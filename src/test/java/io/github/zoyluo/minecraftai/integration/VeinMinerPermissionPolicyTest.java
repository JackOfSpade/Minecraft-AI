package io.github.zoyluo.minecraftai.integration;

import net.fabricmc.fabric.api.util.TriState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two "bots are left out of vanilla and third-party player loops" decisions of this change: bots
 * never vein-mine (the permission answer, and that the optional API is only touched behind a guard) and
 * phantoms never spawn around bots (the mixin targets PhantomSpawner and reuses PlayerKind).
 */
final class VeinMinerPermissionPolicyTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void botsAreDeniedHumansAllowedAndOtherNodesAreLeftAlone() {
        assertEquals("veinminer.use", VeinMinerPermissionPolicy.NODE);
        assertEquals(TriState.FALSE, VeinMinerPermissionPolicy.decide("veinminer.use", true, true));
        assertEquals(TriState.TRUE, VeinMinerPermissionPolicy.decide("veinminer.use", true, false));
        // not a player (a command block, the console, a mob): no opinion
        assertEquals(TriState.DEFAULT, VeinMinerPermissionPolicy.decide("veinminer.use", false, false));
        assertEquals(TriState.DEFAULT, VeinMinerPermissionPolicy.decide("veinminer.use", false, true));
        // no other node is touched, whoever asks
        for (String other : new String[]{"veinminer.admin", "veinminer.use.extra", "VEINMINER.USE", "minecraft.command.gamemode", ""}) {
            assertEquals(TriState.DEFAULT, VeinMinerPermissionPolicy.decide(other, true, true), other);
            assertEquals(TriState.DEFAULT, VeinMinerPermissionPolicy.decide(other, true, false), other);
        }
    }

    @Test
    void thePermissionsApiIsOnlyReferencedFromAGuardedClass() throws IOException {
        String integration = Files.readString(MAIN.resolve("integration/PermissionsIntegration.java"));
        assertTrue(integration.contains("isModLoaded(API_MOD_ID)") && integration.contains("\"fabric-permissions-api-v0\""));
        assertTrue(integration.contains("VeinMinerPermissionPolicy.decide("));
        assertTrue(integration.contains("PlayerKind.isBot("));
        assertTrue(Files.readString(MAIN.resolve("MinecraftAiMod.java")).contains("PermissionsIntegration.registerIfPresent()"));
        // nothing else may load the API classes: the mod has to start without them
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.getFileName().toString().equals("PermissionsIntegration.java")).toList()) {
                assertFalse(Files.readString(file).contains("me.lucko"), file + " references the optional permissions API");
            }
        }
    }

    @Test
    void phantomsOnlyTargetHumans() throws IOException {
        String mixins = Files.readString(Path.of("src/main/resources/minecraftai.mixins.json"));
        assertTrue(mixins.contains("\"PhantomSpawnerHumansOnlyMixin\""));
        String mixin = Files.readString(MAIN.resolve("mixin/PhantomSpawnerHumansOnlyMixin.java"));
        assertTrue(mixin.contains("@Mixin(PhantomSpawner.class)"));
        assertTrue(mixin.contains("method = \"tick\""));
        assertTrue(mixin.contains("Lnet/minecraft/server/level/ServerLevel;players()Ljava/util/List;"));
        assertTrue(mixin.contains("PlayerKind.humansOnly(players)"));
        // the statistic is never edited: humans keep exact vanilla behaviour
        assertFalse(mixin.contains("TIME_SINCE_REST"));
    }
}
