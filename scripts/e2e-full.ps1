<#
.SYNOPSIS
  Full backend end-to-end run on disposable infrastructure: every service plus the gateway, then
  scripts/e2e/E2eFullFlowTest.java walks an invoice from creation to settlement and checks events, emails and audit.
.DESCRIPTION
  Starts MySQL, RabbitMQ, Redis and MailHog from infra/e2e/compose.e2e.yml under a unique project name, with random
  passwords, ephemeral 127.0.0.1 ports and data in tmpfs; the development stack and its volumes are never touched.
  A throwaway signing key and bootstrap admin exist only for this run. Service ports are picked from free ports.
  Logs go to target/e2e-full/ and are scanned for tokens, keys and every generated secret. Only processes and
  containers started by this script are stopped. Needs Docker and Java 21; no .env is required.
.EXAMPLE
  .\scripts\e2e-full.ps1
  .\scripts\e2e-full.ps1 -SkipBuild -KeepInfrastructure
#>
param(
    [switch] $SkipBuild,
    [switch] $KeepLogs,
    [switch] $KeepInfrastructure
)

$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$logDir = Join-Path $root 'target\e2e-full'
$composeFile = Join-Path $root 'infra\e2e\compose.e2e.yml'
Set-Location $root

function New-RandomSecret {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return [Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')
}

function Get-FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}

function Invoke-WithEnvironment([hashtable] $environment, [scriptblock] $action) {
    $saved = @{}
    foreach ($key in $environment.Keys) {
        $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, [string] $environment[$key], 'Process')
    }
    try { & $action } finally {
        foreach ($key in $environment.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
    }
}

# Windows PowerShell turns redirected native stderr (docker progress output) into terminating errors under 'Stop';
# native commands are judged by their exit code instead.
function Invoke-Compose([string[]] $arguments, [string] $logName) {
    $ErrorActionPreference = 'Continue'
    Invoke-WithEnvironment $secrets {
        & docker compose -p $project -f $composeFile @arguments 2>&1 | Out-File -Append (Join-Path $logDir $logName)
    }
    return $LASTEXITCODE
}

function Get-ContainerPort([string] $service, [int] $port) {
    $ErrorActionPreference = 'Continue'
    $mapping = Invoke-WithEnvironment $secrets { & docker compose -p $project -f $composeFile port $service $port 2>$null }
    if ($LASTEXITCODE -ne 0 -or -not $mapping) { throw "No host port for $service`:$port" }
    return [int] ($mapping -split ':')[-1]
}

function Start-AppService([string] $name, [hashtable] $environment) {
    $jar = Get-ChildItem "services/$name/target/*.jar" -Exclude '*-plain.jar', '*sources.jar' | Select-Object -First 1
    if (-not $jar) { throw "No jar for $name; run without -SkipBuild" }
    $process = Invoke-WithEnvironment $environment {
        Start-Process -FilePath $java -ArgumentList @('-jar', $jar.FullName) -PassThru -NoNewWindow `
            -RedirectStandardOutput (Join-Path $logDir "$name.log") -RedirectStandardError (Join-Path $logDir "$name.err.log")
    }
    Write-Host "  started $name on :$($environment['SERVER_PORT']) (pid $($process.Id))"
    return $process
}

function Wait-Healthy([string] $name, [int] $port, [System.Diagnostics.Process] $process) {
    $deadline = (Get-Date).AddSeconds(240)
    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) { throw "$name exited during startup; see target/e2e-full/$name.log" }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 "http://localhost:$port/actuator/health"
            if ($response.StatusCode -eq 200) { Write-Host "  $name is healthy"; return }
        } catch { Start-Sleep -Seconds 2 }
    }
    throw "$name did not become healthy within 240 s; see target/e2e-full/$name.log"
}

$java = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { "$env:JAVA_HOME\bin\java.exe" } else { 'java' }
$project = 'ifp-e2e-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
New-Item -ItemType Directory -Force $logDir | Out-Null
Get-ChildItem $logDir -File | Remove-Item

$secrets = [ordered]@{
    MYSQL_ROOT_PASSWORD        = New-RandomSecret
    AUTH_DB_PASSWORD           = New-RandomSecret
    INVOICE_DB_PASSWORD        = New-RandomSecret
    FINANCING_DB_PASSWORD      = New-RandomSecret
    NOTIFICATION_DB_PASSWORD   = New-RandomSecret
    AUDIT_DB_MIGRATOR_PASSWORD = New-RandomSecret
    AUDIT_DB_PASSWORD          = New-RandomSecret
    RABBITMQ_USER              = 'e2e'
    RABBITMQ_PASSWORD          = New-RandomSecret
    REDIS_PASSWORD             = New-RandomSecret
}
$userPassword = New-RandomSecret
$adminPassword = New-RandomSecret
$adminEmail = "e2e-admin-$($project.Substring(8))@example.test"

if (-not $SkipBuild) {
    Write-Host '== Build'
    & .\mvnw.cmd -B -q package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw 'Maven package failed' }
}

$keyFile = Join-Path $logDir 'jwt-signing-key.pem'
& $java (Join-Path $PSScriptRoot 'GenerateDevJwtKey.java') $keyFile | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Key generation failed' }

$processes = @()
$exitCode = 1
try {
    Write-Host "== Disposable infrastructure ($project)"
    if ((Invoke-Compose @('up', '-d', '--wait') 'compose.log') -ne 0) { throw 'docker compose up failed; see target/e2e-full/compose.log' }
    $mysqlPort = Get-ContainerPort 'mysql' 3306
    $rabbitPort = Get-ContainerPort 'rabbitmq' 5672
    $rabbitAdminPort = Get-ContainerPort 'rabbitmq' 15672
    $redisPort = Get-ContainerPort 'redis' 6379
    $smtpPort = Get-ContainerPort 'mailhog' 1025
    $mailApiPort = Get-ContainerPort 'mailhog' 8025

    $ports = [ordered]@{}
    foreach ($name in 'auth-service', 'invoice-service', 'financing-service', 'notification-service', 'audit-service', 'gateway') {
        $ports[$name] = Get-FreePort
    }
    $issuer = "http://localhost:$($ports['auth-service'])"
    $common = @{
        AUTH_ISSUER_URI = $issuer; AUTH_JWK_SET_URI = "$issuer/oauth2/jwks"; AUTH_AUDIENCE = 'ifp-api'
        DB_HOST = '127.0.0.1'; DB_PORT = $mysqlPort
        RABBITMQ_HOST = '127.0.0.1'; RABBITMQ_PORT = $rabbitPort
        RABBITMQ_USER = $secrets.RABBITMQ_USER; RABBITMQ_PASSWORD = $secrets.RABBITMQ_PASSWORD
    }

    Write-Host '== Services'
    $processes += Start-AppService 'auth-service' ($common + @{
        SERVER_PORT = $ports['auth-service']; DB_PASSWORD = $secrets.AUTH_DB_PASSWORD
        REDIS_HOST = '127.0.0.1'; REDIS_PORT = $redisPort; REDIS_PASSWORD = $secrets.REDIS_PASSWORD
        AUTH_JWT_PRIVATE_KEY_LOCATION = 'file:///' + $keyFile.Replace('\', '/')
        ADMIN_BOOTSTRAP_EMAIL = $adminEmail; ADMIN_BOOTSTRAP_PASSWORD = $adminPassword; ADMIN_BOOTSTRAP_DISPLAY_NAME = 'E2E Admin'
    })
    $processes += Start-AppService 'invoice-service' ($common + @{
        SERVER_PORT = $ports['invoice-service']; DB_PASSWORD = $secrets.INVOICE_DB_PASSWORD
    })
    $processes += Start-AppService 'financing-service' ($common + @{
        SERVER_PORT = $ports['financing-service']; DB_PASSWORD = $secrets.FINANCING_DB_PASSWORD
    })
    $processes += Start-AppService 'notification-service' ($common + @{
        SERVER_PORT = $ports['notification-service']; DB_PASSWORD = $secrets.NOTIFICATION_DB_PASSWORD
        MAIL_HOST = '127.0.0.1'; MAIL_PORT = $smtpPort
    })
    $processes += Start-AppService 'audit-service' ($common + @{
        SERVER_PORT = $ports['audit-service']; DB_PASSWORD = $secrets.AUDIT_DB_PASSWORD
        DB_MIGRATOR_PASSWORD = $secrets.AUDIT_DB_MIGRATOR_PASSWORD
    })
    $processes += Start-AppService 'gateway' ($common + @{
        SERVER_PORT = $ports['gateway']; REDIS_HOST = '127.0.0.1'; REDIS_PORT = $redisPort; REDIS_PASSWORD = $secrets.REDIS_PASSWORD
        AUTH_SERVICE_URI = $issuer
        INVOICE_SERVICE_URI = "http://localhost:$($ports['invoice-service'])"
        FINANCING_SERVICE_URI = "http://localhost:$($ports['financing-service'])"
        NOTIFICATION_SERVICE_URI = "http://localhost:$($ports['notification-service'])"
        AUDIT_SERVICE_URI = "http://localhost:$($ports['audit-service'])"
    })
    $i = 0
    foreach ($name in $ports.Keys) { Wait-Healthy $name $ports[$name] $processes[$i]; $i++ }

    Write-Host '== Full flow'
    Invoke-WithEnvironment @{
        E2E_GATEWAY_URL = "http://localhost:$($ports['gateway'])"; E2E_AUTH_URL = $issuer
        E2E_PASSWORD = $userPassword; E2E_ADMIN_EMAIL = $adminEmail; E2E_ADMIN_PASSWORD = $adminPassword
        E2E_MAILHOG_URL = "http://127.0.0.1:$mailApiPort"; E2E_RABBIT_ADMIN_URL = "http://127.0.0.1:$rabbitAdminPort"
        E2E_RABBIT_USER = $secrets.RABBITMQ_USER; E2E_RABBIT_PASSWORD = $secrets.RABBITMQ_PASSWORD
    } {
        $ErrorActionPreference = 'Continue'
        & $java (Join-Path $PSScriptRoot 'e2e\E2eFullFlowTest.java') 2>&1 | Tee-Object -FilePath (Join-Path $logDir 'full-flow.log')
        $script:exitCode = $LASTEXITCODE
    }
} finally {
    Write-Host '== Stopping services started by this script'
    foreach ($process in $processes) {
        & taskkill /PID $process.Id /T /F 2>&1 | Out-Null
        $process.WaitForExit(15000) | Out-Null
    }
    if ($KeepInfrastructure) {
        Write-Host "  infrastructure kept: docker compose -p $project -f infra/e2e/compose.e2e.yml down"
    } else {
        Write-Host "== Removing disposable infrastructure ($project)"
        if ((Invoke-Compose @('down', '--remove-orphans') 'compose.log') -ne 0) {
            Write-Host "  removal failed; remove containers labelled com.docker.compose.project=$project manually"
            $exitCode = 1
        }
    }
    Remove-Item $keyFile -ErrorAction SilentlyContinue

    Write-Host '== Scanning logs for secrets'
    $known = @($secrets.Values | Where-Object { $_ -ne $secrets.RABBITMQ_USER }) + @($userPassword, $adminPassword)
    $leaks = 0
    foreach ($log in Get-ChildItem $logDir -Filter '*.log') {
        $content = Get-Content $log.FullName -Raw
        if (-not $content) { continue }
        foreach ($pattern in 'eyJ[A-Za-z0-9_-]{10,}\.eyJ', 'PRIVATE KEY', '(?i)authorization:\s*bearer') {
            if ($content -match $pattern) { Write-Host "  LEAK  $($log.Name) matches /$pattern/"; $leaks++ }
        }
        foreach ($secret in $known) {
            if ($content.Contains($secret)) { Write-Host "  LEAK  $($log.Name) contains a generated secret"; $leaks++ }
        }
    }
    if ($leaks -eq 0) { Write-Host '  no tokens, keys or passwords found in service or test output' }
    if ($leaks -gt 0) { $exitCode = 1 }
    if ($exitCode -eq 0 -and -not $KeepLogs) { Remove-Item $logDir -Recurse -Force }
    elseif ($exitCode -ne 0) { Write-Host '  logs kept in target/e2e-full for troubleshooting' }
}

Write-Host "E2E full-flow exit code: $exitCode"
exit $exitCode
