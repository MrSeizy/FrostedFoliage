package dev.frostedfoliage.test;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.client.world.GroundDiscoveryTest;
import dev.frostedfoliage.config.SnowConfig;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Real terrain, model emission, resource reload and independent setting checks. */
public final class GroundSnowTest {
    private static final BlockPos FLOWER = new BlockPos(0, 63, 0), TALL = new BlockPos(3, 63, 0),
            SHRUB = new BlockPos(6, 63, 0), TREE = new BlockPos(12, 63, 0), EDGE = new BlockPos(15, 63, 8),
            ROOF = new BlockPos(-4, 63, 0), WET = new BlockPos(-7, 63, 0), ISOLATED = new BlockPos(40, 63, 0);
    // Under the canopy; the chunk border runs between z=0 and z=-1.
    private static final BlockPos CANOPY = new BlockPos(9, 63, 1), BORDER_SOUTH = new BlockPos(9, 63, 0),
            BORDER_NORTH = new BlockPos(9, 63, -1);
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
            context.waitFor(c -> PLANTS.stream().allMatch(p -> layers(p) == 1) && layers(TREE) == 1 && layers(BORDER_NORTH) == 1, 1200);
            context.waitTicks(20);
            context.runOnClient(c -> {
                check(!FrostedFoliageClient.system().active(), "Ground cover should work without weather history");
                check(layers(ROOF) == 0 && layers(WET) == 0, "Sheltered or waterlogged plant received ground snow");
                for (BlockPos p : PLANTS) mesh(c, p, 1);
                mesh(c, TREE, 1);
                // Equal virtual snow in the same chunk and solid blocks hide sides. Across a chunk border the side stays,
                // because the other chunk's virtual snow can change without rebuilding this mesh.
                sides(c, CANOPY, 1, Set.of());
                sides(c, BORDER_SOUTH, 1, Set.of(Direction.NORTH));
                sides(c, BORDER_NORTH, 1, Set.of(Direction.SOUTH));
                check(c.level.getBlockState(TREE.above().west()).isSolidRender(), "Precondition: log west of TREE");
                sides(c, TREE, 1, Set.of(Direction.NORTH));
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
            context.runOnClient(c -> {
                // Real snow of equal height to the east hides that side; nothing stands beside the other three.
                check(c.level.getBlockState(ISOLATED.above().east()).is(Blocks.SNOW), "Precondition: real snow east of ISOLATED");
                for (Direction side : List.of(Direction.WEST, Direction.NORTH, Direction.SOUTH)) {
                    check(c.level.getBlockState(ISOLATED.above().relative(side)).isAir() && layers(ISOLATED.relative(side)) == 0,
                            "Precondition: nothing " + side + " of ISOLATED");
                }
                sides(c, ISOLATED, 1, Set.of(Direction.WEST, Direction.NORTH, Direction.SOUTH));
            });

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
            // A chunk re-send recaptures this chunk and its neighbours; unchanged covers must not rebuild their sections.
            long rebuildsBefore = count(context, "groundRebuilds=");
            context.runOnClient(c -> FrostedFoliageClient.system().load(c.level, c.level.getChunk(0, 0)));
            context.waitTicks(60);
            check(count(context, "groundRebuilds=") == rebuildsBefore, "Unchanged ground recaptures after a chunk re-send requested rebuilds: "
                    + context.computeOnClient(c -> FrostedFoliageClient.system().ground().diagnostics()));

            // Unequal neighbors in one chunk: a full-height column beside a one-layer one keeps its exposed side.
            BlockPos step = EDGE.west();
            server.runCommand("setblock 14 64 8 minecraft:fern");
            server.runCommand("setblock 13 64 8 minecraft:snow");
            server.runCommand("setblock 16 64 8 minecraft:snow[layers=8]");
            context.waitFor(c -> layers(EDGE) == 8 && layers(step) == 1, 400);
            context.runOnClient(c -> {
                for (BlockPos p : List.of(EDGE, step)) {
                    check(c.level.getBlockState(p.above().north()).isAir() && c.level.getBlockState(p.above().south()).isAir(),
                            "Precondition: nothing north or south of " + p);
                }
                sides(c, EDGE, 8, Set.of(Direction.WEST, Direction.NORTH, Direction.SOUTH));
                sides(c, step, 1, Set.of(Direction.NORTH, Direction.SOUTH));
            });
            server.runCommand("tp @a 12.3 63.6 5.3 -45 9");
            world.getConnection().waitForChunksRender(false, 1200);
            context.waitTicks(20);
            context.takeScreenshot(TestScreenshotOptions.of("ground-02-unequal-sides").withSize(1440, 900));
            FrostedFoliageClient.LOGGER.info("GROUND SNOW PASS plants, trees, roofs, water, 1/4/8 layers, toggles, occupancy edits, ice exclusion, discovery during streaming, snow removal, chunk boundary, reload, idle, side culling: {}", after);
        }
        check(context.computeOnClient(c -> FrostedFoliageClient.system().ground().diagnostics().contains("groundChunks=0")), "Disconnect retained ground cache");
    }

    private static int layers(BlockPos p) { return FrostedFoliageClient.system().ground().layers(p); }
    private static long count(ClientGameTestContext context, String label) {
        String status = context.computeOnClient(c -> FrostedFoliageClient.system().ground().diagnostics());
        var match = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(label) + "(\\d+)").matcher(status);
        if (!match.find()) throw new AssertionError("No " + label + " in " + status);
        return Long.parseLong(match.group(1));
    }
    private static void toggle(ClientGameTestContext context, boolean trees, boolean plants) {
        context.runOnClient(c -> {
            var system = FrostedFoliageClient.system(); system.configure(system.config().withGroundSnow(trees, plants));
        });
        context.waitTicks(20);
    }
    /** Checks the virtual snow's top face and returns the sides it draws, identified by their geometry. */
    private static Set<Direction> mesh(Minecraft client, BlockPos pos, int expected) {
        var state = client.level.getBlockState(pos);
        var model = client.getModelManager().getBlockStateModelSet().get(state);
        var atlas = (net.minecraft.client.renderer.texture.TextureAtlas) client.getTextureManager()
                .getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
        var finder = atlas.spriteFinder();
        int[] tops = {0}; float[] top = {Float.NaN}, sideMin = {Float.POSITIVE_INFINITY}, sideMax = {Float.NEGATIVE_INFINITY};
        Set<Direction> sides = EnumSet.noneOf(Direction.class);
        var emitter = Renderer.get().quadEmitter(q -> {
            if (!finder.find(q).contents().name().toString().equals("minecraft:block/snow")) return;
            check(q.tintIndex() == -1 && q.cullFace() == null, "Ground snow inherited tint or culling");
            if (q.lightFace() == Direction.UP) { tops[0]++; top[0] = q.y(0); return; }
            check(sides.add(q.lightFace()), "Ground snow drew its " + q.lightFace() + " side twice at " + pos);
            for (int i = 0; i < 4; i++) { sideMin[0] = Math.min(sideMin[0], q.y(i)); sideMax[0] = Math.max(sideMax[0], q.y(i)); }
        });
        model.emitQuads(emitter, client.level, pos, state, RandomSource.create(1), d -> false);
        check(tops[0] == (expected == 0 ? 0 : 1) && (expected > 0 || sides.isEmpty()),
                "Ground mesh at " + pos + " had " + tops[0] + " tops and sides " + sides + ", expected layers=" + expected);
        if (expected > 0) {
            check(Math.abs(top[0] - (1 + expected / 8f)) < 1e-5, "Incorrect ground mesh height");
            check(sides.isEmpty() || Math.abs(sideMin[0] - 1) < 1e-5 && Math.abs(sideMax[0] - (1 + expected / 8f)) < 1e-5,
                    "Incorrect ground side height");
            check(model.createGeometryKey(client.level, pos, state, RandomSource.create(1)) == null, "Ground mesh used location-independent cache");
        }
        return sides;
    }
    private static void sides(Minecraft client, BlockPos pos, int layers, Set<Direction> expected) {
        Set<Direction> drawn = mesh(client, pos, layers);
        check(drawn.equals(expected), "Ground snow at " + pos + " drew sides " + drawn + ", expected " + expected);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
