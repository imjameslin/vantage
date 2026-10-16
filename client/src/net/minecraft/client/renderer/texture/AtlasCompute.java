package net.minecraft.client.renderer.texture;

/**
 * Round 7 item 1 (docs/metal/CONTRACT.md 16.R7-2, results/ROUND7.md): on Metal, a ping-pong atlas tick writes its changed
 * animated sprites with a compute kernel on a side command buffer ({@code MetalTickFx.atlasAnimate}) instead of one render
 * pass per mip level, each of which loaded and stored the whole atlas level on the GPU's in-order fragment phase.
 *
 * <p>Toggle {@code -Dmc.texture.animCompute} (default on; {@code false} or {@code 0} = the render passes). Volatile, read
 * at every atlas tick: runtime-switchable (abab). Exact: the kernel reads the texel the passes' NEAREST sample hits and
 * mixes like animate_sprite_interpolate.fsh, tested byte-identical (tools/atlas-anim-test.sh, compute configs).
 */
public final class AtlasCompute {
    public static volatile boolean enabled = !"false".equalsIgnoreCase(System.getProperty("mc.texture.animCompute", "true"))
        && !"0".equals(System.getProperty("mc.texture.animCompute"));
    /** Atlas ticks done by the kernel so far (tests and diagnostics). */
    public static long ticks;

    private AtlasCompute() {
    }
}
