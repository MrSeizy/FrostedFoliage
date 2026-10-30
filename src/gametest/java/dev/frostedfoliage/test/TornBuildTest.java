package dev.frostedfoliage.test;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.config.SnowConfig;
import dev.frostedfoliage.core.BoundedSectionStore;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;

/**
 * A chunk worker is paused inside a section while the snow changes and every rebuild request is dropped, as Sodium does
 * during a section's first build. The resumed worker finishes a torn mesh: blocks before the pause from the old state,
 * the block after it from the new one. The mod must notice (the mesh is stale) and request the section again, in both
 * directions: snow arriving, and snow leaving after its snapshot was discarded.
 */
public final class TornBuildTest {
    /** Coverage each leaf block had when a worker last meshed it, recorded by the test mixin. */
    public static final Map<BlockPos, Double> meshedBlocks = new ConcurrentHashMap<>();
    private static volatile BlockPos pauseAt;
    private static volatile CountDownLatch paused = new CountDownLatch(0), resume = new CountDownLatch(0);
    // The canopy lies in one section; workers visit FIRST before PAUSE in both vanilla's and Sodium's block order.
    private static final BlockPos FIRST = new BlockPos(1, 70, 1), PAUSE = new BlockPos(3, 71, 3);
    private static final List<BlockPos> CANOPY = List.copyOf(BlockPos.betweenClosedStream(1, 70, 1, 3, 71, 3).map(BlockPos::immutable).toList());

    /** Called by the test mixin for every meshed leaf block; logs the two blocks this test watches. */
    public static void trace(BlockPos pos, double coverage) {
        if (pos.equals(FIRST) || pos.equals(PAUSE)) {
            FrostedFoliageClient.LOGGER.info("TORN BUILD meshed {} at {} on {}", pos.toShortString(), coverage, Thread.currentThread().getName());
        }
    }

    /** Called by the test mixin from the meshing worker; blocks that worker once at the requested position. */
    public static void pauseIfRequested(BlockPos pos) {
        BlockPos at = pauseAt;
        if (at == null || !at.equals(pos)) return;
        pauseAt = null;
        FrostedFoliageClient.LOGGER.info("TORN BUILD paused at {} on {} after meshing {}", pos, Thread.currentThread().getName(), meshedBlocks.keySet());
        paused.countDown();
        try { resume.await(60, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public void run(ClientGameTestContext context) {
        SnowConfig config = SnowConfig.builder().snowDepth(1).quality(SnowConfig.Quality.HIGH).maxVisualUpdatesPerSecond(2).build();
        context.runOnClient(c -> { c.options.pauseOnLostFocus = false; FrostedFoliageClient.system().configure(config); });
        try (var world = context.worldBuilder().adjustSettings(s -> s.setName("Frosted Foliage - Torn Build")).create()) {
            var server = world.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            server.runCommand("gamerule minecraft:random_tick_speed 0");
            server.runCommand("fill -8 63 -8 8 63 8 minecraft:grass_block");
            server.runCommand("fillbiome -8 48 -8 8 95 8 minecraft:snowy_plains");
            server.runCommand("fill 2 64 2 2 69 2 minecraft:oak_log");
            server.runCommand("fill 1 70 1 3 71 3 minecraft:oak_leaves[persistent=true]");
            server.runCommand("tp @a 2 70 -5 0 10");
            world.getConnection().waitForChunksRender();
            context.waitFor(c -> CANOPY.stream().allMatch(p -> meshedBlocks.getOrDefault(p, -1.0) == 0), 200);
            // Prime the section: snapshots are only captured while snow is active, and the paused job below must
            // start with a snapshot already acknowledged from the old (bare) state, not with no acknowledgment at all.
            context.runOnClient(c -> FrostedFoliageClient.system().setPreview(true));
            await(context, c -> CANOPY.stream().allMatch(p -> meshedBlocks.get(p) > 0.99), 400, "priming snow never reached every block");
            context.runOnClient(c -> FrostedFoliageClient.system().setPreview(false));
            await(context, c -> CANOPY.stream().allMatch(p -> meshedBlocks.get(p) == 0), 400, "priming snow never melted from every block");
            await(context, c -> stale(c) == 0 && unmeshed(c) == 0, 200, "priming left the section unacknowledged");
            settle(context);

            // Snow arrives while a worker is halfway through the section and every request is dropped.
            pauseMeshing(context);
            LostRebuildTest.dropRebuilds = true;
            context.runOnClient(c -> FrostedFoliageClient.system().setPreview(true));
            context.waitFor(c -> FrostedFoliageClient.system().coverage(FIRST) > 0.99
                    && FrostedFoliageClient.system().diagnostics().contains("pending=0/0 "), 400);
            resumeMeshing(context);
            torn(0, 1);
            // Keep dropping until the mod's own bookkeeping has flagged the torn mesh; the renderer may otherwise
            // rebuild the section for reasons of its own and hide what is being checked.
            await(context, c -> stale(c) > 0, 80, "the torn mesh was never found stale");
            LostRebuildTest.dropRebuilds = false;
            await(context, c -> CANOPY.stream().allMatch(p -> meshedBlocks.get(p) > 0.99), 400, "snow never reached every block");
            await(context, c -> stale(c) == 0, 200, "retries never settled");
            context.runOnClient(c -> FrostedFoliageClient.LOGGER.info("TORN BUILD recovered: snow reached every block; {}", FrostedFoliageClient.system().diagnostics()));

            // Snow leaves: the leaves are excluded while a worker is halfway through the snowy section, its snapshot is
            // discarded, and the cleanup request is dropped. The torn mesh keeps snow on the blocks before the pause.
            settle(context);
            pauseMeshing(context);
            WeakReference<Object> evictedSnapshot = context.computeOnClient(c -> snapshotReference());
            LostRebuildTest.dropRebuilds = true;
            context.runOnClient(c -> FrostedFoliageClient.system().configure(config.toBuilder().excludeBlocks(List.of("minecraft:oak_leaves")).build()));
            context.waitFor(c -> FrostedFoliageClient.system().diagnostics().contains("snapshots=0 ")
                    && FrostedFoliageClient.system().diagnostics().contains("pending=0/0 "), 400);
            // The paused worker and its old acknowledgment remain alive while the cache has discarded this capture.
            // Neither may pin the snapshot's array just to remember which version was meshed.
            for (int attempt = 0; attempt < 10 && evictedSnapshot.get() != null; attempt++) {
                System.gc();
                context.waitTicks(2);
            }
            check(evictedSnapshot.get() == null, "An evicted foliage snapshot is still strongly retained by mesh bookkeeping");
            FrostedFoliageClient.LOGGER.info("TORN BUILD memory: evicted snapshot reclaimed while its stale mesh awaits cleanup");
            resumeMeshing(context);
            torn(1, 0);
            await(context, c -> stale(c) > 0, 80, "the torn mesh was never found stale");
            LostRebuildTest.dropRebuilds = false;
            await(context, c -> CANOPY.stream().allMatch(p -> meshedBlocks.get(p) == 0), 400, "excluded leaves kept their snow");
            await(context, c -> stale(c) == 0, 200, "retries never settled");
            context.runOnClient(c -> FrostedFoliageClient.LOGGER.info("TORN BUILD cleaned: excluded leaves lost their snow; {}", FrostedFoliageClient.system().diagnostics()));
            context.runOnClient(c -> { FrostedFoliageClient.system().setPreview(false); FrostedFoliageClient.system().configure(config); });
        } finally {
            LostRebuildTest.dropRebuilds = false; pauseAt = null; resume.countDown();
        }
    }

    /**
     * Waits until no chunk has loaded for 100 ticks. Chunks still streaming in make the renderer rebuild their
     * neighbours, which would refresh the canopy's acknowledgment on its own and hide what this test checks.
     */
    private static void settle(ClientGameTestContext context) {
        for (int quiet = 0, last = -1; quiet < 100; ) {
            int loaded = context.computeOnClient(c -> c.level.getChunkSource().getLoadedChunksCount());
            if (loaded == last) quiet += 20; else { quiet = 0; last = loaded; }
            context.waitTicks(20);
        }
        context.waitFor(c -> FrostedFoliageClient.system().ground().settled(), 1200);
    }

    /** The paused job must have meshed FIRST before pausing and PAUSE after resuming, from different states. */
    private static void torn(double first, double pause) {
        Double atFirst = meshedBlocks.get(FIRST), atPause = meshedBlocks.get(PAUSE);
        check(atFirst != null && atPause != null && Math.abs(atFirst - first) < 0.01 && Math.abs(atPause - pause) < 0.01,
                "The paused worker did not finish a torn mesh (first block " + atFirst + ", paused block " + atPause + "): " + meshedBlocks);
    }

    /** Requests a rebuild of the canopy's section and lets the client run until a worker is paused at PAUSE. */
    private static void pauseMeshing(ClientGameTestContext context) {
        CANOPY.forEach(meshedBlocks::remove); // Only blocks the paused job itself meshes may count.
        paused = new CountDownLatch(1); resume = new CountDownLatch(1); pauseAt = PAUSE;
        context.runOnClient(c -> c.levelExtractor.setSectionDirty(0, 4, 0));
        context.waitFor(c -> paused.getCount() == 0, 200);
    }

    private static void resumeMeshing(ClientGameTestContext context) {
        meshedBlocks.remove(PAUSE);
        resume.countDown();
        context.waitFor(c -> meshedBlocks.containsKey(PAUSE), 100);
        context.waitTicks(5);
    }

    /** Like waitFor, but logs the mod's diagnostics while waiting and reports them on timeout. */
    private static void await(ClientGameTestContext context, java.util.function.Predicate<net.minecraft.client.Minecraft> predicate, int ticks, String failure) {
        for (int waited = 0; waited < ticks; waited += 20) {
            if (context.computeOnClient(predicate::test)) return;
            context.runOnClient(c -> FrostedFoliageClient.LOGGER.info("TORN BUILD waiting: {} blocks={}", FrostedFoliageClient.system().diagnostics(), meshedBlocks));
            context.waitTicks(20);
        }
        if (context.computeOnClient(predicate::test)) return;
        throw new AssertionError(failure + ": " + context.computeOnClient(c -> FrostedFoliageClient.system().diagnostics()) + " blocks=" + meshedBlocks);
    }

    private static int stale(net.minecraft.client.Minecraft client) { return count(" stale="); }
    private static int unmeshed(net.minecraft.client.Minecraft client) { return count(" unmeshed="); }
    private static int count(String label) {
        var match = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(label) + "(\\d+)").matcher(FrostedFoliageClient.system().diagnostics());
        if (!match.find()) throw new AssertionError("No" + label + " count");
        return Integer.parseInt(match.group(1));
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static WeakReference<Object> snapshotReference() {
        try {
            var system = FrostedFoliageClient.system();
            var field = system.getClass().getDeclaredField("snapshots"); field.setAccessible(true);
            Object snapshot = ((BoundedSectionStore<?>) field.get(system)).get(net.minecraft.core.SectionPos.asLong(FIRST));
            check(snapshot != null, "No snapshot to exercise eviction");
            return new WeakReference<>(snapshot);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
