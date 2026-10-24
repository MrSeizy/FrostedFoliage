package dev.frostedfoliage.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.frostedfoliage.client.world.PlantTypes;
import dev.frostedfoliage.core.PlantSnow;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;

/** Discover used foliage textures from active blockstates/models, including inherited aliases. */
final class FoliageTextures {
    record Use(Identifier block, int part) { }
    record Bounds(double bottom, double top) { }
    private final ResourceManager resources;
    private final boolean plants;
    private final Map<Identifier, Map<String, String>> modelCache = new HashMap<>();
    private final Map<Identifier, Set<Use>> uses = new LinkedHashMap<>();

    private FoliageTextures(ResourceManager resources, boolean plants) { this.resources = resources; this.plants = plants; }

    static Set<Identifier> leaves(ResourceManager resources, Set<Block> extraBlocks) {
        var discovery = new FoliageTextures(resources, false);
        BuiltInRegistries.BLOCK.stream().filter(block -> block instanceof LeavesBlock
                || extraBlocks.contains(block)).forEach(discovery::block);
        return Set.copyOf(discovery.uses.keySet());
    }

    static Map<Identifier, Set<Use>> plants(ResourceManager resources) {
        var discovery = new FoliageTextures(resources, true);
        BuiltInRegistries.BLOCK.stream().filter(PlantTypes::supported).forEach(discovery::block);
        var result = new LinkedHashMap<Identifier, Set<Use>>();
        discovery.uses.forEach((id, use) -> result.put(id, Set.copyOf(use)));
        return Map.copyOf(result);
    }

    private void block(Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        Identifier path = id.withPath("blockstates/" + id.getPath() + ".json");
        JsonObject definition = read(path);
        if (definition == null) return;
        JsonObject variants = object(definition.get("variants"), path, "variants");
        if (variants != null) variants.entrySet().forEach(entry -> {
            int part = entry.getKey().contains("half=upper") ? PlantTypes.UPPER
                    : entry.getKey().contains("half=lower") ? PlantTypes.LOWER : PlantTypes.SINGLE;
            models(entry.getValue(), id, 1 << part);
        });
        JsonArray multipart = array(definition.get("multipart"), path, "multipart");
        if (multipart != null) multipart.forEach(entry -> {
            JsonObject object = object(entry, path, "multipart entry");
            if (object == null) return;
            int parts = parts(object.get("when"));
            models(object.get("apply"), id, parts == 0 ? 1 << PlantTypes.SINGLE : parts);
        });
    }

    private static int parts(JsonElement element) {
        if (element == null || element.isJsonNull()) return 0;
        int result = 0;
        if (element.isJsonArray()) for (JsonElement child : element.getAsJsonArray()) result |= parts(child);
        if (element.isJsonObject()) for (var entry : element.getAsJsonObject().entrySet()) {
            if (entry.getKey().equals("half") && entry.getValue().isJsonPrimitive()) {
                for (String value : entry.getValue().getAsString().split("\\|")) {
                    if (value.equals("upper")) result |= 1 << PlantTypes.UPPER;
                    if (value.equals("lower")) result |= 1 << PlantTypes.LOWER;
                }
            } else result |= parts(entry.getValue());
        }
        return result;
    }

    private void models(JsonElement element, Identifier block, int parts) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> models(child, block, parts));
        } else if (element.isJsonObject()) {
            JsonElement name = element.getAsJsonObject().get("model");
            if (name == null || !name.isJsonPrimitive()) return;
            Identifier model = Identifier.tryParse(name.getAsString());
            if (model == null) return;
            Map<String, String> textures = textures(model, new HashSet<>());
            textures.forEach((key, value) -> {
                if (key.equals("particle") || key.contains("emissive")) return;
                for (int n = 0; value.startsWith("#") && n < 32; n++) value = textures.getOrDefault(value.substring(1), "");
                Identifier texture = Identifier.tryParse(value);
                if (texture == null || texture.getPath().endsWith("_emissive")
                        || (plants && texture.toString().equals("minecraft:block/azalea_plant"))) return;
                Set<Use> use = uses.computeIfAbsent(texture, unused -> new HashSet<>());
                for (int part = PlantTypes.SINGLE; part <= PlantTypes.UPPER; part++) {
                    if ((parts & 1 << part) != 0) use.add(new Use(block, part));
                }
            });
        }
    }

    private Map<String, String> textures(Identifier model, Set<Identifier> visited) {
        Map<String, String> cached = modelCache.get(model);
        if (cached != null) return cached;
        if (visited.size() >= 32 || !visited.add(model) || modelCache.size() >= 2048) return Map.of();
        Identifier path = model.withPath("models/" + model.getPath() + ".json");
        JsonObject definition = read(path);
        var result = new HashMap<String, String>();
        if (definition != null) {
            JsonElement parent = definition.get("parent");
            if (parent != null && parent.isJsonPrimitive()) {
                Identifier id = Identifier.tryParse(parent.getAsString());
                if (id != null) result.putAll(textures(id, visited));
            }
            JsonObject own = object(definition.get("textures"), path, "textures");
            if (own != null) own.entrySet().forEach(entry -> {
                JsonElement value = entry.getValue();
                // 26.3 materials may use {"sprite": "namespace:path", "force_translucent": true}.
                if (value.isJsonObject()) value = value.getAsJsonObject().get("sprite");
                if (value != null && value.isJsonPrimitive()) result.put(entry.getKey(), value.getAsString());
            });
        }
        visited.remove(model);
        Map<String, String> immutable = Map.copyOf(result);
        modelCache.put(model, immutable);
        return immutable;
    }

    private static JsonObject object(JsonElement value, Identifier path, String field) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonObject()) return value.getAsJsonObject();
        malformed(path, field, "object");
        return null;
    }

    private static JsonArray array(JsonElement value, Identifier path, String field) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonArray()) return value.getAsJsonArray();
        malformed(path, field, "array");
        return null;
    }

    private static void malformed(Identifier path, String field, String expected) {
        dev.frostedfoliage.client.FrostedFoliageClient.LOGGER.warn(
                "Skipping invalid foliage resource field {} in {}: expected {}", field, path, expected);
    }

    private JsonObject read(Identifier path) {
        var resource = resources.getResource(path);
        if (resource.isEmpty()) return null;
        try (var reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException | RuntimeException e) {
            dev.frostedfoliage.client.FrostedFoliageClient.LOGGER.warn("Cannot inspect foliage model {}: {}", path, e.toString());
            return null;
        }
    }

    static Map<Identifier, Map<Integer, PlantSnow.Span>> spans(Map<Identifier, Set<Use>> uses, Map<Identifier, Bounds> bounds) {
        var tall = new HashMap<Identifier, Bounds>();
        uses.forEach((texture, use) -> {
            Bounds b = bounds.get(texture);
            if (b == null) return;
            for (Use u : use) if (u.part() != PlantTypes.SINGLE) {
                int offset = u.part() == PlantTypes.UPPER ? 1 : 0;
                Bounds part = new Bounds(b.bottom() + offset, b.top() + offset);
                tall.merge(u.block(), part, (a, c) -> new Bounds(Math.min(a.bottom(), c.bottom()), Math.max(a.top(), c.top())));
            }
        });
        var result = new HashMap<Identifier, Map<Integer, PlantSnow.Span>>();
        uses.forEach((texture, use) -> {
            Bounds b = bounds.get(texture);
            if (b == null) return;
            var shapes = new HashMap<Integer, PlantSnow.Span>();
            for (Use u : use) {
                Bounds shape = u.part() == PlantTypes.SINGLE ? b : tall.get(u.block());
                if (shape == null || shape.top() <= shape.bottom()) continue;
                var span = new PlantSnow.Span(shape.bottom(), shape.top(), u.part() == PlantTypes.UPPER ? 1 : 0);
                shapes.merge(u.part(), span, (a, c) -> new PlantSnow.Span(Math.min(a.bottom(), c.bottom()),
                        Math.max(a.top(), c.top()), a.blockOffset()));
            }
            if (!shapes.isEmpty()) result.put(texture, Map.copyOf(shapes));
        });
        return Map.copyOf(result);
    }
}
