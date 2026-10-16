#!/bin/zsh
# End-to-end check of terrain occlusion culling (MCMTL_OCCLUSION, docs/metal/CONTRACT.md 16.6) on the real Java backend
# with vanilla *_TERRAIN_MULTIDRAW pipelines (offscreen, headless): the same 180 frames are rendered with MCMTL_OCCLUSION=0
# and =1; every frame's colour + depth hash must match, and the ON run's decoder statistics must show culled sections.
# Run tools/build-client.sh first.
#
# Usage: tools/occlusion-e2e-test.sh [extra JVM args...]
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
EXTRACT="$ROOT/build/occlusion-e2e-natives"
RUN="$ROOT/build/occlusion-e2e-run"
mkdir -p "$EXTRACT" "$RUN"
cd "$RUN"   # log4j writes logs/ relative to the working directory
export MTL_DEBUG_LAYER=${MTL_DEBUG_LAYER:-1}
for mode in 0 1; do
  echo "== MCMTL_OCCLUSION=$mode"
  MCMTL_OCCLUSION=$mode MCMTL_DECODER_STATS=60 "$JH/bin/java" --enable-native-access=ALL-UNNAMED -XX:+UseZGC -ea \
    -Dorg.lwjgl.system.SharedLibraryExtractPath="$EXTRACT" \
    "$@" -cp "$CP" "$ROOT/tools/OcclusionE2ETest.java" "$RUN/frames-$mode.txt" 2> "$RUN/stderr-$mode.txt" | tail -3
  grep 'mcmtl occlusion' "$RUN/stderr-$mode.txt" | sed 's/^.*mcmtl occlusion/  mcmtl occlusion/' || true
done
if ! cmp -s "$RUN/frames-0.txt" "$RUN/frames-1.txt"; then
  echo "FAIL: frames differ with occlusion culling:"
  diff "$RUN/frames-0.txt" "$RUN/frames-1.txt" | head -20
  echo "OCCLUSION E2E FAILED"
  exit 1
fi
frames=$(wc -l < "$RUN/frames-1.txt" | tr -d ' ')
culled=$(grep 'mcmtl occlusion' "$RUN/stderr-1.txt" | sed -E 's/.*, culled ([0-9.]+) .*/\1/' | awk '{s += $1} END {print s + 0}')
if (( frames != 180 )) || [[ "$culled" == "0" ]]; then
  echo "FAIL: $frames frames, culled sections per frame (sum over logs) $culled"
  echo "OCCLUSION E2E FAILED"
  exit 1
fi
echo "OCCLUSION E2E PASSED ($frames identical frames; culled sections per planned frame, per 60-frame log: $(grep 'mcmtl occlusion' "$RUN/stderr-1.txt" | sed -E 's/.*, culled ([0-9.]+) .*/\1/' | tr '\n' ' '))"
