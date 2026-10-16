package net.minecraft.client.renderer.chunk;

import java.util.Arrays;

/**
 * S0a per-frame terrain counters (docs/stage2/TERRAIN-PLAN.md section 3.1). Off by default; enable with
 * {@code -Dmc.terrain.stats=true} (or the plan's {@code -Dterrain.stats=true}). Render thread only: {@link #add} is
 * called while the draw list is built, {@link #endFrame} once per frame from {@code LevelRenderer.endFrame}.
 * The bench harness reads {@link #total} / {@link #frames} by reflection (bench/src/bench/Bench.java), so the field
 * names and slot numbers are part of that interface.
 */
public final class TerrainStats {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("mc.terrain.stats", System.getProperty("terrain.stats", "false")));
    /** Quads submitted per layer (by {@code ChunkSectionLayer.ordinal()}: 0 SOLID, 1 CUTOUT, 2 TRANSLUCENT), bridged quads included. */
    public static final int QUADS_SOLID = 0;
    public static final int QUADS_CUTOUT = 1;
    public static final int QUADS_TRANSLUCENT = 2;
    /** Terrain draw records (indirect records, or separate draws on the non-MDI path). */
    public static final int RECORDS = 3;
    /** Sections with at least one drawn layer. */
    public static final int SECTIONS = 4;
    /** Cloud quads drawn. */
    public static final int CLOUD_QUADS = 5;
    /** Quads in buckets that face away from the camera (at bucket granularity), per layer; counted whether or not S1 culls them. */
    public static final int FACING_SKIPPABLE_SOLID = 6;
    public static final int FACING_SKIPPABLE_CUTOUT = 7;
    public static final int FACING_SKIPPABLE_TRANSLUCENT = 8;
    /** Quads in section-layers outside the exact frustum; counted whether or not S2 drops them. */
    public static final int FRUSTUM_SKIPPABLE = 9;
    /** Translucent quads outside the S3 head list (S3 not implemented: always 0). */
    public static final int TRANSLUCENT_TAIL = 10;
    /** Opaque section-layers exactly representable in the 16-byte vertex (S5 not implemented: always 0). */
    public static final int COMPACT_LAYERS = 11;
    /** Opaque section-layers seen (denominator of slot 11). */
    public static final int OPAQUE_LAYERS = 12;
    /** Back-facing quads drawn anyway because S1 bridged them. */
    public static final int BRIDGED_QUADS = 13;
    /** Section-layers dropped (or droppable) by the exact frustum. */
    public static final int FRUSTUM_SKIPPABLE_LAYERS = 14;
    /** Cloud quads in the full (unculled) cloud mesh. */
    public static final int CLOUD_QUADS_TOTAL = 15;
    /** Quads actually removed by S1 (facing) and S2 (frustum) this frame. */
    public static final int FACING_SKIPPED = 16;
    public static final int FRUSTUM_SKIPPED = 17;
    public static final int N = 18;
    private static final long[] frame = new long[N];
    public static final long[] total = new long[N];
    public static long frames;

    private TerrainStats() {
    }

    public static void add(final int slot, final long value) {
        frame[slot] += value;
    }

    public static void endFrame() {
        for (int i = 0; i < N; i++) {
            total[i] += frame[i];
            frame[i] = 0L;
        }

        frames++;
    }

    public static void reset() {
        Arrays.fill(total, 0L);
        frames = 0L;
    }
}
