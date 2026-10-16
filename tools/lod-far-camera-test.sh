#!/bin/zsh
# Only these two Java sources + JOML. No client.jar, native libraries, GPU or game.
set -euo pipefail
ROOT="${0:A:h:h}"
JH="${LOD_CAMERA_JAVA_HOME:-$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home}"
JOML="${LOD_CAMERA_JOML_JAR:-$ROOT/mc/libraries/org/joml/joml/1.10.9/joml-1.10.9.jar}"
[[ -x "$JH/bin/javac" && -x "$JH/bin/java" && -f "$JOML" ]] || {
    print -u2 "Need a JDK and JOML; set LOD_CAMERA_JAVA_HOME / LOD_CAMERA_JOML_JAR if necessary"
    exit 2
}
OUT="$(mktemp -d "${TMPDIR:-/tmp}/lod-far-camera.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
# Bundled JOML has type annotations on old-version classfiles; retain all source lint checks.
"$JH/bin/javac" --release 25 -proc:none -Xlint:all,-classfile -Werror -cp "$JOML" -sourcepath "$OUT" -d "$OUT" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodFarCamera.java" \
    "$ROOT/tools/LodFarCameraTest.java"
"$JH/bin/java" -ea -Djava.awt.headless=true -Djoml.nounsafe=true -cp "$OUT:$JOML" net.minecraft.client.renderer.chunk.LodFarCameraTest
