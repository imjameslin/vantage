package bench;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** CPU policy fixtures and real frozen-client reflection; no renderer, native calls or GPU evidence. */
public final class ReuseSequenceHandoffTest {
    private static int checks;
    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static Exception rejects(Action action) throws Exception {
        try { action.run(); }
        catch (ReflectiveOperationException | RuntimeException expected) { checks++; return expected; }
        throw new AssertionError("expected fail-closed rejection");
    }
    private static Field field(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static Object value(ReuseSequence sequence, String name) throws Exception {
        return field(ReuseSequence.class, name).get(sequence);
    }
    private static JsonObject result(ReuseSequence sequence) {
        return JsonParser.parseString(sequence.resultJson()).getAsJsonObject();
    }
    private static JsonObject snapshot(boolean on, long covered, long revision) {
        JsonObject state = JsonParser.parseString("""
            {"meshHandoff":{"enabled":true},"gpu":{
             "itemCache":{"enabled":true},"groupCache":{"enabled":true,"diagnostics":false},
             "nodeItems":{"enabled":false,"activeLastBuild":false},"itemBuildProfile":null,
             "asyncConsumer":null,"reuse":{"enabled":false},
             "asyncItems":{"enabled":false,"active":false,"pending":false,"failed":false,"error":""},
             "nearHandoff":{"requested":%s,"active":%s,"coveredSections":%d,"revision":%d,
                            "maskReplacements":4,"fullVisibilitySections":30}}}
            """.formatted(on, on, covered, revision)).getAsJsonObject();
        for (String name : ReuseSequence.PREPARE_COUNTERS)
            state.getAsJsonObject("gpu").getAsJsonObject("asyncItems").addProperty(name, 0);
        return state;
    }
    private static void policy() throws Exception {
        var off = ReuseSequence.handoffCounters(snapshot(false, 7, 20), false);
        var on = ReuseSequence.handoffCounters(snapshot(true, 8, 21), true);
        check(ReuseSequence.handoffActivityValid(0, off, off, 0), "OFF stale coverage is allowed when inactive");
        check(ReuseSequence.handoffCoverageObserved(off, on) && ReuseSequence.handoffActivityValid(1, off, on, 1),
            "captured ON covered slots with revision growth qualify");
        check(!ReuseSequence.handoffActivityValid(1, off, on, 0), "revision without observed nonempty coverage fails");
        check(!ReuseSequence.handoffActivityValid(0, off, off, 1), "OFF cannot report active coverage");
        var unchanged = ReuseSequence.handoffCounters(snapshot(true, 8, 20), true);
        var zero = ReuseSequence.handoffCounters(snapshot(true, 0, 21), true);
        check(!ReuseSequence.handoffCoverageObserved(off, unchanged)
            && !ReuseSequence.handoffActivityValid(1, off, unchanged, 1), "warmup/stale revisions never qualify");
        check(!ReuseSequence.handoffCoverageObserved(off, zero), "zero covered slots never qualify even with revision growth");
        var empty = ReuseSequence.handoffCounters(snapshot(true, 0, 0), true);
        check(!ReuseSequence.handoffCoverageObserved(empty, empty)
            && !ReuseSequence.handoffActivityValid(1, empty, empty, 0), "requested/active flags alone never qualify");
        for (int arm : new int[]{0, 1}) {
            check(!ReuseSequence.handoffActivityValid(arm, null, on, 1)
                && !ReuseSequence.handoffActivityValid(arm, off, null, 1), "missing interval fails");
            for (String name : List.of("revision", "maskReplacements", "fullVisibilitySections")) {
                JsonObject reset = snapshot(arm == 1, 8, 21);
                reset.getAsJsonObject("gpu").getAsJsonObject("nearHandoff").addProperty(name, 0);
                check(!ReuseSequence.handoffIntervalValid(arm, off, ReuseSequence.handoffCounters(reset, arm == 1)), "reset fails: " + name);
            }
        }
        check(!ReuseSequence.handoffActivityValid(2, off, on, 1)
            && !ReuseSequence.handoffActivityValid(1, off, on, -1), "invalid arm/count fails");
        check(!ReuseSequence.handoffIntervalValid(0, off, on)
            && !ReuseSequence.handoffIntervalValid(1, off, off), "wrong end arm fails");
        var large = ReuseSequence.handoffCounters(snapshot(true, 1, Long.MAX_VALUE - 1), true);
        check(ReuseSequence.handoffCoverageObserved(large, ReuseSequence.handoffCounters(snapshot(true, 1, Long.MAX_VALUE), true)),
            "revision comparisons retain exact integers");
        for (boolean enabled : new boolean[]{false, true}) {
            for (String flag : List.of("requested", "active")) {
                JsonObject bad = snapshot(enabled, 8, 21), counts = bad.getAsJsonObject("gpu").getAsJsonObject("nearHandoff");
                counts.addProperty(flag, !enabled);
                rejects(() -> ReuseSequence.handoffCounters(bad, enabled)); // Includes unavailable native ON and active OFF.
                counts.remove(flag);
                rejects(() -> ReuseSequence.handoffCounters(bad, enabled));
                for (String invalid : List.of("null", "\"true\"", "1", "{}")) {
                    counts.add(flag, JsonParser.parseString(invalid));
                    rejects(() -> ReuseSequence.handoffCounters(bad, enabled));
                }
            }
            for (String name : List.of("coveredSections", "revision", "maskReplacements", "fullVisibilitySections")) {
                JsonObject bad = snapshot(enabled, 8, 21), counts = bad.getAsJsonObject("gpu").getAsJsonObject("nearHandoff");
                counts.remove(name);
                rejects(() -> ReuseSequence.handoffCounters(bad, enabled));
                for (String invalid : List.of("null", "-1", "0.5", "9223372036854775808", "\"1\"", "true")) {
                    counts.add(name, JsonParser.parseString(invalid));
                    rejects(() -> ReuseSequence.handoffCounters(bad, enabled));
                }
            }
            for (String owner : List.of("itemCache", "groupCache", "nodeItems", "asyncItems", "reuse", "meshHandoff")) {
                JsonObject bad = snapshot(enabled, 8, 21);
                JsonObject parent = owner.equals("meshHandoff") ? bad : bad.getAsJsonObject("gpu"), flags = parent.getAsJsonObject(owner);
                flags.addProperty("enabled", !flags.get("enabled").getAsBoolean());
                rejects(() -> ReuseSequence.handoffCounters(bad, enabled));
                parent.remove(owner);
                rejects(() -> ReuseSequence.handoffCounters(bad, enabled));
            }
            for (String path : List.of("asyncItems.active", "asyncItems.pending", "asyncItems.failed", "nodeItems.activeLastBuild", "groupCache.diagnostics")) {
                JsonObject bad = snapshot(enabled, 8, 21);
                String[] parts = path.split("\\.");
                JsonObject flags = bad.getAsJsonObject("gpu").getAsJsonObject(parts[0]);
                flags.addProperty(parts[1], true);
                rejects(() -> ReuseSequence.handoffCounters(bad, enabled));
                flags.remove(parts[1]);
                rejects(() -> ReuseSequence.handoffCounters(bad, enabled));
            }
            for (String name : List.of("nearHandoff", "itemBuildProfile", "asyncConsumer")) {
                JsonObject bad = snapshot(enabled, 8, 21);
                bad.getAsJsonObject("gpu").remove(name);
                rejects(() -> ReuseSequence.handoffCounters(bad, enabled));
                bad.getAsJsonObject("gpu").add(name, new JsonObject());
                rejects(() -> ReuseSequence.handoffCounters(bad, enabled));
            }
        }
        rejects(() -> ReuseSequence.handoffCounters(null, true));
    }

    private static void heldSettings(Class<?> gpu, Path root) throws Exception {
        var held = ReuseSequence.handoffSettings();
        check(!held.containsKey("nearHandoff") && !held.containsKey("NEAR_HANDOFF_ARMED"), "changing toggle and startup arm excluded from settingsBothArms");
        for (String name : List.of("cacheItems", "cacheGroups", "atomicMeshHandoff"))
            check(Boolean.TRUE.equals(held.get(name)), "held ON: " + name);
        for (String name : List.of("asyncItems", "cacheNodeItems", "GROUP_LOOKUP_DIAGNOSTICS", "ITEM_BUILD_PROFILE",
                "DIAG_LEVELS", "OCCL_DIAG_NOBOX", "prepareProfile",
                "asyncEnabled", "asyncRequested", "imageReuseEnabled"))
            check(Boolean.FALSE.equals(held.get(name)), "held OFF: " + name);
        check(held.get("boundaryProbe").equals(LodBoundaryProbe.ENABLED), "read-only diagnostic recorded accurately");
        for (String name : List.of("cacheItems", "cacheGroups", "cacheNodeItems", "asyncItems")) {
            Field flag = field(gpu, name); boolean saved = flag.getBoolean(null);
            try {
                flag.setBoolean(null, !saved);
                rejects(ReuseSequence::handoffSettings);
                rejects(() -> new ReuseSequence(root.resolve("bad-" + name).toFile()));
            } finally { flag.setBoolean(null, saved); }
        }
        Field reuse = field(Class.forName("net.minecraft.client.renderer.chunk.LodReuse"), "enabled");
        try { reuse.setBoolean(null, true); rejects(ReuseSequence::handoffSettings); }
        finally { reuse.setBoolean(null, false); }
        for (String property : List.of("mc.lod.diag", "mc.lod.cacheGroups.diagnostics", "mc.lod.itemBuildProfile", "mc.lod.prepareProfile",
                "mc.lod.async", "mc.lod.reuse", "mc.lod.asyncItems", "mc.lod.cacheNodeItems", "bench.reuseBoundaryProbe",
                "mc.lod.async.producerProbe", "mc.lod.async.consumerProbe", "mc.lod.nearHandoff")) {
            String saved = System.getProperty(property);
            try {
                System.setProperty(property, property.equals("mc.lod.diag") ? "levels" : property.equals("mc.lod.nearHandoff") ? "false"
                    : property.equals("bench.reuseBoundaryProbe") ? Boolean.toString(!LodBoundaryProbe.ENABLED) : "true");
                rejects(ReuseSequence::handoffSettings);
            } finally {
                if (saved == null) System.clearProperty(property); else System.setProperty(property, saved);
            }
        }
        check(held.equals(ReuseSequence.handoffSettings()), "held settings restored");
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[1]);
        if (args[0].startsWith("late-")) {
            Class.forName("net.minecraft.client.renderer.chunk." + (args[0].equals("late-gpu") ? "LodGpu" : "SectionRenderDispatcher"));
            System.setProperty("mc.lod.nearHandoff", "true"); // Cannot retroactively arm either frozen startup prerequisite.
        }
        if (args[0].equals("latched")) {
            Class.forName(args[3]);
            System.clearProperty(args[2]); // A cleared property cannot conceal an enabled frozen-client flag.
        }
        if (!args[0].equals("normal")) {
            Exception failure = rejects(() -> new ReuseSequence(root.toFile()));
            if (args[0].equals("old-client")) check(failure.getCause() instanceof NoSuchFieldException, "older jar missing toggle fails closed");
            check(!Files.exists(root.resolve("screenshots/reuseseq")), "failed admission creates no capture output");
            System.out.println("ReuseSequenceHandoffTest: " + checks + " checks passed (" + args[0] + ")");
            return;
        }
        Class<?> gpu = Class.forName("net.minecraft.client.renderer.chunk.LodGpu");
        Field runtime = field(gpu, "nearHandoff");
        boolean original = runtime.getBoolean(null);
        check(original, "C initialized runtime from true startup arm");
        boolean saved = Boolean.parseBoolean(args[2]);
        runtime.setBoolean(null, saved);
        ReuseSequence sequence = new ReuseSequence(root.toFile());
        Field toggle = (Field)value(sequence, "enabled");
        check(toggle.equals(runtime) && toggle.getType() == boolean.class && Modifier.isVolatile(toggle.getModifiers())
            && Modifier.isStatic(toggle.getModifiers()) && !Modifier.isFinal(toggle.getModifiers()), "real mutable volatile nearHandoff control");
        for (String owner : List.of("LodGpu", "SectionRenderDispatcher"))
            check(Path.of(Class.forName("net.minecraft.client.renderer.chunk." + owner).getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()
                .equals(Path.of(System.getProperty("reuseSequence.testClientJar")).toRealPath()), "actual frozen client owner: " + owner);
        check(toggle.getBoolean(null) == saved, "constructor preserves runtime arm");
        Method setArm = ReuseSequence.class.getDeclaredMethod("setArm", boolean.class); setArm.setAccessible(true);
        Map<String, Object> held = ReuseSequence.handoffSettings();
        Object startup = value(sequence, "startupArm");
        try {
            for (boolean on : new boolean[]{false, true, false}) {
                setArm.invoke(sequence, on);
                check(toggle.getBoolean(null) == on, "real OFF/ON/OFF readback");
                check(held.equals(ReuseSequence.handoffSettings()), "held settings equal across changing arm");
                check(startup.equals(ReuseSequence.handoffStartupArm()), "startup arm remains true across toggles");
            }
        } finally { setArm.invoke(sequence, saved); }
        check(toggle.getBoolean(null) == saved, "real arm setter restores both possible saved runtime values");
        heldSettings(gpu, root);
        policy();
        JsonObject result = result(sequence), spec = result.getAsJsonObject("trajectorySpec");
        check(((List<?>)value(sequence, "poses")).size() == 120 && spec.get("logicalDtNanos").getAsLong() == 6_250_000L,
            "120-frame existing Lockstep motion preserved");
        check(spec.get("pngWriterThreads").getAsInt() == 2 && !spec.get("runWriterBackpressureHolds").getAsBoolean()
            && spec.get("runMeshWaits").getAsBoolean() == Boolean.getBoolean("bench.reuseReadyReference") && !spec.get("forceBuild").getAsBoolean(), "bounded writers, no RUN holds/mesh waits/forced builds");
        check(spec.get("runtimeNearHandoffCoverageRetentionOnly").getAsBoolean() && !spec.has("synchronousCpuMetadataMotionOnly"), "runtime scope explicit");
        check(!result.getAsJsonObject("handoffActivity").get("activityObserved").getAsBoolean(), "no observation never qualifies");
        var starts = (ReuseSequence.HandoffCounters[])value(sequence, "handoffCountersAtArmStart");
        var ends = (ReuseSequence.HandoffCounters[])value(sequence, "handoffCountersAtArmEnd");
        var covered = (int[])value(sequence, "handoffCoveredRevisionFrames");
        starts[0] = starts[1] = ends[0] = ReuseSequence.handoffCounters(snapshot(false, 7, 20), false);
        ends[1] = ReuseSequence.handoffCounters(snapshot(true, 8, 21), true);
        check(!result(sequence).getAsJsonObject("handoffActivity").get("activityObserved").getAsBoolean(), "revision without covered render cannot qualify receipt");
        covered[1] = 1;
        result = result(sequence);
        check(result.getAsJsonObject("handoffActivity").get("activityObserved").getAsBoolean() && !result.get("captureComplete").getAsBoolean(),
            "policy fixtures cannot complete real capture");
        check(!result.get("imageGateEvaluated").getAsBoolean() && !result.get("releaseEligible").getAsBoolean()
            && !result.get("onHeldImageReuseHitRequired").getAsBoolean(), "no invented image/release/reuse qualification");
        Method manifest = ReuseSequence.class.getDeclaredMethod("manifest"); manifest.setAccessible(true); manifest.invoke(sequence);
        JsonObject receipt = JsonParser.parseString(Files.readString(root.resolve("screenshots/reuseseq/manifest.json"))).getAsJsonObject();
        check(receipt.get("handoffActivity").equals(result.get("handoffActivity")) && receipt.get("startupArm").equals(result.get("startupArm")),
            "manifest/result record identical coverage and separate startup evidence");
        for (String scope : List.of("BENCHMARK-ONLY", "EMPTY-publication", "atomicMeshHandoff", "all flying flashing"))
            check(receipt.get("limitation").getAsString().contains(scope) && result.get("limitation").getAsString().contains(scope), "scope stated: " + scope);
        Field complete = field(ReuseSequence.class, "complete");
        complete.setBoolean(sequence, true);
        manifest.invoke(sequence);
        JsonObject finalReceipt = JsonParser.parseString(Files.readString(root.resolve("screenshots/reuseseq/manifest.json"))).getAsJsonObject();
        check(result(sequence).get("diagnosticOnly").getAsBoolean() == LodBoundaryProbe.ENABLED
            && finalReceipt.get("diagnosticOnly").getAsBoolean() == LodBoundaryProbe.ENABLED
            && result(sequence).get("captureComplete").getAsBoolean() == !LodBoundaryProbe.ENABLED
            && finalReceipt.get("captureComplete").getAsBoolean() == !LodBoundaryProbe.ENABLED,
            "completed diagnostic cannot qualify in either receipt");
        complete.setBoolean(sequence, false);
        check(LodBoundaryProbe.sample(null, 27).isEmpty() && LodBoundaryProbe.sample(null, 36).isEmpty(),
            "bounded diagnostic touches no game outside either preset");
        rejects(() -> new ReuseSequence(root.toFile()));
        check(toggle.getBoolean(null) == saved, "existing shots rejected without changing saved arm");
        runtime.setBoolean(null, original);
        System.out.println("ReuseSequenceHandoffTest: " + checks + " checks passed (savedFlag=" + saved + ")");
    }
}
