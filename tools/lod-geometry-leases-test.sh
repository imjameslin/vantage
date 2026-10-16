#!/bin/zsh
# Only the pure Java retirement policy and fake allocator. No client.jar, native code, GPU or game.
set -euo pipefail
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/lod-geometry-leases.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
"$JH/bin/javac" --release 25 -proc:none -d "$OUT" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodGeometryLeases.java" \
    "$ROOT/tools/LodGeometryLeasesTest.java"
"$JH/bin/java" -ea -cp "$OUT" net.minecraft.client.renderer.chunk.LodGeometryLeasesTest
