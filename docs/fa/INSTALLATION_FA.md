<!-- Persian documentation — see ../README.md for English -->

# نصب و راه‌اندازی TaskMesh

این سند، راه‌اندازی کامل TaskMesh را از صفر تا ورود به کنسول وب توضیح می‌دهد — هم به‌صورت دستی (مناسب توسعه) و هم با Docker Compose. اگر فقط می‌خواهید سریع سیستم را ببینید، مستقیم به بخش [نصب با Docker](#نصب-با-docker-compose) بروید.

> نسخه‌ی 0.1.0 · پشتیبانی از Linux و macOS (مسیرهای ویندوزی در CI هم تست نشده‌اند؛ در عمل باید کار کند ولی تضمینی نمی‌دهیم).

---

## فهرست

1. [پیش‌نیازها](#پیشنیازها)
2. [ساخت دیتابیس](#ساخت-دیتابیس)
3. [اجرای backend](#اجرای-backend)
4. [اجرای worker](#اجرای-worker)
5. [اجرای کنسول وب](#اجرای-کنسول-وب-dev)
6. [ورود با حساب‌های seed](#ورود-با-حسابهای-seed)
7. [اجرای تست E2E](#اجرای-تست-e2e)
8. [نصب با Docker Compose](#نصب-با-docker-compose)
9. [عیب‌یابی نصب](#عیبیابی-نصب)

## پیش‌نیازها

| ابزار | نسخه | برای |
|---|---|---|
| JDK | 21 | backend (build و اجرا) |
| Maven | 3.9.x | build باک‌اند |
| PostgreSQL | 16 | صف job و datastore |
| Python | 3.12 | worker |
| Node.js | 20+ | کنسول وب (dev) |
| curl / jq | هر نسخه | تست دستی API |

نکته‌های محیطی که تجربه نشان داده دردسرساز می‌شوند:

- پورت PostgreSQL توسعه‌ی محلی **5433** است، نه 5432 پیش‌فرض. اگر یک instance جدا روی 5432 دارید، تداخلی پیش نمی‌آید — فقط رشته‌های اتصال را همان 5433 نگه دارید.
- worker به پکیج‌های `psycopg[binary]`، `Pillow` و `FastAPI` نیاز دارد که خود `pip` نصب می‌کند؛ چیزی جداگانه نصب نکنید.
- در محیط توسعه‌ی محلی، PostgreSQL با trust auth بالا می‌آید (بدون رمز)؛ در استقرار واقعی حتماً رمز بگذارید.

## ساخت دیتابیس

فرض: PostgreSQL 16 روی `localhost:5433` در حال اجراست با کاربر `taskmesh` (در محیط dev معمولاً با trust auth و unix socket روی `/tmp`).

```bash
# ساخت دیتابیس dev
createdb -h localhost -p 5433 -U taskmesh taskmesh

# بررسی اتصال
psql -h localhost -p 5433 -U taskmesh -d taskmesh -c "select version();"
```

شما **هیچ جدولی را دستی نمی‌سازید**. اولین اجرای backend مهاجرت‌های Flyway (`V1__init.sql` ساخت schema و `V2__seed_users.sql` ساخت حساب‌های seed) را خودش اعمال می‌کند.

> اگر پورت 5433 با دیتابیس دیگری اشغال است، می‌توانید `TASKMESH_DB_URL` را به یک instance موجود (پورت دلخواه) تغییر دهید یا instance جدا روی 5433 بالا بیاورید — فقط دقت کنید که PostgreSQL 16 باشد، چون DDL از enum و `gen_random_uuid()` استفاده می‌کند.

## اجرای backend

```bash
cd backend

# build کامل + تست‌های unit/integration (تست‌ها به PG :5433 نیاز دارند)
mvn clean verify

# اجرا
java -jar target/taskmesh-backend.jar
# یا در حین توسعه:
mvn spring-boot:run
```

در startup:

1. Flyway مهاجرت‌ها را روی دیتابیس `taskmesh` اعمال می‌کند.
2. سرور روی پورت **8080** بالا می‌آید.
3. sweeper تعمیراتی هر ۵ ثانیه شروع به کار می‌کند.
4. thread مربوط به `LISTEN` روی کانال `taskmesh_events` فعال می‌شود.

صحت‌سنجی:

```bash
curl -s http://localhost:8080/api/v1/health
# {"status":"UP","db":"UP","version":"0.1.0"}
```

اگر secret پیش‌فرض JWT در حال استفاده باشد، backend در لاگ یک WARN صریح چاپ می‌کند — برای dev اشکالی ندارد، برای هر محیط جدی‌تر `TASKMESH_JWT_SECRET` را عوض کنید.

## اجرای worker

```bash
cd worker

# محیط مجازی (یک‌بار)
python3 -m venv .venv
source .venv/bin/activate

# نصب پکیج + console script
pip install -e .
# برای توسعه/تست: pip install -e '.[dev]'

# اجرا
export TASKMESH_DATABASE_URL=postgresql://taskmesh@localhost:5433/taskmesh
export TASKMESH_CONTROL_PORT=9100
taskmesh-worker          # معادل: python -m taskmesh_worker
```

لاگ‌های worker خروجی JSON line روی stdout هستند. اگر در لحظه‌ی استارت دیتابیس در دسترس نباشد، worker کرش نمی‌کند و با backoff دوباره تلاش می‌کند (WARN در لاگ).

صحت‌سنجی از API کنترل worker:

```bash
curl -s localhost:9100/health
# {"status":"IDLE","workerId":"…","currentJobId":null,"uptimeS":42}
```

اگر worker دومی می‌خواهید، همان فرمان را با `TASKMESH_CONTROL_PORT=9101` در ترمینال دیگری اجرا کنید — جزئیات در [WORKERS_FA.md](WORKERS_FA.md).

## اجرای کنسول وب (dev)

```bash
cd web
npm install        # Node 20+؛ package-lock.json تولید/نگهداری می‌شود (در CI: npm ci)
npm run dev        # Vite روی http://localhost:5173
```

سرور dev مسیرهای `/api` و `/ws` را به backend روی `http://localhost:8080` پروکسی می‌کند، پس **اول backend را بالا بیاورید**. هیچ env خاصی لازم نیست؛ `VITE_API_BASE` فقط وقتی API روی origin دیگری است تغییر می‌کند.

## ورود با حساب‌های seed

در <http://localhost:5173> (یا مستقیم از API) با یکی از این حساب‌ها وارد شوید:

| username | password | نقش |
|---|---|---|
| `admin` | `TaskMesh!Admin` | ADMIN — همه‌چیز |
| `operator` | `TaskMesh!Operator` | OPERATOR — عملیات و workerها |
| `user` | `TaskMesh!User` | USER — فقط کارهای خودش |

> این رمزها فقط برای توسعه‌ی محلی در migration seed شده‌اند (hashهای BCrypt). در هر محیط واقعی حساب‌های جدید بسازید و این‌ها را در دسترس نگذارید.

## اجرای تست E2E

وقتی هر سه سرویس (backend + حداقل یک worker + PG) بالا هستند:

```bash
python3 scripts/e2e_smoke.py --base-url http://localhost:8080
# یا سریع‌تر: python3 scripts/e2e_smoke.py --fast
```

این اسکریپت مسیر واقعی کاربر را می‌رود: login، ساخت پروژه، ثبت job از هر ۷ نوع با تأیید دقیق نتیجه، idempotency، اعتبارسنجی payload، اولویت، cancel در دو حالت QUEUED و RUNNING، timeout و retry، heartbeat ورکرها، metrics، لاگ audit و فریم‌های زنده‌ی WebSocket. خروجی موفق: **۴۴/۴۴ چک PASS** و exit code صفر.

وابستگی اسکریپت فقط `requests` است.

## نصب با Docker Compose

مسیر سریع بدون نصب Java/Python/Node:

```bash
cd deploy/compose

# متغیرهای الزامی (فایل .env هم می‌شود)
export TASKMESH_DB_PASSWORD='choose-a-password'    # رمز دیتابیس انتخابی
export TASKMESH_JWT_SECRET='long-random-secret'   # secret تصادفی، بلند و یکتا

docker compose -f deploy/compose/docker-compose.yml up -d --build
```

چهار سرویس بالا می‌آیند:

| سرویس | تصویر | پورت روی هاست |
|---|---|---|
| postgres | `postgres:16-alpine` | — (داخلی) |
| backend | `ghcr.io/parsafathii/taskmesh-backend:0.1.0` | 8080 |
| worker | `ghcr.io/parsafathii/taskmesh-worker:0.1.0` | — (API کنترل به‌صورت پیش‌فرض expose نیست) |
| web | `ghcr.io/parsafathii/taskmesh-web:0.1.0` | **8081** |

کنسول وب: <http://localhost:8081> · health مستقیم API: <http://localhost:8080/api/v1/health>

مقیاس‌دهی workerها:

```bash
docker compose -f deploy/compose/docker-compose.yml up -d --scale worker=4
```

volume مشترک `taskmesh-data` بین backend و worker برای `TASKMESH_STORAGE_DIR` سِت شده تا نتایج فایلی درست سرو شوند. همه‌ی Dockerfileها چندمرحله‌ای هستند و با کاربر **non-root** اجرا می‌شوند.

## عیب‌یابی نصب

### پورت اشغال است

`Port 8080 was already in use` (یا مشابه برای 5173/9100):

```bash
# پیدا کردن پروسه
ss -ltnp | grep -E ':(8080|5173|9100|5433)'
```

backend با `TASKMESH_SERVER_PORT`، worker با `TASKMESH_CONTROL_PORT` و وب با پورت Vite (سوییچ `--port` یا `WEB_PORT` در استقرار) قابل جابه‌جایی است.

### هشدار «default JWT secret in use»

یعنی `TASKMESH_JWT_SECRET` ست نشده و مقدار پیش‌فرض `taskmesh-dev-secret-change-me` فعال است. برای dev اشکال ندارد؛ برای هر چیز جدی‌تر:

```bash
export TASKMESH_JWT_SECRET="$(openssl rand -hex 32)"
```

با تغییر secret، همه‌ی tokenهای قبلی بی‌اعتبار می‌شوند (کاربران باید دوباره login کنند).

### مهاجرت PostgreSQL انجام نشد / جداول وجود ندارند

- اول مطمئن شوید واقعاً به دیتابیس درست وصل شده‌اید: `TASKMESH_DB_URL` باید به `jdbc:postgresql://localhost:5433/taskmesh` (یا معادل compose) اشاره کند.
- Flyway فقط یک‌بار در startup اجرا می‌شود؛ اگر وسط upgrade اتصال قطع شد، پروسه را ری‌استارت کنید تا ادامه دهد.
- برای شروع از صفر در dev: `dropdb -h localhost -p 5433 -U taskmesh taskmesh && createdb -h localhost -p 5433 -U taskmesh taskmesh` — بعد اجرای دوباره‌ی backend همه‌چیز از نو ساخته می‌شود (طبیعتاً داده‌های قبلی می‌روند).
- نسخه‌ی PostgreSQL باید 16 باشد؛ روی نسخه‌های خیلی قدیمی DDL اجرا نمی‌شود.

### worker بالا می‌آید ولی jobها اجرا نمی‌شوند

- `TASKMESH_DATABASE_URL` را چک کنید — این متغیر مخصوص worker است و جدا از `TASKMESH_DB_URL` باک‌اند.
- اگر job از نوعی است که در `TASKMESH_CAPABILITIES` ورکر نیست، ورکر هرگز claimش نمی‌کند (این رفتار عمدی است).
- لاگ JSON ورکر را ببینید؛ خطای claim معمولاً متن SQL و دلیلش را دارد.

### لاگین ۴۲۹ می‌گیرید

Rate limit لاگین **۱۰ تلاش در دقیقه به‌ازای کاربر** است. یک دقیقه صبر کنید یا کاربر دیگری امتحان کنید.

### نتیجه‌ی فایلی 404 می‌دهد

backend و worker باید یک `TASKMESH_STORAGE_DIR` مشترک داشته باشند (در compose خودکار است؛ در اجرای دستی حتماً ست کنید). این دقیقاً همان باگی بود که در integration پیدا و با volume مشترک رفع شد.

---

مرحله‌ی بعد: [USER_GUIDE_FA.md](USER_GUIDE_FA.md) برای گشت‌وگذار در کنسول وب و اپ اندروید، یا [ARCHITECTURE_FA.md](ARCHITECTURE_FA.md) اگر کنجکاوید چرا صف روی PostgreSQL است.
