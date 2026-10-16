package net.minecraft.client.renderer.chunk;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** CPU-only: independent pre-cache scalar reference plus the real LodGpu append/release methods.
 * Constructor-free fake GPU owners contain Java lists/arrays only; no renderer or native methods run.
 */
public final class LodSectionItemsTest {
    private static int checks;
    private static void check(boolean value, String message) {
        ++checks;
        if (!value) throw new AssertionError(message);
    }
    private record Scalar(int[][] items, long[] quads, long[] facing, int[] section) { }

    // Original LodGpu buildItems and addSectionItem arithmetic, kept independent of the cache.
    private static Scalar scalar(int slot, int[] arenas, int[] firstQuad, int[][] clusters) {
        int[][] items = new int[3][];
        long[] quads = new long[3], facing = new long[8];
        for (int l = 0; l < 3; l++) {
            int[] input = clusters[l];
            if (input == null || arenas[l] < 0) continue;
            items[l] = new int[4 * (input.length / 3)];
            int base = 4 * firstQuad[l], count = 0;
            for (int c = 0; c < input.length; c += 3) {
                int c0 = input[c], at = 4 * count;
                int q = (c0 >>> 16 & 63) + 1;
                items[l][at] = base + 4 * (c0 & 0xFFFF);
                items[l][at + 1] = slot | (q - 1) << 20 | (c0 >>> 22 & 7) << 26 | (c0 >>> 25 & 1) << 29;
                items[l][at + 2] = input[c + 1];
                items[l][at + 3] = input[c + 2];
                count++;
                quads[l] += q;
                facing[c0 >>> 22 & 7] += q;
            }
        }
        int lx = 1023, ly = 1023, lz = 1023, hx = 0, hy = 0, hz = 0;
        boolean any = false, unusable = false;
        for (int l = 0; l < 3; l++) {
            int[] input = clusters[l];
            if (input == null || arenas[l] < 0) continue;
            for (int c = 0; c < input.length; c += 3) {
                any = true;
                unusable |= (input[c] >>> 25 & 1) != 0;
                int lo = input[c + 1], hi = input[c + 2];
                lx = Math.min(lx, lo & 1023);
                ly = Math.min(ly, lo >>> 10 & 1023);
                lz = Math.min(lz, lo >>> 20 & 1023);
                hx = Math.max(hx, hi & 1023);
                hy = Math.max(hy, hi >>> 10 & 1023);
                hz = Math.max(hz, hi >>> 20 & 1023);
            }
        }
        return new Scalar(items, quads, facing, any ? new int[]{0, slot | (unusable ? 1 << 29 : 0),
            lx | ly << 10 | lz << 20, hx | hy << 10 | hz << 20} : null);
    }

    private static void equivalent(LodSectionItems actual, Scalar expected) {
        long payload = 8L * 11;
        for (int l = 0; l < 3; l++) {
            int[] want = expected.items[l];
            check(actual.hasLayer(l) == (want != null), "null versus empty layer");
            check(actual.quadCount(l) == expected.quads[l], "layer quad sum");
            if (want == null) continue;
            check(actual.clusterCount(l) == want.length / 4, "cluster count");
            int[] copy = new int[want.length + 8];
            Arrays.fill(copy, 0x76543210);
            actual.copyLayer(l, copy, 4);
            check(Arrays.equals(want, Arrays.copyOfRange(copy, 4, 4 + want.length)), "four packed ints exact");
            check(copy[0] == 0x76543210 && copy[copy.length - 1] == 0x76543210, "append bounds");
            Arrays.fill(copy, 0);
            actual.copyLayer(l, copy, 4);
            check(Arrays.equals(want, Arrays.copyOfRange(copy, 4, 4 + want.length)), "copy cannot mutate cache");
            payload += 4L * want.length;
        }
        for (int direction = 0; direction < 8; direction++)
            check(actual.facingQuads(direction) == expected.facing[direction], "direction " + direction);
        check(actual.hasSection() == (expected.section != null), "empty section omission");
        if (expected.section != null) {
            int[] copy = new int[4]; actual.copySection(copy, 0);
            check(Arrays.equals(copy, expected.section), "section union and unusable bit exact");
            payload += 16;
        }
        check(actual.payloadBytes == payload, "all owned primitive-array bytes");
    }

    private static int[] records(int count, Random random) {
        int[] result = new int[count * 3];
        for (int c = 0; c < count; c++) {
            // Deterministically cover all 8 directions, all 64 quad counts, both unusable values.
            result[3*c] = (c == 0 ? 65535 : random.nextInt(65536)) | (c % 64) << 16
                | (c % 8) << 22 | ((c / 64) & 1) << 25 | (random.nextInt() & 0xfc000000);
            int x = random.nextInt(1024), y = random.nextInt(1024), z = random.nextInt(1024);
            result[3*c+1] = x | y << 10 | z << 20;
            result[3*c+2] = (x + random.nextInt(1024-x)) | (y + random.nextInt(1024-y)) << 10 | (z + random.nextInt(1024-z)) << 20;
        }
        return result;
    }

    private static void packing() {
        Random random = new Random(0x53454354494f4eL);
        for (int test = 0; test < 180; test++) {
            int slot = test % 2 == 0 ? 0 : (1 << 20) - 1;
            int[] arenas = {0, test % 3 == 0 ? -1 : 1, 2};
            int[] first = {0, 2396744, 5592400}; // BLOCK and compact arena offsets, in record units.
            int[][] raw = {records(test == 0 ? 5000 : 129, random), test % 2 == 0 ? null : records(64, random),
                test % 4 == 0 ? new int[0] : records(7, random)};
            Scalar expected = scalar(slot, arenas, first, raw);
            LodSectionItems actual = new LodSectionItems(slot, arenas, first, raw);
            equivalent(actual, expected);
            for (int[] layer : raw) if (layer != null) Arrays.fill(layer, -1);
            Arrays.fill(first, -1); Arrays.fill(arenas, -1);
            equivalent(actual, expected); // The cache retains no mutable input arrays.
        }
        int[][] empty = {null, new int[0], new int[]{-1, -1, -1}};
        int[] arenas = {0, 1, -1}, first = {0, 0, 0};
        equivalent(new LodSectionItems(7, arenas, first, empty), scalar(7, arenas, first, empty));
        boolean refused = false;
        try { new LodSectionItems(7, arenas, first, new int[][]{new int[2], null, null}); }
        catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "reject incomplete record");
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static Object get(Object object, String name) throws Exception { return field(object.getClass(), name).get(object); }
    private static void set(Object object, String name, Object value) throws Exception { field(object.getClass(), name).set(object, value); }
    private static Object blank(Class<?> type) throws Exception {
        Class<?> u = Class.forName("sun.misc.Unsafe");
        Object unsafe = field(u, "theUnsafe").get(null);
        return u.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
    }
    private static final class ExpectedGroup {
        final int layer, arena, level;
        final boolean fade;
        int[] items = new int[0]; long quads;
        ExpectedGroup(int layer, int arena, boolean fade, int level) {
            this.layer = layer; this.arena = arena; this.fade = fade; this.level = level;
        }
    }

    private static void integration(boolean requested) throws Exception {
        Field toggle = field(LodGpu.class, "cacheItems");
        check(Modifier.isPublic(toggle.getModifiers()) && Modifier.isStatic(toggle.getModifiers())
            && Modifier.isVolatile(toggle.getModifiers()), "public static volatile runtime toggle");
        check(LodGpu.cacheItems == requested, "property/default-off");
        LodGpu owner = (LodGpu)blank(LodGpu.class);
        set(owner, "groups", new ArrayList<>());
        List<Object> arenas = new ArrayList<>();
        Class<?> arenaType = Class.forName(LodGpu.class.getName() + "$Arena");
        for (int i = 0; i < 3; i++) {
            Object arena = blank(arenaType); set(arena, "index", i); set(arena, "compact", i == 1); arenas.add(arena);
        }
        set(owner, "arenas", arenas); set(owner, "secItems", new int[4]);
        set(owner, "drawnQuadsByLevel", new long[8]); set(owner, "drawnQuadsBySlot", new long[8]);
        Method append = LodGpu.class.getDeclaredMethod("appendCachedSection", LodGpu.GpuSection.class, boolean.class, int.class);
        Method release = LodGpu.class.getDeclaredMethod("releaseSectionItems", LodGpu.GpuSection.class);
        Method scalarBounds = LodGpu.class.getDeclaredMethod("addSectionItem", LodGpu.GpuSection.class);
        append.setAccessible(true); release.setAccessible(true); scalarBounds.setAccessible(true);
        LodGpu.GpuSection[] sections = new LodGpu.GpuSection[4];
        Scalar[] expected = new Scalar[4]; Random random = new Random(17);
        long payload = 0;
        for (int i = 0; i < sections.length; i++) {
            var s = sections[i] = new LodGpu.GpuSection(); s.slot = i == 3 ? 1048575 : i;
            for (int l = 0; l < 3; l++) {
                s.arena[l] = (i + l) % 3; s.firstQuad[l] = 12345 * (i + l);
                s.clusters[l] = i == 2 ? (l == 1 ? new int[0] : null) : records(700 + i, random);
            }
            expected[i] = scalar(s.slot, s.arena, s.firstQuad, s.clusters);
            int before = (int)get(owner, "secCount"); scalarBounds.invoke(owner, s);
            if (expected[i].section != null)
                check(Arrays.equals(expected[i].section, Arrays.copyOfRange((int[])get(owner, "secItems"), before*4, before*4+4)), "real scalar section bounds");
        }
        List<ExpectedGroup> groups = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            // A second rebuild changes fade grouping, retaining the same immutable section cache.
            set(owner, "secCount", 0); set(owner, "drawnClusters", 0); set(owner, "drawnQuads", 0L);
            Arrays.fill((long[])get(owner, "drawnQuadsByLevel"), 0); Arrays.fill((long[])get(owner, "drawnQuadsBySlot"), 0);
            for (Object group : (List<?>)get(owner, "groups")) { set(group, "count", 0); set(group, "quads", 0L); }
            for (ExpectedGroup g : groups) { g.items = new int[0]; g.quads = 0; }
            ArrayList<Integer> sectionWords = new ArrayList<>(); long total = 0; int count = 0;
            long[] levels = new long[8], facing = new long[8];
            int[] order = {3, 0, 2, 1, 0};
            for (int at = 0; at < order.length; at++) {
                int i = order[at], level = i + 1; boolean fade = (at + round) % 2 == 0;
                append.invoke(owner, sections[i], fade, level);
                Scalar e = expected[i];
                if (e.section != null) for (int word : e.section) sectionWords.add(word);
                for (int l = 0; l < 3; l++) {
                    if (e.items[l] == null) continue;
                    int arena = sections[i].arena[l], groupLevel = LodGpu.DIAG_LEVELS ? level : -1;
                    ExpectedGroup g = null;
                    for (ExpectedGroup existing : groups) if (existing.layer == l && existing.arena == arena && existing.fade == fade && existing.level == groupLevel) { g = existing; break; }
                    if (g == null) {
                        g = new ExpectedGroup(l, arena, fade, groupLevel); groups.add(g);
                        groups.sort((a,b) -> a.layer != b.layer ? Integer.compare(a.layer,b.layer) : Integer.compare(a.arena,b.arena));
                    }
                    int oldLength = g.items.length; g.items = Arrays.copyOf(g.items, oldLength + e.items[l].length);
                    System.arraycopy(e.items[l], 0, g.items, oldLength, e.items[l].length);
                    g.quads += e.quads[l]; total += e.quads[l]; levels[level] += e.quads[l]; count += e.items[l].length / 4;
                }
                for (int d = 0; d < 8; d++) facing[d] += e.facing[d];
            }
            List<?> actual = (List<?>)get(owner, "groups"); check(actual.size() == groups.size(), "empty/fade group creation");
            for (int i = 0; i < groups.size(); i++) {
                Object a = actual.get(i); ExpectedGroup g = groups.get(i);
                check((int)get(a,"layer") == g.layer && (int)get(get(a,"arena"),"index") == g.arena
                    && (boolean)get(a,"fade") == g.fade && (int)get(a,"level") == g.level, "exact group ordering and layout");
                check((int)get(a,"count") == g.items.length/4 && (long)get(a,"quads") == g.quads, "group counts");
                check(Arrays.equals(Arrays.copyOf((int[])get(a,"items"), g.items.length), g.items), "production bulk append bytes");
            }
            check((int)get(owner,"drawnClusters") == count && (long)get(owner,"drawnQuads") == total, "global cluster/quad totals");
            check(Arrays.equals((long[])get(owner,"drawnQuadsByLevel"), levels), "level quad totals");
            check(Arrays.equals((long[])get(owner,"drawnQuadsBySlot"), facing), "facing quad totals");
            check((int)get(owner,"secCount") * 4 == sectionWords.size(), "section ordering/count");
            check(Arrays.equals(Arrays.copyOf((int[])get(owner,"secItems"), sectionWords.size()), sectionWords.stream().mapToInt(Integer::intValue).toArray()), "section record bytes");
            check((long)get(owner,"itemCacheBuilds") == 4 && (long)get(owner,"itemCacheHits") == (round+1)*5-4, "lazy builds and later hits");
            payload = Arrays.stream(sections).mapToLong(s -> s.preparedItems.payloadBytes).sum();
            check((long)get(owner,"itemCachePayloadBytes") == payload, "resident cache accounting");
        }
        for (var s : sections) { release.invoke(owner, s); release.invoke(owner, s); check(s.preparedItems == null, "release clears reference idempotently"); }
        check((long)get(owner,"itemCachePayloadBytes") == 0, "all released bytes removed");
        // Reused slot, new immutable range/layout metadata: no cache keyed by slot survives.
        var replacement = new LodGpu.GpuSection(); replacement.slot = sections[0].slot;
        replacement.arena[0] = 1; replacement.firstQuad[0] = 765432; replacement.clusters[0] = records(1, random);
        append.invoke(owner, replacement, false, 7);
        equivalent(replacement.preparedItems, scalar(replacement.slot, replacement.arena, replacement.firstQuad, replacement.clusters));
        check((long)get(owner,"itemCacheBuilds") == 5, "reupload gets fresh cache");
        release.invoke(owner, replacement); check((long)get(owner,"itemCachePayloadBytes") == 0, "replacement release");
        LodGpu.cacheItems = !requested; check(LodGpu.cacheItems != requested, "runtime toggle mutable");
    }

    public static void main(String[] args) throws Exception {
        packing(); integration(Boolean.parseBoolean(args[0]));
        System.out.println("LodSectionItemsTest: " + checks + " checks passed (initial cacheItems=" + args[0] + ", levels=" + LodGpu.DIAG_LEVELS + ")");
    }
}
