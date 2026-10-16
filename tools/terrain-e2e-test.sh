#!/bin/zsh
# GPU parity test of the stage-2 terrain patches on the real Metal backend (offscreen, headless): vanilla
# SOLID_TERRAIN_MULTIDRAW through indirect records in emission order vs. facing buckets / 16-bit split records / exact
# frustum, and CLOUDS with all faces vs. the filtered subset. Every comparison must be bit-identical.
# Run tools/build-client.sh first.
#
# Usage: tools/terrain-e2e-test.sh [--icb-parity] [extra JVM args...]
#   --icb-parity  terrain ICB (docs/metal/CONTRACT.md 16.5): runs the test twice, with the per-record path
#                 (MCMTL_TERRAIN_ICB=0) and with ICB ranges for every group from the first frame (MCMTL_TERRAIN_ICB=1
#                 MCMTL_TERRAIN_ICB_SYNC=1 MCMTL_TERRAIN_ICB_MIN_RECORDS=1), and requires identical image digests; prints
#                 the decoder's ICB statistics.
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
EXTRACT="$ROOT/build/terrain-e2e-natives"
mkdir -p "$EXTRACT" "$ROOT/build/terrain-e2e-run"
cd "$ROOT/build/terrain-e2e-run"   # log4j writes logs/ relative to the working directory
export MTL_DEBUG_LAYER=${MTL_DEBUG_LAYER:-1}
run() {
  "$JH/bin/java" --enable-native-access=ALL-UNNAMED -XX:+UseZGC -ea \
    -Dorg.lwjgl.system.SharedLibraryExtractPath="$EXTRACT" \
    "$@" -cp "$CP" "$ROOT/tools/TerrainE2ETest.java"
}
if [[ "$1" != "--icb-parity" ]]; then
  run "$@"
  exit $?
fi
shift
OUT="$ROOT/build/terrain-e2e-run"
rc=0
MCMTL_TERRAIN_ICB=0 run "$@" > "$OUT/per-record.txt" 2>&1 || rc=1
MCMTL_TERRAIN_ICB=1 MCMTL_TERRAIN_ICB_SYNC=1 MCMTL_TERRAIN_ICB_MIN_RECORDS=1 MCMTL_DECODER_STATS=1 run "$@" > "$OUT/icb.txt" 2>&1 || rc=1
grep -h "TERRAIN E2E\|FAIL" "$OUT/per-record.txt" "$OUT/icb.txt" || true
grep -h "mcmtl terrain ICB" "$OUT/per-record.txt" "$OUT/icb.txt" | sed 's/^.*mcmtl terrain/mcmtl terrain/' || true
grep "DIGEST" "$OUT/per-record.txt" > "$OUT/per-record.digest" || true
grep "DIGEST" "$OUT/icb.txt" > "$OUT/icb.digest" || true
if [[ ! -s "$OUT/per-record.digest" ]] || ! cmp -s "$OUT/per-record.digest" "$OUT/icb.digest"; then
  echo "ICB PARITY FAILED: image digests differ (or are missing)"
  diff "$OUT/per-record.digest" "$OUT/icb.digest" || true
  exit 1
fi
echo "ICB PARITY: $(wc -l < "$OUT/icb.digest" | tr -d ' ') pose digests identical (per-record vs ICB)"
exit $rc
