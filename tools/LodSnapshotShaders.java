package com.mojang.renderpearl.backend.metal;

import com.google.gson.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.*;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import java.io.*;
import java.lang.classfile.*;
import java.lang.constant.*;
import java.lang.instrument.*;
import java.lang.reflect.Field;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Isolated frozen-client export. The agent records the production createHandle inputs/result;
 * it does not replace shaders, pipeline creation, or handle selection. No game or draw is run. */
public final class LodSnapshotShaders {
    record Call(Object prepared, String vertex, String fragment, String variant) {}
    static final ThreadLocal<Call> CURRENT = new ThreadLocal<>();
    static final Map<Long, Call> CALLS = new ConcurrentHashMap<>();
    static volatile boolean installed;
    static volatile Throwable hookFailure;

    public static void enter(Object prepared, String vertex, String fragment, String variant) {
        CURRENT.set(new Call(prepared, vertex, fragment, variant));
    }
    public static void leave(long handle) {
        Call call = CURRENT.get();
        if (call == null || handle == 0 || CALLS.putIfAbsent(handle, call) != null)
            throw new AssertionError("Invalid/duplicate production PSO recording");
        CURRENT.remove();
    }
    public static void premain(String ignored, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> cls, ProtectionDomain domain, byte[] bytes) {
                if (!name.equals("com/mojang/renderpearl/backend/metal/MetalRenderPipeline")) return null;
                try {
                    ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                        ClassHierarchyResolver.defaultResolver().orElse(ClassHierarchyResolver.ofResourceParsing(loader))));
                    ClassDesc self = ClassDesc.of("com.mojang.renderpearl.backend.metal.LodSnapshotShaders");
                    String descriptor = "(Lcom/mojang/renderpearl/backend/metal/MetalDevice;Lcom/mojang/renderpearl/backend/metal/MetalRenderPipeline$Prepared;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)J";
                    int[] methods = {0}, returns = {0};
                    byte[] transformed = cf.transformClass(cf.parse(bytes), (builder, element) -> {
                        if (!(element instanceof MethodModel m) || !m.methodName().equalsString("createHandle") || !m.methodType().equalsString(descriptor)) {
                            builder.with(element); return;
                        }
                        methods[0]++;
                        builder.transformMethod(m, MethodTransform.transformingCode(new CodeTransform() {
                            @Override public void atStart(CodeBuilder b) {
                                b.aload(1).aload(2).aload(3).aload(4).invokestatic(self, "enter", MethodTypeDesc.of(
                                    ConstantDescs.CD_void, ConstantDescs.CD_Object, ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String));
                            }
                            @Override public void accept(CodeBuilder b, CodeElement e) {
                                if (e instanceof Instruction i && i.opcode() == Opcode.LRETURN) {
                                    b.dup2().invokestatic(self, "leave", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_long));
                                    returns[0]++;
                                }
                                b.with(e);
                            }
                        }));
                    });
                    if (methods[0] != 1 || returns[0] != 1) throw new AssertionError("Frozen createHandle shape changed");
                    if (!cf.verify(transformed).isEmpty()) throw new AssertionError("Recorder bytecode does not verify");
                    installed = true;
                    return transformed;
                } catch (Throwable t) { hookFailure = t; throw new IllegalStateException(t); }
            }
        });
    }
    public static void main(String[] args) throws Exception { Export.run(args); }

    // Keep production class initialization out of premain, before the recorder is installed.
    static final class Export {
        static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        static final Map<String, String> ASSETS = new TreeMap<>();
        static final Map<String, Object> PIPELINES = new LinkedHashMap<>();
        static Path out;
        static String prefix;
        static final long START = System.nanoTime();

        static void run(String[] args) throws Exception {
            require(args.length == 2, "usage: LodSnapshotShaders output-directory snapshot-directory");
            out = Path.of(args[0]); prefix = out.getFileName() + "/";
            JsonObject scene = JsonParser.parseString(Files.readString(Path.of(args[1]).resolve("complete.json"))).getAsJsonObject();
            JsonObject config = scene.getAsJsonObject("config"), toggles = config.getAsJsonObject("toggles").getAsJsonObject("TerrainShaderVariants");
            require(scene.get("complete").getAsBoolean(), "Incomplete snapshot");
            require(scene.getAsJsonArray("resourcePacks").toString().equals("[\"vanilla\"]"), "Only captured vanilla pack is supported");
            require(!toggles.get("frameUsesRgss").getAsBoolean() && !toggles.get("frameAnisotropic").getAsBoolean(), "Expected nearest snapshot");
            require(toggles.get("variantModes").getAsString().equals("vfog,hvary") && toggles.get("useVariant").getAsBoolean(), "Expected production paired default");
            for (var e : config.getAsJsonObject("properties").entrySet())
                if (e.getKey().startsWith("mc.lod.") || e.getKey().equals("mc.lod")) System.setProperty(e.getKey(), e.getValue().getAsString());
            System.setProperty("mcmetal.terrainSpecialize", "true");
            System.setProperty("mcmetal.terrainVariant", "vfog,hvary");
            System.setProperty("mcmetal.terrainVariant.use", "true");
            require(!TerrainVertexMsl.build && !LodShaderProbe.rgb && System.getProperty("mc.lod.diag", "").isEmpty(), "Expected unchanged production shaders");
            require(TerrainShaderVariants.specialize && TerrainShaderVariants.variantModes.equals("vfog,hvary") && TerrainShaderVariants.variantPipelines.isEmpty(), "Shader options differ");
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            MetalBackend backend = new MetalBackend(); backend.loadLibrary();
            GpuDevice device = backend.createDevice(new GpuDebugOptions(0, false, false, true));
            Field deviceField = RenderSystem.class.getDeclaredField("DEVICE"); deviceField.setAccessible(true); deviceField.set(null, device);
            // ShaderSource below reads exclusively the frozen jar's built-in assets (hashes are exported).
            TerrainShaderVariants.setVanillaSourceCheck(() -> true);
            TerrainShaderVariants.setFrameUsesRgss(false); TerrainShaderVariants.setFrameAnisotropic(false);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try (ShaderSource source = source()) {
                Map<String, RenderPipeline> all = new HashMap<>();
                for (RenderPipeline p : RenderPipelines.requiredPipelines()) all.put(p.getLocation().getPath(), p);
                for (RenderPipeline p : RenderPipelines.optionalPipelines()) all.put(p.getLocation().getPath(), p);
                for (int fade : new int[]{0, 16}) for (int compact : new int[]{0, 8}) for (int layer = 0; layer < 3; layer++) {
                    deadline(); int code = fade | compact | layer;
                    String name = new String[]{"solid", "cutout", "translucent"}[layer] + "_lod_" + (compact != 0 ? "compact" : "greedy")
                        + (fade != 0 ? "_fade" : "") + "_terrain_cluster";
                    RenderPipeline p = all.get("pipeline/" + name); require(p != null, "Missing frozen pipeline " + name);
                    try (CompiledRenderPipeline compiled = device.compilePipeline(p, source, workers).get(30, TimeUnit.SECONDS).finishCompile()) {
                        require(compiled != null && installed && hookFailure == null, "Production compile/recorder failed: " + hookFailure);
                        require(RenderPipelines.lodPipeline(code) == p, "Production layerCode dispatch differs: " + code);
                        MetalRenderPipeline metal = (MetalRenderPipeline)((FrontendRenderPipeline)compiled).backendRenderPipeline();
                        export(code, metal);
                    }
                }
                require(TerrainShaderVariants.VARIANT_FAILURES.get() == 0 && TerrainVertexMsl.FAILURES.get() == 0, "Production variant compilation failed");
            } finally { workers.shutdown(); workers.awaitTermination(5, TimeUnit.SECONDS); device.close(); }
            JsonObject provenance = JsonParser.parseString(Files.readString(out.resolve("inputs.json"))).getAsJsonObject();
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schema", "farsight-snapshot-shaders-1"); manifest.put("complete", true);
            manifest.put("provenance", provenance); manifest.put("shaderAssetsSha256", ASSETS);
            manifest.put("options", Map.of("sampling", "nearest", "variantModes", "vfog,hvary", "useVariant", true,
                "terrainHandVertex", false, "shaderFlags", MetalRenderPipeline.SHADER_FLAGS, "languageVersion", MetalFormats.LANGUAGE_VERSION_3_2,
                "mathMode", "fast", "sampleCount", 1, "alphaToCoverage", false));
            manifest.put("pipelines", new ArrayList<>(PIPELINES.values()));
            manifest.put("notes", List.of("Numeric state values are Metal enums from the production Prepared.writeDescriptor, not RenderPearl enums.",
                "Files are relative to this manifest. Binary descriptor handles/label are zeroed; all other bytes are production state.",
                "Each source pair was recorded at actual createHandle and matched to production handle() selection and existing ShaderCache entries.",
                "No geometry, texture upload, draw, frame, or FPS measurement. Full snapshot replay remains the caller's responsibility."));
            Files.writeString(out.resolve("manifest.json"), JSON.toJson(manifest) + "\n");
            System.out.println("EXPORTED " + PIPELINES.size() + " layerCodes; " + CALLS.size() + " actual production PSOs; " + (System.nanoTime()-START)/1e9 + " seconds");
        }

        static void export(int code, MetalRenderPipeline pipeline) throws Exception {
            long selected = pipeline.handle(); Call call = CALLS.get(selected);
            require(call != null, "Selected PSO was not recorded");
            MetalRenderPipeline.Prepared p = (MetalRenderPipeline.Prepared)call.prepared();
            String selectedField = null;
            for (String name : List.of("handle", "rgssHandle", "variantHandle", "rgssVariantHandle", "handHandle", "handRgssHandle", "handVariantHandle", "handRgssVariantHandle")) {
                Field f = MetalRenderPipeline.class.getDeclaredField(name); f.setAccessible(true);
                if (f.getLong(pipeline) == selected) { require(selectedField == null, "Aliased PSO handles"); selectedField = name; }
            }
            require("handle".equals(selectedField) || "variantHandle".equals(selectedField), "Not a nearest production handle: " + selectedField);
            cacheContains(pipeline.device().shaderCache(), MtlNative.STAGE_VERTEX, call.vertex());
            cacheContains(pipeline.device().shaderCache(), MtlNative.STAGE_FRAGMENT, call.fragment());
            String vertexFile = "layer-" + code + ".vert.metal", fragmentFile = "layer-" + code + ".frag.metal";
            Files.writeString(out.resolve(vertexFile), call.vertex()); Files.writeString(out.resolve(fragmentFile), call.fragment());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("layerCode", code); m.put("pipeline", p.name); m.put("sourceQuadBytes", (code & 8) != 0 ? 48 : 112);
            m.put("fade", (code & 16) != 0); m.put("selectedHandleField", selectedField); m.put("selectedVariant", call.variant().trim());
            m.put("pairedVariantApplied", selectedField.equals("variantHandle"));
            m.put("vertexFile", prefix + vertexFile); m.put("fragmentFile", prefix + fragmentFile);
            m.put("vertexEntry", p.vertex.entryPoint()); m.put("fragmentEntry", p.fragment.entryPoint());
            m.put("vertexSha256", sha(call.vertex())); m.put("fragmentSha256", sha(call.fragment()));
            m.put("vertexSignature", signature(call.vertex(), "vertex")); m.put("fragmentSignature", signature(call.fragment(), "fragment"));
            m.put("bindings", Map.of("vertex", bindings(call.vertex(), "vertex"), "fragment", bindings(call.fragment(), "fragment")));
            List<Object> uniforms = new ArrayList<>();
            for (int i = 0; i < p.uniformName.length; i++) uniforms.add(Map.of("name", p.uniformName[i], "kind", p.bindings.kind()[i],
                "metalSlot", p.bindings.slot()[i], "stageMask", p.uniformStages[i]));
            m.put("uniformBindings", uniforms);
            boolean pulling = !call.vertex().contains("[[attribute(");
            require(pulling && !signature(call.vertex(), "vertex").contains("[[stage_in]]"), "Expected pulling vertex shader");
            m.put("vertexDescriptorRequired", false);
            // Read descriptor bytes rather than manually re-derive fixed-function state.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long d = stack.ncalloc(8, 1, (int)MtlNative.PIPELINE_DESC_SIZEOF); p.writeDescriptor(d, 0, 0, 0);
                for (String[] f : new String[][]{{"flags","FLAGS"},{"primitiveType","PRIMITIVE_TYPE"},{"topologyClass","TOPOLOGY_CLASS"},
                    {"emulation","EMULATION"},{"cullMode","CULL_MODE"},{"fillMode","FILL_MODE"},{"frontFacing","FRONT_FACING"},
                    {"depthCompare","DEPTH_COMPARE"},{"depthWriteEnabled","DEPTH_WRITE_ENABLED"},{"pushConstantSize","PUSH_CONSTANT_SIZE"},
                    {"pushConstantStages","PUSH_CONSTANT_STAGES"},{"requiredVertexBufferMask","REQUIRED_VERTEX_BUFFER_MASK"}})
                    m.put(f[0], MemoryUtil.memGetInt(d + offset("PIPELINE_DESC_" + f[1])));
                for (String[] f : new String[][]{{"depthBiasConstant","DEPTH_BIAS_CONSTANT"},{"depthBiasSlopeScale","DEPTH_BIAS_SLOPE_SCALE"},{"depthBiasClamp","DEPTH_BIAS_CLAMP"}})
                    m.put(f[0], MemoryUtil.memGetFloat(d + offset("PIPELINE_DESC_" + f[1])));
                List<Object> colors = new ArrayList<>();
                for (int i = 0; i < p.colorTargetCount; i++) {
                    long c = d + MtlNative.PIPELINE_DESC_COLOR_TARGETS + i * MtlNative.COLOR_TARGET_SIZEOF;
                    Map<String,Object> color = new LinkedHashMap<>(); color.put("index", i);
                    for (String[] f : new String[][]{{"pixelFormat","PIXEL_FORMAT"},{"writeMask","WRITE_MASK"},{"blendingEnabled","BLENDING_ENABLED"},
                        {"rgbBlendOperation","RGB_BLEND_OPERATION"},{"alphaBlendOperation","ALPHA_BLEND_OPERATION"},
                        {"sourceRGBBlendFactor","SOURCE_RGB_BLEND_FACTOR"},{"destinationRGBBlendFactor","DESTINATION_RGB_BLEND_FACTOR"},
                        {"sourceAlphaBlendFactor","SOURCE_ALPHA_BLEND_FACTOR"},{"destinationAlphaBlendFactor","DESTINATION_ALPHA_BLEND_FACTOR"}})
                        color.put(f[0], MemoryUtil.memGetInt(c + offset("COLOR_TARGET_" + f[1])));
                    colors.add(color);
                }
                require(colors.size() == 1, "Expected one color target");
                m.put("colorTarget", colors.getFirst()); m.put("depthPixelFormat", MetalFormats.PIXEL_FORMAT_DEPTH32_FLOAT);
                m.put("vertexAttributes", rows(p.attributes, new String[]{"location", "format", "offset", "bufferIndex"}));
                m.put("vertexLayouts", rows(p.layouts, new String[]{"bufferIndex", "stride", "stepFunction", "stepRate"}));
                Map<String,Object> masks = new LinkedHashMap<>();
                for (String[] f : new String[][]{{"buffers","BUFFER_MASK"},{"textures","TEXTURE_MASK"},{"samplers","SAMPLER_MASK"}}) {
                    long at = d + offset("PIPELINE_DESC_" + f[1]);
                    masks.put(f[0], List.of(Integer.toUnsignedLong(MemoryUtil.memGetInt(at)), Integer.toUnsignedLong(MemoryUtil.memGetInt(at+4))));
                }
                m.put("descriptorStageMasksVertexFragment", masks);
                String file = "layer-" + code + ".pso.bin";
                byte[] bytes = new byte[(int)MtlNative.PIPELINE_DESC_SIZEOF]; MemoryUtil.memByteBuffer(d, bytes.length).get(bytes);
                Files.write(out.resolve(file), bytes); m.put("descriptorFile", prefix + file); m.put("descriptorSha256", sha(bytes));
            }
            PIPELINES.put(Integer.toString(code), m);
            System.out.println("layerCode=" + code + " " + p.name + " selected=" + call.variant().trim() + " depth=" + m.get("depthCompare")
                + " write=" + m.get("depthWriteEnabled") + " cull=" + m.get("cullMode") + " front=" + m.get("frontFacing"));
        }

        static void cacheContains(MetalRenderPipeline.ShaderCache cache, int stage, String source) throws Exception {
            Object key = MetalRenderPipeline.ShaderCache.key(source.getBytes(StandardCharsets.UTF_8), stage, MetalRenderPipeline.SHADER_FLAGS, MetalFormats.LANGUAGE_VERSION_3_2);
            Field f = cache.getClass().getDeclaredField("functions"); f.setAccessible(true);
            Object value = ((Map<?,?>)f.get(cache)).get(key);
            require(value instanceof CompletableFuture<?> future && future.isDone() && !future.isCompletedExceptionally()
                && ((Long)future.getNow(null)) != 0, "Recorded selected source not present in production ShaderCache");
        }
        static long offset(String name) throws Exception { return ((Number)MtlNative.class.getField(name).get(null)).longValue(); }
        static List<Object> rows(int[] values, String[] keys) {
            List<Object> out = new ArrayList<>();
            for (int i=0;i<values.length;i+=keys.length) { Map<String,Object> row=new LinkedHashMap<>(); for(int j=0;j<keys.length;j++)row.put(keys[j],values[i+j]); out.add(row); }
            return out;
        }
        static String signature(String source, String stage) {
            return source.lines().filter(s -> s.startsWith(stage + " ")).findFirst().orElseThrow();
        }
        static List<Object> bindings(String source, String stage) {
            List<Object> bindings = new ArrayList<>();
            Matcher m = Pattern.compile("(\\w+) \\[\\[(buffer|texture|sampler)\\((\\d+)\\)\\]\\]").matcher(signature(source, stage));
            while (m.find()) bindings.add(Map.of("name", m.group(1), "kind", m.group(2), "metalSlot", Integer.parseInt(m.group(3))));
            require(!bindings.isEmpty(), "No parsed bindings"); return bindings;
        }
        static ShaderSource source() {
            Map<Identifier, ShaderSource.CachedIncludeSource> includes = new ConcurrentHashMap<>();
            return new ShaderSource() {
                public String getShader(Identifier id, ShaderType stage) { return resource("assets/"+id.getNamespace()+"/shaders/"+id.getPath()+(stage==ShaderType.VERTEX?".vsh":".fsh")); }
                public CachedIncludeSource getInclude(Identifier id) { return includes.computeIfAbsent(id, k -> CachedIncludeSource.create(k, resource("assets/"+k.getNamespace()+"/shaders/include/"+k.getPath()))); }
                public void close() { includes.values().forEach(CachedIncludeSource::close); }
            };
        }
        static String resource(String path) {
            try (InputStream in = LodSnapshotShaders.class.getClassLoader().getResourceAsStream(path)) {
                require(in != null, "Missing frozen resource " + path); byte[] bytes = in.readAllBytes();
                synchronized(ASSETS) { ASSETS.put(path, sha(bytes)); }
                return new String(bytes, StandardCharsets.UTF_8);
            } catch(IOException e) { throw new UncheckedIOException(e); }
        }
        static String sha(String text) { return sha(text.getBytes(StandardCharsets.UTF_8)); }
        static String sha(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(NoSuchAlgorithmException e) { throw new AssertionError(e); } }
        static void require(boolean b, String why) { if(!b)throw new AssertionError(why); }
        static void deadline() { require(System.nanoTime()-START < 55_000_000_000L, "Export exceeded compile budget"); }
    }
}
