param(
    [string]$BaseUrl = "http://localhost:8080",
    [Parameter(Mandatory = $true)][string]$OperationsToken,
    [ValidateSet("RULE_WORKFLOW", "ACTIVE_MODEL")][string]$AgentMode = "RULE_WORKFLOW",
    [string]$ReportDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) "evaluation-reports"),
    [int]$TimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"

function Invoke-Operations([string]$Method, [string]$Path, [string]$Body = "") {
    $arguments = @("--silent", "--show-error", "--fail-with-body", "--max-time", "30", "-X", $Method,
        "-H", "Accept: application/json", "-H", "X-Operations-Token: $OperationsToken")
    if ($Body) {
        $arguments += @("-H", "Content-Type: application/json", "--data-binary", "@-")
        $output = $Body | & curl.exe @arguments "$BaseUrl$Path" 2>&1
    } else { $output = & curl.exe @arguments "$BaseUrl$Path" 2>&1 }
    if ($LASTEXITCODE -ne 0) { throw "$Method $Path failed: $output" }
    return ($output | Out-String | ConvertFrom-Json)
}

function Wait-Run([string]$RunId) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $run = (Invoke-Operations GET "/api/v1/operations/evaluations/runs/$RunId").data
        if ($run.status -eq "SUCCEEDED") { return $run }
        if ($run.status -eq "FAILED") { throw "Evaluation $RunId failed: $($run.errorMessage)" }
        Start-Sleep -Seconds 2
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Evaluation $RunId timed out"
}

New-Item -ItemType Directory -Force -Path $ReportDirectory | Out-Null
$agentStarted = Invoke-Operations POST "/api/v1/operations/evaluations/agent/runs" "{`"mode`":`"$AgentMode`"}"
$ragStarted = Invoke-Operations POST "/api/v1/operations/evaluations/rag/runs"
$agent = Wait-Run $agentStarted.data.runId
$rag = Wait-Run $ragStarted.data.runId

if ($agent.metrics.toolSelectionAccuracy -lt 0.95 -or $agent.metrics.taskSuccessRate -lt 0.95 `
        -or $agent.metrics.constraintViolationRate -ne 0) {
    throw "Agent evaluation gate failed"
}
if ($rag.metrics.recallAt5 -lt 0.85 -or $rag.metrics.mrr -lt 0.75 -or $rag.metrics.citationValidity -ne 1) {
    throw "RAG evaluation gate failed"
}

$agent | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $ReportDirectory "agent-evaluation.json") -Encoding utf8
$rag | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $ReportDirectory "rag-evaluation.json") -Encoding utf8
Write-Host "Agent and RAG evaluation gates passed."
