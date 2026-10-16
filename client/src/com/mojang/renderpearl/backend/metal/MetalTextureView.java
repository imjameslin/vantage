package com.mojang.renderpearl.backend.metal;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTextureView;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * {@link com.mojang.renderpearl.api.textures.GpuTextureView} (spec 2.2, 2.10). OWNER: I3 (java-device).
 *
 * <p>Extends {@link BaseGpuTextureView} (its {@code getWidth(m)} adds {@code baseMipLevel}, which the frontend
 * relies on). Full mip range: no native object, sampling binds the parent texture. Partial range: one
 * {@code mcmtl_texture_view_create} at creation (cube textures keep the cube type with 6 slices), released
 * deferred on close. Every view holds a reference on its parent ({@link MetalTexture#retainView()}, taken by
 * the constructor and dropped by {@link #close()}).
 *
 * <p>Cross-module API: I4 binds {@link #samplingHandle()} in SET_TEXTURE records; attachments and PRESENT
 * use {@code texture().handle()} with level {@link #baseMipLevel()}.
 */
public final class MetalTextureView extends BaseGpuTextureView {
    private final long viewHandle;
    private boolean closed;

    /**
     * Takes one reference on {@code texture} ({@link MetalTexture#retainView()}).
     *
     * @param viewHandle +1 view handle for a partial mip range, 0 for the full range
     */
    public MetalTextureView(final MetalTexture texture, final int baseMipLevel, final int mipLevels, final long viewHandle) {
        super(texture, baseMipLevel, mipLevels);
        texture.retainView();
        this.viewHandle = viewHandle;
    }

    /**
     * Spec 2.2: IAE if mipLevels &lt; 1; full range -> no native call; partial -> mcmtl_texture_view_create
     * (pixelFormat/textureType of the parent, levels [base, base+count), slices 0..(cube ? 6 : 1));
     * retains the parent.
     */
    public static MetalTextureView create(final MetalDevice device, final MetalTexture texture, final int baseMipLevel, final int mipLevels) {
        if (mipLevels < 1) {
            throw new IllegalArgumentException("mipLevels must be at least 1, was " + mipLevels);
        }
        if (baseMipLevel < 0 || baseMipLevel + mipLevels > texture.getMipLevels()) {
            throw new IllegalArgumentException(
                mipLevels + " mip levels starting from " + baseMipLevel + " would be out of range for texture with only " + texture.getMipLevels() + " mip levels"
            );
        }
        if (texture.isClosed()) {
            throw new IllegalArgumentException("Can't create texture view with closed texture");
        }
        if (baseMipLevel == 0 && mipLevels == texture.getMipLevels()) {
            return new MetalTextureView(texture, 0, mipLevels, 0L);
        }
        long handle;
        if (device.handle() == 0L) {
            handle = MetalBuffer.Headless.nextHandle();
        } else {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long desc = stack.ncalloc(8, 1, (int)MtlNative.TEXTURE_VIEW_DESC_SIZEOF);
                long out = stack.ncalloc(8, 1, (int)MtlNative.OBJECT_RESULT_SIZEOF);
                MemoryUtil.memPutInt(desc + MtlNative.TEXTURE_VIEW_DESC_STRUCT_SIZE, (int)MtlNative.TEXTURE_VIEW_DESC_SIZEOF);
                MemoryUtil.memPutInt(desc + MtlNative.TEXTURE_VIEW_DESC_PIXEL_FORMAT, texture.pixelFormat());
                MemoryUtil.memPutInt(desc + MtlNative.TEXTURE_VIEW_DESC_TEXTURE_TYPE, texture.textureType());
                MemoryUtil.memPutInt(desc + MtlNative.TEXTURE_VIEW_DESC_BASE_LEVEL, baseMipLevel);
                MemoryUtil.memPutInt(desc + MtlNative.TEXTURE_VIEW_DESC_LEVEL_COUNT, mipLevels);
                MemoryUtil.memPutInt(desc + MtlNative.TEXTURE_VIEW_DESC_BASE_SLICE, 0);
                MemoryUtil.memPutInt(desc + MtlNative.TEXTURE_VIEW_DESC_SLICE_COUNT, texture.isCube() ? 6 : 1);
                MemoryUtil.memPutLong(desc + MtlNative.TEXTURE_VIEW_DESC_TEXTURE, texture.handle());
                MemoryUtil.memPutInt(out + MtlNative.OBJECT_RESULT_STRUCT_SIZE, (int)MtlNative.OBJECT_RESULT_SIZEOF);
                MtlNative.check(
                    MtlNative.textureViewCreate(device.handle(), desc, out),
                    "mcmtl_texture_view_create(levels " + baseMipLevel + "+" + mipLevels + " of '" + texture.getLabel() + "')"
                );
                handle = MemoryUtil.memGetLong(out + MtlNative.OBJECT_RESULT_HANDLE);
            }
            if (handle == 0L) {
                throw new IllegalStateException("mcmtl_texture_view_create returned no handle");
            }
        }
        return new MetalTextureView(texture, baseMipLevel, mipLevels, handle);
    }

    @Override
    public MetalTexture texture() {
        return (MetalTexture)super.texture();
    }

    /** Native view handle, 0 for a full-range view. */
    public long viewHandle() {
        return this.viewHandle;
    }

    /** Texture bound for sampling: the view's own handle for partial ranges, else the parent's handle. */
    public long samplingHandle() {
        return this.viewHandle != 0L ? this.viewHandle : this.texture().handle();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    /** Idempotent: defers the release of {@link #viewHandle()} (if any) and calls {@code texture().releaseView()}. */
    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            MetalTexture texture = this.texture();
            texture.device().timeline().deferRelease(this.viewHandle);
            texture.releaseView();
        }
    }

    /** {@link GpuTexture#USAGE_CUBEMAP_COMPATIBLE} views keep the cube type (6 slices). */
    boolean isCube() {
        return (this.texture().usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
    }
}
