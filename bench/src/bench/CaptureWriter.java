package bench;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Owns readback images until encoded. Bounded writers/queue, never blocks the render thread.
 * Close drains already-queued work; a rejected submission closes its image and reports failure.
 */
final class CaptureWriter implements AutoCloseable {
    @FunctionalInterface interface Write { void run() throws Exception; }
    private final ThreadPoolExecutor executor;
    private final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();

    CaptureWriter(int writers, int capacity) {
        AtomicInteger names = new AtomicInteger();
        executor = new ThreadPoolExecutor(writers, writers, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(capacity), task -> {
                Thread t = new Thread(task, "Capture-PNG-" + names.incrementAndGet());
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    void submit(AutoCloseable image, Write write, Consumer<Throwable> done) {
        Runnable job = () -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            Throwable error = null;
            try (image) { write.run(); }
            catch (Throwable failure) { error = failure; }
            finally { active.decrementAndGet(); }
            done.accept(error);
        };
        try { executor.execute(job); }
        catch (RejectedExecutionException failure) {
            try { image.close(); }
            catch (Throwable closing) { failure.addSuppressed(closing); }
            done.accept(failure);
        }
    }

    int peakActive() { return peak.get(); }
    @Override public void close() { executor.shutdown(); }
}
