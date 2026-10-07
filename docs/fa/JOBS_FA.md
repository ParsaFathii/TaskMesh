<!-- Persian documentation — see ../README.md for English -->

# راهنمای Jobها — چرخه‌ی حیات، انواع، retry و cancel

هر چیز در TaskMesh حول job می‌چرخد. این سند چرخه‌ی حیات job را از لحظه‌ی ثبت تا نتیجه، کاتالوگ کامل ۷ نوع، و رفتار retry/timeout/cancel را — با محدودیت‌های واقعی — توضیح می‌دهد.

نمودار: [چرخه‌ی حیات job](../assets/) و [جریان retry](../assets/).

---

## چرخه‌ی حیات یک job

1. **ثبت** — `POST /api/v1/jobs` با `{projectId, type, priority, payload, …}`. backend payload را با کاتالوگ نوع اعتبارسنجی می‌کند، ردیف `QUEUED` می‌سازد و `pg_notify` می‌فرستد.
2. **صف‌شدن** — job در `jobs` می‌ماند تا یک worker آزاد آن را با `SELECT … FOR UPDATE SKIP LOCKED` claim کند. ترتیب claim بر اساس اولویت + aging است (زیر ببینید).
3. **اجرا** — status به `RUNNING` می‌رود، `lease_expires_at` ست می‌شود، worker handler را در thread اجرا می‌کند و progress (۰ تا ۱۰۰) و لاگ را در طول اجرا گزارش می‌دهد.
4. **نتیجه** — خروجی handler یا inline در `job_results` (اگر ≤ 32KB) یا فایل در دیتابیس مشترک `TASKMESH_STORAGE_DIR` با sha256 ذخیره می‌شود.
5. **پایان** — `SUCCEEDED`، یا مسیرهای ناموفق: `FAILED` / `RETRYING` / `TIMED_OUT` / `CANCELLED` که هر کدام پایین‌تر شرح داده شده‌اند.

state machine کامل گذارها در [ARCHITECTURE_FA.md](ARCHITECTURE_FA.md) آمده.

## ۷ نوع job — جدول کامل

پیش‌فرض‌های همه: payload باید دقیقاً هم‌شکل schema باشد (فیلد ناشناخته = خطا). اعتبارسنجی دو لایه است: backend با کاتالوگ `GET /api/v1/job-types`، و worker با مدل‌های strict Pydantic.

### `csv_analysis`

پروفایل‌کردن داده‌های CSV بدون آن‌که فایل را جایی upload کنید.

```json
// payload
{
  "csv": "name,age\nAli,34\nSara,29",   // تا 2MB
  "delimiter": ",",                       // اختیاری: "," یا ";" یا "\t"
  "hasHeader": true                       // اختیاری، پیش‌فرض true
}
// result — نوع هر ستون int / float / text است؛ parseErrors لیستی از {row, message}
{
  "rows": 2,
  "columns": [
    {"name":"name","type":"text","nonNull":2,"missing":0,"unique":2},
    {"name":"age","type":"int","nonNull":2,"missing":0,"unique":2,"min":29,"max":34,"mean":31.5}
  ],
  "delimiterUsed": ",",
  "parseErrors": []
}
```

### `json_transform`

تبدیل‌های عمومی روی documentهای JSON — برای pipelineهای ETL سبک.

```json
// payload
{
  "input": {"user":{"name":"Ali","age":34},"tags":["a","b"]},
  "operations": [
    {"op":"pick","paths":["user.name","tags"]},
    {"op":"rename","from":"user.name","to":"userName"},
    {"op":"flatten","separator":"."}
  ]
}
// result — خروجی تبدیل‌شده + تعداد عملیات اعمال‌شده
{"output": {"userName":"Ali","tags":["a","b"]}, "applied": 3, "operations": 3}
```

عملیات موجود: `pick` (نگه‌داشتن مسیرها)، `remove`، `rename` و `flatten` (تخت‌کردن آبجکت تودرتو با جداکننده).

### `image_resize`

تغییر اندازه‌ی عکس با Pillow (فیلتر LANCZOS). ورودی base64، خروجی **فایل**.

```json
// payload
{
  "imageBase64": "…",
  "width": 320,             // 1..10000
  "height": 240,            // 1..10000
  "maintainAspect": true,   // اختیاری، پیش‌فرض true
  "format": "png"           // اختیاری: "png" یا "jpeg"
}
// result — متادیتا؛ خود عکس به‌صورت FILE result ذخیره می‌شود
{"width": 320, "height": 240, "format": "png", "bytes": 98304, "sha256": "…"}
```

فایل در `results/<job_id>.bin` نوشته می‌شود و دانلودش از `GET /api/v1/jobs/{id}/result` با هدر `X-Job-Result-SHA256`.

### `hash_sha256`

محاسبه‌ی هش — دقیقاً یکی از دو فیلد `contentBase64` یا `text` باید بیاید (هر دو یا هیچ‌کدام = خطای validation).

```json
// payload
{"text": "hello taskmesh"}
// result
{"sha256": "…", "bytes": 14, "source": "text"}
```

### `text_statistics`

آمار متون — شمارش کلمات/خطوط، top words و زمان مطالعه.

```json
// payload
{"text": "…تا 2MB…", "caseSensitive": false}
// result
{
  "characters": 132, "charactersNoSpaces": 110, "words": 21, "uniqueWords": 17,
  "lines": 3, "paragraphs": 1, "avgWordLength": 4.7,
  "readingTimeSeconds": 1,
  "topWords": [{"word":"taskmesh","count":4}]
}
```

### `archive_inspection`

بازکردن و فهرست‌کردن آرشیو ZIP بدون استخراج کامل.

```json
// payload
{"archiveBase64": "…", "maxEntries": 500}   // تا 20MB
// result
{
  "format": "zip", "totalEntries": 42, "totalUncompressedBytes": 1048576,
  "compressionRatio": 0.62,
  "entries": [{"name":"a.txt","size":100,"compressedSize":80,"isDir":false,"modified":"…"}],
  "truncated": false
}
```

### `cpu_benchmark`

اندازه‌گیری توان CPU با دو workload واقعی: تولید اعداد prime یا ضرب ماتریسی.

```json
// payload
{"workload": "primes", "durationSeconds": 5}   // 1..30، پیش‌فرض 5
// result
{"workload": "primes", "operations": 123456, "durationMs": 5000, "opsPerSecond": 24691, "threads": 8}
```

## Idempotency (ارسال امنِ تکراری)

اگر کلاینت‌تان retry می‌فرستد و نمی‌دانید دفعه‌ی قبل به سرور رسیده یا نه، `idempotencyKey` بفرستید:

```bash
curl -X POST http://localhost:8080/api/v1/jobs \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-12345-confirm" \
  -d '{…}'
```

- بار اول: `201 Created` با job جدید.
- دفعات بعد (با همان owner و همان کلید): `200 OK` با **همان job قبلی** — job تازه ساخته نمی‌شود.
- کلید در یک unique index partial روی `(owner_id, idempotency_key)` قفل شده است، پس حتی زیر concurrency هم تکرار ممکن نیست.

کلید می‌تواند داخل body (`idempotencyKey`) یا به‌صورت header بیاید؛ هر دو معتبرند.

## اولویت و گرسنگی (starvation)

چهار سطح: `LOW / NORMAL / HIGH / CRITICAL`. ترتیب claim:

```text
score = priority_rank + LEAST(age_seconds/3600, 1.0)
```

پیامد عملی که باید بدانید:

- CRITICAL معمولاً در چند ثانیه‌ی اول claim می‌شود.
- jobهای LOW در صفِ شلوغ بعد از ~۱ ساعت هم‌رده‌ی NORMAL و بعد از ~۲ ساعت هم‌رده‌ی HIGH می‌شوند. یعنی «پایین بودن اولویت» یعنی «دیرتر»، نه «شاید هرگز».
- tie نهایی با `created_at` (قدیمی‌تر اول) شکسته می‌شود.

## Retry و backoff

وقتی handler خطا بدهد، دو حالت دارد:

- **خطای گذرا (transient)** یا exception غیرمنتظره → `RETRYING`؛ `retry_count` یکی زیاد می‌شود و `available_at = now() + min(300, 2^retry_count × 5)` ثانیه جلو می‌رود. تا وقتی `retry_count < max_retries` (پیش‌فرض ۳، حداکثر ۱۰) ادامه دارد؛ بعدش `FAILED` نهایی.
- **خطای validation** (payload اشتباه، فایل خراب، …) → **بدون retry** مستقیم `FAILED`. منطقش ساده است: اگر ورودی از اول غلط بوده، دوباره اجرا کردنش چیزی عوض نمی‌کند.

نمونه‌ی جدول backoff در [ARCHITECTURE_FA.md](ARCHITECTURE_FA.md) هست. retry دستی هم بعد از پایان نهایی ممکن است: `POST /api/v1/jobs/{id}/retry` برای `FAILED/TIMED_OUT/CANCELLED` → job دوباره `QUEUED` می‌شود و `retry_count` صفر می‌شود (خطای آخر برای debug می‌ماند).

## Timeout

هر job `timeoutSeconds` دارد (۵ تا ۳۶۰۰، پیش‌فرض ۱۲۰). عبور از timeout از سه لایه تضمین می‌شود (thread ورکر، lease، sweeper با حاشیه‌ی `timeout + 60s`). رفتار نتیجه مثل خطای گذرا است: تا وقتی retry مانده → `RETRYING`، و وقتی retryها تمام شد → `TIMED_OUT` نهایی. محدودیت صادقانه: thread پایتونی بعد از timeout درجا kill نمی‌شود (محدودیت CPython) ولی job درست نهایی می‌شود و thread یتیم با پروسه از بین می‌رود.

## Cancel — و محدودیت‌های واقعی‌اش

`POST /api/v1/jobs/{id}/cancel` (owner یا OPERATOR/ADMIN):

- **job در QUEUED/RETRYING**: فوراً `CANCELLED` می‌شود. ساده.
- **job در RUNNING**: flag `cancel_requested` ست می‌شود و cancel «تعاملی» می‌شود: worker بین فازهای اجرا و در پایان کار flag را چک می‌کند و خودش job را `CANCELLED` نهایی می‌کند. اگر worker پاسخ ندهد (مثلاً گیر کرده یا مرد)، sweeper بعد از lease منقضی + ۳۰ ثانیه grace کار را می‌بندد.

دو محدودیت که بهتر است بدانید تا غافلگیر نشوید:

1. cancel از یک handler در میانه‌ی یک عملیات CPU-bound طولانی (مثل `cpu_benchmark` با ۳۰ ثانیه) **تا رسیدن به نقطه‌ی چک بعدی** اعمال نمی‌شود — یعنی شاید چند ثانیه طول بکشد.
2. jobهای پایانی (`SUCCEEDED/FAILED/TIMED_OUT/CANCELLED`) قابل cancel نیستند و API خطای state می‌دهد؛ برای اجرای دوباره از `retry` استفاده کنید.

## کارهای عملی رایج

```bash
# فهرست jobهای RUNNING من
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/jobs?status=RUNNING&priority=HIGH&page=0&size=25"

# لاگ‌های WARN/ERROR یک job
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/jobs/$JOB_ID/logs?level=ERROR&limit=50"

# تاریخچه‌ی attemptها — برای دیدن اینکه job روی چند ماشین رفته
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/jobs/$JOB_ID/attempts"
```

فهرست کامل endpointها و مثال‌های بیشتر: [API_FA.md](API_FA.md).
