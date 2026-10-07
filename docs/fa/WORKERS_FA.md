<!-- Persian documentation — see ../README.md for English -->

# Workerها — runtime، heartbeat، lease و shutdown

worker پایتونی TaskMesh یک پروسه‌ی سبک و «صادق» است: نه state مخفی دارد، نه خودش را زنده‌تر از آنچه هست نشان می‌دهد. اگر پروسه بمیرد، heartbeatها متوقف می‌شوند و backend در ۳۰ ثانیه‌ی بعدی ماجرا را می‌فهمد. این سند رفتار worker را از استارت تا shutdown مرور می‌کند.

نمودار: [چرخه‌ی حیات worker](../assets/).

---

## چرخه‌ی حیات و وضعیت‌ها

هر worker در start خودش را در جدول `workers` ثبت می‌کند (INSERT با UUID یکتا که تا پایان عمر پروسه ثابت می‌ماند) و از آن به بعد وضعیتش در این شش حالت می‌چرخد:

| وضعیت | معنی |
|---|---|
| `STARTING` | در حال register — از INSERT تا اولین loop موفق |
| `IDLE` | زنده و آزاد — منتظر claim |
| `BUSY` | در حال اجرای یک job (در `current_job_id` ثبت است) |
| `DRAINING` | SIGTERM گرفته، job جاری را تمام می‌کند ولی job جدید نمی‌گیرد |
| `OFFLINE` | heartbeat قطع شده و sweeper علامت گذاشته (یا خودش تمیز خارج شده) |
| `ERROR` | خطای مهلک داخلی |

نکته‌ی طراحی: worker هرگز «سازگار دروغ» نمی‌گوید. تشخیص staleness وظیفه‌ی backend است نه worker — چون وقتی پروسه مرد، نمی‌شود از خودش پرسید چطور است.

## Heartbeat و تشخیص stale

- هر `TASKMESH_HEARTBEAT_INTERVAL_S` ثانیه (پیش‌فرض **۱۰**)، worker ردیف خودش را آپدیت می‌کند: `last_heartbeat`، status (`IDLE`/`BUSY`) و `current_job_id`.
- backend در هر بار sweep (هر ۵ ثانیه) ورکرهایی را که `last_heartbeat < now() - 3×interval` باشد `OFFLINE` می‌کند — یعنی با تنظیم پیش‌فرض، مرگ یک worker حداکثر ~۳۰ ثانیه بعد در UI دیده می‌شود.
- در کنسول وب، flag `stale` همان فاصله‌ی heartbeat است؛ ورکر زنده ولی کند هم دیده می‌شود تا با OFFLINE اشتباه نشود.

## Lease و تمدید آن

هر claim یک «اجاره» دارد:

- در لحظه‌ی claim: `lease_expires_at = now() + max(30, timeout_seconds)`.
- worker هم‌زمان با هر heartbeat، lease را هم تمدید می‌کند (به‌علاوه‌ی حاشیه‌ی امنیتی `TASKMESH_LEASE_MARGIN_S`، پیش‌فرض ۱۵ ثانیه). اگر handler طولانی‌تر از timeout باشد ولی worker زنده و progress بدهد، lease زنده می‌ماند و sweeper بی‌دلیل job را نمی‌دزد.
- اگر lease منقضی شود (پروسه مرده یا گیر کرده)، sweeper job را آزاد می‌کند: attempt جاری با outcome `ABANDONED` بسته می‌شود و job با backoff به `RETRYING` می‌رود تا ورکر دیگری (یا همان، اگر زنده برگردد) بگیردش.

این همان مکانیزمی است که در تست E2E با SIGKILL وسط job امتحان شد: attempt 1 با ABANDONED، سپس claim توسط ورکر دوم و SUCCEEDED در attempt 2.

## Shutdown — تفاوت SIGTERM و SIGINT (و SIGKILL)

این سه‌تایی را جدی بگیرید، چون رفتارشان واقعاً فرق دارد:

| سیگنال | رفتار worker | سرانجام job جاری |
|---|---|---|
| **SIGTERM** | `DRAINING` — job جدید claim نمی‌کند، job جاری را تمام می‌کند، بعد exit 0 | طبق نتیجه‌ی واقعی اجرا (معمولاً SUCCEEDED) |
| **SIGINT** (Ctrl+C) | job جاری رها می‌شود و exit 130 | `RETRYING` با attempt `ABANDONED` — رفتار معادل کرش، برای وقتی که نمی‌خواهید صبر کنید |
| **SIGINT دوباره** | خروج فوری بدون نهایی‌سازی | همان بالایی؛ lease بعداً توسط sweeper آزاد می‌شود |
| **SIGKILL** | هیچ فرصتی ندارد | sweeper بعد از انقضای lease → `ABANDONED` → RETRYING |

پس قانون عملیاتی ساده است: **برای restart تمیز `SIGTERM` بدهید** (deployها با Docker/K8s به‌طور پیش‌فرض همین را می‌فرستند). SIGINT فقط برای «الان قطع کن، مهم نیست چه می‌شود».

## API کنترل هر worker

هر worker یک FastAPI سبک روی `TASKMESH_CONTROL_PORT` (پیش‌فرض **9100**) بالا می‌آورد:

```bash
curl -s localhost:9100/health
# {"status":"IDLE","workerId":"…","currentJobId":null,"uptimeS":123}

curl -s localhost:9100/status | jq .
# ردیف کامل workers + پیکربندی (در نبود DB کد 503 می‌دهد)
```

- `/health` کاملاً در حافظه است و همیشه در دسترس — حتی وقتی DB قطع است؛ برای healthcheckها همین را بگیرید.
- این port در compose به‌صورت پیش‌فرض روی هاست expose نمی‌شود؛ اگر لازم دارید دستی publish کنید.

## پیکربندی (متغیرهای محیطی)

| متغیر | پیش‌فرض | توضیح |
|---|---|---|
| `TASKMESH_DATABASE_URL` | `postgresql://taskmesh@localhost:5433/taskmesh` | رشته‌ی اتصال worker به دیتابیس صف |
| `TASKMESH_CAPABILITIES` | هر ۷ نوع | لیست comma-جدا از نوع jobهایی که claim می‌کند |
| `TASKMESH_HEARTBEAT_INTERVAL_S` | `10` | کادنس heartbeat و تمدید lease |
| `TASKMESH_LEASE_MARGIN_S` | `15` | حاشیه‌ی تمدید lease |
| `TASKMESH_CONTROL_PORT` | `9100` | پورت API کنترل (هر نمونه باید متفاوت باشد) |
| `TASKMESH_STORAGE_DIR` | `./data` | ریشه‌ی ذخیره‌ی نتایج فایلی — **مشترک با backend** |
| `TASKMESH_WORKER_NAME` | `<hostname>-<pid>` | نام نمایشی در جدول workers |

اگر DB در لحظه‌ی استارت در دسترس نباشد، worker با backoff دوباره تلاش می‌کند و WARN می‌زند؛ کرش نمی‌کند. لاگ‌ها JSON line روی stdout هستند — مستقیم pipe به جمع‌کننده‌ی لاگ (jq، Loki، …) قابل خوردن است.

## اجرای چند worker

workerها به هم اهمیت نمی‌دهند؛ تعدادشان فقط با PostgreSQL هماهنگ است:

```bash
# ترمینال ۱
TASKMESH_CONTROL_PORT=9100 TASKMESH_WORKER_NAME=worker-1 taskmesh-worker

# ترمینال ۲
TASKMESH_CONTROL_PORT=9101 TASKMESH_WORKER_NAME=worker-2 taskmesh-worker

# ترمینال N — هر پورت کنترل و نام یکتا
```

- **پورت کنترل را حتماً یکتا بدهید** (9100، 9101، …) وگرنه پروسه‌ی دوم روی bind خطا می‌خورد.
- `TASKMESH_STORAGE_DIR` بین همه‌ی workerها و backend مشترک باشد (نتیجه‌ی فایلی هر ورکر باید از API قابل خواندن باشد).
- برای تخصصی‌کردن ماشین‌ها، capability بدهید:

```bash
# فقط این ماشین عکس و benchmark کار می‌کند
TASKMESH_CAPABILITIES=image_resize,cpu_benchmark taskmesh-worker
```

در Docker compose همان کار با scale انجام می‌شود:

```bash
docker compose -f deploy/compose/docker-compose.yml up -d --scale worker=4
```

## وقتی worker می‌میرد، چه اتفاقی می‌افتد؟ (خلاصه‌ی بازیابی)

1. heartbeatها می‌ایستند.
2. بعد از ~۳×interval (پیش‌فرض ۳۰ ثانیه) sweeper ورکر را `OFFLINE` می‌کند.
3. اگر jobی در دستش بود، lease منقضی و آزاد می‌شود: attempt `ABANDONED` + job `RETRYING` + backoff.
4. ورکر زنده‌ی بعدی job را claim می‌کند و از صفر (نه وسط کار — idempotency مسئولیت handler است) اجرا می‌کند.

## تست و بسته‌بندی (برای توسعه‌دهنده‌های worker)

```bash
cd worker && source .venv/bin/activate
python -m pytest -q              # ۱۴۶ تست (unit + integration؛ به PG :5433 نیاز دارد)
ruff check . && ruff format --check .
python -m build                  # wheel + sdist در dist/
python scripts/smoke_test.py     # یک job واقعی از ثبت تا SUCCEEDED + SIGTERM drain
```

معماری داخلی پکیج (`taskmesh_worker`): `runtime.py` حلقه‌ی اصلی و claim، `handlers/` هفت handler مستقل، `models.py` schemaهای strict، `storage.py` نتیجه‌ی inline/فایل با محافظت traversal، `control_api.py` API کنترل و `shutdown.py` مدیریت سیگنال‌ها. اضافه‌کردن نوع job جدید = یک فایل handler + ثبت در registry و کاتالوگ backend.
