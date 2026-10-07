"""The worker runtime loop (SPEC §7, §10).

Responsibilities: register the worker, heartbeat (plus lease renewal while
BUSY), claim jobs with the SPEC §7 SKIP LOCKED statement, execute handlers
in a daemon thread with a hard timeout, report progress (job update +
job_logs + NOTIFY), and finalize jobs as SUCCEEDED / FAILED / RETRYING /
TIMED_OUT / CANCELLED with a job_attempts row per attempt.

Deviation from the verbatim §7 SQL (documented): the claim additionally
filters ``type = ANY(%s)`` so a worker never claims job types outside its
advertised capabilities. With the default capability set (all 7 types) the
filter matches every catalog job, so the protocol is unchanged.
"""

from __future__ import annotations

import logging
import threading
import time
import uuid as uuidlib
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from psycopg.types.json import Jsonb

from .config import WorkerConfig
from .db import Database
from .handlers import (
    CancelState,
    JobCancelled,
    JobContext,
    JobHandler,
    ValidationError,
    classify_error,
)
from .notify import job_event, job_log_event, worker_event
from .registry import HandlerRegistry
from .shutdown import SIGINT_EXIT_CODE, ShutdownCoordinator
from .storage import ResultStorage

log = logging.getLogger("taskmesh_worker.runtime")

IDLE_POLL_S = 1.0
WAIT_SLICE_S = 0.25
CANCEL_CHECK_INTERVAL_S = 1.0
ORPHAN_CLEANUP_INTERVAL_S = 600.0
ORPHAN_MIN_AGE_S = 3600.0
ERROR_TEXT_LIMIT = 2000

CLAIM_SQL = """
WITH next_job AS (
  SELECT id FROM jobs
  WHERE status IN ('QUEUED','RETRYING') AND available_at <= now()
    AND type = ANY(%s)
  ORDER BY (CASE priority WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3
            WHEN 'NORMAL' THEN 2 ELSE 1 END)::double precision
         + LEAST(EXTRACT(EPOCH FROM (now() - created_at)) / 3600.0, 1.0) DESC,
           created_at ASC
  FOR UPDATE SKIP LOCKED
  LIMIT 1
)
UPDATE jobs j
SET status = 'RUNNING', worker_id = %s, started_at = now(),
    queued_at = COALESCE(j.queued_at, now()),
    lease_expires_at = now() + make_interval(secs => GREATEST(30, j.timeout_seconds))
FROM next_job WHERE j.id = next_job.id
RETURNING j.*;
"""

CANCEL_QUEUED_SQL = """
WITH marked AS (
  SELECT id, retry_count FROM jobs
  WHERE status IN ('QUEUED','RETRYING') AND cancel_requested
  FOR UPDATE SKIP LOCKED
)
UPDATE jobs j
SET status = 'CANCELLED', completed_at = now()
FROM marked WHERE j.id = marked.id
RETURNING j.id, j.retry_count
"""


def backoff_seconds(retry_count: int) -> int:
    """Exponential retry backoff (SPEC §5): min(300, 2^retry_count * 5)."""
    return min(300, (2**retry_count) * 5)


@dataclass(frozen=True)
class JobRecord:
    """Snapshot of a claimed job row."""

    id: uuidlib.UUID
    type: str
    payload: dict[str, Any]
    priority: str
    status: str
    retry_count: int
    max_retries: int
    timeout_seconds: int
    cancel_requested: bool
    started_at: datetime | None
    created_at: datetime

    @classmethod
    def from_row(cls, row: dict[str, Any]) -> JobRecord:
        return cls(
            id=row["id"],
            type=row["type"],
            payload=row["payload"] or {},
            priority=row["priority"],
            status=row["status"],
            retry_count=row["retry_count"],
            max_retries=row["max_retries"],
            timeout_seconds=row["timeout_seconds"],
            cancel_requested=row["cancel_requested"],
            started_at=row["started_at"],
            created_at=row["created_at"],
        )


class FinalizeError(Exception):
    """Job row was not in the expected state during finalization."""


def claim_job(db: Database, worker_id: uuidlib.UUID, job_types: list[str]) -> JobRecord | None:
    """Atomically claim one runnable job for ``worker_id`` (or None)."""
    rows = db.execute(CLAIM_SQL, (job_types, worker_id))
    return JobRecord.from_row(rows[0]) if rows else None


@dataclass
class ExecutionOutcome:
    """Discrete result of running a handler for one attempt."""

    kind: str  # success | validation_failed | failed | timed_out | abandoned | cancelled
    result: dict[str, Any] | None = None
    error: BaseException | None = None


class WorkerRuntime:
    """One worker process: heartbeat + claim/execute/finalize loop."""

    def __init__(
        self,
        config: WorkerConfig,
        db: Database,
        registry: HandlerRegistry,
        storage: ResultStorage,
        shutdown: ShutdownCoordinator | None = None,
    ) -> None:
        self.config = config
        self.db = db
        self.registry = registry
        self.storage = storage
        self.shutdown = shutdown or ShutdownCoordinator()
        self.worker_id: str | None = None
        self.current_job: JobRecord | None = None
        self._started_monotonic = time.monotonic()
        self._heartbeat_stop = threading.Event()
        self._heartbeat_thread: threading.Thread | None = None
        self._next_orphan_cleanup = time.monotonic() + ORPHAN_CLEANUP_INTERVAL_S

    # -- control API view --------------------------------------------------

    @property
    def status(self) -> str:
        if self.shutdown.draining:
            return "DRAINING"
        return "BUSY" if self.current_job else "IDLE"

    @property
    def current_job_id(self) -> str | None:
        return str(self.current_job.id) if self.current_job else None

    @property
    def uptime_s(self) -> float:
        return time.monotonic() - self._started_monotonic

    # -- lifecycle ---------------------------------------------------------

    def register(self) -> str:
        """INSERT the workers row and keep the returned id for the process lifetime."""
        rows = self.db.execute(
            """
            INSERT INTO workers (name, hostname, version, status, capabilities,
                                 heartbeat_interval_s, control_port)
            VALUES (%s, %s, %s, 'STARTING', %s, %s, %s)
            RETURNING id
            """,
            (
                self.config.worker_name,
                _hostname(),
                self.config.version,
                self.config.capabilities,
                self.config.heartbeat_interval_s,
                self.config.control_port,
            ),
        )
        self.worker_id = str(rows[0]["id"])
        log.info(
            "worker registered",
            extra={"workerId": self.worker_id},
        )
        worker_event(self.db, self.worker_id, "STARTING", None)
        return self.worker_id

    def start(self) -> None:
        self.register()
        self._heartbeat_thread = threading.Thread(
            target=self._heartbeat_loop,
            name="heartbeat",
            daemon=True,
        )
        self._heartbeat_thread.start()

    def run(self) -> int:
        """Main loop; returns the process exit code (0 drained, 130 aborted)."""
        try:
            self.start()
        except Exception:
            log.exception("worker startup failed")
            self._try_set_worker_status("ERROR")
            return 1
        while not self.shutdown.stop_requested:
            self._cancel_requested_queued_jobs()
            if self.shutdown.draining:
                break
            job = self._claim()
            if job is None:
                self._maybe_cleanup_orphans()
                self.shutdown.wait(IDLE_POLL_S)
                continue
            self._process(job)
        exit_code = SIGINT_EXIT_CODE if self.shutdown.abort_requested else 0
        self._try_set_worker_status("OFFLINE")
        log.info(
            "worker loop finished",
            extra={"workerId": self.worker_id, "code": exit_code},
        )
        self._heartbeat_stop.set()
        return exit_code

    # -- heartbeat ---------------------------------------------------------

    def _heartbeat_loop(self) -> None:
        while not self._heartbeat_stop.wait(self.config.heartbeat_interval_s):
            try:
                self._heartbeat_once()
            except Exception:
                log.warning("heartbeat failed", exc_info=True)

    def _heartbeat_once(self) -> None:
        """Update liveness; renew the lease while a job is running."""
        assert self.worker_id is not None
        status = self.status
        rows = self.db.execute(
            """
            UPDATE workers
            SET last_heartbeat = now(), status = %s::worker_status,
                current_job_id = %s
            WHERE id = %s
            RETURNING last_heartbeat
            """,
            (status, self.current_job_id, uuidlib.UUID(self.worker_id)),
        )
        if not rows:
            log.error("worker row vanished; heartbeats have no target")
            return
        if self.current_job is not None:
            self.db.execute(
                """
                UPDATE jobs
                SET lease_expires_at = now()
                    + make_interval(secs => GREATEST(30, timeout_seconds) + %s)
                WHERE id = %s AND status = 'RUNNING' AND worker_id = %s
                """,
                (
                    self.config.lease_margin_s,
                    self.current_job.id,
                    uuidlib.UUID(self.worker_id),
                ),
            )
        worker_event(self.db, self.worker_id, status, self.current_job_id)

    # -- claiming ------------------------------------------------------------

    def _claim(self) -> JobRecord | None:
        assert self.worker_id is not None
        try:
            return claim_job(self.db, uuidlib.UUID(self.worker_id), self.registry.job_types())
        except Exception:
            log.warning("claim failed", exc_info=True)
            time.sleep(1.0)
            return None

    def _cancel_requested_queued_jobs(self) -> None:
        """Pre-claim cancellation: finalize queued jobs flagged for cancel."""
        try:
            rows = self.db.execute(CANCEL_QUEUED_SQL)
        except Exception:
            log.warning("pre-claim cancellation sweep failed", exc_info=True)
            return
        for row in rows:
            job_id = str(row["id"])
            attempt_number = int(row["retry_count"]) + 1
            with self.db.transaction() as conn:
                _insert_attempt(
                    conn,
                    job_id=uuidlib.UUID(job_id),
                    attempt_number=attempt_number,
                    worker_id=self._worker_uuid(),
                    started_at=datetime.now(UTC),
                    outcome="CANCELLED",
                    error="cancelled before claim",
                )
                _insert_job_log(
                    conn,
                    job_id=uuidlib.UUID(job_id),
                    worker_id=self._worker_uuid(),
                    level="INFO",
                    message="job cancelled before claim",
                )
            job_event(self.db, job_id, "CANCELLED")
            log.info(
                "job cancelled before claim",
                extra={"jobId": job_id},
            )

    # -- execution ------------------------------------------------------------

    def _process(self, job: JobRecord) -> None:
        self.current_job = job
        self._set_worker_state()  # BUSY + worker event
        job_id = str(job.id)
        log.info(
            "job claimed",
            extra={"jobId": job_id, "jobType": job.type},
        )
        self._log_job(job_id, "INFO", f"job claimed by worker {self.config.worker_name}")
        job_event(self.db, job_id, "RUNNING", 0)
        try:
            if self._refresh_cancel_requested(job):
                self._finalize_cancelled(job, "cancelled after claim")
                return
            handler = self.registry.handler(job.type)
            if handler is None:
                self._finalize_failure(
                    job,
                    ValidationError(f"worker has no handler for job type {job.type!r}"),
                    retryable=False,
                )
                return
            try:
                handler.validate(job.payload)
            except ValidationError as exc:
                self._finalize_failure(job, exc, retryable=False)
                return

            cancel_state = CancelState(job.cancel_requested)
            ctx = JobContext(
                job_id=job_id,
                storage=self.storage,
                cancel=cancel_state,
                progress=self._progress_reporter(job, cancel_state),
            )
            outcome = self._execute(job, handler, ctx)
            if outcome.kind == "success":
                if self._refresh_cancel_requested(job):
                    # end-of-run cancellation: the result is discarded
                    self._finalize_cancelled(job, "cancelled during execution")
                else:
                    self._finalize_success(job, outcome.result or {}, ctx)
            elif outcome.kind == "validation_failed":
                self._finalize_failure(job, outcome.error, retryable=False)
            elif outcome.kind == "failed":
                self._finalize_failure(job, outcome.error, retryable=True)
            elif outcome.kind == "timed_out":
                self._finalize_timeout(job)
            elif outcome.kind == "abandoned":
                self._finalize_abandoned(job)
            elif outcome.kind == "cancelled":
                self._finalize_cancelled(job, "cancelled during execution")
        finally:
            self.current_job = None
            self._set_worker_state()
            log.info(
                "job attempt finished",
                extra={"jobId": job_id},
            )

    def _execute(self, job: JobRecord, handler: JobHandler, ctx: JobContext) -> ExecutionOutcome:
        """Run the handler in a daemon thread with a hard timeout.

        The wait is sliced so SIGINT aborts and cancellation flags are acted
        on promptly. On timeout the thread cannot be killed (CPython
        limitation) and is left to die as a daemon thread; the job is
        finalized immediately.
        """
        holder: dict[str, Any] = {"result": None, "error": None}
        thread = threading.Thread(
            target=self._run_handler,
            args=(handler, job.payload, ctx, holder),
            name=f"job-{job.id}",
            daemon=True,
        )
        thread.start()
        deadline = time.monotonic() + job.timeout_seconds
        next_cancel_check = time.monotonic() + CANCEL_CHECK_INTERVAL_S
        while True:
            thread.join(WAIT_SLICE_S)
            if not thread.is_alive():
                break
            now = time.monotonic()
            if now >= deadline:
                log.warning(
                    "job timed out",
                    extra={"jobId": str(job.id)},
                )
                ctx.request_cancel()  # stops cooperative zombie loops promptly
                return ExecutionOutcome(kind="timed_out")
            if self.shutdown.abort_requested:
                ctx.request_cancel()
                return ExecutionOutcome(kind="abandoned")
            if now >= next_cancel_check:
                next_cancel_check = now + CANCEL_CHECK_INTERVAL_S
                if self._refresh_cancel_requested(job):
                    ctx.request_cancel()
        error = holder["error"]
        if error is not None:
            if isinstance(error, JobCancelled):
                return ExecutionOutcome(kind="cancelled", error=error)
            if isinstance(error, ValidationError):
                return ExecutionOutcome(kind="validation_failed", error=error)
            retryable = classify_error(error) == "retryable"
            kind = "failed" if retryable else "validation_failed"
            return ExecutionOutcome(kind=kind, error=error)
        return ExecutionOutcome(kind="success", result=holder["result"])

    def _run_handler(
        self,
        handler: JobHandler,
        payload: dict[str, Any],
        ctx: JobContext,
        holder: dict[str, Any],
    ) -> None:
        try:
            holder["result"] = handler.run(payload, ctx)
        except BaseException as exc:
            holder["error"] = exc

    # -- finalization ---------------------------------------------------------

    def _finalize_success(self, job: JobRecord, result: dict[str, Any], ctx: JobContext) -> None:
        job_id = str(job.id)
        artifact = ctx.artifact
        if artifact is not None:
            record = artifact
            inline: dict[str, Any] | None = result  # metadata alongside the file
        else:
            stored = self.storage.store_result(job_id, result)
            record = stored
            inline = stored.inline
        try:
            with self.db.transaction() as conn:
                _insert_attempt(
                    conn,
                    job_id=job.id,
                    attempt_number=job.retry_count + 1,
                    worker_id=self._worker_uuid(),
                    started_at=job.started_at or datetime.now(UTC),
                    outcome="SUCCEEDED",
                )
                _update_job_success(conn, job.id)
                conn.execute(
                    """
                    INSERT INTO job_results (job_id, kind, inline, file_path, size_bytes,
                                             checksum_sha256)
                    VALUES (%s, %s::result_kind, %s, %s, %s, %s)
                    """,
                    (
                        job.id,
                        record.kind,
                        Jsonb(inline) if inline is not None else None,
                        record.file_path,
                        record.size_bytes,
                        record.sha256,
                    ),
                )
                _insert_job_log(
                    conn,
                    job_id=job.id,
                    worker_id=self._worker_uuid(),
                    level="INFO",
                    message="job succeeded",
                    metadata={"resultKind": record.kind, "resultBytes": record.size_bytes},
                )
        except FinalizeError:
            log.warning("job no longer RUNNING at success finalize", extra={"jobId": job_id})
            return
        job_event(self.db, job_id, "SUCCEEDED", 100)
        log.info(
            "job succeeded",
            extra={"jobId": job_id, "resultKind": record.kind},
        )

    def _finalize_failure(
        self, job: JobRecord, error: BaseException | None, retryable: bool
    ) -> None:
        job_id = str(job.id)
        error_text = _error_text(error)
        retry_available = retryable and job.retry_count < job.max_retries
        backoff = backoff_seconds(job.retry_count) if retry_available else None
        try:
            with self.db.transaction() as conn:
                _insert_attempt(
                    conn,
                    job_id=job.id,
                    attempt_number=job.retry_count + 1,
                    worker_id=self._worker_uuid(),
                    started_at=job.started_at or datetime.now(UTC),
                    outcome="FAILED",
                    error=error_text,
                )
                if retry_available:
                    _update_job_retrying(conn, job.id, error_text, backoff)
                else:
                    _update_job_terminal(conn, job.id, "FAILED", error_text)
                _insert_job_log(
                    conn,
                    job_id=job.id,
                    worker_id=self._worker_uuid(),
                    level="ERROR",
                    message=f"job failed: {error_text}",
                    metadata={"retryable": retryable, "retryAvailable": retry_available},
                )
        except FinalizeError:
            log.warning("job no longer RUNNING at failure finalize", extra={"jobId": job_id})
            return
        status = "RETRYING" if retry_available else "FAILED"
        job_event(self.db, job_id, status)
        log.warning(
            "job attempt failed",
            extra={
                "jobId": job_id,
                "status": status,
                "backoffS": backoff,
            },
        )

    def _finalize_timeout(self, job: JobRecord) -> None:
        job_id = str(job.id)
        retry_available = job.retry_count < job.max_retries
        backoff = backoff_seconds(job.retry_count) if retry_available else None
        error_text = f"execution timed out after {job.timeout_seconds}s"
        try:
            with self.db.transaction() as conn:
                _insert_attempt(
                    conn,
                    job_id=job.id,
                    attempt_number=job.retry_count + 1,
                    worker_id=self._worker_uuid(),
                    started_at=job.started_at or datetime.now(UTC),
                    outcome="TIMED_OUT",
                    error=error_text,
                )
                if retry_available:
                    _update_job_retrying(conn, job.id, error_text, backoff)
                else:
                    _update_job_terminal(conn, job.id, "TIMED_OUT", error_text)
                _insert_job_log(
                    conn,
                    job_id=job.id,
                    worker_id=self._worker_uuid(),
                    level="ERROR",
                    message=f"job timed out after {job.timeout_seconds}s",
                    metadata={"retryAvailable": retry_available},
                )
        except FinalizeError:
            log.warning("job no longer RUNNING at timeout finalize", extra={"jobId": job_id})
            return
        job_event(self.db, job_id, "RETRYING" if retry_available else "TIMED_OUT")
        log.warning(
            "job timed out",
            extra={"jobId": job_id},
        )

    def _finalize_abandoned(self, job: JobRecord) -> None:
        """SIGINT crash semantics: ABANDONED attempt, job to RETRYING."""
        job_id = str(job.id)
        retry_available = job.retry_count < job.max_retries
        backoff = backoff_seconds(job.retry_count) if retry_available else None
        error_text = "abandoned: worker received SIGINT"
        try:
            with self.db.transaction() as conn:
                _insert_attempt(
                    conn,
                    job_id=job.id,
                    attempt_number=job.retry_count + 1,
                    worker_id=self._worker_uuid(),
                    started_at=job.started_at or datetime.now(UTC),
                    outcome="ABANDONED",
                    error=error_text,
                )
                if retry_available:
                    _update_job_retrying(conn, job.id, error_text, backoff)
                else:
                    _update_job_terminal(conn, job.id, "FAILED", error_text)
                _insert_job_log(
                    conn,
                    job_id=job.id,
                    worker_id=self._worker_uuid(),
                    level="WARN",
                    message=error_text,
                )
        except FinalizeError:
            log.warning("job no longer RUNNING at abandon finalize", extra={"jobId": job_id})
            return
        job_event(self.db, job_id, "RETRYING" if retry_available else "FAILED")

    def _finalize_cancelled(self, job: JobRecord, reason: str) -> None:
        job_id = str(job.id)
        try:
            with self.db.transaction() as conn:
                _insert_attempt(
                    conn,
                    job_id=job.id,
                    attempt_number=job.retry_count + 1,
                    worker_id=self._worker_uuid(),
                    started_at=job.started_at or datetime.now(UTC),
                    outcome="CANCELLED",
                    error=reason,
                )
                _update_job_terminal(conn, job.id, "CANCELLED", reason)
                _insert_job_log(
                    conn,
                    job_id=job.id,
                    worker_id=self._worker_uuid(),
                    level="INFO",
                    message=f"job cancelled: {reason}",
                )
        except FinalizeError:
            log.warning("job no longer RUNNING at cancel finalize", extra={"jobId": job_id})
            return
        job_event(self.db, job_id, "CANCELLED")
        log.info("job cancelled", extra={"jobId": job_id})

    # -- helpers -----------------------------------------------------------

    def _worker_uuid(self) -> uuidlib.UUID | None:
        return uuidlib.UUID(self.worker_id) if self.worker_id else None

    def _set_worker_state(self) -> None:
        status = self.status
        try:
            self.db.execute(
                """
                UPDATE workers
                SET status = %s::worker_status, current_job_id = %s
                WHERE id = %s
                """,
                (status, self.current_job_id, self._worker_uuid()),
            )
            worker_event(self.db, str(self.worker_id), status, self.current_job_id)
        except Exception:
            log.warning("failed to update worker state", exc_info=True)

    def _try_set_worker_status(self, status: str) -> None:
        try:
            self.db.execute(
                "UPDATE workers SET status = %s::worker_status WHERE id = %s",
                (status, self._worker_uuid()),
            )
        except Exception:
            log.warning("failed to set worker status %s", status, exc_info=True)

    def _refresh_cancel_requested(self, job: JobRecord) -> bool:
        try:
            rows = self.db.execute("SELECT cancel_requested FROM jobs WHERE id = %s", (job.id,))
            return bool(rows and rows[0]["cancel_requested"])
        except Exception:
            log.warning("cancel check failed", exc_info=True)
            return False

    def _progress_reporter(self, job: JobRecord, cancel_state: CancelState) -> Any:
        last_pct: dict[str, int | None] = {"pct": None}

        def report(pct: int, message: str | None = None) -> None:
            try:
                rows = self.db.execute(
                    """
                    UPDATE jobs SET progress = %s
                    WHERE id = %s AND status = 'RUNNING'
                    RETURNING cancel_requested
                    """,
                    (pct, job.id),
                )
                if not rows:
                    return
                if rows[0]["cancel_requested"]:
                    cancel_state.request()
                if pct != last_pct["pct"] or message:
                    self.db.execute(
                        """
                        INSERT INTO job_logs (job_id, worker_id, level, message)
                        VALUES (%s, %s, 'INFO', %s)
                        """,
                        (job.id, self._worker_uuid(), message or f"progress {pct}%"),
                    )
                    job_event(self.db, str(job.id), "RUNNING", pct)
                last_pct["pct"] = pct
            except Exception:
                log.warning("progress reporting failed", exc_info=True)

        return report

    def _log_job(
        self,
        job_id: str,
        level: str,
        message: str,
        metadata: dict[str, Any] | None = None,
    ) -> None:
        try:
            self.db.execute(
                """
                INSERT INTO job_logs (job_id, worker_id, level, message, metadata)
                VALUES (%s, %s, %s::log_level, %s, %s)
                """,
                (
                    uuidlib.UUID(job_id),
                    self._worker_uuid(),
                    level,
                    message,
                    Jsonb(metadata) if metadata is not None else None,
                ),
            )
            job_log_event(self.db, job_id, level, message)
        except Exception:
            log.warning("job log insert failed", exc_info=True)

    def _maybe_cleanup_orphans(self) -> None:
        if time.monotonic() < self._next_orphan_cleanup:
            return
        self._next_orphan_cleanup = time.monotonic() + ORPHAN_CLEANUP_INTERVAL_S
        try:
            rows = self.db.execute("SELECT job_id FROM job_results")
            known = {str(row["job_id"]) for row in rows}
            if self.current_job_id:
                known.add(self.current_job_id)
            removed = self.storage.cleanup_orphans(known, min_age_s=ORPHAN_MIN_AGE_S)
            if removed:
                log.info("orphan cleanup removed %d files", len(removed))
        except Exception:
            log.warning("orphan cleanup failed", exc_info=True)


# -- shared SQL helpers -----------------------------------------------------


def _insert_attempt(
    conn: Any,
    *,
    job_id: uuidlib.UUID,
    attempt_number: int,
    worker_id: uuidlib.UUID | None,
    started_at: datetime,
    outcome: str,
    error: str | None = None,
) -> None:
    conn.execute(
        """
        INSERT INTO job_attempts (job_id, attempt_number, worker_id, started_at,
                                  finished_at, outcome, error)
        VALUES (%s, %s, %s, %s, now(), %s, %s)
        """,
        (job_id, attempt_number, worker_id, started_at, outcome, error),
    )


def _update_job_success(conn: Any, job_id: uuidlib.UUID) -> None:
    """RUNNING -> SUCCEEDED (guard: exactly one RUNNING row)."""
    cursor = conn.execute(
        """
        UPDATE jobs
        SET status = 'SUCCEEDED'::job_status, progress = 100, last_error = NULL,
            lease_expires_at = NULL, completed_at = now()
        WHERE id = %s AND status = 'RUNNING'
        """,
        (job_id,),
    )
    if cursor.rowcount != 1:
        raise FinalizeError(f"job {job_id} is not RUNNING; refusing transition to SUCCEEDED")


def _update_job_retrying(conn: Any, job_id: uuidlib.UUID, error: str, backoff_s: int) -> None:
    """RUNNING -> RETRYING with exponential backoff (retry_count + 1)."""
    cursor = conn.execute(
        """
        UPDATE jobs
        SET status = 'RETRYING'::job_status, retry_count = retry_count + 1,
            available_at = now() + make_interval(secs => %s),
            last_error = %s, lease_expires_at = NULL
        WHERE id = %s AND status = 'RUNNING'
        """,
        (backoff_s, error, job_id),
    )
    if cursor.rowcount != 1:
        raise FinalizeError(f"job {job_id} is not RUNNING; refusing transition to RETRYING")


def _update_job_terminal(conn: Any, job_id: uuidlib.UUID, status: str, error: str) -> None:
    """RUNNING -> FAILED/TIMED_OUT/CANCELLED (terminal, completed_at set)."""
    cursor = conn.execute(
        """
        UPDATE jobs
        SET status = %s::job_status, completed_at = now(), last_error = %s,
            lease_expires_at = NULL
        WHERE id = %s AND status = 'RUNNING'
        """,
        (status, error, job_id),
    )
    if cursor.rowcount != 1:
        raise FinalizeError(f"job {job_id} is not RUNNING; refusing transition to {status}")


def _insert_job_log(
    conn: Any,
    *,
    job_id: uuidlib.UUID,
    worker_id: uuidlib.UUID | None,
    level: str,
    message: str,
    metadata: dict[str, Any] | None = None,
) -> None:
    conn.execute(
        """
        INSERT INTO job_logs (job_id, worker_id, level, message, metadata)
        VALUES (%s, %s, %s::log_level, %s, %s)
        """,
        (
            job_id,
            worker_id,
            level,
            message,
            Jsonb(metadata) if metadata is not None else None,
        ),
    )


def _error_text(error: BaseException | None) -> str:
    if error is None:
        return "unknown error"
    text = f"{type(error).__name__}: {error}"
    return text[:ERROR_TEXT_LIMIT]


def _hostname() -> str:
    import socket

    return socket.gethostname()
