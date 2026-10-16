#!/bin/zsh
# Headless Metal backend smoke test: loads libmcmetal.dylib out of build/client.jar through the real
# MetalBackend.loadLibrary() path (LWJGL extraction + FFM), probes the GPU and cross-checks the C headers
# against MtlNative/StreamOps. Run tools/build-client.sh first.
#
# Usage: tools/metal-smoke.sh [extra JVM args...]   (e.g. -Dorg.lwjgl.util.DebugLoader=true)
set -e
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
JAR="$ROOT/build/client.jar"
if [[ ! -f "$JAR" ]]; then
  echo "missing $JAR: run tools/build-client.sh first" >&2
  exit 2
fi
CP="$JAR"
# All libraries, natives jars included (LWJGL loads liblwjgl.dylib from them); arm64 only.
for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' | sort)"}; do
  if [[ "$j" == *natives-macos.jar || "$j" == *osx-x86_64.jar ]]; then continue; fi
  CP="$CP:$j"
done
EXTRACT="$ROOT/build/smoke-natives"
mkdir -p "$EXTRACT" "$ROOT/build/smoke"
cd "$ROOT/build/smoke"   # log4j writes logs/ relative to the working directory
exec "$JH/bin/java" --enable-native-access=ALL-UNNAMED -XX:+UseZGC \
  -Dorg.lwjgl.system.SharedLibraryExtractPath="$EXTRACT" \
  "$@" -cp "$CP" "$ROOT/tools/MetalSmoke.java" "$ROOT"
