param(
    [string]$RunId = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [int]$BatchSize = 100
)
$ErrorActionPreference = 'Stop'
if ($RunId -notmatch '^[a-zA-Z0-9-]{3,80}$') { throw 'RunId must contain only letters, digits and hyphens.' }
if ($BatchSize -lt 1) { throw 'BatchSize must be positive.' }

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$output = Join-Path $repo "target/outbox-backlog/$RunId"
if (Test-Path -LiteralPath $output) { throw "Evidence directory already exists: $output" }
New-Item -ItemType Directory -Path $output -Force | Out-Null
$previousOutput = $env:FITPILOT_OUTBOX_OUTPUT
$previousDocker = $env:DOCKER_HOST
$manifest = $null
Push-Location $repo
try {
    $env:FITPILOT_OUTBOX_OUTPUT = $output
    if ($IsWindows) { $env:DOCKER_HOST = 'npipe:////./pipe/dockerDesktopLinuxEngine' }
    $files = @(
        'pom.xml', 'src/main/resources/application.yml',
        'src/main/java/com/fitpilot/infrastructure/events/OutboxRelay.java',
        'src/main/java/com/fitpilot/infrastructure/events/OutboxRepository.java',
        'src/main/java/com/fitpilot/infrastructure/events/EventProperties.java',
        'src/test/java/com/fitpilot/infrastructure/events/OutboxBacklogBenchmark.java',
        'scripts/performance/run-outbox-backlog.ps1'
    )
    $hashes = [ordered]@{}
    foreach ($file in $files) { $hashes[$file] = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash }
    $manifest = [ordered]@{
        runId = $RunId
        startedAt = (Get-Date).ToString('o')
        baseCommit = (git rev-parse HEAD)
        worktreeStatus = @(git status --short)
        batchSize = $BatchSize
        java = @(& java -version 2>&1 | ForEach-Object { "$_" })
        docker = (& docker info --format '{{.ServerVersion}}')
        filesSha256 = $hashes
        status = 'RUNNING'
    }
    $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$output/manifest.json" -Encoding utf8
    & mvn test-compile failsafe:integration-test failsafe:verify '-Dit.test=OutboxBacklogBenchmark' "-Dfitpilot.backlog.batch-size=$BatchSize" *> "$output/experiment.log"
    if ($LASTEXITCODE -ne 0) { throw "Outbox backlog experiment failed; inspect $output/experiment.log" }
    if (-not (Test-Path -LiteralPath "$output/results.json")) { throw 'Missing results.json' }
    $manifest.status = 'PASS'
    $manifest.finishedAt = (Get-Date).ToString('o')
    $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$output/manifest.json" -Encoding utf8
    Write-Output "OUTBOX_BACKLOG=PASS EVIDENCE=$output"
} catch {
    if ($manifest) {
        $manifest.status = 'FAILED'
        $manifest.finishedAt = (Get-Date).ToString('o')
        $manifest.error = $_.Exception.Message
        $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$output/manifest.json" -Encoding utf8
    }
    throw
} finally {
    $env:FITPILOT_OUTBOX_OUTPUT = $previousOutput
    $env:DOCKER_HOST = $previousDocker
    Pop-Location
}
