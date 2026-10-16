package net.minecraft.client.renderer.chunk;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Observable mailbox sequences only; no renderer, reflection into the helper, or source-text assertions. */
public final class LodMeshHandoffTest {
    private static final AtomicInteger CHECKS = new AtomicInteger();
    private static final int WAIT_SECONDS = 5;
    private record Key(String name) {}
    private record Packet(int producer, int generation) {}

    private static void check(boolean ok, String why) {
        CHECKS.incrementAndGet();
        if (!ok) throw new AssertionError(why);
    }

    private static <K, V> void result(LodMeshHandoff.Result<K, V> actual, K key, V payload, long stamp, long bytes) {
        check(actual != null, "expected a claimed result");
        check(actual.key() == key && actual.payload() == payload, "claimed key/payload identities match offered pair");
        check(actual.requestedStamp() == stamp && actual.bytes() == bytes, "claim retains exact payload stamp/size");
    }

    private static void stats(LodMeshHandoff<?, ?> box, boolean closed, int pending, long bytes,
                              long offers, long coalesced, long claims, long rejected) {
        check(box.stats().equals(new LodMeshHandoff.Stats(closed, pending, bytes, offers, coalesced, claims, rejected)),
            "accounting: " + box.stats());
        check(box.isEmpty() == (pending == 0), "empty agrees with observable pending count");
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); }
        catch (Throwable error) { check(type.isInstance(error), "expected " + type + ", got " + error); return; }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) throw new AssertionError("latch deadline");
    }

    private static void barrier(CyclicBarrier barrier) throws Exception {
        barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static void stop(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow();
        check(workers.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "workers terminated within bound");
    }

    private static void fifoIdentityAndAccounting() {
        try (var box = new LodMeshHandoff<Key, Packet>()) {
            Key a = new Key("equal"), equalButDistinct = new Key("equal"), b = new Key("other");
            Packet old = new Packet(0, 0), replacement = new Packet(0, 1), other = new Packet(1, 0), third = new Packet(2, 0);
            check(a != equalButDistinct && a.equals(equalButDistinct), "fixture keys compare equal but have different identities");
            check(box.poll() == null, "empty poll has no claim");
            stats(box, false, 0, 0, 0, 0, 0, 0);
            check(box.offer(a, old, 91, 11), "offer A");
            check(!box.hasStamp(equalButDistinct, 91), "stamp lookup uses identity too");
            check(box.offer(b, other, 33, 17), "offer B");
            check(box.offer(equalButDistinct, third, 91, 0), "equal key is a distinct zero-byte pending result");
            check(box.offer(a, replacement, -8, 5), "replace A with an older numerical stamp and smaller payload");
            stats(box, false, 3, 22, 4, 1, 0, 0);
            check(!box.hasStamp(a, 91) && box.hasStamp(a, -8) && box.hasStamp(equalButDistinct, 91), "replacement affects only A identity");
            result(box.poll(), a, replacement, -8, 5); // Replacement stays ahead of B and the equal key.
            stats(box, false, 2, 17, 4, 1, 1, 0);
            check(!box.hasStamp(a, -8), "claim removes pending stamp");
            check(box.offer(a, old, Long.MIN_VALUE, 23), "offer after claim creates a new FIFO position");
            result(box.poll(), b, other, 33, 17);
            result(box.poll(), equalButDistinct, third, 91, 0);
            result(box.poll(), a, old, Long.MIN_VALUE, 23);
            check(box.poll() == null, "exactly three remaining claims");
            stats(box, false, 0, 0, 5, 1, 4, 0);
        }
    }

    private static void arbitraryStamps() {
        try (var box = new LodMeshHandoff<Object, Packet>()) {
            Object key = new Object();
            long[] stamps = {Long.MAX_VALUE, -1, Long.MIN_VALUE, 0, 0, 47, -300};
            Packet latest = null;
            for (int i = 0; i < stamps.length; i++) {
                latest = new Packet(0, i);
                check(box.offer(key, latest, stamps[i], i), "nonmonotonic/repeated stamps accepted as opaque tags");
                check(box.hasStamp(key, stamps[i]), "pending tag is the last offered tag");
                stats(box, false, 1, i, i + 1, i, 0, 0);
            }
            result(box.poll(), key, latest, -300, 6);
            stats(box, false, 0, 0, 7, 6, 1, 0);
        }
    }

    private static void claimThenOffer() throws Exception {
        try (var box = new LodMeshHandoff<Object, Packet>()) {
            Object key = new Object(); Packet old = new Packet(0, 0), newer = new Packet(0, 1);
            check(box.offer(key, old, 100, 12), "seed old result");
            CountDownLatch claimed = new CountDownLatch(1), published = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(1);
            try {
                Future<?> worker = workers.submit(() -> {
                    await(claimed);
                    check(box.offer(key, newer, 200, 37), "producer publishes after consumer claim");
                    published.countDown();
                    return null;
                });
                var owned = box.poll();
                claimed.countDown();
                await(published);
                result(owned, key, old, 100, 12); // New publication cannot relabel the in-flight old payload.
                stats(box, false, 1, 37, 2, 0, 1, 0);
                check(box.hasStamp(key, 200), "newer publication remains pending while old claim is held");
                result(box.poll(), key, newer, 200, 37);
                result(owned, key, old, 100, 12);
                stats(box, false, 0, 0, 2, 0, 2, 0);
                worker.get(WAIT_SECONDS, TimeUnit.SECONDS);
            } finally { stop(workers); }
        }
    }

    private static void failedOffersAreAtomic() {
        try (var box = new LodMeshHandoff<Object, Object>()) {
            Object a = new Object(), b = new Object(), c = new Object(), old = new Object(), other = new Object(), rejected = new Object();
            check(box.offer(a, old, 8, Long.MAX_VALUE - 3), "large accounting value does not allocate payload bytes");
            check(box.offer(b, other, 9, 3), "exact Long.MAX_VALUE aggregate accepted");
            var before = box.stats();
            expect(ArithmeticException.class, () -> box.offer(c, rejected, 10, 1));
            check(box.stats().equals(before) && !box.hasStamp(c, 10), "overflow new-key offer leaves all counters and membership unchanged");
            expect(ArithmeticException.class, () -> box.offer(a, rejected, 11, Long.MAX_VALUE));
            check(box.stats().equals(before) && box.hasStamp(a, 8) && !box.hasStamp(a, 11), "overflow replacement preserves old pair and accounting");
            expect(IllegalArgumentException.class, () -> box.offer(a, rejected, 12, -1));
            expect(NullPointerException.class, () -> box.offer(null, rejected, 12, 0));
            expect(NullPointerException.class, () -> box.offer(a, null, 12, 0));
            check(box.stats().equals(before), "invalid arguments do not mutate pending state");
            result(box.poll(), a, old, 8, Long.MAX_VALUE - 3);
            stats(box, false, 1, 3, 2, 0, 1, 0);
            result(box.poll(), b, other, 9, 3);
            check(box.poll() == null, "failed offers added no FIFO entries");
            stats(box, false, 0, 0, 2, 0, 2, 0);
        }
    }

    private static void closeAfterClaim() throws Exception {
        var box = new LodMeshHandoff<Object, Packet>();
        Object a = new Object(), b = new Object(); Packet ownedPayload = new Packet(0, 0), pending = new Packet(1, 0);
        check(box.offer(a, ownedPayload, 8, 12) && box.offer(b, pending, 9, 27), "seed claimed and unclaimed work");
        CountDownLatch claimed = new CountDownLatch(1), closed = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(1);
        try {
            Future<?> closer = workers.submit(() -> {
                await(claimed);
                box.close();
                check(!box.offer(a, new Packet(0, 1), 100, 100), "close rejects late offer for claimed key");
                check(!box.offer(b, new Packet(1, 1), 200, 200), "close rejects late offer for unclaimed key");
                closed.countDown();
                return null;
            });
            var owned = box.poll();
            claimed.countDown();
            await(closed);
            result(owned, a, ownedPayload, 8, 12);
            stats(box, true, 0, 0, 2, 0, 1, 2);
            check(box.poll() == null && !box.hasStamp(b, 9), "close revokes unclaimed work without counting it as claimed");
            var before = box.stats(); box.close();
            check(box.stats().equals(before), "repeated close is idempotent");
            closer.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally { box.close(); stop(workers); }
    }

    private record ClosedProbe(LodMeshHandoff<Object, Object> box, ReferenceQueue<Object> queue,
                               List<WeakReference<Object>> unclaimed, LodMeshHandoff.Result<Object, Object> owned) {}

    // Return from this scope so no test local keeps an unclaimed key/payload alive during the reachability check.
    private static ClosedProbe closedProbe() {
        var box = new LodMeshHandoff<Object, Object>();
        var queue = new ReferenceQueue<Object>();
        Object claimedKey = new Object(), claimedPayload = new Object(), key = new Object(), old = new Object(), latest = new Object();
        var refs = List.of(new WeakReference<>(key, queue), new WeakReference<>(old, queue), new WeakReference<>(latest, queue));
        box.offer(claimedKey, claimedPayload, 1, 10);
        box.offer(key, old, 2, 20);
        box.offer(key, latest, 3, 30);
        var owned = box.poll();
        box.close();
        return new ClosedProbe(box, queue, refs, owned);
    }

    private static void closeReleasesReferences() throws Exception {
        ClosedProbe probe = closedProbe();
        // The runner uses SerialGC with explicit GC enabled; queue waits are bounded, with no sleep/retry guesses.
        System.gc();
        for (int i = 0; i < probe.unclaimed().size(); i++) {
            Reference<?> ref = probe.queue().remove(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            check(ref != null && probe.unclaimed().contains(ref), "unclaimed/coalesced key and payload references released");
        }
        for (var ref : probe.unclaimed()) check(ref.get() == null, "closed mailbox holds no unclaimed object strongly");
        check(probe.owned().key() != null && probe.owned().payload() != null && probe.owned().requestedStamp() == 1,
            "consumer-owned pair survives close and collection");
        stats(probe.box(), true, 0, 0, 3, 1, 1, 0);
        Reference.reachabilityFence(probe); // Keep the mailbox itself alive while proving it released the unclaimed objects.
    }

    private static long stamp(int generation) {
        return switch (generation % 5) { case 0 -> Long.MAX_VALUE; case 1 -> Long.MIN_VALUE; case 2 -> 0; case 3 -> -31; default -> 8; };
    }

    private static long bytes(Packet packet) { return (packet.producer() + 1L) * 100 + packet.generation(); }

    private static void batchResult(LodMeshHandoff.Result<Key, Packet> actual, Key[] keys, Packet[][] packets,
                                    int generation, boolean[] seen) {
        check(actual != null, "published batch result is available");
        int p = actual.payload().producer();
        check(p >= 0 && p < keys.length && !seen[p], "exactly one batch result per producer identity");
        seen[p] = true;
        result(actual, keys[p], packets[p][generation], stamp(generation), bytes(packets[p][generation]));
    }

    private static void multipleProducers() throws Exception {
        final int producers = 4, rounds = 12;
        Key[] keys = new Key[producers]; Packet[][] packets = new Packet[producers][rounds * 3];
        for (int p = 0; p < producers; p++) {
            keys[p] = new Key("producer-" + p);
            for (int g = 0; g < rounds * 3; g++) packets[p][g] = new Packet(p, g);
        }
        var phase = new CyclicBarrier(producers + 1);
        var finalReady = new Semaphore(0);
        ExecutorService workers = Executors.newFixedThreadPool(producers);
        try (var box = new LodMeshHandoff<Key, Packet>()) {
            List<Future<?>> futures = new ArrayList<>();
            try {
                for (int p = 0; p < producers; p++) {
                    final int producer = p;
                    futures.add(workers.submit(() -> {
                        for (int round = 0; round < rounds; round++) {
                            int first = 3 * round;
                            for (int g = first; g < first + 2; g++) {
                                Packet packet = packets[producer][g];
                                check(box.offer(keys[producer], packet, stamp(g), bytes(packet)), "concurrent producer burst accepted");
                            }
                            barrier(phase); // All two-packet bursts complete before the first drain.
                            barrier(phase); // Consumer owns those claims before final publication begins.
                            Packet last = packets[producer][first + 2];
                            check(box.offer(keys[producer], last, stamp(first + 2), bytes(last)), "final packet accepted after prior claim");
                            finalReady.release();
                            barrier(phase); // Do not start the next round until the final drain is checked.
                        }
                        return null;
                    }));
                }
                for (int round = 0; round < rounds; round++) {
                    barrier(phase);
                    long pendingBytes = 0;
                    for (int p = 0; p < producers; p++) pendingBytes += bytes(packets[p][3 * round + 1]);
                    stats(box, false, producers, pendingBytes, (3L * round + 2) * producers,
                        (long)(round + 1) * producers, 2L * round * producers, 0);
                    boolean[] seen = new boolean[producers];
                    for (int p = 0; p < producers; p++) batchResult(box.poll(), keys, packets, 3 * round + 1, seen);
                    check(box.poll() == null, "burst drain leaves no duplicate/coalesced old packet");
                    barrier(phase);
                    seen = new boolean[producers];
                    for (int p = 0; p < producers; p++) {
                        check(finalReady.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS), "bounded final-publication notification");
                        batchResult(box.poll(), keys, packets, 3 * round + 2, seen);
                    }
                    stats(box, false, 0, 0, 3L * (round + 1) * producers, (long)(round + 1) * producers,
                        2L * (round + 1) * producers, 0);
                    check(finalReady.availablePermits() == 0 && box.poll() == null, "every final packet claimed exactly once");
                    barrier(phase);
                }
                for (Future<?> future : futures) future.get(WAIT_SECONDS, TimeUnit.SECONDS);
                stats(box, false, 0, 0, 144, 48, 96, 0);
            } finally { stop(workers); }
        }
    }

    public static void main(String[] args) throws Exception {
        fifoIdentityAndAccounting();
        arbitraryStamps();
        failedOffersAreAtomic();
        System.out.println("LodMeshHandoffTest: FIFO/identity/stamps/bytes/overflow sequences passed");
        claimThenOffer();
        closeAfterClaim();
        closeReleasesReferences();
        System.out.println("LodMeshHandoffTest: forced claim/offer/close ordering and reference release passed");
        multipleProducers();
        System.out.println("LodMeshHandoffTest: 4 producers x 12 rounds; 144 offers, 48 coalesced, 96 claims, no lost final packets");
        System.out.println("LodMeshHandoffTest: " + CHECKS.get() + " checks passed (CPU only)");
    }
}
