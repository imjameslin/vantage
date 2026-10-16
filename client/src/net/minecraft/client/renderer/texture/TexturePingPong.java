package net.minecraft.client.renderer.texture;

/**
 * Round 6 item 1 (docs/metal/CONTRACT.md 16.R6-1, results/ROUND6-PLAN.md): double-buffer the textures written once per
 * client tick (the animated atlases: {@code TextureAtlas.uploadAnimationFramesPingPong}; the lightmap: {@code Lightmap}),
 * so the tick's update pass writes the copy no frame in flight samples and frame N never waits for frame N-1 to finish
 * sampling it (Metal orders a write after earlier reads of the same texture: the lost inter-frame overlap measured as
 * +0.73-0.99 ms GPU cadence on tick frames, results/GPU-HEAVY.md 2).
 *
 * <p>Toggle {@code -Dmc.texture.pingPong} (default on; {@code false} or {@code 0} = vanilla's in-place updates). Volatile,
 * read once per atlas tick and once per frame (lightmap, in {@code GameRenderer.extract}): runtime-switchable (abab).
 * Exact: every frame samples a complete copy holding exactly the state vanilla's single texture holds at that frame.
 */
public final class TexturePingPong {
    public static volatile boolean enabled = !"false".equalsIgnoreCase(System.getProperty("mc.texture.pingPong", "true"))
        && !"0".equals(System.getProperty("mc.texture.pingPong"));

    private TexturePingPong() {
    }
}
