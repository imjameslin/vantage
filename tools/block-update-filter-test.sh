#!/bin/zsh
# Stage-2 W11 (docs/metal/CONTRACT.md 16.S2-W11): headless check of the render-equivalent block-update filter
# (tools/BlockUpdateFilterTest.java) over every state pair of every block with the real model groups. No game, no GPU.
# Run tools/build-client.sh first.
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
mkdir -p "$ROOT/build/block-update-filter-run"
cd "$ROOT/build/block-update-filter-run"   # log4j writes logs/ relative to the working directory
exec "$JH/bin/java" -ea "$@" -cp "$CP" "$ROOT/tools/BlockUpdateFilterTest.java"
