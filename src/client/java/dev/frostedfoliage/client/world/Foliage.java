package dev.frostedfoliage.client.world;

import dev.frostedfoliage.config.SnowConfig;
import dev.frostedfoliage.core.FoliageRules;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class Foliage {
    private volatile Set<Block> blocks = Set.of();
    private volatile Set<Block> groundPlants = Set.of();
    public void refresh(SnowConfig config, boolean tagsBound) {
        blocks = BuiltInRegistries.BLOCK.stream().filter(block -> FoliageRules.includes(
                BuiltInRegistries.BLOCK.getKey(block).toString(),
                block instanceof LeavesBlock || (tagsBound && block.defaultBlockState().is(BlockTags.LEAVES)),
                PlantTypes.supported(block), config))
                .collect(Collectors.toUnmodifiableSet());
        groundPlants = BuiltInRegistries.BLOCK.stream().filter(PlantTypes::supported)
                .filter(block -> FoliageRules.includes(BuiltInRegistries.BLOCK.getKey(block).toString(), true, config))
                .collect(Collectors.toUnmodifiableSet());
    }
    /** Built-in leaf/plant classes are always discovered; only these additions depend on rules/tags. */
    public Set<Block> extraLeafBlocks() {
        return blocks.stream().filter(block -> !(block instanceof LeavesBlock) && !PlantTypes.supported(block))
                .collect(Collectors.toUnmodifiableSet());
    }
    public boolean test(BlockState state) { return blocks.contains(state.getBlock()); }
    public boolean isPlant(BlockState state) { return PlantTypes.supported(state.getBlock()); }
    public boolean groundPlant(BlockState state) { return groundPlants.contains(state.getBlock()); }
}
