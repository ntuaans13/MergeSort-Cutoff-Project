#!/usr/bin/env bash
# Pilot run to choose the warm-up: 4 datasets x 5 CUTOFF x FORKS JVMs.
#   ./scripts/pilot.sh [WARMUP] [MEASUREMENTS] [FORKS]      e.g. ./scripts/pilot.sh 20 10 3
set -euo pipefail
cd "$(dirname "$0")/.."
WARMUP="${1:-20}"; MEAS="${2:-10}"; FORKS="${3:-3}"

./scripts/build.sh --require ${NO_MAVEN:+--no-maven}
[ -f data/manifest.csv ] || java -cp target/benchmarks.jar study.data.GenerateData

echo "== Pilot: warmup=$WARMUP measurements=$MEAS forks=$FORKS"
java -cp target/benchmarks.jar study.Launcher configs/pilot.properties \
  --set "warmup=$WARMUP" --set "measurements=$MEAS" --set "forks=$FORKS"

RUN=$(ls -d results/pilot-* | sort | tail -1)
./scripts/analyze.sh "$RUN"
