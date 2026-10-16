package com.mojang.renderpearl.backend.metal;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.renderer.chunk.LodFarGeometry;
import org.lwjgl.system.MemoryUtil;

/**
 * Real native resources, production Java FFM bridge, normal encoder, native decoder and byte readback.
 * Run only in the parent's serialized GPU lane. No rendering pipelines/window/game/far queue.
 * The oracle is a distinct pixel pattern per material/mip/coordinate and capture phase, not a copy
 * of the material state machine. Raw binding/job queries observe native state; never publish for Java.
 * Reflection is limited to geometry fixture construction and a reversible stream-growth fault.
 */
public final class MetalFarMaterialsTest {
    private static final int USAGE = GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST
        | GpuTexture.USAGE_TEXTURE_BINDING;
    private static final long BUDGET = 1 << 20;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "far-materials-test-worker"); t.setDaemon(true); return t;
    });
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static <T> T worker(Callable<T> action) throws Exception {
        try {
            return WORKER.submit(() -> {
                if (RenderSystem.isOnRenderThread()) throw new AssertionError("worker is render thread");
                return action.call();
            }).get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception x) throw x;
            if (e.getCause() instanceof Error x) throw x;
            throw new AssertionError(e.getCause());
        }
    }
    private static void rejects(Callable<?> action, String message) throws Exception {
        try { action.call(); }
        catch (IllegalStateException expected) { checks++; return; }
        throw new AssertionError(message);
    }

    // Read-only observers use the public C ABI in mcmetal_farmaterial.h.
    private static final class Observe {
        static MethodHandle bind(String symbol, FunctionDescriptor desc) {
            return Linker.nativeLinker().downcallHandle(MemorySegment.ofAddress(MtlNative.Loader.address(symbol)), desc);
        }
        static final MethodHandle BINDING = bind("mcmtl_ext_far_material_binding",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG));
        static final MethodHandle JOB = bind("mcmtl_ext_far_material_copy_job",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG));
        static Binding binding(long version, int index) throws Exception {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment b = a.allocate(64, 8);
                int status = (int)BINDING.invokeExact(version, index, b.address());
                check(status == MtlNative.OK, "native binding exists");
                return new Binding(b.get(JAVA_LONG, 0), b.get(JAVA_LONG, 8), b.get(JAVA_LONG, 16),
                    b.get(JAVA_INT, 24), b.get(JAVA_INT, 28), b.get(JAVA_INT, 32), b.get(JAVA_INT, 36),
                    b.get(JAVA_INT, 40), b.get(JAVA_INT, 44), b.get(JAVA_INT, 48));
            } catch (Exception | Error e) { throw e; }
            catch (Throwable t) { throw new AssertionError(t); }
        }
        static boolean hasJob(long version) throws Exception {
            try (Arena a = Arena.ofConfined()) {
                return (int)JOB.invokeExact(version, 0, a.allocate(32, 8).address()) == MtlNative.OK;
            } catch (Exception | Error e) { throw e; }
            catch (Throwable t) { throw new AssertionError(t); }
        }
    }
    private record Binding(long parent, long texture, long sampler, int width, int height,
                           int format, int mips, int base, int levels, int lodOverride) {
        // Borrowed facade ONLY for recording readback while the version or its production deferred
        // reference is alive. Never close/retain this facade: an extra +1 would mask lifetime bugs.
        MetalTexture borrowed(MetalDevice d) {
            return new MetalTexture(d, parent, format, USAGE, "borrowed material observer",
                GpuFormat.RGBA8_UNORM, width, height, 1, mips);
        }
    }

    private static final class Rig implements AutoCloseable {
        final MetalDevice d = new MetalDevice(new MetalBackend(), new GpuDebugOptions(0, false, true, true));
        final MetalCommandEncoder e = d.encoder();
        final List<AutoCloseable> owners = new ArrayList<>();
        final MetalTexture atlas = own(MetalTexture.create(d, "atlas", USAGE, GpuFormat.RGBA8_UNORM, 16, 8, 1, 5));
        final MetalTexture light = own(MetalTexture.create(d, "light", USAGE, GpuFormat.RGBA8_UNORM, 4, 2, 1, 3));
        final MetalTextureView av = own(MetalTextureView.create(d, atlas, 1, 2));
        final MetalTextureView lv = own(MetalTextureView.create(d, light, 0, 3));
        final MetalSampler as = own(MetalSampler.create(d, AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE,
            FilterMode.LINEAR, FilterMode.NEAREST, 1, OptionalDouble.of(1.25)));
        final MetalSampler ls = own(MetalSampler.create(d, AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
            FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty()));
        int submits;
        Rig() throws Exception {
            e.submitFunction = (device, info, result) -> { submits++; return MtlNative.submit(device, info, result); };
            write(1);
        }
        <T extends AutoCloseable> T own(T value) { owners.add(value); return value; }
        MetalFarMaterials.Recipe pin() {
            return own(MetalFarMaterials.pin(d, av, as, lv, ls, BUDGET));
        }
        MetalFarMaterials.Prepared prepare() throws Exception {
            MetalFarMaterials.Recipe recipe = pin();
            check(recipe != null, "pin returns owner");
            return own(worker(() -> { try (recipe) { return recipe.prepare(); } }));
        }
        boolean record(MetalFarMaterials.Prepared p) {
            check(!MetalFrameThrottle.ENABLED || d.frameThrottle().pendingSeq() == 0,
                "normal capture fixture must cross the game frame throttle before recording");
            return p.recordCopies(av, as, lv, ls);
        }
        void write(int phase) { upload(atlas, 0, phase); upload(light, 1, phase); }
        void upload(MetalTexture t, int material, int phase) {
            for (int m = 0; m < t.getMipLevels(); m++) {
                int w = Math.max(1, t.getWidth(m)), h = Math.max(1, t.getHeight(m));
                e.writeToTexture(t, ByteBuffer.wrap(pixels(material, phase, m, w, h)), m, 0, 0, 0, w, h);
            }
        }
        void commitAndWait() {
            long seq = d.timeline().pendingSeq();
            e.submit();
            check(d.timeline().await(seq, 5_000_000_000L), "bounded GPU completion");
            d.timeline().checkErrors();
            d.timeline().drain();
            MetalFrameThrottle.frameThrottle(); // normal game boundary, outside material capture
        }
        void closeSources() { av.close(); lv.close(); atlas.close(); light.close(); as.close(); ls.close(); }
        public void close() throws Exception {
            try {
                // Finish actual submitted work before dropping native versions observed by readbacks.
                long seq = d.timeline().lastCommittedSeq();
                if (!d.isClosed() && seq > 0) check(d.timeline().await(seq, 5_000_000_000L), "cleanup wait");
            } finally {
                try {
                    for (int i = owners.size() - 1; i >= 0; i--) owners.get(i).close();
                } finally { d.close(); }
            }
        }
    }

    private static byte[] pixels(int material, int phase, int mip, int w, int h) {
        byte[] result = new byte[w * h * 4];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int at = (y * w + x) * 4;
            result[at] = (byte)(phase * 41 + material * 67 + mip * 13 + x);
            result[at + 1] = (byte)(mip * 37 + y * 11 + material * 19);
            result[at + 2] = (byte)(phase * 17 + x * 7 + y * 3);
            result[at + 3] = (byte)(255 - material * 23 - mip * 5);
        }
        return result;
    }
    private record Readback(GpuBuffer buffer, byte[] expected, String label) {
        void verify() {
            byte[] actual = new byte[expected.length];
            try (var mapped = buffer.slice(0, actual.length).map(true, false)) { mapped.data().get(actual); }
            int mismatch = Arrays.mismatch(actual, expected);
            check(mismatch == -1, label + " differs at byte " + mismatch);
        }
    }
    private static Readback read(Rig r, Binding b, int material, int phase, int mip) {
        int w = Math.max(1, b.width >> mip), h = Math.max(1, b.height >> mip);
        byte[] expected = pixels(material, phase, mip, w, h);
        GpuBuffer buffer = r.own(r.d.createBuffer(() -> "material readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
            expected.length));
        r.e.copyTextureToBuffer(b.borrowed(r.d), buffer, 0, null, mip);
        return new Readback(buffer, expected, "material " + material + " mip " + mip + " phase " + phase);
    }
    private static void verifyAll(Rig r, MetalFarMaterials.Prepared p, int phase) throws Exception {
        List<Readback> reads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Binding b = Observe.binding(p.handle(), i);
            for (int m = 0; m < b.mips; m++) reads.add(read(r, b, i, phase, m));
        }
        r.commitAndWait();
        reads.forEach(Readback::verify);
    }

    /** Obtain the real MAIN event through the production geometry recipe, backed by a real Java
     * allocation lease. Reflection only opens package-private constructors for this tiny fixture. */
    private static final class Geometry implements AutoCloseable {
        final Object leases;
        final long recipe, event;
        final int[] disposed = {0};
        Geometry(Rig r) throws Exception {
            Class<?> lc = Class.forName("net.minecraft.client.renderer.chunk.LodGeometryLeases");
            Constructor<?> ctor = lc.getDeclaredConstructor(Runnable.class, Runnable.class); ctor.setAccessible(true);
            leases = ctor.newInstance((Runnable)RenderSystem::assertOnRenderThread, (Runnable)() -> disposed[0]++);
            Method acquire = lc.getDeclaredMethod("acquire"); acquire.setAccessible(true);
            Object lease = acquire.invoke(leases);
            Method retain = LodFarGeometry.class.getDeclaredMethod("retain", lease.getClass()); retain.setAccessible(true);
            long token = (long)retain.invoke(null, lease);
            check(token != 0, "real geometry lease registered");
            GpuBuffer vb = r.own(r.d.createBuffer(() -> "geometry lease vertices", GpuBuffer.USAGE_VERTEX, 48));
            GpuBuffer ib = r.own(r.d.createBuffer(() -> "geometry lease indices", GpuBuffer.USAGE_INDEX, 24));
            Constructor<LodFarGeometry.Input> ic = LodFarGeometry.Input.class.getDeclaredConstructor(int[].class,
                int.class, int.class, int.class, int.class, GpuBufferSlice.class, GpuBufferSlice.class);
            ic.setAccessible(true);
            var input = ic.newInstance(new int[4], 1, 1, 8, 0, vb.slice(0, 48), ib.slice(0, 24));
            Constructor<LodFarGeometry> gc = LodFarGeometry.class.getDeclaredConstructor(LodFarGeometry.Source.class,
                List.class, byte[].class, byte[].class, byte[].class, long.class, long.class, long.class);
            gc.setAccessible(true);
            var source = new LodFarGeometry.Source(1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            var snapshot = gc.newInstance(source, List.of(input), new byte[32], new byte[16], new byte[128], token, 192L, 0L);
            MetalFarGeometryPins pins = MetalFarGeometryPins.pin(r.d, snapshot);
            check(pins != null, "geometry pin succeeds");
            recipe = worker(() -> { try (pins) { return pins.captureRecipe(); } });
            check(recipe != 0, "worker geometry recipe owns MAIN event");
            event = worker(() -> MetalFarGeometryPins.uploadEvent(recipe));
            check(event != 0, "borrow actual retained event, never infer a timeline handle");
            ((AutoCloseable)leases).close();
            check(disposed[0] == 0, "closed geometry owner retained by native recipe");
        }
        public void close() throws Exception {
            worker(() -> { MetalFarGeometryPins.dropRecipe(recipe); return null; });
            MetalFarGeometryPins.pollReleases();
            LodFarGeometry.drainDeferredReleases();
            check(disposed[0] == 1, "geometry lease released on render thread");
        }
    }

    private static void roundtrip() throws Exception {
        try (Rig r = new Rig(); Geometry g = new Geometry(r)) {
            int submits = r.submits;
            long seq = r.d.timeline().pendingSeq(), stream = r.e.mainStream().size(), init = r.e.initStream().size();
            var recipe = r.pin();
            rejects(recipe::prepare, "render-thread allocation must reject");
            var p = r.own(worker(() -> { try (recipe) { return recipe.prepare(); } }));
            rejects(() -> p.publish(g.event), "render-thread publication must reject");
            rejects(() -> worker(() -> p.recordCopies(r.av, r.as, r.lv, r.ls)), "worker recording must reject");
            check(r.submits == submits && r.d.timeline().pendingSeq() == seq && r.e.mainStream().size() == stream
                && r.e.initStream().size() == init, "pin/prepare/thread rejection are passive");
            Binding a = Observe.binding(p.handle(), 0), l = Observe.binding(p.handle(), 1);
            check(a.parent != r.atlas.handle() && l.parent != r.light.handle(), "private copies have independent storage");
            check(a.texture != a.parent && a.base == 1 && a.levels == 2 && a.mips == 5,
                "restricted atlas view retained over full parent mip chain");
            check(l.texture == l.parent && l.base == 0 && l.levels == 3, "full light view remains full");
            check(a.sampler == r.as.handle() && l.sampler == r.ls.handle() && a.lodOverride == 0,
                "exact immutable samplers; descriptor clamp is not replaced by encoder override");
            r.write(2); // Pin does not freeze pixels: this is the content the capture must own.
            long ar = r.atlas.contentRevision(), lr = r.light.contentRevision();
            init = r.e.initStream().size(); stream = r.e.mainStream().size();
            check(r.record(p), "record all normal-stream copies");
            check(r.e.mainStream().size() - stream == 8L * StreamOps.SIZE_COPY_TEXTURE,
                "every parent mip including those outside the view copied on MAIN");
            check(r.e.initStream().size() == init, "wrapping copy parents must not schedule zero initialization");
            check(r.submits == submits && r.d.timeline().pendingSeq() == seq, "capture does not submit");
            check(p.atlasRevision() == ar && p.lightRevision() == lr, "capture-time source revisions");
            check(p.ticket().state() == MetalSubmissionTicket.State.PENDING && p.ticket().sequence() == seq,
                "passive ticket pending until real commit");
            check(!worker(() -> p.publish(g.event)) && Observe.hasJob(p.handle()), "pending publish is retryable, no native publication");
            check(!r.record(p), "cannot record a second copy set");
            r.write(3); // Later MAIN writes must not alter the version.
            r.closeSources(); // Native recipe + normal deferred refs must protect decoder inputs.
            MetalCommandEncoder.SubmitFunction real = r.e.submitFunction;
            r.e.submitFunction = (d, i, out) -> {
                int status = real.submit(d, i, out);
                check(p.ticket().state() == MetalSubmissionTicket.State.PENDING, "native return alone does not publish Java ticket");
                return status;
            };
            try { r.e.submit(); } finally { r.e.submitFunction = real; }
            check(p.ticket().state() == MetalSubmissionTicket.State.COMMITTED, "real successful commit publishes ticket");
            check(worker(() -> p.publish(g.event)), "worker publication before any explicit completion wait");
            check(!Observe.hasJob(p.handle()), "native version consumed copy jobs");
            check(!worker(() -> p.publish(g.event)), "publication is one-shot");
            verifyAll(r, p, 2);
        }
    }

    private static void stale() throws Exception {
        try (Rig r = new Rig()) {
            check(MetalFarMaterials.pin(r.d, null, r.as, r.lv, r.ls, BUDGET) == null, "null input declines");
            rejects(() -> MetalFarMaterials.pin(r.d, r.av, r.as, r.lv, r.ls, 1), "native byte budget enforced");
            for (int kind = 0; kind < 4; kind++) {
                var p = r.prepare();
                long bytes = r.e.mainStream().size();
                boolean accepted;
                if (kind == 0) {
                    var alias = r.own(MetalTextureView.create(r.d, r.atlas, 1, 2));
                    check("source-identity-changed".equals(p.recordingDeclineReason(alias,r.as,r.lv,r.ls)), "view preflight reports stale identity");
                    check(p.recordingDeclineReason(r.av,r.as,r.lv,r.ls)==null, "preflight alone does not consume recipe");
                    accepted = p.recordCopies(alias, r.as, r.lv, r.ls);
                } else if (kind == 1) {
                    var alias = r.own(MetalSampler.create(r.d, AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE,
                        FilterMode.LINEAR, FilterMode.NEAREST, 1, OptionalDouble.of(1.25)));
                    check(alias.handle() == r.as.handle(), "same native sampler, different Java draw identity");
                    check("source-identity-changed".equals(p.recordingDeclineReason(r.av,alias,r.lv,r.ls)), "sampler preflight reports Java identity change");
                    accepted = p.recordCopies(r.av, alias, r.lv, r.ls);
                } else if (kind == 2) {
                    worker(() -> { p.close(); p.close(); return null; }); accepted = r.record(p);
                } else {
                    r.lv.close(); accepted = r.record(p);
                }
                check(!accepted && p.ticket() == null && r.e.mainStream().size() == bytes,
                    "stale/closed case " + kind + " emits no copy or ticket");
                check(!r.record(p), "stale attempt cannot be retried with old inputs");
                check(!worker(() -> p.publish(0)), "stale version cannot publish");
            }
            r.commitAndWait();
        }
    }

    private static void cancelled(boolean partial) throws Exception {
        try (Rig r = new Rig()) {
            r.commitAndWait(); // isolate copies from setup uploads
            var p = r.prepare();
            Binding b = Observe.binding(p.handle(), 0);
            long begin = r.e.mainStream().size();
            if (partial) {
                // Fault only the stream storage boundary: leave backing bytes allocated, admit one
                // real COPY record, reject growth for the second. Restore before decoding the prefix.
                Field cap = StreamWriter.class.getDeclaredField("capacity"), closed = StreamWriter.class.getDeclaredField("closed");
                cap.setAccessible(true); closed.setAccessible(true);
                long original = cap.getLong(r.e.mainStream());
                try {
                    cap.setLong(r.e.mainStream(), begin + StreamOps.SIZE_COPY_TEXTURE);
                    closed.setBoolean(r.e.mainStream(), true);
                    rejects(() -> r.record(p), "second-copy stream failure must propagate");
                } finally { closed.setBoolean(r.e.mainStream(), false); cap.setLong(r.e.mainStream(), original); }
                check(r.e.mainStream().size() - begin == StreamOps.SIZE_COPY_TEXTURE, "one real copy survived partial error");
                check(p.ticket() == null && !worker(() -> p.publish(0)), "partial recording never publishes");
                check(!r.record(p), "partial capture cannot retry");
            } else { check(r.record(p), "full capture before cancellation"); }
            // No observer +1 protects b.parent. Only recordCopies' deferred wrapper owns it after close.
            worker(() -> { p.close(); p.close(); return null; });
            r.closeSources();
            r.d.timeline().drain(); // prior completed frames must not retire the pending copy parents
            Readback read = read(r, b, 0, 1, 0);
            r.commitAndWait();
            read.verify();
            check(p.handle() == 0 && !worker(() -> p.publish(0)), "abandoned owner stays closed");
        }
    }

    private static void pinnedLifetime() throws Exception {
        try (Rig r = new Rig()) {
            var recipe = r.pin();
            r.closeSources();
            r.commitAndWait(); // release every original Java wrapper before the worker allocation
            var p = r.own(worker(() -> { try (recipe) { return recipe.prepare(); } }));
            check(Observe.binding(p.handle(), 0).mips == 5 && Observe.binding(p.handle(), 1).mips == 3,
                "native pin survives Java source/view/sampler retirement through worker preparation");
            rejects(() -> worker(recipe::prepare), "closed recipe cannot prepare again");
            check(!r.record(p) && p.ticket() == null, "retained native storage does not bless closed Java draw inputs");
        }
    }

    private static void failedTicket() throws Exception {
        try (Rig r = new Rig(); Geometry g = new Geometry(r)) {
            r.commitAndWait();
            var p = r.prepare(); check(r.record(p), "copies before failed downcall");
            var ticket = p.ticket(); long seq = ticket.sequence();
            MetalCommandEncoder.SubmitFunction real = r.e.submitFunction;
            r.e.submitFunction = (d, i, out) -> { throw new IllegalStateException("injected downcall failure before native submit"); };
            try { rejects(() -> { r.e.submit(); return null; }, "downcall failure escapes"); }
            finally { r.e.submitFunction = real; }
            check(ticket.state() == MetalSubmissionTicket.State.FAILED && r.d.timeline().pendingSeq() == seq,
                "discarded stream marks ticket FAILED without consuming sequence");
            check(!worker(() -> p.publish(g.event)), "failed submission cannot publish");
            r.commitAndWait(); // a real empty commit reuses that sequence
            check(ticket.state() == MetalSubmissionTicket.State.FAILED && !worker(() -> p.publish(g.event)),
                "later successful commit of same value cannot rescue discarded copies");
            var unacknowledged = r.prepare(); check(r.record(unacknowledged), "capture before missing commit acknowledgement");
            r.e.submitFunction = (d, i, out) -> {
                int status = real.submit(d, i, out);
                // Real GPU submission, but inject an absent acknowledgement at the existing boundary.
                long at = out + MtlNative.SUBMIT_RESULT_FLAGS;
                MemoryUtil.memPutInt(at, MemoryUtil.memGetInt(at)
                    & ~MtlNative.SUBMIT_RESULT_FLAG_COMMITTED);
                return status;
            };
            try { r.e.submit(); } finally { r.e.submitFunction = real; }
            check(unacknowledged.ticket().state() == MetalSubmissionTicket.State.FAILED
                && !worker(() -> unacknowledged.publish(g.event)), "OK status without COMMITTED cannot publish");
            MetalFrameThrottle.frameThrottle(); // start the next normal frame before offering capture
            var next = r.prepare(); check(r.record(next), "new sequence has fresh capture");
            r.e.submit();
            rejects(() -> worker(() -> next.publish(0)), "native rejects missing geometry event after actual commit");
            check(!worker(() -> next.publish(g.event)), "failed publication attempt is terminal");
        }
    }

    private static void shutdown() throws Exception {
        try (Rig r = new Rig()) {
            var p = r.prepare(); check(r.record(p), "record before shutdown");
            r.e.shutdown();
            check(p.ticket().state() == MetalSubmissionTicket.State.FAILED, "shutdown fails unsubmitted ticket");
            check(!worker(() -> p.publish(0)), "shutdown offer cannot publish");
        }
    }

    private static void reentrant() throws Exception {
        check(MetalFrameThrottle.ENABLED, "reentrant case requires default late throttle");
        try (Rig r = new Rig(); Geometry g = new Geometry(r)) {
            r.commitAndWait();
            var p = r.prepare();
            long atlasBefore = r.atlas.contentRevision(), lightBefore = r.light.contentRevision();
            boolean[] ran = {false};
            // A completed readback's deferred callback is a supported source of reentrant recording.
            // Tag an already-completed value and arm its guard deterministically; no timing/race oracle.
            r.d.timeline().deferUntil(r.d.timeline().lastCommittedSeq(), () -> { ran[0] = true; r.write(2); });
            r.d.frameThrottle().arm(r.d.timeline().lastCommittedSeq());
            long armed = r.d.frameThrottle().pendingSeq(), seq = r.d.timeline().pendingSeq();
            long main = r.e.mainStream().size(), init = r.e.initStream().size();
            int submits = r.submits, deferred = r.d.timeline().pendingEntries();
            check("throttle-armed".equals(p.recordingDeclineReason(r.av,r.as,r.lv,r.ls)), "preflight reports armed throttle without waiting");
            check(armed != 0 && !p.recordCopies(r.av, r.as, r.lv, r.ls), "armed frame throttle declines material capture");
            check(!ran[0] && r.atlas.contentRevision() == atlasBefore && r.light.contentRevision() == lightBefore,
                "declined capture must not drain the source-writing callback");
            check(r.e.mainStream().size() == main && r.e.initStream().size() == init,
                "decline leaves both command streams untouched");
            check(r.d.frameThrottle().pendingSeq() == armed && r.d.timeline().pendingSeq() == seq
                && r.submits == submits && r.d.timeline().pendingEntries() == deferred,
                "decline leaves throttle armed, callback queued, and submission/deferred ownership untouched");
            check(p.ticket() == null && p.atlasRevision() == 0 && p.lightRevision() == 0,
                "decline exposes no ticket or sampled revisions");
            check(!worker(() -> p.publish(g.event)), "declined capture cannot publish");

            MetalFrameThrottle.frameThrottle(); // only the normal game boundary may drain/wait
            check(ran[0] && r.d.frameThrottle().pendingSeq() == 0 && r.atlas.contentRevision() > atlasBefore
                && r.light.contentRevision() > lightBefore, "normal frame throttle still runs the queued callback");
            check(r.record(p), "same prepared owner can retry after normal frame throttle");
            check(p.atlasRevision() == r.atlas.contentRevision() && p.lightRevision() == r.light.contentRevision(),
                "retry captures post-callback revisions");
            check(p.ticket().state() == MetalSubmissionTicket.State.PENDING && r.submits == submits
                && r.d.timeline().pendingSeq() == seq, "retry records passively without a hidden submit");
            r.e.submit();
            check(worker(() -> p.publish(g.event)), "retried capture reaches actual native publication");
            verifyAll(r, p, 2);
        }
    }

    private static void checkNative() {
        MtlNative.Loader.load();
        List<String> missing = new ArrayList<>();
        for (String name : List.of("mcmtl_ext_far_material_pin", "mcmtl_ext_far_material_prepare",
                "mcmtl_ext_far_material_binding", "mcmtl_ext_far_material_retain_parent", "mcmtl_ext_far_material_copy_count",
                "mcmtl_ext_far_material_copy_job", "mcmtl_ext_far_material_publish", "mcmtl_ext_far_material_drop",
                "mcmtl_ext_far_geometry_pin", "mcmtl_ext_far_geometry_capture", "mcmtl_ext_far_geometry_event",
                "mcmtl_ext_far_geometry_drop", "mcmtl_ext_far_geometry_poll")) {
            try { MtlNative.Loader.address(name); } catch (UnsatisfiedLinkError e) { missing.add(name); }
        }
        System.out.println("Native library: " + MtlNative.Loader.path());
        if (!missing.isEmpty()) throw new IllegalStateException("Parent must supply an updated dylib; missing " + missing);
    }
    public static void main(String[] args) throws Exception {
        try {
            checkNative(); // before any Metal device/GPU work, with an actionable stale-dylib error
            if (args.length == 1 && args[0].equals("check-native")) {
                System.out.println("Native symbols available (no device created)."); return;
            }
            check(MetalFarPrograms.REQUESTED, "requires -Dmc.lod.async=true");
            RenderSystem.initRenderThread();
            int failures = 0;
            List<String> cases = List.of("roundtrip", "stale", "pinned-lifetime", "cancelled", "partial", "failed-ticket", "shutdown", "reentrant");
            String selected = System.getProperty("farMaterials.case");
            check(selected == null || cases.contains(selected), "unknown farMaterials.case: " + selected);
            for (String name : cases) {
                if (selected != null && !selected.equals(name)) continue;
                try {
                    switch (name) {
                        case "roundtrip" -> roundtrip();
                        case "stale" -> stale();
                        case "pinned-lifetime" -> pinnedLifetime();
                        case "cancelled" -> cancelled(false);
                        case "partial" -> cancelled(true);
                        case "failed-ticket" -> failedTicket();
                        case "shutdown" -> shutdown();
                        case "reentrant" -> reentrant();
                        default -> throw new AssertionError(name);
                    }
                    System.out.println("PASS " + name);
                } catch (Exception | AssertionError e) { failures++; System.err.println("FAIL " + name); e.printStackTrace(); }
            }
            System.out.println("MetalFarMaterialsTest: " + checks + " checks, " + failures + " failing cases");
            if (failures != 0) throw new AssertionError("Material integration failures: " + failures);
        } finally { WORKER.shutdownNow(); }
    }
}
