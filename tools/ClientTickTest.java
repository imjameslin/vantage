import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.AmbientParticle;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.timeline.Timeline;

/**
 * R5-D (CONTRACT.md 16.R5-D), -Dmc.fastAnimateTick: headless checks of the facts the exact ClientLevel.animateTick fast
 * path relies on, against the patched ClientLevel's own helpers and the real vanilla registries (no game, no GPU):
 *
 * <ol>
 *   <li>Batched RNG: ClientLevel.fastLcg / fastNextIntPow2, composed as doAnimateTickFast does (six steps, one final
 *       seed), equal LegacyRandomSource.nextInt(16 / 32) x6 and its final seed, for random and edge seeds.</li>
 *   <li>Ambient plan: ClientLevel.computeFastAmbientPlan is 1 (skippable) for every vanilla dimension type; no vanilla
 *       timeline carries AMBIENT_PARTICLES.</li>
 *   <li>Skip exactness against the real EnvironmentAttributeSystem (built with vanilla's addStaticLayers over a level
 *       stub with the vanilla biome registry): for every dimension type x biome, whenever the fast path would skip
 *       (plan 1 and the biome has no AMBIENT_PARTICLES entry) the vanilla getValue is the empty list; biomes with the
 *       entry get a non-empty list (the system really is consulted).</li>
 *   <li>Lookup identities: AIR / VOID_AIR / CAVE_AIR fluid is EMPTY's default state; inside the build height of every
 *       vanilla dimension type the quart Y needs no clamp and lands in the block's section.</li>
 * </ol>
 */
public final class ClientTickTest {
    private static int checks;
    private static int failures;

    public static void main(final String[] args) throws Throwable {
        Locale.setDefault(Locale.ROOT);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Class<?> clientLevel = Class.forName("net.minecraft.client.multiplayer.ClientLevel");
        Method lcg = clientLevel.getDeclaredMethod("fastLcg", long.class);
        Method nextInt = clientLevel.getDeclaredMethod("fastNextIntPow2", long.class, int.class);
        Method plan = clientLevel.getDeclaredMethod("computeFastAmbientPlan", DimensionType.class);
        lcg.setAccessible(true);
        nextInt.setAccessible(true);
        plan.setAccessible(true);
        rng(lcg, nextInt);
        HolderLookup.Provider registries = VanillaRegistries.createWorldLookup();
        ambient(registries, plan);
        lookups(registries);
        System.out.printf("ClientTickTest: %d checks, %d failures%n", checks, failures);
        System.out.println(failures == 0 ? "CLIENT TICK TEST PASSED" : "CLIENT TICK TEST FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(final boolean ok, final String what) {
        checks++;
        if (!ok) {
            if (failures++ < 30) {
                System.out.println("FAIL: " + what);
            }
        }
    }

    private static void rng(final Method lcg, final Method nextInt) throws Throwable {
        SplittableRandom seeds = new SplittableRandom(26_3L);
        LegacyRandomSource vanilla = new LegacyRandomSource(0L);
        java.lang.reflect.Field seedField = LegacyRandomSource.class.getDeclaredField("seed");
        seedField.setAccessible(true);
        AtomicLong vanillaSeed = (AtomicLong)seedField.get(vanilla);
        long[] edge = {0L, 1L, 281474976710655L, 25214903917L, 140737488355328L, 140737488355327L, 11L};
        int bad = 0;
        int n = 200_000;
        for (int i = 0; i < n + edge.length; i++) {
            long s0 = i < edge.length ? edge[i] : seeds.nextLong() & 281474976710655L;
            for (int r : new int[]{16, 32}) {
                vanilla.setSeed(s0 ^ 25214903917L); // raw seed = s0
                int vx = vanilla.nextInt(r) - vanilla.nextInt(r);
                int vy = vanilla.nextInt(r) - vanilla.nextInt(r);
                int vz = vanilla.nextInt(r) - vanilla.nextInt(r);
                long vFinal = vanillaSeed.get();
                long s = (long)lcg.invoke(null, s0);
                int a = (int)nextInt.invoke(null, s, r);
                s = (long)lcg.invoke(null, s);
                int b = (int)nextInt.invoke(null, s, r);
                s = (long)lcg.invoke(null, s);
                int c = (int)nextInt.invoke(null, s, r);
                s = (long)lcg.invoke(null, s);
                int d = (int)nextInt.invoke(null, s, r);
                s = (long)lcg.invoke(null, s);
                int e = (int)nextInt.invoke(null, s, r);
                s = (long)lcg.invoke(null, s);
                int f = (int)nextInt.invoke(null, s, r);
                if (a - b != vx || c - d != vy || e - f != vz || s != vFinal) {
                    bad++;
                }
            }
        }
        check(bad == 0, "batched RNG equals LegacyRandomSource for " + 2 * (n + edge.length) + " seed/bound pairs (" + bad + " differ)");
    }

    private static void ambient(final HolderLookup.Provider registries, final Method plan) throws Throwable {
        HolderLookup.RegistryLookup<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
        List<Holder.Reference<DimensionType>> dimensions = registries.lookupOrThrow(Registries.DIMENSION_TYPE).listElements().toList();
        check(dimensions.size() >= 4, "vanilla dimension types present (" + dimensions.size() + ")");
        int timelinesWith = 0;
        List<Holder.Reference<Timeline>> timelines = registries.lookupOrThrow(Registries.TIMELINE).listElements().toList();
        for (Holder.Reference<Timeline> t : timelines) {
            if (t.value().attributes().contains(EnvironmentAttributes.AMBIENT_PARTICLES)) {
                timelinesWith++;
                System.out.println("  timeline with AMBIENT_PARTICLES: " + t.key().identifier());
            }
        }
        check(timelinesWith == 0, "no vanilla timeline has AMBIENT_PARTICLES");
        @SuppressWarnings("unchecked")
        Registry<Biome> biomeRegistry = (Registry<Biome>)Proxy.newProxyInstance(
            ClientTickTest.class.getClassLoader(), new Class<?>[]{Registry.class}, (proxy, m, a) -> switch (m.getName()) {
                case "listElements" -> biomes.listElements();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == a[0];
                case "toString" -> "biome registry stub";
                default -> throw new UnsupportedOperationException("Registry stub: " + m);
            }
        );
        RegistryAccess access = (RegistryAccess)Proxy.newProxyInstance(
            ClientTickTest.class.getClassLoader(), new Class<?>[]{RegistryAccess.class}, (proxy, m, a) -> switch (m.getName()) {
                case "lookupOrThrow" -> {
                    if (a[0] != Registries.BIOME) {
                        throw new UnsupportedOperationException("RegistryAccess stub: " + a[0]);
                    }
                    yield biomeRegistry;
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == a[0];
                case "toString" -> "registry access stub";
                default -> throw new UnsupportedOperationException("RegistryAccess stub: " + m);
            }
        );
        List<Holder.Reference<Biome>> allBiomes = biomes.listElements().toList();
        @SuppressWarnings("unchecked")
        Holder<Biome>[] current = new Holder[1];
        BiomeManager biomeManager = new BiomeManager((qx, qy, qz) -> current[0], 0L);
        int skipped = 0;
        int consulted = 0;
        // The code-built lookup leaves the timeline tags (#in_overworld, ...) unbound: give every dimension type all
        // vanilla timelines (a superset of each tag) so the plan really iterates them.
        HolderSet<Timeline> allTimelines = HolderSet.direct(new ArrayList<Holder<Timeline>>(timelines));
        // Negative cases: a timeline track or a non-empty dimension constant must disable the skip.
        DimensionType base = withComponent(dimensions.getFirst().value(), "timelines", allTimelines);
        EnvironmentAttributeMap ash = EnvironmentAttributeMap.builder()
            .set(EnvironmentAttributes.AMBIENT_PARTICLES, AmbientParticle.of(ParticleTypes.ASH, 0.5F))
            .build();
        check((byte)plan.invoke(null, withComponent(base, "attributes", ash)) == 2, "non-empty dimension AMBIENT_PARTICLES disables the skip");
        check((byte)plan.invoke(null, base) == 1, "plan with every vanilla timeline is skippable");
        // r5-int review fix (D): a timeline with an AMBIENT_PARTICLES track (a server data pack can add one) disables the
        // skip, whichever position it has in the dimension's timeline set. Built from a vanilla timeline (same clock and
        // period) with an extra track keyed by AMBIENT_PARTICLES; the plan only looks at the timeline's attribute set.
        Timeline donor = timelines.getFirst().value();
        java.lang.reflect.Field tracksField = Timeline.class.getDeclaredField("tracks");
        tracksField.setAccessible(true);
        java.util.Map<?, ?> donorTracks = (java.util.Map<?, ?>)tracksField.get(donor);
        check(!donorTracks.isEmpty(), "donor timeline has tracks");
        java.util.Map<Object, Object> tracks = new java.util.HashMap<>(donorTracks);
        tracks.put(EnvironmentAttributes.AMBIENT_PARTICLES, donorTracks.values().iterator().next());
        java.lang.reflect.Constructor<Timeline> timelineCtor = Timeline.class.getDeclaredConstructor(
            Holder.class, java.util.Optional.class, java.util.Map.class, java.util.Map.class);
        timelineCtor.setAccessible(true);
        java.lang.reflect.Field markersField = Timeline.class.getDeclaredField("timeMarkers");
        markersField.setAccessible(true);
        Timeline particleTimeline = timelineCtor.newInstance(donor.clock(), donor.periodTicks(), tracks, markersField.get(donor));
        check(particleTimeline.attributes().contains(EnvironmentAttributes.AMBIENT_PARTICLES), "synthetic timeline carries AMBIENT_PARTICLES");
        for (int position : new int[]{0, timelines.size() / 2, timelines.size()}) {
            List<Holder<Timeline>> withParticles = new ArrayList<>(timelines);
            withParticles.add(position, Holder.direct(particleTimeline));
            byte p = (byte)plan.invoke(null, withComponent(base, "timelines", HolderSet.direct(withParticles)));
            check(p == 2, "a timeline track for AMBIENT_PARTICLES (at position " + position + " of " + withParticles.size() + ") disables the skip (got " + p + ")");
        }
        check((byte)plan.invoke(null, withComponent(base, "timelines", HolderSet.direct(List.<Holder<Timeline>>of(Holder.direct(particleTimeline))))) == 2,
            "a dimension whose only timeline carries AMBIENT_PARTICLES is not skippable");
        // The weather layers (WeatherAttributes.RAIN / THUNDER, added by addDefaultLayers in every dimension) are checked
        // by the plan the same way; vanilla's maps do not carry the attribute (a positive case would need a different
        // vanilla constant, so only their content is pinned here).
        check(!net.minecraft.world.attribute.WeatherAttributes.RAIN.contains(EnvironmentAttributes.AMBIENT_PARTICLES)
            && !net.minecraft.world.attribute.WeatherAttributes.THUNDER.contains(EnvironmentAttributes.AMBIENT_PARTICLES),
            "vanilla rain / thunder layers carry no AMBIENT_PARTICLES");
        for (Holder.Reference<DimensionType> reference : dimensions) {
            DimensionType dimensionValue = withComponent(reference.value(), "timelines", allTimelines);
            Holder<DimensionType> dimension = Holder.direct(dimensionValue);
            String dimensionName = reference.key().identifier().toString();
            byte p = (byte)plan.invoke(null, dimensionValue);
            check(p == 1, "ambient plan skippable for " + dimensionName + " (got " + p + ")");
            LevelAccessor level = (LevelAccessor)Proxy.newProxyInstance(
                ClientTickTest.class.getClassLoader(), new Class<?>[]{LevelAccessor.class}, (proxy, m, a) -> switch (m.getName()) {
                    case "registryAccess" -> access;
                    case "getBiomeManager" -> biomeManager;
                    case "dimensionType" -> dimension.value();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == a[0];
                    case "toString" -> "level stub";
                    default -> throw new UnsupportedOperationException("LevelAccessor stub: " + m);
                }
            );
            EnvironmentAttributeSystem system = EnvironmentAttributeSystem.builder().addStaticLayers(level).build();
            for (Holder.Reference<Biome> biome : allBiomes) {
                current[0] = biome;
                boolean has = biome.value().getAttributes().contains(EnvironmentAttributes.AMBIENT_PARTICLES);
                for (Vec3 pos : List.of(new Vec3(0.5, 64.5, 0.5), new Vec3(-1234.5, -40.5, 777.5), new Vec3(29999999.5, 319.5, -29999999.5))) {
                    List<AmbientParticle> value = system.getValue(EnvironmentAttributes.AMBIENT_PARTICLES, pos);
                    if (p == 1 && !has) {
                        skipped++;
                        check(value.isEmpty(), "skip is exact: " + biome.key().identifier() + " in " + dimensionName + " gives " + value);
                    } else {
                        consulted++;
                        check(!value.isEmpty(), "biome with AMBIENT_PARTICLES yields particles: " + biome.key().identifier());
                    }
                }
            }
        }
        long withAmbient = allBiomes.stream().filter(b -> b.value().getAttributes().contains(EnvironmentAttributes.AMBIENT_PARTICLES)).count();
        System.out.printf("  %d biomes, %d with AMBIENT_PARTICLES; %d skip cases exact, %d consulted%n", allBiomes.size(), withAmbient, skipped, consulted);
        check(withAmbient >= 1 && withAmbient < allBiomes.size(), "some but not all biomes carry AMBIENT_PARTICLES");
    }

    /** A copy of a record with one component replaced (canonical constructor). */
    private static <R extends Record> R withComponent(final R record, final String name, final Object value) throws ReflectiveOperationException {
        java.lang.reflect.RecordComponent[] components = record.getClass().getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        boolean found = false;
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = components[i].getName().equals(name) ? value : components[i].getAccessor().invoke(record);
            found |= components[i].getName().equals(name);
        }
        if (!found) {
            throw new NoSuchFieldException(name);
        }
        @SuppressWarnings("unchecked")
        R copy = (R)record.getClass().getDeclaredConstructor(types).newInstance(values);
        return copy;
    }

    private static void lookups(final HolderLookup.Provider registries) {
        for (BlockState air : List.of(Blocks.AIR.defaultBlockState(), Blocks.VOID_AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState())) {
            check(air.getFluidState() == Fluids.EMPTY.defaultFluidState(), air + " fluid is EMPTY's default state");
        }
        for (Holder.Reference<DimensionType> dimension : registries.lookupOrThrow(Registries.DIMENSION_TYPE).listElements().toList()) {
            int minY = dimension.value().minY();
            int height = dimension.value().height();
            int maxY = minY + height - 1;
            int quartMinY = minY >> 2;
            int quartMaxY = quartMinY + (height >> 2) - 1;
            int bad = 0;
            for (int y = minY; y <= maxY; y++) {
                int qy = y >> 2;
                int clamped = Mth.clamp(qy, quartMinY, quartMaxY);
                if (clamped != qy || (qy << 2) >> 4 != y >> 4) {
                    bad++;
                }
            }
            check(bad == 0, "quart Y inside the build height of " + dimension.key().identifier() + " needs no clamp (" + bad + " bad)");
        }
    }
}
