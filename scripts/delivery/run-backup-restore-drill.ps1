param(
    [Parameter(Mandatory)][string]$KubeContext,
    [string]$Namespace = "fitpilot",
    [string]$EvidenceDirectory = "release-evidence",
    [string]$ToolImage = "pgvector/pgvector:pg17"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Import-Module (Join-Path $PSScriptRoot "DeliveryGate.Common.psm1") -Force -DisableNameChecking

if ($Namespace -ne "fitpilot") { throw "the current Kustomize contract requires namespace 'fitpilot'" }
Assert-KubernetesContext -Context $KubeContext
New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null

Invoke-Kubectl -Context $KubeContext -Arguments @(
    "-n", $Namespace, "get", "secret", "fitpilot-delivery-drill"
) | Out-Null
$template = Get-Content -Raw -LiteralPath (Join-Path $root "deploy/k8s/drills/backup-restore-job.yml")
$template = $template -replace 'image: pgvector/pgvector:pg17', "image: $ToolImage"

Invoke-Kubectl -Context $KubeContext -Arguments @(
    "-n", $Namespace, "delete", "job", "fitpilot-backup-restore-drill", "--ignore-not-found"
) | Out-Null
Apply-Manifest -Context $KubeContext -Manifest $template

try {
    Wait-ForJobCompletion -Context $KubeContext -Namespace $Namespace `
        -Name "fitpilot-backup-restore-drill" -TimeoutSeconds 600
    $logs = Invoke-Kubectl -Context $KubeContext -Arguments @(
        "-n", $Namespace, "logs", "job/fitpilot-backup-restore-drill"
    ) -Capture
    Set-Content -LiteralPath (Join-Path $EvidenceDirectory "backup-restore.log") `
        -Value $logs -Encoding utf8NoBOM
    if ($logs -notmatch 'BACKUP_RESTORE_DRILL=PASS') {
        throw "backup/restore job completed without the PASS marker"
    }
    $job = Invoke-Kubectl -Context $KubeContext -Arguments @(
        "-n", $Namespace, "get", "job", "fitpilot-backup-restore-drill", "-o", "json"
    ) -Capture
    Set-Content -LiteralPath (Join-Path $EvidenceDirectory "backup-restore-job.json") `
        -Value $job -Encoding utf8NoBOM
    Write-Output "BACKUP_RESTORE_DRILL=PASS"
} catch {
    & kubectl --context $KubeContext -n $Namespace logs job/fitpilot-backup-restore-drill `
        *> (Join-Path $EvidenceDirectory "backup-restore-failure.log")
    throw
}
