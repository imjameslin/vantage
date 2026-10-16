#!/bin/zsh
# Quick compile check of client/src (no jar, no native): javac into build/javac-check, errors only.
# usage: zsh tools/lod-javac-check.sh
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
CP="$ROOT/mc/versions/26.3/26.3.jar"
for j in ${(f)"$(find "$ROOT/mc/libraries/" -name '*.jar' ! -name '*natives*')"}; do CP="$CP:$j"; done
OUT="$ROOT/build/javac-check"
rm -rf "$OUT"; mkdir -p "$OUT"
SOURCES=(${(f)"$(find "$ROOT/client/src" -name '*.java')"})
"$JH/bin/javac" --release 25 -nowarn -proc:none -Xlint:none -encoding UTF-8 -cp "$CP" -d "$OUT" $SOURCES 2>&1 | grep -v '^Note' | head -80
