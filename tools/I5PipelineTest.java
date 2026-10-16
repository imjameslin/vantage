import com.google.gson.JsonElement;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.backend.metal.MetalDevice;
import com.mojang.renderpearl.backend.metal.MetalFormats;
import com.mojang.renderpearl.backend.metal.MetalRenderPipeline;
import com.mojang.renderpearl.backend.metal.MetalTimeline;
import com.mojang.renderpearl.backend.metal.MslTranslator;
import com.mojang.renderpearl.backend.metal.MtlNative;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StrictJsonParser;
import org.lwjgl.system.MemoryUtil;

/**
 * I5 (java-pipeline) test. Run with tools/i5-pipeline-test.sh (builds the test dylib, sets MTL_DEBUG_LAYER=1).
 *
 * <ol>
 *   <li>Unit tests: the CONTRACT.md 6.1 slot scheme incl. both fallbacks, synthetic shaders compiled with the
 *       real GlslCompiler (push constants at buffer 14, cube and texel-buffer masks, vertex-only usage, kind and
 *       texel-format validation), Pending semantics (never throws).</li>
 *   <li>Every vanilla pipeline (RenderPipelines.requiredPipelines + optionalPipelines + every post-effect
 *       pass) through the real frontend (PipelineBuilder: GlslCompiler -> SPIR-V -> binding patching) on 8
 *       worker threads, into MetalRenderPipeline.prepare (structure checks: FLIP_VERTEX_Y, main0, every
 *       [[buffer/texture/sampler(n)]] of the MSL entry points equals the computed masks and the slot scheme)
 *       and into the real MetalDevice.compilePipeline -> ShaderCache -> mcmtl_shader_create (MSL 3.2,
 *       newLibraryWithSource) -> mcmtl_pipeline_create (PSO variants) of the test dylib.</li>
 *   <li>Per pipeline and precompiled variant: Metal reflection vs the masks the decoder will bind, and one
 *       draw with every masked slot bound the decoder's way (MTL_DEBUG_LAYER aborts on any mismatch).</li>
 *   <li>Pixel test: the real core/position_color shaders (debug_filled_box, cull on) draw a GL-CCW, a
 *       GL-CW and another GL-CCW triangle; read back: NDC y = -1 must be memory row 0 (FLIP_VERTEX_Y), CCW
 *       must survive back-face culling (Clockwise winding), CW must be culled, colors exact.</li>
 * </ol>
 */
public final class I5PipelineTest {
    // Checks also run on the frontend's compile workers: atomic counters.
    private static final AtomicInteger failures = new AtomicInteger();
    private static final AtomicInteger checks = new AtomicInteger();

    private static void check(boolean condition, String message) {
        checks.incrementAndGet();
        if (!condition) {
            failures.incrementAndGet();
            System.out.println("FAIL: " + message);
        }
    }

    private static final Linker LINKER = Linker.nativeLinker();
    private static MethodHandle I5_DEVICE_CREATE;
    private static MethodHandle I5_PIPELINE_REFLECT;
    private static MethodHandle I5_RENDER_SMOKE;
    private static MethodHandle I5_RENDER;

    public static void main(String[] args) throws Throwable {
        Locale.setDefault(Locale.ROOT);
        boolean nativeTests = System.getProperty("mcmetal.library") != null;
        installRenderSystemDevice();

        System.out.println("== unit tests");
        bindingSchemeTests();
        syntheticShaderTests();
        gameIntegrationTests();

        System.out.println("== vanilla pipelines");
        MetalDevice device = null;
        long deviceHandle = 0L;
        if (nativeTests) {
            MtlNative.Loader.load();
            List<String> abi = MtlNative.verifyAbi();
            check(abi.isEmpty(), "ABI mismatch: " + abi);
            SymbolLookup lookup = SymbolLookup.libraryLookup(Path.of(System.getProperty("mcmetal.library")), Arena.global());
            I5_DEVICE_CREATE = LINKER.downcallHandle(lookup.find("i5_device_create").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            I5_PIPELINE_REFLECT = LINKER.downcallHandle(lookup.find("i5_pipeline_reflect").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            I5_RENDER_SMOKE = LINKER.downcallHandle(lookup.find("i5_render_smoke").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
            I5_RENDER = LINKER.downcallHandle(lookup.find("i5_render").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(8);
                MemorySegment page = arena.allocate(8);
                int status = (int)I5_DEVICE_CREATE.invokeExact(out, page);
                check(status == 0, "i5_device_create " + status + " " + MtlNative.lastError());
                deviceHandle = out.get(ValueLayout.JAVA_LONG, 0);
                MemorySegment statusPage = MtlNative.statusPage(page.get(ValueLayout.JAVA_LONG, 0));
                device = MetalDevice.forTesting(deviceHandle, statusPage, new MetalTimeline(0L, MemorySegment.ofArray(new long[8])), null, true);
            }
        } else {
            System.out.println("(no -Dmcmetal.library: translation and structure checks only)");
        }

        VanillaRun run = compileVanilla(device);
        if (device != null) {
            nativeChecks(run, device);
            pendingSemanticsNative(run, device);
            shaderCacheClose(device, run);
        }

        System.out.printf("%d checks, %d failures%n", checks.get(), failures.get());
        System.exit(failures.get() == 0 ? 0 : 1);
    }

    // =============================================================================================
    // RenderSystem device (GlslCompiler reads RenderSystem.getDevice().getDeviceInfo())
    // =============================================================================================

    static final DeviceInfo DEVICE_INFO = MetalDevice.buildDeviceInfo("Apple M5 (test)", "i5 test", 1L << 34, 0x7F, 0);

    private static void installRenderSystemDevice() throws ReflectiveOperationException {
        GpuDevice proxy = (GpuDevice)Proxy.newProxyInstance(I5PipelineTest.class.getClassLoader(), new Class<?>[]{GpuDevice.class}, (p, method, a) -> {
            if (method.getName().equals("getDeviceInfo")) {
                return DEVICE_INFO;
            }

            if (method.getName().equals("toString")) {
                return "I5 test GpuDevice";
            }

            throw new UnsupportedOperationException(method.getName());
        });
        Field field = RenderSystem.class.getDeclaredField("DEVICE");
        field.setAccessible(true);
        field.set(null, proxy);
    }

    // =============================================================================================
    // Unit tests
    // =============================================================================================

    private static BindGroupLayout.UniformDescription ubo(String name) {
        return new BindGroupLayout.UniformDescription(name, UniformType.UNIFORM_BUFFER);
    }

    private static BindGroupLayout.UniformDescription tex(String name) {
        return new BindGroupLayout.UniformDescription(name, UniformType.COMBINED_IMAGE_SAMPLER);
    }

    private static BindGroupLayout.UniformDescription texel(String name, GpuFormat format) {
        return new BindGroupLayout.UniformDescription(name, format);
    }

    private static int[] slots(List<BindGroupLayout.UniformDescription> uniforms) throws MslTranslator.MslTranslationException {
        return MslTranslator.assignBindings(uniforms).slot();
    }

    private static void expectSlots(String what, List<BindGroupLayout.UniformDescription> uniforms, int... expected) {
        try {
            int[] actual = slots(uniforms);
            check(java.util.Arrays.equals(actual, expected), what + ": slots " + java.util.Arrays.toString(actual) + " expected " + java.util.Arrays.toString(expected));
        } catch (MslTranslator.MslTranslationException e) {
            check(false, what + ": unexpected failure " + e.getMessage());
        }
    }

    private static void expectFailure(String what, List<BindGroupLayout.UniformDescription> uniforms) {
        try {
            int[] actual = slots(uniforms);
            check(false, what + ": expected a failure, got " + java.util.Arrays.toString(actual));
        } catch (MslTranslator.MslTranslationException e) {
            check(true, what);
        }
    }

    private static void bindingSchemeTests() {
        // Terrain-like (spec 3.1 example order): fixed names win, others dense from 5 / 3.
        expectSlots("terrain", List.of(ubo("Globals"), ubo("Projection"), ubo("ChunkSection"), ubo("Fog"), tex("Sampler2"), tex("Sampler0")), 1, 0, 5, 2, 2, 0);
        expectSlots("entity", List.of(ubo("DynamicTransforms"), ubo("Projection"), ubo("Lighting"), ubo("Fog"), tex("Sampler0"), tex("Sampler1"), tex("Sampler2"), ubo("Globals")),
            4, 0, 3, 2, 0, 1, 2, 1);
        expectSlots("post", List.of(tex("InSampler"), ubo("SamplerInfo"), ubo("BlurConfig")), 3, 5, 6);
        expectSlots("clouds", List.of(ubo("Projection"), ubo("Fog"), ubo("CloudInfo"), texel("CloudFaces", GpuFormat.R8_SINT), ubo("DynamicTransforms")), 0, 2, 5, 3, 4);
        expectSlots("name space per kind", List.of(ubo("Sampler0"), tex("Projection")), 5, 3);

        // UBO fallback, decided on assigned indices: 9 non-fixed UBOs reach 13 (fine), 10 reach 14 (push constants).
        List<BindGroupLayout.UniformDescription> nine = new ArrayList<>();
        nine.add(ubo("Projection"));
        for (int i = 0; i < 9; i++) {
            nine.add(ubo("U" + i));
        }

        expectSlots("9 non-fixed UBOs stay hybrid", nine, 0, 5, 6, 7, 8, 9, 10, 11, 12, 13);
        List<BindGroupLayout.UniformDescription> ten = new ArrayList<>(nine);
        ten.add(ubo("U9"));
        expectSlots("10 non-fixed UBOs fall back to dense", ten, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        List<BindGroupLayout.UniformDescription> ten2 = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten2.add(ubo("U" + i));
        }

        ten2.add(1, tex("Sampler0"));
        ten2.add(ubo("Globals"));
        int[] s = null;
        try {
            s = slots(ten2);
        } catch (MslTranslator.MslTranslationException e) {
            check(false, "dense fallback with interleaved texture: " + e.getMessage());
        }

        if (s != null) {
            check(s[1] == 0 && s[0] == 0 && s[2] == 1 && s[11] == 10, "dense fallback keeps uniform-index order per kind: " + java.util.Arrays.toString(s));
        }

        List<BindGroupLayout.UniformDescription> fourteen = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            fourteen.add(ubo("U" + i));
        }

        expectSlots("14 UBOs dense 0..13", fourteen, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13);
        List<BindGroupLayout.UniformDescription> fifteen = new ArrayList<>(fourteen);
        fifteen.add(ubo("U14"));
        expectFailure("15 UBOs cannot fit 0..13", fifteen);

        // Texture fallback: Sampler0 + 13 others reach 15 (fine); + 14 others reach 16 -> dense.
        List<BindGroupLayout.UniformDescription> textures = new ArrayList<>();
        textures.add(tex("Sampler0"));
        for (int i = 0; i < 13; i++) {
            textures.add(tex("T" + i));
        }

        int[] hybrid = new int[14];
        hybrid[0] = 0;
        for (int i = 1; i < 14; i++) {
            hybrid[i] = i + 2;
        }

        expectSlots("Sampler0 + 13 textures stay hybrid", textures, hybrid);
        textures.add(texel("T13", GpuFormat.R8_SINT));
        int[] dense = new int[15];
        for (int i = 0; i < 15; i++) {
            dense[i] = i;
        }

        expectSlots("Sampler0 + 14 textures (incl. a texel buffer) fall back to dense", textures, dense);
        List<BindGroupLayout.UniformDescription> seventeen = new ArrayList<>(List.of(tex("Sampler0"), tex("Sampler1"), tex("Sampler2")));
        for (int i = 0; i < 14; i++) {
            seventeen.add(tex("T" + i));
        }

        expectFailure("Sampler0..2 + 14 textures cannot fit 0..15", seventeen);
        List<BindGroupLayout.UniformDescription> many = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            many.add(i < 10 ? ubo("U" + i) : tex("T" + i));
        }

        expectFailure("more than 32 uniforms", many);
    }

    // ---- synthetic shaders through the real GlslCompiler -------------------------------------

    private static final ShaderSource NO_INCLUDES = new ShaderSource() {
        @Override
        public String getShader(Identifier id, ShaderType type) {
            return null;
        }

        @Override
        public CachedIncludeSource getInclude(Identifier id) {
            return null;
        }

        @Override
        public void close() {
        }
    };

    private static GlslCompiler glsl;

    private static SpvModule compileGlsl(String name, String source, ShaderType type) throws Exception {
        if (glsl == null) {
            glsl = new GlslCompiler(true, true);
        }

        return glsl.compileToSpv(name, source, type, ShaderDefines.EMPTY, NO_INCLUDES);
    }

    /** PipelineBuilder's binding patching: binding = first-seen index by name, set 0. */
    private static List<BindGroupLayout.UniformDescription> patch(List<SpvModule> modules, Map<String, BindGroupLayout.UniformDescription> declared) throws Exception {
        Map<String, Integer> bindings = new LinkedHashMap<>();
        List<BindGroupLayout.UniformDescription> uniforms = new ArrayList<>();
        for (SpvModule module : modules) {
            for (SpvModule.Reflection.Descriptor descriptor : module.reflect().descriptors()) {
                Integer binding = bindings.get(descriptor.name());
                if (binding == null) {
                    binding = bindings.size();
                    bindings.put(descriptor.name(), binding);
                    uniforms.add(declared.get(descriptor.name()));
                }

                descriptor.binding(binding);
                descriptor.descriptorSetIndex(0);
            }
        }

        return uniforms;
    }

    private static final String HEADER = "#version 330\n#extension GL_ARB_separate_shader_objects : require\n";
    private static final String TRIVIAL_FS = HEADER + "layout(location = 0) out vec4 fragColor;\nvoid main() { fragColor = vec4(1.0); }\n";

    private static void syntheticShaderTests() throws Exception {
        // (a) 10 non-fixed UBOs + Projection: hybrid would put the last on 14 (push constants) -> dense 0..10.
        StringBuilder vs = new StringBuilder(HEADER);
        vs.append("layout(std140) uniform Projection { mat4 ProjMat; };\n");
        Map<String, BindGroupLayout.UniformDescription> declared = new HashMap<>();
        declared.put("Projection", ubo("Projection"));
        StringBuilder sum = new StringBuilder("vec4(0.0)");
        for (int i = 0; i < 10; i++) {
            vs.append("layout(std140) uniform U").append(i).append(" { vec4 v").append(i).append("; };\n");
            declared.put("U" + i, ubo("U" + i));
            sum.append(" + v").append(i);
        }

        vs.append("layout(location = 0) in vec3 Position;\nvoid main() { gl_Position = ProjMat * vec4(Position, 1.0) + ").append(sum).append("; }\n");
        SpvModule v = compileGlsl("test:ten_ubos.vsh", vs.toString(), ShaderType.VERTEX);
        SpvModule f = compileGlsl("test:trivial.fsh", TRIVIAL_FS, ShaderType.FRAGMENT);
        List<BindGroupLayout.UniformDescription> uniforms = patch(List.of(v, f), declared);
        MslTranslator.BindingTable table = MslTranslator.assignBindings(uniforms);
        MslTranslator.StageResult vr = MslTranslator.translate(v, uniforms, table, 0);
        check(vr.bufferMask() == 0x7FF, "ten UBOs: dense buffer mask 0x" + Integer.toHexString(vr.bufferMask()));
        Set<Integer> bufferIndices = indices(vr.msl(), "buffer");
        check(bufferIndices.equals(range(0, 11)), "ten UBOs: MSL buffer indices " + bufferIndices);
        check(vr.msl().contains("gl_Position.y = -(out.gl_Position.y)"), "ten UBOs: FLIP_VERTEX_Y in vertex MSL");

        // (b) push constants -> [[buffer(14)]]; (c) cube + texel buffer; (f) vertex-only sampler.
        String pushVs = HEADER
            + "layout(push_constant) uniform PC { vec4 offset; } pc;\n"
            + "uniform sampler2D Sampler2;\n"
            + "layout(location = 0) in vec3 Position;\nlayout(location = 0) out vec4 light;\n"
            + "void main() { gl_Position = vec4(Position, 1.0) + pc.offset; light = texelFetch(Sampler2, ivec2(0), 0); }\n";
        String cubeFs = HEADER
            + "uniform samplerCube Panorama;\nuniform isamplerBuffer Faces;\nuniform sampler2D Sampler0;\n"
            + "layout(location = 0) in vec4 light;\nlayout(location = 0) out vec4 fragColor;\n"
            + "void main() { fragColor = light * texture(Panorama, vec3(1.0, 0.0, 0.0)) * float(texelFetch(Faces, 3).r); }\n";
        SpvModule pv = compileGlsl("test:push.vsh", pushVs, ShaderType.VERTEX);
        SpvModule cf = compileGlsl("test:cube.fsh", cubeFs, ShaderType.FRAGMENT);
        Map<String, BindGroupLayout.UniformDescription> declared2 = Map.of(
            "Sampler2", tex("Sampler2"), "Panorama", tex("Panorama"), "Faces", texel("Faces", GpuFormat.R8_SINT), "Sampler0", tex("Sampler0"));
        List<BindGroupLayout.UniformDescription> uniforms2 = patch(List.of(pv, cf), declared2);
        BackendRenderPipeline.CreateInfo info2 = new BackendRenderPipeline.CreateInfo(
            "test:push_cube",
            List.of(new BackendRenderPipeline.CreateInfo.Shader("test:push.vsh", "main", pv), new BackendRenderPipeline.CreateInfo.Shader("test:cube.fsh", "main", cf)),
            List.of(new BackendRenderPipeline.CreateInfo.VertexBuffer(0, 12, 0)),
            List.of(new BackendRenderPipeline.CreateInfo.AttribBinding(0, 0, 0, GpuFormat.RGB32_FLOAT)),
            uniforms2, 16, null, PolygonMode.FILL, true, List.of(ColorTargetState.DEFAULT), PrimitiveTopology.TRIANGLES);
        MetalRenderPipeline.Prepared prepared = MetalRenderPipeline.prepare(info2);
        int sampler2 = indexOf(uniforms2, "Sampler2");
        int panorama = indexOf(uniforms2, "Panorama");
        int faces = indexOf(uniforms2, "Faces");
        int sampler0 = find(uniforms2, "Sampler0");
        check(prepared.vertex.usesPushConstants() && (prepared.bufferMask(0) & 1 << 14) != 0, "push constants used at [[buffer(14)]] in the vertex stage");
        check(prepared.pushConstantStages() == MtlNative.STAGE_VERTEX, "push-constant stages = vertex");
        check(indices(prepared.vertex.msl(), "buffer").contains(14), "vertex MSL binds [[buffer(14)]]");
        check(prepared.uniformStages()[sampler2] == MtlNative.STAGE_VERTEX, "Sampler2 is vertex-only");
        check(prepared.bindings.slot()[sampler2] == 2 && (prepared.textureMask(0) & 1 << 2) != 0 && (prepared.samplerMask(0) & 1 << 2) != 0, "Sampler2 at texture/sampler 2 in the vertex stage");
        check(sampler0 < 0 || prepared.bindings.slot()[sampler0] == 0, "Sampler0 at slot 0");
        int panoramaBit = 1 << prepared.bindings.slot()[panorama];
        int facesBit = 1 << prepared.bindings.slot()[faces];
        check((prepared.textureCubeMask(1) & panoramaBit) != 0 && (prepared.samplerMask(1) & panoramaBit) != 0, "Panorama is a cube texture with a sampler");
        check((prepared.texelBufferMask(1) & facesBit) != 0 && (prepared.samplerMask(1) & facesBit) == 0, "Faces is a texel buffer without a sampler");
        check(prepared.fragment.msl().contains("texturecube<float> Panorama") && prepared.fragment.msl().contains("texture_buffer<int> Faces"), "cube and texture_buffer MSL types");
        System.out.println("declared-but-unused Sampler0 " + (sampler0 < 0 ? "not emitted by glslang" : "kept in SPIR-V"));
        check(sampler0 < 0 || prepared.uniformStages()[sampler0] == 0 && (prepared.textureMask(1) & 1) == 0, "declared-but-unused Sampler0 is dropped (stages 0, not in masks)");
        MetalRenderPipeline forTest = prepared.toPipelineForTesting(null);
        check(forTest.texelPixelFormat[faces] == MetalFormats.PIXEL_FORMAT_R8_SINT && forTest.texelBytesPerTexel[faces] == 1, "texel format table");
        check(forTest.usedUniformMask == ((1 << sampler2) | (1 << panorama) | (1 << faces)), "usedUniformMask 0x" + Integer.toHexString(forTest.usedUniformMask));
        check(forTest.vertexBufferSlotMask == 1 && prepared.requiredVertexBufferMask() == 1 << 30 && (prepared.bufferMask(0) & 1 << 30) != 0, "vertex buffer slot 0 -> [[buffer(30)]]");
        check(!forTest.isClosed(), "fresh pipeline open");
        forTest.close();
        forTest.close();
        check(forTest.isClosed(), "close() idempotent (handle 0: no deferred release)");

        // (d) kind mismatch, (e) texel element type mismatch, (g) unaligned attribute -> Pending.NULL, never throws.
        List<BindGroupLayout.UniformDescription> wrongKind = new ArrayList<>(uniforms2);
        wrongKind.set(panorama, ubo("Panorama"));
        expectTranslationFailure("sampler declared as UBO", cf, wrongKind);
        List<BindGroupLayout.UniformDescription> wrongFormat = new ArrayList<>(uniforms2);
        wrongFormat.set(faces, texel("Faces", GpuFormat.R8_UNORM));
        expectTranslationFailure("isamplerBuffer with an UNORM texel format", cf, wrongFormat);
        BackendRenderPipeline.CreateInfo unaligned = new BackendRenderPipeline.CreateInfo(
            info2.name(), info2.shaders(), List.of(new BackendRenderPipeline.CreateInfo.VertexBuffer(0, 16, 0)),
            List.of(new BackendRenderPipeline.CreateInfo.AttribBinding(0, 0, 2, GpuFormat.RGB32_FLOAT)),
            uniforms2, 16, null, PolygonMode.FILL, true, List.of(ColorTargetState.DEFAULT), PrimitiveTopology.TRIANGLES);
        MetalDevice headless = MetalDevice.forTesting(0L, MemorySegment.ofArray(new long[8]), new MetalTimeline(0L, MemorySegment.ofArray(new long[8])), null, false);
        BackendRenderPipeline.Pending pending = MetalRenderPipeline.compile(headless, unaligned);
        check(pending == BackendRenderPipeline.Pending.NULL, "attribute offset 2 -> Pending.NULL");
        check(MetalRenderPipeline.compile(headless, null) == BackendRenderPipeline.Pending.NULL, "null CreateInfo -> Pending.NULL");
        BackendRenderPipeline.CreateInfo rgbTarget = new BackendRenderPipeline.CreateInfo(
            info2.name(), info2.shaders(), info2.vertexBuffers(), info2.attribBindings(), uniforms2, 16, null, PolygonMode.FILL, true,
            List.of(new ColorTargetState(Optional.empty(), GpuFormat.RGB8_UNORM, 15)), PrimitiveTopology.TRIANGLES);
        check(MetalRenderPipeline.compile(headless, rgbTarget) == BackendRenderPipeline.Pending.NULL, "RGB8 color target -> Pending.NULL");
        v.close();
        f.close();
        pv.close();
        cf.close();
    }

    /**
     * Game wiring (CONTRACT.md 10): PreferredGraphicsApi.METAL, its codec name and lang key in the built jar (Options
     * lists PreferredGraphicsApi.values() through OptionInstance.Enum, Options.java:152, and captions with
     * Component.translatable(key)), the backend order, and the patched Minecraft class being the one in the jar.
     */
    private static void gameIntegrationTests() throws IOException {
        net.minecraft.client.PreferredGraphicsApi metal = net.minecraft.client.PreferredGraphicsApi.METAL;
        check(List.of(net.minecraft.client.PreferredGraphicsApi.values()).contains(metal), "PreferredGraphicsApi.values() lists METAL (options cycle)");
        check(metal.getSerializedName().equals("metal"), "METAL serializes as \"metal\"");
        check(net.minecraft.client.PreferredGraphicsApi.CODEC.parse(JsonOps.INSTANCE, new com.google.gson.JsonPrimitive("metal")).getOrThrow() == metal,
            "options.txt value \"metal\" parses");
        check(metal.caption().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t && t.getKey().equals("options.graphicsApi.metal"),
            "METAL caption is translatable options.graphicsApi.metal");
        String lang = resource("assets/minecraft/lang/en_us.json");
        com.google.gson.JsonObject langJson = com.google.gson.JsonParser.parseString(lang).getAsJsonObject();
        for (net.minecraft.client.PreferredGraphicsApi api : net.minecraft.client.PreferredGraphicsApi.values()) {
            String key = ((net.minecraft.network.chat.contents.TranslatableContents)api.caption().getContents()).getKey();
            check(langJson.has(key), "en_us.json in the built jar has " + key);
        }

        check("Metal".equals(langJson.get("options.graphicsApi.metal").getAsString()), "options.graphicsApi.metal renders as \"Metal\"");
        check(langJson.size() > 6000, "en_us.json is the merged vanilla file (" + langJson.size() + " keys)");
        boolean mac = net.minecraft.util.Util.getPlatform() == net.minecraft.util.Util.OS.OSX;
        for (net.minecraft.client.PreferredGraphicsApi api : net.minecraft.client.PreferredGraphicsApi.values()) {
            List<String> order = new ArrayList<>();
            for (com.mojang.renderpearl.api.device.GpuBackend backend : api.getBackendsToTry()) {
                order.add(backend.getName());
            }

            List<String> expected = switch (api) {
                case DEFAULT, METAL -> mac ? List.of("Metal", "Vulkan", "OpenGL") : List.of("OpenGL", "Vulkan");
                case VULKAN -> List.of("Vulkan", "OpenGL");
                case OPENGL -> List.of("OpenGL", "Vulkan");
            };
            check(order.equals(expected), api + " tries " + order + ", expected " + expected);
        }

        try (InputStream in = I5PipelineTest.class.getClassLoader().getResourceAsStream("net/minecraft/client/Minecraft.class")) {
            String constants = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            check(constants.contains("com/mojang/renderpearl/backend/metal/MetalBackend"), "build/client.jar carries the patched Minecraft.class");
        }
    }

    private static void expectTranslationFailure(String what, SpvModule module, List<BindGroupLayout.UniformDescription> uniforms) {
        try {
            MslTranslator.translate(module, uniforms, MslTranslator.assignBindings(uniforms), 16);
            check(false, what + ": expected MslTranslationException");
        } catch (MslTranslator.MslTranslationException e) {
            check(true, what);
        }
    }

    private static int find(List<BindGroupLayout.UniformDescription> uniforms, String name) {
        for (int i = 0; i < uniforms.size(); i++) {
            if (uniforms.get(i).name().equals(name)) {
                return i;
            }
        }

        return -1;
    }

    private static int indexOf(List<BindGroupLayout.UniformDescription> uniforms, String name) {
        for (int i = 0; i < uniforms.size(); i++) {
            if (uniforms.get(i).name().equals(name)) {
                return i;
            }
        }

        throw new IllegalArgumentException(name);
    }

    private static Set<Integer> range(int from, int to) {
        Set<Integer> set = new HashSet<>();
        for (int i = from; i < to; i++) {
            set.add(i);
        }

        return set;
    }

    private static final Pattern ATTRIBUTE = Pattern.compile("\\[\\[(buffer|texture|sampler)\\((\\d+)\\)\\]\\]");

    /** Every [[kind(n)]] in the MSL (only entry-point parameters carry them). */
    private static Set<Integer> indices(String msl, String kind) {
        Set<Integer> set = new HashSet<>();
        Matcher m = ATTRIBUTE.matcher(msl);
        while (m.find()) {
            if (m.group(1).equals(kind)) {
                set.add(Integer.parseInt(m.group(2)));
            }
        }

        return set;
    }

    private static int mask(Set<Integer> set) {
        int mask = 0;
        for (int i : set) {
            mask |= 1 << i;
        }

        return mask;
    }

    // =============================================================================================
    // Vanilla pipelines through the real frontend
    // =============================================================================================

    record Compiled(String name, MetalRenderPipeline.Prepared prepared, MetalRenderPipeline pipeline, boolean hasDepthState, int[] attribOffsets,
                    BackendRenderPipeline.CreateInfo info) {
    }

    static final class VanillaRun {
        final List<Compiled> compiled = Collections.synchronizedList(new ArrayList<>());
        final Map<String, Boolean> uniqueVertexMsl = new ConcurrentHashMap<>();
        final Map<String, Boolean> uniqueFragmentMsl = new ConcurrentHashMap<>();
        final AtomicInteger frontendFailures = new AtomicInteger();
        final AtomicInteger prepareFailures = new AtomicInteger();
        final AtomicInteger compileFailures = new AtomicInteger();
        final AtomicLong translateNanos = new AtomicLong();
        final AtomicLong compileNanos = new AtomicLong();
        final Set<String> flipChecked = ConcurrentHashMap.newKeySet();
        volatile Compiled cullTestPipeline;
        volatile BackendRenderPipeline.CreateInfo sampleInfo;
    }

    private static ShaderSource jarShaderSource() {
        Map<Identifier, ShaderSource.CachedIncludeSource> includes = new ConcurrentHashMap<>();
        return new ShaderSource() {
            @Override
            public String getShader(Identifier id, ShaderType type) {
                return resource("assets/" + id.getNamespace() + "/shaders/" + id.getPath() + (type == ShaderType.VERTEX ? ".vsh" : ".fsh"));
            }

            @Override
            public CachedIncludeSource getInclude(Identifier id) {
                return includes.computeIfAbsent(id, key -> {
                    String source = resource("assets/" + key.getNamespace() + "/shaders/include/" + key.getPath());
                    return source == null ? null : CachedIncludeSource.create(key, source);
                });
            }

            @Override
            public void close() {
                includes.values().forEach(CachedIncludeSource::close);
            }
        };
    }

    private static String resource(String path) {
        try (InputStream in = I5PipelineTest.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(path, e);
        }
    }

    private static List<RenderPipeline> postEffectPipelines() {
        List<RenderPipeline> pipelines = new ArrayList<>();
        for (String effect : List.of("blur", "creeper", "entity_outline", "invert", "spider")) {
            String json = resource("assets/minecraft/post_effect/" + effect + ".json");
            check(json != null, "post effect " + effect + " present");
            if (json == null) {
                continue;
            }

            JsonElement element = StrictJsonParser.parse(json);
            PostChainConfig config = PostChainConfig.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();
            for (int i = 0; i < config.passes().size(); i++) {
                PostChainConfig.Pass pass = config.passes().get(i);
                // Mirror of PostChain.createPass.
                RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                    .withFragmentShader(pass.fragmentShaderId())
                    .withVertexShader(pass.vertexShaderId())
                    .withLocation(Identifier.withDefaultNamespace("post_effect/" + effect + "/" + i));
                BindGroupLayout.Builder layout = BindGroupLayout.builder();
                for (PostChainConfig.Input input : pass.inputs()) {
                    layout.withUniform(input.samplerName() + "Sampler", UniformType.COMBINED_IMAGE_SAMPLER);
                }

                layout.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER);
                for (String block : pass.uniforms().keySet()) {
                    layout.withUniform(block, UniformType.UNIFORM_BUFFER);
                }

                builder.withBindGroupLayout(layout.build());
                builder.withColorTargetState(ColorTargetState.DEFAULT);
                pipelines.add(builder.build());
            }
        }

        return pipelines;
    }

    private static VanillaRun compileVanilla(MetalDevice device) throws Exception {
        VanillaRun run = new VanillaRun();
        List<RenderPipeline> pipelines = new ArrayList<>();
        pipelines.addAll(RenderPipelines.requiredPipelines());
        pipelines.addAll(RenderPipelines.optionalPipelines());
        int staticCount = pipelines.size();
        pipelines.addAll(postEffectPipelines());
        System.out.printf("pipelines: %d required+optional, %d post-effect passes%n", staticCount, pipelines.size() - staticCount);

        GpuDeviceBackend backend = (GpuDeviceBackend)Proxy.newProxyInstance(
            I5PipelineTest.class.getClassLoader(), new Class<?>[]{GpuDeviceBackend.class}, (p, method, a) -> switch (method.getName()) {
                case "getDeviceInfo" -> DEVICE_INFO;
                case "compilePipeline" -> backendCompile(run, device, (BackendRenderPipeline.CreateInfo)a[0]);
                case "toString" -> "I5 test backend";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        ExecutorService workers = Executors.newFixedThreadPool(8);
        long start = System.nanoTime();
        try (PipelineBuilder builder = new PipelineBuilder(backend); ShaderSource source = jarShaderSource()) {
            List<CompletableFuture<CompiledRenderPipeline.Pending>> futures = new ArrayList<>();
            for (RenderPipeline pipeline : pipelines) {
                futures.add(builder.compilePipeline(pipeline, source, workers));
            }

            for (CompletableFuture<CompiledRenderPipeline.Pending> future : futures) {
                CompiledRenderPipeline.Pending pending = future.join();
                // finishCompile closes the SPIR-V modules (on the executor) and wraps the backend pipeline.
                CompiledRenderPipeline compiled = pending.finishCompile();
                if (compiled == null && device != null) {
                    run.frontendFailures.incrementAndGet();
                }
            }
        } finally {
            workers.shutdown();
        }

        long wall = System.nanoTime() - start;
        int pipelinesSeen = run.compiled.size() + run.prepareFailures.get() + run.compileFailures.get();
        System.out.printf(Locale.ROOT, "frontend+translate%s: %d pipelines in %.0f ms wall (spvc %.1f ms total, %.2f ms/pipeline%s)%n",
            device != null ? "+Metal compile" : "", pipelinesSeen, wall / 1e6, run.translateNanos.get() / 1e6,
            run.translateNanos.get() / 1e6 / Math.max(1, pipelinesSeen),
            device != null ? String.format(Locale.ROOT, ", native compile %.0f ms summed over workers", run.compileNanos.get() / 1e6) : "");
        System.out.printf("unique MSL: %d vertex, %d fragment; prepare failures %d, compile failures %d%n",
            run.uniqueVertexMsl.size(), run.uniqueFragmentMsl.size(), run.prepareFailures.get(), run.compileFailures.get());
        int instanced = 0, fans = 0, points = 0, wireframe = 0, biased = 0, noDepth = 0, cube = 0, texel = 0, multiTarget = 0, noTarget = 0;
        for (Compiled c : run.compiled) {
            BackendRenderPipeline.CreateInfo info = c.info();
            boolean perInstance = info.vertexBuffers().stream().anyMatch(b -> b.stepRate() > 0);
            if (perInstance) {
                instanced++;
                for (BackendRenderPipeline.CreateInfo.VertexBuffer b : info.vertexBuffers()) {
                    if (b.stepRate() > 0) {
                        check((c.prepared().requiredVertexBufferMask() & 1 << (30 - b.bufferSlot())) != 0, c.name() + ": instanced slot " + b.bufferSlot() + " required");
                    }
                }
            }

            fans += info.primitiveTopology() == PrimitiveTopology.TRIANGLE_FAN ? 1 : 0;
            points += info.primitiveTopology() == PrimitiveTopology.POINTS ? 1 : 0;
            wireframe += info.polygonMode() == PolygonMode.WIREFRAME ? 1 : 0;
            DepthStencilState dss = info.depthStencilState();
            biased += dss != null && (dss.depthBiasConstant() != 0 || dss.depthBiasScaleFactor() != 0) ? 1 : 0;
            noDepth += dss == null ? 1 : 0;
            cube += c.prepared().textureCubeMask(0) != 0 || c.prepared().textureCubeMask(1) != 0 ? 1 : 0;
            texel += c.prepared().texelBufferMask(0) != 0 || c.prepared().texelBufferMask(1) != 0 ? 1 : 0;
            multiTarget += info.colorTargetStates().size() > 1 ? 1 : 0;
            noTarget += info.colorTargetStates().isEmpty() ? 1 : 0;
        }

        System.out.printf("coverage: %d per-instance, %d triangle-fan, %d point, %d wireframe, %d depth-bias, %d no-depth-state, %d cube, %d texel-buffer, "
            + "%d multi-target, %d depth-only pipelines%n", instanced, fans, points, wireframe, biased, noDepth, cube, texel, multiTarget, noTarget);
        check(instanced > 0 && fans > 0 && points > 0 && wireframe > 0 && biased > 0 && noDepth > 0 && cube > 0 && texel > 0 && multiTarget > 0 && noTarget > 0,
            "every special pipeline class is covered");
        check(pipelinesSeen == pipelines.size(), "every pipeline reached the backend (" + pipelinesSeen + "/" + pipelines.size() + ")");
        check(run.prepareFailures.get() == 0, "every vanilla pipeline translates");
        check(run.compileFailures.get() == 0, "every vanilla pipeline compiles to Metal");
        check(run.frontendFailures.get() == 0, "frontend got every backend pipeline");
        if (device != null) {
            int functions = device.shaderCache().size();
            System.out.printf("shader cache: %d functions (= unique MSL %d)%n", functions, run.uniqueVertexMsl.size() + run.uniqueFragmentMsl.size());
            check(functions == run.uniqueVertexMsl.size() + run.uniqueFragmentMsl.size(), "shader cache dedupes by MSL exactly");
        }

        return run;
    }

    private static final Pattern UBO_PARAM = Pattern.compile("constant (\\w+)& \\w+ \\[\\[buffer\\((\\d+)\\)\\]\\]");
    private static final Pattern TEX_PARAM = Pattern.compile("(?:texture2d|texturecube|texture_buffer)<\\w+> (\\w+) \\[\\[texture\\((\\d+)\\)\\]\\]");
    private static final Pattern SMP_PARAM = Pattern.compile("sampler (\\w+)Smplr \\[\\[sampler\\((\\d+)\\)\\]\\]");

    private static BackendRenderPipeline.Pending backendCompile(VanillaRun run, MetalDevice device, BackendRenderPipeline.CreateInfo info) {
        long t0 = System.nanoTime();
        MetalRenderPipeline.Prepared prepared;
        try {
            prepared = MetalRenderPipeline.prepare(info);
        } catch (MslTranslator.MslTranslationException e) {
            run.prepareFailures.incrementAndGet();
            check(false, info.name() + ": prepare failed: " + e.getMessage());
            return BackendRenderPipeline.Pending.NULL;
        }

        run.translateNanos.addAndGet(System.nanoTime() - t0);
        String dump = System.getProperty("i5.dumpMsl");
        if (dump != null) {
            try {
                Path dir = Path.of(dump);
                java.nio.file.Files.createDirectories(dir);
                String file = info.name().replace(':', '_').replace('/', '_');
                java.nio.file.Files.writeString(dir.resolve(file + ".vert.metal"), prepared.vertex.msl());
                java.nio.file.Files.writeString(dir.resolve(file + ".frag.metal"), prepared.fragment.msl());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        structureChecks(info, prepared);
        run.uniqueVertexMsl.put(sha(prepared.vertex.msl()), Boolean.TRUE);
        // Terrain pipelines compile one function per UseRgss specialization (TerrainShaderVariants).
        String[] fv = com.mojang.renderpearl.backend.metal.TerrainShaderVariants.fragmentVariants(prepared.fragmentShader, prepared.fragment.msl());
        for (String fragmentMsl : fv) {
            run.uniqueFragmentMsl.put(sha(fragmentMsl), Boolean.TRUE);
        }
        // Round 11 (CONTRACT.md 16.R11): the default variant adds a vertex and two fragment functions per terrain pipeline.
        if (fv.length == 2) {
            String[] v = com.mojang.renderpearl.backend.metal.TerrainShaderVariants.variant(info.name(), prepared.vertex.msl(), fv[0]);
            if (v != null) {
                run.uniqueVertexMsl.put(sha(v[0]), Boolean.TRUE);
                run.uniqueFragmentMsl.put(sha(v[1]), Boolean.TRUE);
                String[] r = com.mojang.renderpearl.backend.metal.TerrainShaderVariants.rgssVariant(prepared.vertex.msl(), fv[1]);
                if (r != null) {
                    run.uniqueVertexMsl.put(sha(r[0]), Boolean.TRUE);
                    run.uniqueFragmentMsl.put(sha(r[1]), Boolean.TRUE);
                }
            }
        }

        int[] offsets = info.attribBindings().stream().mapToInt(BackendRenderPipeline.CreateInfo.AttribBinding::offset).toArray();
        if (device == null) {
            run.compiled.add(new Compiled(info.name(), prepared, null, info.depthStencilState() != null, offsets, info));
            return BackendRenderPipeline.Pending.NULL;
        }

        long t1 = System.nanoTime();
        BackendRenderPipeline.Pending pending = device.compilePipeline(info);
        BackendRenderPipeline result = pending.finishCompile();
        run.compileNanos.addAndGet(System.nanoTime() - t1);
        if (!(result instanceof MetalRenderPipeline pipeline)) {
            run.compileFailures.incrementAndGet();
            check(false, info.name() + ": Metal compile failed (see log)");
            return pending;
        }

        tableChecks(info, prepared, pipeline);
        run.compiled.add(new Compiled(info.name(), prepared, pipeline, info.depthStencilState() != null, offsets, info));
        if (run.sampleInfo == null) {
            run.sampleInfo = info;
        }

        if (info.name().equals("minecraft:pipeline/debug_filled_box")) {
            // Same shaders and state with culling on: the pixel test needs back-face culling.
            BackendRenderPipeline.CreateInfo culled = new BackendRenderPipeline.CreateInfo(
                info.name() + "#cull", info.shaders(), info.vertexBuffers(), info.attribBindings(), info.uniforms(), info.pushConstantsSize(),
                info.depthStencilState(), info.polygonMode(), true, info.colorTargetStates(), PrimitiveTopology.TRIANGLES);
            BackendRenderPipeline cullPipeline = device.compilePipeline(culled).finishCompile();
            check(cullPipeline instanceof MetalRenderPipeline, "debug_filled_box#cull compiles");
            if (cullPipeline instanceof MetalRenderPipeline mp) {
                try {
                    run.cullTestPipeline = new Compiled(culled.name(), MetalRenderPipeline.prepare(culled), mp, true, offsets, culled);
                } catch (MslTranslator.MslTranslationException e) {
                    check(false, "prepare #cull: " + e.getMessage());
                }
            }
        }

        return () -> result;
    }

    private static void structureChecks(BackendRenderPipeline.CreateInfo info, MetalRenderPipeline.Prepared prepared) {
        String name = info.name();
        check(prepared.vertex.entryPoint().equals("main0") && prepared.fragment.entryPoint().equals("main0"), name + ": entry points main0");
        check(prepared.vertex.msl().contains("out.gl_Position.y = -(out.gl_Position.y);"), name + ": FLIP_VERTEX_Y missing in vertex MSL");
        check(!prepared.fragment.msl().contains("gl_Position.y = -("), name + ": fragment MSL must not flip");
        check(prepared.vertex.msl().contains("#include <metal_stdlib>") && prepared.vertex.msl().contains("vertex main0_out main0("), name + ": vertex entry");
        check(prepared.fragment.msl().contains("fragment main0_out main0(") || prepared.fragment.msl().contains("fragment void main0("), name + ": fragment entry");
        MslTranslator.StageResult[] stages = {prepared.vertex, prepared.fragment};
        for (int s = 0; s < 2; s++) {
            String msl = stages[s].msl();
            String stage = s == 0 ? "vertex" : "fragment";
            int vertexBuffers = s == 0 ? prepared.requiredVertexBufferMask() : 0;
            // CONTRACT.md 16.R11: terrain pipelines with the per-vertex fog variant also bind Fog (buffer 2) in the vertex
            // stage for the variant PSOs; the exact vertex MSL does not read it. No other pipeline may get it.
            boolean fogForVariant = s == 0 && com.mojang.renderpearl.backend.metal.TerrainShaderVariants.TERRAIN_FRAGMENT.equals(prepared.fragmentShader)
                && com.mojang.renderpearl.backend.metal.TerrainShaderVariants.vertexNeedsFog(name) && prepared.fragment.msl().contains("constant Fog&");
            if (fogForVariant) {
                vertexBuffers |= 1 << com.mojang.renderpearl.backend.metal.TerrainShaderVariants.FOG_SLOT;
                check((prepared.bufferMask(s) & 1 << com.mojang.renderpearl.backend.metal.TerrainShaderVariants.FOG_SLOT) != 0, name + ": vertex stage binds Fog for the round-11 variant");
            }
            // Round 12: vertex-pulling functions (TerrainVertexMsl) name their vertex buffers as [[buffer(n)]] too.
            check((mask(indices(msl, "buffer")) & ~vertexBuffers) == (prepared.bufferMask(s) & ~vertexBuffers), name + " " + stage + ": MSL buffer indices " + indices(msl, "buffer")
                + " vs mask 0x" + Integer.toHexString(prepared.bufferMask(s)));
            check(mask(indices(msl, "texture")) == prepared.textureMask(s), name + " " + stage + ": MSL texture indices " + indices(msl, "texture"));
            check(mask(indices(msl, "sampler")) == prepared.samplerMask(s), name + " " + stage + ": MSL sampler indices " + indices(msl, "sampler"));
            // Slot scheme by name (CONTRACT.md 6.1).
            Matcher m = UBO_PARAM.matcher(msl);
            while (m.find()) {
                int index = find(info.uniforms(), m.group(1));
                int slot = Integer.parseInt(m.group(2));
                check(index >= 0 && prepared.bindings.slot()[index] == slot, name + ": UBO " + m.group(1) + " at buffer " + slot);
                int fixed = MslTranslator.FIXED_BUFFER_NAMES.indexOf(m.group(1));
                check(fixed < 0 || fixed == slot, name + ": fixed UBO " + m.group(1) + " must be at " + fixed);
                check(fixed >= 0 || slot >= MslTranslator.FIRST_DENSE_BUFFER_SLOT, name + ": non-fixed UBO " + m.group(1) + " below 5");
            }

            m = TEX_PARAM.matcher(msl);
            while (m.find()) {
                int index = find(info.uniforms(), m.group(1));
                int slot = Integer.parseInt(m.group(2));
                check(index >= 0 && prepared.bindings.slot()[index] == slot, name + ": texture " + m.group(1) + " at " + slot);
                int fixed = MslTranslator.FIXED_TEXTURE_NAMES.indexOf(m.group(1));
                check(fixed < 0 || fixed == slot, name + ": fixed texture " + m.group(1) + " must be at " + fixed);
            }

            m = SMP_PARAM.matcher(msl);
            while (m.find()) {
                int index = find(info.uniforms(), m.group(1));
                check(index >= 0 && prepared.bindings.slot()[index] == Integer.parseInt(m.group(2)), name + ": sampler " + m.group(1));
            }
        }

        check(prepared.pushConstantStages() == 0, name + ": vanilla uses no push constants");
    }

    private static void tableChecks(BackendRenderPipeline.CreateInfo info, MetalRenderPipeline.Prepared prepared, MetalRenderPipeline pipeline) {
        String name = info.name();
        check(pipeline.handle() != 0L, name + ": native handle");
        check(pipeline.uniformCount == info.uniforms().size(), name + ": uniformCount");
        for (int i = 0; i < pipeline.uniformCount; i++) {
            check(pipeline.uniformName[i].equals(info.uniforms().get(i).name()), name + ": uniformName " + i);
            check(pipeline.uniformKind[i] == MslTranslator.kindOf(info.uniforms().get(i)), name + ": uniformKind " + i);
            check(pipeline.uniformSlot[i] == prepared.bindings.slot()[i], name + ": uniformSlot " + i);
            check(pipeline.uniformStages[i] == prepared.uniformStages()[i], name + ": uniformStages " + i);
        }

        check(pipeline.topology == info.primitiveTopology(), name + ": topology");
        check(pipeline.pushConstantSize == info.pushConstantsSize(), name + ": pushConstantSize");
    }

    private static String sha(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // =============================================================================================
    // Native checks (reflection, decoder-style smoke draw, pixel test)
    // =============================================================================================

    private static void nativeChecks(VanillaRun run, MetalDevice device) throws Throwable {
        List<Compiled> compiled = new ArrayList<>(run.compiled);
        compiled.sort((a, b) -> a.name().compareTo(b.name()));
        int reflected = 0;
        int smoked = 0;
        int vertexBufferReported = 0;
        int surplusBuffers = 0;
        int surplusTextures = 0;
        int surplusSamplers = 0;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(21 * 4L);
            for (Compiled c : compiled) {
                List<Integer> depths = new ArrayList<>(List.of(MetalFormats.PIXEL_FORMAT_DEPTH32_FLOAT));
                if (!c.hasDepthState()) {
                    depths.add(MetalFormats.PIXEL_FORMAT_INVALID);
                }

                for (int depth : depths) {
                    int status = (int)I5_PIPELINE_REFLECT.invokeExact(c.pipeline().handle(), depth, out);
                    check(status == 0, c.name() + ": reflect " + MtlNative.lastError());
                    if (status != 0) {
                        continue;
                    }

                    reflected++;
                    for (int s = 0; s < 2; s++) {
                        String stage = s == 0 ? "vertex" : "fragment";
                        int rBuf = out.getAtIndex(ValueLayout.JAVA_INT, s * 5);
                        int rTex = out.getAtIndex(ValueLayout.JAVA_INT, s * 5 + 1);
                        int rSmp = out.getAtIndex(ValueLayout.JAVA_INT, s * 5 + 2);
                        int rCube = out.getAtIndex(ValueLayout.JAVA_INT, s * 5 + 3);
                        int rTexel = out.getAtIndex(ValueLayout.JAVA_INT, s * 5 + 4);
                        int mBuf = out.getAtIndex(ValueLayout.JAVA_INT, 10 + s * 5);
                        int mTex = out.getAtIndex(ValueLayout.JAVA_INT, 10 + s * 5 + 1);
                        int mSmp = out.getAtIndex(ValueLayout.JAVA_INT, 10 + s * 5 + 2);
                        int mCube = out.getAtIndex(ValueLayout.JAVA_INT, 10 + s * 5 + 3);
                        int mTexel = out.getAtIndex(ValueLayout.JAVA_INT, 10 + s * 5 + 4);
                        int required = s == 0 ? out.getAtIndex(ValueLayout.JAVA_INT, 20) : 0;
                        if ((rBuf & required) != 0) {
                            vertexBufferReported++;
                        }

                        // Safety property: every index Metal's compiled function reads is in the mask the decoder binds or
                        // dummies (CONTRACT.md 6.4). The masks come from spvc's msl_is_resource_used (spec 3.3), so they may be a
                        // superset: Metal also drops samplers next to read()-only textures and UBOs whose members died in its
                        // optimizer. Texture types must agree exactly where Metal reads (cube / texture_buffer / 2D).
                        int vb = required;
                        check((rBuf & ~mBuf) == 0, c.name() + " " + stage + ": Metal reads buffers 0x" + Integer.toHexString(rBuf & ~mBuf) + " outside mask 0x"
                            + Integer.toHexString(mBuf));
                        check((rBuf & vb) == 0 || (rBuf & vb) == vb, c.name() + ": partial vertex-buffer reflection");
                        check((rTex & ~mTex) == 0, c.name() + " " + stage + ": Metal reads textures 0x" + Integer.toHexString(rTex & ~mTex) + " outside mask");
                        check((rSmp & ~mSmp) == 0, c.name() + " " + stage + ": Metal reads samplers 0x" + Integer.toHexString(rSmp & ~mSmp) + " outside mask");
                        check(rCube == (mCube & rTex), c.name() + " " + stage + ": cube textures 0x" + Integer.toHexString(rCube) + " vs 0x" + Integer.toHexString(mCube));
                        check(rTexel == (mTexel & rTex), c.name() + " " + stage + ": texel buffers 0x" + Integer.toHexString(rTexel) + " vs 0x" + Integer.toHexString(mTexel));
                        check((mCube & ~mTex) == 0 && (mTexel & ~mTex) == 0 && (mTexel & mSmp) == 0 && (mCube & mTexel) == 0, c.name() + ": mask consistency");
                        surplusBuffers += Integer.bitCount(mBuf & ~rBuf & ~vb);
                        surplusTextures += Integer.bitCount(mTex & ~rTex);
                        surplusSamplers += Integer.bitCount(mSmp & ~rSmp);
                    }

                    status = (int)I5_RENDER_SMOKE.invokeExact(c.pipeline().handle(), depth);
                    check(status == 0, c.name() + ": smoke draw (depth " + depth + ") " + MtlNative.lastError());
                    if (status == 0) {
                        smoked++;
                    }
                }
            }
        }

        System.out.printf("reflection-checked %d variants (stage_in vertex buffers reported in %d), smoke-drawn %d variants under %s%n",
            reflected, vertexBufferReported, smoked, "1".equals(System.getenv("MTL_DEBUG_LAYER")) ? "MTL_DEBUG_LAYER=1" : "NO debug layer");
        System.out.printf("spvc masks vs Metal's optimized functions: %d buffer, %d texture, %d sampler slots bound but not read (summed over variants and stages)%n",
            surplusBuffers, surplusTextures, surplusSamplers);
        pixelTest(run);
    }

    private static void pixelTest(VanillaRun run) throws Throwable {
        Compiled c = run.cullTestPipeline;
        check(c != null, "pixel test pipeline present");
        if (c == null) {
            return;
        }

        MetalRenderPipeline p = c.pipeline();
        // POSITION_COLOR: float3 Position, ubyte4 Color.
        int stride = c.info().vertexBuffers().get(0).stride();
        int posOffset = c.info().attribBindings().get(0).offset();
        int colorOffset = c.info().attribBindings().get(1).offset();
        float[][] tris = {
            {-1, -1, -0.5f, -1, -1, -0.5f},   // A: GL counter-clockwise, bottom-left in NDC
            {-1, 0.5f, -1, 1, -0.5f, 1},      // B: GL clockwise (back face), top-left in NDC
            {1, 0.5f, 1, 1, 0.5f, 1}          // C: GL counter-clockwise, top-right in NDC
        };
        int[] colors = {0xFF0000FF, 0xFF00FF00, 0xFFFF0000}; // ABGR little endian: red, green, blue
        ByteBuffer vertices = ByteBuffer.allocateDirect(stride * 9).order(ByteOrder.LITTLE_ENDIAN);
        for (int t = 0; t < 3; t++) {
            for (int v = 0; v < 3; v++) {
                int base = (t * 3 + v) * stride;
                vertices.putFloat(base + posOffset, tris[t][v * 2]);
                vertices.putFloat(base + posOffset + 4, tris[t][v * 2 + 1]);
                vertices.putFloat(base + posOffset + 8, 0.5F);
                vertices.putInt(base + colorOffset, colors[t]);
            }
        }

        try (Arena arena = Arena.ofConfined()) {
            List<MemorySegment> data = new ArrayList<>();
            List<Integer> slots = new ArrayList<>();
            for (int i = 0; i < p.uniformCount; i++) {
                if (p.uniformKind[i] != MetalRenderPipeline.KIND_UNIFORM_BUFFER || p.uniformStages[i] == 0) {
                    continue;
                }

                MemorySegment block = arena.allocate(256, 16);
                switch (p.uniformName[i]) {
                    case "Projection" -> identity(block, 0);
                    case "DynamicTransforms" -> {
                        identity(block, 0);
                        identity(block, 64);
                        for (int k = 0; k < 4; k++) {
                            block.set(ValueLayout.JAVA_FLOAT, 128 + 4L * k, 1.0F);
                        }
                    }
                    default -> {
                    }
                }

                data.add(block);
                slots.add(p.uniformSlot[i]);
            }

            check(slots.size() == 2, "position_color uses Projection + DynamicTransforms (" + slots.size() + ")");
            MemorySegment slotArray = arena.allocate(4L * slots.size());
            MemorySegment dataArray = arena.allocate(8L * slots.size());
            MemorySegment sizeArray = arena.allocate(8L * slots.size());
            for (int i = 0; i < slots.size(); i++) {
                slotArray.setAtIndex(ValueLayout.JAVA_INT, i, slots.get(i));
                dataArray.setAtIndex(ValueLayout.ADDRESS, i, data.get(i));
                sizeArray.setAtIndex(ValueLayout.JAVA_LONG, i, 256L);
            }

            MemorySegment clear = arena.allocate(16);
            clear.setAtIndex(ValueLayout.JAVA_FLOAT, 3, 1.0F); // opaque black
            int size = 64;
            MemorySegment pixels = arena.allocate(size * size * 4L);
            MemorySegment vertexSegment = MemorySegment.ofBuffer(vertices);
            int status = (int)I5_RENDER.invokeExact(p.handle(), size, size, clear, 0.0F, 0, vertexSegment, (long)vertices.capacity(), 9,
                slots.size(), slotArray, dataArray, sizeArray, pixels);
            check(status == 0, "pixel test render " + MtlNative.lastError());
            if (status != 0) {
                return;
            }

            // Memory row 0 = NDC y = -1 (GL/Vulkan layout, spec 0.7).
            expectPixel(pixels, size, 2, 2, 0xFF0000FF, "A (GL-CCW, NDC bottom-left) at memory row 2, column 2: red");
            expectPixel(pixels, size, 61, 2, 0xFF000000, "row 61, column 2: not drawn (B is GL-CW: culled, and A must not appear flipped here)");
            expectPixel(pixels, size, 61, 61, 0xFFFF0000, "C (GL-CCW, NDC top-right) at memory row 61, column 61: blue");
            expectPixel(pixels, size, 2, 61, 0xFF000000, "row 2, column 61: background (C must not appear flipped here)");
            expectPixel(pixels, size, 32, 32, 0xFF000000, "center: background");
            int green = 0;
            for (int i = 0; i < size * size; i++) {
                if (pixels.getAtIndex(ValueLayout.JAVA_INT, i) == 0xFF00FF00) {
                    green++;
                }
            }

            check(green == 0, "the GL-CW triangle is culled (" + green + " green pixels)");
            System.out.println("pixel test: FLIP_VERTEX_Y row mapping, Clockwise winding + back-face culling, Projection/DynamicTransforms slots verified");
        }
    }

    private static void identity(MemorySegment block, long offset) {
        for (int k = 0; k < 4; k++) {
            block.set(ValueLayout.JAVA_FLOAT, offset + 4L * (k * 4 + k), 1.0F);
        }
    }

    private static void expectPixel(MemorySegment pixels, int width, int row, int column, int expected, String what) {
        int actual = pixels.getAtIndex(ValueLayout.JAVA_INT, (long)row * width + column);
        check(actual == expected, what + String.format(" (got 0x%08X, want 0x%08X)", actual, expected));
    }

    // =============================================================================================
    // Pending semantics with native failures, shader cache close
    // =============================================================================================

    private static void pendingSemanticsNative(VanillaRun run, MetalDevice device) {
        // A device whose handle is 0 makes mcmtl_shader_create fail: compile must log and return Pending.NULL.
        MetalDevice broken = MetalDevice.forTesting(0L, MemorySegment.ofArray(new long[8]), new MetalTimeline(0L, MemorySegment.ofArray(new long[8])), null, false);
        Compiled sample = run.compiled.isEmpty() ? null : run.compiled.get(0);
        check(sample != null, "a compiled sample exists");
        if (sample == null) {
            return;
        }

        // The SPIR-V modules are closed by now; re-run the frontend for one pipeline is overkill: use the prepared MSL
        // directly through the cache of the broken device instead.
        try {
            broken.shaderCache().function(MtlNative.STAGE_VERTEX, sample.prepared().vertex.msl(), "main0", null);
            check(false, "shader create on a null device must fail");
        } catch (MslTranslator.MslTranslationException e) {
            check(e.getMessage().contains("MCMTL_E_INVALID_ARGUMENT"), "null device -> MCMTL_E_INVALID_ARGUMENT (" + e.getMessage() + ")");
        }

        check(broken.shaderCache().size() == 0, "failed compiles are not cached");
        // Invalid MSL on the real device: COMPILE_FAILED with the compiler log, nothing cached.
        int before = device.shaderCache().size();
        try {
            device.shaderCache().function(MtlNative.STAGE_FRAGMENT, "this is not MSL", "main0", null);
            check(false, "garbage MSL must fail");
        } catch (MslTranslator.MslTranslationException e) {
            check(e.getMessage().contains("MCMTL_E_COMPILE_FAILED"), "garbage MSL -> MCMTL_E_COMPILE_FAILED with a log");
        }

        check(device.shaderCache().size() == before, "failed compile left no cache entry");
        // Concurrent requests for one new source compile once.
        String msl = sample.prepared().fragment.msl() + "\n// i5 concurrency probe\n";
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<CompletableFuture<Long>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return device.shaderCache().function(MtlNative.STAGE_FRAGMENT, msl, "main0", null);
                } catch (MslTranslator.MslTranslationException e) {
                    throw new IllegalStateException(e);
                }
            }, pool));
        }

        Set<Long> handles = new HashSet<>();
        for (CompletableFuture<Long> f : futures) {
            handles.add(f.join());
        }

        pool.shutdown();
        check(handles.size() == 1 && device.shaderCache().size() == before + 1, "16 concurrent requests -> one function (" + handles.size() + ")");
    }

    private static void shaderCacheClose(MetalDevice device, VanillaRun run) {
        // Pipelines are released directly: MetalRenderPipeline.close() defers through the I3 timeline (a stub here).
        for (Compiled c : run.compiled) {
            check(MtlNative.release(device.handle(), c.pipeline().handle()) == MtlNative.OK, "release pipeline");
        }

        if (run.cullTestPipeline != null) {
            MtlNative.release(device.handle(), run.cullTestPipeline.pipeline().handle());
        }

        device.shaderCache().close();
        check(device.shaderCache().size() == 0, "closed cache is empty");
        device.shaderCache().close();
        try {
            device.shaderCache().function(MtlNative.STAGE_VERTEX, run.compiled.get(0).prepared().vertex.msl(), "main0", null);
            check(false, "closed cache refuses compiles");
        } catch (MslTranslator.MslTranslationException e) {
            check(true, "closed cache refuses compiles");
        }

        BackendRenderPipeline.CreateInfo info = run.sampleInfo;
        check(info != null, "sample CreateInfo kept");
    }
}
