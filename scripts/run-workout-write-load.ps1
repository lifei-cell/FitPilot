param(
    [string]$RunId = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [int]$Concurrency = 32,
    [int]$MultiUsers = 24,
    [int]$MultiRequests = 384,
    [int]$SameRequests = 192,
    [int]$OfflineUnique = 96,
    [int]$OfflineReplays = 2
)
$ErrorActionPreference = 'Stop'
if ($RunId -notmatch '^[a-zA-Z0-9-]{3,80}$') { throw 'RunId must contain only letters, digits and hyphens.' }
$repo = Split-Path $PSScriptRoot
$output = Join-Path $repo "target/workout-write-load/$RunId"
if (Test-Path -LiteralPath $output) { throw "Evidence directory already exists: $output" }
New-Item -ItemType Directory -Path $output | Out-Null
$previousOutput = $env:FITPILOT_LOAD_OUTPUT
$previousDocker = $env:DOCKER_HOST
$metadata = $null
Push-Location $repo
try {
    $env:FITPILOT_LOAD_OUTPUT = $output
    if ($IsWindows) { $env:DOCKER_HOST = 'npipe:////./pipe/dockerDesktopLinuxEngine' }
    $files = Get-ChildItem src/main,src/test -Recurse -File
    $files += Get-Item pom.xml,scripts/run-workout-write-load.ps1
    $hashes = [ordered]@{}
    foreach ($file in ($files | Sort-Object FullName)) {
        $hashes[[IO.Path]::GetRelativePath($repo, $file.FullName).Replace('\','/')] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    }
    $metadata = [ordered]@{
        runId = $RunId
        startedAt = (Get-Date).ToString('o')
        baseCommit = (git rev-parse HEAD)
        worktreeStatus = @(git status --short)
        java = @(& java -version 2>&1 | ForEach-Object { "$_" })
        maven = (& mvn -version 2>&1 | Select-Object -First 1)
        docker = (& docker info --format '{{.ServerVersion}}')
        os = [Environment]::OSVersion.ToString()
        logicalProcessors = [Environment]::ProcessorCount
        filesSha256 = $hashes
        settings = [ordered]@{
            concurrency = $Concurrency; multiUsers = $MultiUsers; multiRequests = $MultiRequests
            sameRequests = $SameRequests; offlineUnique = $OfflineUnique; offlineReplays = $OfflineReplays
            hikariMaximumPoolSize = 16; samplingIntervalMs = 50
        }
        scope = 'Isolated PostgreSQL/Redis Testcontainers; service-level writes; Events relay disabled; not production capacity'
        status = 'RUNNING'
    }
    $metadata | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath "$output/manifest.json" -Encoding utf8
    $mavenArgs = @(
        'test-compile', 'failsafe:integration-test', 'failsafe:verify',
        '-Dit.test=WorkoutWriteLoadBenchmark',
        "-Dfitpilot.load.concurrency=$Concurrency",
        "-Dfitpilot.load.multi-users=$MultiUsers",
        "-Dfitpilot.load.multi-requests=$MultiRequests",
        "-Dfitpilot.load.same-requests=$SameRequests",
        "-Dfitpilot.load.offline-unique=$OfflineUnique",
        "-Dfitpilot.load.offline-replays=$OfflineReplays"
    )
    & mvn @mavenArgs *> "$output/experiment.log"
    $code = $LASTEXITCODE
    if ($code -ne 0) { throw "Write load failed; inspect $output/experiment.log" }
    if (-not (Test-Path -LiteralPath "$output/results.json")) { throw "Missing results evidence: $output/results.json" }
    $metadata.status = 'PASS'
    $metadata.finishedAt = (Get-Date).ToString('o')
    $metadata | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath "$output/manifest.json" -Encoding utf8
    Write-Output "WRITE_LOAD=PASS EVIDENCE=$output"
} catch {
    if ($metadata) {
        $metadata.status = 'FAILED'
        $metadata.finishedAt = (Get-Date).ToString('o')
        $metadata.error = $_.Exception.Message
        $metadata | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath "$output/manifest.json" -Encoding utf8
    }
    throw
} finally {
    $env:FITPILOT_LOAD_OUTPUT = $previousOutput
    $env:DOCKER_HOST = $previousDocker
    Pop-Location
}
