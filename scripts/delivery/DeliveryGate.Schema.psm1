Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot "DeliveryGate.Common.psm1") -DisableNameChecking

function Remove-DeliveryJob([string]$Context, [string]$Namespace, [string]$Name) {
    Invoke-Kubectl -Context $Context -Arguments @(
        "-n", $Namespace, "delete", "job", $Name,
        "--ignore-not-found", "--cascade=foreground", "--wait=true", "--timeout=120s"
    ) | Out-Null
}

function Test-V22Applied([string]$Context, [string]$Namespace, [string]$Manifest, [string]$EvidenceDirectory) {
    $job = "fitpilot-schema-status"
    Remove-DeliveryJob $Context $Namespace $job
    Apply-Manifest -Context $Context -Manifest $Manifest
    Wait-ForJobCompletion -Context $Context -Namespace $Namespace -Name $job -TimeoutSeconds 120
    $output = Invoke-Kubectl -Context $Context -Arguments @("-n", $Namespace, "logs", "job/$job") -Capture
    Set-Content -LiteralPath (Join-Path $EvidenceDirectory "schema-status.log") -Value $output -Encoding utf8NoBOM
    $status = [regex]::Matches($output, '(?m)^FITPILOT_V22_APPLIED=(true|false)\r?$')
    if ($status.Count -ne 1) { throw "schema status job did not produce one V22 result" }
    return $status[0].Groups[1].Value -eq "true"
}

function Get-BackendDeployment([string]$Context, [string]$Namespace) {
    $json = Invoke-Kubectl -Context $Context -Arguments @(
        "-n", $Namespace, "get", "deployment", "fitpilot", "--ignore-not-found", "-o", "json"
    ) -Capture
    if ($json) { return ConvertFrom-Json -InputObject $json -AsHashtable }
}

function Stop-BackendForV22([string]$Context, [string]$Namespace, [int]$TimeoutSeconds = 180) {
    if (Get-BackendDeployment $Context $Namespace) {
        # The production HPA has a positive minimum and leaves a manually scaled zero target alone.
        Invoke-Kubectl -Context $Context -Arguments @(
            "-n", $Namespace, "scale", "deployment/fitpilot", "--replicas=0"
        ) | Out-Null
    }
    Wait-ForStoppedBackend $Context $Namespace $TimeoutSeconds
}

function Wait-ForStoppedBackend([string]$Context, [string]$Namespace, [int]$TimeoutSeconds = 180) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $deployment = Get-BackendDeployment $Context $Namespace
        if ($deployment -and $deployment.spec.replicas -ne 0) {
            throw "FitPilot backend was rescaled during the V22 shutdown"
        }
        $pods = Invoke-Kubectl -Context $Context -Arguments @(
            "-n", $Namespace, "get", "pods", "-l", "app.kubernetes.io/name=fitpilot", "-o", "name"
        ) -Capture
        $status = if ($deployment) { $deployment['status'] }
        $stopped = !$deployment -or ($status -and
            $status['observedGeneration'] -ge $deployment.metadata.generation -and !$status['replicas'])
        if ($stopped -and !$pods) { return }
        Start-Sleep -Seconds 2
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "FitPilot backend pods did not terminate before the V22 migration deadline"
}

Export-ModuleMember -Function Remove-DeliveryJob, Test-V22Applied, Get-BackendDeployment,
    Stop-BackendForV22, Wait-ForStoppedBackend
