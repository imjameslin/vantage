#!/bin/zsh
set -euo pipefail
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
LIBS=("$ROOT"/mc/libraries/it/unimi/dsi/fastutil/*/fastutil-*.jar)
(( ${#LIBS} == 1 )) || { print -u2 'expected one fastutil jar'; exit 1; }
OUT="$(mktemp -d "${TMPDIR:-/tmp}/lod-exposure-cache.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
"$JH/bin/javac" -J-Xmx128m -J-XX:ActiveProcessorCount=2 --release 25 -proc:none -cp "$LIBS[1]" -d "$OUT" \
  "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodNearCoverage.java" "$ROOT/tools/LodExposureCacheTest.java"
"$JH/bin/java" -Xmx128m -XX:ActiveProcessorCount=2 -ea -cp "$OUT:$LIBS[1]" \
  net.minecraft.client.renderer.chunk.LodExposureCacheTest
