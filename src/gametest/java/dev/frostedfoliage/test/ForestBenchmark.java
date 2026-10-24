package dev.frostedfoliage.test;

import com.google.gson.GsonBuilder;
import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.config.SnowConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class ForestBenchmark {
    private final List<Map<String, Object>> results = new ArrayList<>();
    public void run(ClientGameTestContext context) {
        context.runOnClient(client -> {
            client.options.renderDistance().set(24);
            client.options.enableVsync().set(false);
            client.options.pauseOnLostFocus = false;
            client.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
            client.options.framerateLimit().set(260);
            FrostedFoliageClient.system().configure(config(false));
        });
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 96 83 202 180 15");
            server.runCommand("time set noon");
            server.runCommand("gamerule minecraft:random_tick_speed 0");
            server.runCommand("gamerule minecraft:max_snow_accumulation_height 0");
            server.runOnServer(mcServer -> {
                var level = mcServer.overworld();
                var leaves = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true);
                var pos = new BlockPos.MutableBlockPos();
                for (int x = 0; x < 192; x++) for (int z = 0; z < 192; z++) level.setBlock(pos.set(x, 63, z), Blocks.SNOW_BLOCK.defaultBlockState(), 3);
                for (int x = 4; x < 192; x += 8) for (int z = 4; z < 192; z += 8) {
                    for (int y = 64; y < 70; y++) level.setBlock(pos.set(x, y, z), Blocks.SPRUCE_LOG.defaultBlockState(), 3);
                    for (int y = 67; y <= 70; y++) {
                        int radius = y < 69 ? 3 : y == 69 ? 2 : 1;
                        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                            level.setBlock(pos.set(x+dx, y, z+dz), leaves, 3);
                        }
                    }
                }
            });
            for (int x = 0; x < 192; x += 16) for (int z = 0; z < 192; z += 16) {
                server.runCommand("fillbiome " + x + " 64 " + z + " " + (x+15) + " 95 " + (z+15) + " minecraft:snowy_taiga");
            }
            context.waitTicks(200);
            world.getConnection().waitForChunksRender(false, 1200);
            context.runOnClient(c -> { if (!c.gui.hud.isHidden()) c.gui.hud.toggle(); });
            server.runCommand("weather rain 100000");
            context.waitTicks(140);
            measure(context, "disabled-during-snowfall", 160);
            context.runOnClient(c -> FrostedFoliageClient.system().configure(config(true)));
            measure(context, "accumulating", 240);
            context.waitTicks(100);
            world.getConnection().waitForChunksRender(false, 1200);
            measure(context, "full-snow-steady", 160);
            context.takeScreenshot("dense-forest-snow");
            context.runOnClient(c -> FrostedFoliageClient.system().configure(config(false)));
            context.waitTicks(160);
            world.getConnection().waitForChunksRender(false, 1200);
            measure(context, "disabled-warm-comparison", 160);
        }
        try {
            Files.writeString(Path.of("benchmark.json"), new GsonBuilder().setPrettyPrinting().create().toJson(results));
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
    }
    private void measure(ClientGameTestContext context, String name, int ticks) {
        String before = context.computeOnClient(c -> FrostedFoliageClient.system().diagnostics());
        context.runOnClient(c -> FrameMetrics.start());
        context.waitTicks(ticks);
        Map<String, Object> row = context.computeOnClient(c -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("phase", name); result.put("renderer", System.getProperty("frostedfoliage.test.renderer"));
            result.put("renderDistance", c.options.renderDistance().get()); result.put("trees", 576);
            result.put("frames", FrameMetrics.stop());
            result.put("heapUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
            result.put("before", before); result.put("after", FrostedFoliageClient.system().diagnostics());
            return result;
        });
        results.add(row);
        FrostedFoliageClient.LOGGER.info("FOREST BENCHMARK {}", new GsonBuilder().create().toJson(row));
    }
    private SnowConfig config(boolean enabled) {
        var d = SnowConfig.defaults();
        return new SnowConfig(enabled, true, true, true, 1, 0.9, 0.2, true, 1,
                8, 6, 0, 0.8, SnowConfig.Quality.MEDIUM, 0.5, 4096, d.includeBlocks(), d.excludeBlocks(), d.extraTextures());
    }
}
