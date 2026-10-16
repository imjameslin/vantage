#!/bin/zsh
# Stage 3 r5: headless estimate of a ray-marched far field at Voxy's detail (tools/lodray_bench.m, results/STAGE3-R5.md).
# Offscreen only; no window. Usage: tools/lodray-bench.sh [--frames N] [--radius R] [--tau T]
set -e
ROOT="${0:A:h:h}"
OUT="$ROOT/build/native-tests"
mkdir -p "$OUT"
xcrun clang -arch arm64 -mmacosx-version-min=26.0 -O2 -fobjc-arc -Wall -Wno-unused-parameter -Wno-unused-function -Wno-unused-variable \
  -o "$OUT/lodray_bench" "$ROOT/tools/lodray_bench.m" -framework Foundation -framework Metal -framework ImageIO -framework CoreGraphics -framework UniformTypeIdentifiers
cd "$ROOT"
exec "$OUT/lodray_bench" --out "$OUT" "$@"
