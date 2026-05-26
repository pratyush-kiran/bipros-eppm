# DESTRUCTIVE — stops all containers AND drops every volume.
param([switch]$Yes)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..')
if (-not $Yes) {
  $confirm = Read-Host "This will DESTROY all bipros containers and volumes. Type 'yes' to continue"
  if ($confirm -ne 'yes') { Write-Host 'Aborted.'; exit 1 }
}
docker compose down -v --remove-orphans
$work = if ($env:BIPROS_WORK_DIR) { $env:BIPROS_WORK_DIR } else { Join-Path $env:TEMP 'khasab' }
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
Write-Host 'Reset complete. Run .\deploy.ps1 to redeploy.'
