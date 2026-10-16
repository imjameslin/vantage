package bench;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpyglassItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Visual-parity scene runner. Walks a fixed list of scenes (sky states, weather,
 * water, entities, particles, other dimensions, GUI screens, zoom), freezes the world tick
 * so each frame is deterministic, and saves a screenshot per scene through the game's
 * own capture path. Running this once per backend gives image pairs to diff.
 *
 * Enabled with -Dbench.mode=scenes; screenshots go to <bench.shots>/screenshots/.
 * -Dbench.only=a,b,c runs only the named scenes (same order, same world edits).
 *
 * Zoom scenes (docs/GOAL.md "Zoom"; capture commands in bench/README.md). All stand on a barrier block under the
 * anchor instead of flying (flying scales the FOV by 1.1, which the spyglass ignores and a zoom key may not), so the
 * spyglass and the zoom key both give exactly options.fov x 0.1 (7 degrees at the default FOV 70). The HUD is hidden
 * (F1) except in spyglass_scope, so the whole frame is world and spyglass and zoom-key shots compare pixel for pixel.
 * Capture waits until the camera FOV has settled (1 s unchanged, at the target).
 * <ul>
 *   <li>spyglass_far: spyglass raised (use key held), looking at the fogged tree line just inside the render-distance
 *       edge (yaw {@value #FAR_YAW}, pitch {@value #FAR_PITCH}). Runs on vanilla (reference for LOD at 10x);
 *   <li>spyglass_scope: the same with the HUD, i.e. the vanilla scope overlay (GUI parity of the overlay itself);
 *   <li>spyglass_entities: NoAI mobs on floating grass strips 40, 55, 70 and 90 blocks south. Deterministic like
 *       "entities" (NoAI, Silent, fixed rotation, summoned with NBT so no random equipment, baby or colour) plus
 *       PersistenceRequired (no random despawn beyond 32 blocks). Round 5: no daylight-burning undead (zombie -> husk,
 *       skeleton -> parched: same models, not in #burn_in_daylight) and no horse (AbstractHorse's random tail flick and
 *       grass eating run even with NoAI; -> polar bear), and the cleanup leaves no death puffs or loot in view
 *       ({@link #KILL_MOBS}), so two runs give the same image (ZOOM-VERIFY saw 3.4-3.8 % from fire and the horse). At
 *       render distance 12 vanilla's entity distance (bounding-box size x 64 x clamp(rd / 8, 1, 2.5) = size x 96 blocks)
 *       shows every mob of this scene, so it does NOT exercise the zoom-aware entity distance; spyglass_far_entities does;
 *   <li>spyglass_far_entities: the same kind of mobs 105-165 blocks south (see {@link #farMobs}): 10 of the 13 are
 *       beyond vanilla's entity distance at render distance 12 but inside the server's tracking range, so vanilla (and
 *       the patched client with -Dmc.zoomEntityDistance=false) draws only the 3 controls, while the zoom-aware entity
 *       distance must draw all 13. The bench checks this from the frame's entity render states (not from pixels) and
 *       writes the result to {@code <shots>/entities/<scene>.tsv} and the scenes JSON ("entityChecks");
 *   <li>zoom_far, zoom_entities, zoom_far_entities: the same views through the patched client's zoom key at
 *       {@value ZoomHook#FACTOR}x (see ZoomHook for the contract). Skipped when the zoom feature is absent (vanilla jar).
 * </ul>
 *
 * <p>Timing (round 6, results/SPEEDUPS.md). -Dbench.scenes.fast=false restores the old fixed waits everywhere. The fast
 * timing only removes waiting that cannot change a frame, so the pinned references made with the old timing stay valid:
 * <ul>
 *   <li>Unfrozen time is kept. Every unfrozen tick moves state the frames show, also in later scenes: rain / thunder
 *       levels (0.01 per server tick), the game time that seeds rain splashes, poured water, mob and particle ages, texture
 *       animation phases, the client level's random sequence. So the 18 original ({@link Timing#TICKED}) scenes settle
 *       by the old rule (at least {@code settle} seconds and ready). Only the zoom scenes ({@link Timing#STATIC}: last in
 *       the run, frozen-from-spawn mobs, no animated texture in view) settle on readiness: the server ran 3 ticks since the
 *       commands, the world was ready and quiet for 10 frames in a row (compile queue empty, no section builder in use, no
 *       client light work, chunk count stable, barrier on the client, spyglass in hand, every summoned mob tracked, the
 *       first-person hand at rest), and no section is still fading in (chunkSectionFadeInTime after its first upload).
 *       Their FOV counts as settled when the camera FOV has been exactly the same for 4 player ticks: the spyglass halves
 *       its distance to the target per tick and the zoom key snaps to it, so this is the float fixed point the old
 *       "unchanged within 1e-4 degrees for 1 s" also ended at.
 *   <li>Frozen time is cut to whole ticks. The old freeze (1.5 s) and file wait (0.5 s) ran on a frozen world. The shot
 *       now follows 4 frames after a client tick has applied the freeze (TickRateManager.runsNormally() false: before
 *       that, frames still render with the live partial tick, so snow, clouds and particles sit at random sub-tick
 *       positions) and once the world is quiet and done fading (GUI scenes
 *       keep the 1.5 s with the screen open: widget tooltips appear after a delay; underwater keeps it: underwater vision
 *       grows per player tick even when frozen), and the run continues once the PNG is complete (IEND chunk) and the frozen
 *       span is the old one minus a whole number of 50 ms ticks, so every later scene unfreezes at the old phase of the
 *       client and server tick grids and gets the same number of unfrozen ticks as before.
 *   <li>The HUD vignette (Hud.vignetteBrightness, a 1 %-per-tick low-pass of the light at the camera, not stopped by the
 *       freeze) is the one visible state the cut frozen ticks would change: {@link #emulateVignette} replays the ticks the
 *       old timing spent, with the same float expression, at the capture and at the unfreeze.
 *   <li>After the last scene the runner waits for 3 server ticks (the cleanup commands applied) instead of 2 s.
 * </ul>
 * Each "scene timing" log line gives the seconds spent in settle / freeze / shot, the unfrozen server and player ticks, the
 * game time at the freeze, the frozen span and which readiness conditions held the scene up.
 */
final class Scenes {
    private enum Phase { SETUP, SETTLE, FREEZE, SHOOT, WAIT_FILE, CLEANUP }

    /** How a zoom scene looks through the scope. */
    enum Optic { SPYGLASS, ZOOM_KEY }

    /** Zoom scene parameters; {@code hud} false hides the HUD (F1), which also hides the hand and the scope overlay. */
    record ZoomSpec(Optic optic, boolean hud) {
    }

    /** TICKED: settles by the old rule (its unfrozen ticks matter); STATIC: settles on readiness (class comment). */
    enum Timing { TICKED, STATIC }

    /** {@code freeze}: seconds the frozen world (and screen) is held before the shot even when ready earlier. */
    record Scene(String name, String dim, double dx, double dy, double dz, float yaw, float pitch,
                 List<String> setup, List<String> cleanup, double settle,
                 Function<Minecraft, Screen> screen, ZoomSpec zoom, Timing timing, double freeze) {
    }

    /** The old fixed waits everywhere (-Dbench.scenes.fast=false): for A/B checks of the readiness waits. */
    static final boolean FAST = !"false".equals(System.getProperty("bench.scenes.fast"));
    /** Screenshot downscale (2 = window size on a Retina display, 1 = the full framebuffer). */
    static final int SHOT_DOWNSCALE = Integer.getInteger("bench.scenes.downscale", 2);
    /** Flicker hunting: after each shot, this many more shots of the following consecutive frames (NAME-bN.png). */
    static final int BURST = Integer.getInteger("bench.scenes.burst", 0);
    private int burstShots;
    /** The old FREEZE hold, kept for GUI screens and underwater. */
    private static final double HOLD_FREEZE_S = 1.5;

    /** spyglass_far / zoom_far / zoomseq view: the fogged tree line south-west of the anchor, at the render-distance edge. */
    static final float FAR_YAW = 25.0F;
    static final float FAR_PITCH = 6.5F;
    /** spyglass_entities / zoom_entities view: due south, framing the mob rows 40-90 blocks out. */
    static final float ENT_YAW = 0.0F;
    static final float ENT_PITCH = 4.9F;
    /** spyglass_far_entities / zoom_far_entities view: due south, rows 105-165 blocks out (vertical span -1.45..+3.6 deg). */
    static final float FAR_ENT_YAW = 0.0F;
    static final float FAR_ENT_PITCH = 1.1F;

    private final List<Scene> scenes = new ArrayList<>();
    private final File shotDir;
    private int index;
    private Phase phase = Phase.SETUP;
    private long phaseStart;
    private final double ax, ay, az;
    private final ChunkWait chunks = new ChunkWait();
    private boolean hudWasHidden;
    private Double savedFactor;
    private long zoomStart;
    private float fovSeen = Float.NaN;
    private long fovSince;
    private int fovSinceTick;
    /** Server tick count when this scene's commands were sent (-1: no integrated server: STATIC scenes use the old settle). */
    private int setupServerTick = -1;
    private final Quiet quiet = new Quiet();
    private int frozenFrames;
    private double settleSeconds, freezeSeconds, shotSeconds;
    /** Old-timing model for the vignette emulation (seconds since SETTLE began; -1: not reached). */
    private double oldReadyAt = -1.0, zoomStartAt = -1.0, oldFovSinceAt = -1.0;
    private float oldFovSeen = Float.NaN;
    private int setupPlayerTick;
    /** The old WAIT_FILE minimum and the game tick (seconds). */
    private static final double OLD_FILE_S = 0.5, TICK_S = 0.05;

    /**
     * The old timing's frozen span, from the frame that sent "tick freeze" to the frame that ended WAIT_FILE (the next frame
     * unfreezes): FREEZE until t >= 1.5 s, one SHOOT frame, WAIT_FILE until t > 0.5 s; each wait overshoots by half a frame
     * on average, so 2.0 s plus two frames.
     */
    private double oldFrozenSpan() {
        return HOLD_FREEZE_S + OLD_FILE_S + 2.0 * lastFrameSeconds;
    }
    /** When the freeze command was sent; the frozen span this scene ends at (-1: not known yet). */
    private long freezeStartNs;
    private double frozenSpanTarget = -1.0;
    /** Frozen ticks the vignette emulation still owes after the shot (set at the shot, used at CLEANUP). */
    private int vignetteFrozenTicks;
    /** Player (client) tick count when the freeze command was sent; the client ticks of the old 1.5 s freeze. */
    private int freezeTick0;
    private static final int OLD_FREEZE_TICKS = 30;
    private long lastFrameNs;
    private double lastFrameSeconds = 0.004;
    /** Diagnostics: server tick / player tick when the world was last unfrozen, and the counts at the freeze. */
    private int unfreezeServerTick = -1, unfreezePlayerTick;
    private String unfrozenNote = "";

    /** Called in the frame that sends "tick freeze" at the end of SETTLE. */
    private void noteFreeze(Minecraft mc) {
        int st = serverTick(mc);
        var server = mc.getSingleplayerServer();
        long serverGameTime = server == null ? -1L : server.overworld().getGameTime();
        unfrozenNote = String.format(Locale.ROOT, ", unfrozen %d server / %d player ticks, game time %d (client %d)",
            st - unfreezeServerTick, (mc.player == null ? 0 : mc.player.tickCount) - unfreezePlayerTick, serverGameTime,
            mc.level == null ? -1L : mc.level.getGameTime());
        unfreezeServerTick = -1;
    }

    Scenes(double[] anchor, File shotDir) {
        this(anchor, shotDir, System.getProperty("bench.only"));
    }

    /** @param only comma-separated scene names to keep, or null for all */
    Scenes(double[] anchor, File shotDir, String only) {
        this.ax = anchor[0];
        this.ay = anchor[1];
        this.az = anchor[2];
        this.shotDir = shotDir;
        build();
        if (only != null && !only.isBlank()) {
            Set<String> keep = Arrays.stream(only.split(",")).map(String::trim).collect(Collectors.toSet());
            scenes.removeIf(s -> !keep.contains(s.name()));
        }
        String runner = "scenes runner: vignette " + (SETTLE_VIGNETTE ? "settled" : "vanilla") + " (bench.scenes.vignette), timing "
            + (FAST ? "fast" : "old") + " (bench.scenes.fast)\n";
        System.out.print("[bench] " + runner);
        try {
            File dir = new File(shotDir, "screenshots");
            dir.mkdirs();
            java.nio.file.Files.writeString(new File(dir, "RUNNER.txt").toPath(), runner);
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }
    }

    private String at(double dx, double dy, double dz) {
        return String.format(Locale.ROOT, "%d %d %d", (int) Math.floor(ax + dx), (int) Math.floor(ay + dy), (int) Math.floor(az + dz));
    }

    private void add(String name, Timing timing, float yaw, float pitch, List<String> setup, List<String> cleanup) {
        scenes.add(new Scene(name, "overworld", 0, 0, 0, yaw, pitch, setup, cleanup, 3.0, null, null, timing, 0.0));
    }

    private void build() {
        add("day", Timing.TICKED, 0, 12, List.of("time set 6000", "weather clear"), List.of());
        add("day_north", Timing.TICKED, 180, 12, List.of("time set 6000", "weather clear"), List.of());
        add("sunrise", Timing.TICKED, -90, -8, List.of("time set 500", "weather clear"), List.of());
        add("sunset", Timing.TICKED, 90, -8, List.of("time set 12600", "weather clear"), List.of());
        add("night_moon", Timing.TICKED, 0, -70, List.of("time set 18000", "weather clear"), List.of());
        add("night_horizon", Timing.TICKED, 45, 5, List.of("time set 18000", "weather clear"), List.of());
        add("clouds", Timing.TICKED, 45, -30, List.of("time set 6000", "weather clear"), List.of());
        add("rain", Timing.TICKED, 0, 5, List.of("time set 6000", "weather rain"), List.of("weather clear"));
        add("thunder", Timing.TICKED, 0, 5, List.of("time set 14000", "weather thunder"), List.of("weather clear"));
        // Underwater vision grows per player tick, also while the world is frozen: the old freeze hold stays too.
        scenes.add(new Scene("underwater", "overworld", 0, 0, 0, 30, 10,
            List.of("time set 6000", "weather clear",
                "fill " + at(-6, -6, -6) + " " + at(6, 6, 6) + " minecraft:glass hollow",
                "fill " + at(-5, -5, -5) + " " + at(5, 5, 5) + " minecraft:water"),
            List.of("fill " + at(-6, -6, -6) + " " + at(6, 6, 6) + " minecraft:air"), 3.0, null, null, Timing.TICKED, HOLD_FREEZE_S));
        add("water_surface", Timing.TICKED, 0, 55,
            List.of("time set 6000", "weather clear",
                "fill " + at(-12, -8, -12) + " " + at(12, -8, 12) + " minecraft:stone",
                "fill " + at(-11, -7, -11) + " " + at(11, -7, 11) + " minecraft:water",
                "fill " + at(-11, -7, 2) + " " + at(-2, -7, 11) + " minecraft:sand",
                "setblock " + at(4, -6, 5) + " minecraft:lily_pad"),
            List.of("fill " + at(-12, -8, -12) + " " + at(12, -6, 12) + " minecraft:air"));
        List<String> mobs = new ArrayList<>(List.of("time set 6000", "weather clear",
            "fill " + at(-10, -3, 3) + " " + at(10, -3, 14) + " minecraft:grass_block",
            "setblock " + at(-7, -2, 5) + " minecraft:chest",
            "setblock " + at(-5, -2, 5) + " minecraft:enchanting_table",
            "setblock " + at(-3, -2, 5) + " minecraft:oak_sign[rotation=0]{front_text:{messages:['\"Metal\"','\"parity\"','\"\"','\"\"']}}",
            "setblock " + at(3, -2, 5) + " minecraft:red_bed[facing=south]",
            "setblock " + at(5, -2, 5) + " minecraft:white_banner",
            "setblock " + at(7, -2, 5) + " minecraft:lantern",
            "setblock " + at(9, -2, 5) + " minecraft:shulker_box"));
        String[] kinds = {"zombie", "skeleton", "creeper", "pig", "cow", "sheep", "villager", "iron_golem", "horse", "spider", "enderman"};
        for (int i = 0; i < kinds.length; i++) {
            mobs.add("summon minecraft:" + kinds[i] + " " + String.format(Locale.ROOT, "%.1f %.1f %.1f", ax - 9 + i * 1.8, ay - 2, az + 10)
                + " {NoAI:1b,Silent:1b,Rotation:[180f,0f]}");
        }
        mobs.add("summon minecraft:armor_stand " + String.format(Locale.ROOT, "%.1f %.1f %.1f", ax, ay - 2, az + 7)
            + " {Rotation:[180f,0f],ShowArms:1b,equipment:{head:{id:\"minecraft:diamond_helmet\"},chest:{id:\"minecraft:netherite_chestplate\"},mainhand:{id:\"minecraft:golden_sword\"}}}");
        mobs.add("summon minecraft:item " + String.format(Locale.ROOT, "%.1f %.1f %.1f", ax + 1.5, ay - 2, az + 6)
            + " {NoGravity:1b,PickupDelay:32767,Age:-32768,Item:{id:\"minecraft:diamond\",count:1}}");
        add("entities", Timing.TICKED, 0, 28, mobs, List.of("kill @e[type=!minecraft:player]",
            "fill " + at(-10, -3, 3) + " " + at(10, 1, 14) + " minecraft:air"));
        // particles: campfire smoke, soul fire, torch flames, a flame / crit / happy-villager burst, by day and night
        for (String[] pt : new String[][] {{"sh_particles", "6000"}, {"sh_particles_night", "18000"}}) {
            List<String> parts = new ArrayList<>(List.of("time set " + pt[1], "weather clear",
                "fill " + at(-10, -3, 3) + " " + at(10, -3, 14) + " minecraft:grass_block",
                "setblock " + at(-4, -2, 7) + " minecraft:campfire",
                "setblock " + at(4, -2, 7) + " minecraft:soul_campfire",
                "setblock " + at(-1, -2, 6) + " minecraft:torch",
                "setblock " + at(1, -2, 6) + " minecraft:torch",
                "particle minecraft:flame " + at(0, 0, 9) + " 1 1 1 0.01 40 force",
                "particle minecraft:crit " + at(-3, 0, 9) + " 1 1 1 0.1 60 force",
                "particle minecraft:happy_villager " + at(3, 0, 9) + " 1 1 1 0.1 40 force",
                "particle minecraft:dust{color:[1.0,0.2,0.2],scale:2.0} " + at(6, 0, 9) + " 1 1 1 0 60 force"));
            add(pt[0], Timing.TICKED, 0, 28, parts, List.of("fill " + at(-10, -3, 3) + " " + at(10, 1, 14) + " minecraft:air"));
        }
        List<String> torchNight = new ArrayList<>(mobs);
        torchNight.addAll(List.of("time set 18000", "weather clear", "item replace entity @s weapon.mainhand with minecraft:torch"));
        add("sh_held_torch_mobs", Timing.TICKED, 0, 28, torchNight, List.of("kill @e[type=!minecraft:player]",
            "fill " + at(-10, -3, 3) + " " + at(10, 1, 14) + " minecraft:air", "item replace entity @s weapon.mainhand with minecraft:air"));
        add("particles", Timing.TICKED, 0, 15, List.of("time set 18000", "weather clear",
                "particle minecraft:end_rod " + at(0, 0, 8) + " 2 2 2 0 200 force",
                "particle minecraft:flame " + at(-3, 0, 8) + " 1 1 1 0 100 force",
                "particle minecraft:heart " + at(3, 0, 8) + " 1 1 1 0 40 force",
                "particle minecraft:dust{color:[1.0,0.2,0.2],scale:2.0} " + at(0, 2, 8) + " 3 1 1 0 200 force"),
            List.of());
        scenes.add(new Scene("nether", "the_nether", 0.5 - ax, 0, 0.5 - az, 0, 10,
            List.of("execute in minecraft:the_nether run fill -3 78 -3 3 84 3 minecraft:air"),
            List.of(), 6.0, null, null, Timing.TICKED, 0.0));
        scenes.add(new Scene("end", "the_end", 0.5 - ax, 0, -60.5 - az, 0, 8,
            List.of("execute in minecraft:the_end run kill @e[type=minecraft:ender_dragon]"),
            List.of(), 6.0, null, null, Timing.TICKED, 0.0));
        scenes.add(new Scene("gui_inventory", "overworld", 0, 0, 0, 0, 12, List.of("time set 6000", "weather clear"), List.of(), 3.0,
            mc -> new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), true), null, Timing.TICKED, HOLD_FREEZE_S));
        scenes.add(new Scene("gui_pause", "overworld", 0, 0, 0, 0, 12, List.of(), List.of(), 2.0, mc -> new PauseScreen(true), null,
            Timing.TICKED, HOLD_FREEZE_S));
        scenes.add(new Scene("gui_options", "overworld", 0, 0, 0, 0, 12, List.of(), List.of(), 2.0,
            mc -> new OptionsScreen(new PauseScreen(true), mc.options), null, Timing.TICKED, HOLD_FREEZE_S));

        // Zoom scenes (class comment). Appended last so the 18 original scenes and their world state are unchanged.
        List<String> clearDay = List.of("time set 6000", "weather clear");
        zoom("spyglass_far", Optic.SPYGLASS, false, FAR_YAW, FAR_PITCH, clearDay, List.of());
        zoom("spyglass_scope", Optic.SPYGLASS, true, FAR_YAW, FAR_PITCH, clearDay, List.of());
        zoom("zoom_far", Optic.ZOOM_KEY, false, FAR_YAW, FAR_PITCH, clearDay, List.of());
        List<String> distant = new ArrayList<>(clearDay);
        List<String> distantCleanup = new ArrayList<>(KILL_MOBS);
        distantMobs(distant, distantCleanup);
        zoom("spyglass_entities", Optic.SPYGLASS, false, ENT_YAW, ENT_PITCH, distant, distantCleanup);
        zoom("zoom_entities", Optic.ZOOM_KEY, false, ENT_YAW, ENT_PITCH, distant, distantCleanup);
        // Round 5: mobs beyond vanilla's entity distance (appended, so every earlier scene and its world state is unchanged).
        List<String> far = new ArrayList<>(clearDay);
        List<String> farCleanup = new ArrayList<>(KILL_MOBS);
        farMobs(far, farCleanup);
        zoom("spyglass_far_entities", Optic.SPYGLASS, false, FAR_ENT_YAW, FAR_ENT_PITCH, far, farCleanup);
        zoom("zoom_far_entities", Optic.ZOOM_KEY, false, FAR_ENT_YAW, FAR_ENT_PITCH, far, farCleanup);

        // Round 11 (CONTRACT 16.R11): dark scenes for the perceptual check of the terrain colour / fog math (banding in
        // dark gradients). A closed stone room 35-46 blocks under the anchor (no sky light), deepslate floor, some ore and
        // cobblestone, lit only by one torch at the far end: smooth block light falls off from 14 to 0 across the room. Then
        // the same room with the torch removed (minimum light everywhere). Peaceful so nothing spawns in the dark. Appended
        // last: every earlier scene and its world state are unchanged.
        String torch = "setblock " + at(-7, -44, 20) + " minecraft:torch";
        List<String> cave = List.of("difficulty peaceful", "weather clear",
            "fill " + at(-9, -46, -4) + " " + at(9, -34, 22) + " minecraft:stone hollow",
            "fill " + at(-8, -45, -3) + " " + at(8, -45, 21) + " minecraft:deepslate",
            "fill " + at(-3, -44, 8) + " " + at(-1, -43, 10) + " minecraft:iron_ore",
            "fill " + at(2, -44, 14) + " " + at(4, -42, 15) + " minecraft:cobblestone",
            torch);
        scenes.add(new Scene("cave", "overworld", 0, -41, 0, 0, 12, cave, List.of(), 3.0, null, null, Timing.TICKED, 0.0));
        scenes.add(new Scene("cave_dark", "overworld", 0, -41, 0, 0, 12, List.of("setblock " + at(-7, -44, 20) + " minecraft:air"),
            List.of("fill " + at(-9, -46, -4) + " " + at(9, -34, 22) + " minecraft:stone"), 3.0, null, null, Timing.TICKED, 0.0));

        // Round 12 Part B (greedy meshing before/after pairs): close-ups of a grass and a stone floor with blocks standing on
        // them, so their corners get ambient-occlusion shadows, on a platform 19 blocks above the anchor (open sky, nothing
        // else in view below the horizon); the camera stands on the platform. Appended last: every earlier scene is unchanged.
        List<String> clearAir = List.of("fill " + at(-9, 19, -3) + " " + at(9, 27, 15) + " minecraft:air");
        List<String> aoGrass = new ArrayList<>(List.of("time set 6000", "weather clear", clearAir.get(0),
            "fill " + at(-9, 19, -3) + " " + at(9, 19, 15) + " minecraft:grass_block",
            "setblock " + at(0, 20, 5) + " minecraft:stone",
            "fill " + at(-4, 20, 8) + " " + at(-2, 20, 8) + " minecraft:cobblestone",
            "fill " + at(2, 20, 3) + " " + at(3, 21, 4) + " minecraft:oak_planks",
            "fill " + at(3, 20, 9) + " " + at(5, 20, 11) + " minecraft:stone",
            "setblock " + at(-2, 20, 3) + " minecraft:stone"));
        scenes.add(new Scene("ao_grass", "overworld", 0.5, 20, 0.5, 0, 50, aoGrass, clearAir, 3.0, null, null, Timing.TICKED, 0.0));
        List<String> aoStone = new ArrayList<>(List.of("time set 6000", "weather clear", clearAir.get(0),
            "fill " + at(-9, 19, -3) + " " + at(9, 19, 15) + " minecraft:stone",
            "fill " + at(-6, 20, 12) + " " + at(6, 23, 12) + " minecraft:stone",
            "fill " + at(-6, 20, 4) + " " + at(-6, 23, 12) + " minecraft:stone",
            "fill " + at(6, 20, 4) + " " + at(6, 22, 12) + " minecraft:cobblestone",
            "fill " + at(0, 20, 8) + " " + at(0, 22, 8) + " minecraft:stone",
            "fill " + at(-3, 20, 6) + " " + at(-2, 20, 7) + " minecraft:stone"));
        scenes.add(new Scene("ao_stone", "overworld", 0.5, 20, 0.5, 0, 35, aoStone, clearAir, 3.0, null, null, Timing.TICKED, 0.0));
        // Stage 4 (Tessera) look scenes: low and mid sun from the side and from behind, so cast shadows are long and visible.
        // Appended last: every earlier scene is unchanged.
        List<String> stoneMorning = new ArrayList<>(aoStone);
        stoneMorning.set(0, "time set 1500");
        scenes.add(new Scene("sh_stone_morning", "overworld", 0.5, 20, 0.5, 0, 35, stoneMorning, clearAir, 3.0, null, null, Timing.TICKED, 0.0));
        add("sh_morning", Timing.TICKED, 0, 12, List.of("time set 2000", "weather clear"), List.of());
        add("sh_morning_back", Timing.TICKED, 90, 12, List.of("time set 2000", "weather clear"), List.of());
        add("sh_afternoon", Timing.TICKED, 180, 12, List.of("time set 9500", "weather clear"), List.of());
        // Stage 4 water: a wide pool, looking east into the low sun (glint, grazing reflection) and west away from it.
        List<String> pool = List.of("fill " + at(-30, -4, -30) + " " + at(30, -2, 30) + " minecraft:stone hollow",
            "fill " + at(-29, -3, -29) + " " + at(29, -3, 29) + " minecraft:water", "fill " + at(-29, -2, -29) + " " + at(29, -2, 29) + " minecraft:air",
            "fill " + at(-30, -1, -30) + " " + at(30, 6, 30) + " minecraft:air");
        List<String> poolMorning = new ArrayList<>(List.of("time set 1200", "weather clear"));
        poolMorning.addAll(pool);
        List<String> poolNoon = new ArrayList<>(List.of("time set 6000", "weather clear"));
        poolNoon.addAll(pool);
        List<String> poolClean = List.of("fill " + at(-30, -4, -30) + " " + at(30, -2, 30) + " minecraft:air");
        add("sh_water_sun", Timing.TICKED, -90, 8, poolMorning, poolClean);
        // probe: biome / structure locations (results in the log)
        add("sh_locate", Timing.TICKED, 0, 12, List.of("gamerule send_command_feedback true", "locate biome minecraft:plains", "locate biome minecraft:jungle", "locate structure minecraft:village_plains", "locate biome minecraft:forest", "locate biome minecraft:cherry_grove"), List.of("gamerule send_command_feedback false"));
        // Stage 4 look references (the target look): green plains at sunset and in the afternoon, a plains village
        // by day, a jungle in the morning looking towards the sun (offsets from the anchor; the camera is above the ground).
        List<String> noFeedback = List.of();
        scenes.add(new Scene("sh_plains_sunset", "overworld", 320, 12, 320, 90, 4, List.of("time set 12300", "weather clear"), noFeedback, 3.0, null, null, Timing.TICKED, 0.0));
        scenes.add(new Scene("sh_plains_day", "overworld", 320, 12, 320, 30, 14, List.of("time set 8000", "weather clear"), noFeedback, 3.0, null, null, Timing.TICKED, 0.0));
        scenes.add(new Scene("sh_held_torch", "overworld", -1612, 14, 547, 20, 45, List.of("time set 18000", "weather clear",
            "item replace entity @s weapon.mainhand with minecraft:torch"), List.of("item replace entity @s weapon.mainhand with minecraft:air"),
            3.0, null, null, Timing.TICKED, 0.0));
        scenes.add(new Scene("sh_rain", "overworld", 320, 12, 320, 30, 14, List.of("time set 8000", "weather rain"), noFeedback, 6.0, null, null, Timing.TICKED, 0.0));
        scenes.add(new Scene("sh_rain_night", "overworld", 320, 12, 320, 30, 14, List.of("time set 18000", "weather rain"), noFeedback, 6.0, null, null, Timing.TICKED, 0.0));
        scenes.add(new Scene("sh_village", "overworld", -1612, 14, 547, 20, 22, List.of("time set 4000", "weather clear"), noFeedback, 3.0, null, null, Timing.TICKED, 0.0));
        scenes.add(new Scene("sh_jungle", "overworld", 1152, 30, -672, -70, 6, List.of("time set 1500", "weather clear"), noFeedback, 3.0, null, null, Timing.TICKED, 0.0));
        // framed like the user's jungle reference shot: morning sun ahead, looking slightly up
        scenes.add(new Scene("sh_ref_sun", "overworld", 1152, 30, -672, -90, -12, List.of("time set 1000", "weather clear"), noFeedback, 3.0, null, null, Timing.TICKED, 0.0));
        // god rays: low morning sun ahead through the jungle canopy, from just above the trees and from inside them
        scenes.add(new Scene("sh_godrays", "overworld", 1152, 30, -672, -90, 2, List.of("time set 300", "weather clear"), noFeedback, 3.0, null, null, Timing.TICKED, 0.0));
        scenes.add(new Scene("sh_godrays_dawn", "overworld", 1152, 4, -672, -90, 4, List.of("time set 23750", "weather clear"), noFeedback, 3.0, null, null, Timing.TICKED, 0.0));
        scenes.add(new Scene("sh_godrays_inside", "overworld", 1180, 0, -672, -90, 10, List.of("time set 23900", "weather clear"), noFeedback, 3.0, null, null, Timing.TICKED, 0.0));
        scenes.add(new Scene("sh_godrays_low", "overworld", 1152, 4, -672, -90, 4, List.of("time set 300", "weather clear"), noFeedback, 3.0, null, null, Timing.TICKED, 0.0));
        // night: the plains view under the moon, and the rising moon itself
        scenes.add(new Scene("sh_plains_night", "overworld", 320, 12, 320, 30, 14, List.of("time set 18000", "weather clear"), noFeedback, 3.0, null, null, Timing.TICKED, 0.0));
        scenes.add(new Scene("sh_moon", "overworld", 1152, 30, -672, -90, -15, List.of("time set 14500", "weather clear"), noFeedback, 3.0, null, null, Timing.TICKED, 0.0));
        add("sh_water_away", Timing.TICKED, 90, 14, poolMorning, poolClean);
        add("sh_water_noon", Timing.TICKED, 30, 25, poolNoon, poolClean);
        // Stage 4 torch light: a small cobblestone yard at night with torches and a lantern.
        List<String> torchSetup = new ArrayList<>(List.of("time set 18000", "weather clear",
            "fill " + at(-8, -3, 2) + " " + at(8, -3, 18) + " minecraft:cobblestone",
            "fill " + at(-8, -2, 2) + " " + at(8, 8, 18) + " minecraft:air",
            "fill " + at(-8, -2, 18) + " " + at(8, 2, 18) + " minecraft:stone_bricks",
            "fill " + at(-8, -2, 2) + " " + at(-8, 2, 18) + " minecraft:oak_planks",
            "setblock " + at(-3, -2, 8) + " minecraft:torch", "setblock " + at(4, -2, 12) + " minecraft:torch",
            "setblock " + at(0, -2, 17) + " minecraft:lantern", "setblock " + at(-7, -2, 14) + " minecraft:torch",
            "setblock " + at(2, -2, 6) + " minecraft:grass_block", "setblock " + at(3, -2, 6) + " minecraft:oak_log"));
        add("sh_torch_night", Timing.TICKED, 0, 30, torchSetup,
            List.of("fill " + at(-8, -3, 2) + " " + at(8, 2, 18) + " minecraft:air"));
    }

    /**
     * Removes the scene mobs out of view: each is teleported 200 blocks below the world (straight down, same chunk), where
     * void damage kills it, so its death animation, the "poof" particles at its removal and its loot all happen out of
     * sight. A plain /kill left death puffs that were still in the next zoom scene's frame at capture time, depending on
     * timing (0.009 % of the frame, a 19 px region), and loot lying on the strips that the next scene re-creates.
     */
    private static final List<String> KILL_MOBS = List.of("execute as @e[type=!minecraft:player] at @s run tp @s ~ -200 ~",
        "kill @e[type=minecraft:item]");

    /** Adds a zoom scene: barrier to stand on, spyglass (or empty hand) in the main hand; both undone in cleanup. */
    private void zoom(String name, Optic optic, boolean hud, float yaw, float pitch, List<String> setup, List<String> cleanup) {
        List<String> su = new ArrayList<>(setup);
        su.addAll(zoomSetup(optic == Optic.SPYGLASS));
        if (name.contains("entities")) {
            // Freeze in the same command batch as the summons: the server broadcasts the frozen state at once, before the
            // spawn packets of that tick, so every mob reaches the client frozen with tickCount 0 and its idle animation
            // (humanoid arm bob, driven by ageInTicks) has the same phase in every run (0.23 % run-to-run difference
            // without it, the enderman's arms). The local player is never frozen: the spyglass FOV still eases in.
            su.add("tick freeze");
        }
        List<String> cu = new ArrayList<>(List.of("item replace entity @s weapon.mainhand with minecraft:air"));
        cu.addAll(cleanup);
        cu.add("setblock " + at(0, -1, 0) + " minecraft:air");
        scenes.add(new Scene(name, "overworld", 0, 0, 0, yaw, pitch, su, cu, 3.0, null, new ZoomSpec(optic, hud), Timing.STATIC, 0.0));
    }

    private List<String> zoomSetup(boolean spyglass) {
        return List.of("setblock " + at(0, -1, 0) + " minecraft:barrier",
            "item replace entity @s weapon.mainhand with " + (spyglass ? "minecraft:spyglass" : "minecraft:air"));
    }

    /** The scene with this name (ZoomSeq reuses spyglass_far's view, setup and cleanup). */
    Scene scene(String name) {
        return scenes.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    /** Block the zoom scenes stand on (setblock at(0, -1, 0)). */
    BlockPos barrier() {
        return BlockPos.containing(ax, ay - 1, az);
    }

    /**
     * Mob rows for the *_entities zoom scenes, seen from the anchor at yaw 0 (south, +z), feet 4 blocks below the
     * player's feet on grass strips (placed only into air, removed only where grass). Lateral offsets keep every mob
     * inside the 7-degree spyglass frame at a 1.54 aspect (half-width 0.094 x distance) and clear of the rows in front.
     */
    private void distantMobs(List<String> setup, List<String> cleanup) {
        int[] rows = {40, 55, 70, 90};
        int[] halfWidth = {5, 6, 8, 10};
        for (int r = 0; r < rows.length; r++) {
            String from = at(-halfWidth[r], -5, rows[r] - 1), to = at(halfWidth[r], -5, rows[r] + 1);
            setup.add("fill " + from + " " + to + " minecraft:grass_block replace minecraft:air");
            cleanup.add("fill " + from + " " + to + " minecraft:air replace minecraft:grass_block");
        }
        Object[][] mobs = {
            {"husk", -2.4, 40}, {"villager", 0.0, 40}, {"cow", 2.4, 40},
            {"sheep", -4.0, 55}, {"polar_bear", 1.2, 55}, {"parched", 4.0, 55},
            {"creeper", -5.0, 70}, {"iron_golem", -1.0, 70}, {"pig", 5.0, 70},
            {"enderman", -7.5, 90}, {"iron_golem", 2.0, 90}, {"spider", 6.0, 90},
        };
        for (Object[] m : mobs) {
            setup.add("summon minecraft:" + m[0] + " "
                + String.format(Locale.ROOT, "%.2f %.2f %.2f", ax + (double) m[1], ay - 4, az + (int) m[2])
                + " {NoAI:1b,Silent:1b,PersistenceRequired:1b,Rotation:[180f,0f]}");
        }
    }

    /**
     * Rows of the *_far_entities scenes: 105, 125, 145 and 165 blocks south of the anchor, one grass strip per row at a
     * rising height (feet at ay-4, ay-2, ay, ay+4; eye at ay+1.62) so that every row has its own band on screen and no
     * mob hides another: from the eye, row 105 spans +2.0..+3.6 degrees below the horizon (strip face included), 125
     * +1.0..+2.1, 145 -0.4..+1.0, 165 -1.45..-0.5; pitch {@value #FAR_ENT_PITCH} centres -1.45..+3.6 in the 7-degree frame.
     * Lateral offsets stay within 0.064 x distance (frame half-width 0.094 x distance at 1.54:1).
     *
     * <p>Mob choice (EntityTypes, 26.3; vanilla entity distance = bounding-box size (2w+h)/3 x 64 x viewScale, viewScale =
     * clamp(rd / 8, 1, 2.5) x entityDistanceScaling = 1.5 at render distance 12; server tracking range = clientTrackingRange
     * x 16, at most the view distance x 16 = 192):
     * <pre>
     *   row   mob          size   vanilla dist  tracked to  vanilla draws it
     *   105   creeper      0.967   92.8         128         no
     *   105   pig          0.900   86.4         160         no
     *   105   villager     1.050  100.8         160         no
     *   105   polar_bear   1.400  134.4         160         yes (control)
     *   125   sheep        1.033   99.2         160         no
     *   125   spider       1.233  118.4         128         no
     *   125   cow          1.067  102.4         160         no
     *   125   polar_bear   1.400  134.4         160         yes (control)
     *   145   polar_bear   1.400  134.4         160         no
     *   145   goat         1.033   99.2         160         no
     *   145   iron_golem   1.833  176.0         160         yes (control)
     *   165   mannequin x2 1.000   96.0         192         no  (only trackRange >= 11 entities reach the client at 165)
     * </pre>
     * All are deterministic: no daylight-burning undead, no AbstractHorse (random tail flick / grass eating with NoAI),
     * summoned with NBT (finalizeSpawn skipped: default variant, colour, no equipment), NoAI, Silent, fixed rotation,
     * PersistenceRequired (monsters beyond 128 blocks would despawn at once); the mannequins are immovable with the
     * default (fixed) skin and no description label. Cleanup removes them out of view ({@link #KILL_MOBS}).
     */
    private void farMobs(List<String> setup, List<String> cleanup) {
        int[] rows = {105, 125, 145, 165};
        int[] stripDy = {-5, -3, -1, 3};
        int[] halfWidth = {8, 8, 7, 6};
        for (int r = 0; r < rows.length; r++) {
            String from = at(-halfWidth[r], stripDy[r], rows[r] - 1), to = at(halfWidth[r], stripDy[r], rows[r] + 1);
            setup.add("fill " + from + " " + to + " minecraft:grass_block replace minecraft:air");
            cleanup.add("fill " + from + " " + to + " minecraft:air replace minecraft:grass_block");
        }
        String mob = " {NoAI:1b,Silent:1b,PersistenceRequired:1b,Rotation:[180f,0f]}";
        String mannequin = " {Silent:1b,Rotation:[180f,0f],immovable:1b,hide_description:1b}";
        Object[][] mobs = {
            {"creeper", -6.0, 0}, {"pig", -2.0, 0}, {"villager", 2.0, 0}, {"polar_bear", 6.0, 0},
            {"sheep", -6.0, 1}, {"spider", -2.0, 1}, {"cow", 2.0, 1}, {"polar_bear", 6.0, 1},
            {"polar_bear", -5.0, 2}, {"goat", 0.0, 2}, {"iron_golem", 5.0, 2},
            {"mannequin", -4.0, 3}, {"mannequin", 4.0, 3},
        };
        for (Object[] m : mobs) {
            int r = (int) m[2];
            setup.add("summon minecraft:" + m[0] + " "
                + String.format(Locale.ROOT, "%.2f %.2f %.2f", ax + (double) m[1], Math.floor(ay + stripDy[r]) + 1.0, az + rows[r])
                + ("mannequin".equals(m[0]) ? mannequin : mob));
        }
    }

    /** @return true when all scenes are done */
    boolean step(Minecraft mc) {
        long frameNs = System.nanoTime();
        if (lastFrameNs != 0L) {
            lastFrameSeconds = 0.9 * lastFrameSeconds + 0.1 * Math.min(0.05, (frameNs - lastFrameNs) / 1e9);
        }
        lastFrameNs = frameNs;
        if (index >= scenes.size()) {
            // Give the server time to apply the last scene's cleanup commands (they edit the scene world, which is saved on
            // exit; runs normally start from the pinned copy anyway): 3 server ticks, or 2 s without an integrated server.
            double t = (System.nanoTime() - phaseStart) / 1e9;
            return FAST && setupServerTick >= 0 ? serverTicksSince(mc, setupServerTick) >= 3 : t > 2.0;
        }
        Scene s = scenes.get(index);
        LocalPlayer player = mc.player;
        mc.gui.toastManager().clear();
        settleVignette(mc);
        double t = (System.nanoTime() - phaseStart) / 1e9;
        switch (phase) {
            case SETUP -> {
                if (this.drainMobs) {
                    // After an entity zoom scene: wait (world unfrozen) until its mobs have left the scene area on the client
                    // (KILL_MOBS sends them below the world), so this scene never freezes them in place or counts them.
                    boolean clear = sceneMobs(mc) == 0;
                    if (!clear && t < 10.0) {
                        return false;
                    }
                    if (!clear) {
                        System.out.println("[bench] scene area still has " + sceneMobs(mc) + " mobs after 10 s");
                    }
                    this.drainMobs = false;
                }
                if (s.zoom() != null && s.zoom().optic() == Optic.ZOOM_KEY && !ZoomHook.available()) {
                    System.out.println("[bench] scene skipped: " + s.name() + " (" + ZoomHook.missing() + ")");
                    index++;
                    next(Phase.SETUP);
                    return false;
                }
                cmd(mc, "tick unfreeze");
                if (unfreezeServerTick < 0) {
                    unfreezeServerTick = serverTick(mc);   // (first scene: no CLEANUP unfroze before it)
                    unfreezePlayerTick = player == null ? 0 : player.tickCount;
                }
                mc.gui.setScreen(null);
                double x = ax + s.dx(), y = ay + s.dy(), z = az + s.dz();
                String tp = String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", x, y, z, s.yaw(), s.pitch());
                cmd(mc, "overworld".equals(s.dim()) ? "execute in minecraft:overworld run " + tp : "execute in minecraft:" + s.dim() + " run " + tp);
                for (String c : s.setup()) {
                    cmd(mc, c);
                }
                setupServerTick = serverTick(mc);
                quiet.reset();
                Arrays.fill(notQuiet, 0);
                fovSinceTick = 0;
                oldReadyAt = zoomStartAt = oldFovSinceAt = -1.0;
                oldFovSeen = Float.NaN;
                setupPlayerTick = player == null ? 0 : player.tickCount;
                if (s.zoom() != null) {
                    hudWasHidden = mc.gui.hud.isHidden();
                    savedFactor = null;
                    if (s.zoom().optic() == Optic.ZOOM_KEY) {
                        savedFactor = ZoomHook.factor();
                        ZoomHook.setFactor(ZoomHook.FACTOR);
                    }
                    zoomStart = 0L;
                    fovSeen = Float.NaN;
                }
                next(Phase.SETTLE);
            }
            case SETTLE -> {
                aim(player, s);
                if (player != null) {
                    player.getAbilities().flying = true;
                }
                // Farsight: also wait until every LOD job is done, so shots always show the far terrain (user rule).
                boolean lodIdle = !LodHook.active() || LodHook.idle();
                boolean ready = mc.level != null && mc.levelRenderer.hasRenderedAllSections()
                    && mc.level.dimension().identifier().getPath().equals(s.dim()) && chunks.stable(mc) && lodIdle;
                double cap = LodHook.active() ? 240.0 : 40.0;
                if (oldReadyAt < 0.0 && t >= s.settle() && ready) {
                    oldReadyAt = t;   // where the old settle would have ended (vignette emulation)
                }
                // Tracked every frame, also for TICKED scenes (the shortened freeze below needs it).
                boolean quietNow = FAST && trackQuiet(mc, s, ready);
                boolean worldReady;
                if (FAST && s.timing() == Timing.STATIC && setupServerTick >= 0) {
                    worldReady = (quietNow && serverTicksSince(mc, setupServerTick) >= 3 && lodIdle) || t > cap;
                } else {
                    worldReady = (t >= s.settle() && ready) || t > cap;
                }
                if (worldReady && !ready && (s.zoom() == null || zoomStart == 0L)) {
                    // Captured anyway after 40 s: the frame may lack terrain. Say so (scripts grep "NOT READY"), as for FOV.
                    System.out.println(String.format(Locale.ROOT, "[bench] %s: world NOT READY after %.0f s (all sections rendered %b, chunks stable %b, lod idle %b)",
                        s.name(), t, mc.level != null && mc.levelRenderer.hasRenderedAllSections(), mc.level != null && chunks.stable(mc), lodIdle));
                }
                if (s.zoom() == null) {
                    if (worldReady) {
                        noteFreeze(mc);
                        cmd(mc, "tick freeze");
                        if (s.screen() != null) {
                            mc.gui.setScreen(s.screen().apply(mc));
                        }
                        settleSeconds = t;
                        frozenFrames = 0;
                        freezeTick0 = player == null ? 0 : player.tickCount;
                        next(Phase.FREEZE);
                    }
                } else {
                    // Raise the spyglass / hold the zoom key once the world is ready, then wait for the FOV to settle.
                    if (worldReady && zoomStart == 0L) {
                        zoomStart = System.nanoTime();
                        zoomStartAt = t;
                    }
                    optics(mc, s, zoomStart != 0L);
                    if (zoomStart != 0L) {
                        boolean settled = fovSettled(mc, s);
                        if (settled || System.nanoTime() - zoomStart > 15_000_000_000L) {
                            float base = mc.options.fov().get();
                            System.out.println(String.format(Locale.ROOT, "[bench] %s: camera fov %.4f (unzoomed %.0f, target %.4f)%s",
                                s.name(), cameraFov(mc), base, base * SpyglassItem.ZOOM_FOV_MODIFIER, settled ? "" : " NOT SETTLED"));
                            noteFreeze(mc);
                            cmd(mc, "tick freeze");
                            settleSeconds = t;
                            frozenFrames = 0;
                            freezeTick0 = player == null ? 0 : player.tickCount;
                            next(Phase.FREEZE);
                        }
                    }
                }
            }
            case FREEZE -> {
                aim(player, s);
                optics(mc, s, true);
                pinTime(mc);
                EntityCapture.armed = s.zoom() != null && s.name().contains("entities");
                boolean done;
                if (FAST) {
                    // The client is frozen once a client tick has applied the server's frozen state: isFrozen() flips when
                    // the packet arrives, runsNormally() only at the next client tick, and until then the frame still
                    // renders with the live partial tick (snow, clouds, particles move). 4 frames after that every frame
                    // has partial tick 1, the pinned time and (entity scenes) an armed capture. A pause screen stops the
                    // client ticks instead (runsNormally() stays true; frames use the partial tick saved at the pause).
                    // The world must still be quiet (sections that were still fading in), and GUI / underwater scenes
                    // keep the old hold.
                    frozenFrames = mc.level != null && (mc.isPaused()
                        || mc.level.tickRateManager().isFrozen() && !mc.level.tickRateManager().runsNormally()) ? frozenFrames + 1 : 0;
                    boolean ready = mc.level != null && mc.levelRenderer.hasRenderedAllSections()
                        && mc.level.dimension().identifier().getPath().equals(s.dim()) && chunks.stable(mc);
                    boolean quietNow = trackQuiet(mc, s, ready);
                    done = frozenFrames >= 4 && t >= s.freeze() && quietNow;
                    if (!done && t > 20) {
                        System.out.println(String.format(Locale.ROOT, "[bench] %s: freeze NOT QUIET after 20 s (frozen frames %d, %s)",
                            s.name(), frozenFrames, notQuietSummary()));
                        done = true;
                    }
                } else {
                    done = t >= HOLD_FREEZE_S;
                }
                if (done) {
                    freezeSeconds = t;
                    freezeStartNs = phaseStart;
                    frozenSpanTarget = -1.0;
                    if (FAST) {
                        // This frame is the one the shot captures: give its HUD vignette the client ticks the old timing
                        // had spent by its capture (class comment): the shorter freeze, and for zoom scenes the shorter settle.
                        // The old freeze ran 30 client ticks; this one ran the ticks counted since the freeze command. A
                        // kept 1.5 s hold ran the same ticks, and a pause screen stops the HUD tick (none spent or skipped).
                        int ticksSoFar = (player == null ? 0 : player.tickCount) - freezeTick0;
                        vignetteFrozenTicks = mc.isPaused() || s.freeze() >= HOLD_FREEZE_S ? 0 : OLD_FREEZE_TICKS - ticksSoFar;
                        emulateVignette(mc, (int) Math.round(20.0 * (oldSettleSeconds(s) - settleSeconds)) + vignetteFrozenTicks);
                    }
                    next(Phase.SHOOT);
                }
            }
            case SHOOT -> {
                aim(player, s);
                optics(mc, s, true);
                pinTime(mc);
                entityCheck(mc, s);
                new File(shotDir, "screenshots/" + s.name() + ".png").delete();
                Screenshot.grab(shotDir, s.name() + ".png", mc.gameRenderer.mainRenderTarget(), SHOT_DOWNSCALE, msg -> {});
                this.burstShots = 0;
                next(Phase.WAIT_FILE);
            }
            case WAIT_FILE -> {
                aim(player, s);
                optics(mc, s, true);
                if (this.burstShots < BURST) {
                    this.burstShots++;
                    Screenshot.grab(shotDir, s.name() + "-b" + this.burstShots + ".png", mc.gameRenderer.mainRenderTarget(), SHOT_DOWNSCALE, msg -> {});
                    break;
                }
                File png = new File(shotDir, "screenshots/" + s.name() + ".png");
                // The file is written after the GPU readback of the captured frame finished, so nothing done from here on
                // can change it; a complete PNG ends with the IEND chunk.
                boolean written;
                if (FAST) {
                    // The frozen span (freeze command to unfreeze) is the old one minus whole ticks (class comment).
                    double span = (System.nanoTime() - freezeStartNs) / 1e9;
                    if (frozenSpanTarget < 0.0 && pngComplete(png)) {
                        double old = oldFrozenSpan();
                        frozenSpanTarget = old + TICK_S * Math.ceil((span - old) / TICK_S - 1e-9);
                    }
                    written = frozenSpanTarget >= 0.0 && span >= frozenSpanTarget;
                } else {
                    written = png.length() > 0 && t > 0.5;
                }
                if (written || t > 10) {
                    if (!written) {
                        System.out.println("[bench] " + s.name() + ": screenshot not complete after 10 s");
                    }
                    shotSeconds = t;
                    double span = (System.nanoTime() - freezeStartNs) / 1e9;
                    System.out.println(String.format(Locale.ROOT, "[bench] scene timing: %s settle %.2f s, freeze %.2f s, shot %.2f s%s%s",
                        s.name(), settleSeconds, freezeSeconds, t, unfrozenNote, FAST ? String.format(Locale.ROOT,
                            ", frozen span %.3f s (old about %.2f s settle, %.3f s frozen), not-quiet frames %s",
                            span, oldSettleSeconds(s), oldFrozenSpan(), notQuietSummary()) : ""));
                    vignetteFrozenTicks = (int) Math.round((oldFrozenSpan() - span) / TICK_S) - vignetteFrozenTicks;
                    next(Phase.CLEANUP);
                }
            }
            case CLEANUP -> {
                if (FAST && !mc.isPaused()) {
                    // The rest of the frozen ticks the old timing spent after the shot (a pause screen stops the HUD tick).
                    emulateVignette(mc, vignetteFrozenTicks);
                }
                this.drainMobs = s.zoom() != null && s.name().contains("entities");
                EntityCapture.armed = false;
                EntityCapture.captures = 0;
                mc.gui.setScreen(null);
                if (s.zoom() != null) {
                    ZoomHook.hold(mc.options.keyUse, false);
                    if (s.zoom().optic() == Optic.ZOOM_KEY) {
                        ZoomHook.hold(ZoomHook.key(), false);
                        if (savedFactor != null) {
                            ZoomHook.setFactor(savedFactor);
                        }
                    }
                    hideHud(mc, hudWasHidden);
                }
                cmd(mc, "tick unfreeze");
                unfreezeServerTick = serverTick(mc);
                unfreezePlayerTick = player == null ? 0 : player.tickCount;
                for (String c : s.cleanup()) {
                    cmd(mc, c);
                }
                setupServerTick = serverTick(mc);   // (the end of the run waits for these commands)
                System.out.println("[bench] scene done: " + s.name());
                index++;
                next(Phase.SETUP);
            }
        }
        return false;
    }

    /**
     * Which scene mobs the last rendered frame (the one the screenshot captures) drew, from its submitted entity render
     * states (EntityCapture, armed from FREEZE on), against the
     * expectation: vanilla's distance test (bounding-box size x 64 x viewScale, all scene mobs are inside the view) when the
     * zoom-aware entity distance is absent (vanilla jar) or off (-Dmc.zoomEntityDistance=false), every tracked mob when it
     * is on (all scene mobs are within the zoomed distance, s = 11.45 for the spyglass at FOV 70, and inside the zoomed view).
     * Only for zoom scenes whose name contains "entities"; writes {@code <shots>/entities/<scene>.tsv}, logs one line and keeps
     * a JSON summary for the scenes result ({@link #entityChecks}).
     */
    private void entityCheck(Minecraft mc, Scene s) {
        if (s.zoom() == null || !s.name().contains("entities") || mc.level == null) {
            return;
        }
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        double viewScale = Mth.clamp(mc.options.getEffectiveRenderDistance() / 8.0, 1.0, 2.5) * mc.options.entityDistanceScaling().get();
        boolean zoomAware = zoomEntityDistance();
        long expectedMobs = s.setup().stream().filter(c -> c.startsWith("summon ")).count();
        StringBuilder tsv = new StringBuilder("type\tx\ty\tz\tdistance\tvanillaMax\tvanillaDraws\tdrawn\texpected\n");
        int tracked = 0, drawn = 0, vanillaDraws = 0, wrong = 0;
        List<String> wrongList = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!inSceneArea(e)) {   // (the previous scene's mobs are gone: KILL_MOBS + the SETUP drain)
                continue;
            }
            tracked++;
            double d = Math.sqrt(e.distanceToSqr(cam));
            double size = e.getBoundingBox().getSize();
            double vanillaMax = (Double.isNaN(size) ? 1.0 : size) * 64.0 * viewScale;
            boolean vanilla = d < vanillaMax;
            boolean isDrawn = EntityCapture.drawn(e.getType(), e.getX(), e.getY(), e.getZ());
            boolean expected = zoomAware || vanilla;
            String type = EntityType.getKey(e.getType()).getPath();
            drawn += isDrawn ? 1 : 0;
            vanillaDraws += vanilla ? 1 : 0;
            if (isDrawn != expected) {
                wrong++;
                wrongList.add(type + "@" + Math.round(d) + (isDrawn ? " drawn" : " missing"));
            }
            tsv.append(String.format(Locale.ROOT, "%s\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%b\t%b\t%b%n",
                type, e.getX(), e.getY(), e.getZ(), d, vanillaMax, vanilla, isDrawn, expected));
        }
        boolean hooked = EntityCapture.captures > 0;
        if (!hooked) {
            wrongList.add("no frame captured (LevelRenderer.submitEntities hook not installed?)");
        }
        boolean pass = hooked && tracked == expectedMobs && wrong == 0;
        String line = String.format(Locale.ROOT, "%s: entity check %s: tracked %d/%d, drawn %d, vanilla distance would draw %d, expected %s, viewScale %.2f%s",
            s.name(), pass ? "PASS" : "FAIL", tracked, expectedMobs, drawn, vanillaDraws,
            zoomAware ? "all (zoom-aware entity distance on)" : "vanilla's set", viewScale, wrongList.isEmpty() ? "" : ", wrong: " + wrongList);
        System.out.println("[bench] " + line);
        entityChecks.put(s.name(), String.format(Locale.ROOT,
            "{\"pass\": %b, \"expectedMobs\": %d, \"tracked\": %d, \"drawn\": %d, \"vanillaWouldDraw\": %d, \"zoomAware\": %b, \"viewScale\": %.3f, \"wrong\": %d}",
            pass, expectedMobs, tracked, drawn, vanillaDraws, zoomAware, viewScale, wrong));
        try {
            File dir = new File(shotDir, "entities");
            dir.mkdirs();
            java.nio.file.Files.writeString(new File(dir, s.name() + ".tsv").toPath(), tsv.toString());
        } catch (java.io.IOException ex) {
            ex.printStackTrace();
        }
    }

    private boolean drainMobs;

    /** True for a living non-player entity in the zoom scenes' mob area (rows 30-200 blocks south, 20 wide, 30 high). */
    private boolean inSceneArea(Entity e) {
        return e instanceof LivingEntity && !(e instanceof Player) && e.getZ() >= az + 30 && e.getZ() <= az + 200
            && Math.abs(e.getX() - ax) <= 20 && Math.abs(e.getY() - ay) <= 30;
    }

    private int sceneMobs(Minecraft mc) {
        int n = 0;
        if (mc.level != null) {
            for (Entity e : mc.level.entitiesForRendering()) {
                n += inSceneArea(e) ? 1 : 0;
            }
        }
        return n;
    }

    /** scene -> JSON object of its entity check, in run order (Bench writes them into the scenes result). */
    final Map<String, String> entityChecks = new LinkedHashMap<>();

    /** The patched client's zoom-aware entity distance (Zoom.DISTANCE_ENABLED, -Dmc.zoomEntityDistance); false on vanilla. */
    private static boolean zoomEntityDistance() {
        try {
            return Class.forName(ZoomHook.CLASS, true, Minecraft.class.getClassLoader()).getField("DISTANCE_ENABLED").getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Per-frame state of a zoom scene (no-op for other scenes): stance, HUD, spyglass or zoom key held while {@code on}. */
    private void optics(Minecraft mc, Scene s, boolean on) {
        ZoomSpec z = s.zoom();
        if (z == null) {
            return;
        }
        stand(mc, barrier());
        hideHud(mc, !z.hud());
        holdSpyglass(mc, on && z.optic() == Optic.SPYGLASS);
        if (z.optic() == Optic.ZOOM_KEY) {
            ZoomHook.hold(ZoomHook.key(), on);
        }
    }

    /**
     * True once the camera FOV has settled at the zoomed value (spyglass: exactly fov x 0.1). Old timing: unchanged within
     * 1e-4 degrees for a second. Fast timing: exactly unchanged over 4 player ticks: the spyglass halves its distance to the
     * target per tick and the zoom key snaps to its target, so a value that survives whole ticks is the float fixed point
     * both eases end at (the old wait also ended there: it needs about 28 ticks, the old check about 40 plus the freeze).
     */
    private boolean fovSettled(Minecraft mc, Scene s) {
        float fov = cameraFov(mc);
        long now = System.nanoTime();
        int tick = mc.player == null ? 0 : mc.player.tickCount;
        if (!(Math.abs(fov - oldFovSeen) <= 1e-4F)) {
            oldFovSeen = fov;   // the old criterion, tracked for the vignette emulation
            oldFovSinceAt = (now - phaseStart) / 1e9;
        }
        if (FAST ? fov != fovSeen : !(Math.abs(fov - fovSeen) <= 1e-4F)) {
            fovSeen = fov;
            fovSince = now;
            fovSinceTick = tick;
        }
        if (FAST ? tick - fovSinceTick < 4 : now - fovSince < 1_000_000_000L) {
            return false;
        }
        float base = mc.options.fov().get();
        if (s.zoom().optic() == Optic.SPYGLASS) {
            return mc.player != null && mc.player.isScoping() && Math.abs(fov - base * SpyglassItem.ZOOM_FOV_MODIFIER) < 0.01F;
        }
        return fov < base * 0.5F;
    }

    /**
     * Stand on the barrier under the anchor instead of flying (flying multiplies the FOV by 1.1). Keeps flying until
     * the barrier has reached the client, so the player never drops into the block.
     */
    static void stand(Minecraft mc, BlockPos barrier) {
        if (mc.player != null && mc.level != null) {
            mc.player.getAbilities().flying = !mc.level.getBlockState(barrier).is(Blocks.BARRIER);
        }
    }

    /** Holds the use key while {@code on} and the spyglass is in the main hand (it raises the spyglass); else releases it. */
    static void holdSpyglass(Minecraft mc, boolean on) {
        ZoomHook.hold(mc.options.keyUse, on && mc.player != null && mc.player.getMainHandItem().is(Items.SPYGLASS));
    }

    /** Sets the F1 state (hidden HUD also hides the hand, crosshair, block outline and scope overlay). */
    static void hideHud(Minecraft mc, boolean hidden) {
        if (mc.gui.hud.isHidden() != hidden) {
            mc.gui.hud.toggle();
        }
    }

    /** Vertical FOV of the last rendered frame (Camera.update runs later in renderFrame than the bench hook). */
    static float cameraFov(Minecraft mc) {
        return mc.gameRenderer.mainCamera().getFov();
    }

    /**
     * Vanilla eases the HUD vignette toward the camera's light level by 1 % per client tick (Hud.updateVignetteBrightness:
     * time constant 5 s, about 27 s to settle within 1/255), so a captured vignette depended on how many ticks had passed
     * since the world loaded and since the light changed, i.e. on how fast the world became ready: a slower start (machine
     * load, a second game in the GPU lock's functional lane) darkened day / day_north / sunrise by up to dE 1.6 (measured).
     * "settled" (default, -Dbench.scenes.vignette): every frame of every scene, set it to the value it converges to at the
     * camera (what a player standing there sees), which the per-tick easing then leaves unchanged; reference
     * run/pinned/shots-vkns-1512x982-vs (-vs-2, -vs-3 for the floor). "vanilla": old behaviour (references made before
     * 2026-10-05 15:50, e.g. run/pinned/shots-vkns-1512x982). The mode is written to screenshots/RUNNER.txt, so a pinned
     * reference says which runner made it.
     */
    static final boolean SETTLE_VIGNETTE = !"vanilla".equals(System.getProperty("bench.scenes.vignette", "settled"));

    static void settleVignette(Minecraft mc) {
        Entity camera = mc.getCameraEntity();
        if (!SETTLE_VIGNETTE || camera == null || mc.level == null) {
            return;
        }
        BlockPos eye = BlockPos.containing(camera.getX(), camera.getEyeY(), camera.getZ());
        float light = Lightmap.getBrightness(camera.level().dimensionType(), camera.level().getMaxLocalRawBrightness(eye));
        mc.gui.hud.vignetteBrightness = Mth.clamp(1.0F - light, 0.0F, 1.0F);
    }

    /** Clouds scroll with game time; pin it so every run sees the same cloud layout. */
    static void pinTime(Minecraft mc) {
        if (mc.level != null) {
            mc.level.setTimeFromServer(240_000L);
        }
    }

    /**
     * Fast timing, besides the old "ready" (compile queue empty, dimension, chunk count stable): no section is being built
     * (every section builder pack back in the pool) and the client has no light work queued; zoom scenes also need the
     * barrier on the client, the spyglass in the main hand (spyglass optic) and every mob the setup summons tracked.
     */
    private int quietWorld(Minecraft mc, Scene s) {
        if (mc.level == null) {
            return Q_QUEUE;
        }
        if (!buildersIdle(mc)) {
            return Q_BUILDERS;
        }
        if (!lightIdle(mc)) {
            return Q_LIGHT;
        }
        // The first-person hand lags behind a camera turn (LocalPlayer.xBob / yBob halve their distance to the view angles
        // per tick): wait for the float fixed point, which the old timing (90+ ticks after the turn) reached too. Any turn
        // gets there within 27 ticks; if mouse input keeps nudging the view, 30 ticks after the turn are enough (that noise
        // was in the old frames as well).
        if (mc.player != null && (mc.player.xBob != mc.player.xBobO || mc.player.yBob != mc.player.yBobO)
            && mc.player.tickCount - setupPlayerTick < 30) {
            return Q_HAND;
        }
        if (s.zoom() == null) {
            return -1;
        }
        if (!mc.level.getBlockState(barrier()).is(Blocks.BARRIER)) {
            return Q_BARRIER;
        }
        if (s.zoom().optic() == Optic.SPYGLASS && (mc.player == null || !mc.player.getMainHandItem().is(Items.SPYGLASS))) {
            return Q_SPYGLASS;
        }
        long summoned = s.setup().stream().filter(c -> c.startsWith("summon ")).count();
        return sceneMobs(mc) == summoned ? -1 : Q_MOBS;
    }

    private static final int Q_QUEUE = 0, Q_DIM = 1, Q_CHUNKS = 2, Q_BUILDERS = 3, Q_LIGHT = 4, Q_BARRIER = 5, Q_SPYGLASS = 6, Q_MOBS = 7,
        Q_FADE = 8, Q_HAND = 9;
    private static final String[] Q_NAMES = {"queue", "dim", "chunks", "builders", "light", "barrier", "spyglass", "mobs", "fade", "hand"};

    /**
     * Seconds from SETUP to the end of SETTLE (zoom scenes: FOV settled) that the old timing would have taken in this scene:
     * TICKED scenes settle by the old rule anyway; STATIC ones by "at least settle seconds and ready" (where this run saw
     * ready again after that mark, if it ran that long); zoom scenes add the old FOV wait (1e-4 degree stability, tracked
     * alongside, plus 1 s) to the moment the old timing would have raised the spyglass.
     */
    private double oldSettleSeconds(Scene s) {
        if (s.timing() == Timing.TICKED) {
            return settleSeconds;
        }
        double ready = s.zoom() == null ? settleSeconds : zoomStartAt;
        double oldReady = ready < s.settle() ? s.settle() : oldReadyAt >= 0.0 ? oldReadyAt : ready;
        if (s.zoom() == null) {
            return oldReady;
        }
        double fov = oldFovSinceAt >= zoomStartAt ? oldFovSinceAt - zoomStartAt + 1.0 : settleSeconds - zoomStartAt;
        return oldReady + fov;
    }

    /**
     * Applies {@code ticks} client ticks of the HUD vignette's low-pass filter (Hud.updateVignetteBrightness: 1 % per tick
     * toward 1 - the light level at the camera, the same float expression) with the current light level, which is constant
     * whenever this is called (frozen world or settled scene). The vignette darkens every HUD scene's frame edges according
     * to the light of the last ~5 s, so a faster runner would change those pixels; this replays the ticks the old timing
     * spent, so the frames match the references made with it (to its own +-1 tick jitter). Negative: this run spent more
     * ticks than the old one (a scene that waited longer for readiness); the filter is undone, v = b + (v - b) / 0.99.
     */
    static void emulateVignette(Minecraft mc, int ticks) {
        Entity camera = mc.getCameraEntity();
        if (ticks == 0 || camera == null || mc.level == null) {
            return;
        }
        BlockPos pos = BlockPos.containing(camera.getX(), camera.getEyeY(), camera.getZ());
        float levelBrightness = net.minecraft.client.renderer.Lightmap.getBrightness(camera.level().dimensionType(),
            camera.level().getMaxLocalRawBrightness(pos));
        float brightness = Mth.clamp(1.0F - levelBrightness, 0.0F, 1.0F);
        float v = mc.gui.hud.vignetteBrightness;
        for (int i = 0; i < ticks; i++) {
            v = v + (brightness - v) * 0.01F;
        }
        for (int i = 0; i > ticks; i--) {
            v = brightness + (v - brightness) / 0.99F;
        }
        mc.gui.hud.vignetteBrightness = v;
    }
    /** Frames of the current scene in which each quiet condition was the first to fail (diagnostics, scene timing line). */
    private final int[] notQuiet = new int[Q_NAMES.length];

    /**
     * One frame of the settle check (fast timing): {@code ready} is the old settle condition. True when the world has been
     * ready and quiet ({@link #quietWorld}) for 10 frames in a row and no section uploaded for the first time is still
     * fading in. Sporadic background work (fluids still flowing after the world load, far light updates) only has to pause
     * for 10 frames: the old timing captured it in whatever state it had at 3 s + 1.5 s, and so does this.
     */
    private boolean trackQuiet(Minecraft mc, Scene s, boolean ready) {
        int why;
        if (!ready) {
            why = mc.level == null || !mc.levelRenderer.hasRenderedAllSections() ? Q_QUEUE
                : !mc.level.dimension().identifier().getPath().equals(s.dim()) ? Q_DIM : Q_CHUNKS;
        } else {
            why = quietWorld(mc, s);
        }
        boolean quietNow = quiet.update(why < 0) >= 0.1 && quiet.frames >= 10;
        if (why < 0 && quietNow && !fadesDone(mc)) {
            why = Q_FADE;
        }
        if (why >= 0) {
            notQuiet[why]++;
        }
        return why < 0 && quietNow;
    }

    private String notQuietSummary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Q_NAMES.length; i++) {
            if (notQuiet[i] > 0) {
                sb.append(sb.isEmpty() ? "" : " ").append(Q_NAMES[i]).append('=').append(notQuiet[i]);
            }
        }
        return sb.isEmpty() ? "none" : sb.toString();
    }

    /**
     * True when every section's first upload is at least chunkSectionFadeInTime (vanilla 0.75 s) + 50 ms old, i.e. no
     * section is still fading in (SectionRenderDispatcher.RenderSection.getVisibility: alpha = time since its first upload /
     * fade time). Reads RenderSection.uploadedTime (Util.getMillis base; 0 = never uploaded) of every section in the view
     * area; true when the fields cannot be read (then only the other conditions apply).
     */
    static boolean fadesDone(Minecraft mc) {
        reflect();
        if (viewAreaSections == null || uploadedTime == null) {
            return true;
        }
        Object viewArea = mc.levelRenderer.viewArea();
        if (viewArea == null) {
            return true;
        }
        long fadeMs = Math.round(mc.options.chunkSectionFadeInTime().get() * 1000.0) + 50L;
        long now = net.minecraft.util.Util.getMillis();
        try {
            for (Object section : (Iterable<?>) viewAreaSections.get(viewArea)) {
                long t = section == null ? 0L : (long) uploadedTime.get(section);
                if (t != 0L && now - t < fadeMs) {
                    return false;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
        return true;
    }

    /** Continuous span (seconds) and frame count a condition has held; any false frame restarts it. */
    static final class Quiet {
        private long since;
        int frames;

        void reset() {
            since = 0L;
            frames = 0;
        }

        double update(boolean ok) {
            if (!ok) {
                reset();
                return 0.0;
            }
            long now = System.nanoTime();
            if (frames++ == 0) {
                since = now;
            }
            return (now - since) / 1e9;
        }
    }

    /** The integrated server's tick count, or -1 (no singleplayer server). */
    static int serverTick(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        return server == null ? -1 : server.getTickCount();
    }

    /** Server ticks since {@code tick}: 3 means the commands sent before it ran and the tick after them was broadcast. */
    static int serverTicksSince(Minecraft mc, int tick) {
        int now = serverTick(mc);
        return now < 0 || tick < 0 ? Integer.MAX_VALUE : now - tick;
    }

    private static java.lang.reflect.Field builderQueue;
    private static java.lang.reflect.Field lightQueue;
    private static java.lang.reflect.Field viewAreaSections;
    private static java.lang.invoke.VarHandle uploadedTime;
    private static boolean reflectionTried;

    private static void reflect() {
        if (reflectionTried) {
            return;
        }
        reflectionTried = true;
        try {
            viewAreaSections = net.minecraft.client.renderer.ViewArea.class.getDeclaredField("sections");
            viewAreaSections.setAccessible(true);
            Class<?> section = net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection.class;
            uploadedTime = java.lang.invoke.MethodHandles.privateLookupIn(section, java.lang.invoke.MethodHandles.lookup())
                .findVarHandle(section, "uploadedTime", long.class);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[bench] scenes: section upload times not readable (" + e + "): no fade-in check");
        }
        try {
            builderQueue = net.minecraft.client.renderer.SectionBufferBuilderPool.class.getDeclaredField("freeBuffers");
            builderQueue.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[bench] scenes: section builder pool not inspectable (" + e + "): compile queue only");
        }
        try {
            lightQueue = net.minecraft.client.multiplayer.ClientLevel.class.getDeclaredField("lightUpdateQueue");
            lightQueue.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[bench] scenes: client light queue not inspectable (" + e + "): light engine only");
        }
    }

    /** Every section builder pack is back in the pool: no section is being compiled right now. */
    static boolean buildersIdle(Minecraft mc) {
        reflect();
        if (builderQueue == null) {
            return true;
        }
        try {
            java.util.concurrent.ArrayBlockingQueue<?> q =
                (java.util.concurrent.ArrayBlockingQueue<?>) builderQueue.get(mc.gameRenderer.renderBuffers().sectionBufferPool());
            return q.remainingCapacity() == 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    /** No light update packet waiting and no light work in the client's light engine. */
    static boolean lightIdle(Minecraft mc) {
        reflect();
        if (mc.level.getLightEngine().hasLightWork()) {
            return false;
        }
        try {
            return lightQueue == null || ((java.util.Collection<?>) lightQueue.get(mc.level)).isEmpty();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    /** A complete PNG file: at least a signature and the 12-byte IEND chunk, ending with IEND's type and CRC. */
    static boolean pngComplete(File f) {
        long n = f.length();
        if (n < 20) {
            return false;
        }
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            byte[] tail = new byte[8];
            r.seek(n - 8);
            r.readFully(tail);
            return tail[0] == 'I' && tail[1] == 'E' && tail[2] == 'N' && tail[3] == 'D'
                && (tail[4] & 0xFF) == 0xAE && (tail[5] & 0xFF) == 0x42 && (tail[6] & 0xFF) == 0x60 && (tail[7] & 0xFF) == 0x82;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** True once the client's loaded chunk count has not changed for two seconds. */
    static final class ChunkWait {
        private int lastChunkCount = -1;
        private long chunkStableSince;

        boolean stable(Minecraft mc) {
            int n = mc.level.getChunkSource().getLoadedChunksCount();
            long now = System.nanoTime();
            if (n != lastChunkCount) {
                lastChunkCount = n;
                chunkStableSince = now;
                return false;
            }
            return now - chunkStableSince > 2_000_000_000L;
        }
    }

    private void next(Phase p) {
        phase = p;
        phaseStart = System.nanoTime();
    }

    static void cmd(Minecraft mc, String c) {
        if (mc.player != null) {
            mc.player.connection.sendCommand(c);
        }
    }

    private static void aim(LocalPlayer player, Scene s) {
        aim(player, s.yaw(), s.pitch());
    }

    static void aim(LocalPlayer player, float yaw, float pitch) {
        if (player == null) {
            return;
        }
        player.setYRot(yaw);
        player.yRotO = yaw;
        player.setXRot(pitch);
        player.xRotO = pitch;
    }
}
