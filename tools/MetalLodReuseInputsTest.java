package com.mojang.renderpearl.backend.metal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import org.lwjgl.system.MemoryUtil;

/** Isolated CPU-only test against client.jar, with the two changed backend classes ahead of it on the classpath. */
public final class MetalLodReuseInputsTest {
    private static final int U = GpuBuffer.USAGE_UNIFORM;
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void equal(byte[] actual, byte[] expected, String message) {
        check(Arrays.equals(actual, expected), message);
    }

    private static final class Rig implements AutoCloseable {
        final MemorySegment page = MemorySegment.ofArray(new long[8]);
        final MetalTimeline timeline = new MetalTimeline(0L, this.page);
        final MetalDevice device = MetalDevice.forTesting(0L, this.page, this.timeline, null, false);
        final ArrayList<MetalBuffer> buffers = new ArrayList<>();
        final ArrayList<Long> staging = new ArrayList<>();
        int submits;

        Rig() {
            this.timeline.testWaitHook = seq -> { throw new AssertionError("unexpected wait for " + seq); };
            this.device.encoder().submitFunction = (dev, info, result) -> {
                this.submits++;
                MemoryUtil.memPutInt(result + MtlNative.SUBMIT_RESULT_FLAGS, MtlNative.SUBMIT_RESULT_FLAG_COMMITTED);
                return MtlNative.OK;
            };
        }

        MetalBuffer buffer(int usage, int size) {
            MetalBuffer buffer = MetalBuffer.create(this.device, null, usage, size);
            this.buffers.add(buffer);
            for (int i = 0; i < size; i++) MemoryUtil.memPutByte(buffer.contents() + i, (byte)i);
            return buffer;
        }

        void stage(MetalBuffer buffer, long seq, int offset, byte[] bytes) {
            long address = MemoryUtil.nmemAlloc(bytes.length);
            this.staging.add(address);
            MemoryUtil.memByteBuffer(address, bytes.length).put(bytes);
            check(buffer.recordStagedWrite(seq, offset, bytes.length, address, this.timeline.completedSeq()), "record stage");
        }

        void complete(long seq) {
            ValueLayout.JAVA_LONG.varHandle().setRelease(this.page, MtlNative.STATUS_PAGE_COMPLETED_VALUE, seq);
        }

        @Override public void close() {
            this.complete(Long.MAX_VALUE); // test-only completion; no native command buffer is submitted
            for (MetalBuffer buffer : this.buffers) buffer.close();
            this.device.close(); // fake submit hook only, if a stream was recorded
            for (long address : this.staging) MemoryUtil.nmemFree(address);
        }
    }

    private static byte[] sequence(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte)i;
        return bytes;
    }

    private static void layoutsAndBounds() throws Exception {
        try (Rig rig = new Rig()) {
            for (int[] layout : new int[][]{{U | 8, 64, 64}, {U | 2, 80, 72}, {U | 2, 48, 48}, {U | 8, 48, 48}, {U, 48, 48}}) {
                MetalBuffer buffer = rig.buffer(layout[0], layout[1]);
                equal(MetalLodReuseInputs.snapshot(buffer.slice(), layout[2]), sequence(layout[2]), "uniform layout " + Arrays.toString(layout));
            }
            MetalBuffer buffer = rig.buffer(U | 2, 80);
            GpuBufferSlice slice = buffer.slice(16, 48);
            byte[] first = MetalLodReuseInputs.snapshot(slice, 32);
            equal(first, Arrays.copyOfRange(sequence(80), 16, 48), "nonzero slice offset + prefix");
            try (var mapping = slice.map(false, true)) {
                check(MetalLodReuseInputs.snapshot(slice, 32) == null, "open mapping fails closed");
                mapping.data().put(0, (byte)99);
            }
            check(MetalLodReuseInputs.snapshot(slice, 32)[0] == 99 && first[0] == 16, "same-frame host mutation and owned copy");
            check(MetalLodReuseInputs.snapshot(slice, 49) == null, "slice length bound");
            check(MetalLodReuseInputs.snapshot(slice, -1) == null, "negative length");
            check(MetalLodReuseInputs.snapshot(null, 0) == null, "null slice");
            check(MetalLodReuseInputs.snapshot(new GpuBufferSlice(null, 0, 0), 0) == null, "null buffer");
            for (long[] bounds : new long[][]{{-1, 1}, {0, -1}, {79, 2}, {Long.MAX_VALUE, 2}, {1, Long.MAX_VALUE}}) {
                check(MetalLodReuseInputs.snapshot(new GpuBufferSlice(buffer, bounds[0], bounds[1]), 0) == null, "invalid/overflowing slice");
            }
            equal(MetalLodReuseInputs.snapshot(buffer.slice(80, 0), 0), new byte[0], "valid empty prefix");
            boolean[] rejected = new boolean[1];
            Thread other = new Thread(() -> rejected[0] = MetalLodReuseInputs.snapshot(slice, 1) == null);
            other.start(); other.join();
            check(rejected[0], "off-render-thread snapshot fails closed");
            buffer.close();
            check(MetalLodReuseInputs.snapshot(slice, 1) == null, "closed buffer");
            check(rig.submits == 0, "snapshot never submits");
        }
    }

    private static void stagedWrites() {
        try (Rig rig = new Rig()) {
            MetalBuffer buffer = rig.buffer(U | 8, 80);
            byte[] expected = sequence(80);
            rig.stage(buffer, 1, 4, new byte[]{90, 91, 92, 93, 94, 95});
            System.arraycopy(new byte[]{90, 91, 92, 93, 94, 95}, 0, expected, 4, 6);
            rig.stage(buffer, 1, 6, new byte[]{10, 11, 12});
            System.arraycopy(new byte[]{10, 11, 12}, 0, expected, 6, 3);
            equal(MetalLodReuseInputs.snapshot(buffer.slice(), 72), Arrays.copyOf(expected, 72), "same-sequence overlapping stage order");
            equal(MetalLodReuseInputs.snapshot(buffer.slice(5, 5), 5), Arrays.copyOfRange(expected, 5, 10), "clipped staging overlap");
            check(MemoryUtil.memGetByte(buffer.contents() + 4) == 4, "snapshot does not mutate backing");
            check(buffer.stagedWriteCount() == 2, "snapshot does not consume replay entries");
            rig.timeline.onCommitted(1);
            rig.stage(buffer, 2, 0, new byte[]{42, 43, 44, 45, 46, 47, 48, 49});
            System.arraycopy(new byte[]{42, 43, 44, 45, 46, 47, 48, 49}, 0, expected, 0, 8);
            equal(MetalLodReuseInputs.snapshot(buffer.slice(), 16), Arrays.copyOf(expected, 16), "cross-sequence overlay order");
            buffer.markGpuWrite(2);
            check(MetalLodReuseInputs.snapshot(buffer.slice(), 16) == null, "pending unknown GPU write beats overlay");
            MemoryUtil.memByteBuffer(buffer.contents(), expected.length).put(expected); // emulate finished GPU copies
            rig.timeline.onCommitted(2);
            rig.complete(2);
            for (long address : rig.staging) MemoryUtil.memSet(address, 0xee, 3);
            equal(MetalLodReuseInputs.snapshot(buffer.slice(), 16), Arrays.copyOf(expected, 16), "completed copies use backing, not stale staging");
            check(buffer.stagedWriteCount() == 3, "completed entries not pruned by snapshot");
            check(rig.submits == 0, "staged snapshots never submit");
        }
        try (Rig rig = new Rig()) {
            MetalBuffer buffer = rig.buffer(U | 8, 64);
            check(buffer.recordStagedWrite(1, 0, 8, 0, 0), "synthetic completed stage");
            rig.timeline.onCommitted(1); rig.complete(1);
            equal(MetalLodReuseInputs.snapshot(buffer.slice(), 64), sequence(64), "completed invalid staging pointer never dereferenced");
            check(buffer.recordStagedWrite(2, 0, 8, 0, 1), "synthetic pending stage");
            check(MetalLodReuseInputs.snapshot(buffer.slice(), 64) == null, "pending missing staging pointer");
        }
    }

    private static void encoderPaths() {
        try (Rig rig = new Rig()) {
            MetalBuffer buffer = rig.buffer(U | 8, 80);
            byte[] expected = sequence(80);
            for (int i = 0; i < MetalBuffer.RENAME_MAX_SPARES + 3; i++) {
                buffer.markUsed(rig.timeline.pendingSeq());
                byte value = (byte)(100 + i);
                rig.device.encoder().writeToBuffer(buffer.slice(i, 1), ByteBuffer.wrap(new byte[]{value}));
                expected[i] = value;
                equal(MetalLodReuseInputs.snapshot(buffer.slice(), 72), Arrays.copyOf(expected, 72), "real encoder rename/stage " + i);
                rig.timeline.onCommitted(rig.timeline.pendingSeq());
            }
            check(buffer.spareCount() == MetalBuffer.RENAME_MAX_SPARES && buffer.stagedWriteCount() == 3, "real encoder staged after rename pool exhausted");
            check(rig.submits == 0 && rig.timeline.completedSeq() == 0, "snapshot leaves submission/completion untouched");
            boolean old = MetalCommandEncoder.RENAME_SMALL_WRITES;
            try {
                MetalCommandEncoder.RENAME_SMALL_WRITES = false;
                buffer.markUsed(rig.timeline.pendingSeq());
                rig.device.encoder().writeToBuffer(buffer.slice(0, 1), ByteBuffer.wrap(new byte[]{9}));
                check(MetalLodReuseInputs.snapshot(buffer.slice(), 72) == null, "untracked staged write fails closed when renaming disabled");
            } finally { MetalCommandEncoder.RENAME_SMALL_WRITES = old; }
        }
    }

    private static void unavailable() {
        try (Rig rig = new Rig()) {
            for (int usage : new int[]{U | 2 | 8, U | 256, 32 | 8, 512 | 8, 8, U | 1, U | 16}) {
                MetalBuffer buffer = rig.buffer(usage, 64);
                check(MetalLodReuseInputs.snapshot(buffer.slice(), 16) == null, "unaudited/native-output usage " + usage);
            }
            MetalBuffer large = rig.buffer(U | 8, 65537);
            check(MetalLodReuseInputs.snapshot(large.slice(), 48) == null, "large native output/unknown buffer");
            for (int options : new int[]{-1, MetalFormats.RESOURCE_STORAGE_PRIVATE, MetalFormats.RESOURCE_HAZARD_TRACKING_UNTRACKED}) {
                // Invalid address is intentional: each case must be rejected before a memory access.
                MetalBuffer wrapped = MetalBuffer.wrapReserved(rig.device, 123, 1, 0, U | 8, 64, 64, options, 0);
                rig.buffers.add(wrapped);
                check(MetalLodReuseInputs.snapshot(wrapped.slice(), 64) == null, "unknown/private options " + options);
            }
            MetalBuffer wrapped = new MetalBuffer(rig.device, 123, 1, 0, U | 8, 64);
            rig.buffers.add(wrapped);
            check(MetalLodReuseInputs.snapshot(wrapped.slice(), 64) == null, "unknown wrapped allocation");
            MetalBuffer buffer = rig.buffer(U | 8, 64);
            boolean old = MetalCommandEncoder.FORCE_FAST_WRITE;
            try {
                MetalCommandEncoder.FORCE_FAST_WRITE = true;
                check(MetalLodReuseInputs.snapshot(buffer.slice(), 64) == null, "unsafe force-fast-write diagnostic");
            } finally { MetalCommandEncoder.FORCE_FAST_WRITE = old; }
            rig.timeline.close();
            check(MetalLodReuseInputs.snapshot(buffer.slice(), 64) == null, "closed timeline cannot bless pending contents");
        }
    }

    public static void main(String[] args) throws Exception {
        RenderSystem.initRenderThread();
        layoutsAndBounds();
        stagedWrites();
        encoderPaths();
        unavailable();
        Path root = Path.of(args[0]);
        String source = Files.readString(root.resolve("client/src/com/mojang/renderpearl/backend/metal/MetalBuffer.java"));
        source = source.substring(source.indexOf("final byte @Nullable [] snapshotCpuBytes("), source.indexOf("/** Retired backings"));
        for (String forbidden : new String[]{".map(", ".await(", ".submit(", ".drain(", "flushForWait(", "ensureThrottled(", "pruneStagedWrites("}) {
            check(!source.contains(forbidden), "snapshot has no blocking/mutating call: " + forbidden);
        }
        System.out.println("MetalLodReuseInputsTest: " + checks + " checks passed (headless, no GPU).");
    }
}
