package dev.frostedfoliage.test;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.config.SnowConfig;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Streams chunks the way exploring does: a straight 1280-block flight at one block per two ticks through the middle of a
 * natural snowy taiga with full snow. Reports how many captures and rebuild requests the flight cost; unchanged
 * neighbour recaptures must not turn into rebuilds, so the rebuild counts should stay close to the number of newly
 * snowy sections.
 */
public final class FlightTest {
    private static final int HALF = Integer.getInteger("frostedfoliage.test.flightHalf", 640);

    public void run(ClientGameTestContext context) {
        context.runOnClient(c -> {
            c.options.renderDistance().set(12);
            c.options.pauseOnLostFocus = false;
            c.options.enableVsync().set(false);
            c.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
            c.options.framerateLimit().set(260);
            FrostedFoliageClient.system().configure(SnowConfig.builder().snowDepth(1).quality(SnowConfig.Quality.HIGH)
                    .maxCachedSections(Boolean.getBoolean("frostedfoliage.test.cacheStress") ? 64 : 4096).maxVisualUpdatesPerSecond(2).build());
        });
        // The default test world is flat; natural terrain with snowy taiga needs the normal preset and a fixed seed.
        try (var world = context.worldBuilder().adjustSettings(s -> {
            var normal = s.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL);
            s.setWorldType(new WorldCreationUiState.WorldTypeEntry(normal));
            s.setName("Frosted Foliage - Flight");
            s.setSeed("20261023");
            s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            s.setAllowCommands(true);
        }).create()) {
            var server = world.getServer();
            BlockPos center = server.computeOnServer(s -> {
                var found = s.overworld().findClosestBiome3d(h -> h.is(Biomes.SNOWY_TAIGA), new BlockPos(0, 64, 0), 6400, 32, 64);
                return found == null ? null : found.getFirst();
            });
            check(center != null, "No snowy taiga within 6400 blocks of the world origin");
            server.runCommand("gamemode spectator @a");
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            server.runCommand("gamerule minecraft:random_tick_speed 0");
            server.runCommand("gamerule minecraft:send_command_feedback false");
            int x = center.getX() - HALF, z = center.getZ();
            server.runCommand("tp @a " + x + " 110 " + z);
            // The harness's full chunk-download wait can time out far from spawn; wait for the player's own chunk, then settle.
            final int startX = x, startZ = z;
            context.waitFor(c -> c.player != null && c.player.blockPosition().getX() >> 4 == startX >> 4
                    && c.level.getChunkSource().hasChunk(startX >> 4, startZ >> 4), 1200);
            context.waitTicks(600);
            context.runOnClient(c -> FrostedFoliageClient.system().setPreview(true));
            // Generate the course first: the integrated server cannot generate terrain at 20 m/s, and a client with no
            // chunks measures nothing. The measured leg flies back over generated chunks.
            for (int i = 0; i < 2 * HALF; i++) {
                x++;
                server.runCommand("tp @a " + x + " 110 " + z);
                context.waitTick();
                if (i % 64 == 0) { final int cx = x >> 4, cz = z >> 4; context.waitFor(c -> c.level.getChunkSource().hasChunk(cx, cz), 1200); }
            }
            context.waitTicks(400);
            context.runOnClient(c -> FrostedFoliageClient.LOGGER.info("FLIGHT start near {}: {}", center.toShortString(), FrostedFoliageClient.system().diagnostics()));
            Map<String, Long> before = counters(context);
            var memoryBefore = context.computeOnClient(c -> SnowWorkMetrics.snapshotMemory());
            context.runOnClient(c -> { FrameMetrics.start(); SnowWorkMetrics.start(); });
            Map<String, Long> middle = null;
            // 10 m/s: at 20 m/s the integrated server's chunk sending falls behind and the client flies through nothing.
            for (int i = 0; i < 2 * HALF; i++) {
                x--;
                server.runCommand("tp @a " + x + " 110 " + z);
                context.waitTicks(2);
                if (i == HALF) middle = counters(context);
            }
            var measurement = context.computeOnClient(c -> {
                var result = new LinkedHashMap<String, Object>();
                result.put("frames", FrameMetrics.stop());
                result.put("work", SnowWorkMetrics.stop());
                result.put("memoryBefore", memoryBefore);
                result.put("memoryAfter", SnowWorkMetrics.snapshotMemory());
                result.put("renderer", System.getProperty("frostedfoliage.test.renderer"));
                result.put("cacheSections", FrostedFoliageClient.system().config().maxCachedSections());
                return result;
            });
            try { Files.writeString(Path.of("flight-benchmark.json"), new GsonBuilder().setPrettyPrinting().create().toJson(measurement)); }
            catch (java.io.IOException e) { throw new AssertionError(e); }
            FrostedFoliageClient.LOGGER.info("FLIGHT METRICS {}", new GsonBuilder().create().toJson(measurement));
            context.waitTicks(400);
            Map<String, Long> after = counters(context);
            StringBuilder report = new StringBuilder("FLIGHT " + 2 * HALF + " blocks at 10 m/s through snowy taiga centred on " + center.toShortString() + ":");
            for (String key : before.keySet()) report.append(' ').append(key).append("+=").append(middle.get(key) - before.get(key)).append('/').append(after.get(key) - middle.get(key));
            report.append(" (first half/second half)");
            context.runOnClient(c -> FrostedFoliageClient.LOGGER.info("{} | {}", report, FrostedFoliageClient.system().diagnostics()));
            context.runOnClient(c -> FrostedFoliageClient.system().setPreview(false));
        }
    }

    private static Map<String, Long> counters(ClientGameTestContext context) {
        String status = context.computeOnClient(c -> FrostedFoliageClient.system().diagnostics());
        var result = new java.util.LinkedHashMap<String, Long>();
        for (String key : new String[]{"captures", "rebuilds", "groundCaptures", "groundRebuilds", "groundChunks"}) {
            Matcher match = Pattern.compile("(?<![A-Za-z])" + key + "=(\\d+)").matcher(status);
            if (!match.find()) throw new AssertionError("No " + key + " in " + status);
            result.put(key, Long.parseLong(match.group(1)));
        }
        return result;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
