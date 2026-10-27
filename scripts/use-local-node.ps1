<#
.SYNOPSIS
  Puts the project-local Node 22 LTS (.tools/node, git-ignored) first on PATH for the current PowerShell session only.
.DESCRIPTION
  Dot-source it from the repository root:  . .\scripts\use-local-node.ps1
  Downloads Node on first use and verifies it against the official SHASUMS256.txt. System Node and npm configuration
  are left untouched; closing the shell undoes the PATH change.
#>
$ifpNodeDir = & {
    $ErrorActionPreference = 'Stop'
    $version = 'v22.23.4'
    $name = "node-$version-win-x64"
    $tools = Join-Path (Split-Path -Parent $PSScriptRoot) '.tools'
    $nodeDir = Join-Path $tools 'node'

    $installed = $null
    if (Test-Path (Join-Path $nodeDir 'node.exe')) {
        $installed = & (Join-Path $nodeDir 'node.exe') --version
    }
    if ($installed -ne $version) {
        if (Test-Path $nodeDir) { Remove-Item -Recurse -Force $nodeDir }
        New-Item -ItemType Directory -Force $tools | Out-Null
        $zip = Join-Path $tools "$name.zip"
        Write-Host "Downloading Node $version ..."
        Invoke-WebRequest -UseBasicParsing "https://nodejs.org/dist/$version/$name.zip" -OutFile $zip
        $sums = (Invoke-WebRequest -UseBasicParsing "https://nodejs.org/dist/$version/SHASUMS256.txt").Content
        if ($sums -is [byte[]]) { $sums = [Text.Encoding]::ASCII.GetString($sums) }
        $expected = (($sums -split "`n") | Where-Object { $_ -match " $name\.zip$" }).Split(' ')[0]
        $actual = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
        if ($expected -ne $actual) {
            Remove-Item $zip
            throw "Checksum mismatch for $name.zip"
        }
        Expand-Archive $zip -DestinationPath $tools -Force
        Remove-Item $zip
        Rename-Item (Join-Path $tools $name) 'node'
    }
    $nodeDir
}

if ($env:Path -notlike "$ifpNodeDir;*") {
    $env:Path = "$ifpNodeDir;$env:Path"
}
Write-Host "Using Node $(node --version) and npm $(npm --version) from $ifpNodeDir (this session only)"
Remove-Variable ifpNodeDir
