"""Job handler contracts: validation, retryability, execution context.

Error taxonomy (SPEC §9 "strict input validation, fail fast, NON-retryable
errors for invalid payload"):

- :class:`ValidationError` — payload violates the job type contract.
  Non-retryable: the job transitions RUNNING -> FAILED immediately.
- :class:`TransientError` — temporary failure worth retrying.
- Any other exception escaping a handler is treated as retryable (a bug in
  the handler is retried at most ``max_retries`` times, then FAILED).
- :class:`JobCancelled` — handler observed cancellation cooperatively and
  stopped; the runtime finalizes the job as CANCELLED.
"""

from __future__ import annotations

import logging
import threading
from abc import ABC, abstractmethod
from collections.abc import Callable, Mapping
from typing import Any, ClassVar

import pydantic
from pydantic import BaseModel

from ..storage import ResultRecord, ResultStorage

log = logging.getLogger("taskmesh_worker.handler")


class ValidationError(Exception):
    """Non-retryable: the job payload is invalid for its type."""


class TransientError(Exception):
    """Retryable: transient execution failure."""


class JobCancelled(Exception):
    """Raised by a handler that honors cancellation mid-execution."""


def classify_error(exc: BaseException) -> str:
    """Map an exception to its retryability class.

    Returns ``"non_retryable"`` or ``"retryable"`` (unexpected exceptions are
    retryable by default so genuine bugs still get bounded retries).
    """
    if isinstance(exc, ValidationError):
        return "non_retryable"
    return "retryable"


class CancelState:
    """Thread-safe cooperative cancellation flag shared with the handler."""

    def __init__(self, initially_requested: bool = False) -> None:
        self._event = threading.Event()
        if initially_requested:
            self._event.set()

    def request(self) -> None:
        self._event.set()

    @property
    def requested(self) -> bool:
        return self._event.is_set()


class JobContext:
    """Execution context handed to handlers.

    ``report_progress`` forwards 0..100 progress to the runtime (database
    update + job log + NOTIFY); ``cancelled`` lets long-running handlers stop
    early; ``attach_artifact`` registers the job's single binary file result.
    """

    def __init__(
        self,
        job_id: str,
        storage: ResultStorage,
        cancel: CancelState,
        progress: Callable[[int, str | None], None] | None = None,
    ) -> None:
        self.job_id = job_id
        self.storage = storage
        self._cancel = cancel
        self._progress = progress
        self._artifact: ResultRecord | None = None

    def report_progress(self, pct: int, message: str | None = None) -> None:
        """Report progress (clamped to 0..100); never raises into handlers."""
        if self._progress is None:
            return
        try:
            self._progress(max(0, min(100, int(pct))), message)
        except Exception:
            log.warning("progress reporting failed", exc_info=True)

    @property
    def cancelled(self) -> bool:
        return self._cancel.requested

    def request_cancel(self) -> None:
        """Called by the runtime when the database says cancellation is pending."""
        self._cancel.request()

    def raise_if_cancelled(self) -> None:
        if self._cancel.requested:
            raise JobCancelled("cancellation observed by handler")

    def attach_artifact(self, record: ResultRecord) -> None:
        if self._artifact is not None:
            raise ValidationError("job already has an attached file result")
        self._artifact = record

    @property
    def artifact(self) -> ResultRecord | None:
        return self._artifact


class JobHandler(ABC):
    """Base class for all job type handlers.

    Subclasses declare ``job_type`` and ``payload_model``; ``validate``
    parses the payload into the model (raising :class:`ValidationError` on
    contract violations) and ``run`` executes the already-validated job.
    """

    job_type: ClassVar[str]
    payload_model: ClassVar[type[BaseModel]]

    def validate(self, payload: Mapping[str, Any]) -> Any:
        """Parse the payload, raising ValidationError on any violation."""
        try:
            return self.payload_model.model_validate(dict(payload))
        except pydantic.ValidationError as exc:
            raise ValidationError(_compact_validation_error(exc)) from exc

    @abstractmethod
    def run(self, payload: Mapping[str, Any], ctx: JobContext) -> dict[str, Any]:
        """Execute the job and return the result dict (SPEC §9 shape)."""


def _compact_validation_error(exc: pydantic.ValidationError) -> str:
    parts = []
    for error in exc.errors(include_url=False):
        loc = ".".join(str(piece) for piece in error["loc"]) or "<root>"
        parts.append(f"{loc}: {error['msg']}")
    return f"invalid payload for {exc.title}: " + "; ".join(parts)
