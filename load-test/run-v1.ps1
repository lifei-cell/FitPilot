param(
    [string]$BaseUrl = "http://host.docker.internal:8080",
    [string]$Duration = "15s"
)

$ErrorActionPreference = "Stop"
$reportDirectory = Join-Path $PSScriptRoot "reports"
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null

foreach ($rate in 100, 500, 1000) {
    docker run --rm `
        -e "BASE_URL=$BaseUrl" `
        -e "RATE=$rate" `
        -e "DURATION=$Duration" `
        -v "${PSScriptRoot}:/work" `
        grafana/k6:0.57.0 run `
        --summary-export "/work/reports/v1-$rate-qps.json" `
        /work/v1-hot-read.js
    if ($LASTEXITCODE -ne 0) { throw "k6 validation failed at $rate QPS" }
}
