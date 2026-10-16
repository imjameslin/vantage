import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.backend.metal.MetalBackend;
import com.mojang.renderpearl.backend.metal.MtlNative;
import com.mojang.renderpearl.frontend.FrontendGpuSurface;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.BooleanSupplier;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Integration (end-to-end, headless) test of the merged Metal backend: everything the game does on the render
 * thread, minus the window/surface, through the public RenderPearl API.
 *
 * <p>{@code new MetalBackend().createDevice(...)} gives the real {@code FrontendGpuDevice} over the real
 * {@code MetalDevice} (I3), {@code MetalCommandEncoder}/{@code MetalRenderPass}/{@code StreamWriter} (I4),
 * {@code MetalRenderPipeline}/{@code MslTranslator} (I5) and {@code libmcmetal.dylib} loaded from
 * {@code build/client.jar} (I1 device/resources/pipelines, I2 decoder, architect guard). Real vanilla pipelines
 * ({@code RenderPipelines.GUI}, {@code GUI_TEXTURED}, {@code DEBUG_FILLED_BOX}, {@code DEBUG_TRIANGLE_FAN},
 * {@code TRACY_BLIT}, {@code SOLID_TERRAIN}, plus one pipeline built from the vanilla {@code core/position_color}
 * shaders with {@code DepthStencilState.DEFAULT}) are compiled from the jar's GLSL through the frontend's
 * {@code PipelineBuilder} (shaderc, SPIR-V binding patching) on worker threads. Every scene renders offscreen,
 * reads the result back with {@code copyTextureToBuffer} + callback + fence, and checks pixels against values
 * computed from GL/Vulkan semantics (memory row 0 = NDC y = -1, reverse-Z, std140 UBOs, blend equations).
 *
 * <p>The whole suite runs twice: debug labels off, then on (pass labels and debug groups through the decoder).
 * Run with {@code MTL_DEBUG_LAYER=1} (tools/metal-e2e-test.sh): any Metal API validation error aborts the process.
 *
 * <p>Usage: {@code tools/build-client.sh && tools/metal-e2e-test.sh}.
 */
public class MetalE2ETest {
    private static int checks;
    private static int failures;
    private static final List<String> FAILED = new ArrayList<>();
    /** SHA-256 over every readback of one device run (printed per run; byte-exactness across toggle settings). */
    private static final java.security.MessageDigest READBACK_DIGEST = sha256();

    private static java.security.MessageDigest sha256() {
        try {
            return java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final int W = 64;
    private static final int H = 64;
    private static final int RT_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST
        | GpuTexture.USAGE_TEXTURE_BINDING;

    private static void check(final boolean condition, final String what) {
        checks++;
        if (!condition) {
            failures++;
            FAILED.add(what);
            System.out.println("  FAIL: " + what);
        }
    }

    public static void main(final String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        MetalBackend backend = new MetalBackend();
        backend.loadLibrary();
        System.out.println("libmcmetal: " + backend.probeInfo());
        for (boolean labels : new boolean[]{false, true}) {
            System.out.println("== device (debug labels " + (labels ? "on" : "off") + ", validation on)");
            long t0 = System.nanoTime();
            GpuDevice device = backend.createDevice(new GpuDebugOptions(0, false, labels, true));
            installRenderSystemDevice(device);
            Scenes scenes = new Scenes(device, labels);
            READBACK_DIGEST.reset();
            try {
                scenes.run();
            } finally {
                scenes.close();
                device.close();
            }
            System.out.printf("   device lifetime %.0f ms%n", (System.nanoTime() - t0) / 1e6);
            // Every readback of this device, in order: compare across runs with different backend toggles (byte-exactness).
            System.out.println("   readback digest (labels " + (labels ? "on" : "off") + "): " + java.util.HexFormat.of().formatHex(READBACK_DIGEST.digest()));
        }
        System.out.printf("%n%d checks, %d failures%n", checks, failures);
        if (failures > 0) {
            for (String f : FAILED) {
                System.out.println("  - " + f);
            }
        }
        System.out.println(failures == 0 ? "METAL E2E PASSED" : "METAL E2E FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** GlslCompiler reads RenderSystem.getDevice().getDeviceInfo(); the game sets it in RenderSystem.initRenderer. */
    private static void installRenderSystemDevice(final GpuDevice device) throws ReflectiveOperationException {
        Field field = RenderSystem.class.getDeclaredField("DEVICE");
        field.setAccessible(true);
        field.set(null, device);
    }

    // =============================================================================================

    private static final class Scenes implements AutoCloseable {
        final GpuDevice device;
        final CommandEncoder enc;
        final boolean labels;
        final ExecutorService workers = Executors.newFixedThreadPool(4);
        final ShaderSource shaders = jarShaderSource();
        final List<AutoCloseable> owned = new ArrayList<>();
        int messagesSeen;

        CompiledRenderPipeline gui;
        CompiledRenderPipeline guiTextured;
        CompiledRenderPipeline debugFilledBox;
        CompiledRenderPipeline debugFan;
        CompiledRenderPipeline tracyBlit;
        CompiledRenderPipeline solidTerrain;
        CompiledRenderPipeline depthColor;
        CompiledRenderPipeline flatClouds;
        CompiledRenderPipeline panorama;
        CompiledRenderPipeline blitDepth;

        GpuSampler nearestClamp;
        GpuSampler linearClamp;
        byte[] guiReference;     // the GUI scene's readback (present fusion compares against it)
        GpuBuffer quadIndices;   // QUADS -> (0,1,2, 2,3,0) per quad, 16 quads, ushort

        Scenes(final GpuDevice device, final boolean labels) {
            this.device = device;
            this.enc = device.createCommandEncoder();
            this.labels = labels;
        }

        void run() {
            this.compilePipelines();
            this.nearestClamp = this.device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1,
                OptionalDouble.empty());
            this.linearClamp = this.device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1,
                OptionalDouble.empty());
            ByteBuffer idx = direct(16 * 6 * 2);
            for (int q = 0; q < 16; q++) {
                int b = q * 4;
                for (int i : new int[]{0, 1, 2, 2, 3, 0}) {
                    idx.putShort((short)(b + i));
                }
            }
            this.quadIndices = this.own(this.device.createBuffer(() -> "e2e quad indices", GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, idx.flip()));

            this.scene("clears and copies", this::clearsAndCopies);
            byte[] gui = this.scene("GUI quads: projection/transform UBOs, translucent blend, scissor", this::guiScene);
            this.scene("render-to-texture + TRACY_BLIT (screenquad sampling a render target)", () -> this.blitScene(gui));
            this.scene("GUI_TEXTURED: writeToTexture, sampler, mip view sampling", this::texturedScene);
            this.scene("reverse-Z depth: DEFAULT write, DEBUG_FILLED_BOX test-only, depth readback", this::depthScene);
            this.scene("triangle fans (draw + drawIndexed) in a depth-less pass", this::fanScene);
            this.scene("SOLID_TERRAIN: 5 UBOs, Sampler0 + vertex-stage Sampler2, int attributes", this::terrainScene);
            this.scene("render into mip 1 view", this::mipRenderScene);
            this.scene("FLAT_CLOUDS: R8_SINT texel buffer (mapped ring buffers, vertex-id geometry)", this::cloudsScene);
            this.scene("PANORAMA: cube map faces", this::panoramaScene);
            this.scene("BLIT_DEPTH: depth-only pass, D32 sampled, gl_FragDepth", this::blitDepthScene);
            this.scene("UBO rewrite between passes (fast + staged writeToBuffer ordering)", this::uboOrderingScene);
            this.scene("UBO rewritten while earlier frames read it in flight: renameSmallWrites off vs on, byte-identical", this::inFlightUboScene);
            this.scene("timestamps + fence flush-on-wait", this::timestampsAndFences);
            this.scene("present: real MetalSurface on an offscreen CAMetalLayer (acquire, blit, submit, present, resize)", this::presentScene);
            this.scene("300 frames: transient UBOs, texture churn, deferred releases, while 192 vanilla pipelines compile on workers",
                this::sustainedFrames);
        }

        <T> T own(final T resource) {
            if (resource instanceof AutoCloseable c) {
                this.owned.add(c);
            }
            return resource;
        }

        byte[] scene(final String name, final java.util.function.Supplier<byte[]> body) {
            int before = failures;
            long t0 = System.nanoTime();
            byte[] result = null;
            try {
                result = body.get();
            } catch (Throwable t) {
                check(false, name + ": threw " + t);
                t.printStackTrace(System.out);
            }
            List<String> messages = this.device.getLastDebugMessages();
            if (messages.size() > this.messagesSeen) {
                List<String> fresh = messages.subList(this.messagesSeen, messages.size());
                for (String m : fresh) {
                    System.out.println("    device message: " + m);
                }
                check(false, name + ": " + fresh.size() + " new device message(s) (decoder warnings / command-buffer errors)");
                this.messagesSeen = messages.size();
            }
            System.out.printf("  %s %s (%.1f ms)%n", failures == before ? "ok  " : "FAIL", name, (System.nanoTime() - t0) / 1e6);
            return result;
        }

        byte[] scene(final String name, final Runnable body) {
            return this.scene(name, () -> {
                body.run();
                return null;
            });
        }

        // -----------------------------------------------------------------------------------------
        // Pipelines
        // -----------------------------------------------------------------------------------------

        void compilePipelines() {
            RenderPipeline depthColorPipeline = RenderPipeline.builder()
                .withLocation("e2e/position_color_depth")
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .build();
            List<RenderPipeline> all = List.of(RenderPipelines.GUI, RenderPipelines.GUI_TEXTURED, RenderPipelines.DEBUG_FILLED_BOX,
                RenderPipelines.DEBUG_TRIANGLE_FAN, RenderPipelines.TRACY_BLIT, RenderPipelines.SOLID_TERRAIN, depthColorPipeline,
                RenderPipelines.FLAT_CLOUDS, RenderPipelines.PANORAMA, RenderPipelines.BLIT_DEPTH);
            long t0 = System.nanoTime();
            var futures = all.stream().map(p -> this.device.compilePipeline(p, this.shaders, this.workers)).toList();
            List<CompiledRenderPipeline> compiled = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                CompiledRenderPipeline c = futures.get(i).join().finishCompile();
                check(c != null, "compile " + all.get(i).getLocation());
                compiled.add(c);
                if (c != null) {
                    this.owned.add(c);
                }
            }
            System.out.printf("  compiled %d vanilla pipelines through PipelineBuilder in %.0f ms%n", all.size(), (System.nanoTime() - t0) / 1e6);
            this.gui = compiled.get(0);
            this.guiTextured = compiled.get(1);
            this.debugFilledBox = compiled.get(2);
            this.debugFan = compiled.get(3);
            this.tracyBlit = compiled.get(4);
            this.solidTerrain = compiled.get(5);
            this.depthColor = compiled.get(6);
            this.flatClouds = compiled.get(7);
            this.panorama = compiled.get(8);
            this.blitDepth = compiled.get(9);
            if (compiled.contains(null)) {
                throw new IllegalStateException("pipeline compile failed; scenes cannot run");
            }
        }

        // -----------------------------------------------------------------------------------------
        // Helpers
        // -----------------------------------------------------------------------------------------

        GpuTexture rt(final String name, final GpuFormat format, final int w, final int h, final int mips) {
            return this.own(this.device.createTexture((String)name, RT_USAGE, format, w, h, 1, mips));
        }

        GpuTextureView view(final GpuTexture t) {
            return this.own(this.device.createTextureView(t));
        }

        /** copyTextureToBuffer + callback + fence: returns the tightly packed mip (row 0 = memory row 0). */
        byte[] readback(final GpuTexture texture, final int mip) {
            int w = texture.getWidth(mip);
            int h = texture.getHeight(mip);
            int bs = texture.getFormat() == GpuFormat.D32_FLOAT ? 4 : texture.getFormat().blockSize();
            GpuBuffer buffer = this.device.createBuffer(() -> "e2e readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long)w * h * bs + 16);
            boolean[] called = new boolean[1];
            this.enc.copyTextureToBuffer(texture, buffer, 16, () -> called[0] = true, mip);
            GpuFence fence = this.enc.createFence();
            this.enc.submit();
            check(fence.awaitCompletion(5_000_000_000L), "readback fence completes");
            fence.close();
            check(called[0], "copyTextureToBuffer callback ran after completion");
            byte[] out = new byte[w * h * bs];
            try (GpuBufferSlice.MappedView view = buffer.slice(16, (long)w * h * bs).map(true, false)) {
                view.data().get(out);
            }
            buffer.close();
            READBACK_DIGEST.update(out);
            return out;
        }

        static int px(final byte[] img, final int w, final int x, final int row) {
            int i = (row * w + x) * 4;
            return (img[i] & 0xFF) << 24 | (img[i + 1] & 0xFF) << 16 | (img[i + 2] & 0xFF) << 8 | img[i + 3] & 0xFF;
        }

        static float depthAt(final byte[] img, final int w, final int x, final int row) {
            return ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN).getFloat((row * w + x) * 4);
        }

        static boolean near(final int actual, final int expected, final int tolerance) {
            for (int s = 0; s < 32; s += 8) {
                if (Math.abs((actual >>> s & 0xFF) - (expected >>> s & 0xFF)) > tolerance) {
                    return false;
                }
            }
            return true;
        }

        static void expectPx(final byte[] img, final int w, final int x, final int row, final int rgba, final String what) {
            int actual = px(img, w, x, row);
            check(near(actual, rgba, 2), String.format("%s: pixel (%d, row %d) = %08x, expected %08x", what, x, row, actual, rgba));
        }

        static ByteBuffer direct(final int size) {
            return ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
        }

        static ByteBuffer mat4(final Matrix4f m) {
            ByteBuffer b = direct(64);
            m.get(b);
            return b;
        }

        /** std140 DynamicTransforms { mat4 ModelViewMat; mat4 TextureMat; vec4 ColorModulator; vec3 ModelOffset; } (160 bytes). */
        static ByteBuffer dynamicTransforms(final Matrix4f modelView, final Vector4f colorModulator) {
            ByteBuffer b = direct(160);
            modelView.get(0, b);
            new Matrix4f().get(64, b);
            b.putFloat(128, colorModulator.x).putFloat(132, colorModulator.y).putFloat(136, colorModulator.z).putFloat(140, colorModulator.w);
            return b;
        }

        /** Vanilla GUI projection: y down, zZeroToOne (DeviceInfo.isZZeroToOne on Metal/Vulkan). */
        static Matrix4f guiOrtho(final int w, final int h) {
            return new Matrix4f().setOrtho(0, w, h, 0, -1, 1, true);
        }

        GpuBufferSlice ubo(final ByteBuffer data) {
            // Transient GPU memory, like the game's per-frame uniform uploads (minUniformOffsetAlignment = 16).
            return this.enc.transientMemory().uploadGpu(data, this.device.getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM);
        }

        /** POSITION_COLOR quad vertices (stride 16): x0,y0 .. x1,y1 in the pipeline's space, CCW in y-up. */
        static void quad(final ByteBuffer vb, final float x0, final float y0, final float x1, final float y1, final float z, final int rgba) {
            // (x0,y1) (x1,y1) (x1,y0) (x0,y0): CCW when y grows downward (GUI space), see the winding notes below.
            float[][] corners = {{x0, y0}, {x0, y1}, {x1, y1}, {x1, y0}};
            for (float[] c : corners) {
                vb.putFloat(c[0]).putFloat(c[1]).putFloat(z).putInt(Integer.reverseBytes(rgba));
            }
        }

        RenderPass pass(final String label, final GpuTextureView color, final Optional<Vector4f> clear, final GpuTextureView depth, final OptionalDouble clearDepth) {
            return this.enc.createRenderPass(() -> label, color, clear.map(v -> (org.joml.Vector4fc)v), depth, clearDepth);
        }

        // -----------------------------------------------------------------------------------------
        // Scenes
        // -----------------------------------------------------------------------------------------

        void clearsAndCopies() {
            GpuTexture color = this.rt("e2e clear color", GpuFormat.RGBA8_UNORM, W, H, 1);
            GpuTexture depth = this.rt("e2e clear depth", GpuFormat.D32_FLOAT, W, H, 1);
            this.enc.clearColorAndDepthTextures(color, new Vector4f(0.0f, 0.0f, 1.0f, 1.0f), depth, 1.0);
            // Region clear: memory rows 8..23, columns 4..35 (no flip), color + depth.
            this.enc.clearColorAndDepthTextures(color, new Vector4f(1.0f, 0.0f, 0.0f, 1.0f), depth, 0.25, 4, 8, 32, 16, 0);
            byte[] c = this.readback(color, 0);
            byte[] d = this.readback(depth, 0);
            expectPx(c, W, 0, 0, 0x0000FFFF, "full clear");
            expectPx(c, W, 4, 8, 0xFF0000FF, "region clear corner (x 4, row 8)");
            expectPx(c, W, 35, 23, 0xFF0000FF, "region clear corner (x 35, row 23)");
            expectPx(c, W, 36, 23, 0x0000FFFF, "outside region (x 36)");
            expectPx(c, W, 4, 24, 0x0000FFFF, "outside region (row 24)");
            check(depthAt(d, W, 10, 10) == 0.25f && depthAt(d, W, 50, 50) == 1.0f && depthAt(d, W, 3, 8) == 1.0f,
                "depth full + region clear: " + depthAt(d, W, 10, 10) + " / " + depthAt(d, W, 50, 50) + " / " + depthAt(d, W, 3, 8));

            // writeToTexture (ByteBuffer, sub-rectangle), copyTextureToTexture (blit), copyBufferToTexture (sx/sy).
            GpuTexture tex = this.own(this.device.createTexture((String)"e2e upload", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
                | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, 16, 16, 1, 1));
            ByteBuffer texels = direct(4 * 2 * 4);
            for (int i = 0; i < 8; i++) {
                texels.putInt(Integer.reverseBytes(0x10203000 | i << 24 | 0xFF));
            }
            this.enc.writeToTexture(tex, texels.flip(), 0, 0, 2, 3, 4, 2);
            this.enc.copyTextureToTexture(tex, color, 0, 40, 40, 2, 3, 4, 2);
            ByteBuffer src = direct(8 * 8 * 4);
            for (int i = 0; i < 64; i++) {
                src.putInt(Integer.reverseBytes(0x00800000 | i << 24 | 0xFF));
            }
            GpuBuffer staging = this.device.createBuffer(() -> "e2e staging", GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_MAP_WRITE, src.flip());
            // source rectangle (sx 3, sy 2) of an 8-wide image, 2x2 texels, to (x 50, row 1).
            this.enc.copyBufferToTexture(staging.slice(), 3, 2, 8, 8, color, 50, 1, 2, 2, 0, 0);
            // Bottom-right 2x2 of the source: the last texel read is the last byte of the buffer, while
            // offset + bytesPerRow * height runs past it (Metal must not be handed an overrunning image size).
            this.enc.copyBufferToTexture(staging.slice(), 6, 6, 8, 8, color, 60, 60, 2, 2, 0, 0);
            byte[] t = this.readback(tex, 0);
            expectPx(t, 16, 2, 3, 0x102030FF, "writeToTexture texel (2,3)");
            expectPx(t, 16, 5, 4, 0x172030FF, "writeToTexture texel (5,4)");
            expectPx(t, 16, 1, 3, 0x00000000, "untouched texel is zero (INIT_TEXTURE)");
            byte[] c2 = this.readback(color, 0);
            expectPx(c2, W, 40, 40, 0x102030FF, "copyTextureToTexture dst (40,40)");
            expectPx(c2, W, 43, 41, 0x172030FF, "copyTextureToTexture dst (43,41)");
            expectPx(c2, W, 50, 1, 0x13800000 | 0xFF, "copyBufferToTexture texel (sx 3, sy 2) = index 19");
            expectPx(c2, W, 51, 2, (8 * 3 + 4) << 24 | 0x800000 | 0xFF, "copyBufferToTexture texel (sx 4, sy 3) = index 28");
            expectPx(c2, W, 60, 60, 54 << 24 | 0x800000 | 0xFF, "copyBufferToTexture at the end of the source (index 54)");
            expectPx(c2, W, 61, 61, 63 << 24 | 0x800000 | 0xFF, "copyBufferToTexture last source texel (index 63)");
            staging.close();

            // writeToBuffer (idle buffer: direct path) + copyToBuffer, read through map after a fence.
            GpuBuffer a = this.device.createBuffer(() -> "e2e a", GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST, 256);
            GpuBuffer b = this.device.createBuffer(() -> "e2e b", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 256);
            ByteBuffer data = direct(64);
            for (int i = 0; i < 16; i++) {
                data.putInt(0x5A000000 + i);
            }
            this.enc.writeToBuffer(a.slice(32, 64), data.flip());
            this.enc.copyToBuffer(a.slice(32, 64), b.slice(128, 64));
            // Rewrite a after the copy was recorded: must go through staging, the copy must still see the old data.
            ByteBuffer data2 = direct(64);
            for (int i = 0; i < 16; i++) {
                data2.putInt(0x77000000 + i);
            }
            this.enc.writeToBuffer(a.slice(32, 64), data2.flip());
            this.enc.copyToBuffer(a.slice(32, 16), b.slice(0, 16));
            GpuFence f = this.enc.createFence();
            this.enc.submit();
            check(f.awaitCompletion(5_000_000_000L), "copy fence");
            try (GpuBufferSlice.MappedView v = b.map(true, false)) {
                ByteBuffer bb = v.data().order(ByteOrder.LITTLE_ENDIAN);
                check(bb.getInt(128) == 0x5A000000 && bb.getInt(128 + 60) == 0x5A00000F, "copyToBuffer saw the first write");
                check(bb.getInt(0) == 0x77000000 && bb.getInt(12) == 0x77000003, "staged rewrite ordered before the second copy");
            }
            a.close();
            b.close();
        }

        byte[] guiScene() {
            GpuTexture color = this.rt("e2e gui", GpuFormat.RGBA8_UNORM, W, H, 1);
            this.drawGui(this.view(color));
            byte[] img = this.readback(color, 0);
            // GUI y grows downward; GUI y -> memory row 63 - y (NDC y = -1 is memory row 0).
            expectPx(img, W, 8, 63 - 8, 0xFF0000FF, "red quad (GUI 8,8)");
            expectPx(img, W, 56, 63 - 56, 0x00FF00FF, "green quad (GUI 56,56)");
            expectPx(img, W, 24, 63 - 24, 0x7F0080FF, "blue over red, TRANSLUCENT (GUI 24,24)");
            expectPx(img, W, 40, 63 - 40, 0x007F80FF, "blue over green (GUI 40,40)");
            expectPx(img, W, 40, 63 - 24, 0x000080FF, "blue over clear (GUI 40,24)");
            expectPx(img, W, 56, 63 - 8, 0x000000FF, "clear color (GUI 56,8)");
            expectPx(img, W, 2, 2, 0xFFFFFFFF, "scissored white (memory x 2, row 2)");
            expectPx(img, W, 7, 7, 0xFFFFFFFF, "scissored white (memory x 7, row 7)");
            expectPx(img, W, 8, 2, 0x00000000 | 0xFF, "outside scissor (x 8): clear");
            expectPx(img, W, 2, 8, 0x000000FF, "outside scissor (row 8): clear");
            this.guiReference = img;
            return img;
        }

        /** The GUI scene's pass (clear to opaque black, 3 quads with translucent blending, a scissored quad) into `target`. */
        void drawGui(final GpuTextureView target) {
            GpuBufferSlice projection = this.ubo(mat4(guiOrtho(W, H)));
            GpuBufferSlice transforms = this.ubo(dynamicTransforms(new Matrix4f(), new Vector4f(1, 1, 1, 1)));
            ByteBuffer vb = direct(4 * 4 * 16);
            quad(vb, 0, 0, 32, 32, 0, 0xFF0000FF);       // red, top-left in GUI space
            quad(vb, 32, 32, 64, 64, 0, 0x00FF00FF);     // green, bottom-right
            quad(vb, 16, 16, 48, 48, 0, 0x0000FF80);     // blue, alpha 128, translucent blend
            quad(vb, 0, 0, 64, 64, 0, 0xFFFFFFFF);       // white, scissored to memory rows/cols 0..7
            GpuBuffer vertices = this.device.createBuffer(() -> "e2e gui vb", GpuBuffer.USAGE_VERTEX, vb.flip());
            try (RenderPass p = this.pass("e2e gui pass", target, Optional.of(new Vector4f(0, 0, 0, 1)), null, OptionalDouble.empty())) {
                p.setUniform("Projection", projection);
                p.setUniform("DynamicTransforms", transforms);
                p.setPipeline(this.gui);
                p.setVertexBuffer(0, vertices.slice());
                p.setIndexBuffer(this.quadIndices, IndexType.SHORT);
                p.drawIndexed(18, 1, 0, 0, 0);
                p.enableScissor(0, 0, 8, 8);
                p.drawIndexed(6, 1, 18, 0, 0);
                p.disableScissor();
            }
            this.owned.add(vertices);   /* closed with the scenes, long after its submit */
        }

        void blitScene(final byte[] reference) {
            if (reference == null) {
                check(false, "blit scene needs the GUI scene output");
                return;
            }
            GpuTexture source = this.rt("e2e blit source", GpuFormat.RGBA8_UNORM, W, H, 1);
            ByteBuffer pixels = direct(reference.length).put(reference).flip();
            this.enc.writeToTexture(source, pixels, 0, 0, 0, 0, W, H);
            GpuTexture target = this.rt("e2e blit target", GpuFormat.RGBA8_UNORM, W, H, 1);
            try (RenderPass p = this.pass("e2e blit pass", this.view(target), Optional.empty(), null, OptionalDouble.empty())) {
                p.setPipeline(this.tracyBlit);
                p.setUniform("InSampler", this.view(source), this.nearestClamp);
                p.draw(3, 1, 0, 0);
            }
            byte[] img = this.readback(target, 0);
            int diff = 0;
            for (int i = 0; i < img.length; i++) {
                diff += img[i] != reference[i] ? 1 : 0;
            }
            check(diff == 0, "screenquad blit reproduces the source exactly (" + diff + " bytes differ)");
        }

        void texturedScene() {
            // 4x4 RGBA8 texture, 2 mips; texel (x, y) of mip 0 = (x*60, y*60, 200, 255); mip 1 = solid (9, 99, 199, 255).
            GpuTexture tex = this.own(this.device.createTexture((String)"e2e sampled", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, 4, 4, 1, 2));
            ByteBuffer mip0 = direct(64);
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    mip0.put((byte)(x * 60)).put((byte)(y * 60)).put((byte)200).put((byte)255);
                }
            }
            this.enc.writeToTexture(tex, mip0.flip(), 0, 0, 0, 0, 4, 4);
            ByteBuffer mip1 = direct(16);
            for (int i = 0; i < 4; i++) {
                mip1.put((byte)9).put((byte)99).put((byte)199).put((byte)255);
            }
            this.enc.writeToTexture(tex, mip1.flip(), 1, 0, 0, 0, 2, 2);
            GpuTexture color = this.rt("e2e textured", GpuFormat.RGBA8_UNORM, W, H, 1);
            GpuBufferSlice projection = this.ubo(mat4(guiOrtho(W, H)));
            GpuBufferSlice transforms = this.ubo(dynamicTransforms(new Matrix4f(), new Vector4f(1, 1, 1, 1)));
            ByteBuffer vb = direct(2 * 4 * 24);
            // POSITION_TEX_COLOR (stride 24): left half samples mip 0 with uv = GUI xy / 32 over [0,32)x[0,64);
            // right half samples through a mip-1 view.
            float[][] left = {{0, 0, 0, 0}, {0, 64, 0, 1}, {32, 64, 1, 1}, {32, 0, 1, 0}};
            float[][] right = {{32, 0, 0, 0}, {32, 64, 0, 1}, {64, 64, 1, 1}, {64, 0, 1, 0}};
            for (float[][] q : new float[][][]{left, right}) {
                for (float[] v : q) {
                    vb.putFloat(v[0]).putFloat(v[1]).putFloat(0).putFloat(v[2]).putFloat(v[3]).putInt(-1);
                }
            }
            GpuBuffer vertices = this.device.createBuffer(() -> "e2e textured vb", GpuBuffer.USAGE_VERTEX, vb.flip());
            GpuTextureView full = this.view(tex);
            GpuTextureView mipView = this.own(this.device.createTextureView(tex, 1, 1));
            try (RenderPass p = this.pass("e2e textured pass", this.view(color), Optional.of(new Vector4f(0, 0, 0, 0)), null, OptionalDouble.empty())) {
                p.setPipeline(this.guiTextured);
                p.setUniform("Projection", projection);
                p.setUniform("DynamicTransforms", transforms);
                p.setUniform("Sampler0", full, this.nearestClamp);
                p.setVertexBuffer(0, vertices.slice());
                p.setIndexBuffer(this.quadIndices, IndexType.SHORT);
                p.drawIndexed(6, 1, 0, 0, 0);
                p.setUniform("Sampler0", mipView, this.nearestClamp);
                p.drawIndexed(6, 1, 6, 0, 0);
            }
            byte[] img = this.readback(color, 0);
            // uv v = GUI y / 64 -> texel row v*4: texture row 0 (uploaded first) sits at GUI y 0..15 = memory rows 48..63.
            for (int ty = 0; ty < 4; ty++) {
                for (int tx = 0; tx < 4; tx++) {
                    int guiX = tx * 8 + 4;
                    int guiY = ty * 16 + 8;
                    int expected = (tx * 60) << 24 | (ty * 60) << 16 | 200 << 8 | 0xFF;
                    expectPx(img, W, guiX, 63 - guiY, expected, "Sampler0 texel (" + tx + "," + ty + ")");
                }
            }
            expectPx(img, W, 48, 32, 0x0963C7FF, "mip-1 view samples mip 1");
            vertices.close();
        }

        void depthScene() {
            GpuTexture color = this.rt("e2e depth color", GpuFormat.RGBA8_UNORM, W, H, 1);
            GpuTexture depth = this.rt("e2e depth", GpuFormat.D32_FLOAT, W, H, 1);
            // Ortho with zZeroToOne, near -1, far 1: z_ndc = 0.5 - z / 2. Reverse-Z: larger = nearer, cleared to 0.
            GpuBufferSlice projection = this.ubo(mat4(guiOrtho(W, H)));
            GpuBufferSlice transforms = this.ubo(dynamicTransforms(new Matrix4f(), new Vector4f(1, 1, 1, 1)));
            ByteBuffer vb = direct(4 * 4 * 16);
            quad(vb, 0, 0, 43, 64, 0.5f, 0xFF0000FF);    // A red,   z_ndc 0.25
            quad(vb, 21, 0, 64, 64, -0.5f, 0x00FF00FF);  // B green, z_ndc 0.75 (nearest)
            quad(vb, 0, 0, 64, 64, 0.0f, 0x0000FFFF);    // C blue,  z_ndc 0.5
            quad(vb, 0, 0, 64, 64, -0.2f, 0xFFFF00FF);   // D yellow DEBUG_FILLED_BOX, z_ndc 0.6, test only
            GpuBuffer vertices = this.device.createBuffer(() -> "e2e depth vb", GpuBuffer.USAGE_VERTEX, vb.flip());
            try (RenderPass p = this.pass("e2e depth pass", this.view(color), Optional.of(new Vector4f(0, 0, 0, 1)), this.view(depth), OptionalDouble.of(0.0))) {
                p.setUniform("Projection", projection);
                p.setUniform("DynamicTransforms", transforms);
                p.setPipeline(this.depthColor);
                p.setVertexBuffer(0, vertices.slice());
                p.setIndexBuffer(this.quadIndices, IndexType.SHORT);
                p.drawIndexed(18, 1, 0, 0, 0);
                p.setPipeline(this.debugFilledBox);
                p.drawIndexed(6, 1, 18, 0, 0);
            }
            byte[] img = this.readback(color, 0);
            byte[] d = this.readback(depth, 0);
            expectPx(img, W, 10, 32, 0xFFFF00FF, "x<21: C passed over A, then D (test-only) passed over C");
            expectPx(img, W, 30, 32, 0x00FF00FF, "21<=x<43: B nearest");
            expectPx(img, W, 60, 32, 0x00FF00FF, "x>=43: B");
            check(Math.abs(depthAt(d, W, 10, 32) - 0.5f) < 1e-6f, "depth x<21 = 0.5 (C written, D did not write): " + depthAt(d, W, 10, 32));
            check(Math.abs(depthAt(d, W, 30, 32) - 0.75f) < 1e-6f, "depth 21<=x<43 = 0.75: " + depthAt(d, W, 30, 32));
            vertices.close();
        }

        void fanScene() {
            GpuTexture color = this.rt("e2e fan", GpuFormat.RGBA8_UNORM, W, H, 1);
            GpuBufferSlice projection = this.ubo(mat4(guiOrtho(W, H)));
            GpuBufferSlice transforms = this.ubo(dynamicTransforms(new Matrix4f(), new Vector4f(1, 1, 1, 1)));
            ByteBuffer vb = direct(12 * 16);
            // Fan 1 (vertices 0..5): hub (16,16), rim around [4,28]^2 -> red. Fan 2 (vertices 6..11): around [36,60]^2 -> green.
            float[][] rim = {{0, 0}, {-12, -12}, {12, -12}, {12, 12}, {-12, 12}, {-12, -12}};
            for (int f = 0; f < 2; f++) {
                float cx = f == 0 ? 16 : 48;
                int rgba = f == 0 ? 0xFF0000FF : 0x00FF00FF;
                for (float[] r : rim) {
                    vb.putFloat(cx + r[0]).putFloat(cx + r[1]).putFloat(0).putInt(Integer.reverseBytes(rgba));
                }
            }
            GpuBuffer vertices = this.device.createBuffer(() -> "e2e fan vb", GpuBuffer.USAGE_VERTEX, vb.flip());
            ByteBuffer ib = direct(16);
            ib.putShort((short)99).putShort((short)99);
            for (int i = 0; i < 6; i++) {
                ib.putShort((short)i);
            }
            GpuBuffer indices = this.device.createBuffer(() -> "e2e fan ib", GpuBuffer.USAGE_INDEX, ib.flip());
            try (RenderPass p = this.pass("e2e fan pass", this.view(color), Optional.of(new Vector4f(0, 0, 0, 1)), null, OptionalDouble.empty())) {
                p.setPipeline(this.debugFan); // has a GEQUAL depth state; the pass has no depth attachment
                p.setUniform("Projection", projection);
                p.setUniform("DynamicTransforms", transforms);
                p.setVertexBuffer(0, vertices.slice());
                p.draw(6, 1, 0, 0);
                p.setIndexBuffer(indices, IndexType.SHORT);
                p.drawIndexed(6, 1, 2, 6, 0);
            }
            byte[] img = this.readback(color, 0);
            for (int[] at : new int[][]{{16, 16}, {6, 6}, {26, 6}, {26, 26}, {6, 26}, {16, 5}}) {
                expectPx(img, W, at[0], 63 - at[1], 0xFF0000FF, "draw() fan covers GUI (" + at[0] + "," + at[1] + ")");
            }
            for (int[] at : new int[][]{{48, 48}, {38, 38}, {58, 38}, {58, 58}, {38, 58}}) {
                expectPx(img, W, at[0], 63 - at[1], 0x00FF00FF, "drawIndexed() fan (firstIndex 2, baseVertex 6) covers GUI (" + at[0] + "," + at[1] + ")");
            }
            expectPx(img, W, 2, 63 - 2, 0x000000FF, "outside fan 1");
            expectPx(img, W, 32, 32, 0x000000FF, "between fans");
            vertices.close();
            indices.close();
        }

        void terrainScene() {
            GpuTexture color = this.rt("e2e terrain color", GpuFormat.RGBA8_UNORM, W, H, 1);
            GpuTexture depth = this.rt("e2e terrain depth", GpuFormat.D32_FLOAT, W, H, 1);
            // Block atlas 16x16: four 8x8 quadrants with distinct colors (row 0 = v 0).
            GpuTexture atlas = this.own(this.device.createTexture((String)"e2e atlas", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, 16, 16, 1, 1));
            int[] quadrant = {0xC86432FF, 0x32C864FF, 0x6432C8FF, 0xC8C832FF}; // (u<.5,v<.5) (u>=.5,v<.5) (u<.5,v>=.5) (u>=.5,v>=.5)
            ByteBuffer a = direct(16 * 16 * 4);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    a.putInt(Integer.reverseBytes(quadrant[(y >= 8 ? 2 : 0) + (x >= 8 ? 1 : 0)]));
                }
            }
            this.enc.writeToTexture(atlas, a.flip(), 0, 0, 0, 0, 16, 16);
            // Lightmap 16x16: only texel (15, 15) is white; UV2 = (240, 240) must sample it (int attribute path).
            GpuTexture lightmap = this.own(this.device.createTexture((String)"e2e lightmap", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, 16, 16, 1, 1));
            ByteBuffer l = direct(16 * 16 * 4);
            for (int i = 0; i < 256; i++) {
                l.putInt(i == 255 ? -1 : Integer.reverseBytes(0x000000FF));
            }
            this.enc.writeToTexture(lightmap, l.flip(), 0, 0, 0, 0, 16, 16);

            ByteBuffer globals = direct(48);
            globals.putInt(0, 16).putInt(4, 32).putInt(8, 48).putFloat(12, 1.0f);       // CameraBlockPos, GlintAlpha
            globals.putFloat(16, -1.0f).putFloat(20, 0).putFloat(24, 0).putFloat(28, 0); // CameraOffset, GameTime
            globals.putFloat(32, W).putFloat(36, H).putInt(40, 0).putInt(44, 0);         // ScreenSize, MenuBlurRadius, UseRgss = 0
            ByteBuffer fog = direct(48);
            fog.putFloat(0, 1).putFloat(4, 0).putFloat(8, 1).putFloat(12, 1);             // magenta FogColor: shows any fog leak
            fog.putFloat(16, 1000).putFloat(20, 2000).putFloat(24, 1000).putFloat(28, 2000).putFloat(32, 3000).putFloat(36, 3000);
            ByteBuffer chunk = direct(16);
            chunk.putInt(0, 17).putInt(4, 32).putInt(8, 48).putFloat(12, 1.0f);          // ChunkPosition - CameraBlockPos + CameraOffset = 0
            ByteBuffer terrain = direct(80);
            new Matrix4f().get(0, terrain);
            terrain.putInt(64, 16).putInt(68, 16);                                         // TextureSize
            GpuBufferSlice uProjection = this.ubo(mat4(new Matrix4f()));
            GpuBufferSlice uGlobals = this.ubo(globals);
            GpuBufferSlice uFog = this.ubo(fog);
            GpuBufferSlice uChunk = this.ubo(chunk);
            GpuBufferSlice uTerrain = this.ubo(terrain);

            // BLOCK vertex format: Position f32x3, Color u8x4, UV0 f32x2, UV2 s16x2 = 28 bytes. Full-screen quad, z 0.5, CCW (y up).
            ByteBuffer vb = direct(4 * 28);
            float[][] v = {{-1, -1, 0, 0}, {1, -1, 1, 0}, {1, 1, 1, 1}, {-1, 1, 0, 1}};
            for (float[] c : v) {
                vb.putFloat(c[0]).putFloat(c[1]).putFloat(0.5f).putInt(-1).putFloat(c[2]).putFloat(c[3]).putShort((short)240).putShort((short)240);
            }
            GpuBuffer vertices = this.device.createBuffer(() -> "e2e terrain vb", GpuBuffer.USAGE_VERTEX, vb.flip());
            try (RenderPass p = this.pass("e2e terrain pass", this.view(color), Optional.of(new Vector4f(0, 0, 0, 1)), this.view(depth), OptionalDouble.of(0.0))) {
                p.setPipeline(this.solidTerrain);
                p.setUniform("Projection", uProjection);
                p.setUniform("Globals", uGlobals);
                p.setUniform("Fog", uFog);
                p.setUniform("ChunkSection", uChunk);
                p.setUniform("TerrainUniform", uTerrain);
                p.setUniform("Sampler0", this.view(atlas), this.nearestClamp);
                p.setUniform("Sampler2", this.view(lightmap), this.linearClamp);
                p.setVertexBuffer(0, vertices.slice());
                p.setIndexBuffer(this.quadIndices, IndexType.SHORT);
                p.drawIndexed(6, 1, 0, 0, 0);
            }
            byte[] img = this.readback(color, 0);
            byte[] d = this.readback(depth, 0);
            // NDC y = -1 (v = 0) is memory row 0: quadrants map 1:1 onto memory quadrants.
            expectPx(img, W, 16, 16, quadrant[0], "terrain quadrant u<.5 v<.5");
            expectPx(img, W, 48, 16, quadrant[1], "terrain quadrant u>=.5 v<.5");
            expectPx(img, W, 16, 48, quadrant[2], "terrain quadrant u<.5 v>=.5");
            expectPx(img, W, 48, 48, quadrant[3], "terrain quadrant u>=.5 v>=.5");
            check(Math.abs(depthAt(d, W, 32, 32) - 0.5f) < 1e-6f, "terrain depth written (0.5): " + depthAt(d, W, 32, 32));
            vertices.close();
        }

        void mipRenderScene() {
            GpuTexture tex = this.rt("e2e mips", GpuFormat.RGBA8_UNORM, W, H, 2);
            GpuTextureView mip1 = this.own(this.device.createTextureView(tex, 1, 1));
            this.enc.clearColorTexture(tex, new Vector4f(0, 0, 0, 1));
            GpuBufferSlice projection = this.ubo(mat4(guiOrtho(32, 32)));
            GpuBufferSlice transforms = this.ubo(dynamicTransforms(new Matrix4f(), new Vector4f(1, 1, 1, 1)));
            ByteBuffer vb = direct(4 * 16);
            quad(vb, 0, 0, 16, 32, 0, 0x00FFFFFF);  // left half of mip 1: cyan
            GpuBuffer vertices = this.device.createBuffer(() -> "e2e mip vb", GpuBuffer.USAGE_VERTEX, vb.flip());
            try (RenderPass p = this.pass("e2e mip pass", mip1, Optional.empty(), null, OptionalDouble.empty())) {
                p.setPipeline(this.gui);
                p.setUniform("Projection", projection);
                p.setUniform("DynamicTransforms", transforms);
                p.setVertexBuffer(0, vertices.slice());
                p.setIndexBuffer(this.quadIndices, IndexType.SHORT);
                p.drawIndexed(6, 1, 0, 0, 0);
            }
            byte[] m1 = this.readback(tex, 1);
            byte[] m0 = this.readback(tex, 0);
            expectPx(m1, 32, 4, 10, 0x00FFFFFF, "mip 1 left half drawn");
            expectPx(m1, 32, 28, 10, 0x000000FF, "mip 1 right half: cleared (Load)");
            expectPx(m0, W, 4, 10, 0x000000FF, "mip 0 untouched");
            vertices.close();
        }

        void cloudsScene() {
            GpuTexture color = this.rt("e2e clouds", GpuFormat.RGBA8_UNORM, W, H, 1);
            GpuTexture depth = this.rt("e2e clouds depth", GpuFormat.D32_FLOAT, W, H, 1);
            // Like CloudRenderer: CloudInfo UBO (usage 130) and CloudFaces texel buffer (usage 258) written through map().
            GpuBuffer info = this.own(this.device.createBuffer(() -> "e2e cloud ubo", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, 48));
            try (GpuBufferSlice.MappedView v = info.map(false, true)) {
                ByteBuffer b = v.data().order(ByteOrder.LITTLE_ENDIAN);
                b.putFloat(0, 0.2f).putFloat(4, 0.4f).putFloat(8, 0.6f).putFloat(12, 1.0f);   // CloudColor
                b.putFloat(16, -0.5f).putFloat(20, 0.25f).putFloat(24, -0.75f);               // CloudOffset
                b.putFloat(32, 0.25f).putFloat(36, 0.25f).putFloat(40, 0.25f);                // CellSize
            }
            GpuBuffer faces = this.own(this.device.createBuffer(() -> "e2e cloud faces", GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_MAP_WRITE, 6));
            try (GpuBufferSlice.MappedView v = faces.map(false, true)) {
                // face A: cell (1, 0) -> x 2, z 0, top (dir 1); face B: cell (0, 1) + FLAG_EXTRA_X -> x 1, z 2, top: 129 as int8 = -127.
                v.data().put(0, (byte)1).put(1, (byte)0).put(2, (byte)1).put(3, (byte)0).put(4, (byte)1).put(5, (byte)-127);
            }
            ByteBuffer fog = direct(48);
            fog.putFloat(0, 1).putFloat(4, 0).putFloat(8, 1).putFloat(12, 1);
            fog.putFloat(16, 1e6f).putFloat(20, 2e6f).putFloat(24, 1e6f).putFloat(28, 2e6f).putFloat(32, 1e6f).putFloat(36, 1e6f); // FogCloudsEnd far away
            // ModelView swaps y and z: NDC (x, y, z) = (pos.x, pos.z, pos.y).
            Matrix4f swapYZ = new Matrix4f().set(1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1);
            try (RenderPass p = this.pass("e2e clouds pass", this.view(color), Optional.of(new Vector4f(0, 0, 0, 0)), this.view(depth), OptionalDouble.of(0.0))) {
                p.setPipeline(this.flatClouds);
                p.setUniform("Projection", this.ubo(mat4(new Matrix4f())));
                p.setUniform("DynamicTransforms", this.ubo(dynamicTransforms(swapYZ, new Vector4f(1, 1, 1, 1))));
                p.setUniform("Fog", this.ubo(fog));
                p.setUniform("CloudInfo", info);
                p.setUniform("CloudFaces", faces);
                p.setIndexBuffer(this.quadIndices, IndexType.SHORT);
                p.drawIndexed(12, 1, 0, 0, 0);
            }
            byte[] img = this.readback(color, 0);
            expectPx(img, W, 36, 12, 0x336699FF, "cloud face A (cell x 2, z 0): x [32,40) rows [8,16)");
            expectPx(img, W, 28, 28, 0x336699FF, "cloud face B (FLAG_EXTRA_X, int8 -127): x [24,32) rows [24,32)");
            expectPx(img, W, 28, 12, 0x00000000, "no cloud at x 28 row 12");
            expectPx(img, W, 36, 28, 0x00000000, "no cloud at x 36 row 28");
        }

        void panoramaScene() {
            GpuTexture cube = this.own(this.device.createTexture((String)"e2e cube", GpuTexture.USAGE_CUBEMAP_COMPATIBLE | GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, 4, 4, 6, 1));
            int[] faceColors = {0xFF0000FF, 0x00FF00FF, 0x0000FFFF, 0xFFFF00FF, 0xFF00FFFF, 0x00FFFFFF}; // +X -X +Y -Y +Z -Z
            for (int f = 0; f < 6; f++) {
                ByteBuffer face = direct(64);
                for (int i = 0; i < 16; i++) {
                    face.putInt(Integer.reverseBytes(faceColors[f]));
                }
                this.enc.writeToTexture(cube, face.flip(), 0, f, 0, 0, 4, 4);
            }
            GpuTexture color = this.rt("e2e panorama", GpuFormat.RGBA8_UNORM, W, H, 1);
            // POSITION quads in NDC (identity matrices); the direction is the position itself. CCW (y up).
            float[][] quads = {
                {-0.5f, -0.5f, 0.5f, 0.5f, 0.9f},    // +Z (layer 4) at the center
                {0.8f, -0.1f, 0.95f, 0.1f, 0.5f},    // +X (layer 0) right
                {-0.95f, -0.1f, -0.8f, 0.1f, 0.5f},  // -X (layer 1) left
                {-0.1f, 0.8f, 0.1f, 0.95f, 0.3f},    // +Y (layer 2) top rows
                {-0.1f, -0.95f, 0.1f, -0.8f, 0.3f},  // -Y (layer 3) bottom rows
            };
            ByteBuffer vb = direct(quads.length * 4 * 12);
            for (float[] q : quads) {
                float[][] corners = {{q[0], q[1]}, {q[2], q[1]}, {q[2], q[3]}, {q[0], q[3]}};
                for (float[] c : corners) {
                    vb.putFloat(c[0]).putFloat(c[1]).putFloat(q[4]);
                }
            }
            GpuBuffer vertices = this.device.createBuffer(() -> "e2e panorama vb", GpuBuffer.USAGE_VERTEX, vb.flip());
            try (RenderPass p = this.pass("e2e panorama pass", this.view(color), Optional.of(new Vector4f(0, 0, 0, 1)), null, OptionalDouble.empty())) {
                p.setPipeline(this.panorama);
                p.setUniform("Projection", this.ubo(mat4(new Matrix4f())));
                p.setUniform("DynamicTransforms", this.ubo(dynamicTransforms(new Matrix4f(), new Vector4f(1, 1, 1, 1))));
                p.setUniform("Sampler0", this.view(cube), this.linearClamp);
                p.setVertexBuffer(0, vertices.slice());
                p.setIndexBuffer(this.quadIndices, IndexType.SHORT);
                p.drawIndexed(6 * quads.length, 1, 0, 0, 0);
            }
            byte[] img = this.readback(color, 0);
            expectPx(img, W, 32, 32, faceColors[4], "+Z face");
            expectPx(img, W, 60, 32, faceColors[0], "+X face");
            expectPx(img, W, 3, 32, faceColors[1], "-X face");
            expectPx(img, W, 32, 60, faceColors[2], "+Y face (top memory rows = NDC y up)");
            expectPx(img, W, 32, 3, faceColors[3], "-Y face");
            vertices.close();
        }

        void blitDepthScene() {
            GpuTexture src = this.rt("e2e depth src", GpuFormat.D32_FLOAT, W, H, 1);
            GpuTexture scratch = this.rt("e2e depth scratch color", GpuFormat.RGBA8_UNORM, W, H, 1);
            this.enc.clearDepthTexture(src, 0.75);
            this.enc.clearColorAndDepthTextures(scratch, new Vector4f(0, 0, 0, 1), src, 0.125, 8, 40, 16, 8, 0);
            GpuTexture dst = this.rt("e2e depth dst", GpuFormat.D32_FLOAT, W, H, 1);
            RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "e2e depth-only pass")
                .withDepthAttachment(this.view(dst), OptionalDouble.of(0.0))
                .build();
            try (RenderPass p = this.enc.createRenderPass(descriptor)) {
                p.setPipeline(this.blitDepth);
                p.setUniform("InSampler", this.view(src), this.nearestClamp);
                p.draw(3, 1, 0, 0);
            }
            byte[] d = this.readback(dst, 0);
            check(depthAt(d, W, 10, 44) == 0.125f, "blit_depth copies the region value (0.125): " + depthAt(d, W, 10, 44));
            check(depthAt(d, W, 40, 10) == 0.75f, "blit_depth copies the cleared value (0.75): " + depthAt(d, W, 40, 10));
            check(depthAt(d, W, 7, 44) == 0.75f, "outside the region (x 7): " + depthAt(d, W, 7, 44));
        }

        void uboOrderingScene() {
            GpuTexture t1 = this.rt("e2e ubo 1", GpuFormat.RGBA8_UNORM, 16, 16, 1);
            GpuTexture t2 = this.rt("e2e ubo 2", GpuFormat.RGBA8_UNORM, 16, 16, 1);
            GpuTexture t3 = this.rt("e2e ubo 3", GpuFormat.RGBA8_UNORM, 16, 16, 1);
            GpuBuffer dyn = this.own(this.device.createBuffer(() -> "e2e dynamic transforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 256));
            GpuBuffer proj = this.own(this.device.createBuffer(() -> "e2e projection", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                mat4(guiOrtho(16, 16))));
            ByteBuffer vb = direct(4 * 16);
            quad(vb, 0, 0, 16, 16, 0, 0xFFFFFFFF);
            GpuBuffer vertices = this.device.createBuffer(() -> "e2e ubo vb", GpuBuffer.USAGE_VERTEX, vb.flip());
            GpuTexture[] targets = {t1, t2, t3};
            Vector4f[] modulators = {new Vector4f(1, 0, 0, 1), new Vector4f(0, 1, 0, 1), new Vector4f(0, 0, 1, 1)};
            for (int i = 0; i < 3; i++) {
                // i = 0: buffer idle -> direct memcpy; i = 1, 2: used by an earlier pass of this frame -> staged COPY_BUFFER.
                this.enc.writeToBuffer(dyn.slice(96, 160), dynamicTransforms(new Matrix4f(), modulators[i]));
                try (RenderPass p = this.pass("e2e ubo pass " + i, this.view(targets[i]), Optional.of(new Vector4f(0, 0, 0, 1)), null, OptionalDouble.empty())) {
                    p.setPipeline(this.gui);
                    p.setUniform("Projection", proj);
                    p.setUniform("DynamicTransforms", dyn.slice(96, 160));
                    p.setVertexBuffer(0, vertices.slice());
                    p.setIndexBuffer(this.quadIndices, IndexType.SHORT);
                    p.drawIndexed(6, 1, 0, 0, 0);
                }
            }
            int[] expected = {0xFF0000FF, 0x00FF00FF, 0x0000FFFF};
            for (int i = 0; i < 3; i++) {
                byte[] img = this.readback(targets[i], 0);
                expectPx(img, 16, 8, 8, expected[i], "pass " + i + " sees its own ColorModulator");
            }
            vertices.close();
        }

        /**
         * CONTRACT.md 16.R5-C (buffer renaming): one 512-byte UBO holds Projection (bytes 0..63) and DynamicTransforms
         * (256..415). Every frame rewrites only its ColorModulator (16 bytes) twice: before pass A, while the previous
         * frames that read the UBO are still in flight (a heavy blended pass at the start of each frame keeps them
         * there), and again between pass A and pass B of the same frame. Each pass draws into its own target. Run with
         * renaming off (staged COPY_BUFFER, the reference) and on (the UBO moves to an idle backing, the rest of its bytes
         * copied on the CPU); every target must show exactly its own modulator and both runs must read back byte-identical
         * images. A renamed backing reused too early, a recorded command retargeted to the new backing, or a lost copy of
         * the untouched bytes (Projection / ModelViewMat) would show up as another frame's color or an empty target.
         */
        void inFlightUboScene() {
            try {
                Field rename = metalStatic("MetalCommandEncoder", "RENAME_SMALL_WRITES");
                boolean saved = rename.getBoolean(null);
                byte[][] reference;
                byte[][] renamed;
                long[] counts;
                try {
                    rename.setBoolean(null, false);
                    reference = this.inFlightUboRun(false, new long[2]);
                    rename.setBoolean(null, true);
                    counts = new long[2];
                    renamed = this.inFlightUboRun(true, counts);
                } finally {
                    rename.setBoolean(null, saved);
                }
                int differing = 0;
                for (int i = 0; i < reference.length; i++) {
                    if (!java.util.Arrays.equals(reference[i], renamed[i])) {
                        differing++;
                    }
                }
                check(differing == 0, "renaming on reads back byte-identical targets (" + differing + " of " + reference.length + " differ)");
                System.out.printf("    renaming on: %d renames, %d of them while an earlier frame was in flight%n", counts[0], counts[1]);
                check(counts[0] >= IN_FLIGHT_FRAMES + counts[1], "every rewrite after a pass of the same frame was renamed (" + counts[0] + ")");
                check(counts[1] >= IN_FLIGHT_FRAMES / 2, "rewrites while an earlier frame was in flight were renamed (" + counts[1] + ")");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        static final int IN_FLIGHT_FRAMES = 24;

        static Field metalStatic(final String cls, final String name) throws ReflectiveOperationException {
            Field f = Class.forName("com.mojang.renderpearl.backend.metal." + cls).getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }

        Object metalEncoder() throws ReflectiveOperationException {
            Field backendField = Class.forName("com.mojang.renderpearl.frontend.FrontendGpuDevice").getDeclaredField("backend");
            backendField.setAccessible(true);
            Object metalDevice = backendField.get(this.device);
            return Class.forName("com.mojang.renderpearl.backend.metal.MetalDevice").getMethod("encoder").invoke(metalDevice);
        }

        static Vector4f inFlightModulator(final int frame, final int pass) {
            return new Vector4f(((frame * 37 + 11) % 256) / 255.0f, pass == 0 ? 64 / 255.0f : 192 / 255.0f, ((frame * 91 + 5) % 256) / 255.0f, 1.0f);
        }

        /** One run of {@link #inFlightUboScene}; counts[0] += renames, counts[1] += renames of buffers an earlier frame read. */
        byte[][] inFlightUboRun(final boolean renaming, final long[] counts) throws ReflectiveOperationException {
            Object encoder = this.metalEncoder();
            Field renames = encoder.getClass().getDeclaredField("renames");
            Field renamesInFlight = encoder.getClass().getDeclaredField("renamesInFlight");
            renames.setAccessible(true);
            renamesInFlight.setAccessible(true);
            long renames0 = renames.getLong(encoder);
            long inFlight0 = renamesInFlight.getLong(encoder);
            int hogSize = 2048;
            GpuTexture hog = this.rt("e2e in-flight hog", GpuFormat.RGBA8_UNORM, hogSize, hogSize, 1);
            GpuTextureView hogView = this.view(hog);
            ByteBuffer hogVb = direct(16 * 4 * 16);
            for (int q = 0; q < 16; q++) {
                quad(hogVb, 0, 0, hogSize, hogSize, 0, 0x02030405);
            }
            GpuBuffer hogVertices = this.device.createBuffer(() -> "e2e in-flight hog vb", GpuBuffer.USAGE_VERTEX, hogVb.flip());
            ByteBuffer vb = direct(4 * 16);
            quad(vb, 0, 0, 16, 16, 0, 0xFFFFFFFF);
            GpuBuffer vertices = this.device.createBuffer(() -> "e2e in-flight vb", GpuBuffer.USAGE_VERTEX, vb.flip());
            ByteBuffer init = direct(512);
            guiOrtho(16, 16).get(0, init);
            ByteBuffer transforms = dynamicTransforms(new Matrix4f(), new Vector4f(1, 1, 1, 1));
            for (int i = 0; i < 160; i++) {
                init.put(256 + i, transforms.get(i));
            }
            GpuBuffer ubo = this.device.createBuffer(() -> "e2e in-flight ubo", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, init);
            GpuTexture[] targets = new GpuTexture[IN_FLIGHT_FRAMES * 2];
            for (int f = 0; f < IN_FLIGHT_FRAMES; f++) {
                ByteBuffer modulator = direct(16);
                for (int pass = 0; pass < 2; pass++) {
                    Vector4f m = inFlightModulator(f, pass);
                    modulator.clear();
                    modulator.putFloat(m.x).putFloat(m.y).putFloat(m.z).putFloat(m.w).flip();
                    // ColorModulator only (DynamicTransforms + 128): every other byte of the UBO must survive a rename.
                    this.enc.writeToBuffer(ubo.slice(256 + 128, 16), modulator);
                    if (pass == 0) {
                        // Keeps this frame (and its UBO read below) on the GPU while the CPU records the next frame.
                        try (RenderPass p = this.pass("e2e in-flight hog", hogView, Optional.of(new Vector4f(0, 0, 0, 1)), null, OptionalDouble.empty())) {
                            p.setPipeline(this.gui);
                            p.setUniform("Projection", this.ubo(mat4(guiOrtho(hogSize, hogSize))));
                            p.setUniform("DynamicTransforms", this.ubo(dynamicTransforms(new Matrix4f(), new Vector4f(1, 1, 1, 1))));
                            p.setVertexBuffer(0, hogVertices.slice());
                            p.setIndexBuffer(this.quadIndices, IndexType.SHORT);
                            for (int layer = 0; layer < 4; layer++) {
                                p.drawIndexed(16 * 6, 1, 0, 0, 0);
                            }
                        }
                    }
                    GpuTexture target = this.rt("e2e in-flight " + f + "/" + pass, GpuFormat.RGBA8_UNORM, 16, 16, 1);
                    targets[f * 2 + pass] = target;
                    try (RenderPass p = this.pass("e2e in-flight pass " + pass, this.view(target), Optional.of(new Vector4f(0, 0, 0, 1)), null,
                        OptionalDouble.empty())) {
                        p.setPipeline(this.gui);
                        p.setUniform("Projection", ubo.slice(0, 64));
                        p.setUniform("DynamicTransforms", ubo.slice(256, 160));
                        p.setVertexBuffer(0, vertices.slice());
                        p.setIndexBuffer(this.quadIndices, IndexType.SHORT);
                        p.drawIndexed(6, 1, 0, 0, 0);
                    }
                }
                this.enc.submit();
            }
            counts[0] += renames.getLong(encoder) - renames0;
            counts[1] += renamesInFlight.getLong(encoder) - inFlight0;
            byte[][] images = new byte[targets.length][];
            for (int i = 0; i < targets.length; i++) {
                images[i] = this.readback(targets[i], 0);
                Vector4f m = inFlightModulator(i / 2, i % 2);
                int expected = Math.round(m.x * 255) << 24 | Math.round(m.y * 255) << 16 | Math.round(m.z * 255) << 8 | 0xFF;
                expectPx(images[i], 16, 8, 8, expected, "renaming " + (renaming ? "on" : "off") + ": frame " + i / 2 + " pass " + i % 2
                    + " sees its own ColorModulator");
                expectPx(images[i], 16, 0, 0, expected, "renaming " + (renaming ? "on" : "off") + ": frame " + i / 2 + " pass " + i % 2
                    + " quad covers the target (Projection kept)");
            }
            ubo.close();
            vertices.close();
            hogVertices.close();
            return images;
        }

        void timestampsAndFences() {
            GpuQueryPool pool = this.device.createTimestampQueryPool(4);
            GpuTexture color = this.rt("e2e ts", GpuFormat.RGBA8_UNORM, W, H, 1);
            this.enc.writeTimestamp(pool, 0);
            try (RenderPass p = this.pass("e2e ts pass", this.view(color), Optional.of(new Vector4f(0.5f, 0.5f, 0.5f, 1)), null, OptionalDouble.empty())) {
                p.writeTimestamp(pool, 2);
            }
            this.enc.writeTimestamp(pool, 1);
            // Flush-on-wait: a blocking wait on the recording submit commits everything recorded so far.
            GpuFence fence = this.enc.createFence();
            check(fence.awaitCompletion(5_000_000_000L), "flush-on-wait fence completes");
            fence.close();
            OptionalLong[] values = pool.getValues(0, 3);
            check(values[0].isPresent() && values[1].isPresent() && values[2].isPresent(), "timestamps resolved: " + java.util.Arrays.toString(values));
            if (values[0].isPresent() && values[1].isPresent()) {
                long span = values[1].getAsLong() - values[0].getAsLong();
                check(span >= 0 && span < 1_000_000_000L, "timestamp span plausible (" + span + " ns)");
            }
            byte[] img = this.readback(color, 0);
            expectPx(img, W, 5, 5, 0x808080FF, "pass between timestamps cleared");
            pool.close();
        }

        /**
         * The game's frame order (Minecraft.runTick): configure, acquireNextTexture, render, blitFromTexture, TimerQuery
         * timestamp, submit, present. The surface is the real MetalSurface over the real libmcmetal surface exports;
         * only the SDL view is replaced (package-private MetalSurface.Natives seam): the layer is a CAMetalLayer made
         * through the Objective-C runtime, so no window is needed. Drawables are framebufferOnly (not readable): the
         * checks are the submit result flags, surface state, timestamps and Metal validation.
         */
        void presentScene() {
            try {
                this.presentSceneChecked();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            } catch (Throwable t) {
                throw t instanceof RuntimeException r ? r : new IllegalStateException(t);
            }
        }

        void presentSceneChecked() throws Throwable {
            Field backendField = Class.forName("com.mojang.renderpearl.frontend.FrontendGpuDevice").getDeclaredField("backend");
            backendField.setAccessible(true);
            Object metalDevice = backendField.get(this.device);
            Class<?> metalDeviceClass = Class.forName("com.mojang.renderpearl.backend.metal.MetalDevice");
            Class<?> surfaceClass = Class.forName("com.mojang.renderpearl.backend.metal.MetalSurface");
            Class<?> nativesClass = Class.forName("com.mojang.renderpearl.backend.metal.MetalSurface$Natives");
            Object metalEncoder = metalDeviceClass.getMethod("encoder").invoke(metalDevice);
            Method lastResultFlags = metalEncoder.getClass().getDeclaredMethod("lastResultFlags");
            lastResultFlags.setAccessible(true);

            long layer = ObjC.newMetalLayer();
            Object natives = Proxy.newProxyInstance(nativesClass.getClassLoader(), new Class<?>[]{nativesClass}, (proxy, m, a) -> switch (m.getName()) {
                case "createView" -> 0x5EED0000L;
                case "getLayer" -> layer;
                case "destroyView" -> null;
                case "lastSdlError" -> "";
                case "windowFlags" -> 0L;
                case "surfaceCreate" -> MtlNative.surfaceCreate((Long)a[0], (Long)a[1], (Long)a[2]);
                case "surfaceConfigure" -> MtlNative.surfaceConfigure((Long)a[0], (Long)a[1]);
                case "surfacePresent" -> MtlNative.surfacePresent((Long)a[0]);
                case "surfaceDestroy" -> MtlNative.surfaceDestroy((Long)a[0]);
                case "toString" -> "e2e surface natives";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == a[0];
                default -> throw new UnsupportedOperationException(m.getName());
            });
            Method forTesting = surfaceClass.getDeclaredMethod("forTesting", metalDeviceClass, long.class, BooleanSupplier.class, nativesClass);
            forTesting.setAccessible(true);
            Object backendSurface = forTesting.invoke(null, metalDevice, 1L, (BooleanSupplier)() -> false, natives);
            GpuSurface surface = new FrontendGpuSurface((com.mojang.renderpearl.backend.api.GpuSurfaceBackend)backendSurface);

            GpuTexture main = this.rt("e2e main target", GpuFormat.RGBA8_UNORM, W, H, 2);
            GpuTextureView mainView = this.own(this.device.createTextureView(main, 0, 1));
            GpuTextureView mip1View = this.own(this.device.createTextureView(main, 1, 1));
            GpuQueryPool timer = this.device.createTimestampQueryPool(2);
            // FIFO (vsync on): the blocking present path, unchanged by the mailbox (CONTRACT.md 16.4), so every frame
            // presents and the per-frame result flags are exact.
            surface.configure(new GpuSurface.Configuration(W, H, GpuSurface.PresentMode.FIFO));
            check(!surface.isSuboptimal(), "configured surface is optimal");
            int frames = 6;
            for (int f = 0; f < frames; f++) {
                if (f == 4) {
                    // The layer changes size behind the backend's back (live resize): the next PRESENT reports
                    // DRAWABLE_RESIZED and the surface turns suboptimal until the game reconfigures it.
                    ObjC.setDrawableSize(layer, 48, 40);
                }
                surface.acquireNextTexture();
                this.enc.writeTimestamp(timer, 0);
                this.enc.clearColorTexture(main, new Vector4f(f / 8.0f, 0.25f, 0.5f, 1));
                surface.blitFromTexture(this.enc, f == 3 ? mip1View : mainView);
                this.enc.writeTimestamp(timer, 1);
                this.enc.submit();
                int flags = (Integer)lastResultFlags.invoke(metalEncoder);
                check((flags & MtlNative.SUBMIT_RESULT_FLAG_PRESENT_ENCODED) != 0, "frame " + f + ": PRESENT encoded (flags 0x" + Integer.toHexString(flags) + ")");
                check((flags & MtlNative.SUBMIT_RESULT_FLAG_COMMITTED) != 0, "frame " + f + ": committed");
                check((flags & MtlNative.SUBMIT_RESULT_FLAG_DRAWABLE_NIL) == 0, "frame " + f + ": drawable acquired");
                boolean resized = (flags & MtlNative.SUBMIT_RESULT_FLAG_DRAWABLE_RESIZED) != 0;
                check(resized == (f == 4), "frame " + f + ": DRAWABLE_RESIZED " + resized);
                surface.present();
                check(surface.isSuboptimal() == (f == 4), "frame " + f + ": suboptimal " + surface.isSuboptimal());
                if (f == 4) {
                    surface.configure(new GpuSurface.Configuration(48, 40, GpuSurface.PresentMode.FIFO));
                    check(!surface.isSuboptimal(), "reconfigured surface is optimal again");
                }
            }
            GpuFence fence = this.enc.createFence();
            this.enc.submit();
            check(fence.awaitCompletion(5_000_000_000L), "frames after present complete");
            OptionalLong[] t = timer.getValues(0, 2);
            check(t[0].isPresent() && t[1].isPresent() && t[1].getAsLong() >= t[0].getAsLong(),
                "TimerQuery-style timestamps around the present pass: " + java.util.Arrays.toString(t));
            timer.close();

            // Present fusion (CONTRACT.md 16.3, MCMTL_PRESENT_FUSION=1 by default): a frame that clears the main target
            // and renders the GUI into it goes straight into the flipped, framebufferOnly drawable once the vanilla GUI
            // pipeline's BGRA8 variant has compiled (asynchronously: the first frames use the present pass). The main
            // target is then stale; a screenshot-style readback at the start of the next frame must still see exactly
            // the frame (restored from the drawable), as must the readback of an unfused frame.
            // With vsync off the surface presents without blocking (mailbox, CONTRACT.md 16.4): a frame is presented when the
            // acquirer thread has a drawable parked, else rendered and not presented; never DRAWABLE_NIL. The 10 ms pause
            // per frame (> one refresh) lets most of them present.
            if (this.guiReference != null) {
                surface.configure(new GpuSurface.Configuration(W, H, GpuSurface.PresentMode.IMMEDIATE));
                GpuTexture fusedMain = this.rt("e2e fused main", GpuFormat.RGBA8_UNORM, W, H, 1);
                GpuTextureView fusedView = this.view(fusedMain);
                int presented = 0;
                for (int f = 0; f < 8; f++) {
                    surface.acquireNextTexture();
                    this.enc.clearColorTexture(fusedMain, new Vector4f(1, 0, 1, 1));   // frame-start clear (GameRenderer C1)
                    this.drawGui(fusedView);
                    surface.blitFromTexture(this.enc, fusedView);
                    this.enc.submit();
                    int flags = (Integer)lastResultFlags.invoke(metalEncoder);
                    check((flags & MtlNative.SUBMIT_RESULT_FLAG_DRAWABLE_NIL) == 0, "fused frame " + f + ": no nil drawable (flags 0x" + Integer.toHexString(flags) + ")");
                    presented += (flags & MtlNative.SUBMIT_RESULT_FLAG_PRESENT_ENCODED) != 0 ? 1 : 0;
                    surface.present();
                    byte[] img = this.readback(fusedMain, 0);
                    check(java.util.Arrays.equals(img, this.guiReference), "fused frame " + f + ": next-frame readback equals the GUI scene");
                    Thread.sleep(10);   // lets the asynchronous BGRA8 variant compile land
                }
                check(presented >= 1, "fused frames: " + presented + " of 8 presented");
                this.mailboxFrames(surface, surfaceClass, lastResultFlags, metalEncoder);
            }
            surface.close();
        }

        /**
         * Mailbox presentation through the real MetalSurface (CONTRACT.md 16.4). The offscreen layer hands drawables back
         * only at the display refresh, like a composited fullscreen layer: back-to-back IMMEDIATE frames must not wait for
         * them (about one per refresh is presented, every other frame is rendered completely and not presented), while
         * FIFO frames wait in nextDrawable and all present. MetalSurface.presentStats() (the bench harness reads it by
         * reflection) counts both.
         */
        void mailboxFrames(final GpuSurface surface, final Class<?> surfaceClass, final Method lastResultFlags, final Object metalEncoder)
            throws Throwable {
            Method presentStats = surfaceClass.getMethod("presentStats");
            int framesIdx = surfaceClass.getField("STAT_FRAMES").getInt(null);
            int presentedIdx = surfaceClass.getField("STAT_PRESENTED").getInt(null);
            int notPresentedIdx = surfaceClass.getField("STAT_NOT_PRESENTED").getInt(null);
            int nilIdx = surfaceClass.getField("STAT_NIL").getInt(null);
            GpuTexture main = this.rt("e2e mailbox main", GpuFormat.RGBA8_UNORM, W, H, 1);
            GpuTextureView mainView = this.view(main);
            // MCMTL_MAILBOX=0: IMMEDIATE uses the previous blocking path (every frame presents, at the refresh here).
            boolean mailboxOn = !"0".equals(System.getenv("MCMTL_MAILBOX"));
            double[] msPerFrame = new double[2];
            for (int mode = 0; mode < 2; mode++) {
                boolean mailbox = mode == 0 && mailboxOn;
                int frames = mailbox ? 240 : 30;
                surface.configure(new GpuSurface.Configuration(W, H, mode == 0 ? GpuSurface.PresentMode.IMMEDIATE : GpuSurface.PresentMode.FIFO));
                Thread.sleep(30);   // the acquirer parks its first drawable
                long[] before = (long[])presentStats.invoke(null);
                int presented = 0;
                long t0 = System.nanoTime();
                for (int f = 0; f < frames; f++) {
                    surface.acquireNextTexture();
                    this.enc.clearColorTexture(main, new Vector4f((f % 200) / 255.0f, 0.25f, mailbox ? 0.5f : 0.75f, 1));
                    surface.blitFromTexture(this.enc, mainView);
                    this.enc.submit();
                    int flags = (Integer)lastResultFlags.invoke(metalEncoder);
                    check((flags & MtlNative.SUBMIT_RESULT_FLAG_DRAWABLE_NIL) == 0, (mailbox ? "mailbox" : "FIFO") + " frame " + f + ": no nil drawable");
                    check(!surface.isSuboptimal(), (mailbox ? "mailbox" : "FIFO") + " frame " + f + ": surface stays optimal");
                    presented += (flags & MtlNative.SUBMIT_RESULT_FLAG_PRESENT_ENCODED) != 0 ? 1 : 0;
                    surface.present();
                }
                double ms = (System.nanoTime() - t0) / 1e6;
                msPerFrame[mode] = ms / frames;
                long[] after = (long[])presentStats.invoke(null);
                long dFrames = after[framesIdx] - before[framesIdx], dPresented = after[presentedIdx] - before[presentedIdx];
                long dNotPresented = after[notPresentedIdx] - before[notPresentedIdx], dNil = after[nilIdx] - before[nilIdx];
                check(dFrames == frames && dPresented == presented && dPresented + dNotPresented == frames && dNil == 0,
                    (mailbox ? "mailbox" : "FIFO") + " presentStats: frames " + dFrames + ", presented " + dPresented + ", not presented " + dNotPresented + ", nil " + dNil);
                if (mailbox) {
                    check(presented >= 1 && presented <= (int)(ms / 4.0) + 3, "mailbox: " + presented + " of " + frames + " presented in " + ms + " ms (about one per refresh)");
                    // The newest frame was rendered completely whether or not it was presented.
                    expectPx(this.readback(main, 0), W, 1, 1, ((((frames - 1) % 200) & 0xFF) << 24) | (0x40 << 16) | (0x80 << 8) | 0xFF, "mailbox: last frame rendered");
                } else {
                    check(presented == frames, "FIFO: every frame presented (" + presented + " of " + frames + ")");
                }
                System.out.printf("  %s: %d frames, %.3f ms/frame (%.0f FPS), presented %d%n",
                    mailbox ? "mailbox (IMMEDIATE)" : mode == 0 ? "blocking (IMMEDIATE, MCMTL_MAILBOX=0)" : "blocking (FIFO)",
                    frames, ms / frames, frames * 1000.0 / ms, presented);
            }
            if (mailboxOn) {
                check(msPerFrame[0] < 0.5 * msPerFrame[1], "mailbox frames never wait for a drawable: " + msPerFrame[0] + " vs FIFO " + msPerFrame[1] + " ms/frame");
            }
        }

        void sustainedFrames() {
            GpuTexture color = this.rt("e2e frames", GpuFormat.RGBA8_UNORM, W, H, 1);
            GpuTextureView colorView = this.view(color);
            ByteBuffer vb = direct(4 * 16);
            quad(vb, 0, 0, 64, 64, 0, 0xFFFFFFFF);
            GpuBuffer vertices = this.device.createBuffer(() -> "e2e frames vb", GpuBuffer.USAGE_VERTEX, vb.flip());
            // Like a resource reload: every vanilla pipeline compiles on worker threads while the render thread keeps
            // decoding frames (PSO creation and lazy variant builds in parallel with mcmtl_submit).
            List<RenderPipeline> vanilla = new ArrayList<>(RenderPipelines.requiredPipelines());
            vanilla.addAll(RenderPipelines.optionalPipelines());
            var compiles = vanilla.stream().map(p -> this.device.compilePipeline(p, this.shaders, this.workers)).toList();
            long t0 = System.nanoTime();
            int frames = 300;
            for (int f = 0; f < frames; f++) {
                // A short-lived texture per frame: INIT_TEXTURE in the init stream + deferred release after completion.
                GpuTexture churn = this.device.createTexture((String)null, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, 8, 8, 1, 1);
                GpuTextureView churnView = this.device.createTextureView(churn);
                float shade = (f % 256) / 255.0f;
                try (RenderPass p = this.pass("e2e frame", colorView, Optional.of(new Vector4f(0, 0, 0, 1)), null, OptionalDouble.empty())) {
                    p.setPipeline(this.gui);
                    p.setUniform("Projection", this.ubo(mat4(guiOrtho(W, H))));
                    for (int d = 0; d < 8; d++) {
                        p.setUniform("DynamicTransforms", this.ubo(dynamicTransforms(new Matrix4f(), new Vector4f(shade, d / 7.0f, 1, 1))));
                        p.setVertexBuffer(0, vertices.slice());
                        p.setIndexBuffer(this.quadIndices, IndexType.SHORT);
                        p.drawIndexed(6, 1, 0, 0, 0);
                    }
                }
                churnView.close();
                churn.close();
                this.enc.submit();
            }
            double ms = (System.nanoTime() - t0) / 1e6;
            System.out.printf("    %d frames in %.0f ms (%.2f ms/frame incl. 2-in-flight throttle)%n", frames, ms, ms / frames);
            int compiled = 0;
            for (var future : compiles) {
                CompiledRenderPipeline c = future.join().finishCompile();
                if (c != null) {
                    compiled++;
                    this.owned.add(c);
                }
            }
            System.out.printf("    %d/%d vanilla pipelines compiled concurrently (%.0f ms after the frames started)%n", compiled, vanilla.size(),
                (System.nanoTime() - t0) / 1e6);
            check(compiled == vanilla.size(), "every vanilla pipeline compiled while rendering (" + compiled + "/" + vanilla.size() + ")");
            byte[] img = this.readback(color, 0);
            int lastShade = Math.round(((frames - 1) % 256) / 255.0f * 255);
            expectPx(img, W, 32, 32, lastShade << 24 | 0xFF << 16 | 0xFF << 8 | 0xFF, "last frame's last draw visible");
            vertices.close();
        }

        @Override
        public void close() {
            for (int i = this.owned.size() - 1; i >= 0; i--) {
                try {
                    this.owned.get(i).close();
                } catch (Exception e) {
                    check(false, "close " + this.owned.get(i) + ": " + e);
                }
            }
            if (this.nearestClamp != null) {
                this.nearestClamp.close();
                this.linearClamp.close();
            }
            this.shaders.close();
            this.workers.shutdown();
        }
    }

    // =============================================================================================

    /** Minimal Objective-C runtime access through FFM (QuartzCore is already loaded by libmcmetal). */
    private static final class ObjC {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup LIBOBJC = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
        private static final MethodHandle GET_CLASS = LINKER.downcallHandle(LIBOBJC.find("objc_getClass").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        private static final MethodHandle SEL = LINKER.downcallHandle(LIBOBJC.find("sel_registerName").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        private static final MethodHandle SEND = LINKER.downcallHandle(LIBOBJC.find("objc_msgSend").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        private static final MethodHandle SEND_SIZE = LINKER.downcallHandle(LIBOBJC.find("objc_msgSend").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, MemoryLayout.structLayout(ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE)));

        /** {@code [CAMetalLayer new]} (+1, deliberately never released: the test process exits). */
        static long newMetalLayer() throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment cls = (MemorySegment)GET_CLASS.invokeExact(arena.allocateFrom("CAMetalLayer"));
                MemorySegment sel = (MemorySegment)SEL.invokeExact(arena.allocateFrom("new"));
                if (cls.address() == 0L) {
                    throw new IllegalStateException("CAMetalLayer class not found");
                }
                return ((MemorySegment)SEND.invokeExact(cls, sel)).address();
            }
        }

        /** {@code layer.drawableSize = CGSizeMake(w, h)}. */
        static void setDrawableSize(final long layer, final double w, final double h) throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment sel = (MemorySegment)SEL.invokeExact(arena.allocateFrom("setDrawableSize:"));
                MemorySegment size = arena.allocate(16, 8);
                size.set(ValueLayout.JAVA_DOUBLE, 0, w);
                size.set(ValueLayout.JAVA_DOUBLE, 8, h);
                SEND_SIZE.invokeExact(MemorySegment.ofAddress(layer), sel, size);
            }
        }
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
        try (InputStream in = MetalE2ETest.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(path, e);
        }
    }
}
