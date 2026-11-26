<#
.SYNOPSIS
  QA5 (adoption): starts the whole stack from zero for a classmate test.
.DESCRIPTION
  1. creates .env from .env.example if it is missing
  2. docker compose down -v   (deletes the database: no tools/policies left from earlier runs)
  3. docker compose up --build -d --wait   (waits until every container is healthy)
  4. checks that the QA5 tool (getShippingRate) is NOT in the registry yet
  5. prints the admin login and the URLs the classmate needs
  It does NOT import the tool or change the policy: that is the classmate's job (docs/qa5-runbook.md).
  Exit code 0 = ready, 2 = could not start.
.EXAMPLE
  .\scripts\qa5-start.ps1
#>
param([string]$BaseUrl = "http://localhost:8080")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path .env)) {
    Copy-Item .env.example .env
    Write-Host "Created .env from .env.example" -ForegroundColor Yellow
}
function Get-EnvValue([string]$Name) {
    $match = Select-String -Path .env -Pattern "^$Name=(.*)$"
    if ($match) { return $match.Matches[0].Groups[1].Value.Trim() }
    return $null
}

# docker prints progress on stderr; in Windows PowerShell 5.1 that must not count as an error.
$ErrorActionPreference = "Continue"
Write-Host "== Removing the old stack and its database (docker compose down -v) ==" -ForegroundColor Cyan
& docker compose down -v
Write-Host "`n== Building and starting (this can take a few minutes the first time) ==" -ForegroundColor Cyan
& docker compose up --build -d --wait
$composeExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($composeExit -ne 0) {
    Write-Host "Stack did not become healthy. Look at: docker compose logs gateway" -ForegroundColor Red
    exit 2
}

$admin = Get-EnvValue "ADMIN_USERNAME"
$adminPassword = Get-EnvValue "ADMIN_PASSWORD"
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${admin}:${adminPassword}"))
$headers = @{ Authorization = "Basic $basic" }

# The demo seeders run right after startup; wait until the ShopBD tools are there, so the classmate
# starts from the same state every time.
$tools = $null
for ($i = 0; $i -lt 30; $i++) {
    try {
        $tools = Invoke-RestMethod "$BaseUrl/api/admin/tools?size=100" -Headers $headers
        if ($tools.content.Count -gt 0) { break }
    } catch { }
    Start-Sleep -Seconds 1
}
if (-not $tools -or $tools.content.Count -eq 0) {
    Write-Host "Gateway is up but the demo tools were not seeded. Look at: docker compose logs gateway" -ForegroundColor Red
    exit 2
}
if ($tools.content.name -contains "getShippingRate") {
    Write-Host "getShippingRate is already registered - the test would not start from zero." -ForegroundColor Red
    exit 2
}

Write-Host ""
Write-Host "================= QA5: READY FOR THE CLASSMATE =================" -ForegroundColor Green
Write-Host "Registered tools now : $($tools.content.name -join ', ')  (getShippingRate is NOT there yet)"
Write-Host ""
Write-Host "Admin login (Swagger 'Authorize' button, HTTP Basic):"
Write-Host "  username : $admin"
Write-Host "  password : $adminPassword"
Write-Host ""
Write-Host "URLs:"
Write-Host "  Runbook          : docs/qa5-runbook.md"
Write-Host "  Admin API (UI)   : $BaseUrl/swagger-ui.html"
Write-Host "  New tool's spec  : http://localhost:8081/openapi/shipping-rate.json   (open it in the browser)"
Write-Host "  Spec URL for the gateway (inside Docker): http://mock-tools:8081/openapi/shipping-rate.json"
Write-Host ""
Write-Host "When the classmate is done:  .\scripts\qa5-check.ps1"
Write-Host "Ready at $(Get-Date -Format 'HH:mm:ss') - START THE TIMER when the classmate opens the runbook." -ForegroundColor Green
exit 0
