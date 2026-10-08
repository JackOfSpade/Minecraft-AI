package io.github.zoyluo.minecraftai.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Locks in party-friendly damage immunity and projectile/melee pass-through. */
final class CompanionFriendlyFireSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void alliesAreDamageImmuneAndRangedCollisionSkipsThem() throws IOException {
        String allegiance = read("entity/CompanionAllegiance.java");
        String mod = read("MinecraftAiMod.java");
        String bot = read("entity/AIPlayerEntity.java");
        String mixins = Files.readString(Path.of("src/main/resources/minecraftai.mixins.json"));
        String projectileMixin = read("mixin/CompanionProjectileCollisionMixin.java");
        String arrowMixin = read("mixin/CompanionArrowCollisionMixin.java");
        String meleeMixin = read("mixin/CompanionMeleePassThroughMixin.java");

        assertTrue(allegiance.contains("!PlayerKind.isBot(player)"));
        assertTrue(allegiance.contains("passThroughMeleeAlly"));
        assertTrue(mod.contains("ServerLivingEntityEvents.ALLOW_DAMAGE.register"));
        assertTrue(bot.contains("CompanionAllegiance.blocksDamage(this, source)"));
        assertTrue(mixins.contains("CompanionProjectileCollisionMixin") && mixins.contains("CompanionArrowCollisionMixin")
                && mixins.contains("CompanionMeleePassThroughMixin"));
        assertTrue(projectileMixin.contains("canHitEntity") && projectileMixin.contains("areAllies(projectile.getOwner(), candidate)"));
        assertTrue(arrowMixin.contains("canHitEntity") && arrowMixin.contains("areAllies(arrow.getOwner(), candidate)"));
        assertTrue(meleeMixin.contains("passThroughMeleeAlly"));
        assertFalse(read("action/StrikeLegality.java").contains("friendly_on_line_of_fire"));
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
