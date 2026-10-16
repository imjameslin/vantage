#!/bin/zsh
set -eu
ROOT="${0:A:h:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
CLIENT=${1:-$ROOT/build/client-farsight-direct-q1.jar}
CP="$ROOT/build/bench-classes:$CLIENT"
for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' | sort)"}; do CP="$CP:$j"; done
"$JH/bin/java" --enable-native-access=ALL-UNNAMED -cp "$CP" "$ROOT/tools/LodSnapshotTest.java" "$CLIENT"
