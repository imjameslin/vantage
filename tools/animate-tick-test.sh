#!/bin/zsh
# R5-D (docs/metal/CONTRACT.md 16.R5-D): end-to-end parity of ClientLevel's animateTick loop, headless (no game, no GPU).
# tools/AnimateTickParityTest.java runs vanilla's loop and ClientLevel.fastAnimateTick on the same worlds and seeds
# in one JVM and compares every tick (particle/sound call hash, level RNG seed after, animateRandom state after). Three
# JVMs: default (-Dmc.fastAnimateTick=true), batched RNG off (-Dmc.fastAnimateTick.batchRng=false), and verify mode
# (-Dmc.fastAnimateTick.verify=true, which must log no mismatch). Prints each run's PARITY and COST lines.
# Run tools/build-client.sh first. Extra args go to every JVM (e.g. -Danimate.ticks=1000).
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
OUT="$ROOT/build/animate-tick-run"
mkdir -p "$OUT"
cd "$OUT"   # log4j writes logs/ relative to the working directory
rc=0
run() {
  local name=$1; shift
  if ! "$JH/bin/java" -ea -XX:+UseZGC -Dmc.fastAnimateTick=true "$@" -cp "$CP" "$ROOT/tools/AnimateTickParityTest.java" > "$OUT/$name.txt" 2>&1; then
    rc=1
  fi
  echo "$name: $(grep -h 'calibration:\|PARITY\|COST\|MISMATCH' "$OUT/$name.txt" | sed 's/^.*STDOUT\]: //' | tr '\n' ' ')"
}
run default "$@"
run nobatch -Dmc.fastAnimateTick.batchRng=false "$@"
run verify -Dmc.fastAnimateTick.verify=true "$@"
if grep -q "mc.fastAnimateTick.verify:" "$OUT/verify.txt"; then
  echo "ANIMATE TICK VERIFY FAILED:"
  grep "mc.fastAnimateTick.verify:" "$OUT/verify.txt" | head -10
  rc=1
fi
for n in default nobatch verify; do
  grep -q "ANIMATE TICK PARITY PASSED" "$OUT/$n.txt" || { echo "$n: no PASSED line (see $OUT/$n.txt)"; rc=1; }
done
(( rc == 0 )) && echo "ANIMATE TICK PARITY: vanilla == fast in all three runs, verify clean"
exit $rc
