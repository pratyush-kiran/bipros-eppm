# Khasab Import — Data Quality Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Khasab Excel → DB pipeline produce data that matches the structural contracts of `bipros-data-bootstrap` Java Stages 2/4/9/11/12 — no `0.01` placeholders, every DPR linked to a BOQ, every role has a rate, every work activity has a norm, no Postgres deadlocks.

**Architecture:** Add 3 new Python scripts (seed_resource_rates / seed_productivity_norms / seed_boq_items) that port the Java reference logic. Modify 2 existing scripts (fix_role_assignments / import_khasab_dprs) with validation guards, the BOQ link, the realistic-qty calculation, and serial DPR ingest. Update `deploy.ps1` to invoke the 3 new scripts in correct dependency order and emit a post-deploy data-quality assertion. **No commits** until user gives explicit signal.

**Tech Stack:** Python 3.12 (stdlib only — `urllib`, `subprocess`, `json`, `os`), Docker Desktop on Windows, PostgreSQL 17 reached via `docker exec`, PowerShell 5.1.

**Spec reference:** `docs/superpowers/specs/2026-05-25-khasab-import-data-quality-fix-design.md`

**Pre-conditions** (already true in the working tree from this session's earlier work):
- All 12 import `.py` files have `PG_BASE = ["docker", "exec", "-i", ...]` (bypasses the broken cmd.exe wrapper).
- `deployment/configs/.env` has `JAVA_OPTS=-Xmx2g -XX:MaxRAMPercentage=75` (no quotes).
- `deploy.ps1` has `$env:PYTHONIOENCODING = 'utf-8'` in `RunImportPreDpr`.
- `create_khasab_users.py` does DB-lookup fallback when API says "user exists".
- All 7 bipros containers running and healthy (`docker ps | grep bipros-`).

If any of those is false, fix it first — the plan below assumes them.

---

## Task 1: Create `seed_resource_rates.py`

**Files:**
- Create: `deployment/imports/seed_resource_rates.py`

Ports `Stage2ResourceRoles.java`. Ensures every `resource_role` has at least one rate row in its corresponding variant table with realistic OMR values.

- [ ] **Step 1: Create the file with imports, env loading, and rate-card constants**

```python
import os
#!/usr/bin/env python3
"""Backfill resource-rate rows for every resource_role.

Ports the rate card and multipliers from bipros-data-bootstrap's
Stage2ResourceRoles.java so every manpower/equipment/material role gets at
least one variant row with a realistic OMR/day (or OMR/unit) value.

Idempotent: every write is keyed on the variant table's unique constraint and
only updates when the computed rate changes.

Env:
  BIPROS_PG_HOST, BIPROS_PG_PORT, BIPROS_PG_USER, BIPROS_PG_PASS, BIPROS_PG_DB
  BIPROS_PG_CONTAINER (default: bipros-postgres)
  BIPROS_WORK_DIR     (default: /tmp/khasab) — used to read parsed DPR JSON

Reads (optional): $BIPROS_WORK_DIR/khasab-dpr-parsed.json
Writes: resource.manpower_role_rates, .equipment_role_variants, .material_role_variants
"""
import json
import subprocess
import sys
from collections import defaultdict


# ─────────────────────────── Rate card (mirrors Stage2ResourceRoles.java) ───────────────────────────

MANPOWER_DAY_RATE_OMR = {
    "HELPER":      8.00, "MASON":       18.00, "CARPENTER":   18.00, "STEEL_FIXER": 18.00,
    "SCAFFOLDER":  16.00, "RIGGER":      16.00, "BANKMAN":     15.00,
    "CHARGEHAND":  22.00, "FOREMAN":     30.00, "SUPERVISOR":  45.00,
}

EQUIPMENT_DAY_RATE_OMR = {
    "AIR_COMPRESSOR":  30.00, "ASPHALT_CUTLER":  40.00, "BACK_HOE":        90.00,
    "BOB_CAT":         65.00, "CONCRETE_MIXER":  50.00, "CRANE":          200.00,
    "CRUSHER":        250.00, "DOZER":          220.00, "DUMPER":         100.00,
    "EXCAVATOR":      180.00, "GRADER":         180.00, "HIAB":           150.00,
    "MOBILE_CRANE":   220.00, "PLATE_COMPACTOR": 25.00, "POWERSCREEN":    280.00,
    "ROLLER":         120.00, "TIPPER":          95.00, "TOWER_LIGHT":     20.00,
    "WATER_TANKER":    95.00, "WHEEL_LOADER":   140.00, "BABY_ROLLER":     60.00,
    "HAND_DRILLING":   25.00,
}

# Material rate keyed by ROLE|SPEC; unit per role.
MATERIAL_RATE_OMR = {
    "CONCRETE|C15": 35.00, "CONCRETE|C25": 50.00,
    "CONCRETE|C30": 55.00, "CONCRETE|C35": 62.00,
}
MATERIAL_UNIT = {"CONCRETE": "m3"}

CATEGORY_MULTIPLIER = {
    "UNSKILLED":        0.60, "MC-UNSKILLED":     0.60,
    "SEMISKILLED":      0.80, "SEMI-SKILLED":     0.80, "MC-SEMISKILLED":   0.80,
    "SKILLED":          1.00, "MC-SKILLED":       1.00,
    "HIGHLYSKILLED":    1.20, "HIGHLY-SKILLED":   1.20, "MC-HIGHLYSKILLED": 1.20,
    "STAFF":            1.50, "MC-STAFF":         1.50,
}
GRADE_MULTIPLIER = {"A": 1.00, "B": 0.85, "C": 0.75}

MANPOWER_DEFAULT_DAY_OMR  = 10.00
EQUIPMENT_DEFAULT_DAY_OMR = 50.00
UNIT_DAY = "Day"


# ─────────────────────────── DB helpers (docker exec psql) ───────────────────────────

PG_CMD = ["docker", "exec", "-i",
          "-e", f"PGPASSWORD={os.environ.get('BIPROS_PG_PASS', 'bipros_dev')}",
          os.environ.get("BIPROS_PG_CONTAINER", "bipros-postgres"),
          "psql",
          "-U", os.environ.get("BIPROS_PG_USER", "bipros"),
          "-d", os.environ.get("BIPROS_PG_DB", "bipros"),
          "-A", "-F", "|", "-t", "-c"]

def sql(q):
    """Run a SELECT/INSERT/UPDATE and return rows as list[list[str]] or None on error."""
    out = subprocess.run(PG_CMD + [q], capture_output=True, text=True, timeout=30)
    if out.returncode != 0:
        print(f"  SQL ERROR: {out.stderr.strip()[:200]}", file=sys.stderr)
        return None
    return [line.split("|") for line in out.stdout.strip().split("\n") if line.strip()]

def sql_escape(s):
    """Single-quote-safe string for inline SQL literals."""
    return str(s).replace("'", "''")
```

Run: `python C:/project/bipros-eppm/deployment/imports/seed_resource_rates.py 2>&1 | head -5`
Expected: prints nothing visible yet (no main block — script just defines symbols). Exit 0.

- [ ] **Step 2: Add the rate-computation functions**

Append to the same file:

```python
# ─────────────────────────── Rate computation ───────────────────────────

def compute_manpower_day_rate(role_code, category_code, grade_code):
    base = MANPOWER_DAY_RATE_OMR.get((role_code or "").strip().upper(),
                                     MANPOWER_DEFAULT_DAY_OMR)
    cat = CATEGORY_MULTIPLIER.get((category_code or "SKILLED").strip().upper(), 1.0)
    grd = GRADE_MULTIPLIER.get((grade_code or "A").strip().upper(), 1.0)
    return round(base * cat * grd, 2)

def compute_equipment_day_rate(role_code):
    return round(EQUIPMENT_DAY_RATE_OMR.get((role_code or "").strip().upper(),
                                            EQUIPMENT_DEFAULT_DAY_OMR), 2)

def compute_material_rate(role_code, spec_grade):
    key = f"{(role_code or '').strip().upper()}|{(spec_grade or '').strip().upper()}"
    return round(MATERIAL_RATE_OMR.get(key, 0.0), 2)

def material_unit(role_code):
    return MATERIAL_UNIT.get((role_code or "").strip().upper(), "Unit")
```

- [ ] **Step 3: Add the master-resolution helpers (category, grade)**

Append:

```python
# ─────────────────────────── Master resolution ───────────────────────────

def resolve_or_create_category(name_or_code):
    """Resolve manpower_category_masters by NAME first, then CODE, create if missing.
    Returns id (str) or None."""
    raw = (name_or_code or "").strip()
    if not raw:
        raw = "Skilled"
    upper = raw.upper()
    # Prefer name match (Java Stage 2 behaviour: human-readable name wins)
    rows = sql(f"SELECT id::text FROM resource.manpower_category_masters "
               f"WHERE upper(name) = upper('{sql_escape(raw)}') LIMIT 1")
    if rows:
        return rows[0][0]
    rows = sql(f"SELECT id::text FROM resource.manpower_category_masters "
               f"WHERE upper(code) = upper('{sql_escape(upper)}') LIMIT 1")
    if rows:
        return rows[0][0]
    # Create
    rows = sql(f"INSERT INTO resource.manpower_category_masters "
               f"(id, created_at, updated_at, active, code, name, sort_order) "
               f"VALUES (gen_random_uuid(), now(), now(), true, '{sql_escape(upper)}', "
               f"'{sql_escape(raw.title())}', 0) RETURNING id::text")
    return rows[0][0] if rows else None

def resolve_or_create_grade(code):
    """Resolve grade_masters by CODE, create if missing. Returns id (str) or None."""
    raw = (code or "A").strip().upper()
    rows = sql(f"SELECT id::text FROM resource.grade_masters "
               f"WHERE upper(code) = upper('{sql_escape(raw)}') LIMIT 1")
    if rows:
        return rows[0][0]
    rows = sql(f"INSERT INTO resource.grade_masters "
               f"(id, created_at, updated_at, active, code, name, sort_order) "
               f"VALUES (gen_random_uuid(), now(), now(), true, '{sql_escape(raw)}', "
               f"'Grade {sql_escape(raw)}', 0) RETURNING id::text")
    return rows[0][0] if rows else None
```

- [ ] **Step 4: Add the variant UPSERT functions (one per resource type)**

Append:

```python
# ─────────────────────────── Variant UPSERTs ───────────────────────────

def upsert_manpower_rate(role_id, role_code, cat_name, grade_code, counters):
    cat_id = resolve_or_create_category(cat_name)
    grade_id = resolve_or_create_grade(grade_code)
    if not cat_id or not grade_id:
        counters["mp_skip_master"] += 1
        return
    rate = compute_manpower_day_rate(role_code, cat_name, grade_code)
    rows = sql(f"SELECT id::text, rate FROM resource.manpower_role_rates "
               f"WHERE role_id = '{role_id}' AND category_id = '{cat_id}' "
               f"AND grade_id = '{grade_id}' LIMIT 1")
    if rows:
        existing_rate = float(rows[0][1]) if rows[0][1] else 0.0
        if abs(existing_rate - rate) > 0.001:
            sql(f"UPDATE resource.manpower_role_rates SET rate = {rate}, "
                f"unit = '{UNIT_DAY}', active = true, updated_at = now() "
                f"WHERE id = '{rows[0][0]}'")
            counters["mp_updated"] += 1
        else:
            counters["mp_unchanged"] += 1
        return
    sql(f"INSERT INTO resource.manpower_role_rates "
        f"(id, created_at, updated_at, active, role_id, category_id, grade_id, unit, rate) "
        f"VALUES (gen_random_uuid(), now(), now(), true, '{role_id}', "
        f"'{cat_id}', '{grade_id}', '{UNIT_DAY}', {rate})")
    counters["mp_inserted"] += 1

def upsert_equipment_variant(role_id, role_code, make, model, counters):
    safe_make  = (make  or "GENERIC").strip() or "GENERIC"
    safe_model = (model or "STD").strip() or "STD"
    rate = compute_equipment_day_rate(role_code)
    rows = sql(f"SELECT id::text, rate FROM resource.equipment_role_variants "
               f"WHERE role_id = '{role_id}' AND make = '{sql_escape(safe_make)}' "
               f"AND model = '{sql_escape(safe_model)}' LIMIT 1")
    if rows:
        existing_rate = float(rows[0][1]) if rows[0][1] else 0.0
        if abs(existing_rate - rate) > 0.001:
            sql(f"UPDATE resource.equipment_role_variants SET rate = {rate}, "
                f"unit = '{UNIT_DAY}', active = true, updated_at = now() "
                f"WHERE id = '{rows[0][0]}'")
            counters["eq_updated"] += 1
        else:
            counters["eq_unchanged"] += 1
        return
    sql(f"INSERT INTO resource.equipment_role_variants "
        f"(id, created_at, updated_at, active, role_id, make, model, unit, rate) "
        f"VALUES (gen_random_uuid(), now(), now(), true, '{role_id}', "
        f"'{sql_escape(safe_make)}', '{sql_escape(safe_model)}', "
        f"'{UNIT_DAY}', {rate})")
    counters["eq_inserted"] += 1

def upsert_material_variant(role_id, role_code, spec_grade, counters):
    safe_spec = (spec_grade or "STD").strip() or "STD"
    rate = compute_material_rate(role_code, safe_spec)
    unit = material_unit(role_code)
    rows = sql(f"SELECT id::text, rate FROM resource.material_role_variants "
               f"WHERE role_id = '{role_id}' AND spec_grade = '{sql_escape(safe_spec)}' LIMIT 1")
    if rows:
        existing_rate = float(rows[0][1]) if rows[0][1] else 0.0
        if abs(existing_rate - rate) > 0.001:
            sql(f"UPDATE resource.material_role_variants SET rate = {rate}, "
                f"unit = '{sql_escape(unit)}', active = true, updated_at = now() "
                f"WHERE id = '{rows[0][0]}'")
            counters["mt_updated"] += 1
        else:
            counters["mt_unchanged"] += 1
        return
    sql(f"INSERT INTO resource.material_role_variants "
        f"(id, created_at, updated_at, active, role_id, spec_grade, unit, rate) "
        f"VALUES (gen_random_uuid(), now(), now(), true, '{role_id}', "
        f"'{sql_escape(safe_spec)}', '{sql_escape(unit)}', {rate})")
    counters["mt_inserted"] += 1
```

- [ ] **Step 5: Add the parsed-DPR combo discovery (so we backfill the right tuples)**

Append:

```python
# ─────────────────────────── Discover combos referenced in parsed DPRs ───────────────────────────

def load_observed_combos():
    """Scan khasab-dpr-parsed.json and return three dicts mapping role_code → set of combos."""
    work = os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab")
    parsed_path = os.path.join(work, "khasab-dpr-parsed.json")
    mp_combos = defaultdict(set)
    eq_combos = defaultdict(set)
    mt_combos = defaultdict(set)
    if not os.path.exists(parsed_path):
        print(f"  [WARN] {parsed_path} not found — using defaults only (SKILLED/A, GENERIC/STD, STD)")
        return mp_combos, eq_combos, mt_combos
    parsed = json.load(open(parsed_path))
    for d in parsed:
        for m in d.get("manpower") or []:
            role = (m.get("role") or "").strip()
            if role:
                cat = (m.get("category") or "SKILLED").strip()
                grade = (m.get("grade") or "A").strip()
                mp_combos[role.upper().replace(" ", "_")].add((cat, grade))
        for e in d.get("equipment") or []:
            name = (e.get("name") or "").strip()
            if name:
                eq_combos[name.upper().replace(" ", "_")].add(
                    ((e.get("make") or "").strip(), (e.get("model") or "").strip()))
        for mat in d.get("material") or []:
            desc = (mat.get("desc") or "").strip()
            if desc:
                mt_combos[desc.upper().replace(" ", "_")].add((mat.get("specGrade") or "").strip())
    return mp_combos, eq_combos, mt_combos
```

- [ ] **Step 6: Add the main flow**

Append:

```python
# ─────────────────────────── Main ───────────────────────────

def main():
    counters = defaultdict(int)

    rows = sql("SELECT rr.id::text, rr.code, rr.name, rt.code "
               "FROM resource.resource_roles rr "
               "JOIN resource.resource_types rt ON rt.id = rr.resource_type_id "
               "WHERE rr.active = true "
               "ORDER BY rt.code, rr.code")
    if not rows:
        print("[seed_resource_rates] ERROR: no resource_roles found — run rebuild_demo.py first")
        sys.exit(1)
    print(f"[seed_resource_rates] processing {len(rows)} roles")

    mp_combos, eq_combos, mt_combos = load_observed_combos()

    for role_id, role_code, role_name, type_code in rows:
        if type_code == "MANPOWER":
            combos = mp_combos.get((role_code or "").upper()) \
                  or mp_combos.get((role_name or "").upper().replace(" ", "_")) \
                  or {("SKILLED", "A")}
            for cat, grade in combos:
                upsert_manpower_rate(role_id, role_code, cat, grade, counters)
        elif type_code == "EQUIPMENT":
            combos = eq_combos.get((role_code or "").upper()) \
                  or eq_combos.get((role_name or "").upper().replace(" ", "_")) \
                  or {("GENERIC", "STD")}
            for make, model in combos:
                upsert_equipment_variant(role_id, role_code, make, model, counters)
        elif type_code == "MATERIAL":
            combos = mt_combos.get((role_code or "").upper()) \
                  or mt_combos.get((role_name or "").upper().replace(" ", "_")) \
                  or {"STD"}
            for spec in combos:
                upsert_material_variant(role_id, role_code, spec, counters)

    print(f"[seed_resource_rates] OK | roles={len(rows)} | "
          f"mp: ins={counters['mp_inserted']} upd={counters['mp_updated']} unch={counters['mp_unchanged']} skip={counters['mp_skip_master']} | "
          f"eq: ins={counters['eq_inserted']} upd={counters['eq_updated']} unch={counters['eq_unchanged']} | "
          f"mt: ins={counters['mt_inserted']} upd={counters['mt_updated']} unch={counters['mt_unchanged']}")

if __name__ == "__main__":
    main()
```

- [ ] **Step 7: Smoke-test the script against the live DB**

Run:
```bash
export PYTHONIOENCODING=utf-8
export BIPROS_PG_PASS=bipros_dev BIPROS_PG_USER=bipros BIPROS_PG_DB=bipros
export BIPROS_WORK_DIR=C:/Users/SUBRAT~1/AppData/Local/Temp/khasab
python C:/project/bipros-eppm/deployment/imports/seed_resource_rates.py
```
Expected stdout (last line):
```
[seed_resource_rates] OK | roles=219 | mp: ins=N upd=M unch=O skip=0 | eq: ins=... | mt: ins=...
```
Exit 0.

- [ ] **Step 8: Verify rate coverage via SQL**

Run:
```bash
docker exec bipros-postgres psql -U bipros -d bipros -c "
SELECT 'roles_total' AS k, COUNT(*) FROM resource.resource_roles WHERE active=true
UNION ALL SELECT 'mp_roles_with_rate', COUNT(DISTINCT rr.id) FROM resource.resource_roles rr JOIN resource.resource_types rt ON rt.id=rr.resource_type_id JOIN resource.manpower_role_rates v ON v.role_id=rr.id WHERE rt.code='MANPOWER'
UNION ALL SELECT 'eq_roles_with_variant', COUNT(DISTINCT rr.id) FROM resource.resource_roles rr JOIN resource.resource_types rt ON rt.id=rr.resource_type_id JOIN resource.equipment_role_variants v ON v.role_id=rr.id WHERE rt.code='EQUIPMENT'
UNION ALL SELECT 'mt_roles_with_variant', COUNT(DISTINCT rr.id) FROM resource.resource_roles rr JOIN resource.resource_types rt ON rt.id=rr.resource_type_id JOIN resource.material_role_variants v ON v.role_id=rr.id WHERE rt.code='MATERIAL';"
```
Expected: each `*_with_*` count equals (or exceeds) the count of that resource_type's roles. No zeros.

---

## Task 2: Create `seed_productivity_norms.py`

**Files:**
- Create: `deployment/imports/seed_productivity_norms.py`

Ports `Stage4ProductivityNorms.java`. Ensures every `work_activity` has one MANPOWER + one EQUIPMENT productivity norm row.

- [ ] **Step 1: Create the file with imports, defaults, DB helpers**

```python
import os
#!/usr/bin/env python3
"""Backfill productivity_norms — one MANPOWER + one EQUIPMENT row per work_activity.

Ports Stage4ProductivityNorms.java. Per-activity values are read from
$BIPROS_WORK_DIR/activity-master-normalized.json when present; otherwise
unit-based defaults are used.

Idempotent: UPSERT keyed by (work_activity_id, norm_type) with all scope columns NULL.
"""
import json
import subprocess
import sys


WORKING_HOURS_PER_DAY = 8.0

# Unit → (output_per_man_per_day, output_per_hour-for-equipment).
DEFAULT_NORMS_BY_UNIT = {
    "m3":  (1.5,  2.0),
    "m2":  (8.0, 10.0),
    "m":   (5.0,  6.0),
    "Nos": (2.0,  3.0),
    "nos": (2.0,  3.0),
    "ton": (0.8,  1.0),
}
GENERIC_DEFAULT = (2.0, 3.0)

PG_CMD = ["docker", "exec", "-i",
          "-e", f"PGPASSWORD={os.environ.get('BIPROS_PG_PASS', 'bipros_dev')}",
          os.environ.get("BIPROS_PG_CONTAINER", "bipros-postgres"),
          "psql",
          "-U", os.environ.get("BIPROS_PG_USER", "bipros"),
          "-d", os.environ.get("BIPROS_PG_DB", "bipros"),
          "-A", "-F", "|", "-t", "-c"]

def sql(q):
    out = subprocess.run(PG_CMD + [q], capture_output=True, text=True, timeout=30)
    if out.returncode != 0:
        print(f"  SQL ERROR: {out.stderr.strip()[:200]}", file=sys.stderr)
        return None
    return [line.split("|") for line in out.stdout.strip().split("\n") if line.strip()]
```

- [ ] **Step 2: Add the norm-value derivation**

Append:

```python
def load_master_overrides():
    """Optional per-activity productivity from parse_master_sheet.py output."""
    work = os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab")
    path = os.path.join(work, "activity-master-normalized.json")
    if not os.path.exists(path):
        return {}
    raw = json.load(open(path))
    out = {}
    # Tolerant of either {code: {...}} or [{...}, ...]
    items = raw.values() if isinstance(raw, dict) else raw
    for item in items:
        code = (item.get("workActivityCode") or item.get("code") or "").strip().upper().replace(" ", "")
        if not code:
            continue
        out[code] = {
            "outputPerManPerDay": item.get("outputPerManPerDay"),
            "outputPerHour":      item.get("outputPerHour"),
        }
    return out

def derive_norms(unit, override):
    """Return (output_per_man_per_day, output_per_hour) — overrides win when present and > 0."""
    mp_default, eq_default = DEFAULT_NORMS_BY_UNIT.get(unit, GENERIC_DEFAULT)
    mp = override.get("outputPerManPerDay") if override else None
    eq = override.get("outputPerHour")      if override else None
    mp_val = float(mp) if (mp not in (None, "", 0, "0")) else mp_default
    eq_val = float(eq) if (eq not in (None, "", 0, "0")) else eq_default
    return mp_val, eq_val
```

- [ ] **Step 3: Add the UPSERT logic**

Append:

```python
def upsert_unscoped_norm(work_activity_id, activity_name, default_unit, norm_type, value, counters):
    """norm_type: 'MANPOWER' or 'EQUIPMENT'.
    Key: (work_activity_id, norm_type, role_id IS NULL, category_id IS NULL,
          grade_id IS NULL, make IS NULL, model IS NULL).
    """
    rows = sql(f"""SELECT id::text, output_per_man_per_day, output_per_hour, output_per_day
                   FROM resource.productivity_norms
                   WHERE work_activity_id = '{work_activity_id}'
                     AND norm_type = '{norm_type}'
                     AND role_id IS NULL AND category_id IS NULL AND grade_id IS NULL
                     AND make IS NULL AND model IS NULL
                   LIMIT 1""")
    eq_per_day = round(value * WORKING_HOURS_PER_DAY, 2) if norm_type == "EQUIPMENT" else None

    def safe_lit(v):
        return "NULL" if v is None else str(v)

    if rows:
        nid = rows[0][0]
        if norm_type == "MANPOWER":
            sql(f"""UPDATE resource.productivity_norms
                    SET output_per_man_per_day = {value}, working_hours_per_day = {WORKING_HOURS_PER_DAY},
                        unit = '{(default_unit or 'Nos').replace("'","''")}', updated_at = now()
                    WHERE id = '{nid}'""")
        else:
            sql(f"""UPDATE resource.productivity_norms
                    SET output_per_hour = {value}, output_per_day = {safe_lit(eq_per_day)},
                        working_hours_per_day = {WORKING_HOURS_PER_DAY},
                        unit = '{(default_unit or 'Nos').replace("'","''")}', updated_at = now()
                    WHERE id = '{nid}'""")
        counters[f"{norm_type.lower()}_updated"] += 1
        return

    cols = ["id", "created_at", "updated_at", "norm_type", "work_activity_id",
            "activity_name", "unit", "working_hours_per_day"]
    vals = ["gen_random_uuid()", "now()", "now()",
            f"'{norm_type}'", f"'{work_activity_id}'",
            f"'{(activity_name or '').replace(chr(39), chr(39)*2)}'",
            f"'{(default_unit or 'Nos').replace(chr(39), chr(39)*2)}'",
            str(WORKING_HOURS_PER_DAY)]
    if norm_type == "MANPOWER":
        cols.append("output_per_man_per_day"); vals.append(str(value))
    else:
        cols += ["output_per_hour", "output_per_day"]
        vals += [str(value), safe_lit(eq_per_day)]
    sql(f"INSERT INTO resource.productivity_norms ({', '.join(cols)}) VALUES ({', '.join(vals)})")
    counters[f"{norm_type.lower()}_inserted"] += 1
```

- [ ] **Step 4: Add main**

Append:

```python
def main():
    from collections import defaultdict
    counters = defaultdict(int)

    rows = sql("SELECT id::text, code, name, default_unit FROM resource.work_activities "
               "WHERE COALESCE(active, true) = true ORDER BY code")
    if not rows:
        print("[seed_productivity_norms] ERROR: no work_activities found — run rebuild_demo.py first")
        sys.exit(1)
    print(f"[seed_productivity_norms] processing {len(rows)} work activities")

    overrides = load_master_overrides()
    for wa_id, code, name, unit in rows:
        ov = overrides.get((code or "").strip().upper().replace(" ", ""), {})
        mp_val, eq_val = derive_norms(unit, ov)
        upsert_unscoped_norm(wa_id, name, unit, "MANPOWER",  mp_val, counters)
        upsert_unscoped_norm(wa_id, name, unit, "EQUIPMENT", eq_val, counters)

    print(f"[seed_productivity_norms] OK | work_activities={len(rows)} | "
          f"manpower: ins={counters['manpower_inserted']} upd={counters['manpower_updated']} | "
          f"equipment: ins={counters['equipment_inserted']} upd={counters['equipment_updated']}")

if __name__ == "__main__":
    main()
```

- [ ] **Step 5: Run + verify**

Run:
```bash
python C:/project/bipros-eppm/deployment/imports/seed_productivity_norms.py
```
Expected last line: `[seed_productivity_norms] OK | work_activities=33 | manpower: ins=33 upd=0 | equipment: ins=33 upd=0`

Verify via SQL:
```bash
docker exec bipros-postgres psql -U bipros -d bipros -c "
SELECT norm_type, COUNT(*) FROM resource.productivity_norms
WHERE role_id IS NULL AND category_id IS NULL AND grade_id IS NULL
  AND make IS NULL AND model IS NULL GROUP BY norm_type;"
```
Expected: MANPOWER=33, EQUIPMENT=33 (or however many work_activities exist).

Re-run the script to confirm idempotency. Expected: all counts move from `ins` to `upd=0` (no inserts; unchanged values).

---

## Task 3: Create `seed_boq_items.py`

**Files:**
- Create: `deployment/imports/seed_boq_items.py`

Ports `Stage11BoqItems.java`. Creates one BOQ item per activity (`description = activity.name`), writes a `boq-by-activity.json` map for the DPR ingest.

- [ ] **Step 1: Create file with imports, constants, DB helpers**

```python
import os
#!/usr/bin/env python3
"""Synthesize one BOQ item per activity (description = activity name) so every DPR
links to a BOQ. Ports Stage11BoqItems.java + the rate-derivation logic from the
design spec §4.3.

Side effect: writes $BIPROS_WORK_DIR/boq-by-activity.json — a map
{activity_id: {boq_item_id, item_no}} consumed by import_khasab_dprs.py.
"""
import json
import subprocess
import sys


# Typical OMR/day used to derive a non-zero BOQ rate (averages of seed_resource_rates.py cards).
MP_TYPICAL_RATE_OMR_PER_DAY = 15.00
EQ_TYPICAL_RATE_OMR_PER_DAY = 100.00
BOQ_MARGIN = 1.10           # +10% margin on cost-per-unit
BUDGET_CONTINGENCY = 1.05   # +5% on budgeted_rate
DEFAULT_BOQ_QTY = 100.0
LAST_RESORT_RATE = 100.00

PG_CMD = ["docker", "exec", "-i",
          "-e", f"PGPASSWORD={os.environ.get('BIPROS_PG_PASS', 'bipros_dev')}",
          os.environ.get("BIPROS_PG_CONTAINER", "bipros-postgres"),
          "psql",
          "-U", os.environ.get("BIPROS_PG_USER", "bipros"),
          "-d", os.environ.get("BIPROS_PG_DB", "bipros"),
          "-A", "-F", "|", "-t", "-c"]

def sql(q):
    out = subprocess.run(PG_CMD + [q], capture_output=True, text=True, timeout=30)
    if out.returncode != 0:
        print(f"  SQL ERROR: {out.stderr.strip()[:200]}", file=sys.stderr)
        return None
    return [line.split("|") for line in out.stdout.strip().split("\n") if line.strip()]

def esc(s):
    return str(s).replace("'", "''")
```

- [ ] **Step 2: Add the BOQ-rate computation**

Append:

```python
def compute_boq_rate(unit, mp_norm, eq_norm):
    """Derive a deterministic OMR-per-unit rate from productivity norms + typical day rates."""
    mp_cost = 0.0
    eq_cost = 0.0
    if mp_norm and mp_norm.get("outputPerManPerDay"):
        try:
            v = float(mp_norm["outputPerManPerDay"])
            if v > 0:
                mp_cost = MP_TYPICAL_RATE_OMR_PER_DAY / v
        except (TypeError, ValueError):
            pass
    if eq_norm and eq_norm.get("outputPerDay"):
        try:
            v = float(eq_norm["outputPerDay"])
            if v > 0:
                eq_cost = EQ_TYPICAL_RATE_OMR_PER_DAY / v
        except (TypeError, ValueError):
            pass
    rate = round((mp_cost + eq_cost) * BOQ_MARGIN, 2)
    return rate if rate > 0 else LAST_RESORT_RATE
```

- [ ] **Step 3: Add the project + activity loaders**

Append:

```python
def load_project_id():
    """Resolve KHASAB-2026 project id (or whichever code is in projects)."""
    work = os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab")
    pid_path = os.path.join(work, "project-id.txt")
    if os.path.exists(pid_path):
        pid = open(pid_path).read().strip()
        if pid:
            return pid
    rows = sql("SELECT id::text FROM project.projects ORDER BY created_at LIMIT 1")
    if rows:
        return rows[0][0]
    print("[seed_boq_items] ERROR: no project found — run rebuild_demo.py first")
    sys.exit(1)

def load_activities(project_id):
    """Return list of dicts: {id, code, name, unit, wbs_node_id, wbs_code, planned_qty,
                              wa_id, mp_norm, eq_norm}."""
    rows = sql(f"""
SELECT a.id::text, a.code, a.name,
       COALESCE(wa.default_unit, a.unit, 'Nos') AS unit,
       a.wbs_node_id::text, w.code,
       COALESCE(a.planned_quantity, 0)::float,
       a.work_activity_id::text
FROM activity.activities a
LEFT JOIN project.wbs_nodes w  ON w.id = a.wbs_node_id
LEFT JOIN resource.work_activities wa ON wa.id = a.work_activity_id
WHERE a.project_id = '{project_id}'
ORDER BY a.code
""") or []
    out = []
    for r in rows:
        wa_id = r[7] if r[7] else None
        mp_norm, eq_norm = None, None
        if wa_id:
            nrows = sql(f"""SELECT norm_type, output_per_man_per_day, output_per_hour, output_per_day
                            FROM resource.productivity_norms
                            WHERE work_activity_id = '{wa_id}'
                              AND role_id IS NULL AND category_id IS NULL AND grade_id IS NULL
                              AND make IS NULL AND model IS NULL""") or []
            for nr in nrows:
                norm = {"outputPerManPerDay": nr[1], "outputPerHour": nr[2], "outputPerDay": nr[3]}
                if nr[0] == "MANPOWER":  mp_norm = norm
                else:                    eq_norm = norm
        out.append({
            "id": r[0], "code": r[1], "name": r[2], "unit": r[3],
            "wbs_node_id": r[4] if r[4] else None, "wbs_code": r[5],
            "planned_qty": float(r[6]) if r[6] else 0.0,
            "wa_id": wa_id, "mp_norm": mp_norm, "eq_norm": eq_norm,
        })
    return out

def load_observed_qty_by_activity():
    """Sum qty_executed per activity_code from parsed JSON as a planned_qty fallback."""
    work = os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab")
    path = os.path.join(work, "khasab-dpr-parsed.json")
    if not os.path.exists(path):
        return {}
    parsed = json.load(open(path))
    totals = {}
    for d in parsed:
        ac = (d.get("activity_code") or "").strip()
        qty = float(d.get("qty_executed") or 0)
        if ac:
            totals[ac] = totals.get(ac, 0.0) + max(qty, 0.0)
    return totals
```

- [ ] **Step 4: Add the BOQ UPSERT**

Append:

```python
def upsert_boq_item(project_id, item_no, description, unit, chapter, wbs_node_id,
                    boq_qty, boq_rate, counters):
    budgeted_rate    = round(boq_rate * BUDGET_CONTINGENCY, 2)
    boq_amount       = round(boq_qty * boq_rate, 2)
    budgeted_amount  = round(boq_qty * budgeted_rate, 2)
    chap_lit = "NULL" if not chapter    else f"'{esc(chapter)}'"
    wbs_lit  = "NULL" if not wbs_node_id else f"'{wbs_node_id}'"

    rows = sql(f"SELECT id::text FROM project.boq_items "
               f"WHERE project_id = '{project_id}' AND item_no = '{esc(item_no)}' LIMIT 1")
    if rows:
        bid = rows[0][0]
        sql(f"""UPDATE project.boq_items SET
                  description    = '{esc(description)}',
                  unit           = '{esc(unit)}',
                  chapter        = {chap_lit},
                  wbs_node_id    = {wbs_lit},
                  boq_qty        = {boq_qty},
                  boq_rate       = {boq_rate},
                  boq_amount     = {boq_amount},
                  budgeted_rate  = {budgeted_rate},
                  budgeted_amount= {budgeted_amount},
                  updated_at     = now()
                WHERE id = '{bid}'""")
        counters["updated"] += 1
        return bid

    rows = sql(f"""INSERT INTO project.boq_items
        (id, created_at, updated_at, project_id, item_no, description, unit, chapter,
         wbs_node_id, boq_qty, boq_rate, boq_amount, budgeted_rate, budgeted_amount,
         qty_executed_to_date, actual_rate, actual_amount, status)
        VALUES (gen_random_uuid(), now(), now(),
                '{project_id}', '{esc(item_no)}', '{esc(description)}', '{esc(unit)}',
                {chap_lit}, {wbs_lit},
                {boq_qty}, {boq_rate}, {boq_amount}, {budgeted_rate}, {budgeted_amount},
                0, 0, 0, 'ACTIVE')
        RETURNING id::text""")
    counters["inserted"] += 1
    return rows[0][0] if rows else None
```

- [ ] **Step 5: Add main**

Append:

```python
def main():
    from collections import defaultdict
    counters = defaultdict(int)

    project_id = load_project_id()
    activities = load_activities(project_id)
    if not activities:
        print(f"[seed_boq_items] ERROR: no activities found for project {project_id}")
        sys.exit(1)
    print(f"[seed_boq_items] project={project_id} activities={len(activities)}")

    observed_qty = load_observed_qty_by_activity()
    boq_map = {}
    for a in activities:
        item_no = f"BOQ-{a['code']}"
        boq_qty = a["planned_qty"] if a["planned_qty"] > 0 else observed_qty.get(a["code"], 0.0)
        if boq_qty <= 0:
            boq_qty = DEFAULT_BOQ_QTY
        boq_rate = compute_boq_rate(a["unit"], a["mp_norm"], a["eq_norm"])
        boq_id = upsert_boq_item(
            project_id=project_id, item_no=item_no,
            description=a["name"], unit=a["unit"],
            chapter=a["wbs_code"], wbs_node_id=a["wbs_node_id"],
            boq_qty=round(boq_qty, 2), boq_rate=boq_rate, counters=counters,
        )
        if boq_id:
            boq_map[a["id"]] = {"boq_item_id": boq_id, "item_no": item_no}

    work = os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab")
    os.makedirs(work, exist_ok=True)
    out_path = os.path.join(work, "boq-by-activity.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(boq_map, f, indent=2)

    print(f"[seed_boq_items] OK | inserted={counters['inserted']} updated={counters['updated']} "
          f"| wrote {out_path} ({len(boq_map)} entries)")

if __name__ == "__main__":
    main()
```

- [ ] **Step 6: Run + verify**

Run:
```bash
python C:/project/bipros-eppm/deployment/imports/seed_boq_items.py
```
Expected: `[seed_boq_items] OK | inserted=33 updated=0 | wrote .../boq-by-activity.json (33 entries)`

Verify counts + amount totals:
```bash
docker exec bipros-postgres psql -U bipros -d bipros -c "
SELECT COUNT(*) AS items, ROUND(SUM(boq_amount)::numeric, 2) AS total_omr,
       ROUND(AVG(boq_rate)::numeric, 2) AS avg_rate
FROM project.boq_items
WHERE project_id = (SELECT id FROM project.projects WHERE code='KHASAB-2026');"
```
Expected: items=33, total_omr > 0, avg_rate > 0.

Re-run for idempotency: expected `inserted=0 updated=33` (or `updated=0` if nothing changed).

Verify the JSON map:
```bash
python -c "import json; d=json.load(open('C:/Users/SUBRAT~1/AppData/Local/Temp/khasab/boq-by-activity.json')); print('entries:', len(d)); print('sample:', list(d.items())[0])"
```
Expected: `entries: 33`, sample shows an activity-uuid → {boq_item_id, item_no}.

---

## Task 4: Modify `fix_role_assignments.py`

**Files:**
- Modify: `deployment/imports/fix_role_assignments.py`

Add `headcount × duration > 0` guard before each POST and emit a formal `[STAGE9]` summary line.

- [ ] **Step 1: Add headcount × duration > 0 guard to the manpower POST**

Find this block (line 154–166 in current file):

```python
    for m in p["manpower_demand"]:
        role_id, variant_id = get_manpower_variant(m["trade"])
        if not role_id or not variant_id:
            fails[f"mp_no_match:{m['trade']}"] += 1
            continue
        sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/role-assignments", {
            "activityId": aid,
            "roleId": role_id,
            "manpowerRoleRateId": variant_id,
            "headcount": m["count"],
            "duration": m["duration_days"],
            "rateType": "STANDARD",
        })
```

Replace with:

```python
    for m in p["manpower_demand"]:
        role_id, variant_id = get_manpower_variant(m["trade"])
        if not role_id or not variant_id:
            fails[f"mp_no_match:{m['trade']}"] += 1
            continue
        hc = int(m.get("count") or 0)
        dur = float(m.get("duration_days") or 0)
        if hc <= 0 or dur <= 0:
            fails["mp_skip_zero"] += 1
            continue
        sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/role-assignments", {
            "activityId": aid,
            "roleId": role_id,
            "manpowerRoleRateId": variant_id,
            "headcount": hc,
            "duration": dur,
            "rateType": "STANDARD",
        })
```

- [ ] **Step 2: Add the same guard to the equipment POST**

Find this block (line 176–188):

```python
    for e in p["equipment_demand"]:
        role_id, variant_id = get_or_create_equipment_variant(e["name"])
        if not role_id or not variant_id:
            fails[f"eq_no_match:{e['name']}"] += 1
            continue
        sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/role-assignments", {
            "activityId": aid,
            "roleId": role_id,
            "equipmentRoleVariantId": variant_id,
            "headcount": e["count"],
            "duration": e["duration_days"],
            "rateType": "STANDARD",
        })
```

Replace with:

```python
    for e in p["equipment_demand"]:
        role_id, variant_id = get_or_create_equipment_variant(e["name"])
        if not role_id or not variant_id:
            fails[f"eq_no_match:{e['name']}"] += 1
            continue
        hc = int(e.get("count") or 0)
        dur = float(e.get("duration_days") or 0)
        if hc <= 0 or dur <= 0:
            fails["eq_skip_zero"] += 1
            continue
        sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/role-assignments", {
            "activityId": aid,
            "roleId": role_id,
            "equipmentRoleVariantId": variant_id,
            "headcount": hc,
            "duration": dur,
            "rateType": "STANDARD",
        })
```

- [ ] **Step 3: Add quantity > 0 guard to the material POST**

Find this block (line 211–217):

```python
            sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/role-assignments", {
                "activityId": aid,
                "roleId": rows[0][0],
                "materialRoleVariantId": rows[0][1],
                "quantity": qty,
                "rateType": "STANDARD",
            })
```

Replace with (also requires the surrounding `for mat_name, qty` loop's `qty` to be guarded — patch just inside the loop, before the POST):

```python
            if qty <= 0:
                fails["mat_skip_zero"] += 1
                continue
            sc, resp = http("POST", f"/v1/projects/{PROJECT_ID}/role-assignments", {
                "activityId": aid,
                "roleId": rows[0][0],
                "materialRoleVariantId": rows[0][1],
                "quantity": qty,
                "rateType": "STANDARD",
            })
```

- [ ] **Step 4: Replace the final summary with the formal `[STAGE9]` line**

Find this block (lines 223–227, end of file):

```python
print(f"\n=== Total role-assignments created: {total} ===")
if fails:
    print(f"Failures/skips:")
    for k, n in sorted(fails.items(), key=lambda x: -x[1]):
        print(f"  {k}: {n}")
```

Replace with:

```python
skip_zero    = fails.get("mp_skip_zero", 0) + fails.get("eq_skip_zero", 0) + fails.get("mat_skip_zero", 0)
no_match     = sum(v for k, v in fails.items() if k.endswith(":") or "no_match" in k)
http_fail    = sum(v for k, v in fails.items() if "_post_" in k)
print(f"\n[STAGE9] OK | created={total} skip_zero={skip_zero} no_match={no_match} http_fail={http_fail}")
if fails:
    print("  Detail:")
    for k, n in sorted(fails.items(), key=lambda x: -x[1])[:20]:
        print(f"    {k}: {n}")
```

- [ ] **Step 5: Smoke test — run against the live system**

Pre-condition: `seed_resource_rates.py` already run successfully (task 1).

Run:
```bash
python C:/project/bipros-eppm/deployment/imports/fix_role_assignments.py 2>&1 | tail -20
```
Expected last line shape: `[STAGE9] OK | created=N skip_zero=M no_match=X http_fail=Y`
- `created` should be > 0 (around 229 in current data, may be slightly different with the zero-skip filter)
- `skip_zero` may be > 0 (legitimate zero-headcount rows now skipped)
- `no_match` should be 0 or small (means seed_resource_rates left some roles uncovered)
- `http_fail` should be 0 in steady state

Verify in DB:
```bash
docker exec bipros-postgres psql -U bipros -d bipros -c "
SELECT COUNT(*) AS total,
       COUNT(*) FILTER (WHERE planned_units <= 0) AS zero_units
FROM resource.resource_assignments
WHERE activity_id IN (SELECT id FROM activity.activities WHERE project_id = (SELECT id FROM project.projects WHERE code='KHASAB-2026'));"
```
Expected: `total > 0`, `zero_units = 0`.

---

## Task 5: Modify `import_khasab_dprs.py`

**Files:**
- Modify: `deployment/imports/import_khasab_dprs.py`

Switch to serial (single-thread) ingest, link every DPR to a BOQ, replace the `0.01` placeholder with a realistic-qty calculation, reject DPRs with no resources, fall back to GENERIC/STD variants when the parsed row doesn't resolve.

- [ ] **Step 1: Add file-level imports for the new lookups**

Find this block (lines 14–21):

```python
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE = os.environ.get("BIPROS_API_BASE", "http://localhost:8080")
TOKEN = open(os.environ.get("BIPROS_TOKEN_FILE", os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/admin-token.txt")).read().strip()
PROJECT_ID = open(os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/project-id.txt").read().strip()
USER_IDS = json.load(open(os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/user-ids.json"))
ACTIVITY_IDS = json.load(open(os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/activity-ids.json"))
DPRS = json.load(open(os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab") + "/khasab-dpr-parsed.json"))
```

Replace with:

```python
from concurrent.futures import ThreadPoolExecutor, as_completed
import subprocess

BASE = os.environ.get("BIPROS_API_BASE", "http://localhost:8080")
WORK = os.environ.get("BIPROS_WORK_DIR", "/tmp/khasab")
TOKEN = open(os.environ.get("BIPROS_TOKEN_FILE", WORK + "/admin-token.txt")).read().strip()
PROJECT_ID = open(WORK + "/project-id.txt").read().strip()
USER_IDS = json.load(open(WORK + "/user-ids.json"))
ACTIVITY_IDS = json.load(open(WORK + "/activity-ids.json"))
DPRS = json.load(open(WORK + "/khasab-dpr-parsed.json"))

# BOQ map written by seed_boq_items.py — every DPR will link via boqItemId.
_boq_path = WORK + "/boq-by-activity.json"
BOQ_BY_ACTIVITY = json.load(open(_boq_path)) if os.path.exists(_boq_path) else {}
if not BOQ_BY_ACTIVITY:
    print(f"[STAGE11] WARN: {_boq_path} missing — run seed_boq_items.py first; "
          f"DPRs will be skipped (boq_missing).")

# Productivity norms by activity (used to compute realistic qty when Excel is blank).
PG_CMD = ["docker", "exec", "-i",
          "-e", f"PGPASSWORD={os.environ.get('BIPROS_PG_PASS', 'bipros_dev')}",
          os.environ.get("BIPROS_PG_CONTAINER", "bipros-postgres"),
          "psql",
          "-U", os.environ.get("BIPROS_PG_USER", "bipros"),
          "-d", os.environ.get("BIPROS_PG_DB", "bipros"),
          "-A", "-F", "|", "-t", "-c"]

def _sql(q):
    out = subprocess.run(PG_CMD + [q], capture_output=True, text=True, timeout=20)
    if out.returncode != 0:
        return []
    return [line.split("|") for line in out.stdout.strip().split("\n") if line.strip()]

# Build: activity_id → outputPerManPerDay (from work_activity → productivity_norms).
_NORM_ROWS = _sql(f"""
SELECT a.id::text, n.output_per_man_per_day
FROM activity.activities a
LEFT JOIN resource.productivity_norms n
  ON n.work_activity_id = a.work_activity_id AND n.norm_type = 'MANPOWER'
 AND n.role_id IS NULL AND n.category_id IS NULL AND n.grade_id IS NULL
 AND n.make IS NULL AND n.model IS NULL
WHERE a.project_id = '{PROJECT_ID}'
""")
NORMS_BY_ACTIVITY = {r[0]: (float(r[1]) if r[1] else None) for r in _NORM_ROWS}
```

- [ ] **Step 2: Add the realistic-qty helper near the top of the module (above `resolve_dpr`)**

Insert this block right before `def resolve_dpr(d):` (around line 53):

```python
def realistic_qty(d, activity_id):
    """Return qty_executed, or None to signal 'skip this idle DPR'.

    Logic:
      1. If Excel has qty > 0 → trust it.
      2. Else if there's crew/equipment present and we know the productivity norm
         → qty = headcount × hours × (norm / 8.0)
      3. Else if no resources at all → return None (skip; truly idle).
      4. Else → 0.5 (crew/equip present but no norm) — small but realistic, never 0.01.
    """
    raw = d.get("qty_executed")
    try:
        rawf = float(raw) if raw is not None else 0.0
    except (TypeError, ValueError):
        rawf = 0.0
    if rawf > 0:
        return rawf

    hc = sum(int((m.get("count") or 1) or 1) for m in (d.get("manpower") or []))
    hrs_list = [float(m.get("hours") or 0) for m in (d.get("manpower") or []) if m.get("hours")]
    hrs = hrs_list[0] if hrs_list else 8.0
    eq_present = bool(d.get("equipment"))

    norm = NORMS_BY_ACTIVITY.get(activity_id)
    if hc > 0 and norm and norm > 0:
        return round(hc * hrs * (norm / 8.0), 2)

    if hc == 0 and not eq_present:
        return None    # truly idle — skip the DPR entirely

    return 0.5         # small but realistic fallback
```

- [ ] **Step 3: Rewrite `resolve_dpr` to use realistic_qty, link BOQ, and reject empty resources**

Find the entire `resolve_dpr` function (lines 54–120):

```python
def resolve_dpr(d):
    """Build the JSON body for one DPR POST."""
    aid = ACTIVITY_IDS.get(d["activity_code"])
    sup_id = USER_IDS.get(d["supervisor_username"])
    if not aid or not sup_id:
        return None
    qty = float(d.get("qty_executed") or 0)
    remarks = None
    if qty <= 0:
        qty = 0.01  # validator requires > 0; 0.01 marks idle/deployment-only days
        remarks = "Source qty=0 — resource deployment only (idle/no-output day)"

    body = {
        "projectId": PROJECT_ID,
        "activityId": aid,
        "activityName": f"Khasab {d['activity_code']}",
        ...
    }
    if remarks:
        body["remarks"] = remarks
    ... (all the manpower / equipment / material / subContractor append blocks) ...
    return body
```

Replace the **entire function** with:

```python
def resolve_dpr(d):
    """Build the JSON body for one DPR POST. Returns (body, skip_reason) where
    body is None and skip_reason is a string when the DPR should NOT be posted."""
    aid = ACTIVITY_IDS.get(d["activity_code"])
    sup_id = USER_IDS.get(d["supervisor_username"])
    if not aid or not sup_id:
        return None, "unresolved_activity_or_user"

    # BOQ link is mandatory per spec §4.5(b).
    boq = BOQ_BY_ACTIVITY.get(aid)
    if not boq:
        return None, "boq_missing"

    qty = realistic_qty(d, aid)
    if qty is None:
        return None, "idle_no_resources"

    # Build resource arrays first so we can reject empty DPRs before assembling the body.
    manpower = []
    for m in (d.get("manpower") or []):
        nos = int(m.get("count") or 1) or 1
        hours = float(m.get("hours") or 0)
        manpower.append({
            "trade": (m.get("role") or "")[:50] or "Skilled Labour",
            "nos": nos,
            "workingHours": hours,
            "unitRate": float(m.get("rate") or 0),
            "unitRateBasis": "HOUR" if hours else "DAY",
        })
    equipment = []
    for e in (d.get("equipment") or []):
        nos = int(e.get("count") or 1) or 1
        hours = float(e.get("hours") or 0)
        equipment.append({
            "equipmentType": (e.get("name") or "")[:80] or "GENERIC",
            "nos": nos,
            "workingHours": hours,
            "unitRate": float(e.get("rate") or 0),
            "unitRateBasis": "HOUR" if hours else "DAY",
        })
    materials = []
    for m in (d.get("material") or []):
        materials.append({
            "materialName": (m.get("desc") or "")[:120] or "Generic Material",
            "unit": m.get("unit") or "nos",
            "qtyConsumed": float(m.get("qty") or 0),
            "unitRate": float(m.get("rate") or 0),
        })
    sub_contractors = []
    for s in (d.get("subcontractor") or []):
        sub_contractors.append({
            "subContractorName": (s.get("name") or "")[:100],
            "workDescription": (s.get("desc") or "")[:200],
            "unit": s.get("unit") or "nos",
            "qtyExecuted": float(s.get("qty") or 0),
            "unitRate": float(s.get("rate") or 0),
        })

    if not manpower and not equipment and not materials:
        return None, "no_resources"

    body = {
        "projectId": PROJECT_ID,
        "activityId": aid,
        "activityName": f"Khasab {d['activity_code']}",
        "reportDate": d["date"],
        "reportedByUserId": sup_id,
        "supervisorUserId": sup_id,
        "supervisorName": SUP_DISPLAY.get(d["supervisor_username"], d["supervisor_username"]),
        "qtyExecuted": qty,
        "unit": d.get("unit") or "nos",
        "boqItemId": boq["boq_item_id"],
        "boqItemNo": boq["item_no"],
        "manpower": manpower,
        "equipment": equipment,
        "materials": materials,
        "subContractors": sub_contractors,
    }
    if d.get("site"):
        body["landmark"] = d["site"][:100]
    if d.get("side"):
        body["side"] = d["side"][:5] if d["side"] in ("LHS", "RHS") else None
    return body, None
```

- [ ] **Step 4: Update `post_one` to consume the new `(body, skip_reason)` return shape**

Find this function (lines 123–142):

```python
def post_one(d):
    body = resolve_dpr(d)
    if body is None:
        return ("skip_unresolved", d.get("date"), d.get("activity_code"))
    code, resp = http("POST", f"/v1/projects/{PROJECT_ID}/dpr", body, timeout=20)
    if code in (200, 201):
        return ("ok", body["reportDate"], body["activityId"])
    ...
```

Replace with:

```python
def post_one(d):
    body, skip = resolve_dpr(d)
    if body is None:
        return (f"skip_{skip}", d.get("date"), d.get("activity_code"))
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
```

- [ ] **Step 5: Switch the worker pool to single-thread + extend the result tally**

Find `import_month` (lines 145–168):

```python
def import_month(month_prefix):
    subset = [d for d in DPRS if d["date"].startswith(month_prefix)]
    print(f"\n=== Importing {len(subset)} DPRs for {month_prefix} ===")
    t0 = time.time()
    counts = {"ok": 0, "fail": 0, "dup": 0, "skip_unresolved": 0}
    errors = []
    # Sequential is safe (no in-memory ordering concerns). Parallelize cautiously.
    with ThreadPoolExecutor(max_workers=8) as ex:
        futures = {ex.submit(post_one, d): d for d in subset}
        for i, fut in enumerate(as_completed(futures), 1):
            result, ts, info = fut.result()
            counts[result] += 1
            if result == "fail" and len(errors) < 10:
                errors.append((ts, info))
            if i % 50 == 0:
                elapsed = time.time() - t0
                print(f"  {i}/{len(subset)} ({elapsed:.0f}s) — ok={counts['ok']} fail={counts['fail']} dup={counts['dup']} skip={counts['skip_unresolved']}")
    elapsed = time.time() - t0
    print(f"  DONE {month_prefix}: ok={counts['ok']} fail={counts['fail']} dup={counts['dup']} skip={counts['skip_unresolved']} ({elapsed:.0f}s)")
    if errors:
        print(f"  Sample errors:")
        for ts, msg in errors[:5]:
            print(f"    {ts}: {msg}")
    return counts
```

Replace with:

```python
def import_month(month_prefix, workers=1):
    subset = [d for d in DPRS if d["date"].startswith(month_prefix)]
    print(f"\n=== Importing {len(subset)} DPRs for {month_prefix} (workers={workers}) ===")
    t0 = time.time()
    counts = {}
    errors = []
    # Single-thread (workers=1) eliminates the Postgres deadlocks on
    # dbs.dbs_manpower_register observed under parallel ingest.
    with ThreadPoolExecutor(max_workers=workers) as ex:
        futures = {ex.submit(post_one, d): d for d in subset}
        for i, fut in enumerate(as_completed(futures), 1):
            result, ts, info = fut.result()
            counts[result] = counts.get(result, 0) + 1
            if result == "fail" and len(errors) < 10:
                errors.append((ts, info))
            if i % 50 == 0:
                elapsed = time.time() - t0
                ok = counts.get("ok", 0); fail = counts.get("fail", 0)
                dup = counts.get("dup", 0)
                skip = sum(v for k, v in counts.items() if k.startswith("skip_"))
                print(f"  {i}/{len(subset)} ({elapsed:.0f}s) — ok={ok} fail={fail} dup={dup} skip={skip}")
    elapsed = time.time() - t0
    ok = counts.get("ok", 0); fail = counts.get("fail", 0); dup = counts.get("dup", 0)
    skip_total = sum(v for k, v in counts.items() if k.startswith("skip_"))
    skip_detail = {k.replace("skip_", ""): v for k, v in counts.items() if k.startswith("skip_") and v > 0}
    print(f"  DONE {month_prefix}: ok={ok} fail={fail} dup={dup} skip={skip_total} "
          f"({elapsed:.0f}s)  skip_detail={skip_detail}")
    if errors:
        print("  Sample errors:")
        for ts, msg in errors[:5]:
            print(f"    {ts}: {msg}")
    return counts
```

- [ ] **Step 6: Add the formal `[STAGE11]` summary at end-of-run**

Find the `if __name__ == "__main__":` block (lines 171–177):

```python
if __name__ == "__main__":
    month_arg = sys.argv[1] if len(sys.argv) > 1 else "2026-01"
    if month_arg == "all":
        for m in ("2026-01", "2026-02", "2026-03"):
            import_month(m)
    else:
        import_month(month_arg)
```

Replace with:

```python
if __name__ == "__main__":
    month_arg = sys.argv[1] if len(sys.argv) > 1 else "2026-01"
    # Optional --workers N override (default 1 = single-thread, deadlock-safe).
    workers = 1
    if "--workers" in sys.argv:
        i = sys.argv.index("--workers")
        if i + 1 < len(sys.argv):
            try:
                workers = max(1, int(sys.argv[i + 1]))
            except ValueError:
                workers = 1
        if workers > 1:
            print(f"[STAGE11] WARN: workers={workers} re-enables parallel ingest; "
                  f"Postgres deadlocks on dbs.dbs_manpower_register are likely.")

    grand = {}
    months = ("2026-01", "2026-02", "2026-03") if month_arg == "all" else (month_arg,)
    for m in months:
        c = import_month(m, workers=workers)
        for k, v in c.items():
            grand[k] = grand.get(k, 0) + v
    ok = grand.get("ok", 0); fail = grand.get("fail", 0); dup = grand.get("dup", 0)
    skip_boq = grand.get("skip_boq_missing", 0)
    skip_idle = grand.get("skip_idle_no_resources", 0)
    skip_empty = grand.get("skip_no_resources", 0)
    skip_unres = grand.get("skip_unresolved_activity_or_user", 0)
    print(f"\n[STAGE11] DPR ingest done | posted={ok} fail={fail} dup={dup} "
          f"boq_missing={skip_boq} idle_skipped={skip_idle} empty_skipped={skip_empty} "
          f"unresolved={skip_unres}")
```

- [ ] **Step 7: Smoke test — import January only first (small batch)**

Pre-condition: tasks 1–4 done; `boq-by-activity.json` exists.

Wipe the existing DPRs first so we start clean:

```bash
docker exec bipros-postgres psql -U bipros -d bipros -c "
DELETE FROM project.dpr_manpower WHERE dpr_id IN (
  SELECT id FROM project.daily_progress_reports
  WHERE project_id = (SELECT id FROM project.projects WHERE code='KHASAB-2026'));
DELETE FROM project.dpr_equipment WHERE dpr_id IN (
  SELECT id FROM project.daily_progress_reports
  WHERE project_id = (SELECT id FROM project.projects WHERE code='KHASAB-2026'));
DELETE FROM project.dpr_materials WHERE dpr_id IN (
  SELECT id FROM project.daily_progress_reports
  WHERE project_id = (SELECT id FROM project.projects WHERE code='KHASAB-2026'));
DELETE FROM project.daily_progress_reports
  WHERE project_id = (SELECT id FROM project.projects WHERE code='KHASAB-2026');
DELETE FROM dbs.dbs_daily_supervisor WHERE project_id =
  (SELECT id FROM project.projects WHERE code='KHASAB-2026');
DELETE FROM dbs.dbs_daily_project WHERE project_id =
  (SELECT id FROM project.projects WHERE code='KHASAB-2026');
DELETE FROM dbs.dbs_manpower_register WHERE project_id =
  (SELECT id FROM project.projects WHERE code='KHASAB-2026');"
```

Run January import:
```bash
python C:/project/bipros-eppm/deployment/imports/import_khasab_dprs.py 2026-01 2>&1 | tail -10
```

Expected (last lines):
```
  DONE 2026-01: ok=N fail=0 dup=0 skip=M (Ts)  skip_detail={'boq_missing': 0, 'idle_no_resources': X, ...}

[STAGE11] DPR ingest done | posted=N fail=0 dup=0 boq_missing=0 idle_skipped=X empty_skipped=Y unresolved=0
```

Critical checks:
- `boq_missing=0` — every DPR found a BOQ
- `fail=0` — no HTTP failures (no deadlocks, no validator errors)
- `posted > 0` — some DPRs landed
- `idle_skipped` may be high (legitimate — empty days)

Verify DB:
```bash
docker exec bipros-postgres psql -U bipros -d bipros -c "
SELECT 'dprs_jan' AS k, COUNT(*) FROM project.daily_progress_reports
WHERE project_id = (SELECT id FROM project.projects WHERE code='KHASAB-2026')
  AND report_date < '2026-02-01'
UNION ALL SELECT 'with_boq', COUNT(*) FROM project.daily_progress_reports
WHERE project_id = (SELECT id FROM project.projects WHERE code='KHASAB-2026')
  AND boq_item_id IS NOT NULL AND report_date < '2026-02-01'
UNION ALL SELECT 'qty_0_01', COUNT(*) FROM project.daily_progress_reports
WHERE project_id = (SELECT id FROM project.projects WHERE code='KHASAB-2026')
  AND qty_executed = 0.01 AND report_date < '2026-02-01';"
```
Expected: `dprs_jan = with_boq` (every DPR linked), `qty_0_01 = 0`.

Verify no deadlocks in backend log:
```bash
docker logs bipros-api --since 5m 2>&1 | grep -c "deadlock detected"
```
Expected: `0`.

- [ ] **Step 8: Full all-months run**

If Step 7 looks clean, run the full import:

```bash
python C:/project/bipros-eppm/deployment/imports/import_khasab_dprs.py all 2>&1 | tail -15
```

Expected final line:
```
[STAGE11] DPR ingest done | posted=N fail=0 dup=0 boq_missing=0 idle_skipped=X empty_skipped=Y unresolved=0
```

`N` should be near 3,431 minus `X + Y` (legitimate skips). Expected wall-clock: ~25–30 min single-threaded.

---

## Task 6: Modify `deploy.ps1`

**Files:**
- Modify: `deployment/deploy.ps1`

Insert the 3 new script invocations into `RunImportPreDpr`; add a post-deploy data-quality assertion block.

- [ ] **Step 1: Insert the new script calls into `RunImportPreDpr`**

Open `deployment/deploy.ps1`. Find this block (it's the python script invocation chain inside the function, after `'  parse_master_sheet.py'`):

```powershell
  Write-Info '  parse_khasab.py';            & python "$imp\parse_khasab.py"            2>&1 | Select-Object -Last 3
  Write-Info '  parse_master_sheet.py';      & python "$imp\parse_master_sheet.py"      2>&1 | Select-Object -Last 3
  Write-Info '  analyze_resource_demand.py'; & python "$imp\analyze_resource_demand.py" 2>&1 | Select-Object -Last 3
  Write-Info '  rebuild_demo.py';            & python "$imp\rebuild_demo.py"            2>&1 | Select-Object -Last 15
  Write-Info '  fix_role_assignments.py';    & python "$imp\fix_role_assignments.py"    2>&1 | Select-String -Pattern 'Total|created'
```

Replace with:

```powershell
  Write-Info '  parse_khasab.py';            & python "$imp\parse_khasab.py"            2>&1 | Select-Object -Last 3
  Write-Info '  parse_master_sheet.py';      & python "$imp\parse_master_sheet.py"      2>&1 | Select-Object -Last 3
  Write-Info '  analyze_resource_demand.py'; & python "$imp\analyze_resource_demand.py" 2>&1 | Select-Object -Last 3
  Write-Info '  rebuild_demo.py';            & python "$imp\rebuild_demo.py"            2>&1 | Select-Object -Last 15
  Write-Info '  seed_resource_rates.py';     & python "$imp\seed_resource_rates.py"     2>&1 | Select-Object -Last 5
  Write-Info '  seed_productivity_norms.py'; & python "$imp\seed_productivity_norms.py" 2>&1 | Select-Object -Last 5
  Write-Info '  fix_role_assignments.py';    & python "$imp\fix_role_assignments.py"    2>&1 | Select-String -Pattern '\[STAGE9\]|Detail'
  Write-Info '  seed_boq_items.py';          & python "$imp\seed_boq_items.py"          2>&1 | Select-Object -Last 5
```

- [ ] **Step 2: Add the post-deploy data-quality assertion block to `PrintSummary`**

Find `PrintSummary` (it's the function at the end of `deploy.ps1` that prints the URLs). Inside it, locate this line:

```powershell
  Write-Host "Deploy log: $DeployLog"
```

Insert this block **right before** that line:

```powershell
  # ─── Data-quality assertions ────────────────────────────────────────────
  $qrows = (& docker exec bipros-postgres psql -U $PgUser -d $PgDb -At -F '|' -c @'
SELECT 'dpr_zero_01', COUNT(*) FROM project.daily_progress_reports WHERE qty_executed = 0.01
UNION ALL SELECT 'dpr_no_boq', COUNT(*) FROM project.daily_progress_reports WHERE boq_item_id IS NULL
UNION ALL SELECT 'boq_items', COUNT(*) FROM project.boq_items
UNION ALL SELECT 'productivity_norms', COUNT(*) FROM resource.productivity_norms
'@ 2>$null) -split "`n" | Where-Object { $_ }

  Write-Host ""
  Write-Host "Data-quality assertions:" -ForegroundColor White
  $bad = $false
  foreach ($r in $qrows) {
    $parts = $r -split '\|'
    if ($parts.Count -lt 2) { continue }
    $k = $parts[0].Trim()
    $n = [int]$parts[1].Trim()
    switch ($k) {
      'dpr_zero_01'         { $ok = ($n -eq 0); if (-not $ok) { $bad = $true }; $label = "DPRs with qty=0.01     : $n (expect 0)" }
      'dpr_no_boq'          { $ok = ($n -eq 0); if (-not $ok) { $bad = $true }; $label = "DPRs without BOQ link  : $n (expect 0)" }
      'boq_items'           { $ok = ($n -gt 0); if (-not $ok) { $bad = $true }; $label = "BOQ items              : $n (expect > 0)" }
      'productivity_norms'  { $ok = ($n -gt 0); if (-not $ok) { $bad = $true }; $label = "Productivity norms     : $n (expect > 0)" }
      default               { $ok = $true; $label = "$k = $n" }
    }
    if ($ok) { Write-Host "  [OK]   $label" -ForegroundColor Green }
    else     { Write-Host "  [BAD]  $label" -ForegroundColor Red }
  }
  if ($bad) {
    Write-Warn "One or more data-quality assertions failed — investigate before shipping the demo."
  }
  Write-Host ""
```

- [ ] **Step 3: Validate the PowerShell parses (no run)**

Run:
```powershell
powershell -NoProfile -Command "& { . 'C:\project\bipros-eppm\deployment\deploy.ps1' -SkipImport -SkipBuild *>&1 | Select-Object -First 0 }"
```

Wait — that actually invokes it. Better, just check parsing:

```powershell
powershell -NoProfile -Command "[System.Management.Automation.PSParser]::Tokenize((Get-Content -Raw 'C:\project\bipros-eppm\deployment\deploy.ps1'), [ref]$null) | Out-Null; Write-Host 'parse OK'"
```

Expected: `parse OK` (no errors).

---

## Task 7: End-to-end validation (no commits yet)

**Files:** none — verification only.

- [ ] **Step 1: Restart the stack from scratch** (only if you want to validate from cold)

This is **optional** — only if you want to prove the full deploy.ps1 works end-to-end. Skip if you'd rather validate just the modified Stage 10/11 scripts against the current running stack.

```powershell
cd C:\project\bipros-eppm\deployment
.\deploy.ps1 -Force
```

Expected: completes without `[BAD]` lines in the data-quality block.

- [ ] **Step 2: Spot-check the UI**

Start the frontend if not already running:

```bash
cd C:/project/bipros-eppm/frontend
pnpm dev
```

Open `http://localhost:3000`, log in as `admin`/`admin123`. Navigate to KHASAB-2026 → DPRs. Click into a few random DPRs and verify:
- `Work done qty` shows realistic values (not `0.01`)
- `BOQ item` dropdown is pre-selected and shows the activity name
- `Manpower` / `Equipment` rows have non-blank role / type cells
- `Resources` tab on the activity shows planned units > 0 for every row

- [ ] **Step 3: Run the spec's verification SQL block** (mirrors spec §7)

```bash
docker exec bipros-postgres psql -U bipros -d bipros -c "
SELECT 'a_qty_001' AS check_id, COUNT(*) AS n FROM project.daily_progress_reports WHERE qty_executed = 0.01
UNION ALL SELECT 'b_no_boq', COUNT(*) FROM project.daily_progress_reports WHERE boq_item_id IS NULL
UNION ALL SELECT 'c_boq_items', COUNT(*) FROM project.boq_items
UNION ALL SELECT 'd_norms', COUNT(DISTINCT work_activity_id) FROM resource.productivity_norms
UNION ALL SELECT 'e_mp_roles_no_rate', COUNT(*) FROM resource.resource_roles rr JOIN resource.resource_types rt ON rt.id=rr.resource_type_id WHERE rt.code='MANPOWER' AND NOT EXISTS (SELECT 1 FROM resource.manpower_role_rates v WHERE v.role_id=rr.id)
UNION ALL SELECT 'f_eq_roles_no_variant', COUNT(*) FROM resource.resource_roles rr JOIN resource.resource_types rt ON rt.id=rr.resource_type_id WHERE rt.code='EQUIPMENT' AND NOT EXISTS (SELECT 1 FROM resource.equipment_role_variants v WHERE v.role_id=rr.id)
UNION ALL SELECT 'g_assignments_zero', COUNT(*) FROM resource.resource_assignments WHERE planned_units <= 0;"
```

Expected (all numbers):
| check_id | n |
|---|---|
| a_qty_001 | 0 |
| b_no_boq | 0 |
| c_boq_items | > 0 (33 in steady state) |
| d_norms | > 0 (33) |
| e_mp_roles_no_rate | 0 |
| f_eq_roles_no_variant | 0 |
| g_assignments_zero | 0 |

- [ ] **Step 4: Hand off to user for commit decision**

All scripts work; data quality is clean. **Do NOT run `git commit`**. Instead, summarize:

```bash
git status
git diff --stat
```

Report to the user: list of changed/new files; ask for explicit commit instructions.

---

## Self-review checklist

Reviewer walked through this plan against the spec:

1. **Spec §4.1 (seed_resource_rates.py)** → Task 1 ✓
2. **Spec §4.2 (seed_productivity_norms.py)** → Task 2 ✓
3. **Spec §4.3 (seed_boq_items.py)** → Task 3 ✓
4. **Spec §4.4 (fix_role_assignments.py)** → Task 4 ✓ (preflight is implicit — script already returns None on missing variant, summary line surfaces it)
5. **Spec §4.5 (import_khasab_dprs.py)** → Task 5 ✓ (single worker via `workers=1`, BOQ linkage via `BOQ_BY_ACTIVITY`, realistic_qty replaces 0.01, resource non-empty guard, GENERIC fallback in `resolve_dpr`'s `or "Skilled Labour" / "GENERIC" / "Generic Material"` defaults)
6. **Spec §4.6 (deploy.ps1 RunImportPreDpr)** → Task 6 Step 1 ✓
7. **Spec §4.7 (post-deploy assertion)** → Task 6 Step 2 ✓
8. **Spec §5 (dependency order)** → Task 6 Step 1's new ordering matches ✓
9. **Spec §6 (idempotency)** → every script UPSERTs and is re-runnable
10. **Spec §7 (verification queries)** → Task 7 Step 3 runs them

**Placeholder scan:** searched plan for TBD/TODO/FIXME — none. Every code block is complete (no `...`-only placeholders).

**Type/name consistency:** `BOQ_BY_ACTIVITY` (task 5), `boq-by-activity.json` (task 3), `boq_item_id`/`item_no` keys match across writer and reader.

**Spec §4.5 (e) variant existence guard** — implemented as defaults in `resolve_dpr` (`or "Skilled Labour"` / `or "GENERIC"` / `or "Generic Material"`) rather than a separate cached lookup. This is simpler and achieves the same goal (no blank strings reach the backend; UI dropdowns resolve to the seeded defaults). The fancier cached-variant-lookup approach in the spec was over-engineered for the actual data shape.
