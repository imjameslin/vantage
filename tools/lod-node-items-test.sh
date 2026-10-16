#!/bin/zsh
# Focused CPU compilation only, against an existing jar. No native/full-client build, GPU or game.
set -euo pipefail
ROOT="${0:A:h:h}"
JH="${LOD_NODES_JAVA_HOME:-$ROOT/mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home}"
CLIENT="${LOD_NODES_CLIENT_JAR:-$ROOT/build/client.jar}"
JH="${JH:A}"
CLIENT="${CLIENT:A}"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/lod-node-items.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
CP="$CLIENT"
for LIB in "$ROOT"/mc/libraries/**/*.jar; do CP="$CP:$LIB"; done
mkdir -p "$OUT/classes" "$OUT/empty"
cd "$OUT"
python3 - "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodGpu.java" <<'PY'
from pathlib import Path
import sys
s=Path(sys.argv[1]).read_text()
b=s[s.index('    private void buildItems('):s.index('    private void appendCachedSection(')]
assert b.count('this.buildCacheNodeItems = cacheNodeItems && cachedItems;')==1
assert b.count('cacheNodeItems')==1, 'sample requested mode once per build'
assert b.index('this.buildCacheNodeItems =')<b.index('for (GroupItems g : this.groups)')
assert b.index('boolean fading = this.fades.containsKey(n);')<b.index('this.appendCachedNode(gs, fading, n.level)')<b.index('for (GpuSection s : gs)')
assert b.count('this.appendCachedSection(s, fading, n.level);')==1 and b.count('this.addSectionItem(s);')==1
a=s[s.index('    private boolean appendCachedNode('):s.index('    private void releaseNodeItems(')]
assert 'cacheNodeItems' not in a and 'cacheGroups' not in a and 'System.nanoTime' not in a
assert a.index('LodNodeItems.chargeFor(')<a.index('new LodSectionItems(')<a.index('LodNodeItems.build(')
assert a.index('if (hit) this.nodeItemHits++;')>a.index('this.nodeItemReplayedClusters += packet.clusterCount;')
f=s[s.index('    void free('):s.index('    private void drain',s.index('    void free('))] if '    private void drain' in s[s.index('    void free('):] else s[s.index('    void free('):s.index('    private void evict',s.index('    void free('))]
assert f.index('this.releaseNodeItems(old);')<f.index('if (old == null)')<f.index('this.geometryLeases.retire()')
c=s[s.index('    void close()'):s.index('    private void releaseGroupLookups()')]
assert c.index('this.releaseAllNodeItems();')<c.index('this.geometryLeases.close();')
failed=s[s.index('            } catch (RuntimeException | Error error)'):s.index('            long elapsed =',s.index('            } catch (RuntimeException | Error error)'))]
assert 'this.releaseAllNodeItems();' in failed
assert 'int nodeMode = this.buildCacheNodeItems ? 1 : 0;' in s
assert 'this.nodeModeBuilds[nodeMode]++;' in s and 'this.nodeModeBuildNanos[nodeMode] += elapsed;' in s
assert 'int groupMode = (cachedItems ? 2 : 0) | (this.buildCacheGroups ? 1 : 0);' in s
stats=s[s.index('    String statsJson()'):s.index('    String statsJson()')+4200]
for name in ('nodeItems','enabled','buildsByMode','buildNsByMode','packetBuilds','packetHits','replayedClusters','payloadBytes','budgetBytes','fallbacks'):
    assert '\\"'+name+'\\"' in stats, name
print('LodNodeItemsTest: mode/preflight/fallback/release/stats wiring passed',flush=True)
PY
"$JH/bin/javac" -J-Xmx256m -J-XX:ActiveProcessorCount=2 --release 25 -proc:none -implicit:none \
    -sourcepath "$OUT/empty" -cp "$CP" -d "$OUT/classes" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodGpu.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodManager.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodMeshHandoff.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodPreparedItems.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodPrepareTiming.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodItemWorker.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodNodeItems.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodSectionItems.java" \
    "$ROOT/client/src/net/minecraft/client/renderer/chunk/LodItemBuildProfile.java" \
    "$ROOT/tools/LodNodeItemsTest.java" "$ROOT/tools/LodSectionItemsTest.java" "$ROOT/tools/LodGroupLookupTest.java"
JAVA=("$JH/bin/java" -Xmx128m -XX:ActiveProcessorCount=2 -ea -Djava.awt.headless=true -Djava.library.path="$OUT/empty")
"${JAVA[@]}" -cp "$OUT/classes:$CP" net.minecraft.client.renderer.chunk.LodNodeItemsTest false
"${JAVA[@]}" -Dmc.lod.cacheNodeItems=true -Dmc.lod.diag=levels -Dmc.lod.cacheGroups.diagnostics=true \
    -cp "$OUT/classes:$CP" net.minecraft.client.renderer.chunk.LodNodeItemsTest true
# Existing independent item/group regression checks, using these same freshly compiled production classes.
"${JAVA[@]}" -cp "$OUT/classes:$CP" net.minecraft.client.renderer.chunk.LodGroupLookupTest false false
"${JAVA[@]}" -Dmc.lod.cacheGroups=true -Dmc.lod.cacheGroups.diagnostics=true -Dmc.lod.diag=levels \
    -cp "$OUT/classes:$CP" net.minecraft.client.renderer.chunk.LodGroupLookupTest true true
"${JAVA[@]}" -cp "$OUT/classes:$CP" net.minecraft.client.renderer.chunk.LodSectionItemsTest false
"${JAVA[@]}" -Dmc.lod.cacheItems=true -Dmc.lod.diag=levels \
    -cp "$OUT/classes:$CP" net.minecraft.client.renderer.chunk.LodSectionItemsTest true
