"""Signal-driven shutdown coordination (SPEC §10).

- SIGTERM → *drain*: stop claiming, finish the current job, then exit 0.
- SIGINT → *abort*: abandon the current job (crash semantics, job goes to
  RETRYING with a job_attempts row of outcome ABANDONED) and exit 130.
  A second SIGINT skips database finalization and hard-exits 130.

The coordinator only sets flags/events; :mod:`taskmesh_worker.runtime`
performs the actual finalization so the signal handler stays reentrant-safe.
"""

from __future__ import annotations

import logging
import os
import signal
import threading

log = logging.getLogger("taskmesh_worker.shutdown")

SIGINT_EXIT_CODE = 130


class ShutdownCoordinator:
    """Thread-safe signal state shared with the runtime loop."""

    def __init__(self) -> None:
        self._drain = threading.Event()
        self._abort = threading.Event()
        self._stop = threading.Event()
        self._sigint_count = 0

    # -- state -------------------------------------------------------------

    @property
    def draining(self) -> bool:
        return self._drain.is_set()

    @property
    def abort_requested(self) -> bool:
        return self._abort.is_set()

    @property
    def stop_requested(self) -> bool:
        return self._drain.is_set() or self._abort.is_set()

    def request_stop(self) -> None:
        """Wake the main loop without changing the drain/abort decision."""
        self._stop.set()

    def request_drain(self) -> None:
        """Behave like SIGTERM (drain, finish current job, exit 0)."""
        self._drain.set()
        self._stop.set()

    def wait(self, timeout: float) -> bool:
        """Block until a stop signal or the timeout; True when woken by a signal."""
        return self._stop.wait(timeout)

    # -- installation -------------------------------------------------------

    def install(self) -> None:
        """Install SIGTERM/SIGINT handlers for the current (main) thread."""

        def on_sigterm(signum: int, _frame: object) -> None:
            log.info("received SIGTERM; draining (will finish current job)")
            self._drain.set()
            self._stop.set()

        def on_sigint(signum: int, _frame: object) -> None:
            self._sigint_count += 1
            if self._sigint_count >= 2:
                log.warning("second SIGINT received; forcing immediate exit")
                os._exit(SIGINT_EXIT_CODE)
            log.info("received SIGINT; aborting current job (crash semantics)")
            self._abort.set()
            self._stop.set()

        signal.signal(signal.SIGTERM, on_sigterm)
        signal.signal(signal.SIGINT, on_sigint)
