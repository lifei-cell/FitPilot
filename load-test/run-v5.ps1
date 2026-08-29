param(
    [ValidateSet("mixed", "burst", "all")]
    [string]$Scenario = "all",
    [string]$BaseUrl = "http://host.docker.internal:8080",
    [string]$Duration = "30m",
    [int]$Rate = 50,
    [int]$StartRate = 20,
    [int]$PeakRate = 200
)

$ErrorActionPreference = "Stop"
$reportDirectory = Join-Path $PSScriptRoot "reports"
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null

function Invoke-K6([string]$Script, [string]$Report, [string[]]$Environment) {
    $arguments = @("run", "--rm", "-e", "BASE_URL=$BaseUrl")
    foreach ($item in $Environment) { $arguments += @("-e", $item) }
    $arguments += @("-v", "${PSScriptRoot}:/work", "grafana/k6:0.57.0", "run", "--quiet",
        "--summary-export", "/work/reports/$Report", "/work/$Script")
    & docker @arguments
    if ($LASTEXITCODE -ne 0) { throw "k6 scenario $Script failed its acceptance thresholds" }
}

if ($Scenario -in @("mixed", "all")) {
    Invoke-K6 "v5-mixed.js" "v5-mixed.json" @("DURATION=$Duration", "RATE=$Rate")
}
if ($Scenario -in @("burst", "all")) {
    Invoke-K6 "v5-burst.js" "v5-burst.json" @("START_RATE=$StartRate", "PEAK_RATE=$PeakRate")
}
