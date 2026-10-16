package net.minecraft.client.renderer.chunk;

import java.util.Objects;
import java.util.function.Function;

/**
 * One pure-CPU preparation at a time, including its unclaimed result. Requests must remain read-only;
 * the builder must not access renderer state or native resources. Only reference handoffs use the monitor.
 */
final class LodItemWorker<Q, R> implements AutoCloseable {
    record Completed<Q, R>(Q request, R result, Throwable failure) {}

    private final Function<Q, R> build;
    private Q queued;
    private Completed<Q, R> completed;
    private boolean running;
    private boolean closed;

    LodItemWorker(String name, Function<Q, R> build) {
        this.build = Objects.requireNonNull(build);
        Thread worker = new Thread(this::run, Objects.requireNonNull(name));
        worker.setDaemon(true);
        worker.start();
    }

    synchronized boolean offer(Q request) {
        Objects.requireNonNull(request);
        if (this.closed || this.busy()) return false;
        this.queued = request;
        this.notifyAll();
        return true;
    }

    /** Nonblocking transfer of the completed request/result pair, including builder failures. */
    synchronized Completed<Q, R> poll() {
        Completed<Q, R> result = this.completed;
        this.completed = null;
        return result;
    }

    /** A closed worker remains busy only while its already-running builder is finishing. */
    synchronized boolean busy() {
        return this.queued != null || this.running || this.completed != null;
    }

    /** Revoke offers/publication without interrupting or joining an in-flight pure-CPU build. */
    @Override public synchronized void close() {
        this.closed = true;
        this.queued = null;
        this.completed = null;
        this.notifyAll();
    }

    private void run() {
        // Each call returns before waiting again, so an idle worker retains no previous request/result locals.
        while (this.prepareNext()) { }
    }

    private boolean prepareNext() {
        Q request;
        synchronized (this) {
            while (!this.closed && this.queued == null) {
                try {
                    this.wait();
                } catch (InterruptedException ignored) {
                    // Close uses notification; an unrelated interrupt must not strand an accepted request.
                }
            }
            if (this.closed) return false;
            request = this.queued;
            this.queued = null;
            this.running = true;
        }

        Completed<Q, R> result;
        try {
            result = new Completed<>(request, this.build.apply(request), null);
        } catch (Throwable failure) {
            result = new Completed<>(request, null, failure);
        }

        synchronized (this) {
            this.running = false;
            if (this.closed) return false;
            this.completed = result;
            return true;
        }
    }
}
