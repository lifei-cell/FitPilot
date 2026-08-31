param(
    [Parameter(Mandatory)][string]$KubeContext,
    [Parameter(Mandatory)][string]$AppImage,
    [Parameter(Mandatory)][string]$WebImage,
    [string]$Namespace = "fitpilot",
    [string]$EvidenceDirectory = "release-evidence",
    [hashtable]$ConfigOverrides = @{},
    [switch]$RollbackDrill,
    [switch]$AllowMutableImages,
    [switch]$DeleteNetworkPoliciesForHostBridge
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Import-Module (Join-Path $PSScriptRoot "DeliveryGate.Common.psm1") -Force -DisableNameChecking

if ($Namespace -ne "fitpilot") { throw "the current Kustomize contract requires namespace 'fitpilot'" }
Assert-ImageReference -Reference $AppImage -AllowMutable:$AllowMutableImages
Assert-ImageReference -Reference $WebImage -AllowMutable:$AllowMutableImages
Assert-KubernetesContext -Context $KubeContext
New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null

$previousApp = Get-DeploymentImage -Context $KubeContext -Namespace $Namespace `
    -Deployment "fitpilot" -Container "fitpilot" -AllowMissing
$previousWeb = Get-DeploymentImage -Context $KubeContext -Namespace $Namespace `
    -Deployment "fitpilot-web" -Container "web" -AllowMissing
if ($RollbackDrill -and ([string]::IsNullOrWhiteSpace($previousApp) -or
        [string]::IsNullOrWhiteSpace($previousWeb))) {
    throw "rollback drill requires an existing application and web deployment"
}

try {
    $migration = Render-Kustomization -Context $KubeContext `
        -Path (Join-Path $root "deploy/k8s/overlays/production-migration") `
        -AppImage $AppImage -ConfigOverrides $ConfigOverrides
    Invoke-Kubectl -Context $KubeContext -Arguments @(
        "-n", $Namespace, "delete", "job", "fitpilot-migrate", "--ignore-not-found"
    ) | Out-Null
    Apply-Manifest -Context $KubeContext -Manifest $migration
    Wait-ForJobCompletion -Context $KubeContext -Namespace $Namespace `
        -Name "fitpilot-migrate" -TimeoutSeconds 300
    $migrationLogs = Invoke-Kubectl -Context $KubeContext -Arguments @(
        "-n", $Namespace, "logs", "job/fitpilot-migrate"
    ) -Capture
    Set-Content -LiteralPath (Join-Path $EvidenceDirectory "migration.log") `
        -Value $migrationLogs -Encoding utf8NoBOM

    $production = Render-Kustomization -Context $KubeContext `
        -Path (Join-Path $root "deploy/k8s/overlays/production") `
        -AppImage $AppImage -WebImage $WebImage -ConfigOverrides $ConfigOverrides
    Apply-Manifest -Context $KubeContext -Manifest $production
    if ($DeleteNetworkPoliciesForHostBridge) {
        Invoke-Kubectl -Context $KubeContext -Arguments @(
            "-n", $Namespace, "delete", "networkpolicy", "fitpilot", "fitpilot-web", "--ignore-not-found"
        ) | Out-Null
    }
    Wait-ForDeployment -Context $KubeContext -Namespace $Namespace -Name "fitpilot"
    Wait-ForDeployment -Context $KubeContext -Namespace $Namespace -Name "fitpilot-web" -Timeout "3m"

    $deployedApp = Get-DeploymentImage -Context $KubeContext -Namespace $Namespace `
        -Deployment "fitpilot" -Container "fitpilot"
    $deployedWeb = Get-DeploymentImage -Context $KubeContext -Namespace $Namespace `
        -Deployment "fitpilot-web" -Container "web"
    if ($deployedApp -ne $AppImage -or $deployedWeb -ne $WebImage) {
        throw "deployed image references do not match the requested candidate"
    }

    if ($RollbackDrill) {
        Invoke-Kubectl -Context $KubeContext -Arguments @(
            "-n", $Namespace, "rollout", "undo", "deployment/fitpilot"
        ) | Out-Null
        Invoke-Kubectl -Context $KubeContext -Arguments @(
            "-n", $Namespace, "rollout", "undo", "deployment/fitpilot-web"
        ) | Out-Null
        Wait-ForDeployment -Context $KubeContext -Namespace $Namespace -Name "fitpilot"
        Wait-ForDeployment -Context $KubeContext -Namespace $Namespace -Name "fitpilot-web" -Timeout "3m"
        if ((Get-DeploymentImage -Context $KubeContext -Namespace $Namespace `
                -Deployment "fitpilot" -Container "fitpilot") -ne $previousApp) {
            throw "application rollback did not restore the previous image"
        }
        if ((Get-DeploymentImage -Context $KubeContext -Namespace $Namespace `
                -Deployment "fitpilot-web" -Container "web") -ne $previousWeb) {
            throw "web rollback did not restore the previous image"
        }
        Invoke-Kubectl -Context $KubeContext -Arguments @(
            "-n", $Namespace, "set", "image", "deployment/fitpilot", "fitpilot=$AppImage"
        ) | Out-Null
        Invoke-Kubectl -Context $KubeContext -Arguments @(
            "-n", $Namespace, "set", "image", "deployment/fitpilot-web", "web=$WebImage"
        ) | Out-Null
        Wait-ForDeployment -Context $KubeContext -Namespace $Namespace -Name "fitpilot"
        Wait-ForDeployment -Context $KubeContext -Namespace $Namespace -Name "fitpilot-web" -Timeout "3m"
    }

    $summary = [ordered]@{
        status = "PASS"
        context = $KubeContext
        namespace = $Namespace
        previousAppImage = $previousApp
        previousWebImage = $previousWeb
        candidateAppImage = $AppImage
        candidateWebImage = $WebImage
        migrationCompleted = $true
        rollbackDrill = [bool]$RollbackDrill
        candidateRecovered = $true
    } | ConvertTo-Json
    Set-Content -LiteralPath (Join-Path $EvidenceDirectory "kubernetes-rollout.json") `
        -Value $summary -Encoding utf8NoBOM
    $all = Invoke-Kubectl -Context $KubeContext -Arguments @(
        "-n", $Namespace, "get", "all", "-o", "wide"
    ) -Capture
    Set-Content -LiteralPath (Join-Path $EvidenceDirectory "kubernetes-resources.txt") `
        -Value $all -Encoding utf8NoBOM
    Write-Output "KUBERNETES_DELIVERY_GATE=PASS"
} catch {
    if (-not [string]::IsNullOrWhiteSpace($previousApp)) {
        & kubectl --context $KubeContext -n $Namespace set image deployment/fitpilot "fitpilot=$previousApp" | Out-Null
        & kubectl --context $KubeContext -n $Namespace rollout status deployment/fitpilot --timeout=5m | Out-Null
    }
    if (-not [string]::IsNullOrWhiteSpace($previousWeb)) {
        & kubectl --context $KubeContext -n $Namespace set image deployment/fitpilot-web "web=$previousWeb" | Out-Null
        & kubectl --context $KubeContext -n $Namespace rollout status deployment/fitpilot-web --timeout=3m | Out-Null
    }
    throw
}
