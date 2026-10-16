#!/bin/zsh
# R7-1 (docs/metal/CONTRACT.md 16.R7-1): the lightmap kernel vs vanilla's "Update light" render pass on the real Metal
# backend, headless, under the Metal debug layer: byte-identical lightmaps for every parameter set, the kernel really ran,
# a mixed kernel / render-pass / in-place sequence digests like the render-pass-only one. Run tools/build-client.sh first.
# Extra args go to the JVM (e.g. -Dlightmap.sets=20000).
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
OUT="$ROOT/build/lightmap-compute-run"
EXTRACT="$ROOT/build/lightmap-compute-natives"
mkdir -p "$OUT" "$EXTRACT"
cd "$OUT"
export MTL_DEBUG_LAYER=${MTL_DEBUG_LAYER:-1}
"$JH/bin/java" --enable-native-access=ALL-UNNAMED -XX:+UseZGC -ea \
  -Dorg.lwjgl.system.SharedLibraryExtractPath="$EXTRACT" "$@" -cp "$CP" "$ROOT/tools/LightmapComputeTest.java" > "$OUT/run.txt" 2>&1 || true
grep -h "MISMATCH\|LIGHTMAP\|sequence\|FAIL\|device message" "$OUT/run.txt" || true
grep -q "LIGHTMAP COMPUTE PASSED" "$OUT/run.txt"
