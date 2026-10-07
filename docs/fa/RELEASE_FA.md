<!-- Persian documentation — see ../README.md for English -->

# فرایند انتشار (Release) TaskMesh

انتشار TaskMesh عمداً یک دکمه‌ی «publish» دستی ندارد: همه‌چیز از روی **تگ git** شروع و توسط GitHub Actions تمام می‌شود. این سند رویداد انتشار، مراحل pipeline، فهرست artifactها و نحوه‌ی تأیید checksum را توضیح می‌دهد.

نمودار: [خط لوله‌ی release](../assets/).

---

## رویداد انتشار

```bash
git tag v0.1.0
git push origin v0.1.0
```

هر push تگ با الگوی `v*.*.*` دو workflow را فعال می‌کند:

- **`release`** — اعتبارسنجی کامل، ساخت artifactها، SBOM، checksumها و انتشار GitHub Release.
- **`docker`** — ساخت و push سه تصویر container به ghcr.io (همین تگ).

نسخه‌گذاری **semver** است (`MAJOR.MINOR.PATCH`) و پیش از هر چیز، workflow یک **بررسی سازگاری نسخه** انجام می‌دهد: عدد تگ باید با `backend/pom.xml`، `worker/pyproject.toml` و `web/package.json` یکی باشد، وگرنه انتشار همان‌جا می‌ایستد. یعنی «تگ بزن و یادت بره نسخه‌ها را آپدیت کنی» ممکن نیست.

## مراحل release workflow

### ۱) Validate — اعتبارسنجی (build + تست)

با یک PostgreSQL 16 به‌عنوان service، دقیقاً همان چیزی که CI می‌کند ولی با استاندارد انتشار:

- backend: `mvn clean verify` روی JDK 21 (unit + integration)
- worker: `ruff` + `python -m pytest` + `python -m build` (wheel و sdist)
- web: `npm ci` + lint + typecheck + test + build تولیدی
- android: `./gradlew testDebugUnitTest` + `assembleRelease`

همه‌ی artifactها به‌عنوان `release-artifacts` آپلود می‌شوند.

### ۲) SBOM — فهرست اجزای نرم‌افزاری

برای هر کامپوننت یک SBOM با فرمت **CycloneDX** ساخته می‌شود:

| فایل | منبع |
|---|---|
| `sbom-backend.json` | cyclonedx-maven-plugin (aggregate) |
| `sbom-worker.json` | `cyclonedx-py` از requirements فریز‌شده |
| `sbom-web.json` | `cyclonedx-npm` از `package-lock.json` |

### ۳) Release — checksum و انتشار

- همه‌ی artifactها و SBOMها جمع می‌شوند و `SHA256SUMS` تولید و بلافاصله با `sha256sum -c` self-verify می‌شود.
- release notes از تغییرات واقعی همان نسخه ساخته می‌شود (متن template در workflow، شامل محدودیت‌های شناخته‌شده مثل APK unsigned).
- با `gh release create` یک GitHub Release با همه‌ی فایل‌ها منتشر می‌شود و در پایان فهرست assetها دوباره خوانده و تأیید می‌شود.

### ۴) تصاویر Docker (workflow جدا، هم‌زمان)

`docker.yml` روی همان تگ، سه تصویر چندمرحله‌ای **non-root** را روی ghcr.io می‌گذارد:

- `ghcr.io/parsafathii/taskmesh-backend:<version>`
- `ghcr.io/parsafathii/taskmesh-worker:<version>`
- `ghcr.io/parsafathii/taskmesh-web:<version>`

تگ‌های اضافه: `MAJOR.MINOR` و `latest` (فقط برای تگ‌های نسخه)؛ pushهای main برچسب rolling `dev` می‌گیرند. بعد از push، digest هر تگ با `docker buildx imagetools inspect` تأیید می‌شود.

## فهرست artifactهای هر نسخه

| Artifact | توضیح |
|---|---|
| `taskmesh-backend-<v>.jar` | Spring Boot fat JAR — با `java -jar` اجرا می‌شود (JDK 21) |
| `taskmesh_worker-<v>-py3-none-any.whl` | پکیج worker — `pip install` کنید |
| `taskmesh_worker-<v>.tar.gz` | همان پکیج به‌صورت sdist |
| `taskmesh-web-<v>.zip` | build تولیدی SPA — با nginx سرو شود (کانفیگ در `web/nginx.conf`) |
| `taskmesh-android-<v>-unsigned.apk` | اپ اندروید — **امضا نشده** |
| `sbom-backend.json` / `sbom-worker.json` / `sbom-web.json` | SBOMهای CycloneDX |
| `SHA256SUMS` | checksum همه‌ی فایل‌های بالا |

## تأیید checksum بعد از دانلود

```bash
# دانلود فایل‌های release و SHA256SUMS از صفحه‌ی GitHub Release، سپس:
sha256sum -c SHA256SUMS
# taskmesh-backend-0.1.0.jar: OK
# taskmesh_worker-0.1.0-py3-none-any.whl: OK
# …
```

همچنین هر نتیجه‌ی فایلی job خودش هدر `X-Job-Result-SHA256` دارد — همان منطق، دو سطح پایین‌تر.

## محدودیت APK اندروید: unsigned

APK release TaskMesh **امضا نمی‌شود** — سیاست پروژه این است که هیچ keystore و کلید امضایی در مخزن نیست (و نباشد). برای توزیع، خودتان با keystore امضا کنید:

```bash
apksigner sign --ks your.keystore \
  taskmesh-android-0.1.0-unsigned.apk
```

این یعنی پیش از نصب باید تصمیم اعتماد (source و checksum) را خودتان بگیرید — که با `SHA256SUMS` بالای همین سند شروع می‌شود.

## نصب از artifactهای release

اگر نمی‌خواهید از Docker استفاده کنید، هر artifact با روش استاندارد خودش نصب می‌شود:

```bash
# backend — فقط JDK 21 لازم است
java -jar taskmesh-backend-0.1.0.jar

# worker — هر محیط پایتونی 3.12
pip install taskmesh_worker-0.1.0-py3-none-any.whl
export TASKMESH_DATABASE_URL=postgresql://… taskmesh-worker

# web — باندل استاتیک را باز کنید و با nginx سرو کنید
unzip taskmesh-web-0.1.0.zip -d /var/www/taskmesh
# کانفیگ مرجع: web/nginx.conf در مخزن (مسیرهای /api و /ws را به backend پروکسی می‌کند)
```

نکته‌ی مهم: `TASKMESH_STORAGE_DIR` بین backend و workerها باید روی یک فضای مشترک (volume/NFS/مسیر mount شده) اشاره کند، وگرنه نتایج فایلی از API قابل خواندن نیستند. برای کل استک، همیشه `docker compose -f deploy/compose/docker-compose.yml up -d` ساده‌ترین مسیر است.

## CHANGELOG و نسخه‌گذاری

- هر نسخه در `CHANGELOG.md` ریشه‌ی مخزن، با فرمت نسخه‌دار (Keep a Changelog-style) ثبت می‌شود و فقط تغییرات واقعی می‌نویسیم — تاریخچه‌ی ساختگی نداریم.
- نسخه‌ی 0.1.0 (اولین انتشار) شامل: backend جاوا با صف Postgres، runtime ورکر پایتون با ۷ handler، کنسول وب React/Vite، اپ اندروید Kotlin/Compose، احراز هویت JWT+BCrypt با سه نقش، retry/backoff/timeout/cancel/idempotency و مانیتورینگ زنده‌ی WebSocket.
- همه‌ی کامپوننت‌ها یک شماره‌ی نسخه دارند و همان تگ را منعکس می‌کنند: `/api/v1/health` در backend، `GET /health` ورکر، `package.json` وب، `versionName` اندروید و labelهای تصاویر Docker.
- **semver** را جدی بگیریم: breaking (مثلاً تغییر شکل payloadها یا رفتار endpoint) → MAJOR، قابلیت جدید (نوع job تازه) → MINOR، رفع باگ → PATCH.

## چک‌لیست قبل از زدن تگ

1. همه‌ی تست‌ها سبز (به‌ویژه یک اجرای تازه‌ی `scripts/e2e_smoke.py`).
2. نسخه‌ها در `pom.xml` / `pyproject.toml` / `package.json` (و `build.gradle.kts`) با هم و با تگِ در نظر گرفته‌شده یکی باشند.
3. `CHANGELOG.md` برای نسخه‌ی جدید نوشته شده باشد.
4. مستندات (انگلیسی و فارسی) اگر رفتاری تغییر کرده، در همان دنباله آپدیت شده باشند.

بعد از انتشار، تگ و Release را با هم ببینید؛ pipeline از آنجا به بعد خودش کار را تمام می‌کند و خطا می‌دهد اگر چیزی جا افتاده باشد.
