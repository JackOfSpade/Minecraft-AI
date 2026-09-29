package io.github.zoyluo.minecraftai.task;

import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.require;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Real-server proofs of the self-preservation reflexes added after the Baritone/PlayerEngine survival
 * comparison: food choice that keeps reserve food, the fire reflex (bucket, observed water, fire block) and
 * the powder-snow escape.
 */
public final class SelfPreservationGameTests {
    // ---- food choice ----

    /**
     * A hungry bot eats the bread and keeps the golden apple; the enchanted golden apple, chorus fruit and
     * suspicious stew are never eaten automatically (a bot carrying only reserve food does not eat at all);
     * and in a low-health emergency the golden apple IS eaten.
     */
    @GameTest(environment = "minecraftai-gametest:self_preservation_game_tests_hungry_bot_eats_bread_and_keeps_the_reserve_food", maxTicks = 700)
    public void hungryBotEatsBreadAndKeepsTheReserveFood(GameTestHelper context) {
        AIPlayerEntity bread = spawnOnPlatform(context, "BreadOverAppleGT", 20, 5, 30, 4);
        bread.getFoodData().setFoodLevel(10);
        bread.getFoodData().setSaturation(0.0F);
        InventoryAction.giveItem(bread, new ItemStack(Items.GOLDEN_APPLE, 1));
        InventoryAction.giveItem(bread, new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1));
        InventoryAction.giveItem(bread, new ItemStack(Items.CHORUS_FRUIT, 3));
        InventoryAction.giveItem(bread, new ItemStack(Items.SUSPICIOUS_STEW, 1));
        InventoryAction.giveItem(bread, new ItemStack(Items.BREAD, 3));

        AIPlayerEntity reserveOnly = spawnOnPlatform(context, "ReserveOnlyGT", 40, 5, 30, 4);
        reserveOnly.getFoodData().setFoodLevel(6);
        reserveOnly.getFoodData().setSaturation(0.0F);
        InventoryAction.giveItem(reserveOnly, new ItemStack(Items.GOLDEN_APPLE, 1));
        InventoryAction.giveItem(reserveOnly, new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1));
        InventoryAction.giveItem(reserveOnly, new ItemStack(Items.CHORUS_FRUIT, 3));
        InventoryAction.giveItem(reserveOnly, new ItemStack(Items.SUSPICIOUS_STEW, 1));
        require(context, InventoryAction.findFoodSlot(reserveOnly) < 0
                        && InventoryAction.findSafeFoodSlot(reserveOnly) < 0
                        && !InventoryAction.hasSafeFood(reserveOnly),
                "reserve food counted as ordinary food for a healthy bot");

        AIPlayerEntity emergency = spawnOnPlatform(context, "EmergencyAppleGT", 60, 5, 30, 4);
        emergency.setHealth(4.0F);
        emergency.getFoodData().setFoodLevel(12);
        emergency.getFoodData().setSaturation(0.0F);
        InventoryAction.giveItem(emergency, new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1));
        InventoryAction.giveItem(emergency, new ItemStack(Items.CHORUS_FRUIT, 2));
        InventoryAction.giveItem(emergency, new ItemStack(Items.GOLDEN_APPLE, 1));

        int[] ticks = {0};
        context.failIfEver(() -> {
            ticks[0]++;
            require(context, InventoryAction.countItem(bread, Items.GOLDEN_APPLE) == 1
                            && InventoryAction.countItem(bread, Items.ENCHANTED_GOLDEN_APPLE) == 1
                            && InventoryAction.countItem(bread, Items.CHORUS_FRUIT) == 3
                            && InventoryAction.countItem(bread, Items.SUSPICIOUS_STEW) == 1,
                    "the bot ate reserve food while it carried bread");
            require(context, !(TaskManager.INSTANCE.getActive(reserveOnly).orElse(null) instanceof EatTask)
                            && InventoryAction.countItem(reserveOnly, Items.GOLDEN_APPLE) == 1
                            && InventoryAction.countItem(reserveOnly, Items.ENCHANTED_GOLDEN_APPLE) == 1
                            && InventoryAction.countItem(reserveOnly, Items.CHORUS_FRUIT) == 3
                            && InventoryAction.countItem(reserveOnly, Items.SUSPICIOUS_STEW) == 1,
                    "a healthy bot with only reserve food ate it");
            require(context, InventoryAction.countItem(emergency, Items.ENCHANTED_GOLDEN_APPLE) == 1
                            && InventoryAction.countItem(emergency, Items.CHORUS_FRUIT) == 2,
                    "the low-health bot ate an enchanted golden apple or chorus fruit");
            boolean breadDone = bread.getFoodData().getFoodLevel() >= 20
                    && InventoryAction.countItem(bread, Items.BREAD) < 3
                    && !(TaskManager.INSTANCE.getActive(bread).orElse(null) instanceof EatTask);
            boolean appleDone = InventoryAction.countItem(emergency, Items.GOLDEN_APPLE) == 0;
            if (ticks[0] < 500) {
                return; // hold on long enough for a wrong reserve bite to show up
            }
            require(context, breadDone, "the bread bot did not eat bread to a full bar: food="
                    + bread.getFoodData().getFoodLevel() + " bread=" + InventoryAction.countItem(bread, Items.BREAD));
            require(context, appleDone, "the low-health bot did not use its golden apple");
            cleanUp(bread, reserveOnly, emergency);
            context.succeed();
        });
    }

    // ---- fire ----

    /** A burning bot with a water bucket puts itself out at its feet and takes the water back. */
    @GameTest(environment = "minecraftai-gametest:self_preservation_game_tests_burning_bot_with_water_bucket_puts_itself_out_and_recovers_the_water", maxTicks = 500)
    public void burningBotWithWaterBucketPutsItselfOutAndRecoversTheWater(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "BucketBurnerGT", 20, 5, 90, 4);
        InventoryAction.giveItem(bot, new ItemStack(Items.WATER_BUCKET, 1));
        BlockPos feet = bot.blockPosition();
        bot.setRemainingFireTicks(300);
        boolean[] sawTask = {false};
        boolean[] sawWater = {false};
        context.failIfEver(() -> {
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            sawTask[0] |= active instanceof FireExtinguishTask;
            sawWater[0] |= InventoryAction.countItem(bot, Items.BUCKET) > 0
                    && !bot.level().getFluidState(feet).isEmpty();
            require(context, bot.isAlive() && bot.getHealth() > 0.0F, "the burning bot died");
            if (!bot.isOnFire() && InventoryAction.countItem(bot, Items.WATER_BUCKET) == 1
                    && !(active instanceof FireExtinguishTask)) {
                require(context, sawTask[0], "the fire went out without the fire reflex");
                require(context, sawWater[0], "the reflex never placed water at the feet");
                require(context, InventoryAction.countItem(bot, Items.BUCKET) == 0,
                        "an empty bucket is left over after the water was recovered");
                require(context, bot.level().getFluidState(feet).isEmpty()
                                && bot.level().getFluidState(feet.above()).isEmpty(),
                        "the placed water was left in the world");
                cleanUp(bot);
                context.succeed();
            }
        });
    }

    /** A burning bot without a bucket walks into water it can see. */
    @GameTest(environment = "minecraftai-gametest:self_preservation_game_tests_burning_bot_next_to_observed_water_walks_into_it", maxTicks = 500)
    public void burningBotNextToObservedWaterWalksIntoIt(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "PoolWalkerGT", 20, 5, 150, 7);
        ServerLevel world = context.getLevel();
        BlockPos feet = bot.blockPosition();
        for (int dx = 4; dx <= 6; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setBlock(feet.offset(dx, -2, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.offset(dx, -1, dz), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        bot.setRemainingFireTicks(300);
        boolean[] sawTask = {false};
        boolean[] sawWater = {false};
        context.failIfEver(() -> {
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            sawTask[0] |= active instanceof FireExtinguishTask;
            sawWater[0] |= bot.isInWater() || world.getFluidState(bot.blockPosition()).is(net.minecraft.tags.FluidTags.WATER);
            require(context, bot.isAlive() && bot.getHealth() > 0.0F, "the burning bot died");
            if (!bot.isOnFire()) {
                require(context, sawTask[0] && sawWater[0],
                        "the fire went out without walking into the pool: task=" + sawTask[0] + " water=" + sawWater[0]);
                require(context, InventoryAction.countItem(bot, Items.WATER_BUCKET) == 0, "unexpected bucket");
                cleanUp(bot);
                context.succeed();
            }
        });
    }

    /** A fire block in the bot's own cell is punched out (real break) instead of standing in it. */
    @GameTest(environment = "minecraftai-gametest:self_preservation_game_tests_burning_bot_punches_out_the_fire_block_it_stands_in", maxTicks = 400)
    public void burningBotPunchesOutTheFireBlockItStandsIn(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(20, 5, 210));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.NETHERRACK.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 6; dy++) {
                    world.setBlock(cell.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setDayTime(1000L);
        AIPlayerEntity bot = spawnAt(context, "FirePuncherGT", feet);
        world.setBlock(feet, Blocks.FIRE.defaultBlockState(), Block.UPDATE_ALL);
        bot.setRemainingFireTicks(160);
        boolean[] sawTask = {false};
        context.failIfEver(() -> {
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            sawTask[0] |= active instanceof FireExtinguishTask;
            require(context, bot.isAlive() && bot.getHealth() > 0.0F, "the bot burned to death in the fire block");
            if (world.getBlockState(feet).isAir()) {
                require(context, sawTask[0], "the fire block vanished without the fire reflex punching it");
                cleanUp(bot);
                context.succeed();
            }
        });
    }

    // ---- powder snow ----

    /** A bot sunk in a pit of powder snow gets out. */
    @GameTest(environment = "minecraftai-gametest:self_preservation_game_tests_bot_stuck_in_powder_snow_gets_out", maxTicks = 600)
    public void botStuckInPowderSnowGetsOut(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "SnowStuckGT", 20, 5, 270, 7);
        ServerLevel world = context.getLevel();
        BlockPos feet = bot.blockPosition();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlock(feet.offset(dx, dy, dz), Blocks.POWDER_SNOW.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        boolean[] sawTask = {false};
        int[] outTicks = {0};
        context.failIfEver(() -> {
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            sawTask[0] |= active instanceof PowderSnowEscapeTask;
            require(context, bot.isAlive() && bot.getHealth() > 0.0F, "the bot died in the powder snow");
            boolean sunk = bot.isInPowderSnow
                    || world.getBlockState(bot.blockPosition()).is(Blocks.POWDER_SNOW)
                    || world.getBlockState(bot.blockPosition().above()).is(Blocks.POWDER_SNOW);
            outTicks[0] = !sunk && bot.onGround() ? outTicks[0] + 1 : 0;
            if (outTicks[0] >= 10) {
                require(context, sawTask[0], "the bot left the powder snow without the escape task");
                cleanUp(bot);
                context.succeed();
            }
        });
    }

    // ---- fixtures ----

    private static void buildFloor(ServerLevel world, BlockPos feet, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 6; dy++) {
                    world.setBlock(cell.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    private static AIPlayerEntity spawnOnPlatform(GameTestHelper context, String name, int x, int y, int z, int radius) {
        BlockPos feet = context.absolutePos(new BlockPos(x, y, z));
        buildFloor(context.getLevel(), feet, radius);
        context.getLevel().setDayTime(1000L);
        return spawnAt(context, name, feet);
    }

    private static AIPlayerEntity spawnAt(GameTestHelper context, String name, BlockPos feet) {
        ServerLevel world = context.getLevel();
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(5.0F);
        return bot;
    }

    private static void cleanUp(AIPlayerEntity... bots) {
        for (AIPlayerEntity bot : bots) {
            TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
            DangerWatcher.INSTANCE.clear(bot);
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), bot.getGameProfile().name());
        }
    }
}
