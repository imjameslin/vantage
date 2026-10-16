#!/bin/zsh
# Headless production composite correctness only; caller owns the serialized GPU lane.
# Uses an already-built client.jar; never builds the client/native library or starts a game.
# Usage: tools/lod-reuse-composite-test.sh [extra JVM args...]
set -euo pipefail
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
JAR="$ROOT/build/client.jar"
if [[ ! -f "$JAR" ]]; then
  echo "missing $JAR: build the client before running this test" >&2
  exit 2
fi
CP="$JAR"
for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' | sort)"}; do
  if [[ "$j" == *natives-macos.jar || "$j" == *osx-x86_64.jar ]]; then continue; fi
  CP="$CP:$j"
done
EXTRACT="$ROOT/build/lod-reuse-composite-natives"
mkdir -p "$EXTRACT" "$ROOT/build/lod-reuse-composite-run"
cd "$ROOT/build/lod-reuse-composite-run"
export MTL_DEBUG_LAYER=1
# Includes Java source-launch compilation, pipeline compilation, readback, and cleanup.
exec python3 - "$JH/bin/java" --enable-native-access=ALL-UNNAMED -XX:+UseZGC -ea \
  -Dorg.lwjgl.system.SharedLibraryExtractPath="$EXTRACT" \
  "$@" -cp "$CP" "$ROOT/tools/LodReuseCompositeTest.java" <<'PY'
import subprocess
import sys

try:
    sys.exit(subprocess.run(sys.argv[1:], timeout=60).returncode)
except subprocess.TimeoutExpired:
    print("FAIL: LOD reuse composite test exceeded 60 seconds", file=sys.stderr)
    sys.exit(124)
PY
