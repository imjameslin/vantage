#!/bin/zsh
# CPU cache checks against the two current cache sources and an existing client jar.
# No client/native build, GPU or game launch; all test output is temporary.
set -euo pipefail
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
JAR="${LOD_TEST_JAR:-$ROOT/build/client.jar}"
JAR="${JAR:A}"
CP="$JAR"
for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' ! -name '*natives*' | sort)"}; do CP="$CP:$j"; done
OUT="$(mktemp -d "${TMPDIR:-/tmp}/lod-mesh-cache-test.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes" "$OUT/tmp" "$OUT/empty-source"
"$JH/bin/javac" -J-Xmx128m -J-XX:ActiveProcessorCount=2 --release 25 -proc:none -implicit:none \
  -sourcepath "$OUT/empty-source" -cp "$CP" -d "$OUT/classes" \
  "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodMeshCache.java" \
  "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodMeshCacheContext.java" \
  "$ROOT/tools/LodMeshCacheTest.java"
cd "$OUT/tmp" # Keep Bootstrap/log4j output inside the disposable test directory too.
"$JH/bin/java" -Xmx256m -XX:ActiveProcessorCount=2 -ea --enable-native-access=ALL-UNNAMED \
  -Djava.awt.headless=true -cp "$OUT/classes:$CP" net.minecraft.client.renderer.chunk.LodMeshCacheTest "$OUT/tmp"
