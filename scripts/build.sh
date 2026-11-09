#!/usr/bin/env bash
# Build + correctness gate (Linux/macOS).
#   ./scripts/build.sh               scaffold mode (OK while MergeSort is TODO)
#   ./scripts/build.sh --require     required before any measurement
#   ./scripts/build.sh --no-maven    no Maven/JMH: pure-JDK jar, fork backend only
set -euo pipefail
cd "$(dirname "$0")/.."

REQUIRE=""
NOMAVEN=0
for a in "$@"; do
  case "$a" in
    --require) REQUIRE="--require" ;;
    --no-maven) NOMAVEN=1 ;;
    *) echo "unknown option $a"; exit 2 ;;
  esac
done

if [ "$NOMAVEN" = 0 ] && command -v mvn >/dev/null 2>&1; then
  echo "== Maven build (dependencies cached in .m2/ inside the project)"
  mvn -q -B -Dmaven.repo.local=.m2/repository clean package
  if ! java -jar target/benchmarks.jar -l | grep -q 'study\.jmh\.SortBenchmark\.sort'; then
    echo "JMH did not register study.jmh.SortBenchmark.sort (missing /META-INF/BenchmarkList?)"; exit 1
  fi
  echo "Built target/benchmarks.jar (JMH backend available)"
else
  [ "$NOMAVEN" = 0 ] && echo "Maven not found on PATH."
  echo "== Pure-JDK build (fork backend only)"
  rm -rf target && mkdir -p target/classes
  find src/main/java -name '*.java' -not -path '*/jmh/*' > target/sources.txt
  javac -encoding UTF-8 --release 17 -d target/classes @target/sources.txt
  jar --create --file target/benchmarks.jar -C target/classes .
  echo "Built target/benchmarks.jar (no JMH)"
fi

echo "== Correctness check"
java -cp target/benchmarks.jar study.check.CheckSort $REQUIRE
