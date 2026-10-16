package com.mojang.renderpearl.backend.metal;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * TASK E pregrow (docs/metal/CONTRACT.md 16.R5-E, {@code -Dmc.terrain.pregrow}, default on since 16.R5-FIN): large Shared
 * {@code MTLBuffer}s created and made GPU-resident on a background thread, so that a new terrain uber-heap
 * ({@code UberGpuBuffer}) never pays that inside a frame.
 *
 * <p>Why: the 16.6 / 21.2 ms max frames of the v-s5c travel runs coincided with a second 128 MiB heap created in-frame
 * (results/TAIL-ANALYSIS.md E14). Measured on M5 / macOS 27 (TravelHitchTest, tools/heap_residency_probe*.m): the create call itself
 * takes ~0.02-0.2 ms, but the first command buffer that references the fresh buffer pays ~3.3-4.4 ms between commit and
 * completion (zero-fill + wiring + GPU mapping of every page); a release costs ~0 ms. A CPU page touch removes ~2/3 of
 * that; a 4-byte fill on a side queue ({@code mcmtl_ext_buffer_make_resident}, native/src/mcmetal_reserve.m) removes all
 * of it without delaying the main queue.
 *
 * <p>What: the game asks ahead of need with {@link #request(int, long)} (render thread, idempotent, allocation-free once
 * a class exists) and later collects the ready buffer with {@link #poll(int, long)} (render thread, non-blocking; null
 * when it is not ready, and the caller then does exactly what it did before). A class is one (size, usage) pair; at
 * most {@link #MAX_CLASSES} classes and at most one ready buffer per class. The worker creates the buffer with
 * {@code mcmtl_buffer_create} (thread-safe, mcmetal.h "Resources") and exactly the resource options
 * {@link MetalBuffer#create} uses, then makes it resident (or, without the extension symbol, writes one byte per 16 KiB
 * page). {@link #poll} wraps it into an ordinary {@link MetalBuffer} (same constructor as a fresh one; contents
 * undefined as the API allows; the residency fill wrote zeros, the content a fresh buffer has).
 *
 * <p>Threading and lifetime: one daemon worker thread, created on the first request. Every native call it makes is
 * bracketed by {@link MetalDevice#beginCompile()} / {@link MetalDevice#endCompile()}, the protocol compile workers use,
 * so {@link MetalDevice#close()} (which sets {@code closed} and waits for those brackets) never destroys the device under
 * it. A slot's backing fields are written by the worker before the volatile {@code state} store of READY and read by the
 * render thread after the volatile load sees READY (happens-before). {@link #close()} runs on the render thread right
 * after that wait: it stops the worker and releases a ready buffer nobody collected, while the native device exists. A
 * failed creation marks the class FAILED and the game keeps its in-frame path for it.
 */
public final class MetalHeapReserve {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Same parse as TravelHitchToggles.PREGROW (the backend does not depend on game classes). */
    static final boolean ENABLED = flag("mc.terrain", true) && flag("mc.terrain.pregrow", true);
    static final int MAX_CLASSES = 4;
    private static final long PAGE_BYTES = 16384L;
    static final int IDLE = 0;
    static final int PENDING = 1;
    static final int READY = 2;
    static final int FAILED = 3;
    private static final int LOGGED_CREATIONS = 8;

    private static volatile @Nullable MetalHeapReserve active;

    private final MetalDevice device;
    private final Slot[] slots = new Slot[MAX_CLASSES];
    private int slotCount;
    private @Nullable ThreadPoolExecutor worker;
    private volatile boolean closed;
    // Statistics.
    private long polled;
    private volatile int creations;
    private volatile int residentByGpu;

    MetalHeapReserve(final MetalDevice device) {
        this.device = device;
    }

    /** MetalDevice constructor: makes {@code device} the active reserve's device when the switch is on. */
    static void install(final MetalDevice device) {
        if (ENABLED) {
            active = new MetalHeapReserve(device);
            LOGGER.info("Metal heap reserve: on (mc.terrain.pregrow)");
        }
    }

    /** MetalDevice.close(), after markClosedAndAwaitCompiles and before the native device goes away. */
    static void uninstall(final MetalDevice device) {
        MetalHeapReserve reserve = active;
        if (reserve != null && reserve.device == device) {
            active = null;
            reserve.close();
        }
    }

    /** Test seam: the active reserve (null when the switch is off or no device is open). */
    static @Nullable MetalHeapReserve active() {
        return active;
    }

    /**
     * Render thread: makes sure a buffer of {@code size} bytes and {@code usage} is being created or is ready. No-op
     * without an active Metal device (Vulkan, OpenGL, headless tests), once the device is closing, when the class
     * failed before, or when {@link #MAX_CLASSES} other classes exist.
     */
    public static void request(final @GpuBuffer.Usage int usage, final long size) {
        MetalHeapReserve reserve = active;
        if (reserve != null) {
            reserve.requestSlot(usage, size);
        }
    }

    /**
     * Render thread: a ready, GPU-resident buffer of {@code size} bytes and {@code usage} (contents undefined), or
     * null. Never waits and never creates a buffer itself.
     */
    public static @Nullable GpuBuffer poll(final @GpuBuffer.Usage int usage, final long size) {
        MetalHeapReserve reserve = active;
        return reserve == null ? null : reserve.pollSlot(usage, size);
    }

    private void requestSlot(final int usage, final long size) {
        if (this.closed || size <= 0L) {
            return;
        }
        Slot slot = this.slot(usage, size, true);
        if (slot == null || slot.state != IDLE) {
            return;
        }
        slot.state = PENDING;
        this.worker().execute(slot);
    }

    private @Nullable GpuBuffer pollSlot(final int usage, final long size) {
        Slot slot = this.slot(usage, size, false);
        if (slot == null || slot.state != READY) {
            return null;
        }
        MetalBuffer buffer = MetalBuffer.wrapReserved(
            this.device, slot.handle, slot.contents, slot.gpuAddress, slot.usage, slot.size, slot.length, slot.options, slot.headlessMemory
        );
        slot.clearBacking();
        slot.state = IDLE;
        this.polled++;
        return buffer;
    }

    private @Nullable Slot slot(final int usage, final long size, final boolean create) {
        for (int i = 0; i < this.slotCount; i++) {
            Slot slot = this.slots[i];
            if (slot.size == size && slot.usage == usage) {
                return slot;
            }
        }
        if (!create || this.slotCount == MAX_CLASSES) {
            return null;
        }
        Slot slot = new Slot(this, usage, size, MetalFormats.bufferResourceOptions(usage, MetalDevice.WRITE_COMBINED));
        this.slots[this.slotCount++] = slot;
        return slot;
    }

    private ThreadPoolExecutor worker() {
        ThreadPoolExecutor executor = this.worker;
        if (executor == null) {
            executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "Metal heap reserve");
                thread.setDaemon(true);
                return thread;
            });
            this.worker = executor;
        }
        return executor;
    }

    /**
     * Render thread, from MetalDevice.close() after every compile bracket ended: no worker task can make a native
     * call any more (beginCompile fails), so stop the worker, then release a ready buffer nobody collected.
     */
    void close() {
        this.closed = true;
        ThreadPoolExecutor executor = this.worker;
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(1L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (int i = 0; i < this.slotCount; i++) {
            Slot slot = this.slots[i];
            if (slot.state == READY) {
                if (slot.headlessMemory != 0L) {
                    MemoryUtil.nmemAlignedFree(slot.headlessMemory);
                } else if (this.device.handle() != 0L) {
                    int status = MtlNative.release(this.device.handle(), slot.handle);
                    if (status != MtlNative.OK) {
                        LOGGER.error("Metal heap reserve: mcmtl_release failed: {}: {}", MtlNative.statusName(status), MtlNative.lastError());
                    }
                }
                slot.clearBacking();
            }
            slot.state = IDLE;
        }
        if (this.creations > 0) {
            LOGGER.info(
                "Metal heap reserve: {} buffers created ahead ({} made resident on the GPU), {} used", this.creations, this.residentByGpu, this.polled
            );
        }
    }

    // Test seams (package-private).
    int state(final int usage, final long size) {
        Slot slot = this.slot(usage, size, false);
        return slot == null ? -1 : slot.state;
    }

    int creations() {
        return this.creations;
    }

    int residentByGpu() {
        return this.residentByGpu;
    }

    /** Test seam: waits until the worker queue is empty and idle (bounded). */
    boolean awaitWorkerIdle(final long timeoutMs) throws InterruptedException {
        ThreadPoolExecutor executor = this.worker;
        if (executor == null) {
            return true;
        }
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (executor.getActiveCount() > 0 || !executor.getQueue().isEmpty()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(1L);
        }
        return true;
    }

    /** One (size, usage) class: at most one buffer being created or ready. */
    private static final class Slot implements Runnable {
        private final MetalHeapReserve reserve;
        final int usage;
        final long size;
        final int options;
        volatile int state;
        // Written by the worker before state = READY; read by the render thread after it saw READY.
        long handle;
        long contents;
        long gpuAddress;
        long length;
        long headlessMemory;

        Slot(final MetalHeapReserve reserve, final int usage, final long size, final int options) {
            this.reserve = reserve;
            this.usage = usage;
            this.size = size;
            this.options = options;
        }

        void clearBacking() {
            this.handle = 0L;
            this.contents = 0L;
            this.gpuAddress = 0L;
            this.length = 0L;
            this.headlessMemory = 0L;
        }

        /** Worker thread. */
        @Override
        public void run() {
            MetalDevice device = this.reserve.device;
            if (!device.beginCompile()) {
                this.state = IDLE;
                return;
            }
            try {
                long start = System.nanoTime();
                boolean gpuResident = false;
                if (device.handle() == 0L) {
                    // Headless device (tests): host memory and a synthetic handle, as MetalBuffer.createHeadless.
                    long allocated = MetalBuffer.alignUp(this.size, 16L);
                    long memory = MemoryUtil.nmemAlignedAlloc(64L, allocated);
                    if (memory == 0L) {
                        throw new OutOfMemoryError("headless reserve of " + this.size + " bytes");
                    }
                    this.handle = MetalBuffer.Headless.nextHandle();
                    this.contents = memory;
                    this.gpuAddress = memory;
                    this.length = allocated;
                    this.headlessMemory = memory;
                } else if (!this.createNative(device)) {
                    this.state = FAILED;
                    return;
                } else {
                    gpuResident = makeResident(device, this.handle);
                }
                if (!gpuResident) {
                    for (long offset = 0L; offset < this.length; offset += PAGE_BYTES) {
                        MemoryUtil.memPutByte(this.contents + offset, (byte)0);
                    }
                } else {
                    this.reserve.residentByGpu++;
                }
                int created = ++this.reserve.creations;
                if (created <= LOGGED_CREATIONS) {
                    LOGGER.info(
                        "Metal heap reserve: {} MiB buffer (usage {}) created and made resident ahead of need in {} ms ({})",
                        this.size >> 20,
                        this.usage,
                        String.format(java.util.Locale.ROOT, "%.2f", (System.nanoTime() - start) / 1e6),
                        gpuResident ? "GPU" : (device.handle() == 0L ? "headless" : "CPU page touch")
                    );
                }
                this.state = READY;
            } catch (Throwable t) {
                LOGGER.warn("Metal heap reserve: creating a {} byte buffer failed; heaps of this size are created in-frame again", this.size, t);
                this.state = FAILED;
            } finally {
                device.endCompile();
            }
        }

        /** mcmtl_buffer_create as MetalBuffer.create does it (same descriptor and options), on the worker thread. */
        private boolean createNative(final MetalDevice device) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long desc = stack.ncalloc(8, 1, (int)MtlNative.BUFFER_DESC_SIZEOF);
                long out = stack.ncalloc(8, 1, (int)MtlNative.BUFFER_RESULT_SIZEOF);
                MemoryUtil.memPutInt(desc + MtlNative.BUFFER_DESC_STRUCT_SIZE, (int)MtlNative.BUFFER_DESC_SIZEOF);
                MemoryUtil.memPutInt(desc + MtlNative.BUFFER_DESC_USAGE, this.usage);
                MemoryUtil.memPutLong(desc + MtlNative.BUFFER_DESC_SIZE, this.size);
                MemoryUtil.memPutInt(desc + MtlNative.BUFFER_DESC_RESOURCE_OPTIONS, this.options);
                if (device.debugLabels()) {
                    MemoryUtil.memPutAddress(desc + MtlNative.BUFFER_DESC_LABEL, MetalBuffer.utf8Label(stack, "Heap reserve " + (this.size >> 20) + " MiB"));
                }
                MemoryUtil.memPutInt(out + MtlNative.BUFFER_RESULT_STRUCT_SIZE, (int)MtlNative.BUFFER_RESULT_SIZEOF);
                int status = MtlNative.bufferCreate(device.handle(), desc, out);
                if (status != MtlNative.OK) {
                    LOGGER.warn(
                        "Metal heap reserve: mcmtl_buffer_create({} bytes) failed: {}: {}; heaps of this size are created in-frame again",
                        this.size,
                        MtlNative.statusName(status),
                        MtlNative.lastError()
                    );
                    return false;
                }
                long handle = MemoryUtil.memGetLong(out + MtlNative.BUFFER_RESULT_HANDLE);
                long contents = MemoryUtil.memGetLong(out + MtlNative.BUFFER_RESULT_CONTENTS);
                if (handle == 0L || contents == 0L) {
                    if (handle != 0L) {
                        MtlNative.release(device.handle(), handle);
                    }
                    LOGGER.warn("Metal heap reserve: mcmtl_buffer_create returned no {}", handle == 0L ? "handle" : "CPU contents");
                    return false;
                }
                this.handle = handle;
                this.contents = contents;
                this.gpuAddress = MemoryUtil.memGetLong(out + MtlNative.BUFFER_RESULT_GPU_ADDRESS);
                this.length = Math.max(MemoryUtil.memGetLong(out + MtlNative.BUFFER_RESULT_LENGTH), this.size);
                this.headlessMemory = 0L;
                return true;
            }
        }
    }

    /**
     * {@code mcmtl_ext_buffer_make_resident} (worker thread, inside the compile bracket): true if the buffer is now
     * GPU-resident; false if the extension symbol is missing or the call failed (the caller then touches the pages).
     */
    private static boolean makeResident(final MetalDevice device, final long handle) {
        MethodHandle mh = Extension.MAKE_RESIDENT;
        if (mh == null) {
            return false;
        }
        try {
            int status = (int)mh.invokeExact(device.handle(), handle);
            if (status != MtlNative.OK) {
                LOGGER.warn("Metal heap reserve: mcmtl_ext_buffer_make_resident failed: {}: {}", MtlNative.statusName(status), MtlNative.lastError());
                return false;
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Metal heap reserve: mcmtl_ext_buffer_make_resident threw", t);
            return false;
        }
    }

    /** Lazily bound optional extension export (not in the frozen mcmetal.h; see native/src/mcmetal_reserve.m). */
    private static final class Extension {
        static final @Nullable MethodHandle MAKE_RESIDENT = bind("mcmtl_ext_buffer_make_resident");

        private static @Nullable MethodHandle bind(final String symbol) {
            try {
                return Linker.nativeLinker()
                    .downcallHandle(MemorySegment.ofAddress(MtlNative.Loader.address(symbol)), FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG));
            } catch (Throwable t) {
                LOGGER.warn("Metal heap reserve: {} not available ({}); reserved buffers are made resident by touching their pages", symbol, t.toString());
                return null;
            }
        }
    }

    private static boolean flag(final String name, final boolean defaultValue) {
        String value = System.getProperty(name);
        return value == null ? defaultValue : !value.equalsIgnoreCase("false") && !value.equals("0");
    }
}
