#!/bin/zsh
# CPU-only snapshot validation and render-thread release policy; no GPU/game.
set -euo pipefail
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
OUT="$ROOT/build/lod-far-geometry-test"
CP="$ROOT/build/client.jar"
[[ -f "$CP" ]] || { print -u2 "Build client.jar first"; exit 2; }
for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' ! -name '*natives*' | sort)"}; do CP="$CP:$j"; done
mkdir -p "$OUT"
"$JH/bin/javac" --release 25 -proc:none -cp "$CP" -d "$OUT" "$ROOT/tools/LodFarGeometryTest.java"
cd "$OUT"
exec "$JH/bin/java" -ea -cp "$OUT:$CP" net.minecraft.client.renderer.chunk.LodFarGeometryTest
