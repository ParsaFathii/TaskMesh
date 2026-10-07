"""Integration tests: full job lifecycle against taskmesh_worker_test."""

from __future__ import annotations

import hashlib
import json
import threading
import time

import pytest

from conftest import (
    attempts,
    insert_job,
    job_logs,
    job_result,
    job_row,
    seed_user_and_project,
    worker_row,
)
from stub_handlers import BoomHandler, ProgressHandler, WaitHandler
from taskmesh_worker.registry import HandlerRegistry

TEXT_STATS_RESULT = {
    "characters": 11,
    "charactersNoSpaces": 10,
    "words": 2,
    "uniqueWords": 2,
    "lines": 1,
    "paragraphs": 1,
    "avgWordLength": 5.0,
    "readingTimeSeconds": 0.6,
    "topWords": [{"word": "hello", "count": 1}, {"word": "world", "count": 1}],
}


@pytest.fixture()
def boom_runtime(make_runtime):
    registry = HandlerRegistry()
    registry.register(BoomHandler)
    return make_runtime(registry=registry)


@pytest.fixture()
def wait_runtime(make_runtime):
    registry = HandlerRegistry()
    registry.register(WaitHandler)
    return make_runtime(registry=registry)


def test_success_with_inline_result(make_runtime, clean_db):
    owner_id, project_id = seed_user_and_project(clean_db)
    job_id = insert_job(clean_db, project_id, owner_id, "text_statistics", {"text": "hello world"})

    runtime = make_runtime()
    runtime.register()
    job = runtime._claim()
    assert job is not None
    runtime._process(job)

    row = job_row(clean_db, job_id)
    assert row["status"] == "SUCCEEDED"
    assert row["progress"] == 100
    assert row["completed_at"] is not None
    assert row["last_error"] is None

    result = job_result(clean_db, job_id)
    assert result is not None
    assert result["kind"] == "inline"
    assert result["inline"] == TEXT_STATS_RESULT
    assert result["file_path"] is None
    expected_serialized = json.dumps(
        TEXT_STATS_RESULT, separators=(",", ":"), ensure_ascii=False
    ).encode()
    assert result["size_bytes"] == len(expected_serialized)
    assert result["checksum_sha256"] == hashlib.sha256(expected_serialized).hexdigest()

    job_attempts = attempts(clean_db, job_id)
    assert len(job_attempts) == 1
    assert job_attempts[0]["attempt_number"] == 1
    assert job_attempts[0]["outcome"] == "SUCCEEDED"
    assert str(job_attempts[0]["worker_id"]) == runtime.worker_id
    assert job_attempts[0]["finished_at"] is not None

    logs = job_logs(clean_db, job_id)
    messages = [entry["message"] for entry in logs]
    assert any("claimed" in message for message in messages)
    assert any("succeeded" in message for message in messages)


def test_progress_pipeline_updates_job_logs_and_progress(make_runtime, clean_db):
    owner_id, project_id = seed_user_and_project(clean_db)
    registry = HandlerRegistry()
    registry.register(ProgressHandler)
    runtime = make_runtime(registry=registry)
    runtime.register()

    job_id = insert_job(clean_db, project_id, owner_id, "test_progress", {"steps": 4})
    job = runtime._claim()
    assert job is not None
    runtime._process(job)

    row = job_row(clean_db, job_id)
    assert row["status"] == "SUCCEEDED"
    assert row["progress"] == 100
    progress_logs = [
        entry for entry in job_logs(clean_db, job_id) if entry["message"].startswith("step")
    ]
    assert len(progress_logs) == 4


def test_invalid_payload_fails_without_retry(make_runtime, clean_db):
    owner_id, project_id = seed_user_and_project(clean_db)
    job_id = insert_job(
        clean_db, project_id, owner_id, "text_statistics", {"text": 123}, max_retries=3
    )

    runtime = make_runtime()
    runtime.register()
    job = runtime._claim()
    assert job is not None
    runtime._process(job)

    row = job_row(clean_db, job_id)
    assert row["status"] == "FAILED"
    assert row["retry_count"] == 0  # non-retryable: no retry consumed
    assert row["completed_at"] is not None
    assert "invalid payload" in row["last_error"]
    job_attempts = attempts(clean_db, job_id)
    assert len(job_attempts) == 1
    assert job_attempts[0]["outcome"] == "FAILED"


def test_transient_failure_retries_with_backoff(boom_runtime, clean_db):
    owner_id, project_id = seed_user_and_project(clean_db)
    job_id = insert_job(
        clean_db,
        project_id,
        owner_id,
        "test_boom",
        {"failMode": "transient"},
        max_retries=2,
    )
    runtime = boom_runtime
    runtime.register()

    job = runtime._claim()
    assert job is not None
    runtime._process(job)
    row = job_row(clean_db, job_id)
    assert row["status"] == "RETRYING"
    assert row["retry_count"] == 1
    assert row["completed_at"] is None
    now = clean_db.execute("SELECT now() AS now")[0]["now"]
    delay = (row["available_at"] - now).total_seconds()
    assert 4.0 <= delay <= 6.5, f"expected ~5s backoff, got {delay}"
    assert attempts(clean_db, job_id)[0]["outcome"] == "FAILED"

    # second attempt: backoff doubles
    clean_db.execute(
        "UPDATE jobs SET available_at = now() - interval '1 second' WHERE id = %s",
        (job_id,),
    )
    job = runtime._claim()
    assert job is not None
    runtime._process(job)
    row = job_row(clean_db, job_id)
    assert row["status"] == "RETRYING"
    assert row["retry_count"] == 2
    now = clean_db.execute("SELECT now() AS now")[0]["now"]
    delay = (row["available_at"] - now).total_seconds()
    assert 9.0 <= delay <= 11.5, f"expected ~10s backoff, got {delay}"

    # third attempt: retries exhausted -> FAILED
    clean_db.execute(
        "UPDATE jobs SET available_at = now() - interval '1 second' WHERE id = %s",
        (job_id,),
    )
    job = runtime._claim()
    assert job is not None
    runtime._process(job)
    row = job_row(clean_db, job_id)
    assert row["status"] == "FAILED"
    assert row["retry_count"] == 2
    assert row["completed_at"] is not None
    all_attempts = attempts(clean_db, job_id)
    assert [a["attempt_number"] for a in all_attempts] == [1, 2, 3]
    assert all(a["outcome"] == "FAILED" for a in all_attempts)


def test_transient_failure_fails_immediately_when_max_retries_zero(boom_runtime, clean_db):
    owner_id, project_id = seed_user_and_project(clean_db)
    job_id = insert_job(
        clean_db,
        project_id,
        owner_id,
        "test_boom",
        {"failMode": "transient"},
        max_retries=0,
    )
    runtime = boom_runtime
    runtime.register()
    job = runtime._claim()
    assert job is not None
    runtime._process(job)
    row = job_row(clean_db, job_id)
    assert row["status"] == "FAILED"
    assert len(attempts(clean_db, job_id)) == 1


def test_queued_job_cancelled_before_claim(make_runtime, clean_db):
    owner_id, project_id = seed_user_and_project(clean_db)
    job_id = insert_job(
        clean_db,
        project_id,
        owner_id,
        "text_statistics",
        {"text": "x"},
        cancel_requested=True,
    )
    runtime = make_runtime()
    runtime.register()
    runtime._cancel_requested_queued_jobs()

    row = job_row(clean_db, job_id)
    assert row["status"] == "CANCELLED"
    assert row["completed_at"] is not None
    job_attempts = attempts(clean_db, job_id)
    assert len(job_attempts) == 1
    assert job_attempts[0]["outcome"] == "CANCELLED"
    assert "before claim" in job_attempts[0]["error"]


def test_cancelled_after_claim_finalizes_cancelled(make_runtime, clean_db):
    owner_id, project_id = seed_user_and_project(clean_db)
    job_id = insert_job(clean_db, project_id, owner_id, "text_statistics", {"text": "x"})
    runtime = make_runtime()
    runtime.register()
    job = runtime._claim()
    assert job is not None

    clean_db.execute("UPDATE jobs SET cancel_requested = true WHERE id = %s", (job_id,))
    runtime._process(job)

    row = job_row(clean_db, job_id)
    assert row["status"] == "CANCELLED"
    assert row["completed_at"] is not None
    job_attempts = attempts(clean_db, job_id)
    assert job_attempts[0]["outcome"] == "CANCELLED"
    assert job_result(clean_db, job_id) is None


def test_cancel_during_execution_cooperative(wait_runtime, clean_db):
    owner_id, project_id = seed_user_and_project(clean_db)
    job_id = insert_job(
        clean_db,
        project_id,
        owner_id,
        "test_wait",
        {"seconds": 10},
        timeout_seconds=60,
    )
    runtime = wait_runtime
    runtime.register()
    job = runtime._claim()
    assert job is not None

    thread = threading.Thread(target=lambda: runtime._process(job))
    thread.start()
    time.sleep(0.5)
    clean_db.execute("UPDATE jobs SET cancel_requested = true WHERE id = %s", (job_id,))
    thread.join(timeout=30)
    assert not thread.is_alive()

    row = job_row(clean_db, job_id)
    assert row["status"] == "CANCELLED"
    job_attempts = attempts(clean_db, job_id)
    assert job_attempts[0]["outcome"] == "CANCELLED"
    # worker row released the job
    assert worker_row(clean_db, runtime.worker_id)["current_job_id"] is None


def test_timeout_marks_timed_out_and_retries(make_runtime, clean_db):
    owner_id, project_id = seed_user_and_project(clean_db)
    job_id = insert_job(
        clean_db,
        project_id,
        owner_id,
        "cpu_benchmark",
        {"workload": "primes", "durationSeconds": 30},
        timeout_seconds=5,
        max_retries=3,
    )
    runtime = make_runtime()
    runtime.register()
    job = runtime._claim()
    assert job is not None

    started = time.monotonic()
    runtime._process(job)
    elapsed = time.monotonic() - started
    assert elapsed < 12, "timeout must finalize well before the workload finishes"

    row = job_row(clean_db, job_id)
    assert row["status"] == "RETRYING"
    assert row["retry_count"] == 1
    assert "timed out" in row["last_error"]
    job_attempts = attempts(clean_db, job_id)
    assert job_attempts[0]["outcome"] == "TIMED_OUT"
    assert job_result(clean_db, job_id) is None


def test_timeout_exhausted_marks_timed_out(make_runtime, clean_db):
    owner_id, project_id = seed_user_and_project(clean_db)
    job_id = insert_job(
        clean_db,
        project_id,
        owner_id,
        "cpu_benchmark",
        {"workload": "primes", "durationSeconds": 30},
        timeout_seconds=5,
        max_retries=0,
    )
    runtime = make_runtime()
    runtime.register()
    job = runtime._claim()
    runtime._process(job)

    row = job_row(clean_db, job_id)
    assert row["status"] == "TIMED_OUT"
    assert row["completed_at"] is not None
    assert attempts(clean_db, job_id)[0]["outcome"] == "TIMED_OUT"


def test_heartbeat_updates_worker_row_and_renews_lease(make_runtime, clean_db):
    owner_id, project_id = seed_user_and_project(clean_db)
    job_id = insert_job(clean_db, project_id, owner_id, "text_statistics", {"text": "x"})
    runtime = make_runtime()
    runtime.register()

    before = worker_row(clean_db, runtime.worker_id)
    time.sleep(0.05)
    job = runtime._claim()
    assert job is not None
    runtime.current_job = job

    runtime._heartbeat_once()
    busy = worker_row(clean_db, runtime.worker_id)
    assert busy["status"] == "BUSY"
    assert str(busy["current_job_id"]) == job_id
    assert busy["last_heartbeat"] > before["last_heartbeat"]

    lease = job_row(clean_db, job_id)["lease_expires_at"]
    assert lease is not None
    assert lease > clean_db.execute("SELECT now() AS now")[0]["now"]

    runtime.current_job = None
    runtime._heartbeat_once()
    idle = worker_row(clean_db, runtime.worker_id)
    assert idle["status"] == "IDLE"
    assert idle["current_job_id"] is None


def test_worker_row_offline_after_drain(make_runtime, clean_db):
    runtime = make_runtime()
    runtime.register()
    runtime.shutdown.request_drain()
    assert runtime.run() == 0
    assert worker_row(clean_db, runtime.worker_id)["status"] == "OFFLINE"


def test_unexpected_handler_error_is_retryable(make_runtime, clean_db):
    from taskmesh_worker.handlers import JobHandler

    class CrashyHandler(JobHandler):
        job_type = "test_crash"

        def validate(self, payload):  # type: ignore[override]
            return payload

        def run(self, payload, ctx):  # type: ignore[override]
            raise ZeroDivisionError("1/0")

    registry = HandlerRegistry()
    registry.register(CrashyHandler)
    runtime = make_runtime(registry=registry)
    runtime.register()

    owner_id, project_id = seed_user_and_project(clean_db)
    job_id = insert_job(clean_db, project_id, owner_id, "test_crash", {}, max_retries=2)
    job = runtime._claim()
    assert job is not None
    runtime._process(job)

    row = job_row(clean_db, job_id)
    assert row["status"] == "RETRYING"
    assert "ZeroDivisionError" in row["last_error"]


def test_file_result_for_large_payload(make_runtime, clean_db):
    from pydantic import Field

    from taskmesh_worker.handlers import JobHandler
    from taskmesh_worker.models import StrictModel

    class BigPayload(StrictModel):
        filler: str = Field(min_length=1)

    class BigHandler(JobHandler):
        job_type = "test_big"
        payload_model = BigPayload

        def run(self, payload, ctx):
            parsed = self.validate(payload)
            return {"filler": parsed.filler}

    registry = HandlerRegistry()
    registry.register(BigHandler)
    runtime = make_runtime(registry=registry)
    runtime.register()

    owner_id, project_id = seed_user_and_project(clean_db)
    filler = "y" * 40000
    job_id = insert_job(clean_db, project_id, owner_id, "test_big", {"filler": filler})
    job = runtime._claim()
    assert job is not None
    runtime._process(job)

    result = job_result(clean_db, job_id)
    assert result["kind"] == "file"
    assert result["file_path"] == f"results/{job_id}.json"
    stored = runtime.storage.read_file(result["file_path"])
    assert json.loads(stored)["filler"] == filler
    assert result["checksum_sha256"] == hashlib.sha256(stored).hexdigest()
