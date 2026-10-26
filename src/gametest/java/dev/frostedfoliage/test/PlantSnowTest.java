package dev.frostedfoliage.test;

import dev.frostedfoliage.client.FrostedFoliageClient;
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

/** Compare real vanilla precipitation with the client overlay in a garden of actual plant models. */
public final class PlantSnowTest {
    private record Plant(BlockPos pos, BlockState state) { }
    private record Mesh(int quads, int snowQuads, int snowPixels, int lowerPixels) { }
    private static final BlockPos LEAF = new BlockPos(12, 66, 0), ROOF = new BlockPos(-18, 64, 4), WARM = new BlockPos(18, 64, 4), WET = new BlockPos(-18, 64, 8);

    public void run(ClientGameTestContext context) {
        var plants = new ArrayList<Plant>();
        context.runOnClient(c -> {
            c.options.renderDistance().set(10);
            c.options.pauseOnLostFocus = false;
            c.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
        });
        // applyConfig, not configure: a launcher instance may start with other depths, and masks must match the test's.
        apply(context, config(false, 0));
        try (var world = context.worldBuilder().adjustSettings(s -> s.setName("Frosted Foliage - Winter Plants")).create()) {
            var server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a -1.5 66 16 180 16");
            server.runCommand("time set noon");
            server.runCommand("gamerule minecraft:advance_time false");
            server.runCommand("gamerule minecraft:random_tick_speed 0");
            server.runCommand("gamerule minecraft:max_snow_accumulation_height 1");
            server.runCommand("weather clear");
            server.runCommand("fill -24 62 -16 24 62 24 minecraft:dirt");
            server.runCommand("fill -24 63 -16 24 63 24 minecraft:grass_block");
            for (int x = -32; x <= 16; x += 16) for (int z = -16; z <= 16; z += 16) {
                server.runCommand("fillbiome " + x + " 48 " + z + " " + (x + 15) + " 95 " + (z + 15) + " minecraft:snowy_taiga");
            }
            server.runOnServer(s -> {
                List<Block> types = List.of(Blocks.LARGE_FERN, Blocks.TALL_GRASS, Blocks.ROSE_BUSH, Blocks.SUNFLOWER,
                        Blocks.LILAC, Blocks.PEONY, Blocks.AZALEA, Blocks.FLOWERING_AZALEA, Blocks.FIREFLY_BUSH,
                        Blocks.SWEET_BERRY_BUSH, Blocks.RED_SHRUB, Blocks.BUSH, Blocks.FERN, Blocks.SHORT_GRASS,
                        Blocks.POPPY, Blocks.DANDELION, Blocks.PINK_PETALS, Blocks.WILDFLOWERS);
                for (int i = 0; i < types.size(); i++) {
                    Block block = types.get(i);
                    BlockPos pos = new BlockPos(-9 + i % 6 * 3, 64, i / 6 * 4);
                    BlockState state = block.defaultBlockState();
                    if (state.hasProperty(BlockStateProperties.AGE_3)) state = state.setValue(BlockStateProperties.AGE_3, 3);
                    if (state.hasProperty(BlockStateProperties.FLOWER_AMOUNT)) state = state.setValue(BlockStateProperties.FLOWER_AMOUNT, 4);
                    if (block instanceof DoublePlantBlock) {
                        DoublePlantBlock.placeAt(s.overworld(), state, pos, 3);
                        plants.add(new Plant(pos.above(), s.overworld().getBlockState(pos.above())));
                    } else s.overworld().setBlock(pos, state, 3);
                    plants.add(new Plant(pos, s.overworld().getBlockState(pos)));
                }
                s.overworld().setBlock(LEAF, Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true), 3);
                s.overworld().setBlock(ROOF, Blocks.FERN.defaultBlockState(), 3);
                s.overworld().setBlock(ROOF.above(3), Blocks.STONE.defaultBlockState(), 3);
                s.overworld().setBlock(WARM, Blocks.FERN.defaultBlockState(), 3);
                for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) s.overworld().setBlock(WET.relative(direction), Blocks.STONE.defaultBlockState(), 3);
                s.overworld().setBlock(WET, Blocks.MANGROVE_PROPAGULE.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true), 3);
            });
            server.runCommand("fillbiome 16 60 4 19 71 7 minecraft:plains");
            context.waitTicks(80);
            world.getConnection().waitForChunksRender(false, 1200);
            if (Boolean.getBoolean("frostedfoliage.test.shaderExpected")
                    && !context.computeOnClient(c -> net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse())) {
                throw new AssertionError("Requested shader pack is not active");
            }
            context.runOnClient(c -> { if (!c.gui.hud.isHidden()) c.gui.hud.toggle(); });
            List<String> before = context.computeOnClient(c -> plants.stream().map(p -> geometry(c, p)).toList());
            context.takeScreenshot(TestScreenshotOptions.of("plants-00-vanilla-dry").withSize(1440, 900));
            server.runCommand("weather rain 100000");
            context.waitTicks(240);
            // Exercise Minecraft's own precipitation routine deterministically at each garden column.
            server.runOnServer(s -> {
                for (int x = -24; x <= 24; x++) for (int z = -16; z <= 24; z++) {
                    s.overworld().tickPrecipitation(new BlockPos(x, 64, z));
                }
                for (Plant p : plants) if (!s.overworld().getBlockState(p.pos()).equals(p.state())) {
                    throw new AssertionError("Vanilla snowfall changed the plant block: " + p);
                }
                if (!s.overworld().getBlockState(new BlockPos(12, 64, 8)).is(Blocks.SNOW)) {
                    throw new AssertionError("Vanilla snowfall did not place the adjacent ground snow control");
                }
            });
            context.waitTicks(30);
            world.getConnection().waitForChunksRender(false, 1200);
            List<String> after = context.computeOnClient(c -> plants.stream().map(p -> geometry(c, p)).toList());
            if (!before.equals(after)) throw new AssertionError("Vanilla plant geometry or texture changed during snowfall");
            context.runOnClient(c -> {
                for (Plant p : plants) if (FrostedFoliageClient.system().coverage(p.pos()) != 0) throw new AssertionError("Disabled mod added snow");
            });
            context.takeScreenshot(TestScreenshotOptions.of("plants-01-vanilla-snowfall").withSize(1440, 900));
            server.runCommand("weather clear");
            context.waitTicks(240);
            context.takeScreenshot(TestScreenshotOptions.of("plants-02-vanilla-after").withSize(1440, 900));
            FrostedFoliageClient.LOGGER.info("VANILLA PLANTS PASS types=18 states={} unchanged through snowfall; adjacent ground accumulated snow", plants.size());
            if (Boolean.getBoolean("frostedfoliage.test.vanillaPlants")) return;

            apply(context, config(true, 30));
            server.runCommand("weather rain 100000");
            context.waitFor(c -> FrostedFoliageClient.system().coverage(LEAF) >= 0.99, 1200);
            settle(context);
            context.runOnClient(c -> {
                for (Plant p : plants) {
                    Mesh mesh = mesh(c, p);
                    boolean lower = dev.frostedfoliage.client.world.PlantTypes.part(p.state()) == dev.frostedfoliage.client.world.PlantTypes.LOWER;
                    if (!lower && mesh.snowPixels() == 0) throw new AssertionError("Missing default plant frost: " + p);
                    if (lower && mesh.lowerPixels() != 0) throw new AssertionError("Tall plant was frosted independently in its lower half: " + p);
                    FrostedFoliageClient.LOGGER.info("PLANT DEFAULT PASS {} quads={} snowPixels={} coverage={}", p.state(), mesh.quads(), mesh.snowPixels(), FrostedFoliageClient.system().coverage(p.pos(), true));
                }
                for (BlockPos sheltered : List.of(ROOF, WARM, WET)) {
                    if (FrostedFoliageClient.system().coverage(sheltered, true) != 0 || mesh(c, new Plant(sheltered, c.level.getBlockState(sheltered))).snowQuads() != 0) {
                        throw new AssertionError("Ineligible plant received snow: " + sheltered);
                    }
                }
            });
            world.getConnection().waitForChunksRender(false, 1200);
            context.takeScreenshot(TestScreenshotOptions.of("plants-03-default-frost").withSize(1440, 900));
            List<Mesh> defaultMeshes = context.computeOnClient(c -> plants.stream().map(p -> mesh(c, p)).toList());
            apply(context, FrostedFoliageClient.system().config().withSnowDepth(0));
            settle(context);
            if (!defaultMeshes.equals(context.computeOnClient(c -> plants.stream().map(p -> mesh(c, p)).toList()))) {
                throw new AssertionError("Changing leaf depth changed plant frost");
            }
            apply(context, FrostedFoliageClient.system().config().withPlants(false, 0.5, 0.65));
            settle(context);
            context.runOnClient(c -> {
                for (Plant p : plants) if (mesh(c, p).snowQuads() != 0) throw new AssertionError("Plant toggle left an overlay");
                if (mesh(c, new Plant(LEAF, c.level.getBlockState(LEAF))).snowQuads() == 0) throw new AssertionError("Plant toggle removed leaf snow");
            });
            for (double depth : new double[]{0, 0.25, 1}) {
                apply(context, FrostedFoliageClient.system().config().withPlants(true, depth, 1));
                settle(context);
                context.runOnClient(c -> {
                    for (Plant p : plants) {
                        Mesh mesh = mesh(c, p);
                        if (depth == 0 && mesh.snowQuads() != 0) throw new AssertionError("Zero plant depth left snow: " + p);
                        if (depth == 1 && mesh.snowPixels() == 0) throw new AssertionError("Full-depth plant has no snow: " + p);
                        if (depth == 0.25 && dev.frostedfoliage.client.world.PlantTypes.part(p.state()) == dev.frostedfoliage.client.world.PlantTypes.LOWER && mesh.snowPixels() != 0) {
                            throw new AssertionError("Shallow frost reached the lower block: " + p);
                        }
                    }
                });
                world.getConnection().waitForChunksRender(false, 1200);
                context.takeScreenshot(TestScreenshotOptions.of("plants-depth-" + (int)(depth * 100)).withSize(1440, 900));
            }
            apply(context, config(true, 30).withPlants(true, 0.5, 0));
            settle(context);
            context.runOnClient(c -> {
                for (Plant p : plants) if (mesh(c, p).snowQuads() != 0) throw new AssertionError("Zero plant coverage left snow");
                if (mesh(c, new Plant(LEAF, c.level.getBlockState(LEAF))).snowQuads() == 0) throw new AssertionError("Plant coverage changed leaves");
            });
            apply(context, config(true, 30));
            settle(context);
            server.runCommand("weather clear");
            context.waitTicks(100);
            world.getConnection().waitForChunksRender(false, 1200);
            context.takeScreenshot(TestScreenshotOptions.of("plants-04-default-clear-view").withSize(1440, 900));
            server.runCommand("tp @a -3.5 65.5 12.5 180 12");
            context.waitTicks(20);
            context.takeScreenshot(TestScreenshotOptions.of("plants-05-close-view").withSize(1440, 900));
            apply(context, config(true, 0));
            context.waitFor(c -> !FrostedFoliageClient.system().active(), 1200);
            settle(context);
            context.runOnClient(c -> {
                for (Plant p : plants) {
                    if (!c.level.getBlockState(p.pos()).equals(p.state())) throw new AssertionError("Plant state changed during the visual effect");
                    if (mesh(c, p).snowQuads() != 0) throw new AssertionError("Plant frost did not melt");
                }
            });
            FrostedFoliageClient.LOGGER.info("PLANT FROST PASS depth/coverage/toggle independent of leaves; roof/warm/water excluded; all plants unchanged after melt");
            server.runCommand("weather rain 100000");
        }
    }

    private static SnowConfig config(boolean enabled, double persistence) {
        return SnowConfig.builder().enabled(enabled).accumulationSeconds(12).meltingSeconds(6).persistenceSeconds(persistence)
                .quality(SnowConfig.Quality.HIGH).maxVisualUpdatesPerSecond(2).build();
    }

    private static void apply(ClientGameTestContext context, SnowConfig config) {
        var future = context.computeOnClient(c -> FrostedFoliageClient.applyConfig(config));
        context.waitFor(c -> future.isDone() && c.gui.overlay() == null, 1200);
        future.join();
    }

    private static void settle(ClientGameTestContext context) {
        context.waitFor(c -> FrostedFoliageClient.system().diagnostics().contains("pending=0/0 "), 1200);
        context.waitTicks(12);
    }

    private static Mesh mesh(Minecraft c, Plant plant) {
        var state = c.level.getBlockState(plant.pos());
        var model = c.getModelManager().getBlockStateModelSet().get(state);
        var atlas = (net.minecraft.client.renderer.texture.TextureAtlas) c.getTextureManager()
                .getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
        int[] values = new int[4];
        var emitter = net.fabricmc.fabric.api.client.renderer.v1.Renderer.get().quadEmitter(q -> {
            values[0]++;
            var sprite = atlas.spriteFinder().find(q);
            if (!sprite.contents().name().getNamespace().equals("frostedfoliage")) return;
            values[1]++;
            for (int y = 0; y < sprite.contents().height(); y++) for (int x = 0; x < sprite.contents().width(); x++) {
                if (!sprite.contents().isTransparent(0, x, y)) {
                    values[2]++;
                    if (y >= sprite.contents().height() / 2) values[3]++;
                }
            }
        });
        model.emitQuads(emitter, c.level, plant.pos(), state, RandomSource.create(1), direction -> false);
        return new Mesh(values[0], values[1], values[2], values[3]);
    }

    private static String geometry(Minecraft c, Plant plant) {
        var state = c.level.getBlockState(plant.pos());
        var model = c.getModelManager().getBlockStateModelSet().get(state);
        var signature = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        var atlas = (net.minecraft.client.renderer.texture.TextureAtlas) c.getTextureManager()
                .getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
        var emitter = net.fabricmc.fabric.api.client.renderer.v1.Renderer.get().quadEmitter(q -> {
            signature.append('|').append(atlas.spriteFinder().find(q).contents().name()).append(':').append(q.tintIndex());
            for (int i = 0; i < 4; i++) signature.append('/').append(q.x(i)).append(',').append(q.y(i)).append(',')
                    .append(q.z(i)).append(',').append(q.u(i)).append(',').append(q.v(i)).append(',').append(q.color(i));
        });
        model.emitQuads(emitter, c.level, plant.pos(), state, RandomSource.create(1), direction -> false);
        return signature.toString();
    }
}
