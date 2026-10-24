package dev.frostedfoliage.client.world;

import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Terrestrial ornamental plants. Crops, aquatic plants, fungi and potted models are separate systems. */
public final class PlantTypes {
    public static final int SINGLE = 0, LOWER = 1, UPPER = 2;
    private PlantTypes() { }

    public static boolean supported(Block block) {
        return block instanceof TallGrassBlock || block instanceof FlowerBlock || block instanceof TallFlowerBlock
                || block instanceof FlowerBedBlock || block instanceof BushBlock || block instanceof FireflyBushBlock
                || block instanceof SweetBerryBushBlock || block instanceof AzaleaBlock || block instanceof SaplingBlock
                || block instanceof DryVegetationBlock || block instanceof CactusFlowerBlock
                || block == Blocks.TALL_GRASS || block == Blocks.LARGE_FERN || block == Blocks.PITCHER_PLANT;
    }

    public static int part(BlockState state) {
        if (!state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) return SINGLE;
        return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER ? UPPER : LOWER;
    }
}
