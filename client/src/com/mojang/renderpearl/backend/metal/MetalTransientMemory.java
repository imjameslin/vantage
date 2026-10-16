package com.mojang.renderpearl.backend.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.device.GpuOutOfMemoryException;
import com.mojang.renderpearl.backend.util.TransientBlockAllocator;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * {@link TransientMemory} (spec 2.9). OWNER: I3 (java-device). One per device, returned by
 * {@code MetalCommandEncoder.transientMemory()}.
 *
 * <p>Design (CONTRACT.md 9.3): CPU allocations use a {@code TransientBlockAllocator<CpuBlock>} (512 KiB
 * blocks, alignment <= 16) recycled immediately at the end of the frame. Staging, GPU and GPU-mapped
 * allocations share one {@code TransientBlockAllocator} over Shared {@code MTLBuffer} blocks of
 * {@link #BLOCK_SIZE}, effective alignment {@code max(requested, 16)} (buffer<->texture copies need offsets
 * that are multiples of the texel size), recycled through {@code timeline.deferUntil(frameSeq, rotate())}.
 * Returned slices are backed by {@link TransientBuffer} ({@code map()}/{@code slice()} throw,
 * {@code isClosed()} becomes true at the next frame, {@code size()} = whole block). The multi-upload
 * fallback quirk of Vulkan (allocateGpuMapped when nothing fits) is mirrored.
 *
 * <p>Cross-module API: I4 uses {@code uploadStaging(data, 16, GpuBuffer.USAGE_COPY_SRC)} for the
 * writeToBuffer / writeToTexture slow paths (the slice's buffer is a {@link MetalBuffer}) and calls
 * {@link #endFrame(long)} from submit(); I3's device calls {@link #close()} at shutdown.
 *
 * <p>Alignment: the effective alignment is the least common multiple of the requested alignment and 16,
 * which equals {@code max(requested, 16)} for every power of two (all vanilla requests) and still honours a
 * non-power-of-two request (the API allows any positive alignment, spec 2.9). Pieces inside one
 * {@code upload*} allocation are padded to the requested alignment, exactly like Vulkan.
 *
 * <p>Unified memory: staging, GPU and GPU-mapped memory are the same Shared blocks, so GPU-mapped writes
 * need no copy; they must precede the commit of the command buffer that reads them (spec 2.9; vanilla
 * complies). A headless ({@link MetalDevice#forTesting}) device uses host-memory blocks with synthetic
 * handles, so I4's headless tests can stage uploads.
 */
public final class MetalTransientMemory implements TransientMemory {
    /** Size of a staging/GPU block (spec 2.9 allows 4-8 MiB). */
    public static final long BLOCK_SIZE = 4L * 1024L * 1024L;
    /** Size of a CPU block (vanilla value). */
    public static final long CPU_BLOCK_SIZE = 512L * 1024L;
    /** Minimum alignment of every staging/GPU allocation. */
    public static final long MIN_GPU_ALIGNMENT = 16L;
    /** Maximum alignment of CPU allocations (vanilla value). */
    public static final long MAX_CPU_ALIGNMENT = 16L;
    /** Maximum alignment of GPU allocations (Vulkan's MAX_GPU_ALIGNMENT). */
    static final long MAX_GPU_ALIGNMENT = Long.highestOneBit(Long.MAX_VALUE);
    /** Usage passed to mcmtl_buffer_create for blocks (no texel rounding needed: blocks are 16-aligned). */
    private static final int BLOCK_USAGE = GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX
        | GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_INDIRECT_PARAMETERS;
    private static final Runnable NO_OP = () -> {};
    /**
     * {@code -Dmcmetal.transientHysteresis=true} (CONTRACT.md 16.R5-C, default off; read when a device is created): the
     * vanilla allocator frees every idle block that a rotation did not reuse, so a frame that needs no staging (every
     * writeToBuffer took the memcpy path) is followed, two frames later, by a fresh 4 MiB MTLBuffer. With hysteresis an
     * idle block is kept in a reserve while the reserve holds fewer than the most blocks any of the last
     * {@link #HYSTERESIS_ROTATIONS} rotations used, and the allocator takes reserve blocks before creating new ones.
     */
    static boolean TRANSIENT_HYSTERESIS = Boolean.getBoolean("mcmetal.transientHysteresis");
    static final int HYSTERESIS_ROTATIONS = 120;

    private final MetalDevice device;
    private final TransientBlockAllocator<TransientBlockAllocator.Allocator.CpuBlock> cpuAllocator;
    private final TransientBlockAllocator<GpuBlock> gpuAllocator;
    /** Advances in endFrame(); transient buffers of older generations report isClosed(). Render thread. */
    private long generation;
    private boolean closed;
    // Diagnostics.
    private long blocksCreated;
    private long blocksFreed;
    // Hysteresis (render thread). All blocks in `reserve` are idle: they reach it only through freeBlock, which the
    // allocator calls for blocks whose frame completed (TransientBlockAllocator.rotate's idle list).
    private final boolean hysteresis;
    private final int @Nullable [] usedPerRotation;
    private int rotationIndex;
    private int usedThisRotation;
    private int highWater;
    private GpuBlock[] reserve = new GpuBlock[0];
    private int reserveCount;
    private long blocksReused;

    public MetalTransientMemory(final MetalDevice device) {
        this.device = device;
        this.cpuAllocator = new TransientBlockAllocator<>(CPU_BLOCK_SIZE, MAX_CPU_ALIGNMENT, TransientBlockAllocator.Allocator.CpuBlock.memalloc());
        this.hysteresis = TRANSIENT_HYSTERESIS;
        if (this.hysteresis) {
            this.usedPerRotation = new int[HYSTERESIS_ROTATIONS];
            this.gpuAllocator = new TransientBlockAllocator<>(
                BLOCK_SIZE, MAX_GPU_ALIGNMENT, TransientBlockAllocator.Allocator.create(this::allocateBlock, this::freeBlock), this::onBlockUse
            );
        } else {
            this.usedPerRotation = null;
            this.gpuAllocator = new TransientBlockAllocator<>(
                BLOCK_SIZE, MAX_GPU_ALIGNMENT, TransientBlockAllocator.Allocator.create(this::allocateBlock, this::freeBlock)
            );
        }
        // Blocks are created lazily on first use.
    }

    /** Hysteresis: the allocator handed out a block for this rotation (regular blocks only; oversized ones are one-offs). */
    private void onBlockUse(final GpuBlock block) {
        if (block.size() == BLOCK_SIZE) {
            this.usedThisRotation++;
        }
    }

    /** A Shared MTLBuffer block (or host memory for a headless device). */
    record GpuBlock(long handle, long contents, long gpuAddress, long size, boolean headless) implements TransientBlockAllocator.Allocator.Block {
        @Override
        public boolean suboptimal() {
            return false;
        }
    }

    private GpuBlock allocateBlock(final long size) {
        if (this.reserveCount > 0 && size == BLOCK_SIZE) {
            // Hysteresis: an idle block kept by freeBlock (its last frame completed).
            GpuBlock block = this.reserve[--this.reserveCount];
            this.reserve[this.reserveCount] = null;
            this.blocksReused++;
            return block;
        }
        long allocated = MetalBuffer.alignUp(Math.max(size, MIN_GPU_ALIGNMENT), MIN_GPU_ALIGNMENT);
        if (this.device.handle() == 0L) {
            long memory = MemoryUtil.nmemAlignedAlloc(256L, allocated);
            if (memory == 0L) {
                throw new GpuOutOfMemoryException("Headless transient block of " + allocated + " bytes");
            }
            this.blocksCreated++;
            return new GpuBlock(MetalBuffer.Headless.nextHandle(), memory, memory, size, true);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long desc = stack.ncalloc(8, 1, (int)MtlNative.BUFFER_DESC_SIZEOF);
            long out = stack.ncalloc(8, 1, (int)MtlNative.BUFFER_RESULT_SIZEOF);
            MemoryUtil.memPutInt(desc + MtlNative.BUFFER_DESC_STRUCT_SIZE, (int)MtlNative.BUFFER_DESC_SIZEOF);
            MemoryUtil.memPutInt(desc + MtlNative.BUFFER_DESC_USAGE, BLOCK_USAGE);
            MemoryUtil.memPutLong(desc + MtlNative.BUFFER_DESC_SIZE, allocated);
            // Shared, default cache mode: staging is also read back by the CPU in tests and stays cheap to read.
            MemoryUtil.memPutInt(desc + MtlNative.BUFFER_DESC_RESOURCE_OPTIONS, MetalFormats.RESOURCE_STORAGE_SHARED);
            if (this.device.debugLabels()) {
                MemoryUtil.memPutAddress(desc + MtlNative.BUFFER_DESC_LABEL, MetalBuffer.utf8Label(stack, "Metal transient block"));
            }
            MemoryUtil.memPutInt(out + MtlNative.BUFFER_RESULT_STRUCT_SIZE, (int)MtlNative.BUFFER_RESULT_SIZEOF);
            MtlNative.check(MtlNative.bufferCreate(this.device.handle(), desc, out), "mcmtl_buffer_create(transient block, " + allocated + " bytes)");
            long handle = MemoryUtil.memGetLong(out + MtlNative.BUFFER_RESULT_HANDLE);
            long contents = MemoryUtil.memGetLong(out + MtlNative.BUFFER_RESULT_CONTENTS);
            long gpuAddress = MemoryUtil.memGetLong(out + MtlNative.BUFFER_RESULT_GPU_ADDRESS);
            if (handle == 0L || contents == 0L) {
                this.device.timeline().deferRelease(handle);
                throw new IllegalStateException("mcmtl_buffer_create returned no transient block");
            }
            this.blocksCreated++;
            return new GpuBlock(handle, contents, gpuAddress, size, false);
        }
    }

    /**
     * Called by the allocator for blocks that sat unused for a rotation (idle), for oversized one-off blocks and
     * at close, always after their frame completed (or at shutdown with the GPU idle). The native release still
     * goes through the FIFO (one batched release per drain).
     */
    private void freeBlock(final GpuBlock block) {
        if (this.hysteresis && !this.closed && block.size() == BLOCK_SIZE && this.reserveCount < this.highWater) {
            if (this.reserveCount == this.reserve.length) {
                this.reserve = Arrays.copyOf(this.reserve, Math.max(4, this.reserveCount * 2));
            }
            this.reserve[this.reserveCount++] = block;
            return;
        }
        this.releaseBlock(block);
    }

    private void releaseBlock(final GpuBlock block) {
        this.blocksFreed++;
        if (block.headless()) {
            this.device.timeline().deferRelease(block.handle());
            long memory = block.contents();
            this.device.timeline().defer(() -> MemoryUtil.nmemAlignedFree(memory));
        } else {
            this.device.timeline().deferRelease(block.handle());
        }
    }

    /**
     * Called by {@code MetalCommandEncoder.submit()} after the frame's last commit signaled {@code frameSeq}:
     * CPU blocks recycle now; staging/GPU blocks via {@code timeline.deferUntil(frameSeq, ...)}; the
     * transient-buffer generation advances so earlier transient buffers report {@code isClosed()}.
     */
    public void endFrame(final long frameSeq) {
        if (this.closed) {
            return;
        }
        this.cpuAllocator.rotate().run();
        if (this.hysteresis) {
            this.endRotation();
        }
        Runnable recycle = this.gpuAllocator.rotate();
        if (this.hysteresis) {
            // rotate() handed its idle blocks to freeBlock (reserve up to highWater); the window may have shrunk.
            while (this.reserveCount > this.highWater) {
                GpuBlock block = this.reserve[--this.reserveCount];
                this.reserve[this.reserveCount] = null;
                this.releaseBlock(block);
            }
        }
        this.device.timeline().deferUntil(frameSeq, recycle);
        this.generation++;
    }

    /** Hysteresis: closes this rotation's count and recomputes the high-water mark of the last HYSTERESIS_ROTATIONS. */
    private void endRotation() {
        int[] used = this.usedPerRotation;
        used[this.rotationIndex] = this.usedThisRotation;
        this.rotationIndex = this.rotationIndex + 1 == used.length ? 0 : this.rotationIndex + 1;
        this.usedThisRotation = 0;
        int max = 0;
        for (int n : used) {
            max = Math.max(max, n);
        }
        this.highWater = max;
    }

    /** Device shutdown (after the GPU is idle): frees every block. Idempotent. */
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.generation++;
        this.cpuAllocator.close();
        this.gpuAllocator.close();
        while (this.reserveCount > 0) {
            GpuBlock block = this.reserve[--this.reserveCount];
            this.reserve[this.reserveCount] = null;
            this.releaseBlock(block);
        }
    }

    /** Hysteresis (tests, diagnostics): blocks taken from the reserve instead of created, blocks in the reserve, high-water mark. */
    long blocksReused() {
        return this.blocksReused;
    }

    int reservedBlocks() {
        return this.reserveCount;
    }

    int highWater() {
        return this.highWater;
    }

    /** Current generation (tests): bumped by every {@link #endFrame(long)}. */
    long generation() {
        return this.generation;
    }

    /** Blocks created / freed so far (tests, diagnostics). */
    long blocksCreated() {
        return this.blocksCreated;
    }

    long blocksFreed() {
        return this.blocksFreed;
    }

    /** lcm(max(alignment, 1), 16): honours the request and the 16-byte minimum (CONTRACT.md 9.3). */
    static long effectiveGpuAlignment(final long alignment) {
        if (alignment <= MIN_GPU_ALIGNMENT) {
            // 1, 2, 4, 8, 16 divide 16; other values below 16 (3, 5, 6, ...) need the lcm.
            long requested = Math.max(alignment, 1L);
            return MIN_GPU_ALIGNMENT % requested == 0L ? MIN_GPU_ALIGNMENT : lcm(requested, MIN_GPU_ALIGNMENT);
        }
        return alignment % MIN_GPU_ALIGNMENT == 0L ? alignment : lcm(alignment, MIN_GPU_ALIGNMENT);
    }

    private static long lcm(final long a, final long b) {
        long x = a;
        long y = b;
        while (y != 0L) {
            long t = x % y;
            x = y;
            y = t;
        }
        return Math.multiplyExact(a / x, b);
    }

    private void checkOpen() {
        if (this.closed) {
            throw new IllegalStateException("Transient memory used after the device was closed");
        }
    }

    private TransientBlockAllocator.Allocation<GpuBlock> allocateGpuBlock(final long size, final long alignment, final long minimumAllocation, final long elementSize) {
        if (MetalFrameThrottle.ENABLED) {
            // Late throttle (CONTRACT.md 16.R5-A): blocks of frame N-1 are back in the free list first, as before.
            this.device.frameThrottle().ensureThrottled();
        }
        this.checkOpen();
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Transient allocation of " + size + " bytes exceeds 2 GB");
        }
        return this.gpuAllocator.allocate(size, effectiveGpuAlignment(alignment), minimumAllocation, Math.max(elementSize, 1L));
    }

    private TransientBuffer wrap(final TransientBlockAllocator.Allocation<GpuBlock> alloc, final @GpuBuffer.Usage int usage) {
        GpuBlock block = alloc.block();
        return new TransientBuffer(this, block.handle(), block.contents(), block.gpuAddress(), usage, block.size(), this.generation);
    }

    private GpuBufferSlice.MappedView mapped(final TransientBlockAllocator.Allocation<GpuBlock> alloc, final @GpuBuffer.Usage int usage) {
        TransientBuffer buffer = this.wrap(alloc, usage);
        ByteBuffer cpu = MemoryUtil.memByteBuffer(alloc.block().contents() + alloc.offset(), (int)alloc.size());
        return new GpuBufferSlice.MappedView(new GpuBufferSlice(buffer, alloc.offset(), alloc.size()), cpu, NO_OP);
    }

    @Override
    public ByteBuffer allocateCpu(final long size, final long alignment, final long minimumAllocation, final long elementSize) {
        this.checkOpen();
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Transient CPU allocation of " + size + " bytes exceeds 2 GB");
        }
        TransientBlockAllocator.Allocation<TransientBlockAllocator.Allocator.CpuBlock> alloc = this.cpuAllocator
            .allocate(size, Math.max(alignment, 1L), minimumAllocation, Math.max(elementSize, 1L));
        return MemoryUtil.memByteBuffer(alloc.block().address() + alloc.offset(), (int)alloc.size());
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(
        final long size, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize
    ) {
        return this.mapped(this.allocateGpuBlock(size, alignment, minimumAllocation, elementSize), usage);
    }

    @Override
    public GpuBufferSlice allocateGpu(final long size, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize) {
        TransientBlockAllocator.Allocation<GpuBlock> alloc = this.allocateGpuBlock(size, alignment, minimumAllocation, elementSize);
        return new GpuBufferSlice(this.wrap(alloc, usage), alloc.offset(), alloc.size());
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(
        final long size, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize
    ) {
        return this.mapped(this.allocateGpuBlock(size, alignment, minimumAllocation, elementSize), usage);
    }

    @Override
    public GpuBufferSlice uploadStaging(
        final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize
    ) {
        return this.upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice uploadGpu(
        final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize
    ) {
        return this.upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    /**
     * Concatenates {@code data} (each piece padded to {@code alignment}) into one allocation; with a partial
     * allocation only what fits is copied (Vulkan parity). Staging and GPU uploads are the same memory here.
     */
    private GpuBufferSlice upload(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize) {
        long pieceAlignment = Math.max(alignment, 1L);
        long totalSize = 0L;
        for (int i = 0; i < data.size(); i++) {
            totalSize += data.get(i).remaining();
            totalSize = Mth.roundToward(totalSize, pieceAlignment);
        }
        TransientBlockAllocator.Allocation<GpuBlock> alloc = this.allocateGpuBlock(totalSize, alignment, minimumAllocation, elementSize);
        long base = alloc.block().contents() + alloc.offset();
        long length = alloc.size();
        long offset = 0L;
        for (int i = 0; i < data.size(); i++) {
            ByteBuffer piece = data.get(i);
            copy(piece, base + offset, Math.min(length - offset, piece.remaining()));
            offset += piece.remaining();
            offset = Mth.roundToward(offset, pieceAlignment);
            if (offset >= length) {
                break;
            }
        }
        return new GpuBufferSlice(this.wrap(alloc, usage), alloc.offset(), length);
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
        return this.multiUpload(data, alignment, usage);
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
        return this.multiUpload(data, alignment, usage);
    }

    /**
     * Vulkan's packing (VulkanTransientMemory.multiUpload): repeatedly place the largest remaining array that
     * fits the current block; when none fits, the largest goes into a new block (Vulkan's "allocateGpuMapped"
     * fallback, which here is the same allocator). Results in input order.
     */
    private List<GpuBufferSlice> multiUpload(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
        if (MetalFrameThrottle.ENABLED) {
            this.device.frameThrottle().ensureThrottled(); // before canAllocateInCurrentBlock looks at the free list
        }
        this.checkOpen();
        int count = data.size();
        GpuBufferSlice[] results = new GpuBufferSlice[count];
        long effective = effectiveGpuAlignment(alignment);
        IntArrayList sorted = IntArrayList.toList(IntStream.range(0, count));
        sorted.sort((a, b) -> Integer.compare(data.get(a).remaining(), data.get(b).remaining()));
        while (!sorted.isEmpty()) {
            int chosen = -1;
            for (int i = sorted.size() - 1; i >= 0; i--) {
                int bufferIndex = sorted.getInt(i);
                if (this.gpuAllocator.canAllocateInCurrentBlock(data.get(bufferIndex).remaining(), effective)) {
                    sorted.removeInt(i);
                    chosen = bufferIndex;
                    break;
                }
            }
            if (chosen < 0) {
                chosen = sorted.popInt();
            }
            ByteBuffer piece = data.get(chosen);
            int length = piece.remaining();
            TransientBlockAllocator.Allocation<GpuBlock> alloc = this.allocateGpuBlock(length, alignment, length, 1L);
            copy(piece, alloc.block().contents() + alloc.offset(), length);
            results[chosen] = new GpuBufferSlice(this.wrap(alloc, usage), alloc.offset(), alloc.size());
        }
        return new ArrayList<>(Arrays.asList(results));
    }

    /** Copies {@code length} bytes from {@code source}'s position (direct or heap; source unchanged). */
    static void copy(final ByteBuffer source, final long destination, final long length) {
        if (length <= 0L) {
            return;
        }
        if (source.isDirect()) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(source), destination, length);
        } else {
            ByteBuffer from = source.duplicate();
            from.limit(from.position() + (int)length);
            MemoryUtil.memByteBuffer(destination, (int)length).put(from);
        }
    }

    /**
     * A transient block exposed as a {@link GpuBuffer} (one object per allocation, like Vulkan). Not owned:
     * {@code close()} only marks it closed; the block is recycled by the allocator.
     */
    public static final class TransientBuffer extends MetalBuffer {
        private final MetalTransientMemory memory;
        private final long allocationGeneration;
        private boolean closed;

        public TransientBuffer(
            final MetalDevice device, final long handle, final long contents, final long gpuAddress, final @GpuBuffer.Usage int usage, final long size
        ) {
            this(device.transientMemory(), handle, contents, gpuAddress, usage, size, device.transientMemory().generation);
        }

        private TransientBuffer(
            final MetalTransientMemory memory,
            final long handle,
            final long contents,
            final long gpuAddress,
            final @GpuBuffer.Usage int usage,
            final long size,
            final long allocationGeneration
        ) {
            super(memory.device, handle, contents, gpuAddress, usage, size);
            this.memory = memory;
            this.allocationGeneration = allocationGeneration;
        }

        @Override
        public boolean isTransient() {
            return true;
        }

        /** True once the frame it was allocated in has been submitted (or after close()). */
        @Override
        public boolean isClosed() {
            if (!this.closed && this.allocationGeneration != this.memory.generation) {
                this.closed = true;
            }
            return this.closed;
        }

        @Override
        public void close() {
            this.closed = true;
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
