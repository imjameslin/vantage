package net.minecraft.client.renderer.chunk;

import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.ZipFile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;

/** Content identity for persistent meshes, computed once on a worker for each LOD manager, never per frame. */
final class LodMeshCacheContext {
    private static String codeKey;
    /** Audited draw consumers: cached sections already exist before these run. Do not exempt
     * whole packages: Lod/LodManager mix rendering with mesher settings and input construction,
     * and dependencies outside the chunk package (models, tints, vanilla compiler) matter too. */
    private static final List<String> NON_MESH_CODE_FAMILIES = List.of(
        "net/minecraft/client/renderer/chunk/LodAsyncProducerProbe",
        "net/minecraft/client/renderer/chunk/LodFarConsumerProbe",
        "net/minecraft/client/renderer/chunk/LodGpu",
        "net/minecraft/client/renderer/chunk/LodGpuBufferCapacity",
        "net/minecraft/client/renderer/chunk/LodSectionItems",
        "net/minecraft/client/renderer/chunk/LodNodeItems",
        "net/minecraft/client/renderer/chunk/LodPreparedItems",
        "net/minecraft/client/renderer/chunk/LodItemWorker",
        "net/minecraft/client/renderer/chunk/LodGeometryLeases",
        "net/minecraft/client/renderer/chunk/LodNearCoverage");

    private LodMeshCacheContext() {}

    static String describe(final ResourceManager resources, final ClientLevel level) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        text(digest, codeKey());
        text(digest, resourcesKey(resources));
        // Biome names alone are insufficient: a server/datapack can change tint definitions without renaming a biome.
        var ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        var biomes = level.registryAccess().lookupOrThrow(Registries.BIOME).listElements()
            .sorted(java.util.Comparator.comparing(b -> b.key().identifier().toString())).toList();
        for (var biome : biomes) {
            text(digest, biome.key().identifier().toString());
            text(digest, canonicalJson(Biome.NETWORK_CODEC.encodeStart(ops, biome.value()).getOrThrow()).toString());
        }
        // Render-equivalent chunk data can still get different blended tints under a different biome-zoom seed.
        var seed = BiomeManager.class.getDeclaredField("biomeZoomSeed");
        if (!seed.trySetAccessible()) {
            throw new IOException("biome zoom seed unavailable");
        }
        text(digest, Long.toString(seed.getLong(level.getBiomeManager())));
        text(digest, level.cardinalLighting().toString());
        for (var block : BuiltInRegistries.BLOCK.listElements().toList()) {
            text(digest, block.key().identifier().toString());
            for (String tag : block.tags().map(t -> t.location().toString()).sorted().toList()) {
                text(digest, tag);
            }
            text(digest, "end-tags");
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Codec maps (notably EnvironmentAttributeMap) have JVM-dependent iteration order; JSON member order is not data. */
    static com.google.gson.JsonElement canonicalJson(final com.google.gson.JsonElement value) {
        if (value.isJsonObject()) {
            var result = new com.google.gson.JsonObject();
            value.getAsJsonObject().keySet().stream().sorted()
                .forEach(key -> result.add(key, canonicalJson(value.getAsJsonObject().get(key))));
            return result;
        }
        if (value.isJsonArray()) {
            var result = new com.google.gson.JsonArray();
            value.getAsJsonArray().forEach(element -> result.add(canonicalJson(element)));
            return result;
        }
        return value;
    }

    /** Include all contributing stack entries (atlas files combine layers), including texture metadata. */
    static String resourcesKey(final ResourceManager resources) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        TreeMap<String, List<Resource>> ordered = new TreeMap<>();
        for (String root : List.of("blockstates", "fluidstates", "models", "textures", "atlases", "lod")) {
            resources.listResourceStacks(root, id -> true).forEach((id, stack) -> ordered.put(id.toString(), stack));
        }
        // listResourceStacks deliberately hides .mcmeta files. Fetch them explicitly: animation frame dimensions can
        // change sprite UVs even when the PNG and pack name are identical, including metadata-only overriding packs.
        for (String name : List.copyOf(ordered.keySet())) {
            var metadata = net.minecraft.resources.Identifier.parse(name + ".mcmeta");
            List<Resource> stack = resources.getResourceStack(metadata);
            if (!stack.isEmpty()) {
                ordered.put(metadata.toString(), stack);
            }
        }
        for (var entry : ordered.entrySet()) {
            text(digest, entry.getKey());
            text(digest, Integer.toString(entry.getValue().size()));
            for (Resource resource : entry.getValue()) {
                text(digest, resource.sourcePackId());
                try (InputStream in = resource.open()) {
                    stream(digest, in);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Hash client class contents except the audited draw-consumer families. Zip timestamps and native/shader
     * changes remain irrelevant. Changing this policy gives one cold cache; never alias an old digest to the new policy. */
    private static synchronized String codeKey() throws Exception {
        if (codeKey == null) {
            Path file = Path.of(LodMesher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            codeKey = codeKey(file);
        }
        return codeKey;
    }

    /** Same archive digest as production, uncached so CPU tests can compare isolated JAR fixtures. */
    static String codeKey(final Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (ZipFile zip = new ZipFile(file.toFile())) {
            for (String name : zip.stream().map(e -> e.getName()).filter(LodMeshCacheContext::meshCodeEntry).sorted().toList()) {
                text(digest, name);
                try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
                    stream(digest, in);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean meshCodeEntry(final String name) {
        if (!name.endsWith(".class")) return false;
        for (String family : NON_MESH_CODE_FAMILIES) {
            if (name.equals(family + ".class")
                || name.startsWith(family + "$") && name.indexOf('/', family.length()) < 0) return false;
        }
        return true;
    }

    private static void text(final MessageDigest digest, final String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void stream(final MessageDigest digest, final InputStream input) throws Exception {
        MessageDigest item = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) {
            item.update(buffer, 0, count);
        }
        digest.update(item.digest());
    }
}
