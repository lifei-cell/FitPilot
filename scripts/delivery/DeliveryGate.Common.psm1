Set-StrictMode -Version Latest

function Invoke-Kubectl {
    param(
        [Parameter(Mandatory)][string]$Context,
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$Capture
    )
    $output = & kubectl --context $Context @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "kubectl failed: kubectl --context $Context $($Arguments -join ' ')`n$($output -join "`n")"
    }
    if ($Capture) { return ($output -join "`n").Trim() }
    $output | Write-Output
}

function Assert-KubernetesContext {
    param([Parameter(Mandatory)][string]$Context)
    $current = Invoke-Kubectl -Context $Context -Arguments @("config", "current-context") -Capture
    if ($current -ne $Context) {
        throw "refusing delivery drill: expected context '$Context', current context is '$current'"
    }
    Invoke-Kubectl -Context $Context -Arguments @("cluster-info") | Out-Null
}

function Assert-ImageReference {
    param(
        [Parameter(Mandatory)][string]$Reference,
        [switch]$AllowMutable
    )
    if (-not $AllowMutable -and $Reference -notmatch '^[^\s@]+@sha256:[a-f0-9]{64}$') {
        throw "production delivery requires an immutable image digest: $Reference"
    }
    if ($Reference -match '\s') { throw "invalid image reference: $Reference" }
}

function Render-Kustomization {
    param(
        [Parameter(Mandatory)][string]$Context,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$AppImage,
        [string]$WebImage,
        [hashtable]$ConfigOverrides = @{}
    )
    $manifest = Invoke-Kubectl -Context $Context -Arguments @("kustomize", $Path) -Capture
    $manifest = $manifest -replace 'image: ghcr\.io/example/fitpilot:[^\s]+', "image: $AppImage"
    if (-not [string]::IsNullOrWhiteSpace($WebImage)) {
        $manifest = $manifest -replace 'image: ghcr\.io/example/fitpilot-web:[^\s]+', "image: $WebImage"
    }
    foreach ($entry in $ConfigOverrides.GetEnumerator()) {
        $key = [Regex]::Escape([string]$entry.Key)
        $yamlValue = ConvertTo-Json ([string]$entry.Value) -Compress
        $manifest = [Regex]::Replace($manifest, "(?m)^  ${key}: .*$", "  $($entry.Key): $yamlValue")
    }
    return $manifest
}

function Write-RestrictedTemporaryFile {
    param(
        [Parameter(Mandatory)][string]$Content,
        [string]$Extension = ".json"
    )
    $path = Join-Path ([System.IO.Path]::GetTempPath()) ("fitpilot-$([Guid]::NewGuid().ToString('N'))$Extension")
    Set-Content -LiteralPath $path -Value $Content -Encoding utf8NoBOM
    if (-not $IsWindows) { & chmod 600 $path }
    return $path
}

function Wait-ForDeployment {
    param(
        [Parameter(Mandatory)][string]$Context,
        [Parameter(Mandatory)][string]$Namespace,
        [Parameter(Mandatory)][string]$Name,
        [string]$Timeout = "5m"
    )
    Invoke-Kubectl -Context $Context -Arguments @(
        "-n", $Namespace, "rollout", "status", "deployment/$Name", "--timeout=$Timeout"
    ) | Out-Null
    Invoke-Kubectl -Context $Context -Arguments @(
        "-n", $Namespace, "wait", "--for=condition=available", "deployment/$Name", "--timeout=$Timeout"
    ) | Out-Null
}

function Wait-ForJobCompletion {
    param(
        [Parameter(Mandatory)][string]$Context,
        [Parameter(Mandatory)][string]$Namespace,
        [Parameter(Mandatory)][string]$Name,
        [int]$TimeoutSeconds = 600
    )
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ($true) {
        $job = Invoke-Kubectl -Context $Context -Arguments @(
            "-n", $Namespace, "get", "job", $Name, "-o", "json"
        ) -Capture | ConvertFrom-Json
        $succeeded = if ($job.status.PSObject.Properties.Name -contains "succeeded") {
            [int]$job.status.succeeded
        } else { 0 }
        $failed = if ($job.status.PSObject.Properties.Name -contains "failed") {
            [int]$job.status.failed
        } else { 0 }
        if ($succeeded -ge 1) { return }
        if ($failed -ge 1) { throw "job $Namespace/$Name failed" }
        if ([DateTimeOffset]::UtcNow -ge $deadline) {
            throw "job $Namespace/$Name did not complete within $TimeoutSeconds seconds"
        }
        Start-Sleep -Seconds 2
    }
}

function Get-DeploymentImage {
    param(
        [Parameter(Mandatory)][string]$Context,
        [Parameter(Mandatory)][string]$Namespace,
        [Parameter(Mandatory)][string]$Deployment,
        [Parameter(Mandatory)][string]$Container,
        [switch]$AllowMissing
    )
    $arguments = @(
        "-n", $Namespace, "get", "deployment", $Deployment,
        "-o", "jsonpath={.spec.template.spec.containers[?(@.name=='$Container')].image}"
    )
    $output = & kubectl --context $Context @arguments 2>$null
    if ($LASTEXITCODE -ne 0) {
        if ($AllowMissing) { return "" }
        throw "deployment not found: $Namespace/$Deployment"
    }
    return ($output -join "").Trim()
}

function Apply-Manifest {
    param(
        [Parameter(Mandatory)][string]$Context,
        [Parameter(Mandatory)][string]$Manifest
    )
    $path = Write-RestrictedTemporaryFile -Content $Manifest -Extension ".yml"
    try {
        Invoke-Kubectl -Context $Context -Arguments @("apply", "-f", $path) | Out-Null
    } finally {
        Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
    }
}

Export-ModuleMember -Function Invoke-Kubectl, Assert-KubernetesContext, Assert-ImageReference,
    Render-Kustomization, Write-RestrictedTemporaryFile, Wait-ForDeployment,
    Wait-ForJobCompletion, Get-DeploymentImage, Apply-Manifest
