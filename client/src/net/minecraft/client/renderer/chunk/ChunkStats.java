package net.minecraft.client.renderer.chunk;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Stage-2 W0b chunk-pipeline counters (docs/metal/CONTRACT.md 16.S2-W0B). Off by default and free when off
 * ({@code ENABLED} is a static final the JIT folds); enable with {@code -Dmc.chunkStats=true} (bench: {@code
 * --bench-prop chunkStats=true}, which also sets it). Nothing here changes what the game does: the hooks only count.
 *
 * <p>Dirty marks are attributed to a source: {@link #SRC_LIGHT_PACKET} (ClientboundLightUpdatePacket: readSectionList's
 * {@code ClientLevel.setSectionDirtyWithNeighbors}), {@link #SRC_CHUNK_LOAD} (a chunk's light enabled after its packet:
 * {@code ClientLevel.setSectionRangeDirty}, 3x3 columns), {@link #SRC_BLOCK} (block changes, {@code LevelExtractor
 * .setBlocksDirty}), {@link #SRC_SAME_RENDER} (render-equivalent block updates, BlockUpdateFilter verify mode) and {@link #SRC_OTHER} (the light engine's own propagation through {@code ClientChunkCache
 * .onLightUpdate}, biome packets, anything else). A section remembers the sources since its last compile was scheduled
 * ({@link #pendingSources}, render thread), so every scheduled compile and every identical recompile is attributed to
 * the sources that dirtied it.
 *
 * <p>Read by the bench (bench/src/bench/ChunkStatsHook.java) through {@link #snapshot()} / {@link #NAMES}: the slot names
 * are that interface.
 */
public final class ChunkStats {
    public static final boolean ENABLED = Boolean.getBoolean("mc.chunkStats");
    public static final int SRC_OTHER = 0;
    public static final int SRC_LIGHT_PACKET = 1;
    public static final int SRC_CHUNK_LOAD = 2;
    public static final int SRC_BLOCK = 3;
    public static final int SRC_SAME_RENDER = 4;   // BlockUpdateFilter verify mode: marks a skip would have dropped
    public static final int SRC_COUNT = 5;
    static final String[] SRC_NAMES = {"other", "lightPacket", "chunkLoad", "block", "sameRender"};
    /** Current dirty source (render thread; set around the ClientLevel / LevelExtractor entry points). */
    public static int source = SRC_OTHER;

    // Slots (snapshot order). Per-source groups are SRC_COUNT wide.
    public static final int MARKS = 0;                      // setSectionDirty calls on a tracked section, per source
    public static final int NEWLY_DIRTY = MARKS + SRC_COUNT;        // ... that turned a clean section dirty
    public static final int SCHED_INITIAL = NEWLY_DIRTY + SRC_COUNT;   // compiles scheduled for UNCOMPILED sections (first source bit)
    public static final int SCHED_RECOMPILE = SCHED_INITIAL + SRC_COUNT;   // compiles scheduled for compiled sections, per source bit
    public static final int IDENTICAL = SCHED_RECOMPILE + SRC_COUNT;   // finished recompiles whose mesh equals the shown one, per source bit
    public static final int RECOMPILE_DONE = IDENTICAL + SRC_COUNT;    // finished recompiles, per source bit (denominator of IDENTICAL)
    public static final int SCHED_SYNC = RECOMPILE_DONE + SRC_COUNT;   // of the scheduled, compiled synchronously on the render thread
    public static final int COMPILES = SCHED_SYNC + 1;          // finished compile tasks (any thread)
    public static final int COMPILE_NS = COMPILES + 1;          // their wall time in SectionCompiler.compile
    public static final int COMPILE_EMPTY = COMPILE_NS + 1;     // compiles with no rendered layer
    public static final int CANCELLED = COMPILE_EMPTY + 1;      // compile tasks cancelled (superseded, reset) before they finished
    public static final int REGION_NS = CANCELLED + 1;          // render thread: RenderRegionCache.createRegion time
    public static final int RESORTS = REGION_NS + 1;            // translucent resort tasks finished
    public static final int UPLOAD_BYTES = RESORTS + 1;         // vertex + index bytes staged by compile tasks
    public static final int BLOCK_UPDATES = UPLOAD_BYTES + 1;        // ClientLevel.sendBlockUpdated calls (server or local block changes)
    public static final int BLOCK_SAME_RENDER = BLOCK_UPDATES + 1;   // ... render-equivalent (BlockUpdateFilter), skipped unless verify / off
    public static final int RECOMPILE_ONLY_SR = BLOCK_SAME_RENDER + 1;   // finished recompiles whose only dirty source was sameRender (verify)
    public static final int IDENTICAL_ONLY_SR = RECOMPILE_ONLY_SR + 1;   // ... of them byte-identical (must equal RECOMPILE_ONLY_SR)
    public static final int N = IDENTICAL_ONLY_SR + 1;
    public static final String[] NAMES = new String[N];

    static {
        for (int s = 0; s < SRC_COUNT; s++) {
            NAMES[MARKS + s] = "marks." + SRC_NAMES[s];
            NAMES[NEWLY_DIRTY + s] = "newlyDirty." + SRC_NAMES[s];
            NAMES[SCHED_INITIAL + s] = "schedInitial." + SRC_NAMES[s];
            NAMES[SCHED_RECOMPILE + s] = "schedRecompile." + SRC_NAMES[s];
            NAMES[IDENTICAL + s] = "identical." + SRC_NAMES[s];
            NAMES[RECOMPILE_DONE + s] = "recompileDone." + SRC_NAMES[s];
        }
        NAMES[SCHED_SYNC] = "schedSync";
        NAMES[COMPILES] = "compiles";
        NAMES[COMPILE_NS] = "compileNs";
        NAMES[COMPILE_EMPTY] = "compileEmpty";
        NAMES[CANCELLED] = "cancelled";
        NAMES[REGION_NS] = "regionNs";
        NAMES[RESORTS] = "resorts";
        NAMES[UPLOAD_BYTES] = "uploadBytes";
        NAMES[BLOCK_UPDATES] = "blockUpdates";
        NAMES[BLOCK_SAME_RENDER] = "blockSameRender";
        NAMES[RECOMPILE_ONLY_SR] = "recompileOnlySameRender";
        NAMES[IDENTICAL_ONLY_SR] = "identicalOnlySameRender";
    }

    private static final AtomicLongArray COUNTERS = new AtomicLongArray(N);
    /** Sources (bit per SRC_*) that dirtied a section since its compile was last scheduled; render thread only. */
    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap PENDING = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

    private ChunkStats() {
    }

    public static void add(final int slot, final long value) {
        COUNTERS.getAndAdd(slot, value);
    }

    /** Render thread: a setSectionDirty on a tracked section ({@code wasDirty}: before the call). */
    public static void mark(final long sectionNode, final boolean wasDirty) {
        int src = source;
        COUNTERS.getAndIncrement(MARKS + src);
        if (!wasDirty) {
            COUNTERS.getAndIncrement(NEWLY_DIRTY + src);
        }

        PENDING.put(sectionNode, PENDING.get(sectionNode) | 1 << src);
    }

    /** Render thread: the sources of a section whose compile is being scheduled (and forgets them). 0 = never marked. */
    public static int takePending(final long sectionNode) {
        return PENDING.remove(sectionNode);
    }

    /** Render thread: a compile scheduled ({@code recompile}: the section had a mesh), with the sources that dirtied it. */
    public static void scheduled(final int sources, final boolean recompile, final boolean sync) {
        if (recompile) {
            for (int s = 0; s < SRC_COUNT; s++) {
                if ((sources & 1 << s) != 0) {
                    COUNTERS.getAndIncrement(SCHED_RECOMPILE + s);
                }
            }
        } else {
            COUNTERS.getAndIncrement(SCHED_INITIAL + (sources == 0 ? SRC_OTHER : Integer.numberOfTrailingZeros(sources)));
        }

        if (sync) {
            COUNTERS.getAndIncrement(SCHED_SYNC);
        }
    }

    /** Any thread: a recompile finished; {@code identical}: its mesh equals the shown one (same hash). */
    public static void recompileDone(final int sources, final boolean identical) {
        for (int s = 0; s < SRC_COUNT; s++) {
            if ((sources & 1 << s) != 0 || sources == 0 && s == SRC_OTHER) {
                COUNTERS.getAndIncrement(RECOMPILE_DONE + s);
                if (identical) {
                    COUNTERS.getAndIncrement(IDENTICAL + s);
                }
            }
        }

        if (sources == 1 << SRC_SAME_RENDER) {
            COUNTERS.getAndIncrement(RECOMPILE_ONLY_SR);
            if (identical) {
                COUNTERS.getAndIncrement(IDENTICAL_ONLY_SR);
            }
        }
    }

    /** Block update kinds, "old>new" block ids with a render-equivalent flag (diagnostic histogram; render thread). */
    private static final java.util.HashMap<String, long[]> BLOCK_KINDS = new java.util.HashMap<>();

    /** Render thread: ClientLevel.sendBlockUpdated (counting only). */
    public static void blockUpdate(
        final net.minecraft.world.level.block.state.BlockState old, final net.minecraft.world.level.block.state.BlockState current, final boolean sameRender
    ) {
        COUNTERS.getAndIncrement(BLOCK_UPDATES);
        if (sameRender) {
            COUNTERS.getAndIncrement(BLOCK_SAME_RENDER);
        }

        String key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(old.getBlock()).getPath() + ">"
            + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(current.getBlock()).getPath() + (sameRender ? " same" : "");
        BLOCK_KINDS.computeIfAbsent(key, k -> new long[1])[0]++;
    }

    /** The 25 most frequent block update kinds as a JSON object (read by the bench). */
    public static String topBlockUpdates() {
        StringBuilder sb = new StringBuilder("{");
        BLOCK_KINDS.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(25)
            .forEach(e -> sb.append(sb.length() > 1 ? ", " : "").append('"').append(e.getKey()).append("\": ").append(e.getValue()[0]));
        return sb.append('}').toString();
    }

    public static long[] snapshot() {
        long[] out = new long[N];
        for (int i = 0; i < N; i++) {
            out[i] = COUNTERS.get(i);
        }

        return out;
    }

    /**
     * Content hashes of a compiled mesh, one per part: per layer (by ordinal; 0 = absent) the uploaded vertex bytes and the
     * index bytes (TRANSLUCENT: index size only, its order follows the camera), then the visibility set, then the block
     * entities by identity. Equal arrays are treated as equal meshes (counters only: nothing is skipped on a hash).
     */
    public static long[] meshHash(final SectionCompiler.Results results, final java.util.function.IntFunction<ByteBuffer> vertexBytes) {
        int layers = ChunkSectionLayer.values().length;
        long[] parts = new long[3 * layers + 2];
        for (var entry : results.renderedLayers.entrySet()) {
            int layer = entry.getKey().ordinal();
            long h = mix(0x9E3779B97F4A7C15L, layer + 1);
            h = hashBytes(h, vertexBytes.apply(layer));
            ByteBuffer index = entry.getValue().indexBuffer();
            // TRANSLUCENT's index buffer is sorted for the camera position at compile time: only its size counts.
            h = entry.getKey() == net.minecraft.client.renderer.chunk.ChunkSectionLayer.TRANSLUCENT
                ? mix(h, index == null ? -1 : index.remaining()) : hashBytes(h, index);
            parts[layer] = h;
            parts[layers + 2 + layer] = lightHash(vertexBytes.apply(layer), true);
            parts[2 * layers + 2 + layer] = lightHash(vertexBytes.apply(layer), false);
        }

        // VisibilitySet has no hashCode (identity): hash its 36 bits.
        long vis = 0L;
        net.minecraft.core.Direction[] dirs = net.minecraft.core.Direction.values();
        for (int a = 0; a < dirs.length; a++) {
            for (int b = 0; b < dirs.length; b++) {
                if (results.visibilitySet.visibilityBetween(dirs[a], dirs[b])) {
                    vis |= 1L << a * dirs.length + b;
                }
            }
        }

        parts[layers] = vis;
        List<BlockEntity> bes = results.blockEntities;
        long h = mix(0L, bes.size());
        for (BlockEntity be : bes) {
            h = mix(h, System.identityHashCode(be));
        }

        parts[layers + 1] = h;
        return parts;
    }

    /** Hash of the 4 light bytes (light) or the other 24 bytes (!light) of every 28-byte vertex (diagnostic: tells a light-only difference from a geometry one). */
    private static long lightHash(final ByteBuffer buffer, final boolean light) {
        if (buffer == null || buffer.remaining() % 28 != 0) {
            return 0L;
        }

        ByteBuffer b = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        long h = 1L;
        for (int v = buffer.position(); v < buffer.limit(); v += 28) {
            if (light) {
                h = mix(h, b.getInt(v + 24));
            } else {
                for (int i = 0; i < 24; i += 4) {
                    h = mix(h, b.getInt(v + i));
                }
            }
        }

        return h;
    }

    private static long hashBytes(long h, final ByteBuffer buffer) {
        if (buffer == null) {
            return mix(h, -1L);
        }

        int pos = buffer.position();
        int end = buffer.limit();
        h = mix(h, end - pos);
        ByteBuffer b = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int i = pos;
        for (; i + 8 <= end; i += 8) {
            h = mix(h, b.getLong(i));
        }

        for (; i < end; i++) {
            h = mix(h, b.get(i));
        }

        return h;
    }

    private static long mix(final long h, final long v) {
        long x = (h ^ v) * 0xBF58476D1CE4E5B9L;
        return x ^ x >>> 31;
    }
}
