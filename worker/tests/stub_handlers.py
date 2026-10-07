"""Deterministic stub handlers used by integration tests."""

from __future__ import annotations

import time
from collections.abc import Mapping
from typing import Any

from pydantic import Field

from taskmesh_worker.handlers import (
    JobCancelled,
    JobContext,
    JobHandler,
    TransientError,
    ValidationError,
)
from taskmesh_worker.models import StrictModel


class BoomPayload(StrictModel):
    failMode: str = Field(pattern=r"^(transient|permanent)$")


class BoomHandler(JobHandler):
    """Fails on demand with a retryable or non-retryable error."""

    job_type = "test_boom"
    payload_model = BoomPayload

    def run(self, payload: Mapping[str, Any], ctx: JobContext) -> dict[str, Any]:
        parsed = self.validate(payload)
        if parsed.failMode == "transient":
            raise TransientError("transient boom")
        raise ValidationError("permanent boom")


class WaitPayload(StrictModel):
    seconds: float = Field(ge=0.05, le=60)


class WaitHandler(JobHandler):
    """Sleeps cooperatively; raises JobCancelled when the runtime flags cancel."""

    job_type = "test_wait"
    payload_model = WaitPayload

    def run(self, payload: Mapping[str, Any], ctx: JobContext) -> dict[str, Any]:
        parsed = self.validate(payload)
        deadline = time.monotonic() + parsed.seconds
        while time.monotonic() < deadline:
            if ctx.cancelled:
                raise JobCancelled("wait handler observed cancellation")
            time.sleep(0.02)
        return {"waitedSeconds": parsed.seconds}


class ProgressPayload(StrictModel):
    steps: int = Field(ge=1, le=50)


class ProgressHandler(JobHandler):
    """Reports progress in steps; used to exercise the progress pipeline."""

    job_type = "test_progress"
    payload_model = ProgressPayload

    def run(self, payload: Mapping[str, Any], ctx: JobContext) -> dict[str, Any]:
        parsed = self.validate(payload)
        for step in range(1, parsed.steps + 1):
            time.sleep(0.05)
            ctx.report_progress(int(step / parsed.steps * 100), f"step {step}")
        return {"steps": parsed.steps}
