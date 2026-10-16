package net.minecraft.client.renderer;

/**
 * Round 7 item 1 (docs/metal/CONTRACT.md 16.R7-1, results/ROUND7.md): on Metal, the per-tick lightmap update runs as a
 * compute kernel on a side command buffer ({@code MetalTickFx}) instead of vanilla's render pass. Vanilla samples the
 * lightmap in the vertex stage, so the render pass made the next world pass's vertex work wait for the previous frame's
 * fragment work (the "tick-frame tail"). Only with the ping-pong lightmap swap armed (CONTRACT 16.R6-1).
 *
 * <p>Toggle {@code -Dmc.lightmap.compute} (default on; {@code false} or {@code 0} = the render pass). Volatile, read at every
 * lightmap update: runtime-switchable (abab). Exact: the kernel is lightmap.fsh's arithmetic, tested byte-identical to
 * the render pass (tools/lightmap-compute-test.sh).
 */
public final class LightmapCompute {
    public static volatile boolean enabled = !"false".equalsIgnoreCase(System.getProperty("mc.lightmap.compute", "true"))
        && !"0".equals(System.getProperty("mc.lightmap.compute"));

    /** Kernel updates so far (tests and the bench config check that the kernel, not the render pass, ran). */
    public static long dispatches;

    private LightmapCompute() {
    }
}
