#!/bin/zsh
# I1 integration test: every vanilla RenderPipeline through GLSL -> shaderc -> SPIRV-Cross MSL ->
# mcmtl_shader_create -> mcmtl_pipeline_create (real natives, compile pool). See tools/CoreMslTest.java.
# Run tools/build-client.sh first.
#
# Usage: tools/core-msl-test.sh [--dump build/msl]
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
mkdir -p "$ROOT/build/coremsl" "$ROOT/build/coremsl-natives"
cd "$ROOT/build/coremsl"   # log4j writes logs/ relative to the working directory
export MTL_DEBUG_LAYER=${MTL_DEBUG_LAYER:-1}
exec "$JH/bin/java" --enable-native-access=ALL-UNNAMED \
  -Dmcmetal.library="$ROOT/build/native/libmcmetal.dylib" -Dorg.lwjgl.system.SharedLibraryExtractPath="$ROOT/build/coremsl-natives" \
  -cp "$CP" "$ROOT/tools/CoreMslTest.java" "$ROOT" "$@"
