package com.mojang.renderpearl.backend.metal;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * {@link GpuDeviceBackend} for Metal (spec 2.2, 1.6, 7). OWNER: I3 (java-device).
 *
 * <p>Threading: every method runs on the render thread except {@link #compilePipeline} (compile workers,
 * concurrent) and {@link #getDeviceInfo()} (any thread). The accessors below are the cross-module API
 * used by I4 (encoder, surface) and I5 (pipelines); they are cheap field reads.
 */
public final class MetalDevice implements GpuDeviceBackend {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** {@code -Dmcmetal.strictBindings=true}: throw "Missing uniform" before draws instead of binding dummies (spec 3.8). */
    public static final boolean STRICT_BINDINGS = Boolean.getBoolean("mcmetal.strictBindings");
    /** {@code -Dmcmetal.writeCombined=true}: Shared+WriteCombined for MAP_WRITE-only buffers (spec U14; default off). */
    public static final boolean WRITE_COMBINED = Boolean.getBoolean("mcmetal.writeCombined");
    /** Bound of the wait in {@link #close()} for compile workers still inside {@link #beginCompile()} (spec 1.3, 10.4 #8). */
    public static final long COMPILE_DRAIN_TIMEOUT_NS = 10_000_000_000L;

    // Filled by the I3 constructor (and by forTesting). Immutable after construction except `closed`.
    private final long handle;
    private final MemorySegment statusPage;
    private final MetalTimeline timeline;
    private final MetalTransientMemory transientMemory;
    private final MetalCommandEncoder encoder;
    /** Late frame throttle state (CONTRACT.md 16.R5-A; inert unless -Dmcmetal.lateThrottle). */
    private final MetalFrameThrottle frameThrottle = new MetalFrameThrottle(this);
    private final MetalRenderPipeline.ShaderCache shaderCache;
    private final boolean debugLabels;
    private final int familyMask;
    private final int minTexelBufferAlignment;
    private final DeviceInfo deviceInfo;
    /** Set first thing in close(); read by compile workers (any thread). */
    private volatile boolean closed;
    /** Compile workers between beginCompile() and endCompile() (any thread). */
    private final AtomicInteger activeCompiles = new AtomicInteger();
    // I3-private device state (render thread).
    /** Deduplicated native sampler states (spec 2.2). */
    private final MetalSampler.Cache samplerCache;
    /** Recycled MTLBuffers of closed buffers (spec 2.2 SHOULD). */
    private final MetalBuffer.Pool bufferPool;
    /** featureMask of mcmtl_device_info (MCMTL_FEATURE_*). */
    private final int featureMask;
    /** close() ran (idempotence; {@link #closed} is already set by markClosedAndAwaitCompiles). */
    private boolean shutDown;
    /** Last getTimestampCalibrationOffset() result (returned after close). */
    private long lastCalibrationOffset;

    /**
     * Fully initializes the device (spec 1.6 step 2) with no window: mcmtl_device_create (flags from
     * {@code debugOptions}: useLabels -> DEVICE_FLAG_DEBUG_LABELS, useValidationLayers ->
     * DEVICE_FLAG_VALIDATION), mcmtl_device_get_info, {@link #buildDeviceInfo}, status page,
     * {@link MetalTimeline}, {@link MetalTransientMemory}, the singleton {@link MetalCommandEncoder} and
     * {@link MetalRenderPipeline.ShaderCache}, all stored in the fields above. Throws
     * {@link BackendCreationException} on any failure, after releasing what it created.
     */
    public MetalDevice(final MetalBackend backend, final GpuDebugOptions debugOptions) throws BackendCreationException {
        backend.loadLibrary(); // idempotent; rethrows a cached load failure
        int flags = (debugOptions.useLabels() ? MtlNative.DEVICE_FLAG_DEBUG_LABELS : 0)
            | (debugOptions.useValidationLayers() ? MtlNative.DEVICE_FLAG_VALIDATION : 0);
        if (debugOptions.useValidationLayers()) {
            LOGGER.info("Metal: useValidationLayers enables command-buffer execution-status errors; API validation needs MTL_DEBUG_LAYER=1 at launch");
        }
        NativeDevice created = NativeDevice.create(flags);
        try {
            this.handle = created.handle();
            this.statusPage = MtlNative.statusPage(created.statusPageAddress());
            this.debugLabels = debugOptions.useLabels();
            this.familyMask = created.familyMask();
            this.featureMask = created.featureMask();
            this.minTexelBufferAlignment = created.minTexelBufferAlignment();
            this.deviceInfo = buildDeviceInfo(created.name(), created.driverInfo(), created.maxBufferLength(), created.familyMask(), created.featureMask());
            this.timeline = new MetalTimeline(created.handle(), this.statusPage);
            this.samplerCache = new MetalSampler.Cache(this);
            this.bufferPool = new MetalBuffer.Pool(this);
            this.transientMemory = new MetalTransientMemory(this);
            this.encoder = new MetalCommandEncoder(this);
            this.shaderCache = new MetalRenderPipeline.ShaderCache(this);
            MetalHeapReserve.install(this); // TASK E pregrow; no-op unless -Dmc.terrain.pregrow=true
        } catch (RuntimeException | LinkageError e) {
            created.destroyAfterFailure(e);
            BackendCreationException failure = new BackendCreationException("Metal device initialization failed: " + e, BackendCreationException.Reason.OTHER);
            failure.initCause(e);
            throw failure;
        }
        LOGGER.info(
            "Metal device: {} ({}), families 0x{}, features 0x{}, maxBufferLength {} MiB, debug labels {}",
            created.name(),
            created.driverInfo(),
            Integer.toHexString(created.familyMask()),
            Integer.toHexString(created.featureMask()),
            created.maxBufferLength() >> 20,
            this.debugLabels
        );
        if (MetalFrameThrottle.ENABLED) {
            MetalFrameThrottle.register(this.frameThrottle);
        }
    }

    /**
     * mcmtl_device_create + mcmtl_device_get_info (spec 1.6 step 2, no window needed). Any failure destroys the
     * native device again and becomes a {@link BackendCreationException} (the game then falls back to Vulkan /
     * OpenGL).
     */
    private record NativeDevice(
        long handle, long statusPageAddress, String name, String driverInfo, long maxBufferLength, int familyMask, int featureMask, int minTexelBufferAlignment
    ) {
        static NativeDevice create(final int flags) throws BackendCreationException {
            long handle;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long desc = stack.ncalloc(8, 1, (int)MtlNative.DEVICE_DESC_SIZEOF);
                long outDevice = stack.ncalloc(8, 1, 8);
                MemoryUtil.memPutInt(desc + MtlNative.DEVICE_DESC_STRUCT_SIZE, (int)MtlNative.DEVICE_DESC_SIZEOF);
                MemoryUtil.memPutInt(desc + MtlNative.DEVICE_DESC_FLAGS, flags);
                MemoryUtil.memPutInt(desc + MtlNative.DEVICE_DESC_FRAMES_IN_FLIGHT, MetalTimeline.FRAMES_IN_FLIGHT);
                MemoryUtil.memPutAddress(desc + MtlNative.DEVICE_DESC_LABEL, MemoryUtil.memAddress(stack.UTF8("Minecraft", true)));
                int status = MtlNative.deviceCreate(desc, outDevice);
                if (status != MtlNative.OK) {
                    throw new BackendCreationException(
                        "mcmtl_device_create failed: " + MtlNative.statusName(status) + ": " + MtlNative.lastError(),
                        status == MtlNative.E_NO_DEVICE || status == MtlNative.E_UNSUPPORTED
                            ? BackendCreationException.Reason.PLATFORM_ERROR
                            : BackendCreationException.Reason.OTHER
                    );
                }
                handle = MemoryUtil.memGetLong(outDevice);
            }
            if (handle == 0L) {
                throw new BackendCreationException("mcmtl_device_create returned no device", BackendCreationException.Reason.OTHER);
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long info = stack.ncalloc(8, 1, (int)MtlNative.DEVICE_INFO_SIZEOF);
                MemoryUtil.memPutInt(info + MtlNative.DEVICE_INFO_STRUCT_SIZE, (int)MtlNative.DEVICE_INFO_SIZEOF);
                int status = MtlNative.deviceGetInfo(handle, info);
                if (status != MtlNative.OK) {
                    String message = "mcmtl_device_get_info failed: " + MtlNative.statusName(status) + ": " + MtlNative.lastError();
                    destroyQuietly(handle);
                    throw new BackendCreationException(message, BackendCreationException.Reason.OTHER);
                }
                long statusPage = MemoryUtil.memGetLong(info + MtlNative.DEVICE_INFO_STATUS_PAGE);
                if (statusPage == 0L) {
                    destroyQuietly(handle);
                    throw new BackendCreationException("mcmtl_device_get_info reported no status page", BackendCreationException.Reason.OTHER);
                }
                int minTexel = MemoryUtil.memGetInt(info + MtlNative.DEVICE_INFO_MIN_TEXEL_BUFFER_ALIGNMENT);
                return new NativeDevice(
                    handle,
                    statusPage,
                    MemoryUtil.memUTF8(info + MtlNative.DEVICE_INFO_NAME),
                    MemoryUtil.memUTF8(info + MtlNative.DEVICE_INFO_DRIVER_INFO),
                    MemoryUtil.memGetLong(info + MtlNative.DEVICE_INFO_MAX_BUFFER_LENGTH),
                    MemoryUtil.memGetInt(info + MtlNative.DEVICE_INFO_FAMILY_MASK),
                    MemoryUtil.memGetInt(info + MtlNative.DEVICE_INFO_FEATURE_MASK),
                    minTexel > 0 ? minTexel : 16
                );
            } catch (RuntimeException | LinkageError e) {
                destroyQuietly(handle);
                throw e;
            }
        }

        void destroyAfterFailure(final Throwable cause) {
            try {
                int status = MtlNative.deviceDestroy(this.handle);
                if (status != MtlNative.OK) {
                    LOGGER.warn("mcmtl_device_destroy after a failed initialization: {}: {}", MtlNative.statusName(status), MtlNative.lastError());
                }
            } catch (Throwable t) {
                cause.addSuppressed(t);
            }
        }

        private static void destroyQuietly(final long handle) {
            try {
                MtlNative.deviceDestroy(handle);
            } catch (Throwable t) {
                LOGGER.warn("mcmtl_device_destroy after a failed initialization threw", t);
            }
        }
    }

    private MetalDevice(
        final long handle, final MemorySegment statusPage, final MetalTimeline timeline, final @Nullable MetalTransientMemory transientMemory,
        final boolean debugLabels
    ) {
        this.handle = handle;
        this.statusPage = statusPage;
        this.timeline = timeline;
        this.debugLabels = debugLabels;
        this.familyMask = MtlNative.FAMILY_APPLE7 | MtlNative.FAMILY_APPLE8 | MtlNative.FAMILY_APPLE9 | MtlNative.FAMILY_APPLE10
            | MtlNative.FAMILY_MAC2 | MtlNative.FAMILY_METAL3 | MtlNative.FAMILY_METAL4;
        this.minTexelBufferAlignment = 16;
        this.featureMask = 0;
        this.deviceInfo = buildDeviceInfo("Headless test device", "mcmetal test (no native device)", 1L << 34, this.familyMask, 0);
        this.samplerCache = new MetalSampler.Cache(this);
        this.bufferPool = new MetalBuffer.Pool(this);
        this.transientMemory = transientMemory != null ? transientMemory : new MetalTransientMemory(this);
        this.encoder = new MetalCommandEncoder(this);
        this.shaderCache = new MetalRenderPipeline.ShaderCache(this);
    }

    /**
     * Headless TEST device (CONTRACT.md 11.3): no native device is created; every accessor works.
     * {@code handle} is normally 0 and {@code timeline} a {@link MetalTimeline} in test mode (device handle 0,
     * status page over heap memory, e.g. {@code MemorySegment.ofArray(new long[8])}). A null
     * {@code transientMemory} creates {@code new MetalTransientMemory(this)}. The encoder and shader cache
     * are created with their public constructors. Lets I4 record streams and I5 run
     * {@link MetalRenderPipeline#compile} before I3 is merged. Never used by the game.
     */
    public static MetalDevice forTesting(
        final long handle, final MemorySegment statusPage, final MetalTimeline timeline, final @Nullable MetalTransientMemory transientMemory,
        final boolean debugLabels
    ) {
        return new MetalDevice(handle, statusPage, timeline, transientMemory, debugLabels);
    }

    // =============================================================================================
    // Cross-module API (I4, I5)
    // =============================================================================================

    /** Native device handle ({@code mcmtl_handle} of the MCMTLDevice); 0 for a {@link #forTesting} device. */
    public long handle() {
        return this.handle;
    }

    /** The device's timeline (pending/completed seq, deferred-release FIFO). */
    public MetalTimeline timeline() {
        return this.timeline;
    }

    /** The singleton command encoder created in the constructor (also returned by {@link #createCommandEncoder()}). */
    public MetalCommandEncoder encoder() {
        return this.encoder;
    }

    /** The singleton transient memory (returned by {@code MetalCommandEncoder.transientMemory()}). */
    public MetalTransientMemory transientMemory() {
        return this.transientMemory;
    }

    /** Late frame throttle state (CONTRACT.md 16.R5-A); its guard is a no-op unless -Dmcmetal.lateThrottle. */
    public MetalFrameThrottle frameThrottle() {
        return this.frameThrottle;
    }

    /** Per-device shader/library cache owned by I5 (created in the constructor, closed in {@link #close()}). */
    public MetalRenderPipeline.ShaderCache shaderCache() {
        return this.shaderCache;
    }

    /** The device's {@code mcmtl_status_page} (read with MtlNative.statusLong/statusInt). Never read after close(). */
    public MemorySegment statusPage() {
        return this.statusPage;
    }

    /** {@code GpuDebugOptions.useLabels()}: record labels and debug groups; evaluate label suppliers only when true. */
    public boolean debugLabels() {
        return this.debugLabels;
    }

    /** {@link #STRICT_BINDINGS}. */
    public boolean strictBindings() {
        return STRICT_BINDINGS;
    }

    /** MCMTL_FAMILY_* mask from mcmtl_device_info. */
    public int familyMask() {
        return this.familyMask;
    }

    /** mcmtl_device_info.minTexelBufferAlignment (16 on M5). */
    public int minTexelBufferAlignment() {
        return this.minTexelBufferAlignment;
    }

    /** MCMTL_FEATURE_* mask from mcmtl_device_info (0 for a {@link #forTesting} device). */
    public int featureMask() {
        return this.featureMask;
    }

    /** I3: the device's sampler-state cache. Render thread. */
    MetalSampler.Cache samplerCache() {
        return this.samplerCache;
    }

    /** I3: the device's MTLBuffer recycling pool. Render thread. */
    MetalBuffer.Pool bufferPool() {
        return this.bufferPool;
    }

    /** True once {@link #close()} started; compilePipeline then returns Pending.NULL. Any thread. */
    public boolean isClosed() {
        return this.closed;
    }

    /**
     * Compile workers (any thread): enter a compile that may make native calls with {@link #handle()}.
     * Returns false (and holds nothing) once {@link #close()} started; otherwise the caller MUST call
     * {@link #endCompile()} in a finally block. close() waits for every entered compile before the shader
     * cache and the native device go away (CONTRACT.md 5.7, 7.1).
     */
    public boolean beginCompile() {
        this.activeCompiles.incrementAndGet();
        if (this.closed) {
            this.activeCompiles.decrementAndGet();
            return false;
        }
        return true;
    }

    /** Leaves a compile entered with a successful {@link #beginCompile()}. Any thread. */
    public void endCompile() {
        this.activeCompiles.decrementAndGet();
    }

    /**
     * First step of {@link #close()}: {@code closed = true}, then waits (bounded by {@code timeoutNs}) until no
     * compile worker is between beginCompile() and endCompile(). Returns false on timeout; close() then logs
     * and deliberately leaks the shader cache's native handles and the native device instead of risking a
     * use-after-free in a worker. Render thread.
     */
    boolean markClosedAndAwaitCompiles(final long timeoutNs) {
        this.closed = true;
        long start = System.nanoTime();
        while (this.activeCompiles.get() > 0) {
            if (System.nanoTime() - start >= timeoutNs) {
                LOGGER.error("Metal device close: {} pipeline compile(s) still running after {} ms; leaking the native device",
                    this.activeCompiles.get(), timeoutNs / 1_000_000L);
                return false;
            }
            LockSupport.parkNanos(500_000L);
        }
        return true;
    }

    // =============================================================================================
    // GpuDeviceBackend
    // =============================================================================================

    /** Once, after the window exists: {@code new MetalSurface(this, windowHandle, isIconified)} (I4). */
    @Override
    public GpuSurfaceBackend createSurface(final long windowHandle, final BooleanSupplier isIconified) {
        this.checkOpen();
        return new MetalSurface(this, windowHandle, isIconified);
    }

    /** Called once by the FrontendGpuDevice constructor: returns the singleton {@link #encoder()}. */
    @Override
    public MetalCommandEncoder createCommandEncoder() {
        return this.encoder;
    }

    private void checkOpen() {
        if (this.shutDown) {
            throw new IllegalStateException("Metal device is closed");
        }
    }

    /** {@link MetalSampler#create}; spec 2.2 (dedupe MTLSamplerState by key, distinct MetalSampler per call). */
    @Override
    public GpuSampler createSampler(
        final AddressMode addressModeU,
        final AddressMode addressModeV,
        final FilterMode minFilter,
        final FilterMode magFilter,
        final int maxAnisotropy,
        final OptionalDouble maxLod
    ) {
        this.checkOpen();
        return MetalSampler.create(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    /** {@link MetalTexture#create}; spec 2.2 (IAE for formats without a pixel format and sizes outside 1..16384). */
    @Override
    public GpuTexture createTexture(
        final @Nullable String label,
        final @GpuTexture.Usage int usage,
        final GpuFormat format,
        final int width,
        final int height,
        final int depthOrLayers,
        final int mipLevels
    ) {
        this.checkOpen();
        return MetalTexture.create(this, label, usage, format, width, height, depthOrLayers, mipLevels);
    }

    /** {@link MetalTextureView#create}; IAE if mipLevels < 1. */
    @Override
    public GpuTextureView createTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        this.checkOpen();
        return MetalTextureView.create(this, (MetalTexture)texture, baseMipLevel, mipLevels);
    }

    /** {@link MetalBuffer#create}: Shared buffer, contents undefined. */
    @Override
    public GpuBuffer createBuffer(final @Nullable Supplier<String> label, final @GpuBuffer.Usage int usage, final long size) {
        this.checkOpen();
        if (BUF_LOG > 0L && size >= BUF_LOG) {
            System.out.println("[buflog] " + System.nanoTime() + " " + size + " " + (label != null ? label.get() : "?"));
        }
        return MetalBuffer.create(this, label, usage, size);
    }

    /** Debug: log createBuffer calls of at least this many bytes ({@code mc.bufLog}, 0 = off). */
    private static final long BUF_LOG = Long.getLong("mc.bufLog", 0L);

    /**
     * {@link MetalBuffer#create} + synchronous memcpy from {@code data.position()} (data unchanged); may be called
     * mid-pass: a fresh or recycled backing is idle, so no GPU copy and no encoder interruption is needed.
     */
    @Override
    public GpuBuffer createBuffer(final @Nullable Supplier<String> label, final @GpuBuffer.Usage int usage, final ByteBuffer data) {
        this.checkOpen();
        int size = data.remaining();
        MetalBuffer buffer = MetalBuffer.create(this, label, usage, size);
        MetalTransientMemory.copy(data, buffer.contents(), size);
        return buffer;
    }

    /** mcmtl_device_copy_messages split on '\n' (oldest first); empty for a headless or closed device. Any thread. */
    @Override
    public List<String> getLastDebugMessages() {
        if (this.handle == 0L || this.shutDown) {
            return List.of();
        }
        return this.timeline.copyMessages();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return this.debugLabels();
    }

    /**
     * Worker threads, concurrent (complete, architect): {@link #beginCompile()} -> {@link MetalRenderPipeline#compile}
     * (I5) -> {@link #endCompile()}. Returns Pending.NULL once close() started. Never throws.
     */
    @Override
    public BackendRenderPipeline.Pending compilePipeline(final BackendRenderPipeline.CreateInfo pipelineCreateInfo) {
        if (!this.beginCompile()) {
            return BackendRenderPipeline.Pending.NULL;
        }
        try {
            return MetalRenderPipeline.compile(this, pipelineCreateInfo);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("Metal pipeline {} failed to compile", pipelineCreateInfo.name(), e);
            return BackendRenderPipeline.Pending.NULL;
        } finally {
            this.endCompile();
        }
    }

    /**
     * Spec 1.6 step 7 / CONTRACT.md 5.7: {@link #markClosedAndAwaitCompiles}({@link #COMPILE_DRAIN_TIMEOUT_NS})
     * (false -> log, skip the native releases of the shader cache and mcmtl_device_destroy, leak);
     * encoder().shutdown() (discards unsubmitted work); wait for every committed seq; timeline().drainAll();
     * transient memory, shader cache, sampler cache closed; timeline().close(); mcmtl_device_destroy.
     * Idempotent.
     *
     * <p>Works on a device that never submitted (MetalBackend closes a device whose FrontendGpuDevice failed).
     * Every step runs even if an earlier one threw (open pass, lost device, a stuck GPU: the idle wait is bounded
     * by {@link MetalTimeline#FRAME_WAIT_TIMEOUT_NS}); the first failure is rethrown at the end with the others
     * suppressed. The buffer pool is emptied before the timeline closes, so every handle Java still owns is
     * released while the native device exists; objects the game never closed (e.g. Tracy's query pool) are left
     * to process exit.
     */
    @Override
    public void close() {
        if (this.shutDown) {
            return;
        }
        this.shutDown = true;
        MetalFrameThrottle.unregister(this.frameThrottle);
        net.minecraft.client.renderer.chunk.LodAsyncProducerProbe.stopDevice(this);
        MetalFarPrograms.closeDevice(this);
        boolean compilesDrained = this.markClosedAndAwaitCompiles(COMPILE_DRAIN_TIMEOUT_NS);
        net.minecraft.client.renderer.chunk.LodAsyncProducerProbe.pollAll();
        // Every step runs even if an earlier one failed (a device that never submitted, an encoder left in a
        // pass by unbalanced debug groups, a lost device): the first failure is rethrown at the end.
        Throwable failure = null;
        try {
            // TASK E pregrow: the reserve worker's native calls are compile brackets, all ended now.
            MetalHeapReserve.uninstall(this);
        } catch (Throwable t) {
            failure = t;
        }
        try {
            this.encoder.shutdown();
        } catch (Throwable t) {
            failure = chain(failure, t);
        }
        try {
            this.awaitIdle();
        } catch (Throwable t) {
            failure = chain(failure, t);
        }
        try {
            this.timeline.drainAll();
        } catch (Throwable t) {
            failure = chain(failure, t);
        }
        try {
            this.transientMemory.close();
        } catch (Throwable t) {
            failure = chain(failure, t);
        }
        if (compilesDrained) {
            try {
                this.shaderCache.close();
            } catch (Throwable t) {
                failure = chain(failure, t);
            }
        }
        try {
            this.samplerCache.close();
        } catch (Throwable t) {
            failure = chain(failure, t);
        }
        try {
            this.bufferPool.close();
        } catch (Throwable t) {
            failure = chain(failure, t);
        }
        try {
            // Runs what the steps above deferred (blocks, functions, sampler states) while the device exists.
            this.timeline.close();
        } catch (Throwable t) {
            failure = chain(failure, t);
        }
        if (this.handle != 0L) {
            if (compilesDrained) {
                try {
                    int status = MtlNative.deviceDestroy(this.handle);
                    if (status != MtlNative.OK) {
                        LOGGER.error("mcmtl_device_destroy failed: {}: {}", MtlNative.statusName(status), MtlNative.lastError());
                    }
                } catch (Throwable t) {
                    failure = chain(failure, t);
                }
            } else {
                LOGGER.error("Metal device close: leaking the native device and the shader cache (pipeline compiles still running)");
            }
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("Metal device close failed", failure);
        }
    }

    /** Bounded wait for every committed timeline value (shutdown: never hang the exit on a stuck GPU). */
    private void awaitIdle() {
        long last = this.timeline.lastCommittedSeq();
        if (last <= 0L || this.timeline.isComplete(last)) {
            return;
        }
        if (!this.timeline.await(last, MetalTimeline.FRAME_WAIT_TIMEOUT_NS)) {
            LOGGER.error("Metal device close: GPU did not finish timeline value {} within {} ms", last, MetalTimeline.FRAME_WAIT_TIMEOUT_NS / 1_000_000L);
        }
    }

    private static Throwable chain(final @Nullable Throwable first, final Throwable next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    /** {@link MetalQueryPool#create}. */
    @Override
    public GpuQueryPool createTimestampQueryPool(final int size) {
        this.checkOpen();
        return MetalQueryPool.create(this, size);
    }

    /**
     * {@code System.nanoTime() - gpuNs} from mcmtl_device_sample_timestamps, bracketed by two nanoTime reads (spec
     * 2.2, 2.13: classic counter samples and sampleTimestamps are nanoseconds on Apple GPUs; timestampPeriod 1.0).
     * 0 for a headless device; the last value after close.
     */
    @Override
    public long getTimestampCalibrationOffset() {
        if (this.handle == 0L || this.shutDown) {
            return this.lastCalibrationOffset;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long cpu = stack.ncalloc(8, 1, 8);
            long gpu = stack.ncalloc(8, 1, 8);
            long before = System.nanoTime();
            int status = MtlNative.deviceSampleTimestamps(this.handle, cpu, gpu);
            long after = System.nanoTime();
            MtlNative.check(status, "mcmtl_device_sample_timestamps");
            long gpuNs = MemoryUtil.memGetLong(gpu);
            long hostNs = before + (after - before) / 2L;
            this.lastCalibrationOffset = hostNs - gpuNs;
            return this.lastCalibrationOffset;
        }
    }

    /** The immutable record built in the constructor. Any thread, hot path. */
    @Override
    public DeviceInfo getDeviceInfo() {
        return this.deviceInfo;
    }

    // =============================================================================================
    // DeviceInfo (spec section 7) -- complete
    // =============================================================================================

    /**
     * Builds the {@link DeviceInfo} of spec section 7 from mcmtl_device_info values. Pure function.
     *
     * @param name        mcmtl_device_info.name, e.g. "Apple M5"
     * @param driverInfo  mcmtl_device_info.driverInfo
     * @param maxBufferLength mcmtl_device_info.maxBufferLength
     * @param familyMask  MCMTL_FAMILY_* bits
     * @param featureMask MCMTL_FEATURE_* bits
     */
    public static DeviceInfo buildDeviceInfo(String name, String driverInfo, long maxBufferLength, int familyMask, int featureMask) {
        Set<String> extensions = new LinkedHashSet<>();
        if ((familyMask & MtlNative.FAMILY_APPLE7) != 0) {
            extensions.add("MTLGPUFamilyApple7");
        }
        if ((familyMask & MtlNative.FAMILY_APPLE8) != 0) {
            extensions.add("MTLGPUFamilyApple8");
        }
        if ((familyMask & MtlNative.FAMILY_APPLE9) != 0) {
            extensions.add("MTLGPUFamilyApple9");
        }
        if ((familyMask & MtlNative.FAMILY_APPLE10) != 0) {
            extensions.add("MTLGPUFamilyApple10");
        }
        if ((familyMask & MtlNative.FAMILY_MAC2) != 0) {
            extensions.add("MTLGPUFamilyMac2");
        }
        if ((familyMask & MtlNative.FAMILY_METAL3) != 0) {
            extensions.add("MTLGPUFamilyMetal3");
        }
        if ((familyMask & MtlNative.FAMILY_METAL4) != 0) {
            extensions.add("MTLGPUFamilyMetal4");
        }
        if ((featureMask & MtlNative.FEATURE_MTL4_DECODER) != 0) {
            extensions.add("MTL4");
        }
        if ((featureMask & MtlNative.FEATURE_METALFX_SPATIAL) != 0) {
            extensions.add("MetalFX");
        }
        return new DeviceInfo(
            name,
            "Apple",
            driverInfo,
            true,
            "Metal",
            1.0F,
            new DeviceLimits(16, 16, 16384, maxBufferLength, 0, 8, Integer.MAX_VALUE),
            new DeviceFeatures(true, true, false, false, true, true, true, true),
            Collections.unmodifiableSet(extensions),
            new HintsAndWorkarounds(true, false, true, false),
            DeviceType.INTEGRATED
        );
    }
}
