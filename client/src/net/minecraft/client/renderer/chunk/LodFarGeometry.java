package net.minecraft.client.renderer.chunk;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owned Phase-1 opaque inputs, captured only AFTER refresh admission at prepareLodReuse.
 * No geometry payload is copied. The lease pins allocation ranges, including after world close.
 * Metadata can cost 48 MiB of section tables plus items per capture; this is not a per-frame API.
 *
 * <p>The backend must copy these CPU bytes and retain the EXACT native buffers on the render thread
 * before handing work to a worker or dropping Java references. Resolve each slice's native offset as
 * MetalBuffer.backingOffset() + slice.offset(). Slices are references, not native retains. The native
 * lifetime owns releaseToken until producer AND all visibility-ID consumers finish. Its destructor
 * queues that token in a bounded native queue; Java polls it, calls deferredRelease, then drains on
 * the render thread, including menu frames after world close. Never use an FFM completion upcall.
 * An abandoned Java-only offer calls close(); after native handoff only the native lifetime releases.
 */
public final class LodFarGeometry implements AutoCloseable {
    static final int MAX_INPUTS = 4096;
    static final int MAX_SECTIONS = 1 << 20;
    static final int VIEW_BYTES = 128;
    public static final long MAX_METADATA_BYTES = 64L << 20;
    // Every arena reserves the same ID span, even BLOCK arenas and arenas absent from this selection.
    static final long FACE_SPAN = (256L << 20) / 48;
    private static final AtomicLong NEXT_GENERATION = new AtomicLong();
    private static final Releases RELEASES = new Releases(RenderSystem::assertOnRenderThread);

    /** Geometry/build identities are owner-local; generation distinguishes worlds/owners. */
    public record Source(long generation, long geometryEpoch, long membershipBuild, long selectionVersion,
                         long visibilityRevision, long allocationEpoch, long frame, long viewNanos,
                         int originX, int originY, int originZ,
                         double cameraX, double cameraY, double cameraZ,
                         double eyeX, double eyeY, double eyeZ) { }

    public static final class Input {
        private final byte[] items;
        private final GpuBufferSlice vertices, indices;
        private final int layerCode, arenaIndex, faceBase, quadCount;

        Input(int[] liveItems, int count, int quadCount, int layerCode, int arenaIndex,
              GpuBufferSlice vertices, GpuBufferSlice indices) {
            this.items = copyItems(liveItems, count);
            this.vertices = vertices;
            this.indices = indices;
            this.layerCode = layerCode;
            this.arenaIndex = arenaIndex;
            this.faceBase = LodFarGeometry.faceBase(arenaIndex);
            this.quadCount = quadCount;
        }

        public ByteBuffer items() { return readOnly(this.items); }
        /** Full arena slice: item first-vertex offsets stay relative to this slice's byte zero. */
        public GpuBufferSlice vertices() { return this.vertices; }
        /** UINT32 sequential quad indices, bounded to this input's pre-cull quad count. */
        public GpuBufferSlice indices() { return this.indices; }
        public int layerCode() { return this.layerCode; }
        public int arenaIndex() { return this.arenaIndex; }
        public int faceBase() { return this.faceBase; }
        public int clusterCount() { return this.items.length / 16; }
        public int quadCount() { return this.quadCount; }
    }

    private final Source source;
    private final List<Input> inputs;
    private final byte[] cullSections, shaderSections, view;
    private final long releaseToken, metadataBytes, captureNanos;

    // Arrays are newly allocated by LodGpu and ownership is transferred here, never shared with live tables.
    LodFarGeometry(Source source, List<Input> inputs, byte[] cullSections, byte[] shaderSections,
                   byte[] view, long releaseToken, long metadataBytes, long captureNanos) {
        this.source = source;
        this.inputs = List.copyOf(inputs);
        this.cullSections = cullSections;
        this.shaderSections = shaderSections;
        this.view = view;
        this.releaseToken = releaseToken;
        this.metadataBytes = metadataBytes;
        this.captureNanos = captureNanos;
    }

    public Source source() { return this.source; }
    public List<Input> inputs() { return this.inputs; }
    public ByteBuffer cullSections() { return readOnly(this.cullSections); }
    public ByteBuffer shaderSections() { return readOnly(this.shaderSections); }
    public ByteBuffer view() { return readOnly(this.view); }
    public int sectionCount() { return this.shaderSections.length / 16; }
    public long releaseToken() { return this.releaseToken; }
    public long metadataBytes() { return this.metadataBytes; }
    public long captureNanos() { return this.captureNanos; }

    private static ByteBuffer readOnly(byte[] bytes) {
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    static long nextGeneration() {
        long generation = NEXT_GENERATION.incrementAndGet();
        if (generation <= 0) throw new IllegalStateException("Far geometry generation exhausted");
        return generation;
    }

    static int faceBase(int arenaIndex) {
        if (arenaIndex < 0 || arenaIndex >= 64) throw new IllegalArgumentException("Invalid arena");
        return Math.toIntExact(arenaIndex * FACE_SPAN);
    }

    /** Validate BEFORE multiplying counts or touching mapped memory. Invalid inputs skip the offer. */
    static boolean validItems(int[] items, int count, long quads, int quadBytes, long vertexBytes, int sections) {
        if (items == null || count <= 0 || count > items.length / 4 || count > (1 << 26)
            || sections <= 0 || sections > MAX_SECTIONS || (quadBytes != 48 && quadBytes != 112)
            || vertexBytes <= 0 || vertexBytes / quadBytes > FACE_SPAN
            || quads <= 0 || quads > 0xFFFFFFFFL / 6) return false;
        long sum = 0;
        for (int i = 0; i < count; i++) {
            long first = Integer.toUnsignedLong(items[4 * i]);
            int packed = items[4 * i + 1];
            int q = ((packed >>> 20) & 63) + 1;
            if ((first & 3) != 0 || (packed & 0xFFFFF) >= sections
                || (first / 4 + q) > vertexBytes / quadBytes) return false;
            sum += q;
        }
        return sum == quads;
    }

    static byte[] copyItems(int[] items, int count) {
        if (count <= 0 || count > items.length / 4 || count > Integer.MAX_VALUE / 16)
            throw new IllegalArgumentException("Invalid item count");
        byte[] bytes = new byte[count * 16];
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asIntBuffer().put(items, 0, count * 4);
        return bytes;
    }

    /** Preserve current planes/deltas exactly; remove only the near-depth section-occlusion mode. */
    static boolean completeView(byte[] bytes) {
        if (bytes.length != VIEW_BYTES) return false;
        ByteBuffer view = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        int flags = view.getInt(84);
        if ((flags & 6) != 6 || (flags & ~31) != 0) return false;
        for (int i = 0; i < 32; i++) {
            if (i != 21 && i != 23 && !Float.isFinite(view.getFloat(i * 4))) return false;
        }
        view.putInt(84, flags & 15);
        return true;
    }

    static long retain(LodGeometryLeases.Lease lease) { return RELEASES.add(lease); }

    /** Any Java thread; only marks an existing slot. Unknown/duplicate tokens cannot fill a queue. */
    public static void deferredRelease(long token) { RELEASES.defer(token); }

    /** Global render-thread poll, required even when the originating world no longer exists. No wait. */
    public static int drainDeferredReleases() { return RELEASES.drain(); }

    /** Cancel a Java-owned offer. Native-owned offers must release via the native lifetime instead. */
    @Override public void close() { deferredRelease(this.releaseToken); }

    /** Three slots globally bound outstanding ownership across world switches as well as active worlds. */
    static final class Releases {
        private final Runnable assertRenderThread;
        private final LodGeometryLeases.Lease[] leases = new LodGeometryLeases.Lease[LodGeometryLeases.MAX_LEASES];
        private final long[] tokens = new long[this.leases.length];
        private final boolean[] pending = new boolean[this.leases.length];
        private long nextToken = 1;

        Releases(Runnable assertRenderThread) { this.assertRenderThread = assertRenderThread; }

        synchronized long add(LodGeometryLeases.Lease lease) {
            this.assertRenderThread.run();
            if (lease == null || lease.isClosed() || this.nextToken == 0) return 0;
            for (int i = 0; i < this.leases.length; i++) {
                if (this.leases[i] == lease) throw new IllegalArgumentException("Lease already registered");
            }
            for (int i = 0; i < this.leases.length; i++) {
                if (this.leases[i] != null) continue;
                long token = this.nextToken;
                this.nextToken = token == Long.MAX_VALUE ? 0 : token + 1;
                this.leases[i] = lease;
                this.tokens[i] = token;
                return token;
            }
            return 0;
        }

        synchronized void defer(long token) {
            if (token <= 0) return;
            for (int i = 0; i < this.leases.length; i++) {
                if (this.tokens[i] == token && this.leases[i] != null) this.pending[i] = true;
            }
        }

        int drain() {
            this.assertRenderThread.run();
            int count = 0;
            for (int i = 0; i < this.leases.length; i++) {
                LodGeometryLeases.Lease lease;
                synchronized (this) {
                    if (!this.pending[i]) continue;
                    lease = this.leases[i];
                    this.leases[i] = null;
                    this.tokens[i] = 0;
                    this.pending[i] = false;
                }
                lease.close(); // May dispose a CLOSED world; never invoked on a worker/native thread.
                count++;
            }
            return count;
        }
    }
}
