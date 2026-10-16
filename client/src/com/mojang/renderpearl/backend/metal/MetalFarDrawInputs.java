package com.mojang.renderpearl.backend.metal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Exact built-in far draw inputs, captured on the render thread and owned until close.
 * All arrays are private owned copies; worker accessors never consult RenderSystem, buffers or pipelines.
 * Transfer this owner through a safe publication mechanism. Use and close must not overlap; close is
 * idempotent and may run on either thread. Shader handles are borrowed from this owner until close.
 * The coordinator owns device lifetime, geometry/material leases, camera and target validation.
 */
public final class MetalFarDrawInputs implements AutoCloseable {
    private static final int PROJECTION = 1 << 0, GLOBALS = 1 << 1, FOG = 1 << 2, TERRAIN = 1 << 5;
    private final MetalDevice device;
    private final byte @Nullable [] projection, globals, fog, terrain;
    private final int[] layerCodes, uniformPresenceMasks;
    private final long[] shadersByLayer;
    private boolean closed;

    private MetalFarDrawInputs(MetalDevice device, byte @Nullable [] projection, byte @Nullable [] globals,
                               byte @Nullable [] fog, byte @Nullable [] terrain, int[] layerCodes,
                               int[] uniformPresenceMasks, long[] shadersByLayer) {
        this.device = device;
        this.projection = projection;
        this.globals = globals;
        this.fog = fog;
        this.terrain = terrain;
        this.layerCodes = layerCodes;
        this.uniformPresenceMasks = uniformPresenceMasks;
        this.shadersByLayer = shadersByLayer;
    }

    /**
     * Capture after uniform updates, at the original draw's selection point, outside render passes.
     * Pipelines and layer codes must be aligned with geometry inputs in their original order and remain
     * stable during this call. Only codes 0/1/8/9 are supported. Each pipeline is requested as supplied:
     * request pins its currently selected native handle and validates the exact built-in sources natively.
     * It may queue worker compilation, but this call never compiles, waits, submits or reads back the GPU.
     *
     * <p>Returns null for pending/unavailable programs or bytes, unsupported bindings, or an unrepresentable
     * capture (including different programs for the same layer). Optimized-out bindings are ignored.
     * Required bytes are copied exactly, including Globals fields ignored by reuse keys. Only the unused
     * last eight bytes of TerrainUniform are zero padded, from the game's 72 bytes to native's 80 bytes.
     * A later uniform write or pipeline selection change requires a new capture, even within one frame.
     */
    public static @Nullable MetalFarDrawInputs capture(List<MetalRenderPipeline> actualPipelines,
                                                       int[] layerCodes, @Nullable GpuBufferSlice terrain) {
        RenderSystem.assertOnRenderThread();
        if (!MetalFarPrograms.REQUESTED || !TerrainShaderVariants.hasBuiltinTerrainSources()
            || actualPipelines == null || layerCodes == null || actualPipelines.isEmpty()
            || actualPipelines.size() > 4096 || actualPipelines.size() != layerCodes.length) return null;

        int[] codes = layerCodes.clone(), masks = new int[codes.length];
        MetalDevice device = null;
        int needed = 0;
        for (int i = 0; i < codes.length; i++) {
            int code = codes[i];
            MetalRenderPipeline pipeline = actualPipelines.get(i);
            if ((code != 0 && code != 1 && code != 8 && code != 9) || pipeline == null
                || pipeline.isClosed() || pipeline.device() == null) return null;
            if (device == null) device = pipeline.device();
            if (pipeline.device() != device || device.isClosed() || device.handle() == 0 || pipeline.handle() == 0) return null;
            int mask = uniformMask(pipeline);
            if (mask < 0) return null;
            masks[i] = mask;
            needed |= mask;
        }

        byte[] projection = (needed & PROJECTION) == 0 ? null
            : snapshot(device, RenderSystem.getProjectionMatrixBuffer(), 64);
        GpuBuffer globalBuffer = (needed & GLOBALS) == 0 ? null : RenderSystem.getGlobalSettingsUniform();
        byte[] globals = globalBuffer == null || globalBuffer.isClosed() ? null : snapshot(device, globalBuffer.slice(), 48);
        byte[] fog = (needed & FOG) == 0 ? null : snapshot(device, RenderSystem.getShaderFog(), 48);
        byte[] transform = (needed & TERRAIN) == 0 ? null : snapshot(device, terrain, 72);
        if (((needed & PROJECTION) != 0 && projection == null) || ((needed & GLOBALS) != 0 && globals == null)
            || ((needed & FOG) != 0 && fog == null) || ((needed & TERRAIN) != 0 && transform == null)) return null;
        byte[] paddedTerrain = transform == null ? null : Arrays.copyOf(transform, 80);

        long[] shaders = new long[10];
        boolean transferred = false;
        try {
            for (int i = 0; i < codes.length; i++) {
                int code = codes[i];
                long borrowed = MetalFarPrograms.request(actualPipelines.get(i), code);
                if (borrowed == 0) return null;
                if (shaders[code] == 0) {
                    shaders[code] = MetalFarPrograms.retain(borrowed);
                    if (shaders[code] == 0) return null;
                } else if (shaders[code] != borrowed) {
                    // The native engine has one shader per layer: never silently replace another selected draw.
                    return null;
                }
            }
            MetalFarDrawInputs captured = new MetalFarDrawInputs(device, projection, globals, fog, paddedTerrain,
                codes, masks, shaders);
            transferred = true;
            return captured;
        } finally {
            if (!transferred) release(shaders);
        }
    }

    private static byte @Nullable [] snapshot(MetalDevice device, @Nullable GpuBufferSlice slice, int bytes) {
        if (slice == null || !(slice.buffer() instanceof MetalBuffer buffer) || buffer.device() != device) return null;
        return MetalLodReuseInputs.snapshot(slice, bytes);
    }

    /** Native engine slots, not Java binding indices. Minus one means unsupported active metadata. */
    private static int uniformMask(MetalRenderPipeline pipeline) {
        int count = pipeline.uniformCount;
        if (pipeline.pushConstantSize != 0 || pipeline.pushConstantStages != 0 || count < 0 || count > 32
            || pipeline.uniformName.length != count || pipeline.uniformKind.length != count
            || pipeline.uniformSlot.length != count || pipeline.uniformStages.length != count) return -1;
        int uniforms = 0, textures = 0;
        for (int i = 0; i < count; i++) {
            int stages = pipeline.uniformStages[i];
            if (stages == 0) continue; // setUniform also drops unused layout entries, e.g. Lighting.
            if ((stages & ~(MtlNative.STAGE_VERTEX | MtlNative.STAGE_FRAGMENT)) != 0) return -1;
            String name = pipeline.uniformName[i];
            int slot = pipeline.uniformSlot[i];
            if (pipeline.uniformKind[i] == MetalRenderPipeline.KIND_UNIFORM_BUFFER) {
                int expected = "Projection".equals(name) ? 0 : "Globals".equals(name) ? 1
                    : "Fog".equals(name) ? 2 : "TerrainUniform".equals(name) ? 5 : -1;
                if (expected < 0 || slot != expected || (uniforms & (1 << slot)) != 0) return -1;
                uniforms |= 1 << slot;
            } else if (pipeline.uniformKind[i] == MetalRenderPipeline.KIND_SAMPLED_TEXTURE) {
                if (!("Sampler0".equals(name) && slot == 0 && stages == MtlNative.STAGE_FRAGMENT)
                    && !("Sampler2".equals(name) && slot == 2 && stages == MtlNative.STAGE_VERTEX)) return -1;
                if ((textures & (1 << slot)) != 0) return -1;
                textures |= 1 << slot;
            } else return -1;
        }
        return uniforms;
    }

    /** Device identity only; the coordinator must keep the device alive through worker/native calls. */
    public MetalDevice device() { return this.device; }
    public int inputCount() { return this.layerCodes.length; }
    public int[] layerCodes() { return this.layerCodes.clone(); }
    /** One mask per input; native slot bits 0/1/2/5. Clear bits must marshal as {NULL, 0}. */
    public int[] uniformMasks() { return this.uniformPresenceMasks.clone(); }
    /** Deep copy in native order: Projection[64], Globals[48], Fog[48], TerrainUniform[80].
     * A row is null when no input uses that UBO. Terrain is 72 exact bytes followed by 8 zero bytes.
     * Per-input absence is determined by uniformMasks(), even when another input uses the same UBO.
     */
    public byte[][] uniformBytes() {
        return new byte[][]{copy(this.projection), copy(this.globals), copy(this.fog), copy(this.terrain)};
    }
    /** Copied table of ten borrowed native ID shader handles. Only 0/1/8/9 may be nonzero.
     * Keep this owner open throughout native submission; copying this array does not retain handles.
     */
    public long[] programs() {
        if (this.closed) throw new IllegalStateException("Far draw inputs closed");
        return this.shadersByLayer.clone();
    }

    /** Render-thread capture of the otherwise-unused paired layout, needed only
     * by optional visibility-ID shading. Never changes the actual draw list. */
    public boolean includePairedProgram(MetalRenderPipeline pipeline,int code) {
        RenderSystem.assertOnRenderThread();
        if(this.closed || pipeline==null || pipeline.isClosed() || pipeline.device()!=device
            || (code!=0 && code!=1 && code!=8 && code!=9)) return false;
        long borrowed=MetalFarPrograms.request(pipeline,code);
        if(borrowed==0) return false;
        if(shadersByLayer[code]!=0) return shadersByLayer[code]==borrowed;
        shadersByLayer[code]=MetalFarPrograms.retain(borrowed);
        return shadersByLayer[code]!=0;
    }

    private static byte @Nullable [] copy(byte @Nullable [] bytes) { return bytes == null ? null : bytes.clone(); }

    @Override public void close() {
        if (this.closed) return;
        this.closed = true;
        release(this.shadersByLayer);
    }

    private static void release(long[] shaders) {
        Throwable failure = null;
        for (long shader : shaders) {
            if (shader == 0) continue;
            try { MetalFarPrograms.release(shader); }
            catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException error) throw error;
    }
}
