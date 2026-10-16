package com.mojang.renderpearl.backend.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

/**
 * I4 TEST DOUBLE of I3's MetalTransientMemory (same public API; see the MetalTimeline double). Always used by
 * tools/test-i4-encoder.sh because the real class creates native MTLBuffers. Off-heap blocks with fake,
 * unique handles; every allocation is a {@link TransientBuffer} whose backing starts at the allocation's
 * block offset ({@code backingOffset() != 0}) and whose returned slice starts {@link #SLICE_SKEW} bytes into
 * it, so a record that forgets either offset is caught.
 */
public final class MetalTransientMemory implements TransientMemory {
    public static final long BLOCK_SIZE = 4L * 1024L * 1024L;
    public static final long CPU_BLOCK_SIZE = 512L * 1024L;
    public static final long MIN_GPU_ALIGNMENT = 16L;
    /** Bytes between the TransientBuffer's backing start and the slice handed out. */
    static final long SLICE_SKEW = 16L;
    static final long HANDLE_BASE = 0x7B10_C000_0000L;

    private final MetalDevice device;
    final List<Long> blockAddresses = new ArrayList<>();
    private long used = BLOCK_SIZE;
    /** endFrame(seq) calls, in order. */
    final List<Long> endFrameSeqs = new ArrayList<>();
    /** Every allocation: {usage, size} (inspection). */
    final List<long[]> allocations = new ArrayList<>();
    private int generation;

    public MetalTransientMemory(final MetalDevice device) {
        this.device = device;
    }

    public void endFrame(final long frameSeq) {
        this.endFrameSeqs.add(frameSeq);
        this.generation++;
    }

    public void close() {
        for (long address : this.blockAddresses) {
            MemoryUtil.nmemAlignedFree(address);
        }
        this.blockAddresses.clear();
    }

    /** Block handle (fake) for block index {@code i}. */
    static long blockHandle(final int i) {
        return HANDLE_BASE + (long)i * 0x1000L;
    }

    /** CPU address of the bytes behind (handle, absolute offset) of a transient allocation. */
    long resolve(final long handle, final long absoluteOffset) {
        int index = (int)((handle - HANDLE_BASE) / 0x1000L);
        return this.blockAddresses.get(index) + absoluteOffset;
    }

    private GpuBufferSlice allocate(final long size, final long alignment, final @GpuBuffer.Usage int usage) {
        long align = Math.max(alignment, MIN_GPU_ALIGNMENT);
        long need = size + SLICE_SKEW;
        long start = (this.used + align - 1L) & -align;
        if (start + need > BLOCK_SIZE) {
            if (need > BLOCK_SIZE) {
                throw new IllegalArgumentException("test double: allocation too large");
            }
            long block = MemoryUtil.nmemAlignedAlloc(16L, BLOCK_SIZE);
            MemoryUtil.memSet(block, 0xCD, BLOCK_SIZE);
            this.blockAddresses.add(block);
            start = 0L;
        }
        this.used = start + need;
        int index = this.blockAddresses.size() - 1;
        long base = this.blockAddresses.get(index);
        TransientBuffer buffer = new TransientBuffer(this.device, blockHandle(index), base, 0xA000_0000L + base, usage, BLOCK_SIZE, this.generation, this);
        buffer.setBacking(blockHandle(index), base + start, 0xA000_0000L + base + start, start);
        this.allocations.add(new long[]{usage, size});
        return new GpuBufferSlice(buffer, SLICE_SKEW, size);
    }

    @Override
    public ByteBuffer allocateCpu(final long size, final long alignment, final long minimumAllocation, final long elementSize) {
        return MemoryUtil.memAlignedAlloc((int)Math.max(alignment, 1L), (int)size);
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(
        final long size, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize
    ) {
        GpuBufferSlice slice = this.allocate(size, alignment, usage);
        MetalBuffer buffer = (MetalBuffer)slice.buffer();
        return new GpuBufferSlice.MappedView(slice, MemoryUtil.memByteBuffer(buffer.contents() + slice.offset(), (int)size), () -> {});
    }

    @Override
    public GpuBufferSlice allocateGpu(final long size, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize) {
        return this.allocate(size, alignment, usage);
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(
        final long size, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize
    ) {
        return this.allocateStaging(size, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice uploadStaging(
        final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize
    ) {
        long total = 0L;
        for (ByteBuffer b : data) {
            total += (b.remaining() + alignment - 1L) / alignment * alignment;
        }
        GpuBufferSlice slice = this.allocate(total, alignment, usage);
        MetalBuffer buffer = (MetalBuffer)slice.buffer();
        long at = buffer.contents() + slice.offset();
        for (ByteBuffer b : data) {
            // Deliberately reads through absolute accessors: never moves the caller's position.
            for (int i = 0; i < b.remaining(); i++) {
                MemoryUtil.memPutByte(at + i, b.get(b.position() + i));
            }
            at += (b.remaining() + alignment - 1L) / alignment * alignment;
        }
        return slice;
    }

    @Override
    public GpuBufferSlice uploadGpu(
        final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize
    ) {
        return this.uploadStaging(data, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
        List<GpuBufferSlice> out = new ArrayList<>();
        for (ByteBuffer b : data) {
            out.add(this.uploadStaging(List.of(b), alignment, usage, b.remaining(), 1L));
        }
        return out;
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
        return this.multiUploadStaging(data, alignment, usage);
    }

    public static final class TransientBuffer extends MetalBuffer {
        private final int generation;
        private final MetalTransientMemory owner;

        public TransientBuffer(
            final MetalDevice device, final long handle, final long contents, final long gpuAddress, final @GpuBuffer.Usage int usage, final long size
        ) {
            this(device, handle, contents, gpuAddress, usage, size, 0, null);
        }

        TransientBuffer(
            final MetalDevice device, final long handle, final long contents, final long gpuAddress, final @GpuBuffer.Usage int usage, final long size,
            final int generation, final MetalTransientMemory owner
        ) {
            super(device, handle, contents, gpuAddress, usage, size);
            this.generation = generation;
            this.owner = owner;
        }

        void setBacking(final long handle, final long contents, final long gpuAddress, final long backingOffset) {
            this.rebind(handle, contents, gpuAddress, backingOffset);
        }

        @Override
        public boolean isTransient() {
            return true;
        }

        @Override
        public boolean isClosed() {
            return this.owner != null && this.owner.generation != this.generation;
        }

        @Override
        public void close() {
        }

        @Override
        public GpuBufferSlice.MappedView map(final long offset, final long length, final boolean read, final boolean write) {
            throw new IllegalStateException("Cannot map transient buffer");
        }

        @Override
        public GpuBufferSlice slice(final long offset, final long length) {
            throw new IllegalStateException("Cannot slice transient buffer");
        }

        @Override
        public GpuBufferSlice slice() {
            throw new IllegalStateException("Cannot slice transient buffer");
        }

        @Override
        public long texelView(final int pixelFormat, final int bytesPerTexel) {
            throw new IllegalStateException("Transient buffers cannot be bound as texel buffers");
        }
    }
}
