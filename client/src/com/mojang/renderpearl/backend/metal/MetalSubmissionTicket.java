package com.mojang.renderpearl.backend.metal;

/**
 * Read-only submission dependency for a worker, captured by the render thread. Identity and sequence
 * never change; the encoder publishes exactly one terminal state with a volatile write. Reading
 * {@link #state()} as {@link State#COMMITTED} acquires the encoder's completed commit bookkeeping.
 *
 * <p>COMMITTED means native submit returned OK with COMMITTED set and the Java timeline advanced. It
 * does not mean GPU completion or successful GPU execution. A far worker must still encode a GPU wait
 * for {@link #sequence()} on the retained main-queue event belonging to {@link #device()}. The device
 * reference is identity only: this ticket retains no native event or resources and offers no CPU wait,
 * callback, submission or encoding API. Resource/event lifetime and GPU failure handling belong to
 * the consumer.
 */
public final class MetalSubmissionTicket {
    public enum State {
        PENDING,
        COMMITTED,
        FAILED
    }

    private final MetalDevice device;
    private final long sequence;
    private volatile State state = State.PENDING;

    MetalSubmissionTicket(final MetalDevice device, final long sequence) {
        this.device = device;
        this.sequence = sequence;
    }

    /** Device identity, including across device recreation; does not retain native ownership. */
    public MetalDevice device() {
        return this.device;
    }

    public long sequence() {
        return this.sequence;
    }

    /** Any thread. Terminal states never change, including when a dropped sequence is retried. */
    public State state() {
        return this.state;
    }

    /** Encoder/render thread only; no callbacks or work may run during publication. */
    void finish(final boolean committed) {
        if (this.state == State.PENDING) {
            this.state = committed ? State.COMMITTED : State.FAILED;
        }
    }
}
