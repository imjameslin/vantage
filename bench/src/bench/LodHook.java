package bench;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Stage 3 LOD support for the bench (docs/stage3/LOD-DESIGN.md §8):
 * <ul>
 *   <li>reflection on {@code net.minecraft.client.renderer.chunk.Lod} of the patched client (absent on vanilla / older jars:
 *       everything here is then a no-op): {@link #active}, {@link #idle}, {@link #json};</li>
 *   <li>{@code bench.mode=lodpregen} ({@link #pregen}): a TEST FIXTURE that makes the integrated server generate (and save)
 *       every chunk within {@code bench.pregen.radius} chunks of the player, so singleplayer LOD runs have far terrain to read.
 *       It writes into the world it runs on: use it only on a copy (tools/lod/lod-world.sh). Not part of the client.</li>
 * </ul>
 * The settle step of perf / abab / photo runs waits for {@link #idle} (all LOD work done) up to {@code bench.lodWait} seconds.
 */
final class LodHook {
    static final double WAIT_S = Double.parseDouble(System.getProperty("bench.lodWait", "900"));
    private static Class<?> lod;
    private static boolean resolved;

    private LodHook() {
    }

    private static Class<?> lod() {
        if (!resolved) {
            resolved = true;
            try {
                lod = Class.forName("net.minecraft.client.renderer.chunk.Lod");
            } catch (Throwable t) {
                lod = null;
            }
        }
        return lod;
    }

    /** True when the patched client was started with the LOD system built (-Dmc.lod=true). */
    static boolean active() {
        Class<?> c = lod();
        if (c == null) {
            return false;
        }
        try {
            Field f = c.getField("BUILD");
            return f.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean idle() {
        Class<?> c = lod();
        if (c == null) {
            return true;
        }
        try {
            Method m = c.getMethod("idle");
            return (boolean) m.invoke(null);
        } catch (Throwable t) {
            return true;
        }
    }

    /** The LOD state plus process memory, as a JSON object (null when the LOD system is not built). */
    static String json() {
        if (!active()) {
            return "null";
        }
        String stats = "null";
        try {
            Object s = lod().getMethod("statsJson").invoke(null);
            if (s != null) {
                stats = s.toString();
            }
        } catch (Throwable ignored) {
        }
        NativeMem.init();
        Runtime rt = Runtime.getRuntime();
        return String.format(Locale.ROOT, "{\"state\": %s, \"heapUsedMB\": %.1f, \"physFootprintMB\": %.1f, \"metalAllocatedMB\": %.1f}", stats,
            (rt.totalMemory() - rt.freeMemory()) / 1048576.0, NativeMem.physFootprint() / 1048576.0, NativeMem.metalAllocated() / 1048576.0);
    }

    /** Process memory without LOD stats (runs without the LOD system, for the memory comparison). */
    static String memoryJson() {
        NativeMem.init();
        Runtime rt = Runtime.getRuntime();
        return String.format(Locale.ROOT, "{\"heapUsedMB\": %.1f, \"physFootprintMB\": %.1f, \"metalAllocatedMB\": %.1f}",
            (rt.totalMemory() - rt.freeMemory()) / 1048576.0, NativeMem.physFootprint() / 1048576.0, NativeMem.metalAllocated() / 1048576.0);
    }

    // ------------------------------------------------------------------------------------------------------------ fixture

    private static final int FLUSH_EVERY = Integer.getInteger("bench.pregen.flushEvery", 8192);

    private static void flush(IntegratedServer server) {
        CompletableFuture<Void> saved = new CompletableFuture<>();
        server.execute(() -> {
            server.saveEverything(true, true, true);
            saved.complete(null);
        });
        try {
            saved.get(600, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.out.println("[bench] pregen save: " + e);
        }
    }
    private static volatile Thread pregenThread;
    static volatile boolean pregenDone;
    static volatile String pregenResult = "";

    /** Starts the fixture generation (once); {@link #pregenDone} turns true when every chunk is generated and saved. */
    static void pregen(Minecraft mc) {
        if (pregenThread != null) {
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        int radius = Integer.getInteger("bench.pregen.radius", 256);
        int window = Integer.getInteger("bench.pregen.window", 384);
        int cx = mc.player.getBlockX() >> 4;
        int cz = mc.player.getBlockZ() >> 4;
        Thread t = new Thread(() -> {
            long t0 = System.nanoTime();
            ServerLevel level = server.overworld();
            Deque<CompletableFuture<?>> inFlight = new ArrayDeque<>();
            long done = 0;
            long total = 0;
            long lastLog = t0;
            for (int ring = 0; ring <= radius; ring++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    for (int dx = -ring; dx <= ring; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring || dx * dx + dz * dz > (radius + 1) * (radius + 1)) {
                            continue;
                        }
                        total++;
                        while (inFlight.size() >= window) {
                            try {
                                inFlight.poll().get(120, TimeUnit.SECONDS);
                            } catch (Exception e) {
                                System.out.println("[bench] pregen: " + e);
                            }
                            done++;
                        }
                        // getChunkFuture's own ticket (UNKNOWN) expires after one tick, before generation finishes: the
                        // chunk was dropped at structure_starts (second try). Hold a FORCED ticket until it is FULL.
                        net.minecraft.world.level.ChunkPos pos = new net.minecraft.world.level.ChunkPos(cx + dx, cz + dz);
                        var cache = level.getChunkSource();
                        server.execute(() -> cache.addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED, pos, 0));
                        inFlight.add(cache.getChunkFuture(pos.x(), pos.z(), ChunkStatus.FULL, true)
                            .whenComplete((r, e) -> server.execute(() -> cache.removeTicketWithRadius(net.minecraft.server.level.TicketType.FORCED, pos, 0))));
                        // The server's IO worker runs chunk writes at background priority behind the steady stream of
                        // foreground reads that new chunk requests issue, so unflushed writes pile up in the heap (first
                        // two tries: ~1700 chunks/s generated, nothing written, heap exhausted). Drain and flush (save
                        // with flush = synchronize) every FLUSH_EVERY chunks.
                        if (total % FLUSH_EVERY == 0) {
                            while (!inFlight.isEmpty()) {
                                try {
                                    inFlight.poll().get(120, TimeUnit.SECONDS);
                                } catch (Exception e) {
                                    System.out.println("[bench] pregen: " + e);
                                }
                                done++;
                            }
                            flush(server);
                        }
                    }
                }
                long now = System.nanoTime();
                if (now - lastLog > 10_000_000_000L) {
                    lastLog = now;
                    System.out.printf(Locale.ROOT, "[bench] pregen: ring %d/%d, %d chunks done, %.0f chunks/s%n", ring, radius, done, done / ((now - t0) / 1e9));
                }
            }
            while (!inFlight.isEmpty()) {
                try {
                    inFlight.poll().get(120, TimeUnit.SECONDS);
                } catch (Exception e) {
                    System.out.println("[bench] pregen: " + e);
                }
                done++;
            }
            flush(server);
            double s = (System.nanoTime() - t0) / 1e9;
            pregenResult = String.format(Locale.ROOT, "{\"radius\": %d, \"chunks\": %d, \"seconds\": %.1f, \"chunksPerSecond\": %.1f}", radius, total, s, total / s);
            System.out.println("[bench] pregen done: " + pregenResult);
            pregenDone = true;
        }, "bench-pregen");
        t.setDaemon(true);
        pregenThread = t;
        t.start();
    }
}
