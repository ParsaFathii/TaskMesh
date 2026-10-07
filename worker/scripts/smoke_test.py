"""End-to-end smoke test of the TaskMesh worker against taskmesh_worker_test.

Single driver, real process: opens a LISTEN connection, seeds a QUEUED
``text_statistics`` job via SQL, spawns a real worker process (uvicorn
control API on :9100), watches the job reach SUCCEEDED, verifies
job_results / job_logs / job_attempts / NOTIFY payloads / the workers row,
curls /health, then stops the worker with SIGTERM and asserts exit code 0.

Run inside the worker venv:  python scripts/smoke_test.py
"""

from __future__ import annotations

import json
import os
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import psycopg
from psycopg.rows import dict_row

ROOT = Path(__file__).resolve().parent.parent
DB_URL = "postgresql://taskmesh@localhost:5433/taskmesh_worker_test"
CONTROL_PORT = 9100
VENV_BIN = ROOT / ".venv" / "bin"


def wait_for_health(timeout_s: float = 15.0) -> dict:
    deadline = time.monotonic() + timeout_s
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(
                f"http://127.0.0.1:{CONTROL_PORT}/health", timeout=2
            ) as response:
                return json.load(response)
        except (urllib.error.URLError, OSError) as exc:
            last_error = exc
            time.sleep(0.25)
    raise RuntimeError(f"control API never became healthy: {last_error}")


def main() -> int:
    listener = psycopg.connect(DB_URL, autocommit=True)
    listener.execute("LISTEN taskmesh_events")
    conn = psycopg.connect(DB_URL, autocommit=True, row_factory=dict_row)

    # clean slate + seed a QUEUED text_statistics job (before the worker starts,
    # so the TRUNCATE can never delete a live worker row)
    conn.execute(
        "TRUNCATE job_results, job_attempts, job_logs, jobs, projects, users, workers"
        " RESTART IDENTITY CASCADE"
    )
    user_id = conn.execute(
        "INSERT INTO users (username, email, password_hash)"
        " VALUES ('smoke', 'smoke@example.test', 'x') RETURNING id"
    ).fetchone()["id"]
    project_id = conn.execute(
        "INSERT INTO projects (name, owner_id) VALUES ('smoke-project', %s) RETURNING id",
        (user_id,),
    ).fetchone()["id"]
    job_id = str(
        conn.execute(
            """
            INSERT INTO jobs (project_id, owner_id, type, priority, payload, status,
                              timeout_seconds)
            VALUES (%s, %s, 'text_statistics', 'NORMAL', %s::jsonb, 'QUEUED', 60)
            RETURNING id
            """,
            (
                project_id,
                user_id,
                json.dumps({"text": "the quick brown fox jumps over the lazy dog"}),
            ),
        ).fetchone()["id"]
    )
    print(f"SEEDED job_id={job_id} (text_statistics, QUEUED)", flush=True)

    # spawn the real worker process
    env = {
        **os.environ,
        "TASKMESH_DATABASE_URL": DB_URL,
        "TASKMESH_CONTROL_PORT": str(CONTROL_PORT),
        "TASKMESH_HEARTBEAT_INTERVAL_S": "2",
        "TASKMESH_LEASE_MARGIN_S": "15",
        "TASKMESH_STORAGE_DIR": str(ROOT / "data"),
        "TASKMESH_WORKER_NAME": "smoke-worker-1",
    }
    log_path = Path("/tmp/worker-smoke.log")
    with log_path.open("w") as log_file:
        worker = subprocess.Popen(
            ["taskmesh-worker"],
            cwd=str(ROOT),
            env=env,
            stdout=log_file,
            stderr=subprocess.STDOUT,
        )
        try:
            health = wait_for_health()
            print(f"HEALTH(before)  {json.dumps(health, sort_keys=True)}", flush=True)

            # watch the job reach a terminal state
            deadline = time.monotonic() + 60
            row: dict | None = None
            while time.monotonic() < deadline:
                row = conn.execute(
                    "SELECT status, progress, worker_id FROM jobs WHERE id = %s", (job_id,)
                ).fetchone()
                if row and row["status"] in ("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT"):
                    break
                time.sleep(0.25)
            assert row is not None, "job vanished"
            print(f"FINAL           status={row['status']} progress={row['progress']}", flush=True)
            if row["status"] != "SUCCEEDED":
                error = conn.execute(
                    "SELECT last_error FROM jobs WHERE id = %s", (job_id,)
                ).fetchone()["last_error"]
                print(f"unexpected terminal state: {row['status']} ({error})", flush=True)
                return 1

            result = conn.execute(
                """
                SELECT kind, inline, size_bytes, checksum_sha256
                FROM job_results WHERE job_id = %s
                """,
                (job_id,),
            ).fetchone()
            assert result is not None, "job_results row missing"
            assert result["kind"] == "inline"
            print(
                f"RESULT          kind={result['kind']} size={result['size_bytes']}"
                f" sha256={result['checksum_sha256'][:16]}...",
                flush=True,
            )
            print(f"RESULT inline   {json.dumps(result['inline'], sort_keys=True)}", flush=True)

            logs = conn.execute(
                "SELECT level, message FROM job_logs WHERE job_id = %s ORDER BY id", (job_id,)
            ).fetchall()
            print(f"JOB LOGS ({len(logs)}):", flush=True)
            for entry in logs:
                print(f"  [{entry['level']}] {entry['message']}", flush=True)

            attempts = conn.execute(
                "SELECT attempt_number, outcome, worker_id FROM job_attempts WHERE job_id = %s",
                (job_id,),
            ).fetchall()
            print(
                f"ATTEMPTS        {[(a['attempt_number'], a['outcome']) for a in attempts]}",
                flush=True,
            )
            assert attempts and attempts[0]["outcome"] == "SUCCEEDED"

            events: list[dict] = []
            drain_deadline = time.monotonic() + 3
            while time.monotonic() < drain_deadline:
                events.extend(json.loads(item.payload) for item in listener.notifies(timeout=0.5))
            job_events = [e for e in events if e.get("t") == "job" and e.get("id") == job_id]
            print(
                f"NOTIFY          total={len(events)}"
                f" job-events={[(e['s'], e.get('p')) for e in job_events]}",
                flush=True,
            )
            assert any(e["s"] == "SUCCEEDED" for e in job_events), "no SUCCEEDED notify"
            assert any(e["s"] == "RUNNING" for e in job_events), "no RUNNING notify"

            worker_row = conn.execute(
                "SELECT name, status, current_job_id FROM workers WHERE id = %s",
                (row["worker_id"],),
            ).fetchone()
            print(
                f"WORKER ROW      name={worker_row['name']} status={worker_row['status']}"
                f" current_job_id={worker_row['current_job_id']}",
                flush=True,
            )
            assert worker_row["status"] == "IDLE"
            assert worker_row["current_job_id"] is None

            with urllib.request.urlopen(
                f"http://127.0.0.1:{CONTROL_PORT}/health", timeout=5
            ) as response:
                health_after = json.load(response)
            print(f"HEALTH(after)   {json.dumps(health_after, sort_keys=True)}", flush=True)

            # graceful shutdown: SIGTERM -> drain -> exit 0
            worker.send_signal(signal.SIGTERM)
            exit_code = worker.wait(timeout=30)
            print(f"WORKER EXIT     code={exit_code} (SIGTERM drain)", flush=True)
            assert exit_code == 0, f"expected exit 0, got {exit_code}"

            offline = conn.execute(
                "SELECT status FROM workers WHERE id = %s", (row["worker_id"],)
            ).fetchone()
            print(f"WORKER ROW      status after drain={offline['status']}", flush=True)
            assert offline["status"] == "OFFLINE"
        finally:
            if worker.poll() is None:
                worker.kill()
                worker.wait(timeout=10)

    print("== worker stdout (JSON lines) ==")
    print(log_path.read_text(), end="")

    listener.close()
    conn.close()
    print("SMOKE OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
