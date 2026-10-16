#!/bin/zsh
# Exactness test of the terrain shader specialization (TerrainShaderVariants): the vanilla terrain pipelines
# unchanged vs specialized on UseRgss vs RGSS early-out, byte-equal renders of a perspective terrain scene with
# both UseRgss values. Headless, Metal API validation on. See tools/TerrainShaderTest.java.
#
# Usage: tools/build-client.sh && tools/terrain-shader-test.sh [extra JVM args...]
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
EXTRACT="$ROOT/build/terrain-shader-natives"
mkdir -p "$EXTRACT" "$ROOT/build/terrain-shader-run"
cd "$ROOT/build/terrain-shader-run"   # log4j writes logs/ relative to the working directory
export MTL_DEBUG_LAYER=${MTL_DEBUG_LAYER:-1}
exec "$JH/bin/java" --enable-native-access=ALL-UNNAMED -XX:+UseZGC -ea \
  -Dorg.lwjgl.system.SharedLibraryExtractPath="$EXTRACT" \
  "$@" -cp "$CP" "$ROOT/tools/TerrainShaderTest.java"
