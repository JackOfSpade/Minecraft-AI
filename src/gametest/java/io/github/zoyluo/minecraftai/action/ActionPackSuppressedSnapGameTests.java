package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Field;
import java.util.Set;

/**
 * Real-server proof of the snap guard ({@code SnapRepeatGuard}): a second physical snap out of the same
 * cell inside the guard window is refused, and the refusal must NOT fall through to the privileged
 * relocation in {@code ActionPack.snapPlayerToNearestStandable}, which would turn the yo-yo guard into a
 * teleport (a bot walked back into a cell over water would be teleported out of it, across the water).
 *
 * <p>The default GameTest profile (strict_survival) denies the emergency teleport, which would make
 * "no teleport happened" trivially true. So the test runs under the operator profile with every
 * capability enabled, proves the teleport really is available (a control snap from a cell with no
 * adjacent footing does relocate the bot), and only then checks the guarded second snap leaves the
 * bot exactly where it stood. The source-order contract in {@code FollowSwimSourceContractTest} pins the
 * same invariant textually; this pins the behaviour.
 */
public final class ActionPackSuppressedSnapGameTests {
    @GameTest(maxTicks = 20)
    public void aSuppressedStartSnapNeverTeleportsTheBot(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos anchor = context.absolutePos(new BlockPos(2, 6, 4));
        // A stone strip x in [0, 3] at floor level; the cell east of it (x = 4) has no floor at all.
        for (int dx = 0; dx <= 3; dx++) {
            BlockPos feet = anchor.offset(dx, 0, 0);
            world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        BlockPos hanging = anchor.offset(4, 0, 0);
        BlockPos farOut = anchor.offset(9, 0, 0);
        for (BlockPos cell : new BlockPos[]{hanging, farOut}) {
            world.setBlock(cell.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        Standability.clearCache();

        String name = "SuppressedSnapGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(anchor),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        MinecraftAiConfig original = MinecraftAiConfig.get();
        try {
            setConfig(withProfile(original, OperatingProfile.OPERATOR));
            require(context, CapabilityRuntime.decide(bot, PrivilegedCapability.EMERGENCY_TELEPORT,
                            "suppressed_snap_gametest").allowed(),
                    "fixture: the emergency teleport must be available so a fall-through would be visible");

            // 1) Over a cell with no footing, one step from the strip: the first snap is a physical step.
            place(world, bot, hanging);
            require(context, !Standability.isStandable(world, hanging), "fixture: the hanging cell is standable");
            require(context, bot.getActionPack().snapPlayerToNearestStandable("gametest_first_snap"),
                    "the first snap out of a footing-less cell should step onto the strip");
            require(context, bot.blockPosition().equals(anchor.offset(3, 0, 0)),
                    "the first snap did not step west onto the strip: " + bot.blockPosition());

            // 2) Whatever walked the bot back into that cell is not undone again inside the window: the
            //    snap is refused, and the refusal must not become a teleport onto the strip.
            place(world, bot, hanging);
            Vec3 before = bot.position();
            boolean second = bot.getActionPack().snapPlayerToNearestStandable("gametest_second_snap");
            require(context, !second, "a repeated snap out of the same cell inside the window must be refused");
            require(context, bot.position().distanceToSqr(before) < 1.0E-12D,
                    "the refused snap moved the bot from " + before + " to " + bot.position()
                            + " (a suppressed snap fell through to the emergency teleport)");

            // 3) Control: a cell with no adjacent footing (so no physical step exists) and no guard entry
            //    IS relocated by the very same call under this profile -- the teleport was reachable.
            place(world, bot, farOut);
            require(context, bot.getActionPack().snapPlayerToNearestStandable("gametest_control_teleport"),
                    "control: the emergency relocation did not run although the profile allows it");
            require(context, Standability.isStandable(world, bot.blockPosition())
                            && !bot.blockPosition().equals(farOut),
                    "control: the bot was not relocated onto footing: " + bot.blockPosition());
        } finally {
            setConfig(original);
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
        }
        context.succeed();
    }

    private static void place(ServerLevel world, AIPlayerEntity bot, BlockPos feet) {
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.fallDistance = 0.0F;
        Standability.clearCache();
    }

    private static MinecraftAiConfig withProfile(MinecraftAiConfig config, OperatingProfile profile) {
        return new MinecraftAiConfig(
                profile,
                config.operatorCapabilities(),
                config.llm(),
                config.perception(),
                config.brain(),
                config.watchdog(),
                config.logging(),
                config.survival(),
                config.combat(),
                config.night(),
                config.mining(),
                config.goal(),
                config.nav(),
                config.pickup(),
                config.conversation());
    }

    private static void setConfig(MinecraftAiConfig config) {
        try {
            Field instance = MinecraftAiConfig.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, config);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to install GameTest config", exception);
        }
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
