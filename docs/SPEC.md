# TaskMesh — System Specification (Single Source of Truth)

This document is the binding contract between all TaskMesh components.
Every implementation (Java backend, Python worker, web, Android) MUST conform to it.
Version: 0.1.0

---

## 1. Identity

- Product: TaskMesh — Distributed Job Processing and Worker Orchestration Platform
- Owner / Author: Parsa Fathi (https://github.com/ParsaFathii)
- Copyright: © 2026 Parsa Fathi
- License: Apache-2.0
- Version: 0.1.0 (all components)

## 2. Local development ports / hosts

| Component        | Host:Port       | Notes                                    |
|------------------|-----------------|------------------------------------------|
| PostgreSQL 16    | localhost:5433  | db `taskmesh`, user `taskmesh`, no password (local dev, trust auth; unix socket `/tmp`) |
| Java API         | localhost:8080  | Spring Boot, context path `/`            |
| Worker control   | localhost:9100+ | FastAPI per worker (offset per instance) |
| Web dev server   | localhost:5173  | Vite (dev only); production served by nginx container |

Connection strings:
- JDBC: `jdbc:postgresql://localhost:5433/taskmesh`
- Python: `postgresql://taskmesh@localhost:5433/taskmesh` (or `host=/tmp port=5433`)

## 3. Queue / broker decision (documented justification)

TaskMesh uses **PostgreSQL as both datastore and job queue**:
- Claims via `SELECT ... FOR UPDATE SKIP LOCKED` (transactional, at-most-once claiming per attempt)
- Backoff/priority via `available_at` + priority ranking with aging
- Real-time wakeups via `LISTEN/NOTIFY` on channel `taskmesh_events`
- Lease/visibility timeout via `lease_expires_at`, enforced by a backend sweeper

Rationale: a single durable store, transactional job claiming, no separate broker
to operate, and all queue semantics (ack, redelivery, priority) are auditable in SQL.
The queue layer is isolated behind a repository/DAO boundary so RabbitMQ can be
introduced later without changing the domain model.

## 4. Database schema (canonical DDL — Flyway V1__init.sql in backend, mirrored in database/schema.sql)

```sql
CREATE TYPE job_status    AS ENUM ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED','RETRYING','TIMED_OUT');
CREATE TYPE job_priority  AS ENUM ('LOW','NORMAL','HIGH','CRITICAL');
CREATE TYPE user_role     AS ENUM ('ADMIN','OPERATOR','USER');
CREATE TYPE worker_status AS ENUM ('STARTING','IDLE','BUSY','DRAINING','OFFLINE','ERROR');
CREATE TYPE log_level     AS ENUM ('DEBUG','INFO','WARN','ERROR');
CREATE TYPE result_kind   AS ENUM ('inline','file');

CREATE TABLE users (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  username      TEXT NOT NULL UNIQUE,
  email         TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,                -- BCrypt
  role          user_role NOT NULL DEFAULT 'USER',
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE projects (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name        TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  owner_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workers (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name                 TEXT NOT NULL,
  hostname             TEXT NOT NULL,
  version              TEXT NOT NULL,
  status               worker_status NOT NULL DEFAULT 'STARTING',
  capabilities         TEXT[] NOT NULL DEFAULT '{}',
  started_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_heartbeat       TIMESTAMPTZ NOT NULL DEFAULT now(),
  heartbeat_interval_s INT NOT NULL DEFAULT 10,
  current_job_id       UUID,
  control_port         INT
);

CREATE TABLE jobs (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id       UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  owner_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  type             TEXT NOT NULL,
  priority         job_priority NOT NULL DEFAULT 'NORMAL',
  payload          JSONB NOT NULL,
  status           job_status NOT NULL DEFAULT 'QUEUED',
  idempotency_key  TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  queued_at        TIMESTAMPTZ,
  started_at       TIMESTAMPTZ,
  completed_at     TIMESTAMPTZ,
  retry_count      INT NOT NULL DEFAULT 0,
  max_retries      INT NOT NULL DEFAULT 3,
  timeout_seconds  INT NOT NULL DEFAULT 120,
  worker_id        UUID REFERENCES workers(id) ON DELETE SET NULL,
  progress         INT,                                   -- 0..100, NULL if not reported
  cancel_requested BOOLEAN NOT NULL DEFAULT false,
  available_at     TIMESTAMPTZ NOT NULL DEFAULT now(),    -- earliest claim time (backoff)
  lease_expires_at TIMESTAMPTZ,
  last_error       TEXT,
  CONSTRAINT jobs_progress_range CHECK (progress IS NULL OR (progress >= 0 AND progress <= 100)),
  CONSTRAINT jobs_max_retries CHECK (max_retries BETWEEN 0 AND 10),
  CONSTRAINT jobs_timeout_range CHECK (timeout_seconds BETWEEN 5 AND 3600)
);
CREATE UNIQUE INDEX jobs_idempotency ON jobs(owner_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX jobs_claim_idx ON jobs (status, available_at) WHERE status IN ('QUEUED','RETRYING');
CREATE INDEX jobs_project_idx ON jobs (project_id, created_at DESC);
CREATE INDEX jobs_owner_idx ON jobs (owner_id, created_at DESC);
CREATE INDEX jobs_status_idx ON jobs (status, created_at DESC);
CREATE INDEX jobs_lease_idx ON jobs (lease_expires_at) WHERE status = 'RUNNING';

CREATE TABLE job_attempts (
  id              BIGSERIAL PRIMARY KEY,
  job_id          UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  attempt_number  INT NOT NULL,
  worker_id       UUID REFERENCES workers(id) ON DELETE SET NULL,
  started_at      TIMESTAMPTZ NOT NULL,
  finished_at     TIMESTAMPTZ,
  outcome         TEXT,                    -- SUCCEEDED | FAILED | TIMED_OUT | CANCELLED | ABANDONED
  error           TEXT
);
CREATE INDEX job_attempts_job_idx ON job_attempts (job_id, attempt_number DESC);

CREATE TABLE job_logs (
  id          BIGSERIAL PRIMARY KEY,
  job_id      UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  worker_id   UUID,
  level       log_level NOT NULL,
  message     TEXT NOT NULL,
  metadata    JSONB,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX job_logs_job_idx ON job_logs (job_id, id DESC);
CREATE INDEX job_logs_created_idx ON job_logs (created_at DESC);

CREATE TABLE job_results (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  job_id          UUID NOT NULL UNIQUE REFERENCES jobs(id) ON DELETE CASCADE,
  kind            result_kind NOT NULL,
  inline          JSONB,
  file_path       TEXT,                    -- relative to storage root, no traversal (validated)
  size_bytes      BIGINT,
  checksum_sha256 TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_logs (
  id            BIGSERIAL PRIMARY KEY,
  actor_id      UUID,
  actor_name    TEXT,
  action        TEXT NOT NULL,             -- e.g. auth.login, job.create, job.cancel
  resource_type TEXT,
  resource_id   TEXT,
  result        TEXT NOT NULL,             -- SUCCESS | FAILURE
  metadata      JSONB,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_logs_created_idx ON audit_logs (created_at DESC);
CREATE INDEX audit_logs_actor_idx ON audit_logs (actor_id, created_at DESC);
```

Seed (Flyway V2__seed_users.sql): three BCrypt-hashed local-dev accounts —
`admin` / `operator` / `user` (roles ADMIN / OPERATOR / USER). Default dev passwords are
documented in README + `.env.example`; hashes are generated at build time by the backend
agent and embedded in the migration (never plaintext).

## 5. Job state machine (validated on backend service layer AND worker)

```
QUEUED   → RUNNING                    (worker claim, SKIP LOCKED)
QUEUED   → CANCELLED                  (user cancel; immediate)
RUNNING  → SUCCEEDED                  (worker success)
RUNNING  → FAILED                     (worker failure, non-retryable OR retries exhausted)
RUNNING  → RETRYING                   (worker retryable failure, retry_count < max_retries)
RUNNING  → TIMED_OUT                  (timeout, retries exhausted)
RUNNING  → CANCELLED                  (worker honors cancel_requested; sweeper finalizes after lease expiry + 30s grace)
RETRYING → RUNNING                    (re-claim after available_at)
RETRYING → CANCELLED                  (user cancel while waiting)
FAILED   → QUEUED                     (manual retry via API; resets retry_count, last_error kept)
TIMED_OUT→ QUEUED                     (manual retry)
CANCELLED→ QUEUED                     (manual retry)
```
Every other transition is illegal and must be rejected.
`RETRYING` waits `available_at = now() + backoff` where
`backoff = min(300, 2^retry_count * 5)` seconds (exponential, capped 5 min).
retry_count increments when entering RETRYING.

## 6. Priority & starvation

Claim ordering (single SQL): `effective_score = priority_rank + LEAST(age_seconds/3600.0, 1.0)`
with priority_rank: CRITICAL=4, HIGH=3, NORMAL=2, LOW=1; secondary sort `created_at ASC`.
Aging guarantees no low-priority starvation beyond ~1 rank shift per hour.

## 7. Claiming protocol (worker → PostgreSQL)

```sql
WITH next_job AS (
  SELECT id FROM jobs
  WHERE status IN ('QUEUED','RETRYING') AND available_at <= now()
  ORDER BY (CASE priority WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'NORMAL' THEN 2 ELSE 1 END)::double precision
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

- Lease renewal: worker updates `lease_expires_at` and `progress` during execution (same cadence as heartbeat).
- Backend sweeper (fixed 5s): 
  - `RUNNING` and `lease_expires_at < now()`: if `started_at + timeout + 60s < now()` → TIMED_OUT/RETRYING path; else crashed worker → RETRYING (backoff), outcome `ABANDONED` in job_attempts.
  - `RUNNING` and `cancel_requested` and lease expired + 30s grace → CANCELLED.
  - workers with `last_heartbeat < now() - 3*heartbeat_interval_s` → OFFLINE.
- Duplicate delivery is impossible per attempt: claim is a single atomic UPDATE; only the
  row-owning transaction sees the job as RUNNING.

## 8. REST API (all under /api/v1, JSON)

Auth: `Authorization: Bearer <JWT>` (HS256, 12h). Login: `POST /api/v1/auth/login`
`{username, password}` → `200 {token, tokenType:"Bearer", expiresIn:43200, user:{id,username,role}}`
Errors: `{"error":{"code":"NOT_FOUND","message":"..."}}` with proper status codes.

| Method | Path                        | Roles            | Notes |
|--------|-----------------------------|------------------|-------|
| GET    | /api/v1/health              | public           | `{status:"UP",db:"UP",version:"0.1.0"}` |
| POST   | /api/v1/auth/login          | public           | audit `auth.login` |
| GET    | /api/v1/me                  | any              | current user |
| GET    | /api/v1/users               | ADMIN            | list users |
| POST   | /api/v1/users               | ADMIN            | create user `{username,email,password,role}` |
| GET    | /api/v1/projects?page&size  | any (filtered)   | USER sees own; ADMIN/OPERATOR all |
| POST   | /api/v1/projects            | any              | `{name,description}` |
| GET    | /api/v1/projects/{id}       | owner / op+      | |
| PATCH  | /api/v1/projects/{id}       | owner            | `{name?,description?}` |
| DELETE | /api/v1/projects/{id}       | owner            | |
| GET    | /api/v1/jobs?projectId&status&type&priority&page&size | any (filtered) | |
| POST   | /api/v1/jobs                | any              | `{projectId,type,priority,payload,maxRetries?,timeoutSeconds?,idempotencyKey?}`; header `Idempotency-Key` also honored; 201 created / 200 existing on duplicate key; payload validated against type catalog; audit `job.create` |
| GET    | /api/v1/jobs/{id}           | owner / op+      | full job incl. worker summary |
| POST   | /api/v1/jobs/{id}/cancel    | owner / op+      | QUEUED→CANCELLED now; RUNNING sets `cancel_requested`; audit `job.cancel` |
| POST   | /api/v1/jobs/{id}/retry     | owner / op+      | FAILED/TIMED_OUT/CANCELLED → QUEUED, retry_count=0; audit `job.retry` |
| GET    | /api/v1/jobs/{id}/result    | owner / op+      | inline JSON, or file bytes (`?download=true` forces file) with `X-Job-Result-SHA256` header |
| GET    | /api/v1/jobs/{id}/logs?level&limit | owner / op+ | newest-first list |
| GET    | /api/v1/jobs/{id}/attempts | owner / op+      | attempt history |
| GET    | /api/v1/workers             | OPERATOR, ADMIN  | includes `stale: true/false` computed |
| GET    | /api/v1/workers/{id}        | OPERATOR, ADMIN  | |
| GET    | /api/v1/logs?actor&action&page&size | OPERATOR, ADMIN | audit logs |
| GET    | /api/v1/metrics             | OPERATOR, ADMIN  | counts by status, queue depth per priority, workers by status, avg/max duration (succeeded last 24h), throughput (completed/hour last 24h) |
| GET    | /api/v1/job-types           | any              | type catalog with payload JSON schemas + example payloads |

Pagination convention: `page` (0-based), `size` (default 25, max 100) →
`{items:[...], page, size, total}`.

WebSocket: `GET /ws/v1/events?token=<JWT>` (raw JSON text frames, server→client push):
```json
{"type":"job.updated","jobId":"...","status":"RUNNING","progress":42,"workerId":"...","timestamp":"ISO-8601"}
{"type":"job.log","jobId":"...","level":"INFO","message":"...","timestamp":"..."}
{"type":"worker.updated","workerId":"...","status":"BUSY","currentJobId":"...","timestamp":"..."}
{"type":"queue.event","event":"job.enqueued|job.requeued","jobId":"...","timestamp":"..."}
{"type":"hello","server":"taskmesh-0.1.0","timestamp":"..."}
```
Backend implements LISTEN on `taskmesh_events`, enriches, and broadcasts to all
authenticated WS sessions.

NOTIFY payloads (small, < 200 bytes; enrichment happens in backend):
```json
{"t":"job","id":"<uuid>","s":"RUNNING","p":42}
{"t":"joblog","id":"<uuid>","l":"INFO","m":"..."}
{"t":"worker","id":"<uuid>","s":"BUSY","j":"<uuid|null>"}
```

## 9. Job type catalog (handler payloads & results)

Common result envelope written to job_results:
- inline (≤ 32 KB serialized): `kind=inline`, `inline` = result JSON
- file (> 32 KB): `kind=file`, stored under `data/results/<job_id>[.bin]`, sha256 recorded

| type | payload | result |
|------|---------|--------|
| csv_analysis | `{csv: string ≤2MB, delimiter?: "," "; "\t", hasHeader?: bool=true}` | `{rows, columns:[{name,type,nonNull,missing,unique,min?,max?,mean?}], delimiterUsed, parseErrors}` |
| json_transform | `{input: object, operations: [{op:"pick"\|"remove"\|"rename"\|"flatten", ...}]}` pick:{op,paths:[]}, remove:{op,paths:[]}, rename:{op,from,to}, flatten:{op,separator:"."} | `{output: object, applied: int, operations: int}` |
| image_resize | `{imageBase64: str, width: 1..10000, height: 1..10000, maintainAspect: bool=true, format?: "png"\|"jpeg"}` | `{width,height,format,bytes,sha256}` — resized image stored as FILE result |
| hash_sha256 | `{contentBase64?: str, text?: str}` exactly one | `{sha256, bytes, source:"base64"\|"text"}` |
| text_statistics | `{text: str ≤2MB, caseSensitive?: bool}` | `{characters, charactersNoSpaces, words, uniqueWords, lines, paragraphs, avgWordLength, readingTimeSeconds, topWords:[{word,count}] ≤20}` |
| archive_inspection | `{archiveBase64: str ≤20MB, maxEntries?: 500}` | `{format:"zip", totalEntries, totalUncompressedBytes, compressionRatio, entries:[{name,size,compressedSize,isDir,modified}] ≤ maxEntries, truncated: bool}` |
| cpu_benchmark | `{workload: "primes"\|"matrix", durationSeconds: 1..30 default 5}` | `{workload, operations, durationMs, opsPerSecond, threads}` |

All handlers: strict input validation (fail fast, NON-retryable errors for invalid payload),
retryable errors for transient issues. No arbitrary code execution. Job handler runs with
a hard timeout enforced by the worker runtime.

## 10. Worker runtime (Python 3.12+)

- Package `taskmesh_worker`, entry `python -m taskmesh_worker` (also console script `taskmesh-worker`)
- Registration: INSERT INTO workers (id, name, hostname, version, capabilities, heartbeat_interval_s, control_port) — id kept for process lifetime
- Heartbeat: every `TASKMESH_HEARTBEAT_INTERVAL_S` (default 10): UPDATE workers SET last_heartbeat, status (IDLE when no current job, BUSY when running), current_job_id; renew lease if running
- Loop: claim → execute handler (thread with timeout) → report progress (0..100) via UPDATE + job_logs + NOTIFY → SUCCEEDED (insert job_results, insert job_attempt) / FAILED / RETRYING (backoff)
- Cancellation: checks `cancel_requested` before claim, between phases, and at completion; finalizes CANCELLED
- Graceful shutdown: SIGTERM → DRAINING (finish current job, no new claims, exit 0); SIGINT → abort current job as RETRYING (crash semantics), exit 130
- Stale detection handled by backend sweeper (worker never fakes liveness; if process dies, heartbeats stop)
- Control API (FastAPI, uvicorn, port `TASKMESH_CONTROL_PORT` default 9100): `GET /health` → `{status, workerId, currentJobId, uptimeS}`; `GET /status` → full worker row
- Env config: `TASKMESH_DATABASE_URL` (default `postgresql://taskmesh@localhost:5433/taskmesh`), `TASKMESH_CAPABILITIES` (comma list; default = all 7 types), `TASKMESH_HEARTBEAT_INTERVAL_S`, `TASKMESH_LEASE_MARGIN_S=15`, `TASKMESH_CONTROL_PORT`, `TASKMESH_STORAGE_DIR` (default `./data`), `TASKMESH_WORKER_NAME`
- Logging: structured JSON lines to stdout; job-level logs into job_logs

## 11. Auth & roles (backend-enforced)

- BCrypt (spring-security-crypto `BCryptPasswordEncoder`), cost 10
- JWT HS256 via jjwt 0.12.x; secret from `TASKMESH_JWT_SECRET` (dev default `taskmesh-dev-secret-change-me` — logged warning when default detected)
- Roles: ADMIN (users, workers, all projects/jobs, logs, metrics, config), OPERATOR (workers, all jobs retry/cancel, logs, metrics), USER (own projects/jobs/results, submit jobs)
- Authorization ALWAYS server-side; UI only hides what backend already denies

## 12. Configuration (env)

| Var | Default | Component |
|-----|---------|-----------|
| TASKMESH_DB_URL | jdbc:postgresql://localhost:5433/taskmesh | backend |
| TASKMESH_DB_USER / TASKMESH_DB_PASSWORD | taskmesh / (empty local) | backend |
| TASKMESH_JWT_SECRET | taskmesh-dev-secret-change-me | backend |
| TASKMESH_STORAGE_DIR | ./data | backend, worker (shared volume in prod) |
| TASKMESH_SERVER_PORT | 8080 | backend |
| TASKMESH_DATABASE_URL | postgresql://taskmesh@localhost:5433/taskmesh | worker |
| TASKMESH_HEARTBEAT_INTERVAL_S | 10 | worker |
| TASKMESH_CAPABILITIES | all 7 | worker |
| TASKMESH_CONTROL_PORT | 9100 | worker |
| WEB_PORT | 5173 dev / 80 nginx | web |

## 13. Versioning

All components report version `0.1.0` (`/api/v1/health`, worker `GET /health`,
web `package.json`, android `versionName`, docker labels, git tag `v0.1.0`).
