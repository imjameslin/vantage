package bench;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Render-thread method timers for the chunk pipeline (stage-2 W0b, {@code bench.chunkStats=true}). BenchAgent wraps the
 * bodies of {@link #TARGETS} with {@code long t = ChunkProbe.enter(); ... ChunkProbe.exit(id, t)} before every return.
 * Only calls on the render thread are timed ({@link #enter} returns 0 elsewhere: packet handlers first run on the
 * network thread, where {@code PacketUtils.ensureRunningOnSameThread} throws to re-queue them, and the integrated server's
 * light engine is the same class as the client's).
 */
public final class ChunkProbe {
    /** owner, method, descriptor (one per id). */
    public static final String[][] TARGETS = {
        {"net/minecraft/client/multiplayer/ClientPacketListener", "handleLevelChunkWithLight",
            "(Lnet/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket;)V"},
        {"net/minecraft/client/multiplayer/ClientPacketListener", "handleLightUpdatePacket",
            "(Lnet/minecraft/network/protocol/game/ClientboundLightUpdatePacket;)V"},
        {"net/minecraft/client/multiplayer/ClientPacketListener", "handleForgetLevelChunk",
            "(Lnet/minecraft/network/protocol/game/ClientboundForgetLevelChunkPacket;)V"},
        {"net/minecraft/client/multiplayer/ClientPacketListener", "handleChunkBlocksUpdate",
            "(Lnet/minecraft/network/protocol/game/ClientboundSectionBlocksUpdatePacket;)V"},
        {"net/minecraft/client/multiplayer/ClientLevel", "pollLightUpdates", "()V"},
        {"net/minecraft/world/level/lighting/LevelLightEngine", "runLightUpdates", "()I"},
        {"net/minecraft/client/multiplayer/ClientChunkCache", "replaceWithPacketData",
            "(IILnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;)Lnet/minecraft/world/level/chunk/LevelChunk;"},
        {"net/minecraft/world/level/lighting/LayerLightSectionStorage", "swapSectionMap", "()V"},
    };
    public static final String[] NAMES = {"chunkPacket", "lightPacket", "forgetChunk", "blocksUpdate", "pollLightUpdates", "runLightUpdates",
        "replaceWithPacketData", "swapSectionMap"};
    public static final int COUNT = TARGETS.length;
    /** [2 * id] calls, [2 * id + 1] ns. */
    private static final AtomicLongArray TOTALS = new AtomicLongArray(2 * COUNT);
    private static final long[] MAX_NS = new long[COUNT];
    static volatile Thread renderThread;
    /** Insertions made by BenchAgent (report). */
    public static final int[] inserted = new int[COUNT];

    private ChunkProbe() {
    }

    public static long enter() {
        return Thread.currentThread() == renderThread ? System.nanoTime() : 0L;
    }

    public static void exit(final int id, final long start) {
        if (start != 0L) {
            long ns = System.nanoTime() - start;
            TOTALS.getAndIncrement(2 * id);
            TOTALS.getAndAdd(2 * id + 1, ns);
            if (ns > MAX_NS[id]) {
                MAX_NS[id] = ns;
            }
        }
    }

    static long[] snapshot() {
        long[] out = new long[2 * COUNT];
        for (int i = 0; i < out.length; i++) {
            out[i] = TOTALS.get(i);
        }
        return out;
    }

    static long maxNs(final int id) {
        return MAX_NS[id];
    }

    static void resetMax() {
        java.util.Arrays.fill(MAX_NS, 0L);
    }
}
