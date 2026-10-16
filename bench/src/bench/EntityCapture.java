package bench;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.EntityType;

/**
 * Which entities a frame really drew (scenes mode only; BenchAgent.entityCaptureHook makes
 * {@code LevelRenderer.submitEntities} call {@link #onSubmit} first). LevelRenderer clears the entity render states right
 * after submitting them, so the bench cannot read them at the top of the next frame. While {@link #armed}, every submit
 * overwrites the snapshot: read at the top of a frame, it describes the previous frame, the one Screenshot.grab captures.
 * Render thread only.
 */
public final class EntityCapture {
    static boolean armed;
    /** Submits seen while armed (0 = hook not installed or never armed). */
    static int captures;
    static EntityType<?>[] types = new EntityType<?>[0];
    static double[] xs = new double[0], ys = new double[0], zs = new double[0];
    static int count;

    private EntityCapture() {
    }

    public static void onSubmit(final LevelRenderState state) {
        if (!armed) {
            return;
        }
        int n = state.entityRenderStates.size();
        if (types.length < n) {
            types = new EntityType<?>[n];
            xs = new double[n];
            ys = new double[n];
            zs = new double[n];
        }
        for (int i = 0; i < n; i++) {
            EntityRenderState s = state.entityRenderStates.get(i);
            types[i] = s.entityType;
            xs[i] = s.x;
            ys[i] = s.y;
            zs[i] = s.z;
        }
        count = n;
        captures++;
    }

    /** True when the last captured frame drew an entity of this type at this position (within 0.05 blocks per axis). */
    static boolean drawn(final EntityType<?> type, final double x, final double y, final double z) {
        for (int i = 0; i < count; i++) {
            if (types[i] == type && Math.abs(xs[i] - x) < 0.05 && Math.abs(ys[i] - y) < 0.05 && Math.abs(zs[i] - z) < 0.05) {
                return true;
            }
        }
        return false;
    }
}
