<!-- Persian documentation — see ../README.md for English -->

# توسعه‌ی TaskMesh — ساختار مخزن، build و مشارکت

این سند برای کسی است که می‌خواهد در کد TaskMesh دست ببرد: از clone تا green شدن همه‌ی تست‌ها، و قواعد مشارکت. قرارداد فنی همه‌ی کامپوننت‌ها [`../SPEC.md`](../SPEC.md) است — اگر کدی با SPEC ناسازگار شود، SPEC ملاک است (یا SPEC را با PR تغییر دهید).

---

## ساختار مخزن

```text
taskmesh/
├── backend/          # Java 21 + Spring Boot 3.5 — REST API، sweeper، WebSocket
├── worker/           # Python 3.12 — پکیج taskmesh_worker با ۷ handler
├── web/              # React 18 + Vite + TS — کنسول عملیاتی
├── android/          # Kotlin + Compose — اپ موبایل
├── database/         # schema.sql — نسخه‌ی آینه‌ای DDL برای مراجعه
├── deploy/compose/   # docker-compose.yml سه‌سرویسی + Postgres
├── docs/             # SPEC.md + مستندات انگلیسی + docs/fa/ (همین‌جا)
├── scripts/
│   └── e2e_smoke.py  # تست E2E سرتاسری (۴۴ چک)
└── .github/          # workflowهای ci / security / docker / release / dependency-review
```

هر کامپوننت README خودش را دارد (با جزئیات معماری داخلی) و `docs/fa/` همان‌ها را فارسی بازگو می‌کند.

## محیط توسعه‌ی هر کامپوننت

### backend (Java 21)

```bash
cd backend
mvn clean verify                      # build + ۹۹ تست (unit + integration)
java -jar target/taskmesh-backend.jar # اجرا
mvn spring-boot:run                   # در حین توسعه
```

- integration testها به PostgreSQL روی `localhost:5433` نیاز دارند و **دیتابیس جدا** `taskmesh_test` را خودشان می‌سازند و Flyway-migrate می‌کنند — دیتابیس dev (`taskmesh`) هرگز دست نمی‌خورد.
- مهاجرت‌ها در `src/main/resources/db/migration/`؛ هر تغییر schema یعنی migration جدید (نه ویرایش V1).
- اگر Maven/JDK در مسیر نیست: `JAVA_HOME` را به JDK 21 بگذارید و برای buildهای سنگین `MAVEN_OPTS="-Xmx1200m"`.

### worker (Python 3.12)

```bash
cd worker
python3 -m venv .venv && source .venv/bin/activate
pip install -e '.[dev]'

python -m pytest -q                   # ۱۴۶ تست (unit + integration به PG :5433)
ruff check . && ruff format --check . # lint و فرمت
python -m build                       # wheel + sdist
python scripts/smoke_test.py          # دود-تست واقعی: یک job کامل + SIGTERM
```

- تست‌های integration دیتابیس خودشان (`taskmesh_worker_test`) را از `tests/fixtures/schema.sql` می‌سازند.
- سبک کد: ruff با line-length ۱۰۰؛ type markerها (`py.typed`) پکیج را typed نگه می‌دارد.
- handler جدید: فایل در `handlers/`، مدل strict در `models.py`، ثبت در `registry.py` — و هم‌زمان کاتالوگ backend (`JobTypeCatalog`) تا API همان نوع را validate کند.

### web (Node 20+)

```bash
cd web
npm install
npm run dev          # Vite روی :5173 (پروکسی /api و /ws به :8080)
npm run test         # vitest — ۶۲ تست
npm run lint         # ESLint — سیاست صفر خطا
npm run typecheck    # tsc --noEmit با TS strict
npm run build        # build تولیدی
```

- state با zustand و data با TanStack Query؛ همه‌ی اعداد روی صفحه از endpoint واقعی یا فریم WS می‌آیند — mock ممنوع.
- فونت‌ها با @fontsource باندل می‌شوند (بدون CDN).

### android (Kotlin + Compose)

```bash
cd android
./gradlew assembleDebug        # APK دیباگ
./gradlew testDebugUnitTest    # ۷۵ تست JVM (با MockWebServer روی stack واقعی Retrofit)
./gradlew assembleRelease      # APK release — unsigned (سیاست: keystore در مخزن نیست)
```

- toolchain: Gradle 8.10.2 (wrapper واقعی در مخزن)، AGP 8.7.3، Kotlin 2.0.21، compileSdk 35.
- **نگه‌دار `android/.gitignore` را**: الگوی `data/` در gitignore ریشه، پکیج `android/.../app/data/` را قورت می‌دهد؛ گاردهای negation آن عمداً گذاشته شده‌اند و حذف‌شان CI را می‌شکند.

## تست E2E سرتاسری

وقتی کل استک (PG + backend + حداقل یک worker) بالا است:

```bash
python3 scripts/e2e_smoke.py --base-url http://localhost:8080
python3 scripts/e2e_smoke.py --fast    # مسیر سریع‌تر برای iteration
```

این اسکریپت همان چیزی است که CI نمی‌تواند فقط با unit test بگیرد: جریان واقعی login تا نتیجه، هر ۷ نوع job با تأیید دقیق خروجی (تا magic byteهای PNG و sha256 فایل)، idempotency، cancel در QUEUED و RUNNING، timeout و retry، اولویت، heartbeat ورکرها، metrics، لاگ audit و فریم‌های WebSocket. عمداً failure path هم دارد — بعد از هر تغییر معنادار در state machine یا claim، یک بار اجرایش کنید.

## CI — چه چیزی سبز باید بماند

workflow `ci` روی هر push به main و هر PR این‌ها را اجرا می‌کند:

- backend: `mvn clean verify` روی JDK 21 + سرویس PostgreSQL 16
- worker: ruff + `pytest` + build wheel و نصب آن در venv تمیز + import check
- web: `npm ci` + lint + typecheck + test + build (ماتریس Node 20 و 22)
- android: `testDebugUnitTest` + `assembleDebug`

کنارش: `security` (CodeQL سه‌زبان + gitleaks روی تاریخچه)، `dependency-review` و `docker` و `release` (ببینید [RELEASE_FA.md](RELEASE_FA.md)).

## سبک commit

از Conventional Commits استفاده می‌کنیم — کوتاه، فعل‌محور و صادق به آنچه داخل commit است:

```text
feat: add worker runtime and job handlers
fix: share storage dir between backend and worker
test: expand distributed failure coverage
docs: add bilingual documentation
ci: add automated build and security workflows
refactor: …  /  chore: …  /  build: …
```

قواعد:

- هر commit باید به کار واقعی اشاره کند؛ commit «برای پر کردن تاریخچه» نمی‌سازیم و تاریخچه‌ی جعلی نداریم.
- author همه‌ی commitها: **Parsa Fathi** (مالک پروژه).
- scope می‌شود کامپوننت را هم نشان داد: `feat(worker): add cpu_benchmark handler`.
- PR بزرگ را با چند commit معنادار بیاورید، نه یک توده.

## قواعد PR

1. **قبل از باز کردن PR** همه‌ی این‌ها روی ماشین خودتان سبز باشد: `mvn clean verify` (backend)، `pytest` + `ruff` (worker)، `lint`/`typecheck`/`test`/`build` (web)، `testDebugUnitTest` (android — اگر SDK ندارید، CI آن را اجرا می‌کند). فقط بخشی که تغییر داده‌اید کافی است، ولی CI همه را می‌گیرد.
2. **تغییر schema**؟ migration Flyway جدید لازم است + به‌روزرسانی `database/schema.sql` + `worker/tests/fixtures/schema.sql` — سه جا هم‌زمان.
3. **تغییر API یا رفتار job**؟ [`../SPEC.md`](../SPEC.md) و مستندات (انگلیسی و فارسی) باید در همان PR آپدیت شوند؛ مستندات تازه نباید از کد جا بمانند.
4. **job type جدید**؟ handler + مدل + تست unit (happy + validation) + کاتالوگ backend + یک مسیر در `e2e_smoke.py`.
5. قالب PR موجود در `.github/pull_request_template.md` را پر کنید: چه تغییراتی، چرا، چطور تست شده. CODEOWNERS (`@ParsaFathii`) باید review کند — PR بدون review merge نمی‌شود.
6. PR نباید مخلوطی از تغییرات بی‌ربط باشد — «یک PR، یک هدف».

## نکته‌های محیطی که وقت‌تان را نجات می‌دهد

- پورت‌های توسعه: PG **5433**، API **8080**، کنترل ورکر **9100+**، وب **5173**. هر چه با این‌ها تداخل دارد را قبل از بالا آوردن استک ببندید.
- در محیط‌هایی که پروسه‌های پس‌زمینه را reap می‌کنند (مثل بعضی sandboxها)، برای سرویس‌های طولانی از `setsid -f nohup <cmd>` استفاده کنید.
- اگر چند worker اجرا می‌کنید، `TASKMESH_CONTROL_PORT` هر کدام یکتا باشد.
- دیتابیس تست‌ها (`taskmesh_test` و `taskmesh_worker_test`) هر بار wipe می‌شوند؛ هیچ‌وقت `TASKMESH_DB_URL` را به آن‌ها اشاره ندهید.
