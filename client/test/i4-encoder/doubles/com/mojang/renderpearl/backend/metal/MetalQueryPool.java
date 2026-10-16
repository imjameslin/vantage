package com.mojang.renderpearl.backend.metal;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import java.util.OptionalLong;

/** I4 TEST DOUBLE of I3's MetalQueryPool (same public API; see MetalTimeline double). */
public final class MetalQueryPool implements GpuQueryPool {
    private final MetalDevice device;
    private final long handle;
    private final int size;
    /** writtenSeq per index (test inspection), 0 = never. */
    final long[] writtenSeq;

    public MetalQueryPool(final MetalDevice device, final long handle, final int size) {
        this.device = device;
        this.handle = handle;
        this.size = size;
        this.writtenSeq = new long[size];
    }

    public static MetalQueryPool create(final MetalDevice device, final int size) {
        throw new UnsupportedOperationException("test double");
    }

    public long handle() {
        return this.handle;
    }

    public MetalDevice device() {
        return this.device;
    }

    public void markWritten(final int index, final long seq) {
        this.writtenSeq[index] = seq;
    }

    @Override
    public int size() {
        return this.size;
    }

    @Override
    public OptionalLong getValue(final int index) {
        return this.getValues(index, 1)[0];
    }

    @Override
    public OptionalLong[] getValues(final int index, final int count) {
        OptionalLong[] values = new OptionalLong[count];
        java.util.Arrays.fill(values, OptionalLong.empty());
        return values;
    }

    @Override
    public void close() {
    }
}
