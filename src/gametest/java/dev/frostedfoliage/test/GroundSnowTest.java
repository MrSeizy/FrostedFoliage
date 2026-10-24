package dev.frostedfoliage.test;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.client.world.GroundDiscoveryTest;
import dev.frostedfoliage.config.SnowConfig;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Real terrain, model emission, resource reload and independent setting checks. */
public final class GroundSnowTest {
    private static final BlockPos FLOWER = new BlockPos(0, 63, 0), TALL = new BlockPos(3, 63, 0),
            SHRUB = new BlockPos(6, 63, 0), TREE = new BlockPos(12, 63, 0), EDGE = new BlockPos(15, 63, 8),
            ROOF = new BlockPos(-4, 63, 0), WET = new BlockPos(-7, 63, 0), ISOLATED = new BlockPos(40, 63, 0);
    private static final List<BlockPos> PLANTS = List.of(FLOWER, TALL, SHRUB, EDGE);

    public void run(ClientGameTestContext context) {
        context.runOnClient(c -> {
            c.options.renderDistance().set(8);
            c.options.pauseOnLostFocus = false;
            c.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
            FrostedFoliageClient.system().configure(SnowConfig.defaults().withPlants(false, 0.5, 0.65));
        });
        try (var world = context.worldBuilder().adjustSettings(s -> s.setName("Ground Snow Verification")).create()) {
            var server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 7 70 19 180 24");
            server.runCommand("time set noon");
            server.runCommand("gamerule minecraft:advance_time false");
            server.runCommand("gamerule minecraft:random_tick_speed 0");
            server.runCommand("gamerule minecraft:max_snow_accumulation_height 0");
            server.runCommand("weather clear");
            server.runCommand("fill -12 63 -10 24 63 12 minecraft:grass_block");
            server.runCommand("fill -12 64 -10 24 64 12 minecraft:snow");
            server.runCommand("fill 8 64 -3 14 64 3 minecraft:air");
            server.runCommand("fill 8 67 -3 14 67 3 minecraft:oak_leaves[persistent=true]");
            server.runCommand("fill 11 64 0 11 67 0 minecraft:oak_log");
            server.runCommand("fill -5 67 -1 -3 67 1 minecraft:stone");
            server.runCommand("fill 40 63 0 41 63 0 minecraft:grass_block");
            server.runCommand("setblock 40 67 0 minecraft:oak_leaves[persistent=true]");
            server.runCommand("setblock 40 64 0 minecraft:torch");
            server.runCommand("setblock 41 64 0 minecraft:snow");
            server.runCommand("fill 24 63 24 25 63 24 minecraft:grass_block");
            server.runCommand("setblock 24 64 24 minecraft:fern");
            server.runCommand("setblock 25 64 24 minecraft:snow");
            server.runOnServer(s -> {
                var level = s.overworld();
                level.setBlock(FLOWER.above(), Blocks.POPPY.defaultBlockState(), 3);
                level.setBlock(SHRUB.above(), Blocks.AZALEA.defaultBlockState(), 3);
                level.setBlock(EDGE.above(), Blocks.FERN.defaultBlockState(), 3);
                level.setBlock(ROOF.above(), Blocks.FERN.defaultBlockState(), 3);
                DoublePlantBlock.placeAt(level, Blocks.LARGE_FERN.defaultBlockState(), TALL.above(), 3);
                for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) level.setBlock(WET.above().relative(d), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(WET.above(), Blocks.MANGROVE_PROPAGULE.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true), 3);
            });
            context.waitTicks(100);
            context.runOnClient(c -> {
                for (BlockPos p : List.of(FLOWER, TALL, SHRUB, TREE, EDGE)) {
                    var chunk = c.level.getChunkAt(p);
                    int y = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX() & 15, p.getZ() & 15);
                    FrostedFoliageClient.LOGGER.info("GROUND PROBE {} layers={} height={} support={} plant={} {}", p, layers(p), y,
                            c.level.getBlockState(p), c.level.getBlockState(p.above()), FrostedFoliageClient.system().ground().diagnostics());
                }
            });
            context.waitFor(c -> PLANTS.stream().allMatch(p -> layers(p) == 1) && layers(TREE) == 1, 1200);
            context.waitTicks(20);
            context.runOnClient(c -> {
                check(!FrostedFoliageClient.system().active(), "Ground cover should work without weather history");
                check(layers(ROOF) == 0 && layers(WET) == 0, "Sheltered or waterlogged plant received ground snow");
                for (BlockPos p : PLANTS) mesh(c, p, 1);
                mesh(c, TREE, 1);
                check(c.level.getBlockState(TREE.above()).isAir(), "Virtual snow changed an air block");
                check(c.level.getBlockState(FLOWER.above()).is(Blocks.POPPY), "Virtual snow replaced a flower");
                if (Boolean.getBoolean("frostedfoliage.test.shaderExpected"))
                    check(net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse(), "Shader pack is inactive");
            });
            context.runOnClient(c -> { if (!c.gui.hud.isHidden()) c.gui.hud.toggle(); });
            world.getConnection().waitForChunksRender(false, 1200);
            context.takeScreenshot(TestScreenshotOptions.of("ground-01-trees-plants").withSize(1440, 900));

            toggle(context, false, true);
            context.runOnClient(c -> { mesh(c, TREE, 0); mesh(c, FLOWER, 1); });
            toggle(context, true, false);
            context.runOnClient(c -> { mesh(c, TREE, 1); mesh(c, FLOWER, 0); });
            toggle(context, false, false);
            context.runOnClient(c -> { mesh(c, TREE, 0); mesh(c, FLOWER, 0); });
            toggle(context, true, true);

            // Both partial and full-height physical snow remain valid anchors.
            for (int height : new int[]{4, 8}) {
                server.runCommand("fill -12 64 -10 24 64 12 minecraft:snow[layers=" + height + "] replace minecraft:snow");
                context.waitFor(c -> layers(FLOWER) == height && layers(TREE) == height && layers(EDGE) == height, 400);
                context.runOnClient(c -> { mesh(c, FLOWER, height); mesh(c, TREE, height); });
            }
            server.runCommand("fill -12 64 -10 24 64 12 minecraft:snow[layers=1] replace minecraft:snow");
            context.waitFor(c -> layers(TREE) == 1 && layers(FLOWER) == 1, 400);
            server.runCommand("setblock 12 66 0 minecraft:stone");
            context.waitFor(c -> layers(TREE) == 0, 400);
            server.runCommand("setblock 12 66 0 minecraft:air");
            context.waitFor(c -> layers(TREE) == 1, 400);

            // These edits leave both heightmaps unchanged beneath the existing canopy.
            for (String block : List.of("torch", "white_carpet", "rail", "redstone_wire", "oak_leaves[persistent=true]")) {
                server.runCommand("setblock 12 64 0 minecraft:" + block);
                context.waitFor(c -> layers(TREE) == 0, 400);
                context.runOnClient(c -> mesh(c, TREE, 0));
                server.runCommand("setblock 12 64 0 minecraft:air");
                context.waitFor(c -> layers(TREE) == 1, 400);
                context.runOnClient(c -> mesh(c, TREE, 1));
            }
            // Removal must reconsider a chunk even when its previous capture found no candidates.
            context.runOnClient(c -> check(layers(ISOLATED) == 0, "Torch already has ground snow"));
            server.runCommand("setblock 40 64 0 minecraft:air");
            context.waitFor(c -> layers(ISOLATED) == 1, 400);
            server.runCommand("setblock 40 64 0 minecraft:snow");
            context.waitFor(c -> layers(ISOLATED) == 0, 400);
            server.runCommand("setblock 40 64 0 minecraft:air");
            context.waitFor(c -> layers(ISOLATED) == 1, 400);
            context.runOnClient(c -> mesh(c, ISOLATED, 1));

            for (String block : List.of("ice", "packed_ice", "barrier")) {
                server.runCommand("setblock 12 63 0 minecraft:" + block);
                context.waitFor(c -> layers(TREE) == 0, 400);
                context.runOnClient(c -> mesh(c, TREE, 0));
                server.runCommand("setblock 12 63 0 minecraft:grass_block");
                context.waitFor(c -> layers(TREE) == 1, 400);
                context.runOnClient(c -> mesh(c, TREE, 1));
            }
            context.waitFor(c -> c.level.getChunkSource().hasChunk(-4, -4) && c.level.getChunkSource().hasChunk(1, 1), 1200);
            context.runOnClient(GroundDiscoveryTest::run);

            // A different chunk supplies the only remaining snow for EDGE.
            server.runCommand("fill -12 64 -10 24 64 12 minecraft:air replace minecraft:snow");
            context.waitFor(c -> PLANTS.stream().allMatch(p -> layers(p) == 0) && layers(TREE) == 0, 400);
            server.runCommand("setblock 16 64 8 minecraft:snow[layers=2]");
            context.waitFor(c -> layers(EDGE) == 2, 400);
            context.runOnClient(c -> { mesh(c, EDGE, 2); check(layers(FLOWER) == 0, "Snow spread beyond its search radius"); });
            server.runCommand("setblock 16 64 8 minecraft:air");
            context.waitFor(c -> layers(EDGE) == 0, 400);
            server.runCommand("setblock 16 64 8 minecraft:snow");
            context.waitFor(c -> layers(EDGE) == 1, 400);

            var reload = context.computeOnClient(c -> c.reloadResourcePacks());
            context.waitFor(c -> reload.isDone(), 1200); reload.join();
            context.waitFor(c -> c.gui.overlay() == null, 1200);
            context.waitFor(c -> layers(EDGE) == 1, 400);
            context.runOnClient(c -> mesh(c, EDGE, 1));
            world.getConnection().waitForChunksRender(false, 1200);
            context.waitFor(c -> FrostedFoliageClient.system().ground().settled(), 1200);
            String before = context.computeOnClient(c -> FrostedFoliageClient.system().ground().diagnostics());
            context.waitTicks(100);
            String after = context.computeOnClient(c -> FrostedFoliageClient.system().ground().diagnostics());
            check(before.equals(after), "Quiet terrain kept scanning or requesting rebuilds: " + before + " -> " + after);
            FrostedFoliageClient.LOGGER.info("GROUND SNOW PASS plants, trees, roofs, water, 1/4/8 layers, toggles, occupancy edits, ice exclusion, discovery during streaming, snow removal, chunk boundary, reload, idle: {}", after);
        }
        check(context.computeOnClient(c -> FrostedFoliageClient.system().ground().diagnostics().contains("groundChunks=0")), "Disconnect retained ground cache");
    }

    private static int layers(BlockPos p) { return FrostedFoliageClient.system().ground().layers(p); }
    private static void toggle(ClientGameTestContext context, boolean trees, boolean plants) {
        context.runOnClient(c -> {
            var system = FrostedFoliageClient.system(); system.configure(system.config().withGroundSnow(trees, plants));
        });
        context.waitTicks(20);
    }
    private static void mesh(Minecraft client, BlockPos pos, int expected) {
        var state = client.level.getBlockState(pos);
        var model = client.getModelManager().getBlockStateModelSet().get(state);
        var atlas = (net.minecraft.client.renderer.texture.TextureAtlas) client.getTextureManager()
                .getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
        var finder = atlas.spriteFinder();
        int[] count = {0}; float[] min = {Float.POSITIVE_INFINITY}, max = {Float.NEGATIVE_INFINITY};
        var emitter = Renderer.get().quadEmitter(q -> {
            if (!finder.find(q).contents().name().toString().equals("minecraft:block/snow")) return;
            count[0]++;
            check(q.tintIndex() == -1 && q.cullFace() == null, "Ground snow inherited tint or culling");
            for (int i = 0; i < 4; i++) { min[0] = Math.min(min[0], q.y(i)); max[0] = Math.max(max[0], q.y(i)); }
        });
        model.emitQuads(emitter, client.level, pos, state, RandomSource.create(1), d -> false);
        check(count[0] == (expected == 0 ? 0 : 5), "Ground mesh count " + count[0] + " at " + pos + " expected layers=" + expected);
        if (expected > 0) {
            check(Math.abs(min[0] - 1) < 1e-5 && Math.abs(max[0] - (1 + expected / 8f)) < 1e-5, "Incorrect ground mesh height");
            check(model.createGeometryKey(client.level, pos, state, RandomSource.create(1)) == null, "Ground mesh used location-independent cache");
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
