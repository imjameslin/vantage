package bench;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * Stage-2 W0b chunk-pipeline metrics ({@code bench.chunkStats=true}, implied by {@code mode=join}): the patched client's
 * {@code ChunkStats} counters (read by reflection; absent on the vanilla jar: counters null, the rest still works),
 * {@link ChunkProbe}'s render-thread timers of the chunk / light packet handlers, and a 250 ms series of loaded chunks,
 * compile queue length and compiles. Result key {@code "chunks"}: {@code total} (from level join to the end),
 * {@code measure} (the MEASURE phase only, perf / travel), {@code join} (mode=join), {@code series}.
 *
 * <p>{@code mode=join} (docs/metal/CONTRACT.md 16.S2-W0B): from the first frame with a level and a player ("joined"), the
 * camera holds yaw {@code bench.yaw} / pitch {@code bench.pitch} where the save put the player, and the run ends once the
 * world is fully built: the loaded chunk count has not changed for {@code bench.join.stable} s (default 1.0), the compile
 * queue is empty, and no visible section is waiting (UNCOMPILED with all neighbours loaded and lit, or dirty), all of it
 * continuously for that long. {@code join.allBuiltS} = seconds from joined to the start of that quiet window;
 * {@code join.chunksDoneS} = when the loaded chunk count last changed; {@code join.compilesPerS} = compiles finished per
 * second until all built.
 */
final class ChunkStatsHook {
    static final boolean JOIN = "join".equals(System.getProperty("bench.mode"));
    static final boolean ENABLED = JOIN || Boolean.getBoolean("bench.chunkStats");
    private static final double STABLE_S = Double.parseDouble(System.getProperty("bench.join.stable", "1.0"));
    private static final String STATS = "net.minecraft.client.renderer.chunk.ChunkStats";
    private static Method snapshotMethod;
    private static String[] names;
    private static Field trackerField;
    private static boolean resolved;

    private static long joinNs;
    private static long[] statsAtJoin, statsAtMeasure, statsAtEnd;
    private static long[] probesAtJoin, probesAtMeasure, probesAtEnd;
    private static long lastSampleNs;
    private static final List<double[]> series = new ArrayList<>();
    private static int lastLoaded = -1;
    private static long loadedChangedNs;
    private static long quietSinceNs;
    private static double allBuiltS = -1.0, chunksDoneS = -1.0;
    private static long[] statsAtBuilt, probesAtBuilt;
    private static int lastPending;
    private static int framesSinceJoin;
    private static long maxFrameNs, lastFrameNs;

    private ChunkStatsHook() {
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> c = Class.forName(STATS);
            if (c.getField("ENABLED").getBoolean(null)) {
                snapshotMethod = c.getMethod("snapshot");
                names = (String[]) c.getField("NAMES").get(null);
            }
        } catch (Throwable ignored) {
            snapshotMethod = null;
        }
        try {
            trackerField = net.minecraft.client.renderer.extract.LevelExtractor.class.getDeclaredField("sectionUpdateTracker");
            trackerField.setAccessible(true);
        } catch (Throwable t) {
            trackerField = null;
        }
    }

    private static long[] stats() {
        try {
            return snapshotMethod == null ? null : (long[]) snapshotMethod.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Every frame (render thread), any state. Returns true once mode=join is done. */
    static boolean onFrame(Minecraft mc, long now) {
        if (!ENABLED) {
            return false;
        }
        if (ChunkProbe.renderThread == null) {
            ChunkProbe.renderThread = Thread.currentThread();
        }
        if (mc.level == null || mc.player == null) {
            return false;
        }
        resolve();
        if (joinNs == 0L) {
            joinNs = now;
            statsAtJoin = stats();
            probesAtJoin = ChunkProbe.snapshot();
            loadedChangedNs = now;
            lastSampleNs = now - 1_000_000_000L;
            System.out.println("[bench] chunks: joined, counters " + (snapshotMethod != null ? "on" : "absent"));
        }
        framesSinceJoin++;
        if (lastFrameNs != 0L) {
            maxFrameNs = Math.max(maxFrameNs, now - lastFrameNs);
        }
        lastFrameNs = now;
        int loaded = mc.level.getChunkSource().getLoadedChunksCount();
        if (loaded != lastLoaded) {
            lastLoaded = loaded;
            loadedChangedNs = now;
        }
        SectionRenderDispatcher dispatcher = mc.levelRenderer.sectionRenderDispatcher();
        int queue = dispatcher == null ? -1 : dispatcher.getCompileQueueSize();
        boolean sample = now - lastSampleNs >= 250_000_000L;
        int pending = lastPending;
        if (JOIN && allBuiltS < 0.0) {
            pending = pendingVisible(mc);
            lastPending = pending;
            if (queue != 0 || pending != 0) {
                quietSinceNs = 0L;
            } else if (quietSinceNs == 0L) {
                quietSinceNs = now;
                statsAtBuilt = stats();
                probesAtBuilt = ChunkProbe.snapshot();
            }
            if (quietSinceNs != 0L && (now - quietSinceNs) / 1e9 >= STABLE_S && (now - loadedChangedNs) / 1e9 >= STABLE_S) {
                // Built = the later of: the quiet window's start, the last change of the loaded chunk count.
                allBuiltS = (Math.max(quietSinceNs, loadedChangedNs) - joinNs) / 1e9;
                chunksDoneS = (loadedChangedNs - joinNs) / 1e9;
                System.out.println(String.format(Locale.ROOT, "[bench] chunks: all built %.3f s after join (chunks %d, last chunk %.3f s)",
                    allBuiltS, loaded, chunksDoneS));
            }
        }
        if (sample) {
            lastSampleNs = now;
            long[] s = stats();
            double compiles = s == null ? -1 : s[idx("compiles")];
            series.add(new double[]{(now - joinNs) / 1e9, loaded, queue, JOIN ? pending : -1, compiles});
        }
        return JOIN && allBuiltS >= 0.0;
    }

    /** Visible sections still waiting for a compile: UNCOMPILED with all neighbours, or dirty. -1 without the tracker. */
    private static int pendingVisible(Minecraft mc) {
        if (trackerField == null) {
            return -1;
        }
        SectionUpdateTracker tracker;
        try {
            tracker = (SectionUpdateTracker) trackerField.get(mc.levelExtractor);
        } catch (Throwable t) {
            return -1;
        }
        if (tracker == null) {
            return -1;
        }
        int pending = 0;
        for (SectionRenderDispatcher.RenderSection section : mc.levelRenderer.visibleSections()) {
            long node = section.getSectionNode();
            SectionUpdateTracker.SectionDirtyState dirty = tracker.getDirtyState(node);
            if (dirty == null || !dirty.isDirty()) {
                continue;
            }
            if (section.sectionMesh.get() != CompiledSectionMesh.UNCOMPILED || tracker.hasAllNeighbors(mc.level, node)) {
                pending++;
            }
        }
        return pending;
    }

    static void beginMeasure() {
        if (ENABLED) {
            statsAtMeasure = stats();
            probesAtMeasure = ChunkProbe.snapshot();
            ChunkProbe.resetMax();
        }
    }

    private static int idx(String name) {
        if (names == null) {
            return 0;
        }
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                return i;
            }
        }
        return 0;
    }

    /** JSON value of the result's "chunks" key ("null" when off). */
    static String json(boolean measured) {
        if (!ENABLED) {
            return "null";
        }
        statsAtEnd = stats();
        probesAtEnd = ChunkProbe.snapshot();
        double now = (System.nanoTime() - joinNs) / 1e9;
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"counters\": ").append(snapshotMethod != null);
        if (snapshotMethod != null) {
            try {
                sb.append(", \"blockKinds\": ").append(Class.forName(STATS).getMethod("topBlockUpdates").invoke(null));
            } catch (Throwable ignored) {
                // older client: no histogram
            }
        }
        sb.append(", \"secondsSinceJoin\": ").append(fmt(now));
        sb.append(", \"total\": ").append(delta(statsAtJoin, statsAtEnd, probesAtJoin, probesAtEnd, now));
        if (measured && statsAtMeasure != null || measured && probesAtMeasure != null) {
            sb.append(", \"measure\": ").append(delta(statsAtMeasure, statsAtEnd, probesAtMeasure, probesAtEnd, -1));
        }
        if (JOIN) {
            sb.append(", \"join\": {\"allBuiltS\": ").append(fmt(allBuiltS)).append(", \"chunksDoneS\": ").append(fmt(chunksDoneS));
            sb.append(", \"loadedChunks\": ").append(lastLoaded).append(", \"frames\": ").append(framesSinceJoin);
            sb.append(", \"maxFrameMs\": ").append(fmt(maxFrameNs / 1e6));
            if (statsAtBuilt != null && statsAtJoin != null && allBuiltS > 0) {
                long compiles = statsAtBuilt[idx("compiles")] - statsAtJoin[idx("compiles")];
                sb.append(", \"compilesToBuilt\": ").append(compiles);
                sb.append(", \"compilesPerS\": ").append(fmt(compiles / allBuiltS));
            }
            if (probesAtBuilt != null) {
                sb.append(", \"toBuilt\": ").append(delta(statsAtJoin, statsAtBuilt, probesAtJoin, probesAtBuilt, allBuiltS));
            }
            sb.append('}');
        }
        sb.append(", \"series\": {\"columns\": [\"t\", \"loadedChunks\", \"queue\", \"pendingVisible\", \"compiles\"], \"rows\": [");
        for (int i = 0; i < series.size(); i++) {
            double[] r = series.get(i);
            sb.append(i == 0 ? "" : ", ").append(String.format(Locale.ROOT, "[%.2f, %d, %d, %d, %d]", r[0], (long) r[1], (long) r[2], (long) r[3], (long) r[4]));
        }
        sb.append("]}}");
        return sb.toString();
    }

    private static String delta(long[] a, long[] b, long[] pa, long[] pb, double seconds) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        if (a != null && b != null && names != null) {
            for (int i = 0; i < names.length; i++) {
                sb.append(first ? "" : ", ").append('"').append(names[i]).append("\": ").append(b[i] - a[i]);
                first = false;
            }
            long compiles = b[idx("compiles")] - a[idx("compiles")];
            long ns = b[idx("compileNs")] - a[idx("compileNs")];
            sb.append(", \"compileUsMean\": ").append(fmt(compiles == 0 ? 0 : ns / 1e3 / compiles));
        }
        if (pa != null && pb != null) {
            for (int id = 0; id < ChunkProbe.COUNT; id++) {
                long calls = pb[2 * id] - pa[2 * id];
                long ns = pb[2 * id + 1] - pa[2 * id + 1];
                sb.append(first ? "" : ", ").append('"').append(ChunkProbe.NAMES[id]).append("\": {\"calls\": ").append(calls)
                    .append(", \"ms\": ").append(fmt(ns / 1e6)).append(", \"usMean\": ").append(fmt(calls == 0 ? 0 : ns / 1e3 / calls))
                    .append(", \"maxMs\": ").append(fmt(ChunkProbe.maxNs(id) / 1e6)).append('}');
                first = false;
            }
        }
        if (seconds > 0) {
            sb.append(first ? "" : ", ").append("\"seconds\": ").append(fmt(seconds));
        }
        return sb.append('}').toString();
    }

    private static final String[] SRC = {"other", "lightPacket", "chunkLoad", "block"};

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
