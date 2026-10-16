#!/bin/zsh
# Actual Java FFM/material/MAIN-stream integration; caller owns the sequential GPU lane.
# No client/native build, window, game, shaders or far-queue work.
# --compile-only is CPU only; --check-native loads/checks symbols without creating a device.
# Usage: zsh tools/far-materials-test.sh [--compile-only|--check-native] [JVM args...]
# Override dylib with -Dmcmetal.library=/absolute/path/to/current/libmcmetal.dylib.
# Select one case with -DfarMaterials.case=roundtrip (see the Java main for names).
set -euo pipefail
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
OUT="$ROOT/build/far-materials-java-test"
JAR="$ROOT/build/client.jar"
MODE=run
if [[ "${1:-}" == --compile-only || "${1:-}" == --check-native ]]; then
  MODE="${1#--}"
  shift
fi
[[ -f "$JAR" ]] || { print -u2 "missing $JAR; requires the parent's existing client build"; exit 2; }
CP="$JAR"
for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' | sort)"}; do
  if [[ "$j" == *natives-macos.jar || "$j" == *osx-x86_64.jar ]]; then continue; fi
  CP="$CP:$j"
done
mkdir -p "$OUT/classes" "$OUT/natives" "$OUT/run" "$OUT/empty-sourcepath"
# Explicit source overlay: never accidentally test the jar's older bridge or compile the whole client.
METAL="$ROOT/client/src/com/mojang/renderpearl/backend/metal"
"$JH/bin/javac" --release 25 -proc:none -implicit:none -encoding UTF-8 \
  -sourcepath "$OUT/empty-sourcepath" -cp "$CP" -d "$OUT/classes" \
  "$METAL/MetalFarMaterials.java" "$METAL/MetalFarGeometryPins.java" "$METAL/MetalFarPrograms.java" \
  "$ROOT/tools/MetalFarMaterialsTest.java"
if [[ "$MODE" == compile-only ]]; then
  print "Compiled current Java bridges and MetalFarMaterialsTest (no GPU work)."
  exit 0
fi
cd "$OUT/run"
export MTL_DEBUG_LAYER=${MTL_DEBUG_LAYER:-1}
exec "$JH/bin/java" --enable-native-access=ALL-UNNAMED -XX:+UseZGC -ea \
  -Dorg.lwjgl.system.SharedLibraryExtractPath="$OUT/natives" \
  -Dmcmetal.library="$ROOT/build/native/libmcmetal.dylib" -Dmc.lod.async=true \
  "$@" -cp "$OUT/classes:$CP" com.mojang.renderpearl.backend.metal.MetalFarMaterialsTest "$MODE"
