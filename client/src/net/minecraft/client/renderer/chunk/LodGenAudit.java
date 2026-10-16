package net.minecraft.client.renderer.chunk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Round 8 diagnostic ({@code -Dmc.lod.genAudit=true}): every captured real chunk is also generated from the seed at level 0 and the
 * two compared per column, so the generated far terrain can be tuned toward what the real chunks show when they replace it.
 * Per column: ground height (top block that is not air, plant, leaves or log), ground block, tree cover (leaves / log above the
 * ground), water at the top. Results go into the LOD stats line ({@code genAudit}).
 */
final class LodGenAudit {
    static final boolean ENABLED = Lod.flag("mc.lod.genAudit", false);

    private static final AtomicLong CHUNKS = new AtomicLong();
    private static final AtomicLong COLUMNS = new AtomicLong();
    /** |ground dy| buckets: 0, 1, 2-3, 4-7, 8-15, 16+. */
    private static final AtomicLongArray DY = new AtomicLongArray(6);
    private static final AtomicLong DY_SUM = new AtomicLong();
    private static final AtomicLong GROUND_SAME = new AtomicLong();
    /** Trees: both, real only, generated only. */
    private static final AtomicLong TREE_BOTH = new AtomicLong();
    private static final AtomicLong TREE_REAL = new AtomicLong();
    private static final AtomicLong TREE_GEN = new AtomicLong();
    /** Canopy top height difference where both have trees (sum of |dy|). */
    private static final AtomicLong CANOPY_DY = new AtomicLong();
    /** Signed canopy top difference (generated - real) where both have trees. */
    private static final AtomicLong CANOPY_SIGNED = new AtomicLong();
    /** Both-tree columns whose real tree reaches at most 2 above the ground (bushes, fallen logs), and their signed sum. */
    private static final AtomicLong LOW_REAL = new AtomicLong();
    private static final AtomicLong LOW_REAL_SIGNED = new AtomicLong();
    private static final AtomicLong WATER_BOTH = new AtomicLong();
    private static final AtomicLong WATER_REAL = new AtomicLong();
    private static final AtomicLong WATER_GEN = new AtomicLong();
    private static final Map<String, AtomicLong> GROUND_PAIRS = new ConcurrentHashMap<>();

    private LodGenAudit() {
    }

    private static final int GROUND = 0;
    private static final int TREE = 1;
    private static final int SKIP = 2;
    private static final int WATER = 3;

    private static int kind(final int id) {
        if (id == LodData.AIR_ID) {
            return SKIP;
        }
        BlockState s = Block.BLOCK_STATE_REGISTRY.byId(id);
        if (s == null || s.isAir()) {
            return SKIP;
        }
        if (s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) {
            return TREE;
        }
        if (s.is(Blocks.WATER) || s.is(Blocks.ICE)) {
            return WATER;
        }
        if (!s.isSolidRender()) {
            return SKIP;   // grass, flowers, snow layers, vines ...
        }
        return GROUND;
    }

    private static String name(final int id) {
        BlockState s = Block.BLOCK_STATE_REGISTRY.byId(id);
        return s == null ? "?" : s.getBlock().builtInRegistryHolder().key().identifier().getPath();
    }

    /** One column's top: [ground y, ground id, tree top y (or -1), water top (1/0)]. */
    private static int[] column(final LodData d, final int x, final int z) {
        int treeTop = -1;
        boolean water = false;
        boolean seenTop = false;
        for (int y = d.height - 1; y >= 0; y--) {
            int id = d.states[LodData.index(x, y, z)];
            int k = kind(id);
            if (k == SKIP) {
                continue;
            }
            if (k == TREE) {
                if (treeTop < 0) {
                    treeTop = y;
                }
                seenTop = true;
                continue;
            }
            if (k == WATER) {
                if (!seenTop) {
                    water = true;
                }
                return new int[]{y, id, treeTop, water ? 1 : 0};
            }
            return new int[]{y, id, treeTop, 0};
        }
        return new int[]{-1, LodData.AIR_ID, treeTop, 0};
    }

    static void compare(final LodData real, final LodData gen) {
        if (real.height != gen.height || real.cellMinY != gen.cellMinY) {
            return;
        }
        CHUNKS.incrementAndGet();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int[] r = column(real, x, z);
                int[] g = column(gen, x, z);
                COLUMNS.incrementAndGet();
                boolean rw = r[3] == 1;
                boolean gw = g[3] == 1;
                if (rw && gw) {
                    WATER_BOTH.incrementAndGet();
                } else if (rw) {
                    WATER_REAL.incrementAndGet();
                } else if (gw) {
                    WATER_GEN.incrementAndGet();
                }
                int dy = g[0] - r[0];
                int a = Math.abs(dy);
                DY.incrementAndGet(a == 0 ? 0 : a == 1 ? 1 : a < 4 ? 2 : a < 8 ? 3 : a < 16 ? 4 : 5);
                DY_SUM.addAndGet(dy);
                if (r[1] == g[1]) {
                    GROUND_SAME.incrementAndGet();
                } else {
                    GROUND_PAIRS.computeIfAbsent(name(r[1]) + ">" + name(g[1]), k -> new AtomicLong()).incrementAndGet();
                }
                boolean rt = r[2] >= 0;
                boolean gt = g[2] >= 0;
                if (rt && gt) {
                    TREE_BOTH.incrementAndGet();
                    CANOPY_DY.addAndGet(Math.abs(r[2] - g[2]));
                    CANOPY_SIGNED.addAndGet(g[2] - r[2]);
                    if (r[2] - r[0] <= 2) {
                        LOW_REAL.incrementAndGet();
                        LOW_REAL_SIGNED.addAndGet(g[2] - r[2]);
                    }
                } else if (rt) {
                    TREE_REAL.incrementAndGet();
                } else if (gt) {
                    TREE_GEN.incrementAndGet();
                }
            }
        }
    }

    static String json() {
        long n = Math.max(1L, COLUMNS.get());
        StringBuilder top = new StringBuilder();
        GROUND_PAIRS.entrySet().stream().sorted((p, q) -> Long.compare(q.getValue().get(), p.getValue().get())).limit(12)
            .forEach(e -> top.append(top.isEmpty() ? "" : ", ").append('"').append(e.getKey()).append("\": ").append(e.getValue().get()));
        long both = Math.max(1L, TREE_BOTH.get());
        return String.format(java.util.Locale.ROOT,
            "{\"chunks\": %d, \"dy%%\": [%.1f, %.1f, %.1f, %.1f, %.1f, %.1f], \"dyMean\": %.2f, \"groundSame%%\": %.1f, "
                + "\"tree%%\": {\"both\": %.1f, \"realOnly\": %.1f, \"genOnly\": %.1f, \"canopyDy\": %.2f, \"canopySigned\": %.2f, \"lowReal%%\": %.1f, \"lowRealSigned\": %.2f}, "
                + "\"water%%\": {\"both\": %.1f, \"realOnly\": %.1f, \"genOnly\": %.1f}, \"groundPairs\": {%s}}",
            CHUNKS.get(), 100.0 * DY.get(0) / n, 100.0 * DY.get(1) / n, 100.0 * DY.get(2) / n, 100.0 * DY.get(3) / n,
            100.0 * DY.get(4) / n, 100.0 * DY.get(5) / n, (double)DY_SUM.get() / n, 100.0 * GROUND_SAME.get() / n,
            100.0 * TREE_BOTH.get() / n, 100.0 * TREE_REAL.get() / n, 100.0 * TREE_GEN.get() / n, (double)CANOPY_DY.get() / both, (double)CANOPY_SIGNED.get() / both, 100.0 * LOW_REAL.get() / both, (double)LOW_REAL_SIGNED.get() / Math.max(1L, LOW_REAL.get()),
            100.0 * WATER_BOTH.get() / n, 100.0 * WATER_REAL.get() / n, 100.0 * WATER_GEN.get() / n, top);
    }
}
