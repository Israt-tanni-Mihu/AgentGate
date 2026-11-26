<#
.SYNOPSIS
  QA5 (adoption): checks the classmate's work with a real agent token. Prints PASS/FAIL per check.
.DESCRIPTION
  Acts as the agent "support-bot" (OAuth2 client credentials, secret from .env) and talks MCP to /mcp:
    1. tool registered with risk level LOW (admin API)
    2. getShippingRate appears in the agent's MCP tools/list
    3. allowed call (5 kg) succeeds
    4. call that breaks the policy (50 kg > 20 kg limit) is DENIED by the policy
    5. audit records exist for both calls
    6. the old demo rules are still in the active policy (nothing else was broken)
  Exit code 0 = all PASS, 1 = at least one FAIL, 2 = could not reach the gateway.
.EXAMPLE
  .\scripts\qa5-check.ps1
#>
param([string]$BaseUrl = "http://localhost:8080")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$toolName = "getShippingRate"

function Get-EnvValue([string]$Name) {
    $match = Select-String -Path .env -Pattern "^$Name=(.*)$"
    if ($match) { return $match.Matches[0].Groups[1].Value.Trim() }
    return $null
}

$script:failed = 0
function Report([string]$Name, [bool]$Ok, [string]$Detail) {
    if ($Ok) { Write-Host "PASS  $Name" -ForegroundColor Green -NoNewline }
    else { Write-Host "FAIL  $Name" -ForegroundColor Red -NoNewline; $script:failed++ }
    Write-Host "   $Detail"
}

$adminBasic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
        "$(Get-EnvValue 'ADMIN_USERNAME'):$(Get-EnvValue 'ADMIN_PASSWORD')"))
$admin = @{ Authorization = "Basic $adminBasic" }

# ---- real agent token (OAuth2 client credentials) ----
try {
    $agentBasic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
            "support-bot:$(Get-EnvValue 'SUPPORT_BOT_SECRET')"))
    $token = (Invoke-RestMethod "$BaseUrl/oauth2/token" -Method Post -Headers @{ Authorization = "Basic $agentBasic" } `
            -Body @{ grant_type = "client_credentials" }).access_token
} catch {
    Write-Host "Could not get an agent token from $BaseUrl : $($_.Exception.Message)" -ForegroundColor Red
    exit 2
}
Write-Host "Agent token for support-bot received (OAuth2 client credentials)`n"

$rpcId = 0
function Invoke-Mcp([string]$Method, $Params) {
    $script:rpcId++
    $body = @{ jsonrpc = "2.0"; id = $script:rpcId; method = $Method; params = $Params } | ConvertTo-Json -Depth 10 -Compress
    return Invoke-RestMethod "$BaseUrl/mcp" -Method Post -ContentType "application/json" `
            -Headers @{ Authorization = "Bearer $token"; Accept = "application/json" } -Body $body
}
function Find-Audit([string]$CallId) {
    for ($i = 0; $i -lt 10; $i++) {   # the audit row may land a moment after the answer
        try { return Invoke-RestMethod "$BaseUrl/api/admin/audit/$CallId" -Headers $admin } catch { Start-Sleep -Milliseconds 300 }
    }
    return $null
}

# 1. registry + risk level
$tool = (Invoke-RestMethod "$BaseUrl/api/admin/tools?size=100" -Headers $admin).content | Where-Object { $_.name -eq $toolName }
Report "1 tool imported, risk level LOW" ($tool -and $tool.riskLevel -eq "LOW") `
    $(if ($tool) { "riskLevel=$($tool.riskLevel), baseUrl=$($tool.baseUrl)" } else { "$toolName is not in the registry" })

# 2. MCP tools/list as the agent
$listed = (Invoke-Mcp "tools/list" @{}).result.tools.name
Report "2 visible in the agent's MCP tools/list" ($listed -contains $toolName) "support-bot sees: $($listed -join ', ')"

# 3. allowed call
$allowed = (Invoke-Mcp "tools/call" @{ name = $toolName; arguments = @{ weightKg = 5; zone = "inside_dhaka" } }).result
$allowedText = $allowed.content[0].text
Report "3 allowed call (5 kg) succeeds" (-not $allowed.isError) $allowedText

# 4. call that breaks the policy
$denied = (Invoke-Mcp "tools/call" @{ name = $toolName; arguments = @{ weightKg = 50; zone = "inside_dhaka" } }).result
$deniedAudit = Find-Audit $denied._meta.'agentgate/callId'
$byPolicy = $deniedAudit -and $deniedAudit.decision -eq "DENIED" -and $deniedAudit.decidedByStep -match "polic"
Report "4 call over the limit (50 kg) denied by policy" ($denied.isError -and $byPolicy) $denied.content[0].text

# 5. audit trail
$allowedAudit = Find-Audit $allowed._meta.'agentgate/callId'
$auditOk = $allowedAudit -and $allowedAudit.decision -eq "ALLOWED" -and $allowedAudit.toolName -eq $toolName -and $deniedAudit
Report "5 audit records for both calls" $auditOk $(if ($auditOk) {
        "allowed: seq $($allowedAudit.seq) rule=$($allowedAudit.ruleId); denied: seq $($deniedAudit.seq) rule=$($deniedAudit.ruleId) step=$($deniedAudit.decidedByStep)"
    } else { "missing audit record(s)" })

# 6. nothing else broken: the demo rules must still be in the active policy
$active = (Invoke-RestMethod "$BaseUrl/api/admin/policies?size=100" -Headers $admin).content | Where-Object { $_.status -eq "ACTIVE" }
$activeRules = (Invoke-RestMethod "$BaseUrl/api/admin/policies/$($active.version)" -Headers $admin).rules.id
$kept = @("support-own-customer-only", "support-read-orders", "support-refund-up-to-2000", "analytics-read-only") |
        Where-Object { $activeRules -notcontains $_ }
Report "6 old demo rules kept in the active policy" ($kept.Count -eq 0) `
    $(if ($kept.Count -eq 0) { "active version $($active.version): $($activeRules -join ', ')" } else { "missing: $($kept -join ', ')" })

Write-Host ""
if ($script:failed -eq 0) { Write-Host "QA5 CHECK: ALL PASS" -ForegroundColor Green; exit 0 }
Write-Host "QA5 CHECK: $($script:failed) FAIL" -ForegroundColor Red
exit 1
