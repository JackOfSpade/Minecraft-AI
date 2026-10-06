package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.BotFixtureMoves;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

/** Collision-shape regressions for the live floor-break veto. */
public final class MiningSafetyGameTests {
    @GameTest(maxTicks = 40)
    public void straddledSlabSupportsAreProtectedForBotsAndOtherPlayers(GameTestHelper context) {
        BlockPos left = context.absolutePos(new BlockPos(5, 4, 5));
        BlockPos right = left.east();
        BlockState slab = Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        context.getLevel().setBlock(left, slab, Block.UPDATE_ALL);
        context.getLevel().setBlock(right, slab, Block.UPDATE_ALL);

        // Feet are exactly on the lower-slab top, and the X centre is exactly on the seam. Both
        // support shapes touch the player: findSupportingBlock would choose only one of them.
        AIPlayerEntity actor = spawn(context, "MiningSafetyActorGT", new Vec3(right.getX(), left.getY() + 0.5D, left.getZ() + 0.5D));
        require(context, MiningSafety.supportOccupancy(actor, left) == MiningSafety.SupportOccupancy.SELF,
                "left slab under a straddling bot was not protected");
        require(context, MiningSafety.supportOccupancy(actor, right) == MiningSafety.SupportOccupancy.SELF,
                "right slab under a straddling bot was not protected");

        AIPlayerEntity nearby = spawn(context, "MiningSafetyNearbyGT", new Vec3(right.getX(), left.getY() + 0.5D, left.getZ() + 0.5D));
        // The actor must be treated as the player whose footing takes precedence. The player
        // branch is what tells a miner to abandon/re-route rather than step aside and break.
        require(context, MiningSafety.supportOccupancy(actor, left) == MiningSafety.SupportOccupancy.PLAYER,
                "a nearby straddling player did not protect the left slab");
        require(context, MiningSafety.supportOccupancy(actor, right) == MiningSafety.SupportOccupancy.PLAYER,
                "a nearby straddling player did not protect the right slab");
        cleanup(context, actor, nearby);
    }

    private static AIPlayerEntity spawn(GameTestHelper context, String name, Vec3 position) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getLevel().getServer(), name, context.getLevel(), position,
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        BotFixtureMoves.place(bot, position);
        bot.setOnGround(true);
        return bot;
    }

    private static void cleanup(GameTestHelper context, AIPlayerEntity... bots) {
        for (AIPlayerEntity bot : bots) {
            bot.getActionPack().stopAll();
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), bot.getGameProfile().name());
        }
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
            throw new IllegalStateException(message);
        }
    }
}
