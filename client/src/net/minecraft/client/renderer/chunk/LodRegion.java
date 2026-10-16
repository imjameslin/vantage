package net.minecraft.client.renderer.chunk;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.function.Function;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * Stage 3: the block view vanilla's {@link SectionCompiler} meshes an LOD node from (docs/stage3/LOD-DESIGN.md §3). Positions
 * are in cell coordinates of the node's level (level 0: block coordinates, so level-0 meshes are vanilla's own output for the
 * cached blocks: models, random offsets, AO, smooth light, blended biome tints). Reads come from the node and its eight
 * same-level neighbours; a missing neighbour repeats the nearest column of the data that exists (no walls at the edge of
 * explored terrain), below the data is filler, above it air in full sky light. Block entities are not part of section
 * meshes and are absent. Light and tints never touch the live level.
 */
final class LodRegion extends RenderSectionRegion {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private final LodData[] nodes;
    private final LodData center;
    private final int level;
    private final int scale;
    private final int blend;
    private final Function<String, Holder<Biome>> biomes;
    private final @Nullable BiomeManager biomeManager;
    private final ColorResolver[] resolvers = new ColorResolver[6];
    private final Long2IntOpenHashMap[] tintCache = new Long2IntOpenHashMap[6];
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

    /** {@code nodes}: 3x3 same-level nodes, index (dz + 1) * 3 + dx + 1, center non-null. */
    LodRegion(final ClientLevel level, final LodData[] nodes, final int blendRadius, final Function<String, Holder<Biome>> biomes) {
        super(level, 0, 0, 0, new SectionCopy[27]);
        this.nodes = nodes;
        this.center = nodes[4];
        this.level = this.center.level;
        this.scale = 1 << this.level;
        this.blend = blendRadius;
        this.biomes = biomes;
        this.biomeManager = this.level == 0 ? level.getBiomeManager().withDifferentSource(this::quartBiome) : null;
        this.stripTrees = LodTrees.drawsLevel(this.level) && LodTrees.spritesAvailable();
    }

    /**
     * Per column of the center node (z * 16 + x): its highest cell holding anything but air (cell y), or MIN_VALUE. Every
     * cell above it reads as air here, so SectionCompiler need not ask.
     */
    int[] columnTops() {
        int[] t = this.columnTops;
        if (t == null) {
            t = new int[256];
            LodData d = this.center;
            for (int col = 0; col < 256; col++) {
                int y = d.height - 1;
                while (y >= 0 && d.states[y * 256 + col] == LodData.AIR_ID) {
                    y--;
                }
                t[col] = y < 0 ? Integer.MIN_VALUE : y + d.cellMinY;
            }
            this.columnTops = t;
        }
        return t;
    }

    private int @Nullable [] columnTops;

    int levelOf() {
        return this.level;
    }

    /** Node holding cell column (cx, cz), clamped into the nearest existing node of the 3x3 (never null). */
    private LodData nodeAt(final int cx, final int cz) {
        int dx = Math.floorDiv(cx, 16) - this.center.nx;
        int dz = Math.floorDiv(cz, 16) - this.center.nz;
        if (dx < -1 || dx > 1 || dz < -1 || dz > 1) {
            return null;
        }
        return this.nodes[(dz + 1) * 3 + dx + 1];
    }

    /** Cell index for (cx, cy, cz) or -1 (above: air), -2 (below: filler); out-of-data columns clamp to the center node. */
    private int cellIndex(final LodData[] holder, int cx, final int cy, int cz) {
        LodData node = this.nodeAt(cx, cz);
        if (node == null) {
            node = this.center;
            cx = Math.max(this.center.nx * 16, Math.min(this.center.nx * 16 + 15, cx));
            cz = Math.max(this.center.nz * 16, Math.min(this.center.nz * 16 + 15, cz));
        }
        holder[0] = node;
        int y = cy - node.cellMinY;
        if (y < 0) {
            return -2;
        }
        if (y >= node.height) {
            return -1;
        }
        return LodData.index(cx - node.nx * 16, y, cz - node.nz * 16);
    }

    private final LodData[] holder = new LodData[1];

    /**
     * Round 8 ({@code mc.lod.skirt}, default true): the top cells of a border column get their outward side faces even where the
     * neighbour node's data is solid there. A node only knows its neighbours' LOD data; what is drawn next to it can be lower
     * (real terrain beside a seed-generated node, or another level): the step had no wall and the sky showed through it under
     * the water (pale patches along the edge of the real terrain) and as crack lines between nodes. The wall is hidden inside
     * the neighbour wherever the two agree.
     */
    static final boolean SKIRT = Lod.flag("mc.lod.skirt", true);
    private static final int NO_SKIRT = Integer.MIN_VALUE;
    private int @Nullable [] centerTop;

    /** Cells: {@code mc.lod.skirtDepth} blocks at level 0 (default 16), halved per level, at least 2. */
    private static final int SKIRT_DEPTH = Integer.getInteger("mc.lod.skirtDepth", 16);

    static int skirtDepth(final int level) {
        return SKIRT ? Math.max(2, SKIRT_DEPTH >> level) : 0;
    }

    /** For a cell of a direct neighbour node within the skirt of the center's border column beside it: that column's top solid
     * cell y; else NO_SKIRT. */
    private int skirtTop(final int cx, final int cy, final int cz) {
        int x0 = this.center.nx * 16, z0 = this.center.nz * 16;
        int bx = Math.max(x0, Math.min(x0 + 15, cx)), bz = Math.max(z0, Math.min(z0 + 15, cz));
        if (Math.abs(cx - bx) + Math.abs(cz - bz) != 1) {
            return NO_SKIRT;
        }
        int[] tops = this.centerTop;
        if (tops == null) {
            tops = new int[256];
            LodData d = this.center;
            for (int col = 0; col < 256; col++) {
                int y = d.height - 1;
                while (y >= 0 && d.states[y * 256 + col] != LodData.FILLER_ID && !LodData.stateOf(d.states[y * 256 + col]).isSolidRender()) {
                    y--;
                }
                tops[col] = y + d.cellMinY;
            }
            this.centerTop = tops;
        }
        int top = tops[(bz - z0) * 16 + bx - x0];
        return cy <= top && cy > top - skirtDepth(this.level) ? top : NO_SKIRT;
    }

    @Override
    public BlockState getBlockState(final BlockPos pos) {
        // the compiler asks each cell about 7 times (itself, then its neighbours' face culling): resolved once per mesh over the
        // node plus a one-cell border, the whole height
        int lx = pos.getX() - (this.center.nx * 16 - 1);
        int lz = pos.getZ() - (this.center.nz * 16 - 1);
        int ly = pos.getY() - (this.center.cellMinY - 1);
        if (STATE_CACHE && lx >= 0 && lx < 18 && lz >= 0 && lz < 18 && ly >= 0 && ly < this.center.height + 2) {
            BlockState[] c = this.stateCache;
            if (c == null) {
                c = this.stateCache = new BlockState[18 * 18 * (this.center.height + 2)];
            }
            int k = (ly * 18 + lz) * 18 + lx;
            BlockState st = c[k];
            if (st == null) {
                st = c[k] = this.resolveBlockState(pos);
            }
            return st;
        }
        return this.resolveBlockState(pos);
    }

    static final boolean STATE_CACHE = Lod.flag("mc.lod.stateCache", true);
    private BlockState @Nullable [] stateCache;

    /**
     * SectionCompiler's far-terrain scan: the slab at {@code min} plus a one-cell border, index ((y + 1) * 18 + z + 1) * 18 +
     * x + 1. Center columns are resolved up to one cell over their top ({@code tops}, SectionCompiler's own skip); above
     * that they read air, as getBlockState would.
     */
    BlockState[] slabBox(final BlockPos min, final int[] tops) {
        BlockState[] box = this.box;
        if (box == null) {
            box = this.box = new BlockState[18 * 18 * 18];
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int z = -1; z <= 16; z++) {
            for (int x = -1; x <= 16; x++) {
                boolean inner = x >= 0 && x < 16 && z >= 0 && z < 16;
                int top = inner ? tops[z * 16 + x] : Integer.MAX_VALUE;
                for (int y = -1; y <= 16; y++) {
                    int wy = min.getY() + y;
                    box[((y + 1) * 18 + z + 1) * 18 + x + 1] = wy > top ? AIR : this.getBlockState(p.set(min.getX() + x, wy, min.getZ() + z));
                }
            }
        }
        return box;
    }

    private BlockState @Nullable [] box;

    /** Index of {@code pos} in the per-mesh caches (node plus a one-cell border, whole height), or -1. */
    private int cacheIndex(final BlockPos pos) {
        int lx = pos.getX() - (this.center.nx * 16 - 1);
        int lz = pos.getZ() - (this.center.nz * 16 - 1);
        int ly = pos.getY() - (this.center.cellMinY - 1);
        return lx >= 0 && lx < 18 && lz >= 0 && lz < 18 && ly >= 0 && ly < this.center.height + 2 ? (ly * 18 + lz) * 18 + lx : -1;
    }

    /**
     * Smooth light's inputs per cell for the whole mesh (LodLighting, {@code mc.lod.lightCache}): vanilla's BlockModelLighter
     * keeps the last 100 positions in linked hash maps, missed most of a node's and paid the maps on every lookup; here both
     * values depend on the position only, so the arrays hold the same numbers. Off for the reference compile of
     * {@code mc.lod.skipVerify}.
     */
    boolean lightCacheOn = true;
    private int @Nullable [] brightCache;
    private float @Nullable [] shadeCache;

    int cachedBrightness(final BlockPos pos) {
        int k = this.cacheIndex(pos);
        if (k < 0) {
            return net.minecraft.util.LightCoordsUtil.BrightnessGetter.DEFAULT.packedBrightness(this, pos);
        }
        int[] c = this.brightCache;
        if (c == null) {
            c = this.brightCache = new int[18 * 18 * (this.center.height + 2)];
            java.util.Arrays.fill(c, Integer.MAX_VALUE);
        }
        int v = c[k];
        if (v == Integer.MAX_VALUE) {
            v = c[k] = net.minecraft.util.LightCoordsUtil.BrightnessGetter.DEFAULT.packedBrightness(this, pos);
        }
        return v;
    }

    float cachedShade(final BlockState state, final BlockPos pos) {
        int k = this.cacheIndex(pos);
        if (k < 0) {
            return state.getShadeBrightness(this, pos);
        }
        float[] c = this.shadeCache;
        if (c == null) {
            c = this.shadeCache = new float[18 * 18 * (this.center.height + 2)];
            java.util.Arrays.fill(c, Float.NaN);
        }
        float v = c[k];
        if (Float.isNaN(v)) {
            v = c[k] = state.getShadeBrightness(this, pos);
        }
        return v;
    }

    private BlockState resolveBlockState(final BlockPos pos) {
        int i = this.cellIndex(this.holder, pos.getX(), pos.getY(), pos.getZ());
        if (i == -1) {
            return AIR;
        }
        if (i == -2) {
            return SKIRT && this.holder[0] != this.center && this.skirtTop(pos.getX(), pos.getY(), pos.getZ()) != NO_SKIRT ? AIR : LodData.FILLER;
        }
        if ((this.level > 0 ? HOLLOW_LEAVES : HOLLOW_LEAVES_L0) && this.holder[0].hiddenLeafAt(i)) {
            return AIR;
        }
        if (this.stripTrees) {
            boolean[] strip = this.stripMask(this.holder[0]);
            if (strip != null && strip[i]) {
                return AIR;
            }
        }
        if (SKIRT && this.holder[0] != this.center && this.skirtTop(pos.getX(), pos.getY(), pos.getZ()) != NO_SKIRT) {
            int id = this.holder[0].states[i];
            if (id == LodData.FILLER_ID || LodData.stateOf(id).isSolidRender()) {
                return AIR;
            }
        }
        BlockState st = LodData.stateOf(this.holder[0].states[i]);
        if (DROP_PLANTS && st.getBlock() instanceof net.minecraft.world.level.block.VegetationBlock && st.getFluidState().isEmpty()) {
            // level 0 (coarser levels already mesh plants as air): cross-model plants are 1-2 px out here and never
            // back-face culled (~9% of the drawn LOD quads in flight)
            return AIR;
        }
        if (DIAG_NO_TREES && (st.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock || st.is(net.minecraft.tags.BlockTags.LOGS))) {
            return AIR;
        }
        return st;
    }

    /** {@code -Dmc.lod.dropPlants}: plants (VegetationBlock, not waterlogged) mesh as air in far terrain. */
    static final boolean DROP_PLANTS = Lod.flag("mc.lod.dropPlants", true);

    /** Diagnostics (round 4, {@code mc.lod.diag} contains "notrees"): leaves and logs mesh as air (cost of the trees). */
    static final boolean DIAG_NO_TREES = System.getProperty("mc.lod.diag", "").contains("notrees");

    /** {@code -Dmc.lod.hollowLeaves} (default true): coarse levels mesh canopy-interior leaves as air (LodData#hiddenLeaves). */
    static final boolean HOLLOW_LEAVES = Lod.flag("mc.lod.hollowLeaves", true);
    /**
     * {@code -Dmc.lod.hollowLeaves0} (default true in M1): the same at level 0. Not exact: canopy-interior leaf faces seen
     * through the cutout holes are gone; beyond the real render distance a leaf block is <= ~7 px and its mipmapped alpha
     * mostly opaque, but under the spyglass the difference can show (docs/stage3/LOD-DESIGN.md §2.2; M3 re-checks it).
     */
    static final boolean HOLLOW_LEAVES_L0 = Lod.flag("mc.lod.hollowLeaves0", true);
    /** Round 4: the node's (and neighbours') tree cells mesh as air; LodMesher draws the trees as stand-ins (LodTrees). */
    boolean stripTrees;
    private final java.util.IdentityHashMap<LodData, boolean[]> stripMasks = new java.util.IdentityHashMap<>();
    private static final boolean[] NO_STRIP = new boolean[0];

    private boolean @Nullable [] stripMask(final LodData node) {
        boolean[] m = this.stripMasks.get(node);
        if (m == null) {
            m = LodTrees.stripMask(node, this.nodes);
            if (m == null) {
                m = NO_STRIP;
            }
            this.stripMasks.put(node, m);
        }
        return m == NO_STRIP ? null : m;
    }

    @Override
    public FluidState getFluidState(final BlockPos pos) {
        return this.getBlockState(pos).getFluidState();
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(final BlockPos pos) {
        return null;
    }

    @Override
    public int getBrightness(final LightLayer layer, final BlockPos pos) {
        int i = this.cellIndex(this.holder, pos.getX(), pos.getY(), pos.getZ());
        if (i == -1) {
            return layer == LightLayer.SKY ? 15 : 0;
        }
        if (i == -2) {
            return 0;
        }
        if (SKIRT && this.holder[0] != this.center) {
            int top = this.skirtTop(pos.getX(), pos.getY(), pos.getZ());
            if (top != NO_SKIRT) {
                int id = this.holder[0].states[i];
                if (id == LodData.FILLER_ID || LodData.stateOf(id).isSolidRender()) {
                    // The skirt's wall is lit as the open cell over its column (the neighbour's solid cell holds no light).
                    int x0 = this.center.nx * 16, z0 = this.center.nz * 16;
                    int bx = Math.max(x0, Math.min(x0 + 15, pos.getX())), bz = Math.max(z0, Math.min(z0 + 15, pos.getZ()));
                    int y = top + 1 - this.center.cellMinY;
                    if (y >= this.center.height) {
                        return layer == LightLayer.SKY ? 15 : 0;
                    }
                    int l = this.center.light[LodData.index(bx - x0, y, bz - z0)] & 0xFF;
                    return layer == LightLayer.SKY ? l >> 4 : l & 15;
                }
            }
        }
        int li = this.holder[0].light[i] & 0xFF;
        return layer == LightLayer.SKY ? li >> 4 : li & 15;
    }

    @Override
    public int getRawBrightness(final BlockPos pos, final int darkening) {
        return Math.max(this.getBrightness(LightLayer.SKY, pos) - darkening, this.getBrightness(LightLayer.BLOCK, pos));
    }

    @Override
    public int getMinY() {
        return this.center.cellMinY - 64;
    }

    @Override
    public int getHeight() {
        return this.center.height + 128;
    }

    // ---------------------------------------------------------------------------------------------------------------- tint

    /** Level 0: quart biome for vanilla's zoom (3x3 nodes; outside them the nearest quart of the center chunk). */
    private Holder<Biome> quartBiome(int qx, final int qy, int qz) {
        LodData node = this.nodeAt(qx << 2, qz << 2);
        if (node == null) {
            node = this.center;
            qx = Math.max(this.center.nx * 4, Math.min(this.center.nx * 4 + 3, qx));
            qz = Math.max(this.center.nz * 4, Math.min(this.center.nz * 4 + 3, qz));
        }
        int idx = node.biomeIndexAtQuart(qx - node.nx * 4, qy - (node.cellMinY >> 2), qz - node.nz * 4);
        return this.biomes.apply(node.biomeKeys[idx]);
    }

    private Holder<Biome> columnBiome(int cx, int cz) {
        LodData node = this.nodeAt(cx, cz);
        if (node == null) {
            node = this.center;
            cx = Math.max(this.center.nx * 16, Math.min(this.center.nx * 16 + 15, cx));
            cz = Math.max(this.center.nz * 16, Math.min(this.center.nz * 16 + 15, cz));
        }
        return this.biomes.apply(node.biomeKeys[node.biomes[(cz - node.nz * 16) * 16 + cx - node.nx * 16]]);
    }

    static final boolean TINT_2D = Lod.flag("mc.lod.tint2d", true);

    @Override
    public int getBlockTint(final BlockPos pos, final ColorResolver resolver) {
        int r = 0;
        while (r < this.resolvers.length && this.resolvers[r] != null && this.resolvers[r] != resolver) {
            r++;
        }
        if (r == this.resolvers.length) {
            return this.computeTint(pos, resolver);
        }
        if (this.resolvers[r] == null) {
            this.resolvers[r] = resolver;
            this.tintCache[r] = new Long2IntOpenHashMap();
            this.tintCache[r].defaultReturnValue(Integer.MIN_VALUE);
        }
        // TINT_2D: level 0 blends per column (first y asked); 3D biome changes along y are not visible from afar.
        long key = TINT_2D ? BlockPos.asLong(pos.getX(), 0, pos.getZ()) : pos.asLong();
        int c = this.tintCache[r].get(key);
        if (c == Integer.MIN_VALUE) {
            c = this.computeTint(pos, resolver);
            this.tintCache[r].put(key, c);
        }
        return c;
    }

    /**
     * Level 0: the zoomed biome of block column (x, z), kept per mesh for the node plus its border and the blend radius.
     * The blend reads each column about (2r+1)^2 times per tint source (grass, foliage, water); one column's biome is the
     * same for all of them. Like TINT_2D, a column keeps the first y asked (3D biome changes are not visible from afar).
     */
    private Holder<Biome> zoomedBiome(final BiomeManager manager, final int x, final int y, final int z) {
        int side = 18 + 2 * this.blend;
        int lx = x - (this.center.nx * 16 - 1 - this.blend);
        int lz = z - (this.center.nz * 16 - 1 - this.blend);
        if (lx < 0 || lz < 0 || lx >= side || lz >= side) {
            return manager.getBiome(this.scratch.set(x, y, z));
        }
        Holder<Biome>[] cache = this.zoomCache;
        if (cache == null) {
            @SuppressWarnings("unchecked")
            Holder<Biome>[] fresh = (Holder<Biome>[])new Holder[side * side];
            cache = this.zoomCache = fresh;
        }
        Holder<Biome> h = cache[lz * side + lx];
        if (h == null) {
            h = cache[lz * side + lx] = manager.getBiome(this.scratch.set(x, y, z));
        }
        return h;
    }

    private Holder<Biome> @Nullable [] zoomCache;

    /** Level 0: {@code ClientLevel.calculateBlockTint} over the cached biomes; coarser: the cell column's biome at its center. */
    private int computeTint(final BlockPos pos, final ColorResolver resolver) {
        if (this.level > 0) {
            int half = this.scale >> 1;
            return resolver.getColor(this.columnBiome(pos.getX(), pos.getZ()).value(), pos.getX() * this.scale + half, pos.getZ() * this.scale + half);
        }
        BiomeManager manager = this.biomeManager;
        int dist = this.blend;
        if (dist == 0) {
            return resolver.getColor(manager.getBiome(pos).value(), pos.getX(), pos.getZ());
        }
        int count = (dist * 2 + 1) * (dist * 2 + 1);
        int totalRed = 0;
        int totalGreen = 0;
        int totalBlue = 0;
        for (int z = pos.getZ() - dist; z <= pos.getZ() + dist; z++) {
            for (int x = pos.getX() - dist; x <= pos.getX() + dist; x++) {
                int color = resolver.getColor(this.zoomedBiome(manager, x, pos.getY(), z).value(), x, z);
                totalRed += ARGB.red(color);
                totalGreen += ARGB.green(color);
                totalBlue += ARGB.blue(color);
            }
        }
        return ARGB.color(totalRed / count, totalGreen / count, totalBlue / count);
    }
}
