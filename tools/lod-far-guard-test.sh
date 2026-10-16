#!/bin/zsh
# Compile current guard/camera sources; client.jar supplies only the actual Source record.
# No game entry point, native library, GPU, renderer initialization or shared build output.
set -euo pipefail
ROOT="${0:A:h:h}"
JH="${LOD_GUARD_JAVA_HOME:-$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home}"
JOML="${LOD_GUARD_JOML_JAR:-$ROOT/mc/libraries/org/joml/joml/1.10.9/joml-1.10.9.jar}"
CLIENT="${LOD_GUARD_CLIENT_JAR:-$ROOT/build/client.jar}"
[[ -x "$JH/bin/javac" && -x "$JH/bin/java" && -f "$JOML" && -f "$CLIENT" ]] || {
    print -u2 "Need JDK 25, JOML and existing client.jar; set LOD_GUARD_{JAVA_HOME,JOML_JAR,CLIENT_JAR}"
    exit 2
}
OUT="$(mktemp -d "${TMPDIR:-/tmp}/lod-far-guard.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
ulimit -t 30
"$JH/bin/javac" -J-Xmx128m -J-XX:ActiveProcessorCount=2 --release 25 -proc:none \
    -Xlint:all,-classfile -Werror -cp "$JOML:$CLIENT" -sourcepath "$OUT" -d "$OUT" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodFarCamera.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodFarGuard.java" \
    "$ROOT/tools/LodFarGuardTest.java"
"$JH/bin/java" -Xmx96m -XX:ActiveProcessorCount=2 -ea -Djava.awt.headless=true -Djoml.nounsafe=true \
    -Djava.library.path="$OUT" -cp "$OUT:$JOML:$CLIENT" net.minecraft.client.renderer.chunk.LodFarGuardTest
