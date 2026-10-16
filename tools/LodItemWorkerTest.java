package net.minecraft.client.renderer.chunk;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** Isolated CPU lifecycle checks. Latches hold builds open; no timing sleeps, GPU, or renderer dependencies. */
public final class LodItemWorkerTest {
    private static final String WORKER_NAME = "lod-item-prepare";
    private static final int WAIT_SECONDS = 5;
    private static final AtomicInteger CHECKS = new AtomicInteger();

    private static void check(boolean condition, String message) {
        CHECKS.incrementAndGet();
        if (!condition) throw new AssertionError(message);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) throw new AssertionError("latch deadline");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static <T> FutureTask<T> control(Callable<T> call) {
        FutureTask<T> task = new FutureTask<>(call);
        Thread thread = new Thread(task, "lod-item-test-control");
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    private static <T> T bounded(Callable<T> call) throws Exception {
        return control(call).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static Thread workerThread() {
        List<Thread> matches = Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.getName().equals(WORKER_NAME) && thread.isAlive()).toList();
        check(matches.size() == 1, "exactly one worker thread");
        Thread thread = matches.getFirst();
        check(thread.isDaemon() && thread != Thread.currentThread(), "preparation uses a separate daemon");
        return thread;
    }

    private static void stopped(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        check(!thread.isAlive(), "closed worker exits within bound");
    }

    /** Called after the builder's final latch, so WAITING means publication finished, without consuming poll(). */
    private static void published(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (thread.getState() != Thread.State.WAITING) {
            if (!thread.isAlive() || System.nanoTime() - deadline >= 0) throw new AssertionError("publication deadline");
            LockSupport.parkNanos(100_000L);
        }
    }

    private static <Q, R> LodItemWorker.Completed<Q, R> completed(LodItemWorker<Q, R> worker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        LodItemWorker.Completed<Q, R> result;
        while ((result = worker.poll()) == null) {
            if (System.nanoTime() - deadline >= 0) throw new AssertionError("result deadline");
            LockSupport.parkNanos(100_000L);
        }
        return result;
    }

    private static void queuedClose() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var worker = new LodItemWorker<Object, Object>(WORKER_NAME, request -> {
            calls.incrementAndGet();
            return request;
        });
        Thread thread = workerThread();
        bounded(() -> {
            // Keep the daemon from claiming the request until queued-close assertions finish.
            synchronized (worker) {
                check(!worker.busy() && worker.poll() == null, "new worker is empty");
                try {
                    worker.offer(null);
                    throw new AssertionError("null request accepted");
                } catch (NullPointerException expected) {
                    check(!worker.busy(), "invalid request leaves slot empty");
                }
                check(worker.offer(new Object()), "first queued request accepted");
                check(worker.busy() && worker.poll() == null, "queued request occupies slot without a result");
                check(!worker.offer(new Object()), "queued request cannot be replaced");
                worker.close();
                worker.close();
                check(!worker.busy() && worker.poll() == null, "close clears queued work");
                check(!worker.offer(new Object()), "close permanently revokes admission");
            }
            return null;
        });
        stopped(thread);
        check(calls.get() == 0, "queued request never invokes builder after close");
    }

    private static void runningAndResultAdmission() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), returning = new CountDownLatch(1);
        AtomicReference<LodItemWorker<Object, Object>> owner = new AtomicReference<>();
        AtomicReference<Thread> builderThread = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        Object first = new Object(), second = new Object(), payload = new Object();
        var worker = new LodItemWorker<Object, Object>(WORKER_NAME, request -> {
            calls.incrementAndGet();
            builderThread.set(Thread.currentThread());
            entered.countDown();
            try {
                check(!Thread.holdsLock(owner.get()), "builder runs outside worker monitor");
                await(release);
                return payload;
            } finally {
                returning.countDown();
            }
        });
        owner.set(worker);
        Thread thread = workerThread();
        try {
            check(worker.offer(first), "offer starts preparation");
            await(entered);
            bounded(() -> {
                check(worker.busy(), "running builder occupies slot");
                check(worker.poll() == null, "poll does not wait for running builder");
                check(!worker.offer(second), "no queue behind running builder");
                check(returning.getCount() == 1, "public calls return while builder remains gated");
                return null;
            });
            release.countDown();
            await(returning);
            published(thread);
            check(worker.busy(), "unclaimed result remains busy");
            check(!worker.offer(second), "unclaimed result blocks admission");
            var result = worker.poll();
            check(result != null && result.request() == first && result.result() == payload && result.failure() == null,
                "poll transfers exact request and result identities");
            check(!worker.busy() && worker.poll() == null, "result is consumed exactly once");
            check(worker.offer(second), "poll reopens the single slot");
            result = completed(worker);
            check(result.request() == second && result.result() == payload && result.failure() == null,
                "same daemon serves subsequent request");
            check(calls.get() == 2 && builderThread.get() == thread, "exactly two builds on the one daemon");
        } finally {
            release.countDown();
            worker.close();
            stopped(thread);
        }
    }

    private static void failureAndRecovery() throws Exception {
        var worker = new LodItemWorker<Object, Object>(WORKER_NAME, request -> {
            if (request instanceof RuntimeException failure) throw failure;
            if (request instanceof Error failure) throw failure;
            return null;
        });
        Thread thread = workerThread();
        try {
            for (Throwable failure : List.of(new IllegalStateException("expected exception"), new AssertionError("expected error"))) {
                check(worker.offer(failure), "failure request accepted");
                var result = completed(worker);
                check(result.request() == failure && result.result() == null && result.failure() == failure,
                    "Throwable is reported intact as completed failure");
                check(!worker.busy(), "poll releases failed request slot");
            }
            Object request = new Object();
            check(worker.offer(request), "worker survives exception and Error");
            var result = completed(worker);
            check(result.request() == request && result.result() == null && result.failure() == null,
                "successful null result is distinguishable from empty poll and failure");
        } finally {
            worker.close();
            stopped(thread);
        }
    }

    private static void closeDuringBuild(boolean fail) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), returning = new CountDownLatch(1);
        var worker = new LodItemWorker<Object, Object>(WORKER_NAME, request -> {
            entered.countDown();
            try {
                await(release);
                if (fail) throw new AssertionError("late failure");
                return new Object();
            } finally {
                returning.countDown();
            }
        });
        Thread thread = workerThread();
        try {
            check(worker.offer(new Object()), "offer before close race");
            await(entered);
            bounded(() -> {
                worker.close();
                worker.close();
                check(returning.getCount() == 1, "close returns without waiting for or interrupting builder");
                check(worker.busy(), "busy still reports actual in-flight CPU work after close");
                check(worker.poll() == null && !worker.offer(new Object()), "closed worker revokes results and new offers");
                return null;
            });
        } finally {
            release.countDown();
            worker.close();
            stopped(thread);
        }
        check(!worker.busy() && worker.poll() == null, "late success/failure cannot resurrect completed state");
        check(!worker.offer(new Object()), "finished closed worker cannot restart");
    }

    private static void concurrentOffers() throws Exception {
        CyclicBarrier start = new CyclicBarrier(3);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var worker = new LodItemWorker<Object, Object>(WORKER_NAME, request -> {
            calls.incrementAndGet();
            await(release);
            return request;
        });
        Thread thread = workerThread();
        Object first = new Object(), second = new Object();
        try {
            FutureTask<Boolean> a = control(() -> {
                start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                return worker.offer(first);
            });
            FutureTask<Boolean> b = control(() -> {
                start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                return worker.offer(second);
            });
            start.await(WAIT_SECONDS, TimeUnit.SECONDS);
            boolean acceptedA = a.get(WAIT_SECONDS, TimeUnit.SECONDS), acceptedB = b.get(WAIT_SECONDS, TimeUnit.SECONDS);
            check(acceptedA != acceptedB, "simultaneous offers admit exactly one request");
            release.countDown();
            var result = completed(worker);
            Object accepted = acceptedA ? first : second;
            check(result.request() == accepted && result.result() == accepted && result.failure() == null,
                "losing offer cannot replace accepted work");
            check(calls.get() == 1 && !worker.busy(), "no second build was queued");
        } finally {
            release.countDown();
            worker.close();
            stopped(thread);
        }
    }

    private record ClosedProbe(LodItemWorker<Object, Object> worker, ReferenceQueue<Object> queue,
                               List<WeakReference<Object>> references) {}

    private static ClosedProbe referenceProbe(boolean finish) throws Exception {
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        List<WeakReference<Object>> references = new ArrayList<>();
        AtomicReference<WeakReference<Object>> output = new AtomicReference<>();
        CountDownLatch returning = new CountDownLatch(1);
        var worker = new LodItemWorker<Object, Object>(WORKER_NAME, request -> {
            Object result = new Object();
            output.set(new WeakReference<>(result, queue));
            returning.countDown();
            return result;
        });
        Thread thread = workerThread();
        Object request = new Object();
        references.add(new WeakReference<>(request, queue));
        synchronized (worker) {
            check(worker.offer(request), "reference fixture admitted");
            if (!finish) worker.close();
        }
        if (finish) {
            await(returning);
            published(thread);
            check(worker.busy() && !worker.offer(new Object()), "reference fixture holds an unclaimed result");
            references.add(output.get());
            worker.close();
        }
        stopped(thread);
        check(!worker.busy() && worker.poll() == null, "close clears queued/completed ownership");
        return new ClosedProbe(worker, queue, references);
    }

    private static void closeReleasesReferences() throws Exception {
        for (boolean finish : new boolean[]{false, true}) {
            ClosedProbe probe = referenceProbe(finish);
            // The runner enables explicit SerialGC; fixture locals are out of scope before collection.
            System.gc();
            for (int i = 0; i < probe.references().size(); i++) {
                Reference<?> reference = probe.queue().remove(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
                check(reference != null && probe.references().contains(reference), "closed worker releases unclaimed reference");
            }
            for (var reference : probe.references()) check(reference.get() == null, "no queued/completed strong reference remains");
            Reference.reachabilityFence(probe);
        }
    }

    public static void main(String[] args) throws Exception {
        queuedClose();
        System.out.println("PASS queued admission, null rejection, close before claim");
        runningAndResultAdmission();
        System.out.println("PASS nonblocking poll, monitor-free builder, completed-slot admission, reuse");
        failureAndRecovery();
        System.out.println("PASS exception/Error publication, recovery, null result");
        closeDuringBuild(false);
        closeDuringBuild(true);
        System.out.println("PASS nonblocking close, stale success/failure rejection, daemon exit");
        concurrentOffers();
        System.out.println("PASS simultaneous offers retain capacity one");
        closeReleasesReferences();
        System.out.println("PASS close releases queued/completed references");
        System.out.println("LodItemWorkerTest: " + CHECKS.get() + " checks passed (CPU only)");
    }
}
