package com.mojang.renderpearl.backend.metal;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * {@link GpuBuffer} backed by a Shared {@code MTLBuffer} (spec 2.2, 2.8). OWNER: I3 (java-device).
 *
 * <p>Extends {@link BaseGpuBuffer} because the frontend casts to it. {@code map()} is a pointer
 * computation on the cached {@link #contents()} address (unsynchronized, coherent, persistent-capable).
 * {@code close()} is idempotent, throws "Attempt to close a mapped buffer" while a mapping is open (Vulkan
 * parity) and defers the native release through {@link MetalTimeline#deferRelease(long)} (together with
 * any cached texel views).
 *
 * <p>Transient staging/GPU blocks are a subclass living in {@link MetalTransientMemory}
 * ({@link #isTransient()} true; map/slice throw; isClosed() flips at the next submit).
 *
 * <p>Cross-module API: I4 reads {@link #handle()} and {@link #backingOffset()} for stream records at
 * RECORD TIME (never cached in a pass or elsewhere: the backing may change between records), writes
 * {@code backingOffset() + sliceOffset} as the record's offset, calls {@link #markUsed(long)} with
 * {@code pendingSeq} on every recorded reference (bindings, vertex/index/indirect buffers, copy source or
 * destination) and uses {@link #lastUseSeq()} + {@link #contents()} for the writeToBuffer fast path
 * (CONTRACT.md 9.4). I4 calls {@link #texelView(int, int)} for TEXEL_BUFFER uniforms.
 *
 * <p>Backing: (handle, contents, gpuAddress, backingOffset) is render-thread state, not final, so that M2
 * buffer renaming (spec 9.1 B8: repoint a small, whole-buffer writeToBuffer target to a fresh ring
 * allocation; already-recorded commands keep the old backing) needs no change outside I3. M1 never calls
 * {@link #rebind} and backingOffset is always 0 for non-transient buffers. Round 5 renaming of small in-flight
 * writeToBuffer targets ({@code -Dmcmetal.renameSmallWrites}, default on) is {@link #renameForWrite}: it swaps
 * (handle, contents, gpuAddress) to one of the buffer's own idle retired backings (CONTRACT.md 16.R5-C).
 *
 * <p>Close-while-mapped: like Vulkan the buffer is marked closed and the call throws; unlike Vulkan the
 * backing is not leaked: it is released (deferred) when the last mapping closes.
 *
 * <p>Recycling (spec 2.2 SHOULD): a closed buffer's {@code MTLBuffer} goes back to a device-owned
 * {@link Pool} once the GPU passed the close tag, and {@link #create} reuses an idle backing of the same
 * size and resource options (StagedVertexBuffer closes and recreates 256 KiB-multiple buffers whenever the
 * GPU lags). Contents of a recycled backing are stale, which the API allows (contents undefined).
 */
public class MetalBuffer extends BaseGpuBuffer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_LABEL_BYTES = MtlNative.MAX_LABEL_BYTES;
    /**
     * Diagnostics (env {@code MCMTL_COPY_STATS}, CONTRACT.md 16.R5-C): buffers get their native label even with debug
     * labels off, so the decoder's copy-destination statistics can name them. Labelled buffers bypass the recycling
     * pool (a recycled backing would carry another buffer's label). Never set in normal play.
     */
    static final boolean DIAG_LABEL_BUFFERS = diagEnv("MCMTL_COPY_STATS");
    /** Renaming (CONTRACT.md 16.R5-C): largest buffer {@link #renameForWrite} accepts. */
    static final long RENAME_MAX_BYTES = 64L * 1024L;
    /** Renaming: retired backings kept per buffer (in flight or idle); at the cap a write that finds none idle is staged. */
    static final int RENAME_MAX_SPARES = 16;
    /** {@link #renameForWrite} results: 0 = renamed, otherwise why not (diagnostics, tests). */
    static final int RENAME_OK = 0;
    static final int RENAME_NO_SIZE = 1;        // larger than RENAME_MAX_BYTES
    static final int RENAME_NO_KIND = 2;        // transient / wrapped / sub-allocated backing, closed, texel-buffer usage or view
    static final int RENAME_NO_MAPPED = 3;      // a mapping is open (its ByteBuffer points into the current backing)
    static final int RENAME_NO_GPU_WRITE = 4;   // a GPU write (copy destination, or a staged write not kept in the replay list) may not have completed
    static final int RENAME_NO_SPARE = 5;       // RENAME_MAX_SPARES backings all in flight, or the native create failed
    static final int RENAME_REASONS = 6;
    /** Renaming: staged writeToBuffer copies kept for replay ({@link #recordStagedWrite}); beyond that a GPU write is marked. */
    static final int RENAME_MAX_STAGED = 8;

    private final MetalDevice device;
    // Backing (render thread). contents/gpuAddress already include backingOffset.
    private long handle;
    private long contents;
    private long gpuAddress;
    private long backingOffset;
    private long lastUseSeq;
    /** Highest timeline value of recorded work that writes this buffer on the GPU (copy destination); 0 = never. */
    private long gpuWriteSeq;
    /** Allocated length of the backing (>= size()); 0 when unknown (wrapped handles). */
    private final long allocatedLength;
    /** MTLResourceOptions of the backing (pool key). */
    private final int resourceOptions;
    /** Host memory of a headless (forTesting) buffer's current backing, freed with the release; 0 otherwise. */
    private long headlessMemory;
    /**
     * Renaming: retired backings of this buffer, 4 longs each {handle, contents, gpuAddress, freeAt}; a backing is idle
     * (reusable) once {@code freeAt <= completedSeq}. Allocated at the first rename. Render thread.
     */
    private long @Nullable [] spares;
    private int spareCount;
    /** Label supplier of a natively labelled buffer (debug labels or {@link #DIAG_LABEL_BUFFERS}); renamed backings get the same label. */
    private @Nullable Supplier<String> diagLabel;
    /**
     * Renaming (r5-int review fix): writeToBuffer calls staged into this buffer (COPY_BUFFER from transient staging memory)
     * whose copy may not have run yet, in stream order, 4 longs each {seq, dstOffset, length, staging CPU address}. Such a
     * write is not a reason to refuse renaming: {@link #renameForWrite} replays it from the staging memory (alive until its
     * frame completed) into the new backing. Render thread; allocated at the first staged write.
     */
    private long @Nullable [] stagedWrites;
    private int stagedWriteCount;

    private boolean closed;
    private boolean released;
    private int mappingCount;
    private @Nullable GpuBufferSlice wholeSlice;

    // Texel-view cache (spec 3.7): one view per pixel format of the current backing (vanilla: one, R8Sint).
    private int texelViewCount;
    private int[] texelViewFormats;
    private long[] texelViewHandles;

    /**
     * Wraps an existing native buffer (I3 factories and the transient subclass).
     *
     * @param handle     +1 id&lt;MTLBuffer&gt; owned by this object (released deferred on close)
     * @param contents   CPU address of the contents (mcmtl_buffer_result.contents)
     * @param gpuAddress mcmtl_buffer_result.gpuAddress
     * @param size       the size the game asked for ({@code size()}; the allocation may be larger)
     */
    protected MetalBuffer(
        final MetalDevice device, final long handle, final long contents, final long gpuAddress, final @GpuBuffer.Usage int usage, final long size
    ) {
        this(device, handle, contents, gpuAddress, usage, size, 0L, -1, 0L);
    }

    private MetalBuffer(
        final MetalDevice device,
        final long handle,
        final long contents,
        final long gpuAddress,
        final @GpuBuffer.Usage int usage,
        final long size,
        final long allocatedLength,
        final int resourceOptions,
        final long headlessMemory
    ) {
        super(usage, size);
        this.device = device;
        this.handle = handle;
        this.contents = contents;
        this.gpuAddress = gpuAddress;
        this.allocatedLength = allocatedLength;
        this.resourceOptions = resourceOptions;
        this.headlessMemory = headlessMemory;
    }

    /**
     * Creates a Shared buffer: mcmtl_buffer_create with resourceOptions
     * {@code MetalFormats.bufferResourceOptions(usage, MetalDevice.WRITE_COMBINED)}, label evaluated only when
     * {@code device.debugLabels()}. Native rounds UNIFORM_TEXEL_BUFFER allocations up to 16 bytes; {@code size()}
     * stays as requested. MCMTL_E_OUT_OF_MEMORY -> GpuOutOfMemoryException. Reuses a pooled backing of the
     * same size class whose deferred release completed (spec 2.2).
     *
     * <p>A {@link MetalDevice#forTesting} device (handle 0) gets host memory and a synthetic handle instead
     * (no native call), so headless tests can record and map buffers.
     */
    public static MetalBuffer create(final MetalDevice device, final @Nullable Supplier<String> label, final @GpuBuffer.Usage int usage, final long size) {
        if (size <= 0L) {
            throw new IllegalArgumentException("Buffer size must be greater than zero, was " + size);
        }
        int options = MetalFormats.bufferResourceOptions(usage, MetalDevice.WRITE_COMBINED);
        if (device.handle() == 0L) {
            return createHeadless(device, usage, size, options);
        }
        Pool pool = device.bufferPool();
        boolean labelled = (device.debugLabels() || DIAG_LABEL_BUFFERS) && label != null;
        if (!labelled) {
            Pool.Backing backing = pool.take(size, usage, options);
            if (backing != null) {
                return new MetalBuffer(device, backing.handle, backing.contents, backing.gpuAddress, usage, size, backing.length, options, 0L);
            }
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long desc = stack.ncalloc(8, 1, (int)MtlNative.BUFFER_DESC_SIZEOF);
            long out = stack.ncalloc(8, 1, (int)MtlNative.BUFFER_RESULT_SIZEOF);
            MemoryUtil.memPutInt(desc + MtlNative.BUFFER_DESC_STRUCT_SIZE, (int)MtlNative.BUFFER_DESC_SIZEOF);
            MemoryUtil.memPutInt(desc + MtlNative.BUFFER_DESC_USAGE, usage);
            MemoryUtil.memPutLong(desc + MtlNative.BUFFER_DESC_SIZE, size);
            MemoryUtil.memPutInt(desc + MtlNative.BUFFER_DESC_RESOURCE_OPTIONS, options);
            if (labelled) {
                MemoryUtil.memPutAddress(desc + MtlNative.BUFFER_DESC_LABEL, utf8Label(stack, label.get()));
            }
            MemoryUtil.memPutInt(out + MtlNative.BUFFER_RESULT_STRUCT_SIZE, (int)MtlNative.BUFFER_RESULT_SIZEOF);
            int status = MtlNative.bufferCreate(device.handle(), desc, out);
            if (status != MtlNative.OK) {
                if (status == MtlNative.E_OUT_OF_MEMORY && pool.trim()) {
                    // Idle pooled buffers were holding memory: retry once.
                    status = MtlNative.bufferCreate(device.handle(), desc, out);
                }
                MtlNative.check(status, "mcmtl_buffer_create(" + size + " bytes, usage " + usage + ")");
            }
            long handle = MemoryUtil.memGetLong(out + MtlNative.BUFFER_RESULT_HANDLE);
            long contents = MemoryUtil.memGetLong(out + MtlNative.BUFFER_RESULT_CONTENTS);
            long gpuAddress = MemoryUtil.memGetLong(out + MtlNative.BUFFER_RESULT_GPU_ADDRESS);
            long length = MemoryUtil.memGetLong(out + MtlNative.BUFFER_RESULT_LENGTH);
            if (handle == 0L || contents == 0L) {
                device.timeline().deferRelease(handle);
                throw new IllegalStateException("mcmtl_buffer_create returned no " + (handle == 0L ? "handle" : "CPU contents (not Shared storage?)"));
            }
            MetalBuffer buffer = new MetalBuffer(device, handle, contents, gpuAddress, usage, size, Math.max(length, size), options, 0L);
            if (labelled) {
                buffer.diagLabel = label;   // renamed backings get the same label (CONTRACT.md 16.R5-C)
            }
            return buffer;
        }
    }

    private static boolean diagEnv(final String name) {
        String value = System.getenv(name);
        return value != null && !value.isEmpty() && !value.equals("0");
    }

    /**
     * TASK E pregrow ({@link MetalHeapReserve#poll}): wraps a backing the reserve worker created with
     * {@code mcmtl_buffer_create} and {@code options} (or headless memory), exactly as {@link #create} wraps a fresh one.
     */
    static MetalBuffer wrapReserved(
        final MetalDevice device,
        final long handle,
        final long contents,
        final long gpuAddress,
        final @GpuBuffer.Usage int usage,
        final long size,
        final long length,
        final int options,
        final long headlessMemory
    ) {
        return new MetalBuffer(device, handle, contents, gpuAddress, usage, size, length, options, headlessMemory);
    }

    private static MetalBuffer createHeadless(final MetalDevice device, final @GpuBuffer.Usage int usage, final long size, final int options) {
        long allocated = alignUp(size, 16L);
        long memory = MemoryUtil.nmemAlignedAlloc(64L, allocated);
        if (memory == 0L) {
            throw new com.mojang.renderpearl.api.device.GpuOutOfMemoryException("Headless buffer of " + size + " bytes");
        }
        return new MetalBuffer(device, Headless.nextHandle(), memory, memory, usage, size, allocated, options, memory);
    }

    /** NUL-terminated UTF-8 label on {@code stack}, truncated to MCMTL_MAX_LABEL_BYTES. */
    static long utf8Label(final MemoryStack stack, final @Nullable String label) {
        if (label == null) {
            return 0L;
        }
        // <= 4 UTF-8 bytes per char keeps the encoded label within MCMTL_MAX_LABEL_BYTES.
        String text = label.length() > MAX_LABEL_BYTES / 4 ? label.substring(0, MAX_LABEL_BYTES / 4) : label;
        return MemoryUtil.memAddress(stack.UTF8(text, true));
    }

    static long alignUp(final long value, final long alignment) {
        return (value + alignment - 1L) / alignment * alignment;
    }

    public final MetalDevice device() {
        return this.device;
    }

    /** Native handle (id&lt;MTLBuffer&gt;) currently backing this buffer, written into stream records. Read at record time. */
    public final long handle() {
        return this.handle;
    }

    /** Byte offset of this buffer's byte 0 inside {@link #handle()}: add it to every recorded offset. Read at record time. */
    public final long backingOffset() {
        return this.backingOffset;
    }

    /** CPU address of this buffer's byte 0 (Shared storage; backing offset included; valid until the deferred release). */
    public final long contents() {
        return this.contents;
    }

    /** GPU address of this buffer's byte 0 (Metal 4 / ICB use; backing offset included). */
    public final long gpuAddress() {
        return this.gpuAddress;
    }

    /**
     * I3 only (M2 renaming, B8; transient sub-allocations): repoints the buffer to another backing. Records
     * written earlier keep the old handle; the caller defers the old backing's release/recycle with the
     * timeline. Cached texel views belong to the old backing and are released (deferred). Render thread.
     *
     * @param contents   CPU address of byte 0 of the new backing range
     * @param gpuAddress GPU address of byte 0 of the new backing range
     */
    protected final void rebind(final long handle, final long contents, final long gpuAddress, final long backingOffset) {
        this.releaseTexelViews();
        this.handle = handle;
        this.contents = contents;
        this.gpuAddress = gpuAddress;
        this.backingOffset = backingOffset;
    }

    /** Highest timeline value of recorded work referencing this buffer (0 = never). Render thread. */
    public final long lastUseSeq() {
        return this.lastUseSeq;
    }

    /** I4: record that work tagged {@code seq} (= pendingSeq) references this buffer. Render thread. */
    public final void markUsed(final long seq) {
        if (seq > this.lastUseSeq) {
            this.lastUseSeq = seq;
        }
    }

    /**
     * I4: work tagged {@code seq} WRITES this buffer on the GPU (COPY_BUFFER / COPY_TEXTURE_TO_BUFFER destination).
     * Tracked unconditionally (one compare per copy record) so that {@link #renameForWrite} stays exact when its
     * toggle is switched on at run time. Render thread.
     */
    public final void markGpuWrite(final long seq) {
        if (seq > this.gpuWriteSeq) {
            this.gpuWriteSeq = seq;
        }
    }

    /** Highest timeline value of recorded GPU writes into this buffer (0 = never). Render thread. */
    final long gpuWriteSeq() {
        return this.gpuWriteSeq;
    }

    /**
     * Exact stream-order bytes for {@link MetalLodReuseInputs}, or null when CPU contents cannot be trusted.
     * Render thread only. Never maps, submits, waits, drains releases, or changes the staged-write list.
     *
     * <p>This deliberately supports only small, ordinary Shared uniform buffers with the usages of the built-in
     * projection/globals (UNIFORM | COPY_DST), terrain/fog (UNIFORM | MAP_WRITE), and empty fog (UNIFORM).
     * GPU-written native LOD outputs do not all call {@link #markGpuWrite}; their mixed usages and/or large sizes
     * must NOT be accepted just because gpuWriteSeq is zero. This is an audited uniform-input path, not general
     * GPU readback. New native/shader writers of an accepted buffer kind must mark their writes on this timeline
     * or be excluded here. Unknown backings, subclasses and other usages fail closed.
     *
     * <p>Completed staged writes are already in Shared memory; their staging pointers may have been recycled.
     * Pending staging pointers remain alive until render-thread reclamation. Replay only those, in recording
     * order, then read the uncovered bytes from Shared memory. In particular, never read a destination range
     * that the GPU might currently be copying into, even if its bytes would subsequently be overwritten here.
     */
    final byte @Nullable [] snapshotCpuBytes(final long offset, final int bytes) {
        int usage = this.usage();
        long size = this.size();
        if (this.getClass() != MetalBuffer.class || this.closed || this.released || this.mappingCount != 0 || this.texelViewCount != 0
            || MetalCommandEncoder.FORCE_FAST_WRITE
            || this.device.isClosed() || this.device.timeline().isClosed()
            || this.handle == 0L || this.contents <= 0L || this.backingOffset != 0L
            || size <= 0L || size > RENAME_MAX_BYTES || this.allocatedLength < size
            || (this.resourceOptions != MetalFormats.RESOURCE_STORAGE_SHARED
                && this.resourceOptions != (MetalFormats.RESOURCE_STORAGE_SHARED | MetalFormats.RESOURCE_CPU_CACHE_WRITE_COMBINED))
            || (usage != GpuBuffer.USAGE_UNIFORM
                && usage != (GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE)
                && usage != (GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST))
            || offset < 0L || bytes < 0 || offset > size || bytes > size - offset
            || size > Long.MAX_VALUE - this.contents) {
            return null;
        }
        MetalTimeline timeline = this.device.timeline();
        long completed = timeline.completedSeq(); // acquire load only; never a GPU wait
        if (this.gpuWriteSeq > completed) {
            return null; // an unrecorded CPU payload / GPU producer may still overwrite any byte
        }
        long[] writes = this.stagedWrites;
        int count = this.stagedWriteCount;
        if (count < 0 || count > RENAME_MAX_STAGED || (count != 0 && (writes == null || count > writes.length / 4))) {
            return null;
        }
        long previousSeq = 0L;
        for (int i = 0; i < count; i++) {
            int p = i * 4;
            long seq = writes[p];
            if (seq <= 0L || seq < previousSeq || seq > timeline.pendingSeq()) {
                return null;
            }
            previousSeq = seq;
            if (seq <= completed) {
                continue; // do not inspect/dereference reclaimed staging memory
            }
            long start = writes[p + 1], length = writes[p + 2], address = writes[p + 3];
            if (start < 0L || length < 0L || start > size || length > size - start
                || address <= 0L || length > Long.MAX_VALUE - address) {
                return null;
            }
        }
        byte[] result = new byte[bytes];
        boolean[] covered = new boolean[bytes];
        long end = offset + bytes;
        for (int i = 0; i < count; i++) {
            int p = i * 4;
            if (writes[p] <= completed) {
                continue;
            }
            long start = writes[p + 1];
            long from = Math.max(offset, start), to = Math.min(end, start + writes[p + 2]);
            if (from < to) {
                int dst = (int)(from - offset), length = (int)(to - from);
                MemoryUtil.memByteBuffer(writes[p + 3] + from - start, length).get(result, dst, length);
                java.util.Arrays.fill(covered, dst, dst + length, true);
            }
        }
        for (int i = 0; i < bytes;) {
            if (covered[i]) {
                i++;
                continue;
            }
            int start = i++;
            while (i < bytes && !covered[i]) {
                i++;
            }
            MemoryUtil.memByteBuffer(this.contents + offset + start, i - start).get(result, start, i - start);
        }
        return result;
    }

    /** Retired backings currently held for renaming (tests, diagnostics). */
    final int spareCount() {
        return this.spareCount;
    }

    /** Staged writes kept for replay (tests). */
    final int stagedWriteCount() {
        return this.stagedWriteCount;
    }

    /**
     * I4 (renaming on): a writeToBuffer of {@code [offset, offset + length)} was staged into this buffer by a COPY_BUFFER
     * tagged {@code seq} from staging memory at {@code stagingAddress}. Keeps it for replay by {@link #renameForWrite} and
     * returns true; returns false (the caller then marks a GPU write, which refuses renaming until it completed) when the
     * buffer can never be renamed or the list is full. Drops entries whose copy completed. Render thread.
     */
    final boolean recordStagedWrite(final long seq, final long offset, final long length, final long stagingAddress, final long completedSeq) {
        if (this.size() > RENAME_MAX_BYTES || this.resourceOptions < 0 || this.backingOffset != 0L) {
            return false;
        }
        this.pruneStagedWrites(completedSeq);
        long[] w = this.stagedWrites;
        if (w == null) {
            w = this.stagedWrites = new long[RENAME_MAX_STAGED * 4];
        }
        if (this.stagedWriteCount == RENAME_MAX_STAGED) {
            return false;
        }
        int p = this.stagedWriteCount++ * 4;
        w[p] = seq;
        w[p + 1] = offset;
        w[p + 2] = length;
        w[p + 3] = stagingAddress;
        return true;
    }

    /** Drops the staged writes whose copy completed (their bytes are in the current backing). Entries are in seq order. */
    private void pruneStagedWrites(final long completedSeq) {
        long[] w = this.stagedWrites;
        int n = this.stagedWriteCount;
        int drop = 0;
        while (drop < n && w[drop * 4] <= completedSeq) {
            drop++;
        }
        if (drop > 0) {
            System.arraycopy(w, drop * 4, w, 0, (n - drop) * 4);
            this.stagedWriteCount = n - drop;
        }
    }

    /**
     * Buffer renaming for {@code writeToBuffer} (CONTRACT.md 16.R5-C; GL orphaning / D3D WRITE_DISCARD). The caller (I4)
     * found that recorded or in-flight work still references the current backing ({@code lastUseSeq() > completedSeq})
     * and is about to write {@code [offset, offset + length)}. Instead of a staged COPY_BUFFER (which the GPU orders
     * after every earlier reader of the buffer -- the cross-frame serialization of TAIL-ANALYSIS S3), this buffer moves
     * to an idle backing: one of its own retired backings whose last use completed, or a new one. The bytes outside the
     * written range are copied from the current backing on the CPU; the caller then writes the new bytes into
     * {@link #contents()} + offset. The old backing is retired: never written again, reusable once {@code
     * lastUseSeq()} (its last reader) completed.
     *
     * <p>Why it is exact: every stream record captured {@code handle()} (+ {@code backingOffset()}) at record time
     * (MetalCommandEncoder / MetalRenderPass write the long into the off-heap record; nothing caches it), so work
     * recorded before this call reads the old backing, whose bytes are frozen at their pre-write value, and work recorded
     * after it reads the new backing. The CPU copy of the old bytes plus the replay of the staged writes still pending
     * ({@link #recordStagedWrite}, from their staging memory, in stream order) equals the stream-order contents when no
     * other GPU write into the buffer is pending ({@link #gpuWriteSeq} completed) -- otherwise this returns
     * {@link #RENAME_NO_GPU_WRITE} and the caller stages as before. An open mapping would keep writing into the old
     * backing: refused. Render thread.
     *
     * @return {@link #RENAME_OK} when renamed, otherwise the reason (nothing changed)
     */
    final int renameForWrite(final long offset, final long length, final long completedSeq) {
        long size = this.size();
        if (size > RENAME_MAX_BYTES) {
            return RENAME_NO_SIZE;
        }
        if (this.resourceOptions < 0 || this.backingOffset != 0L || this.closed || this.texelViewCount != 0
            || (this.usage() & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) != 0) {
            return RENAME_NO_KIND;
        }
        if (this.mappingCount != 0) {
            return RENAME_NO_MAPPED;
        }
        if (this.gpuWriteSeq > completedSeq) {
            return RENAME_NO_GPU_WRITE;
        }
        long[] s = this.spares;
        int pick = -1;
        for (int i = 0; i < this.spareCount; i++) {
            if (s[i * 4 + 3] <= completedSeq) {
                pick = i;
                break;
            }
        }
        if (pick < 0) {
            if (this.spareCount >= RENAME_MAX_SPARES) {
                return RENAME_NO_SPARE;
            }
            if (s == null || (this.spareCount + 1) * 4 > s.length) {
                s = this.spares = s == null ? new long[4 * 4] : java.util.Arrays.copyOf(s, s.length * 2);
            }
            if (!this.createSpare(s, this.spareCount)) {
                return RENAME_NO_SPARE;
            }
            pick = this.spareCount++;
        }
        int p = pick * 4;
        long newHandle = s[p];
        long newContents = s[p + 1];
        long newGpuAddress = s[p + 2];
        long oldContents = this.contents;
        // Bytes outside the written range: the buffer's current (stream-order) contents.
        if (offset > 0L) {
            MemoryUtil.memCopy(oldContents, newContents, offset);
        }
        long end = offset + length;
        if (end < size) {
            MemoryUtil.memCopy(oldContents + end, newContents + end, size - end);
        }
        // Staged writes whose COPY_BUFFER (recorded against the old backing) has not run yet: replayed from their staging
        // memory in stream order (the caller's write, later in the stream, then overwrites its range).
        this.pruneStagedWrites(completedSeq);
        long[] w = this.stagedWrites;
        for (int i = 0; i < this.stagedWriteCount; i++) {
            int q = i * 4;
            MemoryUtil.memCopy(w[q + 3], newContents + w[q + 1], w[q + 2]);
        }
        this.stagedWriteCount = 0;
        // The old backing takes the picked slot: frozen until its last reader completed.
        s[p] = this.handle;
        s[p + 1] = oldContents;
        s[p + 2] = this.gpuAddress;
        s[p + 3] = this.lastUseSeq;
        this.handle = newHandle;
        this.contents = newContents;
        this.gpuAddress = newGpuAddress;
        if (this.headlessMemory != 0L) {
            this.headlessMemory = newContents;
        }
        // Nothing recorded references the new backing yet.
        this.lastUseSeq = 0L;
        return RENAME_OK;
    }

    /** A new backing like the current one (same size, usage, resource options) into spare slot {@code slot}; false on failure. */
    private boolean createSpare(final long[] s, final int slot) {
        int p = slot * 4;
        long size = this.size();
        if (this.headlessMemory != 0L) {
            long memory = MemoryUtil.nmemAlignedAlloc(64L, alignUp(size, 16L));
            if (memory == 0L) {
                return false;
            }
            s[p] = Headless.nextHandle();
            s[p + 1] = memory;
            s[p + 2] = memory;
            s[p + 3] = 0L;
            return true;
        }
        if (this.device.handle() == 0L) {
            return false;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long desc = stack.ncalloc(8, 1, (int)MtlNative.BUFFER_DESC_SIZEOF);
            long out = stack.ncalloc(8, 1, (int)MtlNative.BUFFER_RESULT_SIZEOF);
            MemoryUtil.memPutInt(desc + MtlNative.BUFFER_DESC_STRUCT_SIZE, (int)MtlNative.BUFFER_DESC_SIZEOF);
            MemoryUtil.memPutInt(desc + MtlNative.BUFFER_DESC_USAGE, this.usage());
            MemoryUtil.memPutLong(desc + MtlNative.BUFFER_DESC_SIZE, size);
            MemoryUtil.memPutInt(desc + MtlNative.BUFFER_DESC_RESOURCE_OPTIONS, this.resourceOptions);
            Supplier<String> label = this.diagLabel;
            if (label != null) {
                MemoryUtil.memPutAddress(desc + MtlNative.BUFFER_DESC_LABEL, utf8Label(stack, label.get() + " (renamed)"));
            }
            MemoryUtil.memPutInt(out + MtlNative.BUFFER_RESULT_STRUCT_SIZE, (int)MtlNative.BUFFER_RESULT_SIZEOF);
            int status = MtlNative.bufferCreate(this.device.handle(), desc, out);
            if (status != MtlNative.OK) {
                LOGGER.warn("Buffer renaming: mcmtl_buffer_create({} bytes) failed ({}: {}); staging the write instead", size,
                    MtlNative.statusName(status), MtlNative.lastError());
                return false;
            }
            long handle = MemoryUtil.memGetLong(out + MtlNative.BUFFER_RESULT_HANDLE);
            long contents = MemoryUtil.memGetLong(out + MtlNative.BUFFER_RESULT_CONTENTS);
            if (handle == 0L || contents == 0L) {
                this.device.timeline().deferRelease(handle);
                return false;
            }
            s[p] = handle;
            s[p + 1] = contents;
            s[p + 2] = MemoryUtil.memGetLong(out + MtlNative.BUFFER_RESULT_GPU_ADDRESS);
            s[p + 3] = 0L;
            return true;
        }
    }

    /** Releases every retired backing (close): the FIFO tag (pendingSeq) is >= each one's last use. Render thread. */
    private void releaseSpares() {
        long[] s = this.spares;
        if (s == null) {
            return;
        }
        MetalTimeline timeline = this.device.timeline();
        boolean headless = this.headlessMemory != 0L;
        for (int i = 0; i < this.spareCount; i++) {
            timeline.deferRelease(s[i * 4]);
            if (headless) {
                long memory = s[i * 4 + 1];
                timeline.defer(() -> MemoryUtil.nmemAlignedFree(memory));
            }
        }
        this.spareCount = 0;
        this.spares = null;
        this.stagedWriteCount = 0;
        this.stagedWrites = null;
    }

    /** True for transient blocks from {@link MetalTransientMemory}. */
    public boolean isTransient() {
        return false;
    }

    /**
     * Texture-buffer view of the whole buffer for TEXEL_BUFFER uniforms (spec 3.7): created on first use per
     * pixel format with mcmtl_texel_view_create (the only native call setUniform can trigger; I3 MAY create the
     * R8Sint view eagerly in {@link #create} for USAGE_UNIFORM_TEXEL_BUFFER buffers), cached per (backing,
     * format), released together with the buffer. Render thread.
     *
     * <p>A closed (not yet released) buffer still gets a view, released again right away (deferred), so a
     * game that binds a buffer after closing it in the same frame keeps working like on Vulkan.
     *
     * @param pixelFormat   MTLPixelFormat (MetalRenderPipeline.texelPixelFormat[i])
     * @param bytesPerTexel GpuFormat.blockSize() of the uniform's format
     * @return the view handle to put in a SET_TEXTURE record (sampler 0)
     */
    public long texelView(final int pixelFormat, final int bytesPerTexel) {
        for (int i = 0; i < this.texelViewCount; i++) {
            if (this.texelViewFormats[i] == pixelFormat) {
                return this.texelViewHandles[i];
            }
        }
        long view = this.createTexelView(pixelFormat, bytesPerTexel);
        if (this.closed) {
            this.device.timeline().deferRelease(view);
            return view;
        }
        if (this.texelViewFormats == null) {
            this.texelViewFormats = new int[2];
            this.texelViewHandles = new long[2];
        } else if (this.texelViewCount == this.texelViewFormats.length) {
            this.texelViewFormats = java.util.Arrays.copyOf(this.texelViewFormats, this.texelViewCount * 2);
            this.texelViewHandles = java.util.Arrays.copyOf(this.texelViewHandles, this.texelViewCount * 2);
        }
        this.texelViewFormats[this.texelViewCount] = pixelFormat;
        this.texelViewHandles[this.texelViewCount] = view;
        this.texelViewCount++;
        return view;
    }

    private long createTexelView(final int pixelFormat, final int bytesPerTexel) {
        if (bytesPerTexel <= 0) {
            throw new IllegalArgumentException("bytesPerTexel must be positive, was " + bytesPerTexel);
        }
        if (this.device.handle() == 0L) {
            return Headless.nextHandle();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long desc = stack.ncalloc(8, 1, (int)MtlNative.TEXEL_VIEW_DESC_SIZEOF);
            long out = stack.ncalloc(8, 1, (int)MtlNative.OBJECT_RESULT_SIZEOF);
            MemoryUtil.memPutInt(desc + MtlNative.TEXEL_VIEW_DESC_STRUCT_SIZE, (int)MtlNative.TEXEL_VIEW_DESC_SIZEOF);
            MemoryUtil.memPutInt(desc + MtlNative.TEXEL_VIEW_DESC_PIXEL_FORMAT, pixelFormat);
            MemoryUtil.memPutInt(desc + MtlNative.TEXEL_VIEW_DESC_BYTES_PER_TEXEL, bytesPerTexel);
            MemoryUtil.memPutLong(desc + MtlNative.TEXEL_VIEW_DESC_BUFFER, this.handle);
            MemoryUtil.memPutLong(desc + MtlNative.TEXEL_VIEW_DESC_OFFSET, this.backingOffset);
            MemoryUtil.memPutLong(desc + MtlNative.TEXEL_VIEW_DESC_LENGTH, this.size());
            MemoryUtil.memPutInt(out + MtlNative.OBJECT_RESULT_STRUCT_SIZE, (int)MtlNative.OBJECT_RESULT_SIZEOF);
            MtlNative.check(
                MtlNative.texelViewCreate(this.device.handle(), desc, out),
                "mcmtl_texel_view_create(format " + pixelFormat + ", " + this.size() + " bytes)"
            );
            return MemoryUtil.memGetLong(out + MtlNative.OBJECT_RESULT_HANDLE);
        }
    }

    private void releaseTexelViews() {
        for (int i = 0; i < this.texelViewCount; i++) {
            this.device.timeline().deferRelease(this.texelViewHandles[i]);
            this.texelViewHandles[i] = 0L;
        }
        this.texelViewCount = 0;
    }

    /** Number of open mappings (tests). */
    final int mappingCount() {
        return this.mappingCount;
    }

    /** The whole-buffer slice, cached (GpuBufferSlice is an immutable record; the game slices whole buffers per bind). */
    @Override
    public GpuBufferSlice slice() {
        GpuBufferSlice whole = this.wholeSlice;
        if (whole == null) {
            whole = new GpuBufferSlice(this, 0L, this.size());
            this.wholeSlice = whole;
        }
        return whole;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    /** Idempotent; throws IllegalStateException while mapped; defers the native release (and texel views). */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (this.mappingCount != 0) {
            // Vulkan parity (closed flag set, then throw). The backing is released when the last mapping closes.
            throw new IllegalStateException("Attempt to close a mapped buffer");
        }
        this.releaseBacking();
    }

    /** Deferred release of the backing and its texel views (render thread, once). */
    private void releaseBacking() {
        if (this.released) {
            return;
        }
        this.released = true;
        this.releaseTexelViews();
        this.releaseSpares();
        MetalTimeline timeline = this.device.timeline();
        if (this.headlessMemory != 0L) {
            timeline.deferRelease(this.handle);
            long memory = this.headlessMemory;
            timeline.defer(() -> MemoryUtil.nmemAlignedFree(memory));
            return;
        }
        if (this.resourceOptions >= 0 && this.backingOffset == 0L && this.device.bufferPool().accepts(this.allocatedLength)) {
            long handle = this.handle;
            long contents = this.contents;
            long gpuAddress = this.gpuAddress;
            long length = this.allocatedLength;
            int options = this.resourceOptions;
            int usage = this.usage();
            long size = this.size();
            Pool pool = this.device.bufferPool();
            timeline.defer(() -> pool.put(handle, contents, gpuAddress, length, size, usage, options));
        } else {
            timeline.deferRelease(this.handle);
        }
    }

    private void unmap() {
        this.mappingCount--;
        if (this.mappingCount == 0 && this.closed) {
            this.releaseBacking();
        }
    }

    /**
     * Spec 2.8: checks (closed, MAP_READ/MAP_WRITE usage, range, length <= Integer.MAX_VALUE), then
     * {@code MemoryUtil.memByteBuffer(contents + offset, (int) length)}; onClose = idempotent refcount
     * decrement. Never waits for the GPU. May be called inside render passes.
     */
    @Override
    public GpuBufferSlice.MappedView map(final long offset, final long length, final boolean read, final boolean write) {
        if (MetalFrameThrottle.ENABLED) {
            // Late throttle (CONTRACT.md 16.R5-A): a mapping sees memory as after the frame wait, as before.
            this.device.frameThrottle().ensureThrottled();
        }
        if (this.closed) {
            throw new IllegalStateException("Buffer already closed");
        }
        if (!read && !write) {
            throw new IllegalArgumentException("At least read or write must be true");
        }
        if (read && (this.usage() & GpuBuffer.USAGE_MAP_READ) == 0) {
            throw new IllegalStateException("Buffer is not readable");
        }
        if (write && (this.usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) {
            throw new IllegalStateException("Buffer is not writable");
        }
        if (offset < 0L || length < 0L) {
            throw new IllegalArgumentException("Offset or length must be positive integer values");
        }
        if (offset + length > this.size()) {
            throw new IllegalArgumentException(
                "Cannot map more data than this buffer can hold (attempting to map " + length + " bytes at offset " + offset + " from " + this.size() + " size buffer)"
            );
        }
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Mapping buffer slice larger than 2GB is not supported");
        }
        ByteBuffer data = MemoryUtil.memByteBuffer(this.contents + offset, (int)length);
        this.mappingCount++;
        return new GpuBufferSlice.MappedView(new GpuBufferSlice(this, offset, length), data, new Unmapper(this));
    }

    /** Idempotent onClose of one mapping. */
    private static final class Unmapper implements Runnable {
        private @Nullable MetalBuffer buffer;

        private Unmapper(final MetalBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public void run() {
            MetalBuffer target = this.buffer;
            if (target != null) {
                this.buffer = null;
                target.unmap();
            }
        }
    }

    /** Synthetic handles of a headless ({@link MetalDevice#forTesting}) device: never passed to natives. */
    static final class Headless {
        private static final java.util.concurrent.atomic.AtomicLong NEXT = new java.util.concurrent.atomic.AtomicLong(0x7E57_0000_0000_0000L);

        private Headless() {
        }

        static long nextHandle() {
            return NEXT.getAndAdd(16L);
        }
    }

    /**
     * Recycled {@code MTLBuffer}s of closed buffers (spec 2.2 SHOULD), device-owned, render thread. A backing
     * enters the pool from a timeline action that runs once the GPU passed the close tag, so it is idle; it is
     * reused only for the same requested size and resource options (and the same texel rounding). Bounded:
     * at most {@link #MAX_ENTRY_BYTES} per buffer, {@link #MAX_TOTAL_BYTES} in total, and entries not reused
     * within {@link #MAX_IDLE_SEQS} timeline values are released (oldest first). Never used for labelled
     * buffers (a recycled backing would carry the old label).
     */
    static final class Pool {
        static final long MIN_ENTRY_BYTES = 4L * 1024L;
        static final long MAX_ENTRY_BYTES = 64L * 1024L * 1024L;
        static final long MAX_TOTAL_BYTES = 192L * 1024L * 1024L;
        static final long MAX_IDLE_SEQS = 8L;

        private final MetalDevice device;
        private final ArrayDeque<Backing> entries = new ArrayDeque<>();
        private long totalBytes;
        private boolean closed;
        private long hits;

        Pool(final MetalDevice device) {
            this.device = device;
        }

        record Backing(long handle, long contents, long gpuAddress, long length, long size, int texelRounding, int options, long since) {
        }

        boolean accepts(final long allocatedLength) {
            return !this.closed && !this.device.debugLabels() && !DIAG_LABEL_BUFFERS && allocatedLength >= MIN_ENTRY_BYTES
                && allocatedLength <= MAX_ENTRY_BYTES;
        }

        private static int texelRounding(final int usage) {
            return (usage & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) != 0 ? 1 : 0;
        }

        /** An idle backing for (size, usage class, options), or null. Most recently returned first. */
        @Nullable Backing take(final long size, final int usage, final int options) {
            if (this.entries.isEmpty()) {
                return null;
            }
            this.evictIdle();
            int rounding = texelRounding(usage);
            var iterator = this.entries.descendingIterator();
            while (iterator.hasNext()) {
                Backing backing = iterator.next();
                if (backing.size == size && backing.options == options && backing.texelRounding == rounding) {
                    iterator.remove();
                    this.totalBytes -= backing.length;
                    this.hits++;
                    return backing;
                }
            }
            return null;
        }

        void put(final long handle, final long contents, final long gpuAddress, final long length, final long size, final int usage, final int options) {
            MetalTimeline timeline = this.device.timeline();
            if (this.closed || timeline.isClosed()) {
                timeline.deferRelease(handle);
                return;
            }
            this.entries.addLast(new Backing(handle, contents, gpuAddress, length, size, texelRounding(usage), options, timeline.pendingSeq()));
            this.totalBytes += length;
            while (this.totalBytes > MAX_TOTAL_BYTES && !this.entries.isEmpty()) {
                this.release(this.entries.removeFirst());
            }
            this.evictIdle();
        }

        private void evictIdle() {
            long limit = this.device.timeline().pendingSeq() - MAX_IDLE_SEQS;
            while (!this.entries.isEmpty() && this.entries.peekFirst().since < limit) {
                this.release(this.entries.removeFirst());
            }
        }

        /**
         * A pooled backing entered the pool from a deferred-FIFO action, i.e. after the GPU passed its last
         * use: the pool is the tail end of the FIFO, so it is released directly (no second delay).
         */
        private void release(final Backing backing) {
            this.totalBytes -= backing.length;
            if (this.device.timeline().isClosed() || this.device.handle() == 0L) {
                return;
            }
            int status = MtlNative.release(this.device.handle(), backing.handle);
            if (status != MtlNative.OK) {
                LOGGER.error("mcmtl_release of a pooled buffer failed: {}: {}", MtlNative.statusName(status), MtlNative.lastError());
            }
        }

        /** Releases every idle backing; true if anything was released (OOM retry). */
        boolean trim() {
            boolean any = !this.entries.isEmpty();
            while (!this.entries.isEmpty()) {
                this.release(this.entries.removeFirst());
            }
            return any;
        }

        int size() {
            return this.entries.size();
        }

        long totalBytes() {
            return this.totalBytes;
        }

        long hits() {
            return this.hits;
        }

        /** Device shutdown (GPU idle): releases everything and refuses later returns. */
        void close() {
            while (!this.entries.isEmpty()) {
                this.release(this.entries.removeFirst());
            }
            this.closed = true;
        }
    }
}
