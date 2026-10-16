#!/bin/zsh
# CPU-only production capture API. Compile just capture classes against existing dependencies.
# Never builds client.jar or native code, initializes a GPU/device, or launches the game.
set -euo pipefail
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
CP="$ROOT/build/client.jar"
[[ -f "$CP" ]] || { print -u2 "Missing existing build/client.jar dependencies"; exit 2; }
for j in ${(f)"$(rg --files --no-ignore "$ROOT/mc/libraries" -g '*.jar' | sort)"}; do
    [[ "$j" == *natives-macos.jar || "$j" == *osx-x86_64.jar ]] && continue
    CP="$CP:$j"
done
OUT="$(mktemp -d "${TMPDIR:-/tmp}/lod-far-refresh.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/empty"
SRC="$ROOT/client/src/net/minecraft/client/renderer/chunk"
"$JH/bin/javac" --release 25 -proc:none -implicit:none -sourcepath "$OUT/empty" -cp "$CP" -d "$OUT" \
    "$SRC/LodGpu.java" "$SRC/LodFarRefresh.java" "$SRC/LodFarGeometry.java" "$SRC/LodGeometryLeases.java" \
    "$ROOT/tools/LodFarRefreshTest.java"
cd "$OUT"
"$JH/bin/java" --enable-native-access=ALL-UNNAMED -ea -cp "$OUT:$CP" \
    net.minecraft.client.renderer.chunk.LodFarRefreshTest
