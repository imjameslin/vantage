#!/bin/zsh
# Actual manager publication + marker-race regression check, CPU only. Frozen jar supplies unrelated classes.
set -euo pipefail
ROOT="${0:A:h:h}"
JH="$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home"
CLIENT="${LOD_HANDOFF_CLIENT_JAR:-$ROOT/build/client-node-items-a.jar}"
CLIENT="${CLIENT:A}"
CP="$CLIENT"
for LIB in "$ROOT"/mc/libraries/**/*.jar; do CP="$CP:$LIB"; done
OUT="$(mktemp -d "${TMPDIR:-/tmp}/lod-mesh-publication.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes" "$OUT/empty" "$OUT/mutant"
cd "$OUT"
JAVAC=("$JH/bin/javac" -J-Xmx256m -J-XX:ActiveProcessorCount=2 --release 25 -proc:none -implicit:none -sourcepath "$OUT/empty")
"${JAVAC[@]}" -cp "$CP" -d "$OUT/classes" \
  "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodManager.java" \
  "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodGpu.java" \
  "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodMeshHandoff.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodPreparedItems.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodPrepareTiming.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodItemWorker.java" \
  "$ROOT/tools/LodMeshPublicationTest.java"
JAVA=("$JH/bin/java" -Xmx128m -XX:ActiveProcessorCount=2 -ea -Djava.awt.headless=true -Djava.library.path="$OUT/empty")
TEST=net.minecraft.client.renderer.chunk.LodMeshPublicationTest
CLASS=net/minecraft/client/renderer/chunk/LodManager.class
"${JAVA[@]}" -cp "$OUT/classes:$CP" "$TEST" "$OUT/classes/$CLASS" false
"${JAVA[@]}" -Dmc.lod.atomicMeshHandoff=true -cp "$OUT/classes:$CP" "$TEST" "$OUT/classes/$CLASS" true
# Recreate the review finding: move the marker after publication. The compiled control-flow guard must reject it.
python3 - "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodManager.java" "$OUT/mutant/LodManager.java" <<'PY'
from pathlib import Path
import sys
s = Path(sys.argv[1]).read_text()
marker = '        n.meshStamp = requestedStamp;\n'
assert s.count(marker) == 1
s = s.replace(marker, '')
needle = '            if (!this.meshHandoff.offer(n, ready, requestedStamp, bytes)) n.meshStamp = -1L;'
assert s.count(needle) == 1
s = s.replace(needle, needle + '\n            n.meshStamp = requestedStamp;')
Path(sys.argv[2]).write_text(s)
PY
"${JAVAC[@]}" -cp "$OUT/classes:$CP" -d "$OUT/mutant" "$OUT/mutant/LodManager.java"
if "${JAVA[@]}" -cp "$OUT/classes:$CP" "$TEST" "$OUT/mutant/$CLASS" > "$OUT/mutant.log" 2>&1; then
  cat "$OUT/mutant.log"
  print -u2 'FAIL: marker-after-publication mutant escaped the guard'
  exit 1
fi
rg -q 'scheduling marker written before result becomes claimable' "$OUT/mutant.log"
print 'LodMeshPublicationTest: known marker-after-publication mutant rejected'
