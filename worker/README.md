# TaskMesh Worker (Python)

Distributed job worker for [TaskMesh](https://github.com/ParsaFathii/TaskMesh) —
version 0.1.0, Apache-2.0, author Parsa Fathi.

The worker registers itself in PostgreSQL, heartbeats, claims jobs with
`SELECT … FOR UPDATE SKIP LOCKED`, executes typed handlers in isolated
threads with hard timeouts, reports progress, and finalizes jobs as
SUCCEEDED / FAILED / RETRYING / TIMED_OUT / CANCELLED. It conforms to
`docs/SPEC.md` (schema §4, state machine §5, priority §6, claiming §7,
job type catalog §9, worker runtime §10, config §12).

## Install

```bash
cd taskmesh/worker
python3 -m venv .venv
source .venv/bin/activate
pip install -e .            # runtime + console script
pip install -e '.[dev]'     # + pytest, ruff, httpx, build
```

Install from release artifacts instead:

```bash
pip install dist/taskmesh_worker-0.1.0-py3-none-any.whl
# or: pip install dist/taskmesh_worker-0.1.0.tar.gz
```

## Run

```bash
export TASKMESH_DATABASE_URL=postgresql://taskmesh@localhost:5433/taskmesh
export TASKMESH_CONTROL_PORT=9100
taskmesh-worker                # or: python -m taskmesh_worker
```

Logs are structured JSON lines on stdout. SIGTERM drains (finish current
job, exit 0); SIGINT aborts the current job to RETRYING with a
`job_attempts` outcome of `ABANDONED` and exits 130 (a second SIGINT
hard-exits). If the database is unreachable at start, the worker retries
with backoff (WARN logs) instead of crashing.

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `TASKMESH_DATABASE_URL` | `postgresql://taskmesh@localhost:5433/taskmesh` | job queue database |
| `TASKMESH_CAPABILITIES` | all 7 job types | comma-separated job types this worker claims |
| `TASKMESH_HEARTBEAT_INTERVAL_S` | `10` | heartbeat + lease renewal cadence |
| `TASKMESH_LEASE_MARGIN_S` | `15` | extra seconds added when renewing a lease |
| `TASKMESH_CONTROL_PORT` | `9100` | FastAPI control port (offset per instance) |
| `TASKMESH_STORAGE_DIR` | `./data` | result storage root (`results/` below it) |
| `TASKMESH_WORKER_NAME` | `<hostname>-<pid>` | worker name shown in the `workers` table |

## Control API

| Endpoint | Response |
|---|---|
| `GET /health` | `{status, workerId, currentJobId, uptimeS}` (in-memory, always available) |
| `GET /status` | full `workers` row + sanitized config (503 if DB is down) |

```bash
curl -s localhost:9100/health
curl -s localhost:9100/status | jq .
```

## Handler catalog

| Type | Payload | Result |
|---|---|---|
| `csv_analysis` | `{csv ≤2MB, delimiter?: , ; \t, hasHeader?=true}` | `{rows, columns:[{name,type,nonNull,missing,unique,min?,max?,mean?}], delimiterUsed, parseErrors}` |
| `json_transform` | `{input, operations:[{op:"pick"\|"remove"\|"rename"\|"flatten", …}]}` | `{output, applied, operations}` |
| `image_resize` | `{imageBase64, width 1..10000, height 1..10000, maintainAspect?=true, format?: "png"\|"jpeg"}` | `{width,height,format,bytes,sha256}` + FILE result (image bytes) |
| `hash_sha256` | `{contentBase64? \| text?}` (exactly one) | `{sha256, bytes, source}` |
| `text_statistics` | `{text ≤2MB, caseSensitive?=false}` | `{characters, charactersNoSpaces, words, uniqueWords, lines, paragraphs, avgWordLength, readingTimeSeconds, topWords ≤20}` |
| `archive_inspection` | `{archiveBase64 ≤20MB, maxEntries?=500}` | `{format, totalEntries, totalUncompressedBytes, compressionRatio, entries, truncated}` |
| `cpu_benchmark` | `{workload:"primes"\|"matrix", durationSeconds? 1..30 default 5}` | `{workload, operations, durationMs, opsPerSecond, threads}` |

Validation is strict (unknown fields rejected) and invalid payloads are
**non-retryable** (job → FAILED). Transient errors and unexpected handler
exceptions are retried with `backoff = min(300, 2^retry_count · 5)` s until
`max_retries` is exhausted. No job type executes arbitrary code.

Result envelope: handler results ≤ 32 KiB are stored inline in
`job_results.inline`; larger results become `results/<job_id>.json` files.
`image_resize` always stores its bytes as `results/<job_id>.bin`
(`kind=file`, sha256 + size recorded, metadata dict kept in `inline`).
All file paths are UUID-derived, canonically resolved, and confined to the
results root (traversal attempts are rejected).

## Tests

```bash
source .venv/bin/activate
python -m pytest -q            # unit + integration (needs local PostgreSQL on :5433)
```

Integration tests create and migrate their own database
(`taskmesh_worker_test`) from `tests/fixtures/schema.sql` (SPEC §4 DDL);
the dev database `taskmesh` is never touched. The control API is tested via
httpx's ASGI transport; NOTIFY is verified with a real LISTEN connection.

An end-to-end smoke test (real worker process, real job, LISTEN/NOTIFY,
control API, SIGTERM drain) lives in `scripts/smoke_test.py`:

```bash
python scripts/smoke_test.py   # seeds a text_statistics job and watches it run
```

## Development

```bash
ruff check . && ruff format --check .
python -m build    # wheel + sdist into dist/
```

## Deviations from SPEC (documented)

- The claim statement (§7) additionally filters `type = ANY(…)`, because a
  worker must not claim job types outside its advertised capabilities. With
  the default capability set (all 7 types) the statement matches the SPEC
  SQL exactly.
- `image_resize` file results keep the metadata dict in `job_results.inline`
  alongside `kind=file` (one row per job; the metadata is not lost).
- The execution timeout cannot kill the handler thread (CPython threads are
  not killable); the job is finalized immediately and the orphaned daemon
  thread dies with the process. Cancellation is cooperative between phases
  plus an end-of-run check, exactly as documented in §10.
