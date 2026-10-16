#!/bin/zsh
# CPU-only retention checks against the current cache source and an existing client jar.
set -euo pipefail
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
JAR="${LOD_TEST_JAR:-$ROOT/build/client.jar}"
JAR="${JAR:A}"
CP="$JAR"
for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' ! -name '*natives*' | sort)"}; do CP="$CP:$j"; done
OUT="$(mktemp -d "${TMPDIR:-/tmp}/lod-mesh-cache-retention-test.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes" "$OUT/tmp" "$OUT/empty-source"
"$JH/bin/javac" -J-Xmx128m -J-XX:ActiveProcessorCount=2 --release 25 -proc:none -implicit:none \
  -sourcepath "$OUT/empty-source" -cp "$CP" -d "$OUT/classes" \
  "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodMeshCache.java" \
  "$ROOT/tools/LodMeshCacheRetentionTest.java"
cd "$OUT/tmp"
"$JH/bin/java" -Xmx256m -XX:ActiveProcessorCount=2 -ea --enable-native-access=ALL-UNNAMED \
  -Djava.awt.headless=true -cp "$OUT/classes:$CP" net.minecraft.client.renderer.chunk.LodMeshCacheRetentionTest "$OUT/tmp"
