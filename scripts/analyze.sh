#!/usr/bin/env bash
# Runs scripts/analyze.py with the project's virtual environment (or python3).
#   ./scripts/analyze.sh results/grid-20261108-213000 [--doubling results/doubling-...]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -x "$ROOT/.venv/bin/python" ]; then PY="$ROOT/.venv/bin/python"
elif command -v python3 >/dev/null 2>&1; then PY=python3
else echo "Python not found - skip analysis. See README section 6."; exit 0; fi
"$PY" "$ROOT/scripts/analyze.py" "$@"
