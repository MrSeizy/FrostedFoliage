package dev.frostedfoliage.client;

import net.fabricmc.api.ClientModInitializer;
import dev.frostedfoliage.client.render.SnowModels;
import dev.frostedfoliage.client.render.SnowSpriteSource;
import dev.frostedfoliage.client.world.SnowSystem;
import dev.frostedfoliage.config.ConfigStore;
import dev.frostedfoliage.config.SnowConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.SpriteSourceRegistry;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class FrostedFoliageClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("frostedfoliage");
    private static SnowSystem system;
    private static ConfigStore store;
    public static SnowSystem system() { return system; }
    public static String configLoadError() { return store.loadError(); }
    public static void saveConfig(SnowConfig config) throws java.io.IOException { store.save(config); applyConfig(config); }

    /** Apply on the client thread; refresh generated depth masks only when their inputs change. */
    public static java.util.concurrent.CompletableFuture<Void> applyConfig(SnowConfig config) {
        SnowConfig previous = system.config();
        system.configure(config);
        boolean foliageRulesChanged = previous.moddedFoliage() != config.moddedFoliage()
                || !previous.includeBlocks().equals(config.includeBlocks()) || !previous.excludeBlocks().equals(config.excludeBlocks());
        if (previous.snowDepth() != config.snowDepth() || previous.plantSnowDepth() != config.plantSnowDepth()
                || previous.plantsEnabled() != config.plantsEnabled() || !previous.extraTextures().equals(config.extraTextures())
                || (foliageRulesChanged && SnowSpriteSource.needsFoliageReload())) {
            return net.minecraft.client.Minecraft.getInstance().reloadResourcePacks();
        }
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    @Override
    public void onInitializeClient() {
        var configDir = FabricLoader.getInstance().getConfigDir();
        store = new ConfigStore(configDir.resolve("frostedfoliage.json"), configDir.resolve("snowytrees.json"));
        SnowConfig config = SnowConfig.defaults();
        try { config = store.load(); } catch (java.io.IOException e) { LOGGER.error("Using defaults; original config retained", e); }
        system = new SnowSystem(config);
        SpriteSourceRegistry.register(Identifier.fromNamespaceAndPath("frostedfoliage", "snow_masks"), SnowSpriteSource.CODEC);
        SnowModels.register(system);
        ClientTickEvents.END_CLIENT_TICK.register(system::tick);
        ClientChunkEvents.CHUNK_LOAD.register(system::load);
        ClientChunkEvents.CHUNK_UNLOAD.register(system::unload);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> system.reset(null));
        CommonLifecycleEvents.TAGS_LOADED.register((registries, clientSide) -> {
            if (clientSide) net.minecraft.client.Minecraft.getInstance().execute(() -> {
                system.tagsLoaded();
                if (SnowSpriteSource.needsFoliageReload()) net.minecraft.client.Minecraft.getInstance().reloadResourcePacks();
            });
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            var command = dispatcher.register(literal("frostedfoliage")
                .then(literal("status").executes(ctx -> {
                    String status = system.diagnostics();
                    ctx.getSource().sendFeedback(Component.literal(status)); LOGGER.info(status); return 1;
                }))
                .then(literal("reload").executes(ctx -> {
                    try { applyConfig(store.load()); ctx.getSource().sendFeedback(Component.literal("Frosted Foliage settings reloaded; changed depth/texture masks refresh automatically.")); return 1; }
                    catch (java.io.IOException e) { ctx.getSource().sendError(Component.literal(e.getMessage())); return 0; }
                })));
            dispatcher.register(literal("snowytrees").redirect(command));
        });
        LOGGER.info("Frosted Foliage ready: Minecraft 26.3, Fabric Rendering API, client-only simulation");
    }
}
