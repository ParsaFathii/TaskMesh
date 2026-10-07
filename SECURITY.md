# TaskMesh Security

How TaskMesh 0.1.0 handles authentication, authorization, input validation, file
safety and supply-chain controls. This document describes implemented behavior, not
intentions. The component contract is [docs/SPEC.md](docs/SPEC.md); design context is
in [ARCHITECTURE.md](ARCHITECTURE.md).

## Authentication

- **Password storage**: BCrypt (`spring-security-crypto` `BCryptPasswordEncoder`,
  cost 10). Plaintext passwords are never stored or logged. The seeded dev accounts
  (`V2__seed_users.sql`) ship as pre-computed hashes, with the dev passwords
  documented only for local development.
- **Tokens**: JWT signed with HS256 (jjwt 0.12.6), 12 h expiry. Login returns
  `{token, tokenType:"Bearer", expiresIn:43200, user}`. The signature key comes from
  `TASKMESH_JWT_SECRET`.
- **WebSocket**: `/ws/v1/events?token=<JWT>` authenticates once at connect time with
  the same JWT; unauthenticated sockets are refused.
- Tampered, expired or unsigned tokens are rejected with `401 UNAUTHORIZED` — covered
  by unit tests (issue/parse/expiry/tamper) and integration tests.
- Known gap (tracked in README §16): there is no password-change UI in 0.1.0; an
  ADMIN can create users via the users API, but password rotation requires operator
  action on the database.

## Authorization

Roles are enforced **server-side on every endpoint**; the web console and Android app
only hide what the API already denies (SPEC §11). Matrix:

| Capability | ADMIN | OPERATOR | USER |
|---|---|---|---|
| List/create users (`/users`) | ✔ | — | — |
| View workers, audit logs, metrics | ✔ | ✔ | — |
| View all projects and jobs | ✔ | ✔ | own only |
| Cancel / retry any job | ✔ | ✔ | own only |
| Submit jobs, manage own projects | ✔ | ✔ | ✔ |

Job/project visibility is filtered in the query layer (Specifications), not by
stripping results after the fact.

## Rate limiting

Login is limited to **10 attempts per minute per username**; excess attempts get
`429 RATE_LIMITED` with the standard error envelope. Successful and failed attempts
both count. Audit entries record login successes and failures (`auth.login`).

## Input validation

Two independent layers reject malformed input before any handler runs:

- **API side** (`catalog/`): the job type catalog defines payload shapes per type;
  `POST /api/v1/jobs` validates the payload against the catalog (unknown/missing
  fields, bounds such as `width ≤ 10000`, `text ≤ 2 MB`) and returns
  `400 VALIDATION` with a specific message. `hash_sha256` enforces "exactly one of
  `contentBase64`/`text`".
- **Worker side** (Pydantic strict models, `extra="forbid"`): every handler
  re-validates the payload it claimed; a payload that fails validation is a
  **non-retryable** failure — the job fails once with a clear error rather than
  burning retries.
- Request-size and pagination bounds are enforced (`size` max 100; nginx
  `client_max_body_size 25m` in the deployment layout).
- Errors use a single envelope (`{"error":{"code","message"}}`) and never echo
  secrets or stack traces.

## Path traversal protection and file result safety

- Worker-written result files live under `TASKMESH_STORAGE_DIR/results/` with
  UUID-derived names (`results/<job_id>.json|.bin`) — the job id is a UUID, so no
  user-controlled path segments are used.
- Before serving, the backend resolves the stored path canonically and requires the
  result to remain under the results root; any escape attempt is rejected (covered by
  integration tests).
- Every file result carries a recorded SHA-256; `GET /api/v1/jobs/{id}/result`
  returns the checksum in the `X-Job-Result-SHA256` header so clients can verify the
  bytes they received.
- Result access follows job visibility: owner or OPERATOR+.

## No arbitrary code execution — by design

The job type set is **closed**: exactly 7 handlers (`csv_analysis`, `json_transform`,
`image_resize`, `hash_sha256`, `text_statistics`, `archive_inspection`,
`cpu_benchmark`). A job's `type` must match one of them; payloads are data for those
handlers, never scripts, shell commands, URLs to fetch, or expressions to evaluate.
There is no plugin-loading path and no dynamic import of user input. Workers only
claim types in their advertised capabilities.

## Secret handling

- All secrets are environment variables (`TASKMESH_JWT_SECRET`,
  `TASKMESH_DB_PASSWORD`); nothing is committed.
- If the JWT secret is left at its development default
  (`taskmesh-dev-secret-change-me`), the backend logs a **WARN at startup** — grep
  your logs for it before exposing any deployment.
- Structured JSON logging (logstash encoder, MDC request ids) deliberately logs
  metadata, not payloads or credentials; passwords and tokens never appear in
  `audit_logs` metadata.
- gitleaks scans the full git history for leaked secrets on every push/PR and weekly
  (see below).

## Supply chain

- **Lockfiles**: `web/package-lock.json` (installed via `npm ci` in CI), worker
  dependencies pinned in `pyproject.toml` with a frozen requirements SBOM input at
  release time, Maven resolved against the Spring Boot 3.5.16 parent BOM.
- **Dependabot** covers maven, pip, npm, gradle, GitHub Actions and Docker.
- **CodeQL** scans `java-kotlin`, `python`, `javascript-typescript` on PRs, pushes
  to `main`, and weekly (`.github/workflows/security.yml`).
- **gitleaks** scans full history in the same workflow.
- **Dependency review** runs on PRs that change manifests
  (`.github/workflows/dependency-review.yml`).
- **Releases** attach CycloneDX SBOMs (`sbom-backend.json`, `sbom-worker.json`,
  `sbom-web.json`) and `SHA256SUMS` (verified in-pipeline with `sha256sum -c`).
- Container images are built from the component Dockerfiles in CI (multi-stage,
  non-root `uid 10001`, healthchecks), published to ghcr.io.

## Reporting security issues

Please report suspected vulnerabilities privately to the repository owner
(https://github.com/ParsaFathii) via GitHub security advisories or the contact listed
on the profile — do not open a public issue for security reports. Include
reproduction steps, affected component and version (all components report `0.1.0` at
`/api/v1/health` / worker `/health`). Please avoid public disclosure until a fix is
released.
