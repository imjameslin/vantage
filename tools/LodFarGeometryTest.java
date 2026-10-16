package net.minecraft.client.renderer.chunk;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.util.ArrayList;
import java.util.Arrays;

/** Bounded CPU-only fixture: production item/view validation, immutable bytes and cross-world release policy. */
public final class LodFarGeometryTest {
    private static int checks;
    private static final Thread RENDER_THREAD = Thread.currentThread();

    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Check " + checks);
    }

    private static void rejects(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); }
        catch (Throwable failure) {
            check(type.isInstance(failure));
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void assertRenderThread() {
        if (Thread.currentThread() != RENDER_THREAD) throw new IllegalStateException("Not render thread");
    }

    private record Buffer(long size) implements GpuBuffer {
        @Override public int usage() { return GpuBuffer.USAGE_VERTEX; }
        @Override public boolean isClosed() { return false; }
        @Override public void close() { throw new AssertionError("Snapshot must not close buffers"); }
        @Override public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
            throw new AssertionError("CPU fixture must not map GPU storage");
        }
    }

    public static void main(String[] args) throws Exception {
        itemBoundsAndOwnership();
        viewCopy();
        releaseAcrossWorldClose();
        System.out.println("LodFarGeometryTest: " + checks + " checks passed (CPU only)");
    }

    private static void itemBoundsAndOwnership() {
        // Includes the highest legal section slot and a 64-quad cluster at the end of an arena.
        for (int stride : new int[]{48, 112}) {
            long arenaBytes = 256L << 20;
            int end = (int)(arenaBytes / stride);
            int[] items = {0, 3, 123, 456, (end - 64) * 4, 0xFFFFF | (63 << 20), 789, 1011};
            check(LodFarGeometry.validItems(items, 2, 65, stride, arenaBytes, 1 << 20));
            check(!LodFarGeometry.validItems(items, 2, 64, stride, arenaBytes, 1 << 20));
            check(!LodFarGeometry.validItems(items, Integer.MAX_VALUE, 65, stride, arenaBytes, 1 << 20));
            check(!LodFarGeometry.validItems(items, -1, 65, stride, arenaBytes, 1 << 20));
            check(!LodFarGeometry.validItems(items, 2, 65, stride, arenaBytes, (1 << 20) - 1));
            check(!LodFarGeometry.validItems(items, 2, 65, stride, (long)end * stride - 1, 1 << 20));
            int[] bad = items.clone();
            bad[0] = -4; // unsigned input offset must not underflow into the buffer
            check(!LodFarGeometry.validItems(bad, 2, 65, stride, arenaBytes, 1 << 20));
            bad[0] = 1;
            check(!LodFarGeometry.validItems(bad, 2, 65, stride, arenaBytes, 1 << 20));
            var vertices = new Buffer(arenaBytes + 128).slice(128, arenaBytes);
            var indices = new Buffer(65 * 24 + 64).slice(64, 65 * 24);
            var input = new LodFarGeometry.Input(items, 2, 65, stride == 48 ? 8 : 0, 7, vertices, indices);
            items[0] = 999;
            check(input.items().getInt(0) == 0 && input.items().getInt(16) == (end - 64) * 4);
            check(input.clusterCount() == 2 && input.quadCount() == 65);
            check(input.vertices() == vertices && input.vertices().offset() == 128);
            check(input.indices() == indices && input.indices().offset() == 64);
            rejects(ReadOnlyBufferException.class, () -> input.items().putInt(0, 1));
            input.items().position(8);
            check(input.items().position() == 0 && input.items().order() == ByteOrder.nativeOrder());
            check(input.faceBase() == LodFarGeometry.faceBase(7));
        }
        for (int arena = 0; arena < 63; arena++) {
            check((long)LodFarGeometry.faceBase(arena) + LodFarGeometry.FACE_SPAN
                == LodFarGeometry.faceBase(arena + 1));
        }
        check((long)LodFarGeometry.faceBase(63) + LodFarGeometry.FACE_SPAN <= 0xFFFFFFFFL);
        rejects(IllegalArgumentException.class, () -> LodFarGeometry.faceBase(64));
        rejects(IllegalArgumentException.class, () -> LodFarGeometry.copyItems(new int[4], Integer.MAX_VALUE));
    }

    private static void viewCopy() {
        byte[] live = new byte[128];
        ByteBuffer v = ByteBuffer.wrap(live).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 32; i++) v.putFloat(i * 4, i + 0.25f);
        v.putInt(84, 31); // includes section-occlusion, cleared only in the owned copy
        byte[] owned = live.clone();
        check(LodFarGeometry.completeView(owned));
        check(ByteBuffer.wrap(owned).order(ByteOrder.nativeOrder()).getInt(84) == 15 && v.getInt(84) == 31);
        for (int i = 0; i < 128; i++) if (i < 84 || i >= 88) check(live[i] == owned[i]);
        v.putInt(84, 2);
        check(!LodFarGeometry.completeView(live));
        v.putInt(84, 63);
        check(!LodFarGeometry.completeView(live));
        v.putInt(84, 7).putFloat(96, Float.NaN);
        check(!LodFarGeometry.completeView(live));
        check(!LodFarGeometry.completeView(new byte[127]));

        byte[] cull = new byte[64], shader = new byte[32];
        Arrays.fill(cull, (byte)7); Arrays.fill(shader, (byte)9);
        var list = new ArrayList<LodFarGeometry.Input>();
        var source = new LodFarGeometry.Source(10, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 1.25, 2.5, 3.75, 4, 5, 6);
        var snapshot = new LodFarGeometry(source, list, cull, shader, owned, 99, 224, 123);
        check(snapshot.sectionCount() == 2 && snapshot.metadataBytes() == 224 && snapshot.captureNanos() == 123);
        check(snapshot.source().cameraX() == 1.25 && snapshot.releaseToken() == 99);
        list.add(null);
        check(snapshot.inputs().isEmpty());
        rejects(UnsupportedOperationException.class, () -> snapshot.inputs().clear());
        rejects(ReadOnlyBufferException.class, () -> snapshot.cullSections().put(0, (byte)0));
        rejects(ReadOnlyBufferException.class, () -> snapshot.shaderSections().put(0, (byte)0));
        rejects(ReadOnlyBufferException.class, () -> snapshot.view().put(0, (byte)0));
    }

    private static void releaseAcrossWorldClose() throws Exception {
        var releases = new LodFarGeometry.Releases(LodFarGeometryTest::assertRenderThread);
        int[] disposed = {0};
        var oldWorld = new LodGeometryLeases(LodFarGeometryTest::assertRenderThread, () -> disposed[0]++);
        var newWorld = new LodGeometryLeases(LodFarGeometryTest::assertRenderThread, () -> disposed[0]++);
        var first = oldWorld.acquire();
        long a = releases.add(first), b = releases.add(oldWorld.acquire()), c = releases.add(newWorld.acquire());
        var refused = newWorld.acquire();
        check(a > 0 && b > a && c > b && releases.add(refused) == 0);
        refused.close();
        rejects(IllegalArgumentException.class, () -> releases.add(first));
        oldWorld.close(); // No future old-world frames. Registry must keep this owner alive.
        Throwable[] error = {null};
        Thread worker = new Thread(() -> {
            try {
                releases.defer(b); releases.defer(b); releases.defer(-1); releases.defer(Long.MAX_VALUE);
                rejects(IllegalStateException.class, releases::drain);
            } catch (Throwable failure) { error[0] = failure; }
        }, "fake-native-release-poll");
        worker.start(); worker.join();
        if (error[0] != null) throw new AssertionError(error[0]);
        check(oldWorld.count() == 2 && disposed[0] == 0);
        check(releases.drain() == 1 && oldWorld.count() == 1 && disposed[0] == 0);
        long replacement = releases.add(newWorld.acquire());
        check(replacement > c);
        releases.defer(b); // Stale token must not release the reused slot.
        check(releases.drain() == 0 && newWorld.count() == 2);
        releases.defer(a);
        check(releases.drain() == 1 && oldWorld.disposed() && disposed[0] == 1);
        newWorld.close();
        releases.defer(c); releases.defer(replacement);
        check(releases.drain() == 2 && newWorld.disposed() && disposed[0] == 2);
        releases.defer(a); releases.defer(c);
        check(releases.drain() == 0);
    }
}
