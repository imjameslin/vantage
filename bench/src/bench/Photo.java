package bench;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;

/**
 * Photo mode for sharing (not a measurement): -Dbench.photo=N shows the F3 debug overlay with the FPS chart from warmup
 * on and saves N full-resolution in-game screenshots (Minecraft's own Screenshot.grab of the main render target, HUD
 * included) during MEASURE, every bench.photo.every seconds (default 8, first at that time), into
 * bench.shots/screenshots/photo-<label>-<i>.png. Works on the vanilla jar too. The overlay costs FPS, so photo runs
 * are never used as benchmark numbers.
 *
 * -Dbench.f3 shows an overlay without screenshots (to measure what the overlay costs), and chooses what photo mode shows:
 *   full (or true)  the F3 overlay (the game dir's debug profile, normally the default one) plus the FPS chart (photo's
 *                   default); bench.f3.chart=false leaves the chart off
 *   fps             only the FPS line (the "fps" debug entry always on, every other entry off, overlay closed: what the
 *                   performance profile shows outside F3). Set in memory only: debug-profile.json is not written.
 * The overlay is switched on once, at the start of WARMUP, so the measured window is steady.
 */
final class Photo {
    static final int COUNT = Integer.getInteger("bench.photo", 0);
    static final double EVERY_S = Double.parseDouble(System.getProperty("bench.photo.every", "8"));
    /** Seconds into MEASURE before the first photo's interval starts (bench.photo.start, default 0): frame bursts at one spot. */
    static final double START_S = Double.parseDouble(System.getProperty("bench.photo.start", "0"));
    static final String F3 = System.getProperty("bench.f3", COUNT > 0 ? "full" : "off");
    static final boolean F3_FPS_ONLY = "fps".equals(F3);
    static final boolean F3_FULL = "full".equals(F3) || "true".equals(F3);
    static final boolean F3_CHART = Boolean.parseBoolean(System.getProperty("bench.f3.chart", "true"));
    private static boolean overlayOn;
    private static boolean lodOpaqueApplied;
    private static long startNs;
    private static int taken;

    /** Stage 3 zoom check: -Dbench.zoom=F holds the zoom key at magnification F from warmup on (10 = the spyglass's FOV). */
    static final double ZOOM = Double.parseDouble(System.getProperty("bench.zoom", "0"));

    private Photo() {
    }

    private static void holdZoom() {
        if (ZOOM > 0.0 && ZoomHook.available()) {
            ZoomHook.setFactor(ZOOM);
            ZoomHook.hold(ZoomHook.key(), true);
        }
    }

    static boolean enabled() {
        return COUNT > 0;
    }

    /** From WARMUP: turn the F3 overlay (and FPS chart) or the FPS line on once. */
    static void warmup(Minecraft mc) {
        applyLodOpaque();
        holdZoom();
        if (overlayOn || !(F3_FULL || F3_FPS_ONLY)) {
            return;
        }
        overlayOn = true;
        if (F3_FPS_ONLY) {
            fpsOnly(mc.debugEntries);
            System.out.println("[bench] f3: fps line only " + mc.debugEntries.getCurrentlyEnabled());
            return;
        }
        mc.debugEntries.setOverlayVisible(true);
        if (F3_CHART && !mc.getDebugOverlay().showFpsCharts()) {
            mc.getDebugOverlay().toggleFpsCharts();
        }
        System.out.println("[bench] f3: overlay " + mc.debugEntries.isOverlayVisible() + ", fps chart "
            + mc.getDebugOverlay().showFpsCharts() + ", entries " + mc.debugEntries.getCurrentlyEnabled());
    }

    /** Optional bench-only shader switch, applied once before measurements/photos; unset is vanilla-safe. */
    private static void applyLodOpaque() {
        if (lodOpaqueApplied) return;
        String requested = System.getProperty("bench.photo.lodOpaque");
        if (requested != null) {
            if (!"true".equalsIgnoreCase(requested) && !"false".equalsIgnoreCase(requested))
                throw new IllegalArgumentException("bench.photo.lodOpaque must be true or false");
            try {
                Field flag = Class.forName("net.minecraft.client.renderer.chunk.LodGpu").getField("asyncOpaque");
                int modifiers = flag.getModifiers();
                if (flag.getType() != boolean.class || !Modifier.isStatic(modifiers)
                        || !Modifier.isVolatile(modifiers) || Modifier.isFinal(modifiers))
                    throw new IllegalStateException("bench.photo.lodOpaque requires mutable static volatile LodGpu.asyncOpaque");
                flag.setAccessible(true);
                boolean value = Boolean.parseBoolean(requested);
                flag.setBoolean(null, value);
                if (flag.getBoolean(null) != value) throw new IllegalStateException("LodGpu.asyncOpaque readback failed");
                System.out.println("[bench] bench.photo.lodOpaque=" + value + "; LodGpu.asyncOpaque=" + flag.getBoolean(null));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("bench.photo.lodOpaque could not be applied", e);
            }
        }
        lodOpaqueApplied = true;
    }

    @SuppressWarnings("unchecked")
    private static void fpsOnly(DebugScreenEntryList list) {
        try {
            Field f = DebugScreenEntryList.class.getDeclaredField("allStatuses");
            f.setAccessible(true);
            Map<Identifier, DebugScreenEntryStatus> statuses = (Map<Identifier, DebugScreenEntryStatus>) f.get(list);
            statuses.clear();
            statuses.put(DebugScreenEntries.FPS, DebugScreenEntryStatus.ALWAYS_ON);
            list.setOverlayVisible(false);
            list.rebuildCurrentList();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("bench.f3=fps: " + e, e);
        }
    }

    /** From MEASURE, every frame. */
    static void measure(Minecraft mc, long now, String label) {
        holdZoom();
        if (!enabled() || taken >= COUNT) {
            return;
        }
        if (startNs == 0L) {
            startNs = now;
        }
        if ((now - startNs) / 1e9 >= START_S + EVERY_S * (taken + 1)) {
            taken++;
            File shots = new File(System.getProperty("bench.shots", "."));
            new File(shots, "screenshots").mkdirs();
            String name = "photo-" + label + "-" + taken + ".png";
            Screenshot.grab(shots, name, mc.gameRenderer.mainRenderTarget(), 1, msg -> {});
            System.out.println("[bench] photo " + taken + "/" + COUNT + ": " + name);
        }
    }
}
