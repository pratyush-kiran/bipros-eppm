# Fresh Environment + Khasab Real-Data E2E Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wipe the dev `bipros` DB back to system-master baseline, configure 12 users (8 supervisors + 2 engineers + 1 CM + 1 PM), create a fresh `KHASAB-2026` project with WBS + activities + productivity norms + simple master rates, import ~26,920 Khasab DPR rows (date-shifted +1y) via bulk endpoint, validate every module and the AI tool surface, and produce a single consolidated HTML execution report.

**Architecture:** 14 sequential phases, each driven by a 3-agent team (implementer + reviewer + devil's-advocate Explore agents) whose findings are synthesized by the main thread. All mutations execute on the main thread for a single audit trail. After each phase, pause for user OK before continuing.

**Tech Stack:** Native Homebrew Postgres 16 (`bipros@127.0.0.1:5432`), Spring Boot 3.5 backend on `:8080`, Next.js 16 frontend on `:3000`, Python venv (`/tmp/xlsx_venv` with openpyxl + pandas) for Excel parsing, `curl` for API mutations with admin JWT, Playwright MCP for browser checks, single static HTML for the final report.

**Spec:** `docs/superpowers/specs/2026-05-24-fresh-env-and-khasab-import-design.md` (commit `0ae8b462`)

---

## File Structure

**Artifacts created during the run:**

| Path | Created at | Purpose |
|---|---|---|
| `/tmp/bipros-backup-2026-05-24.dump` | Task 1 | pg_dump custom-format backup for rollback |
| `/tmp/khasab-state.json` | Task 5–9 | Runtime ID registry (project, WBS, activities, user IDs, rate-master IDs) |
| `/tmp/parse_khasab.py` | Task 3 | Python parser for the Khasab workbook |
| `/tmp/khasab-dpr-parsed.json` | Task 3 | Normalized DPR payloads ready for bulk POST |
| `/tmp/khasab-dpr-validation_report.md` | Task 3 | Pre-import validation: unknown supervisors/activities/dates |
| `/tmp/ai-ground-truth.json` | Task 14 | SQL ground-truth per AI question |
| `/tmp/ai-results.json` | Task 14 | AI response + tool trace + grade per question |
| `docs/ActualData/exports/khasab-dpr-2026-05-24.csv` | Task 17 | Flat DPR export |
| `docs/ActualData/exports/khasab-dpr-2026-05-24.xlsx` | Task 17 | 4-sheet pivoted export |
| `docs/dpr-dbs-e2e-test-execution-log-2026-05-24.md` | Tasks 1–18 (appended live) | Markdown mirror of prior logs |
| `docs/dpr-dbs-e2e-execution-log-2026-05-24.html` | Task 18 | Single-file HTML deliverable |

**Files modified during the run:**

| Path | Why |
|---|---|
| `backend/bipros-api/src/main/resources/application.yml` | Add 4 backfill-disable properties (Task 1, reverted in Task 19) |

---

## Recurring sub-routines

These show up in many tasks; the actual code is repeated inline per the no-placeholders rule, but here's the conceptual shape for orientation.

**Auth token refresh** (called at the start of every phase that mutates):

```bash
TOKEN=$(curl -sS -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | jq -r '.data.token')
test -n "$TOKEN" || { echo "FATAL: token empty"; exit 1; }
```

**3-agent team dispatch** (concept — actual prompts are inline per task):

1. Implementer agent (general-purpose): drafts payload/SQL/commands
2. Reviewer agent (Explore): verifies against spec + runbook gotchas
3. Devil's advocate agent (Explore): challenges assumptions, hunts edge cases
4. Main thread synthesizes → confirms with user → executes

**Markdown log append** (after each task):

```bash
cat >> docs/dpr-dbs-e2e-test-execution-log-2026-05-24.md <<EOF
## Task N — <title> — $(date -u +%Y-%m-%dT%H:%M:%SZ)
- Decisions: ...
- Commands run: ...
- Validation: Expected vs Actual (table)
- Findings: ...
EOF
```

---

## Task 1: Phase 1 — DB cleanup (backup → wipe → restart)

**Files:**
- Create: `/tmp/bipros-backup-2026-05-24.dump`
- Modify: `backend/bipros-api/src/main/resources/application.yml`
- Append: `docs/dpr-dbs-e2e-test-execution-log-2026-05-24.md`

- [ ] **Step 1.1: Dispatch 3-agent team for cleanup plan validation**

Dispatch in parallel (in one message):

```
Implementer agent (general-purpose):
"Draft a TRUNCATE SQL script for the bipros DB that wipes all transactional
data in schemas project/activity/scheduling/cost/evm/baseline/dbs/udf/portfolio/contract/document/permit/safety/site_ops/ai/gis/analytics
plus risk.* (except risk_category_master + risk_templates) plus public.audit_log
plus DELETE FROM public.users WHERE username != 'admin'.
KEEP all rate masters, work_activities, resource_roles, all *_category_master,
roles, permissions, calendars, currencies, evm_settings.
Output ordered TRUNCATE statements (FK-safe) and a verification SELECT block."

Reviewer agent (Explore):
"Walk the schema and confirm the implementer's TRUNCATE list does not miss any
transactional table and does not accidentally wipe a master. Cross-check with
this list of schemas I know to exist [list]. Report any miss or false-positive."

Devil's advocate agent (Explore):
"Find every bipros table whose deletion would cause backend boot failure or
admin login failure or AI conversation cache poisoning. Specifically check:
session/refresh tokens, auth state, ai.conversations, ai.messages, fk
references from system tables to public.users. What did the implementer miss?"
```

- [ ] **Step 1.2: Synthesize agent outputs and confirm with user**

Show user: final TRUNCATE order, list of kept tables, any flagged risks. Pause for OK.

- [ ] **Step 1.3: Backup**

```bash
pg_dump -h 127.0.0.1 -U bipros -F c -f /tmp/bipros-backup-2026-05-24.dump bipros 2>&1
ls -lh /tmp/bipros-backup-2026-05-24.dump
pg_restore --list /tmp/bipros-backup-2026-05-24.dump | wc -l
```

Expected: file ~150-300MB, restore list line count > 1000 (lots of objects).

- [ ] **Step 1.4: Execute TRUNCATE plan**

Run the synthesized SQL (one transaction):

```bash
psql -h 127.0.0.1 -U bipros -d bipros -v ON_ERROR_STOP=1 -f /tmp/wipe-transactional.sql 2>&1 | tail -30
```

Expected: every TRUNCATE returns silently; final COMMIT succeeds; no error.

- [ ] **Step 1.5: Disable backfill seeders in application.yml**

Edit `backend/bipros-api/src/main/resources/application.yml`. Find the existing `bipros:` block (or add at file end if absent) and ensure these properties exist:

```yaml
bipros:
  dbs:
    backfill:
      enabled: false
  backfill:
    legacy-daily-output:
      enabled: false
  seeder:
    project-team:
      enabled: true
seeders:
  legacy:
    enabled: false
  excel-master:
    enabled: false
analytics:
  bootstrap:
    enabled: false
```

If `bipros:` already exists, MERGE these keys into it — don't duplicate the top-level key (YAML will reject).

- [ ] **Step 1.6: Restart backend**

```bash
pkill -f "bipros-api" || true
sleep 3
# verify nothing on :8080
lsof -ti:8080 || echo "port clear"

# boot in background (with -am to avoid stale-jar gotcha)
cd /Volumes/Java/Projects/bipros-eppm
(mvn -f backend/bipros-api/pom.xml -am -Dmaven.test.skip=true spring-boot:run > /tmp/bipros-backend.log 2>&1) &
BACKEND_PID=$!
echo $BACKEND_PID > /tmp/bipros-backend.pid

# poll health (timeout 5 min)
for i in $(seq 1 60); do
  if curl -sf http://localhost:8080/actuator/health > /dev/null; then
    echo "backend up after ${i}x5s"
    break
  fi
  sleep 5
done
curl -sf http://localhost:8080/actuator/health | jq .
```

Expected: `{"status":"UP",...}`. If not up after 5 minutes, tail `/tmp/bipros-backend.log` and abort.

- [ ] **Step 1.7: Verify BIPROS_AI_KEK is set (per `dev_ai_kek` memory)**

```bash
ps eww $(cat /tmp/bipros-backend.pid) | grep -o 'BIPROS_AI_KEK=[^ ]*' | head -1 || \
  echo "WARN: BIPROS_AI_KEK not in backend env — AI tests will fail in Task 14"
```

If missing, instruct user to export it and restart Phase 1.6 only.

- [ ] **Step 1.8: Restart frontend (only if not already running)**

```bash
if ! lsof -ti:3000 > /dev/null; then
  cd /Volumes/Java/Projects/bipros-eppm/frontend
  (pnpm dev > /tmp/bipros-frontend.log 2>&1) &
  echo $! > /tmp/bipros-frontend.pid
  for i in $(seq 1 30); do
    if curl -sf http://localhost:3000 > /dev/null; then
      echo "frontend up after ${i}x3s"
      break
    fi
    sleep 3
  done
fi
```

- [ ] **Step 1.9: Run clean-state verification queries**

```bash
psql -h 127.0.0.1 -U bipros -d bipros -c "
SELECT 'dpr' AS metric, COUNT(*) FROM project.daily_progress_reports
UNION ALL SELECT 'projects', COUNT(*) FROM project.projects
UNION ALL SELECT 'users', COUNT(*) FROM public.users
UNION ALL SELECT 'roles', COUNT(*) FROM public.roles
UNION ALL SELECT 'manpower_rates', COUNT(*) FROM resource.manpower_rate_masters
UNION ALL SELECT 'equipment_rates', COUNT(*) FROM resource.equipment_rate_masters
UNION ALL SELECT 'material_rates', COUNT(*) FROM resource.material_rate_masters
UNION ALL SELECT 'work_activities', COUNT(*) FROM resource.work_activities
UNION ALL SELECT 'resource_roles', COUNT(*) FROM resource.resource_roles
;"
```

Expected:

| metric | count |
|---|---:|
| dpr | 0 |
| projects | 0 |
| users | 1 |
| roles | 22 |
| manpower_rates | 16 |
| equipment_rates | 57 |
| material_rates | 33 |
| work_activities | 178 |
| resource_roles | 207 |

- [ ] **Step 1.10: Append to markdown log + pause for user OK (Gate 1)**

Append Task 1 section to `docs/dpr-dbs-e2e-test-execution-log-2026-05-24.md` with backup path/size, verification table, any deviations from expected.

**GATE:** ask user "Phase 1 verified clean. Proceed to Phase 2?"

---

## Task 2: Phase 2 — Frontend smoke

**Files:**
- Create: `frontend/e2e/.artifacts/screenshots/2026-05-24-phase2-empty-dashboard.png`
- Append: `docs/dpr-dbs-e2e-test-execution-log-2026-05-24.md`

- [ ] **Step 2.1: Open browser to login page**

```
mcp__plugin_playwright_playwright__browser_navigate
  url: http://localhost:3000
```

Expected: login form visible.

- [ ] **Step 2.2: Login as admin/admin123**

```
mcp__plugin_playwright_playwright__browser_fill_form
  fields: [
    {name: "username", value: "admin"},
    {name: "password", value: "admin123"}
  ]

mcp__plugin_playwright_playwright__browser_click
  selector: 'button[type="submit"]'
```

Expected: redirect to dashboard.

- [ ] **Step 2.3: Verify zero-state**

```
mcp__plugin_playwright_playwright__browser_snapshot
mcp__plugin_playwright_playwright__browser_console_messages
mcp__plugin_playwright_playwright__browser_network_requests
```

Expected: dashboard renders empty/zero-state tiles, no console errors, no 4xx/5xx.

- [ ] **Step 2.4: Screenshot for HTML report**

```
mcp__plugin_playwright_playwright__browser_take_screenshot
  filename: frontend/e2e/.artifacts/screenshots/2026-05-24-phase2-empty-dashboard.png
  fullPage: true
```

- [ ] **Step 2.5: Append to log + GATE 2**

Pause: "Phase 2 smoke passed. Proceed to Phase 3 (Excel parse)?"

---

## Task 3: Phase 3 — Parse Khasab workbook + produce validation report

**Files:**
- Create: `/tmp/parse_khasab.py`
- Create: `/tmp/khasab-dpr-parsed.json`
- Create: `/tmp/khasab-dpr-validation_report.md`

- [ ] **Step 3.1: Ensure Python venv with openpyxl + pandas**

```bash
test -x /tmp/xlsx_venv/bin/python || {
  python3 -m venv /tmp/xlsx_venv
  /tmp/xlsx_venv/bin/pip install --quiet openpyxl pandas python-dateutil
}
/tmp/xlsx_venv/bin/python -c 'import openpyxl, pandas; print("ok")'
```

Expected: `ok`.

- [ ] **Step 3.2: Write the parser**

Create `/tmp/parse_khasab.py`:

```python
#!/usr/bin/env python3
"""Parse Khasab workbook, shift dates +1y, group into DPR payloads."""
import json, sys, re, os
from collections import defaultdict
from datetime import datetime
from dateutil.relativedelta import relativedelta
from openpyxl import load_workbook

XLSX = "/Volumes/Java/Projects/bipros-eppm/docs/ActualData/1. Daily Data-Khasab  Jan, Feb, Mar 2026.xlsx"
OUT_JSON = "/tmp/khasab-dpr-parsed.json"
OUT_REPORT = "/tmp/khasab-dpr-validation_report.md"

SUPERVISOR_MAP = {
    "Mohd Ismaila": "ismaila",
    "Md Saiffuddin": "saiffuddin",
    "Illayaraja": "illayaraja",
    "K. Barman": "kbarman",
    "Vijaykumar": "vijaykumar",
    "VijayKumar": "vijaykumar",
    "Parvaiz": "parvaiz",
    "Sanjar Alam": "sanjar",
    "Anirban Datta": "anirban",
    # everything else → unknown bucket
}

SHEETS = ["Jan-2026", "Feb-2026", "March-2026"]

def shift(d):
    if d is None: return None
    if isinstance(d, str):
        try: d = datetime.fromisoformat(d)
        except: return None
    return (d + relativedelta(years=1)).date().isoformat()

wb = load_workbook(XLSX, data_only=True)
unknown_sups = set()
unknown_acts = set()
total_rows = 0
groups = defaultdict(lambda: {"manpower": [], "equipment": [], "material": [], "subcontractor": []})

for sheet in SHEETS:
    ws = wb[sheet]
    # Headers at row 4, data starts row 5 (per exploration)
    for row in ws.iter_rows(min_row=5, values_only=True):
        if not row or not row[1]:  # row[1] is Date column (col B)
            continue
        total_rows += 1
        date = shift(row[1])
        site = row[2]
        activity_code = (row[7] or "").strip() if row[7] else ""
        unit = row[8]
        qty_exec = row[9]
        sup_name = (row[10] or "").strip() if row[10] else ""
        mp_role = row[11]
        mp_count = row[12]
        mp_hours = row[13]
        mp_rate = row[14]
        eq_name = row[16]
        eq_count = row[17]
        eq_hours = row[18]
        eq_rate = row[19]
        mat_desc = row[21]
        mat_unit = row[22]
        mat_qty = row[23]
        mat_rate = row[24]
        sub_name = row[26]
        sub_desc = row[27]
        sub_unit = row[28]
        sub_qty = row[29]
        sub_rate = row[30]

        if sup_name and sup_name not in SUPERVISOR_MAP:
            unknown_sups.add(sup_name)
        if activity_code:
            unknown_acts.add(activity_code)  # collected; whittled later

        username = SUPERVISOR_MAP.get(sup_name, None)
        if not username or not date or not activity_code:
            continue

        key = (date, activity_code, username)
        g = groups[key]
        g["date"] = date
        g["activity_code"] = activity_code
        g["supervisor_username"] = username
        g["site"] = site
        g["unit"] = unit
        g["qty_executed"] = qty_exec or 0
        if mp_role and mp_count:
            g["manpower"].append({"role": mp_role, "count": mp_count,
                                   "hours": mp_hours or 0, "rate": mp_rate or 0})
        if eq_name and eq_count:
            g["equipment"].append({"name": eq_name, "count": eq_count,
                                    "hours": eq_hours or 0, "rate": eq_rate or 0})
        if mat_desc and mat_qty:
            g["material"].append({"desc": mat_desc, "unit": mat_unit,
                                   "qty": mat_qty, "rate": mat_rate or 0})
        if sub_name and sub_qty:
            g["subcontractor"].append({"name": sub_name, "desc": sub_desc,
                                        "unit": sub_unit, "qty": sub_qty, "rate": sub_rate or 0})

dprs = list(groups.values())

with open(OUT_JSON, "w") as f:
    json.dump(dprs, f, indent=2, default=str)

# Validation report
with open(OUT_REPORT, "w") as f:
    f.write(f"# Khasab DPR parse validation\n\n")
    f.write(f"- Total source rows scanned: **{total_rows}**\n")
    f.write(f"- Unique DPR groups produced: **{len(dprs)}**\n")
    f.write(f"- Aggregation collapse ratio: {total_rows / max(len(dprs),1):.1f} source rows per DPR\n\n")
    f.write(f"## Unknown supervisors ({len(unknown_sups)})\n\n")
    for s in sorted(unknown_sups):
        f.write(f"- `{s}`\n")
    f.write(f"\n## Activity codes found ({len(unknown_acts)})\n\n")
    for a in sorted(unknown_acts):
        f.write(f"- `{a}`\n")
    f.write(f"\n## DPR counts per month\n\n")
    by_month = defaultdict(int)
    for d in dprs:
        by_month[d["date"][:7]] += 1
    for m in sorted(by_month):
        f.write(f"- {m}: {by_month[m]}\n")

print(f"Wrote {OUT_JSON} ({len(dprs)} DPRs)")
print(f"Wrote {OUT_REPORT}")
```

- [ ] **Step 3.3: Run the parser**

```bash
/tmp/xlsx_venv/bin/python /tmp/parse_khasab.py
```

Expected: `Wrote /tmp/khasab-dpr-parsed.json (N DPRs)` where N is in 800–2,000 range.

- [ ] **Step 3.4: Inspect validation report**

```bash
cat /tmp/khasab-dpr-validation_report.md
```

Review: unknown supervisor names (should be small list — anyone not in the 8 canonical), activity-code list (used to verify WBS coverage in Task 6), DPR counts per month.

- [ ] **Step 3.5: Append to log + GATE 3**

Pause: "Phase 3 produced N DPRs from M source rows. Unknown supervisors: [list]. Unknown activity codes: [list]. Proceed to Phase 4?"

---

## Task 4: Phase 4 — Create 12 users

**Files:** none new; mutates `public.users`, `public.role_permissions`, `public.refresh_tokens`

- [ ] **Step 4.1: Refresh admin token**

```bash
TOKEN=$(curl -sS -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | jq -r '.data.token')
test -n "$TOKEN" && echo "token len=${#TOKEN}"
```

- [ ] **Step 4.2: Dispatch reviewer agent — verify CM role enum**

```
Reviewer agent (Explore):
"Find the exact Spring role enum / Role.name that grants CM-tier DBS access
in the bipros-dbs and bipros-security modules. Specifically: what role does
DbsFinancialTool check for the CM level? Is it PROJECT_CONTROL,
CONSTRUCTION_MANAGER, SITE_MANAGER, or something else? Read RolePermissionMatrix
and the DBS tier handling code. Report the exact string."
```

Pause for reviewer response before proceeding. If the answer differs from `PROJECT_CONTROL`, adjust Step 4.3 accordingly.

- [ ] **Step 4.3: Create users (loop)**

```bash
USERS_JSON=$(cat <<'JSON'
[
  {"u":"ravi","n":"RAVI","r":"PROJECT_MANAGER","reports_to":null},
  {"u":"rahul","n":"Rahul","r":"PROJECT_CONTROL","reports_to":"ravi"},
  {"u":"hemendrase","n":"HemendraSE","r":"SITE_ENGINEER","reports_to":"rahul"},
  {"u":"subratse","n":"SubratSE","r":"SITE_ENGINEER","reports_to":"rahul"},
  {"u":"anirban","n":"Anirban Datta","r":"SUPERVISOR","reports_to":"hemendrase"},
  {"u":"illayaraja","n":"Illayaraja","r":"SUPERVISOR","reports_to":"hemendrase"},
  {"u":"kbarman","n":"K. Barman","r":"SUPERVISOR","reports_to":"hemendrase"},
  {"u":"parvaiz","n":"Parvaiz","r":"SUPERVISOR","reports_to":"hemendrase"},
  {"u":"saiffuddin","n":"Md Saiffuddin","r":"SUPERVISOR","reports_to":"subratse"},
  {"u":"ismaila","n":"Mohd Ismaila","r":"SUPERVISOR","reports_to":"subratse"},
  {"u":"sanjar","n":"Sanjar Alam","r":"SUPERVISOR","reports_to":"subratse"},
  {"u":"vijaykumar","n":"VijayKumar","r":"SUPERVISOR","reports_to":"subratse"}
]
JSON
)

mkdir -p /tmp/khasab-users
echo "$USERS_JSON" > /tmp/khasab-users/spec.json

for u in $(jq -r '.[].u' /tmp/khasab-users/spec.json); do
  name=$(jq -r ".[] | select(.u==\"$u\") | .n" /tmp/khasab-users/spec.json)
  role=$(jq -r ".[] | select(.u==\"$u\") | .r" /tmp/khasab-users/spec.json)
  resp=$(curl -sS -X POST http://localhost:8080/v1/users \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$u\",\"email\":\"$u@bipros.test\",\"firstName\":\"$name\",\"password\":\"Password@123\",\"roles\":[\"$role\"]}")
  uid=$(echo "$resp" | jq -r '.data.id // empty')
  echo "{\"u\":\"$u\",\"id\":\"$uid\"}" >> /tmp/khasab-users/ids.jsonl
done
cat /tmp/khasab-users/ids.jsonl
```

Expected: 12 lines with non-empty `id` UUIDs.

- [ ] **Step 4.4: Verify each user can login**

```bash
for u in ravi rahul hemendrase subratse anirban illayaraja kbarman parvaiz saiffuddin ismaila sanjar vijaykumar; do
  status=$(curl -sS -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/v1/auth/login \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$u\",\"password\":\"Password@123\"}")
  echo "$u: $status"
done
```

Expected: every user returns `200`.

- [ ] **Step 4.5: Persist user ID registry**

```bash
jq -s 'map({(.u): .id}) | add' /tmp/khasab-users/ids.jsonl > /tmp/khasab-state.json
cat /tmp/khasab-state.json
```

- [ ] **Step 4.6: Append to log + GATE 4**

Pause: "Phase 4 — 12 users created, all login OK. Proceed to Phase 5 (project + WBS)?"

---

## Task 5: Phase 5a — Create project

**Files:** none new; mutates `project.projects`

- [ ] **Step 5.1: POST project**

```bash
RAVI_ID=$(jq -r .ravi /tmp/khasab-state.json)
PROJECT_RESP=$(curl -sS -X POST http://localhost:8080/v1/projects \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"code\": \"KHASAB-2026\",
    \"name\": \"Khasab Road Project 2026\",
    \"epsParentId\": \"e38edde8-b6cb-4d2c-8e16-72a8336e7c0a\",
    \"startDate\": \"2026-01-01\",
    \"endDate\": \"2026-12-31\",
    \"contractValue\": 50000000,
    \"currencyCode\": \"INR\",
    \"ownerId\": \"$RAVI_ID\"
  }")
PROJECT_ID=$(echo "$PROJECT_RESP" | jq -r '.data.id')
echo "PROJECT_ID=$PROJECT_ID"
jq --arg p "$PROJECT_ID" '. + {projectId: $p}' /tmp/khasab-state.json > /tmp/khasab-state.json.new
mv /tmp/khasab-state.json.new /tmp/khasab-state.json
```

Expected: non-empty UUID. Persist to state file.

- [ ] **Step 5.2: Verify Section G auto-seed (gotcha #7)**

```bash
sleep 2  # listener async
curl -sS "http://localhost:8080/v1/projects/$PROJECT_ID/general-expenses/plan-items" \
  -H "Authorization: Bearer $TOKEN" | jq '.data | length'
```

Expected: `20`.

- [ ] **Step 5.3: Append to log (no gate yet — WBS in Task 6)**

---

## Task 6: Phase 5b — Build WBS + activities

**Files:** none new; mutates `project.wbs_nodes`, `project.activities`

- [ ] **Step 6.1: Dispatch reviewer agent — verify activity-code coverage**

```
Reviewer agent (Explore):
"Read /tmp/khasab-dpr-validation_report.md and extract the unique activity-code
list. Cross-reference each against resource.work_activities table via psql
(SELECT code FROM resource.work_activities). For each code in the Khasab data
that is NOT in the work_activities master, report it as a 'must add to master
first' item. Output: { existing: [...], missing: [...] }."
```

- [ ] **Step 6.2: Add missing work-activities to the master (if any)**

If reviewer reports missing codes, for each:

```bash
curl -sS -X POST http://localhost:8080/v1/work-activities \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"code\":\"<code>\",\"name\":\"<derived from DBS workbook description>\",\"unit\":\"<from data>\"}"
```

- [ ] **Step 6.3: Create WBS tree (10 nodes total)**

```bash
PROJECT_ID=$(jq -r .projectId /tmp/khasab-state.json)

WBS_TREE='[
  {"code":"1.0","name":"Preliminaries","parent":null},
  {"code":"1.3","name":"Soil Investigation","parent":"1.0"},
  {"code":"2.0","name":"Sub-structure","parent":null},
  {"code":"2.3","name":"Bored Cast In-Situ Piling","parent":"2.0"},
  {"code":"2.4","name":"Pile Cap","parent":"2.0"},
  {"code":"2.6","name":"Pier","parent":"2.0"},
  {"code":"2.7","name":"Abutment","parent":"2.0"},
  {"code":"3.0","name":"Super-structure","parent":null},
  {"code":"3.2","name":"Concrete RCC","parent":"3.0"},
  {"code":"5.0","name":"Bearings","parent":null},
  {"code":"9.0","name":"Approach Slab","parent":null},
  {"code":"13.0","name":"Drainage","parent":null},
  {"code":"18.0","name":"Pavement","parent":null}
]'
echo "$WBS_TREE" > /tmp/khasab-wbs-spec.json

declare -A WBS_IDS
for code in $(jq -r '.[].code' /tmp/khasab-wbs-spec.json); do
  name=$(jq -r ".[] | select(.code==\"$code\") | .name" /tmp/khasab-wbs-spec.json)
  parent_code=$(jq -r ".[] | select(.code==\"$code\") | .parent // empty" /tmp/khasab-wbs-spec.json)
  parent_id="${WBS_IDS[$parent_code]:-null}"
  body="{\"code\":\"$code\",\"name\":\"$name\""
  [ -n "$parent_id" ] && [ "$parent_id" != "null" ] && body+=", \"parentId\":\"$parent_id\""
  body+="}"
  resp=$(curl -sS -X POST "http://localhost:8080/v1/projects/$PROJECT_ID/wbs" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$body")
  wid=$(echo "$resp" | jq -r '.data.id')
  WBS_IDS[$code]="$wid"
  echo "$code -> $wid"
done

# persist
jq -n --argjson m "$(declare -p WBS_IDS | sed 's/declare -A WBS_IDS=//;s/^(\(.*\))$/\1/' | jq -R 'split(" ") | map(capture("\\[(?<k>[^]]+)\\]=\"(?<v>[^\"]+)\""))| from_entries')" '.' > /tmp/khasab-wbs-ids.json
```

(Note: the bash → jq conversion of declare -A is brittle; fall back to writing IDs to jsonl line-by-line if needed.)

- [ ] **Step 6.4: Create activities — one per (WBS leaf, activity-code-family)**

For each known activity code in the Khasab data, create one activity on its corresponding WBS leaf:

```bash
PROJECT_ID=$(jq -r .projectId /tmp/khasab-state.json)
LEAF_BY_PREFIX='{
  "1.3.5":"1.3", "2.3.6":"2.3", "2.4.6":"2.4", "2.6.6":"2.6", "2.7.6":"2.7",
  "3.2.6":"3.2", "5.1.7":"5.0", "9.1.6":"9.0", "13.1.7":"13.0", "18.3.6":"18.0"
}'
echo "$LEAF_BY_PREFIX" > /tmp/khasab-leaf-prefix.json

# read codes from the parser output
for code in $(jq -r '.[].activity_code' /tmp/khasab-dpr-parsed.json | sort -u); do
  # find matching prefix
  prefix=$(echo "$code" | grep -oE '^[0-9]+\.[0-9]+\.[0-9]+' || echo "")
  leaf=$(jq -r --arg p "$prefix" '.[$p] // empty' /tmp/khasab-leaf-prefix.json)
  [ -z "$leaf" ] && { echo "SKIP $code (no leaf)"; continue; }
  leaf_id=$(jq -r --arg c "$leaf" '.[$c]' /tmp/khasab-wbs-ids.json)
  # name + unit from validation report (fall back to "Activity $code" and "nos")
  body="{\"code\":\"$code\",\"name\":\"Activity $code\",\"wbsId\":\"$leaf_id\",\"unit\":\"nos\",\"plannedStart\":\"2026-01-01\",\"plannedFinish\":\"2026-12-31\",\"type\":\"TASK_DEPENDENT\"}"
  resp=$(curl -sS -X POST "http://localhost:8080/v1/projects/$PROJECT_ID/activities" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$body")
  aid=$(echo "$resp" | jq -r '.data.id // empty')
  echo "{\"code\":\"$code\",\"id\":\"$aid\",\"wbsLeaf\":\"$leaf\"}" >> /tmp/khasab-activities.jsonl
done
wc -l /tmp/khasab-activities.jsonl
```

Expected: line count equals the count of unique activity codes from the validation report.

- [ ] **Step 6.5: Verify WBS in UI**

```
mcp__plugin_playwright_playwright__browser_navigate
  url: http://localhost:3000/projects/<PROJECT_ID>/wbs

mcp__plugin_playwright_playwright__browser_take_screenshot
  filename: frontend/e2e/.artifacts/screenshots/2026-05-24-phase5-wbs-tree.png
```

- [ ] **Step 6.6: GATE 5**

Pause: "Phase 5 — project + WBS (13 nodes) + N activities created. Proceed to Phase 6 (master data audit)?"

---

## Task 7: Phase 6 — Master data audit + augment

**Files:** none new; mutates `resource.manpower_rate_masters` / `equipment_rate_masters` / `material_rate_masters` (insert-only)

- [ ] **Step 7.1: Audit existing rate masters**

```bash
psql -h 127.0.0.1 -U bipros -d bipros -c "
SELECT category, name, rate, unit FROM resource.manpower_rate_masters ORDER BY name;"

psql -h 127.0.0.1 -U bipros -d bipros -c "
SELECT category, name, rate, unit FROM resource.equipment_rate_masters ORDER BY name;"

psql -h 127.0.0.1 -U bipros -d bipros -c "
SELECT category, name, rate, unit FROM resource.material_rate_masters ORDER BY name;"
```

Capture which of the spec's 10 manpower roles, 6 equipment, 5 materials are already present.

- [ ] **Step 7.2: Add missing manpower rates (loop)**

For each role missing (per Step 7.1):

```bash
curl -sS -X POST http://localhost:8080/v1/manpower-rate-master \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Helper","grade":"A","rate":500,"unit":"DAY","currencyCode":"INR"}'
```

Repeat for: Mason 800, Carpenter 800, Steel Fixer 900, Rigger 900, Scaffolder 900, Bankman 700, Chargehand 1000, Foreman 1200, Supervisor 1500.

- [ ] **Step 7.3: Add missing equipment rates (loop)**

```bash
for pair in "Vibrator:500" "Concrete Mixer:2000" "Wheel Loader:3000" "Truck:4000" "Excavator:5000" "Crane:8000"; do
  name="${pair%:*}"
  rate="${pair##*:}"
  curl -sS -X POST http://localhost:8080/v1/equipment-rate-master \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"$name\",\"make\":\"Generic\",\"rate\":$rate,\"unit\":\"DAY\",\"currencyCode\":\"INR\"}"
done
```

- [ ] **Step 7.4: Add missing material rates**

```bash
curl -sS -X POST http://localhost:8080/v1/material-rate-master \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Cement OPC 43","unit":"kg","rate":10,"currencyCode":"INR"}'
# Steel Fe500 70/kg, Aggregate 20mm 20/cum, Sand 15/cum, Water 0.50/litre
```

- [ ] **Step 7.5: Re-query and persist ID registry**

```bash
psql -h 127.0.0.1 -U bipros -d bipros -At -c "
SELECT json_build_object(
  'manpower', (SELECT json_object_agg(name, json_build_object('id', id, 'rate', rate)) FROM resource.manpower_rate_masters),
  'equipment', (SELECT json_object_agg(name, json_build_object('id', id, 'rate', rate)) FROM resource.equipment_rate_masters),
  'material', (SELECT json_object_agg(name, json_build_object('id', id, 'rate', rate)) FROM resource.material_rate_masters)
);" > /tmp/khasab-rate-masters.json
cat /tmp/khasab-rate-masters.json | jq .
```

- [ ] **Step 7.6: GATE 6**

Pause: "Phase 6 — master data audited and augmented. Proceed to Phase 7 (subcontractors)?"

---

## Task 8: Phase 7 — Subcontractor setup

**Files:** none new; mutates `resource.sub_contractor_masters` + mapping table

- [ ] **Step 8.1: Create 2 generic subcontractors**

```bash
for sc in "Generic-Sub-A" "Generic-Sub-B"; do
  resp=$(curl -sS -X POST http://localhost:8080/v1/sub-contractors \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"$sc\",\"contactEmail\":\"$sc@bipros.test\",\"defaultRate\":600,\"rateUnit\":\"DAY\"}")
  echo "$resp" | jq -r '.data.id'
done
```

- [ ] **Step 8.2: Persist IDs**

```bash
psql -h 127.0.0.1 -U bipros -d bipros -At -c "
SELECT json_object_agg(name, id) FROM resource.sub_contractor_masters;" > /tmp/khasab-subs.json
```

- [ ] **Step 8.3: GATE 7**

Pause: "Phase 7 — 2 subcontractors created. Proceed to Phase 8 (productivity norms)?"

---

## Task 9: Phase 8 — Productivity norms (bulk)

**Files:** none new; mutates `resource.productivity_norms`

- [ ] **Step 9.1: Build the bulk payload**

```bash
# Defaults table from spec section 8
DEFAULTS_JSON=$(cat <<'JSON'
[
  {"resourceType":"MANPOWER","activityFamily":"Excavation","role":"Helper","baseUnitsPerDay":2,"unit":"cum"},
  {"resourceType":"MANPOWER","activityFamily":"Pavement","role":"Helper","baseUnitsPerDay":5,"unit":"sqm"},
  {"resourceType":"MANPOWER","activityFamily":"Concreting","role":"Mason","baseUnitsPerDay":3,"unit":"cum"},
  {"resourceType":"MANPOWER","activityFamily":"Formwork","role":"Carpenter","baseUnitsPerDay":4,"unit":"sqm"},
  {"resourceType":"EQUIPMENT","activityFamily":"Excavation","name":"Excavator","baseUnitsPerDay":50,"unit":"cum"},
  {"resourceType":"EQUIPMENT","activityFamily":"Excavation","name":"Wheel Loader","baseUnitsPerDay":80,"unit":"cum"},
  {"resourceType":"EQUIPMENT","activityFamily":"Concreting","name":"Concrete Mixer","baseUnitsPerDay":10,"unit":"cum"}
]
JSON
)
```

The actual `POST /v1/productivity-norms/bulk` payload shape needs to match the entity. Reviewer agent below will get the actual schema.

- [ ] **Step 9.2: Dispatch reviewer agent — verify productivity-norm payload shape**

```
Reviewer agent (Explore):
"Read /backend/bipros-resource/src/main/java/com/bipros/resource/presentation/controller/ProductivityNormController.java
and the related Create*Request DTOs. What is the exact payload shape for
POST /v1/productivity-norms/bulk? Specifically: required fields, field names,
how to specify the workActivityId (by code or UUID?), and the difference between
default (resourceType only) vs per-resource override (resourceId)."
```

Adjust Step 9.3 payload structure per reviewer's reply.

- [ ] **Step 9.3: POST defaults**

```bash
curl -sS -X POST http://localhost:8080/v1/productivity-norms/bulk \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d @/tmp/khasab-norms-defaults.json
```

(Payload built per reviewer's schema confirmation.)

- [ ] **Step 9.4: Derive top-10 per-activity overrides from Excel data**

```bash
# Get top-10 activity codes by row volume
jq -r 'group_by(.activity_code) | map({code:.[0].activity_code, n:length}) | sort_by(.n) | reverse | .[:10] | .[].code' \
  /tmp/khasab-dpr-parsed.json
```

For each, compute `qty_executed / sum(manpower.count)` averaged across DPRs for that code → that's the empirical productivity. Round to 1 decimal. Reviewer cross-checks against defaults (±30% sanity).

- [ ] **Step 9.5: POST per-activity overrides**

(Built per the override results.)

- [ ] **Step 9.6: Verify norms in UI**

```
mcp__plugin_playwright_playwright__browser_navigate
  url: http://localhost:3000/productivity-norms

mcp__plugin_playwright_playwright__browser_take_screenshot
  filename: frontend/e2e/.artifacts/screenshots/2026-05-24-phase8-norms.png
```

- [ ] **Step 9.7: GATE 8**

Pause: "Phase 8 — N default norms + M overrides created. Proceed to Phase 9 (DPR import)?"

---

## Task 10: Phase 9.1 — Lock all activities

**Files:** none new; mutates `project.activities.status`

- [ ] **Step 10.1: Lock loop**

```bash
PROJECT_ID=$(jq -r .projectId /tmp/khasab-state.json)
for aid in $(jq -r '.id' /tmp/khasab-activities.jsonl); do
  curl -sS -X POST "http://localhost:8080/v1/projects/$PROJECT_ID/activities/$aid/lock" \
    -H "Authorization: Bearer $TOKEN" -o /dev/null -w "%{http_code} "
done
echo
```

Expected: all 200.

- [ ] **Step 10.2: Verify all locked in DB**

```bash
psql -h 127.0.0.1 -U bipros -d bipros -c "
SELECT status, COUNT(*) FROM project.activities GROUP BY status;"
```

Expected: all activities show locked status (e.g. `LOCKED` or `BASELINED`).

---

## Task 11: Phase 9.2 — Import Jan-2026 DPRs

**Files:** none new; mutates `project.daily_progress_reports`, dpr_manpower/equipment/material/subcontractor

- [ ] **Step 11.1: Build Jan-2026-only payload**

```bash
jq '[.[] | select(.date | startswith("2026-01"))]' /tmp/khasab-dpr-parsed.json > /tmp/khasab-jan-dprs.json
echo "Jan DPRs: $(jq length /tmp/khasab-jan-dprs.json)"
```

- [ ] **Step 11.2: Resolve activity/user/rate IDs in each DPR**

A small Python helper joins `khasab-jan-dprs.json` with `khasab-activities.jsonl` + `khasab-users/ids.jsonl` + `khasab-rate-masters.json` and produces a fully resolved payload at `/tmp/khasab-jan-dprs-resolved.json` ready for POST.

```python
# /tmp/resolve_dpr_ids.py
import json
INPUT_FILE = "/tmp/khasab-jan-dprs.json"
OUTPUT_FILE = "/tmp/khasab-jan-dprs-resolved.json"
acts = {l["code"]: l["id"] for l in (json.loads(line) for line in open("/tmp/khasab-activities.jsonl"))}
users = {l["u"]: l["id"] for l in (json.loads(line) for line in open("/tmp/khasab-users/ids.jsonl"))}
rates = json.load(open("/tmp/khasab-rate-masters.json"))
dprs = json.load(open(INPUT_FILE))

resolved = []
for d in dprs:
    act_id = acts.get(d["activity_code"])
    user_id = users.get(d["supervisor_username"])
    if not act_id or not user_id:
        continue
    mp = [{**m, "manpowerRoleRateId": rates["manpower"].get(m["role"], {}).get("id")}
          for m in d["manpower"]]
    eq = [{**e, "equipmentRoleVariantId": rates["equipment"].get(e["name"], {}).get("id")}
          for e in d["equipment"]]
    mat = [{**m, "materialRoleVariantId": rates["material"].get(m["desc"], {}).get("id")}
          for m in d["material"]]
    resolved.append({
        "reportDate": d["date"],
        "activityId": act_id,
        "reportedByUserId": user_id,
        "qtyExecuted": d["qty_executed"],
        "unit": d["unit"],
        "manpower": mp,
        "equipment": eq,
        "material": mat
    })
json.dump(resolved, open(OUTPUT_FILE, "w"), default=str, indent=2)
print(f"Resolved {len(resolved)} / {len(dprs)} DPRs")
```

Run:

```bash
/tmp/xlsx_venv/bin/python /tmp/resolve_dpr_ids.py
```

- [ ] **Step 11.3: POST in batches of 25**

```bash
PROJECT_ID=$(jq -r .projectId /tmp/khasab-state.json)
TOTAL=$(jq length /tmp/khasab-jan-dprs-resolved.json)
SUCCESS=0; FAIL=0
for i in $(seq 0 25 $TOTAL); do
  batch=$(jq ".[$i:$((i+25))]" /tmp/khasab-jan-dprs-resolved.json)
  resp=$(curl -sS -X POST "http://localhost:8080/v1/projects/$PROJECT_ID/dpr/bulk" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$batch")
  ok=$(echo "$resp" | jq '[.data[] | select(.id != null)] | length')
  SUCCESS=$((SUCCESS + ok))
  FAIL=$((FAIL + 25 - ok))
  echo "batch $i: $ok ok"
done
echo "TOTAL: $SUCCESS success, $FAIL fail"
```

- [ ] **Step 11.4: Verify DB row count**

```bash
psql -h 127.0.0.1 -U bipros -d bipros -c "
SELECT COUNT(*) AS jan_dprs FROM project.daily_progress_reports
WHERE report_date BETWEEN '2026-01-01' AND '2026-01-31';"
```

---

## Task 12: Phase 9.3 — Jan spot-check (GATE 9 — critical)

**Files:** none new; read-only

- [ ] **Step 12.1: Pick 3 random Jan DPRs from DB**

```bash
psql -h 127.0.0.1 -U bipros -d bipros -At -c "
SELECT id, report_date, activity_id, reported_by_user_id, qty_executed, unit
FROM project.daily_progress_reports
WHERE report_date BETWEEN '2026-01-01' AND '2026-01-31'
ORDER BY random() LIMIT 3;"
```

- [ ] **Step 12.2: Show user the DB values + ask them to cross-check vs Excel**

Present table: date | activity_code | supervisor | qty | unit. User verifies against Excel by row.

- [ ] **Step 12.3: GATE 9 — explicit user OK before Feb+March import**

Pause: "Spot-check passed: row 1, row 2, row 3 all match Excel. Proceed to import Feb + March (~17,000 DPRs)?"

---

## Task 13: Phase 9.4 — Import Feb + March DPRs

**Files:** none new; mutates `project.daily_progress_reports` + DPR sub-tables

- [ ] **Step 13.1: Build Feb-2026 + Mar-2026 payloads**

```bash
jq '[.[] | select(.date | startswith("2026-02"))]' /tmp/khasab-dpr-parsed.json > /tmp/khasab-feb-dprs.json
jq '[.[] | select(.date | startswith("2026-03"))]' /tmp/khasab-dpr-parsed.json > /tmp/khasab-mar-dprs.json
```

- [ ] **Step 13.2: Resolve and POST Feb**

Same script as Task 11.2-11.3, parameterized for `/tmp/khasab-feb-dprs.json`.

- [ ] **Step 13.3: Resolve and POST March**

Same again for March.

- [ ] **Step 13.4: Verify total DPR count**

```bash
psql -h 127.0.0.1 -U bipros -d bipros -c "
SELECT date_part('month', report_date) AS month, COUNT(*)
FROM project.daily_progress_reports
WHERE report_date BETWEEN '2026-01-01' AND '2026-03-31'
GROUP BY 1 ORDER BY 1;"
```

Expected: 3 months, counts roughly matching the parser's DPR-per-month report.

---

## Task 14: Phase 9.5 — Material Consumption Logs + DPR re-fire

**Files:** none new; mutates `resource.material_consumption_logs`; re-PUTs DPRs

- [ ] **Step 14.1: Identify DPRs with material consumption**

```bash
jq '[.[] | select(.material | length > 0)]' /tmp/khasab-dpr-parsed.json > /tmp/khasab-dprs-with-material.json
echo "DPRs with material: $(jq length /tmp/khasab-dprs-with-material.json)"
```

- [ ] **Step 14.2: POST MCLs (one per material line per DPR)**

```bash
PROJECT_ID=$(jq -r .projectId /tmp/khasab-state.json)
# For each DPR with material, for each material line: POST /v1/projects/{pid}/material-consumption
# (Python helper to keep this readable.)
/tmp/xlsx_venv/bin/python /tmp/post_mcls.py
```

(`post_mcls.py` reads `/tmp/khasab-dprs-with-material.json` and POSTs each material line.)

- [ ] **Step 14.3: Re-PUT each parent DPR (runbook gotcha #2 / Finding 5)**

For every DPR id touched by MCL, fetch its current state and PUT it back unchanged. This triggers `BoqActualRateRecalcListener` since `MaterialConsumptionLoggedEvent` doesn't fire it.

```bash
for dpr_id in $(jq -r '.[].id' /tmp/mcl-touched-dprs.json); do
  current=$(curl -sS "http://localhost:8080/v1/projects/$PROJECT_ID/dpr/$dpr_id" \
    -H "Authorization: Bearer $TOKEN" | jq '.data')
  curl -sS -X PUT "http://localhost:8080/v1/projects/$PROJECT_ID/dpr/$dpr_id" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" -d "$current" -o /dev/null -w "%{http_code} "
done
```

- [ ] **Step 14.4: Force DBS range recompute**

```bash
PROJECT_ID=$(jq -r .projectId /tmp/khasab-state.json)
curl -sS -X POST "http://localhost:8080/v1/projects/$PROJECT_ID/dbs/recompute-range?from=2026-01-24&to=2026-03-29" \
  -H "Authorization: Bearer $TOKEN"
```

Expected: 200 OK with summary count.

- [ ] **Step 14.5: GATE 10**

Pause: "Phase 9 complete — total DPRs N, MCLs M, DBS recomputed for 65 days. Proceed to Phase 10 (resource planning validation)?"

---

## Task 15: Phase 10–11 — Validation pass (resource plan + 12 screens)

**Files:** 12 screenshots under `frontend/e2e/.artifacts/screenshots/`

- [ ] **Step 15.1: Resource planning spot-check (5 activities)**

```bash
PROJECT_ID=$(jq -r .projectId /tmp/khasab-state.json)
for aid in $(jq -r '.id' /tmp/khasab-activities.jsonl | shuf -n 5); do
  echo "=== activity $aid ==="
  curl -sS "http://localhost:8080/v1/projects/$PROJECT_ID/role-assignments?activityId=$aid" \
    -H "Authorization: Bearer $TOKEN" | jq '.data | length'
  psql -h 127.0.0.1 -U bipros -d bipros -At -c "
    SELECT SUM(actual_cost) FROM project.daily_progress_reports
    WHERE activity_id = '$aid';"
done
```

- [ ] **Step 15.2: Per-screen Playwright sweep**

For each of the 12 screens in the spec's matrix (Executive dashboard, Project overview, WBS tree, DPR list, Productivity, Capacity util, DBS Supervisor/Engineer/CM/PM, Field summary):

```
mcp__plugin_playwright_playwright__browser_navigate url: <URL>
mcp__plugin_playwright_playwright__browser_take_screenshot filename: <path>
```

For each, compare displayed total against SQL ground-truth query (per spec Phase 11 matrix).

- [ ] **Step 15.3: Record results in markdown log + GATE 11**

Pause: "Phase 10–11 validation done. <N> screens match SQL; <M> divergences flagged. Proceed to Phase 12 (AI validation)?"

---

## Task 16: Phase 12 — AI validation (50 questions)

**Files:** `/tmp/ai-ground-truth.json`, `/tmp/ai-results.json`

- [ ] **Step 16.1: Verify BIPROS_AI_KEK is in backend env**

```bash
test -n "$BIPROS_AI_KEK" && \
  curl -sS http://localhost:8080/v1/ai/chat \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"message":"ping","projectId":"'$PROJECT_ID'"}' | jq '.data.responseText'
```

If empty, abort and ask user to set env var.

- [ ] **Step 16.2: Build 50-question test bank**

Create `/tmp/ai-questions.json` with the 50 questions (8 DPR + 6 utilization + 6 productivity + 4 capacity + 6 cost + 5 activity + 4 materials + 6 DBS + 5 cross-domain). For each: `{question, expected_value, sql, tool_expected}`.

- [ ] **Step 16.3: Ground-truth precompute (Pass A)**

```bash
/tmp/xlsx_venv/bin/python /tmp/ai_precompute.py
```

Runs each question's SQL, stores expected value.

- [ ] **Step 16.4: AI grading run (Pass B) with stop-on-repeat**

```bash
/tmp/xlsx_venv/bin/python /tmp/ai_grade.py
```

Walks questions sequentially. After each AI response, append to `/tmp/ai-results.json` with grade. Stop after 3 consecutive identical responses (per `feedback_ai_test_stop_on_repeat` memory).

- [ ] **Step 16.5: Aggregate pass rate**

```bash
jq '[.[] | .grade] | group_by(.) | map({grade:.[0], count:length})' /tmp/ai-results.json
```

- [ ] **Step 16.6: GATE 12**

Pause: "Phase 12 — AI: PASS N, PARTIAL M, FAIL K. Proceed to Phase 13 (findings)?"

---

## Task 17: Phase 13–14 — Findings consolidation + exports

**Files:** `docs/ActualData/exports/khasab-dpr-2026-05-24.csv`, `.xlsx`

- [ ] **Step 17.1: Aggregate findings from logs**

Walk all markdown log sections, extract every "Finding" block, deduplicate, number sequentially starting from 10 (carryover 5/7/8/9 from prior run).

- [ ] **Step 17.2: CSV export**

```bash
mkdir -p docs/ActualData/exports
psql -h 127.0.0.1 -U bipros -d bipros -At --csv -c "
SELECT
  d.report_date,
  u.username AS supervisor,
  a.code AS activity_code,
  d.qty_executed,
  d.unit,
  d.actual_cost
FROM project.daily_progress_reports d
JOIN public.users u ON u.id = d.reported_by_user_id
JOIN project.activities a ON a.id = d.activity_id
ORDER BY d.report_date, a.code;" > docs/ActualData/exports/khasab-dpr-2026-05-24.csv

wc -l docs/ActualData/exports/khasab-dpr-2026-05-24.csv
```

- [ ] **Step 17.3: XLSX export (4 sheets)**

```bash
/tmp/xlsx_venv/bin/python /tmp/export_xlsx.py
```

(Python script reads the CSV + does pivots for `By-Supervisor`, `By-Activity`, `By-Date`.)

---

## Task 18: HTML report generation

**Files:** `docs/dpr-dbs-e2e-execution-log-2026-05-24.html`

- [ ] **Step 18.1: Build HTML from markdown log**

Single-file HTML with embedded CSS, sections per spec section 16. Convert markdown → HTML with a small Python helper (`markdown` lib or `pandoc` if available). Embed screenshots as base64 if total size < 5MB, otherwise as relative paths.

```bash
/tmp/xlsx_venv/bin/python /tmp/build_html_report.py \
  --in docs/dpr-dbs-e2e-test-execution-log-2026-05-24.md \
  --ai-results /tmp/ai-results.json \
  --validation-report /tmp/khasab-dpr-validation_report.md \
  --out docs/dpr-dbs-e2e-execution-log-2026-05-24.html
```

- [ ] **Step 18.2: Sanity check**

```bash
ls -lh docs/dpr-dbs-e2e-execution-log-2026-05-24.html
file docs/dpr-dbs-e2e-execution-log-2026-05-24.html
# Open in browser
open docs/dpr-dbs-e2e-execution-log-2026-05-24.html
```

- [ ] **Step 18.3: GATE 13 — final user OK**

Pause: "HTML report at <path>. Review and confirm. Once OK, I'll restore application.yml and remove disabled-seeder properties."

---

## Task 19: Cleanup — revert application.yml seeder flags

**Files:**
- Modify: `backend/bipros-api/src/main/resources/application.yml` (revert Task 1.5 changes if user wants the env restored to seeder-enabled defaults)

- [ ] **Step 19.1: Ask user — keep flags off (preferred for future E2E runs) or restore?**

Pause: "Should I revert the seeder-disable flags from application.yml, or leave them off so future E2E runs start clean?"

- [ ] **Step 19.2: If revert: undo Step 1.5 changes**

```bash
git diff backend/bipros-api/src/main/resources/application.yml
# show diff, then either commit or revert based on user choice
```

- [ ] **Step 19.3: Update memory if anything noteworthy emerged**

Per `using-superpowers` memory guidance:
- Save any new feedback memory if the user corrected an approach
- Save any new project memory if there's persistent context worth keeping

---

## Task 20: Final summary message to user

- [ ] **Step 20.1: Compose summary**

Short message with: total wall-clock, count of DPRs imported, AI pass rate, finding count (open/closed), HTML report path, backup path for rollback.

- [ ] **Step 20.2: Mark all TaskCreate tasks complete**

---

## Self-Review

**Spec coverage:** All 14 phases mapped. Phase 1 → Task 1. Phase 2 → Task 2. Phase 3 → Task 3. Phase 4 → Task 4. Phase 5a → Task 5; Phase 5b → Task 6. Phase 6 → Task 7. Phase 7 → Task 8. Phase 8 → Task 9. Phase 9.1–9.7 → Tasks 10–14. Phases 10–11 → Task 15. Phase 12 → Task 16. Phase 13–14 → Task 17. HTML report (deliverable) → Task 18. Cleanup + summary (not in spec but needed) → Tasks 19–20.

**Placeholder scan:** All `<placeholder>` instances are runtime values that must be substituted at execution time (e.g. `<PROJECT_ID>` is `$PROJECT_ID` shell variable). One genuine gap: Task 9.4 (top-10 override calculation) — the formula is named but the per-activity values are derived at runtime from data. This is unavoidable but the formula `qty_executed / sum(manpower.count)` is explicit.

**Type consistency:** ID registry uses consistent keys: `projectId`, `khasab-users/ids.jsonl` keys = `u/id`, `khasab-activities.jsonl` keys = `code/id/wbsLeaf`, `khasab-rate-masters.json` nested object — used consistently across Tasks 4–14.

**Known fragility:** Step 6.3 has a brittle bash → jq conversion of `declare -A`; if it fails at execution, fall back to writing IDs to `khasab-wbs-ids.jsonl` line-by-line and `jq -s 'from_entries'` to merge. Documented in step.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-24-fresh-env-and-khasab-import.md`.

Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task (with the 3-agent team-per-phase pattern nested inside), review between tasks, fast iteration. Most aligned with the user's stated agent-team requirement.

2. **Inline Execution** — Execute tasks in this session sequentially. Batches with checkpoints for review. Slower but tighter integration with the brainstorming/spec context already in this thread.

Which approach?
