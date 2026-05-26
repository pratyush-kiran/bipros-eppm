#!/usr/bin/env python3
"""Wipe all DPRs in the Khasab project and re-import them from the parsed source.

Use this to refresh DPRs on an ALREADY-populated DB (the plain import_khasab_dprs.py
only POSTs, so existing rows would come back as 'dup' and keep stale data). On a fresh
DB you don't need this — deploy.sh runs import_khasab_dprs.py directly.

Parallelism: BY DATE. Each DPR write fires an AFTER_COMMIT DBS recompute serialised per
(project,date) by a Postgres advisory lock; giving each worker a whole date (its rows
posted sequentially) and running dates in parallel means the lock is never contended →
near-linear speedup. Safe because the import sends no boqItemNo (BOQ-sync no-op) and the
activity-progress listeners are AFTER_COMMIT (a race can't roll back a DPR write).

Env: BIPROS_API_BASE, BIPROS_WORK_DIR, BIPROS_TOKEN_FILE (same as import_khasab_dprs.py),
BIPROS_DPR_IMPORT_WORKERS (default 12).
"""
import sys, os, json, time, threading, collections, urllib.request
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import import_khasab_dprs as imp

PID = imp.PROJECT_ID
WORKERS = int(os.environ.get("BIPROS_DPR_IMPORT_WORKERS", "12"))
_auth_lock = threading.Lock()
_TOKEN_FILE = os.environ.get("BIPROS_TOKEN_FILE",
                             os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/admin-token.txt")
_PSQL = os.environ.get("BIPROS_PSQL", "psql")
_PG_BASE = ["env", f"PGPASSWORD={os.environ.get('BIPROS_PG_PASS', 'bipros_dev')}", _PSQL,
            "-h", os.environ.get("BIPROS_PG_HOST", "127.0.0.1"), "-p", os.environ.get("BIPROS_PG_PORT", "5432"),
            "-U", os.environ.get("BIPROS_PG_USER", "bipros"), "-d", os.environ.get("BIPROS_PG_DB", "bipros"),
            "-A", "-t", "-c"]


def _sql(q):
    import subprocess
    out = subprocess.run(_PG_BASE + [q], capture_output=True, text=True, timeout=120)
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip() or out.stdout.strip())
    return out.stdout.strip()


def relogin():
    for attempt in range(5):
        try:
            req = urllib.request.Request(f"{imp.BASE}/v1/auth/login", method="POST")
            req.add_header("Content-Type", "application/json")
            body = json.dumps({"username": os.environ.get("BIPROS_ADMIN_USER", "admin"),
                               "password": os.environ.get("BIPROS_ADMIN_PASS", "admin123")}).encode()
            with urllib.request.urlopen(req, data=body, timeout=20) as r:
                tok = json.load(r)["data"]
            tok = tok.get("accessToken") or tok.get("token")
            if tok:
                imp.TOKEN = tok
                try: open(_TOKEN_FILE, "w").write(tok)
                except OSError: pass
                print(f"[auth] logged in @ {time.strftime('%H:%M:%S')}", flush=True)
                return
        except Exception as e:
            print(f"[auth] login attempt {attempt+1} failed: {e}", flush=True)
        time.sleep(2)
    raise SystemExit("[auth] could not log in")


# Wrap http so a 401 anywhere triggers one re-login + retry (long runs outlive a token).
_orig_http = imp.http
def http_retry(method, path, body=None, timeout=30):
    code, resp = _orig_http(method, path, body, timeout)
    if code == 401:
        with _auth_lock:
            relogin()
        code, resp = _orig_http(method, path, body, timeout)
    return code, resp
imp.http = http_retry


def enumerate_dprs():
    """(id, reportDate) for every DPR currently in the project."""
    out, before = [], None
    while True:
        q = f"/v1/projects/{PID}/dpr?from=2026-01-01&to=2026-03-31&days=50"
        if before: q += f"&before={before}"
        _, resp = imp.http("GET", q)
        page = resp.get("data") or {}
        out += [(it["id"], it.get("reportDate")) for it in (page.get("items") or [])]
        if page.get("hasMore") and page.get("nextCursor"):
            before = page["nextCursor"]
        else:
            break
    return out


def wipe():
    # Clear the BOQ link first. Deleting a BOQ-linked DPR fires the in-transaction
    # DprBoqSyncListener, whose BoqCalculator.recompute can overflow the numeric(9,6)
    # percent_complete / cost_variance_percent columns (409 DATA_INTEGRITY) — which would
    # make the DELETE fail and leave the row behind as a 'dup'. Null the link via SQL so
    # the delete-time BOQ-sync is a no-op. (link_dprs_to_boq.py re-links after reload.)
    try:
        _sql(f"UPDATE project.daily_progress_reports SET boq_item_id=NULL, boq_item_no=NULL "
             f"WHERE project_id='{PID}'")
        print("[wipe] cleared BOQ links (avoids delete-time recompute overflow)", flush=True)
    except Exception as e:
        print(f"[wipe] WARN could not clear BOQ links ({e}); continuing", flush=True)
    rows = enumerate_dprs()
    by_date = collections.defaultdict(list)
    for did, rdate in rows:
        by_date[rdate].append(did)
    print(f"[wipe] deleting {len(rows)} DPRs across {len(by_date)} dates ({WORKERS} workers) ...", flush=True)
    t0 = time.time(); done = [0]; failed = [0]; lock = threading.Lock()
    def del_one(i):
        # Retry until the row is actually gone — a silently-failed DELETE (timeout/500
        # under recompute load) would otherwise survive and the reload would skip it as a
        # 'dup', leaving stale data behind.
        for _ in range(4):
            c, _r = imp.http("DELETE", f"/v1/projects/{PID}/dpr/{i}")
            if c in (200, 204, 404):
                return True
            time.sleep(0.3)
        return False
    def del_date(ids):
        for i in ids:
            ok = del_one(i)
            with lock:
                done[0] += 1
                if not ok:
                    failed[0] += 1
                if done[0] % 200 == 0:
                    print(f"[wipe] {done[0]}/{len(rows)} ({time.time()-t0:.0f}s) failed={failed[0]}", flush=True)
    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        list(ex.map(del_date, by_date.values()))
    print(f"[wipe] DONE ({time.time()-t0:.0f}s) failed={failed[0]}", flush=True)
    # Safety net: if any deletes still failed, the reload would dup them — surface it so
    # the post-reload reconcile / qty check can catch any stragglers.
    if failed[0]:
        print(f"[wipe] WARNING {failed[0]} deletes did not confirm — reload may report dups", flush=True)


def reconcile():
    """Re-post any parsed record that didn't land (covers transient failures)."""
    present = set(); before = None
    while True:
        q = f"/v1/projects/{PID}/dpr?from=2026-01-01&to=2026-03-31&days=50"
        if before: q += f"&before={before}"
        _, resp = imp.http("GET", q); pg = resp.get("data") or {}
        for it in (pg.get("items") or []):
            present.add((it.get("reportDate"), it.get("activityId"), it.get("supervisorUserId")))
        if pg.get("hasMore") and pg.get("nextCursor"): before = pg["nextCursor"]
        else: break
    missing = []
    for d in imp.DPRS:
        aid = imp.ACTIVITY_IDS.get(d["activity_code"]); sid = imp.USER_IDS.get(d["supervisor_username"])
        if aid and sid and (d["date"], aid, sid) not in present:
            missing.append(d)
    print(f"[reconcile] {len(present)} present, {len(missing)} to re-post", flush=True)
    for d in missing:
        res, ts, info = imp.post_one(d)
        if res != "ok":
            print(f"[reconcile] {ts} {d['activity_code']} {d['supervisor_username']}: {res} {str(info)[:120]}", flush=True)
    return len(present), len(missing)


if __name__ == "__main__":
    relogin()
    wipe()
    relogin()
    print("[reload] importing all months ...", flush=True)
    for m in ("2026-01", "2026-02", "2026-03"):
        imp.import_month(m)
    relogin()
    present, missing = reconcile()
    print(f"[verify] DPRs now in project: {len(enumerate_dprs())}", flush=True)
    print("[reload] ALL DONE", flush=True)
