"""Worker entrypoint: ``python -m taskmesh_worker`` / ``taskmesh-worker``.

Wires configuration, database (with startup retry), storage, registry,
heartbeat/claim loop, control API, and signal handling. Logs structured
JSON lines to stdout (timestamp, level, event, workerId, jobId).
"""

from __future__ import annotations

import json
import logging
import sys
from datetime import UTC, datetime
from typing import Any

from .config import WorkerConfig
from .control_api import ControlServer
from .db import Database
from .registry import HandlerRegistry
from .runtime import WorkerRuntime
from .shutdown import ShutdownCoordinator
from .storage import ResultStorage

_RESERVED_RECORD_FIELDS = frozenset(logging.LogRecord("", 0, "", 0, "", None, None).__dict__)


class JsonLineFormatter(logging.Formatter):
    """Format records as compact JSON lines; extra fields become top-level keys."""

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "timestamp": datetime.fromtimestamp(record.created, tz=UTC).isoformat(),
            "level": record.levelname,
            "event": record.getMessage(),
            "logger": record.name,
        }
        for key, value in record.__dict__.items():
            if key in _RESERVED_RECORD_FIELDS or key.startswith("_") or key == "message":
                continue
            payload[key] = value
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, default=str)


def setup_logging(level: int = logging.INFO) -> logging.Logger:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonLineFormatter())
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(level)
    for noisy in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        logging.getLogger(noisy).setLevel(logging.WARNING)
    return logging.getLogger("taskmesh_worker")


def main(argv: list[str] | None = None) -> int:
    log = setup_logging()
    try:
        config = WorkerConfig.from_env()
    except ValueError as exc:
        log.error("invalid configuration: %s", exc)
        return 2

    db = Database(config.database_url)
    try:
        # Graceful degradation: wait out database outages instead of crashing.
        db.ensure_connected()
    except Exception:
        log.exception("database unreachable at startup; giving up")
        return 1

    storage = ResultStorage(config.storage_dir)
    registry = HandlerRegistry.default(config.capabilities)
    shutdown = ShutdownCoordinator()
    shutdown.install()
    runtime = WorkerRuntime(config, db, registry, storage, shutdown)

    control = ControlServer(runtime, config.control_port)
    control.start()
    if control.wait_ready():
        log.info("control API listening on port %d", config.control_port)
    else:
        log.warning("control API on port %d did not report ready in time", config.control_port)

    log.info(
        "starting worker",
        extra={
            "workerName": config.worker_name,
            "capabilities": ",".join(config.capabilities),
        },
    )
    try:
        exit_code = runtime.run()
    except KeyboardInterrupt:  # pragma: no cover - SIGINT normally handled by coordinator
        exit_code = 130
    finally:
        control.stop()
        db.close()
    log.info("worker exited with code %d", exit_code)
    return exit_code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
