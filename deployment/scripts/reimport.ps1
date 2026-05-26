# Refresh the Khasab DPRs against the ALREADY-running stack (containers untouched).
# Wipes the project's DPRs and re-imports from source (a plain import would only hit
# "dup"), seeding any missing rate-book variants first so every resource resolves and
# pre-selects in the DPR dropdown. For a full rebuild use .\deploy.ps1.
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..')

# Load deployment env (API port, work dir, etc.) if present.
if (Test-Path configs/.env) {
  Get-Content configs/.env | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim()) }
  }
}

$apiPort = if ($env:API_HOST_PORT) { $env:API_HOST_PORT } else { '8080' }
if (-not $env:BIPROS_API_BASE)  { $env:BIPROS_API_BASE  = "http://localhost:$apiPort" }
if (-not $env:BIPROS_WORK_DIR)  { $env:BIPROS_WORK_DIR  = "$env:TEMP\khasab" }
if (-not $env:BIPROS_TOKEN_FILE){ $env:BIPROS_TOKEN_FILE = "$env:BIPROS_WORK_DIR\admin-token.txt" }
if (-not $env:BIPROS_EXCEL_DIR) { $env:BIPROS_EXCEL_DIR  = "$PWD\data\khasab-excel" }
# psql wrapper + DB coords for the BOQ-link SQL step.
if (-not $env:BIPROS_PSQL)    { $env:BIPROS_PSQL    = "$PWD\scripts\psql-wrapper.cmd" }
if (-not $env:BIPROS_PG_USER) { $env:BIPROS_PG_USER = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'bipros' } }
if (-not $env:BIPROS_PG_DB)   { $env:BIPROS_PG_DB   = if ($env:POSTGRES_DB)   { $env:POSTGRES_DB }   else { 'bipros' } }

Write-Host "==> Re-parsing source workbook"
python3 imports/parse_khasab.py

Write-Host "==> Seeding rate-book variants for every DPR resource (idempotent)"
python3 imports/seed_resource_catalog.py

Write-Host "==> Wipe + reload DPRs (by-date parallel)"
python3 imports/reload_dprs.py

Write-Host "==> Linking every DPR to a BOQ item (match by code, else random)"
python3 imports/link_dprs_to_boq.py

Write-Host "==> Assigning supervisors to activities (from DPR data)"
python3 imports/assign_activity_supervisors.py

Write-Host "==> Aligning units across WorkActivity / Activity / DPR"
python3 imports/align_activity_units.py
