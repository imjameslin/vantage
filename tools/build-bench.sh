#!/bin/zsh
# Compile the benchmark agent against the vanilla client jar and package it.
set -e
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
CP="$ROOT/mc/versions/26.3/26.3.jar"
for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' ! -name '*natives*')"}; do CP="$CP:$j"; done
rm -rf "$ROOT/build/bench-classes"; mkdir -p "$ROOT/build/bench-classes"
"$JH/bin/javac" --release 25 -nowarn -cp "$CP" -d "$ROOT/build/bench-classes" ${(f)"$(find "$ROOT/bench/src" -name '*.java')"}
printf 'Premain-Class: bench.BenchAgent\n' > "$ROOT/build/bench-manifest.txt"
"$JH/bin/jar" --create --file "$ROOT/build/bench.jar" --manifest "$ROOT/build/bench-manifest.txt" -C "$ROOT/build/bench-classes" .
echo "built $ROOT/build/bench.jar"
