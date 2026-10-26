package dev.frostedfoliage.client;

import net.fabricmc.api.ClientModInitializer;
import dev.frostedfoliage.client.render.SnowModels;
import dev.frostedfoliage.client.render.SnowSpriteSource;
import dev.frostedfoliage.client.world.SnowSystem;
import dev.frostedfoliage.config.ConfigDiff;
import dev.frostedfoliage.config.ConfigStore;
import dev.frostedfoliage.config.SnowConfig;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.SpriteSourceRegistry;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class FrostedFoliageClient implements ClientModInitializer {
    public static final String MOD_ID = "frostedfoliage";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static SnowSystem system;
    private static ConfigStore store;
    public static SnowSystem system() { return system; }
    public static String configLoadError() { return store.loadError(); }
    public static void saveConfig(SnowConfig config) throws IOException { store.save(config); applyConfig(config); }

    /** Apply on the client thread; refresh generated depth masks only when their inputs change. */
    public static CompletableFuture<Void> applyConfig(SnowConfig config) {
        var diff = new ConfigDiff(system.config(), config);
        system.configure(config);
        if (diff.textures() || (diff.blockRules() && SnowSpriteSource.needsFoliageReload())) {
            return Minecraft.getInstance().reloadResourcePacks();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onInitializeClient() {
        var configDir = FabricLoader.getInstance().getConfigDir();
        // snowytrees.json and /snowytrees predate the rename; both only redirect to the current names.
        store = new ConfigStore(configDir.resolve(MOD_ID + ".json"), configDir.resolve("snowytrees.json"));
        SnowConfig config = SnowConfig.defaults();
        try { config = store.load(); } catch (IOException e) { LOGGER.error("Using defaults; original config retained", e); }
        system = new SnowSystem(config);
        SpriteSourceRegistry.register(Identifier.fromNamespaceAndPath(MOD_ID, "snow_masks"), SnowSpriteSource.CODEC);
        SnowModels.register(system);
        ClientTickEvents.END_CLIENT_TICK.register(system::tick);
        ClientChunkEvents.CHUNK_LOAD.register(system::load);
        ClientChunkEvents.CHUNK_UNLOAD.register(system::unload);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> system.reset(null));
        CommonLifecycleEvents.TAGS_LOADED.register((registries, clientSide) -> {
            if (clientSide) Minecraft.getInstance().execute(() -> {
                system.tagsLoaded();
                if (SnowSpriteSource.needsFoliageReload()) Minecraft.getInstance().reloadResourcePacks();
            });
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            var command = dispatcher.register(literal(MOD_ID)
                .then(literal("status").executes(ctx -> {
                    String status = system.diagnostics();
                    ctx.getSource().sendFeedback(Component.literal(status)); LOGGER.info(status); return 1;
                }))
                .then(literal("preview").executes(ctx -> {
                    boolean on = !system.preview();
                    system.setPreview(on);
                    String key = !on ? "off" : system.config().enabled() ? "on" : "disabled";
                    ctx.getSource().sendFeedback(Component.translatable("frostedfoliage.command.preview." + key)); return 1;
                }))
                .then(literal("reload").executes(ctx -> {
                    try { applyConfig(store.load()); ctx.getSource().sendFeedback(Component.translatable("frostedfoliage.command.reload")); return 1; }
                    catch (IOException e) { ctx.getSource().sendError(Component.literal(e.getMessage())); return 0; }
                })));
            dispatcher.register(literal("snowytrees").redirect(command));
        });
        String version = FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        LOGGER.info("Frosted Foliage {} ready: Fabric Rendering API, client-only simulation", version);
    }
}
