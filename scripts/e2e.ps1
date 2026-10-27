<#
.SYNOPSIS
  Repeatable local end-to-end check: infrastructure + auth-service + invoice-service + gateway, then
  scripts/e2e/E2eSmokeTest.java against the real Authorization Code + PKCE flow.
.DESCRIPTION
  Requires Docker, Java 21 and a filled-in .env (copy .env.example). Secrets are read from .env and handed to the
  services through environment variables; nothing secret is printed. Service logs go to target/e2e/ and are scanned
  for tokens, private keys and passwords before the script finishes. Only processes started by this script are stopped.
.EXAMPLE
  .\scripts\e2e.ps1
  .\scripts\e2e.ps1 -GatewayPort 18080 -SkipBuild
#>
param(
    [int] $AuthPort = 9000,
    [int] $InvoicePort = 8081,
    [int] $GatewayPort = 8080,
    [switch] $SkipBuild,
    [switch] $KeepLogs
)

$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$logDir = Join-Path $root 'target\e2e'
Set-Location $root

function Read-DotEnv([string] $path) {
    if (-not (Test-Path $path)) { throw ".env not found. Copy .env.example to .env and replace the placeholders." }
    $values = @{}
    foreach ($line in Get-Content $path) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') { $values[$Matches[1]] = $Matches[2].Trim() }
    }
    foreach ($required in 'AUTH_DB_PASSWORD', 'INVOICE_DB_PASSWORD', 'REDIS_PASSWORD') {
        if (-not $values[$required]) { throw "$required is missing in .env" }
    }
    return $values
}

function Assert-PortFree([int] $port) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        throw "Port $port is already in use. Stop that process or pass a different port (see -AuthPort/-InvoicePort/-GatewayPort)."
    }
}

function New-RandomSecret {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return [Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')
}

function Start-AppService([string] $name, [string] $jar, [hashtable] $environment) {
    $saved = @{}
    foreach ($key in $environment.Keys) {
        $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, [string] $environment[$key], 'Process')
    }
    try {
        $process = Start-Process -FilePath $java -ArgumentList @('-jar', $jar) -PassThru -NoNewWindow `
            -RedirectStandardOutput (Join-Path $logDir "$name.log") -RedirectStandardError (Join-Path $logDir "$name.err.log")
    } finally {
        foreach ($key in $environment.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
    }
    Write-Host "  started $name (pid $($process.Id))"
    return $process
}

function Wait-Healthy([string] $name, [int] $port, [System.Diagnostics.Process] $process) {
    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) { throw "$name exited during startup; see target/e2e/$name.log" }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 "http://localhost:$port/actuator/health"
            if ($response.StatusCode -eq 200) { Write-Host "  $name is healthy"; return }
        } catch { Start-Sleep -Seconds 2 }
    }
    throw "$name did not become healthy within 180 s; see target/e2e/$name.log"
}

function Find-Jar([string] $module) {
    $jar = Get-ChildItem "services/$module/target/*.jar" -Exclude '*-plain.jar', '*sources.jar' | Select-Object -First 1
    if (-not $jar) { throw "No jar for $module; run without -SkipBuild" }
    return $jar.FullName
}

# A launcher shim (e.g. Oracle's javapath) starts the JVM as a child process, so services are stopped as process trees.
$java = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { "$env:JAVA_HOME\bin\java.exe" } else { 'java' }
$dotenv = Read-DotEnv (Join-Path $root '.env')
$AuthPort, $InvoicePort, $GatewayPort | ForEach-Object { Assert-PortFree $_ }
New-Item -ItemType Directory -Force $logDir | Out-Null
Get-ChildItem $logDir -File | Remove-Item

Write-Host '== Infrastructure'
& docker compose up -d --wait mysql redis
if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed' }

$keyFile = Join-Path $root 'infra\keys\jwt-signing-key.pem'
& (Join-Path $PSScriptRoot 'generate-dev-jwt-key.ps1') -OutFile $keyFile | Out-Null

if (-not $SkipBuild) {
    Write-Host '== Build'
    & .\mvnw.cmd -B -q package -DskipTests -pl 'services/auth-service,services/invoice-service,services/gateway' -am
    if ($LASTEXITCODE -ne 0) { throw 'Maven package failed' }
}

$issuer = "http://localhost:$AuthPort"
$common = @{
    AUTH_ISSUER_URI  = $issuer
    AUTH_JWK_SET_URI = "$issuer/oauth2/jwks"
    AUTH_AUDIENCE    = 'ifp-api'
    DB_PORT          = $(if ($dotenv['MYSQL_PORT']) { $dotenv['MYSQL_PORT'] } else { '3307' })
}
$e2ePassword = New-RandomSecret
$processes = @()
$exitCode = 1
try {
    Write-Host '== Services'
    $processes += Start-AppService 'auth-service' (Find-Jar 'auth-service') ($common + @{
        SERVER_PORT = $AuthPort; DB_PASSWORD = $dotenv['AUTH_DB_PASSWORD']
        AUTH_JWT_PRIVATE_KEY_LOCATION = 'file:///' + $keyFile.Replace('\', '/')
    })
    $processes += Start-AppService 'invoice-service' (Find-Jar 'invoice-service') ($common + @{
        SERVER_PORT = $InvoicePort; DB_PASSWORD = $dotenv['INVOICE_DB_PASSWORD']
        # Local invoice_db is migrated with the dev seed data (see README); auth-service stays on its default profile
        # so it must load the real key file instead of falling back to an ephemeral key.
        SPRING_PROFILES_ACTIVE = 'dev'
    })
    $processes += Start-AppService 'gateway' (Find-Jar 'gateway') ($common + @{
        SERVER_PORT = $GatewayPort; REDIS_PASSWORD = $dotenv['REDIS_PASSWORD']
        REDIS_PORT = $(if ($dotenv['REDIS_PORT']) { $dotenv['REDIS_PORT'] } else { '6379' })
        AUTH_SERVICE_URI = $issuer; INVOICE_SERVICE_URI = "http://localhost:$InvoicePort"
    })
    Wait-Healthy 'auth-service' $AuthPort $processes[0]
    Wait-Healthy 'invoice-service' $InvoicePort $processes[1]
    Wait-Healthy 'gateway' $GatewayPort $processes[2]

    Write-Host '== Smoke test'
    $clientEnv = @{
        E2E_GATEWAY_URL = "http://localhost:$GatewayPort"; E2E_AUTH_URL = $issuer
        E2E_INVOICE_URL = "http://localhost:$InvoicePort"; E2E_PASSWORD = $e2ePassword
    }
    foreach ($key in $clientEnv.Keys) { [Environment]::SetEnvironmentVariable($key, $clientEnv[$key], 'Process') }
    try {
        & $java (Join-Path $PSScriptRoot 'e2e\E2eSmokeTest.java') 2>&1 | Tee-Object -FilePath (Join-Path $logDir 'smoke-test.log')
        $exitCode = $LASTEXITCODE
    } finally {
        foreach ($key in $clientEnv.Keys) { [Environment]::SetEnvironmentVariable($key, $null, 'Process') }
    }
} finally {
    Write-Host '== Stopping services started by this script'
    foreach ($process in $processes) {
        & taskkill /PID $process.Id /T /F 2>&1 | Out-Null
        $process.WaitForExit(15000) | Out-Null
    }

    Write-Host '== Scanning logs for secrets'
    $secrets = @($e2ePassword, $dotenv['AUTH_DB_PASSWORD'], $dotenv['INVOICE_DB_PASSWORD'], $dotenv['REDIS_PASSWORD'],
        $dotenv['MYSQL_ROOT_PASSWORD']) | Where-Object { $_ }
    $leaks = 0
    foreach ($log in Get-ChildItem $logDir -Filter '*.log') {
        $content = Get-Content $log.FullName -Raw
        if (-not $content) { continue }
        $patterns = @('eyJ[A-Za-z0-9_-]{10,}\.eyJ', 'PRIVATE KEY', '(?i)authorization:\s*bearer')
        foreach ($pattern in $patterns) {
            if ($content -match $pattern) { Write-Host "  LEAK  $($log.Name) matches /$pattern/"; $leaks++ }
        }
        foreach ($secret in $secrets) {
            if ($content.Contains($secret)) { Write-Host "  LEAK  $($log.Name) contains a configured secret"; $leaks++ }
        }
    }
    if ($leaks -eq 0) { Write-Host '  no tokens, keys or passwords found in service or test output' }
    if ($leaks -gt 0) { $exitCode = 1 }
    if ($exitCode -eq 0 -and -not $KeepLogs) { Remove-Item $logDir -Recurse -Force }
    elseif ($exitCode -ne 0) { Write-Host "  logs kept in target/e2e for troubleshooting" }
}

Write-Host "E2E exit code: $exitCode"
exit $exitCode
