package bench;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Reads the optional stage-2 terrain counters of the patched client
 * ({@code net.minecraft.client.renderer.chunk.TerrainStats}, docs/stage2/TERRAIN-PLAN.md S0a) by reflection, so the
 * bench still runs on the vanilla jar (the class is absent there: {@code "terrain": null}). The counters only run with
 * {@code -Dmc.terrain.stats=true} (else {@code "terrain": {"enabled": false, ...}}). All values are per-frame means
 * over the MEASURE phase. Slot numbers mirror TerrainStats.
 */
final class TerrainStatsHook {
    private static final String STATS = "net.minecraft.client.renderer.chunk.TerrainStats";
    private static final String TOGGLES = "net.minecraft.client.renderer.chunk.TerrainToggles";
    private static final String[] TOGGLE_FIELDS = {
        "faceBuckets", "shortIndices", "exactFrustum", "cloudCull", "fastDrawList", "cloudRunCopy", "BRIDGE_QUADS", "SPLIT_PROBE",
        "cutoutFrontToBack", "solidFrontToBack", "sortCutoutSections", "sortSolidSections"
    };
    private static final long[] TOTAL;
    private static final Field FRAMES;
    private static final Method RESET;
    private static final boolean PRESENT;
    private static final boolean ENABLED;

    static {
        long[] total = null;
        Field frames = null;
        Method reset = null;
        boolean enabled = false;
        try {
            Class<?> c = Class.forName(STATS);
            enabled = c.getField("ENABLED").getBoolean(null);
            total = (long[]) c.getField("total").get(null);
            frames = c.getField("frames");
            reset = c.getMethod("reset");
        } catch (Throwable ignored) {
            total = null;
        }
        TOTAL = total;
        FRAMES = frames;
        RESET = reset;
        PRESENT = total != null && frames != null && reset != null;
        ENABLED = PRESENT && enabled;
    }

    private TerrainStatsHook() {
    }

    static boolean enabled() {
        return ENABLED;
    }

    static void reset() {
        if (ENABLED) {
            try {
                RESET.invoke(null);
            } catch (Throwable ignored) {
            }
        }
    }

    static long frames() {
        try {
            return ENABLED ? FRAMES.getLong(null) : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static long total(int slot) {
        return ENABLED && slot < TOTAL.length ? TOTAL[slot] : 0L;
    }

    /** SOLID + CUTOUT + TRANSLUCENT quads submitted so far. */
    static long terrainQuads() {
        return total(0) + total(1) + total(2);
    }

    /** The JSON value of the bench result's {@code "terrain"} key. */
    static String json() {
        if (!PRESENT) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"enabled\": ").append(ENABLED);
        sb.append(", \"toggles\": {");
        boolean first = true;
        try {
            Class<?> t = Class.forName(TOGGLES);
            for (String name : TOGGLE_FIELDS) {
                Object value;
                try {
                    value = t.getField(name).get(null);
                } catch (NoSuchFieldException absent) {
                    continue;   // older client jar
                }
                sb.append(first ? "" : ", ").append('"').append(name).append("\": ").append(value);
                first = false;
            }
        } catch (Throwable ignored) {
        }
        sb.append('}');
        if (ENABLED) {
            long frames = frames();
            double f = Math.max(1L, frames);
            long opaqueLayers = total(12);
            sb.append(", \"frames\": ").append(frames);
            field(sb, "sectionsPerFrame", total(4) / f);
            field(sb, "recordsPerFrame", total(3) / f);
            field(sb, "solidQuads", total(0) / f);
            field(sb, "cutoutQuads", total(1) / f);
            field(sb, "translucentQuads", total(2) / f);
            field(sb, "cloudQuads", total(5) / f);
            field(sb, "cloudQuadsTotal", total(15) / f);
            field(sb, "skippableFacing", (total(6) + total(7) + total(8)) / f);
            field(sb, "skippableFacingSolid", total(6) / f);
            field(sb, "skippableFacingCutout", total(7) / f);
            field(sb, "skippableFacingTranslucent", total(8) / f);
            field(sb, "skippableFrustum", total(9) / f);
            field(sb, "skippableFrustumLayers", total(14) / f);
            field(sb, "skippedFacing", total(16) / f);
            field(sb, "skippedFrustum", total(17) / f);
            field(sb, "bridgedQuads", total(13) / f);
            field(sb, "translucentTail", total(10) / f);
            field(sb, "compactLayerShare", opaqueLayers == 0 ? 0.0 : (double) total(11) / opaqueLayers);
            field(sb, "opaqueLayersPerFrame", opaqueLayers / f);
        }
        return sb.append('}').toString();
    }

    private static void field(StringBuilder sb, String key, double value) {
        sb.append(", \"").append(key).append("\": ").append(String.format(Locale.ROOT, "%.3f", value));
    }
}
