package bench;

import com.google.common.base.Stopwatch;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.telemetry.TelemetryProperty;
import net.minecraft.client.telemetry.events.GameLoadTimesEvent;
import net.minecraft.server.Bootstrap;

/**
 * Startup timeline (docs/stage2/STAGE2-PLAN.md W0c): when each launch phase ended, in ms since the java process started.
 * Driven by {@link Bench#onFrame()} (one guarded call per frame until the world is ready; nothing after it, so the
 * measured phases never run any of this). Written to {@code <bench.out>.startup.json} when the world is ready;
 * tools/launch.py merges it into the bench JSON as {@code "startup"} after the game exits (and deletes the side file).
 *
 * <p>Stamps ({@code "t"}, ms since process start, kernel start time of the java process):
 * <ul>
 *   <li>{@code jvmStart}: RuntimeMXBean start time (VM creation);</li>
 *   <li>{@code mainStart}: vanilla's own load-time stopwatch starts in {@code Main.main} (after option parsing and native
 *       library loading), read back through {@link GameLoadTimesEvent} at the first frame;</li>
 *   <li>{@code window}: the pre-window stopwatch stopped (window created, Minecraft.java:564), {@code bootstrapMs} is
 *       {@link Bootstrap#bootstrapDuration};</li>
 *   <li>{@code reloadStart}: the initial resource reload and its LoadingOverlay were created;</li>
 *   <li>{@code firstFrame}: the first renderFrame (end of the Minecraft constructor);</li>
 *   <li>{@code reloadDone}: the reload finished and the overlay started fading ({@code LoadingOverlay.fadeOutStart}; the
 *       frame time if that field is unavailable); quick play opens the world right here;</li>
 *   <li>{@code overlayGone}: the overlay was removed (vanilla: 2.0 s after reloadDone);</li>
 *   <li>{@code titleShown}: first frame with the title screen and no overlay (where the bench opens the world without
 *       quick play; absent with quick play);</li>
 *   <li>{@code titleWidgets}: first frame with the title screen, no overlay and its widgets fully shown
 *       ({@code TitleScreen.fading} false: vanilla fades them in over 2 s from the first title frame, which the overlay's
 *       fade-out already draws; stage-2 W7 shows them at once). Reached in vanilla's fade only with
 *       {@code -Dbench.titleSettle=true}, which makes the bench wait for it before opening the world;</li>
 *   <li>{@code worldOpen}: the bench opens (or creates) the world from the title screen (Bench.WAIT_TITLE; absent with quick
 *       play, where the game opens it itself);</li>
 *   <li>{@code serverCreated}: the integrated server object exists (singleplayer);</li>
 *   <li>{@code levelJoined}: client level and player exist;</li>
 *   <li>{@code worldReady}: the bench's world-ready moment (level, player, no screen, no overlay), the same instant as
 *       {@code secondsToWorldReady}.</li>
 * </ul>
 * {@code "phases"} are the differences (ms): jvm (process start to main), preWindow (bootstrap, window), init (window to
 * first frame), reload, fade, title (overlay gone to the world open: 0 for the title path unless bench.titleSettle waits for
 * the widgets, absent with quick play), open (world open to level joined), levelLoad (level joined to world ready),
 * openToReady (world open to world ready: title screen to playing, existing or new world), total (process start to world
 * ready).
 * {@code "cds"} says whether class data sharing (the AOT cache: this runtime ships no default CDS archive) is mapped.
 *
 * <p>{@code -Dbench.exitAfterReady=<seconds>}: stop the game that many seconds after the world is ready (startup
 * measurements, AOT training and shader warm-up runs: tools/launch.py); no bench result is written then, only the timeline.
 *
 * <p>{@code -Dbench.reloads=N} (stage-2 W7): after world ready, Bench's RELOADS state runs N resource reloads as F3+T does
 * ({@code Minecraft.reloadResourcePacks}), 1 s apart; {@code "reloads"} lists per reload {@code callbackMs} (request to the
 * completion callback, exact: taken in the callback), {@code overlayMs} (request to the first frame without the overlay) and
 * {@code terrainMs} (request to the first frame of a run of at least 100 ms in which the terrain the callback discarded is
 * back: section storage rebuilt, camera section compiled and faded in, compile queue empty; vanilla APIs plus a reflective
 * read of {@code LevelExtractor.shouldInvalidateCompiledGeometry}, so vanilla jars report it too). A reload is done once both
 * are taken.
 * The timeline is written again after the last reload; with exitAfterReady the exit timer starts there.
 */
public final class Startup {
    /** True until the timeline is written (and, with exitAfterReady, the game is stopped). Read by Bench.onFrame. */
    public static boolean pending = true;

    private static final double EXIT_AFTER_READY = Double.parseDouble(System.getProperty("bench.exitAfterReady", "-1"));
    private static final String OUT = System.getProperty("bench.out");
    /** bench.titleSettle: the bench opens the world only once the title widgets are fully shown (Bench.WAIT_TITLE). */
    static final boolean TITLE_SETTLE = Boolean.getBoolean("bench.titleSettle");
    /** bench.reloads: resource reloads after world ready (Bench.RELOADS). */
    static final int RELOADS = Integer.getInteger("bench.reloads", 0);
    private static final double RELOAD_TIMEOUT_S = 120.0;

    /** nanoTime -> epoch microseconds anchor, sampled once. */
    private static final long ANCHOR_NANO = System.nanoTime();
    private static final long ANCHOR_EPOCH_US = epochMicros(Instant.now());

    private static final Map<String, Long> stamps = new LinkedHashMap<>();   // name -> nanoTime
    private static long processStartEpochUs;
    private static long jvmStartEpochMs;
    private static long bootstrapMs = -1;
    private static String reloadDoneSource = "frame";
    private static boolean overlaySeen;
    private static long readyNs;
    private static boolean written;
    private static Field titleFading;
    private static boolean titleFadingBroken;
    private static final List<long[]> reloads = new ArrayList<>();   // {request, callback, overlay gone, terrain back} (nanoTime)
    private static long reloadRequestNs;
    private static volatile long reloadCallbackNs;
    private static long reloadsEndNs;   // last reload's overlay gone (0 until all RELOADS are done)
    private static boolean reloadActive;
    private static long reloadOverlayNs;   // 0 until the overlay is gone
    private static long terrainRunNs;      // start of the current run of terrain-back frames (0 = none)
    private static Field invalidationPending;

    private Startup() {
    }

    private static long epochMicros(Instant i) {
        return i.getEpochSecond() * 1_000_000L + i.getNano() / 1000L;
    }

    private static void stamp(String name, long nanoTime) {
        stamps.putIfAbsent(name, nanoTime);
    }

    /** Bench.onFrame, while {@link #pending}: {@code worldReadyNs} is Bench's (0 until the world is ready). */
    public static void onFrame(long worldReadyNs) {
        long now = System.nanoTime();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (stamps.isEmpty()) {
                first(now);
            }
            Overlay overlay = mc.gui.overlay();
            if (overlay != null) {
                overlaySeen = true;
            }
            if (!stamps.containsKey("reloadDone") && mc.isGameLoadFinished()) {
                stamp("reloadDone", reloadDoneNs(overlay, now));
            }
            if (overlaySeen && overlay == null) {
                stamp("overlayGone", now);
            }
            if (overlay == null && mc.gui.screen() instanceof TitleScreen title) {
                stamp("titleShown", now);
                if (!stamps.containsKey("titleWidgets") && !titleFading(title)) {
                    stamp("titleWidgets", now);
                }
            }
            if (mc.getSingleplayerServer() != null) {
                stamp("serverCreated", now);
            }
            if (mc.level != null && mc.player != null) {
                stamp("levelJoined", now);
            }
            if (worldReadyNs != 0L && readyNs == 0L) {
                readyNs = worldReadyNs;
                stamp("worldReady", worldReadyNs);
                write();
                if (EXIT_AFTER_READY < 0) {
                    pending = false;
                }
            }
            long exitFrom = RELOADS > 0 ? reloadsEndNs : readyNs;
            if (readyNs != 0L && exitFrom != 0L && EXIT_AFTER_READY >= 0 && (now - exitFrom) / 1e9 >= EXIT_AFTER_READY) {
                pending = false;
                System.out.println("[bench] startup run: stopping " + EXIT_AFTER_READY + " s after world ready"
                    + (RELOADS > 0 ? " and " + RELOADS + " reloads" : ""));
                mc.stop();
            }
        } catch (Throwable t) {
            pending = false;
            System.out.println("[bench] startup timeline disabled: " + t);
        }
    }

    /** Bench.WAIT_TITLE: the bench opens the world from the title screen now. */
    public static void worldOpen(long now) {
        stamp("worldOpen", now);
    }

    /** Whether the title screen still fades its widgets in (TitleScreen.fading, by reflection: vanilla and patched jars). */
    private static boolean titleFading(TitleScreen screen) {
        if (titleFadingBroken) {
            return false;
        }
        try {
            if (titleFading == null) {
                Field f = TitleScreen.class.getDeclaredField("fading");
                f.setAccessible(true);
                titleFading = f;
            }
            return titleFading.getBoolean(screen);
        } catch (Throwable t) {
            titleFadingBroken = true;
            System.out.println("[bench] startup: TitleScreen.fading unreadable, titleWidgets = titleShown: " + t);
            return false;
        }
    }

    /** Bench.WAIT_TITLE with bench.titleSettle: true while the title widgets are still fading in (the bench keeps waiting). */
    public static boolean titleStillFading(Screen screen) {
        return TITLE_SETTLE && screen instanceof TitleScreen title && titleFading(title);
    }

    /**
     * Bench.RELOADS (after world ready, before SETTLING): one resource reload at a time as F3+T, 1 s apart; true when all
     * {@link #RELOADS} are done (then the timeline is written again, with "reloads").
     */
    public static boolean stepReloads(Minecraft mc, long now) {
        if (reloadActive) {
            if (reloadCallbackNs != 0L) {
                if (reloadOverlayNs == 0L && mc.gui.overlay() == null) {
                    reloadOverlayNs = now;
                }
                if (terrainBack(mc)) {
                    terrainRunNs = terrainRunNs == 0L ? now : terrainRunNs;
                } else {
                    terrainRunNs = 0L;
                }
            }
            if (reloadOverlayNs != 0L && terrainRunNs != 0L && now - terrainRunNs >= 100_000_000L) {
                reloads.add(new long[]{reloadRequestNs, reloadCallbackNs, reloadOverlayNs, terrainRunNs});
                System.out.println(String.format(Locale.ROOT, "[bench] reload %d: callback %.0f ms, overlay gone %.0f ms, terrain back %.0f ms",
                    reloads.size(), (reloadCallbackNs - reloadRequestNs) / 1e6, (reloadOverlayNs - reloadRequestNs) / 1e6,
                    (terrainRunNs - reloadRequestNs) / 1e6));
                reloadActive = false;
                if (reloads.size() >= RELOADS) {
                    reloadsEndNs = now;   // both overlay gone and terrain back
                    written = false;
                    write();
                }
            } else if ((now - reloadRequestNs) / 1e9 > RELOAD_TIMEOUT_S) {
                throw new IllegalStateException("resource reload " + (reloads.size() + 1) + " did not finish in " + RELOAD_TIMEOUT_S + " s");
            }
            return false;
        }
        if (reloads.size() >= RELOADS) {
            return true;
        }
        long last = reloads.isEmpty() ? readyNs : Math.max(reloads.get(reloads.size() - 1)[2], reloads.get(reloads.size() - 1)[3]);
        if (now - last < 1_000_000_000L) {
            return false;
        }
        reloadRequestNs = System.nanoTime();
        reloadCallbackNs = 0L;
        reloadOverlayNs = 0L;
        terrainRunNs = 0L;
        reloadActive = true;
        mc.reloadResourcePacks().thenRun(() -> reloadCallbackNs = System.nanoTime());
        return false;
    }

    /** After a reload's callback: the discarded terrain is back (see the class comment, {@code terrainMs}). */
    private static boolean terrainBack(Minecraft mc) {
        if (mc.level == null) {
            return false;
        }
        try {
            if (invalidationPending == null) {
                invalidationPending = mc.levelExtractor.getClass().getDeclaredField("shouldInvalidateCompiledGeometry");
                invalidationPending.setAccessible(true);
            }
            if (invalidationPending.getBoolean(mc.levelExtractor)) {
                return false;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("LevelExtractor.shouldInvalidateCompiledGeometry unreadable", e);
        }
        if (!mc.levelRenderer.hasRenderedAllSections()) {
            return false;
        }
        net.minecraft.core.BlockPos camera = mc.gameRenderer.mainCamera().blockPosition();
        return mc.level.isOutsideBuildHeight(camera.getY())
            || mc.levelRenderer.isSectionCompiledAndVisible(camera, net.minecraft.util.Util.toMillis(mc.options.chunkSectionFadeInTime().get()));
    }

    private static void first(long now) {
        stamp("firstFrame", now);
        try {
            processStartEpochUs = ProcessHandle.current().info().startInstant().map(Startup::epochMicros).orElse(0L);
        } catch (Throwable ignored) {
        }
        try {
            jvmStartEpochMs = ManagementFactory.getRuntimeMXBean().getStartTime();
        } catch (Throwable ignored) {
        }
        bootstrapMs = Bootstrap.bootstrapDuration.get();
        try {
            // Vanilla's load-time stopwatches (GameLoadTimesEvent): still held here, the event is sent at reloadDone.
            Field f = GameLoadTimesEvent.class.getDeclaredField("measurements");
            f.setAccessible(true);
            Map<?, ?> m;
            synchronized (GameLoadTimesEvent.INSTANCE) {
                m = new LinkedHashMap<>((Map<?, ?>) f.get(GameLoadTimesEvent.INSTANCE));
            }
            Stopwatch total = (Stopwatch) m.get(TelemetryProperty.LOAD_TIME_TOTAL_TIME_MS);
            Stopwatch preWindow = (Stopwatch) m.get(TelemetryProperty.LOAD_TIME_PRE_WINDOW_MS);
            Stopwatch overlay = (Stopwatch) m.get(TelemetryProperty.LOAD_TIME_LOADING_OVERLAY_MS);
            if (total != null) {
                long mainStart = now - total.elapsed(TimeUnit.NANOSECONDS);
                stamps.put("mainStart", mainStart);
                if (preWindow != null && !preWindow.isRunning()) {
                    stamps.put("window", mainStart + preWindow.elapsed(TimeUnit.NANOSECONDS));
                }
            }
            if (overlay != null) {
                stamps.put("reloadStart", now - overlay.elapsed(TimeUnit.NANOSECONDS));
            }
        } catch (Throwable t) {
            System.out.println("[bench] startup: no vanilla load-time stopwatches: " + t);
        }
    }

    /**
     * LoadingOverlay.fadeOutStart (Util.getMillis() right after the reload's onFinish, which runs in the overlay's tick),
     * mapped onto System.nanoTime() through Util.getNanos() (its time source is not System.nanoTime() in this client);
     * else now. With quick play the world is opened inside onFinish, which renders frames of its own: the first such frame
     * sees the finished reload before fadeOutStart is set and keeps its own time ("frame").
     */
    private static long reloadDoneNs(Overlay overlay, long now) {
        if (overlay != null) {
            try {
                Field f = overlay.getClass().getDeclaredField("fadeOutStart");
                f.setAccessible(true);
                long ms = f.getLong(overlay);
                long offset = System.nanoTime() - net.minecraft.util.Util.getNanos();
                long ns = ms * 1_000_000L + offset;
                if (ms > 0L && ns <= now && now - ns < 10_000_000_000L) {
                    reloadDoneSource = "fadeOutStart";
                    return ns;
                }
            } catch (Throwable ignored) {
            }
        }
        return now;
    }

    /** ms since the process started for a nanoTime stamp. */
    private static double t(long nanoTime) {
        double epochUs = ANCHOR_EPOCH_US + (nanoTime - ANCHOR_NANO) / 1000.0;
        return (epochUs - processStartEpochUs) / 1000.0;
    }

    private static Double phase(String from, String to) {
        Long a = stamps.get(from);
        Long b = stamps.get(to);
        return a == null || b == null ? null : (b - a) / 1e6;
    }

    /** jdk.internal.misc.CDS.isUsingArchive() (JDK 25; exported to ALL-UNNAMED by Mojang's JVM flags). */
    static boolean cdsMapped() {
        try {
            return (Boolean) Class.forName("jdk.internal.misc.CDS").getMethod("isUsingArchive").invoke(null);
        } catch (Throwable t) {
            return false;
        }
    }

    static String json() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"t\": {");
        sb.append("\"processStart\": 0.0");
        if (jvmStartEpochMs != 0L && processStartEpochUs != 0L) {
            sb.append(String.format(Locale.ROOT, ", \"jvmStart\": %.1f", jvmStartEpochMs - processStartEpochUs / 1000.0));
        }
        stamps.entrySet().stream().sorted(Map.Entry.comparingByValue())
            .forEach(e -> sb.append(String.format(Locale.ROOT, ", \"%s\": %.1f", e.getKey(), t(e.getValue()))));
        sb.append("}, \"phases\": {");
        Map<String, Double> p = new LinkedHashMap<>();
        Long mainStart = stamps.get("mainStart");
        p.put("jvm", mainStart == null ? null : t(mainStart));
        p.put("preWindow", phase("mainStart", "window"));
        p.put("init", phase("window", "firstFrame"));
        p.put("reload", phase("reloadStart", "reloadDone"));
        p.put("fade", phase("reloadDone", "overlayGone"));
        String open = stamps.containsKey("worldOpen") ? "worldOpen" : stamps.containsKey("titleShown") ? "titleShown" : "reloadDone";
        p.put("title", stamps.containsKey("titleShown") ? phase("overlayGone", open) : null);
        p.put("open", phase(open, "levelJoined"));
        p.put("levelLoad", phase("levelJoined", "worldReady"));
        p.put("openToReady", phase(open, "worldReady"));
        p.put("firstFrameToReady", phase("firstFrame", "worldReady"));
        Long ready = stamps.get("worldReady");
        p.put("total", ready == null ? null : t(ready));
        boolean firstP = true;
        for (Map.Entry<String, Double> e : p.entrySet()) {
            if (e.getValue() == null || e.getValue().isNaN()) {
                continue;
            }
            sb.append(firstP ? "" : ", ").append(String.format(Locale.ROOT, "\"%s\": %.1f", e.getKey(), e.getValue()));
            firstP = false;
        }
        sb.append("}, ");
        sb.append("\"bootstrapMs\": ").append(bootstrapMs).append(", ");
        sb.append("\"reloadDoneFrom\": \"").append(reloadDoneSource).append("\", ");
        sb.append("\"quickPlay\": ").append(Boolean.getBoolean("bench.quickPlay")).append(", ");
        sb.append("\"cds\": ").append(cdsMapped());
        if (TITLE_SETTLE) {
            sb.append(", \"titleSettle\": true");
        }
        if (RELOADS > 0) {
            sb.append(", \"reloads\": [");
            for (int i = 0; i < reloads.size(); i++) {
                long[] r = reloads.get(i);
                sb.append(i == 0 ? "" : ", ").append(String.format(Locale.ROOT, "{\"callbackMs\": %.1f, \"overlayMs\": %.1f, \"terrainMs\": %.1f}",
                    (r[1] - r[0]) / 1e6, (r[2] - r[0]) / 1e6, (r[3] - r[0]) / 1e6));
            }
            sb.append("]");
        }
        sb.append("}");
        return sb.toString();
    }

    private static void write() {
        if (written || OUT == null) {
            return;
        }
        written = true;
        String j = json();
        try {
            Files.writeString(Path.of(OUT + ".startup.json"), j + "\n");
        } catch (Throwable t) {
            System.out.println("[bench] startup: cannot write " + OUT + ".startup.json: " + t);
        }
        System.out.println("[bench] startup " + j);
    }
}
