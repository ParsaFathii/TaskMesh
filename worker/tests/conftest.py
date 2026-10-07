"""Shared fixtures: test database (taskmesh_worker_test), storage, contexts."""

from __future__ import annotations

import os
import uuid as uuidlib
from collections.abc import Callable, Generator
from pathlib import Path

import psycopg
import pytest
from psycopg.rows import dict_row

from taskmesh_worker.config import WorkerConfig
from taskmesh_worker.db import Database
from taskmesh_worker.handlers import CancelState, JobContext
from taskmesh_worker.registry import ALL_JOB_TYPES, HandlerRegistry
from taskmesh_worker.runtime import WorkerRuntime
from taskmesh_worker.shutdown import ShutdownCoordinator
from taskmesh_worker.storage import ResultStorage

PG_HOST = os.environ.get("TASKMESH_TEST_PG_HOST", "localhost")
PG_PORT = int(os.environ.get("TASKMESH_TEST_PG_PORT", "5433"))
TEST_DB = "taskmesh_worker_test"
TEST_DB_URL = f"postgresql://taskmesh@{PG_HOST}:{PG_PORT}/{TEST_DB}"
ADMIN_DB_URL = f"postgresql://taskmesh@{PG_HOST}:{PG_PORT}/postgres"
SCHEMA_PATH = Path(__file__).parent / "fixtures" / "schema.sql"

TABLES = "users, projects, workers, jobs, job_attempts, job_logs, job_results, audit_logs"
TYPES = "job_status, job_priority, user_role, worker_status, log_level, result_kind"


@pytest.fixture(scope="session")
def database_url() -> str:
    """Create the dedicated test database and apply the SPEC §4 schema."""
    admin = psycopg.connect(ADMIN_DB_URL, autocommit=True)
    try:
        exists = admin.execute(
            "SELECT 1 FROM pg_database WHERE datname = %s", (TEST_DB,)
        ).fetchone()
        if not exists:
            admin.execute(f'CREATE DATABASE "{TEST_DB}"')
    finally:
        admin.close()

    conn = psycopg.connect(TEST_DB_URL, autocommit=True, row_factory=dict_row)
    try:
        conn.execute(f"DROP TABLE IF EXISTS {TABLES} CASCADE")
        conn.execute(f"DROP TYPE IF EXISTS {TYPES} CASCADE")
        for statement in SCHEMA_PATH.read_text().split(";"):
            if statement.strip():
                conn.execute(statement)
    finally:
        conn.close()
    return TEST_DB_URL


@pytest.fixture()
def db(database_url: str) -> Generator[Database, None, None]:
    database = Database(database_url)
    database.ensure_connected(max_attempts=3)
    yield database
    database.close()


@pytest.fixture()
def clean_db(db: Database) -> Database:
    db.execute(f"TRUNCATE TABLE {TABLES} RESTART IDENTITY CASCADE")
    return db


@pytest.fixture()
def storage(tmp_path: Path) -> ResultStorage:
    return ResultStorage(tmp_path / "data")


@pytest.fixture()
def job_ctx(storage: ResultStorage) -> JobContext:
    """A job context with a recording progress callback and no database."""
    calls: list[tuple[int, str | None]] = []
    ctx = JobContext(
        job_id=str(uuidlib.uuid4()),
        storage=storage,
        cancel=CancelState(),
        progress=lambda pct, message=None: calls.append((pct, message)),
    )
    ctx.progress_calls = calls  # type: ignore[attr-defined]
    return ctx


@pytest.fixture()
def worker_config(tmp_path: Path) -> WorkerConfig:
    return WorkerConfig(
        database_url=TEST_DB_URL,
        capabilities=list(ALL_JOB_TYPES),
        heartbeat_interval_s=1,
        control_port=9199,
        storage_dir=tmp_path / "data",
        worker_name="test-worker",
    )


@pytest.fixture()
def make_runtime(worker_config: WorkerConfig, clean_db: Database) -> Callable[..., WorkerRuntime]:
    """Factory for a runtime bound to the clean test database."""

    def _make(registry: HandlerRegistry | None = None) -> WorkerRuntime:
        return WorkerRuntime(
            worker_config,
            clean_db,
            registry or HandlerRegistry.default(),
            ResultStorage(worker_config.storage_dir),
            ShutdownCoordinator(),
        )

    return _make


# -- SQL seeding helpers ------------------------------------------------------


def seed_user_and_project(db: Database) -> tuple[str, str]:
    suffix = uuidlib.uuid4().hex[:12]
    user_id = db.execute(
        """
        INSERT INTO users (username, email, password_hash, role)
        VALUES (%s, %s, 'bcrypt-test-hash', 'USER')
        RETURNING id
        """,
        (f"user_{suffix}", f"user_{suffix}@example.test"),
    )[0]["id"]
    project_id = db.execute(
        "INSERT INTO projects (name, owner_id) VALUES (%s, %s) RETURNING id",
        (f"project_{suffix}", user_id),
    )[0]["id"]
    return str(user_id), str(project_id)


def insert_job(
    db: Database,
    project_id: str,
    owner_id: str,
    job_type: str,
    payload: dict,
    *,
    priority: str = "NORMAL",
    status: str = "QUEUED",
    max_retries: int = 3,
    timeout_seconds: int = 120,
    cancel_requested: bool = False,
    available_at_now: bool = True,
) -> str:
    rows = db.execute(
        """
        INSERT INTO jobs (project_id, owner_id, type, priority, payload, status,
                          max_retries, timeout_seconds, cancel_requested, available_at)
        VALUES (%s, %s, %s, %s::job_priority, %s, %s::job_status, %s, %s, %s,
                CASE WHEN %s THEN now() ELSE now() + interval '1 hour' END)
        RETURNING id
        """,
        (
            project_id,
            owner_id,
            job_type,
            priority,
            payload,
            status,
            max_retries,
            timeout_seconds,
            cancel_requested,
            available_at_now,
        ),
    )
    return str(rows[0]["id"])


def job_row(db: Database, job_id: str) -> dict:
    rows = db.execute("SELECT * FROM jobs WHERE id = %s", (job_id,))
    assert rows, f"job {job_id} vanished"
    return rows[0]


def attempts(db: Database, job_id: str) -> list[dict]:
    return db.execute(
        "SELECT * FROM job_attempts WHERE job_id = %s ORDER BY attempt_number",
        (job_id,),
    )


def job_logs(db: Database, job_id: str) -> list[dict]:
    return db.execute("SELECT * FROM job_logs WHERE job_id = %s ORDER BY id", (job_id,))


def job_result(db: Database, job_id: str) -> dict | None:
    rows = db.execute("SELECT * FROM job_results WHERE job_id = %s", (job_id,))
    return rows[0] if rows else None


def worker_row(db: Database, worker_id: str) -> dict:
    rows = db.execute("SELECT * FROM workers WHERE id = %s", (worker_id,))
    assert rows, f"worker {worker_id} vanished"
    return rows[0]
