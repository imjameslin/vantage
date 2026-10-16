#!/bin/zsh
# Terrain ICB probe + benchmark (docs/metal/CONTRACT.md 16.5, spec U4): plain Metal, headless, no window. Builds
# tools/terrain_icb_probe.m and runs it: U4 checks (ICB base vertex / base instance, odd 16-bit index offsets, reset
# commands, instanceCount > 1, which shaders ICB pipelines accept; bytes vs per-record draws), then serialized GPU time
# and CPU encode time of ~860 terrain records at 3600x2260 for: per-record indirect draws, CPU-decoded direct draws,
# ICBs (CPU-encoded every frame, cached, GPU-encoded by a compute kernel), the arg-buffer texture shaders, baselines.
#
# Usage: tools/terrain-icb-probe.sh [frames=200] [rounds=3] [scene=terrain|overhead|overhead-tile]
#   env: MTL_DEBUG_LAYER=1 (validation), PROBE_PIPELINED=1 (CPU only, frames in flight), PROBE_SPLIT=k (k executes/group)
set -e
ROOT="${0:A:h:h}"
OUT="$ROOT/build/native-tests"
mkdir -p "$OUT"
xcrun clang -arch arm64 -mmacosx-version-min=26.0 -O2 -fobjc-arc -Wall -Wno-unused-parameter \
  -o "$OUT/terrain_icb_probe" "$ROOT/tools/terrain_icb_probe.m" -framework Foundation -framework Metal
exec "$OUT/terrain_icb_probe" "$@"
