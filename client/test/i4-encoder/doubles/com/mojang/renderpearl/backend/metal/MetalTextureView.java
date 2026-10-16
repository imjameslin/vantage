package com.mojang.renderpearl.backend.metal;

import com.mojang.renderpearl.backend.common.BaseGpuTextureView;

/** I4 TEST DOUBLE of I3's MetalTextureView (same public API; see MetalTimeline double). */
public final class MetalTextureView extends BaseGpuTextureView {
    private final long viewHandle;
    private boolean closed;

    public MetalTextureView(final MetalTexture texture, final int baseMipLevel, final int mipLevels, final long viewHandle) {
        super(texture, baseMipLevel, mipLevels);
        this.viewHandle = viewHandle;
    }

    public static MetalTextureView create(final MetalDevice device, final MetalTexture texture, final int baseMipLevel, final int mipLevels) {
        throw new UnsupportedOperationException("test double");
    }

    @Override
    public MetalTexture texture() {
        return (MetalTexture)super.texture();
    }

    public long viewHandle() {
        return this.viewHandle;
    }

    public long samplingHandle() {
        return this.viewHandle != 0L ? this.viewHandle : this.texture().handle();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        this.closed = true;
    }
}
