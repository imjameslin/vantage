import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.metal.MetalBackend;
import com.mojang.renderpearl.backend.metal.MetalTickFx;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.LightmapCompute;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.client.renderer.texture.TexturePingPong;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

/**
 * R7-1 (docs/metal/CONTRACT.md 16.R7-1): the lightmap kernel (mcmtl_ext_lightmap_compute) against vanilla's render pass
 * ("Update light", lightmap.fsh through the game's own pipeline) on the real Metal device, byte for byte.
 *
 * <p>For each parameter set the patched Lightmap runs one update with {@code LightmapCompute.enabled = false} (render pass)
 * and one with {@code true} (kernel), each followed by a readback of the copy frames then sample; the 1024 bytes must be
 * identical. Parameter sets: edge values (0, 1, the extractor's ranges' ends), game-like values (blockFactor 1.4 + flicker,
 * sky factors, the overworld / nether / end ambient colours, night vision, darkness, boss darkening) and uniform random
 * floats over [0, 2] (also outside the game's ranges). Also checks: the kernel ran (dispatch counter), both copies are
 * kernel-writable, no device messages (run under MTL_DEBUG_LAYER=1 by tools/lightmap-compute-test.sh), and a final
 * mixed sequence (kernel and render pass interleaved at random, frames sampling in between) digests the same as a
 * render-pass-only sequence.
 */
public final class LightmapComputeTest {
    private static int failures;
    private static long checks;

    public static void main(final String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        int randomSets = Integer.getInteger("lightmap.sets", 4000);
        MetalBackend backend = new MetalBackend();
        backend.loadLibrary();
        GpuDevice device = backend.createDevice(new GpuDebugOptions(0, false, false, true));
        Field deviceField = RenderSystem.class.getDeclaredField("DEVICE");
        deviceField.setAccessible(true);
        deviceField.set(null, device);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            RenderSystem.initRenderThread();
            RenderSystem.getSamplerCache().initialize();
            ShaderSource shaders = jarShaderSource();
            PipelineCache cache = new PipelineCache(device, shaders);
            CompiledRenderPipeline compiled = device.compilePipeline(RenderPipelines.LIGHTMAP, shaders, workers).join().finishCompile();
            check(compiled != null, "lightmap pipeline compiles");
            cache.insert(RenderPipelines.LIGHTMAP, compiled);
            RenderSystem.setFallbackPipelineCache(cache);
            TexturePingPong.enabled = true;

            Lightmap lightmap = new Lightmap();
            CommandEncoder enc = device.createCommandEncoder();
            check(MetalTickFx.isWritable(lightmap.getTextureView().texture()), "copy A kernel-writable");
            LightmapRenderState state = new LightmapRenderState();
            state.needsUpdate = true;
            SplittableRandom r = new SplittableRandom(1234);
            int sets = 0;
            int mismatched = 0;
            int distinct = 0;
            java.util.Set<String> seen = new java.util.HashSet<>();
            long before = LightmapCompute.dispatches;
            int total = 64 + 600 + randomSets;
            for (int i = 0; i < total; i++) {
                fill(state, r, i);
                byte[] pass = update(device, enc, lightmap, state, false);
                byte[] kernel = update(device, enc, lightmap, state, true);
                sets++;
                checks++;
                if (!java.util.Arrays.equals(pass, kernel)) {
                    mismatched++;
                    if (mismatched <= 10) {
                        int first = -1;
                        int count = 0;
                        int maxDiff = 0;
                        for (int b = 0; b < pass.length; b++) {
                            if (pass[b] != kernel[b]) {
                                count++;
                                if (first < 0) first = b;
                                maxDiff = Math.max(maxDiff, Math.abs((pass[b] & 255) - (kernel[b] & 255)));
                            }
                        }
                        System.out.printf("MISMATCH set %d: %d bytes differ (first texel %d channel %d: pass %d kernel %d), max diff %d; %s%n",
                            i, count, first / 4, first % 4, pass[first] & 255, kernel[first] & 255, maxDiff, describe(state));
                    }
                }
                if (seen.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pass)))) distinct++;
            }
            long ran = LightmapCompute.dispatches - before;
            check(ran == sets, "kernel ran for every set (" + ran + " of " + sets + ")");
            check(MetalTickFx.isWritable(lightmap.getTextureView().texture()), "copy B kernel-writable");
            check(mismatched == 0, mismatched + " of " + sets + " parameter sets differ");
            System.out.println("LIGHTMAP COMPUTE PARITY: " + sets + " parameter sets, " + (sets - mismatched) + " byte-identical, "
                + distinct + " distinct lightmaps");

            // Mixed sequence: kernel and render pass interleaved at random (as a runtime toggle flip would), with the
            // ping-pong toggle also flipped (in-place updates always take the render pass), digest of every frame's copy.
            String a = sequence(device, enc, false);
            String b = sequence(device, enc, true);
            check(a.equals(b), "mixed sequence digests equal the render-pass-only sequence");
            System.out.println("LIGHTMAP COMPUTE SEQUENCE: " + (a.equals(b) ? "identical" : "DIFFERENT"));
            lightmap.close();

            java.util.List<String> messages = device.getLastDebugMessages();
            for (String m : messages) {
                System.out.println("  device message: " + m);
            }
            check(messages.isEmpty(), "no device messages");
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            check(false, "threw " + t);
        } finally {
            workers.shutdownNow();
        }
        System.out.println(failures == 0 ? "LIGHTMAP COMPUTE PASSED (" + checks + " checks)" : "LIGHTMAP COMPUTE FAILED (" + failures + ")");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String sequence(final GpuDevice device, final CommandEncoder enc, final boolean mixed) throws Exception {
        Lightmap lightmap = new Lightmap();
        SplittableRandom r = new SplittableRandom(99);
        SplittableRandom m = new SplittableRandom(7);
        MessageDigest all = MessageDigest.getInstance("SHA-256");
        LightmapRenderState state = new LightmapRenderState();
        long before = LightmapCompute.dispatches;
        for (int frame = 0; frame < 300; frame++) {
            fill(state, r, 64 + frame);
            state.needsUpdate = r.nextInt(3) != 0;
            boolean pingPong = r.nextInt(6) != 0;
            TexturePingPong.enabled = pingPong;
            LightmapCompute.enabled = mixed && m.nextBoolean();
            lightmap.notePendingUpdate(state.needsUpdate);
            lightmap.render(state);
            all.update(readback(device, enc, lightmap.getTextureView().texture()));
        }
        TexturePingPong.enabled = true;
        LightmapCompute.enabled = true;
        System.out.println("sequence " + (mixed ? "mixed" : "render pass only") + ": kernel updates " + (LightmapCompute.dispatches - before));
        lightmap.close();
        return HexFormat.of().formatHex(all.digest());
    }

    private static byte[] update(final GpuDevice device, final CommandEncoder enc, final Lightmap lightmap, final LightmapRenderState state,
        final boolean kernel) throws Exception {
        LightmapCompute.enabled = kernel;
        lightmap.notePendingUpdate(true);
        lightmap.render(state);
        return readback(device, enc, lightmap.getTextureView().texture());
    }

    private static byte[] readback(final GpuDevice device, final CommandEncoder enc, final GpuTexture texture) throws Exception {
        allowCopySource(texture);
        GpuBuffer buffer = device.createBuffer(() -> "lightmap readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 16 * 16 * 4);
        enc.copyTextureToBuffer(texture, buffer, 0, () -> {}, 0);
        GpuFence fence = enc.createFence();
        enc.submit();
        check(fence.awaitCompletion(5_000_000_000L), "readback fence");
        fence.close();
        byte[] data = new byte[16 * 16 * 4];
        try (GpuBufferSlice.MappedView view = buffer.slice(0, data.length).map(true, false)) {
            view.data().get(data);
        }
        buffer.close();
        return data;
    }

    private static final float[][] AMBIENT = {
        {0.0F, 0.0F, 0.0F}, {0.04F, 0.04F, 0.04F}, {0.13F, 0.1F, 0.1F}, {0.25F, 0.22F, 0.3F}, {1.0F, 1.0F, 1.0F}
    };

    /** Set i: 0-63 edge grid, 64-663 game-like, then uniform random over [0, 2]. */
    private static void fill(final LightmapRenderState s, final SplittableRandom r, final int i) {
        if (i < 64) {
            float[] edge = {0.0F, 1.0F, 0.5F, 1.5F};
            s.skyFactor = edge[i & 3];
            s.blockFactor = edge[(i >> 2) & 3];
            s.brightness = (i & 16) != 0 ? 1.0F : 0.0F;
            s.darknessEffectScale = 0.0F;
            s.nightVisionEffectIntensity = (i & 32) != 0 ? 1.0F : 0.0F;
            s.bossOverlayWorldDarkening = 0.0F;
            s.blockLightTint = new Vector3f(1.0F, 0.85F, 0.7F);
            s.skyLightColor = new Vector3f(1.0F, 1.0F, 1.0F);
            float[] amb = AMBIENT[i % AMBIENT.length];
            s.ambientColor = new Vector3f(amb[0], amb[1], amb[2]);
            s.nightVisionColor = new Vector3f(1.0F, 1.0F, 1.0F);
        } else if (i < 664) {
            s.blockFactor = 1.4F + (float)(r.nextDouble() * 0.2 - 0.1);
            s.skyFactor = r.nextInt(4) == 0 ? (float)r.nextInt(2) : r.nextFloat();
            s.brightness = r.nextInt(3) == 0 ? 0.5F : r.nextFloat();
            s.darknessEffectScale = r.nextInt(5) == 0 ? r.nextFloat() * 0.7F : 0.0F;
            s.nightVisionEffectIntensity = r.nextInt(5) == 0 ? r.nextFloat() : 0.0F;
            s.bossOverlayWorldDarkening = r.nextInt(6) == 0 ? r.nextFloat() : 0.0F;
            s.blockLightTint = new Vector3f(1.0F, 0.6F + r.nextFloat() * 0.4F, 0.4F + r.nextFloat() * 0.6F);
            s.skyLightColor = new Vector3f(r.nextFloat(), r.nextFloat(), 1.0F);
            float[] amb = AMBIENT[r.nextInt(AMBIENT.length)];
            s.ambientColor = new Vector3f(amb[0], amb[1], amb[2]);
            s.nightVisionColor = new Vector3f(r.nextFloat(), r.nextFloat(), r.nextFloat());
        } else {
            s.blockFactor = r.nextFloat() * 2.0F;
            s.skyFactor = r.nextFloat() * 2.0F;
            s.brightness = r.nextFloat();
            s.darknessEffectScale = r.nextFloat();
            s.nightVisionEffectIntensity = r.nextFloat();
            s.bossOverlayWorldDarkening = r.nextFloat();
            s.blockLightTint = new Vector3f(r.nextFloat() * 2.0F, r.nextFloat() * 2.0F, r.nextFloat() * 2.0F);
            s.skyLightColor = new Vector3f(r.nextFloat() * 2.0F, r.nextFloat() * 2.0F, r.nextFloat() * 2.0F);
            s.ambientColor = new Vector3f(r.nextFloat(), r.nextFloat(), r.nextFloat());
            s.nightVisionColor = new Vector3f(r.nextFloat() * 2.0F, r.nextFloat() * 2.0F, r.nextFloat() * 2.0F);
        }
    }

    private static String describe(final LightmapRenderState s) {
        return String.format("sky %s block %s bright %s dark %s nv %s boss %s tint %s sky %s amb %s nvc %s", s.skyFactor, s.blockFactor,
            s.brightness, s.darknessEffectScale, s.nightVisionEffectIntensity, s.bossOverlayWorldDarkening, s.blockLightTint,
            s.skyLightColor, s.ambientColor, s.nightVisionColor);
    }

    private static void allowCopySource(final GpuTexture texture) throws ReflectiveOperationException {
        for (Class<?> c = texture.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("usage");
                f.setAccessible(true);
                f.setInt(texture, f.getInt(texture) | GpuTexture.USAGE_COPY_SRC);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException("usage");
    }

    private static ShaderSource jarShaderSource() {
        java.util.Map<Identifier, ShaderSource.CachedIncludeSource> includes = new java.util.concurrent.ConcurrentHashMap<>();
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
        try (InputStream in = LightmapComputeTest.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(path, e);
        }
    }

    private static void check(final boolean ok, final String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
