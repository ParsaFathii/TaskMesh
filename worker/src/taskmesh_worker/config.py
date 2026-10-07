"""Worker configuration loaded from environment variables (SPEC §12).

Every knob is overridable so multiple workers can run side by side on one
host with distinct control ports and capability sets.
"""

from __future__ import annotations

import os
import socket
from dataclasses import dataclass, field
from pathlib import Path

from .registry import ALL_JOB_TYPES

DEFAULT_DATABASE_URL = "postgresql://taskmesh@localhost:5433/taskmesh"
DEFAULT_HEARTBEAT_INTERVAL_S = 10
DEFAULT_LEASE_MARGIN_S = 15
DEFAULT_CONTROL_PORT = 9100
DEFAULT_STORAGE_DIR = "./data"


@dataclass(frozen=True)
class WorkerConfig:
    """Immutable runtime configuration for a single worker process."""

    database_url: str = DEFAULT_DATABASE_URL
    capabilities: list[str] = field(default_factory=lambda: list(ALL_JOB_TYPES))
    heartbeat_interval_s: int = DEFAULT_HEARTBEAT_INTERVAL_S
    lease_margin_s: int = DEFAULT_LEASE_MARGIN_S
    control_port: int = DEFAULT_CONTROL_PORT
    storage_dir: Path = Path(DEFAULT_STORAGE_DIR)
    worker_name: str = ""
    version: str = "0.1.0"

    def __post_init__(self) -> None:
        if self.heartbeat_interval_s < 1:
            raise ValueError("TASKMESH_HEARTBEAT_INTERVAL_S must be >= 1")
        if self.lease_margin_s < 0:
            raise ValueError("TASKMESH_LEASE_MARGIN_S must be >= 0")
        if not 1 <= self.control_port <= 65535:
            raise ValueError("TASKMESH_CONTROL_PORT must be within 1..65535")
        unknown = set(self.capabilities) - set(ALL_JOB_TYPES)
        if unknown:
            raise ValueError(
                f"unknown capabilities {sorted(unknown)}; valid job types: {sorted(ALL_JOB_TYPES)}"
            )
        if not self.capabilities:
            raise ValueError("TASKMESH_CAPABILITIES must list at least one job type")
        if not self.worker_name:
            object.__setattr__(self, "worker_name", f"{socket.gethostname()}-{os.getpid()}")
        object.__setattr__(self, "storage_dir", Path(self.storage_dir))

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> WorkerConfig:
        """Build a config from the process environment (or an injected mapping)."""
        source = dict(os.environ) if env is None else env
        capabilities_raw = source.get("TASKMESH_CAPABILITIES", "").strip()
        capabilities = (
            [c.strip() for c in capabilities_raw.split(",") if c.strip()]
            if capabilities_raw
            else list(ALL_JOB_TYPES)
        )
        return cls(
            database_url=source.get("TASKMESH_DATABASE_URL", DEFAULT_DATABASE_URL),
            capabilities=capabilities,
            heartbeat_interval_s=int(
                source.get("TASKMESH_HEARTBEAT_INTERVAL_S", DEFAULT_HEARTBEAT_INTERVAL_S)
            ),
            lease_margin_s=int(source.get("TASKMESH_LEASE_MARGIN_S", DEFAULT_LEASE_MARGIN_S)),
            control_port=int(source.get("TASKMESH_CONTROL_PORT", DEFAULT_CONTROL_PORT)),
            storage_dir=Path(source.get("TASKMESH_STORAGE_DIR", DEFAULT_STORAGE_DIR)),
            worker_name=source.get("TASKMESH_WORKER_NAME", ""),
        )

    def safe_view(self) -> dict[str, object]:
        """Config as a JSON-safe dict with database credentials stripped."""
        url = self.database_url
        try:
            from urllib.parse import urlsplit

            parts = urlsplit(url)
            sanitized = f"{parts.scheme}://{parts.hostname}:{parts.port or 5432}{parts.path}"
        except ValueError:
            sanitized = "<unparseable>"
        return {
            "databaseUrl": sanitized,
            "capabilities": list(self.capabilities),
            "heartbeatIntervalS": self.heartbeat_interval_s,
            "leaseMarginS": self.lease_margin_s,
            "controlPort": self.control_port,
            "storageDir": str(self.storage_dir),
            "workerName": self.worker_name,
            "version": self.version,
        }
