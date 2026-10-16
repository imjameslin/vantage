package com.mojang.renderpearl.backend.metal;

import com.mojang.renderpearl.api.device.GpuDeviceLossException;
import com.mojang.renderpearl.api.device.GpuOutOfMemoryException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * I4 TEST DOUBLE of I3's MetalTimeline (test mode only, CONTRACT.md 9.2 / 11.3). Same public API as the
 * skeleton; used by tools/test-i4-encoder.sh only while client/src/.../MetalTimeline.java is still the
 * I3 skeleton. Never compiled into client.jar.
 */
public final class MetalTimeline {
    public static final int FRAMES_IN_FLIGHT = 2;
    public static final long FRAME_WAIT_TIMEOUT_NS = 5_000_000_000L;
    public static final long FIRST_SEQ = 1L;

    private final long deviceHandle;
    private final MemorySegment statusPage;
    private long pendingSeq = FIRST_SEQ;
    private long awaitedSeq;
    private int seenErrorCount;
    private boolean closed;
    private final ArrayDeque<Entry> fifo = new ArrayDeque<>();
    /** Native releases collected by drains (test mode: dropped, kept inspectable). */
    final List<Long> releasedHandles = new ArrayList<>();
    /** Every onCommitted value, in order (test inspection). */
    final List<Long> committed = new ArrayList<>();

    private record Entry(long seq, Runnable action, long handle) {
    }

    public MetalTimeline(final long deviceHandle, final MemorySegment statusPage) {
        this.deviceHandle = deviceHandle;
        this.statusPage = statusPage;
    }

    public long pendingSeq() {
        return this.pendingSeq;
    }

    public long lastCommittedSeq() {
        return this.pendingSeq - 1L;
    }

    public long completedSeq() {
        if (this.closed) {
            return Long.MAX_VALUE;
        }
        return Math.max(MtlNative.statusLong(this.statusPage, MtlNative.STATUS_PAGE_COMPLETED_VALUE), this.awaitedSeq);
    }

    public boolean isComplete(final long seq) {
        return seq <= this.completedSeq();
    }

    public void onCommitted(final long seq) {
        if (seq != this.pendingSeq) {
            throw new IllegalStateException("onCommitted(" + seq + ") but pendingSeq is " + this.pendingSeq);
        }
        this.committed.add(seq);
        this.pendingSeq = seq + 1L;
    }

    public boolean await(final long seq, final long timeoutNs) {
        if (this.closed || seq <= this.completedSeq()) {
            return true;
        }
        if (seq >= this.pendingSeq) {
            throw new IllegalStateException("Cannot wait for uncommitted seq " + seq + " (pending " + this.pendingSeq + ")");
        }
        long start = System.nanoTime();
        while (true) {
            if (MtlNative.statusInt(this.statusPage, MtlNative.STATUS_PAGE_DEVICE_LOST) != 0) {
                throw new GpuDeviceLossException("device lost while waiting for " + seq);
            }
            if (MtlNative.statusLong(this.statusPage, MtlNative.STATUS_PAGE_COMPLETED_VALUE) >= seq) {
                this.awaitedSeq = Math.max(this.awaitedSeq, seq);
                return true;
            }
            if (timeoutNs == 0L || (timeoutNs > 0L && System.nanoTime() - start >= timeoutNs)) {
                return false;
            }
            LockSupport.parkNanos(100_000L);
        }
    }

    public void checkErrors() {
        if (this.closed) {
            return;
        }
        int errors = MtlNative.statusInt(this.statusPage, MtlNative.STATUS_PAGE_ERROR_COUNT);
        boolean lost = MtlNative.statusInt(this.statusPage, MtlNative.STATUS_PAGE_DEVICE_LOST) != 0;
        if (errors != this.seenErrorCount) {
            this.seenErrorCount = errors;
            if (lost) {
                throw new GpuDeviceLossException("Metal device lost");
            }
            if (MtlNative.statusInt(this.statusPage, MtlNative.STATUS_PAGE_LAST_ERROR_CODE) == 8) {
                throw new GpuOutOfMemoryException("Metal command buffer ran out of memory");
            }
            throw new IllegalStateException("Metal command buffer failed");
        }
        if (lost) {
            throw new GpuDeviceLossException("Metal device lost");
        }
    }

    public void deferRelease(final long handle) {
        if (handle == 0L || this.closed) {
            return;
        }
        this.fifo.add(new Entry(this.pendingSeq, null, handle));
    }

    public void defer(final Runnable action) {
        this.deferUntil(this.pendingSeq, action);
    }

    public void deferUntil(final long seq, final Runnable action) {
        if (this.closed) {
            action.run();
            return;
        }
        this.fifo.add(new Entry(seq, action, 0L));
    }

    public void drain() {
        long completed = this.completedSeq();
        while (!this.fifo.isEmpty() && this.fifo.peekFirst().seq() <= completed) {
            this.run(this.fifo.pollFirst());
        }
    }

    public void drainAll() {
        while (!this.fifo.isEmpty()) {
            this.run(this.fifo.pollFirst());
        }
    }

    private void run(final Entry entry) {
        if (entry.action() != null) {
            entry.action().run();
        } else {
            this.releasedHandles.add(entry.handle());
        }
    }

    /** Test inspection: entries still waiting. */
    int pendingEntries() {
        return this.fifo.size();
    }

    public void close() {
        this.closed = true;
    }

    public boolean isClosed() {
        return this.closed;
    }
}
