param(
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [Parameter(Mandatory = $true)][string]$OperationsToken,
    [int]$IntervalSeconds = 10
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$containerPath = Join-Path $OutputDirectory "container-stats.jsonl"
$metricPath = Join-Path $OutputDirectory "runtime-metrics.jsonl"
$eventPath = Join-Path $OutputDirectory "event-status.jsonl"
Set-Content -LiteralPath $containerPath -Value "" -Encoding utf8NoBOM
Set-Content -LiteralPath $metricPath -Value "" -Encoding utf8NoBOM
Set-Content -LiteralPath $eventPath -Value "" -Encoding utf8NoBOM

function Read-PrometheusValue([string]$Query) {
    try {
        $encoded = [Uri]::EscapeDataString($Query)
        $response = Invoke-RestMethod -Uri "http://127.0.0.1:9090/api/v1/query?query=$encoded" -TimeoutSec 5
        $values = @($response.data.result | ForEach-Object { [double]$_.value[1] })
        if ($values.Count -eq 0) { return $null }
        return ($values | Measure-Object -Maximum).Maximum
    } catch {
        return $null
    }
}

while ($true) {
    $timestamp = (Get-Date).ToUniversalTime().ToString("o")
    try {
        $containerIds = @(& docker compose ps -q)
        if ($containerIds.Count -gt 0) {
            $lines = @(& docker stats --no-stream --format "{{json .}}" @containerIds)
            foreach ($line in $lines) {
                if ([string]::IsNullOrWhiteSpace($line)) { continue }
                $sample = $line | ConvertFrom-Json
                [ordered]@{
                    timestamp = $timestamp
                    container = $sample.Name
                    cpuPercent = $sample.CPUPerc
                    memoryUsage = $sample.MemUsage
                    memoryPercent = $sample.MemPerc
                    networkIO = $sample.NetIO
                    blockIO = $sample.BlockIO
                    pids = $sample.PIDs
                } | ConvertTo-Json -Compress | Add-Content -LiteralPath $containerPath -Encoding utf8NoBOM
            }
        }
    } catch {
        [ordered]@{ timestamp=$timestamp; collectionError=$_.Exception.Message } |
            ConvertTo-Json -Compress | Add-Content -LiteralPath $containerPath -Encoding utf8NoBOM
    }

    [ordered]@{
        timestamp = $timestamp
        appUp = Read-PrometheusValue 'up{job="fitpilot"}'
        processCpuUsage = Read-PrometheusValue 'process_cpu_usage'
        heapUsedBytes = Read-PrometheusValue 'sum(jvm_memory_used_bytes{area="heap"})'
        hikariActive = Read-PrometheusValue 'sum(hikaricp_connections_active)'
        hikariMax = Read-PrometheusValue 'sum(hikaricp_connections_max)'
        kafkaLagMax = Read-PrometheusValue 'max(kafka_consumer_fetch_manager_records_lag) or vector(0)'
        outboxPending = Read-PrometheusValue 'fitpilot_outbox_pending'
        outboxOldestSeconds = Read-PrometheusValue 'fitpilot_outbox_oldest_pending_seconds'
        http5xxRate = Read-PrometheusValue 'sum(rate(http_server_requests_seconds_count{status=~"5.."}[1m]))'
    } | ConvertTo-Json -Compress | Add-Content -LiteralPath $metricPath -Encoding utf8NoBOM

    try {
        $eventRequest = @{
            Uri = "http://127.0.0.1:8080/api/v1/operations/events/status"
            Headers = @{"X-Operations-Token"=$OperationsToken}
            TimeoutSec = 5
        }
        $event = Invoke-RestMethod @eventRequest
        [ordered]@{ timestamp=$timestamp; data=$event.data } | ConvertTo-Json -Depth 8 -Compress |
            Add-Content -LiteralPath $eventPath -Encoding utf8NoBOM
    } catch {
        [ordered]@{ timestamp=$timestamp; collectionError=$_.Exception.Message } |
            ConvertTo-Json -Compress | Add-Content -LiteralPath $eventPath -Encoding utf8NoBOM
    }
    Start-Sleep -Seconds $IntervalSeconds
}
