package com.mojang.renderpearl.backend.metal;

import org.lwjgl.system.MemoryUtil;

/**
 * Growable off-heap buffer of command-stream records (mcmetal_stream.h). OWNER: I4 (java-encoder).
 *
 * <p>Contract (CONTRACT.md 4.1): 16-byte aligned base address ({@code MemoryUtil.nmemAlignedAlloc(16, ...)}),
 * records appended at 16-byte aligned positions, every reserved byte zeroed (padding must be 0), growth by
 * reallocation (records hold no absolute addresses into the stream, so moving is safe). The native decoder
 * reads the bytes synchronously inside {@code mcmtl_submit}; after that call returns the writer may be reset
 * and reused immediately. Not thread-safe (render thread).
 *
 * <p>Zeroing strategy: the allocation is kept all-zero beyond {@link #size()} at all times (zeroed at
 * allocation and growth, and the used prefix is zeroed again by {@link #reset()}), so {@link #reserve} hands
 * out pre-zeroed memory without touching it: one bounds check and one add per record, no allocation.
 *
 * <p>Typical record write:
 * <pre>{@code
 * long r = writer.reserve(StreamOps.SIZE_DRAW);
 * MemoryUtil.memPutLong(r, StreamOps.header(StreamOps.OP_DRAW, StreamOps.SIZE_DRAW, vertexCount));
 * MemoryUtil.memPutInt(r + StreamOps.DRAW_INSTANCE_COUNT, instanceCount);
 * ...
 * }</pre>
 */
public final class StreamWriter implements AutoCloseable {
    /** Default initial capacity (grows by doubling). */
    public static final int DEFAULT_CAPACITY = 256 * 1024;
    /** Smallest allocation (a few records). */
    private static final long MIN_CAPACITY = 1024L;

    private final String name;
    /** 16-byte aligned base address; 0 once closed. */
    private long base;
    private long capacity;
    /** Bytes recorded; always a multiple of 16. */
    private long position;
    private boolean closed;

    public StreamWriter(final String name, final int initialCapacity) {
        this.name = name;
        long cap = Math.max(MIN_CAPACITY, ((long)initialCapacity + 15L) & ~15L);
        this.base = allocateZeroed(cap);
        this.capacity = cap;
    }

    public String name() {
        return this.name;
    }

    /**
     * Reserves {@code size} bytes (multiple of 16, 16..65520) at the end of the stream and returns their
     * address, zero-filled. The address is valid until the next {@code reserve} (which may grow and move the
     * buffer) or {@link #reset()}.
     */
    public long reserve(final int size) {
        if ((size & 15) != 0 || size < StreamOps.STREAM_ALIGNMENT || size > StreamOps.STREAM_MAX_RECORD) {
            throw new IllegalArgumentException("Invalid record size " + size + " for stream " + this.name);
        }
        long pos = this.position;
        long end = pos + size;
        if (end > this.capacity) {
            this.grow(end);
        }
        this.position = end;
        return this.base + pos;
    }

    /** Base address of the recorded bytes (pass to mcmtl_submit_info.stream / initStream). */
    public long address() {
        return this.base;
    }

    /** Address of byte {@code offset} of the recorded stream ({@code offset < size()}); valid until the next reserve. */
    public long addressAt(final long offset) {
        if (offset < 0L || offset >= this.position) {
            throw new IndexOutOfBoundsException("Offset " + offset + " outside stream " + this.name + " of " + this.position + " bytes");
        }
        return this.base + offset;
    }

    /** Number of bytes recorded (a multiple of 16). */
    public long size() {
        return this.position;
    }

    /** Allocated bytes (diagnostics and tests). */
    public long capacity() {
        return this.capacity;
    }

    public boolean isEmpty() {
        return this.position == 0L;
    }

    /** Forgets every record (keeps the allocation, zeroes the bytes that were used). */
    public void reset() {
        if (this.position != 0L) {
            MemoryUtil.memSet(this.base, 0, this.position);
            this.position = 0L;
        }
    }

    public boolean isClosed() {
        return this.closed;
    }

    /** Frees the native memory. Idempotent; a later {@link #reserve} throws IllegalStateException. */
    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            MemoryUtil.nmemAlignedFree(this.base);
            this.base = 0L;
            this.capacity = 0L;
            this.position = 0L;
        }
    }

    private void grow(final long required) {
        if (this.closed) {
            throw new IllegalStateException("Stream " + this.name + " is closed");
        }
        long newCapacity = Math.max(this.capacity, MIN_CAPACITY);
        while (newCapacity < required) {
            newCapacity <<= 1;
        }
        long newBase = allocateZeroed(newCapacity);
        if (this.position != 0L) {
            MemoryUtil.memCopy(this.base, newBase, this.position);
        }
        MemoryUtil.nmemAlignedFree(this.base);
        this.base = newBase;
        this.capacity = newCapacity;
    }

    private long allocateZeroed(final long bytes) {
        long address = MemoryUtil.nmemAlignedAlloc(StreamOps.STREAM_ALIGNMENT, bytes);
        if (address == 0L) {
            throw new OutOfMemoryError("Cannot allocate " + bytes + " bytes for command stream " + this.name);
        }
        MemoryUtil.memSet(address, 0, bytes);
        return address;
    }
}
