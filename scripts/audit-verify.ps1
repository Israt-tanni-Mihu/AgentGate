<#
.SYNOPSIS
  QA3: asks the gateway to recompute the whole audit hash chain.
.DESCRIPTION
  Calls GET /api/admin/audit/verify. Prints OK + record count, or the first broken sequence number.
  Exit code 0 = chain intact, 1 = tampering detected, 2 = could not ask the gateway.
  Admin login comes from ADMIN_USERNAME / ADMIN_PASSWORD (the same variables the gateway uses);
  without them the "local" profile defaults are used.
.EXAMPLE
  .\scripts\audit-verify.ps1
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$User = $(if ($env:ADMIN_USERNAME) { $env:ADMIN_USERNAME } else { "admin" }),
    [string]$Password = $(if ($env:ADMIN_PASSWORD) { $env:ADMIN_PASSWORD } else { "admin-local-only" })
)

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
