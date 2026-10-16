import com.mojang.serialization.Lifecycle;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.regex.Pattern;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.timeline.Timeline;
import sun.misc.Unsafe;

/**
 * R5-D (CONTRACT.md 16.R5-D), -Dmc.fastAnimateTick: end-to-end parity and cost of ClientLevel's animateTick loop,
 * headless (no game, no GPU). Run with -Dmc.fastAnimateTick=true (tools/animate-tick-test.sh).
 *
 * <p>A real (patched) ClientLevel without its constructor (Unsafe; only the fields the loop reads are set): real
 * LevelChunks in a real ClientChunkCache, the vanilla biome registry (MappedRegistry) and dimension types, the real
 * EnvironmentAttributeSystem static layers (dimension constant + biome positional, as the game builds them), every block
 * state whose animateTick runs headlessly (calibrated; block entities excluded), fluids, waterlogged states, all-air
 * sections, missing chunks, positions near the build limits, nether biomes with AMBIENT_PARTICLES, marker targets.
 * addParticle / addAlwaysVisibleParticle / playLocalSound are overridden to fold every call (type, options, all
 * arguments, the sound seed drawn from the level RNG) into a hash.
 *
 * <p>Each tick runs vanilla's loop (doAnimateTick x 667 x (16, 32), exactly animateTick's body) and the fast loop
 * (ClientLevel.fastAnimateTick) from the same level RNG seed and animateRandom seed: hash, call count, level RNG seed
 * afterwards and animateRandom state afterwards must be equal. Then a terrain-like world times both (blocks of 50 ticks
 * alternate): {@code COST animateTick vanilla .. us, fast .. us}.
 */
public final class AnimateTickParityTest {
    private static Unsafe unsafe;
    private static MethodHandle fast;

    /** Never constructed normally: Unsafe.allocateInstance, fields set by reflection. */
    static final class TestLevel extends ClientLevel {
        static final Pattern IDENTITY = Pattern.compile("@[0-9a-f]+");
        long hash = 0xcbf29ce484222325L;
        int calls;

        private TestLevel() {
            super((ClientPacketListener)null, null, null, null, 0, 0, (LevelExtractor)null, false, 0L, 0);
            throw new AssertionError();
        }

        void mix(final long v) {
            this.hash = (this.hash ^ v) * 0x100000001b3L;
            this.hash ^= this.hash >>> 29;
        }

        void mixD(final double d) {
            this.mix(Double.doubleToRawLongBits(d));
        }

        private void particle(final int kind, final ParticleOptions p, final double x, final double y, final double z, final double xd,
            final double yd, final double zd) {
            this.calls++;
            this.mix(kind);
            this.mix(BuiltInRegistries.PARTICLE_TYPE.getId(p.getType()));
            // Value-based identity of the options: toString minus identity hashes (SimpleParticleType / nested types).
            this.mix(IDENTITY.matcher(p.toString()).replaceAll("").hashCode());
            this.mixD(x);
            this.mixD(y);
            this.mixD(z);
            this.mixD(xd);
            this.mixD(yd);
            this.mixD(zd);
        }

        @Override
        public void addParticle(final ParticleOptions p, final double x, final double y, final double z, final double xd, final double yd, final double zd) {
            this.particle(1, p, x, y, z, xd, yd, zd);
        }

        @Override
        public void addParticle(final ParticleOptions p, final boolean overrideLimiter, final boolean alwaysShow, final double x, final double y,
            final double z, final double xd, final double yd, final double zd) {
            this.particle(2 + (overrideLimiter ? 4 : 0) + (alwaysShow ? 8 : 0), p, x, y, z, xd, yd, zd);
        }

        @Override
        public void addAlwaysVisibleParticle(final ParticleOptions p, final double x, final double y, final double z, final double xd,
            final double yd, final double zd) {
            this.particle(16, p, x, y, z, xd, yd, zd);
        }

        @Override
        public void addAlwaysVisibleParticle(final ParticleOptions p, final boolean overrideLimiter, final double x, final double y,
            final double z, final double xd, final double yd, final double zd) {
            this.particle(32 + (overrideLimiter ? 4 : 0), p, x, y, z, xd, yd, zd);
        }

        @Override
        public void playLocalSound(final double x, final double y, final double z, final SoundEvent sound, final SoundSource source,
            final float volume, final float pitch, final boolean distanceDelay) {
            this.calls++;
            this.mix(64);
            this.mix(sound.location().hashCode());
            this.mix(source.ordinal());
            this.mixD(x);
            this.mixD(y);
            this.mixD(z);
            this.mix(Float.floatToRawIntBits(volume));
            this.mix(Float.floatToRawIntBits(pitch));
            this.mix(distanceDelay ? 1 : 0);
            this.mix(this.random.nextLong()); // vanilla draws the sound seed here
        }

        @Override
        public void playLocalSound(final Entity sourceEntity, final SoundEvent sound, final SoundSource source, final float volume, final float pitch) {
            this.calls++;
            this.mix(128);
            this.mix(this.random.nextLong());
        }
    }

    public static void main(final String[] args) throws Throwable {
        Locale.setDefault(Locale.ROOT);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Field uf = Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        unsafe = (Unsafe)uf.get(null);
        int ticks = Integer.getInteger("animate.ticks", 400);
        int costTicks = Integer.getInteger("animate.costTicks", 3000);
        System.out.println("mc.fastAnimateTick=" + Boolean.getBoolean("mc.fastAnimateTick") + " batchRng="
            + System.getProperty("mc.fastAnimateTick.batchRng", "default") + " verify=" + Boolean.getBoolean("mc.fastAnimateTick.verify"));
        if (!Boolean.getBoolean("mc.fastAnimateTick")) {
            System.out.println("run with -Dmc.fastAnimateTick=true (the fast loop's statics)");
            System.exit(2);
        }
        Method fastMethod = ClientLevel.class.getDeclaredMethod(
            "fastAnimateTick", int.class, int.class, int.class, RandomSource.class, Block.class, BlockPos.MutableBlockPos.class
        );
        fastMethod.setAccessible(true);
        fast = MethodHandles.lookup().unreflect(fastMethod);

        HolderLookup.Provider lookup = VanillaRegistries.createWorldLookup();
        MappedRegistry<Biome> biomeRegistry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        for (Holder.Reference<Biome> b : lookup.lookupOrThrow(Registries.BIOME).listElements()
            .sorted(Comparator.comparing(r -> r.key().identifier().toString())).toList()) {
            biomeRegistry.register(b.key(), b.value(), RegistrationInfo.BUILT_IN);
        }
        biomeRegistry.freeze();
        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(List.of(biomeRegistry));
        List<Holder<Timeline>> timelines = new ArrayList<>(lookup.lookupOrThrow(Registries.TIMELINE).listElements().toList());
        List<Holder.Reference<Biome>> biomes = biomeRegistry.listElements().toList();
        List<Holder.Reference<Biome>> ambientBiomes = biomes.stream()
            .filter(b -> b.value().getAttributes().contains(EnvironmentAttributes.AMBIENT_PARTICLES)).toList();
        List<Holder.Reference<Biome>> plainBiomes = biomes.stream()
            .filter(b -> !b.value().getAttributes().contains(EnvironmentAttributes.AMBIENT_PARTICLES)).toList();
        DimensionType overworld = dimension(lookup, "overworld", timelines);
        DimensionType nether = dimension(lookup, "the_nether", timelines);

        List<BlockState> palette = null;
        long total = 0;
        int mismatches = 0;
        int ticksRun = 0;
        int exceptions = 0;
        for (int phase = 0; phase < 2; phase++) {
            DimensionType dim = phase == 0 ? overworld : nether;
            TestLevel level = level(access, dim, phase == 0 ? Level.OVERWORLD : Level.NETHER);
            if (palette == null) {
                palette = calibrate(level, biomeRegistry);
            }
            SplittableRandom rnd = new SplittableRandom(4242 + phase);
            buildChaosWorld(level, palette, rnd, biomes, ambientBiomes, plainBiomes);
            for (int t = 0; t < ticks; t++) {
                int xt = rnd.nextInt(-40, 41);
                int zt = rnd.nextInt(-40, 41);
                int yt = switch (t % 4) {
                    case 0 -> level.getMinY() + rnd.nextInt(0, 24);
                    case 1 -> level.getMaxY() - rnd.nextInt(0, 24);
                    default -> rnd.nextInt(level.getMinY(), level.getMaxY() + 1);
                };
                long seed = rnd.nextLong();
                long animSeed = rnd.nextLong();
                Block marker = t % 3 == 0 ? palette.get(rnd.nextInt(palette.size())).getBlock() : null;
                String a = run(level, false, xt, yt, zt, seed, animSeed, marker);
                total += level.calls;
                String b = run(level, true, xt, yt, zt, seed, animSeed, marker);
                ticksRun++;
                if (a.contains("EXC")) {
                    exceptions++;
                }
                if (!a.equals(b) && mismatches++ < 10) {
                    System.out.printf("MISMATCH phase %d tick %d at (%d %d %d): vanilla %s / fast %s%n", phase, t, xt, yt, zt, a, b);
                }
            }
        }
        System.out.printf("PARITY %d ticks, %d particle/sound calls, %d cut short by headless-only exceptions (both paths), %d mismatches%n",
            ticksRun, total, exceptions, mismatches);

        // Cost: terrain-like overworld; blocks of 50 ticks alternate vanilla / fast to share JIT and thermal state.
        TestLevel level = level(access, overworld, Level.OVERWORLD);
        SplittableRandom rnd = new SplittableRandom(99);
        buildTerrainWorld(level, rnd, plainBiomes);
        long[] ns = new long[2];
        int[] measured = new int[2];
        for (int t = 0; t < 2 * costTicks; t++) {
            boolean useFast = (t / 50) % 2 == 1;
            int xt = rnd.nextInt(-8, 9);
            int zt = rnd.nextInt(-8, 9);
            int yt = 70 + rnd.nextInt(0, 6);
            long seed = rnd.nextLong();
            long animSeed = rnd.nextLong();
            long t0 = System.nanoTime();
            run(level, useFast, xt, yt, zt, seed, animSeed, null);
            long t1 = System.nanoTime();
            if (t >= costTicks / 2) {
                ns[useFast ? 1 : 0] += t1 - t0;
                measured[useFast ? 1 : 0]++;
            }
        }
        System.out.printf("COST animateTick vanilla %.1f us, fast %.1f us (means of %d / %d ticks, terrain world)%n",
            ns[0] / 1e3 / measured[0], ns[1] / 1e3 / measured[1], measured[0], measured[1]);
        System.out.println(mismatches == 0 && ticksRun > 0 ? "ANIMATE TICK PARITY PASSED" : "ANIMATE TICK PARITY FAILED");
        System.exit(mismatches == 0 && ticksRun > 0 ? 0 : 1);
    }

    /** One animateTick body (vanilla loop or ClientLevel.fastAnimateTick); returns "hash levelSeed animateRandomNext calls [EXC]". */
    private static String run(final TestLevel level, final boolean useFast, final int xt, final int yt, final int zt, final long seed,
        final long animSeed, final Block marker) throws Throwable {
        AtomicLong levelSeed = levelSeed(level);
        ((RandomSource)get(level, Level.class, "random")).setSeed(seed);
        RandomSource animateRandom = new SingleThreadedRandomSource(animSeed);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        level.hash = 0xcbf29ce484222325L;
        level.calls = 0;
        String exception = "";
        try {
            if (useFast) {
                fast.invoke((ClientLevel)level, xt, yt, zt, animateRandom, marker, pos);
            } else {
                for (int i = 0; i < 667; i++) {
                    level.doAnimateTick(xt, yt, zt, 16, animateRandom, marker, pos);
                    level.doAnimateTick(xt, yt, zt, 32, animateRandom, marker, pos);
                }
            }
        } catch (RuntimeException e) {
            exception = " EXC " + e.getClass().getSimpleName();
        }
        return String.format("%016x %012x %016x %d%s", level.hash, levelSeed.get(), animateRandom.nextLong(), level.calls, exception);
    }

    private static DimensionType dimension(final HolderLookup.Provider lookup, final String name, final List<Holder<Timeline>> timelines)
        throws ReflectiveOperationException {
        DimensionType d = lookup.lookupOrThrow(Registries.DIMENSION_TYPE)
            .getOrThrow(ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.withDefaultNamespace(name))).value();
        java.lang.reflect.RecordComponent[] components = DimensionType.class.getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = components[i].getName().equals("timelines") ? HolderSet.direct(timelines) : components[i].getAccessor().invoke(d);
        }
        return DimensionType.class.getDeclaredConstructor(types).newInstance(values);
    }

    @SuppressWarnings("removal")
    private static void set(final Object target, final Class<?> owner, final String name, final Object value) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        long off = unsafe.objectFieldOffset(f);
        Class<?> t = f.getType();
        if (t == boolean.class) {
            unsafe.putBoolean(target, off, (Boolean)value);
        } else if (t == byte.class) {
            unsafe.putByte(target, off, (Byte)value);
        } else if (t == int.class) {
            unsafe.putInt(target, off, (Integer)value);
        } else if (t == long.class) {
            unsafe.putLong(target, off, (Long)value);
        } else {
            unsafe.putObject(target, off, value);
        }
    }

    private static Object get(final Object target, final Class<?> owner, final String name) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static TestLevel level(final RegistryAccess access, final DimensionType dim, final ResourceKey<Level> key) throws Throwable {
        TestLevel level = (TestLevel)unsafe.allocateInstance(TestLevel.class);
        ClientLevel.ClientLevelData data = new ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false);
        set(level, Level.class, "levelData", data);
        set(level, ClientLevel.class, "clientLevelData", data);
        set(level, Level.class, "dimensionTypeRegistration", Holder.direct(dim));
        set(level, Level.class, "dimension", key);
        set(level, Level.class, "isClientSide", true);
        set(level, Level.class, "thread", Thread.currentThread());
        set(level, Level.class, "isDebug", false);
        set(level, Level.class, "registryAccess", access);
        set(level, Level.class, "palettedContainerFactory", PalettedContainerFactory.create(access));
        set(level, Level.class, "biomeManager", new BiomeManager(level, 0x5eedL));
        set(level, Level.class, "random", new LegacyRandomSource(1L));
        set(level, ClientLevel.class, "chunkSource", new ClientChunkCache(level, 8));
        // As the constructor: the system first (vanilla's static layers: dimension constant + biome positional), then the plan.
        set(level, ClientLevel.class, "environmentAttributes", EnvironmentAttributeSystem.builder().addStaticLayers(level).build());
        Method plan = ClientLevel.class.getDeclaredMethod("computeFastAmbientPlan", DimensionType.class);
        plan.setAccessible(true);
        set(level, ClientLevel.class, "fastAmbientPlan", (Byte)plan.invoke(null, dim));
        set(level, ClientLevel.class, "fastChunkGrid", new LevelChunk[25]);
        return level;
    }

    private static AtomicLong levelSeed(final Level level) throws ReflectiveOperationException {
        Object random = get(level, Level.class, "random");
        return (AtomicLong)get(random, LegacyRandomSource.class, "seed");
    }

    @SuppressWarnings("unchecked")
    private static void putChunk(final TestLevel level, final int x, final int z, final LevelChunk chunk) throws ReflectiveOperationException {
        Object storage = get(level.getChunkSource(), ClientChunkCache.class, "storage");
        Class<?> storageClass = storage.getClass();
        int viewRange = (Integer)get(storage, storageClass, "viewRange");
        AtomicReferenceArray<LevelChunk> chunks = (AtomicReferenceArray<LevelChunk>)get(storage, storageClass, "chunks");
        chunks.set(Math.floorMod(z, viewRange) * viewRange + Math.floorMod(x, viewRange), chunk);
    }

    /** Block states whose animateTick (and fluid animateTick) run in this headless level without throwing. */
    private static List<BlockState> calibrate(final TestLevel level, final MappedRegistry<Biome> biomes) throws Throwable {
        LevelChunk chunk = new LevelChunk(level, new ChunkPos(0, 0));
        chunk.fillBiomesFromNoise((qx, qy, qz) -> biomes.getOrThrow(Biomes.PLAINS));
        putChunk(level, 0, 0, chunk);
        List<BlockState> ok = new ArrayList<>();
        int rejected = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(8, 64, 8);
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(64));
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            if (state.hasBlockEntity()) {
                rejected++; // a block entity would be created through the client's renderer dispatcher
                continue;
            }
            section.setBlockState(8, 0, 8, state);
            boolean good = true;
            try {
                RandomSource r = RandomSource.create(Block.BLOCK_STATE_REGISTRY.getId(state)); // not hashCode: identity, differs per JVM
                for (int i = 0; i < 400 && good; i++) {
                    state.getBlock().animateTick(state, level, pos, r);
                    if (!state.getFluidState().isEmpty()) {
                        state.getFluidState().animateTick(level, pos, r);
                    }
                    state.isCollisionShapeFullBlock(level, pos);
                    state.isFaceSturdy(level, pos, Direction.DOWN);
                }
            } catch (Throwable e) {
                good = false;
            }
            if (good) {
                ok.add(state);
            } else {
                rejected++;
            }
        }
        section.setBlockState(8, 0, 8, Blocks.AIR.defaultBlockState());
        putChunk(level, 0, 0, null);
        System.out.printf("calibration: %d block states usable, %d rejected (block entity, or animateTick needs the full client)%n", ok.size(), rejected);
        return ok;
    }

    private static void buildChaosWorld(final TestLevel level, final List<BlockState> palette, final SplittableRandom rnd,
        final List<Holder.Reference<Biome>> biomes, final List<Holder.Reference<Biome>> ambient, final List<Holder.Reference<Biome>> plain)
        throws ReflectiveOperationException {
        List<BlockState> fluids = palette.stream().filter(s -> !s.getFluidState().isEmpty()).toList();
        List<BlockState> airs = List.of(Blocks.AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.VOID_AIR.defaultBlockState());
        for (int cx = -5; cx <= 4; cx++) {
            for (int cz = -5; cz <= 4; cz++) {
                if (rnd.nextInt(12) == 0) {
                    continue; // missing chunk: vanilla reads VOID_AIR / plains
                }
                LevelChunk chunk = new LevelChunk(level, new ChunkPos(cx, cz));
                long biomeSalt = rnd.nextLong();
                chunk.fillBiomesFromNoise((qx, qy, qz) -> {
                    long h = (qx * 0x9E3779B97F4A7C15L) ^ (qy * 0xC2B2AE3D27D4EB4FL) ^ (qz * 0x165667B19E3779F9L) ^ biomeSalt;
                    h ^= h >>> 31;
                    h *= 0xBF58476D1CE4E5B9L;
                    h ^= h >>> 29;
                    int pick = (int)Math.floorMod(h, 100L);
                    List<Holder.Reference<Biome>> from = pick < 30 ? ambient : pick < 40 ? biomes : plain;
                    return from.get((int)Math.floorMod(h >>> 8, (long)from.size()));
                });
                for (LevelChunkSection section : chunk.getSections()) {
                    int kind = rnd.nextInt(10);
                    if (kind < 3) {
                        continue; // all air (hasOnlyAir)
                    }
                    int fill = kind < 5 ? 3 : kind < 8 ? 40 : 100;
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                int p = rnd.nextInt(100);
                                if (p >= fill) {
                                    continue;
                                }
                                BlockState s = p % 7 == 0 ? fluids.get(rnd.nextInt(fluids.size()))
                                    : p % 11 == 0 ? airs.get(rnd.nextInt(3)) : palette.get(rnd.nextInt(palette.size()));
                                section.setBlockState(x, y, z, s, false);
                            }
                        }
                    }
                }
                putChunk(level, cx, cz, chunk);
            }
        }
    }

    private static void buildTerrainWorld(final TestLevel level, final SplittableRandom rnd, final List<Holder.Reference<Biome>> plain)
        throws ReflectiveOperationException {
        Holder<Biome> taiga = plain.stream().filter(b -> b.key().identifier().getPath().equals("snowy_taiga")).findFirst().orElse(plain.getFirst());
        Holder<Biome> river = plain.stream().filter(b -> b.key().identifier().getPath().equals("frozen_river")).findFirst().orElse(plain.getFirst());
        BlockState[] ground = {Blocks.STONE.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
            Blocks.GRAVEL.defaultBlockState(), Blocks.ANDESITE.defaultBlockState()};
        for (int cx = -4; cx <= 4; cx++) {
            for (int cz = -4; cz <= 4; cz++) {
                LevelChunk chunk = new LevelChunk(level, new ChunkPos(cx, cz));
                int fcx = cx;
                chunk.fillBiomesFromNoise((qx, qy, qz) -> fcx == 1 ? river : taiga);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int h = 66 + ((x * 7 + z * 3 + cx * 5 + cz * 11) & 3);
                        for (int y = level.getMinY(); y <= h + 12; y++) {
                            BlockState s;
                            if (y < h - 4) {
                                s = ground[rnd.nextInt(ground.length)];
                                if (y < 20 && rnd.nextInt(60) == 0) {
                                    s = Blocks.CAVE_AIR.defaultBlockState();
                                }
                            } else if (y < h) {
                                s = cx == 1 ? Blocks.WATER.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                            } else if (y == h) {
                                s = cx == 1 ? Blocks.ICE.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
                            } else if (y == h + 1 && rnd.nextInt(6) == 0) {
                                s = Blocks.SNOW.defaultBlockState();
                            } else if (y > h + 3 && ((x + z + cx) % 5 == 0) && rnd.nextInt(3) > 0) {
                                s = Blocks.CHERRY_LEAVES.defaultBlockState(); // untinted: tinted leaves need Minecraft's BlockColors
                            } else if (y > h && y <= h + 3 && (x + z + cx) % 5 == 0 && x % 5 == 0) {
                                s = Blocks.SPRUCE_LOG.defaultBlockState();
                            } else {
                                continue;
                            }
                            chunk.getSection(chunk.getSectionIndex(y)).setBlockState(x, y & 15, z, s, false);
                        }
                    }
                }
                putChunk(level, cx, cz, chunk);
            }
        }
    }
}
