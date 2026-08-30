[CmdletBinding()]
param(
    [string]$ComposeFile = "docker-compose.yml"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$environmentPath = Join-Path $root ".env"
$seedPath = Join-Path $PSScriptRoot "seed-demo-data.sql"

if (-not (Test-Path -LiteralPath $environmentPath)) {
    throw ".env is required. Copy .env.example first."
}

$settings = @{}
Get-Content -LiteralPath $environmentPath | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)=(.*)$') { $settings[$Matches[1].Trim()] = $Matches[2] }
}

$database = if ($settings.ContainsKey("DB_NAME")) { $settings["DB_NAME"] } else { "fitpilot" }
$username = if ($settings.ContainsKey("DB_USERNAME")) { $settings["DB_USERNAME"] } else { "fitpilot" }

Push-Location $root
try {
    Get-Content -LiteralPath $seedPath -Raw |
        docker compose -f $ComposeFile exec -T postgres psql -v ON_ERROR_STOP=1 -U $username -d $database
    if ($LASTEXITCODE -ne 0) { throw "Demo seed failed." }
} finally {
    Pop-Location
}

Write-Host "Demo data is ready. Login: demo_athlete / FitPilotDemo2026!" -ForegroundColor Green
