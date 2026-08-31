param(
    [Parameter(Mandatory)][string]$KubeContext,
    [Parameter(Mandatory)][string]$RotatedJwtSecret,
    [Parameter(Mandatory)][string]$RotatedOperationsToken,
    [string]$Namespace = "fitpilot",
    [string]$EvidenceDirectory = "release-evidence",
    [switch]$RevokePreviousJwt
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Import-Module (Join-Path $PSScriptRoot "DeliveryGate.Common.psm1") -Force -DisableNameChecking

if ($Namespace -ne "fitpilot") { throw "the current Kustomize contract requires namespace 'fitpilot'" }
if ([Text.Encoding]::UTF8.GetByteCount($RotatedJwtSecret) -lt 32) {
    throw "rotated JWT secret must contain at least 32 bytes"
}
if ([string]::IsNullOrWhiteSpace($RotatedOperationsToken)) {
    throw "rotated operations token must not be empty"
}
Assert-KubernetesContext -Context $KubeContext
New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null

$originalJson = Invoke-Kubectl -Context $KubeContext -Arguments @(
    "-n", $Namespace, "get", "secret", "fitpilot-secrets", "-o", "json"
) -Capture
$original = $originalJson | ConvertFrom-Json
$originalData = @{}
foreach ($property in $original.data.PSObject.Properties) {
    $originalData[$property.Name] = [string]$property.Value
}
foreach ($requiredKey in "JWT_SECRET", "OPERATIONS_TOKEN") {
    if (-not $originalData.ContainsKey($requiredKey)) {
        throw "fitpilot-secrets is missing required key $requiredKey"
    }
}

function Secret-Manifest([hashtable]$Data) {
    return @{
        apiVersion = "v1"
        kind = "Secret"
        metadata = @{name = "fitpilot-secrets"; namespace = $Namespace}
        type = if ($null -ne $original.type) { [string]$original.type } else { "Opaque" }
        data = $Data
    } | ConvertTo-Json -Depth 8
}

function Restart-Application([string]$Phase) {
    Invoke-Kubectl -Context $KubeContext -Arguments @(
        "-n", $Namespace, "annotate", "deployment/fitpilot",
        "fitpilot.io/secret-rotation=$Phase", "--overwrite"
    ) | Out-Null
    Invoke-Kubectl -Context $KubeContext -Arguments @(
        "-n", $Namespace, "rollout", "restart", "deployment/fitpilot"
    ) | Out-Null
    Wait-ForDeployment -Context $KubeContext -Namespace $Namespace -Name "fitpilot"
}

$rotationId = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
try {
    $stageOne = $originalData.Clone()
    $stageOne["JWT_PREVIOUS_SECRET"] = $originalData["JWT_SECRET"]
    $stageOne["PREVIOUS_OPERATIONS_TOKEN"] = $originalData["OPERATIONS_TOKEN"]
    $stageOne["JWT_SECRET"] = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($RotatedJwtSecret))
    $stageOne["OPERATIONS_TOKEN"] = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($RotatedOperationsToken))
    Apply-Manifest -Context $KubeContext -Manifest (Secret-Manifest $stageOne)
    Restart-Application -Phase "$rotationId-stage"

    $appImage = Get-DeploymentImage -Context $KubeContext -Namespace $Namespace `
        -Deployment "fitpilot" -Container "fitpilot"
    $check = Get-Content -Raw -LiteralPath (Join-Path $root "deploy/k8s/drills/secret-rotation-check-job.yml")
    $check = $check -replace 'image: ghcr\.io/example/fitpilot:[^\s]+', "image: $appImage"
    Invoke-Kubectl -Context $KubeContext -Arguments @(
        "-n", $Namespace, "delete", "job", "fitpilot-secret-rotation-check", "--ignore-not-found"
    ) | Out-Null
    Apply-Manifest -Context $KubeContext -Manifest $check
    Wait-ForJobCompletion -Context $KubeContext -Namespace $Namespace `
        -Name "fitpilot-secret-rotation-check" -TimeoutSeconds 300
    $authLogs = Invoke-Kubectl -Context $KubeContext -Arguments @(
        "-n", $Namespace, "logs", "job/fitpilot-secret-rotation-check"
    ) -Capture
    Set-Content -LiteralPath (Join-Path $EvidenceDirectory "secret-rotation-auth.log") `
        -Value $authLogs -Encoding utf8NoBOM
    if ($authLogs -notmatch 'SECRET_ROTATION_AUTH_CHECK=PASS') {
        throw "rotated operations token verification did not pass"
    }

    $stageTwo = $stageOne.Clone()
    $stageTwo.Remove("PREVIOUS_OPERATIONS_TOKEN")
    if ($RevokePreviousJwt) { $stageTwo.Remove("JWT_PREVIOUS_SECRET") }
    Apply-Manifest -Context $KubeContext -Manifest (Secret-Manifest $stageTwo)
    Restart-Application -Phase "$rotationId-final"

    $summary = [ordered]@{
        status = "PASS"
        rotationId = $rotationId
        previousJwtRevoked = [bool]$RevokePreviousJwt
        newOperationsTokenAccepted = $true
        previousOperationsTokenRejected = $true
    } | ConvertTo-Json
    Set-Content -LiteralPath (Join-Path $EvidenceDirectory "secret-rotation.json") `
        -Value $summary -Encoding utf8NoBOM
    Write-Output "SECRET_ROTATION_DRILL=PASS"
} catch {
    & kubectl --context $KubeContext -n $Namespace logs job/fitpilot-secret-rotation-check `
        *> (Join-Path $EvidenceDirectory "secret-rotation-failure.log")
    Apply-Manifest -Context $KubeContext -Manifest (Secret-Manifest $originalData)
    Restart-Application -Phase "$rotationId-rollback"
    throw
}
