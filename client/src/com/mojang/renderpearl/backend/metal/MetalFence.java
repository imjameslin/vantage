package com.mojang.renderpearl.backend.metal;

import com.mojang.renderpearl.api.commands.GpuFence;

/**
 * {@link GpuFence}: a captured timeline value (spec 1.5, 2.12). OWNER: I3 (java-device). Created by
 * {@code MetalCommandEncoder.createFence()} as {@code new MetalFence(device, timeline.pendingSeq())}.
 *
 * <p>{@code awaitCompletion(timeoutNs)} (timeouts in nanoseconds, -1 = forever):
 * <ol>
 *   <li>closed or {@code seq <= completedSeq()} -> true (also drains the timeline opportunistically);</li>
 *   <li>{@code seq == pendingSeq()}: timeout 0 -> false; otherwise the flush extension:
 *       {@link MetalCommandEncoder#flushForWait(long)} (throws IllegalStateException if a render pass is
 *       open), then wait as below;</li>
 *   <li>otherwise timeout 0 -> false, else {@link MetalTimeline#await(long, long)}.</li>
 * </ol>
 * {@code close()} marks the fence complete (no native work). Plain Java object; ~5-10 per frame.
 *
 * <p>The poll ({@code timeout 0}) is a field compare plus one acquire load of the status page; only a fence
 * that turned complete drains the FIFO. Blocking waits also surface command-buffer errors
 * ({@link MetalTimeline#checkErrors()}, CONTRACT.md 5.6).
 */
public final class MetalFence implements GpuFence {
    private final MetalDevice device;
    private final long seq;
    /** Render thread. True once observed complete or closed. */
    private boolean completed;

    public MetalFence(final MetalDevice device, final long seq) {
        this.device = device;
        this.seq = seq;
    }

    /** The captured timeline value. */
    public long seq() {
        return this.seq;
    }

    public MetalDevice device() {
        return this.device;
    }

    @Override
    public boolean awaitCompletion(final long timeoutNS) {
        if (this.completed) {
            return true;
        }
        MetalTimeline timeline = this.device.timeline();
        if (this.seq <= timeline.completedSeq()) {
            this.completed = true;
            timeline.drain();
            return true;
        }
        if (timeoutNS == 0L) {
            return false;
        }
        if (this.seq >= timeline.pendingSeq()) {
            // Flush-on-wait (spec 1.5 extension, CONTRACT.md 5.4): commit everything recorded so far so that
            // this seq gets signalled; throws IllegalStateException while a render pass is open.
            this.device.encoder().flushForWait(this.seq);
        }
        boolean done = timeline.await(this.seq, timeoutNS);
        timeline.checkErrors();
        if (done) {
            this.completed = true;
            timeline.drain();
        }
        return done;
    }

    /** True once the fence was observed complete or closed (no timeline access). */
    public boolean isCompleted() {
        return this.completed;
    }

    @Override
    public void close() {
        this.completed = true;
    }
}
