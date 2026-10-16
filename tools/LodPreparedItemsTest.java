package net.minecraft.client.renderer.chunk;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.client.renderer.chunk.LodPreparedItems.Group;
import net.minecraft.client.renderer.chunk.LodPreparedItems.Key;
import net.minecraft.client.renderer.chunk.LodPreparedItems.Node;
import net.minecraft.client.renderer.chunk.LodPreparedItems.Result;

/** Independent scalar differential test. Only this source and the two pure CPU builders are loaded. */
public final class LodPreparedItemsTest {
    private static final long SEED = 0x5052455041524544L;
    private static int checks, comparisons;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static final class Section implements LodPreparedItems.Source {
        int slot, reads, readLimit = Integer.MAX_VALUE;
        int cacheReads, cacheReadLimit = Integer.MAX_VALUE;
        LodSectionItems cached;
        LodSectionItems[] cacheSequence;
        final int[] arenas, first;
        final int[][] clusters;
        Section(int slot, int[] arenas, int[] first, int[][] clusters) {
            this.slot = slot; this.arenas = arenas; this.first = first; this.clusters = clusters;
        }
        public int slot() { return slot; }
        public int arena(int layer) { return arenas[layer]; }
        public int firstQuad(int layer) { return first[layer]; }
        public int[] clusters(int layer) {
            if (++reads > readLimit) throw new AssertionError("Payload pass on rejected input");
            return clusters[layer];
        }
        public LodSectionItems preparedRecords() {
            if (++cacheReads > cacheReadLimit) throw new AssertionError("Unexpected cache lookup");
            return cacheSequence == null ? cached : cacheSequence[(cacheReads - 1) % cacheSequence.length];
        }
    }

    private static final class RefGroup {
        final Key key;
        int[] items = new int[0];
        long quads;
        RefGroup(Key key) { this.key = key; }
    }

    private record Reference(List<RefGroup> groups, int[] sections, int sectionCount,
        int clusters, long quads, long[] levels, long[] facing) { }

    // Linear lookup and sort at EACH discovery, as in LodGpu's scalar path; no builder helpers.
    private static RefGroup group(List<RefGroup> groups, int layer, int arena, boolean fade, int level) {
        for (RefGroup g : groups) {
            Key k = g.key;
            if (k.layer() == layer && k.arena() == arena && k.fade() == fade && k.level() == level) return g;
        }
        RefGroup result = new RefGroup(new Key(layer, arena, fade, level));
        groups.add(result);
        groups.sort((a, b) -> a.key.layer() != b.key.layer() ? Integer.compare(a.key.layer(), b.key.layer())
            : Integer.compare(a.key.arena(), b.key.arena()));
        return result;
    }

    private static Reference scalar(List<Node> nodes, List<Key> seeds, boolean diagnostics) {
        List<RefGroup> groups = new ArrayList<>();
        for (Key key : seeds) groups.add(new RefGroup(key));
        // Public input accepts arbitrary seed order; normalize using the same stable comparator.
        groups.sort((a, b) -> a.key.layer() != b.key.layer() ? Integer.compare(a.key.layer(), b.key.layer())
            : Integer.compare(a.key.arena(), b.key.arena()));
        ArrayList<Integer> sectionWords = new ArrayList<>();
        int sections = 0, clusters = 0;
        long quads = 0;
        long[] levels = new long[8], facing = new long[8];
        for (Node node : nodes) {
            for (LodPreparedItems.Source raw : node.sections()) {
                Section s = (Section)raw;
                sections++;
                // Separate addSectionItem traversal, copied from the original scalar arithmetic.
                int lx = 1023, ly = 1023, lz = 1023, hx = 0, hy = 0, hz = 0;
                boolean any = false, unusable = false;
                for (int l = 0; l < 3; l++) {
                    int[] input = s.clusters[l];
                    if (input == null || s.arenas[l] < 0) continue;
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
                if (any) {
                    sectionWords.add(0); sectionWords.add(s.slot | (unusable ? 1 << 29 : 0));
                    sectionWords.add(lx | ly << 10 | lz << 20); sectionWords.add(hx | hy << 10 | hz << 20);
                }
                for (int l = 0; l < 3; l++) {
                    int[] input = s.clusters[l];
                    if (input == null || s.arenas[l] < 0) continue;
                    RefGroup g = group(groups, l, s.arenas[l], node.fade(), diagnostics ? node.level() : -1);
                    int at = g.items.length, base = 4 * s.first[l];
                    g.items = Arrays.copyOf(g.items, at + 4 * (input.length / 3));
                    for (int c = 0; c < input.length; c += 3, at += 4) {
                        int c0 = input[c], q = (c0 >>> 16 & 63) + 1;
                        g.items[at] = base + 4 * (c0 & 0xFFFF);
                        g.items[at + 1] = s.slot | (q - 1) << 20 | (c0 >>> 22 & 7) << 26 | (c0 >>> 25 & 1) << 29;
                        g.items[at + 2] = input[c + 1]; g.items[at + 3] = input[c + 2];
                        g.quads += q; clusters++; quads += q;
                        levels[Math.min(7, node.level())] += q;
                        facing[c0 >>> 22 & 7] += q;
                    }
                }
            }
        }
        int[] packedSections = new int[sectionWords.size()];
        for (int i = 0; i < packedSections.length; i++) packedSections[i] = sectionWords.get(i);
        return new Reference(groups, packedSections, sections, clusters, quads, levels, facing);
    }

    private static void equivalent(Result actual, Reference expected) {
        comparisons++;
        check(actual != null, "admitted result");
        check(actual.groups.size() == expected.groups.size(), "group count including empty seeds/layers");
        for (int i = 0; i < expected.groups.size(); i++) {
            Group a = actual.groups.get(i); RefGroup e = expected.groups.get(i);
            check(a.key().equals(e.key), "stable group identity/order at " + i);
            check(Arrays.equals(a.items(), e.items), "cluster words/order at " + i);
            check(a.quads() == e.quads, "group quad sum");
        }
        check(Arrays.equals(actual.sectionItems, expected.sections), "section words/union/order");
        check(actual.sectionCount == expected.sectionCount, "drawn sections include empty sources");
        check(actual.clusterCount == expected.clusters && actual.quadCount == expected.quads, "global totals");
        check(Arrays.equals(actual.quadsByLevel, expected.levels), "level buckets");
        check(Arrays.equals(actual.quadsByFacing, expected.facing), "all facing buckets");
        check(actual.chargedBytes == 1024L + 384L * expected.groups.size()
            + 16L * (expected.clusters + expected.sections.length / 4L), "documented payload/header charge");
    }

    private static Result exercise(List<Node> nodes, List<Key> seeds, boolean diagnostics) {
        Reference expected = scalar(nodes, seeds, diagnostics);
        // Count source occurrences and raw triples, independently of cached records/statistics.
        int cachedSections = 0, cachedClusters = 0;
        for (Node node : nodes) for (LodPreparedItems.Source raw : node.sections()) {
            Section s = (Section)raw;
            if (s.cached == null) continue;
            cachedSections++;
            for (int l = 0; l < 3; l++)
                if (s.arenas[l] >= 0 && s.clusters[l] != null) cachedClusters += s.clusters[l].length / 3;
        }
        Result actual = LodPreparedItems.build(nodes, seeds, diagnostics, 8L << 20);
        equivalent(actual, expected);
        cachedCounts(actual, cachedSections, cachedClusters);
        Result exact = LodPreparedItems.build(nodes, seeds, diagnostics, actual.chargedBytes);
        equivalent(exact, expected);
        cachedCounts(exact, cachedSections, cachedClusters);
        check(LodPreparedItems.build(nodes, seeds, diagnostics, actual.chargedBytes - 1) == null,
            "one byte below exact charge");
        equivalent(actual, scalar(nodes, seeds, diagnostics)); // Builder did not mutate the input.
        return actual;
    }

    private static void cachedCounts(Result result, int sections, int clusters) {
        check(result.cachedSectionCount == sections, "cached source occurrences, including empty/absent sources");
        check(result.cachedClusterCount == clusters, "cached cluster occurrences, including duplicate sources");
    }

    private static LodSectionItems cache(Section source) {
        return new LodSectionItems(source.slot, source.arenas, source.first, source.clusters);
    }

    // 0 = scalar, 1 = all cached, 2 = alternating cached/scalar sources.
    private static void setCaches(List<Section> sources, int mode) {
        for (int i = 0; i < sources.size(); i++) {
            Section source = sources.get(i);
            source.cached = mode != 0 && i % mode == 0 ? cache(source) : null;
        }
    }

    private static int[] records(int count, Random random) {
        int[] result = new int[3 * count];
        for (int c = 0; c < count; c++) {
            // 1024 records exhaust every count x facing x unusable combination; high bits are ignored.
            result[3*c] = (c % 3 == 0 ? 65535 : c % 3 == 1 ? 0 : random.nextInt(65536))
                | (c % 64) << 16 | (c / 64 % 8) << 22 | (c / 512 % 2) << 25 | (random.nextInt() & 0xfc000000);
            int x = random.nextInt(1024), y = random.nextInt(1024), z = random.nextInt(1024);
            result[3*c+1] = x | y << 10 | z << 20 | (random.nextInt() & 0xc0000000);
            result[3*c+2] = (x + random.nextInt(1024-x)) | (y + random.nextInt(1024-y)) << 10
                | (z + random.nextInt(1024-z)) << 20 | (random.nextInt() & 0xc0000000);
        }
        if (count != 0) { result[1] = 0xc0000000; result[2] = -1; }
        return result;
    }

    private static Section section(int slot, int arena, int[] records) {
        return new Section(slot, new int[]{arena, -1, -1}, new int[3], new int[][]{records, null, null});
    }

    private static void fixtures() {
        Random random = new Random(SEED);
        Section a = new Section(0, new int[]{2, 0, 1}, new int[]{0, 2396744, 5592400},
            new int[][]{records(1024, random), records(1024, random), records(1024, random)});
        Section b = new Section((1 << 20) - 1, new int[]{2, 0, 3}, new int[]{65535, 1, 5592400},
            new int[][]{records(64, random), records(8, random), records(1, random)});
        Section empty = new Section(3, new int[]{4, 2, -1}, new int[]{0, -1, -1},
            new int[][]{new int[0], null, new int[2]});
        Section absent = new Section(4, new int[]{-1, 0, -7}, new int[]{-1, -1, -1},
            new int[][]{new int[1], null, new int[0]});
        List<Key> seeds = List.of(new Key(2, 8, false, -1), new Key(0, 2, true, 9),
            new Key(0, 2, false, 9), new Key(0, 2, false, -1), new Key(1, 2, false, 0));
        List<Node> nodes = List.of(new Node(List.of(a, b, empty, absent), false, 9),
            new Node(List.of(b, a), true, 9), new Node(List.of(a), false, 7),
            new Node(List.of(empty), false, 0), new Node(List.of(a), true, Integer.MAX_VALUE));
        for (int mode = 0; mode < 3; mode++) {
            setCaches(List.of(a, b, empty, absent), mode);
            for (boolean diagnostics : new boolean[]{false, true}) {
                Result result = exercise(nodes, seeds, diagnostics);
                for (Key seed : seeds)
                    check(result.groups.stream().anyMatch(g -> g.key() == seed), "seed key identity retained");
                if (diagnostics) {
                    List<Key> ties = result.groups.stream().map(Group::key)
                        .filter(k -> k.layer() == 0 && k.arena() == 2).toList();
                    check(ties.equals(List.of(seeds.get(1), seeds.get(2), seeds.get(3),
                        new Key(0, 2, false, 7), new Key(0, 2, true, Integer.MAX_VALUE))), "explicit creation ties");
                    check(result.quadsByLevel[7] == result.quadCount, "high levels clamp stats only");
                }
                exercise(List.of(), seeds, diagnostics);
                Result onlyEmpty = exercise(List.of(new Node(List.of(empty, absent), false, 0)), List.of(), diagnostics);
                check(onlyEmpty.sectionCount == 2 && onlyEmpty.sectionItems.length == 0
                    && onlyEmpty.groups.size() == 1, "empty versus absent");
                exercise(List.of(), List.of(), diagnostics);
            }
        }
        // All-zero bounds and usable-only records must not inherit a union or unusable bit.
        List<Section> bounds = List.of(section(17, 0, new int[]{0, 0, 0}),
            section(18, 1, new int[]{63 << 16 | 7 << 22, -1, -1}));
        for (int mode = 0; mode < 3; mode++) {
            setCaches(bounds, mode);
            exercise(List.of(new Node(bounds, false, 0)), List.of(), true);
        }
    }

    private static void randomized() {
        Random random = new Random(SEED);
        for (int test = 0; test < 160; test++) {
            List<Section> pool = new ArrayList<>();
            for (int i = 0; i < 1 + random.nextInt(10); i++) {
                int[] arenas = new int[3], first = new int[3]; int[][] clusters = new int[3][];
                for (int l = 0; l < 3; l++) {
                    arenas[l] = random.nextInt(6) - 1;
                    first[l] = random.nextInt(5592401);
                    clusters[l] = random.nextInt(4) == 0 ? null : records(random.nextInt(65), random);
                    if (clusters[l] != null) {
                        // Randomize all c0 fields independently, in addition to exhaustive fixtures.
                        for (int c = 0; c < clusters[l].length; c += 3) clusters[l][c] = random.nextInt();
                    }
                }
                pool.add(new Section(random.nextInt(1 << 20), arenas, first, clusters));
            }
            List<Node> nodes = new ArrayList<>();
            for (int i = 0; i < random.nextInt(12); i++) {
                List<Section> sections = new ArrayList<>();
                for (int j = 0; j < random.nextInt(8); j++) sections.add(pool.get(random.nextInt(pool.size())));
                nodes.add(new Node(sections, random.nextBoolean(), random.nextInt(12)));
            }
            List<Key> seeds = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                Key key = new Key(random.nextInt(3), random.nextInt(7), random.nextBoolean(), random.nextInt(13) - 1);
                if (!seeds.contains(key)) seeds.add(key);
            }
            exercise(nodes, seeds, false);
            Result diagnosed = exercise(nodes, seeds, true);
            // Rebuild with the last result's order, as the live renderer will do.
            Collections.reverse(nodes);
            exercise(nodes, diagnosed.groups.stream().map(Group::key).toList(), true);
            setCaches(pool, test % 2 == 0 ? 1 : 2);
            exercise(nodes, seeds, false);
            exercise(nodes, diagnosed.groups.stream().map(Group::key).toList(), true);
        }
    }

    private static void capacityAndValidation() {
        Section guarded = section(0, 0, new int[3]); guarded.readLimit = 0;
        guarded.cached = cache(guarded); guarded.cacheReadLimit = 0;
        List<Node> nodes = List.of(new Node(List.of(guarded), false, 0));
        for (long cap : new long[]{Long.MIN_VALUE, -1, 0, 1023})
            check(LodPreparedItems.build(nodes, List.of(), false, cap) == null, "fixed allowance preflight");
        check(LodPreparedItems.build(nodes, List.of(new Key(0, 0, false, -1)), false, 1407) == null,
            "seed allowance preflight");
        guarded.readLimit = 3;
        check(LodPreparedItems.build(nodes, List.of(), false, 1439) == null, "no payload pass below exact charge");
        check(guarded.reads == 3, "length-only pass finished before final section charge rejection");
        guarded.reads = 0; guarded.readLimit = 6;
        check(LodPreparedItems.build(List.of(new Node(List.of(guarded, guarded), false, 0)),
            List.of(), false, 1471) == null, "later source rejects entire request before cache lookup");
        check(guarded.reads == 6, "all raw metadata admitted before any cached payload");

        // Reuse a 768 KiB array: a missing preflight would attempt multi-GiB output in a 128 MiB JVM.
        Section large = section(0, 0, new int[3 * 65536]);
        large.cached = cache(large); large.cacheReadLimit = 0;
        check(LodPreparedItems.build(List.of(new Node(Collections.nCopies(8192, large), false, 0)),
            List.of(), false, Long.MAX_VALUE) == null, "per-group int word overflow");
        List<Node> many = new ArrayList<>();
        for (int level = 0; level < 5; level++)
            many.add(new Node(Collections.nCopies(8191, large), false, level));
        check(LodPreparedItems.build(many, List.of(), true, Long.MAX_VALUE) == null, "global cluster count overflow");
        List<Section> huge = Collections.nCopies(1 << 30, guarded);
        check(LodPreparedItems.build(List.of(new Node(huge, false, 0), new Node(huge, true, 0)),
            List.of(), false, Long.MAX_VALUE) == null, "source count overflow before reading sources");

        invalid(List.of(new Node(List.of(section(-1, 0, new int[0])), false, 0)), List.of());
        invalid(List.of(new Node(List.of(section(1 << 20, 0, new int[0])), false, 0)), List.of());
        invalid(List.of(new Node(List.of(section(0, 0, new int[2])), false, 0)), List.of());
        Section negativeFirst = section(0, 0, new int[3]); negativeFirst.first[0] = -1;
        invalid(List.of(new Node(List.of(negativeFirst), false, 0)), List.of());
        invalid(List.of(new Node(List.of(), false, -1)), List.of());
        for (Key key : List.of(new Key(-1, 0, false, 0), new Key(3, 0, false, 0),
            new Key(0, -1, false, 0), new Key(0, 0, false, -2))) invalid(List.of(), List.of(key));
        Key duplicate = new Key(0, 0, false, -1);
        invalid(List.of(), List.of(duplicate, duplicate));

        // Deliberately corrupt raw metadata after making a valid cache: admission must still reject
        // it without consulting the cache. This is an invalid-input fixture, not a supported source.
        for (int bad = 0; bad < 4; bad++) {
            Section corrupt = section(0, 0, new int[3]);
            corrupt.cached = cache(corrupt); corrupt.cacheReadLimit = 0;
            if (bad == 0) corrupt.slot = -1;
            if (bad == 1) corrupt.slot = 1 << 20;
            if (bad == 2) corrupt.clusters[0] = new int[2];
            if (bad == 3) corrupt.first[0] = -1;
            invalid(List.of(new Node(List.of(corrupt), false, 0)), List.of());
        }
    }

    private static void changingCache() {
        Section source = new Section((1 << 20) - 1, new int[]{2, 0, 1}, new int[]{65535, 0, 5592400},
            new int[][]{records(1024, new Random(SEED)), new int[0], new int[]{0, 0, 0}});
        LodSectionItems first = cache(source), second = cache(source);
        List<Node> nodes = List.of(new Node(List.of(source, source), false, 0),
            new Node(List.of(source, source), true, 9), new Node(List.of(source, source), false, 7));
        // Both non-null objects describe the exact same immutable source; only availability changes.
        for (LodSectionItems[] sequence : new LodSectionItems[][]{
            {first, null, second, null, first, second}, {null, first, null, second, first, second}}) {
            source.cacheSequence = sequence;
            for (boolean diagnostics : new boolean[]{false, true}) {
                source.cacheReads = 0; source.cacheReadLimit = 6;
                source.reads = 0; source.readLimit = 3 * 6 + 3 * 2; // Admission plus two scalar appends.
                long hitsBefore = LodPreparedItems.admissionHitSections;
                Result result = LodPreparedItems.build(nodes, List.of(), diagnostics, 8L << 20);
                equivalent(result, scalar(nodes, List.of(), diagnostics));
                cachedCounts(result, 4, 4 * 1025);
                check(source.cacheReads == 6, "one cache lookup per append, even for duplicate sources");
                check(source.reads == source.readLimit - 3 * (LodPreparedItems.admissionHitSections - hitsBefore),
                    "cached appends use cached payload after raw or memoized admission");
            }
        }
    }

    private static void invalid(List<Node> nodes, List<Key> seeds) {
        boolean rejected = false;
        try { LodPreparedItems.build(nodes, seeds, true, 1L << 20); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "invalid metadata rejected");
    }

    private static void detached(Object object, Set<Object> forbidden, Set<Object> visited) throws Exception {
        if (object == null || object instanceof Number || object instanceof Boolean) return;
        check(!forbidden.contains(object), "no input object/array retained");
        if (!visited.add(object) || object instanceof int[] || object instanceof long[]) return;
        if (object instanceof Iterable<?> values) {
            for (Object value : values) detached(value, forbidden, visited);
        } else {
            check(object instanceof Result || object instanceof Group || object instanceof Key,
                "result graph contains only result/group/key/owned array/list objects");
            for (Field field : object.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true); detached(field.get(object), forbidden, visited);
            }
        }
    }

    private static void forbidCache(Object object, Set<Object> forbidden) throws Exception {
        if (object == null || !forbidden.add(object)) return;
        if (object instanceof Object[] array) {
            for (Object entry : array) forbidCache(entry, forbidden);
        } else if (object instanceof LodSectionItems) {
            for (Field field : object.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                field.setAccessible(true); forbidCache(field.get(object), forbidden);
            }
        }
    }

    private static void ownership(boolean cached) throws Exception {
        Section source = new Section(73, new int[]{1, 2, 3}, new int[]{5, 6, 7},
            new int[][]{records(128, new Random(SEED)), new int[0], new int[]{0, 0, 0}});
        if (cached) source.cached = cache(source);
        List<Section> sections = new ArrayList<>(List.of(source));
        Node node = new Node(sections, true, 10);
        List<Node> nodes = new ArrayList<>(List.of(node));
        List<Key> seeds = new ArrayList<>(List.of(new Key(2, 9, false, -1)));
        Reference expected = scalar(nodes, seeds, true);
        Result result = exercise(nodes, seeds, true);
        Set<Object> forbidden = Collections.newSetFromMap(new IdentityHashMap<>());
        Collections.addAll(forbidden, source, source.arenas, source.first, source.clusters, sections, node, nodes, seeds);
        for (int[] input : source.clusters) forbidden.add(input);
        forbidCache(source.cached, forbidden);
        detached(result, forbidden, Collections.newSetFromMap(new IdentityHashMap<>()));
        Result disposable = LodPreparedItems.build(nodes, seeds, true, result.chargedBytes);
        equivalent(disposable, expected);
        for (Group group : disposable.groups) Arrays.fill(group.items(), -1);
        Arrays.fill(disposable.sectionItems, -1);
        Arrays.fill(disposable.quadsByLevel, -1); Arrays.fill(disposable.quadsByFacing, -1);
        equivalent(result, expected);
        equivalent(LodPreparedItems.build(nodes, seeds, true, result.chargedBytes), expected);
        source.cached = null;
        source.slot = -1; Arrays.fill(source.arenas, -1); Arrays.fill(source.first, -1);
        for (int[] input : source.clusters) Arrays.fill(input, -1);
        Arrays.fill(source.clusters, null); sections.clear(); nodes.clear(); seeds.clear();
        equivalent(result, expected);
        boolean immutable = false;
        try { result.groups.clear(); } catch (UnsupportedOperationException expectedException) { immutable = true; }
        check(immutable, "published group list is immutable");
    }

    private static void admissionChanges() {
        if (!LodPreparedItems.admissionCache) return;
        Section source = new Section(73, new int[]{2, 1, 0}, new int[]{5, 6, 7},
            new int[][]{records(128, new Random(SEED)), new int[0], new int[]{0, 0, 0}});
        source.cached = cache(source);
        List<Section> sections = List.of(source, source);
        exercise(List.of(new Node(sections, false, 0)), List.of(), false);
        for (boolean fade : new boolean[]{true, false}) for (boolean diagnostics : new boolean[]{true, false}) {
            long before = LodPreparedItems.admissionHitNodes;
            var seeds = List.of(new Key(2, 0, !fade, diagnostics ? 5 : -1), new Key(0, 2, fade, diagnostics ? 7 : -1));
            exercise(List.of(new Node(sections, fade, 7), new Node(sections, !fade, 5)), seeds, diagnostics);
            check(LodPreparedItems.admissionHitNodes > before, "hit retains new fade, level and seed ordering");
        }
    }

    private static void collidingGroups() {
        Random random = new Random(SEED);
        List<Section> sections = new ArrayList<>();
        for (int arena : new int[]{0, 64, 1, 65, Integer.MAX_VALUE}) {
            Section s = new Section(sections.size(), new int[]{arena, arena, arena}, new int[]{0, 1, 2},
                new int[][]{records(3, random), new int[0], records(2, random)});
            s.cached = cache(s);
            sections.add(s);
        }
        List<Node> nodes = List.of(new Node(sections, false, 0), new Node(sections, true, 0),
            new Node(sections, true, 5), new Node(sections, false, 5), new Node(sections, false, 0));
        List<Key> seeds = List.of(new Key(2, 64, true, 5), new Key(0, 0, false, 0), new Key(1, 1, true, 0));
        for (boolean diagnostics : new boolean[]{false, true}) exercise(nodes, seeds, diagnostics);
    }

    public static void main(String[] args) throws Exception {
        LodPreparedItems.admissionCache = Boolean.getBoolean("test.admissionCache");
        LodPreparedItems.directGroupLookup = Boolean.getBoolean("test.directGroupLookup");
        check(Runtime.version().feature() == 25, "Java 25");
        check(Runtime.getRuntime().availableProcessors() == 2, "ActiveProcessorCount=2");
        check(Runtime.getRuntime().maxMemory() <= 128L << 20, "Xmx128m");
        fixtures(); randomized(); capacityAndValidation(); changingCache(); ownership(false); ownership(true);
        admissionChanges(); collidingGroups();
        if (LodPreparedItems.directGroupLookup) {
            check(LodPreparedItems.directGroupBuilds > 0 && LodPreparedItems.directGroupHits > 0,
                "direct group lookup actually used");
        }
        if (LodPreparedItems.admissionCache) {
            check(LodPreparedItems.admissionHitNodes > 0, "admission summaries actually reused");
        }
        System.out.println("LodPreparedItemsTest: " + checks + " checks passed; " + comparisons
            + " scalar comparisons; seed=0x" + Long.toHexString(SEED));
        System.out.println("Java 25; ActiveProcessorCount=2; Xmx128m; CPU only; no client jar");
    }
}
