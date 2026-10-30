<#
.SYNOPSIS
  Stops the public demo started by start-demo.ps1: the Cloudflare tunnels it started, then the containers.
.DESCRIPTION
  Data is kept by default, so the next start-demo.ps1 continues with the same users and invoices.
  -RemoveData also deletes the demo's volumes (databases, signing key) and .env.demo.
.EXAMPLE
  .\deploy\tunnel\stop-demo.ps1
  .\deploy\tunnel\stop-demo.ps1 -RemoveData
#>
param(
    [string] $Project = 'ifp-demo',
    [switch] $RemoveData
)

$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$state = Join-Path $env:LOCALAPPDATA 'ifp-demo'
$envFile = Join-Path $root '.env.demo'
$pidFile = Join-Path $state 'tunnels.json'
Set-Location $root

if (Test-Path $pidFile) {
    foreach ($id in (Get-Content $pidFile -Raw | ConvertFrom-Json).pids) {
        $process = Get-Process -Id $id -ErrorAction SilentlyContinue
        if ($process -and $process.ProcessName -eq 'cloudflared') { Stop-Process -Id $id -Force }
    }
    Remove-Item $pidFile -Force
    Write-Host 'Tunnels stopped'
}

if (-not (Test-Path $envFile)) {
    Write-Host "No $envFile; nothing else to stop"
    return
}

$ErrorActionPreference = 'Continue'
$compose = @('compose', '-p', $Project, '--env-file', $envFile, '-f', 'docker-compose.yml', '-f', 'deploy/compose.public.yml', '--profile', 'app')
if ($RemoveData) {
    & docker @compose down -v --remove-orphans 2>&1 | Out-Null
    # Some Compose versions keep named volumes on `down -v`; remove what is left of this project only.
    $volumes = & docker volume ls -q --filter "label=com.docker.compose.project=$Project"
    if ($volumes) { & docker volume rm $volumes | Out-Null }
    Remove-Item $envFile -Force
    Write-Host 'Containers, volumes and .env.demo removed'
} else {
    & docker @compose stop 2>&1 | Out-Null
    Write-Host 'Containers stopped; data kept'
}
