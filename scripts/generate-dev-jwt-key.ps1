<#
.SYNOPSIS
  DEVELOPMENT ONLY. Creates infra/keys/jwt-signing-key.pem (git-ignored) for auth-service.
.DESCRIPTION
  Never use this key outside local development and never commit it. Production keys come from a secret store.
  Existing keys are never overwritten; delete the file yourself to rotate.
#>
param([string] $OutFile = (Join-Path $PSScriptRoot '..\infra\keys\jwt-signing-key.pem'))

$ErrorActionPreference = 'Stop'
$OutFile = [System.IO.Path]::GetFullPath($OutFile)
$existed = Test-Path $OutFile

& java (Join-Path $PSScriptRoot 'GenerateDevJwtKey.java') $OutFile
if ($LASTEXITCODE -ne 0) { throw "Key generation failed (exit code $LASTEXITCODE)" }

if (-not $existed) {
    # Restrict the file to the current user (no inherited ACLs).
    & icacls $OutFile /inheritance:r /grant:r "$($env:USERNAME):(R,W)" | Out-Null
}
Write-Host "Use it with: AUTH_JWT_PRIVATE_KEY_LOCATION=file:$OutFile"
