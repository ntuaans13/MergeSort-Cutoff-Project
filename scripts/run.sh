#!/usr/bin/env bash
# Main grid or doubling test.
#   ./scripts/run.sh configs/grid.properties --dry-run
#   ./scripts/run.sh configs/grid.properties
#   ./scripts/run.sh --resume results/grid-20261108-213000
#   ./scripts/run.sh configs/doubling.properties      (needs results/best_cutoff.txt from the grid analysis)
set -euo pipefail
cd "$(dirname "$0")/.."

./scripts/build.sh --require ${NO_MAVEN:+--no-maven}
[ -f data/manifest.csv ] || java -cp target/benchmarks.jar study.data.GenerateData
java -cp target/benchmarks.jar study.Launcher "$@"
