package dev.frostedfoliage;

import dev.frostedfoliage.config.ConfigDiff;
import dev.frostedfoliage.config.SnowConfig;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigDiffTest {
    /** Read every tick or per capture, so changing them needs no cache invalidation. */
    private static final Set<String> SIMULATION_ONLY = Set.of("gradualAccumulation", "gradualMelting",
            "accumulationSeconds", "meltingSeconds", "persistenceSeconds", "quality", "maxVisualUpdatesPerSecond");

    @Test void everySettingIsEitherInvalidatedOrDeclaredSimulationOnly() throws Exception {
        var defaults = SnowConfig.defaults();
        for (RecordComponent component : SnowConfig.class.getRecordComponents()) {
            var changed = with(defaults, component);
            assertNotEquals(defaults, changed, component.getName() + " perturbation was clamped away");
            var diff = new ConfigDiff(defaults, changed);
            boolean invalidates = diff.textures() || diff.exposure() || diff.appearance() || diff.groundSnow()
                    || diff.cacheSize() || diff.foliageRules();
            assertEquals(!SIMULATION_ONLY.contains(component.getName()), invalidates, component.getName());
        }
    }

    @Test void identicalConfigsInvalidateNothing() {
        var diff = new ConfigDiff(SnowConfig.defaults(), SnowConfig.defaults());
        assertFalse(diff.textures() || diff.exposure() || diff.appearance() || diff.groundSnow() || diff.cacheSize());
    }

    @Test void categoriesMatchWhatEachSystemRefreshes() {
        var d = SnowConfig.defaults();
        var plants = new ConfigDiff(d, d.toBuilder().plantsEnabled(false).build());
        assertTrue(plants.textures() && plants.foliageRules() && plants.appearance());
        assertFalse(plants.blockRules() || plants.groundSnow(), "Ground snow ignores the plant frost toggle");
        var exposure = new ConfigDiff(d, d.toBuilder().skyExposureSensitivity(0.1).build());
        assertTrue(exposure.exposure()); assertFalse(exposure.appearance() || exposure.textures());
        var modded = new ConfigDiff(d, d.toBuilder().moddedFoliage(false).build());
        assertTrue(modded.blockRules() && modded.groundSnow() && modded.exposure()); assertFalse(modded.textures());
        var whiteness = new ConfigDiff(d, d.toBuilder().whiteness(0.1).build());
        assertTrue(whiteness.appearance()); assertFalse(whiteness.textures() || whiteness.exposure());
    }

    private static SnowConfig with(SnowConfig base, RecordComponent target) throws Exception {
        RecordComponent[] components = SnowConfig.class.getRecordComponents();
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            Object value = components[i].getAccessor().invoke(base);
            values[i] = components[i].getName().equals(target.getName()) ? perturb(value) : value;
        }
        var types = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
        return SnowConfig.class.getDeclaredConstructor(types).newInstance(values);
    }

    /** Moves each value inward so the compact constructor's clamping cannot undo the change. */
    private static Object perturb(Object value) {
        return switch (value) {
            case Boolean b -> !b;
            case Double d -> d == 0 ? 0.5 : d / 2;
            case Integer i -> i / 2;
            case SnowConfig.Quality q -> SnowConfig.Quality.values()[(q.ordinal() + 1) % SnowConfig.Quality.values().length];
            case List<?> list -> List.of("test:block");
            default -> throw new AssertionError("Add a perturbation for " + value.getClass());
        };
    }
}
