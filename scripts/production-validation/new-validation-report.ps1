param(
    [Parameter(Mandatory = $true)][string]$RunId,
    [Parameter(Mandatory = $true)][string]$EvidenceDirectory,
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [Parameter(Mandatory = $true)][string]$VerifyStatus,
    [Parameter(Mandatory = $true)][string]$LoadStatus,
    [Parameter(Mandatory = $true)][string]$StartedAt,
    [Parameter(Mandatory = $true)][string]$CompletedAt
)

$ErrorActionPreference = "Stop"

function Read-Json([string]$Name) {
    $path = Join-Path $EvidenceDirectory $Name
    if (-not (Test-Path -LiteralPath $path)) { return $null }
    return Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
}

function Metric([object]$Report, [string]$Name, [string]$Field) {
    if ($null -eq $Report) { return "N/A" }
    $metric = $Report.metrics.PSObject.Properties[$Name].Value
    if ($null -eq $metric) { return "N/A" }
    $value = $metric.PSObject.Properties[$Field].Value
    if ($null -eq $value) { return "N/A" }
    return [math]::Round([double]$value, 4)
}

function Read-JsonLines([string]$Name) {
    $paths = @(Get-ChildItem -LiteralPath $EvidenceDirectory -Recurse -File -Filter $Name |
        Sort-Object FullName | Select-Object -ExpandProperty FullName)
    if ($paths.Count -eq 0) { return @() }
    return @($paths | ForEach-Object { Get-Content -LiteralPath $_ } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { $_ | ConvertFrom-Json })
}

function Maximum([object[]]$Rows, [string]$Property) {
    $values = @($Rows | Where-Object { $null -ne $_ -and $null -ne $_.PSObject.Properties[$Property] } |
        ForEach-Object { $_.PSObject.Properties[$Property].Value } |
        Where-Object { $null -ne $_ } | ForEach-Object { [double]$_ })
    if ($values.Count -eq 0) { return "N/A" }
    return [math]::Round(($values | Measure-Object -Maximum).Maximum, 4)
}

$mixed = Read-Json "v5-mixed.json"
$burst = Read-Json "v5-burst.json"
$observability = Read-Json "observability-validation.json"
$runtime = Read-JsonLines "runtime-metrics.jsonl"
$containers = Read-JsonLines "container-stats.jsonl"
$events = Read-JsonLines "event-status.jsonl"
$appContainers = @($containers | Where-Object { $_.container -match 'app' -and $null -ne $_.cpuPercent })
$maxAppCpu = Maximum @($appContainers | ForEach-Object {
    [pscustomobject]@{value=[double](($_.cpuPercent -replace '%','').Trim())}
}) "value"
$maxAppMemoryPercent = Maximum @($appContainers | ForEach-Object {
    [pscustomobject]@{value=[double](($_.memoryPercent -replace '%','').Trim())}
}) "value"
$observabilityPassed = $null -ne $observability -and
    @($observability.checks.PSObject.Properties | Where-Object { $_.Value -ne "PASS" }).Count -eq 0
$appLogPaths = @(Get-ChildItem -LiteralPath $EvidenceDirectory -Recurse -File -Filter "app.log" |
    Select-Object -ExpandProperty FullName)
$errorLines = @($appLogPaths | ForEach-Object {
    Select-String -LiteralPath $_ -Pattern '"level":"ERROR"'
})
$k6FailureLines = @(Get-ChildItem -LiteralPath $EvidenceDirectory -Recurse -File -Filter "v5-*.log" |
    ForEach-Object { Select-String -LiteralPath $_.FullName -Pattern 'Request Failed' })
$rerunDirectories = @(Get-ChildItem -LiteralPath $EvidenceDirectory -Directory -Filter "*-rerun-*" |
    Select-Object -ExpandProperty Name)
$lastEvent = @($events | Where-Object { $null -ne $_.data } |
    Sort-Object { [datetime]$_.timestamp } | Select-Object -Last 1)
$commit = (& git rev-parse HEAD).Trim()

$lines = @(
    "# FitPilot P0 生产验收报告",
    "",
    "- Run ID：``$RunId``",
    "- 验证前 Git 基线：``$commit``（P0 变更的最终提交以 Git 历史为准）",
    "- 开始时间：$StartedAt",
    "- 完成时间：$CompletedAt",
    "- 原始证据目录：``load-test/reports/v5-$RunId``（本地忽略，不提交凭据或大体积原始数据）",
    "- 局部重跑证据：$(if($rerunDirectories.Count -gt 0){($rerunDirectories -join ', ')}else{'无'})",
    "",
    "## 验收结论",
    "",
    "| 门禁 | 结果 |",
    "|---|---|",
    "| Maven 单元测试、Testcontainers E2E、Flyway、JaCoCo、SBOM | $VerifyStatus |",
    "| 30 分钟混合流量 + 5 分钟突发流量 | $LoadStatus |",
    "| Prometheus/Grafana/Loki/Tempo/Alertmanager 在线联调 | $(if($observabilityPassed){'PASS'}else{'FAIL'}) |",
    "| GitHub Actions、GHCR、真实 Kubernetes | BLOCKED：本机无 gh CLI、无 kube context 和生产凭据 |",
    "",
    "只有前三项通过且远端门禁产生真实流水线/集群证据后，才能批准生产流量。",
    "",
    "## 压测结果",
    "",
    "| 场景 | Iterations | HTTP 请求 | HTTP 失败率 | 业务成功率 | 普通 API P95 | Agent P95 | Dropped |",
    "|---|---:|---:|---:|---:|---:|---:|---:|",
    "| 30 分钟混合流量 | $(Metric $mixed 'iterations' 'count') | $(Metric $mixed 'http_reqs' 'count') | $(Metric $mixed 'http_req_failed' 'value') | $(Metric $mixed 'business_success' 'value') | $(Metric $mixed 'ordinary_api_duration' 'p(95)') ms | $(Metric $mixed 'agent_duration' 'p(95)') ms | $(Metric $mixed 'dropped_iterations' 'count') |",
    "| 5 分钟突发流量 | $(Metric $burst 'iterations' 'count') | $(Metric $burst 'http_reqs' 'count') | $(Metric $burst 'http_req_failed' 'value') | $(Metric $burst 'business_success' 'value') | $(Metric $burst 'ordinary_api_duration' 'p(95)') ms | $(Metric $burst 'agent_duration' 'p(95)') ms | $(Metric $burst 'dropped_iterations' 'count') |",
    "",
    "验收阈值：HTTP 失败率 <1%，业务成功率 >99%，普通 API P95 <250ms，Agent P95 <8s；突发场景不得出现 dropped iterations。",
    "",
    "## 资源与一致性证据",
    "",
    "- App 最大 CPU：$maxAppCpu%",
    "- App 最大内存占比：$maxAppMemoryPercent%",
    "- JVM Heap 最大值：$(Maximum $runtime 'heapUsedBytes') bytes",
    "- Hikari Active 最大值：$(Maximum $runtime 'hikariActive')，配置上限采样值：$(Maximum $runtime 'hikariMax')",
    "- Kafka Consumer Lag 最大值：$(Maximum $runtime 'kafkaLagMax')",
    "- Outbox Pending 最大值：$(Maximum $runtime 'outboxPending')",
    "- Outbox Oldest Age 最大值：$(Maximum $runtime 'outboxOldestSeconds') 秒",
    "- App ERROR 日志条数：$($errorLines.Count)",
    "- k6 HTTP 失败数：混合场景 $(Metric $mixed 'http_req_failed' 'passes')，突发场景 $(Metric $burst 'http_req_failed' 'passes')",
    "- 已归档 k6 客户端错误明细条数：$($k6FailureLines.Count)",
    "- 最终事件状态：``$($lastEvent | ConvertTo-Json -Depth 8 -Compress)``",
    "",
    "## 可观测性与告警证据",
    ""
)
if ($null -ne $observability) {
    foreach ($check in $observability.checks.PSObject.Properties) {
        $lines += "- $($check.Name)：$($check.Value)"
    }
} else {
    $lines += "- 未生成在线联调证据。"
}
$lines += @(
    "",
    "## 远端发布待办",
    "",
    "1. GitHub Actions 完成 OWASP、Gitleaks、Trivy、SBOM/provenance 与 GHCR 多架构镜像推送。",
    "2. 使用不可变镜像 Digest 执行 migration Job、2 副本滚动升级、readiness 和 PDB/HPA 验证。",
    "3. 在真实数据副本完成备份恢复、密钥轮换和版本回滚演练，并上传流水线与集群证据。",
    "4. 远端门禁未完成前，本报告结论保持 BLOCKED，不将本机渲染或 Compose 结果等同生产发布。"
)

$parent = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $parent | Out-Null
$lines | Set-Content -LiteralPath $OutputPath -Encoding utf8NoBOM
