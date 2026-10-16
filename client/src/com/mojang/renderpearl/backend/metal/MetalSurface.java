package com.mojang.renderpearl.backend.metal;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLMetal;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * {@link GpuSurfaceBackend} over the SDL Metal view's {@code CAMetalLayer} (spec 2.6, 6; CONTRACT.md 8).
 * OWNER: I4 (java-encoder). Created once by {@code MetalDevice.createSurface}; render (= AppKit main) thread.
 *
 * <p>Constructor: {@code SDL_SetHint(SDL_HINT_VIDEO_METAL_AUTO_RESIZE_DRAWABLE, "0")}, {@code view =
 * SDL_Metal_CreateView(window)} (0 -> IllegalStateException(SDL_GetError())), {@code layer =
 * SDL_Metal_GetLayer(view)}, {@code mcmtl_surface_create(device, layer)}.
 * <ul>
 *   <li>{@code configure}: 1..16384 else SurfaceException; mcmtl_surface_configure (DISPLAY_SYNC iff FIFO,
 *       maximumDrawableCount 3); clears suboptimal and the eaten exception. No GPU wait.</li>
 *   <li>{@code isSuboptimal}: edge-triggered (nil drawable or drawable size != configured), cleared by configure.</li>
 *   <li>{@code acquireNextTexture}: rethrow the eaten exception; iconified -> SurfaceException("Cannot acquire
 *       minimized window"); {@code SDL_GetWindowFlags & SDL_WINDOW_OCCLUDED} -> drop this frame silently. Never
 *       calls nextDrawable (it happens during decode of PRESENT).</li>
 *   <li>{@code blitFromTexture}: unless dropped, {@code encoder.recordPresent(this, view)}; cannot throw
 *       SurfaceException.</li>
 *   <li>{@code present}: mcmtl_surface_present; must never throw.</li>
 *   <li>Vsync off presents without blocking by default ("mailbox", CONTRACT.md 16.4, {@code MCMTL_MAILBOX}): a frame
 *       whose submit reports neither PRESENT_ENCODED nor DRAWABLE_NIL was rendered and not presented. Counted in
 *       {@link #presentStats()} with presented / nil / occluded frames.</li>
 *   <li>{@code close}: wait for {@code timeline.lastCommittedSeq()}, mcmtl_surface_destroy,
 *       SDL_Metal_DestroyView(view) -- before the window is destroyed.</li>
 * </ul>
 *
 * <p>Every platform call goes through {@link Natives}, so tests can drive the whole state machine without a
 * window or a native device ({@link #forTesting}).
 */
public final class MetalSurface implements GpuSurfaceBackend {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Spec 6.4: FIFO (vsync) and IMMEDIATE only. */
    public static final Set<GpuSurface.PresentMode> SUPPORTED_PRESENT_MODES = Collections.unmodifiableSet(
        EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE)
    );
    /** Largest drawable edge (Apple-family 2D texture limit). */
    public static final int MAX_DRAWABLE_SIZE = 16384;
    /** {@code CAMetalLayer.maximumDrawableCount} (2 or 3 are legal; spec 6.2). */
    public static final int MAXIMUM_DRAWABLE_COUNT = 3;
    /**
     * Drawable count with vsync off in a window (fullscreen uses 3, see {@link #drawableCountFor}). Measured on M5 / macOS 27,
     * fullscreen 3600x2260: with 3 drawables and displaySyncEnabled=NO, nextDrawable still hands drawables back at display
     * refresh (120 FPS cap); with 2 the cap disappears (120 -> 312 FPS on the same scene). With the default mailbox
     * presentation (CONTRACT.md 16.4) the render thread never calls nextDrawable and the native side configures
     * {@code MCMTL_MAILBOX_DRAWABLES} (default 3) instead.
     */
    public static final int IMMEDIATE_DRAWABLE_COUNT = 2;

    /**
     * Vsync on: 3. Vsync off: 3 in fullscreen (the game uses a non-Spaces fullscreen window on macOS, see
     * RenderSystem.initBackendSystem; measured 624 vs 565 FPS median), 2 in a window (a composited window with 3
     * drawables is paced at display refresh).
     */
    private int drawableCountFor(final GpuSurface.PresentMode mode) {
        if (mode == GpuSurface.PresentMode.FIFO) {
            return MAXIMUM_DRAWABLE_COUNT;
        }
        boolean fullscreen = (this.natives.windowFlags(this.windowHandle) & SDLVideo.SDL_WINDOW_FULLSCREEN) != 0L;
        return fullscreen ? MAXIMUM_DRAWABLE_COUNT : IMMEDIATE_DRAWABLE_COUNT;
    }

    /** Indices of {@link #presentStats()}. */
    public static final int STAT_FRAMES = 0;
    /** Frames whose PRESENT registered a drawable present (fused or present pass). */
    public static final int STAT_PRESENTED = 1;
    /** Frames rendered but not presented (mailbox: no drawable free in time, CONTRACT.md 16.4). */
    public static final int STAT_NOT_PRESENTED = 2;
    /** Frames whose blocking nextDrawable returned nil (DRAWABLE_NIL). */
    public static final int STAT_NIL = 3;
    /** Frames dropped before recording a PRESENT (fully occluded window). */
    public static final int STAT_OCCLUDED = 4;
    private static final int STAT_COUNT = 5;
    /**
     * Cumulative presentation counters of every Metal surface (render thread only). {@code STAT_FRAMES} counts the frames
     * whose PRESENT reached the native side; each also counts in exactly one of PRESENTED, NOT_PRESENTED or NIL.
     */
    private static final long[] PRESENT_STATS = new long[STAT_COUNT];

    /** A copy of the presentation counters (index with {@code STAT_*}); read by the bench harness through reflection. */
    public static long[] presentStats() {
        return PRESENT_STATS.clone();
    }

    /** Zeroes the presentation counters (bench harness, start of the measurement). */
    public static void resetPresentStats() {
        java.util.Arrays.fill(PRESENT_STATS, 0L);
    }

    /** Platform calls of the surface (SDL + libmcmetal), replaceable in tests. */
    interface Natives {
        /** SDL_SetHint(METAL_AUTO_RESIZE_DRAWABLE, "0") then SDL_Metal_CreateView(window); 0 on failure. */
        long createView(long window);

        long getLayer(long view);

        void destroyView(long view);

        String lastSdlError();

        long windowFlags(long window);

        int surfaceCreate(long device, long layer, long outSurface);

        int surfaceConfigure(long surface, long config);

        int surfacePresent(long surface);

        int surfaceDestroy(long surface);
    }

    /** The real SDL / libmcmetal calls. */
    static final Natives SYSTEM = new Natives() {
        @Override
        public long createView(final long window) {
            // The backend owns drawableSize (deterministic sizes, render scale); must precede view creation.
            SDLHints.SDL_SetHint(SDLHints.SDL_HINT_VIDEO_METAL_AUTO_RESIZE_DRAWABLE, "0");
            return SDLMetal.SDL_Metal_CreateView(window);
        }

        @Override
        public long getLayer(final long view) {
            return SDLMetal.SDL_Metal_GetLayer(view);
        }

        @Override
        public void destroyView(final long view) {
            SDLMetal.SDL_Metal_DestroyView(view);
        }

        @Override
        public String lastSdlError() {
            String error = SDLError.SDL_GetError();
            return error == null ? "" : error;
        }

        @Override
        public long windowFlags(final long window) {
            return SDLVideo.SDL_GetWindowFlags(window);
        }

        @Override
        public int surfaceCreate(final long device, final long layer, final long outSurface) {
            return MtlNative.surfaceCreate(device, layer, outSurface);
        }

        @Override
        public int surfaceConfigure(final long surface, final long config) {
            return MtlNative.surfaceConfigure(surface, config);
        }

        @Override
        public int surfacePresent(final long surface) {
            return MtlNative.surfacePresent(surface);
        }

        @Override
        public int surfaceDestroy(final long surface) {
            return MtlNative.surfaceDestroy(surface);
        }
    };

    private final MetalDevice device;
    private final long windowHandle;
    private final BooleanSupplier isIconified;
    private final Natives natives;
    private final long viewHandle;
    private final long handle;
    private boolean suboptimal;
    private @Nullable SurfaceException eatenException;
    private boolean dropFrame;
    private boolean closed;
    private int configuredWidth;
    private int configuredHeight;
    private boolean loggedPresentFailure;

    public MetalSurface(final MetalDevice device, final long windowHandle, final BooleanSupplier isIconified) {
        this(device, windowHandle, isIconified, SYSTEM);
    }

    MetalSurface(final MetalDevice device, final long windowHandle, final BooleanSupplier isIconified, final Natives natives) {
        this.device = device;
        this.windowHandle = windowHandle;
        this.isIconified = isIconified;
        this.natives = natives;
        long view = natives.createView(windowHandle);
        if (view == 0L) {
            throw new IllegalStateException("SDL_Metal_CreateView failed: " + natives.lastSdlError());
        }
        long surface = 0L;
        try {
            long layer = natives.getLayer(view);
            if (layer == 0L) {
                throw new IllegalStateException("SDL_Metal_GetLayer returned no CAMetalLayer: " + natives.lastSdlError());
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long out = stack.ncalloc(8, 1, 8);
                int status = natives.surfaceCreate(device.handle(), layer, out);
                MtlNative.check(status, "mcmtl_surface_create");
                surface = MemoryUtil.memGetLong(out);
            }
            if (surface == 0L) {
                throw new IllegalStateException("mcmtl_surface_create returned a null surface");
            }
        } catch (RuntimeException | Error e) {
            try {
                natives.destroyView(view);
            } catch (RuntimeException | Error suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        this.viewHandle = view;
        this.handle = surface;
    }

    /** A surface over fake platform calls (tests); {@code natives.createView/surfaceCreate} provide the handles. */
    static MetalSurface forTesting(final MetalDevice device, final long windowHandle, final BooleanSupplier isIconified, final Natives natives) {
        return new MetalSurface(device, windowHandle, isIconified, natives);
    }

    public MetalDevice device() {
        return this.device;
    }

    public long windowHandle() {
        return this.windowHandle;
    }

    /** Native MCMTLSurface handle written into PRESENT records. */
    public long handle() {
        return this.handle;
    }

    /** SDL_MetalView handle. */
    long viewHandle() {
        return this.viewHandle;
    }

    /** True while the current frame is dropped (occluded window): blitFromTexture records nothing. */
    boolean isFrameDropped() {
        return this.dropFrame;
    }

    /**
     * I4 (MetalCommandEncoder, after any mcmtl_submit whose stream contained this surface's PRESENT):
     * DRAWABLE_NIL -> suboptimal + eaten SurfaceException("nextDrawable returned nil"); DRAWABLE_RESIZED ->
     * suboptimal. Neither PRESENT_ENCODED nor DRAWABLE_NIL: the frame was rendered and not presented (mailbox, no
     * drawable free; nothing is wrong with the surface).
     */
    void onPresentResult(final int submitResultFlags, final int drawableWidth, final int drawableHeight) {
        PRESENT_STATS[STAT_FRAMES]++;
        if ((submitResultFlags & MtlNative.SUBMIT_RESULT_FLAG_PRESENT_ENCODED) != 0) {
            PRESENT_STATS[STAT_PRESENTED]++;
        } else if ((submitResultFlags & MtlNative.SUBMIT_RESULT_FLAG_DRAWABLE_NIL) != 0) {
            PRESENT_STATS[STAT_NIL]++;
        } else {
            PRESENT_STATS[STAT_NOT_PRESENTED]++;
        }
        if ((submitResultFlags & MtlNative.SUBMIT_RESULT_FLAG_DRAWABLE_NIL) != 0) {
            this.suboptimal = true;
            if (this.eatenException == null) {
                this.eatenException = new SurfaceException("nextDrawable returned nil");
            }
        }
        if ((submitResultFlags & MtlNative.SUBMIT_RESULT_FLAG_DRAWABLE_RESIZED) != 0) {
            this.suboptimal = true;
        }
    }

    @Override
    public void configure(final GpuSurface.Configuration config) throws SurfaceException {
        int width = config.width();
        int height = config.height();
        if (width < 1 || height < 1 || width > MAX_DRAWABLE_SIZE || height > MAX_DRAWABLE_SIZE) {
            this.suboptimal = true;
            throw new SurfaceException(
                "Requested drawable size (" + width + " x " + height + ") not within allowed extent: min(1 x 1) max(" + MAX_DRAWABLE_SIZE + " x " + MAX_DRAWABLE_SIZE + ")"
            );
        }
        if (this.closed) {
            throw new IllegalStateException("Surface is closed");
        }
        int status;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long cfg = stack.ncalloc(8, 1, (int)MtlNative.SURFACE_CONFIG_SIZEOF);
            MemoryUtil.memPutInt(cfg + MtlNative.SURFACE_CONFIG_STRUCT_SIZE, (int)MtlNative.SURFACE_CONFIG_SIZEOF);
            MemoryUtil.memPutInt(cfg + MtlNative.SURFACE_CONFIG_WIDTH, width);
            MemoryUtil.memPutInt(cfg + MtlNative.SURFACE_CONFIG_HEIGHT, height);
            MemoryUtil.memPutInt(
                cfg + MtlNative.SURFACE_CONFIG_FLAGS, config.presentMode() == GpuSurface.PresentMode.FIFO ? MtlNative.SURFACE_FLAG_DISPLAY_SYNC : 0
            );
            MemoryUtil.memPutInt(cfg + MtlNative.SURFACE_CONFIG_MAXIMUM_DRAWABLE_COUNT, this.drawableCountFor(config.presentMode()));
            status = this.natives.surfaceConfigure(this.handle, cfg);
            if (status != MtlNative.OK) {
                // Read the native message on this thread now; the game logs it and retries next frame.
                RuntimeException cause = MtlNative.exceptionFor(status, "mcmtl_surface_configure(" + width + "x" + height + ")");
                this.suboptimal = true;
                throw new SurfaceException(cause);
            }
        }
        this.configuredWidth = width;
        this.configuredHeight = height;
        LOGGER.info("Metal surface configured: {}x{} {}", width, height, config.presentMode());
        this.suboptimal = false;
        this.eatenException = null;
    }

    @Override
    public boolean isSuboptimal() {
        return this.suboptimal;
    }

    @Override
    public void acquireNextTexture() throws SurfaceException {
        SurfaceException eaten = this.eatenException;
        if (eaten != null) {
            this.eatenException = null;
            throw new SurfaceException(eaten);
        }
        if (this.isIconified.getAsBoolean()) {
            throw new SurfaceException("Cannot acquire minimized window");
        }
        // Fully hidden window: render as usual but present nothing (never an exception: the game would warn and
        // reconfigure every frame). The drawable is acquired late, while PRESENT is decoded.
        this.dropFrame = (this.natives.windowFlags(this.windowHandle) & SDLVideo.SDL_WINDOW_OCCLUDED) != 0L;
        if (this.dropFrame) {
            PRESENT_STATS[STAT_OCCLUDED]++;
        }
    }

    @Override
    public void blitFromTexture(final CommandEncoderBackend commandEncoder, final GpuTextureView textureView) {
        if (this.dropFrame || this.closed) {
            return;
        }
        ((MetalCommandEncoder)commandEncoder).recordPresent(this, (MetalTextureView)textureView);
    }

    @Override
    public void present() {
        this.dropFrame = false;
        if (this.closed) {
            return;
        }
        try {
            int status = this.natives.surfacePresent(this.handle);
            if (status != MtlNative.OK && !this.loggedPresentFailure) {
                this.loggedPresentFailure = true;
                LOGGER.warn("mcmtl_surface_present failed: {} {} (logged once)", MtlNative.statusName(status), MtlNative.lastError());
            }
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            // present() must never throw: the frontend would stay "acquired" forever (spec 6.8).
            if (!this.loggedPresentFailure) {
                this.loggedPresentFailure = true;
                LOGGER.warn("mcmtl_surface_present failed (logged once)", t);
            }
        }
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return SUPPORTED_PRESENT_MODES;
    }

    /** Size given to the last successful {@link #configure} (0 before). */
    int configuredWidth() {
        return this.configuredWidth;
    }

    int configuredHeight() {
        return this.configuredHeight;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        try {
            // A PRESENT recorded but never committed must not reach the decoder after the surface is gone.
            this.device.encoder().cancelPresent(this);
            MetalTimeline timeline = this.device.timeline();
            long last = timeline.lastCommittedSeq();
            if (last > 0L && !timeline.await(last, MetalTimeline.FRAME_WAIT_TIMEOUT_NS)) {
                LOGGER.warn("Metal surface close: frame {} did not complete within 5 s; destroying the surface anyway", last);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Metal surface close: waiting for the last frame failed", e);
        } finally {
            try {
                int status = this.natives.surfaceDestroy(this.handle);
                if (status != MtlNative.OK) {
                    LOGGER.warn("mcmtl_surface_destroy failed: {} {}", MtlNative.statusName(status), MtlNative.lastError());
                }
            } catch (RuntimeException e) {
                LOGGER.warn("mcmtl_surface_destroy failed", e);
            } finally {
                this.natives.destroyView(this.viewHandle);
            }
        }
    }
}
