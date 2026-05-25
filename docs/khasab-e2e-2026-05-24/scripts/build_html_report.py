#!/usr/bin/env python3
"""Build the single consolidated HTML execution report for the
2026-05-24 Khasab E2E run.

Output: docs/dpr-dbs-e2e-execution-log-2026-05-24.html
"""
import json
import os
import subprocess
import datetime
import base64
import html

REPO = "/Volumes/Java/Projects/bipros-eppm"
PROJECT_ID = open("/tmp/khasab/project-id.txt").read().strip()
OUT = f"{REPO}/docs/dpr-dbs-e2e-execution-log-2026-05-24.html"
PSQL = "/Applications/Postgres.app/Contents/Versions/latest/bin/psql"
PG_BASE = ["env", "PGPASSWORD=bipros_dev", PSQL, "-h", "127.0.0.1", "-U", "bipros", "-d", "bipros", "-A", "-t", "-c"]


def q(sql):
    full = sql.replace("$PROJECT_ID", PROJECT_ID)
    out = subprocess.run(PG_BASE + [full], capture_output=True, text=True, timeout=30)
    return out.stdout.strip() if out.returncode == 0 else f"ERR: {out.stderr[:200]}"


def safe_read(path, default=""):
    try:
        return open(path).read()
    except FileNotFoundError:
        return default


def safe_json(path, default=None):
    try:
        return json.load(open(path))
    except (FileNotFoundError, json.JSONDecodeError):
        return default if default is not None else {}


# Gather data
metadata = {
    "generated_at": datetime.datetime.now().isoformat(timespec="seconds"),
    "project_id": PROJECT_ID,
    "project_code": q("SELECT code FROM project.projects WHERE id='$PROJECT_ID'"),
    "project_name": q("SELECT name FROM project.projects WHERE id='$PROJECT_ID'"),
    "dpr_total": q("SELECT COUNT(*) FROM project.daily_progress_reports WHERE project_id='$PROJECT_ID'"),
    "dpr_per_month": q("SELECT string_agg(date_trunc('month', report_date)::date::text || '=' || c::text, '; ' ORDER BY date_trunc('month', report_date)) FROM (SELECT report_date, COUNT(*) c FROM project.daily_progress_reports WHERE project_id='$PROJECT_ID' GROUP BY report_date) sub"),
    "activity_count": q("SELECT COUNT(*) FROM activity.activities WHERE project_id='$PROJECT_ID'"),
    "wbs_count": q("SELECT COUNT(*) FROM project.wbs_nodes WHERE project_id='$PROJECT_ID'"),
    "user_count": q("SELECT COUNT(*) FROM public.users"),
    "team_count": q("SELECT COUNT(*) FROM project.project_team WHERE project_id='$PROJECT_ID'"),
    "manpower_lines": q("SELECT COUNT(*) FROM project.dpr_manpower m JOIN project.daily_progress_reports d ON d.id=m.dpr_id WHERE d.project_id='$PROJECT_ID'"),
    "equipment_lines": q("SELECT COUNT(*) FROM project.dpr_equipment e JOIN project.daily_progress_reports d ON d.id=e.dpr_id WHERE d.project_id='$PROJECT_ID'"),
    "material_lines": q("SELECT COUNT(*) FROM project.dpr_material m JOIN project.daily_progress_reports d ON d.id=m.dpr_id WHERE d.project_id='$PROJECT_ID'"),
    "section_g_items": q("SELECT COUNT(*) FROM dbs.general_expense_plan_item WHERE project_id='$PROJECT_ID'"),
    "dbs_supervisor_rows": q("SELECT COUNT(*) FROM dbs.dbs_daily_supervisor WHERE project_id='$PROJECT_ID'"),
    "dbs_project_rows": q("SELECT COUNT(*) FROM dbs.dbs_daily_project WHERE project_id='$PROJECT_ID'"),
}

ai_results = safe_json("/tmp/ai-results.json", {"total": 0, "pass": 0, "partial": 0, "fail": 0, "results": []})
validation_report = safe_read("/tmp/khasab-dpr-validation_report.md", "(not generated)")
execution_log_md = safe_read(f"{REPO}/docs/dpr-dbs-e2e-test-execution-log-2026-05-24.md", "")

# Findings (carryover + new)
findings = [
    {"n": 5, "status": "open", "title": "BoqActualRateRecalcListener doesn't fire on MaterialConsumptionLoggedEvent",
     "desc": "MCL events don't trigger BOQ actualRate refresh. Workaround: re-PUT parent DPR after MCL. From May-19 run."},
    {"n": 7, "status": "open", "title": "% Achieved tile missing from Supervisor DBS UI",
     "desc": "API returns pctAchieved but frontend tile is missing. From May-19 run."},
    {"n": 8, "status": "open", "title": "CM-tier contributionPct scaled as percentage not fraction",
     "desc": "Returns as -123.1429 (pct) instead of -1.2314 (fraction). API inconsistency. From May-19 run."},
    {"n": 9, "status": "open", "title": "CM-tier missing totalExpense + contribution fields",
     "desc": "API inconsistency vs other tiers. From May-19 run."},
    {"n": 10, "status": "open", "title": "4 Khasab supervisors not in user list (Sohail, Manzar, V.P. Gupta, A.K. Mishra)",
     "desc": "Real data had 12 supervisors; user spec only listed 8. Added 4 extras to capture all DPRs. Affected ~2659 source rows."},
    {"n": 11, "status": "open", "title": "Profile_permissions cascade-deleted from profiles TRUNCATE",
     "desc": "TRUNCATE public.profiles cascaded to wipe 552 profile_permissions rows. Restored from backup. Future cleanups: keep profiles."},
    {"n": 12, "status": "open", "title": "Project DTO field name mismatches",
     "desc": "POST /v1/projects: 'epsParentId' must be 'epsNodeId'; 'startDate'/'endDate' must be 'plannedStartDate'/'plannedFinishDate'; 'contractValue' must be 'originalBudget' and isn't settable via PUT (used SQL)."},
    {"n": 13, "status": "open", "title": "POST /v1/projects/{id}/activities requires projectId in body",
     "desc": "Even though projectId is in the URL path, the request validator rejects without it in the body."},
    {"n": 14, "status": "open", "title": "ProjectRole enum differs from user-role enum",
     "desc": "ProjectRole = {PM, CONSTRUCTION_MANAGER, SITE_MANAGER, ENGINEER, SUPERVISOR, QS, SAFETY}. PROJECT_MANAGER and SITE_ENGINEER (valid user roles) are NOT valid project-team roles."},
    {"n": 15, "status": "open", "title": "DPR validator rejects qtyExecuted=0",
     "desc": "Real Khasab data has 470/3431 DPRs with qty=0 (idle/deployment-only days). Substituted 0.01 + remarks marker to preserve the resource-deployment record."},
    {"n": 16, "status": "open", "title": "Khasab data 33 activity codes don't match 178-row work_activity catalogue",
     "desc": "We created project-scoped activities with bare code strings; no link to work_activity. Productivity-norm seeding deferred."},
    {"n": 17, "status": "open", "title": "Phase 7 (subcontractors) and most of Phase 6 (extra masters) are no-op for Khasab",
     "desc": "Source data has 0 subcontractor rows and 0 material rows. Existing rate masters cover most equipment/manpower; some gaps (Chargehand, bankman, Wheel Loader, Tipper) but not blocking."},
]

# Build HTML
def esc(s):
    return html.escape(str(s)) if s is not None else ""

def md_to_html(md):
    """Very lightweight markdown→HTML. Handles headings, code, tables, lists, paras."""
    out = []
    in_code = False
    in_table = False
    in_list = False
    for line in md.split("\n"):
        if line.startswith("```"):
            in_code = not in_code
            out.append("<pre><code>" if in_code else "</code></pre>")
            continue
        if in_code:
            out.append(esc(line))
            continue
        # Tables
        if "|" in line and line.strip().startswith("|"):
            cells = [c.strip() for c in line.strip("|").split("|")]
            if all(re.match(r"^[-:]+$", c) for c in cells):
                continue  # separator
            if not in_table:
                out.append("<table>")
                in_table = True
            cell_tag = "th" if not out or out[-1] == "<table>" else "td"
            out.append("<tr>" + "".join(f"<{cell_tag}>{esc(c)}</{cell_tag}>" for c in cells) + "</tr>")
            continue
        elif in_table:
            out.append("</table>")
            in_table = False
        # Headings
        if line.startswith("### "):
            out.append(f"<h3>{esc(line[4:])}</h3>")
        elif line.startswith("## "):
            out.append(f"<h2>{esc(line[3:])}</h2>")
        elif line.startswith("# "):
            out.append(f"<h1>{esc(line[2:])}</h1>")
        elif line.startswith("- "):
            if not in_list:
                out.append("<ul>")
                in_list = True
            out.append(f"<li>{esc(line[2:])}</li>")
        else:
            if in_list:
                out.append("</ul>")
                in_list = False
            if line.strip():
                out.append(f"<p>{esc(line)}</p>")
    if in_table:
        out.append("</table>")
    if in_list:
        out.append("</ul>")
    return "\n".join(out)


import re

CSS = """
* { box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #fafafa; color: #1a1a1a; margin: 0; padding: 0; line-height: 1.55; }
.container { max-width: 1280px; margin: 0 auto; padding: 40px 32px; }
h1 { font-size: 2.4rem; margin: 0 0 0.5em; font-weight: 700; letter-spacing: -0.02em; }
h2 { font-size: 1.5rem; margin: 2em 0 0.6em; padding-bottom: 0.4em; border-bottom: 1px solid #e5e5e5; font-weight: 600; }
h3 { font-size: 1.15rem; margin: 1.5em 0 0.4em; font-weight: 600; }
.hero { background: linear-gradient(135deg, #1f2937, #111827); color: white; padding: 40px; border-radius: 16px; margin-bottom: 32px; }
.hero h1 { color: white; }
.hero .meta { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 24px; margin-top: 24px; }
.hero .meta div { background: rgba(255,255,255,0.08); padding: 16px; border-radius: 8px; }
.hero .meta .label { font-size: 0.75rem; opacity: 0.7; text-transform: uppercase; letter-spacing: 0.05em; margin-bottom: 4px; }
.hero .meta .value { font-size: 1.4rem; font-weight: 600; }
.status-badge { display: inline-block; padding: 4px 12px; border-radius: 12px; font-size: 0.8rem; font-weight: 600; }
.status-pass { background: #dcfce7; color: #166534; }
.status-fail { background: #fee2e2; color: #991b1b; }
.status-warn { background: #fef3c7; color: #92400e; }
.status-open { background: #ffedd5; color: #9a3412; }
table { width: 100%; border-collapse: collapse; margin: 12px 0; background: white; border-radius: 8px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.05); }
th, td { padding: 10px 14px; text-align: left; border-bottom: 1px solid #f0f0f0; font-size: 0.95rem; vertical-align: top; }
th { background: #f8f9fa; font-weight: 600; }
tr:last-child td { border-bottom: none; }
pre { background: #1e293b; color: #e2e8f0; padding: 16px; border-radius: 8px; overflow-x: auto; font-size: 0.85rem; line-height: 1.5; }
code { background: #f3f4f6; padding: 2px 6px; border-radius: 4px; font-size: 0.85em; font-family: 'SF Mono', Menlo, monospace; }
pre code { background: transparent; padding: 0; color: inherit; }
.finding { border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px 20px; margin: 12px 0; background: white; }
.finding-title { font-weight: 600; margin-bottom: 6px; }
.finding-meta { font-size: 0.8rem; color: #6b7280; margin-bottom: 8px; }
.ai-row { display: grid; grid-template-columns: 60px 100px 1fr 1fr 80px; gap: 12px; padding: 8px 0; border-bottom: 1px solid #f3f4f6; font-size: 0.88rem; }
.ai-row .id { font-family: monospace; color: #6b7280; }
.ai-row .grade { font-weight: 600; }
.ai-row.PASS .grade { color: #166534; }
.ai-row.PARTIAL .grade { color: #92400e; }
.ai-row.FAIL .grade { color: #991b1b; }
.ai-row.ERROR .grade { color: #6b7280; }
details { background: white; border: 1px solid #e5e5e5; border-radius: 8px; padding: 12px 16px; margin: 12px 0; }
summary { cursor: pointer; font-weight: 600; padding: 4px 0; }
.muted { color: #6b7280; font-size: 0.9rem; }
img.screenshot { max-width: 100%; border: 1px solid #e5e5e5; border-radius: 8px; margin: 12px 0; }
"""

# AI results table rows
ai_html = ""
if ai_results.get("results"):
    ai_html += "<div class='ai-grid'>"
    for r in ai_results["results"]:
        grade_class = r.get("grade", "ERROR")
        ai_html += f"""
        <details class='ai-row {grade_class}'>
          <summary>
            <span class='id'>{esc(r['id'])}</span>
            <span class='grade'>{esc(grade_class)}</span>
            <span class='question'>{esc(r['question'])}</span>
            <span class='expected muted'>exp: {esc(str(r.get('expected', ''))[:40])}</span>
          </summary>
          <div style='margin-top:8px;'>
            <div><strong>Actual:</strong> {esc(r.get('actual', ''))}</div>
            <div class='muted'>Tools: {esc(', '.join(r.get('tool_names', [])))} | {r.get('elapsed_sec', 0)}s</div>
          </div>
        </details>
        """
    ai_html += "</div>"
else:
    ai_html = "<p class='muted'>AI grading not run yet (run /tmp/ai_grade.py after import completes).</p>"

# Findings HTML
findings_html = ""
for f in findings:
    findings_html += f"""
    <div class='finding'>
      <div class='finding-title'>Finding {f['n']} — {esc(f['title'])}</div>
      <div class='finding-meta'>Status: <span class='status-badge status-{f["status"]}'>{f['status'].upper()}</span></div>
      <div>{esc(f['desc'])}</div>
    </div>
    """

# Screenshots
screenshot_dir = f"{REPO}/frontend/e2e/.artifacts/screenshots"
screenshot_html = ""
if os.path.isdir(screenshot_dir):
    for fname in sorted(os.listdir(screenshot_dir)):
        if fname.startswith("2026-05-24-") and fname.endswith(".png"):
            screenshot_html += f"<div><strong>{esc(fname)}</strong><br><img class='screenshot' src='frontend/e2e/.artifacts/screenshots/{esc(fname)}' alt='{esc(fname)}'/></div>"

html_doc = f"""<!DOCTYPE html>
<html lang='en'>
<head>
<meta charset='UTF-8'>
<title>Bipros DPR→DBS E2E — Khasab Real-Data Run — 2026-05-24</title>
<style>{CSS}</style>
</head>
<body>
<div class='container'>

<div class='hero'>
  <div class='muted' style='opacity:0.7; font-size: 0.9rem;'>Bipros EPPM · Execution Log</div>
  <h1>Khasab Real-Data DPR→DBS E2E — 2026-05-24</h1>
  <div class='muted' style='color: rgba(255,255,255,0.7);'>Generated {esc(metadata['generated_at'])}</div>
  <div class='meta'>
    <div><div class='label'>Project</div><div class='value'>{esc(metadata['project_code'])}</div></div>
    <div><div class='label'>DPRs imported</div><div class='value'>{esc(metadata['dpr_total'])}</div></div>
    <div><div class='label'>Activities</div><div class='value'>{esc(metadata['activity_count'])}</div></div>
    <div><div class='label'>WBS nodes</div><div class='value'>{esc(metadata['wbs_count'])}</div></div>
    <div><div class='label'>Users created</div><div class='value'>{esc(metadata['user_count'])}</div></div>
    <div><div class='label'>Manpower lines</div><div class='value'>{esc(metadata['manpower_lines'])}</div></div>
    <div><div class='label'>Equipment lines</div><div class='value'>{esc(metadata['equipment_lines'])}</div></div>
    <div><div class='label'>Section G items</div><div class='value'>{esc(metadata['section_g_items'])}</div></div>
  </div>
</div>

<h2>Run summary</h2>
<table>
<tr><th>Phase</th><th>Status</th><th>Notes</th></tr>
<tr><td>1. DB cleanup + restart</td><td><span class='status-badge status-pass'>PASS</span></td><td>Backed up to /tmp/bipros-backup-2026-05-24.dump (22MB). TRUNCATEd ~190 transactional tables, kept system masters. Restored profile_permissions after cascade hit.</td></tr>
<tr><td>2. Frontend smoke</td><td><span class='status-badge status-pass'>PASS</span></td><td>Admin login + dashboard render + projects page captured.</td></tr>
<tr><td>3. Excel parse</td><td><span class='status-badge status-pass'>PASS</span></td><td>{esc(metadata['dpr_total'])} DPRs parsed from 26,788 source rows. +1y date shift applied. 4 extra supervisors added beyond user spec.</td></tr>
<tr><td>4. User creation</td><td><span class='status-badge status-pass'>PASS</span></td><td>16 users (PM, CM, 2 SEs, 12 supervisors). All login OK.</td></tr>
<tr><td>5a. Project + Section G</td><td><span class='status-badge status-pass'>PASS</span></td><td>KHASAB-2026 created with 20-row Section G auto-seed. Budget set via SQL (PUT didn't expose field).</td></tr>
<tr><td>5b. Team + WBS + Activities</td><td><span class='status-badge status-pass'>PASS</span></td><td>16 team members with reports-to chain; 22 WBS nodes; 33 activities.</td></tr>
<tr><td>6. Master data audit</td><td><span class='status-badge status-warn'>PARTIAL</span></td><td>Used existing masters as-is; missing: Chargehand, bankman, Wheel Loader, Tipper, etc. Logged as Finding 17.</td></tr>
<tr><td>7. Subcontractors</td><td><span class='status-badge status-warn'>N/A</span></td><td>Source data has 0 subcontractor rows — phase skipped.</td></tr>
<tr><td>8. Productivity norms</td><td><span class='status-badge status-warn'>DEFERRED</span></td><td>Khasab activities don't map to work-activity catalogue. Norms via DPR data instead (Finding 16).</td></tr>
<tr><td>9. DPR import + lock</td><td><span class='status-badge status-pass'>PASS</span></td><td>All 33 activities locked. DPRs by month: {esc(metadata['dpr_per_month'])[:200]}</td></tr>
<tr><td>10. Resource planning validation</td><td><span class='status-badge status-warn'>SKIPPED</span></td><td>No role-assignments created (DPR auto-deployment via supervisor identity instead).</td></tr>
<tr><td>11. App screen sweep</td><td><span class='status-badge status-warn'>PARTIAL</span></td><td>Login + dashboard + projects list captured. Per-screen totals validation deferred.</td></tr>
<tr><td>12. AI validation</td><td><span class='status-badge status-{'pass' if ai_results.get('pass', 0) > ai_results.get('fail', 0) else 'warn'}'>{ai_results.get('pass', 0)}/{ai_results.get('total', 50)} PASS</span></td><td>50 questions, {ai_results.get('pass', 0)} pass / {ai_results.get('partial', 0)} partial / {ai_results.get('fail', 0)} fail. {('Aborted early (repeat responses)' if ai_results.get('aborted_early') else '')}</td></tr>
<tr><td>13. Findings</td><td><span class='status-badge status-warn'>{len(findings)} OPEN</span></td><td>4 carried over from May-19; 8 new from this run.</td></tr>
<tr><td>14. CSV/XLSX exports</td><td><span class='status-badge status-pass'>PASS</span></td><td>See deliverables below.</td></tr>
</table>

<h2>Project Metadata</h2>
<table>
<tr><th>Field</th><th>Value</th></tr>
<tr><td>Project ID</td><td><code>{esc(metadata['project_id'])}</code></td></tr>
<tr><td>Project code</td><td>{esc(metadata['project_code'])}</td></tr>
<tr><td>Project name</td><td>{esc(metadata['project_name'])}</td></tr>
<tr><td>Total DPRs</td><td>{esc(metadata['dpr_total'])}</td></tr>
<tr><td>Activities</td><td>{esc(metadata['activity_count'])}</td></tr>
<tr><td>WBS nodes</td><td>{esc(metadata['wbs_count'])}</td></tr>
<tr><td>Users (total)</td><td>{esc(metadata['user_count'])}</td></tr>
<tr><td>Project team members</td><td>{esc(metadata['team_count'])}</td></tr>
<tr><td>Manpower DPR lines</td><td>{esc(metadata['manpower_lines'])}</td></tr>
<tr><td>Equipment DPR lines</td><td>{esc(metadata['equipment_lines'])}</td></tr>
<tr><td>Material DPR lines</td><td>{esc(metadata['material_lines'])}</td></tr>
<tr><td>Section G plan items</td><td>{esc(metadata['section_g_items'])}</td></tr>
<tr><td>DBS supervisor rows</td><td>{esc(metadata['dbs_supervisor_rows'])}</td></tr>
<tr><td>DBS project rows</td><td>{esc(metadata['dbs_project_rows'])}</td></tr>
</table>

<h2>Findings ({len(findings)} open)</h2>
{findings_html}

<h2>AI Validation (50 questions)</h2>
<div style='display: flex; gap: 12px; margin-bottom: 16px;'>
  <span class='status-badge status-pass'>PASS: {ai_results.get('pass', 0)}</span>
  <span class='status-badge status-warn'>PARTIAL: {ai_results.get('partial', 0)}</span>
  <span class='status-badge status-fail'>FAIL: {ai_results.get('fail', 0)}</span>
  {f"<span class='status-badge status-warn'>ABORTED EARLY</span>" if ai_results.get('aborted_early') else ''}
</div>
{ai_html}

<h2>Khasab DPR Parse — Validation Report</h2>
<details><summary>Raw parser output (click to expand)</summary>
<div>{md_to_html(validation_report)}</div>
</details>

<h2>Screenshots</h2>
{screenshot_html or '<p class="muted">No screenshots captured.</p>'}

<h2>Deliverables</h2>
<ul>
<li><strong>This HTML report</strong>: <code>docs/dpr-dbs-e2e-execution-log-2026-05-24.html</code></li>
<li><strong>Markdown execution log</strong>: <code>docs/dpr-dbs-e2e-test-execution-log-2026-05-24.md</code></li>
<li><strong>DPR CSV export</strong>: <code>docs/ActualData/exports/khasab-dpr-2026-05-24.csv</code></li>
<li><strong>DPR Excel export</strong>: <code>docs/ActualData/exports/khasab-dpr-2026-05-24.xlsx</code></li>
<li><strong>Design spec</strong>: <code>docs/superpowers/specs/2026-05-24-fresh-env-and-khasab-import-design.md</code></li>
<li><strong>Implementation plan</strong>: <code>docs/superpowers/plans/2026-05-24-fresh-env-and-khasab-import.md</code></li>
<li><strong>DB backup</strong>: <code>/tmp/bipros-backup-2026-05-24.dump</code> (22MB, pre-wipe state)</li>
<li><strong>Parsed DPRs (JSON)</strong>: <code>/tmp/khasab-dpr-parsed.json</code></li>
<li><strong>AI ground-truth</strong>: <code>/tmp/ai-ground-truth.json</code></li>
<li><strong>AI grading results</strong>: <code>/tmp/ai-results.json</code></li>
</ul>

<h2>Markdown Execution Log</h2>
<details><summary>Full markdown log (click to expand)</summary>
<div>{md_to_html(execution_log_md)}</div>
</details>

</div>
</body>
</html>"""

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w") as f:
    f.write(html_doc)

print(f"Wrote {OUT}")
print(f"  Size: {os.path.getsize(OUT)} bytes")
