#!/bin/zsh
# Stage-2 W7, instant UI transitions (docs/metal/CONTRACT.md 16.W7): headless checks of the patched LoadingOverlay, TitleScreen,
# DownloadedPackSource and Minecraft.doWorldLoad (tools/UiTransitionsTest.java; no game window, no GPU). Runs the suite with the
# default (toggle unset = on) and with -Dmc.ui.instantTransitions=false; each run also flips the toggle at runtime, so both
# modes are exercised either way. Run tools/build-client.sh first.
# Usage: tools/ui-transitions-test.sh [extra JVM args...]
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
mkdir -p "$ROOT/build/ui-transitions-run"
cd "$ROOT/build/ui-transitions-run"   # log4j writes logs/ relative to the working directory
set +e
"$JH/bin/java" -ea "$@" -cp "$CP" "$ROOT/tools/UiTransitionsTest.java"
A=$?
"$JH/bin/java" -ea -Dmc.ui.instantTransitions=false "$@" -cp "$CP" "$ROOT/tools/UiTransitionsTest.java"
B=$?
set -e
if [[ $A -ne 0 || $B -ne 0 ]]; then
  echo "ui-transitions-test: FAILED (default run exit $A, toggle-off run exit $B)"
  exit 1
fi
echo "ui-transitions-test: PASSED (default and -Dmc.ui.instantTransitions=false)"
