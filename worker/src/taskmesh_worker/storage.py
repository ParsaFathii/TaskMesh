"""Result storage: inline JSONB vs on-disk files (SPEC §9 envelope).

Rules:
- Handler results are serialized compactly; if the payload is <= 32 KiB the
  result is inline, otherwise it is written under ``<storage_dir>/results``.
- Binary artifacts (image bytes) are always written to files named
  ``results/<job_id>.bin`` (one file result per job — job_results.job_id is
  unique).
- Every path is derived from a validated UUID and canonically resolved; any
  attempt to escape the results root raises :class:`StorageError`.

The ``cleanup_orphans`` hook is best-effort: the runtime calls it
periodically to delete result files whose job ids no longer exist.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import time
import uuid as uuidlib
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .models import MAX_INLINE_BYTES

log = logging.getLogger("taskmesh_worker.storage")

ARTIFACT_EXTENSIONS = frozenset({"bin", "json"})


class StorageError(Exception):
    """Raised for unsafe or invalid storage operations."""


@dataclass(frozen=True)
class ResultRecord:
    """A stored (or inline) result ready for the job_results row."""

    kind: str  # "inline" | "file"
    inline: dict[str, Any] | None
    file_path: str | None  # relative to the storage root, POSIX separators
    size_bytes: int
    sha256: str


class ResultStorage:
    """Filesystem-backed result storage rooted at ``storage_dir``."""

    def __init__(self, root: Path) -> None:
        self.root = Path(root)
        self.results_root = self.root / "results"
        self._results_root_real = self.results_root.resolve()
        self.results_root.mkdir(parents=True, exist_ok=True)

    # -- path safety -------------------------------------------------------

    def _safe_path(self, job_id: str, ext: str) -> Path:
        """Compute ``results/<job_id>.<ext>`` with traversal protection.

        The job id must be a canonical UUID (so it cannot contain separators
        or traversal fragments), the extension is whitelisted, and the
        resolved path must stay inside the canonical results root.
        """
        if ext not in ARTIFACT_EXTENSIONS:
            raise StorageError(f"unallowed result extension: {ext!r}")
        try:
            canonical = str(uuidlib.UUID(str(job_id)))
        except (ValueError, AttributeError, TypeError) as exc:
            raise StorageError(f"job id is not a valid UUID: {job_id!r}") from exc
        path = self.results_root / f"{canonical}.{ext}"
        resolved = path.resolve(strict=False)
        if resolved.parent != self._results_root_real:
            raise StorageError(f"path escapes results root: {path}")
        if not str(resolved).startswith(str(self._results_root_real) + os.sep):
            raise StorageError(f"path escapes results root: {path}")
        return path

    # -- writes ------------------------------------------------------------

    def store_result(self, job_id: str, result: dict[str, Any]) -> ResultRecord:
        """Store a handler result inline (<= 32 KiB) or as a JSON file."""
        serialized = json.dumps(
            result, separators=(",", ":"), ensure_ascii=False, default=str
        ).encode("utf-8")
        checksum = hashlib.sha256(serialized).hexdigest()
        if len(serialized) <= MAX_INLINE_BYTES:
            return ResultRecord(
                kind="inline",
                inline=dict(result),
                file_path=None,
                size_bytes=len(serialized),
                sha256=checksum,
            )
        record = self._write_file(job_id, "json", serialized)
        return ResultRecord(
            kind="file",
            inline=None,
            file_path=record,
            size_bytes=len(serialized),
            sha256=checksum,
        )

    def store_artifact(self, job_id: str, data: bytes, ext: str = "bin") -> ResultRecord:
        """Store raw bytes (e.g. resized image) as the job's file result."""
        record_path = self._write_file(job_id, ext, data)
        return ResultRecord(
            kind="file",
            inline=None,
            file_path=record_path,
            size_bytes=len(data),
            sha256=hashlib.sha256(data).hexdigest(),
        )

    def _write_file(self, job_id: str, ext: str, data: bytes) -> str:
        path = self._safe_path(job_id, ext)
        tmp = path.with_suffix(path.suffix + ".tmp")
        tmp.write_bytes(data)
        os.replace(tmp, path)
        return path.relative_to(self.root).as_posix()

    # -- reads (used by tests and tooling) ----------------------------------

    def read_file(self, file_path: str) -> bytes:
        """Read a stored result file by its root-relative POSIX path."""
        if ".." in Path(file_path).parts:
            raise StorageError(f"path traversal rejected: {file_path!r}")
        target = (self.root / file_path).resolve(strict=False)
        if not str(target).startswith(str(self._results_root_real) + os.sep):
            raise StorageError(f"path escapes results root: {file_path!r}")
        return target.read_bytes()

    # -- maintenance --------------------------------------------------------

    def cleanup_orphans(self, known_job_ids: set[str], *, min_age_s: float = 3600.0) -> list[str]:
        """Delete stale result files whose job ids are unknown; best-effort.

        Only files matching the storage naming convention
        (``<uuid>.bin`` / ``<uuid>.json``) that are older than ``min_age_s``
        and whose job id is not in ``known_job_ids`` are removed, so
        artifacts of in-flight jobs (written before their job_results row
        exists) are never touched and stray non-result files are ignored.
        Failures to stat or unlink are logged and skipped, not raised.
        """
        removed: list[str] = []
        try:
            candidates = list(self.results_root.iterdir())
        except OSError:
            log.warning("cannot list results root for orphan cleanup", exc_info=True)
            return removed
        cutoff = time.time() - min_age_s
        for entry in candidates:
            if entry.is_dir():
                continue
            stem, dot, ext = entry.name.rpartition(".")
            if not dot or ext not in ARTIFACT_EXTENSIONS:
                continue  # not a file this storage owns
            try:
                job_id = str(uuidlib.UUID(stem))
            except ValueError:
                continue  # not a job-named result file
            try:
                if entry.stat().st_mtime >= cutoff:
                    continue
            except OSError:
                continue
            if job_id in known_job_ids:
                continue
            try:
                entry.unlink()
                removed.append(entry.relative_to(self.root).as_posix())
                log.info("removed orphan result file %s", entry.name)
            except OSError:
                log.warning("failed to remove orphan file %s", entry.name, exc_info=True)
        return removed
