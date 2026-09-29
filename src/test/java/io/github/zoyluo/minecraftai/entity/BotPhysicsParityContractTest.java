package io.github.zoyluo.minecraftai.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Source-contract pins for the two vanilla behaviours a bot has to get from the server because it has no client (see
 * docs/BOT_PHYSICS_PARITY.md): fall damage (checked once per tick, never twice with the Baritone driver) and the knockback of a player hit.
 */
class BotPhysicsParityContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    @Test
    void everyBotTickChecksFallsOnceAfterThePhysicsTick() throws IOException {
        String source = read("entity/AIPlayerEntity.java");
        int tick = source.indexOf("public void tick()");
        int reset = source.indexOf("this.fallChecked = false;", tick);
        int from = source.indexOf("this.tickFromY = this.getY();", reset);
        int physics = source.indexOf("super.tick()", from);
        int doTick = source.indexOf("this.doTick()", physics);
        String after = source.substring(doTick, source.indexOf("logDamageSummary(damageLog.flushIfIdle", doTick));
        assertTrue(tick >= 0 && reset > tick && from > reset && physics > from && doTick > physics,
                "the tick starts by forgetting the last check and noting where it began, before any physics");
        int driven = after.indexOf("if (baritoneDrives)");
        int legacyBranch = after.indexOf("} else {", driven);
        assertTrue(after.indexOf("checkFallDamageOnce()", driven) > driven && after.indexOf("checkFallDamageOnce()", legacyBranch) > legacyBranch,
                "a driven tick and a legacy tick both end with the once-only fall check");
        assertTrue(after.indexOf("checkFallDamageOnce()", legacyBranch) < after.indexOf("this.actionPack.onUpdate()", legacyBranch),
                "the legacy update (which may snap or teleport the bot) must not be part of the measured movement");
        String check = source.substring(source.indexOf("private void checkFallDamageOnce()"));
        check = check.substring(0, check.indexOf("\n    }\n"));
        assertTrue(check.contains("if (this.fallChecked)") && check.contains("this.fallChecked = true;")
                        && check.contains("isPassenger()") && check.contains("this.doCheckFallDamage(") && check.contains("this.onGround()"),
                "one check per tick, not for a passenger or a dead bot, with the tick's movement and the ground flag");
    }

    @Test
    void theBaritoneDriverMarksItsOwnFallCheckSoNothingIsChargedTwice() throws IOException {
        String driver = read("baritone/BaritoneDriver.java");
        int check = driver.indexOf("bot.doCheckFallDamage(");
        int mark = driver.indexOf("bot.markFallChecked();", check);
        assertTrue(check > 0 && mark > check && mark - check < 400, "the driver marks the tick right after its own fall check");
    }

    @Test
    void playerMeleeKnockbackOfABotIsNotThrownAwayAndTheMixinIsRegistered() throws IOException {
        String mixin = read("mixin/BotMeleeKnockbackMixin.java");
        assertTrue(mixin.contains("@Mixin(Player.class)") && mixin.contains("method = \"causeExtraKnockback\"")
                        && mixin.contains("Entity;hurtMarked:Z") && mixin.contains("!(target instanceof AIPlayerEntity) && original.call(target)"),
                "only an AIPlayerEntity target reads as not marked; anyone else gets the original answer");
        String config = Files.readString(Path.of("src/main/resources/minecraftai.mixins.json"));
        assertTrue(config.contains("\"BotMeleeKnockbackMixin\""), "the mixin must be listed in minecraftai.mixins.json");
        assertFalse(mixin.contains("@Redirect"), "a non-exclusive wrap, so other mods can inject into the same read");
    }
}
