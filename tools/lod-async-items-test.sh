#!/bin/zsh
# Actual renderer controller with headless MetalDevice and CPU-backed buffers, no Metal GPU/game.
set -euo pipefail
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
CP="${LOD_ASYNC_ITEMS_JAR:-$ROOT/build/client-node-items-a.jar}"
CP="${CP:A}"
for LIB in "$ROOT"/mc/libraries/**/*.jar; do
  [[ "$LIB" == *natives-macos.jar || "$LIB" == *osx-x86_64.jar ]] && continue
  CP="$CP:$LIB"
done
OUT="$(mktemp -d "${TMPDIR:-/tmp}/lod-async-items.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes" "$OUT/empty" "$OUT/natives"
SRC="$ROOT/client/src/net/minecraft/client/renderer/chunk"
"$JH/bin/javac" -J-Xmx256m -J-XX:ActiveProcessorCount=2 --release 25 -proc:none -implicit:none \
  -sourcepath "$OUT/empty" -cp "$CP" -d "$OUT/classes" \
  "$SRC/SectionRenderDispatcher.java" "$SRC/Lod.java" "$SRC/LodNearCoverage.java" "$SRC/TerrainDrawList.java" \
  "$ROOT/client/src/com/mojang/renderpearl/backend/metal/MetalClusterCull.java" \
  "$SRC/LodGpu.java" "$SRC/LodManager.java" "$SRC/LodMeshHandoff.java" \
  "$SRC/LodPreparedItems.java" "$SRC/LodPrepareTiming.java" "$SRC/LodItemWorker.java" "$ROOT/tools/LodAsyncItemsTest.java"
cd "$OUT"
for ATOMIC in false true; do
  "$JH/bin/java" -Xmx256m -XX:ActiveProcessorCount=2 --enable-native-access=ALL-UNNAMED -ea \
    -Djava.awt.headless=true -Dorg.lwjgl.system.SharedLibraryExtractPath="$OUT/natives" \
    -Dmc.lod.atomicMeshHandoff="$ATOMIC" \
    -Dtest.exactRevalidate="${LOD_EXACT_REVALIDATE:-false}" \
    -cp "$OUT/classes:$CP" net.minecraft.client.renderer.chunk.LodAsyncItemsTest
done
