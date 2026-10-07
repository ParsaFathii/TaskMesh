<!-- Persian documentation — see ../README.md for English -->

# TaskMesh — پلتفرم پردازش توزیع‌شده‌ی job و ارکستراسیون worker

> نسخه 0.1.0 · صاحب پروژه: [Parsa Fathi](https://github.com/ParsaFathii) · مجوز Apache-2.0
> مخزن: <https://github.com/ParsaFathii/TaskMesh> · Copyright © 2026 Parsa Fathi

[English documentation](../README.md) | مستندات فارسی (همین صفحه)

---

## TaskMesh چیست؟

TaskMesh یک پلتفرم متن‌باز برای **اجرای قابل‌اتکای jobهای پس‌زمینه روی چند ماشین** است. شما job را با یک payload مشخص ثبت می‌کنید، TaskMesh آن را در صف می‌گذارد، یک worker آزاد آن را به‌صورت اتمی claim می‌کند، اجرایش می‌کند، progress و لاگ را زنده گزارش می‌دهد و نتیجه را (inline یا فایل) کنار خود job نگه می‌دارد. اگر وسط راه worker کرش کرد، خطای گذرا رخ داد یا execution از timeout گذشت، خودِ سیستم مسئول بازیابی است؛ شما فقط نتیجه را می‌بینید.

## مسئله‌ای که TaskMesh حل می‌کند

توسعه‌دهنده‌ای که تا حالا صف کار توزیع‌شده راه انداخته باشد، این دردها را می‌شناسد:

- **claim اتمی** — دو worker نباید همزمان یک job را بردارند. TaskMesh با `SELECT … FOR UPDATE SKIP LOCKED` روی PostgreSQL هر claim را به یک `UPDATE` اتمی تبدیل می‌کند؛ تکرار تحویلِ هر attempt عملاً غیرممکن است.
- **retry و backoff** — خطای گذرا باید خودکار با فاصله‌ی نمایی دوباره اجرا شود (`min(300, 2^retry × 5)` ثانیه) تا طوفان retry خرابکاری نکند.
- **timeout** — job گیرکرده نباید برای همیشه «در حال اجرا» بماند. سه لایه‌ی timeout (thread در worker، lease در دیتابیس، sweeper در backend) این تضمین می‌کنند.
- **visibility** — هر job دقیقاً معلوم است کجاست: در کدام صف، روی کدام worker، چند بار attempt خورده، لاگش چه گفته و نتیجه‌اش کجاست. کنسول وب و اپ اندروید همه‌ی این‌ها را زنده (WebSocket) نشان می‌دهند.
- **بازیابی از کرش** — اگر پروسه‌ی worker با SIGKILL کشته شود، lease منقضی می‌شود، sweeper attempt را ABANDONED ثبت می‌کند و job با backoff دوباره وارد صف می‌شود. این سناریو در تست E2E واقعی (بازیابی از crash) تکرار و تأیید شده است.

جمع‌بندی یک‌خطی: **صفی که کرش، تکرار و گم‌شدن job ندارد**، بدون آن‌که مجبور باشید یک broker جداگانه راه بیندازید و عملش کنید.

## در یک نگاه

| مؤلفه | پشته | توضیح |
|---|---|---|
| **backend** | Java 21 + Spring Boot 3.5 | REST API، JWT، state machine، sweeper (هر ۵ ثانیه)، broadcast رویدادهای WebSocket |
| **صف و دیتابیس** | PostgreSQL 16 | هم datastore و هم queue؛ claim با `SKIP LOCKED`، بیدارباش با `LISTEN/NOTIFY` روی کانال `taskmesh_events` |
| **worker** | Python 3.12 (psycopg، Pillow، FastAPI) | runtime سبک با ۷ handler واقعی، heartbeat، lease renewal، API کنترل روی :9100 |
| **کنسول وب** | React 18 + Vite + TypeScript | داشبورد عملیاتی با queue laneها، لاگ زنده، timeline و metrics |
| **اپ اندروید** | Kotlin + Jetpack Compose | مانیتورینگ موبایل jobها، notification داخلی و مدیریت workerها |

## نقشه‌ی معماری

```text
                ┌─────────────┐   REST/WS    ┌──────────────┐
   کاربر/اپ ───▶│ کنسول وب /  │─────────────▶│  backend     │
                │ اپ اندروید  │              │  (Java 21)   │
                └─────────────┘              └──────┬───────┘
                                                    │ SQL (JDBC)
                                                    ▼
                                          ┌──────────────────┐
        NOTIFY (taskmesh_events) ◀────────│   PostgreSQL 16  │
        LISTEN ────────────────────────▶   │  صف + state +    │
                                          │  نتایج + لاگ‌ها   │
                                          └────────┬─────────┘
                                                   │ claim (SKIP LOCKED)
                                    ┌──────────────┼──────────────┐
                                    ▼              ▼              ▼
                              ┌─────────┐    ┌─────────┐    ┌─────────┐
                              │ worker 1│    │ worker 2│    │ worker N│
                              │ Python  │    │ Python  │    │ Python  │
                              │ :9100   │    │ :9101   │    │ :9100+N │
                              └─────────┘    └─────────┘    └─────────┘
```

دیگر نمودارها در [`../assets/`](../assets/) موجودند (معماری سیستم، چرخه‌ی حیات job، چرخه‌ی حیات worker، جریان retry و خط لوله‌ی release).

## شروع سریع — توسعه‌ی محلی

پورت‌های توسعه‌ی محلی: PostgreSQL **5433**، API **8080**، API کنترل workerها **9100 به بعد**، سرور dev وب **5173**.

```bash
# 0) پیش‌نیازها: Java 21، PostgreSQL 16، Python 3.12، Node 20+
#    جزئیات کامل در INSTALLATION_FA.md

# 1) دیتابیس
createdb -h localhost -p 5433 -U taskmesh taskmesh

# 2) backend (مهاجرت‌های Flyway خودکار اعمال می‌شوند)
cd backend
mvn clean verify                      # build + تست‌ها (۹۹ تست)
java -jar target/taskmesh-backend.jar # API روی http://localhost:8080

# 3) worker (در یک ترمینال دیگر)
cd ../worker
python3 -m venv .venv
source .venv/bin/activate
pip install -e .
export TASKMESH_DATABASE_URL=postgresql://taskmesh@localhost:5433/taskmesh
export TASKMESH_CONTROL_PORT=9100
taskmesh-worker                       # لاگ JSON روی stdout

# 4) کنسول وب (ترمینال سوم)
cd ../web
npm install
npm run dev                           # http://localhost:5173
```

ورود با حساب‌های seed (مخصوص توسعه‌ی محلی):

| username | password | نقش |
|---|---|---|
| `admin` | `TaskMesh!Admin` | ADMIN |
| `operator` | `TaskMesh!Operator` | OPERATOR |
| `user` | `TaskMesh!User` | USER |

برای اطمینان از سلامت کل استک، smoke تست سرتاسری را اجرا کنید:

```bash
python3 scripts/e2e_smoke.py --base-url http://localhost:8080
# ۴۴ چک، از احراز هویت و همه‌ی انواع job تا cancel، timeout و WebSocket
```

## اجرای کامل با Docker Compose

اگر Docker دارید و نمی‌خواهید Java/Python/Node نصب کنید:

```bash
cd deploy/compose
export TASKMESH_DB_PASSWORD='choose-a-real-password'      # رمز دیتابیس
export TASKMESH_JWT_SECRET='long-random-secret'           # secret تصادفی JWT
docker compose -f deploy/compose/docker-compose.yml up -d --build
```

- کنسول وب: <http://localhost:8081>
- API مستقیم: <http://localhost:8080/api/v1/health>
- سه Dockerfile چندمرحله‌ای و **non-root** برای backend / worker / web وجود دارد.
- مقیاس workerها به‌صورت افقی:

```bash
docker compose -f deploy/compose/docker-compose.yml up -d --scale worker=4
```

دقت کنید که `TASKMESH_STORAGE_DIR` بین backend و worker باید **یک volume مشترک** باشد تا نتایجِ فایلی (مثلاً خروجی `image_resize`) از سمت API قابل سرو شدن باشند.

## انواع job (۷ نوع)

| نوع | ورودی (payload) | خروجی (result) |
|---|---|---|
| `csv_analysis` | متن CSV تا 2MB، delimiter اختیاری | پروفایل ستون‌ها: نوع، missing، mean، … |
| `json_transform` | آبجکت JSON + لیست عملیات pick/remove/rename/flatten | خروجی تبدیل‌شده + تعداد عملیات اعمال‌شده |
| `image_resize` | عکس (base64) + عرض/ارتفاع | عکس تغییراندازه‌یافته به‌عنوان FILE result با sha256 |
| `hash_sha256` | متن یا محتوای base64 (دقیقاً یکی) | هش SHA-256 |
| `text_statistics` | متن تا 2MB | تعداد کلمات/خطوط، top words و زمان مطالعه |
| `archive_inspection` | فایل ZIP (base64) تا 20MB | فهرست ورودی‌ها، نسبت فشرده‌سازی، … |
| `cpu_benchmark` | workload و مدت (۱ تا ۳۰ ثانیه) | تعداد عملیات و ops/ثانیه |

جدول کامل فیلدها و مثال payloadها در [JOBS_FA.md](JOBS_FA.md) و همچنین endpoint زنده‌ی `GET /api/v1/job-types` آمده است. هیچ jobی کد دلخواه اجرا نمی‌کند — فقط همین ۷ نوع typed.

## نقش‌ها

| نقش | دسترسی |
|---|---|
| **ADMIN** | همه‌چیز: مدیریت کاربران، workerها، همه‌ی jobها و پروژه‌ها، لاگ‌های audit و metrics |
| **OPERATOR** | workerها، retry/cancel همه‌ی jobها، لاگ‌ها و metrics (بدون مدیریت کاربران) |
| **USER** | پروژه‌ها و jobهای خودش — ثبت job، دیدن نتیجه، cancel/retry کارهای خود |

اعمال مجوز همیشه سمت سرور انجام می‌شود؛ UI فقط چیزی را مخفی می‌کند که backend قبلاً رد کرده است.

## وضعیت تست‌ها (همه سبز و واقعی)

| مجموعه | تعداد |
|---|---|
| backend (Maven) | ۹۹ |
| worker (pytest) | ۱۴۶ |
| وب (vitest) | ۶۲ |
| اندروید (JVM unit) | ۷۵ |
| E2E (`scripts/e2e_smoke.py`) | ۴۴/۴۴ چک موفق |

CI روی GitHub Actions با workflowهای ci / security / dependency-review / docker / release به‌همراه Dependabot اجرا می‌شود.

## مستندات فارسی

| سند | موضوع |
|---|---|
| [INSTALLATION_FA.md](INSTALLATION_FA.md) | نصب و راه‌اندازی گام‌به‌گام + عیب‌یابی |
| [USER_GUIDE_FA.md](USER_GUIDE_FA.md) | راهنمای کنسول وب و اپ اندروید |
| [ARCHITECTURE_FA.md](ARCHITECTURE_FA.md) | تصمیم‌های معماری و مدل داده |
| [JOBS_FA.md](JOBS_FA.md) | انواع job، چرخه‌ی حیات، retry و cancel |
| [WORKERS_FA.md](WORKERS_FA.md) | runtime ورکر، heartbeat، lease و shutdown |
| [API_FA.md](API_FA.md) | مرجع REST و WebSocket با مثال curl |
| [SECURITY_FA.md](SECURITY_FA.md) | احراز هویت، مجوزها و زنجیره‌ی تأمین |
| [DEVELOPMENT_FA.md](DEVELOPMENT_FA.md) | مشارکت در توسعه، build و تست |
| [RELEASE_FA.md](RELEASE_FA.md) | فرایند انتشار و artifactها |
| [COPYRIGHT_FA.md](COPYRIGHT_FA.md) | مجوز Apache-2.0 و مالکیت معنوی |

نسخه‌ی انگلیسی مستندات: [`../README.md`](../README.md) — قرارداد فنی کامل در [`../SPEC.md`](../SPEC.md).

## محدودیت‌های شناخته‌شده (صادقانه)

- صف روی PostgreSQL پیاده شده؛ broker خارجی (مثل RabbitMQ) وجود ندارد. این یک تصمیم معماری آگاهانه است و مسیر مهاجرت آینده در SPEC مستند شده.
- APK اندروید release **امضا نشده** است؛ برای توزیع باید با keystore خودتان امضا کنید.
- کنسول وب فقط تم تیره دارد — طراحی عمدی، نه کمبود.
- تغییر رمز عبور از UI هنوز پیاده نشده است.

## مجوز

Copyright © 2026 Parsa Fathi — مجوز [Apache-2.0](COPYRIGHT_FA.md). توضیح ساده‌ی مجوز به فارسی در [COPYRIGHT_FA.md](COPYRIGHT_FA.md) آمده است.
