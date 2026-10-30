package dev.frostedfoliage.test;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.config.SnowConfig;
import dev.frostedfoliage.core.MeshAcks;
import dev.frostedfoliage.core.MeshRevision;
import dev.frostedfoliage.core.SectionRebuildQueue;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;

/** Feeds deferred, evicted meshes through the real tick path while the renderer drops every request. */
public final class RebuildBudgetTest {
    @SuppressWarnings("unchecked")
    public static void run(Minecraft client) {
        var system = FrostedFoliageClient.system();
        var previous = system.config();
        boolean preview = system.preview();
        var sections = new ArrayList<Long>();
        for (int x = -8; x <= 8 && sections.size() < 512; x++) for (int z = -8; z <= 8 && sections.size() < 512; z++) {
            if (!client.level.getChunkSource().hasChunk(x, z)) continue;
            for (int i = 0; i < client.level.getSectionsCount() && sections.size() < 512; i++) {
                sections.add(SectionPos.asLong(x, client.level.getSectionYFromSectionIndex(i), z));
            }
        }
        check(sections.size() == 512, "Not enough loaded sections for the cleanup burst");
        try {
            LostRebuildTest.dropRebuilds = true;
            for (var quality : SnowConfig.Quality.values()) {
                system.reset(client.level);
                system.configure(previous.toBuilder().enabled(false).quality(quality).build());
                system.setPreview(false);
                // No snapshots remain. These acknowledgments represent snow left in meshes whose captures were evicted.
                var foliage = (MeshAcks<MeshRevision>) field(system, "meshed");
                var ground = (MeshAcks<MeshRevision>) field(system.ground(), "meshed");
                for (long key : sections) {
                    foliage.put(key, new MeshRevision(-1, -1, -1));
                    ground.put(key, new MeshRevision(-1, 0, -1));
                }
                int budget = 2 * system.config().rebuildsPerTick(), peak = 0;
                for (int tick = 0; tick < 80; tick++) {
                    long before = (long) field(system, "sectionRebuilds");
                    system.tick(client);
                    int dispatched = Math.toIntExact((long) field(system, "sectionRebuilds") - before);
                    peak = Math.max(peak, dispatched);
                    check(dispatched <= budget, "Cleanup bypassed the " + quality + " budget: " + dispatched);
                    check(((SectionRebuildQueue) field(system, "rebuildQueue")).size() <= 64, "Dispatch queue exceeded its capacity");
                }
                check(peak == budget, "The fixture did not saturate the dispatch budget");
                check((long) field(system, "rebuilds") > 0 && (long) field(system.ground(), "rebuilds") > 0,
                        "One cleanup producer was starved");
                FrostedFoliageClient.LOGGER.info("CLEANUP BUDGET PASS: {} acknowledged sections in each source, {} peak={}/tick", sections.size(), quality, peak);
            }
        } finally {
            LostRebuildTest.dropRebuilds = false;
            system.reset(client.level); system.configure(previous); system.setPreview(preview);
        }
    }

    private static Object field(Object owner, String name) {
        try {
            var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
