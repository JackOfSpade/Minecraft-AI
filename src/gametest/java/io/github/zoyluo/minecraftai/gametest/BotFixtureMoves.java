package io.github.zoyluo.minecraftai.gametest;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.TeleportAudit;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Moves a bot as a GameTest fixture step (placing it where a scenario starts, putting it back between trials).
 *
 * <p>Every move made here is recorded by {@link TeleportAudit} as {@code TEST}, never as a {@code CORRECTION}, so a test can assert
 * on the corrections of the code under test ({@code TeleportAudit.corrections(bot) == 0}) without its own harness moves counting.
 * It replaces the fixture uses of {@code FakePlayerMotion.stepToStandable / stepTo / nudge}, which are production correction
 * primitives. Assertions are always per bot under test: {@code TeleportAudit.reset(bot)} before the measured phase.</p>
 */
public final class BotFixtureMoves {
    private BotFixtureMoves() {
    }

    /** Puts the bot at the bottom centre of {@code feet}, standing still (no velocity, no fall in progress). */
    public static void place(AIPlayerEntity bot, BlockPos feet) {
        place(bot, Vec3.atBottomCenterOf(feet));
    }

    /** Puts the bot at {@code pos}, standing still (no velocity, no fall in progress), keeping its facing. */
    public static void place(AIPlayerEntity bot, Vec3 pos) {
        runAsFixture(() -> {
            bot.teleportTo(bot.level(), pos.x, pos.y, pos.z, Set.of(), bot.getYRot(), bot.getXRot(), true);
            bot.setDeltaMovement(Vec3.ZERO);
            bot.fallDistance = 0.0D;
        });
    }

    /** Runs {@code action} with a {@link TeleportAudit#testScope() test scope} open: any bot teleport inside it is {@code TEST}. */
    public static void runAsFixture(Runnable action) {
        try (TeleportAudit.Scope ignored = TeleportAudit.testScope()) {
            action.run();
        }
    }
}
