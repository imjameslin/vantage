#!/bin/zsh
# W1 tests (HUD vignette no-op skip, -Dmc.hud.vignetteSkipNoop; docs/stage2/STAGE2-PLAN.md W1): see
# tools/HudVignetteTest.java. Headless: quantization boundary, world-border path, VIGNETTE pipeline facts and the
# resource-pack shader guard, a differential test of HudVignetteSkip.blitWithoutDraw against the vanilla blit through the
# real GuiGraphicsExtractor / GuiRenderState (layering, sort, batching), StagedVertexBuffer, a bytecode comparison of
# build/client.jar's Hud with the verbatim vanilla Hud.java compiled here by the same javac (including the skip block's
# exact instructions and arguments), the patched Hud.extractVignette run end to end (toggle off / on / resource-pack
# shaders, world-border and camera cases), the shader guard against the sources the game's own resource loading
# produces (vanilla pack + this game dir's asset index, resource packs on top), and (unless --no-gpu) the Metal check: VIGNETTE with colour 0 over random content == no draw, offscreen RGBA8 and through the present-fusion BGRA8
# drawable (offscreen CAMetalLayer, no window). Metal API validation is on.
#
# Usage: lockf -k /tmp/mc-gpu.lock zsh tools/build-client.sh
#        lockf -k /tmp/mc-gpu.lock zsh tools/hud-vignette-test.sh [--no-gpu] [extra JVM args...]
set -e
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
JAR="$ROOT/build/client.jar"
VANILLA="$ROOT/mc/versions/26.3/26.3.jar"
CLASSES="$ROOT/build/client-classes"
OUT="$ROOT/build/hud-vignette-test"
if [[ ! -f "$JAR" || ! -d "$CLASSES" ]]; then
  echo "missing $JAR / $CLASSES: run tools/build-client.sh first" >&2
  exit 2
fi
TEST_ARGS=()
JVM_ARGS=()
for a in "$@"; do
  if [[ "$a" == "--no-gpu" ]]; then TEST_ARGS+=("$a"); else JVM_ARGS+=("$a"); fi
done

LIBS=""
for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' | sort)"}; do
  if [[ "$j" == *natives-macos.jar || "$j" == *osx-x86_64.jar ]]; then continue; fi
  LIBS="$LIBS:$j"
done

# The verbatim vanilla Hud, compiled exactly like tools/build-client.sh compiles client/src (same javac, flags and
# classes on the classpath), for the bytecode comparison.
rm -rf "$OUT/vanilla-hud"; mkdir -p "$OUT/vanilla-hud" "$OUT/run" "$OUT/natives"
"$JH/bin/javac" --release 25 -g -nowarn -proc:none -Xlint:none -encoding UTF-8 -implicit:none \
  -cp "$CLASSES:$VANILLA$LIBS" -d "$OUT/vanilla-hud" "$ROOT/src/vanilla/net/minecraft/client/gui/Hud.java"

cd "$OUT/run"   # log4j writes logs/ relative to the working directory
export MTL_DEBUG_LAYER=${MTL_DEBUG_LAYER:-1}
export MCMTL_DECODER_STATS=${MCMTL_DECODER_STATS:-6}   # the decoder logs present-fusion counts (fused frames) every 6 frames
set +e
"$JH/bin/java" --enable-native-access=ALL-UNNAMED -XX:+UseZGC -ea -Djava.awt.headless=true \
  -Dorg.lwjgl.system.SharedLibraryExtractPath="$OUT/natives" -Dw1.mcDir="$ROOT/mc" $JVM_ARGS \
  -cp "$JAR$LIBS" "$ROOT/tools/HudVignetteTest.java" "$JAR" "$OUT/vanilla-hud" $TEST_ARGS > "$OUT/log.txt" 2>&1
rc=$?
set -e
grep -v "mcmtl decoder stats\|mcmtl present (\|mcmtl terrain ICB\|^\s*$" "$OUT/log.txt" | grep -v "^20[0-9][0-9]-.*mcmtl" || true
if grep -q "W1 FUSED SCENE" "$OUT/log.txt"; then
  fused=$(grep -o "mcmtl present fusion (mode [0-9]*): fused [0-9]*" "$OUT/log.txt" | awk '{s += $NF} END {print s + 0}')
  echo "present fusion (decoder statistics): $fused fused frames"
  if (( fused < 1 )); then
    echo "FAIL: no frame of the fused scene was rendered into the drawable (BGRA8 path untested)"
    rc=1
  fi
fi
echo "log: $OUT/log.txt"
exit $rc
