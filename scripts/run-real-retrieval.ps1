param([string]$RunId = (Get-Date -Format 'yyyyMMdd-HHmmss'))
$ErrorActionPreference = 'Stop'
if ($RunId -notmatch '^[a-zA-Z0-9-]{3,80}$') { throw 'Invalid RunId.' }
$repo = Split-Path $PSScriptRoot
$output = Join-Path $repo "target/real-retrieval/$RunId"
if (Test-Path -LiteralPath $output) { throw "Evidence directory already exists: $output" }
New-Item -ItemType Directory -Path $output -Force | Out-Null
$modelDir = Join-Path $repo 'target/models/paraphrase-multilingual-MiniLM-L12-v2'
$model = Join-Path $modelDir 'onnx/model_quint8_avx2.onnx'
$tokenizer = Join-Path $modelDir 'tokenizer.json'
$holdout = Join-Path $repo 'src/test/resources/retrieval/holdout-v1.json'
$expectedModel = '98A01D88B7DE996CDEA58C32CA71208C09968D143798814B2EA09D3439DC334F'
$expectedHoldout = 'EDDC5922F71DF776E8474BB7EB01AF3762CF627CCCEADAC1EA15546BB527DE91'
if (-not (Test-Path -LiteralPath $model) -or -not (Test-Path -LiteralPath $tokenizer)) {
    throw 'Pinned model files are missing. See docs/performance/real-retrieval-protocol-2026-09-17.md.'
}
if ((Get-FileHash -LiteralPath $model -Algorithm SHA256).Hash -ne $expectedModel) { throw 'ONNX model hash mismatch.' }
if ((Get-FileHash -LiteralPath $holdout -Algorithm SHA256).Hash -ne $expectedHoldout) { throw 'Holdout hash mismatch.' }
$previousDocker = $env:DOCKER_HOST
$server = $null
$metadata = $null
Push-Location $repo
try {
    if ($IsWindows) { $env:DOCKER_HOST = 'npipe:////./pipe/dockerDesktopLinuxEngine' }
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    $metadata = [ordered]@{
        runId = $RunId; startedAt = (Get-Date).ToString('o'); baseCommit = (git rev-parse HEAD)
        worktreeStatus = @(git status --short); os = [Environment]::OSVersion.ToString()
        logicalProcessors = [Environment]::ProcessorCount; java = @(& java -version 2>&1 | ForEach-Object { "$_" })
        python = (& python --version); docker = (& docker info --format '{{.ServerVersion}}')
        modelRevision = 'e8f8c211226b894fcb81acc59f3b34ba3efd5f42'
        modelSha256 = $expectedModel; tokenizerSha256 = (Get-FileHash $tokenizer -Algorithm SHA256).Hash
        holdoutSha256 = $expectedHoldout
        pythonDependencies = (& python -c "import importlib.metadata as m; print({x:m.version(x) for x in ['onnxruntime','tokenizers','numpy']})")
        corpusSha256 = (Get-FileHash 'src/main/resources/eval/rag-corpus-v1.jsonl' -Algorithm SHA256).Hash
        codeSha256 = [ordered]@{}
        status = 'RUNNING'
    }
    foreach ($name in @('scripts/evaluation/prepare_model.py',
            'scripts/evaluation/local_embedding_server.py',
            'scripts/evaluation/summarize_retrieval.py',
            'scripts/run-real-retrieval.ps1',
            'src/test/java/com/fitpilot/evaluation/RealEmbeddingRetrievalBenchmark.java')) {
        $metadata.codeSha256[$name] = (Get-FileHash -LiteralPath $name -Algorithm SHA256).Hash
    }
    foreach ($image in @('pgvector/pgvector:pg17', 'redis:7.4-alpine',
            'docker.elastic.co/elasticsearch/elasticsearch:8.17.4')) {
        $metadata["image:$image"] = (& docker image inspect $image --format '{{.Id}}')
    }
    $metadata | ConvertTo-Json -Depth 8 | Set-Content "$output/manifest.json" -Encoding utf8
    $server = Start-Process python -ArgumentList @('scripts/evaluation/local_embedding_server.py',
        '--model-dir', "`"$modelDir`"", '--port', "$port") -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput "$output/model.stdout.log" -RedirectStandardError "$output/model.stderr.log"
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($server.HasExited) { throw 'Embedding server exited during startup.' }
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$port/health" -TimeoutSec 1
            if ($health.dimensions -eq 384) { $ready = $true; break }
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $ready) { throw 'Embedding server did not become ready.' }
    $before = Invoke-RestMethod "http://127.0.0.1:$port/stats"
    & mvn test-compile failsafe:integration-test failsafe:verify '-Dit.test=RealEmbeddingRetrievalBenchmark' `
        "-Dfitpilot.real-embedding.port=$port" "-Dfitpilot.real-retrieval.output=$output/raw.json" `
        '-DskipTests=false' '-DskipITs=false' '-Dmaven.test.skip=false' *> "$output/maven.log"
    if ($LASTEXITCODE -ne 0) { throw "Benchmark failed; inspect $output/maven.log" }
    $after = Invoke-RestMethod "http://127.0.0.1:$port/stats"
    $stats = [ordered]@{ requests = $after.requests - $before.requests
        tokens = $after.tokens - $before.tokens
        inferenceMs = [Math]::Round($after.inferenceMs - $before.inferenceMs, 3)
        modelWorkingSetBytes = (Get-Process -Id $server.Id).WorkingSet64
        modelFileBytes = (Get-Item -LiteralPath $model).Length }
    $stats | ConvertTo-Json | Set-Content "$output/model-stats.json" -Encoding utf8
    & python scripts/evaluation/summarize_retrieval.py "$output/raw.json" "$output/summary.json" `
        --holdout "$holdout" --tokenizer "$tokenizer"
    if ($LASTEXITCODE -ne 0) { throw 'Summary failed.' }
    $metadata.status = 'PASS'
    $metadata.finishedAt = (Get-Date).ToString('o')
    $metadata | ConvertTo-Json -Depth 8 | Set-Content "$output/manifest.json" -Encoding utf8
    Write-Output "REAL_RETRIEVAL=PASS EVIDENCE=$output"
} catch {
    if ($metadata) {
        $metadata.status = 'FAILED'
        $metadata.finishedAt = (Get-Date).ToString('o')
        $metadata | ConvertTo-Json -Depth 8 | Set-Content "$output/manifest.json" -Encoding utf8
    }
    throw
} finally {
    if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id }
    $env:DOCKER_HOST = $previousDocker
    Pop-Location
}
