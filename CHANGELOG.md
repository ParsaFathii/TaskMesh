# Changelog

All notable changes to TaskMesh are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-10-07

Initial release. TaskMesh — Distributed Job Processing and Worker Orchestration
Platform (Java 21 + Python 3.12 + React 18 + Kotlin 2.0, PostgreSQL-as-queue,
Apache-2.0).

### Added

**Platform core**
- PostgreSQL 16 as datastore and job queue: `SELECT … FOR UPDATE SKIP LOCKED`
  claiming, `LISTEN/NOTIFY` wakeups on channel `taskmesh_events`, per-job leases
  with heartbeat renewal, and a 5 s backend sweeper (lease expiry, stale-worker
  detection at 3× heartbeat, cancellation grace of 30 s).
- Job state machine with validated transitions
  (QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/RETRYING/TIMED_OUT), priorities
  LOW/NORMAL/HIGH/CRITICAL with aging (`rank + min(age_h, 1)`), retry backoff
  `min(300, 2^retries·5) s`, idempotent submission via unique `(owner, key)`,
  immediate and cooperative cancellation, and multi-layer timeout enforcement.
- Result handling: inline JSONB results ≤ 32 KiB; file results (sha256 recorded,
  path-traversal protected) served with `X-Job-Result-SHA256`.

**Backend (`backend/`, Java 21 / Spring Boot 3.5.16)**
- REST API under `/api/v1` (health, auth/login, me, users, projects, jobs with
  cancel/retry/result/logs/attempts, workers, audit logs, metrics, job-types
  catalog), Flyway migrations (V1 schema + V2 seeded dev users), JPA/Hibernate 6.6,
  WebSocket `/ws/v1/events` with hello/queue.event/job.updated/job.log/worker.updated
  frames, JSON logging with request ids, login rate limiting (10/min/username).

**Worker runtime (`worker/`, Python 3.12)**
- Package `taskmesh_worker` (wheel + sdist): registration, heartbeats + lease
  renewal, SKIP LOCKED claiming with capability filtering, thread execution with
  hard timeouts, progress reporting, SIGTERM drain / SIGINT abandon, and a FastAPI
  control endpoint (`:9100`, `/health`, `/status`) per worker.
- Seven job handlers: `csv_analysis`, `json_transform`, `image_resize` (Pillow
  LANCZOS, file result), `hash_sha256`, `text_statistics`, `archive_inspection`
  (zipfile), `cpu_benchmark` (primes/matrix). Closed handler set — no arbitrary
  code execution from payloads.

**Web console (`web/`, React 18 + Vite + TypeScript strict)**
- Dark operations console: queue lanes (CRITICAL/HIGH/NORMAL/LOW), live WS event
  ticker with reconnect/backoff and stale detection, worker tiles, job timeline,
  live log console, result inspection, metrics; pages Login, Operations, Projects,
  ProjectDetail, Jobs, JobCreate, JobDetail, Workers, WorkerDetail, Logs, Metrics,
  Settings. Monospace data typography (Inter + JetBrains Mono bundled locally).

**Android client (`android/`, Kotlin 2.0.21 + Jetpack Compose)**
- Eight screens (Login, Jobs, JobDetail, NewJob, Projects, Workers, Notifications,
  Settings) over the REST API via Retrofit 2.11 + kotlinx-serialization; local
  notification center with 30 s foreground polling (no FCM by design); debug
  cleartext for emulator development, HTTPS-only release; unsigned release APK.

**Security**
- JWT HS256 (12 h) + BCrypt cost 10; roles ADMIN/OPERATOR/USER enforced
  server-side; audit log (auth.login, project.*, job.create/cancel/retry,
  user.create); strict payload validation on API and worker; path-traversal
  protection on result serving; startup WARN on default JWT secret.

**CI/CD and operations**
- GitHub Actions: ci.yml (component test matrix incl. PostgreSQL services and
  Node 20/22), security.yml (CodeQL java/python/js + gitleaks), dependency-review,
  docker.yml (ghcr.io images), release.yml (tag-triggered: validation, artifacts,
  CycloneDX SBOMs, SHA256SUMS, GitHub Release); Dependabot and CODEOWNERS.
- Docker: multi-stage non-root images (backend, worker, web behind nginx with
  SPA/API/WS proxying) and a compose stack with a shared results volume and
  scalable workers.
- End-to-end smoke test `scripts/e2e_smoke.py`: 44 checks including worker-crash
  recovery (SIGKILL mid-job → attempt ABANDONED → re-claim → SUCCEEDED).

**Tests** (all green at release): backend 99, worker 146, web 62, android 75 JVM
tests, e2e 44 checks.

**Documentation**: English docs (README, ARCHITECTURE, SECURITY, CONTRIBUTING,
CHANGELOG, THIRD_PARTY_NOTICES, SPEC, SVG diagrams), Persian documentation set
under `docs/fa/`, and `.env.example`.

[0.1.0]: https://github.com/ParsaFathii/TaskMesh/releases/tag/v0.1.0
