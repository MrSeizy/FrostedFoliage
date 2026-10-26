package dev.frostedfoliage.test;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.config.SnowConfig;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/**
 * Sodium ignores a section's rebuild request while that section's first mesh is still being built. Snow that arrives in
 * that window must still reach the mesh: the renderer drops every request here, then the snow has to appear anyway, on
 * the leaves and on the ground beneath them.
 */
public final class LostRebuildTest {
    /** Set by this test; the test mixin cancels every section rebuild request while it is true. */
    public static volatile boolean dropRebuilds;
    /** Coverage each section's leaves had when the renderer last meshed them, recorded by the test mixin. */
    public static final Map<Long, Double> meshedCoverage = new ConcurrentHashMap<>();
    /** Ground snow layers the block beneath the canopy had when the renderer last meshed it, or -1 before that. */
    public static volatile int groundMeshedLayers = -1;
    public static final BlockPos GROUND = new BlockPos(1, 63, 1);
    private static final BlockPos LEAF = new BlockPos(0, 71, 0);

    public void run(ClientGameTestContext context) {
        context.runOnClient(c -> {
            c.options.pauseOnLostFocus = false;
            FrostedFoliageClient.system().configure(SnowConfig.builder().snowDepth(1)
                    .quality(SnowConfig.Quality.HIGH).maxVisualUpdatesPerSecond(2).build());
        });
        try (var world = context.worldBuilder().adjustSettings(s -> s.setName("Frosted Foliage - Lost Rebuild")).create()) {
            var server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            server.runCommand("gamerule minecraft:random_tick_speed 0");
            server.runCommand("fill -8 63 -8 8 63 8 minecraft:grass_block");
            server.runCommand("fillbiome -8 48 -8 8 95 8 minecraft:snowy_plains");
            server.runCommand("fill 0 64 0 0 69 0 minecraft:oak_log");
            server.runCommand("fill -1 70 -1 1 71 1 minecraft:oak_leaves[persistent=true]");
            server.runCommand("tp @a 0 70 -6 0 10");
            world.getConnection().waitForChunksRender();
            long section = SectionPos.asLong(LEAF);
            // The vanilla renderer can report chunks rendered before it recompiles the section with the new leaves.
            context.waitFor(c -> meshedCoverage.containsKey(section), 200);
            check(meshedCoverage.get(section) == 0, "Bare canopy was meshed with snow before snowfall: " + meshedCoverage.get(section));

            // Snow arrives while the renderer ignores every rebuild request, as Sodium does during a first build.
            dropRebuilds = true;
            long before = count(context, " rebuilds=");
            context.runOnClient(c -> FrostedFoliageClient.system().setPreview(true));
            context.waitFor(c -> FrostedFoliageClient.system().coverage(LEAF) > 0.99
                    && FrostedFoliageClient.system().diagnostics().contains("pending=0/0 "), 400);
            check(count(context, " rebuilds=") > before, "The snow change requested no rebuild");
            context.waitTicks(20);
            check(meshedCoverage.get(section) == 0, "A dropped rebuild request still reached the renderer");
            dropRebuilds = false;

            // Nothing about the snow changes from here on, so only a retry can bring it to the mesh.
            context.waitFor(c -> meshedCoverage.get(section) > 0.99, 400);
            // Once every snowy mesh is current, the retries stop.
            context.waitFor(c -> FrostedFoliageClient.system().diagnostics().contains(" stale=0 "), 200);
            world.getConnection().waitForChunksRender(false);
            context.runOnClient(c -> { if (!c.gui.hud.isHidden()) c.gui.hud.toggle(); });
            context.takeScreenshot(TestScreenshotOptions.of("lost-rebuild-recovered").withSize(1280, 800));
            context.runOnClient(c -> FrostedFoliageClient.LOGGER.info("LOST REBUILD recovered: meshed coverage {} after dropped requests; {}",
                    meshedCoverage.get(section), FrostedFoliageClient.system().diagnostics()));

            // Ground snow beneath the canopy follows real snow nearby. Snow placed in the next chunk changes this
            // capture without touching this section, so only the mod's own rebuild request can redraw it.
            context.waitFor(c -> groundMeshedLayers >= 0, 200);
            check(groundMeshedLayers == 0, "Ground beneath the canopy was meshed with snow before any fell: " + groundMeshedLayers);
            dropRebuilds = true;
            long groundBefore = count(context, " groundRebuilds=");
            server.runCommand("setblock -5 64 1 minecraft:snow[layers=3]");
            context.waitFor(c -> FrostedFoliageClient.system().ground().layers(GROUND) > 0 && FrostedFoliageClient.system().ground().settled(), 400);
            check(count(context, " groundRebuilds=") > groundBefore, "The ground snow change requested no rebuild");
            context.waitTicks(20);
            check(groundMeshedLayers == 0, "A dropped ground rebuild request still reached the renderer");
            dropRebuilds = false;
            context.waitFor(c -> groundMeshedLayers > 0, 400);
            context.waitFor(c -> FrostedFoliageClient.system().diagnostics().contains(" groundStale=0"), 200);
            context.runOnClient(c -> FrostedFoliageClient.LOGGER.info("LOST REBUILD ground recovered: meshed layers {} after dropped requests; {}",
                    groundMeshedLayers, FrostedFoliageClient.system().diagnostics()));
            context.runOnClient(c -> FrostedFoliageClient.system().setPreview(false));
        } finally {
            dropRebuilds = false;
        }
    }

    private static long count(ClientGameTestContext context, String label) {
        String status = context.computeOnClient(c -> FrostedFoliageClient.system().diagnostics());
        var match = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(label) + "(\\d+)").matcher(status);
        if (!match.find()) throw new AssertionError("No" + label + "count in " + status);
        return Long.parseLong(match.group(1));
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
