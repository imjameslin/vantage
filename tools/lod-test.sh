#!/bin/zsh
# Stage 3 (docs/stage3/LOD-DESIGN.md §8): headless LOD data-layer checks (tools/LodTest.java -> LodSelfTest in the client
# jar): level-0 build and sealing, codec round trip, downsample rules, store region files, key packing, level formula.
# No game, no GPU. Run tools/build-client.sh first (LOD_TEST_JAR=<jar> picks another jar).
set -e
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
JAR="${LOD_TEST_JAR:-$ROOT/build/client.jar}"
if [[ ! -f "$JAR" ]]; then
  echo "missing $JAR: run tools/build-client.sh first" >&2
  exit 2
fi
CP="$JAR"
for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' | sort)"}; do
  if [[ "$j" == *natives-macos.jar || "$j" == *osx-x86_64.jar ]]; then continue; fi
  CP="$CP:$j"
done
mkdir -p "$ROOT/build/lod-test-run"
cd "$ROOT/build/lod-test-run"
exec "$JH/bin/java" -ea "$@" -cp "$CP" "$ROOT/tools/LodTest.java"
