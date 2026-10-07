-- TaskMesh initial schema (SPEC §4, canonical DDL — do not deviate).

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
