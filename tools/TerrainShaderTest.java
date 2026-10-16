import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.metal.MetalBackend;
import com.mojang.renderpearl.backend.metal.TerrainShaderVariants;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Headless exactness test of the terrain shader specialization (TerrainShaderVariants) on the real Metal backend.
 *
 * <p>The vanilla SOLID_TERRAIN, CUTOUT_TERRAIN and TRANSLUCENT_TERRAIN pipelines are compiled through the frontend
 * (shaderc, SPIRV-Cross, Metal) once per configuration (each on its own device): unchanged, specialized on UseRgss,
 * RGSS early-out, rolled RGSS loop, mip-nearest RGSS level sampler, and combinations. Each renders the same perspective scene at 1600x1000 with both UseRgss values: a 160x160
 * block ground plane receding to the horizon plus walls at several angles, so the frame covers magnified texels,
 * the RGSS transition band and strongly minified, grazing terrain; a 512x512 atlas with 6 random mip levels (random
 * alpha for cutout/translucent), random vertex colours and lightmap coordinates, fog and a chunk fade. Every
 * configuration must match the unchanged pipelines byte for byte (colour and depth), and the RGSS and nearest images
 * must differ (the test reaches both paths).
 *
 * <p>Also: the resource-pack gate (GameRenderer.metalTerrainShadersVanilla on in-memory packs: vanilla only, and a
 * pack overriding terrain.fsh, terrain.vsh, an include they use, or an unrelated shader), a configuration with a
 * terrain-overriding pack (generic path: nothing rewritten, same images), and every pipeline whose fragment shader is
 * core/terrain (OIT, wireframe, multidraw) compiling to two specialized PSOs with the default switches.
 *
 * <p>Usage: tools/build-client.sh && tools/terrain-shader-test.sh
 */
public class TerrainShaderTest {
    private static final int W = 1600;
    private static final int H = 1000;
    private static final int ATLAS = 512;
    private static final int ATLAS_MIPS = 6;
    private static int checks;
    private static int failures;

    private static void check(final boolean condition, final String what) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("  FAIL: " + what);
        }
    }

    record Config(String name, boolean specialize, boolean earlyOut, boolean noUnroll, boolean levelSampler, boolean overridden) {
        Config(final String name, final boolean specialize, final boolean earlyOut, final boolean noUnroll, final boolean levelSampler) {
            this(name, specialize, earlyOut, noUnroll, levelSampler, false);
        }
    }

    public static void main(final String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (System.getProperty("mcmetal.terrainSpecialize") == null && System.getProperty("mcmetal.terrainRgssEarlyOut") == null) {
            check(TerrainShaderVariants.specialize && TerrainShaderVariants.rgssEarlyOut, "specialization and RGSS early-out are on by default");
        }
        if (System.getProperty("mcmetal.terrainVariant") == null && System.getProperty("mcmetal.terrainVariant.use") == null) {
            check(TerrainShaderVariants.useVariant && TerrainShaderVariants.DEFAULT_MODES.equals(TerrainShaderVariants.variantModes),
                "round 11: the perceptual variant (" + TerrainShaderVariants.DEFAULT_MODES + ") is on by default");
        }
        // The exactness checks below compare the exact PSOs: no variant unless a section sets one.
        TerrainShaderVariants.variantModes = "";
        TerrainShaderVariants.useVariant = false;

        // Resource-pack gate on in-memory resource managers (the jar's vanilla pack, plus one overriding pack).
        ResourceManager vanillaOnly = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(pack("vanilla", Map.of(), true)));
        ResourceManager terrainPack = new MultiPackResourceManager(PackType.CLIENT_RESOURCES,
            List.of(pack("vanilla", Map.of(), true), pack("file/override.zip", Map.of("shaders/include/texture_sampling.glsl", "// replaced\n"), false)));
        check(GameRenderer.metalTerrainShadersVanilla(vanillaOnly), "gate: vanilla pack only -> vanilla");
        check(!GameRenderer.metalTerrainShadersVanilla(terrainPack), "gate: pack overriding include texture_sampling.glsl -> overridden");
        for (String path : new String[]{"shaders/core/terrain.fsh", "shaders/core/terrain.vsh", "shaders/include/fog.glsl", "shaders/include/oit_common.glsl"}) {
            ResourceManager m = new MultiPackResourceManager(PackType.CLIENT_RESOURCES,
                List.of(pack("vanilla", Map.of(), true), pack("file/override.zip", Map.of(path, resource("assets/minecraft/" + path)), false)));
            check(!GameRenderer.metalTerrainShadersVanilla(m), "gate: pack overriding " + path + " (same text) -> overridden");
        }

        ResourceManager unrelated = new MultiPackResourceManager(PackType.CLIENT_RESOURCES,
            List.of(pack("vanilla", Map.of(), true), pack("file/override.zip", Map.of("shaders/core/position.vsh", "// replaced\n"), false)));
        check(GameRenderer.metalTerrainShadersVanilla(unrelated), "gate: pack overriding an unrelated shader -> vanilla");
        ResourceManager quoted = new MultiPackResourceManager(PackType.CLIENT_RESOURCES,
            List.of(pack("vanilla", Map.of("shaders/core/terrain.fsh", "#include \"fog.glsl\"\n"), true)));
        check(!GameRenderer.metalTerrainShadersVanilla(quoted), "gate: quoted include -> overridden");

        MetalBackend backend = new MetalBackend();
        backend.loadLibrary();
        if ("r12".equals(System.getProperty("terrainShaderTest.only"))) {
            round12(backend, vanillaOnly);
            System.out.printf("%n%d checks, %d failures%n", checks, failures);
            System.out.println(failures == 0 ? "TERRAIN SHADER TEST PASSED (r12 only)" : "TERRAIN SHADER TEST FAILED");
            System.exit(failures == 0 ? 0 : 1);
        }
        List<Config> configs = List.of(
            new Config("unchanged", false, false, false, false),
            new Config("specialized", true, false, false, false),
            new Config("rgss-early-out", false, true, false, false),
            new Config("rgss-no-unroll", false, false, true, false),
            new Config("rgss-level-sampler", false, false, false, true),
            new Config("specialized+early-out", true, true, false, false),
            new Config("specialized+early-out+no-unroll+level-sampler", true, true, true, true),
            new Config("resource-pack-override (all switches on)", true, true, true, true, true)
        );
        Map<String, byte[]> reference = null;
        for (Config config : configs) {
            TerrainShaderVariants.specialize = config.specialize();
            TerrainShaderVariants.rgssEarlyOut = config.earlyOut();
            TerrainShaderVariants.rgssNoUnroll = config.noUnroll();
            TerrainShaderVariants.rgssLevelSampler = config.levelSampler();
            ResourceManager manager = config.overridden() ? terrainPack : vanillaOnly;
            TerrainShaderVariants.setVanillaSourceCheck(() -> GameRenderer.metalTerrainShadersVanilla(manager));
            GpuDevice device = backend.createDevice(new GpuDebugOptions(0, false, false, true));
            installRenderSystemDevice(device);
            Map<String, byte[]> images;
            long t0 = System.nanoTime();
            try (Rig rig = new Rig(device)) {
                images = rig.run();
            } finally {
                device.close();
            }
            int specialized = TerrainShaderVariants.SPECIALIZED.getAndSet(0);
            int earlyOut = TerrainShaderVariants.EARLY_OUT_APPLIED.getAndSet(0);
            int noUnroll = TerrainShaderVariants.NO_UNROLL_APPLIED.getAndSet(0);
            int levelSampler = TerrainShaderVariants.LEVEL_SAMPLER_APPLIED.getAndSet(0);
            int overridden = TerrainShaderVariants.OVERRIDDEN.getAndSet(0);
            System.out.printf("== %s: %d images in %.0f ms (fragment shaders specialized %d, early-out %d, rolled loop %d, level sampler %d, generic (pack) %d)%n",
                config.name(), images.size(), (System.nanoTime() - t0) / 1e6, specialized, earlyOut, noUnroll, levelSampler, overridden);
            boolean on = !config.overridden();
            check((on && config.levelSampler()) == (levelSampler == 3), config.name() + ": level sampler applied to the 3 terrain shaders iff on (" + levelSampler + ")");
            check((on && config.specialize()) == (specialized == 3), config.name() + ": 3 terrain pipelines specialized iff on (" + specialized + ")");
            check((on && config.earlyOut()) == (earlyOut == 3), config.name() + ": early-out applied to the 3 terrain shaders iff on (" + earlyOut + ")");
            check((on && config.noUnroll()) == (noUnroll == 3), config.name() + ": rolled loop applied to the 3 terrain shaders iff on (" + noUnroll + ")");
            check(config.overridden() == (overridden == 3), config.name() + ": generic path for the 3 terrain shaders iff a pack overrides them (" + overridden + ")");
            if (reference == null) {
                reference = images;
                for (String pipeline : new String[]{"solid", "cutout", "translucent"}) {
                    byte[] nearest = images.get(pipeline + "/nearest/color");
                    byte[] rgss = images.get(pipeline + "/rgss/color");
                    int differing = differingPixels(nearest, rgss);
                    System.out.printf("   %s: RGSS differs from nearest in %d of %d pixels; %d pixels covered%n", pipeline, differing, W * H, covered(nearest));
                    check(differing > W * H / 100, pipeline + ": the RGSS path changes the image (" + differing + " pixels)");
                }
                continue;
            }

            for (Map.Entry<String, byte[]> e : reference.entrySet()) {
                byte[] actual = images.get(e.getKey());
                int differing = actual == null ? -1 : differingPixels(e.getValue(), actual);
                check(differing == 0, config.name() + " " + e.getKey() + " equals the unchanged pipeline (" + differing + " pixels differ)");
            }
        }

        // CONTRACT.md 16.R8-2: runtime-switchable nearest-path variants (exact candidates) against the unchanged pipelines.
        String[] variantModes = System.getProperty("terrainShaderTest.variants", "maglevel0;earlydiscard;maglevel0,earlydiscard").split(";");
        for (String modes : variantModes) {
            TerrainShaderVariants.specialize = true;
            TerrainShaderVariants.rgssEarlyOut = true;
            TerrainShaderVariants.rgssNoUnroll = false;
            TerrainShaderVariants.rgssLevelSampler = false;
            TerrainShaderVariants.variantModes = modes;
            TerrainShaderVariants.variantPipelines = "";
            TerrainShaderVariants.useVariant = true;
            TerrainShaderVariants.setFrameAnisotropic(false);
            TerrainShaderVariants.setVanillaSourceCheck(() -> GameRenderer.metalTerrainShadersVanilla(vanillaOnly));
            GpuDevice device = backend.createDevice(new GpuDebugOptions(0, false, false, true));
            installRenderSystemDevice(device);
            Map<String, byte[]> images;
            try (Rig rig = new Rig(device)) {
                images = rig.run();
            } finally {
                device.close();
            }
            int built = TerrainShaderVariants.VARIANTS_BUILT.getAndSet(0);
            TerrainShaderVariants.SPECIALIZED.set(0);
            TerrainShaderVariants.EARLY_OUT_APPLIED.set(0);
            System.out.printf("== variant %s: %d images, variant PSOs %d%n", modes, images.size(), built);
            int expected = modes.contains("earlydiscard") ? 2 : 3;   // solid has no alpha test
            check(built == expected, "variant " + modes + ": built for " + expected + " pipelines (" + built + ")");
            for (Map.Entry<String, byte[]> e : reference.entrySet()) {
                byte[] actual = images.get(e.getKey());
                int differing = actual == null ? -1 : differingPixels(e.getValue(), actual);
                check(differing == 0, "variant " + modes + " " + e.getKey() + " equals the unchanged pipeline (" + differing + " pixels differ)");
            }
            if (Boolean.getBoolean("terrainShaderTest.diagnosticVariant")) {
                break;
            }
        }
        // Mutation canary: an inexact variant (level 0 everywhere) must differ from the reference.
        {
            TerrainShaderVariants.variantModes = "level0";
            GpuDevice device = backend.createDevice(new GpuDebugOptions(0, false, false, true));
            installRenderSystemDevice(device);
            Map<String, byte[]> images;
            try (Rig rig = new Rig(device)) {
                images = rig.run();
            } finally {
                device.close();
            }
            TerrainShaderVariants.VARIANTS_BUILT.set(0);
            TerrainShaderVariants.SPECIALIZED.set(0);
            TerrainShaderVariants.EARLY_OUT_APPLIED.set(0);
            int differing = differingPixels(reference.get("cutout/nearest/color"), images.get("cutout/nearest/color"));
            System.out.printf("== canary level0: cutout nearest differs in %d pixels%n", differing);
            check(differing > 0, "canary: the level-0 variant changes the image (the comparison sees variants)");
            TerrainShaderVariants.variantModes = "";
            TerrainShaderVariants.useVariant = false;
        }

        // CONTRACT.md 16.R11: perceptual-only candidates (round 11). Not byte-exact by design; bounded per-pixel error.
        // Fog parameters of this rig: environmental 30..90 (spherical), render distance 50..80 (cylindrical), so the
        // ramps' kinks cross many of the 1-block quads (the worst case for per-vertex fog), plus a chunk fade of 0.6.
        String[] inexact = System.getProperty("terrainShaderTest.inexact", "vfog;vfog,hvary").split(";");
        for (String modes : inexact) {
            if (modes.isBlank()) {
                continue;
            }
            TerrainShaderVariants.specialize = true;
            TerrainShaderVariants.rgssEarlyOut = true;
            TerrainShaderVariants.rgssNoUnroll = false;
            TerrainShaderVariants.rgssLevelSampler = false;
            TerrainShaderVariants.variantModes = modes;
            TerrainShaderVariants.variantPipelines = "";
            TerrainShaderVariants.useVariant = true;
            TerrainShaderVariants.setFrameAnisotropic(false);
            TerrainShaderVariants.setVanillaSourceCheck(() -> GameRenderer.metalTerrainShadersVanilla(vanillaOnly));
            GpuDevice device = backend.createDevice(new GpuDebugOptions(0, false, false, true));
            installRenderSystemDevice(device);
            Map<String, byte[]> images;
            try (Rig rig = new Rig(device)) {
                images = rig.run();
            } finally {
                device.close();
            }
            int built = TerrainShaderVariants.VARIANTS_BUILT.getAndSet(0);
            int rgssBuilt = TerrainShaderVariants.RGSS_VARIANTS_BUILT.getAndSet(0);
            int failed = TerrainShaderVariants.VARIANT_FAILURES.getAndSet(0);
            TerrainShaderVariants.SPECIALIZED.set(0);
            TerrainShaderVariants.EARLY_OUT_APPLIED.set(0);
            System.out.printf("== inexact variant %s: variant PSOs %d nearest + %d RGSS, failed %d%n", modes, built, rgssBuilt, failed);
            check(built == 3 && rgssBuilt == 3 && failed == 0, "inexact variant " + modes + ": built for the 3 pipelines, nearest and RGSS (" + built + ", " + rgssBuilt + ", failed " + failed + ")");
            // The off switch: the same PSOs built, useVariant false -> byte-identical to the exact pipelines.
            {
                TerrainShaderVariants.useVariant = false;
                GpuDevice off = backend.createDevice(new GpuDebugOptions(0, false, false, true));
                installRenderSystemDevice(off);
                Map<String, byte[]> offImages;
                try (Rig rig = new Rig(off)) {
                    offImages = rig.run();
                } finally {
                    off.close();
                }
                TerrainShaderVariants.VARIANTS_BUILT.set(0);
                TerrainShaderVariants.RGSS_VARIANTS_BUILT.set(0);
                TerrainShaderVariants.SPECIALIZED.set(0);
                TerrainShaderVariants.EARLY_OUT_APPLIED.set(0);
                for (Map.Entry<String, byte[]> e : reference.entrySet()) {
                    check(differingPixels(e.getValue(), offImages.get(e.getKey())) == 0, "variant " + modes + " built but switched off: " + e.getKey() + " equals the exact pipeline");
                }
                TerrainShaderVariants.useVariant = true;
            }
            for (Map.Entry<String, byte[]> e : reference.entrySet()) {
                byte[] actual = images.get(e.getKey());
                if (e.getKey().endsWith("/depth")) {
                    // Same vertex positions: depth is unchanged unless a cutout pixel flips its alpha test.
                    System.out.printf("   %s: depth differs in %d pixels%n", e.getKey(), differingPixels(e.getValue(), actual));
                    continue;
                }
                int[] st = deltaStats(e.getValue(), actual);
                System.out.printf("   %s: %d pixels differ, max channel delta %d, pixels with delta > 2: %d, > 4: %d%n", e.getKey(), st[0], st[1], st[2], st[3]);
                check(st[0] > 0, "inexact variant " + modes + " " + e.getKey() + ": the variant is in use (some pixel differs)");
                check(st[3] <= W * H / 10000, "inexact variant " + modes + " " + e.getKey() + ": at most 0.01 % of pixels off by more than 4 levels (" + st[3] + ")");
            }
        }
        TerrainShaderVariants.variantModes = "";
        TerrainShaderVariants.useVariant = false;

        round12(backend, vanillaOnly);
        coverage(backend, vanillaOnly);
        System.out.printf("%n%d checks, %d failures%n", checks, failures);
        System.out.println(failures == 0 ? "TERRAIN SHADER TEST PASSED" : "TERRAIN SHADER TEST FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * Every pipeline whose fragment shader is core/terrain (solid, cutout, translucent, OIT, wireframe, multidraw) gets
     * the default rewrites and two PSOs; a non-terrain pipeline sharing the texture_sampling include does not.
     */
    static void coverage(final MetalBackend backend, final ResourceManager vanillaOnly) throws Exception {
        TerrainShaderVariants.specialize = true;
        TerrainShaderVariants.rgssEarlyOut = true;
        TerrainShaderVariants.rgssNoUnroll = false;
        TerrainShaderVariants.rgssLevelSampler = false;
        TerrainShaderVariants.variantModes = TerrainShaderVariants.DEFAULT_MODES;   // round 11 default variant on every terrain pipeline
        TerrainShaderVariants.variantPipelines = "";
        TerrainShaderVariants.useVariant = true;
        TerrainShaderVariants.VARIANTS_BUILT.set(0);
        TerrainShaderVariants.RGSS_VARIANTS_BUILT.set(0);
        TerrainShaderVariants.VARIANT_FAILURES.set(0);
        TerrainShaderVariants.setVanillaSourceCheck(() -> GameRenderer.metalTerrainShadersVanilla(vanillaOnly));
        Identifier terrain =Identifier.parse(TerrainShaderVariants.TERRAIN_FRAGMENT);
        List<RenderPipeline> pipelines = new ArrayList<>();
        List<RenderPipeline> all = new ArrayList<>(RenderPipelines.requiredPipelines());
        all.addAll(RenderPipelines.optionalPipelines());   // wireframe
        for (RenderPipeline p : all) {
            if (terrain.equals(p.getShaders().get(ShaderType.FRAGMENT))) {
                pipelines.add(p);
            }
        }

        pipelines.add(RenderPipelines.ENTITY_CUTOUT);   // control: not terrain
        GpuDevice device = backend.createDevice(new GpuDebugOptions(0, false, false, true));
        try {
            installRenderSystemDevice(device);
            ExecutorService workers = Executors.newFixedThreadPool(4);
            try (ShaderSource shaders = jarShaderSource()) {
                for (RenderPipeline p : pipelines) {
                    CompiledRenderPipeline c = device.compilePipeline(p, shaders, workers).join().finishCompile();
                    check(c != null, "coverage: compile " + p.getLocation());
                    if (c != null) {
                        c.close();
                    }
                }
            } finally {
                workers.shutdown();
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        } finally {
            device.close();
        }

        int n = pipelines.size() - 1;
        int specialized = TerrainShaderVariants.SPECIALIZED.getAndSet(0);
        int earlyOut = TerrainShaderVariants.EARLY_OUT_APPLIED.getAndSet(0);
        System.out.printf("== coverage: %d terrain pipelines (%s): specialized %d, early-out %d%n", n,
            pipelines.subList(0, n).stream().map(p -> p.getLocation().getPath()).toList(), specialized, earlyOut);
        check(n >= 14, "coverage: at least the 14 vanilla terrain pipelines (" + n + ")");
        check(specialized == n, "coverage: every terrain pipeline specialized (" + specialized + " of " + n + ")");
        // Round 12 Part B: the greedy prototype's pipelines sample through their own RGSS function (explicit derivatives).
        long greedy = pipelines.subList(0, n).stream().filter(p -> p.getLocation().getPath().contains("_greedy")).count();
        check(earlyOut == n - greedy, "coverage: RGSS early-out in every terrain pipeline but the greedy ones (" + earlyOut + " of " + (n - greedy) + ")");
        long oit = pipelines.subList(0, n).stream().filter(p -> p.getLocation().getPath().contains("oit_")).count();
        int vb = TerrainShaderVariants.VARIANTS_BUILT.getAndSet(0);
        int rb = TerrainShaderVariants.RGSS_VARIANTS_BUILT.getAndSet(0);
        int vf = TerrainShaderVariants.VARIANT_FAILURES.getAndSet(0);
        System.out.printf("== coverage: default variant %s built for %d nearest / %d RGSS PSOs (%d OIT pipelines keep the exact shaders), %d failures%n",
            TerrainShaderVariants.DEFAULT_MODES, vb, rb, oit, vf);
        check(vb == n - oit && rb == n - oit && vf == 0, "coverage: default variant on every non-OIT terrain pipeline, nearest and RGSS (" + vb + ", " + rb + " of " + (n - oit) + ", failures " + vf + ")");
        TerrainShaderVariants.variantModes = "";
        TerrainShaderVariants.useVariant = false;
    }

    /** In-memory pack: {@code files} (path under assets/minecraft/ -> text) over, if {@code jar}, the jar's own assets. */
    static PackResources pack(final String id, final Map<String, String> files, final boolean jar) {
        PackLocationInfo location = new PackLocationInfo(id, Component.literal(id), PackSource.BUILT_IN, Optional.empty());
        return new PackResources() {
            @Override
            public IoSupplier<InputStream> getResource(final PackType type, final Identifier id) {
                if (!id.getNamespace().equals("minecraft")) {
                    return null;
                }

                String text = files.get(id.getPath());
                if (text == null && jar) {
                    text = resource("assets/minecraft/" + id.getPath());
                }

                byte[] bytes = text == null ? null : text.getBytes(StandardCharsets.UTF_8);
                return bytes == null ? null : () -> new ByteArrayInputStream(bytes);
            }

            @Override
            public void listResources(final PackType type, final String namespace, final String directory, final PackResources.ResourceOutput output) {
            }

            @Override
            public Set<String> getNamespaces(final PackType type) {
                return Set.of("minecraft");
            }

            @Override
            public PackLocationInfo location() {
                return location;
            }

            @Override
            public IoSupplier<InputStream> getRootResource(final String... path) {
                return null;
            }

            @Override
            public <T> T getMetadataSection(final MetadataSectionType<T> type) {
                return null;
            }

            @Override
            public void close() {
            }
        };
    }

    /** {differing pixels, max channel delta, pixels with a channel delta > 2, pixels with a channel delta > 4} (RGBA8). */
    static int[] deltaStats(final byte[] a, final byte[] b) {
        int diff = 0;
        int max = 0;
        int gt2 = 0;
        int gt4 = 0;
        for (int i = 0; i < a.length; i += 4) {
            int m = 0;
            for (int c = 0; c < 4; c++) {
                m = Math.max(m, Math.abs((a[i + c] & 0xFF) - (b[i + c] & 0xFF)));
            }
            if (m > 0) {
                diff++;
            }
            if (m > 2) {
                gt2++;
            }
            if (m > 4) {
                gt4++;
            }
            max = Math.max(max, m);
        }
        return new int[]{diff, max, gt2, gt4};
    }

    static int differingPixels(final byte[] a, final byte[] b) {
        if (a.length != b.length) {
            return Integer.MAX_VALUE;
        }

        int n = 0;
        for (int i = 0; i < a.length; i += 4) {
            if (a[i] != b[i] || a[i + 1] != b[i + 1] || a[i + 2] != b[i + 2] || a[i + 3] != b[i + 3]) {
                n++;
            }
        }

        return n;
    }

    /** Pixels that are not the clear colour (0, 0, 0, 0). */
    static int covered(final byte[] img) {
        int n = 0;
        for (int i = 0; i < img.length; i += 4) {
            if ((img[i] | img[i + 1] | img[i + 2] | img[i + 3]) != 0) {
                n++;
            }
        }

        return n;
    }

    private static void installRenderSystemDevice(final GpuDevice device) throws ReflectiveOperationException {
        Field field = RenderSystem.class.getDeclaredField("DEVICE");
        field.setAccessible(true);
        field.set(null, device);
    }

    /**
     * Round 12 (CONTRACT.md 16.R12-B / 16.R12-C): the multidraw terrain pipelines (per-instance section info, base vertex /
     * base instance per section draw) with the translated vertex function vs the hand-written pull form ({@code TerrainVertexMsl}),
     * exact PSOs and the round-11 variant PSOs; and the alternative triangle orders of {@code TerrainToggles.QUAD_INDEX_PATTERNS}.
     * Every image must equal the reference byte for byte (colour and depth). Vertex colours cover every byte value.
     */
    static void round12(final MetalBackend backend, final ResourceManager vanillaOnly) throws Exception {
        TerrainShaderVariants.specialize = true;
        TerrainShaderVariants.rgssEarlyOut = true;
        TerrainShaderVariants.rgssNoUnroll = false;
        TerrainShaderVariants.rgssLevelSampler = false;
        TerrainShaderVariants.variantPipelines = "";
        TerrainShaderVariants.setFrameAnisotropic(false);
        TerrainShaderVariants.setVanillaSourceCheck(() -> GameRenderer.metalTerrainShadersVanilla(vanillaOnly));
        int[][] patterns = net.minecraft.client.renderer.chunk.TerrainToggles.QUAD_INDEX_PATTERNS;
        boolean quick = Boolean.getBoolean("terrainShaderTest.r12quick");
        for (String modes : quick ? new String[]{""} : new String[]{"", TerrainShaderVariants.DEFAULT_MODES}) {
            TerrainShaderVariants.variantModes = modes;
            TerrainShaderVariants.useVariant = !modes.isEmpty();
            Map<String, byte[]> ref = null;
            // {hand-written PSOs built, selected, quad index pattern}
            int[][] configs = quick ? new int[][]{{0, 0, 0}, {1, 1, 0}} : new int[][]{{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 0, 1}, {1, 1, 1}};
            for (int[] c : configs) {
                com.mojang.renderpearl.backend.metal.TerrainVertexMsl.build = c[0] == 1;
                com.mojang.renderpearl.backend.metal.TerrainVertexMsl.useHand = c[1] == 1;
                GpuDevice device = backend.createDevice(new GpuDebugOptions(0, false, false, true));
                installRenderSystemDevice(device);
                Map<String, byte[]> images;
                try (Rig rig = new Rig(device, true, patterns[c[2]])) {
                    images = rig.run();
                } finally {
                    device.close();
                }
                int handBuilt = com.mojang.renderpearl.backend.metal.TerrainVertexMsl.BUILT.getAndSet(0);
                int handFailed = com.mojang.renderpearl.backend.metal.TerrainVertexMsl.FAILURES.getAndSet(0);
                TerrainShaderVariants.VARIANTS_BUILT.set(0);
                TerrainShaderVariants.RGSS_VARIANTS_BUILT.set(0);
                TerrainShaderVariants.SPECIALIZED.set(0);
                TerrainShaderVariants.EARLY_OUT_APPLIED.set(0);
                String name = "r12 multidraw [variant '" + modes + "', hand built " + c[0] + " used " + c[1] + ", pattern " + c[2] + "]";
                System.out.printf("== %s: %d images, hand-written sets built %d, failed %d%n", name, images.size(), handBuilt, handFailed);
                check(handBuilt == (c[0] == 1 ? 3 : 0) && handFailed == 0, name + ": hand-written PSO sets built for the 3 multidraw pipelines iff on (" + handBuilt + ", failed " + handFailed + ")");
                if (ref == null) {
                    ref = images;
                    continue;
                }
                for (Map.Entry<String, byte[]> e : ref.entrySet()) {
                    byte[] actual = images.get(e.getKey());
                    int differing = actual == null ? -1 : differingPixels(e.getValue(), actual);
                    check(differing == 0, name + " " + e.getKey() + " equals the translated vertex function, pattern 0 (" + differing + " pixels differ)");
                }
            }
        }
        com.mojang.renderpearl.backend.metal.TerrainVertexMsl.build = false;
        com.mojang.renderpearl.backend.metal.TerrainVertexMsl.useHand = true;
        clusterTest(backend, quick);
        greedyTest(backend);
        TerrainShaderVariants.variantModes = "";
        TerrainShaderVariants.useVariant = false;
    }

    /**
     * Round 12 (a) (CONTRACT.md 16.R12-A): SOLID / CUTOUT drawn per section through the multidraw pipelines vs the same layout-A
     * meshes through the GPU cluster cull and the cluster pipelines (vertex pulling, one indirect draw): byte-identical colour and
     * depth with the exact PSOs and with the round-11 variant, nearest and RGSS; the cull must drop quads (frustum and facing).
     * Skipped with {@code -Dmc.terrain.clusters=false} (the cluster pipelines and mesh-time clusters are startup switches).
     */
    static void clusterTest(final MetalBackend backend, final boolean quick) throws Exception {
        if (!net.minecraft.client.renderer.chunk.TerrainClusters.BUILD) {
            System.out.println("== r12 clusters: skipped (-Dmc.terrain.clusters=false)");
            check(!Boolean.getBoolean("terrainShaderTest.requireClusters"), "cluster test requested but -Dmc.terrain.clusters is off");
            return;
        }
        for (String modes : quick ? new String[]{""} : new String[]{"", TerrainShaderVariants.DEFAULT_MODES}) {
            TerrainShaderVariants.variantModes = modes;
            TerrainShaderVariants.useVariant = !modes.isEmpty();
            GpuDevice device = backend.createDevice(new GpuDebugOptions(0, false, false, true));
            installRenderSystemDevice(device);
            Map<String, byte[]> images;
            long[] submitted;
            long kept;
            Rig rig = new Rig(device, true, new int[]{0, 1, 2, 2, 3, 0});
            rig.clusterMode = true;
            try {
                images = rig.run();
                submitted = rig.submitted.clone();
                kept = rig.clusterFrame == null ? -1L : rig.clusterFrame.keptQuads(0);
                if (rig.clusterFrame != null) {
                    rig.clusterFrame.close();
                }
            } finally {
                rig.close();
                device.close();
            }
            TerrainShaderVariants.VARIANTS_BUILT.set(0);
            TerrainShaderVariants.RGSS_VARIANTS_BUILT.set(0);
            TerrainShaderVariants.SPECIALIZED.set(0);
            TerrainShaderVariants.EARLY_OUT_APPLIED.set(0);
            String name = "r12 clusters [variant '" + modes + "']";
            System.out.printf("== %s: %d images; last cull (cutout, RGSS): %d quads submitted, %d kept%n", name, images.size(), submitted[1], kept);
            check(kept > 0 && kept < submitted[1], name + ": the GPU cull keeps some quads and drops some (" + kept + " of " + submitted[1] + ")");
            for (String layer : new String[]{"solid", "cutout"}) {
                for (String rest : new String[]{"/nearest/color", "/nearest/depth", "/rgss/color", "/rgss/depth"}) {
                    byte[] ref = images.get(layer + rest);
                    byte[] cl = images.get(layer + "-cl" + rest);
                    int differing = ref == null || cl == null ? -1 : differingPixels(ref, cl);
                    // Depth (coverage, positions) and CUTOUT colour must be byte-identical. SOLID colour: the Apple GPU interpolates
                    // varyings of opaque (HSR) draws differently at a few pixels when the primitive stream changes (one draw instead
                    // of one per section, or back-facing quads left out: measured 6-34 pixels of 1.6 M, at most 3 levels, both
                    // alone; per-section draws with only the frustum test are byte-identical): allowed up to 0.01 % of pixels,
                    // at most 4 levels (measured max 3).
                    boolean strict = rest.endsWith("/depth") || layer.equals("cutout") || Boolean.getBoolean("terrainShaderTest.clusterStrict");
                    int[] st = ref == null || cl == null ? new int[]{-1, 99, 99, 99} : deltaStats(ref, cl);
                    check(strict ? differing == 0 : differing >= 0 && differing <= W * H / 10000 && st[1] <= 4,
                        name + " " + layer + rest + ": cluster draw equals the per-section multidraw draw" + (strict ? "" : " (<= 0.01 % px, <= 4 levels)")
                            + " (" + differing + " pixels differ, max delta " + st[1] + ")");
                    if (!strict) {
                        System.out.printf("   %s %s%s: %d pixels differ, max channel delta %d%n", name, layer, rest, differing, st[1]);
                    }
                    if (differing > 0) {
                        StringBuilder where = new StringBuilder();
                        for (int i = 0, shown = 0; i < ref.length && shown < 8; i += 4) {
                            if (ref[i] != cl[i] || ref[i + 1] != cl[i + 1] || ref[i + 2] != cl[i + 2] || ref[i + 3] != cl[i + 3]) {
                                where.append(String.format(" (%d,%d)", (i / 4) % W, (i / 4) / W));
                                shown++;
                            }
                        }
                        int[] st2 = deltaStats(ref, cl);
                        System.out.println("     first differing pixels:" + where + "; max channel delta " + st2[1] + ", > 2: " + st2[2] + ", > 4: " + st2[3]);
                    }
                }
            }
            check(covered(images.get("cutout-cl/nearest/color")) > W * H / 4, name + ": the cluster draw covers the frame");
            for (String rest : new String[]{"/nearest/color", "/rgss/color"}) {
                System.out.printf("   determinism: solid-cl vs solid-cl2%s %d pixels, solid vs solid2%s %d pixels%n", rest,
                    differingPixels(images.get("solid-cl" + rest), images.get("solid-cl2" + rest)), rest, differingPixels(images.get("solid" + rest), images.get("solid2" + rest)));
            }
        }
    }

    /**
     * Round 12 Part B (prototype, not exact): the vanilla multidraw quads vs their greedy copy (TerrainGreedy, run with
     * -Dmc.terrain.greedy=exact|aggressive) through the greedy pipelines, on regions of one sprite / colour / light. Reports the
     * quad reduction and the pixel deltas; checks that it merged and that the image stays close (wrong UV wrapping would
     * change most pixels of every merged face).
     */
    static void greedyTest(final MetalBackend backend) throws Exception {
        if (!net.minecraft.client.renderer.chunk.TerrainGreedy.BUILD) {
            System.out.println("== r12 greedy: skipped (run with -Dmc.terrain.greedy=exact or aggressive)");
            return;
        }
        net.minecraft.client.renderer.chunk.TerrainGreedy.setAtlasSize(ATLAS, ATLAS);
        TerrainShaderVariants.variantModes = "";
        TerrainShaderVariants.useVariant = false;
        GpuDevice device = backend.createDevice(new GpuDebugOptions(0, false, false, true));
        installRenderSystemDevice(device);
        Map<String, byte[]> images;
        int in;
        int out;
        Rig rig = new Rig(device, true, new int[]{0, 1, 2, 2, 3, 0});
        rig.greedyMode = true;
        try {
            images = rig.run();
            in = rig.greedyQuadsIn;
            out = rig.greedyQuadsOut;
        } finally {
            rig.close();
            device.close();
        }
        TerrainShaderVariants.VARIANTS_BUILT.set(0);
        TerrainShaderVariants.RGSS_VARIANTS_BUILT.set(0);
        TerrainShaderVariants.SPECIALIZED.set(0);
        TerrainShaderVariants.EARLY_OUT_APPLIED.set(0);
        System.out.printf("== r12 greedy (%s): %d quads -> %d%n", System.getProperty("mc.terrain.greedy"), in, out);
        String dumpDir = System.getProperty("terrainShaderTest.dump");
        if (dumpDir != null) {
            for (String key : new String[]{"solid/nearest/color", "solid-g/nearest/color"}) {
                byte[] img = images.get(key);
                java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(W, H, java.awt.image.BufferedImage.TYPE_INT_RGB);
                for (int i = 0; i < W * H; i++) {
                    bi.setRGB(i % W, i / W, (img[4 * i] & 0xFF) << 16 | (img[4 * i + 1] & 0xFF) << 8 | img[4 * i + 2] & 0xFF);
                }
                javax.imageio.ImageIO.write(bi, "png", new java.io.File(dumpDir, key.replace('/', '_') + ".png"));
            }
        }
        check(out < in / 4, "greedy: merged most quads of the uniform regions (" + in + " -> " + out + ")");
        for (String layer : new String[]{"solid", "cutout"}) {
            for (String rest : new String[]{"/nearest/color", "/rgss/color", "/nearest/depth"}) {
                byte[] ref = images.get(layer + rest);
                byte[] g = images.get(layer + "-g" + rest);
                int[] st = deltaStats(ref, g);
                System.out.printf("   greedy %s%s: %d pixels differ (%.3f %%), max delta %d, > 2: %d, > 4: %d%n", layer, rest, st[0], 100.0 * st[0] / (W * H),
                    st[1], st[2], st[3]);
                if (rest.endsWith("/color")) {
                    // Not exact by design: mostly the fog (this rig ramps it over 30-90 blocks, so per-vertex distances interpolated
                    // across 8-block faces differ), plus T-junction and wrap-seam pixels. A wrong UV wrap changes most pixels.
                    check(st[3] <= W * H / 20, "greedy " + layer + rest + ": at most 5 % of pixels off by more than 4 levels (" + st[3] + ")");
                }
            }
        }
    }

    private static final class Rig implements AutoCloseable {
        final GpuDevice device;
        final CommandEncoder enc;
        final ExecutorService workers = Executors.newFixedThreadPool(4);
        final ShaderSource shaders = jarShaderSource();
        final List<AutoCloseable> owned = new ArrayList<>();
        /** Round 12: multidraw pipelines, sections as instances (per-instance ChunkPosition / visibility). */
        final boolean multidraw;
        final int[] pattern;
        /** Round 12 (a): SOLID / CUTOUT multidraw reference vs the cluster pipelines fed by the GPU cull (TerrainClusters). */
        boolean clusterMode;
        int clusterLayer = -1;
        List<net.minecraft.client.renderer.chunk.TerrainMeshInfo> sectionInfos = new ArrayList<>();
        net.minecraft.client.renderer.chunk.TerrainClusters.Frame clusterFrame;
        long[] submitted = new long[2];
        /** Round 12 Part B: vanilla multidraw quads vs their greedy copy (TerrainGreedy) through the greedy pipelines. */
        boolean greedyMode;
        Geometry greedyGeometry;
        int greedyQuadsIn;
        int greedyQuadsOut;
        int[] origins = new int[0];
        List<ByteBuffer> greedyParts = new ArrayList<>();

        Rig(final GpuDevice device) {
            this(device, false, new int[]{0, 1, 2, 2, 3, 0});
        }

        Rig(final GpuDevice device, final boolean multidraw, final int[] pattern) {
            this.device = device;
            this.enc = device.createCommandEncoder();
            this.multidraw = multidraw;
            this.pattern = pattern;
        }

        <T extends AutoCloseable> T own(final T t) {
            this.owned.add(t);
            return t;
        }

        Map<String, byte[]> run() {
            List<RenderPipeline> all = this.greedyMode
                ? List.of(RenderPipelines.SOLID_TERRAIN_MULTIDRAW, RenderPipelines.CUTOUT_TERRAIN_MULTIDRAW, RenderPipelines.SOLID_TERRAIN_GREEDY_MULTIDRAW,
                    RenderPipelines.CUTOUT_TERRAIN_GREEDY_MULTIDRAW)
                : this.clusterMode
                ? List.of(RenderPipelines.SOLID_TERRAIN_MULTIDRAW, RenderPipelines.CUTOUT_TERRAIN_MULTIDRAW, RenderPipelines.SOLID_TERRAIN_CLUSTER, RenderPipelines.CUTOUT_TERRAIN_CLUSTER,
                    RenderPipelines.SOLID_TERRAIN_CLUSTER, RenderPipelines.SOLID_TERRAIN_MULTIDRAW)
                : this.multidraw
                ? List.of(RenderPipelines.SOLID_TERRAIN_MULTIDRAW, RenderPipelines.CUTOUT_TERRAIN_MULTIDRAW, RenderPipelines.TRANSLUCENT_TERRAIN_MULTIDRAW)
                : List.of(RenderPipelines.SOLID_TERRAIN, RenderPipelines.CUTOUT_TERRAIN, RenderPipelines.TRANSLUCENT_TERRAIN);
            var futures = all.stream().map(p -> this.device.compilePipeline(p, this.shaders, this.workers)).toList();
            List<CompiledRenderPipeline> compiled = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                CompiledRenderPipeline c = futures.get(i).join().finishCompile();
                check(c != null, "compile " + all.get(i).getLocation());
                if (c == null) {
                    throw new IllegalStateException("pipeline compile failed");
                }

                compiled.add(this.own(c));
            }

            GpuTexture atlas = this.atlas();
            GpuTexture lightmap = this.lightmap();
            // The game's chunk-layer sampler (LevelRenderer): clamp, linear/linear, mipmapped (maxLod empty), anisotropy 1
            // outside the ANISOTROPIC option.
            GpuSampler atlasSampler = this.own(this.device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1,
                OptionalDouble.empty()));
            GpuSampler lightSampler = this.own(this.device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1,
                OptionalDouble.of(0.0)));
            Geometry geometry = this.geometry();
            Map<String, byte[]> images = new LinkedHashMap<>();
            String[] names = this.greedyMode ? new String[]{"solid", "cutout", "solid-g", "cutout-g"} : this.clusterMode ? new String[]{"solid", "cutout", "solid-cl", "cutout-cl", "solid-cl2", "solid2"} : new String[]{"solid", "cutout", "translucent"};
            for (int p = 0; p < names.length; p++) {
                this.clusterLayer = !this.clusterMode || p < 2 || p == 5 ? -1 : p == 4 ? 0 : p - 2;
                for (boolean rgss : new boolean[]{false, true}) {
                    GpuTexture color = this.own(this.device.createTexture((String)"terrain color",
                        GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, W, H, 1, 1));
                    GpuTexture depth = this.own(this.device.createTexture((String)"terrain depth",
                        GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.D32_FLOAT, W, H, 1, 1));
                    this.render(compiled.get(p), rgss, color, depth, atlas, atlasSampler, lightmap, lightSampler,
                        this.greedyMode && p >= 2 ? this.greedyGeometry : geometry);
                    String key = names[p] + "/" + (rgss ? "rgss" : "nearest");
                    images.put(key + "/color", this.readback(color));
                    images.put(key + "/depth", this.readback(depth));
                }
            }

            return images;
        }

        record Geometry(GpuBuffer vertices, GpuBuffer indices, int quads, GpuBuffer instances, int[] sections) {
            Geometry(final GpuBuffer vertices, final GpuBuffer indices, final int quads) {
                this(vertices, indices, quads, null, null);
            }
        }

        /** Atlas 512x512, 6 mips of random texels; alpha is 0 or 255 per texel (cutout) with some mid values. */
        GpuTexture atlas() {
            GpuTexture atlas = this.own(this.device.createTexture((String)"terrain atlas", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, ATLAS, ATLAS, 1, ATLAS_MIPS));
            SplittableRandom random = new SplittableRandom(7);
            for (int mip = 0; mip < ATLAS_MIPS; mip++) {
                int size = ATLAS >> mip;
                ByteBuffer texels = direct(size * size * 4);
                for (int i = 0; i < size * size; i++) {
                    int alpha = switch (random.nextInt(8)) {
                        case 0 -> 0;
                        case 1 -> random.nextInt(256);
                        default -> 255;
                    };
                    texels.put((byte)random.nextInt(256)).put((byte)random.nextInt(256)).put((byte)random.nextInt(256)).put((byte)alpha);
                }

                this.enc.writeToTexture(atlas, texels.flip(), mip, 0, 0, 0, size, size);
            }

            return atlas;
        }

        GpuTexture lightmap() {
            GpuTexture lightmap = this.own(this.device.createTexture((String)"terrain lightmap", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, 16, 16, 1, 1));
            SplittableRandom random = new SplittableRandom(11);
            ByteBuffer texels = direct(16 * 16 * 4);
            for (int i = 0; i < 256; i++) {
                texels.put((byte)(64 + random.nextInt(192))).put((byte)(64 + random.nextInt(192))).put((byte)(64 + random.nextInt(192))).put((byte)255);
            }

            this.enc.writeToTexture(lightmap, texels.flip(), 0, 0, 0, 0, 16, 16);
            return lightmap;
        }

        /**
         * BLOCK vertex format (Position f32x3, Color u8x4, UV0 f32x2, UV2 s16x2 = 28 bytes), section-relative positions.
         * Ground plane y = 0 over x, z in [-80, 80) (camera at y 1.7), and four walls at different angles and distances.
         * Each quad maps one random 16x16 sprite of the atlas, like block faces.
         */
        Geometry geometry() {
            List<float[]> quads = new ArrayList<>();
            for (int x = -80; x < 80; x++) {
                for (int z = -80; z < 80; z++) {
                    quads.add(new float[]{x, 0, z, x, 0, z + 1, x + 1, 0, z + 1, x + 1, 0, z});
                }
            }

            // Walls: facing the camera at z = -12 (8 wide, 6 high), oblique ones at x = +-6, a far one at z = -60.
            for (int x = -4; x < 4; x++) {
                for (int y = 0; y < 6; y++) {
                    quads.add(new float[]{x, y, -12, x + 1, y, -12, x + 1, y + 1, -12, x, y + 1, -12});
                }
            }

            for (int z = -40; z < -4; z++) {
                for (int y = 0; y < 3; y++) {
                    quads.add(new float[]{-6, y, z, -6, y, z + 1, -6, y + 1, z + 1, -6, y + 1, z});
                    quads.add(new float[]{6, y, z + 1, 6, y, z, 6, y + 1, z, 6, y + 1, z + 1});
                }
            }

            for (int x = -40; x < 40; x++) {
                for (int y = 0; y < 10; y++) {
                    quads.add(new float[]{x, y, -60, x + 1, y, -60, x + 1, y + 1, -60, x, y + 1, -60});
                }
            }

            if (this.clusterMode) {
                // Round 12 (a): a ceiling at y = 6 over [-20, 20)^2, once facing up (seen from below: back faces, dropped by the
                // facing test) and once facing down (front faces), so both cluster tests reach every outcome.
                for (int x = -20; x < 20; x++) {
                    for (int z = -20; z < 20; z++) {
                        quads.add(new float[]{x, 6, z, x, 6, z + 1, x + 1, 6, z + 1, x + 1, 6, z});
                        quads.add(new float[]{x, 6, z, x + 1, 6, z, x + 1, 6, z + 1, x, 6, z + 1});
                    }
                }
            }
            if (this.multidraw) {
                return this.multidrawGeometry(quads);
            }
            SplittableRandom random = new SplittableRandom(3);
            int sprites = ATLAS / 16;
            ByteBuffer vb = direct(quads.size() * 4 * 28);
            float[][] corner = {{0, 1}, {0, 0}, {1, 0}, {1, 1}};
            for (float[] q : quads) {
                int sprite = random.nextInt(sprites * sprites);
                float u0 = (sprite % sprites) * 16.0f / ATLAS;
                float v0 = (sprite / sprites) * 16.0f / ATLAS;
                for (int c = 0; c < 4; c++) {
                    int shade = 160 + random.nextInt(96);
                    int rgba = shade << 24 | (shade - random.nextInt(40)) << 16 | (shade - random.nextInt(40)) << 8 | 0xFF;
                    vb.putFloat(q[c * 3]).putFloat(q[c * 3 + 1]).putFloat(q[c * 3 + 2]).putInt(Integer.reverseBytes(rgba))
                        .putFloat(u0 + corner[c][0] * 16.0f / ATLAS).putFloat(v0 + corner[c][1] * 16.0f / ATLAS)
                        .putShort((short)(random.nextInt(16) * 16)).putShort((short)(random.nextInt(16) * 16));
                }
            }

            GpuBuffer vertices = this.own(this.device.createBuffer(() -> "terrain vb", GpuBuffer.USAGE_VERTEX, vb.flip()));
            ByteBuffer ib = direct(quads.size() * 6 * 4);
            for (int q = 0; q < quads.size(); q++) {
                for (int i : new int[]{0, 1, 2, 2, 3, 0}) {
                    ib.putInt(q * 4 + i);
                }
            }

            GpuBuffer indices = this.own(this.device.createBuffer(() -> "terrain ib", GpuBuffer.USAGE_INDEX, ib.flip()));
            return new Geometry(vertices, indices, quads.size());
        }

        /**
         * Round 12: the same quads grouped into 16x16-column "sections" (instances: ChunkPosition = section origin, positions
         * relative to it, visibility 1 / 0.6 / 0.3), every colour byte random (0..255), indices in {@link #pattern}. sections =
         * {first quad, quad count, instance} per section.
         */
        Geometry multidrawGeometry(final List<float[]> quads) {
            java.util.TreeMap<Integer, List<float[]>> bySection = new java.util.TreeMap<>();
            for (float[] q : quads) {
                int sx = Math.floorDiv((int)Math.floor(Math.min(q[0], q[6])), 16);
                int sz = Math.floorDiv((int)Math.floor(Math.min(q[2], q[8])), 16);
                bySection.computeIfAbsent((sx + 64) * 256 + sz + 64, k -> new ArrayList<>()).add(q);
            }
            SplittableRandom random = new SplittableRandom(5);
            int sprites = ATLAS / 16;
            ByteBuffer vb = direct(quads.size() * 4 * 28);
            ByteBuffer inst = direct(bySection.size() * 16);
            int[] sections = new int[bySection.size() * 3];
            float[][] corner = {{0, 1}, {0, 0}, {1, 0}, {1, 1}};
            int s = 0;
            int first = 0;
            for (Map.Entry<Integer, List<float[]>> e : bySection.entrySet()) {
                int ox = (e.getKey() / 256 - 64) * 16;
                int oz = (e.getKey() % 256 - 64) * 16;
                float visibility = s % 3 == 0 ? 1.0f : s % 3 == 1 ? 0.6f : 0.3f;
                inst.putInt(ox).putInt(0).putInt(oz).putFloat(visibility);
                this.origins = java.util.Arrays.copyOf(this.origins, 2 * s + 2);
                this.origins[2 * s] = ox;
                this.origins[2 * s + 1] = oz;
                for (float[] q : e.getValue()) {
                    int sprite = random.nextInt(sprites * sprites);
                    if (this.greedyMode) {
                        // Mergeable: one sprite per 8x8-block region of each plane, one colour and light per region.
                        int rx = Math.floorDiv((int)Math.min(Math.min(q[0], q[3]), q[6]), 8);
                        int ry = Math.floorDiv((int)Math.min(Math.min(q[1], q[4]), q[7]), 8);
                        int rz = Math.floorDiv((int)Math.min(Math.min(q[2], q[5]), q[8]), 8);
                        sprite = Math.floorMod(rx * 73 + ry * 19 + rz * 151, sprites * sprites);
                    }
                    float u0 = (sprite % sprites) * 16.0f / ATLAS;
                    float v0 = (sprite / sprites) * 16.0f / ATLAS;
                    int regionColor = 0xFF000000 | (sprite * 2654435761L > 0 ? (int)(sprite * 2654435761L) : sprite * 7919) & 0x00FFFFFF;
                    short regionLight = (short)(sprite % 15 * 16);
                    for (int c = 0; c < 4; c++) {
                        int alpha = random.nextInt(4) == 0 ? random.nextInt(256) : 255;
                        int rgba = random.nextInt(256) << 24 | random.nextInt(256) << 16 | random.nextInt(256) << 8 | alpha;
                        if (this.greedyMode) {
                            rgba = Integer.rotateLeft(regionColor, 8) | 0xFF;
                        }
                        vb.putFloat(q[c * 3] - ox).putFloat(q[c * 3 + 1]).putFloat(q[c * 3 + 2] - oz).putInt(Integer.reverseBytes(rgba))
                            .putFloat(u0 + corner[c][0] * 16.0f / ATLAS).putFloat(v0 + corner[c][1] * 16.0f / ATLAS)
                            .putShort(this.greedyMode ? regionLight : (short)(random.nextInt(241))).putShort(this.greedyMode ? (short)240 : (short)(random.nextInt(241)));
                    }
                }
                if (this.greedyMode) {
                    ByteBuffer range = vb.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                    range.position(first * 4 * 28).limit((first + e.getValue().size()) * 4 * 28);
                    ByteBuffer section = range.slice().order(ByteOrder.LITTLE_ENDIAN);
                    net.minecraft.client.renderer.chunk.TerrainMeshInfo info = net.minecraft.client.renderer.chunk.TerrainMeshInfo.analyze(section, 4 * e.getValue().size(), 28, 0, true);
                    ByteBuffer g = info == null ? null : net.minecraft.client.renderer.chunk.TerrainGreedy.merge(section, e.getValue().size(), info);
                    if (g == null) {
                        g = section.duplicate();
                    } else {
                        net.minecraft.client.renderer.chunk.TerrainMeshInfo.analyze(g, g.remaining() / 28, 28, 0, true);
                    }
                    this.greedyParts.add(g);
                    this.greedyQuadsIn += e.getValue().size();
                    this.greedyQuadsOut += g.remaining() / 112;
                }
                if (this.clusterMode) {
                    // Layout A (S1 buckets) and the clusters, exactly as SectionCompiler does at mesh time.
                    ByteBuffer range = vb.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                    range.position(first * 4 * 28).limit((first + e.getValue().size()) * 4 * 28);
                    net.minecraft.client.renderer.chunk.TerrainMeshInfo info = net.minecraft.client.renderer.chunk.TerrainMeshInfo.analyze(
                        range.slice().order(ByteOrder.LITTLE_ENDIAN), 4 * e.getValue().size(), 28, 0, true);
                    check(info != null && info.clusters() != null, "cluster test: section " + s + " analyzed with clusters");
                    this.sectionInfos.add(info);
                }
                sections[3 * s] = first;
                sections[3 * s + 1] = e.getValue().size();
                sections[3 * s + 2] = s;
                first += e.getValue().size();
                s++;
            }
            GpuBuffer vertices = this.own(this.device.createBuffer(() -> "terrain vb", GpuBuffer.USAGE_VERTEX, vb.flip()));
            GpuBuffer instances = this.own(this.device.createBuffer(() -> "terrain sections", GpuBuffer.USAGE_VERTEX, inst.flip()));
            if (this.greedyMode) {
                int total = 0;
                for (ByteBuffer g : this.greedyParts) {
                    total += g.remaining();
                }
                ByteBuffer gvb = direct(total);
                int[] gsec = new int[sections.length];
                int gfirst = 0;
                int maxG = 0;
                for (int i = 0; i < this.greedyParts.size(); i++) {
                    ByteBuffer g = this.greedyParts.get(i);
                    int n = g.remaining() / 112;
                    gvb.put(g.duplicate());
                    gsec[3 * i] = gfirst;
                    gsec[3 * i + 1] = n;
                    gsec[3 * i + 2] = i;
                    gfirst += n;
                    maxG = Math.max(maxG, n);
                }
                GpuBuffer gvertices = this.own(this.device.createBuffer(() -> "terrain greedy vb", GpuBuffer.USAGE_VERTEX, gvb.flip()));
                ByteBuffer gib = direct(Math.max(1, maxG) * 6 * 4);
                for (int q = 0; q < maxG; q++) {
                    for (int i : this.pattern) {
                        gib.putInt(q * 4 + i);
                    }
                }
                GpuBuffer gindices = this.own(this.device.createBuffer(() -> "terrain greedy ib", GpuBuffer.USAGE_INDEX, gib.flip()));
                this.greedyGeometry = new Geometry(gvertices, gindices, gfirst, instances, gsec);
            }
            int maxQuads = 0;
            for (int i = 0; i < s; i++) {
                maxQuads = Math.max(maxQuads, sections[3 * i + 1]);
            }
            ByteBuffer ib = direct(maxQuads * 6 * 4);
            for (int q = 0; q < maxQuads; q++) {
                for (int i : this.pattern) {
                    ib.putInt(q * 4 + i);
                }
            }
            GpuBuffer indices = this.own(this.device.createBuffer(() -> "terrain ib", GpuBuffer.USAGE_INDEX, ib.flip()));
            return new Geometry(vertices, indices, quads.size(), instances, sections);
        }

        void render(
            final CompiledRenderPipeline pipeline,
            final boolean rgss,
            final GpuTexture color,
            final GpuTexture depth,
            final GpuTexture atlas,
            final GpuSampler atlasSampler,
            final GpuTexture lightmap,
            final GpuSampler lightSampler,
            final Geometry geometry
        ) {
            // Globals: camera block (0, 0, 0), offset (-0.3, -1.7, -0.6) (camera at (0.3, 1.7, 0.6)).
            ByteBuffer globals = direct(48);
            globals.putInt(0, 0).putInt(4, 0).putInt(8, 0).putFloat(12, 1.0f);
            globals.putFloat(16, -0.3f).putFloat(20, -1.7f).putFloat(24, -0.6f).putFloat(28, 0.25f);
            globals.putFloat(32, W).putFloat(36, H).putInt(40, 0).putInt(44, rgss ? 1 : 0);
            ByteBuffer fog = direct(48);
            fog.putFloat(0, 0.6f).putFloat(4, 0.7f).putFloat(8, 0.9f).putFloat(12, 1.0f);
            fog.putFloat(16, 30.0f).putFloat(20, 90.0f).putFloat(24, 50.0f).putFloat(28, 80.0f).putFloat(32, 100.0f).putFloat(36, 100.0f);
            ByteBuffer chunk = direct(16);
            chunk.putInt(0, 0).putInt(4, 0).putInt(8, 0).putFloat(12, 0.6f);   // ChunkPosition, ChunkVisibility (fade)
            ByteBuffer terrain = direct(80);
            Matrix4f modelView = new Matrix4f().rotateX((float)Math.toRadians(14)).rotateY((float)Math.toRadians(-8));
            modelView.get(0, terrain);
            terrain.putInt(64, ATLAS).putInt(68, ATLAS);
            // Reverse-Z perspective (depth 1 at the near plane, 0 at the far plane), zZeroToOne, like the game.
            Matrix4f projection = new Matrix4f().perspective((float)Math.toRadians(70), (float)W / H, 1000.0f, 0.05f, true);
            ByteBuffer proj = direct(64);
            projection.get(0, proj);

            TerrainShaderVariants.setFrameUsesRgss(rgss);   // what GameRenderer does next to the Globals upload
            try (RenderPass p = this.enc.createRenderPass(() -> "terrain", this.own(this.device.createTextureView(color)),
                Optional.of(new Vector4f(0, 0, 0, 0)), this.own(this.device.createTextureView(depth)), OptionalDouble.of(0.0))) {
                p.setPipeline(pipeline);
                p.setUniform("Projection", this.ubo(proj));
                p.setUniform("Globals", this.ubo(globals));
                p.setUniform("Fog", this.ubo(fog));
                p.setUniform("ChunkSection", this.ubo(chunk));
                p.setUniform("TerrainUniform", this.ubo(terrain));
                p.setUniform("Sampler0", this.own(this.device.createTextureView(atlas)), atlasSampler);
                p.setUniform("Sampler2", this.own(this.device.createTextureView(lightmap)), lightSampler);
                p.setVertexBuffer(0, geometry.vertices().slice());
                p.setIndexBuffer(geometry.indices(), IndexType.INT);
                if (this.clusterLayer >= 0) {
                    for (net.minecraft.client.renderer.chunk.TerrainClusters.Draw draw : this.cull(geometry, modelView, projection)) {
                        p.setVertexBuffer(0, draw.vertexBuffer());
                        p.setVertexBuffer(1, geometry.instances().slice());
                        p.setVertexBuffer(2, draw.quads());
                        p.setVertexBuffer(3, draw.items());
                        p.setIndexBuffer(draw.indexBuffer(), IndexType.INT);
                        p.drawIndexedIndirect(draw.args(), 1);
                    }
                } else if (geometry.sections() != null) {
                    // One draw per section: base vertex = its first vertex, base instance = its section info.
                    p.setVertexBuffer(1, geometry.instances().slice());
                    int[] sec = geometry.sections();
                    for (int i = 0; i < sec.length; i += 3) {
                        p.drawIndexed(sec[i + 1] * 6, 1, 0, sec[i] * 4, sec[i + 2]);
                    }
                } else {
                    p.drawIndexed(geometry.quads() * 6, 1, 0, 0, 0);
                }
            }
        }

        /**
         * Round 12 (a): the cluster list of every section (all buckets: the GPU does the facing test), culled for this view by
         * TerrainClusters.Frame (the game's code path: CPU-mapped ring, mcmtl_ext_cluster_cull on a side command buffer).
         */
        List<net.minecraft.client.renderer.chunk.TerrainClusters.Draw> cull(final Geometry geometry, final Matrix4f modelView, final Matrix4f projection) {
            if (this.clusterFrame == null) {
                this.clusterFrame = new net.minecraft.client.renderer.chunk.TerrainClusters.Frame();
                check(this.clusterFrame.usable(), "cluster test: cluster path usable (Metal, extension, both cluster pipelines with the pulling vertex function)");
            }
            // terrainShaderTest.clusterPerSection: one cluster draw per section (diagnostics: draw grouping).
            boolean perSection = Boolean.getBoolean("terrainShaderTest.clusterPerSection");
            List<net.minecraft.client.renderer.chunk.TerrainClusters.Group> groups = new ArrayList<>();
            int[] sec = geometry.sections();
            int n = sec.length / 3;
            int[] sectionData = new int[4 * n];
            long quads = 0;
            net.minecraft.client.renderer.chunk.TerrainClusters.Group group = null;
            for (int i = 0; i < n; i++) {
                if (group == null || perSection) {
                    group = new net.minecraft.client.renderer.chunk.TerrainClusters.Group();
                    group.reset(this.clusterLayer, geometry.vertices());
                    groups.add(group);
                }
                quads += group.add(this.sectionInfos.get(i).clusters(), 0x7F, sec[3 * i] * 4, sec[3 * i + 2]);
                sectionData[4 * i] = this.origins[2 * i];
                sectionData[4 * i + 2] = this.origins[2 * i + 1];
            }
            net.minecraft.client.renderer.chunk.TerrainFrustum frustum = net.minecraft.client.renderer.chunk.TerrainFrustum.of(projection, modelView);
            double cx = 0.3;
            double cy = 1.7;
            double cz = 0.6;
            List<net.minecraft.client.renderer.chunk.TerrainClusters.Draw> draws = this.clusterFrame.dispatch(groups, n, sectionData, frustum.planesCopy(), true,
                cx, cy, cz, cx + frustum.eyeX(), cy + frustum.eyeY(), cz + frustum.eyeZ());
            check(draws != null && !draws.isEmpty(), "cluster test: cluster draws");
            this.submitted[this.clusterLayer] = quads;
            return draws;
        }

        GpuBufferSlice ubo(final ByteBuffer data) {
            return this.enc.transientMemory().uploadGpu(data, this.device.getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM);
        }

        byte[] readback(final GpuTexture texture) {
            int w = texture.getWidth(0);
            int h = texture.getHeight(0);
            int bs = 4;
            GpuBuffer buffer = this.device.createBuffer(() -> "terrain readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long)w * h * bs);
            this.enc.copyTextureToBuffer(texture, buffer, 0, () -> {}, 0);
            GpuFence fence = this.enc.createFence();
            this.enc.submit();
            check(fence.awaitCompletion(10_000_000_000L), "readback fence completes");
            fence.close();
            byte[] out = new byte[w * h * bs];
            try (GpuBufferSlice.MappedView view = buffer.slice(0, (long)w * h * bs).map(true, false)) {
                view.data().get(out);
            }

            buffer.close();
            return out;
        }

        @Override
        public void close() {
            for (int i = this.owned.size() - 1; i >= 0; i--) {
                try {
                    this.owned.get(i).close();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            this.shaders.close();
            this.workers.shutdown();
        }
    }

    static ByteBuffer direct(final int size) {
        return ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static ShaderSource jarShaderSource() {
        Map<Identifier, ShaderSource.CachedIncludeSource> includes = new ConcurrentHashMap<>();
        return new ShaderSource() {
            @Override
            public String getShader(final Identifier id, final ShaderType type) {
                return resource("assets/" + id.getNamespace() + "/shaders/" + id.getPath() + (type == ShaderType.VERTEX ? ".vsh" : ".fsh"));
            }

            @Override
            public CachedIncludeSource getInclude(final Identifier id) {
                return includes.computeIfAbsent(id, key -> {
                    String source = resource("assets/" + key.getNamespace() + "/shaders/include/" + key.getPath());
                    return source == null ? null : CachedIncludeSource.create(key, source);
                });
            }

            @Override
            public void close() {
                includes.values().forEach(CachedIncludeSource::close);
                includes.clear();
            }
        };
    }

    private static String resource(final String path) {
        try (InputStream in = TerrainShaderTest.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
