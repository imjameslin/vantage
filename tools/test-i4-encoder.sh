#!/bin/zsh
# I4 (java-encoder) headless tests: StreamWriter, MetalCommandEncoder, MetalRenderPass, MetalSurface.
# No GPU, window or dylib needed (fake mcmtl_submit / surface calls, streams decoded back in Java).
# Run tools/build-client.sh first.
#
# Test doubles (client/test/i4-encoder/doubles) stand in for I3 classes that are still skeletons in this
# tree (detected by their "TODO(I3)" markers) and always for MetalTransientMemory (the real one allocates
# MTLBuffers). Once I3 is merged, the real MetalTimeline/MetalTexture/... are tested instead.
#
# Usage: tools/test-i4-encoder.sh [extra JVM args...]
set -e
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
JAR="$ROOT/build/client.jar"
if [[ ! -f "$JAR" ]]; then
  echo "missing $JAR: run tools/build-client.sh first" >&2
  exit 2
fi
CP="$JAR"
for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' | sort)"}; do
  if [[ "$j" == *natives-macos.jar || "$j" == *osx-x86_64.jar ]]; then continue; fi
  CP="$CP:$j"
done
TEST="$ROOT/client/test/i4-encoder"
OUT="$ROOT/build/i4-test"
DOUBLES_OUT="$ROOT/build/i4-doubles"
rm -rf "$OUT" "$DOUBLES_OUT"
mkdir -p "$OUT" "$DOUBLES_OUT" "$ROOT/build/i4-natives"

typeset -a DOUBLES
for d in "$TEST"/doubles/com/mojang/renderpearl/backend/metal/*.java; do
  name="${d:t}"
  real="$ROOT/client/src/com/mojang/renderpearl/backend/metal/$name"
  if [[ "$name" == "MetalTransientMemory.java" ]] || grep -q "TODO(I3)" "$real"; then
    DOUBLES+=("$d")
  fi
done
echo "test doubles: ${(j:, :)${DOUBLES[@]:t:r}}"
if (( ${#DOUBLES} )); then
  "$JH/bin/javac" --release 25 -g -nowarn -proc:none -encoding UTF-8 -cp "$CP" -d "$DOUBLES_OUT" $DOUBLES
fi
TCP="$DOUBLES_OUT:$CP"
"$JH/bin/javac" --release 25 -g -proc:none -encoding UTF-8 -Xlint:none -cp "$TCP" -d "$OUT" "$TEST"/src/com/mojang/renderpearl/backend/metal/*.java
cd "$OUT"   # log4j writes logs/ relative to the working directory
exec "$JH/bin/java" --enable-native-access=ALL-UNNAMED -XX:+UseZGC \
  -Dorg.lwjgl.system.SharedLibraryExtractPath="$ROOT/build/i4-natives" \
  "$@" -cp "$OUT:$TCP" com.mojang.renderpearl.backend.metal.I4EncoderTest
