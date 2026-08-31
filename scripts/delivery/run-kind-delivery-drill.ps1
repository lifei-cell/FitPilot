param(
    [string]$KindExecutable = "kind",
    [string]$ClusterName = "fitpilot-delivery",
    [string]$EvidenceDirectory = "release-evidence/kind",
    [switch]$KeepCluster
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Import-Module (Join-Path $PSScriptRoot "DeliveryGate.Common.psm1") -Force -DisableNameChecking

if ($ClusterName -ne "fitpilot-delivery") {
    throw "the drill only permits the fixed isolated cluster name 'fitpilot-delivery'"
}
$context = "kind-$ClusterName"
$infraProject = "fitpilot-delivery-infra"
$infraCompose = Join-Path $root "deploy/kind/docker-compose.yml"
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) "fitpilot-delivery-$([Guid]::NewGuid().ToString('N'))"
$baselineSource = Join-Path $temporaryRoot "baseline"
$archive = Join-Path $temporaryRoot "baseline.tar"
$clusterCreated = $false
$infraStarted = $false
$previousLocation = Get-Location

$databasePassword = "db-$([Guid]::NewGuid().ToString('N'))"
$initialJwtSecret = "jwt-old-$([Guid]::NewGuid().ToString('N'))"
$rotatedJwtSecret = "jwt-new-$([Guid]::NewGuid().ToString('N'))"
$initialOperationsToken = "ops-old-$([Guid]::NewGuid().ToString('N'))"
$rotatedOperationsToken = "ops-new-$([Guid]::NewGuid().ToString('N'))"
$baselineApp = "fitpilot-delivery-app:baseline"
$candidateApp = "fitpilot-delivery-app:candidate"
$baselineWeb = "fitpilot-delivery-web:baseline"
$candidateWeb = "fitpilot-delivery-web:candidate"
$backupToolImage = "fitpilot-delivery-pgtools:pg17"

function Invoke-Required([string]$Command, [string[]]$Arguments) {
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command failed with exit code $LASTEXITCODE" }
}

function New-FitPilotSecret {
    $data = @{
        DB_PASSWORD = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($databasePassword))
        JWT_SECRET = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($initialJwtSecret))
        OPERATIONS_TOKEN = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($initialOperationsToken))
    }
    $manifest = @{
        apiVersion = "v1"
        kind = "Secret"
        metadata = @{name = "fitpilot-secrets"; namespace = "fitpilot"}
        type = "Opaque"
        data = $data
    } | ConvertTo-Json -Depth 6
    Apply-Manifest -Context $context -Manifest $manifest
}

New-Item -ItemType Directory -Force -Path $EvidenceDirectory, $baselineSource | Out-Null
Set-Location $root
try {
    $existingClusters = & $KindExecutable get clusters
    if ($LASTEXITCODE -ne 0) { throw "unable to list Kind clusters" }
    if ($existingClusters -contains $ClusterName) {
        throw "isolated cluster '$ClusterName' already exists; remove it explicitly before this drill"
    }

    Invoke-Required "git" @("archive", "--format=tar", "HEAD", "-o", $archive)
    Invoke-Required "tar" @("-xf", $archive, "-C", $baselineSource)
    Invoke-Required "docker" @("build", "-t", $baselineApp, $baselineSource)
    Invoke-Required "docker" @("build", "-t", $baselineWeb, (Join-Path $baselineSource "web"))
    Invoke-Required "docker" @("build", "-t", $candidateApp, $root)
    Invoke-Required "docker" @("build", "-t", $candidateWeb, (Join-Path $root "web"))
    Invoke-Required "docker" @(
        "build", "--provenance=false", "-t", $backupToolImage,
        "-f", (Join-Path $root "deploy/kind/PgTools.Dockerfile"), $root
    )

    $env:DELIVERY_DB_PASSWORD = $databasePassword
    Invoke-Required "docker" @("compose", "-p", $infraProject, "-f", $infraCompose, "up", "-d", "--wait")
    $infraStarted = $true

    Invoke-Required $KindExecutable @("create", "cluster", "--name", $ClusterName, "--wait", "3m")
    $clusterCreated = $true
    Invoke-Required $KindExecutable @(
        "load", "docker-image", $baselineApp, $baselineWeb, $candidateApp, $candidateWeb,
        $backupToolImage, "--name", $ClusterName
    )
    Invoke-Required "kubectl" @("--context", $context, "apply", "-f", "deploy/k8s/base/namespace.yml")
    New-FitPilotSecret

    $config = @{
        DB_URL = "jdbc:postgresql://host.docker.internal:15432/fitpilot"
        DB_USERNAME = "fitpilot"
        REDIS_HOST = "host.docker.internal"
        REDIS_PORT = "16379"
        EVENTS_ENABLED = "false"
        RAG_ENABLED = "false"
        LLM_ENABLED = "false"
        OTEL_TRACING_EXPORT_ENABLED = "false"
    }
    & (Join-Path $PSScriptRoot "run-kubernetes-rollout.ps1") `
        -KubeContext $context -AppImage $baselineApp -WebImage $baselineWeb `
        -EvidenceDirectory (Join-Path $EvidenceDirectory "baseline") `
        -ConfigOverrides $config -AllowMutableImages -DeleteNetworkPoliciesForHostBridge

    Invoke-Required "docker" @(
        "compose", "-p", $infraProject, "-f", $infraCompose, "exec", "-T", "postgres",
        "psql", "-U", "fitpilot", "-d", "fitpilot", "-c",
        "insert into users(username,email,password_hash) values ('delivery-drill','delivery@invalid.local','not-a-login-hash') on conflict do nothing"
    )

    $sourceUrl = "postgresql://fitpilot:$databasePassword@host.docker.internal:15432/fitpilot"
    $restoreUrl = "postgresql://fitpilot:$databasePassword@host.docker.internal:15432/fitpilot_restore"
    & (Join-Path $PSScriptRoot "prepare-database-drill.ps1") `
        -KubeContext $context -SourceDatabaseUrl $sourceUrl -RestoreDatabaseUrl $restoreUrl

    $gateParameters = @{
        KubeContext = $context
        AppImage = $candidateApp
        WebImage = $candidateWeb
        RotatedJwtSecret = $rotatedJwtSecret
        RotatedOperationsToken = $rotatedOperationsToken
        EvidenceDirectory = (Join-Path $EvidenceDirectory "candidate")
        ConfigOverrides = $config
        RollbackDrill = $true
        BackupRestoreDrill = $true
        SecretRotationDrill = $true
        RevokePreviousJwt = $true
        AllowMutableImages = $true
        BackupToolImage = $backupToolImage
        DeleteNetworkPoliciesForHostBridge = $true
    }
    & (Join-Path $PSScriptRoot "run-delivery-gate.ps1") @gateParameters
    Write-Output "KIND_DELIVERY_DRILL=PASS"
} finally {
    Set-Location $previousLocation
    if (-not $KeepCluster -and $clusterCreated) {
        & $KindExecutable delete cluster --name $ClusterName | Out-Null
    }
    if ($infraStarted) {
        & docker compose -p $infraProject -f $infraCompose down --volumes --remove-orphans | Out-Null
    }
    Remove-Item Env:DELIVERY_DB_PASSWORD -ErrorAction SilentlyContinue
    $resolvedTemporaryRoot = [IO.Path]::GetFullPath($temporaryRoot)
    $resolvedSystemTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($resolvedTemporaryRoot.StartsWith($resolvedSystemTemp, [StringComparison]::OrdinalIgnoreCase) -and
            (Split-Path -Leaf $resolvedTemporaryRoot).StartsWith("fitpilot-delivery-")) {
        Remove-Item -LiteralPath $resolvedTemporaryRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
