param(
    [Parameter(Mandatory)][string]$KubeContext,
    [Parameter(Mandatory)][string]$SourceDatabaseUrl,
    [Parameter(Mandatory)][string]$RestoreDatabaseUrl,
    [string]$Namespace = "fitpilot"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot "DeliveryGate.Common.psm1") -Force -DisableNameChecking

if ($SourceDatabaseUrl -eq $RestoreDatabaseUrl) {
    throw "source and restore database URLs must be different"
}
foreach ($url in $SourceDatabaseUrl, $RestoreDatabaseUrl) {
    if ($url -notmatch '^postgres(?:ql)?://') {
        throw "backup/restore drill requires PostgreSQL connection URLs"
    }
}
Assert-KubernetesContext -Context $KubeContext

$data = @{
    SOURCE_DATABASE_URL = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($SourceDatabaseUrl))
    RESTORE_DATABASE_URL = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($RestoreDatabaseUrl))
}
$manifest = @{
    apiVersion = "v1"
    kind = "Secret"
    metadata = @{name = "fitpilot-delivery-drill"; namespace = $Namespace}
    type = "Opaque"
    data = $data
} | ConvertTo-Json -Depth 6
Apply-Manifest -Context $KubeContext -Manifest $manifest
Write-Output "DATABASE_DRILL_SECRET=READY"
