param([string]$RunId = (Get-Date -Format 'yyyyMMdd-HHmmss'))
$ErrorActionPreference = 'Stop'
if ($RunId -notmatch '^[a-zA-Z0-9-]{3,80}$') { throw 'RunId must contain only letters, digits and hyphens.' }
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$output = Join-Path $repo "target/architecture-comparison/$RunId"
if (Test-Path -LiteralPath $output) { throw "Evidence directory already exists: $output" }
New-Item -ItemType Directory -Path $output | Out-Null
$previousOutput = $env:FITPILOT_COMPARISON_OUTPUT
$previousDocker = $env:DOCKER_HOST
Push-Location $repo
try {
    $env:FITPILOT_COMPARISON_OUTPUT = $output
    if ($IsWindows) { $env:DOCKER_HOST = 'npipe:////./pipe/dockerDesktopLinuxEngine' }
    $files = Get-ChildItem src/main,src/test -Recurse -File
    $files += Get-Item pom.xml,scripts/performance/run-architecture-comparison.ps1
    $hashes = [ordered]@{}
    foreach ($file in ($files | Sort-Object FullName)) {
        $hashes[[IO.Path]::GetRelativePath($repo, $file.FullName).Replace('\','/')] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    }
    $metadata = [ordered]@{
        runId = $RunId; startedAt = (Get-Date).ToString('o'); baseCommit = (git rev-parse HEAD)
        worktreeStatus = @(git status --short); java = @(& java -version 2>&1 | ForEach-Object { "$_" })
        docker = (& docker info --format '{{.ServerVersion}}'); os = [Environment]::OSVersion.ToString()
        logicalProcessors = [Environment]::ProcessorCount; filesSha256 = $hashes
        scope = 'Isolated Testcontainers; sequential mechanism comparisons; no external AI provider; not production capacity'
        status = 'RUNNING'
    }
    $metadata | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$output/manifest.json" -Encoding utf8
    & mvn test-compile failsafe:integration-test failsafe:verify '-Dit.test=ArchitectureComparison' *> "$output/experiment.log"
    $code = $LASTEXITCODE
    if ($code -ne 0) { throw "Comparison failed; inspect $output/experiment.log" }
    foreach ($name in @('cache','events','retrieval','containers')) {
        if (-not (Test-Path -LiteralPath "$output/$name.json")) { throw "Missing $name evidence" }
    }
    $metadata.status = 'PASS'
    $metadata.finishedAt = (Get-Date).ToString('o')
    $metadata | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$output/manifest.json" -Encoding utf8
    Write-Output "COMPARISON=PASS EVIDENCE=$output"
} catch {
    if ($metadata) {
        $metadata.status = 'FAILED'
        $metadata.finishedAt = (Get-Date).ToString('o')
        $metadata | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$output/manifest.json" -Encoding utf8
    }
    throw
} finally {
    $env:FITPILOT_COMPARISON_OUTPUT = $previousOutput
    $env:DOCKER_HOST = $previousDocker
    Pop-Location
}
