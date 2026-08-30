param(
    [switch]$SkipInstall,
    [switch]$SkipBrowserInstall
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$web = Join-Path $root "web"

function Assert-LastExitCode([string]$Step) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE"
    }
}

Push-Location $root
try {
    & mvn --batch-mode --no-transfer-progress clean verify
    Assert-LastExitCode "Maven quality gate"

    Push-Location $web
    try {
        if (-not $SkipInstall) {
            & npm ci
            Assert-LastExitCode "npm ci"
        }
        if (-not $SkipBrowserInstall) {
            $installArguments = @("playwright", "install", "chromium")
            if ($env:CI -eq "true") {
                $installArguments += "--with-deps"
            }
            & npx @installArguments
            Assert-LastExitCode "Playwright Chromium install"
        }
        & npm run quality
        Assert-LastExitCode "Web quality gate"
    } finally {
        Pop-Location
    }
} finally {
    Pop-Location
}

Write-Output "QUALITY_GATE=PASS"
