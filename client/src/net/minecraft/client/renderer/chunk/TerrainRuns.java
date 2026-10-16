package net.minecraft.client.renderer.chunk;

import java.util.List;
import net.minecraft.client.renderer.DynamicGpuData;

/**
 * Draw-record emission for terrain section-layers that use the sequential quad index pattern (S1/S1b of
 * docs/stage2/TERRAIN-PLAN.md). A record always starts at index 0 of the quad index buffer and selects its quads
 * through {@code baseVertex}, so every record of a section-layer shares the section's vertex heap and therefore its
 * multi-draw group.
 */
public final class TerrainRuns {
    /** View-bob / float margin in blocks (plan section 2 row 9). */
    public static final double EPS = 0.25;
    /**
     * Quads per draw with the dedicated 16-bit terrain index buffer. 16383, not 16384: the largest index is then 65531,
     * so 0xFFFF (the 16-bit primitive-restart value Metal always honours) never occurs, as in vanilla's own 16-bit
     * buffers ({@code IndexType.least} only picks SHORT below 65536 vertices).
     */
    public static final int SHORT_INDEX_QUADS = 16383;
    public static final int NO_SPLIT = Integer.MAX_VALUE;

    private TerrainRuns() {
    }

    /**
     * Merges the wanted slots ({@code wantedMask}, empty slots ignored) into contiguous quad ranges in layout order.
     * Runs merge across empty slots, and across unwanted slots totalling at most {@code bridge} quads (those quads face
     * away from the camera, so the GPU culls them). Writes {@code (firstQuad, quadCount)} pairs into {@code out}
     * (capacity 8 suffices: at most 4 runs over 7 slots) and returns the number of runs.
     */
    public static int computeRuns(final int[] slotQuadStart, final int wantedMask, final int bridge, final int[] out) {
        int runs = 0;
        int runStart = -1;
        int runEnd = -1;

        for (int slot = 0; slot < TerrainMeshInfo.SLOT_COUNT; slot++) {
            int start = slotQuadStart[slot];
            int end = slotQuadStart[slot + 1];
            if (end == start || (wantedMask & 1 << slot) == 0) {
                continue;
            }

            if (runStart < 0) {
                runStart = start;
            } else if (start - runEnd > bridge) {
                out[2 * runs] = runStart;
                out[2 * runs + 1] = runEnd - runStart;
                runs++;
                runStart = start;
            }

            runEnd = end;
        }

        if (runStart >= 0) {
            out[2 * runs] = runStart;
            out[2 * runs + 1] = runEnd - runStart;
            runs++;
        }

        return runs;
    }

    /**
     * Emits the records for the buckets in {@code visibleMask} of a bucketed section-layer. Returns the number of quads
     * submitted (bridged back faces included).
     */
    public static int emit(
        final TerrainMeshInfo info,
        final int visibleMask,
        final int bridge,
        final int baseVertex,
        final int baseInstance,
        final int maxQuadsPerDraw,
        final int splitProbe,
        final int[] scratch,
        final List<DynamicGpuData.IndexedDraw> out
    ) {
        int runs = computeRuns(info.slotQuadStartArray(), visibleMask, bridge, scratch);
        int quads = 0;

        for (int r = 0; r < runs; r++) {
            int first = scratch[2 * r];
            int count = scratch[2 * r + 1];
            emitRange(first, count, baseVertex, baseInstance, maxQuadsPerDraw, splitProbe, out);
            quads += count;
        }

        return quads;
    }

    /** Emits a whole section-layer of {@code quads} quads (no culling), split as the index buffer requires. */
    public static int emitWhole(
        final int quads, final int baseVertex, final int baseInstance, final int maxQuadsPerDraw, final int splitProbe, final List<DynamicGpuData.IndexedDraw> out
    ) {
        emitRange(0, quads, baseVertex, baseInstance, maxQuadsPerDraw, splitProbe, out);
        return quads;
    }

    /**
     * Records for quads {@code [firstQuad, firstQuad + quads)}: chunks of at most {@code maxQuadsPerDraw} quads, each
     * chunk optionally split into {@code splitProbe} nearly equal parts (S0c probe). Same triangles, same order.
     */
    static void emitRange(
        final int firstQuad,
        final int quads,
        final int baseVertex,
        final int baseInstance,
        final int maxQuadsPerDraw,
        final int splitProbe,
        final List<DynamicGpuData.IndexedDraw> out
    ) {
        int done = 0;

        while (done < quads) {
            int chunk = Math.min(maxQuadsPerDraw, quads - done);
            int parts = Math.max(1, Math.min(splitProbe, chunk));
            int partStart = 0;

            for (int part = 0; part < parts; part++) {
                int partEnd = (int)((long)chunk * (part + 1) / parts);
                int partQuads = partEnd - partStart;
                out.add(new DynamicGpuData.IndexedDraw(6 * partQuads, 1, 0, baseVertex + 4 * (firstQuad + done + partStart), baseInstance));
                partStart = partEnd;
            }

            done += chunk;
        }
    }
}
