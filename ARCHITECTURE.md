# TaskMesh Architecture

Version 0.1.0. This document describes what is implemented in this repository; the
binding contract between components is [docs/SPEC.md](docs/SPEC.md). Component-level
notes live in each README ([backend](backend/README.md), [worker](worker/README.md),
[web](web/README.md), [android](android/README.md)).

## 1. Components

![System architecture](docs/assets/architecture.svg)

| Component | Technology | Responsibilities |
|---|---|---|
| API backend | Java 21, Spring Boot 3.5, Flyway, JPA/Hibernate 6.6, jjwt 0.12 | REST `/api/v1/*`, WebSocket `/ws/v1/events`, auth + roles, job submission & idempotency, result serving, audit log, metrics, maintenance sweeper |
| Database | PostgreSQL 16 | datastore (8 tables) **and** job queue (SKIP LOCKED claims, LISTEN/NOTIFY wakeups, leases) |
| Worker runtime | Python 3.12, psycopg 3, Pillow, FastAPI | job claiming, execution of 7 typed handlers, heartbeats + lease renewal, progress, finalization, control API `:9100` |
| Web console | React 18, Vite, TypeScript strict, TanStack Query, zustand | operations UI: queue lanes, event ticker, job timeline, live logs, metrics; WS with reconnect/backoff |
| Android client | Kotlin 2.0, Jetpack Compose, Retrofit | mobile access to the same REST API; local notification center fed by 30 s polling |

## 2. PostgreSQL as the queue — decision and rationale

TaskMesh deliberately uses one PostgreSQL instance as both datastore and queue:

1. **Atomic claiming with `FOR UPDATE SKIP LOCKED`** — a worker's claim is a single
   `UPDATE` that selects the next eligible row and flips it to `RUNNING` in the same
   transaction. Two workers racing for the same job cannot both win: the loser's
   `SELECT` skips the locked row. This gives at-most-once claiming *per attempt*
   without any coordination service.
2. **Backoff and priority are data, not broker config** — `available_at` (earliest
   claim time) and the priority-with-aging expression are ordinary columns/SQL, so
   queue state is inspectable with SQL and transactional with everything else
   (submission, retries, results).
3. **Wakeups with `LISTEN/NOTIFY`** — every job/worker state change is published as
   a compact JSON notification on channel `taskmesh_events`; the backend's LISTEN
   connection turns these into WebSocket frames, so clients see queue transitions
   in real time without polling the jobs table. Workers themselves claim on a
   short poll (1 s idle interval) rather than LISTEN, which keeps the claim path a
   single transaction.
4. **Leases + sweeper for visibility** — a claimed job has `lease_expires_at`; the
   worker renews it while alive. If the worker dies, the row stays `RUNNING` until the
   backend sweeper (fixed 5 s rate) notices the expired lease and re-queues the job.
   Crash recovery falls out of the data model.

**Why not RabbitMQ now**: one fewer service to deploy, secure and back up; queue
semantics (ack, redelivery, priority) are expressed in rows we already need for
auditing; and 0.1.0 scale assumptions (single database, moderate enqueue rates) do not
require broker throughput. The queue is isolated behind the repository/DAO boundary
(`JobRepository` claim queries, worker `runtime.py` claim statement), so introducing
RabbitMQ later is a documented future path that would not change the domain model.
The trade-offs: enqueue throughput is bounded by PostgreSQL write capacity, and the
queue shares the database's failure domain.

## 3. Job state machine

![Job lifecycle](docs/assets/job-lifecycle.svg)

Legal transitions (validated by the backend `JobStateMachine` and mirrored in the
worker):

```
QUEUED    → RUNNING      worker claim (SKIP LOCKED)
QUEUED    → CANCELLED    user cancel; immediate
RUNNING   → SUCCEEDED    worker success
RUNNING   → FAILED       non-retryable failure OR retries exhausted
RUNNING   → RETRYING     retryable failure, retry_count < max_retries
RUNNING   → TIMED_OUT    timeout, retries exhausted
RUNNING   → CANCELLED    worker honors cancel_requested; sweeper finalizes after lease expiry + 30 s grace
RETRYING  → RUNNING      re-claim after available_at
RETRYING  → CANCELLED    user cancel while waiting
FAILED    → QUEUED       manual retry via API (resets retry_count)
TIMED_OUT → QUEUED       manual retry
CANCELLED → QUEUED       manual retry
```

Every other transition is rejected. `retry_count` increments when entering `RETRYING`.
Manual retry keeps `last_error` for traceability.

## 4. The claim statement

The worker runs (parameterized; SPEC §7):

```sql
WITH next_job AS (
  SELECT id FROM jobs
  WHERE status IN ('QUEUED','RETRYING') AND available_at <= now()
  ORDER BY (CASE priority WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3
                          WHEN 'NORMAL' THEN 2 ELSE 1 END)::double precision
         + LEAST(EXTRACT(EPOCH FROM (now() - created_at)) / 3600.0, 1.0) DESC,
           created_at ASC
  FOR UPDATE SKIP LOCKED
  LIMIT 1
)
UPDATE jobs j
SET status = 'RUNNING', worker_id = :workerId, started_at = now(),
    queued_at = COALESCE(j.queued_at, now()),
    lease_expires_at = now() + make_interval(secs => GREATEST(30, j.timeout_seconds))
FROM next_job WHERE j.id = next_job.id
RETURNING j.*;
```

What each piece does:

- `status IN ('QUEUED','RETRYING') AND available_at <= now()` — only claimable,
  backoff-respecting rows (partial index `jobs_claim_idx` backs this).
- The `ORDER BY` expression is the **priority ranking with aging** (below).
- `FOR UPDATE SKIP LOCKED` — the atomic claim: row locking happens inside the CTE
  scan; a concurrently locked job is invisible rather than blocking.
- The `UPDATE … RETURNING` — claim and data return in one transaction; the worker
  commits before executing, so the claim survives a worker crash (the lease handles
  the cleanup).
- `lease_expires_at = now() + GREATEST(30, timeout_seconds)` — the initial lease.
- The worker additionally filters `type = ANY(%s)` to its advertised capabilities
  (a documented, SPEC-noted extension; with the default capability set it is a
  no-op).

## 5. Priority and starvation

Claim ordering uses
`effective_score = priority_rank + LEAST(age_hours, 1.0)` with
`priority_rank` = 4/3/2/1 for CRITICAL/HIGH/NORMAL/LOW, tie-broken by `created_at ASC`.
The aging term saturates at 1.0 after an hour, so a LOW job (1) effectively reaches
NORMAL (2) rank after ~1 hour and HIGH (3) after ~2 hours — starvation is bounded by
rank shifts rather than unbounded. Queue depth per priority is exposed in
`GET /api/v1/metrics`.

## 6. Retry and backoff

![Retry flow](docs/assets/retry-flow.svg)

- Handler errors are classified: **validation errors → non-retryable** (job FAILED
  immediately); **transient errors and unexpected exceptions → retryable**.
- On a retryable failure with `retry_count < max_retries` (default 3, range 0–10):
  the job enters `RETRYING` with
  `available_at = now() + backoff`, `backoff = min(300, 2^retry_count · 5)` seconds —
  5 s, 10 s, 20 s, 40 s, 80 s, 160 s, capped at 300 s.
- Retries exhausted: `FAILED` (error) or `TIMED_OUT` (timeout cause).
- Manual retry (`POST /api/v1/jobs/{id}/retry`, owner or OPERATOR+) resets
  `retry_count` to 0 and re-queues the job.
- A worker receiving SIGINT aborts its current job as `RETRYING` and records the
  attempt outcome `ABANDONED`; the same outcome is recorded by the sweeper when a
  crashed worker's lease expires.

## 7. Timeout layers

Timeouts are enforced by three cooperating layers:

1. **Worker execution thread** — each handler runs in a thread with
   `timeout_seconds` (job column, 5–3600, default 120); on expiry the job is
   finalized (TIMED_OUT, or RETRYING if retries remain). CPython threads cannot be
   killed, so the runtime finalizes the job immediately and the orphaned daemon
   thread dies with the process (documented limitation).
2. **Lease** — while executing, the worker renews `lease_expires_at` on every
   heartbeat (interval + `TASKMESH_LEASE_MARGIN_S`, default 10 s + 15 s). A live
   worker therefore never has its job stolen.
3. **Backend sweeper (5 s)** — for `RUNNING` rows with `lease_expires_at < now()`:
   if `started_at + timeout_seconds + 60 s < now()`, the hard-timeout path applies
   (TIMED_OUT/RETRYING); otherwise the worker is treated as crashed → RETRYING with
   backoff and attempt outcome `ABANDONED`. `cancel_requested` rows are finalized
   CANCELLED after lease expiry + 30 s grace. Workers silent for
   `3 × heartbeat_interval_s` are marked OFFLINE.

## 8. Cancellation

- `QUEUED` jobs are cancelled immediately by the API (QUEUED → CANCELLED).
- `RUNNING` jobs: the API only sets `cancel_requested = true`. The worker checks the
  flag before claiming, between execution phases, and at completion — cancellation is
  cooperative, matching the timeout limitation above. If the worker does not
  finalize within the lease + 30 s grace, the sweeper finalizes CANCELLED.
- `RETRYING` jobs waiting in backoff can be cancelled (RETRYING → CANCELLED).
- Cancel of a terminal job is rejected by the state machine.

## 9. Idempotency

Submission accepts `idempotencyKey` (body field or `Idempotency-Key` header). A
unique partial index `jobs(owner_id, idempotency_key) WHERE idempotency_key IS NOT NULL`
makes duplicates a constraint conflict the service turns into a friendly response:
the first submission returns `201 Created` with the new job; a duplicate returns
`200 OK` with the existing job. This is verified end-to-end by the smoke test.

## 10. WebSocket event flow

```
worker / API code
  → pg_notify('taskmesh_events', '{"t":"job","id":"…","s":"RUNNING","p":42}')   (compact, <200 B)
  → backend PgListener (dedicated LISTEN connection)
  → EventBroadcaster (enriches: job/worker names, timestamps, levels)
  → EventsWebSocket sessions (ws://…/ws/v1/events?token=<JWT>)
  → clients receive frames:
     hello · queue.event · job.updated · job.log · worker.updated
```

Authentication for the WS endpoint happens once at connect time via the `token`
query parameter (the same 12 h JWT). The web console reconnects with exponential
backoff 1 s → 30 s (±20 % jitter) and force-reconnects after 30 s of silence; WS
frames additionally drive TanStack Query cache invalidation, so tables and lanes
update without polling.

## 11. Result storage layout

- **Inline**: serialized result ≤ 32 KiB → `job_results.kind = 'inline'`,
  `inline` JSONB column.
- **File**: larger results (and always the `image_resize` image bytes) →
  `TASKMESH_STORAGE_DIR/results/<job_id>.json|.bin`; the row records `file_path`
  (relative), `size_bytes`, `checksum_sha256`. `image_resize` keeps its metadata
  dict in `inline` alongside `kind = file`.
- The directory **must be shared** (same volume/path) between backend and workers —
  workers write, the backend serves. The serving path is hardened: the stored path
  is resolved canonically and must stay under the results root (traversal rejected),
  and responses carry `X-Job-Result-SHA256` so clients can verify bytes.

## 12. Scaling notes

- **Workers scale horizontally** with no coordination: each instance claims with
  SKIP LOCKED independently (verified in integration tests with concurrent claimers,
  and by the crash-recovery E2E check where worker 2 picks up worker 1's abandoned
  job). Compose: `--scale worker=N`.
- **The API can scale horizontally for reads/requests**, with one 0.1.0 caveat: the
  maintenance sweeper assumes a single instance. Compose pins `backend` to scale 1;
  running multiple sweeper instances would double-finalize (the state machine
  rejects illegal transitions, so it stays correct, but noisy). Before scaling the
  API tier, add single-sweeper coordination (advisory lock or leader election).
- **PostgreSQL** is the shared bottleneck by design (see §2): connections, enqueue
  rate, and NOTIFY volume all land there. The queue boundary is isolated so a broker
  can be introduced without domain changes.

## 13. Data model

Eight tables (canonical DDL: `docs/SPEC.md` §4 / `backend/src/main/resources/db/migration/V1__init.sql`):

| Table | Purpose |
|---|---|
| `users` | accounts, BCrypt hashes, role (ADMIN/OPERATOR/USER) |
| `projects` | job grouping with owner |
| `jobs` | the queue and state machine: status, priority, payload JSONB, `available_at` (backoff), `lease_expires_at`, `retry_count`/`max_retries`, `timeout_seconds`, `progress`, `cancel_requested`, idempotency key |
| `job_attempts` | one row per attempt: worker, start/finish, outcome (SUCCEEDED/FAILED/TIMED_OUT/CANCELLED/ABANDONED) |
| `job_logs` | per-job log lines (level, message, metadata JSONB) |
| `job_results` | inline JSONB or file reference + size + sha256 |
| `workers` | worker registry: status, capabilities, heartbeat, current job, control port |
| `audit_logs` | auth.login, project.*, job.create/cancel/retry, user.create events |

Flyway migrations: `V1__init.sql` (schema), `V2__seed_users.sql` (three local-dev
accounts with pre-hashed passwords).
