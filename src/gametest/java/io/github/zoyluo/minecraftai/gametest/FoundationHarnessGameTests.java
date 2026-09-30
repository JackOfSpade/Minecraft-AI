package io.github.zoyluo.minecraftai.gametest;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.RecentDamage;
import io.github.zoyluo.minecraftai.entity.TeleportAudit;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The harness the behaviour work (pace, hostile-bot targeting, worst-first gear, wardens, no micro-teleports) is measured with:
 * survival mock players that really take damage, {@link RecentDamage} recording the hits in level game time, and
 * {@link TeleportAudit} classifying the moves of a bot.
 */
public final class FoundationHarnessGameTests {
    /** Relative height of the fixture platform (own layer: other suites use 0..45, 74 and 86). */
    private static final int LAYER_Y = 100;

    private static void fail(GameTestHelper context, String message) {
        context.fail(Component.nullToEmpty(message));
        throw new IllegalStateException(message);
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            fail(context, message);
        }
    }

    /** A 9x9 stone floor with air above; returns the feet cell at its middle. */
    private static BlockPos platform(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(4, LAYER_Y, 4));
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.setBlock(feet.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlock(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        return feet;
    }

    private static AIPlayerEntity spawn(GameTestHelper context, String name, BlockPos feet) {
        ServerLevel world = context.getLevel();
        return AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
    }

    @GameTest(maxTicks = 40)
    public void survivalMockTakesDamage(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        ServerPlayer owner = MockPlayers.survivalMock(context);
        ServerPlayer attacker = MockPlayers.survivalMock(context);
        require(context, owner.getUUID() != attacker.getUUID() && !owner.getUUID().equals(attacker.getUUID()),
                "the two mock players share a UUID");
        float before = owner.getHealth();
        long now = world.getGameTime();

        boolean applied = owner.hurtServer(world, world.damageSources().playerAttack(attacker), 2.0F);
        float lost = before - owner.getHealth();
        require(context, applied && lost > 0.0F,
                "a survival mock did not take damage: applied=" + applied + " lost=" + lost
                        + " creative=" + owner.isCreative() + " loaded=" + owner.connection.hasClientLoaded()
                        + " invulnerable=" + owner.getAbilities().invulnerable);

        Optional<RecentDamage.Hit> hit = RecentDamage.lastHitBy(owner.getUUID(), attacker.getUUID(), now, 5);
        require(context, hit.isPresent(), "RecentDamage did not record the hit of the attacking mock");
        RecentDamage.Hit recorded = hit.get();
        require(context, recorded.gameTime() == now, "the hit was stamped " + recorded.gameTime() + " instead of the level game time " + now);
        require(context, Math.abs(recorded.amount() - lost) < 1.0E-3F,
                "the recorded amount " + recorded.amount() + " differs from the health lost " + lost);
        require(context, recorded.attackerId() == attacker.getId() && recorded.byEntity(),
                "the hit does not name the attacker: " + recorded);
        require(context, RecentDamage.tookEntityDamage(owner.getUUID(), now, 5)
                        && RecentDamage.maxSingleHit(owner.getUUID(), now, 5) >= lost - 1.0E-3F
                        && RecentDamage.recentHits(owner.getUUID(), now, 5).size() == 1,
                "the window queries do not report the hit");
        require(context, RecentDamage.lastHitBy(owner.getUUID(), attacker.getUUID(), now + 200, 100).isEmpty()
                        && !RecentDamage.tookEntityDamage(owner.getUUID(), now + 200, 100),
                "a hit 200 ticks old is inside a 100 tick window");
        require(context, RecentDamage.lastHitBy(attacker.getUUID(), owner.getUUID(), now, 5).isEmpty(),
                "the attacker is recorded as a victim of the owner");
        context.succeed();
    }

    @GameTest(maxTicks = 60)
    public void fixturePlaceCountsAsTest(GameTestHelper context) {
        BlockPos feet = platform(context);
        AIPlayerEntity bot = spawn(context, "FoundationPlaceGT", feet);
        TeleportAudit.reset(bot);

        BotFixtureMoves.place(bot, feet.east(2));
        require(context, TeleportAudit.count(bot, TeleportAudit.Kind.TEST) == 1,
                "BotFixtureMoves.place did not count as exactly one TEST move: " + TeleportAudit.count(bot, TeleportAudit.Kind.TEST));
        require(context, TeleportAudit.corrections(bot) == 0,
                "a fixture move counted as a correction, caller " + TeleportAudit.lastCaller(bot));
        require(context, TeleportAudit.lastCaller(bot).contains("BotFixtureMoves#"),
                "the caller of the fixture move is " + TeleportAudit.lastCaller(bot));

        // A move a GameTest makes itself (its class name ends in GameTests) is a test move as well.
        bot.teleportTo(context.getLevel(), feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                java.util.Set.of(), 0.0F, 0.0F, true);
        require(context, TeleportAudit.count(bot, TeleportAudit.Kind.TEST) == 2 && TeleportAudit.corrections(bot) == 0,
                "a teleport from a GameTests class is not a TEST move");

        TeleportAudit.reset(bot);
        require(context, TeleportAudit.count(bot, TeleportAudit.Kind.TEST) == 0 && "-".equals(TeleportAudit.lastCaller(bot)),
                "reset did not clear the counters");
        AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), bot.getGameProfile().name());
        context.succeed();
    }

    @GameTest(maxTicks = 60)
    public void spawnTeleportIsLifecycle(GameTestHelper context) {
        BlockPos feet = platform(context);
        AIPlayerEntity bot = spawn(context, "FoundationSpawnGT", feet);
        int lifecycle = TeleportAudit.count(bot, TeleportAudit.Kind.LIFECYCLE);
        int corrections = TeleportAudit.corrections(bot);
        int test = TeleportAudit.count(bot, TeleportAudit.Kind.TEST);
        String caller = TeleportAudit.lastCaller(bot);
        AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), bot.getGameProfile().name());
        TeleportAudit.reset(bot);
        require(context, lifecycle >= 1, "the spawn teleport was not classified LIFECYCLE (caller " + caller + ")");
        require(context, corrections == 0 && test == 0,
                "the spawn teleport was counted as a correction or a test move: corrections=" + corrections + " test=" + test);
        require(context, caller.contains("AIPlayerManager#"), "the spawn teleport's caller is " + caller);
        context.succeed();
    }
}
