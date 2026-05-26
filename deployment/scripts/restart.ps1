# Recreate all containers without touching volumes (data preserved).
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..')
Write-Host 'Restarting bipros stack (volumes preserved)…'
docker compose restart
$port = if ($env:API_HOST_PORT) { $env:API_HOST_PORT } else { '8080' }
Write-Host 'Waiting for backend health…'
while ($true) {
  try {
    $r = Invoke-WebRequest -UseBasicParsing "http://localhost:$port/actuator/health" -TimeoutSec 5
    if ($r.Content -match 'UP') { break }
  } catch {}
  Start-Sleep -Seconds 3
}
Write-Host "OK — http://localhost:$port/actuator/health"
