package net.minecraft.client.renderer.chunk;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDevice;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import net.minecraft.TracingExecutor;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection.SectionTask;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;

/** CPU-only regression for authoritative EMPTY. Runs real CompileTask.doTask and publication callbacks with a paused
 * compiler. Only the dispatcher's GPU-owning constructor is bypassed; no renderer, window, world or GPU is started. */
public final class SectionEmptyPublicationTest {
    private static final String PROPERTY = "mc.lod.nearHandoff";
    private static final long NODE = SectionPos.asLong(13, 4, -4);
    private static final long OTHER_NODE = SectionPos.asLong(-23, 5, 17);
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object blank(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        return unsafe.getMethod("allocateInstance", Class.class).invoke(field(unsafe, "theUnsafe").get(null), type);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        field(owner.getClass(), name).set(owner, value);
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("compile barrier timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void eventually(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
        check(condition.getAsBoolean(), message);
    }

    private static final class Step {
        final SectionCompiler.Results results = new SectionCompiler.Results();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch resume;
        volatile long compiledForNode;

        Step(boolean paused, boolean blockEntities) {
            resume = new CountDownLatch(paused ? 1 : 0);
            // A list marker is sufficient: neither compilation publication nor this test renders block entities.
            if (blockEntities) results.blockEntities.add(null);
        }
    }

    private static final class Compiler extends SectionCompiler {
        final ConcurrentLinkedQueue<Step> steps = new ConcurrentLinkedQueue<>();

        Compiler() { super(false, false, null, null, null); }

        Step next(boolean paused, boolean blockEntities) {
            Step step = new Step(paused, blockEntities);
            steps.add(step);
            return step;
        }

        @Override
        public Results compile(SectionPos pos, RenderSectionRegion region, VertexSorting sorting, SectionBufferBuilderPack buffers) {
            Step step = steps.remove();
            step.compiledForNode = pos.asLong();
            step.entered.countDown();
            await(step.resume);
            return step.results;
        }
    }

    private static final class Worker {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread;

        Worker(Runnable run) {
            thread = new Thread(() -> {
                try { run.run(); } catch (Throwable t) { failure.set(t); }
            }, "empty-publication-test");
            thread.setDaemon(true);
            thread.start();
        }

        void finish() throws Exception {
            thread.join(5000);
            check(!thread.isAlive(), "worker completed without deadlock");
            if (failure.get() != null) throw new AssertionError("worker failed", failure.get());
        }
    }

    private static final class Fixture {
        final ReentrantLock lock = new ReentrantLock();
        final Compiler compiler = new Compiler();
        final SectionTaskDynamicQueue queue = new SectionTaskDynamicQueue();
        final AtomicInteger notifications = new AtomicInteger();
        final SectionRenderDispatcher dispatcher;
        final RenderSection section;
        volatile boolean unlockedPublication;
        volatile boolean monitoredPublication;

        Fixture(boolean budgeted) throws Exception {
            dispatcher = (SectionRenderDispatcher)blank(SectionRenderDispatcher.class);
            set(dispatcher, "copyLock", lock);
            set(dispatcher, "queue", queue);
            set(dispatcher, "meshSeqCounter", new AtomicLong());
            set(dispatcher, "cameraPosition", new AtomicReference<>(Vec3.ZERO));
            set(dispatcher, "chunkUberBuffers", Map.of());
            set(dispatcher, "sectionCompiler", compiler);
            set(dispatcher, "uploadBudget", budgeted ? new UberGpuBuffer.UploadBudget(1024, 2) : null);
            set(dispatcher, "onSectionMeshUpdate", (Consumer<RenderSection>)s -> {
                unlockedPublication |= !lock.isHeldByCurrentThread();
                monitoredPublication |= Thread.holdsLock(s);
                notifications.incrementAndGet();
            });
            section = dispatcher.new RenderSection(0, NODE);
        }

        SectionTask task() throws Exception {
            return (SectionTask)method(RenderSection.class, "createCompileTask", RenderSectionRegion.class).invoke(section, (Object)null);
        }

        void queued(Worker worker) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!lock.hasQueuedThread(worker.thread) && worker.thread.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            check(lock.hasQueuedThread(worker.thread), "operation must wait for copyLock");
        }

        long applied() throws Exception { return field(RenderSection.class, "appliedMeshSeq").getLong(section); }
        long sequence(SectionTask task) throws Exception { return field(task.getClass(), "meshSeq").getLong(task); }

        void upload(CompiledSectionMesh mesh, long node, long sequence) throws Exception {
            lock.lock();
            try {
                method(RenderSection.class, "checkSectionMesh", CompiledSectionMesh.class, long.class, long.class)
                    .invoke(section, mesh, node, sequence);
            } finally { lock.unlock(); }
        }
    }

    private static final class TrackedMesh implements SectionMesh {
        final ReentrantLock lock;
        int closes;
        boolean unlockedClose;

        TrackedMesh(ReentrantLock lock) { this.lock = lock; }
        @Override public boolean facesCanSeeEachother(Direction a, Direction b) { return false; }
        @Override public void close() { closes++; unlockedClose |= !lock.isHeldByCurrentThread(); }
    }

    private static final class UploadedMesh extends CompiledSectionMesh {
        final ReentrantLock lock;
        int closes;

        UploadedMesh(ReentrantLock lock) {
            super(TranslucencyPointOfView.of(Vec3.ZERO, NODE), new SectionCompiler.Results(), 1L);
            this.lock = lock;
        }

        @Override public void close() {
            check(lock.isHeldByCurrentThread(), "stale uploaded mesh released under copyLock");
            closes++;
            super.close();
        }
    }

    private static void fresh(boolean budgeted, boolean blockEntities) throws Exception {
        boolean guarded = Boolean.getBoolean(PROPERTY) || budgeted;
        Fixture f = new Fixture(budgeted);
        TrackedMesh previous = new TrackedMesh(f.lock);
        f.section.sectionMesh.set(previous);
        Step step = f.compiler.next(false, blockEntities);
        f.section.compileSync(null);
        SectionMesh published = f.section.getSectionMesh();
        check(blockEntities ? published instanceof CompiledSectionMesh : published == CompiledSectionMesh.EMPTY,
            "fresh no-upload result publishes EMPTY or block-entity-only mesh");
        if (blockEntities) check(published.getRenderableBlockEntities() == step.results.blockEntities, "block entities retained");
        check(f.notifications.get() == 1 && f.unlockedPublication != guarded, "fresh publication uses expected lock semantics");
        check(!f.monitoredPublication, "publication callback does not hold the statistics monitor");
        check(previous.closes == 1 && !previous.unlockedClose, "replaced mesh released once under copyLock");
        check(guarded ? f.applied() > 0 : f.applied() == 0, "fresh publication uses expected compile sequence");
        long time = field(RenderSection.class, "uploadedTime").getLong(f.section);
        check(time > 0, "fresh publication sets normal upload time");
        f.compiler.next(false, false);
        f.section.compileSync(null);
        check(field(RenderSection.class, "uploadedTime").getLong(f.section) == time, "recompile preserves existing fade time");
    }

    private static void publicationWaitsForLock(boolean budgeted) throws Exception {
        boolean guarded = Boolean.getBoolean(PROPERTY) || budgeted;
        Fixture f = new Fixture(budgeted);
        SectionTask task = f.task();
        f.compiler.next(false, false);
        f.lock.lock();
        Worker worker = new Worker(() -> task.doTask(null));
        try {
            f.queued(worker);
            if (guarded) {
                check(f.section.getSectionMesh() == CompiledSectionMesh.UNCOMPILED && f.notifications.get() == 0,
                    "EMPTY must not be exposed before copyLock acquisition");
            } else {
                check(f.section.getSectionMesh() == CompiledSectionMesh.EMPTY && f.notifications.get() == 1 && f.unlockedPublication,
                    "OFF preserves predecessor nonbudgeted publication before copyLock");
            }
        } finally {
            f.lock.unlock();
            worker.finish();
        }
        check(f.section.getSectionMesh() == CompiledSectionMesh.EMPTY && f.unlockedPublication != guarded,
            "fresh worker uses expected publication lock semantics");
    }

    /** All mutations are made after the old compiler read its coordinates and before it returns EMPTY. */
    private static void staleCompile(boolean budgeted, String mutation, boolean blockEntities) throws Exception {
        boolean armed = Boolean.getBoolean(PROPERTY);
        Fixture f = new Fixture(budgeted);
        SectionTask task = f.task();
        Step old = f.compiler.next(true, blockEntities);
        AtomicReference<SectionTaskResult> outcome = new AtomicReference<>();
        Worker worker = new Worker(() -> outcome.set(task.doTask(null)));
        try {
            await(old.entered);
            check(old.compiledForNode == NODE, "old compile captured original coordinates");
            switch (mutation) {
                case "cancel" -> task.cancel();
                case "reset" -> f.section.reset();
                case "move" -> f.section.setSectionNode(OTHER_NODE);
                case "return" -> { f.section.setSectionNode(OTHER_NODE); f.section.setSectionNode(NODE); }
                case "pending" -> f.task();
                case "newer" -> { f.compiler.next(false, true); f.section.compileSync(null); }
                default -> throw new AssertionError(mutation);
            }
            SectionMesh expected = f.section.getSectionMesh();
            int notifications = f.notifications.get();
            long applied = f.applied();
            old.resume.countDown();
            worker.finish();
            if (armed || budgeted && (mutation.equals("move") || mutation.equals("newer"))) {
                check(f.section.getSectionMesh() == expected && f.notifications.get() == notifications && f.applied() == applied,
                    "stale no-upload result rejected after " + mutation);
            } else {
                SectionMesh published = f.section.getSectionMesh();
                check(blockEntities ? published instanceof CompiledSectionMesh && published.getRenderableBlockEntities() == old.results.blockEntities
                                    : published == CompiledSectionMesh.EMPTY,
                    "OFF preserves predecessor stale publication after " + mutation);
                check(f.notifications.get() == notifications + 1 && f.applied() == (budgeted ? f.sequence(task) : applied),
                    "OFF preserves predecessor stale notification/sequence after " + mutation);
            }
            check(outcome.get() == (armed ? SectionTaskResult.CANCELLED : SectionTaskResult.SUCCESSFUL),
                "expected compile result after " + mutation);
            if (mutation.equals("move")) {
                check(f.section.getSectionNode() == OTHER_NODE && f.section.getRenderOrigin().equals(SectionPos.of(OTHER_NODE).origin()),
                    "rotated section retains matching node and origin");
            }
        } finally {
            old.resume.countDown();
            worker.finish();
        }
    }

    /** Cancellation must be checked again after waiting for copyLock, not merely after compile(). */
    private static void overtakenAtLock(boolean budgeted) throws Exception {
        Fixture f = new Fixture(budgeted);
        SectionTask old = f.task();
        f.compiler.next(false, false);
        f.lock.lock();
        Worker worker = new Worker(() -> old.doTask(null));
        SectionMesh newer;
        try {
            f.queued(worker);
            f.compiler.next(false, true);
            f.section.compileSync(null);
            newer = f.section.getSectionMesh();
        } finally {
            f.lock.unlock();
            worker.finish();
        }
        check(f.section.getSectionMesh() == newer && f.notifications.get() == 1,
            "EMPTY waiting for copyLock cannot replace newer compile");
    }

    private static void mutationWaitsForLock(boolean budgeted, String mutation) throws Exception {
        boolean armed = Boolean.getBoolean(PROPERTY);
        Fixture f = new Fixture(budgeted);
        SectionTask task = f.task();
        f.section.sectionMesh.set(CompiledSectionMesh.EMPTY);
        f.lock.lock();
        Worker worker = new Worker(() -> {
            switch (mutation) {
                case "cancel" -> task.cancel();
                case "reset" -> f.section.reset();
                case "move" -> f.section.setSectionNode(OTHER_NODE);
                default -> throw new AssertionError(mutation);
            }
        });
        try {
            if (!armed && mutation.equals("cancel")) {
                worker.finish();
                check(task.doTask(null) == SectionTaskResult.CANCELLED, "OFF cancellation completes without copyLock");
            } else {
                f.queued(worker);
                check(f.section.getSectionMesh() == (armed ? CompiledSectionMesh.EMPTY : CompiledSectionMesh.UNCOMPILED)
                        && f.section.getSectionNode() == NODE,
                    "expected mesh and coordinates while mutation waits for copyLock: " + mutation);
            }
        } finally {
            f.lock.unlock();
            worker.finish();
        }
    }

    /** Exercise coordinate and sequence guards independently of cancellation, and completed upload ordering. */
    private static void sequenceAndCoordinates(boolean budgeted) throws Exception {
        for (String reason : new String[]{"coordinates", "sequence"}) {
            Fixture f = new Fixture(budgeted);
            SectionTask task = f.task();
            TrackedMesh rejected = new TrackedMesh(f.lock);
            if (reason.equals("sequence")) set(f.section, "appliedMeshSeq", f.sequence(task) + 1);
            long applied = f.applied();
            method(task.getClass(), "setEmptyBudgeted", SectionMesh.class, long.class)
                .invoke(task, rejected, reason.equals("coordinates") ? OTHER_NODE : NODE);
            check(f.section.getSectionMesh() == CompiledSectionMesh.UNCOMPILED && f.notifications.get() == 0 && f.applied() == applied,
                "no-upload guard independently rejects " + reason);
            check(rejected.closes == 1 && !rejected.unlockedClose, "rejected no-upload payload released once under copyLock");
        }

        Fixture f = new Fixture(budgeted);
        SectionTask old = f.task();
        long oldSeq = f.sequence(old);
        f.compiler.next(false, false);
        f.section.compileSync(null);
        check(f.applied() > oldSeq && oldSeq > 0, "sequence advances for EMPTY in both modes");
        UploadedMesh late = new UploadedMesh(f.lock);
        f.upload(late, NODE, oldSeq);
        check(f.section.getSectionMesh() == CompiledSectionMesh.EMPTY && late.closes == 1 && f.notifications.get() == 1,
            "older staged upload cannot overwrite authoritative EMPTY");

        // A newer fully uploaded mesh can replace EMPTY as usual.
        SectionTask newer = f.task();
        long newerSeq = f.sequence(newer);
        UploadedMesh fresh = new UploadedMesh(f.lock);
        f.upload(fresh, NODE, newerSeq);
        check(f.section.getSectionMesh() == fresh && fresh.closes == 0 && f.notifications.get() == 2,
            "newer completed upload replaces EMPTY normally");

        // Coordinates alone cannot detect a section moving away and back while an upload is carried.
        f.section.setSectionNode(OTHER_NODE);
        f.section.setSectionNode(NODE);
        UploadedMesh recycled = new UploadedMesh(f.lock);
        f.upload(recycled, NODE, newerSeq);
        check(f.section.getSectionMesh() == CompiledSectionMesh.UNCOMPILED && recycled.closes == 1,
            "reset sequence prevents old upload resurrection at reused coordinates");

        SectionTask cancelled = f.task();
        cancelled.cancel();
        TrackedMesh rejected = new TrackedMesh(f.lock);
        method(cancelled.getClass(), "setEmptyBudgeted", SectionMesh.class, long.class).invoke(cancelled, rejected, NODE);
        check(f.section.getSectionMesh() == CompiledSectionMesh.UNCOMPILED && rejected.closes == 1 && !rejected.unlockedClose,
            "cancelled no-upload payload released once under copyLock");
    }

    /** Explicit OFF contract, runnable against the predecessor too: zero nonbudget sequences, no reset barrier,
     * lock-free task replacement, and the existing budget-only coordinate/sequence checks. */
    private static void legacyOrdering(boolean budgeted) throws Exception {
        Fixture f = new Fixture(budgeted);
        check(f.applied() == 0, "OFF constructor does not advance applied sequence");
        SectionTask first = f.task();
        check(f.sequence(first) == (budgeted ? 1 : 0), "OFF predecessor first task sequence");
        AtomicReference<SectionTask> replacement = new AtomicReference<>();
        f.lock.lock();
        Worker worker = new Worker(() -> {
            try { replacement.set(f.task()); } catch (Exception e) { throw new AssertionError(e); }
        });
        try { worker.finish(); } finally { f.lock.unlock(); }
        check(f.sequence(replacement.get()) == (budgeted ? 2 : 0), "OFF task replacement does not need copyLock");
        check(first.doTask(null) == SectionTaskResult.CANCELLED, "OFF replacement cancels previous task");
        set(f.section, "appliedMeshSeq", 17L);
        f.section.reset();
        f.section.setSectionNode(OTHER_NODE);
        f.section.setSectionNode(NODE);
        check(f.applied() == 17 && f.sequence(f.task()) == (budgeted ? 3 : 0), "OFF reset/movement preserve predecessor sequences");

        for (String reason : new String[]{"coordinates", "sequence"}) {
            Fixture g = new Fixture(budgeted);
            g.section.sectionMesh.set(CompiledSectionMesh.EMPTY);
            set(g.section, "appliedMeshSeq", 10L);
            UploadedMesh mesh = new UploadedMesh(g.lock);
            g.upload(mesh, reason.equals("coordinates") ? OTHER_NODE : NODE, reason.equals("sequence") ? 9L : 11L);
            check(budgeted ? g.section.getSectionMesh() == CompiledSectionMesh.EMPTY && mesh.closes == 1 && g.notifications.get() == 0
                           : g.section.getSectionMesh() == mesh && mesh.closes == 0 && g.notifications.get() == 1,
                "OFF retains budget-only uploaded mesh guard: " + reason);
            check(g.applied() == 10, "OFF rejected budget upload / unguarded nonbudget upload leaves sequence unchanged");
        }
    }

    private static void startup(boolean expected) throws Exception {
        Field flag = field(SectionRenderDispatcher.class, "NEAR_HANDOFF_STARTUP");
        check(Modifier.isStatic(flag.getModifiers()) && Modifier.isFinal(flag.getModifiers()), "startup arm is static final");
        check(flag.getBoolean(null) == expected, "startup arm matches explicit property/default");
        String original = System.getProperty(PROPERTY);
        try {
            System.setProperty(PROPERTY, Boolean.toString(!expected));
            Fixture f = new Fixture(false);
            check(flag.getBoolean(null) == expected, "startup arm cannot change in-session");
            check((f.sequence(f.task()) > 0) == expected, "in-session property change cannot arm/disarm prerequisite");
        } finally {
            if (original == null) System.clearProperty(PROPERTY); else System.setProperty(PROPERTY, original);
        }
    }

    private static boolean ready(Fixture f) {
        f.lock.lock();
        try { return f.section.handoffEmptyReady(); } finally { f.lock.unlock(); }
    }

    private static void emptyProvenance(boolean budgeted) throws Exception {
        boolean armed = Boolean.getBoolean(PROPERTY);
        Fixture f = new Fixture(budgeted);
        long generation = f.section.handoffGeneration();
        check(!ready(f), "UNCOMPILED has no EMPTY provenance");
        check(f.section.sectionMesh.compareAndSet(CompiledSectionMesh.UNCOMPILED, CompiledSectionMesh.EMPTY), "graph-style initial CAS succeeds");
        check(!ready(f), "graph-style EMPTY alone is never authoritative");
        AtomicReference<Boolean> callbackReady = new AtomicReference<>();
        set(f.dispatcher, "onSectionMeshUpdate", (Consumer<RenderSection>)s -> callbackReady.set(s.handoffEmptyReady()));
        f.compiler.next(false, false);
        f.section.compileSync(null);
        check(ready(f) == armed && callbackReady.get() == armed, "safe EMPTY marked before publication callback, only when armed");
        check(f.section.handoffGeneration() == generation, "ordinary EMPTY publication preserves handoff generation");
        f.section.reset();
        check(armed ? f.section.handoffGeneration() > generation : f.section.handoffGeneration() == generation,
            "reset advances handoff generation only when armed");
        generation = f.section.handoffGeneration();
        check(!ready(f), "reset clears EMPTY provenance");
        check(f.section.sectionMesh.compareAndSet(CompiledSectionMesh.UNCOMPILED, CompiledSectionMesh.EMPTY), "graph-style CAS after reset succeeds");
        check(!ready(f), "graph-style CAS after reset cannot inherit old EMPTY provenance");

        f.compiler.next(false, false);
        f.section.compileSync(null);
        f.compiler.next(false, true);
        f.section.compileSync(null);
        check(!ready(f) && field(RenderSection.class, "handoffEmptySeq").getLong(f.section) == 0,
            "block-entity-only replacement clears EMPTY marker");
        f.compiler.next(false, false);
        f.section.compileSync(null);
        SectionTask newer = f.task();
        f.upload(new UploadedMesh(f.lock), NODE, f.sequence(newer));
        check(f.section.handoffGeneration() == generation, "ordinary uploaded replacement preserves handoff generation");
        check(!ready(f) && field(RenderSection.class, "handoffEmptySeq").getLong(f.section) == 0,
            "uploaded nonempty replacement clears EMPTY marker");

        f.compiler.next(false, false);
        f.section.compileSync(null);
        for (long node : new long[]{OTHER_NODE, NODE}) {
            f.section.setSectionNode(node);
            check(armed ? f.section.handoffGeneration() > generation : f.section.handoffGeneration() == generation,
                "coordinate rotation advances handoff generation only when armed");
            generation = f.section.handoffGeneration();
            check(!ready(f), "rotating coordinates clears EMPTY provenance");
            check(f.section.sectionMesh.compareAndSet(CompiledSectionMesh.UNCOMPILED, CompiledSectionMesh.EMPTY), "graph CAS at rotated coordinates succeeds");
            check(!ready(f), "graph CAS at rotated/reused coordinates is not authoritative");
            Step step = f.compiler.next(false, false);
            f.section.compileSync(null);
            check(step.compiledForNode == node && ready(f) == armed, "safe EMPTY for current rotated coordinates is authoritative only ON");
        }

        if (armed) {
            SectionTask stale = f.task();
            Step step = f.compiler.next(true, false);
            Worker worker = new Worker(() -> stale.doTask(null));
            try {
                await(step.entered);
                f.section.setSectionNode(OTHER_NODE);
                f.section.setSectionNode(NODE);
                check(f.section.sectionMesh.compareAndSet(CompiledSectionMesh.UNCOMPILED, CompiledSectionMesh.EMPTY), "graph wins CAS in reused generation");
                step.resume.countDown();
                worker.finish();
                check(!ready(f), "stale compile cannot certify graph EMPTY after coordinate reuse");
            } finally { step.resume.countDown(); worker.finish(); }
        }
    }

    private static void queueCancellationLockOrder() throws Exception {
        Fixture f = new Fixture(false);
        SectionTask task = f.task();
        f.queue.add(task);
        f.lock.lock();
        Worker clear = new Worker(f.dispatcher::clearCompileQueue);
        Worker enqueue = null;
        try {
            f.queued(clear);
            enqueue = new Worker(() -> f.queue.add(task));
            enqueue.finish();
            check(f.queue.size() == 2, "queue clearing waits for copyLock without holding queue monitor");
        } finally {
            f.lock.unlock();
            clear.finish();
            if (enqueue != null) enqueue.finish();
        }
        check(f.dispatcher.isQueueEmpty() && task.doTask(null) == SectionTaskResult.CANCELLED, "queued tasks cancelled and drained without inversion");
    }

    private static void statsResetLockOrder() throws Exception {
        check(ChunkStats.ENABLED, "statistics lock-order test requires mc.chunkStats=true");
        Fixture f = new Fixture(false);
        SectionTask task = f.task();
        f.compiler.next(false, false);
        Worker reset;
        Worker compile;
        synchronized (f.section) {
            reset = new Worker(f.section::reset);
            eventually(f.lock::isLocked, "reset acquired copyLock before waiting on statistics monitor");
            compile = new Worker(() -> task.doTask(null));
            eventually(() -> compile.thread.getState() == Thread.State.BLOCKED, "compile reaches statistics monitor while reset holds copyLock");
        }
        reset.finish();
        compile.finish();
        check(f.section.getSectionMesh() == CompiledSectionMesh.UNCOMPILED, "stats releases section monitor before waiting for publication lock");
    }

    /** Reuse the existing fake-GPU fixtures without editing their shared tests. The mesh has compileTaskStartTime=0
     * and no CompileTask: the private four-argument upload entry must supply a current sequence itself. */
    private static void fourArgumentUploads() throws Exception {
        Class<?> suite = Class.forName("com.mojang.renderpearl.backend.metal.TravelHitchTest");
        DeviceInfo info = (DeviceInfo)method(suite, "fakeInfo").invoke(null);
        Class<?> gpuType = Class.forName(suite.getName() + "$FakeGpu");
        var gpuConstructor = gpuType.getDeclaredConstructor(DeviceInfo.class);
        gpuConstructor.setAccessible(true);
        Object gpu = gpuConstructor.newInstance(info);
        Field deviceField = field(RenderSystem.class, "DEVICE");
        Object previousDevice = deviceField.get(null);
        deviceField.set(null, (GpuDevice)field(gpuType, "device").get(gpu));
        Class<?> compilerType = Class.forName(suite.getName() + "$StubCompiler");
        var compilerConstructor = compilerType.getDeclaredConstructor();
        compilerConstructor.setAccessible(true);
        SectionCompiler compiler = (SectionCompiler)compilerConstructor.newInstance();
        try (var executor = Executors.newSingleThreadExecutor()) {
            for (boolean budgeted : new boolean[]{false, true}) {
                SectionRenderDispatcher dispatcher = new SectionRenderDispatcher(new TracingExecutor(executor),
                    (RenderBuffers)blank(RenderBuffers.class), compiler, section -> {});
                try {
                    UberGpuBuffer.UploadBudget budget = budgeted ? new UberGpuBuffer.UploadBudget(1024, 2) : null;
                    set(dispatcher, "uploadBudget", budget);
                    for (Object buffers : ((Map<?, ?>)field(SectionRenderDispatcher.class, "chunkUberBuffers").get(dispatcher)).values()) {
                        for (String kind : new String[]{"vertexBuffer", "indexBuffer"}) {
                            ((UberGpuBuffer<?>)method(buffers.getClass(), kind).invoke(buffers)).setUploadBudget(budget);
                        }
                    }
                    RenderSection section = dispatcher.new RenderSection(0, NODE);
                    for (int phase = 0; phase < 3; phase++) {
                        if (phase == 1) section.reset();
                        if (phase == 2) {
                            field(compilerType, "solidQuads").setInt(compiler, 0);
                            section.compileSync(null);
                        }
                        long before = field(RenderSection.class, "appliedMeshSeq").getLong(section);
                        field(compilerType, "solidQuads").setInt(compiler, 1);
                        SectionCompiler.Results results = compiler.compile(SectionPos.of(NODE), null, VertexSorting.byDistance(0, 0, 0), null);
                        CompiledSectionMesh mesh = new CompiledSectionMesh(TranslucencyPointOfView.of(Vec3.ZERO, NODE), results, 0L);
                        MeshData data = results.renderedLayers.get(ChunkSectionLayer.SOLID);
                        check((boolean)method(RenderSection.class, "addSectionBuffersToUberBuffer", ChunkSectionLayer.class,
                                CompiledSectionMesh.class, ByteBuffer.class, ByteBuffer.class)
                            .invoke(section, ChunkSectionLayer.SOLID, mesh, data.vertexBuffer(), data.indexBuffer()), "four-argument upload stages real geometry");
                        data.close();
                        dispatcher.lock();
                        try {
                            for (int pass = 0; pass < 6; pass++) dispatcher.uploadTerrainBuffersToGpu();
                            check(section.getSectionMesh() == mesh && mesh.isVertexBufferUploaded(ChunkSectionLayer.SOLID)
                                && mesh.isIndexBufferUploaded(ChunkSectionLayer.SOLID), "four-argument upload publishes after constructor/reset/EMPTY");
                            long applied = field(RenderSection.class, "appliedMeshSeq").getLong(section);
                            check(Boolean.getBoolean(PROPERTY) || budgeted ? applied > before : applied == 0,
                                "four-argument overload uses nextMeshSeq with startup ON or budget, zero otherwise");
                            check(!section.handoffEmptyReady(), "four-argument nonempty upload clears EMPTY provenance");
                        } finally { dispatcher.unlock(); }
                    }
                    section.reset();
                } finally { dispatcher.dispose(); }
            }
        } finally {
            method(compilerType, "freeBuilders").invoke(compiler);
            deviceField.set(null, previousDevice);
        }
    }

    public static void main(String[] args) throws Exception {
        String suite = args.length == 0 ? "all" : args[0];
        if (suite.equals("provenance")) {
            for (boolean budgeted : new boolean[]{false, true}) emptyProvenance(budgeted);
            System.out.println("SectionEmptyPublicationTest: " + checks + " provenance checks passed (armed=" + Boolean.getBoolean(PROPERTY) + ")");
            return;
        }
        if (suite.equals("compat")) {
            RenderSystem.initRenderThread();
            fourArgumentUploads();
            Class<?> budgetSuite = Class.forName("com.mojang.renderpearl.backend.metal.TravelHitchTest");
            method(budgetSuite, "budgetSectionOrdering").invoke(null);
            check(field(budgetSuite, "failures").getInt(null) == 0, "existing 450-trial budget ordering test passes");
            System.out.println("SectionEmptyPublicationTest: " + checks + " four-argument/budget compatibility checks passed (armed=" + Boolean.getBoolean(PROPERTY) + ")");
            return;
        }
        if (suite.equals("startup")) {
            startup(Boolean.parseBoolean(args[1]));
            System.out.println("SectionEmptyPublicationTest: " + checks + " startup checks passed (armed=" + args[1] + ")");
            return;
        }
        boolean legacy = suite.equals("legacy");
        check(Boolean.getBoolean(PROPERTY) != legacy, "safe suites require explicit -Dmc.lod.nearHandoff=true; legacy requires OFF");
        if (suite.equals("all")) {
            queueCancellationLockOrder();
            if (ChunkStats.ENABLED) statsResetLockOrder();
        }
        for (boolean budgeted : new boolean[]{false, true}) {
            if (legacy || suite.equals("all") || suite.equals("lock")) {
                publicationWaitsForLock(budgeted);
                for (String mutation : new String[]{"cancel", "reset", "move"}) mutationWaitsForLock(budgeted, mutation);
                fresh(budgeted, false);
                fresh(budgeted, true);
            }
            if (legacy || suite.equals("all") || suite.equals("races")) {
                for (String mutation : new String[]{"cancel", "reset", "move", "return", "pending", "newer"}) {
                    staleCompile(budgeted, mutation, false);
                    staleCompile(budgeted, mutation, true);
                }
                if (!legacy) overtakenAtLock(budgeted);
            }
            if (legacy) legacyOrdering(budgeted);
            if (suite.equals("all") || suite.equals("sequence")) sequenceAndCoordinates(budgeted);
        }
        System.out.println("SectionEmptyPublicationTest: " + checks + " checks passed (" + suite + ", both upload modes)");
    }
}
