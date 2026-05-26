# Tail bipros-api logs.
param([string]$Service = 'bipros-api')
Set-Location (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..')
docker compose logs -f --tail=100 $Service
