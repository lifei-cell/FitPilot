param(
    [Parameter(Mandatory)][string]$KubeContext,
    [Parameter(Mandatory)][string]$AppImage,
    [Parameter(Mandatory)][string]$WebImage,
    [Parameter(Mandatory)][string]$RotatedJwtSecret,
    [Parameter(Mandatory)][string]$RotatedOperationsToken,
    [string]$Namespace = "fitpilot",
    [string]$EvidenceDirectory = "release-evidence",
    [hashtable]$ConfigOverrides = @{},
    [switch]$RollbackDrill,
    [switch]$BackupRestoreDrill,
    [switch]$SecretRotationDrill,
    [switch]$RevokePreviousJwt,
    [switch]$AllowMutableImages,
    [string]$BackupToolImage = "pgvector/pgvector:pg17",
    [switch]$DeleteNetworkPoliciesForHostBridge
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null

if ($BackupRestoreDrill) {
    try {
        & (Join-Path $PSScriptRoot "run-backup-restore-drill.ps1") `
            -KubeContext $KubeContext -Namespace $Namespace -EvidenceDirectory $EvidenceDirectory `
            -ToolImage $BackupToolImage
    } finally {
        & kubectl --context $KubeContext -n $Namespace delete secret fitpilot-delivery-drill `
            --ignore-not-found | Out-Null
    }
}

$rolloutArguments = @{
    KubeContext = $KubeContext
    Namespace = $Namespace
    AppImage = $AppImage
    WebImage = $WebImage
    EvidenceDirectory = $EvidenceDirectory
    ConfigOverrides = $ConfigOverrides
    RollbackDrill = $RollbackDrill
    AllowMutableImages = $AllowMutableImages
    DeleteNetworkPoliciesForHostBridge = $DeleteNetworkPoliciesForHostBridge
}
& (Join-Path $PSScriptRoot "run-kubernetes-rollout.ps1") @rolloutArguments

if ($SecretRotationDrill) {
    & (Join-Path $PSScriptRoot "run-secret-rotation-drill.ps1") `
        -KubeContext $KubeContext -Namespace $Namespace -EvidenceDirectory $EvidenceDirectory `
        -RotatedJwtSecret $RotatedJwtSecret -RotatedOperationsToken $RotatedOperationsToken `
        -RevokePreviousJwt:$RevokePreviousJwt
}

Write-Output "DELIVERY_GATE=PASS"
