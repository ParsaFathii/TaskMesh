"""cpu_benchmark handler: bounded CPU-bound workloads, no I/O.

Two workloads (pure Python, deliberately single-threaded):

- ``primes`` — trial-division primality tests over successive candidate
  integers. One operation = one candidate tested.
- ``matrix`` — 256x256 matrix multiplications in plain Python loops. One
  operation = one completed multiplication. Sub-1 ops/s on typical hardware
  is expected and honest; the loop still reports time-based progress.

Both loops run until ``durationSeconds`` have elapsed, report progress on
the execution context (time-based percentage), and honor cooperative
cancellation. Results report ``threads = 1``: the benchmark itself is
single-threaded by design.
"""

from __future__ import annotations

import time
from collections.abc import Mapping
from typing import Any

from ..models import CpuBenchmarkPayload
from .base import JobCancelled, JobContext, JobHandler

MATRIX_SIZE = 256
PROGRESS_INTERVAL_S = 0.5
PRIMES_TIME_CHECK_EVERY = 256
PRIMES_CHUNK = 2048
MATRIX_ROW_CHUNK = 16


class CpuBenchmarkHandler(JobHandler):
    job_type = "cpu_benchmark"
    payload_model = CpuBenchmarkPayload

    def run(self, payload: Mapping[str, Any], ctx: JobContext) -> dict[str, Any]:
        parsed = self.validate(payload)
        deadline = time.monotonic() + parsed.durationSeconds
        started = time.monotonic()
        if parsed.workload == "primes":
            operations = self._run_primes(started, deadline, ctx)
        else:
            operations = self._run_matrix(started, deadline, ctx)
        duration_ms = int((time.monotonic() - started) * 1000)
        ops_per_second = round(operations / (duration_ms / 1000), 6) if duration_ms else 0.0
        return {
            "workload": parsed.workload,
            "operations": operations,
            "durationMs": duration_ms,
            "opsPerSecond": ops_per_second,
            "threads": 1,
        }

    # -- workloads ---------------------------------------------------------

    def _run_primes(self, started: float, deadline: float, ctx: JobContext) -> int:
        operations = 0
        candidate = 2
        next_progress = time.monotonic() + PROGRESS_INTERVAL_S
        while True:
            for _ in range(PRIMES_CHUNK):
                _is_prime(candidate)
                operations += 1
                candidate += 1
                if operations % PRIMES_TIME_CHECK_EVERY == 0 and time.monotonic() >= deadline:
                    return operations
            now = time.monotonic()
            if now >= deadline:
                return operations
            if now >= next_progress:
                self._report(ctx, started, deadline, now)
                next_progress = now + PROGRESS_INTERVAL_S

    def _run_matrix(self, started: float, deadline: float, ctx: JobContext) -> int:
        operations = 0
        size = MATRIX_SIZE
        a = _matrix_a(size)
        b = _matrix_b(size)
        result = [[0] * size for _ in range(size)]
        next_progress = time.monotonic() + PROGRESS_INTERVAL_S
        while True:
            for row_start in range(0, size, MATRIX_ROW_CHUNK):
                _multiply_rows(a, b, result, row_start, min(row_start + MATRIX_ROW_CHUNK, size))
                now = time.monotonic()
                if now >= deadline:
                    return operations
                if now >= next_progress:
                    self._report(ctx, started, deadline, now)
                    next_progress = now + PROGRESS_INTERVAL_S
            operations += 1

    def _report(self, ctx: JobContext, started: float, deadline: float, now: float) -> None:
        if ctx.cancelled:
            raise JobCancelled("cpu_benchmark observed cancellation")
        total = max(deadline - started, 1e-9)
        elapsed = min(now - started, total)
        ctx.report_progress(int(elapsed / total * 100))


def _is_prime(n: int) -> bool:
    if n < 2:
        return False
    if n in (2, 3):
        return True
    if n % 2 == 0:
        return False
    divisor = 3
    while divisor * divisor <= n:
        if n % divisor == 0:
            return False
        divisor += 2
    return True


def _matrix_a(size: int) -> list[list[int]]:
    return [[(i * 7 + j * 3) % 11 + 1 for j in range(size)] for i in range(size)]


def _matrix_b(size: int) -> list[list[int]]:
    return [[(i * 5 + j * 2) % 13 + 1 for j in range(size)] for i in range(size)]


def _multiply_rows(
    a: list[list[int]],
    b: list[list[int]],
    result: list[list[int]],
    row_start: int,
    row_end: int,
) -> None:
    """Compute result rows [row_start, row_end) of the product a x b."""
    size = len(b)
    for i in range(row_start, row_end):
        a_row = a[i]
        out_row = result[i]
        for j in range(size):
            acc = 0
            for k in range(size):
                acc += a_row[k] * b[k][j]
            out_row[j] = acc
