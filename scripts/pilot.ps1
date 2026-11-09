# Pilot run to choose the warm-up: 4 datasets x 5 CUTOFF x Forks JVMs.
#   ./scripts/pilot.ps1 -Warmup 20 -Measurements 10 -Forks 3
#   ./scripts/pilot.ps1 -Warmup 50
param(
    [int]$Warmup = 20,
    [int]$Measurements = 10,
    [int]$Forks = 3,
    [switch]$NoMaven
)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

& (Join-Path $PSScriptRoot "build.ps1") -RequireImplementation -NoMaven:$NoMaven

if (-not (Test-Path data/manifest.csv)) {
    Write-Host "== Generating datasets"
    & java -cp target/benchmarks.jar study.data.GenerateData
    if ($LASTEXITCODE -ne 0) { throw "GenerateData failed" }
}

Write-Host "== Pilot: warmup=$Warmup measurements=$Measurements forks=$Forks"
& java -cp target/benchmarks.jar study.Launcher configs/pilot.properties `
    --set "warmup=$Warmup" --set "measurements=$Measurements" --set "forks=$Forks"
if ($LASTEXITCODE -ne 0) { throw "Pilot failed - open the newest results/pilot-*/logs/*.log and look for the root cause." }

$run = Get-ChildItem results -Directory -Filter "pilot-*" | Sort-Object Name | Select-Object -Last 1
& (Join-Path $PSScriptRoot "analyze.ps1") $run.FullName
