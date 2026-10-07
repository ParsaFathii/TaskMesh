# Third-Party Notices

TaskMesh (Copyright 2026 Parsa Fathi, Apache-2.0) redistributes and depends on the
third-party components below. This file lists the **direct, relevant dependencies**
per component. Complete machine-readable inventories are attached to every release
as CycloneDX SBOMs (`sbom-backend.json`, `sbom-worker.json`, `sbom-web.json`).
Transitive runtime dependencies are resolved by each ecosystem's lockfile/metadata
and are covered by those SBOMs.

License identifiers are SPDX IDs as declared by upstream package metadata at the
pinned versions used in 0.1.0. Entries marked *(as declared by upstream)* were taken
from upstream metadata — re-verify them when upgrading. Nothing here overrides the
upstream license texts; where a license requires preservation of notices
(Apache-2.0 §4, MIT, BSD, OFL), the upstream NOTICE/license text must be retained
in redistributions.

## Backend (Java 21, Spring Boot 3.5.16) — `backend/pom.xml`

| Component | Version | Purpose | License | Upstream |
|---|---|---|---|---|
| Spring Boot (starters: web, websocket, data-jpa, security, validation, test) | 3.5.16 | application framework, embedded Tomcat | Apache-2.0 | https://spring.io/projects/spring-boot |
| Spring Framework / Spring Security | via Boot 3.5.16 | MVC, security (BCrypt encoder) | Apache-2.0 | https://github.com/spring-projects |
| Hibernate ORM | 6.6.x | JPA persistence (via `spring-boot-starter-data-jpa`) | LGPL-2.1-or-later (with the classpath exception declared by upstream — see upstream LICENSE; as declared by upstream) | https://hibernate.org/orm/ |
| Flyway (flyway-core + flyway-database-postgresql) | 11.x (Boot-managed) | database migrations | Apache-2.0 (community edition) | https://flywaydb.org/ |
| PostgreSQL JDBC (pgjdbc) | via Boot BOM | JDBC driver (also used directly for LISTEN/NOTIFY) | BSD-2-Clause | https://jdbc.postgresql.org/ |
| jjwt (api, impl, jackson) | 0.12.6 | JWT HS256 issue/parse | Apache-2.0 | https://github.com/jwtk/jjwt |
| Jackson (via Boot + jjwt) | via Boot | JSON serialization | Apache-2.0 | https://github.com/FasterXML/jackson |
| Apache Tomcat (embedded) | 10.1.x | HTTP/WebSocket container | Apache-2.0 | https://tomcat.apache.org/ |
| Logback + SLF4J (via Boot) | via Boot | logging backend | Logback: EPL-1.0 OR LGPL-2.1 (dual, as declared by upstream); SLF4J: MIT | https://logback.qos.ch/ · https://www.slf4j.org/ |
| logstash-logback-encoder | 8.0 | structured JSON logging | Apache-2.0 (as declared by upstream) | https://github.com/logfellow/logstash-logback-encoder |
| Hibernate Validator (via starter-validation) | via Boot | bean validation | Apache-2.0 | https://hibernate.org/validator/ |

Backend packaging note: the Spring Boot fat JAR embeds these libraries; per
Apache-2.0 §4, retain upstream NOTICE files when redistributing the JAR.

## Worker (Python 3.12) — `worker/pyproject.toml`

Runtime (pinned ranges, tested with psycopg[binary] 3.3.6, pillow 12.3.0,
fastapi 0.142.2, uvicorn 0.54.0, pydantic 2.13.5):

| Component | Purpose | License | Upstream |
|---|---|---|---|
| psycopg 3 (psycopg[binary]) | PostgreSQL driver, LISTEN/NOTIFY, SKIP LOCKED claims | LGPL-3.0-or-later, with the linking/distribution exception declared by upstream (see upstream LICENSE; as declared by upstream) | https://www.psycopg.org/ |
| Pillow | image_resize handler (LANCZOS resampling) | HPND (Historical Permission Notice and Disclaimer — permissive; attribution notice must be preserved; as declared by upstream) | https://python-pillow.org/ |
| FastAPI (+ Starlette) | per-worker control API (:9100) | FastAPI: MIT · Starlette: BSD-3-Clause | https://fastapi.tiangolo.com/ · https://www.starlette.io/ |
| uvicorn | ASGI server for the control API | BSD-3-Clause | https://www.uvicorn.org/ |
| pydantic | strict payload/result models | MIT | https://docs.pydantic.dev/ |
| hatchling (build backend, build-time only) | wheel/sdist packaging | MIT | https://github.com/pypa/hatch |

Standard-library-only handlers (`csv`, `json`, `hashlib`, `zipfile`,
`statistics`, `math`) use CPython itself (PSF License).

## Web console (React 18 + Vite) — `web/package.json`

| Component | Version | Purpose | License | Upstream |
|---|---|---|---|---|
| React / ReactDOM | 18.3.x | UI runtime | MIT | https://react.dev/ |
| Vite (+ @vitejs/plugin-react) | 6.x | build/dev server | MIT | https://vite.dev/ |
| Tailwind CSS 4 (+ @tailwindcss/vite) | 4.1.x | styling (custom token theme) | MIT | https://tailwindcss.com/ |
| TanStack Query | 5.x | server-state cache/invalidation | MIT | https://tanstack.com/query |
| zustand | 5.x | client state stores | MIT | https://github.com/pmndrs/zustand |
| react-router-dom | 6.x | routing | MIT | https://reactrouter.com/ |
| @fontsource-variable/inter | 5.x | Inter variable font (bundled, no CDN) | package MIT; **font: SIL OFL 1.1**, Reserved Font Name "Inter" | https://rsms.me/inter/ |
| @fontsource-variable/jetbrains-mono | 5.x | JetBrains Mono variable font (bundled) | package MIT; **font: SIL OFL 1.1**, Reserved Font Name "JetBrains Mono" | https://www.jetbrains.com/lp/mono/ |

OFL note: the Open Font License requires that the license (and this reserved-name
notice) accompany any redistribution of the font files themselves; the font files
bundled in `web/dist` keep the upstream naming and are redistributed under OFL 1.1,
not Apache-2.0.

Dev-only tooling (not shipped): TypeScript (Apache-2.0), ESLint + plugins (MIT),
vitest (MIT), jsdom (MIT), @testing-library (MIT), typescript-eslint (MIT).

## Android client (Kotlin 2.0.21 + Compose) — `android/gradle/libs.versions.toml`

| Component | Version | Purpose | License | Upstream |
|---|---|---|---|---|
| Kotlin (stdlib + compose + serialization plugins) | 2.0.21 | language, compiler plugins | Apache-2.0 | https://kotlinlang.org/ |
| Jetpack Compose (BOM 2024.09.03: material3 1.3.0, ui/foundation 1.7.3, material-icons-core) | per BOM | UI toolkit | Apache-2.0 | https://developer.android.com/jetpack/compose |
| AndroidX (core 1.13.1, lifecycle 2.8.6, activity-compose 1.9.2, navigation-compose 2.8.1) | pinned | Android integration | Apache-2.0 | https://developer.android.com/jetpack |
| Material Design icons (core set) | 1.7.3 | UI icons | Apache-2.0 | https://github.com/google/material-design-icons |
| Retrofit + converter-kotlinx-serialization | 2.11.0 | REST client | Apache-2.0 | https://square.github.io/retrofit/ |
| OkHttp (+ MockWebServer, test-only) | 4.12.0 | HTTP stack | Apache-2.0 | https://square.github.io/okhttp/ |
| kotlinx-serialization-json | 1.7.3 | DTO (de)serialization | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| kotlinx-coroutines | 1.8.1 | async/structured concurrency | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| Gradle (wrapper 8.10.2) + Android Gradle Plugin 8.7.3 | pinned | build | Apache-2.0 | https://gradle.org/ |

The Android SDK itself is used under the Android SDK Terms of Service
(https://developer.android.com/studio/terms); the app's own launcher icon is an
original TaskMesh vector drawable.

## Docker base images — component Dockerfiles

| Image | Used by | License of the key software | Upstream |
|---|---|---|---|
| `maven:3.9-eclipse-temurin-21` / `eclipse-temurin:21-jre` | backend | Eclipse Temurin JDK: **GPL-2.0-with-classpath-exception** (builds by Adoptium; Ubuntu base subject to its own licenses) | https://adoptium.net/ |
| `python:3.12-slim` | worker | Python: **PSF-2.0** (Python Software Foundation License); Debian base subject to its own licenses | https://www.python.org/ |
| `node:22-alpine` | web (build stage) | Node.js: **MIT**; Alpine Linux packages under their respective licenses (musl: MIT) | https://nodejs.org/ · https://alpinelinux.org/ |
| `nginx:1.27-alpine` | web (runtime) | nginx: **BSD-2-Clause** (2-clause BSD-like license); Alpine base | https://nginx.org/ |
| `postgres:16-alpine` | compose / CI | PostgreSQL: **PostgreSQL License** (permissive, BSD/MIT-style); Alpine base | https://www.postgresql.org/ |

## Notice preservation

- **Apache-2.0** components: redistribution must retain upstream copyright/license
  and NOTICE text (Apache-2.0 §4). TaskMesh's own NOTICE (`/NOTICE`) covers original
  work; it does not and must not replace upstream notices.
- **MIT / BSD** components: retain the copyright and permission notice.
- **OFL 1.1 fonts**: the font files may be bundled/redistributed under OFL 1.1 with
  the license text; Reserved Font Names ("Inter", "JetBrains Mono") may not be used
  for modified versions.
- **LGPL (Hibernate, psycopg, Logback-dual)**: used unmodified as linked libraries;
  the classpath/linking exceptions declared by upstream permit inclusion in this
  Apache-2.0 distribution. Source availability obligations are satisfied by the
  upstream projects; modifications to these libraries (none in 0.1.0) would require
  corresponding source disclosure.
- **HPND (Pillow)**: permissive; retain the original copyright/attribution lines.

## Verification

Licenses were reviewed against upstream metadata at 0.1.0 pin time. CycloneDX SBOMs
in each GitHub Release are the authoritative per-release inventory; dependency
changes are surfaced by Dependabot and the dependency-review workflow.
