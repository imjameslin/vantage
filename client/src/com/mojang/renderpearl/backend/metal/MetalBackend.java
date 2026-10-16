package com.mojang.renderpearl.backend.metal;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.slf4j.Logger;

/**
 * {@link GpuBackend} for Metal (spec 2.1). OWNER: I3 (java-device).
 *
 * <p>{@link #loadLibrary()} is complete (architect, part of the load-path proof): platform check,
 * {@link MtlNative.Loader#load()}, {@link MtlNative#verifyAbi()}, {@code mcmtl_probe} and the GPU-family /
 * OS checks. Failures are cached and rethrown as {@link BackendCreationException} so the game falls back
 * to the next backend. {@link #createDevice(GpuDebugOptions)} wraps {@link MetalDevice}; any runtime
 * failure there (native device creation, a missing export, an unimplemented native stub) also becomes a
 * {@link BackendCreationException}, after the half-built device was closed.
 */
public final class MetalBackend implements GpuBackend {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Classic (M1) decoder requirement. */
    public static final int REQUIRED_FAMILY = MtlNative.FAMILY_APPLE7;
    /** -mmacosx-version-min of libmcmetal. */
    public static final int REQUIRED_MACOS_MAJOR = 26;

    private boolean libraryLoaded;
    private @Nullable BackendCreationException libraryLoadFailure;
    private @Nullable ProbeInfo probeInfo;

    /** Result of {@code mcmtl_probe} plus load details (logging, crash reports, MetalSmoke). */
    public record ProbeInfo(
        String deviceName,
        int familyMask,
        int osMajor,
        int osMinor,
        int osPatch,
        boolean unifiedMemory,
        long maxBufferLength,
        long recommendedMaxWorkingSetSize,
        int abiVersion,
        @Nullable String libraryPath
    ) {
    }

    @Override
    public String getName() {
        return "Metal";
    }

    @Override
    public void loadLibrary() throws BackendCreationException {
        if (this.libraryLoaded) {
            return;
        }
        if (this.libraryLoadFailure != null) {
            throw this.libraryLoadFailure;
        }
        try {
            if (Platform.get() != Platform.MACOSX || Platform.getArchitecture() != Platform.Architecture.ARM64) {
                throw new BackendCreationException("Metal backend requires macOS on Apple silicon", BackendCreationException.Reason.PLATFORM_ERROR);
            }
            MtlNative.Loader.load();
            List<String> problems = MtlNative.verifyAbi();
            if (!problems.isEmpty()) {
                throw new BackendCreationException("libmcmetal ABI mismatch: " + String.join("; ", problems), BackendCreationException.Reason.OTHER);
            }
            ProbeInfo probe = probe();
            if ((probe.familyMask() & REQUIRED_FAMILY) == 0) {
                throw new BackendCreationException(
                    "GPU '" + probe.deviceName() + "' does not support MTLGPUFamilyApple7", BackendCreationException.Reason.PLATFORM_ERROR
                );
            }
            if (probe.osMajor() < REQUIRED_MACOS_MAJOR) {
                throw new BackendCreationException(
                    "Metal backend requires macOS " + REQUIRED_MACOS_MAJOR + " or newer (running " + probe.osMajor() + "." + probe.osMinor() + ")",
                    BackendCreationException.Reason.PLATFORM_ERROR
                );
            }
            this.probeInfo = probe;
            this.libraryLoaded = true;
            LOGGER.info(
                "Loaded libmcmetal (ABI {}) from {}: {} (families 0x{}), macOS {}.{}.{}",
                probe.abiVersion(),
                probe.libraryPath(),
                probe.deviceName(),
                Integer.toHexString(probe.familyMask()),
                probe.osMajor(),
                probe.osMinor(),
                probe.osPatch()
            );
        } catch (BackendCreationException e) {
            this.libraryLoadFailure = e;
            throw e;
        } catch (Throwable t) {
            // UnsatisfiedLinkError (missing dylib/symbol), ExceptionInInitializerError (MtlNative.Downcalls binding),
            // IllegalStateException from the FFM layer, ...
            this.libraryLoadFailure = new BackendCreationException("Failed to load the Metal backend library: " + t, BackendCreationException.Reason.PLATFORM_ERROR);
            this.libraryLoadFailure.initCause(t);
            throw this.libraryLoadFailure;
        }
    }

    private static ProbeInfo probe() throws BackendCreationException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long info = stack.ncalloc(8, 1, (int)MtlNative.PROBE_INFO_SIZEOF);
            MemoryUtil.memPutInt(info + MtlNative.PROBE_INFO_STRUCT_SIZE, (int)MtlNative.PROBE_INFO_SIZEOF);
            int status = MtlNative.probe(info);
            if (status != MtlNative.OK) {
                throw new BackendCreationException(
                    "Metal device probe failed: " + MtlNative.statusName(status) + ": " + MtlNative.lastError(), BackendCreationException.Reason.PLATFORM_ERROR
                );
            }
            return new ProbeInfo(
                MemoryUtil.memUTF8(info + MtlNative.PROBE_INFO_DEVICE_NAME),
                MemoryUtil.memGetInt(info + MtlNative.PROBE_INFO_FAMILY_MASK),
                MemoryUtil.memGetInt(info + MtlNative.PROBE_INFO_OS_MAJOR),
                MemoryUtil.memGetInt(info + MtlNative.PROBE_INFO_OS_MINOR),
                MemoryUtil.memGetInt(info + MtlNative.PROBE_INFO_OS_PATCH),
                MemoryUtil.memGetInt(info + MtlNative.PROBE_INFO_HAS_UNIFIED_MEMORY) != 0,
                MemoryUtil.memGetLong(info + MtlNative.PROBE_INFO_MAX_BUFFER_LENGTH),
                MemoryUtil.memGetLong(info + MtlNative.PROBE_INFO_RECOMMENDED_MAX_WORKING_SET_SIZE),
                MtlNative.abiVersion(),
                MtlNative.Loader.path()
            );
        }
    }

    /** Probe result of the last successful {@link #loadLibrary()}, or null. */
    public @Nullable ProbeInfo probeInfo() {
        return this.probeInfo;
    }

    /** No-op: the dylib stays mapped for the life of the process (spec 2.1). */
    @Override
    public void unloadLibrary() {
    }

    /** {@code flags} is always 8224 (RESIZABLE | HIGH_PIXEL_DENSITY); adds SDL_WINDOW_METAL. Returns 0 on failure. */
    @Override
    public long createWindow(final @Nullable String title, final int width, final int height, final long flags) {
        return SDLVideo.SDL_CreateWindow(title, width, height, flags | SDLVideo.SDL_WINDOW_METAL);
    }

    /**
     * {@code new FrontendGpuDevice(new MetalDevice(...))}. If the {@link FrontendGpuDevice} constructor fails
     * after the {@link MetalDevice} was created (PipelineBuilder/shaderc init, Tracy query pool, ...), the
     * MetalDevice is closed before the failure is reported, so a fallback backend never shares the process
     * with a live Metal device (CONTRACT.md 9.1).
     */
    @Override
    public GpuDevice createDevice(final GpuDebugOptions debugOptions) throws BackendCreationException {
        this.loadLibrary();
        MetalDevice device;
        try {
            device = new MetalDevice(this, debugOptions);
        } catch (BackendCreationException e) {
            throw e;
        } catch (RuntimeException | LinkageError e) {
            throw creationFailure(e);
        }
        try {
            return new FrontendGpuDevice(device);
        } catch (RuntimeException | LinkageError e) {
            try {
                device.close();
            } catch (Throwable t) {
                e.addSuppressed(t);
            }
            throw creationFailure(e);
        }
    }

    private static BackendCreationException creationFailure(final Throwable cause) {
        BackendCreationException failure = new BackendCreationException("Metal device creation failed: " + cause, BackendCreationException.Reason.OTHER);
        failure.initCause(cause);
        return failure;
    }
}
