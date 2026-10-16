package com.mojang.renderpearl.backend.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * {@link GpuTexture} backed by a Private {@code MTLTexture} (spec 2.2, 2.10). OWNER: I3 (java-device).
 *
 * <p>Lifetime (Vulkan parity, spec 2.2): the texture counts itself plus one reference per open
 * {@link MetalTextureView}; the native handle is released (deferred) only when the texture and all its views
 * are closed. {@code close()} may be called repeatedly and before its views are closed.
 *
 * <p>Cross-module API: I4 uses {@link #handle()} (attachments, clears, copies, PRESENT), {@link #pixelFormat()}
 * and {@link #isCube()}; I3's {@link MetalTextureView} uses {@link #retainView()} / {@link #releaseView()}.
 *
 * <p>{@code getWidth(mip)} / {@code getHeight(mip)} are {@link BaseGpuTexture}'s {@code width >> mip}, not
 * clamped to 1 (spec 2.10): the frontend validates with them; native copy extents clamp themselves.
 */
public final class MetalTexture extends BaseGpuTexture {
    /** Apple-family 2D/cube size limit (DeviceLimits.maxTextureSize). */
    public static final int MAX_SIZE = 16384;

    private final MetalDevice device;
    private final long handle;
    private final int pixelFormat;
    /** Self + open views (render thread). 0 = native release scheduled. */
    private int references = 1;
    private boolean closed;
    /** Render-thread-only revision shared by all mip levels, layers and views of this texture. */
    private long contentRevision;

    /**
     * Wraps a created native texture.
     *
     * @param handle      +1 id&lt;MTLTexture&gt; (mcmtl_object_result.handle)
     * @param pixelFormat MTLPixelFormat used at creation
     * @param label       never null ("" when no label, spec 2.2)
     */
    public MetalTexture(
        final MetalDevice device,
        final long handle,
        final int pixelFormat,
        final @GpuTexture.Usage int usage,
        final String label,
        final GpuFormat format,
        final int width,
        final int height,
        final int depthOrLayers,
        final int mipLevels
    ) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.device = device;
        this.handle = handle;
        this.pixelFormat = pixelFormat;
    }

    /**
     * Spec 2.2: IAE if {@code MetalFormats.pixelFormat(format) == 0} or width/height outside 1..16384;
     * mcmtl_texture_create with textureType/textureUsage/resourceOptions from {@link MetalFormats}
     * (Cube iff USAGE_CUBEMAP_COMPATIBLE, Private storage); OUT_OF_MEMORY -> GpuOutOfMemoryException;
     * label {@code null -> ""}. Zero-fills textures without RENDER_ATTACHMENT via
     * {@link MetalCommandEncoder#recordTextureInit(MetalTexture)} (spec 2.2, U15: atlas gaps are never cleared
     * by vanilla and GL drivers zero-fill in practice; render targets are always cleared by the game).
     *
     * <p>A {@link MetalDevice#forTesting} device (handle 0) gets a synthetic handle (no native call).
     */
    public static MetalTexture create(
        final MetalDevice device,
        final @Nullable String label,
        final @GpuTexture.Usage int usage,
        final GpuFormat format,
        final int width,
        final int height,
        final int depthOrLayers,
        final int mipLevels
    ) {
        int pixelFormat = MetalFormats.pixelFormat(format);
        if (pixelFormat == MetalFormats.PIXEL_FORMAT_INVALID) {
            throw new IllegalArgumentException("Texture format " + format + " has no Metal pixel format");
        }
        if (width < 1 || height < 1 || width > MAX_SIZE || height > MAX_SIZE) {
            throw new IllegalArgumentException("Texture size " + width + "x" + height + " is outside 1.." + MAX_SIZE);
        }
        if (mipLevels < 1) {
            throw new IllegalArgumentException("mipLevels must be at least 1, was " + mipLevels);
        }
        boolean cube = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
        if (cube ? depthOrLayers != 6 : depthOrLayers != 1) {
            throw new IllegalArgumentException("Unsupported depthOrLayers " + depthOrLayers + " for " + (cube ? "a cube" : "a 2D") + " texture");
        }
        String safeLabel = label == null ? "" : label;
        long handle;
        if (device.handle() == 0L) {
            handle = MetalBuffer.Headless.nextHandle();
        } else {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long desc = stack.ncalloc(8, 1, (int)MtlNative.TEXTURE_DESC_SIZEOF);
                long out = stack.ncalloc(8, 1, (int)MtlNative.OBJECT_RESULT_SIZEOF);
                MemoryUtil.memPutInt(desc + MtlNative.TEXTURE_DESC_STRUCT_SIZE, (int)MtlNative.TEXTURE_DESC_SIZEOF);
                MemoryUtil.memPutInt(desc + MtlNative.TEXTURE_DESC_PIXEL_FORMAT, pixelFormat);
                MemoryUtil.memPutInt(desc + MtlNative.TEXTURE_DESC_TEXTURE_TYPE, MetalFormats.textureType(usage));
                MemoryUtil.memPutInt(desc + MtlNative.TEXTURE_DESC_WIDTH, width);
                MemoryUtil.memPutInt(desc + MtlNative.TEXTURE_DESC_HEIGHT, height);
                MemoryUtil.memPutInt(desc + MtlNative.TEXTURE_DESC_DEPTH, 1);
                MemoryUtil.memPutInt(desc + MtlNative.TEXTURE_DESC_MIP_LEVELS, mipLevels);
                MemoryUtil.memPutInt(desc + MtlNative.TEXTURE_DESC_ARRAY_LENGTH, 1);
                MemoryUtil.memPutInt(desc + MtlNative.TEXTURE_DESC_TEXTURE_USAGE, MetalFormats.textureUsage(usage));
                MemoryUtil.memPutInt(desc + MtlNative.TEXTURE_DESC_RESOURCE_OPTIONS, MetalFormats.textureResourceOptions());
                if (label != null && !label.isEmpty()) {
                    MemoryUtil.memPutAddress(desc + MtlNative.TEXTURE_DESC_LABEL, MetalBuffer.utf8Label(stack, label));
                }
                MemoryUtil.memPutInt(out + MtlNative.OBJECT_RESULT_STRUCT_SIZE, (int)MtlNative.OBJECT_RESULT_SIZEOF);
                MtlNative.check(
                    MtlNative.textureCreate(device.handle(), desc, out),
                    "mcmtl_texture_create(" + format + " " + width + "x" + height + ", " + mipLevels + " mips, usage " + usage + ")"
                );
                handle = MemoryUtil.memGetLong(out + MtlNative.OBJECT_RESULT_HANDLE);
            }
            if (handle == 0L) {
                throw new IllegalStateException("mcmtl_texture_create returned no handle");
            }
        }
        MetalTexture texture = new MetalTexture(device, handle, pixelFormat, usage, safeLabel, format, width, height, depthOrLayers, mipLevels);
        if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) == 0) {
            try {
                device.encoder().recordTextureInit(texture);
            } catch (RuntimeException | Error e) {
                texture.close();
                throw e;
            }
        }
        return texture;
    }

    public MetalDevice device() {
        return this.device;
    }

    /** Parent texture handle: attachments (with level = view.baseMipLevel()), clears, copies, PRESENT, full-range sampling. */
    public long handle() {
        return this.handle;
    }

    /**
     * Conservative content version for render-thread cache keys, scoped to this texture's identity.
     * Advances when a possible write is recorded, not when the GPU completes it. Reads do not advance it.
     */
    public long contentRevision() {
        return this.contentRevision;
    }

    /**
     * Invalidate before recording a possible write (including native work outside the command stream).
     * Never roll back on failure: part of the write may already have been encoded. Render thread only.
     */
    void markContentWritten() {
        this.contentRevision++;
    }

    /** MTLPixelFormat of the texture. */
    public int pixelFormat() {
        return this.pixelFormat;
    }

    /** True if created with USAGE_CUBEMAP_COMPATIBLE (MTLTextureTypeCube, 6 faces). */
    public boolean isCube() {
        return (this.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
    }

    /** MTLTextureType of the texture (and of its views). */
    public int textureType() {
        return MetalFormats.textureType(this.usage());
    }

    /** I3 (MetalTextureView constructor): one more open view. */
    public void retainView() {
        if (this.references <= 0) {
            throw new IllegalStateException("Cannot create a view of released texture '" + this.getLabel() + "'");
        }
        this.references++;
    }

    /** I3 (MetalTextureView.close): one view fewer; releases the native texture (deferred) when closed and unreferenced. */
    public void releaseView() {
        this.release();
    }

    private void release() {
        this.references--;
        if (this.references < 0) {
            this.references = 0;
            throw new IllegalStateException("Too many views removed from texture '" + this.getLabel() + "'");
        }
        if (this.references == 0) {
            this.device.timeline().deferRelease(this.handle);
        }
    }

    /** Open references (self + views); 0 once the native release was scheduled (tests). */
    int references() {
        return this.references;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    /** Idempotent; drops the texture's own reference (see class doc). */
    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            this.release();
        }
    }
}
