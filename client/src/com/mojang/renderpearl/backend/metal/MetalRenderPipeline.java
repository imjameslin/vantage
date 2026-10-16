package com.mojang.renderpearl.backend.metal;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * {@link BackendRenderPipeline} for Metal (spec 2.5, 3; CONTRACT.md 7). OWNER: I5 (java-pipeline+integration).
 *
 * <p>The public final arrays are the binding table I4 reads on the hot path (index = uniform index =
 * SPIR-V binding = position in {@code CreateInfo.uniforms()}): for every {@code setUniform(i, v)} the
 * encoder records the Metal slot {@code uniformSlot[i]} for the stages {@code uniformStages[i]}, dispatching
 * on {@code uniformKind[i]}. Arrays are never mutated after construction.
 *
 * <p>{@code close()} (render thread, PipelineCache clear) marks the pipeline closed and defers the release of
 * {@link #handle()} through the timeline; it is idempotent.
 *
 * <p>Compilation is split in two halves so that every decision can be tested without a Metal device:
 * {@link #prepare} (pure Java + spvc: binding table, MSL per stage, masks, the whole
 * {@code mcmtl_pipeline_desc} content as a {@link Prepared}) and the native half in {@link #compile}
 * (shader functions through the {@link ShaderCache}, {@link Prepared#writeDescriptor}, mcmtl_pipeline_create).
 */
public final class MetalRenderPipeline implements BackendRenderPipeline {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** UNIFORM_BUFFER: SET_BUFFER at [[buffer(uniformSlot[i])]], value is a GpuBufferSlice. */
    public static final int KIND_UNIFORM_BUFFER = 0;
    /** COMBINED_IMAGE_SAMPLER: SET_TEXTURE at [[texture/sampler(uniformSlot[i])]], value is a TextureViewAndSampler. */
    public static final int KIND_SAMPLED_TEXTURE = 1;
    /** TEXEL_BUFFER: SET_TEXTURE (sampler 0) with the buffer's texel view, value is a whole-buffer GpuBufferSlice. */
    public static final int KIND_TEXEL_BUFFER = 2;

    /** Shader flags passed to mcmtl_shader_create (part of the shader-cache key). M1: none (fast math, no invariance). */
    static final int SHADER_FLAGS = 0;

    private final MetalDevice device;
    private final long handle;
    /**
     * The UseRgss == 1 specialization of a terrain pipeline ({@link #handle} is then the UseRgss != 1 one), or 0
     * (TerrainShaderVariants).
     */
    private final long rgssHandle;
    /**
     * Experimental nearest-path variant of a specialized terrain pipeline (TerrainShaderVariants#nearestVariant,
     * CONTRACT.md 16.R8), picked instead of {@link #handle} while {@code TerrainShaderVariants.useVariant} is set; 0 = none.
     */
    private long variantHandle;
    /**
     * Round 11 (CONTRACT.md 16.R11): the RGSS PSO with the same paired variant (fog per vertex, half varyings), or 0; and
     * whether the variant leaves texture sampling untouched (paired modes only), so it also serves anisotropic frames.
     */
    private long rgssVariantHandle;
    private boolean variantKeepsSampling;
    /**
     * Round 12 (c) (CONTRACT.md 16.R12-C, {@link TerrainVertexMsl}): the same four PSOs with the hand-written pull-form vertex
     * function (byte-identical output), picked while {@code TerrainVertexMsl.useHand}; 0 = not built.
     */
    private long handHandle;
    private long handRgssHandle;
    private long handVariantHandle;
    private long handRgssVariantHandle;
    /** Render thread only (close/isClosed); volatile because nothing guarantees the caller's thread. */
    private volatile boolean closed;

    /** CreateInfo.name(). */
    public final String name;
    /** Number of uniform bindings (<= 32). */
    public final int uniformCount;
    /** Uniform names (strict-mode messages). */
    public final String[] uniformName;
    /** KIND_* per binding. */
    public final int[] uniformKind;
    /** Metal index per binding (buffer index for UBOs, texture/sampler index otherwise). */
    public final int[] uniformSlot;
    /** MtlNative.STAGE_* mask of the stages whose MSL reads the binding; 0 = unused (setUniform is dropped). */
    public final int[] uniformStages;
    /** MTLPixelFormat of TEXEL_BUFFER bindings (0 otherwise). */
    public final int[] texelPixelFormat;
    /** Bytes per texel of TEXEL_BUFFER bindings (0 otherwise). */
    public final int[] texelBytesPerTexel;
    /** Bit i set iff {@code uniformStages[i] != 0} (strict-bindings check before draws). */
    public final int usedUniformMask;
    /** CreateInfo.pushConstantsSize() (bytes copied by RenderPass.pushConstants). */
    public final int pushConstantSize;
    /** MtlNative.STAGE_* mask of stages reading the push-constant block. */
    public final int pushConstantStages;
    /** RenderPearl vertex-buffer slots (0..15) the pipeline reads (strict-mode check). */
    public final int vertexBufferSlotMask;
    /** CreateInfo.primitiveTopology() (fan emulation is native; informational). */
    public final PrimitiveTopology topology;

    /** Arrays are taken over, not copied. */
    public MetalRenderPipeline(
        final MetalDevice device,
        final long handle,
        final String name,
        final String[] uniformName,
        final int[] uniformKind,
        final int[] uniformSlot,
        final int[] uniformStages,
        final int[] texelPixelFormat,
        final int[] texelBytesPerTexel,
        final int pushConstantSize,
        final int pushConstantStages,
        final int vertexBufferSlotMask,
        final PrimitiveTopology topology
    ) {
        this(
            device, handle, 0L, name, uniformName, uniformKind, uniformSlot, uniformStages, texelPixelFormat, texelBytesPerTexel, pushConstantSize,
            pushConstantStages, vertexBufferSlotMask, topology
        );
    }

    /** {@code rgssHandle}: the UseRgss == 1 specialization of a terrain pipeline, or 0 (TerrainShaderVariants). */
    public MetalRenderPipeline(
        final MetalDevice device,
        final long handle,
        final long rgssHandle,
        final String name,
        final String[] uniformName,
        final int[] uniformKind,
        final int[] uniformSlot,
        final int[] uniformStages,
        final int[] texelPixelFormat,
        final int[] texelBytesPerTexel,
        final int pushConstantSize,
        final int pushConstantStages,
        final int vertexBufferSlotMask,
        final PrimitiveTopology topology
    ) {
        this.device = device;
        this.handle = handle;
        this.rgssHandle = rgssHandle;
        this.name = name;
        this.uniformCount = uniformKind.length;
        this.uniformName = uniformName;
        this.uniformKind = uniformKind;
        this.uniformSlot = uniformSlot;
        this.uniformStages = uniformStages;
        this.texelPixelFormat = texelPixelFormat;
        this.texelBytesPerTexel = texelBytesPerTexel;
        int used = 0;
        for (int i = 0; i < uniformStages.length && i < 32; i++) {
            if (uniformStages[i] != 0) {
                used |= 1 << i;
            }
        }
        this.usedUniformMask = used;
        this.pushConstantSize = pushConstantSize;
        this.pushConstantStages = pushConstantStages;
        this.vertexBufferSlotMask = vertexBufferSlotMask;
        this.topology = topology;
    }

    /**
     * Compile worker entry point, called by {@link MetalDevice#compilePipeline} between
     * {@link MetalDevice#beginCompile()} and {@link MetalDevice#endCompile()} (test harnesses must do the same
     * or use a {@link MetalDevice#forTesting} device) (spec 2.2 compilePipeline, 2.5, 3.3): binding table, MSL per stage
     * ({@link MslTranslator}), functions via {@link ShaderCache} (mcmtl_shader_create), mcmtl_pipeline_create
     * (vertex descriptor, color targets, depth state, raster state, masks, precompiled variants: Depth32Float
     * always, Invalid iff depthStencilState == null). Returns {@code () -> pipeline} or
     * {@link BackendRenderPipeline.Pending#NULL}; never throws, never needs the render thread.
     */
    public static BackendRenderPipeline.Pending compile(final MetalDevice device, final BackendRenderPipeline.CreateInfo info) {
        String pipelineName = info == null ? "<null>" : info.name();
        try {
            Prepared prepared = prepare(info);
            MetalRenderPipeline pipeline = create(device, prepared);
            return () -> pipeline;
        } catch (MslTranslator.MslTranslationException e) {
            LOGGER.error("Couldn't compile Metal pipeline {}: {}", pipelineName, e.getMessage());
            return BackendRenderPipeline.Pending.NULL;
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            LOGGER.error("Couldn't compile Metal pipeline {}", pipelineName, t);
            return BackendRenderPipeline.Pending.NULL;
        }
    }

    /**
     * Native half of {@link #compile}: functions through the device's {@link ShaderCache}, then
     * {@code mcmtl_pipeline_create} with {@link Prepared#writeDescriptor}. Shader functions stay owned by the
     * cache whatever happens here.
     */
    static MetalRenderPipeline create(final MetalDevice device, final Prepared prepared) throws MslTranslator.MslTranslationException {
        String[] rgss = TerrainShaderVariants.fragmentVariants(prepared.fragmentShader, prepared.fragment.msl());
        if (rgss.length == 1) {
            return prepared.toPipeline(device, createHandle(device, prepared, rgss[0], ""), 0L);
        }

        // Terrain: one PSO per UseRgss value (TerrainShaderVariants); handle() picks the one matching this frame.
        long nearest = createHandle(device, prepared, rgss[0], " [nearest]");
        long rgssHandle;
        try {
            rgssHandle = createHandle(device, prepared, rgss[1], " [rgss]");
        } catch (MslTranslator.MslTranslationException | RuntimeException e) {
            MtlNative.release(device.handle(), nearest);
            throw e;
        }

        MetalRenderPipeline pipeline = prepared.toPipeline(device, nearest, rgssHandle);
        String[] v = TerrainShaderVariants.variant(prepared.name, prepared.vertex.msl(), rgss[0]);
        TerrainShaderVariants.dumpVertex(prepared.name, prepared.vertex.msl(), v == null ? null : v[0]);
        if (v != null) {
            try {
                pipeline.variantHandle = createHandle(device, prepared, v[0], v[1], " [nearest variant]");
                String[] r = TerrainShaderVariants.rgssVariant(prepared.vertex.msl(), rgss[1]);
                if (r != null) {
                    pipeline.rgssVariantHandle = createHandle(device, prepared, r[0], r[1], " [rgss variant]");
                    pipeline.variantKeepsSampling = true;
                }
            } catch (MslTranslator.MslTranslationException | RuntimeException e) {
                // A variant that does not compile must never cost the pipeline: keep the exact PSOs only.
                LOGGER.warn("Terrain shader variant {} failed for {}: {}", TerrainShaderVariants.variantModes, prepared.name, e.getMessage());
                TerrainShaderVariants.VARIANT_FAILURES.incrementAndGet();
                if (pipeline.variantHandle != 0L) {
                    MtlNative.release(device.handle(), pipeline.variantHandle);
                    pipeline.variantHandle = 0L;
                }
            }
        }
        if (TerrainVertexMsl.build) {
            buildHandSet(device, prepared, pipeline, rgss);
        }
        return pipeline;
    }

    /**
     * Round 12 (c): the hand-written vertex function's twins of the nearest / RGSS PSOs and of the round-11 variant PSOs (the
     * paired rewrite applies to the hand-written text unchanged). Any failure keeps the translated set only.
     */
    private static void buildHandSet(final MetalDevice device, final Prepared prepared, final MetalRenderPipeline pipeline, final String[] rgss) {
        String hand = TerrainVertexMsl.pullVertex(prepared.vertex.msl());
        if (hand == null) {
            return;
        }
        try {
            pipeline.handHandle = createHandle(device, prepared, hand, rgss[0], " [nearest hand]");
            pipeline.handRgssHandle = createHandle(device, prepared, hand, rgss[1], " [rgss hand]");
            if (pipeline.variantHandle != 0L && pipeline.variantKeepsSampling) {
                String[] v = TerrainShaderVariants.pairedVariant(hand, rgss[0]);
                String[] r = TerrainShaderVariants.pairedVariant(hand, rgss[1]);
                if (v != null && r != null) {
                    pipeline.handVariantHandle = createHandle(device, prepared, v[0], v[1], " [nearest variant hand]");
                    pipeline.handRgssVariantHandle = createHandle(device, prepared, r[0], r[1], " [rgss variant hand]");
                }
            }
            TerrainVertexMsl.BUILT.incrementAndGet();
        } catch (MslTranslator.MslTranslationException | RuntimeException e) {
            LOGGER.warn("Hand-written terrain vertex function failed for {}: {}", prepared.name, e.getMessage());
            TerrainVertexMsl.FAILURES.incrementAndGet();
            pipeline.releaseHandSet(device);
        }
    }

    private void releaseHandSet(final MetalDevice device) {
        long[] handles = {this.handHandle, this.handRgssHandle, this.handVariantHandle, this.handRgssVariantHandle};
        for (long h : handles) {
            if (h != 0L) {
                MtlNative.release(device.handle(), h);
            }
        }
        this.handHandle = 0L;
        this.handRgssHandle = 0L;
        this.handVariantHandle = 0L;
        this.handRgssVariantHandle = 0L;
    }

    /** One native pipeline of {@code prepared} with the given fragment MSL (the vertex stage is prepared.vertex). */
    private static long createHandle(final MetalDevice device, final Prepared prepared, final String fragmentMsl, final String variant)
        throws MslTranslator.MslTranslationException {
        return createHandle(device, prepared, prepared.vertex.msl(), fragmentMsl, variant);
    }

    private static long createHandle(final MetalDevice device, final Prepared prepared, final String vertexMsl, final String fragmentMsl, final String variant)
        throws MslTranslator.MslTranslationException {
        ShaderCache cache = device.shaderCache();
        String[] probe = LodShaderProbe.rgb ? LodShaderProbe.apply(prepared.name, vertexMsl, fragmentMsl) : null;
        String vertexLabel = device.debugLabels() ? prepared.name + " (vertex)" : null;
        String fragmentLabel = device.debugLabels() ? prepared.name + variant + " (fragment)" : null;
        long vertexFunction = cache.function(MtlNative.STAGE_VERTEX, probe == null ? vertexMsl : probe[0], prepared.vertex.entryPoint(), vertexLabel);
        long fragmentFunction = cache.function(MtlNative.STAGE_FRAGMENT, probe == null ? fragmentMsl : probe[1], prepared.fragment.entryPoint(), fragmentLabel);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long desc = stack.ncalloc(8, 1, (int)MtlNative.PIPELINE_DESC_SIZEOF);
            long out = stack.ncalloc(8, 1, 8);
            long label = device.debugLabels() ? MemoryUtil.memAddress(stack.UTF8(prepared.name + variant)) : 0L;
            prepared.writeDescriptor(desc, vertexFunction, fragmentFunction, label);
            int status = MtlNative.pipelineCreate(device.handle(), desc, out);
            if (status != MtlNative.OK) {
                throw new MslTranslator.MslTranslationException(
                    "mcmtl_pipeline_create failed: " + MtlNative.statusName(status) + ": " + MtlNative.lastError()
                );
            }

            return MemoryUtil.memGetLong(out);
        }
    }

    /**
     * Pure half of {@link #compile} (no Metal device needed; spvc only): validates {@code info}, assigns the
     * binding table, translates both stages and computes every {@code mcmtl_pipeline_desc} field. Must run
     * while the CreateInfo's SPIR-V modules are alive (inside compilePipeline); the result keeps no module.
     */
    public static Prepared prepare(final BackendRenderPipeline.CreateInfo info) throws MslTranslator.MslTranslationException {
        if (info == null) {
            throw new MslTranslator.MslTranslationException("null CreateInfo");
        }

        SpvModule vertexModule = null;
        SpvModule fragmentModule = null;
        for (BackendRenderPipeline.CreateInfo.Shader shader : info.shaders()) {
            SpvModule module = shader.module();
            if (module.type() == ShaderType.VERTEX && vertexModule == null) {
                vertexModule = module;
            } else if (module.type() == ShaderType.FRAGMENT && fragmentModule == null) {
                fragmentModule = module;
            } else {
                throw new MslTranslator.MslTranslationException("Duplicate " + module.type().getName() + " shader");
            }
        }

        if (vertexModule == null || fragmentModule == null) {
            throw new MslTranslator.MslTranslationException("A Metal pipeline needs a vertex and a fragment shader");
        }

        int pushConstantSize = info.pushConstantsSize();
        if (pushConstantSize < 0 || pushConstantSize > MtlNative.MAX_PUSH_CONSTANT_BYTES) {
            throw new MslTranslator.MslTranslationException("Push-constant size " + pushConstantSize + " outside 0.." + MtlNative.MAX_PUSH_CONSTANT_BYTES);
        }

        List<BindGroupLayout.UniformDescription> uniforms = info.uniforms();
        MslTranslator.BindingTable bindings = MslTranslator.assignBindings(uniforms);
        MslTranslator.StageResult vertex = MslTranslator.translate(vertexModule, uniforms, bindings, pushConstantSize);
        MslTranslator.StageResult fragment = MslTranslator.translate(fragmentModule, uniforms, bindings, pushConstantSize);
        if (TerrainShaderVariants.DIAG_FLAT) {
            fragment = fragment.withMsl(TerrainShaderVariants.diagFragment(info.name(), fragment.msl()));
        }

        // CONTRACT.md 16.R11: the per-vertex fog variant reads the Fog UBO in the vertex stage too.
        boolean terrainFragment = info.shaders().stream()
            .anyMatch(s -> s.module().type() == ShaderType.FRAGMENT && TerrainShaderVariants.TERRAIN_FRAGMENT.equals(s.name()));
        if (terrainFragment && TerrainShaderVariants.vertexNeedsFog(info.name())) {
            for (int i = 0; i < uniforms.size(); i++) {
                if ("Fog".equals(uniforms.get(i).name()) && bindings.kind()[i] == KIND_UNIFORM_BUFFER && bindings.slot()[i] == TerrainShaderVariants.FOG_SLOT
                    && (fragment.usedUniformMask() & 1L << i) != 0L) {
                    vertex = vertex.withUniform(i, bindings.slot()[i]);
                }
            }
        }

        // Stage 3 r3 (CONTRACT 16.S3-LOD-R3): the LOD occlusion box pipeline: both stages hand-written; the fragment stage writes
        // the LodOcclusion buffer (bound through its uniform slot).
        if (info.name().contains("lod_occlusion_terrain_cluster")) {
            int occl = -1;
            for (int i = 0; i < uniforms.size(); i++) {
                if ("LodOcclusion".equals(uniforms.get(i).name()) && bindings.kind()[i] == KIND_UNIFORM_BUFFER) {
                    occl = i;
                }
            }
            String[] msl = occl < 0 ? null : TerrainVertexMsl.lodOcclusion(vertex.msl(), bindings.slot()[occl]);
            TerrainVertexMsl.setClusterReady(info.name(), msl != null);
            if (msl == null) {
                LOGGER.warn("LOD occlusion pipeline {} not built (uniform index {}, vertex signature {})", info.name(), occl,
                    vertex.msl().lines().filter(l -> l.startsWith("vertex ")).findFirst().orElse("?"));
            }
            if (msl != null) {
                vertex = vertex.withMsl(msl[0]);
                fragment = fragment.withMsl(msl[1]).withUniform(occl, bindings.slot()[occl]);
            }
            return new Prepared(info, bindings, vertex, fragment);
        }

        // Round 12 (a) (CONTRACT.md 16.R12-A): the cluster pipelines' vertex stage is the hand-written pulling function.
        // Its two extra inputs are vertex buffer slots 2 and 3 (Metal buffers 28 and 27, CONTRACT 6.1), bound by the draw.
        if (terrainFragment && info.name().endsWith("_terrain_cluster")) {
            String msl = info.name().contains("_lod_compact_")
                ? TerrainVertexMsl.clusterVertexCompact(vertex.msl(), MtlNative.VERTEX_BUFFER_INDEX_BASE - TerrainVertexMsl.CLUSTER_QUADS_SLOT,
                    MtlNative.VERTEX_BUFFER_INDEX_BASE - TerrainVertexMsl.CLUSTER_ITEMS_SLOT)
                : info.name().contains("_lod_greedy_")
                ? TerrainVertexMsl.clusterVertexGreedy(vertex.msl(), MtlNative.VERTEX_BUFFER_INDEX_BASE - TerrainVertexMsl.CLUSTER_QUADS_SLOT,
                    MtlNative.VERTEX_BUFFER_INDEX_BASE - TerrainVertexMsl.CLUSTER_ITEMS_SLOT)
                : TerrainVertexMsl.clusterVertex(vertex.msl(), MtlNative.VERTEX_BUFFER_INDEX_BASE - TerrainVertexMsl.CLUSTER_QUADS_SLOT,
                    MtlNative.VERTEX_BUFFER_INDEX_BASE - TerrainVertexMsl.CLUSTER_ITEMS_SLOT);
            // Without it (a resource pack's terrain shader) the pipeline compiles as the multidraw one and is never drawn
            // (TerrainClusters checks TerrainVertexMsl.clusterPipelinesReady()).
            TerrainVertexMsl.setClusterReady(info.name(), msl != null);
            if (msl != null) {
                vertex = vertex.withMsl(msl);
            } else if (info.name().contains("_lod_")) {
                LOGGER.warn("LOD cluster vertex function for {} not built; its LOD layer will not be drawn", info.name());
            }
            if (msl != null && TerrainVertexMsl.LIT && info.name().contains("_lod_compact_")) {
                int lightmap = -1;
                List<BindGroupLayout.UniformDescription> us = info.uniforms();
                for (int i = 0; i < us.size(); i++) {
                    if (us.get(i).name().equals("Sampler2")) {
                        lightmap = i;
                    }
                }
                String lit = lightmap < 0 ? null : TerrainVertexMsl.litFragment(fragment.msl());
                if (lit == null) {
                    LOGGER.warn("LOD lit-merge fragment for {} not built; lit faces would be wrong, cluster pipeline disabled", info.name());
                    TerrainVertexMsl.setClusterReady(info.name(), false);
                } else {
                    fragment = fragment.withMsl(lit).withResources(1L << lightmap, 1 << 30, 1 << 2, 1 << 2);
                }
            }
            if ((info.name().contains("_lod_greedy_") || info.name().contains("_lod_compact_")) && System.getProperty("mc.lod.diag", "").contains("flatfs")) {
                // Diagnostics (stage 3 r2 cost attribution): LOD fragments output the vertex colour only.
                fragment = fragment.withMsl("#include <metal_stdlib>\nusing namespace metal;\nstruct main0_out\n{\n    float4 fragColor [[color(0)]];\n};\n"
                    + "struct main0_in\n{\n    float4 vertexColor [[user(locn2)]];\n};\n"
                    + "fragment main0_out main0(main0_in in [[stage_in]])\n{\n    main0_out out = {};\n    out.fragColor = in.vertexColor;\n    return out;\n}\n");
            }
        }

        return new Prepared(info, bindings, vertex, fragment);
    }

    public MetalDevice device() {
        return this.device;
    }

    /**
     * Native MCMTLPipeline handle written into SET_PIPELINE records: for a specialized terrain pipeline, the variant
     * matching the UseRgss value of the frame being recorded (TerrainShaderVariants).
     */
    public long handle() {
        if (this.rgssHandle != 0L) {
            boolean hand = this.handHandle != 0L && TerrainVertexMsl.useHand;
            if (TerrainShaderVariants.frameUsesRgss()) {
                if (this.rgssVariantHandle != 0L && TerrainShaderVariants.useVariant) {
                    return hand && this.handRgssVariantHandle != 0L ? this.handRgssVariantHandle : this.rgssVariantHandle;
                }
                return hand ? this.handRgssHandle : this.rgssHandle;
            }
            if (this.variantHandle != 0L && (this.variantKeepsSampling ? TerrainShaderVariants.useVariant : TerrainShaderVariants.frameUsesVariant())) {
                return hand && this.handVariantHandle != 0L ? this.handVariantHandle : this.variantHandle;
            }
            return hand ? this.handHandle : this.handle;
        }
        return this.handle;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    /**
     * Render thread (PipelineCache clear, post-chain close, shutdown). Idempotent. The PSO variants and the
     * depth-stencil state live in the native MCMTLPipeline, which is released through the deferred FIFO once
     * the GPU passed every recorded use (spec 2.5; the classic command buffer also retains it).
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;
        if (this.handle != 0L) {
            this.device.timeline().deferRelease(this.handle);
        }

        if (this.rgssHandle != 0L) {
            this.device.timeline().deferRelease(this.rgssHandle);
        }

        if (this.variantHandle != 0L) {
            this.device.timeline().deferRelease(this.variantHandle);
        }

        if (this.rgssVariantHandle != 0L) {
            this.device.timeline().deferRelease(this.rgssVariantHandle);
        }

        long[] hand = {this.handHandle, this.handRgssHandle, this.handVariantHandle, this.handRgssVariantHandle};
        for (long h : hand) {
            if (h != 0L) {
                this.device.timeline().deferRelease(h);
            }
        }
    }

    @Override
    public String toString() {
        return "MetalRenderPipeline[" + this.name + "]";
    }

    // =============================================================================================
    // Prepared: everything mcmtl_pipeline_create needs, computed without a device
    // =============================================================================================

    /**
     * The device-independent result of {@link #prepare}: the binding table, both translated stages, and every
     * field of {@code mcmtl_pipeline_desc} except the function handles and the label (CONTRACT.md 7.2).
     * Immutable; the arrays are owned by this object (accessors return them for tests and diagnostics: do not
     * modify).
     */
    public static final class Prepared {
        public final String name;
        /** CreateInfo.Shader name of the fragment stage (TerrainShaderVariants scope), or "" if none is named. */
        public final String fragmentShader;
        public final MslTranslator.BindingTable bindings;
        public final MslTranslator.StageResult vertex;
        public final MslTranslator.StageResult fragment;
        final String[] uniformName;
        final int[] uniformStages;
        final int[] texelPixelFormat;
        final int[] texelBytesPerTexel;
        final int pushConstantSize;
        final int pushConstantStages;
        final int vertexBufferSlotMask;
        final PrimitiveTopology topology;

        // mcmtl_pipeline_desc fields
        final int flags;
        final int primitiveType;
        final int topologyClass;
        final int emulation;
        final int cullMode;
        final int fillMode;
        final int depthCompare;
        final int depthWriteEnabled;
        final float depthBiasConstant;
        final float depthBiasSlopeScale;
        final int colorTargetCount;
        /** COLOR_TARGET_INTS ints per target: pixelFormat, writeMask, blendingEnabled, rgbOp, alphaOp, srcRGB, dstRGB, srcA, dstA. */
        final int[] colorTargets;
        final int attributeCount;
        /** 4 ints per attribute: location, format, offset, bufferIndex. */
        final int[] attributes;
        final int layoutCount;
        /** 4 ints per layout: bufferIndex, stride, stepFunction, stepRate. */
        final int[] layouts;
        final int[] bufferMask = new int[2];
        final int[] textureMask = new int[2];
        final int[] samplerMask = new int[2];
        final int[] textureCubeMask = new int[2];
        final int[] texelBufferMask = new int[2];
        final int requiredVertexBufferMask;
        final int[] precompileDepthFormats;

        static final int COLOR_TARGET_INTS = 9;

        Prepared(
            final BackendRenderPipeline.CreateInfo info,
            final MslTranslator.BindingTable bindings,
            final MslTranslator.StageResult vertex,
            final MslTranslator.StageResult fragment
        ) throws MslTranslator.MslTranslationException {
            this.name = info.name();
            String fragmentShader = "";
            for (BackendRenderPipeline.CreateInfo.Shader shader : info.shaders()) {
                if (shader.module().type() == ShaderType.FRAGMENT) {
                    fragmentShader = shader.name();
                }
            }

            this.fragmentShader = fragmentShader;
            this.bindings = bindings;
            this.vertex = vertex;
            this.fragment = fragment;
            this.topology = info.primitiveTopology();
            this.pushConstantSize = info.pushConstantsSize();

            // ---- binding table (CONTRACT.md 6.2) ----------------------------------------------
            List<BindGroupLayout.UniformDescription> uniforms = info.uniforms();
            int count = uniforms.size();
            this.uniformName = new String[count];
            this.uniformStages = new int[count];
            this.texelPixelFormat = new int[count];
            this.texelBytesPerTexel = new int[count];
            for (int i = 0; i < count; i++) {
                BindGroupLayout.UniformDescription uniform = uniforms.get(i);
                this.uniformName[i] = uniform.name();
                int stages = 0;
                if ((vertex.usedUniformMask() & 1L << i) != 0L) {
                    stages |= MtlNative.STAGE_VERTEX;
                }

                if ((fragment.usedUniformMask() & 1L << i) != 0L) {
                    stages |= MtlNative.STAGE_FRAGMENT;
                }

                this.uniformStages[i] = stages;
                if (bindings.kind()[i] == KIND_TEXEL_BUFFER) {
                    GpuFormat format = uniform.gpuFormat();
                    int pixelFormat = format == null ? MetalFormats.PIXEL_FORMAT_INVALID : MetalFormats.pixelFormat(format);
                    if (pixelFormat == MetalFormats.PIXEL_FORMAT_INVALID || !format.hasColorAspect()) {
                        throw new MslTranslator.MslTranslationException("Texel buffer " + uniform.name() + " has no Metal pixel format (" + format + ")");
                    }

                    this.texelPixelFormat[i] = pixelFormat;
                    this.texelBytesPerTexel[i] = format.blockSize();
                }
            }

            this.pushConstantStages = (vertex.usesPushConstants() ? MtlNative.STAGE_VERTEX : 0) | (fragment.usesPushConstants() ? MtlNative.STAGE_FRAGMENT : 0);

            // ---- fixed-function state (CONTRACT.md 7.2) -----------------------------------------
            PrimitiveTopology topology = info.primitiveTopology();
            this.primitiveType = MetalFormats.primitiveType(topology);
            this.topologyClass = MetalFormats.topologyClass(topology);
            this.emulation = MetalFormats.emulation(topology);
            this.cullMode = MetalFormats.cullMode(info.cull());
            this.fillMode = MetalFormats.fillMode(info.polygonMode());
            DepthStencilState depthState = info.depthStencilState();
            if (depthState != null) {
                this.flags = MtlNative.PIPELINE_FLAG_HAS_DEPTH_STATE;
                this.depthCompare = MetalFormats.compareFunction(depthState.depthTest());
                this.depthWriteEnabled = depthState.writeDepth() ? 1 : 0;
                this.depthBiasConstant = depthState.depthBiasConstant();
                this.depthBiasSlopeScale = depthState.depthBiasScaleFactor();
                this.precompileDepthFormats = new int[]{MetalFormats.PIXEL_FORMAT_DEPTH32_FLOAT};
            } else {
                this.flags = 0;
                this.depthCompare = MetalFormats.COMPARE_ALWAYS;
                this.depthWriteEnabled = 0;
                this.depthBiasConstant = 0.0F;
                this.depthBiasSlopeScale = 0.0F;
                this.precompileDepthFormats = new int[]{MetalFormats.PIXEL_FORMAT_DEPTH32_FLOAT, MetalFormats.PIXEL_FORMAT_INVALID};
            }

            // ---- color targets ----------------------------------------------------------------
            List<@Nullable ColorTargetState> targets = info.colorTargetStates();
            if (targets.size() > MtlNative.MAX_COLOR_ATTACHMENTS) {
                throw new MslTranslator.MslTranslationException(targets.size() + " color targets; Metal supports " + MtlNative.MAX_COLOR_ATTACHMENTS);
            }

            this.colorTargetCount = targets.size();
            this.colorTargets = new int[this.colorTargetCount * COLOR_TARGET_INTS];
            for (int i = 0; i < this.colorTargetCount; i++) {
                ColorTargetState target = targets.get(i);
                if (target == null) {
                    continue;
                }

                int pixelFormat = MetalFormats.pixelFormat(target.format());
                if (pixelFormat == MetalFormats.PIXEL_FORMAT_INVALID || !target.format().hasColorAspect()) {
                    throw new MslTranslator.MslTranslationException("Color target " + i + " has format " + target.format() + ", not a Metal color format");
                }

                int base = i * COLOR_TARGET_INTS;
                this.colorTargets[base] = pixelFormat;
                this.colorTargets[base + 1] = MetalFormats.colorWriteMask(target.writeMask());
                BlendFunction blend = target.blendFunction().orElse(null);
                if (blend != null) {
                    this.colorTargets[base + 2] = 1;
                    this.colorTargets[base + 3] = MetalFormats.blendOperation(blend.color().op());
                    this.colorTargets[base + 4] = MetalFormats.blendOperation(blend.alpha().op());
                    this.colorTargets[base + 5] = MetalFormats.blendFactor(blend.color().sourceFactor());
                    this.colorTargets[base + 6] = MetalFormats.blendFactor(blend.color().destFactor());
                    this.colorTargets[base + 7] = MetalFormats.blendFactor(blend.alpha().sourceFactor());
                    this.colorTargets[base + 8] = MetalFormats.blendFactor(blend.alpha().destFactor());
                } else {
                    // Metal's descriptor defaults (ignored while blending is disabled).
                    this.colorTargets[base + 3] = MetalFormats.BLEND_OPERATION_ADD;
                    this.colorTargets[base + 4] = MetalFormats.BLEND_OPERATION_ADD;
                    this.colorTargets[base + 5] = MetalFormats.BLEND_FACTOR_ONE;
                    this.colorTargets[base + 6] = MetalFormats.BLEND_FACTOR_ZERO;
                    this.colorTargets[base + 7] = MetalFormats.BLEND_FACTOR_ONE;
                    this.colorTargets[base + 8] = MetalFormats.BLEND_FACTOR_ZERO;
                }
            }

            // ---- vertex descriptor (spec 3.6) -----------------------------------------------------
            List<BackendRenderPipeline.CreateInfo.AttribBinding> attribs = info.attribBindings();
            if (attribs.size() > MtlNative.MAX_VERTEX_ATTRIBUTES) {
                throw new MslTranslator.MslTranslationException(attribs.size() + " vertex attributes; Metal supports " + MtlNative.MAX_VERTEX_ATTRIBUTES);
            }

            this.attributeCount = attribs.size();
            this.attributes = new int[this.attributeCount * 4];
            int attributeSlots = 0;
            long usedLocations = 0L;
            for (int i = 0; i < this.attributeCount; i++) {
                BackendRenderPipeline.CreateInfo.AttribBinding attrib = attribs.get(i);
                int slot = attrib.bufferSlot();
                int location = attrib.location();
                if (slot < 0 || slot >= MtlNative.MAX_VERTEX_BUFFERS) {
                    throw new MslTranslator.MslTranslationException("Vertex attribute " + location + " uses buffer slot " + slot + " (0.." + (MtlNative.MAX_VERTEX_BUFFERS - 1) + ")");
                }

                if (location < 0 || location >= MtlNative.MAX_VERTEX_ATTRIBUTES || (usedLocations & 1L << location) != 0L) {
                    throw new MslTranslator.MslTranslationException("Invalid or duplicate vertex attribute location " + location);
                }

                usedLocations |= 1L << location;
                int format = MetalFormats.vertexFormat(attrib.format());
                if (format == MetalFormats.VERTEX_FORMAT_INVALID) {
                    throw new MslTranslator.MslTranslationException("Vertex attribute " + location + " has format " + attrib.format() + ", which Metal cannot fetch");
                }

                if (attrib.offset() < 0 || (attrib.offset() & 3) != 0) {
                    throw new MslTranslator.MslTranslationException(
                        "Vertex attribute " + location + " has offset " + attrib.offset() + "; Metal requires a multiple of 4"
                    );
                }

                int base = i * 4;
                this.attributes[base] = location;
                this.attributes[base + 1] = format;
                this.attributes[base + 2] = attrib.offset();
                this.attributes[base + 3] = MtlNative.VERTEX_BUFFER_INDEX_BASE - slot;
                attributeSlots |= 1 << slot;
            }

            List<BackendRenderPipeline.CreateInfo.VertexBuffer> buffers = info.vertexBuffers();
            int[] layouts = new int[MtlNative.MAX_VERTEX_BUFFERS * 4];
            int layoutCount = 0;
            int layoutSlots = 0;
            for (BackendRenderPipeline.CreateInfo.VertexBuffer buffer : buffers) {
                int slot = buffer.bufferSlot();
                if (slot < 0 || slot >= MtlNative.MAX_VERTEX_BUFFERS || (attributeSlots & 1 << slot) == 0) {
                    continue; // no attribute reads it: no layout (the shader may use gl_VertexIndex only)
                }

                if ((layoutSlots & 1 << slot) != 0) {
                    throw new MslTranslator.MslTranslationException("Vertex buffer slot " + slot + " declared twice");
                }

                if (buffer.stride() <= 0 || (buffer.stride() & 3) != 0) {
                    throw new MslTranslator.MslTranslationException("Vertex buffer slot " + slot + " has stride " + buffer.stride() + "; Metal requires a positive multiple of 4");
                }

                if (buffer.stepRate() < 0) {
                    throw new MslTranslator.MslTranslationException("Vertex buffer slot " + slot + " has step rate " + buffer.stepRate());
                }

                int base = layoutCount * 4;
                layouts[base] = MtlNative.VERTEX_BUFFER_INDEX_BASE - slot;
                layouts[base + 1] = buffer.stride();
                layouts[base + 2] = buffer.stepRate() > 0 ? MetalFormats.VERTEX_STEP_PER_INSTANCE : MetalFormats.VERTEX_STEP_PER_VERTEX;
                layouts[base + 3] = Math.max(1, buffer.stepRate());
                layoutCount++;
                layoutSlots |= 1 << slot;
            }

            if (layoutSlots != attributeSlots) {
                throw new MslTranslator.MslTranslationException(
                    "Vertex attributes read buffer slots 0x" + Integer.toHexString(attributeSlots) + " but only 0x" + Integer.toHexString(layoutSlots) + " are declared"
                );
            }

            for (int i = 0; i < this.attributeCount; i++) {
                int slot = MtlNative.VERTEX_BUFFER_INDEX_BASE - this.attributes[i * 4 + 3];
                int stride = strideOf(buffers, slot);
                int size = attribs.get(i).format().blockSize();
                if (this.attributes[i * 4 + 2] + size > stride) {
                    throw new MslTranslator.MslTranslationException(
                        "Vertex attribute " + this.attributes[i * 4] + " (offset " + this.attributes[i * 4 + 2] + ", " + size + " bytes) exceeds stride " + stride
                    );
                }
            }

            this.layoutCount = layoutCount;
            this.layouts = Arrays.copyOf(layouts, layoutCount * 4);
            this.vertexBufferSlotMask = layoutSlots;
            int required = 0;
            for (int slot = 0; slot < MtlNative.MAX_VERTEX_BUFFERS; slot++) {
                if ((layoutSlots & 1 << slot) != 0) {
                    required |= 1 << (MtlNative.VERTEX_BUFFER_INDEX_BASE - slot);
                }
            }

            // Round 12 (a): the cluster vertex function also reads vertex buffer slots 2 and 3 (quad list, cluster items): a
            // draw without them is skipped like one without its vertex buffer (CONTRACT 6.4).
            if (vertex.msl().contains("mcmtl_quads [[buffer(")) {
                required |= 1 << (MtlNative.VERTEX_BUFFER_INDEX_BASE - TerrainVertexMsl.CLUSTER_QUADS_SLOT);
                required |= 1 << (MtlNative.VERTEX_BUFFER_INDEX_BASE - TerrainVertexMsl.CLUSTER_ITEMS_SLOT);
            }

            this.requiredVertexBufferMask = required;

            // ---- per-stage Metal index masks (CONTRACT.md 6.4) ---------------------------------
            MslTranslator.StageResult[] stages = {vertex, fragment};
            for (int s = 0; s < 2; s++) {
                this.bufferMask[s] = stages[s].bufferMask();
                this.textureMask[s] = stages[s].textureMask();
                this.samplerMask[s] = stages[s].samplerMask();
                this.textureCubeMask[s] = stages[s].textureCubeMask();
                this.texelBufferMask[s] = stages[s].texelBufferMask();
            }

            this.bufferMask[0] |= required;
        }

        private static int strideOf(final List<BackendRenderPipeline.CreateInfo.VertexBuffer> buffers, final int slot) {
            for (BackendRenderPipeline.CreateInfo.VertexBuffer buffer : buffers) {
                if (buffer.bufferSlot() == slot) {
                    return buffer.stride();
                }
            }

            return 0;
        }

        /**
         * Fills a zeroed {@code mcmtl_pipeline_desc} at {@code address} (MtlNative.PIPELINE_DESC_SIZEOF bytes).
         * {@code label} is the address of a NUL-terminated UTF-8 string or 0.
         */
        public void writeDescriptor(final long address, final long vertexFunction, final long fragmentFunction, final long label) {
            MemoryUtil.memSet(address, 0, MtlNative.PIPELINE_DESC_SIZEOF);
            MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_STRUCT_SIZE, (int)MtlNative.PIPELINE_DESC_SIZEOF);
            MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_FLAGS, this.flags);
            MemoryUtil.memPutLong(address + MtlNative.PIPELINE_DESC_VERTEX_FUNCTION, vertexFunction);
            MemoryUtil.memPutLong(address + MtlNative.PIPELINE_DESC_FRAGMENT_FUNCTION, fragmentFunction);
            MemoryUtil.memPutLong(address + MtlNative.PIPELINE_DESC_LABEL, label);
            MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_PRIMITIVE_TYPE, this.primitiveType);
            MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_TOPOLOGY_CLASS, this.topologyClass);
            MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_EMULATION, this.emulation);
            MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_CULL_MODE, this.cullMode);
            MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_FILL_MODE, this.fillMode);
            MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_FRONT_FACING, MetalFormats.WINDING_CLOCKWISE);
            MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_DEPTH_COMPARE, this.depthCompare);
            MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_DEPTH_WRITE_ENABLED, this.depthWriteEnabled);
            MemoryUtil.memPutFloat(address + MtlNative.PIPELINE_DESC_DEPTH_BIAS_CONSTANT, this.depthBiasConstant);
            MemoryUtil.memPutFloat(address + MtlNative.PIPELINE_DESC_DEPTH_BIAS_SLOPE_SCALE, this.depthBiasSlopeScale);
            MemoryUtil.memPutFloat(address + MtlNative.PIPELINE_DESC_DEPTH_BIAS_CLAMP, 0.0F);
            MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_PUSH_CONSTANT_SIZE, this.pushConstantSize);
            MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_PUSH_CONSTANT_STAGES, this.pushConstantStages);
            MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_COLOR_TARGET_COUNT, this.colorTargetCount);
            for (int i = 0; i < this.colorTargetCount; i++) {
                long target = address + MtlNative.PIPELINE_DESC_COLOR_TARGETS + i * MtlNative.COLOR_TARGET_SIZEOF;
                int base = i * COLOR_TARGET_INTS;
                MemoryUtil.memPutInt(target + MtlNative.COLOR_TARGET_PIXEL_FORMAT, this.colorTargets[base]);
                MemoryUtil.memPutInt(target + MtlNative.COLOR_TARGET_WRITE_MASK, this.colorTargets[base + 1]);
                MemoryUtil.memPutInt(target + MtlNative.COLOR_TARGET_BLENDING_ENABLED, this.colorTargets[base + 2]);
                MemoryUtil.memPutInt(target + MtlNative.COLOR_TARGET_RGB_BLEND_OPERATION, this.colorTargets[base + 3]);
                MemoryUtil.memPutInt(target + MtlNative.COLOR_TARGET_ALPHA_BLEND_OPERATION, this.colorTargets[base + 4]);
                MemoryUtil.memPutInt(target + MtlNative.COLOR_TARGET_SOURCE_RGB_BLEND_FACTOR, this.colorTargets[base + 5]);
                MemoryUtil.memPutInt(target + MtlNative.COLOR_TARGET_DESTINATION_RGB_BLEND_FACTOR, this.colorTargets[base + 6]);
                MemoryUtil.memPutInt(target + MtlNative.COLOR_TARGET_SOURCE_ALPHA_BLEND_FACTOR, this.colorTargets[base + 7]);
                MemoryUtil.memPutInt(target + MtlNative.COLOR_TARGET_DESTINATION_ALPHA_BLEND_FACTOR, this.colorTargets[base + 8]);
            }

            MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_ATTRIBUTE_COUNT, this.attributeCount);
            for (int i = 0; i < this.attributeCount; i++) {
                long attribute = address + MtlNative.PIPELINE_DESC_ATTRIBUTES + i * MtlNative.VERTEX_ATTRIBUTE_SIZEOF;
                MemoryUtil.memPutInt(attribute + MtlNative.VERTEX_ATTRIBUTE_LOCATION, this.attributes[i * 4]);
                MemoryUtil.memPutInt(attribute + MtlNative.VERTEX_ATTRIBUTE_FORMAT, this.attributes[i * 4 + 1]);
                MemoryUtil.memPutInt(attribute + MtlNative.VERTEX_ATTRIBUTE_OFFSET, this.attributes[i * 4 + 2]);
                MemoryUtil.memPutInt(attribute + MtlNative.VERTEX_ATTRIBUTE_BUFFER_INDEX, this.attributes[i * 4 + 3]);
            }

            MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_LAYOUT_COUNT, this.layoutCount);
            for (int i = 0; i < this.layoutCount; i++) {
                long layout = address + MtlNative.PIPELINE_DESC_LAYOUTS + i * MtlNative.VERTEX_LAYOUT_SIZEOF;
                MemoryUtil.memPutInt(layout + MtlNative.VERTEX_LAYOUT_BUFFER_INDEX, this.layouts[i * 4]);
                MemoryUtil.memPutInt(layout + MtlNative.VERTEX_LAYOUT_STRIDE, this.layouts[i * 4 + 1]);
                MemoryUtil.memPutInt(layout + MtlNative.VERTEX_LAYOUT_STEP_FUNCTION, this.layouts[i * 4 + 2]);
                MemoryUtil.memPutInt(layout + MtlNative.VERTEX_LAYOUT_STEP_RATE, this.layouts[i * 4 + 3]);
            }

            for (int s = 0; s < 2; s++) {
                MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_BUFFER_MASK + 4L * s, this.bufferMask[s]);
                MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_TEXTURE_MASK + 4L * s, this.textureMask[s]);
                MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_SAMPLER_MASK + 4L * s, this.samplerMask[s]);
                MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_TEXTURE_CUBE_MASK + 4L * s, this.textureCubeMask[s]);
                MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_TEXEL_BUFFER_MASK + 4L * s, this.texelBufferMask[s]);
            }

            MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_REQUIRED_VERTEX_BUFFER_MASK, this.requiredVertexBufferMask);
            MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_PRECOMPILE_DEPTH_FORMAT_COUNT, this.precompileDepthFormats.length);
            for (int i = 0; i < this.precompileDepthFormats.length; i++) {
                MemoryUtil.memPutInt(address + MtlNative.PIPELINE_DESC_PRECOMPILE_DEPTH_FORMATS + 4L * i, this.precompileDepthFormats[i]);
            }
        }

        /** The Java pipeline object around a native handle (the binding-table arrays are copied: Prepared stays immutable). */
        MetalRenderPipeline toPipeline(final MetalDevice device, final long handle) {
            return this.toPipeline(device, handle, 0L);
        }

        /** {@code rgssHandle}: the UseRgss == 1 specialization of {@code handle}, or 0 (TerrainShaderVariants). */
        MetalRenderPipeline toPipeline(final MetalDevice device, final long handle, final long rgssHandle) {
            MetalRenderPipeline result = new MetalRenderPipeline(
                device,
                handle,
                rgssHandle,
                this.name,
                this.uniformName.clone(),
                this.bindings.kind().clone(),
                this.bindings.slot().clone(),
                this.uniformStages.clone(),
                this.texelPixelFormat.clone(),
                this.texelBytesPerTexel.clone(),
                this.pushConstantSize,
                this.pushConstantStages,
                this.vertexBufferSlotMask,
                this.topology
            );
            return result;
        }

        /** The pipeline object I4 would see, with handle 0 (tests: binding tables without a device). */
        public MetalRenderPipeline toPipelineForTesting(final MetalDevice device) {
            return this.toPipeline(device, 0L);
        }

        public int[] uniformStages() {
            return this.uniformStages;
        }

        public int pushConstantStages() {
            return this.pushConstantStages;
        }

        public int vertexBufferSlotMask() {
            return this.vertexBufferSlotMask;
        }

        public int requiredVertexBufferMask() {
            return this.requiredVertexBufferMask;
        }

        /** Per-stage masks: index 0 = vertex, 1 = fragment. */
        public int bufferMask(final int stage) {
            return this.bufferMask[stage];
        }

        public int textureMask(final int stage) {
            return this.textureMask[stage];
        }

        public int samplerMask(final int stage) {
            return this.samplerMask[stage];
        }

        public int textureCubeMask(final int stage) {
            return this.textureCubeMask[stage];
        }

        public int texelBufferMask(final int stage) {
            return this.texelBufferMask[stage];
        }
    }

    // =============================================================================================
    // ShaderCache
    // =============================================================================================

    /**
     * Per-device cache of shader functions (spec 2.2, 3.3, CONTRACT.md 7.1): key = SHA-256 of the generated
     * MSL source + stage + shader flags/language version (exact: identical MSL is an identical function, so
     * pipelines that share a module but differ only in bindings the module does not declare share one
     * library). SPIR-V -> MSL translation runs before the lookup (cheap next to newLibraryWithSource); it MAY
     * itself be memoized by (SHA-256(spv), stage, {@link MslTranslator#OPTIONS_VERSION}, the (uniform index,
     * kind, slot) tuples of the uniforms that module declares, push constants used). Value =
     * {@code CompletableFuture<Long>} of a +1 function handle owned by the cache (concurrent compiles of one
     * key are deduplicated). Thread-safe (compile workers, always inside
     * {@link MetalDevice#beginCompile()}/{@link MetalDevice#endCompile()}). Created by the {@link MetalDevice}
     * constructor; {@link #close()} (device shutdown, after every compile left) marks it closed and releases
     * every handle; a handle created after close() (only possible after the bounded compile wait timed out)
     * is released immediately instead of being inserted.
     *
     * <p>Failed compiles are not cached: the failing future is removed after it completed (its waiters see
     * the failure), so a later reload retries (MSL compile failures are deterministic, but XPC compiler
     * service hiccups are not).
     */
    public static final class ShaderCache implements AutoCloseable {
        private final MetalDevice device;
        private final boolean farSourcesAvailable;
        private final ConcurrentHashMap<Key, CompletableFuture<Long>> functions = new ConcurrentHashMap<>();
        /** Guards {@link #closed} against the completion of new entries (see {@link #publish}). */
        private final Object lock = new Object();
        private boolean closed;

        public ShaderCache(final MetalDevice device) {
            this.device = device;
            this.farSourcesAvailable = MetalFarSourceCapture.enable(device);
        }

        /** Exact source metadata available for worker-built ID PSOs; false means synchronous fallback. */
        public boolean farSourcesAvailable() { return this.farSourcesAvailable; }

        public MetalDevice device() {
            return this.device;
        }

        /** Cache key: SHA-256 of the UTF-8 MSL (4 longs), stage, shader flags, MTLLanguageVersion. */
        record Key(long h0, long h1, long h2, long h3, int stage, int flags, int languageVersion) {
        }

        /** Number of cached (or in-flight) functions. */
        public int size() {
            return this.functions.size();
        }

        /**
         * Returns the cache-owned function handle for {@code msl} (compiling it with mcmtl_shader_create on
         * the calling worker if nobody else did or is doing it). Blocks while another worker compiles the same
         * source. Throws {@link MslTranslator.MslTranslationException} with the compiler log on failure.
         */
        public long function(final int stage, final String msl, final String entryPoint, final @Nullable String label)
            throws MslTranslator.MslTranslationException {
            byte[] source = msl.getBytes(StandardCharsets.UTF_8);
            Key key = key(source, stage, SHADER_FLAGS, MetalFormats.LANGUAGE_VERSION_3_2);
            CompletableFuture<Long> future = this.functions.get(key);
            if (future == null) {
                CompletableFuture<Long> mine = new CompletableFuture<>();
                future = this.functions.putIfAbsent(key, mine);
                if (future == null) {
                    future = mine;
                    this.compileInto(mine, key, source, stage, entryPoint, label);
                }
            }

            try {
                return future.get();
            } catch (ExecutionException | CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new MslTranslator.MslTranslationException(cause.getMessage() == null ? cause.toString() : cause.getMessage(), cause);
            } catch (CancellationException e) {
                throw new MslTranslator.MslTranslationException("Shader compile cancelled", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MslTranslator.MslTranslationException("Interrupted while waiting for a shader compile", e);
            }
        }

        private void compileInto(
            final CompletableFuture<Long> future, final Key key, final byte[] source, final int stage, final String entryPoint, final @Nullable String label
        ) {
            try {
                long handle = this.createFunction(source, stage, entryPoint, label);
                this.publish(future, key, handle);
            } catch (Throwable t) {
                future.completeExceptionally(t);
                this.functions.remove(key, future);
                if (t instanceof VirtualMachineError error) {
                    throw error;
                }
            }
        }

        private long createFunction(final byte[] source, final int stage, final String entryPoint, final @Nullable String label)
            throws MslTranslator.MslTranslationException {
            synchronized (this.lock) {
                if (this.closed) {
                    throw new MslTranslator.MslTranslationException("Shader cache closed (device shutting down)");
                }
            }

            ByteBuffer native_ = MemoryUtil.memAlloc(source.length);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                native_.put(source).flip();
                long desc = stack.ncalloc(8, 1, (int)MtlNative.SHADER_DESC_SIZEOF);
                long out = stack.ncalloc(8, 1, 8);
                MemoryUtil.memPutInt(desc + MtlNative.SHADER_DESC_STRUCT_SIZE, (int)MtlNative.SHADER_DESC_SIZEOF);
                MemoryUtil.memPutInt(desc + MtlNative.SHADER_DESC_STAGE, stage);
                MemoryUtil.memPutInt(desc + MtlNative.SHADER_DESC_LANGUAGE_VERSION, MetalFormats.LANGUAGE_VERSION_3_2);
                MemoryUtil.memPutInt(desc + MtlNative.SHADER_DESC_FLAGS, SHADER_FLAGS);
                MemoryUtil.memPutLong(desc + MtlNative.SHADER_DESC_SOURCE, MemoryUtil.memAddress(native_));
                MemoryUtil.memPutLong(desc + MtlNative.SHADER_DESC_SOURCE_LENGTH, source.length);
                MemoryUtil.memPutLong(desc + MtlNative.SHADER_DESC_ENTRY_POINT, MemoryUtil.memAddress(stack.UTF8(entryPoint)));
                MemoryUtil.memPutLong(desc + MtlNative.SHADER_DESC_LABEL, label == null ? 0L : MemoryUtil.memAddress(stack.UTF8(label)));
                int status = MtlNative.shaderCreate(this.device.handle(), desc, out);
                if (status != MtlNative.OK) {
                    String log = MtlNative.lastError();
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Failed MSL ({} stage):\n{}", stage == MtlNative.STAGE_VERTEX ? "vertex" : "fragment", new String(source, StandardCharsets.UTF_8));
                    }

                    throw new MslTranslator.MslTranslationException("mcmtl_shader_create failed: " + MtlNative.statusName(status) + ": " + log);
                }

                long handle = MemoryUtil.memGetLong(out);
                if (handle == 0L) {
                    throw new MslTranslator.MslTranslationException("mcmtl_shader_create returned a null function");
                }

                return handle;
            } finally {
                MemoryUtil.memFree(native_);
            }
        }

        /** Completes {@code future} with {@code handle} unless the cache closed meanwhile (then releases it). */
        private void publish(final CompletableFuture<Long> future, final Key key, final long handle) throws MslTranslator.MslTranslationException {
            synchronized (this.lock) {
                if (!this.closed) {
                    future.complete(handle);
                    return;
                }
            }

            MtlNative.release(this.device.handle(), handle);
            throw new MslTranslator.MslTranslationException("Shader cache closed (device shutting down)");
        }

        /**
         * Device shutdown (after every compile worker left, CONTRACT.md 5.7): marks the cache closed and
         * releases every function handle with one mcmtl_release_batch. Idempotent. Later inserts are refused.
         */
        @Override
        public void close() {
            List<Long> handles = new ArrayList<>();
            synchronized (this.lock) {
                if (this.closed) {
                    return;
                }

                this.closed = true;
                for (CompletableFuture<Long> future : this.functions.values()) {
                    if (future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled()) {
                        Long handle = future.getNow(0L);
                        if (handle != null && handle != 0L) {
                            handles.add(handle);
                        }
                    }
                }

                this.functions.clear();
            }

            if (handles.isEmpty() || this.device.handle() == 0L) {
                return;
            }

            long array = MemoryUtil.nmemAlloc(8L * handles.size());
            try {
                for (int i = 0; i < handles.size(); i++) {
                    MemoryUtil.memPutLong(array + 8L * i, handles.get(i));
                }

                int status = MtlNative.releaseBatch(this.device.handle(), array, handles.size());
                if (status != MtlNative.OK) {
                    LOGGER.warn("Releasing {} Metal shader functions failed: {} {}", handles.size(), MtlNative.statusName(status), MtlNative.lastError());
                }
            } finally {
                MemoryUtil.nmemFree(array);
            }
        }

        static Key key(final byte[] source, final int stage, final int flags, final int languageVersion) {
            byte[] digest = sha256().digest(source);
            return new Key(longAt(digest, 0), longAt(digest, 8), longAt(digest, 16), longAt(digest, 24), stage, flags, languageVersion);
        }

        private static long longAt(final byte[] bytes, final int offset) {
            long value = 0L;
            for (int i = 0; i < 8; i++) {
                value = value << 8 | bytes[offset + i] & 0xFFL;
            }

            return value;
        }

        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }
    }
}
