import bench.BenchAgent;
import bench.ChunkProbe;
import bench.Lockstep;
import it.unimi.dsi.fastutil.floats.FloatUnaryOperator;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * Headless test of the zoomseq lockstep hook (BenchAgent.lockstepTimer + Lockstep): transforms the vanilla
 * DeltaTracker$Timer class, loads it (the JVM verifier checks the inserted bytecode) and drives it like the game loop.
 * Checks that with Lockstep off it behaves exactly like the untransformed class, that with Lockstep on it ignores
 * wall-clock time (one tick every K frames, partial tick (frame mod K)/K, delta 1/K), that hold freezes the clock and
 * that vanilla timing resumes after stop without a catch-up burst. No GPU, window or game instance.
 * Run through tools/bench-hook-test.sh (needs build/bench-classes from tools/build-bench.sh).
 */
public final class BenchHookTest {
    private static final String TIMER = "net.minecraft.client.DeltaTracker$Timer";
    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        byte[] original;
        try (ZipFile z = new ZipFile(args[0])) {
            original = z.getInputStream(z.getEntry(TIMER.replace('.', '/') + ".class")).readAllBytes();
        }
        ClassLoader parent = BenchHookTest.class.getClassLoader();
        byte[] patched = BenchAgent.lockstepTimer(original, parent);
        check("transform produced a class", patched != null && patched.length > original.length);
        ClassLoader child = new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!TIMER.equals(name)) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        c = defineClass(name, patched, 0, patched.length);
                    }
                    if (resolve) {
                        resolveClass(c);
                    }
                    return c;
                }
            }
        };
        Class<?> hooked = Class.forName(TIMER, true, child); // links: runs the bytecode verifier on the hook
        Class<?> vanilla = Class.forName(TIMER, true, parent);
        check("hooked class is a separate definition", hooked != vanilla);
        FloatUnaryOperator mspt = f -> f;
        T h = new T(hooked, mspt);
        T v = new T(vanilla, mspt);

        // 1. Lockstep off: identical to vanilla.
        long[] times = {1010, 1049, 1050, 1125, 1300, 1301, 1302, 2000};
        for (long ms : times) {
            int a = h.advance(ms), b = v.advance(ms);
            check("off: ticks at " + ms, a == b);
            check("off: partial at " + ms, h.partial() == v.partial());
            check("off: delta at " + ms, h.delta() == v.delta());
        }

        // 2. Lockstep on, 2 frames per tick: wall-clock time is ignored.
        Lockstep.start(2);
        long[] wild = {5000, 5000, 4000, 99999, 100000, 100001};
        int[] ticks = {1, 0, 1, 0, 1, 0};
        float[] partials = {0.0F, 0.5F, 0.0F, 0.5F, 0.0F, 0.5F};
        for (int i = 0; i < wild.length; i++) {
            check("K=2 frame " + i + " ticks", h.advance(wild[i]) == ticks[i]);
            check("K=2 frame " + i + " partial", h.partial() == partials[i]);
            check("K=2 frame " + i + " delta", h.delta() == 0.5F);
            check("K=2 frame index " + i, Lockstep.frame() == i);
        }

        // 3. Hold: no tick, same partial, zero delta, same frame.
        Lockstep.hold(true);
        for (int i = 0; i < 3; i++) {
            check("hold ticks", h.advance(200000 + i * 1000L) == 0);
            check("hold partial", h.partial() == 0.5F);
            check("hold delta", h.delta() == 0.0F);
            check("hold frame", Lockstep.frame() == 5);
        }
        Lockstep.hold(false);
        check("after hold: tick", h.advance(300000) == 1);
        check("after hold: partial", h.partial() == 0.0F);
        check("after hold: frame", Lockstep.frame() == 6);
        check("steps counted", Lockstep.steps() == 10);

        // 4. Lockstep on, 3 frames per tick.
        Lockstep.start(3);
        int[] ticks3 = {1, 0, 0, 1, 0, 0, 1};
        for (int i = 0; i < ticks3.length; i++) {
            check("K=3 frame " + i + " ticks", h.advance(400000 - i) == ticks3[i]);
            check("K=3 frame " + i + " partial", Math.abs(h.partial() - (i % 3) / 3.0F) < 1e-6F);
            check("K=3 frame " + i + " delta", Math.abs(h.delta() - 1.0F / 3.0F) < 1e-6F);
        }

        // 5. Stop: vanilla timing resumes from the last time seen, no catch-up burst.
        h.advance(500000); // frame 7: partial 1/3, lastMs = 500000
        Lockstep.stop();
        int resumed = h.advance(500050); // residual 1/3 + 1 tick of 50 ms
        check("stop: one tick after 50 ms (got " + resumed + ")", resumed == 1);
        check("stop: residual carried", Math.abs(h.partial() - 1.0F / 3.0F) < 1e-5F);
        check("stop: vanilla delta", h.delta() == 1.0F);

        // 6. Frame-trace probes (-Dbench.frameTrace, CONTRACT.md 16.R5-B) on Minecraft.runTick / renderFrame: the vanilla
        //    class and, when given (args[1]), the patched client's. Every probe lands exactly once, the class verifies,
        //    renderFrame still starts with Bench.onFrame, and no other method body changes.
        traceProbes("vanilla", args[0]);
        if (args.length > 1 && new java.io.File(args[1]).isFile()) {
            traceProbes("patched", args[1]);
        }

        // 7. FrameProbe + FrameTrace driven like the game loop (needs -Dbench.frameTrace=true): row contents, zero
        //    allocation per frame, and a result JSON for tools/frame_trace.py (args[2]).
        if (Boolean.getBoolean("bench.frameTrace")) {
            traceRows(args.length > 2 ? args[2] : null);
        } else {
            check("run with -Dbench.frameTrace=true (tools/bench-hook-test.sh does)", false);
        }

        // 8. Scenes-mode entity capture hook (LevelRenderer.submitEntities starts with EntityCapture.onSubmit(state)) on the
        //    vanilla and the patched LevelRenderer: inserted once, verifies, nothing else changes.
        captureHook("vanilla", args[0]);
        if (args.length > 1 && new java.io.File(args[1]).isFile()) {
            captureHook("patched", args[1]);
        }

        // 9. Stage-2 W0b chunk probes (bench.chunkStats): every ChunkProbe target exists in the vanilla jar and the patched
        //    client, is wrapped exactly once (enter at the start, exit before every return), the class verifies, and no
        //    other method changes.
        chunkProbes("vanilla", args[0]);
        if (args.length > 1 && new java.io.File(args[1]).isFile()) {
            chunkProbes("patched", args[1]);
        }

        System.out.println(String.format(Locale.ROOT, "BenchHookTest: %d checks, %d failures", checks, failures));
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void chunkProbes(String name, String jarPath) throws Exception {
        java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[]{new java.io.File(jarPath).toURI().toURL()},
            BenchHookTest.class.getClassLoader());
        java.lang.classfile.ClassFile cf = java.lang.classfile.ClassFile.of(java.lang.classfile.ClassFile.ClassHierarchyResolverOption.of(
            java.lang.classfile.ClassHierarchyResolver.defaultResolver().orElse(java.lang.classfile.ClassHierarchyResolver.ofResourceParsing(loader))));
        java.util.Set<String> owners = new java.util.LinkedHashSet<>();
        for (String[] t : ChunkProbe.TARGETS) {
            owners.add(t[0]);
        }
        for (String owner : owners) {
            byte[] original;
            try (ZipFile z = new ZipFile(jarPath)) {
                original = z.getInputStream(z.getEntry(owner + ".class")).readAllBytes();
            }
            int[] before = ChunkProbe.inserted.clone();
            byte[] patched = BenchAgent.chunkProbes(original, loader, owner);
            java.util.List<VerifyError> errors = cf.verify(patched);
            check(name + ": chunk probes in " + owner + " verify (" + errors + ")", errors.isEmpty());
            java.lang.classfile.ClassModel b = cf.parse(original);
            java.lang.classfile.ClassModel a = cf.parse(patched);
            for (int id = 0; id < ChunkProbe.COUNT; id++) {
                if (ChunkProbe.TARGETS[id][0].equals(owner)) {
                    check(name + ": chunk probe " + ChunkProbe.NAMES[id] + " inserted once", ChunkProbe.inserted[id] - before[id] == 1);
                }
            }
            for (int i = 0; i < b.methods().size(); i++) {
                java.lang.classfile.MethodModel mb = b.methods().get(i);
                java.lang.classfile.MethodModel ma = a.methods().get(i);
                boolean target = false;
                for (String[] t : ChunkProbe.TARGETS) {
                    target |= t[0].equals(owner) && mb.methodName().equalsString(t[1]) && mb.methodType().equalsString(t[2]);
                }
                byte[] cb = mb.findAttribute(java.lang.classfile.Attributes.code()).map(c -> c.codeArray()).orElse(new byte[0]);
                byte[] ca = ma.findAttribute(java.lang.classfile.Attributes.code()).map(c -> c.codeArray()).orElse(new byte[0]);
                if (target) {
                    check(name + ": " + owner + "." + mb.methodName() + " grew", ca.length > cb.length);
                } else if (!java.util.Arrays.equals(ca, cb)) {
                    check(name + ": " + owner + "." + mb.methodName() + mb.methodType() + " must not change", false);
                }
            }
        }
    }

    private static final String MINECRAFT = "net/minecraft/client/Minecraft";

    private static void captureHook(String name, String jarPath) throws Exception {
        String cls = "net/minecraft/client/renderer/LevelRenderer";
        byte[] original;
        try (ZipFile z = new ZipFile(jarPath)) {
            original = z.getInputStream(z.getEntry(cls + ".class")).readAllBytes();
        }
        java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[]{new java.io.File(jarPath).toURI().toURL()},
            BenchHookTest.class.getClassLoader());
        byte[] patched = BenchAgent.entityCaptureHook(original, loader);
        check(name + ": entity capture hook report submitEntities=1 (got " + BenchAgent.entityCaptureReport + ")",
            "submitEntities=1".equals(BenchAgent.entityCaptureReport));
        java.lang.classfile.ClassFile cf = java.lang.classfile.ClassFile.of(java.lang.classfile.ClassFile.ClassHierarchyResolverOption.of(
            java.lang.classfile.ClassHierarchyResolver.defaultResolver().orElse(java.lang.classfile.ClassHierarchyResolver.ofResourceParsing(loader))));
        java.util.List<VerifyError> errors = cf.verify(patched);
        check(name + ": LevelRenderer with the capture hook verifies (" + errors + ")", errors.isEmpty());
        java.lang.classfile.ClassModel before = cf.parse(original), after = cf.parse(patched);
        int unchanged = 0;
        for (int i = 0; i < before.methods().size(); i++) {
            java.lang.classfile.MethodModel b = before.methods().get(i), a = after.methods().get(i);
            String sig = b.methodName().stringValue() + b.methodType().stringValue();
            byte[] cb = b.findAttribute(java.lang.classfile.Attributes.code()).map(c -> c.codeArray()).orElse(new byte[0]);
            byte[] ca = a.findAttribute(java.lang.classfile.Attributes.code()).map(c -> c.codeArray()).orElse(new byte[0]);
            if (b.methodName().equalsString("submitEntities")) {
                java.util.List<java.lang.classfile.Instruction> ins = new java.util.ArrayList<>();
                for (java.lang.classfile.CodeElement e : a.code().orElseThrow()) {
                    if (e instanceof java.lang.classfile.Instruction in) {
                        ins.add(in);
                    }
                }
                check(name + ": " + sig + " starts with aload_2; invokestatic EntityCapture.onSubmit(LevelRenderState)",
                    ins.size() > 2 && ins.get(0) instanceof java.lang.classfile.instruction.LoadInstruction ld && ld.slot() == 2
                        && ins.get(1) instanceof java.lang.classfile.instruction.InvokeInstruction inv && inv.owner().asInternalName().equals("bench/EntityCapture")
                        && inv.name().equalsString("onSubmit") && inv.type().stringValue().equals("(Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V"));
                check(name + ": rest of " + sig + " unchanged", java.util.Arrays.equals(java.util.Arrays.copyOfRange(ca, ca.length - cb.length, ca.length), cb));
            } else if (java.util.Arrays.equals(cb, ca)) {
                unchanged++;
            } else {
                check(name + ": " + sig + " must not change", false);
            }
        }
        System.out.println(name + ": entity capture hook, " + unchanged + " other LevelRenderer methods byte-identical");
    }

    private static void traceProbes(String name, String jarPath) throws Exception {
        byte[] original;
        try (ZipFile z = new ZipFile(jarPath)) {
            original = z.getInputStream(z.getEntry(MINECRAFT + ".class")).readAllBytes();
        }
        java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[]{new java.io.File(jarPath).toURI().toURL()},
            BenchHookTest.class.getClassLoader());
        byte[] patched = BenchAgent.traceHooks(original, loader);
        String report = BenchAgent.traceReport();
        System.out.println("frameTrace probes (" + name + "): " + report);
        // The patched client's late-throttle bundle (CONTRACT.md 16.R5-A) holds a second copy of the four runTick sites.
        boolean late = cfParse(original).methods().stream().anyMatch(m -> m.methodName().equalsString(BenchAgent.LATE_BUNDLE));
        check(name + ": late-throttle bundle present iff patched", late == name.equals("patched"));
        java.util.Set<String> tickSites = java.util.Set.of("packets", "tasks", "texTick", "tick");
        for (String part : report.split(", ")) {
            String[] kv = part.split("=");
            String expected = late && tickSites.contains(kv[0]) ? "2" : "1";
            check(name + ": probe " + kv[0] + " inserted " + expected + "x (got " + kv[1] + ")", kv.length == 2 && kv[1].equals(expected));
        }
        java.lang.classfile.ClassFile cf = java.lang.classfile.ClassFile.of(java.lang.classfile.ClassFile.ClassHierarchyResolverOption.of(
            java.lang.classfile.ClassHierarchyResolver.defaultResolver().orElse(java.lang.classfile.ClassHierarchyResolver.ofResourceParsing(loader))));
        java.util.List<VerifyError> errors = cf.verify(patched);
        check(name + ": transformed Minecraft verifies (" + errors + ")", errors.isEmpty());
        java.lang.classfile.ClassModel before = cf.parse(original);
        java.lang.classfile.ClassModel after = cf.parse(patched);
        check(name + ": same method count", before.methods().size() == after.methods().size());
        int unchanged = 0;
        for (int i = 0; i < before.methods().size() && i < after.methods().size(); i++) {
            java.lang.classfile.MethodModel b = before.methods().get(i);
            java.lang.classfile.MethodModel a = after.methods().get(i);
            String sig = b.methodName().stringValue() + b.methodType().stringValue();
            check(name + ": method order " + sig, sig.equals(a.methodName().stringValue() + a.methodType().stringValue()));
            boolean probed = sig.equals("runTick(Z)V") || sig.equals("renderFrame(Z)V") || sig.startsWith(BenchAgent.LATE_BUNDLE + "(");
            byte[] cb = b.findAttribute(java.lang.classfile.Attributes.code()).map(c -> c.codeArray()).orElse(new byte[0]);
            byte[] ca = a.findAttribute(java.lang.classfile.Attributes.code()).map(c -> c.codeArray()).orElse(new byte[0]);
            if (probed) {
                check(name + ": " + sig + " grew", ca.length > cb.length);
            } else if (java.util.Arrays.equals(cb, ca)) {
                unchanged++;
            } else {
                check(name + ": " + sig + " must not change", false);
            }
            if (sig.equals("renderFrame(Z)V")) {
                java.lang.classfile.CodeModel code = a.code().orElseThrow();
                java.lang.classfile.Instruction first = null;
                for (java.lang.classfile.CodeElement e : code) {
                    if (e instanceof java.lang.classfile.Instruction ins) {
                        first = ins;
                        break;
                    }
                }
                check(name + ": renderFrame starts with Bench.onFrame", first instanceof java.lang.classfile.instruction.InvokeInstruction inv
                    && inv.owner().asInternalName().equals("bench/Bench") && inv.name().equalsString("onFrame"));
            }
        }
        System.out.println(name + ": " + unchanged + " other methods byte-identical");
    }

    private static void spin(long ns) {
        long end = System.nanoTime() + ns;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    /** One simulated game-loop iteration: renderFrame body (render, submit), runTick bundle, then onFrame (records). */
    private static long loop(int f, boolean tickFrame, long renderNs, long tickNs) {
        bench.FrameProbe.enter(bench.FrameProbe.RENDER);
        spin(renderNs);
        bench.FrameProbe.exit(bench.FrameProbe.RENDER);
        bench.FrameProbe.enter(bench.FrameProbe.SUBMIT);
        spin(30_000);
        bench.FrameProbe.exit(bench.FrameProbe.SUBMIT);
        bench.FrameProbe.ticks(tickFrame ? 1 : 0);
        bench.FrameProbe.enter(bench.FrameProbe.PACKETS);
        spin(20_000);
        bench.FrameProbe.exit(bench.FrameProbe.PACKETS);
        if (tickFrame) {
            bench.FrameProbe.enter(bench.FrameProbe.TICK);
            spin(tickNs);
            bench.FrameProbe.exit(bench.FrameProbe.TICK);
        }
        bench.FrameProbe.bundleEnd();
        return bench.TraceSelfTest.frame(f * 0.9);
    }

    private static void traceRows(String out) throws Exception {
        bench.TraceSelfTest.begin(2.0);
        check("selftest: no Metal backend", !bench.TraceSelfTest.metalLive());
        int frames = 400;
        for (int f = 0; f < frames; f++) {
            boolean tickFrame = f % 8 == 0;
            // Every 100th frame is a "render hitch" (1.2 ms) so the analyzer has non-tick slow frames too.
            loop(f, tickFrame, f % 100 == 50 ? 1_200_000L : 100_000L, 500_000L);
        }
        check("selftest: rows", bench.TraceSelfTest.rows() == frames);
        int bad = 0;
        for (int k = 0; k < frames; k++) {
            boolean tickFrame = k % 8 == 0;
            long ticks = bench.TraceSelfTest.value("ticks", k);
            long tick = bench.TraceSelfTest.value("tickNs", k);
            long bundle = bench.TraceSelfTest.value("bundleNs", k);
            long packets = bench.TraceSelfTest.value("packetsNs", k);
            long render = bench.TraceSelfTest.value("renderNs", k);
            long submit = bench.TraceSelfTest.value("submitNs", k);
            long pre = bench.TraceSelfTest.value("preSubmitNs", k);
            long dt = bench.TraceSelfTest.value("dtNs", k);
            boolean ok = ticks == (tickFrame ? 1 : 0) && bench.TraceSelfTest.value("ticksToDo", k) == ticks
                && (tickFrame ? tick >= 500_000L : tick == 0L) && packets >= 20_000L && render >= 100_000L && submit >= 30_000L
                && pre >= render && bundle >= packets + tick && dt >= pre + submit + bundle
                && bench.TraceSelfTest.value("seq", k) == -1L && bench.TraceSelfTest.value("waitNs", k) == -1L
                && bench.TraceSelfTest.value("gpuEndNs", k) == -1L && bench.TraceSelfTest.value("view", k) == Math.round(k * 0.9 * 1000.0)
                && bench.TraceSelfTest.value("tNs", k) > 0L;
            long bundleCpu = bench.TraceSelfTest.value("bundleCpuNs", k);
            ok &= bundleCpu == -1L || bundleCpu >= bundle / 4;   // the bundle spins: mostly CPU time
            if (!ok) {
                bad++;
                if (bad < 4) {
                    System.out.println("row " + k + ": ticks " + ticks + " tick " + tick + " bundle " + bundle + " packets " + packets + " render "
                        + render + " submit " + submit + " pre " + pre + " dt " + dt + " bundleCpu " + bundleCpu);
                }
            }
        }
        check("selftest: " + bad + " rows with inconsistent columns", bad == 0);
        String[] columns = bench.TraceSelfTest.columns();
        check("selftest: 46 columns, dtNs first", columns.length == 46 && columns[0].equals("dtNs"));

        // Zero allocation per frame (warm JIT): the probes and FrameTrace.record.
        // Every 100th frame is slower than -Dbench.frameTrace.jfrSlowMs (0.5 ms in tools/bench-hook-test.sh): with no JFR
        // recording running a slow frame must not allocate its event either (r5-int review fix).
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        for (int f = 0; f < 3000; f++) {
            loop(frames + f, f % 8 == 0, f % 100 == 3 ? 600_000L : 1_000L, 1_000L);
        }
        long a0 = mx.getCurrentThreadAllocatedBytes();
        for (int f = 0; f < 2000; f++) {
            loop(frames + 3000 + f, f % 8 == 0, f % 100 == 3 ? 600_000L : 1_000L, 1_000L);
        }
        long allocated = mx.getCurrentThreadAllocatedBytes() - a0;
        System.out.println("selftest: " + allocated + " bytes allocated over 2000 traced frames");
        check("selftest: zero allocation per traced frame (" + allocated + " bytes / 2000 frames)", allocated == 0L);

        String json = bench.TraceSelfTest.resultJson("bench-hook-selftest");
        check("selftest: JSON has rows", json.contains("\"rows\": [") && json.contains("\"config\": {"));
        if (out != null) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(out), json);
            System.out.println("selftest JSON: " + out + " (" + json.length() + " chars)");
        }
    }

    private static java.lang.classfile.ClassModel cfParse(byte[] bytes) {
        return java.lang.classfile.ClassFile.of().parse(bytes);
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    /** Reflective handle on one DeltaTracker$Timer definition (20 ticks per second, start time 1000 ms). */
    private static final class T {
        private final Object timer;
        private final Method advance;
        private final Method partial;
        private final Method delta;

        T(Class<?> c, FloatUnaryOperator mspt) throws Exception {
            Constructor<?> ctor = c.getConstructor(float.class, long.class, FloatUnaryOperator.class);
            timer = ctor.newInstance(20.0F, 1000L, mspt);
            advance = c.getMethod("advanceGameTime", long.class);
            partial = c.getMethod("getGameTimeDeltaPartialTick", boolean.class);
            delta = c.getMethod("getGameTimeDeltaTicks");
        }

        int advance(long ms) throws Exception {
            return (int) advance.invoke(timer, ms);
        }

        float partial() throws Exception {
            return (float) partial.invoke(timer, true);
        }

        float delta() throws Exception {
            return (float) delta.invoke(timer);
        }
    }
}
