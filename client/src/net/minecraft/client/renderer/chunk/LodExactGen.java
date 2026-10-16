package net.minecraft.client.renderer.chunk;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.jspecify.annotations.Nullable;

/**
 * Farsight exact generation ({@code mc.lod.exactGen}, singleplayer): the far terrain from vanilla's own generator steps (structure
 * starts, structure references, biomes, terrain, features) run on our own threads in private proto-chunks, the way a
 * pregenerator would but without light, mobs, ticking or saving. The result is the real world's blocks (trees, lakes, villages)
 * for the LOD, many times faster than full chunks through the server's ticket system.
 *
 * <p>One batch = an {@code B x B} block of target chunks. Their features need neighbours at TERRAIN (radius 1), and the features
 * of those neighbours reach into the targets (trees across a border), so the ring at radius 1 runs FEATURES too and the ring at
 * radius 2 TERRAIN; the structure steps reach 8 chunks further. Each batch owns all its proto-chunks (no sharing between threads).
 */
final class LodExactGen {
    static final boolean ENABLED = Lod.flag("mc.lod.exactGen", true);
    static final int BATCH = Math.max(2, Integer.getInteger("mc.lod.exactBatch", 16));
    /** Chunks of a step outstanding at once (on our pool or vanilla's background pool). */
    /** Chunks kept around a batch (beyond its half-width) for the next batches. */
    static final int KEEP = Integer.getInteger("mc.lod.exactKeep", 24);
    static final int IN_FLIGHT = Math.max(1, Integer.getInteger("mc.lod.exactInFlight", 3));
    /** Structure starts in parallel (StructureTemplate.Palette's cache is concurrent in our build). */
    static final boolean PARALLEL_STARTS = Lod.flag("mc.lod.exactParallelStarts", true);
    private static final ChunkStatus[] ORDER = {
        ChunkStatus.STRUCTURE_STARTS, ChunkStatus.STRUCTURE_REFERENCES, ChunkStatus.BIOMES, ChunkStatus.TERRAIN, ChunkStatus.FEATURES
    };

    /** A proto-chunk of a batch: serves itself for every status it has reached (what WorldGenRegion asks the cache for). */
    private static final class Holder extends GenerationChunkHolder {
        net.minecraft.world.level.chunk.@Nullable LodProtoChunk chunk;
        volatile ChunkStatus reached = ChunkStatus.EMPTY;

        Holder(final ChunkPos pos) {
            super(pos);
        }

        /** Created on the first step run on it (the structure ring is wide; most of it never gets past the cheap steps). */
        net.minecraft.world.level.chunk.LodProtoChunk chunk(final ServerLevel level) {
            if (this.chunk == null) {
                this.chunk = new net.minecraft.world.level.chunk.LodProtoChunk(this.getPos(), level, level.palettedContainerFactory());
            }
            return this.chunk;
        }

        @Override
        public @Nullable ChunkAccess getChunkIfPresentUnchecked(final ChunkStatus status) {
            return this.chunk != null && status.isOrBefore(this.reached) ? this.chunk : null;
        }

        @Override
        protected void addSaveDependency(final CompletableFuture<?> sync) {
        }

        @Override
        public int getTicketLevel() {
            return 0;
        }

        @Override
        public int getQueueLevel() {
            return 0;
        }
    }

    /** Wall time per step over all batches (stats). */
    static final java.util.concurrent.atomic.AtomicLong[] STEP_NANOS = new java.util.concurrent.atomic.AtomicLong[ORDER.length];
    /** Chunks each step ran (stats: the reuse between batches). */
    static final java.util.concurrent.atomic.AtomicLong[] STEP_CHUNKS = new java.util.concurrent.atomic.AtomicLong[ORDER.length];

    static {
        for (int i = 0; i < ORDER.length; i++) {
            STEP_NANOS[i] = new java.util.concurrent.atomic.AtomicLong();
            STEP_CHUNKS[i] = new java.util.concurrent.atomic.AtomicLong();
        }
    }

    static String stepStats() {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < ORDER.length; i++) {
            if (i > 0) b.append(", ");
            b.append('"').append(ORDER[i].getName()).append("\": [").append(STEP_NANOS[i].get() / 1_000_000L).append(", ")
                .append(STEP_CHUNKS[i].get()).append(']');
        }
        return b.append('}').toString();
    }

    private LodExactGen() {
    }

    static WorldGenContext context(final ServerLevel level) {
        var cache = level.getChunkSource();
        return new WorldGenContext(level, cache.getGenerator(), level.getServer().getStructureTemplateManager(), cache.getLightEngine(),
            Runnable::run, pos -> {
            });
    }

    /**
     * Worker: generates the batch whose low corner is ({@code bx}, {@code bz}) (chunk coordinates, multiples of {@link #BATCH})
     * and hands each target chunk at FEATURES to {@code out}. Returns false when cancelled.
     */
    /** The proto-chunks kept between batches (touched by one batch at a time). */
    static final class World {
        final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Holder> holders = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    }

    static boolean generate(final ServerLevel level, final WorldGenContext context, final World world, final int bx, final int bz,
        final java.util.Set<Long> targets, final Consumer<ProtoChunk> out, final java.util.function.BooleanSupplier cancelled, final java.util.concurrent.ForkJoinPool pool) {
        // radius (around the target block) each status is needed at: FEATURES 1, TERRAIN 2, and the structure steps
        // their own accumulated reach beyond the TERRAIN ring
        ChunkStep features = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES);
        int[] reach = new int[ORDER.length];
        for (int i = 0; i < ORDER.length; i++) {
            reach[i] = ORDER[i] == ChunkStatus.FEATURES ? 1 : 1 + features.getAccumulatedRadiusOf(ORDER[i]);
        }
        int margin = reach[0];
        for (int r : reach) {
            margin = Math.max(margin, r);
        }
        // the deepest step's own cache radius (WorldGenRegion maps its whole dependency square)
        int stepRadius = 0;
        for (ChunkStatus s : ORDER) {
            stepRadius = Math.max(stepRadius, ChunkPyramid.GENERATION_PYRAMID.getStepTo(s).directDependencies().size());
        }
        // holders for every chunk a step of this batch can read (persist across batches: the ring chunks of one batch are
        // the targets of the next, at the status they reached)
        int range = margin + stepRadius + 1;
        for (int z = bz - range; z < bz + BATCH + range; z++) {
            for (int x = bx - range; x < bx + BATCH + range; x++) {
                world.holders.computeIfAbsent(ChunkPos.pack(x, z), p -> new Holder(ChunkPos.unpack(p)));
            }
        }
        var holders = world.holders;
        // each chunk's distance to the nearest target: a step runs only within its reach of the targets
        int span = BATCH + 2 * margin;
        int[] dist = new int[span * span];
        java.util.Arrays.fill(dist, Integer.MAX_VALUE);
        for (long t : targets) {
            int tx = ChunkPos.getX(t) - (bx - margin);
            int tz = ChunkPos.getZ(t) - (bz - margin);
            for (int z = Math.max(0, tz - margin); z <= Math.min(span - 1, tz + margin); z++) {
                for (int x = Math.max(0, tx - margin); x <= Math.min(span - 1, tx + margin); x++) {
                    int d = Math.max(Math.abs(x - tx), Math.abs(z - tz));
                    if (d < dist[z * span + x]) {
                        dist[z * span + x] = d;
                    }
                }
            }
        }
        // every step runs its chunks in parallel (vanilla does too); FEATURES writes into the neighbours (radius 1), so it runs
        // in 9 phases of chunks 3 apart, which never share a neighbour
        for (int i = 0; i < ORDER.length; i++) {
            ChunkStatus status = ORDER[i];
            ChunkStep step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
            int r = reach[i];
            int sr = step.directDependencies().size();
            int phases = status == ChunkStatus.FEATURES ? 3 : 1;
            boolean serial = !PARALLEL_STARTS && status == ChunkStatus.STRUCTURE_STARTS;
            long t0 = System.nanoTime();
            for (int phase = 0; phase < phases * phases; phase++) {
                java.util.List<Holder> todo = new java.util.ArrayList<>();
                for (int z = bz - r; z < bz + BATCH + r; z++) {
                    for (int x = bx - r; x < bx + BATCH + r; x++) {
                        if (phases == 1 || Math.floorMod(x, 3) + 3 * Math.floorMod(z, 3) == phase) {
                            Holder h = holders.get(ChunkPos.pack(x, z));
                            if (dist[(z - bz + margin) * span + (x - bx + margin)] <= r && !h.reached.isOrAfter(status)) {
                                todo.add(h);
                            }
                        }
                    }
                }
                for (Holder h : todo) {
                    h.chunk(level);   // created here, before the parallel part
                }
                STEP_CHUNKS[i].addAndGet(todo.size());
                if (cancelled.getAsBoolean()) {
                    return false;
                }
                if (serial) {
                    for (Holder h : todo) {
                        if (cancelled.getAsBoolean()) {
                            return false;
                        }
                        apply(context, step, sr, holders, h).join();
                    }
                } else {
                    // the async steps (biomes, terrain) run on vanilla's background pool and our threads must not block on
                    // them: each chunk is started on our pool, its future composed, at most IN_FLIGHT outstanding (the same
                    // pool compiles the sections near the player)
                    java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(IN_FLIGHT);
                    java.util.List<CompletableFuture<ChunkAccess>> futures = new java.util.ArrayList<>(todo.size());
                    for (Holder h : todo) {
                        if (cancelled.getAsBoolean()) {
                            break;
                        }
                        slots.acquireUninterruptibly();
                        futures.add(CompletableFuture.supplyAsync(() -> apply(context, step, sr, holders, h), pool)
                            .thenCompose(f -> f)
                            .whenComplete((c, e) -> slots.release()));
                    }
                    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
                    if (cancelled.getAsBoolean()) {
                        return false;
                    }
                }
                for (Holder h : todo) {
                    h.reached = status;
                }
            }
            STEP_NANOS[i].addAndGet(System.nanoTime() - t0);
        }
        for (long t : targets) {
            out.accept(holders.get(t).chunk(level));
        }
        // forget the chunks far from this batch (memory); a chunk regenerated later repeats its features into neighbours
        // still kept, which places the same seeded features again
        int keep = BATCH / 2 + KEEP;
        int cx = bx + BATCH / 2;
        int cz = bz + BATCH / 2;
        holders.values().removeIf(h -> Math.max(Math.abs(h.getPos().x() - cx), Math.abs(h.getPos().z() - cz)) > keep);
        return true;
    }

    private static CompletableFuture<ChunkAccess> apply(final WorldGenContext context, final ChunkStep step, final int sr,
        final it.unimi.dsi.fastutil.longs.Long2ObjectMap<Holder> all, final Holder h) {
        ChunkPos pos = h.getPos();
        StaticCache2D<GenerationChunkHolder> local = StaticCache2D.create(pos.x(), pos.z(), sr, (ax, az) -> all.get(ChunkPos.pack(ax, az)));
        return step.apply(context, local, h.chunk);
    }

    /** The statuses a batch runs, in order (diagnostics). */
    static List<ChunkStatus> order() {
        return List.of(ORDER);
    }
}
