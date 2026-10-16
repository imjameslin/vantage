package com.mojang.renderpearl.backend.metal;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.device.GpuDeviceLossException;
import com.mojang.renderpearl.api.device.GpuOutOfMemoryException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * Timeline, frame throttling and deferred-release FIFO (spec 1.5, CONTRACT.md 5). OWNER: I3 (java-device).
 *
 * <p>Model: the native {@code MTLSharedEvent} timeline is signaled with dense values 1, 2, 3, ... -- every
 * {@code mcmtl_submit} with {@code SUBMIT_FLAG_COMMIT} signals exactly {@link #pendingSeq()}, after which
 * the encoder calls {@link #onCommitted(long)}. {@link #completedSeq()} is the native contiguous-prefix
 * completion value read from the status page (acquire load, no downcall). A {@link MetalFence} is just a
 * captured {@code pendingSeq}.
 *
 * <p>Deferred FIFO: entries tagged with a seq (normally {@code pendingSeq()} at insertion: anything closed
 * while value v is pending may still be referenced by work that v's commit submits). Tags are
 * non-decreasing in insertion order. {@link #drain()} runs, in FIFO order on the render thread, every
 * entry whose tag is {@code <= completedSeq()}; native releases collected in one drain go out in a single
 * {@code mcmtl_release_batch} after that drain's callbacks ran.
 *
 * <p>Threading: render thread only, except {@link #completedSeq()} / {@link #isComplete(long)} (any thread).
 *
 * <p>Test mode (CONTRACT.md 11.3): with {@code deviceHandle == 0} the timeline makes no downcalls at all.
 * The status page is then ordinary memory the test owns (e.g. {@code MemorySegment.ofArray(new long[8])},
 * 8-byte aligned) and advances by writing {@code STATUS_PAGE_COMPLETED_VALUE} etc.; {@link #await} polls
 * the page (with its timeout) instead of {@code mcmtl_timeline_wait}; native releases collected by a drain
 * are dropped instead of {@code mcmtl_release_batch} (kept inspectable: {@link #testReleasedHandles()}).
 *
 * <p>Closed state ({@link #close()}, CONTRACT.md 5.3/5.7): after {@code mcmtl_device_destroy} the status page
 * is freed, so the timeline never reads it again: {@link #completedSeq()} returns {@code Long.MAX_VALUE},
 * waits return true immediately, {@link #deferRelease} drops the handle (leak; the device is gone),
 * {@link #defer}/{@link #deferUntil} run the action immediately, {@link #checkErrors()} does nothing.
 *
 * <p>Implementation: the FIFO is a growable ring of parallel arrays (tag, handle, action), so deferring a
 * native release allocates nothing in steady state; the release batch is an off-heap {@code mcmtl_handle}
 * array reused across drains.
 */
public final class MetalTimeline {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Frames in flight (spec 1.5; 3 needs game patch G5). */
    public static final int FRAMES_IN_FLIGHT = 2;
    /** Cap of the frame-throttle wait in submit() before IllegalStateException (Vulkan parity). */
    public static final long FRAME_WAIT_TIMEOUT_NS = 5_000_000_000L;
    /** First value ever signaled. */
    public static final long FIRST_SEQ = 1L;

    /** Capacity of the buffer used to fetch the native debug-message ring (64 messages x <= 1 KiB). */
    static final int MESSAGE_BUFFER_BYTES = MtlNative.MAX_DEBUG_MESSAGES * 1024 + 1;
    /** MTLCommandBufferErrorOutOfMemory (CONTRACT.md 5.3). */
    private static final int COMMAND_BUFFER_ERROR_OUT_OF_MEMORY = MetalFormats.COMMAND_BUFFER_ERROR_OUT_OF_MEMORY;
    private static final int INITIAL_FIFO_CAPACITY = 256;
    private static final long TEST_POLL_NS = 50_000L;

    private final long deviceHandle;
    private final MemorySegment statusPage;
    private final boolean testMode;

    /** Render thread. */
    private long pendingSeq = FIRST_SEQ;
    /** Largest seq a successful native wait returned for (any thread reads). */
    private volatile long awaitedSeq;
    private volatile boolean closed;

    // Deferred FIFO: ring of parallel arrays, [head, head + size) valid (render thread).
    private long[] tags = new long[INITIAL_FIFO_CAPACITY];
    private long[] handles = new long[INITIAL_FIFO_CAPACITY];
    private Runnable[] actions = new Runnable[INITIAL_FIFO_CAPACITY];
    private int head;
    private int size;
    private long lastTag;
    private boolean draining;

    // Release batch (off-heap mcmtl_handle[]), reused across drains (render thread).
    private long batchAddress;
    private int batchCapacity;
    private int batchCount;

    // Error bookkeeping (checkErrors).
    private int lastErrorCount;
    private int lastMessageCount;

    /** Test mode only: handles a drain would have released. */
    private final List<Long> testReleased;
    /**
     * Test mode only (tools/LateThrottleTest.java): told the value a blocking wait is about to wait for, so a fake GPU can
     * complete values only when the CPU really waits for them (asynchronously, from its own thread). Null otherwise.
     */
    java.util.function.LongConsumer testWaitHook;

    /**
     * @param deviceHandle native device handle (mcmtl_timeline_* / mcmtl_release_batch); 0 = test mode
     * @param statusPage   MtlNative.statusPage(mcmtl_device_info.statusPage), or test memory in test mode
     */
    public MetalTimeline(final long deviceHandle, final MemorySegment statusPage) {
        this.deviceHandle = deviceHandle;
        this.statusPage = statusPage;
        this.testMode = deviceHandle == 0L;
        this.testReleased = this.testMode ? new ArrayList<>() : List.of();
        // lastErrorCount / lastMessageCount start at 0 (a fresh device's page): messages pushed while the device
        // was created (decoder init warnings) are logged by the first checkErrors().
    }

    /** Value the next COMMIT signals; also the tag of fences and deferred entries created now. */
    public long pendingSeq() {
        return this.pendingSeq;
    }

    /** Highest committed value ({@code pendingSeq() - 1}). */
    public long lastCommittedSeq() {
        return this.pendingSeq - 1L;
    }

    /**
     * Largest v such that all values <= v completed: {@code max(status page completedValue (acquire load),
     * the largest seq a successful await() returned for)} -- a successful wait means the GPU executed that
     * signal although the completion handler that advances the page may lag a few microseconds
     * (CONTRACT.md 5.1). {@code Long.MAX_VALUE} once closed. Any thread, ~1 ns.
     */
    public long completedSeq() {
        if (this.closed) {
            return Long.MAX_VALUE;
        }
        long page = MtlNative.statusLong(this.statusPage, MtlNative.STATUS_PAGE_COMPLETED_VALUE);
        long awaited = this.awaitedSeq;
        return Math.max(page, awaited);
    }

    /** {@code seq <= completedSeq()}. Any thread. */
    public boolean isComplete(final long seq) {
        return seq <= this.completedSeq();
    }

    /**
     * Called by {@link MetalCommandEncoder} right after a COMMIT that signaled {@code seq}; requires
     * {@code seq == pendingSeq()}, then pendingSeq becomes {@code seq + 1}.
     */
    public void onCommitted(final long seq) {
        if (seq != this.pendingSeq) {
            throw new IllegalStateException("Metal timeline diverged: committed seq " + seq + " but pendingSeq is " + this.pendingSeq);
        }
        this.pendingSeq = seq + 1L;
    }

    /**
     * Blocks until {@code seq} completed: true when complete, false on timeout. {@code timeoutNs}: 0 = poll,
     * {@code < 0} = forever (MtlNative.WAIT_FOREVER), otherwise nanoseconds (mcmtl_timeline_wait, which also
     * returns as soon as the GPU executed the signal; on success record {@code seq} so that
     * {@link #completedSeq()} reflects it immediately). Throws
     * {@link com.mojang.renderpearl.api.device.GpuDeviceLossException} on device loss and
     * {@link IllegalStateException} for an uncommitted seq ({@code >= pendingSeq()}); callers that may
     * hold the current seq go through {@link MetalCommandEncoder#flushForWait(long)} first.
     */
    public boolean await(final long seq, final long timeoutNs) {
        if (this.closed || seq <= this.completedSeq()) {
            return true;
        }
        if (seq >= this.pendingSeq) {
            throw new IllegalStateException("Cannot wait for timeline value " + seq + ": not committed yet (pendingSeq " + this.pendingSeq + ")");
        }
        if (timeoutNs == 0L) {
            return false;
        }
        if (this.testMode) {
            return this.awaitTestPage(seq, timeoutNs);
        }
        long traceStart = MetalFrameTrace.ENABLED ? System.nanoTime() : 0L;
        long spin = spinNs;
        if (spin > 0L && SpinExt.SPIN != null) {
            // CONTRACT.md 16.R8-1: busy-poll first (keeps the render thread runnable while the GPU finishes), then block.
            int spun;
            try {
                spun = (int)SpinExt.SPIN.invokeExact(this.deviceHandle, seq, timeoutNs < 0L ? spin : Math.min(spin, timeoutNs), spinMode);
            } catch (Throwable t) {
                spun = MtlNative.E_TIMEOUT;
            }
            if (spun == MtlNative.OK) {
                if (MetalFrameTrace.ENABLED) {
                    MetalFrameTrace.onWait(System.nanoTime() - traceStart);
                }
                this.recordAwaited(seq);
                return true;
            }
        }
        int status = MtlNative.timelineWait(this.deviceHandle, seq, timeoutNs < 0L ? MtlNative.WAIT_FOREVER : timeoutNs);
        if (MetalFrameTrace.ENABLED) {
            MetalFrameTrace.onWait(System.nanoTime() - traceStart); // CONTRACT.md 16.R5-B
        }
        if (status == MtlNative.OK) {
            this.recordAwaited(seq);
            return true;
        }
        if (status == MtlNative.E_TIMEOUT) {
            return false;
        }
        throw MtlNative.exceptionFor(status, "mcmtl_timeline_wait(" + seq + ")");
    }

    /**
     * Busy-poll budget of every blocking wait before it blocks (CONTRACT.md 16.R8-1, {@code -Dmcmetal.spinWaitUs}, default
     * 2000 us; 0 = block at once, the r7 wait). A blocked render thread sleeps ~80 % of a GPU-bound heavy frame, the
     * scheduler then runs it slower (its CPU time per frame doubles to quadruples in some laps), the next frame is committed
     * too late to overlap the GPU's current one, and the frame interval alternates long / short. Polling keeps the thread
     * runnable. Runtime toggle (abab): read once per wait, render thread.
     */
    public static volatile long spinNs = Long.getLong("mcmetal.spinWaitUs", 2000L) * 1000L;
    /**
     * Busy-poll instruction ({@code -Dmcmetal.spinWaitMode}): 1 = wfe (default: the core idles in low power until the next
     * event-stream tick), 0 = yield. Runtime toggle.
     */
    public static volatile int spinMode = Integer.getInteger("mcmetal.spinWaitMode", 1);

    /** Optional extension export {@code mcmtl_ext_timeline_spin} (native/src/mcmetal_common.m); null when missing. */
    private static final class SpinExt {
        static final java.lang.invoke.@org.jspecify.annotations.Nullable MethodHandle SPIN = bind();

        private static java.lang.invoke.@org.jspecify.annotations.Nullable MethodHandle bind() {
            try {
                return java.lang.foreign.Linker.nativeLinker().downcallHandle(
                    MemorySegment.ofAddress(MtlNative.Loader.address("mcmtl_ext_timeline_spin")),
                    java.lang.foreign.FunctionDescriptor.of(
                        java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_LONG,
                        java.lang.foreign.ValueLayout.JAVA_LONG, java.lang.foreign.ValueLayout.JAVA_LONG, java.lang.foreign.ValueLayout.JAVA_INT
                    )
                );
            } catch (Throwable t) {
                LOGGER.warn("Metal timeline: mcmtl_ext_timeline_spin not available ({}); waits block", t.toString());
                return null;
            }
        }
    }

    private void recordAwaited(final long seq) {
        if (seq > this.awaitedSeq) {
            this.awaitedSeq = seq;
        }
    }

    /** Test mode: poll the test-owned page until it reaches {@code seq}, device loss or the timeout. */
    private boolean awaitTestPage(final long seq, final long timeoutNs) {
        long start = System.nanoTime();
        java.util.function.LongConsumer hook = this.testWaitHook;
        if (hook != null && MtlNative.statusLong(this.statusPage, MtlNative.STATUS_PAGE_COMPLETED_VALUE) < seq) {
            hook.accept(seq);
        }
        while (true) {
            if (MtlNative.statusLong(this.statusPage, MtlNative.STATUS_PAGE_COMPLETED_VALUE) >= seq) {
                return true;
            }
            if (MtlNative.statusInt(this.statusPage, MtlNative.STATUS_PAGE_DEVICE_LOST) != 0) {
                throw new GpuDeviceLossException("GPU device lost while waiting for timeline value " + seq);
            }
            if (timeoutNs > 0L && System.nanoTime() - start >= timeoutNs) {
                return false;
            }
            LockSupport.parkNanos(TEST_POLL_NS);
        }
    }

    /**
     * Reads the status page (spec 2.3 submit step 6). When {@code errorCount} grew since the last call:
     * remembers the new count first (each failure is reported once), logs the {@code mcmtl_device_copy_messages}
     * text, then throws: {@link com.mojang.renderpearl.api.device.GpuDeviceLossException} when deviceLost is
     * set, {@link com.mojang.renderpearl.api.device.GpuOutOfMemoryException} when lastErrorCode is
     * MTLCommandBufferErrorOutOfMemory (8), otherwise {@link IllegalStateException} with the message text.
     * A sticky deviceLost throws on every call. New messageCount alone (decoder warnings) is only logged.
     * Called by the encoder in submit() and by fence waits.
     */
    public void checkErrors() {
        if (this.closed) {
            return;
        }
        int errorCount = MtlNative.statusInt(this.statusPage, MtlNative.STATUS_PAGE_ERROR_COUNT);
        int messageCount = MtlNative.statusInt(this.statusPage, MtlNative.STATUS_PAGE_MESSAGE_COUNT);
        boolean deviceLost = MtlNative.statusInt(this.statusPage, MtlNative.STATUS_PAGE_DEVICE_LOST) != 0;
        boolean newErrors = errorCount != this.lastErrorCount;
        boolean newMessages = messageCount != this.lastMessageCount;
        if (!newErrors && !newMessages && !deviceLost) {
            return;
        }
        int addedMessages = messageCount - this.lastMessageCount;
        // Record first: every failure surfaces exactly once even if logging or the throw below fails.
        this.lastErrorCount = errorCount;
        this.lastMessageCount = messageCount;
        String text = "";
        if (newErrors || newMessages) {
            List<String> recent = this.copyMessages();
            int from = Math.max(0, recent.size() - Math.max(0, addedMessages));
            List<String> added = recent.subList(from, recent.size());
            for (String message : added) {
                if (newErrors) {
                    LOGGER.error("Metal: {}", message);
                } else {
                    LOGGER.warn("Metal: {}", message);
                }
            }
            text = String.join("; ", added);
        }
        if (deviceLost) {
            throw new GpuDeviceLossException(this.errorText("GPU device lost", text));
        }
        if (newErrors) {
            int code = MtlNative.statusInt(this.statusPage, MtlNative.STATUS_PAGE_LAST_ERROR_CODE);
            if (code == COMMAND_BUFFER_ERROR_OUT_OF_MEMORY) {
                throw new GpuOutOfMemoryException(this.errorText("Metal command buffer ran out of memory", text));
            }
            throw new IllegalStateException(this.errorText("Metal command buffer failed (MTLCommandBufferError " + code + ")", text));
        }
    }

    private String errorText(final String what, final String messages) {
        long value = MtlNative.statusLong(this.statusPage, MtlNative.STATUS_PAGE_LAST_ERROR_VALUE);
        return what + " at timeline value " + value + (messages.isEmpty() ? "" : ": " + messages);
    }

    /**
     * The native debug-message ring (oldest first), or an empty list in test mode / after close. Any thread
     * (mcmtl_device_copy_messages is thread-safe).
     */
    List<String> copyMessages() {
        if (this.testMode || this.closed) {
            return List.of();
        }
        long buffer = MemoryUtil.nmemAlloc(MESSAGE_BUFFER_BYTES);
        if (buffer == 0L) {
            return List.of();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long outLength = stack.ncalloc(4, 1, 4);
            int status = MtlNative.deviceCopyMessages(this.deviceHandle, buffer, MESSAGE_BUFFER_BYTES, outLength);
            if (status != MtlNative.OK) {
                LOGGER.warn("mcmtl_device_copy_messages failed: {}: {}", MtlNative.statusName(status), MtlNative.lastError());
                return List.of();
            }
            int length = MemoryUtil.memGetInt(outLength);
            if (length <= 0) {
                return List.of();
            }
            String joined = MemoryUtil.memUTF8(buffer, length);
            return List.of(joined.split("\n"));
        } finally {
            MemoryUtil.nmemFree(buffer);
        }
    }

    /** Releases native handle {@code handle} once the GPU passed {@link #pendingSeq()}. 0 is ignored. */
    public void deferRelease(final long handle) {
        if (handle == 0L || this.closed) {
            return;
        }
        this.enqueue(this.pendingSeq, handle, null);
    }

    /** Runs {@code action} on the render thread once the GPU passed {@link #pendingSeq()}. */
    public void defer(final Runnable action) {
        if (this.closed) {
            action.run();
            return;
        }
        this.enqueue(this.pendingSeq, 0L, action);
    }

    /**
     * Runs {@code action} once {@code seq} completed; {@code seq} must be >= every tag inserted so far (a smaller
     * value is raised to the last tag: running later is always safe).
     */
    public void deferUntil(final long seq, final Runnable action) {
        if (this.closed) {
            action.run();
            return;
        }
        this.enqueue(seq, 0L, action);
    }

    private void enqueue(final long seq, final long handle, final Runnable action) {
        long tag = Math.max(seq, this.lastTag);
        if (this.size == this.tags.length) {
            this.grow();
        }
        int index = (this.head + this.size) & (this.tags.length - 1);
        this.tags[index] = tag;
        this.handles[index] = handle;
        this.actions[index] = action;
        this.size++;
        this.lastTag = tag;
    }

    private void grow() {
        int capacity = this.tags.length;
        long[] newTags = new long[capacity * 2];
        long[] newHandles = new long[capacity * 2];
        Runnable[] newActions = new Runnable[capacity * 2];
        for (int i = 0; i < this.size; i++) {
            int from = (this.head + i) & (capacity - 1);
            newTags[i] = this.tags[from];
            newHandles[i] = this.handles[from];
            newActions[i] = this.actions[from];
        }
        this.tags = newTags;
        this.handles = newHandles;
        this.actions = newActions;
        this.head = 0;
    }

    /** Number of entries waiting in the FIFO (diagnostics, tests). */
    public int pendingEntries() {
        return this.size;
    }

    /** Executes every entry whose tag is complete, in FIFO order (render thread; submit() and fence polls). */
    public void drain() {
        if (this.closed || this.size == 0) {
            return;
        }
        long traceStart = MetalFrameTrace.ENABLED ? System.nanoTime() : 0L;
        long traceNested = MetalFrameTrace.ENABLED ? MetalFrameTrace.nestedNs() : 0L;
        this.drainUpTo(this.completedSeq());
        if (MetalFrameTrace.ENABLED) {
            // CONTRACT.md 16.R5-B: own time only (a callback's fence wait / flush commit is counted as wait / native submit).
            MetalFrameTrace.onDrain(System.nanoTime() - traceStart - (MetalFrameTrace.nestedNs() - traceNested));
        }
    }

    /** Device shutdown, after waiting for idle: executes every remaining entry regardless of its tag. */
    public void drainAll() {
        if (this.draining) {
            // Called from a deferred action: the outer drain owns the FIFO (and its release batch).
            return;
        }
        // Entries deferred by the actions themselves (e.g. a transient block freed by a rotation) are tagged
        // with pendingSeq and run in later rounds; loop until the FIFO is empty.
        for (int round = 0; this.size > 0 && round < 1000; round++) {
            this.drainUpTo(Long.MAX_VALUE);
        }
        if (this.size > 0) {
            LOGGER.error("Metal timeline: {} deferred entries keep re-deferring themselves; dropping them", this.size);
            this.clearFifo();
        }
    }

    /**
     * Runs entries with {@code tag <= limit} in FIFO order. Not re-entrant: a nested call (a callback that
     * polls a fence) returns immediately and the outer drain picks up where it is. Native handles go out in
     * one batch after the callbacks, even if a callback throws (the exception is rethrown afterwards and the
     * entries behind it stay queued).
     */
    private void drainUpTo(final long limit) {
        if (this.draining) {
            return;
        }
        this.draining = true;
        try {
            // Entries appended while draining get tag >= pendingSeq > completedSeq and stay queued (except
            // for drainAll, whose next round runs them).
            int budget = this.size;
            while (budget-- > 0 && this.size > 0) {
                int index = this.head;
                long tag = this.tags[index];
                if (tag > limit) {
                    break;
                }
                long handle = this.handles[index];
                Runnable action = this.actions[index];
                this.actions[index] = null;
                this.head = (index + 1) & (this.tags.length - 1);
                this.size--;
                if (action != null) {
                    action.run();
                } else {
                    this.addToBatch(handle);
                }
            }
        } finally {
            this.draining = false;
            this.flushBatch();
        }
    }

    private void addToBatch(final long handle) {
        if (this.testMode) {
            this.testReleased.add(handle);
            return;
        }
        if (this.batchCount == this.batchCapacity) {
            int capacity = Math.max(64, this.batchCapacity * 2);
            long address = MemoryUtil.nmemRealloc(this.batchAddress, (long)capacity * Long.BYTES);
            if (address == 0L) {
                // Out of native memory for the batch: release this one directly.
                int status = MtlNative.release(this.deviceHandle, handle);
                if (status != MtlNative.OK) {
                    LOGGER.error("mcmtl_release failed: {}: {}", MtlNative.statusName(status), MtlNative.lastError());
                }
                return;
            }
            this.batchAddress = address;
            this.batchCapacity = capacity;
        }
        MemoryUtil.memPutLong(this.batchAddress + (long)this.batchCount * Long.BYTES, handle);
        this.batchCount++;
    }

    private void flushBatch() {
        if (this.batchCount == 0) {
            return;
        }
        if (this.closed || this.batchAddress == 0L) {
            // close() ran from inside a deferred action: the device may be gone; leak instead of touching it.
            this.batchCount = 0;
            return;
        }
        int count = this.batchCount;
        this.batchCount = 0;
        int status = MtlNative.releaseBatch(this.deviceHandle, this.batchAddress, count);
        if (status != MtlNative.OK) {
            LOGGER.error("mcmtl_release_batch({} handles) failed: {}: {}", count, MtlNative.statusName(status), MtlNative.lastError());
        }
    }

    private void clearFifo() {
        Arrays.fill(this.actions, null);
        this.head = 0;
        this.size = 0;
    }

    /**
     * MetalDevice.close(), after {@link #drainAll()} and right before mcmtl_device_destroy: runs whatever the
     * shutdown steps between drainAll() and here deferred (transient blocks, shader functions, sampler states:
     * the GPU is idle and the device still exists), then enters the closed state described in the class
     * comment. Idempotent.
     */
    public void close() {
        if (this.closed) {
            return;
        }
        try {
            this.drainAll();
        } finally {
            this.closed = true;
            this.clearFifo();
            if (this.batchAddress != 0L) {
                MemoryUtil.nmemFree(this.batchAddress);
                this.batchAddress = 0L;
                this.batchCapacity = 0;
                this.batchCount = 0;
            }
        }
    }

    /** True after {@link #close()}. */
    public boolean isClosed() {
        return this.closed;
    }

    /** True in test mode (device handle 0): no downcalls. */
    public boolean isTestMode() {
        return this.testMode;
    }

    /** Test mode: every native handle a drain released, in release order (empty list outside test mode). */
    public List<Long> testReleasedHandles() {
        return this.testReleased;
    }
}
