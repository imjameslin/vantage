package com.mojang.renderpearl.backend.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;
import org.jspecify.annotations.Nullable;

/** I4 TEST DOUBLE of I3's MetalTexture (same public API; see MetalTimeline double). */
public final class MetalTexture extends BaseGpuTexture {
    private final MetalDevice device;
    private final long handle;
    private final int pixelFormat;
    private boolean closed;
    private int views;

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
        throw new UnsupportedOperationException("test double");
    }

    public MetalDevice device() {
        return this.device;
    }

    public long handle() {
        return this.handle;
    }

    public int pixelFormat() {
        return this.pixelFormat;
    }

    public boolean isCube() {
        return (this.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
    }

    public void retainView() {
        this.views++;
    }

    public void releaseView() {
        this.views--;
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
