#!/bin/zsh
# R5-D (docs/metal/CONTRACT.md 16.R5-D): headless checks for the exact client-tick fast paths (no game, no GPU):
# ClientLevel's batched animateTick RNG vs LegacyRandomSource, the AMBIENT_PARTICLES skip plan against the real
# EnvironmentAttributeSystem over the vanilla biome / dimension-type / timeline registries, lookup identities.
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
mkdir -p "$ROOT/build/client-tick-run"
cd "$ROOT/build/client-tick-run"   # log4j writes logs/ relative to the working directory
exec "$JH/bin/java" -ea "$@" -cp "$CP" "$ROOT/tools/ClientTickTest.java"
