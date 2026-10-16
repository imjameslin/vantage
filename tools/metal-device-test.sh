#!/bin/zsh
# I3 (java-device) tests: compiles tools/MetalDeviceTest.java against build/client.jar into build/i3-tests (same
# runtime package as the backend, so the package-private test seams are reachable) and runs it with
# MTL_DEBUG_LAYER=1. Part A needs no native library; part B uses the dylib packaged in build/client.jar (or
# -Dmcmetal.library=/abs/path/libmcmetal.dylib) and is skipped while mcmtl_device_create is a stub.
# Run tools/build-client.sh first.
#
# Usage: tools/metal-device-test.sh [extra JVM args...]
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
OUT="$ROOT/build/i3-tests"
rm -rf "$OUT/classes"; mkdir -p "$OUT/classes" "$OUT/natives"
"$JH/bin/javac" --release 25 -g -nowarn -proc:none -encoding UTF-8 -cp "$CP" -d "$OUT/classes" "$ROOT/tools/MetalDeviceTest.java"
cd "$OUT"   # log4j writes logs/ relative to the working directory
MTL_DEBUG_LAYER=1 exec "$JH/bin/java" --enable-native-access=ALL-UNNAMED -XX:+UseZGC -ea \
  -Dorg.lwjgl.system.SharedLibraryExtractPath="$OUT/natives" \
  "$@" -cp "$OUT/classes:$CP" com.mojang.renderpearl.backend.metal.MetalDeviceTest
