<#
.SYNOPSIS
  Runs the platform on this Windows machine and publishes it through free Cloudflare quick tunnels (no account).
.DESCRIPTION
  Starts three quick tunnels (app, API gateway, sign-in), writes their https URLs into .env.demo at the repository
  root (git-ignored; random secrets are generated once and kept), pulls the images published from main to GHCR and
  starts the stack with deploy/compose.public.yml. Only the frontend, gateway and auth-service are reachable from the
  internet. Quick-tunnel URLs change every time the tunnels restart, and the demo is online only while this machine,
  Docker and the tunnels run. Data survives restarts; stop-demo.ps1 -RemoveData deletes it.
.EXAMPLE
  .\deploy\tunnel\start-demo.ps1
  .\deploy\tunnel\start-demo.ps1 -ImageTag sha-<full commit SHA>
#>
param(
    [string] $Registry = '',
    [string] $ImageTag = 'latest',
    [string] $Project = 'ifp-demo',
    [int] $AppPort = 34200,
    [int] $ApiPort = 38080,
    [int] $AuthPort = 39000,
    [int] $MailPort = 38025
)

$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$state = Join-Path $env:LOCALAPPDATA 'ifp-demo'
$envFile = Join-Path $root '.env.demo'
$pidFile = Join-Path $state 'tunnels.json'
New-Item -ItemType Directory -Force $state | Out-Null
Set-Location $root

function New-Secret {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return [Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')
}

# Windows PowerShell turns native stderr (docker progress output) into errors under 'Stop'; judge by exit code.
function Invoke-Compose([string[]] $arguments) {
    $ErrorActionPreference = 'Continue'
    & docker compose -p $Project --env-file $envFile -f docker-compose.yml -f deploy/compose.public.yml --profile app @arguments 2>&1 |
        Out-File -Append (Join-Path $state 'compose.log')
    if ($LASTEXITCODE -ne 0) { throw "docker compose $($arguments -join ' ') failed; see $state\compose.log" }
}

function Get-Cloudflared {
    $onPath = Get-Command cloudflared -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $exe = Join-Path $state 'cloudflared.exe'
    if (-not (Test-Path $exe)) {
        Write-Host 'Downloading cloudflared from github.com/cloudflare/cloudflared ...'
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe' `
            -OutFile $exe -UseBasicParsing
    }
    $signature = Get-AuthenticodeSignature $exe
    if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O="?Cloudflare, Inc\."?') {
        Remove-Item $exe -Force
        throw 'cloudflared.exe is not validly signed by Cloudflare, Inc.; deleted it'
    }
    return $exe
}

function Stop-Tunnels {
    if (-not (Test-Path $pidFile)) { return }
    foreach ($id in (Get-Content $pidFile -Raw | ConvertFrom-Json).pids) {
        $process = Get-Process -Id $id -ErrorAction SilentlyContinue
        if ($process -and $process.ProcessName -eq 'cloudflared') { Stop-Process -Id $id -Force }
    }
    Remove-Item $pidFile -Force
}

function Start-Tunnel([string] $exe, [string] $name, [int] $port) {
    $log = Join-Path $state "tunnel-$name.log"
    Remove-Item $log -ErrorAction SilentlyContinue
    # Created through WMI, hidden and without inherited handles: a tunnel started with Start-Process would keep this
    # script's output pipe open for as long as it runs, so a caller piping the output would never finish.
    $startup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow = [uint16] 0 }
    $created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine = "`"$exe`" tunnel --no-autoupdate --logfile `"$log`" --url http://localhost:$port"
        ProcessStartupInformation = $startup
    }
    if ($created.ReturnValue -ne 0) { throw "Could not start the $name tunnel (WMI error $($created.ReturnValue))" }
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $match = Select-String -Path $log -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($match) { return @{ Url = $match.Matches[0].Value; Pid = [int] $created.ProcessId } }
        if (-not (Get-Process -Id $created.ProcessId -ErrorAction SilentlyContinue)) { break }
    }
    throw "The $name tunnel did not start; see $log"
}

function Set-EnvValues([hashtable] $values) {
    $lines = [System.Collections.Generic.List[string]]::new()
    if (Test-Path $envFile) { $lines.AddRange([string[]] (Get-Content $envFile)) }
    foreach ($key in $values.Keys) {
        $index = -1
        for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i].StartsWith("$key=")) { $index = $i; break } }
        if ($index -ge 0) { $lines[$index] = "$key=$($values[$key])" } else { $lines.Add("$key=$($values[$key])") }
    }
    Set-Content -Path $envFile -Value $lines -Encoding ascii
}

function Wait-Public([string] $url) {
    $hostName = ([Uri] $url).Host
    $deadline = (Get-Date).AddMinutes(5)
    while ((Get-Date) -lt $deadline) {
        try { Resolve-DnsName $hostName -Server 1.1.1.1 -DnsOnly -ErrorAction Stop | Out-Null; break } catch { Start-Sleep -Seconds 5 }
    }
    while ((Get-Date) -lt $deadline) {
        try { return Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 15 } catch { Start-Sleep -Seconds 5 }
    }
    throw "$url did not answer within 5 minutes"
}

& docker info *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker is not running' }

if (-not $Registry) {
    $origin = git remote get-url origin
    if ($origin -notmatch 'github\.com[:/]([^/]+)/') { throw 'Pass -Registry ghcr.io/<owner>/ifp' }
    $Registry = "ghcr.io/$($Matches[1].ToLowerInvariant())/ifp"
}

$running = & docker ps -q --filter "label=com.docker.compose.project=$Project"
if (-not $running) {
    foreach ($port in $AppPort, $ApiPort, $AuthPort, $MailPort) {
        if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Port $port is in use" }
    }
}

if (-not (Test-Path $envFile)) {
    Write-Host "Creating $envFile with new random secrets"
    $secrets = [ordered]@{ COMPOSE_PROJECT_NAME = $Project; RABBITMQ_USER = 'ifp'; ADMIN_BOOTSTRAP_EMAIL = 'admin@ifp-demo.test';
        ADMIN_BOOTSTRAP_DISPLAY_NAME = 'Demo Admin' }
    foreach ($name in 'MYSQL_ROOT_PASSWORD', 'AUTH_DB_PASSWORD', 'INVOICE_DB_PASSWORD', 'FINANCING_DB_PASSWORD',
            'NOTIFICATION_DB_PASSWORD', 'AUDIT_DB_MIGRATOR_PASSWORD', 'AUDIT_DB_PASSWORD', 'RABBITMQ_PASSWORD',
            'REDIS_PASSWORD', 'ADMIN_BOOTSTRAP_PASSWORD') {
        $secrets[$name] = New-Secret
    }
    Set-EnvValues $secrets
}

$cloudflared = Get-Cloudflared
Stop-Tunnels
Write-Host 'Starting Cloudflare quick tunnels ...'
$app = Start-Tunnel $cloudflared 'app' $AppPort
$api = Start-Tunnel $cloudflared 'api' $ApiPort
$auth = Start-Tunnel $cloudflared 'auth' $AuthPort
@{ pids = @($app.Pid, $api.Pid, $auth.Pid) } | ConvertTo-Json | Set-Content $pidFile

Set-EnvValues ([ordered]@{
    IFP_IMAGE_REGISTRY = $Registry; IFP_IMAGE_TAG = $ImageTag
    FRONTEND_PORT = $AppPort; GATEWAY_PORT = $ApiPort; AUTH_PORT = $AuthPort; MAILHOG_UI_PORT = $MailPort
    AUTH_ISSUER_URI = $auth.Url; IFP_PUBLIC_API_URL = $api.Url
    AUTH_SPA_REDIRECT_URIS = "$($app.Url)/auth/callback"; AUTH_SPA_POST_LOGOUT_REDIRECT_URIS = "$($app.Url)/"
    AUTH_SPA_ALLOWED_ORIGINS = $app.Url; GATEWAY_CORS_ALLOWED_ORIGINS = $app.Url
    # cloudflared forwards the visitor's address as the only X-Forwarded-For entry.
    GATEWAY_TRUSTED_PROXY_HOPS = 1
})

Write-Host "Pulling $Registry/*:$ImageTag and starting the stack (first start takes a few minutes) ..."
Invoke-Compose @('pull', '--quiet', '--ignore-buildable')
Invoke-Compose @('pull', '--quiet', 'auth-service', 'invoice-service', 'financing-service', 'notification-service',
    'audit-service', 'gateway', 'frontend')
Invoke-Compose @('up', '-d', '--no-build', '--wait', '--wait-timeout', '900')

Write-Host 'Waiting for the public URLs to resolve ...'
$config = (Wait-Public "$($app.Url)/config.json").Content | ConvertFrom-Json
$discovery = (Wait-Public "$($auth.Url)/.well-known/openid-configuration").Content | ConvertFrom-Json
if ($config.apiBaseUrl -ne $api.Url -or $discovery.issuer -ne $auth.Url) { throw 'The public URLs are not wired up as expected' }

Write-Host ''
Write-Host "App:      $($app.Url)"
Write-Host "API:      $($api.Url)"
Write-Host "Sign-in:  $($auth.Url)"
Write-Host "Emails:   http://localhost:$MailPort (this machine only)"
Write-Host "Admin:    admin@ifp-demo.test; the password is ADMIN_BOOTSTRAP_PASSWORD in $envFile"
Write-Host 'These URLs change whenever the tunnels restart. Stop with .\deploy\tunnel\stop-demo.ps1'
