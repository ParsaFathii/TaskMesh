"""LISTEN/NOTIFY event emission on the ``taskmesh_events`` channel (SPEC §8).

Payloads are compact JSON (< 200 bytes); enrichment (timestamps, usernames)
is the backend's job. Emission is best-effort from the runtime's point of
view: a failed NOTIFY never fails job finalization.
"""

from __future__ import annotations

import json
import logging
from typing import Any

from .db import Database

log = logging.getLogger("taskmesh_worker.notify")

CHANNEL = "taskmesh_events"
MESSAGE_TRUNCATE_CHARS = 120


def emit(db: Database, payload: dict[str, Any]) -> None:
    """Send one NOTIFY with a compact JSON payload."""
    try:
        payload_json = json.dumps(payload, separators=(",", ":"))
        db.execute("SELECT pg_notify(%s, %s)", (CHANNEL, payload_json))
    except Exception:
        log.warning("failed to emit %s event", payload.get("t"), exc_info=True)


def job_event(db: Database, job_id: str, status: str, progress: int | None = None) -> None:
    event: dict[str, Any] = {"t": "job", "id": job_id, "s": status}
    if progress is not None:
        event["p"] = max(0, min(100, int(progress)))
    emit(db, event)


def job_log_event(db: Database, job_id: str, level: str, message: str) -> None:
    emit(
        db,
        {
            "t": "joblog",
            "id": job_id,
            "l": level,
            "m": message[:MESSAGE_TRUNCATE_CHARS],
        },
    )


def worker_event(db: Database, worker_id: str, status: str, current_job_id: str | None) -> None:
    emit(db, {"t": "worker", "id": worker_id, "s": status, "j": current_job_id})
