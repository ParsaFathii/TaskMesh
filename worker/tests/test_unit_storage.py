"""Unit tests: result storage (path safety, inline/file decision, orphans)."""

from __future__ import annotations

import hashlib
import json
import os
import time
import uuid as uuidlib

import pytest

from taskmesh_worker.models import MAX_INLINE_BYTES
from taskmesh_worker.storage import ResultRecord, ResultStorage, StorageError


def test_small_result_is_inline(storage: ResultStorage):
    job_id = str(uuidlib.uuid4())
    record = storage.store_result(job_id, {"a": 1})
    assert record.kind == "inline"
    assert record.inline == {"a": 1}
    assert record.file_path is None
    assert record.size_bytes <= MAX_INLINE_BYTES
    assert (
        record.sha256
        == hashlib.sha256(
            json.dumps({"a": 1}, separators=(",", ":"), ensure_ascii=False).encode()
        ).hexdigest()
    )


def test_large_result_becomes_file(storage: ResultStorage):
    job_id = str(uuidlib.uuid4())
    payload = {"data": "x" * 40000}
    record = storage.store_result(job_id, payload)
    assert record.kind == "file"
    assert record.inline is None
    assert record.file_path == f"results/{job_id}.json"
    assert record.size_bytes > MAX_INLINE_BYTES
    stored = storage.read_file(record.file_path or "")
    assert json.loads(stored) == payload
    assert record.sha256 == hashlib.sha256(stored).hexdigest()


def test_artifact_written_as_bin(storage: ResultStorage):
    job_id = str(uuidlib.uuid4())
    data = b"\x89PNG-somebytes"
    record = storage.store_artifact(job_id, data)
    assert record == ResultRecord(
        kind="file",
        inline=None,
        file_path=f"results/{job_id}.bin",
        size_bytes=len(data),
        sha256=hashlib.sha256(data).hexdigest(),
    )
    assert storage.read_file(record.file_path or "") == data


@pytest.mark.parametrize(
    "job_id",
    [
        "../../etc/passwd",
        "/etc/passwd",
        "deadbeef-not-a-uuid",
        "a/b/c",
        "",
        ".",
    ],
)
def test_traversal_job_ids_rejected(storage: ResultStorage, job_id: str):
    with pytest.raises(StorageError):
        storage.store_artifact(job_id, b"data")


def test_traversal_on_read_rejected(storage: ResultStorage):
    with pytest.raises(StorageError):
        storage.read_file("results/../../etc/passwd")
    with pytest.raises(StorageError):
        storage.read_file("../worker/pyproject.toml")


def test_unlisted_extension_rejected(storage: ResultStorage):
    with pytest.raises(StorageError):
        storage.store_artifact(str(uuidlib.uuid4()), b"x", ext="exe")


def test_cleanup_orphans_respects_age_and_known_ids(storage: ResultStorage):
    fresh_known = str(uuidlib.uuid4())
    stale_known = str(uuidlib.uuid4())
    stale_unknown = str(uuidlib.uuid4())
    fresh_unknown = str(uuidlib.uuid4())

    for job_id in (fresh_known, stale_known, stale_unknown, fresh_unknown):
        storage.store_artifact(job_id, b"payload")

    old = time.time() - 7200
    for job_id in (stale_known, stale_unknown):
        os.utime(storage.results_root / f"{job_id}.bin", (old, old))

    removed = storage.cleanup_orphans({stale_known, fresh_known}, min_age_s=3600)
    assert removed == [f"results/{stale_unknown}.bin"]
    assert (storage.results_root / f"{stale_known}.bin").exists()
    assert (storage.results_root / f"{fresh_known}.bin").exists()
    assert (storage.results_root / f"{fresh_unknown}.bin").exists()
    assert not (storage.results_root / f"{stale_unknown}.bin").exists()


def test_cleanup_skips_non_uuid_junk_files(storage: ResultStorage):
    junk = storage.results_root / "notes.txt"
    junk.write_text("stray file")
    old = time.time() - 7200
    os.utime(junk, (old, old))
    removed = storage.cleanup_orphans(set(), min_age_s=0)
    # junk files are not UUID job results; they are left alone
    assert removed == []
    assert junk.exists()
