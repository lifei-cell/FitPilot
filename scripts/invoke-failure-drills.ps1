param(
    [string]$BaseUrl = "http://localhost:8080",
    [Parameter(Mandatory = $true)][string]$OperationsToken,
    [string]$EnvFile = (Join-Path (Split-Path $PSScriptRoot -Parent) ".env.example"),
    [switch]$IncludeLlm
)

$ErrorActionPreference = "Stop"
$composeFile = Join-Path (Split-Path $PSScriptRoot -Parent) "docker-compose.yml"
$suffix = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$username = "v5drill_$suffix"
$password = "FitPilot!V5Drill"
$jsonHeaders = @{ "Content-Type" = "application/json" }
$operationsHeaders = @{ "X-Operations-Token" = $OperationsToken }

function Invoke-Json([string]$Method, [string]$Path, $Body = $null, [hashtable]$Headers = @{}) {
    $arguments = @("--silent", "--show-error", "--fail-with-body", "--max-time", "20", "-X", $Method,
        "-H", "Accept: application/json")
    foreach ($entry in $Headers.GetEnumerator()) { $arguments += @("-H", "$($entry.Key): $($entry.Value)") }
    if ($null -ne $Body) {
        $arguments += @("-H", "Content-Type: application/json", "--data-binary", "@-")
        $output = ($Body | ConvertTo-Json -Depth 10 -Compress) | & curl.exe @arguments "$BaseUrl$Path" 2>&1
    } else {
        $output = & curl.exe @arguments "$BaseUrl$Path" 2>&1
    }
    if ($LASTEXITCODE -ne 0) { throw "$Method $Path failed: $output" }
    try { return ($output | Out-String | ConvertFrom-Json) }
    catch { throw "$Method $Path returned invalid JSON" }
}

function Wait-Ready {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try {
            $health = Invoke-Json GET "/actuator/health/readiness"
            if ($health.status -eq "UP") { return }
        } catch { }
        Start-Sleep -Seconds 2
    }
    throw "FitPilot readiness did not recover"
}

function Invoke-Compose([string[]]$Arguments) {
    & docker compose -f $composeFile --env-file $EnvFile @Arguments | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "docker compose $($Arguments -join ' ') failed" }
}

function Stop-Service([string]$Name) { Invoke-Compose @("stop", $Name) }
function Start-Service([string]$Name) { Invoke-Compose @("up", "-d", "--wait", $Name) }

function New-DrillContext {
    Invoke-Json POST "/api/v1/auth/register" @{ username=$username; email="$username@example.invalid"; password=$password } $jsonHeaders | Out-Null
    $login = Invoke-Json POST "/api/v1/auth/login" @{ username=$username; password=$password } $jsonHeaders
    $auth = @{ Authorization = "Bearer $($login.data.accessToken)" }
    $plan = Invoke-Json POST "/api/v1/training-plans" @{
        name="Failure drill"; goal="STRENGTH"; durationWeeks=4
        days=@(@{ dayNumber=1; name="Day 1"; exercises=@(@{
            exerciseId=1; sequence=1; targetSets=3; targetRepsMin=5; targetRepsMax=8; targetRpe=8; restSeconds=120
        }) })
    } $auth
    Invoke-Json POST "/api/v1/training-plans/$($plan.data.id)/activate" $null $auth | Out-Null
    return @{ Headers=$auth; PlanId=$plan.data.id; DayId=$plan.data.days[0].id }
}

function Test-RedisFallback($context) {
    Write-Host "[drill] Redis cache fallback"
    Stop-Service "redis"
    try {
        $response = Invoke-Json GET "/api/v1/exercises/1" $null $context.Headers
        if ($response.code -ne 0) { throw "Redis fallback returned a business error" }
    } finally { Start-Service "redis" }
}

function Test-KafkaOutboxRecovery($context) {
    Write-Host "[drill] Kafka outage and outbox recovery"
    Stop-Service "kafka"
    try {
        $workout = Invoke-Json POST "/api/v1/workouts" @{
            trainingPlanId=$context.PlanId; trainingPlanDayId=$context.DayId; name="Kafka outage workout"
        } $context.Headers
        $exerciseId = $workout.data.exercises[0].id
        Invoke-Json POST "/api/v1/workouts/$($workout.data.id)/exercises/$exerciseId/sets" @{
            weightKg=60; reps=8; rpe=8; rir=2; isWarmup=$false; isFailure=$false
        } $context.Headers | Out-Null
        Invoke-Json POST "/api/v1/workouts/$($workout.data.id)/complete" $null $context.Headers | Out-Null
    } finally { Start-Service "kafka" }

    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $status = Invoke-Json GET "/api/v1/operations/events/status" $null $operationsHeaders
        $pending = if ($null -eq $status.data.outbox.PENDING) { 0 } else { [int]$status.data.outbox.PENDING }
        if ($pending -eq 0) { return }
        Start-Sleep -Seconds 2
    }
    throw "Kafka recovered but outbox events were not published within 60 seconds"
}

function Test-ElasticsearchFallback($context) {
    Write-Host "[drill] Elasticsearch outage and vector fallback"
    Stop-Service "elasticsearch"
    try {
        $response = Invoke-Json GET "/api/v1/rag/search?q=squat&topK=5" $null $context.Headers
        if ($response.data.retrievalMode -ne "VECTOR_ONLY") {
            throw "Expected VECTOR_ONLY RAG degradation, got $($response.data.retrievalMode)"
        }
    } finally { Start-Service "elasticsearch" }
}

function Test-LlmFallback($context) {
    Write-Host "[drill] Primary and dual LLM outage"
    $env:LLM_ENABLED = "true"
    $env:LLM_PRIMARY_URL = "http://llm-primary-mock:8080/v1/chat/completions"
    $env:LLM_PRIMARY_SMALL_MODEL = "mock-primary-small"
    $env:LLM_PRIMARY_MEDIUM_MODEL = "mock-primary-medium"
    $env:LLM_PRIMARY_STRONG_MODEL = "mock-primary-strong"
    $env:LLM_FALLBACK_URL = "http://llm-fallback-mock:8080/v1/chat/completions"
    $env:LLM_FALLBACK_SMALL_MODEL = "mock-fallback-small"
    $env:LLM_FALLBACK_MEDIUM_MODEL = "mock-fallback-medium"
    $env:LLM_FALLBACK_STRONG_MODEL = "mock-fallback-strong"
    Invoke-Compose @("--profile", "fault", "up", "-d", "--wait", "llm-primary-mock", "llm-fallback-mock", "app")
    Wait-Ready

    try {
        Stop-Service "llm-primary-mock"
        $session = Invoke-Json POST "/api/v1/agent/sessions" $null $context.Headers
        $fallback = Invoke-Json POST "/api/v1/agent/sessions/$($session.data.id)/messages" @{ message="查看当前计划" } $context.Headers
        if (-not $fallback.data.degraded -or $fallback.data.model -notlike "mock-fallback-*") {
            throw "Primary outage did not select the fallback model"
        }

        Stop-Service "llm-fallback-mock"
        $ruleSession = Invoke-Json POST "/api/v1/agent/sessions" $null $context.Headers
        $rule = Invoke-Json POST "/api/v1/agent/sessions/$($ruleSession.data.id)/messages" @{ message="查看当前计划" } $context.Headers
        if (-not $rule.data.degraded -or $rule.data.model -ne "RULE_WORKFLOW") {
            throw "Dual LLM outage did not select RULE_WORKFLOW"
        }
    } finally {
        Start-Service "llm-primary-mock"
        Start-Service "llm-fallback-mock"
    }
}

Wait-Ready
Write-Host "[drill] Creating isolated test user and active plan"
$context = New-DrillContext
Test-RedisFallback $context
Test-KafkaOutboxRecovery $context
Test-ElasticsearchFallback $context
if ($IncludeLlm) { Test-LlmFallback $context }
Wait-Ready
Write-Host "All requested V5 failure drills passed."
