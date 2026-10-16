package com.mojang.renderpearl.backend.metal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import org.jspecify.annotations.Nullable;

/** CPU input snapshots for exact LOD reuse; unavailable input always means redraw. */
public final class MetalLodReuseInputs {
    private MetalLodReuseInputs() {
    }

    /**
     * Returns an owned copy of the first {@code bytes} of {@code slice}, including pending staged CPU writes in
     * stream order, or null if it cannot be established without waiting. Call on the render thread after input
     * updates and before the draw being keyed; any later input write requires a new snapshot, even in the same frame.
     * No GPU readback, mapping, submit, throttle, or fence wait occurs here.
     *
     * <p>Conservatively limited to ordinary Shared buffers of at most 64 KiB and the audited CPU uniform usages;
     * see {@link MetalBuffer#snapshotCpuBytes}. Private/wrapped/transient/unknown GPU-produced buffers are unsupported.
     * No byte normalization is performed: callers select fields only after guarding built-in shader sources with
     * {@link TerrainShaderVariants#hasBuiltinTerrainSources()}. Built-in inputs are Projection[64], TerrainUniform[72]
     * (ModelView + TextureSize), Fog[48], and Globals[48]. Globals camera is [0,12) + [16,28), UseRgss is [44,48);
     * GameTime [28,32) is unused by built-in terrain. Keep projection/view/viewport and material state in the draw key.
     */
    public static byte @Nullable [] snapshot(final @Nullable GpuBufferSlice slice, final int bytes) {
        if (!RenderSystem.isOnRenderThread() || slice == null || bytes < 0
            || !(slice.buffer() instanceof MetalBuffer buffer)) {
            return null;
        }
        long offset = slice.offset(), length = slice.length(), size = buffer.size();
        // The record constructor allows malformed slices; use subtraction, not overflowing offset + length.
        if (offset < 0L || length < 0L || offset > size || length > size - offset || bytes > length) {
            return null;
        }
        return buffer.snapshotCpuBytes(offset, bytes);
    }
}
