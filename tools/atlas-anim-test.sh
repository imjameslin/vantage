#!/bin/zsh
# R5-D (docs/metal/CONTRACT.md 16.R5-D): texture-animation upload parity on the real Metal backend, headless.
# Runs tools/AtlasAnimTest.java with -Dmc.fastTextureAnimation=false and =true on a block atlas built from the jar's
# block textures (mip 4), compares the per-tick SHA-256 of every mip level (must be identical), prints the cost lines.
# Run tools/build-client.sh first. Extra args go to both JVMs (e.g. -Datlas.ticks=600).
set -e
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
JAR="$ROOT/build/client.jar"
if [[ ! -f "$JAR" ]]; then
  echo "missing $JAR: run tools/build-client.sh first" >&2
  exit 2
fi
CP="$JAR"
for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' | sort)"}; do
  if [[ "$j" == *natives-macos.jar || "$j" == *osx-x86_64.jar ]]; then continue; fi
  CP="$CP:$j"
done
OUT="$ROOT/build/atlas-anim-run"
EXTRACT="$ROOT/build/atlas-anim-natives"
mkdir -p "$OUT" "$EXTRACT"
cd "$OUT"   # log4j writes logs/ relative to the working directory
export MTL_DEBUG_LAYER=${MTL_DEBUG_LAYER:-1}
run() {
  "$JH/bin/java" --enable-native-access=ALL-UNNAMED -XX:+UseZGC -ea \
    -Dorg.lwjgl.system.SharedLibraryExtractPath="$EXTRACT" \
    "$@" -cp "$CP" "$ROOT/tools/AtlasAnimTest.java"
}
rc=0
# One configuration: toggle off vs on, identical per-tick digests of every mip level.
#   $1 = name (log prefix), $2.. = extra JVM args
check_config() {
  local name=$1; shift
  run -Dmc.texture.pingPong=false -Dmc.fastTextureAnimation=false "$@" > "$OUT/$name-off.txt" 2>&1 || rc=1
  run -Dmc.texture.pingPong=false -Dmc.fastTextureAnimation=true "$@" > "$OUT/$name-on.txt" 2>&1 || rc=1
  grep -h "^atlas \|^sprites:\|COST\|FAIL\|PASSED\|device message" "$OUT/$name-off.txt" | sed "s/^/$name off: /" || true
  grep -h "^atlas \|^sprites:\|COST\|FAIL\|PASSED\|device message" "$OUT/$name-on.txt" | sed "s/^/$name on:  /" || true
  grep "^DIGEST" "$OUT/$name-off.txt" > "$OUT/$name-off.digest" || true
  grep "^DIGEST" "$OUT/$name-on.txt" > "$OUT/$name-on.digest" || true
  if [[ ! -s "$OUT/$name-off.digest" ]] || ! cmp -s "$OUT/$name-off.digest" "$OUT/$name-on.digest"; then
    echo "ATLAS PARITY FAILED ($name): mip-level digests differ (or are missing)"
    diff "$OUT/$name-off.digest" "$OUT/$name-on.digest" | head -20 || true
    exit 1
  fi
  distinct=$(awk '$3 == 0 {print $4}' "$OUT/$name-off.digest" | sort -u | wc -l | tr -d ' ')
  unchanged=$(awk '$3 == 0 { if ($2 > 0 && $4 == prev) n++; prev = $4 } END { print n + 0 }' "$OUT/$name-off.digest")
  echo "ATLAS PARITY ($name): $(wc -l < "$OUT/$name-on.digest" | tr -d ' ') level digests identical (toggle off vs on), $distinct distinct level-0 states, $unchanged ticks without a change"
}
# 1. The block atlas (interpolated + plain animated sprites: both pipelines every tick).
check_config blocks "$@"
# 2. r5-int review fix (D): no interpolated sprites, so ticks take the BLIT-only pass and, when no sprite changed frame,
#    the early return (no pass at all). Requires at least one tick without a change.
check_config nointerp -Datlas.noInterpolated=true -Datlas.ticks=240 -Datlas.costTicks=400 "$@"
nointerpUnchanged=$unchanged
# 2b. Few slow animations (frame time >= 3): most ticks change nothing (the early return), some take the BLIT pass.
check_config slow -Datlas.noInterpolated=true -Datlas.minFrameTime=3 -Datlas.maxAnimated=4 -Datlas.ticks=120 -Datlas.costTicks=400 "$@"
if (( unchanged == 0 )); then
  echo "ATLAS PARITY FAILED (nointerp): no tick without a change, the early return was not exercised"
  exit 1
fi
# 3. R6-1 (docs/metal/CONTRACT.md 16.R6-1): ping-pong atlas (-Dmc.texture.pingPong) vs in place, digests of the copy frames
#    sample after every tick must equal the in-place atlas's (both with fast animation on: the r5 default).
check_pingpong() {
  local name=$1; shift
  run -Dmc.fastTextureAnimation=true -Dmc.texture.pingPong=false "$@" > "$OUT/$name-inplace.txt" 2>&1 || rc=1
  run -Dmc.fastTextureAnimation=true -Dmc.texture.pingPong=true "$@" > "$OUT/$name-pp.txt" 2>&1 || rc=1
  grep -h "COST\|FAIL\|PINGPONG\|LIGHTMAP\|device message" "$OUT/$name-inplace.txt" | sed "s/^/$name in place: /" || true
  grep -h "COST\|FAIL\|PINGPONG\|LIGHTMAP\|device message" "$OUT/$name-pp.txt" | sed "s/^/$name ping-pong: /" || true
  grep "^DIGEST" "$OUT/$name-inplace.txt" > "$OUT/$name-inplace.digest" || true
  grep "^DIGEST" "$OUT/$name-pp.txt" > "$OUT/$name-pp.digest" || true
  if [[ ! -s "$OUT/$name-inplace.digest" ]] || ! cmp -s "$OUT/$name-inplace.digest" "$OUT/$name-pp.digest"; then
    echo "ATLAS PING-PONG PARITY FAILED ($name): mip-level digests differ (or are missing)"
    diff "$OUT/$name-inplace.digest" "$OUT/$name-pp.digest" | head -20 || true
    exit 1
  fi
  if ! grep -q "PINGPONG.*distinct copies sampled 2" "$OUT/$name-pp.txt" || ! grep -q "LIGHTMAP.*distinct copies sampled 2" "$OUT/$name-pp.txt"; then
    echo "ATLAS PING-PONG FAILED ($name): the ping-pong run did not alternate between two copies"
    exit 1
  fi
  # R7-1 / R7-2 (CONTRACT 16.R7-1, 16.R7-2): unless switched off, the ping-pong run's ticks and lightmap updates must have
  # gone through the compute kernels (so this parity is the kernels' parity against vanilla's render passes).
  if [[ "$*" != *animCompute=false* ]]; then
    if ! grep -q "PINGPONG.*kernel ticks [1-9]" "$OUT/$name-pp.txt"; then
      echo "ATLAS KERNEL FAILED ($name): no atlas tick went through the kernel"
      exit 1
    fi
  fi
  if [[ "$*" != *lightmap.compute=false* ]]; then
    if ! grep -q "LIGHTMAP.*kernel updates [1-9]" "$OUT/$name-pp.txt"; then
      echo "LIGHTMAP KERNEL FAILED ($name): no lightmap update went through the kernel"
      exit 1
    fi
  fi
  echo "ATLAS PING-PONG PARITY ($name): $(wc -l < "$OUT/$name-pp.digest" | tr -d ' ') level digests identical (in place vs ping-pong)"
}
check_pingpong pp-blocks "$@"
check_pingpong pp-slow -Datlas.noInterpolated=true -Datlas.minFrameTime=3 -Datlas.maxAnimated=4 -Datlas.ticks=120 -Datlas.costTicks=400 "$@"
check_pingpong pp-flip3 -Datlas.pingPongFlip=3 -Datlas.ticks=200 -Datlas.costTicks=400 "$@"
check_pingpong pp-fliprand -Datlas.pingPongFlip=-1 -Datlas.ticks=200 -Datlas.costTicks=400 "$@"
# R7: the same with the kernels switched off (ping-pong render passes, the r6b path).
check_pingpong pp-render -Dmc.texture.animCompute=false -Dmc.lightmap.compute=false "$@"
# R7-2: the kernels with the runtime toggle flipped every few ticks (render-pass and kernel ticks mixed).
check_pingpong pp-kflip -Datlas.computeFlip=-1 -Datlas.ticks=200 -Datlas.costTicks=400 "$@"
check_pingpong pp-fliprand-slow -Datlas.pingPongFlip=-1 -Datlas.noInterpolated=true -Datlas.minFrameTime=3 -Datlas.maxAnimated=6 -Datlas.ticks=200 -Datlas.costTicks=400 "$@"
exit $rc
