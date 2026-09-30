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
        require(context, !InventoryAction.hasFood(reserveOnly)
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

    /**
     * The water the fire reflex placed at the feet is taken back even when the task ends without its normal
     * pick-up path: here it is aborted (a preempting request, despawn, death all end the same way) right after the
     * water went down, and the source must be gone with the bucket full again.
     */
    @GameTest(environment = "minecraftai-gametest:self_preservation_game_tests_fire_reflex_water_is_recovered_after_an_aborted_extinguish", maxTicks = 500)
    public void fireReflexWaterIsRecoveredAfterAnAbortedExtinguish(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "AbortBurnerGT", 20, 5, 330, 4);
        InventoryAction.giveItem(bot, new ItemStack(Items.WATER_BUCKET, 1));
        BlockPos feet = bot.blockPosition();
        ServerLevel world = context.getLevel();
        bot.setRemainingFireTicks(300);
        int[] abortedAt = {-1};
        context.failIfEver(() -> {
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            require(context, bot.isAlive() && bot.getHealth() > 0.0F, "the burning bot died");
            if (abortedAt[0] < 0) {
                boolean placed = InventoryAction.countItem(bot, Items.BUCKET) > 0
                        && InventoryAction.countItem(bot, Items.WATER_BUCKET) == 0
                        && world.getFluidState(feet).isSource();
                if (placed && active instanceof FireExtinguishTask) {
                    // The fire is put out first so the watcher does not start a second reflex, then the task
                    // is aborted before its own PICKUP phase ever ran.
                    bot.setRemainingFireTicks(0);
                    TaskManager.INSTANCE.abort(bot);
                    abortedAt[0] = (int) context.getTick();
                    require(context, InventoryAction.countItem(bot, Items.WATER_BUCKET) == 1
                                    && InventoryAction.countItem(bot, Items.BUCKET) == 0,
                            "the abort left the bucket empty: water=" + InventoryAction.countItem(bot, Items.WATER_BUCKET)
                                    + " empty=" + InventoryAction.countItem(bot, Items.BUCKET));
                    require(context, !world.getFluidState(feet).isSource(),
                            "the placed water source was left in the world after the abort");
                }
                return;
            }
            if (context.getTick() - abortedAt[0] >= 40) {
                require(context, world.getFluidState(feet).isEmpty() && world.getFluidState(feet.above()).isEmpty(),
                        "leftover water around the feet: " + world.getFluidState(feet));
                require(context, InventoryAction.countItem(bot, Items.WATER_BUCKET) == 1,
                        "the water bucket was not returned");
                cleanUp(bot);
                context.succeed();
            }
        });
    }

    /**
     * A burning bot standing under a roof (no rain reaches it) with no water in reach walks out to a cell the
     * rain does reach: findWaterOrRain offers an observed, standable, rained-on cell that is not under the roof,
     * and the fire goes out there.
     */
    @GameTest(environment = "minecraftai-gametest:self_preservation_game_tests_burning_bot_under_aroof_walks_out_into_the_rain", maxTicks = 600)
    public void burningBotUnderARoofWalksOutIntoTheRain(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "RainWalkerGT", 20, 5, 390, 8);
        ServerLevel world = context.getLevel();
        BlockPos feet = bot.blockPosition();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.setBlock(feet.offset(dx, 3, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        world.setWeatherParameters(0, 6000, true, false);
        world.setRainLevel(1.0F);
        io.github.zoyluo.minecraftai.gametest.GameTestCleanup.whenFinished(context, () -> {
            world.setWeatherParameters(6000, 0, false, false);
            world.setRainLevel(0.0F);
        });
        boolean[] ignited = {false};
        boolean[] sawTask = {false};
        context.failIfEver(() -> {
            require(context, bot.isAlive() && bot.getHealth() > 0.0F, "the burning bot died");
            if (context.getTick() < 5) {
                return;
            }
            if (!ignited[0]) {
                require(context, world.isRainingAt(feet.offset(4, 1, 0)),
                        "fixture: it is not raining at the open cell (biome or weather): raining=" + world.isRaining());
                require(context, !world.isRainingAt(feet.above()), "fixture: the roof does not shelter the bot");
                bot.setRemainingFireTicks(300);
                var candidate = FireExtinguishTask.findWaterOrRain(bot);
                require(context, candidate.isPresent(), "no rain candidate was offered to the burning bot");
                BlockPos cell = candidate.get();
                require(context, world.isRainingAt(cell.above()) && !world.getFluidState(cell).is(net.minecraft.tags.FluidTags.WATER),
                        "the candidate is not a rained-on dry cell: " + cell);
                require(context, Math.abs(cell.getX() - feet.getX()) > 2 || Math.abs(cell.getZ() - feet.getZ()) > 2,
                        "the candidate is under the roof: " + cell);
                ignited[0] = true;
                return;
            }
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            sawTask[0] |= active instanceof FireExtinguishTask;
            if (!bot.isOnFire()) {
                require(context, sawTask[0], "the fire went out without the fire reflex");
                require(context, world.isRainingAt(bot.blockPosition().above()),
                        "the bot was put out but not by walking into the rain: " + bot.blockPosition());
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

    /**
     * A running fire rescue is preempted by a critical fight, not the other way round: at the retreat health
     * an observed hostile in range makes DangerWatcher assign a CombatTask, the FireExtinguishTask is PAUSED
     * beneath it (the fire is still there), and once the hostile is gone and the bot has healed the rescue
     * resumes or the fire is out. Before this the preemption was only pinned by a source-contract test.
     */
    @GameTest(environment = "minecraftai-gametest:self_preservation_game_tests_critical_fight_pauses_a_running_fire_rescue_and_it_completes_after", maxTicks = 700)
    public void criticalFightPausesARunningFireRescueAndItCompletesAfter(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "FireFighterGT", 20, 5, 210, 12);
        ServerLevel world = context.getLevel();
        BlockPos feet = bot.blockPosition();
        // Water eight blocks off keeps the walking rescue running for a while (no bucket to shortcut it).
        for (int dx = 6; dx <= 8; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setBlock(feet.offset(dx, -2, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.offset(dx, -1, dz), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD, 1));
        bot.setRemainingFireTicks(600);
        int retreatHp = io.github.zoyluo.minecraftai.MinecraftAiConfig.get().combat().retreatHp();
        FireExtinguishTask[] rescue = {null};
        CombatTask[] combat = {null};
        net.minecraft.world.entity.monster.zombie.Husk[] husk = {null};
        int[] stage = {0};
        int[] outAt = {-1};
        // What the bot could know of the husk in the first ticks of stage 1 (for the failure message only).
        java.util.List<String> firstTicks = new java.util.ArrayList<>();
        context.failIfEver(() -> {
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            require(context, bot.isAlive() && bot.getHealth() > 0.0F, "the burning bot died, stage=" + stage[0]);
            switch (stage[0]) {
                case 0 -> {
                    if (active instanceof FireExtinguishTask fire) {
                        rescue[0] = fire;
                        BlockPos huskFeet = bot.blockPosition().west(3);
                        var h = net.minecraft.world.entity.EntityType.HUSK.create(world,
                                net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                        require(context, h != null, "could not create the husk fixture");
                        h.setPersistenceRequired();
                        h.setNoAi(true);
                        h.snapTo(huskFeet.getX() + 0.5D, huskFeet.getY(), huskFeet.getZ() + 0.5D, 90.0F, 0.0F);
                        world.addFreshEntity(h);
                        husk[0] = h;
                        bot.setHealth(retreatHp);
                        stage[0] = 1;
                    }
                }
                case 1 -> {
                    if (active instanceof CombatTask fight) {
                        combat[0] = fight;
                        require(context, rescue[0].state() == TaskState.PAUSED
                                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == rescue[0],
                                "the fight took over but the fire rescue was not paused beneath it: "
                                        + rescue[0].state());
                        // The hostile is gone and the bot has healed: the rescue may go on.
                        husk[0].discard();
                        bot.setHealth(bot.getMaxHealth());
                        bot.hurtTime = 0;
                        stage[0] = 2;
                    } else {
                        if (firstTicks.size() < 12) {
                            firstTicks.add(context.getTick() + ":hp=" + bot.getHealth()
                                    + ",ticking=" + world.isPositionEntityTicking(husk[0].blockPosition())
                                    + ",found=" + !world.getEntitiesOfClass(husk[0].getClass(), bot.getBoundingBox().inflate(10.0D)).isEmpty()
                                    + ",sight=" + bot.hasLineOfSight(husk[0]) + ",active=" + (active == null ? "none" : active.name()));
                        }
                        require(context, context.getTick() < 200,
                                "a critical fight never took over from the fire rescue: active="
                                        + (active == null ? "none" : active.name()) + " first ticks " + firstTicks);
                    }
                }
                default -> {
                    if (!bot.isOnFire()) {
                        if (outAt[0] < 0) {
                            outAt[0] = (int) context.getTick();
                        }
                        // A fresh rescue may have put the fire out first; the older frame resumes (and ends
                        // at once, nothing is burning) on a following scan: it must not stay paused for good.
                        if (rescue[0].state() != TaskState.PAUSED) {
                            cleanUp(bot);
                            context.succeed();
                        } else {
                            require(context, context.getTick() < outAt[0] + 100,
                                    "the fire is out but the rescue frame was left paused");
                        }
                    } else {
                        require(context, context.getTick() < 650,
                                "the fire was never put out after the fight: active="
                                        + (active == null ? "none" : active.name())
                                        + " rescue=" + rescue[0].state());
                    }
                }
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
