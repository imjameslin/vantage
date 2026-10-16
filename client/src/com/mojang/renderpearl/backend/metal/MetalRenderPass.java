package com.mojang.renderpearl.backend.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * {@link RenderPassBackend}: records pass-level records into the encoder's main stream (spec 2.4, 3.4;
 * CONTRACT.md 4.4 and 9.4). OWNER: I4 (java-encoder).
 *
 * <p>One small object per {@code createRenderPass}, so a stale reference to a finished pass is detected
 * exactly: after {@code submitRenderPass()} every method throws {@link IllegalStateException} (the frontend
 * keeps forwarding calls after close). All binding state lives in the encoder-owned {@link State}, reused
 * by every pass (only one pass is open at a time), so nothing else is allocated per pass.
 *
 * <p>Binding state (no hash maps, no allocation, no native calls except the first
 * {@link MetalBuffer#texelView} per (buffer, format)): the bound {@link MetalRenderPipeline}'s
 * tables, the last value recorded per binding index and a set mask; {@code setPipeline} clears the set mask;
 * {@code setUniform} drops exact repeats ({@code GpuBufferSlice}: same buffer and offset;
 * {@code TextureViewAndSampler}: same view and sampler), drops indices with {@code uniformStages[i] == 0},
 * and records SET_BUFFER / SET_TEXTURE / CLEAR_BINDING with the pipeline's slot and stage mask. Buffer
 * records use {@code buffer.handle()} and {@code buffer.backingOffset() + slice.offset()} read at record time
 * (identity dedupe stays valid: a backing only changes outside render passes).
 *
 * <p>Other dedupe (all per pass): re-setting the bound pipeline records nothing and keeps the binding state
 * (equivalent: the frontend re-sends every uniform it holds right after {@code setPipeline}); identical
 * consecutive vertex-buffer, index-buffer and scissor commands are dropped. Vertex and index buffers persist
 * across pipelines within a pass (as the decoder's Metal bindings do).
 */
public final class MetalRenderPass implements RenderPassBackend {
    private final MetalCommandEncoder encoder;
    private final State state;
    private boolean closed;

    MetalRenderPass(final MetalCommandEncoder encoder, final State state) {
        this.encoder = encoder;
        this.state = state;
    }

    public MetalCommandEncoder encoder() {
        return this.encoder;
    }

    /** True once {@code submitRenderPass()} ended this pass (or the encoder shut down). */
    public boolean isClosed() {
        return this.closed;
    }

    void markClosed() {
        this.closed = true;
    }

    private State open() {
        if (this.closed) {
            throw new IllegalStateException("Can't use a closed render pass");
        }
        return this.state;
    }

    /** Only when debug labels are on: evaluates the supplier and records PUSH_DEBUG_GROUP; otherwise never calls {@code get()}. */
    @Override
    public void pushDebugGroup(final Supplier<String> label) {
        State s = this.open();
        if (this.encoder.debugLabels()) {
            this.encoder.recordPushDebugGroup(label.get());
            s.debugDepth++;
        }
    }

    @Override
    public void popDebugGroup() {
        State s = this.open();
        if (this.encoder.debugLabels()) {
            if (s.debugDepth == 0) {
                throw new IllegalStateException("Can't pop more debug groups than was pushed!");
            }
            s.debugDepth--;
            this.encoder.recordPopDebugGroup();
        }
    }

    /** Swap tables, clear the set mask, record SET_PIPELINE(pipeline.handle()); the bound pipeline again records nothing. */
    @Override
    public void setPipeline(final BackendRenderPipeline pipeline) {
        State s = this.open();
        if (!(pipeline instanceof MetalRenderPipeline metalPipeline)) {
            throw new IllegalArgumentException("Pipeline must be instance of MetalRenderPipeline");
        }
        if (metalPipeline == s.pipeline) {
            return;
        }
        s.pipeline = metalPipeline;
        s.setMask = 0;
        s.skipDraws = SKIP_PIPELINES.length > 0 && skipOn && skipped(metalPipeline);
        long r = this.encoder.record(StreamOps.OP_SET_PIPELINE, StreamOps.SIZE_SET_PIPELINE, 0);
        MemoryUtil.memPutLong(r + StreamOps.SET_PIPELINE_PIPELINE, metalPipeline.handle());
    }

    /** Dispatch on {@code pipeline.uniformKind[index]} (never instanceof); see class doc. */
    @Override
    public void setUniform(final int index, final @Nullable Object value) {
        State s = this.open();
        MetalRenderPipeline p = s.pipeline;
        if (p == null || index < 0 || index >= p.uniformCount) {
            return;
        }
        int stages = p.uniformStages[index];
        if (stages == 0) {
            return; // no stage's MSL reads it: never bound (CONTRACT.md 6.2)
        }
        boolean tracked = index < State.TRACKED_UNIFORMS;
        int bit = tracked ? 1 << index : 0;
        int kind = p.uniformKind[index];
        int slot = p.uniformSlot[index];
        if (value == null) {
            if (!tracked || (s.setMask & bit) != 0) {
                s.setMask &= ~bit;
                if (tracked) {
                    s.lastA[index] = null;
                    s.lastB[index] = null;
                }
                long r = this.encoder.record(StreamOps.OP_CLEAR_BINDING, StreamOps.SIZE_CLEAR_BINDING, slot);
                MemoryUtil.memPutInt(r + StreamOps.CLEAR_BINDING_STAGES, stages);
                MemoryUtil.memPutInt(
                    r + StreamOps.CLEAR_BINDING_KIND,
                    kind == MetalRenderPipeline.KIND_UNIFORM_BUFFER ? StreamOps.BINDING_KIND_BUFFER : StreamOps.BINDING_KIND_TEXTURE
                );
            }
            return;
        }
        switch (kind) {
            case MetalRenderPipeline.KIND_UNIFORM_BUFFER -> {
                GpuBufferSlice slice = (GpuBufferSlice)value;
                MetalBuffer buffer = (MetalBuffer)slice.buffer();
                long offset = slice.offset();
                if (tracked) {
                    if ((s.setMask & bit) != 0 && s.lastA[index] == buffer && s.lastOffset[index] == offset) {
                        return;
                    }
                    s.setMask |= bit;
                    s.lastA[index] = buffer;
                    s.lastB[index] = null;
                    s.lastOffset[index] = offset;
                }
                long r = this.encoder.record(StreamOps.OP_SET_BUFFER, StreamOps.SIZE_SET_BUFFER, slot);
                MemoryUtil.memPutInt(r + StreamOps.SET_BUFFER_STAGES, stages);
                MemoryUtil.memPutLong(r + StreamOps.SET_BUFFER_BUFFER, buffer.handle());
                MemoryUtil.memPutLong(r + StreamOps.SET_BUFFER_OFFSET, buffer.backingOffset() + offset);
                buffer.markUsed(this.encoder.pendingSeq());
            }
            case MetalRenderPipeline.KIND_SAMPLED_TEXTURE -> {
                TextureViewAndSampler pair = (TextureViewAndSampler)value;
                MetalTextureView view = (MetalTextureView)pair.view();
                MetalSampler sampler = (MetalSampler)pair.sampler();
                if (tracked) {
                    if ((s.setMask & bit) != 0 && s.lastA[index] == view && s.lastB[index] == sampler) {
                        return;
                    }
                    s.setMask |= bit;
                    s.lastA[index] = view;
                    s.lastB[index] = sampler;
                }
                this.recordSetTexture(slot, stages, view.samplingHandle(), sampler.handle());
            }
            case MetalRenderPipeline.KIND_TEXEL_BUFFER -> {
                GpuBufferSlice slice = (GpuBufferSlice)value;
                MetalBuffer buffer = (MetalBuffer)slice.buffer();
                long offset = slice.offset();
                if (tracked) {
                    if ((s.setMask & bit) != 0 && s.lastA[index] == buffer && s.lastOffset[index] == offset) {
                        return;
                    }
                    s.setMask |= bit;
                    s.lastA[index] = buffer;
                    s.lastB[index] = null;
                    s.lastOffset[index] = offset;
                }
                // Texel buffers are whole-buffer views (the frontend requires a whole-buffer slice, spec 3.7).
                long texelView = buffer.texelView(p.texelPixelFormat[index], p.texelBytesPerTexel[index]);
                this.recordSetTexture(slot, stages, texelView, 0L);
                buffer.markUsed(this.encoder.pendingSeq());
            }
            default -> throw new IllegalStateException("Unknown uniform kind " + kind + " at index " + index + " of pipeline " + p.name);
        }
    }

    private void recordSetTexture(final int slot, final int stages, final long texture, final long sampler) {
        long r = this.encoder.record(StreamOps.OP_SET_TEXTURE, StreamOps.SIZE_SET_TEXTURE, slot);
        MemoryUtil.memPutInt(r + StreamOps.SET_TEXTURE_STAGES, stages);
        MemoryUtil.memPutLong(r + StreamOps.SET_TEXTURE_TEXTURE, texture);
        MemoryUtil.memPutLong(r + StreamOps.SET_TEXTURE_SAMPLER, sampler);
    }

    /** Copies {@code pipeline.pushConstantSize} bytes inline (PUSH_CONSTANTS, stages = pipeline.pushConstantStages). */
    @Override
    public void pushConstants(final ByteBuffer value) {
        State s = this.open();
        MetalRenderPipeline p = s.pipeline;
        if (p == null) {
            throw new IllegalStateException("Must bind pipeline before pushing constants");
        }
        int length = p.pushConstantSize;
        if (length <= 0 || p.pushConstantStages == 0) {
            return; // no stage reads the block: nothing to bind
        }
        if (length > MtlNative.MAX_PUSH_CONSTANT_BYTES) {
            throw new IllegalArgumentException("Push constant block of " + length + " bytes exceeds " + MtlNative.MAX_PUSH_CONSTANT_BYTES);
        }
        if (value.remaining() < length) {
            throw new IllegalArgumentException("Not enough values for push constant range");
        }
        int size = StreamOps.pushConstantsSize(length);
        long r = this.encoder.record(StreamOps.OP_PUSH_CONSTANTS, size, length);
        MemoryUtil.memPutInt(r + StreamOps.PUSH_CONSTANTS_STAGES, p.pushConstantStages);
        MetalCommandEncoder.copyBytes(value, r + StreamOps.PUSH_CONSTANTS_SIZEOF, length);
    }

    @Override
    public void enableScissor(final int x, final int y, final int width, final int height) {
        State s = this.open();
        if (!s.scissorReset && s.scissorX == x && s.scissorY == y && s.scissorWidth == width && s.scissorHeight == height) {
            return;
        }
        s.scissorReset = false;
        s.scissorX = x;
        s.scissorY = y;
        s.scissorWidth = width;
        s.scissorHeight = height;
        long r = this.encoder.record(StreamOps.OP_SET_SCISSOR, StreamOps.SIZE_SET_SCISSOR, 0);
        MemoryUtil.memPutInt(r + StreamOps.SET_SCISSOR_X, x);
        MemoryUtil.memPutInt(r + StreamOps.SET_SCISSOR_Y, y);
        MemoryUtil.memPutInt(r + StreamOps.SET_SCISSOR_WIDTH, width);
        MemoryUtil.memPutInt(r + StreamOps.SET_SCISSOR_HEIGHT, height);
    }

    /** RESET_SCISSOR (= render area, not "off"). */
    @Override
    public void disableScissor() {
        State s = this.open();
        if (s.scissorReset) {
            return;
        }
        s.scissorReset = true;
        this.encoder.recordEmpty(StreamOps.OP_RESET_SCISSOR);
    }

    /** Null keeps the previous binding (records nothing). */
    @Override
    public void setVertexBuffer(final int slot, final @Nullable GpuBufferSlice vertexBuffer) {
        State s = this.open();
        if (vertexBuffer == null) {
            return;
        }
        if (slot < 0 || slot >= MtlNative.MAX_VERTEX_BUFFERS) {
            throw new IllegalArgumentException("Vertex buffer slot is out of range: " + slot);
        }
        MetalBuffer buffer = (MetalBuffer)vertexBuffer.buffer();
        long offset = vertexBuffer.offset();
        int bit = 1 << slot;
        if ((s.vertexBoundMask & bit) != 0 && s.vertexBuffer[slot] == buffer && s.vertexOffset[slot] == offset) {
            return;
        }
        s.vertexBoundMask |= bit;
        s.vertexBuffer[slot] = buffer;
        s.vertexOffset[slot] = offset;
        long r = this.encoder.record(StreamOps.OP_SET_VERTEX_BUFFER, StreamOps.SIZE_SET_VERTEX_BUFFER, slot);
        MemoryUtil.memPutLong(r + StreamOps.SET_VERTEX_BUFFER_BUFFER, buffer.handle());
        MemoryUtil.memPutLong(r + StreamOps.SET_VERTEX_BUFFER_OFFSET, buffer.backingOffset() + offset);
        buffer.markUsed(this.encoder.pendingSeq());
    }

    /** Whole buffer at offset 0; MetalFormats.indexType. */
    @Override
    public void setIndexBuffer(final GpuBuffer indexBuffer, final IndexType indexType) {
        State s = this.open();
        MetalBuffer buffer = (MetalBuffer)indexBuffer;
        int type = MetalFormats.indexType(indexType);
        if (s.indexBuffer == buffer && s.indexType == type) {
            return;
        }
        // SET_INDEX_BUFFER has no offset field: a non-zero backing offset (never in M1) is folded into firstIndex
        // of direct indexed draws; indirect indexed draws cannot carry it and are rejected below.
        long backingOffset = buffer.backingOffset();
        if (backingOffset % indexType.bytes != 0L) {
            throw new IllegalStateException("Index buffer backing offset " + backingOffset + " is not a multiple of the index size " + indexType.bytes);
        }
        s.indexBuffer = buffer;
        s.indexType = type;
        s.indexBias = backingOffset / indexType.bytes;
        long r = this.encoder.record(StreamOps.OP_SET_INDEX_BUFFER, StreamOps.SIZE_SET_INDEX_BUFFER, type);
        MemoryUtil.memPutLong(r + StreamOps.SET_INDEX_BUFFER_BUFFER, buffer.handle());
        buffer.markUsed(this.encoder.pendingSeq());
    }

    @Override
    public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
        State s = this.open();
        if (s.skipDraws) {
            return;
        }
        this.beforeDraw(s, true);
        if ((indexCount | instanceCount | firstIndex | firstInstance) < 0) {
            throw new IllegalArgumentException(
                "Invalid indexed draw: indexCount " + indexCount + ", instanceCount " + instanceCount + ", firstIndex " + firstIndex + ", firstInstance " + firstInstance
            );
        }
        if (indexCount == 0 || instanceCount == 0) {
            return;
        }
        long first = firstIndex + s.indexBias;
        if (first > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("firstIndex " + first + " does not fit in 32 bits");
        }
        long r = this.encoder.record(StreamOps.OP_DRAW_INDEXED, StreamOps.SIZE_DRAW_INDEXED, indexCount);
        MemoryUtil.memPutInt(r + StreamOps.DRAW_INDEXED_INSTANCE_COUNT, instanceCount);
        MemoryUtil.memPutInt(r + StreamOps.DRAW_INDEXED_FIRST_INDEX, (int)first);
        MemoryUtil.memPutInt(r + StreamOps.DRAW_INDEXED_BASE_VERTEX, vertexOffset);
        MemoryUtil.memPutInt(r + StreamOps.DRAW_INDEXED_FIRST_INSTANCE, firstInstance);
    }

    /** Feature reported false: throw UnsupportedOperationException (spec 2.4). */
    @Override
    public void multiDrawIndexed(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
        this.open();
        throw new UnsupportedOperationException("Metal backend does not support the multiDrawDirectInterleaved device feature");
    }

    /** Feature reported false: throw UnsupportedOperationException (spec 2.4). */
    @Override
    public void multiDrawIndexed(final PointerBuffer firstIndexOffsets, final IntBuffer indexCounts, final IntBuffer vertexOffsets, final int drawCount) {
        this.open();
        throw new UnsupportedOperationException("Metal backend does not support the multiDrawDirectSeparate device feature");
    }

    @Override
    public void drawIndexedIndirect(final GpuBufferSlice commands, final int drawCount) {
        State s = this.open();
        this.beforeDraw(s, true);
        if (s.indexBias != 0L) {
            throw new UnsupportedOperationException("drawIndexedIndirect with an index buffer at a non-zero backing offset");
        }
        this.recordIndirect(StreamOps.OP_DRAW_INDEXED_INDIRECT, commands, drawCount);
    }

    @Override
    public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
        State s = this.open();
        if (s.skipDraws) {
            return;
        }
        this.beforeDraw(s, false);
        if ((vertexCount | instanceCount | firstVertex | firstInstance) < 0) {
            throw new IllegalArgumentException(
                "Invalid draw: vertexCount " + vertexCount + ", instanceCount " + instanceCount + ", firstVertex " + firstVertex + ", firstInstance " + firstInstance
            );
        }
        if (vertexCount == 0 || instanceCount == 0) {
            return;
        }
        long r = this.encoder.record(StreamOps.OP_DRAW, StreamOps.SIZE_DRAW, vertexCount);
        MemoryUtil.memPutInt(r + StreamOps.DRAW_INSTANCE_COUNT, instanceCount);
        MemoryUtil.memPutInt(r + StreamOps.DRAW_FIRST_VERTEX, firstVertex);
        MemoryUtil.memPutInt(r + StreamOps.DRAW_FIRST_INSTANCE, firstInstance);
    }

    /** Feature reported false: throw UnsupportedOperationException (spec 2.4). */
    @Override
    public void multiDraw(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
        this.open();
        throw new UnsupportedOperationException("Metal backend does not support the multiDrawDirectInterleaved device feature");
    }

    /** Feature reported false: throw UnsupportedOperationException (spec 2.4). */
    @Override
    public void multiDraw(final IntBuffer firstVertices, final IntBuffer vertexCounts, final int drawCount) {
        this.open();
        throw new UnsupportedOperationException("Metal backend does not support the multiDrawDirectSeparate device feature");
    }

    @Override
    public void drawIndirect(final GpuBufferSlice commands, final int drawCount) {
        State s = this.open();
        this.beforeDraw(s, false);
        this.recordIndirect(StreamOps.OP_DRAW_INDIRECT, commands, drawCount);
    }

    /** Bounds-check {@code index < pool.size()}; record TIMESTAMP inside the pass. */
    @Override
    public void writeTimestamp(final GpuQueryPool pool, final int index) {
        this.open();
        this.encoder.recordTimestamp((MetalQueryPool)pool, index);
    }

    private void recordIndirect(final int op, final GpuBufferSlice commands, final int drawCount) {
        if (this.open().skipDraws) {
            return;
        }
        if (drawCount < 0) {
            throw new IllegalArgumentException("Invalid indirect draw count " + drawCount);
        }
        if (drawCount == 0) {
            return;
        }
        MetalBuffer buffer = (MetalBuffer)commands.buffer();
        long offset = buffer.backingOffset() + commands.offset();
        if ((offset & 3L) != 0L) {
            throw new IllegalArgumentException("Indirect commands offset " + offset + " must be a multiple of 4");
        }
        long r = this.encoder.record(op, StreamOps.SIZE_DRAW_INDIRECT, drawCount);
        MemoryUtil.memPutLong(r + StreamOps.DRAW_INDIRECT_BUFFER, buffer.handle());
        MemoryUtil.memPutLong(r + StreamOps.DRAW_INDIRECT_OFFSET, offset);
        buffer.markUsed(this.encoder.pendingSeq());
    }

    /** A pipeline must be bound; with -Dmcmetal.strictBindings=true missing bindings throw like Vulkan (spec 3.8). */
    private void beforeDraw(final State s, final boolean indexed) {
        MetalRenderPipeline p = s.pipeline;
        if (p == null) {
            throw new IllegalStateException("Can't draw without a render pipeline");
        }
        if (this.encoder.strictBindings) {
            int missing = p.usedUniformMask & ~s.setMask;
            if (missing != 0) {
                int index = Integer.numberOfTrailingZeros(missing);
                throw new IllegalStateException("Missing uniform " + (index < p.uniformName.length ? p.uniformName[index] : "#" + index) + " in pipeline " + p.name);
            }
            int missingVertex = p.vertexBufferSlotMask & ~s.vertexBoundMask;
            if (missingVertex != 0) {
                throw new IllegalStateException("Missing vertex buffer at slot " + Integer.numberOfTrailingZeros(missingVertex) + " in pipeline " + p.name);
            }
            if (indexed && s.indexBuffer == null) {
                throw new IllegalStateException("Missing index buffer in pipeline " + p.name);
            }
        }
    }

    /**
     * Per-pass recording state, owned by the encoder and reused by every pass. Masks are reset by
     * {@link #begin()}; object references are dropped at pass end so finished passes do not pin buffers.
     */
    /**
     * Diagnostics only (never set in normal play): -Dmcmetal.skipPipelines=substr1,substr2 drops every draw whose pipeline
     * name contains one of the substrings, to price each part of the frame by FPS difference.
     */
    private static final String[] SKIP_PIPELINES = parseSkip(System.getProperty("mcmetal.skipPipelines", ""));
    /** Diagnostics: abab-switchable gate of -Dmcmetal.skipPipelines (round 9 cost budget); "*" matches every pipeline. */
    public static volatile boolean skipOn = !"false".equals(System.getProperty("mcmetal.skipPipelines.on", "true"));

    private static String[] parseSkip(final String value) {
        return value.isBlank() ? new String[0] : java.util.Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toArray(String[]::new);
    }

    private static boolean skipped(final MetalRenderPipeline pipeline) {
        for (String needle : SKIP_PIPELINES) {
            if ("*".equals(needle) || pipeline.name.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    static final class State {
        /** Diagnostics: draws of the bound pipeline are dropped (-Dmcmetal.skipPipelines). */
        boolean skipDraws;
        /** Binding indices with dedupe/set tracking (MetalRenderPipeline.usedUniformMask covers 32). */
        static final int TRACKED_UNIFORMS = 32;

        @Nullable MetalRenderPipeline pipeline;
        /** Bit i: a non-null value is recorded for binding index i since the last SET_PIPELINE. */
        int setMask;
        /** Last recorded buffer (UBO / texel) or view per binding index. */
        final @Nullable Object[] lastA = new Object[TRACKED_UNIFORMS];
        /** Last recorded sampler per binding index. */
        final @Nullable Object[] lastB = new Object[TRACKED_UNIFORMS];
        final long[] lastOffset = new long[TRACKED_UNIFORMS];
        final @Nullable Object[] vertexBuffer = new Object[MtlNative.MAX_VERTEX_BUFFERS];
        final long[] vertexOffset = new long[MtlNative.MAX_VERTEX_BUFFERS];
        int vertexBoundMask;
        @Nullable MetalBuffer indexBuffer;
        int indexType = -1;
        /** Index buffer backing offset in indices (0 in M1), added to firstIndex of direct indexed draws. */
        long indexBias;
        boolean scissorReset = true;
        int scissorX;
        int scissorY;
        int scissorWidth;
        int scissorHeight;
        /** PUSH_DEBUG_GROUPs recorded inside the open pass and not yet popped (labels on only). */
        int debugDepth;

        void begin() {
            this.pipeline = null;
            this.setMask = 0;
            this.vertexBoundMask = 0;
            this.indexBuffer = null;
            this.indexType = -1;
            this.indexBias = 0L;
            this.scissorReset = true;
            this.debugDepth = 0;
        }

        void clearReferences() {
            this.pipeline = null;
            this.indexBuffer = null;
            this.setMask = 0;
            this.vertexBoundMask = 0;
            this.debugDepth = 0;
            Arrays.fill(this.lastA, null);
            Arrays.fill(this.lastB, null);
            Arrays.fill(this.vertexBuffer, null);
        }
    }
}
