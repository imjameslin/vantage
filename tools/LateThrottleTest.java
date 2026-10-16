package com.mojang.renderpearl.backend.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDeviceLossException;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;

/**
 * Headless test of the late frame throttle (docs/metal/CONTRACT.md 16.R5-A, {@link MetalFrameThrottle}). Run with
 * tools/late-throttle-test.sh, which runs this class twice: with {@code -Dmcmetal.lateThrottle=false} (old path; the
 * toggle defaults to on since round 5) and with
 * {@code -Dmcmetal.lateThrottle=true -Dmcmetal.lateThrottle.strict=true}, and requires both runs to print the same
 * stream digest.
 *
 * <p>No native library: a {@link MetalDevice#forTesting} device over a test-mode {@link MetalTimeline} whose status page
 * the test writes ("GPU completion"), and a fake {@code mcmtl_submit} that records what each commit carries.
 *
 * <ul>
 *   <li>Both modes, first: a scripted 9-frame sequence that uses every encoder entry point (passes, clears, buffer and
 *       texture writes and copies, timestamps, INIT_TEXTURE, PRESENT, transient uploads, mappings, readback callbacks
 *       that record, a flush-on-wait). The off run does the "tick" work after submit()'s wait (old order); the on run does
 *       it before the throttle point (new order, several calls trip the guard). The fake GPU completes a timeline
 *       value only when the CPU blocks on it, asynchronously from its own thread (never as a side effect of a commit),
 *       so completion state, fast paths, recycling and drains depend on the waits the code really takes. The digest of
 *       every committed stream (init + main bytes, signal value, flags) and of the CPU-written buffer contents must be
 *       identical: the late throttle changes when the CPU waits, not what is recorded. Every frame-end commit happens
 *       with frame N-2 complete (at most 2 frames in flight). On: the same script with the throttle sabotaged (the
 *       armed wait cleared after every submit) must violate that invariant, which shows the check can fail.</li>
 *   <li>On: submit() does not wait; a write before the throttle point blocks until frame N-1 completes and then sees
 *       the same fast path / drained FIFO as the old order; the throttle point waits and is idempotent; every guarded
 *       entry point takes an armed wait and every unguarded one leaves it armed; guard calls from other threads never
 *       wait; timeout and device loss surface as before; the game hooks and counters.</li>
 *   <li>Off: submit() waits for frame N-1 itself (the old path), the throttle is inert, the game hooks are no-ops.</li>
 * </ul>
 */
public final class LateThrottleTest {
    private static int checks;
    private static int failures;
    private static final VarHandle LONG = ValueLayout.JAVA_LONG.varHandle();
    private static final VarHandle INT = ValueLayout.JAVA_INT.varHandle();
    private static final int TEX_USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING;
    private static final int RT_USAGE = TEX_USAGE | GpuTexture.USAGE_RENDER_ATTACHMENT;

    public static void main(final String[] args) {
        boolean late = MetalFrameThrottle.ENABLED;
        System.out.println("mode: " + (late ? "late throttle ON" : "late throttle OFF (old path)") + (MetalFrameThrottle.STRICT ? ", strict" : ""));
        // First in both modes: synthetic handles come from one global counter, so the digests are comparable.
        section("scripted frames: committed streams and CPU writes", LateThrottleTest::scriptedFrames);
        if (late) {
            section("on: the scripted-frames checks fail when the throttle never waits (sabotage)", LateThrottleTest::scriptedFramesSabotaged);
            section("on: commit() never runs callbacks (no re-entrant commit)", LateThrottleTest::lateNoReentrantCommit);
            section("on: GPU failures stay fatal (recorded, rethrown at the frame point)", LateThrottleTest::lateStickyFailures);
            section("on: the throttle point reports whether a wait blocked (input pump)", LateThrottleTest::lateBlockedFlag);
            section("on: submit arms, a write before the throttle point waits", LateThrottleTest::lateWriteWaits);
            section("on: the throttle point waits once", LateThrottleTest::lateThrottlePoint);
            section("on: every guarded entry point takes the wait, unguarded ones do not", LateThrottleTest::lateGuardCoverage);
            section("on: guard calls from other threads never wait", LateThrottleTest::lateOtherThread);
            section("on: timeout and device loss", LateThrottleTest::lateErrors);
            section("on: game hooks and counters", LateThrottleTest::lateHooksAndStats);
        } else {
            section("off: submit waits for the previous frame (old path)", LateThrottleTest::offOldPath);
            section("off: throttle and game hooks are inert", LateThrottleTest::offInert);
        }
        System.out.printf(Locale.ROOT, "%n%d checks, %d failures%n", checks, failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    // =============================================================================================
    // Harness
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

    private static ByteBuffer bytes(final int length, final int seed) {
        ByteBuffer b = MemoryUtil.memAlloc(length);
        for (int i = 0; i < length; i++) {
            b.put(i, (byte)(seed * 31 + i * 7));
        }
        return b;
    }

    /** Headless device, test-mode timeline over heap memory, fake mcmtl_submit that logs every commit. */
    private static final class Rig {
        final MemorySegment page = MemorySegment.ofArray(new long[(int)(MtlNative.STATUS_PAGE_SIZEOF / 8)]);
        final MetalTimeline timeline = new MetalTimeline(0L, this.page);
        final MetalDevice device = MetalDevice.forTesting(0L, this.page, this.timeline, null, false);
        final MetalCommandEncoder encoder = this.device.encoder();
        final MetalFrameThrottle throttle = this.device.frameThrottle();
        /** Per commit: signal value, flags, completedSeq at the commit, main stream bytes. */
        final List<long[]> commits = new ArrayList<>();
        /** Fake GPU: committing value s completes s - 1 (frame ends) or s itself (flush-on-wait). */
        boolean gpuFollowsCommits;
        /** Lazy fake GPU ({@link #lazyGpu}): the largest value a blocked wait asked for; its thread completes it. */
        final java.util.concurrent.atomic.AtomicLong gpuRequested = new java.util.concurrent.atomic.AtomicLong();
        int gpuBadRequests;
        boolean hashStreams;
        long digest = 0xCBF29CE484222325L;

        Rig() {
            this.encoder.submitFunction = (dev, info, result) -> {
                long seq = MemoryUtil.memGetLong(info + MtlNative.SUBMIT_INFO_SIGNAL_VALUE);
                int flags = MemoryUtil.memGetInt(info + MtlNative.SUBMIT_INFO_FLAGS);
                long mainLength = MemoryUtil.memGetLong(info + MtlNative.SUBMIT_INFO_STREAM_LENGTH);
                this.commits.add(new long[]{seq, flags, this.timeline.completedSeq(), mainLength});
                if (this.hashStreams) {
                    this.hashLong(seq);
                    this.hashLong(flags);
                    this.hashRange(MemoryUtil.memGetLong(info + MtlNative.SUBMIT_INFO_INIT_STREAM), MemoryUtil.memGetLong(info + MtlNative.SUBMIT_INFO_INIT_STREAM_LENGTH));
                    this.hashRange(MemoryUtil.memGetLong(info + MtlNative.SUBMIT_INFO_STREAM), mainLength);
                }
                if (this.gpuFollowsCommits) {
                    this.complete((flags & MtlNative.SUBMIT_FLAG_FRAME_END) != 0 ? seq - 1 : seq);
                }
                MemoryUtil.memPutInt(result + MtlNative.SUBMIT_RESULT_FLAGS, MtlNative.SUBMIT_RESULT_FLAG_COMMITTED);
                return MtlNative.OK;
            };
            this.encoder.frameWaitTimeoutNs = 3_000_000_000L;
        }

        /**
         * Review fix (A, test strength): a GPU that completes a timeline value only when the CPU blocks waiting for it, from
         * its own thread after a short delay (asynchronously, as a real GPU), never because something was committed. A
         * code path that skips a wait therefore sees older completion state than one that waits, and the invariant and
         * digest checks can fail. Deterministic: values complete only while a waiter blocks on them.
         */
        void lazyGpu() {
            this.timeline.testWaitHook = seq -> {
                if (seq > this.timeline.lastCommittedSeq()) {
                    this.gpuBadRequests++;   // a wait for work that was never committed would hang a real GPU
                }
                this.gpuRequested.accumulateAndGet(seq, Math::max);
            };
            Thread gpu = new Thread(() -> {
                while (true) {
                    long want = this.gpuRequested.get();
                    if (want > (long)LONG.getVolatile(this.page, MtlNative.STATUS_PAGE_COMPLETED_VALUE)) {
                        java.util.concurrent.locks.LockSupport.parkNanos(200_000L);   // "GPU work" finishing
                        this.complete(want);
                    } else {
                        java.util.concurrent.locks.LockSupport.parkNanos(20_000L);
                    }
                }
            }, "fake GPU");
            gpu.setDaemon(true);
            gpu.start();
        }

        void complete(final long value) {
            long current = (long)LONG.getVolatile(this.page, MtlNative.STATUS_PAGE_COMPLETED_VALUE);
            if (value > current) {
                LONG.setVolatile(this.page, MtlNative.STATUS_PAGE_COMPLETED_VALUE, value);
            }
        }

        void completeAll() {
            this.complete(this.timeline.lastCommittedSeq());
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

        void hashLong(final long v) {
            for (int i = 0; i < 8; i++) {
                this.digest = (this.digest ^ ((v >>> (8 * i)) & 0xFF)) * 0x100000001B3L;
            }
        }

        /** Synthetic headless handles (MetalBuffer.Headless, tag 0x7E57 in the top bits) -> order of first appearance. */
        final java.util.Map<Long, Long> handleOrdinals = new java.util.HashMap<>();

        /**
         * Stream bytes, with every 8-byte word that is a synthetic handle replaced by the order in which handles first
         * appear in the hashed streams: the late order may create an object (e.g. a transient block in a deferred callback
         * that now runs at a guard after the tick's createTexture) in a different order relative to another one, which
         * only renumbers the global handle counter; what is recorded, and which object each record names, must not differ.
         */
        void hashRange(final long address, final long length) {
            this.hashLong(length);
            long i = 0;
            for (; i + 8 <= length; i += 8) {
                long word = MemoryUtil.memGetLong(address + i);
                if ((word >>> 48) == 0x7E57L) {
                    word = 0x4A4E_0000_0000_0000L | this.handleOrdinals.computeIfAbsent(word, k -> (long)this.handleOrdinals.size());
                }
                this.hashLong(word);
            }
            for (; i < length; i++) {
                this.digest = (this.digest ^ (MemoryUtil.memGetByte(address + i) & 0xFF)) * 0x100000001B3L;
            }
        }

        MetalSurface surface() {
            return MetalSurface.forTesting(this.device, 42L, () -> false, new FakeSurfaceNatives());
        }
    }

    /** MetalSurface platform calls that do nothing. */
    private static final class FakeSurfaceNatives implements MetalSurface.Natives {
        @Override
        public long createView(final long window) {
            return 0x5D10_0000_0000L;
        }

        @Override
        public long getLayer(final long view) {
            return 0xCA00_0000_0000L;
        }

        @Override
        public void destroyView(final long view) {
        }

        @Override
        public String lastSdlError() {
            return "";
        }

        @Override
        public long windowFlags(final long window) {
            return 0L;
        }

        @Override
        public int surfaceCreate(final long device, final long layer, final long outSurface) {
            MemoryUtil.memPutLong(outSurface, 0x5F00_0000_0000L);
            return MtlNative.OK;
        }

        @Override
        public int surfaceConfigure(final long surface, final long config) {
            return MtlNative.OK;
        }

        @Override
        public int surfacePresent(final long surface) {
            return MtlNative.OK;
        }

        @Override
        public int surfaceDestroy(final long surface) {
            return MtlNative.OK;
        }
    }

    /** The resources of the scripted frames (and of the coverage test). */
    private static final class Scene {
        final MetalDevice device;
        final GpuTexture color;
        final GpuTexture depth;
        final GpuTexture atlas;
        final GpuTexture atlas2;
        final GpuTextureView colorView;
        final GpuTextureView depthView;
        final GpuBuffer uniform;
        final GpuBuffer idle;
        final GpuBuffer mapped;
        final GpuBuffer readback;
        final GpuBuffer source;
        final GpuQueryPool queries;
        final MetalSurface surface;

        Scene(final Rig rig) {
            this.device = rig.device;
            this.color = this.device.createTexture("color", RT_USAGE, GpuFormat.RGBA8_UNORM, 64, 32, 1, 1);
            this.depth = this.device.createTexture("depth", GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.D32_FLOAT, 64, 32, 1, 1);
            this.atlas = this.device.createTexture("atlas", TEX_USAGE, GpuFormat.RGBA8_UNORM, 32, 32, 1, 1); // INIT_TEXTURE
            this.atlas2 = this.device.createTexture("atlas2", RT_USAGE, GpuFormat.RGBA8_UNORM, 32, 32, 1, 1);
            this.colorView = this.device.createTextureView(this.color, 0, 1);
            this.depthView = this.device.createTextureView(this.depth, 0, 1);
            this.uniform = this.device.createBuffer(null, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, 256);
            this.idle = this.device.createBuffer(null, GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC, 256);
            this.mapped = this.device.createBuffer(null, GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM, 128);
            this.readback = this.device.createBuffer(null, GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 64 * 32 * 4);
            this.source = this.device.createBuffer(null, GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST, 32 * 32 * 4);
            this.queries = this.device.createTimestampQueryPool(8);
            this.surface = rig.surface();
        }

        RenderPassDescriptor passDescriptor() {
            return RenderPassDescriptor.builder(() -> "main")
                .withColorAttachment(this.colorView, Optional.<Vector4fc>of(new Vector4f(0.1F, 0.2F, 0.3F, 1.0F)))
                .withDepthAttachment(this.depthView, OptionalDouble.of(1.0))
                .withRenderArea(new RenderPass.RenderArea(0, 0, 64, 32))
                .build();
        }
    }

    // =============================================================================================
    // Both modes: scripted frames, identical streams
    // =============================================================================================

    private static void scriptedFrames() throws Exception {
        long[] r = runScript(false);
        System.out.printf(Locale.ROOT, "  DECISION-DIGEST %016x (handle-independent: stream sizes, completion at commits, CPU writes, callbacks)%n", r[1]);
        decisionDigest = r[1];
        streamDigest = r[0];
    }

    private static long decisionDigest;
    private static long streamDigest;
    private static java.lang.reflect.Field pendingSeqField;

    /**
     * Review fix (A): the scripted frames with the throttle sabotaged (the reviewer's mutation: the armed wait is cleared
     * right after every submit, so nothing ever waits for frame N-1). The invariant check must report violations and
     * the handle-independent decision digest must differ, i.e. the scripted-frames checks can fail on a throttle bug.
     */
    private static void scriptedFramesSabotaged() throws Exception {
        long[] r = runScript(true);
        System.out.printf(Locale.ROOT, "  sabotaged: %d frame-end commits without frame N-2 complete, stream digest %016x (normal %016x), decision digest %016x (normal %016x)%n",
            r[2], r[0], streamDigest, r[1], decisionDigest);
        check(r[2] > 0, "sabotaged throttle: the at-most-2-frames-in-flight check fails (" + r[2] + " violations)");
        check(r[0] != streamDigest, "sabotaged throttle: the stream digest differs (fast paths / recycling see older completion state)");
        check(r[1] != decisionDigest, "sabotaged throttle: the decision digest differs");
    }

    /** Returns {stream digest, decision digest, frame-end commits without frame N-2 complete}. */
    private static long[] runScript(final boolean sabotage) throws Exception {
        boolean late = MetalFrameThrottle.ENABLED;
        Rig rig = new Rig();
        rig.lazyGpu();
        rig.hashStreams = true;
        long decision = 0xCBF29CE484222325L;
        if (sabotage && pendingSeqField == null) {
            pendingSeqField = MetalFrameThrottle.class.getDeclaredField("pendingSeq");
            pendingSeqField.setAccessible(true);
        }
        Scene s = new Scene(rig);
        MetalCommandEncoder enc = rig.encoder;
        TransientMemory transients = enc.transientMemory();
        List<String> callbacks = new ArrayList<>();
        List<Long> frameSeqs = new ArrayList<>();
        List<GpuFence> fences = new ArrayList<>();
        int frames = 9;
        long tripsBefore = rig.throttle.tripCount();
        for (int f = 1; f <= frames; f++) {
            final int frame = f;
            // ---- "client tick": packets, tasks, tick(). Old order: after submit()'s wait; late order: before the throttle.
            if (f % 3 == 2) {
                enc.writeToTexture(s.atlas, bytes(8 * 8 * 4, f), 0, 0, 0, 0, 8, 8); // e.g. a map/skin texture upload
            }
            if (f == 4) {
                rig.device.createTexture("late " + f, TEX_USAGE, GpuFormat.RGBA8_UNORM, 4, 4, 1, 1); // INIT_TEXTURE
            }
            if (f == 7) {
                // A fence wait on the current value from game code: flush-on-wait commit.
                GpuFence now = enc.createFence();
                check(now.awaitCompletion(1_000_000_000L), "flush-on-wait fence completes");
            }
            // ---- throttle point (Minecraft.runTick), then the texture tick and renderFrame.
            if (late) {
                rig.throttle.throttle();
                check(sabotage || rig.throttle.pendingSeq() == 0L, "frame " + f + ": nothing armed after the throttle point");
            }
            enc.writeToTexture(s.atlas, bytes(8 * 8 * 4, 100 + f), 0, 0, 8, 8, 8, 8); // animation pass stand-in
            enc.writeToBuffer(s.uniform.slice(0L, 64L), bytes(64, 200 + f)); // in use by frame f-1: staged copy
            enc.writeToBuffer(s.idle.slice(64L, 32L), bytes(32, 300 + f)); // idle: CPU fast path
            try (GpuBufferSlice.MappedView view = s.mapped.map(false, true)) {
                view.data().put(f % 128, (byte)f);
            }
            GpuBufferSlice vertices = transients.uploadGpu(bytes(48, 400 + f), 16L, GpuBuffer.USAGE_VERTEX);
            List<GpuBufferSlice> multi = transients.multiUploadGpu(List.of(bytes(20, f), bytes(36, f + 1)), 4L, GpuBuffer.USAGE_INDEX);
            check(multi.size() == 2, "multi upload");
            GpuBufferSlice.MappedView staged = transients.allocateStaging(64L, 16L, GpuBuffer.USAGE_COPY_SRC);
            staged.data().put(0, (byte)f);
            if (f >= 4) {
                GpuFence old = fences.get(f - 4);
                check(old.awaitCompletion(-1L), "ring-buffer fence of frame " + (f - 3));
            }
            RenderPassBackend pass = enc.createRenderPass(s.passDescriptor());
            pass.setVertexBuffer(0, s.uniform.slice());
            pass.setVertexBuffer(1, vertices);
            pass.enableScissor(0, 0, 16, 16);
            pass.disableScissor();
            pass.writeTimestamp(s.queries, 0);
            enc.submitRenderPass();
            enc.copyToBuffer(s.idle.slice(0L, 64L), s.uniform.slice(64L, 64L));
            enc.copyToBuffer(staged.slice(), s.uniform.slice(128L, 64L));
            enc.copyBufferToTexture(s.source.slice(), 1, 1, 32, 32, s.atlas2, 0, 0, 4, 4, 0, 0);
            enc.copyTextureToTexture(s.atlas, s.atlas2, 0, 8, 8, 0, 0, 8, 8);
            enc.writeTimestamp(s.queries, 1);
            if (f % 4 == 2) {
                enc.copyTextureToBuffer(s.color, s.readback, 0L, () -> {
                    callbacks.add("readback " + frame);
                    // A callback that records: runs in a drain (old: after submit's wait; late: submit's drain or a trip).
                    enc.writeToBuffer(s.uniform.slice(192L, 16L), bytes(16, 500 + frame));
                }, 0);
            }
            if (f == 5) {
                enc.copyTextureToBuffer(s.color, s.readback, 16L, () -> callbacks.add("region " + frame), 0, 2, 2, 4, 4);
            }
            enc.clearColorTexture(s.atlas2, new Vector4f(0.5F, 0.5F, 0.5F, 1.0F));
            enc.clearColorAndDepthTextures(s.color, new Vector4f(0.0F, 0.0F, 0.0F, 1.0F), s.depth, 1.0);
            enc.clearColorAndDepthTextures(s.color, new Vector4f(1.0F, 0.0F, 0.0F, 1.0F), s.depth, 0.5, 4, 4, 8, 8, 0);
            enc.clearDepthTexture(s.depth, 1.0);
            s.surface.acquireNextTexture();
            s.surface.blitFromTexture(enc, (MetalTextureView)s.colorView);
            long seq = rig.timeline.pendingSeq();
            enc.submit();
            frameSeqs.add(seq);
            if (sabotage) {
                pendingSeqField.setLong(rig.throttle, 0L);   // the mutation: never wait for frame N-1
                s.surface.present();
                fences.add(enc.createFence());
                continue;
            }
            s.surface.present();
            fences.add(enc.createFence()); // MappableRingBuffer.rotate() after submit: not guarded, same value in both modes
            if (late) {
                // (With the lazy GPU nothing completes inside submit(): frame 2's readback callback runs at frame 4's first
                // guard trip in the late order, in submit(3)'s drain in the old one; recorded streams are the same.)
                checkEq(f == 1 ? 0 : frameSeqs.get(f - 2), rig.throttle.pendingSeq(), "frame " + f + ": submit arms frame N-1");
            }
        }
        // At most two frames in flight: every frame-end commit of frame N happens with frame N-2 complete. The lazy GPU
        // completes nothing on commit, so this holds only if the code waited for N-2 before committing N.
        int frameIndex = 0;
        long violations = 0;
        for (long[] c : rig.commits) {
            for (long v : new long[]{c[3], c[2], c[1] & ~0L}) {
                for (int i = 0; i < 8; i++) {
                    decision = (decision ^ ((v >>> (8 * i)) & 0xFF)) * 0x100000001B3L;
                }
            }
            if ((c[1] & MtlNative.SUBMIT_FLAG_FRAME_END) == 0) {
                continue;
            }
            if (frameIndex >= 2) {
                boolean ok = c[2] >= frameSeqs.get(frameIndex - 2);
                if (!ok) {
                    violations++;
                }
                if (!sabotage) {
                    check(ok, "frame " + (frameIndex + 1) + " committed with frame " + (frameIndex - 1) + " complete (completed " + c[2] + ")");
                }
            }
            frameIndex++;
        }
        if (!sabotage) {
            checkEq(frames, frameIndex, "frame-end commits");
            check(rig.commits.size() == frames + 1, "one flush-on-wait commit besides the frames (" + rig.commits.size() + ")");
            checkEq(0, rig.gpuBadRequests, "every wait was for committed work");
        }
        // CPU-written memory and callback order are part of the digest.
        rig.hashRange(((MetalBuffer)s.idle).contents(), 256);
        rig.hashRange(((MetalBuffer)s.mapped).contents(), 128);
        for (String c : callbacks) {
            rig.hashLong(c.hashCode());
        }
        rig.hashLong(rig.device.transientMemory().blocksCreated());
        long[] cpu = {checksumOf(((MetalBuffer)s.idle).contents(), 256), checksumOf(((MetalBuffer)s.mapped).contents(), 128),
            callbacks.hashCode(), rig.device.transientMemory().blocksCreated(), rig.device.transientMemory().blocksFreed()};
        for (long v : cpu) {
            for (int i = 0; i < 8; i++) {
                decision = (decision ^ ((v >>> (8 * i)) & 0xFF)) * 0x100000001B3L;
            }
        }
        if (sabotage) {
            return new long[]{rig.digest, decision, violations};
        }
        check(callbacks.size() >= 3, "readback callbacks ran (" + callbacks + ")");
        long trips = rig.throttle.tripCount() - tripsBefore;
        if (late) {
            check(trips >= 4, "late order: tick-phase uploads, INIT_TEXTURE, flush and recording callbacks tripped the guard (" + trips + ")");
        } else {
            checkEq(0, trips, "old path: no guard trips");
        }
        System.out.printf(Locale.ROOT, "  STREAM-DIGEST %016x commits=%d callbacks=%s trips=%d%n", rig.digest, rig.commits.size(), callbacks, trips);
        return new long[]{rig.digest, decision, violations};
    }

    private static long checksumOf(final long address, final long length) {
        long sum = 0xCBF29CE484222325L;
        for (long i = 0; i < length; i++) {
            sum = (sum ^ (MemoryUtil.memGetByte(address + i) & 0xFF)) * 0x100000001B3L;
        }
        return sum;
    }

    // =============================================================================================
    // Late throttle on
    // =============================================================================================

    private static void lateWriteWaits() {
        Rig rig = new Rig();
        Scene s = new Scene(rig);
        MetalCommandEncoder enc = rig.encoder;
        boolean[] ranTag1 = new boolean[1];
        rig.timeline.defer(() -> ranTag1[0] = true); // tagged 1: runs once frame 1 completed
        enc.clearColorTexture(s.color, new Vector4f());
        enc.submit(); // frame 1 (seq 1)
        checkEq(0, rig.throttle.pendingSeq(), "first frame arms nothing");
        enc.clearColorTexture(s.color, new Vector4f());
        long start = System.nanoTime();
        enc.submit(); // frame 2 (seq 2): must not wait for seq 1, which never completes on its own here
        long submitMs = (System.nanoTime() - start) / 1_000_000L;
        check(submitMs < 500, "submit() returns without waiting (" + submitMs + " ms)");
        checkEq(1, rig.throttle.pendingSeq(), "submit arms frame N-1");
        checkEq(0, rig.timeline.completedSeq(), "frame 1 still running");
        check(!ranTag1[0], "frame 1's deferred entry has not run");
        long trips = rig.throttle.tripCount();
        int streamBefore = (int)enc.mainStream().size();
        rig.completeLater(1, 150);
        start = System.nanoTime();
        ByteBuffer data = bytes(16, 7);
        enc.writeToBuffer(s.idle.slice(0L, 16L), data); // the write before the throttle point
        long waitedMs = (System.nanoTime() - start) / 1_000_000L;
        check(waitedMs >= 120, "the write waited for frame N-1 (" + waitedMs + " ms)");
        check(rig.timeline.completedSeq() >= 1, "frame N-1 complete when the write proceeds");
        checkEq(0, rig.throttle.pendingSeq(), "wait taken");
        checkEq(trips + 1, rig.throttle.tripCount(), "one guard trip");
        check(ranTag1[0], "the FIFO drained after the wait, as in the old submit()");
        checkEq(streamBefore, enc.mainStream().size(), "idle buffer: CPU fast path, nothing recorded (as after the old wait)");
        boolean same = true;
        for (int i = 0; i < 16; i++) {
            same &= MemoryUtil.memGetByte(((MetalBuffer)s.idle).contents() + i) == data.get(i);
        }
        check(same, "the bytes landed in the buffer");
        rig.completeAll();
        rig.throttle.throttle();
    }

    private static void lateThrottlePoint() {
        Rig rig = new Rig();
        Scene s = new Scene(rig);
        MetalCommandEncoder enc = rig.encoder;
        enc.submit();
        enc.clearColorTexture(s.color, new Vector4f());
        enc.submit();
        long trips = rig.throttle.tripCount();
        long[] before = MetalFrameThrottle.stats();
        rig.completeLater(1, 100);
        long start = System.nanoTime();
        rig.throttle.throttle();
        long waitedMs = (System.nanoTime() - start) / 1_000_000L;
        check(waitedMs >= 80, "throttle point waited for frame N-1 (" + waitedMs + " ms)");
        checkEq(0, rig.throttle.pendingSeq(), "nothing armed afterwards");
        checkEq(trips, rig.throttle.tripCount(), "the throttle point is not a guard trip");
        long[] after = MetalFrameThrottle.stats();
        checkEq(before[MetalFrameThrottle.STAT_BLOCKED_WAITS] + 1, after[MetalFrameThrottle.STAT_BLOCKED_WAITS], "one blocking wait counted");
        check(after[MetalFrameThrottle.STAT_WAIT_NS] - before[MetalFrameThrottle.STAT_WAIT_NS] >= 80_000_000L, "wait time counted");
        start = System.nanoTime();
        rig.throttle.throttle();
        enc.clearColorTexture(s.color, new Vector4f());
        check((System.nanoTime() - start) / 1_000_000L < 50, "second throttle and later records do not wait");
        checkEq(trips, rig.throttle.tripCount(), "no trip after the throttle point");
        rig.completeAll();
    }

    /** Arms a wait for a frame that is already complete, so a guard takes it without blocking. */
    private static void armCompleted(final Rig rig) {
        rig.completeAll();
        rig.throttle.throttle();
        rig.encoder.submit();
        rig.completeAll();
        check(rig.throttle.pendingSeq() != 0L, "armed");
    }

    private static void guarded(final Rig rig, final String what, final Body op) throws Exception {
        armCompleted(rig);
        long trips = rig.throttle.tripCount();
        op.run();
        checkEq(0, rig.throttle.pendingSeq(), what + ": takes the armed wait");
        checkEq(trips + 1, rig.throttle.tripCount(), what + ": one guard trip");
    }

    private static void unguarded(final Rig rig, final String what, final Body op) throws Exception {
        armCompleted(rig);
        long pending = rig.throttle.pendingSeq();
        long trips = rig.throttle.tripCount();
        op.run();
        checkEq(pending, rig.throttle.pendingSeq(), what + ": leaves the wait armed");
        checkEq(trips, rig.throttle.tripCount(), what + ": no trip");
    }

    private static void lateGuardCoverage() throws Exception {
        Rig rig = new Rig();
        Scene s = new Scene(rig);
        MetalCommandEncoder enc = rig.encoder;
        TransientMemory transients = enc.transientMemory();
        enc.submit();
        // CommandEncoderBackend
        guarded(rig, "createRenderPass", () -> {
            enc.createRenderPass(s.passDescriptor());
            enc.submitRenderPass();
        });
        guarded(rig, "clearColorTexture", () -> enc.clearColorTexture(s.atlas2, new Vector4f()));
        guarded(rig, "clearColorAndDepthTextures", () -> enc.clearColorAndDepthTextures(s.color, new Vector4f(), s.depth, 1.0));
        guarded(rig, "clearColorAndDepthTextures(region)", () -> enc.clearColorAndDepthTextures(s.color, new Vector4f(), s.depth, 1.0, 0, 0, 4, 4, 0));
        guarded(rig, "clearDepthTexture", () -> enc.clearDepthTexture(s.depth, 1.0));
        guarded(rig, "writeToBuffer (idle buffer, fast path)", () -> enc.writeToBuffer(s.idle.slice(0L, 8L), bytes(8, 1)));
        guarded(rig, "writeToBuffer (in-use buffer, staged copy)", () -> {
            ((MetalBuffer)s.uniform).markUsed(rig.timeline.pendingSeq());
            enc.writeToBuffer(s.uniform.slice(0L, 8L), bytes(8, 2));
        });
        guarded(rig, "copyToBuffer", () -> enc.copyToBuffer(s.idle.slice(0L, 16L), s.uniform.slice(16L, 16L)));
        guarded(rig, "writeToTexture", () -> enc.writeToTexture(s.atlas, bytes(16, 3), 0, 0, 0, 0, 2, 2));
        guarded(rig, "copyBufferToTexture", () -> enc.copyBufferToTexture(s.source.slice(), 0, 0, 32, 32, s.atlas2, 0, 0, 2, 2, 0, 0));
        guarded(rig, "copyTextureToBuffer", () -> enc.copyTextureToBuffer(s.color, s.readback, 0L, () -> {}, 0));
        guarded(rig, "copyTextureToBuffer(region)", () -> enc.copyTextureToBuffer(s.color, s.readback, 0L, () -> {}, 0, 0, 0, 2, 2));
        guarded(rig, "copyTextureToTexture", () -> enc.copyTextureToTexture(s.atlas, s.atlas2, 0, 0, 0, 0, 0, 4, 4));
        guarded(rig, "writeTimestamp", () -> enc.writeTimestamp(s.queries, 2));
        guarded(rig, "flushForWait (fence wait on the current value)", () -> enc.createFence().awaitCompletion(1_000_000L));
        {
            armCompleted(rig);
            long trips = rig.throttle.tripCount();
            enc.submit(); // a frame that recorded nothing: its commit takes the wait first
            checkEq(trips + 1, rig.throttle.tripCount(), "submit of an empty frame: one guard trip (commit)");
        }
        // Device / resources
        guarded(rig, "createTexture without RENDER_ATTACHMENT (INIT_TEXTURE)", () -> rig.device.createTexture("t", TEX_USAGE, GpuFormat.RGBA8_UNORM, 4, 4, 1, 1));
        guarded(rig, "present (blitFromTexture)", () -> {
            s.surface.acquireNextTexture();
            s.surface.blitFromTexture(enc, (MetalTextureView)s.colorView);
        });
        guarded(rig, "MetalBuffer.map (write)", () -> s.mapped.map(false, true).close());
        guarded(rig, "MetalBuffer.map (read)", () -> s.readback.map(true, false).close());
        guarded(rig, "transient allocateGpu", () -> transients.allocateGpu(32L, 16L, GpuBuffer.USAGE_VERTEX));
        guarded(rig, "transient allocateStaging", () -> transients.allocateStaging(32L, 16L, GpuBuffer.USAGE_COPY_SRC));
        guarded(rig, "transient allocateGpuMapped", () -> transients.allocateGpuMapped(32L, 16L, GpuBuffer.USAGE_UNIFORM));
        guarded(rig, "transient uploadStaging", () -> transients.uploadStaging(bytes(24, 4), 16L, GpuBuffer.USAGE_COPY_SRC));
        guarded(rig, "transient uploadGpu", () -> transients.uploadGpu(bytes(24, 5), 16L, GpuBuffer.USAGE_VERTEX));
        guarded(rig, "transient multiUploadStaging", () -> transients.multiUploadStaging(List.of(bytes(8, 6), bytes(12, 7)), 4L, GpuBuffer.USAGE_COPY_SRC));
        guarded(rig, "transient multiUploadGpu", () -> transients.multiUploadGpu(List.of(bytes(8, 6), bytes(12, 7)), 4L, GpuBuffer.USAGE_INDEX));
        // Not guarded: no command, no commit, no write into memory GPU work can reference.
        unguarded(rig, "createFence", enc::createFence);
        GpuFence oldFence = enc.createFence();
        unguarded(rig, "fence poll", () -> oldFence.awaitCompletion(0L));
        unguarded(rig, "createBuffer(size)", () -> rig.device.createBuffer(null, GpuBuffer.USAGE_VERTEX, 64L));
        unguarded(rig, "createBuffer(data) (fresh backing)", () -> rig.device.createBuffer(null, GpuBuffer.USAGE_VERTEX, bytes(64, 8)));
        unguarded(rig, "createTexture with RENDER_ATTACHMENT", () -> rig.device.createTexture("rt", RT_USAGE, GpuFormat.RGBA8_UNORM, 4, 4, 1, 1));
        unguarded(rig, "createTextureView", () -> rig.device.createTextureView(s.color, 0, 1));
        unguarded(rig, "createSampler", () -> rig.device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.REPEAT, FilterMode.NEAREST, FilterMode.LINEAR, 1, OptionalDouble.empty()));
        unguarded(rig, "createTimestampQueryPool", () -> rig.device.createTimestampQueryPool(4));
        unguarded(rig, "transient allocateCpu", () -> transients.allocateCpu(64L, 16L));
        unguarded(rig, "buffer close", () -> rig.device.createBuffer(null, GpuBuffer.USAGE_VERTEX, 64L).close());
        unguarded(rig, "surface present()", s.surface::present);
        unguarded(rig, "timeline defer", () -> rig.timeline.defer(() -> {}));
        // Pass-level calls only happen inside a pass, i.e. after createRenderPass took the wait (strict mode would throw).
        armCompleted(rig);
        RenderPassBackend pass = enc.createRenderPass(s.passDescriptor());
        long trips = rig.throttle.tripCount();
        pass.setVertexBuffer(0, s.uniform.slice());
        pass.enableScissor(0, 0, 4, 4);
        pass.writeTimestamp(s.queries, 3);
        enc.submitRenderPass();
        checkEq(trips, rig.throttle.tripCount(), "pass-level records after createRenderPass: no further trips");
        rig.completeAll();
        rig.throttle.throttle();
    }

    private static void lateOtherThread() throws Exception {
        Rig rig = new Rig();
        Scene s = new Scene(rig);
        rig.encoder.submit();
        rig.encoder.submit(); // arms seq 1, which stays incomplete
        long pending = rig.throttle.pendingSeq();
        check(pending != 0L, "armed");
        Throwable[] failure = new Throwable[1];
        long[] elapsedMs = new long[1];
        Thread worker = new Thread(() -> {
            long start = System.nanoTime();
            try (GpuBufferSlice.MappedView view = s.mapped.map(false, true)) {
                view.data().put(0, (byte)1);
            } catch (Throwable t) {
                failure[0] = t;
            }
            elapsedMs[0] = (System.nanoTime() - start) / 1_000_000L;
        });
        worker.start();
        worker.join(5000L);
        check(failure[0] == null, "map on another thread works (" + failure[0] + ")");
        check(elapsedMs[0] < 500, "and does not wait (" + elapsedMs[0] + " ms)");
        checkEq(pending, rig.throttle.pendingSeq(), "the render thread's wait stays armed");
        rig.completeAll();
        rig.throttle.throttle();
    }

    private static void lateErrors() {
        {
            Rig rig = new Rig();
            MetalCommandEncoder enc = rig.encoder;
            enc.frameWaitTimeoutNs = 30_000_000L;
            enc.submit();
            enc.submit(); // arms seq 1: never completes
            IllegalStateException e = expect(IllegalStateException.class, rig.throttle::throttle, "throttle point timeout");
            check(e != null && "5s timeout reached when waiting for the GPU".equals(e.getMessage()), "same timeout exception as the old submit() (" + e + ")");
        }
        {
            Rig rig = new Rig();
            Scene s = new Scene(rig);
            MetalCommandEncoder enc = rig.encoder;
            enc.frameWaitTimeoutNs = 30_000_000L;
            enc.submit();
            enc.submit(); // arms seq 1: never completes
            IllegalStateException e = expect(IllegalStateException.class, () -> enc.writeToBuffer(s.idle.slice(0L, 4L), bytes(4, 1)), "guard timeout");
            check(e != null && "5s timeout reached when waiting for the GPU".equals(e.getMessage()), "same exception from a guard (" + e + ")");
        }
        {
            Rig rig = new Rig();
            MetalCommandEncoder enc = rig.encoder;
            enc.submit();
            enc.submit(); // arms the previous frame
            rig.completeAll();
            INT.setVolatile(rig.page, MtlNative.STATUS_PAGE_DEVICE_LOST, 1);
            expect(GpuDeviceLossException.class, rig.throttle::throttle, "device loss surfaces at the throttle point");
            expect(GpuDeviceLossException.class, enc::submit, "and at the next submit");
        }
    }

    /**
     * Review fix (A): a GPU failure taken by a guard inside code that catches exceptions (a task, a packet handler) is
     * recorded; the wait stays armed, every later guard, the throttle point and submit() rethrow the same exception, and
     * nothing more is committed. Before the fix the failure was swallowed and the next commit could go out with frame N-1
     * still running.
     */
    private static void lateStickyFailures() {
        // 1. Timeout swallowed by a "task".
        {
            Rig rig = new Rig();
            Scene s = new Scene(rig);
            MetalCommandEncoder enc = rig.encoder;
            enc.frameWaitTimeoutNs = 30_000_000L;
            enc.submit();
            enc.submit(); // arms seq 1: never completes
            RuntimeException swallowed = null;
            try {
                enc.writeToBuffer(s.idle.slice(0L, 4L), bytes(4, 1)); // e.g. a map texture upload in a packet handler
            } catch (RuntimeException e) {
                swallowed = e; // BlockableEventLoop.doRunTask / PacketProcessor catch Exception
            }
            check(swallowed != null, "the guard timed out");
            check(rig.throttle.pendingSeq() != 0L, "the wait stays armed after the failure");
            int commits = rig.commits.size();
            RuntimeException again = expect(RuntimeException.class, () -> enc.clearColorTexture(s.color, new Vector4f()), "the next guard rethrows");
            check(again == swallowed, "the same exception object");
            again = expect(RuntimeException.class, rig.throttle::throttle, "the throttle point rethrows");
            check(again == swallowed, "the same exception at the frame point");
            rig.complete(1); // the GPU recovering does not make the failure go away
            again = expect(RuntimeException.class, enc::submit, "submit rethrows");
            check(again == swallowed, "the same exception from submit");
            checkEq(commits, rig.commits.size(), "nothing committed after the failure");
            check(rig.throttle.failure() == swallowed, "failure recorded");
        }
        // 2. Command-buffer error (checkErrors records it before throwing, so it surfaced only once before the fix).
        {
            Rig rig = new Rig();
            Scene s = new Scene(rig);
            MetalCommandEncoder enc = rig.encoder;
            enc.submit();
            enc.submit(); // arms seq 1
            rig.completeAll();
            INT.setVolatile(rig.page, MtlNative.STATUS_PAGE_LAST_ERROR_CODE, 5);
            INT.setVolatile(rig.page, MtlNative.STATUS_PAGE_ERROR_COUNT, 1);
            RuntimeException swallowed = null;
            try {
                enc.copyToBuffer(s.idle.slice(0L, 16L), s.uniform.slice(0L, 16L));
            } catch (RuntimeException e) {
                swallowed = e;
            }
            check(swallowed instanceof IllegalStateException && swallowed.getMessage().contains("MTLCommandBufferError 5"),
                "command-buffer error surfaced at the guard (" + swallowed + ")");
            RuntimeException again = expect(RuntimeException.class, rig.throttle::throttle, "and again at the throttle point");
            check(again == swallowed, "the same exception at the frame point");
        }
        // 3. Device loss swallowed.
        {
            Rig rig = new Rig();
            Scene s = new Scene(rig);
            MetalCommandEncoder enc = rig.encoder;
            enc.submit();
            enc.submit();
            rig.completeAll();
            INT.setVolatile(rig.page, MtlNative.STATUS_PAGE_DEVICE_LOST, 1);
            try {
                s.mapped.map(false, true).close();
            } catch (RuntimeException ignored) {
            }
            expect(GpuDeviceLossException.class, rig.throttle::throttle, "device loss rethrown at the throttle point");
        }
        // 4. A drain callback that throws inside a guard trip (before: thrown by submit() inside renderFrame).
        {
            Rig rig = new Rig();
            Scene s = new Scene(rig);
            MetalCommandEncoder enc = rig.encoder;
            RuntimeException boom = new IllegalStateException("callback failure");
            rig.timeline.defer(() -> {
                throw boom;
            }); // tagged 1
            enc.submit();
            enc.submit(); // arms 1
            rig.completeAll();
            try {
                enc.clearColorTexture(s.color, new Vector4f());
            } catch (RuntimeException ignored) {
            }
            RuntimeException again = expect(RuntimeException.class, rig.throttle::throttle, "a throwing drain callback is rethrown at the frame point");
            check(again == boom, "the callback's exception");
        }
    }

    /**
     * Review fix (A): commit() never runs deferred callbacks. A callback that commits (a fence wait on the current value)
     * runs in the guard of flushForWait()/submit() before they read the value they commit; before the fix it ran inside
     * commit() after the value was fixed, and the outer commit signalled the same value again ("timeline diverged").
     */
    private static void lateNoReentrantCommit() throws Exception {
        // flushForWait
        {
            Rig rig = new Rig();
            rig.lazyGpu();
            Scene s = new Scene(rig);
            MetalCommandEncoder enc = rig.encoder;
            int[] ran = new int[1];
            rig.timeline.defer(() -> {
                ran[0]++;
                enc.clearColorTexture(s.atlas2, new Vector4f());
                check(enc.createFence().awaitCompletion(1_000_000_000L), "callback's flush-on-wait fence completes");
            }); // tagged 1
            enc.clearColorTexture(s.color, new Vector4f());
            enc.submit(); // frame 1
            enc.clearColorTexture(s.color, new Vector4f());
            enc.submit(); // frame 2: arms 1
            check(enc.createFence().awaitCompletion(1_000_000_000L), "game fence wait on the current value completes");
            checkEq(1, ran[0], "the callback ran once (in the guard, before the commit)");
            checkCommitSequence(rig, "flushForWait");
        }
        // submit of a frame whose only backend call is submit(): its guard drains a callback that commits
        {
            Rig rig = new Rig();
            rig.lazyGpu();
            Scene s = new Scene(rig);
            MetalCommandEncoder enc = rig.encoder;
            int[] ran = new int[1];
            enc.clearColorTexture(s.color, new Vector4f());
            enc.submit(); // frame 1
            rig.timeline.defer(() -> {
                ran[0]++;
                check(enc.createFence().awaitCompletion(1_000_000_000L), "callback's flush-on-wait fence completes");
            }); // tagged 2
            enc.clearColorTexture(s.color, new Vector4f());
            enc.submit(); // frame 2: arms 1
            rig.throttle.throttle();
            enc.submit(); // frame 3: arms 2
            enc.submit(); // frame 4, nothing recorded: the guard waits for 2 and drains the committing callback first
            checkEq(1, ran[0], "the callback ran once");
            checkCommitSequence(rig, "submit");
        }
    }

    private static void checkCommitSequence(final Rig rig, final String what) {
        long last = 0L;
        boolean increasing = true;
        for (long[] c : rig.commits) {
            increasing &= c[0] > last;
            last = c[0];
        }
        check(increasing, what + ": every commit signals a new, increasing timeline value (no value committed twice)");
        checkEq(last, rig.timeline.lastCommittedSeq(), what + ": the timeline agrees with the commits");
    }

    /** Review fix (A, input latency): frameThrottle() reports whether a wait since the last submit blocked. */
    private static void lateBlockedFlag() {
        check(!MetalFrameThrottle.frameThrottle(), "no game device: never blocked");
        Rig rig = new Rig();
        Scene s = new Scene(rig);
        MetalCommandEncoder enc = rig.encoder;
        enc.submit();
        enc.submit(); // arms 1
        rig.completeLater(1, 40);
        check(rig.throttle.throttle(), "the throttle point blocked: true");
        check(!rig.throttle.throttle(), "a second call: false");
        enc.submit(); // arms 2
        rig.complete(2);
        check(!rig.throttle.throttle(), "frame N-1 already complete: false");
        enc.submit(); // arms 3
        rig.completeLater(3, 40);
        enc.clearColorTexture(s.color, new Vector4f()); // a guard trip that blocks, before the throttle point
        check(rig.throttle.throttle(), "a blocking guard trip since the submit: true");
        rig.completeAll();
    }

    private static void lateHooksAndStats() {
        check(!MetalFrameThrottle.active(), "no game device: hooks inactive");
        MetalFrameThrottle.frameThrottle(); // no-op
        Rig rig = new Rig();
        Scene s = new Scene(rig);
        MetalCommandEncoder enc = rig.encoder;
        MetalFrameThrottle.resetStats();
        MetalFrameThrottle.register(rig.throttle);
        try {
            check(MetalFrameThrottle.active(), "registered device: runTick uses the late order");
            enc.submit();
            enc.submit();
            rig.completeLater(1, 60);
            long start = System.nanoTime();
            MetalFrameThrottle.frameThrottle();
            check((System.nanoTime() - start) / 1_000_000L >= 40, "static frameThrottle() waits on the registered device");
            checkEq(0, rig.throttle.pendingSeq(), "and takes the wait");
            // Frame 3: one trip (the second call finds the wait taken); frame 4: none; frame 5: one (flush-on-wait).
            enc.submit();
            rig.completeAll();
            enc.writeToBuffer(s.idle.slice(0L, 4L), bytes(4, 1));
            enc.clearColorTexture(s.atlas2, new Vector4f()); // no second wait: already taken
            enc.submit();
            rig.completeAll();
            MetalFrameThrottle.frameThrottle();
            enc.clearColorTexture(s.atlas2, new Vector4f());
            enc.submit();
            rig.completeAll();
            enc.createFence().awaitCompletion(1_000_000L); // flush-on-wait before the throttle point: one trip
            MetalFrameThrottle.frameThrottle();
            enc.submit();
            long[] st = MetalFrameThrottle.stats();
            checkEq(6, st[MetalFrameThrottle.STAT_FRAMES], "frames counted per submit");
            checkEq(2, st[MetalFrameThrottle.STAT_TRIPS], "trips");
            checkEq(2, st[MetalFrameThrottle.STAT_TRIP_FRAMES], "frames with trips");
            checkEq(1, st[MetalFrameThrottle.STAT_MAX_TRIPS_PER_FRAME], "max trips per frame");
            MetalFrameThrottle.resetStats();
            checkEq(0, MetalFrameThrottle.stats()[MetalFrameThrottle.STAT_FRAMES], "resetStats");
            // Stat line: every arm() logs with a 1 ns interval, across a reset; it must never throw.
            MetalFrameThrottle.statIntervalNs = 1L;
            try {
                for (int i = 0; i < 3; i++) {
                    rig.completeAll();
                    MetalFrameThrottle.frameThrottle();
                    enc.submit();
                    if (i == 1) {
                        MetalFrameThrottle.resetStats();
                    }
                }
                rig.completeAll();
                enc.writeToBuffer(s.idle.slice(0L, 4L), bytes(4, 2)); // a trip after the reset is logged again
                enc.submit();
                checkEq(2, MetalFrameThrottle.stats()[MetalFrameThrottle.STAT_FRAMES], "frames since the reset");
                checkEq(1, MetalFrameThrottle.stats()[MetalFrameThrottle.STAT_TRIPS], "trips since the reset");
            } finally {
                MetalFrameThrottle.statIntervalNs = MetalFrameThrottle.STAT_INTERVAL_NS;
            }
            rig.completeAll();
            rig.device.close();
            check(!MetalFrameThrottle.active(), "device close unregisters");
        } finally {
            MetalFrameThrottle.unregister(rig.throttle);
        }
    }

    // =============================================================================================
    // Late throttle off (old path)
    // =============================================================================================

    private static void offOldPath() {
        Rig rig = new Rig();
        Scene s = new Scene(rig);
        MetalCommandEncoder enc = rig.encoder;
        enc.clearColorTexture(s.color, new Vector4f());
        enc.submit();
        enc.clearColorTexture(s.color, new Vector4f());
        rig.completeLater(1, 120);
        long start = System.nanoTime();
        enc.submit(); // old path: waits for frame 1 inside submit()
        long waitedMs = (System.nanoTime() - start) / 1_000_000L;
        check(waitedMs >= 100, "submit() waited for frame N-1 (" + waitedMs + " ms)");
        check(rig.timeline.completedSeq() >= 1, "frame N-1 complete when submit() returns");
        checkEq(0, rig.throttle.pendingSeq(), "nothing armed");
        enc.frameWaitTimeoutNs = 30_000_000L;
        IllegalStateException e = expect(IllegalStateException.class, enc::submit, "submit() timeout (frame 2 never completes)");
        check(e != null && "5s timeout reached when waiting for the GPU".equals(e.getMessage()), "old timeout exception (" + e + ")");
    }

    private static void offInert() throws Exception {
        check(!MetalFrameThrottle.ENABLED && !MetalFrameThrottle.STRICT, "toggle off");
        MetalFrameThrottle.resetStats();
        Rig rig = new Rig();
        rig.gpuFollowsCommits = true;
        Scene s = new Scene(rig);
        MetalFrameThrottle.register(rig.throttle); // even a registered throttle stays inert when the toggle is off
        try {
            check(!MetalFrameThrottle.active(), "active() is false when off");
            for (int f = 0; f < 4; f++) {
                MetalFrameThrottle.frameThrottle();
                rig.encoder.writeToBuffer(s.idle.slice(0L, 4L), bytes(4, f));
                s.mapped.map(false, true).close();
                rig.encoder.transientMemory().uploadGpu(bytes(16, f), 16L, GpuBuffer.USAGE_VERTEX);
                rig.encoder.submit();
                checkEq(0, rig.throttle.pendingSeq(), "frame " + f + ": nothing armed");
            }
            long[] st = MetalFrameThrottle.stats();
            long sum = 0;
            for (long v : st) {
                sum += v;
            }
            checkEq(0, sum, "counters untouched");
        } finally {
            MetalFrameThrottle.unregister(rig.throttle);
        }
    }
}
