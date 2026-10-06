package io.github.zoyluo.minecraftai.brain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The items vanilla's crafting recipes produce, read from the recipe data in the game jar. A running server's
 * runtime recipe index is built from these (minus the storage-compaction pairs it prunes on purpose), so this is
 * what makes "which words the index calls crafted" testable without a server.
 */
final class VanillaRecipeOutputs {
    private static Set<Item> outputs;

    private VanillaRecipeOutputs() {
    }

    /** Every crafting-table or inventory recipe result, except the reciprocal storage pairs the index prunes. */
    static synchronized Set<Item> indexed() {
        if (outputs == null) {
            outputs = load();
        }
        return outputs;
    }

    private static Set<Item> load() {
        Set<String> results = new HashSet<>();
        // output id -> the single plain item some recipe of it is made from (null entry: no such recipe)
        Map<String, Set<String>> singleSources = new HashMap<>();
        try {
            for (Path file : recipeFiles()) {
                JsonObject recipe = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                if (!recipe.has("type") || !recipe.get("type").getAsString().startsWith("minecraft:crafting_")) {
                    continue;
                }
                String result = resultId(recipe.get("result"));
                if (result == null) {
                    continue; // special recipes (dyeing, firework ...) have no fixed result
                }
                results.add(result);
                String single = singleIngredient(recipe);
                if (single != null) {
                    singleSources.computeIfAbsent(result, key -> new HashSet<>()).add(single);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        Set<String> pruned = new HashSet<>();
        singleSources.forEach((output, sources) -> {
            for (String material : sources) {
                if (singleSources.getOrDefault(material, Set.of()).contains(output)) {
                    pruned.add(output);
                    pruned.add(material);
                }
            }
        });
        Set<Item> items = new HashSet<>();
        for (String id : results) {
            if (pruned.contains(id)) {
                continue;
            }
            Identifier identifier = Identifier.tryParse(id);
            if (identifier != null) {
                BuiltInRegistries.ITEM.getOptional(identifier).filter(item -> item != Items.AIR).ifPresent(items::add);
            }
        }
        return Set.copyOf(items);
    }

    private static String resultId(JsonElement result) {
        if (result == null) {
            return null;
        }
        if (result.isJsonPrimitive()) {
            return result.getAsString();
        }
        JsonObject object = result.getAsJsonObject();
        return object.has("id") ? object.get("id").getAsString() : null;
    }

    /** The one plain item a recipe is made from ("9 ingots make a block"), or null when it takes several kinds. */
    private static String singleIngredient(JsonObject recipe) {
        List<JsonElement> slots = new ArrayList<>();
        if (recipe.has("ingredients")) {
            recipe.getAsJsonArray("ingredients").forEach(slots::add);
        } else if (recipe.has("key")) {
            recipe.getAsJsonObject("key").entrySet().forEach(entry -> slots.add(entry.getValue()));
        }
        Set<String> distinct = new HashSet<>();
        for (JsonElement slot : slots) {
            if (!slot.isJsonPrimitive() || slot.getAsString().startsWith("#")) {
                return null;
            }
            distinct.add(slot.getAsString());
        }
        return distinct.size() == 1 ? distinct.iterator().next() : null;
    }

    private static List<Path> recipeFiles() throws IOException {
        URL url = VanillaRecipeOutputs.class.getClassLoader().getResource("data/minecraft/recipe");
        if (url == null) {
            throw new IllegalStateException("the vanilla recipe data is not on the test classpath");
        }
        try {
            URI uri = url.toURI();
            Path directory;
            if ("jar".equals(uri.getScheme())) {
                FileSystem system;
                try {
                    system = FileSystems.newFileSystem(uri, Map.of());
                } catch (FileSystemAlreadyExistsException already) {
                    system = FileSystems.getFileSystem(uri);
                }
                directory = system.getPath("/data/minecraft/recipe");
            } else {
                directory = Path.of(uri);
            }
            try (Stream<Path> files = Files.list(directory)) {
                return files.filter(path -> path.toString().endsWith(".json")).toList();
            }
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
