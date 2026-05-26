#!/usr/bin/env python3
"""Compute ground-truth answers for the 50 AI validation questions
by running SQL against the bipros DB. Writes /tmp/ai-ground-truth.json.
"""
import json
import os
import subprocess

PROJECT_ID = open("/tmp/khasab/project-id.txt").read().strip()
PSQL = "/Applications/Postgres.app/Contents/Versions/latest/bin/psql"
PG_BASE = ["env", f"PGPASSWORD={os.environ.get('BIPROS_PG_PASS', 'bipros_dev')}", PSQL, "-h", os.environ.get("BIPROS_PG_HOST", "127.0.0.1"), "-p", os.environ.get("BIPROS_PG_PORT", "5432"), "-U", os.environ.get("BIPROS_PG_USER", "bipros"), "-d", os.environ.get("BIPROS_PG_DB", "bipros"), "-A", "-t", "-c"]


def q(sql):
    """Run a SQL query, return single scalar or first column."""
    full = sql.replace("$PROJECT_ID", PROJECT_ID)
    try:
        out = subprocess.run(PG_BASE + [full], capture_output=True, text=True, timeout=30)
        if out.returncode != 0:
            return f"SQL_ERROR: {out.stderr.strip()[:200]}"
        return out.stdout.strip()
    except Exception as e:
        return f"EXCEPTION: {e}"


# Question definitions. Format: (id, family, question_text, sql, expected_type)
QUESTIONS = [
    # === DPR Summary (8) ===
    ("q01", "dpr_summary", "How many DPRs were submitted in January 2026?",
     "SELECT COUNT(*) FROM project.daily_progress_reports WHERE project_id='$PROJECT_ID' AND date_part('month', report_date)=1", "int"),
    ("q02", "dpr_summary", "How many DPRs were submitted in February 2026?",
     "SELECT COUNT(*) FROM project.daily_progress_reports WHERE project_id='$PROJECT_ID' AND date_part('month', report_date)=2", "int"),
    ("q03", "dpr_summary", "How many DPRs were submitted in March 2026?",
     "SELECT COUNT(*) FROM project.daily_progress_reports WHERE project_id='$PROJECT_ID' AND date_part('month', report_date)=3", "int"),
    ("q04", "dpr_summary", "What is the total number of DPRs across Jan-Mar 2026?",
     "SELECT COUNT(*) FROM project.daily_progress_reports WHERE project_id='$PROJECT_ID'", "int"),
    ("q05", "dpr_summary", "How many DPRs did Mohd Ismaila submit?",
     "SELECT COUNT(*) FROM project.daily_progress_reports d JOIN public.users u ON u.id=d.supervisor_user_id WHERE d.project_id='$PROJECT_ID' AND u.username='ismaila'", "int"),
    ("q06", "dpr_summary", "What is the date of the earliest DPR in this project?",
     "SELECT MIN(report_date) FROM project.daily_progress_reports WHERE project_id='$PROJECT_ID'", "date"),
    ("q07", "dpr_summary", "What is the date of the latest DPR in this project?",
     "SELECT MAX(report_date) FROM project.daily_progress_reports WHERE project_id='$PROJECT_ID'", "date"),
    ("q08", "dpr_summary", "Which supervisor submitted the most DPRs?",
     "SELECT u.username FROM project.daily_progress_reports d JOIN public.users u ON u.id=d.supervisor_user_id WHERE d.project_id='$PROJECT_ID' GROUP BY u.username ORDER BY COUNT(*) DESC LIMIT 1", "str"),

    # === Resource utilization (6) ===
    ("q09", "resource", "How many unique supervisors filed DPRs?",
     "SELECT COUNT(DISTINCT supervisor_user_id) FROM project.daily_progress_reports WHERE project_id='$PROJECT_ID' AND supervisor_user_id IS NOT NULL", "int"),
    ("q10", "resource", "How many total manpower-days were deployed in January 2026?",
     "SELECT COALESCE(SUM(m.nos),0) FROM project.daily_progress_reports d JOIN project.dpr_manpower m ON m.dpr_id=d.id WHERE d.project_id='$PROJECT_ID' AND date_part('month', d.report_date)=1", "int"),
    ("q11", "resource", "Which manpower trade is used most often (by row count)?",
     "SELECT trade FROM project.dpr_manpower m JOIN project.daily_progress_reports d ON d.id=m.dpr_id WHERE d.project_id='$PROJECT_ID' GROUP BY trade ORDER BY COUNT(*) DESC LIMIT 1", "str"),
    ("q12", "resource", "How many distinct equipment types are deployed?",
     "SELECT COUNT(DISTINCT equipment_type) FROM project.dpr_equipment e JOIN project.daily_progress_reports d ON d.id=e.dpr_id WHERE d.project_id='$PROJECT_ID'", "int"),
    ("q13", "resource", "Which equipment type is used most often?",
     "SELECT equipment_type FROM project.dpr_equipment e JOIN project.daily_progress_reports d ON d.id=e.dpr_id WHERE d.project_id='$PROJECT_ID' GROUP BY equipment_type ORDER BY COUNT(*) DESC LIMIT 1", "str"),
    ("q14", "resource", "How many Excavator deployments in March 2026?",
     "SELECT COUNT(*) FROM project.dpr_equipment e JOIN project.daily_progress_reports d ON d.id=e.dpr_id WHERE d.project_id='$PROJECT_ID' AND equipment_type='Excavator' AND date_part('month', d.report_date)=3", "int"),

    # === Productivity (6) ===
    ("q15", "productivity", "What is the total quantity executed across all DPRs?",
     "SELECT ROUND(COALESCE(SUM(qty_executed),0)::numeric, 2) FROM project.daily_progress_reports WHERE project_id='$PROJECT_ID'", "decimal"),
    ("q16", "productivity", "What is the average qty_executed per DPR?",
     "SELECT ROUND(COALESCE(AVG(qty_executed),0)::numeric, 2) FROM project.daily_progress_reports WHERE project_id='$PROJECT_ID' AND qty_executed > 1", "decimal"),
    ("q17", "productivity", "How many DPRs are idle-deployment-only (qty<=1)?",
     "SELECT COUNT(*) FROM project.daily_progress_reports WHERE project_id='$PROJECT_ID' AND qty_executed <= 1", "int"),
    ("q18", "productivity", "Which activity has the highest total qty_executed?",
     "SELECT a.code FROM project.daily_progress_reports d JOIN activity.activities a ON a.id=d.activity_id WHERE d.project_id='$PROJECT_ID' GROUP BY a.code ORDER BY SUM(d.qty_executed) DESC LIMIT 1", "str"),
    ("q19", "productivity", "What is the total qty_executed for activity 2.3.6(i)b?",
     "SELECT ROUND(COALESCE(SUM(qty_executed),0)::numeric, 2) FROM project.daily_progress_reports d JOIN activity.activities a ON a.id=d.activity_id WHERE d.project_id='$PROJECT_ID' AND a.code='2.3.6(i)b'", "decimal"),
    ("q20", "productivity", "How many distinct activity codes have at least one DPR?",
     "SELECT COUNT(DISTINCT activity_id) FROM project.daily_progress_reports WHERE project_id='$PROJECT_ID'", "int"),

    # === Capacity utilization (4) ===
    ("q21", "capacity", "Total man-power line cost across all DPRs?",
     "SELECT ROUND(COALESCE(SUM(line_cost),0)::numeric, 2) FROM project.dpr_manpower m JOIN project.daily_progress_reports d ON d.id=m.dpr_id WHERE d.project_id='$PROJECT_ID'", "decimal"),
    ("q22", "capacity", "Total equipment line cost across all DPRs?",
     "SELECT ROUND(COALESCE(SUM(line_cost),0)::numeric, 2) FROM project.dpr_equipment e JOIN project.daily_progress_reports d ON d.id=e.dpr_id WHERE d.project_id='$PROJECT_ID'", "decimal"),
    ("q23", "capacity", "How many DPRs have at least one equipment line?",
     "SELECT COUNT(DISTINCT d.id) FROM project.daily_progress_reports d JOIN project.dpr_equipment e ON e.dpr_id=d.id WHERE d.project_id='$PROJECT_ID'", "int"),
    ("q24", "capacity", "How many DPRs have at least one manpower line?",
     "SELECT COUNT(DISTINCT d.id) FROM project.daily_progress_reports d JOIN project.dpr_manpower m ON m.dpr_id=d.id WHERE d.project_id='$PROJECT_ID'", "int"),

    # === Cost (6) ===
    ("q25", "cost", "Total project cost (manpower + equipment line costs)?",
     "SELECT ROUND(COALESCE((SELECT SUM(line_cost) FROM project.dpr_manpower m JOIN project.daily_progress_reports d ON d.id=m.dpr_id WHERE d.project_id='$PROJECT_ID'),0)::numeric + COALESCE((SELECT SUM(line_cost) FROM project.dpr_equipment e JOIN project.daily_progress_reports d ON d.id=e.dpr_id WHERE d.project_id='$PROJECT_ID'),0)::numeric, 2)", "decimal"),
    ("q26", "cost", "Manpower cost in January 2026?",
     "SELECT ROUND(COALESCE(SUM(line_cost),0)::numeric, 2) FROM project.dpr_manpower m JOIN project.daily_progress_reports d ON d.id=m.dpr_id WHERE d.project_id='$PROJECT_ID' AND date_part('month', d.report_date)=1", "decimal"),
    ("q27", "cost", "Equipment cost in March 2026?",
     "SELECT ROUND(COALESCE(SUM(line_cost),0)::numeric, 2) FROM project.dpr_equipment e JOIN project.daily_progress_reports d ON d.id=e.dpr_id WHERE d.project_id='$PROJECT_ID' AND date_part('month', d.report_date)=3", "decimal"),
    ("q28", "cost", "Which supervisor has the highest total manpower cost?",
     "SELECT u.username FROM project.dpr_manpower m JOIN project.daily_progress_reports d ON d.id=m.dpr_id JOIN public.users u ON u.id=d.supervisor_user_id WHERE d.project_id='$PROJECT_ID' GROUP BY u.username ORDER BY SUM(m.line_cost) DESC LIMIT 1", "str"),
    ("q29", "cost", "Which activity has the highest equipment cost?",
     "SELECT a.code FROM project.dpr_equipment e JOIN project.daily_progress_reports d ON d.id=e.dpr_id JOIN activity.activities a ON a.id=d.activity_id WHERE d.project_id='$PROJECT_ID' GROUP BY a.code ORDER BY SUM(e.line_cost) DESC LIMIT 1", "str"),
    ("q30", "cost", "Average manpower cost per DPR?",
     "SELECT ROUND(COALESCE(AVG(d.total_cost),0)::numeric, 2) FROM (SELECT dpr.id, SUM(m.line_cost) AS total_cost FROM project.daily_progress_reports dpr JOIN project.dpr_manpower m ON m.dpr_id=dpr.id WHERE dpr.project_id='$PROJECT_ID' GROUP BY dpr.id) d", "decimal"),

    # === Activity/WBS (5) ===
    ("q31", "activity", "How many activities are in the project?",
     "SELECT COUNT(*) FROM activity.activities WHERE project_id='$PROJECT_ID'", "int"),
    ("q32", "activity", "How many WBS nodes does the project have?",
     "SELECT COUNT(*) FROM project.wbs_nodes WHERE project_id='$PROJECT_ID'", "int"),
    ("q33", "activity", "How many activities are in status LOCKED or ACTIVE?",
     "SELECT COUNT(*) FROM activity.activities WHERE project_id='$PROJECT_ID' AND edit_status='LOCKED'", "int"),
    ("q34", "activity", "What is the WBS path for activity 2.3.6(i)b?",
     "SELECT w.code || ' ' || w.name FROM activity.activities a JOIN project.wbs_nodes w ON w.id=a.wbs_node_id WHERE a.project_id='$PROJECT_ID' AND a.code='2.3.6(i)b'", "str"),
    ("q35", "activity", "How many activities are under WBS node 2.3 (Bored Piling)?",
     "SELECT COUNT(*) FROM activity.activities a JOIN project.wbs_nodes w ON w.id=a.wbs_node_id WHERE a.project_id='$PROJECT_ID' AND w.code='2.3'", "int"),

    # === Materials (4) — Khasab data has 0 materials ===
    ("q36", "material", "How many material consumption logs exist?",
     "SELECT COUNT(*) FROM resource.material_consumption_logs WHERE project_id='$PROJECT_ID'", "int"),
    ("q37", "material", "How many DPRs have material lines?",
     "SELECT COUNT(DISTINCT d.id) FROM project.daily_progress_reports d JOIN project.dpr_material m ON m.dpr_id=d.id WHERE d.project_id='$PROJECT_ID'", "int"),
    ("q38", "material", "How many distinct materials are referenced in DPRs?",
     "SELECT COUNT(DISTINCT material_name) FROM project.dpr_material m JOIN project.daily_progress_reports d ON d.id=m.dpr_id WHERE d.project_id='$PROJECT_ID'", "int"),
    ("q39", "material", "Total material cost in March 2026?",
     "SELECT ROUND(COALESCE(SUM(line_cost),0)::numeric, 2) FROM project.dpr_material m JOIN project.daily_progress_reports d ON d.id=m.dpr_id WHERE d.project_id='$PROJECT_ID' AND date_part('month', d.report_date)=3", "decimal"),

    # === DBS Financial (6) ===
    ("q40", "dbs", "How many DBS supervisor rows exist?",
     "SELECT COUNT(*) FROM dbs.dbs_daily_supervisor WHERE project_id='$PROJECT_ID'", "int"),
    ("q41", "dbs", "How many DBS project (PM) rows exist?",
     "SELECT COUNT(*) FROM dbs.dbs_daily_project WHERE project_id='$PROJECT_ID'", "int"),
    ("q42", "dbs", "How many DBS engineer rows exist?",
     "SELECT COUNT(*) FROM dbs.dbs_daily_engineer WHERE project_id='$PROJECT_ID'", "int"),
    ("q43", "dbs", "How many DBS CM rows exist?",
     "SELECT COUNT(*) FROM dbs.dbs_daily_cm WHERE project_id='$PROJECT_ID'", "int"),
    ("q44", "dbs", "Total DBS supervisor boq_achieved_amount across all days?",
     "SELECT ROUND(COALESCE(SUM(boq_achieved_amount),0)::numeric, 2) FROM dbs.dbs_daily_supervisor WHERE project_id='$PROJECT_ID'", "decimal"),
    ("q45", "dbs", "Average DBS supervisor contribution per day?",
     "SELECT ROUND(COALESCE(AVG(contribution),0)::numeric, 2) FROM dbs.dbs_daily_supervisor WHERE project_id='$PROJECT_ID'", "decimal"),

    # === Cross-domain (5) ===
    ("q46", "cross", "Which day in Q1 2026 had the most DPRs submitted?",
     "SELECT report_date::text FROM project.daily_progress_reports WHERE project_id='$PROJECT_ID' GROUP BY report_date ORDER BY COUNT(*) DESC LIMIT 1", "str"),
    ("q47", "cross", "How many unique (supervisor, activity, date) combinations exist?",
     "SELECT COUNT(DISTINCT (supervisor_user_id, activity_id, report_date)) FROM project.daily_progress_reports WHERE project_id='$PROJECT_ID'", "int"),
    ("q48", "cross", "Which supervisor worked on the most distinct activities?",
     "SELECT u.username FROM project.daily_progress_reports d JOIN public.users u ON u.id=d.supervisor_user_id WHERE d.project_id='$PROJECT_ID' GROUP BY u.username ORDER BY COUNT(DISTINCT d.activity_id) DESC LIMIT 1", "str"),
    ("q49", "cross", "Across Jan-Mar 2026, what's the average DPRs per day?",
     "SELECT ROUND((COUNT(*)::numeric / NULLIF(COUNT(DISTINCT report_date), 0))::numeric, 2) FROM project.daily_progress_reports WHERE project_id='$PROJECT_ID'", "decimal"),
    ("q50", "cross", "How many DPRs include both manpower and equipment?",
     "SELECT COUNT(DISTINCT d.id) FROM project.daily_progress_reports d JOIN project.dpr_manpower m ON m.dpr_id=d.id JOIN project.dpr_equipment e ON e.dpr_id=d.id WHERE d.project_id='$PROJECT_ID'", "int"),
]


def main():
    out = []
    for qid, family, text, sql, etype in QUESTIONS:
        val = q(sql)
        out.append({
            "id": qid, "family": family, "question": text,
            "sql": sql, "expected_value": val, "expected_type": etype
        })
        print(f"  {qid} [{family}] {text[:60]}... → {val[:80]}")

    with open("/tmp/ai-ground-truth.json", "w") as f:
        json.dump(out, f, indent=2)
    print(f"\nWrote {len(out)} ground-truth records to /tmp/ai-ground-truth.json")


if __name__ == "__main__":
    main()
