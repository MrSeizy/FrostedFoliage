package dev.frostedfoliage.client.compat;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.config.SnowConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.Tooltip;
import me.shedaniel.clothconfig2.gui.ClothConfigScreen;
import me.shedaniel.clothconfig2.gui.widget.SearchFieldEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.joml.Vector2i;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.function.Supplier;

/** Optional integration. Neither Cloth Config nor Mod Menu is a required dependency. */
public final class SnowConfigScreen {
    private static final ClientTooltipPositioner TOOLTIP_POSITION = (width, height, x, y, tooltipWidth, tooltipHeight) -> {
        int left = x + 12, top = y + 18;
        if (left + tooltipWidth > width - 4) left = Math.max(4, x - 12 - tooltipWidth);
        if (top + tooltipHeight > height - 4) top = Math.max(4, y - tooltipHeight - 18);
        return new Vector2i(left, top);
    };
    private SnowConfigScreen() { }
    public static Screen create(Screen parent) {
        SnowConfig c = FrostedFoliageClient.system().config(), d = SnowConfig.defaults();
        var builder = ConfigBuilder.create().setParentScreen(parent).setTitle(Component.literal("Frosted Foliage"));
        builder.setAfterInitConsumer(screen -> {
            if (screen instanceof ClothConfigScreen cloth) {
                cloth.listWidget.children().removeIf(SearchFieldEntry.class::isInstance);
            }
        });
        var entries = builder.entryBuilder();
        var visual = builder.getOrCreateCategory(Component.literal("Appearance"));
        var trees = builder.getOrCreateCategory(Component.literal("Trees"));
        var plants = builder.getOrCreateCategory(Component.literal("Plants"));
        var simulation = builder.getOrCreateCategory(Component.literal("Weather"));
        var blocks = builder.getOrCreateCategory(Component.literal("Advanced"));
        var enabled = entries.startBooleanToggle(Component.literal("Enable snow"), c.enabled()).setDefaultValue(d.enabled())
                .setTooltipSupplier(tooltip("Show foliage frost and ground snow.")).build();
        var accumulation = entries.startBooleanToggle(Component.literal("Gradual buildup"), c.gradualAccumulation()).setDefaultValue(true)
                .setTooltipSupplier(tooltip("Let snow build up over time. Off applies it immediately.")).build();
        var melting = entries.startBooleanToggle(Component.literal("Gradual melting"), c.gradualMelting()).setDefaultValue(true)
                .setTooltipSupplier(tooltip("Let snow fade after the melt delay. Off removes it in one step.")).build();
        var modded = entries.startBooleanToggle(Component.literal("Modded foliage"), c.moddedFoliage()).setDefaultValue(true)
                .setTooltipSupplier(tooltip("Include leaves and supported plants from other mods.")).build();
        var maximum = entries.startIntSlider(Component.literal("Leaf coverage"), (int) Math.round(c.maximumCoverage()*100), 0, 100).setTextGetter(SnowConfigScreen::percent).setDefaultValue(100)
                .setTooltipSupplier(tooltip("Limit how much of the leaf texture turns snowy.")).build();
        var whiteness = entries.startIntSlider(Component.literal("Snow whiteness"), (int) Math.round(c.whiteness()*100), 0, 100).setTextGetter(SnowConfigScreen::percent).setDefaultValue(90)
                .setTooltipSupplier(tooltip("Make foliage frost lighter or darker.")).build();
        var variation = entries.startIntSlider(Component.literal("Buildup variation"), (int) Math.round(c.variation()*100), 0, 100).setTextGetter(SnowConfigScreen::percent).setDefaultValue(20)
                .setTooltipSupplier(tooltip("Vary how quickly nearby blocks gather snow.")).build();
        var top = entries.startBooleanToggle(Component.literal("Tops first"), c.preferTopFaces()).setDefaultValue(true)
                .setTooltipSupplier(tooltip("Coat leaf tops before sides and undersides.")).build();
        var depth = entries.startIntSlider(Component.literal("Leaf snow depth"), (int) Math.round(c.snowDepth()*100), 0, 100).setTextGetter(SnowConfigScreen::percent)
                .setDefaultValue(50).setTooltipSupplier(tooltip("0% coats tops only. 100% covers the whole leaf block.")).build();
        String loadError = FrostedFoliageClient.configLoadError();
        if (loadError != null) {
            visual.addEntry(entries.startTextDescription(Component.literal(
                    "Configuration could not be loaded. Fix the file and run /frostedfoliage reload before saving. " + loadError)).build());
        }
        visual.addEntry(entries.startTextDescription(Component.literal("These settings affect both trees and plants.")).build());
        visual.addEntry(enabled); visual.addEntry(whiteness); visual.addEntry(variation);
        var treeGround = entries.startBooleanToggle(Component.literal("Ground snow"), c.treeGroundSnow()).setDefaultValue(true)
                .setTooltipSupplier(tooltip("Cover ground beneath trees when nearby ground has snow.")).build();
        trees.addEntry(maximum); trees.addEntry(depth); trees.addEntry(top); trees.addEntry(treeGround);
        var plantsEnabled = entries.startBooleanToggle(Component.literal("Snow on plants"), c.plantsEnabled()).setDefaultValue(true)
                .setTooltipSupplier(tooltip("Add frost to supported plants, including grass and flowers.")).build();
        var plantDepth = entries.startIntSlider(Component.literal("Plant snow depth"), (int) Math.round(c.plantSnowDepth()*100), 0, 100).setTextGetter(SnowConfigScreen::percent)
                .setDefaultValue((int) Math.round(d.plantSnowDepth()*100)).setTooltipSupplier(tooltip("0% is off. 100% coats the whole plant.")).build();
        var plantMaximum = entries.startIntSlider(Component.literal("Plant coverage"), (int) Math.round(c.plantMaximumCoverage()*100), 0, 100).setTextGetter(SnowConfigScreen::percent)
                .setDefaultValue((int) Math.round(d.plantMaximumCoverage()*100)).setTooltipSupplier(tooltip("Lower values leave more of the plant's color showing.")).build();
        var plantGround = entries.startBooleanToggle(Component.literal("Ground snow"), c.plantGroundSnow()).setDefaultValue(true)
                .setTooltipSupplier(tooltip("Cover ground beneath plants when nearby ground has snow.")).build();
        plants.addEntry(plantsEnabled); plants.addEntry(plantDepth); plants.addEntry(plantMaximum); plants.addEntry(plantGround);
        var accSeconds = entries.startDoubleField(Component.literal("Buildup time (s)"), c.accumulationSeconds()).setMin(1).setMax(86400).setDefaultValue(180d)
                .setTooltipSupplier(tooltip("Time to reach full coverage during steady snowfall.")).build();
        var meltSeconds = entries.startDoubleField(Component.literal("Melt time (s)"), c.meltingSeconds()).setMin(1).setMax(86400).setDefaultValue(240d)
                .setTooltipSupplier(tooltip("Time to melt after the delay. Freezing biomes take longer.")).build();
        var persistence = entries.startDoubleField(Component.literal("Melt delay (s)"), c.persistenceSeconds()).setMin(0).setMax(86400).setDefaultValue(30d)
                .setTooltipSupplier(tooltip("Wait this long after snowfall ends. Doubles in freezing biomes.")).build();
        var exposure = entries.startIntSlider(Component.literal("Canopy delay"), (int) Math.round(c.skyExposureSensitivity()*100), 0, 100).setTextGetter(SnowConfigScreen::percent).setDefaultValue(80)
                .setTooltipSupplier(tooltip("Higher values slow snow buildup beneath other leaves.")).build();
        var quality = entries.startEnumSelector(Component.literal("Quality"), SnowConfig.Quality.class, c.quality()).setDefaultValue(SnowConfig.Quality.MEDIUM)
                .setTooltipSupplier(tooltip("Higher settings smooth out buildup but use more processing power.")).build();
        var frequency = entries.startDoubleField(Component.literal("Updates/second"), c.maxVisualUpdatesPerSecond()).setMin(0.05).setMax(2).setDefaultValue(0.5d)
                .setTooltipSupplier(tooltip("Refresh snow more often at higher values. Lower values reduce work.")).build();
        var limit = entries.startIntField(Component.literal("Cache size"), c.maxCachedSections()).setMin(64).setMax(16384).setDefaultValue(4096)
                .setTooltipSupplier(tooltip("Tracks this many 16x16x16 block sections. Higher limits use more memory.")).build();
        simulation.addEntry(accumulation); simulation.addEntry(accSeconds); simulation.addEntry(melting);
        simulation.addEntry(meltSeconds); simulation.addEntry(persistence); simulation.addEntry(exposure);
        blocks.addEntry(modded); blocks.addEntry(quality); blocks.addEntry(frequency); blocks.addEntry(limit);
        blocks.addEntry(entries.startTextDescription(Component.literal("Most players can leave these lists empty. Use them to add missing foliage or keep snow off specific blocks.")).build());
        var include = entries.startStrList(Component.literal("Included blocks"), c.includeBlocks()).setDefaultValue(java.util.List.of())
                .setCellErrorSupplier(SnowConfigScreen::identifierError)
                .setTooltipSupplier(tooltip("Add blocks the mod missed. Use block IDs, such as minecraft:oak_leaves.")).build();
        var exclude = entries.startStrList(Component.literal("Excluded blocks"), c.excludeBlocks()).setDefaultValue(java.util.List.of())
                .setCellErrorSupplier(SnowConfigScreen::identifierError)
                .setTooltipSupplier(tooltip("Keep snow off these blocks, even if included. Example: minecraft:oak_leaves.")).build();
        var textures = entries.startStrList(Component.literal("Extra textures"), c.extraTextures()).setDefaultValue(java.util.List.of())
                .setCellErrorSupplier(SnowConfigScreen::identifierError)
                .setTooltipSupplier(tooltip("Add missing textures for included blocks. Example: minecraft:block/fern.")).build();
        blocks.addEntry(include); blocks.addEntry(exclude); blocks.addEntry(textures);
        builder.setSavingRunnable(() -> {
            var updated = c.toBuilder().enabled(enabled.getValue()).gradualAccumulation(accumulation.getValue())
                    .gradualMelting(melting.getValue()).moddedFoliage(modded.getValue()).maximumCoverage(maximum.getValue()/100d)
                    .whiteness(whiteness.getValue()/100d).variation(variation.getValue()/100d).preferTopFaces(top.getValue())
                    .snowDepth(depth.getValue()/100d).plantsEnabled(plantsEnabled.getValue()).plantSnowDepth(plantDepth.getValue()/100d)
                    .plantMaximumCoverage(plantMaximum.getValue()/100d).accumulationSeconds(accSeconds.getValue())
                    .meltingSeconds(meltSeconds.getValue()).persistenceSeconds(persistence.getValue())
                    .skyExposureSensitivity(exposure.getValue()/100d).quality(quality.getValue())
                    .maxVisualUpdatesPerSecond(frequency.getValue()).maxCachedSections(limit.getValue())
                    .includeBlocks(include.getValue()).excludeBlocks(exclude.getValue()).extraTextures(textures.getValue())
                    .treeGroundSnow(treeGround.getValue()).plantGroundSnow(plantGround.getValue()).build();
            try {
                FrostedFoliageClient.saveConfig(updated);
            } catch (java.io.IOException e) {
                FrostedFoliageClient.LOGGER.error("Could not save Frosted Foliage settings", e);
                Minecraft.getInstance().gui.chatListener().handleSystemMessage(Component.literal("Could not save Frosted Foliage settings: " + e.getMessage()), true);
            }
        });
        return buildScreen(builder, visual, trees, plants, simulation, blocks);
    }

    private static Screen buildScreen(ConfigBuilder builder, ConfigCategory... categories) {
        var byName = new LinkedHashMap<String, ConfigCategory>();
        for (var category : categories) byName.put(category.getCategoryKey().getString(), category);
        var screen = new ClothConfigScreen(builder.getParentScreen(), builder.getTitle(), byName, builder.getDefaultBackgroundTexture()) {
            private final ArrayList<Tooltip> pendingTooltips = new ArrayList<>();
            @Override public void addTooltip(Tooltip tooltip) { pendingTooltips.add(tooltip); }
            @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
                super.extractRenderState(graphics, mouseX, mouseY, delta);
                for (Tooltip tooltip : pendingTooltips) {
                    graphics.setTooltipForNextFrame(Minecraft.getInstance().font, tooltip.getText(), TOOLTIP_POSITION,
                            tooltip.getX(), tooltip.getY(), false);
                }
                pendingTooltips.clear();
            }
        };
        screen.setSavingRunnable(builder.getSavingRunnable());
        screen.setAfterInitConsumer(builder.getAfterInitConsumer());
        screen.setFallbackCategory(categories[0].getCategoryKey());
        screen.setEditable(builder.isEditable());
        screen.setTransparentBackground(builder.hasTransparentBackground());
        screen.setAlwaysShowTabs(builder.isAlwaysShowTabs());
        screen.setConfirmSave(builder.doesConfirmSave());
        return screen;
    }

    private static Component percent(int value) { return Component.literal(value + "%"); }

    private static Optional<Component> identifierError(String value) {
        try { SnowConfig.normalizeIdentifier(value); return Optional.empty(); }
        catch (IllegalArgumentException e) { return Optional.of(Component.literal(e.getMessage())); }
    }

    /** Wrap tips to leave room beside the pointer, even at the smallest GUI width. */
    private static Supplier<Optional<Component[]>> tooltip(String text) {
        return () -> {
            Minecraft client = Minecraft.getInstance();
            int width = Math.min(160, Math.max(80, client.getWindow().getGuiScaledWidth() / 2 - 24));
            Component[] lines = client.font.getSplitter().splitLines(text, width, Style.EMPTY).stream()
                    .map(line -> Component.literal(line.getString())).toArray(Component[]::new);
            return Optional.of(lines);
        };
    }
}
