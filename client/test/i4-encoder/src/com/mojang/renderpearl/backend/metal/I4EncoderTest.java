package com.mojang.renderpearl.backend.metal;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDeviceLossException;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * I4 (java-encoder) headless tests: StreamWriter, every CommandEncoderBackend / RenderPassBackend / surface
 * method, decoded back from the produced stream bytes with {@link I4StreamDecoder} (typed through
 * {@link StreamOps.Layouts}), the submit / flush / throttle / drain sequence through a fake mcmtl_submit, the
 * surface state machine through fake platform calls, the real RenderPearl frontend driving the backend, and
 * zero-allocation of the per-draw hot path. No GPU, window or dylib needed. Run with tools/test-i4-encoder.sh.
 */
public final class I4EncoderTest {
    private static int checks;
    private static int failures;
    private static String currentTest = "";
    private static long nextHandle = 0x1_0000_0000L;

    public static void main(final String[] args) throws Exception {
        boolean usingDoubles = MetalTimeline.class.getProtectionDomain().getCodeSource().getLocation().toString().contains("i4-doubles");
        System.out.println("I4 encoder tests (I3 test doubles: " + (usingDoubles ? "yes" : "no, real I3 classes") + ")");
        run("streamWriter", I4EncoderTest::testStreamWriter);
        run("layoutsMatchStreamOps", I4EncoderTest::testLayoutsMatchStreamOps);
        run("beginEndPass", I4EncoderTest::testBeginEndPass);
        run("debugLabels", I4EncoderTest::testDebugLabels);
        run("uniformDedupe", I4EncoderTest::testUniformDedupe);
        run("vertexIndexScissorDraws", I4EncoderTest::testVertexIndexScissorDraws);
        run("closedPassThrows", I4EncoderTest::testClosedPassThrows);
        run("pushConstants", I4EncoderTest::testPushConstants);
        run("clears", I4EncoderTest::testClears);
        run("writeToBuffer", I4EncoderTest::testWriteToBuffer);
        run("copyToBuffer", I4EncoderTest::testCopyToBuffer);
        run("writeToTexture", I4EncoderTest::testWriteToTexture);
        run("copyBufferToTexture", I4EncoderTest::testCopyBufferToTexture);
        run("copyTextureToBuffer", I4EncoderTest::testCopyTextureToBuffer);
        run("copyTextureToTexture", I4EncoderTest::testCopyTextureToTexture);
        run("timestampsAndFences", I4EncoderTest::testTimestampsAndFences);
        run("submitSequence", I4EncoderTest::testSubmitSequence);
        run("submitThrottleAndErrors", I4EncoderTest::testSubmitThrottleAndErrors);
        run("flushForWait", I4EncoderTest::testFlushForWait);
        run("submissionTicketCapture", I4EncoderTest::testSubmissionTicketCapture);
        run("submissionTicketPublication", I4EncoderTest::testSubmissionTicketPublication);
        run("submissionTicketFailures", I4EncoderTest::testSubmissionTicketFailures);
        run("submissionTicketDrop", I4EncoderTest::testSubmissionTicketDrop);
        run("submissionTicketPostSubmitError", I4EncoderTest::testSubmissionTicketPostSubmitError);
        run("submissionTicketFlushAndShutdown", I4EncoderTest::testSubmissionTicketFlushAndShutdown);
        run("submissionTicketAllocationFree", I4EncoderTest::testSubmissionTicketAllocationFree);
        run("surface", I4EncoderTest::testSurface);
        run("surfacePresentStats", I4EncoderTest::testSurfacePresentStats);
        run("surfaceClose", I4EncoderTest::testSurfaceClose);
        run("frontendIntegration", I4EncoderTest::testFrontendIntegration);
        run("strictBindings", I4EncoderTest::testStrictBindings);
        run("hotPathAllocationFree", I4EncoderTest::testHotPathAllocationFree);
        run("shutdown", I4EncoderTest::testShutdown);
        System.out.println();
        System.out.println((failures == 0 ? "ALL PASSED" : failures + " FAILURE(S)") + " (" + checks + " checks)");
        System.exit(failures == 0 ? 0 : 1);
    }

    @FunctionalInterface
    interface Body {
        void run() throws Exception;
    }

    private static void run(final String name, final Body body) {
        currentTest = name;
        int before = failures;
        try {
            body.run();
        } catch (Throwable t) {
            failures++;
            System.out.println("  FAIL " + name + ": unexpected " + t);
            t.printStackTrace(System.out);
        }
        System.out.println((failures == before ? "PASS " : "FAIL ") + name);
    }

    // =============================================================================================
    // Assertions
    // =============================================================================================

    static void check(final boolean condition, final String what) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("  FAIL " + currentTest + " (line " + callerLine() + "): " + what);
        }
    }

    private static int callerLine() {
        return StackWalker.getInstance().walk(frames -> frames
            .filter(f -> !f.getMethodName().matches("check|eq|eqf|eqo|expectThrows|callerLine|checkSetBuffer|checkSetTexture"))
            .findFirst().map(StackWalker.StackFrame::getLineNumber).orElse(-1));
    }

    static void eq(final long expected, final long actual, final String what) {
        check(expected == actual, what + ": expected " + expected + " (0x" + Long.toHexString(expected) + "), got " + actual + " (0x" + Long.toHexString(actual) + ")");
    }

    static void eqf(final float expected, final float actual, final String what) {
        check(Float.floatToIntBits(expected) == Float.floatToIntBits(actual), what + ": expected " + expected + ", got " + actual);
    }

    static void eqo(final Object expected, final Object actual, final String what) {
        check(expected.equals(actual), what + ": expected " + expected + ", got " + actual);
    }

    static <T extends Throwable> T expectThrows(final Class<T> type, final Body body, final String what) {
        checks++;
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            failures++;
            System.out.println("  FAIL " + currentTest + " (line " + callerLine() + "): " + what + ": expected " + type.getSimpleName() + ", got " + t);
            return null;
        }
        failures++;
        System.out.println("  FAIL " + currentTest + " (line " + callerLine() + "): " + what + ": expected " + type.getSimpleName() + ", nothing thrown");
        return null;
    }

    // =============================================================================================
    // Fixture: headless device + fake mcmtl_submit
    // =============================================================================================

    static final class Submit {
        int infoStructSize;
        int flags;
        long signal;
        long label;
        long initPointer;
        long mainPointer;
        byte[] init;
        byte[] main;
        int resultStructSize;
        boolean resultZeroed;
    }

    static final class Fixture {
        final MemorySegment page = MemorySegment.ofArray(new long[8]);
        final MetalTimeline timeline = new MetalTimeline(0L, this.page);
        final MetalDevice device;
        final MetalCommandEncoder encoder;
        final List<Submit> submits = new ArrayList<>();
        boolean autoComplete = true;
        int nextStatus = MtlNative.OK;
        int nextResultFlags;
        boolean nextCommitted = true;
        int nextDrawableWidth;
        int nextDrawableHeight;
        RuntimeException throwNext;

        Fixture(final boolean labels) {
            this.device = MetalDevice.forTesting(0L, this.page, this.timeline, null, labels);
            this.encoder = this.device.encoder();
            this.encoder.submitFunction = this::fakeSubmit;
        }

        private int fakeSubmit(final long dev, final long info, final long result) {
            Submit s = new Submit();
            s.infoStructSize = MemoryUtil.memGetInt(info + MtlNative.SUBMIT_INFO_STRUCT_SIZE);
            s.flags = MemoryUtil.memGetInt(info + MtlNative.SUBMIT_INFO_FLAGS);
            s.initPointer = MemoryUtil.memGetLong(info + MtlNative.SUBMIT_INFO_INIT_STREAM);
            s.mainPointer = MemoryUtil.memGetLong(info + MtlNative.SUBMIT_INFO_STREAM);
            s.init = I4StreamDecoder.copyOf(s.initPointer, MemoryUtil.memGetLong(info + MtlNative.SUBMIT_INFO_INIT_STREAM_LENGTH));
            s.main = I4StreamDecoder.copyOf(s.mainPointer, MemoryUtil.memGetLong(info + MtlNative.SUBMIT_INFO_STREAM_LENGTH));
            s.signal = MemoryUtil.memGetLong(info + MtlNative.SUBMIT_INFO_SIGNAL_VALUE);
            s.label = MemoryUtil.memGetLong(info + MtlNative.SUBMIT_INFO_LABEL);
            s.resultStructSize = MemoryUtil.memGetInt(result + MtlNative.SUBMIT_RESULT_STRUCT_SIZE);
            boolean zero = true;
            for (long k = 4; k < MtlNative.SUBMIT_RESULT_SIZEOF; k++) {
                zero &= MemoryUtil.memGetByte(result + k) == 0;
            }
            s.resultZeroed = zero;
            this.submits.add(s);
            if (this.throwNext != null) {
                RuntimeException t = this.throwNext;
                this.throwNext = null;
                throw t;
            }
            // Emulate the native guard: the value is consumed on every path.
            STATUS.setRelease(this.page, MtlNative.STATUS_PAGE_SUBMITTED_VALUE, s.signal);
            if (this.autoComplete) {
                this.complete(s.signal);
            }
            MemoryUtil.memPutInt(result + MtlNative.SUBMIT_RESULT_FLAGS,
                this.nextResultFlags | (this.nextCommitted ? MtlNative.SUBMIT_RESULT_FLAG_COMMITTED : 0));
            MemoryUtil.memPutInt(result + MtlNative.SUBMIT_RESULT_DRAWABLE_WIDTH, this.nextDrawableWidth);
            MemoryUtil.memPutInt(result + MtlNative.SUBMIT_RESULT_DRAWABLE_HEIGHT, this.nextDrawableHeight);
            this.nextResultFlags = 0;
            this.nextCommitted = true;
            int status = this.nextStatus;
            this.nextStatus = MtlNative.OK;
            return status;
        }

        void complete(final long seq) {
            STATUS.setRelease(this.page, MtlNative.STATUS_PAGE_COMPLETED_VALUE, seq);
        }

        void setInt(final long offset, final int value) {
            STATUS_INT.setRelease(this.page, offset, value);
        }

        MetalTransientMemory transientMemory() {
            return this.device.transientMemory();
        }

        List<I4StreamDecoder.Rec> main() {
            return I4StreamDecoder.parse(this.encoder.mainStream(), false);
        }

        List<I4StreamDecoder.Rec> init() {
            return I4StreamDecoder.parse(this.encoder.initStream(), true);
        }

        Submit lastSubmit() {
            return this.submits.get(this.submits.size() - 1);
        }

        void resetMain() {
            this.encoder.mainStream().reset();
            this.encoder.initStream().reset();
        }

        long pending() {
            return this.timeline.pendingSeq();
        }
    }

    private static final VarHandle STATUS = JAVA_LONG.varHandle();
    private static final VarHandle STATUS_INT = java.lang.foreign.ValueLayout.JAVA_INT.varHandle();

    // =============================================================================================
    // Test resources (fake handles above 2^32 so a truncated long shows up)
    // =============================================================================================

    static long newHandle() {
        nextHandle += 0x1_0010L;
        return nextHandle;
    }

    /** A MetalBuffer over test-owned memory; optional backing offset (I3 renaming / sub-allocation). */
    static final class TestBuffer extends MetalBuffer {
        final long memory;
        final long texelViewHandle = newHandle();
        int texelViewCalls;
        int texelFormat;
        int texelBytes;
        boolean closed;

        static TestBuffer create(final MetalDevice device, final int usage, final long size, final long backingOffset) {
            long memory = MemoryUtil.nmemAlignedAlloc(16L, size + backingOffset + 16L);
            MemoryUtil.memSet(memory, 0, size + backingOffset + 16L);
            return new TestBuffer(device, memory, usage, size, backingOffset);
        }

        private TestBuffer(final MetalDevice device, final long memory, final int usage, final long size, final long backingOffset) {
            super(device, newHandle(), memory, 0xD000_0000_0000L + memory, usage, size);
            this.memory = memory;
            if (backingOffset != 0L) {
                this.rebind(this.handle(), memory + backingOffset, 0xD000_0000_0000L + memory + backingOffset, backingOffset);
            }
        }

        @Override
        public long texelView(final int pixelFormat, final int bytesPerTexel) {
            this.texelViewCalls++;
            this.texelFormat = pixelFormat;
            this.texelBytes = bytesPerTexel;
            return this.texelViewHandle;
        }

        @Override
        public boolean isClosed() {
            return this.closed;
        }

        @Override
        public void close() {
            this.closed = true;
        }

        @Override
        public GpuBufferSlice.MappedView map(final long offset, final long length, final boolean read, final boolean write) {
            return new GpuBufferSlice.MappedView(new GpuBufferSlice(this, offset, length), MemoryUtil.memByteBuffer(this.contents() + offset, (int)length), () -> {});
        }
    }

    static MetalTexture texture(final MetalDevice device, final GpuFormat format, final int usage, final int width, final int height, final int layers, final int mips) {
        return new MetalTexture(device, newHandle(), MetalFormats.pixelFormat(format), usage, "", format, width, height, layers, mips);
    }

    static MetalTextureView fullView(final MetalTexture texture) {
        return new MetalTextureView(texture, 0, texture.getMipLevels(), 0L);
    }

    static MetalTextureView partialView(final MetalTexture texture, final int base, final int count) {
        return new MetalTextureView(texture, base, count, newHandle());
    }

    static MetalSampler sampler() {
        return new MetalSampler(newHandle(), AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.NEAREST, 1, OptionalDouble.empty());
    }

    static final int RT = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
    static final int V = MtlNative.STAGE_VERTEX;
    static final int F = MtlNative.STAGE_FRAGMENT;
    static final int K_UBO = MetalRenderPipeline.KIND_UNIFORM_BUFFER;
    static final int K_TEX = MetalRenderPipeline.KIND_SAMPLED_TEXTURE;
    static final int K_TEXEL = MetalRenderPipeline.KIND_TEXEL_BUFFER;

    /** Projection(UBO 0, V), Globals(UBO 1, V|F), Sampler0(tex 0, F), CloudFaces(texel 3, V, R8Sint), Unused(UBO 5, stages 0). */
    static MetalRenderPipeline pipeline(final MetalDevice device, final String name, final int pushConstantSize, final int pushConstantStages) {
        return new MetalRenderPipeline(
            device, newHandle(), name,
            new String[]{"Projection", "Globals", "Sampler0", "CloudFaces", "Unused"},
            new int[]{K_UBO, K_UBO, K_TEX, K_TEXEL, K_UBO},
            new int[]{0, 1, 0, 3, 5},
            new int[]{V, V | F, F, V, 0},
            new int[]{0, 0, 0, MetalFormats.PIXEL_FORMAT_R8_SINT, 0},
            new int[]{0, 0, 0, 1, 0},
            pushConstantSize, pushConstantStages, 0b1, PrimitiveTopology.QUADS
        );
    }

    static RenderPassDescriptor simplePass(final MetalTextureView color, final Supplier<String> label) {
        return RenderPassDescriptor.builder(label).withColorAttachment(color).build();
    }

    static ByteBuffer pattern(final int size, final int seed) {
        ByteBuffer b = MemoryUtil.memAlloc(size);
        for (int k = 0; k < size; k++) {
            b.put(k, (byte)(seed + k * 7));
        }
        return b;
    }

    static Supplier<String> forbiddenLabel() {
        return () -> {
            throw new AssertionError("label supplier must not be evaluated when debug labels are off");
        };
    }

    @SuppressWarnings("unchecked")
    static <T> T peek(final Object target, final String fieldName) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return (T)f.get(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    // =============================================================================================
    // Tests
    // =============================================================================================

    static void testStreamWriter() {
        StreamWriter w = new StreamWriter("t", 64);
        eq(0, w.address() & 15L, "base 16-byte aligned");
        check(w.isEmpty() && w.size() == 0, "empty");
        long r = w.reserve(32);
        eq(w.address(), r, "first record at base");
        check(Arrays.equals(new byte[32], I4StreamDecoder.read(r, 32)), "reserved bytes are zero");
        MemoryUtil.memSet(r, 0xFF, 32);
        long r2 = w.reserve(16);
        eq(32, r2 - w.address(), "second record offset");
        check(Arrays.equals(new byte[16], I4StreamDecoder.read(r2, 16)), "second record zero");
        for (int bad : new int[]{0, 8, 24, -16, 65536, 65521, 65536 + 16}) {
            expectThrows(IllegalArgumentException.class, () -> w.reserve(bad), "reserve(" + bad + ")");
        }
        eq(48, w.size(), "size unchanged by rejected reserves");
        // Growth: records keep their content across reallocations.
        long capacity = w.capacity();
        List<Long> offsets = new ArrayList<>();
        int grown = 0;
        for (int k = 0; k < 64; k++) {
            long before = w.capacity();
            long off = w.size();
            long a = w.reserve(4096);
            check(Arrays.equals(new byte[4096], I4StreamDecoder.read(a, 4096)), "fresh record zero after growth " + k);
            MemoryUtil.memSet(a, k + 1, 4096);
            offsets.add(off);
            if (w.capacity() != before) {
                grown++;
                eq(0, w.address() & 15L, "aligned after growth");
            }
        }
        check(grown >= 3 && w.capacity() > capacity, "grew several times (" + grown + ")");
        for (int k = 0; k < offsets.size(); k++) {
            byte[] content = I4StreamDecoder.read(w.address() + offsets.get(k), 4096);
            byte[] expected = new byte[4096];
            Arrays.fill(expected, (byte)(k + 1));
            check(Arrays.equals(expected, content), "record " + k + " survived growth");
        }
        check(Arrays.equals(filled(32, (byte)0xFF), I4StreamDecoder.read(w.address(), 32)), "first record survived growth");
        long used = w.size();
        w.reset();
        check(w.isEmpty(), "reset empties");
        check(Arrays.equals(new byte[(int)used], I4StreamDecoder.read(w.address(), (int)used)), "reset zeroes the used bytes");
        long again = w.reserve(65520);
        check(Arrays.equals(new byte[65520], I4StreamDecoder.read(again, 65520)), "max-size record zero after reset");
        expectThrows(IndexOutOfBoundsException.class, () -> w.addressAt(65520), "addressAt past end");
        eq(w.address() + 16, w.addressAt(16), "addressAt");
        w.close();
        w.close();
        check(w.isClosed(), "closed");
        expectThrows(IllegalStateException.class, () -> w.reserve(16), "reserve after close");
    }

    static byte[] filled(final int n, final byte value) {
        byte[] b = new byte[n];
        Arrays.fill(b, value);
        return b;
    }

    static String upperSnake(final String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2").toUpperCase();
    }

    static void testLayoutsMatchStreamOps() throws Exception {
        int layouts = 0;
        int members = 0;
        for (Field lf : StreamOps.Layouts.class.getFields()) {
            if (lf.getType() != StructLayout.class) {
                continue;
            }
            StructLayout layout = (StructLayout)lf.get(null);
            String prefix = lf.getName();
            layouts++;
            eqo("mcmtl_cmd_" + prefix.toLowerCase(), layout.name().orElse(""), "layout name of " + prefix);
            eq(layout.byteSize(), StreamOps.class.getField(prefix + "_SIZEOF").getLong(null), prefix + "_SIZEOF");
            for (MemoryLayout m : layout.memberLayouts()) {
                String name = m.name().orElseThrow();
                long offset = layout.byteOffset(MemoryLayout.PathElement.groupElement(name));
                eq(offset, StreamOps.class.getField(prefix + "_" + upperSnake(name)).getLong(null), prefix + "." + name);
                members++;
            }
        }
        check(layouts == 26, "26 record layouts (" + layouts + ")");
        // Fixed record sizes vs layouts.
        Object[][] sizes = {
            {"EMPTY", "EMPTY"}, {"END_PASS", "END_PASS"}, {"CLEAR_TEXTURE", "CLEAR_TEXTURE"}, {"CLEAR_REGION", "CLEAR_REGION"},
            {"COPY_BUFFER", "COPY_BUFFER"}, {"COPY_BUFFER_TEXTURE", "COPY_BUFFER_TEXTURE"}, {"COPY_TEXTURE", "COPY_TEXTURE"}, {"PRESENT", "PRESENT"},
            {"INIT_TEXTURE", "INIT_TEXTURE"}, {"TIMESTAMP", "TIMESTAMP"}, {"SET_PIPELINE", "SET_PIPELINE"}, {"SET_BUFFER", "SET_BUFFER"},
            {"SET_TEXTURE", "SET_TEXTURE"}, {"CLEAR_BINDING", "CLEAR_BINDING"}, {"SET_VERTEX_BUFFER", "SET_VERTEX_BUFFER"},
            {"SET_INDEX_BUFFER", "SET_INDEX_BUFFER"}, {"SET_SCISSOR", "SET_SCISSOR"}, {"DRAW", "DRAW"}, {"DRAW_INDEXED", "DRAW_INDEXED"},
            {"DRAW_INDIRECT", "DRAW_INDIRECT"}, {"UPSCALE", "UPSCALE"}, {"BEGIN_PASS_BASE", "BEGIN_PASS"}, {"COLOR_ATTACHMENT", "COLOR_ATTACHMENT"},
        };
        for (Object[] pair : sizes) {
            StructLayout layout = (StructLayout)StreamOps.Layouts.class.getField((String)pair[1]).get(null);
            eq(layout.byteSize(), StreamOps.class.getField("SIZE_" + pair[0]).getInt(null), "SIZE_" + pair[0]);
        }
        // Every opcode has a decoder layout (the test decoder covers the whole op table).
        for (Field f : StreamOps.class.getFields()) {
            if (f.getName().startsWith("OP_") && !f.getName().equals("OP_LIMIT")) {
                check(I4StreamDecoder.layoutFor(f.getInt(null)) != null, "decoder layout for " + f.getName());
                check(f.getInt(null) < StreamOps.OP_LIMIT, f.getName() + " < OP_LIMIT");
            }
        }
        // header() packs op/size/arg exactly as mcmtl_cmd_header.
        long buf = MemoryUtil.nmemAlignedAlloc(16, 16);
        MemoryUtil.memPutLong(buf, StreamOps.header(0xABCD, 0x1230, 0xFEDCBA98));
        MemorySegment seg = MemorySegment.ofAddress(buf).reinterpret(16);
        eq(0xABCD, seg.get(java.lang.foreign.ValueLayout.JAVA_SHORT, StreamOps.HEADER_OP) & 0xFFFF, "header op");
        eq(0x1230, seg.get(java.lang.foreign.ValueLayout.JAVA_SHORT, StreamOps.HEADER_SIZE) & 0xFFFF, "header size");
        eq(0xFEDCBA98, seg.get(java.lang.foreign.ValueLayout.JAVA_INT, StreamOps.HEADER_ARG), "header arg");
        MemoryUtil.nmemAlignedFree(buf);
        System.out.println("    " + layouts + " layouts, " + members + " fields cross-checked against StreamOps constants");
    }

    static void testBeginEndPass() {
        Fixture fx = new Fixture(false);
        MetalTexture color0 = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 256, 128, 1, 3);
        MetalTexture color2 = texture(fx.device, GpuFormat.RGBA16_FLOAT, RT, 128, 64, 1, 1);
        MetalTexture depth = texture(fx.device, GpuFormat.D32_FLOAT, RT, 256, 128, 1, 3);
        MetalTextureView v0 = partialView(color0, 1, 1);
        MetalTextureView v2 = fullView(color2);
        MetalTextureView vd = partialView(depth, 1, 1);
        RenderPassDescriptor descriptor = RenderPassDescriptor.builder(forbiddenLabel())
            .withColorAttachment(v0, Optional.<Vector4fc>of(new Vector4f(0.25F, 0.5F, 0.75F, 1.0F)))
            .withUnusedColorAttachment()
            .withColorAttachment(v2)
            .withDepthAttachment(vd, OptionalDouble.of(0.5))
            .withRenderArea(new RenderPass.RenderArea(4, 8, 100, 50))
            .build();
        check(!fx.encoder.isInRenderPass(), "not in pass");
        RenderPassBackend pass = fx.encoder.createRenderPass(descriptor);
        check(fx.encoder.isInRenderPass(), "in pass");
        expectThrows(IllegalStateException.class, () -> fx.encoder.createRenderPass(descriptor), "second createRenderPass");
        expectThrows(IllegalStateException.class, fx.encoder::submit, "submit with a pass open");
        eq(0, fx.submits.size(), "no native submit with a pass open");
        fx.encoder.submitRenderPass();
        check(!fx.encoder.isInRenderPass(), "pass ended");
        expectThrows(IllegalStateException.class, fx.encoder::submitRenderPass, "submitRenderPass without a pass");
        List<I4StreamDecoder.Rec> recs = fx.main();
        eqo(List.of("BEGIN_PASS", "END_PASS"), I4StreamDecoder.ops(recs), "records");
        I4StreamDecoder.Rec b = recs.get(0);
        eq(48 + 3 * 32, b.size, "BEGIN_PASS size");
        eq(3, b.i("colorCount"), "colorCount (null slot included)");
        eq(StreamOps.PASS_FLAG_DEPTH_CLEAR, b.i("flags"), "depth clear flag");
        eq(0b001, b.i("colorClearMask"), "clear mask");
        eq(4, b.i("renderX"), "renderX");
        eq(8, b.i("renderY"), "renderY");
        eq(100, b.i("renderWidth"), "renderWidth");
        eq(50, b.i("renderHeight"), "renderHeight");
        eqf(0.5F, b.f("depthClearValue"), "depthClearValue");
        eq(1, b.i("depthLevel"), "depthLevel = view base mip");
        eq(depth.handle(), b.l("depthTexture"), "depth parent texture handle");
        I4StreamDecoder.Rec a0 = b.attachment(0);
        eq(color0.handle(), a0.l("texture"), "attachment 0 = parent texture (not the view)");
        eq(1, a0.i("level"), "attachment 0 level");
        check(Arrays.equals(new float[]{0.25F, 0.5F, 0.75F, 1.0F}, a0.fa("clearColor")), "attachment 0 clear color RGBA");
        I4StreamDecoder.Rec a1 = b.attachment(1);
        eq(0, a1.l("texture"), "unused slot texture 0");
        eq(0, a1.i("level"), "unused slot level");
        I4StreamDecoder.Rec a2 = b.attachment(2);
        eq(color2.handle(), a2.l("texture"), "attachment 2");
        check(Arrays.equals(new float[4], a2.fa("clearColor")), "load attachment has zero clear color");
        // Depth-only pass with Load, default render area.
        fx.resetMain();
        fx.encoder.createRenderPass(RenderPassDescriptor.builder(forbiddenLabel()).withDepthAttachment(fullView(depth)).build());
        fx.encoder.submitRenderPass();
        I4StreamDecoder.Rec d = fx.main().get(0);
        eq(0, d.i("colorCount"), "depth-only colorCount");
        eq(0, d.i("flags"), "no depth clear");
        eq(256, d.i("renderWidth"), "default render area width");
        eq(depth.handle(), d.l("depthTexture"), "depth-only texture");
        eq(48, d.size, "BEGIN_PASS without colors");
        check(pass != fx.encoder.currentPass(), "passes are distinct objects");
    }

    static void testDebugLabels() {
        Fixture fx = new Fixture(true);
        MetalTexture color = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 64, 64, 1, 1);
        RenderPassBackend pass = fx.encoder.createRenderPass(simplePass(fullView(color), () -> "Main"));
        pass.pushDebugGroup(() -> "terrain");
        pass.pushDebugGroup(() -> "über\u0000x");
        pass.popDebugGroup();
        fx.encoder.submitRenderPass(); // one group still open: closed before END_PASS
        List<I4StreamDecoder.Rec> recs = fx.main();
        eqo(List.of("PUSH_DEBUG_GROUP", "BEGIN_PASS", "PUSH_DEBUG_GROUP", "PUSH_DEBUG_GROUP", "POP_DEBUG_GROUP", "POP_DEBUG_GROUP", "END_PASS", "POP_DEBUG_GROUP"),
            I4StreamDecoder.ops(recs), "labelled pass records");
        eqo("Main", label(recs.get(0)), "pass label");
        eq(16, recs.get(0).size, "short label record size");
        eqo("terrain", label(recs.get(2)), "group label");
        eqo("über?x", label(recs.get(3)), "UTF-8 label, NUL replaced");
        // Long labels are truncated at a character boundary.
        fx.resetMain();
        fx.encoder.recordPushDebugGroup("a".repeat(2000));
        fx.encoder.recordPushDebugGroup("a" + "é".repeat(600));
        fx.encoder.recordPopDebugGroup();
        fx.encoder.recordPopDebugGroup();
        recs = fx.main();
        eq(1024, recs.get(0).arg, "ASCII label truncated to MAX_LABEL_BYTES");
        eq(StreamOps.pushDebugGroupSize(1024), recs.get(0).size, "max label record size");
        eq(1023, recs.get(1).arg, "multi-byte label cut on a character boundary");
        eqo("a" + "é".repeat(511), label(recs.get(1)), "truncated label decodes");
        // Labels off: suppliers are never evaluated, nothing is recorded.
        Fixture off = new Fixture(false);
        RenderPassBackend p2 = off.encoder.createRenderPass(simplePass(fullView(texture(off.device, GpuFormat.RGBA8_UNORM, RT, 8, 8, 1, 1)), forbiddenLabel()));
        p2.pushDebugGroup(forbiddenLabel());
        p2.popDebugGroup();
        off.encoder.submitRenderPass();
        eqo(List.of("BEGIN_PASS", "END_PASS"), I4StreamDecoder.ops(off.main()), "no debug records with labels off");
    }

    static String label(final I4StreamDecoder.Rec r) {
        return new String(r.payload(StreamOps.PUSH_DEBUG_GROUP_SIZEOF, r.arg), StandardCharsets.UTF_8);
    }

    static void testUniformDedupe() {
        Fixture fx = new Fixture(false);
        MetalTexture color = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 64, 64, 1, 2);
        MetalRenderPipeline p1 = pipeline(fx.device, "p1", 0, 0);
        MetalRenderPipeline p2 = pipeline(fx.device, "p2", 0, 0);
        TestBuffer bufA = TestBuffer.create(fx.device, GpuBuffer.USAGE_UNIFORM, 256, 0);
        TestBuffer bufB = TestBuffer.create(fx.device, GpuBuffer.USAGE_UNIFORM, 256, 256);
        TestBuffer texel = TestBuffer.create(fx.device, GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, 64, 0);
        MetalTextureView view0 = fullView(color);
        MetalTextureView viewPartial = partialView(color, 1, 1);
        MetalSampler samplerA = sampler();
        MetalSampler samplerB = sampler();
        RenderPassBackend pass = fx.encoder.createRenderPass(simplePass(fullView(color), forbiddenLabel()));
        fx.resetMain(); // keep BEGIN_PASS out of the expectations below (the validator needs it, see end)
        pass.setUniform(0, bufA.slice(0, 64));                                  // no pipeline: ignored
        pass.setPipeline(p1);                                                   // SET_PIPELINE
        pass.setUniform(0, bufA.slice(0, 64));                                  // SET_BUFFER
        pass.setUniform(0, new GpuBufferSlice(bufA, 0, 64));                   // repeat: dropped
        pass.setUniform(0, new GpuBufferSlice(bufA, 0, 128));                  // same buffer+offset: dropped
        pass.setUniform(0, new GpuBufferSlice(bufA, 64, 64));                  // SET_BUFFER offset 64
        pass.setUniform(1, new GpuBufferSlice(bufB, 32, 64));                  // SET_BUFFER backing 256 + 32
        pass.setUniform(2, new TextureViewAndSampler(view0, samplerA));        // SET_TEXTURE
        pass.setUniform(2, new TextureViewAndSampler(view0, samplerA));        // dropped
        pass.setUniform(2, new TextureViewAndSampler(view0, samplerB));        // SET_TEXTURE (sampler changed)
        pass.setUniform(2, new TextureViewAndSampler(viewPartial, samplerB));  // SET_TEXTURE (view changed)
        pass.setUniform(3, texel.slice());                                      // SET_TEXTURE texel view
        pass.setUniform(3, texel.slice());                                      // dropped, no 2nd texelView call
        pass.setUniform(4, bufA.slice());                                       // stages 0: dropped
        pass.setUniform(99, bufA.slice());                                      // out of range
        pass.setUniform(-1, bufA.slice());                                      // out of range
        pass.setUniform(0, null);                                               // CLEAR_BINDING buffer
        pass.setUniform(0, null);                                               // already clear
        pass.setUniform(2, null);                                               // CLEAR_BINDING texture
        pass.setUniform(4, null);                                               // unused index
        pass.setUniform(0, new GpuBufferSlice(bufA, 64, 64));                  // SET_BUFFER again after the clear
        pass.setPipeline(p1);                                                   // same pipeline: nothing, state kept
        pass.setUniform(0, new GpuBufferSlice(bufA, 64, 64));                  // dropped
        pass.setPipeline(p2);                                                   // SET_PIPELINE, state cleared
        pass.setUniform(0, new GpuBufferSlice(bufA, 64, 64));                  // SET_BUFFER
        pass.setUniform(1, new GpuBufferSlice(bufB, 32, 64));                  // SET_BUFFER
        pass.setUniform(0, null);                                               // CLEAR_BINDING
        pass.setUniform(2, null);                                               // nothing set since setPipeline: dropped
        byte[] bytes = I4StreamDecoder.snapshot(fx.encoder.mainStream());
        // Wrap in BEGIN/END so the pass-op validator accepts the fragment.
        List<I4StreamDecoder.Rec> recs = parseInPass(bytes);
        eqo(List.of("SET_PIPELINE", "SET_BUFFER", "SET_BUFFER", "SET_BUFFER", "SET_TEXTURE", "SET_TEXTURE", "SET_TEXTURE", "SET_TEXTURE",
                "CLEAR_BINDING", "CLEAR_BINDING", "SET_BUFFER", "SET_PIPELINE", "SET_BUFFER", "SET_BUFFER", "CLEAR_BINDING"),
            I4StreamDecoder.ops(recs), "dedupe sequence");
        eq(p1.handle(), recs.get(0).l("pipeline"), "SET_PIPELINE handle");
        checkSetBuffer(recs.get(1), 0, V, bufA.handle(), 0, "Projection @0");
        checkSetBuffer(recs.get(2), 0, V, bufA.handle(), 64, "Projection @64");
        checkSetBuffer(recs.get(3), 1, V | F, bufB.handle(), 256 + 32, "Globals with backing offset");
        checkSetTexture(recs.get(4), 0, F, color.handle(), samplerA.handle(), "Sampler0 full view = parent handle");
        checkSetTexture(recs.get(5), 0, F, color.handle(), samplerB.handle(), "Sampler0 sampler B");
        checkSetTexture(recs.get(6), 0, F, viewPartial.viewHandle(), samplerB.handle(), "Sampler0 partial view handle");
        checkSetTexture(recs.get(7), 3, V, texel.texelViewHandle, 0L, "CloudFaces texel view, no sampler");
        eq(1, texel.texelViewCalls, "texelView created once");
        eq(MetalFormats.PIXEL_FORMAT_R8_SINT, texel.texelFormat, "texel view format");
        eq(1, texel.texelBytes, "texel view bytes per texel");
        I4StreamDecoder.Rec c0 = recs.get(8);
        eq(0, c0.i("index"), "clear binding slot");
        eq(V, c0.i("stages"), "clear binding stages");
        eq(StreamOps.BINDING_KIND_BUFFER, c0.i("kind"), "clear binding kind buffer");
        I4StreamDecoder.Rec c1 = recs.get(9);
        eq(0, c1.i("index"), "clear texture slot");
        eq(F, c1.i("stages"), "clear texture stages");
        eq(StreamOps.BINDING_KIND_TEXTURE, c1.i("kind"), "clear binding kind texture");
        eq(p2.handle(), recs.get(11).l("pipeline"), "second SET_PIPELINE");
        long seq = fx.pending();
        eq(seq, bufA.lastUseSeq(), "bufA marked used");
        eq(seq, bufB.lastUseSeq(), "bufB marked used");
        eq(seq, texel.lastUseSeq(), "texel buffer marked used");
        fx.encoder.submitRenderPass();
        // A new pass starts with clean state: the same pipeline and uniform are recorded again.
        fx.resetMain();
        RenderPassBackend pass2 = fx.encoder.createRenderPass(simplePass(fullView(color), forbiddenLabel()));
        pass2.setPipeline(p2);
        pass2.setUniform(0, new GpuBufferSlice(bufA, 64, 64));
        fx.encoder.submitRenderPass();
        eqo(List.of("BEGIN_PASS", "SET_PIPELINE", "SET_BUFFER", "END_PASS"), I4StreamDecoder.ops(fx.main()), "state reset per pass");
    }

    /** Validates a pass-level fragment by wrapping it in a minimal BEGIN_PASS / END_PASS. */
    static List<I4StreamDecoder.Rec> parseInPass(final byte[] fragment) {
        byte[] wrapped = new byte[48 + fragment.length + 16];
        ByteBuffer w = ByteBuffer.wrap(wrapped).order(ByteOrder.LITTLE_ENDIAN);
        w.putLong(0, StreamOps.header(StreamOps.OP_BEGIN_PASS, 48, 0));
        System.arraycopy(fragment, 0, wrapped, 48, fragment.length);
        w.putLong(48 + fragment.length, StreamOps.header(StreamOps.OP_END_PASS, 16, 0));
        List<I4StreamDecoder.Rec> all = I4StreamDecoder.parse(wrapped, false);
        return all.subList(1, all.size() - 1);
    }

    static void checkSetBuffer(final I4StreamDecoder.Rec r, final int index, final int stages, final long buffer, final long offset, final String what) {
        eq(StreamOps.OP_SET_BUFFER, r.op, what + " op");
        eq(index, r.i("index"), what + " index");
        eq(stages, r.i("stages"), what + " stages");
        eq(buffer, r.l("buffer"), what + " buffer");
        eq(offset, r.l("offset"), what + " offset");
    }

    static void checkSetTexture(final I4StreamDecoder.Rec r, final int index, final int stages, final long texture, final long sampler, final String what) {
        eq(StreamOps.OP_SET_TEXTURE, r.op, what + " op");
        eq(index, r.i("index"), what + " index");
        eq(stages, r.i("stages"), what + " stages");
        eq(texture, r.l("texture"), what + " texture");
        eq(sampler, r.l("sampler"), what + " sampler");
    }

    static void testVertexIndexScissorDraws() {
        Fixture fx = new Fixture(false);
        MetalTexture color = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 64, 64, 1, 1);
        MetalRenderPipeline p = pipeline(fx.device, "p", 0, 0);
        TestBuffer vb = TestBuffer.create(fx.device, GpuBuffer.USAGE_VERTEX, 4096, 0);
        TestBuffer vb2 = TestBuffer.create(fx.device, GpuBuffer.USAGE_VERTEX, 4096, 48);
        TestBuffer ib = TestBuffer.create(fx.device, GpuBuffer.USAGE_INDEX, 4096, 0);
        TestBuffer indirect = TestBuffer.create(fx.device, GpuBuffer.USAGE_INDIRECT_PARAMETERS, 4096, 32);
        RenderPassBackend pass = fx.encoder.createRenderPass(simplePass(fullView(color), forbiddenLabel()));
        expectThrows(IllegalStateException.class, () -> pass.draw(3, 1, 0, 0), "draw without a pipeline");
        pass.setVertexBuffer(0, null);                                  // nothing (keeps previous binding)
        pass.setVertexBuffer(0, vb.slice());                            // SET_VERTEX_BUFFER slot 0 @0
        pass.setVertexBuffer(0, new GpuBufferSlice(vb, 0, 4096));      // dropped
        pass.setVertexBuffer(0, new GpuBufferSlice(vb, 96, 128));      // @96
        pass.setVertexBuffer(0, null);                                  // nothing
        pass.setVertexBuffer(1, new GpuBufferSlice(vb2, 16, 64));      // slot 1 @48+16
        expectThrows(IllegalArgumentException.class, () -> pass.setVertexBuffer(16, vb.slice()), "slot 16");
        pass.setIndexBuffer(ib, IndexType.SHORT);                       // SET_INDEX_BUFFER u16
        pass.setIndexBuffer(ib, IndexType.SHORT);                       // dropped
        pass.setIndexBuffer(ib, IndexType.INT);                         // u32
        pass.setPipeline(p);
        pass.enableScissor(1, 2, 3, 4);                                 // SET_SCISSOR
        pass.enableScissor(1, 2, 3, 4);                                 // dropped
        pass.disableScissor();                                          // RESET_SCISSOR
        pass.disableScissor();                                          // dropped
        pass.enableScissor(1, 2, 3, 4);                                 // SET_SCISSOR again
        pass.draw(10, 1, 0, 0);                                         // DRAW
        pass.draw(0, 1, 0, 0);                                          // nothing
        pass.draw(3, 0, 0, 0);                                          // nothing
        expectThrows(IllegalArgumentException.class, () -> pass.draw(-1, 1, 0, 0), "negative vertex count");
        pass.drawIndexed(36, 2, 6, -5000, 3);                           // DRAW_INDEXED
        pass.drawIndirect(new GpuBufferSlice(indirect, 16, 32), 2);     // DRAW_INDIRECT
        pass.drawIndexedIndirect(new GpuBufferSlice(indirect, 40, 60), 3); // DRAW_INDEXED_INDIRECT
        pass.drawIndirect(new GpuBufferSlice(indirect, 16, 32), 0);     // nothing
        expectThrows(UnsupportedOperationException.class, () -> pass.multiDraw(MemoryUtil.memAllocInt(4), 1, 0, 2), "multiDraw interleaved");
        expectThrows(UnsupportedOperationException.class, () -> pass.multiDraw(MemoryUtil.memAllocInt(2), MemoryUtil.memAllocInt(2), 2), "multiDraw separate");
        expectThrows(UnsupportedOperationException.class, () -> pass.multiDrawIndexed(MemoryUtil.memAllocInt(6), 1, 0, 2), "multiDrawIndexed interleaved");
        expectThrows(UnsupportedOperationException.class,
            () -> pass.multiDrawIndexed(PointerBuffer.allocateDirect(2), MemoryUtil.memAllocInt(2), MemoryUtil.memAllocInt(2), 2), "multiDrawIndexed separate");
        fx.encoder.submitRenderPass();
        List<I4StreamDecoder.Rec> recs = fx.main();
        eqo(List.of("BEGIN_PASS", "SET_VERTEX_BUFFER", "SET_VERTEX_BUFFER", "SET_VERTEX_BUFFER", "SET_INDEX_BUFFER", "SET_INDEX_BUFFER", "SET_PIPELINE",
                "SET_SCISSOR", "RESET_SCISSOR", "SET_SCISSOR", "DRAW", "DRAW_INDEXED", "DRAW_INDIRECT", "DRAW_INDEXED_INDIRECT", "END_PASS"),
            I4StreamDecoder.ops(recs), "records");
        eq(0, recs.get(1).i("slot"), "vb slot");
        eq(vb.handle(), recs.get(1).l("buffer"), "vb handle");
        eq(0, recs.get(1).l("offset"), "vb offset");
        eq(96, recs.get(2).l("offset"), "vb new offset");
        eq(1, recs.get(3).i("slot"), "vb2 slot");
        eq(vb2.handle(), recs.get(3).l("buffer"), "vb2 handle");
        eq(48 + 16, recs.get(3).l("offset"), "vb2 offset includes backing offset");
        eq(MetalFormats.INDEX_TYPE_UINT16, recs.get(4).i("indexType"), "u16");
        eq(ib.handle(), recs.get(4).l("buffer"), "ib handle");
        eq(MetalFormats.INDEX_TYPE_UINT32, recs.get(5).i("indexType"), "u32");
        I4StreamDecoder.Rec sc = recs.get(7);
        eq(1, sc.i("x"), "scissor x");
        eq(2, sc.i("y"), "scissor y");
        eq(3, sc.i("width"), "scissor w");
        eq(4, sc.i("height"), "scissor h");
        I4StreamDecoder.Rec draw = recs.get(10);
        eq(10, draw.i("vertexCount"), "vertexCount");
        eq(1, draw.i("instanceCount"), "instanceCount");
        eq(0, draw.i("firstVertex"), "firstVertex");
        eq(0, draw.i("firstInstance"), "firstInstance");
        I4StreamDecoder.Rec di = recs.get(11);
        eq(36, di.i("indexCount"), "indexCount");
        eq(2, di.i("instanceCount"), "indexed instanceCount");
        eq(6, di.i("firstIndex"), "firstIndex");
        eq(-5000, di.i("baseVertex"), "signed baseVertex");
        eq(3, di.i("firstInstance"), "indexed firstInstance");
        I4StreamDecoder.Rec ind = recs.get(12);
        eq(2, ind.i("drawCount"), "indirect drawCount");
        eq(indirect.handle(), ind.l("buffer"), "indirect buffer");
        eq(32 + 16, ind.l("offset"), "indirect offset includes backing");
        I4StreamDecoder.Rec iind = recs.get(13);
        eq(3, iind.i("drawCount"), "indexed indirect drawCount");
        eq(32 + 40, iind.l("offset"), "indexed indirect offset");
        long seq = fx.pending();
        eq(seq, vb.lastUseSeq(), "vb marked used");
        eq(seq, vb2.lastUseSeq(), "vb2 marked used");
        eq(seq, ib.lastUseSeq(), "ib marked used");
        eq(seq, indirect.lastUseSeq(), "indirect buffer marked used");
    }

    static void testClosedPassThrows() {
        Fixture fx = new Fixture(true);
        MetalTexture color = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 64, 64, 1, 1);
        MetalRenderPipeline p = pipeline(fx.device, "p", 16, V);
        TestBuffer buf = TestBuffer.create(fx.device, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_INDIRECT_PARAMETERS, 256, 0);
        MetalQueryPool pool = new MetalQueryPool(fx.device, newHandle(), 4);
        RenderPassBackend pass = fx.encoder.createRenderPass(simplePass(fullView(color), () -> "x"));
        fx.encoder.submitRenderPass();
        long size = fx.encoder.mainStream().size();
        ByteBuffer pc = MemoryUtil.memCalloc(16);
        Body[] calls = {
            () -> pass.pushDebugGroup(() -> "g"), pass::popDebugGroup, () -> pass.setPipeline(p), () -> pass.setUniform(0, buf.slice()),
            () -> pass.setUniform(0, null), () -> pass.pushConstants(pc), () -> pass.enableScissor(0, 0, 1, 1), pass::disableScissor,
            () -> pass.setVertexBuffer(0, buf.slice()), () -> pass.setVertexBuffer(0, null), () -> pass.setIndexBuffer(buf, IndexType.SHORT),
            () -> pass.drawIndexed(3, 1, 0, 0, 0), () -> pass.draw(3, 1, 0, 0), () -> pass.drawIndirect(buf.slice(), 1),
            () -> pass.drawIndexedIndirect(buf.slice(), 1), () -> pass.writeTimestamp(pool, 0),
            () -> pass.multiDraw(MemoryUtil.memAllocInt(4), 1, 0, 2), () -> pass.multiDraw(MemoryUtil.memAllocInt(2), MemoryUtil.memAllocInt(2), 2),
            () -> pass.multiDrawIndexed(MemoryUtil.memAllocInt(6), 1, 0, 2),
            () -> pass.multiDrawIndexed(PointerBuffer.allocateDirect(2), MemoryUtil.memAllocInt(2), MemoryUtil.memAllocInt(2), 2),
        };
        for (int k = 0; k < calls.length; k++) {
            expectThrows(IllegalStateException.class, calls[k], "closed pass call #" + k);
        }
        eq(size, fx.encoder.mainStream().size(), "nothing recorded through a closed pass");
        // A stale pass stays closed while a new pass is open (no pooling of pass objects).
        RenderPassBackend pass2 = fx.encoder.createRenderPass(simplePass(fullView(color), () -> "y"));
        expectThrows(IllegalStateException.class, () -> pass.setPipeline(p), "stale pass while another is open");
        pass2.setPipeline(p);
        fx.encoder.submitRenderPass();
        fx.main();
    }

    static void testPushConstants() {
        Fixture fx = new Fixture(false);
        MetalTexture color = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 64, 64, 1, 1);
        MetalRenderPipeline p = pipeline(fx.device, "pc", 20, V | F);
        MetalRenderPipeline none = pipeline(fx.device, "none", 0, 0);
        RenderPassBackend pass = fx.encoder.createRenderPass(simplePass(fullView(color), forbiddenLabel()));
        ByteBuffer data = pattern(32, 3);
        data.position(4);
        expectThrows(IllegalStateException.class, () -> pass.pushConstants(data), "push constants without a pipeline");
        pass.setPipeline(p);
        pass.pushConstants(data);
        eq(4, data.position(), "source position unchanged");
        ByteBuffer heap = ByteBuffer.allocate(24);
        for (int k = 0; k < 24; k++) {
            heap.put(k, (byte)(100 + k));
        }
        heap.position(2);
        pass.pushConstants(heap);
        ByteBuffer small = MemoryUtil.memCalloc(8);
        expectThrows(IllegalArgumentException.class, () -> pass.pushConstants(small), "too few bytes");
        pass.setPipeline(none);
        pass.pushConstants(small); // size 0: nothing
        fx.encoder.submitRenderPass();
        List<I4StreamDecoder.Rec> recs = fx.main();
        eqo(List.of("BEGIN_PASS", "SET_PIPELINE", "PUSH_CONSTANTS", "PUSH_CONSTANTS", "SET_PIPELINE", "END_PASS"), I4StreamDecoder.ops(recs), "records");
        I4StreamDecoder.Rec r = recs.get(2);
        eq(16 + 32, r.size, "record size 16 + align16(20)");
        eq(20, r.i("byteLength"), "byteLength");
        eq(V | F, r.i("stages"), "stages");
        byte[] expected = new byte[20];
        for (int k = 0; k < 20; k++) {
            expected[k] = (byte)(3 + (k + 4) * 7);
        }
        check(Arrays.equals(expected, r.payload(16, 20)), "direct payload = bytes from position");
        byte[] expectedHeap = new byte[20];
        for (int k = 0; k < 20; k++) {
            expectedHeap[k] = (byte)(102 + k);
        }
        check(Arrays.equals(expectedHeap, recs.get(3).payload(16, 20)), "heap payload");
    }

    static void testClears() {
        Fixture fx = new Fixture(false);
        MetalTexture color = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 64, 32, 1, 3);
        MetalTexture depth = texture(fx.device, GpuFormat.D32_FLOAT, RT, 64, 32, 1, 3);
        MetalTexture smallDepth = texture(fx.device, GpuFormat.D32_FLOAT, RT, 32, 32, 1, 3);
        Vector4f c = new Vector4f(0.1F, 0.2F, 0.3F, 0.4F);
        fx.encoder.clearColorTexture(color, c);
        fx.encoder.clearDepthTexture(depth, 0.25);
        fx.encoder.clearColorAndDepthTextures(color, c, depth, 1.0);
        fx.encoder.clearColorAndDepthTextures(color, c, depth, 0.5, 2, 3, 10, 5, 1);
        expectThrows(IllegalArgumentException.class, () -> fx.encoder.clearColorAndDepthTextures(color, c, smallDepth, 0.5, 0, 0, 1, 1, 0), "depth size mismatch");
        expectThrows(IllegalArgumentException.class, () -> fx.encoder.clearColorAndDepthTextures(color, c, depth, 0.5, 30, 0, 4, 1, 1), "region outside mip");
        List<I4StreamDecoder.Rec> recs = fx.main();
        eqo(List.of("CLEAR_TEXTURE", "CLEAR_TEXTURE", "CLEAR_TEXTURE", "CLEAR_TEXTURE", "CLEAR_REGION"), I4StreamDecoder.ops(recs), "clear records");
        I4StreamDecoder.Rec ct = recs.get(0);
        eq(StreamOps.ASPECT_COLOR, ct.i("aspects"), "color aspect");
        eq(color.handle(), ct.l("texture"), "color texture");
        eq(0, ct.i("baseLevel"), "baseLevel");
        eq(3, ct.i("levelCount"), "all mips");
        eq(0, ct.i("slice"), "slice");
        check(Arrays.equals(new float[]{0.1F, 0.2F, 0.3F, 0.4F}, ct.fa("color")), "clear color");
        I4StreamDecoder.Rec dt = recs.get(1);
        eq(StreamOps.ASPECT_DEPTH, dt.i("aspects"), "depth aspect");
        eqf(0.25F, dt.f("depth"), "depth value");
        check(Arrays.equals(new float[4], dt.fa("color")), "depth clear has no color");
        eq(StreamOps.ASPECT_COLOR, recs.get(2).i("aspects"), "pair: color first");
        eq(StreamOps.ASPECT_DEPTH, recs.get(3).i("aspects"), "pair: depth second");
        eqf(1.0F, recs.get(3).f("depth"), "pair depth");
        I4StreamDecoder.Rec rg = recs.get(4);
        eq(1, rg.i("level"), "region level");
        eq(color.handle(), rg.l("colorTexture"), "region color");
        eq(depth.handle(), rg.l("depthTexture"), "region depth");
        eq(2, rg.i("x"), "region x");
        eq(3, rg.i("y"), "region y");
        eq(10, rg.i("width"), "region w");
        eq(5, rg.i("height"), "region h");
        eqf(0.5F, rg.f("depth"), "region depth value");
        RenderPassBackend pass = fx.encoder.createRenderPass(simplePass(fullView(color), forbiddenLabel()));
        expectThrows(IllegalStateException.class, () -> fx.encoder.clearColorTexture(color, c), "clear inside a pass");
        fx.encoder.submitRenderPass();
    }

    static void testWriteToBuffer() {
        Fixture fx = new Fixture(false);
        fx.autoComplete = false;
        MetalTexture color = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 64, 64, 1, 1);
        MetalRenderPipeline p = pipeline(fx.device, "p", 0, 0);
        TestBuffer dst = TestBuffer.create(fx.device, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 512, 64);
        ByteBuffer data = pattern(48, 9);
        data.position(5);
        // 1. Idle buffer: CPU write, no record.
        fx.encoder.writeToBuffer(new GpuBufferSlice(dst, 32, 64), data);
        eq(0, fx.encoder.mainStream().size(), "fast path records nothing");
        eq(5, data.position(), "source position unchanged");
        eq(48, data.limit(), "source limit unchanged");
        check(Arrays.equals(slice(data), I4StreamDecoder.read(dst.contents() + 32, 43)), "fast path bytes at contents + offset");
        // 2. Buffer referenced by recorded work: staged copy in stream order.
        RenderPassBackend pass = fx.encoder.createRenderPass(simplePass(fullView(color), forbiddenLabel()));
        pass.setPipeline(p);
        pass.setUniform(0, new GpuBufferSlice(dst, 0, 64));
        fx.encoder.submitRenderPass();
        fx.resetMain();
        byte[] before = I4StreamDecoder.read(dst.contents(), 512);
        ByteBuffer data2 = pattern(40, 77);
        fx.encoder.writeToBuffer(new GpuBufferSlice(dst, 128, 64), data2);
        check(Arrays.equals(before, I4StreamDecoder.read(dst.contents(), 512)), "slow path never touches the in-use buffer");
        eq(0, data2.position(), "slow path source position unchanged");
        List<I4StreamDecoder.Rec> recs = fx.main();
        eqo(List.of("COPY_BUFFER"), I4StreamDecoder.ops(recs), "slow path record");
        I4StreamDecoder.Rec cb = recs.get(0);
        eq(dst.handle(), cb.l("dstBuffer"), "dst handle");
        eq(64 + 128, cb.l("dstOffset"), "dst offset includes backing");
        eq(40, cb.l("length"), "length");
        long srcHandle = cb.l("srcBuffer");
        check(srcHandle >= MetalTransientMemory.HANDLE_BASE, "staging is transient memory");
        eq(0, cb.l("srcOffset") & 15L, "staging offset 16-aligned");
        check(Arrays.equals(slice(data2), I4StreamDecoder.read(fx.transientMemory().resolve(srcHandle, cb.l("srcOffset")), 40)), "staged bytes");
        long[] alloc = fx.transientMemory().allocations.get(fx.transientMemory().allocations.size() - 1);
        eq(GpuBuffer.USAGE_COPY_SRC, alloc[0], "staging usage COPY_SRC");
        eq(fx.pending(), dst.lastUseSeq(), "dst marked used");
        // 3. Committed but not completed: still staged.
        fx.encoder.submit();
        fx.encoder.writeToBuffer(new GpuBufferSlice(dst, 0, 64), pattern(16, 1));
        eqo(List.of("COPY_BUFFER"), I4StreamDecoder.ops(fx.main()), "in-flight buffer is staged");
        fx.complete(1);
        fx.encoder.submit(); // commits seq 2 (with that copy); dst now last used by seq 2
        fx.complete(2);
        // 4. Completed: fast path again (heap source).
        ByteBuffer heap = ByteBuffer.allocate(8);
        heap.putLong(0, 0x1122334455667788L);
        fx.encoder.writeToBuffer(new GpuBufferSlice(dst, 256, 8), heap);
        eq(0, fx.encoder.mainStream().size(), "completed buffer: fast path");
        check(Arrays.equals(Arrays.copyOf(heap.array(), 8), I4StreamDecoder.read(dst.contents() + 256, 8)), "heap fast path bytes");
        fx.encoder.writeToBuffer(new GpuBufferSlice(dst, 0, 8), MemoryUtil.memAlloc(0));
        eq(0, fx.encoder.mainStream().size(), "empty write records nothing");
        expectThrows(IllegalArgumentException.class, () -> fx.encoder.writeToBuffer(new GpuBufferSlice(dst, 0, 4), pattern(8, 0)), "write larger than slice");
    }

    static byte[] slice(final ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        for (int k = 0; k < out.length; k++) {
            out[k] = b.get(b.position() + k);
        }
        return out;
    }

    static void testCopyToBuffer() {
        Fixture fx = new Fixture(false);
        TestBuffer a = TestBuffer.create(fx.device, GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST, 1024, 32);
        TestBuffer b = TestBuffer.create(fx.device, GpuBuffer.USAGE_COPY_DST, 1024, 0);
        fx.encoder.copyToBuffer(new GpuBufferSlice(a, 16, 100), new GpuBufferSlice(b, 200, 100));
        fx.encoder.copyToBuffer(new GpuBufferSlice(a, 0, 64), new GpuBufferSlice(a, 64, 64));     // same buffer, disjoint
        fx.encoder.copyToBuffer(new GpuBufferSlice(a, 0, 64), new GpuBufferSlice(a, 32, 64));     // overlap: staged
        expectThrows(IllegalArgumentException.class, () -> fx.encoder.copyToBuffer(new GpuBufferSlice(a, 0, 8), new GpuBufferSlice(b, 0, 16)), "length mismatch");
        List<I4StreamDecoder.Rec> recs = fx.main();
        eqo(List.of("COPY_BUFFER", "COPY_BUFFER", "COPY_BUFFER", "COPY_BUFFER"), I4StreamDecoder.ops(recs), "records");
        I4StreamDecoder.Rec r0 = recs.get(0);
        eq(a.handle(), r0.l("srcBuffer"), "src");
        eq(32 + 16, r0.l("srcOffset"), "src offset with backing");
        eq(b.handle(), r0.l("dstBuffer"), "dst");
        eq(200, r0.l("dstOffset"), "dst offset");
        eq(100, r0.l("length"), "length");
        I4StreamDecoder.Rec r1 = recs.get(1);
        eq(a.handle(), r1.l("srcBuffer"), "same-buffer src");
        eq(a.handle(), r1.l("dstBuffer"), "same-buffer dst");
        eq(32 + 64, r1.l("dstOffset"), "same-buffer dst offset");
        I4StreamDecoder.Rec s1 = recs.get(2);
        I4StreamDecoder.Rec s2 = recs.get(3);
        eq(a.handle(), s1.l("srcBuffer"), "staged step 1 reads a");
        eq(32, s1.l("srcOffset"), "staged step 1 src offset");
        check(s1.l("dstBuffer") >= MetalTransientMemory.HANDLE_BASE, "staged step 1 writes scratch");
        eq(s1.l("dstBuffer"), s2.l("srcBuffer"), "step 2 reads the same scratch");
        eq(s1.l("dstOffset"), s2.l("srcOffset"), "scratch offsets match");
        eq(a.handle(), s2.l("dstBuffer"), "staged step 2 writes a");
        eq(32 + 32, s2.l("dstOffset"), "staged dst offset");
        eq(64, s1.l("length"), "staged length 1");
        eq(64, s2.l("length"), "staged length 2");
        long[] alloc = fx.transientMemory().allocations.get(fx.transientMemory().allocations.size() - 1);
        eq(GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST, alloc[0], "scratch usage");
        eq(fx.pending(), a.lastUseSeq(), "src marked used");
        eq(fx.pending(), b.lastUseSeq(), "dst marked used");
    }

    static void testWriteToTexture() {
        Fixture fx = new Fixture(false);
        MetalTexture tex = texture(fx.device, GpuFormat.RGBA8_UNORM, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, 64, 64, 1, 2);
        MetalTexture cube = texture(fx.device, GpuFormat.RGBA8_UNORM, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_CUBEMAP_COMPATIBLE, 16, 16, 6, 1);
        MetalTexture r8 = texture(fx.device, GpuFormat.R8_UNORM, GpuTexture.USAGE_COPY_DST, 16, 16, 1, 1);
        ByteBuffer src = pattern(3 + 64 + 12, 40);
        src.position(3);
        fx.encoder.writeToTexture(tex, src, 1, 0, 8, 12, 4, 4);
        eq(3, src.position(), "position unchanged");
        eq(79, src.limit(), "limit unchanged");
        long[] alloc = fx.transientMemory().allocations.get(fx.transientMemory().allocations.size() - 1);
        eq(64, alloc[1], "exactly w*h*bs bytes staged (not the whole remaining buffer)");
        fx.encoder.writeToTexture(cube, pattern(16 * 16 * 4, 0), 0, 5, 0, 0, 16, 16);
        fx.encoder.writeToTexture(r8, pattern(3 * 5, 0), 0, 0, 1, 2, 3, 5);
        expectThrows(IllegalArgumentException.class, () -> fx.encoder.writeToTexture(tex, pattern(256, 0), 1, 0, 30, 0, 4, 4), "rect outside mip 1");
        expectThrows(IllegalArgumentException.class, () -> fx.encoder.writeToTexture(tex, pattern(256, 0), 2, 0, 0, 0, 1, 1), "mip out of range");
        expectThrows(IllegalArgumentException.class, () -> fx.encoder.writeToTexture(tex, pattern(8, 0), 0, 0, 0, 0, 4, 4), "source too small");
        expectThrows(IllegalArgumentException.class, () -> fx.encoder.writeToTexture(cube, pattern(1024, 0), 0, 6, 0, 0, 4, 4), "layer out of range");
        List<I4StreamDecoder.Rec> recs = fx.main();
        eqo(List.of("COPY_BUFFER_TO_TEXTURE", "COPY_BUFFER_TO_TEXTURE", "COPY_BUFFER_TO_TEXTURE"), I4StreamDecoder.ops(recs), "records");
        I4StreamDecoder.Rec r = recs.get(0);
        eq(1, r.i("level"), "level");
        eq(tex.handle(), r.l("texture"), "texture");
        eq(16, r.i("bytesPerRow"), "bytesPerRow = w*bs");
        eq(64, r.i("bytesPerImage"), "bytesPerImage = w*h*bs");
        eq(0, r.i("slice"), "slice");
        eq(8, r.i("x"), "x");
        eq(12, r.i("y"), "y");
        eq(4, r.i("width"), "w");
        eq(4, r.i("height"), "h");
        eq(0, r.i("blitOptions"), "blit options");
        eq(0, r.l("bufferOffset") % 16, "staging offset aligned");
        check(Arrays.equals(Arrays.copyOf(slice(src), 64), I4StreamDecoder.read(fx.transientMemory().resolve(r.l("buffer"), r.l("bufferOffset")), 64)),
            "staged texels = the 64 bytes at the source position");
        eq(5, recs.get(1).i("slice"), "cube face = slice");
        eq(64, recs.get(1).i("bytesPerRow"), "cube bytesPerRow");
        eq(3, recs.get(2).i("bytesPerRow"), "R8 bytesPerRow");
        eq(15, recs.get(2).i("bytesPerImage"), "R8 bytesPerImage");
    }

    static void testCopyBufferToTexture() {
        Fixture fx = new Fixture(false);
        MetalTexture tex = texture(fx.device, GpuFormat.RGBA8_UNORM, GpuTexture.USAGE_COPY_DST, 64, 64, 1, 1);
        MetalTexture r8 = texture(fx.device, GpuFormat.R8_UNORM, GpuTexture.USAGE_COPY_DST, 64, 64, 1, 1);
        TestBuffer src = TestBuffer.create(fx.device, GpuBuffer.USAGE_COPY_SRC, 8192, 0);
        fx.encoder.copyBufferToTexture(new GpuBufferSlice(src, 64, 4096), 2, 3, 16, 16, tex, 10, 11, 4, 5, 0, 0);
        // Frontend check passes (sW*ch*bs = 320 <= 400) but the sub-rect reads to byte 472: must be rejected.
        expectThrows(IllegalArgumentException.class,
            () -> fx.encoder.copyBufferToTexture(new GpuBufferSlice(src, 0, 400), 2, 3, 16, 16, tex, 0, 0, 4, 5, 0, 0), "sx/sy overrun");
        expectThrows(IllegalArgumentException.class,
            () -> fx.encoder.copyBufferToTexture(new GpuBufferSlice(src, 0, 4096), 14, 0, 16, 16, tex, 0, 0, 4, 5, 0, 0), "sx + w > sW");
        fx.encoder.copyBufferToTexture(new GpuBufferSlice(src, 2, 4000), 0, 0, 8, 8, tex, 0, 0, 8, 8, 0, 0);   // misaligned for RGBA8: staged
        fx.encoder.copyBufferToTexture(new GpuBufferSlice(src, 3, 4000), 1, 0, 8, 8, r8, 0, 0, 4, 4, 0, 0);    // R8 accepts any offset
        List<I4StreamDecoder.Rec> recs = fx.main();
        eqo(List.of("COPY_BUFFER_TO_TEXTURE", "COPY_BUFFER", "COPY_BUFFER_TO_TEXTURE", "COPY_BUFFER_TO_TEXTURE"), I4StreamDecoder.ops(recs), "records");
        I4StreamDecoder.Rec r = recs.get(0);
        eq(src.handle(), r.l("buffer"), "buffer");
        eq(64 + (3 * 16 + 2) * 4, r.l("bufferOffset"), "first texel byte");
        eq(16 * 4, r.i("bytesPerRow"), "row pitch sW*bs");
        eq(16 * 4 * 5, r.i("bytesPerImage"), "image pitch sW*ch*bs");
        eq(10, r.i("x"), "dx");
        eq(11, r.i("y"), "dy");
        eq(4, r.i("width"), "cw");
        eq(5, r.i("height"), "ch");
        I4StreamDecoder.Rec stage = recs.get(1);
        eq(src.handle(), stage.l("srcBuffer"), "realign source");
        eq(2, stage.l("srcOffset"), "realign src offset");
        eq((7 * 8 + 8) * 4, stage.l("length"), "realigned span");
        I4StreamDecoder.Rec staged = recs.get(2);
        eq(stage.l("dstBuffer"), staged.l("buffer"), "upload from scratch");
        eq(stage.l("dstOffset"), staged.l("bufferOffset"), "scratch offset");
        eq(0, staged.l("bufferOffset") % 4, "aligned to the texel size");
        eq(32, staged.i("bytesPerRow"), "staged row pitch");
        eq(3 + 1, recs.get(3).l("bufferOffset"), "R8 unaligned offset kept");
        eq(fx.pending(), src.lastUseSeq(), "source marked used");
    }

    static void testCopyTextureToBuffer() {
        Fixture fx = new Fixture(false);
        fx.autoComplete = false;
        MetalTexture tex = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 32, 16, 1, 2);
        MetalTexture noSrc = texture(fx.device, GpuFormat.RGBA8_UNORM, GpuTexture.USAGE_RENDER_ATTACHMENT, 32, 16, 1, 1);
        MetalTexture cube = texture(fx.device, GpuFormat.RGBA8_UNORM, RT | GpuTexture.USAGE_CUBEMAP_COMPATIBLE, 16, 16, 6, 1);
        MetalTexture depth = texture(fx.device, GpuFormat.D32_FLOAT, RT, 32, 16, 1, 1);
        MetalTexture depthStencil = texture(fx.device, GpuFormat.D32_FLOAT_S8_UINT, RT, 8, 8, 1, 1);
        TestBuffer buf = TestBuffer.create(fx.device, GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 32 * 16 * 4 + 64, 0);
        TestBuffer noDst = TestBuffer.create(fx.device, GpuBuffer.USAGE_MAP_READ, 4096, 0);
        List<String> order = new ArrayList<>();
        fx.encoder.copyTextureToBuffer(tex, buf, 0, () -> order.add("a"), 0);
        fx.encoder.copyTextureToBuffer(tex, buf, 0, () -> order.add("b"), 1);
        fx.encoder.copyTextureToBuffer(tex, buf, 16, () -> order.add("c"), 0, 4, 2, 8, 4);
        fx.encoder.copyTextureToBuffer(tex, buf, 2, () -> order.add("d"), 0, 0, 0, 2, 2);       // misaligned: via scratch
        fx.encoder.copyTextureToBuffer(depth, buf, 0, null, 0);
        fx.encoder.copyTextureToBuffer(depthStencil, buf, 0, null, 0);
        Runnable never = () -> order.add("never");
        expectThrows(IllegalArgumentException.class, () -> fx.encoder.copyTextureToBuffer(tex, buf, 128, never, 0), "5-arg: buffer too small");
        expectThrows(IllegalArgumentException.class, () -> fx.encoder.copyTextureToBuffer(tex, buf, -4, never, 0), "5-arg: negative offset");
        expectThrows(IllegalArgumentException.class, () -> fx.encoder.copyTextureToBuffer(noSrc, buf, 0, never, 0), "5-arg: texture without COPY_SRC");
        expectThrows(IllegalArgumentException.class, () -> fx.encoder.copyTextureToBuffer(tex, noDst, 0, never, 0), "5-arg: buffer without COPY_DST");
        expectThrows(UnsupportedOperationException.class, () -> fx.encoder.copyTextureToBuffer(cube, buf, 0, never, 0), "5-arg: layered texture");
        expectThrows(IllegalArgumentException.class, () -> fx.encoder.copyTextureToBuffer(tex, buf, 0, never, 2), "5-arg: mip out of range");
        MetalTexture closedTex = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 4, 4, 1, 1);
        closedTex.close();
        expectThrows(IllegalStateException.class, () -> fx.encoder.copyTextureToBuffer(closedTex, buf, 0, never, 0), "5-arg: closed texture");
        List<I4StreamDecoder.Rec> recs = fx.main();
        eqo(List.of("COPY_TEXTURE_TO_BUFFER", "COPY_TEXTURE_TO_BUFFER", "COPY_TEXTURE_TO_BUFFER", "COPY_TEXTURE_TO_BUFFER", "COPY_BUFFER",
            "COPY_TEXTURE_TO_BUFFER", "COPY_TEXTURE_TO_BUFFER"), I4StreamDecoder.ops(recs), "records");
        I4StreamDecoder.Rec m0 = recs.get(0);
        eq(0, m0.i("level"), "level 0");
        eq(buf.handle(), m0.l("buffer"), "buffer");
        eq(tex.handle(), m0.l("texture"), "texture");
        eq(128, m0.i("bytesPerRow"), "bytesPerRow = w*bs");
        eq(2048, m0.i("bytesPerImage"), "bytesPerImage = w*h*bs");
        eq(32, m0.i("width"), "whole mip width");
        eq(16, m0.i("height"), "whole mip height");
        I4StreamDecoder.Rec m1 = recs.get(1);
        eq(1, m1.i("level"), "level 1");
        eq(16, m1.i("width"), "mip 1 width");
        eq(8, m1.i("height"), "mip 1 height");
        I4StreamDecoder.Rec sub = recs.get(2);
        eq(16, sub.l("bufferOffset"), "region offset");
        eq(4, sub.i("x"), "region x");
        eq(2, sub.i("y"), "region y");
        eq(8, sub.i("width"), "region w");
        eq(32, sub.i("bytesPerRow"), "region bytesPerRow");
        I4StreamDecoder.Rec viaScratch = recs.get(3);
        check(viaScratch.l("buffer") >= MetalTransientMemory.HANDLE_BASE, "misaligned readback goes to scratch");
        I4StreamDecoder.Rec fix = recs.get(4);
        eq(viaScratch.l("buffer"), fix.l("srcBuffer"), "scratch copied out");
        eq(buf.handle(), fix.l("dstBuffer"), "into the destination");
        eq(2, fix.l("dstOffset"), "at the requested offset");
        eq(16, fix.l("length"), "2x2 RGBA8");
        eq(128, recs.get(5).i("bytesPerRow"), "D32 4 bytes/texel");
        eq(0, recs.get(5).i("blitOptions"), "D32 no blit option");
        eq(32, recs.get(6).i("bytesPerRow"), "D32S8 depth plane 4 bytes/texel");
        eq(MetalFormats.BLIT_OPTION_DEPTH_FROM_DEPTH_STENCIL, recs.get(6).i("blitOptions"), "D32S8 DepthFromDepthStencil");
        eq(fx.pending(), buf.lastUseSeq(), "destination marked used");
        // Callbacks: only after the commit that carries the copy completed, in FIFO order, on submit's drain.
        check(order.isEmpty(), "callbacks not run at record time");
        fx.encoder.submit(); // seq 1, not completed
        check(order.isEmpty(), "callbacks not run before completion");
        fx.complete(1);
        fx.encoder.submit(); // seq 2: waits for 1 (done), drains
        eqo(List.of("a", "b", "c", "d"), order, "callbacks in FIFO order after completion");
    }

    static void testCopyTextureToTexture() {
        Fixture fx = new Fixture(false);
        MetalTexture a = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 64, 64, 1, 2);
        MetalTexture b = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 64, 64, 1, 2);
        MetalTexture hdr = texture(fx.device, GpuFormat.RGBA16_FLOAT, RT, 64, 64, 1, 2);
        MetalTexture r8 = texture(fx.device, GpuFormat.R8_UNORM, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, 64, 64, 1, 2);
        fx.encoder.copyTextureToTexture(a, b, 1, 3, 4, 5, 6, 7, 8);
        fx.encoder.copyTextureToTexture(a, hdr, 0, 0, 0, 0, 0, 64, 64);
        expectThrows(IllegalArgumentException.class, () -> fx.encoder.copyTextureToTexture(a, r8, 0, 0, 0, 0, 0, 4, 4), "format change into a non-render-target");
        expectThrows(IllegalArgumentException.class, () -> fx.encoder.copyTextureToTexture(a, b, 1, 30, 0, 0, 0, 4, 4), "dst rect outside mip 1");
        expectThrows(IllegalArgumentException.class, () -> fx.encoder.copyTextureToTexture(a, b, 2, 0, 0, 0, 0, 1, 1), "mip out of range");
        List<I4StreamDecoder.Rec> recs = fx.main();
        eqo(List.of("COPY_TEXTURE", "COPY_TEXTURE"), I4StreamDecoder.ops(recs), "records");
        I4StreamDecoder.Rec r = recs.get(0);
        eq(0, r.i("flags"), "same format: blit");
        eq(a.handle(), r.l("srcTexture"), "src");
        eq(b.handle(), r.l("dstTexture"), "dst");
        eq(1, r.i("srcLevel"), "src level");
        eq(1, r.i("dstLevel"), "dst level");
        eq(0, r.i("srcSlice"), "src slice");
        eq(0, r.i("dstSlice"), "dst slice");
        eq(5, r.i("srcX"), "srcX");
        eq(6, r.i("srcY"), "srcY");
        eq(3, r.i("dstX"), "dstX");
        eq(4, r.i("dstY"), "dstY");
        eq(7, r.i("width"), "w");
        eq(8, r.i("height"), "h");
        eq(StreamOps.COPY_FLAG_DRAW, recs.get(1).i("flags"), "different formats: draw");
    }

    static void testTimestampsAndFences() {
        Fixture fx = new Fixture(false);
        MetalTexture color = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 8, 8, 1, 1);
        MetalQueryPool pool = new MetalQueryPool(fx.device, newHandle(), 4);
        expectThrows(IllegalStateException.class, () -> fx.encoder.writeTimestamp(pool, 4), "index == size (frontend off-by-one)");
        expectThrows(IllegalStateException.class, () -> fx.encoder.writeTimestamp(pool, -1), "negative index");
        fx.encoder.writeTimestamp(pool, 3);
        RenderPassBackend pass = fx.encoder.createRenderPass(simplePass(fullView(color), forbiddenLabel()));
        pass.writeTimestamp(pool, 1);
        expectThrows(IllegalStateException.class, () -> pass.writeTimestamp(pool, 4), "pass: index == size");
        fx.encoder.submitRenderPass();
        fx.encoder.writeTimestamp(pool, 2);
        List<I4StreamDecoder.Rec> recs = fx.main();
        eqo(List.of("TIMESTAMP", "BEGIN_PASS", "TIMESTAMP", "END_PASS", "TIMESTAMP"), I4StreamDecoder.ops(recs), "records");
        eq(3, recs.get(0).i("index"), "index");
        eq(pool.handle(), recs.get(0).l("queryPool"), "pool handle");
        eq(1, recs.get(2).i("index"), "in-pass index");
        long[] written = peek(pool, "writtenSeq");
        if (written != null) {
            eq(fx.pending(), written[3], "markWritten(3, pendingSeq)");
            eq(fx.pending(), written[1], "markWritten(1, pendingSeq)");
            eq(0, written[0], "index 0 untouched");
        }
        GpuFence fence = fx.encoder.createFence();
        eq(fx.pending(), ((MetalFence)fence).seq(), "fence captures pendingSeq");
        fx.encoder.submit();
        eq(fx.pending(), ((MetalFence)fx.encoder.createFence()).seq(), "next fence captures the next seq");
        eq(2, fx.pending(), "pending advanced");
    }

    static void testSubmitSequence() {
        Fixture fx = new Fixture(false);
        MetalTexture tex = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 16, 16, 1, 1);
        // 1. Empty frame.
        fx.encoder.submit();
        eq(1, fx.submits.size(), "one native submit");
        Submit s = fx.lastSubmit();
        eq(MtlNative.SUBMIT_INFO_SIZEOF, s.infoStructSize, "info structSize");
        eq(MtlNative.SUBMIT_RESULT_SIZEOF, s.resultStructSize, "result structSize set by the caller");
        check(s.resultZeroed, "result zero-filled");
        eq(MtlNative.SUBMIT_FLAG_COMMIT | MtlNative.SUBMIT_FLAG_FRAME_END, s.flags, "COMMIT | FRAME_END");
        eq(1, s.signal, "first signal value is 1");
        eq(0, s.initPointer, "empty init stream: NULL");
        eq(0, s.mainPointer, "empty main stream: NULL");
        eq(0, s.label, "no label");
        eq(2, fx.pending(), "pendingSeq = 2");
        eqo(List.of(1L), fx.transientMemory().endFrameSeqs, "endFrame(1)");
        // 2. Init + main streams go out together and are reset afterwards.
        fx.encoder.recordTextureInit(tex);
        fx.encoder.clearColorTexture(tex, new Vector4f(1, 0, 0, 1));
        RenderPassBackend pass = fx.encoder.createRenderPass(simplePass(fullView(tex), forbiddenLabel()));
        fx.encoder.recordTextureInit(tex); // legal while a pass is open (init stream)
        expectThrows(IllegalStateException.class, fx.encoder::submit, "submit with a pass open");
        eq(1, fx.submits.size(), "pass-open submit made no native call");
        eq(2, fx.pending(), "pass-open submit consumed nothing");
        fx.encoder.submitRenderPass();
        fx.encoder.submit();
        s = fx.lastSubmit();
        eq(2, s.signal, "dense signal values");
        List<I4StreamDecoder.Rec> init = I4StreamDecoder.parse(s.init, true);
        eqo(List.of("INIT_TEXTURE", "INIT_TEXTURE"), I4StreamDecoder.ops(init), "init stream");
        eq(tex.handle(), init.get(0).l("texture"), "init texture handle");
        eq(StreamOps.INIT_TEXTURE_ZERO, init.get(0).i("mode"), "zero mode");
        eqo(List.of("CLEAR_TEXTURE", "BEGIN_PASS", "END_PASS"), I4StreamDecoder.ops(I4StreamDecoder.parse(s.main, false)), "main stream");
        check(fx.encoder.mainStream().isEmpty() && fx.encoder.initStream().isEmpty(), "streams reset after submit");
        long r = fx.encoder.mainStream().reserve(64);
        check(Arrays.equals(new byte[64], I4StreamDecoder.read(r, 64)), "reused stream memory is zero");
        fx.resetMain();
        // 3. A native error still advances the timeline; the error surfaces after the bookkeeping.
        fx.encoder.clearColorTexture(tex, new Vector4f());
        fx.nextStatus = MtlNative.E_BAD_STREAM;
        expectThrows(IllegalStateException.class, fx.encoder::submit, "BAD_STREAM -> IllegalStateException");
        eq(4, fx.pending(), "value consumed despite the error");
        eqo(List.of(1L, 2L, 3L), fx.transientMemory().endFrameSeqs, "endFrame ran despite the error");
        check(fx.encoder.mainStream().isEmpty(), "streams reset despite the error");
        fx.nextStatus = MtlNative.E_OUT_OF_MEMORY;
        expectThrows(com.mojang.renderpearl.api.device.GpuOutOfMemoryException.class, fx.encoder::submit, "OUT_OF_MEMORY mapping");
        eq(5, fx.pending(), "value consumed");
        // 4. The downcall itself failing (no native call happened) does not advance the timeline.
        fx.encoder.clearColorTexture(tex, new Vector4f());
        fx.throwNext = new IllegalStateException("linkage");
        expectThrows(IllegalStateException.class, fx.encoder::submit, "downcall failure propagates");
        eq(5, fx.pending(), "nothing consumed when the downcall did not happen");
        check(fx.encoder.mainStream().isEmpty(), "recorded work dropped");
        fx.encoder.submit();
        eq(5, fx.lastSubmit().signal, "next submit reuses the unconsumed value");
        // 5. Deferred actions drain on submit once complete.
        List<String> ran = new ArrayList<>();
        fx.timeline.defer(() -> ran.add("x"));
        fx.encoder.submit();
        eqo(List.of("x"), ran, "deferred action drained by submit");
    }

    static void testSubmitThrottleAndErrors() throws Exception {
        if (MetalFrameThrottle.ENABLED) {
            // -Dmcmetal.lateThrottle: submit() only arms the wait (CONTRACT.md 16.R5-A); the wait, its timeout and the error
            // order are tested by tools/LateThrottleTest.java. This test asserts the old path.
            System.out.println("  skipped: late throttle on (see tools/late-throttle-test.sh)");
            return;
        }
        Fixture fx = new Fixture(false);
        fx.autoComplete = false;
        fx.encoder.submit(); // frame 1: no previous frame, no wait
        eq(2, fx.pending(), "frame 1 committed");
        Thread completer = new Thread(() -> {
            try {
                Thread.sleep(60);
            } catch (InterruptedException e) {
                return;
            }
            fx.complete(1);
        });
        long start = System.nanoTime();
        completer.start();
        fx.encoder.submit(); // frame 2 must wait for frame 1
        long waitedMs = (System.nanoTime() - start) / 1_000_000L;
        completer.join();
        check(waitedMs >= 45, "frame 2 waited for frame 1 (" + waitedMs + " ms)");
        // Frame 3 waits for frame 2, which never completes: timeout.
        fx.encoder.frameWaitTimeoutNs = 30_000_000L;
        IllegalStateException timeout = expectThrows(IllegalStateException.class, fx.encoder::submit, "frame wait timeout");
        if (timeout != null) {
            check(timeout.getMessage().contains("5s timeout reached when waiting for the GPU"), "timeout message: " + timeout.getMessage());
        }
        eq(4, fx.pending(), "timed-out frame still committed");
        // Device loss reported through the status page.
        fx.complete(3);
        fx.setInt(MtlNative.STATUS_PAGE_ERROR_COUNT, 1);
        fx.setInt(MtlNative.STATUS_PAGE_DEVICE_LOST, 1);
        fx.nextStatus = MtlNative.E_DEVICE_LOST;
        GpuDeviceLossException lost = expectThrows(GpuDeviceLossException.class, fx.encoder::submit, "device loss");
        if (lost != null) {
            check(lost.getSuppressed().length == 1, "submit error attached as suppressed");
        }
    }

    static void testFlushForWait() {
        Fixture fx = new Fixture(false);
        MetalTexture tex = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 16, 16, 1, 1);
        fx.encoder.recordTextureInit(tex);
        fx.encoder.clearColorTexture(tex, new Vector4f());
        GpuFence fence = fx.encoder.createFence();
        long seq = ((MetalFence)fence).seq();
        fx.encoder.flushForWait(seq);
        eq(1, fx.submits.size(), "flush committed");
        Submit s = fx.lastSubmit();
        eq(MtlNative.SUBMIT_FLAG_COMMIT, s.flags, "COMMIT only (not FRAME_END)");
        eq(seq, s.signal, "signals the fence's seq");
        eqo(List.of("INIT_TEXTURE"), I4StreamDecoder.ops(I4StreamDecoder.parse(s.init, true)), "init stream flushed");
        eqo(List.of("CLEAR_TEXTURE"), I4StreamDecoder.ops(I4StreamDecoder.parse(s.main, false)), "main stream flushed");
        eq(seq + 1, fx.pending(), "pending advanced");
        check(fx.transientMemory().endFrameSeqs.isEmpty(), "a flush does not end the frame");
        fx.encoder.flushForWait(seq);
        eq(1, fx.submits.size(), "already committed: no-op");
        RenderPassBackend pass = fx.encoder.createRenderPass(simplePass(fullView(tex), forbiddenLabel()));
        long current = fx.pending();
        expectThrows(IllegalStateException.class, () -> fx.encoder.flushForWait(current), "flush with a pass open");
        fx.encoder.submitRenderPass();
        fx.encoder.submit();
        eq(MtlNative.SUBMIT_FLAG_COMMIT | MtlNative.SUBMIT_FLAG_FRAME_END, fx.lastSubmit().flags, "frame end");
        eq(seq + 1, fx.lastSubmit().signal, "frame signals the next value");
        eqo(List.of(seq + 1), fx.transientMemory().endFrameSeqs, "endFrame with the frame's last seq");
        fx.nextStatus = MtlNative.E_BAD_STREAM;
        fx.encoder.clearColorTexture(tex, new Vector4f());
        long now = fx.pending();
        expectThrows(IllegalStateException.class, () -> fx.encoder.flushForWait(now), "flush surfaces a native error");
        eq(now + 1, fx.pending(), "flush value consumed despite the error");
    }

    static void testSubmissionTicketCapture() {
        Fixture fx = new Fixture(false);
        MetalTexture tex = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 16, 16, 1, 1);
        fx.encoder.recordTextureInit(tex);
        fx.encoder.clearColorTexture(tex, new Vector4f(1, 0, 0, 1));
        byte[] init = I4StreamDecoder.copyOf(fx.encoder.initStream().address(), fx.encoder.initStream().size());
        byte[] main = I4StreamDecoder.copyOf(fx.encoder.mainStream().address(), fx.encoder.mainStream().size());
        int[] callbacks = {0};
        fx.timeline.deferUntil(0L, () -> callbacks[0]++); // already eligible; capture must not drain it
        fx.timeline.testWaitHook = seq -> { throw new AssertionError("capture must not wait"); };
        // Deliberately arm an uncommitted value: even a nonblocking guard would reject it.
        fx.device.frameThrottle().arm(fx.pending());
        long trips = fx.device.frameThrottle().tripCount();
        check(peek(fx.encoder, "submissionTicket") == null, "ticket allocation is lazy");
        MetalSubmissionTicket ticket = fx.encoder.captureSubmissionTicket();
        check(ticket.device() == fx.device, "ticket carries exact device identity");
        eq(fx.pending(), ticket.sequence(), "captures current pending sequence");
        eqo(MetalSubmissionTicket.State.PENDING, ticket.state(), "recording is not commitment");
        for (int i = 0; i < 100; i++) {
            check(ticket == fx.encoder.captureSubmissionTicket(), "same sequence shares one ticket");
        }
        eq(0, fx.submits.size(), "capture never submits");
        eq(0, callbacks[0], "capture never drains callbacks");
        eq(ticket.sequence(), fx.device.frameThrottle().pendingSeq(), "capture leaves throttle armed");
        eq(trips, fx.device.frameThrottle().tripCount(), "capture never trips throttle");
        check(fx.transientMemory().endFrameSeqs.isEmpty(), "capture never ends a frame");
        check(Arrays.equals(init, I4StreamDecoder.copyOf(fx.encoder.initStream().address(), fx.encoder.initStream().size())), "capture preserves init bytes");
        check(Arrays.equals(main, I4StreamDecoder.copyOf(fx.encoder.mainStream().address(), fx.encoder.mainStream().size())), "capture preserves main bytes");
        fx.device.frameThrottle().arm(0L);
        fx.encoder.submit();
        check(Arrays.equals(init, fx.lastSubmit().init) && Arrays.equals(main, fx.lastSubmit().main), "ticket leaves submitted stream bytes unchanged");
        MetalSubmissionTicket next = fx.encoder.captureSubmissionTicket();
        check(next != ticket, "next sequence gets a distinct ticket");
        eq(ticket.sequence() + 1, next.sequence(), "ticket sequence is immutable");
        RenderPassBackend pass = fx.encoder.createRenderPass(simplePass(fullView(tex), forbiddenLabel()));
        long size = fx.encoder.mainStream().size();
        expectThrows(IllegalStateException.class, fx.encoder::captureSubmissionTicket, "capture rejects open pass");
        eq(size, fx.encoder.mainStream().size(), "rejected capture preserves stream");
        eqo(MetalSubmissionTicket.State.PENDING, next.state(), "rejected capture leaves existing ticket pending");
        fx.encoder.submitRenderPass();
        check(next == fx.encoder.captureSubmissionTicket(), "closed pass reuses pending ticket");
        Fixture other = new Fixture(false);
        check(other.encoder.captureSubmissionTicket().device() != ticket.device(), "zero native handles still have distinct device identities");
        other.encoder.shutdown();
        fx.encoder.shutdown();
    }

    /** Real worker observation, both before native return and after the ticket's volatile publication. */
    static void testSubmissionTicketPublication() throws Exception {
        for (boolean completeBeforeReturn : new boolean[]{false, true}) {
            Fixture fx = new Fixture(false);
            fx.autoComplete = completeBeforeReturn;
            fx.timeline.testWaitHook = seq -> { throw new AssertionError("ticket commitment must not await its GPU sequence"); };
            MetalTexture tex = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 16, 16, 1, 1);
            fx.encoder.recordTextureInit(tex);
            fx.encoder.clearColorTexture(tex, new Vector4f());
            MetalSubmissionTicket ticket = fx.encoder.captureSubmissionTicket();
            CountDownLatch insideDowncall = new CountDownLatch(1);
            CountDownLatch inspected = new CountDownLatch(1);
            MetalSubmissionTicket.State[] observed = new MetalSubmissionTicket.State[2];
            long[] published = new long[4];
            Throwable[] workerError = new Throwable[1];
            Thread worker = new Thread(() -> {
                try {
                    awaitTicketLatch(insideDowncall);
                    observed[0] = ticket.state();
                    inspected.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    MetalSubmissionTicket.State state;
                    while ((state = ticket.state()) == MetalSubmissionTicket.State.PENDING && System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                    }
                    observed[1] = state;
                    // No other writer-to-worker synchronization follows the downcall. These ordinary
                    // Java fields must be visible through the terminal state's release/acquire edge.
                    published[0] = fx.timeline.pendingSeq();
                    published[1] = fx.encoder.initStream().size();
                    published[2] = fx.encoder.mainStream().size();
                    published[3] = fx.encoder.lastResultFlags();
                } catch (Throwable t) {
                    workerError[0] = t;
                } finally {
                    inspected.countDown();
                }
            }, "ticket-observer");
            worker.setDaemon(true);
            MetalCommandEncoder.SubmitFunction downcall = fx.encoder.submitFunction;
            fx.encoder.submitFunction = (dev, info, result) -> {
                int status = downcall.submit(dev, info, result); // result flags and native status page already set
                eq(ticket.sequence(), fx.pending(), "Java timeline still pending inside downcall");
                insideDowncall.countDown();
                awaitTicketLatch(inspected);
                return status;
            };
            worker.start();
            fx.encoder.submit(); // first frame has no prior-frame wait
            worker.join(6_000L);
            check(!worker.isAlive(), "worker observed terminal publication without a GPU wait");
            check(workerError[0] == null, "worker error: " + workerError[0]);
            eqo(MetalSubmissionTicket.State.PENDING, observed[0], "worker cannot see COMMITTED inside downcall");
            eqo(MetalSubmissionTicket.State.COMMITTED, observed[1], "worker sees native success after return");
            eq(ticket.sequence() + 1, published[0], "publication exposes advanced Java timeline");
            eq(0, published[1], "publication exposes reset init stream");
            eq(0, published[2], "publication exposes reset main stream");
            check((published[3] & MtlNative.SUBMIT_RESULT_FLAG_COMMITTED) != 0, "publication exposes result bookkeeping");
            eq(completeBeforeReturn ? ticket.sequence() : 0L, fx.timeline.completedSeq(), "commit state is independent of GPU completion");
            fx.encoder.shutdown();
            eqo(MetalSubmissionTicket.State.COMMITTED, ticket.state(), "committed ticket stays terminal after shutdown");
        }
    }

    private static void awaitTicketLatch(final CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out coordinating ticket observer");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    static void testSubmissionTicketFailures() {
        for (int status : new int[]{MtlNative.E_BAD_STREAM, MtlNative.E_OUT_OF_MEMORY, MtlNative.E_DEVICE_LOST, MtlNative.OK}) {
            for (boolean committedFlag : new boolean[]{false, true}) {
                if (status == MtlNative.OK && committedFlag) {
                    continue; // successes covered with both GPU completion timings above
                }
                Fixture fx = new Fixture(false);
                MetalSubmissionTicket ticket = fx.encoder.captureSubmissionTicket();
                fx.nextStatus = status;
                fx.nextCommitted = committedFlag;
                if (status == MtlNative.OK) {
                    fx.encoder.submit(); // consumed, but no evidence that an event signal was committed
                } else {
                    expectThrows(RuntimeException.class, fx.encoder::submit, "native status failure propagates");
                }
                eq(ticket.sequence() + 1, fx.pending(), "native failure consumes sequence");
                eq(ticket.sequence(), fx.timeline.completedSeq(), "native failure can already appear complete");
                eqo(MetalSubmissionTicket.State.FAILED, ticket.state(), "consumption/flag/completion alone cannot publish success");
                MetalSubmissionTicket next = fx.encoder.captureSubmissionTicket();
                check(next != ticket, "consumed failure permits a distinct next ticket");
                fx.encoder.submit();
                eqo(MetalSubmissionTicket.State.COMMITTED, next.state(), "later successful sequence can commit");
                eqo(MetalSubmissionTicket.State.FAILED, ticket.state(), "later success cannot revive failed ticket");
                fx.encoder.shutdown();
            }
        }
    }

    static void testSubmissionTicketDrop() {
        for (boolean linkageError : new boolean[]{false, true}) {
            Fixture fx = new Fixture(false);
            MetalTexture tex = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 16, 16, 1, 1);
            fx.encoder.recordTextureInit(tex);
            fx.encoder.clearColorTexture(tex, new Vector4f());
            MetalSubmissionTicket ticket = fx.encoder.captureSubmissionTicket();
            MetalCommandEncoder.SubmitFunction downcall = fx.encoder.submitFunction;
            if (linkageError) {
                fx.encoder.submitFunction = (dev, info, result) -> { throw new LinkageError("fake downcall linkage failure"); };
                expectThrows(LinkageError.class, fx.encoder::submit, "downcall Error propagates");
            } else {
                fx.throwNext = new IllegalStateException("fake downcall failure");
                expectThrows(IllegalStateException.class, fx.encoder::submit, "downcall exception propagates");
            }
            eq(ticket.sequence(), fx.pending(), "downcall failure does not consume sequence");
            check(fx.encoder.mainStream().isEmpty() && fx.encoder.initStream().isEmpty(), "exception drops both streams");
            eqo(MetalSubmissionTicket.State.FAILED, ticket.state(), "dropped work fails ticket");
            check(ticket == fx.encoder.captureSubmissionTicket(), "failed unconsumed sequence still shares its sole ticket");
            fx.encoder.submitFunction = downcall;
            fx.encoder.submit();
            eq(ticket.sequence(), fx.lastSubmit().signal, "retry consumes the same sequence");
            eqo(MetalSubmissionTicket.State.FAILED, ticket.state(), "successful retry cannot revive dependency on dropped work");
            check(ticket != fx.encoder.captureSubmissionTicket(), "next sequence finally gets a fresh ticket");
            fx.encoder.shutdown();
        }
    }

    static void testSubmissionTicketPostSubmitError() {
        Fixture fx = new Fixture(false);
        MetalTexture tex = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 16, 16, 1, 1);
        fx.encoder.recordTextureInit(tex);
        fx.encoder.clearColorTexture(tex, new Vector4f());
        MetalSubmissionTicket ticket = fx.encoder.captureSubmissionTicket();
        MetalCommandEncoder.SubmitFunction downcall = fx.encoder.submitFunction;
        fx.encoder.submitFunction = (dev, info, result) -> {
            int status = downcall.submit(dev, info, result);
            // Inject a Java bookkeeping error after an otherwise successful downcall: the encoder's
            // onCommitted must detect divergence. No production callback or test hook is needed.
            fx.timeline.onCommitted(ticket.sequence());
            return status;
        };
        expectThrows(IllegalStateException.class, fx.encoder::submit, "post-submit Java error propagates");
        eq(ticket.sequence(), fx.timeline.lastCommittedSeq(), "native/Java consumption alone is insufficient");
        eqo(MetalSubmissionTicket.State.FAILED, ticket.state(), "Java commit bookkeeping failure fails ticket");
        check(fx.encoder.mainStream().isEmpty() && fx.encoder.initStream().isEmpty(), "post-submit failure cannot replay streams");
        fx.encoder.submitFunction = downcall;
        MetalSubmissionTicket next = fx.encoder.captureSubmissionTicket();
        fx.encoder.submit();
        eqo(MetalSubmissionTicket.State.COMMITTED, next.state(), "next sequence recovers after bookkeeping failure");
        fx.encoder.shutdown();
    }

    static void testSubmissionTicketFlushAndShutdown() {
        Fixture fx = new Fixture(false);
        int[] callbacks = {0};
        fx.timeline.defer(() -> callbacks[0]++);
        fx.timeline.testWaitHook = seq -> { throw new AssertionError("flush ticket must not wait"); };
        MetalSubmissionTicket ticket = fx.encoder.captureSubmissionTicket();
        fx.encoder.flushForWait(ticket.sequence());
        eqo(MetalSubmissionTicket.State.COMMITTED, ticket.state(), "flush publishes ticket");
        eq(MtlNative.SUBMIT_FLAG_COMMIT, fx.lastSubmit().flags, "flush remains COMMIT only");
        eq(0, callbacks[0], "flush publication does not run ready callbacks");
        check(fx.transientMemory().endFrameSeqs.isEmpty(), "flush publication does not end frame");
        fx.encoder.flushForWait(ticket.sequence());
        eq(1, fx.submits.size(), "flushing committed ticket is a no-op");
        MetalSubmissionTicket failed = fx.encoder.captureSubmissionTicket();
        fx.nextStatus = MtlNative.E_BAD_STREAM;
        expectThrows(IllegalStateException.class, () -> fx.encoder.flushForWait(failed.sequence()), "flush native failure propagates");
        eqo(MetalSubmissionTicket.State.FAILED, failed.state(), "flush error fails ticket despite consumed sequence");
        MetalSubmissionTicket pending = fx.encoder.captureSubmissionTicket();
        MetalTexture tex = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 16, 16, 1, 1);
        fx.encoder.createRenderPass(simplePass(fullView(tex), forbiddenLabel()));
        fx.encoder.shutdown();
        fx.encoder.shutdown();
        eqo(MetalSubmissionTicket.State.FAILED, pending.state(), "shutdown drops pending ticket even with open pass");
        eqo(MetalSubmissionTicket.State.COMMITTED, ticket.state(), "shutdown preserves earlier terminal success");
        eq(0, callbacks[0], "shutdown publication does not drain callbacks");
        eq(2, fx.submits.size(), "shutdown never submits");
        expectThrows(IllegalStateException.class, fx.encoder::captureSubmissionTicket, "capture rejects shutdown");
    }

    static void testSubmissionTicketAllocationFree() {
        Fixture fx = new Fixture(false);
        // Omit the fixture's allocating Submit/byte[] history while measuring the real commit path.
        fx.encoder.submitFunction = (dev, info, result) -> {
            long seq = MemoryUtil.memGetLong(info + MtlNative.SUBMIT_INFO_SIGNAL_VALUE);
            STATUS.setRelease(fx.page, MtlNative.STATUS_PAGE_SUBMITTED_VALUE, seq);
            fx.complete(seq);
            MemoryUtil.memPutInt(result + MtlNative.SUBMIT_RESULT_FLAGS, MtlNative.SUBMIT_RESULT_FLAG_COMMITTED);
            return MtlNative.OK;
        };
        for (int i = 0; i < 10_000; i++) {
            fx.encoder.flushForWait(fx.pending());
        }
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        // The first instrumented batch can trigger VM/JIT setup at this distinct call site (1,200
        // bytes on the initial run). Use fixed warmup batches at the measured site, then require
        // BOTH final batches to be exactly zero; do not pick the minimum or relax per-commit limits.
        long[] samples = new long[5];
        for (int batch = 0; batch < samples.length; batch++) {
            long start = threads.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < 1_000; i++) {
                fx.encoder.flushForWait(fx.pending());
            }
            samples[batch] = threads.getCurrentThreadAllocatedBytes() - start;
        }
        System.out.println("    uncaptured commit allocation batches: " + Arrays.toString(samples));
        eq(0, samples[3], "warmed uncaptured commits allocate no heap bytes (batch 4)");
        eq(0, samples[4], "warmed uncaptured commits allocate no heap bytes (batch 5)");
        check(peek(fx.encoder, "submissionTicket") == null, "commits never create unsolicited tickets");
        MetalSubmissionTicket ticket = fx.encoder.captureSubmissionTicket();
        for (int i = 0; i < 10_000; i++) {
            fx.encoder.captureSubmissionTicket();
        }
        long before = threads.getCurrentThreadAllocatedBytes();
        boolean same = true;
        for (int i = 0; i < 1_000; i++) {
            same &= ticket == fx.encoder.captureSubmissionTicket();
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        check(same, "all repeated captures reuse ticket");
        eq(0, allocated, "repeated capture allocates no heap bytes");
        fx.encoder.shutdown();
    }

    /** Fake SDL + libmcmetal surface calls. */
    static final class FakeSurfaceNatives implements MetalSurface.Natives {
        final List<String> calls = new ArrayList<>();
        long view = 0x5D10_0000_0000L;
        long layer = 0xCA00_0000_0000L;
        long surface = 0x5F00_0000_0000L;
        long windowFlags;
        int createStatus = MtlNative.OK;
        int configureStatus = MtlNative.OK;
        int presentStatus = MtlNative.OK;
        boolean presentThrows;
        int[] lastConfig;
        Runnable onDestroy = () -> {};

        @Override
        public long createView(final long window) {
            this.calls.add("createView");
            return this.view;
        }

        @Override
        public long getLayer(final long view) {
            this.calls.add("getLayer");
            return this.layer;
        }

        @Override
        public void destroyView(final long view) {
            this.calls.add("destroyView");
        }

        @Override
        public String lastSdlError() {
            return "fake SDL error";
        }

        @Override
        public long windowFlags(final long window) {
            return this.windowFlags;
        }

        @Override
        public int surfaceCreate(final long device, final long layer, final long outSurface) {
            this.calls.add("surfaceCreate");
            if (this.createStatus == MtlNative.OK) {
                MemoryUtil.memPutLong(outSurface, this.surface);
            }
            return this.createStatus;
        }

        @Override
        public int surfaceConfigure(final long surface, final long config) {
            this.calls.add("configure");
            this.lastConfig = new int[]{
                MemoryUtil.memGetInt(config + MtlNative.SURFACE_CONFIG_STRUCT_SIZE), MemoryUtil.memGetInt(config + MtlNative.SURFACE_CONFIG_WIDTH),
                MemoryUtil.memGetInt(config + MtlNative.SURFACE_CONFIG_HEIGHT), MemoryUtil.memGetInt(config + MtlNative.SURFACE_CONFIG_FLAGS),
                MemoryUtil.memGetInt(config + MtlNative.SURFACE_CONFIG_MAXIMUM_DRAWABLE_COUNT), MemoryUtil.memGetInt(config + MtlNative.SURFACE_CONFIG_RESERVED)
            };
            return this.configureStatus;
        }

        @Override
        public int surfacePresent(final long surface) {
            this.calls.add("present");
            if (this.presentThrows) {
                throw new IllegalStateException("boom");
            }
            return this.presentStatus;
        }

        @Override
        public int surfaceDestroy(final long surface) {
            this.calls.add("surfaceDestroy");
            this.onDestroy.run();
            return MtlNative.OK;
        }
    }

    static void testSurface() throws Exception {
        Fixture fx = new Fixture(false);
        boolean[] iconified = {false};
        // Construction failures clean up.
        FakeSurfaceNatives noView = new FakeSurfaceNatives();
        noView.view = 0L;
        IllegalStateException e = expectThrows(IllegalStateException.class, () -> MetalSurface.forTesting(fx.device, 42L, () -> false, noView), "no view");
        if (e != null) {
            check(e.getMessage().contains("fake SDL error"), "SDL error in message");
        }
        eqo(List.of("createView"), noView.calls, "nothing else after a failed view");
        FakeSurfaceNatives badCreate = new FakeSurfaceNatives();
        badCreate.createStatus = MtlNative.E_SURFACE;
        expectThrows(IllegalStateException.class, () -> MetalSurface.forTesting(fx.device, 42L, () -> false, badCreate), "surface create fails");
        eqo(List.of("createView", "getLayer", "surfaceCreate", "destroyView"), badCreate.calls, "view destroyed after a failed create");
        // Normal surface.
        FakeSurfaceNatives n = new FakeSurfaceNatives();
        MetalSurface surface = MetalSurface.forTesting(fx.device, 42L, () -> iconified[0], n);
        eq(n.surface, surface.handle(), "surface handle");
        check(surface.supportedPresentModes().size() == 2 && surface.supportedPresentModes().contains(GpuSurface.PresentMode.FIFO)
            && surface.supportedPresentModes().contains(GpuSurface.PresentMode.IMMEDIATE), "FIFO + IMMEDIATE");
        expectThrows(SurfaceException.class, () -> surface.configure(new GpuSurface.Configuration(0, 10, GpuSurface.PresentMode.FIFO)), "width 0");
        expectThrows(SurfaceException.class, () -> surface.configure(new GpuSurface.Configuration(16385, 10, GpuSurface.PresentMode.FIFO)), "width 16385");
        check(!n.calls.contains("configure"), "invalid sizes never reach native");
        surface.configure(new GpuSurface.Configuration(1708, 960, GpuSurface.PresentMode.FIFO));
        check(Arrays.equals(new int[]{24, 1708, 960, MtlNative.SURFACE_FLAG_DISPLAY_SYNC, 3, 0}, n.lastConfig), "FIFO config " + Arrays.toString(n.lastConfig));
        surface.configure(new GpuSurface.Configuration(16384, 16384, GpuSurface.PresentMode.IMMEDIATE));
        // 4c123c2: IMMEDIATE uses MetalSurface.IMMEDIATE_DRAWABLE_COUNT (2) drawables, FIFO keeps 3.
        check(Arrays.equals(new int[]{24, 16384, 16384, 0, MetalSurface.IMMEDIATE_DRAWABLE_COUNT, 0}, n.lastConfig), "IMMEDIATE config " + Arrays.toString(n.lastConfig));
        check(!surface.isSuboptimal(), "configured surface is optimal");
        n.configureStatus = MtlNative.E_SURFACE;
        expectThrows(SurfaceException.class, () -> surface.configure(new GpuSurface.Configuration(800, 600, GpuSurface.PresentMode.FIFO)), "native configure failure");
        n.configureStatus = MtlNative.OK;
        surface.configure(new GpuSurface.Configuration(800, 600, GpuSurface.PresentMode.FIFO));
        // Iconified / occluded.
        iconified[0] = true;
        SurfaceException min = expectThrows(SurfaceException.class, surface::acquireNextTexture, "iconified");
        if (min != null) {
            eqo("Cannot acquire minimized window", min.getMessage(), "iconified message");
        }
        iconified[0] = false;
        MetalTexture main = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 800, 600, 1, 2);
        n.windowFlags = org.lwjgl.sdl.SDLVideo.SDL_WINDOW_OCCLUDED;
        surface.acquireNextTexture(); // occluded: no exception
        check(surface.isFrameDropped(), "occluded frame dropped");
        surface.blitFromTexture(fx.encoder, fullView(main));
        eq(0, fx.encoder.mainStream().size(), "dropped frame records no PRESENT");
        fx.encoder.submit();
        surface.present();
        check(!surface.isFrameDropped(), "present clears the drop flag");
        n.windowFlags = 0;
        // Normal frame: PRESENT of the view's parent texture at its base mip.
        surface.acquireNextTexture();
        surface.blitFromTexture(fx.encoder, partialView(main, 1, 1));
        List<I4StreamDecoder.Rec> recs = fx.main();
        eqo(List.of("PRESENT"), I4StreamDecoder.ops(recs), "PRESENT recorded");
        eq(n.surface, recs.get(0).l("surface"), "surface handle");
        eq(main.handle(), recs.get(0).l("texture"), "parent texture");
        eq(1, recs.get(0).i("level"), "view base mip");
        eq(StreamOps.PRESENT_FLAG_NONE, recs.get(0).i("flags"), "flags 0");
        fx.nextResultFlags = MtlNative.SUBMIT_RESULT_FLAG_PRESENT_ENCODED;
        fx.nextDrawableWidth = 800;
        fx.nextDrawableHeight = 600;
        fx.encoder.submit();
        surface.present();
        check(!surface.isSuboptimal(), "presented frame keeps the surface optimal");
        // nil drawable: suboptimal + eaten exception rethrown once by the next acquire.
        surface.acquireNextTexture();
        surface.blitFromTexture(fx.encoder, fullView(main));
        fx.nextResultFlags = MtlNative.SUBMIT_RESULT_FLAG_DRAWABLE_NIL;
        fx.encoder.submit();
        surface.present();
        check(surface.isSuboptimal(), "nil drawable -> suboptimal");
        SurfaceException eaten = expectThrows(SurfaceException.class, surface::acquireNextTexture, "eaten exception rethrown");
        if (eaten != null) {
            check(eaten.getCause() != null && eaten.getCause().getMessage().contains("nextDrawable returned nil"), "eaten cause");
        }
        surface.acquireNextTexture(); // thrown once only
        check(surface.isSuboptimal(), "still suboptimal until configure");
        surface.configure(new GpuSurface.Configuration(800, 600, GpuSurface.PresentMode.FIFO));
        check(!surface.isSuboptimal(), "configure clears suboptimal");
        // Resized drawable: suboptimal, no exception.
        surface.acquireNextTexture();
        surface.blitFromTexture(fx.encoder, fullView(main));
        fx.nextResultFlags = MtlNative.SUBMIT_RESULT_FLAG_DRAWABLE_RESIZED | MtlNative.SUBMIT_RESULT_FLAG_PRESENT_ENCODED;
        fx.encoder.submit();
        surface.present();
        check(surface.isSuboptimal(), "resized drawable -> suboptimal");
        surface.acquireNextTexture();
        // The result goes to the surface after the commit that carried its PRESENT (here a fence flush).
        surface.configure(new GpuSurface.Configuration(800, 600, GpuSurface.PresentMode.FIFO));
        surface.acquireNextTexture();
        surface.blitFromTexture(fx.encoder, fullView(main));
        fx.nextResultFlags = MtlNative.SUBMIT_RESULT_FLAG_DRAWABLE_NIL;
        fx.encoder.flushForWait(fx.pending());
        check(surface.isSuboptimal(), "flush commit delivered the PRESENT result");
        surface.configure(new GpuSurface.Configuration(800, 600, GpuSurface.PresentMode.FIFO));
        fx.nextResultFlags = MtlNative.SUBMIT_RESULT_FLAG_DRAWABLE_NIL;
        fx.encoder.submit(); // no PRESENT in this commit
        check(!surface.isSuboptimal(), "a commit without PRESENT does not touch the surface");
        surface.present();
        // present() never throws.
        n.presentThrows = true;
        surface.present();
        n.presentThrows = false;
        n.presentStatus = MtlNative.E_SURFACE;
        surface.present();
        check(true, "present swallowed failures");
        surface.close();
    }

    /** Presentation counters (CONTRACT.md 16.4): one per frame whose PRESENT was submitted, split by the result flags. */
    static void testSurfacePresentStats() throws Exception {
        Fixture fx = new Fixture(false);
        FakeSurfaceNatives n = new FakeSurfaceNatives();
        MetalSurface surface = MetalSurface.forTesting(fx.device, 42L, () -> false, n);
        surface.configure(new GpuSurface.Configuration(64, 64, GpuSurface.PresentMode.IMMEDIATE));
        MetalTexture main = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 64, 64, 1, 1);
        MetalSurface.resetPresentStats();
        int[] results = {
            MtlNative.SUBMIT_RESULT_FLAG_PRESENT_ENCODED,
            0,   // mailbox: rendered, not presented
            0,
            MtlNative.SUBMIT_RESULT_FLAG_PRESENT_ENCODED | MtlNative.SUBMIT_RESULT_FLAG_DRAWABLE_RESIZED,
        };
        for (int i = 0; i < results.length; i++) {
            surface.acquireNextTexture();
            surface.blitFromTexture(fx.encoder, fullView(main));
            fx.nextResultFlags = results[i];
            fx.encoder.submit();
            surface.present();
            if (i == 2) {
                check(!surface.isSuboptimal(), "a frame that was not presented leaves the surface optimal");
            }
        }
        long[] st = MetalSurface.presentStats();
        check(st[MetalSurface.STAT_FRAMES] == 4 && st[MetalSurface.STAT_PRESENTED] == 2 && st[MetalSurface.STAT_NOT_PRESENTED] == 2
            && st[MetalSurface.STAT_NIL] == 0, "present stats " + Arrays.toString(st));
        st[0] = 99;
        check(MetalSurface.presentStats()[0] == 4, "presentStats returns a copy");
        surface.configure(new GpuSurface.Configuration(64, 64, GpuSurface.PresentMode.IMMEDIATE));
        // nil drawable (blocking path) and an occluded window (no PRESENT recorded, no submit result for the surface).
        surface.acquireNextTexture();
        surface.blitFromTexture(fx.encoder, fullView(main));
        fx.nextResultFlags = MtlNative.SUBMIT_RESULT_FLAG_DRAWABLE_NIL;
        fx.encoder.submit();
        surface.present();
        expectThrows(SurfaceException.class, surface::acquireNextTexture, "eaten nil-drawable exception");
        n.windowFlags = org.lwjgl.sdl.SDLVideo.SDL_WINDOW_OCCLUDED;
        surface.acquireNextTexture();
        surface.blitFromTexture(fx.encoder, fullView(main));
        fx.nextResultFlags = MtlNative.SUBMIT_RESULT_FLAG_PRESENT_ENCODED;
        fx.encoder.submit();
        surface.present();
        n.windowFlags = 0;
        st = MetalSurface.presentStats();
        check(st[MetalSurface.STAT_FRAMES] == 5 && st[MetalSurface.STAT_NIL] == 1 && st[MetalSurface.STAT_OCCLUDED] == 1
            && st[MetalSurface.STAT_PRESENTED] == 2, "nil + occluded stats " + Arrays.toString(st));
        MetalSurface.resetPresentStats();
        check(Arrays.stream(MetalSurface.presentStats()).allMatch(v -> v == 0L), "reset");
        surface.close();
    }

    static void testSurfaceClose() throws Exception {
        Fixture fx = new Fixture(false);
        FakeSurfaceNatives n = new FakeSurfaceNatives();
        MetalSurface surface = MetalSurface.forTesting(fx.device, 42L, () -> false, n);
        surface.configure(new GpuSurface.Configuration(64, 64, GpuSurface.PresentMode.FIFO));
        MetalTexture main = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 64, 64, 1, 1);
        // A committed frame still running when close() starts: close waits for it before destroying.
        fx.autoComplete = false;
        surface.acquireNextTexture();
        surface.blitFromTexture(fx.encoder, fullView(main));
        fx.encoder.submit();
        surface.present();
        long last = fx.timeline.lastCommittedSeq();
        boolean[] completeAtDestroy = {false};
        n.onDestroy = () -> completeAtDestroy[0] = fx.timeline.completedSeq() >= last;
        // A PRESENT recorded but never committed: neutralized so the next commit never sees the dead surface.
        surface.acquireNextTexture();
        surface.blitFromTexture(fx.encoder, fullView(main));
        fx.encoder.clearColorTexture(main, new Vector4f());
        Thread completer = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return;
            }
            fx.complete(last);
        });
        completer.start();
        n.calls.clear();
        surface.close();
        completer.join();
        check(completeAtDestroy[0], "surface destroyed only after the last committed frame completed");
        eqo(List.of("surfaceDestroy", "destroyView"), n.calls, "close order: native surface, then SDL view");
        surface.close();
        eqo(List.of("surfaceDestroy", "destroyView"), n.calls, "close is idempotent");
        List<I4StreamDecoder.Rec> recs = fx.main();
        eqo(List.of("NOP", "CLEAR_TEXTURE"), I4StreamDecoder.ops(recs), "pending PRESENT turned into a NOP");
        eq(StreamOps.SIZE_PRESENT, recs.get(0).size, "NOP keeps the record size");
        fx.autoComplete = true;
        fx.encoder.submit();
        surface.present(); // after close: no native call, no exception
        check(!n.calls.contains("present"), "no present after close");
    }

    static void testFrontendIntegration() {
        Fixture fx = new Fixture(false);
        FrontendCommandEncoder frontend = new FrontendCommandEncoder(null, fx.device, fx.encoder);
        MetalTexture color = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 64, 64, 1, 1);
        MetalTexture depth = texture(fx.device, GpuFormat.D32_FLOAT, RT, 64, 64, 1, 1);
        MetalRenderPipeline p1 = pipeline(fx.device, "p1", 0, 0);
        MetalRenderPipeline p2 = pipeline(fx.device, "p2", 0, 0);
        List<BindGroupLayout.UniformDescription> uniforms = List.of(
            new BindGroupLayout.UniformDescription("Projection", UniformType.UNIFORM_BUFFER),
            new BindGroupLayout.UniformDescription("Globals", UniformType.UNIFORM_BUFFER),
            new BindGroupLayout.UniformDescription("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER),
            new BindGroupLayout.UniformDescription("CloudFaces", UniformType.TEXEL_BUFFER, GpuFormat.R8_SINT),
            new BindGroupLayout.UniformDescription("Unused", UniformType.UNIFORM_BUFFER));
        Object2IntOpenHashMap<String> indices = new Object2IntOpenHashMap<>();
        for (int k = 0; k < uniforms.size(); k++) {
            indices.put(uniforms.get(k).name(), k);
        }
        List<ColorTargetState> targets = List.of(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 15));
        FrontendRenderPipeline f1 = new FrontendRenderPipeline("p1", p1, List.of(), indices, uniforms, targets, true, 0);
        FrontendRenderPipeline f2 = new FrontendRenderPipeline("p2", p2, List.of(), indices, uniforms, targets, true, 0);
        TestBuffer projection = TestBuffer.create(fx.device, GpuBuffer.USAGE_UNIFORM, 64, 0);
        TestBuffer vb = TestBuffer.create(fx.device, GpuBuffer.USAGE_VERTEX, 1024, 0);
        TestBuffer ib = TestBuffer.create(fx.device, GpuBuffer.USAGE_INDEX, 1024, 0);
        MetalSampler sampler = sampler();
        RenderPass pass = frontend.createRenderPass(() -> "frontend", fullView(color), Optional.<Vector4fc>of(new Vector4f(0, 0, 0, 1)), fullView(depth), OptionalDouble.of(0.0));
        pass.setUniform("Projection", projection);             // stored by the frontend (no pipeline yet)
        pass.setPipeline(f1);                                  // SET_PIPELINE + replay Projection
        pass.setUniform("Sampler0", fullView(color), sampler);
        pass.setVertexBuffer(0, vb.slice());
        pass.setIndexBuffer(ib, IndexType.SHORT);
        pass.drawIndexed(6, 1, 0, 0, 0);
        long before = fx.encoder.mainStream().size();
        pass.setPipeline(f1);                                  // same pipeline: replay of 2 uniforms, all dropped
        eq(before, fx.encoder.mainStream().size(), "re-binding the same pipeline records nothing");
        pass.setPipeline(f2);                                  // SET_PIPELINE + 2 uniforms recorded again
        pass.drawIndexed(6, 1, 6, 0, 0);
        pass.close();
        frontend.submit();
        eq(1, fx.submits.size(), "one submit");
        List<I4StreamDecoder.Rec> recs = I4StreamDecoder.parse(fx.lastSubmit().main, false);
        List<String> ops = I4StreamDecoder.ops(recs);
        eqo(List.of("BEGIN_PASS", "SET_PIPELINE", "SET_BUFFER", "SET_TEXTURE", "SET_VERTEX_BUFFER", "SET_INDEX_BUFFER", "DRAW_INDEXED", "SET_PIPELINE",
            ops.get(8), ops.get(9), "DRAW_INDEXED", "END_PASS"), ops, "frontend-driven stream");
        check(ops.subList(8, 10).containsAll(List.of("SET_BUFFER", "SET_TEXTURE")), "pipeline switch replays both uniforms");
        eq(StreamOps.PASS_FLAG_DEPTH_CLEAR, recs.get(0).i("flags"), "depth clear via the frontend");
        eq(1, recs.get(0).i("colorClearMask"), "color clear via the frontend");
        // The frontend forwards calls after close(); the backend rejects them.
        expectThrows(IllegalStateException.class, () -> pass.setUniform("Projection", projection), "use after close via the frontend");
    }

    static void testStrictBindings() {
        Fixture fx = new Fixture(false);
        fx.encoder.strictBindings = true;
        MetalTexture color = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 16, 16, 1, 1);
        MetalRenderPipeline p = pipeline(fx.device, "strict", 0, 0);
        TestBuffer ubo = TestBuffer.create(fx.device, GpuBuffer.USAGE_UNIFORM, 256, 0);
        TestBuffer texel = TestBuffer.create(fx.device, GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, 64, 0);
        TestBuffer vb = TestBuffer.create(fx.device, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX, 256, 0);
        RenderPassBackend pass = fx.encoder.createRenderPass(simplePass(fullView(color), forbiddenLabel()));
        pass.setPipeline(p);
        IllegalStateException e = expectThrows(IllegalStateException.class, () -> pass.draw(3, 1, 0, 0), "missing uniform");
        if (e != null) {
            check(e.getMessage().startsWith("Missing uniform Projection"), "names the first missing uniform: " + e.getMessage());
        }
        pass.setUniform(0, ubo.slice());
        pass.setUniform(1, ubo.slice());
        pass.setUniform(2, new TextureViewAndSampler(fullView(color), sampler()));
        pass.setUniform(3, texel.slice());
        e = expectThrows(IllegalStateException.class, () -> pass.draw(3, 1, 0, 0), "missing vertex buffer");
        if (e != null) {
            check(e.getMessage().startsWith("Missing vertex buffer at slot 0"), e.getMessage());
        }
        pass.setVertexBuffer(0, vb.slice());
        pass.draw(3, 1, 0, 0);
        expectThrows(IllegalStateException.class, () -> pass.drawIndexed(3, 1, 0, 0, 0), "missing index buffer");
        pass.setIndexBuffer(vb, IndexType.SHORT);
        pass.drawIndexed(3, 1, 0, 0, 0);
        pass.setUniform(1, null);
        expectThrows(IllegalStateException.class, () -> pass.draw(3, 1, 0, 0), "cleared uniform is missing again");
        fx.encoder.submitRenderPass();
        fx.main();
    }

    static void testHotPathAllocationFree() {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        Fixture fx = new Fixture(false);
        MetalTexture color = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 64, 64, 1, 1);
        MetalRenderPipeline p1 = pipeline(fx.device, "a", 16, V);
        MetalRenderPipeline p2 = pipeline(fx.device, "b", 16, V);
        TestBuffer ubo = TestBuffer.create(fx.device, GpuBuffer.USAGE_UNIFORM, 4096, 0);
        TestBuffer texel = TestBuffer.create(fx.device, GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, 64, 0);
        TestBuffer vb = TestBuffer.create(fx.device, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_INDIRECT_PARAMETERS, 4096, 0);
        GpuBufferSlice[] transforms = new GpuBufferSlice[64];
        for (int k = 0; k < transforms.length; k++) {
            transforms[k] = new GpuBufferSlice(ubo, 16L * k, 16);
        }
        GpuBufferSlice globals = ubo.slice();
        GpuBufferSlice texelSlice = texel.slice();
        GpuBufferSlice vertices = vb.slice();
        GpuBufferSlice indirect = new GpuBufferSlice(vb, 0, 200);
        TextureViewAndSampler tex = new TextureViewAndSampler(fullView(color), sampler());
        ByteBuffer pc = MemoryUtil.memCalloc(16);
        RenderPassBackend pass = fx.encoder.createRenderPass(simplePass(fullView(color), forbiddenLabel()));
        Runnable frame = () -> {
            for (int k = 0; k < 64; k++) {
                pass.setPipeline((k & 8) == 0 ? p1 : p2);
                pass.setUniform(0, transforms[k]);
                pass.setUniform(1, globals);
                pass.setUniform(2, tex);
                pass.setUniform(3, texelSlice);
                pass.setVertexBuffer(0, vertices);
                pass.setIndexBuffer(vb, IndexType.SHORT);
                pass.enableScissor(0, 0, 32, 32);
                pass.pushConstants(pc);
                pass.drawIndexed(6, 1, k * 6, k, 0);
                pass.draw(4, 1, 0, 0);
                pass.drawIndexedIndirect(indirect, 10);
                pass.disableScissor();
                pass.setUniform(0, null);
            }
        };
        for (int warm = 0; warm < 300; warm++) {
            frame.run();
        }
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int rep = 0; rep < 200; rep++) {
            frame.run();
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        eq(0, allocated, "heap bytes allocated by 200 x 64 draws with bindings");
        long records = fx.encoder.mainStream().size();
        System.out.println("    " + (500 * 64) + " draws recorded into " + records / 1024 + " KiB of stream, 0 heap bytes in the measured phase");
        fx.encoder.submitRenderPass();
    }

    static void testShutdown() {
        Fixture fx = new Fixture(false);
        MetalTexture color = texture(fx.device, GpuFormat.RGBA8_UNORM, RT, 16, 16, 1, 1);
        fx.encoder.clearColorTexture(color, new Vector4f());
        RenderPassBackend pass = fx.encoder.createRenderPass(simplePass(fullView(color), forbiddenLabel()));
        fx.encoder.shutdown();
        check(!fx.encoder.isInRenderPass(), "no pass after shutdown");
        expectThrows(IllegalStateException.class, () -> pass.draw(3, 1, 0, 0), "pass closed by shutdown");
        expectThrows(IllegalStateException.class, fx.encoder::submit, "submit after shutdown");
        expectThrows(IllegalStateException.class, () -> fx.encoder.recordTextureInit(color), "record after shutdown");
        expectThrows(IllegalStateException.class, () -> fx.encoder.clearColorTexture(color, new Vector4f()), "clear after shutdown");
        fx.encoder.shutdown();
        eq(0, fx.submits.size(), "shutdown never submits");
    }
}
