package net.minecraft.client.renderer.chunk;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** CPU-only calls to actual LodGpu group/cached append methods, using the existing independent scalar packing
 * reference for cacheItems OFF. The production scalar loop stays inline and unchanged in buildItems.
 * Constructor-free owners contain only Java
 * metadata; no renderer, GPU buffers, device, queue or native method is initialized. Run lod-group-lookup-test.sh. */
public final class LodGroupLookupTest {
    private static int checks;
    private static final Class<?> ARENA;
    private static final Method GROUP, SCALAR_REFERENCE, CACHED, RELEASE;
    static {
        try {
            ARENA = Class.forName(LodGpu.class.getName() + "$Arena");
            GROUP = method("group", int.class, ARENA, boolean.class, int.class);
            SCALAR_REFERENCE = LodSectionItemsTest.class.getDeclaredMethod("scalar", int.class, int[].class, int[].class, int[][].class);
            SCALAR_REFERENCE.setAccessible(true);
            CACHED = method("appendCachedSection", LodGpu.GpuSection.class, boolean.class, int.class);
            RELEASE = method("releaseGroupLookups");
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static Object get(Object object, String name) throws Exception { return field(object.getClass(), name).get(object); }
    private static void set(Object object, String name, Object value) throws Exception { field(object.getClass(), name).set(object, value); }
    private static Method method(String name, Class<?>... parameters) throws Exception {
        Method m = LodGpu.class.getDeclaredMethod(name, parameters); m.setAccessible(true); return m;
    }
    private static Object blank(Class<?> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Object unsafe = field(unsafeType, "theUnsafe").get(null);
        return unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
    }
    private static LodGpu owner() throws Exception {
        LodGpu owner = (LodGpu)blank(LodGpu.class);
        set(owner, "groups", new ArrayList<>()); set(owner, "arenas", new ArrayList<>());
        set(owner, "secItems", new int[4]);
        set(owner, "drawnQuadsByLevel", new long[8]); set(owner, "drawnQuadsBySlot", new long[8]);
        set(owner, "groupLookupCalls", new long[2]); set(owner, "groupLookupProbes", new long[2]);
        return owner;
    }
    @SuppressWarnings("unchecked")
    private static List<Object> list(LodGpu owner, String name) throws Exception { return (List<Object>)get(owner, name); }
    private static Object arena(LodGpu owner, int index, boolean compact) throws Exception {
        Object arena = blank(ARENA); set(arena, "index", index); set(arena, "compact", compact);
        list(owner, "arenas").add(arena); return arena;
    }
    private record Key(int layer, Object arena, boolean fade, int level) { }
    private static Object group(LodGpu owner, Key key) throws Exception {
        return GROUP.invoke(owner, key.layer, key.arena, key.fade, key.level);
    }
    private static long counter(LodGpu owner, String name, int mode) throws Exception { return ((long[])get(owner, name))[mode]; }

    private static void lookupContract(boolean diagnostics) throws Exception {
        LodGpu owner = owner();
        // Same numeric index, different identity: a global index-only cache would silently return the wrong group.
        Object a = arena(owner, 0, false), b = arena(owner, 0, true);
        List<Key> keys = new ArrayList<>(); List<Object> original = new ArrayList<>();
        for (Object arena : List.of(a, b)) for (int layer = 2; layer >= 0; layer--)
            for (boolean fade : new boolean[]{true, false}) for (int level = 7; level >= -1; level--)
                keys.add(new Key(layer, arena, fade, level));
        for (Key key : keys) original.add(group(owner, key));
        check(original.size() == 108 && list(owner, "groups").size() == 108, "all valid keys, both arena identities");
        check(get(a, "groupLookup") == null && get(b, "groupLookup") == null, "OFF creates no lookup tables");
        List<Object> originalOrder = new ArrayList<>(list(owner, "groups"));
        long expectedProbes = 0;
        for (Object g : original) expectedProbes += originalOrder.indexOf(g) + 1;
        set(owner, "buildCacheGroups", true);
        LodGpu.cacheGroups = false; // Already sampled ON: a mid-build toggle must not switch the lookup path.
        long before = counter(owner, "groupLookupProbes", 1);
        for (int i = 0; i < keys.size(); i++) check(group(owner, keys.get(i)) == original.get(i), "first ON seeds existing identity");
        check(counter(owner, "groupLookupProbes", 1) - before == (diagnostics ? expectedProbes : 0), "cold ON scans original list first");
        check(list(owner, "groups").equals(originalOrder), "activation preserves stable tie order and creates no duplicates");
        check((int)get(owner, "groupLookupTables") == 2, "one table per arena");
        for (Object arena : List.of(a, b)) {
            Object[] table = (Object[])get(arena, "groupLookup");
            check(table.length == 54 && Arrays.stream(table).allMatch(x -> x != null), "all 54 table slots populated without collision");
        }
        before = counter(owner, "groupLookupProbes", 1);
        long hits = (long)get(owner, "groupLookupHits");
        for (int i = keys.size() - 1; i >= 0; i--) check(group(owner, keys.get(i)) == original.get(i), "warm lookup exact identity");
        check(counter(owner, "groupLookupProbes", 1) == before, "warm lookup scans zero groups");
        check((long)get(owner, "groupLookupHits") - hits == (diagnostics ? keys.size() : 0), "diagnostics hit count or zero cost disabled");
        set(owner, "buildCacheGroups", false);
        LodGpu.cacheGroups = true; // Sampled OFF must still scan, despite warmed tables and a live toggle.
        before = counter(owner, "groupLookupProbes", 0);
        for (int i = 0; i < keys.size(); i++) check(group(owner, keys.get(i)) == original.get(i), "OFF after ON exact identity");
        check(counter(owner, "groupLookupProbes", 0) - before == (diagnostics ? expectedProbes : 0), "OFF retains original scalar scan");
        check((int)get(owner, "groupLookupTables") == 2, "OFF retains bounded warm tables");
        set(owner, "buildCacheGroups", true);
        Object fresh = arena(owner, 1, false);
        Key freshKey = new Key(1, fresh, true, 7);
        Object freshGroup = group(owner, freshKey);
        check(group(owner, freshKey) == freshGroup && (int)get(owner, "groupLookupTables") == 3, "new arena ON creates one group then hits");
        for (int layer : new int[]{-1, 0, 2, 3}) for (int level : new int[]{Integer.MIN_VALUE, -2, 8, Integer.MAX_VALUE}) {
            Key key = new Key(layer, a, false, level);
            Object invalid = group(owner, key);
            before = counter(owner, "groupLookupProbes", 1);
            check(group(owner, key) == invalid, "invalid key falls back without new exception or duplicate");
            check(counter(owner, "groupLookupProbes", 1) - before == (diagnostics ? list(owner, "groups").indexOf(invalid) + 1 : 0),
                "invalid key uses scalar lookup");
        }
        for (int layer : new int[]{-1, 3}) {
            Key key = new Key(layer, b, true, 0);
            Object invalid = group(owner, key);
            check(group(owner, key) == invalid, "invalid layer with valid level falls back");
        }
        check((int)get(owner, "groupLookupTables") == 3, "invalid keys never allocate more tables");
        List<Object> beforeRelease = new ArrayList<>(list(owner, "groups"));
        RELEASE.invoke(owner); RELEASE.invoke(owner);
        check((int)get(owner, "groupLookupTables") == 0, "CPU lookup release idempotent");
        for (Object arena : list(owner, "arenas")) check(get(arena, "groupLookup") == null, "release clears per-arena reference");
        check(list(owner, "groups").equals(beforeRelease), "lookup release leaves original group ownership intact");
        if (!diagnostics) {
            check(Arrays.equals((long[])get(owner, "groupLookupCalls"), new long[2]), "disabled diagnostic call counters untouched");
            check(Arrays.equals((long[])get(owner, "groupLookupProbes"), new long[2]), "disabled diagnostic probe counters untouched");
        }
    }

    private static LodGpu.GpuSection section(int id) {
        LodGpu.GpuSection s = new LodGpu.GpuSection(); s.slot = id == 4 ? (1 << 20) - 1 : 30 + id;
        for (int layer = 0; layer < 3; layer++) {
            s.arena[layer] = id >= 3 ? 2 : (id + layer) % 2;
            s.firstQuad[layer] = id * 32768 + layer * 64;
            if (id == 2 && layer != 1) continue;
            int count = id == 2 ? 0 : id == 0 && layer == 0 ? 1031 : 3 + id + layer;
            int[] c = s.clusters[layer] = new int[count * 3];
            for (int i = 0; i < count; i++) {
                c[3 * i] = (i & 65535) | (i % 64) << 16 | (i % 8) << 22 | (i % 2) << 25;
                c[3 * i + 1] = id | (layer + 1) << 10 | (i % 16) << 20;
                c[3 * i + 2] = (id + 4) | (layer + 8) << 10 | (i % 16 + 9) << 20;
            }
        }
        if (id == 1) s.arena[2] = -1; // Non-null metadata with no arena must remain omitted.
        return s;
    }
    private static void reset(LodGpu owner, boolean cachedGroups) throws Exception {
        set(owner, "buildCacheGroups", cachedGroups); set(owner, "secCount", 0);
        set(owner, "drawnClusters", 0); set(owner, "drawnQuads", 0L);
        Arrays.fill((long[])get(owner, "drawnQuadsByLevel"), 0L);
        Arrays.fill((long[])get(owner, "drawnQuadsBySlot"), 0L);
        for (Object g : list(owner, "groups")) { set(g, "count", 0); set(g, "quads", 0L); }
    }
    // Test-only adapter: independent existing scalar packing, routed through the real group lookup in either mode.
    // Deliberately does not extract or invoke the production buildItems loop (which also uploads GPU buffers).
    private static void appendScalarReference(LodGpu owner, LodGpu.GpuSection s, boolean fade, int level) throws Exception {
        Object packed = SCALAR_REFERENCE.invoke(null, s.slot, s.arena, s.firstQuad, s.clusters);
        int[] section = (int[])get(packed, "section");
        if (section != null) {
            int count = (int)get(owner, "secCount"), at = count * 4;
            int[] output = Arrays.copyOf((int[])get(owner, "secItems"), at + 4);
            System.arraycopy(section, 0, output, at, 4);
            set(owner, "secItems", output); set(owner, "secCount", count + 1);
        }
        int[][] items = (int[][])get(packed, "items");
        long[] quads = (long[])get(packed, "quads");
        for (int layer = 0; layer < 3; layer++) {
            if (items[layer] == null) continue;
            Object g = group(owner, new Key(layer, list(owner, "arenas").get(s.arena[layer]), fade, LodGpu.DIAG_LEVELS ? level : -1));
            int oldCount = (int)get(g, "count"), count = items[layer].length / 4;
            int[] output = Arrays.copyOf((int[])get(g, "items"), (oldCount + count) * 4);
            System.arraycopy(items[layer], 0, output, oldCount * 4, items[layer].length);
            set(g, "items", output); set(g, "count", oldCount + count);
            set(g, "quads", (long)get(g, "quads") + quads[layer]);
            set(owner, "drawnClusters", (int)get(owner, "drawnClusters") + count);
            set(owner, "drawnQuads", (long)get(owner, "drawnQuads") + quads[layer]);
            ((long[])get(owner, "drawnQuadsByLevel"))[Math.min(7, level)] += quads[layer];
        }
        long[] facing = (long[])get(packed, "facing"), totals = (long[])get(owner, "drawnQuadsBySlot");
        for (int direction = 0; direction < 8; direction++) totals[direction] += facing[direction];
    }
    private static void equalOutput(LodGpu actual, LodGpu scalar) throws Exception {
        List<Object> a = list(actual, "groups"), b = list(scalar, "groups");
        check(a.size() == b.size(), "group count including empty layers");
        for (int i = 0; i < a.size(); i++) {
            Object x = a.get(i), y = b.get(i);
            for (String name : List.of("layer", "fade", "level", "count", "quads"))
                check(get(x, name).equals(get(y, name)), "ordered group field " + name);
            check(get(get(x, "arena"), "index").equals(get(get(y, "arena"), "index")), "ordered arena index");
            check(get(get(x, "arena"), "compact").equals(get(get(y, "arena"), "compact")), "ordered arena layout");
            int words = (int)get(x, "count") * 4;
            check(Arrays.equals(Arrays.copyOf((int[])get(x, "items"), words), Arrays.copyOf((int[])get(y, "items"), words)),
                "exact ordered group bytes (same concatenation sent to native ring)");
        }
        for (String name : List.of("secCount", "drawnClusters", "drawnQuads")) check(get(actual, name).equals(get(scalar, name)), "total " + name);
        for (String name : List.of("drawnQuadsByLevel", "drawnQuadsBySlot"))
            check(Arrays.equals((long[])get(actual, name), (long[])get(scalar, name)), "total " + name);
        int words = (int)get(actual, "secCount") * 4;
        check(Arrays.equals(Arrays.copyOf((int[])get(actual, "secItems"), words), Arrays.copyOf((int[])get(scalar, "secItems"), words)),
            "exact ordered section bytes");
    }
    private static void appendMatrix() throws Exception {
        for (boolean items : new boolean[]{false, true}) for (boolean groups : new boolean[]{false, true}) {
            LodGpu actual = owner(), scalar = owner();
            for (LodGpu o : List.of(actual, scalar)) { arena(o, 0, false); arena(o, 1, true); }
            LodGpu.GpuSection[] sections = new LodGpu.GpuSection[5], reference = new LodGpu.GpuSection[5];
            for (int i = 0; i < sections.length; i++) { sections[i] = section(i); reference[i] = section(i); }
            int[][] orders = {{1, 0, 2, 1}, {3, 0, 2, 1, 4}, {4, 3, 1, 0, 2, 4}, {1, 4, 0, 3, 2}};
            for (int round = 0; round < orders.length; round++) {
                if (round == 1) { arena(actual, 2, false); arena(scalar, 2, false); }
                // OFF -> ON -> OFF -> ON. Existing groups, warmed entries and new fade/level keys coexist.
                boolean useGroups = groups && round % 2 == 1;
                boolean useItems = items && round > 0;
                reset(actual, useGroups); reset(scalar, false);
                LodGpu.cacheItems = useItems; LodGpu.cacheGroups = useGroups;
                for (int at = 0; at < orders[round].length; at++) {
                    int i = orders[round][at], level = (i * 3 + round) % 8;
                    boolean fade = (at + round) % 2 == 0;
                    if (useItems) CACHED.invoke(actual, sections[i], fade, level);
                    else appendScalarReference(actual, sections[i], fade, level);
                    appendScalarReference(scalar, reference[i], fade, level);
                }
                equalOutput(actual, scalar);
                check((int)get(actual, "groupLookupTables") <= 3, "append table memory bounded by actual arenas");
            }
            check((long)get(actual, "itemCacheBuilds") == (items ? 5 : 0), "item-cache modes actually exercised");
            check((int)get(actual, "groupLookupTables") == (groups ? 3 : 0), "group-cache modes actually exercised");
        }
    }
    public static void main(String[] args) throws Exception {
        boolean initial = Boolean.parseBoolean(args[0]), diagnostics = Boolean.parseBoolean(args[1]);
        Field flag = field(LodGpu.class, "cacheGroups"); int modifiers = flag.getModifiers();
        check(Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && Modifier.isVolatile(modifiers), "public static volatile flag");
        check(LodGpu.cacheGroups == initial, "default/property cacheGroups");
        check((boolean)field(LodGpu.class, "GROUP_LOOKUP_DIAGNOSTICS").get(null) == diagnostics, "diagnostics property");
        lookupContract(diagnostics); appendMatrix();
        System.out.println("LodGroupLookupTest: " + checks + " checks passed (initial=" + initial
            + ", diagnostics=" + diagnostics + ", levels=" + LodGpu.DIAG_LEVELS + ")");
    }
}
