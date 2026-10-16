package net.minecraft.client.renderer.chunk;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Tiny CPU allocator fixture: real retirement policy, fake ranges, section slots and GPU buffers. */
public final class LodGeometryLeasesTest {
    private static int checks;

    private static void check(String message, boolean condition) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            check("expected " + type.getSimpleName() + ", got " + failure, type.isInstance(failure));
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private record Allocation(int range, int slot, int value) { }

    private static final class Buffer {
        int closes;
        void close() {
            check("buffer disposed exactly once", this.closes++ == 0);
        }
    }

    private record Pending(long epoch, long frame, int grace, int range, int slot, Buffer buffer) { }

    private static final class Allocator implements AutoCloseable {
        final Thread renderThread = Thread.currentThread();
        final LodGeometryLeases leases;
        final boolean[] freeRanges, freeSlots;
        final int[] ranges, slots;
        final List<Pending> pending = new ArrayList<>();
        final List<Buffer> buffers = new ArrayList<>();
        long frame;
        int serial, disposals;
        boolean reuseClosed;

        Allocator(int capacity) {
            this.freeRanges = new boolean[capacity];
            this.freeSlots = new boolean[capacity];
            this.ranges = new int[capacity];
            this.slots = new int[capacity];
            Arrays.fill(this.freeRanges, true);
            Arrays.fill(this.freeSlots, true);
            this.leases = new LodGeometryLeases(this::assertRenderThread, this::dispose);
        }

        void assertRenderThread() {
            if (Thread.currentThread() != this.renderThread) throw new IllegalStateException("Not render thread");
        }

        Allocation allocate() {
            if (this.leases.closed()) return null;
            int range = firstFree(this.freeRanges), slot = firstFree(this.freeSlots);
            if (range < 0 || slot < 0) return null;
            this.freeRanges[range] = this.freeSlots[slot] = false;
            this.ranges[range] = this.slots[slot] = ++this.serial;
            return new Allocation(range, slot, this.serial);
        }

        private static int firstFree(boolean[] free) {
            for (int i = 0; i < free.length; i++) if (free[i]) return i;
            return -1;
        }

        Buffer buffer() {
            Buffer buffer = new Buffer();
            this.buffers.add(buffer);
            return buffer;
        }

        void retire(Allocation allocation) {
            long epoch = this.leases.retire();
            this.pending.add(new Pending(epoch, this.frame, 6, allocation.range(), -1, null));
            this.pending.add(new Pending(epoch, this.frame, 6, -1, allocation.slot(), null));
        }

        void retire(Buffer buffer) {
            this.pending.add(new Pending(this.leases.retire(), this.frame, 8, -1, -1, buffer));
        }

        void drain(long frame) {
            this.frame = frame;
            for (int i = this.pending.size() - 1; i >= 0; i--) {
                Pending p = this.pending.get(i);
                if (!this.leases.canRecycle(p.epoch(), p.frame(), frame, p.grace())) continue;
                if (p.range() >= 0) this.freeRanges[p.range()] = true;
                if (p.slot() >= 0) this.freeSlots[p.slot()] = true;
                if (p.buffer() != null) p.buffer().close();
                this.pending.remove(i);
            }
        }

        void held(Allocation a) {
            check("range cannot be overwritten", !this.freeRanges[a.range()] && this.ranges[a.range()] == a.value());
            check("section identity cannot be recycled", !this.freeSlots[a.slot()] && this.slots[a.slot()] == a.value());
        }

        private void dispose() {
            this.assertRenderThread();
            check("dispose only after revocation and last lease", this.leases.closed() && this.leases.count() == 0);
            check("one teardown callback", ++this.disposals == 1);
            for (Buffer buffer : this.buffers) if (buffer.closes == 0) buffer.close();
            Arrays.fill(this.ranges, 0);
            Arrays.fill(this.slots, 0);
            this.pending.clear();
        }

        @Override public void close() {
            this.assertRenderThread();
            this.reuseClosed = true;
            this.leases.close();
        }
    }

    public static void main(String[] args) throws Exception {
        noLeaseBoundaries();
        retireBeforeAcquire();
        overlappingSnapshots();
        nestedAndStaleTokens();
        foreignOwners();
        releaseBeforeGrace();
        deferredClose();
        renderThreadOwnership();
        overflowSafety();
        reentrantCleanup();
        allocationFreeChecks();
        System.out.println("LodGeometryLeasesTest: " + checks + " checks passed (CPU only)");
    }

    private static void noLeaseBoundaries() {
        try (Allocator a = new Allocator(1)) {
            a.frame = 100;
            Allocation original = a.allocate();
            Buffer old = a.buffer();
            a.retire(original);
            a.retire(old);
            for (int age = 0; age <= 6; age++) {
                a.drain(100 + age);
                a.held(original);
                check("no range or slot reusable through age six", a.allocate() == null);
                check("old buffer remains live", old.closes == 0);
            }
            a.drain(107);
            Allocation replacement = a.allocate();
            check("range and slot reused at age seven", replacement != null
                && replacement.range() == original.range() && replacement.slot() == original.slot());
            check("range really overwritten only after retirement", a.ranges[original.range()] != original.value());
            a.drain(108);
            check("buffer still live at exact age eight", old.closes == 0);
            a.drain(109);
            check("buffer disposed at age nine", old.closes == 1 && a.pending.isEmpty());
            check("off path creates no leases", a.leases.count() == 0 && a.leases.oldestEpoch() == 0);
        }
    }

    private static void retireBeforeAcquire() {
        try (Allocator a = new Allocator(2)) {
            Allocation before = a.allocate();
            Buffer beforeBuffer = a.buffer();
            a.retire(before);
            a.retire(beforeBuffer);
            Allocation current = a.allocate();
            Buffer currentBuffer = a.buffer();
            var snapshot = a.leases.acquire();
            check("captures current epoch", snapshot.epoch() == a.leases.epoch());
            a.retire(current);
            a.retire(currentBuffer);
            a.drain(9);
            Allocation recycled = a.allocate();
            check("later lease does not pin earlier range/slot", recycled != null
                && recycled.range() == before.range() && recycled.slot() == before.slot());
            check("later lease does not pin earlier buffer", beforeBuffer.closes == 1);
            a.held(current);
            check("subsequently retired buffer pinned", currentBuffer.closes == 0);
            snapshot.close();
            a.drain(9);
            check("mature retirements drain without restarting age", currentBuffer.closes == 1 && a.pending.isEmpty());
        }
    }

    private static void overlappingSnapshots() {
        try (Allocator a = new Allocator(3)) {
            Allocation first = a.allocate(), second = a.allocate(), third = a.allocate();
            var oldest = a.leases.acquire();
            a.retire(first);
            var middle = a.leases.acquire();
            a.retire(second);
            var newest = a.leases.acquire();
            a.retire(third);
            long epoch = a.leases.epoch();
            check("fourth snapshot refused without changing epoch", a.leases.acquire() == null
                && a.leases.count() == 3 && a.leases.epoch() == epoch);
            a.drain(100);
            a.held(first); a.held(second); a.held(third);
            check("allocator cannot overwrite any snapshot", a.allocate() == null);
            middle.close(); middle.close();
            check("out-of-order repeated close leaves oldest protection", a.leases.count() == 2
                && a.leases.oldestEpoch() == oldest.epoch());
            a.drain(100);
            a.held(first); a.held(second); a.held(third);
            oldest.close();
            a.drain(100);
            check("oldest advances past released middle", a.leases.oldestEpoch() == newest.epoch());
            check("earlier retirements drain while newer lease lives", a.freeRanges[first.range()]
                && a.freeSlots[first.slot()] && a.freeRanges[second.range()] && a.freeSlots[second.slot()]);
            a.held(third);
            Allocation afterCapture = a.allocate();
            a.retire(afterCapture);
            a.drain(200);
            a.held(afterCapture); // Deliberately conservative global protection.
            oldest.close();
            check("old stale close cannot release newest", a.leases.count() == 1);
            newest.close(); newest.close();
            a.drain(200);
            check("last close drains all matured storage", a.pending.isEmpty() && a.leases.oldestEpoch() == 0);
        }
    }

    private static void nestedAndStaleTokens() {
        try (Allocator a = new Allocator(1)) {
            Allocation allocation = a.allocate();
            var first = a.leases.acquire();
            var nested = a.leases.acquire();
            first.close();
            var reusedRegistrySlot = a.leases.acquire();
            check("same epoch leases have distinct identity", nested != reusedRegistrySlot
                && first.epoch() == reusedRegistrySlot.epoch());
            a.retire(allocation);
            first.close();
            nested.close();
            a.drain(1000);
            a.held(allocation);
            check("stale token cannot release reused registry slot", first.isClosed()
                && !reusedRegistrySlot.isClosed() && a.leases.count() == 1);
            reusedRegistrySlot.close();
            a.drain(1000);
            check("last nested lease permits allocation", a.allocate() != null);
        }
    }

    private static void foreignOwners() {
        try (Allocator a = new Allocator(1); Allocator b = new Allocator(1)) {
            var own = a.leases.acquire();
            var foreign = b.leases.acquire();
            check("owner-local epochs may coincide", own.epoch() == foreign.epoch());
            Allocation allocation = a.allocate();
            a.retire(allocation);
            rejects(IllegalArgumentException.class, () -> a.leases.release(foreign));
            rejects(IllegalArgumentException.class, () -> a.leases.release(null));
            foreign.close();
            rejects(IllegalArgumentException.class, () -> a.leases.release(foreign));
            check("foreign active/closed tokens leave own lease intact", a.leases.count() == 1 && b.leases.count() == 0);
            a.drain(100);
            a.held(allocation);
            own.close();
        }
    }

    private static void releaseBeforeGrace() {
        try (Allocator a = new Allocator(1)) {
            Allocation allocation = a.allocate();
            Buffer buffer = a.buffer();
            var lease = a.leases.acquire();
            a.retire(allocation); a.retire(buffer);
            lease.close();
            a.drain(6);
            a.held(allocation);
            a.drain(8);
            check("lease release cannot shorten ordinary grace", a.allocate() != null && buffer.closes == 0);
            a.drain(9);
            check("released buffer still observes age nine", buffer.closes == 1);
        }
    }

    private static void deferredClose() {
        Allocator a = new Allocator(2);
        Allocation retired = a.allocate(), resident = a.allocate();
        Buffer pending = a.buffer(), current = a.buffer();
        var first = a.leases.acquire();
        a.retire(retired); a.retire(pending);
        var second = a.leases.acquire();
        a.close(); a.close();
        check("world close revokes offers and allocation immediately", a.reuseClosed && a.leases.closed()
            && a.leases.acquire() == null && a.allocate() == null);
        rejects(IllegalStateException.class, a.leases::retire);
        a.drain(1000);
        a.held(retired); a.held(resident);
        check("world close retains current and deferred buffers", pending.closes == 0 && current.closes == 0
            && a.disposals == 0 && !a.leases.disposed());
        second.close(); second.close();
        check("out-of-order release cannot trigger early teardown", a.disposals == 0);
        first.close(); // No subsequent frame or drain: this must perform teardown itself.
        check("last close disposes without another world frame", a.disposals == 1 && a.leases.disposed()
            && pending.closes == 1 && current.closes == 1 && a.pending.isEmpty());
        check("range payload and section storage disposed", a.ranges[resident.range()] == 0 && a.slots[resident.slot()] == 0);
        a.close(); first.close(); second.close();
        check("repeated lifecycle operations cannot double dispose", a.disposals == 1 && a.leases.count() == 0);
        check("closed owner never returns retired storage to allocator", !a.leases.canRecycle(1, 0, 10000, 6));

        Allocator empty = new Allocator(1);
        Buffer unleased = empty.buffer();
        empty.close(); empty.close();
        check("zero-lease teardown remains immediate", empty.disposals == 1 && unleased.closes == 1);
    }

    private static void renderThreadOwnership() throws Exception {
        Allocator a = new Allocator(1);
        var lease = a.leases.acquire();
        Allocation allocation = a.allocate();
        a.retire(allocation);
        Throwable[] failure = new Throwable[1];
        Thread worker = new Thread(() -> {
            try {
                rejects(IllegalStateException.class, a.leases::acquire);
                rejects(IllegalStateException.class, lease::close);
                rejects(IllegalStateException.class, a.leases::retire);
                rejects(IllegalStateException.class, a.leases::close);
            } catch (Throwable error) { failure[0] = error; }
        }, "fake-native-completion");
        worker.start(); worker.join();
        if (failure[0] != null) throw new AssertionError("Thread ownership test failed", failure[0]);
        check("wrong-thread operations leave ownership intact", a.leases.count() == 1 && !a.leases.closed() && !lease.isClosed());
        a.drain(100); a.held(allocation);
        a.close();
        lease.close(); // Models the render-thread completion poll after world close.
        check("render-thread completion drains closed owner", a.disposals == 1);
    }

    private static void overflowSafety() {
        var leases = new LodGeometryLeases(() -> {}, () -> {}, Long.MAX_VALUE - 1);
        var first = leases.acquire();
        long beforeMax = leases.retire();
        var second = leases.acquire();
        long atMax = leases.retire();
        var third = leases.acquire(); // After retirement at saturation: conservatively pins that epoch too.
        check("epoch saturates instead of wrapping", beforeMax == Long.MAX_VALUE - 1 && atMax == Long.MAX_VALUE
            && leases.retire() == Long.MAX_VALUE && leases.epoch() == Long.MAX_VALUE);
        first.close();
        check("pre-saturation retirement can drain", leases.canRecycle(beforeMax, 0, 100, 6));
        second.close();
        check("later saturated snapshot still protects identical epoch", !leases.canRecycle(atMax, 0, 100, 6));
        third.close();
        check("saturated retirement drains when no leases remain", leases.canRecycle(atMax, 0, 100, 6));
        leases.close();

        var ages = new LodGeometryLeases(() -> {}, () -> {});
        long epoch = ages.retire();
        long retiredFrame = Long.MAX_VALUE - 3;
        for (int age = 0; age <= 10; age++) {
            check("range age across signed frame overflow " + age,
                ages.canRecycle(epoch, retiredFrame, retiredFrame + age, 6) == (age > 6));
            check("buffer age across signed frame overflow " + age,
                ages.canRecycle(epoch, retiredFrame, retiredFrame + age, 8) == (age > 8));
        }
        check("backward frame fails closed", !ages.canRecycle(epoch, 100, 99, 6));
        check("ambiguous half-cycle frame age fails closed", !ages.canRecycle(epoch, 0, Long.MIN_VALUE, 6));
        for (long invalid : new long[]{0, -1, Long.MIN_VALUE, ages.epoch() + 1}) {
            check("invalid retirement epoch fails closed", !ages.canRecycle(invalid, 0, 100, 6));
        }
        check("invalid grace fails closed", !ages.canRecycle(epoch, 0, 100, -1));
        rejects(IllegalArgumentException.class, () -> new LodGeometryLeases(() -> {}, () -> {}, 0));
        rejects(IllegalArgumentException.class, () -> new LodGeometryLeases(() -> {}, () -> {}, -1));
        ages.close();
    }

    private static void reentrantCleanup() {
        LodGeometryLeases[] owner = new LodGeometryLeases[1];
        int[] disposals = {0};
        owner[0] = new LodGeometryLeases(() -> {}, () -> {
            disposals[0]++;
            owner[0].close();
        });
        owner[0].close(); owner[0].close();
        check("cleanup claims disposal before invoking callback", disposals[0] == 1);
    }

    private static void allocationFreeChecks() {
        var leases = new LodGeometryLeases(() -> {}, () -> {});
        long epoch = leases.retire();
        int eligible = 0;
        for (int i = 0; i < 200000; i++) if (leases.canRecycle(epoch, 0, i, 6)) eligible++;
        var bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean allocation && allocation.isThreadAllocatedMemorySupported()) {
            allocation.setThreadAllocatedMemoryEnabled(true);
            long thread = Thread.currentThread().threadId();
            allocation.getThreadAllocatedBytes(thread); // Warm up the measurement itself.
            long before = allocation.getThreadAllocatedBytes(thread);
            for (int i = 0; i < 200000; i++) if (leases.canRecycle(epoch, 0, i, 6)) eligible++;
            long bytes = allocation.getThreadAllocatedBytes(thread) - before;
            check("zero-lease checks allocate zero bytes (actual " + bytes + ")", bytes == 0);
            check("measured checks executed", eligible == 399986);
        } else {
            System.out.println("Thread allocation counter unavailable; allocation measurement skipped");
        }
        leases.close();
    }
}
