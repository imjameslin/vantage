package net.minecraft.client.renderer.chunk;

import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.foreign.Arena;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Map;

/** Calls the production LodGpu API with CPU memory only; no device, game, native Metal or GPU. */
public final class LodFarRefreshTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static Field field(String name) throws Exception {
        Field f = LodGpu.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Object get(LodGpu gpu, String name) throws Exception { return field(name).get(gpu); }
    private static void set(LodGpu gpu, String name, Object value) throws Exception { field(name).set(gpu, value); }

    /** Any resident/input/arena inspection, even size(), fails the bounded-work test. */
    private static final class NoScan extends AbstractList<Object> {
        @Override public int size() { throw new AssertionError("Geometry collection inspected"); }
        @Override public Object get(int index) { throw new AssertionError("Geometry collection scanned"); }
    }

    public static void main(String[] args) throws Exception {
        RenderSystem.initRenderThread();
        // LodGpu's constructor only initializes CPU state. A null manager and null GPU buffers
        // deliberately make the test independent of world/device initialization.
        LodGpu gpu = new LodGpu(null);
        set(gpu, "resident", new NoScan());
        set(gpu, "groups", new NoScan());
        set(gpu, "arenas", new NoScan());
        try (Arena memory = Arena.ofConfined()) {
            var block = memory.allocate(128, 8);
            ByteBuffer live = block.asByteBuffer().order(ByteOrder.nativeOrder());
            for (int i = 0; i < 32; i++) live.putFloat(i * 4, i + 0.25f);
            live.putInt(84, 31);
            set(gpu, "view", block.address());
            set(gpu, "itemsDirty", false);
            set(gpu, "c0Set", true);
            set(gpu, "farViewReady", true);
            set(gpu, "frame", 42L);
            set(gpu, "farViewFrame", 42L);
            set(gpu, "farViewNanos", 9_000L);
            set(gpu, "builds", 3L);
            set(gpu, "builtSelection", 4L);
            set(gpu, "visibilityRevision", 5L);
            set(gpu, "c0x", 100); set(gpu, "c0y", -20); set(gpu, "c0z", 300);
            String[] cameras = {"farCameraX", "farCameraY", "farCameraZ", "farEyeX", "farEyeY", "farEyeZ"};
            for (int i = 0; i < cameras.length; i++) set(gpu, cameras[i], 10.25 + i);
            LodGeometryLeases leases = (LodGeometryLeases)get(gpu, "geometryLeases");
            var original = new LodFarGeometry.Source((long)get(gpu, "farGeneration"), 1, 3, 4, 5,
                leases.epoch(), 2, 100, 100, -20, 300, 1, 2, 3, 4, 5, 6);
            check(gpu.captureFarRefresh(original) == null, "No live allocation protection must decline");
            var lease = gpu.acquireGeometryLease();
            long token = LodFarGeometry.retain(lease);
            check(token != 0, "Original lease registered");
            copyAndSource(gpu, original, live);
            invalidation(gpu, original);
            invalidView(gpu, original, live);
            for (String camera : cameras) {
                Object saved = get(gpu, camera);
                for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                    set(gpu, camera, invalid);
                    check(gpu.captureFarRefresh(original) == null, camera + " must be finite");
                }
                set(gpu, camera, saved);
            }
            Throwable[] workerFailure = {null};
            Thread worker = new Thread(() -> {
                try { gpu.captureFarRefresh(original); }
                catch (Throwable expected) { workerFailure[0] = expected; }
            }, "far-refresh-wrong-thread");
            worker.start(); worker.join();
            check(workerFailure[0] instanceof IllegalStateException, "Render thread required");

            // Full registry must still admit: refresh cannot consume a fourth lease.
            var second = gpu.acquireGeometryLease();
            var third = gpu.acquireGeometryLease();
            check(gpu.captureFarRefresh(original) != null && leases.count() == 3, "No extra lease");
            second.close(); third.close();
            // Pending deferred releases must not be drained as a side effect of camera capture.
            LodFarGeometry.deferredRelease(token);
            check(gpu.captureFarRefresh(original) != null && !lease.isClosed(), "Capture never drains releases");
            check(LodFarGeometry.drainDeferredReleases() == 1, "Caller releases original ownership");
            check(gpu.captureFarRefresh(original) == null, "Released final lease declines");

            lease = gpu.acquireGeometryLease();
            leases.retire(); // Even an unrelated work-buffer retirement conservatively rejects.
            check(gpu.captureFarRefresh(original) == null, "Changed retirement epoch declines");
            lease.close();
            saturatedEpoch(gpu, original);
        }
        System.out.println("LodFarRefreshTest: " + checks + " checks passed (CPU only; production capture API)");
    }

    private static void copyAndSource(LodGpu gpu, LodFarGeometry.Source original, ByteBuffer live) {
        byte[] before = new byte[128]; live.duplicate().get(before);
        LodFarRefresh refresh = gpu.captureFarRefresh(original);
        check(refresh != null, "Stable geometry with moving camera admitted");
        var expected = new LodFarGeometry.Source(original.generation(), 1, 3, 4, 5, original.allocationEpoch(),
            42, 9_000, 100, -20, 300, 10.25, 11.25, 12.25, 13.25, 14.25, 15.25);
        check(refresh.source().equals(expected), "Original identities with current camera/eye/time/frame");
        byte[] owned = refresh.view();
        check(owned.length == 128, "Exact view size");
        byte[] expectedView = before.clone();
        ByteBuffer.wrap(expectedView).order(ByteOrder.nativeOrder()).putInt(84, 15);
        check(Arrays.equals(owned, expectedView), "Exact complete view; only near occlusion removed");
        check(live.getInt(84) == 31, "Live flags unmodified");
        owned[0] ^= 127;
        check(Arrays.equals(refresh.view(), expectedView), "Accessor bytes independently owned");
        live.putFloat(0, 99.5f);
        check(Arrays.equals(refresh.view(), expectedView), "Later live writes cannot alter capture");
        LodFarRefresh next = gpu.captureFarRefresh(original);
        check(ByteBuffer.wrap(next.view()).order(ByteOrder.nativeOrder()).getFloat(0) == 99.5f,
            "Next camera view copied afresh");
        live.put(0, before);
    }

    private static void invalidation(LodGpu gpu, LodFarGeometry.Source original) throws Exception {
        check(gpu.captureFarRefresh(null) == null, "Null original declines");
        String[] flags = {"closed", "failed", "itemsDirty", "c0Set", "farViewReady"};
        for (String flag : flags) {
            boolean saved = (boolean)get(gpu, flag);
            set(gpu, flag, !saved);
            check(gpu.captureFarRefresh(original) == null, flag + " declines");
            set(gpu, flag, saved);
        }
        String[] identities = {"farGeneration", "geometryEpoch", "builds", "builtSelection", "visibilityRevision",
            "frame", "farViewFrame"};
        for (String identity : identities) {
            long saved = (long)get(gpu, identity);
            set(gpu, identity, saved + 1);
            check(gpu.captureFarRefresh(original) == null, identity + " mismatch declines");
            set(gpu, identity, saved);
        }
        for (String origin : new String[]{"c0x", "c0y", "c0z"}) {
            int saved = (int)get(gpu, origin);
            set(gpu, origin, saved + 4097);
            check(gpu.captureFarRefresh(original) == null, "Rebase without geometryEpoch change: " + origin);
            set(gpu, origin, saved);
        }
        long address = (long)get(gpu, "view");
        for (long invalid : new long[]{0, -1, Long.MAX_VALUE, Long.MAX_VALUE - 127}) {
            set(gpu, "view", invalid);
            check(gpu.captureFarRefresh(original) == null, "Invalid view address declined before read");
        }
        set(gpu, "view", address);
        @SuppressWarnings("unchecked") Map<Object, Object> fades = (Map<Object, Object>)get(gpu, "fades");
        fades.put(new Object(), new Object());
        check(gpu.captureFarRefresh(original) == null, "Active fades decline");
        fades.clear();
        check(gpu.captureFarRefresh(original) != null, "Restored stable state admits");
    }

    private static void invalidView(LodGpu gpu, LodFarGeometry.Source original, ByteBuffer live) {
        for (int flags = 0; flags < 64; flags++) {
            live.putInt(84, flags);
            check((gpu.captureFarRefresh(original) != null) == ((flags & 6) == 6 && (flags & ~31) == 0),
                "Production completeView flag admission " + flags);
        }
        live.putInt(84, 31);
        for (int word = 0; word < 32; word++) {
            if (word == 21 || word == 23) continue; // flags and reserved integer, per completeView
            float saved = live.getFloat(word * 4);
            for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
                live.putFloat(word * 4, invalid);
                check(gpu.captureFarRefresh(original) == null, "Nonfinite cull word " + word);
            }
            live.putFloat(word * 4, saved);
        }
        check(gpu.captureFarRefresh(original) != null, "Valid full view restored");
    }

    private static void saturatedEpoch(LodGpu gpu, LodFarGeometry.Source original) throws Exception {
        var closed = new LodGeometryLeases(RenderSystem::assertOnRenderThread, () -> {}, original.allocationEpoch());
        set(gpu, "geometryLeases", closed);
        var closingLease = closed.acquire();
        check(gpu.captureFarRefresh(original) != null, "Live matching allocation owner admits");
        closed.close();
        check(gpu.captureFarRefresh(original) == null, "Closed allocation owner declines even with live lease");
        closingLease.close();

        var saturated = new LodGeometryLeases(RenderSystem::assertOnRenderThread, () -> {}, Long.MAX_VALUE);
        set(gpu, "geometryLeases", saturated);
        var lease = saturated.acquire();
        var source = new LodFarGeometry.Source(original.generation(), 1, 3, 4, 5, Long.MAX_VALUE,
            2, 100, 100, -20, 300, 1, 2, 3, 4, 5, 6);
        check(gpu.captureFarRefresh(source) == null, "Saturated epoch cannot prove unchanged retirements");
        saturated.retire();
        check(gpu.captureFarRefresh(source) == null, "Saturated retirement remains declined");
        saturated.close();
        lease.close();
    }
}
