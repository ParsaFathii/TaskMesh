<!-- Persian documentation — see ../README.md for English -->

# معماری TaskMesh

این سند «چرا»ی TaskMesh است: چرا صف روی PostgreSQL نشسته، job چطور از یک وضعیت به وضعیت دیگر می‌رود، timeout چند لایه دارد و چطور سیستم مقیاس می‌گیرد. مرجع فنی رسمی، [`../SPEC.md`](../SPEC.md) است؛ اینجا همان تصمیم‌ها را با استدلال مهندسی باز می‌کنیم.

نمودارهای مرتبط: [معماری سیستم](../assets/) · [چرخه‌ی حیات job](../assets/) · [چرخه‌ی حیات worker](../assets/) · [جریان retry](../assets/).

---

## نمای کلی اجزا

چهار پروسه‌ی مستقل روی یک PostgreSQL مشترک:

1. **backend (Java 21 / Spring Boot 3.5)** — REST API، احراز هویت JWT، اعتبارسنجی payload، state machine، sweeper تعمیراتی، LISTEN/NOTIFY و broadcast به WebSocket.
2. **worker (Python 3.12)** — هر نمونه یک پروسه‌ی مستقل است که خودش را register می‌کند، heartbeat می‌فرستد، job claim می‌کند و اجرا می‌کند.
3. **کنسول وب (React/Vite/TS)** — SPA که فقط با REST + WebSocket حرف می‌زند؛ هیچ منطق صفی سمت کلاینت نیست.
4. **اپ اندروید (Kotlin/Compose)** — همان قرارداد API، برای موبایل.

backend و workerها **هیچ ارتباط مستقیمی با هم ندارند**؛ هماهنگی‌شان فقط از طریق ردیف‌های جدول `jobs` (claim، lease، heartbeat) و کانال NOTIFY انجام می‌شود. همین ساده‌گرفتنِ هماهنگی است که کرش یک طرف را برای طرف دیگر بی‌خطر می‌کند.

## چرا PostgreSQL به‌عنوان queue؟ (تصمیم معماری شماره‌ی یک)

طبیعی است که اول از همه بپرسید «چرا RabbitMQ/Redis/Kafka نه؟». جواب کوتاه: در مقیاس و SLA موردنظر TaskMesh (ده‌ها worker، هزاران job در ساعت، نیاز به audit کامل)، PostgreSQL به‌تنهایی هر چهار گوشه‌ی مسئله را حل می‌کند و یک مؤلفه‌ی عملیاتی کمتر به شما تحمیل نمی‌کند:

- **Claim اتمی با `FOR UPDATE SKIP LOCKED`**: هر worker یک job را با یک `UPDATE` اتمی قفل می‌کند. اگر دو worker همزمان سراغ یک job بروند، دومی ردیف قفل‌شده را skip می‌کند و سراغ بعدی می‌رود — نه قفل منتظری هست، نه double-delivery. این الگو سال‌هاست در job queueهای production اثبات‌شده (صف‌های چند-مصرفی روی Postgres) جواب داده است.
- **بیدارباش با `LISTEN/NOTIFY`**: به‌جای polling سنگین، backend و workerها روی کانال `taskmesh_events` بیدار می‌شوند. در نبود رویداد (مثلاً بعد از backoff زمان‌دار)، polling کم‌بسامد هم هست — یعنی سازوکار زمان‌محورِ `available_at` هم پشتیبان است.
- **lease با `lease_expires_at`**: job در حال اجرا «ملکِ» worker است تا وقتی lease تمدید می‌شود. اگر worker بمیرد، lease منقضی می‌شود و sweeper job را آزاد می‌کند — بدون آن‌که worker نیاز به «خداحافظی» درست داشته باشد.
- **audit در SQL**: هر چیز (attempt، لاگ، retry، نتیجه) ردیف جدول است. می‌توانید با یک SELECT تاریخچه‌ی کامل هر job را — حتی سال‌ها بعد — بازسازی کنید. debug کردن صف با کوئری، ارزشی است که brokerهای external به‌سختی می‌دهند.

trade-off هم صادقانه بگوییم: throughput صف به write throughput یک instance PostgreSQL گره خورده است و semantic صف با semantic دیتای شما در یک lock-space است. برای همین، **لایه‌ی queue پشت مرز repository/DAO ایزوله شده** تا اگر روزی RabbitMQ اضافه شد، مدل دامنه دست نخورد. این مسیر آینده در SPEC §3 مستند است، نه در کد — چون پیاده نشده.

## ماشین حالت job

هفت وضعیت: `QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED / RETRYING / TIMED_OUT`. گذارهای مجاز:

```text
QUEUED    → RUNNING        (claim توسط worker — SKIP LOCKED)
QUEUED    → CANCELLED      (cancel کاربر — فوری)
RUNNING   → SUCCEEDED      (worker با موفقیت تمام کرد)
RUNNING   → FAILED         (خطای غیرقابل-retry، یا اتمام retryها)
RUNNING   → RETRYING       (خطای گذرا و retry_count < max_retries)
RUNNING   → TIMED_OUT      (timeout و اتمام retryها)
RUNNING   → CANCELLED      (cancel تعاملی؛ sweeper بعد از lease + ۳۰ ثانیه grace نهایی می‌کند)
RETRYING  → RUNNING        (claim مجدد بعد از سپری‌شدن available_at)
RETRYING  → CANCELLED      (cancel در زمان انتظار backoff)
FAILED    → QUEUED         (retry دستی از API؛ retry_count صفر می‌شود)
TIMED_OUT → QUEUED         (retry دستی)
CANCELLED → QUEUED         (retry دستی)
```

هر گذار دیگر غیرقانونی است و `JobStateMachine` در backend (و به‌موازاتش ورکر در نهایی‌سازی) ردش می‌کند — این دوگانه‌ی اعتبارسنجی تضمین می‌کند حتی raceهای نادر هم سمت سرور گرفته شوند.

## اولویت و فرمول aging

اولویت چهار سطح دارد (LOW=1، NORMAL=2، HIGH=3، CRITICAL=4). claim بر اساس score زیر مرتب می‌شود:

```text
effective_score = priority_rank + LEAST(age_seconds / 3600, 1.0)
```

یعنی هر ساعت انتظار، حداکثر ۱ امتیاز به job اضافه می‌کند. نتیجه‌ی عملی: در صفِ شلوغِ پر از jobهای HIGH، یک jobِ NORMAL بعد از ~۱ ساعت عملاً هم‌رده‌ی HIGHها می‌شود و بعد از ~۲ ساعت هم‌رده‌ی CRITICAL. **گرسنگی (starvation) به‌طور ساختاری غیرممکن شده** — بدون آن‌که کسی ورکرها را دستی اولویت‌بندی کند. tie-break هم `created_at ASC` است.

## ریاضیات backoff

ورود به RETRYING، `available_at` را این‌طور جلو می‌برد:

```text
backoff = min(300, 2^retry_count × 5)   ثانیه
```

| retry_count | backoff |
|---|---|
| ۰ (اولین شکست) | ۵ ثانیه |
| ۱ | ۱۰ ثانیه |
| ۲ | ۲۰ ثانیه |
| ۳ | ۴۰ ثانیه |
| ۴ | ۸۰ ثانیه |
| ۵ | ۱۶۰ ثانیه |
| ۶ به بعد | ۳۰۰ ثانیه (سقف) |

این تضمین می‌کند خطای گذرا (قطعی لحظه‌ای شبکه، lockگذاری موقت) سریع دوباره امتحان شود ولی خرابی پایدار (مثلاً dependency خراب) در ۵ دقیقه‌ی cycle قفل نکند صف را. با تنظیم پیش‌فرض max_retries=3 مجموع زمان انتظار یک job حدود ۳۵ ثانیه است (۵+۱۰+۲۰) — قابل تحمل برای عملیات.

## سه لایه‌ی timeout

این بخش مهم‌ترین طراحی «قابل‌اتکا»ی TaskMesh است، چون job گیرکرده بدترین دشمن صف است:

1. **لایه‌ی thread در worker**: handler در thread جدا اجرا می‌شود و runtime پایتون بعد از `timeout_seconds` نتیجه را نمی‌پذیرد و job را نهایی می‌کند (TIMED_OUT/RETRYING طبق state machine). محدودیت صادقانه‌ی CPython: thread واقعاً kill نمی‌شود و بعد از مرگ پروسه می‌رود — ولی وضعیت job درست نهایی شده است.
2. **لایه‌ی lease در دیتابیس**: در لحظه‌ی claim، `lease_expires_at = now() + max(30, timeout_seconds)` ست می‌شود و worker هم‌زمان با heartbeat آن را تمدید می‌کند. اگر worker زنده باشد lease تازه می‌ماند؛ اگر نه، منقضی می‌شود.
3. **لایه‌ی sweeper در backend**: هر ۵ ثانیه:
   - `RUNNING` با lease منقضی: اگر `started_at + timeout + 60s` هم گذشته باشد → مسیر TIMED_OUT/RETRYING؛ وگرنه یعنی worker کرش کرده → RETRYING با ثبت attempt با outcome `ABANDONED`.
   - `RUNNING` با `cancel_requested` و lease منقضی + ۳۰ ثانیه grace → CANCELLED (اگر worker خودش هنوز فرصت تعامل نداشته باشد).
   - ورکر با heartbeat قدیمی‌تر از ۳×interval → OFFLINE.

سه لایه روی هم یعنی هیچ مسیر «job برای همیشه در RUNNING می‌ماند» وجود ندارد — حتی اگر پروسه‌ی worker با SIGKILL نابود شود. این سناریو عیناً در تست E2E تزریق و تأیید شده: kill ورکر وسط job → attempt 1 با ABANDONED → RETRYING → ورکر دوم claim کرد → attempt 2 با SUCCEEDED.

## جریان WebSocket

زنده بودن کنسول به این زنجیره تکیه دارد:

```text
worker: UPDATE/INSERT در PostgreSQL
   └─▶ pg_notify('taskmesh_events', '{"t":"job","id":"…","s":"RUNNING","p":42}')
         └─▶ backend: thread LISTEN (همیشه فعال)
               └─▶ enrich: id→job کامل، workerId، timestamp ISO-8601
                     └─▶ broadcast به همه‌ی sessionهای WS authenticate شده
                           └─▶ کلاینت: EventTicker / لاگ زنده / invalidation کش React Query
```

- endpoint: `GET /ws/v1/events?token=<JWT>` — فریم‌های JSON خالص، فقط push از سرور.
- کلاینت وب در قطعی، با backoff نمایی ۱ ثانیه تا سقف ۳۰ ثانیه (+jitter ±۲۰٪) دوباره وصل می‌شود و اگر ۳۰ ثانیه فریمی نیاید، اتصال را «stale» تشخیص داده و بازسازی می‌کند.
- رویدادها debounce ۴۰۰ms در invalidation دارند تا در burst (مثلاً پایان ۱۰۰ job) UI قفل نشود.

## مقیاس‌پذیری

- **افقی شدن workerها**: هر worker یک پروسه‌ی مستقل با UUID خودش است. چون claim اتمی است، تعداد worker فقط با اجرای پروسه‌های بیشتر بالا می‌رود — در compose با `--scale worker=4`، در bare-metal با اجرای چند پروسه (هر کدام `TASKMESH_CONTROL_PORT` متفاوت: 9100، 9101، …). محدودیت واقعی، توان write همان instance PostgreSQL است.
- **توزیع capability**: هر worker می‌تواند با `TASKMESH_CAPABILITIES` فقط زیرمجموعه‌ای از ۷ نوع job را claim کند (مثلاً ماشین‌های قوی فقط `cpu_benchmark` و `image_resize`).
- **نکته‌ی تک‌نمونه‌ای sweeper**: sweeper در فرایند backend اجرا می‌شود و مدل استقرار 0.1.0 **یک نمونه‌ی backend** است. اگر روزی چند نمونه‌ی backend پشت یک load balancer اجرا کنید، منطق sweeper باید هماهنگ/قفل شود (مثلاً leader election یا advisory lock) وگرنه واگذاری‌های BACKFILL هم‌زمان تداخل پیدا می‌کنند — نه به‌خاطر correctness (گذارها با state machine محافظت شده‌اند) بلکه به‌خاطر کارایی و لاگ تکراری. در نسخه‌ی فعلی این محدودیت آگاهانه پذیرفته شده و مستند است.

## موجودیت‌های دیتابیس

| جدول | نقش |
|---|---|
| `users` | کاربران با hash BCrypt و نقش ADMIN/OPERATOR/USER |
| `projects` | پروژه‌ها (owner + cascade حذف) |
| `workers` | ردیف هر ورکر: status، capabilityها، heartbeat، job جاری، control port |
| `jobs` | قلب سیستم: type، payload (JSONB)، status، اولویت، retry/timeout، lease، progress، idempotency key |
| `job_attempts` | تاریخچه‌ی هر تلاش: شماره، ورکر، outcome (SUCCEEDED/FAILED/TIMED_OUT/CANCELLED/ABANDONED) |
| `job_logs` | لاگ سطح job با level و metadata JSONB |
| `job_results` | نتیجه: inline (JSONB) یا file (مسیر نسبی + sha256 + حجم) |
| `audit_logs` | رخدادهای امنیتی/عملیاتی: login، ساخت job، cancel، retry با actor و نتیجه |

ایندکس‌های کلیدی: `jobs_claim_idx` (partial روی QUEUED/RETRYING) برای claim سریع، `jobs_lease_idx` (partial روی RUNNING) برای sweeper، و unique indexِ partial برای idempotency روی `(owner_id, idempotency_key)`.

## اگر عمیق‌تر می‌خواهید بروید

- SQL دقیق claim: [`../SPEC.md`](../SPEC.md) §7
- کاتالوگ کامل انواع job: [JOBS_FA.md](JOBS_FA.md)
- رفتار runtime ورکر: [WORKERS_FA.md](WORKERS_FA.md)
