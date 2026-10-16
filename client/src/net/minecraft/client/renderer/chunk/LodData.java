package net.minecraft.client.renderer.chunk;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Stage 3 LOD node data (docs/stage3/LOD-DESIGN.md §2): one node of level {@code k} covers {@code 2^k x 2^k} chunks as a
 * {@code 16 x height x 16} grid of cells of {@code 2^k} blocks. Level 0 is one chunk column at block resolution.
 *
 * <p>Decoded form: {@link #states} (global block-state ids, {@code Block.BLOCK_STATE_REGISTRY}) and {@link #light}
 * ({@code sky << 4 | block}) indexed {@code (y * 16 + z) * 16 + x}; biomes as indices into {@link #biomeKeys}: level 0 keeps
 * vanilla's 4x4x4 quart grid ({@code (qy * 4 + qz) * 4 + qx}, exact tints), coarser levels one biome per column.
 *
 * <p>Level 0 is built from a captured chunk with two invisible-from-outside simplifications (§2.2): air without sky light
 * becomes {@link #FILLER} (sealed caves: their walls are never meshed), and a full opaque block whose six neighbours inside the
 * chunk are full opaque becomes {@link #FILLER} (hidden ores / stone variants collapse into one run). Light is stored only for
 * cells that are not full opaque (the only ones whose light the mesher reads). Coarser levels are 2x2x2 downsamples
 * ({@link #downsample}).
 *
 * <p>Encoded form (deflate): version, level, node x / z, cell min y, height, biome key palette (strings), biome indices,
 * then per column (z-major) its vertical runs of (state id, light, length) as varints, bottom up.
 */
final class LodData {
    static final int VERSION = 2;
    /** Diagnostic ({@code -Dmc.lod.fillerDebug=true}): filler is emerald, so an exposed filler face is obvious in a frame. */
    static final BlockState FILLER = (Boolean.getBoolean("mc.lod.fillerDebug") ? Blocks.EMERALD_BLOCK : Blocks.STONE).defaultBlockState();
    static final int FILLER_ID = Block.BLOCK_STATE_REGISTRY.getId(FILLER);
    static final int AIR_ID = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
    final int level;
    final int nx;
    final int nz;
    final int cellMinY;
    final int height;
    final int[] states;
    final byte[] light;
    final String[] biomeKeys;
    /** Level 0: {@code 16 * (height / 4)} quarts; level > 0: 256 columns. */
    final short[] biomes;
    /**
     * Round 4: tree records of the node (LodTrees; levels 1..LodTrees.MAX_LEVEL, 3 ints per tree), or null (none recorded:
     * level 0 detects on demand, coarser levels carry none).
     */
    volatile int @Nullable [] trees;   // volatile: filled lazily on nodes the decode cache shares between workers
    /** Diagnostic (mc.lod.tintDebug): made by the seed generator in this session (not stored). */
    transient boolean generated;

    LodData(final int level, final int nx, final int nz, final int cellMinY, final int height, final int[] states, final byte[] light,
        final String[] biomeKeys, final short[] biomes) {
        this.level = level;
        this.nx = nx;
        this.nz = nz;
        this.cellMinY = cellMinY;
        this.height = height;
        this.states = states;
        this.light = light;
        this.biomeKeys = biomeKeys;
        this.biomes = biomes;
    }

    static int index(final int x, final int y, final int z) {
        return (y * 16 + z) * 16 + x;
    }

    int state(final int x, final int y, final int z) {
        return this.states[index(x, y, z)];
    }

    /** Biome key index of a block / cell position inside the node (level 0: quart lookup). */
    int biomeIndexAt(final int x, final int y, final int z) {
        if (this.level == 0) {
            int qy = Math.max(0, Math.min(this.height / 4 - 1, y >> 2));
            return this.biomes[(qy * 4 + (z >> 2)) * 4 + (x >> 2)];
        }
        return this.biomes[z * 16 + x];
    }

    /** Level 0 quart lookup (quart coordinates inside the node). */
    int biomeIndexAtQuart(final int qx, final int qy, final int qz) {
        int y = Math.max(0, Math.min(this.height / 4 - 1, qy));
        return this.biomes[(y * 4 + qz) * 4 + qx];
    }

    // ---------------------------------------------------------------------------------------------------------------- build

    /** What the render thread copies out of a client chunk (or the singleplayer reader out of a saved chunk). */
    static final class Capture {
        int cx;
        int cz;
        int minSectionY;
        int sectionCount;
        /** Per section: block states, null = only air. */
        net.minecraft.world.level.chunk.PalettedContainer<BlockState>[] sections;
        /** Tests (LodSelfTest): per section 4096 states ((y * 16 + z) * 16 + x), null = only air; used instead of sections. */
        BlockState @Nullable [][] dense;
        /** Per section: 2048-byte nibble arrays or null (no data). */
        byte[][] sky;
        byte[][] block;
        /** Per section: 64 biome keys (qy * 16 + qz * 4 + qx), or null. */
        String[][] biomes;
        /** Sky light is present (client light engine / isLightOn); false = estimate from the column (all-solid below the top). */
        boolean hasSkyLight;
    }

    static final boolean SEAL_DARK_WATER = Lod.flag("mc.lod.sealDarkWater", false);
    static final boolean LEAF_LIGHT = Lod.flag("mc.lod.leafLight", true);
    static final boolean LIGHT_DIAG = Lod.flag("mc.lod.lightDiag", false);
    private static final java.util.concurrent.atomic.AtomicInteger DIAG_LOGGED = new java.util.concurrent.atomic.AtomicInteger();

    static LodData fromCapture(final Capture c, final boolean sealDarkAir) {
        int height = c.sectionCount * 16;
        int cellMinY = c.minSectionY * 16;
        int n = 256 * height;
        int[] states = new int[n];
        byte[] light = new byte[n];
        // Raw copy (and sky light, with null sections inheriting from above as the client sky engine does).
        int[] skyBelow = new int[256];
        Arrays.fill(skyBelow, 15);
        for (int s = c.sectionCount - 1; s >= 0; s--) {
            var pc = c.sections == null ? null : c.sections[s];
            BlockState[] dense = c.dense == null ? null : c.dense[s];
            byte[] sky = c.sky == null ? null : c.sky[s];
            byte[] bl = c.block == null ? null : c.block[s];
            for (int y = 15; y >= 0; y--) {
                int wy = s * 16 + y;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int i = index(x, wy, z);
                        BlockState state = dense != null ? dense[(y * 16 + z) * 16 + x] : pc == null ? null : pc.get(x, y, z);
                        int id = state == null ? AIR_ID : Block.BLOCK_STATE_REGISTRY.getId(state);
                        states[i] = id;
                        int nib = (y * 16 + z) * 16 + x;
                        int skyLevel;
                        if (sky != null) {
                            skyLevel = nibble(sky, nib);
                        } else if (c.hasSkyLight) {
                            skyLevel = skyBelow[z * 16 + x];
                        } else {
                            skyLevel = skyBelow[z * 16 + x];
                            if (state != null && !state.isAir()) {
                                // Round 8: as the sky engine dims it (leaves and water by 1 per block, solid blocks to 0). Before,
                                // any block made everything below dark: crowns under a taller tree's canopy were black.
                                skyBelow[z * 16 + x] = Math.max(0, skyLevel - Math.max(1, state.getLightDampening()));
                            }
                        }
                        int blockLevel = bl == null ? 0 : nibble(bl, nib);
                        light[i] = (byte)(skyLevel << 4 | blockLevel);
                    }
                }
                if (sky != null) {
                    for (int k = 0; k < 256; k++) {
                        skyBelow[k] = nibble(sky, y * 256 + k);
                    }
                }
            }
        }
        // Pass 1: seal dark air (dimensions with sky light only: elsewhere sky 0 is everywhere).
        // Round 8: only under a solid block of the column (caves, overhangs). Air under a dense canopy is dark too (sky 0 a few
        // blocks in under jungle crowns) but open to view between the leaves: sealed, it showed as flat dark plates in the trees.
        for (int col = 0; sealDarkAir && col < 256; col++) {
            boolean roof = false;
            for (int y = height - 1; y >= 0; y--) {
                int i = y * 256 + col;
                // Round 8 (SEAL_DARK_WATER): flooded caves as well. Their walls and inner water surfaces were meshed under every
                // sea bed (up to half of a real ocean node's quads), never in view from outside.
                // Off by default: 6-9 % fewer quads on an ocean flight, but three of three flights had one ~90 ms frame with it
                // (0.1 % low 11-17 against 52-79 without), cause not found.
                if (states[i] == AIR_ID || isAir(states[i]) || SEAL_DARK_WATER && roof && stateOf(states[i]).getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                    if (roof && (light[i] >> 4 & 15) == 0) {
                        states[i] = FILLER_ID;
                        light[i] = 0;
                    }
                } else if (!roof && stateOf(states[i]).isSolidRender() && !stateOf(states[i]).is(net.minecraft.tags.BlockTags.LOGS)) {
                    roof = true;
                }
            }
        }
        // Pass 2: hidden opaque blocks become filler; light kept only where it is read.
        boolean[] opaque = new boolean[n];
        for (int i = 0; i < n; i++) {
            opaque[i] = states[i] == FILLER_ID || stateOf(states[i]).isSolidRender();
        }
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int i = index(x, y, z);
                    if (!opaque[i]) {
                        continue;
                    }
                    light[i] = 0;
                    if (states[i] == FILLER_ID || x == 0 || x == 15 || z == 0 || z == 15) {
                        continue;
                    }
                    boolean below = y == 0 || opaque[i - 256];
                    boolean above = y + 1 < height && opaque[i + 256];
                    if (below && above && opaque[i - 1] && opaque[i + 1] && opaque[i - 16] && opaque[i + 16]) {
                        states[i] = FILLER_ID;
                    }
                }
            }
        }
        if (LIGHT_DIAG) {
            int sealedAbove = 0, darkAbove = 0, yLo = 9999, yHi = -9999, nullSky = 0;
            for (int i = 0; i + 256 < n; i++) {
                if (states[i] == AIR_ID || states[i] == FILLER_ID || !stateOf(states[i]).is(net.minecraft.tags.BlockTags.LEAVES)) {
                    continue;
                }
                int up = i + 256;
                boolean sealed = states[up] == FILLER_ID;
                boolean dark = !sealed && isAir(states[up]) && (light[up] >> 4 & 15) < 4;
                if (sealed || dark) {
                    if (sealed) sealedAbove++; else darkAbove++;
                    int y = up / 256 + cellMinY;
                    yLo = Math.min(yLo, y);
                    yHi = Math.max(yHi, y);
                    if (c.sky == null || c.sky[up / 256 / 16] == null) nullSky++;
                }
            }
            if (sealedAbove + darkAbove > 8 && DIAG_LOGGED.incrementAndGet() < 60) {
                Lod.LOGGER.info("[lod] lightDiag chunk {},{} leaves under sealed air {} / dark air {} y {}..{} nullSkySection {} hasSky {}",
                    c.cx, c.cz, sealedAbove, darkAbove, yLo, yHi, nullSky, c.hasSkyLight);
            }
        }
        // Biomes (quarts).
        Map<String, Integer> palette = new HashMap<>();
        java.util.List<String> keys = new java.util.ArrayList<>();
        short[] biomes = new short[16 * c.sectionCount * 4];
        for (int s = 0; s < c.sectionCount; s++) {
            String[] b = c.biomes == null ? null : c.biomes[s];
            for (int q = 0; q < 64; q++) {
                String key = b == null || b[q] == null ? "minecraft:plains" : b[q];
                Integer idx = palette.get(key);
                if (idx == null) {
                    idx = keys.size();
                    palette.put(key, idx);
                    keys.add(key);
                }
                int qy = q >> 4;
                int qz = q >> 2 & 3;
                int qx = q & 3;
                biomes[((s * 4 + qy) * 4 + qz) * 4 + qx] = (short)(int)idx;
            }
        }
        return new LodData(0, c.cx, c.cz, cellMinY, height, states, light, keys.toArray(String[]::new), biomes);
    }

    private static int nibble(final byte[] data, final int index) {
        return data[index >> 1] >> ((index & 1) << 2) & 15;
    }

    static BlockState stateOf(final int id) {
        BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
        return state == null ? Blocks.AIR.defaultBlockState() : state;
    }

    static boolean isAir(final int id) {
        return stateOf(id).isAir();
    }

    /**
     * Downsample category of a state: 0 air (also small non-full blocks: plants, torches, ...), 1 fluid, 2 solid-ish (full
     * opaque, leaves, any block whose top is a full face, filler), with snow layers mapped to the snow block.
     */
    private static int category(final BlockState state) {
        if (state.isAir()) {
            return 0;
        }
        if (state.isSolidRender() || (state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) || state.is(Blocks.SNOW)) {
            return 2;
        }
        if (!state.getFluidState().isEmpty()) {
            return 1;
        }
        // Round 2: full translucent cubes (ice, packed / blue ice, glass) are surface blocks too: a frozen river stays ice
        // in the coarse levels instead of showing the water under it (perceptual check vs real terrain at RD 32).
        if (state.getBlock() instanceof net.minecraft.world.level.block.HalfTransparentBlock || state.getBlock() instanceof net.minecraft.world.level.block.TransparentBlock) {
            return 2;
        }
        return 0;
    }

    private static boolean isSnowCover(final int id) {
        if (id == FILLER_ID) {
            return false;
        }
        BlockState s = stateOf(id);
        return s.is(Blocks.SNOW) || s.is(Blocks.SNOW_BLOCK);
    }

    /** Round 2: snow-dusted canopies in coarse levels ({@code mc.lod.snowTops}, default true). */
    static final boolean SNOW_TOPS = !"false".equals(System.getProperty("mc.lod.snowTops"));
    /** Round 3: leaf children (of 8) a leaves-only coarse cell needs ({@code mc.lod.leafMin}; 4 = round 2's majority rule). */
    static final int LEAF_MIN = Math.max(4, Math.min(8, Integer.getInteger("mc.lod.leafMin", 4)));
    /**
     * Coarse water at its real height ({@code mc.lod.fluidLevels}, default true): a coarse fluid cell's surface sits at the
     * children's real surface (as a fraction of the cell, in vanilla's ninths: a flowing state of that amount, a source at
     * 8/9), not at the cell's top. Without it a lake whose top water block is the lower half of a 2-block cell shows its
     * surface ~0.9 block high at level 1, and the step against level 0 water is an open slit (no side face between fluids).
     */
    static final boolean FLUID_LEVELS = !"false".equals(System.getProperty("mc.lod.fluidLevels"));

    /** Surface height of fluid state {@code id} as a fraction of its cell, given whether fluid lies on top of it. */
    private static float fluidHeight(final int id, final boolean covered) {
        if (covered) {
            return 1.0F;
        }
        return stateOf(id).getFluidState().getAmount() / 9.0F;
    }

    /** The fluid {@code rep}'s state with surface {@code frac} of the cell (amount = round(9 frac) in 1..8; 8 = source). */
    static int fluidAt(final int rep, final float frac) {
        BlockState s = stateOf(rep);
        if (!(s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)) {
            return rep;
        }
        int amount = Math.max(1, Math.min(8, Math.round(frac * 9.0F)));
        return Block.BLOCK_STATE_REGISTRY.getId(s.setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 8 - amount));
    }

    /**
     * Coarse ice at its real height ({@code mc.lod.iceLevels}, default true): like {@link #FLUID_LEVELS}, a coarse ice cell whose
     * ice is only in its lower child layer is stored as frosted ice of age {@code round(4 frac) - 1} (frac = surface height as
     * a fraction of the cell: 1/4, 1/2 or 3/4); LodMesher lowers those cells' faces to that height and gives them the ice
     * texture. A full cell stays ice. Without it a frozen lake one block below a level boundary steps a block up there.
     */
    static final boolean ICE_LEVELS = !"false".equals(System.getProperty("mc.lod.iceLevels"));

    static boolean isIce(final int id) {
        if (id == FILLER_ID || id == AIR_ID) {
            return false;
        }
        BlockState s = stateOf(id);
        return s.is(Blocks.ICE) || s.is(Blocks.FROSTED_ICE);
    }

    /** Surface height of a coarse ice cell as a fraction of the cell (1 = full; frosted ice of age a at a coarse level: (a + 1) / 4). */
    static float iceHeight(final int id, final int level) {
        BlockState s = stateOf(id);
        if (level == 0 || !s.is(Blocks.FROSTED_ICE)) {
            return 1.0F;
        }
        return (s.getValue(net.minecraft.world.level.block.FrostedIceBlock.AGE) + 1) / 4.0F;
    }

    static int iceAt(final float frac) {
        int age = Math.round(frac * 4.0F) - 1;
        if (age >= 3) {
            return Block.BLOCK_STATE_REGISTRY.getId(Blocks.ICE.defaultBlockState());
        }
        return Block.BLOCK_STATE_REGISTRY.getId(Blocks.FROSTED_ICE.defaultBlockState()
            .setValue(net.minecraft.world.level.block.FrostedIceBlock.AGE, Math.max(0, age)));
    }

    /** Round 2: canopy cells prefer leaves over logs ({@code mc.lod.leavesOverLogs}, default true). */
    static final boolean LEAVES_OVER_LOGS = !"false".equals(System.getProperty("mc.lod.leavesOverLogs"));

    /**
     * Round 2 snowy-leaves marker: the leaves state with {@code persistent=true, distance=7} (vanilla only has it for
     * player-placed leaves out of reach of logs; in a coarse LOD cell it means "leaves under snow").
     */
    static boolean isSnowyLeaves(final int id) {
        if (id == FILLER_ID || id == AIR_ID) {
            return false;
        }
        BlockState s = stateOf(id);
        return s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock && s.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
            && s.getValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE) == 7;
    }

    private static int snowyLeaves(final int id) {
        BlockState s = stateOf(id).setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true)
            .setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE, 7);
        return Block.BLOCK_STATE_REGISTRY.getId(s);
    }

    private static int representative(final int id) {
        BlockState state = stateOf(id);
        if (state.is(Blocks.SNOW)) {
            return Block.BLOCK_STATE_REGISTRY.getId(Blocks.SNOW_BLOCK.defaultBlockState());
        }
        if (!state.getFluidState().isEmpty() && !state.isSolidRender() && !(state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock)) {
            // Waterlogged plants etc. become their fluid's source block.
            return Block.BLOCK_STATE_REGISTRY.getId(state.getFluidState().createLegacyBlock());
        }
        return id;
    }

    /**
     * The parent node (level {@code level}, node {@code nx, nz}) from its four children (index {@code dz * 2 + dx}; null =
     * no data): each parent cell takes the 2x2x2 child cells. Rule (§2.3): if at least four of the eight are solid-ish or fluid,
     * the cell takes the most frequent such state of the upper child layer (else of the lower one), fluids only where they are
     * the majority; otherwise it is air with the maximum sky and block light of the eight. Biome per column: the most frequent
     * of the 2x2 child columns' surface biomes. Null if all four children are null.
     */
    static @Nullable LodData downsample(final int level, final int nx, final int nz, final @Nullable LodData[] children, final int worldMinY, final int worldHeight) {
        boolean any = false;
        for (LodData c : children) {
            any |= c != null;
        }
        if (!any) {
            return null;
        }
        int cellMinY = Math.floorDiv(worldMinY, 1 << level);
        int height = Math.max(1, worldHeight >> level);
        int[] states = new int[256 * height];
        byte[] light = new byte[256 * height];
        Arrays.fill(states, AIR_ID);
        Arrays.fill(light, (byte)0xF0);
        Map<String, Integer> palette = new HashMap<>();
        java.util.List<String> keys = new java.util.ArrayList<>();
        short[] biomes = new short[256];
        int[] cand = new int[8];
        int[] cat = new int[8];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                LodData child = children[(z >> 3) * 2 + (x >> 3)];
                if (child == null) {
                    for (int y = 0; y < height; y++) {
                        states[index(x, y, z)] = AIR_ID;
                    }
                    biomes[z * 16 + x] = (short)biomeIndex(palette, keys, "minecraft:plains");
                    continue;
                }
                int cx0 = (x & 7) * 2;
                int cz0 = (z & 7) * 2;
                int childBase = child.cellMinY;
                for (int y = 0; y < height; y++) {
                    int cy0 = (cellMinY + y) * 2 - childBase;
                    int k = 0;
                    int solid = 0;
                    int fluid = 0;
                    int maxSky = 0;
                    int maxBlock = 0;
                    int leafSky = 0;
                    int leafBlock = 0;
                    for (int dy = 1; dy >= 0; dy--) {
                        for (int dz = 0; dz < 2; dz++) {
                            for (int dx = 0; dx < 2; dx++) {
                                int cy = cy0 + dy;
                                int id;
                                int li;
                                if (cy < 0) {
                                    id = FILLER_ID;
                                    li = 0;
                                } else if (cy >= child.height) {
                                    id = AIR_ID;
                                    li = 0xF0;
                                } else {
                                    int ci = index(cx0 + dx, cy, cz0 + dz);
                                    id = child.states[ci];
                                    li = child.light[ci] & 0xFF;
                                }
                                int c = id == FILLER_ID ? 2 : category(stateOf(id));
                                cand[k] = id;
                                cat[k] = c;
                                k++;
                                if (c == 2) {
                                    solid++;
                                } else if (c == 1) {
                                    fluid++;
                                }
                                if (c != 2) {
                                    maxSky = Math.max(maxSky, li >> 4 & 15);
                                    maxBlock = Math.max(maxBlock, li & 15);
                                }
                                else if (LEAF_LIGHT && id != FILLER_ID && stateOf(id).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
                                    leafSky = Math.max(leafSky, li >> 4 & 15);
                                    leafBlock = Math.max(leafBlock, li & 15);
                                }
                            }
                        }
                    }
                    int i = index(x, y, z);
                    // Round 3 (mc.lod.leafMin): a cell that is solid only thanks to leaves needs at least LEAF_MIN leaf children,
                    // so coarse canopies keep the gaps between trees (the 4-of-8 rule closes them: coverage overestimated).
                    int leafChildren = 0;
                    if (LEAF_MIN > 4) {
                        for (int c = 0; c < 8; c++) {
                            if (cat[c] == 2 && cand[c] != FILLER_ID && stateOf(cand[c]).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
                                leafChildren++;
                            }
                        }
                    }
                    boolean thinCanopy = leafChildren > 0 && solid - leafChildren + fluid < 4 && leafChildren < LEAF_MIN;
                    if (solid + fluid >= 4 && !thinCanopy) {
                        boolean wantFluid = fluid > solid;
                        int pick = pickMode(cand, cat, 0, 4, wantFluid ? 1 : 2);
                        if (pick == Integer.MIN_VALUE) {
                            pick = pickMode(cand, cat, 4, 8, wantFluid ? 1 : 2);
                        }
                        if (pick == Integer.MIN_VALUE) {
                            pick = pickMode(cand, cat, 0, 8, wantFluid ? 2 : 1);
                        }
                        if (!wantFluid && isSnowCover(pick)) {
                            // Round 1b: a snow cover on leaves stays leaves (a full snow cell would paint the canopy's sides
                            // white: thin snow layers are only a top tint in vanilla's image). Snow on the ground stays snow.
                            for (int c = 0; c < 8; c++) {
                                if (cat[c] == 2 && stateOf(cand[c]).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
                                    pick = cand[c];
                                    break;
                                }
                            }
                        }
                        if (!wantFluid && LEAVES_OVER_LOGS && pick != FILLER_ID && stateOf(pick).is(net.minecraft.tags.BlockTags.LOGS)) {
                            // Round 2: a trunk inside a canopy cell shows as a brown block on far tree tops; the canopy's
                            // visible surface is leaves, so leaves win over logs when the group has any.
                            for (int c = 0; c < 8; c++) {
                                if (cat[c] == 2 && cand[c] != FILLER_ID && stateOf(cand[c]).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
                                    pick = cand[c];
                                    break;
                                }
                            }
                        }
                        int rep = representative(pick);
                        if (!wantFluid && SNOW_TOPS && y > 0 && stateOf(pick).is(Blocks.SNOW)) {
                            // Round 2: only snow layers here (no full block, no leaves) right above leaves: a thin snow cover
                            // on the canopy, not a snow cube. The cell stays open and the leaves below become snowy.
                            boolean onlyLayers = true;
                            for (int c = 0; c < 8; c++) {
                                onlyLayers &= cat[c] != 2 || cand[c] != FILLER_ID && stateOf(cand[c]).is(Blocks.SNOW);
                            }
                            int below = index(x, y - 1, z);
                            if (onlyLayers && states[below] != FILLER_ID && stateOf(states[below]).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
                                states[below] = snowyLeaves(states[below]);
                                states[i] = AIR_ID;
                                light[i] = (byte)(maxSky << 4 | maxBlock);
                                continue;
                            }
                        }
                        if (!wantFluid && SNOW_TOPS && rep != FILLER_ID && stateOf(rep).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
                            // Round 2: leaves under snow (a snow layer among the eight, or the child already snowy) keep
                            // the leaves but carry the snowy marker: LodMesher paints their top faces with the snow texture
                            // (snow-dusted canopy: white tops, green sides, as vanilla's snow layers on leaves look).
                            boolean snowy = false;
                            for (int c = 0; c < 8; c++) {
                                int id = cand[c];
                                snowy |= id != FILLER_ID && (stateOf(id).is(Blocks.SNOW) || isSnowyLeaves(id));
                            }
                            if (snowy) {
                                rep = snowyLeaves(rep);
                            }
                        }
                        if (wantFluid && FLUID_LEVELS && rep != FILLER_ID) {
                            // Real surface: the upper child layer's highest fluid (half a cell plus its height), else the lower's.
                            float upper = -1.0F;
                            float lower = -1.0F;
                            for (int c = 0; c < 8; c++) {
                                if (cat[c] != 1) {
                                    continue;
                                }
                                int cdx = c & 1;
                                int cdz = (c >> 1) & 1;
                                if (c < 4) {
                                    int above = cy0 + 2;
                                    boolean covered = above >= 0 && above < child.height
                                        && category(stateOf(child.states[index(cx0 + cdx, above, cz0 + cdz)])) == 1;
                                    upper = Math.max(upper, fluidHeight(cand[c], covered));
                                } else {
                                    lower = Math.max(lower, fluidHeight(cand[c], false));
                                }
                            }
                            float frac = upper >= 0.0F ? 0.5F + 0.5F * upper : 0.5F * Math.max(0.0F, lower);
                            rep = fluidAt(rep, frac);
                        }
                        if (!wantFluid && ICE_LEVELS && isIce(rep)) {
                            // Ice surface height: upper child layer's highest ice (half a cell plus its height), else the lower's.
                            float upper = -1.0F;
                            float lower = -1.0F;
                            boolean upperSolid = false;
                            for (int c = 0; c < 8; c++) {
                                if (c < 4 && cat[c] == 2) {
                                    upperSolid = true;
                                }
                                if (!isIce(cand[c])) {
                                    continue;
                                }
                                if (c < 4) {
                                    upper = Math.max(upper, iceHeight(cand[c], level - 1));
                                } else {
                                    lower = Math.max(lower, iceHeight(cand[c], level - 1));
                                }
                            }
                            // Other solids in the upper layer (snow, shore blocks): keep the full cell.
                            float frac = upper >= 0.0F ? 0.5F + 0.5F * upper : upperSolid ? 1.0F : 0.5F * Math.max(0.0F, lower);
                            rep = iceAt(frac);
                        }
                        states[i] = rep;
                        light[i] = wantFluid ? (byte)(maxSky << 4 | maxBlock) : 0;
                        if (LEAF_LIGHT && !wantFluid && rep != FILLER_ID && stateOf(rep).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
                            // Round 8: leaf cells keep light (the brightest of their open and leaf children). Leaves do not occlude,
                            // so smooth lighting averages their own light into the canopy's faces: with 0 here every forest
                            // beyond level 0 was much darker than the real one in front of it (a visible line at the handoff).
                            light[i] = (byte)(Math.max(maxSky, leafSky) << 4 | Math.max(maxBlock, leafBlock));
                        }
                    } else {
                        states[i] = AIR_ID;
                        light[i] = (byte)(maxSky << 4 | maxBlock);
                    }
                }
                // Surface biome of the 2x2 child columns.
                String best = null;
                int bestCount = 0;
                Map<String, Integer> counts = new HashMap<>(4);
                for (int dz = 0; dz < 2; dz++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int ccx = cx0 + dx;
                        int ccz = cz0 + dz;
                        int top = 0;
                        for (int cy = child.height - 1; cy >= 0; cy--) {
                            int id = child.states[index(ccx, cy, ccz)];
                            if (id != AIR_ID && !isAir(id)) {
                                top = cy;
                                break;
                            }
                        }
                        String key = child.biomeKeys[child.biomeIndexAt(ccx, top, ccz)];
                        int cnt = counts.merge(key, 1, Integer::sum);
                        if (cnt > bestCount) {
                            bestCount = cnt;
                            best = key;
                        }
                    }
                }
                biomes[z * 16 + x] = (short)biomeIndex(palette, keys, best);
            }
        }
        LodData out = new LodData(level, nx, nz, cellMinY, height, states, light, keys.toArray(String[]::new), biomes);
        if (LodTrees.listsAt(level)) {
            if (level == 1) {
                for (LodData c : children) {
                    if (c != null && c.level == 0 && c.trees == null) {
                        c.trees = LodTrees.detect(c);
                    }
                }
            }
            out.trees = LodTrees.merge(children);
        }
        return out;
    }

    /**
     * Round 3: {@code parent} (a downsample of real children) with the quadrants whose child is missing ({@code children[q] ==
     * null}, index {@code dz * 2 + dx}) taken from the seed-generated node {@code gen} of the same level and position (columns,
     * cells, light and biomes). Same cell grid required.
     */
    static LodData overlay(final LodData parent, final LodData gen, final @Nullable LodData[] children) {
        int[] states = parent.states.clone();
        byte[] light = parent.light.clone();
        Map<String, Integer> palette = new HashMap<>();
        java.util.List<String> keys = new java.util.ArrayList<>();
        short[] biomes = new short[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                boolean fromGen = children[(z >> 3) * 2 + (x >> 3)] == null;
                LodData src = fromGen ? gen : parent;
                if (fromGen) {
                    for (int y = 0; y < parent.height; y++) {
                        int i = index(x, y, z);
                        states[i] = gen.states[i];
                        light[i] = gen.light[i];
                    }
                }
                biomes[z * 16 + x] = (short)biomeIndex(palette, keys, src.biomeKeys[src.biomes[z * 16 + x]]);
            }
        }
        LodData out = new LodData(parent.level, parent.nx, parent.nz, parent.cellMinY, parent.height, states, light, keys.toArray(String[]::new), biomes);
        if (parent.trees != null || gen.trees != null) {
            // Round 4: the parent's trees plus the generated node's trees standing in the quadrants taken from it.
            int c = 1 << parent.level;
            int ox = parent.nx * 16 * c;
            int oz = parent.nz * 16 * c;
            int[] pt = parent.trees == null ? LodTrees.NONE : parent.trees;
            int[] gt = gen.trees == null ? LodTrees.NONE : gen.trees;
            int[] t = java.util.Arrays.copyOf(pt, pt.length + gt.length);
            int n = pt.length;
            for (int i = 0; i + 2 < gt.length; i += 3) {
                int qx = (int)Math.floor((gt[i] * 0.5 - ox) / (8.0 * c));
                int qz = (int)Math.floor((gt[i + 1] * 0.5 - oz) / (8.0 * c));
                if (qx >= 0 && qx <= 1 && qz >= 0 && qz <= 1 && children[qz * 2 + qx] == null) {
                    t[n++] = gt[i];
                    t[n++] = gt[i + 1];
                    t[n++] = gt[i + 2];
                }
            }
            out.trees = java.util.Arrays.copyOf(t, n);
        }
        return out;
    }

    /**
     * Mesh-time view of coarse levels (LodRegion, level >= 1; the stored data is unchanged so further downsampling still sees
     * the canopy): leaves cells whose six neighbours inside the node are solid-ish (leaves, full opaque, filler). At these
     * distances the mipmapped cutout leaves are close to opaque, so the canopy's inner faces cannot be seen; meshing those
     * cells as air keeps the canopy shell and drops the volume's faces (docs/stage3/LOD-DESIGN.md §3).
     */
    /** Per state id: 0 unknown, 1 other, 2 solid render, 3 leaves (hiddenLeaves without touching each BlockState). */
    private static final byte[] KINDS = new byte[Block.BLOCK_STATE_REGISTRY.size()];

    private static int kind(final int id) {
        if (id < 0 || id >= KINDS.length) {
            BlockState st = stateOf(id);
            return st.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock ? 3 : st.isSolidRender() ? 2 : 1;
        }
        int k = KINDS[id];
        if (k == 0) {
            BlockState st = stateOf(id);
            k = st.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock ? 3 : st.isSolidRender() ? 2 : 1;
            KINDS[id] = (byte)k;
        }
        return k;
    }

    /** {@link #hiddenLeaves()} for one cell: a leaf cell inside the node with solid or leaf cells on all six sides. */
    boolean hiddenLeafAt(final int i) {
        int id = this.states[i];
        if (id == FILLER_ID || kind(id) != 3) {
            return false;
        }
        int x = i & 15;
        int z = (i >> 4) & 15;
        int y = i >> 8;
        if (x < 1 || x > 14 || z < 1 || z > 14 || y < 1 || y + 1 >= this.height) {
            return false;
        }
        return this.solidAt(i - 1) && this.solidAt(i + 1) && this.solidAt(i - 16) && this.solidAt(i + 16) && this.solidAt(i - 256) && this.solidAt(i + 256);
    }

    private boolean solidAt(final int i) {
        int id = this.states[i];
        return id == FILLER_ID || kind(id) >= 2;
    }

    boolean[] hiddenLeaves() {
        int n = this.states.length;
        boolean[] solid = new boolean[n];
        boolean[] leaves = new boolean[n];
        for (int i = 0; i < n; i++) {
            int id = this.states[i];
            if (id == FILLER_ID) {
                solid[i] = true;
                continue;
            }
            int k = kind(id);
            leaves[i] = k == 3;
            solid[i] = k >= 2;
        }
        boolean[] out = new boolean[n];
        for (int y = 1; y + 1 < this.height; y++) {
            for (int z = 1; z < 15; z++) {
                for (int x = 1; x < 15; x++) {
                    int i = index(x, y, z);
                    out[i] = leaves[i] && solid[i - 1] && solid[i + 1] && solid[i - 16] && solid[i + 16] && solid[i - 256] && solid[i + 256];
                }
            }
        }
        return out;
    }

    private static int biomeIndex(final Map<String, Integer> palette, final java.util.List<String> keys, final String key) {
        Integer idx = palette.get(key);
        if (idx == null) {
            idx = keys.size();
            palette.put(key, idx);
            keys.add(key);
        }
        return idx;
    }

    /** Most frequent id among {@code cand[from..to)} of category {@code want}; MIN_VALUE if none. Ties: first seen. */
    private static int pickMode(final int[] cand, final int[] cat, final int from, final int to, final int want) {
        int best = Integer.MIN_VALUE;
        int bestCount = 0;
        int n = 0;
        for (int a = from; a < to; a++) {
            n += cat[a] == want ? 1 : 0;
        }
        // each id counted once (at its first index, which also keeps the first-seen tie rule); an id holding more than half
        // of the group cannot be beaten
        outer:
        for (int a = from; a < to; a++) {
            int v = cand[a];
            if (cat[a] != want) {
                continue;
            }
            for (int b = from; b < a; b++) {
                if (cat[b] == want && cand[b] == v) {
                    continue outer;
                }
            }
            int count = 1;
            for (int b = a + 1; b < to; b++) {
                if (cat[b] == want && cand[b] == v) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                best = v;
                if (2 * count > n) {
                    break;
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------------------------------------------------------- codec

    byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(VERSION);
            out.writeByte(this.level);
            out.writeInt(this.nx);
            out.writeInt(this.nz);
            out.writeShort(this.cellMinY);
            out.writeShort(this.height);
            out.writeShort(this.biomeKeys.length);
            for (String key : this.biomeKeys) {
                out.writeUTF(key);
            }
            out.writeInt(this.biomes.length);
            boolean wide = this.biomeKeys.length > 255;
            for (short b : this.biomes) {
                if (wide) {
                    out.writeShort(b);
                } else {
                    out.writeByte(b);
                }
            }
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int runs = 0;
                    for (int y = 0; y < this.height; ) {
                        int i = index(x, y, z);
                        int y2 = y + 1;
                        while (y2 < this.height && this.states[index(x, y2, z)] == this.states[i] && this.light[index(x, y2, z)] == this.light[i]) {
                            y2++;
                        }
                        runs++;
                        y = y2;
                    }
                    writeVarInt(out, runs);
                    for (int y = 0; y < this.height; ) {
                        int i = index(x, y, z);
                        int y2 = y + 1;
                        while (y2 < this.height && this.states[index(x, y2, z)] == this.states[i] && this.light[index(x, y2, z)] == this.light[i]) {
                            y2++;
                        }
                        writeVarInt(out, this.states[i]);
                        out.writeByte(this.light[i]);
                        writeVarInt(out, y2 - y);
                        y = y2;
                    }
                }
            }
            int[] t = this.trees;
            writeVarInt(out, t == null ? 0 : t.length / 3 + 1);
            if (t != null) {
                for (int i = 0; i + 2 < t.length; i += 3) {
                    out.writeInt(t[i]);
                    out.writeInt(t[i + 1]);
                    out.writeInt(t[i + 2]);
                }
            }
            out.flush();
            byte[] raw = bytes.toByteArray();
            Deflater deflater = new Deflater(6);
            try {
                deflater.setInput(raw);
                deflater.finish();
                byte[] buf = new byte[Math.max(256, raw.length / 2)];
                ByteArrayOutputStream packed = new ByteArrayOutputStream(raw.length / 4 + 64);
                DataOutputStream header = new DataOutputStream(packed);
                header.writeInt(raw.length);
                while (!deflater.finished()) {
                    int len = deflater.deflate(buf);
                    packed.write(buf, 0, len);
                }
                return packed.toByteArray();
            } finally {
                deflater.end();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static LodData decode(final byte[] blob) throws IOException {
        int rawLength = (blob[0] & 0xFF) << 24 | (blob[1] & 0xFF) << 16 | (blob[2] & 0xFF) << 8 | blob[3] & 0xFF;
        if (rawLength <= 0 || rawLength > 64 << 20) {
            throw new IOException("bad LOD blob length " + rawLength);
        }
        byte[] raw = new byte[rawLength];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(blob, 4, blob.length - 4);
            int off = 0;
            while (off < rawLength) {
                int n = inflater.inflate(raw, off, rawLength - off);
                if (n == 0 && (inflater.finished() || inflater.needsInput())) {
                    break;
                }
                off += n;
            }
            if (off != rawLength) {
                throw new IOException("truncated LOD blob");
            }
        } catch (DataFormatException e) {
            throw new IOException(e);
        } finally {
            inflater.end();
        }
        java.io.ByteArrayInputStream bytes = new java.io.ByteArrayInputStream(raw);
        DataInputStream in = new DataInputStream(bytes);
        int version = in.readUnsignedByte();
        if (version != VERSION && version != 1) {
            throw new IOException("LOD blob version " + version);
        }
        int level = in.readUnsignedByte();
        int nx = in.readInt();
        int nz = in.readInt();
        int cellMinY = in.readShort();
        int height = in.readShort();
        int paletteSize = in.readUnsignedShort();
        String[] keys = new String[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            keys[i] = in.readUTF();
        }
        int biomeCount = in.readInt();
        short[] biomes = new short[biomeCount];
        boolean wide = paletteSize > 255;
        for (int i = 0; i < biomeCount; i++) {
            biomes[i] = wide ? in.readShort() : (short)in.readUnsignedByte();
        }
        int[] states = new int[256 * height];
        byte[] light = new byte[256 * height];
        // the column runs straight from the array (the stream's per-byte reads are synchronized: a third of decoding)
        int[] at = {rawLength - bytes.available()};
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int runs = readVarInt(raw, at);
                int y = 0;
                for (int r = 0; r < runs; r++) {
                    int id = readVarInt(raw, at);
                    if (at[0] >= rawLength) {
                        throw new java.io.EOFException();
                    }
                    byte li = raw[at[0]++];
                    int len = readVarInt(raw, at);
                    if (y + len > height) {
                        throw new IOException("LOD run overflow");
                    }
                    for (int k = 0, i = index(x, y, z); k < len; k++, i += 256) {
                        states[i] = id;
                        light[i] = li;
                    }
                    y += len;
                }
                if (y != height) {
                    throw new IOException("LOD column short: " + y + " of " + height);
                }
            }
        }
        in = new DataInputStream(new java.io.ByteArrayInputStream(raw, at[0], rawLength - at[0]));
        LodData out = new LodData(level, nx, nz, cellMinY, height, states, light, keys, biomes);
        if (version >= 2) {
            int n = readVarInt(in);
            if (n > 0) {
                int[] t = new int[3 * (n - 1)];
                for (int i = 0; i < t.length; i++) {
                    t[i] = in.readInt();
                }
                out.trees = t;
            }
        }
        return out;
    }

    private static void writeVarInt(final DataOutputStream out, int v) throws IOException {
        while ((v & ~0x7F) != 0) {
            out.writeByte(v & 0x7F | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    /** {@link #readVarInt(DataInputStream)} from {@code raw} at {@code at[0]} (advanced). */
    private static int readVarInt(final byte[] raw, final int[] at) throws IOException {
        int v = 0;
        int shift = 0;
        int p = at[0];
        while (true) {
            if (p >= raw.length) {
                throw new java.io.EOFException();
            }
            int b = raw[p++] & 0xFF;
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                at[0] = p;
                return v;
            }
            shift += 7;
            if (shift > 28) {
                throw new IOException("varint too long");
            }
        }
    }

    private static int readVarInt(final DataInputStream in) throws IOException {
        int v = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return v;
            }
            shift += 7;
            if (shift > 28) {
                throw new IOException("varint too long");
            }
        }
    }

    /**
     * {top, bottom}: top = highest cell (exclusive) holding anything but air (0 if none); bottom = lowest cell that is not
     * full opaque / filler in columns {@code [x0, x1] x [z0, z1]} ({@code height} if none). Slabs entirely below the lowest open
     * cell of a node and of its neighbours' adjacent edge columns cannot have a visible face (LodMesher).
     */
    int topCell() {
        for (int y = this.height - 1; y >= 0; y--) {
            for (int c = 0; c < 256; c++) {
                int id = this.states[y * 256 + c];
                if (id != AIR_ID && !isAir(id)) {
                    return y + 1;
                }
            }
        }
        return 0;
    }

    int lowestOpen(final int x0, final int x1, final int z0, final int z1) {
        for (int y = 0; y < this.height; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    int id = this.states[index(x, y, z)];
                    if (id != FILLER_ID && !stateOf(id).isSolidRender()) {
                        return y;
                    }
                }
            }
        }
        return this.height;
    }
}
