package io.github.zoyluo.minecraftai.task;

import com.google.gson.JsonObject;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.EquipAction;
import io.github.zoyluo.minecraftai.action.InteractAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.action.StrikeLegality;
import io.github.zoyluo.minecraftai.brain.ToolDefinition;
import io.github.zoyluo.minecraftai.brain.ToolRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Live proofs of the combat hardening pass: DPS weapon choice, the shield loop, the never-melee
 * threat table, strike legality (reach and collider line of sight), friendly-fire exclusion, the
 * creeper shield fallback and the shoot-from-where-it-stands ranged option.
 *
 * <p>Every fixture uses real survival inventory, entities and tasks; each test owns its own arena
 * height so a leftover wall from one test can never leak into another.
 */
public final class CombatHardeningGameTests {
    private static final String ENV = "minecraftai-gametest:combat_hardening_game_tests_";

    @GameTest(environment = ENV + "weapon_choice_picks_sword_over_same_tier_axe", maxTicks = 40)
    public void weaponChoicePicksSwordOverSameTierAxe(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "DpsSwordGT", 2);
        record Tier(String name, Item sword, Item axe) {
        }
        List<Tier> tiers = List.of(
                new Tier("wooden", Items.WOODEN_SWORD, Items.WOODEN_AXE),
                new Tier("stone", Items.STONE_SWORD, Items.STONE_AXE),
                new Tier("copper", Items.COPPER_SWORD, Items.COPPER_AXE),
                new Tier("iron", Items.IRON_SWORD, Items.IRON_AXE),
                new Tier("diamond", Items.DIAMOND_SWORD, Items.DIAMOND_AXE),
                new Tier("netherite", Items.NETHERITE_SWORD, Items.NETHERITE_AXE));
        for (Tier tier : tiers) {
            bot.getInventory().clearContent();
            // The axe goes in first so it is genuinely the held weapon before selection.
            InventoryAction.giveItem(bot, new ItemStack(tier.axe()));
            InventoryAction.giveItem(bot, new ItemStack(tier.sword()));
            require(context, EquipAction.attackDamage(new ItemStack(tier.axe()))
                            >= EquipAction.attackDamage(new ItemStack(tier.sword())),
                    tier.name() + " fixture: the axe must out-damage the sword per hit for this to prove DPS");
            CombatCore.equipMelee(bot);
            require(context, bot.getMainHandItem().is(tier.sword()),
                    tier.name() + " sword lost to the same-tier axe, held=" + bot.getMainHandItem().getItem());
        }

        // A Sharpness V stone sword out-damages a plain iron sword, so the small bonus must count.
        bot.getInventory().clearContent();
        var enchantments = context.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        ItemStack sharp = new ItemStack(Items.STONE_SWORD);
        sharp.enchant(enchantments.get(Enchantments.SHARPNESS.identifier()).orElseThrow(), 5);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_SWORD));
        InventoryAction.giveItem(bot, sharp);
        CombatCore.equipMelee(bot);
        require(context, bot.getMainHandItem().is(Items.STONE_SWORD) && bot.getMainHandItem().isEnchanted(),
                "Sharpness V stone sword was not preferred over a plain iron sword: "
                        + bot.getMainHandItem().getItem());

        // Spears and the mace are excluded from automatic melee choice on purpose.
        bot.getInventory().clearContent();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SPEAR));
        InventoryAction.giveItem(bot, new ItemStack(Items.MACE));
        require(context, EquipAction.bestWeaponSlot(bot).isEmpty()
                        && !EquipAction.isQualifiedMeleeWeapon(new ItemStack(Items.STONE_SPEAR))
                        && !EquipAction.isQualifiedMeleeWeapon(new ItemStack(Items.MACE)),
                "a spear or a mace was admitted as an automatic melee weapon");
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        CombatCore.equipMelee(bot);
        require(context, bot.getMainHandItem().is(Items.WOODEN_SWORD),
                "spear/mace displaced the wooden sword: " + bot.getMainHandItem().getItem());
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = ENV + "shield_holding_bot_at_mid_health_still_attacks", maxTicks = 220)
    public void shieldHoldingBotAtMidHealthStillAttacks(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "ShieldStrikeGT", 14);
        BlockPos origin = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.SHIELD));
        // 12 hp is inside the shield window (retreat 6 + 6) and above the retreat threshold: the
        // old loop raised the shield forever and never swung. Natural regeneration would lift the
        // bot out of that window within seconds, so hunger is lowered and the health pinned each tick.
        bot.getFoodData().setFoodLevel(6);
        bot.setHealth(12.0F);
        Husk husk = spawnHusk(context, origin.east(2));
        float initialHealth = husk.getHealth();

        CombatTask combat = CombatTask.defensive(husk, 6.0F, origin);
        TaskManager.INSTANCE.assign(bot, combat, TaskOrigin.safety("gametest_shield_strike"));
        AtomicInteger hits = new AtomicInteger();
        AtomicBoolean shieldRaisedBetweenSwings = new AtomicBoolean();
        float[] lastHealth = {initialHealth};
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "bot died against the disabled husk");
            bot.setHealth(12.0F);
            if (husk.getHealth() < lastHealth[0]) {
                hits.incrementAndGet();
            }
            lastHealth[0] = husk.getHealth();
            if (combat.describe().contains("phase=BLOCK")
                    && bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.OFF_HAND) {
                shieldRaisedBetweenSwings.set(true);
            }
            if (hits.get() >= 2 || !husk.isAlive()) {
                require(context, shieldRaisedBetweenSwings.get(),
                        "the fight ended without the shield ever being raised between swings");
                husk.discard();
                despawnAndComplete(context, bot);
            } else if (combat.state() == TaskState.FAILED || combat.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("shield combat ended as " + combat.state()
                        + ":" + combat.failureReason() + " hits=" + hits.get()));
            } else if (context.getTick() >= 180) {
                context.fail(Component.nullToEmpty("shield-holding bot never attacked: hits="
                        + hits.get() + " phase=" + combat.describe()));
            }
        });
    }

    @GameTest(environment = ENV + "guard_task_never_melees_creeper_or_calm_enderman", maxTicks = 160)
    public void guardTaskNeverMeleesCreeperOrCalmEnderman(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GuardCreeperGT", 26);
        BlockPos origin = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD));
        // The creeper and the calm enderman are the NEAREST candidates; the husk is the only
        // legitimate target. A guard that skips the never-melee rule fights the nearest one.
        var creeper = spawnDisabledCreeper(context, origin.east(2));
        EnderMan enderman = EntityType.ENDERMAN.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (enderman == null) {
            despawnAndComplete(context, bot);
            context.fail(Component.nullToEmpty("failed to create calm enderman fixture"));
            return;
        }
        enderman.setPersistenceRequired();
        enderman.setNoAi(true);
        BlockPos endermanFeet = origin.west(2);
        enderman.snapTo(endermanFeet.getX() + 0.5D, endermanFeet.getY(), endermanFeet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(enderman);
        Husk husk = spawnHusk(context, origin.north(3));
        float creeperHealth = creeper.getHealth();
        float endermanHealth = enderman.getHealth();

        // The pure target policy: the husk, and only the husk, is a legal guard target.
        require(context, CombatCore.nearestHostileAround(bot, origin, 10.0D).orElse(null) == husk,
                "the guard target policy did not skip the creeper and the calm enderman: "
                        + CombatCore.nearestHostileAround(bot, origin, 10.0D)
                        .map(entity -> entity.getType().toString()).orElse("none"));

        // Driven directly (not through the task manager): DangerWatcher legitimately preempts a
        // guard for a visible creeper, and this proves the guard itself never swings at one.
        GuardTask guard = GuardTask.point(origin);
        guard.start(bot);
        AtomicBoolean engagedHusk = new AtomicBoolean();
        context.failIfEver(() -> {
            if (guard.state() == TaskState.RUNNING) {
                guard.tick(bot);
            }
            require(context, creeper.getHealth() == creeperHealth,
                    "GuardTask meleed a creeper: hp " + creeper.getHealth() + " " + guard.describe());
            require(context, enderman.getHealth() == endermanHealth,
                    "GuardTask meleed a calm enderman: hp " + enderman.getHealth());
            require(context, guard.state() == TaskState.RUNNING,
                    "GuardTask ended: " + guard.state() + ":" + guard.failureReason());
            if (!guard.describe().contains("phase=WATCH") && !guard.describe().contains("phase=RETURN")) {
                engagedHusk.set(true);
            }
            if (context.getTick() >= 60) {
                // Positive control: the guard did pick the legal husk as its target.
                require(context, engagedHusk.get(), "the guard never engaged the legal husk target");
                guard.abort(bot);
                creeper.discard();
                enderman.discard();
                husk.discard();
                despawnAndComplete(context, bot);
            }
        });
    }

    @GameTest(environment = ENV + "no_melee_against_warden", maxTicks = 80)
    public void noMeleeAgainstWarden(GameTestHelper context) {
        AIPlayerEntity bot = spawnCorridor(context, "CombatWardenGT", 38, -64, 12);
        BlockPos origin = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_SWORD));
        bot.setHealth(20.0F);
        Warden warden = spawnDisabledWarden(context, origin.east(2));
        float initialHealth = warden.getHealth();

        // The pure policy first: a warden is never a melee target, defensive or commanded.
        require(context, CombatCore.isMeleeForbiddenThreat(warden),
                "a warden was not in the never-melee table");
        require(context, !CombatCore.strikeIfReady(bot, warden),
                "strikeIfReady accepted a warden");

        CombatTask combat = CombatTask.defensive(warden, 6.0F, origin);
        combat.start(bot);
        for (int tick = 0; tick < 12; tick++) {
            combat.tick(bot);
        }
        require(context, combat.state() == TaskState.RUNNING
                        && combat.describe().contains("phase=RETREAT")
                        && warden.getHealth() == initialHealth,
                "defensive CombatTask engaged a warden: " + combat.describe()
                        + " state=" + combat.state() + ":" + combat.failureReason());
        BlockPos goal = bot.getActionPack().activePathGoal();
        // The retreat leg must clear the 15-block sonic boom, not the generic six-block step.
        require(context, goal != null
                        && Math.sqrt(goal.distSqr(warden.blockPosition()))
                        >= CombatCore.WARDEN_SONIC_BOOM_RANGE,
                "warden retreat did not clear the sonic boom range: "
                        + (goal == null ? "no goal" : goal.toShortString()));
        combat.abort(bot);
        warden.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = ENV + "warden_threat_routes_to_evade_beyond_sonic_boom_range", maxTicks = 80)
    public void wardenThreatRoutesToEvadeBeyondSonicBoomRange(GameTestHelper context) {
        AIPlayerEntity bot = spawnCorridor(context, "EvadeWardenGT", 50, -64, 12);
        BlockPos origin = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_SWORD));
        bot.setHealth(20.0F);
        bot.getFoodData().setFoodLevel(20);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_warden_evade"));
        Warden warden = spawnDisabledWarden(context, origin.east(6));
        float initialHealth = warden.getHealth();

        require(context, DangerWatcher.isActiveHostileThreat(bot, warden)
                        && CombatCore.isWithinHostilePressureEnvelope(bot, warden),
                "a warden in view was not a factual active threat");
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof EvadeTask,
                "warden routed to " + (active == null ? "idle" : active.name()));
        BlockPos goal = bot.getActionPack().activePathGoal();
        require(context, goal != null
                        && Math.sqrt(goal.distSqr(warden.blockPosition()))
                        > CombatCore.WARDEN_SONIC_BOOM_RANGE,
                "warden evade goal stayed inside the sonic boom range: "
                        + (goal == null ? "no goal" : goal.toShortString()));
        require(context, warden.getHealth() == initialHealth, "warden routing dealt combat damage");
        warden.discard();
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = ENV + "strike_through_wall_or_beyond_vanilla_reach_is_refused", maxTicks = 130)
    public void strikeThroughWallOrBeyondVanillaReachIsRefused(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "StrikeLegalGT", 62);
        var world = context.getLevel();
        BlockPos origin = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD));
        Husk husk = spawnHusk(context, origin.east(2));
        BlockPos wall = origin.east(1);

        // 1. A pane of glass between the bot and a husk two blocks away: inside feet-range, no strike.
        buildWall(context, wall, Blocks.GLASS);
        float before = husk.getHealth();
        ActionResult through = InteractAction.attackEntity(bot, husk);
        require(context, through.isFailed() && "no_line_of_sight".equals(through.reason())
                        && husk.getHealth() == before,
                "a strike went through glass: " + through.status() + ":" + through.reason());

        // 2. A meadow between them (non-colliding plants) must NOT block the strike.
        buildWall(context, wall, Blocks.AIR);
        world.setBlock(wall, Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_ALL);
        ActionResult grass = InteractAction.attackEntity(bot, husk);
        require(context, grass.isSuccess() && husk.getHealth() < before,
                "grass occluded a strike: " + grass.status() + ":" + grass.reason());

        // 3. Beyond vanilla reach: an entity four blocks away, by the strike and by the tool.
        world.setBlock(wall, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        husk.snapTo(origin.getX() + 4.5D, origin.getY(), origin.getZ() + 0.5D, 90.0F, 0.0F);
        float farBefore = husk.getHealth();
        ActionResult far = InteractAction.attackEntity(bot, husk);
        require(context, far.isFailed() && "out_of_reach".equals(far.reason())
                        && husk.getHealth() == farBefore,
                "a strike landed beyond vanilla reach: " + far.status() + ":" + far.reason());
        JsonObject args = new JsonObject();
        args.addProperty("entity_type", "minecraft:husk");
        ToolDefinition tool = new ToolRegistry().get("attack_entity").orElseThrow();
        ToolDefinition.ToolResult toolResult = tool.handler().invoke(bot, args);
        require(context, toolResult != null && !toolResult.ok() && husk.getHealth() == farBefore,
                "attack_entity landed a hit from 4.5 blocks: "
                        + (toolResult == null ? "null" : toolResult.message()));

        // 4. The fight itself: a full glass partition, the husk two blocks behind it. The task may
        //    watch and path, but never swings through the glass, even once the attack cooldown is up.
        husk.snapTo(origin.getX() + 2.5D, origin.getY(), origin.getZ() + 0.5D, 90.0F, 0.0F);
        buildWall(context, wall, Blocks.GLASS);
        float guardedHealth = husk.getHealth();
        CombatTask combat = CombatTask.defensive(husk, 6.0F, origin);
        TaskManager.INSTANCE.assign(bot, combat, TaskOrigin.safety("gametest_strike_legality"));
        context.failIfEver(() -> {
            require(context, husk.getHealth() == guardedHealth,
                    "CombatTask struck through glass: hp " + husk.getHealth() + " " + combat.describe());
            if (context.getTick() >= 90) {
                husk.discard();
                despawnAndComplete(context, bot);
            }
        });
    }

    @GameTest(environment = ENV + "bot_never_targets_its_owner_or_another_bot", maxTicks = 40)
    public void botNeverTargetsItsOwnerOrAnotherBot(GameTestHelper context) {
        var world = context.getLevel();
        ServerPlayer owner = context.makeMockServerPlayerInLevel();
        AIPlayerEntity bot = spawnPlatform(context, "FriendlyFireGT", 74, owner.getUUID());
        BlockPos origin = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD));
        AIPlayerEntity other = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), "FriendlyOtherGT", world,
                        Vec3.atBottomCenterOf(origin.east(2)), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn the second bot"));
        owner.teleportTo(world, origin.getX() + 0.5D, origin.getY(), origin.getZ() + 2.5D,
                Set.of(), 0.0F, 0.0F, true);
        float otherHealth = other.getHealth();
        float ownerHealth = owner.getHealth();

        require(context, CombatCore.isFriendly(bot, other) && CombatCore.isFriendly(bot, owner),
                "the owner or another bot was not classed friendly");
        require(context, CombatCore.nearestTarget(bot, EntityType.PLAYER, 20).isEmpty(),
                "nearestTarget chose the owner or another bot: "
                        + CombatCore.nearestTarget(bot, EntityType.PLAYER, 20).map(LivingEntity::getName));
        ActionResult otherHit = InteractAction.attackEntity(bot, other);
        ActionResult ownerHit = InteractAction.attackEntity(bot, owner);
        require(context, otherHit.isFailed() && ownerHit.isFailed()
                        && other.getHealth() == otherHealth && owner.getHealth() == ownerHealth,
                "a strike landed on the owner or another bot: " + otherHit.reason() + "/" + ownerHit.reason());
        require(context, !CombatCore.strikeIfReady(bot, other), "strikeIfReady accepted another bot");
        require(context, !CombatCore.hostileTo(bot, other) && !CombatCore.hostileTo(bot, owner),
                "the owner or another bot was classed hostile");

        // A bow must not be released into a friend standing on the line of fire.
        Husk husk = spawnHusk(context, origin.east(8));
        other.teleportTo(world, origin.getX() + 4.5D, origin.getY(), origin.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        require(context, StrikeLegality.friendlyOnLineOfFire(bot, husk),
                "a friend on the line of fire was not detected");
        other.teleportTo(world, origin.getX() + 4.5D, origin.getY(), origin.getZ() + 6.5D,
                Set.of(), 0.0F, 0.0F, true);
        owner.teleportTo(world, origin.getX() + 0.5D, origin.getY(), origin.getZ() + 6.5D,
                Set.of(), 0.0F, 0.0F, true);
        require(context, !StrikeLegality.friendlyOnLineOfFire(bot, husk),
                "a friend off the line of fire blocked the shot");
        husk.discard();
        AIPlayerManager.INSTANCE.despawn(world.getServer(), other.getGameProfile().name());
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = ENV + "hostility_uses_enemy_interface_and_keeps_calm_neutrals_passive", maxTicks = 40)
    public void hostilityUsesEnemyInterfaceAndKeepsCalmNeutralsPassive(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "HostilePolicyGT", 86);
        var world = context.getLevel();
        Slime big = EntityType.SLIME.create(world, EntitySpawnReason.COMMAND);
        Slime tiny = EntityType.SLIME.create(world, EntitySpawnReason.COMMAND);
        MagmaCube tinyMagma = EntityType.MAGMA_CUBE.create(world, EntitySpawnReason.COMMAND);
        ZombifiedPiglin piglin = EntityType.ZOMBIFIED_PIGLIN.create(world, EntitySpawnReason.COMMAND);
        var phantom = EntityType.PHANTOM.create(world, EntitySpawnReason.COMMAND);
        var cow = EntityType.COW.create(world, EntitySpawnReason.COMMAND);
        require(context, big != null && tiny != null && tinyMagma != null && piglin != null
                        && phantom != null && cow != null,
                "failed to create the hostility fixtures");
        big.setSize(2, true);
        tiny.setSize(1, true);
        tinyMagma.setSize(1, true);

        // instanceof Monster misses all of these; the Enemy interface does not.
        require(context, CombatCore.hostileTo(bot, big), "a size-2 slime was not hostile");
        require(context, CombatCore.hostileTo(bot, tinyMagma), "a tiny magma cube was not hostile");
        require(context, CombatCore.hostileTo(bot, phantom), "a phantom was not hostile");
        require(context, !CombatCore.hostileTo(bot, tiny), "a harmless tiny slime was hostile");
        // A calm neutral is never auto-attacked; angry at this bot, or after it hurt the bot, it is.
        require(context, !CombatCore.hostileTo(bot, piglin), "a calm zombified piglin was hostile");
        piglin.setPersistentAngerEndTime(world.getGameTime() + 600L);
        piglin.setPersistentAngerTarget(EntityReference.of(bot.getUUID()));
        require(context, CombatCore.hostileTo(bot, piglin),
                "a zombified piglin angry at the bot was not hostile");
        require(context, !CombatCore.hostileTo(bot, cow), "a calm cow was hostile");
        bot.setLastHurtByMob(cow);
        require(context, CombatCore.hostileTo(bot, cow), "a mob that just hurt the bot was not hostile");
        despawnAndComplete(context, bot);
    }

    @GameTest(environment = ENV + "late_fuse_creeper_with_no_wall_material_gets_the_shield", maxTicks = 120)
    public void lateFuseCreeperWithNoWallMaterialGetsTheShield(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "CreeperShieldGT", 98);
        var world = context.getLevel();
        BlockPos origin = bot.blockPosition().immutable();
        // A sealed 4x3 room: no escape route, and no material to wall with. The only defence left
        // is the shield, and an unshielded bot would take a lethal blast at two blocks.
        for (int dx = -2; dx <= 3; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean interior = dx >= -1 && dx <= 2 && dz >= -1 && dz <= 1;
                for (int dy = 0; dy <= 1; dy++) {
                    world.setBlock(origin.offset(dx, dy, dz),
                            interior ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState(),
                            Block.UPDATE_ALL);
                }
                world.setBlock(origin.offset(dx, 2, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        bot.getInventory().clearContent();
        InventoryAction.giveItem(bot, new ItemStack(Items.SHIELD));
        bot.setHealth(20.0F);
        require(context, MaterialPalette.countEmergencyShelterBlocks(bot) == 0,
                "the fixture bot still carried wall material");

        var creeper = spawnDisabledCreeper(context, origin.east(2));
        creeper.ignite();
        creeper.setSwellDir(1);
        for (int tick = 0; tick < 15; tick++) {
            creeper.tick();
        }
        require(context, creeper.isAlive() && creeper.getSwelling(1.0F) >= 0.45F,
                "the fixture creeper did not reach a late fuse: " + creeper.getSwelling(1.0F));

        CreeperDefenseTask task = new CreeperDefenseTask(creeper, creeper.blockPosition());
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.safety("gametest_creeper_shield"));
        AtomicBoolean sawShieldPhase = new AtomicBoolean();
        AtomicBoolean sawBlocking = new AtomicBoolean();
        AtomicInteger goneTicks = new AtomicInteger();
        context.failIfEver(() -> {
            if (task.describe().contains("phase=SHIELD")) {
                sawShieldPhase.set(true);
            }
            if (bot.isBlocking()) {
                sawBlocking.set(true);
            }
            if (!creeper.isAlive() || creeper.isRemoved()) {
                if (goneTicks.incrementAndGet() >= 3) {
                    require(context, bot.isAlive() && bot.getHealth() >= 19.0F,
                            "the shield did not stop the blast: hp=" + bot.getHealth() + " alive=" + bot.isAlive());
                    require(context, sawShieldPhase.get() && sawBlocking.get(),
                            "the blast was survived without the shield fallback: phase_seen="
                                    + sawShieldPhase.get() + " blocking_seen=" + sawBlocking.get());
                    TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_creeper_shield_done");
                    despawnAndComplete(context, bot);
                }
            }
        });
    }

    @GameTest(environment = ENV + "observed_drowned_outside_the_leash_is_shot_not_looped", maxTicks = 300)
    public void observedDrownedOutsideTheLeashIsShotNotLooped(GameTestHelper context) {
        AIPlayerEntity bot = spawnCorridor(context, "RangedDrownedGT", 110, -6, 20);
        var world = context.getLevel();
        BlockPos origin = bot.blockPosition().immutable();
        // A one-deep pool thirteen blocks east: outside the eight-block melee leash, in plain view.
        for (int dx = 12; dx <= 14; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setBlock(origin.offset(dx, -2, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(origin.offset(dx, -1, dz), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        bot.getInventory().clearContent();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.BOW));
        InventoryAction.giveItem(bot, new ItemStack(Items.ARROW, 8));
        bot.setHealth(20.0F);
        bot.getFoodData().setFoodLevel(20);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ranged_drowned"));
        var drowned = EntityType.DROWNED.create(world, EntitySpawnReason.COMMAND);
        if (drowned == null) {
            despawnAndComplete(context, bot);
            context.fail(Component.nullToEmpty("failed to create the drowned fixture"));
            return;
        }
        drowned.setPersistenceRequired();
        drowned.setNoAi(true);
        BlockPos drownedFeet = origin.offset(13, -1, 0);
        drowned.snapTo(drownedFeet.getX() + 0.5D, drownedFeet.getY(), drownedFeet.getZ() + 0.5D, 90.0F, 0.0F);
        world.addFreshEntity(drowned);
        int arrowsBefore = arrows(bot);
        require(context, !CombatTask.isWithinDefensiveLeash(origin, drowned.blockPosition())
                        && CombatTask.canShootFromWhereItStands(bot, drowned),
                "the drowned fixture was not an outside-the-leash shootable target, dist="
                        + bot.distanceTo(drowned));

        DangerWatcher.INSTANCE.scanBot(world.getServer(), bot);
        Task first = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, first instanceof CombatTask,
                "an observed shootable hostile was not assigned defensive combat: "
                        + (first == null ? "idle" : first.name()));
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "bot died against the disabled drowned");
            if (arrows(bot) < arrowsBefore) {
                drowned.discard();
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_ranged_done");
                despawnAndComplete(context, bot);
            } else if (context.getTick() >= 250) {
                Task now = TaskManager.INSTANCE.getActive(bot).orElse(null);
                context.fail(Component.nullToEmpty("the bot never shot the drowned; active="
                        + (now == null ? "idle" : now.describe())));
            }
        });
    }

    // ------------------------------------------------------------------ fixtures

    private static int arrows(AIPlayerEntity bot) {
        int count = InventoryAction.countItem(bot, Items.ARROW);
        ItemStack offhand = bot.getOffhandItem();
        return offhand.is(Items.ARROW) ? count + offhand.getCount() : count;
    }

    private static void buildWall(GameTestHelper context, BlockPos column, Block block) {
        for (int dz = -3; dz <= 4; dz++) {
            for (int dy = 0; dy <= 2; dy++) {
                context.getLevel().setBlock(column.offset(0, dy, dz), block.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static AIPlayerEntity spawnPlatform(GameTestHelper context, String name, int relativeY) {
        return spawnPlatform(context, name, relativeY, null);
    }

    private static AIPlayerEntity spawnPlatform(GameTestHelper context, String name, int relativeY, UUID owner) {
        var world = context.getLevel();
        world.setDayTime(1000L);
        BlockPos feet = context.absolutePos(new BlockPos(3, relativeY, 3));
        for (int dx = -3; dx <= 4; dx++) {
            for (int dz = -3; dz <= 4; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlock(cell.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        return spawnBot(context, name, feet, owner);
    }

    /** A five-wide stone strip {@code minDx..maxDx} blocks either side of the bot, three blocks of air above. */
    private static AIPlayerEntity spawnCorridor(GameTestHelper context, String name, int relativeY,
                                                 int minDx, int maxDx) {
        var world = context.getLevel();
        world.setDayTime(1000L);
        BlockPos feet = context.absolutePos(new BlockPos(3, relativeY, 3));
        for (int dx = minDx; dx <= maxDx; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlock(cell.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        return spawnBot(context, name, feet, null);
    }

    private static AIPlayerEntity spawnBot(GameTestHelper context, String name, BlockPos feet, UUID owner) {
        var world = context.getLevel();
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet),
                        0.0F, 0.0F, GameType.SURVIVAL, owner)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        return bot;
    }

    private static Husk spawnHusk(GameTestHelper context, BlockPos feet) {
        Husk husk = EntityType.HUSK.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (husk == null) {
            context.fail(Component.nullToEmpty("failed to create the husk fixture"));
            throw new IllegalStateException("failed to create the husk fixture");
        }
        husk.setPersistenceRequired();
        husk.setNoAi(true);
        husk.snapTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(husk);
        return husk;
    }

    private static net.minecraft.world.entity.monster.Creeper spawnDisabledCreeper(GameTestHelper context,
                                                                                     BlockPos feet) {
        var creeper = EntityType.CREEPER.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (creeper == null) {
            context.fail(Component.nullToEmpty("failed to create the creeper fixture"));
            throw new IllegalStateException("failed to create the creeper fixture");
        }
        creeper.setPersistenceRequired();
        creeper.setNoAi(true);
        creeper.snapTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(creeper);
        return creeper;
    }

    private static Warden spawnDisabledWarden(GameTestHelper context, BlockPos feet) {
        Warden warden = EntityType.WARDEN.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (warden == null) {
            context.fail(Component.nullToEmpty("failed to create the warden fixture"));
            throw new IllegalStateException("failed to create the warden fixture");
        }
        warden.setPersistenceRequired();
        warden.setNoAi(true);
        warden.snapTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(warden);
        return warden;
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
