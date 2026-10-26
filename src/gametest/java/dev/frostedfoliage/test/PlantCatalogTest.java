package dev.frostedfoliage.test;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.client.world.PlantTypes;
import dev.frostedfoliage.config.SnowConfig;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Exercise every supported vanilla plant and its dry ground model variants, not just the showcase garden. */
public final class PlantCatalogTest {
    private record Probe(Block block, BlockPos pos, int part) { }

    public void run(ClientGameTestContext context) {
        var config = SnowConfig.builder().maximumCoverage(0).snowDepth(0).accumulationSeconds(8).meltingSeconds(6)
                .quality(SnowConfig.Quality.HIGH).maxVisualUpdatesPerSecond(2)
                .plantsEnabled(true).plantSnowDepth(1).plantMaximumCoverage(1).build();
        var reload = context.computeOnClient(c -> FrostedFoliageClient.applyConfig(config));
        context.waitFor(c -> reload.isDone() && c.gui.overlay() == null, 1200);
        reload.join();
        var probes = new ArrayList<Probe>();
        try (var world = context.worldBuilder().adjustSettings(s -> s.setName("Frosted Foliage - Plant Catalog")).create()) {
            var server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 18 77 22 145 30");
            server.runCommand("time set noon");
            server.runCommand("gamerule minecraft:advance_time false");
            server.runCommand("gamerule minecraft:random_tick_speed 0");
            server.runCommand("gamerule minecraft:max_snow_accumulation_height 0");
            server.runCommand("weather clear");
            server.runCommand("fill -28 62 -28 28 62 16 minecraft:dirt");
            server.runCommand("fill -28 63 -28 28 63 16 minecraft:grass_block");
            for (int x = -32; x <= 16; x += 16) for (int z = -32; z <= 16; z += 16) {
                server.runCommand("fillbiome " + x + " 48 " + z + " " + (x + 15) + " 95 " + (z + 15) + " minecraft:snowy_taiga");
            }
            List<Block> types = BuiltInRegistries.BLOCK.stream().filter(b -> PlantTypes.supported(b)
                    && BuiltInRegistries.BLOCK.getKey(b).getNamespace().equals("minecraft")).toList();
            server.runOnServer(s -> {
                for (int i = 0; i < types.size(); i++) {
                    Block block = types.get(i);
                    BlockPos pos = new BlockPos(-24 + i % 12 * 4, 64, -24 + i / 12 * 4);
                    BlockState state = block.defaultBlockState();
                    if (block == Blocks.CACTUS_FLOWER) {
                        s.overworld().setBlock(pos.below(), Blocks.SAND.defaultBlockState(), 3);
                        s.overworld().setBlock(pos, Blocks.CACTUS.defaultBlockState(), 3);
                        pos = pos.above();
                    }
                    if (block instanceof DoublePlantBlock) {
                        DoublePlantBlock.placeAt(s.overworld(), state, pos, 3);
                        probes.add(new Probe(block, pos.above(), PlantTypes.UPPER));
                    } else s.overworld().setBlock(pos, state, 3);
                    probes.add(new Probe(block, pos, PlantTypes.part(state)));
                }
                FrostedFoliageClient.LOGGER.info("PLANT CATALOG generated {} supported vanilla plant types", types.size());
            });
            context.waitTicks(80);
            world.getConnection().waitForChunksRender(false, 1200);
            context.runOnClient(c -> {
                if (!c.gui.hud.isHidden()) c.gui.hud.toggle();
                for (Probe p : probes) if (!c.level.getBlockState(p.pos()).is(p.block())) throw new AssertionError("Plant failed to survive placement: " + p);
            });
            server.runCommand("weather rain 100000");
            context.waitFor(c -> probes.stream().allMatch(p -> FrostedFoliageClient.system().coverage(p.pos(), true) >= 0.99), 1200);
            context.waitFor(c -> FrostedFoliageClient.system().diagnostics().contains("pending=0/0 "), 1200);
            int checked = context.computeOnClient(c -> {
                int variants = 0;
                for (Probe p : probes) for (BlockState state : p.block().getStateDefinition().getPossibleStates()) {
                    if (PlantTypes.part(state) != p.part()) continue;
                    if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) continue;
                    if (state.hasProperty(BlockStateProperties.HANGING) && state.getValue(BlockStateProperties.HANGING)) continue;
                    int snow = snowPixels(c, p.pos(), state);
                    if (snow == 0) throw new AssertionError("Plant model has no visible snow: " + state);
                    variants++;
                }
                return variants;
            });
            world.getConnection().waitForChunksRender(false, 1200);
            context.takeScreenshot(TestScreenshotOptions.of("plants-all-types").withSize(1440, 900));
            FrostedFoliageClient.LOGGER.info("PLANT CATALOG PASS types={} states={} at full plant coverage while leaf coverage is zero", types.size(), checked);
        }
        if (!context.computeOnClient(c -> FrostedFoliageClient.system().diagnostics().contains("snapshots=0"))) throw new AssertionError("Plant world leaked snapshots on disconnect");
    }

    private static int snowPixels(Minecraft c, BlockPos pos, BlockState state) {
        var model = c.getModelManager().getBlockStateModelSet().get(state);
        var atlas = (net.minecraft.client.renderer.texture.TextureAtlas) c.getTextureManager()
                .getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
        int[] count = {0};
        var emitter = net.fabricmc.fabric.api.client.renderer.v1.Renderer.get().quadEmitter(q -> {
            var sprite = atlas.spriteFinder().find(q);
            if (!sprite.contents().name().getPath().startsWith("generated/plants/")) return;
            if (!sprite.contents().name().getNamespace().equals("frostedfoliage")) return;
            for (int y = 0; y < sprite.contents().height(); y++) for (int x = 0; x < sprite.contents().width(); x++) {
                if (!sprite.contents().isTransparent(0, x, y)) count[0]++;
            }
        });
        model.emitQuads(emitter, c.level, pos, state, RandomSource.create(1), direction -> false);
        return count[0];
    }
}
