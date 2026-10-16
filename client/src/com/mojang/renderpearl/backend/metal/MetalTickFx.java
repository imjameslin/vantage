package com.mojang.renderpearl.backend.metal;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Round 7 item 1 (docs/metal/CONTRACT.md 16.R7-1 / 16.R7-2, results/ROUND7.md): the per-tick texture updates as compute
 * dispatches on side command buffers (native/src/mcmetal_tickfx.m) instead of vanilla's render passes, so the first frame
 * after a client tick keeps its overlap with the previous frame and no longer loads and stores whole atlas mip levels:
 * <ul>
 *   <li>the lightmap ({@code mcmtl_ext_lightmap_compute}; patched {@code Lightmap}): vanilla samples it in the vertex
 *       stage, so its render pass, queued behind the previous frame's fragment work, held back the next world pass's
 *       vertex work;</li>
 *   <li>animated atlas sprites ({@code mcmtl_ext_atlas_animate}; patched {@code TextureAtlas}): one render pass per mip
 *       level of every atlas with a changed sprite, each loading and storing the whole level.</li>
 * </ul>
 * Optional extension symbols (not in the frozen mcmetal.h): when missing, {@link #available()} is false and the render
 * passes are used. Targets must be created through {@link #createWritable} (ShaderWrite).
 */
public final class MetalTickFx {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Bytes per {@code mcmtl_anim_op} (native/src/mcmetal_tickfx.m). */
    public static final int ANIM_OP_SIZE = 64;

    private MetalTickFx() {
    }

    /** True if the extension exports exist (the jar's own dylib always has them). */
    public static boolean available() {
        return Ext.LIGHTMAP_COMPUTE != null && Ext.LIGHTMAP_PREPARE != null && Ext.ATLAS_ANIMATE != null && Ext.ATLAS_PREPARE != null;
    }

    /** True if {@code texture} is a Metal texture of a live device and the extension is available. */
    public static boolean isMetal(final GpuTexture texture) {
        return texture instanceof MetalTexture metal && metal.device().handle() != 0L && available();
    }

    /**
     * If {@link #isMetal}: a new texture of the same usage, format, size and mip count that a kernel may also write
     * (ShaderWrite; measured free for sampling, results/ROUND7.md), {@code texture} closed. Otherwise {@code texture}. Call
     * before the texture holds content.
     */
    public static GpuTexture createWritable(final GpuTexture texture, final String label) {
        if (!isMetal(texture)) {
            return texture;
        }
        MetalTexture metal = (MetalTexture)texture;
        MetalTexture writable = MetalTexture.create(
            metal.device(),
            label,
            texture.usage() | MetalFormats.USAGE_EXT_SHADER_WRITE,
            texture.getFormat(),
            texture.getWidth(0),
            texture.getHeight(0),
            texture.getDepthOrLayers(),
            texture.getMipLevels()
        );
        texture.close();
        return writable;
    }

    /** True if {@code texture} came from {@link #createWritable} on Metal (RGBA8). */
    public static boolean isWritable(final GpuTexture texture) {
        return texture instanceof MetalTexture metal
            && !metal.isClosed()
            && (metal.usage() & MetalFormats.USAGE_EXT_SHADER_WRITE) != 0
            && metal.getFormat() == GpuFormat.RGBA8_UNORM
            && available();
    }

    /** Compiles the lightmap kernel for {@code texture}'s device (once); false if unavailable (use the render pass). */
    public static boolean prepareLightmap(final GpuTexture texture) {
        return prepare(texture, Ext.LIGHTMAP_PREPARE, "lightmap");
    }

    /** Compiles the atlas animation kernel for {@code texture}'s device (once); false if unavailable. */
    public static boolean prepareAtlas(final GpuTexture texture) {
        return prepare(texture, Ext.ATLAS_PREPARE, "atlas animation");
    }

    private static boolean prepare(final GpuTexture texture, final @Nullable MethodHandle mh, final String what) {
        if (!isMetal(texture) || mh == null) {
            return false;
        }
        try {
            int status = (int)mh.invokeExact(((MetalTexture)texture).device().handle());
            if (status != MtlNative.OK) {
                LOGGER.warn("Metal {} kernel unavailable ({}: {}); using the render passes", what, MtlNative.statusName(status), MtlNative.lastError());
                return false;
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Metal {} kernel: prepare threw; using the render passes", what, t);
            return false;
        }
    }

    /**
     * The timeline seq of the stream being recorded on {@code texture}'s device (it advances at every committing submit);
     * -1 if not Metal. Callers note it whenever they record stream work on a kernel target and run a kernel on that target
     * only once the seq has advanced past it: the side command buffer is committed at once, so it must never overtake
     * stream records that touch its target and are still uncommitted.
     */
    public static long recordingSeq(final GpuTexture texture) {
        return texture instanceof MetalTexture metal && metal.device().handle() != 0L ? metal.device().timeline().pendingSeq() : -1L;
    }

    /**
     * Stream copy of one mip level rectangle between two Metal textures through the backend encoder: the front end
     * requires USAGE_COPY_SRC on the source, which the animation frame textures (vanilla usage 5) lack; on Metal both
     * usages map to the same ShaderRead texture (MetalFormats.textureUsage), so the copy is the one the front end would
     * record.
     */
    public static void copyLevel(final GpuTexture source, final GpuTexture destination, final int level, final int destX, final int destY,
        final int width, final int height) {
        ((MetalTexture)destination).device().encoder().copyTextureToTexture(source, destination, level, destX, destY, 0, 0, width, height);
    }

    /** Native texture handle of a Metal texture (for {@code mcmtl_anim_op}); 0 if not Metal. */
    public static long handle(final GpuTexture texture) {
        return texture instanceof MetalTexture metal ? metal.handle() : 0L;
    }

    /**
     * Runs the lightmap kernel into {@code texture} ({@link #isWritable}, 16x16) with the std140 LightmapInfo bytes at
     * {@code infoAddress} ({@code infoBytes} >= 92), committed now on a side command buffer of the device's queue. The
     * caller guarantees that nothing recorded earlier in the current frame's stream reads or writes {@code texture}
     * (Lightmap: the ping-pong copy no frame samples). False if the call failed (the caller then records the render pass).
     */
    public static boolean lightmap(final GpuTexture texture, final long infoAddress, final int infoBytes) {
        MetalTexture metal = (MetalTexture)texture;
        // This side command buffer bypasses the decoder; failure may follow partial native encoding.
        metal.markContentWritten();
        try {
            int status = (int)Ext.LIGHTMAP_COMPUTE.invokeExact(metal.device().handle(), metal.handle(), infoAddress, infoBytes);
            if (status != MtlNative.OK) {
                LOGGER.warn("Metal lightmap compute failed: {}: {}", MtlNative.statusName(status), MtlNative.lastError());
                return false;
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Metal lightmap compute threw", t);
            return false;
        }
    }

    /**
     * Stage 4 (Tessera): {@link #lightmap} that also writes {@code sky} (same texels, alpha = 0.5 + 0.5 * sky level: the
     * terrain lightmap) in the same dispatch. Same ordering guarantee for both textures. False if unavailable or failed.
     */
    public static boolean lightmapSky(final GpuTexture texture, final GpuTexture sky, final long infoAddress, final int infoBytes) {
        if (Ext.LIGHTMAP_COMPUTE_SKY == null) {
            return false;
        }
        MetalTexture metal = (MetalTexture)texture;
        MetalTexture skyMetal = (MetalTexture)sky;
        metal.markContentWritten();
        skyMetal.markContentWritten();
        try {
            int status = (int)Ext.LIGHTMAP_COMPUTE_SKY.invokeExact(metal.device().handle(), metal.handle(), skyMetal.handle(), infoAddress, infoBytes);
            if (status != MtlNative.OK) {
                LOGGER.warn("Metal lightmap + sky compute failed: {}: {}", MtlNative.statusName(status), MtlNative.lastError());
                return false;
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Metal lightmap + sky compute threw", t);
            return false;
        }
    }

    /**
     * Runs {@code count} sprite ops ({@link #ANIM_OP_SIZE} bytes each at {@code opsAddress}, layout {@code mcmtl_anim_op})
     * into {@code atlas} ({@link #isWritable}), reading the packed animation frames {@code frames}, in one dispatch committed
     * now on a side command buffer. Same ordering guarantee as {@link #lightmap}
     * (TextureAtlas: the ping-pong spare). False if the call failed before anything was encoded (render passes instead).
     */
    public static boolean atlasAnimate(final GpuTexture atlas, final GpuTexture frames, final long opsAddress, final int count) {
        MetalTexture metal = (MetalTexture)atlas;
        // Invalidate only the destination parent, including when the native call fails or throws.
        metal.markContentWritten();
        try {
            int status = (int)Ext.ATLAS_ANIMATE.invokeExact(metal.device().handle(), metal.handle(), ((MetalTexture)frames).handle(), opsAddress, count);
            if (status != MtlNative.OK) {
                LOGGER.warn("Metal atlas animation compute failed: {}: {}", MtlNative.statusName(status), MtlNative.lastError());
                return false;
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Metal atlas animation compute threw", t);
            return false;
        }
    }

    /** Lazily bound optional extension exports (native/src/mcmetal_tickfx.m). */
    private static final class Ext {
        static final @Nullable MethodHandle LIGHTMAP_PREPARE = bind("mcmtl_ext_lightmap_prepare", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
        static final @Nullable MethodHandle LIGHTMAP_COMPUTE = bind(
            "mcmtl_ext_lightmap_compute", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT)
        );
        static final @Nullable MethodHandle LIGHTMAP_COMPUTE_SKY = bind(
            "mcmtl_ext_lightmap_compute_sky", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT)
        );
        static final @Nullable MethodHandle ATLAS_PREPARE = bind("mcmtl_ext_atlas_prepare", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
        static final @Nullable MethodHandle ATLAS_ANIMATE = bind(
            "mcmtl_ext_atlas_animate", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT)
        );

        private static @Nullable MethodHandle bind(final String symbol, final FunctionDescriptor descriptor) {
            try {
                return Linker.nativeLinker().downcallHandle(MemorySegment.ofAddress(MtlNative.Loader.address(symbol)), descriptor);
            } catch (Throwable t) {
                LOGGER.warn("Metal tick kernels: {} not available ({}); the render passes are used", symbol, t.toString());
                return null;
            }
        }
    }
}
