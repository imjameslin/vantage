package com.mojang.renderpearl.backend.metal;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import java.util.OptionalLong;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Timestamp {@link GpuQueryPool} (spec 2.13). OWNER: I3 (java-device).
 *
 * <p>Values are nanoseconds (the backend reports {@code timestampPeriod = 1.0}). {@code getValues} is
 * non-blocking: a sample is available iff it was written ({@link #markWritten}) by work whose seq completed;
 * available samples are fetched with {@code mcmtl_query_pool_resolve} (MtlNative.TIMESTAMP_UNAVAILABLE ->
 * empty). Unwritten samples read {@link OptionalLong#empty()}. Index bounds: {@code index + count <= size}
 * (IndexOutOfBoundsException otherwise). {@code close()} defers the native release. Tracy creates 1024 at
 * device construction and never closes it; normal gameplay writes no timestamps (keep it zero-cost).
 *
 * <p>Cross-module API: I4 records TIMESTAMP with {@link #handle()} after bounds-checking
 * {@code index < size()} and calls {@link #markWritten(int, long)} with {@code pendingSeq}.
 *
 * <p>Resolution: one {@code mcmtl_query_pool_resolve} per contiguous run of available samples (Tracy reads
 * up to 1024 entries per frame and stops at the first unavailable one); no downcall when nothing is
 * available. A headless ({@link MetalDevice#forTesting}) pool never has values.
 */
public final class MetalQueryPool implements GpuQueryPool {
    /** Resolve runs of up to this many samples use the MemoryStack; longer ones a temporary malloc. */
    private static final int STACK_RESOLVE_SAMPLES = 1024;

    private final MetalDevice device;
    private final long handle;
    private final int size;
    /** Timeline value of the work that wrote each sample, 0 = never written (render thread). */
    private final long[] writtenSeq;
    private boolean closed;

    public MetalQueryPool(final MetalDevice device, final long handle, final int size) {
        this.device = device;
        this.handle = handle;
        this.size = size;
        this.writtenSeq = new long[Math.max(0, size)];
    }

    /** mcmtl_query_pool_create(device, size). */
    public static MetalQueryPool create(final MetalDevice device, final int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Query pool size must be positive, was " + size);
        }
        if (device.handle() == 0L) {
            return new MetalQueryPool(device, MetalBuffer.Headless.nextHandle(), size);
        }
        long handle;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long outPool = stack.ncalloc(8, 1, 8);
            MtlNative.check(MtlNative.queryPoolCreate(device.handle(), size, outPool), "mcmtl_query_pool_create(" + size + ")");
            handle = MemoryUtil.memGetLong(outPool);
        }
        if (handle == 0L) {
            throw new IllegalStateException("mcmtl_query_pool_create returned no handle");
        }
        return new MetalQueryPool(device, handle, size);
    }

    /** Native MCMTLQueryPool handle written into TIMESTAMP records. */
    public long handle() {
        return this.handle;
    }

    public MetalDevice device() {
        return this.device;
    }

    /** I4: sample {@code index} is written by work tagged {@code seq} (invalidates any older value). Render thread. */
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
        if (index < 0 || count < 0 || (long)index + count > this.size) {
            throw new IndexOutOfBoundsException(
                "getValues would read out-of-bounds for an array of " + count + " starting at " + index + ", when total size is " + this.size
            );
        }
        OptionalLong[] result = new OptionalLong[count];
        long completed = this.device.timeline().completedSeq();
        boolean resolvable = !this.closed && this.device.handle() != 0L && !this.device.timeline().isClosed();
        int i = 0;
        while (i < count) {
            if (!resolvable || !this.isAvailable(index + i, completed)) {
                result[i++] = OptionalLong.empty();
                continue;
            }
            int runStart = i;
            while (i < count && this.isAvailable(index + i, completed)) {
                i++;
            }
            this.resolve(index + runStart, i - runStart, result, runStart);
        }
        return result;
    }

    private boolean isAvailable(final int sample, final long completedSeq) {
        long written = this.writtenSeq[sample];
        return written != 0L && written <= completedSeq;
    }

    /** Resolves samples [first, first + count) into result[offset..]. */
    private void resolve(final int first, final int count, final OptionalLong[] result, final int offset) {
        long bytes = (long)count * Long.BYTES;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean onStack = count <= STACK_RESOLVE_SAMPLES;
            long values = onStack ? stack.nmalloc(8, (int)bytes) : MemoryUtil.nmemAlloc(bytes);
            if (values == 0L) {
                throw new OutOfMemoryError("query resolve buffer of " + bytes + " bytes");
            }
            try {
                MtlNative.check(
                    MtlNative.queryPoolResolve(this.device.handle(), this.handle, first, count, values),
                    "mcmtl_query_pool_resolve(" + first + ", " + count + ")"
                );
                for (int i = 0; i < count; i++) {
                    long value = MemoryUtil.memGetLong(values + (long)i * Long.BYTES);
                    result[offset + i] = value == MtlNative.TIMESTAMP_UNAVAILABLE ? OptionalLong.empty() : OptionalLong.of(value);
                }
            } finally {
                if (!onStack) {
                    MemoryUtil.nmemFree(values);
                }
            }
        }
    }

    /** Idempotent; defers the native release. */
    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            this.device.timeline().deferRelease(this.handle);
        }
    }

    /** True after {@link #close()}. */
    public boolean isClosed() {
        return this.closed;
    }
}
