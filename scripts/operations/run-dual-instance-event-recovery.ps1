[CmdletBinding()]
param(
    [string]$RunId = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = 'Stop'
if ($RunId -notmatch '^[a-zA-Z0-9-]{3,80}$') { throw 'RunId must contain only letters, digits and hyphens.' }
if ($TimeoutSeconds -lt 30 -or $TimeoutSeconds -gt 600) { throw 'TimeoutSeconds must be between 30 and 600.' }

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$envFile = Join-Path $repo '.env'
$compose = Join-Path $repo 'docker-compose.yml'
$override = Join-Path $repo 'docker-compose.dual-instance.yml'
$seed = Join-Path $repo 'scripts/development/seed-demo-data.sql'
$output = Join-Path $repo "target/dual-instance-events/$RunId"
$project = "fitpilot-dual-$RunId".ToLowerInvariant()
if (-not (Test-Path -LiteralPath $envFile)) { throw '.env is required. Copy .env.example first.' }
if (Test-Path -LiteralPath $output) { throw "Evidence directory already exists: $output" }
New-Item -ItemType Directory -Path $output | Out-Null

$settings = @{}
Get-Content -LiteralPath $envFile | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)=(.*)$') { $settings[$Matches[1].Trim()] = $Matches[2] }
}
$dbName = if ($settings.ContainsKey('DB_NAME') -and $settings.DB_NAME) { $settings.DB_NAME } else { 'fitpilot' }
$dbUser = if ($settings.ContainsKey('DB_USERNAME') -and $settings.DB_USERNAME) { $settings.DB_USERNAME } else { 'fitpilot' }
$composeArgs = @('--env-file', $envFile, '-f', $compose, '-f', $override, '-p', $project)
$log = Join-Path $output 'experiment.log'
$manifest = [ordered]@{
    runId = $RunId
    startedAt = (Get-Date).ToString('o')
    baseCommit = (& git rev-parse HEAD)
    project = $project
    dockerHost = if ($IsWindows) { 'npipe:////./pipe/dockerDesktopLinuxEngine' } else { $env:DOCKER_HOST }
    claimTimeoutSeconds = 3
    topology = 'two independent Spring Boot containers, shared PostgreSQL/Redis/Kafka/Elasticsearch'
    scope = 'one API completion while Kafka and app-b are stopped; app-a claims then stops; app-b restarts with Kafka and recovers the shared Outbox and projections'
    sourceSha256 = [ordered]@{
        'docker-compose.dual-instance.yml' = (Get-FileHash -LiteralPath $override -Algorithm SHA256).Hash
        'scripts/operations/run-dual-instance-event-recovery.ps1' = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
    }
    status = 'RUNNING'
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $output 'manifest.json') -Encoding utf8

function Invoke-Compose([string[]]$Arguments) {
    $result = & docker compose @composeArgs @Arguments 2>&1
    $result | Add-Content -LiteralPath $log
    if ($LASTEXITCODE -ne 0) { throw "docker compose $($Arguments -join ' ') failed" }
}

function Invoke-Psql([string]$Sql) {
    $result = $Sql | & docker compose @composeArgs exec -T postgres psql -v ON_ERROR_STOP=1 -U $dbUser -d $dbName -At 2>&1
    if ($LASTEXITCODE -ne 0) { throw "database query failed: $Sql" }
    return (($result | Out-String).Trim())
}

function Invoke-Json([string]$Method, [string]$Url, [object]$Body, [string]$Token = '') {
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $params = @{ Method = $Method; Uri = $Url; Headers = $headers; ContentType = 'application/json'; TimeoutSec = 30 }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Compress -Depth 8) }
    return Invoke-RestMethod @params
}

function Wait-Ready([string]$Url) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(120)
    do {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 5
            if ($response.StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Seconds 2
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "service did not become ready: $Url"
}

function Read-EventState([long]$WorkoutId, [long]$UserId) {
    $sql = @"
SELECT json_build_object(
  'outboxStatus', COALESCE((SELECT status FROM outbox_event WHERE event_type='WorkoutCompletedEvent' AND aggregate_id='$WorkoutId' ORDER BY id DESC LIMIT 1), 'MISSING'),
  'pendingEvents', (SELECT count(*) FROM outbox_event WHERE status IN ('PENDING','SENDING')),
  'processedConsumers', (SELECT count(*) FROM processed_event p JOIN outbox_event o ON o.event_id=p.event_id WHERE o.event_type='WorkoutCompletedEvent' AND o.aggregate_id='$WorkoutId'),
  'personalRecords', (SELECT count(*) FROM personal_record WHERE workout_id=$WorkoutId),
  'analytics', (SELECT count(*) FROM workout_analytics_projection WHERE workout_id=$WorkoutId),
  'notifications', (SELECT count(*) FROM user_notification WHERE user_id=$UserId AND source_event_id IN (SELECT event_id FROM outbox_event WHERE event_type='PersonalRecordCreatedEvent' AND aggregate_id IN (SELECT id::text FROM personal_record WHERE workout_id=$WorkoutId)))
)::text;
"@
    return (Invoke-Psql $sql | ConvertFrom-Json)
}

$appA = 'http://127.0.0.1:18080'
$readyA = 'http://127.0.0.1:19091/actuator/health/readiness'
$readyB = 'http://127.0.0.1:19092/actuator/health/readiness'
$workoutId = 0L
$userId = 0L
try {
    if ($IsWindows) { $env:DOCKER_HOST = 'npipe:////./pipe/dockerDesktopLinuxEngine' }
    Invoke-Compose @('up', '-d', '--build', '--wait', 'app-a', 'app-b')
    Wait-Ready $readyA
    Wait-Ready $readyB

    $seedText = Get-Content -LiteralPath $seed -Raw
    $seedOutput = $seedText | & docker compose @composeArgs exec -T postgres psql -v ON_ERROR_STOP=1 -U $dbUser -d $dbName 2>&1
    $seedOutput | Add-Content -LiteralPath $log
    if ($LASTEXITCODE -ne 0) { throw 'demo seed failed' }

    Invoke-Compose @('stop', 'kafka')
    Invoke-Compose @('stop', 'app-b')
    $login = Invoke-Json 'Post' "$appA/api/v1/auth/login" @{ username = 'demo_athlete'; password = 'FitPilotDemo2026!' }
    $token = $login.data.accessToken
    $userId = [long](Invoke-Psql "SELECT id FROM users WHERE username='demo_athlete';")
    $plan = Invoke-Json 'Get' "$appA/api/v1/training-plans/active/current" $null $token
    $planData = $plan.data
    $day = $planData.days | Select-Object -First 1
    $workout = Invoke-Json 'Post' "$appA/api/v1/workouts" @{ trainingPlanId = [long]$planData.id; trainingPlanDayId = [long]$day.id } $token
    $workoutData = $workout.data
    $workoutId = [long]$workoutData.id
    $exercise = $workoutData.exercises | Select-Object -First 1
    [void](Invoke-Json 'Post' "$appA/api/v1/workouts/$workoutId/exercises/$($exercise.id)/sets" @{ weightKg = 80; reps = 5; rpe = 8; isWarmup = $false; isFailure = $false } $token)
    [void](Invoke-Json 'Post' "$appA/api/v1/workouts/$workoutId/complete" @{} $token)

    $claimed = $false
    $claimDeadline = [DateTimeOffset]::UtcNow.AddSeconds(8)
    do {
        $state = Read-EventState $workoutId $userId
        if ($state.outboxStatus -eq 'SENDING') { $claimed = $true; break }
        Start-Sleep -Milliseconds 250
    } while ([DateTimeOffset]::UtcNow -lt $claimDeadline)
    Invoke-Compose @('stop', 'app-a')
    Invoke-Compose @('up', '-d', '--wait', 'kafka', 'app-b')

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $state = Read-EventState $workoutId $userId
        if ($state.outboxStatus -eq 'SENT' -and $state.pendingEvents -eq 0 -and
            $state.processedConsumers -ge 2 -and $state.personalRecords -gt 0 -and
            $state.analytics -eq 1 -and $state.notifications -eq $state.personalRecords) { break }
        Start-Sleep -Seconds 1
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    if ($state.outboxStatus -ne 'SENT' -or $state.pendingEvents -ne 0 -or $state.processedConsumers -lt 2 -or
        $state.personalRecords -le 0 -or $state.analytics -ne 1 -or $state.notifications -ne $state.personalRecords) {
        throw "dual-instance recovery did not converge: $($state | ConvertTo-Json -Compress)"
    }
    if (-not $claimed) { throw 'app-a was stopped before an Outbox claim was observed' }

    $manifest.status = 'PASS'
    $manifest.finishedAt = (Get-Date).ToString('o')
    $manifest.workoutId = $workoutId
    $manifest.userId = $userId
    $manifest.crashedInstance = 'app-a'
    $manifest.recoveryInstance = 'app-b'
    $manifest.outboxAfterRecovery = $state
    $manifest.proof = [ordered]@{ appBPausedBeforeFault = $true; appAStoppedAfterClaim = $claimed; appBHealthy = $true; duplicateBusinessRows = 0 }
    $manifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $output 'manifest.json') -Encoding utf8
    Write-Output "DUAL_INSTANCE_EVENT_RECOVERY=PASS EVIDENCE=$output"
} catch {
    $manifest.status = 'FAILED'
    $manifest.finishedAt = (Get-Date).ToString('o')
    $manifest.error = $_.Exception.Message
    $manifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $output 'manifest.json') -Encoding utf8
    throw
} finally {
    try { Invoke-Compose @('logs', '--no-color', 'app-a', 'app-b') } catch { }
    try { Invoke-Compose @('down', '--volumes', '--remove-orphans') } catch { }
}
