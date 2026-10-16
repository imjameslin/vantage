package net.minecraft.world.level.levelgen.material;

import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import org.jspecify.annotations.Nullable;

/**
 * Stage 3 r3 (seed-based LOD generation, client/.../chunk/LodGen; CONTRACT 16.S3-LOD-R3): vanilla's surface rules evaluated
 * for single columns, the way {@link MaterialSystem#buildSurface} does per block (same context fields: surface depth noise,
 * gradients, stone depth above / below, water height), without a chunk. New class in vanilla's package only for the
 * package-private {@link MaterialRuleContext} constructor and updates; no vanilla class or signature is changed.
 */
public final class LodMaterial {
    private final MaterialRuleContext context;
    private final RuleEvaluator rule;
    private int x;
    private int z;

    public LodMaterial(final RandomState randomState, final DensitySamplerSet samplers, final Function<BlockPos, Holder<Biome>> biomeGetter,
        final WorldGenerationContext generationContext, final MaterialRule rule, final int anyX, final int anyZ) {
        this.context = new MaterialRuleContext(randomState.surfaceSystem(), randomState, new DensityVolume(1, 1, 1, anyX, 0, anyZ), samplers, biomeGetter,
            generationContext, null);
        this.rule = rule.compile(this.context);
    }

    /** Starts a column (surface gradients in blocks per two blocks, as MaterialSystem computes them from the heightmap). */
    public void column(final int blockX, final int blockZ, final int gradientX, final int gradientZ) {
        this.x = blockX;
        this.z = blockZ;
        this.context.updateXZ(blockX, blockZ, gradientX, gradientZ);
    }

    /** The rule's block at {@code blockY} of the current column (null = keep the default block). */
    public @Nullable BlockState at(final int blockY, final int stoneDepthAbove, final int stoneDepthBelow, final int waterHeight) {
        this.context.updateY(stoneDepthAbove, stoneDepthBelow, waterHeight, blockY);
        return this.rule.tryApply(this.x, blockY, this.z);
    }
}
