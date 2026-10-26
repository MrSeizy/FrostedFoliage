package dev.frostedfoliage.test;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.config.SnowConfig;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CloudStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;

/** Capture real Minecraft spruce geometry and the production snow renderer for the mod icon. */
public final class IconCapture {
    public void run(ClientGameTestContext context) {
        context.runOnClient(c -> {
            c.options.renderDistance().set(8);
            c.options.fov().set(30);
            c.options.cloudStatus().set(CloudStatus.OFF);
            c.options.cutoutLeaves().set(true);
            c.options.pauseOnLostFocus = false;
            c.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
            FrostedFoliageClient.system().configure(SnowConfig.builder().snowDepth(1).accumulationSeconds(6)
                    .persistenceSeconds(3600).quality(SnowConfig.Quality.HIGH).maxVisualUpdatesPerSecond(2).build());
        });
        var highest = new BlockPos[]{BlockPos.ZERO};
        try (var world = context.worldBuilder().adjustSettings(s -> s.setName("Frosted Foliage - Icon Studio")).create()) {
            var server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 0 72 20 180 12");
            server.runCommand("time set 3000");
            server.runCommand("gamerule minecraft:advance_time false");
            server.runCommand("gamerule minecraft:random_tick_speed 0");
            server.runCommand("gamerule minecraft:max_snow_accumulation_height 0");
            server.runOnServer(s -> {
                for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) s.overworld().getChunk(x, z);
            });
            // One continuous backdrop behind the entire frame, with no floor/wall color split.
            server.runCommand("fill -80 0 -20 80 110 -20 minecraft:gray_concrete");
            server.runCommand("fill -3 63 -3 3 63 3 minecraft:snow_block");
            server.runCommand("setblock 0 63 0 minecraft:grass_block");
            server.runOnServer(s -> {
                var level = s.overworld();
                var feature = level.registryAccess().lookupOrThrow(Registries.FEATURE).getOrThrow(TreeFeatures.SPRUCE).value();
                boolean selected = false;
                for (long seed = 1; seed <= 100 && !selected; seed++) {
                    for (var pos : BlockPos.betweenClosed(-6, 64, -6, 6, 96, 6)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    level.setBlock(new BlockPos(0, 63, 0), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                    if (!feature.place(level, level.getChunkSource().getGenerator(), RandomSource.create(seed), new BlockPos(0, 64, 0))) continue;
                    int leaves = 0, topLeaves = 0;
                    highest[0] = BlockPos.ZERO;
                    for (var pos : BlockPos.betweenClosed(-6, 64, -6, 6, 96, 6)) {
                        if (level.getBlockState(pos).getBlock() instanceof LeavesBlock) {
                            leaves++;
                            if (pos.getY() > highest[0].getY()) { highest[0] = pos.immutable(); topLeaves = 0; }
                            if (pos.getY() == highest[0].getY()) topLeaves++;
                        }
                    }
                    // Choose a vanilla seed with a readable pine silhouette; never edit its leaves.
                    selected = topLeaves == 1 && leaves >= 65 && highest[0].getY() >= 71;
                    if (selected) FrostedFoliageClient.LOGGER.info("ICON TREE native spruce seed={} leaves={} top={}", seed, leaves, highest[0]);
                }
                if (!selected) throw new AssertionError("No suitable native spruce in the candidate seeds");
            });
            server.runCommand("fillbiome -12 60 -12 12 88 12 minecraft:snowy_taiga");
            server.runCommand("weather rain 100000");
            context.waitFor(c -> FrostedFoliageClient.system().coverage(highest[0]) >= 0.99, 1200);
            context.waitFor(c -> FrostedFoliageClient.system().diagnostics().contains("pending=0/0 "), 1200);
            if (Boolean.getBoolean("frostedfoliage.test.shaderExpected") && !context.computeOnClient(c ->
                    net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse())) {
                throw new AssertionError("Requested shader pack is not active");
            }
            server.runCommand("weather clear");
            context.waitTicks(200);
            context.runOnClient(c -> { if (!c.gui.hud.isHidden()) c.gui.hud.toggle(); });
            double height = highest[0].getY() + 1 - 64;
            double centerY = 63.5 + height * 0.50;
            double distance = height / (2 * Math.tan(Math.toRadians(15))) * 1.35;
            for (int angle : new int[]{30, 45}) {
                double x = 0.5 + distance * Math.sin(Math.toRadians(angle));
                double z = 0.5 + distance * Math.cos(Math.toRadians(angle));
                double y = centerY + distance * Math.tan(Math.toRadians(12)) - 1.62;
                server.runCommand(String.format(Locale.ROOT, "tp @a %.4f %.4f %.4f %d 12", x, y, z, 180 - angle));
                context.waitTicks(40);
                world.getConnection().waitForChunksRender(false, 1200);
                context.takeScreenshot(TestScreenshotOptions.of("icon-spruce-100-angle-" + angle).withSize(1024, 1024));
            }
            FrostedFoliageClient.LOGGER.info("ICON CAPTURE COMPLETE native spruce, 100% snow depth, full weather coverage, single gray backdrop");
        }
    }
}
