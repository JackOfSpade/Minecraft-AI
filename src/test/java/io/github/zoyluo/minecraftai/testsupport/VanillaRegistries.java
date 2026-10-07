package io.github.zoyluo.minecraftai.testsupport;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

/**
 * The game's registries in a plain JVM, as a unit test can have them. {@code Bootstrap.bootStrap()} registers every block and
 * fluid but leaves every tag unbound ({@code state.is(BlockTags.LEAVES)} is false for oak leaves), so the vanilla block and
 * fluid tags are bound here from the {@code data/minecraft/tags} files inside the game jar itself: the same files the server
 * loads, with {@code #tag} references resolved.
 */
public final class VanillaRegistries {
    private static Map<String, JsonObject> files;

    private VanillaRegistries() {
    }

    /** Bootstraps the registries and binds the vanilla block and fluid tags once per JVM. */
    public static synchronized void ensureReady() {
        if (files != null) {
            return;
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try {
            files = tagFiles();
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("cannot read the vanilla tags from the game jar", e);
        }
        bind((MappedRegistry<Block>) BuiltInRegistries.BLOCK, Registries.BLOCK, "block", Set.of());
        bind((MappedRegistry<Fluid>) BuiltInRegistries.FLUID, Registries.FLUID, "fluid", Set.of());
    }

    /**
     * Replaces every block tag as a data pack reload would: all vanilla ones again, except that the named ones (file names under
     * {@code tags/block}, such as {@code leaves}) hold no block. Call with no name to restore vanilla.
     */
    public static synchronized void reloadBlockTags(String... emptied) {
        ensureReady();
        bind((MappedRegistry<Block>) BuiltInRegistries.BLOCK, Registries.BLOCK, "block", Set.of(emptied));
    }

    private static <T> void bind(MappedRegistry<T> registry, ResourceKey<? extends Registry<T>> key, String directory,
                                 Set<String> emptied) {
        String prefix = "data/minecraft/tags/" + directory + "/";
        Map<TagKey<T>, List<Holder<T>>> tags = new HashMap<>();
        for (String file : files.keySet()) {
            if (file.startsWith(prefix)) {
                String name = file.substring(prefix.length());
                tags.put(TagKey.create(key, Identifier.withDefaultNamespace(name)),
                        emptied.contains(name) ? List.of() : resolve(registry, files, prefix, name, new HashSet<>()));
            }
        }
        registry.prepareTagReload(new TagLoader.LoadResult<>(key, tags)).apply();
    }

    private static <T> List<Holder<T>> resolve(MappedRegistry<T> registry, Map<String, JsonObject> files, String prefix,
                                               String name, Set<String> visiting) {
        if (!visiting.add(name)) {
            throw new IllegalStateException("tag cycle at " + name);
        }
        JsonObject tag = files.get(prefix + name);
        if (tag == null) {
            throw new IllegalStateException("tag file " + prefix + name + " is missing from the game jar");
        }
        Set<Holder<T>> out = new LinkedHashSet<>();
        for (JsonElement entry : tag.getAsJsonArray("values")) {
            boolean required = true;
            String id;
            if (entry.isJsonObject()) {
                id = entry.getAsJsonObject().get("id").getAsString();
                required = !entry.getAsJsonObject().has("required") || entry.getAsJsonObject().get("required").getAsBoolean();
            } else {
                id = entry.getAsString();
            }
            if (id.startsWith("#")) {
                out.addAll(resolve(registry, files, prefix, Identifier.parse(id.substring(1)).getPath(), visiting));
            } else {
                Optional<Holder.Reference<T>> holder = registry.get(Identifier.parse(id));
                if (holder.isPresent()) {
                    out.add(holder.get());
                } else if (required) {
                    throw new IllegalStateException("tag " + name + " lists the unknown id " + id);
                }
            }
        }
        visiting.remove(name);
        return new ArrayList<>(out);
    }

    /** Every {@code data/minecraft/tags/**.json} of the jar that holds the game's classes. */
    private static Map<String, JsonObject> tagFiles() throws IOException, URISyntaxException {
        Path jarPath = Path.of(Block.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Map<String, JsonObject> files = new HashMap<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String path = entry.getName();
                if (path.startsWith("data/minecraft/tags/") && path.endsWith(".json")) {
                    try (Reader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
                        files.put(path.substring(0, path.length() - ".json".length()),
                                JsonParser.parseReader(reader).getAsJsonObject());
                    }
                }
            }
        }
        return files;
    }
}
