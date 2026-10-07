# Database

TaskMesh uses Flyway migrations that live with the backend:

- Schema (V1): `backend/src/main/resources/db/migration/V1__init.sql`
- Seed users (V2): `backend/src/main/resources/db/migration/V2__seed_users.sql`
- A mirror of the schema DDL for reference: `docs/SPEC.md` §4

For Docker Compose, PostgreSQL is provisioned by `deploy/compose/docker-compose.yml`
and migrated automatically by the backend on startup.
