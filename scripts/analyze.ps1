# Runs scripts/analyze.py with the project's virtual environment (or any Python 3 found).
#   ./scripts/analyze.ps1 results/grid-20261108-213000 [--doubling results/doubling-...]
$ErrorActionPreference = "Stop"
$root = Join-Path $PSScriptRoot ".."
$candidates = @((Join-Path $root ".venv/Scripts/python.exe"), (Join-Path $root ".venv/bin/python"))
$py = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
$pyArgs = @()
if (-not $py) {
    if (Get-Command python -ErrorAction SilentlyContinue) { $py = "python" }
    elseif (Get-Command py -ErrorAction SilentlyContinue) { $py = "py"; $pyArgs = @("-3") }
    else { Write-Host "Python not found - skip analysis. See README section 6."; exit 0 }
}
& $py @pyArgs (Join-Path $PSScriptRoot "analyze.py") @args
if ($LASTEXITCODE -ne 0) { throw "analyze.py failed (did you run: python -m pip install -r requirements.txt ?)" }
