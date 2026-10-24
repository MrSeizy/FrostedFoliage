package dev.frostedfoliage.test;

import com.google.gson.Gson;
import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.client.render.SnowSpriteSource;
import dev.frostedfoliage.config.SnowConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.block.Blocks;

/** Exercise actual tag packets, reconnects, and config-driven atlas invalidation. */
public final class FoliageReloadTest {
    private static final Identifier GLASS = Identifier.withDefaultNamespace("block/glass");

    public void run(ClientGameTestContext context) {
        context.runOnClient(c -> c.options.pauseOnLostFocus = false);
        apply(context, SnowConfig.defaults());
        TestWorldSave save;
        Path tag;
        SnowSpriteSource.Catalog tagged;
        try (var world = context.worldBuilder().create()) {
            save = world.getWorldSave();
            if (Boolean.getBoolean("frostedfoliage.test.shaderExpected")) {
                check(context.computeOnClient(c -> net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse()),
                        "Requested shader pack is not active");
            }
            noReloadForOrdinaryRules(context);
            Path pack = save.getSaveDirectory().resolve("datapacks/frostedfoliage-tag-test");
            tag = pack.resolve("data/minecraft/tags/block/leaves.json");
            var format = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA);
            write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"Foliage tag reload regression\",\"min_format\":["
                    + format.major() + "," + format.minor() + "],\"max_format\":[" + format.major() + "," + format.minor() + "]}}");
            write(tag, "{\"values\":[\"minecraft:glass\"]}");
            world.getServer().runCommand("reload");
            context.waitFor(c -> SnowSpriteSource.catalog().extraLeafBlocks().contains(Blocks.GLASS)
                    && c.gui.overlay() == null, 1200);
            check(SnowSpriteSource.catalog().sources().contains(GLASS), "Tag-added foliage texture was not discovered");
            tagged = SnowSpriteSource.catalog();
        }
        check(context.computeOnClient(c -> !FrostedFoliageClient.system().foliage().test(Blocks.GLASS.defaultBlockState())),
                "Disconnect did not clear tag-only eligibility");
        context.runOnClient(c -> {
            var config = FrostedFoliageClient.system().config();
            check(FrostedFoliageClient.applyConfig(config).isDone(), "Unchanged main-menu settings reloaded stale world tags");
            var gson = new Gson();
            var json = gson.toJsonTree(config).getAsJsonObject();
            json.addProperty("meltingSeconds", config.meltingSeconds() + 1);
            check(FrostedFoliageClient.applyConfig(gson.fromJson(json, SnowConfig.class)).isDone(), "Timing-only main-menu settings reloaded stale world tags");
            check(FrostedFoliageClient.applyConfig(config).isDone(), "Restoring timing-only settings reloaded stale world tags");
        });
        context.waitTicks(20);
        check(SnowSpriteSource.catalog() == tagged, "Main-menu settings replaced the tagged atlas");
        try (var world = save.open()) {
            context.waitFor(c -> FrostedFoliageClient.system().foliage().test(Blocks.GLASS.defaultBlockState())
                    && c.gui.overlay() == null, 1200);
            context.waitTicks(40);
            check(SnowSpriteSource.catalog() == tagged, "Rejoining unchanged foliage tags reloaded the atlas");
            noReloadForOrdinaryRules(context);
            write(tag, "{\"values\":[]}");
            world.getServer().runCommand("reload");
            context.waitFor(c -> !FrostedFoliageClient.system().foliage().test(Blocks.GLASS.defaultBlockState())
                    && SnowSpriteSource.catalog().extraLeafBlocks().isEmpty() && c.gui.overlay() == null, 1200);
            check(!SnowSpriteSource.catalog().sources().contains(GLASS), "Removed tag-only texture remained in the atlas catalog");

            var gson = new Gson();
            var json = gson.toJsonTree(SnowConfig.defaults()).getAsJsonObject();
            json.add("includeBlocks", gson.toJsonTree(java.util.List.of("minecraft:glass")));
            apply(context, gson.fromJson(json, SnowConfig.class));
            check(SnowSpriteSource.catalog().sources().contains(GLASS), "Explicit custom block did not refresh texture discovery");
            apply(context, SnowConfig.defaults());
            check(!SnowSpriteSource.catalog().sources().contains(GLASS), "Removing custom block failed to refresh texture discovery");
        }
        FrostedFoliageClient.LOGGER.info("FOLIAGE RELOAD REGRESSIONS PASSED: ordinary rules, tag add/remove, rejoin and custom inclusion");
    }

    private static void noReloadForOrdinaryRules(ClientGameTestContext context) {
        var before = SnowSpriteSource.catalog();
        context.runOnClient(c -> {
            var gson = new Gson();
            var json = gson.toJsonTree(FrostedFoliageClient.system().config()).getAsJsonObject();
            json.add("excludeBlocks", gson.toJsonTree(java.util.List.of("minecraft:oak_leaves", "minecraft:fern")));
            json.addProperty("moddedFoliage", false);
            check(FrostedFoliageClient.applyConfig(gson.fromJson(json, SnowConfig.class)).isDone(), "Ordinary foliage rules started a reload");
            check(FrostedFoliageClient.applyConfig(SnowConfig.defaults()).isDone(), "Restoring ordinary foliage rules started a reload");
        });
        context.waitTicks(40);
        check(SnowSpriteSource.catalog() == before, "Ordinary foliage rules replaced the atlas catalog");
    }

    private static void apply(ClientGameTestContext context, SnowConfig config) {
        var reload = context.computeOnClient(c -> FrostedFoliageClient.applyConfig(config));
        context.waitFor(c -> reload.isDone(), 1200); reload.join();
        context.waitFor(c -> c.gui.overlay() == null, 1200);
    }

    private static void write(Path file, String contents) {
        try { Files.createDirectories(file.getParent()); Files.writeString(file, contents); }
        catch (IOException e) { throw new AssertionError(e); }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
