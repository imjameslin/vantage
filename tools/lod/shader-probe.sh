#!/bin/zsh
# Compile every production Farsight LOD pipeline without launching a game.
set -e
ROOT="${0:A:h:h:h}"
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
EXTRACT="$ROOT/build/lod-shader-probe-natives"
mkdir -p "$EXTRACT" "$ROOT/build/lod-shader-probe-run"
cd "$ROOT/build/lod-shader-probe-run"   # log4j writes logs/ relative to the working directory
export MTL_DEBUG_LAYER=${MTL_DEBUG_LAYER:-1}
exec "$JH/bin/java" --enable-native-access=ALL-UNNAMED -XX:+UseZGC -ea \
  -Dorg.lwjgl.system.SharedLibraryExtractPath="$EXTRACT" \
  -Dmc.lod=true "$@" -cp "$CP" "$ROOT/tools/LodShaderProbeTest.java"
