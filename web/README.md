# TaskMesh Web Console

TypeScript + React 18 + Vite SPA — the operations console for the TaskMesh
distributed job-processing platform. It speaks **only** the REST + WebSocket API
defined in [`../docs/SPEC.md`](../docs/SPEC.md) §8 (no mock data, no fake
timers: every number on screen comes from a live endpoint or a WS frame).

Version `0.1.0` (matches the backend / health version).

---

## Quick start

```bash
npm install          # Node 20+; generates package-lock.json (CI: npm ci)
npm run dev          # Vite dev server on http://localhost:5173
```

The dev server proxies `/api` and `/ws` (WebSocket) to the Java backend on
`http://localhost:8080` (see `vite.config.ts`). Start the backend first:

```bash
cd ../backend && java -jar target/taskmesh-backend.jar   # JDK 21, port 8080
```

Local dev accounts (seeded by the backend): `admin` / `operator` / `user`
(default dev passwords are documented in the backend README).

### All scripts

| script             | what it does                                            |
|--------------------|---------------------------------------------------------|
| `npm run dev`      | Vite dev server (proxy → backend :8080)                 |
| `npm run build`    | production build → `dist/`                              |
| `npm run preview`  | serve the production build locally                      |
| `npm run test`     | vitest once (`npm run test -- --run` also works)        |
| `npm run lint`     | ESLint (flat config, zero-error policy)                 |
| `npm run typecheck`| `tsc --noEmit` (strict)                                 |

### Environment

| var              | default    | purpose                                             |
|------------------|-----------|------------------------------------------------------|
| `VITE_API_BASE`  | `/api/v1` | REST base URL. Keep relative behind the dev proxy / nginx; set an absolute URL only when the API lives on another origin. |

The WS endpoint is derived from the page origin (`ws(s)://host/ws/v1/events?token=…`),
so the same proxy rule serves it in dev.

---

## Routes

| route           | page           | notes                                                       |
|-----------------|----------------|-------------------------------------------------------------|
| `/login`        | Login          | JWT + user persisted to `localStorage["taskmesh.session"]`; 401 surfaced inline |
| `/`             | Operations     | QueueLanes + KPI strip + worker fleet + WS event ticker (role-adaptive) |
| `/projects`     | Projects       | table + create dialog (POST /projects)                      |
| `/projects/:id` | Project detail | info + project jobs + edit/delete (owner)                   |
| `/jobs`         | Jobs           | dense filterable table, server-side paging, WS row flash    |
| `/jobs/new`     | Job creation   | payload form generated from the live `/job-types` catalog   |
| `/jobs/:id`     | Job detail     | timeline, attempts, live log console, result, cancel/retry  |
| `/workers`      | Workers        | OPERATOR/ADMIN only — tiles + table, stale flags            |
| `/workers/:id`  | Worker detail  | state ring, heartbeat age, current job, live worker events  |
| `/logs`         | Audit logs     | OPERATOR/ADMIN only — actor/action/result filters + paging  |
| `/metrics`      | Metrics        | OPERATOR/ADMIN only — stat tiles + bars from /metrics       |
| `/settings`     | Settings       | /me profile, token reveal/copy, logout, system panel        |

## Role-adaptive behavior (SPEC §11)

Authorization is **always enforced server-side**; the UI only hides what the
API already denies:

- **ADMIN / OPERATOR** — full console: cluster KPIs from `/metrics`, worker
  fleet from `/workers`, audit trail, lane depth from metrics.
- **USER** — Operations shows *own-job* KPIs and their own queued jobs in the
  lanes (the API filters job lists per owner); the workers and metrics sections
  are never rendered (those endpoints 403), and `/workers`, `/logs`, `/metrics`
  routes show an explicit "requires OPERATOR" panel instead of crashing.
- Action buttons (cancel/retry) appear only for owners or operators; job
  detail hides deep links to 403 endpoints for USER.

## Real-time strategy (WS `useEvents`)

`src/ws/EventSocket.ts` owns the connection to `/ws/v1/events?token=<JWT>`:

- parses SPEC §8 JSON text frames, silently drops malformed ones
- **reconnect with exponential backoff 1s → 2s → 4s … capped 30s, ±20 % jitter**
- **stale detection**: no frame for 30 s → force reconnect
- connection state drives the top-bar LED (`live / reconnecting n / down`) and
  the amber "Reconnecting…" banner; the attempt counter resets after a
  successful open
- frames feed **two paths**:
  1. *direct streams* — bottom `EventTicker` tape, live job log console
     (`job.log` frames merged with the REST history), row flash highlights
  2. *TanStack Query invalidation* — `job.updated` → `job`/`jobs` caches,
     `worker.updated` → `workers`/`worker`, terminal transitions → `metrics`,
     debounced 400 ms to survive event bursts

REST layer (`src/api/client.ts`): bearer injection from the auth store, error
normalization to the SPEC shape `{"error":{"code","message"}}`, network errors
→ `NETWORK` ApiError, and 401 on protected endpoints drops the session and
redirects to `/login` (a login-endpoint 401 is shown as invalid credentials
instead).

## Design system — "queue lanes" console

A single, intentional dark theme (an operations control room, not a marketing
dashboard). Tokens live in `src/index.css` under Tailwind CSS 4 `@theme`:

| token             | value     | use                                  |
|-------------------|-----------|--------------------------------------|
| `--color-bg`      | `#101418` | page background (graphite)           |
| `--color-bg-deep` | `#0b0e12` | rail, ticker, log consoles           |
| `--color-panel`   | `#151a21` | bordered panels                      |
| `--color-panel-2` | `#1b212b` | hover surfaces                       |
| `--color-line`    | `#262d37` | hairline borders                     |
| `--color-ink`     | `#e6e8ea` | primary text                         |
| `--color-mute`    | `#8b949e` | secondary text                       |
| `--color-amber`   | `#f5a623` | **primary accent** — actions, highlights, RUNNING |
| `--color-emerald` | `#2ea043` | SUCCEEDED / success                  |
| `--color-rose`    | `#f85149` | FAILED / danger                      |
| `--color-yellow`  | `#d29922` | RETRYING / warnings                  |
| `--color-orange`  | `#e5683c` | TIMED_OUT                            |
| `--color-teal`    | `#39c5cf` | info / logs INFO                     |

- **No default Tailwind blue/indigo anywhere**; no light theme by design
  (documented on the Settings page).
- Typography: `@fontsource-variable/inter` for UI, `@fontsource-variable/jetbrains-mono`
  for **all data** (ids, timestamps, numbers, payloads). Fonts are bundled —
  no CDN.
- Shape language: 4–6 px radii, 1 px hairline borders, dotted-graphite
  background texture, uppercase micro-labels with `0.12em` letter-spacing
  (the `micro` utility).
- Motion: only two subtle effects — a 1.6 s opacity pulse on RUNNING/BUSY
  status dots and a 1.8 s amber row-flash when a WS event updates a row.
- Layout: 64 px left icon rail with tooltips (bottom tab bar ≤ md), fixed top
  context bar (breadcrumb + page chips + WS LED + user menu), fixed bottom
  EventTicker tape. Responsive from 375 px up.
- Signature components (`src/components/`): `QueueLanes` (CRITICAL/HIGH/NORMAL/LOW
  lanes with live counts + scrolling job chips), `EventTicker`, `WorkerTile`
  (SVG state ring + heartbeat age), `TimelineStepper`, `JsonInspector`
  (tree/raw JSON viewer), plus `StatusDot`-style badges in `ui/Bits.tsx`.
- Accessibility: semantic landmarks (`nav/main/header/footer/section`),
  skip-to-content link, `aria-label`s on the rail + every icon-only control,
  amber focus rings, keyboard-operable JSON tree nodes and modals (Esc/backdrop
  close, focus restore), `role="status"` for the connection LED.

## Tests

`vitest` + `@testing-library/react` (jsdom), 62 tests across 6 files:

- `apiClient.test.ts` — token injection, content-type, SPEC error
  normalization, 401 session-drop vs login-401, 403 pass-through, network
  errors, query-string building
- `useEvents.test.ts` — frame parsing, backoff schedule + jitter, reconnect
  escalation, stale-window reconnect, close-is-final, dispatch side effects
  (cache invalidation, ticker, live logs, hello)
- `authStore.test.ts` — persistence to `taskmesh.session`, logout, expiry flag,
  rehydration
- `JobDetails.test.tsx` — timeline from fixtures, cancel visibility per
  status, attempts + log console rendering, role gating of worker links,
  cancel confirmation dialog POST
- `QueueLanes.test.tsx` — 4 lanes in claim order, chip placement by priority,
  authoritative depth from metrics, chip navigation
- `wireShapes.test.ts` — regression: parses the *verbatim* JSON shapes the
  live backend returns for `/metrics` and `/job-types`

## Production build

```bash
npm run build    # ~350 kB JS (105 kB gzip) + 48 kB CSS (11 kB gzip)
npm run preview  # verify locally
```

Single-bundle SPA; served by nginx in the deployment layout (same origin as
the API, so the relative `/api/v1` and `/ws/v1` paths keep working).
