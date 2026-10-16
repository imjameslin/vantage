package net.minecraft.client.renderer.chunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** CPU-only fake-allocation fixture using the production identity, sizing, aging and maintenance policy. */
public final class LodGpuBufferCapacityTest {
    private static final long MIB = 1L << 20;
    private static int checks;

    private static void check(String message, boolean result) {
        checks++;
        if (!result) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check("opt-in property", LodGpuBufferCapacity.ENABLED == Boolean.parseBoolean(args[0]));
        sizing();
        hysteresis();
        maintenance();
        lifecycle();
        System.out.println("LodGpuBufferCapacityTest: " + checks + " checks passed (enabled=" + LodGpuBufferCapacity.ENABLED + ")");
    }

    private static void sizing() {
        check("minimum capacity", LodGpuBufferCapacity.capacity(1) == 65536L);
        check("12.5 percent growth", LodGpuBufferCapacity.capacity(1048576L) == 1179648L);
        long previous = 0L;
        for (long required : new long[]{0, 1, 4095, 4096, 65535, 65536, 65537, 1048575, 1048576, 5592405, Integer.MAX_VALUE}) {
            long size = LodGpuBufferCapacity.capacity(required);
            check("every candidate fits at boundary " + required, size >= required);
            check("monotonic/page-friendly " + required, size >= previous && size % 4096L == 0L);
            check("slack bounded by floor/rounding " + required, size <= Math.max(65536L, required + required / 8 + 4095L));
            check("both quad formats use long byte arithmetic " + required, 8L * size >= 4L * size);
            previous = size;
        }
        for (long invalid : new long[]{-1L, (long)Integer.MAX_VALUE + 1L, Long.MAX_VALUE}) {
            boolean rejected = false;
            try { LodGpuBufferCapacity.capacity(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
            check("invalid bound rejected", rejected);
        }
    }

    private static void hysteresis() {
        var use = new LodGpuBufferCapacity.Use();
        for (int frame = 0; frame <= 56; frame += 4) use.observe(frame, 1L << 20, 4L << 20, 4, 4);
        check("no shrink before 60 low-use frames", !use.shrinkReady(56));
        use.observe(60, 1L << 20, 4L << 20, 4, 4);
        check("persistent low use permits shrink", use.shrinkReady(60));
        use.observe(64, 3500000, 4L << 20, 4, 4);
        check("rebound resets history", !use.shrinkReady(64));
        use.observe(68, 1L << 20, 4L << 20, 4, 4);
        use.observe(76, 1L << 20, 4L << 20, 4, 4);
        check("missing a ring visit resets low-use interval", !use.shrinkReady(128));
        use.resized();
        check("replacement resets hysteresis", !use.shrinkReady(1000));

        var small = new LodGpuBufferCapacity.Use();
        for (int frame = 0; frame <= 80; frame += 4) small.observe(frame, 65536, 300000, 4, 4);
        check("tiny savings never cause replacement", !small.shrinkReady(80));
        var ratio = new LodGpuBufferCapacity.Use();
        long target = LodGpuBufferCapacity.capacity(16L << 20);
        for (int frame = 0; frame <= 80; frame += 4) ratio.observe(frame, 16L << 20, target + (1L << 20), 4, 4);
        check("4 MiB alone does not bypass relative hysteresis", !ratio.shrinkReady(80));
    }

    private static void maintenance() {
        var budget = new LodGpuBufferCapacity.Maintenance();
        check("oversized optional allocation refused", !budget.shrink(0, 100 * MIB, 33 * MIB, 0));
        check("retirement overlap refused", !budget.shrink(0, 32 * MIB, 8 * MIB, 100 * MIB));
        check("first replacement admitted", budget.shrink(0, 32 * MIB, 8 * MIB, 0));
        check("second replacement in same frame refused", !budget.shrink(0, 32 * MIB, 8 * MIB, 0));
        check("cooldown outlives LodGpu retirement delay", !budget.shrink(9, 32 * MIB, 8 * MIB, 0));
        Set<Integer> slots = new HashSet<>();
        slots.add(0);
        for (int frame = 13; frame <= 39; frame += 13) {
            check("later slot gets maintenance", budget.shrink(frame, 32 * MIB, 8 * MIB, 0));
            slots.add(frame % 4);
        }
        check("cooldown visits every ring slot", slots.size() == 4);
        check("inactive retirement 1", budget.retire(100, 32 * MIB, 0));
        check("inactive retirement 2", budget.retire(100, 32 * MIB, 32 * MIB));
        check("third inactive retirement waits", !budget.retire(100, MIB, 64 * MIB));
        check("inactive budget shared across slots", !budget.retire(101, 32 * MIB, 120 * MIB));
        check("single oversized inactive buffer can drain", budget.retire(102, 200 * MIB, 0));
        check("oversized retirement blocks another", !budget.retire(102, MIB, 200 * MIB));
    }

    private static final class FakeJob extends LodGpuBufferCapacity.Use {
        final int id;
        long capacity;
        boolean closed;
        FakeJob(int id, long capacity) { this.id = id; this.capacity = capacity; }
    }

    private record Retired(FakeJob job, long frame, long bytes) { }

    private static void lifecycle() {
        List<LodGpuBufferCapacity.Jobs<FakeJob>> rings = new ArrayList<>();
        for (int i = 0; i < 4; i++) rings.add(new LodGpuBufferCapacity.Jobs<>());
        var key = new LodGpuBufferCapacity.Key(3, 0, false, -1);
        Set<Integer> ids = new HashSet<>();
        int[] serial = {0};
        for (int i = 0; i < 4; i++) {
            FakeJob job = rings.get(i).get(key, () -> new FakeJob(++serial[0], 4L << 20));
            job.observe(i, 1L << 20, job.capacity, 4, 4);
            ids.add(job.id);
        }
        check("all four slots own independent backing", ids.size() == 4);
        var slot = rings.getFirst();
        FakeJob solid = slot.get(key, () -> { throw new AssertionError("lost group identity"); });
        var other = new LodGpuBufferCapacity.Key(3, 1, false, -1);
        FakeJob cutout = slot.get(other, () -> new FakeJob(++serial[0], 2L << 20));
        cutout.observe(4, 65536, cutout.capacity, 4, 4);
        List<FakeJob> changedOrder = List.of(slot.get(other, () -> null), slot.get(key, () -> null));
        check("reordering keeps buffers with their sources", changedOrder.get(0) == cutout && changedOrder.get(1) == solid);
        for (var variant : List.of(new LodGpuBufferCapacity.Key(4, 0, false, -1),
                new LodGpuBufferCapacity.Key(3, 0, true, -1), new LodGpuBufferCapacity.Key(3, 0, false, 2))) {
            FakeJob different = slot.get(variant, () -> new FakeJob(++serial[0], 65536));
            check("arena/fade/diagnostic identity distinguished", different != solid);
            different.observe(4, 1, different.capacity, 4, 4);
        }
        check("last ring snapshot current", solid.current(3, 4));
        check("unused next rotation classified inactive", !solid.current(4, 4));
        check("short absence preserves allocation", !solid.expired(119));
        solid.observe(116, 1L << 20, solid.capacity, 4, 4);
        check("reappearance restarts grace", !solid.expired(120));

        var budget = new LodGpuBufferCapacity.Maintenance();
        List<Retired> retired = new ArrayList<>();
        long[] pending = {0};
        int[] retirementCount = {0};
        for (int frame = 120; frame <= 260; frame++) {
            final int now = frame;
            var it = retired.iterator();
            while (it.hasNext()) {
                Retired r = it.next();
                if (now - r.frame() > 8) {
                    check("not closed before retirement drains", !r.job().closed);
                    r.job().closed = true;
                    pending[0] -= r.bytes();
                    it.remove();
                }
            }
            int before = retirementCount[0];
            for (var ring : rings) ring.retireInactive(now, budget, () -> pending[0], job -> 4L * job.capacity, job -> {
                check("inactive job goes to retirement, not close", !job.closed && job.expired(now));
                retired.add(new Retired(job, now, 4L * job.capacity));
                pending[0] += 4L * job.capacity;
                retirementCount[0]++;
            });
            if (retirementCount[0] > before) check("bounded inactive work per frame", retirementCount[0] - before <= 2);
            if (frame == 120) check("reappeared group survives other retirements", slot.values().contains(solid));
        }
        check("all inactive allocations eventually leave registries", rings.stream().allMatch(r -> r.values().isEmpty()));
        check("all retirement bytes eventually drain", pending[0] == 0 && retired.isEmpty());
        FakeJob replacement = slot.get(key, () -> new FakeJob(++serial[0], LodGpuBufferCapacity.capacity(2000000)));
        check("retired job cannot be resurrected", replacement != solid && solid.closed);
        check("larger reappearance fits all current candidates", replacement.capacity >= 2000000);
    }
}
