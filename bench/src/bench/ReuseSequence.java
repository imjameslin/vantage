package bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;

/**
 * Opt-in {@code bench.mode=reuseseq}: two full-resolution Lockstep camera trajectories, feature off then on.
 * {@code bench.reuseFeature=reuse} switches {@code LodReuse.enabled}; ON holds must observe exact-image reuse.
 * {@code bench.reuseFeature=items} switches {@code LodGpu.cacheItems}, requires an actual ON cached item build,
 * and uses phase0-motion with async/image reuse disabled: synchronous CPU metadata motion evidence only.
 * {@code bench.reuseFeature=groups} holds cacheItems=true and toggles LodGpu.cacheGroups; diagnostics must
 * be off. Both arms must build in their sampled mode (2 OFF / 3 ON), with nonempty cached appends in ON.
 * {@code bench.reuseFeature=nodes} holds items/groups on, diagnostics/profiling off, and toggles
 * LodGpu.cacheNodeItems. Captured mode-0 OFF/mode-1 ON builds and ON packet replay are required.
 * {@code bench.reuseFeature=prepare} toggles LodGpu.asyncItems, holding item/group caches and atomic mesh
 * handoff on, node caching/diagnostics/profiling/far rendering/image reuse off. Captured ON renders must
 * offer and accept nonempty worker results; synchronous bootstrap alone never qualifies. PNG readbacks
 * can change worker cadence: this is spatial/temporal image evidence, never latency/FPS evidence.
 * {@code bench.reuseFeature=opaque} toggles LodGpu.asyncOpaque with prepare's held controls and
 * asyncItems=true in both arms. Requires captured ON opaqueFrames growth and none in OFF;
 * an active async list suffices, without requiring worker accepts or adding RUN waits.
 * {@code bench.reuseFeature=handoff} is BENCHMARK-ONLY: toggles volatile LodGpu.nearHandoff under
 * startup mc.lod.nearHandoff=true in BOTH arms. Requires captured ON native activity, covered slots
 * and coverage revision growth. Async items/far rendering/image reuse/node caching/diagnostics/profile
 * stay off; item/group caches and atomicMeshHandoff stay on. This measures runtime coverage/retention,
 * not startup EMPTY-publication, the held atomic-handoff prerequisite, or all flying flashing.
 * The OFF arm is the scripted synchronous reference for frozen-jar comparisons.
 * Read-only boundary diagnostics cannot qualify. With reuseBoundaryPreset=handoff28 and
 * reuseBoundaryReference=true, capture only four settled OFF poses for static-tree attribution.
 * That diagnostic waits for terrain and does not match animation clocks or constitute motion evidence.
 * Optional {@code bench.reuseTrajectory=phase0-motion}: 120 consecutive renders of simultaneous eastward
 * flight at 10.9 blocks/logical second and yaw at 360 degrees/logical second. This never establishes
 * Phase 1 far-image async age, sustainable cadence or motion acceptance.
 * Run through launch.py's lock/Prism guards with the same full-detail settings as reuse-run.py,
 * {@code -Dmc.lod.reuse=false}, {@code --bench-prop world=bench}, {@code --bench-prop photo=0},
 * {@code --bench-prop f3=off}, and a fresh {@code --bench-prop shots=<directory>}.
 * The default spot is (6.5,106,6.5), yaw 200, pitch 12; bench.pos/yaw/pitch override it.
 * Output: screenshots/reuseseq/{off,on}/frame-NNN.png and manifest.json. Existing output is never replaced.
 *
 * Every RUN render is captured at the NEXT pre-render hook, before changing camera or arm. The default arm has
 * 25 consecutive scripted renders: eight initial holds, yaw changes, translations, stop/hold, move,
 * eight final holds. At most one arm's 25 (default) or 120 (phase0-motion) readbacks/writers are outstanding;
 * two PNG writers share a bounded queue; drain between arms, never insert writer holds
 * inside a sequence. The PNG callback and complete-file check must finish before successful completion.
 *
 * Each arm resets Lockstep.start(8), runs the same four-tick off prefix, then captures every render:
 * 1/8 tick (6.25 ms virtual time) per frame, with the world frozen. Eight frames per tick leave room
 * for cull/shader admission between lighting updates. Actual clock, camera/FOV and reuse counters are
 * recorded; mismatches or no image-reuse hit during a default trajectory's ON hold fail capture. This is a scripted camera
 * trajectory, not physics flight. Asynchronous LOD, lightmap state and wall-time effects are not reset.
 * No perceptual comparison, image-gate pass or FPS claim is made here; timing remains ABAB.
 */
final class ReuseSequence {
    private enum Phase { SETUP, PREWARM, RESET, PREFIX, RUN, REFERENCE_WAIT, REFERENCE_STAGE, REFERENCE_CAPTURE, DRAIN, BOUNDARY_REFERENCE, BOUNDARY_DRAIN, CLEANUP, DONE }
    private record Pose(String name, double x, double y, double z, float yaw, float pitch) {}
    record Clock(long frame, long steps, long nanos, float deltaTicks, float playerPartial, float worldPartial, float cameraPartial) {}
    private record Rendered(int index, Pose pose, boolean enabled, double eyeY, Clock clock) {}
    private record View(double x, double y, double z, float yaw, float pitch, float fov) {}

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final String FEATURE = System.getProperty("bench.reuseFeature", "reuse");
    private static final boolean BOUNDARY_REFERENCE = Boolean.getBoolean("bench.reuseBoundaryReference");
    private static final boolean READY_REFERENCE = Boolean.getBoolean("bench.reuseReadyReference");
    private static final boolean ITEMS = "items".equals(FEATURE);
    private static final boolean GROUPS = "groups".equals(FEATURE);
    private static final boolean NODES = "nodes".equals(FEATURE);
    private static final boolean PREPARE = "prepare".equals(FEATURE);
    private static final boolean OPAQUE = "opaque".equals(FEATURE);
    private static final boolean HANDOFF = "handoff".equals(FEATURE);
    private static final boolean METADATA = ITEMS || GROUPS || NODES || PREPARE;
    private static final boolean MOTION_FEATURE = METADATA || HANDOFF || OPAQUE;
    private static final String TRAJECTORY = System.getProperty("bench.reuseTrajectory", MOTION_FEATURE ? "phase0-motion" : "default");
    private static final boolean PHASE0_MOTION = "phase0-motion".equals(TRAJECTORY);
    private static final String COUNTERS = HANDOFF ? "nearHandoff" : PREPARE || OPAQUE ? "asyncItems" : NODES ? "nodeItems" : GROUPS ? "groupCache" : ITEMS ? "itemCache" : "reuse";
    private static final String HIT_FIELD = ITEMS ? "cachedBuilds" : "hits";
    private static final String TOGGLE_CLASS = "net.minecraft.client.renderer.chunk." + (MOTION_FEATURE ? "LodGpu" : "LodReuse");
    private static final String TOGGLE_FIELD = OPAQUE ? "asyncOpaque" : HANDOFF ? "nearHandoff" : PREPARE ? "asyncItems" : NODES ? "cacheNodeItems" : GROUPS ? "cacheGroups" : ITEMS ? "cacheItems" : "enabled";
    // Same accounting as reuse-run.py prepare_activity(), including its OFF upload-deferral exception.
    static final List<String> PREPARE_COUNTERS = List.of("offers", "accepted", "rejected", "bootstrap", "fallbacks",
        "uploadDefers", "captureNs", "installNs", "workerNs", "acceptedClusters");
    private static final List<String> PREPARE_REQUIRED = List.of("offers", "accepted", "acceptedClusters", "workerNs");
    private static final String PREPARE_LIMITATION = "CPU draw-record worker spatial/temporal image evidence only. "
        + "Motion PNG readbacks and writers can change worker cadence; no latency/FPS or far-image async acceptance claim.";
    private static final String HANDOFF_LIMITATION = "BENCHMARK-ONLY runtime near-handoff coverage/retention OFF/ON evidence. "
        + "Startup EMPTY-publication and atomicMeshHandoff are held prerequisites, not qualified by this comparison. "
        + "Does not qualify all flying flashing, image-gate acceptance, latency/FPS or sustainable cadence.";
    private static final int WIDTH = 3024, HEIGHT = 1964;
    private static final int FRAMES_PER_TICK = 8, PREFIX_FRAMES = 4 * FRAMES_PER_TICK;
    private static final long LOGICAL_FRAME_NANOS = 50_000_000L / FRAMES_PER_TICK;
    private static final int HOLD_FRAMES = 8;
    private static final int MAX_CAPTURES_PER_ARM = 120;
    private static final double PHASE0_FLIGHT_BLOCKS_PER_SECOND = 10.9;
    private static final double PHASE0_TURN_DEGREES_PER_SECOND = 360.0;
    private final File shotDir;
    private final Path output;
    private final Field enabled;
    private final Map<String, Object> startupArm;
    private final List<Pose> poses;
    private final List<Map<String, Object>> frames = new ArrayList<>();
    private final List<View> reference = new ArrayList<>();
    private final List<Clock> referenceClocks = new ArrayList<>();
    private final boolean[] clockObserved = new boolean[2];
    private final int[] capturedFramesByArm = new int[2];
    private final long[] itemBuildsAtArmStart = {-1, -1}, itemBuildsAtArmEnd = {-1, -1};
    private final int[] itemAppendFramesByArm = new int[2];
    private final long[][] groupBuildsAtArmStart = new long[2][], groupBuildsAtArmEnd = new long[2][];
    private final NodeCounters[] nodeCountersAtArmStart = new NodeCounters[2], nodeCountersAtArmEnd = new NodeCounters[2];
    private NodeCounters previousNodeCounters;
    private final PrepareCounters[] prepareCountersAtArmStart = new PrepareCounters[2], prepareCountersAtArmEnd = new PrepareCounters[2];
    private PrepareCounters previousPrepareCounters;
    private final long[] opaqueFramesAtArmStart = {-1, -1}, opaqueFramesAtArmEnd = {-1, -1};
    private long previousOpaqueFrames;
    private final HandoffCounters[] handoffCountersAtArmStart = new HandoffCounters[2], handoffCountersAtArmEnd = new HandoffCounters[2];
    private HandoffCounters previousHandoffCounters;
    private final int[] handoffCoveredRevisionFrames = new int[2];
    private final AtomicInteger pending = new AtomicInteger();
    private final CaptureWriter imageWriter = new CaptureWriter(2, MAX_CAPTURES_PER_ARM);
    private final AtomicReference<String> writerError = new AtomicReference<>();
    private final Scenes.ChunkWait chunks = new Scenes.ChunkWait();
    private Phase phase = Phase.SETUP;
    private long phaseStart = System.nanoTime();
    private int arm, nextFrame, resetFrames;
    private int peakPendingCaptures;
    private Rendered last;
    private boolean hudWasHidden, overlayWasVisible, wasFrozen, savedEnabled, setup;
    private Pose savedPose;
    private Map<String, Object> settings;
    private Object config;
    private String failure;
    private boolean complete;
    private long previousHits, onHeldHits;
    private long previousItemAppends;
    private int prewarmIndex;
    private long prewarmStart;
    private int boundaryReferenceFrame = 28;
    private final List<Map<String, Object>> boundaryReferences = new ArrayList<>();
    private long referenceWaitRenders;
    private int referenceReadyRenders;
    private long referenceReadySince;
    private LodReferenceReadiness.State referenceReadiness;
    private int referenceServerChunkX=Integer.MIN_VALUE, referenceServerChunkZ=Integer.MIN_VALUE;
    private final ReferenceImageInputs referenceImageInputs = new ReferenceImageInputs();
    private final List<Map<String,Object>> referenceRenderInputs = new ArrayList<>();

    ReuseSequence(File shotDir) {
        try {
            if (!"reuse".equals(FEATURE) && !MOTION_FEATURE) {
                throw new IllegalArgumentException("unknown bench.reuseFeature: " + FEATURE);
            }
            if (!"default".equals(TRAJECTORY) && !PHASE0_MOTION) {
                throw new IllegalArgumentException("unknown bench.reuseTrajectory: " + TRAJECTORY);
            }
            if (MOTION_FEATURE && !PHASE0_MOTION) throw new IllegalArgumentException(FEATURE + " requires phase0-motion's 120 consecutive renders");
            if (BOUNDARY_REFERENCE && (!HANDOFF || !LodBoundaryProbe.ENABLED
                    || !"handoff28".equals(System.getProperty("bench.reuseBoundaryPreset"))))
                throw new IllegalArgumentException("settled boundary reference requires handoff28 read-only diagnostics; never a motion qualification");
            if (READY_REFERENCE && (!HANDOFF || BOUNDARY_REFERENCE || LodBoundaryProbe.ENABLED))
                throw new IllegalArgumentException("ready reference is handoff-only and excludes boundary diagnostics");
            this.shotDir = shotDir;
            this.output = shotDir.toPath().toAbsolutePath().resolve("screenshots/reuseseq");
            enabled = Class.forName(TOGGLE_CLASS).getField(TOGGLE_FIELD);
            enabled.setAccessible(true); // LodGpu is package-private even though its toggles are public.
            if (enabled.getType() != boolean.class || !Modifier.isStatic(enabled.getModifiers()) || Modifier.isFinal(enabled.getModifiers())) {
                throw new IllegalStateException("reuseseq requires mutable static " + TOGGLE_CLASS + "." + TOGGLE_FIELD + " boolean");
            }
            if ((HANDOFF || OPAQUE) && !Modifier.isVolatile(enabled.getModifiers()))
                throw new IllegalStateException(FEATURE + " requires volatile LodGpu." + TOGGLE_FIELD);
            startupArm = HANDOFF ? handoffStartupArm() : null;
            if (HANDOFF) handoffSettings(); // Fail admission before creating output or mutating the runtime arm.
            if (OPAQUE) opaqueSettings();
            String[] pos = System.getProperty("bench.pos", "6.5,106,6.5").split(",");
            if (pos.length != 3) throw new IllegalArgumentException("bench.pos must be x,y,z");
            double x = Double.parseDouble(pos[0]), y = Double.parseDouble(pos[1]), z = Double.parseDouble(pos[2]);
            float yaw = Float.parseFloat(System.getProperty("bench.yaw", "200"));
            float pitch = Float.parseFloat(System.getProperty("bench.pitch", "12"));
            if (!Double.isFinite(x + y + z) || !Float.isFinite(yaw + pitch)) throw new IllegalArgumentException("nonfinite pose");
            List<Pose> trajectory = new ArrayList<>();
            if (PHASE0_MOTION) {
                for (int i = 0; i < MAX_CAPTURES_PER_ARM; i++) {
                    // Derive each sample from the absolute logical index, never accumulated wall time.
                    double seconds = (i * LOGICAL_FRAME_NANOS) / 1_000_000_000.0;
                    trajectory.add(new Pose("phase0-flight-turn-" + i,
                        x + PHASE0_FLIGHT_BLOCKS_PER_SECOND * seconds, y, z,
                        (float)(yaw + PHASE0_TURN_DEGREES_PER_SECOND * seconds), pitch));
                }
            } else {
                for (int i = 0; i < HOLD_FRAMES; i++) trajectory.add(new Pose("spot-hold-" + i, x, y, z, yaw, pitch));
                trajectory.addAll(List.of(
                    new Pose("yaw-0", x, y, z, yaw + 5, pitch),
                    new Pose("yaw-1", x, y, z, yaw + 15, pitch),
                    new Pose("yaw-2", x, y, z, yaw + 30, pitch),
                    new Pose("translate-0", x + 0.2725, y, z, yaw + 30, pitch),
                    new Pose("translate-1", x + 0.5450, y, z, yaw + 30, pitch),
                    new Pose("translate-2", x + 0.8175, y, z, yaw + 30, pitch),
                    new Pose("stop-hold-0", x + 0.8175, y, z, yaw + 30, pitch),
                    new Pose("stop-hold-1", x + 0.8175, y, z, yaw + 30, pitch),
                    new Pose("move-again", x + 1.0900, y, z, yaw + 30, pitch)));
                for (int i = 0; i < HOLD_FRAMES; i++) trajectory.add(new Pose("hold-again-" + i, x + 1.0900, y, z, yaw + 30, pitch));
            }
            if (trajectory.isEmpty() || trajectory.size() > MAX_CAPTURES_PER_ARM) {
                throw new IllegalArgumentException("reuseseq capture budget exceeded");
            }
            poses = List.copyOf(trajectory);
            Files.createDirectories(output.getParent());
            Files.createDirectory(output);
            Files.createDirectory(output.resolve("off"));
            Files.createDirectory(output.resolve("on"));
            if (BOUNDARY_REFERENCE) Files.createDirectory(output.resolve("settled"));
        } catch (ReflectiveOperationException | IOException e) {
            throw new IllegalStateException("reuseseq setup failed", e);
        }
    }

    boolean step(Minecraft mc) {
        if (phase == Phase.DONE) return true;
        try {
            if (phase == Phase.SETUP || phase == Phase.PREWARM
                    || phase == Phase.RESET || phase == Phase.PREFIX || phase == Phase.RUN
                    || phase == Phase.BOUNDARY_REFERENCE || phase == Phase.REFERENCE_WAIT
                    || phase == Phase.REFERENCE_STAGE || phase == Phase.REFERENCE_CAPTURE) requireTarget(mc);
            switch (phase) {
                case SETUP -> {
                    if (!mc.hasSingleplayerServer() || !LodHook.active()) throw new IllegalStateException("reuseseq requires local singleplayer LOD");
                    if (Photo.COUNT != 0 || !"off".equals(Photo.F3)) throw new IllegalStateException("reuseseq requires photo=0 and f3=off");
                    savedEnabled = enabled.getBoolean(null);
                    hudWasHidden = mc.gui.hud.isHidden();
                    overlayWasVisible = mc.debugEntries.isOverlayVisible();
                    wasFrozen = mc.level.tickRateManager().isFrozen();
                    LocalPlayer p = mc.player;
                    savedPose = new Pose("restore", p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
                    settings = settings(mc);
                    ConfigBlock.prepare();
                    config = JsonParser.parseString(ConfigBlock.json(mc, 0));
                    setup = true;
                    for (String command : List.of("gamerule advance_time false", "gamerule advance_weather false",
                            "gamerule random_tick_speed 0", "gamerule max_snow_accumulation_height 0", "time set 6000", "weather clear", "tick freeze")) {
                        Scenes.cmd(mc, command);
                    }
                    setArm(false);
                    apply(mc, poses.get(BOUNDARY_REFERENCE ? boundaryReferenceFrame : 0));
                    prewarmStart = System.nanoTime();
                    next(BOUNDARY_REFERENCE ? Phase.BOUNDARY_REFERENCE : Phase.PREWARM);
                    manifest();
                }
                case BOUNDARY_REFERENCE -> {
                    // Attribution only: OFF at four fully settled poses. No Lockstep/motion claim.
                    setArm(false);
                    Pose pose = poses.get(boundaryReferenceFrame);
                    apply(mc, pose);
                    if (elapsed() > LodHook.WAIT_S) throw new IllegalStateException("settled boundary reference deadline");
                    if (elapsed() >= 2 && frozen(mc) && mc.levelRenderer.hasRenderedAllSections()
                            && chunks.stable(mc) && LodHook.idle()) {
                        Camera camera = mc.gameRenderer.mainCamera();
                        var eye = camera.position();
                        View view = new View(eye.x, eye.y, eye.z, camera.yRot(), camera.xRot(), camera.getFov());
                        if (!near(view.x(), pose.x()) || !near(view.y(), pose.y() + mc.player.getEyeHeight())
                                || !near(view.z(), pose.z()) || !near(view.yaw(), pose.yaw()) || !near(view.pitch(), pose.pitch()))
                            throw new IllegalStateException("settled reference camera mismatch");
                        String file = "settled/frame-%03d.png".formatted(boundaryReferenceFrame);
                        boundaryReferences.add(Map.of("file", file, "frame", boundaryReferenceFrame,
                            "actualCamera", view, "boundaryDiagnostic", LodBoundaryProbe.sample(mc, boundaryReferenceFrame),
                            "nearAllBuilt", true, "lodIdle", true, "settleSeconds", elapsed()));
                        pending.incrementAndGet();
                        try {
                            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), 1, image ->
                                imageWriter.submit(image, () -> {
                                    image.writeToFile(output.resolve(file));
                                    if (!Scenes.pngComplete(output.resolve(file).toFile())) throw new IOException("incomplete " + file);
                                }, error -> {
                                    if (error != null) writerError.compareAndSet(null, error.toString());
                                    pending.decrementAndGet();
                                }));
                        } catch (RuntimeException | Error e) { pending.decrementAndGet(); throw e; }
                        next(Phase.BOUNDARY_DRAIN);
                        manifest();
                    }
                }
                case BOUNDARY_DRAIN -> {
                    if (pending.get() != 0 && elapsed() < 60) return false;
                    if (pending.get() != 0 || writerError.get() != null) throw new IllegalStateException("settled reference writer failure");
                    if (++boundaryReferenceFrame <= 31) {
                        apply(mc, poses.get(boundaryReferenceFrame));
                        next(Phase.BOUNDARY_REFERENCE);
                    } else {
                        complete = true; // Diagnostic marker still forces captureComplete=false.
                        cleanup(mc);
                        next(Phase.CLEANUP);
                    }
                    manifest();
                }
                case PREWARM -> {
                    // A settled starting view does not guarantee that vanilla has compiled chunks
                    // behind the camera. Otherwise arm A exposes holes and arm B inherits its work.
                    // Visit every distinct trajectory pose before EITHER measured image sequence.
                    setArm(false);
                    apply(mc, poses.get(prewarmIndex));
                    if ((System.nanoTime() - prewarmStart) * 1.0e-9 > Math.max(180.0, LodHook.WAIT_S)) {
                        throw new IllegalStateException("reuseseq full-trajectory prewarm deadline");
                    }
                    if (elapsed() >= 0.5 && frozen(mc) && mc.levelRenderer.hasRenderedAllSections()
                            && chunks.stable(mc) && LodHook.idle()) {
                        Pose prior = poses.get(prewarmIndex++);
                        while (prewarmIndex < poses.size() && samePose(prior, poses.get(prewarmIndex))) prewarmIndex++;
                        if (prewarmIndex == poses.size()) {
                            resetFrames = 0;
                            apply(mc, poses.getFirst());
                            next(Phase.RESET);
                        } else {
                            apply(mc, poses.get(prewarmIndex));
                            next(Phase.PREWARM);
                        }
                        manifest();
                    }
                }
                case RESET -> {
                    setArm(false); // Two or more off renders invalidate any previous arm's cached frame.
                    apply(mc, poses.getFirst());
                    resetFrames++;
                    if (elapsed() > Math.max(120.0, LodHook.WAIT_S)) throw new IllegalStateException("reuseseq settle/freeze deadline");
                    if (resetFrames >= 3 && elapsed() >= 2 && frozen(mc) && mc.levelRenderer.hasRenderedAllSections()
                            && chunks.stable(mc) && LodHook.idle()) {
                        lodState(); // Fail closed on actual tau or absent exact-reuse counters.
                        nextFrame = 0;
                        if (READY_REFERENCE) referenceImageInputs.beginArm(mc);
                        Lockstep.start(FRAMES_PER_TICK); // Reset every arm; this render still has the old timer values.
                        ZoomHook.bindClock(true);
                        next(Phase.PREFIX);
                    }
                }
                case PREFIX -> {
                    setArm(false);
                    apply(mc, poses.getFirst());
                    if (elapsed() > 5) throw new IllegalStateException("reuseseq Lockstep prefix deadline / missing timer hook");
                    if (Lockstep.steps() == 0) return false; // Never capture the render that called start().
                    clock(mc); // Verify the real DeltaTracker readbacks, not just the helper's state.
                    if (Lockstep.frame() >= PREFIX_FRAMES - (READY_REFERENCE && arm == 0 ? 1 : 0)) {
                        JsonObject state = lodState();
                        if (OPAQUE) {
                            previousOpaqueFrames = opaqueFrames(state, false);
                            opaqueFramesAtArmStart[arm] = previousOpaqueFrames;
                        } else if (HANDOFF) {
                            previousHandoffCounters = handoffCounters(state, false);
                            handoffCountersAtArmStart[arm] = previousHandoffCounters;
                        } else if (PREPARE) {
                            previousPrepareCounters = prepareCounters(state, false);
                            prepareCountersAtArmStart[arm] = previousPrepareCounters;
                        } else if (NODES) {
                            previousNodeCounters = nodeCounters(state.getAsJsonObject("gpu"), enabled.getBoolean(null));
                            nodeCountersAtArmStart[arm] = previousNodeCounters;
                        } else if (GROUPS) {
                            groupBuildsAtArmStart[arm] = groupBuilds(state.getAsJsonObject("gpu"), enabled.getBoolean(null));
                            previousHits = groupBuildsAtArmStart[arm][2 + arm];
                            previousItemAppends = itemHits(state.getAsJsonObject("gpu").getAsJsonObject("itemCache"));
                        } else previousHits = state.getAsJsonObject("gpu").getAsJsonObject(COUNTERS).get(HIT_FIELD).getAsLong();
                        if (ITEMS) {
                            itemBuildsAtArmStart[arm] = previousHits;
                            previousItemAppends = itemAppends(state.getAsJsonObject("gpu").getAsJsonObject(COUNTERS));
                        }
                        if (READY_REFERENCE && arm == 0) prepareReference(mc);
                        else { next(Phase.RUN); stage(mc); }
                    }
                }
                case REFERENCE_WAIT -> {
                    if (arm != 0 || !READY_REFERENCE) throw new IllegalStateException("reference wait reached candidate");
                    setArm(false); apply(mc, poses.get(nextFrame));
                    referenceWaitRenders++;
                    referenceReadiness=LodReferenceReadiness.read(mc);
                    JsonObject state=lodState();
                    boolean ready=referenceReadiness.ready() && referenceLodReady(state);
                    referenceReadyRenders=ready ? referenceReadyRenders+1 : 0;
                    if(!ready || referenceReadySince==0)referenceReadySince=System.nanoTime();
                    if (elapsed()>30) throw new IllegalStateException("reference readiness deadline: "+referenceReadiness+" LOD="+referenceLodSummary(state));
                    if ((System.nanoTime()-referenceReadySince)>=150_000_000L && referenceReadyRenders>=2) {
                        Lockstep.hold(false); next(Phase.REFERENCE_STAGE);
                    }
                }
                case REFERENCE_STAGE -> {
                    stage(mc); // One normal 1/8-tick render, with the same logical clock as ON.
                    Lockstep.hold(true);
                    next(Phase.REFERENCE_CAPTURE);
                }
                case REFERENCE_CAPTURE -> {
                    capture(mc,last); last=null;
                    if (nextFrame==poses.size()) next(Phase.DRAIN);
                    else prepareReference(mc);
                    if(nextFrame%30==0)manifest();
                }
                case RUN -> {
                    // Camera.update and rendering happened after the previous hook. Capture first!
                    if (last != null) {
                        capture(mc, last);
                        last = null;
                    }
                    if (nextFrame == poses.size()) {
                        if (OPAQUE) {
                            if (!opaqueActivityValid(arm, opaqueFramesAtArmStart[arm], opaqueFramesAtArmEnd[arm]))
                                fail("opaque requires captured ON opaqueFrames growth and no OFF growth; zero eligible ON frames cannot qualify; arm=" + arm);
                        } else if (HANDOFF) {
                            if (!handoffActivityValid(arm, handoffCountersAtArmStart[arm], handoffCountersAtArmEnd[arm], handoffCoveredRevisionFrames[arm]))
                                fail("handoff requires captured active ON coverage with revision growth; requested/warmup alone cannot qualify; arm=" + arm);
                        } else if (PREPARE) {
                            if (!prepareActivityValid(arm, prepareCountersAtArmStart[arm], prepareCountersAtArmEnd[arm]))
                                fail("prepare requires captured ON offers/accepted/acceptedClusters/workerNs growth and no OFF CPU activity; bootstrap/warmup alone cannot qualify; arm=" + arm);
                        } else if (NODES) {
                            if (!nodeActivityValid(arm, nodeCountersAtArmStart[arm], nodeCountersAtArmEnd[arm]))
                                fail("nodes requires mode-0 OFF/mode-1 ON builds and ON packet hits plus replayed clusters within captured renders; arm=" + arm);
                        } else if (GROUPS) {
                            if (!groupActivityValid(arm, groupBuildsAtArmStart[arm], groupBuildsAtArmEnd[arm], itemAppendFramesByArm[arm]))
                                fail("groups requires only mode-2 OFF/mode-3 ON builds and a nonempty cached append in ON; arm=" + arm);
                        } else if (ITEMS) {
                            long start = itemBuildsAtArmStart[arm], end = itemBuildsAtArmEnd[arm];
                            if (!itemActivityValid(arm, start, end, itemAppendFramesByArm[arm]))
                                fail("items requires zero OFF cached builds and an ON cached build appending nonempty clusters within captured renders; arm="
                                    + arm + " start=" + start + " end=" + end);
                        } else if (!PHASE0_MOTION && arm == 1 && onHeldHits == 0) {
                            fail("no image-reuse hits measured during ON held trajectory frames");
                        }
                        Lockstep.hold(true);
                        next(Phase.DRAIN);
                    } else {
                        stage(mc);
                    }
                }
                case DRAIN -> {
                    if (pending.get() != 0 && elapsed() < 60) return false;
                    if (pending.get() != 0) fail("screenshot writer drain deadline; pending=" + pending.get());
                    if (writerError.get() != null) fail(writerError.get());
                    if (failure == null && arm == 0) {
                        Lockstep.stop();
                        arm = 1;
                        resetFrames = 0;
                        next(Phase.RESET);
                        setArm(false);
                        apply(mc, poses.getFirst());
                        manifest();
                    } else {
                        complete = failure == null && frames.size() == 2 * poses.size();
                        cleanup(mc);
                        next(Phase.CLEANUP);
                        manifest();
                    }
                }
                case CLEANUP -> {
                    if (elapsed() >= 2) {
                        next(Phase.DONE);
                        manifest();
                        return true;
                    }
                }
                case DONE -> { return true; }
            }
        } catch (Exception | LinkageError e) {
            fail(e.toString());
            last = null;
            Lockstep.hold(true);
            // Preserve a bounded drain even on camera, toggle, readback or metadata failures.
            if (phase == Phase.DRAIN || phase == Phase.CLEANUP) {
                try { cleanup(mc); } catch (Exception cleanupError) { failure += "; cleanup: " + cleanupError; }
                phase = Phase.DONE;
                try { manifest(); } catch (IOException ignored) { /* Report failure through Bench. */ }
                return true;
            }
            if (phase != Phase.DRAIN) next(Phase.DRAIN);
            try { manifest(); } catch (IOException ignored) { /* Bench's failure result still names the output. */ }
        }
        return false;
    }

    private void stage(Minecraft mc) throws ReflectiveOperationException {
        if (!frozen(mc) || !settings.equals(settings(mc))) throw new IllegalStateException("world thawed or fog/cloud/video settings changed");
        Pose pose = poses.get(nextFrame);
        Clock clock = clock(mc);
        if (clock.frame() != PREFIX_FRAMES + nextFrame) throw new IllegalStateException("nonconsecutive trajectory clock");
        setArm(arm == 1);
        apply(mc, pose);
        if(READY_REFERENCE)referenceImageInputs.prepareRender(mc,clock.frame(),FRAMES_PER_TICK);
        last = new Rendered(nextFrame++, pose, arm == 1, pose.y() + mc.player.getEyeHeight(), clock);
    }

    private void prepareReference(Minecraft mc) throws ReflectiveOperationException {
        if (arm != 0 || !READY_REFERENCE) throw new IllegalStateException("candidate may not wait for terrain");
        Lockstep.hold(true); setArm(false); Pose pose=poses.get(nextFrame); apply(mc,pose);
        // Held client ticks cannot send movement at a chunk boundary. Synchronize only the
        // local golden-reference fixture's server position so vanilla can deliver neighbors
        // needed to compile its new edge sections. ON never sends these commands.
        int cx=(int)Math.floor(pose.x()/16), cz=(int)Math.floor(pose.z()/16);
        if(cx!=referenceServerChunkX || cz!=referenceServerChunkZ) {
            Scenes.cmd(mc,String.format(java.util.Locale.ROOT,"tp @s %.9f %.9f %.9f %.9f %.9f",
                pose.x(),pose.y(),pose.z(),(double)pose.yaw(),(double)pose.pitch()));
            referenceServerChunkX=cx; referenceServerChunkZ=cz;
        }
        referenceReadyRenders=0; referenceReadySince=0; referenceReadiness=null;
        next(Phase.REFERENCE_WAIT);
    }

    static boolean referenceLodReady(JsonObject state) {
        return state.get("idle").getAsBoolean() && state.get("refusedOverBudget").getAsInt()==0
            && state.get("pendingMeshes").getAsInt()==0 && state.get("queue").getAsInt()==0
            && state.get("inFlight").getAsInt()==0 && state.get("selected").getAsInt()>0
            && state.getAsJsonObject("gpu").get("fading").getAsInt()==0;
    }

    private static String referenceLodSummary(JsonObject state) {
        var out=new JsonObject();
        for(String key:List.of("idle","refusedOverBudget","pendingMeshes","queue","inFlight","selected"))out.add(key,state.get(key));
        out.add("fading",state.getAsJsonObject("gpu").get("fading"));
        return out.toString();
    }

    private Clock clock(Minecraft mc) {
        var timer = mc.getDeltaTracker();
        Clock clock = new Clock(Lockstep.frame(), Lockstep.steps(), Lockstep.nanos(), timer.getGameTimeDeltaTicks(),
            timer.getGameTimeDeltaPartialTick(true), timer.getGameTimeDeltaPartialTick(false),
            mc.gameRenderer.mainCamera().getCameraEntityPartialTicks(timer));
        long holds=READY_REFERENCE && arm==0 ? Lockstep.heldSteps() : 0;
        if (!Lockstep.active() || clock.frame() < 0 || clock.steps() != clock.frame() + 1 + holds
                || !frozen(mc) || clock.deltaTicks() != 1.0F / FRAMES_PER_TICK
                || clock.playerPartial() != Lockstep.partial() || clock.worldPartial() != 1.0F) {
            throw new IllegalStateException("Lockstep timer/frozen partial-tick mismatch: " + clock);
        }
        clockObserved[arm] = true;
        return clock;
    }

    private void capture(Minecraft mc, Rendered rendered) throws ReflectiveOperationException {
        if (rendered.index() != capturedFramesByArm[arm] || rendered.index() >= poses.size()
                || frames.size() >= 2 * poses.size()) {
            throw new IllegalStateException("nonconsecutive capture or capture budget exceeded");
        }
        if (!frozen(mc) || enabled.getBoolean(null) != rendered.enabled() || !settings.equals(settings(mc))) {
            throw new IllegalStateException("capture arm/freeze/settings changed since staging");
        }
        Camera camera = mc.gameRenderer.mainCamera();
        View view = new View(camera.position().x, camera.position().y, camera.position().z, camera.yRot(), camera.xRot(), camera.getFov());
        Pose pose = rendered.pose();
        boolean matches = near(view.x(), pose.x()) && near(view.y(), rendered.eyeY()) && near(view.z(), pose.z())
            && near(Math.IEEEremainder(view.yaw() - pose.yaw(), 360), 0) && near(view.pitch(), pose.pitch())
            && Float.isFinite(view.fov()) && view.fov() > 0;
        boolean pairMatches = arm == 0 || sameView(reference.get(rendered.index()), view);
        boolean clockMatches = arm == 0 || sameRenderClock(referenceClocks.get(rendered.index()),rendered.clock(),READY_REFERENCE);
        if (arm == 0) {
            reference.add(view);
            referenceClocks.add(rendered.clock());
        }
        if (READY_REFERENCE && arm==0) {
            if (Lockstep.frame()!=rendered.clock().frame() || mc.getDeltaTracker().getGameTimeDeltaTicks()!=0
                    || Lockstep.steps()!=rendered.clock().steps()+1 || referenceReadiness==null || !referenceReadiness.ready())
                throw new IllegalStateException("ready reference capture did not follow its one normal staged render");
        } else if (clock(mc).frame() != rendered.clock().frame() + 1)
            throw new IllegalStateException("capture did not follow the staged render");
        JsonObject state = lodState();
        boolean held = rendered.index() > 0 && samePose(poses.get(rendered.index() - 1), pose);
        String file = (arm == 0 ? "off" : "on") + "/frame-" + String.format(java.util.Locale.ROOT, "%03d", rendered.index()) + ".png";
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("file", file);
        row.put("frame", rendered.index());
        row.put("trajectoryTimeNanos", rendered.index() * LOGICAL_FRAME_NANOS);
        row.put("arm", rendered.enabled() ? "on" : "off");
        row.put("enabledReadBack", enabled.getBoolean(null));
        row.put("requestedPose", pose);
        row.put("actualCamera", view);
        row.put("requestedCameraMatches", matches);
        row.put("pairedCameraMatches", pairMatches);
        row.put("stagedPlayerPartialTick", rendered.clock().playerPartial());
        row.put("renderClock", rendered.clock());
        row.put("pairedClockMatches", clockMatches);
        row.put("heldSincePreviousFrame", held);
        if (READY_REFERENCE) {
            var inputs=referenceImageInputs.rendered(mc);
            if(arm==0)referenceRenderInputs.add(inputs);
            boolean inputMatch=arm==0 || referenceRenderInputs.get(rendered.index()).equals(inputs);
            row.put("renderInputs",inputs); row.put("pairedRenderInputsMatch",inputMatch);
            if(!inputMatch)throw new IllegalStateException("reference/candidate inputs differ at "+rendered.index()
                +" expected="+referenceRenderInputs.get(rendered.index())+" actual="+inputs);
        }
        if (READY_REFERENCE && arm==0) {
            var afterRender=LodReferenceReadiness.read(mc);
            if(!afterRender.ready() || !referenceLodReady(state))
                throw new IllegalStateException("reference lost readiness during final render: "+afterRender+" LOD="+referenceLodSummary(state));
            row.put("referenceReadiness",referenceReadiness);
            row.put("referenceReadinessAfterRender",afterRender);
            row.put("referenceWaitRendersTotal",referenceWaitRenders);
            row.put("referenceHeldTimerCalls",Lockstep.heldSteps());
        }
        if (OPAQUE) {
            long count = opaqueFrames(state, rendered.enabled());
            if (count < previousOpaqueFrames || (arm == 0 && count != previousOpaqueFrames))
                throw new IllegalStateException("opaqueFrames reset or grew during captured OFF arm");
            row.put("opaqueFramesSincePreviousFrame", count - previousOpaqueFrames);
            row.put("asyncItemsCountersAfterRender", state.getAsJsonObject("gpu").get("asyncItems"));
            previousOpaqueFrames = count;
            opaqueFramesAtArmEnd[arm] = count; // Excludes prefix and writer-drain renders.
        } else if (HANDOFF) {
            HandoffCounters counts = handoffCounters(state, rendered.enabled());
            if (!handoffIntervalValid(arm, previousHandoffCounters, counts))
                throw new IllegalStateException("handoff counters reset or requested/active state disagrees with captured arm");
            boolean exercised = handoffCoverageObserved(previousHandoffCounters, counts);
            if (exercised) handoffCoveredRevisionFrames[arm]++;
            row.put("handoffRevisionSincePreviousFrame", counts.revision() - previousHandoffCounters.revision());
            row.put("handoffCoveredRevisionObserved", exercised);
            row.put("handoffCountersAfterRender", counts);
            previousHandoffCounters = counts;
            handoffCountersAtArmEnd[arm] = counts; // Excludes prewarm, OFF prefix and writer-drain renders.
        } else if (PREPARE) {
            PrepareCounters counts = prepareCounters(state, rendered.enabled());
            if (!prepareIntervalValid(arm, previousPrepareCounters, counts))
                throw new IllegalStateException("prepare counters reset or CPU worker activity occurred in captured OFF arm");
            row.put("prepareCountersSincePreviousFrame", prepareDelta(previousPrepareCounters, counts));
            row.put("prepareCountersAfterRender", state.getAsJsonObject("gpu").get("asyncItems"));
            previousPrepareCounters = counts;
            prepareCountersAtArmEnd[arm] = counts; // Excludes prewarm, OFF prefix and writer-drain renders.
        } else if (NODES) {
            NodeCounters counts = nodeCounters(state.getAsJsonObject("gpu"), rendered.enabled());
            if (!nodeIntervalValid(arm, previousNodeCounters, counts))
                throw new IllegalStateException("node counters reset or unexpected mode/replay during captured arm");
            row.put("nodeBuildMode", arm);
            row.put("nodeModeBuildsSincePreviousFrame", counts.builds(arm) - previousNodeCounters.builds(arm));
            row.put("nodePacketHitsSincePreviousFrame", counts.packetHits() - previousNodeCounters.packetHits());
            row.put("nodeReplayedClustersSincePreviousFrame", counts.replayedClusters() - previousNodeCounters.replayedClusters());
            row.put("nodeItemsCountersAfterRender", state.getAsJsonObject("gpu").get("nodeItems"));
            previousNodeCounters = counts;
            nodeCountersAtArmEnd[arm] = counts; // Captured renders only, excluding prefix and screenshot drain.
        } else {
            long[] groupCounts = GROUPS ? groupBuilds(state.getAsJsonObject("gpu"), rendered.enabled()) : null;
            long hits = GROUPS ? groupCounts[2 + arm] : state.getAsJsonObject("gpu").getAsJsonObject(COUNTERS).get(HIT_FIELD).getAsLong();
            long hitDelta = hits - previousHits;
            previousHits = hits;
            if (hitDelta < 0) throw new IllegalStateException(COUNTERS + "." + HIT_FIELD + " counter reset during trajectory");
            if (METADATA) {
                // Last captured render, excluding prefix and screenshot-drain renders.
                if (GROUPS) groupBuildsAtArmEnd[arm] = groupCounts;
                else itemBuildsAtArmEnd[arm] = hits;
                JsonObject itemCounters = state.getAsJsonObject("gpu").getAsJsonObject("itemCache");
                long appends = GROUPS ? itemHits(itemCounters) : itemAppends(itemCounters);
                long appendDelta = appends - previousItemAppends;
                previousItemAppends = appends;
                if (appendDelta < 0) throw new IllegalStateException("item append counters reset during trajectory");
                boolean exercised = itemAppendObserved(hitDelta, appendDelta, state.getAsJsonObject("gpu").get("drawnClusters").getAsLong());
                if (exercised) itemAppendFramesByArm[arm]++;
                row.put(GROUPS ? "groupModeBuildsSincePreviousFrame" : "cachedItemBuildsSincePreviousFrame", hitDelta);
                row.put(GROUPS ? "cachedSectionHitsSincePreviousFrame" : "cachedSectionAppendsSincePreviousFrame", appendDelta);
                row.put("cachedNonemptyAppendObserved", exercised);
                row.put("itemCacheCountersAfterRender", itemCounters);
                if (GROUPS) {
                    row.put("groupBuildMode", 2 + arm);
                    row.put("groupCacheCountersAfterRender", state.getAsJsonObject("gpu").get("groupCache"));
                }
            } else {
                if (rendered.enabled() && held) onHeldHits += hitDelta;
                row.put("imageReuseHitsSincePreviousFrame", hitDelta);
                row.put("reuseCountersAfterRender", state.getAsJsonObject("gpu").get(COUNTERS));
            }
        }
        row.put("lodStateAtCaptureHook", state);
        if (LodBoundaryProbe.ENABLED) row.put("boundaryDiagnostic", LodBoundaryProbe.sample(mc, rendered.index()));
        frames.add(row);
        int outstanding = pending.incrementAndGet();
        if (outstanding > poses.size()) {
            pending.decrementAndGet();
            throw new IllegalStateException("capture queue exceeded one arm; refusing rather than inserting writer holds");
        }
        peakPendingCaptures = Math.max(peakPendingCaptures, outstanding);
        try {
            // Same vanilla native-resolution readback and NativeImage PNG encoder as Screenshot.grab.
            // Only PNG dispatch changes: vanilla's unbounded IO pool can run >100 encoders at once.
            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), 1, image ->
                imageWriter.submit(image, () -> {
                    image.writeToFile(output.resolve(file));
                    if (!Scenes.pngComplete(output.resolve(file).toFile()))
                        throw new IOException("incomplete screenshot " + file);
                }, error -> {
                    if (error != null) writerError.compareAndSet(null, "screenshot " + file + ": " + error);
                    pending.decrementAndGet();
                }));
            capturedFramesByArm[arm]++;
        } catch (RuntimeException | Error e) {
            pending.decrementAndGet();
            throw e;
        }
        if (!matches || !pairMatches || !clockMatches) throw new IllegalStateException("rendered camera/FOV/clock mismatch in " + file);
    }

    private static boolean samePose(Pose a, Pose b) {
        return a.x() == b.x() && a.y() == b.y() && a.z() == b.z() && a.yaw() == b.yaw() && a.pitch() == b.pitch();
    }

    private static boolean near(double a, double b) { return Double.isFinite(a) && Math.abs(a - b) <= 0.0001; }
    private static boolean sameView(View a, View b) {
        return a.equals(b); // Exact double/float camera and FOV match between paired images.
    }

    static boolean sameRenderClock(Clock a, Clock b, boolean referenceWaits) {
        return a.equals(b) || referenceWaits && a.frame()==b.frame() && a.nanos()==b.nanos()
            && a.deltaTicks()==b.deltaTicks() && a.playerPartial()==b.playerPartial()
            && a.worldPartial()==b.worldPartial() && a.cameraPartial()==b.cameraPartial();
    }

    private JsonObject lodState() throws IllegalAccessException {
        JsonObject state = JsonParser.parseString(LodHook.json()).getAsJsonObject().getAsJsonObject("state");
        if (state.get("tau").getAsDouble() != 2 || state.get("selected").getAsInt() <= 0
                || !state.getAsJsonObject("gpu").has(COUNTERS)) throw new IllegalStateException("actual tau=2 / LOD selection / " + COUNTERS + " counters absent");
        if (ITEMS) {
            JsonObject counters = state.getAsJsonObject("gpu").getAsJsonObject(COUNTERS);
            if (!counters.has(HIT_FIELD) || counters.get(HIT_FIELD).getAsLong() < 0
                    || counters.get("enabled").getAsBoolean() != enabled.getBoolean(null))
                throw new IllegalStateException("items counters absent or runtime toggle disagrees");
        }
        if (GROUPS) groupBuilds(state.getAsJsonObject("gpu"), enabled.getBoolean(null));
        if (NODES) nodeCounters(state.getAsJsonObject("gpu"), enabled.getBoolean(null));
        if (PREPARE) prepareCounters(state, enabled.getBoolean(null));
        if (OPAQUE) opaqueFrames(state, enabled.getBoolean(null));
        if (HANDOFF) handoffCounters(state, enabled.getBoolean(null));
        return state;
    }

    private static boolean frozen(Minecraft mc) {
        return mc.level.tickRateManager().isFrozen() && !mc.level.tickRateManager().runsNormally();
    }

    private static void requireTarget(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gui.screen() != null || mc.gui.overlay() != null
                || !mc.options.getCameraType().isFirstPerson()
                || mc.getWindow().getWidth() != WIDTH || mc.getWindow().getHeight() != HEIGHT
                || mc.gameRenderer.mainRenderTarget().width != WIDTH || mc.gameRenderer.mainRenderTarget().height != HEIGHT) {
            throw new IllegalStateException("reuseseq requires unobstructed first-person native 3024x1964 target");
        }
    }

    private static Map<String, Object> settings(Minecraft mc) throws ReflectiveOperationException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clouds", String.valueOf(mc.options.cloudStatus().get()));
        result.put("cloudRange", mc.options.cloudRange().get());
        result.put("fov", mc.options.fov().get());
        result.put("renderDistance", mc.options.getEffectiveRenderDistance());
        Class<?> lod = Class.forName("net.minecraft.client.renderer.chunk.Lod");
        for (String name : List.of("fog", "fogDist", "fogEdge", "RADIUS", "TAU", "GPU_BYTES", "draw", "layerMask")) {
            result.put(name, lod.getField(name).get(null));
        }
        if (MOTION_FEATURE) {
            Field imageReuse = Class.forName("net.minecraft.client.renderer.chunk.LodReuse").getField("enabled");
            Field async = Class.forName("net.minecraft.client.renderer.chunk.LodAsyncProducerProbe").getDeclaredField("ENABLED");
            async.setAccessible(true);
            boolean reuseEnabled = imageReuse.getBoolean(null), asyncEnabled = async.getBoolean(null);
            if (Boolean.getBoolean("mc.lod.async") || Boolean.getBoolean("mc.lod.reuse") || asyncEnabled || reuseEnabled
                    || ((Number)result.get("RADIUS")).intValue() != 512 || ((Number)result.get("TAU")).doubleValue() != 2
                    || ((Number)result.get("GPU_BYTES")).longValue() != (6144L << 20)
                    || !Boolean.TRUE.equals(result.get("draw")) || ((Number)result.get("layerMask")).intValue() != 7)
                throw new IllegalStateException(FEATURE + " motion requires synchronous LOD 512/tau2/gpuMB6144, all layers, async=false and reuse=false");
            result.put("asyncEnabled", asyncEnabled);
            result.put("imageReuseEnabled", reuseEnabled);
            if (PREPARE) result.putAll(prepareSettings());
            if (OPAQUE) result.putAll(opaqueSettings());
            if (HANDOFF) result.putAll(handoffSettings());
            if (GROUPS || NODES) {
                Class<?> gpu = Class.forName(TOGGLE_CLASS);
                Field items = gpu.getField("cacheItems"), diagnostics = gpu.getDeclaredField("GROUP_LOOKUP_DIAGNOSTICS");
                items.setAccessible(true); diagnostics.setAccessible(true);
                boolean itemFlag = items.getBoolean(null), diagnosticFlag = diagnostics.getBoolean(null);
                if (!groupControlsValid(itemFlag, diagnosticFlag) || Boolean.getBoolean("mc.lod.cacheGroups.diagnostics"))
                    throw new IllegalStateException(FEATURE + " motion requires cacheItems=true and group diagnostics=false in both arms");
                result.put("cacheItems", itemFlag);
                result.put("groupLookupDiagnostics", diagnosticFlag);
                if (NODES) {
                    Field groups = gpu.getField("cacheGroups"), profile = gpu.getDeclaredField("ITEM_BUILD_PROFILE");
                    groups.setAccessible(true); profile.setAccessible(true);
                    boolean groupFlag = groups.getBoolean(null), profileFlag = profile.getBoolean(null);
                    String lodDiagnostics = System.getProperty("mc.lod.diag", "");
                    if (!nodeControlsValid(itemFlag, groupFlag, diagnosticFlag || !lodDiagnostics.isEmpty(), profileFlag)
                            || Boolean.getBoolean("mc.lod.itemBuildProfile"))
                        throw new IllegalStateException("nodes motion requires cacheItems/cacheGroups=true and diagnostics/profiling=false in both arms");
                    result.put("cacheGroups", groupFlag);
                    result.put("itemBuildProfile", profileFlag);
                    result.put("lodDiagnostics", lodDiagnostics);
                }
            }
        }
        return result;
    }

    private void apply(Minecraft mc, Pose pose) {
        LocalPlayer p = mc.player;
        p.setDeltaMovement(0, 0, 0);
        p.setSprinting(false);
        p.setShiftKeyDown(false);
        p.snapTo(pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch()); // also pins old positions/angles for interpolation
        Scenes.aim(p, pose.yaw(), pose.pitch());
        Scenes.hideHud(mc, true);
        mc.debugEntries.setOverlayVisible(false);
        Scenes.pinTime(mc); // identical cloud game time in both arms, no cloud/fog quality change
    }

    private void setArm(boolean value) throws IllegalAccessException {
        enabled.setBoolean(null, value);
        if (enabled.getBoolean(null) != value) throw new IllegalStateException(TOGGLE_CLASS + "." + TOGGLE_FIELD + " readback failed");
    }

    private void cleanup(Minecraft mc) throws IllegalAccessException {
        imageWriter.close(); // Nonblocking; queued images still close/report even on an earlier failure.
        Lockstep.stop();
        ZoomHook.bindClock(false);
        if (!setup) return;
        setArm(savedEnabled);
        if (mc.player != null && mc.level != null) apply(mc, savedPose);
        Scenes.hideHud(mc, hudWasHidden);
        mc.debugEntries.setOverlayVisible(overlayWasVisible);
        if (!wasFrozen) Scenes.cmd(mc, "tick unfreeze");
        setup = false;
    }

    private void fail(String message) {
        if (failure == null) failure = message;
        complete = false;
    }

    private double elapsed() { return (System.nanoTime() - phaseStart) / 1e9; }
    private void next(Phase next) { phase = next; phaseStart = System.nanoTime(); }

    private Map<String, Object> trajectorySpec() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("label", TRAJECTORY);
        spec.put("logicalDtNanos", LOGICAL_FRAME_NANOS);
        spec.put("logicalDtSeconds", LOGICAL_FRAME_NANOS / 1_000_000_000.0);
        spec.put("logicalDurationSeconds", poses.size() * LOGICAL_FRAME_NANOS / 1_000_000_000.0);
        spec.put("firstToLastSampleSeconds", (poses.size() - 1) * LOGICAL_FRAME_NANOS / 1_000_000_000.0);
        spec.put("framesPerArm", poses.size());
        spec.put("expectedTotalCaptures", 2 * poses.size());
        spec.put("maxOutstandingCaptures", poses.size());
        spec.put("runWriterBackpressureHolds", false);
        spec.put("pngWriterThreads", 2);
        spec.put("phase0CleanupOnly", PHASE0_MOTION && !MOTION_FEATURE);
        if (METADATA && !PREPARE) spec.put("synchronousCpuMetadataMotionOnly", true);
        if (OPAQUE) {
            spec.put("opaqueShaderMotionOnly", true);
            spec.put("runMeshWaits", false);
            spec.put("forceBuild", false);
        }
        if (HANDOFF) {
            spec.put("runtimeNearHandoffCoverageRetentionOnly", true);
            spec.put("runMeshWaits", READY_REFERENCE);
            spec.put("referenceReadinessWaits",READY_REFERENCE);
            spec.put("referenceLocalServerPositionSync",READY_REFERENCE);
            spec.put("logicalFrameLightmapInputsBothArms",READY_REFERENCE);
            spec.put("candidateRunMeshWaits",false);
            spec.put("candidateRunHolds",false);
            spec.put("candidateServerPositionSync",false);
            spec.put("canonicalClockIgnoresOnlyReferenceTimerCallCount",READY_REFERENCE);
            spec.put("forceBuild", false);
            spec.put("limitation", HANDOFF_LIMITATION);
        }
        if (PREPARE) {
            spec.put("cpuDrawRecordWorkerMotionOnly", true);
            spec.put("runWorkerWaits", false);
            spec.put("forceBuild", false);
            spec.put("pngReadbacksMayChangeWorkerCadence", true);
            spec.put("latencyMeasured", false);
            spec.put("limitation", PREPARE_LIMITATION);
        }
        spec.put("phase1AsyncAcceptanceEligible", false);
        spec.put("asyncAgeMeasured", false);
        spec.put("sustainableCadenceMeasured", false);
        if (PHASE0_MOTION) {
            spec.put("flightBlocksPerLogicalSecond", PHASE0_FLIGHT_BLOCKS_PER_SECOND);
            spec.put("yawDegreesPerLogicalSecond", PHASE0_TURN_DEGREES_PER_SECOND);
            spec.put("flightAxis", "+X (east), independent of yaw");
        }
        return spec;
    }

    private void manifest() throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schema", "farsight-reuse-sequence-1");
        data.put("feature", FEATURE);
        data.put("captureComplete", complete && !LodBoundaryProbe.ENABLED);
        data.put("diagnosticOnly", LodBoundaryProbe.ENABLED);
        data.put("readySynchronousReference",READY_REFERENCE);
        data.put("referenceWaitRenders",referenceWaitRenders);
        data.put("settledBoundaryReferenceOnly", BOUNDARY_REFERENCE);
        data.put("settledBoundaryReferences", boundaryReferences);
        if (BOUNDARY_REFERENCE) data.put("settledReferenceLimitation",
            "Four fully settled OFF poses for static-tree attribution only; animation clocks are not matched. Not motion, FPS or release evidence.");
        data.put("diagnosticCaptureComplete", complete);
        data.put("imageGateEvaluated", false);
        data.put("continuousMotionVerified", false);
        data.put("lockstepTimerHooked", clockObserved[0] && clockObserved[1]);
        data.put("lockstepObservedArms", clockObserved);
        data.put("trajectory", OPAQUE ? "Opaque shader OFF/ON with async items held ON: consecutive eastward flight + fast yaw, no held samples"
            : HANDOFF ? "Runtime near-handoff coverage/retention OFF/ON: consecutive eastward flight + fast yaw, no held samples"
            : PREPARE ? "CPU draw-record worker OFF/ON: consecutive eastward flight + fast yaw, no held samples" : METADATA
            ? "Synchronous CPU metadata OFF/ON: consecutive eastward flight + fast yaw, no held samples" : PHASE0_MOTION
            ? "Phase 0 synchronous cleanup only: consecutive eastward flight + fast yaw, no held samples"
            : "Two consecutive per-render camera trajectories: " + HOLD_FRAMES
                + "-frame initial hold, yaw spin, translated flight, stop, move, " + HOLD_FRAMES + "-frame final hold");
        data.put("trajectorySpec", trajectorySpec());
        data.put("framesPerTick", FRAMES_PER_TICK);
        data.put("offPrefixFramesPerArm", PREFIX_FRAMES);
        data.put("initialAndFinalHoldFrames", PHASE0_MOTION ? 0 : HOLD_FRAMES);
        data.put("onHeldImageReuseHits", onHeldHits);
        data.put("onHeldImageReuseHitRequired", !ITEMS && !PHASE0_MOTION);
        if (ITEMS) data.put("itemBuildEvidence", itemBuildEvidence());
        if (GROUPS) data.put("groupBuildEvidence", groupBuildEvidence());
        if (NODES) data.put("nodeBuildEvidence", nodeBuildEvidence());
        if (PREPARE) data.put("prepareActivity", prepareActivity());
        if (OPAQUE) data.put("opaqueActivity", opaqueActivity());
        if (HANDOFF) {
            data.put("handoffActivity", handoffActivity());
            data.put("startupArm", startupArm);
        }
        data.put("releaseEligible", false);
        data.put("limitation", (OPAQUE ? "Opaque shader spatial/temporal image evidence only; PNG readbacks can change worker cadence. "
            : HANDOFF ? HANDOFF_LIMITATION + " " : PREPARE ? PREPARE_LIMITATION + " " : METADATA
            ? "Synchronous CPU metadata motion evidence ONLY, not async-renderer acceptance. Async rendering and image reuse must be disabled. " : PHASE0_MOTION
            ? "Synchronous Phase 0 cleanup evidence ONLY; unacceptable for Phase 1 async age/motion acceptance. Screenshot readbacks and PNG work can change wall cadence and allow extra async refresh time. " : "")
            + "Scripted camera trajectories with equal Lockstep game-clock prefixes and frozen world, not physics flight or FPS measurement. "
            + (READY_REFERENCE ? "Reference-only readiness waits; light seed reset and captured light/sprite inputs checked. Wall-time LOD fades remain unmodified. "
                : "Asynchronous LOD/lightmap state and wall-time effects are not reset. ")
            + "No image comparison performed; measured hits alone do not pass the image gate.");
        data.put("samplePoint", "Previous render target/camera/feature counters at next pre-render hook, before arm or pose changes");
        data.put("phase", phase.toString());
        data.put("error", failure);
        data.put("pendingWrites", pending.get());
        data.put("capturedFramesByArm", capturedFramesByArm);
        data.put("peakPendingCaptures", peakPendingCaptures);
        data.put("peakActivePngWriters", imageWriter.peakActive());
        data.put("expectedFramesPerArm", poses.size());
        data.put("width", WIDTH);
        data.put("height", HEIGHT);
        data.put("downscale", 1);
        data.put("settingsBothArms", settings);
        data.put("configAtSetup", config);
        data.put("frames", frames);
        Files.writeString(output.resolve("manifest.json"), JSON.toJson(data) + "\n");
    }

    String resultJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("manifest", output.resolve("manifest.json").toString());
        result.put("feature", FEATURE);
        result.put("trajectorySpec", trajectorySpec());
        result.put("captureComplete", complete && !LodBoundaryProbe.ENABLED);
        result.put("diagnosticOnly", LodBoundaryProbe.ENABLED);
        result.put("readySynchronousReference",READY_REFERENCE);
        result.put("referenceWaitRenders",referenceWaitRenders);
        result.put("settledBoundaryReferenceOnly", BOUNDARY_REFERENCE);
        result.put("settledBoundaryReferences", boundaryReferences);
        if (BOUNDARY_REFERENCE) result.put("settledReferenceLimitation",
            "Four fully settled OFF poses for static-tree attribution only; animation clocks are not matched. Not motion, FPS or release evidence.");
        result.put("error", failure);
        result.put("frames", frames.size());
        result.put("pendingWrites", pending.get());
        result.put("capturedFramesByArm", capturedFramesByArm);
        result.put("peakPendingCaptures", peakPendingCaptures);
        result.put("peakActivePngWriters", imageWriter.peakActive());
        result.put("imageGateEvaluated", false);
        result.put("continuousMotionVerified", false);
        result.put("lockstepTimerHooked", clockObserved[0] && clockObserved[1]);
        result.put("onHeldImageReuseHits", onHeldHits);
        result.put("onHeldImageReuseHitRequired", !ITEMS && !PHASE0_MOTION);
        if (ITEMS) result.put("itemBuildEvidence", itemBuildEvidence());
        if (GROUPS) result.put("groupBuildEvidence", groupBuildEvidence());
        if (NODES) result.put("nodeBuildEvidence", nodeBuildEvidence());
        if (PREPARE) result.put("prepareActivity", prepareActivity());
        if (OPAQUE) result.put("opaqueActivity", opaqueActivity());
        if (HANDOFF) {
            result.put("handoffActivity", handoffActivity());
            result.put("startupArm", startupArm);
            result.put("limitation", HANDOFF_LIMITATION);
        }
        result.put("releaseEligible", false);
        return JSON.toJson(result);
    }

    private Map<String, Object> itemBuildEvidence() {
        return Map.of("counter", "gpu.itemCache.cachedBuilds", "armOrder", List.of("off", "on"),
            "armStart", itemBuildsAtArmStart, "armEnd", itemBuildsAtArmEnd,
            "offMustNotIncrease", true, "onMustIncrease", true,
            "nonemptyAppendFrames", itemAppendFramesByArm, "forceBuild", false,
            "onIncreaseObserved", itemBuildsAtArmStart[1] >= 0 && itemBuildsAtArmEnd[1] > itemBuildsAtArmStart[1]);
    }

    private Map<String, Object> groupBuildEvidence() {
        return Map.of("counter", "gpu.groupCache.buildsByMode", "armOrder", List.of("off", "on"),
            "requiredModes", List.of(2, 3), "armStart", groupBuildsAtArmStart, "armEnd", groupBuildsAtArmEnd,
            "nonemptyAppendFrames", itemAppendFramesByArm, "appendCounter", "gpu.itemCache.hits", "forceBuild", false,
            "activityObserved", groupActivityValid(0, groupBuildsAtArmStart[0], groupBuildsAtArmEnd[0], itemAppendFramesByArm[0])
                && groupActivityValid(1, groupBuildsAtArmStart[1], groupBuildsAtArmEnd[1], itemAppendFramesByArm[1]));
    }

    private Map<String, Object> nodeBuildEvidence() {
        return Map.of("counter", "gpu.nodeItems.buildsByMode", "armOrder", List.of("off", "on"),
            "requiredModes", List.of(0, 1), "armStart", nodeCountersAtArmStart, "armEnd", nodeCountersAtArmEnd,
            "hitCounter", "gpu.nodeItems.packetHits", "clusterCounter", "gpu.nodeItems.replayedClusters", "forceBuild", false,
            "activityObserved", nodeActivityValid(0, nodeCountersAtArmStart[0], nodeCountersAtArmEnd[0])
                && nodeActivityValid(1, nodeCountersAtArmStart[1], nodeCountersAtArmEnd[1]));
    }

    private Map<String, Object> opaqueActivity() {
        return Map.of("counter", "gpu.asyncItems.opaqueFrames", "armOrder", List.of("off", "on"),
            "armStart", opaqueFramesAtArmStart, "armEnd", opaqueFramesAtArmEnd,
            "offMustNotIncrease", true, "onMustIncrease", true, "capturedRendersOnly", true,
            "activityObserved", opaqueActivityValid(0, opaqueFramesAtArmStart[0], opaqueFramesAtArmEnd[0])
                && opaqueActivityValid(1, opaqueFramesAtArmStart[1], opaqueFramesAtArmEnd[1]));
    }

    static long opaqueFrames(JsonObject state, boolean expectedEnabled) {
        prepareCounters(state, true); // Active async list in BOTH arms; no worker-accept growth requirement.
        JsonObject counts = counterObject(counterObject(state, "gpu"), "asyncItems");
        if (counterFlag(counts, "opaqueRequested") != expectedEnabled)
            throw new IllegalStateException("opaque requested flag disagrees with rendered arm");
        return nodeCounter(counts, "opaqueFrames");
    }

    static boolean opaqueActivityValid(int arm, long start, long end) {
        return start >= 0 && end >= start && (arm == 0 ? end == start : arm == 1 && end > start);
    }

    private Map<String, Object> prepareActivity() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("counter", "gpu.asyncItems");
        evidence.put("armOrder", List.of("off", "on"));
        evidence.put("armStart", prepareCountersAtArmStart);
        evidence.put("armEnd", prepareCountersAtArmEnd);
        Map<String, Long> off = prepareDelta(prepareCountersAtArmStart[0], prepareCountersAtArmEnd[0]);
        Map<String, Long> on = prepareDelta(prepareCountersAtArmStart[1], prepareCountersAtArmEnd[1]);
        Map<String, Object> byArm = new LinkedHashMap<>();
        byArm.put("off", off); byArm.put("on", on);
        evidence.put("byArm", byArm);
        Map<String, Long> difference = new LinkedHashMap<>();
        if (off != null && on != null) {
            for (String name : List.of("rejected", "fallbacks", "uploadDefers"))
                difference.put(name, on.get(name) - off.get(name));
        }
        evidence.put("diagnosticDifferenceOnMinusOff", difference);
        evidence.put("requiredOnIncreases", PREPARE_REQUIRED);
        evidence.put("offAllowedIncrease", "uploadDefers");
        evidence.put("warmupExcluded", true);
        evidence.put("capturedRendersOnly", true);
        evidence.put("forceBuild", false);
        evidence.put("waitForWorker", false);
        evidence.put("activityObserved", prepareActivityValid(0, prepareCountersAtArmStart[0], prepareCountersAtArmEnd[0])
            && prepareActivityValid(1, prepareCountersAtArmStart[1], prepareCountersAtArmEnd[1]));
        evidence.put("performanceApproved", false);
        evidence.put("imageApproved", false);
        evidence.put("limitation", PREPARE_LIMITATION);
        return evidence;
    }

    private Map<String, Object> handoffActivity() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("counter", "gpu.nearHandoff");
        evidence.put("armOrder", List.of("off", "on"));
        evidence.put("armStart", handoffCountersAtArmStart);
        evidence.put("armEnd", handoffCountersAtArmEnd);
        evidence.put("coveredRevisionFrames", handoffCoveredRevisionFrames);
        evidence.put("capturedRendersOnly", !READY_REFERENCE);
        evidence.put("candidateCapturedRendersOnly", true);
        evidence.put("forceBuild", false);
        evidence.put("waitForMesh", READY_REFERENCE);
        evidence.put("candidateWaitForMesh", false);
        evidence.put("activityObserved", handoffActivityValid(0, handoffCountersAtArmStart[0], handoffCountersAtArmEnd[0], handoffCoveredRevisionFrames[0])
            && handoffActivityValid(1, handoffCountersAtArmStart[1], handoffCountersAtArmEnd[1], handoffCoveredRevisionFrames[1]));
        evidence.put("limitation", HANDOFF_LIMITATION);
        return evidence;
    }

    record HandoffCounters(boolean requested, boolean active, long coveredSections, long revision,
                           long maskReplacements, long fullVisibilitySections) {}

    /** Native admission must actually be active after each ON render; requested alone cannot qualify.
     * OFF may retain stale coverage totals. Reuse the existing held-state validator with asyncItems OFF. */
    static HandoffCounters handoffCounters(JsonObject state, boolean expectedEnabled) {
        prepareCounters(state, false);
        JsonObject gpu = counterObject(state, "gpu"), counts = counterObject(gpu, "nearHandoff");
        boolean requested = counterFlag(counts, "requested"), active = counterFlag(counts, "active");
        if (requested != expectedEnabled || active != expectedEnabled
                || counterFlag(counterObject(gpu, "nodeItems"), "activeLastBuild"))
            throw new IllegalStateException("handoff requested/active state or held node cache disagrees with rendered arm");
        return new HandoffCounters(requested, active, nodeCounter(counts, "coveredSections"), nodeCounter(counts, "revision"),
            nodeCounter(counts, "maskReplacements"), nodeCounter(counts, "fullVisibilitySections"));
    }

    static boolean handoffIntervalValid(int arm, HandoffCounters start, HandoffCounters end) {
        return (arm == 0 || arm == 1) && start != null && end != null
            && start.requested() == start.active() && end.requested() == (arm == 1) && end.active() == (arm == 1)
            && (arm == 1 || !start.active()) && start.coveredSections() >= 0 && end.coveredSections() >= 0
            && start.revision() >= 0 && end.revision() >= start.revision()
            && start.maskReplacements() >= 0 && end.maskReplacements() >= start.maskReplacements()
            && start.fullVisibilitySections() >= 0 && end.fullVisibilitySections() >= start.fullVisibilitySections();
    }

    static boolean handoffCoverageObserved(HandoffCounters start, HandoffCounters end) {
        return handoffIntervalValid(1, start, end) && end.coveredSections() > 0 && end.revision() > start.revision();
    }

    static boolean handoffActivityValid(int arm, HandoffCounters start, HandoffCounters end, int coveredRevisionFrames) {
        return handoffIntervalValid(arm, start, end) && (arm == 0 ? coveredRevisionFrames == 0
            : coveredRevisionFrames > 0 && end.revision() > start.revision());
    }

    /** Read real held controls at setup, staging and capture. Prepare toggles only asyncItems. */
    static Map<String, Object> prepareSettings() throws ReflectiveOperationException {
        return heldSettings(false);
    }

    static Map<String, Object> opaqueSettings() throws ReflectiveOperationException {
        Map<String, Object> flags = prepareSettings(); // Includes held nearHandoff, when available.
        boolean asyncItems = staticFlag(Class.forName("net.minecraft.client.renderer.chunk.LodGpu"), "asyncItems");
        boolean profile = staticFlag(Class.forName("net.minecraft.client.renderer.chunk.LodPreparedItems"), "PREPARE_PROFILE");
        if (!asyncItems || profile || LodBoundaryProbe.ENABLED)
            throw new IllegalStateException("opaque requires asyncItems=true and prepare profile/boundary diagnostics=false");
        flags.put("asyncItems", asyncItems);
        flags.put("prepareProfile", profile);
        flags.put("boundaryProbe", false);
        return flags;
    }

    static Map<String, Object> handoffSettings() throws ReflectiveOperationException {
        handoffStartupArm();
        return heldSettings(true);
    }

    /** Startup arms are immutable prerequisites, separate from the changing runtime toggle. */
    static Map<String, Object> handoffStartupArm() throws ReflectiveOperationException {
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("mc.lod.nearHandoff", Boolean.getBoolean("mc.lod.nearHandoff"));
        flags.put("LodGpu.NEAR_HANDOFF_ARMED", staticFlag(Class.forName("net.minecraft.client.renderer.chunk.LodGpu"), "NEAR_HANDOFF_ARMED"));
        flags.put("SectionRenderDispatcher.NEAR_HANDOFF_STARTUP", staticFlag(Class.forName("net.minecraft.client.renderer.chunk.SectionRenderDispatcher"), "NEAR_HANDOFF_STARTUP"));
        if (flags.containsValue(false)) throw new IllegalStateException("handoff requires startup mc.lod.nearHandoff=true in both client classes");
        return Map.copyOf(flags);
    }

    private static Map<String, Object> heldSettings(boolean runtimeHandoff) throws ReflectiveOperationException {
        String mode = runtimeHandoff ? "handoff" : "prepare";
        Class<?> gpu = Class.forName("net.minecraft.client.renderer.chunk.LodGpu");
        Map<String, Object> flags = new LinkedHashMap<>();
        for (String name : List.of("cacheItems", "cacheGroups", "cacheNodeItems", "GROUP_LOOKUP_DIAGNOSTICS",
                "ITEM_BUILD_PROFILE", "DIAG_LEVELS", "OCCL_DIAG_NOBOX")) {
            boolean value = staticFlag(gpu, name);
            if (value != (name.equals("cacheItems") || name.equals("cacheGroups")))
                throw new IllegalStateException(mode + " held LodGpu flag disagrees: " + name);
            flags.put(name, value);
        }
        boolean handoff = staticFlag(Class.forName("net.minecraft.client.renderer.chunk.LodManager"), "ATOMIC_MESH_HANDOFF");
        boolean far = staticFlag(Class.forName("net.minecraft.client.renderer.chunk.LodAsyncProducerProbe"), "ENABLED");
        boolean farRequested = staticFlag(Class.forName("com.mojang.renderpearl.backend.metal.MetalFarPrograms"), "REQUESTED");
        boolean reuse = staticFlag(Class.forName("net.minecraft.client.renderer.chunk.LodReuse"), "enabled");
        String diagnostics = System.getProperty("mc.lod.diag", "");
        if (!handoff || far || farRequested || reuse || !diagnostics.isEmpty()
                || Boolean.getBoolean("mc.lod.cacheGroups.diagnostics") || Boolean.getBoolean("mc.lod.itemBuildProfile")
                || Boolean.getBoolean("mc.lod.prepareProfile")
                || Boolean.getBoolean("mc.lod.async") || Boolean.getBoolean("mc.lod.reuse"))
            throw new IllegalStateException(mode + " requires atomicMeshHandoff=true and diagnostics/profiling/async far rendering/image reuse=false");
        // Newer clients can repair near-mesh handoff in both arms. Record and hold this
        // prerequisite explicitly; its presence is not an ON/OFF quality claim for the repair.
        if (runtimeHandoff) {
            boolean asyncItems = staticFlag(gpu, "asyncItems");
            boolean prepareProfile = staticFlag(Class.forName("net.minecraft.client.renderer.chunk.LodPreparedItems"), "PREPARE_PROFILE");
            if (asyncItems || prepareProfile
                    || Boolean.getBoolean("mc.lod.asyncItems")
                    || Boolean.getBoolean("bench.reuseBoundaryProbe") != LodBoundaryProbe.ENABLED
                    || Boolean.getBoolean("mc.lod.cacheNodeItems") || Boolean.getBoolean("mc.lod.async.producerProbe")
                    || Boolean.getBoolean("mc.lod.async.consumerProbe"))
                throw new IllegalStateException("handoff requires asyncItems/node cache/profile/far probes=false and a stable boundary diagnostic setting");
            flags.put("asyncItems", asyncItems);
            flags.put("prepareProfile", prepareProfile);
            // Boundary diagnostics are allowed only as non-qualifying observations:
            // both manifest and result always force captureComplete=false when enabled.
            flags.put("boundaryProbe", LodBoundaryProbe.ENABLED);
            // Do not include nearHandoff: settingsBothArms must be identical across runtime OFF/ON.
        } else {
            boolean nearHandoff = false;
            try { nearHandoff = staticFlag(gpu, "nearHandoff"); }
            catch (NoSuchFieldException oldClient) { /* frozen pre-repair client */ }
            flags.put("nearHandoff", nearHandoff);
        }
        flags.put("atomicMeshHandoff", handoff);
        flags.put("asyncEnabled", far);
        flags.put("asyncRequested", farRequested);
        flags.put("imageReuseEnabled", reuse);
        flags.put("lodDiagnostics", diagnostics);
        return flags;
    }

    private static boolean staticFlag(Class<?> owner, String name) throws ReflectiveOperationException {
        Field flag = owner.getDeclaredField(name);
        flag.setAccessible(true);
        if (flag.getType() != boolean.class || !Modifier.isStatic(flag.getModifiers()))
            throw new IllegalStateException("invalid static boolean " + owner.getName() + "." + name);
        return flag.getBoolean(null);
    }

    record PrepareCounters(Map<String, Long> values) {
        PrepareCounters { values = Map.copyOf(values); }
    }

    /** Validate post-render production stats; an ON frame may have a pending worker, without waiting. */
    static PrepareCounters prepareCounters(JsonObject state, boolean expectedEnabled) {
        JsonObject gpu = counterObject(state, "gpu"), packing = counterObject(gpu, "asyncItems");
        boolean active = counterFlag(packing, "active"), pending = counterFlag(packing, "pending");
        if (counterFlag(packing, "enabled") != expectedEnabled || active != expectedEnabled || (pending && !active))
            throw new IllegalStateException("prepare enabled/active/pending state does not match rendered arm");
        var error = packing.get("error");
        if (counterFlag(packing, "failed") || error == null || !error.isJsonPrimitive()
                || !error.getAsJsonPrimitive().isString() || !error.getAsString().isEmpty())
            throw new IllegalStateException("prepare worker failed or reported an invalid/nonempty error");
        if (!counterFlag(counterObject(gpu, "itemCache"), "enabled")
                || !counterFlag(counterObject(gpu, "groupCache"), "enabled")
                || counterFlag(counterObject(gpu, "groupCache"), "diagnostics")
                || counterFlag(counterObject(gpu, "nodeItems"), "enabled")
                || !counterFlag(counterObject(state, "meshHandoff"), "enabled")
                || counterFlag(counterObject(gpu, "reuse"), "enabled")
                || !gpu.has("asyncConsumer") || !gpu.get("asyncConsumer").isJsonNull()
                || !gpu.has("itemBuildProfile") || !gpu.get("itemBuildProfile").isJsonNull())
            throw new IllegalStateException("prepare held cache/handoff/diagnostics/profiling/far-rendering/reuse flags disagree");
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String name : PREPARE_COUNTERS) {
            var value = packing.get(name);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                throw new IllegalStateException("prepare counter absent or not numeric: " + name);
            long count = value.getAsBigDecimal().longValueExact();
            if (count < 0) throw new IllegalStateException("negative prepare counter: " + name);
            counts.put(name, count);
        }
        return new PrepareCounters(counts);
    }

    private static JsonObject counterObject(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonObject())
            throw new IllegalStateException("counter object absent: " + name);
        return parent.getAsJsonObject(name);
    }

    static boolean prepareIntervalValid(int arm, PrepareCounters start, PrepareCounters end) {
        if ((arm != 0 && arm != 1) || start == null || end == null) return false;
        for (String name : PREPARE_COUNTERS) {
            long before = start.values().getOrDefault(name, -1L), after = end.values().getOrDefault(name, -1L);
            if (before < 0 || after < before || (arm == 0 && !name.equals("uploadDefers") && after != before)) return false;
        }
        // uploadPending() runs before the first OFF frame clears pending ON work.
        return true;
    }

    static boolean prepareActivityValid(int arm, PrepareCounters start, PrepareCounters end) {
        if (!prepareIntervalValid(arm, start, end)) return false;
        if (arm == 1) {
            for (String name : PREPARE_REQUIRED) if (end.values().get(name) <= start.values().get(name)) return false;
        }
        return true;
    }

    private static Map<String, Long> prepareDelta(PrepareCounters start, PrepareCounters end) {
        if (start == null || end == null) return null;
        Map<String, Long> delta = new LinkedHashMap<>();
        for (String name : PREPARE_COUNTERS) delta.put(name, end.values().get(name) - start.values().get(name));
        return delta;
    }

    /** Immutable capture-boundary counters: preparation or warmup alone never proves replay. */
    record NodeCounters(long offBuilds, long onBuilds, long packetHits, long replayedClusters) {
        long builds(int arm) { return arm == 0 ? offBuilds : onBuilds; }
    }

    static boolean nodeControlsValid(boolean cacheItems, boolean cacheGroups, boolean diagnostics, boolean profiling) {
        return cacheItems && cacheGroups && !diagnostics && !profiling;
    }

    static NodeCounters nodeCounters(JsonObject gpu, boolean expectedNodes) {
        if (gpu == null || !gpu.has("itemCache") || !gpu.has("groupCache") || !gpu.has("nodeItems")
                || !gpu.has("itemBuildProfile") || !gpu.get("itemBuildProfile").isJsonNull())
            throw new IllegalStateException("nodes counters absent or item profiling enabled");
        JsonObject items = gpu.getAsJsonObject("itemCache"), groups = gpu.getAsJsonObject("groupCache"), nodes = gpu.getAsJsonObject("nodeItems");
        if (!nodeControlsValid(counterFlag(items, "enabled"), counterFlag(groups, "enabled"), counterFlag(groups, "diagnostics"), false)
                || counterFlag(nodes, "enabled") != expectedNodes)
            throw new IllegalStateException("nodes held flags or runtime toggle disagree");
        long[] builds = nodeCounterPair(nodes, "buildsByMode");
        nodeCounterPair(nodes, "buildNsByMode");
        for (String name : List.of("packetBuilds", "payloadBytes", "budgetBytes", "fallbacks")) nodeCounter(nodes, name);
        return new NodeCounters(builds[0], builds[1], nodeCounter(nodes, "packetHits"), nodeCounter(nodes, "replayedClusters"));
    }

    private static long[] nodeCounterPair(JsonObject counters, String name) {
        if (!counters.has(name) || !counters.get(name).isJsonArray() || counters.getAsJsonArray(name).size() != 2)
            throw new IllegalStateException("invalid node counter pair " + name);
        var pair = counters.getAsJsonArray(name);
        return new long[]{nodeCounterValue(pair.get(0)), nodeCounterValue(pair.get(1))};
    }

    private static long nodeCounter(JsonObject counters, String name) {
        return nodeCounterValue(counters.get(name));
    }

    private static long nodeCounterValue(com.google.gson.JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalStateException("node counter must be a nonnegative integer");
        long count = value.getAsBigDecimal().longValueExact();
        if (count < 0) throw new IllegalStateException("negative node counter");
        return count;
    }

    static boolean nodeIntervalValid(int arm, NodeCounters start, NodeCounters end) {
        if ((arm != 0 && arm != 1) || start == null || end == null) return false;
        if (start.offBuilds() < 0 || start.onBuilds() < 0 || start.packetHits() < 0 || start.replayedClusters() < 0
                || end.offBuilds() < start.offBuilds() || end.onBuilds() < start.onBuilds()
                || end.packetHits() < start.packetHits() || end.replayedClusters() < start.replayedClusters()
                || end.builds(1 - arm) != start.builds(1 - arm)) return false;
        return arm == 1 || (end.packetHits() == start.packetHits() && end.replayedClusters() == start.replayedClusters());
    }

    static boolean nodeActivityValid(int arm, NodeCounters start, NodeCounters end) {
        return nodeIntervalValid(arm, start, end) && end.builds(arm) > start.builds(arm)
            && (arm == 0 || (end.packetHits() > start.packetHits() && end.replayedClusters() > start.replayedClusters()));
    }

    static boolean groupControlsValid(boolean cacheItems, boolean diagnostics) {
        return cacheItems && !diagnostics;
    }

    /** Validated full mode snapshot; a toggled boolean alone is never exercise evidence. */
    static long[] groupBuilds(JsonObject gpu, boolean expectedGroups) {
        if (gpu == null || !gpu.has("itemCache") || !gpu.has("groupCache"))
            throw new IllegalStateException("groups item/group counters absent");
        JsonObject items = gpu.getAsJsonObject("itemCache"), groups = gpu.getAsJsonObject("groupCache");
        if (items == null || groups == null || !items.has("enabled") || !groups.has("diagnostics") || !groups.has("enabled")
                || !groupControlsValid(counterFlag(items, "enabled"), counterFlag(groups, "diagnostics"))
                || counterFlag(groups, "enabled") != expectedGroups || !groups.has("buildsByMode")
                || !groups.get("buildsByMode").isJsonArray() || groups.getAsJsonArray("buildsByMode").size() != 4)
            throw new IllegalStateException("groups flags/diagnostics/build-mode counters invalid");
        itemHits(items);
        long[] counts = new long[4];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = groups.getAsJsonArray("buildsByMode").get(i).getAsBigDecimal().longValueExact();
            if (counts[i] < 0) throw new IllegalStateException("negative group build-mode counter");
        }
        return counts;
    }

    static boolean groupActivityValid(int arm, long[] start, long[] end, int appendFrames) {
        if ((arm != 0 && arm != 1) || start == null || end == null || start.length != 4 || end.length != 4 || appendFrames < 0) return false;
        for (int i = 0; i < 4; i++) {
            if (start[i] < 0 || end[i] < start[i] || (i != 2 + arm && end[i] != start[i])) return false;
        }
        return end[2 + arm] > start[2 + arm] && (arm == 0 || appendFrames > 0);
    }

    private static long itemHits(JsonObject counters) {
        if (counters == null || !counters.has("hits")) throw new IllegalStateException("item cache hits absent");
        long hits = counters.get("hits").getAsBigDecimal().longValueExact();
        if (hits < 0) throw new IllegalStateException("negative item cache hits");
        return hits;
    }

    private static boolean counterFlag(JsonObject counters, String name) {
        if (counters == null || !counters.has(name) || !counters.get(name).isJsonPrimitive() || !counters.getAsJsonPrimitive(name).isBoolean())
            throw new IllegalStateException("invalid counter control flag " + name);
        return counters.get(name).getAsBoolean();
    }

    /** CPU-testable evidence predicates used by the capture and RUN completion gates. */
    static boolean itemAppendObserved(long buildDelta, long appendDelta, long drawnClusters) {
        return buildDelta > 0 && appendDelta > 0 && drawnClusters > 0;
    }

    static boolean itemActivityValid(int arm, long start, long end, int appendFrames) {
        if (start < 0 || end < start || appendFrames < 0) return false;
        return arm == 0 ? end == start && appendFrames == 0 : arm == 1 && end > start && appendFrames > 0;
    }

    private static long itemAppends(JsonObject counters) {
        long builds = counters.get("builds").getAsLong(), hits = counters.get("hits").getAsLong();
        if (builds < 0 || hits < 0) throw new IllegalStateException("negative item cache counters");
        return Math.addExact(builds, hits); // Each appendCachedSection call records exactly one of these.
    }
}
