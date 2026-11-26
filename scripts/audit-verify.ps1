<#
.SYNOPSIS
  QA3: asks the gateway to recompute the whole audit hash chain.
.DESCRIPTION
  Calls GET /api/admin/audit/verify. Prints OK + record count, or the first broken sequence number.
  Exit code 0 = chain intact, 1 = tampering detected, 2 = could not ask the gateway.
  Admin login (ADMIN_USERNAME / ADMIN_PASSWORD, the same variables the gateway uses) is taken from,
  in order: the shell environment, the repo's .env file (what docker compose uses),
  and finally the "local" profile defaults.
  If Windows blocks the script, run it with:
    powershell -ExecutionPolicy Bypass -File .\scripts\audit-verify.ps1
.EXAMPLE
  .\scripts\audit-verify.ps1
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$User,
    [string]$Password
)

# Reads NAME=value from the repo-root .env, so the script works from any folder.
function Get-EnvFileValue([string]$Name) {
    $envFile = Join-Path $PSScriptRoot "..\.env"
    if (-not (Test-Path $envFile)) { return $null }
    $match = Select-String -Path $envFile -Pattern "^$Name=(.*)$"
    if ($match) { return $match.Matches[0].Groups[1].Value.Trim() }
    return $null
}

function Resolve-Setting([string]$Name, [string]$Default) {
    $fromShell = [Environment]::GetEnvironmentVariable($Name)
    if ($fromShell) { return $fromShell }
    $fromFile = Get-EnvFileValue $Name
    if ($fromFile) { return $fromFile }
    return $Default
}

if (-not $User) { $User = Resolve-Setting "ADMIN_USERNAME" "admin" }
if (-not $Password) { $Password = Resolve-Setting "ADMIN_PASSWORD" "admin-local-only" }

$pair = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${User}:${Password}"))
try {
    $result = Invoke-RestMethod -Uri "$BaseUrl/api/admin/audit/verify" -Headers @{ Authorization = "Basic $pair" }
} catch {
    Write-Host "Could not reach the gateway: $($_.Exception.Message)" -ForegroundColor Red
    exit 2
}

if ($result.status -eq "OK") {
    Write-Host "AUDIT CHAIN OK - $($result.records) records verified" -ForegroundColor Green
    exit 0
}
Write-Host "AUDIT CHAIN BROKEN at sequence number $($result.firstBrokenSeq): $($result.problem)" -ForegroundColor Red
exit 1
