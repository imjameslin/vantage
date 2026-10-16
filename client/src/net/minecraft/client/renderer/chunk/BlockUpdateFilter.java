package net.minecraft.client.renderer.chunk;

import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stage-2 W11 (docs/metal/CONTRACT.md 16.S2-W11): skip the section rebuild of a block update whose old and new states
 * mesh identically ("render-equivalent"), e.g. the leaves {@code distance} updates a server sends by the tens of
 * thousands while terrain is generated (88 % of all recompiles in the travel bench were byte-identical meshes, 93 % of the
 * block-caused ones; results/STAGE2-R1.md).
 *
 * <p>Vanilla's client marks the 3x3x3 neighbourhood dirty twice for a block update: through {@code Level.setBlocksDirty}
 * (filtered by {@link ModelManager#requiresRender}) and through {@code ClientLevel.sendBlockUpdated} (unfiltered). Only the
 * second is skipped, and only when {@link #renderEquivalent} proves that every input the section compiler reads about
 * the block at {@code pos} is the same for both states, so no mesh in the neighbourhood can change:
 * <ul>
 * <li>the model and its tint: same block, same model group and fluid state ({@code !requiresRender}: the visual equality
 * group of the unbaked model plus the block colour's colouring properties);</li>
 * <li>section compiler / VisGraph / block entities: render shape, solid render, block entity presence;</li>
 * <li>lighting of the mesh (BlockModelLighter / LightCoordsUtil): emissive rendering, light emission, shade brightness,
 * full collision shape (AO), light dampening and sky-light propagation (the light values themselves come from the light
 * engine, whose updates dirty sections on their own path);</li>
 * <li>face culling (Block.shouldRenderFace, FluidRenderer): the face occlusion shapes (by identity: conservative), and
 * {@code skipRendering} both ways against the six neighbours actually present;</li>
 * <li>the model offset at {@code pos}.</li>
 * </ul>
 * Anything not proven equal keeps vanilla's rebuild. An update with {@code old == current} is an explicit re-render request
 * (block entities such as signs and campfires, and mods, call {@code sendBlockUpdated(pos, s, s, flags)} for that) and is
 * always kept. Client-only and invisible to servers (no packet changes).
 *
 * <p>Modes ({@code -Dmc.chunk.sameRenderSkip}): {@code true} (default) skips; {@code false} = vanilla; {@code verify} does
 * not skip but tags those marks as source {@link ChunkStats#SRC_SAME_RENDER} so the counters can prove the recompiles they
 * cause are byte-identical (needs {@code -Dmc.chunkStats=true}). {@link #enabled} is runtime-switchable (A/B).
 */
public final class BlockUpdateFilter {
    private static final String MODE = System.getProperty("mc.chunk.sameRenderSkip", "true");
    public static final boolean VERIFY = "verify".equals(MODE);
    /** Runtime switch (render thread reads it once per block update). */
    public static volatile boolean enabled = "true".equals(MODE);
    private static final Direction[] DIRECTIONS = Direction.values();

    private BlockUpdateFilter() {
    }

    /** True when the update old -> current at pos cannot change any section mesh (see the class comment). */
    public static boolean renderEquivalent(
        final ModelManager models, final BlockGetter level, final BlockPos pos, final BlockState old, final BlockState current
    ) {
        // old == current is an explicit re-render request (sign / campfire / shelf block entities, and mods force a rebuild
        // this way): always keep it.
        if (old == current || old.getBlock() != current.getBlock() || models.requiresRender(old, current)) {
            return false;
        }

        if (old.getRenderShape() != current.getRenderShape()
            || old.isSolidRender() != current.isSolidRender()
            || old.canOcclude() != current.canOcclude()
            || old.hasBlockEntity() != current.hasBlockEntity()
            || old.emissiveRendering() != current.emissiveRendering()
            || old.getLightEmission() != current.getLightEmission()
            || old.getLightDampening() != current.getLightDampening()
            || old.propagatesSkylightDown() != current.propagatesSkylightDown()
            || old.useShapeForLightOcclusion() != current.useShapeForLightOcclusion()
            || old.getOcclusionShape() != current.getOcclusionShape()) {
            return false;
        }

        for (Direction direction : DIRECTIONS) {
            if (old.getFaceOcclusionShape(direction) != current.getFaceOcclusionShape(direction)) {
                return false;
            }
        }

        if (Float.compare(old.getShadeBrightness(level, pos), current.getShadeBrightness(level, pos)) != 0
            || old.isCollisionShapeFullBlock(level, pos) != current.isCollisionShapeFullBlock(level, pos)
            || !old.getOffset(pos).equals(current.getOffset(pos))) {
            return false;
        }

        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        for (Direction direction : DIRECTIONS) {
            BlockState neighbor = level.getBlockState(neighborPos.setWithOffset(pos, direction));
            if (old.skipRendering(neighbor, direction) != current.skipRendering(neighbor, direction)
                || neighbor.skipRendering(old, direction.getOpposite()) != neighbor.skipRendering(current, direction.getOpposite())) {
                return false;
            }
        }

        return true;
    }
}
