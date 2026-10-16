package com.mojang.renderpearl.backend.metal;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.RefUberGpuBuffer;
import com.mojang.blaze3d.vertex.StagingBuffer;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.minecraft.TracingExecutor;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.TravelHitchToggles;
import net.minecraft.core.SectionPos;
import net.minecraft.client.renderer.texture.EntityTexturePrefetch;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceMetadata;
import org.lwjgl.system.MemoryUtil;

/**
 * TASK E headless tests (docs/metal/CONTRACT.md 16.R5-E): the Metal heap reserve (background creation, poll, class
 * limit, close; plus a real-device run that measures a 128 MiB heap and checks GPU residency), UberGpuBuffer pregrow (allocation placement, upload order, callbacks and return values identical to
 * the vanilla path while heaps are created from spares), the upload budget (flush = vanilla; bounded delay, vertex/index
 * pairing, never-delayed re-sorts and render-thread stagings, released meshes dropped), and the entity texture prefetch
 * (same pixels and metadata as the synchronous load, generations, memory bound). No window, frame or game. Run through
 * tools/travel-hitch-test.sh (needs build/client.jar; runs with -Dmc.terrain.pregrow=true).
 */
public final class TravelHitchTest {
    private static int checks;
    private static int failures;
    private static final ExecutorService STAGER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "test chunk worker");
        thread.setDaemon(true);
        return thread;
    });

    public static void main(final String[] args) throws Exception {
        RenderSystem.initRenderThread();
        check(TravelHitchToggles.PREGROW && MetalHeapReserve.ENABLED, "run with -Dmc.terrain.pregrow=true");
        heapReserve();
        nativeHeapReserve();
        for (int seed = 1; seed <= 6; seed++) {
            pregrowMatchesVanilla(seed);
        }
        pregrowSpareLifecycle();
        for (int seed = 1; seed <= 4; seed++) {
            budgetFlushMatchesVanilla(seed);
            budgetBoundsAndPairing(seed, 2);
        }
        budgetBoundsAndPairing(9, 0);
        budgetReleasedAndUrgent();
        budgetSectionOrdering();
        // Review finding (E, minor): the same uber-buffer rigs on the staging Metal really uses (writeToBufferIsSlow +
        // persistent mapping -> StagingBuffer.PersistentlyMapped: a MappableRingBuffer, copyToBuffer, ring rotation only
        // when no handle is open), with the uploaded bytes checked per key at its callback.
        persistentStaging = true;
        FakeGpu ring = new FakeGpu(fakeInfo());
        java.lang.reflect.Field deviceField = RenderSystem.class.getDeclaredField("DEVICE");
        deviceField.setAccessible(true);
        Object previousDevice = deviceField.get(null);
        deviceField.set(null, ring.device);   // MappableRingBuffer allocates and fences through RenderSystem.getDevice()
        for (int seed = 1; seed <= 3; seed++) {
            pregrowMatchesVanilla(seed);
        }
        for (int seed = 1; seed <= 2; seed++) {
            budgetFlushMatchesVanilla(seed);
            budgetBoundsAndPairing(seed, 2);
        }
        budgetReleasedAndUrgent();
        check(ring.fences > 10, "persistently mapped staging: the ring rotated (" + ring.fences + " rotations)");
        System.out.println("  persistently mapped staging: " + ring.fences + " ring rotations, " + ring.created.size() + " ring buffers");
        deviceField.set(null, previousDevice);
        persistentStaging = false;
        entityTexturePrefetch();
        System.out.printf("TravelHitchTest: %d checks, %d failures%n", checks, failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------------------------------------------------
    // helpers

    private static void check(final boolean ok, final String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    private static void checkEq(final long expected, final long actual, final String what) {
        check(expected == actual, what + " (expected " + expected + ", got " + actual + ")");
    }

    private static void onWorker(final Runnable r) {
        try {
            STAGER.submit(r).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Headless device + test-mode timeline over heap memory (as MetalDeviceTest's rig). */
    private static final class Rig {
        final MemorySegment page = MemorySegment.ofArray(new long[(int)(MtlNative.STATUS_PAGE_SIZEOF / 8)]);
        final MetalTimeline timeline = new MetalTimeline(0L, this.page);
        final MetalDevice device = MetalDevice.forTesting(0L, this.page, this.timeline, null, false);

        Rig() {
            this.device.encoder().submitFunction = (dev, info, result) -> {
                MemoryUtil.memPutInt(result + MtlNative.SUBMIT_RESULT_FLAGS, MtlNative.SUBMIT_RESULT_FLAG_COMMITTED);
                return MtlNative.OK;
            };
        }

        void completeAll() {
            long seq = this.timeline.pendingSeq();
            this.timeline.onCommitted(seq);
            this.page.set(java.lang.foreign.ValueLayout.JAVA_LONG, MtlNative.STATUS_PAGE_COMPLETED_VALUE, seq);
            this.timeline.drain();
        }
    }

    /** GpuBuffer stand-in: identity, size and usage; host memory for map() (staging ring buffers) on first use. */
    static final class FakeBuffer implements GpuBuffer {
        private static final AtomicInteger NEXT = new AtomicInteger();
        final int id = NEXT.incrementAndGet();
        final long size;
        final int usage;
        boolean closed;
        long memory;

        FakeBuffer(final int usage, final long size) {
            this.usage = usage;
            this.size = size;
        }

        @Override
        public long size() {
            return this.size;
        }

        @Override
        public int usage() {
            return this.usage;
        }

        @Override
        public boolean isClosed() {
            return this.closed;
        }

        @Override
        public GpuBufferSlice.MappedView map(final long offset, final long length, final boolean read, final boolean write) {
            if (this.memory == 0L) {
                this.memory = MemoryUtil.nmemAlloc(this.size);
            }
            return new GpuBufferSlice.MappedView(this.slice(offset, length), MemoryUtil.memByteBuffer(this.memory + offset, (int)length), () -> {});
        }

        @Override
        public void close() {
            this.closed = true;
            if (this.memory != 0L) {
                MemoryUtil.nmemFree(this.memory);
                this.memory = 0L;
            }
        }
    }

    /** FNV-1a over {@code length} bytes at {@code address} (copy contents). */
    static long checksum(final long address, final long length) {
        long sum = 1469598103934665603L;
        for (long i = 0; i < length; i++) {
            sum = (sum ^ (MemoryUtil.memGetByte(address + i) & 0xFF)) * 1099511628211L;
        }
        return sum;
    }

    static long checksum(final ByteBuffer data) {
        long sum = 1469598103934665603L;
        for (int i = data.position(); i < data.limit(); i++) {
            sum = (sum ^ (data.get(i) & 0xFF)) * 1099511628211L;
        }
        return sum;
    }

    /** The second test round runs the rigs on persistently mapped staging (Metal's StagingBuffer path). */
    static boolean persistentStaging;

    /** GpuDevice / CommandEncoder stand-ins: createBuffer makes FakeBuffers, writeToBuffer records the copy. */
    static final class FakeGpu {
        final List<FakeBuffer> created = new ArrayList<>();
        /** (destination offset, length, checksum) per copy, in order. */
        final List<long[]> copies = new ArrayList<>();
        final GpuDevice device;
        final CommandEncoder encoder;
        /** Fences created (MappableRingBuffer.rotate: one per staging ring rotation). */
        int fences;
        /** Copies whose source is persistently mapped staging memory (copyToBuffer). */
        int mappedCopies;

        FakeGpu(final DeviceInfo info) {
            InvocationHandler encoderHandler = (proxy, method, args) -> {
                if (method.getName().equals("writeToBuffer") && args.length == 2 && args[1] instanceof ByteBuffer data) {
                    GpuBufferSlice slice = (GpuBufferSlice)args[0];
                    this.copies.add(new long[]{slice.offset(), data.remaining(), checksum(data)});
                    return null;
                }
                if (method.getName().equals("copyToBuffer") && args.length == 2) {
                    // PersistentlyMapped staging: the bytes the GPU would copy are those in the mapped ring buffer now.
                    GpuBufferSlice source = (GpuBufferSlice)args[0];
                    GpuBufferSlice target = (GpuBufferSlice)args[1];
                    FakeBuffer src = (FakeBuffer)source.buffer();
                    check(src.memory != 0L && !src.closed, "copyToBuffer source is live mapped staging memory");
                    this.copies.add(new long[]{target.offset(), source.length(), checksum(src.memory + source.offset(), source.length())});
                    this.mappedCopies++;
                    return null;
                }
                if (method.getName().equals("createFence")) {
                    this.fences++;
                    return (com.mojang.renderpearl.api.commands.GpuFence)Proxy.newProxyInstance(
                        com.mojang.renderpearl.api.commands.GpuFence.class.getClassLoader(), new Class<?>[]{com.mojang.renderpearl.api.commands.GpuFence.class},
                        (p, m, a) -> m.getName().equals("awaitCompletion") ? Boolean.TRUE : null
                    );
                }
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
                throw new UnsupportedOperationException(method.getName());
            };
            this.encoder = (CommandEncoder)Proxy.newProxyInstance(CommandEncoder.class.getClassLoader(), new Class<?>[]{CommandEncoder.class}, encoderHandler);
            InvocationHandler deviceHandler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "createBuffer":
                        if (args.length == 3 && args[2] instanceof Long size) {
                            FakeBuffer buffer = new FakeBuffer((Integer)args[1], size);
                            this.created.add(buffer);
                            return buffer;
                        }
                        break;
                    case "getDeviceInfo":
                        return info;
                    case "createCommandEncoder":
                        return this.encoder;
                    default:
                        break;
                }
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
                throw new UnsupportedOperationException(method.getName());
            };
            this.device = (GpuDevice)Proxy.newProxyInstance(GpuDevice.class.getClassLoader(), new Class<?>[]{GpuDevice.class}, deviceHandler);
        }
    }

    /** Heap source with a latency: a requested buffer becomes ready {@code latency} ticks later. */
    static final class FakeSource implements UberGpuBuffer.HeapSource {
        final int latency;
        final Map<Long, Integer> pending = new HashMap<>();
        final Map<Long, FakeBuffer> ready = new HashMap<>();
        int requests;
        int handedOut;
        final List<FakeBuffer> given = new ArrayList<>();

        FakeSource(final int latency) {
            this.latency = latency;
        }

        void tick() {
            for (Map.Entry<Long, Integer> e : new ArrayList<>(this.pending.entrySet())) {
                if (e.getValue() <= 1) {
                    this.pending.remove(e.getKey());
                    this.ready.put(e.getKey(), new FakeBuffer((int)(e.getKey() >>> 40), e.getKey() & ((1L << 40) - 1)));
                } else {
                    e.setValue(e.getValue() - 1);
                }
            }
        }

        @Override
        public void request(final int usage, final long size) {
            long key = ((long)usage << 40) | size;
            if (!this.pending.containsKey(key) && !this.ready.containsKey(key)) {
                this.requests++;
                this.pending.put(key, this.latency);
            }
        }

        @Override
        public GpuBuffer poll(final int usage, final long size) {
            FakeBuffer buffer = this.ready.remove(((long)usage << 40) | size);
            if (buffer != null) {
                check(buffer.usage == usage && buffer.size == size, "heap source buffer has the requested usage and size");
                this.handedOut++;
                this.given.add(buffer);
            }
            return buffer;
        }
    }

    /**
     * Device info of the fake GPU. First round: Cpu staging (writeToBuffer). With {@link #persistentStaging}: what
     * MetalDevice reports (writeToBufferIsSlow, explicit depth, persistent mapping), so StagingBuffer.create picks
     * PersistentlyMapped as in the game on Metal.
     */
    static DeviceInfo fakeInfo() {
        DeviceInfo metal = new Rig().device.getDeviceInfo();
        boolean p = persistentStaging;
        return new DeviceInfo(
            "fake", "fake", "fake", true, "fake", 1.0F, metal.limits(), new DeviceFeatures(true, true, false, false, true, true, true, p), Set.of(),
            new HintsAndWorkarounds(p, false, p, false), DeviceType.INTEGRATED
        );
    }

    static final class Key {
        final int id;

        Key(final int id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return "k" + this.id;
        }
    }

    static ByteBuffer payload(final Random random, final int size) {
        ByteBuffer b = MemoryUtil.memAlloc(size);
        for (int i = 0; i < size; i += 8) {
            b.put(i, (byte)random.nextInt());
        }
        return b;
    }

    // ------------------------------------------------------------------------------------------------------------
    // A. Metal heap reserve (headless device)

    private static void heapReserve() throws Exception {
        Rig rig = new Rig();
        MetalHeapReserve.install(rig.device);
        MetalHeapReserve reserve = MetalHeapReserve.active();
        check(reserve != null, "install makes the reserve active");
        int vertexUsage = GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC;
        long size = 4L << 20;
        check(MetalHeapReserve.poll(vertexUsage, size) == null, "poll before any request returns null");
        MetalHeapReserve.request(vertexUsage, size);
        MetalHeapReserve.request(vertexUsage, size);
        check(reserve.awaitWorkerIdle(5000), "worker finishes");
        checkEq(1, reserve.creations(), "two requests of one class create one buffer");
        checkEq(2, reserve.state(vertexUsage, size), "class READY");
        GpuBuffer buffer = MetalHeapReserve.poll(vertexUsage, size);
        check(buffer instanceof MetalBuffer, "poll returns a MetalBuffer");
        if (buffer instanceof MetalBuffer metal) {
            checkEq(size, metal.size(), "reserved buffer size");
            checkEq(vertexUsage, metal.usage(), "reserved buffer usage");
            check(!metal.isClosed() && metal.contents() != 0L, "reserved buffer open with CPU contents");
            MemoryUtil.memPutLong(metal.contents() + size - 8, 42L);
            checkEq(42L, MemoryUtil.memGetLong(metal.contents() + size - 8), "reserved contents writable to the end");
            metal.close();
            rig.completeAll();
        }
        check(MetalHeapReserve.poll(vertexUsage, size) == null, "a ready buffer is handed out once");
        checkEq(0, reserve.state(vertexUsage, size), "class IDLE after poll");
        // Class limit.
        for (int i = 0; i < MetalHeapReserve.MAX_CLASSES + 2; i++) {
            MetalHeapReserve.request(GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, (1L << 20) + 4096L * i);
        }
        check(reserve.awaitWorkerIdle(5000), "worker finishes the class requests");
        checkEq(-1, reserve.state(GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, (1L << 20) + 4096L * (MetalHeapReserve.MAX_CLASSES + 1)),
            "no slot beyond MAX_CLASSES");
        // Leave one READY backing, then close the device: uninstall releases it and deactivates the reserve.
        MetalHeapReserve.request(vertexUsage, size);
        check(reserve.awaitWorkerIdle(5000), "worker finishes");
        checkEq(2, reserve.state(vertexUsage, size), "READY before close");
        try {
            rig.device.close();
        } catch (RuntimeException e) {
            System.out.println("  note: headless device close threw after the reserve step: " + e);
        }
        check(MetalHeapReserve.active() == null, "device close deactivates the reserve");
        checkEq(0, reserve.state(vertexUsage, size), "close frees a ready backing");
        MetalHeapReserve.request(vertexUsage, size);
        check(MetalHeapReserve.poll(vertexUsage, size) == null, "no reserve after close");
    }

    /**
     * Real Metal device (no window, no frame): what a 128 MiB terrain heap costs when it is created on the calling
     * thread (vanilla: in-frame) versus collected from the reserve worker, and that the reserve path works end to end.
     */
    private static void nativeHeapReserve() throws Exception {
        MetalBackend backend = new MetalBackend();
        try {
            backend.loadLibrary();
        } catch (com.mojang.renderpearl.api.device.BackendCreationException e) {
            System.out.println("  skip: native heap reserve (libmcmetal not loadable: " + e.getMessage() + ")");
            return;
        }
        MetalDevice device = new MetalDevice(backend, new com.mojang.renderpearl.api.device.GpuDebugOptions(0, false, false, false));
        MetalHeapReserve reserve = MetalHeapReserve.active();
        check(reserve != null, "native device installs the reserve");
        int usage = GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC;
        long size = 128L << 20;
        long[] createNs = new long[3];
        long[] touchNs = new long[3];
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            MetalBuffer buffer = (MetalBuffer)device.createBuffer(null, usage, size); // what vanilla does in-frame
            long t1 = System.nanoTime();
            for (long offset = 0L; offset < size; offset += 16384L) {
                MemoryUtil.memPutByte(buffer.contents() + offset, (byte)0);
            }
            long t2 = System.nanoTime();
            createNs[i] = t1 - t0;
            touchNs[i] = t2 - t1;
            buffer.close();
        }
        long t0 = System.nanoTime();
        MetalHeapReserve.request(usage, size);
        long t1 = System.nanoTime();
        check(reserve != null && reserve.awaitWorkerIdle(10000), "native reserve worker finishes");
        long t2 = System.nanoTime();
        GpuBuffer reserved = MetalHeapReserve.poll(usage, size);
        long t3 = System.nanoTime();
        check(reserved instanceof MetalBuffer m && m.size() == size && m.contents() != 0L && m.gpuAddress() != 0L, "native reserved 128 MiB buffer");
        checkEq(1, reserve == null ? 0 : reserve.residentByGpu(), "made resident through mcmtl_ext_buffer_make_resident");
        if (reserved instanceof MetalBuffer m) {
            checkEq(0, MemoryUtil.memGetInt(m.contents()), "residency fill wrote the zeros a fresh buffer holds");
        }
        if (reserved instanceof MetalBuffer m) {
            MemoryUtil.memPutLong(m.contents() + size - 8, 7L);
            checkEq(7L, MemoryUtil.memGetLong(m.contents() + size - 8), "native reserved buffer writable");
        }
        check(t3 - t2 < 1_000_000L, "poll of a ready buffer takes well under a millisecond (" + (t3 - t2) + " ns)");
        System.out.printf(java.util.Locale.ROOT,
"  native 128 MiB heap: in-thread create %.2f / %.2f / %.2f ms, CPU page touch %.2f / %.2f / %.2f ms; reserve: request %.3f ms, worker (create + GPU residency) %.2f ms, poll %.3f ms%n",
            createNs[0] / 1e6, createNs[1] / 1e6, createNs[2] / 1e6, touchNs[0] / 1e6, touchNs[1] / 1e6, touchNs[2] / 1e6, (t1 - t0) / 1e6, (t2 - t1) / 1e6,
            (t3 - t2) / 1e6);
        if (reserved != null) {
            reserved.close(); // deferred release (runs at device close here: nothing is submitted)
        }
        try {
            device.close();
        } catch (RuntimeException e) {
            System.out.println("  note: native device close: " + e);
        }
        check(MetalHeapReserve.active() == null, "native device close deactivates the reserve");
    }

    // ------------------------------------------------------------------------------------------------------------
    // B. UberGpuBuffer pregrow vs vanilla

    /** One uber buffer with its own staging buffer and fake GPU, plus the record of callbacks. */
    static final class Uber {
        final FakeGpu gpu;
        final StagingBuffer staging;
        final UberGpuBuffer<Key> buffer;
        final List<Integer> callbacks = new ArrayList<>();
        /** Per key: checksum of the bytes last staged; every callback checks the copy just made carried them. */
        final Map<Key, Long> expected = new IdentityHashMap<>();
        int badCopies;

        Uber(final DeviceInfo info, final int heapSize, final boolean pregrow, final UberGpuBuffer.HeapSource source) {
            this.gpu = new FakeGpu(info);
            this.staging = StagingBuffer.create("test", this.gpu.device, 96 << 20);
            this.buffer = new UberGpuBuffer<>("test", GpuBuffer.USAGE_VERTEX, heapSize, 16, this.staging);
            this.buffer.setPregrowForTesting(pregrow, source);
        }

        boolean stage(final Key key, final ByteBuffer data) {
            boolean staged = this.buffer.addAllocation(key, k -> {
                this.callbacks.add(k.id);
                if (this.gpu.copies.get(this.gpu.copies.size() - 1)[2] != this.expected.get(k)) {
                    this.badCopies++;
                }
            }, data.duplicate());
            if (staged) {
                this.expected.put(key, checksum(data));
            }
            return staged;
        }

        boolean upload() {
            try (StagingBuffer.Uploader uploader = this.staging.startUploading(this.gpu.encoder)) {
                return this.buffer.uploadStagedAllocations(this.gpu.device, uploader);
            }
        }
    }

    /** The r5-base (pre-TASK-E) UberGpuBuffer, verbatim (tools/ref/RefUberGpuBuffer.java), on the same fakes. */
    static final class RefUber {
        final FakeGpu gpu;
        final StagingBuffer staging;
        final RefUberGpuBuffer<Key> buffer;
        final List<Integer> callbacks = new ArrayList<>();

        RefUber(final DeviceInfo info, final int heapSize) {
            this.gpu = new FakeGpu(info);
            this.staging = StagingBuffer.create("ref", this.gpu.device, 96 << 20);
            this.buffer = new RefUberGpuBuffer<>("ref", GpuBuffer.USAGE_VERTEX, heapSize, 16, this.staging);
        }

        boolean stage(final Key key, final ByteBuffer data) {
            return this.buffer.addAllocation(key, k -> this.callbacks.add(k.id), data.duplicate());
        }

        boolean upload() {
            try (StagingBuffer.Uploader uploader = this.staging.startUploading(this.gpu.encoder)) {
                return this.buffer.uploadStagedAllocations(this.gpu.device, uploader);
            }
        }
    }

    private static void pregrowMatchesVanilla(final int seed) {
        DeviceInfo info = fakeInfo();
        int heapSize = 16 << 20;
        FakeSource source = new FakeSource(1 + seed % 3);
        Uber vanilla = new Uber(info, heapSize, false, UberGpuBuffer.HeapSource.METAL_RESERVE);
        Uber pregrow = new Uber(info, heapSize, true, source);
        // Review finding (E, minor): the toggle-off path ("vanilla") is also compared with the r5-base code itself.
        RefUber ref = new RefUber(info, heapSize);
        boolean refOk = true;
        Random random = new Random(seed);
        List<Key> live = new ArrayList<>();
        int nextKey = 0;
        boolean ok = true;
        int maxHeaps = 0;
        for (int frame = 0; frame < 1500 && ok; frame++) {
            boolean growing = (frame / 150) % 2 == 0;
            int adds = random.nextInt(growing ? 12 : 4);
            for (int i = 0; i < adds; i++) {
                Key key;
                if (!live.isEmpty() && random.nextInt(6) == 0) {
                    key = live.get(random.nextInt(live.size())); // re-stage (replace) an existing key
                } else {
                    key = new Key(nextKey++);
                    live.add(key);
                }
                ByteBuffer data = payload(random, 16 * (1 + random.nextInt(random.nextInt(8) == 0 ? 131072 : 8192)));
                boolean staged = vanilla.stage(key, data);
                check(staged == pregrow.stage(key, data) && staged == ref.stage(key, data), "same staging result");
                MemoryUtil.memFree(data);
            }
            int removes = random.nextInt(growing ? 4 : 14);
            for (int i = 0; i < removes && !live.isEmpty(); i++) {
                Key key = live.remove(random.nextInt(live.size()));
                vanilla.buffer.removeAllocation(key);
                pregrow.buffer.removeAllocation(key);
                ref.buffer.removeAllocation(key);
            }
            source.tick();
            boolean a = vanilla.upload();
            boolean b = pregrow.upload();
            boolean r = ref.upload();
            ok &= a == b;
            refOk &= a == r && vanilla.buffer.heapCount() == ref.buffer.heapCount() && vanilla.callbacks.equals(ref.callbacks)
                && vanilla.gpu.copies.size() == ref.gpu.copies.size();
            for (Key key : live) {
                var x = vanilla.buffer.getAllocation(key);
                var y = ref.buffer.getAllocation(key);
                refOk &= vanilla.buffer.heapIndex(key) == ref.buffer.heapIndex(key)
                    && (x == null) == (y == null) && (x == null || x.getOffsetFromHeap() == y.getOffsetFromHeap() && x.getSize() == y.getSize());
            }
            ok &= vanilla.buffer.heapCount() == pregrow.buffer.heapCount();
            ok &= vanilla.callbacks.equals(pregrow.callbacks);
            ok &= vanilla.gpu.copies.size() == pregrow.gpu.copies.size();
            for (Key key : live) {
                ok &= vanilla.buffer.heapIndex(key) == pregrow.buffer.heapIndex(key);
                var x = vanilla.buffer.getAllocation(key);
                var y = pregrow.buffer.getAllocation(key);
                ok &= (x == null) == (y == null) && (x == null || x.getOffsetFromHeap() == y.getOffsetFromHeap() && x.getSize() == y.getSize());
            }
            if (!ok) {
                check(false, "seed " + seed + " frame " + frame + ": pregrow diverged from vanilla");
            }
            maxHeaps = Math.max(maxHeaps, vanilla.buffer.heapCount());
            check(UberGpuBuffer.spareBytesTotal() <= TravelHitchToggles.PREGROW_MAX_SPARE_BYTES, "spare bytes within the bound");
        }
        for (int i = 0; i < vanilla.gpu.copies.size() && ok; i++) {
            long[] x = vanilla.gpu.copies.get(i);
            long[] y = pregrow.gpu.copies.get(i);
            ok &= x[0] == y[0] && x[1] == y[1] && x[2] == y[2];
        }
        check(ok, "seed " + seed + ": identical copies (offset, length, bytes), callbacks, heaps and placements");
        for (int i = 0; i < vanilla.gpu.copies.size() && refOk; i++) {
            long[] x = vanilla.gpu.copies.get(i);
            long[] y = ref.gpu.copies.get(i);
            refOk &= x[0] == y[0] && x[1] == y[1] && x[2] == y[2];
        }
        check(refOk, "seed " + seed + ": toggle-off path == r5-base UberGpuBuffer (return values, copies, callbacks, heaps, placements)");
        checkEq(0, vanilla.badCopies + pregrow.badCopies, "seed " + seed + ": every callback's copy carried the bytes staged for its key");
        if (persistentStaging) {
            check(vanilla.gpu.mappedCopies > 0 && vanilla.gpu.mappedCopies == vanilla.gpu.copies.size(), "seed " + seed + ": copies came from mapped staging");
        }
        check(maxHeaps >= 3, "seed " + seed + ": workload grows to several heaps (" + maxHeaps + ")");
        check(pregrow.buffer.sparesUsed() > 0, "seed " + seed + ": heaps created from spares (" + pregrow.buffer.pregrowStats() + ")");
        check(pregrow.buffer.heapsCreatedInFrame() < vanilla.buffer.heapsCreatedInFrame(),
            "seed " + seed + ": fewer in-frame heap creations (" + pregrow.buffer.heapsCreatedInFrame() + " vs " + vanilla.buffer.heapsCreatedInFrame() + ")");
        System.out.printf("  pregrow seed %d: vanilla created %d heaps in-frame, pregrow %d (%s, %d source requests)%n", seed,
            vanilla.buffer.heapsCreatedInFrame(), pregrow.buffer.heapsCreatedInFrame(), pregrow.buffer.pregrowStats(), source.requests);
        vanilla.buffer.close();
        pregrow.buffer.close();
        ref.buffer.close();
        vanilla.staging.close();
        pregrow.staging.close();
        ref.staging.close();
        checkEq(0, UberGpuBuffer.spareBytesTotal(), "close gives every spare back");
        for (FakeBuffer f : pregrow.gpu.created) {
            check(f.closed, "every created heap buffer closed");
        }
    }

    /** Spare kept from a freed heap, released above the high-water mark, closed with the uber buffer. */
    private static void pregrowSpareLifecycle() {
        DeviceInfo info = fakeInfo();
        int heapSize = 16 << 20;
        FakeSource source = new FakeSource(1);
        Uber uber = new Uber(info, heapSize, true, source);
        Random random = new Random(7);
        // Fill one heap to ~90 %: below the low-water mark (25 % free) -> request, ready next tick -> spare.
        List<Key> keys = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            Key key = new Key(i);
            keys.add(key);
            ByteBuffer data = payload(random, (heapSize / 10));
            uber.stage(key, data);
            MemoryUtil.memFree(data);
        }
        uber.upload();
        checkEq(1, uber.buffer.heapCount(), "one heap");
        checkEq(1, source.requests, "low free space requests a spare");
        check(!uber.buffer.hasSpare(), "spare not ready yet");
        source.tick();
        uber.upload();
        check(uber.buffer.hasSpare(), "ready spare collected");
        // Overflow: the second heap takes the spare (no createBuffer call).
        int createdBefore = uber.gpu.created.size();
        for (int i = 9; i < 12; i++) {
            Key key = new Key(i);
            keys.add(key);
            ByteBuffer data = payload(random, heapSize / 10);
            uber.stage(key, data);
            MemoryUtil.memFree(data);
        }
        check(uber.upload(), "heap added (return value as vanilla)");
        checkEq(2, uber.buffer.heapCount(), "two heaps");
        checkEq(createdBefore, uber.gpu.created.size(), "second heap from the spare, no in-frame createBuffer");
        checkEq(1, uber.buffer.sparesUsed(), "spare used");
        // Free the second heap's allocations: the empty heap is kept as the spare (free space stays below high water).
        for (int i = 9; i < 12; i++) {
            uber.buffer.removeAllocation(keys.get(i));
        }
        check(uber.upload(), "heap removed (return value as vanilla)");
        checkEq(1, uber.buffer.heapCount(), "back to one heap");
        check(uber.buffer.hasSpare(), "freed heap kept as spare");
        checkEq(1, source.given.size(), "one buffer from the source");
        check(!source.given.get(0).closed, "kept heap buffer (the source's, used as heap 1) not closed");
        // Drop most allocations: free space above the high-water mark -> spare released.
        for (int i = 0; i < 8; i++) {
            uber.buffer.removeAllocation(keys.get(i));
        }
        uber.upload();
        check(!uber.buffer.hasSpare(), "spare released above the high-water mark");
        check(source.given.get(0).closed, "released spare closed");
        checkEq(0, UberGpuBuffer.spareBytesTotal(), "spare bytes back to 0");
        // Review finding (E, minor): an uber buffer whose last heap became free (a layer with nothing in view) keeps that
        // heap as its spare, but gives it back after PREGROW_IDLE_PASSES passes without a heap, so idle layers do not hold
        // spares that count against the global cap while another layer grows. A heap needed before then takes the spare.
        for (int i = 8; i < 9; i++) {
            uber.buffer.removeAllocation(keys.get(i));
        }
        uber.upload();
        checkEq(0, uber.buffer.heapCount(), "last heap freed");
        check(uber.buffer.hasSpare(), "last freed heap kept as the spare");
        checkEq(heapSize, UberGpuBuffer.spareBytesTotal(), "the idle spare counts against the cap");
        for (int p = 2; p < TravelHitchToggles.PREGROW_IDLE_PASSES; p++) {
            uber.upload();
        }
        check(uber.buffer.hasSpare(), "spare still held after PREGROW_IDLE_PASSES - 1 passes without a heap");
        Key regrow = new Key(100);
        ByteBuffer regrowData = payload(random, heapSize / 10);
        uber.stage(regrow, regrowData);
        int createdBeforeRegrow = uber.gpu.created.size();
        uber.upload();
        checkEq(1, uber.buffer.heapCount(), "layer grows again");
        checkEq(createdBeforeRegrow, uber.gpu.created.size(), "the regrown heap took the idle spare (no in-frame createBuffer)");
        check(!uber.buffer.hasSpare() && UberGpuBuffer.spareBytesTotal() == 0L, "spare consumed");
        uber.buffer.removeAllocation(regrow);
        uber.upload();
        check(uber.buffer.hasSpare(), "freed again: kept as the spare");
        for (int p = 2; p < TravelHitchToggles.PREGROW_IDLE_PASSES; p++) {
            uber.upload();
        }
        check(uber.buffer.hasSpare(), "idle count restarted after the regrow");
        uber.upload();
        check(!uber.buffer.hasSpare(), "spare given back after PREGROW_IDLE_PASSES passes without a heap");
        checkEq(1, uber.buffer.sparesReleasedIdle(), "one idle release");
        checkEq(0, UberGpuBuffer.spareBytesTotal(), "idle release frees the cap");
        boolean allClosed = true;
        for (FakeBuffer f : uber.gpu.created) {
            allClosed &= f.closed;
        }
        for (FakeBuffer f : source.given) {
            allClosed &= f.closed;
        }
        check(allClosed, "every heap buffer closed once the idle spare is released");
        MemoryUtil.memFree(regrowData);
        uber.buffer.close();
    }

    // ------------------------------------------------------------------------------------------------------------
    // C. Upload budget

    /** A section-layer pair: vertex and index uber buffers sharing one staging buffer, like SectionRenderDispatcher. */
    static final class Pair {
        final FakeGpu gpu;
        final StagingBuffer staging;
        final UberGpuBuffer<Key> vertex;
        final UberGpuBuffer<Key> index;
        final UberGpuBuffer.UploadBudget budget;
        final ObjectOpenHashSet<Key> carried = new ObjectOpenHashSet<>();
        /** Per key: pass number of the vertex / index callback (last one). */
        final Map<Key, Integer> vertexPass = new IdentityHashMap<>();
        final Map<Key, Integer> indexPass = new IdentityHashMap<>();
        final List<String> events = new ArrayList<>();
        int pass;
        /** Per key: checksums of the vertex / index bytes last staged; each callback checks the copy just made. */
        final Map<Key, Long> expectedV = new IdentityHashMap<>();
        final Map<Key, Long> expectedI = new IdentityHashMap<>();
        int badCopies;

        Pair(final DeviceInfo info, final UberGpuBuffer.UploadBudget budget) {
            this.gpu = new FakeGpu(info);
            this.staging = StagingBuffer.create("test", this.gpu.device, 96 << 20);
            this.vertex = new UberGpuBuffer<>("v", GpuBuffer.USAGE_VERTEX, 16 << 20, 16, this.staging);
            this.index = new UberGpuBuffer<>("i", GpuBuffer.USAGE_INDEX, 4 << 20, 8, this.staging);
            this.vertex.setPregrowForTesting(false, UberGpuBuffer.HeapSource.METAL_RESERVE);
            this.index.setPregrowForTesting(false, UberGpuBuffer.HeapSource.METAL_RESERVE);
            this.budget = budget;
            this.vertex.setUploadBudget(budget);
            this.index.setUploadBudget(budget);
        }

        void stage(final Key key, final ByteBuffer v, final ByteBuffer i) {
            if (v != null) {
                if (this.vertex.addAllocation(key, k -> {
                    this.vertexPass.put(k, this.pass);
                    this.events.add("v" + k.id);
                    this.verifyLastCopy(this.expectedV.get(k));
                }, v.duplicate())) {
                    this.expectedV.put(key, checksum(v));
                }
            }
            if (this.index.addAllocation(key, k -> {
                this.indexPass.put(k, this.pass);
                this.events.add("i" + k.id);
                this.verifyLastCopy(this.expectedI.get(k));
            }, i.duplicate())) {
                this.expectedI.put(key, checksum(i));
            }
        }

        private void verifyLastCopy(final Long expected) {
            if (expected == null || this.gpu.copies.get(this.gpu.copies.size() - 1)[2] != expected) {
                this.badCopies++;
            }
        }

        void release(final Key key) {
            this.vertex.removeAllocation(key);
            this.index.removeAllocation(key);
        }

        /** Vanilla pass (budget == null) or one budgeted frame pass. */
        void upload(final boolean flush) {
            this.pass++;
            try (StagingBuffer.Uploader uploader = this.staging.startUploading(this.gpu.encoder)) {
                if (this.budget == null) {
                    this.vertex.uploadStagedAllocations(this.gpu.device, uploader);
                    this.index.uploadStagedAllocations(this.gpu.device, uploader);
                } else {
                    this.budget.beginFrame(flush);
                    this.carried.clear();
                    this.vertex.uploadStagedAllocationsBudgeted(this.gpu.device, uploader, this.budget, true, this.carried);
                    this.index.uploadStagedAllocationsBudgeted(this.gpu.device, uploader, this.budget, false, this.carried);
                    this.carried.clear();
                }
            }
        }
    }

    private static void budgetFlushMatchesVanilla(final int seed) {
        DeviceInfo info = fakeInfo();
        Pair vanilla = new Pair(info, null);
        Pair flushed = new Pair(info, new UberGpuBuffer.UploadBudget(1, 2));
        Random random = new Random(100 + seed);
        List<Key> live = new ArrayList<>();
        int next = 0;
        boolean ok = true;
        for (int frame = 0; frame < 600; frame++) {
            boolean growing = (frame / 100) % 2 == 0;
            int adds = random.nextInt(growing ? 10 : 3);
            for (int a = 0; a < adds; a++) {
                Key key = new Key(next++);
                live.add(key);
                ByteBuffer v = payload(random, 28 * (1 + random.nextInt(20000)));
                ByteBuffer i = payload(random, 8 * (1 + random.nextInt(4000)));
                onWorker(() -> {
                    vanilla.stage(key, v, i);
                    flushed.stage(key, v, i);
                });
                MemoryUtil.memFree(v);
                MemoryUtil.memFree(i);
            }
            int removes = random.nextInt(growing ? 3 : 10);
            for (int r = 0; r < removes && !live.isEmpty(); r++) {
                Key key = live.remove(random.nextInt(live.size()));
                vanilla.release(key);
                flushed.release(key);
            }
            vanilla.upload(false);
            flushed.upload(true);
            ok &= vanilla.events.equals(flushed.events);
            for (Key key : live) {
                ok &= vanilla.vertex.heapIndex(key) == flushed.vertex.heapIndex(key) && vanilla.index.heapIndex(key) == flushed.index.heapIndex(key);
                var x = vanilla.vertex.getAllocation(key);
                var y = flushed.vertex.getAllocation(key);
                ok &= (x == null) == (y == null) && (x == null || x.getOffsetFromHeap() == y.getOffsetFromHeap());
            }
        }
        ok &= vanilla.gpu.copies.size() == flushed.gpu.copies.size();
        for (int c = 0; c < vanilla.gpu.copies.size() && ok; c++) {
            long[] x = vanilla.gpu.copies.get(c);
            long[] y = flushed.gpu.copies.get(c);
            ok &= x[0] == y[0] && x[1] == y[1] && x[2] == y[2];
        }
        check(ok, "seed " + seed + ": budget path with flush == vanilla (callbacks, copies, placements)");
        checkEq(0, vanilla.badCopies + flushed.badCopies, "seed " + seed + ": every callback's copy carried the bytes staged for its key");
        vanilla.vertex.close();
        vanilla.index.close();
        flushed.vertex.close();
        flushed.index.close();
        vanilla.staging.close();
        flushed.staging.close();
    }

    private static void budgetBoundsAndPairing(final int seed, final int maxDelay) {
        DeviceInfo info = fakeInfo();
        UberGpuBuffer.UploadBudget budget = new UberGpuBuffer.UploadBudget(256L * 1024L, maxDelay);
        Pair pair = new Pair(info, budget);
        Random random = new Random(200 + seed);
        Map<Key, Integer> stagedAfterPass = new IdentityHashMap<>();
        List<Key> live = new ArrayList<>();
        java.util.Set<Key> released = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        int next = 0;
        int worstDelay = 0;
        boolean ok = true;
        for (int frame = 0; frame < 800; frame++) {
            int adds = frame % 50 < 5 ? 30 : random.nextInt(4); // bursts
            for (int a = 0; a < adds; a++) {
                Key key = new Key(next++);
                live.add(key);
                stagedAfterPass.put(key, pair.pass);
                ByteBuffer v = payload(random, 28 * (1 + random.nextInt(4000)));
                ByteBuffer i = payload(random, 8 * (1 + random.nextInt(1000)));
                onWorker(() -> pair.stage(key, v, i));
                MemoryUtil.memFree(v);
                MemoryUtil.memFree(i);
            }
            if (random.nextInt(3) == 0 && !live.isEmpty()) {
                Key key = live.remove(random.nextInt(live.size()));
                pair.release(key);
                released.add(key);
            }
            pair.upload(false);
            for (Key key : live) {
                Integer staged = stagedAfterPass.get(key);
                Integer v = pair.vertexPass.get(key);
                Integer i = pair.indexPass.get(key);
                if (v == null) {
                    // Still carried: must not be older than the bound.
                    ok &= pair.pass - (staged + 1) < maxDelay;
                    ok &= i == null;
                } else {
                    int delay = v - (staged + 1);
                    worstDelay = Math.max(worstDelay, delay);
                    ok &= delay >= 0 && delay <= maxDelay;
                    ok &= i != null && i.intValue() == v.intValue();
                }
            }
            for (Key key : released) {
                ok &= pair.vertex.getAllocation(key) == null && pair.index.getAllocation(key) == null;
            }
            if (!ok) {
                check(false, "seed " + seed + " frame " + frame + ": delay bound / pairing / release violated");
                break;
            }
        }
        // Released keys never got a callback after their release (they had none before either: released at random).
        pair.upload(true);
        checkEq(0, pair.vertex.stagedCount() + pair.index.stagedCount(), "seed " + seed + ": nothing staged after a flush");
        for (Key key : live) {
            ok &= pair.vertex.getAllocation(key) != null && pair.index.getAllocation(key) != null;
        }
        check(ok, "seed " + seed + " maxDelay " + maxDelay + ": every upload within " + maxDelay + " frames of vanilla, vertex/index together");
        if (maxDelay > 0) {
            check(budget.carriedEntries > 0 && worstDelay > 0, "seed " + seed + ": the budget actually carried uploads (worst delay " + worstDelay + ")");
        } else {
            checkEq(0, worstDelay, "maxDelay 0 never delays");
        }
        checkEq(0, pair.badCopies, "seed " + seed + ": every carried upload copied the bytes staged for its key (no staging reuse under a carried entry)");
        System.out.printf("  budget seed %d maxDelay %d%s: %d carry decisions, worst delay %d frame(s), %d copies%n", seed, maxDelay,
            persistentStaging ? " (persistently mapped staging)" : "", budget.carriedEntries, worstDelay, pair.gpu.copies.size());
        pair.vertex.close();
        pair.index.close();
        pair.staging.close();
    }

    private static void budgetReleasedAndUrgent() {
        DeviceInfo info = fakeInfo();
        UberGpuBuffer.UploadBudget budget = new UberGpuBuffer.UploadBudget(1024L, 3);
        Pair pair = new Pair(info, budget);
        Random random = new Random(5);
        Key first = new Key(1);
        Key second = new Key(2);
        Key third = new Key(3);
        ByteBuffer v = payload(random, 28 * 1000);
        ByteBuffer i = payload(random, 8 * 300);
        onWorker(() -> {
            pair.stage(first, v, i);
            pair.stage(second, v, i);
            pair.stage(third, v, i);
        });
        pair.upload(false);
        checkEq(1, pair.vertexPass.size(), "1 KiB budget: only the first entry of the pass is uploaded");
        // Release a carried key: its staged entries are dropped, never uploaded.
        Key carried = pair.vertexPass.containsKey(first) ? second : first;
        pair.release(carried);
        pair.upload(false);
        pair.upload(true);
        check(!pair.vertexPass.containsKey(carried) && !pair.indexPass.containsKey(carried), "released carried key never uploaded");
        check(pair.vertex.getAllocation(carried) == null && pair.index.getAllocation(carried) == null, "released carried key has no allocation");
        checkEq(0, pair.vertex.stagedCount() + pair.index.stagedCount(), "released entries dropped");
        // Render-thread staging (synchronous compile) is never carried, even with the budget spent.
        Key a = new Key(10);
        Key b = new Key(11);
        Key urgent = new Key(12);
        onWorker(() -> {
            pair.stage(a, v, i);
            pair.stage(b, v, i);
        });
        pair.stage(urgent, v, i); // main thread = render thread
        pair.upload(false);
        check(pair.vertexPass.get(urgent) != null && pair.vertexPass.get(urgent) == pair.pass, "render-thread staging uploaded in the next pass");
        // Index-only re-stage of an uploaded key (translucency re-sort) is never delayed.
        pair.upload(true);
        Key resorted = urgent;
        ByteBuffer sorted = payload(random, 8 * 300);
        onWorker(() -> {
            pair.stage(new Key(20), v, i);
            pair.stage(new Key(21), v, i);
            pair.index.addAllocation(resorted, k -> pair.indexPass.put(k, pair.pass), sorted.duplicate());
        });
        pair.upload(false);
        checkEq(pair.pass, pair.indexPass.get(resorted), "index-only re-sort uploaded in the next pass");
        // A callback that releases another carried key during the pass drops that key's entries too.
        pair.upload(true);
        Key victim = new Key(30);
        Key releaser = new Key(31);
        onWorker(() -> {
            pair.vertex.addAllocation(releaser, k -> {
                pair.vertexPass.put(k, pair.pass);
                pair.release(victim);
            }, v.duplicate());
            pair.index.addAllocation(releaser, k -> pair.indexPass.put(k, pair.pass), i.duplicate());
            pair.stage(victim, v, i);
        });
        for (int n = 0; n < 6; n++) {
            pair.upload(false);
        }
        check(pair.vertexPass.containsKey(releaser), "releaser uploaded");
        check(pair.vertex.getAllocation(victim) == null && pair.index.getAllocation(victim) == null, "victim released by a callback has no allocation");
        checkEq(0, pair.vertex.stagedCount() + pair.index.stagedCount(), "no entry left staged");
        MemoryUtil.memFree(v);
        MemoryUtil.memFree(i);
        MemoryUtil.memFree(sorted);
        checkEq(0, pair.badCopies, "released / urgent / re-sort: every callback's copy carried the bytes staged for its key");
        pair.vertex.close();
        pair.index.close();
        pair.staging.close();
    }


    // ------------------------------------------------------------------------------------------------------------
    // C2. Upload budget: per-section mesh ordering, on the real SectionRenderDispatcher / RenderSection / CompileTask

    /** SectionCompiler stand-in: the next compile returns {@link #solidQuads} SOLID quads (0 = no geometry -> EMPTY). */
    static final class StubCompiler extends SectionCompiler {
        volatile int solidQuads;
        final List<ByteBufferBuilder> builders = java.util.Collections.synchronizedList(new ArrayList<>());

        StubCompiler() {
            super(false, false, null, null, null);
        }

        @Override
        public SectionCompiler.Results compile(
            final SectionPos sectionPos, final RenderSectionRegion region, final VertexSorting vertexSorting, final SectionBufferBuilderPack builders
        ) {
            SectionCompiler.Results results = new SectionCompiler.Results();
            int quads = this.solidQuads;
            if (quads > 0) {
                ByteBufferBuilder bytes = new ByteBufferBuilder(quads * 4 * 32 + 1024);
                this.builders.add(bytes);
                BufferBuilder b = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
                for (int q = 0; q < quads; q++) {
                    for (int v = 0; v < 4; v++) {
                        b.addVertex(q, v, quads, -1, 0.5F, 0.5F, 0, 15728880, 0.0F, 1.0F, 0.0F);
                    }
                }
                results.renderedLayers.put(ChunkSectionLayer.SOLID, b.buildOrThrow());
            }
            return results;
        }

        void freeBuilders() {
            synchronized (this.builders) {
                this.builders.forEach(ByteBufferBuilder::close);
                this.builders.clear();
            }
        }
    }

    /** SOLID quad count of a section's shown mesh: -1 for EMPTY, -2 for UNCOMPILED, -3 for anything else. */
    static int shownQuads(final SectionRenderDispatcher.RenderSection section) {
        SectionMesh mesh = section.getSectionMesh();
        if (mesh == CompiledSectionMesh.EMPTY) {
            return -1;
        }
        if (mesh == CompiledSectionMesh.UNCOMPILED) {
            return -2;
        }
        SectionMesh.SectionDraw draw = mesh.getSectionDraw(ChunkSectionLayer.SOLID);
        return draw == null ? -3 : draw.indexCount() / 6;
    }

    private static Object getField(final Object target, final Class<?> owner, final String name) throws ReflectiveOperationException {
        java.lang.reflect.Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void setField(final Object target, final Class<?> owner, final String name, final Object value) throws ReflectiveOperationException {
        java.lang.reflect.Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * Review finding (E, major): with the budget, an older mesh M1 of a section whose upload is carried over must never
     * replace a newer mesh M2 of the same section that was shown first (urgent compileSync, a later async compile, or an
     * EMPTY result set directly by the worker). Drives the real SectionRenderDispatcher with the real CompileTask code
     * (a stub SectionCompiler), the real budgeted upload passes and checkSectionMesh, over many trials (identity-hash
     * order decides which entries are carried); counts the trials in which M1's upload really completed after M2 was
     * shown, so the check is known to have exercised the overtaking case.
     */
    private static void budgetSectionOrdering() throws Exception {
        FakeGpu gpu = new FakeGpu(fakeInfo());
        java.lang.reflect.Field deviceField = RenderSystem.class.getDeclaredField("DEVICE");
        deviceField.setAccessible(true);
        Object previousDevice = deviceField.get(null);
        deviceField.set(null, gpu.device);
        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        RenderBuffers renderBuffers = (RenderBuffers)((sun.misc.Unsafe)unsafeField.get(null)).allocateInstance(RenderBuffers.class);
        StubCompiler compiler = new StubCompiler();
        int[] pass = new int[1];
        /** Per section: the pass at which its shown mesh last changed, and to what (quads). */
        Map<SectionRenderDispatcher.RenderSection, int[]> shownAt = new IdentityHashMap<>();
        SectionRenderDispatcher dispatcher = new SectionRenderDispatcher(
            new TracingExecutor(java.util.concurrent.Executors.newSingleThreadExecutor()), renderBuffers, compiler,
            section -> {
                synchronized (shownAt) {
                    shownAt.put(section, new int[]{pass[0], shownQuads(section)});
                }
            }
        );
        // Budget mode on this dispatcher (the field is final; the toggle is a JVM property the suite does not set).
        UberGpuBuffer.UploadBudget budget = new UberGpuBuffer.UploadBudget(24L * 1024L, 2);
        setField(dispatcher, SectionRenderDispatcher.class, "uploadBudget", budget);
        Map<?, ?> uber = (Map<?, ?>)getField(dispatcher, SectionRenderDispatcher.class, "chunkUberBuffers");
        /** Pass in which the current trial's M1 (the only 100-quad mesh) got its SOLID vertex allocation, or -1. */
        int[] m1UploadPass = {-1};
        for (Map.Entry<?, ?> e : uber.entrySet()) {
            Object buffers = e.getValue();
            for (String kind : new String[]{"vertexBuffer", "indexBuffer"}) {
                java.lang.reflect.Method accessor = buffers.getClass().getDeclaredMethod(kind);
                accessor.setAccessible(true);
                @SuppressWarnings("unchecked")
                UberGpuBuffer<Object> ub = (UberGpuBuffer<Object>)accessor.invoke(buffers);
                ub.setPregrowForTesting(false, UberGpuBuffer.HeapSource.METAL_RESERVE);
                ub.setUploadBudget(budget);
                if (e.getKey() == ChunkSectionLayer.SOLID && kind.equals("vertexBuffer")) {
                    @SuppressWarnings("unchecked")
                    UberGpuBuffer.AllocationListener<Object> original = (UberGpuBuffer.AllocationListener<Object>)getField(ub, UberGpuBuffer.class, "allocationListener");
                    ub.setAllocationListener((key, buffer, offset) -> {
                        if (buffer != null && key instanceof CompiledSectionMesh mesh) {
                            SectionMesh.SectionDraw draw = mesh.getSectionDraw(ChunkSectionLayer.SOLID);
                            if (draw != null && draw.indexCount() == 600) {
                                m1UploadPass[0] = pass[0];
                            }
                        }
                        original.allocationChanged(key, buffer, offset);
                    });
                }
            }
        }
        java.lang.reflect.Method createCompileTask = SectionRenderDispatcher.RenderSection.class.getDeclaredMethod("createCompileTask", RenderSectionRegion.class);
        createCompileTask.setAccessible(true);
        Runnable frame = () -> {
            pass[0]++;
            dispatcher.lock();
            try {
                dispatcher.uploadTerrainBuffersForFrame(false);
            } finally {
                dispatcher.unlock();
            }
        };
        String[] variants = {"urgent compileSync M2", "async M2", "EMPTY M2"};
        int trials = 150;
        int index = 0;
        int node = 0;
        for (int v = 0; v < variants.length; v++) {
            final int variant = v;
            int wrong = 0;
            int overtaken = 0;
            int leaked = 0;
            for (int trial = 0; trial < trials; trial++) {
                SectionRenderDispatcher.RenderSection section = dispatcher.new RenderSection(index++, SectionPos.asLong(node++, 0, 0));
                m1UploadPass[0] = -1;
                List<SectionRenderDispatcher.RenderSection> fillers = new ArrayList<>();
                List<SectionRenderDispatcher.RenderSection.SectionTask> tasks = new ArrayList<>();
                // Tasks are created on the render thread (as LevelRenderer.compileSections does); M1 = 100 quads.
                for (int f = 0; f < 6; f++) {
                    SectionRenderDispatcher.RenderSection filler = dispatcher.new RenderSection(index++, SectionPos.asLong(node++, 0, 0));
                    fillers.add(filler);
                    tasks.add((SectionRenderDispatcher.RenderSection.SectionTask)createCompileTask.invoke(filler, (Object)null));
                }
                SectionRenderDispatcher.RenderSection.SectionTask m1 = (SectionRenderDispatcher.RenderSection.SectionTask)createCompileTask.invoke(section, (Object)null);
                tasks.add(trial % 7, m1);
                onWorker(() -> {
                    for (SectionRenderDispatcher.RenderSection.SectionTask task : tasks) {
                        compiler.solidQuads = task == m1 ? 100 : 150;
                        task.doTask(null);
                    }
                });
                frame.run();   // the budget uploads part of it, carries the rest (M1 among them in some trials)
                int m2Quads = variant == 2 ? -1 : 120;
                List<SectionRenderDispatcher.RenderSection.SectionTask> more = new ArrayList<>();
                for (int f = 0; f < 3; f++) {
                    SectionRenderDispatcher.RenderSection filler = dispatcher.new RenderSection(index++, SectionPos.asLong(node++, 0, 0));
                    fillers.add(filler);
                    more.add((SectionRenderDispatcher.RenderSection.SectionTask)createCompileTask.invoke(filler, (Object)null));
                }
                if (variant == 0) {
                    onWorker(() -> {
                        for (SectionRenderDispatcher.RenderSection.SectionTask task : more) {
                            compiler.solidQuads = 150;
                            task.doTask(null);
                        }
                    });
                    compiler.solidQuads = 120;
                    section.compileSync(null);   // render thread: staged urgent, uploaded in the next pass
                } else {
                    SectionRenderDispatcher.RenderSection.SectionTask m2 = (SectionRenderDispatcher.RenderSection.SectionTask)createCompileTask.invoke(section, (Object)null);
                    more.add(0, m2);
                    onWorker(() -> {
                        for (SectionRenderDispatcher.RenderSection.SectionTask task : more) {
                            compiler.solidQuads = task == m2 ? (variant == 2 ? 0 : 120) : 150;
                            task.doTask(null);
                        }
                    });
                }
                for (int p = 0; p < 6; p++) {
                    frame.run();
                }
                int shown = shownQuads(section);
                if (shown != m2Quads) {
                    wrong++;
                    if (wrong <= 3) {
                        System.out.println("  " + variants[variant] + " trial " + trial + ": section shows " + shown + " quads, expected " + m2Quads);
                    }
                }
                // Did M1's upload complete after M2 was shown (the overtaking case)?
                int[] last = shownAt.get(section);
                if (m1UploadPass[0] >= 0 && last != null && last[1] == m2Quads && m1UploadPass[0] > last[0]) {
                    overtaken++;
                }
                // Nothing of this trial may stay allocated or staged once everything is released.
                section.reset();
                for (SectionRenderDispatcher.RenderSection filler : fillers) {
                    filler.reset();
                }
                frame.run();
                compiler.freeBuilders();
            }
            int staged = 0;
            for (Object buffers : uber.values()) {
                for (String kind : new String[]{"vertexBuffer", "indexBuffer"}) {
                    java.lang.reflect.Method accessor = buffers.getClass().getDeclaredMethod(kind);
                    accessor.setAccessible(true);
                    UberGpuBuffer<?> ub = (UberGpuBuffer<?>)accessor.invoke(buffers);
                    staged += ub.stagedCount();
                    int[] allocations = new int[1];
                    ub.forEachAllocation((k, a) -> allocations[0]++);
                    leaked += allocations[0];
                }
            }
            System.out.printf(Locale.ROOT, "  budget section ordering, %s: %d trials, M1 upload completed after M2 was shown in %d, wrong mesh shown in %d%n",
                variants[variant], trials, overtaken, wrong);
            checkEq(0, wrong, variants[variant] + ": the newest mesh of the section stays shown");
            check(overtaken > 0, variants[variant] + ": the overtaking case was exercised (" + overtaken + " trials)");
            checkEq(0, staged, variants[variant] + ": nothing left staged");
            checkEq(0, leaked, variants[variant] + ": no allocation left after every section was reset");
        }
        dispatcher.dispose();
        deviceField.set(null, previousDevice);
    }

    // ------------------------------------------------------------------------------------------------------------
    // D. Entity texture prefetch

    /** In-memory resource manager: PNGs (and .mcmeta) under textures/entity plus one elsewhere. */
    static final class FakeResources implements ResourceManager {
        final Map<Identifier, byte[]> files = new LinkedHashMap<>();
        final AtomicInteger opens = new AtomicInteger();
        volatile long openDelayNanos;

        @Override
        public Optional<Resource> getResource(final Identifier location) {
            byte[] bytes = this.files.get(location);
            if (bytes == null) {
                return Optional.empty();
            }
            Identifier meta = Identifier.fromNamespaceAndPath(location.getNamespace(), location.getPath() + ".mcmeta");
            byte[] metaBytes = this.files.get(meta);
            return Optional.of(new Resource((PackResources)null, () -> {
                this.opens.incrementAndGet();
                if (this.openDelayNanos > 0L) {
                    java.util.concurrent.locks.LockSupport.parkNanos(this.openDelayNanos);
                }
                return new ByteArrayInputStream(bytes);
            }, metaBytes == null ? ResourceMetadata.EMPTY_SUPPLIER : () -> ResourceMetadata.fromJsonStream(new ByteArrayInputStream(metaBytes))));
        }

        @Override
        public Set<String> getNamespaces() {
            return Set.of("minecraft");
        }

        @Override
        public List<Resource> getResourceStack(final Identifier location) {
            return this.getResource(location).map(List::of).orElse(List.of());
        }

        @Override
        public Map<Identifier, Resource> listResources(final String directory, final ResourceManager.Selector selector) {
            Map<Identifier, Resource> result = new LinkedHashMap<>();
            for (Identifier id : this.files.keySet()) {
                if (id.getPath().startsWith(directory + "/") && selector.isIncluded(id)) {
                    result.put(id, this.getResource(id).orElseThrow());
                }
            }
            return result;
        }

        @Override
        public Map<Identifier, List<Resource>> listResourceStacks(final String directory, final ResourceManager.Selector selector) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Stream<PackResources> listPacks() {
            return Stream.of();
        }
    }

    static byte[] png(final Random random, final int w, final int h) throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    static boolean sameContents(final TextureContents a, final TextureContents b) {
        NativeImage x = a.image();
        NativeImage y = b.image();
        if (x.getWidth() != y.getWidth() || x.getHeight() != y.getHeight() || x.format() != y.format() || a.blur() != b.blur() || a.clamp() != b.clamp()) {
            return false;
        }
        for (int py = 0; py < x.getHeight(); py++) {
            for (int px = 0; px < x.getWidth(); px++) {
                if (x.getPixel(px, py) != y.getPixel(px, py)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void entityTexturePrefetch() throws Exception {
        Random random = new Random(11);
        FakeResources resources = new FakeResources();
        List<Identifier> entity = new ArrayList<>();
        for (int n = 0; n < 40; n++) {
            Identifier id = Identifier.withDefaultNamespace("textures/entity/mob" + n + "/mob" + n + ".png");
            entity.add(id);
            resources.files.put(id, png(random, 8 << random.nextInt(4), 8 << random.nextInt(4)));
        }
        Identifier withMeta = entity.get(3);
        resources.files.put(Identifier.withDefaultNamespace(withMeta.getPath() + ".mcmeta"),
            "{\"texture\":{\"blur\":true,\"clamp\":true}}".getBytes(StandardCharsets.UTF_8));
        Identifier broken = Identifier.withDefaultNamespace("textures/entity/broken.png");
        resources.files.put(broken, "not a png".getBytes(StandardCharsets.UTF_8));
        Identifier gui = Identifier.withDefaultNamespace("textures/gui/widget.png");
        resources.files.put(gui, png(random, 16, 16));

        EntityTexturePrefetch prefetch = new EntityTexturePrefetch();
        check(prefetch.take(entity.get(0)) == null, "nothing before start");
        prefetch.start(resources);
        check(prefetch.awaitWorker(10000), "prefetch worker finishes");
        checkEq(40, prefetch.size(), "every decodable PNG under textures/entity prefetched (not the broken one, not gui)");
        check(prefetch.take(gui) == null, "textures outside the directories are not prefetched");
        check(prefetch.take(broken) == null, "undecodable PNG left to the synchronous path");
        boolean same = true;
        for (Identifier id : entity) {
            TextureContents pre = prefetch.take(id);
            TextureContents sync = TextureContents.load(resources, id);
            same &= pre != null && sameContents(pre, sync);
            if (pre != null) {
                pre.close();
            }
            sync.close();
        }
        check(same, "prefetched contents equal the synchronous load (pixels, format, blur/clamp metadata)");
        TextureContents meta = TextureContents.load(resources, withMeta);
        check(meta.blur() && meta.clamp(), "metadata case exercised");
        meta.close();
        check(prefetch.take(entity.get(0)) == null, "an entry is handed out once");
        checkEq(0, prefetch.cachedBytes(), "no bytes held after all were taken");
        checkEq(40, prefetch.hits(), "40 hits");

        // Generations: a reload invalidates everything prefetched before.
        prefetch.start(resources);
        check(prefetch.awaitWorker(10000), "second prefetch finishes");
        checkEq(40, prefetch.size(), "refilled");
        prefetch.invalidate();
        checkEq(0, prefetch.size(), "invalidate drains");
        checkEq(0, prefetch.cachedBytes(), "invalidate frees the bytes");
        check(prefetch.take(entity.get(5)) == null, "nothing after invalidate");

        // Invalidate while the worker runs (opens slowed to ~0.2 ms): nothing stale survives, every image is closed.
        resources.openDelayNanos = 200_000L;
        int interrupted = 0;
        for (int round = 0; round < 20; round++) {
            prefetch.start(resources);
            Thread.sleep(1 + random.nextInt(6));
            int seen = prefetch.size();
            prefetch.invalidate();
            check(prefetch.awaitWorker(10000), "worker stops after invalidate");
            if (seen < 40) {
                interrupted++;
            }
            checkEq(0, prefetch.size(), "no stale entry after a racing invalidate");
            checkEq(0, prefetch.cachedBytes(), "no bytes held after a racing invalidate");
            check(prefetch.take(entity.get(round)) == null, "no stale entry handed out after a racing invalidate");
        }
        check(interrupted >= 10, "invalidate interrupted the worker in most rounds (" + interrupted + "/20)");
        resources.openDelayNanos = 0L;
        prefetch.close();
        checkEq(0, prefetch.cachedBytes(), "close frees everything");
        prefetch.start(resources);
        check(prefetch.awaitWorker(1000) && prefetch.size() == 0, "no prefetch after close");
    }
}
