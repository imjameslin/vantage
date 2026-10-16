#!/bin/zsh
# Focused CPU compilation/tests against an existing frozen client; no full build/native/GPU/game.
set -euo pipefail
ROOT="${0:A:h:h}"
JH="${LOD_GROUPS_JAVA_HOME:-$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home}"
CLIENT="${LOD_GROUPS_CLIENT_JAR:-$ROOT/build/client-items-cache-a.jar}"
JH="${JH:A}"
CLIENT="${CLIENT:A}"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/lod-group-lookup.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
CP="$CLIENT"
for LIB in "$ROOT"/mc/libraries/**/*.jar; do CP="$CP:$LIB"; done
mkdir -p "$OUT/classes" "$OUT/empty"
cd "$OUT"
# Reflection exercises real group/cached append methods; OFF uses the independent existing scalar reference.
# Check their wiring without extracting the original scalar loop from GPU-bearing buildItems.
python3 - "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodGpu.java" <<'PY'
from pathlib import Path
import sys
s = Path(sys.argv[1]).read_text()
b = s[s.index('    private void buildItems('):s.index('    private void appendCachedSection(')]
assert b.count('this.buildCacheGroups = cacheGroups;') == 1 and b.count('cacheGroups') == 1
assert b.index('this.buildCacheGroups = cacheGroups;') < b.index('for (GroupItems g : this.groups)')
assert 'appendScalarSection' not in s, 'keep the original scalar loop inline'
assert b.count('this.addSectionItem(s);') == 1
assert b.count('this.appendCachedSection(s, fading, n.level);') == 1
g = s[s.index('    private GroupItems group('):s.index('    private Job job(')]
assert 'cacheGroups' not in g, 'lookup must use the build sample, never reread the volatile flag'
assert 'int groupMode = (cachedItems ? 2 : 0) | (this.buildCacheGroups ? 1 : 0);' in s
assert 'this.groupModeBuilds[groupMode]++;' in s and 'this.groupModeBuildNanos[groupMode] += elapsed;' in s
c = s[s.index('    void close()'):s.index('    private void releaseGroupLookups()')]
assert 'this.releaseGroupLookups();' in c, 'owner close must drop CPU lookup references'
print('LodGroupLookupTest: build-mode/append/counter/close wiring passed', flush=True)
PY
"$JH/bin/javac" -J-Xmx256m -J-XX:ActiveProcessorCount=2 --release 25 -proc:none -implicit:none \
    -sourcepath "$OUT/empty" -cp "$CP" -d "$OUT/classes" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodGpu.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodSectionItems.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodNodeItems.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodManager.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodMeshHandoff.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodPreparedItems.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodPrepareTiming.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodItemWorker.java" \
    "$ROOT/tools/LodGroupLookupTest.java" "$ROOT/tools/LodSectionItemsTest.java"
JAVA=("$JH/bin/java" -Xmx128m -XX:ActiveProcessorCount=2 -ea -Djava.awt.headless=true -Djava.library.path="$OUT/empty")
TEST=net.minecraft.client.renderer.chunk.LodGroupLookupTest
"${JAVA[@]}" -cp "$OUT/classes:$CP" "$TEST" false false
"${JAVA[@]}" -Dmc.lod.cacheGroups=true -cp "$OUT/classes:$CP" "$TEST" true false
"${JAVA[@]}" -Dmc.lod.cacheGroups.diagnostics=true -cp "$OUT/classes:$CP" "$TEST" false true
"${JAVA[@]}" -Dmc.lod.cacheGroups=true -Dmc.lod.cacheGroups.diagnostics=true -Dmc.lod.diag=levels \
    -cp "$OUT/classes:$CP" "$TEST" true true
# Keep the existing item-cache reflection/API tests intact too; do not edit that test or its runner.
"${JAVA[@]}" -cp "$OUT/classes:$CP" net.minecraft.client.renderer.chunk.LodSectionItemsTest false
"${JAVA[@]}" -Dmc.lod.cacheItems=true -Dmc.lod.diag=levels -cp "$OUT/classes:$CP" \
    net.minecraft.client.renderer.chunk.LodSectionItemsTest true
