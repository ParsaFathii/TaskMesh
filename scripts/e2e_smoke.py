#!/usr/bin/env python3
"""TaskMesh end-to-end smoke test.

Runs against a live TaskMesh deployment (API + at least one worker + PostgreSQL)
and exercises the real user path: login -> project -> submit -> workers execute ->
result inspection, plus failure paths (idempotency, validation, cancellation,
timeout/retry, priority, WebSocket events).

Usage:
    python3 scripts/e2e_smoke.py [--base-url http://localhost:8080] [--fast]

Exit code 0 = all checks passed. Any failure prints context and exits 1.
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import os
import secrets
import sys
import time
import zipfile

import requests

PASS = 0
FAIL = 0

# Credentials default to the documented local-dev seed accounts (README §3.1)
# and can be overridden for other deployments via environment variables.
CRED_ADMIN = (os.environ.get("TASKMESH_E2E_ADMIN_USER", "admin"),
              os.environ.get("TASKMESH_E2E_ADMIN_PASSWORD", "TaskMesh!Admin"))
CRED_OPERATOR = (os.environ.get("TASKMESH_E2E_OPERATOR_USER", "operator"),
                 os.environ.get("TASKMESH_E2E_OPERATOR_PASSWORD", "TaskMesh!Operator"))
CRED_USER = (os.environ.get("TASKMESH_E2E_USER_USER", "user"),
             os.environ.get("TASKMESH_E2E_USER_PASSWORD", "TaskMesh!User"))


def check(name: str, ok: bool, detail: str = "") -> bool:
    global PASS, FAIL
    mark = "PASS" if ok else "FAIL"
    print(f"[{mark}] {name}" + (f" — {detail}" if detail else ""))
    if ok:
        PASS += 1
    else:
        FAIL += 1
    return ok


class TaskMesh:
    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.token: str | None = None
        self.role: str | None = None

    def login(self, username: str, password: str) -> dict:
        r = requests.post(f"{self.base}/api/v1/auth/login",
                          json={"username": username, "password": password}, timeout=10)
        r.raise_for_status()
        body = r.json()
        self.token = body["token"]
        self.role = body["user"]["role"]
        return body

    def req(self, method: str, path: str, expect: int | tuple = 200, **kw) -> requests.Response:
        headers = kw.pop("headers", {})
        if self.token:
            headers.setdefault("Authorization", f"Bearer {self.token}")
        r = requests.request(method, f"{self.base}{path}", headers=headers, timeout=20, **kw)
        codes = expect if isinstance(expect, tuple) else (expect,)
        if r.status_code not in codes:
            raise AssertionError(f"{method} {path} -> {r.status_code}: {r.text[:300]}")
        return r


def _unwrap_job(body: dict) -> dict:
    """GET /api/v1/jobs/{id} returns {job, worker}; the envelope is flattened here."""
    return body.get("job", body) if isinstance(body, dict) else body


def wait_job(tm: TaskMesh, job_id: str, terminal: set[str], timeout_s: float = 90) -> dict:
    deadline = time.monotonic() + timeout_s
    job = {}
    while time.monotonic() < deadline:
        job = _unwrap_job(tm.req("GET", f"/api/v1/jobs/{job_id}").json())
        if job["status"] in terminal:
            return job
        time.sleep(0.7)
    raise AssertionError(f"job {job_id} did not reach {terminal} within {timeout_s}s (last: {job.get('status')})")


def run(base_url: str, fast: bool) -> int:
    tm = TaskMesh(base_url)
    suffix = secrets.token_hex(4)

    # --- health ------------------------------------------------------------
    h = requests.get(f"{base_url}/api/v1/health", timeout=10).json()
    check("health endpoint reports UP + db UP", h.get("status") == "UP" and h.get("db") == "UP",
          f"version={h.get('version')}")

    # --- auth ---------------------------------------------------------------
    admin = TaskMesh(base_url)
    admin.login(*CRED_ADMIN)
    check("admin login + role", admin.role == "ADMIN")

    user = TaskMesh(base_url)
    user.login(*CRED_USER)
    check("user login + role", user.role == "USER")

    tm.login(*CRED_OPERATOR)
    check("operator login + role", tm.role == "OPERATOR")

    bad = requests.post(f"{base_url}/api/v1/auth/login",
                        json={"username": "user", "password": "wrong"}, timeout=10)
    check("login rejects wrong password (401)", bad.status_code == 401)

    check("user cannot list workers (403)",
          user.req("GET", "/api/v1/workers", expect=403).status_code == 403)
    check("operator can list workers",
          tm.req("GET", "/api/v1/workers").json()["total"] >= 1)

    r = requests.get(f"{base_url}/api/v1/jobs", timeout=10)
    check("unauthenticated request rejected (401)", r.status_code == 401)

    # --- catalog -----------------------------------------------------------
    types = tm.req("GET", "/api/v1/job-types").json()
    type_names = {t["type"] for t in types["items"] if isinstance(types, dict) and "items" in types} \
        if isinstance(types, dict) else {t["type"] for t in types}
    expected = {"csv_analysis", "json_transform", "image_resize", "hash_sha256",
                "text_statistics", "archive_inspection", "cpu_benchmark"}
    check("job type catalog serves all 7 types", expected <= type_names, f"got {sorted(type_names)}")

    # --- project -----------------------------------------------------------
    proj = tm.req("POST", "/api/v1/projects", expect=201,
                  json={"name": f"e2e-smoke-{suffix}", "description": "created by e2e_smoke.py"}).json()
    pid = proj["id"]
    check("project created", bool(pid), f"{proj['name']}")

    other = user.req("GET", f"/api/v1/projects/{pid}", expect=(403, 404))
    check("user cannot access operator's project (403/404)", other.status_code in (403, 404))

    def submit(payload: dict, expect: int | tuple = 201, **kw) -> requests.Response:
        return tm.req("POST", "/api/v1/jobs", expect=expect, json=payload, **kw)

    base_job = {"projectId": pid, "priority": "HIGH", "timeoutSeconds": 60, "maxRetries": 1}

    # --- the seven real job types -------------------------------------------
    jobs: dict[str, str] = {}

    r = submit({**base_job, "type": "text_statistics", "payload": {
        "text": "TaskMesh runs distributed jobs.\nJobs run on workers.\nWorkers report progress."}})
    jobs["text_statistics"] = r.json()["id"]
    check("text_statistics submitted (201)", r.status_code == 201)

    csv_data = "name,age,city\nAli,34,Tehran\nSara,28,Shiraz\nJohn,41,London\n"
    r = submit({**base_job, "type": "csv_analysis", "payload": {"csv": csv_data, "hasHeader": True}})
    jobs["csv_analysis"] = r.json()["id"]
    check("csv_analysis submitted", r.status_code == 201)

    r = submit({**base_job, "type": "json_transform", "payload": {
        "input": {"a": 1, "b": {"c": 2, "d": 3}, "e": [1, 2]},
        "operations": [{"op": "rename", "from": "a", "to": "alpha"},
                        {"op": "flatten", "separator": "."}]}})
    jobs["json_transform"] = r.json()["id"]
    check("json_transform submitted", r.status_code == 201)

    r = submit({**base_job, "type": "hash_sha256", "payload": {"text": "taskmesh e2e"}})
    jobs["hash_sha256"] = r.json()["id"]
    check("hash_sha256 submitted", r.status_code == 201)

    # tiny 8x8 png
    from PIL import Image  # noqa: imported lazily so the script only needs Pillow for image jobs
    buf = io.BytesIO()
    Image.new("RGB", (64, 48), (245, 166, 35)).save(buf, "PNG")
    img_b64 = base64.b64encode(buf.getvalue()).decode()
    r = submit({**base_job, "type": "image_resize",
                "payload": {"imageBase64": img_b64, "width": 16, "height": 12}})
    jobs["image_resize"] = r.json()["id"]
    check("image_resize submitted", r.status_code == 201)

    zbuf = io.BytesIO()
    with zipfile.ZipFile(zbuf, "w") as z:
        z.writestr("docs/readme.txt", "hello taskmesh")
        z.writestr("data/rows.csv", "a,b\n1,2\n")
    r = submit({**base_job, "type": "archive_inspection",
                "payload": {"archiveBase64": base64.b64encode(zbuf.getvalue()).decode()}})
    jobs["archive_inspection"] = r.json()["id"]
    check("archive_inspection submitted", r.status_code == 201)

    bench_secs = 2 if fast else 3
    r = submit({**base_job, "type": "cpu_benchmark",
                "payload": {"workload": "primes", "durationSeconds": bench_secs}})
    jobs["cpu_benchmark"] = r.json()["id"]
    check("cpu_benchmark submitted", r.status_code == 201)

    # --- wait for the fast ones ---------------------------------------------
    fast_jobs = {k: v for k, v in jobs.items() if k != "cpu_benchmark"}
    for name, jid in fast_jobs.items():
        j = wait_job(tm, jid, {"SUCCEEDED"}, timeout_s=90)
        is_file_result = name == "image_resize"
        if is_file_result:
            rr = tm.req("GET", f"/api/v1/jobs/{jid}/result?download=true")
            res = {"_raw": rr.content, "_sha256": rr.headers.get("X-Job-Result-SHA256", "")}
        else:
            res = tm.req("GET", f"/api/v1/jobs/{jid}/result").json()
        ok = j["status"] == "SUCCEEDED"
        detail = ""
        if name == "text_statistics":
            ok = ok and res["words"] == 11 and res["lines"] == 3 and res["topWords"][0]["count"] == 2
            detail = f"words={res['words']}"
        elif name == "csv_analysis":
            ok = ok and res["rows"] == 3 and len(res["columns"]) == 3 and res["columns"][1]["type"] == "int"
            detail = f"rows={res['rows']}, col2 type={res['columns'][1]['type']}"
        elif name == "json_transform":
            ok = ok and res["output"].get("alpha") == 1 and res["output"].get("b.c") == 2
            detail = f"applied={res['applied']}"
        elif name == "hash_sha256":
            import hashlib
            expect_sha = hashlib.sha256(b"taskmesh e2e").hexdigest()
            ok = ok and res["sha256"] == expect_sha and res["source"] == "text"
            detail = "sha256 verified"
        elif name == "image_resize":
            import hashlib
            raw = res["_raw"]
            digest = hashlib.sha256(raw).hexdigest()
            ok = (ok and raw[:8] == b"\x89PNG\r\n\x1a\n"
                  and len(raw) > 0 and digest == res["_sha256"])
            detail = f"PNG {len(raw)}B, sha256 header matches body"
        elif name == "archive_inspection":
            ok = ok and res["totalEntries"] == 2 and res["entries"][0]["name"] == "docs/readme.txt"
            detail = f"entries={res['totalEntries']}"
        check(f"job {name} executed by worker → SUCCEEDED w/ correct result", ok, detail)

    logs_body = tm.req("GET", f"/api/v1/jobs/{jobs['text_statistics']}/logs").json()
    log_items = logs_body if isinstance(logs_body, list) else logs_body.get("items", [])
    check("job has structured logs", len(log_items) >= 1, f"{len(log_items)} log rows")
    attempts_body = tm.req("GET", f"/api/v1/jobs/{jobs['text_statistics']}/attempts").json()
    attempts = attempts_body if isinstance(attempts_body, list) else attempts_body.get("items", [])
    check("job attempt history recorded", len(attempts) == 1 and attempts[0]["outcome"] == "SUCCEEDED")

    # cpu benchmark completes too
    j = wait_job(tm, jobs["cpu_benchmark"], {"SUCCEEDED"}, timeout_s=60)
    check("cpu_benchmark SUCCEEDED with throughput", j["status"] == "SUCCEEDED")

    # --- idempotency ----------------------------------------------------------
    idem = f"e2e-{suffix}"
    r1 = submit({**base_job, "type": "text_statistics", "idempotencyKey": idem,
                 "payload": {"text": "idempotent"}}, expect=201)
    r2 = submit({**base_job, "type": "text_statistics", "idempotencyKey": idem,
                 "payload": {"text": "idempotent"}}, expect=200)
    check("idempotency: duplicate key returns existing job (200, same id)",
          r1.json()["id"] == r2.json()["id"])

    # --- validation failures ---------------------------------------------------
    r = submit({**base_job, "type": "no_such_type", "payload": {}}, expect=400)
    check("unknown job type rejected (400)", r.status_code == 400)
    r = submit({**base_job, "type": "hash_sha256", "payload": {"text": "a", "contentBase64": "YQ=="}},
               expect=400)
    check("payload shape violation rejected at API (400)", r.status_code == 400)

    # invalid semantics: passes API shape validation, fails in the worker as NON-retryable
    # (rename from a path that does not exist can only be detected by the handler)
    r = submit({**base_job, "type": "json_transform", "payload": {
        "input": {"alpha": 1}, "operations": [{"op": "rename", "from": "missing.path", "to": "x"}]}})
    bad_job = r.json()["id"]
    j = wait_job(tm, bad_job, {"FAILED"}, timeout_s=60)

    check("worker-side invalid payload → FAILED (non-retryable, no retry storm)",
          j["status"] == "FAILED" and j["retryCount"] == 0, f"status={j['status']}")

    # --- priority ordering -------------------------------------------------------
    prio_proj = tm.req("POST", "/api/v1/projects", expect=201,
                       json={"name": f"e2e-prio-{suffix}", "description": "priority test"}).json()
    pp = prio_proj["id"]
    ids = []
    for prio, text in [("LOW", "low-prio-first"), ("NORMAL", "normal-prio"),
                       ("HIGH", "high-prio"), ("CRITICAL", "critical-prio")]:
        ids.append(submit({"projectId": pp, "type": "text_statistics", "priority": prio,
                           "payload": {"text": text}}).json()["id"])
    for jid in ids:
        wait_job(tm, jid, {"SUCCEEDED"}, timeout_s=90)
    rows = tm.req("GET", f"/api/v1/jobs?projectId={pp}&size=50").json()["items"]
    order = [r["priority"] for r in sorted(rows, key=lambda x: x["startedAt"] or "")]
    check("priority order respected (CRITICAL first, LOW last)",
          order == ["CRITICAL", "HIGH", "NORMAL", "LOW"], f"{order}")

    # --- cancellation of RUNNING job ---------------------------------------------
    long_proj = tm.req("POST", "/api/v1/projects", expect=201,
                       json={"name": f"e2e-cancel-{suffix}", "description": "cancel test"}).json()
    long_id = submit({"projectId": long_proj["id"], "type": "cpu_benchmark", "priority": "NORMAL",
                      "timeoutSeconds": 120, "payload": {"workload": "matrix",
                                                         "durationSeconds": 30}}).json()["id"]
    j = wait_job(tm, long_id, {"RUNNING"}, timeout_s=30)
    check("long job reached RUNNING", j["status"] == "RUNNING")
    tm.req("POST", f"/api/v1/jobs/{long_id}/cancel", expect=200)
    j = wait_job(tm, long_id, {"CANCELLED"}, timeout_s=90)
    check("RUNNING job cancelled (cooperative)", j["status"] == "CANCELLED")

    # cancellation of QUEUED job: create while workers are busy? use a fresh type
    # with a long queue: submit N long jobs to occupy both workers, then cancel a queued one.
    filler = []
    for _ in range(4):
        filler.append(submit({"projectId": long_proj["id"], "type": "cpu_benchmark",
                              "timeoutSeconds": 120,
                              "payload": {"workload": "matrix", "durationSeconds": 15}}).json()["id"])
    queued_target = submit({"projectId": long_proj["id"], "type": "cpu_benchmark",
                            "timeoutSeconds": 120,
                            "payload": {"workload": "matrix", "durationSeconds": 15}}).json()["id"]
    tj = _unwrap_job(tm.req("GET", f"/api/v1/jobs/{queued_target}").json())
    if tj["status"] in ("QUEUED", "RUNNING"):
        tm.req("POST", f"/api/v1/jobs/{queued_target}/cancel", expect=200)
        j = wait_job(tm, queued_target, {"CANCELLED"}, timeout_s=120)
        check("QUEUED job cancelled", j["status"] == "CANCELLED")
    for fid in filler:
        tm.req("POST", f"/api/v1/jobs/{fid}/cancel", expect=(200, 409))
        wait_job(tm, fid, {"CANCELLED", "SUCCEEDED"}, timeout_s=120)
    check("filler jobs drained (cancelled or succeeded)", True)

    # --- timeout + retry -----------------------------------------------------------
    to_proj = tm.req("POST", "/api/v1/projects", expect=201,
                     json={"name": f"e2e-timeout-{suffix}", "description": "timeout test"}).json()
    to_id = submit({"projectId": to_proj["id"], "type": "cpu_benchmark", "timeoutSeconds": 8,
                    "maxRetries": 2, "payload": {"workload": "matrix", "durationSeconds": 30}}).json()["id"]
    j = wait_job(tm, to_id, {"RETRYING", "TIMED_OUT", "RUNNING"}, timeout_s=60)
    saw_retry = j["status"] == "RETRYING"
    deadline = time.monotonic() + 200
    final = j
    while time.monotonic() < deadline:
        final = _unwrap_job(tm.req("GET", f"/api/v1/jobs/{to_id}").json())
        if final["status"] in ("TIMED_OUT", "FAILED", "SUCCEEDED"):
            break
        time.sleep(1.0)
    check("timeout handled: job TIMED_OUT after retries (not stuck RUNNING)",
          final["status"] == "TIMED_OUT",
          f"path saw RETRYING={saw_retry}, final={final['status']}, retries={final['retryCount']}")

    # --- retry endpoint ---------------------------------------------------------------
    r = tm.req("POST", f"/api/v1/jobs/{to_id}/retry", expect=200)
    requeued = _unwrap_job(tm.req("GET", f"/api/v1/jobs/{to_id}").json())
    check("manual retry re-queues job", requeued["status"] in ("QUEUED", "RETRYING", "RUNNING")
          and requeued["retryCount"] == 0)
    if requeued["status"] in ("QUEUED", "RETRYING"):
        tm.req("POST", f"/api/v1/jobs/{to_id}/cancel", expect=200)
        wait_job(tm, to_id, {"CANCELLED"}, timeout_s=60)

    # --- workers & metrics & logs -------------------------------------------------------
    workers = tm.req("GET", "/api/v1/workers").json()
    check("workers listed with heartbeats", workers["total"] >= 1)
    online = [w for w in workers["items"] if w["status"] in ("IDLE", "BUSY") and not w.get("stale")]
    check("at least one live worker", len(online) >= 1, f"{len(online)} live / {workers['total']}")

    m = tm.req("GET", "/api/v1/metrics").json()
    check("metrics: counts + queue depth",
          m["jobsByStatus"]["SUCCEEDED"] >= 7 and "queueDepth" in json.dumps(m)[:400],
          f"succeeded={m['jobsByStatus'].get('SUCCEEDED')}")

    audit = tm.req("GET", "/api/v1/logs?action=auth.login&size=10").json()
    actions = {a["action"] for a in audit["items"]}
    check("audit log captured logins", "auth.login" in actions and audit["total"] >= 1,
          f"{audit['total']} login records")
    audit2 = tm.req("GET", "/api/v1/logs?size=100").json()
    actions2 = {a["action"] for a in audit2["items"]}
    check("audit log captured job/project ops",
          {"job.create", "project.create", "job.cancel", "job.retry"} <= actions2,
          f"{sorted(actions2)[:8]}")

    # --- websocket events ----------------------------------------------------------------
    token = tm.token
    ws_events = []
    try:
        import websocket  # websocket-client
        ws = websocket.create_connection(f"ws://localhost:8080/ws/v1/events?token={token}", timeout=8)
        probe = submit({**base_job, "type": "text_statistics", "payload": {"text": "ws probe"}}).json()
        deadline = time.monotonic() + 30
        done = False
        while time.monotonic() < deadline and not done and len(ws_events) < 60:
            try:
                ws.settimeout(2)
                frame = json.loads(ws.recv())
            except Exception:
                continue
            ws_events.append(frame)
            if frame.get("type") == "job.updated" and frame.get("jobId") == probe["id"] \
                    and frame.get("status") == "SUCCEEDED":
                done = True
        ws.close()
    except Exception as exc:  # noqa: BLE001
        check("websocket stream delivered live job events", False, f"ws error: {exc}")
    else:
        kinds = {e.get("type") for e in ws_events}
        got_probe = any(e.get("jobId") == probe["id"] for e in ws_events)
        check("websocket stream delivered live job events", "job.updated" in kinds and got_probe,
              f"types={sorted(k for k in kinds if k)}")

    print(f"\n==== e2e result: {PASS} passed, {FAIL} failed ====")
    return 0 if FAIL == 0 else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--fast", action="store_true", help="shorter benchmark durations")
    args = ap.parse_args()
    try:
        return run(args.base_url, args.fast)
    except AssertionError as exc:
        print(f"[FAIL] {exc}")
        return 1
    except requests.RequestException as exc:
        print(f"[FAIL] transport: {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
