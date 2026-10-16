#!/bin/zsh
# Fabric compatibility, headless (docs/metal/CONTRACT.md 16.FAB, results/FABRIC.md): ModCompat (superseded rendering mods
# found and named), the method merge in build/client.jar (every changed vanilla method is listed as ours; the methods mods
# hit are vanilla bytecode), and, when the test mods are present (mc/fabric/mods), the static mixin audit: no mixin of
# those mods may resolve in vanilla but not in our jar (BROKEN). Differences in injection-point counts inside methods we
# patch are printed for review, not failed. No GPU, window or game.
#
# usage: tools/fabric-compat-test.sh [client.jar]
set -e
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
JAR="${1:-$ROOT/build/client.jar}"; JAR="${JAR:A}"
VANILLA="$ROOT/mc/versions/26.3/26.3.jar"
OUT="$ROOT/build/fabric-compat-test"
CP="$JAR"; for j in ${(f)"$(find "$ROOT/mc/libraries" -name '*.jar' ! -name '*natives*')"}; do CP="$CP:$j"; done
rm -rf "$OUT"; mkdir -p "$OUT/classes"
"$JH/bin/javac" --release 25 -nowarn -encoding UTF-8 -cp "$CP" -d "$OUT/classes" "$ROOT/tools/FabricCompatTest.java" "$ROOT/tools/MixinAudit.java"
"$JH/bin/java" -cp "$OUT/classes:$CP" FabricCompatTest "$VANILLA" "$JAR" "$ROOT/build/method-merge.txt"
if [[ -d "$ROOT/mc/fabric/mods" ]]; then
  set +e
  "$JH/bin/java" -cp "$OUT/classes" MixinAudit "$VANILLA" "$JAR" "$ROOT/mc/fabric/mods" > "$OUT/mixin-audit.txt" 2>&1
  set -e
  grep -E "^PROBLEM|DIFF|CHECK|BROKEN|^mixins on|^MIXIN" "$OUT/mixin-audit.txt" || true
  if grep -q "BROKEN" "$OUT/mixin-audit.txt"; then
    echo "FAIL: a test mod's mixin resolves in vanilla but not in $JAR ($OUT/mixin-audit.txt)"
    exit 1
  fi
  echo "mixin audit: no BROKEN injection ($OUT/mixin-audit.txt)"
fi
echo "fabric-compat: PASS"
