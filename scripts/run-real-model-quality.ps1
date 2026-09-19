[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$OperationsToken,
    [string]$EnvFile = '.env',
    [string]$ReportDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) 'evaluation-reports/real-model'),
    [int]$TimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot
$envPath = if ([IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $repo $EnvFile }
$settings = @{}
if (Test-Path -LiteralPath $envPath) {
    Get-Content -LiteralPath $envPath | ForEach-Object {
        if ($_ -match '^\s*([^#=]+)=(.*)$') { $settings[$Matches[1].Trim()] = $Matches[2] }
    }
}
if (-not $OperationsToken) { $OperationsToken = $settings['OPERATIONS_TOKEN'] }
New-Item -ItemType Directory -Force -Path $ReportDirectory | Out-Null
$manifestPath = Join-Path $ReportDirectory 'manifest.json'
$required = @('LLM_ENABLED', 'LLM_PRIMARY_URL', 'LLM_PRIMARY_API_KEY', 'LLM_PRIMARY_SMALL_MODEL')
$missing = @($required | Where-Object { -not $settings[$_] -and -not [Environment]::GetEnvironmentVariable($_) })
$enabled = if ($settings['LLM_ENABLED']) { $settings['LLM_ENABLED'] } else { $env:LLM_ENABLED }
$manifest = [ordered]@{
    startedAt = (Get-Date).ToString('o')
    baseCommit = (& git rev-parse HEAD)
    provider = 'external OpenAI-compatible endpoint'
    mode = 'ACTIVE_MODEL'
    dataset = 'agent-v1 + rag-v1.2'
    promptVersion = 'reported by application'
    llmEnabled = ($enabled -eq 'true')
    primaryConfigured = ($missing -notcontains 'LLM_PRIMARY_URL' -and $missing -notcontains 'LLM_PRIMARY_API_KEY' -and $missing -notcontains 'LLM_PRIMARY_SMALL_MODEL')
    secretsRecorded = $false
    status = 'RUNNING'
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding utf8
try {
    if ($missing.Count -gt 0 -or $enabled -ne 'true' -or -not $OperationsToken) {
        $manifest.status = 'BLOCKED_EXTERNAL_PROVIDER'
        $manifest.missingPrerequisites = @($missing + $(if ($enabled -ne 'true') { 'LLM_ENABLED=true' }) + $(if (-not $OperationsToken) { 'OPERATIONS_TOKEN' }))
        $manifest.finishedAt = (Get-Date).ToString('o')
        $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding utf8
        throw "real-model preflight blocked; see $manifestPath"
    }

    & (Join-Path $PSScriptRoot 'run-evaluations.ps1') -BaseUrl $BaseUrl -OperationsToken $OperationsToken `
        -AgentMode ACTIVE_MODEL -ReportDirectory $ReportDirectory -TimeoutSeconds $TimeoutSeconds
    if ($LASTEXITCODE -ne 0) { throw 'evaluation gate failed' }
    $agent = Get-Content -Raw (Join-Path $ReportDirectory 'agent-evaluation.json') | ConvertFrom-Json
    if (-not $agent.model -or $agent.model -eq 'RULE_WORKFLOW') {
        throw 'ACTIVE_MODEL run fell back to RULE_WORKFLOW; no real provider quality evidence was produced'
    }
    $manifest.status = 'PASS'
    $manifest.model = $agent.model
    $manifest.agentMetrics = $agent.metrics
    $manifest.finishedAt = (Get-Date).ToString('o')
    $manifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $manifestPath -Encoding utf8
    Write-Output "REAL_MODEL_QUALITY=PASS EVIDENCE=$ReportDirectory"
} catch {
    if ($manifest.status -eq 'RUNNING') { $manifest.status = 'FAILED' }
    $manifest.error = $_.Exception.Message
    $manifest.finishedAt = (Get-Date).ToString('o')
    $manifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $manifestPath -Encoding utf8
    throw
}
