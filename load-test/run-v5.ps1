param(
    [ValidateSet("mixed", "burst", "all")]
    [string]$Scenario = "all",
    [string]$BaseUrl = "http://host.docker.internal:8080",
    [string]$Duration = "30m",
    [int]$Rate = 50,
    [int]$StartRate = 20,
    [int]$PeakRate = 200,
    [string]$RunId = (Get-Date -Format "yyyyMMdd-HHmmss")
)

$ErrorActionPreference = "Stop"
$reportDirectory = Join-Path $PSScriptRoot "reports\v5-$RunId"
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null

function Invoke-K6([string]$Script, [string]$Report, [string[]]$Environment) {
    $arguments = @("run", "--rm", "-e", "BASE_URL=$BaseUrl")
    foreach ($item in $Environment) { $arguments += @("-e", $item) }
    $arguments += @("-v", "${PSScriptRoot}:/work", "grafana/k6:0.57.0", "run", "--quiet",
        "--summary-export", "/work/reports/v5-$RunId/$Report", "/work/$Script")
    $logPath = Join-Path $reportDirectory ($Report -replace '\.json$', '.log')
    & docker @arguments 2>&1 | Tee-Object -LiteralPath $logPath
    $exitCode = $LASTEXITCODE
    $reportPath = Join-Path $reportDirectory $Report
    if (Test-Path -LiteralPath $reportPath) { Remove-ReportSecrets $reportPath }
    if ($exitCode -ne 0) { throw "k6 scenario $Script failed its acceptance thresholds" }
}

function Remove-ReportSecrets([string]$Path) {
    $report = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    if ($null -ne $report.setup_data) {
        $report.setup_data.PSObject.Properties.Remove("token")
    }
    $report | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $Path -Encoding utf8NoBOM
}

$failures = @()
if ($Scenario -in @("mixed", "all")) {
    try { Invoke-K6 "v5-mixed.js" "v5-mixed.json" @("DURATION=$Duration", "RATE=$Rate") }
    catch { $failures += $_.Exception.Message }
}
if ($Scenario -in @("burst", "all")) {
    try { Invoke-K6 "v5-burst.js" "v5-burst.json" @("START_RATE=$StartRate", "PEAK_RATE=$PeakRate") }
    catch { $failures += $_.Exception.Message }
}

Write-Output "K6_REPORT_DIRECTORY=$reportDirectory"
if ($failures.Count -gt 0) { throw ($failures -join "; ") }
