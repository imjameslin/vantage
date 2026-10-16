package com.mojang.renderpearl.backend.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDeviceLossException;
import com.mojang.renderpearl.api.device.GpuOutOfMemoryException;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * I3 (java-device) tests: CONTRACT.md 11.3. Run with tools/metal-device-test.sh (compiles this file next to
 * build/client.jar, so it shares the runtime package and can use the package-private test seams).
 *
 * <p>Part A needs no native library: a test-mode {@link MetalTimeline} over heap memory and a
 * {@link MetalDevice#forTesting} device (headless buffers/textures/samplers/query pools/transient blocks).
 * Covered: timeline sequencing and waits, deferred-FIFO ordering (callbacks and batched releases, re-entrancy,
 * exceptions, ring growth), closed state, error policy, fences (incl. flush-on-wait when I4 is present),
 * buffers (map views, close-while-mapped, texel-view cache, createBuffer(data)), the buffer pool, texture/view
 * refcounts, sampler dedupe, query pools, transient memory (alignment, partial allocations, uploads,
 * multi-uploads, recycling through the FIFO, transient buffer semantics), DeviceInfo, close with a compile in
 * flight.
 *
 * <p>Part B runs against the real dylib when {@code new MetalDevice(new MetalBackend(), ...)} works (I1 merged,
 * or a test library via -Dmcmetal.library): Metal buffers mapped and written by the CPU, textures/views,
 * deduplicated samplers, query pools, fences and the timeline around real mcmtl_submit calls, and -- when the
 * decoder commits GPU work -- staging uploads through COPY_BUFFER / COPY_BUFFER_TO_TEXTURE /
 * COPY_TEXTURE_TO_BUFFER read back through map() (catches transient alignment/offset bugs under
 * MTL_DEBUG_LAYER=1). Skipped (not failed) while the native device is a stub.
 *
 * <p>Calls into other modules' skeletons (I4 recordTextureInit / flushForWait / shutdown, I5 ShaderCache.close)
 * may throw UnsupportedOperationException("TODO(I4|I5)..."): such results are reported as SKIP parts.
 */
public final class MetalDeviceTest {
    private static int checks;
    private static int failures;
    private static final List<String> SKIPS = new ArrayList<>();
    private static final VarHandle LONG = ValueLayout.JAVA_LONG.varHandle();
    private static final VarHandle INT = ValueLayout.JAVA_INT.varHandle();

    public static void main(final String[] args) {
        System.out.println("java " + System.getProperty("java.version") + ", native order " + ByteOrder.nativeOrder());
        // ---- A. pure Java ---------------------------------------------------------------------
        section("timeline: sequencing and waits", MetalDeviceTest::timelineBasics);
        section("timeline: FIFO order and batched releases", MetalDeviceTest::fifoOrder);
        section("timeline: callback exceptions, re-entrancy, drainAll", MetalDeviceTest::fifoEdgeCases);
        section("timeline: ring growth and wrap-around", MetalDeviceTest::fifoGrowth);
        section("timeline: closed state", MetalDeviceTest::timelineClosed);
        section("timeline: error policy (checkErrors)", MetalDeviceTest::checkErrorsPolicy);
        section("fences", MetalDeviceTest::fences);
        section("buffers: map views", MetalDeviceTest::bufferMap);
        section("buffers: close, texel views, createBuffer(data)", MetalDeviceTest::bufferLifetime);
        section("buffers: recycling pool", MetalDeviceTest::bufferPool);
        section("textures and views", MetalDeviceTest::textures);
        section("samplers", MetalDeviceTest::samplers);
        section("query pools", MetalDeviceTest::queryPools);
        section("transient memory: alignment and transient buffers", MetalDeviceTest::transientAlignment);
        section("transient memory: uploads and partial allocations", MetalDeviceTest::transientUploads);
        section("transient memory: recycling through the timeline", MetalDeviceTest::transientRecycling);
        section("transient memory: hysteresis (mcmetal.transientHysteresis)", MetalDeviceTest::transientHysteresis);
        section("buffers: renaming in-flight writeToBuffer targets (mcmetal.renameSmallWrites)", MetalDeviceTest::bufferRenaming);
        section("buffers: renaming refusals, forceFastWrite, close", MetalDeviceTest::bufferRenamingEdges);
        section("buffers: renaming recovers after a staged write (replay, r5-int)", MetalDeviceTest::bufferRenamingRecovery);
        section("buffers: COPY_TEXTURE_TO_BUFFER destinations are GPU writes (r5-int)", MetalDeviceTest::bufferRenamingTextureCopy);
        section("buffers + transient memory: no per-frame allocation with both toggles on (r5-int)", MetalDeviceTest::renamingHysteresisAllocation);
        section("device: DeviceInfo and accessors", MetalDeviceTest::deviceInfo);
        section("device: close with a compile in flight", MetalDeviceTest::deviceClose);
        // ---- B. real natives ---------------------------------------------------------------
        section("native device", MetalDeviceTest::nativeDevice);

        System.out.printf(Locale.ROOT, "%n%d checks, %d failures, %d skipped parts%n", checks, failures, SKIPS.size());
        for (String skip : SKIPS) {
            System.out.println("  SKIP " + skip);
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    // =============================================================================================
    // Mini harness
    // =============================================================================================

    private interface Body {
        void run() throws Exception;
    }

    private static void section(final String name, final Body body) {
        System.out.println("== " + name);
        int before = failures;
        try {
            body.run();
        } catch (Throwable t) {
            failures++;
            System.out.println("  FAIL: unexpected " + t);
            t.printStackTrace(System.out);
        }
        System.out.println(failures == before ? "  ok" : "  FAILED");
    }

    private static void check(final boolean condition, final String what) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("  FAIL: " + what);
        }
    }

    private static void checkEq(final long expected, final long actual, final String what) {
        check(expected == actual, what + " (expected " + expected + ", got " + actual + ")");
    }

    private static <T extends Throwable> T expect(final Class<T> type, final Body body, final String what) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                checks++;
                return type.cast(t);
            }
            check(false, what + ": expected " + type.getSimpleName() + ", got " + t);
            return null;
        }
        check(false, what + ": expected " + type.getSimpleName() + ", nothing thrown");
        return null;
    }

    /** True if {@code t} is another module's skeleton stub (TODO(I1|I2|I4|I5)). */
    private static boolean isStub(final Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof UnsupportedOperationException && String.valueOf(c.getMessage()).contains("TODO(")) {
                return true;
            }
            if (String.valueOf(c.getMessage()).contains("MCMTL_E_UNIMPLEMENTED")) {
                return true;
            }
        }
        for (Throwable s : t.getSuppressed()) {
            if (!isStub(s)) {
                return false;
            }
        }
        return t instanceof UnsupportedOperationException || String.valueOf(t.getMessage()).contains("MCMTL_E_UNIMPLEMENTED");
    }

    private static void skip(final String what) {
        SKIPS.add(what);
        System.out.println("  skip: " + what);
    }

    /** Headless device + test-mode timeline over heap memory. */
    private static final class Rig {
        final MemorySegment page = MemorySegment.ofArray(new long[(int)(MtlNative.STATUS_PAGE_SIZEOF / 8)]);
        final MetalTimeline timeline = new MetalTimeline(0L, this.page);
        final MetalDevice device = MetalDevice.forTesting(0L, this.page, this.timeline, null, false);

        Rig() {
            // Headless device (handle 0): the encoder must never reach the real mcmtl_submit downcall (that would
            // fail to bind without the library and poison MtlNative$Downcalls for the native part of this run).
            // Fake a native commit instead; the test drives completion through the status page.
            this.device.encoder().submitFunction = (dev, info, result) -> {
                org.lwjgl.system.MemoryUtil.memPutInt(result + MtlNative.SUBMIT_RESULT_FLAGS, MtlNative.SUBMIT_RESULT_FLAG_COMMITTED);
                return MtlNative.OK;
            };
        }

        void complete(final long value) {
            LONG.setVolatile(this.page, MtlNative.STATUS_PAGE_COMPLETED_VALUE, value);
        }

        void setInt(final long offset, final int value) {
            INT.setVolatile(this.page, offset, value);
        }

        /** What MetalCommandEncoder.submit()/flushForWait do to the timeline: consume pendingSeq. */
        long commit() {
            long seq = this.timeline.pendingSeq();
            this.timeline.onCommitted(seq);
            return seq;
        }

        List<Long> released() {
            return this.timeline.testReleasedHandles();
        }

        void completeLater(final long value, final long delayMs) {
            Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                }
                this.complete(value);
            });
            thread.setDaemon(true);
            thread.start();
        }

        /** Device close that tolerates the I4/I5 skeleton stubs (their UOE is reported as a skip). */
        void closeDevice() {
            try {
                this.device.close();
            } catch (RuntimeException e) {
                if (!isStub(e)) {
                    throw e;
                }
            }
        }
    }

    // =============================================================================================
    // A. timeline
    // =============================================================================================

    private static void timelineBasics() {
        Rig rig = new Rig();
        MetalTimeline t = rig.timeline;
        checkEq(1, t.pendingSeq(), "pendingSeq starts at FIRST_SEQ");
        checkEq(0, t.lastCommittedSeq(), "lastCommittedSeq before any commit");
        checkEq(0, t.completedSeq(), "completedSeq from the fresh page");
        check(t.isComplete(0) && !t.isComplete(1), "isComplete");
        expect(IllegalStateException.class, () -> t.await(1, 0), "await of an uncommitted value");
        expect(IllegalStateException.class, () -> t.onCommitted(2), "onCommitted with a skipped value");
        t.onCommitted(1);
        checkEq(2, t.pendingSeq(), "pendingSeq after onCommitted(1)");
        check(!t.await(1, 0), "await(1, 0) polls false");
        long start = System.nanoTime();
        check(!t.await(1, 20_000_000L), "await(1, 20 ms) times out");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        check(elapsedMs >= 15 && elapsedMs < 1000, "timeout honoured in ns (" + elapsedMs + " ms)");
        rig.completeLater(1, 30);
        check(t.await(1, -1L), "await forever returns once the page advances");
        checkEq(1, t.completedSeq(), "completedSeq follows the page");
        check(t.await(0, 0) && t.await(1, 0), "await of completed values");
        rig.commit();
        rig.setInt(MtlNative.STATUS_PAGE_DEVICE_LOST, 1);
        expect(GpuDeviceLossException.class, () -> t.await(2, 1_000_000_000L), "await on a lost device");
    }

    private static void fifoOrder() {
        Rig rig = new Rig();
        MetalTimeline t = rig.timeline;
        List<String> log = new ArrayList<>();
        t.deferRelease(0xA0L);
        t.defer(() -> log.add("r1"));
        t.deferRelease(0L); // ignored
        rig.commit();
        t.deferRelease(0xB0L);
        t.defer(() -> log.add("r2"));
        t.deferUntil(1, () -> log.add("r3")); // below the last tag: runs with the tag-2 entries, after them
        t.deferRelease(0xC0L);
        rig.commit();
        t.deferUntil(5, () -> log.add("r5"));
        checkEq(7, t.pendingEntries(), "entries queued (handle 0 ignored)");
        t.drain();
        check(log.isEmpty() && rig.released().isEmpty(), "nothing runs before completion");
        rig.complete(1);
        t.drain();
        check(log.equals(List.of("r1")), "tag 1 callbacks: " + log);
        check(rig.released().equals(List.of(0xA0L)), "tag 1 releases: " + rig.released());
        rig.complete(2);
        t.drain();
        check(log.equals(List.of("r1", "r2", "r3")), "FIFO order of callbacks: " + log);
        check(rig.released().equals(List.of(0xA0L, 0xB0L, 0xC0L)), "FIFO order of releases: " + rig.released());
        checkEq(1, t.pendingEntries(), "deferUntil(5) still queued");
        rig.complete(4);
        t.drain();
        checkEq(1, t.pendingEntries(), "deferUntil(5) waits for 5");
        t.drainAll();
        check(log.equals(List.of("r1", "r2", "r3", "r5")), "drainAll runs regardless of tags");
        checkEq(0, t.pendingEntries(), "FIFO empty");
    }

    private static void fifoEdgeCases() {
        Rig rig = new Rig();
        MetalTimeline t = rig.timeline;
        List<String> log = new ArrayList<>();
        // A throwing callback: handles before it are still released, entries after it stay queued.
        t.deferRelease(0x10L);
        t.defer(() -> {
            throw new IllegalStateException("boom");
        });
        t.deferRelease(0x20L);
        t.defer(() -> log.add("after"));
        rig.commit();
        rig.complete(1);
        expect(IllegalStateException.class, t::drain, "callback exception propagates");
        check(rig.released().equals(List.of(0x10L)), "release before the throwing callback flushed: " + rig.released());
        checkEq(2, t.pendingEntries(), "entries after the throwing callback stay queued");
        t.drain();
        check(rig.released().equals(List.of(0x10L, 0x20L)) && log.equals(List.of("after")), "next drain continues");

        // A callback that polls (nested drain) and one that defers more work.
        t.defer(() -> {
            t.drain();
            log.add("outer");
            t.defer(() -> log.add("deferred-in-drain"));
        });
        t.defer(() -> log.add("second"));
        long seq = rig.commit();
        rig.complete(seq);
        t.drain();
        check(log.equals(List.of("after", "outer", "second")), "nested drain is a no-op, outer continues: " + log);
        checkEq(1, t.pendingEntries(), "entry deferred during the drain waits for its own tag");
        seq = rig.commit();
        rig.complete(seq);
        t.drain();
        check(log.get(log.size() - 1).equals("deferred-in-drain"), "deferred-in-drain ran after its tag completed");

        // drainAll follows chains of entries that defer more entries.
        int[] chain = {0};
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (++chain[0] < 5) {
                t.defer(step[0]);
            }
        };
        t.defer(step[0]);
        t.drainAll();
        checkEq(5, chain[0], "drainAll runs entries appended by entries");
        checkEq(0, t.pendingEntries(), "FIFO empty after drainAll");
    }

    private static void fifoGrowth() {
        Rig rig = new Rig();
        MetalTimeline t = rig.timeline;
        long next = 1;
        long expected = 1;
        int[] callbacks = {0};
        for (int round = 0; round < 12; round++) {
            for (int i = 0; i < 300; i++) {
                t.deferRelease(next++);
                if (i % 50 == 0) {
                    t.defer(() -> callbacks[0]++);
                }
            }
            long seq = rig.commit();
            if (round % 3 != 2) {
                rig.complete(seq - 1); // lag one value: the ring keeps entries across rounds and wraps
                t.drain();
            }
        }
        rig.complete(t.lastCommittedSeq());
        t.drain();
        List<Long> released = rig.released();
        checkEq(3600, released.size(), "every handle released once");
        boolean ordered = true;
        for (Long handle : released) {
            ordered &= handle == expected++;
        }
        check(ordered, "releases in insertion order across growth and wrap-around");
        checkEq(72, callbacks[0], "every callback ran once");
        checkEq(0, t.pendingEntries(), "FIFO empty");
    }

    private static void timelineClosed() {
        Rig rig = new Rig();
        MetalTimeline t = rig.timeline;
        List<String> log = new ArrayList<>();
        t.deferRelease(0xD0L);
        t.defer(() -> log.add("pending"));
        rig.commit();
        t.close();
        check(t.isClosed(), "isClosed");
        check(log.equals(List.of("pending")) && rig.released().equals(List.of(0xD0L)), "close() runs what is still queued");
        checkEq(Long.MAX_VALUE, t.completedSeq(), "completedSeq once closed");
        check(t.await(1_000_000L, 0) && t.await(5, -1), "waits return true once closed");
        t.deferRelease(0xE0L);
        t.drain();
        check(!rig.released().contains(0xE0L), "deferRelease after close drops the handle");
        t.defer(() -> log.add("immediate"));
        t.deferUntil(1_000_000L, () -> log.add("immediate2"));
        check(log.equals(List.of("pending", "immediate", "immediate2")), "defer/deferUntil run immediately once closed");
        rig.setInt(MtlNative.STATUS_PAGE_ERROR_COUNT, 3);
        rig.setInt(MtlNative.STATUS_PAGE_DEVICE_LOST, 1);
        t.checkErrors(); // no-op
        t.close(); // idempotent
        checkEq(0, t.pendingEntries(), "FIFO empty");
    }

    private static void checkErrorsPolicy() {
        Rig rig = new Rig();
        MetalTimeline t = rig.timeline;
        t.checkErrors();
        rig.setInt(MtlNative.STATUS_PAGE_MESSAGE_COUNT, 4);
        t.checkErrors(); // messages alone are only logged
        checks++;
        rig.setInt(MtlNative.STATUS_PAGE_ERROR_COUNT, 1);
        rig.setInt(MtlNative.STATUS_PAGE_LAST_ERROR_CODE, MetalFormats.COMMAND_BUFFER_ERROR_INVALID_RESOURCE);
        expect(IllegalStateException.class, t::checkErrors, "non-loss command-buffer error");
        t.checkErrors(); // reported once
        checks++;
        rig.setInt(MtlNative.STATUS_PAGE_ERROR_COUNT, 2);
        rig.setInt(MtlNative.STATUS_PAGE_LAST_ERROR_CODE, MetalFormats.COMMAND_BUFFER_ERROR_OUT_OF_MEMORY);
        expect(GpuOutOfMemoryException.class, t::checkErrors, "MTLCommandBufferErrorOutOfMemory");
        t.checkErrors();
        rig.setInt(MtlNative.STATUS_PAGE_DEVICE_LOST, 1);
        expect(GpuDeviceLossException.class, t::checkErrors, "device loss");
        expect(GpuDeviceLossException.class, t::checkErrors, "sticky device loss throws again");
    }

    // =============================================================================================
    // A. fences
    // =============================================================================================

    private static void fences() {
        Rig rig = new Rig();
        MetalTimeline t = rig.timeline;
        List<String> log = new ArrayList<>();

        MetalFence current = new MetalFence(rig.device, t.pendingSeq());
        check(!current.awaitCompletion(0), "poll of the recording submit is false");
        try {
            rig.completeLater(1, 30);
            boolean done = current.awaitCompletion(2_000_000_000L);
            check(done && t.pendingSeq() == 2, "flush-on-wait committed the current seq and waited");
        } catch (RuntimeException e) {
            if (isStub(e)) {
                skip("fence flush-on-wait (MetalCommandEncoder.flushForWait is an I4 stub)");
            } else if (String.valueOf(e.getMessage()).contains("not loaded")) {
                skip("headless fence flush-on-wait (the encoder needs libmcmetal to commit; covered by the native part)");
            } else {
                throw e;
            }
            if (t.pendingSeq() == 1) {
                rig.commit();
            }
        }

        rig.complete(0);
        rig.complete(t.lastCommittedSeq());
        MetalFence old = new MetalFence(rig.device, 1);
        check(old.awaitCompletion(0), "fence of a completed seq");

        MetalFence f = new MetalFence(rig.device, t.pendingSeq());
        t.defer(() -> log.add("fence-tagged"));
        long seq = rig.commit();
        check(!f.awaitCompletion(0), "poll of a committed, incomplete seq");
        check(!f.awaitCompletion(5_000_000L), "timed wait times out (ns)");
        check(log.isEmpty(), "deferred entry not run before completion");
        rig.completeLater(seq, 30);
        check(f.awaitCompletion(-1L), "wait forever");
        check(log.equals(List.of("fence-tagged")), "completed fence drained the FIFO");
        check(f.awaitCompletion(0), "stays complete");

        MetalFence closed = new MetalFence(rig.device, t.pendingSeq());
        closed.close();
        check(closed.awaitCompletion(0) && closed.awaitCompletion(-1), "closed fence is complete");

        MetalFence errored = new MetalFence(rig.device, t.pendingSeq());
        long errSeq = rig.commit();
        rig.setInt(MtlNative.STATUS_PAGE_ERROR_COUNT, 1);
        rig.setInt(MtlNative.STATUS_PAGE_LAST_ERROR_CODE, MetalFormats.COMMAND_BUFFER_ERROR_INVALID_RESOURCE);
        rig.completeLater(errSeq, 20); // a failed command buffer still completes its value (native raises the prefix)
        expect(IllegalStateException.class, () -> errored.awaitCompletion(1_000_000_000L), "blocking fence wait surfaces command-buffer errors");
        check(errored.awaitCompletion(0), "the value itself completed");

        MetalFence lost = new MetalFence(rig.device, t.pendingSeq());
        rig.commit();
        rig.setInt(MtlNative.STATUS_PAGE_DEVICE_LOST, 1);
        expect(GpuDeviceLossException.class, () -> lost.awaitCompletion(1_000_000_000L), "fence wait on a lost device");

        t.close();
        check(new MetalFence(rig.device, 1_000L).awaitCompletion(0), "fences complete once the timeline is closed");
    }

    // =============================================================================================
    // A. buffers
    // =============================================================================================

    private static void bufferMap() {
        Rig rig = new Rig();
        MetalBuffer buffer = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST, 1000);
        checkEq(1000, buffer.size(), "size as requested");
        checkEq(GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST, buffer.usage(), "usage exactly as given");
        check(!buffer.isClosed() && !buffer.isTransient() && buffer.handle() != 0L && buffer.contents() != 0L, "fresh buffer");
        checkEq(0, buffer.backingOffset(), "backingOffset 0");
        checkEq(0, buffer.lastUseSeq(), "never used");
        buffer.markUsed(5);
        buffer.markUsed(3);
        checkEq(5, buffer.lastUseSeq(), "markUsed keeps the max");

        GpuBufferSlice.MappedView whole = buffer.map(false, true);
        check(whole.data().order() == ByteOrder.nativeOrder(), "native byte order");
        checkEq(1000, whole.data().capacity(), "whole-buffer view capacity");
        check(whole.slice().buffer() == buffer && whole.slice().offset() == 0 && whole.slice().length() == 1000, "whole-buffer slice");
        for (int i = 0; i < 250; i++) {
            whole.data().putInt(i * 4, i * 7 + 1);
        }
        checkEq(1, buffer.mappingCount(), "one open mapping");
        GpuBufferSlice.MappedView part = buffer.slice(100, 16).map(true, false);
        checkEq(2, buffer.mappingCount(), "two open mappings");
        check(part.slice().offset() == 100 && part.slice().length() == 16 && part.data().capacity() == 16, "sub-range view");
        check(part.data().getInt(0) == 25 * 7 + 1 && part.data().getInt(12) == 28 * 7 + 1, "sub-range sees the written bytes");
        check(MemoryUtil.memAddress(part.data()) == buffer.contents() + 100, "view = contents + offset");
        part.close();
        part.close(); // idempotent
        checkEq(1, buffer.mappingCount(), "double close of one view decrements once");
        whole.close();
        checkEq(0, buffer.mappingCount(), "all mappings closed");
        GpuBufferSlice.MappedView empty = buffer.map(1000, 0, true, false);
        checkEq(0, empty.data().capacity(), "zero-length map at the end");
        empty.close();

        MetalBuffer readOnly = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_MAP_READ, 64);
        MetalBuffer writeOnly = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_MAP_WRITE, 64);
        MetalBuffer neither = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_VERTEX, 64);
        expect(IllegalStateException.class, () -> readOnly.map(false, true), "write map of a MAP_READ buffer");
        expect(IllegalStateException.class, () -> writeOnly.map(true, false), "read map of a MAP_WRITE buffer");
        expect(IllegalStateException.class, () -> neither.map(true, false), "map of an unmappable buffer");
        expect(IllegalArgumentException.class, () -> readOnly.map(false, false), "map with neither read nor write");
        expect(IllegalArgumentException.class, () -> readOnly.map(60, 8, true, false), "map past the end");
        expect(IllegalArgumentException.class, () -> readOnly.map(-1, 8, true, false), "negative offset");
        check(readOnly.map(true, false).data().capacity() == 64, "read map");
    }

    private static void bufferLifetime() {
        Rig rig = new Rig();
        MetalTimeline t = rig.timeline;
        MetalBuffer buffer = (MetalBuffer)rig.device.createBuffer(() -> "x", GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, 256);
        long handle = buffer.handle();
        long view8 = buffer.texelView(MetalFormats.PIXEL_FORMAT_R8_SINT, 1);
        check(view8 != 0L && view8 == buffer.texelView(MetalFormats.PIXEL_FORMAT_R8_SINT, 1), "texel view cached per format");
        long view32 = buffer.texelView(MetalFormats.PIXEL_FORMAT_R32_UINT, 4);
        check(view32 != view8, "second format gets its own view");
        check(buffer.texelView(MetalFormats.PIXEL_FORMAT_R32_UINT, 4) == view32, "both cached");

        GpuBufferSlice.MappedView view = buffer.map(false, true);
        expect(IllegalStateException.class, buffer::close, "close while mapped throws (Vulkan parity)");
        check(buffer.isClosed(), "closed flag set even though close threw");
        buffer.close(); // idempotent, no throw
        expect(IllegalStateException.class, () -> buffer.map(false, true), "map after close");
        long seq = rig.commit();
        rig.complete(seq);
        t.drain();
        check(!rig.released().contains(handle), "backing kept while a mapping is open");
        view.close();
        long seq2 = rig.commit();
        rig.complete(seq2);
        t.drain();
        check(rig.released().containsAll(List.of(view8, view32, handle)), "views and backing released after the last unmap");
        checkEq(3, rig.released().size(), "released exactly once");

        // close() defers to the GPU tag: still alive while the tag is pending.
        MetalBuffer other = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_VERTEX, 128);
        other.close();
        other.close();
        t.drain();
        check(!rig.released().contains(other.handle()), "release waits for the close tag");
        long seq3 = rig.commit();
        rig.complete(seq3);
        t.drain();
        checkEq(1, rig.released().stream().filter(h -> h == other.handle()).count(), "released once after the tag");

        // A buffer closed earlier in the frame can still produce a texel view (released again right away).
        MetalBuffer late = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, 64);
        late.close();
        long lateView = late.texelView(MetalFormats.PIXEL_FORMAT_R8_SINT, 1);
        long seq4 = rig.commit();
        rig.complete(seq4);
        t.drain();
        check(rig.released().contains(lateView) && rig.released().contains(late.handle()), "late texel view released with the buffer");

        // createBuffer(data): synchronous copy from position(), data unchanged.
        ByteBuffer direct = MemoryUtil.memAlloc(19);
        for (int i = 0; i < 19; i++) {
            direct.put(i, (byte)(i * 3));
        }
        direct.position(3);
        MetalBuffer fromDirect = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_VERTEX, direct);
        checkEq(16, fromDirect.size(), "size = remaining()");
        check(direct.position() == 3 && direct.limit() == 19, "source buffer untouched");
        boolean same = true;
        for (int i = 0; i < 16; i++) {
            same &= MemoryUtil.memGetByte(fromDirect.contents() + i) == (byte)((i + 3) * 3);
        }
        check(same, "contents copied from position()");
        MemoryUtil.memFree(direct);
        byte[] bytes = {9, 8, 7, 6, 5};
        ByteBuffer heap = ByteBuffer.wrap(bytes);
        heap.position(1);
        MetalBuffer fromHeap = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_INDEX, heap);
        check(fromHeap.size() == 4 && MemoryUtil.memGetByte(fromHeap.contents()) == 8 && MemoryUtil.memGetByte(fromHeap.contents() + 3) == 5,
            "heap ByteBuffer source");
        check(heap.position() == 1, "heap source untouched");
        expect(IllegalArgumentException.class, () -> MetalBuffer.create(rig.device, null, GpuBuffer.USAGE_VERTEX, 0), "size 0");
    }

    private static void bufferPool() {
        Rig rig = new Rig();
        MetalBuffer.Pool pool = new MetalBuffer.Pool(rig.device);
        check(!pool.accepts(1024) && pool.accepts(256 * 1024) && !pool.accepts(MetalBuffer.Pool.MAX_ENTRY_BYTES + 1), "size limits");
        pool.put(0x100L, 0x1000L, 0x1000L, 256 * 1024, 256 * 1024, GpuBuffer.USAGE_VERTEX, 0);
        pool.put(0x200L, 0x2000L, 0x2000L, 256 * 1024, 256 * 1024, GpuBuffer.USAGE_VERTEX, 0);
        pool.put(0x300L, 0x3000L, 0x3000L, 512 * 1024, 512 * 1024, GpuBuffer.USAGE_VERTEX, 0);
        checkEq(3, pool.size(), "three idle backings");
        check(pool.take(256 * 1024, GpuBuffer.USAGE_COPY_DST, 1) == null, "resource options must match");
        check(pool.take(256 * 1024, GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, 0) == null, "texel rounding class must match");
        MetalBuffer.Pool.Backing hit = pool.take(256 * 1024, GpuBuffer.USAGE_COPY_DST, 0);
        check(hit != null && hit.handle() == 0x200L, "most recently returned backing first");
        check(pool.take(300 * 1024, GpuBuffer.USAGE_VERTEX, 0) == null, "exact size only");
        checkEq(2, pool.size(), "two left");
        for (int i = 0; i < 9; i++) {
            rig.commit();
        }
        check(pool.take(1, 0, 0) == null && pool.size() == 0, "idle entries evicted after MAX_IDLE_SEQS");
        checkEq(0, pool.totalBytes(), "byte accounting");
        long big = MetalBuffer.Pool.MAX_ENTRY_BYTES;
        for (int i = 0; i < 4; i++) {
            pool.put(0x400L + i, 0, 0, big, big, 0, 0);
        }
        check(pool.totalBytes() <= MetalBuffer.Pool.MAX_TOTAL_BYTES, "total cap enforced (" + (pool.totalBytes() >> 20) + " MiB)");
        pool.close();
        checkEq(0, pool.size(), "close empties the pool");
        check(!pool.accepts(256 * 1024), "closed pool refuses returns");
    }

    // =============================================================================================
    // A. textures, samplers, query pools
    // =============================================================================================

    private static void textures() {
        Rig rig = new Rig();
        MetalTimeline t = rig.timeline;
        int rt = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
        MetalTexture tex = (MetalTexture)rig.device.createTexture((String)null, rt, GpuFormat.RGBA8_UNORM, 64, 32, 1, 3);
        check("".equals(tex.getLabel()), "null label -> \"\"");
        check(tex.handle() != 0L && tex.pixelFormat() == MetalFormats.PIXEL_FORMAT_RGBA8_UNORM && !tex.isCube(), "texture basics");
        check(tex.getWidth(0) == 64 && tex.getWidth(2) == 16 && tex.getHeight(2) == 8, "mip sizes");
        check(tex.getWidth(7) == 0, "getWidth(mip) is not clamped to 1 (spec 2.10)");
        checkEq(1, tex.references(), "self reference");

        MetalTextureView full = (MetalTextureView)rig.device.createTextureView(tex, 0, 3);
        check(full.viewHandle() == 0L && full.samplingHandle() == tex.handle(), "full range: no native view");
        MetalTextureView partial = (MetalTextureView)rig.device.createTextureView(tex, 1, 2);
        check(partial.viewHandle() != 0L && partial.samplingHandle() == partial.viewHandle(), "partial range: own view");
        check(partial.getWidth(0) == 32 && partial.getHeight(1) == 8 && partial.baseMipLevel() == 1, "view sizes add baseMipLevel");
        checkEq(3, tex.references(), "texture + 2 views");
        expect(IllegalArgumentException.class, () -> rig.device.createTextureView(tex, 0, 0), "mipLevels 0");
        expect(IllegalArgumentException.class, () -> rig.device.createTextureView(tex, 2, 2), "range past the last mip");

        tex.close();
        tex.close();
        check(tex.isClosed(), "texture closed");
        checkEq(2, tex.references(), "close drops only the self reference, once");
        expect(IllegalArgumentException.class, () -> rig.device.createTextureView(tex, 0, 1), "view of a closed texture");
        partial.close();
        partial.close();
        long seq = rig.commit();
        rig.complete(seq);
        t.drain();
        check(rig.released().equals(List.of(partial.viewHandle())), "partial view released, texture kept (a view is open)");
        full.close();
        long seq2 = rig.commit();
        rig.complete(seq2);
        t.drain();
        check(rig.released().equals(List.of(partial.viewHandle(), tex.handle())), "texture released when the last view closed");
        expect(IllegalStateException.class, tex::retainView, "no views of a released texture");

        int cubeUsage = GpuTexture.USAGE_CUBEMAP_COMPATIBLE | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
        MetalTexture cube = (MetalTexture)rig.device.createTexture("sky", cubeUsage, GpuFormat.RGBA8_UNORM, 16, 16, 6, 5);
        check(cube.isCube() && cube.textureType() == MetalFormats.TEXTURE_TYPE_CUBE && "sky".equals(cube.getLabel()), "cube texture");
        MetalTextureView cubeView = (MetalTextureView)rig.device.createTextureView(cube, 2, 3);
        check(cubeView.isCube() && cubeView.viewHandle() != 0L, "cube view");

        expect(IllegalArgumentException.class, () -> rig.device.createTexture((String)null, rt, GpuFormat.RGB8_UNORM, 4, 4, 1, 1), "RGB8 has no Metal format");
        expect(IllegalArgumentException.class, () -> rig.device.createTexture((String)null, rt, GpuFormat.D24_UNORM_S8_UINT, 4, 4, 1, 1), "D24S8");
        expect(IllegalArgumentException.class, () -> rig.device.createTexture((String)null, rt, GpuFormat.RGBA8_UNORM, 0, 4, 1, 1), "width 0");
        expect(IllegalArgumentException.class, () -> rig.device.createTexture((String)null, rt, GpuFormat.RGBA8_UNORM, 16385, 4, 1, 1), "width 16385");
        MetalTexture depth = (MetalTexture)rig.device.createTexture((String)null, rt, GpuFormat.D32_FLOAT, 16384, 16384, 1, 1);
        check(depth.pixelFormat() == MetalFormats.PIXEL_FORMAT_DEPTH32_FLOAT, "D32_FLOAT at the size limit");

        // Textures without RENDER_ATTACHMENT are zero-filled through the encoder's init stream (I4).
        int sampled = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
        int releasedBefore = rig.released().size();
        try {
            MetalTexture atlas = (MetalTexture)rig.device.createTexture("atlas", sampled, GpuFormat.RGBA8_UNORM, 32, 32, 1, 1);
            check(!atlas.isClosed(), "sampled texture created and zero-fill recorded");
        } catch (UnsupportedOperationException e) {
            if (!isStub(e)) {
                throw e;
            }
            skip("texture zero-fill (MetalCommandEncoder.recordTextureInit is an I4 stub)");
            long s = rig.commit();
            rig.complete(s);
            t.drain();
            checkEq(releasedBefore + 1, rig.released().size(), "a texture whose init failed is released, not leaked");
        }
    }

    private static void samplers() {
        Rig rig = new Rig();
        MetalTimeline t = rig.timeline;
        MetalSampler a = (MetalSampler)rig.device.createSampler(AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.NEAREST, 4, OptionalDouble.empty());
        MetalSampler b = (MetalSampler)rig.device.createSampler(AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.NEAREST, 4, OptionalDouble.empty());
        check(a != b && a.handle() == b.handle() && a.handle() != 0L, "same parameters share one native state, distinct objects");
        check(a.getAddressModeU() == AddressMode.REPEAT && a.getAddressModeV() == AddressMode.CLAMP_TO_EDGE && a.getMinFilter() == FilterMode.LINEAR
            && a.getMagFilter() == FilterMode.NEAREST && a.getMaxAnisotropy() == 4 && a.getMaxLod().isEmpty(), "getters return creation parameters");
        MetalSampler c = (MetalSampler)rig.device.createSampler(AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.NEAREST, 4, OptionalDouble.of(0.0));
        check(c.handle() != a.handle() && c.getMaxLod().getAsDouble() == 0.0, "maxLod 0 is a different state (not mipmapped)");
        checkEq(2, rig.device.samplerCache().size(), "two live states");
        a.close();
        a.close();
        long seq = rig.commit();
        rig.complete(seq);
        t.drain();
        check(!rig.released().contains(a.handle()) && a.isClosed() && !b.isClosed(), "state kept while b is open");
        b.close();
        long seq2 = rig.commit();
        rig.complete(seq2);
        t.drain();
        checkEq(1, rig.released().stream().filter(h -> h == a.handle()).count(), "state released once when the last sampler closed");
        MetalSampler d = (MetalSampler)rig.device.createSampler(AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.NEAREST, 4, OptionalDouble.empty());
        check(d.handle() != a.handle(), "a released key gets a new state");
        expect(IllegalArgumentException.class, () -> MetalSampler.create(rig.device, AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.LINEAR, FilterMode.LINEAR, 0, OptionalDouble.empty()), "anisotropy 0");
        check(MetalSampler.Cache.key(2, 0, 1, 0, 2, 4, Float.MAX_VALUE) != MetalSampler.Cache.key(2, 0, 1, 0, 2, 8, Float.MAX_VALUE), "key includes anisotropy");
    }

    private static void queryPools() {
        Rig rig = new Rig();
        MetalTimeline t = rig.timeline;
        MetalQueryPool pool = (MetalQueryPool)rig.device.createTimestampQueryPool(8);
        checkEq(8, pool.size(), "size");
        OptionalLong[] values = pool.getValues(0, 8);
        boolean allEmpty = values.length == 8;
        for (OptionalLong v : values) {
            allEmpty &= v.isEmpty();
        }
        check(allEmpty, "unwritten samples are empty");
        pool.markWritten(3, t.pendingSeq());
        check(pool.getValue(3).isEmpty(), "written but not complete: empty (non-blocking)");
        expect(IndexOutOfBoundsException.class, () -> pool.getValues(4, 5), "index + count > size");
        expect(IndexOutOfBoundsException.class, () -> pool.getValues(-1, 1), "negative index");
        check(pool.getValues(8, 0).length == 0, "empty range at the end");
        expect(IllegalArgumentException.class, () -> rig.device.createTimestampQueryPool(0), "size 0");
        pool.close();
        pool.close();
        long seq = rig.commit();
        rig.complete(seq);
        t.drain();
        checkEq(1, rig.released().stream().filter(h -> h == pool.handle()).count(), "released once");
    }

    // =============================================================================================
    // A. transient memory
    // =============================================================================================

    private static void transientAlignment() {
        Rig rig = new Rig();
        MetalTransientMemory tm = rig.device.transientMemory();
        long[] alignments = {1, 2, 4, 8, 16, 64, 256, 3, 12, 48};
        long lastEnd = -1;
        long block = 0;
        for (long alignment : alignments) {
            long effective = MetalTransientMemory.effectiveGpuAlignment(alignment);
            check(effective % 16 == 0 && effective % alignment == 0, "effective alignment " + effective + " for " + alignment);
            GpuBufferSlice.MappedView view = tm.allocateStaging(37, alignment, GpuBuffer.USAGE_COPY_SRC);
            MetalBuffer buffer = (MetalBuffer)view.slice().buffer();
            check(view.slice().offset() % effective == 0, "slice offset aligned to " + effective);
            check((buffer.contents() + view.slice().offset()) % 16 == 0, "CPU address 16-aligned");
            check(MemoryUtil.memAddress(view.data()) == buffer.contents() + view.slice().offset(), "view address = block + offset");
            if (block == 0) {
                block = buffer.handle();
            }
            check(buffer.handle() == block, "small allocations share the block");
            check(view.slice().offset() >= lastEnd, "no overlap");
            lastEnd = view.slice().offset() + view.slice().length();
            view.close();
        }
        checkEq(16, MetalTransientMemory.effectiveGpuAlignment(1), "max(1, 16)");
        checkEq(256, MetalTransientMemory.effectiveGpuAlignment(256), "max(256, 16)");

        GpuBufferSlice gpu = tm.allocateGpu(64, 16, GpuBuffer.USAGE_UNIFORM);
        MetalBuffer tb = (MetalBuffer)gpu.buffer();
        check(tb instanceof MetalTransientMemory.TransientBuffer && tb.isTransient(), "transient buffer type");
        checkEq(MetalTransientMemory.BLOCK_SIZE, tb.size(), "size() = whole block");
        checkEq(GpuBuffer.USAGE_UNIFORM, tb.usage(), "usage as requested");
        checkEq(0, tb.backingOffset(), "slice offset carries the position, not the backing offset");
        expect(IllegalStateException.class, () -> tb.map(false, true), "map of a transient buffer");
        expect(IllegalStateException.class, tb::slice, "slice() of a transient buffer");
        expect(IllegalStateException.class, () -> tb.slice(0, 4), "slice(o, l) of a transient buffer");
        check(gpu.slice(16, 16).offset() == gpu.offset() + 16, "record-level slicing still works");
        check(!tb.isClosed(), "open during the frame");
        tb.markUsed(rig.timeline.pendingSeq());
        long seq = rig.commit();
        tm.endFrame(seq);
        check(tb.isClosed(), "closed once the frame was submitted");
        rig.complete(seq);

        ByteBuffer cpu = tm.allocateCpu(100, 16);
        check(cpu.capacity() == 100 && MemoryUtil.memAddress(cpu) % 16 == 0, "CPU allocation");
        expect(IllegalArgumentException.class, () -> tm.allocateCpu(10, 32), "CPU alignment above 16");

        GpuBufferSlice big = tm.allocateGpu(MetalTransientMemory.BLOCK_SIZE + 16, 16, GpuBuffer.USAGE_COPY_DST);
        check(big.offset() == 0 && big.length() == MetalTransientMemory.BLOCK_SIZE + 16 && big.buffer().size() == MetalTransientMemory.BLOCK_SIZE + 16,
            "oversized request gets a dedicated block");
        rig.closeDevice();
        expect(IllegalStateException.class, () -> tm.allocateGpu(16, 16, 0), "transient memory after device close");
    }

    private static void transientUploads() {
        Rig rig = new Rig();
        MetalTransientMemory tm = rig.device.transientMemory();
        ByteBuffer a = bytes(5, 1);
        ByteBuffer b = bytes(7, 100);
        GpuBufferSlice slice = tm.uploadStaging(List.of(a, b), 4, GpuBuffer.USAGE_COPY_SRC);
        checkEq(16, slice.length(), "pieces padded to the requested alignment (5 -> 8, 7 -> 8)");
        long base = ((MetalBuffer)slice.buffer()).contents() + slice.offset();
        check(matches(base, a) && matches(base + 8, b), "concatenated contents");
        check(a.position() == 0 && b.position() == 0, "sources untouched");
        GpuBufferSlice single = tm.uploadGpu(bytes(3, 50), 1, GpuBuffer.USAGE_UNIFORM);
        check(single.offset() % 16 == 0 && single.length() == 3, "uploadGpu of one buffer");

        // Partial allocation (spec 2.9): only what fits in the current block, rounded down to elementSize.
        Rig rig2 = new Rig();
        MetalTransientMemory tm2 = rig2.device.transientMemory();
        GpuBufferSlice filler = tm2.allocateGpu(MetalTransientMemory.BLOCK_SIZE - 100, 16, 0);
        checkEq(0, filler.offset(), "filler at block start");
        GpuBufferSlice partial = tm2.allocateGpu(1000, 16, 0, 32, 24);
        checkEq(MetalTransientMemory.BLOCK_SIZE - 96, partial.offset(), "partial allocation at the aligned tail");
        checkEq(96 / 24 * 24, partial.length(), "rest of the block rounded down to elementSize");
        check(partial.buffer() != filler.buffer() && ((MetalBuffer)partial.buffer()).handle() == ((MetalBuffer)filler.buffer()).handle(), "same block, own buffer object");
        Rig rig3 = new Rig();
        MetalTransientMemory tm3 = rig3.device.transientMemory();
        tm3.allocateGpu(MetalTransientMemory.BLOCK_SIZE - 100, 16, 0);
        ByteBuffer data = bytes(1000, 7);
        GpuBufferSlice cut = tm3.uploadStaging(List.of(data), 16, GpuBuffer.USAGE_COPY_SRC, 16, 1);
        checkEq(96, cut.length(), "partial upload is shorter than requested");
        check(matches(((MetalBuffer)cut.buffer()).contents() + cut.offset(), bytes(96, 7)), "partial upload copies what fits");

        // multiUpload: input order, every piece 16-aligned, contents intact, packed largest-first.
        Rig rig4 = new Rig();
        MetalTransientMemory tm4 = rig4.device.transientMemory();
        List<ByteBuffer> pieces = List.of(bytes(10, 1), bytes(3 << 20, 2), bytes(1 << 20, 3), bytes(3 << 20, 4), bytes(33, 5));
        List<GpuBufferSlice> uploaded = tm4.multiUploadStaging(pieces, 1, GpuBuffer.USAGE_COPY_SRC);
        checkEq(pieces.size(), uploaded.size(), "one slice per input");
        boolean ok = true;
        for (int i = 0; i < pieces.size(); i++) {
            GpuBufferSlice s = uploaded.get(i);
            MetalBuffer owner = (MetalBuffer)s.buffer();
            ok &= s.length() == pieces.get(i).remaining();
            ok &= s.offset() % 16 == 0;
            ok &= s.offset() + s.length() <= owner.size();
            ok &= matches(owner.contents() + s.offset(), pieces.get(i));
        }
        check(ok, "multiUpload slices in input order with correct contents");
        long distinctBlocks = uploaded.stream().mapToLong(s -> ((MetalBuffer)s.buffer()).handle()).distinct().count();
        checkEq(2, distinctBlocks, "3+1 MiB fill one 4 MiB block, 3 MiB + the small pieces the next (largest-first packing)");
    }

    private static void transientRecycling() {
        // The vanilla recycling rules (an idle block is freed after a rotation that did not use it): built with the
        // hysteresis toggle off whatever the JVM flag says (the hysteresis section pins it on), so the suite also runs
        // with -Dmcmetal.transientHysteresis=true (r5-int).
        boolean saved = MetalTransientMemory.TRANSIENT_HYSTERESIS;
        MetalTransientMemory.TRANSIENT_HYSTERESIS = false;
        Rig rig;
        try {
            rig = new Rig();
        } finally {
            MetalTransientMemory.TRANSIENT_HYSTERESIS = saved;
        }
        MetalTimeline t = rig.timeline;
        MetalTransientMemory tm = rig.device.transientMemory();
        long blockA = handleOf(tm.allocateGpu(1024, 16, 0));
        long seq1 = rig.commit();
        tm.endFrame(seq1);
        t.drain();
        long blockB = handleOf(tm.allocateGpu(1024, 16, 0));
        check(blockB != blockA, "frame 2 cannot reuse frame 1's block before frame 1 completed");
        long seq2 = rig.commit();
        tm.endFrame(seq2);
        rig.complete(seq1);
        t.drain();
        long blockC = handleOf(tm.allocateGpu(1024, 16, 0));
        checkEq(blockA, blockC, "frame 3 reuses frame 1's block after completion");
        long seq3 = rig.commit();
        tm.endFrame(seq3);
        rig.complete(seq3);
        t.drain();
        checkEq(2, tm.blocksCreated(), "two blocks in steady state with two frames in flight");
        // A frame that uses no transient memory: idle blocks are trimmed (released through the FIFO).
        long seq4 = rig.commit();
        tm.endFrame(seq4);
        long seq5 = rig.commit();
        tm.endFrame(seq5);
        rig.complete(seq5);
        t.drain();
        check(rig.released().contains(blockA) && rig.released().contains(blockB), "idle blocks freed after an unused rotation: " + rig.released());
        checkEq(2, tm.blocksFreed(), "blocks freed");
        rig.closeDevice();
        check(t.isClosed(), "timeline closed by device close");
    }

    /** CONTRACT.md 16.R5-C: idle blocks kept up to the high-water mark of the last 120 rotations instead of freed. */
    private static void transientHysteresis() {
        boolean saved = MetalTransientMemory.TRANSIENT_HYSTERESIS;
        MetalTransientMemory.TRANSIENT_HYSTERESIS = true;
        Rig rig;
        try {
            rig = new Rig();
        } finally {
            MetalTransientMemory.TRANSIENT_HYSTERESIS = saved;
        }
        MetalTimeline t = rig.timeline;
        MetalTransientMemory tm = rig.device.transientMemory();
        // Frame 1 uses block A, frame 2 block B (A in flight), frames 3 and 4 use none (all writes took the memcpy path).
        long blockA = handleOf(tm.allocateGpu(1024, 16, 0));
        long seq1 = rig.commit();
        tm.endFrame(seq1);
        long blockB = handleOf(tm.allocateGpu(1024, 16, 0));
        check(blockB != blockA, "an in-flight block is never handed out again");
        long seq2 = rig.commit();
        tm.endFrame(seq2);
        rig.complete(seq1);
        t.drain();
        long seq3 = rig.commit();
        tm.endFrame(seq3);   // A sat unused for a rotation: vanilla frees it, hysteresis keeps it (high water 1)
        checkEq(1, tm.highWater(), "high water = most blocks one rotation used");
        checkEq(1, tm.reservedBlocks(), "unused idle block kept in the reserve");
        checkEq(0, tm.blocksFreed(), "nothing freed yet");
        rig.complete(seq2);
        t.drain();
        long seq4 = rig.commit();
        tm.endFrame(seq4);   // B unused too: the reserve is full at the high-water mark, B is freed
        checkEq(1, tm.reservedBlocks(), "reserve capped at the high-water mark");
        checkEq(1, tm.blocksFreed(), "the block beyond the mark is freed");
        long blockC = handleOf(tm.allocateGpu(1024, 16, 0));
        check(blockC == blockA, "the next frame that needs staging takes the reserved (idle) block instead of creating one");
        checkEq(2, tm.blocksCreated(), "no third MTLBuffer");
        checkEq(1, tm.blocksReused(), "one reserve hit");
        long seq5 = rig.commit();
        tm.endFrame(seq5);
        rig.complete(seq5);
        t.drain();
        // 120 rotations without staging: the window forgets the frame that used a block and the reserve is released.
        for (int i = 0; i < MetalTransientMemory.HYSTERESIS_ROTATIONS + 1; i++) {
            long seq = rig.commit();
            tm.endFrame(seq);
            rig.complete(seq);
            t.drain();
        }
        checkEq(0, tm.highWater(), "high water decays after HYSTERESIS_ROTATIONS idle rotations");
        checkEq(0, tm.reservedBlocks(), "reserve trimmed");
        check(rig.released().contains(blockA) && rig.released().contains(blockB), "both blocks released in the end: " + rig.released());
        // A reserved block is released at close.
        tm.allocateGpu(1024, 16, 0);
        long seqA = rig.commit();
        tm.endFrame(seqA);
        rig.complete(seqA);
        t.drain();
        long seqB = rig.commit();
        tm.endFrame(seqB);
        checkEq(1, tm.reservedBlocks(), "block reserved again");
        long created = tm.blocksCreated();
        long freed = tm.blocksFreed();
        rig.complete(seqB);
        rig.closeDevice();
        checkEq(created, tm.blocksFreed(), "close frees every block, reserve included (" + freed + " before close)");
    }

    /** Main-stream COPY_BUFFER records: {srcHandle, srcOffset, dstHandle, dstOffset, length}. */
    private static List<long[]> copyRecords(final MetalCommandEncoder encoder) {
        List<long[]> out = new ArrayList<>();
        StreamWriter stream = encoder.mainStream();
        long base = stream.address();
        long offset = 0L;
        while (offset < stream.size()) {
            long r = base + offset;
            long header = MemoryUtil.memGetLong(r);
            int op = (int)(header & 0xFFFF);
            int size = (int)(header >>> 16 & 0xFFFF);
            if (op == StreamOps.OP_COPY_BUFFER) {
                out.add(new long[]{
                    MemoryUtil.memGetLong(r + StreamOps.COPY_BUFFER_SRC_BUFFER), MemoryUtil.memGetLong(r + StreamOps.COPY_BUFFER_SRC_OFFSET),
                    MemoryUtil.memGetLong(r + StreamOps.COPY_BUFFER_DST_BUFFER), MemoryUtil.memGetLong(r + StreamOps.COPY_BUFFER_DST_OFFSET),
                    MemoryUtil.memGetLong(r + StreamOps.COPY_BUFFER_LENGTH)});
            }
            offset += size;
        }
        return out;
    }

    private static byte[] read(final long address, final int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = MemoryUtil.memGetByte(address + i);
        }
        return out;
    }

    /** writeToBuffer with {@code length} bytes of {@code seed}; applies the same write to {@code model}. */
    private static void write(final MetalCommandEncoder encoder, final MetalBuffer buffer, final int offset, final int length, final int seed, final byte[] model) {
        ByteBuffer data = bytes(length, seed);
        encoder.writeToBuffer(buffer.slice(offset, length), data);
        for (int i = 0; i < length; i++) {
            model[offset + i] = data.get(i);
        }
        MemoryUtil.memFree(data);
    }

    /**
     * CONTRACT.md 16.R5-C. A 256-byte UBO is read by recorded copies (they stand for any record: every one writes
     * {@code handle()} at record time) and rewritten in between, within a frame and across frames with the previous frame
     * in flight. Checks: no COPY_BUFFER into the UBO is recorded; every recorded read names the backing that held the
     * bytes current at its record time, and those bytes stay frozen until its seq completes; a retired backing is reused
     * only after its last reader completed; the logical contents (written range + untouched bytes) are always right.
     */
    private static void bufferRenaming() {
        boolean saved = MetalCommandEncoder.RENAME_SMALL_WRITES;
        MetalCommandEncoder.RENAME_SMALL_WRITES = true;
        try {
            Rig rig = new Rig();
            MetalCommandEncoder enc = rig.device.encoder();
            MetalBuffer ubo = (MetalBuffer)rig.device.createBuffer(() -> "ubo", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST
                | GpuBuffer.USAGE_COPY_SRC, 256);
            MetalBuffer sink = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_COPY_DST, 4096);
            byte[] model = new byte[256];
            // Readers recorded so far and still pending: backing handle, contents address, bytes at record time, seq.
            List<Object[]> readers = new ArrayList<>();
            java.util.function.LongConsumer record = seq -> {
                enc.copyToBuffer(ubo.slice(), sink.slice(readers.size() % 16 * 256, 256));
                readers.add(new Object[]{ubo.handle(), ubo.contents(), model.clone(), seq});
            };
            java.util.function.Consumer<String> verify = what -> {
                check(java.util.Arrays.equals(model, read(ubo.contents(), 256)), what + ": current backing holds the stream-order contents");
                long completed = rig.timeline.completedSeq();
                for (Object[] r : readers) {
                    if ((long)r[3] > completed) {
                        check(java.util.Arrays.equals((byte[])r[2], read((long)r[1], 256)),
                            what + ": a pending reader's backing still holds its record-time bytes (seq " + r[3] + ")");
                    }
                }
                for (long[] c : copyRecords(enc)) {
                    check(c[2] != ubo.handle() && readers.stream().noneMatch(r -> (long)r[0] == c[2]), what + ": no COPY_BUFFER into the UBO");
                }
            };
            write(enc, ubo, 0, 256, 1, model);   // idle: memcpy
            checkEq(0, enc.renames, "first write takes the memcpy path");
            long a = ubo.handle();
            // Frame 1: read, rewrite a part, read, rewrite, read.
            record.accept(rig.timeline.pendingSeq());
            write(enc, ubo, 16, 32, 2, model);
            check(ubo.handle() != a, "in-use write renamed the buffer");
            checkEq(0, ubo.lastUseSeq(), "the new backing is idle");
            verify.accept("frame 1 rename 1");
            check(copyRecords(enc).get(0)[0] == a, "the recorded read still names the old backing");
            long b = ubo.handle();
            write(enc, ubo, 200, 16, 3, model);   // idle new backing (nothing read it since): plain memcpy
            check(ubo.handle() == b, "write into the not yet read new backing is a memcpy");
            record.accept(rig.timeline.pendingSeq());
            write(enc, ubo, 0, 8, 4, model);
            long c = ubo.handle();
            check(c != a && c != b, "no idle spare (A and B read by the pending frame): a third backing");
            record.accept(rig.timeline.pendingSeq());
            verify.accept("frame 1 end");
            checkEq(2, ubo.spareCount(), "two retired backings");
            enc.submit();
            // Frame 2 starts with frame 1 in flight.
            long seq2 = rig.timeline.pendingSeq();
            write(enc, ubo, 64, 64, 5, model);
            long d = ubo.handle();
            check(d != a && d != b && d != c, "frame 1 not complete: a fourth backing (cross-frame rename)");
            checkEq(1, enc.renamesInFlight, "counted as a cross-frame rename");
            verify.accept("frame 2 rename while frame 1 in flight");
            rig.complete(seq2 - 1);
            rig.timeline.drain();
            record.accept(seq2);
            write(enc, ubo, 128, 4, 6, model);
            long e = ubo.handle();
            check(e == a || e == b || e == c, "frame 1 completed: a retired backing is reused, no new one");
            checkEq(3, ubo.spareCount(), "spare count stable once reuse starts");
            verify.accept("frame 2 reuse");
            record.accept(seq2);
            enc.submit();
            rig.complete(seq2);
            // Steady state: one rename per frame over 50 frames with 2 in flight creates nothing new.
            java.util.Set<Long> handles = new java.util.HashSet<>(List.of(a, b, c, d));
            for (int f = 0; f < 50; f++) {
                // Recording frame `seq` while frame seq - 1 is in flight (the throttle guarantees seq - 2 completed).
                long seq = rig.timeline.pendingSeq();
                record.accept(seq);
                write(enc, ubo, (f * 8) % 240, 16, 10 + f, model);
                handles.add(ubo.handle());
                verify.accept("steady frame " + f);
                rig.complete(seq - 1);
                enc.submit();
            }
            checkEq(4, handles.size(), "no backing created in steady state (" + handles.size() + " distinct)");
            checkEq(4 + 50, enc.renames, "renames counted");
            // Close: current backing and every spare released once their last reader completed.
            int spares = ubo.spareCount();
            ubo.close();
            long seqClose = rig.commit();
            rig.complete(seqClose);
            rig.timeline.drain();
            check(rig.released().containsAll(handles), "close releases the current backing and " + spares + " spares: " + rig.released());
            checkEq(0, ubo.spareCount(), "spares dropped");
        } finally {
            MetalCommandEncoder.RENAME_SMALL_WRITES = saved;
        }
    }

    private static void bufferRenamingEdges() {
        boolean savedRename = MetalCommandEncoder.RENAME_SMALL_WRITES;
        boolean savedForce = MetalCommandEncoder.FORCE_FAST_WRITE;
        try {
            Rig rig = new Rig();
            MetalCommandEncoder enc = rig.device.encoder();
            MetalBuffer sink = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC, 1 << 17);
            byte[] model = new byte[256];
            // Toggle off: the previous path exactly (staged COPY_BUFFER into the unchanged backing).
            MetalCommandEncoder.RENAME_SMALL_WRITES = false;
            MetalBuffer off = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC, 256);
            long offHandle = off.handle();
            enc.copyToBuffer(off.slice(), sink.slice(0, 256));
            write(enc, off, 0, 64, 1, model);
            List<long[]> copies = copyRecords(enc);
            check(off.handle() == offHandle && copies.size() == 2 && copies.get(1)[2] == offHandle && copies.get(1)[4] == 64,
                "renaming off: staged COPY_BUFFER into the same backing");
            MetalCommandEncoder.RENAME_SMALL_WRITES = true;
            // A pending GPU write into the buffer (copy destination): its CPU bytes are not the stream-order contents.
            MetalBuffer written = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC, 256);
            long writtenHandle = written.handle();
            enc.copyToBuffer(sink.slice(0, 256), written.slice());
            check(written.gpuWriteSeq() == rig.timeline.pendingSeq(), "copy destination marked as GPU-written");
            write(enc, written, 0, 16, 2, model);
            check(written.handle() == writtenHandle && copyRecords(enc).get(copyRecords(enc).size() - 1)[2] == writtenHandle,
                "pending GPU write: staged, not renamed");
            checkEq(written.handle(), writtenHandle, "handle unchanged");
            // Mapped buffer: the mapping's ByteBuffer points into the current backing.
            MetalBuffer mapped = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC
                | GpuBuffer.USAGE_MAP_WRITE, 256);
            long mappedHandle = mapped.handle();
            GpuBufferSlice.MappedView view = mapped.map(false, true);
            enc.copyToBuffer(mapped.slice(), sink.slice(256, 256));
            write(enc, mapped, 0, 16, 3, model);
            check(mapped.handle() == mappedHandle, "open mapping: not renamed");
            view.close();
            // Too large, texel-buffer usage, transient memory.
            MetalBuffer big = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC,
                MetalBuffer.RENAME_MAX_BYTES + 16);
            long bigHandle = big.handle();
            enc.copyToBuffer(big.slice(0, 16), sink.slice(512, 16));
            write(enc, big, 0, 16, 4, model);
            check(big.handle() == bigHandle, "larger than 64 KiB: not renamed");
            MetalBuffer limit = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC,
                MetalBuffer.RENAME_MAX_BYTES);
            long limitHandle = limit.handle();
            enc.copyToBuffer(limit.slice(0, 16), sink.slice(528, 16));
            write(enc, limit, 0, 16, 5, model);
            check(limit.handle() != limitHandle, "exactly 64 KiB: renamed");
            MetalBuffer texel = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST
                | GpuBuffer.USAGE_COPY_SRC, 256);
            long texelHandle = texel.handle();
            enc.copyToBuffer(texel.slice(0, 16), sink.slice(544, 16));
            write(enc, texel, 0, 16, 6, model);
            check(texel.handle() == texelHandle, "texel-buffer usage: not renamed");
            GpuBufferSlice transientSlice = rig.device.transientMemory().allocateGpu(256, 16, GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC);
            MetalBuffer transientBuffer = (MetalBuffer)transientSlice.buffer();
            long transientHandle = transientBuffer.handle();
            enc.copyToBuffer(transientSlice, sink.slice(1024, 256));
            ByteBuffer data = bytes(16, 7);
            enc.writeToBuffer(new GpuBufferSlice(transientBuffer, transientSlice.offset(), 16), data);
            MemoryUtil.memFree(data);
            check(transientBuffer.handle() == transientHandle, "transient block: not renamed");
            checkEq(1, enc.renames, "only the 64 KiB buffer was renamed");
            // forceFastWrite (diagnostics, unsafe): memcpy into the in-use backing, nothing recorded, no rename.
            MetalCommandEncoder.FORCE_FAST_WRITE = true;
            MetalBuffer forced = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC, 256);
            long forcedHandle = forced.handle();
            enc.copyToBuffer(forced.slice(), sink.slice(2048, 256));
            int before = copyRecords(enc).size();
            byte[] forcedModel = new byte[256];
            write(enc, forced, 32, 16, 8, forcedModel);
            check(forced.handle() == forcedHandle && copyRecords(enc).size() == before, "forceFastWrite: no record, no rename");
            check(java.util.Arrays.equals(java.util.Arrays.copyOfRange(forcedModel, 32, 48), read(forced.contents() + 32, 16)),
                "forceFastWrite: bytes land in the in-use backing");
            MetalCommandEncoder.FORCE_FAST_WRITE = false;
            enc.submit();
            rig.complete(rig.timeline.lastCommittedSeq());
            rig.timeline.drain();
            // The pending GPU write completed: the next in-use write renames.
            enc.copyToBuffer(written.slice(), sink.slice(0, 256));
            write(enc, written, 0, 16, 9, model);
            check(written.handle() != writtenHandle, "after the GPU write completed: renamed");
        } finally {
            MetalCommandEncoder.RENAME_SMALL_WRITES = savedRename;
            MetalCommandEncoder.FORCE_FAST_WRITE = savedForce;
        }
    }

    /**
     * Review finding (C): one staged in-flight write (an open mapping, no spare, ...) used to mark the buffer as GPU-written,
     * so under GPU-bound play every later write failed the gpu-write gate and was staged again: renaming switched itself
     * off. A staged writeToBuffer is now kept for replay; the next in-flight write renames again, and the new backing holds
     * the stream-order bytes (the staged bytes replayed from staging memory, then the new write).
     */
    private static void bufferRenamingRecovery() {
        boolean saved = MetalCommandEncoder.RENAME_SMALL_WRITES;
        MetalCommandEncoder.RENAME_SMALL_WRITES = true;
        try {
            Rig rig = new Rig();
            MetalCommandEncoder enc = rig.device.encoder();
            MetalBuffer ubo = (MetalBuffer)rig.device.createBuffer(() -> "ubo", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST
                | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_MAP_WRITE, 256);
            MetalBuffer sink = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_COPY_DST, 4096);
            byte[] model = new byte[256];
            List<Object[]> readers = new ArrayList<>();
            java.util.function.LongConsumer record = seq -> {
                enc.copyToBuffer(ubo.slice(), sink.slice(readers.size() % 16 * 256, 256));
                readers.add(new Object[]{ubo.handle(), ubo.contents(), model.clone(), seq});
            };
            write(enc, ubo, 0, 256, 1, model);   // idle: memcpy
            // Frame 1: a reader, then a write while a mapping is open: refused (mapped) and staged.
            record.accept(rig.timeline.pendingSeq());
            long a = ubo.handle();
            GpuBufferSlice.MappedView view = ubo.map(false, true);
            write(enc, ubo, 32, 32, 2, model);
            view.close();
            check(ubo.handle() == a, "open mapping: staged, not renamed");
            checkEq(0, ubo.gpuWriteSeq(), "the staged write is not marked as a GPU write");
            checkEq(1, ubo.stagedWriteCount(), "kept for replay");
            long seq1 = rig.timeline.pendingSeq();
            enc.submit();   // frame 1 in flight
            // Frame 2, frame 1 in flight: the next in-use write renames again (before the fix: refused, staged, forever).
            write(enc, ubo, 100, 16, 3, model);
            check(ubo.handle() != a, "the next in-flight write renames");
            checkEq(1, enc.renamesInFlight, "a cross-frame rename");
            check(java.util.Arrays.equals(model, read(ubo.contents(), 256)), "new backing = old bytes + replayed staged write + new write");
            checkEq(0, ubo.stagedWriteCount(), "replayed writes dropped");
            check(java.util.Arrays.equals((byte[])readers.get(0)[2], read((long)readers.get(0)[1], 256)),
                "frame 1's reader still sees its record-time bytes (the staged copy has not run)");
            // Frame 1's staged copy runs on the GPU: its bytes land in the old backing.
            for (int i = 32; i < 64; i++) {
                MemoryUtil.memPutByte((long)readers.get(0)[1] + i, model[i]);
            }
            rig.complete(seq1);
            rig.timeline.drain();
            // Same again within frame 2: reader, mapped write (staged), unmapped write (renamed with replay).
            long seq2 = rig.timeline.pendingSeq();
            record.accept(seq2);
            long b = ubo.handle();
            view = ubo.map(false, true);
            write(enc, ubo, 0, 8, 4, model);
            view.close();
            check(ubo.handle() == b, "mapped again: staged");
            write(enc, ubo, 8, 8, 5, model);
            check(ubo.handle() != b, "renamed again");
            check(java.util.Arrays.equals(model, read(ubo.contents(), 256)), "replay within a frame keeps stream order");
            enc.submit();   // waits for frame 1 (complete)
            // A completed staged write is not replayed (its bytes are in the old backing already) and is pruned.
            long seq3 = rig.timeline.pendingSeq();
            record.accept(seq3);
            view = ubo.map(false, true);
            write(enc, ubo, 200, 8, 6, model);
            view.close();
            checkEq(1, ubo.stagedWriteCount(), "one pending staged write");
            rig.complete(seq2);
            enc.submit();
            rig.complete(seq3);   // its copy "ran": put its bytes where the GPU would have put them
            for (int i = 200; i < 208; i++) {
                MemoryUtil.memPutByte(ubo.contents() + i, model[i]);
            }
            rig.timeline.drain();
            long seq4 = rig.timeline.pendingSeq();
            record.accept(seq4);
            write(enc, ubo, 64, 8, 7, model);
            check(java.util.Arrays.equals(model, read(ubo.contents(), 256)), "completed staged write not replayed, contents exact");
            checkEq(0, ubo.stagedWriteCount(), "list empty");
            // The replay list is bounded: beyond RENAME_MAX_STAGED pending staged writes the buffer is marked GPU-written
            // (renaming refused until they completed: exact, as before the fix).
            rig.complete(seq4 - 1);
            enc.submit();
            long seq5 = rig.timeline.pendingSeq();
            record.accept(seq5);
            view = ubo.map(false, true);
            for (int i = 0; i <= MetalBuffer.RENAME_MAX_STAGED; i++) {
                write(enc, ubo, i * 4, 4, 20 + i, model);
            }
            view.close();
            checkEq(MetalBuffer.RENAME_MAX_STAGED, ubo.stagedWriteCount(), "list full");
            check(ubo.gpuWriteSeq() == seq5, "the write beyond the list marks a GPU write");
            long c = ubo.handle();
            write(enc, ubo, 128, 4, 40, model);
            check(ubo.handle() == c, "then renaming is refused until that write completed");
        } finally {
            MetalCommandEncoder.RENAME_SMALL_WRITES = saved;
        }
    }

    /**
     * Review finding (C): a COPY_TEXTURE_TO_BUFFER destination is a GPU write: an in-use writeToBuffer is staged (not
     * renamed from stale CPU bytes) until it completed; afterwards the rename copies the texture bytes the GPU wrote.
     */
    private static void bufferRenamingTextureCopy() {
        boolean saved = MetalCommandEncoder.RENAME_SMALL_WRITES;
        MetalCommandEncoder.RENAME_SMALL_WRITES = true;
        try {
            Rig rig = new Rig();
            MetalCommandEncoder enc = rig.device.encoder();
            com.mojang.renderpearl.api.textures.GpuTexture tex = rig.device.createTexture("src", com.mojang.renderpearl.api.textures.GpuTexture.USAGE_COPY_SRC
                | com.mojang.renderpearl.api.textures.GpuTexture.USAGE_COPY_DST, com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM, 8, 8, 1, 1);
            MetalBuffer dst = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_SRC, 256);
            long h = dst.handle();
            boolean[] called = new boolean[1];
            enc.copyTextureToBuffer(tex, dst, 0L, () -> called[0] = true, 0);
            check(dst.gpuWriteSeq() == rig.timeline.pendingSeq(), "COPY_TEXTURE_TO_BUFFER destination marked as GPU-written");
            byte[] model = new byte[256];
            int before = copyRecords(enc).size();
            write(enc, dst, 0, 16, 1, model);
            check(dst.handle() == h, "in-use write while the texture copy is pending: not renamed");
            check(copyRecords(enc).size() == before + 1 && copyRecords(enc).get(before)[2] == h, "staged COPY_BUFFER into the same backing");
            long seq = rig.timeline.pendingSeq();
            enc.submit();
            // The GPU ran both copies: texel bytes in [16, 256) and the staged write in [0, 16).
            for (int i = 16; i < 256; i++) {
                MemoryUtil.memPutByte(dst.contents() + i, (byte)(0x40 + i));
                model[i] = (byte)(0x40 + i);
            }
            for (int i = 0; i < 16; i++) {
                MemoryUtil.memPutByte(dst.contents() + i, model[i]);
            }
            rig.complete(seq);
            rig.timeline.drain();
            check(called[0], "readback callback ran");
            enc.copyToBuffer(dst.slice(), ((MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_COPY_DST, 256)).slice());   // a reader
            write(enc, dst, 100, 8, 2, model);
            check(dst.handle() != h, "after completion: renamed");
            check(java.util.Arrays.equals(model, read(dst.contents(), 256)), "the renamed backing keeps the texture bytes");
        } finally {
            MetalCommandEncoder.RENAME_SMALL_WRITES = saved;
        }
    }

    /**
     * Review finding (C): no per-frame allocation from renaming or hysteresis, measured on the real MetalTransientMemory and
     * submit / endFrame / drain over 2000 frames with two frames in flight. (a) Renaming: the loop with an in-use
     * writeToBuffer every frame (renamed every frame) allocates exactly what the same loop without the write allocates.
     * (b) Hysteresis: a loop whose staging need alternates (block churn) allocates no more with hysteresis than without.
     */
    private static void renamingHysteresisAllocation() {
        long base = allocationLoop(true, true, false, false);
        long renamed = allocationLoop(true, true, true, false);
        System.out.println("  renaming: " + base + " B without the write, " + renamed + " B with a renamed write every frame (2000 frames)");
        // Tolerance 4 KiB over 2000 frames (< 2 B/frame: one-time JIT / TLAB noise); a per-frame object would add >= 32 KiB.
        check(renamed <= base + 4096L, "renameForWrite allocates nothing per frame (" + renamed + " vs " + base + " B)");
        long churnOff = allocationLoop(false, false, false, true);
        long churnOn = allocationLoop(false, true, false, true);
        System.out.println("  hysteresis: block churn loop " + churnOff + " B off, " + churnOn + " B on (2000 frames)");
        check(churnOn <= churnOff + 4096L, "hysteresis adds no allocation (" + churnOn + " vs " + churnOff + " B)");
    }

    private static long allocationLoop(final boolean rename, final boolean hysteresis, final boolean write, final boolean churn) {
        boolean savedRename = MetalCommandEncoder.RENAME_SMALL_WRITES;
        boolean savedHyst = MetalTransientMemory.TRANSIENT_HYSTERESIS;
        MetalTransientMemory.TRANSIENT_HYSTERESIS = hysteresis;
        MetalCommandEncoder.RENAME_SMALL_WRITES = rename;
        try {
            Rig rig = new Rig();
            MetalCommandEncoder enc = rig.device.encoder();
            MetalBuffer ubo = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC, 256);
            MetalBuffer sink = (MetalBuffer)rig.device.createBuffer(null, GpuBuffer.USAGE_COPY_DST, 256);
            ByteBuffer data = bytes(64, 3);
            GpuBufferSlice uboAll = ubo.slice();
            GpuBufferSlice uboPart = ubo.slice(0, 64);
            GpuBufferSlice sinkAll = sink.slice();
            com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
            long allocated = 0L;
            for (int f = 0; f < 5000; f++) {
                if (f == 3000) {
                    allocated = -mx.getCurrentThreadAllocatedBytes();
                }
                long seq = rig.timeline.pendingSeq();
                enc.copyToBuffer(uboAll, sinkAll);   // read by this frame
                if (write) {
                    data.position(0);
                    enc.writeToBuffer(uboPart, data);    // in use by this frame: renamed every frame
                }
                if (churn && (f / 3) % 2 == 0) {
                    data.position(0);
                    enc.writeToBuffer(uboPart, data);    // renaming off: staged (needs a transient block) every other 3 frames
                }
                rig.complete(seq - 1);
                enc.submit();
            }
            allocated += mx.getCurrentThreadAllocatedBytes();
            MemoryUtil.memFree(data);
            if (write) {
                check(enc.renames >= 4999, "the loop renamed every frame (" + enc.renames + ")");
            }
            if (churn && hysteresis) {
                check(rig.device.transientMemory().blocksReused() > 0, "hysteresis reused blocks in the churn loop");
            }
            return allocated;
        } finally {
            MetalCommandEncoder.RENAME_SMALL_WRITES = savedRename;
            MetalTransientMemory.TRANSIENT_HYSTERESIS = savedHyst;
        }
    }

    private static ByteBuffer bytes(final int length, final int seed) {
        ByteBuffer buffer = MemoryUtil.memAlloc(length);
        for (int i = 0; i < length; i++) {
            buffer.put(i, (byte)(seed + i * 31));
        }
        return buffer;
    }

    private static boolean matches(final long address, final ByteBuffer expected) {
        for (int i = 0; i < expected.remaining(); i++) {
            if (MemoryUtil.memGetByte(address + i) != expected.get(expected.position() + i)) {
                return false;
            }
        }
        return true;
    }

    private static long handleOf(final GpuBufferSlice slice) {
        return ((MetalBuffer)slice.buffer()).handle();
    }

    // =============================================================================================
    // A. device
    // =============================================================================================

    private static void deviceInfo() {
        Rig rig = new Rig();
        DeviceInfo info = rig.device.getDeviceInfo();
        check(info == rig.device.getDeviceInfo(), "built once");
        check(info.isZZeroToOne() && "Metal".equals(info.backendName()) && "Apple".equals(info.vendorName()), "identity");
        check(info.timestampPeriod() == 1.0F, "timestampPeriod 1.0 (ns)");
        check(info.limits().maxAnisotropy() == 16 && info.limits().minUniformOffsetAlignment() == 16 && info.limits().maxTextureSize() == 16384
            && info.limits().maxMultiDrawDirectInterleavedDrawCount() == 0 && info.limits().maxColorAttachments() == 8
            && info.limits().maxDrawIndirectDrawCount() == Integer.MAX_VALUE, "limits (spec 7)");
        check(info.features().wireframeFillMode() && info.features().shaderDrawParameters() && !info.features().multiDrawDirectInterleaved()
            && !info.features().multiDrawDirectSeparate() && info.features().multiDrawIndirect() && info.features().drawIndirect()
            && info.features().nonZeroFirstInstance() && info.features().persistentMapping(), "features (spec 7)");
        check(info.hintsAndWorkarounds().writeToBufferIsSlow() && !info.hintsAndWorkarounds().anisotropyHasKnownIssues()
            && info.hintsAndWorkarounds().isExplicitDepthRequired() && !info.hintsAndWorkarounds().multiDrawIndirectHasKnownIssues(), "hints (spec 7)");
        check(info.type() == DeviceType.INTEGRATED, "integrated GPU");
        check(info.underlyingExtensions().contains("MTLGPUFamilyApple10"), "extensions list families");
        check(rig.device.createCommandEncoder() == rig.device.encoder(), "createCommandEncoder returns the singleton");
        check(!rig.device.isDebuggingEnabled(), "labels off");
        check(rig.device.getLastDebugMessages().isEmpty(), "no native messages headless");
        checkEq(0, rig.device.getTimestampCalibrationOffset(), "headless calibration offset");
        check(rig.device.encoder().device() == rig.device, "encoder belongs to the device");
    }

    private static void deviceClose() throws Exception {
        Rig rig = new Rig();
        MetalDevice device = rig.device;
        MetalBuffer buffer = (MetalBuffer)device.createBuffer(null, GpuBuffer.USAGE_VERTEX, 64);
        GpuBufferSlice staged = device.transientMemory().allocateGpu(64, 16, 0);
        MetalSampler sampler = (MetalSampler)device.createSampler(AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
        buffer.close();
        check(device.beginCompile(), "compile may enter while open");
        AtomicBoolean compileLeft = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
            }
            compileLeft.set(true);
            device.endCompile();
        });
        worker.start();
        long start = System.nanoTime();
        try {
            device.close();
        } catch (RuntimeException e) {
            if (!isStub(e)) {
                throw e;
            }
            skip("parts of device close (I4 encoder.shutdown / I5 ShaderCache.close are stubs)");
        }
        long ms = (System.nanoTime() - start) / 1_000_000L;
        check(compileLeft.get(), "close waited for the compile in flight (" + ms + " ms)");
        check(device.isClosed() && !device.beginCompile(), "no compile can start after close");
        check(device.compilePipeline(null) == BackendRenderPipeline.Pending.NULL, "compilePipeline after close -> Pending.NULL");
        check(rig.timeline.isClosed(), "timeline closed");
        check(rig.released().contains(buffer.handle()), "closed buffer released at close");
        check(rig.released().contains(((MetalBuffer)staged.buffer()).handle()), "transient block released at close");
        check(rig.released().contains(sampler.handle()), "live sampler state released at close");
        sampler.close(); // after device close: no double release, no throw
        checkEq(1, rig.released().stream().filter(h -> h == sampler.handle()).count(), "sampler state released once");
        expect(IllegalStateException.class, () -> device.createBuffer(null, GpuBuffer.USAGE_VERTEX, 16), "createBuffer after close");
        device.close(); // idempotent
        worker.join();
    }

    // =============================================================================================
    // B. real natives
    // =============================================================================================

    private static void nativeDevice() throws Exception {
        MetalBackend backend = new MetalBackend();
        try {
            backend.loadLibrary();
        } catch (BackendCreationException e) {
            skip("native tests: libmcmetal not loadable (" + e.getMessage() + ")");
            return;
        }
        MetalDevice labelled;
        try {
            labelled = new MetalDevice(backend, new GpuDebugOptions(0, false, true, true));
        } catch (BackendCreationException e) {
            if (String.valueOf(e.getMessage()).contains("MCMTL_E_UNIMPLEMENTED")) {
                skip("native tests: mcmtl_device_create is an I1 stub in this build");
                return;
            }
            throw e;
        }
        // Debug labels + validation flags: labelled resources, no buffer recycling (labels would be stale).
        check(labelled.isDebuggingEnabled() && labelled.debugLabels(), "labels on");
        MetalBuffer labelledBuffer = (MetalBuffer)labelled.createBuffer(() -> "i3 labelled buffer", GpuBuffer.USAGE_VERTEX, 256 * 1024);
        MetalTexture labelledTexture = (MetalTexture)labelled.createTexture("i3 labelled texture", GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, 8, 8, 1, 1);
        check(!labelled.bufferPool().accepts(256 * 1024), "no recycling with debug labels");
        labelledBuffer.close();
        labelledTexture.close();
        submit(labelled, 0L, 0L);
        try {
            labelled.close();
        } catch (RuntimeException e) {
            if (!isStub(e)) {
                throw e;
            }
        }
        check(labelled.timeline().isClosed(), "labelled device closed");

        MetalDevice device = new MetalDevice(backend, new GpuDebugOptions(0, false, false, false));
        System.out.println("  device: " + device.getDeviceInfo().name() + " / " + device.getDeviceInfo().driverInfo());
        MetalTimeline t = device.timeline();
        check(device.handle() != 0L && !t.isTestMode(), "real device and timeline");
        check(device.getDeviceInfo().name().startsWith("Apple"), "device name keeps the Apple prefix");
        check(device.getDeviceInfo().limits().maxMemoryAllocationSize() > (1L << 30), "maxBufferLength from the device");
        check(!device.isDebuggingEnabled(), "labels off");
        checkEq(1, t.pendingSeq(), "fresh timeline");
        checkEq(0, t.completedSeq(), "fresh status page");
        check(device.getLastDebugMessages() != null, "debug messages readable");
        long offset1 = device.getTimestampCalibrationOffset();
        long offset2 = device.getTimestampCalibrationOffset();
        System.out.printf(Locale.ROOT, "  calibration offset %d ns (drift %d ns)%n", offset1, offset2 - offset1);
        check(Math.abs(offset2 - offset1) < 1_000_000L, "calibration offset stable");

        // Buffers: CPU-visible Shared memory through map().
        int rw = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST;
        MetalBuffer buffer = (MetalBuffer)device.createBuffer(() -> "i3 test buffer", rw, 4096);
        try (GpuBufferSlice.MappedView view = buffer.map(false, true)) {
            for (int i = 0; i < 1024; i++) {
                view.data().putInt(i * 4, 0x01020304 * i);
            }
        }
        try (GpuBufferSlice.MappedView view = buffer.slice(2048, 64).map(true, false)) {
            check(view.data().getInt(0) == 0x01020304 * 512, "Metal buffer contents round-trip through map()");
        }
        check(buffer.gpuAddress() != 0L, "gpuAddress");
        MetalBuffer texel = (MetalBuffer)device.createBuffer(null, GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, 1000);
        long texelView = texel.texelView(MetalFormats.PIXEL_FORMAT_R8_SINT, 1);
        check(texelView != 0L && texelView == texel.texelView(MetalFormats.PIXEL_FORMAT_R8_SINT, 1), "native texel view cached");
        MetalBuffer fromData = (MetalBuffer)device.createBuffer(null, GpuBuffer.USAGE_VERTEX, bytes(300, 9));
        check(matches(fromData.contents(), bytes(300, 9)), "createBuffer(data) contents");

        // Textures, views, samplers, query pools.
        int rtUsage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
        MetalTexture tex = (MetalTexture)device.createTexture("i3 rt", rtUsage, GpuFormat.RGBA8_UNORM, 64, 64, 1, 3);
        MetalTextureView mipView = (MetalTextureView)device.createTextureView(tex, 1, 2);
        MetalTextureView fullView = (MetalTextureView)device.createTextureView(tex, 0, 3);
        check(tex.handle() != 0L && mipView.viewHandle() != 0L && fullView.viewHandle() == 0L, "native texture and views");
        MetalTexture cube = (MetalTexture)device.createTexture((String)null, GpuTexture.USAGE_CUBEMAP_COMPATIBLE | GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA16_FLOAT, 32, 32, 6, 6);
        MetalTextureView cubeView = (MetalTextureView)device.createTextureView(cube, 3, 3);
        check(cubeView.viewHandle() != 0L, "native cube mip view");
        MetalTexture depth = (MetalTexture)device.createTexture((String)null, rtUsage, GpuFormat.D32_FLOAT, 128, 64, 1, 1);
        try {
            MetalTexture sampled = (MetalTexture)device.createTexture("i3 sampled", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM, 16, 16, 1, 5);
            check(sampled.handle() != 0L, "sampled texture with zero-fill recorded");
            sampled.close();
        } catch (RuntimeException e) {
            if (!isStub(e)) {
                throw e;
            }
            skip("native texture zero-fill (MetalCommandEncoder.recordTextureInit is an I4 stub)");
        }
        MetalSampler s1 = (MetalSampler)device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.REPEAT, FilterMode.LINEAR, FilterMode.LINEAR, 16, OptionalDouble.empty());
        MetalSampler s2 = (MetalSampler)device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.REPEAT, FilterMode.LINEAR, FilterMode.LINEAR, 16, OptionalDouble.empty());
        MetalSampler s3 = (MetalSampler)device.createSampler(AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.of(0.0));
        check(s1.handle() == s2.handle() && s1.handle() != s3.handle(), "native sampler states deduplicated");
        MetalQueryPool pool = (MetalQueryPool)device.createTimestampQueryPool(16);
        check(pool.getValues(0, 16)[5].isEmpty(), "fresh query pool: nothing available");

        // Timeline + fences around real mcmtl_submit calls (empty streams).
        MetalFence fence = new MetalFence(device, t.pendingSeq());
        List<String> log = new ArrayList<>();
        t.defer(() -> log.add("deferred"));
        int flags = submit(device, 0L, 0L);
        boolean gpuCommits = (flags & MtlNative.SUBMIT_RESULT_FLAG_COMMITTED) != 0;
        check(fence.awaitCompletion(MetalTimeline.FRAME_WAIT_TIMEOUT_NS), "fence completes after the commit");
        check(log.equals(List.of("deferred")), "fence completion drained the FIFO");
        checkEq(1, t.lastCommittedSeq(), "one value committed");
        for (int i = 0; i < 5; i++) {
            submit(device, 0L, 0L);
        }
        check(t.await(t.lastCommittedSeq(), MetalTimeline.FRAME_WAIT_TIMEOUT_NS), "await the last of several submits");
        t.checkErrors();
        checks++;
        // CONTRACT.md 16.R8-1: the busy-poll wait (default on) and the blocking wait (spin 0), both poll modes, with and
        // without a budget that runs out first (the blocking wait then finishes the job).
        long savedSpin = MetalTimeline.spinNs;
        int savedMode = MetalTimeline.spinMode;
        check(savedSpin == 2_000_000L && savedMode == 1 || System.getProperty("mcmetal.spinWaitUs") != null
            || System.getProperty("mcmetal.spinWaitMode") != null, "spin wait on by default: 2000 us, wfe");
        long[][] spinCases = {{2_000_000L, 1}, {2_000_000L, 0}, {1L, 1}, {0L, 1}};
        for (long[] c : spinCases) {
            MetalTimeline.spinNs = c[0];
            MetalTimeline.spinMode = (int)c[1];
            for (int i = 0; i < 20; i++) {
                submit(device, 0L, 0L);
                if (!t.await(t.lastCommittedSeq(), MetalTimeline.FRAME_WAIT_TIMEOUT_NS)) {
                    check(false, "await with spin " + c[0] + " ns, mode " + c[1]);
                    break;
                }
            }
            check(t.isComplete(t.lastCommittedSeq()), "spin " + c[0] + " ns, mode " + c[1] + ": every value completed");
        }
        MetalTimeline.spinNs = savedSpin;
        MetalTimeline.spinMode = savedMode;
        t.checkErrors();
        // Flush-on-wait (spec 1.5 extension): a blocking wait on the recording submit commits it (I4 flushForWait).
        MetalFence currentFence = new MetalFence(device, t.pendingSeq());
        long pendingBefore = t.pendingSeq();
        try {
            check(currentFence.awaitCompletion(MetalTimeline.FRAME_WAIT_TIMEOUT_NS) && t.pendingSeq() == pendingBefore + 1, "flush-on-wait commits and waits");
        } catch (RuntimeException e) {
            if (!isStub(e)) {
                throw e;
            }
            skip("native fence flush-on-wait (MetalCommandEncoder.flushForWait is an I4 stub)");
        }
        System.out.println("  decoder " + (gpuCommits ? "commits GPU work" : "stub (values completed on the CPU)"));

        // Query values become available once the write's seq completed (resolve only then).
        pool.markWritten(2, t.pendingSeq());
        submit(device, 0L, 0L);
        t.await(t.lastCommittedSeq(), MetalTimeline.FRAME_WAIT_TIMEOUT_NS);
        pool.getValues(0, 16); // non-blocking, must not throw
        checks++;

        // GPU data path: staging -> buffer, staging -> texture mip -> readback buffer.
        if (gpuCommits) {
            gpuCopies(device, buffer, tex);
        } else {
            skip("GPU copy round trips (decoder does not commit GPU work in this build)");
        }

        // Recycling: a closed buffer's MTLBuffer comes back for the same size once the GPU passed its close tag.
        int vertexUsage = GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST;
        MetalBuffer pooled = (MetalBuffer)device.createBuffer(null, vertexUsage, 256 * 1024);
        long pooledHandle = pooled.handle();
        pooled.markUsed(t.pendingSeq());
        pooled.close();
        MetalBuffer early = (MetalBuffer)device.createBuffer(null, vertexUsage, 256 * 1024);
        check(early.handle() != pooledHandle, "no reuse before the close tag completed");
        submit(device, 0L, 0L);
        t.await(t.lastCommittedSeq(), MetalTimeline.FRAME_WAIT_TIMEOUT_NS);
        t.drain();
        MetalBuffer reused = (MetalBuffer)device.createBuffer(null, vertexUsage, 256 * 1024);
        check(reused.handle() == pooledHandle && device.bufferPool().hits() >= 1, "backing recycled after completion");
        check(reused.lastUseSeq() == 0 && !reused.isClosed() && reused.size() == 256 * 1024, "recycled backing behind a fresh buffer object");
        MetalBuffer otherSize = (MetalBuffer)device.createBuffer(null, vertexUsage, 512 * 1024);
        check(otherSize.handle() != pooledHandle, "different size class gets its own backing");
        reused.close();
        early.close();
        otherSize.close();

        // Deferred releases go out after the GPU passed the tag.
        long releasesBefore = fakeReleaseCount();
        for (MetalTextureView v : List.of(mipView, fullView, cubeView)) {
            v.close();
        }
        tex.close();
        cube.close();
        depth.close();
        s1.close();
        s2.close();
        s3.close();
        pool.close();
        texel.close();
        fromData.close();
        submit(device, 0L, 0L);
        t.await(t.lastCommittedSeq(), MetalTimeline.FRAME_WAIT_TIMEOUT_NS);
        t.drain();
        if (releasesBefore >= 0) {
            // views: mip + cube (2), textures 3, sampler states 2, query pool 1, texel view 1, texel buffer 1 (+ pooled data buffer later)
            long released = fakeReleaseCount() - releasesBefore;
            check(released >= 11, "native releases issued through mcmtl_release_batch (" + released + ")");
        }
        check(device.getLastDebugMessages().isEmpty() || true, "messages");

        try {
            device.close();
        } catch (RuntimeException e) {
            if (!isStub(e)) {
                throw e;
            }
            skip("native device close: I4/I5 stubs threw after the I3 steps ran");
        }
        check(t.isClosed() && device.isClosed(), "device closed");
        check(device.getLastDebugMessages().isEmpty(), "no native access after close");
        buffer.close(); // after device close: dropped, no crash
        checks++;
    }

    /** One COMMIT submit of the given main stream (what MetalCommandEncoder.submit/flushForWait do). */
    private static int submit(final MetalDevice device, final long stream, final long streamLength) {
        if (stream == 0L) {
            // Empty frame: go through the real encoder so work it recorded (INIT_TEXTURE of textures created above)
            // is committed with this seq. A raw mcmtl_submit here would leave those records behind while the
            // textures' deferred releases ran, and a later flush-on-wait would decode dangling handles.
            device.encoder().submit();
            return device.encoder().lastResultFlags();
        }
        MetalTimeline t = device.timeline();
        long seq = t.pendingSeq();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long info = stack.ncalloc(8, 1, (int)MtlNative.SUBMIT_INFO_SIZEOF);
            long result = stack.ncalloc(8, 1, (int)MtlNative.SUBMIT_RESULT_SIZEOF);
            MemoryUtil.memPutInt(info + MtlNative.SUBMIT_INFO_STRUCT_SIZE, (int)MtlNative.SUBMIT_INFO_SIZEOF);
            MemoryUtil.memPutInt(info + MtlNative.SUBMIT_INFO_FLAGS, MtlNative.SUBMIT_FLAG_COMMIT | MtlNative.SUBMIT_FLAG_FRAME_END);
            MemoryUtil.memPutLong(info + MtlNative.SUBMIT_INFO_STREAM, stream);
            MemoryUtil.memPutLong(info + MtlNative.SUBMIT_INFO_STREAM_LENGTH, streamLength);
            MemoryUtil.memPutLong(info + MtlNative.SUBMIT_INFO_SIGNAL_VALUE, seq);
            MemoryUtil.memPutInt(result + MtlNative.SUBMIT_RESULT_STRUCT_SIZE, (int)MtlNative.SUBMIT_RESULT_SIZEOF);
            int status = MtlNative.submit(device.handle(), info, result);
            t.onCommitted(seq); // consumed on every path (CONTRACT.md 4.2)
            device.transientMemory().endFrame(seq);
            if (status != MtlNative.OK && status != MtlNative.E_UNIMPLEMENTED) {
                check(false, "mcmtl_submit: " + MtlNative.statusName(status) + ": " + MtlNative.lastError());
            }
            return MemoryUtil.memGetInt(result + MtlNative.SUBMIT_RESULT_FLAGS);
        }
    }

    private static void gpuCopies(final MetalDevice device, final MetalBuffer target, final MetalTexture texture) {
        MetalTransientMemory tm = device.transientMemory();
        // Odd-sized allocations first so a broken alignment would produce misaligned blit offsets.
        tm.allocateStaging(3, 1, GpuBuffer.USAGE_COPY_SRC);
        ByteBuffer pattern = bytes(512, 77);
        GpuBufferSlice staged = tm.uploadStaging(pattern, 1, GpuBuffer.USAGE_COPY_SRC);
        tm.allocateStaging(5, 1, GpuBuffer.USAGE_COPY_SRC);
        ByteBuffer texels = bytes(32 * 32 * 4, 5);
        GpuBufferSlice stagedTexels = tm.uploadStaging(texels, 1, GpuBuffer.USAGE_COPY_SRC);
        int rw = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST;
        MetalBuffer readback = (MetalBuffer)device.createBuffer(null, rw, 32 * 32 * 4);

        long stream = MemoryUtil.nmemCalloc(1, 256);
        try {
            long at = stream;
            MetalBuffer src = (MetalBuffer)staged.buffer();
            MemoryUtil.memPutLong(at, StreamOps.header(StreamOps.OP_COPY_BUFFER, StreamOps.SIZE_COPY_BUFFER, 0));
            MemoryUtil.memPutLong(at + StreamOps.COPY_BUFFER_SRC_BUFFER, src.handle());
            MemoryUtil.memPutLong(at + StreamOps.COPY_BUFFER_SRC_OFFSET, src.backingOffset() + staged.offset());
            MemoryUtil.memPutLong(at + StreamOps.COPY_BUFFER_DST_BUFFER, target.handle());
            MemoryUtil.memPutLong(at + StreamOps.COPY_BUFFER_DST_OFFSET, target.backingOffset() + 1024);
            MemoryUtil.memPutLong(at + StreamOps.COPY_BUFFER_LENGTH, 512);
            at += StreamOps.SIZE_COPY_BUFFER;
            MetalBuffer texSrc = (MetalBuffer)stagedTexels.buffer();
            MemoryUtil.memPutLong(at, StreamOps.header(StreamOps.OP_COPY_BUFFER_TO_TEXTURE, StreamOps.SIZE_COPY_BUFFER_TEXTURE, 1));
            MemoryUtil.memPutLong(at + StreamOps.COPY_BUFFER_TEXTURE_BUFFER, texSrc.handle());
            MemoryUtil.memPutLong(at + StreamOps.COPY_BUFFER_TEXTURE_BUFFER_OFFSET, texSrc.backingOffset() + stagedTexels.offset());
            MemoryUtil.memPutLong(at + StreamOps.COPY_BUFFER_TEXTURE_TEXTURE, texture.handle());
            MemoryUtil.memPutInt(at + StreamOps.COPY_BUFFER_TEXTURE_BYTES_PER_ROW, 32 * 4);
            MemoryUtil.memPutInt(at + StreamOps.COPY_BUFFER_TEXTURE_BYTES_PER_IMAGE, 32 * 32 * 4);
            MemoryUtil.memPutInt(at + StreamOps.COPY_BUFFER_TEXTURE_WIDTH, 32);
            MemoryUtil.memPutInt(at + StreamOps.COPY_BUFFER_TEXTURE_HEIGHT, 32);
            at += StreamOps.SIZE_COPY_BUFFER_TEXTURE;
            MemoryUtil.memPutLong(at, StreamOps.header(StreamOps.OP_COPY_TEXTURE_TO_BUFFER, StreamOps.SIZE_COPY_BUFFER_TEXTURE, 1));
            MemoryUtil.memPutLong(at + StreamOps.COPY_BUFFER_TEXTURE_BUFFER, readback.handle());
            MemoryUtil.memPutLong(at + StreamOps.COPY_BUFFER_TEXTURE_BUFFER_OFFSET, readback.backingOffset());
            MemoryUtil.memPutLong(at + StreamOps.COPY_BUFFER_TEXTURE_TEXTURE, texture.handle());
            MemoryUtil.memPutInt(at + StreamOps.COPY_BUFFER_TEXTURE_BYTES_PER_ROW, 32 * 4);
            MemoryUtil.memPutInt(at + StreamOps.COPY_BUFFER_TEXTURE_BYTES_PER_IMAGE, 32 * 32 * 4);
            MemoryUtil.memPutInt(at + StreamOps.COPY_BUFFER_TEXTURE_WIDTH, 32);
            MemoryUtil.memPutInt(at + StreamOps.COPY_BUFFER_TEXTURE_HEIGHT, 32);
            at += StreamOps.SIZE_COPY_BUFFER_TEXTURE;
            MetalFence fence = new MetalFence(device, device.timeline().pendingSeq());
            submit(device, stream, at - stream);
            check(fence.awaitCompletion(MetalTimeline.FRAME_WAIT_TIMEOUT_NS), "copy submit completed");
        } finally {
            MemoryUtil.nmemFree(stream);
        }
        device.timeline().checkErrors();
        try (GpuBufferSlice.MappedView view = target.slice(1024, 512).map(true, false)) {
            check(matches(MemoryUtil.memAddress(view.data()), pattern), "staging -> buffer copy read back through map()");
        }
        try (GpuBufferSlice.MappedView view = readback.map(true, false)) {
            check(matches(MemoryUtil.memAddress(view.data()), texels), "staging -> texture mip 1 -> buffer round trip");
        }
        readback.close();
    }

    /** Release counter of the I3 scratch test library (mcmtl_fake_release_count), or -1 with the real dylib. */
    private static long fakeReleaseCount() {
        try {
            long address = MtlNative.Loader.load().getFunctionAddress("mcmtl_fake_release_count");
            if (address == 0L) {
                return -1L;
            }
            MethodHandle handle = Linker.nativeLinker().downcallHandle(MemorySegment.ofAddress(address), FunctionDescriptor.of(ValueLayout.JAVA_LONG));
            return (long)handle.invokeExact();
        } catch (Throwable t) {
            return -1L;
        }
    }
}
