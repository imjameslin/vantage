#!/bin/zsh
# Stage 3 round 1b diagnostics: offline LodGreedy analysis of slab-layers dumped with -Dmc.lod.dumpDir=<dir>.
# usage: tools/lod/greedy-tool.sh <dump dir> [tol...]
# Compiles the working copy's LodGreedy.java over build/client.jar (LOD_TOOL_JAR=<jar> picks another), so merge rule changes
# can be evaluated without a client build.
set -e
ROOT="${0:A:h:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
JAR="${LOD_TOOL_JAR:-$ROOT/build/client.jar}"
CP="$JAR"
for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' | sort)"}; do
  if [[ "$j" == *natives-macos.jar || "$j" == *osx-x86_64.jar ]]; then continue; fi
  CP="$CP:$j"
done
OUT="$ROOT/build/lod-greedy-tool"
rm -rf "$OUT"; mkdir -p "$OUT"
"$JH/bin/javac" --release 25 -nowarn -proc:none -cp "$CP" -d "$OUT" "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodGreedy.java"
exec "$JH/bin/java" -cp "$OUT:$CP" "$ROOT/tools/LodGreedyTool.java" "$@"
