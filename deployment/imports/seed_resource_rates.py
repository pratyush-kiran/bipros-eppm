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


# ─────────────────────────── Master resolution ───────────────────────────

def resolve_or_create_category(name_or_code):
    """Resolve manpower_category_masters by NAME first, then CODE, create if missing.
    Returns id (str) or None."""
    raw = (name_or_code or "").strip()
    if not raw:
        raw = "Skilled"
    upper = raw.upper()
    # Prefer name match (Java Stage 2 behaviour: human-readable name wins)
    rows = sql(f"SELECT id::text FROM resource.manpower_category_master "
               f"WHERE upper(name) = upper('{sql_escape(raw)}') LIMIT 1")
    if rows:
        return rows[0][0]
    rows = sql(f"SELECT id::text FROM resource.manpower_category_master "
               f"WHERE upper(code) = upper('{sql_escape(upper)}') LIMIT 1")
    if rows:
        return rows[0][0]
    # Create
    rows = sql(f"INSERT INTO resource.manpower_category_master "
               f"(id, version, created_at, updated_at, created_by, updated_by, "
               f"active, code, name, sort_order) "
               f"VALUES (gen_random_uuid(), 0, now(), now(), 'SYSTEM', 'SYSTEM', "
               f"true, '{sql_escape(upper)}', '{sql_escape(raw.title())}', 0) "
               f"RETURNING id::text")
    return rows[0][0] if rows else None

def resolve_or_create_grade(code):
    """Resolve grade_master by CODE, create if missing. Returns id (str) or None."""
    raw = (code or "A").strip().upper()
    rows = sql(f"SELECT id::text FROM resource.grade_master "
               f"WHERE upper(code) = upper('{sql_escape(raw)}') LIMIT 1")
    if rows:
        return rows[0][0]
    rows = sql(f"INSERT INTO resource.grade_master "
               f"(id, version, created_at, updated_at, created_by, updated_by, "
               f"active, code, name, sort_order) "
               f"VALUES (gen_random_uuid(), 0, now(), now(), 'SYSTEM', 'SYSTEM', "
               f"true, '{sql_escape(raw)}', 'Grade {sql_escape(raw)}', 0) "
               f"RETURNING id::text")
    return rows[0][0] if rows else None


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
            # COALESCE on version/created_by heals any pre-existing rows that
            # were inserted before the audit-column fix (where version=NULL).
            sql(f"UPDATE resource.manpower_role_rates SET rate = {rate}, "
                f"unit = '{UNIT_DAY}', active = true, updated_at = now(), "
                f"updated_by = 'SYSTEM', "
                f"version = COALESCE(version, 0), "
                f"created_by = COALESCE(created_by, 'SYSTEM') "
                f"WHERE id = '{rows[0][0]}'")
            counters["mp_updated"] += 1
        else:
            counters["mp_unchanged"] += 1
        return
    sql(f"INSERT INTO resource.manpower_role_rates "
        f"(id, version, created_at, updated_at, created_by, updated_by, "
        f"active, role_id, category_id, grade_id, unit, rate) "
        f"VALUES (gen_random_uuid(), 0, now(), now(), 'SYSTEM', 'SYSTEM', "
        f"true, '{role_id}', '{cat_id}', '{grade_id}', '{UNIT_DAY}', {rate})")
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
                f"unit = '{UNIT_DAY}', active = true, updated_at = now(), "
                f"updated_by = 'SYSTEM', "
                f"version = COALESCE(version, 0), "
                f"created_by = COALESCE(created_by, 'SYSTEM') "
                f"WHERE id = '{rows[0][0]}'")
            counters["eq_updated"] += 1
        else:
            counters["eq_unchanged"] += 1
        return
    sql(f"INSERT INTO resource.equipment_role_variants "
        f"(id, version, created_at, updated_at, created_by, updated_by, "
        f"active, role_id, make, model, unit, rate) "
        f"VALUES (gen_random_uuid(), 0, now(), now(), 'SYSTEM', 'SYSTEM', "
        f"true, '{role_id}', '{sql_escape(safe_make)}', '{sql_escape(safe_model)}', "
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
                f"unit = '{sql_escape(unit)}', active = true, updated_at = now(), "
                f"updated_by = 'SYSTEM', "
                f"version = COALESCE(version, 0), "
                f"created_by = COALESCE(created_by, 'SYSTEM') "
                f"WHERE id = '{rows[0][0]}'")
            counters["mt_updated"] += 1
        else:
            counters["mt_unchanged"] += 1
        return
    sql(f"INSERT INTO resource.material_role_variants "
        f"(id, version, created_at, updated_at, created_by, updated_by, "
        f"active, role_id, spec_grade, unit, rate) "
        f"VALUES (gen_random_uuid(), 0, now(), now(), 'SYSTEM', 'SYSTEM', "
        f"true, '{role_id}', '{sql_escape(safe_spec)}', '{sql_escape(unit)}', {rate})")
    counters["mt_inserted"] += 1


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
    with open(parsed_path, encoding="utf-8") as f:
        parsed = json.load(f)
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


# ─────────────────────────── Audit-column heal (idempotent) ───────────────────────────

def heal_audit_nulls():
    """Backfill NULL version/created_by/updated_by on rows inserted by earlier
    runs of these scripts before the audit-column fix was added. Hibernate's
    @Version field cannot be NULL — leaving it NULL causes a NullPointerException
    in any code path that auto-flushes a dirty entity (e.g. DPR submit →
    DprBoqSyncListener → BoqService.addExecutedQty). One-shot fix; subsequent
    runs are no-ops once all NULLs are healed."""
    for table in [
        "resource.manpower_category_master",
        "resource.grade_master",
        "resource.manpower_role_rates",
        "resource.equipment_role_variants",
        "resource.material_role_variants",
    ]:
        sql(f"UPDATE {table} SET "
            f"version = COALESCE(version, 0), "
            f"created_by = COALESCE(created_by, 'SYSTEM'), "
            f"updated_by = COALESCE(updated_by, 'SYSTEM') "
            f"WHERE version IS NULL OR created_by IS NULL OR updated_by IS NULL")


# ─────────────────────────── Main ───────────────────────────

def main():
    counters = defaultdict(int)
    heal_audit_nulls()

    rows = sql("SELECT rr.id::text, rr.code, rr.name, rt.code "
               "FROM resource.resource_roles rr "
               "JOIN resource.resource_types rt ON rt.id = rr.resource_type_id "
               "WHERE rr.active = true "
               "ORDER BY rt.code, rr.code")
    if rows is None:
        print("[seed_resource_rates] ERROR: SQL query failed (see SQL ERROR above) — check DB connectivity")
        sys.exit(1)
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
            # NOTE: material combos are a set of spec_grade STRINGS (not tuples like
            # manpower/equipment). spec_grade is the only variant key for materials.
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
