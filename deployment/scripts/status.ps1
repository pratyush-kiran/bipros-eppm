# Show container + DB state. Read-only.
$ErrorActionPreference = 'Continue'
Set-Location (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..')

Write-Host '=== Containers ==='
docker compose ps --format "table {{.Name}}`t{{.Status}}`t{{.Ports}}"

Write-Host ''
Write-Host '=== Backend ==='
$port = if ($env:API_HOST_PORT) { $env:API_HOST_PORT } else { '8080' }
try {
  (Invoke-WebRequest -UseBasicParsing "http://localhost:$port/actuator/health" -TimeoutSec 5).Content
} catch { Write-Host '(unreachable)' }

Write-Host ''
Write-Host '=== Khasab data ==='
$pgUser = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'bipros' }
$pgDb   = if ($env:POSTGRES_DB)   { $env:POSTGRES_DB }   else { 'bipros' }
docker exec bipros-postgres psql -U $pgUser -d $pgDb -c @"
SELECT
  (SELECT COUNT(*) FROM project.projects WHERE code='KHASAB-2026') AS project,
  (SELECT COUNT(*) FROM project.daily_progress_reports) AS dprs,
  (SELECT COUNT(*) FROM activity.activities WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026')) AS activities,
  (SELECT COUNT(*) FROM resource.resource_assignments) AS role_assigns,
  (SELECT COUNT(*) FROM risk.risks) AS risks,
  (SELECT COUNT(*) FROM project.dpr_issues) AS dpr_issues;
"@
