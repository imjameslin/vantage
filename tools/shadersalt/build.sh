#!/bin/zsh
# Build build/shadersalt.jar (tools/shadersalt/shadersalt/SaltAgent.java; measurement only, see its class comment).
# usage: lockf -k /tmp/mc-gpu.lock zsh tools/shadersalt/build.sh [out jar]
#        then --jvm=-javaagent:$PWD/build/shadersalt.jar=<salt>[,time] on launch.py / warm-shaders.sh
set -e
R="${0:A:h:h:h}"
OUT="${1:-$R/build/shadersalt.jar}"
JH="$R/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
T=$(mktemp -d "$R/build/shadersalt-classes.XXXX")
"$JH/bin/javac" --release 25 -nowarn -d "$T" "$R"/tools/shadersalt/shadersalt/*.java
printf 'Premain-Class: shadersalt.SaltAgent\n' > "$T.mf"
"$JH/bin/jar" --create --file "$OUT" --manifest "$T.mf" -C "$T" .
rm -rf "$T" "$T.mf"
echo "built $OUT"
