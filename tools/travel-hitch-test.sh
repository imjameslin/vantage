#!/bin/zsh
# TASK E headless tests (docs/metal/CONTRACT.md 16.R5-E): Metal heap reserve, UberGpuBuffer pregrow vs vanilla, terrain
# upload budget, entity texture prefetch. Compiles tools/TravelHitchTest.java against build/client.jar into
# build/travel-hitch-test (package com.mojang.renderpearl.backend.metal, for the package-private seams) and runs it with
# -Dmc.terrain.pregrow=true. No GPU work: the Metal device is the headless forTesting one. Run tools/build-client.sh first.
#
# Usage: tools/travel-hitch-test.sh [extra JVM args...]
#   env TRAVEL_HITCH_OUT: build directory (default build/travel-hitch-test); tools/headless-suites.sh gives each variant its
#   own, so they can run at the same time.
set -e
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
JAR="$ROOT/build/client.jar"
if [[ ! -f "$JAR" ]]; then
  echo "missing $JAR: run tools/build-client.sh first" >&2
  exit 2
fi
CP="$JAR"
# All libraries, natives jars included (LWJGL core and STB load from them); arm64 only.
for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' | sort)"}; do
  if [[ "$j" == *natives-macos.jar || "$j" == *osx-x86_64.jar ]]; then continue; fi
  CP="$CP:$j"
done
OUT="${TRAVEL_HITCH_OUT:-$ROOT/build/travel-hitch-test}"; OUT="${OUT:A}"
rm -rf "$OUT/classes"; mkdir -p "$OUT/classes" "$OUT/natives"
"$JH/bin/javac" --release 25 -g -nowarn -proc:none -encoding UTF-8 -cp "$CP" -d "$OUT/classes" "$ROOT/tools/TravelHitchTest.java" \
  "$ROOT/tools/ref/RefUberGpuBuffer.java"
cd "$OUT"   # log4j writes logs/ relative to the working directory
exec "$JH/bin/java" --enable-native-access=ALL-UNNAMED -XX:+UseZGC -ea -Djava.awt.headless=true \
  -Dorg.lwjgl.system.SharedLibraryExtractPath="$OUT/natives" -Dmc.terrain.pregrow=true \
  "$@" -cp "$OUT/classes:$CP" com.mojang.renderpearl.backend.metal.TravelHitchTest
