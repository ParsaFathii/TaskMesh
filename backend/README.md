# TaskMesh Backend

Java 21 / Spring Boot 3.5 REST API and orchestration backend for TaskMesh.
PostgreSQL is both the datastore and the job queue (claims via `FOR UPDATE SKIP LOCKED`,
wakeups via `LISTEN/NOTIFY` on channel `taskmesh_events`). Version 0.1.0.

## Build & test

```bash
# build + unit/integration tests (integration tests need local PostgreSQL on :5433)
MAVEN_OPTS="-Xmx1200m" mvn clean verify

# build only
mvn -DskipTests package
```

Requires Java 21 and Maven 3.9.x. Integration tests run against a dedicated
`taskmesh_test` database on `localhost:5433` (created and Flyway-migrated
automatically on first run; the dev `taskmesh` database is never touched).
If your local Maven is not on PATH: `/home/z/tools/apache-maven-3.9.9/bin/mvn`
and set `JAVA_HOME` to a JDK 21 (e.g. `/home/z/tools/jdk-21`).

## Run

```bash
java -jar target/taskmesh-backend.jar
# or
mvn spring-boot:run
```

The app starts on port 8080, migrates the schema with Flyway, starts the 5 s
maintenance sweeper and the LISTEN thread, and serves `/api/v1/health`.

WebSocket events: `ws://localhost:8080/ws/v1/events?token=<JWT>` (raw JSON text
frames, server push only).

### Seeded dev accounts (BCrypt cost 10)

| username | password         | role     |
|----------|------------------|----------|
| admin    | `TaskMesh!Admin` | ADMIN    |
| operator | `TaskMesh!Operator` | OPERATOR |
| user     | `TaskMesh!User`  | USER     |

## Configuration (environment variables)

| Var | Default | Notes |
|-----|---------|-------|
| `TASKMESH_DB_URL` | `jdbc:postgresql://localhost:5433/taskmesh` | JDBC URL |
| `TASKMESH_DB_USER` | `taskmesh` | |
| `TASKMESH_DB_PASSWORD` | *(empty)* | local dev uses trust auth |
| `TASKMESH_JWT_SECRET` | `taskmesh-dev-secret-change-me` | WARN logged when default is in use |
| `TASKMESH_STORAGE_DIR` | `./data` | root for file job results (shared with workers) |
| `TASKMESH_SERVER_PORT` | `8080` | |
| `TASKMESH_CORS_ORIGINS` | `http://localhost:5173` | comma-separated list |

## Layout

```
com.taskmesh
├── config        security, CORS, WebSocket, scheduling, Jackson (ISO-8601 UTC)
├── domain        JPA entities mirroring SPEC §4 (JSONB via @JdbcTypeCode)
├── repository    Spring Data JPA + Specifications (visibility filters)
├── service       auth, projects, jobs (idempotency + state machine), workers,
│                 metrics, audit, MaintenanceService sweeper (5 s)
├── controller    /api/v1/* REST, {items,page,size,total} pagination
├── security      JWT (jjwt 0.12, HS256, 12 h), BCrypt, login rate limit (10/min/user)
├── messaging     LISTEN/NOTIFY listener + event broadcaster
├── websocket     /ws/v1/events endpoint (token query param)
├── catalog       7 job types with payload schemas (GET /api/v1/job-types)
├── storage       result file serving with path-traversal protection
└── observability JSON logs with MDC request id, access log filter
```

Migrations: `src/main/resources/db/migration` — `V1__init.sql` is the canonical
DDL from SPEC §4; `V2__seed_users.sql` inserts the three dev accounts.

## Local dev notes

- PostgreSQL 16 runs on **:5433** (not the default 5432), trust auth, unix socket `/tmp`.
  Create a fresh dev DB with: `createdb -h localhost -p 5433 -U taskmesh taskmesh`.
- Tests use `taskmesh_test` on the same instance; they wipe all non-seed rows
  between test methods, so never point `TASKMESH_DB_URL` at it for development.
- Error envelope everywhere: `{"error":{"code","message"}}` — 400 VALIDATION,
  401 UNAUTHORIZED, 403 FORBIDDEN, 404 NOT_FOUND, 409 CONFLICT, 429 RATE_LIMITED.
- Job state transitions are validated by `JobStateMachine` (SPEC §5); the sweeper
  honors lease expiry, the 30 s cancellation grace and the hard-timeout margin
  (timeout + 60 s) from SPEC §7.
