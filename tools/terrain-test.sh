#!/bin/zsh
# Stage-2 terrain patch tests (docs/stage2/TERRAIN-PLAN.md S0/S1/S1b/S2/S7), headless: facing classification,
# in-place stable bucket sort + metadata, visible-bucket mask, merged-run records, exact frustum, cloud face filter.
# No GPU, window or game instance. Run tools/build-client.sh first.
#
# Usage: tools/terrain-test.sh [extra JVM args...]
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
mkdir -p "$ROOT/build/terrain-test"
cd "$ROOT/build/terrain-test"   # log4j writes logs/ relative to the working directory
exec "$JH/bin/java" -XX:+UseZGC "$@" -cp "$CP" "$ROOT/tools/TerrainTest.java"
