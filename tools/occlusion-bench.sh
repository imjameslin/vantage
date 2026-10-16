#!/bin/zsh
# Headless GPU measurement of terrain occlusion culling at 3600x2260 (docs/metal/OCCLUSION.md): today's terrain vs.
# the ideal (oracle) culled terrain vs. the cost of the culling mechanisms. Offscreen only; no window.
#
# Usage: tools/occlusion-bench.sh [--frames N] [--rounds N] [--caves] [--pose NAME]
set -e
ROOT="${0:A:h:h}"
OUT="$ROOT/build/native-tests"
mkdir -p "$OUT"
xcrun clang -arch arm64 -mmacosx-version-min=26.0 -O2 -fobjc-arc -Wall -Wno-unused-parameter -Wno-unused-function \
  -o "$OUT/occlusion_bench" "$ROOT/tools/occlusion_bench.m" -framework Foundation -framework Metal
exec "$OUT/occlusion_bench" "$@"
