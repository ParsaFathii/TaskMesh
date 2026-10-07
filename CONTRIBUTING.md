# Contributing to TaskMesh

TaskMesh is a four-component monorepo (Java backend, Python worker, React web,
Kotlin Android) around a PostgreSQL queue. Contributions that keep the system
verifiable are welcome: reproduced bug fixes, tests, and features that fit
[docs/SPEC.md](docs/SPEC.md).

## Development environment

Prerequisites: PostgreSQL 16, JDK 21, Python 3.12, Node 20+. Local dev ports:
PostgreSQL `5433`, API `8080`, worker control `9100+`, web dev `5173` —
[README §3](README.md#3-quick-start--local-development) has the full walkthrough.

| Component | Setup |
|---|---|
| backend | `cd backend && mvn clean verify` — integration tests need PostgreSQL on `:5433` (they create/migrate their own `taskmesh_test` database) |
| worker | `cd worker && python3 -m venv .venv && source .venv/bin/activate && pip install -e '.[dev]'` — integration tests create `taskmesh_worker_test` |
| web | `cd web && npm install` (Node 20+; CI also runs Node 22) |
| android | `cd android && ./gradlew testDebugUnitTest` — needs Android SDK platform 35 locally; CI compiles it on GitHub runners |

Never point your dev environment at the test databases; tests wipe data between
runs.

## Test gates — run before every PR

These are the exact commands CI runs (`.github/workflows/ci.yml`); a PR is expected
to have run them locally:

```bash
# Backend (Java 21 + live PostgreSQL on :5433)
cd backend && MAVEN_OPTS="-Xmx1200m" mvn clean verify

# Worker (Python 3.12 + live PostgreSQL on :5433)
cd worker && source .venv/bin/activate
ruff check .
python -m pytest -q

# Web
cd web
npm run lint && npm run typecheck && npm run test && npm run build

# Android (JVM tests; CI also builds the APK)
cd android && ./gradlew --no-daemon testDebugUnitTest

# End-to-end, with backend + worker running (optional but expected for
# queue/backend/worker changes)
python3 scripts/e2e_smoke.py --fast
```

Current green baselines: backend 99 tests, worker 146 tests, web 62 tests,
android 75 JVM tests, e2e smoke 44 checks. A PR must not lower these numbers
without removing the code they cover.

## Expectations for changes

- **Multi-pass quality is the norm**: build, test, then re-read your diff as a
  reviewer. During 0.1.0 integration, real bugs (storage-path mismatch, a word
  counting defect) were found on the second and third pass, not the first.
- **Conform to the SPEC**: `docs/SPEC.md` is the contract between components. If a
  change breaks the contract, either update the SPEC in the same PR with rationale
  (see the documented deviations in `worker/README.md` for the accepted pattern), or
  change the design.
- **Document deviations**: the worker README's "Deviations from SPEC" section is the
  model — state what differs and why.
- **Keep the honest tone**: no marketing language, no claims about features that do
  not exist. If you add a limitation, list it in README §16.
- **Do not commit secrets**, keystores, or local env files. The default JWT secret
  triggers a startup WARN — leave that warning behavior intact.
- The web console is intentionally dark-only; the Android notification design is
  intentionally FCM-free. Changing these is a design decision to raise in an issue
  first, not a drive-by PR.

## Commit style

- Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`, `ci:`, `refactor:`).
  Scope suffixes welcome: `fix(worker): …`.
- One logical change per commit; commits must contain real work only — no
  placeholder commits, no "update" or "misc" messages.
- Keep `android/.gitignore` re-inclusion guards intact (they keep the Kotlin
  `data/` package tracked despite the root `data/` runtime-storage ignore rule).

## Pull requests

- Describe what changed and why; reference the SPEC sections involved.
- State which gates from the list above you ran, with results.
- For queue semantics changes, include the e2e run output (44 checks pass).
- CI must be green: component builds, tests, CodeQL, gitleaks, dependency review.
- Reviews by the code owner (`@ParsaFathii`, see `CODEOWNERS`) are required.

## Code of conduct

Be professional and direct. Technical disagreement is fine; personal remarks, noise
and hostile language are not. Assume competence on the other side, cite code or
SPEC sections rather than opinions, and keep issues on-topic.

## Security issues

Do not open a public issue or PR for vulnerabilities — follow
[SECURITY.md](SECURITY.md#reporting-security-issues) instead.
