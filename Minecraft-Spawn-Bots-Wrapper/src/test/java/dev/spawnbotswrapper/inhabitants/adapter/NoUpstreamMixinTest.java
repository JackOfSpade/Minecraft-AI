package dev.spawnbotswrapper.inhabitants.adapter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The mod boundary: "never modify the PvP BOT or HeroBot jars, and no mixins into their classes". The published addon contains
 * no mixin at all (none in {@code src/main}, no mixin config, none declared by its {@code fabric.mod.json}, none in the built
 * jar, checked only when a jar has been built before the tests run). The ONE mixin of the project is the test-harness shim {@code InventoryHelperDevShimMixin} in the
 * GameTest source set: it only translates PvP BOT's reflective hotbar-field lookup to the Mojang-named runtime field so the
 * dev-environment server can run PvP BOT at all, changes no behaviour, and is never part of the published jar. It is the only
 * mixin allowed to exist anywhere in this build, and any other mixin (above all one into an upstream class) fails here.
 */
class NoUpstreamMixinTest {
    /** The harness-only shim, the single permitted mixin (a file name in {@code src/gametest/java}). */
    private static final Set<String> HARNESS_ONLY_MIXINS = Set.of("InventoryHelperDevShimMixin.java");

    private static Path projectRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("src").resolve("main").resolve("java"))) {
                return p;
            }
        }
        fail("project root not found from " + dir);
        return null;
    }

    private static List<Path> files(Path root, String suffix) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(suffix)).toList();
        }
    }

    @Test
    void theProductionSourceSetHasNoMixinAtAll() throws IOException {
        Path main = projectRoot().resolve("src").resolve("main");
        List<String> offenders = new ArrayList<>();
        for (Path p : files(main.resolve("java"), ".java")) {
            String text = Files.readString(p);
            if (text.contains("org.spongepowered") || text.contains("@Mixin")) {
                offenders.add(main.relativize(p).toString());
            }
        }
        assertEquals(List.of(), offenders, "no mixin (and so none into PvP BOT or HeroBot) may exist in src/main");
        assertEquals(List.of(), files(main.resolve("resources"), ".mixins.json"), "no mixin config in the production resources");
        Path descriptor = main.resolve("resources").resolve("fabric.mod.json");
        assertFalse(Files.readString(descriptor).contains("\"mixins\""), "fabric.mod.json declares no mixin config");
    }

    @Test
    void theOnlyMixinInTheWholeBuildIsTheHarnessOnlyShimInTheGameTestSourceSet() throws IOException {
        Path gametest = projectRoot().resolve("src").resolve("gametest");
        List<String> mixins = new ArrayList<>();
        for (Path p : files(gametest.resolve("java"), ".java")) {
            String text = Files.readString(p);
            if (text.contains("org.spongepowered") || text.contains("@Mixin")) {
                mixins.add(p.getFileName().toString());
            }
        }
        assertEquals(HARNESS_ONLY_MIXINS, Set.copyOf(mixins),
                "a mixin was added (or the shim removed): mixins are only allowed as the documented harness-only shim");
        for (String mixin : HARNESS_ONLY_MIXINS) {
            Path file = files(gametest.resolve("java"), mixin).get(0);
            String text = Files.readString(file);
            assertTrue(text.contains("TEST HARNESS ONLY") && text.contains("never part of the published jar"),
                    mixin + " must say that it is harness-only and not published");
            assertTrue(text.contains("@Mixin(targets = \"org.stepan1411.pvp_bot.utils.InventoryHelper\")"),
                    mixin + " may only target PvP BOT's InventoryHelper (the reflective hotbar-field lookup)");
            assertEquals(1, text.split("@Mixin", -1).length - 1, mixin + " holds exactly one mixin");
        }
    }

    @Test
    void theBuiltJarContainsNoMixinWhenOneExists() throws IOException {
        Path libs = projectRoot().resolve("build").resolve("libs");
        for (Path jar : files(libs, ".jar")) {
            String name = jar.getFileName().toString();
            if (name.endsWith("-sources.jar") || name.endsWith("-dev.jar")) {
                continue;
            }
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (java.util.Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                    String entry = e.nextElement().getName();
                    assertFalse(entry.endsWith(".mixins.json") || entry.contains("/mixin/") || entry.contains("gametest"),
                            name + " must not contain " + entry);
                }
            }
        }
    }
}
