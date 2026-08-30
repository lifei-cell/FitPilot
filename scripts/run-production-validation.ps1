param(
    [string]$Duration = "30m",
    [int]$Rate = 50,
    [int]$StartRate = 20,
    [int]$PeakRate = 200,
    [switch]$SkipMaven,
    [switch]$SkipLoad
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$runId = Get-Date -Format "yyyyMMdd-HHmmss"
$evidenceDirectory = Join-Path $root "load-test\reports\v5-$runId"
$reportPath = Join-Path $root "docs\release\p0-production-validation.md"
$startedAt = (Get-Date).ToUniversalTime().ToString("o")
$verifyStatus = "SKIPPED"
$loadStatus = "SKIPPED"
$collectorJob = $null
$composeStarted = $false
$pipelineError = $null

if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) { $env:DB_PASSWORD = "P0-$([Guid]::NewGuid().ToString('N'))" }
if ([string]::IsNullOrWhiteSpace($env:JWT_SECRET)) { $env:JWT_SECRET = "P0-jwt-$([Guid]::NewGuid().ToString('N'))" }
if ([string]::IsNullOrWhiteSpace($env:OPERATIONS_TOKEN)) { $env:OPERATIONS_TOKEN = "P0-ops-$([Guid]::NewGuid().ToString('N'))" }
if ([string]::IsNullOrWhiteSpace($env:GRAFANA_ADMIN_PASSWORD)) { $env:GRAFANA_ADMIN_PASSWORD = "P0-grafana-$([Guid]::NewGuid().ToString('N'))" }
$env:COMPOSE_PROJECT_NAME = "fitpilot-p0-$runId".ToLowerInvariant()

New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
Set-Location $root

try {
    if (-not $SkipMaven) {
        & mvn clean verify
        if ($LASTEXITCODE -ne 0) { throw "mvn clean verify failed" }
        $verifyStatus = "PASS"
    }

    $env:OTEL_TRACING_EXPORT_ENABLED = "true"
    $env:TRACING_SAMPLING_PROBABILITY = "1.0"
    & docker compose --profile observability up -d --build --wait
    if ($LASTEXITCODE -ne 0) { throw "production Compose baseline failed to start" }
    $composeStarted = $true

    $observabilityScript = Join-Path $PSScriptRoot "production-validation\test-observability.ps1"
    & $observabilityScript -OutputDirectory $evidenceDirectory

    if (-not $SkipLoad) {
        $collector = Join-Path $PSScriptRoot "production-validation\collect-runtime-evidence.ps1"
        $collectorJob = Start-Job -ScriptBlock {
            param($WorkingDirectory, $Script, $Output, $Token)
            Set-Location $WorkingDirectory
            & $Script -OutputDirectory $Output -OperationsToken $Token -IntervalSeconds 10
        } -ArgumentList $root, $collector, $evidenceDirectory, $env:OPERATIONS_TOKEN

        try {
            $loadParameters = @{
                Scenario = "all"
                Duration = $Duration
                Rate = $Rate
                StartRate = $StartRate
                PeakRate = $PeakRate
                RunId = $runId
            }
            & (Join-Path $root "load-test\run-v5.ps1") @loadParameters
            if ($LASTEXITCODE -ne 0) { throw "k6 validation failed" }
            $loadStatus = "PASS"
        } catch {
            $loadStatus = "FAIL: $($_.Exception.Message)"
        } finally {
            Stop-Job -Job $collectorJob -ErrorAction SilentlyContinue
            Receive-Job -Job $collectorJob -ErrorAction SilentlyContinue |
                Out-File (Join-Path $evidenceDirectory "collector.log")
            Remove-Job -Job $collectorJob -Force -ErrorAction SilentlyContinue
            $collectorJob = $null
        }
    }

    & docker compose logs --no-color app |
        Set-Content -LiteralPath (Join-Path $evidenceDirectory "app.log") -Encoding utf8NoBOM
} catch {
    $pipelineError = $_
    Set-Content -LiteralPath (Join-Path $evidenceDirectory "pipeline-error.txt") -Value $_.Exception.ToString() -Encoding utf8NoBOM
} finally {
    if ($null -ne $collectorJob) {
        Stop-Job -Job $collectorJob -ErrorAction SilentlyContinue
        Remove-Job -Job $collectorJob -Force -ErrorAction SilentlyContinue
    }
    if ($composeStarted) {
        & docker compose --profile observability down --volumes --remove-orphans
    }
    $completedAt = (Get-Date).ToUniversalTime().ToString("o")
    $reportParameters = @{
        RunId = $runId
        EvidenceDirectory = $evidenceDirectory
        OutputPath = $reportPath
        VerifyStatus = $verifyStatus
        LoadStatus = $loadStatus
        StartedAt = $startedAt
        CompletedAt = $completedAt
    }
    & (Join-Path $PSScriptRoot "production-validation\new-validation-report.ps1") @reportParameters
}

if ($null -ne $pipelineError) { throw $pipelineError }
if ($verifyStatus -ne "PASS" -or $loadStatus -ne "PASS") {
    throw "P0 production validation did not pass: verify=$verifyStatus load=$loadStatus"
}
Write-Output "P0_REPORT=$reportPath"
Write-Output "P0_EVIDENCE=$evidenceDirectory"
