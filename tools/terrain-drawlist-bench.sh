#!/bin/zsh
# Render-thread terrain draw-list micro-benchmark / equivalence check (tools/TerrainDrawListBench.java): the real
# LevelRenderer.prepareChunkRendersIndirect over a synthetic world uploaded through the real section/uber-buffer path,
# on the real Metal device (headless). Run tools/build-client.sh first.
#
# Usage: tools/terrain-drawlist-bench.sh [--jar other.jar] [-Dprop=value...] [bench|check] [frames] [key=value...]
#   bench: ns + bytes allocated per call (alternates fastDrawList=false/true in blocks when the jar has the toggle)
#   check: prints a DIGEST of every output byte over a churning camera path; compare jars / -Dmc.terrain.* values
set -e
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
JAR="$ROOT/build/client.jar"
JVM=()
while (( $# )); do
  case "$1" in
    --jar) JAR="${2:A}"; shift 2 ;;
    -D*|-X*) JVM+=("$1"); shift ;;
    *) break ;;
  esac
done
if [[ ! -f "$JAR" ]]; then
  echo "missing $JAR: run tools/build-client.sh first" >&2
  exit 2
fi
CP="$JAR"
for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' | sort)"}; do
  if [[ "$j" == *natives-macos.jar || "$j" == *osx-x86_64.jar ]]; then continue; fi
  CP="$CP:$j"
done
EXTRACT="$ROOT/build/terrain-drawlist-natives"
mkdir -p "$EXTRACT" "$ROOT/build/terrain-drawlist-run"
cd "$ROOT/build/terrain-drawlist-run"   # log4j writes logs/ relative to the working directory
# The game's GC/heap flags (version json default-user-jvm), so allocation costs match.
exec "$JH/bin/java" --enable-native-access=ALL-UNNAMED -Xms2G -Xmx4G -XX:+UseCompactObjectHeaders -XX:+UseZGC \
  -Dorg.lwjgl.system.SharedLibraryExtractPath="$EXTRACT" \
  $JVM -cp "$CP" "$ROOT/tools/TerrainDrawListBench.java" "$@"
