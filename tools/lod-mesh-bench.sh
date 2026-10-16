#!/bin/zsh
# Stage 3 r4: headless comparison of LOD quad drawing paths (indirect vertex pulling vs object + mesh shaders); see
# tools/lod_mesh_bench.m. Offscreen only; no window. Usage: tools/lod-mesh-bench.sh [--frames N] [--tau T] [--yaw DEG]
set -e
ROOT="${0:A:h:h}"
OUT="$ROOT/build/native-tests"
mkdir -p "$OUT"
xcrun clang -arch arm64 -mmacosx-version-min=26.0 -O2 -fobjc-arc -Wall -Wno-unused-parameter -Wno-unused-function -Wno-unused-variable \
  -o "$OUT/lod_mesh_bench" "$ROOT/tools/lod_mesh_bench.m" -framework Foundation -framework Metal
exec "$OUT/lod_mesh_bench" "$@"
