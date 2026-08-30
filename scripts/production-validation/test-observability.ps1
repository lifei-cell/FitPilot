param(
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$checks = [ordered]@{}

function Wait-For([string]$Name, [scriptblock]$Probe, [int]$Timeout = $TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($Timeout)
    do {
        try {
            $value = & $Probe
            if ($value) {
                $script:checks[$Name] = "PASS"
                return $value
            }
        } catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)
    $script:checks[$Name] = "FAIL: $lastError"
    throw "observability check '$Name' did not pass within $Timeout seconds"
}

function Prometheus-Query([string]$Query) {
    $encoded = [Uri]::EscapeDataString($Query)
    return Invoke-RestMethod -Uri "http://127.0.0.1:9090/api/v1/query?query=$encoded" -TimeoutSec 5
}

function Firing-PrometheusAlert([string]$AlertName) {
    $response = Invoke-RestMethod -Uri "http://127.0.0.1:9090/api/v1/alerts" -TimeoutSec 5
    return @($response.data.alerts | Where-Object { $_.labels.alertname -eq $AlertName -and $_.state -eq "firing" }).Count -gt 0
}

try {
    Wait-For "prometheus-ready" { (Invoke-WebRequest -Uri "http://127.0.0.1:9090/-/ready" -TimeoutSec 5).StatusCode -eq 200 } | Out-Null
    Wait-For "grafana-ready" { (Invoke-RestMethod -Uri "http://127.0.0.1:3000/api/health" -TimeoutSec 5).database -eq "ok" } | Out-Null
    Wait-For "loki-ready" { (Invoke-WebRequest -Uri "http://127.0.0.1:3100/ready" -TimeoutSec 5).StatusCode -eq 200 } | Out-Null
    Wait-For "tempo-ready" { (Invoke-WebRequest -Uri "http://127.0.0.1:3200/ready" -TimeoutSec 5).StatusCode -eq 200 } | Out-Null
    Wait-For "alertmanager-ready" { (Invoke-WebRequest -Uri "http://127.0.0.1:9093/-/ready" -TimeoutSec 5).StatusCode -eq 200 } | Out-Null
    Wait-For "prometheus-scrape" { [double](Prometheus-Query 'up{job="fitpilot"}').data.result[0].value[1] -eq 1 } | Out-Null

    1..5 | ForEach-Object {
        Invoke-WebRequest -Uri "http://127.0.0.1:8080/api/v1/exercises/1" -TimeoutSec 10 | Out-Null
    }
    Wait-For "loki-log-ingestion" {
        $query = [Uri]::EscapeDataString('{job="fitpilot"}')
        $result = Invoke-RestMethod -Uri "http://127.0.0.1:3100/loki/api/v1/query_range?query=$query&limit=20" -TimeoutSec 5
        @($result.data.result).Count -gt 0
    } | Out-Null
    Wait-For "tempo-trace-ingestion" {
        $result = Invoke-RestMethod -Uri "http://127.0.0.1:3200/api/search?limit=20" -TimeoutSec 10
        @($result.traces).Count -gt 0
    } | Out-Null

    & docker compose stop app | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "failed to stop app for alert validation" }
    Wait-For "prometheus-target-down-alert" { Firing-PrometheusAlert "FitPilotTargetDown" } | Out-Null
    Wait-For "alertmanager-received-alert" {
        $alerts = Invoke-RestMethod -Uri "http://127.0.0.1:9093/api/v2/alerts" -TimeoutSec 5
        @($alerts | Where-Object { $_.labels.alertname -eq "FitPilotTargetDown" -and $_.status.state -eq "active" }).Count -gt 0
    } | Out-Null
} finally {
    & docker compose start app | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Wait-For "app-recovered" { [double](Prometheus-Query 'up{job="fitpilot"}').data.result[0].value[1] -eq 1 } | Out-Null
        Wait-For "target-down-alert-resolved" { -not (Firing-PrometheusAlert "FitPilotTargetDown") } | Out-Null
    } else {
        $checks["app-recovered"] = "FAIL: docker compose start app failed"
    }
    [ordered]@{
        completedAt = (Get-Date).ToUniversalTime().ToString("o")
        checks = $checks
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutputDirectory "observability-validation.json") -Encoding utf8NoBOM
}
