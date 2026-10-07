package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * {@link MiningSafety#openingRefusal(AIPlayerEntity, BlockPos)} against a real world: fluids are judged by tag, which
 * the plain-JVM unit test does not load. A digger in an open room is told what it may open from what it sees, and a
 * fluid hidden behind rock changes nothing, because nothing hidden is read.
 */
public final class MiningSafetyOpeningRefusalGameTests {
    @GameTest(maxTicks = 80)
    public void fluidsAndFallingBlocksAreRefusedFromWhatTheBotSeesAndNothingElse(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(8, 8, 8));
        // A stone box, nine blocks of rock to a side and two thick, with a 7x5x7 room in it.
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -2; dy <= 7; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    boolean room = Math.abs(dx) <= 3 && Math.abs(dz) <= 3 && dy >= 0 && dy <= 4;
                    world.setBlock(feet.offset(dx, dy, dz),
                            room ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), "OpeningRefusalGT", world, Vec3.atBottomCenterOf(feet),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn OpeningRefusalGT"));
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);

        // A bot that has just joined sees nothing until its chunk view is set up a tick or two later.
        context.runAfterDelay(10, () -> {
            BlockPos diggable = feet.offset(4, 1, 0);
            BlockPos structure = feet.offset(4, 1, 2);
            world.setBlock(structure, Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_ALL);
            BlockPos gravel = feet.offset(4, 1, -2);
            world.setBlock(gravel, Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);

            BlockPos lavaInRoom = feet.offset(3, 2, 2);
            world.setBlock(lavaInRoom, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
            BlockPos waterOver = feet.offset(-2, 4, -2);
            world.setBlock(waterOver, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            BlockPos underWater = waterOver.below();
            BlockPos lavaBeside = feet.offset(2, 0, -3);
            world.setBlock(lavaBeside, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
            BlockPos besideLava = lavaBeside.south();
            // Lava two cells deep in the rock, behind the cell that is to be dug: the digger cannot see it.
            BlockPos hiddenRock = feet.offset(-4, 1, 0);
            world.setBlock(hiddenRock.west(), Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);

            for (BlockPos seen : new BlockPos[] {diggable, structure, gravel, lavaInRoom, underWater, besideLava, hiddenRock}) {
                require(context, ObservableWorldQuery.canObserveCell(bot, seen) || ObservableWorldQuery.canObserveBlock(bot, seen),
                        "fixture: " + seen.toShortString() + " must be in the bot's view");
            }
            require(context, !ObservableWorldQuery.canObserveBlock(bot, hiddenRock.west()),
                    "fixture: the lava behind the rock must be out of the bot's view");

            expect(context, null, MiningSafety.openingRefusal(bot, diggable), "plain stone with nothing seen around it");
            String denial = MiningSafety.openingRefusal(bot, structure);
            require(context, denial != null && denial.startsWith("break_refused:"),
                    "stone bricks are not natural terrain: " + denial);
            expect(context, MiningSafety.GRAVITY, MiningSafety.openingRefusal(bot, gravel), "gravel would fall into the opening");
            expect(context, "lava", MiningSafety.openingRefusal(bot, lavaInRoom), "lava in the cell");
            expect(context, "water", MiningSafety.openingRefusal(bot, underWater), "water over the cell floods it");
            expect(context, MiningSafety.ADJACENT_FLUID, MiningSafety.openingRefusal(bot, besideLava),
                    "an open cell beside lava is the one the lava comes in through");
            expect(context, null, MiningSafety.openingRefusal(bot, hiddenRock),
                    "lava behind rock the bot cannot see is not read");
            AIPlayerManager.INSTANCE.despawn(world.getServer(), "OpeningRefusalGT");
            context.succeed();
        });
    }

    private static void expect(GameTestHelper context, String expected, String actual, String why) {
        require(context, java.util.Objects.equals(expected, actual),
                why + ": expected " + expected + " but the verdict was " + actual);
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
