package bench;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;

/** Frozen-client reflection and synthetic capture-policy checks only; no game/native/worker execution. */
public final class ReuseSequenceOpaqueTest {
    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void rejects(Action action) throws Exception {
        try { action.run(); }
        catch (IllegalStateException | IllegalArgumentException expected) { return; }
        throw new AssertionError("expected rejection");
    }
    private static Field field(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static JsonObject snapshot(boolean on, long frames) {
        JsonObject state = JsonParser.parseString("""
            {"meshHandoff":{"enabled":true},"gpu":{
             "itemCache":{"enabled":true},"groupCache":{"enabled":true,"diagnostics":false},
             "nodeItems":{"enabled":false},"itemBuildProfile":null,"asyncConsumer":null,
             "reuse":{"enabled":false},"asyncItems":{"enabled":true,"active":true,
             "pending":false,"failed":false,"error":"","opaqueRequested":%s,"opaqueFrames":%d}}}
            """.formatted(on, frames)).getAsJsonObject();
        for (String name : ReuseSequence.PREPARE_COUNTERS)
            state.getAsJsonObject("gpu").getAsJsonObject("asyncItems").addProperty(name, 0);
        return state;
    }
    public static void main(String[] args) throws Exception {
        if (args[0].equals("vanilla")) {
            try { Class.forName("net.minecraft.client.renderer.chunk.LodGpu"); throw new AssertionError("expected vanilla"); }
            catch (ClassNotFoundException expected) { }
            Photo.warmup(null); // f3=off, zoom=0, property unset: must not resolve any LOD classes.
        } else {
            Class<?> gpu = Class.forName("net.minecraft.client.renderer.chunk.LodGpu");
            check(Path.of(gpu.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()
                .equals(Path.of(System.getProperty("opaque.testClientJar")).toRealPath()), "real frozen client");
            Field opaque = field(gpu, "asyncOpaque");
            check(opaque.getType() == boolean.class && Modifier.isStatic(opaque.getModifiers())
                && Modifier.isVolatile(opaque.getModifiers()) && !Modifier.isFinal(opaque.getModifiers()), "volatile mutable bool");
            check(!opaque.getBoolean(null), "prototype defaults off even with mc.lod.asyncOpaque=true");
            if (args[0].equals("photo")) {
                String requested = System.getProperty("bench.photo.lodOpaque");
                if ("invalid".equals(requested)) {
                    rejects(() -> Photo.warmup(null));
                } else {
                    opaque.setBoolean(null, requested == null || !Boolean.parseBoolean(requested));
                    Photo.warmup(null);
                    boolean expected = requested == null || Boolean.parseBoolean(requested);
                    check(opaque.getBoolean(null) == expected, "photo applies optional flag / preserves unset");
                    opaque.setBoolean(null, !expected);
                    Photo.warmup(null);
                    check(opaque.getBoolean(null) == !expected, "photo applies once");
                }
            } else if (args[0].equals("bad-profile")) {
                rejects(ReuseSequence::opaqueSettings);
            } else {
                var held = ReuseSequence.opaqueSettings();
                check(Boolean.TRUE.equals(held.get("asyncItems")) && Boolean.TRUE.equals(held.get("nearHandoff")), "held settings recorded");
                ReuseSequence sequence = new ReuseSequence(Path.of(args[1]).toFile());
                check(((List<?>)field(ReuseSequence.class, "poses").get(sequence)).size() == 120, "120 motion frames");
                check(((Field)field(ReuseSequence.class, "enabled").get(sequence)).equals(opaque), "opaque toggle selected");
                var setArm = ReuseSequence.class.getDeclaredMethod("setArm", boolean.class); setArm.setAccessible(true);
                for (boolean on : new boolean[]{true, false}) {
                    setArm.invoke(sequence, on);
                    check(opaque.getBoolean(null) == on && held.equals(ReuseSequence.opaqueSettings()), "only opaque changes");
                }
                ((CaptureWriter)field(ReuseSequence.class, "imageWriter").get(sequence)).close();
                for (String name : List.of("asyncItems", "cacheItems", "cacheGroups", "cacheNodeItems")) {
                    Field f = field(gpu, name); boolean saved = f.getBoolean(null);
                    try { f.setBoolean(null, !saved); rejects(ReuseSequence::opaqueSettings); }
                    finally { f.setBoolean(null, saved); }
                }
                long start = ReuseSequence.opaqueFrames(snapshot(false, 20), false);
                long end = ReuseSequence.opaqueFrames(snapshot(true, 21), true); // Zero worker accepts suffice.
                check(ReuseSequence.opaqueActivityValid(0, start, start), "OFF no growth");
                check(ReuseSequence.opaqueActivityValid(1, start, end), "ON actual growth with zero accepts");
                check(!ReuseSequence.opaqueActivityValid(0, start, end), "OFF growth fails");
                check(!ReuseSequence.opaqueActivityValid(1, start, start), "no eligible ON frames fails");
                for (int arm : new int[]{0, 1}) {
                    check(!ReuseSequence.opaqueActivityValid(arm, -1, end), "missing boundary fails");
                    check(!ReuseSequence.opaqueActivityValid(arm, end, start), "counter reset fails");
                    rejects(() -> ReuseSequence.opaqueFrames(snapshot(arm == 0, 20), arm == 1));
                    JsonObject bad = snapshot(arm == 1, 20);
                    bad.getAsJsonObject("gpu").getAsJsonObject("asyncItems").addProperty("active", false);
                    rejects(() -> ReuseSequence.opaqueFrames(bad, arm == 1));
                }
                rejects(() -> ReuseSequence.opaqueFrames(snapshot(true, -1), true));
            }
        }
        System.out.println("PASS opaque CPU " + args[0]);
    }
}
