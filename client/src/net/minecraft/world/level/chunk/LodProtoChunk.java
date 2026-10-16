package net.minecraft.world.level.chunk;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;

/**
 * A proto-chunk generated only for Farsight's far terrain (LodExactGen): never saved or loaded as a level chunk. The generator
 * skips what can't be seen from afar for these (ores and underground decoration), see ChunkGenerator.applyBiomeDecoration.
 */
public final class LodProtoChunk extends ProtoChunk {
    public LodProtoChunk(final ChunkPos chunkPos, final LevelHeightAccessor levelHeightAccessor, final PalettedContainerFactory containerFactory) {
        super(chunkPos, UpgradeData.EMPTY, levelHeightAccessor, containerFactory, null);
    }
}
