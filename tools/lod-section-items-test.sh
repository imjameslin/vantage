#!/bin/zsh
# Targeted Java compilation against the existing client jar; no full build, native, GPU or game.
set -euo pipefail
ROOT="${0:A:h:h}"
JH="${LOD_ITEMS_JAVA_HOME:-$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home}"
CLIENT="${LOD_ITEMS_CLIENT_JAR:-$ROOT/build/client.jar}"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/lod-section-items.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
CP="$CLIENT"
for LIB in "$ROOT"/mc/libraries/**/*.jar; do CP="$CP:$LIB"; done
mkdir -p "$OUT/classes" "$OUT/empty"
"$JH/bin/javac" -J-Xmx256m -J-XX:ActiveProcessorCount=2 --release 25 -proc:none -implicit:none \
    -sourcepath "$OUT/empty" -cp "$CP" -d "$OUT/classes" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodGpu.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodSectionItems.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodNodeItems.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodManager.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodMeshHandoff.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodPreparedItems.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodPrepareTiming.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodItemWorker.java" \
    "$ROOT/tools/LodSectionItemsTest.java"
"$JH/bin/java" -Xmx128m -XX:ActiveProcessorCount=2 -ea -Djava.awt.headless=true -Djava.library.path="$OUT/empty" \
    -cp "$OUT/classes:$CP" net.minecraft.client.renderer.chunk.LodSectionItemsTest false
"$JH/bin/java" -Xmx128m -XX:ActiveProcessorCount=2 -ea -Djava.awt.headless=true -Djava.library.path="$OUT/empty" \
    -Dmc.lod.cacheItems=true -Dmc.lod.diag=levels -cp "$OUT/classes:$CP" \
    net.minecraft.client.renderer.chunk.LodSectionItemsTest true
