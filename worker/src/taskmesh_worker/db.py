"""PostgreSQL connectivity for the worker.

A thin wrapper around a single psycopg3 autocommit connection, with
reconnect-with-backoff semantics for transient database outages:

- ``ensure_connected`` retries forever by default (startup graceful
  degradation: log WARN, never crash) with exponential backoff capped at 30s.
- ``execute`` retries an autocommit statement once after a reconnect when the
  connection dies mid-flight (operational errors only; data errors propagate).
- ``transaction`` wraps multi-statement finalization in a real transaction
  (BEGIN/COMMIT) on the same connection; it deliberately does NOT retry —
  partial transactions must fail loudly to the caller.
"""

from __future__ import annotations

import logging
import time
from collections.abc import Iterator, Sequence
from contextlib import contextmanager
from typing import Any

import psycopg
from psycopg import OperationalError
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

log = logging.getLogger("taskmesh_worker.db")

BACKOFF_CAP_S = 30.0


def adapt(value: Any) -> Any:
    """Adapt Python containers to psycopg parameter types (dict -> jsonb)."""
    if isinstance(value, dict):
        return Jsonb(value)
    return value


def adapt_params(params: Sequence[Any] | None) -> tuple[Any, ...] | None:
    """Adapt an iterable of statement parameters."""
    if params is None:
        return None
    return tuple(adapt(p) for p in params)


class Database:
    """Owns one autocommit connection to the job queue database."""

    def __init__(self, url: str, *, connect_timeout_s: float = 10.0) -> None:
        self.url = url
        self.connect_timeout_s = connect_timeout_s
        self._conn: psycopg.Connection[dict] | None = None

    # -- lifecycle ---------------------------------------------------------

    def connect(self) -> psycopg.Connection[dict]:
        """Open the connection (raises on failure)."""
        self._conn = psycopg.connect(
            self.url,
            autocommit=True,
            row_factory=dict_row,
            connect_timeout=int(self.connect_timeout_s),
        )
        return self._conn

    def ensure_connected(
        self,
        *,
        max_attempts: int | None = None,
        base_delay_s: float = 0.5,
    ) -> None:
        """Retry connecting with exponential backoff.

        ``max_attempts=None`` retries forever (used at startup so the worker
        waits out database restarts instead of crashing).
        """
        attempt = 0
        while True:
            attempt += 1
            try:
                self.connect()
                if attempt > 1:
                    log.info("database connection established after %d attempts", attempt)
                return
            except OperationalError as exc:
                if max_attempts is not None and attempt >= max_attempts:
                    raise
                delay = min(BACKOFF_CAP_S, base_delay_s * (2 ** (attempt - 1)))
                log.warning(
                    "database unreachable (attempt %d): %s; retrying in %.1fs",
                    attempt,
                    str(exc).strip().splitlines()[0] if str(exc) else exc,
                    delay,
                )
                time.sleep(delay)

    def close(self) -> None:
        if self._conn is not None:
            try:
                self._conn.close()
            except Exception:  # pragma: no cover - best-effort close
                log.warning("error while closing database connection", exc_info=True)
            self._conn = None

    @property
    def connection(self) -> psycopg.Connection[dict]:
        if self._conn is None:
            raise RuntimeError("database not connected; call ensure_connected() first")
        return self._conn

    # -- execution ---------------------------------------------------------

    def execute(self, sql: str, params: Sequence[Any] | None = None) -> list[dict[str, Any]]:
        """Run one autocommit statement and return dict rows.

        Reconnects once on operational failure (dropped socket, restart) and
        retries the statement; anything else propagates to the caller.
        """
        adapted = adapt_params(params)
        try:
            with self.connection.cursor() as cur:
                cur.execute(sql, adapted)
                if cur.pgresult is not None and cur.description is not None:
                    return list(cur.fetchall())
                return []
        except OperationalError:
            log.warning("connection lost during statement; reconnecting", exc_info=True)
            self.close()
            self.ensure_connected(max_attempts=3)
            with self.connection.cursor() as cur:
                cur.execute(sql, adapted)
                if cur.pgresult is not None and cur.description is not None:
                    return list(cur.fetchall())
                return []

    @contextmanager
    def transaction(self) -> Iterator[psycopg.Connection[dict]]:
        """Yield the connection inside an explicit transaction.

        psycopg issues BEGIN on entry and COMMIT on clean exit (ROLLBACK on
        exception), even in autocommit mode.
        """
        with self.connection.transaction():
            yield self.connection

    def run(self, sql: str, params: Sequence[Any] | None = None) -> None:
        """Run one autocommit statement, discarding any result rows."""
        self.execute(sql, params)
