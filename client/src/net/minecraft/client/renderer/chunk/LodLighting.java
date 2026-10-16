package net.minecraft.client.renderer.chunk;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.state.BlockState;

/** BlockModelLighter's cache for far terrain (LodRegion#cachedBrightness, {@code mc.lod.lightCache}, default true). */
public final class LodLighting {
    static final boolean ENABLED = Lod.flag("mc.lod.lightCache", true);

    public static final LightCoordsUtil.BrightnessGetter BRIGHTNESS = (level, pos) -> ((LodRegion)level).cachedBrightness(pos);

    private LodLighting() {
    }

    public static boolean cached(final BlockAndTintGetter level) {
        return ENABLED && level instanceof LodRegion r && r.lightCacheOn;
    }

    public static float shade(final BlockState state, final BlockAndTintGetter level, final BlockPos pos) {
        return ((LodRegion)level).cachedShade(state, pos);
    }
}
