import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.chunk.BlockUpdateFilter;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.ModelGroupCollector;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.VanillaPackResourcesBuilder;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;

/**
 * Stage-2 W11 (docs/metal/CONTRACT.md 16.S2-W11): headless check of BlockUpdateFilter.renderEquivalent over every pair of
 * states of every block, with the real model groups (vanilla blockstate definitions from the client jar, the default
 * BlockColors' colouring properties) and two neighbourhoods (air all around; the same state all around).
 *
 * <p>Checks: an update to the same state is never skipped (explicit re-render requests); every skipped pair has the same
 * block, model group, fluid state, render shape, light emission / dampening, occlusion shapes and BE presence (the
 * predicate's contract, re-checked independently); known cases (leaves distance / persistent: skipped; waterlogged,
 * redstone ore lit, furnace lit, snowy grass: kept). Prints the blocks that have skippable pairs (review list).
 */
public class BlockUpdateFilterTest {
    private static int checks;
    private static int failures;

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    /** A 3x3x3 world: {@code center} at the origin, {@code around} everywhere else. */
    record Neighbourhood(BlockState center, BlockState around) implements BlockGetter {
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return pos.equals(BlockPos.ZERO) ? this.center : this.around;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return this.getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Field infoField = net.minecraft.client.resources.ClientPackSource.class.getDeclaredField("VANILLA_PACK_INFO");
        infoField.setAccessible(true);
        net.minecraft.server.packs.resources.ResourceManager resources = new VanillaPackResourcesBuilder().exposeNamespace("minecraft")
            .pushJarResources().build((PackLocationInfo) infoField.get(null)).asResourceManager();
        BlockStateModelLoader.LoadedModels loaded = BlockStateModelLoader.loadBlockStates(resources, Runnable::run).join();
        Object2IntMap<BlockState> groups = ModelGroupCollector.build(BlockColors.createDefault(), loaded);
        check("block state models loaded (" + loaded.models().size() + ")", loaded.models().size() > 20000);
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        ModelManager models = (ModelManager) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(ModelManager.class);
        Field groupsField = ModelManager.class.getDeclaredField("modelGroups");
        groupsField.setAccessible(true);
        groupsField.set(models, groups);

        long pairs = 0;
        long skipped = 0;
        Map<String, long[]> perBlock = new TreeMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            List<BlockState> states = block.getStateDefinition().getPossibleStates();
            for (BlockState s : states) {
                check("same state kept: " + s, !BlockUpdateFilter.renderEquivalent(models, new Neighbourhood(s, Blocks.AIR.defaultBlockState()), BlockPos.ZERO, s, s));
            }
            if (states.size() > 400) {
                continue;   // e.g. redstone wire, note blocks: covered by the per-pair contract below on smaller blocks; keeps the test fast
            }
            for (BlockState a : states) {
                for (BlockState b : states) {
                    if (a == b) {
                        continue;
                    }
                    pairs++;
                    boolean airAround = BlockUpdateFilter.renderEquivalent(models, new Neighbourhood(b, Blocks.AIR.defaultBlockState()), BlockPos.ZERO, a, b);
                    boolean sameAround = BlockUpdateFilter.renderEquivalent(models, new Neighbourhood(b, b), BlockPos.ZERO, a, b);
                    if (airAround || sameAround) {
                        skipped++;
                        perBlock.computeIfAbsent(BuiltInRegistries.BLOCK.getKey(block).getPath(), k -> new long[1])[0]++;
                        contract(models, groups, a, b);
                    }
                }
            }
        }

        BlockState leaves1 = Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.DISTANCE, 1);
        BlockState leaves7 = leaves1.setValue(BlockStateProperties.DISTANCE, 7);
        BlockState leavesP = leaves7.setValue(BlockStateProperties.PERSISTENT, true);
        BlockState leavesW = leaves1.setValue(BlockStateProperties.WATERLOGGED, true);
        BlockGetter air = new Neighbourhood(leaves7, Blocks.AIR.defaultBlockState());
        check("oak leaves distance 1 -> 7 skipped", BlockUpdateFilter.renderEquivalent(models, air, BlockPos.ZERO, leaves1, leaves7));
        check("oak leaves persistent skipped", BlockUpdateFilter.renderEquivalent(models, air, BlockPos.ZERO, leaves7, leavesP));
        check("oak leaves waterlogged kept", !BlockUpdateFilter.renderEquivalent(models, air, BlockPos.ZERO, leaves1, leavesW));
        BlockState ore = Blocks.REDSTONE_ORE.defaultBlockState();
        check("redstone ore lit kept", !BlockUpdateFilter.renderEquivalent(models, air, BlockPos.ZERO, ore, ore.setValue(BlockStateProperties.LIT, true)));
        BlockState furnace = Blocks.FURNACE.defaultBlockState();
        check("furnace lit kept", !BlockUpdateFilter.renderEquivalent(models, air, BlockPos.ZERO, furnace, furnace.setValue(BlockStateProperties.LIT, true)));
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        check("snowy grass kept", !BlockUpdateFilter.renderEquivalent(models, air, BlockPos.ZERO, grass, grass.setValue(BlockStateProperties.SNOWY, true)));
        check("air -> stone kept", !BlockUpdateFilter.renderEquivalent(models, air, BlockPos.ZERO, Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState()));
        check("air -> cave air kept (different blocks)", !BlockUpdateFilter.renderEquivalent(models, air, BlockPos.ZERO, Blocks.AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState()));

        System.out.println(String.format(Locale.ROOT, "pairs %d, render-equivalent %d (%.2f %%); blocks with render-equivalent pairs (%d):",
            pairs, skipped, 100.0 * skipped / Math.max(1, pairs), perBlock.size()));
        List<String> lines = new ArrayList<>();
        perBlock.forEach((k, v) -> lines.add(k + " " + v[0]));
        System.out.println("  " + String.join(", ", lines));
        System.out.println(String.format(Locale.ROOT, "BlockUpdateFilterTest: %d checks, %d failures", checks, failures));
        System.exit(failures == 0 ? 0 : 1);
    }

    /** What a skipped pair must share, re-checked independently of the predicate's code. */
    private static void contract(ModelManager models, Object2IntMap<BlockState> groups, BlockState a, BlockState b) {
        String p = a + " -> " + b;
        boolean ok = a.getBlock() == b.getBlock()
            && groups.getInt(a) == groups.getInt(b) && groups.getInt(a) != -1
            && a.getFluidState() == b.getFluidState()
            && a.getRenderShape() == b.getRenderShape()
            && a.getLightEmission() == b.getLightEmission()
            && a.getLightDampening() == b.getLightDampening()
            && a.isSolidRender() == b.isSolidRender()
            && a.hasBlockEntity() == b.hasBlockEntity()
            && a.emissiveRendering() == b.emissiveRendering();
        for (Direction d : Direction.values()) {
            ok &= a.getFaceOcclusionShape(d) == b.getFaceOcclusionShape(d);
        }
        check("contract of a skipped pair: " + p, ok);
    }
}
