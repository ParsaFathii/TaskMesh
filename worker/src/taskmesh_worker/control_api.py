"""Per-worker FastAPI control endpoint (SPEC §10).

Served by uvicorn in a daemon thread on ``TASKMESH_CONTROL_PORT``:

- ``GET /health`` — liveness plus the in-memory worker view (works even
  when the database is down).
- ``GET /status`` — the full ``workers`` row from the database plus the
  sanitized runtime configuration.
"""

from __future__ import annotations

import threading
import time
from typing import Any, Protocol

import uvicorn
from fastapi import FastAPI, HTTPException

from .db import Database


class WorkerState(Protocol):
    """Runtime attributes exposed to the control API."""

    @property
    def worker_id(self) -> str | None: ...

    @property
    def status(self) -> str: ...

    @property
    def current_job_id(self) -> str | None: ...

    @property
    def uptime_s(self) -> float: ...

    @property
    def config(self) -> Any: ...

    db: Database


def create_app(state: WorkerState) -> FastAPI:
    app = FastAPI(title="TaskMesh Worker Control", version="0.1.0")

    @app.get("/health")
    def health() -> dict[str, Any]:
        return {
            "status": state.status,
            "workerId": state.worker_id,
            "currentJobId": state.current_job_id,
            "uptimeS": round(state.uptime_s, 3),
        }

    @app.get("/status")
    def status() -> dict[str, Any]:
        if state.worker_id is None:
            raise HTTPException(status_code=503, detail="worker not registered yet")
        try:
            rows = state.db.execute("SELECT * FROM workers WHERE id = %s", (state.worker_id,))
        except Exception as exc:
            raise HTTPException(status_code=503, detail=f"database unavailable: {exc}") from exc
        if not rows:
            raise HTTPException(status_code=404, detail="worker row not found")
        return {"worker": rows[0], "config": state.config.safe_view()}

    return app


class ControlServer:
    """Uvicorn server bound to a daemon thread."""

    def __init__(self, state: WorkerState, port: int, host: str = "127.0.0.1") -> None:
        self.port = port
        self._server = uvicorn.Server(
            uvicorn.Config(
                create_app(state),
                host=host,
                port=port,
                log_level="warning",
                access_log=False,
                log_config=None,
                lifespan="off",
            )
        )
        self._thread = threading.Thread(target=self._server.run, name="control-api", daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._server.should_exit = True
        self._thread.join(timeout=5.0)

    def wait_ready(self, timeout_s: float = 10.0) -> bool:
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            if self._server.started:
                return True
            time.sleep(0.05)
        return False
