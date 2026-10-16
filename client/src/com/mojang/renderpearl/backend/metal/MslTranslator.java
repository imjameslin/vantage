package com.mojang.renderpearl.backend.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcEntryPoint;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/**
 * SPIR-V -> MSL translation with LWJGL spvc (spec 3.1-3.3). OWNER: I5 (java-pipeline+integration).
 * Used only by {@link MetalRenderPipeline#compile}; runs on compile worker threads (one spvc context per
 * call, never shared, destroyed before returning). The API below is I5-internal and may be reshaped by I5.
 *
 * <p>Options (spec 3.3): MSL_VERSION 30200, MSL_PLATFORM macOS, MSL_TEXTURE_BUFFER_NATIVE, no argument
 * buffers, no decoration bindings, FLIP_VERTEX_Y (vertex stage), no FIXUP_DEPTH_CONVENTION,
 * MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS, FORCE_ZERO_INITIALIZED_VARIABLES, point-size and frag-depth builtins.
 * Resource bindings via spvc_compiler_msl_add_resource_binding for every uniform index and stage (push
 * constants: desc set -1, binding 0 -> buffer 14). After compiling: entry point main0, assert no auxiliary
 * buffers, collect per-stage used masks with spvc_compiler_msl_is_resource_used.
 *
 * <p>Besides translating, {@link #translate} validates the module against the binding model before
 * anything reaches Metal: only uniform buffers and (combined) sampled images, all in descriptor set 0 with
 * binding = uniform index, kinds matching {@code CreateInfo.uniforms()}, 2D/cube/buffer images only (no
 * arrays, multisampling or depth-compare images: no RenderPearl texture or sampler could feed them), and
 * texel-buffer element types matching the declared texel format. A violation is a
 * {@link MslTranslationException}, i.e. a clean pipeline compile failure instead of a Metal validation
 * error at draw time.
 */
public final class MslTranslator {
    /** Bump whenever an option or the binding scheme changes (part of every cache key). */
    public static final int OPTIONS_VERSION = 1;

    /** Fixed UBO slots (spec 3.2); every other UBO is dense from {@link #FIRST_DENSE_BUFFER_SLOT}. */
    public static final List<String> FIXED_BUFFER_NAMES = List.of("Projection", "Globals", "Fog", "Lighting", "DynamicTransforms");
    public static final int FIRST_DENSE_BUFFER_SLOT = 5;
    /** Fixed texture/sampler slots (spec 3.2); every other texture or texel buffer is dense from {@link #FIRST_DENSE_TEXTURE_SLOT}. */
    public static final List<String> FIXED_TEXTURE_NAMES = List.of("Sampler0", "Sampler1", "Sampler2");
    public static final int FIRST_DENSE_TEXTURE_SLOT = 3;

    /** Highest uniform count a pipeline may declare (binding masks are 32-bit in the encoder). */
    public static final int MAX_UNIFORMS = 32;
    /** spvc execution models (SpvExecutionModelVertex / SpvExecutionModelFragment). */
    public static final int EXECUTION_MODEL_VERTEX = 0;
    public static final int EXECUTION_MODEL_FRAGMENT = 4;

    // SPIR-V / spvc constants used below (values from spirv.h and spirv_cross_c.h).
    private static final int SPIRV_MAGIC = 0x07230203;
    private static final int DECORATION_BINDING = 33;
    private static final int DECORATION_DESCRIPTOR_SET = 34;
    private static final int DIM_2D = 1;
    private static final int DIM_CUBE = 3;
    private static final int DIM_BUFFER = 5;
    private static final int BASETYPE_INT32 = 7;
    private static final int BASETYPE_UINT32 = 8;
    private static final int BASETYPE_FP32 = 13;
    /** Resource types the binding model cannot serve (spvc_resource_type). */
    private static final int[] UNSUPPORTED_RESOURCE_TYPES = {
        Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER,
        Spvc.SPVC_RESOURCE_TYPE_SUBPASS_INPUT,
        Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE,
        Spvc.SPVC_RESOURCE_TYPE_ATOMIC_COUNTER,
        Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE,
        Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS,
        Spvc.SPVC_RESOURCE_TYPE_ACCELERATION_STRUCTURE
    };

    private MslTranslator() {
    }

    /**
     * Metal slots per uniform index: {@code kind[i]} is a MetalRenderPipeline.KIND_* value, {@code slot[i]} the
     * [[buffer(n)]] (UBO) or [[texture(n)]]/[[sampler(n)]] index.
     */
    public record BindingTable(int[] kind, int[] slot) {
        public int size() {
            return this.kind.length;
        }
    }

    /**
     * One translated stage. Masks: bit n = Metal index n read by this stage; {@code usedUniformMask} bit i =
     * uniform index i read by this stage. {@code bufferMask} holds UBO slots and, when
     * {@code usesPushConstants}, {@link MtlNative#PUSH_CONSTANT_BUFFER_INDEX}; vertex-buffer indices are
     * added by the pipeline (they come from the vertex descriptor, not from the shader resources).
     */
    public record StageResult(
        String msl,
        String entryPoint,
        long usedUniformMask,
        int bufferMask,
        int textureMask,
        int samplerMask,
        int textureCubeMask,
        int texelBufferMask,
        boolean usesPushConstants
    ) {
        /** The same stage with other MSL source (specialization rewrites that keep every resource binding). */
        public StageResult withMsl(final String newMsl) {
            return new StageResult(newMsl, this.entryPoint, this.usedUniformMask, this.bufferMask, this.textureMask, this.samplerMask, this.textureCubeMask, this.texelBufferMask, this.usesPushConstants);
        }

        /**
         * The same stage, also binding UBO uniform index {@code uniform} at Metal buffer index {@code slot} (a variant of
         * this stage reads it; binding an unused buffer is harmless for the others). CONTRACT.md 16.R11.
         */
        public StageResult withResources(final long uniforms, final int buffers, final int textures, final int samplers) {
            return new StageResult(this.msl, this.entryPoint, this.usedUniformMask | uniforms, this.bufferMask | buffers, this.textureMask | textures,
                this.samplerMask | samplers, this.textureCubeMask, this.texelBufferMask, this.usesPushConstants);
        }

        /** Lit merge (TerrainVertexMsl.litFragment): also reads uniform {@code uniform}, buffer 30, texture / sampler 2. */
        public StageResult withUniform(final int uniform, final int slot) {
            return new StageResult(this.msl, this.entryPoint, this.usedUniformMask | 1L << uniform, this.bufferMask | 1 << slot, this.textureMask, this.samplerMask,
                this.textureCubeMask, this.texelBufferMask, this.usesPushConstants);
        }
    }

    /** Translation failure (logged by the caller, which then returns Pending.NULL). */
    public static final class MslTranslationException extends Exception {
        public MslTranslationException(final String message) {
            super(message);
        }

        public MslTranslationException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    /** MetalRenderPipeline.KIND_* of a uniform type. */
    public static int kindOf(final BindGroupLayout.UniformDescription uniform) {
        return switch (uniform.type()) {
            case UNIFORM_BUFFER -> MetalRenderPipeline.KIND_UNIFORM_BUFFER;
            case COMBINED_IMAGE_SAMPLER -> MetalRenderPipeline.KIND_SAMPLED_TEXTURE;
            case TEXEL_BUFFER -> MetalRenderPipeline.KIND_TEXEL_BUFFER;
        };
    }

    /**
     * Spec 3.2 hybrid assignment (CONTRACT.md 6.1): fixed names first, then dense per kind in uniform-index
     * order. The fallback is decided per kind on the ASSIGNED indices, not on counts: if any hybrid UBO slot
     * is &gt; 13 ({@link MtlNative#MAX_UNIFORM_BUFFER_INDEX}; 14 is the push-constant buffer), use fully dense
     * UBO slots 0..13 and fail if that still exceeds 13; if any hybrid texture/texel-buffer slot is &gt; 15,
     * use fully dense 0..15 and fail if that still exceeds 15.
     *
     * <p>Texture fixed names apply to the whole texture index space (combined image samplers and texel
     * buffers share it); a name is fixed only for the matching kind space (a UBO called "Sampler0" is dense).
     */
    public static BindingTable assignBindings(final List<BindGroupLayout.UniformDescription> uniforms) throws MslTranslationException {
        int count = uniforms.size();
        if (count > MAX_UNIFORMS) {
            throw new MslTranslationException("Pipeline declares " + count + " uniforms; at most " + MAX_UNIFORMS + " are supported");
        }

        int[] kind = new int[count];
        int[] slot = new int[count];
        int nextBuffer = FIRST_DENSE_BUFFER_SLOT;
        int nextTexture = FIRST_DENSE_TEXTURE_SLOT;
        int maxBuffer = -1;
        int maxTexture = -1;
        for (int i = 0; i < count; i++) {
            BindGroupLayout.UniformDescription uniform = uniforms.get(i);
            kind[i] = kindOf(uniform);
            if (kind[i] == MetalRenderPipeline.KIND_UNIFORM_BUFFER) {
                int fixed = FIXED_BUFFER_NAMES.indexOf(uniform.name());
                slot[i] = fixed >= 0 ? fixed : nextBuffer++;
                maxBuffer = Math.max(maxBuffer, slot[i]);
            } else {
                int fixed = FIXED_TEXTURE_NAMES.indexOf(uniform.name());
                slot[i] = fixed >= 0 ? fixed : nextTexture++;
                maxTexture = Math.max(maxTexture, slot[i]);
            }
        }

        if (maxBuffer > MtlNative.MAX_UNIFORM_BUFFER_INDEX) {
            assignDense(kind, slot, true, MtlNative.MAX_UNIFORM_BUFFER_INDEX, "uniform buffers");
        }

        if (maxTexture > MtlNative.MAX_TEXTURE_BINDINGS - 1) {
            assignDense(kind, slot, false, MtlNative.MAX_TEXTURE_BINDINGS - 1, "textures and texel buffers");
        }

        // Fixed names are unique per pipeline (BindGroupLayout.ensureCompatible), so slots never collide;
        // re-check anyway: two uniforms on one Metal index would silently alias.
        int bufferSlots = 0;
        int textureSlots = 0;
        for (int i = 0; i < count; i++) {
            int bit = 1 << slot[i];
            if (kind[i] == MetalRenderPipeline.KIND_UNIFORM_BUFFER) {
                if ((bufferSlots & bit) != 0) {
                    throw new MslTranslationException("Uniform " + uniforms.get(i).name() + " collides on [[buffer(" + slot[i] + ")]]");
                }

                bufferSlots |= bit;
            } else {
                if ((textureSlots & bit) != 0) {
                    throw new MslTranslationException("Uniform " + uniforms.get(i).name() + " collides on [[texture(" + slot[i] + ")]]");
                }

                textureSlots |= bit;
            }
        }

        return new BindingTable(kind, slot);
    }

    private static void assignDense(final int[] kind, final int[] slot, final boolean buffers, final int maxIndex, final String what) throws MslTranslationException {
        int next = 0;
        for (int i = 0; i < kind.length; i++) {
            if ((kind[i] == MetalRenderPipeline.KIND_UNIFORM_BUFFER) == buffers) {
                slot[i] = next++;
            }
        }

        if (next - 1 > maxIndex) {
            throw new MslTranslationException("Pipeline uses " + next + " " + what + "; Metal slots 0.." + maxIndex + " are available");
        }
    }

    /** spvc execution model of a stage. */
    public static int executionModel(final ShaderType type) {
        return switch (type) {
            case VERTEX -> EXECUTION_MODEL_VERTEX;
            case FRAGMENT -> EXECUTION_MODEL_FRAGMENT;
        };
    }

    /**
     * Translates one stage of a pipeline. Uses only {@code module.spv()} (patched: set 0, binding = uniform
     * index) and {@code module.type()}; must not keep the module (the frontend frees it after finishCompile).
     */
    public static StageResult translate(
        final SpvModule module, final List<BindGroupLayout.UniformDescription> uniforms, final BindingTable bindings, final int pushConstantsSize
    ) throws MslTranslationException {
        return translate(module.spv(), module.type(), uniforms, bindings, pushConstantsSize);
    }

    /** {@link #translate(SpvModule, List, BindingTable, int)} on raw SPIR-V words (native order). */
    public static StageResult translate(
        final ByteBuffer spv,
        final ShaderType type,
        final List<BindGroupLayout.UniformDescription> uniforms,
        final BindingTable bindings,
        final int pushConstantsSize
    ) throws MslTranslationException {
        if (bindings.size() != uniforms.size()) {
            throw new MslTranslationException("Binding table has " + bindings.size() + " entries for " + uniforms.size() + " uniforms");
        }

        IntBuffer words = spirvWords(spv);
        int model = executionModel(type);
        boolean vertex = type == ShaderType.VERTEX;
        long context = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            PointerBuffer count = stack.mallocPointer(1);
            check(0L, Spvc.spvc_context_create(pointer), "spvc_context_create");
            context = pointer.get(0);
            check(context, Spvc.spvc_context_parse_spirv(context, words, words.remaining(), pointer), "parse SPIR-V");
            long ir = pointer.get(0);
            check(context, Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pointer), "create MSL compiler");
            long compiler = pointer.get(0);

            String entryName = findEntryPoint(compiler, model, type, pointer, count);
            check(context, Spvc.spvc_compiler_set_entry_point(compiler, entryName, model), "set entry point");

            check(context, Spvc.spvc_compiler_create_compiler_options(compiler, pointer), "create compiler options");
            long options = pointer.get(0);
            setUint(context, options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MetalFormats.SPVC_MSL_VERSION_3_2);
            setUint(context, options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS);
            setBool(context, options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
            setBool(context, options, Spvc.SPVC_COMPILER_OPTION_MSL_ARGUMENT_BUFFERS, false);
            setBool(context, options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING, false);
            setBool(context, options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, vertex);
            setBool(context, options, Spvc.SPVC_COMPILER_OPTION_FIXUP_DEPTH_CONVENTION, false);
            setBool(context, options, Spvc.SPVC_COMPILER_OPTION_MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS, true);
            setBool(context, options, Spvc.SPVC_COMPILER_OPTION_FORCE_ZERO_INITIALIZED_VARIABLES, true);
            setBool(context, options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN, true);
            setBool(context, options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_FRAG_DEPTH_BUILTIN, true);
            setBool(context, options, Spvc.SPVC_COMPILER_OPTION_MSL_INVARIANT_FP_MATH, false);
            check(context, Spvc.spvc_compiler_install_compiler_options(compiler, options), "install compiler options");

            check(context, Spvc.spvc_compiler_create_shader_resources(compiler, pointer), "create shader resources");
            long resources = pointer.get(0);
            for (int unsupported : UNSUPPORTED_RESOURCE_TYPES) {
                SpvcReflectedResource.Buffer list = resourceList(context, resources, unsupported, pointer, count);
                if (list.remaining() > 0) {
                    throw new MslTranslationException(
                        type.getName() + " shader declares " + list.get(0).nameString() + ", a resource kind the Metal backend does not support (spvc resource type "
                            + unsupported + ")"
                    );
                }
            }

            validateUniformBuffers(compiler, resourceList(context, resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, pointer, count), uniforms, bindings, type);
            int cubeUniforms = validateSampledImages(compiler, resourceList(context, resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, pointer, count), uniforms, bindings, type);
            SpvcReflectedResource.Buffer pushConstantBlocks = resourceList(context, resources, Spvc.SPVC_RESOURCE_TYPE_PUSH_CONSTANT, pointer, count);
            boolean hasPushConstantBlock = pushConstantBlocks.remaining() > 0;
            if (hasPushConstantBlock && pushConstantsSize <= 0) {
                throw new MslTranslationException(type.getName() + " shader declares a push-constant block but the pipeline has pushConstantsSize 0");
            }

            SpvcMslResourceBinding binding = SpvcMslResourceBinding.calloc(stack);
            for (int i = 0; i < uniforms.size(); i++) {
                Spvc.spvc_msl_resource_binding_init(binding);
                binding.stage(model).desc_set(0).binding(i);
                int metalSlot = bindings.slot()[i];
                switch (bindings.kind()[i]) {
                    case MetalRenderPipeline.KIND_UNIFORM_BUFFER -> binding.msl_buffer(metalSlot).msl_texture(0).msl_sampler(0);
                    case MetalRenderPipeline.KIND_SAMPLED_TEXTURE -> binding.msl_buffer(0).msl_texture(metalSlot).msl_sampler(metalSlot);
                    default -> binding.msl_buffer(0).msl_texture(metalSlot).msl_sampler(0);
                }

                check(context, Spvc.spvc_compiler_msl_add_resource_binding(compiler, binding), "add resource binding " + i);
            }

            if (hasPushConstantBlock) {
                Spvc.spvc_msl_resource_binding_init(binding);
                binding.stage(model)
                    .desc_set(Spvc.SPVC_MSL_PUSH_CONSTANT_DESC_SET)
                    .binding(Spvc.SPVC_MSL_PUSH_CONSTANT_BINDING)
                    .msl_buffer(MtlNative.PUSH_CONSTANT_BUFFER_INDEX)
                    .msl_texture(0)
                    .msl_sampler(0);
                check(context, Spvc.spvc_compiler_msl_add_resource_binding(compiler, binding), "add push-constant binding");
            }

            check(context, Spvc.spvc_compiler_compile(compiler, pointer), "compile MSL");
            String msl = MemoryUtil.memUTF8(pointer.get(0));

            if (Spvc.spvc_compiler_msl_needs_swizzle_buffer(compiler)) {
                throw new MslTranslationException(type.getName() + " MSL needs a swizzle buffer (unsupported)");
            }

            if (Spvc.spvc_compiler_msl_needs_buffer_size_buffer(compiler)) {
                throw new MslTranslationException(type.getName() + " MSL needs a buffer-size buffer (unsupported)");
            }

            if (Spvc.spvc_compiler_msl_needs_output_buffer(compiler)) {
                throw new MslTranslationException(type.getName() + " MSL needs an output buffer (unsupported)");
            }

            if (Spvc.spvc_compiler_msl_is_rasterization_disabled(compiler)) {
                throw new MslTranslationException(type.getName() + " MSL disables rasterization (unsupported)");
            }

            String entryPoint = Spvc.spvc_compiler_get_cleansed_entry_point_name(compiler, entryName, model);
            if (entryPoint == null || entryPoint.isEmpty()) {
                throw new MslTranslationException("No MSL entry point for " + entryName);
            }

            long usedUniforms = 0L;
            int bufferMask = 0;
            int textureMask = 0;
            int samplerMask = 0;
            int textureCubeMask = 0;
            int texelBufferMask = 0;
            for (int i = 0; i < uniforms.size(); i++) {
                if (!Spvc.spvc_compiler_msl_is_resource_used(compiler, model, 0, i)) {
                    continue;
                }

                usedUniforms |= 1L << i;
                int bit = 1 << bindings.slot()[i];
                switch (bindings.kind()[i]) {
                    case MetalRenderPipeline.KIND_UNIFORM_BUFFER -> bufferMask |= bit;
                    case MetalRenderPipeline.KIND_SAMPLED_TEXTURE -> {
                        textureMask |= bit;
                        samplerMask |= bit;
                        if ((cubeUniforms & (1 << i)) != 0) {
                            textureCubeMask |= bit;
                        }
                    }
                    default -> {
                        textureMask |= bit;
                        texelBufferMask |= bit;
                    }
                }
            }

            boolean usesPushConstants = hasPushConstantBlock
                && Spvc.spvc_compiler_msl_is_resource_used(compiler, model, Spvc.SPVC_MSL_PUSH_CONSTANT_DESC_SET, Spvc.SPVC_MSL_PUSH_CONSTANT_BINDING);
            if (usesPushConstants) {
                bufferMask |= 1 << MtlNative.PUSH_CONSTANT_BUFFER_INDEX;
            }

            return new StageResult(msl, entryPoint, usedUniforms, bufferMask, textureMask, samplerMask, textureCubeMask, texelBufferMask, usesPushConstants);
        } finally {
            if (context != 0L) {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    /** A direct, native-order view of the SPIR-V words (copies only when the caller passed a heap buffer). */
    private static IntBuffer spirvWords(final ByteBuffer spv) throws MslTranslationException {
        if (spv == null || spv.remaining() < 20 || (spv.remaining() & 3) != 0) {
            throw new MslTranslationException("Malformed SPIR-V module (" + (spv == null ? "null" : spv.remaining() + " bytes") + ")");
        }

        ByteBuffer bytes = spv.slice().order(ByteOrder.nativeOrder());
        if (!bytes.isDirect()) {
            ByteBuffer direct = ByteBuffer.allocateDirect(bytes.remaining()).order(ByteOrder.nativeOrder());
            direct.put(bytes).flip();
            bytes = direct;
        }

        IntBuffer words = bytes.asIntBuffer();
        if (words.get(0) != SPIRV_MAGIC) {
            throw new MslTranslationException("Not a SPIR-V module (magic 0x" + Integer.toHexString(words.get(0)) + ")");
        }

        return words;
    }

    private static String findEntryPoint(final long compiler, final int model, final ShaderType type, final PointerBuffer pointer, final PointerBuffer count)
        throws MslTranslationException {
        if (Spvc.spvc_compiler_get_entry_points(compiler, pointer, count) != Spvc.SPVC_SUCCESS) {
            throw new MslTranslationException("Couldn't list SPIR-V entry points");
        }

        SpvcEntryPoint.Buffer entryPoints = SpvcEntryPoint.create(pointer.get(0), (int)count.get(0));
        String found = null;
        for (int i = 0; i < entryPoints.remaining(); i++) {
            SpvcEntryPoint entryPoint = entryPoints.get(i);
            if (entryPoint.execution_model() == model) {
                if (found != null) {
                    throw new MslTranslationException("SPIR-V module has several " + type.getName() + " entry points");
                }

                found = entryPoint.nameString();
            }
        }

        if (found == null) {
            throw new MslTranslationException("SPIR-V module has no " + type.getName() + " entry point");
        }

        return found;
    }

    private static SpvcReflectedResource.Buffer resourceList(
        final long context, final long resources, final int resourceType, final PointerBuffer pointer, final PointerBuffer count
    ) throws MslTranslationException {
        check(context, Spvc.spvc_resources_get_resource_list_for_type(resources, resourceType, pointer, count), "list resources of type " + resourceType);
        return SpvcReflectedResource.create(pointer.get(0), (int)count.get(0));
    }

    /** Checks set 0 / binding range; returns the uniform index. */
    private static int uniformIndexOf(
        final long compiler, final SpvcReflectedResource resource, final List<BindGroupLayout.UniformDescription> uniforms, final ShaderType type
    ) throws MslTranslationException {
        int set = Spvc.spvc_compiler_get_decoration(compiler, resource.id(), DECORATION_DESCRIPTOR_SET);
        int index = Spvc.spvc_compiler_get_decoration(compiler, resource.id(), DECORATION_BINDING);
        if (set != 0 || index < 0 || index >= uniforms.size()) {
            throw new MslTranslationException(
                type.getName() + " shader resource " + resource.nameString() + " has set " + set + " binding " + index + ", outside the pipeline's "
                    + uniforms.size() + " uniforms"
            );
        }

        return index;
    }

    private static void validateUniformBuffers(
        final long compiler,
        final SpvcReflectedResource.Buffer list,
        final List<BindGroupLayout.UniformDescription> uniforms,
        final BindingTable bindings,
        final ShaderType type
    ) throws MslTranslationException {
        for (int r = 0; r < list.remaining(); r++) {
            SpvcReflectedResource resource = list.get(r);
            int index = uniformIndexOf(compiler, resource, uniforms, type);
            if (Spvc.spvc_type_get_num_array_dimensions(Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id())) != 0) {
                throw new MslTranslationException(type.getName() + " shader declares an array of uniform blocks (" + resource.nameString() + "), not supported");
            }

            if (bindings.kind()[index] != MetalRenderPipeline.KIND_UNIFORM_BUFFER) {
                throw new MslTranslationException(
                    type.getName() + " shader declares uniform block " + resource.nameString() + " at binding " + index + " but the pipeline declares "
                        + uniforms.get(index).type()
                );
            }
        }
    }

    /** Validates sampled images; returns the uniform-index bits of cube textures. */
    private static int validateSampledImages(
        final long compiler,
        final SpvcReflectedResource.Buffer list,
        final List<BindGroupLayout.UniformDescription> uniforms,
        final BindingTable bindings,
        final ShaderType type
    ) throws MslTranslationException {
        int cube = 0;
        for (int r = 0; r < list.remaining(); r++) {
            SpvcReflectedResource resource = list.get(r);
            int index = uniformIndexOf(compiler, resource, uniforms, type);
            String name = resource.nameString();
            long imageType = Spvc.spvc_compiler_get_type_handle(compiler, resource.base_type_id());
            if (imageType == 0L) {
                throw new MslTranslationException("No type information for " + name);
            }

            if (Spvc.spvc_type_get_num_array_dimensions(Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id())) != 0) {
                throw new MslTranslationException(type.getName() + " shader declares an array of samplers (" + name + "), not supported");
            }

            if (Spvc.spvc_type_get_image_arrayed(imageType) || Spvc.spvc_type_get_image_multisampled(imageType) || Spvc.spvc_type_get_image_is_depth(imageType)) {
                throw new MslTranslationException(type.getName() + " shader samples " + name + " as an arrayed, multisampled or shadow image, not supported");
            }

            int dim = Spvc.spvc_type_get_image_dimension(imageType);
            int kind = bindings.kind()[index];
            switch (dim) {
                case DIM_2D, DIM_CUBE -> {
                    if (kind != MetalRenderPipeline.KIND_SAMPLED_TEXTURE) {
                        throw new MslTranslationException(type.getName() + " shader declares sampler " + name + " but the pipeline declares " + uniforms.get(index).type());
                    }

                    if (dim == DIM_CUBE) {
                        cube |= 1 << index;
                    }
                }
                case DIM_BUFFER -> {
                    if (kind != MetalRenderPipeline.KIND_TEXEL_BUFFER) {
                        throw new MslTranslationException(type.getName() + " shader declares texel buffer " + name + " but the pipeline declares " + uniforms.get(index).type());
                    }

                    checkTexelElementType(compiler, imageType, uniforms.get(index), name, type);
                }
                default -> throw new MslTranslationException(type.getName() + " shader samples " + name + " with SpvDim " + dim + ", not supported");
            }
        }

        return cube;
    }

    /** texture_buffer<int|uint|float> must match the integer-ness of the declared texel format (Metal validates it). */
    private static void checkTexelElementType(
        final long compiler, final long imageType, final BindGroupLayout.UniformDescription uniform, final String name, final ShaderType type
    ) throws MslTranslationException {
        GpuFormat format = uniform.gpuFormat();
        if (format == null || MetalFormats.pixelFormat(format) == MetalFormats.PIXEL_FORMAT_INVALID || !format.hasColorAspect()) {
            throw new MslTranslationException("Texel buffer " + name + " has no usable texel format (" + format + ")");
        }

        int expected = switch (format.componentType()) {
            case SINT_8, SINT_16, SINT_32 -> BASETYPE_INT32;
            case UINT_8, UINT_16, UINT_32 -> BASETYPE_UINT32;
            default -> BASETYPE_FP32;
        };
        long sampledType = Spvc.spvc_compiler_get_type_handle(compiler, Spvc.spvc_type_get_image_sampled_type(imageType));
        int actual = sampledType == 0L ? -1 : Spvc.spvc_type_get_basetype(sampledType);
        if (actual != expected) {
            throw new MslTranslationException(
                type.getName() + " shader reads texel buffer " + name + " with element base type " + actual + " but its format is " + format
            );
        }
    }

    private static void setBool(final long context, final long options, final int option, final boolean value) throws MslTranslationException {
        check(context, Spvc.spvc_compiler_options_set_bool(options, option, value), "set option " + option);
    }

    private static void setUint(final long context, final long options, final int option, final int value) throws MslTranslationException {
        check(context, Spvc.spvc_compiler_options_set_uint(options, option, value), "set option " + option);
    }

    private static void check(final long context, final int result, final String what) throws MslTranslationException {
        if (result != Spvc.SPVC_SUCCESS) {
            String detail = context == 0L ? null : Spvc.spvc_context_get_last_error_string(context);
            throw new MslTranslationException("spvc: " + what + " failed (" + errorName(result) + ")" + (detail == null || detail.isEmpty() ? "" : ": " + detail));
        }
    }

    private static String errorName(final int result) {
        return switch (result) {
            case Spvc.SPVC_ERROR_INVALID_SPIRV -> "SPVC_ERROR_INVALID_SPIRV";
            case Spvc.SPVC_ERROR_UNSUPPORTED_SPIRV -> "SPVC_ERROR_UNSUPPORTED_SPIRV";
            case Spvc.SPVC_ERROR_OUT_OF_MEMORY -> "SPVC_ERROR_OUT_OF_MEMORY";
            case Spvc.SPVC_ERROR_INVALID_ARGUMENT -> "SPVC_ERROR_INVALID_ARGUMENT";
            default -> Integer.toString(result);
        };
    }
}
