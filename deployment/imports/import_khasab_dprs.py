import os
#!/usr/bin/env python3
"""Import Khasab DPRs (Jan, then Feb, then March) into KHASAB-2026 project.

Resolves activity codes → activity UUIDs, supervisor usernames → user UUIDs,
maps manpower/equipment names, and POSTs DPRs one at a time (no bulk endpoint
visible yet; will batch in parallel if needed for performance).
"""
import json
import urllib.request
import urllib.error
import sys
import time
import zlib
import random as _random
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE = os.environ.get("BIPROS_API_BASE", "http://localhost:8080")
TOKEN = open(os.environ.get("BIPROS_TOKEN_FILE", os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/admin-token.txt")).read().strip()
PROJECT_ID = open(os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/project-id.txt").read().strip()
USER_IDS = json.load(open(os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/user-ids.json"))
ACTIVITY_IDS = json.load(open(os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/activity-ids.json"))
DPRS = json.load(open(os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/khasab-dpr-parsed.json"))
# Canonical unit per activity code (written by parse_khasab.py) so the DPR's unit matches the
# Activity's WorkActivity default_unit. Optional — fall back to normalising the row's own unit.
try:
    ACTIVITY_UNITS = json.load(open(os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/activity-units.json"))
except (OSError, ValueError):
    ACTIVITY_UNITS = {}

_UNIT_MAP = {
    "cu.m.": "cum", "Cu.m.": "cum", "Cum": "cum", "CUM": "cum",
    "sq.m.": "sqm", "Sqm": "sqm", "Sq.m": "sqm",
    "lin.m.": "m", "Lin.m.": "m", "Km": "km", "kg.": "kg", "Kg": "kg",
    "t.": "MT", "tonne": "MT", "Nos": "nos", "Nr.": "nos", "Nr": "nos",
    "nr.": "nos", "nr": "nos", "LS": "LS", "Month": "month",
}


def canonical_unit(d):
    """Unit for a DPR: the activity code's canonical unit, else the normalised row unit."""
    code = d.get("activity_code")
    if code in ACTIVITY_UNITS:
        return ACTIVITY_UNITS[code]
    raw = d.get("unit") or ""
    return _UNIT_MAP.get(raw.strip(), (raw or "nos").lower().strip())

# Per-activity pool of REAL (source>0) daily quantities, as whole numbers. Idle/no-output
# source days (~74% — source qty 0) draw a realistic whole-number quantity from their own
# activity's distribution so the DPR shows a natural number (e.g. 100, 230) instead of the
# old 0.01 placeholder. Falls back to the project-wide pool for activities with no real days.
_ACT_QTYS = {}
_GLOBAL_QTYS = []
for _d in DPRS:
    _q = float(_d.get("qty_executed") or 0)
    if _q > 0:
        _w = max(1, round(_q))
        _ACT_QTYS.setdefault(_d["activity_code"], []).append(_w)
        _GLOBAL_QTYS.append(_w)
if not _GLOBAL_QTYS:
    _GLOBAL_QTYS = [round(x) for x in (10, 25, 50, 75, 100, 150)]


def realistic_qty(d):
    """Whole-number Workdone Quantity for a DPR: the rounded source qty when the source
    recorded output, else a realistic whole number sampled (stably, per DPR) from this
    activity's real daily quantities."""
    src = float(d.get("qty_executed") or 0)
    if src > 0:
        return max(1, round(src))
    pool = _ACT_QTYS.get(d["activity_code"]) or _GLOBAL_QTYS
    seed = zlib.crc32(f"{d['date']}|{d['activity_code']}|{d['supervisor_username']}".encode())
    return _random.Random(seed).choice(pool)

# Username → supervisor display name (reverse of supervisor map in parser)
SUP_DISPLAY = {
    "ismaila": "Mohd Ismaila", "saiffuddin": "Md Saiffuddin",
    "illayaraja": "Illayaraja", "kbarman": "K. Barman",
    "vijaykumar": "VijayKumar", "parvaiz": "Parvaiz",
    "sanjar": "Sanjar Alam", "anirban": "Anirban Datta",
    "sohail": "Sohail", "manzar": "Manzar",
    "vpgupta": "V.P. Gupta", "akmishra": "A.K. Mishra",
}

# Activity code → activity name (from ACTIVITY_IDS keys; reuse as-is)


def http(method, path, body=None, timeout=30):
    req = urllib.request.Request(f"{BASE}{path}", method=method)
    req.add_header("Authorization", f"Bearer {TOKEN}")
    req.add_header("Content-Type", "application/json")
    data = json.dumps(body).encode() if body else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        try:
            err_body = json.loads(e.read())
        except Exception:
            err_body = {"error": str(e)}
        return e.code, err_body
    except Exception as e:
        return 0, {"error": str(e)}


# ---------------------------------------------------------------------------
# Role resolution: the DPR form's manpower/equipment column is a dropdown only
# (no free-text fallback). It pre-selects via roleId::variantId. The dropdown is
# populated from (a) the activity's PLANNED role assignments and (b) the global
# rate book (/v1/role-rates/{manpower,equipment}). So to make an imported resource
# show up as "chosen" on ANY activity, we resolve the Excel name to a (roleId,
# variantId) that the dropdown will offer — preferring the activity's planned variant
# (shows "(planned)"), then falling back to the global rate book. The free-text trade /
# equipmentType is still sent as a fallback (and for names absent from the catalog —
# run seed_resource_catalog.py first to guarantee every name has a rate-book variant).
# ---------------------------------------------------------------------------
import re as _re
import threading as _threading

_ROLE_CACHE = {}          # activity_uuid -> {"MANPOWER": {norm_name:(roleId,variantId)}, "EQUIPMENT": {...}}
_ROLE_CACHE_LOCK = _threading.Lock()
_RATE_BOOK = None         # {"MANPOWER": {norm_name:(roleId,variantId)}, "EQUIPMENT": {...}} — global
_RATE_BOOK_LOCK = _threading.Lock()

# Excel name (normalized) -> planned role name (normalized). Covers the renames where
# the source category differs from the seeded role name.
ALIAS = {
    "helper": "helper / handyman",
    "steel fixer": "rebar fixer",
    "dozer": "bulldozer d6",
    "grader": "motor grader",
    "concrete mixer": "truck-mounted concrete mixer",
    "crane": "50-ton mobile crane",
    "mobile crane": "50-ton mobile crane",
    "excavator": "20-ton hydraulic excavator",
    "back hoe": "20-ton hydraulic excavator",
    "wheel loader": "wheel loader 3cy",
}


def _norm(s):
    return " ".join(str(s or "").strip().lower().split())


def _role_maps(aid):
    """Return {"MANPOWER": {...}, "EQUIPMENT": {...}} of norm_name -> (roleId, variantId)
    for an activity's planned role assignments. Cached per activity (thread-safe)."""
    if aid in _ROLE_CACHE:
        return _ROLE_CACHE[aid]
    with _ROLE_CACHE_LOCK:
        if aid in _ROLE_CACHE:
            return _ROLE_CACHE[aid]
        maps = {"MANPOWER": {}, "EQUIPMENT": {}}
        code, resp = http("GET", f"/v1/projects/{PROJECT_ID}/activities/{aid}/role-assignments")
        rows = resp.get("data", []) if (code == 200 and isinstance(resp, dict)) else []
        for a in rows:
            rt = a.get("roleType")
            bucket = "MANPOWER" if rt in ("MANPOWER", "LABOR") else ("EQUIPMENT" if rt == "EQUIPMENT" else None)
            if not bucket:
                continue
            name = _norm(a.get("roleName"))
            rid, vid = a.get("roleId"), a.get("variantId")
            if name and rid and vid:
                maps[bucket][name] = (rid, vid)
        _ROLE_CACHE[aid] = maps
        return maps


def _rate_book():
    """Global rate book: norm_name -> (roleId, variantId) for every active manpower /
    equipment variant. This is what the DPR dropdown shows in its rate-book bucket, so a
    line resolved here pre-selects on any activity regardless of its plan. Cached once."""
    global _RATE_BOOK
    if _RATE_BOOK is not None:
        return _RATE_BOOK
    with _RATE_BOOK_LOCK:
        if _RATE_BOOK is not None:
            return _RATE_BOOK
        book = {"MANPOWER": {}, "EQUIPMENT": {}}
        for bucket, path in (("MANPOWER", "/v1/role-rates/manpower"),
                             ("EQUIPMENT", "/v1/role-rates/equipment")):
            code, resp = http("GET", path)
            for v in (resp.get("data", []) if (code == 200 and isinstance(resp, dict)) else []):
                name = _norm(v.get("roleName"))
                rid, vid = v.get("roleId"), v.get("id")
                if name and rid and vid and name not in book[bucket]:
                    book[bucket][name] = (rid, vid)
        _RATE_BOOK = book
        return _RATE_BOOK


def resolve_role(name, table):
    """Match an Excel resource name to a planned role's (roleId, variantId).
    Order: normalized exact -> alias -> a planned name that contains all words of the
    Excel name (directional, avoids 'baby roller' -> 'roller' false hits)."""
    n = _norm(name)
    if not n or not table:
        return None
    if n in table:
        return table[n]
    a = ALIAS.get(n)
    if a and a in table:
        return table[a]
    nwords = set(n.split())
    best = None
    for k, v in table.items():
        kwords = set(k.split())
        if nwords <= kwords:                 # planned name contains every word of the Excel name
            if best is None or len(kwords) < best[0]:
                best = (len(kwords), v)
    return best[1] if best else None


def resolve_dpr(d):
    """Build the JSON body for one DPR POST."""
    aid = ACTIVITY_IDS.get(d["activity_code"])
    sup_id = USER_IDS.get(d["supervisor_username"])
    if not aid or not sup_id:
        return None
    qty = realistic_qty(d)   # whole number (rounded source, or realistic per-activity fill)

    body = {
        "projectId": PROJECT_ID,
        "activityId": aid,
        "activityName": f"Khasab {d['activity_code']}",
        "reportDate": d["date"],
        "reportedByUserId": sup_id,
        "supervisorUserId": sup_id,
        "supervisorName": SUP_DISPLAY.get(d["supervisor_username"], d["supervisor_username"]),
        "qtyExecuted": qty,
        "unit": canonical_unit(d),
        "manpower": [],
        "equipment": [],
        "materials": [],
        "subContractors": [],
    }
    # location optional but useful
    if d.get("site"):
        body["landmark"] = d["site"][:100]
    if d.get("side"):
        body["side"] = d["side"][:5] if d["side"] in ("LHS", "RHS") else None

    maps = _role_maps(aid)        # activity-planned (preferred — shows "(planned)")
    book = _rate_book()           # global rate book (fallback — still a dropdown option)
    for m in d["manpower"]:
        row = {
            "trade": (m.get("role") or "")[:50],
            "nos": int(m.get("count", 1) or 1),
            "workingHours": float(m.get("hours", 0) or 0),
            "unitRate": float(m.get("rate", 0) or 0),
            "unitRateBasis": "HOUR" if m.get("hours") else "DAY",
        }
        hit = resolve_role(m.get("role"), maps["MANPOWER"]) or resolve_role(m.get("role"), book["MANPOWER"])
        if hit:
            row["roleId"], row["manpowerRoleRateId"] = hit
        body["manpower"].append(row)
    for e in d["equipment"]:
        row = {
            "equipmentType": (e.get("name") or "")[:80],
            "nos": int(e.get("count", 1) or 1),
            "workingHours": float(e.get("hours", 0) or 0),
            "unitRate": float(e.get("rate", 0) or 0),
            "unitRateBasis": "HOUR" if e.get("hours") else "DAY",
        }
        hit = resolve_role(e.get("name"), maps["EQUIPMENT"]) or resolve_role(e.get("name"), book["EQUIPMENT"])
        if hit:
            row["roleId"], row["equipmentRoleVariantId"] = hit
        body["equipment"].append(row)
    for m in d["material"]:
        body["materials"].append({
            "materialName": (m.get("desc") or "")[:120],
            "unit": m.get("unit") or "nos",
            "qtyConsumed": float(m.get("qty", 0) or 0),
            "unitRate": float(m.get("rate", 0) or 0),
        })
    for s in d["subcontractor"]:
        body["subContractors"].append({
            "subContractorName": (s.get("name") or "")[:100],
            "workDescription": (s.get("desc") or "")[:200],
            "unit": s.get("unit") or "nos",
            "qtyExecuted": float(s.get("qty", 0) or 0),
            "unitRate": float(s.get("rate", 0) or 0),
        })
    return body


def post_one(d):
    body = resolve_dpr(d)
    if body is None:
        return ("skip_unresolved", d.get("date"), d.get("activity_code"))
    code, resp = http("POST", f"/v1/projects/{PROJECT_ID}/dpr", body, timeout=20)
    if code in (200, 201):
        return ("ok", body["reportDate"], body["activityId"])
    err_raw = resp.get("error", {}) if isinstance(resp, dict) else {}
    if isinstance(err_raw, dict):
        msg = err_raw.get("message", str(err_raw))[:120]
        details = err_raw.get("details") or []
        code_str = err_raw.get("code", "")
    else:
        msg = str(err_raw)[:120]
        details = []
        code_str = ""
    if "ALREADY" in msg.upper() or code_str == "DPR_ALREADY_EXISTS_FOR_ACTIVITY":
        return ("dup", body["reportDate"], msg)
    detail_str = "; ".join(f"{dd.get('field')}={dd.get('reason')}" for dd in details[:5]) if details else ""
    return ("fail", body["reportDate"], f"{msg}|{detail_str}")


def import_month(month_prefix):
    subset = [d for d in DPRS if d["date"].startswith(month_prefix)]
    print(f"\n=== Importing {len(subset)} DPRs for {month_prefix} ===")
    t0 = time.time()
    counts = {"ok": 0, "fail": 0, "dup": 0, "skip_unresolved": 0}
    errors = []
    lock = _threading.Lock()
    # Parallelize BY DATE. Each DPR write fires an AFTER_COMMIT DBS recompute serialised
    # per (project, date) by a Postgres advisory lock. If we parallelized arbitrarily,
    # workers on the same date would queue on that lock (and, worse, contend) — the
    # observed ~1 op/sec wall. By giving each worker a whole DATE (its DPRs posted
    # sequentially) and running different dates in parallel, no two workers ever share a
    # date, so the advisory lock is never contended → near-linear speedup. Safe because
    # the import sends no boqItemNo (BOQ-sync is a no-op) and the activity-progress
    # listeners are AFTER_COMMIT (a race there can't roll back a DPR write).
    # Override worker count with BIPROS_DPR_IMPORT_WORKERS=N.
    _workers = int(os.environ.get("BIPROS_DPR_IMPORT_WORKERS", "12"))
    by_date = {}
    for d in subset:
        by_date.setdefault(d["date"], []).append(d)

    def post_date(items):
        local = {"ok": 0, "fail": 0, "dup": 0, "skip_unresolved": 0}
        local_errs = []
        for d in items:
            result, ts, info = post_one(d)
            local[result] += 1
            if result == "fail":
                local_errs.append((ts, info))
        with lock:
            for k in counts:
                counts[k] += local[k]
            errors.extend(local_errs[:max(0, 10 - len(errors))])
            done = sum(counts.values())
            print(f"  {done}/{len(subset)} ({time.time()-t0:.0f}s) — "
                  f"ok={counts['ok']} fail={counts['fail']} dup={counts['dup']} skip={counts['skip_unresolved']}")
        return local

    with ThreadPoolExecutor(max_workers=_workers) as ex:
        list(ex.map(post_date, by_date.values()))
    elapsed = time.time() - t0
    print(f"  DONE {month_prefix}: ok={counts['ok']} fail={counts['fail']} dup={counts['dup']} skip={counts['skip_unresolved']} ({elapsed:.0f}s)")
    if errors:
        print(f"  Sample errors:")
        for ts, msg in errors[:5]:
            print(f"    {ts}: {msg}")
    return counts


if __name__ == "__main__":
    month_arg = sys.argv[1] if len(sys.argv) > 1 else "2026-01"
    if month_arg == "all":
        for m in ("2026-01", "2026-02", "2026-03"):
            import_month(m)
    else:
        import_month(month_arg)
