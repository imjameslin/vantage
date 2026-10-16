#!/bin/zsh
# In-session A/B statistics test (docs/metal/CONTRACT.md 16.AB), headless, no game, no GPU:
#  1. tools/AbabStatsTest.java against build/bench-classes: distributions, cell summaries, calibration by simulation,
#     sequential false-positive rate, toggle spec parsing and reflection;
#  2. tools/abab.py --selftest: the Python re-implementation against synthetic cases, and (when result JSONs are given)
#     against the Java statistics of real abab runs, recomputed from their per-frame arrays.
# Run tools/build-bench.sh first. usage: tools/abab-stats-test.sh [results/raw/LABEL.json ...]
set -e
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
if [[ ! -d "$ROOT/build/bench-classes" ]]; then
  echo "missing build/bench-classes: run tools/build-bench.sh first" >&2
  exit 2
fi
"$JH/bin/java" -cp "$ROOT/build/bench-classes" "$ROOT/tools/AbabStatsTest.java"
python3 "$ROOT/tools/abab.py" --selftest "$@"
