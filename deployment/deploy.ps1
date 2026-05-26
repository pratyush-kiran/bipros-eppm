# Requires: PowerShell 5.1+ and Docker Desktop.
# Equivalent of deploy.sh for Windows.
# Usage:
#   .\deploy.ps1                # full deploy
#   .\deploy.ps1 -Force         # tear down + redeploy
#   .\deploy.ps1 -SkipImport    # bring up stack only
#   .\deploy.ps1 -SkipBuild     # skip docker compose build
[CmdletBinding()]
param(
  [switch]$Force,
  [switch]$SkipImport,
  [switch]$SkipBuild,
  [string]$EnvFile
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

# ─── Load .env ───────────────────────────────────────────────────────────────
if (-not $EnvFile) { $EnvFile = Join-Path $ScriptDir 'configs\.env' }
if ((-not (Test-Path $EnvFile)) -and (Test-Path "$ScriptDir\configs\.env.example")) {
  Copy-Item "$ScriptDir\configs\.env.example" $EnvFile
}
if (Test-Path $EnvFile) {
  Get-Content $EnvFile | ForEach-Object {
    if ($_ -match '^\s*#') { return }
    if ($_ -match '^\s*([^=\s]+)\s*=\s*(.*)\s*$') {
      [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
  }
}

$LogDir = Join-Path $ScriptDir 'logs'
if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir | Out-Null }
$DeployLog = Join-Path $LogDir ("deploy-$(Get-Date -Format yyyyMMdd-HHmmss).log")
"" | Out-File -FilePath $DeployLog

$ApiPort       = if ($env:API_HOST_PORT)       { $env:API_HOST_PORT }       else { '8080' }
$PgPort        = if ($env:PG_HOST_PORT)        { $env:PG_HOST_PORT }        else { '5433' }
$RedisPort     = if ($env:REDIS_HOST_PORT)     { $env:REDIS_HOST_PORT }     else { '6379' }
$ChHttpPort    = if ($env:CLICKHOUSE_HTTP_PORT){ $env:CLICKHOUSE_HTTP_PORT }else { '8123' }
$MinioApiPort  = if ($env:MINIO_API_PORT)      { $env:MINIO_API_PORT }      else { '9000' }
$MinioConPort  = if ($env:MINIO_CONSOLE_PORT)  { $env:MINIO_CONSOLE_PORT }  else { '9001' }
$DoclingPort   = if ($env:DOCLING_PORT)        { $env:DOCLING_PORT }        else { '5001' }
$PgAdminPort   = if ($env:PGADMIN_PORT)        { $env:PGADMIN_PORT }        else { '5050' }
$PgUser        = if ($env:POSTGRES_USER)       { $env:POSTGRES_USER }       else { 'bipros' }
$PgDb          = if ($env:POSTGRES_DB)         { $env:POSTGRES_DB }         else { 'bipros' }
$Profiles      = if ($env:BIPROS_PROFILES)     { $env:BIPROS_PROFILES }     else { 'prod,init-prod' }

$TotalSteps = 12
$Script:Step = 0
$WorkDir = if ($env:BIPROS_WORK_DIR) { $env:BIPROS_WORK_DIR } else { Join-Path $env:TEMP 'khasab' }
if (-not (Test-Path $WorkDir)) { New-Item -ItemType Directory -Path $WorkDir | Out-Null }

function Write-Stage([string]$Msg) {
  $Script:Step++
  $pct = [int]($Script:Step * 100 / $TotalSteps)
  Write-Host ""
  Write-Host ('═' * 63) -ForegroundColor DarkGray
  Write-Host ("[{0,2}/{1,2}] " -f $Script:Step, $TotalSteps) -NoNewline -ForegroundColor White
  Write-Host $Msg -ForegroundColor Cyan -NoNewline
  Write-Host ("  {0}%" -f $pct) -ForegroundColor DarkGray
  Write-Progress -Activity 'Bipros deployment' -Status $Msg -PercentComplete $pct
  "$(Get-Date -Format HH:mm:ss) [STAGE] $Msg" | Out-File -Append $DeployLog
}
function Write-Info([string]$Msg) { Write-Host "[INFO]  $Msg" -ForegroundColor Cyan;    "$(Get-Date -Format HH:mm:ss) [INFO]  $Msg" | Out-File -Append $DeployLog }
function Write-Ok  ([string]$Msg) { Write-Host "[OK]    $Msg" -ForegroundColor Green;   "$(Get-Date -Format HH:mm:ss) [OK]    $Msg" | Out-File -Append $DeployLog }
function Write-Warn([string]$Msg) { Write-Host "[WARN]  $Msg" -ForegroundColor Yellow;  "$(Get-Date -Format HH:mm:ss) [WARN]  $Msg" | Out-File -Append $DeployLog }
function Write-Err ([string]$Msg) { Write-Host "[ERROR] $Msg" -ForegroundColor Red;     "$(Get-Date -Format HH:mm:ss) [ERROR] $Msg" | Out-File -Append $DeployLog }
function Die([string]$Msg) { Write-Err $Msg; exit 1 }

function Banner {
  Write-Host ""
  Write-Host "═══════════════════════════════════════════════════════════════" -ForegroundColor Cyan
  Write-Host "  Bipros EPPM — Khasab Road Project 2026"               -ForegroundColor Cyan
  Write-Host "  Single-host deployment (Docker / Windows)"             -ForegroundColor Cyan
  Write-Host "═══════════════════════════════════════════════════════════════" -ForegroundColor Cyan
  Write-Host "  Deploy log:     $DeployLog"
  Write-Host "  Env file:       $EnvFile"
  Write-Host "  Force redeploy: $($Force.IsPresent)"
  Write-Host "  Skip import:    $($SkipImport.IsPresent)"
}

# ─── Step 1: Docker preflight ───────────────────────────────────────────────
function PreflightDocker {
  Write-Stage 'Docker preflight'
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Die 'Docker is not installed. Install Docker Desktop for Windows and re-run.'
  }
  Write-Ok "docker CLI:    $(& docker --version)"

  try { docker info | Out-Null }
  catch {
    Write-Warn 'Docker daemon not running — attempting to start Docker Desktop'
    $dockerExe = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (Test-Path $dockerExe) {
      Start-Process -FilePath $dockerExe
      Write-Info 'Waiting up to 120s for Docker Desktop…'
      $t = 0
      while ($t -lt 120) {
        Start-Sleep -Seconds 3; $t += 3
        try { docker info | Out-Null; break } catch {}
      }
      if ($t -ge 120) { Die 'Docker did not start within 120s' }
    } else {
      Die "Docker Desktop not found at $dockerExe — start it manually and re-run"
    }
  }
  Write-Ok 'docker daemon: running'

  try { docker compose version | Out-Null }
  catch { Die 'docker compose plugin missing — install Docker Compose v2 (bundled with Docker Desktop)' }
  Write-Ok "compose:       $(& docker compose version --short 2>$null)"
}

# ─── Step 2: Port preflight ─────────────────────────────────────────────────
function PreflightPorts {
  Write-Stage 'Checking host ports + disk space'
  $needed = @($ApiPort, $PgPort, $RedisPort, $ChHttpPort, $MinioApiPort, $MinioConPort, $DoclingPort, $PgAdminPort)
  $inUse = @()
  foreach ($p in $needed) {
    $conn = Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue
    if ($conn) {
      $byUs = (docker ps --format '{{.Ports}}' 2>$null) -match ":${p}->"
      if (-not $byUs) { $inUse += $p }
    }
  }
  if ($inUse.Count -gt 0) {
    Write-Warn "Ports in use by non-bipros processes: $($inUse -join ', ')"
    Write-Warn "Edit configs\.env to override (e.g. API_HOST_PORT=8081) or stop the conflicting processes"
    Die 'Port conflict — see warning above'
  }
  Write-Ok 'All required ports free'

  $drive = (Get-Item $ScriptDir).PSDrive
  $freeGb = [int]($drive.Free / 1GB)
  if ($freeGb -lt 10) {
    Write-Warn "Only ${freeGb} GB free on $($drive.Root) — recommend >= 20 GB"
  } else {
    Write-Ok "Disk free:     ${freeGb} GB on $($drive.Root)"
  }
}

# ─── Step 3: Force wipe ─────────────────────────────────────────────────────
function ForceWipe {
  Write-Stage 'Tear-down (--Force)'
  if (-not $Force) { Write-Info 'Skipped (no -Force flag)'; return }
  Write-Warn 'Destroying existing containers + volumes…'
  & docker compose down -v --remove-orphans 2>&1 | Out-File -Append $DeployLog
  Write-Ok 'Tear-down complete'
}

# ─── Step 4: Build image ────────────────────────────────────────────────────
function BuildImage {
  Write-Stage 'Build backend image'
  if ($SkipBuild) { Write-Info 'Skipped (-SkipBuild)'; return }
  Write-Info 'Building bipros-api:prod (multi-stage, ~5-12 min first time)…'
  $env:DOCKER_BUILDKIT = '1'
  & docker compose build bipros-api 2>&1 | Tee-Object -FilePath $DeployLog -Append | Where-Object { $_ -match 'DONE|ERROR|Built' }
  if ($LASTEXITCODE -ne 0) { Die "build failed (exit $LASTEXITCODE)" }
  Write-Ok 'Image built'
}

# ─── Wait helpers ───────────────────────────────────────────────────────────
function Wait-ContainerHealthy([string]$Name, [int]$TimeoutSec = 120) {
  $t = 0
  while ($t -lt $TimeoutSec) {
    $s = & docker compose ps $Name --format json 2>$null
    if ($s -match '"Health":"healthy"') { return }
    Start-Sleep -Seconds 3; $t += 3
  }
  Die "$Name did not become healthy within ${TimeoutSec}s"
}

function Wait-Url([string]$Url, [int]$TimeoutSec = 180) {
  $t = 0
  while ($t -lt $TimeoutSec) {
    try {
      $r = Invoke-WebRequest -UseBasicParsing $Url -TimeoutSec 5
      if ($r.Content -match 'UP') { return $true }
    } catch {}
    Start-Sleep -Seconds 5; $t += 5
    if ($t % 30 -eq 0) { Write-Info "  …still waiting (${t}s)" }
  }
  return $false
}

# ─── Step 5/6: Start infra ──────────────────────────────────────────────────
function StartInfraDb {
  Write-Stage 'Start Postgres + Redis'
  & docker compose up -d postgresql redis 2>&1 | Out-File -Append $DeployLog
  Write-Info 'Waiting for Postgres healthcheck…'
  Wait-ContainerHealthy 'postgresql'
  Write-Ok 'Postgres healthy'
}

function StartInfraMisc {
  Write-Stage 'Start ClickHouse + MinIO + Docling + pgAdmin'
  & docker compose up -d clickhouse minio docling pgadmin 2>&1 | Out-File -Append $DeployLog
  Write-Info 'Waiting for ClickHouse healthcheck…'
  try { Wait-ContainerHealthy 'clickhouse' 90 } catch { Write-Warn 'ClickHouse slow to start — continuing' }
  Write-Ok 'Infra stack up'
}

# ─── Step 7: Start backend ──────────────────────────────────────────────────
function StartBackend {
  Write-Stage "Start bipros-api (profile: $Profiles)"
  & docker compose up -d bipros-api 2>&1 | Out-File -Append $DeployLog
  Write-Info 'Waiting for backend health (up to 3 min)…'
  if (-not (Wait-Url "http://localhost:$ApiPort/actuator/health" 180)) {
    Write-Err 'Backend did not become healthy — last log lines:'
    & docker logs bipros-api --tail 20
    Die 'Backend boot failure'
  }
  Write-Ok "Backend healthy: http://localhost:$ApiPort/actuator/health"
}

# ─── Step 8: Bootstrap masters ──────────────────────────────────────────────
function BootstrapMasters {
  Write-Stage 'Bootstrap resource catalogue (masters)'
  $n = & docker exec bipros-postgres psql -U $PgUser -d $PgDb -At -c "SELECT COUNT(*) FROM resource.equipment_role_variants" 2>$null
  if ([int]($n -replace '\D','') -gt 0) {
    Write-Info "Catalogue already populated ($n equipment role variants) — skipping"
    return
  }
  Write-Info "Loading data\sql\01-bipros-masters.sql…"
  & docker exec bipros-postgres psql -U $PgUser -d $PgDb -q -c "DELETE FROM resource.resource_roles; DELETE FROM resource.resource_types;" 2>&1 | Out-File -Append $DeployLog
  Get-Content (Join-Path $ScriptDir 'data\sql\01-bipros-masters.sql') | & docker exec -i bipros-postgres psql -U $PgUser -d $PgDb 2>&1 | Where-Object { $_ -match 'ERROR|INSERT' } | Select-Object -First 50 | Out-File -Append $DeployLog
  $n = & docker exec bipros-postgres psql -U $PgUser -d $PgDb -At -c "SELECT COUNT(*) FROM resource.equipment_role_variants"
  Write-Ok "Catalogue loaded: $n equipment role variants"
}

# ─── Step 9: Admin token ────────────────────────────────────────────────────
function GrabAdminToken {
  Write-Stage 'Authenticate as admin'
  try {
    $r = Invoke-RestMethod -Method Post -Uri "http://localhost:$ApiPort/v1/auth/login" `
      -Headers @{'Content-Type'='application/json'} -Body '{"username":"admin","password":"admin123"}'
    $token = $r.data.accessToken
    if (-not $token) { throw 'no token in response' }
    Set-Content -Path (Join-Path $WorkDir 'admin-token.txt') -Value $token -NoNewline
    Write-Ok "Admin token saved -> $WorkDir\admin-token.txt"
  } catch {
    Die "admin login failed: $_"
  }
}

# ─── Step 10: Khasab import (pre-DPR) ───────────────────────────────────────
function RunImportPreDpr {
  Write-Stage 'Khasab import — parse + project + activities + role-assignments'
  if ($SkipImport) { Write-Info 'Skipped (-SkipImport)'; return }

  $projectCount = & docker exec bipros-postgres psql -U $PgUser -d $PgDb -At -c "SELECT COUNT(*) FROM project.projects WHERE code='KHASAB-2026'" 2>$null
  if (([int]($projectCount -replace '\D','') -gt 0) -and (-not $Force)) {
    Write-Info 'KHASAB-2026 already exists — skipping import. Use -Force to redo.'
    return
  }

  # Common env for all python scripts. Use the WSL wrapper if Docker Desktop / WSL is in use.
  $env:BIPROS_API_BASE   = "http://localhost:$ApiPort"
  $env:BIPROS_PG_HOST    = '127.0.0.1'
  $env:BIPROS_PG_PORT    = $PgPort
  $env:BIPROS_PG_USER    = $PgUser
  $env:BIPROS_PG_PASS    = if ($env:POSTGRES_PASSWORD) { $env:POSTGRES_PASSWORD } else { 'bipros_dev' }
  $env:BIPROS_PG_DB      = $PgDb
  $env:BIPROS_WORK_DIR   = $WorkDir
  $env:BIPROS_TOKEN_FILE = Join-Path $WorkDir 'admin-token.txt'
  $env:BIPROS_EXCEL_DIR  = Join-Path $ScriptDir 'data\khasab-excel'
  # On Windows, the python scripts use `docker exec` via this wrapper.
  $env:BIPROS_PSQL       = Join-Path $ScriptDir 'scripts\psql-wrapper.cmd'

  # Ensure openpyxl
  & python -c 'import openpyxl' 2>$null
  if ($LASTEXITCODE -ne 0) { Write-Warn 'Installing openpyxl…'; & python -m pip install --user --quiet openpyxl }

  $imp = Join-Path $ScriptDir 'imports'
  Write-Info '  parse_khasab.py';            & python "$imp\parse_khasab.py"            2>&1 | Select-Object -Last 3
  Write-Info '  parse_master_sheet.py';      & python "$imp\parse_master_sheet.py"      2>&1 | Select-Object -Last 3
  Write-Info '  analyze_resource_demand.py'; & python "$imp\analyze_resource_demand.py" 2>&1 | Select-Object -Last 3
  Write-Info '  rebuild_demo.py';            & python "$imp\rebuild_demo.py"            2>&1 | Select-Object -Last 15
  Write-Info '  fix_role_assignments.py';    & python "$imp\fix_role_assignments.py"    2>&1 | Select-String -Pattern 'Total|created'

  # Ensure every DPR resource name has a rate-book variant so imported rows resolve to a
  # role+variant and pre-select in the DPR dropdown (idempotent).
  Write-Info '  seed_resource_catalog.py';   & python "$imp\seed_resource_catalog.py"   2>&1 | Select-String -Pattern 'Seeding|Seed summary|Coverage|STILL'

  # Assign each activity the supervisor(s) who file DPRs for it (DPR form filters by supervisor).
  Write-Info '  assign_activity_supervisors.py'; & python "$imp\assign_activity_supervisors.py" 2>&1 | Select-Object -Last 3

  Write-Info '  Re-locking activities for DPR ingest'
  $token = Get-Content -Raw (Join-Path $WorkDir 'admin-token.txt')
  $pid_   = Get-Content -Raw (Join-Path $WorkDir 'project-id.txt')
  $actIds = Get-Content -Raw (Join-Path $WorkDir 'activity-ids.json') | ConvertFrom-Json
  $locked = 0
  foreach ($prop in $actIds.PSObject.Properties) {
    $aid = $prop.Value
    try {
      Invoke-WebRequest -UseBasicParsing -Method Post `
        -Uri "http://localhost:$ApiPort/v1/projects/$pid_/activities/$aid/lock" `
        -Headers @{Authorization = "Bearer $token"} -TimeoutSec 5 | Out-Null
      $locked++
    } catch { }
  }
  Write-Ok "Locked $locked activities"
}

# ─── Step 11: DPR import ────────────────────────────────────────────────────
function RunImportDprs {
  Write-Stage 'Khasab import — DPR upload (3,431 rows, ~5-15 min)'
  if ($SkipImport) { Write-Info 'Skipped (-SkipImport)'; return }
  & python (Join-Path $ScriptDir 'imports\import_khasab_dprs.py') all 2>&1 | Tee-Object -FilePath (Join-Path $LogDir 'dpr-import.log') | Where-Object { $_ -match 'DONE|ok=|fail=' }
  $n = & docker exec bipros-postgres psql -U $PgUser -d $PgDb -At -c "SELECT COUNT(*) FROM project.daily_progress_reports"
  Write-Ok "DPRs in DB: $n"
}

# ─── Step 12: Post-DPR ──────────────────────────────────────────────────────
function RunImportPostDpr {
  Write-Stage 'Khasab import — cost + EVM + BOQ + dashboard + risks'
  if ($SkipImport) { Write-Info 'Skipped (-SkipImport)'; return }
  $imp = Join-Path $ScriptDir 'imports'
  Write-Info '  fix_demo_v2.py';           & python "$imp\fix_demo_v2.py" 2>&1 | Select-Object -Last 10
  Write-Info '  link_dprs_to_boq.py';      & python "$imp\link_dprs_to_boq.py" 2>&1 | Select-Object -Last 6
  Write-Info '  align_activity_units.py';  & python "$imp\align_activity_units.py" 2>&1 | Select-Object -Last 3
  Write-Info '  create_norms_only.py';     & python "$imp\create_norms_only.py" 2>&1 | Select-Object -Last 5
  Write-Info '  tune_productivity_norms.sql'; Get-Content "$imp\tune_productivity_norms.sql" | & docker exec -i bipros-postgres psql -U $PgUser -d $PgDb 2>&1 | Select-Object -Last 5
  Write-Info '  populate_dashboard.py';    & python "$imp\populate_dashboard.py" 2>&1 | Select-Object -Last 10
  Write-Info '  add_weather_risks.py';     & python "$imp\add_weather_risks.py" 2>&1 | Select-Object -Last 10
  Write-Info '  fix_dpr_activity_name_drift.sql'; Get-Content (Join-Path $ScriptDir 'data\sql\99-fix-dpr-drift.sql') | & docker exec -i bipros-postgres psql -U $PgUser -d $PgDb 2>&1 | Select-Object -Last 5
}

function PrintSummary {
  Write-Progress -Activity 'Bipros deployment' -Completed
  Write-Host ""
  Write-Host "═══════════════════════════════════════════════════════════════" -ForegroundColor Green
  Write-Host "  Deployment complete" -ForegroundColor Green
  Write-Host "═══════════════════════════════════════════════════════════════" -ForegroundColor Green
  Write-Host ""
  & docker exec bipros-postgres psql -U $PgUser -d $PgDb -c @"
SELECT
  (SELECT COUNT(*) FROM project.projects WHERE code='KHASAB-2026') AS project,
  (SELECT COUNT(*) FROM project.daily_progress_reports) AS dprs,
  (SELECT COUNT(*) FROM activity.activities WHERE project_id=(SELECT id FROM project.projects WHERE code='KHASAB-2026')) AS activities,
  (SELECT COUNT(*) FROM resource.resource_assignments) AS role_assigns,
  (SELECT COUNT(*) FROM risk.risks) AS risks,
  (SELECT COUNT(*) FROM project.dpr_issues) AS dpr_issues;
"@ 2>$null
  Write-Host ""
  Write-Host "URLs:" -ForegroundColor White
  Write-Host "  Backend health:  http://localhost:$ApiPort/actuator/health"
  Write-Host "  Swagger UI:      http://localhost:$ApiPort/swagger-ui.html"
  Write-Host "  pgAdmin:         http://localhost:$PgAdminPort   ($($env:PGADMIN_EMAIL ?? 'admin@bipros.io') / $($env:PGADMIN_PASSWORD ?? 'admin'))"
  Write-Host ""
  Write-Host "Admin login:      admin / admin123  " -NoNewline
  Write-Host "(change immediately for prod)" -ForegroundColor Yellow
  Write-Host ""
  Write-Host "Frontend: not in this stack — run `pnpm dev` in ..\frontend\"
  Write-Host ""
  Write-Host "Deploy log: $DeployLog"
}

# ─── Main ───────────────────────────────────────────────────────────────────
Banner
PreflightDocker
PreflightPorts
ForceWipe
BuildImage
StartInfraDb
StartInfraMisc
StartBackend
BootstrapMasters
GrabAdminToken
RunImportPreDpr
RunImportDprs
RunImportPostDpr
PrintSummary
