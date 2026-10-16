package net.minecraft.client.renderer.chunk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Stage 3 round 4: tree stand-ins for the coarse LOD levels (docs/stage3/LOD-DESIGN.md §3.2, CONTRACT 16.S3-LOD-R4).
 *
 * <p>At levels {@code 1..}{@link #MAX_LEVEL} a canopy of 2- or 4-block cubes is the costliest and the least faithful part of
 * the far forest (results/STAGE3-R3.md: the default's perceptual error is "2/4-block cubes instead of 1-block spruce rings").
 * Instead, every tree is kept as a record (position, trunk base, crown, radius, kind, snow) and drawn as ONE camera-facing
 * (cylindrical) billboard quad textured with an impostor sprite of a vanilla-shaped tree (tools/LodImpostorGen.java renders the
 * sprites from the vanilla textures at build time into the block atlas: {@code block/lod_tree_*}); the tree's own leaves and
 * trunk cells are meshed as air there (the canopy cubes disappear). Conifer sprites carry their constant leaf tint, broadleaf
 * sprites are tinted per tree like the leaves (BlockColors at the tree).
 *
 * <p>Records: 3 ints per tree: axis x and z in half blocks (2 x + 1 for a one-block trunk, 2 x + 2 for a 2x2 trunk), then
 * {@code (base + 2048) & 0xFFF | (top - base) << 12 (6 bits) | (top - crownLow) << 18 (5 bits) | radius << 23 (3 bits) | kind << 26
 * (5 bits) | snowy << 31}; base = lowest trunk block, top = highest leaves block (blocks). Level-1 lists are detected on the
 * level-0 children during the downsample ({@link #detect}); coarser lists are the children's lists concatenated (only up to
 * {@link #MAX_LEVEL}); the seed generator records its own trees.
 */
final class LodTrees {
    /** {@code mc.lod.trees} (false since round 8): stand-ins at the coarse levels 1..{@link #MAX_LEVEL}. A stand-in is a side
     * view of the tree; from the air (flying, hills) the real canopy shows its bright top, so forests with stand-ins were much
     * darker than the real forest in front of them (a visible line where the levels meet). Canopy cubes match in brightness and
     * measured no slower (jungle and swamp flights, 1 % lows within noise). */
    static final boolean ENABLED = Lod.flag("mc.lod.trees", false);
    /** {@code mc.lod.treeMax} (2): coarsest level drawn with stand-ins (and carrying tree lists). */
    static final int MAX_LEVEL = Math.max(1, Math.min(5, Integer.getInteger("mc.lod.treeMax", 2)));
    /** {@code mc.lod.treeMin} (1): finest level drawn with stand-ins (0 = also level 0; lists are always detected at level 1). */
    static final int MIN_LEVEL = Math.max(0, Math.min(MAX_LEVEL, Integer.getInteger("mc.lod.treeMin", 2)));
    /** {@code mc.lod.treeScale} (1.4: best vista perceptual score of 1.0 / 1.2 / 1.4 / 1.6): billboard width factor. */
    static final float WIDTH_SCALE = Float.parseFloat(System.getProperty("mc.lod.treeScale", "1.4"));
    /** {@code mc.lod.treesFlat0} (false): flat-lit (far, Lod.FLAT0_PX) level-0 nodes also draw their trees as stand-ins. */
    static final boolean FLAT0 = Lod.flag("mc.lod.treesFlat0", false);

    static boolean drawsLevel(final int level) {
        return ENABLED && level >= MIN_LEVEL && level <= MAX_LEVEL;
    }

    static boolean listsAt(final int level) {
        return ENABLED && level >= 1 && level <= MAX_LEVEL;
    }

    // ------------------------------------------------------------------------------------------------------------- kinds

    static final int SPRUCE = 0;
    static final int PINE = 1;
    static final int OAK = 2;
    static final int BIRCH = 3;
    static final int JUNGLE = 4;
    static final int ACACIA = 5;
    static final int DARK_OAK = 6;
    static final int MANGROVE = 7;
    static final int CHERRY = 8;
    static final int AZALEA = 9;
    static final int FLOWERING_AZALEA = 10;
    static final int PALE_OAK = 11;
    static final int KINDS = 12;

    /**
     * Sprite per kind (and snowy variant for conifers): name, sprite width / height in blocks, sprite bottom relative to the
     * anchor (conifers: trunk base, broadleaf: crown top - 3), canonical crown radius, canonical height (conifers: top + 1 -
     * base; broadleaf: crown rows). Keep in sync with tools/LodImpostorGen.java.
     */
    record Sprite(String name, float width, float height, float radius, float canonHeight, boolean conifer, float bottom) {
        Sprite(final String name, final float width, final float height, final float radius, final float canonHeight, final boolean conifer) {
            this(name, width, height, radius, canonHeight, conifer, conifer ? 0.0F : -1.0F);
        }

        /** The build's sprite metrics (assets/minecraft/lod/tree_sprites.txt, written by the generator with the cropped sprites). */
        Sprite measured() {
            float[] m = METRICS.get(this.name);
            return m == null ? this : new Sprite(this.name, m[0], m[1], this.radius, this.canonHeight, this.conifer, m[2]);
        }
    }

    private static final java.util.Map<String, float[]> METRICS = new java.util.HashMap<>();

    static {
        try (java.io.InputStream in = LodTrees.class.getResourceAsStream("/assets/minecraft/lod/tree_sprites.txt")) {
            if (in != null) {
                for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                    String[] f = line.trim().split("\\s+");
                    if (f.length == 4 && !f[0].startsWith("#")) {
                        METRICS.put(f[0], new float[]{Float.parseFloat(f[1]), Float.parseFloat(f[2]), Float.parseFloat(f[3])});
                    }
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            Lod.LOGGER.warn("[lod] tree sprite metrics unreadable", e);
        }
    }

    static final Sprite[] SPRITES = new Sprite[KINDS];
    static final Sprite[] SNOWY = new Sprite[KINDS];
    static final Block[] LEAVES = new Block[KINDS];

    static {
        SPRITES[SPRUCE] = new Sprite("lod_tree_spruce", 10.0F, 13.0F, 3.0F, 9.0F, true).measured();
        SNOWY[SPRUCE] = new Sprite("lod_tree_spruce_snowy", 10.0F, 13.0F, 3.0F, 9.0F, true).measured();
        SPRITES[PINE] = new Sprite("lod_tree_pine", 10.0F, 13.0F, 1.0F, 10.0F, true).measured();
        SNOWY[PINE] = new Sprite("lod_tree_pine_snowy", 10.0F, 13.0F, 1.0F, 10.0F, true).measured();
        String[] broad = {null, null, "oak", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "azalea", "flowering_azalea", "pale_oak"};
        for (int k = OAK; k < KINDS; k++) {
            SPRITES[k] = new Sprite("lod_tree_" + broad[k], 7.0F, 5.0F, 2.0F, 4.0F, false).measured();
            SNOWY[k] = SPRITES[k];
        }
        LEAVES[SPRUCE] = Blocks.SPRUCE_LEAVES;
        LEAVES[PINE] = Blocks.SPRUCE_LEAVES;
        LEAVES[OAK] = Blocks.OAK_LEAVES;
        LEAVES[BIRCH] = Blocks.BIRCH_LEAVES;
        LEAVES[JUNGLE] = Blocks.JUNGLE_LEAVES;
        LEAVES[ACACIA] = Blocks.ACACIA_LEAVES;
        LEAVES[DARK_OAK] = Blocks.DARK_OAK_LEAVES;
        LEAVES[MANGROVE] = Blocks.MANGROVE_LEAVES;
        LEAVES[CHERRY] = Blocks.CHERRY_LEAVES;
        LEAVES[AZALEA] = Blocks.AZALEA_LEAVES;
        LEAVES[FLOWERING_AZALEA] = Blocks.FLOWERING_AZALEA_LEAVES;
        LEAVES[PALE_OAK] = Blocks.PALE_OAK_LEAVES;
    }

    /** Kind of a leaves block (spruce: decided later between spruce and pine), or -1. */
    static int kindOf(final Block leaves) {
        for (int k = 0; k < KINDS; k++) {
            if (k != PINE && LEAVES[k] == leaves) {
                return k;
            }
        }
        return -1;
    }

    // ----------------------------------------------------------------------------------------------------------- records

    static int pack(final int base, final int top, final int crownLow, final int radius, final int kind, final boolean snowy) {
        return (base + 2048) & 0xFFF | Math.max(0, Math.min(63, top - base)) << 12 | Math.max(0, Math.min(31, top - crownLow)) << 18
            | Math.max(0, Math.min(7, radius)) << 23 | (kind & 31) << 26 | (snowy ? 1 : 0) << 31;
    }

    static int base(final int p) {
        return (p & 0xFFF) - 2048;
    }

    static int top(final int p) {
        return base(p) + (p >>> 12 & 63);
    }

    static int crownLow(final int p) {
        return top(p) - (p >>> 18 & 31);
    }

    static int radius(final int p) {
        return p >>> 23 & 7;
    }

    static int kind(final int p) {
        return p >>> 26 & 31;
    }

    static boolean snowy(final int p) {
        return p >>> 31 != 0;
    }

    static final int[] NONE = new int[0];

    /** Concatenation of the children's lists (null children / lists skipped). */
    static int[] merge(final @Nullable LodData[] children) {
        int n = 0;
        for (LodData c : children) {
            if (c != null && c.trees != null) {
                n += c.trees.length;
            }
        }
        int[] out = new int[n];
        int at = 0;
        for (LodData c : children) {
            if (c != null && c.trees != null) {
                System.arraycopy(c.trees, 0, out, at, c.trees.length);
                at += c.trees.length;
            }
        }
        return out;
    }

    // --------------------------------------------------------------------------------------------------------- detection

    private static boolean isLeaves(final int id) {
        return id != LodData.FILLER_ID && id != LodData.AIR_ID && LodData.stateOf(id).getBlock() instanceof LeavesBlock;
    }

    private static final java.util.concurrent.ConcurrentHashMap<Block, Boolean> LOGS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Logs by registry name (tags are not bound before a world loads: headless tests, early meshes). */
    private static boolean isLog(final int id) {
        if (id == LodData.FILLER_ID || id == LodData.AIR_ID) {
            return false;
        }
        Block b = LodData.stateOf(id).getBlock();
        return LOGS.computeIfAbsent(b, k -> {
            String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(k).getPath();
            return !path.startsWith("stripped_") && (path.endsWith("_log") || path.endsWith("_stem")) || path.equals("mangrove_roots");
        });
    }

    /** Snow layers / snow blocks (coarse cells of snow layers on a canopy) inside a crown. */
    private static boolean isSnowCover(final int id) {
        return id != LodData.FILLER_ID && id != LodData.AIR_ID && (LodData.stateOf(id).is(Blocks.SNOW) || LodData.stateOf(id).is(Blocks.SNOW_BLOCK));
    }

    private static boolean isSnowLayer(final int id) {
        return id != LodData.FILLER_ID && id != LodData.AIR_ID && LodData.stateOf(id).is(Blocks.SNOW);
    }

    /**
     * Trees of a level-0 node: a trunk is a vertical run of logs standing on a non-tree block whose top log has leaves among
     * the 3x3 cells around and above it; 2x2 trunks are one tree (min corner). Crown: top = highest leaves cell in the 3x3
     * column above the trunk, low = lowest row with leaves within Chebyshev distance 3 of the axis, radius = the median over
     * crown rows and the four axis directions of the leaves run length from the trunk (dense forests: crowns touch, so runs
     * are capped per kind). Only the node's own cells are read (crowns crossing the border are measured on the inside).
     */
    static int[] detect(final LodData n) {
        if (n.level != 0) {
            return NONE;
        }
        int[] out = new int[48];
        int count = 0;
        int h = n.height;
        boolean[] trunkTop = new boolean[256 * h];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                for (int y = h - 2; y >= 1; y--) {
                    int id = n.states[LodData.index(x, y, z)];
                    if (!isLog(id)) {
                        continue;
                    }
                    int above = n.states[LodData.index(x, y + 1, z)];
                    if (isLog(above)) {
                        continue;
                    }
                    boolean leaves = isLeaves(above);
                    for (int dz = -1; dz <= 1 && !leaves; dz++) {
                        for (int dx = -1; dx <= 1 && !leaves; dx++) {
                            int xx = x + dx;
                            int zz = z + dz;
                            if (xx < 0 || xx > 15 || zz < 0 || zz > 15) {
                                continue;
                            }
                            for (int dy = 0; dy <= 1 && !leaves; dy++) {
                                leaves = isLeaves(n.states[LodData.index(xx, y + dy, zz)]);
                            }
                        }
                    }
                    if (leaves) {
                        trunkTop[LodData.index(x, y, z)] = true;
                    }
                }
            }
        }
        int[] runs = new int[64];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                for (int y = h - 2; y >= 1; y--) {
                    if (!trunkTop[LodData.index(x, y, z)]) {
                        continue;
                    }
                    // 2x2 trunks: keep the min corner only.
                    if ((x > 0 && trunkTop[LodData.index(x - 1, y, z)]) || (z > 0 && trunkTop[LodData.index(x, y, z - 1)])) {
                        continue;
                    }
                    boolean wide = x < 15 && z < 15 && trunkTop[LodData.index(x + 1, y, z)] && trunkTop[LodData.index(x, y, z + 1)];
                    int base = y;
                    while (base > 0 && isLog(n.states[LodData.index(x, base - 1, z)])) {
                        base--;
                    }
                    if (base == 0 || isLeaves(n.states[LodData.index(x, base - 1, z)]) || n.states[LodData.index(x, base - 1, z)] == LodData.AIR_ID) {
                        continue;   // floating logs / branches of another tree
                    }
                    // Crown top: highest leaves in the 3x3 above the trunk.
                    int top = y;
                    int leafId = -1;
                    for (int yy = y; yy < Math.min(h, y + 8); yy++) {
                        boolean any = false;
                        for (int dz = -1; dz <= 1; dz++) {
                            for (int dx = -1; dx <= 1; dx++) {
                                int xx = x + dx;
                                int zz = z + dz;
                                if (xx >= 0 && xx <= 15 && zz >= 0 && zz <= 15) {
                                    int id = n.states[LodData.index(xx, yy, zz)];
                                    if (isLeaves(id)) {
                                        any = true;
                                        top = yy;
                                        leafId = id;
                                    }
                                }
                            }
                        }
                        if (!any && yy > y + 1) {
                            break;
                        }
                    }
                    if (leafId < 0) {
                        continue;
                    }
                    int kind = kindOf(LodData.stateOf(leafId).getBlock());
                    if (kind < 0) {
                        continue;
                    }
                    // Crown low and radius.
                    int low = top;
                    int nr = 0;
                    for (int yy = top; yy >= base; yy--) {
                        boolean any = false;
                        int[] dirs = {1, 0, -1, 0, 0, 1, 0, -1};
                        for (int d = 0; d < 4; d++) {
                            int len = 0;
                            for (int s = 1; s <= 6; s++) {
                                int xx = x + dirs[2 * d] * s + (wide && dirs[2 * d] > 0 ? 1 : 0);
                                int zz = z + dirs[2 * d + 1] * s + (wide && dirs[2 * d + 1] > 0 ? 1 : 0);
                                if (xx < 0 || xx > 15 || zz < 0 || zz > 15) {
                                    len = -1;
                                    break;
                                }
                                if (!isLeaves(n.states[LodData.index(xx, yy, zz)])) {
                                    break;
                                }
                                len = s;
                            }
                            if (len > 0) {
                                any = true;
                                if (nr < runs.length) {
                                    runs[nr++] = len;
                                }
                            } else if (len == 0) {
                                for (int dz = -1; dz <= 1 && !any; dz++) {
                                    for (int dx = -1; dx <= 1 && !any; dx++) {
                                        int xx = x + dx;
                                        int zz = z + dz;
                                        any = (dx != 0 || dz != 0) && xx >= 0 && xx <= 15 && zz >= 0 && zz <= 15 && isLeaves(n.states[LodData.index(xx, yy, zz)]);
                                    }
                                }
                            }
                        }
                        if (any) {
                            low = yy;
                        }
                    }
                    int radius;
                    if (nr == 0) {
                        radius = 1;
                    } else {
                        Arrays.sort(runs, 0, nr);
                        radius = runs[Math.min(nr - 1, (nr * 3) / 4)];
                    }
                    if (kind == SPRUCE && !wide && radius <= 1 && top - low <= 5) {
                        kind = PINE;
                    }
                    int cap = kind == PINE ? 2 : wide ? 6 : kind == SPRUCE ? 3 : kind == DARK_OAK || kind == PALE_OAK || kind == JUNGLE ? 4 : 3;
                    radius = Math.max(1, Math.min(cap, radius));
                    boolean snowy = top + 1 < h && isSnowLayer(n.states[LodData.index(x, top + 1, z)]);
                    for (int dz = -1; dz <= 1 && !snowy; dz++) {
                        for (int dx = -1; dx <= 1 && !snowy; dx++) {
                            int xx = x + dx;
                            int zz = z + dz;
                            if (xx >= 0 && xx <= 15 && zz >= 0 && zz <= 15) {
                                for (int yy = top; yy <= Math.min(h - 1, top + 1) && !snowy; yy++) {
                                    snowy = isSnowLayer(n.states[LodData.index(xx, yy, zz)]);
                                }
                            }
                        }
                    }
                    int wx = (n.nx * 16 + x) * 2 + (wide ? 2 : 1);
                    int wz = (n.nz * 16 + z) * 2 + (wide ? 2 : 1);
                    int cy = n.cellMinY;
                    if (count + 3 > out.length) {
                        out = Arrays.copyOf(out, out.length * 2);
                    }
                    out[count++] = wx;
                    out[count++] = wz;
                    out[count++] = pack(cy + base, cy + top, cy + low, radius, kind, snowy);
                    break;   // one tree per column
                }
            }
        }
        return Arrays.copyOf(out, count);
    }

    // ------------------------------------------------------------------------------------------------------- mesh time

    /**
     * Mesh-time strip mask of {@code node} (level >= 1) from the tree lists of the 3x3 nodes: leaves / log cells inside a
     * tree's crown cylinder (radius + 1 blocks around the axis, crown low .. top + 1) or its trunk column become air.
     */
    static boolean[] stripMask(final LodData node, final @Nullable LodData[] nodes3x3) {
        int c = 1 << node.level;
        int n = node.states.length;
        boolean[] out = new boolean[n];
        int ox = node.nx * 16 * c;
        int oz = node.nz * 16 * c;
        boolean any = false;
        for (LodData other : nodes3x3) {
            if (other == null || other.trees == null) {
                continue;
            }
            int[] t = other.trees;
            for (int i = 0; i + 2 < t.length; i += 3) {
                double ax = t[i] * 0.5;
                double az = t[i + 1] * 0.5;
                int p = t[i + 2];
                double r = radius(p) + 1.0;
                if (ax + r < ox || ax - r > ox + 16 * c || az + r < oz || az - r > oz + 16 * c) {
                    continue;
                }
                int y0 = Math.floorDiv(crownLow(p), c) - node.cellMinY;
                int y1 = Math.floorDiv(top(p) + 1, c) - node.cellMinY;
                int tb = Math.floorDiv(base(p), c) - node.cellMinY;
                int x0 = Math.max(0, (int)Math.floor((ax - r - ox) / c));
                int x1 = Math.min(15, (int)Math.floor((ax + r - ox) / c));
                int z0 = Math.max(0, (int)Math.floor((az - r - oz) / c));
                int z1 = Math.min(15, (int)Math.floor((az + r - oz) / c));
                int axc = (int)Math.floor((ax - 0.25 - ox) / c);
                int azc = (int)Math.floor((az - 0.25 - oz) / c);
                for (int y = Math.max(0, Math.min(tb, y0)); y <= Math.min(node.height - 1, y1); y++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int x = x0; x <= x1; x++) {
                            int idx = LodData.index(x, y, z);
                            int id = node.states[idx];
                            if (y >= y0 && (isLeaves(id) || isSnowCover(id))) {
                                out[idx] = true;
                                any = true;
                            } else if (x == axc && z == azc && isLog(id)) {
                                out[idx] = true;
                                any = true;
                            }
                        }
                    }
                }
            }
        }
        return any ? out : null;
    }

    private static volatile float @Nullable [][] spriteUv;
    private static volatile float @Nullable [][] snowyUv;

    /** Atlas bounds {u0, u1, v0, v1} of every kind's sprite (null if a sprite is missing: the kind keeps its cubes). */
    private static float @Nullable [] uv(final int kind, final boolean snowy) {
        float[][] uv = snowy ? snowyUv : spriteUv;
        if (uv == null) {
            float[][] a = new float[KINDS][];
            float[][] b = new float[KINDS][];
            try {
                net.minecraft.client.renderer.texture.TextureAtlas atlas = net.minecraft.client.Minecraft.getInstance().getAtlasManager()
                    .getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS);
                net.minecraft.client.renderer.texture.TextureAtlasSprite missing = atlas.missingSprite();
                for (int k = 0; k < KINDS; k++) {
                    for (int s = 0; s < 2; s++) {
                        Sprite sp = s == 0 ? SPRITES[k] : SNOWY[k];
                        net.minecraft.client.renderer.texture.TextureAtlasSprite t = atlas.getSprite(net.minecraft.resources.Identifier.withDefaultNamespace("block/" + sp.name()));
                        float[] q = t == null || t == missing ? null : new float[]{t.getU0(), t.getU1(), t.getV0(), t.getV1()};
                        (s == 0 ? a : b)[k] = q;
                    }
                }
            } catch (RuntimeException e) {
                Lod.LOGGER.warn("[lod] tree sprites unavailable", e);
                return null;
            }
            spriteUv = a;
            snowyUv = b;
            uv = snowy ? b : a;
        }
        return uv[kind];
    }

    /** Resource reload: sprite positions change. */
    static void resetSprites() {
        spriteUv = null;
        snowyUv = null;
    }

    static boolean spritesAvailable() {
        return uv(SPRUCE, false) != null;
    }

    /**
     * Appends one billboard quad (four 28-byte BLOCK vertices, section-local cell coordinates of slab {@code slab}) per tree of
     * {@code node} whose base lies in the slab to {@code dst} (null = new buffer); returns the buffer (position at the end) or
     * {@code dst} if no tree was added. The quad's CPU corners are diagonal ((ax - hw, y, az - hw) -> (ax + hw, y, az + hw)): a
     * non-planar-axis quad (facing bucket UNASSIGNED: never facing-culled) whose box contains the billboard at every rotation;
     * LodCompact recognizes the shape and stores it as a billboard record (TerrainVertexMsl: rotated toward the camera).
     */
    static @Nullable ByteBuffer billboards(final LodData node, final int slab, final LodRegion region, final @Nullable ByteBuffer dst, final int[] count) {
        int[] t = node.trees;
        if (t == null || t.length == 0) {
            return dst;
        }
        int c = 1 << node.level;
        float inv = 1.0F / c;
        int ox = node.nx * 16 * c;
        int oz = node.nz * 16 * c;
        int slabY0 = slab * 16 * c;
        ByteBuffer out = dst;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        net.minecraft.client.color.block.BlockColors colors = net.minecraft.client.Minecraft.getInstance().getBlockColors();
        for (int i = 0; i + 2 < t.length; i += 3) {
            int p = t[i + 2];
            int kind = kind(p);
            if (kind >= KINDS) {
                continue;
            }
            boolean snowy = snowy(p);
            Sprite sp = snowy ? SNOWY[kind] : SPRITES[kind];
            float[] uv = uv(kind, snowy);
            if (uv == null) {
                continue;
            }
            float ax = t[i] * 0.5F - ox;
            float az = t[i + 1] * 0.5F - oz;
            float scaleH = (radius(p) + 0.5F) / (sp.radius() + 0.5F) * WIDTH_SCALE;
            float bottom;
            float height;
            if (sp.conifer()) {
                float scaleV = (top(p) + 1 - base(p)) / sp.canonHeight();
                bottom = base(p) + sp.bottom() * scaleV;
                height = sp.height() * scaleV;
            } else {
                float scaleV = Math.max(0.75F, (top(p) - crownLow(p) + 1) / sp.canonHeight());
                bottom = top(p) + 1 - 4.0F * scaleV + sp.bottom() * scaleV;
                height = sp.height() * scaleV;
            }
            if (bottom < slabY0 || bottom >= slabY0 + 16 * c) {
                continue;
            }
            float hw = sp.width() * 0.5F * scaleH;
            // Cell units, section-local; snapped to the compact 1/32-cell grid.
            float x0 = snap((ax - hw) * inv);
            float x1 = snap((ax + hw) * inv);
            float z0 = snap((az - hw) * inv);
            float z1 = snap((az + hw) * inv);
            float y0 = snap((bottom - slabY0) * inv);
            float y1 = snap((bottom + height - slabY0) * inv);
            float axc = snap(ax * inv);
            float azc = snap(az * inv);
            if (y0 < 0.0F || y0 > 31.0F || axc < 0.0F || axc >= 31.9F || azc < 0.0F || azc >= 31.9F || hw * inv > 60.0F || (y1 - y0) > 60.0F) {
                continue;   // anchor outside the compact range (never for trees owned by the node)
            }
            x0 = axc - snap(hw * inv);
            x1 = axc + snap(hw * inv);
            z0 = azc - snap(hw * inv);
            z1 = azc + snap(hw * inv);
            int color = 0xFFFFFFFF;
            if (!sp.conifer()) {
                BlockState leaves = LEAVES[kind].defaultBlockState();
                net.minecraft.client.color.block.BlockTintSource src = colors.getTintSource(leaves, 0);
                if (src != null) {
                    pos.set((int)Math.floor(ax * inv) + node.nx * 16, Math.floorDiv(top(p), c), (int)Math.floor(az * inv) + node.nz * 16);
                    int rgb = src.colorInWorld(leaves, region, pos);
                    color = 0xFF000000 | rgb & 0xFFFFFF;
                }
            }
            // BLOCK colour bytes in memory order R, G, B, A.
            int abgr = 0xFF000000 | (color & 0xFF) << 16 | (color & 0xFF00) | (color >> 16 & 0xFF);
            pos.set((int)Math.floor(ax * inv) + node.nx * 16, Math.floorDiv(top(p) + 1, c), (int)Math.floor(az * inv) + node.nz * 16);
            // Light above the crown: leaves / filler cells store none (LodData), so take the brightest of the cells above.
            int sky = 0;
            int blk = 0;
            for (int k = 0; k < 6 && sky < 15; k++) {
                sky = Math.max(sky, region.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos));
                blk = Math.max(blk, region.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos));
                pos.move(0, 1, 0);
            }
            short lu = (short)(blk << 4);
            short lv = (short)(sky << 4);
            if (out == null || out.remaining() < 4 * LodMesher.STRIDE) {
                ByteBuffer n = ByteBuffer.allocateDirect(Math.max(64 * 4 * LodMesher.STRIDE, out == null ? 0 : out.capacity() * 2)).order(ByteOrder.nativeOrder());
                if (out != null) {
                    out.flip();
                    n.put(out);
                }
                out = n;
            }
            // Corners: 0 = (x0, y0, z0) u0 v1, 1 = (x1, y0, z1) u1 v1, 2 = (x1, y1, z1) u1 v0, 3 = (x0, y1, z0) u0 v0.
            vertex(out, x0, y0, z0, abgr, uv[0], uv[3], lu, lv);
            vertex(out, x1, y0, z1, abgr, uv[1], uv[3], lu, lv);
            vertex(out, x1, y1, z1, abgr, uv[1], uv[2], lu, lv);
            vertex(out, x0, y1, z0, abgr, uv[0], uv[2], lu, lv);
            count[0]++;
        }
        return out;
    }

    private static float snap(final float v) {
        return Math.round(v * 32.0F) / 32.0F;
    }

    private static void vertex(final ByteBuffer b, final float x, final float y, final float z, final int abgr, final float u, final float v, final short lu, final short lv) {
        b.putFloat(x).putFloat(y).putFloat(z).putInt(abgr).putFloat(u).putFloat(v).putShort(lu).putShort(lv);
    }

    /** True if the four corners at {@code at} have the billboard shape (see {@link #billboards}). */
    static boolean isBillboard(final float[] x, final float[] y, final float[] z) {
        return y[0] == y[1] && y[2] == y[3] && y[2] > y[0] && x[0] == x[3] && z[0] == z[3] && x[1] == x[2] && z[1] == z[2] && x[1] > x[0] && z[1] > z[0]
            && x[1] - x[0] == z[1] - z[0];
    }
}
