"""Integration tests: LISTEN/NOTIFY event emission (SPEC §8)."""

from __future__ import annotations

import json
import time

import psycopg

from conftest import insert_job, seed_user_and_project
from taskmesh_worker import notify


def _drain_notifications(listener: psycopg.Connection, deadline_s: float) -> list[dict]:
    """Collect NOTIFY payloads until the deadline."""
    events: list[dict] = []
    deadline = time.monotonic() + deadline_s
    while time.monotonic() < deadline:
        got_one = False
        for item in listener.notifies(timeout=0.25):
            events.append(json.loads(item.payload))
            got_one = True
        if got_one and events:
            return events
    return events


def test_notify_emit_round_trip(db, database_url):
    listener = psycopg.connect(database_url, autocommit=True)
    try:
        listener.execute("LISTEN taskmesh_events")
        notify.job_event(db, "00000000-0000-0000-0000-000000000001", "RUNNING", 42)
        events = _drain_notifications(listener, deadline_s=5)
    finally:
        listener.close()
    assert events
    job_event = events[0]
    assert job_event == {
        "t": "job",
        "id": "00000000-0000-0000-0000-000000000001",
        "s": "RUNNING",
        "p": 42,
    }
    assert len(json.dumps(job_event)) < 200  # SPEC §8: small payloads


def test_notify_worker_and_joblog_events(db, database_url):
    listener = psycopg.connect(database_url, autocommit=True)
    try:
        listener.execute("LISTEN taskmesh_events")
        notify.worker_event(db, "00000000-0000-0000-0000-000000000002", "BUSY", None)
        notify.job_log_event(db, "00000000-0000-0000-0000-000000000003", "WARN", "be careful")
        events = _drain_notifications(listener, deadline_s=5)
    finally:
        listener.close()
    assert {
        "t": "worker",
        "id": "00000000-0000-0000-0000-000000000002",
        "s": "BUSY",
        "j": None,
    } in events
    assert {
        "t": "joblog",
        "id": "00000000-0000-0000-0000-000000000003",
        "l": "WARN",
        "m": "be careful",
    } in events


def test_lifecycle_emits_running_and_succeeded(make_runtime, clean_db, database_url):
    listener = psycopg.connect(database_url, autocommit=True)
    try:
        listener.execute("LISTEN taskmesh_events")
        owner_id, project_id = seed_user_and_project(clean_db)
        job_id = insert_job(
            clean_db, project_id, owner_id, "text_statistics", {"text": "notify me"}
        )
        runtime = make_runtime()
        runtime.register()
        job = runtime._claim()
        assert job is not None
        runtime._process(job)

        deadline = time.monotonic() + 5
        events: list[dict] = []
        while time.monotonic() < deadline:
            events.extend(json.loads(item.payload) for item in listener.notifies(timeout=0.25))
            job_events = [e for e in events if e.get("t") == "job" and e.get("id") == job_id]
            if any(e.get("s") == "SUCCEEDED" for e in job_events):
                break
        statuses = [e["s"] for e in events if e.get("t") == "job" and e.get("id") == job_id]
        assert "RUNNING" in statuses
        assert "SUCCEEDED" in statuses
        log_events = [e for e in events if e.get("t") == "joblog" and e.get("id") == job_id]
        assert log_events, "job log notifications expected"
        worker_events = [
            e for e in events if e.get("t") == "worker" and e.get("id") == runtime.worker_id
        ]
        assert any(e.get("s") == "BUSY" for e in worker_events)
        assert any(e.get("s") in ("IDLE", "DRAINING") for e in worker_events)
    finally:
        listener.close()
