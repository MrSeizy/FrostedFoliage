package dev.frostedfoliage.test;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.client.render.SnowSpriteSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;

/** A real resource reload must tolerate broken plant definitions without dropping the pack. */
public final class MalformedPlantPackTest {
    public void run(ClientGameTestContext context) {
        Path pack;
        try {
            Path directory = context.computeOnClient(c -> c.getResourcePackDirectory());
            Files.createDirectories(directory);
            pack = Files.createTempDirectory(directory, "frostedfoliage-malformed-test-");
        } catch (IOException e) { throw new AssertionError("Cannot create test pack", e); }
        var previous = context.computeOnClient(c -> List.copyOf(c.getResourcePackRepository().getSelectedIds()));
        String packId = "file/" + pack.getFileName();
        try {
            var format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
            write(pack, "pack.mcmeta", "{\"pack\":{\"description\":\"Malformed plant regression fixture\",\"min_format\":["
                    + format.major() + "," + format.minor() + "],\"max_format\":[" + format.major() + "," + format.minor() + "]}}");
            var definitions = Map.of(
                    "blockstates/dandelion.json", "{\"variants\":[]}",
                    "blockstates/poppy.json", "{\"multipart\":{}}",
                    "blockstates/blue_orchid.json", "{\"multipart\":[7,{\"apply\":{\"model\":\"minecraft:block/blue_orchid\"}}]}",
                    "models/block/allium.json", "{\"parent\":\"minecraft:block/cross\",\"textures\":[]}",
                    "blockstates/azure_bluet.json", "[]",
                    "blockstates/red_tulip.json", "{broken");
            for (var entry : definitions.entrySet()) write(pack, "assets/minecraft/" + entry.getKey(), entry.getValue());
            context.runOnClient(c -> {
                var repository = c.getResourcePackRepository();
                repository.reload();
                var selected = new ArrayList<>(previous);
                selected.add(packId);
                repository.setSelected(selected);
                check(repository.getSelectedIds().contains(packId), "Malformed test pack was not selected");
            });
            reload(context);
            context.runOnClient(c -> {
                check(c.getResourcePackRepository().getSelectedIds().contains(packId), "Reload silently discarded the test pack");
                var resource = c.getResourceManager().getResource(Identifier.withDefaultNamespace("blockstates/dandelion.json")).orElseThrow();
                check(resource.sourcePackId().equals(packId), "Malformed resource is not active");
                var plants = SnowSpriteSource.catalog().plantSources();
                check(plants.containsKey(Identifier.withDefaultNamespace("block/fern")), "Healthy plant masks were lost");
                check(plants.containsKey(Identifier.withDefaultNamespace("block/blue_orchid")), "Valid multipart sibling was lost");
                check(!plants.containsKey(Identifier.withDefaultNamespace("block/dandelion")), "Invalid variants were not skipped");
                check(!plants.containsKey(Identifier.withDefaultNamespace("block/allium")), "Invalid textures were not skipped");
            });
            FrostedFoliageClient.LOGGER.info("MALFORMED PLANT PACK REGRESSION PASSED: six malformed resources, healthy masks retained");
        } catch (IOException e) { throw new AssertionError("Cannot write test pack", e); }
        finally {
            context.runOnClient(c -> c.getResourcePackRepository().setSelected(previous));
            reload(context);
            try (var files = Files.walk(pack)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            } catch (IOException e) { throw new AssertionError("Cannot remove test pack", e); }
        }
    }

    private static void write(Path pack, String name, String content) throws IOException {
        Path file = pack.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void reload(ClientGameTestContext context) {
        var reload = context.computeOnClient(c -> c.reloadResourcePacks());
        context.waitFor(c -> reload.isDone(), 1200);
        reload.join();
        context.waitFor(c -> c.gui.overlay() == null, 1200);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
