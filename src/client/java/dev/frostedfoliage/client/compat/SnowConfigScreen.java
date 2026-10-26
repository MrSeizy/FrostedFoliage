package dev.frostedfoliage.client.compat;

import dev.frostedfoliage.client.FrostedFoliageClient;
import dev.frostedfoliage.config.SnowConfig;
import dev.frostedfoliage.core.SliderSteps;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.Requirement;
import me.shedaniel.clothconfig2.api.Tooltip;
import me.shedaniel.clothconfig2.gui.ClothConfigScreen;
import me.shedaniel.clothconfig2.gui.widget.SearchFieldEntry;
import me.shedaniel.clothconfig2.impl.builders.BooleanToggleBuilder;
import me.shedaniel.clothconfig2.impl.builders.IntSliderBuilder;
import me.shedaniel.clothconfig2.impl.builders.StringListBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.joml.Vector2i;

/** Optional integration. Neither Cloth Config nor Mod Menu is a required dependency. */
public final class SnowConfigScreen {
    private static final String KEY = "frostedfoliage.config.";
    private static final double[] DURATIONS = {5, 10, 15, 30, 45, 60, 90, 120, 180, 240, 300, 420, 600, 900, 1200, 1800,
            2700, 3600, 5400, 7200, 10800, 14400, 21600, 43200, 86400};
    private static final double[] REFRESH_RATES = {0.05, 0.1, 0.2, 0.25, 0.5, 1, 2};
    private static final double[] CACHE_SIZES = {64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384};
    private static final SystemToast.SystemToastId SAVE_FAILED = new SystemToast.SystemToastId();
    private static final ClientTooltipPositioner TOOLTIP_POSITION = (width, height, x, y, tooltipWidth, tooltipHeight) -> {
        int left = x + 12, top = y + 18;
        if (left + tooltipWidth > width - 4) left = Math.max(4, x - 12 - tooltipWidth);
        if (top + tooltipHeight > height - 4) top = Math.max(4, y - tooltipHeight - 18);
        return new Vector2i(left, top);
    };
    private static boolean warnedIncompatible;

    /** Some settings regenerate snow textures when saved, which briefly reloads resources. */
    private enum Note { NONE, RELOADS, MAY_RELOAD }

    private SnowConfigScreen() { }
    public static Screen create(Screen parent) {
        SnowConfig c = FrostedFoliageClient.system().config(), d = SnowConfig.defaults();
        var builder = ConfigBuilder.create().setParentScreen(parent).setTitle(Component.translatable(KEY + "title"));
        builder.setAfterInitConsumer(SnowConfigScreen::hideSearch);
        var entries = builder.entryBuilder();
        var visual = builder.getOrCreateCategory(Component.translatable(KEY + "category.appearance"));
        var trees = builder.getOrCreateCategory(Component.translatable(KEY + "category.trees"));
        var plants = builder.getOrCreateCategory(Component.translatable(KEY + "category.plants"));
        var simulation = builder.getOrCreateCategory(Component.translatable(KEY + "category.weather"));
        var blocks = builder.getOrCreateCategory(Component.translatable(KEY + "category.advanced"));

        String loadError = FrostedFoliageClient.configLoadError();
        if (loadError != null) visual.addEntry(entries.startTextDescription(Component.translatable(KEY + "load_error", loadError)).build());
        visual.addEntry(entries.startTextDescription(Component.translatable(KEY + "shared_note")).build());
        var enabled = toggle(entries, "enabled", c.enabled(), d.enabled(), Note.NONE).build();
        var whiteness = percent(entries, "whiteness", c.whiteness(), d.whiteness(), Note.NONE).build();
        var variation = percent(entries, "variation", c.variation(), d.variation(), Note.NONE).build();
        visual.addEntry(enabled); visual.addEntry(whiteness); visual.addEntry(variation);

        var maximum = percent(entries, "leaf_coverage", c.maximumCoverage(), d.maximumCoverage(), Note.NONE).build();
        var depth = percent(entries, "leaf_depth", c.snowDepth(), d.snowDepth(), Note.RELOADS).build();
        var top = toggle(entries, "tops_first", c.preferTopFaces(), d.preferTopFaces(), Note.NONE).build();
        var treeGround = toggle(entries, "tree_ground", c.treeGroundSnow(), d.treeGroundSnow(), Note.NONE).build();
        trees.addEntry(maximum); trees.addEntry(depth); trees.addEntry(top); trees.addEntry(treeGround);

        var plantsEnabled = toggle(entries, "plants_enabled", c.plantsEnabled(), d.plantsEnabled(), Note.RELOADS).build();
        var plantDepth = percent(entries, "plant_depth", c.plantSnowDepth(), d.plantSnowDepth(), Note.RELOADS)
                .setRequirement(Requirement.isTrue(plantsEnabled)).build();
        var plantMaximum = percent(entries, "plant_coverage", c.plantMaximumCoverage(), d.plantMaximumCoverage(), Note.NONE)
                .setRequirement(Requirement.isTrue(plantsEnabled)).build();
        var plantGround = toggle(entries, "plant_ground", c.plantGroundSnow(), d.plantGroundSnow(), Note.NONE).build();
        plants.addEntry(plantsEnabled); plants.addEntry(plantDepth); plants.addEntry(plantMaximum); plants.addEntry(plantGround);

        var accumulation = toggle(entries, "gradual_buildup", c.gradualAccumulation(), d.gradualAccumulation(), Note.NONE).build();
        double[] buildupSteps = SliderSteps.of(DURATIONS, c.accumulationSeconds(), d.accumulationSeconds());
        var accSeconds = stepped(entries, "buildup_time", buildupSteps, c.accumulationSeconds(), d.accumulationSeconds(),
                SnowConfigScreen::duration).setRequirement(Requirement.isTrue(accumulation)).build();
        var melting = toggle(entries, "gradual_melting", c.gradualMelting(), d.gradualMelting(), Note.NONE).build();
        double[] meltSteps = SliderSteps.of(DURATIONS, c.meltingSeconds(), d.meltingSeconds());
        var meltSeconds = stepped(entries, "melt_time", meltSteps, c.meltingSeconds(), d.meltingSeconds(),
                SnowConfigScreen::duration).setRequirement(Requirement.isTrue(melting)).build();
        double[] delaySteps = SliderSteps.of(DURATIONS, 0, c.persistenceSeconds(), d.persistenceSeconds());
        var persistence = stepped(entries, "melt_delay", delaySteps, c.persistenceSeconds(), d.persistenceSeconds(),
                SnowConfigScreen::duration).build();
        var exposure = percent(entries, "canopy_delay", c.skyExposureSensitivity(), d.skyExposureSensitivity(), Note.NONE).build();
        simulation.addEntry(accumulation); simulation.addEntry(accSeconds); simulation.addEntry(melting);
        simulation.addEntry(meltSeconds); simulation.addEntry(persistence); simulation.addEntry(exposure);

        var modded = toggle(entries, "modded_foliage", c.moddedFoliage(), d.moddedFoliage(), Note.MAY_RELOAD).build();
        var quality = entries.startEnumSelector(Component.translatable(KEY + "quality"), SnowConfig.Quality.class, c.quality())
                .setDefaultValue(d.quality()).setTooltipSupplier(tooltip("quality", Note.NONE))
                .setEnumNameProvider(q -> Component.translatable(KEY + "quality." + q.name().toLowerCase(Locale.ROOT))).build();
        double[] refreshSteps = SliderSteps.of(REFRESH_RATES, c.maxVisualUpdatesPerSecond(), d.maxVisualUpdatesPerSecond());
        var frequency = stepped(entries, "refresh", refreshSteps, c.maxVisualUpdatesPerSecond(), d.maxVisualUpdatesPerSecond(),
                rate -> Component.translatable(KEY + "refresh.every", number(1 / rate))).build();
        double[] cacheSteps = SliderSteps.of(CACHE_SIZES, c.maxCachedSections(), d.maxCachedSections());
        var limit = stepped(entries, "cache_size", cacheSteps, c.maxCachedSections(), d.maxCachedSections(),
                size -> Component.translatable(KEY + "cache_size.value", (int) size)).build();
        blocks.addEntry(modded); blocks.addEntry(quality); blocks.addEntry(frequency); blocks.addEntry(limit);
        blocks.addEntry(entries.startTextDescription(Component.translatable(KEY + "lists_note")).build());
        var include = list(entries, "include_blocks", c.includeBlocks(), d.includeBlocks(), Note.MAY_RELOAD).build();
        var exclude = list(entries, "exclude_blocks", c.excludeBlocks(), d.excludeBlocks(), Note.MAY_RELOAD).build();
        var textures = list(entries, "extra_textures", c.extraTextures(), d.extraTextures(), Note.RELOADS).build();
        blocks.addEntry(include); blocks.addEntry(exclude); blocks.addEntry(textures);

        builder.setSavingRunnable(() -> {
            var updated = c.toBuilder().enabled(enabled.getValue()).whiteness(whiteness.getValue() / 100d)
                    .variation(variation.getValue() / 100d)
                    .maximumCoverage(maximum.getValue() / 100d).snowDepth(depth.getValue() / 100d)
                    .preferTopFaces(top.getValue()).treeGroundSnow(treeGround.getValue())
                    .plantsEnabled(plantsEnabled.getValue()).plantSnowDepth(plantDepth.getValue() / 100d)
                    .plantMaximumCoverage(plantMaximum.getValue() / 100d).plantGroundSnow(plantGround.getValue())
                    .gradualAccumulation(accumulation.getValue()).accumulationSeconds(buildupSteps[accSeconds.getValue()])
                    .gradualMelting(melting.getValue()).meltingSeconds(meltSteps[meltSeconds.getValue()])
                    .persistenceSeconds(delaySteps[persistence.getValue()]).skyExposureSensitivity(exposure.getValue() / 100d)
                    .moddedFoliage(modded.getValue()).quality(quality.getValue())
                    .maxVisualUpdatesPerSecond(refreshSteps[frequency.getValue()]).maxCachedSections((int) cacheSteps[limit.getValue()])
                    .includeBlocks(include.getValue()).excludeBlocks(exclude.getValue()).extraTextures(textures.getValue()).build();
            try {
                FrostedFoliageClient.saveConfig(updated);
            } catch (IOException e) {
                FrostedFoliageClient.LOGGER.error("Could not save Frosted Foliage settings", e);
                // A toast shows on the title screen too, where chat and the action bar are not drawn.
                SystemToast.addOrUpdate(Minecraft.getInstance().gui.toastManager(), SAVE_FAILED,
                        Component.translatable(KEY + "save_failed"), Component.literal(e.getMessage()));
            }
        });
        try {
            return customScreen(builder, visual, trees, plants, simulation, blocks);
        } catch (LinkageError e) {
            incompatible(e);
            return builder.build();
        }
    }

    /**
     * Cloth's public builder cannot place tooltips beside the pointer, so this subclasses its screen. A Cloth release
     * that changes those internals throws a LinkageError here; {@link #create} then falls back to the standard screen.
     */
    private static Screen customScreen(ConfigBuilder builder, ConfigCategory... categories) {
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

    /** Runs during screen init, after create() has returned, so it needs its own guard. */
    private static void hideSearch(Screen screen) {
        try {
            if (screen instanceof ClothConfigScreen cloth) cloth.listWidget.children().removeIf(SearchFieldEntry.class::isInstance);
        } catch (LinkageError e) {
            incompatible(e);
        }
    }

    private static void incompatible(LinkageError e) {
        if (warnedIncompatible) return;
        warnedIncompatible = true;
        FrostedFoliageClient.LOGGER.warn("This Cloth Config version changed internals the settings screen customizes; using its standard layout", e);
    }

    private static BooleanToggleBuilder toggle(ConfigEntryBuilder entries, String name, boolean value, boolean defaultValue, Note note) {
        return entries.startBooleanToggle(Component.translatable(KEY + name), value).setDefaultValue(defaultValue)
                .setTooltipSupplier(tooltip(name, note));
    }

    private static IntSliderBuilder percent(ConfigEntryBuilder entries, String name, double value, double defaultValue, Note note) {
        return entries.startIntSlider(Component.translatable(KEY + name), (int) Math.round(value * 100), 0, 100)
                .setDefaultValue((int) Math.round(defaultValue * 100)).setTextGetter(p -> Component.translatable(KEY + "percent", p))
                .setTooltipSupplier(tooltip(name, note));
    }

    /** A slider over preset values; {@code steps} must contain both the current and the default value. */
    private static IntSliderBuilder stepped(ConfigEntryBuilder entries, String name, double[] steps, double value, double defaultValue,
                                            DoubleFunction<Component> label) {
        return entries.startIntSlider(Component.translatable(KEY + name), SliderSteps.indexOf(steps, value), 0, steps.length - 1)
                .setDefaultValue(SliderSteps.indexOf(steps, defaultValue)).setTextGetter(i -> label.apply(steps[i]))
                .setTooltipSupplier(tooltip(name, Note.NONE));
    }

    private static StringListBuilder list(ConfigEntryBuilder entries, String name, List<String> value, List<String> defaultValue, Note note) {
        return entries.startStrList(Component.translatable(KEY + name), value).setDefaultValue(defaultValue)
                .setCellErrorSupplier(SnowConfigScreen::identifierError).setTooltipSupplier(tooltip(name, note));
    }

    private static Component duration(double seconds) {
        long total = Math.round(seconds), hours = total / 3600, minutes = total % 3600 / 60, rest = total % 60;
        if (total < 60) return Component.translatable(KEY + "duration.seconds", total);
        if (hours == 0) return rest == 0 ? Component.translatable(KEY + "duration.minutes", minutes)
                : Component.translatable(KEY + "duration.minutes_seconds", minutes, rest);
        return minutes == 0 ? Component.translatable(KEY + "duration.hours", hours)
                : Component.translatable(KEY + "duration.hours_minutes", hours, minutes);
    }

    private static String number(double value) {
        return BigDecimal.valueOf(Math.round(value * 100) / 100.0).stripTrailingZeros().toPlainString();
    }

    private static Optional<Component> identifierError(String value) {
        try { SnowConfig.normalizeIdentifier(value); return Optional.empty(); }
        catch (IllegalArgumentException e) { return Optional.of(Component.translatable(KEY + "invalid_id")); }
    }

    /** Wrap tips to leave room beside the pointer, even at the smallest GUI width. */
    private static Supplier<Optional<Component[]>> tooltip(String name, Note note) {
        return () -> {
            Minecraft client = Minecraft.getInstance();
            int width = Math.min(160, Math.max(80, client.getWindow().getGuiScaledWidth() / 2 - 24));
            var lines = new ArrayList<Component>();
            wrap(client, lines, Component.translatable(KEY + name + ".tooltip"), width);
            if (note != Note.NONE) {
                wrap(client, lines, Component.translatable(KEY + (note == Note.RELOADS ? "reload_note" : "may_reload_note")), width);
            }
            return Optional.of(lines.toArray(Component[]::new));
        };
    }

    private static void wrap(Minecraft client, List<Component> lines, Component text, int width) {
        client.font.getSplitter().splitLines(text, width, Style.EMPTY).forEach(line -> lines.add(Component.literal(line.getString())));
    }
}
