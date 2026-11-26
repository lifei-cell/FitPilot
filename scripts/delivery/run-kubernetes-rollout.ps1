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
Import-Module (Join-Path $PSScriptRoot "DeliveryGate.Schema.psm1") -Force -DisableNameChecking

function Set-DeliveryImage([string]$Name, [string]$Container, [string]$Image) {
    Invoke-Kubectl -Context $KubeContext -Arguments @(
        "-n", $Namespace, "set", "image", "deployment/$Name", "$Container=$Image"
    ) | Out-Null
}

if ($Namespace -ne "fitpilot") { throw "the current Kustomize contract requires namespace 'fitpilot'" }
Assert-ImageReference -Reference $AppImage -AllowMutable:$AllowMutableImages
Assert-ImageReference -Reference $WebImage -AllowMutable:$AllowMutableImages
Assert-KubernetesContext -Context $KubeContext
New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null

$statusManifest = Render-Kustomization -Context $KubeContext `
    -Path (Join-Path $root "deploy/k8s/overlays/production-schema-status") `
    -AppImage $AppImage -ConfigOverrides $ConfigOverrides
$production = Render-Kustomization -Context $KubeContext `
    -Path (Join-Path $root "deploy/k8s/overlays/production") `
    -AppImage $AppImage -WebImage $WebImage -ConfigOverrides $ConfigOverrides

# A cancelled run may have left a migration pod behind. Settle it before reading Flyway history.
Remove-DeliveryJob $KubeContext $Namespace "fitpilot-migrate"
$v22Applied = Test-V22Applied $KubeContext $Namespace $statusManifest $EvidenceDirectory
$backend = Get-BackendDeployment $KubeContext $Namespace
$compatibilityAnnotation = "fitpilot.io/v22-compatible-image"
$previousApp = ""
$compatibleImage = ""
if ($backend) {
    $previousApp = ($backend.spec.template.spec.containers | Where-Object { $_.name -eq "fitpilot" }).image
    if ($backend.metadata['annotations']) {
        $compatibleImage = $backend.metadata.annotations[$compatibilityAnnotation]
    }
}
$previousWeb = Get-DeploymentImage -Context $KubeContext -Namespace $Namespace `
    -Deployment "fitpilot-web" -Container "web" -AllowMissing
$forwardOnly = !$v22Applied -or !$previousApp -or $compatibleImage -cne $previousApp
if ($RollbackDrill -and ($forwardOnly -or !$previousWeb)) {
    throw "rollback drill requires V22 and a gate-verified compatible backend and web deployment"
}

$migrationOverlay = if ($v22Applied) { "production-migration" } else { "production-v22-migration" }
$migration = Render-Kustomization -Context $KubeContext `
    -Path (Join-Path $root "deploy/k8s/overlays/$migrationOverlay") `
    -AppImage $AppImage -ConfigOverrides $ConfigOverrides
$rolloutStarted = $false
try {
    if ($forwardOnly) { Stop-BackendForV22 $KubeContext $Namespace }
    Apply-Manifest -Context $KubeContext -Manifest $migration
    Wait-ForJobCompletion -Context $KubeContext -Namespace $Namespace `
        -Name "fitpilot-migrate" -TimeoutSeconds 300
    $migrationLogs = Invoke-Kubectl -Context $KubeContext -Arguments @(
        "-n", $Namespace, "logs", "job/fitpilot-migrate"
    ) -Capture
    Set-Content -LiteralPath (Join-Path $EvidenceDirectory "migration.log") `
        -Value $migrationLogs -Encoding utf8NoBOM

    if ($forwardOnly -and $backend) {
        # Replace the old template while replicas are still zero, before apply can restore them.
        Set-DeliveryImage "fitpilot" "fitpilot" $AppImage
        Wait-ForStoppedBackend $KubeContext $Namespace
    }
    $rolloutStarted = $true
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
        Set-DeliveryImage "fitpilot" "fitpilot" $previousApp
        Set-DeliveryImage "fitpilot-web" "web" $previousWeb
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
        Set-DeliveryImage "fitpilot" "fitpilot" $AppImage
        Set-DeliveryImage "fitpilot-web" "web" $WebImage
        Wait-ForDeployment -Context $KubeContext -Namespace $Namespace -Name "fitpilot"
        Wait-ForDeployment -Context $KubeContext -Namespace $Namespace -Name "fitpilot-web" -Timeout "3m"
    }

    Invoke-Kubectl -Context $KubeContext -Arguments @(
        "-n", $Namespace, "annotate", "deployment/fitpilot", "$compatibilityAnnotation=$AppImage", "--overwrite"
    ) | Out-Null
    $summary = [ordered]@{
        status = "PASS"
        context = $KubeContext
        namespace = $Namespace
        previousAppImage = $previousApp
        previousWebImage = $previousWeb
        candidateAppImage = $AppImage
        candidateWebImage = $WebImage
        migrationCompleted = $true
        v22AppliedBefore = $v22Applied
        forwardOnly = $forwardOnly
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
    if ($forwardOnly) {
        Write-Warning "V22 forward-only delivery failed. Leave the old backend stopped and retry with a compatible candidate."
    } elseif ($rolloutStarted) {
        try {
            Set-DeliveryImage "fitpilot" "fitpilot" $previousApp
            if ($previousWeb) { Set-DeliveryImage "fitpilot-web" "web" $previousWeb }
            Wait-ForDeployment -Context $KubeContext -Namespace $Namespace -Name "fitpilot"
            if ($previousWeb) {
                Wait-ForDeployment -Context $KubeContext -Namespace $Namespace -Name "fitpilot-web" -Timeout "3m"
            }
        } catch {
            Write-Warning "Compatible image recovery failed: $_"
        }
    }
    throw
}
