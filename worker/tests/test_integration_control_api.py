"""Integration tests: control API (FastAPI /health, /status) and DB helpers.

The control API is exercised through httpx's ASGI transport (in-process, no
socket), the same wire path an external HTTP client takes against the
uvicorn server started by the worker. Async tests run on anyio's asyncio
backend; ``anyio_backend`` is the plugin's default.
"""

from __future__ import annotations

import time

import pytest
from httpx import ASGITransport, AsyncClient

from taskmesh_worker.control_api import create_app
from taskmesh_worker.db import Database
from taskmesh_worker.registry import ALL_JOB_TYPES


@pytest.fixture()
def anyio_backend() -> str:
    return "asyncio"


def async_client(runtime) -> AsyncClient:
    """Async httpx client bound to the control app via ASGITransport."""
    transport = ASGITransport(app=create_app(runtime))
    return AsyncClient(transport=transport, base_url="http://worker")


class TestControlApi:
    @pytest.mark.anyio()
    async def test_health_before_registration(self, make_runtime):
        runtime = make_runtime()
        async with async_client(runtime) as client:
            response = await client.get("/health")
        assert response.status_code == 200
        body = response.json()
        assert body["status"] == "IDLE"
        assert body["workerId"] is None
        assert body["currentJobId"] is None
        assert body["uptimeS"] >= 0

    @pytest.mark.anyio()
    async def test_health_and_status_after_registration(self, make_runtime, clean_db):
        runtime = make_runtime()
        runtime.register()
        async with async_client(runtime) as client:
            health = (await client.get("/health")).json()
            assert health["workerId"] == runtime.worker_id
            assert health["status"] == "IDLE"

            status_response = await client.get("/status")
            assert status_response.status_code == 200
            body = status_response.json()
            # the database row is STARTING until the first heartbeat
            assert body["worker"]["name"] == "test-worker"
            assert body["worker"]["hostname"]
            assert body["worker"]["version"] == "0.1.0"
            assert body["worker"]["status"] == "STARTING"
            assert body["worker"]["capabilities"] == sorted(ALL_JOB_TYPES)
            assert body["worker"]["control_port"] == 9199
            assert body["config"]["controlPort"] == 9199
            assert body["config"]["databaseUrl"].startswith("postgresql://localhost:5433/")

            runtime._heartbeat_once()
            body = (await client.get("/status")).json()
            assert body["worker"]["status"] == "IDLE"

    @pytest.mark.anyio()
    async def test_status_reflects_busy_state(self, make_runtime, clean_db):
        from conftest import insert_job, seed_user_and_project

        runtime = make_runtime()
        runtime.register()
        owner_id, project_id = seed_user_and_project(clean_db)
        insert_job(clean_db, project_id, owner_id, "text_statistics", {"text": "x"})
        job = runtime._claim()
        assert job is not None
        runtime.current_job = job
        try:
            async with async_client(runtime) as client:
                body = (await client.get("/health")).json()
                assert body["status"] == "BUSY"
                assert body["currentJobId"] == str(job.id)
        finally:
            runtime.current_job = None


class TestDatabaseHelpers:
    def test_execute_returns_dict_rows(self, db):
        rows = db.execute("SELECT 1 AS one, 'x' AS letter")
        assert rows == [{"one": 1, "letter": "x"}]

    def test_transaction_commits(self, clean_db):
        clean_db.execute(
            "INSERT INTO users (username, email, password_hash) VALUES ('t1', 't1@x.y', 'h')"
        )
        with clean_db.transaction() as conn:
            conn.execute(
                "INSERT INTO users (username, email, password_hash) VALUES ('t2', 't2@x.y', 'h')"
            )
        count = clean_db.execute("SELECT count(*) AS n FROM users")[0]["n"]
        assert count == 2

    def test_transaction_rolls_back_on_error(self, clean_db):
        with pytest.raises(RuntimeError), clean_db.transaction() as conn:
            conn.execute(
                "INSERT INTO users (username, email, password_hash)"
                " VALUES ('gone', 'gone@x.y', 'h')"
            )
            raise RuntimeError("abort")
        count = clean_db.execute("SELECT count(*) AS n FROM users")[0]["n"]
        assert count == 0

    def test_ensure_connected_retries_with_backoff(self):
        database = Database("postgresql://taskmesh@localhost:59999/no-such-db", connect_timeout_s=2)
        started = time.monotonic()
        with pytest.raises(Exception):  # noqa: B017 - psycopg.OperationalError
            database.ensure_connected(max_attempts=2)
        assert time.monotonic() - started >= 0.5  # first backoff waited
