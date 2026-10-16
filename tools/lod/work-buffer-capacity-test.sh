#!/bin/zsh
# Compile only the CPU policy and its fixture. No client build, native libraries, GPU or game.
set -euo pipefail
ROOT="${0:A:h:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/lod-work-buffers.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
"$JH/bin/javac" --release 25 -proc:none -d "$OUT" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodGpuBufferCapacity.java" \
    "$ROOT/tools/LodGpuBufferCapacityTest.java"
"$JH/bin/java" -ea -cp "$OUT" net.minecraft.client.renderer.chunk.LodGpuBufferCapacityTest false
"$JH/bin/java" -ea -Dmc.lod.compactWorkBuffers=true -cp "$OUT" net.minecraft.client.renderer.chunk.LodGpuBufferCapacityTest true
