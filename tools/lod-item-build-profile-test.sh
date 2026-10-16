#!/bin/zsh
# Pure CPU accounting/source-wiring test. Does not build the client or load any native/game code.
set -euo pipefail
ROOT="${0:A:h:h}"
JH="${LOD_PROFILE_JAVA_HOME:-$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home}"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/lod-item-build-profile.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes" "$OUT/empty"
"$JH/bin/javac" -J-Xmx128m -J-XX:ActiveProcessorCount=2 --release 25 -proc:none -implicit:none \
    -sourcepath "$OUT/empty" -cp "$OUT/empty" -d "$OUT/classes" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodItemBuildProfile.java" \
    "$ROOT/tools/LodItemBuildProfileTest.java"
JAVA=("$JH/bin/java" -Xmx64m -XX:ActiveProcessorCount=2 -ea -cp "$OUT/classes")
TEST=net.minecraft.client.renderer.chunk.LodItemBuildProfileTest
SOURCE="$ROOT/client/src/net/minecraft/client/renderer/chunk/LodGpu.java"
"${JAVA[@]}" "$TEST" false "$SOURCE" > "$OUT/off.log"
"${JAVA[@]}" -Dmc.lod.itemBuildProfile=false "$TEST" false "$SOURCE" > "$OUT/false.log"
"${JAVA[@]}" -Dmc.lod.itemBuildProfile=true "$TEST" true "$SOURCE" > "$OUT/on.log"
python3 - "$OUT" <<'PY'
from pathlib import Path
import json, sys
for p in (Path(sys.argv[1]) / name for name in ('off.log', 'false.log', 'on.log')):
    lines = p.read_text().splitlines()
    print(lines[0])
    value = json.loads(lines[1])
    assert value['phaseOrder'] == ['selectionFades', 'prepareAppend', 'sectionRing', 'itemRing']
    assert value['modeEncoding'] == 'items*2+groups'
    for mode in value['modes']:
        assert mode['failed']['attempts'] == 0
        record = mode['completed']
        assert record['attempts'] == (1 if mode['mode'] == 3 else 0)
        assert sum(record['phaseNs']) == record['totalNs']
    record = value['modes'][3]['completed']
    assert record['phaseNs'] == [10, 40, 30, 20]
    assert record['preparedSectionPayloadBytes'] == record['sectionCompletedCopyBytes'] == 288
    assert record['preparedClusterPayloadBytes'] == record['itemCompletedCopyBytes'] == 1920
print('LodItemBuildProfile JSON: 3 snapshots checked')
PY
