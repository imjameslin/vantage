package net.minecraft.client.renderer.chunk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensityBufferPool;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.material.LodMaterial;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.jspecify.annotations.Nullable;

/**
 * Stage 3 r3 (CONTRACT 16.S3-LOD-R3, docs/stage3/LOD-DESIGN.md §6.1): seed-based LOD terrain, our "DHSeedgen". Generates one
 * LOD node of any level directly at its cell size from the world seed with vanilla's own worldgen pieces, never a chunk:
 * <ul>
 * <li>terrain shape: the noise router's {@code overworld/sloped_cheese} density (final density without caves, ore veins,
 * noodles and the cave entrances) with final_density's top and bottom gradients, sampled on vanilla's own 4 x 8 x 4
 * interpolation lattice (or every cell at coarse levels) and interpolated the way vanilla interpolates final density, so the
 * surface height is vanilla's except where caves open at the surface;</li>
 * <li>biomes: the dimension's biome source with the seed's climate sampler (no zoom fuzz);</li>
 * <li>surface blocks: vanilla's surface (material) rules per column at the surface and on exposed slopes, sea level water,
 * freezing (ice on cold water, snow on cold ground and canopies);</li>
 * <li>trees: per chunk a deterministic set of trees by biome (density and shapes after vanilla's tree features), voxelized
 * into the node's cells (at coarse levels a cell is leaves where the trees fill enough of it).</li>
 * </ul>
 * Underground cells are sealed (filler) as in captured level-0 data. Singleplayer uses the integrated server's generator
 * (seed, datapack worldgen); multiplayer only with a seed the user supplies ({@code -Dmc.lod.seed=<seed>} or
 * {@code <game dir>/lod/seeds.properties}: {@code <server address>=<seed>}) and vanilla's overworld generator. Nothing is
 * requested from or sent to the server. Real chunks always replace generated nodes (LodManager reads the real store first).
 */
final class LodGen {
    static final boolean ENABLED = Lod.flag("mc.lod.gen", true);
    /** Leaves cell when the trees fill at least this share of it (levels >= 2; levels 0 / 1 use 1 block / 4 of 8). */
    /** Round 8: generated top water / ice cells at the real surface height ({@code mc.lod.genFluidTop}). */
    static final boolean FLUID_TOP = Lod.flag("mc.lod.genFluidTop", true);
    static final float COARSE_FILL = Float.parseFloat(System.getProperty("mc.lod.genFill", "0.3"));
    /** Round 4: canopy coverage that turns a coarse ground cell (levels >= 2) into leaves ({@code mc.lod.genCanopy}, 0 = off). */
    static final float CANOPY = Float.parseFloat(System.getProperty("mc.lod.genCanopy", "0.5"));
    /** Exposed slope cells as surface blocks ({@code mc.lod.genExposed}, default off: measured worse vs real, vista dE 7.7 -> 8.2 with genFill 0.5). */
    static final boolean EXPOSED_SURFACE = Lod.flag("mc.lod.genExposed", false);
    /** Biomes through vanilla's BiomeManager zoom (seeded border fuzz) as the surface rules see them ({@code mc.lod.genBiomeZoom}). */
    static final boolean BIOME_ZOOM = Lod.flag("mc.lod.genBiomeZoom", true);
    /** Trees only where the biome at the trunk has the chunk's forest, as vanilla's biome filter ({@code mc.lod.genTreeBiome}). */
    static final boolean TREE_BIOME = Lod.flag("mc.lod.genTreeBiome", true);
    /** Trees only on soil (the surface rule's block at the trunk in the dirt tag) ({@code mc.lod.genTreeSoil}). */
    static final boolean TREE_SOIL = Lod.flag("mc.lod.genTreeSoil", false);
    /** Vanilla's dirt tag spelled out (the tag is not bound on the worker threads' holders). */
    private static final java.util.Set<net.minecraft.world.level.block.Block> SOIL = java.util.Set.of(
        net.minecraft.world.level.block.Blocks.GRASS_BLOCK, net.minecraft.world.level.block.Blocks.DIRT,
        net.minecraft.world.level.block.Blocks.COARSE_DIRT, net.minecraft.world.level.block.Blocks.PODZOL,
        net.minecraft.world.level.block.Blocks.MYCELIUM, net.minecraft.world.level.block.Blocks.ROOTED_DIRT,
        net.minecraft.world.level.block.Blocks.MOSS_BLOCK, net.minecraft.world.level.block.Blocks.PALE_MOSS_BLOCK,
        net.minecraft.world.level.block.Blocks.MUD, net.minecraft.world.level.block.Blocks.MUDDY_MANGROVE_ROOTS);
    private static final int AIR_ID = LodData.AIR_ID;
    private static final int FILLER_ID = LodData.FILLER_ID;
    private final RandomState randomState;
    private final DensityFunction terrain;
    private final boolean finalDensity;
    private final BiomeSource biomeSource;
    private final MaterialRule rule;
    private final WorldGenerationContext genContext;
    private final int seaLevel;
    private final int minY;
    private final int height;
    final long seed;
    final String source;
    private final int waterId;
    private final int iceId;
    private final int snowLayerId;
    private final int snowBlockId;
    private final ThreadLocal<DensityBufferPool> pools;
    final AtomicLongArray generatedPerLevel = new AtomicLongArray(8);
    final AtomicLongArray nanosPerLevel = new AtomicLongArray(8);
    /** Of nanosPerLevel: the density lattice part. */
    final AtomicLongArray latNanosPerLevel = new AtomicLongArray(8);

    private LodGen(final RandomState randomState, final DensityFunction terrain, final boolean finalDensity, final BiomeSource biomeSource, final MaterialRule rule,
        final WorldGenerationContext genContext, final int seaLevel, final int minY, final int height, final long seed, final BlockState defaultFluid, final String source) {
        this.randomState = randomState;
        this.terrain = terrain;
        this.finalDensity = finalDensity;
        this.biomeSource = biomeSource;
        this.rule = rule;
        this.genContext = genContext;
        this.seaLevel = seaLevel;
        this.minY = minY;
        this.height = height;
        this.seed = seed;
        this.source = source;
        this.waterId = Block.BLOCK_STATE_REGISTRY.getId(defaultFluid);
        this.iceId = Block.BLOCK_STATE_REGISTRY.getId(Blocks.ICE.defaultBlockState());
        this.snowLayerId = Block.BLOCK_STATE_REGISTRY.getId(Blocks.SNOW.defaultBlockState());
        this.snowBlockId = Block.BLOCK_STATE_REGISTRY.getId(Blocks.SNOW_BLOCK.defaultBlockState());
        this.pools = ThreadLocal.withInitial(randomState::acquireDensityBufferPool);
    }

    // ---------------------------------------------------------------------------------------------------------------- setup

    /** The generator for a client level, or null (switched off, no seed known, not a noise generator, or setup failed). */
    static @Nullable LodGen forLevel(final ClientLevel level) {
        if (!ENABLED) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        try {
            var server = mc.getSingleplayerServer();
            if (server != null) {
                ServerLevel sl = server.getLevel(level.dimension());
                if (sl == null || !(sl.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator g)) {
                    return null;
                }
                NoiseGeneratorSettings settings = g.generatorSettings().value();
                HolderLookup.Provider registries = server.registryAccess();
                return create(sl.getChunkSource().randomState(), settings, g.getBiomeSource(), registries, new WorldGenerationContext(g, sl), sl.getSeed(),
                    level.dimension() == Level.OVERWORLD, "singleplayer world seed");
            }
            Long seed = multiplayerSeed(mc);
            if (seed == null || level.dimension() != Level.OVERWORLD) {
                return null;
            }
            return vanillaOverworld(seed, level.getMinY(), level.getHeight());
        } catch (Throwable t) {
            Lod.LOGGER.warn("[lod] seed generation unavailable", t);
            return null;
        }
    }

    /** Vanilla's overworld generator for a seed (built-in worldgen registries: multiplayer with a user seed, tests). */
    static LodGen vanillaOverworld(final long seed, final int minY, final int height) {
        HolderLookup.Provider vanilla = net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
        Holder<NoiseGeneratorSettings> settings = vanilla.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        RandomState rs = RandomState.create(vanilla.lookupOrThrow(Registries.NOISE), seed, settings.value());
        BiomeSource biomes = MultiNoiseBiomeSource.createFromPreset(
            vanilla.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        NoiseBasedChunkGenerator g = new NoiseBasedChunkGenerator(biomes, settings);
        LevelHeightAccessor heights = LevelHeightAccessor.create(minY, height);
        LodGen gen = create(rs, settings.value(), biomes, vanilla, new WorldGenerationContext(g, heights), seed, true, "user seed (vanilla overworld generator)");
        gen.vanillaGenerator = g;
        gen.heights = heights;
        return gen;
    }

    /** Tests (LodSelfTest): vanilla's generator and height accessor behind {@link #vanillaOverworld}. */
    @Nullable NoiseBasedChunkGenerator vanillaGenerator;
    @Nullable LevelHeightAccessor heights;

    RandomState randomState() {
        return this.randomState;
    }

    /** Generator version and parameters in the generated store's directory name (a change regenerates). */
    static String versionTag() {
        return (FLUID_TOP ? "w" : "") + "v3f" + Math.round(COARSE_FILL * 100.0F) + (EXPOSED_SURFACE ? "e" : "") + (BIOME_ZOOM ? "z" : "") + (TREE_BIOME ? "b" : "") + (TREE_SOIL ? "s" : "") + (LodTrees.ENABLED ? "t" + LodTrees.MAX_LEVEL : "") + (CANOPY > 0.0F ? "c" + Math.round(CANOPY * 100.0F) : "");
    }

    private static LodGen create(final RandomState rs, final NoiseGeneratorSettings settings, final BiomeSource biomes, final HolderLookup.Provider registries,
        final WorldGenerationContext genContext, final long seed, final boolean overworld, final String source) {
        DensityFunction terrain = null;
        if (overworld) {
            terrain = registries.lookup(Registries.DENSITY_FUNCTION)
                .flatMap(r -> r.get(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.withDefaultNamespace("overworld/sloped_cheese"))))
                .map(Holder::value).orElse(null);
        }
        boolean fin = terrain == null;
        if (fin) {
            terrain = settings.noiseRouter().finalDensity();
        }
        LodGen gen = new LodGen(rs, terrain, fin, biomes, settings.materialRule().value(), genContext, settings.seaLevel(), genContext.getMinGenY(),
            genContext.getGenDepth(), seed, settings.defaultFluid(), source);
        Lod.LOGGER.info("[lod] seed generation on: {} ({}), terrain {}", source, Long.toHexString(seed), fin ? "final density" : "sloped_cheese");
        return gen;
    }

    /** Multiplayer: the seed the user gave for this server ({@code mc.lod.seed}, or {@code lod/seeds.properties}), else null. */
    private static @Nullable Long multiplayerSeed(final Minecraft mc) {
        String prop = System.getProperty("mc.lod.seed");
        if (prop != null && !prop.isBlank()) {
            return parseSeed(prop.trim());
        }
        ServerData server = mc.getCurrentServer();
        Path file = mc.gameDirectory.toPath().resolve("lod").resolve("seeds.properties");
        if (server == null || !Files.isRegularFile(file)) {
            return null;
        }
        Properties p = new Properties();
        try (var in = Files.newInputStream(file)) {
            p.load(in);
        } catch (java.io.IOException e) {
            return null;
        }
        String v = p.getProperty(server.ip);
        return v == null || v.isBlank() ? null : parseSeed(v.trim());
    }

    /** A seed as typed in the world-creation screen: a number, or any other text hashed the way vanilla hashes it. */
    static long parseSeed(final String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return s.hashCode();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- nodes

    /** Generates node (level, nx, nz). Thread-safe (worker threads). */
    LodData generate(final int level, final int nx, final int nz) {
        long t0 = System.nanoTime();
        int c = 1 << level;
        int cellMinY = Math.floorDiv(this.minY, c);
        int cells = Math.max(1, this.height >> level);
        int x0 = nx * 16 * c;
        int z0 = nz * 16 * c;
        // ---- density lattice: step s horizontally (vanilla's 4 at fine levels), 8 vertically; one extra step around the node
        int s = Math.max(4, c);
        int nL = 16 * c / s + 3;
        int lx0 = x0 - s;
        int lz0 = z0 - s;
        int ny = this.height / 8 + 1;
        float[] lat = new float[nL * nL * ny];
        DensityBufferPool pool = this.pools.get();
        SamplerContext sc = SamplerContext.builder().useBufferArena(pool).enableCaches().build();
        DensitySamplerSet samplers = this.randomState.samplersWithContext(sc);
        DensityVolume vol = new DensityVolume(nL, ny, nL, lx0, this.minY, lz0, s, 8, s);
        // the band only where the column cache is not used: with it most columns are already sampled (levels 0-2) and the
        // band's coarse pass cost more than it saved (measured)
        boolean colCached = s == COL_STEP && COL_CACHE;
        int[] band = BAND && !colCached ? this.band(samplers, nL, ny, lx0, lz0, s) : new int[]{0, ny - 1};
        if (colCached) {
            this.latticeCached(lat, vol, nL, ny, lx0, lz0, samplers, s);
        } else {
            int r0 = band[0];
            int rows = band[1] - r0 + 1;
            DensityVolume part = new DensityVolume(nL, rows, nL, lx0, this.minY + r0 * 8, lz0, s, 8, s);
            try (ScopedDensityBuffer buf = samplers.get(this.terrain).sampleVolume(part)) {
                for (int z = 0; z < nL; z++) {
                    for (int x = 0; x < nL; x++) {
                        for (int y = 0; y < ny; y++) {
                            float d;
                            if (y < r0) {
                                d = BAND_SOLID;
                            } else if (y > band[1]) {
                                d = BAND_AIR;
                            } else {
                                d = buf.get(part.indexUnchecked(x, y - r0, z));
                                d = this.finalDensity ? d : gradients(d, this.minY + y * 8);
                            }
                            lat[vol.indexUnchecked(x, y, z)] = d;
                        }
                    }
                }
            }
        }
        if (BAND_VERIFY && BAND_CHECKS.incrementAndGet() % 10 == 0) {
            // diagnostics: each column's highest solid row, banded against the whole height
            int bad = 0;
            float max = 0.0F;
            try (ScopedDensityBuffer buf = samplers.get(this.terrain).sampleVolume(vol)) {
                for (int z = 0; z < nL; z++) {
                    for (int x = 0; x < nL; x++) {
                        int tFull = -1, tBand = -1;
                        for (int y = ny - 1; y >= 0; y--) {
                            int i = vol.indexUnchecked(x, y, z);
                            float d = this.finalDensity ? buf.get(i) : gradients(buf.get(i), this.minY + y * 8);
                            if (tFull < 0 && d > 0.0F) tFull = y;
                            if (tBand < 0 && lat[i] > 0.0F) tBand = y;
                            if (!colCached && y >= band[0] && y <= band[1]) max = Math.max(max, Math.abs(d - lat[i]));
                        }
                        bad += tFull != tBand ? 1 : 0;
                    }
                }
            }
            if (BAND_CHECKS.get() % 2000 == 0) {
                Lod.LOGGER.info("[lod] bandVerify checked {}", BAND_CHECKS.get() / 10);
            }
            if (bad > 0 || max > 1e-4F) {
                Lod.LOGGER.warn("[lod] bandVerify level {} cached {} s {} band {}..{} topMismatch {}/{} maxDiff {}", level, colCached, s, band[0], band[1], bad, nL * nL, max);
            }
        }
        this.latNanosPerLevel.addAndGet(Math.min(7, level), System.nanoTime() - t0);
        // ---- columns: 16 x 16 cells plus a border ring (index 0 and 17), density per lattice row interpolated to the cell centre
        int nc = 18;
        float[][] col = new float[nc * nc][];
        int[] surf = new int[nc * nc];
        for (int j = 0; j < nc; j++) {
            for (int i = 0; i < nc; i++) {
                double bx = x0 + (i - 1) * c + (c - 1) * 0.5;
                double bz = z0 + (j - 1) * c + (c - 1) * 0.5;
                float[] v = column(lat, nL, ny, (bx - lx0) / s, (bz - lz0) / s, vol);
                col[j * nc + i] = v;
                surf[j * nc + i] = surfaceY(v);
            }
        }
        BiomeResolver resolver = this.biomeSource.createResolver(this.randomState.createClimateSampler(sc));
        Map<Long, Holder<Biome>> biomeCache = new HashMap<>();
        BiomeResolver cached = (qx, qy, qz) -> biomeCache.computeIfAbsent(
            (long)qx << 40 ^ (long)(qy & 0xFFFF) << 24 ^ qz & 0xFFFFFFL, k -> resolver.getNoiseBiome(qx, qy, qz));
        // vanilla's surface rules see the zoomed biome (BiomeManager's seeded fuzz at the borders): without it narrow beaches
        // drew as grass until the real chunk arrived
        net.minecraft.world.level.biome.BiomeManager zoom = BIOME_ZOOM ? new net.minecraft.world.level.biome.BiomeManager(cached,
            net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(this.seed)) : null;
        Function<BlockPos, Holder<Biome>> biomeAt = zoom != null ? zoom::getBiome
            : pos -> cached.getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()), QuartPos.fromBlock(pos.getZ()));
        LodMaterial material = new LodMaterial(this.randomState, samplers, biomeAt, this.genContext, this.rule, x0, z0);
        int[] states = new int[256 * cells];
        byte[] light = new byte[256 * cells];
        java.util.Arrays.fill(states, AIR_ID);
        java.util.Arrays.fill(light, (byte)0xF0);
        @SuppressWarnings("unchecked")
        Holder<Biome>[] colBiome = new Holder[256];
        boolean[] cold = new boolean[256];
        int[] topCell = new int[256];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int ci = (z + 1) * nc + x + 1;
                int sy = surf[ci];
                int bx = x0 + x * c + (c >> 1);
                int bz = z0 + z * c + (c >> 1);
                float[] v = col[ci];
                Holder<Biome> biome = biomeAt.apply(pos.set(bx, Math.max(sy, this.seaLevel), bz));
                colBiome[z * 16 + x] = biome;
                // Surface rules: gradients as vanilla's (height difference over two blocks), scaled from cell spacing.
                int gx = Math.round((surf[ci + 1] - surf[ci - 1]) / (float)c);
                int gz = Math.round((surf[ci + nc] - surf[ci - nc]) / (float)c);
                material.column(bx, bz, gx, gz);
                int minNeighbour = Math.min(Math.min(surf[ci - 1], surf[ci + 1]), Math.min(surf[ci - nc], surf[ci + nc]));
                int water = sy < this.seaLevel - 1 ? this.seaLevel : Integer.MIN_VALUE;
                int top = -1;
                for (int cy = cells - 1; cy >= 0; cy--) {
                    int cellBottom = (cellMinY + cy) * c;
                    int centre = cellBottom + (c >> 1);
                    int i = LodData.index(x, cy, z);
                    if (density(v, centre, this.minY) > 0.0F) {
                        if (top < 0) {
                            top = cy;
                        }
                        int rep = Math.min(cellBottom + c - 1, sy);
                        // Round 8: and directly under solid ground. Under an air pocket of the density (overhangs, jungle hills) the
                        // cell's top is in view: it drew as a flat dark stone plate among the trees.
                        if (cellBottom + c - 1 < minNeighbour - c && top != cy && cy + 1 < cells && solidCell(states[LodData.index(x, cy + 1, z)])) {
                            states[i] = FILLER_ID;   // below every neighbour's surface: never visible (sealed, as level 0 seals)
                            light[i] = 0;
                            continue;
                        }
                        // Cells exposed on a slope (a neighbour column ends below their top) hold the slope surface in the real
                        // data, so the downsample shows the surface block there: depth 1 (steep faces still get stone by the rules).
                        boolean exposed = EXPOSED_SURFACE && c > 1 && cellBottom + c - 1 >= minNeighbour;
                        BlockState st = material.at(rep, exposed ? 1 : Math.max(1, sy - rep + 1), 64, water);
                        states[i] = st == null ? FILLER_ID : Block.BLOCK_STATE_REGISTRY.getId(st);
                        light[i] = 0;
                    } else if (FLUID_TOP ? cellBottom < this.seaLevel : centre < this.seaLevel) {
                        // Round 8: the top water cell at the real surface's height (as LodData's downsample of real water stores
                        // it: sea level - 1 + 8/9 in ninths of the cell), not a full cell: the levels' water met in steps
                        // (lines) and level 1 dropped the top water layer (void gaps on the sea).
                        boolean topWater = FLUID_TOP && c > 1 && cellBottom + c >= this.seaLevel;
                        states[i] = topWater ? LodData.fluidAt(this.waterId, (this.seaLevel - 1 + 8.0F / 9.0F - cellBottom) / c) : this.waterId;
                        light[i] = (byte)(Math.max(0, 15 - Math.max(0, (this.seaLevel - 1 - (cellBottom + c - 1)))) << 4);
                    }
                }
                topCell[z * 16 + x] = top;
                pos.set(bx, Math.max(sy, this.seaLevel) + 1, bz);
                cold[z * 16 + x] = !biome.value().warmEnoughToRain(pos, this.seaLevel);
            }
        }
        // ---- trees
        // Round 4: tree records for the stand-ins (LodTrees) at the levels that draw them; [0] = used ints.
        int[] records = LodTrees.listsAt(level) ? new int[1 + 3 * 64 * (c * c) + 3] : null;
        this.trees(level, x0, z0, c, cellMinY, cells, lat, nL, ny, lx0, lz0, s, vol, states, light, biomeAt, records, material);
        // ---- freezing: ice on cold water, snow on cold ground and canopies
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                if (!cold[z * 16 + x]) {
                    continue;
                }
                for (int cy = cells - 1; cy >= 0; cy--) {
                    int i = LodData.index(x, cy, z);
                    int id = states[i];
                    if (id == AIR_ID) {
                        continue;
                    }
                    if (id == this.waterId || FLUID_TOP && LodData.stateOf(id).getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                        int cellBottom = (cellMinY + cy) * c;
                        states[i] = FLUID_TOP && c > 1 && cellBottom + c >= this.seaLevel ? LodData.iceAt((float)(this.seaLevel - cellBottom) / c) : this.iceId;
                        light[i] = 0;
                    } else if (id != FILLER_ID) {
                        BlockState st = LodData.stateOf(id);
                        if (st.getBlock() instanceof LeavesBlock) {
                            if (c == 1) {
                                if (cy + 1 < cells) {
                                    states[i + 256] = this.snowLayerId;
                                }
                            } else {
                                states[i] = Block.BLOCK_STATE_REGISTRY.getId(st.setValue(LeavesBlock.PERSISTENT, true).setValue(LeavesBlock.DISTANCE, 7));
                            }
                        } else if (st.isSolidRender()) {
                            if (c == 1) {
                                if (st.hasProperty(BlockStateProperties.SNOWY)) {
                                    states[i] = Block.BLOCK_STATE_REGISTRY.getId(st.setValue(BlockStateProperties.SNOWY, true));
                                }
                                if (cy + 1 < cells) {
                                    states[i + 256] = this.snowLayerId;
                                }
                            } else {
                                states[i] = this.snowBlockId;
                            }
                        }
                    }
                    break;
                }
            }
        }
        // ---- biomes
        Map<String, Integer> palette = new HashMap<>();
        java.util.List<String> keys = new java.util.ArrayList<>();
        short[] biomes;
        if (level == 0) {
            biomes = new short[16 * (cells / 4)];
            for (int qz = 0; qz < 4; qz++) {
                for (int qx = 0; qx < 4; qx++) {
                    String key = colBiome[(qz * 4 + 1) * 16 + qx * 4 + 1].getRegisteredName();
                    int idx = palette.computeIfAbsent(key, k -> {
                        keys.add(k);
                        return keys.size() - 1;
                    });
                    for (int qy = 0; qy < cells / 4; qy++) {
                        biomes[(qy * 4 + qz) * 4 + qx] = (short)idx;
                    }
                }
            }
        } else {
            biomes = new short[256];
            for (int i = 0; i < 256; i++) {
                String key = colBiome[i].getRegisteredName();
                biomes[i] = (short)(int)palette.computeIfAbsent(key, k -> {
                    keys.add(k);
                    return keys.size() - 1;
                });
            }
        }
        this.generatedPerLevel.incrementAndGet(Math.min(7, level));
        this.nanosPerLevel.addAndGet(Math.min(7, level), System.nanoTime() - t0);
        LodData out = new LodData(level, nx, nz, cellMinY, cells, states, light, keys.toArray(String[]::new), biomes);
        out.generated = true;
        if (records != null) {
            out.trees = java.util.Arrays.copyOfRange(records, 1, 1 + records[0]);
        }
        return out;
    }

    /** final_density's gradients around the terrain density (bottom: towards 0.1171875 below y -40; top: towards -0.078125 above y 240). */
    private static float gradients(final float density, final int y) {
        float top = Math.max(0.0F, Math.min(1.0F, (256 - y) / 16.0F));
        float v = -0.078125F + top * (density + 0.078125F);
        float bottom = Math.max(0.0F, Math.min(1.0F, (y + 64) / 24.0F));
        return 0.1171875F + bottom * (v - 0.1171875F);
    }

    /** One column's lattice-row densities, bilinear in x / z at lattice coordinates (fx, fz). */
    /**
     * Lattice columns (every 4 blocks, the whole height) shared by the nodes of levels 0-2 ({@code mc.lod.colCache}): each
     * node sampled a one-step border its neighbours sample too, and levels 0, 1 and 2 sample the same 4-block grid, so most
     * columns were sampled two or three times (the noise is most of the generator's time). Direct-mapped, last write wins.
     */
    static final boolean COL_CACHE = Lod.flag("mc.lod.colCache", true);
    private static final int COL_STEP = 4;
    private static final int COL_MASK = (1 << 17) - 1;

    /** A cached lattice column; rows r0..r1 sampled (the rest unset). */
    private record Col(long key, float[] v, int r0, int r1) {
    }

    private final Col[] cols = new Col[COL_MASK + 1];

    private static int colSlot(final long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int)(h >>> 40) & COL_MASK;
    }

    /**
     * Rows of the lattice worth sampling ({@code mc.lod.genBand}, default true; coarse levels, which do not share the column
     * cache): the 3D noise is most of the lattice's time
     * and was sampled over the whole height (49 rows) though only the band around the surface is ever seen. A coarse pass
     * (every 4th row, 32 blocks) finds the node's highest solid row per column; the band runs from 2 rows (16 blocks) below
     * the lowest such row to 2 rows above the row over the highest. The true surface lies between a column's coarse solid row
     * and the next coarse row, so it stays inside the band, sampled exactly; below the band is solid (caves this deep only
     * added hidden faces), above it air. Returns {first row, last row}.
     */
    private int[] band(final DensitySamplerSet samplers, final int nL, final int ny, final int lx0, final int lz0, final int s) {
        int nyc = (ny - 1) / 4 + 1;
        // rows the gradients fix whatever the noise (sloped_cheese): solid at the bottom, air from y 256 up; not sampled
        int kLo = 0;
        int kHi = nyc - 1;
        if (!this.finalDensity) {
            while (kLo < kHi && this.minY + kLo * 32 <= -64) {
                kLo++;
            }
            while (kHi > kLo && this.minY + kHi * 32 >= 256) {
                kHi--;
            }
        }
        DensityVolume coarse = new DensityVolume(nL, kHi - kLo + 1, nL, lx0, this.minY + kLo * 32, lz0, s, 32, s);
        int minTop = Integer.MAX_VALUE;
        int maxTop = -1;
        try (ScopedDensityBuffer buf = samplers.get(this.terrain).sampleVolume(coarse)) {
            for (int z = 0; z < nL; z++) {
                for (int x = 0; x < nL; x++) {
                    int top = kLo - 1;
                    for (int k = kHi; k >= kLo; k--) {
                        float d = buf.get(coarse.indexUnchecked(x, k - kLo, z));
                        if ((this.finalDensity ? d : gradients(d, this.minY + k * 32)) > 0.0F) {
                            top = k;
                            break;
                        }
                    }
                    minTop = Math.min(minTop, top);
                    maxTop = Math.max(maxTop, top);
                }
            }
        }
        if (maxTop < 0) {
            return new int[]{0, ny - 1};
        }
        int r0 = Math.max(0, minTop * 4 - BAND_MARGIN);
        int r1 = Math.min(ny - 1, (maxTop + 1) * 4 + BAND_MARGIN);
        BAND_ROWS.addAndGet(r1 - r0 + 1 + nyc);
        BAND_NODES.incrementAndGet();
        return new int[]{r0, r1};
    }

    static final boolean BAND = Lod.flag("mc.lod.genBand", true);
    private static final boolean BAND_VERIFY = Lod.flag("mc.lod.bandVerify", false);
    private static final java.util.concurrent.atomic.AtomicLong BAND_CHECKS = new java.util.concurrent.atomic.AtomicLong();
    private static final int BAND_MARGIN = 2;
    private static final float BAND_SOLID = 1.0F;
    private static final float BAND_AIR = -1.0F;
    /** Rows sampled (band + coarse pass) and nodes, for the stats (of 49 rows each before). */
    static final java.util.concurrent.atomic.AtomicLong BAND_ROWS = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong BAND_NODES = new java.util.concurrent.atomic.AtomicLong();

    private void latticeCached(final float[] lat, final DensityVolume vol, final int nL, final int ny, final int lx0, final int lz0,
        final DensitySamplerSet samplers, final int s) {
        // mostly cold (level 2 nodes far beyond where levels 0-1 sampled): the band (see band) pays for its coarse pass
        int r0 = 0;
        int r1 = ny - 1;
        if (BAND) {
            int cold = 0;
            for (int z = 0; z < nL; z++) {
                for (int x = 0; x < nL; x++) {
                    long key = net.minecraft.world.level.ChunkPos.pack(Math.floorDiv(lx0, COL_STEP) + x, Math.floorDiv(lz0, COL_STEP) + z);
                    Col c = this.cols[colSlot(key)];
                    cold += c != null && c.key == key && c.v.length == ny ? 0 : 1;
                }
            }
            if (cold * 2 > nL * nL) {
                int[] band = this.band(samplers, nL, ny, lx0, lz0, s);
                r0 = band[0];
                r1 = band[1];
            }
        }
        Col[] got = new Col[nL * nL];
        int minX = nL, maxX = -1, minZ = nL, maxZ = -1;
        for (int z = 0; z < nL; z++) {
            for (int x = 0; x < nL; x++) {
                long key = net.minecraft.world.level.ChunkPos.pack(Math.floorDiv(lx0, COL_STEP) + x, Math.floorDiv(lz0, COL_STEP) + z);
                Col c = this.cols[colSlot(key)];
                // any cached column serves: its band holds its own surface (see band), so outside it reads solid/air as here
                if (c != null && c.key == key && c.v.length == ny) {
                    got[z * nL + x] = c;
                } else {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                }
            }
        }
        if (maxX >= 0) {
            // the missing columns' bounding box in one volume (same grid, so the same values as the whole node's volume)
            int w = maxX - minX + 1;
            int h = maxZ - minZ + 1;
            DensityVolume part = new DensityVolume(w, r1 - r0 + 1, h, lx0 + minX * COL_STEP, this.minY + r0 * 8, lz0 + minZ * COL_STEP, COL_STEP, 8, COL_STEP);
            try (ScopedDensityBuffer buf = samplers.get(this.terrain).sampleVolume(part)) {
                for (int z = 0; z < h; z++) {
                    for (int x = 0; x < w; x++) {
                        int gi = (z + minZ) * nL + x + minX;
                        if (got[gi] != null) {
                            continue;
                        }
                        float[] v = new float[ny];
                        for (int y = r0; y <= r1; y++) {
                            float d = buf.get(part.indexUnchecked(x, y - r0, z));
                            v[y] = this.finalDensity ? d : gradients(d, this.minY + y * 8);
                        }
                        long key = net.minecraft.world.level.ChunkPos.pack(Math.floorDiv(lx0, COL_STEP) + x + minX, Math.floorDiv(lz0, COL_STEP) + z + minZ);
                        got[gi] = this.cols[colSlot(key)] = new Col(key, v, r0, r1);
                    }
                }
            }
        }
        // rows outside a column's band read solid below and air above
        for (int z = 0; z < nL; z++) {
            for (int x = 0; x < nL; x++) {
                Col c = got[z * nL + x];
                for (int y = 0; y < ny; y++) {
                    lat[vol.indexUnchecked(x, y, z)] = y < c.r0 ? BAND_SOLID : y > c.r1 ? BAND_AIR : c.v[y];
                }
            }
        }
        if (COL_VERIFY && COL_VERIFIED.incrementAndGet() % 50 == 0) {
            // diagnostics: the cached lattice against the node's own volume
            float max = 0.0F;
            try (ScopedDensityBuffer buf = samplers.get(this.terrain).sampleVolume(vol)) {
                for (int z = 0; z < nL; z++) {
                    for (int x = 0; x < nL; x++) {
                        for (int y = got[z * nL + x].r0; y <= got[z * nL + x].r1; y++) {
                            int i = vol.indexUnchecked(x, y, z);
                            float d = this.finalDensity ? buf.get(i) : gradients(buf.get(i), this.minY + y * 8);
                            max = Math.max(max, Math.abs(d - lat[i]));
                        }
                    }
                }
            }
            if (max > 1e-5F) {
                Lod.LOGGER.warn("[lod] colCache mismatch {} at {},{}", max, lx0, lz0);
            }
            if (COL_VERIFIED.get() % 5000 == 0) {
                System.out.println("[lod] colCacheVerify checked " + COL_VERIFIED.get() / 50 + " nodes; band rows avg "
                    + String.format("%.1f", BAND_ROWS.get() / (double)Math.max(1L, BAND_NODES.get())) + " of " + ny);
            }
        }
    }

    static final boolean COL_VERIFY = Boolean.getBoolean("mc.lod.colCacheVerify");
    private static final java.util.concurrent.atomic.AtomicLong COL_VERIFIED = new java.util.concurrent.atomic.AtomicLong();

    private static float[] column(final float[] lat, final int nL, final int ny, final double fx, final double fz, final DensityVolume vol) {
        int ix = Math.max(0, Math.min(nL - 2, (int)Math.floor(fx)));
        int iz = Math.max(0, Math.min(nL - 2, (int)Math.floor(fz)));
        float tx = (float)Math.max(0.0, Math.min(1.0, fx - ix));
        float tz = (float)Math.max(0.0, Math.min(1.0, fz - iz));
        float[] out = new float[ny];
        for (int y = 0; y < ny; y++) {
            float a = lat[vol.indexUnchecked(ix, y, iz)];
            float b = lat[vol.indexUnchecked(ix + 1, y, iz)];
            float c = lat[vol.indexUnchecked(ix, y, iz + 1)];
            float d = lat[vol.indexUnchecked(ix + 1, y, iz + 1)];
            float ab = a + tx * (b - a);
            float cd = c + tx * (d - c);
            out[y] = ab + tz * (cd - ab);
        }
        return out;
    }

    /** Density at block y of a column (linear between lattice rows: vanilla's interpolation). */
    private static float density(final float[] v, final int y, final int minY) {
        float f = (y - minY) / 8.0F;
        int i = (int)Math.floor(f);
        if (i < 0) {
            return v[0];
        }
        if (i >= v.length - 1) {
            return v[v.length - 1];
        }
        float t = f - i;
        return v[i] + t * (v[i + 1] - v[i]);
    }

    /** Highest solid block of a column (minY - 1 if none). */
    private int surfaceY(final float[] v) {
        for (int i = v.length - 1; i >= 0; i--) {
            if (v[i] > 0.0F) {
                if (i == v.length - 1) {
                    return this.minY + i * 8;
                }
                float t = v[i] / (v[i] - v[i + 1]);
                int y = this.minY + i * 8 + (int)Math.floor(t * 8.0F - 1e-4F);
                return Math.max(this.minY + i * 8, y);
            }
        }
        return this.minY - 1;
    }

    // ---------------------------------------------------------------------------------------------------------------- trees

    /** Tree shapes (after vanilla's tree features: trunk height, foliage placer shape and size). */
    enum Kind {
        SPRUCE(Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES, 6, 4, 1),
        PINE(Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES, 7, 4, 1),
        MEGA_SPRUCE(Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES, 13, 14, 2),
        OAK(Blocks.OAK_LOG, Blocks.OAK_LEAVES, 4, 3, 1),
        FANCY_OAK(Blocks.OAK_LOG, Blocks.OAK_LEAVES, 8, 5, 1),
        BIRCH(Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES, 5, 3, 1),
        TALL_BIRCH(Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES, 8, 6, 1),
        DARK_OAK(Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_LEAVES, 6, 3, 2),
        PALE_OAK(Blocks.PALE_OAK_LOG, Blocks.PALE_OAK_LEAVES, 6, 3, 2),
        JUNGLE(Blocks.JUNGLE_LOG, Blocks.JUNGLE_LEAVES, 4, 7, 1),
        MEGA_JUNGLE(Blocks.JUNGLE_LOG, Blocks.JUNGLE_LEAVES, 12, 14, 2),
        JUNGLE_BUSH(Blocks.JUNGLE_LOG, Blocks.OAK_LEAVES, 1, 1, 1),
        ACACIA(Blocks.ACACIA_LOG, Blocks.ACACIA_LEAVES, 5, 3, 1),
        CHERRY(Blocks.CHERRY_LOG, Blocks.CHERRY_LEAVES, 6, 3, 1),
        MANGROVE(Blocks.MANGROVE_LOG, Blocks.MANGROVE_LEAVES, 6, 4, 1);

        final int log;
        final int leaves;
        final int baseHeight;
        final int heightRange;
        final int width;

        Kind(final Block log, final Block leaves, final int baseHeight, final int heightRange, final int width) {
            this.log = Block.BLOCK_STATE_REGISTRY.getId(log.defaultBlockState());
            BlockState l = leaves.defaultBlockState();
            if (l.hasProperty(LeavesBlock.DISTANCE)) {
                l = l.setValue(LeavesBlock.DISTANCE, 1);
            }
            this.leaves = Block.BLOCK_STATE_REGISTRY.getId(l);
            this.baseHeight = baseHeight;
            this.heightRange = heightRange;
            this.width = width;
        }
    }

    /** Trees of a biome: expected count per chunk and the kinds with weights (vanilla's tree features per biome). */
    record Forest(float perChunk, Kind[] kinds, float[] weights) {
        static Forest of(final float perChunk, final Object... kindsAndWeights) {
            Kind[] k = new Kind[kindsAndWeights.length / 2];
            float[] w = new float[k.length];
            for (int i = 0; i < k.length; i++) {
                k[i] = (Kind)kindsAndWeights[2 * i];
                w[i] = ((Number)kindsAndWeights[2 * i + 1]).floatValue();
            }
            return new Forest(perChunk, k, w);
        }

        Kind pick(final float r) {
            float sum = 0.0F;
            for (float x : this.weights) {
                sum += x;
            }
            float t = r * sum;
            for (int i = 0; i < this.kinds.length; i++) {
                t -= this.weights[i];
                if (t <= 0.0F) {
                    return this.kinds[i];
                }
            }
            return this.kinds[this.kinds.length - 1];
        }
    }

    private static final Forest NONE = Forest.of(0.0F, Kind.OAK, 1);
    private static final Map<String, Forest> FORESTS = new HashMap<>();

    static {
        Forest taiga = Forest.of(10.1F, Kind.SPRUCE, 2, Kind.PINE, 1);
        FORESTS.put("minecraft:taiga", taiga);
        FORESTS.put("minecraft:snowy_taiga", taiga);
        FORESTS.put("minecraft:old_growth_spruce_taiga", Forest.of(10.1F, Kind.MEGA_SPRUCE, 1, Kind.SPRUCE, 2, Kind.PINE, 1));
        FORESTS.put("minecraft:old_growth_pine_taiga", Forest.of(10.1F, Kind.MEGA_SPRUCE, 1, Kind.PINE, 2, Kind.SPRUCE, 1));
        FORESTS.put("minecraft:grove", Forest.of(10.1F, Kind.SPRUCE, 1, Kind.PINE, 1));
        FORESTS.put("minecraft:windswept_forest", Forest.of(3.1F, Kind.SPRUCE, 2, Kind.OAK, 1));
        FORESTS.put("minecraft:windswept_hills", Forest.of(0.1F, Kind.SPRUCE, 2, Kind.OAK, 1));
        FORESTS.put("minecraft:windswept_gravelly_hills", Forest.of(0.1F, Kind.SPRUCE, 2, Kind.OAK, 1));
        FORESTS.put("minecraft:forest", Forest.of(10.1F, Kind.OAK, 7, Kind.FANCY_OAK, 1, Kind.BIRCH, 2));
        FORESTS.put("minecraft:flower_forest", Forest.of(6.1F, Kind.OAK, 7, Kind.FANCY_OAK, 1, Kind.BIRCH, 2));
        FORESTS.put("minecraft:birch_forest", Forest.of(10.1F, Kind.BIRCH, 1));
        FORESTS.put("minecraft:old_growth_birch_forest", Forest.of(10.1F, Kind.TALL_BIRCH, 1));
        FORESTS.put("minecraft:dark_forest", Forest.of(16.0F, Kind.DARK_OAK, 6, Kind.BIRCH, 1, Kind.OAK, 1));
        FORESTS.put("minecraft:pale_garden", Forest.of(16.0F, Kind.PALE_OAK, 1));
        FORESTS.put("minecraft:dappled_forest", Forest.of(6.0F, Kind.OAK, 3, Kind.BIRCH, 1));
        FORESTS.put("minecraft:jungle", Forest.of(50.1F, Kind.JUNGLE_BUSH, 5, Kind.JUNGLE, 4, Kind.MEGA_JUNGLE, 1));
        FORESTS.put("minecraft:sparse_jungle", Forest.of(2.1F, Kind.JUNGLE_BUSH, 1, Kind.JUNGLE, 1));
        FORESTS.put("minecraft:bamboo_jungle", Forest.of(30.0F, Kind.JUNGLE_BUSH, 3, Kind.JUNGLE, 1, Kind.MEGA_JUNGLE, 1));
        FORESTS.put("minecraft:savanna", Forest.of(1.1F, Kind.ACACIA, 4, Kind.OAK, 1));
        FORESTS.put("minecraft:savanna_plateau", Forest.of(1.1F, Kind.ACACIA, 4, Kind.OAK, 1));
        FORESTS.put("minecraft:windswept_savanna", Forest.of(2.1F, Kind.ACACIA, 4, Kind.OAK, 1));
        FORESTS.put("minecraft:swamp", Forest.of(2.1F, Kind.OAK, 1));
        FORESTS.put("minecraft:mangrove_swamp", Forest.of(25.0F, Kind.MANGROVE, 1));
        FORESTS.put("minecraft:cherry_grove", Forest.of(10.1F, Kind.CHERRY, 1));
        FORESTS.put("minecraft:wooded_badlands", Forest.of(5.1F, Kind.OAK, 1));
        FORESTS.put("minecraft:plains", Forest.of(0.05F, Kind.OAK, 4, Kind.FANCY_OAK, 1));
        FORESTS.put("minecraft:sunflower_plains", Forest.of(0.05F, Kind.OAK, 4, Kind.FANCY_OAK, 1));
        FORESTS.put("minecraft:meadow", Forest.of(0.01F, Kind.OAK, 1, Kind.BIRCH, 1));
        FORESTS.put("minecraft:snowy_plains", Forest.of(0.1F, Kind.SPRUCE, 1));
        FORESTS.put("minecraft:ice_spikes", Forest.of(0.1F, Kind.SPRUCE, 1));
    }

    private static Forest forest(final Holder<Biome> biome) {
        Forest f = FORESTS.get(biome.getRegisteredName());
        return f == null ? NONE : f;
    }

    /** Fast deterministic mix (splitmix64 finalizer). */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * Voxelizes the trees of every chunk overlapping the node (plus a margin for crowns crossing its border) into cell counts;
     * a free cell becomes leaves (logs if it holds no leaves) where the trees fill enough of it.
     */
    /**
     * Round 4: the LodTrees record of a generated tree (crown and radius after the shapes in {@link #tree}); 0 if the kind has
     * no stand-in.
     */
    static int record(final Kind kind, final int base, final int th, final boolean snowy) {
        int top = base + th - 1;
        return switch (kind) {
            case SPRUCE -> LodTrees.pack(base, top + 1, base + Math.max(1, th / 4), 3, LodTrees.SPRUCE, snowy);
            case MEGA_SPRUCE -> LodTrees.pack(base, top + 1, base + Math.max(1, th / 4), 5, LodTrees.SPRUCE, snowy);
            case PINE -> LodTrees.pack(base, top + 1, top - Math.max(2, th / 3), 1, LodTrees.PINE, snowy);
            case OAK -> LodTrees.pack(base, top + 1, top - 2, 2, LodTrees.OAK, snowy);
            case FANCY_OAK -> LodTrees.pack(base, top + 1, top - 4, 3, LodTrees.OAK, snowy);
            case BIRCH, TALL_BIRCH -> LodTrees.pack(base, top + 1, top - 2, 2, LodTrees.BIRCH, snowy);
            case JUNGLE -> LodTrees.pack(base, top + 1, top - 2, 2, LodTrees.JUNGLE, snowy);
            case MEGA_JUNGLE -> LodTrees.pack(base, top + 1, top - 2, 4, LodTrees.JUNGLE, snowy);
            case JUNGLE_BUSH -> LodTrees.pack(base, base + 1, base, 2, LodTrees.OAK, snowy);
            case DARK_OAK -> LodTrees.pack(base, top + 1, top - 1, 3, LodTrees.DARK_OAK, snowy);
            case PALE_OAK -> LodTrees.pack(base, top + 1, top - 1, 3, LodTrees.PALE_OAK, snowy);
            case ACACIA -> LodTrees.pack(base, top + 1, top, 2, LodTrees.ACACIA, snowy);
            case CHERRY -> LodTrees.pack(base, top + 2, top - 1, 3, LodTrees.CHERRY, snowy);
            case MANGROVE -> LodTrees.pack(base, top + 1, top - 2, 3, LodTrees.MANGROVE, snowy);
        };
    }

    private void trees(final int level, final int x0, final int z0, final int c, final int cellMinY, final int cells, final float[] lat, final int nL,
        final int ny, final int lx0, final int lz0, final int s, final DensityVolume vol, final int[] states, final byte[] light,
        final Function<BlockPos, Holder<Biome>> biomeAt) {
        this.trees(level, x0, z0, c, cellMinY, cells, lat, nL, ny, lx0, lz0, s, vol, states, light, biomeAt, null, null);
    }

    private void trees(final int level, final int x0, final int z0, final int c, final int cellMinY, final int cells, final float[] lat, final int nL,
        final int ny, final int lx0, final int lz0, final int s, final DensityVolume vol, final int[] states, final byte[] light,
        final Function<BlockPos, Holder<Biome>> biomeAt, final int @Nullable [] records,
        final net.minecraft.world.level.levelgen.material.@Nullable LodMaterial ground) {
        int span = 16 * c;
        int cx0 = Math.floorDiv(x0 - 6, 16);
        int cx1 = Math.floorDiv(x0 + span + 6, 16);
        int cz0 = Math.floorDiv(z0 - 6, 16);
        int cz1 = Math.floorDiv(z0 + span + 6, 16);
        int n = 256 * cells;
        short[] leaves = new short[n];
        short[] logs = new short[n];
        int[] leafId = new int[n];
        int[] logId = new int[n];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Holder<Biome> lastBiome = null;
        int lastBiomeQuart = Integer.MIN_VALUE;
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                long h = mix(this.seed ^ mix((long)cx * 0x9E3779B97F4A7C15L + cz));
                // The biome at the chunk centre decides the forest (vanilla decorates per chunk with its biomes).
                int mx = cx * 16 + 8;
                int mz = cz * 16 + 8;
                Holder<Biome> biome = biomeAt.apply(pos.set(mx, this.seaLevel + 8, mz));
                Forest f = forest(biome);
                if (f.perChunk <= 0.0F) {
                    continue;
                }
                int count = (int)f.perChunk;
                if ((h >>> 40 & 0xFFFF) / 65536.0F < f.perChunk - count) {
                    count++;
                }
                for (int t = 0; t < count; t++) {
                    long r = mix(h + t * 0x632BE59BD9B4E019L);
                    int tx = cx * 16 + (int)(r & 15);
                    int tz = cz * 16 + (int)(r >>> 4 & 15);
                    Kind kind = f.pick((r >>> 8 & 0xFFFF) / 65536.0F);
                    int th = kind.baseHeight + (int)((r >>> 24 & 0xFF) * (kind.heightRange + 1) >> 8);
                    // Ground: the lattice column at the trunk (vanilla's interpolation), on land, not on a cliff.
                    float[] v = column(lat, nL, ny, (tx + 0.5 - lx0) / s, (tz + 0.5 - lz0) / s, vol);
                    int gy = this.surfaceY(v);
                    if (gy < this.seaLevel) {
                        continue;
                    }
                    float[] v2 = column(lat, nL, ny, (tx + 2.5 - lx0) / s, (tz + 0.5 - lz0) / s, vol);
                    float[] v3 = column(lat, nL, ny, (tx + 0.5 - lx0) / s, (tz + 2.5 - lz0) / s, vol);
                    if (Math.abs(this.surfaceY(v2) - gy) > 3 || Math.abs(this.surfaceY(v3) - gy) > 3) {
                        continue;
                    }
                    // vanilla's biome filter: the tree's own position must be in a biome with this forest (beaches, rivers and
                    // other borders inside a forest chunk stay bare)
                    if (TREE_BIOME && forest(biomeAt.apply(pos.set(tx, gy, tz))) != f) {
                        continue;
                    }
                    // and the ground under the trunk must be soil (saplings do not survive on sand, gravel or stone)
                    if (TREE_SOIL && ground != null) {
                        ground.column(tx, tz, Math.round((this.surfaceY(v2) - gy) * 0.5F), Math.round((this.surfaceY(v3) - gy) * 0.5F));
                        BlockState soil = ground.at(gy, 1, 64, Integer.MIN_VALUE);
                        if (soil != null && !SOIL.contains(soil.getBlock())) {
                            continue;
                        }
                    }
                    this.tree(kind, tx, gy, tz, th, r, x0, z0, c, cellMinY, cells, leaves, logs, leafId, logId);
                    if (records != null && tx >= x0 && tx < x0 + span && tz >= z0 && tz < z0 + span) {
                        boolean snowy = !biome.value().warmEnoughToRain(pos.set(tx, gy + th, tz), this.seaLevel);
                        int rec = record(kind, gy + 1, th, snowy);
                        if (rec != 0) {
                            if (records[0] + 3 > records.length - 1) {
                                return;   // never: sized for the node's chunks
                            }
                            int at = 1 + records[0];
                            records[at] = 2 * tx + (kind.width == 2 ? 2 : 1);
                            records[at + 1] = 2 * tz + (kind.width == 2 ? 2 : 1);
                            records[at + 2] = rec;
                            records[0] += 3;
                        }
                    }
                }
            }
        }
        float volume = (float)c * c * c;
        float need = c == 1 ? 1.0F : c == 2 ? 4.0F : Math.max(1.0F, volume * COARSE_FILL);
        for (int i = 0; i < n; i++) {
            int total = leaves[i] + logs[i];
            if (total <= 0 || states[i] != AIR_ID) {
                continue;
            }
            if (total >= need) {
                states[i] = leaves[i] > 0 ? leafId[i] : logId[i];
                // Round 8: leaves keep sky light, as real leaves do (the faces next to them read it). At 0, the ground and the
                // crowns under any leaf cell were black: flat dark plates all over jungles.
                light[i] = leaves[i] > 0 ? (byte)0xE0 : 0;
            }
        }
        if (c >= 4 && CANOPY > 0.0F && !LodTrees.drawsLevel(level)) {
            // Round 4: the real downsample keeps a forest's canopy on the ground cell (4 of 8 children solid: the lower half is
            // ground, the upper half's most frequent state is leaves), so far forests read green; a cell's volume share of
            // leaves alone (above) is far below the fill threshold at 8-32 block cells. Ground cells whose column holds
            // canopy over CANOPY of its area (leaves voxels / (c * c * 3 layers)) take the leaves.
            int canopySpan = (int)Math.ceil(14.0 / c) + 1;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int t = -1;
                    for (int y = cells - 1; y >= 0; y--) {
                        int id = states[LodData.index(x, y, z)];
                        if (id != AIR_ID) {
                            t = y;
                            break;
                        }
                    }
                    if (t < 0 || states[LodData.index(x, t, z)] == this.waterId) {
                        continue;
                    }
                    int sum = 0;
                    int best = -1;
                    int bestCount = 0;
                    for (int y = t; y < Math.min(cells, t + canopySpan); y++) {
                        int i = LodData.index(x, y, z);
                        sum += leaves[i];
                        if (leaves[i] > bestCount) {
                            bestCount = leaves[i];
                            best = leafId[i];
                        }
                    }
                    if (best >= 0 && sum >= CANOPY * c * c * 3.0F) {
                        int i = LodData.index(x, t, z);
                        if (LodData.stateOf(states[i]).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
                            continue;
                        }
                        states[i] = best;
                        light[i] = (byte)0xE0;
                    }
                }
            }
        }
    }

    private boolean solidCell(final int id) {
        return id == FILLER_ID || id != AIR_ID && LodData.stateOf(id).isSolidRender();
    }

    private void tree(final Kind kind, final int tx, final int gy, final int tz, final int th, final long r, final int x0, final int z0, final int c,
        final int cellMinY, final int cells, final short[] leaves, final short[] logs, final int[] leafId, final int[] logId) {
        int base = gy + 1;
        int top = base + th - 1;
        int w = kind.width;
        for (int y = base; y <= top; y++) {
            for (int dz = 0; dz < w; dz++) {
                for (int dx = 0; dx < w; dx++) {
                    add(tx + dx, y, tz + dz, kind.log, false, x0, z0, c, cellMinY, cells, leaves, logs, leafId, logId);
                }
            }
        }
        double ox = tx + (w - 1) * 0.5;
        double oz = tz + (w - 1) * 0.5;
        switch (kind) {
            case SPRUCE, MEGA_SPRUCE -> {
                int rmax = kind == Kind.MEGA_SPRUCE ? 5 : 3;
                int start = base + Math.max(1, th / 4);
                for (int y = start; y <= top + 1; y++) {
                    int d = top + 1 - y;
                    double rad = Math.min(rmax, (d + 1) / 2 + (d % 4 == 3 ? 0 : 0)) + (w - 1) * 0.5;
                    disk(kind, ox, y, oz, rad, x0, z0, c, cellMinY, cells, leaves, logs, leafId, logId);
                }
            }
            case PINE -> {
                int start = top - Math.max(2, th / 3);
                for (int y = start; y <= top + 1; y++) {
                    int d = top + 1 - y;
                    disk(kind, ox, y, oz, Math.min(2, (d + 1) / 2), x0, z0, c, cellMinY, cells, leaves, logs, leafId, logId);
                }
            }
            case OAK, BIRCH, TALL_BIRCH, JUNGLE -> {
                for (int y = top - 2; y <= top + 1; y++) {
                    disk(kind, ox, y, oz, y >= top ? 1.2 : 2.3, x0, z0, c, cellMinY, cells, leaves, logs, leafId, logId);
                }
            }
            case FANCY_OAK -> {
                for (int y = top - 4; y <= top + 1; y++) {
                    double d = y - (top - 1.5);
                    disk(kind, ox, y, oz, Math.sqrt(Math.max(0.0, 9.0 - d * d)), x0, z0, c, cellMinY, cells, leaves, logs, leafId, logId);
                }
            }
            case DARK_OAK, PALE_OAK -> {
                for (int y = top - 1; y <= top + 1; y++) {
                    disk(kind, ox, y, oz, y == top + 1 ? 2.5 : 3.6, x0, z0, c, cellMinY, cells, leaves, logs, leafId, logId);
                }
            }
            case MEGA_JUNGLE -> {
                for (int y = top - 2; y <= top + 1; y++) {
                    disk(kind, ox, y, oz, y == top + 1 ? 3.0 : 4.5, x0, z0, c, cellMinY, cells, leaves, logs, leafId, logId);
                }
            }
            case JUNGLE_BUSH -> {
                for (int y = base; y <= base + 1; y++) {
                    disk(kind, ox, y, oz, y == base ? 2.3 : 1.3, x0, z0, c, cellMinY, cells, leaves, logs, leafId, logId);
                }
            }
            case ACACIA -> {
                double sx = ox + ((r >>> 32 & 3) - 1.5);
                double sz = oz + ((r >>> 34 & 3) - 1.5);
                disk(kind, sx, top, sz, 2.6, x0, z0, c, cellMinY, cells, leaves, logs, leafId, logId);
                disk(kind, sx, top + 1, sz, 1.6, x0, z0, c, cellMinY, cells, leaves, logs, leafId, logId);
            }
            case CHERRY -> {
                for (int y = top - 1; y <= top + 2; y++) {
                    disk(kind, ox, y, oz, y >= top + 2 ? 2.0 : y == top + 1 ? 3.5 : 4.2, x0, z0, c, cellMinY, cells, leaves, logs, leafId, logId);
                }
            }
            case MANGROVE -> {
                for (int y = top - 2; y <= top + 1; y++) {
                    disk(kind, ox, y, oz, y == top + 1 ? 1.5 : y == top ? 2.5 : 3.2, x0, z0, c, cellMinY, cells, leaves, logs, leafId, logId);
                }
            }
        }
    }

    private static void disk(final Kind kind, final double ox, final int y, final double oz, final double rad, final int x0, final int z0, final int c,
        final int cellMinY, final int cells, final short[] leaves, final short[] logs, final int[] leafId, final int[] logId) {
        int ri = (int)Math.ceil(rad);
        double r2 = rad * rad + 0.25;
        for (int dz = -ri; dz <= ri + 1; dz++) {
            for (int dx = -ri; dx <= ri + 1; dx++) {
                double ddx = Math.floor(ox) + dx - ox;
                double ddz = Math.floor(oz) + dz - oz;
                if (ddx * ddx + ddz * ddz <= r2) {
                    add((int)Math.floor(ox) + dx, y, (int)Math.floor(oz) + dz, kind.leaves, true, x0, z0, c, cellMinY, cells, leaves, logs, leafId, logId);
                }
            }
        }
    }

    private static void add(final int bx, final int by, final int bz, final int id, final boolean leaf, final int x0, final int z0, final int c,
        final int cellMinY, final int cells, final short[] leaves, final short[] logs, final int[] leafId, final int[] logId) {
        int x = Math.floorDiv(bx - x0, c);
        int z = Math.floorDiv(bz - z0, c);
        int y = Math.floorDiv(by, c) - cellMinY;
        if (x < 0 || x > 15 || z < 0 || z > 15 || y < 0 || y >= cells) {
            return;
        }
        int i = LodData.index(x, y, z);
        if (leaf) {
            if (leaves[i] < Short.MAX_VALUE) {
                leaves[i]++;
            }
            leafId[i] = id;
        } else {
            if (logs[i] < Short.MAX_VALUE) {
                logs[i]++;
            }
            logId[i] = id;
        }
    }

    String statsJson() {
        StringBuilder sb = new StringBuilder("{\"source\": \"").append(this.source).append("\", \"perLevel\": [");
        double chunks = 0.0;
        double seconds = 0.0;
        for (int l = 0; l < 8; l++) {
            long n = this.generatedPerLevel.get(l);
            if (l > 0) {
                sb.append(", ");
            }
            sb.append(String.format(java.util.Locale.ROOT, "[%d, %.2f, %.2f]", n, n == 0 ? 0.0 : this.nanosPerLevel.get(l) / 1e6 / n,
                n == 0 ? 0.0 : this.latNanosPerLevel.get(l) / 1e6 / n));
            chunks += n * (double)(1L << 2 * l);
            seconds += this.nanosPerLevel.get(l) / 1e9;
        }
        return sb.append(String.format(java.util.Locale.ROOT, "], \"chunkEquivalents\": %.0f, \"threadSeconds\": %.2f, \"chunksPerThreadSecond\": %.0f}", chunks,
            seconds, seconds == 0.0 ? 0.0 : chunks / seconds)).toString();
    }
}
