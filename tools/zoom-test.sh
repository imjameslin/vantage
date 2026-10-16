#!/bin/zsh
# Zoom key tests (docs/features/ZOOM.md), headless: easing / frame-rate independence / clamping / scroll steps /
# reset / mouse sensitivity / zoom-aware distance scale in ZoomMath, plus hook wiring in build/client.jar (bytecode
# scan) when it exists. No GPU, window or game instance.
#
# ZoomMath.java is compiled on its own with an empty classpath first: it must not depend on game classes.
#
# Usage: tools/zoom-test.sh [extra JVM args...]
set -e
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
JAR="$ROOT/build/client.jar"
OUT="$ROOT/build/zoom-test"
rm -rf "$OUT/classes"; mkdir -p "$OUT/classes"
"$JH/bin/javac" --release 25 -g -nowarn -encoding UTF-8 -cp "" -d "$OUT/classes" \
  "$ROOT/client/src/net/minecraft/client/zoom/ZoomMath.java"
ARGS=()
if [[ -f "$JAR" ]]; then
  ARGS=("$JAR")
else
  echo "note: $JAR missing, hook wiring checks skipped (run tools/build-client.sh first)" >&2
fi
cd "$OUT"
exec "$JH/bin/java" "$@" -cp "$OUT/classes" "$ROOT/tools/ZoomTest.java" $ARGS
