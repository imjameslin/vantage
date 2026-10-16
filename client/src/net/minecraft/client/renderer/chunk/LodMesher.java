package net.minecraft.client.renderer.chunk;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.core.SectionPos;
import org.jspecify.annotations.Nullable;

/**
 * Stage 3: meshes one LOD node with vanilla's {@link SectionCompiler} over a {@link LodRegion} (docs/stage3/LOD-DESIGN.md
 * §3). The node's cell grid is cut into vertical slabs of 16 cells (vanilla's section shape); each non-trivial slab is one
 * {@link Section}: the compiler's 28-byte BLOCK vertices of the SOLID, CUTOUT and TRANSLUCENT layers in the S1 facing-bucket
 * layout with their 64-quad clusters ({@link TerrainClusters#compute}: bounds in 1/16 cell), positions then scaled from
 * cells to blocks ({@code 2^level}). Origin = the slab's minimum corner in blocks.
 */
final class LodMesher {
    static final int STRIDE = 28;
    /** Round 4 stats: tree stand-in quads meshed (cumulative). */
    static final java.util.concurrent.atomic.AtomicLong TREE_QUADS = new java.util.concurrent.atomic.AtomicLong();

    /** One meshed slab: per layer (SOLID, CUTOUT, TRANSLUCENT) vertices and clusters, or null. */
    static final class Section {
        final int originX;
        final int originY;
        final int originZ;
        final int level;
        final @Nullable ByteBuffer[] vertices = new ByteBuffer[3];
        final int @Nullable [][] clusters = new int[3][];
        final int[] quads = new int[3];
        /** Round 2: layer stored as {@link LodCompact} records (48 bytes per quad) instead of BLOCK vertices. */
        final boolean[] compact = new boolean[3];

        Section(final int originX, final int originY, final int originZ, final int level) {
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.level = level;
        }

        long bytes() {
            long b = 0L;
            for (int layer = 0; layer < 3; layer++) {
                ByteBuffer v = this.vertices[layer];
                if (v != null) {
                    b += v.remaining();
                }
            }
            return b;
        }

        int quadCount() {
            return this.quads[0] + this.quads[1] + this.quads[2];
        }
    }

    private LodMesher() {
    }

    private static volatile float @Nullable [] snowUv;

    static void resetSprites() {
        snowUv = null;
        iceUv = null;
    }

    /** Ice sprite bounds {u0, u1, v0, v1}, then frosted ice ages 0..3 (4 floats each). */
    private static volatile float @Nullable [] iceUv;

    private static float @Nullable [] iceUv() {
        float[] uv = iceUv;
        if (uv == null) {
            try {
                var models = net.minecraft.client.Minecraft.getInstance().getModelManager().getBlockStateModelSet();
                uv = new float[20];
                for (int a = -1; a < 4; a++) {
                    net.minecraft.world.level.block.state.BlockState st = a < 0 ? net.minecraft.world.level.block.Blocks.ICE.defaultBlockState()
                        : net.minecraft.world.level.block.Blocks.FROSTED_ICE.defaultBlockState().setValue(net.minecraft.world.level.block.FrostedIceBlock.AGE, a);
                    net.minecraft.client.renderer.texture.TextureAtlasSprite s = models.getParticleMaterial(st).sprite();
                    int o = (a + 1) * 4;
                    uv[o] = s.getU0();
                    uv[o + 1] = s.getU1();
                    uv[o + 2] = s.getV0();
                    uv[o + 3] = s.getV1();
                }
                iceUv = uv;
            } catch (RuntimeException e) {
                return null;
            }
        }
        return uv;
    }

    /** True if node cell (x, y, z) (section-local x/z, node y) exists and is frosted ice (LodData.ICE_LEVELS' partial ice). */
    private static boolean frosted(final LodData node, final int x, final int y, final int z) {
        return x >= 0 && x <= 15 && z >= 0 && z <= 15 && y >= 0 && y < node.height
            && LodData.stateOf(node.states[LodData.index(x, y, z)]).is(net.minecraft.world.level.block.Blocks.FROSTED_ICE);
    }

    /**
     * Coarse levels (LodData.ICE_LEVELS): faces of frosted-ice cells drop their top edge to the cell's ice height
     * ((age + 1) / 4) and take the ice texture, so a coarse frozen lake sits at the real ice height. Before LodGreedy.
     */
    static void iceLevels(final ByteBuffer v, final int quads, final LodData node, final int slab) {
        float[] ice = null;
        for (int q = 0; q < quads; q++) {
            int at = q * 4 * STRIDE;
            float u = v.getFloat(at + 16);
            float w = v.getFloat(at + 20);
            if (ice == null) {
                ice = iceUv();
                if (ice == null) {
                    return;
                }
            }
            boolean inFrosted = false;
            for (int a = 1; a < 5 && !inFrosted; a++) {
                inFrosted = u >= ice[a * 4] && u <= ice[a * 4 + 1] && w >= ice[a * 4 + 2] && w <= ice[a * 4 + 3];
            }
            if (!inFrosted) {
                continue;
            }
            float cx = 0.0F;
            float cy = 0.0F;
            float cz = 0.0F;
            float minY = Float.MAX_VALUE;
            for (int k = 0; k < 4; k++) {
                cx += v.getFloat(at + k * STRIDE);
                float y = v.getFloat(at + k * STRIDE + 4);
                cy += y;
                cz += v.getFloat(at + k * STRIDE + 8);
                minY = Math.min(minY, y);
            }
            cx *= 0.25F;
            cy *= 0.25F;
            cz *= 0.25F;
            boolean xPlane = v.getFloat(at) == v.getFloat(at + STRIDE) && v.getFloat(at) == v.getFloat(at + 2 * STRIDE);
            boolean zPlane = v.getFloat(at + 8) == v.getFloat(at + STRIDE + 8) && v.getFloat(at + 8) == v.getFloat(at + 2 * STRIDE + 8);
            boolean yPlane = !xPlane && !zPlane;
            int base = slab * 16 - node.cellMinY;
            int cell;
            int ox = (int)Math.floor(cx);
            int oz = (int)Math.floor(cz);
            if (yPlane) {
                // Top face of the cell below (lowered), or a bottom face (left as is).
                int yy = Math.round(cy);
                if (!frosted(node, ox, base + yy - 1, oz)) {
                    continue;
                }
                cell = yy - 1;
            } else {
                cell = (int)Math.floor(minY);
                int ny = base + cell;
                if (xPlane) {
                    int px = Math.round(cx);
                    ox = frosted(node, px - 1, ny, oz) ? px - 1 : px;
                } else {
                    int pz = Math.round(cz);
                    oz = frosted(node, ox, ny, pz - 1) ? pz - 1 : pz;
                }
                if (!frosted(node, ox, ny, oz)) {
                    continue;
                }
            }
            float h = LodData.iceHeight(node.states[LodData.index(ox, base + cell, oz)], 1);
            float u0 = Float.MAX_VALUE;
            float v0 = Float.MAX_VALUE;
            for (int k = 0; k < 4; k++) {
                u0 = Math.min(u0, v.getFloat(at + k * STRIDE + 16));
                v0 = Math.min(v0, v.getFloat(at + k * STRIDE + 20));
            }
            for (int k = 0; k < 4; k++) {
                int a = at + k * STRIDE;
                if (v.getFloat(a + 4) >= cell + 1) {
                    v.putFloat(a + 4, cell + h);
                }
                v.putFloat(a + 16, v.getFloat(a + 16) == u0 ? ice[0] : ice[1]);
                v.putFloat(a + 20, v.getFloat(a + 20) == v0 ? ice[2] : ice[3]);
            }
        }
    }

    /** Snow block sprite bounds {u0, u1, v0, v1}; cleared with the LOD meshes after atlas/resource reloads. */
    private static float @Nullable [] snowUv() {
        float[] uv = snowUv;
        if (uv == null) {
            try {
                net.minecraft.client.renderer.texture.TextureAtlasSprite s = net.minecraft.client.Minecraft.getInstance().getModelManager().getBlockStateModelSet()
                    .getParticleMaterial(net.minecraft.world.level.block.Blocks.SNOW_BLOCK.defaultBlockState()).sprite();
                uv = new float[]{s.getU0(), s.getU1(), s.getV0(), s.getV1()};
                snowUv = uv;
            } catch (RuntimeException e) {
                return null;
            }
        }
        return uv;
    }

    /**
     * Round 2 (snow-dusted canopies): in coarse levels, the top face of a snowy-leaves cell ({@link LodData#isSnowyLeaves})
     * under an open cell gets the snow block's texture and untinted white colour, the sides stay leaves: the look of vanilla's
     * snow layers on spruce leaves (white tops, green sides) instead of a green canopy or white cubes. Quad count unchanged;
     * snow tops share one tile, so LodGreedy merges them like any top face. Positions are section-local cells here.
     */
    /**
     * Round 2: share of the leaves' colour mixed into a coarse snow top ({@code mc.lod.snowMix}, default 0.35): a coarse cell's
     * top stands for several tree tops whose snow covers only part of them (dark needles between the snow), so a pure white
     * top reads too white next to the real terrain (perceptual check at RD 32).
     */
    static final float SNOW_MIX = Float.parseFloat(System.getProperty("mc.lod.snowMix", "0.35"));

    /** The snow top's vertex colour (RGBA8 in memory order R, G, B, A) from the leaves' tinted colour. */
    static int snowColor(final int leaf) {
        int out = leaf & 0xFF000000;
        for (int sh = 0; sh < 24; sh += 8) {
            int c = leaf >>> sh & 0xFF;
            int m = Math.round(255.0F + (c - 255.0F) * SNOW_MIX);
            out |= (m & 0xFF) << sh;
        }
        return out;
    }

    /**
     * Round 3: share of white mixed into the side faces of coarse snowy-leaves cells ({@code mc.lod.snowSide}, default 0: measured worse at tau 8, perceptual dE 0.87 -> 1.26): a
     * coarse canopy cell stands for several snow-covered branch rings whose snowy tops show from the side too (real spruces
     * read lighter than plain needles from a distance).
     */
    static final float SNOW_SIDE = Float.parseFloat(System.getProperty("mc.lod.snowSide", "0"));

    /** Vertical face of a snowy-leaves cell (either cell touching the face): colour mixed towards white by {@link #SNOW_SIDE}. */
    private static void snowSide(final ByteBuffer v, final int at, final LodData node, final int slab) {
        float cx = 0.0F;
        float cy = 0.0F;
        float cz = 0.0F;
        for (int k = 0; k < 4; k++) {
            cx += v.getFloat(at + k * STRIDE);
            cy += v.getFloat(at + k * STRIDE + 4);
            cz += v.getFloat(at + k * STRIDE + 8);
        }
        cx *= 0.25F;
        cy *= 0.25F;
        cz *= 0.25F;
        boolean xPlane = v.getFloat(at) == v.getFloat(at + STRIDE) && v.getFloat(at) == v.getFloat(at + 2 * STRIDE);
        boolean zPlane = v.getFloat(at + 8) == v.getFloat(at + STRIDE + 8) && v.getFloat(at + 8) == v.getFloat(at + 2 * STRIDE + 8);
        if (!xPlane && !zPlane) {
            return;
        }
        int y = slab * 16 + (int)Math.floor(cy) - node.cellMinY;
        if (y < 0 || y >= node.height) {
            return;
        }
        boolean snowy = false;
        for (int s = -1; s <= 0 && !snowy; s++) {
            int x = xPlane ? Math.round(cx) + s : (int)Math.floor(cx);
            int z = zPlane ? Math.round(cz) + s : (int)Math.floor(cz);
            if (x >= 0 && x <= 15 && z >= 0 && z <= 15) {
                snowy = LodData.isSnowyLeaves(node.states[LodData.index(x, y, z)]);
            }
        }
        if (!snowy) {
            return;
        }
        for (int k = 0; k < 4; k++) {
            int a = at + k * STRIDE + 12;
            int c = v.getInt(a);
            int out = c & 0xFF000000;
            for (int sh = 0; sh < 24; sh += 8) {
                int ch = c >>> sh & 0xFF;
                out |= Math.min(255, Math.round(ch + (255 - ch) * SNOW_SIDE)) << sh;
            }
            v.putInt(a, out);
        }
    }

    static void snowTops(final ByteBuffer v, final int quads, final LodData node, final int slab) {
        float[] snow = null;
        for (int q = 0; q < quads; q++) {
            int at = q * 4 * STRIDE;
            float y = v.getFloat(at + 4);
            if (y != v.getFloat(at + STRIDE + 4) || y != v.getFloat(at + 2 * STRIDE + 4) || y != v.getFloat(at + 3 * STRIDE + 4) || y != Math.rint(y)) {
                if (SNOW_SIDE > 0.0F) {
                    snowSide(v, at, node, slab);
                }
                continue;
            }
            float cx = 0.0F;
            float cz = 0.0F;
            for (int k = 0; k < 4; k++) {
                cx += v.getFloat(at + k * STRIDE);
                cz += v.getFloat(at + k * STRIDE + 8);
            }
            int x = (int)Math.floor(cx * 0.25F);
            int z = (int)Math.floor(cz * 0.25F);
            int cy = slab * 16 + (int)y - 1 - node.cellMinY;
            if (x < 0 || x > 15 || z < 0 || z > 15 || cy < 0 || cy + 1 >= node.height) {
                continue;
            }
            if (!LodData.isSnowyLeaves(node.states[LodData.index(x, cy, z)])) {
                continue;
            }
            int above = node.states[LodData.index(x, cy + 1, z)];
            if (above == LodData.FILLER_ID || LodData.stateOf(above).isSolidRender() || LodData.stateOf(above).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
                continue;
            }
            if (snow == null) {
                snow = snowUv();
                if (snow == null) {
                    return;
                }
            }
            float u0 = Float.MAX_VALUE;
            float v0 = Float.MAX_VALUE;
            for (int k = 0; k < 4; k++) {
                u0 = Math.min(u0, v.getFloat(at + k * STRIDE + 16));
                v0 = Math.min(v0, v.getFloat(at + k * STRIDE + 20));
            }
            for (int k = 0; k < 4; k++) {
                int a = at + k * STRIDE;
                v.putFloat(a + 16, v.getFloat(a + 16) == u0 ? snow[0] : snow[1]);
                v.putFloat(a + 20, v.getFloat(a + 20) == v0 ? snow[2] : snow[3]);
                v.putInt(a + 12, snowColor(v.getInt(a + 12)));
            }
        }
    }

    /** Diagnostics: {@code -Dmc.lod.dumpDir=<dir>} appends up to 3000 raw slab-layers per level (before LodGreedy) for offline analysis. */
    private static final String DUMP_DIR = System.getProperty("mc.lod.dumpDir");
    private static final java.util.concurrent.atomic.AtomicIntegerArray DUMPED = new java.util.concurrent.atomic.AtomicIntegerArray(8);

    private static void dump(final int level, final int layer, final ByteBuffer copy, final int vertexCount) {
        if (DUMP_DIR == null || DUMPED.getAndIncrement(Math.min(7, level)) >= 3000) {
            return;
        }
        try {
            java.nio.file.Path d = java.nio.file.Path.of(DUMP_DIR);
            java.nio.file.Files.createDirectories(d);
            byte[] data = new byte[vertexCount * STRIDE];
            copy.duplicate().get(data);
            java.nio.ByteBuffer head = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            head.putInt(level).putInt(layer).putInt(vertexCount / 4).putInt(TerrainGreedy.atlasWidth()).putInt(TerrainGreedy.atlasHeight());
            synchronized (DUMPED) {
                try (var out = java.nio.file.Files.newOutputStream(d.resolve("lod-l" + level + ".bin"), java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND)) {
                    out.write(head.array());
                    out.write(data);
                }
            }
        } catch (java.io.IOException e) {
            Lod.LOGGER.warn("[lod] dump failed", e);
        }
    }

    /**
     * Meshes {@code nodes[4]} (3x3 same-level nodes, null = missing). Slabs above the node's top cell or entirely below the
     * lowest open (not full opaque) cell of the node and of its neighbours' adjacent edge columns are skipped: they have no
     * face that can be seen.
     */
    static final boolean TINT_DEBUG = Lod.flag("mc.lod.tintDebug", false);
    static final boolean TOP_DIAG = Boolean.getBoolean("mc.lod.topDiag");
    /** {@code mc.lod.floatDiag}: logs columns whose top run of blocks hangs in the air, well above the columns beside it. */
    static final boolean FLOAT_DIAG = Boolean.getBoolean("mc.lod.floatDiag");
    private static final java.util.concurrent.atomic.AtomicInteger FLOAT_LOGGED = new java.util.concurrent.atomic.AtomicInteger();

    private static void floatDiag(final LodData node) {
        int[] top = new int[256];
        for (int col = 0; col < 256; col++) {
            top[col] = -1;
            for (int y = node.height - 1; y >= 0; y--) {
                if (!LodData.isAir(node.states[y * 256 + col])) {
                    top[col] = y;
                    break;
                }
            }
        }
        for (int col = 0; col < 256; col++) {
            int t = top[col];
            if (t < 0) {
                continue;
            }
            int b = t;
            while (b > 0 && !LodData.isAir(node.states[(b - 1) * 256 + col])) {
                b--;
            }
            int gap = 0;
            while (b - 1 - gap >= 0 && LodData.isAir(node.states[(b - 1 - gap) * 256 + col])) {
                gap++;
            }
            int x = col & 15, z = col >> 4;
            int around = -1;
            if (x > 0) around = Math.max(around, top[col - 1]);
            if (x < 15) around = Math.max(around, top[col + 1]);
            if (z > 0) around = Math.max(around, top[col - 16]);
            if (z < 15) around = Math.max(around, top[col + 16]);
            if (gap >= 3 && b > around + 2 && (node.level > 0 || gap >= 8) && (node.states[t * 256 + col] == LodData.FILLER_ID || LodData.stateOf(node.states[t * 256 + col]).isSolidRender()) && FLOAT_LOGGED.incrementAndGet() <= 600) {
                int size = 16 << node.level;
                System.out.println("[lod-float] L" + node.level + " node " + node.nx + "," + node.nz + " (block " + (node.nx * size) + ","
                    + (node.nz * size) + ") gen=" + node.generated + " col " + x + "," + z + " run cells " + b + ".." + t + " gap " + gap
                    + " around " + around + " top=" + LodData.stateOf(node.states[t * 256 + col]) + " bottom=" + LodData.stateOf(node.states[b * 256 + col]));
            }
        }
    }
    static final boolean WATER_SNAP = Lod.flag("mc.lod.waterSnap", true);
    static final boolean WATER_BORDER_CUT = Lod.flag("mc.lod.waterBorderCut", true);
    static final java.util.concurrent.atomic.AtomicLong WATER_BORDER_CUTS = new java.util.concurrent.atomic.AtomicLong();

    /** {@code mc.lod.waterSkirt} (cells, 0 = off): depth of the water skirts at node borders. */
    static final float WATER_SKIRT = Float.parseFloat(System.getProperty("mc.lod.waterSkirt", "0"));

    /**
     * Round 8: a short water wall under every water-surface edge on the node's border. A level stores the surface height in
     * ninths of its cell, so two levels' surfaces meet with a step of up to a few tenths of a block; through that step the sea
     * bed showed without water over it: thin dark lines across the ocean along the level borders.
     */
    private static ByteBuffer waterSkirts(final ByteBuffer v, final int quads) {
        int base = v.position();
        int[] edges = new int[quads * 4];
        int n = 0;
        for (int q = 0; q < quads; q++) {
            int at = base + q * 4 * STRIDE;
            float y0 = v.getFloat(at + 4);
            if (Math.abs(v.getFloat(at + STRIDE + 4) - y0) > 1.0E-4F || Math.abs(v.getFloat(at + 2 * STRIDE + 4) - y0) > 1.0E-4F
                || Math.abs(v.getFloat(at + 3 * STRIDE + 4) - y0) > 1.0E-4F) {
                continue;
            }
            for (int k = 0; k < 4; k++) {
                int a = at + k * STRIDE;
                int b = at + (k + 1 & 3) * STRIDE;
                float ax = v.getFloat(a), az = v.getFloat(a + 8), bx = v.getFloat(b), bz = v.getFloat(b + 8);
                if (ax <= 0.001F && bx <= 0.001F || ax >= 15.999F && bx >= 15.999F || az <= 0.001F && bz <= 0.001F || az >= 15.999F && bz >= 15.999F) {
                    edges[n++] = q * 4 + k;
                }
            }
        }
        if (n == 0) {
            return v;
        }
        ByteBuffer out = ByteBuffer.allocateDirect(quads * 4 * STRIDE + n * 8 * STRIDE).order(ByteOrder.nativeOrder());
        ByteBuffer dup = v.duplicate();
        dup.limit(base + quads * 4 * STRIDE);
        out.put(dup);
        byte[] va = new byte[STRIDE];
        byte[] vb = new byte[STRIDE];
        for (int e = 0; e < n; e++) {
            int q = edges[e] >> 2;
            int k = edges[e] & 3;
            int a = base + (q * 4 + k) * STRIDE;
            int b = base + (q * 4 + (k + 1 & 3)) * STRIDE;
            v.get(a, va);
            v.get(b, vb);
            float ya = v.getFloat(a + 4) - WATER_SKIRT;
            float yb = v.getFloat(b + 4) - WATER_SKIRT;
            // Both windings: the wall is seen from either side of the step.
            for (int side = 0; side < 2; side++) {
                int p = out.position();
                out.put(side == 0 ? va : vb).put(side == 0 ? vb : va).put(side == 0 ? vb : va).put(side == 0 ? va : vb);
                out.putFloat(p + 2 * STRIDE + 4, side == 0 ? yb : ya);
                out.putFloat(p + 3 * STRIDE + 4, side == 0 ? ya : yb);
            }
        }
        out.flip();
        return out;
    }

    /** {@code mc.lod.waterOverlap} (cells, 0 = off): how far water quads reach over their node's border. */
    static final float WATER_OVERLAP = Float.parseFloat(System.getProperty("mc.lod.waterOverlap", "0"));

    /** Diagnostic ({@code -Dmc.lod.skipVerify}): every slab compiled the vanilla way (no SKIP_ENCLOSED, no LodLighting) and ours, compared. */
    static final boolean SKIP_VERIFY = Boolean.getBoolean("mc.lod.skipVerify");
    static final java.util.concurrent.atomic.AtomicLong VERIFY_SLABS = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong VERIFY_BAD = new java.util.concurrent.atomic.AtomicLong();

    private static void verify(final SectionCompiler compiler, final SectionPos pos, final LodRegion region, final SectionBufferBuilderPack pack) {
        List<byte[]> want = new ArrayList<>();
        region.lightCacheOn = false;   // the reference is vanilla's own path (no enclosed skip, vanilla's light cache)
        SectionCompiler.Results a;
        try {
            a = compiler.compile(pos, region, VertexSorting.ORTHOGRAPHIC_Z, pack, false);
        } finally {
            region.lightCacheOn = true;
        }
        try {
            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                want.add(bytes(a.renderedLayers.get(layer)));
            }
        } finally {
            a.release();
            pack.clearAll();
        }
        SectionCompiler.Results b = compiler.compile(pos, region, VertexSorting.ORTHOGRAPHIC_Z, pack, true);
        try {
            boolean same = true;
            int l = 0;
            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                same &= java.util.Arrays.equals(want.get(l++), bytes(b.renderedLayers.get(layer)));
            }
            long n = VERIFY_SLABS.incrementAndGet();
            if (!same && VERIFY_BAD.incrementAndGet() <= 5) {
                System.out.println("[lod] skipVerify MISMATCH at " + pos + " level " + region.levelOf());
            }
            if (n % 20000L == 0L) {
                System.out.println("[lod] skipVerify slabs " + n + " mismatches " + VERIFY_BAD.get());
            }
        } finally {
            b.release();
            pack.clearAll();
        }
    }

    private static byte @Nullable [] bytes(final @Nullable MeshData mesh) {
        if (mesh == null || mesh.drawState().vertexCount() == 0) {
            return null;
        }
        ByteBuffer v = mesh.vertexBuffer().duplicate();
        byte[] out = new byte[mesh.drawState().vertexCount() * mesh.drawState().format().getVertexSize()];
        v.get(out);
        return out;
    }

    static List<Section> mesh(final LodRegion region, final LodData[] nodes, final SectionCompiler compiler, final SectionBufferBuilderPack pack) {
        return mesh(region, nodes, compiler, pack, false);
    }

    /** {@code flat}: round 4 flat-lit level 0 (Lod.FLAT0_PX): merged with the coarse levels' colour tolerance. */
    static List<Section> mesh(final LodRegion region, final LodData[] nodes, final SectionCompiler compiler, final SectionBufferBuilderPack pack, final boolean flat) {
        List<Section> out = new ArrayList<>();
        LodData node = nodes[4];
        if (node.level == 0 && region.stripTrees) {
            for (LodData d : nodes) {
                if (d != null && d.trees == null) {
                    d.trees = LodTrees.detect(d);
                }
            }
        }
        int scale = 1 << node.level;
        int top = node.topCell();
        if (top == 0) {
            return out;
        }
        int bottom = node.lowestOpen(0, 15, 0, 15);
        if (nodes[1] != null) {
            bottom = Math.min(bottom, nodes[1].lowestOpen(0, 15, 15, 15));
        }
        if (nodes[7] != null) {
            bottom = Math.min(bottom, nodes[7].lowestOpen(0, 15, 0, 0));
        }
        if (nodes[3] != null) {
            bottom = Math.min(bottom, nodes[3].lowestOpen(15, 15, 0, 15));
        }
        if (nodes[5] != null) {
            bottom = Math.min(bottom, nodes[5].lowestOpen(0, 0, 0, 15));
        }
        // LodRegion.SKIRT: the border walls reach below the lowest open cell.
        int lowCell = node.cellMinY + Math.max(0, bottom - 1 - LodRegion.skirtDepth(node.level));
        int highCell = node.cellMinY + top;
        int firstSlab = Math.floorDiv(lowCell, 16);
        int lastSlab = Math.floorDiv(highCell, 16);
        float[] topSeen = null;
        for (int slab = firstSlab; slab <= lastSlab; slab++) {
            SectionPos pos = SectionPos.of(node.nx, slab, node.nz);
            if (SKIP_VERIFY) {
                verify(compiler, pos, region, pack);
            }
            SectionCompiler.Results results = compiler.compile(pos, region, VertexSorting.ORTHOGRAPHIC_Z, pack);
            try {
                Section section = null;
                // Round 4: tree stand-ins of this slab (LodTrees), appended to the CUTOUT layer below.
                ByteBuffer trees = null;
                int[] treeCount = new int[1];
                if (region.stripTrees) {
                    trees = LodTrees.billboards(node, slab, region, null, treeCount);
                    if (trees != null) {
                        trees.flip();
                        TREE_QUADS.addAndGet(treeCount[0]);
                    }
                }
                for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                    MeshData mesh = results.renderedLayers.get(layer);
                    boolean addTrees = layer == ChunkSectionLayer.CUTOUT && trees != null && trees.remaining() > 0;
                    boolean treesOnly = false;
                    int vertexCount;
                    ByteBuffer copy;
                    TerrainMeshInfo info;
                    MeshData.DrawState state = mesh == null ? null : mesh.drawState();
                    if (state == null || state.format() != DefaultVertexFormat.BLOCK || state.primitiveTopology() != PrimitiveTopology.QUADS || state.vertexCount() == 0) {
                        if (!addTrees) {
                            continue;
                        }
                        copy = trees;
                        vertexCount = trees.remaining() / STRIDE;
                        info = null;
                        addTrees = false;
                        treesOnly = true;
                    } else {
                        vertexCount = state.vertexCount();
                        ByteBuffer src = mesh.vertexBuffer();
                        copy = ByteBuffer.allocateDirect(vertexCount * STRIDE).order(ByteOrder.nativeOrder());
                        ByteBuffer dup = src.duplicate();
                        dup.limit(dup.position() + vertexCount * STRIDE);
                        copy.put(dup).flip();
                        info = layer.translucent() ? null : results.terrainInfo.get(layer);
                    }
                    if (node.level > 0 && layer == ChunkSectionLayer.SOLID && LodData.SNOW_TOPS) {
                        snowTops(copy, vertexCount / 4, node, slab);
                    }
                    if (TINT_DEBUG && layer != ChunkSectionLayer.TRANSLUCENT) {
                        // Diagnostic: far terrain in a flat colour (level 0 red, seed-generated level 0 blue, coarser yellow) to tell it from real sections.
                        for (int v = 0; v < vertexCount; v++) {
                            int at = copy.position() + v * STRIDE + 12;
                            if (node.level == 0 && node.generated) copy.put(at, (byte)64).put(at + 1, (byte)96).put(at + 2, (byte)255);
                            else copy.put(at, (byte)255).put(at + 1, (byte)(node.level == 0 ? 48 : 255)).put(at + 2, (byte)48);
                        }
                    }
                    if (node.level > 0 && layer == ChunkSectionLayer.TRANSLUCENT && WATER_SNAP && !treesOnly) {
                        // Round 8: water surfaces at the real height (a block's 8/9) at every level. A level stores the height in
                        // ninths of its cell (up to cell/18 off), so two levels' surfaces met with a small step; the sea bed
                        // showed through it as a thin dashed line across the ocean along the level borders.
                        int c = 1 << node.level;
                        for (int v = 0; v < vertexCount; v++) {
                            int at = copy.position() + v * STRIDE + 4;
                            float yc = slab * 16 + copy.getFloat(at);
                            if (Math.abs(yc - Math.round(yc)) > 0.002F) {
                                double blocks = Math.floor((double)yc * c - 8.0 / 9.0 + 0.5) + 8.0 / 9.0;
                                copy.putFloat(at, (float)(blocks / c - slab * 16));
                            }
                        }
                    }
                    if (node.level > 0 && layer == ChunkSectionLayer.TRANSLUCENT && LodData.ICE_LEVELS && !treesOnly) {
                        iceLevels(copy, vertexCount / 4, node, slab);
                    }
                    if (!treesOnly) {
                        dump(node.level, layer.ordinal(), copy, vertexCount);
                    }
                    if (TOP_DIAG && node.level == 0 && !treesOnly && !layer.translucent()) {
                        if (topSeen == null) { topSeen = new float[256]; java.util.Arrays.fill(topSeen, -1e9F); }
                        for (int q = 0; q + 3 < vertexCount; q += 4) {
                            float x0 = 1e9F, x1 = -1e9F, z0 = 1e9F, z1 = -1e9F, y0 = 1e9F, y1 = -1e9F;
                            for (int v = 0; v < 4; v++) {
                                int at = copy.position() + (q + v) * STRIDE;
                                float x = copy.getFloat(at), y = copy.getFloat(at + 4), z = copy.getFloat(at + 8);
                                x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y);
                                z0 = Math.min(z0, z); z1 = Math.max(z1, z);
                            }
                            if (y1 - y0 > 0.001F) continue;
                            for (int z = Math.max(0, (int)Math.floor(z0 + 0.25F)); z < 16 && z + 0.5F < z1; z++) {
                                for (int x = Math.max(0, (int)Math.floor(x0 + 0.25F)); x < 16 && x + 0.5F < x1; x++) {
                                    topSeen[z * 16 + x] = Math.max(topSeen[z * 16 + x], slab * 16 + y1);
                                }
                            }
                        }
                    }
                    // Lit merge (Lod.LIT_MERGE): needs the compact records; a layer that falls back to BLOCK vertices is redone
                    // without it.
                    ByteBuffer base = copy;
                    int baseCount = vertexCount;
                    TerrainMeshInfo baseInfo = info;
                    ByteBuffer records = null;
                    boolean skip = false;
                    for (int attempt = 0; attempt < 2; attempt++) {
                        boolean lit = attempt == 0 && Lod.LIT_MERGE && !layer.translucent() && LodCompact.ENABLED;
                        copy = base;
                        vertexCount = baseCount;
                        info = baseInfo;
                        LodGreedy.LIT_TABLES.get().clear();
                        if (Lod.MERGE && !treesOnly) {
                            // Round 1b (LodGreedy): back-to-back opaque pairs out, greedy rectangles in; then re-bucketed below.
                            ByteBuffer reduced = LodGreedy.reduce(copy, vertexCount / 4, layer == ChunkSectionLayer.SOLID && Lod.PAIRS,
                                node.level == 0 && !flat ? Lod.MERGE_TOL0 : Lod.MERGE_TOL, lit);
                            if (reduced != copy) {
                                copy = reduced;
                                vertexCount = reduced.remaining() / STRIDE;
                                info = null;
                                if (vertexCount == 0) {
                                    skip = true;
                                    break;
                                }
                            }
                        }
                        if (node.level > 0 && Lod.TILE_REPEAT && layer != ChunkSectionLayer.TRANSLUCENT && !treesOnly) {
                            // Round 3: coarse faces repeat their texture per block (LodGreedy.repeatTiles).
                            LodGreedy.repeatTiles(copy, vertexCount / 4, 1 << node.level, TerrainGreedy.atlasWidth(), TerrainGreedy.atlasHeight());
                        }
                        if (layer == ChunkSectionLayer.TRANSLUCENT && WATER_BORDER_CUT && !treesOnly) {
                            // Round 8: no water faces in the node's border planes. They are side faces toward a neighbour whose
                            // same-level data has no water there (other source, other height); the node drawn next door has, so
                            // the face showed through the surface as a thin dark line across the ocean.
                            int kept = 0;
                            int quads = vertexCount / 4;
                            int base0 = copy.position();
                            for (int q = 0; q < quads; q++) {
                                int at = base0 + q * 4 * STRIDE;
                                boolean x0 = true, x1 = true, z0 = true, z1 = true;
                                for (int k = 0; k < 4; k++) {
                                    float x = copy.getFloat(at + k * STRIDE);
                                    float z = copy.getFloat(at + k * STRIDE + 8);
                                    x0 &= x <= 0.001F;
                                    x1 &= x >= 15.999F;
                                    z0 &= z <= 0.001F;
                                    z1 &= z >= 15.999F;
                                }
                                if (x0 || x1 || z0 || z1) {
                                    WATER_BORDER_CUTS.incrementAndGet();
                                    continue;
                                }
                                if (kept != q) {
                                    for (int i = 0; i < 4 * STRIDE; i += 4) {
                                        copy.putInt(base0 + kept * 4 * STRIDE + i, copy.getInt(at + i));
                                    }
                                }
                                kept++;
                            }
                            if (kept != quads) {
                                vertexCount = kept * 4;
                                if (copy == base) {
                                    baseCount = vertexCount;
                                }
                                copy.limit(base0 + vertexCount * STRIDE);
                                info = null;
                                if (vertexCount == 0) {
                                    skip = true;
                                    break;
                                }
                            }
                        }
                        if (layer == ChunkSectionLayer.TRANSLUCENT && WATER_OVERLAP > 0.0F && !treesOnly) {
                            // Round 8: water reaches a little over the node's border. Neighbouring nodes (other levels, merged
                            // quads) share the border but not its vertices, so single pixels along it missed both surfaces and
                            // showed the sea bed: dotted dark lines across the ocean.
                            if (WATER_SKIRT > 0.0F) {
                                ByteBuffer skirted = waterSkirts(copy, vertexCount / 4);
                                if (skirted != copy) {
                                    copy = skirted;
                                    vertexCount = skirted.remaining() / STRIDE;
                                    info = null;
                                }
                            }
                            for (int v = 0; v < vertexCount; v++) {
                                int at = copy.position() + v * STRIDE;
                                float x = copy.getFloat(at);
                                float z = copy.getFloat(at + 8);
                                if (x <= 0.001F) {
                                    copy.putFloat(at, x - WATER_OVERLAP);
                                } else if (x >= 15.999F) {
                                    copy.putFloat(at, x + WATER_OVERLAP);
                                }
                                if (z <= 0.001F) {
                                    copy.putFloat(at + 8, z - WATER_OVERLAP);
                                } else if (z >= 15.999F) {
                                    copy.putFloat(at + 8, z + WATER_OVERLAP);
                                }
                            }
                        }
                        if (addTrees) {
                            ByteBuffer joined = ByteBuffer.allocateDirect(copy.remaining() + trees.remaining()).order(ByteOrder.nativeOrder());
                            joined.put(copy.duplicate()).put(trees.duplicate()).flip();
                            copy = joined;
                            vertexCount = joined.remaining() / STRIDE;
                            info = null;
                        }
                        if (info == null || !info.bucketed() || info.clusters() == null) {
                            // TRANSLUCENT (classified only by the compiler) or no bucket layout: reorder our copy (no sort needed:
                            // LOD water is drawn unsorted, docs/stage3/LOD-DESIGN.md §5.3).
                            info = TerrainMeshInfo.analyze(copy, vertexCount, STRIDE, 0, true, !layer.translucent());
                        }
                        if (info == null || info.clusters() == null) {
                            skip = true;
                            break;
                        }
                        records = LodCompact.encode(copy, vertexCount / 4, node.level, TerrainGreedy.atlasWidth(), TerrainGreedy.atlasHeight());
                        if (records != null) {
                            LodCompact.LAYERS_COMPACT.incrementAndGet();
                        } else if (LodCompact.ENABLED) {
                            LodCompact.LAYERS_BLOCK.incrementAndGet();
                        }
                        if (records != null || LodGreedy.LIT_TABLES.get().isEmpty()) {
                            break;
                        }
                    }
                    LodGreedy.LIT_TABLES.get().clear();
                    if (skip) {
                        continue;
                    }
                    if (records == null && scale != 1) {
                        for (int v = 0; v < vertexCount; v++) {
                            int at = v * STRIDE;
                            copy.putFloat(at, copy.getFloat(at) * scale);
                            copy.putFloat(at + 4, copy.getFloat(at + 4) * scale);
                            copy.putFloat(at + 8, copy.getFloat(at + 8) * scale);
                        }
                    }
                    if (section == null) {
                        section = new Section(node.nx * 16 * scale, slab * 16 * scale, node.nz * 16 * scale, node.level);
                    }
                    int l = layer.ordinal();
                    section.vertices[l] = records != null ? records : copy;
                    section.compact[l] = records != null;
                    section.clusters[l] = info.clusters();
                    section.quads[l] = vertexCount / 4;
                }
                if (section != null) {
                    out.add(section);
                }
            } finally {
                results.release();
                pack.clearAll();
            }
        }
        if (FLOAT_DIAG) {
            floatDiag(node);
        }
        if (TOP_DIAG && node.level == 0) {
            int missing = 0; String sample = "";
            for (int col = 0; col < 256; col++) {
                for (int y = node.height - 1; y >= 0; y--) {
                    int id = node.states[y * 256 + col];
                    if (id != LodData.FILLER_ID && !LodData.stateOf(id).isSolidRender()) continue;
                    float want = node.cellMinY + y + 1;
                    if (topSeen == null || Math.abs(topSeen[col] - want) > 0.01F && topSeen[col] < want) {
                        missing++;
                        if (sample.isEmpty()) {
                            int up = y + 1 < node.height ? node.states[(y + 1) * 256 + col] : -1;
                            sample = " e.g. col " + (col & 15) + "," + (col >> 4) + " y " + (node.cellMinY + y) + " " + (id == LodData.FILLER_ID ? "FILLER" : LodData.stateOf(id))
                                + " above " + (up < 0 ? "none" : up == LodData.FILLER_ID ? "FILLER" : String.valueOf(LodData.stateOf(up))) + " seen " + (topSeen == null ? "null" : topSeen[col])
                                + " slabs " + firstSlab + ".." + lastSlab;
                        }
                    }
                    break;
                }
            }
            if (missing > 0) System.out.println("[lod] topMissing node " + node.nx + "," + node.nz + " count " + missing + sample);
        }
        return out;
    }
}
