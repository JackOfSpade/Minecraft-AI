package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.EquipAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.MiningCursor;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Live scheduling proofs for DangerWatcher's task-preemption boundaries. */
public final class DangerWatcherLowHealthGameTests {
    @GameTest(maxTicks = 40)
    public void exactObsidianPickBudgetIsNotPreemptedByGenericResupply(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "ObsidianToolBudgetGT", 2);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);

        // Give the damaged pick first so it is genuinely held. Raw 33 means exactly 32 usable
        // breaks: sufficient for this mission, but well inside DangerWatcher's generic 10% band.
        ItemStack diamond = new ItemStack(Items.DIAMOND_PICKAXE);
        diamond.setDamageValue(diamond.getMaxDamage() - 33);
        InventoryAction.giveItem(bot, diamond);
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.WATER_BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 52));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 24));
        for (int index = 0; index < 4; index++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        }
        require(context, bot.getMainHandItem().is(Items.DIAMOND_PICKAXE)
                        && MiningServiceTask.usableDurability(bot.getMainHandItem()) == 32,
                "fixture did not hold the exact raw-33 obsidian pick");

        MiningServiceTask service = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                ServicePolicy.obsidianPreflight(32));
        TaskManager.INSTANCE.assign(bot, service,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_obsidian_service_tool_budget"));
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        requireUnpreempted(context, bot, service, "MiningServiceTask");

        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_service_probe_complete");
        CreateObsidianTask create = new CreateObsidianTask(32);
        TaskManager.INSTANCE.assign(bot, create,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_create_obsidian_tool_budget"));
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        requireUnpreempted(context, bot, create, "CreateObsidianTask");
        require(context, MiningServiceTask.usableDurability(bot.getMainHandItem()) == 32,
                "generic resupply damaged or replaced the exact-budget pick");

        TaskManager.INSTANCE.pauseFor(bot, "gametest_obsidian_safety_pause");
        require(context, TaskManager.INSTANCE.peekPaused(bot).orElse(null) == create,
                "fixture did not preserve the paused CreateObsidianTask");
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        requireUnpreempted(context, bot, create, "paused CreateObsidianTask");
        require(context, MiningServiceTask.usableDurability(bot.getMainHandItem()) == 32,
                "paused exact-budget owner triggered generic local tool crafting");
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_create_obsidian_raw_one_settlement_is_not_preempted", maxTicks = 800)
    public void createObsidianRawOneSettlementIsNotPreempted(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CreateRawOneOwnerGT", 2);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        BlockPos target = bot.blockPosition().east();
        bot.level().setBlock(
                target, Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);

        ItemStack damagedDiamond = new ItemStack(Items.DIAMOND_PICKAXE);
        damagedDiamond.setDamageValue(damagedDiamond.getMaxDamage() - 2);
        InventoryAction.giveItem(bot, damagedDiamond);
        ItemStack diamond = bot.getMainHandItem();
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 32));
        InventoryAction.giveItem(bot, new ItemStack(Items.WATER_BUCKET));
        require(context, diamond.is(Items.DIAMOND_PICKAXE) && rawDurability(diamond) == 2,
                "fixture did not hold the raw-two diamond pick");

        CreateObsidianTask task = new CreateObsidianTask(
                1, createActiveBreakCheckpoint(bot.blockPosition(), target, bot.blockPosition()));
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_create_raw_one_owner"));
        AtomicBoolean scannedRawOneSettlement = new AtomicBoolean();

        context.failIfEver(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("raw-two CreateObsidianTask ended as "
                        + task.state() + ":" + task.failureReason()
                        + " checkpoint=" + task.checkpoint()));
            }
            if (!scannedRawOneSettlement.get()
                    && bot.level().getBlockState(target).isAir()
                    && rawDurability(diamond) == 1
                    && task.state() == TaskState.RUNNING) {
                Map<String, String> settlement = task.checkpoint();
                require(context, encode(target).equals(settlement.get("active_break_pos"))
                                || encode(target).equals(settlement.get("pending_pickup_pos")),
                        "raw-one Create state was not an active-break/pickup settlement: "
                                + settlement);
                require(context, bot.getMainHandItem().getItem() == Items.DIAMOND_PICKAXE,
                        "raw-one Create settlement was not holding its pickaxe");
                DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
                requireUnpreempted(context, bot, task, "raw-one CreateObsidianTask settlement");
                scannedRawOneSettlement.set(true);
            }
            if (task.state() == TaskState.COMPLETED) {
                require(context, scannedRawOneSettlement.get(),
                        "CreateObsidianTask completed without exposing the raw-one settlement window");
                require(context, InventoryAction.countItem(bot, Items.OBSIDIAN) == 1,
                        "CreateObsidianTask did not physically settle the final obsidian pickup");
                require(context, rawDurability(diamond) == 1,
                        "legal raw-two break did not leave the expected raw-one pick");
                despawnAndComplete(context, bot);
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_ore_dig_raw_one_pickup_and_active_break_remain_owned", maxTicks = 40)
    public void oreDigRawOnePickupAndActiveBreakRemainOwned(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "OreRawOneOwnerGT", 2);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        ItemStack damagedDiamond = new ItemStack(Items.DIAMOND_PICKAXE);
        damagedDiamond.setDamageValue(damagedDiamond.getMaxDamage() - 1);
        InventoryAction.giveItem(bot, damagedDiamond);
        ItemStack diamond = bot.getMainHandItem();
        require(context, diamond.is(Items.DIAMOND_PICKAXE) && rawDurability(diamond) == 1,
                "fixture did not hold the raw-one diamond pick");

        BlockPos debt = bot.blockPosition().east();
        OreDigTask pickupOwner = new OreDigTask(Set.of(Blocks.IRON_ORE), 1,
                oreDigCheckpoint(bot.blockPosition(), debt, null));
        TaskManager.INSTANCE.assign(bot, pickupOwner,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_raw_one_pickup_owner"));
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        requireUnpreempted(context, bot, pickupOwner, "raw-one OreDigTask pickup");
        require(context, encode(debt).equals(pickupOwner.checkpoint().get("pending_pickup_pos")),
                "OreDig pickup debt was changed by DangerWatcher");

        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_pickup_owner_probe_complete");
        bot.level().setBlock(
                debt, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        OreDigTask activeBreakOwner = new OreDigTask(Set.of(Blocks.IRON_ORE), 1,
                oreDigCheckpoint(bot.blockPosition(), null, debt));
        TaskManager.INSTANCE.assign(bot, activeBreakOwner,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ore_raw_one_active_owner"));
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        requireUnpreempted(context, bot, activeBreakOwner, "raw-one OreDigTask active break");
        require(context, encode(debt).equals(activeBreakOwner.checkpoint().get("active_break_pos")),
                "OreDig active-break debt was changed by DangerWatcher");
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_raw_one_pick_on_non_owner_still_triggers_generic_resupply", maxTicks = 40)
    public void rawOnePickOnNonOwnerStillTriggersGenericResupply(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "RawOneNonOwnerGT", 2);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        ItemStack diamond = new ItemStack(Items.DIAMOND_PICKAXE);
        diamond.setDamageValue(diamond.getMaxDamage() - 1);
        InventoryAction.giveItem(bot, diamond);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_raw_one_non_owner"));

        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof ResupplyTask,
                "non-owner raw-one pick did not trigger generic resupply: "
                        + (active == null ? "idle" : active.name()));
        require(context, TaskManager.INSTANCE.hasPaused(bot)
                        && work.state() == TaskState.PAUSED,
                "generic resupply did not preserve the interrupted non-owner task");
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_stone_pick_craft_ignores_nearly_broken_held_pick", maxTicks = 40)
    public void stonePickCraftIgnoresNearlyBrokenHeldPick(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CraftHeldPickOwnerGT", 2);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        ItemStack nearlyBroken = new ItemStack(Items.STONE_PICKAXE);
        nearlyBroken.setDamageValue(nearlyBroken.getMaxDamage() - 2);
        InventoryAction.giveItem(bot, nearlyBroken);
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 15));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 10));
        require(context, bot.getMainHandItem().is(Items.STONE_PICKAXE)
                        && rawDurability(bot.getMainHandItem()) == 2,
                "fixture did not hold the nearly-broken stone pick");

        CraftTask craft = new CraftTask(Items.STONE_PICKAXE, 5);
        TaskManager.INSTANCE.assign(bot, craft,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_craft_held_pick_owner"));
        int sticksBefore = InventoryAction.countItem(bot, Items.STICK);
        int stoneBefore = InventoryAction.countItem(bot, Items.COBBLESTONE);

        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

        requireUnpreempted(context, bot, craft, "stone-pick CraftTask");
        require(context, InventoryAction.countItem(bot, Items.STICK) == sticksBefore
                        && InventoryAction.countItem(bot, Items.COBBLESTONE) == stoneBefore,
                "generic resupply spent sealed craft inputs");
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_paused_mining_owner_resupplies_in_place_without_base_travel", maxTicks = 450)
    public void pausedMiningOwnerResuppliesInPlaceWithoutBaseTravel(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "PausedMineLocalSupplyGT", 2);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        BlockPos origin = bot.blockPosition().immutable();

        ItemStack nearlyBroken = new ItemStack(Items.WOODEN_PICKAXE);
        nearlyBroken.setDamageValue(nearlyBroken.getMaxDamage() - 2);
        InventoryAction.giveItem(bot, nearlyBroken);
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 2));
        require(context, bot.getMainHandItem().is(Items.WOODEN_PICKAXE)
                        && rawDurability(bot.getMainHandItem()) == 2,
                "fixture did not hold the nearly-broken wooden pick");

        DigDownTask owner = new DigDownTask(Blocks.STONE, 3);
        TaskManager.INSTANCE.assign(bot, owner,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_paused_mining_local_supply"));
        TaskManager.INSTANCE.pauseFor(bot, "gametest_safety_displacement_complete");
        require(context, TaskManager.INSTANCE.getActive(bot).isEmpty()
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == owner
                        && owner.state() == TaskState.PAUSED,
                "fixture did not preserve the paused DigDown owner");

        // A remembered remote base makes an ordinary ResupplyTask eligible to travel. The paused
        // owner branch must ignore it and use only the carried crafting inputs at this exact pose.
        io.github.zoyluo.minecraftai.memory.BotMemoryStore.INSTANCE.of(bot.getUUID())
                .markPlace("base", bot.level(), origin.offset(4, 0, 4));
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof ResupplyTask,
                "paused mining owner did not trigger tool service: "
                        + (active == null ? "idle" : active.name()));
        ResupplyTask resupply = (ResupplyTask) active;
        require(context, resupply.localOnly(),
                "paused mining owner received a travelling ResupplyTask");
        AtomicBoolean observedLocalOnly = new AtomicBoolean();

        // Real survival-paced reclaim (mining the borrowed crafting table back down, then walking
        // the short physical hop to its dropped item) can legitimately take one or two local steps
        // right next to `origin` -- that is not "travel". Only the remembered base 4,0,4 blocks away
        // is forbidden, so bound both checks to a small local radius instead of demanding the bot
        // and its path executor stay perfectly motionless for the whole craft+reclaim cycle.
        double localRadiusSquared = 9.0D;
        context.failIfEver(() -> {
            if (resupply.describe().contains("note=local_only")) {
                observedLocalOnly.set(true);
            }
            require(context, bot.blockPosition().distSqr(origin) <= localRadiusSquared,
                    "paused-owner resupply moved toward the remembered base: "
                            + bot.blockPosition().toShortString());
            BlockPos activeGoal = bot.getActionPack().activePathGoal();
            require(context, activeGoal == null
                            || activeGoal.distSqr(origin) <= localRadiusSquared,
                    "paused-owner resupply started a base path");
            if (resupply.state() == TaskState.FAILED
                    || resupply.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("local-only resupply ended as "
                        + resupply.state() + ":" + resupply.failureReason()));
            }
            if (resupply.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, observedLocalOnly.get(),
                    "paused-owner resupply never entered its local-only boundary");
            require(context, bot.getMainHandItem().is(Items.WOODEN_PICKAXE)
                            && rawDurability(bot.getMainHandItem())
                            == bot.getMainHandItem().getMaxDamage(),
                    "local-only resupply did not craft and equip a fresh wooden pick");
            require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == owner
                            && !TaskManager.INSTANCE.hasPaused(bot)
                            && owner.state() == TaskState.RUNNING,
                    "local tool service did not resume the same DigDown instance");
            io.github.zoyluo.minecraftai.memory.BotMemoryStore.INSTANCE.remove(bot.getUUID());
            despawnAndComplete(context, bot);
        });
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_paused_mining_owner_does_not_travel_for_damaged_combat_weapon", maxTicks = 40)
    public void pausedMiningOwnerDoesNotTravelForDamagedCombatWeapon(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "PausedMineWeaponBoundaryGT", 2);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        BlockPos origin = bot.blockPosition().immutable();

        ItemStack damagedSword = new ItemStack(Items.WOODEN_SWORD);
        damagedSword.setDamageValue(damagedSword.getMaxDamage() - 1);
        InventoryAction.giveItem(bot, damagedSword);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));
        require(context, bot.getMainHandItem().is(Items.WOODEN_SWORD)
                        && rawDurability(bot.getMainHandItem()) == 1,
                "fixture did not retain the damaged combat weapon in hand");

        DigDownTask owner = new DigDownTask(Blocks.STONE, 3);
        TaskManager.INSTANCE.assign(bot, owner,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_paused_mining_weapon_boundary"));
        TaskManager.INSTANCE.pauseFor(bot, "gametest_combat_displacement_complete");
        io.github.zoyluo.minecraftai.memory.BotMemoryStore.INSTANCE.of(bot.getUUID())
                .markPlace("base", bot.level(), origin.offset(4, 0, 4));

        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, !(active instanceof ResupplyTask),
                "damaged combat weapon started base-travelling resupply over a paused miner");
        require(context, active == owner,
                "paused mining owner was not resumed after combat weapon service was suppressed: "
                        + (active == null ? "idle" : active.name()));
        require(context, !TaskManager.INSTANCE.hasPaused(bot),
                "paused mining owner remained stranded behind combat weapon service");
        require(context, bot.blockPosition().equals(origin)
                        && bot.getActionPack().isPathExecutorIdle(),
                "combat weapon boundary moved toward the remembered base");

        // DangerWatcher scans continuously. Once the paused owner is active, the next scan must
        // still recognize its transaction ownership instead of treating the damaged sword as an
        // ordinary background resupply request.
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active == owner
                        && !TaskManager.INSTANCE.hasPaused(bot)
                        && bot.getActionPack().isPathExecutorIdle(),
                "second scan started combat-weapon resupply over the active mining owner");
        io.github.zoyluo.minecraftai.memory.BotMemoryStore.INSTANCE.remove(bot.getUUID());
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_healthy_melee_combat_is_not_preempted_by_underground_entomb", maxTicks = 60)
    public void healthyMeleeCombatIsNotPreemptedByUndergroundEntomb(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatEntombGT", 2);
        BlockPos origin = bot.blockPosition().immutable();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                // Keep a conventional two-block-high chamber. The previous y+3 roof depended on
                // a heightmap update outside this empty template and intermittently read as open
                // sky when the large default batch prepared neighbouring fixtures in parallel.
                context.getLevel().setBlock(origin.offset(dx, 2, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 16));
        // Sky light under the fresh roof only drops once the light engine catches up with the
        // fixture placement; under parallel batch load that can lag past tick 1 and made this
        // fixture intermittently read as open sky on CI. Gate on the observed light instead of
        // a fixed tick so the assertions cannot race the engine.
        AtomicBoolean asserted = new AtomicBoolean();
        context.failIfEver(() -> {
            if (asserted.get() || context.getLevel().canSeeSky(origin)) {
                return;
            }
            asserted.set(true);
            require(context, !context.getLevel().canSeeSky(origin),
                    "combat-entomb fixture was not underground");
            Zombie zombie = EntityType.ZOMBIE.create(context.getLevel(), EntitySpawnReason.COMMAND);
            if (zombie == null) {
                despawnAndComplete(context, bot);
                context.fail(Component.nullToEmpty("failed to create close-combat zombie fixture"));
                return;
            }
            BlockPos hostileFeet = origin.east();
            zombie.setPersistenceRequired();
            zombie.snapTo(hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                    hostileFeet.getZ() + 0.5D, 0.0F, 0.0F);
            context.getLevel().addFreshEntity(zombie);

            CombatTask combat = CombatTask.defensive(zombie, 6.0F, origin);
            TaskManager.INSTANCE.assign(bot, combat,
                    TaskOrigin.safety("gametest_close_combat_entomb"));
            combat.tick(bot);
            bot.setHealth(17.5F);
            bot.hurtTime = 5;

            DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            require(context, active == combat,
                    "healthy defensive combat was replaced with "
                            + (active == null ? "idle" : active.name()));
            require(context, !TaskManager.INSTANCE.hasPaused(bot),
                    "healthy defensive combat was pushed behind an emergency shelter");
            require(context, combat.state() == TaskState.RUNNING,
                    "healthy defensive combat became terminal: "
                            + combat.state() + ":" + combat.failureReason());

            zombie.discard();
            despawnAndComplete(context, bot);
        });
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_equal_damage_weapon_selection_prefers_remaining_durability", maxTicks = 20)
    public void equalDamageWeaponSelectionPrefersRemainingDurability(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatDurabilityGT", 2);
        ItemStack nearlyBroken = new ItemStack(Items.WOODEN_SWORD);
        nearlyBroken.setDamageValue(nearlyBroken.getMaxDamage() - 1);
        InventoryAction.giveItem(bot, nearlyBroken);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        require(context, bot.getMainHandItem().is(Items.WOODEN_SWORD)
                        && rawDurability(bot.getMainHandItem()) == 1,
                "fixture did not initially hold the nearly-broken equal-damage weapon");

        CombatCore.equipMelee(bot);

        require(context, bot.getMainHandItem().is(Items.WOODEN_SWORD)
                        && rawDurability(bot.getMainHandItem())
                        == bot.getMainHandItem().getMaxDamage(),
                "equal-damage selection retained the lower-durability weapon");
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_equal_damage_weapon_selection_prefers_sword_before_durability", maxTicks = 20)
    public void equalDamageWeaponSelectionPrefersSwordBeforeDurability(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatSwordPriorityGT", 2);
        ItemStack twoUseSword = new ItemStack(Items.STONE_SWORD);
        twoUseSword.setDamageValue(twoUseSword.getMaxDamage() - 2);
        ItemStack freshPickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        require(context,
                Double.compare(EquipAction.attackDamage(twoUseSword),
                        EquipAction.attackDamage(freshPickaxe)) == 0,
                "fixture weapons do not have equal attack damage");
        InventoryAction.giveItem(bot, twoUseSword);
        InventoryAction.giveItem(bot, freshPickaxe);

        CombatCore.equipMelee(bot);

        require(context, bot.getMainHandItem().is(Items.STONE_SWORD)
                        && rawDurability(bot.getMainHandItem()) == 2,
                "equal-damage fresh pickaxe displaced the admitted two-use sword");
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_armor_equip_remains_independent_from_melee_weapon_filtering", maxTicks = 20)
    public void armorEquipRemainsIndependentFromMeleeWeaponFiltering(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "ArmorFilterIndependenceGT", 2);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_CHESTPLATE));

        int equipped = EquipAction.equipBestArmor(bot);

        require(context, equipped == 1
                        && bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE),
                "melee weapon filtering suppressed ordinary armor equip");
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_axe_remains_qualified_while_pickaxe_cannot_displace_it", maxTicks = 20)
    public void axeRemainsQualifiedWhilePickaxeCannotDisplaceIt(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "AxeWeaponQualificationGT", 2);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_AXE));

        EquipAction.equipBestWeapon(bot);

        require(context, bot.getMainHandItem().is(Items.STONE_AXE),
                "qualified axe was displaced by a non-weapon pickaxe");
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_pickaxe_only_inventory_cannot_authorize_combat", maxTicks = 40)
    public void pickaxeOnlyInventoryCannotAuthorizeCombat(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "PickaxeOnlyNoCombatGT", 2);
        BlockPos origin = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        require(context, EquipAction.bestWeaponSlot(bot).isEmpty(),
                "stone pickaxe was classified as a qualified melee weapon");

        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_pickaxe_only_no_combat"));
        Zombie zombie = EntityType.ZOMBIE.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (zombie == null) {
            despawnAndComplete(context, bot);
            context.fail(Component.nullToEmpty("failed to create pickaxe-only zombie fixture"));
            return;
        }
        BlockPos hostileFeet = origin.east(2);
        zombie.setPersistenceRequired();
        zombie.setNoAi(true);
        zombie.snapTo(hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                hostileFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(zombie);
        require(context, CombatCore.hasLineOfSight(bot, zombie),
                "pickaxe-only hostile fixture lacked factual line of sight");

        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof EvadeTask,
                "pickaxe-only inventory entered "
                        + (active == null ? "idle" : active.name()) + " instead of Evade");
        require(context, work.state() == TaskState.PAUSED,
                "pickaxe-only Evade did not preserve interrupted work");
        zombie.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_final_use_sword_cannot_authorize_combat", maxTicks = 40)
    public void finalUseSwordCannotAuthorizeCombat(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "FinalUseSwordNoCombatGT", 2);
        BlockPos origin = bot.blockPosition().immutable();
        ItemStack finalUseSword = new ItemStack(Items.STONE_SWORD);
        finalUseSword.setDamageValue(finalUseSword.getMaxDamage() - 1);
        InventoryAction.giveItem(bot, finalUseSword);
        require(context, EquipAction.bestWeaponSlot(bot).isEmpty(),
                "raw-1 sword was admitted as a defensive melee weapon");

        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_final_use_sword_no_combat"));
        Zombie zombie = EntityType.ZOMBIE.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (zombie == null) {
            despawnAndComplete(context, bot);
            context.fail(Component.nullToEmpty("failed to create final-use sword zombie fixture"));
            return;
        }
        BlockPos hostileFeet = origin.east(2);
        zombie.setPersistenceRequired();
        zombie.setNoAi(true);
        zombie.snapTo(hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                hostileFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(zombie);
        require(context, CombatCore.hasLineOfSight(bot, zombie),
                "final-use sword hostile fixture lacked factual line of sight");

        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof EvadeTask,
                "raw-1 sword entered "
                        + (active == null ? "idle" : active.name()) + " instead of Evade");
        require(context, work.state() == TaskState.PAUSED,
                "raw-1 sword Evade did not preserve interrupted work");
        zombie.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_final_use_axe_is_not_a_qualified_melee_weapon", maxTicks = 20)
    public void finalUseAxeIsNotAQualifiedMeleeWeapon(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "FinalUseAxeNoCombatGT", 2);
        ItemStack finalUseAxe = new ItemStack(Items.STONE_AXE);
        finalUseAxe.setDamageValue(finalUseAxe.getMaxDamage() - 1);
        InventoryAction.giveItem(bot, finalUseAxe);

        require(context, EquipAction.bestWeaponSlot(bot).isEmpty(),
                "raw-1 axe was admitted as a defensive melee weapon");
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_ranged_line_of_sight_blocks_combat_heal_beyond_melee_boundary", maxTicks = 30)
    public void rangedLineOfSightBlocksCombatHealBeyondMeleeBoundary(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatRangedHealGT", 2);
        int deathBaseline = deathCount(bot);
        BlockPos origin = bot.blockPosition().immutable();
        bot.setHealth(8.0F);
        bot.getFoodData().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 2));

        // Stay inside the owned 8x8 footprint. This diagonal/elevated pose is 6.93 blocks away,
        // exercising the reported seven-block boundary without leaking into a neighbour fixture.
        BlockPos skeletonFeet = origin.offset(4, 4, 4);
        context.getLevel().setBlock(
                skeletonFeet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        Skeleton skeleton = EntityType.SKELETON.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (skeleton == null) {
            despawnAndComplete(context, bot);
            context.fail(Component.nullToEmpty("failed to create ranged-heal skeleton fixture"));
            return;
        }
        skeleton.setPersistenceRequired();
        skeleton.setNoAi(true);
        skeleton.snapTo(
                skeletonFeet.getX() + 0.5D, skeletonFeet.getY(),
                skeletonFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(skeleton);
        double skeletonDistance = bot.position().distanceTo(skeleton.position());
        require(context, skeletonDistance > 6.8D && skeletonDistance < 7.2D
                        && CombatCore.hasLineOfSight(bot, skeleton),
                "ranged-heal fixture was not a seven-block LOS threat: distance="
                        + skeletonDistance + " los=" + CombatCore.hasLineOfSight(bot, skeleton));

        CombatTask combat = CombatTask.defensive(skeleton, 10.0F, origin);
        TaskManager.INSTANCE.assign(bot, combat,
                TaskOrigin.safety("gametest_ranged_los_blocks_heal"));
        combat.tick(bot);
        combat.tick(bot);

        require(context, combat.state() == TaskState.RUNNING
                        && combat.describe().contains("phase=RETREAT"),
                "ranged LOS entered HEAL beyond the melee boundary: " + combat.describe());
        require(context, !bot.isUsingItem()
                        && InventoryAction.countItem(bot, Items.COOKED_BEEF) == 2,
                "ranged LOS allowed food use before reaching safety");
        require(context, deathCount(bot) == deathBaseline,
                "ranged-heal transition changed the bot death counter");

        BlockPos occluder = origin.offset(2, 3, 2);
        context.getLevel().setBlock(
                occluder, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        require(context, !CombatCore.hasLineOfSight(bot, skeleton),
                "ranged-heal occluder did not physically break LOS");
        combat.tick(bot);
        combat.tick(bot);

        require(context, combat.describe().contains("phase=HEAL") && bot.isUsingItem(),
                "breaking ranged LOS did not release the combat heal boundary: "
                        + combat.describe());
        skeleton.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_night_creeper_with_shelter_materials_chooses_dedicated_defense", maxTicks = 80)
    public void nightCreeperWithShelterMaterialsChoosesDedicatedDefense(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnEscapeCorridor(context, "NightCreeperDefenseGT", 36);
        context.getLevel().setDayTime(18000L);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 32));
        int blocksBefore = InventoryAction.countItem(bot, Items.COBBLESTONE);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_night_creeper_work"));
        Creeper creeper = spawnDisabledCreeper(
                context, bot.blockPosition().east(8), "night Creeper routing fixture");

        require(context, ObservableWorldQuery.canObserveEntity(bot, creeper)
                        && CombatCore.hasLineOfSight(bot, creeper),
                "night Creeper was not factually observable");
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof CreeperDefenseTask,
                "night Creeper with shelter material selected "
                        + (active == null ? "idle" : active.name()));
        require(context, work.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "night Creeper did not preserve exactly one mission frame");
        require(context, !bot.getActionPack().isPathExecutorIdle(),
                "night Creeper defense did not admit a real surface path");
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == blocksBefore,
                "night Creeper routing consumed shelter material");
        creeper.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_low_health_creeper_cannot_enter_emergency_entomb", maxTicks = 80)
    public void lowHealthCreeperCannotEnterEmergencyEntomb(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnEscapeCorridor(context, "LowCreeperDefenseGT", 52);
        context.getLevel().setDayTime(18000L);
        bot.setHealth(4.7F);
        bot.getFoodData().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 32));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 2));
        int blocksBefore = InventoryAction.countItem(bot, Items.COBBLESTONE);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_low_creeper_work"));
        Creeper creeper = spawnDisabledCreeper(
                context, bot.blockPosition().east(6), "low-health Creeper routing fixture");

        require(context, ObservableWorldQuery.canObserveEntity(bot, creeper)
                        && CombatCore.hasLineOfSight(bot, creeper),
                "low-health Creeper was not factually observable");
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof CreeperDefenseTask,
                "low-health Creeper entered " + (active == null ? "idle" : active.name()));
        require(context, work.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "low-health Creeper did not preserve exactly one mission frame");
        require(context, !bot.getActionPack().isPathExecutorIdle(),
                "low-health Creeper defense did not admit a real surface path");
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == blocksBefore,
                "low-health Creeper routing consumed shelter material");
        creeper.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_observable_creeper_at_fifteen_blocks_triggers_dedicated_defense", maxTicks = 80)
    public void observableCreeperAtFifteenBlocksTriggersDedicatedDefense(
            GameTestHelper context) {
        AIPlayerEntity bot = spawnOnEscapeCorridor(
                context, "CreeperFifteenDefenseGT", 60);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_creeper_fifteen_work"));
        Creeper creeper = spawnDisabledCreeper(
                context, bot.blockPosition().east(15), "fifteen-block Creeper fixture");

        require(context, ObservableWorldQuery.canObserveEntity(bot, creeper)
                        && CombatCore.hasLineOfSight(bot, creeper),
                "fifteen-block Creeper was not factually observable");
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof CreeperDefenseTask,
                "fifteen-block Creeper left mission active as "
                        + (active == null ? "idle" : active.name()));
        require(context, work.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "fifteen-block Creeper did not preserve one mission frame");
        require(context, !bot.getActionPack().isPathExecutorIdle(),
                "fifteen-block Creeper did not admit a real escape path");
        creeper.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_completed_creeper_defense_reacquires_without_mission_stack_gap", maxTicks = 160)
    public void completedCreeperDefenseReacquiresWithoutMissionStackGap(
            GameTestHelper context) {
        AIPlayerEntity bot = spawnOnEscapeCorridor(context, "CreeperReacquireGT", 108);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_creeper_reacquire_work"));
        Creeper first = spawnDisabledCreeper(
                context, bot.blockPosition().east(8), "initial Creeper cooldown fixture");
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        Task firstSafety = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, firstSafety instanceof CreeperDefenseTask,
                "initial Creeper did not schedule dedicated defense");
        BlockPos firstGoal = bot.getActionPack().activePathGoal();
        require(context, firstGoal != null,
                "initial Creeper defense had no admitted goal");

        first.discard();
        bot.teleportTo(context.getLevel(),
                firstGoal.getX() + 0.5D, firstGoal.getY(), firstGoal.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot(), true);
        bot.setDeltaMovement(Vec3.ZERO);
        for (int tick = 0; tick < 99; tick++) {
            firstSafety.tick(bot);
        }
        require(context, firstSafety.state() == TaskState.RUNNING
                        && work.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "Creeper defense released its mission before the 100-tick LOS grace");
        firstSafety.tick(bot);
        require(context, firstSafety.state() == TaskState.COMPLETED,
                "settled Creeper defense did not complete after factual grace: "
                        + firstSafety.state() + ":" + firstSafety.failureReason());
        TaskManager.INSTANCE.tickAll(context.getLevel().getServer());
        require(context, TaskManager.INSTANCE.getActive(bot).isEmpty()
                        && work.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "completed Creeper defense did not leave exactly one paused mission frame");

        Creeper reappeared = spawnDisabledCreeper(
                context, bot.blockPosition().east(15), "reappearing Creeper cooldown fixture");
        require(context, ObservableWorldQuery.canObserveEntity(bot, reappeared),
                "reappearing fifteen-block Creeper was not observable");
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        require(context, TaskManager.INSTANCE.getActive(bot)
                        .orElse(null) instanceof CreeperDefenseTask,
                "completed defense retained a gap for the reappearing Creeper");
        require(context, work.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "Creeper reacquisition resumed or duplicated the mission frame");
        reappeared.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_closer_zombie_cannot_mask_observable_creeper", maxTicks = 80)
    public void closerZombieCannotMaskObservableCreeper(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnEscapeCorridor(context, "MixedCreeperDefenseGT", 76);
        context.getLevel().setDayTime(18000L);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 32));
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_mixed_creeper_work"));
        Husk husk = EntityType.HUSK.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (husk == null) {
            despawnAndComplete(context, bot);
            context.fail(Component.nullToEmpty("failed to create mixed-pressure Husk fixture"));
            return;
        }
        husk.setPersistenceRequired();
        husk.setNoAi(true);
        BlockPos huskFeet = bot.blockPosition().east(3);
        husk.snapTo(
                huskFeet.getX() + 0.5D, huskFeet.getY(), huskFeet.getZ() + 0.5D,
                90.0F, 0.0F);
        context.getLevel().addFreshEntity(husk);
        Creeper creeper = spawnDisabledCreeper(
                context, bot.blockPosition().east(15), "mixed-pressure Creeper fixture");

        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof CreeperDefenseTask,
                "closer ordinary hostile masked Creeper with "
                        + (active == null ? "idle" : active.name()));
        require(context, work.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "mixed Creeper pressure did not preserve one mission frame");
        require(context, !bot.getActionPack().isPathExecutorIdle(),
                "mixed Creeper pressure did not admit a real escape path");
        husk.discard();
        creeper.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_evade_examines_fifth_direction_within_bounded_admission", maxTicks = 80)
    public void evadeExaminesFifthDirectionWithinBoundedAdmission(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "EvadeFifthDirectionGT", 92);
        BlockPos origin = bot.blockPosition().immutable();
        var world = context.getLevel();
        // Threat is east, so -90 degrees is south and is generated fifth. Keep every earlier
        // twelve-block endpoint unsupported while exposing one ordinary south corridor.
        for (int dz = 5; dz <= 16; dz++) {
            BlockPos cell = origin.south(dz);
            world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        Creeper creeper = spawnDisabledCreeper(
                context, origin.east(8), "fifth-direction Creeper fixture");
        EvadeTask evade = new EvadeTask(new Threat(
                Threat.Type.HOSTILE, Threat.Severity.HIGH, creeper, creeper.blockPosition()));
        evade.start(bot);

        BlockPos admitted = bot.getActionPack().activePathGoal();
        require(context, evade.state() == TaskState.RUNNING && admitted != null,
                "fifth escape direction was starved by earlier rejected endpoints: "
                        + evade.state() + ":" + evade.failureReason());
        require(context, admitted.getZ() > origin.getZ() + 4
                        && Math.abs(admitted.getX() - origin.getX()) <= 4,
                "Evade admitted the wrong directional endpoint: " + admitted.toShortString());
        creeper.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_observed_creeper_defense_extends_beyond_first_waypoint", maxTicks = 500)
    public void observedCreeperDefenseExtendsBeyondFirstWaypoint(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnEscapeCorridor(
                context, "CreeperExtendDefenseGT", 68);
        BlockPos origin = bot.blockPosition().immutable();
        int deathBaseline = deathCount(bot);
        Creeper creeper = spawnDisabledCreeper(
                context, origin.east(8), "moving Creeper escape fixture");
        CreeperDefenseTask defense =
                new CreeperDefenseTask(creeper, creeper.blockPosition());
        TaskManager.INSTANCE.assign(bot, defense,
                TaskOrigin.safety("gametest_creeper_defense_extension"));

        context.failIfEver(() -> {
            BlockPos trailing = bot.blockPosition().east(15);
            creeper.snapTo(
                    trailing.getX() + 0.5D, trailing.getY(), trailing.getZ() + 0.5D,
                    90.0F, 0.0F);
            if (defense.state() == TaskState.FAILED
                    || defense.state() == TaskState.CANCELLED) {
                creeper.discard();
                despawnAndComplete(context, bot);
                context.fail(Component.nullToEmpty("extended Creeper defense ended as "
                        + defense.state() + ":" + defense.failureReason()));
                return;
            }
            // The first projection is twelve blocks west and the task can settle within 2.5 blocks
            // of it. Reaching fifteen blocks proves a second path leg was admitted.
            if (bot.getX() <= origin.getX() - 15.0D) {
                require(context, defense.state() == TaskState.RUNNING,
                        "Creeper defense completed at its first moving-threat waypoint");
                require(context, ObservableWorldQuery.canObserveEntity(bot, creeper),
                        "moving Creeper left factual perception before extension proof");
                require(context, deathCount(bot) == deathBaseline,
                        "moving Creeper defense changed the bot death counter");
                creeper.discard();
                despawnAndComplete(context, bot);
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_point_blank_live_charged_creeper_during_stalled_evade_survives_and_resumes_mission", maxTicks = 340)
    public void pointBlankLiveChargedCreeperDuringStalledEvadeSurvivesAndResumesMission(
            GameTestHelper context) {
        AIPlayerEntity bot = spawnOnReactiveEscapeArena(
                context, "LiveCreeperStalledEvadeGT", 196);
        BlockPos origin = bot.blockPosition().immutable();
        bot.setHealth(20.0F);
        bot.getFoodData().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 16));
        assertStrictCapabilities(context, bot);
        int deathBaseline = deathCount(bot);

        HoldingTask mission = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, mission,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_live_creeper_mission"));
        Vec3 stallAnchor = Vec3.atBottomCenterOf(origin);
        Vec3 approachAnchor = Vec3.atBottomCenterOf(origin.east(8));
        Creeper[] creeperRef = {null};
        AtomicBoolean pointBlankPressure = new AtomicBoolean();
        AtomicBoolean explosionObserved = new AtomicBoolean();
        int[] armedTicks = {0};
        int[] maxPausedDepth = {0};

        // Commit and physically stall the same initial westbound SAFETY path on both revisions
        // before applying point-blank pressure. Damage waits until join invulnerability expires.
        context.runAtTickTime(70, () -> {
            Creeper creeper = spawnLiveTargetingCreeper(
                    context, origin.east(8), bot, "live Creeper fuse fixture");
            creeperRef[0] = creeper;
            require(context, ObservableWorldQuery.canObserveEntity(bot, creeper)
                            && CombatCore.hasLineOfSight(bot, creeper),
                    "live Creeper was not factually observable before safety routing");

            DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
            Task safety = TaskManager.INSTANCE.getActive(bot).orElse(null);
            BlockPos committedGoal = bot.getActionPack().activePathGoal();
            require(context, safety != null && safety != mission
                            && TaskManager.INSTANCE.activeOrigin(bot)
                            .map(TaskOrigin::safety).orElse(false),
                    "live Creeper did not assign a dedicated SAFETY owner");
            require(context, mission.state() == TaskState.PAUSED
                            && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                            && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                    "live Creeper did not preserve exactly one mission frame");
            require(context, committedGoal != null
                            && committedGoal.getX() < origin.getX() - 4,
                    "live Creeper safety did not commit its initial westbound path");
        });

        context.runAtTickTime(80, () -> {
            Creeper creeper = creeperRef[0];
            require(context, creeper != null && creeper.isAlive(),
                    "live Creeper disappeared before point-blank pressure");
            // Stronger-than-evidence regression: keep vanilla fuse/explosion behavior, but use
            // vanilla charged state so an unshielded full-health baseline is strictly fatal.
            BlockPos fuseFeet = origin.east();
            creeper.snapTo(
                    fuseFeet.getX() + 0.5D, fuseFeet.getY(), fuseFeet.getZ() + 0.5D,
                    90.0F, 0.0F);
            creeper.setTarget(bot);
            chargeCreeperWithoutLightningDamage(context, creeper);
            creeper.ignite();
            require(context, !creeper.isNoAi()
                            && creeper.getTarget() == bot
                            && creeper.isPowered()
                            && creeper.isIgnited()
                            && ObservableWorldQuery.canObserveEntity(bot, creeper)
                            && CombatCore.hasLineOfSight(bot, creeper),
                    "point-blank charged Creeper was not a live targeting pressure source");
            pointBlankPressure.set(true);
        });

        context.failIfEver(() -> {
            Creeper creeper = creeperRef[0];
            if (creeper == null) {
                if (bot.isAlive()) {
                    bot.teleportTo(context.getLevel(),
                            stallAnchor.x, stallAnchor.y, stallAnchor.z,
                            Set.of(), bot.getYRot(), bot.getXRot(), true);
                    bot.setDeltaMovement(Vec3.ZERO);
                }
                return;
            }
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (creeper.isAlive() && !pointBlankPressure.get()) {
                creeper.snapTo(
                        approachAnchor.x, approachAnchor.y, approachAnchor.z,
                        90.0F, 0.0F);
                creeper.setDeltaMovement(Vec3.ZERO);
                creeper.setTarget(bot);
            }
            if (creeper.isAlive() && bot.isAlive()
                    && (!pointBlankPressure.get() || active instanceof EvadeTask)) {
                bot.teleportTo(context.getLevel(),
                        stallAnchor.x, stallAnchor.y, stallAnchor.z,
                        Set.of(), bot.getYRot(), bot.getXRot(), true);
                bot.setDeltaMovement(Vec3.ZERO);
            }
            int depth = TaskManager.INSTANCE.pausedDepth(bot);
            maxPausedDepth[0] = Math.max(maxPausedDepth[0], depth);
            require(context, bot.isAlive() && deathCount(bot) == deathBaseline,
                    "point-blank live charged Creeper killed the bot after its Evade path stalled"
                            + " at " + bot.blockPosition().toShortString()
                            + " deaths=" + deathCount(bot));
            require(context, depth <= 1,
                    "live Creeper recovery grew the paused mission stack to " + depth);
            if (depth == 1) {
                require(context, TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                                && mission.state() == TaskState.PAUSED,
                        "live Creeper recovery replaced or resumed the mission too early");
            }

            if (active != null && active != mission) {
                require(context, TaskManager.INSTANCE.activeOrigin(bot)
                                .map(TaskOrigin::safety).orElse(false),
                        "live Creeper recovery transferred control to a non-safety task");
            }
            if (creeper.isAlive()) {
                creeper.setTarget(bot);
                if (creeper.isIgnited() || creeper.getSwellDir() > 0) {
                    armedTicks[0]++;
                }
            } else if (explosionObserved.compareAndSet(false, true)) {
                // The movement anchor ends with the factual blast, allowing the same paused
                // mission to resume after its SAFETY owner repays the pressure.
            }

            if (active == mission) {
                require(context, explosionObserved.get() && armedTicks[0] >= 20,
                        "mission resumed without observing a real Creeper fuse and explosion");
                require(context, mission.state() == TaskState.RUNNING
                                && depth == 0
                                && maxPausedDepth[0] == 1,
                        "same mission did not resume with one fully repaid safety frame");
                despawnAndComplete(context, bot);
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_creeper_is_never_hit_from_strike_or_secondary_retreat", maxTicks = 80)
    public void creeperIsNeverHitFromStrikeOrSecondaryRetreat(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatCreeperRetreatGT", 2);
        int deathBaseline = deathCount(bot);
        BlockPos origin = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));

        Creeper creeper = EntityType.CREEPER.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (creeper == null) {
            despawnAndComplete(context, bot);
            context.fail(Component.nullToEmpty("failed to create Creeper combat fixture"));
            return;
        }
        creeper.setPersistenceRequired();
        creeper.setNoAi(true);
        BlockPos creeperFeet = origin.east();
        creeper.snapTo(
                creeperFeet.getX() + 0.5D, creeperFeet.getY(),
                creeperFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(creeper);
        float creeperHealth = creeper.getHealth();

        // Exercise the normal STRIKE entry independently of DangerWatcher routing.
        CombatTask strikeProbe = CombatTask.defensive(creeper, 6.0F, origin);
        strikeProbe.start(bot);
        strikeProbe.tick(bot);
        strikeProbe.tick(bot);
        strikeProbe.tick(bot);
        require(context, creeper.getHealth() == creeperHealth
                        && strikeProbe.describe().contains("phase=RETREAT"),
                "STRIKE dealt melee damage to a Creeper: " + strikeProbe.describe());
        bot.getActionPack().stopAll();

        for (BlockPos wall : new BlockPos[]{origin.west(), origin.north(), origin.south()}) {
            context.getLevel().setBlock(
                    wall, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(
                    wall.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                context.getLevel().setBlock(
                        origin.offset(dx, 2, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        bot.setHealth(8.0F);
        bot.getFoodData().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 2));

        BlockPos primaryFeet = origin.offset(4, 4, 0);
        context.getLevel().setBlock(
                primaryFeet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        // A regular Zombie can burn under the shared GameTest world's daytime and make this
        // no-melee assertion fail without any bot attack. Husk preserves the same close hostile
        // pressure contract while keeping health changes attributable to CombatTask alone.
        Husk primary = EntityType.HUSK.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (primary == null) {
            creeper.discard();
            despawnAndComplete(context, bot);
            context.fail(Component.nullToEmpty("failed to create Creeper secondary primary fixture"));
            return;
        }
        primary.setPersistenceRequired();
        primary.setNoAi(true);
        primary.snapTo(
                primaryFeet.getX() + 0.5D, primaryFeet.getY(),
                primaryFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(primary);
        float primaryHealth = primary.getHealth();

        CombatTask combat = CombatTask.defensive(primary, 10.0F, origin);
        TaskManager.INSTANCE.assign(bot, combat,
                TaskOrigin.safety("gametest_secondary_creeper_no_counterattack"));
        AtomicBoolean dedicatedOwnerObserved = new AtomicBoolean();
        context.failIfEver(() -> {
            require(context, creeper.isAlive() && creeper.getHealth() == creeperHealth,
                    "combat or dedicated defense attacked the secondary Creeper");
            require(context, primary.isAlive() && primary.getHealth() == primaryHealth,
                    "secondary Creeper pressure redirected damage to the primary");
            require(context, Double.compare(combat.progress(), 0.0D) == 0,
                    "secondary Creeper pressure advanced primary kill credit");
            require(context, !bot.isUsingItem(),
                    "combat healed before reaching the Creeper eight-block/LOS boundary");
            require(context, bot.isAlive() && deathCount(bot) == deathBaseline,
                    "secondary Creeper regression changed the bot death counter");
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (active instanceof CreeperDefenseTask) {
                dedicatedOwnerObserved.set(true);
                require(context, combat.state() == TaskState.FAILED
                                && "aborted".equals(combat.failureReason())
                                && TaskManager.INSTANCE.activeOrigin(bot)
                                .map(TaskOrigin::safety).orElse(false)
                                && TaskManager.INSTANCE.pausedDepth(bot) == 0,
                        "SAFETY Combat was not replaced in place by dedicated defense");
            } else {
                require(context, active == combat && !dedicatedOwnerObserved.get(),
                        "secondary Creeper transferred to unexpected owner "
                                + (active == null ? "idle" : active.name()));
            }
            if (context.getTick() >= 40) {
                require(context, dedicatedOwnerObserved.get(),
                        "secondary Creeper never established dedicated defense");
                primary.discard();
                creeper.discard();
                despawnAndComplete(context, bot);
            } else if (!dedicatedOwnerObserved.get()
                    && (combat.state() == TaskState.FAILED
                    || combat.state() == TaskState.CANCELLED)) {
                context.fail(Component.nullToEmpty("secondary-Creeper combat ended as "
                        + combat.state() + ":" + combat.failureReason()));
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_primary_death_during_heal_is_credited_exactly_once", maxTicks = 20)
    public void primaryDeathDuringHealIsCreditedExactlyOnce(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatHealPrimaryDeathGT", 2);
        int deathBaseline = deathCount(bot);
        BlockPos origin = bot.blockPosition().immutable();
        context.getLevel().setDayTime(18000L);
        bot.setHealth(8.0F);
        bot.getFoodData().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 2));

        BlockPos primaryFeet = origin.offset(4, 4, 0);
        context.getLevel().setBlock(primaryFeet.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        Zombie primary = EntityType.ZOMBIE.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (primary == null) {
            despawnAndComplete(context, bot);
            context.fail(Component.nullToEmpty("failed to create heal-primary fixture"));
            return;
        }
        primary.setPersistenceRequired();
        primary.setNoAi(true);
        primary.snapTo(primaryFeet.getX() + 0.5D, primaryFeet.getY(),
                primaryFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(primary);

        CombatTask combat = CombatTask.defensive(primary, 10.0F, origin);
        TaskManager.INSTANCE.assign(bot, combat,
                TaskOrigin.safety("gametest_primary_death_during_heal"));
        combat.tick(bot);
        require(context, combat.describe().contains("phase=HEAL"),
                "safe-distance primary did not put low-health combat into HEAL: "
                        + combat.describe());

        primary.discard();
        combat.tick(bot);

        require(context, combat.state() == TaskState.COMPLETED,
                "primary death during HEAL ended as " + combat.state()
                        + ":" + combat.failureReason());
        require(context, Double.compare(combat.progress(), 1.0D) == 0,
                "primary death during HEAL was not credited exactly once: "
                        + combat.describe());
        require(context, deathCount(bot) == deathBaseline,
                "HEAL primary-death settlement changed the bot death counter");
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_nearest_secondary_pressure_blocks_food_without_taking_primary_credit", maxTicks = 100)
    public void nearestSecondaryPressureBlocksFoodWithoutTakingPrimaryCredit(
            GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatSecondaryPressureGT", 2);
        int deathBaseline = deathCount(bot);
        BlockPos origin = bot.blockPosition().immutable();
        context.getLevel().setDayTime(18000L);
        for (BlockPos wall : new BlockPos[]{origin.west(), origin.north(), origin.south()}) {
            context.getLevel().setBlock(
                    wall, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(
                    wall.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        bot.setHealth(8.0F);
        bot.getFoodData().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 2));

        BlockPos primaryFeet = origin.offset(4, 4, 0);
        context.getLevel().setBlock(primaryFeet.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        // GameTests share one world and other batches may change time after this test sets night.
        // Use non-burning Zombie variants so every health delta remains attributable to combat.
        Husk primary = EntityType.HUSK.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        Husk secondary = EntityType.HUSK.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (primary == null || secondary == null) {
            if (primary != null) {
                primary.discard();
            }
            if (secondary != null) {
                secondary.discard();
            }
            despawnAndComplete(context, bot);
            context.fail(Component.nullToEmpty("failed to create secondary-pressure fixtures"));
            return;
        }
        primary.setPersistenceRequired();
        primary.setNoAi(true);
        primary.snapTo(primaryFeet.getX() + 0.5D, primaryFeet.getY(),
                primaryFeet.getZ() + 0.5D, 90.0F, 0.0F);
        secondary.setPersistenceRequired();
        secondary.setNoAi(true);
        BlockPos secondaryFeet = origin.east();
        secondary.snapTo(secondaryFeet.getX() + 0.5D, secondaryFeet.getY(),
                secondaryFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(primary);
        context.getLevel().addFreshEntity(secondary);
        float primaryHealth = primary.getHealth();
        float secondaryHealth = secondary.getHealth();

        CombatTask combat = CombatTask.defensive(primary, 10.0F, origin);
        TaskManager.INSTANCE.assign(bot, combat,
                TaskOrigin.safety("gametest_secondary_pressure"));
        context.failIfEver(() -> {
            require(context, bot.isAlive() && deathCount(bot) == deathBaseline,
                    "secondary-pressure combat violated the zero-death boundary");
            if (secondary.isAlive() && bot.distanceTo(secondary) < 5.0D) {
                require(context, !bot.isUsingItem(),
                        "combat ate while the secondary hostile remained inside the heal boundary");
            }
            require(context, primary.getHealth() == primaryHealth,
                    "retreat pressure redirected primary kill ownership");
            require(context, Double.compare(combat.progress(), 0.0D) == 0,
                    "secondary damage advanced the primary kill quota");
            if (secondary.getHealth() < secondaryHealth) {
                primary.discard();
                secondary.discard();
                despawnAndComplete(context, bot);
            } else if (combat.state() == TaskState.FAILED
                    || combat.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("secondary-pressure combat ended as "
                        + combat.state() + ":" + combat.failureReason()));
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_ranged_secondary_at_fourteen_blocks_blocks_primary_settlement_until_los_breaks", maxTicks = 40)
    public void rangedSecondaryAtFourteenBlocksBlocksPrimarySettlementUntilLosBreaks(
            GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatRangedSecondaryGT", 2);
        BlockPos origin = bot.blockPosition().immutable();
        bot.setHealth(8.0F);
        bot.getFoodData().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 2));
        for (int dx = 1; dx <= 14; dx++) {
            BlockPos corridor = origin.east(dx);
            context.getLevel().setBlock(
                    corridor.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(
                    corridor, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(
                    corridor.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        for (BlockPos wall : new BlockPos[]{origin.west(), origin.north(), origin.south()}) {
            context.getLevel().setBlock(
                    wall, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(
                    wall.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }

        Husk primary = EntityType.HUSK.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        Skeleton secondary = EntityType.SKELETON.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (primary == null || secondary == null) {
            if (primary != null) {
                primary.discard();
            }
            if (secondary != null) {
                secondary.discard();
            }
            despawnAndComplete(context, bot);
            context.fail(Component.nullToEmpty("failed to create ranged-secondary fixtures"));
            return;
        }
        BlockPos primaryFeet = origin.east();
        BlockPos secondaryFeet = origin.east(14);
        primary.setPersistenceRequired();
        primary.setNoAi(true);
        primary.snapTo(primaryFeet.getX() + 0.5D, primaryFeet.getY(),
                primaryFeet.getZ() + 0.5D, 90.0F, 0.0F);
        secondary.setPersistenceRequired();
        secondary.setNoAi(true);
        secondary.snapTo(secondaryFeet.getX() + 0.5D, secondaryFeet.getY(),
                secondaryFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(primary);
        context.getLevel().addFreshEntity(secondary);
        require(context, CombatCore.hasLineOfSight(bot, secondary)
                        && bot.distanceTo(secondary) > 13.8D
                        && bot.distanceTo(secondary) < 14.2D,
                "ranged secondary was not an observable fourteen-block threat");

        CombatTask combat = CombatTask.defensive(primary, 10.0F, origin);
        combat.start(bot);
        combat.tick(bot);
        primary.discard();
        combat.tick(bot);

        require(context, combat.state() == TaskState.RUNNING
                        && combat.describe().contains("phase=RETREAT"),
                "ranged secondary did not retain retreat ownership: " + combat.describe());
        require(context, InventoryAction.countItem(bot, Items.COOKED_BEEF) == 2
                        && !bot.isUsingItem(),
                "combat ate while a fourteen-block ranged secondary retained LOS");

        for (int dz = -3; dz <= 3; dz++) {
            BlockPos occluder = origin.east(7).south(dz);
            context.getLevel().setBlock(
                    occluder, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(
                    occluder.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        combat.tick(bot);
        require(context, combat.state() == TaskState.COMPLETED,
                "breaking ranged-secondary LOS did not release settlement: "
                        + combat.state() + ":" + combat.describe());

        secondary.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_combat_reequips_backup_in_the_same_attack_boundary", maxTicks = 100)
    public void combatReequipsBackupInTheSameAttackBoundary(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatBackupWeaponGT", 2);
        int deathBaseline = deathCount(bot);
        BlockPos origin = bot.blockPosition().immutable();
        // Worst-first judges the fight target: a weapon with fewer than (hits + 2) uses left is never ADEQUATE, so against an
        // ordinary zombie the fresh stone sword is right from the first swing and the two-use sword is never drawn. The
        // fixture therefore fights an iron-armoured zombie that no sword here kills in five hits: nothing is adequate, the
        // rule falls back to the best-damage weapon (the two-use stone sword), and it is that weapon which becomes
        // ineligible mid-fight and must be replaced by the fresh wooden backup in the same attack boundary.
        ItemStack twoUseStoneSword = new ItemStack(Items.STONE_SWORD);
        twoUseStoneSword.setDamageValue(twoUseStoneSword.getMaxDamage() - 2);
        InventoryAction.giveItem(bot, twoUseStoneSword);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        require(context, bot.getMainHandItem().is(Items.STONE_SWORD)
                        && rawDurability(bot.getMainHandItem()) == 2,
                "fixture did not hold the stronger two-use weapon");
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                context.getLevel().setBlock(origin.offset(dx, 2, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }

        Zombie zombie = EntityType.ZOMBIE.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (zombie == null) {
            despawnAndComplete(context, bot);
            context.fail(Component.nullToEmpty("failed to create backup-weapon zombie fixture"));
            return;
        }
        zombie.setPersistenceRequired();
        zombie.setNoAi(true);
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        zombie.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        zombie.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        zombie.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        BlockPos hostileFeet = origin.east();
        zombie.snapTo(hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                hostileFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(zombie);
        float initialHealth = zombie.getHealth();

        CombatTask combat = CombatTask.defensive(zombie, 6.0F, origin);
        TaskManager.INSTANCE.assign(bot, combat,
                TaskOrigin.safety("gametest_combat_backup_weapon"));
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "bot died in the disabled-zombie weapon fixture");
            require(context, deathCount(bot) == deathBaseline,
                    "backup-weapon combat changed the bot death counter");
            ItemStack retiredStoneSword = bot.getInventory().getNonEquipmentItems().stream()
                    .filter(stack -> stack.is(Items.STONE_SWORD))
                    .findFirst()
                    .orElse(ItemStack.EMPTY);
            if (!retiredStoneSword.isEmpty()
                    && rawDurability(retiredStoneSword) == 1) {
                require(context, zombie.getHealth() < initialHealth,
                        "two-use weapon lost durability before this combat damaged its target"
                                + " health=" + zombie.getHealth()
                                + " initial=" + initialHealth);
                ItemStack held = bot.getMainHandItem();
                require(context, held.is(Items.WOODEN_SWORD)
                                && rawDurability(held) > 1,
                        "newly ineligible weapon was not atomically replaced by its backup"
                                + " held=" + held.getItem()
                                + " raw=" + rawDurability(held)
                                + " selected=" + bot.getInventory().getSelectedSlot()
                                + " wood_count="
                                + InventoryAction.countItem(bot, Items.WOODEN_SWORD));
                zombie.discard();
                despawnAndComplete(context, bot);
            } else if (combat.state() == TaskState.FAILED
                    || combat.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("backup-weapon combat ended as "
                        + combat.state() + ":" + combat.failureReason()));
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_contact_hostile_blocks_healing_and_forces_counterattack", maxTicks = 100)
    public void contactHostileBlocksHealingAndForcesCounterattack(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatContactHealGT", 2);
        int deathBaseline = deathCount(bot);
        BlockPos origin = bot.blockPosition().immutable();
        for (BlockPos wall : new BlockPos[]{origin.west(), origin.north(), origin.south()}) {
            context.getLevel().setBlock(
                    wall, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(
                    wall.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                context.getLevel().setBlock(origin.offset(dx, 2, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        bot.setHealth(8.0F);
        bot.getFoodData().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 2));

        Zombie zombie = EntityType.ZOMBIE.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (zombie == null) {
            despawnAndComplete(context, bot);
            context.fail(Component.nullToEmpty("failed to create contact-heal zombie fixture"));
            return;
        }
        zombie.setPersistenceRequired();
        zombie.setNoAi(true);
        BlockPos hostileFeet = origin.east();
        zombie.snapTo(hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                hostileFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(zombie);
        float initialHealth = zombie.getHealth();
        AtomicBoolean counterattacked = new AtomicBoolean();

        CombatTask combat = CombatTask.defensive(zombie, 10.0F, origin);
        TaskManager.INSTANCE.assign(bot, combat,
                TaskOrigin.safety("gametest_contact_hostile_blocks_heal"));
        combat.tick(bot);
        require(context, combat.describe().contains("phase=RETREAT"),
                "low-health acquire spent its first tick approaching the contact hostile: "
                        + combat.describe());
        BlockPos retreatGoal = bot.getActionPack().activePathGoal();
        require(context, retreatGoal != null
                        && retreatGoal.distSqr(hostileFeet)
                        > origin.distSqr(hostileFeet),
                "low-health acquire did not admit a goal away from the contact hostile: "
                        + (retreatGoal == null ? "no goal" : retreatGoal.toShortString()));
        context.failIfEver(() -> {
            if (zombie.isAlive()) {
                require(context, !bot.isUsingItem(),
                        "combat began eating while a live hostile remained in contact range");
                require(context, bot.getMainHandItem().is(Items.WOODEN_SWORD),
                        "combat replaced its melee weapon with food at contact range");
            }
            if (zombie.getHealth() < initialHealth) {
                counterattacked.set(true);
            }
            require(context, bot.isAlive(), "bot died against the disabled contact hostile");
            require(context, deathCount(bot) == deathBaseline,
                    "contact-heal combat changed the bot death counter");
            if (!zombie.isAlive() || context.getTick() >= 60) {
                require(context, counterattacked.get(),
                        "blocked retreat never counterattacked the contact hostile");
                zombie.discard();
                despawnAndComplete(context, bot);
            } else if (combat.state() == TaskState.FAILED
                    || combat.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("contact-heal combat ended as "
                        + combat.state() + ":" + combat.failureReason()));
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_leash_exit_cannot_complete_while_a_hostile_remains_in_contact", maxTicks = 30)
    public void leashExitCannotCompleteWhileAHostileRemainsInContact(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatLeashContactGT", 2);
        int deathBaseline = deathCount(bot);
        BlockPos origin = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));

        Zombie zombie = EntityType.ZOMBIE.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (zombie == null) {
            despawnAndComplete(context, bot);
            context.fail(Component.nullToEmpty("failed to create leash-contact zombie fixture"));
            return;
        }
        zombie.setPersistenceRequired();
        zombie.setNoAi(true);
        BlockPos hostileFeet = origin.east();
        zombie.snapTo(hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                hostileFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(zombie);

        // The task's work-site anchor is deliberately outside its defensive leash while the live
        // hostile is still touching the bot. A leash check may end pursuit only after safety; it
        // must not complete here and expose the paused mining task to a free zombie hit.
        CombatTask combat = CombatTask.defensive(zombie, 6.0F, origin.west(9));
        TaskManager.INSTANCE.assign(bot, combat,
                TaskOrigin.safety("gametest_leash_contact_retreat"));
        combat.tick(bot);

        require(context, combat.state() == TaskState.RUNNING
                        && combat.describe().contains("phase=RETREAT"),
                "leash exit completed while a hostile remained in contact: "
                        + combat.state() + ":" + combat.describe());
        require(context, !bot.isUsingItem(),
                "leash-contact retreat began eating beside the hostile");
        require(context, deathCount(bot) == deathBaseline,
                "leash-contact retreat changed the bot death counter");
        zombie.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(maxTicks = 160)
    public void lowHealthAloneDoesNotReplaceCurrentWorkWithEvade(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "LowHealthNoThreatGT", 2);
        bot.setHealth(4.7F);
        bot.getFoodData().setFoodLevel(17);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_low_health_no_threat"));

        BlockPos origin = bot.blockPosition().immutable();
        context.failIfEver(() -> {
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            require(context, active == work,
                    "low HP without a reachable threat replaced work with "
                            + (active == null ? "idle" : active.name()));
            require(context, !TaskManager.INSTANCE.hasPaused(bot),
                    "low HP without a threat unnecessarily paused current work");
            require(context, bot.blockPosition().getY() == origin.getY(),
                    "low HP without a threat changed vertical layer: "
                            + origin.toShortString() + " -> " + bot.blockPosition().toShortString());
            if (context.getTick() >= 120) {
                despawnAndComplete(context, bot);
            }
        });
    }

    // This fixture opens a fourteen-block hostile corridor, wider than GameTest's default
    // structure spacing. Keep it in an isolated batch so neighbouring mobs/walls cannot change
    // the admission fact between the two synchronous scans.
    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_observed_hostile_inside_threat_cooldown_blocks_new_naked_healing_eat", maxTicks = 40)
    public void observedHostileInsideThreatCooldownBlocksNewNakedHealingEat(
            GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "NakedEatAdmissionGT", 2);
        BlockPos origin = bot.blockPosition().immutable();
        bot.setHealth(17.5F);
        bot.getFoodData().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        HoldingTask initialWork = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, initialWork,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_naked_eat_cooldown_seed"));

        Skeleton skeleton = EntityType.SKELETON.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (skeleton == null) {
            despawnAndComplete(context, bot);
            context.fail(Component.nullToEmpty("failed to create remote naked-eat skeleton fixture"));
            return;
        }
        BlockPos hostileFeet = origin.east(7);
        BlockPos rangedFeet = origin.east(14);
        for (int dx = 1; dx <= 14; dx++) {
            BlockPos corridor = origin.east(dx);
            context.getLevel().setBlock(corridor.below(),
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(corridor,
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(corridor.above(),
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        skeleton.setPersistenceRequired();
        skeleton.setNoAi(true);
        skeleton.snapTo(hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                hostileFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(skeleton);
        require(context, CombatCore.hasLineOfSight(bot, skeleton),
                "remote naked-eat skeleton was not initially reachable");

        // Seed the ordinary threat retry cooldown with a real defensive assignment, then replace
        // the cancelled transaction with fresh resumable work while the same hostile remains.
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) instanceof CombatTask,
                "fixture did not seed the threat cooldown through defensive combat");
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_naked_eat_cooldown_seeded");
        skeleton.snapTo(rangedFeet.getX() + 0.5D, rangedFeet.getY(),
                rangedFeet.getZ() + 0.5D, 90.0F, 0.0F);
        double rangedDistance = bot.position().distanceTo(skeleton.position());
        require(context, rangedDistance > 13.8D && rangedDistance < 14.2D
                        && CombatCore.hasLineOfSight(bot, skeleton),
                "naked-eat ranged fixture was not a fourteen-block LOS threat: distance="
                        + rangedDistance);

        bot.setHealth(4.7F);
        bot.getFoodData().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.MUTTON, 2));
        HoldingTask recoveryWork = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, recoveryWork,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_naked_eat_admission"));

        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active == recoveryWork,
                "observed ranged hostile inside threat cooldown admitted naked "
                        + (active == null ? "idle" : active.name()));
        require(context, !TaskManager.INSTANCE.hasPaused(bot),
                "blocked naked EatTask grew a safety pause frame");
        require(context, InventoryAction.countItem(bot, Items.MUTTON) == 2,
                "blocked naked EatTask consumed food");
        skeleton.discard();
        despawnAndComplete(context, bot);
    }

    // The terminal-episode decision counts every observable hostile. An isolated batch proves
    // the intended close zombie without inheriting ranged mobs from adjacent empty structures.
    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_terminal_shelter_episode_uses_close_defensive_combat_until_relocation", maxTicks = 80)
    public void terminalShelterEpisodeUsesCloseDefensiveCombatUntilRelocation(
            GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "ShelterEpisodeFallbackGT", 2);
        BlockPos origin = bot.blockPosition().immutable();
        for (int dx = -3; dx <= 4; dx++) {
            for (int dz = -3; dz <= 4; dz++) {
                context.getLevel().setBlock(origin.offset(dx, 2, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        bot.setHealth(4.7F);
        bot.getFoodData().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 16));
        InventoryAction.giveItem(bot, new ItemStack(Items.MUTTON, 2));
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_episode_work"));

        Zombie zombie = EntityType.ZOMBIE.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (zombie == null) {
            despawnAndComplete(context, bot);
            context.fail(Component.nullToEmpty("failed to create shelter-episode zombie fixture"));
            return;
        }
        BlockPos hostileFeet = origin.east(2);
        zombie.setPersistenceRequired();
        zombie.setNoAi(true);
        zombie.snapTo(hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                hostileFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(zombie);
        require(context, CombatCore.hasLineOfSight(bot, zombie),
                "shelter-episode fixture hostile was not reachable");

        DangerWatcher.INSTANCE.noteShelterTerminal(
                bot, origin, TaskState.FAILED, "gametest_shelter_terminal");
        require(context, DangerWatcher.INSTANCE.shelterEpisodeActive(bot),
                "terminal shelter did not latch its local hostile episode");

        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof CombatTask,
                "locked shelter site did not choose close defensive combat: "
                        + (active == null ? "idle" : active.name()));
        require(context, work.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "episode fallback did not preserve exactly one work frame");
        CombatTask combat = (CombatTask) active;
        combat.tick(bot);
        require(context, combat.describe().contains("phase=RETREAT"),
                "low-HP episode fallback did not start in RETREAT: " + combat.describe());
        require(context, DangerWatcher.INSTANCE.shelterEpisodeActive(bot),
                "continuing same-site hostile prematurely reset the shelter episode");

        BlockPos relocated = origin.south(5);
        context.getLevel().setBlock(relocated.below(),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(relocated, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(relocated.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(relocated.above(2),
                Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        bot.teleportTo(context.getLevel(), relocated.getX() + 0.5D, relocated.getY(),
                relocated.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        require(context, !DangerWatcher.INSTANCE.shelterEpisodeActive(bot),
                "significant relocation did not reset the terminal shelter episode");

        zombie.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(maxTicks = 40)
    public void lowHealthWithFoodPausesWorkToEatForHealing(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "LowHealthHealGT", 2);
        bot.setHealth(4.7F);
        bot.getFoodData().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.MUTTON, 2));
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_low_health_heal"));

        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof EatTask,
                "low HP with usable food did not schedule healing eat: "
                        + (active == null ? "idle" : active.name()));
        require(context, TaskManager.INSTANCE.hasPaused(bot) && work.state() == TaskState.PAUSED,
                "healing eat did not preserve the interrupted work cursor");
        despawnAndComplete(context, bot);
    }

    @GameTest(maxTicks = 200)
    public void hostileLowHealthCannotInterruptAtomicHealingEat(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "LowHealthAtomicEatGT", 2);
        BlockPos origin = bot.blockPosition().immutable();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                context.getLevel().setBlock(origin.offset(dx, 2, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        bot.setHealth(4.7F);
        bot.getFoodData().setFoodLevel(17);
        InventoryAction.giveItem(bot, new ItemStack(Items.MUTTON, 2));
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_low_health_atomic_eat"));

        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        Task scheduled = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, scheduled instanceof EatTask,
                "fixture did not schedule the healing EatTask: "
                        + (scheduled == null ? "idle" : scheduled.name()));
        EatTask eat = (EatTask) scheduled;
        int pausedDepth = TaskManager.INSTANCE.pausedDepth(bot);
        require(context, pausedDepth == 1 && work.state() == TaskState.PAUSED,
                "fixture did not preserve exactly one interrupted work frame");

        Skeleton skeleton = EntityType.SKELETON.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (skeleton == null) {
            despawnAndComplete(context, bot);
            context.fail(Component.nullToEmpty("failed to create low-health skeleton fixture"));
            return;
        }
        BlockPos hostileFeet = origin.east(2);
        skeleton.setPersistenceRequired();
        skeleton.setNoAi(true);
        skeleton.snapTo(hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                hostileFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(skeleton);
        // The physical two-block roof is the fact this regression needs. canSeeSky() depends
        // on a lazily refreshed heightmap and can briefly report the pre-fixture value when the
        // default GameTest batch prepares many neighbouring structures in the same server tick.
        require(context, context.getLevel().getBlockState(origin.above(2)).is(Blocks.STONE),
                "atomic-eat fixture did not retain its physical cave roof");
        require(context, bot.hasLineOfSight(skeleton) && CombatCore.hasLineOfSight(bot, skeleton),
                "atomic-eat skeleton was not an observable hostile");

        EvadeTask impossibleEscape = new EvadeTask(new Threat(
                Threat.Type.LOW_HP, Threat.Severity.HIGH, skeleton, hostileFeet));
        impossibleEscape.start(bot);
        impossibleEscape.tick(bot);
        require(context, impossibleEscape.state() == TaskState.FAILED
                        && "no_valid_escape_route".equals(impossibleEscape.failureReason()),
                "fixture unexpectedly exposed an escape route: " + impossibleEscape.describe());

        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == eat,
                "LOW_HP skeleton replaced the active healing EatTask");
        require(context, TaskManager.INSTANCE.pausedDepth(bot) == pausedDepth,
                "LOW_HP skeleton grew the pause stack before eating began");

        context.failIfEver(() -> {
            int remainingMutton = InventoryAction.countItem(bot, Items.MUTTON);
            if (remainingMutton < 2 && bot.getFoodData().getFoodLevel() > 17) {
                require(context, TaskManager.INSTANCE.pausedDepth(bot) == pausedDepth,
                        "pause stack grew while the physical bite was settling");
                skeleton.discard();
                despawnAndComplete(context, bot);
                return;
            }
            require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == eat,
                    "healing EatTask lost ownership before consuming food");
            require(context, eat.state() == TaskState.RUNNING,
                    "healing EatTask became terminal before consuming food: "
                            + eat.state() + ":" + eat.failureReason());
            require(context, TaskManager.INSTANCE.pausedDepth(bot) == pausedDepth,
                    "hostile scan nested another safety frame above healing EatTask");
            DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        });
    }

    @GameTest(maxTicks = 40)
    public void entitylessLowHealthThreatCannotInventDownwardEscape(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "LowHealthVectorGT", 25);
        BlockPos origin = bot.blockPosition().immutable();
        // Provide the exact tempting old destination: a valid dry cave floor twenty blocks below.
        BlockPos cave = origin.below(20);
        context.getLevel().setBlock(cave.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(cave, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(cave.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        EvadeTask task = new EvadeTask(new Threat(
                Threat.Type.LOW_HP, Threat.Severity.HIGH, null, origin));
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.FAILED
                        && "no_valid_escape_route".equals(task.failureReason()),
                "entity-less low HP invented an escape route: " + task.describe());
        require(context, bot.blockPosition().getY() == origin.getY()
                        && bot.getActionPack().isPathExecutorIdle(),
                "entity-less low HP began moving toward the cave below");
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_failed_surface_path_evade_releases_sprint_and_allows_paused_work_resume", maxTicks = 60)
    public void failedSurfacePathEvadeReleasesSprintAndAllowsPausedWorkResume(
            GameTestHelper context) {
        // Keep the fixture well above neighbouring templates: Evade deliberately searches about
        // twenty horizontal blocks away, beyond the empty structure's eight-block footprint.
        AIPlayerEntity bot = spawnOnPlatform(context, "EvadeAdmissionCleanupGT", 80);
        BlockPos origin = bot.blockPosition().immutable();
        var world = context.getLevel();

        // chooseGoal can prove a dry standable destination, but the sealed start makes the
        // surface-only path admission fail without granting excavation as an escape shortcut.
        for (BlockPos wall : new BlockPos[]{
                origin.north(), origin.south(), origin.east(), origin.west()}) {
            world.setBlock(wall, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(wall.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(origin.above(2), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos candidate = origin.east(20);
        world.setBlock(candidate.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(candidate, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(candidate.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_evade_admission_cleanup"));
        TaskManager.INSTANCE.pauseFor(bot, "gametest_hostile_interrupt");
        require(context, work.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "fixture did not preserve one paused mission frame");

        EvadeTask evade = new EvadeTask(new Threat(
                Threat.Type.HOSTILE, Threat.Severity.HIGH, null, origin.west()));
        TaskManager.INSTANCE.assign(bot, evade, TaskOrigin.safety("gametest_failed_evade"));
        TaskManager.INSTANCE.tickAll(world.getServer());
        require(context, evade.state() == TaskState.FAILED
                        && "no_valid_escape_route".equals(evade.failureReason()),
                "sealed surface path did not fail admission: " + evade.describe());
        require(context, TaskManager.INSTANCE.getActive(bot).isEmpty(),
                "TaskManager retained the terminal EvadeTask");
        require(context, bot.getActionPack().isPathExecutorIdle()
                        && !bot.getActionPack().hasActiveActions(),
                "failed evade retained sprint or another synthetic action");
        require(context, DangerWatcher.canResumePausedWork(bot, java.util.Optional.empty()),
                "safe post-evade state did not pass the mission resume gate");

        TaskManager.INSTANCE.resumeFromPause(bot);
        require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == work
                        && work.state() == TaskState.RUNNING
                        && !TaskManager.INSTANCE.hasPaused(bot),
                "failed evade left the original mission frame permanently paused");
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_paused_dig_down_claims_observed_lava_and_pays_exact_return", maxTicks = 120)
    public void pausedDigDownClaimsObservedLavaAndPaysExactReturn(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "DigDownLavaReturnGT", 55);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        var world = context.getLevel();
        BlockPos start = bot.blockPosition().immutable();
        BlockPos middle = start.east().below();
        BlockPos tail = middle.east().below();
        List<BlockPos> trail = List.of(start, middle, tail);
        for (BlockPos feet : trail) {
            world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        bot.teleportTo(world, tail.getX() + 0.5D, tail.getY(), tail.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 12));

        // One elevated source stays inside the watcher's +/-2 horizontal and +/-1 vertical window
        // from every factual waypoint. Three stone sides contain it; the visible south cell is reset
        // before each scan so fluid spread cannot turn this ownership proof into a contact-lava test.
        BlockPos lava = tail.north(2).above();
        world.setBlock(lava.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(lava.north(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(lava.east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(lava.west(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(lava, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(lava.south(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        Map<String, String> checkpoint = new DigDownTask.DigDownCheckpoint(
                4, "minecraft:stone", 36, DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, tail.getY(), 0, 12, 1000, 900, 0,
                0, 0, false, null, 0, trail,
                -1, 0, -20, false, 0, -1, -1L, false).encode();
        DigDownTask task = new DigDownTask(Blocks.STONE, 36, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_lava_return"));
        TaskManager.INSTANCE.pauseFor(bot, "gametest_failed_lava_evade_complete");
        DigDownTask.DigDownCheckpoint paused = DigDownTask.DigDownCheckpoint
                .decode(task.checkpoint()).orElse(null);
        require(context, paused != null
                        && paused.phase() == DigDownTask.Phase.RETURN
                        && paused.returnOutcome() == DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED
                        && paused.returnTrailIndex() == trail.size() - 1,
                "fixture did not publish the paused factual return debt: " + task.checkpoint());
        int deathsBefore = deathCount(bot);
        float healthBefore = bot.getHealth();

        // Cross the old trap-repeat boundary without advancing the task. Every scan must be
        // idempotent: same instance, same cursor/budget, no generic Evade and no new pause frame.
        for (int scan = 0; scan < 6; scan++) {
            world.setBlock(lava, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(lava.south(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            require(context, DangerWatcher.INSTANCE.scanBot(world.getServer(), bot),
                    "visible lava scan was not handled at iteration " + scan);
            DigDownTask.DigDownCheckpoint returning = DigDownTask.DigDownCheckpoint
                    .decode(task.checkpoint()).orElse(null);
            require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == task
                            && task.state() == TaskState.RUNNING
                            && TaskManager.INSTANCE.pausedDepth(bot) == 0,
                    "visible lava replaced or stranded the DigDown owner at scan " + scan);
            require(context, returning != null
                            && returning.phase() == DigDownTask.Phase.RETURN
                            && returning.returnOutcome() == DigDownTask.ReturnOutcome.WALLED
                            && returning.returnTrailIndex() == trail.size() - 1
                            && returning.returnBudgetUsed() == 0,
                    "repeated lava scan reset or changed the exact return debt: "
                            + task.checkpoint());
        }
        require(context, EpisodeMemory.INSTANCE.isExcluded(
                        bot.getUUID(), start, world.getServer().getTickCount()),
                "observed-lava entry was not excluded from same-episode replanning");

        int[] lastReturnIndex = {trail.size() - 1};
        int[] lastReturnBudget = {0};
        context.failIfEver(() -> {
            if (task.state() != TaskState.RUNNING) {
                require(context, task.state() == TaskState.FAILED
                                && "dig_down_walled collected=12".equals(task.failureReason()),
                        "lava return lost its typed terminal outcome: "
                                + task.state() + ":" + task.failureReason());
                require(context, bot.blockPosition().equals(start),
                        "lava return settled before the exact origin: "
                                + bot.blockPosition().toShortString());
                require(context, bot.getHealth() == healthBefore
                                && deathCount(bot) == deathsBefore
                                && !bot.isInLava()
                                && !bot.isOnFire()
                                && TaskManager.INSTANCE.pausedDepth(bot) == 0,
                        "exact lava return ended with damage, contact or a paused frame");
                // Once DigDown has paid its return debt, the still-visible source may legitimately
                // start a fresh generic Evade. That post-terminal safety task is outside this
                // ownership proof and despawnAndComplete clears it with the fixture.
                despawnAndComplete(context, bot);
                return;
            }

            require(context, trail.contains(bot.blockPosition()),
                    "lava return left the factual trail: " + bot.blockPosition().toShortString());
            require(context, world.getBlockState(lava).is(Blocks.LAVA),
                    "DigDown mutated the factual lava source");
            require(context, bot.getHealth() == healthBefore
                            && deathCount(bot) == deathsBefore
                            && !bot.isInLava()
                            && !bot.isOnFire(),
                    "exact lava return caused damage, death or contact");
            require(context, TaskManager.INSTANCE.pausedDepth(bot) == 0,
                    "repeated lava scan recreated a paused frame");

            DigDownTask.DigDownCheckpoint live = DigDownTask.DigDownCheckpoint
                    .decode(task.checkpoint()).orElse(null);
            require(context, live != null
                            && live.returnTrailIndex() <= lastReturnIndex[0]
                            && live.returnBudgetUsed() >= lastReturnBudget[0],
                    "lava return cursor or budget regressed: " + task.checkpoint());
            lastReturnIndex[0] = live.returnTrailIndex();
            lastReturnBudget[0] = live.returnBudgetUsed();
            world.setBlock(lava, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(lava.south(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            DangerWatcher.INSTANCE.scanBot(world.getServer(), bot);
            require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == task
                            && TaskManager.INSTANCE.pausedDepth(bot) == 0,
                    "continuous lava observation preempted the returning DigDown");
        });
    }

    @GameTest(maxTicks = 40)
    public void unprovokedEndermanDoesNotInterruptCurrentWork(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "PassiveEndermanGT", 2);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_passive_enderman"));

        EnderMan enderman = EntityType.ENDERMAN.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (enderman == null) {
            despawnAndComplete(context, bot);
            context.fail(Component.nullToEmpty("failed to create passive Enderman fixture"));
            return;
        }
        BlockPos endermanFeet = bot.blockPosition().east(4);
        enderman.setPersistenceRequired();
        enderman.snapTo(endermanFeet.getX() + 0.5D, endermanFeet.getY(),
                endermanFeet.getZ() + 0.5D, 0.0F, 0.0F);
        context.getLevel().addFreshEntity(enderman);

        require(context, !enderman.isCreepy() && enderman.getTarget() == null,
                "Enderman fixture spawned already provoked");
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active == work,
                "unprovoked Enderman replaced current work with "
                        + (active == null ? "idle" : active.name()));
        require(context, !TaskManager.INSTANCE.hasPaused(bot),
                "unprovoked Enderman paused current work");
        enderman.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_enderman_angry_at_another_entity_does_not_interrupt_current_work", maxTicks = 40)
    public void endermanAngryAtAnotherEntityDoesNotInterruptCurrentWork(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "OtherAngerEndermanGT", 116);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_enderman_other_anger"));

        var bystander = EntityType.COW.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (bystander == null) {
            despawnAndComplete(context, bot);
            context.fail(Component.nullToEmpty("failed to create Enderman anger bystander"));
            return;
        }
        bystander.setPersistenceRequired();
        BlockPos bystanderFeet = bot.blockPosition().east(4);
        bystander.snapTo(
                bystanderFeet.getX() + 0.5D, bystanderFeet.getY(),
                bystanderFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(bystander);

        EnderMan enderman = spawnDisabledEnderman(
                context, bot.blockPosition().east(2), "other-anger Enderman fixture");
        enderman.setPersistentAngerEndTime(enderman.level().getGameTime() + 600L);
        enderman.setPersistentAngerTarget(EntityReference.of(bystander.getUUID()));
        enderman.setTarget(bystander);
        require(context, enderman.isCreepy()
                        && enderman.getTarget() == bystander
                        && !enderman.isAngryAt(bot, bot.level()),
                "Enderman fixture was not angry exclusively at the bystander");
        require(context, !DangerWatcher.isActiveHostileThreat(bot, enderman),
                "anger directed at another entity was attributed to this bot");

        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == work
                        && !TaskManager.INSTANCE.hasPaused(bot),
                "other-directed Enderman anger interrupted current work");
        enderman.discard();
        bystander.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_provoked_enderman_routes_to_evade", maxTicks = 80)
    public void provokedEndermanRoutesToEvade(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnEscapeCorridor(context, "ProvokedEndermanGT", 132);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_provoked_enderman"));
        EnderMan enderman = spawnDisabledEnderman(
                context, bot.blockPosition().east(8), "provoked Enderman fixture");
        enderman.setPersistentAngerEndTime(enderman.level().getGameTime() + 600L);
        enderman.setPersistentAngerTarget(EntityReference.of(bot.getUUID()));
        enderman.setTarget(bot);
        float initialHealth = enderman.getHealth();

        require(context, DangerWatcher.isActiveHostileThreat(bot, enderman)
                        && ObservableWorldQuery.canObserveEntity(bot, enderman)
                        && CombatCore.hasLineOfSight(bot, enderman),
                "provoked Enderman was not a factual active threat");
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof EvadeTask,
                "provoked Enderman routed to "
                        + (active == null ? "idle" : active.name()));
        require(context, work.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "provoked Enderman did not preserve exactly one work frame");
        require(context, !bot.getActionPack().isPathExecutorIdle()
                        && bot.getActionPack().activePathGoal() != null,
                "provoked Enderman Evade did not admit a surface path");
        require(context, enderman.getHealth() == initialHealth,
                "provoked Enderman routing dealt combat damage");
        enderman.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_direct_combat_never_attacks_enderman", maxTicks = 80)
    public void directCombatNeverAttacksEnderman(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnEscapeCorridor(context, "CombatEndermanGuardGT", 164);
        BlockPos origin = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        EnderMan enderman = spawnDisabledEnderman(
                context, origin.east(3), "direct-combat Enderman fixture");
        enderman.setPersistentAngerEndTime(enderman.level().getGameTime() + 600L);
        enderman.setPersistentAngerTarget(EntityReference.of(bot.getUUID()));
        enderman.setTarget(bot);
        float initialHealth = enderman.getHealth();

        // Exercise CombatTask directly so the assertion survives even if a caller bypasses the
        // normal DangerWatcher -> Evade routing boundary.
        CombatTask combat = CombatTask.defensive(enderman, 6.0F, origin);
        combat.start(bot);
        combat.tick(bot);
        combat.tick(bot);
        combat.tick(bot);

        require(context, combat.state() == TaskState.RUNNING
                        && combat.describe().contains("phase=RETREAT")
                        && enderman.getHealth() == initialHealth,
                "direct CombatTask attacked an Enderman: " + combat.describe());
        require(context, !bot.getActionPack().isPathExecutorIdle(),
                "direct Enderman CombatTask did not retain escape movement");
        enderman.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = "minecraftai-gametest:danger_watcher_low_health_game_tests_combat_retreat_admits_lateral_surface_path", maxTicks = 80)
    public void combatRetreatAdmitsLateralSurfacePath(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "CombatLateralRetreatGT", 148);
        var world = context.getLevel();
        BlockPos origin = bot.blockPosition().immutable();

        // Remove every projected endpoint, then expose only a connected north corridor. The
        // hostile stands east, so a direct retreat would be west and only the shared fan can find
        // this factual lateral route.
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                BlockPos cell = origin.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        for (int dz = 0; dz >= -12; dz--) {
            for (int dx = -1; dx <= 1; dx++) {
                BlockPos cell = origin.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        bot.setHealth(8.0F);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));

        Husk husk = EntityType.HUSK.create(world, EntitySpawnReason.COMMAND);
        if (husk == null) {
            despawnAndComplete(context, bot);
            context.fail(Component.nullToEmpty("failed to create lateral-retreat Husk fixture"));
            return;
        }
        husk.setPersistenceRequired();
        husk.setNoAi(true);
        BlockPos hostileFeet = origin.east();
        husk.snapTo(
                hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                hostileFeet.getZ() + 0.5D, 90.0F, 0.0F);
        world.addFreshEntity(husk);

        CombatTask combat = CombatTask.defensive(husk, 10.0F, origin);
        TaskManager.INSTANCE.assign(bot, combat,
                TaskOrigin.safety("gametest_lateral_combat_retreat"));
        combat.tick(bot);

        BlockPos goal = bot.getActionPack().activePathGoal();
        require(context, combat.state() == TaskState.RUNNING
                        && combat.describe().contains("phase=RETREAT"),
                "low-health combat did not retain RETREAT ownership: "
                        + combat.state() + ":" + combat.failureReason());
        require(context, goal != null
                        && Math.abs(goal.getX() - origin.getX()) <= 1
                        && goal.getZ() <= origin.getZ() - 5,
                "Combat retreat did not select the lateral corridor: "
                        + (goal == null ? "no goal" : goal.toShortString()));
        require(context, !bot.getActionPack().isPathExecutorIdle()
                        && bot.getActionPack().isWalkToIdle(),
                "Combat retreat bypassed surface-path admission");
        husk.discard();
        despawnAndComplete(context, bot);
    }

    private static Map<String, String> createActiveBreakCheckpoint(BlockPos origin,
                                                                    BlockPos obsidian,
                                                                    BlockPos stand) {
        Map<String, String> values = new LinkedHashMap<>(
                ObsidianSearchCursor.initial(origin, 12).encode());
        values.put("task_schema", "2");
        values.put("target_count", "1");
        values.put("phase", CreateObsidianTask.Phase.MINE.name());
        values.put("inventory_baseline", "0");
        values.put("collected", "0");
        values.put("serviced_collected", "0");
        values.put("pending_service_boundary", "0");
        values.put("budget_used", "20");
        values.put("phase_started", "20");
        values.put("last_progress", "20");
        values.put("pickup_grace", "0");
        values.put("water_bucket_baseline", "-1");
        values.put("pending_pickup_inventory", "-1");
        values.put("pickup_gain_budget", "-1");
        values.put("active_break_inventory", "0");
        values.put("protection_prepared", "false");
        values.put("obsidian", encode(obsidian));
        values.put("stand", encode(stand));
        values.put("active_break_pos", encode(obsidian));
        Map<String, String> checkpoint = Map.copyOf(values);
        if (ObsidianCheckpoint.decode(checkpoint, 1, 24000).isEmpty()) {
            throw new IllegalStateException("invalid Create raw-one fixture: " + checkpoint);
        }
        return checkpoint;
    }

    private static Map<String, String> oreDigCheckpoint(BlockPos face,
                                                         BlockPos pendingPickup,
                                                         BlockPos activeBreak) {
        Set<Block> ores = Set.of(Blocks.IRON_ORE);
        Map<String, String> checkpoint = new OreDigCheckpoint(
                4,
                1,
                true,
                0,
                0,
                false,
                40,
                0,
                0,
                MiningCursor.initial(face, 48),
                OreDigTask.oreFingerprint(ores),
                0,
                0,
                null,
                null,
                pendingPickup,
                pendingPickup,
                pendingPickup == null ? -1 : 0,
                pendingPickup == null ? -1 : 0,
                -1,
                activeBreak,
                activeBreak == null ? -1 : 0).encode();
        if (OreDigCheckpoint.decode(checkpoint, ores).isEmpty()) {
            throw new IllegalStateException("invalid OreDig raw-one fixture: " + checkpoint);
        }
        return checkpoint;
    }

    private static int rawDurability(ItemStack stack) {
        return stack.isEmpty() || !stack.isDamageableItem()
                ? 0 : stack.getMaxDamage() - stack.getDamageValue();
    }

    private static int deathCount(AIPlayerEntity bot) {
        return bot.getStats().getValue(
                Stats.CUSTOM.get(Stats.DEATHS));
    }

    private static String encode(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static AIPlayerEntity spawnOnPlatform(GameTestHelper context, String name, int relativeY) {
        var world = context.getLevel();
        world.setDayTime(1000L);
        BlockPos feet = context.absolutePos(new BlockPos(3, relativeY, 3));
        // Own the complete 8x8 template footprint. The old z=-76..-184 offsets escaped the
        // structure and let unrelated long GameTests overwrite raw-one drops and cave roofs.
        for (int dx = -3; dx <= 4; dx++) {
            for (int dz = -3; dz <= 4; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        return bot;
    }

    private static AIPlayerEntity spawnOnEscapeCorridor(GameTestHelper context,
                                                         String name,
                                                         int relativeY) {
        var world = context.getLevel();
        world.setDayTime(1000L);
        BlockPos feet = context.absolutePos(new BlockPos(3, relativeY, 3));
        // Keep long escape fixtures vertically isolated from the ordinary 8x8 GameTest footprint.
        // This proves real path admission without overwriting neighbouring structures.
        for (int dx = -64; dx <= 12; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        return bot;
    }

    private static AIPlayerEntity spawnOnReactiveEscapeArena(GameTestHelper context,
                                                              String name,
                                                              int relativeY) {
        var world = context.getLevel();
        world.setDayTime(1000L);
        BlockPos feet = context.absolutePos(new BlockPos(3, relativeY, 3));
        for (int dx = -18; dx <= 18; dx++) {
            for (int dz = -18; dz <= 18; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(
                        cell.below(), Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        return bot;
    }

    private static Creeper spawnLiveTargetingCreeper(GameTestHelper context,
                                                            BlockPos feet,
                                                            AIPlayerEntity target,
                                                            String fixture) {
        Creeper creeper = EntityType.CREEPER.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (creeper == null) {
            context.fail(Component.nullToEmpty("failed to create " + fixture));
            throw new IllegalStateException("failed to create " + fixture);
        }
        creeper.setPersistenceRequired();
        creeper.setNoAi(false);
        creeper.snapTo(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(creeper);
        creeper.setTarget(target);
        return creeper;
    }

    private static void chargeCreeperWithoutLightningDamage(GameTestHelper context,
                                                             Creeper creeper) {
        LightningBolt lightning = EntityType.LIGHTNING_BOLT.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (lightning == null) {
            context.fail(Component.nullToEmpty("failed to create charged Creeper fixture"));
            throw new IllegalStateException("failed to create charged Creeper fixture");
        }
        creeper.thunderHit(context.getLevel(), lightning);
        creeper.clearFire();
        creeper.setHealth(creeper.getMaxHealth());
    }

    private static Creeper spawnDisabledCreeper(GameTestHelper context,
                                                       BlockPos feet,
                                                       String fixture) {
        Creeper creeper = EntityType.CREEPER.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (creeper == null) {
            context.fail(Component.nullToEmpty("failed to create " + fixture));
            throw new IllegalStateException("failed to create " + fixture);
        }
        creeper.setPersistenceRequired();
        creeper.setNoAi(true);
        creeper.snapTo(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(creeper);
        return creeper;
    }

    private static EnderMan spawnDisabledEnderman(GameTestHelper context,
                                                         BlockPos feet,
                                                         String fixture) {
        EnderMan enderman = EntityType.ENDERMAN.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (enderman == null) {
            context.fail(Component.nullToEmpty("failed to create " + fixture));
            throw new IllegalStateException("failed to create " + fixture);
        }
        enderman.setPersistenceRequired();
        enderman.setNoAi(true);
        enderman.snapTo(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(enderman);
        return enderman;
    }

    private static void despawnAndComplete(GameTestHelper context, AIPlayerEntity bot) {
        String name = bot.getGameProfile().name();
        DangerWatcher.INSTANCE.clear(bot);
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    private static void requireUnpreempted(GameTestHelper context,
                                           AIPlayerEntity bot,
                                           Task expected,
                                           String owner) {
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active == expected,
                owner + " was replaced with " + (active == null ? "idle" : active.name()));
        require(context, !TaskManager.INSTANCE.hasPaused(bot),
                owner + " was pushed behind generic resupply");
        require(context, expected.state() == TaskState.RUNNING,
                owner + " became terminal: " + expected.state());
    }

    private static void assertStrictCapabilities(GameTestHelper context, AIPlayerEntity bot) {
        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
        for (PrivilegedCapability capability : PrivilegedCapability.values()) {
            require(context, !CapabilityRuntime.decide(
                            bot, capability, "danger_watcher_live_creeper_gametest").allowed(),
                    "strict_survival unexpectedly allowed " + capability);
        }
    }

    private static final class HoldingTask extends AbstractTask {
        @Override
        public String name() {
            return "holding_work";
        }

        @Override
        public String describe() {
            return "Holding a resumable work cursor";
        }

        @Override
        public double progress() {
            return 0.5D;
        }

        @Override
        protected void onStart(AIPlayerEntity bot) {
        }

        @Override
        protected void onTick(AIPlayerEntity bot) {
        }
    }
}
