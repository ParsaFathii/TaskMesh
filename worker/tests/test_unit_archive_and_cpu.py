"""Unit tests: archive_inspection and cpu_benchmark handlers (no database)."""

from __future__ import annotations

import base64
import io
import zipfile

import pytest

from taskmesh_worker.handlers import JobCancelled, ValidationError
from taskmesh_worker.handlers.archive_inspection import (
    ArchiveInspectionHandler,
    _iso_modified,
)
from taskmesh_worker.handlers.cpu_benchmark import CpuBenchmarkHandler


@pytest.fixture()
def handler() -> ArchiveInspectionHandler:
    return ArchiveInspectionHandler()


@pytest.fixture()
def cpu_handler() -> CpuBenchmarkHandler:
    return CpuBenchmarkHandler()


def zip_b64(entries: dict[str, bytes]) -> str:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
        archive.writestr("dir/", b"")
    return base64.b64encode(buffer.getvalue()).decode("ascii")


class TestArchiveInspection:
    def test_exact_result_shape(self, handler, job_ctx):
        archive = zip_b64({"a.txt": b"hello", "b.txt": b"x" * 1000})
        result = handler.run({"archiveBase64": archive}, job_ctx)
        assert result["format"] == "zip"
        assert result["totalEntries"] == 3  # a.txt, b.txt, dir/
        assert result["totalUncompressedBytes"] == 5 + 1000
        assert result["truncated"] is False
        assert len(result["entries"]) == 3
        by_name = {entry["name"]: entry for entry in result["entries"]}
        assert by_name["a.txt"]["size"] == 5
        assert by_name["a.txt"]["isDir"] is False
        assert by_name["dir/"]["isDir"] is True
        assert by_name["dir/"]["size"] == 0
        for entry in result["entries"]:
            assert set(entry) == {"name", "size", "compressedSize", "isDir", "modified"}
        total_compressed = sum(e["compressedSize"] for e in result["entries"])
        expected_ratio = round((5 + 1000) / total_compressed, 4)
        assert result["compressionRatio"] == expected_ratio

    def test_max_entries_truncates_with_flag(self, handler, job_ctx):
        archive = zip_b64({"a.txt": b"1", "b.txt": b"2", "c.txt": b"3"})
        result = handler.run({"archiveBase64": archive, "maxEntries": 2}, job_ctx)
        assert len(result["entries"]) == 2
        assert result["truncated"] is True
        assert result["totalEntries"] == 4  # + dir/

    def test_modified_timestamp_format(self):
        assert _iso_modified((2026, 1, 2, 3, 4, 5)) == "2026-01-02T03:04:05"

    def test_not_a_zip(self, handler, job_ctx):
        with pytest.raises(ValidationError, match="zip"):
            handler.run({"archiveBase64": base64.b64encode(b"plain text").decode()}, job_ctx)

    def test_invalid_base64(self, handler, job_ctx):
        with pytest.raises(ValidationError, match="base64"):
            handler.run({"archiveBase64": "@@@"}, job_ctx)

    def test_bad_max_entries(self, handler, job_ctx):
        with pytest.raises(ValidationError):
            handler.run({"archiveBase64": zip_b64({"a": b"1"}), "maxEntries": 0}, job_ctx)

    def test_extra_field_forbidden(self, handler, job_ctx):
        with pytest.raises(ValidationError):
            handler.run({"archiveBase64": zip_b64({"a": b"1"}), "deep": True}, job_ctx)

    def test_empty_zip(self, handler, job_ctx):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w"):
            pass
        result = handler.run(
            {"archiveBase64": base64.b64encode(buffer.getvalue()).decode()}, job_ctx
        )
        assert result["totalEntries"] == 0
        assert result["entries"] == []
        assert result["compressionRatio"] == 0.0
        assert result["truncated"] is False


class TestCpuBenchmark:
    def test_primes_workload(self, cpu_handler, job_ctx):
        result = cpu_handler.run({"workload": "primes", "durationSeconds": 1}, job_ctx)
        assert result["workload"] == "primes"
        assert result["operations"] > 1000
        assert 900 <= result["durationMs"] <= 3000
        assert result["opsPerSecond"] > 0
        assert result["threads"] == 1
        assert set(result) == {"workload", "operations", "durationMs", "opsPerSecond", "threads"}
        assert job_ctx.progress_calls  # type: ignore[attr-defined]

    def test_matrix_workload(self, cpu_handler, job_ctx):
        result = cpu_handler.run({"workload": "matrix", "durationSeconds": 1}, job_ctx)
        assert result["workload"] == "matrix"
        assert result["operations"] >= 0
        assert 900 <= result["durationMs"] <= 3000

    def test_duration_bounds(self, cpu_handler, job_ctx):
        for seconds in (0, -1, 31, 100):
            with pytest.raises(ValidationError):
                cpu_handler.run({"workload": "primes", "durationSeconds": seconds}, job_ctx)

    def test_unknown_workload(self, cpu_handler, job_ctx):
        with pytest.raises(ValidationError):
            cpu_handler.run({"workload": "gpu", "durationSeconds": 2}, job_ctx)

    def test_default_duration_is_five_seconds(self, cpu_handler, job_ctx):
        parsed = cpu_handler.validate({"workload": "primes"})
        assert parsed.durationSeconds == 5

    def test_progress_stays_in_range(self, cpu_handler, job_ctx):
        cpu_handler.run({"workload": "primes", "durationSeconds": 1}, job_ctx)
        for pct, _message in job_ctx.progress_calls:  # type: ignore[attr-defined]
            assert 0 <= pct <= 100

    def test_cooperative_cancellation(self, cpu_handler, storage):
        from taskmesh_worker.handlers import CancelState, JobContext

        ctx = JobContext(job_id="x", storage=storage, cancel=CancelState(True))
        with pytest.raises(JobCancelled):
            cpu_handler.run({"workload": "primes", "durationSeconds": 5}, ctx)
