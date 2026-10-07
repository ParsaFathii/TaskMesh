# TaskMesh — Android Client

Native Kotlin + Jetpack Compose client for the TaskMesh distributed job
processing and worker orchestration platform. It talks to the real Java Spring
Boot API (see `../docs/SPEC.md`, §8) over Retrofit — no web-view wrapping.

- Version: **0.1.0** (SPEC §13, consistent with every TaskMesh component)
- Owner / Author: **Parsa Fathi** — Copyright © 2026 Parsa Fathi, Apache-2.0
- Package: `com.taskmesh.app`

## Toolchain

| Tool | Version |
|------|---------|
| Gradle | 8.10.2 (wrapper included) |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 (Compose compiler via `org.jetbrains.kotlin.plugin.compose`) |
| Compose BOM | 2024.09.03 (Material3 1.3.0, UI/Foundation 1.7.3) |
| JDK | 17+ (CI uses the GitHub runner's preinstalled JDK) |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |

Requirements: Android Studio Ladybug+ (or a plain SDK 35 + JDK 17 setup).

## Build

```bash
cd android
./gradlew assembleDebug          # debug APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JVM unit tests (Retrofit stack against MockWebServer)
./gradlew assembleRelease        # UNSIGNED release APK (see below)
```

No Android SDK is needed to *read* the sources, but compiling requires the
Android SDK with **platforms;android-35** (CI — GitHub Actions ubuntu-latest
runners — ships an SDK; if the platform is missing, AGP auto-installs it
because the runner's SDK licenses are pre-accepted). JDK 17+ is required
(AGP 8.7.3 requires Gradle 8.9+, satisfied by the wrapper's 8.10.2).
The Gradle wrapper downloads Gradle 8.10.2 on first use.

### Repository note (source tracking)

The repository-root `.gitignore` ignores `data/` directories (runtime storage
for the worker/backend). This module's Kotlin package
`app/src/main/java/com/taskmesh/app/data/` would be swallowed by that pattern,
so `android/.gitignore` re-includes it (and the unit test sources) with
explicit negation rules. **Do not remove those guards** — otherwise the data
layer silently disappears from git and CI fails with missing classes.

### Release build is unsigned (by design)

`assembleRelease` produces an **unsigned** APK: no signing config and no
keystores are committed (per release-security policy). `isMinifyEnabled` is
`false` for the 0.1.0 release so the artifact maps 1:1 to sources;
`proguard-rules.pro` ships kotlinx-serialization keep rules for when R8 is
turned on. To distribute, sign the APK with your own keystore:

```bash
apksigner sign --ks your.keystore app/build/outputs/apk/release/app-release-unsigned.apk
```

### Cleartext traffic

- **debug** allows plain HTTP (`src/debug/AndroidManifest.xml` sets
  `android:usesCleartextTraffic="true"`) so you can develop against
  `http://10.0.2.2:8080` from the emulator.
- **release** disables cleartext (`src/release/AndroidManifest.xml`) —
  production APIs must be served over **HTTPS**.

## Running against a local backend

1. Start the TaskMesh API (default port 8080).
2. Install the debug build on an emulator.
3. Sign in with one of the seeded dev accounts (`admin` / `operator` / `user`).
   The default server URL is `http://10.0.2.2:8080` — the Android emulator's
   alias for your host machine's loopback. Change it on the login screen or in
   Settings (a physical device needs your machine's LAN IP).

## Screens

| Screen | Contents |
|--------|----------|
| Login | username / password / server URL |
| Jobs | status filter chips, project filter chips, pull-to-refresh, FAB → new job |
| Job detail | status/priority/type, timestamp timeline, worker info, payload JSON, attempts, last 50 logs, result (inline pretty JSON + copy / file ref + SHA-256), cancel & retry |
| New job | project + type chips (type catalog from `/job-types`, SPEC §9 fallback), priority, payload JSON (validated), timeout/retries/idempotency key |
| Projects | owned projects + create-project dialog (name/description); tapping one filters the Jobs list |
| Workers | WorkerCard grid with heartbeat age, BUSY pulse, stale flag (OPERATOR/ADMIN only — tab hidden for USER per SPEC §11) |
| Notifications (Alerts) | in-app event feed with unread badge |
| Settings | base URL config, current user, logout, version |

## Notification design (no push service, by design)

TaskMesh 0.1.0 has **no FCM/push dependency** — an external push platform is
out of scope and would violate the "no external paid notification platform"
requirement. Instead:

- `NotificationCenter` (pure Kotlin, unit-tested) keeps job/worker status
  snapshots and emits app-level events on **observed transitions** (job
  completed / failed / timed out / cancelled, worker offline), deduplicated by
  `(job, terminal status)` / `(worker, OFFLINE)`.
- `AppViewModel` polls `GET /api/v1/jobs` (size 100) every **30 seconds while
  the app is open** (plus `GET /api/v1/workers` for ADMIN/OPERATOR) and feeds
  the snapshots to the center.
- New events are posted as **system notifications** on the
  `taskmesh_jobs` channel (runtime permission requested on Android 13+).
- Limitations (documented, not hidden): events are only detected while the
  process is alive; a full background push would require adding a push
  service later. The in-app feed persists for the process lifetime only.

## Architecture

```
app/src/main/java/com/taskmesh/app/
├── data/            DTOs (kotlinx.serialization, SPEC §8 shapes), Retrofit
│                    TaskMeshApi, AuthInterceptor (Bearer + 401 logout),
│                    TokenStore (SharedPreferences), SessionRepository
│                    (dynamic base URL rebuild), JsonConfig, ApiErrors
├── model/           StatusMapping (label/tone/terminal), TimeFormat (pure)
├── notifications/   NotificationCenter (transition detection + dedup),
│                    NotificationHelper (system notifications)
├── ui/theme/        dark graphite identity (#101418 bg, amber #F5A623 primary,
│                    emerald #2EA043 success, rose #F85149 error, monospace
│                    typography for ids/timestamps/status)
├── ui/components/   StatusDot (pulsing), StatusChip, JobRow, WorkerCard,
│                    SectionHeader, EmptyState, ErrorPanel, LabeledValue, ShortId
├── ui/screens/      Login, Jobs, JobDetail, NewJob, Projects, Workers,
│                    Notifications, Settings (ViewModel + Compose per screen)
└── ui/nav/          AppRoot: NavHost + bottom NavigationBar (Jobs, Projects,
                     Workers [role-gated], Alerts w/ badge, Settings)
```

State flows one way: `Retrofit → SessionRepository (Result) → ViewModel
(StateFlow uiState) → @Composable screens`. No business logic in composables;
manual dependency container (`ServiceLocator`) instead of a DI framework —
the dependency set is intentionally minimal:

Retrofit 2.11.0 + `converter-kotlinx-serialization`, OkHttp 4.12.0,
kotlinx-serialization-json 1.7.3, kotlinx-coroutines 1.8.1, Compose
(core icon set explicitly declared), AndroidX
core/lifecycle/activity-compose/navigation-compose. Nothing else.

## Tests

`./gradlew testDebugUnitTest` runs the JVM suite:

- `DtoParsingTest` — login response, jobs (all statuses/priorities), paginated
  list envelope, error envelope, workers, job-type catalog, attempts, logs,
  results; fixtures match SPEC §8/§9 JSON shapes.
- `AuthInterceptorTest` — bearer header injection, no header when anonymous,
  401 → logout callback (and 200 does not trigger it).
- `ApiFlowTest` — MockWebServer flows through the *real* stack: login stores
  token, jobs fetch carries bearer + query filters, cancel/retry POST paths,
  error envelope mapping, 401 session teardown, inline/file result handling,
  base URL normalization.
- `NotificationCenterTest` — transition detection, dedup, worker offline,
  100-event cap, unread/mark-read/clear.
- `StatusMappingTest` — labels/tones/terminal set per SPEC §5.
- `TimeFormatTest` — ISO parsing (Instant/offset/local), relative ages, display.

There are no instrumented tests in 0.1.0 (and no emulator is claimed — none
was used).

## Copyright

Copyright © 2026 Parsa Fathi. Licensed under Apache-2.0.
