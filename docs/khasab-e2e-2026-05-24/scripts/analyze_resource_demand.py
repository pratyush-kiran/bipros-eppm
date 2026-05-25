#!/usr/bin/env python3
"""For each activity_code in Khasab DPRs:
1. Resolve a real name from master sheet (with smart parent fallback)
2. Derive typical resource demand (manpower roles + counts, equipment, materials)
   from average crew across all DPRs for that code.

Output: /tmp/khasab/activity-plan.json
{
  "<code>": {
    "name": "...",       # real, not 'Khasab X.Y.Z'
    "unit": "...",
    "rate": float,       # from master if known
    "manpower_demand": [{"trade": "Helper", "count": 5, "duration_days": 65}, ...],
    "equipment_demand": [{"name": "Excavator", "count": 1, "duration_days": 65}, ...],
    "material_demand": [{"name": "Cement", "qty_per_day": 5, "unit": "MT"}, ...],
  }
}
"""
import json
import re
from collections import defaultdict, Counter
from datetime import date

DPRS = json.load(open("/tmp/khasab-dpr-parsed.json"))
MASTER = json.load(open("/tmp/khasab/activity-master-normalized.json"))


def normalize(c):
    return re.sub(r'\s+', '', str(c)).strip()


def resolve_name(code):
    """Smart lookup with parent-fallback chain."""
    nc = normalize(code)
    if nc in MASTER:
        return MASTER[nc]["name"], MASTER[nc]["unit"], MASTER[nc]["unit_rate"]

    # Try progressively shorter suffixes
    # 13.1.7(ix)b2 → 13.1.7(ix)b → 13.1.7(ix)
    candidates = []
    # Strip trailing digit
    candidates.append(re.sub(r'\d+$', '', nc).rstrip())
    # Strip trailing single letter
    candidates.append(re.sub(r'[a-z]$', '', nc).rstrip())
    # Strip trailing (letters/digits)
    candidates.append(re.sub(r'\([^)]*\)[^()]*$', '', nc).rstrip().rstrip('.'))
    # Try parent prefix (strip last .X)
    parts = nc.split('.')
    if len(parts) > 1:
        candidates.append('.'.join(parts[:-1]))

    for c in candidates:
        if c and c in MASTER:
            return MASTER[c]["name"], MASTER[c]["unit"], MASTER[c]["unit_rate"]

    # Find the closest master entry whose code starts with our prefix
    # e.g. '13.1.7(ix)b1' → look for any master code starting with '13.1.7(ix)' → use first match
    for prefix_len in range(len(nc), 2, -1):
        prefix = nc[:prefix_len]
        candidates = [k for k in MASTER.keys() if k.startswith(prefix)]
        if candidates:
            c = sorted(candidates)[0]
            # Use master's literal name but tag the variant
            base_name = MASTER[c]["name"]
            return base_name, MASTER[c]["unit"], MASTER[c]["unit_rate"]

    # No match anywhere — keep the literal code as the name (no synthesis)
    return code, "nos", 0


# Derive resource demand per activity
agg = defaultdict(lambda: {
    "manpower": defaultdict(list),    # role → [count per DPR]
    "equipment": defaultdict(list),
    "material": defaultdict(list),
    "qty_executed": [],
    "dates": set(),
})

for d in DPRS:
    code = d["activity_code"]
    a = agg[code]
    if d.get("qty_executed", 0) > 1:
        a["qty_executed"].append(d["qty_executed"])
    a["dates"].add(d["date"])

    for m in d.get("manpower", []):
        a["manpower"][m["role"]].append(m["count"])
    for e in d.get("equipment", []):
        a["equipment"][e["name"]].append(e["count"])
    for mat in d.get("material", []):
        a["material"][mat["desc"]].append(mat["qty"])


plan = {}
for code, a in agg.items():
    name, unit, rate = resolve_name(code)
    duration = len(a["dates"])  # span days
    # Aggregate manpower: average count per role per active day, ceil
    mp_demand = []
    for role, counts in a["manpower"].items():
        # use median count (more typical than max)
        sc = sorted(counts)
        median = sc[len(sc) // 2] if sc else 1
        mp_demand.append({
            "trade": role,
            "count": max(int(round(median)), 1),
            "duration_days": duration,
        })
    eq_demand = []
    for eq, counts in a["equipment"].items():
        sc = sorted(counts)
        median = sc[len(sc) // 2] if sc else 1
        eq_demand.append({
            "name": eq,
            "count": max(int(round(median)), 1),
            "duration_days": duration,
        })
    mat_demand = []
    for mat, qtys in a["material"].items():
        avg_qty = sum(qtys) / max(len(qtys), 1)
        mat_demand.append({
            "name": mat,
            "qty_per_day": round(avg_qty, 2),
            "total_qty": round(sum(qtys), 2),
        })

    plan[code] = {
        "name": name,
        "unit": unit,
        "rate": rate,
        "duration_days": duration,
        "qty_avg": round(sum(a["qty_executed"]) / max(len(a["qty_executed"]), 1), 2),
        "qty_total": round(sum(a["qty_executed"]), 2),
        "dpr_count": sum(1 for d in DPRS if d["activity_code"] == code),
        "manpower_demand": mp_demand,
        "equipment_demand": eq_demand,
        "material_demand": mat_demand,
    }

with open("/tmp/khasab/activity-plan.json", "w") as f:
    json.dump(plan, f, indent=2, default=str)

print(f"Generated resource plan for {len(plan)} activities")
print(f"\nSample (top 5 by DPR count):")
for code in sorted(plan.keys(), key=lambda k: -plan[k]["dpr_count"])[:5]:
    p = plan[code]
    print(f"\n  {code}: {p['name'][:60]}")
    print(f"    unit={p['unit']}, duration={p['duration_days']}d, qty_total={p['qty_total']}")
    print(f"    Manpower: {[(m['trade'], m['count']) for m in p['manpower_demand']]}")
    print(f"    Equipment: {[(e['name'], e['count']) for e in p['equipment_demand']]}")
    if p["material_demand"]:
        print(f"    Material: {[(m['name'][:30], m['total_qty']) for m in p['material_demand'][:3]]}")

print(f"\nWrote /tmp/khasab/activity-plan.json")
