package dev.frostedfoliage.test;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.client.world.PlantTypes;
import dev.frostedfoliage.config.SnowConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Opt-in photography session. Terrain, biomes and vegetation come entirely from normal world generation. */
public final class NaturalGalleryCapture {
    private static final Path DIRECTORY = Path.of("natural-gallery");
    private static final Path REQUEST = DIRECTORY.resolve("request.json"), RESPONSE = DIRECTORY.resolve("response.json");
    private final com.google.gson.Gson json = new GsonBuilder().setPrettyPrinting().create();
    private int lastId = -1;

    public void run(ClientGameTestContext context) {
        try {
            Files.createDirectories(DIRECTORY);
            Files.deleteIfExists(REQUEST);
            context.runOnClient(c -> {
                c.options.renderDistance().set(12);
                c.options.pauseOnLostFocus = false;
                c.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
                c.options.cutoutLeaves().set(true);
                c.options.fov().set(60);
                c.options.bobView().set(false);
                c.options.fovEffectScale().set(0.0);
                FrostedFoliageClient.system().configure(SnowConfig.builder().plantSnowDepth(0.5).plantMaximumCoverage(0.65)
                        .accumulationSeconds(16).persistenceSeconds(86400).quality(SnowConfig.Quality.HIGH)
                        .maxVisualUpdatesPerSecond(2).maxCachedSections(8192).build());
            });
            for (long seed : new long[]{20261023L, 314159265L}) {
                try (var world = context.worldBuilder().adjustSettings(s -> {
                    var normal = s.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL);
                    s.setWorldType(new WorldCreationUiState.WorldTypeEntry(normal));
                    s.setName("Frosted Foliage Gallery " + seed);
                    s.setSeed(Long.toString(seed));
                    s.setGenerateStructures(true);
                    s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                    s.setDifficulty(Difficulty.PEACEFUL);
                    s.setAllowCommands(true);
                }).create()) {
                    var server = world.getServer();
                    server.runOnServer(s -> {
                        if (s.overworld().getChunkSource().getGenerator() instanceof FlatLevelSource)
                            throw new AssertionError("Gallery requires normal terrain");
                    });
                    server.runCommand("gamemode creative @a");
                    server.runCommand("time set 2500");
                    server.runCommand("gamerule minecraft:advance_time false");
                    server.runCommand("gamerule minecraft:advance_weather false");
                    server.runCommand("weather rain 100000");
                    fly(context, server);
                    context.runOnClient(c -> {
                        if (!c.gui.hud.isHidden()) c.gui.hud.toggle();
                        if (Boolean.getBoolean("frostedfoliage.test.shaderExpected")
                                && !net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse())
                            throw new AssertionError("Gallery shader pack is inactive");
                    });
                    JsonObject ready = new JsonObject(); ready.addProperty("seed", seed); ready.addProperty("status", "ready");
                    respond(lastId, ready);
                    if (Boolean.getBoolean("frostedfoliage.test.capture")) {
                        new FoliageCaptureTest().run(context, world);
                        return;
                    }
                    boolean next = false;
                    while (!next) {
                        context.waitTicks(2);
                        if (!Files.exists(REQUEST)) continue;
                        JsonObject request = JsonParser.parseString(Files.readString(REQUEST)).getAsJsonObject();
                        int id = request.get("id").getAsInt();
                        if (id <= lastId) continue;
                        lastId = id;
                        String action = request.get("action").getAsString();
                        JsonObject result;
                        try {
                            if (action.equals("finish")) { respond(id, status(context)); return; }
                            if (action.equals("next")) { next = true; result = status(context); }
                            else result = execute(context, world, request);
                            result.addProperty("seed", seed); respond(id, result);
                        } catch (Exception e) {
                            result = new JsonObject(); result.addProperty("error", e.toString()); respond(id, result);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonObject execute(ClientGameTestContext context, TestSingleplayerContext world, JsonObject request) throws Exception {
        var server = world.getServer();
        String action = request.get("action").getAsString();
        if (action.equals("locate")) {
            String biome = request.get("biome").getAsString();
            return server.computeOnServer(s -> {
                var key = ResourceKey.create(Registries.BIOME, Identifier.parse(biome));
                BlockPos start = new BlockPos(integer(request, "x", 0), integer(request, "y", 100), integer(request, "z", 0));
                var match = s.overworld().findClosestBiome3d(b -> b.is(key), start, 6400, 32, 64);
                JsonObject result = new JsonObject();
                if (match == null) result.addProperty("error", "Biome not found: " + biome);
                else { result.addProperty("biome", biome); result.add("position", coordinates(match.getFirst())); }
                return result;
            });
        }
        if (action.equals("move")) {
            double x = number(request, "x", 0), y = number(request, "y", 100), z = number(request, "z", 0);
            double yaw = number(request, "yaw", 0), pitch = number(request, "pitch", 20);
            if (request.has("targetX")) {
                double dx = request.get("targetX").getAsDouble() - x, dz = request.get("targetZ").getAsDouble() - z;
                double dy = request.get("targetY").getAsDouble() - (y + 1.62);
                yaw = Math.toDegrees(Math.atan2(-dx, dz)); pitch = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
            }
            server.runCommand(String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f %.3f %.3f", x, y, z, yaw, pitch));
            fly(context, server);
            context.runOnClient(c -> c.options.fov().set(integer(request, "fov", 60)));
            context.waitTicks(integer(request, "wait", 240));
            world.getConnection().waitForChunksRender(false, 2400);
        } else if (action.equals("weather")) {
            String weather = request.get("value").getAsString();
            if (!weather.equals("rain") && !weather.equals("clear")) throw new IllegalArgumentException("Unsupported weather");
            server.runCommand("weather " + weather + " 100000");
            context.waitTicks(integer(request, "wait", 160));
        } else if (action.equals("time")) {
            server.runCommand("time set " + integer(request, "value", 2500)); context.waitTicks(30);
        } else if (action.equals("depth")) {
            var reload = context.computeOnClient(c -> {
                var config = FrostedFoliageClient.system().config().withSnowDepth(number(request, "leaf", 0.5))
                        .withPlants(true, number(request, "plant", 0.5), number(request, "coverage", 0.65));
                return FrostedFoliageClient.applyConfig(config);
            });
            context.waitFor(c -> reload.isDone() && c.gui.overlay() == null, 2400); reload.join();
            context.waitTicks(100); world.getConnection().waitForChunksRender(false, 2400);
        } else if (action.equals("reload")) {
            var reload = context.computeOnClient(c -> c.reloadResourcePacks());
            context.waitFor(c -> reload.isDone() && c.gui.overlay() == null, 2400); reload.join();
            context.waitTicks(100); world.getConnection().waitForChunksRender(false, 2400);
        } else if (action.equals("settings")) {
            if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cloth-config"))
                throw new IllegalStateException("Settings photography requires Cloth Config");
            if (request.has("defaults") && request.get("defaults").getAsBoolean()) {
                var reload = context.computeOnClient(c -> FrostedFoliageClient.applyConfig(SnowConfig.defaults()));
                context.waitFor(c -> reload.isDone() && c.gui.overlay() == null, 2400); reload.join();
                context.waitTicks(100); world.getConnection().waitForChunksRender(false, 2400);
            }
            String category = request.has("category") ? request.get("category").getAsString() : "Plants";
            int index = java.util.List.of("Appearance", "Trees", "Plants", "Weather", "Advanced").indexOf(category);
            if (index < 0) throw new IllegalArgumentException("Unknown settings category: " + category);
            context.runOnClient(c -> {
                c.getWindow().setWindowed(integer(request, "width", 1280), integer(request, "height", 800));
                c.options.guiScale().set(3);
                var screen = dev.frostedfoliage.client.compat.SnowConfigScreen.create(null);
                try {
                    screen.getClass().getField("selectedCategoryIndex").setInt(screen, index);
                } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                c.gui.setScreen(screen);
            });
            context.getInput().setCursorPos(10, 10);
            context.waitTicks(40);
        } else if (action.equals("resize")) {
            context.runOnClient(c -> c.getWindow().setWindowed(
                    integer(request, "width", 960), integer(request, "height", 540)));
            context.waitTicks(integer(request, "wait", 100));
        } else if (action.equals("shaders")) {
            context.runOnClient(c -> net.irisshaders.iris.Iris.toggleShaders(c, request.get("enabled").getAsBoolean()));
            context.waitTicks(100); world.getConnection().waitForChunksRender(false, 2400);
        } else if (action.equals("enabled")) {
            context.runOnClient(c -> {
                var configJson = com.google.gson.JsonParser.parseString(json.toJson(FrostedFoliageClient.system().config())).getAsJsonObject();
                configJson.addProperty("enabled", request.get("value").getAsBoolean());
                FrostedFoliageClient.system().configure(json.fromJson(configJson, SnowConfig.class));
                c.levelExtractor.allChanged();
            });
            context.waitTicks(100); world.getConnection().waitForChunksRender(false, 2400);
        } else if (action.equals("raw")) {
            String name = request.get("name").getAsString();
            if (!name.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Invalid screenshot name");
            context.waitTicks(integer(request, "wait", 40));
            var saved = new java.util.concurrent.CompletableFuture<Void>();
            context.runOnClient(c -> net.minecraft.client.Screenshot.takeScreenshot(c.gameRenderer.mainRenderTarget(), image -> {
                try (image) {
                    Files.createDirectories(Path.of("screenshots"));
                    image.writeToFile(Path.of("screenshots", "raw-" + name + ".png"));
                    saved.complete(null);
                } catch (Exception e) { saved.completeExceptionally(e); }
            }));
            context.waitFor(c -> saved.isDone(), 2400); saved.join();
        } else if (action.equals("shot") || action.equals("shot-legacy")) {
            String name = request.get("name").getAsString();
            if (!name.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Invalid screenshot name");
            context.waitTicks(integer(request, "wait", 40));
            world.getConnection().waitForChunksRender(false, 2400);
            int width = integer(request, "width", 1920), height = integer(request, "height", 1080);
            if (action.equals("shot-legacy")) context.takeScreenshot(TestScreenshotOptions.of(name).withSize(width, height));
            else GameFrameCapture.capture(context, name, width, height);
        } else if (action.equals("survey") || action.equals("plants")) {
            return survey(server, request);
        } else if (!action.equals("status")) throw new IllegalArgumentException("Unknown action: " + action);
        return status(context);
    }

    private static JsonObject survey(TestServerContext server, JsonObject request) {
        return server.computeOnServer(s -> {
            var level = s.overworld();
            int cx = integer(request, "x", 0), cz = integer(request, "z", 0), radius = integer(request, "radius", 48);
            int plantLimit = integer(request, "plantLimit", 80);
            String plantFilter = request.has("plantFilter") ? request.get("plantFilter").getAsString() : "";
            boolean snowOnly = request.has("snowOnly") && request.get("snowOnly").getAsBoolean();
            boolean plantsOnly = request.get("action").getAsString().equals("plants");
            JsonArray terrain = new JsonArray(), leaves = new JsonArray(), plants = new JsonArray();
            JsonObject plantCounts = new JsonObject();
            var pos = new BlockPos.MutableBlockPos();
            for (int x = cx - radius; x <= cx + radius; x++) for (int z = cz - radius; z <= cz + radius; z++) {
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                if (!plantsOnly && (x - cx) % 8 == 0 && (z - cz) % 8 == 0) {
                    JsonArray point = coordinates(new BlockPos(x, ground, z)); point.add(top); terrain.add(point);
                }
                var state = level.getBlockState(pos.set(x, top, z));
                if (state.is(Blocks.SNOW)) state = level.getBlockState(pos.setY(--top));
                if (!plantsOnly && state.is(BlockTags.LEAVES) && (x - cx) % 8 == 0 && (z - cz) % 8 == 0) leaves.add(coordinates(pos));
                for (int y = ground - 1; y <= ground + 2; y++) {
                    state = level.getBlockState(pos.set(x, y, z));
                    if (!PlantTypes.supported(state.getBlock())) continue;
                    String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    var precipitation = level.getBiome(pos).value().getPrecipitationAt(pos, level.getSeaLevel());
                    if (snowOnly && precipitation != Biome.Precipitation.SNOW) break;
                    plantCounts.addProperty(name, plantCounts.has(name) ? plantCounts.get(name).getAsInt() + 1 : 1);
                    if (plants.size() < plantLimit && name.contains(plantFilter)) {
                        JsonArray point = coordinates(pos); point.add(name); point.add(precipitation.name()); plants.add(point);
                    }
                    break;
                }
            }
            JsonObject result = new JsonObject();
            if (!plantsOnly) { result.add("terrain", terrain); result.add("leaves", leaves); }
            result.add("plants", plants); result.add("plantCounts", plantCounts); return result;
        });
    }

    private static void fly(ClientGameTestContext context, TestServerContext server) {
        server.runOnServer(s -> s.getPlayerList().getPlayers().forEach(p -> {
            p.setGameMode(GameType.CREATIVE); p.getAbilities().flying = true; p.onUpdateAbilities();
        }));
        context.runOnClient(c -> { c.player.getAbilities().flying = true; c.player.onUpdateAbilities(); });
    }
    private static JsonObject status(ClientGameTestContext context) {
        return context.computeOnClient(c -> {
            JsonObject result = new JsonObject(); result.add("position", coordinates(c.player.blockPosition()));
            result.addProperty("yaw", c.player.getYRot()); result.addProperty("pitch", c.player.getXRot());
            result.addProperty("creative", c.player.isCreative()); result.addProperty("flying", c.player.getAbilities().flying);
            result.addProperty("windowWidth", c.getWindow().getWidth()); result.addProperty("windowHeight", c.getWindow().getHeight());
            result.addProperty("targetWidth", c.gameRenderer.mainRenderTarget().width); result.addProperty("targetHeight", c.gameRenderer.mainRenderTarget().height);
            result.addProperty("enabled", FrostedFoliageClient.system().config().enabled());
            result.addProperty("diagnostics", FrostedFoliageClient.system().diagnostics()); return result;
        });
    }
    private void respond(int id, JsonObject result) throws Exception {
        result.addProperty("id", id); Files.writeString(RESPONSE, json.toJson(result));
        Files.writeString(DIRECTORY.resolve("response-" + id + ".json"), json.toJson(result));
        FrostedFoliageClient.LOGGER.info("GALLERY RESPONSE {}", json.toJson(result));
    }
    private static JsonArray coordinates(BlockPos pos) { JsonArray a = new JsonArray(); a.add(pos.getX()); a.add(pos.getY()); a.add(pos.getZ()); return a; }
    private static int integer(JsonObject obj, String key, int fallback) { return obj.has(key) ? obj.get(key).getAsInt() : fallback; }
    private static double number(JsonObject obj, String key, double fallback) { return obj.has(key) ? obj.get(key).getAsDouble() : fallback; }
}
