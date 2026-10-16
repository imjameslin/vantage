#!/bin/zsh
# Actual Java handoff controller + CPU-backed Metal buffers. No GPU/game evidence.
set -euo pipefail
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
CP="${LOD_NEAR_HANDOFF_JAR:-$ROOT/build/client-async-items-fade-a.jar}"
CP="${CP:A}"
for LIB in "$ROOT"/mc/libraries/**/*.jar; do
  [[ "$LIB" == *natives-macos.jar || "$LIB" == *osx-x86_64.jar ]] && continue
  CP="$CP:$LIB"
done
OUT="$(mktemp -d "${TMPDIR:-/tmp}/lod-near-handoff.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes" "$OUT/empty" "$OUT/natives"
SRC="$ROOT/client/src/net/minecraft/client/renderer/chunk"
"$JH/bin/javac" -J-Xmx512m -J-XX:ActiveProcessorCount=2 --release 25 -proc:none -implicit:none \
  -sourcepath "$OUT/empty" -cp "$CP" -d "$OUT/classes" \
  "$SRC/SectionRenderDispatcher.java" "$SRC/LodGpu.java" "$SRC/LodNearCoverage.java" "$SRC/LodSectionItems.java" "$SRC/Lod.java" "$SRC/TerrainDrawList.java" \
  "$ROOT/client/src/com/mojang/renderpearl/backend/metal/MetalClusterCull.java" \
  "$SRC/LodManager.java" "$SRC/LodMeshHandoff.java" "$SRC/LodPreparedItems.java" \
  "$SRC/LodPrepareTiming.java" "$SRC/LodItemWorker.java" \
  "$ROOT/tools/LodAsyncItemsTest.java" "$ROOT/tools/LodNearHandoffTest.java"
cd "$OUT"
"$JH/bin/java" -Xmx256m -XX:ActiveProcessorCount=2 --enable-native-access=ALL-UNNAMED -ea \
  -Djava.awt.headless=true -Dorg.lwjgl.system.SharedLibraryExtractPath="$OUT/natives" \
  -cp "$OUT/classes:$CP" net.minecraft.client.renderer.chunk.LodNearHandoffTest
