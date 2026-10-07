"""Job type registry: maps job types to handler instances.

The registry is the worker's single source of truth for which job types it
can execute; the runtime claims only jobs whose ``type`` is registered
(worker capabilities, SPEC §10).
"""

from __future__ import annotations

from collections.abc import Iterable

from .handlers import (
    ArchiveInspectionHandler,
    CpuBenchmarkHandler,
    CsvAnalysisHandler,
    HashSha256Handler,
    ImageResizeHandler,
    JobHandler,
    JsonTransformHandler,
    TextStatisticsHandler,
)

BUILTIN_HANDLERS: tuple[type[JobHandler], ...] = (
    ArchiveInspectionHandler,
    CpuBenchmarkHandler,
    CsvAnalysisHandler,
    HashSha256Handler,
    ImageResizeHandler,
    JsonTransformHandler,
    TextStatisticsHandler,
)

ALL_JOB_TYPES = tuple(sorted(h.job_type for h in BUILTIN_HANDLERS))

__all__ = [
    "ALL_JOB_TYPES",
    "BUILTIN_HANDLERS",
    "HandlerRegistry",
]


class HandlerRegistry:
    """Registry of job type handlers; one handler instance per type."""

    def __init__(self) -> None:
        self._handlers: dict[str, JobHandler] = {}

    def register(self, handler_cls: type[JobHandler]) -> None:
        job_type = handler_cls.job_type
        if job_type in self._handlers:
            raise ValueError(f"duplicate handler registration for job type {job_type!r}")
        self._handlers[job_type] = handler_cls()

    def handler(self, job_type: str) -> JobHandler | None:
        return self._handlers.get(job_type)

    def job_types(self) -> list[str]:
        return sorted(self._handlers)

    @classmethod
    def default(cls, capabilities: Iterable[str] | None = None) -> HandlerRegistry:
        """Build the registry for the built-in catalog, optionally filtered."""
        registry = cls()
        wanted = set(capabilities) if capabilities is not None else None
        for handler_cls in BUILTIN_HANDLERS:
            if wanted is None or handler_cls.job_type in wanted:
                registry.register(handler_cls)
        return registry
