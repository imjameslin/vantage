import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import com.mojang.renderpearl.backend.metal.MetalFormats;
import com.mojang.renderpearl.backend.metal.MtlNative;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/**
 * I1 (native-core) integration test: every vanilla render pipeline through the real native exports.
 *
 * <p>For each pipeline of {@code RenderPipelines.requiredPipelines()} and {@code optionalPipelines()} (read
 * headlessly from the client jar): GLSL from the vanilla jar, shadowed by build/client.jar's own shader files, with the pipeline's defines and the macros the
 * frontend's GlslCompiler adds on Metal -> SPIR-V (shaderc, same options as GlslCompiler) -> MSL 3.2
 * (SPIRV-Cross with the spec 3.3 options and the CONTRACT.md 6.1 hybrid binding layout) ->
 * {@code mcmtl_shader_create} (deduplicated by MSL text, like the I5 ShaderCache) -> {@code mcmtl_pipeline_create}
 * with the pipeline's real vertex formats, color targets, blend functions, write masks, depth state,
 * topology and the CONTRACT.md 7.2 precompile list. Compiles run on a thread pool, as on the game's
 * compile workers, so the natives are exercised concurrently. Only the binding assignment and the
 * GLSL plumbing are re-implemented here (I5 owns the production path); the native side is the real one.
 *
 * <p>Usage (after tools/build-client.sh):
 * <pre>
 *   CP=build/client.jar:$(find mc/libraries -name '*.jar' ! -name '*natives-macos.jar' ! -name '*x86_64*' | tr '\n' ':')
 *   MTL_DEBUG_LAYER=1 mc/runtime/java-runtime-epsilon/jre.bundle/Contents/Home/bin/java --enable-native-access=ALL-UNNAMED \
 *     -Dmcmetal.library=build/native/libmcmetal.dylib -Dorg.lwjgl.system.SharedLibraryExtractPath=build/smoke-natives \
 *     -cp "$CP" tools/CoreMslTest.java . [--dump build/msl]
 * </pre>
 * Exit code 0 = every pipeline compiled (GLSL, MSL, Metal) and every PSO precompiled.
 */
public class CoreMslTest {
    private static final String JAR = "mc/versions/26.3/26.3.jar";
    private static final int SPV_DECORATION_LOCATION = 30;
    private static final int SPV_DECORATION_BINDING = 33;
    private static final int SPV_DECORATION_DESCRIPTOR_SET = 34;
    private static final int SPV_DIM_CUBE = 3;
    private static final int SPV_DIM_BUFFER = 5;
    private static final int MODEL_VERTEX = 0;
    private static final int MODEL_FRAGMENT = 4;
    private static final Pattern INCLUDE = Pattern.compile("^\\s*#include\\s*<([a-z0-9_.-]+):([^>]+)>\\s*$", Pattern.MULTILINE);
    private static final Map<String, Integer> FIXED_UBO_SLOTS = Map.of("Projection", 0, "Globals", 1, "Fog", 2, "Lighting", 3, "DynamicTransforms", 4);

    private static Map<String, String> assets;
    private static long device;
    private static final Map<String, CompletableFuture<Long>> SHADER_CACHE = new ConcurrentHashMap<>();
    private static final List<String> FAILURES = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger PIPELINES_OK = new AtomicInteger();
    private static final AtomicInteger DEPTHLESS_SKIPPED = new AtomicInteger();
    private static final AtomicInteger MSL_COMPILED = new AtomicInteger();
    private static Path dumpDir;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        if (args.length > 2 && args[1].equals("--dump")) {
            dumpDir = root.resolve(args[2]);
            Files.createDirectories(dumpDir);
        }
        assets = readShaderAssets(root.resolve(JAR));
        // The shipped client jar's shader overrides shadow the vanilla files, as in game (round-4 merge: the S5
        // compact pipelines need build/client.jar's core/terrain.vsh with its TERRAIN_COMPACT decode).
        Path clientJar = root.resolve("build/client.jar");
        int overridden = 0;
        if (Files.exists(clientJar)) {
            Map<String, String> shipped = readShaderAssets(clientJar);
            for (Map.Entry<String, String> e : shipped.entrySet()) {
                if (!e.getValue().equals(assets.put(e.getKey(), e.getValue()))) overridden++;
            }
        }
        System.out.println("shader assets: " + assets.size() + " (" + overridden + " from build/client.jar)");

        List<RenderPipeline> pipelines = new ArrayList<>(RenderPipelines.requiredPipelines());
        pipelines.addAll(RenderPipelines.optionalPipelines());
        pipelines.sort((a, b) -> a.getLocation().toString().compareTo(b.getLocation().toString()));
        System.out.println("vanilla pipelines: " + pipelines.size());

        MtlNative.Loader.load();
        device = createDevice();
        long start = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()));
        List<Future<?>> futures = new ArrayList<>();
        List<Long> pipelineHandles = Collections.synchronizedList(new ArrayList<>());
        for (RenderPipeline pipeline : pipelines) {
            futures.add(pool.submit(() -> {
                try {
                    long handle = compilePipeline(pipeline);
                    if (handle != 0) pipelineHandles.add(handle);
                } catch (Throwable t) {
                    FAILURES.add(pipeline.getLocation() + ": " + t);
                }
            }));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        long ms = (System.nanoTime() - start) / 1_000_000L;

        for (long h : pipelineHandles) MtlNative.release(device, h);
        for (CompletableFuture<Long> f : SHADER_CACHE.values()) {
            Long h = f.getNow(0L);
            if (h != null && h != 0L) MtlNative.release(device, h);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment messages = arena.allocate(16384);
            MemorySegment length = arena.allocate(4);
            MtlNative.deviceCopyMessages(device, messages.address(), 16384, length.address());
            String text = messages.getString(0);
            if (!text.isEmpty()) System.out.println("device messages:\n  " + text.replace("\n", "\n  "));
            for (String line : text.split("\n")) {
                if (line.contains("depth-less variant unavailable")) DEPTHLESS_SKIPPED.incrementAndGet();
            }
        }
        check(MtlNative.deviceDestroy(device), "mcmtl_device_destroy");

        System.out.printf("pipelines: %d/%d created (%d without a depth-less variant), %d unique MSL libraries compiled, %d ms%n",
            PIPELINES_OK.get(), pipelines.size(), DEPTHLESS_SKIPPED.get(), MSL_COMPILED.get(), ms);
        for (String f : FAILURES) System.out.println("FAIL " + f);
        System.out.println(FAILURES.isEmpty() ? "CoreMslTest PASSED" : "CoreMslTest FAILED (" + FAILURES.size() + ")");
        System.exit(FAILURES.isEmpty() ? 0 : 1);
    }

    // =============================================================================================

    private static long createDevice() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = arena.allocate(MtlNative.DEVICE_DESC_SIZEOF, 8);
            desc.set(ValueLayout.JAVA_INT, MtlNative.DEVICE_DESC_STRUCT_SIZE, (int)MtlNative.DEVICE_DESC_SIZEOF);
            desc.set(ValueLayout.JAVA_INT, MtlNative.DEVICE_DESC_FLAGS, MtlNative.DEVICE_FLAG_DEBUG_LABELS);
            desc.set(ValueLayout.JAVA_INT, MtlNative.DEVICE_DESC_FRAMES_IN_FLIGHT, 2);
            MemorySegment label = arena.allocateFrom("CoreMslTest");
            desc.set(ValueLayout.JAVA_LONG, MtlNative.DEVICE_DESC_LABEL, label.address());
            MemorySegment out = arena.allocate(8);
            check(MtlNative.deviceCreate(desc.address(), out.address()), "mcmtl_device_create");
            long handle = out.get(ValueLayout.JAVA_LONG, 0);
            MemorySegment info = arena.allocate(MtlNative.DEVICE_INFO_SIZEOF, 8);
            info.set(ValueLayout.JAVA_INT, MtlNative.DEVICE_INFO_STRUCT_SIZE, (int)MtlNative.DEVICE_INFO_SIZEOF);
            check(MtlNative.deviceGetInfo(handle, info.address()), "mcmtl_device_get_info");
            System.out.println("device: " + info.getString(MtlNative.DEVICE_INFO_NAME) + " / " + info.getString(MtlNative.DEVICE_INFO_DRIVER_INFO));
            return handle;
        }
    }

    private static void check(int status, String what) {
        if (status != MtlNative.OK) throw new IllegalStateException(what + ": " + MtlNative.statusName(status) + ": " + MtlNative.lastError());
    }

    // ---- GLSL sources -------------------------------------------------------------------------

    private static Map<String, String> readShaderAssets(Path jar) throws Exception {
        Map<String, String> result = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry e : Collections.list(zip.entries())) {
                String name = e.getName();
                if (!e.isDirectory() && name.startsWith("assets/") && name.contains("/shaders/")) {
                    try (InputStream in = zip.getInputStream(e)) {
                        result.put(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
        }
        return result;
    }

    private static String loadSource(Identifier id, ShaderType type) {
        String path = "assets/" + id.getNamespace() + "/shaders/" + id.getPath() + (type == ShaderType.VERTEX ? ".vsh" : ".fsh");
        String source = assets.get(path);
        if (source == null) throw new IllegalStateException("missing shader source " + path);
        return expandIncludes(source, 0);
    }

    private static String expandIncludes(String source, int depth) {
        if (depth > 16) throw new IllegalStateException("include depth");
        Matcher m = INCLUDE.matcher(source);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String path = "assets/" + m.group(1) + "/shaders/include/" + m.group(2);
            String included = assets.get(path);
            if (included == null) throw new IllegalStateException("missing include " + path);
            m.appendReplacement(sb, Matcher.quoteReplacement(expandIncludes(included, depth + 1)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** GlslCompiler.compileToSpv with the macros a Metal device enables (isZZeroToOne, explicit depth, base instance). */
    private static ByteBuffer compileGlsl(String name, String source, ShaderType type, ShaderDefines defines) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, 0, 4202496);
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
            Shaderc.shaderc_compile_options_set_preserve_bindings(options, false);
            Shaderc.shaderc_compile_options_set_generate_debug_info(options);
            Shaderc.shaderc_compile_options_set_optimization_level(options, 0);
            Shaderc.shaderc_compile_options_add_macro_definition(options, "RENDERPEARL_DEPTH_IS_ZERO_TO_ONE", "");
            Shaderc.shaderc_compile_options_add_macro_definition(options, "RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE", "");
            Shaderc.shaderc_compile_options_add_macro_definition(options, "RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE", "");
            for (Map.Entry<String, String> macro : defines.values().entrySet()) {
                Shaderc.shaderc_compile_options_add_macro_definition(options, macro.getKey(), macro.getValue());
            }
            for (String flag : defines.flags()) Shaderc.shaderc_compile_options_add_macro_definition(options, flag, "");
            long result = Shaderc.shaderc_compile_into_spv(compiler, source, type == ShaderType.FRAGMENT ? 1 : 0, name, "main", options);
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result) != 0) {
                    throw new IllegalStateException("GLSL " + name + ": " + Shaderc.shaderc_result_get_error_message(result));
                }
                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
                copy.put(bytes).flip();
                return copy;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    // ---- SPIR-V -> MSL ------------------------------------------------------------------------

    record Resource(int id, String name, int kind, int dim) {
        static final int UBO = 0, TEXTURE = 1, TEXEL = 2;
    }

    record Input(String name, int location, int baseType, int vecSize) {
    }

    /** One spvc context per stage (never shared across threads). */
    static final class Stage implements AutoCloseable {
        final long context;
        final long compiler;
        final ShaderType type;
        final List<Resource> resources = new ArrayList<>();
        final List<Input> inputs = new ArrayList<>();
        final List<Input> outputs = new ArrayList<>();

        Stage(ByteBuffer spirv, ShaderType type) {
            this.type = type;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer pp = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_context_create(pp), 0, "context_create");
                this.context = pp.get(0);
                IntBuffer words = spirv.order(ByteOrder.nativeOrder()).asIntBuffer();
                checkSpvc(Spvc.spvc_context_parse_spirv(this.context, words, words.remaining(), pp), this.context, "parse");
                long ir = pp.get(0);
                checkSpvc(Spvc.spvc_context_create_compiler(this.context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pp),
                    this.context, "create_compiler");
                this.compiler = pp.get(0);
                checkSpvc(Spvc.spvc_compiler_create_shader_resources(this.compiler, pp), this.context, "resources");
                long res = pp.get(0);
                for (int[] t : new int[][] {{Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, Resource.UBO}, {Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, Resource.TEXTURE}}) {
                    for (SpvcReflectedResource r : list(res, t[0], stack)) {
                        long typeHandle = Spvc.spvc_compiler_get_type_handle(this.compiler, r.type_id());
                        int dim = t[1] == Resource.TEXTURE ? Spvc.spvc_type_get_image_dimension(typeHandle) : -1;
                        int kind = dim == SPV_DIM_BUFFER ? Resource.TEXEL : t[1];
                        String name = t[1] == Resource.UBO ? Spvc.spvc_compiler_get_name(this.compiler, r.base_type_id()) : r.nameString();
                        if (name == null || name.isEmpty()) name = r.nameString();
                        this.resources.add(new Resource(r.id(), name, kind, dim));
                    }
                }
                for (int[] t : new int[][] {{Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, 0}, {Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT, 1}}) {
                    for (SpvcReflectedResource r : list(res, t[0], stack)) {
                        long typeHandle = Spvc.spvc_compiler_get_type_handle(this.compiler, r.type_id());
                        Input in = new Input(r.nameString(), Spvc.spvc_compiler_get_decoration(this.compiler, r.id(), SPV_DECORATION_LOCATION),
                            Spvc.spvc_type_get_basetype(typeHandle), Spvc.spvc_type_get_vector_size(typeHandle));
                        (t[1] == 0 ? this.inputs : this.outputs).add(in);
                    }
                }
            }
        }

        private static List<SpvcReflectedResource> list(long res, int type, MemoryStack stack) {
            PointerBuffer list = stack.mallocPointer(1);
            PointerBuffer count = stack.mallocPointer(1);
            Spvc.spvc_resources_get_resource_list_for_type(res, type, list, count);
            List<SpvcReflectedResource> out = new ArrayList<>();
            SpvcReflectedResource.Buffer buffer = SpvcReflectedResource.create(list.get(0), (int)count.get(0));
            for (int i = 0; i < count.get(0); i++) out.add(buffer.get(i));
            return out;
        }

        int model() {
            return this.type == ShaderType.VERTEX ? MODEL_VERTEX : MODEL_FRAGMENT;
        }

        /**
         * Spec 3.3 options + the binding table; returns the MSL. Like the frontend's SPIR-V patching
         * (PipelineBuilder / SPIRVModule), every descriptor first gets DescriptorSet 0 and Binding = its
         * pipeline-wide uniform index (slot[2]); shaderc's auto-bound numbers collide across kinds.
         */
        String translate(Map<String, int[]> slots) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer pp = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_create_compiler_options(this.compiler, pp), this.context, "options");
                long o = pp.get(0);
                Spvc.spvc_compiler_options_set_uint(o, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MetalFormats.SPVC_MSL_VERSION_3_2);
                Spvc.spvc_compiler_options_set_uint(o, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS);
                Spvc.spvc_compiler_options_set_bool(o, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
                Spvc.spvc_compiler_options_set_bool(o, Spvc.SPVC_COMPILER_OPTION_MSL_ARGUMENT_BUFFERS, false);
                Spvc.spvc_compiler_options_set_bool(o, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING, false);
                Spvc.spvc_compiler_options_set_bool(o, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, this.type == ShaderType.VERTEX);
                Spvc.spvc_compiler_options_set_bool(o, Spvc.SPVC_COMPILER_OPTION_FIXUP_DEPTH_CONVENTION, false);
                Spvc.spvc_compiler_options_set_bool(o, Spvc.SPVC_COMPILER_OPTION_MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS, true);
                Spvc.spvc_compiler_options_set_bool(o, Spvc.SPVC_COMPILER_OPTION_FORCE_ZERO_INITIALIZED_VARIABLES, true);
                Spvc.spvc_compiler_options_set_bool(o, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN, true);
                Spvc.spvc_compiler_options_set_bool(o, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_FRAG_DEPTH_BUILTIN, true);
                checkSpvc(Spvc.spvc_compiler_install_compiler_options(this.compiler, o), this.context, "install options");
                for (Resource r : this.resources) {
                    int[] slot = slots.get(r.name());
                    Spvc.spvc_compiler_set_decoration(this.compiler, r.id(), SPV_DECORATION_DESCRIPTOR_SET, 0);
                    Spvc.spvc_compiler_set_decoration(this.compiler, r.id(), SPV_DECORATION_BINDING, slot[2]);
                    SpvcMslResourceBinding b = SpvcMslResourceBinding.calloc(stack);
                    Spvc.spvc_msl_resource_binding_init(b);
                    b.stage(this.model()).desc_set(0).binding(slot[2]);
                    if (r.kind() == Resource.UBO) {
                        b.msl_buffer(slot[1]);
                    } else if (r.kind() == Resource.TEXTURE) {
                        b.msl_texture(slot[1]).msl_sampler(slot[1]);
                    } else {
                        b.msl_texture(slot[1]);
                    }
                    checkSpvc(Spvc.spvc_compiler_msl_add_resource_binding(this.compiler, b), this.context, "binding " + r.name());
                }
                checkSpvc(Spvc.spvc_compiler_compile(this.compiler, pp), this.context, "compile");
                String msl = MemoryUtil.memUTF8(pp.get(0));
                String entry = Spvc.spvc_compiler_get_cleansed_entry_point_name(this.compiler, "main", this.model());
                if (!"main0".equals(entry)) throw new IllegalStateException("entry point " + entry);
                return msl;
            }
        }

        boolean used(Resource r, Map<String, int[]> slots) {
            return Spvc.spvc_compiler_msl_is_resource_used(this.compiler, this.model(), 0, slots.get(r.name())[2]);
        }

        @Override
        public void close() {
            Spvc.spvc_context_destroy(this.context);
        }
    }

    private static void checkSpvc(int result, long context, String what) {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new IllegalStateException("spvc " + what + ": " + result + (context != 0 ? " " + Spvc.spvc_context_get_last_error_string(context) : ""));
        }
    }

    /** CONTRACT.md 6.1 hybrid layout: name -> {kind, Metal slot, uniform index (first-seen order, as PipelineBuilder)}. */
    private static Map<String, int[]> assignSlots(List<Stage> stages) {
        Map<String, int[]> slots = new LinkedHashMap<>();
        int nextUbo = 5;
        int nextTexture = 3;
        for (Stage s : stages) {
            for (Resource r : s.resources) {
                if (slots.containsKey(r.name())) continue;
                int slot;
                if (r.kind() == Resource.UBO) {
                    Integer fixed = FIXED_UBO_SLOTS.get(r.name());
                    slot = fixed != null ? fixed : nextUbo++;
                } else {
                    slot = switch (r.name()) {
                        case "Sampler0" -> 0;
                        case "Sampler1" -> 1;
                        case "Sampler2" -> 2;
                        default -> nextTexture++;
                    };
                }
                slots.put(r.name(), new int[] {r.kind(), slot, slots.size()});
            }
        }
        if (nextUbo > 14 || nextTexture > 16) throw new IllegalStateException("binding fallback needed (not exercised by vanilla)");
        return slots;
    }

    // ---- Native compile ------------------------------------------------------------------------

    private static long shader(String msl, ShaderType type, String label) throws Exception {
        String key = type + "\n" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(msl.getBytes(StandardCharsets.UTF_8)));
        CompletableFuture<Long> mine = new CompletableFuture<>();
        CompletableFuture<Long> existing = SHADER_CACHE.putIfAbsent(key, mine);
        if (existing != null) return existing.get();
        try (Arena arena = Arena.ofConfined()) {
            byte[] bytes = msl.getBytes(StandardCharsets.UTF_8);
            MemorySegment source = arena.allocate(bytes.length);   /* deliberately not NUL-terminated */
            source.copyFrom(MemorySegment.ofArray(bytes));
            MemorySegment desc = arena.allocate(MtlNative.SHADER_DESC_SIZEOF, 8);
            desc.set(ValueLayout.JAVA_INT, MtlNative.SHADER_DESC_STRUCT_SIZE, (int)MtlNative.SHADER_DESC_SIZEOF);
            desc.set(ValueLayout.JAVA_INT, MtlNative.SHADER_DESC_STAGE, type == ShaderType.VERTEX ? MtlNative.STAGE_VERTEX : MtlNative.STAGE_FRAGMENT);
            desc.set(ValueLayout.JAVA_INT, MtlNative.SHADER_DESC_LANGUAGE_VERSION, MetalFormats.LANGUAGE_VERSION_3_2);
            desc.set(ValueLayout.JAVA_LONG, MtlNative.SHADER_DESC_SOURCE, source.address());
            desc.set(ValueLayout.JAVA_LONG, MtlNative.SHADER_DESC_SOURCE_LENGTH, bytes.length);
            desc.set(ValueLayout.JAVA_LONG, MtlNative.SHADER_DESC_ENTRY_POINT, arena.allocateFrom("main0").address());
            desc.set(ValueLayout.JAVA_LONG, MtlNative.SHADER_DESC_LABEL, arena.allocateFrom(label).address());
            MemorySegment out = arena.allocate(8);
            int status = MtlNative.shaderCreate(device, desc.address(), out.address());
            if (status != MtlNative.OK) {
                IllegalStateException e = new IllegalStateException("mcmtl_shader_create " + label + ": " + MtlNative.statusName(status) + ": "
                    + MtlNative.lastError());
                mine.completeExceptionally(e);
                throw e;
            }
            MSL_COMPILED.incrementAndGet();
            long handle = out.get(ValueLayout.JAVA_LONG, 0);
            mine.complete(handle);
            return handle;
        }
    }

    private static long compilePipeline(RenderPipeline pipeline) throws Exception {
        String name = pipeline.getLocation().toString();
        List<Stage> stages = new ArrayList<>();
        List<ByteBuffer> spirv = new ArrayList<>();
        try {
            for (ShaderType type : new ShaderType[] {ShaderType.VERTEX, ShaderType.FRAGMENT}) {
                Identifier id = pipeline.getShaders().get(type);
                ByteBuffer spv = compileGlsl(id.toString(), loadSource(id, type), type, pipeline.getShaderDefines());
                spirv.add(spv);
                stages.add(new Stage(spv, type));
            }
            Map<String, int[]> slots = assignSlots(stages);
            long[] functions = new long[2];
            int[] bufferMask = new int[2], textureMask = new int[2], cubeMask = new int[2], texelMask = new int[2];
            for (int s = 0; s < 2; s++) {
                Stage stage = stages.get(s);
                String msl = stage.translate(slots);
                for (Resource r : stage.resources) {
                    if (!stage.used(r, slots)) continue;
                    int slot = slots.get(r.name())[1];
                    if (r.kind() == Resource.UBO) {
                        bufferMask[s] |= 1 << slot;
                    } else {
                        textureMask[s] |= 1 << slot;
                        if (r.kind() == Resource.TEXEL) texelMask[s] |= 1 << slot;
                        if (r.dim() == SPV_DIM_CUBE) cubeMask[s] |= 1 << slot;
                    }
                }
                String label = pipeline.getShaders().get(stage.type).toString();
                if (dumpDir != null) {
                    Files.writeString(dumpDir.resolve(name.replace(':', '_').replace('/', '_') + (s == 0 ? ".vert.metal" : ".frag.metal")), msl);
                }
                functions[s] = shader(msl, stage.type, label);
            }
            return createPipeline(pipeline, name, stages.get(0), functions, bufferMask, textureMask, cubeMask, texelMask);
        } finally {
            for (Stage s : stages) s.close();
            for (ByteBuffer b : spirv) MemoryUtil.memFree(b);
        }
    }

    private static long createPipeline(RenderPipeline pipeline, String name, Stage vertex, long[] functions, int[] bufferMask, int[] textureMask,
        int[] cubeMask, int[] texelMask) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment d = arena.allocate(MtlNative.PIPELINE_DESC_SIZEOF, 8);
            d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_STRUCT_SIZE, (int)MtlNative.PIPELINE_DESC_SIZEOF);
            DepthStencilState dss = pipeline.getDepthStencilState();
            d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_FLAGS, dss != null ? MtlNative.PIPELINE_FLAG_HAS_DEPTH_STATE : 0);
            d.set(ValueLayout.JAVA_LONG, MtlNative.PIPELINE_DESC_VERTEX_FUNCTION, functions[0]);
            d.set(ValueLayout.JAVA_LONG, MtlNative.PIPELINE_DESC_FRAGMENT_FUNCTION, functions[1]);
            d.set(ValueLayout.JAVA_LONG, MtlNative.PIPELINE_DESC_LABEL, arena.allocateFrom(name).address());
            d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_PRIMITIVE_TYPE, MetalFormats.primitiveType(pipeline.getPrimitiveTopology()));
            d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_TOPOLOGY_CLASS, MetalFormats.topologyClass(pipeline.getPrimitiveTopology()));
            d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_EMULATION, MetalFormats.emulation(pipeline.getPrimitiveTopology()));
            d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_CULL_MODE, MetalFormats.cullMode(pipeline.isCull()));
            d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_FILL_MODE, MetalFormats.fillMode(pipeline.getPolygonMode()));
            d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_FRONT_FACING, MetalFormats.WINDING_CLOCKWISE);
            d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_DEPTH_COMPARE, dss != null ? MetalFormats.compareFunction(dss.depthTest()) : MetalFormats.COMPARE_ALWAYS);
            d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_DEPTH_WRITE_ENABLED, dss != null && dss.writeDepth() ? 1 : 0);
            d.set(ValueLayout.JAVA_FLOAT, MtlNative.PIPELINE_DESC_DEPTH_BIAS_CONSTANT, dss != null ? dss.depthBiasConstant() : 0f);
            d.set(ValueLayout.JAVA_FLOAT, MtlNative.PIPELINE_DESC_DEPTH_BIAS_SLOPE_SCALE, dss != null ? dss.depthBiasScaleFactor() : 0f);

            List<ColorTargetState> targets = pipeline.getColorTargetStates();
            d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_COLOR_TARGET_COUNT, targets.size());
            for (int i = 0; i < targets.size(); i++) {
                ColorTargetState t = targets.get(i);
                if (t == null) continue;
                long base = MtlNative.PIPELINE_DESC_COLOR_TARGETS + i * MtlNative.COLOR_TARGET_SIZEOF;
                int format = MetalFormats.pixelFormat(t.format());
                if (format == 0) throw new IllegalStateException(name + ": color target " + i + " has no Metal format " + t.format());
                d.set(ValueLayout.JAVA_INT, base + MtlNative.COLOR_TARGET_PIXEL_FORMAT, format);
                d.set(ValueLayout.JAVA_INT, base + MtlNative.COLOR_TARGET_WRITE_MASK, MetalFormats.colorWriteMask(t.writeMask()));
                Optional<BlendFunction> blend = t.blendFunction();
                if (blend.isPresent()) {
                    BlendFunction f = blend.get();
                    d.set(ValueLayout.JAVA_INT, base + MtlNative.COLOR_TARGET_BLENDING_ENABLED, 1);
                    d.set(ValueLayout.JAVA_INT, base + MtlNative.COLOR_TARGET_RGB_BLEND_OPERATION, MetalFormats.blendOperation(f.color().op()));
                    d.set(ValueLayout.JAVA_INT, base + MtlNative.COLOR_TARGET_ALPHA_BLEND_OPERATION, MetalFormats.blendOperation(f.alpha().op()));
                    d.set(ValueLayout.JAVA_INT, base + MtlNative.COLOR_TARGET_SOURCE_RGB_BLEND_FACTOR, MetalFormats.blendFactor(f.color().sourceFactor()));
                    d.set(ValueLayout.JAVA_INT, base + MtlNative.COLOR_TARGET_DESTINATION_RGB_BLEND_FACTOR, MetalFormats.blendFactor(f.color().destFactor()));
                    d.set(ValueLayout.JAVA_INT, base + MtlNative.COLOR_TARGET_SOURCE_ALPHA_BLEND_FACTOR, MetalFormats.blendFactor(f.alpha().sourceFactor()));
                    d.set(ValueLayout.JAVA_INT, base + MtlNative.COLOR_TARGET_DESTINATION_ALPHA_BLEND_FACTOR, MetalFormats.blendFactor(f.alpha().destFactor()));
                }
            }

            // Vertex input exactly like PipelineBuilder.generateBackendCreateInfo (match by element name).
            Map<String, Input> inputs = new HashMap<>();
            for (Input in : vertex.inputs) inputs.put(in.name(), in);
            List<VertexFormat> bindings = pipeline.getVertexFormatBindings();
            int attributeCount = 0, layoutCount = 0, required = 0;
            String previousName = null;
            int previousLocation = 0;
            for (int slot = 0; slot < bindings.size(); slot++) {
                VertexFormat format = bindings.get(slot);
                if (format == null) continue;
                boolean any = false;
                for (VertexFormatElement element : format.getElements()) {
                    Input in = inputs.get(element.name());
                    if (in == null) continue;
                    int location = element.name().equals(previousName) ? previousLocation + 1 : in.location();
                    long base = MtlNative.PIPELINE_DESC_ATTRIBUTES + attributeCount * MtlNative.VERTEX_ATTRIBUTE_SIZEOF;
                    d.set(ValueLayout.JAVA_INT, base + MtlNative.VERTEX_ATTRIBUTE_LOCATION, location);
                    d.set(ValueLayout.JAVA_INT, base + MtlNative.VERTEX_ATTRIBUTE_FORMAT, MetalFormats.vertexFormat(element.format()));
                    d.set(ValueLayout.JAVA_INT, base + MtlNative.VERTEX_ATTRIBUTE_OFFSET, element.offset());
                    d.set(ValueLayout.JAVA_INT, base + MtlNative.VERTEX_ATTRIBUTE_BUFFER_INDEX, 30 - slot);
                    attributeCount++;
                    any = true;
                    previousName = element.name();
                    previousLocation = location;
                }
                if (any) {
                    long base = MtlNative.PIPELINE_DESC_LAYOUTS + layoutCount * MtlNative.VERTEX_LAYOUT_SIZEOF;
                    d.set(ValueLayout.JAVA_INT, base + MtlNative.VERTEX_LAYOUT_BUFFER_INDEX, 30 - slot);
                    d.set(ValueLayout.JAVA_INT, base + MtlNative.VERTEX_LAYOUT_STRIDE, format.getVertexSize());
                    d.set(ValueLayout.JAVA_INT, base + MtlNative.VERTEX_LAYOUT_STEP_FUNCTION,
                        format.getStepRate() > 0 ? MetalFormats.VERTEX_STEP_PER_INSTANCE : MetalFormats.VERTEX_STEP_PER_VERTEX);
                    d.set(ValueLayout.JAVA_INT, base + MtlNative.VERTEX_LAYOUT_STEP_RATE, Math.max(1, format.getStepRate()));
                    layoutCount++;
                    required |= 1 << (30 - slot);
                    bufferMask[0] |= 1 << (30 - slot);
                }
            }
            d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_ATTRIBUTE_COUNT, attributeCount);
            d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_LAYOUT_COUNT, layoutCount);
            for (int s = 0; s < 2; s++) {
                d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_BUFFER_MASK + 4L * s, bufferMask[s]);
                d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_TEXTURE_MASK + 4L * s, textureMask[s]);
                d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_SAMPLER_MASK + 4L * s, textureMask[s] & ~texelMask[s]);
                d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_TEXTURE_CUBE_MASK + 4L * s, cubeMask[s]);
                d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_TEXEL_BUFFER_MASK + 4L * s, texelMask[s]);
            }
            d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_REQUIRED_VERTEX_BUFFER_MASK, required);
            d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_PRECOMPILE_DEPTH_FORMAT_COUNT, dss == null ? 2 : 1);
            d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_PRECOMPILE_DEPTH_FORMATS, MetalFormats.PIXEL_FORMAT_DEPTH32_FLOAT);
            d.set(ValueLayout.JAVA_INT, MtlNative.PIPELINE_DESC_PRECOMPILE_DEPTH_FORMATS + 4, MetalFormats.PIXEL_FORMAT_INVALID);

            MemorySegment out = arena.allocate(8);
            int status = MtlNative.pipelineCreate(device, d.address(), out.address());
            if (status != MtlNative.OK) {
                FAILURES.add(name + ": mcmtl_pipeline_create: " + MtlNative.statusName(status) + ": " + MtlNative.lastError());
                return 0;
            }
            long handle = out.get(ValueLayout.JAVA_LONG, 0);
            PIPELINES_OK.incrementAndGet();
            return handle;
        }
    }
}
