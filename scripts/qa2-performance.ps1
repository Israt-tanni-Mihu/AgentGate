<#
.SYNOPSIS
  QA2: gateway overhead at 100 calls/s. Runs a baseline (mock-tools directly) and the gateway test, prints the overhead.
.DESCRIPTION
  1. starts the stack (own compose project "agentgate-qa2", fresh database, raised rate limits) unless -NoStack
  2. k6 baseline  : 100 calls/s straight to mock-tools
  3. k6 gateway   : the same call through the gateway (OAuth2 token, policy, guards, audit ...)
  4. overhead = gateway p95 - baseline p95; PASS if <= 30 ms and k6 had no errors
  5. stops the stack (unless -KeepStack)
  k6 runs from a local install if there is one, otherwise from the grafana/k6 Docker image (no install needed).
  Exit code 0 = QA2 passed, 1 = failed, 2 = could not run.
.EXAMPLE
  .\scripts\qa2-performance.ps1
  .\scripts\qa2-performance.ps1 -Duration 120s -OutDir qa\k6\out
#>
param(
    [string]$Duration = "60s",
    [string]$Warmup = "15s",
    [int]$Rate = 100,
    [double]$MaxOverheadMs = 30,
    [string]$OutDir = "qa/k6/out",
    [switch]$NoStack,
    [switch]$KeepStack
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$project = "agentgate-qa2"
$compose = @("compose", "-p", $project, "-f", "docker-compose.yml", "-f", "qa/k6/docker-compose.qa2.yml")
$EvidenceDir = "docs/evidence/qa2"
$K6Image = "grafana/k6:2.4.0"   # pinned, so a re-run uses the same k6 as the evidence
New-Item -ItemType Directory -Force $OutDir | Out-Null

# Windows PowerShell 5.1 writes "> file" and Tee-Object as UTF-16, which grep/git show as binary.
# Every text file this script writes goes through here: UTF-8 without BOM.
function Write-Utf8 {
    param([string]$Path, [string[]]$Lines)
    $full = Join-Path (Get-Location) $Path
    [System.IO.File]::WriteAllLines($full, $Lines, (New-Object System.Text.UTF8Encoding($false)))
}

function Invoke-K6 {
    param([string]$Mode, [hashtable]$ExtraEnv)
    $envList = @{ MODE = $Mode; RATE = "$Rate"; DURATION = $Duration; WARMUP = $Warmup; MAX_OVERHEAD_MS = "$MaxOverheadMs" } + $ExtraEnv
    $summary = "$Mode-summary.json"
    if (Get-Command k6 -ErrorAction SilentlyContinue) {
        foreach ($k in $envList.Keys) { Set-Item "env:$k" $envList[$k] }
        $env:GATEWAY_URL = "http://localhost:8080"; $env:MOCK_TOOLS_URL = "http://localhost:8081"
        $lines = & k6 run --quiet --no-color --summary-export "$OutDir/$summary" qa/k6/qa2.js
    } else {
        # On the compose network, so the traffic does not go through the Windows host port mapping.
        $dockerArgs = @("run", "--rm", "--network", "${project}_default",
            "-v", "${root}/qa/k6:/scripts:ro", "-v", "${root}/${OutDir}:/out",
            "-e", "GATEWAY_URL=http://gateway:8080", "-e", "MOCK_TOOLS_URL=http://mock-tools:8081")
        foreach ($k in $envList.Keys) { $dockerArgs += @("-e", "$k=$($envList[$k])") }
        $dockerArgs += @($K6Image, "run", "--quiet", "--no-color", "--summary-export", "/out/$summary", "/scripts/qa2.js")
        $lines = & docker @dockerArgs
    }
    $exit = [int]$LASTEXITCODE
    $lines | Out-Host
    Write-Utf8 "$OutDir/$Mode-output.txt" ([string[]]$lines)
    return $exit
}

function Get-P95 { param([string]$File) (Get-Content $File -Raw | ConvertFrom-Json).metrics.'http_req_duration{scenario:measure}'.'p(95)' }

try {
    if (-not $NoStack) {
        Write-Host "== Starting the stack ($project) ==" -ForegroundColor Cyan
        & docker @compose up --build -d --wait
        if ($LASTEXITCODE -ne 0) { Write-Host "Could not start the stack" -ForegroundColor Red; exit 2 }
    }

    Write-Host "`n== Baseline: $Rate calls/s straight to mock-tools ==" -ForegroundColor Cyan
    $baselineExit = Invoke-K6 -Mode "baseline" -ExtraEnv @{}
    $baselineP95 = Get-P95 "$OutDir/baseline-summary.json"

    Write-Host "`n== Gateway: $Rate calls/s through the gateway ==" -ForegroundColor Cyan
    $secret = (Select-String -Path .env -Pattern '^SUPPORT_BOT_SECRET=(.*)$').Matches.Groups[1].Value
    $gatewayExit = Invoke-K6 -Mode "gateway" -ExtraEnv @{ BASELINE_P95_MS = "$baselineP95"; AGENT_SECRET = $secret }
    $gatewayP95 = Get-P95 "$OutDir/gateway-summary.json"

    $overhead = $gatewayP95 - $baselineP95
    $pass = ($baselineExit -eq 0) -and ($gatewayExit -eq 0) -and ($overhead -le $MaxOverheadMs)
    Write-Host "`n================ QA2 RESULT ================" -ForegroundColor Cyan
    "{0,-26} {1,8:N2} ms" -f "baseline p95 (no gateway)", $baselineP95 | Write-Host
    "{0,-26} {1,8:N2} ms" -f "gateway p95", $gatewayP95 | Write-Host
    "{0,-26} {1,8:N2} ms   (limit {2} ms)" -f "OVERHEAD (gateway-baseline)", $overhead, $MaxOverheadMs | Write-Host
    "k6 exit codes: baseline=$baselineExit gateway=$gatewayExit (0 = all thresholds held)" | Write-Host
    @{ baselineP95 = $baselineP95; gatewayP95 = $gatewayP95; overhead = $overhead; pass = $pass } |
        ConvertTo-Json | Set-Content -Encoding utf8 "$OutDir/overhead.json"

    # Copy the machine-readable results into docs/ so the evidence numbers can be checked later.
    New-Item -ItemType Directory -Force $EvidenceDir | Out-Null
    foreach ($f in @("baseline-summary.json", "gateway-summary.json", "overhead.json", "baseline-output.txt", "gateway-output.txt")) {
        Copy-Item "$OutDir/$f" "$EvidenceDir/$f" -Force
    }
    Write-Host "Results copied to $EvidenceDir"
    if ($pass) { Write-Host "QA2 PASSED" -ForegroundColor Green; $code = 0 } else { Write-Host "QA2 FAILED" -ForegroundColor Red; $code = 1 }
} finally {
    if (-not $NoStack -and -not $KeepStack) {
        Write-Host "`n== Stopping the stack ==" -ForegroundColor Cyan
        & docker @compose down -v
    }
}
exit $code
