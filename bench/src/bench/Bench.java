package bench;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Deterministic in-game benchmark. Opens (or creates) a fixed-seed creative world,
 * lifts the player above spawn, waits for every visible chunk section to be built,
 * then spins the camera at a constant angular speed while recording the time
 * between consecutive frames. Results are written as JSON and the game exits.
 *
 * System properties:
 *   bench.out      result file (required)
 *   bench.label    free-form label stored in the result
 *   bench.world    world folder name (default "bench")
 *   bench.seed     world seed (default 4242)
 *   bench.warmup   seconds to spin before measuring (default 15)
 *   bench.seconds  seconds to measure (default 30)
 *   bench.spin     seconds per full 360° turn (default 10; Infinity holds the exact yaw)
 *   bench.pitch    camera pitch in degrees (default 12)
 *   bench.yaw      yaw at the start of the spin, degrees (default 0); bench.spin=Infinity for a fixed view
 *   bench.mode     perf (default), travel, scenes (Scenes: parity screenshots), zoomseq (ZoomSeq: zoom frame sequence),
 *                  reuseseq (ReuseSequence: off/on Lockstep trajectory captures; no image-gate claim),
 *                  soak (Soak: flight + window resizing / fullscreen toggling, memory time series and verdict),
 *                  abab (Abab: in-session A/B, runtime toggles switched at frame boundaries; bench.abab.*, CONTRACT 16.AB)
 *   bench.join     host:port: multiplayer guest (the game joins with --quickPlayMultiplayer; no world is created or
 *                  opened; soak mode only; tools/soak-lan.sh)
 *   bench.quickPlay  true: the game was started with --quickPlaySingleplayer bench.world (tools/launch.py, existing worlds)
 *                  and opens it itself at the end of the resource reload; the bench waits for the level as for bench.join
 *   bench.exitAfterReady  seconds: stop the game that long after world ready (Startup: startup runs, AOT training, warm-up)
 *   bench.shots    scenes/zoomseq/reuseseq: folder that receives screenshots/ (default: the game directory)
 *   bench.only     scenes: comma-separated scene names to run (default all)
 *   bench.zoomClass, bench.zoomKey   zoom feature lookup (ZoomHook); bench.zoomseq.*: see ZoomSeq
 *   bench.titleSettle  true: on the title path, open the world only once the title screen's widgets are fully shown
 *                  (vanilla fades them in over 2 s; stage-2 W7 shows them at once); Startup stamps titleWidgets
 *   bench.reloads  N: N resource reloads (as F3+T) after world ready, 1 s apart, timed (Startup "reloads"; perf / travel /
 *                  startup runs; with exitAfterReady its timer starts after the last reload)
 *
 * With the patched client and -Dmc.terrain.stats=true the result also carries a "terrain" block of per-frame
 * terrain counters and "perSecondTerrainQuads" (see TerrainStatsHook); on vanilla "terrain" is null. "lateThrottle"
 * records the Metal late frame throttle's toggle and guard-trip counters (ThrottleStatsHook): null only on the vanilla
 * jar; on the patched jar it is an object whose "active" is false on Vulkan/OpenGL or with the toggle off.
 *
 * Every result carries a "config" block of the effective configuration (ConfigBlock; bench.config=false omits it).
 *   bench.frameTrace       true: per-frame trace of the MEASURE phase ("frameTrace" block, FrameTrace; probes inserted
 *                          by BenchAgent; implies -Dmcmetal.frameTrace=true). Analyse with tools/frame_trace.py.
 *   bench.frameTrace.max   trace rows preallocated (default seconds x 2500 + 4096); bench.frameTrace.cpu=false: no
 *                          thread-CPU columns; bench.guardTrips=fqcn#member: late-throttle guard-trip counter source.
 */
public final class Bench {
    private enum State { WAIT_TITLE, LOADING, RELOADS, SETTLING, WARMUP, MEASURE, SCENES, SOAK, JOIN, PREGEN, SNAPSHOT, REUSESEQ, DONE }

    private static final String OUT = System.getProperty("bench.out");
    private static final String LABEL = System.getProperty("bench.label", "unnamed");
    private static final String WORLD = System.getProperty("bench.world", "bench");
    private static final long SEED = Long.getLong("bench.seed", 4242L);
    private static final double WARMUP_S = Double.parseDouble(System.getProperty("bench.warmup", "15"));
    private static final double MEASURE_S = Double.parseDouble(System.getProperty("bench.seconds", "30"));
    private static final double SPIN_S = Double.parseDouble(System.getProperty("bench.spin", "10"));
    private static final float PITCH = Float.parseFloat(System.getProperty("bench.pitch", "12"));
    /** Yaw at the start of the spin (degrees); bench.spin=Infinity holds this direction exactly. */
    private static final float BASE_YAW = Float.parseFloat(System.getProperty("bench.yaw", "0"));
    private static final String MODE = System.getProperty("bench.mode", "perf");
    /** "travel": fly east at ground level through a freshly generated world (realistic exploration load). */
    private static final boolean TRAVEL = "travel".equals(MODE) || Abab.TRAVEL;
    private static final double TRAVEL_SPEED = Double.parseDouble(System.getProperty("bench.speed", "10.9"));
    /** Travel: height above the surface (blocks), camera pitch, and a yaw sweep (+-degrees over a period, s) to look around. */
    private static final double TRAVEL_HEIGHT = Double.parseDouble(System.getProperty("bench.travel.height", "3"));
    private static final float TRAVEL_PITCH = Float.parseFloat(System.getProperty("bench.travel.pitch", "5"));
    private static final double TRAVEL_SWEEP = Double.parseDouble(System.getProperty("bench.travel.sweep", "0"));
    /** Travel: start this many blocks away (x and z) from the spawn, over never-generated land (a fresh world's first flight). */
    private static final double TRAVEL_OFFSET = Double.parseDouble(System.getProperty("bench.travel.offset", "0"));
    private static final double TRAVEL_SWEEP_S = Double.parseDouble(System.getProperty("bench.travel.sweepPeriod", "8"));
    private static double travelX0, travelZ0, travelY;
    private static long travelStartNs, travelLastNs;
    private static FlightLoop flightLoop;
    private static Scenes scenes;
    private static ZoomSeq zoomSeq;
    private static ReuseSequence reuseSequence;
    /** "soak": Soak drives the run and writes its own result. */
    private static final boolean SOAK = "soak".equals(MODE);
    private static final String JOIN = System.getProperty("bench.join");
    /** The game was started with --quickPlaySingleplayer bench.world (tools/launch.py): no title screen, no openWorld here. */
    private static final boolean QUICK_PLAY = Boolean.getBoolean("bench.quickPlay");
    private static volatile Soak soak;

    private static State state = State.WAIT_TITLE;
    private static final long launchNs = System.nanoTime();
    private static long stateStartNs;
    private static long lastFrameNs;
    private static long worldReadyNs;
    private static float baseYaw;
    private static long[] frameTimes = new long[1 << 21];
    private static int frameCount;
    private static final List<Integer> perSecond = new ArrayList<>();
    private static final List<Double> perSecondTerrainQuads = new ArrayList<>();
    private static int framesThisSecond;
    private static long secondStartNs;
    private static long terrainQuadsAtSecond;
    private static long terrainFramesAtSecond;
    private static boolean tracePrepared;
    private static long lodLogNs;
    private static double lodSettleSeconds;

    static {
        // 10 minutes for every mode but soak, whose run is soak.minutes long (plus world creation / joining).
        long baseMs = SOAK ? (long) ((Soak.MINUTES * 60.0 + 900.0) * 1000.0) : Abab.ENABLED ? Abab.watchdogMs() : 600_000L;
        // Stage 3: LOD runs wait for the LOD to settle (cold store: minutes) and the fixture generation takes longer:
        // -Dbench.timeout=<s> raises the limit.
        long limitMs = Math.max(baseMs, (long) (Double.parseDouble(System.getProperty("bench.timeout", "0")) * 1000.0));
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(limitMs);
            } catch (InterruptedException ignored) {
                return;
            }
            if (state != State.DONE) {
                if (soak != null) {
                    soak.fail(null, "watchdog timeout in state " + state);
                } else {
                    writeFailure("timeout in state " + state);
                }
                Runtime.getRuntime().halt(3);
            }
        }, "bench-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private Bench() {
    }

    public static void onFrame() {
        if (OUT == null || state == State.DONE) {
            return;
        }
        if (Startup.pending) {
            Startup.onFrame(worldReadyNs);   // startup timeline (W0c); false from world ready on
        }
        try {
            if (ChunkStatsHook.ENABLED && ChunkStatsHook.onFrame(Minecraft.getInstance(), System.nanoTime()) && state == State.JOIN) {
                writeJoin(Minecraft.getInstance());
                state = State.DONE;
                Minecraft.getInstance().stop();
                return;
            }
            step(Minecraft.getInstance());
        } catch (Throwable t) {
            t.printStackTrace();
            if (soak != null) {
                soak.fail(Minecraft.getInstance(), t.toString());
            } else {
                writeFailure(t.toString());
            }
            state = State.DONE;
            Minecraft.getInstance().stop();
        }
    }

    private static void enter(State next) {
        state = next;
        stateStartNs = System.nanoTime();
        System.out.println("[bench] -> " + next);
    }

    private static double secondsInState() {
        return (System.nanoTime() - stateStartNs) / 1e9;
    }

    private static void step(Minecraft mc) {
        long now = System.nanoTime();
        switch (state) {
            case WAIT_TITLE -> {
                Screen screen = mc.gui.screen();
                if (JOIN != null || QUICK_PLAY) {
                    // Quick play: --quickPlayMultiplayer (guest) / --quickPlaySingleplayer <world> (tools/launch.py) opens the
                    // world by itself when the resource reload finishes; wait for the level, fail on a disconnect / error screen.
                    String what = JOIN != null ? "join " + JOIN : "quick play " + WORLD;
                    if (mc.level != null && mc.player != null) {
                        System.out.println("[bench] " + what + ": in the world after " + Math.round((now - launchNs) / 1e9) + " s");
                        enter(State.LOADING);
                    } else if (screen != null && screen.getClass().getSimpleName().contains("Disconnect")
                        || (now - launchNs) / 1e9 > 600.0) {
                        String why = what + " failed: screen " + (screen == null ? "none" : screen.getClass().getSimpleName() + " '"
                            + screen.getTitle().getString() + "'");
                        System.out.println("[bench] " + why);
                        writeFailure(why);
                        state = State.DONE;
                        mc.stop();
                    }
                    return;
                }
                if (screen instanceof TitleScreen && mc.gui.overlay() == null && !Startup.titleStillFading(screen)) {
                    Startup.worldOpen(now);
                    enter(State.LOADING);
                    if (mc.getLevelSource().levelExists(WORLD)) {
                        mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.gui.setScreen(screen));
                    } else {
                        LevelSettings settings = new LevelSettings(
                            WORLD, GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
                        mc.createWorldOpenFlows().createFreshLevel(
                            WORLD, settings, new WorldOptions(SEED, true, false), WorldPresets::createNormalWorldDimensions, screen);
                    }
                }
            }
            case LOADING -> {
                if (ChunkStatsHook.JOIN && mc.level != null && mc.player != null) {
                    // Stage-2 W0b join run: no commands, no teleport; hold the view until ChunkStatsHook says all built.
                    worldReadyNs = now;
                    enter(State.JOIN);
                    aimYaw(mc, BASE_YAW);
                    return;
                }
                if (mc.level != null && mc.player != null && mc.gui.screen() == null && mc.gui.overlay() == null) {
                    LocalPlayer player = mc.player;
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    player.connection.sendCommand("time set 6000");
                    player.connection.sendCommand("weather clear");
                    // Stage 3 r2 (fog checks): extra commands after the default time / weather, ';'-separated
                    // (e.g. "time set 18000;weather rain"). Use only on a scratch copy of a world: they are saved.
                    for (String c : System.getProperty("bench.cmds", "").split(";")) {
                        if (!c.isBlank()) {
                            player.connection.sendCommand(c.trim());
                        }
                    }
                    if (SOAK) {
                        if (!player.isCreative()) {
                            player.connection.sendCommand("gamemode creative");
                        }
                        worldReadyNs = now;
                        soak = new Soak(mc, LABEL, OUT);
                        enter(State.SOAK);
                        return;
                    }
                    if ("lodpregen".equals(MODE)) {
                        worldReadyNs = now;
                        LodHook.pregen(mc);
                        enter(State.PREGEN);
                        return;
                    }
                    if ("reuseseq".equals(MODE)) {
                        worldReadyNs = now;
                        reuseSequence = new ReuseSequence(new java.io.File(System.getProperty("bench.shots", ".")));
                        enter(State.REUSESEQ);
                        return;
                    }
                    double[] anchor = TRAVEL ? null : anchor(mc);
                    if (TRAVEL) {
                        travelX0 = player.getX() + TRAVEL_OFFSET;
                        travelZ0 = player.getZ() + TRAVEL_OFFSET;
                        travelY = TRAVEL_OFFSET != 0.0 ? 64.0 + TRAVEL_HEIGHT
                            : mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                                player.getBlockX(), player.getBlockZ()) + TRAVEL_HEIGHT;
                        travelStartNs = 0L;
                    } else {
                        player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.1f %.1f %.1f 0 %.1f", anchor[0], anchor[1], anchor[2], PITCH));
                    }
                    baseYaw = BASE_YAW;
                    worldReadyNs = now;
                    if ("scenes".equals(MODE) || "zoomseq".equals(MODE)) {
                        for (String rule : new String[]{"send_command_feedback", "spawn_mobs", "advance_time", "advance_weather"}) {
                            player.connection.sendCommand("gamerule " + rule + " false");
                        }
                        // The world must change only through the scene commands (CONTRACT 11.6): no snow from the rain and
                        // thunder scenes (it landed at random on leaves and on water_surface's sand, and was saved), and no
                        // random block ticks (ice forming on water edges, which needs no rain, plant growth).
                        player.connection.sendCommand("gamerule max_snow_accumulation_height 0");
                        player.connection.sendCommand("gamerule random_tick_speed 0");
                        player.connection.sendCommand("kill @e[type=!minecraft:player]");
                        java.io.File shots = new java.io.File(System.getProperty("bench.shots", "."));
                        if ("scenes".equals(MODE)) {
                            scenes = new Scenes(anchor, shots);
                        } else {
                            zoomSeq = new ZoomSeq(anchor, shots);
                        }
                        enter(State.SCENES);
                    } else {
                        enter(Startup.RELOADS > 0 ? State.RELOADS : State.SETTLING);
                    }
                }
            }
            case RELOADS -> {
                dismissPause(mc);
                aim(mc, 0.0);
                if (Startup.stepReloads(mc, now)) {
                    enter(State.SETTLING);
                }
            }
            case SETTLING -> {
                dismissPause(mc);
                aim(mc, 0.0);
                // Stage 3: with the LOD system built, also wait until every LOD job is done (bench.lodWait cap).
                boolean lodSettled = !LodHook.active() || Boolean.getBoolean("bench.noLodSettle") || LodHook.idle() && secondsInState() > 12.0;
                if (LodHook.active() && (now - lodLogNs) > 10_000_000_000L) {
                    lodLogNs = now;
                    System.out.println("[bench] lod: " + LodHook.json());
                }
                if (secondsInState() > 8.0 && mc.levelRenderer.hasRenderedAllSections() && lodSettled) {
                    if (LodHook.active()) {
                        lodSettleSeconds = secondsInState();
                        System.out.println("[bench] lod settled after " + Math.round(lodSettleSeconds) + " s: " + LodHook.json());
                    }
                    enter(State.WARMUP);
                }
                if (secondsInState() > (LodHook.active() ? Math.max(120.0, LodHook.WAIT_S) : 120.0)) {
                    if (LodHook.active()) {
                        lodSettleSeconds = -secondsInState();
                        System.out.println("[bench] lod NOT settled: " + LodHook.json());
                    }
                    enter(State.WARMUP);
                }
            }
            case PREGEN -> {
                dismissPause(mc);
                if (LodHook.pregenDone) {
                    writeDone("lodpregen complete", "\"pregen\": " + LodHook.pregenResult);
                    state = State.DONE;
                    mc.stop();
                }
            }
            case WARMUP -> {
                dismissPause(mc);
                if (TRAVEL && travelStartNs == 0L) {
                    travelStartNs = now;
                    travelLastNs = now;
                    if (Abab.TRAVEL_LOOP) {
                        flightLoop = new FlightLoop(travelX0, travelZ0, TRAVEL_SPEED, Abab.BLOCK_S);
                        // Freeze altitude above the entire loaded route before warmup; no frame-rate-dependent climb.
                        double high = travelY;
                        for (int x = (int) Math.floor(travelX0 - flightLoop.radius); x <= Math.ceil(travelX0 + flightLoop.radius); x++) {
                            for (int z = (int) Math.floor(travelZ0); z <= Math.ceil(travelZ0 + 2 * flightLoop.radius); z++) {
                                if (!mc.level.hasChunk(x >> 4, z >> 4)) throw new IllegalStateException("travelLoop route not loaded");
                                high = Math.max(high, mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) + 3.0);
                            }
                        }
                        travelY = high;
                    }
                }
                if (!tracePrepared) {
                    // Resolve / allocate / bind everything the trace and config block need, outside the measured window.
                    tracePrepared = true;
                    ConfigBlock.prepare();
                    if (Abab.ENABLED) {
                        Abab.prepare();
                    }
                    if (FrameTrace.ENABLED) {
                        FrameTrace.prepare(Abab.ENABLED ? Abab.plannedSeconds() : MEASURE_S, TRAVEL);
                    }
                }
                if (Abab.ENABLED) {
                    // Both arms alternate during warmup (the JIT sees both paths); the heavy preset holds its view.
                    Abab.warmup(secondsInState());
                    if (Abab.HEAVY) {
                        aimYaw(mc, (float) Abab.HEAVY_YAW);
                    } else {
                        aim(mc, secondsInState());
                    }
                } else {
                    aim(mc, secondsInState());
                }
                Photo.warmup(mc);
                if (secondsInState() >= WARMUP_S) {
                    if ("lodsnapshot".equals(MODE)) {
                        if (lodSettleSeconds <= 0 || !LodHook.active() || !LodHook.idle()) {
                            throw new IllegalStateException("LOD snapshot requires a settled, active renderer");
                        }
                        LodSnapshot.arm();
                        enter(State.SNAPSHOT);
                        break;
                    }
                    // Round-5 snapshots (config counters, late-throttle counters, trace begin) are taken first and the
                    // measurement origin is sampled after them, so none of their cost lands in frameTimes[0] (r5-int
                    // review fix). From here on the code is r5-base's, with t0 in place of the frame's `now`.
                    ConfigBlock.begin();
                    ThrottleStatsHook.reset();
                    if (FrameTrace.ENABLED) {
                        FrameTrace.begin(System.nanoTime());
                    }
                    long t0 = System.nanoTime();
                    if (FrameTrace.ENABLED) {
                        FrameTrace.setOrigin(t0);
                    }
                    if (Abab.ENABLED) {
                        Abab.begin(t0);   // block 0's arm; its first abab.discard frames are not analysed
                    }
                    // Stage 3 r6: commands at the start of the measurement (e.g. "time set 6000": photo pairs whose settles differ in
                    // length otherwise see the sun at different heights).
                    for (String c : System.getProperty("bench.measureCmds", "").split(";")) {
                        if (!c.isBlank() && mc.player != null) {
                            mc.player.connection.sendCommand(c.trim());
                        }
                    }
                    enter(State.MEASURE);
                    frameCount = 0;
                    framesThisSecond = 0;
                    secondStartNs = t0;
                    lastFrameNs = t0;
                    TerrainStatsHook.reset();
                    PresentStatsHook.reset();
                    ChunkStatsHook.beginMeasure();
                    terrainQuadsAtSecond = 0L;
                    terrainFramesAtSecond = 0L;
                }
            }
            case SNAPSHOT -> {
                if (LodSnapshot.finished()) {
                    writeDone(LodSnapshot.succeeded() ? "lodsnapshot complete" : "lodsnapshot failed",
                        "\"snapshot\": " + LodSnapshot.resultJson());
                    state = State.DONE;
                    mc.stop();
                } else if (secondsInState() > 150) {
                    throw new IllegalStateException("LOD snapshot hooks did not finish");
                }
            }
            case REUSESEQ -> {
                if (reuseSequence.step(mc)) {
                    writeDone("reuseseq finished; image gate not evaluated", "\"reuseseq\": " + reuseSequence.resultJson());
                    state = State.DONE;
                    mc.stop();
                }
            }
            case MEASURE -> {
                dismissPause(mc);
                Photo.measure(mc, now, LABEL);
                Recorder.measure(mc, now, LABEL);
                long dt = now - lastFrameNs;
                lastFrameNs = now;
                if (Abab.ENABLED) {
                    // In-session A/B: Abab owns the blocks, the arm switches (here, at the frame boundary) and the camera.
                    if (frameCount < frameTimes.length) {
                        frameTimes[frameCount++] = dt;
                        if (FrameTrace.ENABLED) {
                            LocalPlayer p = mc.player;
                            FrameTrace.record(now, dt, p == null ? 0.0 : TRAVEL ? p.getX() : p.getYRot());
                        }
                    }
                    Abab.record(now, dt);
                    if (Abab.step(now)) {
                        writeResults(mc);
                        state = State.DONE;
                        mc.stop();
                        return;
                    }
                    if (TRAVEL) {
                        aim(mc, 0.0);   // stage 3 r6: abab.preset=travel flies east as the travel mode does
                    } else {
                        aimYaw(mc, Abab.yaw(System.nanoTime()));
                    }
                    return;
                }
                if (frameCount > 0 || dt > 0) {
                    if (frameCount < frameTimes.length) {
                        frameTimes[frameCount++] = dt;
                        if (FrameTrace.ENABLED) {
                            // Row k = frameTimes[k]. The view the finished frame used: aim() below sets the next one.
                            LocalPlayer p = mc.player;
                            FrameTrace.record(now, dt, p == null ? 0.0 : TRAVEL ? p.getX() : p.getYRot());
                        }
                    }
                }
                framesThisSecond++;
                if (now - secondStartNs >= 1_000_000_000L) {
                    perSecond.add(framesThisSecond);
                    framesThisSecond = 0;
                    secondStartNs += 1_000_000_000L;
                    if (TerrainStatsHook.enabled()) {
                        long quads = TerrainStatsHook.terrainQuads();
                        long frames = TerrainStatsHook.frames();
                        long dFrames = frames - terrainFramesAtSecond;
                        perSecondTerrainQuads.add(dFrames > 0 ? (double) (quads - terrainQuadsAtSecond) / dFrames : 0.0);
                        terrainQuadsAtSecond = quads;
                        terrainFramesAtSecond = frames;
                    }
                }
                aim(mc, secondsInState());
                if (secondsInState() >= MEASURE_S) {
                    writeResults(mc);
                    state = State.DONE;
                    mc.stop();
                }
            }
            case SCENES -> {
                if (scenes != null ? scenes.step(mc) : zoomSeq.step(mc)) {
                    // Which pinned world the run started from (tools/launch.py: "<snapshot>@<id>"; "none": the game dir's own).
                    String extra = "\"pinnedWorld\": " + FrameTrace.quote(System.getProperty("bench.pinnedWorld", "none"));
                    if (scenes != null && !scenes.entityChecks.isEmpty()) {
                        StringBuilder checks = new StringBuilder();
                        scenes.entityChecks.forEach((k, v) -> checks.append(checks.isEmpty() ? "" : ", ").append('"').append(k).append("\": ").append(v));
                        extra += ", \"entityChecks\": {" + checks + "}";
                    }
                    writeDone(MODE + " complete", extra);
                    state = State.DONE;
                    mc.stop();
                }
            }
            case SOAK -> {
                dismissPause(mc);
                if (soak.step(mc)) {
                    soak.finish(mc);
                    state = State.DONE;
                    mc.stop();
                }
            }
            case JOIN -> {
                dismissPause(mc);
                aimYaw(mc, BASE_YAW);
                if (secondsInState() > 300.0) {
                    writeFailure("join: not all built after 300 s");
                    state = State.DONE;
                    mc.stop();
                }
            }
            case DONE -> {
            }
        }
    }

    /**
     * Absolute camera anchor for this world, fixed on first use: 40 blocks above the
     * terrain at the player's initial spawn column. Stored in the world folder so that
     * every run (and every backend) renders exactly the same scene.
     */
    private static double[] anchor(Minecraft mc) {
        // Stage 3 photo / vista runs: -Dbench.pos=x,y,z replaces the world's anchor (not written to it).
        String pos = System.getProperty("bench.pos");
        if (pos != null && !pos.isBlank()) {
            String[] p = pos.split(",");
            return new double[]{Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim()), Double.parseDouble(p[2].trim())};
        }
        Path file = mc.getLevelSource().getLevelPath(WORLD).resolve("bench-anchor.txt");
        try {
            if (Files.exists(file)) {
                String[] p = Files.readString(file).trim().split(" ");
                return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])};
            }
            int x = mc.player.getBlockX();
            int z = mc.player.getBlockZ();
            int ground = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
            double[] a = {x + 0.5, ground + 40.0, z + 0.5};
            Files.writeString(file, a[0] + " " + a[1] + " " + a[2] + "\n");
            return a;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void dismissPause(Minecraft mc) {
        if (mc.gui.screen() instanceof PauseScreen) {
            mc.gui.setScreen(null);
        }
    }

    private static void aim(Minecraft mc, double seconds) {
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        if (TRAVEL) {
            travel(mc, player);
            return;
        }
        aimYaw(mc, baseYaw + (float) (360.0 * (seconds / SPIN_S) % 360.0));
    }

    /** Sets the camera to {@code yaw} at the bench pitch (spin and abab modes). */
    private static void aimYaw(Minecraft mc, float yaw) {
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        player.setYRot(yaw);
        player.yRotO = yaw;
        player.setXRot(PITCH);
        player.xRotO = PITCH;
    }

    /** Constant-speed flight east, following the terrain 3 blocks above the surface (rate-limited climb). */
    private static void travel(Minecraft mc, LocalPlayer player) {
        long now = System.nanoTime();
        double t = travelStartNs == 0L ? 0.0 : (now - travelStartNs) / 1e9;
        double dt = travelLastNs == 0L ? 0.0 : Math.min(0.1, (now - travelLastNs) / 1e9);
        travelLastNs = now;
        double x = travelX0 + TRAVEL_SPEED * t;
        double z = travelZ0;
        float yaw = -90.0F;
        if (Abab.TRAVEL_LOOP) {
            if (flightLoop != null) {
                flightLoop.at(state == State.MEASURE ? Abab.loopSeconds(now) : t);
                x = flightLoop.x;
                z = flightLoop.z;
                yaw = flightLoop.yaw;
            } else {
                x = travelX0;
            }
        }
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!Abab.TRAVEL_LOOP && mc.level != null && mc.level.hasChunk(bx >> 4, bz >> 4)) {
            double target = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, bx, bz) + TRAVEL_HEIGHT;
            double step = 12.0 * dt;
            travelY += Math.max(-step, Math.min(step, target - travelY));
        }
        if (!Abab.TRAVEL_LOOP && TRAVEL_SWEEP != 0.0) {
            yaw += (float) (TRAVEL_SWEEP * Math.sin(2.0 * Math.PI * t / TRAVEL_SWEEP_S));
        }
        float pitch = Abab.TRAVEL_LOOP ? 5.0F : TRAVEL_PITCH;
        player.getAbilities().flying = true;
        player.setDeltaMovement(0.0, 0.0, 0.0);
        player.snapTo(x, travelY, z, yaw, pitch);
        player.xo = x;
        player.yo = travelY;
        player.zo = z;
        player.setYRot(yaw);
        player.yRotO = yaw;
        player.setXRot(pitch);
        player.xRotO = pitch;
    }

    private static void writeResults(Minecraft mc) {
        long[] ft = Arrays.copyOf(frameTimes, frameCount);
        long total = 0;
        for (long t : ft) {
            total += t;
        }
        long[] sorted = ft.clone();
        Arrays.sort(sorted);
        double avgFps = frameCount / (total / 1e9);
        double medianMs = pct(sorted, 0.50) / 1e6;
        double p99Ms = pct(sorted, 0.99) / 1e6;
        double p999Ms = pct(sorted, 0.999) / 1e6;
        DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        kv(sb, "label", LABEL);
        if (flightLoop != null) {
            sb.append("  \"travelLoop\": {\"kind\": \"warm repeated circle, not fresh exploration\", \"originX\": ")
                .append(travelX0).append(", \"originZ\": ").append(travelZ0).append(", \"y\": ").append(travelY)
                .append(", \"radius\": ").append(flightLoop.radius).append(", \"speed\": ").append(TRAVEL_SPEED)
                .append(", \"periodSeconds\": ").append(flightLoop.period).append("},\n");
        }
        if (Abab.ENABLED) {
            kv(sb, "mode", MODE);   // top-level FPS figures below pool both arms (discarded frames too): use "abab"
        }
        kv(sb, "backend", info.backendName());
        kv(sb, "device", info.name());
        kv(sb, "driver", info.driverInfo());
        kv(sb, "framebuffer", mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
        num(sb, "renderDistance", mc.options.getEffectiveRenderDistance());
        num(sb, "frames", frameCount);
        num(sb, "seconds", total / 1e9);
        num(sb, "avgFps", avgFps);
        num(sb, "medianFps", 1000.0 / medianMs);
        num(sb, "p1LowFps", 1000.0 / p99Ms);
        num(sb, "p01LowFps", 1000.0 / p999Ms);
        num(sb, "medianFrameMs", medianMs);
        num(sb, "p99FrameMs", p99Ms);
        num(sb, "maxFrameMs", sorted.length == 0 ? 0 : sorted[sorted.length - 1] / 1e6);
        num(sb, "secondsToWorldReady", (worldReadyNs - launchNs) / 1e9);
        // Stage 3: LOD state and process memory at the end of the run (LodHook; "lod" is null without -Dmc.lod=true).
        sb.append("  \"lod\": ").append(LodHook.json()).append(",\n");
        num(sb, "lodSettleSeconds", lodSettleSeconds);
        sb.append("  \"memory\": ").append(LodHook.memoryJson()).append(",\n");
        sb.append("  \"terrain\": ").append(TerrainStatsHook.json()).append(",\n");
        sb.append("  \"present\": ").append(PresentStatsHook.json(total / 1e9)).append(",\n");
        sb.append("  \"lateThrottle\": ").append(ThrottleStatsHook.json()).append(",\n");
        if (ChunkStatsHook.ENABLED) {
            sb.append("  \"chunks\": ").append(ChunkStatsHook.json(true)).append(",\n");
        }
        if (ConfigBlock.ENABLED) {
            sb.append("  \"config\": ").append(ConfigBlock.json(mc, frameCount)).append(",\n");
        }
        int ababStart = sb.length();
        if (Abab.ENABLED) {
            sb.append("  \"abab\": ").append(Abab.json(false)).append(",\n");
        }
        int ababEnd = sb.length();
        int traceStart = sb.length();
        if (FrameTrace.ENABLED) {
            sb.append("  \"frameTrace\": ").append(FrameTrace.json()).append(",\n");
        }
        int traceEnd = sb.length();
        if (TerrainStatsHook.enabled()) {
            sb.append("  \"perSecondTerrainQuads\": [");
            for (int i = 0; i < perSecondTerrainQuads.size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(String.format(Locale.ROOT, "%.1f", perSecondTerrainQuads.get(i)));
            }
            sb.append("],\n");
        }
        sb.append("  \"perSecondFps\": ").append(perSecond).append("\n}\n");
        try {
            Files.writeString(Path.of(OUT), sb.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
        // The frame trace and the abab block (megabytes) stay out of the launch log.
        String logged = sb.toString();
        if (traceEnd > traceStart) {
            logged = logged.substring(0, traceStart) + "  \"frameTrace\": \"(" + FrameTrace.rows() + " rows, see " + OUT + ")\",\n"
                + logged.substring(traceEnd);
        }
        if (ababEnd > ababStart) {
            logged = logged.substring(0, ababStart) + "  \"abab\": \"(" + Abab.summary() + ", see " + OUT + ")\",\n"
                + logged.substring(ababEnd);
        }
        System.out.println("[bench] RESULT " + logged);
    }

    /** Stage-2 W0b join run result (ChunkStatsHook): no frame-time figures, the "chunks" block with its "join" member. */
    private static void writeJoin(Minecraft mc) {
        StringBuilder sb = new StringBuilder("{\n");
        kv(sb, "label", LABEL);
        kv(sb, "mode", MODE);
        kv(sb, "backend", RenderSystem.getDevice().getDeviceInfo().backendName());
        kv(sb, "framebuffer", mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
        num(sb, "renderDistance", mc.options.getEffectiveRenderDistance());
        num(sb, "secondsToJoin", (worldReadyNs - launchNs) / 1e9);
        if (ConfigBlock.ENABLED) {
            ConfigBlock.prepare();
            sb.append("  \"config\": ").append(ConfigBlock.json(mc, 0)).append(",\n");
        }
        sb.append("  \"chunks\": ").append(ChunkStatsHook.json(false)).append("\n}\n");
        try {
            Files.writeString(Path.of(OUT), sb.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
        String logged = sb.toString();
        int series = logged.indexOf(", \"series\"");
        System.out.println("[bench] RESULT " + (series > 0 ? logged.substring(0, series) + "}" : logged));
    }

    private static long pct(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 1;
        }
        int idx = (int) Math.min(sorted.length - 1, Math.round(p * (sorted.length - 1)));
        return Math.max(1, sorted[idx]);
    }

    private static void kv(StringBuilder sb, String k, String v) {
        sb.append("  \"").append(k).append("\": \"").append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append("\",\n");
    }

    private static void num(StringBuilder sb, String k, double v) {
        sb.append("  \"").append(k).append("\": ").append(String.format(Locale.ROOT, "%.3f", v)).append(",\n");
    }

    /** Like writeFailure (scripts look for "error": "scenes complete"), plus extra JSON members. */
    private static void writeDone(String why, String extraMembers) {
        if (OUT == null) {
            return;
        }
        try {
            Files.writeString(Path.of(OUT), "{\"label\": \"" + LABEL + "\", \"error\": \"" + why.replace("\"", "'") + "\", " + extraMembers + "}\n");
        } catch (IOException ignored) {
        }
    }

    private static void writeFailure(String why) {
        if (OUT == null) {
            return;
        }
        try {
            Files.writeString(Path.of(OUT), "{\"label\": \"" + LABEL + "\", \"error\": \"" + why.replace("\"", "'") + "\"}\n");
        } catch (IOException ignored) {
        }
    }
}
