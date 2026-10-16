#!/bin/zsh
# Late frame throttle tests (docs/metal/CONTRACT.md 16.R5-A): compiles tools/LateThrottleTest.java against build/client.jar
# (same runtime package as the backend, for the package-private seams) and runs it twice, headless (no dylib, no GPU):
#   1. -Dmcmetal.lateThrottle=false (the old path: submit() waits for frame N-1; the toggle defaults to on since round 5),
#   2. -Dmcmetal.lateThrottle=true -Dmcmetal.lateThrottle.strict=true (late throttle; a record while the wait is armed throws).
# Both runs must pass and print the same STREAM-DIGEST (identical committed command streams and CPU writes).
# Run tools/build-client.sh first.
#
# Usage: tools/late-throttle-test.sh [extra JVM args...]
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
OUT="$ROOT/build/late-throttle-tests"
rm -rf "$OUT/classes"; mkdir -p "$OUT/classes" "$OUT/natives"
"$JH/bin/javac" --release 25 -g -nowarn -proc:none -encoding UTF-8 -cp "$CP" -d "$OUT/classes" "$ROOT/tools/LateThrottleTest.java"
cd "$OUT"   # log4j writes logs/ relative to the working directory
run() {
  "$JH/bin/java" --enable-native-access=ALL-UNNAMED -XX:+UseZGC -ea \
    -Dorg.lwjgl.system.SharedLibraryExtractPath="$OUT/natives" \
    "$@" -cp "$OUT/classes:$CP" com.mojang.renderpearl.backend.metal.LateThrottleTest
}
set +e
run -Dmcmetal.lateThrottle=false "$@" | tee "$OUT/off.log"
OFF=${pipestatus[1]}
run -Dmcmetal.lateThrottle=true -Dmcmetal.lateThrottle.strict=true -Dmcmetal.lateThrottle.logTrips=0 "$@" | tee "$OUT/on.log"
ON=${pipestatus[1]}
set -e
D_OFF=$(grep -o 'STREAM-DIGEST [0-9a-f]*' "$OUT/off.log" || true)
D_ON=$(grep -o 'STREAM-DIGEST [0-9a-f]*' "$OUT/on.log" || true)
echo
echo "off: exit $OFF, ${D_OFF:-no digest}"
echo "on:  exit $ON, ${D_ON:-no digest}"
if [[ $OFF -ne 0 || $ON -ne 0 ]]; then
  echo "late-throttle-test: FAILED (test failures)"
  exit 1
fi
if [[ -z "$D_OFF" || "$D_OFF" != "$D_ON" ]]; then
  echo "late-throttle-test: FAILED (committed streams differ between the old path and the late throttle)"
  exit 1
fi
echo "late-throttle-test: PASSED (both modes, identical committed streams)"
