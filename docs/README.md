# TaskMesh Documentation Index

TaskMesh — Distributed Job Processing and Worker Orchestration Platform (v0.1.0,
Apache-2.0, Copyright 2026 Parsa Fathi). This directory holds the English
documentation set; the Persian set lives in [`fa/`](fa/README_FA.md)
(مستندات فارسی).

| Document | Contents |
|---|---|
| [SPEC.md](SPEC.md) | The binding contract between all components: database DDL, state machine, claim SQL, REST + WebSocket API, job type catalog, worker protocol, roles, configuration, versioning |
| [../README.md](../README.md) | Project overview, problem statement, quick start, job/worker types, configuration, testing, CI/CD, releases, troubleshooting, known limitations |
| [../ARCHITECTURE.md](../ARCHITECTURE.md) | PostgreSQL-as-queue rationale, state machine, claim SQL explained, backoff math, priority aging, timeout layers, cancellation, idempotency, WS event flow, storage, scaling, data model |
| [../SECURITY.md](../SECURITY.md) | Authentication, authorization matrix, rate limiting, input validation, path traversal protection, secret handling, supply chain, reporting |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | Dev environment per component, pre-PR test gates, commit style, review expectations |
| [../CHANGELOG.md](../CHANGELOG.md) | Release history (Keep a Changelog format) |
| [../THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) | Third-party dependencies, licenses and notice requirements per component |

Component-specific notes: [backend](../backend/README.md) ·
[worker](../worker/README.md) · [web](../web/README.md) ·
[android](../android/README.md).

Diagrams (SVG, [`assets/`](assets/)):
[architecture](assets/architecture.svg) ·
[job lifecycle](assets/job-lifecycle.svg) ·
[worker lifecycle](assets/worker-lifecycle.svg) ·
[retry flow](assets/retry-flow.svg) ·
[release pipeline](assets/release-pipeline.svg).

Persian documentation: [`fa/`](fa/README_FA.md) — نصب، راهنمای کاربری، معماری،
API، امنیت و انتشار به زبان فارسی.
