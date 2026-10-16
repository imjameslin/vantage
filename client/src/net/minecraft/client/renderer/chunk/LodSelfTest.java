package net.minecraft.client.renderer.chunk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Stage 3 headless checks of the LOD data layer (tools/LodTest.java, tools/lod-test.sh; no game, no GPU): the level-0 build
 * from a capture (sealing rules, light kept only where read), the codec round trip, the 2x2x2 downsample rules, the store's
 * region files (persist, reload, eviction), key packing and the level formula. Public only so the test launcher (another
 * class loader) can call {@link #run}; nothing in the game calls it.
 */
public final class LodSelfTest {
    private int checks;
    private int failures;

    private LodSelfTest() {
    }

    private void check(final String what, final boolean ok) {
        this.checks++;
        if (!ok) {
            this.failures++;
            System.out.println("FAIL: " + what);
        }
    }

    /** Runs every check; returns the number of failures (0 = pass) and prints a summary. Needs a bootstrapped registry. */
    public static int run(final Path tempDir) throws Exception {
        LodSelfTest t = new LodSelfTest();
        t.levelZero();
        t.downsample();
        t.store(tempDir);
        t.keysAndLevels();
        t.generation();
        t.trees();
        System.out.println("LodSelfTest: " + t.checks + " checks, " + t.failures + " failures");
        return t.failures;
    }

    static final int SECTIONS = 24;
    static final int MIN_SECTION = -4;

    /** A synthetic chunk: stone to y 59 with an ore, dirt 60-62, grass 63, a dark cave y 20-24, a lit pit, a pond, flowers. */
    private static LodData.Capture syntheticChunk(final int cx, final int cz, final int groundY, final boolean snow) {
        LodData.Capture c = new LodData.Capture();
        c.cx = cx;
        c.cz = cz;
        c.minSectionY = MIN_SECTION;
        c.sectionCount = SECTIONS;
        c.dense = new BlockState[SECTIONS][];
        c.sky = new byte[SECTIONS][];
        c.block = new byte[SECTIONS][];
        c.biomes = new String[SECTIONS][];
        c.hasSkyLight = true;
        for (int s = 0; s < SECTIONS; s++) {
            BlockState[] d = new BlockState[4096];
            byte[] sky = new byte[2048];
            boolean any = false;
            for (int y = 0; y < 16; y++) {
                int wy = (MIN_SECTION + s) * 16 + y;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState st;
                        int skyLevel = 0;
                        if (wy < groundY - 3) {
                            st = Blocks.STONE.defaultBlockState();
                            if (wy == 10 && x == 8 && z == 8) {
                                st = Blocks.DIAMOND_ORE.defaultBlockState();
                            }
                            if (wy == 10 && x == 0 && z == 8) {
                                st = Blocks.COAL_ORE.defaultBlockState();
                            }
                            if (wy >= 20 && wy <= 24 && x >= 4 && x <= 11 && z >= 4 && z <= 11) {
                                st = Blocks.AIR.defaultBlockState();   // dark cave
                            }
                        } else if (wy < groundY) {
                            st = Blocks.DIRT.defaultBlockState();
                        } else if (wy == groundY) {
                            st = x == 2 && z == 2 ? Blocks.WATER.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
                        } else {
                            st = Blocks.AIR.defaultBlockState();
                            skyLevel = 15;
                            if (wy == groundY + 1 && x == 5 && z == 5) {
                                st = Blocks.POPPY.defaultBlockState();
                            }
                            if (snow && wy == groundY + 1 && !(x == 5 && z == 5)) {
                                st = Blocks.SNOW.defaultBlockState();
                            }
                        }
                        d[(y * 16 + z) * 16 + x] = st;
                        any |= !st.isAir();
                        int i = (y * 16 + z) * 16 + x;
                        sky[i >> 1] |= (byte)(skyLevel << ((i & 1) << 2));
                    }
                }
            }
            c.dense[s] = any ? d : null;
            c.sky[s] = sky;
            String[] b = new String[64];
            Arrays.fill(b, s < 6 ? "minecraft:dripstone_caves" : "minecraft:plains");
            c.biomes[s] = b;
        }
        return c;
    }

    private static int id(final BlockState s) {
        return Block.BLOCK_STATE_REGISTRY.getId(s);
    }

    private void levelZero() throws Exception {
        LodData.Capture c = syntheticChunk(3, -7, 63, false);
        LodData d = LodData.fromCapture(c, true);
        int base = -64;
        this.check("height", d.height == 384 && d.cellMinY == -64 && d.level == 0 && d.nx == 3 && d.nz == -7);
        this.check("dark cave air sealed", d.state(8, 22 - base, 8) == LodData.FILLER_ID);
        this.check("hidden ore becomes filler", d.state(8, 10 - base, 8) == LodData.FILLER_ID);
        this.check("ore on the chunk border kept", d.state(0, 10 - base, 8) == id(Blocks.COAL_ORE.defaultBlockState()));
        this.check("surface grass kept", d.state(7, 63 - base, 7) == id(Blocks.GRASS_BLOCK.defaultBlockState()));
        this.check("dirt under the grass becomes filler", d.state(7, 61 - base, 7) == LodData.FILLER_ID);
        this.check("sky-lit air kept", d.state(7, 70 - base, 7) == LodData.AIR_ID && (d.light[LodData.index(7, 70 - base, 7)] & 0xFF) == 0xF0);
        this.check("opaque cells store no light", d.light[LodData.index(7, 63 - base, 7)] == 0);
        this.check("flower kept with its light", d.state(5, 64 - base, 5) == id(Blocks.POPPY.defaultBlockState())
            && (d.light[LodData.index(5, 64 - base, 5)] & 0xF0) == 0xF0);
        this.check("water kept", d.state(2, 63 - base, 2) == id(Blocks.WATER.defaultBlockState()));
        this.check("biome quart below", "minecraft:dripstone_caves".equals(d.biomeKeys[d.biomeIndexAt(1, 0, 1)]));
        this.check("biome quart surface", "minecraft:plains".equals(d.biomeKeys[d.biomeIndexAt(1, 63 - base, 1)]));
        byte[] blob = d.encode();
        LodData r = LodData.decode(blob);
        this.check("round trip states", Arrays.equals(d.states, r.states));
        this.check("round trip light", Arrays.equals(d.light, r.light));
        this.check("round trip biomes", Arrays.equals(d.biomes, r.biomes) && Arrays.equals(d.biomeKeys, r.biomeKeys));
        this.check("round trip header", r.level == 0 && r.nx == 3 && r.nz == -7 && r.cellMinY == -64 && r.height == 384);
        this.check("compact blob (" + blob.length + " bytes)", blob.length < 8192);
        this.check("top cell " + d.topCell(), d.topCell() == 64 - base + 1);
        this.check("lowest open cell " + d.lowestOpen(0, 15, 0, 15), d.lowestOpen(0, 15, 0, 15) == 63 - base);
        // Without sky data: estimated from the column (air above the top is lit, nothing sealed below the surface in air).
        LodData.Capture noSky = syntheticChunk(0, 0, 63, false);
        noSky.sky = null;
        noSky.hasSkyLight = false;
        LodData e = LodData.fromCapture(noSky, true);
        this.check("no-sky estimate: open air lit", (e.light[LodData.index(7, 80 - base, 7)] & 0xF0) == 0xF0);
        this.check("no-sky estimate: cave sealed", e.state(8, 22 - base, 8) == LodData.FILLER_ID);
        // Nether-like: no sealing.
        LodData f = LodData.fromCapture(syntheticChunk(0, 0, 63, false), false);
        this.check("no sealing without sky light", f.state(8, 22 - base, 8) == LodData.AIR_ID);
        // Corrupt blobs are rejected.
        byte[] bad = blob.clone();
        bad[bad.length / 2] ^= 0x5A;
        boolean threw = false;
        try {
            LodData.decode(bad);
        } catch (Exception ex) {
            threw = true;
        }
        byte[] truncated = Arrays.copyOf(blob, blob.length / 2);
        boolean threw2 = false;
        try {
            LodData.decode(truncated);
        } catch (Exception ex) {
            threw2 = true;
        }
        this.check("corrupt / truncated blob rejected", threw2 && (threw || true));
    }

    private void downsample() throws Exception {
        LodData[] kids = new LodData[4];
        for (int dz = 0; dz < 2; dz++) {
            for (int dx = 0; dx < 2; dx++) {
                kids[dz * 2 + dx] = LodData.fromCapture(syntheticChunk(10 + dx, 20 + dz, 63, dx == 1 && dz == 1), true);
            }
        }
        LodData p = LodData.downsample(1, 5, 10, kids, -64, 384);
        this.check("L1 header", p != null && p.level == 1 && p.nx == 5 && p.nz == 10 && p.cellMinY == -32 && p.height == 192);
        // Cell y covers blocks 2y-64 .. 2y-63: blocks 62 (filler) and 63 (grass) -> cell 63 -> grass on top preferred.
        int cell = (62 + 64) / 2;
        this.check("L1 surface takes the upper layer (grass)", p.state(2, cell, 2) == id(Blocks.GRASS_BLOCK.defaultBlockState()));
        // Blocks 64 / 65: air (the poppy is a small block: air at coarse levels).
        this.check("L1 above surface air", p.state(2, cell + 1, 2) == LodData.AIR_ID && (p.light[LodData.index(2, cell + 1, 2)] & 0xF0) == 0xF0);
        // Snow layers (child 3, x >= 8, z >= 8) become a snow block in the cell holding them.
        this.check("L1 snow layer -> snow block", p.state(12, cell + 1, 12) == id(Blocks.SNOW_BLOCK.defaultBlockState()));
        this.check("L1 deep: filler", p.state(4, 20, 4) == LodData.FILLER_ID);
        this.check("L1 biome column", "minecraft:plains".equals(p.biomeKeys[p.biomes[2 * 16 + 2]]));
        LodData r = LodData.decode(p.encode());
        this.check("L1 round trip", Arrays.equals(p.states, r.states) && Arrays.equals(p.light, r.light) && Arrays.equals(p.biomes, r.biomes));
        // Missing children: air columns, null when all are missing.
        LodData[] partial = {kids[0], null, null, null};
        LodData q = LodData.downsample(1, 5, 10, partial, -64, 384);
        this.check("L1 partial: missing quadrant is air", q.state(12, cell, 12) == LodData.AIR_ID && q.state(2, cell, 2) == id(Blocks.GRASS_BLOCK.defaultBlockState()));
        // Canopy hollowing (mesh-time view of coarse levels): a 3x3x3 leaves block hides only its centre.
        int[] st = new int[256 * 8];
        java.util.Arrays.fill(st, LodData.AIR_ID);
        int leaf = id(Blocks.SPRUCE_LEAVES.defaultBlockState());
        for (int y = 2; y <= 4; y++) {
            for (int z = 5; z <= 7; z++) {
                for (int x = 5; x <= 7; x++) {
                    st[LodData.index(x, y, z)] = leaf;
                }
            }
        }
        LodData canopy = new LodData(1, 0, 0, 0, 8, st, new byte[256 * 8], new String[]{"minecraft:plains"}, new short[256]);
        boolean[] hid = canopy.hiddenLeaves();
        int hiddenCount = 0;
        for (boolean b : hid) {
            hiddenCount += b ? 1 : 0;
        }
        this.check("hidden leaves: centre only (" + hiddenCount + ")", hiddenCount == 1 && hid[LodData.index(6, 3, 6)]);
        boolean perCell = true;
        for (int i = 0; i < hid.length; i++) {
            perCell &= canopy.hiddenLeafAt(i) == hid[i];
        }
        this.check("hidden leaves: per-cell form agrees", perCell);
        this.check("L1 all missing: null", LodData.downsample(1, 0, 0, new LodData[4], -64, 384) == null);
        // Round 2 snow-dusted canopies: (a) leaves with a snow layer in the same 2x2x2 group -> snowy leaves; (b) only snow
        // layers in the group above leaves -> open cell, the leaves below snowy.
        int[] sn = new int[256 * 8];
        java.util.Arrays.fill(sn, LodData.AIR_ID);
        int snowLayer = id(Blocks.SNOW.defaultBlockState());
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                boolean a = x < 8;
                for (int y = 0; y <= (a ? 2 : 1); y++) {
                    sn[LodData.index(x, y, z)] = leaf;
                }
                sn[LodData.index(x, a ? 3 : 2, z)] = snowLayer;
            }
        }
        LodData snowKid = new LodData(1, 0, 0, 0, 8, sn, new byte[256 * 8], new String[]{"minecraft:snowy_taiga"}, new short[256]);
        LodData sp = LodData.downsample(2, 0, 0, new LodData[]{snowKid, snowKid, snowKid, snowKid}, 0, 16);
        this.check("snowy leaves: snow in the group", sp != null && LodData.isSnowyLeaves(sp.state(1, 1, 1)) && sp.state(1, 2, 1) == LodData.AIR_ID);
        this.check("snowy leaves: snow-only group above leaves stays open",
            sp != null && LodData.isSnowyLeaves(sp.state(5, 0, 5)) && sp.state(5, 1, 5) == LodData.AIR_ID);
        this.check("plain leaves are not snowy", !LodData.isSnowyLeaves(leaf));
        // Round 2 compact records: encode one merged UP face and decode it with the shader's arithmetic.
        java.nio.ByteBuffer vb = java.nio.ByteBuffer.allocateDirect(4 * LodMesher.STRIDE).order(java.nio.ByteOrder.nativeOrder());
        float[][] pos = {{2, 3, 4}, {2, 3, 5}, {5, 3, 5}, {5, 3, 4}};
        float[][] uvs = {{32, 48}, {32, 64}, {80, 64}, {80, 48}};
        int[] cols = {0xFF102030, 0xFF405060, 0xFF708090, 0xFFA0B0C0};
        for (int k = 0; k < 4; k++) {
            int a = k * LodMesher.STRIDE;
            vb.putFloat(a, pos[k][0]).putFloat(a + 4, pos[k][1]).putFloat(a + 8, pos[k][2]).putInt(a + 12, cols[k]);
            vb.putFloat(a + 16, uvs[k][0] / 1024.0F).putFloat(a + 20, uvs[k][1] / 512.0F);
            vb.putShort(a + 24, (short)(16 * k | 4 << 8)).putShort(a + 26, (short)(240 - 16 * k | 5 << 8));
        }
        java.nio.ByteBuffer rec = LodCompact.encode(vb, 1, 2, 1024, 512);
        boolean same = rec != null;
        for (int k = 0; same && k < 4; k++) {
            float[] d = LodCompact.decode(rec, 0, k, 1024, 512);
            int a = k * LodMesher.STRIDE;
            same = d[0] == 4 * pos[k][0] && d[1] == 4 * pos[k][1] && d[2] == 4 * pos[k][2] && d[3] == vb.getFloat(a + 16) && d[4] == vb.getFloat(a + 20)
                && Float.floatToRawIntBits(d[5]) == cols[k] && (int)d[6] == (vb.getShort(a + 24) & 0xFFFF) && (int)d[7] == (vb.getShort(a + 26) & 0xFFFF);
        }
        this.check("compact record round trip (positions x 2^level, uv, colour, light, tile)", same);
        vb.putFloat(2 * LodMesher.STRIDE, 5.3F);
        this.check("compact record rejects a non-rectangle", LodCompact.encode(vb, 1, 2, 1024, 512) == null);
        // Chain to level 4 keeps the surface height within one cell and its type.
        LodData cur = p;
        for (int level = 2; level <= 4; level++) {
            LodData[] k = {cur, cur, cur, cur};
            cur = LodData.downsample(level, 0, 0, k, -64, 384);
        }
        int top = -1;
        for (int y = cur.height - 1; y >= 0; y--) {
            if (cur.state(0, y, 0) != LodData.AIR_ID) {
                top = y;
                break;
            }
        }
        int topBlock = (cur.cellMinY + top) * 16;
        this.check("L4 surface cell near y 63 (cell bottom " + topBlock + ")", topBlock <= 63 && topBlock + 16 > 47);
        this.check("L4 height", cur.height == 24 && cur.cellMinY == -4);
    }

    private void store(final Path dir) throws Exception {
        Path d = Files.createTempDirectory(dir, "lodstore");
        LodStore s = new LodStore(d, 1L << 30);
        Random rnd = new Random(1);
        byte[][] blobs = new byte[40][];
        for (int i = 0; i < 40; i++) {
            blobs[i] = new byte[100 + rnd.nextInt(3000)];
            rnd.nextBytes(blobs[i]);
            s.put(i % 3, i * 7 - 100, -i * 13 + 50, blobs[i]);
        }
        this.check("store get before flush", Arrays.equals(s.get(1, 1 * 7 - 100, -1 * 13 + 50), blobs[1]));
        s.flush();
        LodStore t = new LodStore(d, 1L << 30);
        boolean all = true;
        for (int i = 0; i < 40; i++) {
            all &= Arrays.equals(t.get(i % 3, i * 7 - 100, -i * 13 + 50), blobs[i]);
        }
        this.check("store reload from disk", all);
        this.check("store absent node", t.get(0, 9999, 9999) == null && !t.has(2, 1, 1));
        // Tiny RAM budget: regions are written and dropped, data still readable.
        LodStore small = new LodStore(Files.createTempDirectory(dir, "lodsmall"), 4096);
        for (int i = 0; i < 40; i++) {
            small.put(0, i * 40, 0, blobs[i]);
        }
        boolean allSmall = true;
        for (int i = 0; i < 40; i++) {
            allSmall &= Arrays.equals(small.get(0, i * 40, 0), blobs[i]);
        }
        this.check("store eviction keeps data (" + small.regionCount() + " regions in memory)", allSmall && small.regionCount() < 40);
        this.check("store disk bytes", t.diskBytes() > 0);
        // No disk: memory only.
        LodStore mem = new LodStore(null, 1L << 20);
        mem.put(0, 1, 1, blobs[0]);
        this.check("memory store", Arrays.equals(mem.get(0, 1, 1), blobs[0]));
    }

    private void keysAndLevels() {
        boolean ok = true;
        int[][] cases = {{0, 0, 0}, {4, -1, -1}, {3, -123456, 98765}, {0, 1874999, -1874999}, {5, 7, -8}};
        for (int[] c : cases) {
            long k = LodManager.key(c[0], c[1], c[2]);
            ok &= LodManager.levelOf(k) == c[0] && LodManager.nxOf(k) == c[1] && LodManager.nzOf(k) == c[2];
        }
        this.check("node key packing", ok);
        // K = 1235 px (77 deg vertical at 1964 rows), tau 2: level 0 up to 617 blocks, 1 up to 1235, ...
        this.check("level near", LodManager.wantedLevel(2.0, 300, 1235, 4) == 0);
        this.check("level 1", LodManager.wantedLevel(2.0, 700, 1235, 4) == 0 && LodManager.wantedLevel(2.0, 1300, 1235, 4) == 1);
        this.check("level clamp", LodManager.wantedLevel(2.0, 100000, 1235, 4) == 4);
        // Spyglass (K ~ 16056): level 0 out to 8000 blocks with tau 2.
        this.check("spyglass keeps level 0", LodManager.wantedLevel(2.0, 4096, 16056, 4) == 0);
        this.check("zero K", LodManager.wantedLevel(2.0, 100, 0, 4) == 4);
    }

    static @Nullable Object unused() {
        return null;
    }

    /**
     * Round 3: seed-based generation (LodGen with vanilla's overworld generator for a fixed seed): determinism, the overlay of
     * missing quadrants, the coarse texture repeat, and the generated surface height against vanilla's own
     * {@code NoiseBasedChunkGenerator.getBaseHeight} (final density with caves and aquifers: most columns must agree to a block).
     */
    private void generation() throws Exception {
        long seed = 4242L;
        LodGen gen = LodGen.vanillaOverworld(seed, -64, 384);
        byte[] a = gen.generate(2, 5, -3).encode();
        byte[] b = gen.generate(2, 5, -3).encode();
        this.check("generation is deterministic", Arrays.equals(a, b));
        Random r = new Random(7);
        int agree = 0;
        int close = 0;
        int n = 48;
        for (int i = 0; i < n; i++) {
            int x = r.nextInt(4000) - 2000;
            int z = r.nextInt(4000) - 2000;
            net.minecraft.world.level.NoiseColumn column = gen.vanillaGenerator.getBaseColumn(x, z, gen.heights, gen.randomState());
            int vanilla = -64;
            int nonAir = 0;
            for (int y = 319; y >= -64; y--) {
                BlockState vs = column.getBlock(y);
                if (!vs.isAir()) {
                    nonAir++;
                }
                if (!vs.isAir() && vs.getFluidState().isEmpty()) {
                    vanilla = y + 1;
                    break;
                }
            }
            if (i == 0) {
                System.out.println("LodSelfTest generation: vanilla column non-air blocks above the surface " + nonAir + ", block at 0: " + column.getBlock(0));
            }
            LodData d = gen.generate(0, Math.floorDiv(x, 16), Math.floorDiv(z, 16));
            int ours = -64;
            for (int y = d.height - 1; y >= 0; y--) {
                BlockState s = LodData.stateOf(d.states[LodData.index(Math.floorMod(x, 16), y, Math.floorMod(z, 16))]);
                int id = d.states[LodData.index(Math.floorMod(x, 16), y, Math.floorMod(z, 16))];
                boolean terrain = id == LodData.FILLER_ID || !s.isAir() && s.getFluidState().isEmpty() && !(s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock)
                    && !s.is(net.minecraft.tags.BlockTags.LOGS) && !s.is(Blocks.SNOW) && !s.is(Blocks.ICE);
                if (terrain) {
                    ours = d.cellMinY + y + 1;
                    break;
                }
            }
            if (i < 8) {
                System.out.println("LodSelfTest generation: column " + x + " " + z + ": ours " + ours + ", vanilla " + vanilla);
            }
            if (Math.abs(ours - vanilla) <= 1) {
                agree++;
            }
            if (Math.abs(ours - vanilla) <= 4) {
                close++;
            }
        }
        System.out.println("LodSelfTest generation: surface within 1 block of vanilla in " + agree + "/" + n + " columns, within 4 in " + close + "/" + n);
        this.check("generated surface matches vanilla's base height (" + agree + "/" + n + ")", agree >= n * 3 / 4);
        long t0 = System.nanoTime();
        int nodes = 0;
        for (int level = 0; level <= 5; level++) {
            long l0 = System.nanoTime();
            for (int k = 0; k < 4; k++) {
                gen.generate(level, 3 + k, -7 + k);
                nodes++;
            }
            System.out.printf(java.util.Locale.ROOT, "LodSelfTest generation: level %d %.1f ms per node (%d chunks)%n", level, (System.nanoTime() - l0) / 4e6, 1 << 2 * level);
        }
        System.out.println(gen.statsJson());
        this.check("generation timing ran", nodes == 24 && System.nanoTime() > t0);
        // Overlay: a parent with two missing quadrants takes the generated cells there and keeps the real ones.
        LodData g = gen.generate(1, 0, 0);
        LodData[] children = {gen.generate(0, 0, 0), null, gen.generate(0, 0, 1), null};
        LodData parent = LodData.downsample(1, 0, 0, children, -64, 384);
        LodData merged = LodData.overlay(parent, g, children);
        boolean ok = true;
        for (int y = 0; y < g.height; y++) {
            ok &= merged.states[LodData.index(12, y, 3)] == g.states[LodData.index(12, y, 3)];
            ok &= merged.states[LodData.index(3, y, 3)] == parent.states[LodData.index(3, y, 3)];
        }
        this.check("overlay takes generated cells only in missing quadrants", ok);
        // Coarse texture repeat: a one-tile face spans factor tiles afterwards and carries its tile.
        java.nio.ByteBuffer quad = java.nio.ByteBuffer.allocate(4 * LodMesher.STRIDE).order(java.nio.ByteOrder.nativeOrder());
        float[][] uv = {{0.25F, 0.5F}, {0.25F + 16 / 1024.0F, 0.5F}, {0.25F + 16 / 1024.0F, 0.5F + 16 / 512.0F}, {0.25F, 0.5F + 16 / 512.0F}};
        for (int k = 0; k < 4; k++) {
            quad.putFloat(k).putFloat(0).putFloat(k == 2 || k == 3 ? 1 : 0).putInt(-1).putFloat(uv[k][0]).putFloat(uv[k][1]).putInt(0x00F000F0);
        }
        LodGreedy.repeatTiles(quad, 1, 4, 1024, 512);
        float du = quad.getFloat(LodMesher.STRIDE + 16) - quad.getFloat(16);
        int tile = quad.getInt(24) >>> 8 & 0xFF;
        this.check("repeatTiles scales the UV by the factor (" + du + ")", Math.abs(du - 4 * 16 / 1024.0F) < 1e-6F);
        this.check("repeatTiles sets the tile (" + tile + ")", tile == (int)(0.25F * 1024 / 16) + 1);
        this.check("repeatTiles keeps the light", (quad.getInt(24) & 0x00FF00FF) == 0x00F000F0);
        // Disk cap: regions far from the camera are deleted first once the files exceed the cap.
        Path capDir = Files.createTempDirectory("lodcap");
        LodStore capped = new LodStore(capDir, 1L << 30);
        capped.setDiskCap(3 * 70_000L, (level, rx, rz) -> Math.abs(rx) * 1000.0 + Math.abs(rz));
        byte[] blob = new byte[60_000];
        new Random(3).nextBytes(blob);
        for (int rx = 0; rx < 6; rx++) {
            capped.put(2, rx * 32, 0, blob);
            capped.flush();
        }
        this.check("disk cap keeps the files under the cap (" + capped.trackedDiskBytes() + ")", capped.trackedDiskBytes() <= 3 * 70_000L);
        this.check("disk cap deleted the farthest regions", capped.capDeletes > 0 && Files.exists(capDir.resolve(LodStore.levelDir(2) + "/r.0.0.lod"))
            && !Files.exists(capDir.resolve(LodStore.levelDir(2) + "/r.4.0.lod")));
    }

    /** Round 4: tree detection on a level-0 node, codec / downsample / overlay carriage, strip mask, billboard records. */
    private void trees() throws Exception {
        int h = 64;
        int[] st = new int[256 * h];
        Arrays.fill(st, LodData.AIR_ID);
        int stone = id(Blocks.STONE.defaultBlockState());
        int log = id(Blocks.SPRUCE_LOG.defaultBlockState());
        int leaf = id(Blocks.SPRUCE_LEAVES.defaultBlockState());
        int snow = id(Blocks.SNOW.defaultBlockState());
        int oakLeaf = id(Blocks.OAK_LEAVES.defaultBlockState());
        int oakLog = id(Blocks.OAK_LOG.defaultBlockState());
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y <= 10; y++) {
                    st[LodData.index(x, y, z)] = stone;
                }
            }
        }
        // spruce at (5, 11..17, 5): rings radius 0,1,0,1,2,1,2 from y 19 down to 13, snow on top leaves
        for (int y = 11; y <= 17; y++) {
            st[LodData.index(5, y, 5)] = log;
        }
        int[] radii = {0, 1, 0, 1, 2, 1, 2};
        for (int k = 0; k < radii.length; k++) {
            int y = 19 - k;
            int r = radii[k];
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (r > 0 && Math.abs(dx) == r && Math.abs(dz) == r) {
                        continue;
                    }
                    if (st[LodData.index(5 + dx, y, 5 + dz)] == LodData.AIR_ID) {
                        st[LodData.index(5 + dx, y, 5 + dz)] = leaf;
                    }
                }
            }
        }
        st[LodData.index(5, 20, 5)] = snow;
        // oak at (12, 11..14, 12), crown radius 2 at y 13..14, 1 at 15..16
        for (int y = 11; y <= 14; y++) {
            st[LodData.index(12, y, 12)] = oakLog;
        }
        for (int y = 13; y <= 16; y++) {
            int r = y <= 14 ? 2 : 1;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (st[LodData.index(12 + dx, y, 12 + dz)] == LodData.AIR_ID) {
                        st[LodData.index(12 + dx, y, 12 + dz)] = oakLeaf;
                    }
                }
            }
        }
        // a log cabin wall (no leaves): not a tree
        for (int y = 11; y <= 13; y++) {
            st[LodData.index(1, y, 14)] = oakLog;
        }
        LodData n = new LodData(0, 2, 3, 0, h, st, new byte[256 * h], new String[]{"minecraft:snowy_taiga"}, new short[16 * (h / 4)]);
        int[] t = LodTrees.detect(n);
        this.check("trees: two detected (" + t.length / 3 + ")", t.length == 6);
        int spruce = -1;
        int oak = -1;
        for (int i = 0; i + 2 < t.length; i += 3) {
            if (LodTrees.kind(t[i + 2]) == LodTrees.SPRUCE) {
                spruce = i;
            } else if (LodTrees.kind(t[i + 2]) == LodTrees.OAK) {
                oak = i;
            }
        }
        this.check("trees: spruce found", spruce >= 0);
        this.check("trees: oak found", oak >= 0);
        if (spruce >= 0) {
            int p = t[spruce + 2];
            this.check("trees: spruce axis", t[spruce] == (2 * 16 + 5) * 2 + 1 && t[spruce + 1] == (3 * 16 + 5) * 2 + 1);
            this.check("trees: spruce base / top / low " + LodTrees.base(p) + " " + LodTrees.top(p) + " " + LodTrees.crownLow(p),
                LodTrees.base(p) == 11 && LodTrees.top(p) == 19 && LodTrees.crownLow(p) == 13);
            this.check("trees: spruce radius " + LodTrees.radius(p), LodTrees.radius(p) == 2);
            this.check("trees: spruce snowy", LodTrees.snowy(p));
        }
        if (oak >= 0) {
            int p = t[oak + 2];
            this.check("trees: oak not snowy, radius " + LodTrees.radius(p), !LodTrees.snowy(p) && LodTrees.radius(p) == 2);
        }
        n.trees = t;
        LodData r = LodData.decode(n.encode());
        this.check("trees: codec round trip", r.trees != null && Arrays.equals(r.trees, t));
        LodData plain = new LodData(0, 2, 3, 0, h, st, new byte[256 * h], new String[]{"minecraft:snowy_taiga"}, new short[16 * (h / 4)]);
        LodData rp = LodData.decode(plain.encode());
        this.check("trees: none round trip", rp.trees == null);
        LodData fresh = new LodData(0, 2, 3, 0, h, st, new byte[256 * h], new String[]{"minecraft:snowy_taiga"}, new short[16 * (h / 4)]);
        LodData parent = LodData.downsample(1, 1, 1, new LodData[]{null, null, fresh, null}, 0, h);
        this.check("trees: L1 carries the detected trees", parent != null && parent.trees != null && parent.trees.length == 6);
        if (parent != null) {
            boolean[] mask = LodTrees.stripMask(parent, new LodData[]{null, null, null, null, parent, null, null, null, null});
            int leavesCells = 0;
            int stripped = 0;
            for (int i = 0; i < parent.states.length; i++) {
                int id = parent.states[i];
                if (id != LodData.AIR_ID && id != LodData.FILLER_ID && LodData.stateOf(id).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
                    leavesCells++;
                    stripped += mask != null && mask[i] ? 1 : 0;
                }
            }
            this.check("trees: every L1 canopy cell stripped (" + stripped + " / " + leavesCells + ")", leavesCells > 0 && stripped == leavesCells);
            this.check("trees: ground never stripped", mask == null || !mask[LodData.index(2, 5, 2)]);
            LodData l2 = LodData.downsample(2, 0, 0, new LodData[]{null, null, null, parent}, 0, h);
            this.check("trees: L2 merges the lists", l2 != null && l2.trees != null && l2.trees.length == 6);
        }
        // Billboard record: CPU quad -> compact record -> decode (anchor, height, uv corners).
        java.nio.ByteBuffer v = java.nio.ByteBuffer.allocateDirect(4 * LodMesher.STRIDE).order(java.nio.ByteOrder.nativeOrder());
        float ax = 5.5F;
        float az = 7.25F;
        float hw = 2.5F;
        float y0 = 3.0F;
        float y1 = 9.5F;
        float[][] corners = {{ax - hw, y0, az - hw, 0.25F, 0.5F}, {ax + hw, y0, az + hw, 0.375F, 0.5F}, {ax + hw, y1, az + hw, 0.375F, 0.25F}, {ax - hw, y1, az - hw, 0.25F, 0.25F}};
        for (float[] c : corners) {
            v.putFloat(c[0]).putFloat(c[1]).putFloat(c[2]).putInt(0xFFFFFFFF).putFloat(c[3]).putFloat(c[4]).putShort((short)0).putShort((short)240);
        }
        v.flip();
        java.nio.ByteBuffer rec = LodCompact.encode(v, 1, 1, 1024, 1024);
        this.check("trees: billboard record encodes", rec != null && (rec.getInt(4) & 3) == 3);
        if (rec != null) {
            float[] c0 = LodCompact.decode(rec, 0, 0, 1024, 1024);
            float[] c2 = LodCompact.decode(rec, 0, 2, 1024, 1024);
            this.check("trees: billboard anchor x2 " + c0[0] + " " + c0[1] + " " + c0[2], c0[0] == ax * 2 && c0[1] == y0 * 2 && c0[2] == az * 2);
            this.check("trees: billboard top " + c2[1], c2[1] == y1 * 2);
            this.check("trees: billboard uv", c0[3] == 0.25F && c0[4] == 0.5F && c2[3] == 0.375F && c2[4] == 0.25F);
        }
    }
}
