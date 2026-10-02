package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.gametest.PerceptionFixtures;
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
import io.github.zoyluo.minecraftai.gametest.MockPlayers;
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
        record Tier(String name, Item sword, Item axe, boolean swordWins) {
        }
        List<Tier> tiers = List.of(
                new Tier("wooden", Items.WOODEN_SWORD, Items.WOODEN_AXE, true),
                new Tier("stone", Items.STONE_SWORD, Items.STONE_AXE, true),
                new Tier("copper", Items.COPPER_SWORD, Items.COPPER_AXE, true),
                new Tier("iron", Items.IRON_SWORD, Items.IRON_AXE, true),
                new Tier("diamond", Items.DIAMOND_SWORD, Items.DIAMOND_AXE, true),
                new Tier("netherite", Items.NETHERITE_SWORD, Items.NETHERITE_AXE, true),
                // Gold is the documented exception of the DPS formula: the golden axe swings at the
                // full 1.0 attack speed (7 x 1.0 = 7.0) against the golden sword (4 x 1.6 = 6.4).
                new Tier("golden", Items.GOLDEN_SWORD, Items.GOLDEN_AXE, false));
        for (Tier tier : tiers) {
            bot.getInventory().clearContent();
            // The axe goes in first so it is genuinely the held weapon before selection.
            InventoryAction.giveItem(bot, new ItemStack(tier.axe()));
            InventoryAction.giveItem(bot, new ItemStack(tier.sword()));
            require(context, EquipAction.attackDamage(new ItemStack(tier.axe()))
                            >= EquipAction.attackDamage(new ItemStack(tier.sword())),
                    tier.name() + " fixture: the axe must out-damage the sword per hit for this to prove DPS");
            CombatCore.equipMelee(bot);
            if (tier.swordWins()) {
                require(context, bot.getMainHandItem().is(tier.sword()),
                        tier.name() + " sword lost to the same-tier axe, held=" + bot.getMainHandItem().getItem());
            } else {
                require(context, bot.getMainHandItem().is(tier.axe()),
                        tier.name() + " axe was expected to win on DPS (documented exception), held="
                                + bot.getMainHandItem().getItem());
            }
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

    @GameTest(environment = ENV + "guard_task_never_melees_creeper_or_calm_enderman", maxTicks = 160 + PerceptionFixtures.MAX_WAIT_TICKS)
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
        float creeperHealth = creeper.getHealth();
        float endermanHealth = enderman.getHealth();

        // Perception is on: the bot notices the three one after the other, each by turning to it and waiting the reaction time of the
        // shared formula (a noticed creeper or zombie makes the watcher react at once, so each step starts from a clean slate; the
        // calm enderman is no threat). They are all known to it, and all in view, when the guard starts.
        PerceptionFixtures.faceToward(bot, enderman);
        PerceptionFixtures.afterNoticed(context, bot, List.of(enderman), since1 -> {
        PerceptionFixtures.faceToward(bot, creeper);
        PerceptionFixtures.afterNoticedFresh(context, bot, List.of(creeper), since2 -> {
        Husk husk = spawnHusk(context, origin.north(3));
        PerceptionFixtures.faceToward(bot, husk);
        PerceptionFixtures.afterNoticedFresh(context, bot, List.of(husk, creeper, enderman), since -> {

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
        PerceptionFixtures.everyTick(context, () -> {
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
            if (since.getAsLong() >= 60) {
                // Positive control: the guard did pick the legal husk as its target.
                require(context, engagedHusk.get(), "the guard never engaged the legal husk target");
                guard.abort(bot);
                creeper.discard();
                enderman.discard();
                husk.discard();
                despawnAndComplete(context, bot);
            }
        });
        });
        });
        });
    }

    @GameTest(environment = ENV + "no_melee_against_warden", maxTicks = 80 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void noMeleeAgainstWarden(GameTestHelper context) {
        AIPlayerEntity bot = spawnCorridor(context, "CombatWardenGT", 38, -64, 12);
        BlockPos origin = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_SWORD));
        bot.setHealth(20.0F);
        Warden warden = spawnDisabledWarden(context, origin.east(2));
        float initialHealth = warden.getHealth();

        PerceptionFixtures.faceToward(bot, warden);
        PerceptionFixtures.afterNoticedFresh(context, bot, List.of(warden), since -> {
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
        });
    }

    @GameTest(environment = ENV + "warden_threat_routes_to_evade_beyond_sonic_boom_range", maxTicks = 80 + PerceptionFixtures.MAX_WAIT_TICKS)
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

        PerceptionFixtures.faceToward(bot, warden);
        PerceptionFixtures.afterNoticed(context, bot, List.of(warden), () -> {
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
        });
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
        // Human aim: a strike lands only under the crosshair, so the fixture faces the husk (the small pitch left is turned at once).
        io.github.zoyluo.minecraftai.action.LookAction.lookAt(bot, husk.getBoundingBox().getCenter());
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
        ServerPlayer owner = MockPlayers.mock(context);
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

    @GameTest(environment = ENV + "late_fuse_creeper_with_no_wall_material_gets_the_shield", maxTicks = 120 + PerceptionFixtures.MAX_WAIT_TICKS)
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
        // The bot notices the (silent, unlit) creeper first; the fuse is lit afterwards.
        PerceptionFixtures.faceToward(bot, creeper);
        PerceptionFixtures.afterNoticed(context, bot, List.of(creeper), () -> {
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
        PerceptionFixtures.everyTick(context, () -> {
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
        });
    }

    @GameTest(environment = ENV + "observed_drowned_outside_the_leash_is_shot_not_looped", maxTicks = 300 + PerceptionFixtures.MAX_WAIT_TICKS)
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
        PerceptionFixtures.faceToward(bot, drowned);
        PerceptionFixtures.afterNoticed(context, bot, List.of(drowned), since -> {
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
        PerceptionFixtures.everyTick(context, () -> {
            require(context, bot.isAlive(), "bot died against the disabled drowned");
            if (arrows(bot) < arrowsBefore) {
                drowned.discard();
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_ranged_done");
                despawnAndComplete(context, bot);
            } else if (since.getAsLong() >= 250) {
                Task now = TaskManager.INSTANCE.getActive(bot).orElse(null);
                context.fail(Component.nullToEmpty("the bot never shot the drowned; active="
                        + (now == null ? "idle" : now.describe())));
            }
        });
        });
    }

    /**
     * Peekaboo against two live skeletons: the bot builds its cover column, then peeks out and ducks
     * back. Both moves must be real walks by movement inputs: no tick may carry the bot a block, and
     * getting out from behind the column must take several ticks (a teleport step took one).
     */
    @GameTest(environment = ENV + "peekaboo_walks_out_and_back_without_teleport", maxTicks = 420 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void peekabooWalksOutAndBackWithoutTeleport(GameTestHelper context) {
        AIPlayerEntity bot = spawnCorridor(context, "PeekabooGT", 122, -4, 16);
        var world = context.getLevel();
        BlockPos origin = bot.blockPosition().immutable();
        // This test deliberately keeps both skeletons live, so vanilla arrow knockback is part of
        // the run.  spawnCorridor's floor ends at z=+/-2; retain that narrow course before an
        // unlucky outward knock can send the bot into the artificial GameTest void.  The U opens
        // east toward both skeleton lanes and leaves the hide, cover, expose, and firing cells open.
        buildPeekabooRetainingU(context, origin);
        bot.getInventory().clearContent();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.BOW));
        InventoryAction.giveItem(bot, new ItemStack(Items.ARROW, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_HELMET));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_CHESTPLATE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_LEGGINGS));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_BOOTS));
        bot.setHealth(20.0F);
        bot.getFoodData().setFoodLevel(20);

        // Two live, armed skeletons (a helmet keeps the daylight off them) at the far end.
        var first = spawnArmedSkeleton(context, origin.east(11).north());
        var second = spawnArmedSkeleton(context, origin.east(11).south());
        // The skeletons stand still until the bot has noticed them (a live skeleton would shoot before the bot knew of it); then the
        // fight is the one the fixture always had.
        first.setNoAi(true);
        second.setNoAi(true);
        PerceptionFixtures.faceToward(bot, first);
        PerceptionFixtures.afterNoticed(context, bot, List.of(first, second), since -> {
        first.setNoAi(false);
        second.setNoAi(false);
        require(context, CombatCore.rangedThreatsAround(bot, 24.0D).size() >= 2,
                "the skeleton fixtures were not two observable ranged threats");

        CombatTask combat = new CombatTask(EntityType.SKELETON, 2, 6.0F);
        TaskManager.INSTANCE.assign(bot, combat, TaskOrigin.safety("gametest_peekaboo"));

        Vec3[] previous = {bot.position()};
        Vec3[] hide = {null};
        int[] peekStartTick = {-1};
        int[] leftCoverTick = {-1};
        boolean[] cycleDone = {false};
        double[] maxStep = {0.0D};
        PerceptionFixtures.everyTick(context, () -> {
            Vec3 now = bot.position();
            double step = Math.hypot(now.x - previous[0].x, now.z - previous[0].z);
            previous[0] = now;
            require(context, bot.isAlive(), "the bot died on the skeleton course: " + combat.describe());
            boolean peeking = combat.describe().contains("phase=COVER_PEEK");
            if (peeking && bot.hurtTime == 0) {
                maxStep[0] = Math.max(maxStep[0], step);
                require(context, step < 0.75D,
                        "a peek step moved the bot " + step + " blocks in one tick (a teleport, not a walk)");
            }
            if (peeking && peekStartTick[0] < 0) {
                peekStartTick[0] = (int) since.getAsLong();
                hide[0] = now;
            }
            if (peeking && leftCoverTick[0] < 0 && hide[0] != null
                    && Math.hypot(now.x - hide[0].x, now.z - hide[0].z) >= 0.6D) {
                leftCoverTick[0] = (int) since.getAsLong();
                require(context, leftCoverTick[0] - peekStartTick[0] >= 3,
                        "the bot reached the exposed cell within " + (leftCoverTick[0] - peekStartTick[0])
                                + " ticks: that is a teleport, not a walk");
            }
            if (leftCoverTick[0] >= 0 && !peeking && hide[0] != null
                    && Math.hypot(now.x - hide[0].x, now.z - hide[0].z) <= 0.4D) {
                cycleDone[0] = true;
            }
            if (cycleDone[0]) {
                require(context, maxStep[0] < 0.75D, "a peek step was a teleport");
                first.discard();
                second.discard();
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_peekaboo_done");
                despawnAndComplete(context, bot);
            } else if (since.getAsLong() >= 380) {
                context.fail(Component.nullToEmpty("no complete peek out and back: peekStart="
                        + peekStartTick[0] + " leftCover=" + leftCoverTick[0] + " maxStep=" + maxStep[0]
                        + " state=" + combat.state() + " " + combat.describe() + " hp=" + bot.getHealth()));
            }
        });
        });
    }

    /**
     * A ledge beside the fight: the bot stands on the outer edge of a platform whose north side is
     * a long drop. Its post-swing repositioning strafes alternate sideways every twenty ticks; the
     * footing guard must never let a strafe carry it over the edge. Asserted on the running behaviour: the
     * bot's own sideways input never points at the ledge from the rim, and its position never crosses it.
     */
    @GameTest(environment = ENV + "reposition_strafe_never_walks_off_the_ledge", maxTicks = 220)
    public void repositionStrafeNeverWalksOffTheLedge(GameTestHelper context) {
        var world = context.getLevel();
        world.setDayTime(1000L);
        BlockPos feet = context.absolutePos(new BlockPos(3, 134, 3));
        for (int dx = -3; dx <= 5; dx++) {
            for (int dz = -3; dz <= 4; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                boolean floor = dz >= 0;
                world.setBlock(cell.below(),
                        floor ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlock(cell.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        AIPlayerEntity bot = spawnBot(context, "LedgeStrafeGT", feet, null);
        // Right on the northern rim: the box still rests on the platform but a few ticks of strafing
        // north would take it fully off.
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.06D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setDeltaMovement(Vec3.ZERO);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD));
        bot.setHealth(20.0F);
        Husk husk = spawnHusk(context, feet.east(2));
        husk.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(600.0D);
        husk.setHealth(600.0F);

        CombatTask combat = CombatTask.defensive(husk, 6.0F, feet);
        TaskManager.INSTANCE.assign(bot, combat, TaskOrigin.safety("gametest_ledge_strafe"));
        double startY = bot.getY();
        double startZ = bot.getZ();
        double rimZ = feet.getZ();
        AtomicInteger repositionTicks = new AtomicInteger();
        AtomicInteger strafeAwayTicks = new AtomicInteger();
        context.failIfEver(() -> {
            require(context, bot.isAlive() && bot.getY() > startY - 0.05D,
                    "the bot strafed off the ledge: y=" + bot.getY() + " z=" + bot.getZ()
                            + " " + combat.describe());
            // The running strafe itself, not the guard function in a pose: the bot's own sideways input
            // (xxa, what the task wrote through the action pack) and where it actually went.
            require(context, bot.getZ() >= startZ - 0.05D,
                    "the bot crossed the rim: z=" + bot.getZ() + " start=" + startZ + " " + combat.describe());
            if (combat.describe().contains("phase=REPOSITION")) {
                repositionTicks.incrementAndGet();
                // Facing east, a positive strafe input is toward the north (the ledge).
                if (bot.getZ() < rimZ + 0.6D) {
                    require(context, bot.xxa <= 0.0F,
                            "the running strafe pushed toward the ledge from the rim: xxa=" + bot.xxa
                                    + " z=" + bot.getZ() + " " + combat.describe());
                    if (bot.xxa < 0.0F) {
                        strafeAwayTicks.incrementAndGet();
                    }
                }
            }
            if (context.getTick() >= 190) {
                require(context, repositionTicks.get() >= 8,
                        "the fight never repositioned: " + combat.describe());
                require(context, strafeAwayTicks.get() >= 1,
                        "the fight never strafed away from the ledge while repositioning at the rim");
                husk.discard();
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_ledge_strafe_done");
                despawnAndComplete(context, bot);
            }
        });
    }

    /**
     * A skeleton that is visibly drawing its bow at the bot, close enough for the arrow to come with
     * little warning, gets the shield up before the arrow exists (the fixture skeleton has no AI and
     * never fires: the shield can only be a reaction to the drawn bow). A skeleton drawing with its
     * head turned away must not.
     */
    @GameTest(environment = ENV + "drawing_skeleton_raises_the_shield_before_the_arrow", maxTicks = 260 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void drawingSkeletonRaisesTheShieldBeforeTheArrow(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "PreShieldGT", 146);
        BlockPos origin = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.SHIELD));
        bot.setHealth(20.0F);
        Husk husk = spawnHusk(context, origin.east(2));
        husk.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(600.0D);
        husk.setHealth(600.0F);
        var skeleton = EntityType.SKELETON.create(context.getLevel(), EntitySpawnReason.COMMAND);
        require(context, skeleton != null, "failed to create the skeleton fixture");
        skeleton.setPersistenceRequired();
        skeleton.setNoAi(true);
        skeleton.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        skeleton.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        // Behind the husk in the bot's view (the bot turns to its fight, east): a skeleton behind the bot would not be noticed at all.
        BlockPos skeletonFeet = origin.east(6);
        // The skeleton must tick (its draw advances only then): six blocks away can be a neighbouring chunk that is merely loaded, as
        // the structure lands on chunk borders at random. Its chunks are forced like any wide fixture's (skeleton to bot, +-2).
        io.github.zoyluo.minecraftai.gametest.GameTestChunkForcing.forceForTest(context,
                (Math.min(skeletonFeet.getX(), origin.getX()) - 2) >> 4, (Math.max(skeletonFeet.getX(), origin.getX()) + 2) >> 4,
                (origin.getZ() - 2) >> 4, (origin.getZ() + 2) >> 4);
        // Facing east, away from the bot: yaw -90 points the head along +x.
        skeleton.snapTo(skeletonFeet.getX() + 0.5D, skeletonFeet.getY(), skeletonFeet.getZ() + 0.5D, -90.0F, 0.0F);
        skeleton.setYHeadRot(-90.0F);
        context.getLevel().addFreshEntity(skeleton);
        skeleton.startUsingItem(InteractionHand.MAIN_HAND);

        // The bot faces its enemy and notices both before the scenario starts (the shield rule needs a NOTICED shooter).
        PerceptionFixtures.faceToward(bot, husk);
        PerceptionFixtures.afterNoticed(context, bot, List.of(husk, skeleton), since -> {
        CombatTask combat = CombatTask.defensive(husk, 6.0F, origin);
        TaskManager.INSTANCE.assign(bot, combat, TaskOrigin.safety("gametest_pre_shield"));
        int[] turnedAt = {-1};
        int[] drawRestarts = {0};
        boolean[] raised = {false};
        PerceptionFixtures.everyTick(context, () -> {
            require(context, bot.isAlive(), "the bot died: " + combat.describe());
            if (!skeleton.isUsingItem() || !skeleton.getUseItem().is(Items.BOW)) {
                // The fixture keeps its (AI-less) skeleton drawing; a dropped draw simply starts over.
                drawRestarts[0]++;
                skeleton.startUsingItem(InteractionHand.MAIN_HAND);
            }
            // The husk the bot fights stands in reach, so the melee rhythm legitimately raises the shield between the bot's own swings
            // (phase BLOCK, owned by the combat task): what this test is about is the shield guard's RESPONSE to the shooter.
            boolean shieldUp = ShieldGuard.INSTANCE.holding(bot) && bot.getOffhandItem().is(Items.SHIELD);
            if (turnedAt[0] < 0) {
                require(context, !shieldUp,
                        "the shield went up for a skeleton drawing with its head turned away (tick "
                                + since.getAsLong() + ", draw ticks " + skeleton.getTicksUsingItem() + ")");
                if (since.getAsLong() >= 30 && skeleton.getTicksUsingItem() >= 14) {
                    turnedAt[0] = (int) since.getAsLong();
                    // Turn to face the bot: yaw 90 points the head along -x.
                    skeleton.setYRot(90.0F);
                    skeleton.setYHeadRot(90.0F);
                    skeleton.setYBodyRot(90.0F);
                } else if (since.getAsLong() >= 120) {
                    context.fail(Component.nullToEmpty("the fixture skeleton never held a draw: restarts="
                            + drawRestarts[0] + " draw_ticks=" + skeleton.getTicksUsingItem()));
                }
                return;
            }
            if (shieldUp && CombatTask.nearbyDrawingShooter(bot) == skeleton) {
                raised[0] = true;
            }
            if (raised[0] && bot.isBlocking()) {
                skeleton.discard();
                husk.discard();
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_pre_shield_done");
                despawnAndComplete(context, bot);
            } else if (since.getAsLong() >= turnedAt[0] + 30) {
                context.fail(Component.nullToEmpty("the shield never came up for a skeleton drawing at the bot: "
                        + combat.describe() + " drawing=" + (CombatTask.nearbyDrawingShooter(bot) == skeleton)
                        + " draw_ticks=" + skeleton.getTicksUsingItem() + " restarts=" + drawRestarts[0]));
            }
        });
        });
    }

    /**
     * A hostile the guard can see from its post but never reach: the moment the guard engages it, a
     * glass partition cuts the line of sight, and once the guard has given up and returned the
     * partition is gone again (as when a mob is visible across a gap but out of sight on any route
     * to it). The guard must drop it for a cooldown instead of cycling engage / walk up /
     * disengage / return.
     */
    @GameTest(environment = ENV + "guard_does_not_cycle_on_the_target_it_lost_sight_of", maxTicks = 220)
    public void guardDoesNotCycleOnTheTargetItLostSightOf(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "GuardCooldownGT", 158);
        BlockPos origin = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD));
        Husk husk = spawnHusk(context, origin.east(4));
        // Once the partition is gone the server's own danger response may fight it too: keep it alive.
        husk.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(600.0D);
        husk.setHealth(600.0F);

        GuardTask guard = GuardTask.point(origin);
        guard.start(bot);
        AtomicInteger engagements = new AtomicInteger();
        AtomicBoolean lostSightExit = new AtomicBoolean();
        boolean[] wasApproaching = {false};
        boolean[] wallUp = {false};
        context.failIfEver(() -> {
            if (guard.state() == TaskState.RUNNING) {
                guard.tick(bot);
            }
            require(context, guard.state() == TaskState.RUNNING,
                    "GuardTask ended: " + guard.state() + ":" + guard.failureReason());
            boolean approaching = guard.describe().contains("phase=APPROACH");
            if (approaching && !wasApproaching[0]) {
                engagements.incrementAndGet();
                buildWall(context, origin.east(2), Blocks.GLASS);
                wallUp[0] = true;
            }
            if (!approaching && wasApproaching[0]) {
                lostSightExit.set(true);
                buildWall(context, origin.east(2), Blocks.AIR);
                wallUp[0] = false;
            }
            wasApproaching[0] = approaching;
            require(context, !wallUp[0] || husk.getHealth() == husk.getMaxHealth(),
                    "the guard struck through the glass");
            if (context.getTick() >= 150) {
                require(context, engagements.get() >= 1 && lostSightExit.get(),
                        "the guard never engaged and dropped the unreachable husk: engagements="
                                + engagements.get() + " " + guard.describe());
                require(context, engagements.get() == 1,
                        "the guard re-engaged a target it had just lost sight of, " + engagements.get() + " times");
                guard.abort(bot);
                husk.discard();
                despawnAndComplete(context, bot);
            }
        });
    }


    // ------------------------------------------------------------------ bow give-up: cancel, never fire

    /** Arrow entities the bot itself loosed (a stray arrow of another test in the same world does not count). */
    private static int botArrows(GameTestHelper context, AIPlayerEntity bot) {
        return context.getLevel().getEntitiesOfClass(
                net.minecraft.world.entity.projectile.arrow.AbstractArrow.class,
                bot.getBoundingBox().inflate(64.0D), arrow -> arrow.getOwner() == bot).size();
    }

    /**
     * A friend (the owner) stands on the line of fire of a bow that is drawn fully: the bot must hold, then
     * give the bow up by CANCELLING the draw. Releasing it would fire the arrow into the owner, which is
     * exactly what the guard exists to prevent, so no Arrow entity may ever exist and no arrow leaves the
     * inventory, while the fight goes on without the bow.
     */
    @GameTest(environment = ENV + "friendly_owner_on_the_line_of_fire_gives_the_bow_up_without_a_shot", maxTicks = 320 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void friendlyOwnerOnTheLineOfFireGivesTheBowUpWithoutAShot(GameTestHelper context) {
        ServerPlayer owner = MockPlayers.mock(context);
        AIPlayerEntity bot = spawnCorridor(context, "FriendLineGT", 170, -4, 16, owner.getUUID());
        var world = context.getLevel();
        BlockPos origin = bot.blockPosition().immutable();
        bot.getInventory().clearContent();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.BOW));
        InventoryAction.giveItem(bot, new ItemStack(Items.ARROW, 16));
        bot.setHealth(20.0F);
        bot.getFoodData().setFoodLevel(20);
        Husk husk = spawnHusk(context, origin.east(12));
        owner.teleportTo(world, origin.getX() + 6.5D, origin.getY(), origin.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        PerceptionFixtures.faceToward(bot, husk);
        PerceptionFixtures.afterNoticed(context, bot, List.of(husk), since -> {
        require(context, StrikeLegality.friendlyOnLineOfFire(bot, husk),
                "the fixture owner was not on the line of fire");
        int arrowsBefore = arrows(bot);
        CombatTask combat = new CombatTask(EntityType.HUSK, 1, 6.0F);
        TaskManager.INSTANCE.assign(bot, combat, TaskOrigin.safety("gametest_friend_line"));

        boolean[] sawFullDraw = {false};
        int[] giveUpTick = {-1};
        PerceptionFixtures.everyTick(context, () -> {
            require(context, bot.isAlive(), "the bot died: " + combat.describe());
            require(context, botArrows(context, bot) == 0,
                    "an arrow was spawned with the owner on the line of fire: " + combat.describe());
            require(context, arrows(bot) >= arrowsBefore,
                    "an arrow left the inventory (a shot) with the owner on the line of fire");
            if (giveUpTick[0] < 0) {
                // The owner stays on the line for the whole hold.
                owner.teleportTo(world, origin.getX() + 6.5D, origin.getY(), origin.getZ() + 0.5D,
                        Set.of(), 0.0F, 0.0F, true);
                if (bot.isUsingItem() && bot.getTicksUsingItem() >= 20
                        && combat.describe().contains("phase=RANGED")) {
                    sawFullDraw[0] = true;
                }
                if (combat.isRangedSuppressed()) {
                    giveUpTick[0] = (int) since.getAsLong();
                    require(context, sawFullDraw[0],
                            "the bow was given up without ever being fully drawn: the give-up was not the "
                                    + "dangerous one (bow drawn at full pull)");
                    require(context, !bot.isUsingItem(),
                            "the bow is still drawn after the give-up: " + combat.describe());
                    require(context, !combat.describe().contains("phase=RANGED"),
                            "the bot did not fall back from the ranged phase: " + combat.describe());
                }
            } else if (since.getAsLong() >= giveUpTick[0] + 25) {
                husk.discard();
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_friend_line_done");
                despawnAndComplete(context, bot);
            }
            if (giveUpTick[0] < 0 && since.getAsLong() >= 290) {
                context.fail(Component.nullToEmpty("the bow was never given up: sawFullDraw=" + sawFullDraw[0]
                        + " " + combat.describe() + " using=" + bot.isUsingItem()
                        + " ticks=" + bot.getTicksUsingItem()));
            }
        });
        });
    }

    /**
     * The same guard on the cover-peek path: with the owner standing on the line of fire at the end of each
     * peek the drawn bow must never be released; after a few blocked peeks it is given up by cancelling.
     */
    @GameTest(environment = ENV + "friendly_owner_on_the_peek_line_gives_the_bow_up_without_a_shot", maxTicks = 900 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void friendlyOwnerOnThePeekLineGivesTheBowUpWithoutAShot(GameTestHelper context) {
        ServerPlayer owner = MockPlayers.mock(context);
        AIPlayerEntity bot = spawnCorridor(context, "FriendPeekGT", 182, -4, 16, owner.getUUID());
        var world = context.getLevel();
        BlockPos origin = bot.blockPosition().immutable();
        givePeekabooKit(bot);
        owner.teleportTo(world, origin.getX() - 2.5D, origin.getY(), origin.getZ() + 2.5D,
                Set.of(), 0.0F, 0.0F, true);
        // One behind the other on the same line: under perception the bot may shoot at whichever skeleton it has noticed, and the friend
        // on that line is on the line of fire of both (the fixture no longer relies on the bot picking the nearer of two lines).
        var first = spawnArmedSkeleton(context, origin.east(11));
        var second = spawnArmedSkeleton(context, origin.east(13));
        first.setNoAi(true);
        second.setNoAi(true);
        PerceptionFixtures.faceToward(bot, first);
        PerceptionFixtures.afterNoticedFresh(context, bot, List.of(first, second), since -> {
        require(context, CombatCore.rangedThreatsAround(bot, 24.0D).size() >= 2,
                "the skeleton fixtures were not two observable ranged threats");
        int arrowsBefore = arrows(bot);
        CombatTask combat = new CombatTask(EntityType.SKELETON, 2, 6.0F);
        TaskManager.INSTANCE.assign(bot, combat, TaskOrigin.safety("gametest_friend_peek"));

        int[] peekTicks = {0};
        int[] giveUpTick = {-1};
        PerceptionFixtures.everyTick(context, () -> {
            require(context, bot.isAlive(), "the bot died: " + combat.describe());
            require(context, botArrows(context, bot) == 0,
                    "an arrow was spawned with the owner on the peek line: " + combat.describe());
            require(context, arrows(bot) >= arrowsBefore,
                    "an arrow left the inventory (a shot) with the owner on the peek line");
            if (giveUpTick[0] < 0) {
                if (combat.describe().contains("phase=COVER_PEEK")) {
                    peekTicks[0]++;
                    placeOnLineOfFire(owner, bot, first.distanceTo(bot) <= second.distanceTo(bot) ? first : second);
                }
                if (combat.isRangedSuppressed()) {
                    giveUpTick[0] = (int) since.getAsLong();
                    require(context, peekTicks[0] > 0, "the bow was given up before any peek: " + combat.describe());
                    require(context, !bot.isUsingItem() && !combat.describe().contains("phase=COVER"),
                            "the bot is still in cover with the bow drawn after the give-up: " + combat.describe());
                }
            } else if (since.getAsLong() >= giveUpTick[0] + 25) {
                first.discard();
                second.discard();
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_friend_peek_done");
                despawnAndComplete(context, bot);
            }
            if (giveUpTick[0] < 0 && since.getAsLong() >= 840) {
                context.fail(Component.nullToEmpty("the peek bow was never given up: peekTicks=" + peekTicks[0]
                        + " " + combat.describe() + " " + combat.state()));
            }
        });
        });
    }

    /**
     * The bow leaves the plan while the bot sits in cover with the bow already drawing (the threats close in
     * to melee range): the cover-hide exit must cancel the draw. Releasing it would loose an arrow that
     * skipped the line-of-fire check.
     */
    @GameTest(environment = ENV + "cover_hide_exit_with_the_bow_drawn_fires_no_arrow", maxTicks = 420 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void coverHideExitWithTheBowDrawnFiresNoArrow(GameTestHelper context) {
        AIPlayerEntity bot = spawnCorridor(context, "CoverExitGT", 194, -4, 16);
        BlockPos origin = bot.blockPosition().immutable();
        givePeekabooKit(bot);
        var first = spawnArmedSkeleton(context, origin.east(11).north());
        var second = spawnArmedSkeleton(context, origin.east(11).south());
        first.setNoAi(true);
        second.setNoAi(true);
        PerceptionFixtures.faceToward(bot, first);
        PerceptionFixtures.afterNoticedFresh(context, bot, List.of(first, second), since -> {
        require(context, CombatCore.rangedThreatsAround(bot, 24.0D).size() >= 2,
                "the skeleton fixtures were not two observable ranged threats");
        int arrowsBefore = arrows(bot);
        CombatTask combat = new CombatTask(EntityType.SKELETON, 2, 6.0F);
        TaskManager.INSTANCE.assign(bot, combat, TaskOrigin.safety("gametest_cover_exit"));

        int[] exitTick = {-1};
        PerceptionFixtures.everyTick(context, () -> {
            require(context, bot.isAlive(), "the bot died: " + combat.describe());
            require(context, botArrows(context, bot) == 0,
                    "an arrow was loosed by the cover-hide exit: " + combat.describe());
            require(context, arrows(bot) >= arrowsBefore, "an arrow left the inventory on the cover-hide exit");
            if (exitTick[0] < 0) {
                if (combat.describe().contains("phase=COVER_HIDE") && bot.isUsingItem()
                        && bot.getTicksUsingItem() >= 6) {
                    // The threats close in: the bow leaves the plan (shouldUseBow turns false) with the bow drawing.
                    first.snapTo(origin.getX() + 3.5D, origin.getY(), origin.getZ() + 0.5D, 90.0F, 0.0F);
                    second.snapTo(origin.getX() + 3.5D, origin.getY(), origin.getZ() + 1.5D, 90.0F, 0.0F);
                    exitTick[0] = (int) since.getAsLong();
                }
            } else if (since.getAsLong() >= exitTick[0] + 12) {
                require(context, !combat.describe().contains("phase=COVER"),
                        "the bot stayed in cover after the bow left the plan: " + combat.describe());
                first.discard();
                second.discard();
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_cover_exit_done");
                despawnAndComplete(context, bot);
            }
            if (exitTick[0] < 0 && since.getAsLong() >= 380) {
                context.fail(Component.nullToEmpty("never reached a drawn bow in cover: " + combat.describe()
                        + " " + combat.state()));
            }
        });
        });
    }

    /**
     * The walked step keeps vanilla's item-use slowdown: the same one-block walk takes several times as long,
     * and moves several times slower, with a bow drawn. A step that was slowed only for a few ticks keeps no
     * slowed timeout afterwards (the budget is spent per tick, not latched).
     */
    @GameTest(environment = ENV + "walked_step_slows_with_a_drawn_bow_and_times_out_on_a_budget", maxTicks = 400)
    public void walkedStepSlowsWithADrawnBowAndTimesOutOnABudget(GameTestHelper context) {
        AIPlayerEntity bot = spawnCorridor(context, "StepSlowGT", 206, -4, 16);
        var world = context.getLevel();
        BlockPos origin = bot.blockPosition().immutable();
        bot.getInventory().clearContent();
        InventoryAction.giveItem(bot, new ItemStack(Items.BOW));
        InventoryAction.giveItem(bot, new ItemStack(Items.ARROW, 16));
        bot.getActionPack().stopAll();
        // Face east once; every step below keeps that aim.
        io.github.zoyluo.minecraftai.action.LookAction.lookHorizontallyAt(bot,
                new Vec3(origin.getX() + 10.5D, bot.getY(), origin.getZ() + 0.5D));

        int[] stage = {0};
        CombatCore.InputStep[] step = {null};
        int[] freeTicks = {0};
        int[] slowedTicks = {0};
        double[] freePeak = {0.0D};
        double[] slowedPeak = {0.0D};
        Vec3[] last = {bot.position()};
        Vec3[] pin = {null};
        context.failIfEver(() -> {
            Vec3 now = bot.position();
            double speed = Math.hypot(now.x - last[0].x, now.z - last[0].z);
            last[0] = now;
            switch (stage[0]) {
                case 0 -> { // an ordinary one-block step east
                    if (step[0] == null) {
                        step[0] = CombatCore.beginStepByInput(origin.east(), true, false);
                    }
                    freePeak[0] = Math.max(freePeak[0], speed);
                    CombatCore.StepStatus status = CombatCore.stepByInput(bot, step[0]);
                    require(context, status != CombatCore.StepStatus.FAILED,
                            "the plain step failed: " + step[0].failure());
                    if (status == CombatCore.StepStatus.ARRIVED) {
                        freeTicks[0] = step[0].ticks();
                        step[0] = null;
                        stage[0] = 1;
                    }
                }
                case 1 -> { // walk back, again unslowed, to the start
                    if (step[0] == null) {
                        step[0] = CombatCore.beginStepByInput(origin, true, false);
                    }
                    CombatCore.StepStatus status = CombatCore.stepByInput(bot, step[0]);
                    require(context, status != CombatCore.StepStatus.FAILED,
                            "the return step failed: " + step[0].failure());
                    if (status == CombatCore.StepStatus.ARRIVED) {
                        step[0] = null;
                        InteractAction.useItemInAir(bot, InteractionHand.MAIN_HAND);
                        stage[0] = 2;
                    }
                }
                case 2 -> { // the same step east with the bow drawn
                    require(context, bot.isUsingItem(), "the bow was not drawn for the slowed step");
                    if (step[0] == null) {
                        step[0] = CombatCore.beginStepByInput(origin.east(), true, false);
                    }
                    slowedPeak[0] = Math.max(slowedPeak[0], speed);
                    CombatCore.StepStatus status = CombatCore.stepByInput(bot, step[0]);
                    require(context, status != CombatCore.StepStatus.FAILED,
                            "the slowed step failed: " + step[0].failure() + " after " + step[0].ticks() + " ticks");
                    if (status == CombatCore.StepStatus.ARRIVED) {
                        slowedTicks[0] = step[0].ticks();
                        // A step is mostly acceleration and settling for the unslowed walk, so the tick ratio is well under the
                        // 5x input ratio (10 vs 23 measured): the peak speed below is the exact measure.
                        require(context, slowedTicks[0] >= 2 * freeTicks[0],
                                "the drawn-bow step took " + slowedTicks[0] + " ticks against " + freeTicks[0]
                                        + " unslowed: the item-use slowdown is not applied");
                        require(context, slowedPeak[0] <= 0.35D * freePeak[0],
                                "the drawn-bow step peaked at " + slowedPeak[0] + " blocks/tick against "
                                        + freePeak[0] + " unslowed");
                        bot.stopUsingItem();
                        step[0] = null;
                        stage[0] = 3;
                    }
                }
                case 3 -> { // slowed for 5 ticks only, then the bow drops: the timeout must not stay at 50
                    if (step[0] == null) {
                        InteractAction.useItemInAir(bot, InteractionHand.MAIN_HAND);
                        step[0] = CombatCore.beginStepByInput(origin, true, false);
                        pin[0] = bot.position();
                    }
                    // The bot is held in place so the step can never arrive: only the timeout ends it.
                    bot.teleportTo(world, pin[0].x, pin[0].y, pin[0].z, Set.of(), bot.getYRot(), bot.getXRot(), true);
                    bot.setDeltaMovement(Vec3.ZERO);
                    CombatCore.StepStatus status = CombatCore.stepByInput(bot, step[0]);
                    if (step[0].ticks() == 5) {
                        bot.stopUsingItem();
                    }
                    if (status == CombatCore.StepStatus.FAILED) {
                        int failedAt = step[0].ticks();
                        require(context, "timeout".equals(step[0].failure()),
                                "the pinned step failed for the wrong reason: " + step[0].failure());
                        require(context, failedAt >= 12 && failedAt <= 20,
                                "a step slowed for 5 ticks then free timed out after " + failedAt
                                        + " ticks (about 15 expected, 51 with a sticky slowed flag)");
                        despawnAndComplete(context, bot);
                    } else if (step[0].ticks() > 60) {
                        context.fail(Component.nullToEmpty("the pinned step never timed out"));
                    }
                }
                default -> { }
            }
            if (context.getTick() >= 380) {
                context.fail(Component.nullToEmpty("the step scenario stalled in stage " + stage[0]
                        + " free=" + freeTicks[0] + " slowed=" + slowedTicks[0]));
            }
        });
    }

    private static void givePeekabooKit(AIPlayerEntity bot) {
        bot.getInventory().clearContent();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.BOW));
        InventoryAction.giveItem(bot, new ItemStack(Items.ARROW, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_HELMET));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_CHESTPLATE));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_LEGGINGS));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_BOOTS));
        bot.setHealth(20.0F);
        bot.getFoodData().setFoodLevel(20);
    }

    /** Stands {@code friend} three and a half blocks from the bot along its line of fire to {@code target}. */
    private static void placeOnLineOfFire(ServerPlayer friend, AIPlayerEntity bot, LivingEntity target) {
        Vec3 eye = bot.getEyePosition();
        Vec3 aim = target.getBoundingBox().getCenter();
        Vec3 flat = new Vec3(aim.x - eye.x, 0.0D, aim.z - eye.z).normalize();
        Vec3 spot = new Vec3(bot.getX() + flat.x * 3.5D, bot.getY(), bot.getZ() + flat.z * 3.5D);
        friend.teleportTo(bot.level(), spot.x, spot.y, spot.z, Set.of(), 0.0F, 0.0F, true);
    }

    // ------------------------------------------------------------------ fixtures

    private static net.minecraft.world.entity.monster.skeleton.Skeleton spawnArmedSkeleton(GameTestHelper context,
                                                                                        BlockPos feet) {
        var skeleton = EntityType.SKELETON.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (skeleton == null) {
            context.fail(Component.nullToEmpty("failed to create the skeleton fixture"));
            throw new IllegalStateException("failed to create the skeleton fixture");
        }
        skeleton.setPersistenceRequired();
        skeleton.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        skeleton.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        skeleton.snapTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(skeleton);
        return skeleton;
    }

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

    /** Two-block-high retaining U for the live-arrow peekaboo fixture, open toward its east-facing shooters. */
    private static void buildPeekabooRetainingU(GameTestHelper context, BlockPos origin) {
        var world = context.getLevel();
        for (int dx = -4; dx <= 2; dx++) {
            setTwoHighStone(world, origin.offset(dx, 0, -3));
            setTwoHighStone(world, origin.offset(dx, 0, 3));
        }
        for (int dz = -2; dz <= 2; dz++) {
            setTwoHighStone(world, origin.offset(-5, 0, dz));
        }
    }

    private static void setTwoHighStone(net.minecraft.server.level.ServerLevel world, BlockPos feet) {
        world.setBlock(feet, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(feet.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
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
        return spawnCorridor(context, name, relativeY, minDx, maxDx, null);
    }

    private static AIPlayerEntity spawnCorridor(GameTestHelper context, String name, int relativeY,
                                                 int minDx, int maxDx, UUID owner) {
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
        return spawnBot(context, name, feet, owner);
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
