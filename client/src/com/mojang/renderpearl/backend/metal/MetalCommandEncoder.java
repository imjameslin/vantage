package com.mojang.renderpearl.backend.metal;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * {@link CommandEncoderBackend}: records every call into the off-heap command stream (spec 2.3, 1.7;
 * CONTRACT.md 4 and 9.4). OWNER: I4 (java-encoder). One instance per device, created by the
 * {@link MetalDevice} constructor; render thread only.
 *
 * <p>Holds two {@link StreamWriter}s: the main stream and the init stream (INIT_TEXTURE records only, decoded first
 * at the next commit). {@link #submit()} performs one {@code mcmtl_submit} with COMMIT | FRAME_END, then
 * throttles to 2 frames in flight, drains the timeline and rotates transient memory. The only other native
 * call it makes is the flush-on-wait commit ({@link #flushForWait(long)}).
 *
 * <p>Recording rules (CONTRACT.md 4.4, 9.4): every record is written in place with {@code memPut*} at the
 * {@link StreamOps} offsets of a zero-filled reservation; buffers are referenced by {@code handle()} with
 * {@code backingOffset()} added to every offset, read at record time; every recorded buffer reference calls
 * {@code markUsed(pendingSeq)}. Validation the frontend skips (spec 1.2, 2.3) happens before anything is
 * recorded, so a rejected call leaves the stream untouched.
 */
public final class MetalCommandEncoder implements CommandEncoderBackend {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Initial capacity of the init stream (a handful of INIT_TEXTURE records per frame at most). */
    static final int INIT_STREAM_CAPACITY = 4 * 1024;
    /** Effective alignment of every transient allocation the encoder makes (buffer/texture copies). */
    static final long STAGING_ALIGNMENT = 16L;
    private static final int SUBMIT_SCRATCH_BYTES = (int)(MtlNative.SUBMIT_INFO_SIZEOF + MtlNative.SUBMIT_RESULT_SIZEOF);
    /**
     * {@code -Dmcmetal.renameSmallWrites} (CONTRACT.md 16.R5-C, default ON since round 5, 16.R5-FIN; {@code =false} is the
     * off switch): a writeToBuffer into a buffer of at most 64 KiB that recorded or in-flight work still reads moves the
     * buffer to an idle backing ({@link MetalBuffer#renameForWrite}) and memcpys, instead of staging + COPY_BUFFER. Read on
     * every call (tests flip it). Measured only together with the late throttle (results/ROUND5-AB.md 2.5).
     */
    static boolean RENAME_SMALL_WRITES = MetalFrameThrottle.defaultOn("mcmetal.renameSmallWrites");
    /**
     * {@code -Dmcmetal.diag.forceFastWrite=true}: DIAGNOSTICS ONLY, UNSAFE. writeToBuffer always memcpys into the current
     * backing, even while the GPU may read it (no COPY_BUFFER is ever recorded for it): races with in-flight frames and
     * changes what earlier passes of the same frame read, so the image may change. Exists to confirm in a Metal System
     * Trace that the hoisted UBO copies serialize consecutive frames (TAIL-ANALYSIS S3).
     */
    static boolean FORCE_FAST_WRITE = Boolean.getBoolean("mcmetal.diag.forceFastWrite");
    /** Env {@code MCMTL_COPY_STATS}: log writeToBuffer outcomes every {@link #WRITE_STATS_FRAMES} frames (diagnostics). */
    private static final boolean WRITE_STATS = MetalBuffer.DIAG_LABEL_BUFFERS;
    private static final int WRITE_STATS_FRAMES = 600;
    // writeToBuffer outcome counters (writeStats[]): fast path, forced fast path, renamed, staged with renaming off,
    // staged because renameForWrite refused (+ reason, MetalBuffer.RENAME_NO_*).
    static final int WS_FAST = 0;
    static final int WS_FORCED = 1;
    static final int WS_RENAMED = 2;
    static final int WS_STAGED_OFF = 3;
    static final int WS_STAGED_REASON = 3;   // + MetalBuffer.RENAME_NO_* (1..5)
    static final int WS_COUNT = WS_STAGED_REASON + MetalBuffer.RENAME_REASONS;

    /** The {@code mcmtl_submit} downcall; replaceable by tests (no native device needed). */
    @FunctionalInterface
    interface SubmitFunction {
        /** Same contract as {@link MtlNative#submit(long, long, long)}. */
        int submit(long device, long info, long result);
    }

    static final SubmitFunction NATIVE_SUBMIT = MtlNative::submit;

    private final MetalDevice device;
    private final StreamWriter mainStream;
    private final StreamWriter initStream;
    /** mcmtl_submit_info followed by mcmtl_submit_result (off-heap scratch, reused by every commit). */
    private final long submitScratch;
    /** Binding/dedupe state shared by every {@link MetalRenderPass} of this encoder (one pass open at a time). */
    private final MetalRenderPass.State passState = new MetalRenderPass.State();
    private @Nullable MetalRenderPass currentPass;
    /** True if a PUSH_DEBUG_GROUP(pass label) precedes the open pass's BEGIN_PASS. */
    private boolean passLabelPushed;
    /** Seq signaled by the previous {@link #submit()} (0 before the first frame). */
    private long previousFrameSeq;
    /** Surface whose PRESENT is recorded but not yet committed, and the main-stream offset of that record. */
    private @Nullable MetalSurface pendingPresentSurface;
    private long pendingPresentOffset = -1L;
    private boolean shutDown;
    /** Lazily captured once per sequence; keep a failed ticket if a downcall dropped an unconsumed seq. */
    private @Nullable MetalSubmissionTicket submissionTicket;
    private int lastResultFlags;
    private int lastDrawableWidth;
    private int lastDrawableHeight;
    private boolean loggedSkippedDraws;
    /**
     * Renames done (writeToBuffer through MetalBuffer.renameForWrite), those whose buffer an earlier frame still read
     * (the cross-frame case; the rest were read earlier in the frame being recorded), and old-content bytes they copied.
     */
    long renames;
    long renamesInFlight;
    long renameCopiedBytes;
    /** writeToBuffer outcomes (WS_*; maintained only with MCMTL_COPY_STATS, renames always) and frames since the last log. */
    final long[] writeStats = new long[WS_COUNT];
    private long writeStatsInFlight;
    private int writeStatsFrames;

    // Test hooks (package-private; production values never change).
    SubmitFunction submitFunction = NATIVE_SUBMIT;
    long frameWaitTimeoutNs = MetalTimeline.FRAME_WAIT_TIMEOUT_NS;
    boolean strictBindings = MetalDevice.STRICT_BINDINGS;

    /** Must not throw and must not call into {@code device} (the device constructor is still running). */
    public MetalCommandEncoder(final MetalDevice device) {
        this.device = device;
        this.mainStream = new StreamWriter("main", StreamWriter.DEFAULT_CAPACITY);
        this.initStream = new StreamWriter("init", INIT_STREAM_CAPACITY);
        long scratch = MemoryUtil.nmemAlignedAlloc(16L, SUBMIT_SCRATCH_BYTES);
        if (scratch == 0L) {
            throw new OutOfMemoryError("Cannot allocate mcmtl_submit scratch structs");
        }
        MemoryUtil.memSet(scratch, 0, SUBMIT_SCRATCH_BYTES);
        this.submitScratch = scratch;
    }

    public MetalDevice device() {
        return this.device;
    }

    // =============================================================================================
    // Cross-module API
    // =============================================================================================

    /**
     * Captures the current pending submission dependency, on the render thread outside a pass. This is
     * only a bounded Java allocation on first capture of a sequence: no throttle guard, drain, submit,
     * native call, shader compile or wait. Repeated captures share the same ticket, even if it failed
     * after dropping a stream without consuming the sequence. Only a new sequence gets a new ticket.
     * See {@link MetalSubmissionTicket} for the worker's retained-event GPU wait requirement.
     */
    public MetalSubmissionTicket captureSubmissionTicket() {
        this.checkUsable();
        this.checkOutsidePass();
        long seq = this.device.timeline().pendingSeq();
        MetalSubmissionTicket ticket = this.submissionTicket;
        if (ticket == null || ticket.sequence() != seq) {
            ticket = new MetalSubmissionTicket(this.device, seq);
            this.submissionTicket = ticket;
        }
        return ticket;
    }

    /**
     * I3 (MetalTexture.create): records {@code INIT_TEXTURE(texture, MCMTL_INIT_TEXTURE_ZERO)} into the init
     * stream. Legal at any time, including while a render pass is open.
     */
    public void recordTextureInit(final MetalTexture texture) {
        this.throttleGuard();
        if (MetalFrameThrottle.STRICT) {
            this.strictThrottleCheck();
        }
        texture.markContentWritten();
        long r = this.initStream.reserve(StreamOps.SIZE_INIT_TEXTURE);
        MemoryUtil.memPutLong(r, StreamOps.header(StreamOps.OP_INIT_TEXTURE, StreamOps.SIZE_INIT_TEXTURE, StreamOps.INIT_TEXTURE_ZERO));
        MemoryUtil.memPutLong(r + StreamOps.INIT_TEXTURE_TEXTURE, texture.handle());
    }

    /**
     * I3 (MetalFence.awaitCompletion with a non-zero timeout on the current seq): if {@code seq ==
     * timeline.pendingSeq()} commits everything recorded so far ({@code mcmtl_submit} with COMMIT, not
     * FRAME_END; init + main streams) so that {@code seq} will complete, then {@code timeline.onCommitted}.
     * No-op if {@code seq} was already committed. Throws {@link IllegalStateException}("Cannot wait on a
     * fence for the current submit while a render pass is open") if a pass is open (spec 1.5).
     */
    public void flushForWait(final long seq) {
        if (seq != this.device.timeline().pendingSeq()) {
            return;
        }
        if (MetalFrameThrottle.ENABLED) {
            // Late throttle: take the frame wait (and its drain, whose callbacks may record or commit) before the value
            // to commit is fixed, then re-validate it; commit() itself never drains (CONTRACT.md 16.R5-A).
            this.throttleGuard();
            if (seq != this.device.timeline().pendingSeq()) {
                return;
            }
        }
        this.checkUsable();
        if (this.currentPass != null) {
            throw new IllegalStateException("Cannot wait on a fence for the current submit while a render pass is open");
        }
        RuntimeException error = this.commit(seq, MtlNative.SUBMIT_FLAG_COMMIT);
        if (error != null) {
            throw error;
        }
    }

    /** True between {@link #createRenderPass} and {@link #submitRenderPass}. */
    public boolean isInRenderPass() {
        return this.currentPass != null;
    }

    /**
     * I3 (MetalDevice.close): discards everything recorded but not committed (including a pass left open by
     * unbalanced debug groups), frees the stream buffers. Does not wait; the device waits afterwards.
     */
    public void shutdown() {
        if (this.shutDown) {
            return;
        }
        this.shutDown = true;
        if (this.submissionTicket != null) {
            this.submissionTicket.finish(false);
        }
        MetalRenderPass pass = this.currentPass;
        if (pass != null) {
            pass.markClosed();
            this.currentPass = null;
        }
        this.passState.clearReferences();
        this.pendingPresentSurface = null;
        this.pendingPresentOffset = -1L;
        this.mainStream.close();
        this.initStream.close();
        MemoryUtil.nmemAlignedFree(this.submitScratch);
    }

    /** I4 (MetalSurface.blitFromTexture): records PRESENT(surface, view.texture(), view.baseMipLevel()). */
    void recordPresent(final MetalSurface surface, final MetalTextureView view) {
        this.throttleGuard();
        if (this.currentPass != null) {
            throw new IllegalStateException("Close the existing render pass before presenting");
        }
        long offset = this.mainStream.size();
        long r = this.record(StreamOps.OP_PRESENT, StreamOps.SIZE_PRESENT, StreamOps.PRESENT_FLAG_NONE);
        MemoryUtil.memPutLong(r + StreamOps.PRESENT_SURFACE, surface.handle());
        MemoryUtil.memPutLong(r + StreamOps.PRESENT_TEXTURE, view.texture().handle());
        MemoryUtil.memPutInt(r + StreamOps.PRESENT_LEVEL, view.baseMipLevel());
        this.pendingPresentSurface = surface;
        this.pendingPresentOffset = offset;
    }

    /**
     * I4 (MetalSurface.close): a PRESENT of {@code surface} that was recorded but not committed is turned into a
     * NOP so the next commit never touches the destroyed native surface.
     */
    void cancelPresent(final MetalSurface surface) {
        if (this.pendingPresentSurface == surface && !this.shutDown) {
            long r = this.mainStream.addressAt(this.pendingPresentOffset);
            MemoryUtil.memSet(r, 0, StreamOps.SIZE_PRESENT);
            MemoryUtil.memPutLong(r, StreamOps.header(StreamOps.OP_NOP, StreamOps.SIZE_PRESENT, 0));
        }
        if (this.pendingPresentSurface == surface) {
            this.pendingPresentSurface = null;
            this.pendingPresentOffset = -1L;
        }
    }

    // =============================================================================================
    // CommandEncoderBackend (spec 2.3)
    // =============================================================================================

    /**
     * Spec 2.3 submit() / CONTRACT.md 5.2: ISE if a pass is open; mcmtl_submit(init + main, COMMIT | FRAME_END,
     * signal pendingSeq); timeline.onCommitted(seq) whatever the status (native consumes the value on every
     * path, CONTRACT.md 4.2); surface result flags; transientMemory.endFrame(frameSeq); reset streams; wait for
     * the previous frame's seq (5 s cap -> ISE); timeline.checkErrors() (device loss / OOM / ISE);
     * timeline.drain(); then rethrow a deferred mcmtl_submit error.
     */
    @Override
    public void submit() {
        this.checkUsable();
        if (this.currentPass != null) {
            throw new IllegalStateException("Cannot submit while a render pass is open; close the render pass first");
        }
        if (MetalFrameThrottle.ENABLED) {
            this.submitLateThrottle();
            return;
        }
        MetalTimeline timeline = this.device.timeline();
        long seq = timeline.pendingSeq();
        RuntimeException submitError = this.commit(seq, MtlNative.SUBMIT_FLAG_COMMIT | MtlNative.SUBMIT_FLAG_FRAME_END);
        if (WRITE_STATS) {
            this.writeStatsFrame();
        }
        long previous = this.previousFrameSeq;
        this.previousFrameSeq = seq;
        try {
            this.device.transientMemory().endFrame(seq);
            if (previous > 0L && !timeline.await(previous, this.frameWaitTimeoutNs)) {
                throw new IllegalStateException("5s timeout reached when waiting for the GPU");
            }
            timeline.checkErrors();
            timeline.drain();
        } catch (RuntimeException e) {
            if (submitError != null) {
                e.addSuppressed(submitError);
            }
            throw e;
        }
        if (submitError != null) {
            throw submitError;
        }
    }

    /**
     * {@link #submit()} with -Dmcmetal.lateThrottle (CONTRACT.md 16.R5-A): the same commit, then the frames-in-flight wait
     * for the previous frame is only armed ({@link MetalFrameThrottle#arm}); transient memory rotates, and checkErrors and
     * drain run without blocking. The wait itself is taken at the throttle point or by the first guarded backend call.
     */
    private void submitLateThrottle() {
        // The guard first: its drain may run callbacks that record or commit; the value is read afterwards. (A wait is
        // armed here only after a frame with no backend call since the previous submit, or after a GPU failure, which
        // this rethrows.)
        this.throttleGuard();
        MetalTimeline timeline = this.device.timeline();
        long seq = timeline.pendingSeq();
        RuntimeException submitError = this.commit(seq, MtlNative.SUBMIT_FLAG_COMMIT | MtlNative.SUBMIT_FLAG_FRAME_END);
        if (WRITE_STATS) {
            this.writeStatsFrame();
        }
        long previous = this.previousFrameSeq;
        this.previousFrameSeq = seq;
        this.device.frameThrottle().arm(previous);
        try {
            this.device.transientMemory().endFrame(seq);
            timeline.checkErrors();
            timeline.drain();
        } catch (RuntimeException e) {
            if (submitError != null) {
                e.addSuppressed(submitError);
            }
            throw e;
        }
        if (submitError != null) {
            throw submitError;
        }
    }

    /** CONTRACT.md 16.R5-A: the late-throttle guard at a public entry point; folds away unless -Dmcmetal.lateThrottle. */
    private void throttleGuard() {
        if (MetalFrameThrottle.ENABLED) {
            this.device.frameThrottle().ensureThrottled();
        }
    }

    /** -Dmcmetal.lateThrottle.strict (tests): a record appended while the frame wait is still armed is a missing guard. */
    private void strictThrottleCheck() {
        if (this.device.frameThrottle().pendingSeq() != 0L) {
            throw new IllegalStateException("Metal late throttle: command recorded before the frame throttle (unguarded entry point)");
        }
    }

    @Override
    public TransientMemory transientMemory() {
        return this.device.transientMemory();
    }

    /** Records BEGIN_PASS (+ PUSH_DEBUG_GROUP(label) first when debug labels are on); returns a new MetalRenderPass. */
    @Override
    public RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
        this.throttleGuard();
        this.checkUsable();
        if (this.currentPass != null) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        }
        List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
        int colorCount = colors.size();
        if (colorCount > MtlNative.MAX_COLOR_ATTACHMENTS) {
            throw new IllegalArgumentException("Render pass has " + colorCount + " color attachments, Metal supports " + MtlNative.MAX_COLOR_ATTACHMENTS);
        }
        RenderPassDescriptor.@Nullable Attachment<OptionalDouble> depth = descriptor.depthAttachment();
        if (colorCount == 0 && depth == null) {
            throw new IllegalArgumentException("At least one attachment (depth or color) must be specified");
        }
        // Validate everything before the first byte is recorded: a half-written pass would poison the stream.
        for (int i = 0; i < colorCount; i++) {
            RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colors.get(i);
            if (attachment != null && (!(attachment.textureView() instanceof MetalTextureView) || attachment.clearValue() == null)) {
                throw new IllegalArgumentException("Color attachment " + i + " needs a Metal texture view and a (possibly empty) clear value");
            }
        }
        if (depth != null && (!(depth.textureView() instanceof MetalTextureView) || depth.clearValue() == null)) {
            throw new IllegalArgumentException("Depth attachment needs a Metal texture view and a (possibly empty) clear value");
        }
        RenderPass.RenderArea area = descriptor.renderArea();
        boolean labels = this.device.debugLabels();
        if (labels) {
            this.recordPushDebugGroup(descriptor.label().get());
        }
        // A load-only or empty pass can still write. Invalidate every parent before BEGIN_PASS so
        // failures partway through encoding cannot leave any possibly written attachment untracked.
        for (int i = 0; i < colorCount; i++) {
            RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colors.get(i);
            if (attachment != null) {
                ((MetalTextureView)attachment.textureView()).texture().markContentWritten();
            }
        }
        if (depth != null) {
            ((MetalTextureView)depth.textureView()).texture().markContentWritten();
        }
        int size = StreamOps.beginPassSize(colorCount);
        long r = this.record(StreamOps.OP_BEGIN_PASS, size, colorCount);
        int clearMask = 0;
        for (int i = 0; i < colorCount; i++) {
            RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colors.get(i);
            if (attachment == null) {
                continue;
            }
            MetalTextureView view = (MetalTextureView)attachment.textureView();
            long a = r + StreamOps.BEGIN_PASS_SIZEOF + (long)i * StreamOps.COLOR_ATTACHMENT_SIZEOF;
            MemoryUtil.memPutLong(a + StreamOps.COLOR_ATTACHMENT_TEXTURE, view.texture().handle());
            MemoryUtil.memPutInt(a + StreamOps.COLOR_ATTACHMENT_LEVEL, view.baseMipLevel());
            Optional<Vector4fc> clear = attachment.clearValue();
            if (clear.isPresent()) {
                clearMask |= 1 << i;
                putColor(a + StreamOps.COLOR_ATTACHMENT_CLEAR_COLOR, clear.get());
            }
        }
        int flags = 0;
        if (depth != null) {
            MetalTextureView view = (MetalTextureView)depth.textureView();
            MemoryUtil.memPutLong(r + StreamOps.BEGIN_PASS_DEPTH_TEXTURE, view.texture().handle());
            MemoryUtil.memPutInt(r + StreamOps.BEGIN_PASS_DEPTH_LEVEL, view.baseMipLevel());
            OptionalDouble clearDepth = depth.clearValue();
            if (clearDepth.isPresent()) {
                flags |= StreamOps.PASS_FLAG_DEPTH_CLEAR;
                MemoryUtil.memPutFloat(r + StreamOps.BEGIN_PASS_DEPTH_CLEAR_VALUE, (float)clearDepth.getAsDouble());
            }
        }
        MemoryUtil.memPutInt(r + StreamOps.BEGIN_PASS_FLAGS, flags);
        MemoryUtil.memPutInt(r + StreamOps.BEGIN_PASS_COLOR_CLEAR_MASK, clearMask);
        MemoryUtil.memPutInt(r + StreamOps.BEGIN_PASS_RENDER_X, area.x());
        MemoryUtil.memPutInt(r + StreamOps.BEGIN_PASS_RENDER_Y, area.y());
        MemoryUtil.memPutInt(r + StreamOps.BEGIN_PASS_RENDER_WIDTH, area.width());
        MemoryUtil.memPutInt(r + StreamOps.BEGIN_PASS_RENDER_HEIGHT, area.height());
        this.passState.begin();
        this.passLabelPushed = labels;
        MetalRenderPass pass = new MetalRenderPass(this, this.passState);
        this.currentPass = pass;
        return pass;
    }

    /** Records END_PASS (+ POP_DEBUG_GROUP); marks the pass object closed. */
    @Override
    public void submitRenderPass() {
        MetalRenderPass pass = this.currentPass;
        if (pass == null) {
            throw new IllegalStateException("Cannot submit a render pass if one hasn't been started!");
        }
        // Debug groups pushed inside the pass must not outlive its render encoder.
        for (int i = this.passState.debugDepth; i > 0; i--) {
            this.recordPopDebugGroup();
        }
        this.recordEmpty(StreamOps.OP_END_PASS);
        if (this.passLabelPushed) {
            this.recordPopDebugGroup();
            this.passLabelPushed = false;
        }
        pass.markClosed();
        this.currentPass = null;
        this.passState.clearReferences();
    }

    @Override
    public void clearColorTexture(final GpuTexture colorTexture, final Vector4fc clearColor) {
        this.throttleGuard();
        this.checkOutsidePass();
        MetalTexture texture = (MetalTexture)colorTexture;
        this.recordClearTexture(texture, StreamOps.ASPECT_COLOR, clearColor, 0.0F);
    }

    @Override
    public void clearColorAndDepthTextures(final GpuTexture colorTexture, final Vector4fc clearColor, final GpuTexture depthTexture, final double clearDepth) {
        this.throttleGuard();
        this.checkOutsidePass();
        MetalTexture color = (MetalTexture)colorTexture;
        MetalTexture depth = (MetalTexture)depthTexture;
        this.recordClearTexture(color, StreamOps.ASPECT_COLOR, clearColor, 0.0F);
        this.recordClearTexture(depth, StreamOps.ASPECT_DEPTH, null, (float)clearDepth);
    }

    @Override
    public void clearColorAndDepthTextures(
        final GpuTexture colorTexture,
        final Vector4fc clearColor,
        final GpuTexture depthTexture,
        final double clearDepth,
        final int regionX,
        final int regionY,
        final int regionWidth,
        final int regionHeight,
        final int mipLevel
    ) {
        this.throttleGuard();
        this.checkOutsidePass();
        MetalTexture color = (MetalTexture)colorTexture;
        MetalTexture depth = (MetalTexture)depthTexture;
        checkMip(color, mipLevel);
        checkMip(depth, mipLevel);
        int width = mipWidth(color, mipLevel);
        int height = mipHeight(color, mipLevel);
        if (mipWidth(depth, mipLevel) != width || mipHeight(depth, mipLevel) != height) {
            throw new IllegalArgumentException(
                "Depth texture mip " + mipLevel + " is " + mipWidth(depth, mipLevel) + "x" + mipHeight(depth, mipLevel) + ", color texture mip is " + width + "x" + height
            );
        }
        if (regionX < 0 || regionY < 0 || regionWidth <= 0 || regionHeight <= 0
            || (long)regionX + regionWidth > width || (long)regionY + regionHeight > height) {
            throw new IllegalArgumentException(
                "Clear region " + regionX + "," + regionY + " " + regionWidth + "x" + regionHeight + " is outside mip " + mipLevel + " (" + width + "x" + height + ")"
            );
        }
        color.markContentWritten();
        depth.markContentWritten();
        long r = this.record(StreamOps.OP_CLEAR_REGION, StreamOps.SIZE_CLEAR_REGION, mipLevel);
        MemoryUtil.memPutLong(r + StreamOps.CLEAR_REGION_COLOR_TEXTURE, color.handle());
        MemoryUtil.memPutLong(r + StreamOps.CLEAR_REGION_DEPTH_TEXTURE, depth.handle());
        MemoryUtil.memPutInt(r + StreamOps.CLEAR_REGION_X, regionX);
        MemoryUtil.memPutInt(r + StreamOps.CLEAR_REGION_Y, regionY);
        MemoryUtil.memPutInt(r + StreamOps.CLEAR_REGION_WIDTH, regionWidth);
        MemoryUtil.memPutInt(r + StreamOps.CLEAR_REGION_HEIGHT, regionHeight);
        putColor(r + StreamOps.CLEAR_REGION_COLOR, clearColor);
        MemoryUtil.memPutFloat(r + StreamOps.CLEAR_REGION_DEPTH, (float)clearDepth);
    }

    @Override
    public void clearDepthTexture(final GpuTexture depthTexture, final double clearDepth) {
        this.throttleGuard();
        this.checkOutsidePass();
        this.recordClearTexture((MetalTexture)depthTexture, StreamOps.ASPECT_DEPTH, null, (float)clearDepth);
    }

    /** Fast path: memcpy into contents when {@code buffer.lastUseSeq() <= completedSeq}; else staging + COPY_BUFFER. */
    @Override
    public void writeToBuffer(final GpuBufferSlice destination, final ByteBuffer data) {
        this.throttleGuard();
        this.checkOutsidePass();
        int length = data.remaining();
        if (length == 0) {
            return;
        }
        MetalBuffer target = (MetalBuffer)destination.buffer();
        long offset = destination.offset();
        if (offset < 0L || length > destination.length() || offset + length > target.size()) {
            throw new IllegalArgumentException(
                "Cannot write " + length + " bytes into slice [" + offset + ", +" + destination.length() + ") of a " + target.size() + " byte buffer"
            );
        }
        long completed = this.device.timeline().completedSeq();
        if (target.lastUseSeq() <= completed) {
            // Idle buffer: no recorded or in-flight work references it, so a CPU write is already in stream order.
            if (WRITE_STATS) {
                this.writeStats[WS_FAST]++;
            }
            copyBytes(data, target.contents() + offset, length);
            return;
        }
        if (WRITE_STATS && target.lastUseSeq() < this.pendingSeq()) {
            this.writeStatsInFlight++;
        }
        if (FORCE_FAST_WRITE) {
            // Diagnostics only (unsafe, see FORCE_FAST_WRITE): the GPU may still read these bytes.
            if (WRITE_STATS) {
                this.writeStats[WS_FORCED]++;
            }
            copyBytes(data, target.contents() + offset, length);
            return;
        }
        if (RENAME_SMALL_WRITES) {
            // CONTRACT.md 16.R5-C: recorded work keeps the old backing (handles are captured at record time).
            boolean earlierFrame = target.lastUseSeq() < this.pendingSeq();
            int reason = target.renameForWrite(offset, length, completed);
            if (reason == MetalBuffer.RENAME_OK) {
                this.renames++;
                if (earlierFrame) {
                    this.renamesInFlight++;
                }
                this.renameCopiedBytes += target.size() - length;
                copyBytes(data, target.contents() + offset, length);
                return;
            }
            if (WRITE_STATS) {
                this.writeStats[WS_STAGED_REASON + reason]++;
            }
        } else if (WRITE_STATS) {
            this.writeStats[WS_STAGED_OFF]++;
        }
        GpuBufferSlice staging = this.uploadStaging(data, length);
        MetalBuffer stagingBuffer = (MetalBuffer)staging.buffer();
        this.recordCopyBufferUnmarked(stagingBuffer, staging.offset(), target, offset, length);
        long seq = this.pendingSeq();
        // Renaming on (r5-int review fix): a staged write is kept for replay instead of being marked as a GPU write, so
        // one refusal (no spare, open mapping) does not keep refusing every later in-flight write of this buffer.
        if (!RENAME_SMALL_WRITES || !target.recordStagedWrite(seq, offset, length, stagingBuffer.contents() + staging.offset(), completed)) {
            target.markGpuWrite(seq);
        }
    }

    @Override
    public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
        this.throttleGuard();
        this.checkOutsidePass();
        long length = source.length();
        if (length != target.length()) {
            throw new IllegalArgumentException("Cannot copy from slice of size " + length + " to slice of size " + target.length() + ", they must be equal");
        }
        MetalBuffer src = (MetalBuffer)source.buffer();
        MetalBuffer dst = (MetalBuffer)target.buffer();
        if (source.offset() < 0L || target.offset() < 0L || source.offset() + length > src.size() || target.offset() + length > dst.size()) {
            throw new IllegalArgumentException("Buffer copy of " + length + " bytes is out of bounds");
        }
        if (length == 0L) {
            return;
        }
        long srcStart = src.backingOffset() + source.offset();
        long dstStart = dst.backingOffset() + target.offset();
        if (src.handle() == dst.handle() && srcStart < dstStart + length && dstStart < srcStart + length) {
            // Overlapping ranges of one MTLBuffer: blit copies with overlap are undefined, stage through scratch memory.
            GpuBufferSlice scratch = this.device.transientMemory().allocateGpu(length, STAGING_ALIGNMENT, GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST);
            MetalBuffer scratchBuffer = (MetalBuffer)scratch.buffer();
            this.recordCopyBuffer(src, source.offset(), scratchBuffer, scratch.offset(), length);
            this.recordCopyBuffer(scratchBuffer, scratch.offset(), dst, target.offset(), length);
        } else {
            this.recordCopyBuffer(src, source.offset(), dst, target.offset(), length);
        }
    }

    @Override
    public void writeToTexture(
        final GpuTexture destination, final ByteBuffer source, final int mipLevel, final int depthOrLayer, final int destX, final int destY, final int width, final int height
    ) {
        this.throttleGuard();
        this.checkOutsidePass();
        MetalTexture texture = (MetalTexture)destination;
        checkMip(texture, mipLevel);
        checkLayer(texture, depthOrLayer);
        checkRect(texture, mipLevel, destX, destY, width, height, "Dest");
        int texelBytes = bufferTexelBytes(texture.getFormat());
        long bytes = (long)width * height * texelBytes;
        if (bytes > source.remaining()) {
            throw new IllegalArgumentException("Copy would overrun the source buffer (remaining " + source.remaining() + ", copy needs " + bytes + ")");
        }
        if (bytes == 0L) {
            return;
        }
        GpuBufferSlice staging = this.uploadStaging(source, (int)bytes);
        long bytesPerRow = (long)width * texelBytes;
        this.recordBufferTexture(
            StreamOps.OP_COPY_BUFFER_TO_TEXTURE, (MetalBuffer)staging.buffer(), staging.offset(), texture, mipLevel, depthOrLayer, destX, destY, width, height,
            bytesPerRow, bytes
        );
    }

    @Override
    public void copyBufferToTexture(
        final GpuBufferSlice source,
        final int sourceX,
        final int sourceY,
        final int sourceWidth,
        final int sourceHeight,
        final GpuTexture destination,
        final int destinationX,
        final int destinationY,
        final int copyWidth,
        final int copyHeight,
        final int mipLevel,
        final int arrayLayer
    ) {
        this.throttleGuard();
        this.checkOutsidePass();
        MetalBuffer buffer = (MetalBuffer)source.buffer();
        MetalTexture texture = (MetalTexture)destination;
        checkMip(texture, mipLevel);
        checkLayer(texture, arrayLayer);
        checkRect(texture, mipLevel, destinationX, destinationY, copyWidth, copyHeight, "Dest");
        if (sourceX < 0 || sourceY < 0 || sourceWidth < 0 || sourceHeight < 0
            || (long)sourceX + copyWidth > sourceWidth || (long)sourceY + copyHeight > sourceHeight) {
            throw new IllegalArgumentException(
                "Copy source (" + sourceWidth + "x" + sourceHeight + ") is not large enough to read a rectangle of " + copyWidth + "x" + copyHeight
                    + " from " + sourceX + "x" + sourceY
            );
        }
        if (source.offset() < 0L || source.length() < 0L || source.offset() + source.length() > buffer.size()) {
            throw new IllegalArgumentException("Source slice [" + source.offset() + ", +" + source.length() + ") is outside a " + buffer.size() + " byte buffer");
        }
        if (copyWidth == 0 || copyHeight == 0) {
            return;
        }
        int texelBytes = bufferTexelBytes(texture.getFormat());
        long bytesPerRow = (long)sourceWidth * texelBytes;
        long first = ((long)sourceY * sourceWidth + sourceX) * texelBytes;
        long end = ((long)(sourceY + copyHeight - 1) * sourceWidth + sourceX + copyWidth) * texelBytes;
        if (end > source.length()) {
            throw new IllegalArgumentException(
                "Copy of " + copyWidth + "x" + copyHeight + " at " + sourceX + "," + sourceY + " from a " + sourceWidth + " texel wide image reads " + end
                    + " bytes, but the source slice holds " + source.length()
            );
        }
        long bytesPerImage = bytesPerRow * copyHeight;
        if ((buffer.backingOffset() + source.offset() + first) % texelBytes == 0L) {
            this.recordBufferTexture(
                StreamOps.OP_COPY_BUFFER_TO_TEXTURE, buffer, source.offset() + first, texture, mipLevel, arrayLayer, destinationX, destinationY, copyWidth,
                copyHeight, bytesPerRow, bytesPerImage
            );
        } else {
            // Metal needs the source offset to be a multiple of the texel size: realign through scratch memory.
            long span = end - first;
            GpuBufferSlice scratch = this.device.transientMemory()
                .allocateGpu(Math.max(span, bytesPerImage), STAGING_ALIGNMENT, GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST);
            MetalBuffer scratchBuffer = (MetalBuffer)scratch.buffer();
            this.recordCopyBuffer(buffer, source.offset() + first, scratchBuffer, scratch.offset(), span);
            this.recordBufferTexture(
                StreamOps.OP_COPY_BUFFER_TO_TEXTURE, scratchBuffer, scratch.offset(), texture, mipLevel, arrayLayer, destinationX, destinationY, copyWidth,
                copyHeight, bytesPerRow, bytesPerImage
            );
        }
    }

    @Override
    public void copyTextureToBuffer(final GpuTexture source, final GpuBuffer destination, final long offset, final Runnable callback, final int mipLevel) {
        this.throttleGuard();
        this.checkOutsidePass();
        MetalTexture texture = (MetalTexture)source;
        checkMip(texture, mipLevel);
        this.recordTextureToBuffer(texture, (MetalBuffer)destination, offset, callback, mipLevel, 0, 0, mipWidth(texture, mipLevel), mipHeight(texture, mipLevel));
    }

    @Override
    public void copyTextureToBuffer(
        final GpuTexture source, final GpuBuffer destination, final long offset, final Runnable callback, final int mipLevel, final int x, final int y, final int width, final int height
    ) {
        this.throttleGuard();
        this.checkOutsidePass();
        MetalTexture texture = (MetalTexture)source;
        checkMip(texture, mipLevel);
        this.recordTextureToBuffer(texture, (MetalBuffer)destination, offset, callback, mipLevel, x, y, width, height);
    }

    @Override
    public void copyTextureToTexture(
        final GpuTexture source, final GpuTexture destination, final int mipLevel, final int destX, final int destY, final int sourceX, final int sourceY, final int width, final int height
    ) {
        this.throttleGuard();
        this.checkOutsidePass();
        MetalTexture src = (MetalTexture)source;
        MetalTexture dst = (MetalTexture)destination;
        checkMip(src, mipLevel);
        checkMip(dst, mipLevel);
        checkRect(src, mipLevel, sourceX, sourceY, width, height, "Source");
        checkRect(dst, mipLevel, destX, destY, width, height, "Dest");
        if (src.getDepthOrLayers() > 1 || dst.getDepthOrLayers() > 1) {
            throw new UnsupportedOperationException("Textures with multiple depths or layers are not yet supported for copying");
        }
        int flags = 0;
        if (src.pixelFormat() != dst.pixelFormat()) {
            if ((dst.usage() & GpuTexture.USAGE_RENDER_ATTACHMENT) == 0) {
                throw new IllegalArgumentException(
                    "Cannot copy " + src.getFormat() + " into " + dst.getFormat() + ": different formats need a destination with USAGE_RENDER_ATTACHMENT"
                );
            }
            flags = StreamOps.COPY_FLAG_DRAW;
        }
        if (width == 0 || height == 0) {
            return;
        }
        dst.markContentWritten();
        long r = this.record(StreamOps.OP_COPY_TEXTURE, StreamOps.SIZE_COPY_TEXTURE, flags);
        MemoryUtil.memPutLong(r + StreamOps.COPY_TEXTURE_SRC_TEXTURE, src.handle());
        MemoryUtil.memPutLong(r + StreamOps.COPY_TEXTURE_DST_TEXTURE, dst.handle());
        MemoryUtil.memPutInt(r + StreamOps.COPY_TEXTURE_SRC_LEVEL, mipLevel);
        MemoryUtil.memPutInt(r + StreamOps.COPY_TEXTURE_DST_LEVEL, mipLevel);
        MemoryUtil.memPutInt(r + StreamOps.COPY_TEXTURE_SRC_X, sourceX);
        MemoryUtil.memPutInt(r + StreamOps.COPY_TEXTURE_SRC_Y, sourceY);
        MemoryUtil.memPutInt(r + StreamOps.COPY_TEXTURE_DST_X, destX);
        MemoryUtil.memPutInt(r + StreamOps.COPY_TEXTURE_DST_Y, destY);
        MemoryUtil.memPutInt(r + StreamOps.COPY_TEXTURE_WIDTH, width);
        MemoryUtil.memPutInt(r + StreamOps.COPY_TEXTURE_HEIGHT, height);
    }

    /** {@code new MetalFence(device, timeline.pendingSeq())}. */
    @Override
    public GpuFence createFence() {
        return new MetalFence(this.device, this.device.timeline().pendingSeq());
    }

    /** Bounds-check {@code index < pool.size()}; record TIMESTAMP; {@code pool.markWritten(index, pendingSeq)}. Legal while the frontend is "in pass". */
    @Override
    public void writeTimestamp(final GpuQueryPool pool, final int index) {
        this.throttleGuard();
        this.recordTimestamp((MetalQueryPool)pool, index);
    }

    // =============================================================================================
    // Commit
    // =============================================================================================

    /**
     * One mcmtl_submit with COMMIT (+ {@code extraFlags}) signaling {@code seq} (= pendingSeq): the native side
     * consumes the value on every return path, so {@code timeline.onCommitted(seq)} runs whatever the status.
     * Returns the exception for a failed status (built right away so it carries this thread's last native
     * error) for the caller to throw at the right point, or null.
     */
    private @Nullable RuntimeException commit(final long seq, final int flags) {
        // Late throttle: never more than two frames' commits in flight (an empty frame or a flush-on-wait before the
        // throttle point waits here). No drain here: callbacks must not run after the caller fixed seq (they could
        // commit it); submit() and flushForWait() take the full guard before reading seq. Folds away when off.
        if (MetalFrameThrottle.ENABLED) {
            this.device.frameThrottle().ensureThrottledNoDrain();
        }
        long info = this.submitScratch;
        long result = info + MtlNative.SUBMIT_INFO_SIZEOF;
        StreamWriter init = this.initStream;
        StreamWriter main = this.mainStream;
        MemoryUtil.memPutInt(info + MtlNative.SUBMIT_INFO_STRUCT_SIZE, (int)MtlNative.SUBMIT_INFO_SIZEOF);
        MemoryUtil.memPutInt(info + MtlNative.SUBMIT_INFO_FLAGS, flags);
        MemoryUtil.memPutLong(info + MtlNative.SUBMIT_INFO_INIT_STREAM, init.isEmpty() ? 0L : init.address());
        MemoryUtil.memPutLong(info + MtlNative.SUBMIT_INFO_INIT_STREAM_LENGTH, init.size());
        MemoryUtil.memPutLong(info + MtlNative.SUBMIT_INFO_STREAM, main.isEmpty() ? 0L : main.address());
        MemoryUtil.memPutLong(info + MtlNative.SUBMIT_INFO_STREAM_LENGTH, main.size());
        MemoryUtil.memPutLong(info + MtlNative.SUBMIT_INFO_SIGNAL_VALUE, seq);
        MemoryUtil.memPutLong(info + MtlNative.SUBMIT_INFO_LABEL, 0L);
        MemoryUtil.memPutLong(info + MtlNative.SUBMIT_INFO_RESERVED, 0L);
        MemoryUtil.memSet(result, 0, MtlNative.SUBMIT_RESULT_SIZEOF);
        MemoryUtil.memPutInt(result + MtlNative.SUBMIT_RESULT_STRUCT_SIZE, (int)MtlNative.SUBMIT_RESULT_SIZEOF);
        MetalSubmissionTicket ticket = this.submissionTicket;
        if (ticket != null && ticket.sequence() != seq) {
            ticket = null;
        }
        try {
            MetalDiag.sync();
            int status = this.submitFunction.submit(this.device.handle(), info, result);
            RuntimeException error = null;
            if (status != MtlNative.OK) {
                long errorOffset = MemoryUtil.memGetLong(result + MtlNative.SUBMIT_RESULT_ERROR_OFFSET);
                boolean inInit = (MemoryUtil.memGetInt(result + MtlNative.SUBMIT_RESULT_FLAGS) & MtlNative.SUBMIT_RESULT_FLAG_ERROR_IN_INIT_STREAM) != 0;
                error = MtlNative.exceptionFor(
                    status, "mcmtl_submit(seq " + seq + ", error at byte " + errorOffset + " of the " + (inInit ? "init" : "main") + " stream)"
                );
            }
            this.device.timeline().onCommitted(seq);
            if (MetalFrameTrace.ENABLED) {
                MetalFrameTrace.onCommit(this.device, result, flags, seq); // CONTRACT.md 16.R5-B (bench frame trace)
            }
            int resultFlags = MemoryUtil.memGetInt(result + MtlNative.SUBMIT_RESULT_FLAGS);
            int drawableWidth = MemoryUtil.memGetInt(result + MtlNative.SUBMIT_RESULT_DRAWABLE_WIDTH);
            int drawableHeight = MemoryUtil.memGetInt(result + MtlNative.SUBMIT_RESULT_DRAWABLE_HEIGHT);
            this.lastResultFlags = resultFlags;
            this.lastDrawableWidth = drawableWidth;
            this.lastDrawableHeight = drawableHeight;
            if ((resultFlags & MtlNative.SUBMIT_RESULT_FLAG_DRAWS_SKIPPED) != 0 && !this.loggedSkippedDraws) {
                this.loggedSkippedDraws = true;
                LOGGER.debug("Metal: {} draw(s) skipped in submit {} (missing vertex/index buffer or pipeline variant); logged once",
                    MemoryUtil.memGetInt(result + MtlNative.SUBMIT_RESULT_DRAWS_SKIPPED), seq);
            }
            this.resetStreams();
            MetalSurface surface = this.pendingPresentSurface;
            if (surface != null) {
                this.pendingPresentSurface = null;
                this.pendingPresentOffset = -1L;
                surface.onPresentResult(resultFlags, drawableWidth, drawableHeight);
            }
            // Publish last, after the downcall returned and all commit bookkeeping/reset succeeded. A
            // consumed failed native value is not a usable dependency; early GPU completion is fine.
            if (ticket != null) {
                ticket.finish(status == MtlNative.OK && (resultFlags & MtlNative.SUBMIT_RESULT_FLAG_COMMITTED) != 0);
            }
            return error;
        } catch (RuntimeException | Error e) {
            // A downcall failure leaves pendingSeq unchanged; a post-submit Java failure may have
            // advanced it already. Neither may publish a usable ticket or replay the dropped streams.
            if (ticket != null) {
                ticket.finish(false);
            }
            this.resetStreams();
            this.pendingPresentSurface = null;
            this.pendingPresentOffset = -1L;
            throw e;
        }
    }

    private void resetStreams() {
        this.mainStream.reset();
        this.initStream.reset();
    }

    private void checkUsable() {
        if (this.shutDown) {
            throw new IllegalStateException("The Metal command encoder has been shut down");
        }
    }

    private void checkOutsidePass() {
        if (this.currentPass != null) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        }
    }

    // =============================================================================================
    // Package-private state for MetalRenderPass and tests
    // =============================================================================================

    boolean debugLabels() {
        return this.device.debugLabels();
    }

    long pendingSeq() {
        return this.device.timeline().pendingSeq();
    }

    StreamWriter mainStream() {
        return this.mainStream;
    }

    StreamWriter initStream() {
        return this.initStream;
    }

    @Nullable MetalRenderPass currentPass() {
        return this.currentPass;
    }

    int lastResultFlags() {
        return this.lastResultFlags;
    }

    int lastDrawableWidth() {
        return this.lastDrawableWidth;
    }

    int lastDrawableHeight() {
        return this.lastDrawableHeight;
    }

    // =============================================================================================
    // Record writers (main stream). Each reserves a zeroed record and fills the non-zero fields.
    // =============================================================================================

    /** Reserves a main-stream record and writes its header; returns the record address. */
    long record(final int op, final int size, final int arg) {
        if (MetalFrameThrottle.STRICT) {
            this.strictThrottleCheck();
        }
        long r = this.mainStream.reserve(size);
        MemoryUtil.memPutLong(r, StreamOps.header(op, size, arg));
        return r;
    }

    /** NOP / POP_DEBUG_GROUP / RESET_SCISSOR / END_PASS: header only (16 bytes). */
    void recordEmpty(final int op) {
        if (MetalFrameThrottle.STRICT) {
            this.strictThrottleCheck();
        }
        long r = this.mainStream.reserve(StreamOps.SIZE_EMPTY);
        MemoryUtil.memPutLong(r, StreamOps.header(op, StreamOps.SIZE_EMPTY, 0));
    }

    void recordPopDebugGroup() {
        this.recordEmpty(StreamOps.OP_POP_DEBUG_GROUP);
    }

    /** PUSH_DEBUG_GROUP with the label's UTF-8 bytes inline (truncated to MAX_LABEL_BYTES on a character boundary, NUL -> '?'). */
    void recordPushDebugGroup(final @Nullable String label) {
        String text = label == null ? "" : label;
        int length = text.length();
        boolean ascii = length <= MtlNative.MAX_LABEL_BYTES;
        for (int i = 0; ascii && i < length; i++) {
            char c = text.charAt(i);
            ascii = c != 0 && c < 0x80;
        }
        if (ascii) {
            int size = StreamOps.pushDebugGroupSize(length);
            long r = this.record(StreamOps.OP_PUSH_DEBUG_GROUP, size, length);
            long p = r + StreamOps.PUSH_DEBUG_GROUP_SIZEOF;
            for (int i = 0; i < length; i++) {
                MemoryUtil.memPutByte(p + i, (byte)text.charAt(i));
            }
            return;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int n = bytes.length;
        if (n > MtlNative.MAX_LABEL_BYTES) {
            n = MtlNative.MAX_LABEL_BYTES;
            while (n > 0 && (bytes[n] & 0xC0) == 0x80) {
                n--; // never cut a multi-byte sequence
            }
        }
        int size = StreamOps.pushDebugGroupSize(n);
        long r = this.record(StreamOps.OP_PUSH_DEBUG_GROUP, size, n);
        long p = r + StreamOps.PUSH_DEBUG_GROUP_SIZEOF;
        for (int i = 0; i < n; i++) {
            byte b = bytes[i];
            MemoryUtil.memPutByte(p + i, b == 0 ? (byte)'?' : b);
        }
    }

    /** COPY_BUFFER; offsets are slice offsets (the backing offsets are added here); marks both buffers used. */
    void recordCopyBuffer(final MetalBuffer src, final long srcOffset, final MetalBuffer dst, final long dstOffset, final long length) {
        this.recordCopyBufferUnmarked(src, srcOffset, dst, dstOffset, length);
        dst.markGpuWrite(this.pendingSeq());
    }

    /** COPY_BUFFER + markUsed on both; the caller decides how the destination's GPU write is tracked. */
    private void recordCopyBufferUnmarked(final MetalBuffer src, final long srcOffset, final MetalBuffer dst, final long dstOffset, final long length) {
        long r = this.record(StreamOps.OP_COPY_BUFFER, StreamOps.SIZE_COPY_BUFFER, 0);
        MemoryUtil.memPutLong(r + StreamOps.COPY_BUFFER_SRC_BUFFER, src.handle());
        MemoryUtil.memPutLong(r + StreamOps.COPY_BUFFER_SRC_OFFSET, src.backingOffset() + srcOffset);
        MemoryUtil.memPutLong(r + StreamOps.COPY_BUFFER_DST_BUFFER, dst.handle());
        MemoryUtil.memPutLong(r + StreamOps.COPY_BUFFER_DST_OFFSET, dst.backingOffset() + dstOffset);
        MemoryUtil.memPutLong(r + StreamOps.COPY_BUFFER_LENGTH, length);
        long seq = this.pendingSeq();
        src.markUsed(seq);
        dst.markUsed(seq);
    }

    /** COPY_BUFFER_TO_TEXTURE / COPY_TEXTURE_TO_BUFFER (shared layout); {@code bufferOffset} is a slice offset. */
    void recordBufferTexture(
        final int op, final MetalBuffer buffer, final long bufferOffset, final MetalTexture texture, final int level, final int slice, final int x, final int y,
        final int width, final int height, final long bytesPerRow, final long bytesPerImage
    ) {
        if (bytesPerRow > 0xFFFF_FFFFL || bytesPerImage > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("Copy of " + width + "x" + height + " texels is too large (" + bytesPerImage + " bytes)");
        }
        if (op == StreamOps.OP_COPY_BUFFER_TO_TEXTURE) {
            texture.markContentWritten();
        }
        long r = this.record(op, StreamOps.SIZE_COPY_BUFFER_TEXTURE, level);
        MemoryUtil.memPutLong(r + StreamOps.COPY_BUFFER_TEXTURE_BUFFER, buffer.handle());
        MemoryUtil.memPutLong(r + StreamOps.COPY_BUFFER_TEXTURE_BUFFER_OFFSET, buffer.backingOffset() + bufferOffset);
        MemoryUtil.memPutLong(r + StreamOps.COPY_BUFFER_TEXTURE_TEXTURE, texture.handle());
        MemoryUtil.memPutInt(r + StreamOps.COPY_BUFFER_TEXTURE_BYTES_PER_ROW, (int)bytesPerRow);
        MemoryUtil.memPutInt(r + StreamOps.COPY_BUFFER_TEXTURE_BYTES_PER_IMAGE, (int)bytesPerImage);
        MemoryUtil.memPutInt(r + StreamOps.COPY_BUFFER_TEXTURE_SLICE, slice);
        MemoryUtil.memPutInt(r + StreamOps.COPY_BUFFER_TEXTURE_X, x);
        MemoryUtil.memPutInt(r + StreamOps.COPY_BUFFER_TEXTURE_Y, y);
        MemoryUtil.memPutInt(r + StreamOps.COPY_BUFFER_TEXTURE_WIDTH, width);
        MemoryUtil.memPutInt(r + StreamOps.COPY_BUFFER_TEXTURE_HEIGHT, height);
        MemoryUtil.memPutInt(r + StreamOps.COPY_BUFFER_TEXTURE_BLIT_OPTIONS, MetalFormats.blitOptions(texture.getFormat()));
        buffer.markUsed(this.pendingSeq());
        if (op == StreamOps.OP_COPY_TEXTURE_TO_BUFFER) {
            buffer.markGpuWrite(this.pendingSeq());
        }
    }

    /** TIMESTAMP after the bounds check the frontend gets wrong (index &lt;= size); marks the sample written. */
    void recordTimestamp(final MetalQueryPool pool, final int index) {
        if (index < 0 || index >= pool.size()) {
            throw new IllegalStateException("Index " + index + " is out of range for query pool of size " + pool.size());
        }
        long r = this.record(StreamOps.OP_TIMESTAMP, StreamOps.SIZE_TIMESTAMP, index);
        MemoryUtil.memPutLong(r + StreamOps.TIMESTAMP_QUERY_POOL, pool.handle());
        pool.markWritten(index, this.pendingSeq());
    }

    private void recordClearTexture(final MetalTexture texture, final int aspects, final @Nullable Vector4fc color, final float depth) {
        texture.markContentWritten();
        long r = this.record(StreamOps.OP_CLEAR_TEXTURE, StreamOps.SIZE_CLEAR_TEXTURE, aspects);
        MemoryUtil.memPutLong(r + StreamOps.CLEAR_TEXTURE_TEXTURE, texture.handle());
        MemoryUtil.memPutInt(r + StreamOps.CLEAR_TEXTURE_BASE_LEVEL, 0);
        MemoryUtil.memPutInt(r + StreamOps.CLEAR_TEXTURE_LEVEL_COUNT, texture.getMipLevels());
        MemoryUtil.memPutInt(r + StreamOps.CLEAR_TEXTURE_SLICE, 0);
        MemoryUtil.memPutFloat(r + StreamOps.CLEAR_TEXTURE_DEPTH, depth);
        if (color != null) {
            putColor(r + StreamOps.CLEAR_TEXTURE_COLOR, color);
        }
    }

    private void recordTextureToBuffer(
        final MetalTexture texture, final MetalBuffer buffer, final long offset, final @Nullable Runnable callback, final int mipLevel, final int x, final int y,
        final int width, final int height
    ) {
        checkRect(texture, mipLevel, x, y, width, height, "Copy source");
        if ((texture.usage() & GpuTexture.USAGE_COPY_SRC) == 0) {
            throw new IllegalArgumentException("Texture needs USAGE_COPY_SRC to be a source for a copy");
        }
        if ((buffer.usage() & GpuBuffer.USAGE_COPY_DST) == 0) {
            throw new IllegalArgumentException("Buffer needs USAGE_COPY_DST to be a destination for a copy");
        }
        if (texture.getDepthOrLayers() > 1) {
            throw new UnsupportedOperationException("Textures with multiple depths or layers are not yet supported for copying");
        }
        int texelBytes = bufferTexelBytes(texture.getFormat());
        long bytes = (long)width * height * texelBytes;
        if (offset < 0L || offset + bytes > buffer.size()) {
            throw new IllegalArgumentException(
                "Buffer of size " + buffer.size() + " is not large enough to hold " + width + "x" + height + " pixels (" + texelBytes + " bytes each) starting from offset "
                    + offset
            );
        }
        if (texture.isClosed()) {
            throw new IllegalStateException("Source texture is closed");
        }
        if (buffer.isClosed()) {
            throw new IllegalStateException("Destination buffer is closed");
        }
        if (bytes != 0L) {
            long bytesPerRow = (long)width * texelBytes;
            if ((buffer.backingOffset() + offset) % texelBytes == 0L) {
                this.recordBufferTexture(StreamOps.OP_COPY_TEXTURE_TO_BUFFER, buffer, offset, texture, mipLevel, 0, x, y, width, height, bytesPerRow, bytes);
            } else {
                // Metal needs the destination offset to be a multiple of the texel size: copy via scratch memory.
                GpuBufferSlice scratch = this.device.transientMemory().allocateGpu(bytes, STAGING_ALIGNMENT, GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST);
                MetalBuffer scratchBuffer = (MetalBuffer)scratch.buffer();
                this.recordBufferTexture(
                    StreamOps.OP_COPY_TEXTURE_TO_BUFFER, scratchBuffer, scratch.offset(), texture, mipLevel, 0, x, y, width, height, bytesPerRow, bytes
                );
                this.recordCopyBuffer(scratchBuffer, scratch.offset(), buffer, offset, bytes);
            }
        }
        if (callback != null) {
            // Runs on the render thread, in FIFO order, once the commit carrying this copy has completed.
            this.device.timeline().defer(callback);
        }
    }

    /**
     * Copies exactly {@code length} bytes of {@code data} (from its position) into a 16-byte aligned staging
     * allocation; {@code data}'s position and limit are unchanged afterwards.
     */
    private GpuBufferSlice uploadStaging(final ByteBuffer data, final int length) {
        int position = data.position();
        int limit = data.limit();
        try {
            if (limit - position != length) {
                data.limit(position + length);
            }
            return this.device.transientMemory().uploadStaging(data, STAGING_ALIGNMENT, GpuBuffer.USAGE_COPY_SRC);
        } finally {
            if (data.limit() != limit || data.position() != position) {
                data.limit(limit);
                data.position(position);
            }
        }
    }

    // =============================================================================================
    // Diagnostics (MCMTL_COPY_STATS; CONTRACT.md 16.R5-C)
    // =============================================================================================

    private final long[] writeStatsLogged = new long[WS_COUNT + 6];

    /** Every WRITE_STATS_FRAMES submits: writeToBuffer outcomes and transient-block churn per frame since the last log. */
    private void writeStatsFrame() {
        if (++this.writeStatsFrames < WRITE_STATS_FRAMES) {
            return;
        }
        double f = this.writeStatsFrames;
        this.writeStatsFrames = 0;
        long[] last = this.writeStatsLogged;
        long[] now = new long[WS_COUNT + 6];
        System.arraycopy(this.writeStats, 0, now, 0, WS_COUNT);
        now[WS_RENAMED] = this.renames;
        MetalTransientMemory tm = this.device.transientMemory();
        now[WS_COUNT] = this.renameCopiedBytes;
        now[WS_COUNT + 1] = this.writeStatsInFlight;
        now[WS_COUNT + 2] = tm.blocksCreated();
        now[WS_COUNT + 3] = tm.blocksFreed();
        now[WS_COUNT + 4] = tm.blocksReused();
        now[WS_COUNT + 5] = tm.reservedBlocks();
        StringBuilder staged = new StringBuilder();
        String[] reasons = {"", "size", "kind", "mapped", "gpu-write", "no-spare"};
        for (int r = 1; r < MetalBuffer.RENAME_REASONS; r++) {
            long n = now[WS_STAGED_REASON + r] - last[WS_STAGED_REASON + r];
            if (n != 0L) {
                staged.append(' ').append(reasons[r]).append('=').append(String.format("%.2f", n / f));
            }
        }
        LOGGER.info(
            "Metal writeToBuffer per frame ({} frames; renameSmallWrites={}, forceFastWrite={}): fast {}, renamed {} ({} old bytes copied), "
                + "staged {} (renaming off {}; refused:{}), forced {}; target read by an earlier frame {}; transient blocks created {} / freed {} / "
                + "reused from the hysteresis reserve {} (reserve now {})",
            (int)f, RENAME_SMALL_WRITES, FORCE_FAST_WRITE, String.format("%.2f", (now[WS_FAST] - last[WS_FAST]) / f),
            String.format("%.2f", (now[WS_RENAMED] - last[WS_RENAMED]) / f), String.format("%.0f", (now[WS_COUNT] - last[WS_COUNT]) / f),
            String.format("%.2f", (sum(now, WS_STAGED_OFF, WS_COUNT) - sum(last, WS_STAGED_OFF, WS_COUNT)) / f),
            String.format("%.2f", (now[WS_STAGED_OFF] - last[WS_STAGED_OFF]) / f), staged.length() == 0 ? " none" : staged,
            String.format("%.2f", (now[WS_FORCED] - last[WS_FORCED]) / f), String.format("%.2f", (now[WS_COUNT + 1] - last[WS_COUNT + 1]) / f),
            now[WS_COUNT + 2] - last[WS_COUNT + 2], now[WS_COUNT + 3] - last[WS_COUNT + 3], now[WS_COUNT + 4] - last[WS_COUNT + 4], now[WS_COUNT + 5]
        );
        System.arraycopy(now, 0, last, 0, now.length);
    }

    private static long sum(final long[] values, final int from, final int to) {
        long s = 0L;
        for (int i = from; i < to; i++) {
            s += values[i];
        }
        return s;
    }

    // =============================================================================================
    // Helpers
    // =============================================================================================

    static void putColor(final long address, final Vector4fc color) {
        MemoryUtil.memPutFloat(address, color.x());
        MemoryUtil.memPutFloat(address + 4L, color.y());
        MemoryUtil.memPutFloat(address + 8L, color.z());
        MemoryUtil.memPutFloat(address + 12L, color.w());
    }

    /** Copies {@code length} bytes starting at {@code src.position()} to {@code dst}; never changes {@code src}. */
    static void copyBytes(final ByteBuffer src, final long dst, final int length) {
        if (src.isDirect()) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(src), dst, length);
        } else {
            MemorySegment.copy(MemorySegment.ofBuffer(src), 0L, MemorySegment.ofAddress(dst).reinterpret(length), 0L, length);
        }
    }

    /**
     * Bytes per texel on the buffer side of a buffer/texture copy: the block size, except for combined
     * depth/stencil formats, whose copies move the depth plane only (DepthFromDepthStencil: 4 bytes for
     * D32_FLOAT_S8_UINT, as Vulkan's depth-aspect copies do).
     */
    static int bufferTexelBytes(final GpuFormat format) {
        return format == GpuFormat.D32_FLOAT_S8_UINT ? 4 : format.blockSize();
    }

    /** Metal mip extent: {@code max(1, width >> mip)} (the API value is not clamped, spec 2.10). */
    static int mipWidth(final GpuTexture texture, final int mip) {
        return Math.max(1, texture.getWidth(mip));
    }

    static int mipHeight(final GpuTexture texture, final int mip) {
        return Math.max(1, texture.getHeight(mip));
    }

    private static void checkMip(final GpuTexture texture, final int mip) {
        if (mip < 0 || mip >= texture.getMipLevels()) {
            throw new IllegalArgumentException("Invalid mipLevel " + mip + ", must be >= 0 and < " + texture.getMipLevels());
        }
    }

    private static void checkLayer(final GpuTexture texture, final int layer) {
        if (layer < 0 || layer >= texture.getDepthOrLayers()) {
            throw new IllegalArgumentException("Depth or layer " + layer + " is out of range, must be >= 0 and < " + texture.getDepthOrLayers());
        }
    }

    private static void checkRect(final GpuTexture texture, final int mip, final int x, final int y, final int width, final int height, final String what) {
        if (x < 0 || y < 0 || width < 0 || height < 0
            || (long)x + width > mipWidth(texture, mip) || (long)y + height > mipHeight(texture, mip)) {
            throw new IllegalArgumentException(
                what + " texture (" + mipWidth(texture, mip) + "x" + mipHeight(texture, mip) + " at mip " + mip + ") does not contain the rectangle " + width + "x"
                    + height + " at " + x + "," + y
            );
        }
    }
}
