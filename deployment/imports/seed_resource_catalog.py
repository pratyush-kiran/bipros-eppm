#!/usr/bin/env python3
"""Ensure every manpower/equipment resource name used in the DPRs has a rate-book
variant, so it appears in (and pre-selects on) the DPR resource dropdown for any activity.

The DPR dropdown is fed by the activity's planned assignments PLUS the global rate book
(/v1/role-rates/{manpower,equipment}). A resource only shows up there if its ResourceRole
has an active variant. This script walks the distinct resource names in the parsed DPR
JSON and, for any name the rate book can't already satisfy (via import_khasab_dprs's
matcher + ALIAS), creates the missing role and/or a variant.

Idempotent: re-running skips names already covered. Env: BIPROS_API_BASE, BIPROS_WORK_DIR,
BIPROS_TOKEN_FILE (same as import_khasab_dprs.py).

Usage:  python3 seed_resource_catalog.py
"""
import sys, os, json, collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import import_khasab_dprs as imp  # reuses http(), _norm, ALIAS, resolve_role, DPRS, TOKEN

http = imp.http


def get_data(path):
    code, resp = http("GET", path)
    return resp.get("data", []) if (code == 200 and isinstance(resp, dict)) else []


def main():
    # ---- reference data ----------------------------------------------------
    types = {t.get("code"): t.get("id") for t in get_data("/v1/resource-types")}
    mp_type, eq_type = types.get("MANPOWER"), types.get("EQUIPMENT")
    if not mp_type or not eq_type:
        sys.exit("Could not resolve MANPOWER/EQUIPMENT resource-type ids")

    cats = {imp._norm(c.get("name")): c.get("id") for c in get_data("/v1/admin/manpower-categories")}
    # generic fallback categories
    generic_cat = cats.get("skilled") or cats.get("unskilled") or (next(iter(cats.values())) if cats else None)
    grades = get_data("/v1/grade-master")
    grade_id = grades[0].get("id") if grades else None
    if not generic_cat or not grade_id:
        sys.exit("Need at least one manpower category and one grade to seed variants")

    # existing roles by normalized name, split by type
    roles = get_data("/v1/resource-roles")
    mp_roles = {imp._norm(r["name"]): r["id"] for r in roles if r.get("resourceTypeCode") == "MANPOWER"}
    eq_roles = {imp._norm(r["name"]): r["id"] for r in roles if r.get("resourceTypeCode") == "EQUIPMENT"}

    # distinct names + a representative (median) hourly rate from the DPR data
    mp_names, eq_names = collections.OrderedDict(), collections.OrderedDict()
    mp_rates, eq_rates = collections.defaultdict(list), collections.defaultdict(list)
    for d in imp.DPRS:
        for m in d["manpower"]:
            mp_names[m["role"].strip()] = True
            if m.get("rate"): mp_rates[m["role"].strip()].append(float(m["rate"]))
        for e in d["equipment"]:
            eq_names[e["name"].strip()] = True
            if e.get("rate"): eq_rates[e["name"].strip()].append(float(e["rate"]))

    def median(xs, default):
        xs = sorted(x for x in xs if x > 0)
        return xs[len(xs) // 2] if xs else default

    def code_for(prefix, name):
        slug = "".join(ch if ch.isalnum() else "-" for ch in name.upper()).strip("-")
        return f"{prefix}-{slug}"[:50]

    created_roles = added_variants = skipped = 0

    def ensure_manpower(name):
        nonlocal created_roles, added_variants, skipped
        book = imp._rate_book()["MANPOWER"]
        if imp.resolve_role(name, book):
            skipped += 1
            return
        rate = round(median(mp_rates.get(name, []), 5.0), 2)
        rid = mp_roles.get(imp._norm(name)) or (imp.resolve_role(name, mp_roles))
        if rid:  # role exists → add one variant
            c, r = http("POST", f"/v1/roles/{rid}/manpower-rates",
                        {"categoryId": generic_cat, "gradeId": grade_id, "unit": "Hour", "rate": rate, "active": True})
            ok = c in (200, 201)
            print(f"  [mp] variant -> existing role '{name}' rate={rate} : {'OK' if ok else 'FAIL '+str(r)[:120]}")
            added_variants += ok
        else:    # create role + variant in one shot
            c, r = http("POST", "/v1/resource-roles/with-variants",
                        {"code": code_for("MP", name), "name": name, "resourceTypeId": mp_type,
                         "active": True,
                         "manpowerVariants": [{"categoryId": generic_cat, "gradeId": grade_id,
                                               "unit": "Hour", "rate": rate, "active": True}]})
            ok = c in (200, 201)
            print(f"  [mp] role+variant '{name}' rate={rate} : {'OK' if ok else 'FAIL '+str(r)[:160]}")
            created_roles += ok

    def ensure_equipment(name):
        nonlocal created_roles, added_variants, skipped
        book = imp._rate_book()["EQUIPMENT"]
        if imp.resolve_role(name, book):
            skipped += 1
            return
        rate = round(median(eq_rates.get(name, []), 10.0), 2)
        rid = eq_roles.get(imp._norm(name)) or (imp.resolve_role(name, eq_roles))
        if rid:
            c, r = http("POST", f"/v1/roles/{rid}/equipment-variants",
                        {"make": "Generic", "model": "Standard", "unit": "Hour", "rate": rate, "active": True})
            ok = c in (200, 201)
            print(f"  [eq] variant -> existing role '{name}' rate={rate} : {'OK' if ok else 'FAIL '+str(r)[:120]}")
            added_variants += ok
        else:
            c, r = http("POST", "/v1/resource-roles/with-variants",
                        {"code": code_for("EQ", name), "name": name, "resourceTypeId": eq_type,
                         "active": True,
                         "equipmentVariants": [{"make": "Generic", "model": "Standard",
                                                "unit": "Hour", "rate": rate, "active": True}]})
            ok = c in (200, 201)
            print(f"  [eq] role+variant '{name}' rate={rate} : {'OK' if ok else 'FAIL '+str(r)[:160]}")
            created_roles += ok

    print(f"Seeding catalog for {len(mp_names)} manpower + {len(eq_names)} equipment names ...")
    for n in mp_names: ensure_manpower(n)
    for n in eq_names: ensure_equipment(n)

    # bust the cached rate book and report final coverage
    imp._RATE_BOOK = None
    book = imp._rate_book()
    mp_miss = [n for n in mp_names if not imp.resolve_role(n, book["MANPOWER"])]
    eq_miss = [n for n in eq_names if not imp.resolve_role(n, book["EQUIPMENT"])]
    print(f"\nSeed summary: roles created={created_roles}, variants added={added_variants}, already-covered={skipped}")
    print(f"Coverage now: manpower {len(mp_names)-len(mp_miss)}/{len(mp_names)}, equipment {len(eq_names)-len(eq_miss)}/{len(eq_names)}")
    if mp_miss: print("  STILL missing manpower:", mp_miss)
    if eq_miss: print("  STILL missing equipment:", eq_miss)


if __name__ == "__main__":
    main()
