package net.minecraft.client.renderer.chunk;

/**
 * TASK E switches (travel max-frame hitches, results/TAIL-ANALYSIS.md section 5 V2; docs/metal/CONTRACT.md 16.R5-E).
 * {@link #PREGROW} defaults to ON since round 5 (CONTRACT.md 16.R5-FIN, results/ROUND5.md; {@code -Dmc.terrain.pregrow=false}
 * is the off switch); {@link #UPLOAD_BUDGET_KB} defaults to 0 (off). With both off the code paths are exactly the previous
 * ones. They are read once at class initialisation from system properties; {@code -Dmc.terrain=false} (the terrain master
 * switch) also turns the terrain ones off.
 *
 * <ul>
 *   <li>{@link #PREGROW} ({@code -Dmc.terrain.pregrow}, default true): terrain uber-buffer heaps get a spare heap buffer before
 *       they run out of space (created and made GPU-resident on the Metal heap-reserve thread, or kept from a heap that
 *       became empty), so a new 128 MiB / 32 MiB heap never pays its first-use residency (~3.5 ms per 128 MiB on M5)
 *       inside a frame when the spare is ready; and the multi-draw-indirect command / section-info storages are sized
 *       from the render distance at world join instead of doubling mid-frame. Allocation placement, upload order and the
 *       image are unchanged.</li>
 *   <li>{@link #UPLOAD_BUDGET_KB} ({@code -Dmc.terrain.uploadBudgetKB=N}, 0 = unlimited = vanilla): at most about N KiB
 *       of new section meshes are uploaded per frame; the rest is carried over, but never by more than
 *       {@link #UPLOAD_MAX_DELAY_FRAMES} frames, never while the client waits for the player's section (level loading),
 *       never for meshes compiled on the render thread, and never once the staging buffer is half full.</li>
 * </ul>
 */
public final class TravelHitchToggles {
    private static final boolean MASTER = flag("mc.terrain", true);
    /** Spare uber-heap buffers ahead of need (resident before first use), draw storages pre-sized at world join. */
    public static final boolean PREGROW = MASTER && flag("mc.terrain.pregrow", true);
    /**
     * Pregrow low-water mark, as a fraction of one heap: a spare is requested once the free bytes of an uber buffer's
     * heaps fall below it. A spare is kept while the free bytes stay at or below three times this value.
     */
    public static final double PREGROW_FREE = clamp(doubleProperty("mc.terrain.pregrowFree", 0.25), 0.01, 1.0);
    /**
     * Upper bound of the spare heap buffers held by all uber buffers together ({@code -Dmc.terrain.pregrowSpareMB}, default
     * 320 = two vertex + two index heaps). The Metal heap reserve holds at most one more buffer per heap size.
     */
    public static final long PREGROW_MAX_SPARE_BYTES = Math.max(0L, Long.getLong("mc.terrain.pregrowSpareMB", 320L)) << 20;
    /**
     * Upload passes in a row with no heap in an uber buffer (a layer with nothing in view) after which its spare is given
     * back ({@code -Dmc.terrain.pregrowIdlePasses}, default 600, about one second): idle layers must not hold spares that
     * count against {@link #PREGROW_MAX_SPARE_BYTES} while another layer grows.
     */
    public static final int PREGROW_IDLE_PASSES = Math.max(1, Integer.getInteger("mc.terrain.pregrowIdlePasses", 600));
    /** Pre-sized multi-draw-indirect records (and section infos) per render-distance column: n * (2 rd + 1)^2. */
    public static final int PREGROW_DRAWS_PER_COLUMN = Math.max(0, Integer.getInteger("mc.terrain.pregrowDrawsPerColumn", 4));
    /** Per-frame terrain upload budget in KiB; 0 = unlimited (vanilla). */
    public static final int UPLOAD_BUDGET_KB = MASTER ? Math.max(0, Integer.getInteger("mc.terrain.uploadBudgetKB", 0)) : 0;
    /** Largest delay, in frames, of a carried-over upload relative to the frame vanilla would upload it in. */
    public static final int UPLOAD_MAX_DELAY_FRAMES = Math.max(0, Integer.getInteger("mc.terrain.uploadMaxDelayFrames", 2));
    /** Staging-buffer fill fraction at or above which a frame uploads everything (keeps the staging ring rotating). */
    public static final double UPLOAD_FLUSH_FILL = clamp(doubleProperty("mc.terrain.uploadFlushFill", 0.5), 0.0, 1.0);

    private TravelHitchToggles() {
    }

    private static boolean flag(final String name, final boolean defaultValue) {
        String value = System.getProperty(name);
        return value == null ? defaultValue : !value.equalsIgnoreCase("false") && !value.equals("0");
    }

    private static double doubleProperty(final String name, final double defaultValue) {
        String value = System.getProperty(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double clamp(final double value, final double min, final double max) {
        return Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }
}
