<!-- Persian documentation — see ../README.md for English -->

# مرجع REST API و WebSocket

TaskMesh یک API JSON ساده و قابل پیش‌بینی دارد: همه‌چیز زیر `/api/v1`، احراز هویت با Bearer token، خطاها همیشه با یک شکل ثابت. این سند مرجع کامل endpointها + مثال‌های curl و پروتکل WebSocket است.

> در همه‌ی مثال‌ها فرض کرده‌ایم API روی `http://localhost:8080` اجراست و token در متغیر shell با نام `$TOKEN` است.

---

## احراز هویت

- **login**: `POST /api/v1/auth/login` با `{username, password}` → خروجی `token` (JWT با الگوریتم HS256 و عمر ۱۲ ساعت = 43200 ثانیه).
- همه‌ی endpointهای دیگر (به‌جز `/health` و خود login) هدر زیر را می‌خواهند:

```text
Authorization: Bearer <token>
```

- با انقضای token پاسخ 401 می‌گیرید و باید دوباره login کنید (نسخه‌ی refresh token وجود ندارد — سادگی عمدی).
- WebSocket از query param استفاده می‌کند: `ws://…/ws/v1/events?token=<JWT>`.

## جدول کامل endpointها

| متد | مسیر | نقش لازم | پارامترها / نکات |
|---|---|---|---|
| GET | `/api/v1/health` | عمومی | بدون هدر؛ `{status, db, version}` |
| POST | `/api/v1/auth/login` | عمومی | body: `{username, password}`؛ rate limit ۱۰/min |
| GET | `/api/v1/me` | هر کاربر | پروفایل کاربر جاری |
| GET | `/api/v1/users` | ADMIN | فهرست کاربران |
| POST | `/api/v1/users` | ADMIN | `{username, email, password, role}` |
| GET | `/api/v1/projects` | هر کاربر | `?page&size` — USER فقط پروژه‌های خودش |
| POST | `/api/v1/projects` | هر کاربر | `{name, description}` |
| GET | `/api/v1/projects/{id}` | owner / op+ | |
| PATCH | `/api/v1/projects/{id}` | owner | `{name?, description?}` |
| DELETE | `/api/v1/projects/{id}` | owner | cascade روی jobها |
| GET | `/api/v1/jobs` | هر کاربر | `?projectId&status&type&priority&page&size` — USER فقط jobهای خودش |
| POST | `/api/v1/jobs` | هر کاربر | `{projectId, type, priority, payload, maxRetries?, timeoutSeconds?, idempotencyKey?}`؛ header `Idempotency-Key` هم پذیرفته می‌شود؛ 201 جدید / 200 تکراری |
| GET | `/api/v1/jobs/{id}` | owner / op+ | جزئیات کامل + خلاصه‌ی worker |
| POST | `/api/v1/jobs/{id}/cancel` | owner / op+ | فوری برای QUEUED؛ تعاملی برای RUNNING |
| POST | `/api/v1/jobs/{id}/retry` | owner / op+ | برای FAILED / TIMED_OUT / CANCELLED |
| GET | `/api/v1/jobs/{id}/result` | owner / op+ | inline JSON یا بایت‌های فایل؛ `?download=true` و هدر `X-Job-Result-SHA256` |
| GET | `/api/v1/jobs/{id}/logs` | owner / op+ | `?level&limit` — جدید به قدیم |
| GET | `/api/v1/jobs/{id}/attempts` | owner / op+ | تاریخچه‌ی attemptها |
| GET | `/api/v1/workers` | OPERATOR, ADMIN | شامل فیلد محاسبه‌شده‌ی `stale` |
| GET | `/api/v1/workers/{id}` | OPERATOR, ADMIN | |
| GET | `/api/v1/logs` | OPERATOR, ADMIN | لاگ audit — `?actor&action&page&size` |
| GET | `/api/v1/metrics` | OPERATOR, ADMIN | شمارش‌ها به تفکیک status، عمق صف، وضعیت ورکرها، مدت‌زمان‌ها، throughput |
| GET | `/api/v1/job-types` | هر کاربر | کاتالوگ انواع job با schema و payload نمونه |

## شکل خطاها و صفحه‌بندی

**خطا** همیشه یک envelope ثابت است — کلاینت لازم نیست هر endpoint را جداگانه هندل کند:

```json
{ "error": { "code": "NOT_FOUND", "message": "job 0f3… not found" } }
```

کدهای رایج: `400 VALIDATION` · `401 UNAUTHORIZED` · `403 FORBIDDEN` · `404 NOT_FOUND` · `409 CONFLICT` · `429 RATE_LIMITED`.

**صفحه‌بندی**: `page` از صفر شروع می‌شود، `size` پیش‌فرض ۲۵ و حداکثر ۱۰۰:

```json
{ "items": [ … ], "page": 0, "size": 25, "total": 137 }
```

## مثال‌های کامل curl

### 1) login و ذخیره‌ی token

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user","password":"TaskMesh!User"}'
# {"token":"eyJ…","tokenType":"Bearer","expiresIn":43200,"user":{"id":"…","username":"user","role":"USER"}}

TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user","password":"TaskMesh!User"}' | jq -r .token)
```

### 2) ساخت پروژه

```bash
curl -s -X POST http://localhost:8080/api/v1/projects \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"daily-reports","description":"گزارش‌های شبانه"}'
# → 201 با آبجکت پروژه؛ id را نگه دارید
```

### 3) ثبت job (اینجا: یک hash_sha256)

```bash
curl -s -X POST http://localhost:8080/api/v1/jobs \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{
        "projectId": "'"$PROJECT_ID"'",
        "type": "hash_sha256",
        "priority": "NORMAL",
        "payload": {"text": "hello taskmesh"},
        "idempotencyKey": "demo-001"
      }'
# → 201 Created؛ فراخوانی دوباره با همان کلید → 200 با همان job
```

### 4) پیگیری وضعیت

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/jobs/$JOB_ID" | jq '{status, progress, workerId, lastError}'
# بعد از اجرا: {"status":"SUCCEEDED","progress":100,"workerId":"…","lastError":null}
```

### 5) گرفتن نتیجه

```bash
# نتیجه‌ی inline (مثل hash_sha256)
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/jobs/$JOB_ID/result
# {"sha256":"…","bytes":14,"source":"text"}

# نتیجه‌ی فایلی (مثل image_resize) + تأیید checksum
curl -s -D - -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/jobs/$JOB_ID/result?download=true" -o out.png
grep X-Job-Result-SHA256   # با sha256sum out.png مقایسه کنید
```

### 6) cancel و retry

```bash
# cancel — برای QUEUED فوری است، برای RUNNING تعاملی
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/jobs/$JOB_ID/cancel

# retry — برای jobهای پایانی FAILED/TIMED_OUT/CANCELLED
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/jobs/$JOB_ID/retry
```

## WebSocket

اتصال:

```text
ws://localhost:8080/ws/v1/events?token=<JWT>
```

فریم‌ها متنِ JSON هستند و فقط از سرور به کلاینت push می‌شوند:

```json
{"type":"hello","server":"taskmesh-0.1.0","timestamp":"…"}
{"type":"job.updated","jobId":"…","status":"RUNNING","progress":42,"workerId":"…","timestamp":"…"}
{"type":"job.log","jobId":"…","level":"INFO","message":"phase 2 done","timestamp":"…"}
{"type":"worker.updated","workerId":"…","status":"BUSY","currentJobId":"…","timestamp":"…"}
{"type":"queue.event","event":"job.enqueued","jobId":"…","timestamp":"…"}
```

نکته‌ها:

- token در query param می‌رود چون مرورگر اجازه‌ی هدر سفارشی در handshake WS نمی‌دهد.
- session بدون token یا با token نامعتبر پذیرفته نمی‌شود.
- کلاینت مرجع (کنسول وب) با قطعی، backoff نمایی ۱ تا ۳۰ ثانیه با jitter وصل می‌شود؛ اگر ۳۰ ثانیه فریم نیاید اتصال را stale می‌گیرد و بازسازی می‌کند.

## برای تست سریع کل مسیر

همین جریانِ بالا (login → پروژه → job → نتیجه، به‌علاوه مسیرهای خطا) در اسکریپت E2E خودکار شده:

```bash
python3 scripts/e2e_smoke.py --base-url http://localhost:8080
```

جزئیات مجوز هر نقش: [SECURITY_FA.md](SECURITY_FA.md) · انواع payload: [JOBS_FA.md](JOBS_FA.md).
