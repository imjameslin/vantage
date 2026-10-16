#!/bin/zsh
# Compile only the pure CPU builders and independent scalar test; no client jar or full build.
set -euo pipefail
ROOT="${0:A:h:h}"
JH="${LOD_PREPARED_JAVA_HOME:-$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home}"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/lod-prepared-items.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes" "$OUT/empty"
"$JH/bin/javac" -J-Xmx128m -J-XX:ActiveProcessorCount=2 --release 25 -proc:none -implicit:none \
    -Xlint:all -Werror -sourcepath "$OUT/empty" -cp "$OUT/empty" -d "$OUT/classes" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodSectionItems.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodPreparedItems.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodPrepareTiming.java" \
    "$ROOT/tools/LodPreparedItemsTest.java"
"$JH/bin/java" -Xmx128m -XX:ActiveProcessorCount=2 -ea -Djava.awt.headless=true \
    -Djava.library.path="$OUT/empty" -Dmc.lod.prepareProfile="${LOD_PREPARE_PROFILE:-false}" \
    -Dtest.admissionCache="${LOD_ADMISSION_CACHE:-false}" \
    -Dtest.directGroupLookup="${LOD_DIRECT_GROUP_LOOKUP:-false}" \
    -cp "$OUT/classes" net.minecraft.client.renderer.chunk.LodPreparedItemsTest
