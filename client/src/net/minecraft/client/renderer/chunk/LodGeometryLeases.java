package net.minecraft.client.renderer.chunk;

import java.util.Objects;

/**
 * Render-thread retirement policy, with no GPU dependencies. A lease captures the next retirement epoch:
 * retirements at or after that epoch cannot recycle until the lease closes. Earlier retirements are not
 * pinned by a later snapshot. Protection is deliberately global, including allocations made after capture.
 *
 * Epochs are owner-local diagnostics, never release tokens or a way to acquire a historical snapshot.
 * They saturate at Long.MAX_VALUE instead of wrapping: at saturation later leases conservatively pin
 * earlier retirements in that same epoch. There are at most three leases (the far-image slot limit).
 * The fixed registry is allocated once; retirement/recycle checks allocate nothing, including with no leases.
 */
final class LodGeometryLeases implements AutoCloseable {
    static final int MAX_LEASES = 3;

    private final Runnable assertOwnerThread;
    private final Runnable dispose;
    private final Lease[] leases = new Lease[MAX_LEASES];
    // Individually published diagnostics; callers needing a coherent snapshot read on the render thread.
    private volatile long epoch;
    private volatile long oldestEpoch;
    private volatile int count;
    private volatile boolean closed;
    private volatile boolean disposed;

    LodGeometryLeases(Runnable assertOwnerThread, Runnable dispose) {
        this(assertOwnerThread, dispose, 1L);
    }

    /** Nonzero starting epoch also allows CPU tests to reach saturation without billions of retirements. */
    LodGeometryLeases(Runnable assertOwnerThread, Runnable dispose, long initialEpoch) {
        if (initialEpoch <= 0L) throw new IllegalArgumentException("Invalid retirement epoch");
        this.assertOwnerThread = Objects.requireNonNull(assertOwnerThread);
        this.dispose = Objects.requireNonNull(dispose);
        this.epoch = initialEpoch;
    }

    /** Null means closed or all three image leases are occupied; skip the offer without waiting. */
    Lease acquire() {
        this.assertOwnerThread.run();
        if (this.closed || this.count == MAX_LEASES) return null;
        for (int i = 0; i < this.leases.length; i++) {
            if (this.leases[i] == null) {
                Lease lease = new Lease(this, i, this.epoch);
                this.leases[i] = lease;
                if (this.count == 0) this.oldestEpoch = lease.epoch;
                this.count++;
                return lease;
            }
        }
        throw new IllegalStateException("Inconsistent geometry lease count");
    }

    /** Stamp one retirement batch, before it can be observed by a subsequent snapshot acquisition. */
    long retire() {
        this.assertOwnerThread.run();
        if (this.closed) throw new IllegalStateException("Geometry owner is closed");
        long retiredEpoch = this.epoch;
        if (this.epoch != Long.MAX_VALUE) this.epoch++;
        return retiredEpoch;
    }

    /**
     * Keep the ordinary strict age boundary as well as lease protection. Modular subtraction tolerates
     * the frame counter crossing Long.MAX_VALUE for elapsed ages below 2^63; negative/ambiguous ages
     * fail closed. Invalid epochs and a closed owner must never return storage to the live allocator.
     */
    boolean canRecycle(long retiredEpoch, long retiredFrame, long frame, int graceFrames) {
        return !this.closed && graceFrames >= 0 && retiredEpoch > 0L && retiredEpoch <= this.epoch
            && frame - retiredFrame > graceFrames
            && (this.count == 0 || retiredEpoch < this.oldestEpoch);
    }

    long epoch() { return this.epoch; }
    long oldestEpoch() { return this.oldestEpoch; } // Zero means no leases.
    int count() { return this.count; }
    boolean closed() { return this.closed; }
    boolean disposed() { return this.disposed; }

    /** Release by identity, never by epoch. Check ownership even for an already closed foreign token. */
    void release(Lease lease) {
        this.assertOwnerThread.run();
        if (lease == null || lease.owner != this) throw new IllegalArgumentException("Foreign geometry lease");
        if (lease.closed) return;
        if (this.leases[lease.slot] != lease) throw new IllegalStateException("Stale geometry lease");
        this.leases[lease.slot] = null;
        lease.closed = true;
        this.count--;
        long oldest = 0L;
        for (int i = 0; i < this.leases.length; i++) {
            Lease live = this.leases[i];
            if (live != null && (oldest == 0L || live.epoch < oldest)) oldest = live.epoch;
        }
        this.oldestEpoch = oldest;
        this.disposeIfDrained();
    }

    /** Revoke acquisition and recycling immediately; dispose on this thread after the last release. */
    @Override public void close() {
        this.assertOwnerThread.run();
        this.closed = true;
        this.disposeIfDrained();
    }

    private void disposeIfDrained() {
        if (this.closed && this.count == 0 && !this.disposed) {
            // Claim before invoking external cleanup, including reentrant close or a cleanup exception.
            this.disposed = true;
            this.dispose.run();
        }
    }

    /** Keep alive through the producer AND every consumer of stored visibility IDs; close on render thread. */
    static final class Lease implements AutoCloseable {
        private final LodGeometryLeases owner;
        private final int slot;
        private final long epoch;
        private boolean closed;

        private Lease(LodGeometryLeases owner, int slot, long epoch) {
            this.owner = owner;
            this.slot = slot;
            this.epoch = epoch;
        }

        long epoch() { return this.epoch; }
        boolean isClosed() { return this.closed; }

        @Override public void close() {
            this.owner.release(this);
        }
    }
}
