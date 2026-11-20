$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$evidenceRoot = Join-Path ([IO.Path]::GetTempPath()) "fitpilot-v22-test-$([Guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $evidenceRoot | Out-Null
$script:previousApp = "fitpilot:previous"
$script:nextApp = "fitpilot:candidate"
$previousKubectl = Get-Item -Path Function:global:kubectl -ErrorAction SilentlyContinue

function Invoke-FakeKubectl {
    # Every unexpected command fails so the tests exercise the real rollout entry point.
    $global:LASTEXITCODE = 0
    if ($args[0] -ne "--context" -or $args[1] -ne "isolated-test") { throw "unexpected context" }
    $command = $args[2..($args.Count - 1)]
    if ($command[0] -eq "kustomize") { return "bundle: $(Split-Path -Leaf $command[1])" }
    if (($command -join " ") -eq "config current-context") { return "isolated-test" }
    if ($command[0] -eq "cluster-info") { return }
    if ($command[0] -eq "apply") {
        $bundle = (Get-Content -LiteralPath $command[2] -Raw).Trim().Substring(8)
        $fakeState.trace.Add("apply:$bundle")
        if ($bundle -eq "production") {
            $fakeState.image = $nextApp
            $fakeState.web = "web:candidate"
            $fakeState.replicas = 2
        }
        return
    }
    if ($command[0] -ne "-n" -or $command[1] -ne "fitpilot") { throw "unexpected namespace" }
    $action = $command[2..($command.Count - 1)]
    switch ("$($action[0]) $($action[1])") {
        "delete job" { return }
        "get job" {
            $failed = $fakeState.failure -eq $action[2]
            $type = if ($failed) { "Failed" } else { "Complete" }
            return @{status = @{conditions = @(@{type = $type; status = "True"})}} | ConvertTo-Json -Depth 5
        }
        "logs job/fitpilot-schema-status" { return "FITPILOT_V22_APPLIED=$($fakeState.v22)" }
        "logs job/fitpilot-migrate" { return "migration finished" }
        "get deployment" {
            if ($action[2] -eq "fitpilot-web") { return $fakeState.web }
            if ($action -notcontains "json") { return $fakeState.image }
            if (!$fakeState.image) { return }
            $metadata = @{generation = 11}
            if ($fakeState.compatible) {
                $metadata.annotations = @{"fitpilot.io/v22-compatible-image" = $fakeState.compatible}
            }
            return @{
                metadata = $metadata
                spec = @{
                    replicas = $fakeState.replicas
                    template = @{spec = @{containers = @(@{name = "fitpilot"; image = $fakeState.image})}}
                }
                status = @{observedGeneration = 11}
            } | ConvertTo-Json -Depth 9
        }
        "scale deployment/fitpilot" {
            if ($action[2] -ne "--replicas=0") { throw "unexpected scale target" }
            $fakeState.trace.Add("scale:0")
            $fakeState.replicas = 0
            return
        }
        "get pods" {
            if ($action[3] -ne "app.kubernetes.io/name=fitpilot") { throw "unexpected pod selector" }
            if ($fakeState.terminatingPods) {
                $fakeState.terminatingPods--
                $fakeState.trace.Add("pods:terminating")
                return "pod/fitpilot-terminating"
            }
            $fakeState.trace.Add("pods:empty")
            return
        }
        "set image" {
            $container, $image = $action[3] -split "=", 2
            if ($container -eq "fitpilot") { $fakeState.image = $image }
            else { $fakeState.web = $image }
            $fakeState.trace.Add("image:$image")
            return
        }
        "rollout status" {
            if ($fakeState.failure -eq "rollout" -and $fakeState.image -eq $nextApp) {
                $global:LASTEXITCODE = 1
                return "candidate rollout failed"
            }
            return
        }
        "wait --for=condition=available" { return }
        "annotate deployment/fitpilot" {
            $fakeState.trace.Add("seal")
            $fakeState.compatible = ($action[2] -split "=", 2)[1]
            return
        }
        "get all" { return }
        default { throw "unexpected kubectl command: $($action -join ' ')" }
    }
}

function Assert-Trace([string[]]$Expected) {
    if (($script:state.trace -join ", ") -cne ($Expected -join ", ")) {
        throw "expected [$($Expected -join ', ')], saw [$($script:state.trace -join ', ')]"
    }
}

function Invoke-Scenario([string]$Name, [hashtable]$Overrides, [string]$ExpectedError = "", [switch]$RollbackDrill) {
    $script:state = @{
        v22 = "false"; image = $script:previousApp; web = "web:previous"; failure = ""
        compatible = ""; replicas = 2; terminatingPods = 0
        trace = [Collections.Generic.List[string]]::new()
    }
    foreach ($entry in $Overrides.GetEnumerator()) { $script:state[$entry.Key] = $entry.Value }
    $fakeState = $script:state
    $nextApp = $script:nextApp
    Set-Item -Path Function:global:kubectl -Value (${function:Invoke-FakeKubectl}.GetNewClosure())
    $errorText = ""
    try {
        & (Join-Path $PSScriptRoot "run-kubernetes-rollout.ps1") -KubeContext isolated-test `
            -AppImage $script:nextApp -WebImage web:candidate -AllowMutableImages `
            -EvidenceDirectory (Join-Path $evidenceRoot $Name) -RollbackDrill:$RollbackDrill | Out-Null
    } catch { $errorText = $_.Exception.Message }
    if ((!$ExpectedError -and $errorText) -or ($ExpectedError -and $errorText -notlike "*$ExpectedError*")) {
        throw "$Name expected error '$ExpectedError', saw '$errorText'"
    }
}

try {
    $probe = "apply:production-schema-status"
    $migration = "apply:production-migration"
    $v22Migration = "apply:production-v22-migration"
    $deploy = "apply:production"

    Invoke-Scenario "probe-failed" @{failure = "fitpilot-schema-status"} "fitpilot-schema-status failed"
    Assert-Trace @($probe)
    Invoke-Scenario "pending-rollback-drill" @{} "rollback drill requires V22" -RollbackDrill
    Assert-Trace @($probe)
    Invoke-Scenario "fresh-install" @{image = ""; web = ""; replicas = 0}
    Assert-Trace @($probe, "pods:empty", $v22Migration, $deploy, "seal")
    Invoke-Scenario "v22-upgrade" @{terminatingPods = 1}
    Assert-Trace @($probe, "scale:0", "pods:terminating", "pods:empty", $v22Migration,
        "image:$script:nextApp", "pods:empty", $deploy, "seal")
    Invoke-Scenario "migration-failed" @{failure = "fitpilot-migrate"} "fitpilot-migrate failed"
    Assert-Trace @($probe, "scale:0", "pods:empty", $v22Migration)
    if ($script:state.replicas -ne 0) { throw "failed migration resumed the old backend" }
    Invoke-Scenario "first-rollout-failed" @{failure = "rollout"} "candidate rollout failed"
    Assert-Trace @($probe, "scale:0", "pods:empty", $v22Migration, "image:$script:nextApp", "pods:empty", $deploy)
    Invoke-Scenario "retry-unverified" @{v22 = "true"; compatible = "fitpilot:other"; failure = "rollout"} "candidate rollout failed"
    Assert-Trace @($probe, "scale:0", "pods:empty", $migration, "image:$script:nextApp", "pods:empty", $deploy)
    Invoke-Scenario "compatible-rollout-failed" @{v22 = "true"; compatible = $script:previousApp; failure = "rollout"} "candidate rollout failed"
    Assert-Trace @($probe, $migration, $deploy, "image:$script:previousApp", "image:web:previous")
    Invoke-Scenario "compatible-rollback-drill" @{v22 = "true"; compatible = $script:previousApp} -RollbackDrill
    Assert-Trace @($probe, $migration, $deploy, "image:$script:previousApp", "image:web:previous",
        "image:$script:nextApp", "image:web:candidate", "seal")

    Write-Output "V22_DELIVERY_REGRESSION=PASS"
} finally {
    if ($previousKubectl) {
        Set-Item -Path Function:global:kubectl -Value $previousKubectl.ScriptBlock
    } else {
        Remove-Item -Path Function:global:kubectl -ErrorAction SilentlyContinue
    }
    foreach ($testDirectory in Get-ChildItem -LiteralPath $evidenceRoot -Directory) {
        Get-ChildItem -LiteralPath $testDirectory.FullName -File | Remove-Item -Force
        Remove-Item -LiteralPath $testDirectory.FullName
    }
    Remove-Item -LiteralPath $evidenceRoot
}
