"""Integration tests: claiming protocol (SKIP LOCKED exclusivity, ordering)."""

from __future__ import annotations

import threading
import time

from conftest import (
    insert_job,
    job_row,
    seed_user_and_project,
)
from taskmesh_worker.db import Database
from taskmesh_worker.registry import HandlerRegistry
from taskmesh_worker.runtime import claim_job

TEST_DB_URL = "postgresql://taskmesh@localhost:5433/taskmesh_worker_test"


def test_sequential_claims_return_distinct_jobs(make_runtime, clean_db):
    owner_id, project_id = seed_user_and_project(clean_db)
    job_a = insert_job(clean_db, project_id, owner_id, "text_statistics", {"text": "a"})
    job_b = insert_job(clean_db, project_id, owner_id, "text_statistics", {"text": "b"})

    runtime = make_runtime()
    runtime.register()
    first = runtime._claim()
    second = runtime._claim()

    assert first is not None and second is not None
    assert str(first.id) != str(second.id)
    assert {str(first.id), str(second.id)} == {job_a, job_b}

    for job_id in (job_a, job_b):
        row = job_row(clean_db, job_id)
        assert row["status"] == "RUNNING"
        assert str(row["worker_id"]) == runtime.worker_id
        assert row["started_at"] is not None
        assert row["queued_at"] is not None
        assert row["lease_expires_at"] is not None


def test_claim_skips_unavailable_jobs(make_runtime, clean_db):
    owner_id, project_id = seed_user_and_project(clean_db)
    insert_job(
        clean_db,
        project_id,
        owner_id,
        "text_statistics",
        {"text": "later"},
        available_at_now=False,
    )
    runtime = make_runtime()
    runtime.register()
    assert runtime._claim() is None


def test_claim_filters_by_capability(make_runtime, clean_db):
    owner_id, project_id = seed_user_and_project(clean_db)
    insert_job(clean_db, project_id, owner_id, "text_statistics", {"text": "x"})

    from stub_handlers import BoomHandler

    limited = HandlerRegistry()
    limited.register(BoomHandler)
    runtime = make_runtime(registry=limited)
    runtime.register()
    assert runtime._claim() is None  # text_statistics not in this registry

    boom_job = insert_job(clean_db, project_id, owner_id, "test_boom", {"failMode": "permanent"})
    assert str(runtime._claim().id) == boom_job  # type: ignore[union-attr]


def test_priority_orders_claims(make_runtime, clean_db):
    owner_id, project_id = seed_user_and_project(clean_db)
    low = insert_job(
        clean_db, project_id, owner_id, "text_statistics", {"text": "l"}, priority="LOW"
    )
    normal = insert_job(
        clean_db, project_id, owner_id, "text_statistics", {"text": "n"}, priority="NORMAL"
    )
    critical = insert_job(
        clean_db, project_id, owner_id, "text_statistics", {"text": "c"}, priority="CRITICAL"
    )

    runtime = make_runtime()
    runtime.register()
    assert str(runtime._claim().id) == critical  # type: ignore[union-attr]
    assert str(runtime._claim().id) == normal  # type: ignore[union-attr]
    assert str(runtime._claim().id) == low  # type: ignore[union-attr]


def test_same_priority_claims_oldest_first(make_runtime, clean_db):
    owner_id, project_id = seed_user_and_project(clean_db)
    first = insert_job(clean_db, project_id, owner_id, "text_statistics", {"text": "1"})
    time.sleep(0.01)
    second = insert_job(clean_db, project_id, owner_id, "text_statistics", {"text": "2"})

    runtime = make_runtime()
    runtime.register()
    assert str(runtime._claim().id) == first  # type: ignore[union-attr]
    assert str(runtime._claim().id) == second  # type: ignore[union-attr]


def test_concurrent_claims_are_exclusive(clean_db, database_url):
    owner_id, project_id = seed_user_and_project(clean_db)
    job_ids = {
        insert_job(clean_db, project_id, owner_id, "text_statistics", {"text": str(i)})
        for i in range(6)
    }

    worker_ids = [
        clean_db.execute(
            "INSERT INTO workers (name, hostname, version) VALUES (%s, 'h', 'test') RETURNING id",
            (f"concurrent-{i}",),
        )[0]["id"]
        for i in range(4)
    ]

    claimed: list[str] = []
    barrier = threading.Barrier(4)

    def claimer(worker_id) -> None:
        database = Database(database_url)
        database.ensure_connected(max_attempts=3)
        try:
            barrier.wait()
            job = claim_job(database, worker_id, ["text_statistics"])
            if job is not None:
                claimed.append(str(job.id))
        finally:
            database.close()

    threads = [threading.Thread(target=claimer, args=(wid,)) for wid in worker_ids]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=30)

    assert len(claimed) == 4
    assert len(set(claimed)) == 4
    assert set(claimed) <= job_ids
