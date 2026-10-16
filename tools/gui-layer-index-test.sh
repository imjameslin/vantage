#!/bin/zsh
# Round 6 G1 (GUI layering index, -Dmc.gui.layerIndex; docs/metal/CONTRACT.md 16.G1): tools/GuiLayerIndexTest.java against
# build/client.jar. Headless, no GPU: the index against vanilla's linear scan, and GuiRenderState's layer tree with the
# index off / on / toggled mid-frame, plus the F3 chart workload. usage: zsh tools/gui-layer-index-test.sh [JVM args...]
set -e
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
JAR="$ROOT/build/client.jar"
[[ -f "$JAR" ]] || { echo "missing $JAR: run tools/build-client.sh first" >&2; exit 2; }
LIBS=""
for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' | sort)"}; do
  if [[ "$j" == *natives-macos.jar || "$j" == *osx-x86_64.jar ]]; then continue; fi
  LIBS="$LIBS:$j"
done
"$JH/bin/java" -ea -Djava.awt.headless=true "$@" -cp "$JAR$LIBS" "$ROOT/tools/GuiLayerIndexTest.java"
