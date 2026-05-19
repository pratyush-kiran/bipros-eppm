#!/usr/bin/env bash
# Verifies the Oman-Demo Insights KPIs are populated post-restart.
# Usage: ./scripts/verify-oman-demo-insights.sh [BACKEND_BASE_URL]
# Default base URL: http://localhost:8080
# Requires: python (for JSON parsing — jq is not installed)

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
PROJECT_ID="d901671a-cd23-41c6-8886-d2c1b0ddd3c5"   # OMAN-DEMO-KHASAB
FROM_DATE="$(date -d '30 days ago' +%Y-%m-%d 2>/dev/null || date -v-30d +%Y-%m-%d)"
TO_DATE="$(date +%Y-%m-%d)"

echo "==> Verifying Oman-Demo Insights against $BASE_URL"
echo "    project_id = $PROJECT_ID"
echo "    window     = $FROM_DATE → $TO_DATE"
echo ""

echo "==> Logging in as admin/admin123"
TOKEN=$(curl -s -X POST "$BASE_URL/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' \
  | python -c "import sys, json; d=json.load(sys.stdin); print(d.get('data',{}).get('accessToken',''))")
if [[ -z "$TOKEN" ]]; then
  echo "ERROR: could not extract JWT from login response"
  exit 1
fi
echo "    token acquired (${#TOKEN} chars)"
echo ""

echo "==> Step 2: DB sanity (counts MUST be > 0)"
docker exec bipros-postgres psql -U bipros -d bipros -c "
SELECT 'dpr_last_30d' k, count(*) FROM project.daily_progress_reports WHERE project_id = '$PROJECT_ID' AND report_date >= current_date - 30
UNION ALL SELECT 'dpr_last_7d', count(*) FROM project.daily_progress_reports WHERE project_id = '$PROJECT_ID' AND report_date >= current_date - 7
UNION ALL SELECT 'mp_with_resid', count(*) FROM project.dpr_manpower m JOIN project.daily_progress_reports d ON d.id = m.dpr_id WHERE d.project_id = '$PROJECT_ID' AND m.resource_id IS NOT NULL
UNION ALL SELECT 'eq_with_resid', count(*) FROM project.dpr_equipment e JOIN project.daily_progress_reports d ON d.id = e.dpr_id WHERE d.project_id = '$PROJECT_ID' AND e.resource_id IS NOT NULL
UNION ALL SELECT 'manpower_attendance', count(*) FROM resource.manpower_attendance ma JOIN resource.resources r ON r.id = ma.resource_id WHERE r.code LIKE 'OMD-LAB-%'
UNION ALL SELECT 'manpower_financials', count(*) FROM resource.manpower_financials mf JOIN resource.resources r ON r.id = mf.resource_id WHERE r.code LIKE 'OMD-LAB-%'
UNION ALL SELECT 'eq_service_date_set', count(*) FROM resource.resource_equipment_details ed JOIN resource.resources r ON r.id = ed.resource_id WHERE r.code LIKE 'OMD-EQ-%' AND ed.next_service_date IS NOT NULL
UNION ALL SELECT 'eq_service_due_7d', count(*) FROM resource.resource_equipment_details ed JOIN resource.resources r ON r.id = ed.resource_id WHERE r.code LIKE 'OMD-EQ-%' AND ed.next_service_date BETWEEN current_date AND current_date + 7
UNION ALL SELECT 'mat_issues_30d', count(*) FROM resource.material_issue WHERE project_id = '$PROJECT_ID' AND issue_date >= current_date - 30
UNION ALL SELECT 'mat_consumption_30d', count(*) FROM resource.material_consumption_logs WHERE project_id = '$PROJECT_ID' AND log_date >= current_date - 30
UNION ALL SELECT 'OMD-MAT_resources', count(*) FROM resource.resources WHERE code LIKE 'OMD-MAT-%'
ORDER BY 1;"
echo ""

dump() {  # dump <label> <url> <python expr>
  echo "==> $1"
  curl -s -H "Authorization: Bearer $TOKEN" "$BASE_URL$2" \
    | python -c "import sys, json; d=json.load(sys.stdin); $3"
  echo ""
}

dump "Step 3a: Manpower KPI" \
  "/v1/projects/$PROJECT_ID/kpis/manpower?from=$FROM_DATE&to=$TO_DATE" \
"
data=d['data']; wu=data['workforceUtilization']; cs=data['labourCostSummary']
print(f'  Workforce Utilisation:     {wu[\"utilizationPct\"]:.4f}  (active={wu[\"activeResourceCount\"]}, labour={wu[\"laborResourceCount\"]})')
print(f'  Total Labour Cost:         {cs[\"actualLabourCost\"]:,.2f}')
print(f'  Planned Labour Cost:       {cs[\"plannedLabourCost\"]:,.2f}')
print(f'  Labour Cost Variance:      {cs[\"labourCostVariance\"]:,.2f}')
print(f'  LCPI:                      {cs[\"lcpi\"]:.4f}')
print(f'  OT Cost % of Wage Bill:    {cs[\"otCostPct\"]:.4f}')
print(f'  Avg Productivity Factor:   {data[\"headlineProductivityFactor\"]:.4f}')
print(f'  Idle Time Ratio:           {data[\"idleTimeRatioPct\"]:.4f}')
print(f'  Overtime Ratio:            {data[\"overtimeRatioPct\"]:.4f}')
print(f'  Cost / Unit Output:        {data[\"weightedAvgCostPerUnit\"]:,.2f}')
print(f'  Cumulative Progress:       {data[\"cumulativeProgressPct\"]:.4f}')
print(f'  Productivity bottom rows:  {len(data[\"productivityFactor\"])}')
print(f'  Labour Cost / Unit rows:   {len(data[\"labourCostPerUnit\"])}')
print(f'  Crew Output rows:          {len(data[\"crewOutput\"])}')
print(f'  Output Achievement rows:   {len(data[\"outputAchievement\"])}')
"

dump "Step 3b: Equipment KPI" \
  "/v1/projects/$PROJECT_ID/kpis/equipment?from=$FROM_DATE&to=$TO_DATE" \
"
data=d['data']
print(f'  Machines Tracked:          {len(data[\"utilization\"])}')
print(f'  Mechanical Availability:   {data[\"mechanicalAvailabilityPct\"]:.4f}')
print(f'  Equipment Productivity Ix: {data[\"equipmentProductivityIndexPct\"]:.4f}')
print(f'  Idle Machine Cost:         {data[\"idleMachineCostTotal\"]:,.2f}')
print(f'  Idle-Time Alerts:          {len(data[\"idleAlerts\"])}')
print(f'  Service Due rows:          {len(data[\"serviceDue\"])}')
print(f'  Availability+Perf rows:    {len(data[\"availabilityPerformance\"])}')
print(f'  Owned/Rented slices:       {len(data[\"ownedVsRented\"])}')
print(f'  Fuel/Output rows:          {len(data[\"fuelPerOutput\"])}')
"

dump "Step 3c: Material KPI" \
  "/v1/projects/$PROJECT_ID/kpis/material?from=$FROM_DATE&to=$TO_DATE" \
"
data=d['data']
print(f'  Issued Qty:                {data[\"issuedQty\"]:,.3f}')
print(f'  Consumed Qty:              {data[\"consumedQty\"]:,.3f}')
print(f'  Wastage Qty:               {data[\"wastageQty\"]:,.3f}')
print(f'  Material Utilisation %:    {data[\"materialUtilizationPct\"]:.4f}')
print(f'  Wastage %:                 {data[\"wastagePct\"]:.4f}')
print(f'  Reconciliation Balance:    {data[\"reconciliationBalance\"]:.3f}')
print(f'  Cost/Unit Finished (wt):   {data[\"weightedAvgCostPerUnitFinished\"]:,.2f}')
print(f'  By-material rows:          {len(data[\"byMaterial\"])}')
print(f'  Cost-per-activity rows:    {len(data[\"costPerUnitByActivity\"])}')
print(f'  Price Variance (Phase 2):  {data[\"materialPriceVariance\"]}  (expected null)')
print(f'  Usage Variance (Phase 2):  {data[\"materialUsageVariance\"]}  (expected null)')
"

dump "Step 3d: EVM KPI (regression)" "/v1/projects/$PROJECT_ID/kpis/evm" \
"
data=d['data']
print(f'  BAC: {data[\"budgetAtCompletion\"]:,.2f}  PV: {data[\"plannedValue\"]:,.2f}  EV: {data[\"earnedValue\"]:,.2f}  AC: {data[\"actualCost\"]:,.2f}')
print(f'  SV: {data[\"scheduleVariance\"]:,.2f}  CV: {data[\"costVariance\"]:,.2f}  SPI: {data[\"schedulePerformanceIndex\"]:.3f}  CPI: {data[\"costPerformanceIndex\"]:.3f}')
print(f'  EAC: {data[\"estimateAtCompletion\"]:,.2f}  ETC: {data[\"estimateToComplete\"]:,.2f}  VAC: {data[\"varianceAtCompletion\"]:,.2f}  TCPI: {data[\"toCompletePerformanceIndex\"]:.3f}')
"

dump "Step 3e: Field summary" "/v1/projects/$PROJECT_ID/dashboards/field/summary" \
"
data=d['data']
print(f'  As-of date:                {data[\"asOfDate\"]}')
print(f'  Workers on Site:           {data[\"workersOnSite\"]}')
print(f'  Equipment Deployed:        {data[\"equipmentDeployed\"]}')
print(f'  Operating Hours 4d:        {data[\"operatingHours4d\"]:.1f}')
print(f'  Stock Availability %:      {data[\"stockAvailabilityPct\"]:.4f}')
print(f'  Re-order Breach Count:     {data[\"reorderBreachCount\"]}')
print(f'  Safety Incidents:          {data[\"safetyIncidents\"]}')
print(f'  Active Sites:              {len(data[\"activeSites\"])}')
print(f'  Daily Worklogs:            {len(data[\"dailyWorklogs\"])} rows ({data[\"dailyWorklogs\"][0][\"date\"] if data[\"dailyWorklogs\"] else \"\"} … {data[\"dailyWorklogs\"][-1][\"date\"] if data[\"dailyWorklogs\"] else \"\"})')
"

echo "==> DONE. Headline values above should all be > 0 (except Phase 2 Material Price/Usage Variance)."
