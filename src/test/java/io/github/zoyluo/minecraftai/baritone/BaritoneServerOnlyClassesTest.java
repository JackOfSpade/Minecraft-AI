package io.github.zoyluo.minecraftai.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import baritone.api.BaritoneAPI;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The compiled Baritone that ships in the mod jar must not reference the Minecraft client at all (the game and the
 * dedicated server both load it). The build already guarantees this because the {@code baritone} source set compiles
 * against the common Minecraft jar only; this test reads the class files themselves, so it also catches a classpath
 * change that would silently let client classes in.
 */
class BaritoneServerOnlyClassesTest {
    private static final List<String> FORBIDDEN = List.of("net/minecraft/client/", "com/mojang/blaze3d/", "org/lwjgl/");

    @Test
    void noBaritoneClassMentionsTheMinecraftClientOrTheRenderer() throws Exception {
        List<String> offenders = new ArrayList<>();
        int scanned = scanBaritoneClasses((name, bytes) -> {
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            for (String forbidden : FORBIDDEN) {
                if (text.contains(forbidden)) {
                    offenders.add(name + " mentions " + forbidden);
                }
            }
        });
        assertTrue(scanned > 300, "expected the whole Baritone (300+ classes), scanned " + scanned);
        assertEquals(List.of(), offenders);
    }

    @Test
    void excludedPartsAreNotCompiled() throws Exception {
        List<String> names = new ArrayList<>();
        scanBaritoneClasses((name, bytes) -> names.add(name));
        assertTrue(names.contains("baritone/pathing/movement/movements/MovementPillar.class"), "the movement classes must be there");
        assertTrue(names.contains("baritone/process/elytra/NullElytraProcess.class"), "the elytra stub must be there");
        assertTrue(names.stream().noneMatch(n -> n.startsWith("baritone/command/")), "chat commands are excluded");
        assertTrue(names.stream().noneMatch(n -> n.equals("baritone/process/ElytraProcess.class")), "elytra flying is excluded");
        assertTrue(names.stream().noneMatch(n -> n.startsWith("baritone/launch/")), "client mixins are excluded");
        assertTrue(names.stream().noneMatch(n -> n.equals("baritone/utils/player/BaritonePlayerContext.class")),
                "the client player context is replaced by ServerPlayerContext");
    }

    private interface ClassVisitor {
        void visit(String name, byte[] bytes) throws IOException;
    }

    /** Visits every class under the {@code baritone/} package of whatever directory or jar holds Baritone. */
    private static int scanBaritoneClasses(ClassVisitor visitor) throws IOException, URISyntaxException {
        Path location = Path.of(BaritoneAPI.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        int count = 0;
        if (Files.isDirectory(location)) {
            try (Stream<Path> files = Files.walk(location)) {
                for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                    visitor.visit(location.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
                    count++;
                }
            }
        } else {
            try (FileSystem jar = FileSystems.newFileSystem(location);
                 Stream<Path> files = Files.walk(jar.getPath("/baritone"))) {
                for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                    visitor.visit(file.toString().substring(1), Files.readAllBytes(file));
                    count++;
                }
            }
        }
        return count;
    }
}
