#!/bin/zsh
# Integration end-to-end test (headless, no window): real vanilla pipelines through the whole merged Metal
# backend (FrontendGpuDevice -> MetalDevice/encoder/pipelines -> libmcmetal from build/client.jar), offscreen
# rendering and pixel readback. See tools/MetalE2ETest.java. Metal API validation is on: any validation error
# aborts the process (non-zero exit).
#
# Usage: tools/build-client.sh && tools/metal-e2e-test.sh [extra JVM args...]
#        (e.g. -Dmcmetal.strictBindings=true, -Dmcmetal.library=/abs/path/libmcmetal.dylib)
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
EXTRACT="$ROOT/build/e2e-natives"
mkdir -p "$EXTRACT" "$ROOT/build/e2e-run"
cd "$ROOT/build/e2e-run"   # log4j writes logs/ relative to the working directory
export MTL_DEBUG_LAYER=${MTL_DEBUG_LAYER:-1}
exec "$JH/bin/java" --enable-native-access=ALL-UNNAMED -XX:+UseZGC -ea \
  -Dorg.lwjgl.system.SharedLibraryExtractPath="$EXTRACT" \
  "$@" -cp "$CP" "$ROOT/tools/MetalE2ETest.java" "$ROOT"
