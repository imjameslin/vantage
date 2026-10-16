package net.minecraft.client.renderer.chunk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/** Run with tools/lod-mesh-cache-test.sh. No GPU, no game, isolated disposable cache files. */
public final class LodMeshCacheTest {
    private static int checks;
    private static final List<String> DRAW_METADATA_RUNTIME = List.of(
        "mc.lod.cacheItems", "mc.lod.cacheGroups", "mc.lod.cacheGroups.diagnostics", "mc.lod.itemBuildProfile", "mc.lod.prepareProfile",
        "mc.lod.cacheNodeItems", "mc.lod.cacheNodeItemsMB", "mc.lod.atomicMeshHandoff", "mc.lod.asyncItems", "mc.lod.nearHandoff");

    private static void check(String message, boolean result) {
        checks++;
        if (!result) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path root = Files.createTempDirectory(Path.of(args[0]), "mesh-cache-");
        try {
            roundTrip(root);
            keysAndSnapshots();
            resources();
            codecOrdering();
            codeIdentity(root);
            runtimeProperties();
            runtimeFlagReopen(root);
            corruption(root);
            failedWrite(root);
            budgetReplacement(root);
            System.out.println("LodMeshCacheTest: " + checks + " checks, 0 failures");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }

    private static byte[] key(int tag) {
        byte[] key = new byte[20];
        Arrays.fill(key, (byte)tag);
        return key;
    }

    private static List<LodMesher.Section> mesh() {
        LodMesher.Section first = new LodMesher.Section(-128, -64, 256, 3);
        for (int layer = 0; layer < 3; layer++) {
            first.quads[layer] = layer + 1;
            first.compact[layer] = layer != 1;
            int bytes = first.quads[layer] * (first.compact[layer] ? LodCompact.RECORD_BYTES : 112);
            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes + 16).order(ByteOrder.nativeOrder());
            for (int i = 0; i < bytes + 16; i++) buffer.put((byte)(i * 17 + layer));
            buffer.position(7).limit(bytes + 7);
            first.vertices[layer] = buffer;
            first.clusters[layer] = layer == 2 ? null : new int[]{layer << 16 | layer << 22, 0x12345678, 0x23456789};
        }
        return List.of(first, new LodMesher.Section(-128, 64, 256, 3));
    }

    private static void roundTrip(Path root) throws Exception {
        Path base = root.resolve("round-trip");
        LodMeshCache cache = LodMeshCache.open(base, "round");
        List<LodMesher.Section> original = mesh();
        cache.put(3, -1, -9, key(1), -64, 128, original);
        check("encode leaves original buffer position/limit", original.getFirst().vertices[0].position() == 7
            && original.getFirst().vertices[0].remaining() == 48);
        check("memory round trip", LodMeshCache.sameMesh(cache.get(3, -1, -9, key(1)), -64, 128, original));
        cache.flush();
        cache = LodMeshCache.open(base, "round");
        var saved = cache.get(3, -1, -9, key(1));
        check("disk round trip: vertices, clusters, format, extents", LodMeshCache.sameMesh(saved, -64, 128, original));
        check("decoded buffers have native byte order", saved.sections().getFirst().vertices[0].order() == ByteOrder.nativeOrder());
        check("input change misses", cache.get(3, -1, -9, key(2)) == null);
        check("different coordinate misses", cache.get(3, -2, -9, key(1)) == null);
        check("different level misses", cache.get(2, -1, -9, key(1)) == null);
        cache.verify(saved, -64, 128, original);
        check("verify matches bytes", cache.verified == 1 && cache.verifyFailures == 0);
        saved.sections().getFirst().vertices[0].put(0, (byte)0);
        check("corrupted vertex detected", !LodMeshCache.sameMesh(saved, -64, 128, original));
        check("consumer mutation doesn't corrupt stored cache", LodMeshCache.sameMesh(cache.get(3, -1, -9, key(1)), -64, 128, original));
        var badCluster = cache.get(3, -1, -9, key(1));
        badCluster.sections().getFirst().clusters[0][1] ^= 1;
        check("corrupted bounds detected", !LodMeshCache.sameMesh(badCluster, -64, 128, original));
        check("changed extents detected", !LodMeshCache.sameMesh(cache.get(3, -1, -9, key(1)), -63, 128, original));
        cache.put(0, 0, 0, key(4), 0, 0, List.of());
        cache.flush();
        check("empty node round trip", LodMeshCache.open(base, "round").get(0, 0, 0, key(4)).sections().isEmpty());
    }

    private static void keysAndSnapshots() throws Exception {
        int[] states = new int[1024];
        Arrays.fill(states, LodData.FILLER_ID);
        LodData a = new LodData(0, 0, 0, 0, 4, states, new byte[1024], new String[]{"minecraft:plains"}, new short[16]);
        byte[][] live = {a.encode()};
        AtomicInteger reads = new AtomicInteger();
        var snapshot = LodMeshCache.capture(0, 0, 0, false, false, (x, z) -> {
            reads.incrementAndGet();
            return x == 0 && z == 0 ? live[0] : null;
        });
        states[0] = LodData.AIR_ID;
        live[0] = a.encode(); // update after capture: saved key MUST still describe the original decoded geometry
        check("snapshot decodes captured version after live update", snapshot.decode()[4].states[0] == LodData.FILLER_ID);
        check("snapshot reads inputs only once", reads.get() == 9);
        var changed = LodMeshCache.capture(0, 0, 0, false, false, (x, z) -> x == 0 && z == 0 ? live[0] : null);
        check("updated data gets a different key", !Arrays.equals(snapshot.key(), changed.key()));
        byte[][] blobs = snapshot.blobs();
        for (int i = 0; i < 9; i++) {
            byte[][] copy = blobs.clone();
            copy[i] = new byte[]{(byte)i};
            check("every neighbour participates " + i, !Arrays.equals(snapshot.key(), LodMeshCache.key(0, 0, 0, false, false, copy)));
        }
        check("flat flag participates", !Arrays.equals(snapshot.key(), LodMeshCache.key(0, 0, 0, true, false, blobs)));
        check("strip flag participates", !Arrays.equals(snapshot.key(), LodMeshCache.key(0, 0, 0, false, true, blobs)));
        check("coordinates participate", !Arrays.equals(snapshot.key(), LodMeshCache.key(0, 1, 0, false, false, blobs)));
        check("level participates", !Arrays.equals(snapshot.key(), LodMeshCache.key(1, 0, 0, false, false, blobs)));
    }

    private static Resource resource(String content) {
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new Resource(null, () -> new ByteArrayInputStream(bytes)) {
            @Override public String sourcePackId() { return "same-pack-name"; }
        };
    }

    private record Resources(Map<Identifier, List<Resource>> entries) implements ResourceManager {
        public Set<String> getNamespaces() { return Set.of("minecraft"); }
        public Stream<PackResources> listPacks() { return Stream.empty(); }
        public Optional<Resource> getResource(Identifier id) { return Optional.ofNullable(entries.get(id)).map(List::getLast); }
        public List<Resource> getResourceStack(Identifier id) { return entries.getOrDefault(id, List.of()); }
        public Map<Identifier, List<Resource>> listResourceStacks(String directory, Selector selector) {
            Map<Identifier, List<Resource>> out = new LinkedHashMap<>();
            entries.forEach((id, stack) -> { if (id.getPath().startsWith(directory + "/") && !id.getPath().endsWith(".mcmeta")
                && selector.isIncluded(id)) out.put(id, stack); });
            return out;
        }
        public Map<Identifier, Resource> listResources(String directory, Selector selector) {
            Map<Identifier, Resource> out = new LinkedHashMap<>();
            listResourceStacks(directory, selector).forEach((id, stack) -> out.put(id, stack.getLast()));
            return out;
        }
    }

    private static void resources() throws Exception {
        Identifier model = Identifier.withDefaultNamespace("models/block/tree.json");
        Identifier atlas = Identifier.withDefaultNamespace("atlases/blocks.json");
        Map<Identifier, List<Resource>> data = new LinkedHashMap<>();
        data.put(model, List.of(resource("trunk")));
        data.put(atlas, List.of(resource("base-atlas"), resource("custom-atlas")));
        String before = LodMeshCacheContext.resourcesKey(new Resources(data));
        data.put(model, List.of(resource("leaves")));
        check("changed contents under same pack name invalidates", !before.equals(LodMeshCacheContext.resourcesKey(new Resources(data))));
        data.put(model, List.of(resource("trunk")));
        data.put(atlas, List.of(resource("changed-base-atlas"), resource("custom-atlas")));
        check("lower atlas stack layer participates", !before.equals(LodMeshCacheContext.resourcesKey(new Resources(data))));
        data.put(atlas, List.of(resource("base-atlas"), resource("custom-atlas")));
        Map<Identifier, List<Resource>> reordered = new LinkedHashMap<>();
        reordered.put(atlas, data.get(atlas)); reordered.put(model, data.get(model));
        check("enumeration order doesn't invalidate", before.equals(LodMeshCacheContext.resourcesKey(new Resources(reordered))));
        data.put(Identifier.withDefaultNamespace("models/block/tree.json.mcmeta"), List.of(resource("changed-metadata")));
        check("metadata hidden by resource enumeration participates", !before.equals(LodMeshCacheContext.resourcesKey(new Resources(data))));
        String fp = fingerprint(before);
        check("resource context participates", !fp.equals(fingerprint("changed-context")));
        System.setProperty("mc.lod.radius", "512");
        check("runtime radius doesn't invalidate", fp.equals(fingerprint(before)));
        System.clearProperty("mc.lod.radius");
        System.setProperty("mc.lod.compactWorkBuffers", "true");
        check("temporary work-buffer capacity reuses canonical meshes", fp.equals(fingerprint(before)));
        System.clearProperty("mc.lod.compactWorkBuffers");
        for (String value : List.of("false", "true")) {
            System.setProperty("mc.lod.reuse", value);
            check("exact image reuse does not change CPU meshes " + value, fp.equals(fingerprint(before)));
        }
        System.clearProperty("mc.lod.reuse");
        System.setProperty("mc.lod.meshCacheVerify", "true");
        check("verification mode reuses production cache", fp.equals(fingerprint(before)));
        System.clearProperty("mc.lod.meshCacheVerify");
        System.setProperty("mc.lod.mergeTol", "9");
        check("mesher setting invalidates", !fp.equals(fingerprint(before)));
        System.clearProperty("mc.lod.mergeTol");
        System.setProperty("mc.terrain.faceBuckets", "false");
        check("terrain mesh setting invalidates", !fp.equals(fingerprint(before)));
        System.clearProperty("mc.terrain.faceBuckets");
        check("AO invalidates", !fp.equals(LodMeshCache.fingerprint(2, false, true, 1024, 1024, before)));
        check("atlas size invalidates", !fp.equals(LodMeshCache.fingerprint(2, true, true, 2048, 1024, before)));
        check("biome blend invalidates", !fp.equals(LodMeshCache.fingerprint(3, true, true, 1024, 1024, before)));
    }

    private static String fingerprint(String context) {
        return LodMeshCache.fingerprint(2, true, true, 1024, 1024, context);
    }

    /** Exercise the real archive digest, including entry membership, names, bytes and ordering. The small payloads
     * need not be loadable classes: production hashes their bytes without loading or initializing any renderer. */
    private static void codeIdentity(Path root) throws Exception {
        String chunk = "net/minecraft/client/renderer/chunk/";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        List<String> retained = new java.util.ArrayList<>(List.of(
            chunk + "LodMesher.class", chunk + "LodMesher$Section.class", chunk + "LodManager.class",
            chunk + "LodData.class", chunk + "LodRegion.class", chunk + "LodGreedy.class", chunk + "LodCompact.class",
            chunk + "LodTrees.class", chunk + "SectionCompiler.class", chunk + "TerrainMeshInfo.class",
            chunk + "TerrainClusters.class", chunk + "LodMeshCache.class", chunk + "LodMeshCacheContext.class",
            chunk + "Lod.class", chunk + "LodManager$Node.class", chunk + "LodMeshHandoff.class",
            "com/mojang/blaze3d/vertex/BufferBuilder.class",
            "net/minecraft/client/renderer/block/ModelBlockRenderer.class",
            "net/minecraft/client/color/block/BlockColors.class",
            "net/minecraft/client/gui/components/ChatComponent.class"));
        List<String> excluded = new java.util.ArrayList<>();
        // Independent audit list, not reflected from production: unknown families must invalidate.
        for (String family : List.of("LodAsyncProducerProbe", "LodFarConsumerProbe", "LodGpu", "LodGpuBufferCapacity",
            "LodSectionItems", "LodNodeItems", "LodPreparedItems", "LodItemWorker", "LodGeometryLeases", "LodNearCoverage")) {
            for (String suffix : List.of(".class", "$Worker.class", "$Scene.class", "$1.class", "$Scene$Nested.class")) {
                excluded.add(chunk + family + suffix);
            }
            // Similar names, other packages and a slash after '$' are NOT members of the excluded family.
            retained.addAll(List.of(chunk + family + "Extra.class", chunk + family + "Scene.class",
                chunk + family + "/Child.class", chunk + family + "$Nested/Child.class",
                chunk + "extra/" + family + ".class", "other/" + family + "$Worker.class"));
        }
        for (String name : retained) entries.put(name, new byte[]{1, 2, 3});
        for (String name : excluded) entries.put(name, new byte[]{4, 5, 6});
        entries.put("native/libmcmetal.dylib", new byte[]{7});
        entries.put("shaders/terrain.msl", new byte[]{8});
        Path jar = root.resolve("code-fixture.jar");
        String before = archiveKey(jar, entries, 1_600_000_000_000L);
        for (String name : excluded) {
            Map<String, byte[]> changed = new LinkedHashMap<>(entries);
            changed.put(name, new byte[]{99});
            check("coordinator byte change ignored: " + name, before.equals(archiveKey(jar, changed, 1_600_000_000_000L)));
            changed.remove(name);
            check("coordinator removal/addition ignored: " + name, before.equals(archiveKey(jar, changed, 1_600_000_000_000L)));
        }
        for (String name : retained) {
            Map<String, byte[]> changed = new LinkedHashMap<>(entries);
            changed.put(name, new byte[]{99});
            check("retained class bytes invalidate: " + name, !before.equals(archiveKey(jar, changed, 1_600_000_000_000L)));
            changed.remove(name);
            check("retained class removal/addition invalidates: " + name, !before.equals(archiveKey(jar, changed, 1_600_000_000_000L)));
        }
        Map<String, byte[]> renamed = new LinkedHashMap<>(entries);
        renamed.put(chunk + "NewMeshHelper.class", renamed.remove(chunk + "LodMesher.class"));
        check("retained class names participate even with identical bytes", !before.equals(archiveKey(jar, renamed, 1_600_000_000_000L)));
        Map<String, byte[]> reordered = new LinkedHashMap<>();
        entries.keySet().stream().sorted(java.util.Comparator.reverseOrder()).forEach(n -> reordered.put(n, entries.get(n)));
        check("zip order and timestamps ignored", before.equals(archiveKey(jar, reordered, 1_700_000_000_000L)));
        reordered.put("native/libmcmetal.dylib", new byte[]{99});
        reordered.put("shaders/terrain.msl", new byte[]{99});
        check("native and shader payloads still ignored by code digest", before.equals(archiveKey(jar, reordered, 1_700_000_000_000L)));
    }

    private static String archiveKey(Path jar, Map<String, byte[]> entries, long timestamp) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var entry : entries.entrySet()) {
                ZipEntry item = new ZipEntry(entry.getKey());
                item.setTime(timestamp);
                zip.putNextEntry(item);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return LodMeshCacheContext.codeKey(jar);
    }

    private static void runtimeProperties() {
        // Explicit independent list: adding a new production exemption needs an audited test decision too.
        List<String> runtime = new java.util.ArrayList<>(List.of("mc.lod.async", "mc.lod.async.active", "mc.lod.async.holdExact",
            "mc.lod.async.producerProbe", "mc.lod.async.producerProbe.guard", "mc.lod.async.producerProbe.refreshes",
            "mc.lod.async.consumerProbe", "mc.lod.async.shade", "mc.lod.async.shadeLayers",
            "mc.lod.async.shadeDiagnostics", "mc.lod.async.shadeAtlasFamily", "mc.lod.materialInputs"));
        runtime.addAll(DRAW_METADATA_RUNTIME);
        java.util.Properties original = (java.util.Properties)System.getProperties().clone();
        try {
            // Each exemption must match both absence and changed values, not only one property's default.
            runtime.forEach(System::clearProperty);
            String before = fingerprint("fixed-resource-biome-seed-tag-context");
            for (String name : runtime) {
                for (String value : List.of("false", "true", "17")) {
                    System.setProperty(name, value);
                    check("audited runtime property ignored: " + name + "=" + value,
                        before.equals(fingerprint("fixed-resource-biome-seed-tag-context")));
                }
                System.clearProperty(name);
            }
            runtime.forEach(name -> System.setProperty(name, "true"));
            check("all audited runtime properties together ignored", before.equals(fingerprint("fixed-resource-biome-seed-tag-context")));
            for (String name : List.of("mc.lod.async.futureMeshMode", "mc.lod.async.shade.future", "mc.lod.async.shadeExtra",
                "mc.lod.async.producerProbe.refreshes.future", "mc.lod.asyncExtra", "mc.lod.materialInputs.future",
                "mc.lod.materialInputsExtra", "mc.lod.cacheItems.future", "mc.lod.cacheItemsExtra",
                "mc.lod.cacheGroups.future", "mc.lod.cacheGroupsExtra", "mc.lod.cacheGroups.diagnostics.future",
                "mc.lod.cacheGroups.diagnosticsExtra", "mc.lod.itemBuildProfile.future", "mc.lod.itemBuildProfileExtra",
                "mc.lod.prepareProfile.future", "mc.lod.prepareProfileExtra",
                "mc.lod.cacheNodeItems.future", "mc.lod.cacheNodeItemsExtra", "mc.lod.cacheNodeItemsMB.future", "mc.lod.cacheNodeItemsMBExtra",
                "mc.lod.nearHandoff.future", "mc.lod.nearHandoffExtra", "mc.lod.atomicMeshHandoff.future", "mc.lod.atomicMeshHandoffExtra", "mc.lod.asyncItems.future", "mc.lod.asyncItemsExtra",
                "mc.lod.futureMeshMode", "mc.lod.mergeTol", "mc.terrain.faceBuckets")) {
                System.clearProperty(name);
                String absent = fingerprint("fixed-resource-biome-seed-tag-context");
                System.setProperty(name, "first");
                String first = fingerprint("fixed-resource-biome-seed-tag-context");
                check("unknown/mesh property presence invalidates: " + name, !absent.equals(first));
                System.setProperty(name, "second");
                check("unknown/mesh property value invalidates: " + name, !first.equals(fingerprint("fixed-resource-biome-seed-tag-context")));
                System.clearProperty(name);
            }
        } finally {
            System.setProperties(original);
        }
    }

    private static void runtimeFlagReopen(Path root) throws Exception {
        java.util.Properties original = (java.util.Properties)System.getProperties().clone();
        try {
            DRAW_METADATA_RUNTIME.forEach(System::clearProperty);
            String context = "fixed-resource-biome-seed-tag-context", absent = fingerprint(context);
            Path base = root.resolve("runtime-flags");
            List<LodMesher.Section> mesh = mesh();
            LodMeshCache cache = LodMeshCache.open(base, absent);
            cache.put(3, -1, -9, key(7), -64, 128, mesh);
            cache.flush(); // No close API: reopen with a fresh owner and no shared in-memory regions.
            Path region = base.resolve(absent).resolve("L3/m.-1.-2.bin");
            byte[] savedBytes = Files.readAllBytes(region);
            // Visit every Boolean combination, then return to all properties absent.
            int combinations = 1 << DRAW_METADATA_RUNTIME.size();
            for (int mask = 0; mask <= combinations; mask++) {
                for (int bit = 0; bit < DRAW_METADATA_RUNTIME.size(); bit++) {
                    String name = DRAW_METADATA_RUNTIME.get(bit);
                    if (mask == combinations) System.clearProperty(name);
                    else System.setProperty(name, Boolean.toString((mask & (1 << bit)) != 0));
                }
                String mode = mask == combinations ? "absent again" : "mask=" + mask;
                String next = fingerprint(context);
                check("draw metadata flags keep namespace: " + mode, absent.equals(next));
                cache = LodMeshCache.open(base, next);
                var hit = cache.get(3, -1, -9, key(7));
                check("flag flip/reopen retains exact disk mesh: " + mode,
                    hit != null && LodMeshCache.sameMesh(hit, -64, 128, mesh));
                check("reopen hits without rewrite: " + mode, cache.hits == 1 && cache.misses == 0 && cache.stores == 0);
                check("original region survives unchanged: " + mode,
                    Files.isRegularFile(region) && Arrays.equals(savedBytes, Files.readAllBytes(region)));
            }
        } finally {
            System.setProperties(original);
        }
    }

    private static void codecOrdering() {
        var a = com.google.gson.JsonParser.parseString("{\"effects\":{\"water\":3,\"grass\":4},\"attributes\":{\"b\":2,\"a\":1},\"list\":[{\"y\":6,\"x\":5},7]}");
        var b = com.google.gson.JsonParser.parseString("{\"list\":[{\"x\":5,\"y\":6},7],\"attributes\":{\"a\":1,\"b\":2},\"effects\":{\"grass\":4,\"water\":3}}");
        String canonical = LodMeshCacheContext.canonicalJson(a).toString();
        check("codec map order is irrelevant at every depth", canonical.equals(LodMeshCacheContext.canonicalJson(b).toString()));
        b.getAsJsonObject().getAsJsonObject("effects").addProperty("grass", 5);
        check("codec value change still invalidates", !canonical.equals(LodMeshCacheContext.canonicalJson(b).toString()));
        check("codec array order remains significant", !LodMeshCacheContext.canonicalJson(com.google.gson.JsonParser.parseString("[1,2]")).equals(
            LodMeshCacheContext.canonicalJson(com.google.gson.JsonParser.parseString("[2,1]"))));
    }

    private static void corruption(Path root) throws Exception {
        Path base = root.resolve("corrupt");
        LodMeshCache cache = LodMeshCache.open(base, "test");
        cache.put(0, 0, 0, key(1), -64, 128, mesh());
        cache.flush();
        Path file = base.resolve("test/L0/m.0.0.bin");
        byte[] good = Files.readAllBytes(file);
        for (int len : new int[]{0, 15, 271, good.length - 1}) {
            Files.write(file, Arrays.copyOf(good, len));
            check("truncated region " + len, LodMeshCache.open(base, "test").get(0, 0, 0, key(1)) == null);
        }
        for (int invalid : new int[]{-1, Integer.MAX_VALUE}) {
            byte[] bad = good.clone();
            ByteBuffer.wrap(bad).putInt(16, invalid);
            Files.write(file, bad);
            check("region length rejected " + invalid, LodMeshCache.open(base, "test").get(0, 0, 0, key(1)) == null);
            bad = good.clone();
            ByteBuffer.wrap(bad).putInt(272 + 20, invalid);
            Files.write(file, bad);
            check("decoded length rejected " + invalid, LodMeshCache.open(base, "test").get(0, 0, 0, key(1)) == null);
        }
        byte[] bad = good.clone();
        bad[bad.length - 1] ^= 0xff;
        Files.write(file, bad);
        check("zlib checksum validated", LodMeshCache.open(base, "test").get(0, 0, 0, key(1)) == null);
        byte[] compressed = Arrays.copyOfRange(good, 296, good.length);
        byte[] plain = new InflaterInputStream(new ByteArrayInputStream(compressed)).readAllBytes();
        for (int field : new int[]{8, 30, 34}) {
            byte[] corruptPlain = plain.clone();
            ByteBuffer.wrap(corruptPlain).putInt(field, Integer.MAX_VALUE);
            Files.write(file, regionWithPlain(good, corruptPlain));
            check("malformed mesh structure " + field, LodMeshCache.open(base, "test").get(0, 0, 0, key(1)) == null);
        }
        Files.write(file, good);
        check("valid file still loads after corrupt misses", LodMeshCache.open(base, "test").get(0, 0, 0, key(1)) != null);
    }

    private static byte[] regionWithPlain(byte[] good, byte[] plain) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(good, 0, 296);
        try (DeflaterOutputStream z = new DeflaterOutputStream(out)) { z.write(plain); }
        byte[] result = out.toByteArray();
        ByteBuffer.wrap(result).putInt(16, result.length - 272).putInt(292, plain.length);
        return result;
    }

    private static void failedWrite(Path root) throws Exception {
        Path base = root.resolve("retry");
        LodMeshCache cache = LodMeshCache.open(base, "test");
        cache.put(0, 0, 0, key(1), -64, 128, mesh());
        Path blocker = base.resolve("test/L0/m.0.0.bin.tmp");
        Files.createDirectories(blocker);
        cache.flush();
        Files.delete(blocker);
        cache.flush();
        check("failed atomic write stays dirty for retry", LodMeshCache.open(base, "test").get(0, 0, 0, key(1)) != null);
    }

    private static void budgetReplacement(Path root) throws Exception {
        System.setProperty("mc.lod.meshCacheMB", "64");
        LodMeshCache cache = LodMeshCache.open(root.resolve("budget"), "test");
        cache.put(0, 0, 0, key(1), -64, 128, mesh());
        var used = LodMeshCache.class.getDeclaredField("diskUsed");
        used.setAccessible(true); used.setLong(cache, 64L << 20);
        cache.put(0, 0, 0, key(2), 0, 0, List.of());
        check("smaller replacement accepted when full", cache.get(0, 0, 0, key(2)) != null);
        System.clearProperty("mc.lod.meshCacheMB");
    }
}
