# Main grid or doubling test.
#   ./scripts/run.ps1 configs/grid.properties -DryRun      counts + time estimate, measures nothing
#   ./scripts/run.ps1 configs/grid.properties
#   ./scripts/run.ps1 -Resume results/grid-20261108-213000  continue an interrupted run
#   ./scripts/run.ps1 configs/doubling.properties           after analysing the grid (needs results/best_cutoff.txt)
param(
    [string]$Config = "configs/grid.properties",
    [switch]$DryRun,
    [string]$Resume = "",
    [switch]$NoMaven
)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

& (Join-Path $PSScriptRoot "build.ps1") -RequireImplementation -NoMaven:$NoMaven
if (-not (Test-Path data/manifest.csv)) {
    & java -cp target/benchmarks.jar study.data.GenerateData
    if ($LASTEXITCODE -ne 0) { throw "GenerateData failed" }
}

if ($Resume) {
    & java -cp target/benchmarks.jar study.Launcher --resume $Resume
} elseif ($DryRun) {
    & java -cp target/benchmarks.jar study.Launcher $Config --dry-run
} else {
    & java -cp target/benchmarks.jar study.Launcher $Config
}
if ($LASTEXITCODE -ne 0) { throw "Launcher failed (exit $LASTEXITCODE). Fix the cause, then use -Resume <run directory>." }
