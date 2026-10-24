package dev.frostedfoliage.test;

import com.google.gson.GsonBuilder;
import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.client.world.SnowSystem;
import dev.frostedfoliage.config.SnowConfig;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Repeatable pre/post-fix workload. Instrumentation and disposable-world changes stay in the test jar. */
public final class BlockUpdateBenchmark {
    private static final BlockPos PROBE = new BlockPos(100, 70, 100);
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    private static volatile Churn churn;
    private static boolean registered;

    public void run(ClientGameTestContext context) {
        RESULTS.clear();
        if (!registered) {
            ServerTickEvents.END_SERVER_TICK.register(server -> {
                Churn work = churn;
                if (work != null && work.level.getServer() == server) work.tick();
            });
            registered = true;
        }
        context.runOnClient(c -> {
            c.options.renderDistance().set(16);
            c.options.enableVsync().set(false);
            c.options.pauseOnLostFocus = false;
            c.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
            c.options.framerateLimit().set(260);
            FrostedFoliageClient.system().configure(config());
        });
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 96 83 202 180 15");
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            server.runOnServer(s -> {
                var level = s.overworld();
                var leaves = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true);
                var pos = new BlockPos.MutableBlockPos();
                for (int x = 0; x < 192; x++) for (int z = 0; z < 192; z++) {
                    level.setBlock(pos.set(x, 63, z), Blocks.SNOW_BLOCK.defaultBlockState(), 3);
                }
                for (int x = 4; x < 192; x += 8) for (int z = 4; z < 192; z += 8) {
                    for (int y = 64; y < 70; y++) level.setBlock(pos.set(x, y, z), Blocks.SPRUCE_LOG.defaultBlockState(), 3);
                    for (int y = 67; y <= 70; y++) {
                        int radius = y < 69 ? 3 : y == 69 ? 2 : 1;
                        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                            level.setBlock(pos.set(x + dx, y, z + dz), leaves, 3);
                        }
                    }
                }
            });
            for (int x = 0; x < 192; x += 16) for (int z = 0; z < 192; z += 16) {
                server.runCommand("fillbiome " + x + " 48 " + z + " " + (x + 15) + " 95 " + (z + 15) + " minecraft:snowy_taiga");
            }
            context.waitTicks(160);
            world.getConnection().waitForChunksRender(false, 1200);
            server.runCommand("weather rain 100000");
            context.waitFor(c -> FrostedFoliageClient.system().coverage(PROBE) > 0.99, 1200);
            context.waitFor(c -> c.level.getRainLevel(1) >= 0.99, 1200);
            context.waitTicks(100);
            measure(context, "natural-snow-default-rules", 600);

            // Isolate the repeatable workload after measuring ordinary weather mechanics.
            server.runCommand("gamerule minecraft:random_tick_speed 0");
            server.runCommand("gamerule minecraft:max_snow_accumulation_height 0");
            settled(context);
            measure(context, "quiet-snow", 200);
            server.runOnServer(s -> churn = new Churn(s.overworld()));
            Map<String, Object> row = measure(context, "unrelated-solid-block-changes", 400);
            server.runOnServer(s -> {
                row.put("serverBlockChanges", churn.changes);
                churn = null;
            });
            settled(context);
            if (Boolean.getBoolean("frostedfoliage.test.churn.verify")) {
                check(((Number) row.get("captures")).longValue() == 0, "Irrelevant changes recaptured foliage");
                check(((Number) row.get("rebuildRequests")).longValue() == 0, "Irrelevant changes rebuilt foliage");
                Counters before = context.computeOnClient(c -> counters());
                context.runOnClient(c -> {
                    var system = FrostedFoliageClient.system();
                    var unchanged = system.config();
                    FrostedFoliageClient.applyConfig(unchanged).join();
                    var gson = new GsonBuilder().create();
                    var json = gson.toJsonTree(unchanged).getAsJsonObject();
                    json.addProperty("meltingSeconds", unchanged.meltingSeconds() + 1);
                    FrostedFoliageClient.applyConfig(gson.fromJson(json, SnowConfig.class)).join();
                });
                context.waitTicks(40);
                Counters after = context.computeOnClient(c -> counters());
                check(after.captures == before.captures && after.rebuilds == before.rebuilds,
                        "Unchanged or timing-only config invalidated foliage");

                // This column is between trees. Its height changes, but no foliage sample does.
                server.runCommand("setblock 112 78 112 minecraft:stone");
                context.waitFor(c -> c.level.getBlockState(new BlockPos(112, 78, 112)).is(Blocks.STONE), 400);
                settled(context);
                Counters recaptured = context.computeOnClient(c -> counters());
                check(recaptured.captures > after.captures, "Unchanged-environment recapture was not exercised");
                check(recaptured.rebuilds == after.rebuilds, "An identical environment triggered a rebuild");
            }

            server.runCommand("setblock 100 78 100 minecraft:stone");
            context.waitFor(c -> FrostedFoliageClient.system().coverage(PROBE) == 0, 400);
            check(quads(context, PROBE) == 6, "New roof left a snow overlay");
            server.runCommand("setblock 100 78 100 minecraft:air");
            context.waitFor(c -> FrostedFoliageClient.system().coverage(PROBE) > 0.99, 400);
            check(quads(context, PROBE) > 6, "Removing roof failed to restore snow");
            server.runCommand("setblock 1 85 1 minecraft:spruce_leaves[persistent=true]");
            var added = new BlockPos(1, 85, 1);
            context.waitFor(c -> FrostedFoliageClient.system().coverage(added) > 0.99, 400);
            check(quads(context, added) > 6, "New foliage section was not discovered");
            server.runCommand("setblock 1 85 1 minecraft:air");
            context.waitFor(c -> FrostedFoliageClient.system().coverage(added) == 0, 400);
            FrostedFoliageClient.LOGGER.info("BLOCK UPDATE REGRESSIONS PASSED: roof placement/removal and foliage discovery/removal");
        } finally {
            churn = null;
            try {
                Files.writeString(Path.of("block-update-benchmark.json"), new GsonBuilder().setPrettyPrinting().create().toJson(RESULTS));
            } catch (java.io.IOException e) { throw new AssertionError(e); }
        }
    }

    private static Map<String, Object> measure(ClientGameTestContext context, String phase, int ticks) {
        Counters before = context.computeOnClient(c -> { FrameMetrics.start(); return counters(); });
        context.waitTicks(ticks);
        var row = context.computeOnClient(c -> {
            Counters after = counters();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("label", System.getProperty("frostedfoliage.test.churn.label"));
            result.put("renderer", System.getProperty("frostedfoliage.test.renderer"));
            result.put("phase", phase);
            result.put("clientTicks", after.ticks - before.ticks);
            result.put("captures", after.captures - before.captures);
            result.put("rebuildRequests", after.rebuilds - before.rebuilds);
            result.put("captureTotalMs", (after.captureNanos - before.captureNanos) / 1e6);
            result.put("modTickTotalMs", (after.tickNanos - before.tickNanos) / 1e6);
            result.put("frames", FrameMetrics.stop());
            result.put("status", FrostedFoliageClient.system().diagnostics());
            return result;
        });
        RESULTS.add(row);
        FrostedFoliageClient.LOGGER.info("BLOCK UPDATE BENCHMARK {}", new GsonBuilder().create().toJson(row));
        return row;
    }

    private static void settled(ClientGameTestContext context) {
        context.waitFor(c -> FrostedFoliageClient.system().diagnostics().contains("pending=0/0 "), 1200);
        context.waitTicks(40);
    }

    private static int quads(ClientGameTestContext context, BlockPos pos) {
        return context.computeOnClient(c -> {
            var state = c.level.getBlockState(pos);
            int[] count = {0};
            var emitter = net.fabricmc.fabric.api.client.renderer.v1.Renderer.get().quadEmitter(q -> count[0]++);
            c.getModelManager().getBlockStateModelSet().get(state).emitQuads(emitter, c.level, pos, state,
                    net.minecraft.util.RandomSource.create(1), direction -> false);
            return count[0];
        });
    }

    private static SnowConfig config() {
        var d = SnowConfig.defaults();
        return new SnowConfig(true, false, true, true, 1, d.whiteness(), d.variation(), true, d.snowDepth(),
                8, 6, 0, d.skyExposureSensitivity(), SnowConfig.Quality.MEDIUM, 0.5, 4096,
                d.includeBlocks(), d.excludeBlocks(), d.extraTextures());
    }

    private record Counters(long captures, long rebuilds, long captureNanos, long tickNanos, long ticks) { }
    private static Counters counters() {
        return new Counters(counter("captures"), counter("rebuilds"), counter("captureNanos"), counter("tickNanos"), counter("ticks"));
    }
    private static long counter(String name) {
        try {
            Field field = SnowSystem.class.getDeclaredField(name);
            field.setAccessible(true);
            return ((Number) field.get(FrostedFoliageClient.system())).longValue();
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static final class Churn {
        final ServerLevel level;
        long changes;
        Churn(ServerLevel level) { this.level = level; }
        void tick() {
            for (int i = 0; i < 8; i++) {
                int column = (int) (changes % 144);
                boolean alternate = changes / 144 % 2 == 0;
                level.setBlock(new BlockPos(column % 12 * 16 + 8, 50, column / 12 * 16 + 8),
                        (alternate ? Blocks.STONE : Blocks.ANDESITE).defaultBlockState(), 3);
                changes++;
            }
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
