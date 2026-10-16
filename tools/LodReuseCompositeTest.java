import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.metal.MetalBackend;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

/** Bounded headless test of the production LOD_REUSE_COMPOSITE pipeline and jar shaders.
 * Compares every RGBA8 and D32 byte, including alpha, against a CPU oracle and a direct
 * redraw. Does not enable or exercise LodReuse's cache policy or require a game/window.
 * Run tools/lod-reuse-composite-test.sh after building; the caller owns GPU serialization.
 */
public final class LodReuseCompositeTest {
    private static final int W = 7, H = 5, BYTES = W * H * 4;
    private static final int USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
        | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
    private static final Identifier REDRAW = Identifier.withDefaultNamespace("test/lod_reuse_redraw");
    private static int checks;

    // Independent reference: a constant fragment per 1x1 scissor, with no texture fetch
    // or gl_FragCoord indexing. Also renders a fresh cache to test attachment -> sampling ordering.
    private static final String REDRAW_FRAGMENT = """
        #version 330
        #extension GL_ARB_separate_shader_objects : require
        layout(std140) uniform TestPixel { vec4 Color; float Depth; };
        layout(location = 0) out vec4 fragColor;
        void main() { fragColor = Color; gl_FragDepth = Depth; }
        """;

    public static void main(final String[] args) throws Exception {
        require(args.length == 0, "no Java arguments (the runner accepts JVM options)");
        MetalBackend backend = new MetalBackend();
        backend.loadLibrary();
        GpuDevice device = backend.createDevice(new GpuDebugOptions(0, false, true, true));
        Field field = RenderSystem.class.getDeclaredField("DEVICE");
        field.setAccessible(true);
        Object previous = field.get(null);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (ShaderSource shaders = jarShaderSource(); Rig rig = new Rig(device)) {
            field.set(null, device); // GlslCompiler reads RenderSystem.getDevice().getDeviceInfo().
            RenderPipeline production = RenderPipelines.LOD_REUSE_COMPOSITE;
            require(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true).equals(production.getDepthStencilState()),
                "production must use unbiased GEQUAL with depth writes");
            require(production.getColorTargetStates().equals(List.of(ColorTargetState.DEFAULT)),
                "production must write all RGBA8 channels without blending");
            rig.composite = rig.own(compile(device, production, shaders, workers));
            RenderPipeline redraw = RenderPipeline.builder().withLocation("test/lod_reuse_redraw")
                .withVertexShader("core/screenquad").withFragmentShader(REDRAW)
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("TestPixel", UniformType.UNIFORM_BUFFER).build())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true)).build();
            rig.redraw = rig.own(compile(device, redraw, shaders, workers));
            rig.run();
            require(device.getLastDebugMessages().isEmpty(), "device messages: " + device.getLastDebugMessages());
        } finally {
            workers.shutdownNow();
            try { device.close(); } finally { field.set(null, previous); }
        }
        System.out.println("LOD REUSE COMPOSITE PASSED: " + checks + " checks (RGBA8 + D32 byte-exact)");
    }

    private static CompiledRenderPipeline compile(final GpuDevice device, final RenderPipeline pipeline,
            final ShaderSource source, final ExecutorService workers) throws Exception {
        CompiledRenderPipeline result = device.compilePipeline(pipeline, source, workers).get(20, TimeUnit.SECONDS).finishCompile();
        require(result != null, "compile failed: " + pipeline.getLocation());
        return result;
    }

    private record Frame(byte[] color, byte[] depth) {
        float depthAt(final int pixel) { return ByteBuffer.wrap(depth).order(ByteOrder.LITTLE_ENDIAN).getFloat(pixel * 4); }
    }

    private record Target(GpuTexture color, GpuTexture depth, GpuTextureView cv, GpuTextureView dv) {}

    /** Unequal rows/corners, adjacent contrasting texels, and alpha 0/53/128/255.
     * Eight cases: empty/clear, empty/covered, covered/clear, near wins, exact tie,
     * cached nearer, near wins by one D32 ULP, cached wins by one D32 ULP.
     * Generation 1 changes color, depth AND coverage, including newly empty pixels.
     */
    private static Frame fixture(final int generation, final boolean cached) {
        byte[] color = new byte[BYTES];
        ByteBuffer depth = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
        int salt = cached ? 19 : 113;
        int[] alpha = {0, 53, 128, 255};
        for (int p = 0; p < W * H; p++) {
            int x = p % W, y = p / W, kind = (p + generation * 3) % 8;
            color[4 * p] = (byte)(37 * p + 67 * generation + salt);
            color[4 * p + 1] = (byte)(91 * x + 43 * y + 29 * generation + salt);
            color[4 * p + 2] = (byte)(13 * x + 71 * y + 97 * generation + salt);
            color[4 * p + 3] = (byte)alpha[(p + 2 * generation + (cached ? 0 : 1)) % 4];
            // One ULP above multiples of 1/64 exposes a lossy depth copy.
            float d = Math.nextUp((1 + (p * 7 + generation * 11) % 53) / 64.0f);
            depth.putFloat(cached ? (kind < 2 ? 0.0f : d) : switch (kind) {
                case 0, 2 -> 0.0f;
                case 1, 3 -> 0.9375f;
                case 4 -> d;
                case 5 -> d * 0.5f;
                case 6 -> Math.nextUp(d);
                default -> Math.nextDown(d);
            });
        }
        return new Frame(color, depth.array());
    }

    private static Frame expected(final Frame cached, final Frame main) {
        Frame out = new Frame(main.color.clone(), main.depth.clone());
        for (int p = 0; p < W * H; p++) {
            float d = cached.depthAt(p);
            if (d > 0.0f && d >= main.depthAt(p)) {
                System.arraycopy(cached.color, p * 4, out.color, p * 4, 4);
                System.arraycopy(cached.depth, p * 4, out.depth, p * 4, 4);
            }
        }
        return out;
    }

    private static final class Rig implements AutoCloseable {
        final GpuDevice device;
        final CommandEncoder enc;
        final List<AutoCloseable> owned = new ArrayList<>();
        CompiledRenderPipeline composite, redraw;

        Rig(final GpuDevice device) { this.device = device; enc = device.createCommandEncoder(); }

        <T extends AutoCloseable> T own(final T resource) { owned.add(resource); return resource; }

        Target target(final String name) {
            return target(name, W, H);
        }

        Target target(final String name, int width, int height) {
            GpuTexture c = own(device.createTexture(name + " color", USAGE, GpuFormat.RGBA8_UNORM, width, height, 1, 1));
            GpuTexture d = own(device.createTexture(name + " depth", USAGE, GpuFormat.D32_FLOAT, width, height, 1, 1));
            return new Target(c, d, own(device.createTextureView(c)), own(device.createTextureView(d)));
        }

        void upload(final Target target, final Frame frame) {
            enc.writeToTexture(target.color, direct(BYTES).put(frame.color).flip(), 0, 0, 0, 0, W, H);
            enc.writeToTexture(target.depth, direct(BYTES).put(frame.depth).flip(), 0, 0, 0, 0, W, H);
        }

        void composite(final Target source, final Target destination, final GpuSampler sampler) {
            try (RenderPass pass = enc.createRenderPass(() -> "production LOD reuse", destination.cv, Optional.empty(),
                    destination.dv, OptionalDouble.empty())) {
                pass.setPipeline(composite);
                pass.setUniform("ReuseColor", source.cv, sampler);
                pass.setUniform("ReuseDepth", source.dv, sampler);
                pass.draw(3, 1, 0, 0);
            }
        }

        void redraw(final Target destination, final Frame frame) {
            try (RenderPass pass = enc.createRenderPass(() -> "direct pixel redraw", destination.cv, Optional.empty(),
                    destination.dv, OptionalDouble.empty())) {
                pass.setPipeline(redraw);
                for (int p = 0; p < W * H; p++) {
                    if (frame.depthAt(p) == 0.0f) continue;
                    ByteBuffer pixel = direct(32); // std140 vec4 + float, rounded to 16-byte alignment
                    for (int channel = 0; channel < 4; channel++) pixel.putFloat(channel * 4, (frame.color[p * 4 + channel] & 255) / 255.0f);
                    pixel.putFloat(16, frame.depthAt(p));
                    pass.setUniform("TestPixel", enc.transientMemory().uploadGpu(pixel,
                        device.getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM));
                    pass.enableScissor(p % W, p / W, 1, 1);
                    pass.draw(3, 1, 0, 0);
                }
            }
        }

        Frame read(final Target target) {
            try (GpuBuffer buffer = device.createBuffer(() -> "RGBA8 + D32 readback",
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, BYTES * 2L)) {
                boolean[] done = new boolean[2];
                enc.copyTextureToBuffer(target.color, buffer, 0, () -> done[0] = true, 0);
                enc.copyTextureToBuffer(target.depth, buffer, BYTES, () -> done[1] = true, 0);
                try (GpuFence fence = enc.createFence()) {
                    enc.submit();
                    require(fence.awaitCompletion(5_000_000_000L), "readback fence timed out");
                }
                require(done[0] && done[1], "both readback callbacks completed");
                Frame result = new Frame(new byte[BYTES], new byte[BYTES]);
                try (GpuBufferSlice.MappedView mapped = buffer.slice().map(true, false)) {
                    mapped.data().get(result.color).get(result.depth);
                }
                return result;
            }
        }

        void run() {
            Target cache = target("cache"), a = target("nearest / old"), b = target("linear / refreshed"), ref = target("reference");
            GpuSampler nearest = own(device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty()));
            GpuSampler linear = own(device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty()));
            Frame cached0 = fixture(0, true), main0 = fixture(0, false), cached1 = fixture(1, true), main1 = fixture(1, false);
            Frame clear = new Frame(main0.color, new byte[BYTES]);
            upload(cache, cached0);
            upload(a, clear);
            upload(b, clear);
            composite(cache, a, nearest);
            composite(cache, b, linear); // texelFetch must be independent of filtering state.
            compare(expected(cached0, clear), read(a), "same-pixel copy, nearest, including transparent colors");
            compare(expected(cached0, clear), read(b), "same-pixel copy, linear sampler, no filtering");

            upload(a, main0);
            upload(ref, main0);
            composite(cache, a, nearest);
            redraw(ref, cached0);
            Frame mixed = read(a), direct0 = read(ref), oracle0 = expected(cached0, main0);
            compare(oracle0, mixed, "GEQUAL: near wins, equal cached wins, one-ULP decisions, empty discard");
            compare(oracle0, direct0, "independent direct redraw");
            compare(direct0, mixed, "composite vs direct redraw");

            // All operations below are recorded before any submit/wait: sample old cache,
            // clear and redraw BOTH attachments in place, sample the fresh cache, and draw reference.
            upload(a, main0);
            composite(cache, a, nearest);
            enc.clearColorAndDepthTextures(cache.color, new Vector4f(1, 0, 1, 0), cache.depth, 0.0);
            redraw(cache, cached1);
            upload(b, main1);
            upload(ref, main1);
            composite(cache, b, nearest);
            redraw(ref, cached1);
            Frame old = read(a), refreshed = read(b), direct1 = read(ref), oracle1 = expected(cached1, main1);
            require(!Arrays.equals(oracle0.color, oracle1.color) && !Arrays.equals(oracle0.depth, oracle1.depth),
                "refresh must change both expected color and depth");
            compare(oracle0, old, "earlier draw retains old cache contents");
            compare(oracle1, refreshed, "same-frame fresh color/depth and changed coverage");
            compare(oracle1, direct1, "fresh direct redraw reference");
            compare(direct1, refreshed, "same-frame composite vs fresh redraw");

            // Poison color remains in a wholly empty cache. In particular depth 0 == 0
            // would pass GEQUAL without the production shader's discard.
            enc.clearColorAndDepthTextures(cache.color, new Vector4f(1, 0, 1, 0), cache.depth, 0.0);
            upload(a, main1);
            composite(cache, a, nearest);
            compare(main1, read(a), "all-empty cache preserves every main color/depth byte");

            // Small padded source -> full cache, with odd tile boundaries and repeated source reuse.
            // No submit between uploads/copies/composite: verifies their GPU ordering and exact D32 copies.
            // This tests copy/composite behavior; cropped terrain projection is checked by the in-game gate.
            int sw = ((W + 3) / 4 + 3) & ~1, sh = ((H + 3) / 4 + 3) & ~1;
            Target scratch = target("padded tile scratch", sw, sh);
            for (Frame fresh : List.of(cached0, cached1)) {
                for (int ty = 0; ty < 4; ty++) for (int tx = 0; tx < 4; tx++) {
                    int x = tx * W / 4, y = ty * H / 4;
                    int width = (tx + 1) * W / 4 - x, height = (ty + 1) * H / 4 - y;
                    int left = x & ~1, top = y & ~1;
                    ByteBuffer colors = direct(sw * sh * 4), depths = direct(sw * sh * 4);
                    for (int sy = 0; sy < sh; sy++) for (int sx = 0; sx < sw; sx++) {
                        int gx = left + sx, gy = top + sy, at = 4 * (sy * sw + sx);
                        if (gx < W && gy < H) for (int b0 = 0; b0 < 4; b0++) {
                            colors.put(at + b0, fresh.color[4 * (gy * W + gx) + b0]);
                            depths.put(at + b0, fresh.depth[4 * (gy * W + gx) + b0]);
                        }
                    }
                    enc.writeToTexture(scratch.color, colors, 0, 0, 0, 0, sw, sh);
                    enc.writeToTexture(scratch.depth, depths, 0, 0, 0, 0, sw, sh);
                    enc.copyTextureToTexture(scratch.color, cache.color, 0, x, y, x - left, y - top, width, height);
                    enc.copyTextureToTexture(scratch.depth, cache.depth, 0, x, y, x - left, y - top, width, height);
                }
                compare(fresh, read(cache), "padded tile copies preserve complete RGBA8/D32 image");
                upload(a, main1);
                composite(cache, a, nearest);
                compare(expected(fresh, main1), read(a), "tiled cache composite preserves near and equal-depth ordering");
            }
        }

        @Override public void close() throws Exception {
            Exception failure = null;
            for (int i = owned.size() - 1; i >= 0; i--) {
                try { owned.get(i).close(); } catch (Exception e) {
                    if (failure == null) failure = e; else failure.addSuppressed(e);
                }
            }
            if (failure != null) throw failure;
        }
    }

    private static ByteBuffer direct(final int bytes) { return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN); }

    private static void require(final boolean condition, final String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void compare(final Frame expected, final Frame actual, final String label) {
        compareBytes(expected.color, actual.color, label + " RGBA8");
        compareBytes(expected.depth, actual.depth, label + " D32");
        System.out.println("  PASS " + label);
    }

    private static void compareBytes(final byte[] expected, final byte[] actual, final String label) {
        int i = Arrays.mismatch(expected, actual);
        require(i == -1, i == -1 ? label : String.format(
            "%s: (%d, row %d), byte %d: expected %02x, got %02x", label, (i / 4) % W, i / (4 * W), i % 4,
            expected[i] & 255, actual[i] & 255));
    }

    // Same ShaderSource/CachedIncludeSource contract as TerrainShaderTest and MetalE2ETest.
    // Production source is read from the built jar, never a reimplementation of lod_reuse.fsh.
    private static ShaderSource jarShaderSource() {
        Map<Identifier, ShaderSource.CachedIncludeSource> includes = new ConcurrentHashMap<>();
        return new ShaderSource() {
            public String getShader(final Identifier id, final ShaderType type) {
                if (id.equals(REDRAW) && type == ShaderType.FRAGMENT) return REDRAW_FRAGMENT;
                return resource("assets/" + id.getNamespace() + "/shaders/" + id.getPath() + (type == ShaderType.VERTEX ? ".vsh" : ".fsh"));
            }
            public CachedIncludeSource getInclude(final Identifier id) {
                return includes.computeIfAbsent(id, key -> CachedIncludeSource.create(key,
                    resource("assets/" + key.getNamespace() + "/shaders/include/" + key.getPath())));
            }
            public void close() { includes.values().forEach(CachedIncludeSource::close); includes.clear(); }
        };
    }

    private static String resource(final String path) {
        try (InputStream in = LodReuseCompositeTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing built shader " + path + "; rebuild client.jar first");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException(path, e); }
    }
}
