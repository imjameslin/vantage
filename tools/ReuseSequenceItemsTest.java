package bench;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;

/** Constructor/reflection and capture-evidence policy only. No Minecraft instance, native or GPU calls. */
public final class ReuseSequenceItemsTest {
    private static int checks;
    private static void check(boolean ok, String why) {
        ++checks;
        if (!ok) throw new AssertionError(why);
    }
    private static Field field(String name) throws Exception {
        Field field = ReuseSequence.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Method method(String name, Class<?>... types) throws Exception {
        Method method = ReuseSequence.class.getDeclaredMethod(name, types); method.setAccessible(true); return method;
    }

    private static void activity() throws Exception {
        check(ReuseSequence.itemActivityValid(0, 37, 37, 0), "OFF unchanged counters pass");
        check(!ReuseSequence.itemActivityValid(0, 37, 38, 0), "OFF must not build cached items");
        check(!ReuseSequence.itemActivityValid(0, 37, 38, 1), "OFF activity cannot pass");
        check(!ReuseSequence.itemActivityValid(0, 37, 37, 1), "inconsistent OFF append evidence fails");
        check(!ReuseSequence.itemActivityValid(1, 37, 37, 0), "enabled with zero ON builds fails");
        check(!ReuseSequence.itemActivityValid(1, 37, 37, 1), "append alone without ON build fails");
        check(!ReuseSequence.itemActivityValid(1, 37, 38, 0), "ON build without nonempty append fails");
        check(ReuseSequence.itemActivityValid(1, 37, 38, 1), "one actual ON build and append passes");
        check(ReuseSequence.itemActivityValid(1, 37, 42, 3), "several actual ON builds pass");
        for (int arm : new int[]{0, 1}) {
            check(!ReuseSequence.itemActivityValid(arm, -1, -1, 0), "unobserved arm fails");
            check(!ReuseSequence.itemActivityValid(arm, 37, 36, 1), "counter reset fails");
            check(!ReuseSequence.itemActivityValid(arm, 37, 38, -1), "negative append count fails");
        }
        check(!ReuseSequence.itemActivityValid(2, 37, 38, 1), "invalid arm fails");
        check(ReuseSequence.itemActivityValid(1, Long.MAX_VALUE-1, Long.MAX_VALUE, 1), "large cumulative counters retain exact comparison");
        for (long[] row : new long[][]{{0,4,100}, {1,0,100}, {1,4,0}, {-1,4,100}, {1,-1,100}, {1,4,-1}})
            check(!ReuseSequence.itemAppendObserved(row[0], row[1], row[2]), "missing/negative frame evidence fails");

        Method appends = method("itemAppends", JsonObject.class);
        JsonObject before = JsonParser.parseString("{\"builds\":12,\"hits\":24}").getAsJsonObject();
        for (String afterJson : new String[]{"{\"builds\":13,\"hits\":24}", "{\"builds\":12,\"hits\":25}"}) {
            JsonObject after = JsonParser.parseString(afterJson).getAsJsonObject();
            long delta = (long)appends.invoke(null, after) - (long)appends.invoke(null, before);
            boolean observed = ReuseSequence.itemAppendObserved(1, delta, 128);
            check(delta == 1 && observed, "cold preparation or warm hit proves an append");
            check(ReuseSequence.itemActivityValid(1, 80, 81, observed ? 1 : 0), "actual counter deltas feed RUN gate");
        }
        check((long)appends.invoke(null, before) == 36, "cache calls sum builds and hits");
        for (String invalid : new String[]{"{\"builds\":-1,\"hits\":24}", "{\"builds\":12,\"hits\":-1}",
                "{\"builds\":9223372036854775807,\"hits\":1}"}) {
            boolean rejected = false;
            try { appends.invoke(null, JsonParser.parseString(invalid).getAsJsonObject()); }
            catch (InvocationTargetException expected) {
                rejected = expected.getCause() instanceof IllegalStateException || expected.getCause() instanceof ArithmeticException;
            }
            check(rejected, "invalid append counters rejected");
        }
    }

    private static JsonObject groupSnapshot(boolean enabled) {
        return JsonParser.parseString("{\"itemCache\":{\"enabled\":true,\"hits\":30},\"groupCache\":{\"enabled\":"
            + enabled + ",\"diagnostics\":false,\"buildsByMode\":[9,8,20,30]}}").getAsJsonObject();
    }

    private static void groupActivity() throws Exception {
        check(ReuseSequence.groupControlsValid(true, false), "groups require items without diagnostics");
        check(!ReuseSequence.groupControlsValid(false, false), "groups cannot turn item caching off");
        check(!ReuseSequence.groupControlsValid(true, true), "groups diagnostics cannot qualify");
        for (boolean enabled : new boolean[]{false, true}) {
            JsonObject snapshot = groupSnapshot(enabled);
            long[] start = ReuseSequence.groupBuilds(snapshot, enabled);
            check(Arrays.equals(start, new long[]{9,8,20,30}), "all four sampled mode counters retained");
            start[0] = 99;
            check(ReuseSequence.groupBuilds(snapshot, enabled)[0] == 9, "snapshot has independent ownership");
            for (int mutation = 0; mutation < 17; mutation++) {
                JsonObject bad = groupSnapshot(enabled), items = bad.getAsJsonObject("itemCache"), groups = bad.getAsJsonObject("groupCache");
                switch (mutation) {
                    case 0 -> items.addProperty("enabled", false);
                    case 1 -> groups.addProperty("enabled", !enabled);
                    case 2 -> groups.addProperty("diagnostics", true);
                    case 3 -> bad.remove("itemCache");
                    case 4 -> bad.remove("groupCache");
                    case 5 -> groups.remove("buildsByMode");
                    case 6 -> groups.add("buildsByMode", JsonParser.parseString("[0,0,0]"));
                    case 7 -> groups.add("buildsByMode", JsonParser.parseString("[0,0,-1,0]"));
                    case 8 -> groups.add("buildsByMode", JsonParser.parseString("[0,0,0.5,0]"));
                    case 9 -> items.remove("hits");
                    case 10 -> items.addProperty("hits", -1);
                    case 11 -> groups.remove("diagnostics");
                    case 12 -> items.remove("enabled");
                    case 13 -> groups.addProperty("diagnostics", "false");
                    case 14 -> groups.add("buildsByMode", JsonParser.parseString("[0,0,9223372036854775808,0]"));
                    case 15 -> groups.add("buildsByMode", JsonParser.parseString("null"));
                    case 16 -> items.addProperty("hits", 0.5);
                }
                boolean rejected = false;
                try { ReuseSequence.groupBuilds(bad, enabled); }
                catch (RuntimeException expected) { rejected = true; }
                check(rejected, "group flags/counters fail closed, mutation=" + mutation);
            }
        }
        long[] start = {9,8,20,30}, off = {9,8,21,30}, on = {9,8,20,31};
        check(ReuseSequence.groupActivityValid(0, start, off, 0), "OFF requires mode 2 rather than zero total builds");
        check(ReuseSequence.groupActivityValid(0, start, off, 1), "OFF may append cached items");
        check(ReuseSequence.groupActivityValid(1, start, on, 1), "ON mode 3 and nonempty append pass");
        check(!ReuseSequence.groupActivityValid(1, start, on, 0), "ON build without observed append fails");
        for (int arm : new int[]{0,1}) {
            check(!ReuseSequence.groupActivityValid(arm, start, start, 1), "no actual build in either arm fails");
            check(!ReuseSequence.groupActivityValid(arm, null, on, 1), "unobserved arm fails");
            check(!ReuseSequence.groupActivityValid(arm, start, new long[]{9,8,21,31}, 1), "cross-arm mode growth fails");
            check(!ReuseSequence.groupActivityValid(arm, start, new long[]{8,8,21,31}, 1), "any mode reset fails");
            check(!ReuseSequence.groupActivityValid(arm, start, new long[3], 1), "mode shape mismatch fails");
            for (int other = 0; other < 4; other++) {
                if (other == 2 + arm) continue;
                long[] wrong = (arm == 0 ? off : on).clone(); wrong[other]++;
                check(!ReuseSequence.groupActivityValid(arm, start, wrong, 1), "unexpected sampled mode " + other + " fails");
            }
        }
        check(!ReuseSequence.groupActivityValid(2, start, on, 1), "invalid group arm fails");
        check(!ReuseSequence.groupActivityValid(1, start, on, -1), "negative append evidence fails");
        Method hits = method("itemHits", JsonObject.class);
        JsonObject before = groupSnapshot(true), after = groupSnapshot(true);
        after.getAsJsonObject("groupCache").getAsJsonArray("buildsByMode").set(3, JsonParser.parseString("31"));
        after.getAsJsonObject("itemCache").addProperty("hits", 31);
        long[] a = ReuseSequence.groupBuilds(before, true), b = ReuseSequence.groupBuilds(after, true);
        long hitDelta = (long)hits.invoke(null, after.getAsJsonObject("itemCache")) - (long)hits.invoke(null, before.getAsJsonObject("itemCache"));
        boolean observed = ReuseSequence.itemAppendObserved(b[3]-a[3], hitDelta, 128);
        check(observed && ReuseSequence.groupActivityValid(1, a, b, observed ? 1 : 0), "real-shaped mode/hit deltas feed shared gates");
        check(!ReuseSequence.itemAppendObserved(b[3]-a[3], 0, 128), "boolean/build alone is not a cached append");
        check(!ReuseSequence.itemAppendObserved(b[3]-a[3], hitDelta, 0), "empty output is not exercise evidence");
    }

    private static JsonObject nodeSnapshot(boolean enabled) {
        return JsonParser.parseString("""
            {"itemCache":{"enabled":true},"groupCache":{"enabled":true,"diagnostics":false},
             "itemBuildProfile":null,"nodeItems":{"enabled":%s,"buildsByMode":[20,30],
             "buildNsByMode":[400,500],"packetBuilds":10,"packetHits":40,"replayedClusters":800,
             "payloadBytes":4096,"budgetBytes":8192,"fallbacks":2}}
            """.formatted(enabled)).getAsJsonObject();
    }

    private static void rejectNodeSnapshot(JsonObject gpu, boolean enabled, String why) {
        boolean rejected = false;
        try { ReuseSequence.nodeCounters(gpu, enabled); }
        catch (RuntimeException expected) { rejected = true; }
        check(rejected, why);
    }

    private static void nodeActivity() {
        for (int bits = 0; bits < 16; bits++) {
            check(ReuseSequence.nodeControlsValid((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0)
                == (bits == 3), "only items/groups ON and diagnostics/profiling OFF qualify");
        }
        for (boolean enabled : new boolean[]{false, true}) {
            JsonObject gpu = nodeSnapshot(enabled);
            var start = ReuseSequence.nodeCounters(gpu, enabled);
            check(start.equals(new ReuseSequence.NodeCounters(20,30,40,800)), "exact named snapshot values");
            for (String name : List.of("itemCache", "groupCache", "itemBuildProfile", "nodeItems")) {
                JsonObject bad = gpu.deepCopy(); bad.remove(name);
                rejectNodeSnapshot(bad, enabled, "missing " + name + " fails closed");
            }
            for (int mutation = 0; mutation < 11; mutation++) {
                JsonObject bad = gpu.deepCopy();
                switch (mutation) {
                    case 0 -> bad.getAsJsonObject("itemCache").addProperty("enabled", false);
                    case 1 -> bad.getAsJsonObject("groupCache").addProperty("enabled", false);
                    case 2 -> bad.getAsJsonObject("groupCache").addProperty("diagnostics", true);
                    case 3 -> bad.getAsJsonObject("nodeItems").addProperty("enabled", !enabled);
                    case 4 -> bad.getAsJsonObject("itemCache").remove("enabled");
                    case 5 -> bad.getAsJsonObject("groupCache").remove("enabled");
                    case 6 -> bad.getAsJsonObject("groupCache").remove("diagnostics");
                    case 7 -> bad.getAsJsonObject("nodeItems").remove("enabled");
                    case 8 -> bad.getAsJsonObject("nodeItems").addProperty("enabled", Boolean.toString(enabled));
                    case 9 -> bad.add("itemBuildProfile", new JsonObject());
                    case 10 -> bad.getAsJsonObject("groupCache").addProperty("diagnostics", "false");
                }
                rejectNodeSnapshot(bad, enabled, "wrong/missing held flag or profiling, mutation=" + mutation);
            }
            for (String name : List.of("packetBuilds", "packetHits", "replayedClusters", "payloadBytes", "budgetBytes", "fallbacks")) {
                JsonObject bad = gpu.deepCopy(); bad.getAsJsonObject("nodeItems").remove(name);
                rejectNodeSnapshot(bad, enabled, "missing node counter " + name);
                for (String value : List.of("null", "-1", "0.5", "9223372036854775808", "\"1\"", "true")) {
                    bad = gpu.deepCopy(); bad.getAsJsonObject("nodeItems").add(name, JsonParser.parseString(value));
                    rejectNodeSnapshot(bad, enabled, "invalid numeric node counter " + name + "=" + value);
                }
            }
            for (String name : List.of("buildsByMode", "buildNsByMode")) {
                JsonObject bad = gpu.deepCopy(); bad.getAsJsonObject("nodeItems").remove(name);
                rejectNodeSnapshot(bad, enabled, "missing node pair " + name);
                for (String value : List.of("null", "[1]", "[1,2,3]", "[-1,2]", "[1,0.5]", "[1,\"2\"]", "[1,9223372036854775808]")) {
                    bad = gpu.deepCopy(); bad.getAsJsonObject("nodeItems").add(name, JsonParser.parseString(value));
                    rejectNodeSnapshot(bad, enabled, "invalid node pair " + name + "=" + value);
                }
            }
        }
        var start = new ReuseSequence.NodeCounters(20,30,40,800);
        var off = new ReuseSequence.NodeCounters(21,30,40,800);
        var on = new ReuseSequence.NodeCounters(20,31,42,928);
        check(ReuseSequence.nodeActivityValid(0, start, off), "OFF real mode-0 build with no replay qualifies");
        check(ReuseSequence.nodeActivityValid(1, start, on), "ON real mode-1 build, packet hits and clusters qualify");
        for (int arm : new int[]{0,1}) {
            check(ReuseSequence.nodeIntervalValid(arm, start, start), "idle captured frame allowed");
            check(!ReuseSequence.nodeActivityValid(arm, start, start), "warmup totals without captured activity fail");
            check(!ReuseSequence.nodeActivityValid(arm, null, on), "missing arm start fails");
            check(!ReuseSequence.nodeActivityValid(arm, start, null), "missing arm end fails");
            check(!ReuseSequence.nodeActivityValid(arm, start, new ReuseSequence.NodeCounters(21,31,42,928)), "mixed build modes fail");
            for (var reset : List.of(new ReuseSequence.NodeCounters(19,31,42,928), new ReuseSequence.NodeCounters(21,29,42,928),
                    new ReuseSequence.NodeCounters(21,31,39,928), new ReuseSequence.NodeCounters(21,31,42,799)))
                check(!ReuseSequence.nodeIntervalValid(arm, start, reset), "any activity counter reset fails immediately");
        }
        check(!ReuseSequence.nodeActivityValid(0, start, on), "wrong OFF build mode fails");
        check(!ReuseSequence.nodeActivityValid(1, start, off), "wrong ON build mode fails");
        check(!ReuseSequence.nodeActivityValid(1, start, new ReuseSequence.NodeCounters(20,30,42,928)), "ON hits without captured build fail");
        check(!ReuseSequence.nodeActivityValid(1, start, new ReuseSequence.NodeCounters(20,31,40,928)), "ON no actual packet replay hit fails");
        check(!ReuseSequence.nodeActivityValid(1, start, new ReuseSequence.NodeCounters(20,31,42,800)), "ON no replayed clusters fails");
        check(!ReuseSequence.nodeActivityValid(0, start, new ReuseSequence.NodeCounters(21,30,41,800)), "OFF packet replay fails");
        check(!ReuseSequence.nodeActivityValid(0, start, new ReuseSequence.NodeCounters(21,30,40,801)), "OFF cluster replay fails");
        check(!ReuseSequence.nodeActivityValid(2, start, on), "invalid node arm fails");
        JsonObject before = nodeSnapshot(true), after = before.deepCopy();
        after.getAsJsonObject("nodeItems").addProperty("packetBuilds", 11);
        after.getAsJsonObject("nodeItems").getAsJsonArray("buildsByMode").set(1, JsonParser.parseString("31"));
        check(!ReuseSequence.nodeActivityValid(1, ReuseSequence.nodeCounters(before, true), ReuseSequence.nodeCounters(after, true)),
            "packet preparation alone cannot qualify");
        after.getAsJsonObject("nodeItems").addProperty("packetHits", 42);
        after.getAsJsonObject("nodeItems").addProperty("replayedClusters", 928);
        check(ReuseSequence.nodeActivityValid(1, ReuseSequence.nodeCounters(before, true), ReuseSequence.nodeCounters(after, true)),
            "actual shaped captured deltas feed runtime gate");
    }

    /** Synthetic counter snapshots test evidence policy, never claim worker/GPU execution. */
    private static JsonObject prepareSnapshot(boolean enabled) {
        JsonObject state = JsonParser.parseString("""
            {"meshHandoff":{"enabled":true},"gpu":{
             "itemCache":{"enabled":true},"groupCache":{"enabled":true,"diagnostics":false},
             "nodeItems":{"enabled":false},"itemBuildProfile":null,"asyncConsumer":null,"reuse":{"enabled":false},
             "asyncItems":{"enabled":%s,"active":%s,"pending":false,"failed":false,"error":""}}}
            """.formatted(enabled, enabled)).getAsJsonObject();
        for (String name : ReuseSequence.PREPARE_COUNTERS) packing(state).addProperty(name, 100);
        return state;
    }

    private static JsonObject packing(JsonObject state) { return state.getAsJsonObject("gpu").getAsJsonObject("asyncItems"); }

    private static void increment(JsonObject state, String name, long delta) {
        JsonObject counts = packing(state);
        counts.addProperty(name, counts.get(name).getAsLong() + delta);
    }

    private static JsonObject prepareOn() {
        JsonObject state = prepareSnapshot(true);
        for (String name : ReuseSequence.PREPARE_COUNTERS) increment(state, name, 1);
        increment(state, "offers", 1);
        increment(state, "acceptedClusters", 127);
        increment(state, "workerNs", 39);
        increment(state, "uploadDefers", 3);
        packing(state).addProperty("pending", true); // A last-frame pending job must not require a wait.
        return state;
    }

    private static void rejectPrepareSnapshot(JsonObject state, boolean enabled, String why) {
        boolean rejected = false;
        try { ReuseSequence.prepareCounters(state, enabled); }
        catch (RuntimeException expected) { rejected = true; }
        check(rejected, why);
    }

    private static void prepareActivity() {
        check(ReuseSequence.PREPARE_COUNTERS.equals(List.of("offers", "accepted", "rejected", "bootstrap", "fallbacks",
            "uploadDefers", "captureNs", "installNs", "workerNs", "acceptedClusters")), "all production/runner counters covered");
        var start = ReuseSequence.prepareCounters(prepareSnapshot(false), false);
        var on = ReuseSequence.prepareCounters(prepareOn(), true);
        check(ReuseSequence.prepareActivityValid(0, start, start), "OFF unchanged CPU counters qualify");
        check(ReuseSequence.prepareActivityValid(1, start, on), "captured ON offers/acceptance/nonempty clusters/worker time qualify");
        check(ReuseSequence.prepareIntervalValid(1, start, start), "idle ON frame permitted without waiting or forcing work");
        check(!ReuseSequence.prepareActivityValid(1, start, start), "warmup-only totals never qualify captured ON activity");
        JsonObject zero = prepareSnapshot(true);
        for (String name : ReuseSequence.PREPARE_COUNTERS) packing(zero).addProperty(name, 0);
        var absent = ReuseSequence.prepareCounters(zero, true);
        check(!ReuseSequence.prepareActivityValid(1, absent, absent), "absent worker activity fails");
        JsonObject bootstrap = prepareSnapshot(true);
        for (String name : List.of("bootstrap", "captureNs", "installNs")) increment(bootstrap, name, 1);
        check(!ReuseSequence.prepareActivityValid(1, start, ReuseSequence.prepareCounters(bootstrap, true)),
            "synchronous bootstrap alone never qualifies");
        for (String name : List.of("offers", "accepted", "acceptedClusters", "workerNs")) {
            JsonObject bad = prepareOn(); packing(bad).addProperty(name, 100);
            check(!ReuseSequence.prepareActivityValid(1, start, ReuseSequence.prepareCounters(bad, true)), "missing captured growth: " + name);
        }
        for (int arm : new int[]{0, 1}) {
            check(!ReuseSequence.prepareActivityValid(arm, null, on), "unobserved prepare start fails");
            check(!ReuseSequence.prepareActivityValid(arm, start, null), "unobserved prepare end fails");
            for (String name : ReuseSequence.PREPARE_COUNTERS) {
                JsonObject bad = prepareSnapshot(arm == 1); packing(bad).addProperty(name, 99);
                check(!ReuseSequence.prepareIntervalValid(arm, start, ReuseSequence.prepareCounters(bad, arm == 1)),
                    "counter reset rejected in each captured interval: " + name);
            }
        }
        check(!ReuseSequence.prepareActivityValid(2, start, on), "invalid prepare arm fails");
        for (String name : ReuseSequence.PREPARE_COUNTERS) {
            JsonObject off = prepareSnapshot(false); increment(off, name, 1);
            boolean allowed = name.equals("uploadDefers");
            check(ReuseSequence.prepareActivityValid(0, start, ReuseSequence.prepareCounters(off, false)) == allowed,
                "OFF rejects CPU activity, permits only transition uploadDefers: " + name);
        }
        JsonObject large = prepareSnapshot(true), larger = prepareSnapshot(true);
        for (String name : ReuseSequence.PREPARE_COUNTERS) {
            packing(large).addProperty(name, Long.MAX_VALUE - 1); packing(larger).addProperty(name, Long.MAX_VALUE);
        }
        check(ReuseSequence.prepareActivityValid(1, ReuseSequence.prepareCounters(large, true), ReuseSequence.prepareCounters(larger, true)),
            "large counters preserve exact integer differences");
        JsonObject owned = prepareOn(); var frozen = ReuseSequence.prepareCounters(owned, true);
        increment(owned, "accepted", 10);
        check(frozen.values().get("accepted") == 101, "counter snapshot owns its values");
        for (boolean enabled : new boolean[]{false, true}) {
            JsonObject good = prepareSnapshot(enabled);
            ReuseSequence.prepareCounters(good, enabled);
            JsonObject pending = good.deepCopy(); packing(pending).addProperty("pending", true);
            if (enabled) check(ReuseSequence.prepareCounters(pending, true).equals(start), "pending ON worker allowed without a wait");
            else rejectPrepareSnapshot(pending, false, "pending OFF worker rejected");
            for (String name : List.of("enabled", "active")) {
                JsonObject bad = good.deepCopy(); packing(bad).addProperty(name, !enabled);
                rejectPrepareSnapshot(bad, enabled, "wrong rendered " + name);
            }
            for (String name : List.of("enabled", "active", "pending", "failed", "error")) {
                JsonObject bad = good.deepCopy(); packing(bad).remove(name);
                rejectPrepareSnapshot(bad, enabled, "missing worker state " + name);
                for (String value : List.of("null", "0", "{}", "[]", "\"false\"")) {
                    bad = good.deepCopy(); packing(bad).add(name, JsonParser.parseString(value));
                    rejectPrepareSnapshot(bad, enabled, "malformed worker state " + name + "=" + value);
                }
            }
            JsonObject failed = good.deepCopy(); packing(failed).addProperty("failed", true);
            rejectPrepareSnapshot(failed, enabled, "worker failure fails even with empty error");
            failed = good.deepCopy(); packing(failed).addProperty("error", "packing capacity");
            rejectPrepareSnapshot(failed, enabled, "nonempty error fails even if failed has cleared OFF");
            for (String name : ReuseSequence.PREPARE_COUNTERS) {
                JsonObject bad = good.deepCopy(); packing(bad).remove(name);
                rejectPrepareSnapshot(bad, enabled, "missing prepare counter " + name);
                for (String value : List.of("null", "-1", "0.5", "9223372036854775808", "\"1\"", "true", "[]")) {
                    bad = good.deepCopy(); packing(bad).add(name, JsonParser.parseString(value));
                    rejectPrepareSnapshot(bad, enabled, "invalid numeric prepare counter " + name + "=" + value);
                }
            }
            for (String owner : List.of("itemCache", "groupCache", "nodeItems", "reuse", "meshHandoff")) {
                JsonObject bad = good.deepCopy();
                JsonObject parent = owner.equals("meshHandoff") ? bad : bad.getAsJsonObject("gpu");
                JsonObject flags = parent.getAsJsonObject(owner);
                flags.addProperty("enabled", !flags.get("enabled").getAsBoolean());
                rejectPrepareSnapshot(bad, enabled, "tampered held " + owner);
                flags.addProperty("enabled", "false");
                rejectPrepareSnapshot(bad, enabled, "held " + owner + " requires boolean");
                parent.remove(owner);
                rejectPrepareSnapshot(bad, enabled, "absent held " + owner);
            }
            for (String value : List.of("true", "null", "\"false\"")) {
                JsonObject bad = good.deepCopy(); bad.getAsJsonObject("gpu").getAsJsonObject("groupCache")
                    .add("diagnostics", JsonParser.parseString(value));
                rejectPrepareSnapshot(bad, enabled, "group diagnostics must be false");
            }
            for (String name : List.of("asyncConsumer", "itemBuildProfile")) {
                JsonObject bad = good.deepCopy(); bad.getAsJsonObject("gpu").remove(name);
                rejectPrepareSnapshot(bad, enabled, "missing " + name + " is not disabled");
                for (String value : List.of("{}", "false", "\"null\"")) {
                    bad = good.deepCopy(); bad.getAsJsonObject("gpu").add(name, JsonParser.parseString(value));
                    rejectPrepareSnapshot(bad, enabled, "enabled/malformed " + name);
                }
            }
            JsonObject bad = good.deepCopy(); bad.getAsJsonObject("gpu").remove("asyncItems");
            rejectPrepareSnapshot(bad, enabled, "absent production stats never qualify");
        }
        rejectPrepareSnapshot(null, false, "absent state fails");
        rejectPrepareSnapshot(new JsonObject(), false, "absent gpu fails");
    }

    private static void prepareEvidence(ReuseSequence sequence) throws Exception {
        JsonObject result = JsonParser.parseString(sequence.resultJson()).getAsJsonObject();
        JsonObject evidence = result.getAsJsonObject("prepareActivity"), spec = result.getAsJsonObject("trajectorySpec");
        check(!evidence.get("activityObserved").getAsBoolean(), "unobserved arm cannot qualify");
        check(!evidence.get("forceBuild").getAsBoolean() && !evidence.get("waitForWorker").getAsBoolean(), "no forced rebuilds or worker waits");
        check(!evidence.get("performanceApproved").getAsBoolean() && !evidence.get("imageApproved").getAsBoolean(), "no acceptance claims");
        check(evidence.get("warmupExcluded").getAsBoolean() && evidence.get("capturedRendersOnly").getAsBoolean(), "only captured work qualifies");
        check(spec.get("cpuDrawRecordWorkerMotionOnly").getAsBoolean() && !spec.has("synchronousCpuMetadataMotionOnly"), "worker evidence correctly labeled");
        check(spec.get("pngReadbacksMayChangeWorkerCadence").getAsBoolean() && !spec.get("latencyMeasured").getAsBoolean()
            && !spec.get("sustainableCadenceMeasured").getAsBoolean() && !spec.get("phase1AsyncAcceptanceEligible").getAsBoolean(), "readback limitation explicit");
        var starts = (ReuseSequence.PrepareCounters[])field("prepareCountersAtArmStart").get(sequence);
        var ends = (ReuseSequence.PrepareCounters[])field("prepareCountersAtArmEnd").get(sequence);
        starts[0] = starts[1] = ReuseSequence.prepareCounters(prepareSnapshot(false), false);
        ends[0] = starts[0]; ends[1] = ReuseSequence.prepareCounters(prepareSnapshot(true), true);
        check(!JsonParser.parseString(sequence.resultJson()).getAsJsonObject().getAsJsonObject("prepareActivity")
            .get("activityObserved").getAsBoolean(), "receipt rejects warmup-only activity");
        JsonObject off = prepareSnapshot(false); increment(off, "uploadDefers", 1);
        ends[0] = ReuseSequence.prepareCounters(off, false);
        ends[1] = ReuseSequence.prepareCounters(prepareOn(), true);
        result = JsonParser.parseString(sequence.resultJson()).getAsJsonObject(); evidence = result.getAsJsonObject("prepareActivity");
        check(evidence.get("activityObserved").getAsBoolean() && !result.get("captureComplete").getAsBoolean(), "CPU evidence never completes capture");
        JsonObject totals = evidence.getAsJsonObject("byArm"), difference = evidence.getAsJsonObject("diagnosticDifferenceOnMinusOff");
        check(totals.getAsJsonObject("off").get("offers").getAsLong() == 0
            && totals.getAsJsonObject("off").get("uploadDefers").getAsLong() == 1
            && totals.getAsJsonObject("on").get("offers").getAsLong() == 2
            && totals.getAsJsonObject("on").get("acceptedClusters").getAsLong() == 128
            && totals.getAsJsonObject("on").get("workerNs").getAsLong() == 40, "exact captured arm deltas serialized");
        check(difference.get("rejected").getAsLong() == 1 && difference.get("fallbacks").getAsLong() == 1
            && difference.get("uploadDefers").getAsLong() == 3, "rejection/fallback/upload-deferral interval differences reported");
        check(evidence.getAsJsonArray("armStart").get(1).getAsJsonObject().getAsJsonObject("values").get("accepted").getAsLong() == 100,
            "warmup baseline preserved separately from measured deltas");
        prepareActivity();
    }

    private static void checkPrepareHeld(Class<?> gpu) throws Exception {
        var flags = ReuseSequence.prepareSettings();
        check(Boolean.TRUE.equals(flags.get("cacheItems")) && Boolean.TRUE.equals(flags.get("cacheGroups"))
            && Boolean.TRUE.equals(flags.get("atomicMeshHandoff")), "real production held flags on");
        for (String name : List.of("cacheItems", "cacheGroups", "cacheNodeItems")) {
            Field flag = gpu.getField(name); flag.setAccessible(true);
            boolean saved = flag.getBoolean(null), rejected = false;
            try {
                flag.setBoolean(null, !saved);
                try { ReuseSequence.prepareSettings(); } catch (IllegalStateException expected) { rejected = true; }
            } finally { flag.setBoolean(null, saved); }
            check(rejected, "tampered real production flag rejected: " + name);
        }
        Field reuse = Class.forName("net.minecraft.client.renderer.chunk.LodReuse").getField("enabled");
        boolean saved = reuse.getBoolean(null), rejected = false;
        try {
            reuse.setBoolean(null, true);
            try { ReuseSequence.prepareSettings(); } catch (IllegalStateException expected) { rejected = true; }
        } finally { reuse.setBoolean(null, saved); }
        check(rejected, "actual image reuse toggle tampering rejected");
        for (String property : List.of("mc.lod.diag", "mc.lod.cacheGroups.diagnostics", "mc.lod.itemBuildProfile", "mc.lod.prepareProfile", "mc.lod.async", "mc.lod.reuse")) {
            String old = System.getProperty(property); rejected = false;
            try {
                System.setProperty(property, property.equals("mc.lod.diag") ? "levels" : "true");
                try { ReuseSequence.prepareSettings(); } catch (IllegalStateException expected) { rejected = true; }
            } finally {
                if (old == null) System.clearProperty(property); else System.setProperty(property, old);
            }
            check(rejected, "tampered diagnostic/profiling/far-rendering property rejected: " + property);
        }
        check(flags.equals(ReuseSequence.prepareSettings()), "held flags restored after policy tests");
        for (String name : List.of("asyncOffers", "asyncAccepted", "asyncRejected", "asyncBootstrap", "asyncFallbacks", "asyncUploadDefers",
                "asyncCaptureNs", "asyncInstallNs", "asyncWorkerNs", "asyncAcceptedClusters"))
            check(gpu.getDeclaredField(name).getType() == long.class, "real frozen client has production counter " + name);
        check(gpu.getDeclaredMethod("statsJson").getReturnType() == String.class, "real frozen stats entrypoint exists; not invoked without renderer");
    }

    public static void main(String[] args) throws Exception {
        boolean items = args[0].equals("items");
        boolean groups = args[0].equals("groups"), nodes = args[0].equals("nodes"), prepare = args[0].equals("prepare");
        boolean metadata = items || groups || nodes || prepare;
        boolean motion = "phase0-motion".equals(System.getProperty("bench.reuseTrajectory", metadata ? "phase0-motion" : "default"));
        Path root = Path.of(args[1]);
        ReuseSequence sequence = new ReuseSequence(root.toFile());
        Field toggle = (Field)field("enabled").get(sequence);
        check(toggle.getName().equals(prepare ? "asyncItems" : nodes ? "cacheNodeItems" : groups ? "cacheGroups" : items ? "cacheItems" : "enabled"), "correct runtime field");
        check(toggle.getDeclaringClass().getName().endsWith(metadata ? ".LodGpu" : ".LodReuse"), "correct runtime owner");
        check(toggle.canAccess(null), "constructor enabled reflection access");
        check(Path.of(toggle.getDeclaringClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()
            .equals(Path.of(System.getProperty("reuseSequence.testClientJar")).toRealPath()), "controls loaded from actual frozen client jar");
        check(Modifier.isPublic(toggle.getModifiers()) && Modifier.isStatic(toggle.getModifiers())
            && Modifier.isVolatile(toggle.getModifiers()), "real mutable volatile field");
        if (metadata) check(!Modifier.isPublic(toggle.getDeclaringClass().getModifiers()), "test actually covers package-private LodGpu");
        boolean saved = toggle.getBoolean(null);
        check(saved == Boolean.getBoolean(prepare ? "mc.lod.asyncItems" : nodes ? "mc.lod.cacheNodeItems" : groups ? "mc.lod.cacheGroups" : items ? "mc.lod.cacheItems" : "mc.lod.reuse"), "constructor preserves initial flag");
        Method setArm = method("setArm", boolean.class);
        try {
            setArm.invoke(sequence, false); check(!toggle.getBoolean(null), "OFF write/readback");
            if (groups) checkItemsHeld(toggle.getDeclaringClass());
            if (nodes) checkNodeHeld(toggle.getDeclaringClass());
            if (prepare) checkPrepareHeld(toggle.getDeclaringClass());
            setArm.invoke(sequence, true); check(toggle.getBoolean(null), "ON write/readback");
            if (groups) checkItemsHeld(toggle.getDeclaringClass());
            if (nodes) checkNodeHeld(toggle.getDeclaringClass());
            if (prepare) checkPrepareHeld(toggle.getDeclaringClass());
        } finally { setArm.invoke(sequence, saved); }
        check(toggle.getBoolean(null) == saved, "saved value can be restored through real arm setter");
        List<?> poses = (List<?>)field("poses").get(sequence);
        check(poses.size() == (motion ? 120 : 25), "existing trajectory lengths");
        JsonObject result = JsonParser.parseString(sequence.resultJson()).getAsJsonObject();
        check(result.get("feature").getAsString().equals(args[0]), "feature receipt");
        check(!result.get("captureComplete").getAsBoolean(), "construction is never capture success");
        check(!result.get("imageGateEvaluated").getAsBoolean() && !result.get("releaseEligible").getAsBoolean(), "no image/acceptance claim");
        check(result.get("onHeldImageReuseHitRequired").getAsBoolean() == (!metadata && !motion), "reuse held-hit rule preserved");
        check(result.has("itemBuildEvidence") == items, "items evidence does not replace reuse receipts");
        check(result.has("groupBuildEvidence") == groups, "groups evidence remains separate");
        check(result.has("nodeBuildEvidence") == nodes, "nodes evidence remains separate");
        check(result.has("prepareActivity") == prepare, "prepare evidence remains separate");
        if (items) {
            JsonObject evidence = result.getAsJsonObject("itemBuildEvidence");
            check(!evidence.get("onIncreaseObserved").getAsBoolean(), "no activity cannot be reported as exercised");
            check(!evidence.get("forceBuild").getAsBoolean(), "natural rebuilds only");
            check(result.getAsJsonObject("trajectorySpec").get("synchronousCpuMetadataMotionOnly").getAsBoolean(), "synchronous metadata label");
            long[] start = (long[])field("itemBuildsAtArmStart").get(sequence);
            long[] end = (long[])field("itemBuildsAtArmEnd").get(sequence);
            int[] observed = (int[])field("itemAppendFramesByArm").get(sequence);
            start[0] = end[0] = 10; start[1] = 10; end[1] = 11; observed[1] = 1;
            result = JsonParser.parseString(sequence.resultJson()).getAsJsonObject();
            evidence = result.getAsJsonObject("itemBuildEvidence");
            check(evidence.getAsJsonArray("armStart").get(1).getAsLong() == 10
                && evidence.getAsJsonArray("armEnd").get(1).getAsLong() == 11
                && evidence.getAsJsonArray("nonemptyAppendFrames").get(1).getAsInt() == 1, "arm evidence serialized exactly");
            check(evidence.get("onIncreaseObserved").getAsBoolean() && !result.get("captureComplete").getAsBoolean(), "counter evidence alone cannot complete capture");
        }
        if (groups) {
            JsonObject evidence = result.getAsJsonObject("groupBuildEvidence");
            check(!evidence.get("activityObserved").getAsBoolean(), "no group activity cannot qualify");
            check(!evidence.get("forceBuild").getAsBoolean(), "group rebuilds remain natural");
            check(result.getAsJsonObject("trajectorySpec").get("synchronousCpuMetadataMotionOnly").getAsBoolean(), "groups labeled synchronous CPU evidence");
            long[][] starts = (long[][])field("groupBuildsAtArmStart").get(sequence), ends = (long[][])field("groupBuildsAtArmEnd").get(sequence);
            int[] observed = (int[])field("itemAppendFramesByArm").get(sequence);
            starts[0] = new long[]{0,0,10,0}; ends[0] = new long[]{0,0,11,0};
            starts[1] = new long[]{0,0,11,0}; ends[1] = new long[]{0,0,11,1}; observed[1] = 1;
            result = JsonParser.parseString(sequence.resultJson()).getAsJsonObject();
            evidence = result.getAsJsonObject("groupBuildEvidence");
            check(evidence.getAsJsonArray("armStart").get(0).getAsJsonArray().get(2).getAsLong() == 10
                && evidence.getAsJsonArray("armEnd").get(1).getAsJsonArray().get(3).getAsLong() == 1, "full arm-mode snapshots serialized");
            check(evidence.get("activityObserved").getAsBoolean() && !result.get("captureComplete").getAsBoolean(), "group counters alone never complete capture");
            groupActivity();
        }
        if (nodes) {
            JsonObject evidence = result.getAsJsonObject("nodeBuildEvidence");
            check(!evidence.get("activityObserved").getAsBoolean(), "no node activity cannot qualify");
            check(!evidence.get("forceBuild").getAsBoolean(), "node rebuilds remain natural");
            check(result.getAsJsonObject("trajectorySpec").get("synchronousCpuMetadataMotionOnly").getAsBoolean(), "nodes labeled synchronous CPU evidence");
            check(!result.getAsJsonObject("trajectorySpec").get("sustainableCadenceMeasured").getAsBoolean(), "no cadence claim");
            var starts = (ReuseSequence.NodeCounters[])field("nodeCountersAtArmStart").get(sequence);
            var ends = (ReuseSequence.NodeCounters[])field("nodeCountersAtArmEnd").get(sequence);
            starts[0] = new ReuseSequence.NodeCounters(10,0,0,0); ends[0] = new ReuseSequence.NodeCounters(11,0,0,0);
            starts[1] = new ReuseSequence.NodeCounters(11,0,0,0); ends[1] = new ReuseSequence.NodeCounters(11,1,2,128);
            result = JsonParser.parseString(sequence.resultJson()).getAsJsonObject();
            evidence = result.getAsJsonObject("nodeBuildEvidence");
            check(evidence.getAsJsonArray("armStart").get(0).getAsJsonObject().get("offBuilds").getAsLong() == 10
                && evidence.getAsJsonArray("armEnd").get(1).getAsJsonObject().get("packetHits").getAsLong() == 2
                && evidence.getAsJsonArray("armEnd").get(1).getAsJsonObject().get("replayedClusters").getAsLong() == 128, "node arm snapshots serialized exactly");
            check(evidence.get("activityObserved").getAsBoolean() && !result.get("captureComplete").getAsBoolean(), "node counters alone never complete capture");
            nodeActivity();
        }
        if (prepare) prepareEvidence(sequence);
        method("manifest").invoke(sequence);
        check(Files.isRegularFile(root.resolve("screenshots/reuseseq/manifest.json")), "manifest writable without renderer");
        if (prepare) {
            JsonObject manifest = JsonParser.parseString(Files.readString(root.resolve("screenshots/reuseseq/manifest.json"))).getAsJsonObject();
            check(manifest.get("prepareActivity").equals(JsonParser.parseString(sequence.resultJson()).getAsJsonObject().get("prepareActivity")),
                "manifest and result carry identical interval evidence");
            check(manifest.get("limitation").getAsString().contains("PNG readbacks"), "manifest warns readbacks change worker cadence");
        }
        activity();
        boolean diagnostic = Boolean.getBoolean("bench.reuseBoundaryProbe");
        check(JsonParser.parseString(sequence.resultJson()).getAsJsonObject().get("diagnosticOnly").getAsBoolean() == diagnostic,
            "diagnostic marker matches actual probe");
        Field complete = field("complete");
        complete.setBoolean(sequence, true);
        check(JsonParser.parseString(sequence.resultJson()).getAsJsonObject().get("captureComplete").getAsBoolean() == !diagnostic,
            "diagnostic cannot qualify even with complete capture");
        method("manifest").invoke(sequence);
        JsonObject finalManifest = JsonParser.parseString(Files.readString(root.resolve("screenshots/reuseseq/manifest.json"))).getAsJsonObject();
        check(finalManifest.get("diagnosticOnly").getAsBoolean() == diagnostic
            && finalManifest.get("captureComplete").getAsBoolean() == !diagnostic,
            "manifest diagnostic cannot qualify");
        complete.setBoolean(sequence, false);
        check(LodBoundaryProbe.sample(null, 31).isEmpty() && LodBoundaryProbe.sample(null, 36).isEmpty(),
            "probe touches no game outside four bounded frames");
        System.out.println("ReuseSequenceItemsTest: " + checks + " checks passed (feature=" + args[0]
            + ", trajectory=" + (motion ? "phase0-motion" : "default") + ", savedFlag=" + saved + ")");
    }

    private static void checkItemsHeld(Class<?> gpu) throws Exception {
        Field items = gpu.getField("cacheItems"), diagnostics = gpu.getDeclaredField("GROUP_LOOKUP_DIAGNOSTICS");
        items.setAccessible(true); diagnostics.setAccessible(true);
        check(items.getBoolean(null) && !diagnostics.getBoolean(null), "real group arm keeps items=true and diagnostics=false");
    }

    private static void checkNodeHeld(Class<?> gpu) throws Exception {
        Field items = gpu.getField("cacheItems"), groups = gpu.getField("cacheGroups"),
            diagnostics = gpu.getDeclaredField("GROUP_LOOKUP_DIAGNOSTICS"), profile = gpu.getDeclaredField("ITEM_BUILD_PROFILE");
        for (Field f : List.of(items, groups, diagnostics, profile)) f.setAccessible(true);
        check(ReuseSequence.nodeControlsValid(items.getBoolean(null), groups.getBoolean(null), diagnostics.getBoolean(null), profile.getBoolean(null)),
            "node toggle leaves held items/groups/diagnostics/profile fields unchanged");
    }
}
