package net.minecraft.client.renderer.chunk;

import com.mojang.renderpearl.api.pipeline.IndexType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * R9-1 (CONTRACT 16.R9-1): back-facing TRANSLUCENT quads are not drawn, exactly.
 *
 * <p>Every translucent terrain pipeline culls back faces, so a back-facing quad produces no fragment and no depth write;
 * leaving it out of the draw cannot change the image. TRANSLUCENT keeps vanilla's per-quad sorted index buffer, whose order
 * must not change, so S1's bucket reorder cannot be used. Instead, when a sorted index buffer is uploaded, it is extended to
 * {@code [full sorted list (N quads)][the same list without the quads of the facing buckets that face away from the camera
 * (K quads)]}: the first part is byte-for-byte vanilla's buffer, the second keeps the relative order of every remaining quad.
 * The dropped bucket mask E and K are stored with the allocation (set in the upload callback, together with the offset).
 *
 * <p>At draw time ({@link TerrainDrawList}) the K-quad list is drawn only if, for the eye of this frame (the bobbed eye of
 * the exact frustum, as S1), every bucket in E is still excluded by {@link TerrainMeshInfo#visibleMask} with S1's margin
 * {@link TerrainRuns#EPS}: then every dropped quad lies in a plane the eye is behind by more than the margin, so it is back-
 * facing (its bucket is a facing class, classified exactly by {@link TerrainMeshInfo#classifyQuad}). Otherwise the full
 * vanilla list is drawn. Either way the drawn quads are vanilla's front-facing quads in vanilla's order.
 *
 * <p>The buckets are chosen at upload with a wider margin ({@link #BUILD_EPS}) from the dispatcher's camera position, so
 * that small eye movements (bob) keep the short list valid until the next resort. Index memory of translucent sections
 * grows by K quads (at most 2x).
 */
public final class TranslucentFacing {
    static final double BUILD_EPS = 1.0;
    static final int NO_SLOTS = 0;

    private TranslucentFacing() {
    }

    /** Result of {@link #extend}: the new index bytes (memAlloc'd; free with {@link #free}), the kept index count, E. */
    public record Extended(ByteBuffer buffer, int keptIndexCount, int droppedMask) {
    }

    /**
     * The dropped bucket mask for an eye at {@code (rx, ry, rz)} relative to the section origin: facing buckets with quads
     * that {@link TerrainMeshInfo#visibleMask} excludes at {@code eps}.
     */
    static int droppedMask(final TerrainMeshInfo info, final double rx, final double ry, final double rz, final double eps) {
        int visible = info.visibleMask(rx, ry, rz, eps);
        int mask = 0;
        for (int slot = 0; slot < TerrainMeshInfo.SLOT_COUNT; slot++) {
            if (slot != TerrainMeshInfo.SLOT_UNASSIGNED && (visible & 1 << slot) == 0 && info.slotQuads(slot) > 0) {
                mask |= 1 << slot;
            }
        }

        return mask;
    }

    /**
     * Extends a sorted QUADS index buffer (vanilla layout: 6 indices per quad, the first being 4 x its vertex quad) as
     * described above, or returns null when nothing would be dropped or the buffer does not match {@code info}.
     */
    public static @Nullable Extended extend(
        final ByteBuffer sorted, final IndexType indexType, final @Nullable TerrainMeshInfo info, final double rx, final double ry, final double rz
    ) {
        if (info == null || info.quadSlots == null) {
            return null;
        }

        byte[] slots = info.quadSlots;
        int bytesPerIndex = indexType.bytes;
        int base = sorted.position();
        int length = sorted.remaining();
        int quads = length / (6 * bytesPerIndex);
        if (quads * 6 * bytesPerIndex != length || quads != slots.length) {
            return null;
        }

        int mask = droppedMask(info, rx, ry, rz, BUILD_EPS);
        if (mask == NO_SLOTS) {
            return null;
        }

        ByteBuffer in = sorted.duplicate().order(ByteOrder.nativeOrder());
        int quadBytes = 6 * bytesPerIndex;
        int kept = 0;
        int[] keptQuads = new int[quads];
        for (int q = 0; q < quads; q++) {
            int at = base + q * quadBytes;
            int first = bytesPerIndex == 2 ? in.getShort(at) & 0xFFFF : in.getInt(at);
            int src = first >>> 2;
            if (src >= quads || (first & 3) != 0) {
                return null;
            }

            if ((mask & 1 << slots[src]) == 0) {
                keptQuads[kept++] = q;
            }
        }

        if (kept == quads) {
            return null;
        }

        ByteBuffer out = MemoryUtil.memAlloc(length + kept * quadBytes);
        long src = MemoryUtil.memAddress(sorted);
        long dst = MemoryUtil.memAddress(out);
        MemoryUtil.memCopy(src, dst, length);
        for (int i = 0; i < kept; i++) {
            MemoryUtil.memCopy(src + (long)keptQuads[i] * quadBytes, dst + length + (long)i * quadBytes, quadBytes);
        }

        return new Extended(out, 6 * kept, mask);
    }

    public static void free(final Extended extended) {
        MemoryUtil.memFree(extended.buffer());
    }
}
