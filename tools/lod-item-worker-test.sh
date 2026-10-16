#!/bin/zsh
# Pure helper + focused test only: no client jar, libraries, renderer, native code or shared build outputs.
set -euo pipefail
ROOT="${0:A:h:h}"
JH="${LOD_ITEM_WORKER_JAVA_HOME:-$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home}"
JH="${JH:A}"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/lod-item-worker.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes" "$OUT/empty"
cd "$OUT"
"$JH/bin/javac" -J-Xmx128m -J-XX:ActiveProcessorCount=2 --release 25 -proc:none -implicit:none \
    -cp "$OUT/empty" -sourcepath "$OUT/empty" -d "$OUT/classes" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodItemWorker.java" \
    "$ROOT/tools/LodItemWorkerTest.java"
"$JH/bin/java" -Xmx128m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC -XX:-DisableExplicitGC \
    -ea -Djava.awt.headless=true -Djava.library.path="$OUT/empty" -cp "$OUT/classes" \
    net.minecraft.client.renderer.chunk.LodItemWorkerTest
