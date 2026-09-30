package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.EquipAction;
import io.github.zoyluo.minecraftai.action.HumanAim;
import io.github.zoyluo.minecraftai.action.InteractAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.action.RangedWeapon;
import io.github.zoyluo.minecraftai.action.StrikeLegality;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.GameTestChunkForcing;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Live proofs of the consolidated ranged-weapon layer (bow and crossbow through {@link RangedWeapon}) and of human aim
 * ({@link HumanAim}): natural vanilla rates and ammunition, the legal-shot guards, worst-first weapon choice, the turn-rate cap,
 * no shot before the aim settles, and a strike that lands only under the crosshair.
 *
 * <p>Every fixture uses real survival inventory, entities and tasks (a target with no AI stands still); each test owns its arena
 * height and its own test environment (batch), so the tests that change the global aim config never overlap another test.
 */
public final class RangedWeaponGameTests {
    private static final String ENV = "minecraftai-gametest:ranged_weapon_game_tests_";

    // ------------------------------------------------------------------ crossbow, natural rates

    @GameTest(environment = ENV + "crossbow_kills_a_zombie_at_eight_blocks_and_consumes_one_arrow_per_shot", maxTicks = 700)
    public void crossbowKillsAZombieAtEightBlocksAndConsumesOneArrowPerShot(GameTestHelper context) {
        killsAZombieAtEightBlocks(context, "XbowKillGT", 212, new ItemStack(Items.CROSSBOW), 24);
    }

    @GameTest(environment = ENV + "bow_kills_a_zombie_at_eight_blocks_and_consumes_one_arrow_per_shot", maxTicks = 900)
    public void bowKillsAZombieAtEightBlocksAndConsumesOneArrowPerShot(GameTestHelper context) {
        killsAZombieAtEightBlocks(context, "BowKillGT", 218, new ItemStack(Items.BOW), 24);
    }

    /**
     * A bot that carries only a ranged weapon and arrows kills a zombie eight blocks away. Arrows are really consumed: one per
     * shot for a bow (at the release), one per load for a crossbow (so the shots seen plus a shot still loaded make up the
     * arrows gone), and every consumed arrow is an arrow entity that flew.
     */
    private static void killsAZombieAtEightBlocks(GameTestHelper context, String name, int relativeY, ItemStack weapon,
                                                    int arrowCount) {
        AIPlayerEntity bot = spawnCorridor(context, name, relativeY, -4, 14, null);
        BlockPos origin = bot.blockPosition().immutable();
        bot.getInventory().clearContent();
        InventoryAction.giveItem(bot, weapon);
        InventoryAction.giveItem(bot, new ItemStack(Items.ARROW, arrowCount));
        Zombie zombie = spawnZombie(context, origin.east(8), false);
        int arrowsBefore = arrows(bot);
        CombatTask combat = new CombatTask(EntityType.ZOMBIE, 1, 6.0F);
        TaskManager.INSTANCE.assign(bot, combat, TaskOrigin.safety("gametest_ranged_kill"));

        Set<Integer> flown = new HashSet<>();
        boolean crossbow = weapon.is(Items.CROSSBOW);
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "the bot died: " + combat.describe());
            noteNewArrows(context, bot, flown);
            if (!zombie.isAlive()) {
                int consumed = arrowsBefore - arrows(bot);
                int loaded = crossbow && RangedWeapon.isLoaded(bot.getMainHandItem()) ? 1 : 0;
                require(context, flown.size() >= 2,
                        "the zombie died from only " + flown.size() + " arrow: it should take several");
                require(context, consumed == flown.size() + loaded,
                        "arrows were not consumed one per shot: consumed=" + consumed + " flown=" + flown.size()
                                + " loaded=" + loaded);
                despawnAndComplete(context, bot);
            }
        });
    }

    /**
     * A Quick Charge III crossbow shoots at the natural vanilla rate: the charge is 10 ticks instead of 25, plus the tick that
     * fires (about 12 ticks per shot), with no invented cap. A plain crossbow of the same kit is far slower (about 27).
     */
    @GameTest(environment = ENV + "quick_charge_iii_crossbow_fires_at_the_natural_rate", maxTicks = 260)
    public void quickChargeIiiCrossbowFiresAtTheNaturalRate(GameTestHelper context) {
        AIPlayerEntity bot = spawnCorridor(context, "QuickChargeGT", 224, -4, 14, null);
        BlockPos origin = bot.blockPosition().immutable();
        bot.getInventory().clearContent();
        InventoryAction.giveItem(bot, enchanted(context, Items.CROSSBOW, Enchantments.QUICK_CHARGE, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.ARROW, 64));
        Zombie zombie = spawnZombie(context, origin.east(10), true);
        CombatTask combat = new CombatTask(EntityType.ZOMBIE, 5, 6.0F);
        TaskManager.INSTANCE.assign(bot, combat, TaskOrigin.safety("gametest_quick_charge"));
        require(context, RangedWeapon.drawTicks(bot, findWeapon(bot)) == 10,
                "fixture: Quick Charge III must charge in 10 ticks, was "
                        + RangedWeapon.drawTicks(bot, findWeapon(bot)));

        Set<Integer> flown = new HashSet<>();
        List<Long> shotTicks = new java.util.ArrayList<>();
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "the bot died: " + combat.describe());
            int before = flown.size();
            noteNewArrows(context, bot, flown);
            if (flown.size() > before) {
                shotTicks.add(context.getTick());
            }
            if (shotTicks.size() >= 7) {
                for (int i = 1; i < shotTicks.size(); i++) {
                    long gap = shotTicks.get(i) - shotTicks.get(i - 1);
                    require(context, gap >= 10 && gap <= 14,
                            "shot gap " + gap + " ticks is not the natural ~12 of Quick Charge III: " + shotTicks);
                }
                zombie.discard();
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_quick_charge_done");
                despawnAndComplete(context, bot);
            } else if (context.getTick() >= 240) {
                context.fail(Component.nullToEmpty("only " + shotTicks.size() + " shots in 240 ticks: " + shotTicks
                        + " " + combat.describe()));
            }
        });
    }

    /**
     * A crossbow charged beforehand is held loaded and fires as soon as a legal shot appears: it holds while a glass pane hides the
     * zombie (no clear line), and shoots within a few ticks of the pane going away, with no arrow in the inventory.
     */
    @GameTest(environment = ENV + "preloaded_crossbow_fires_as_soon_as_the_target_is_in_legal_sight", maxTicks = 200)
    public void preloadedCrossbowFiresAsSoonAsTheTargetIsInLegalSight(GameTestHelper context) {
        AIPlayerEntity bot = spawnCorridor(context, "PreloadGT", 230, -4, 14, null);
        var world = context.getLevel();
        BlockPos origin = bot.blockPosition().immutable();
        bot.getInventory().clearContent();
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(new ItemStack(Items.ARROW)));
        InventoryAction.giveItem(bot, crossbow);
        Zombie zombie = spawnZombie(context, origin.east(9), true);
        BlockPos pane = origin.east(4);
        for (int dz = -2; dz <= 2; dz++) {
            for (int dy = 0; dy <= 3; dy++) {
                world.setBlock(pane.offset(0, dy, dz), Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        CombatTask combat = CombatTask.defensive(zombie, 6.0F, origin.east(4));
        TaskManager.INSTANCE.assign(bot, combat, TaskOrigin.safety("gametest_preloaded"));

        Set<Integer> flown = new HashSet<>();
        int[] openedAt = {-1};
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "the bot died: " + combat.describe());
            int tick = (int) context.getTick();
            noteNewArrows(context, bot, flown);
            if (openedAt[0] < 0) {
                require(context, flown.isEmpty(), "a shot was fired through the glass pane: " + combat.describe());
                require(context, RangedWeapon.isLoaded(bot.getMainHandItem()),
                        "the pre-loaded crossbow did not stay loaded behind the pane: " + combat.describe());
                if (tick >= 30) {
                    for (int dz = -2; dz <= 2; dz++) {
                        for (int dy = 0; dy <= 3; dy++) {
                            world.setBlock(pane.offset(0, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        }
                    }
                    openedAt[0] = tick;
                }
                return;
            }
            if (!flown.isEmpty()) {
                require(context, tick - openedAt[0] <= 4,
                        "the loaded crossbow took " + (tick - openedAt[0]) + " ticks to shoot after the line opened");
                zombie.discard();
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_preloaded_done");
                despawnAndComplete(context, bot);
            } else if (tick - openedAt[0] > 12) {
                context.fail(Component.nullToEmpty("no shot " + (tick - openedAt[0]) + " ticks after the line opened: "
                        + combat.describe()));
            }
        });
    }

    /** A Multishot crossbow uses one arrow and launches three (vanilla), all through the same item-use path. */
    @GameTest(environment = ENV + "multishot_crossbow_uses_one_arrow_and_launches_three", maxTicks = 200)
    public void multishotCrossbowUsesOneArrowAndLaunchesThree(GameTestHelper context) {
        AIPlayerEntity bot = spawnCorridor(context, "MultishotGT", 236, -4, 14, null);
        BlockPos origin = bot.blockPosition().immutable();
        bot.getInventory().clearContent();
        InventoryAction.giveItem(bot, enchanted(context, Items.CROSSBOW, Enchantments.MULTISHOT, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.ARROW, 5));
        Zombie zombie = spawnZombie(context, origin.east(8), true);
        CombatTask combat = new CombatTask(EntityType.ZOMBIE, 5, 6.0F);
        TaskManager.INSTANCE.assign(bot, combat, TaskOrigin.safety("gametest_multishot"));

        Set<Integer> flown = new HashSet<>();
        int[] volleyTick = {-1};
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "the bot died: " + combat.describe());
            noteNewArrows(context, bot, flown);
            int tick = (int) context.getTick();
            if (volleyTick[0] < 0 && !flown.isEmpty()) {
                volleyTick[0] = tick;
                require(context, flown.size() == 3, "the Multishot volley launched " + flown.size() + " arrows, not 3");
                require(context, arrows(bot) == 4, "the volley used " + (5 - arrows(bot)) + " arrows, not 1");
            } else if (volleyTick[0] >= 0 && tick >= volleyTick[0] + 3) {
                require(context, flown.size() == 3, "more than one volley in three ticks: " + flown.size());
                zombie.discard();
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_multishot_done");
                despawnAndComplete(context, bot);
            } else if (volleyTick[0] < 0 && tick >= 150) {
                context.fail(Component.nullToEmpty("no Multishot volley in 150 ticks: " + combat.describe()));
            }
        });
    }

    /** Infinity on a bow keeps the plain arrows, exactly as vanilla does: the shots are real, the stack does not shrink. */
    @GameTest(environment = ENV + "infinity_bow_keeps_its_plain_arrows_as_vanilla_does", maxTicks = 300)
    public void infinityBowKeepsItsPlainArrowsAsVanillaDoes(GameTestHelper context) {
        AIPlayerEntity bot = spawnCorridor(context, "InfinityGT", 242, -4, 14, null);
        BlockPos origin = bot.blockPosition().immutable();
        bot.getInventory().clearContent();
        InventoryAction.giveItem(bot, enchanted(context, Items.BOW, Enchantments.INFINITY, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.ARROW, 4));
        Zombie zombie = spawnZombie(context, origin.east(8), true);
        CombatTask combat = new CombatTask(EntityType.ZOMBIE, 5, 6.0F);
        TaskManager.INSTANCE.assign(bot, combat, TaskOrigin.safety("gametest_infinity"));
        Set<Integer> flown = new HashSet<>();
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "the bot died: " + combat.describe());
            noteNewArrows(context, bot, flown);
            require(context, arrows(bot) == 4, "an Infinity bow used up plain arrows: " + arrows(bot));
            if (flown.size() >= 3) {
                zombie.discard();
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_infinity_done");
                despawnAndComplete(context, bot);
            } else if (context.getTick() >= 270) {
                context.fail(Component.nullToEmpty("only " + flown.size() + " Infinity shots: " + combat.describe()));
            }
        });
    }

    // ------------------------------------------------------------------ legal shots, friendly fire

    /**
     * With the owner standing on the line of fire the crossbow is never fired: it loads, holds (loaded), and is given up by the
     * ranged hold. No arrow entity may ever exist. (The same guard is proven for the bow by
     * {@code combat_hardening_game_tests_friendly_owner_on_the_line_of_fire_gives_the_bow_up_without_a_shot}.)
     */
    @GameTest(environment = ENV + "no_crossbow_shot_while_the_owner_stands_in_the_line_of_fire", maxTicks = 320)
    public void noCrossbowShotWhileTheOwnerStandsInTheLineOfFire(GameTestHelper context) {
        ServerPlayer owner = context.makeMockServerPlayerInLevel();
        AIPlayerEntity bot = spawnCorridor(context, "FriendXbowGT", 248, -4, 16, owner.getUUID());
        var world = context.getLevel();
        BlockPos origin = bot.blockPosition().immutable();
        bot.getInventory().clearContent();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.CROSSBOW));
        InventoryAction.giveItem(bot, new ItemStack(Items.ARROW, 16));
        Zombie zombie = spawnZombie(context, origin.east(12), true);
        placeOwner(owner, world, origin.getX() + 6.5D, origin);
        require(context, StrikeLegality.friendlyOnLineOfFire(bot, zombie), "fixture: the owner is not on the line of fire");
        CombatTask combat = new CombatTask(EntityType.ZOMBIE, 1, 6.0F);
        TaskManager.INSTANCE.assign(bot, combat, TaskOrigin.safety("gametest_friend_xbow"));

        boolean[] sawLoaded = {false};
        int[] giveUpTick = {-1};
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "the bot died: " + combat.describe());
            require(context, botArrows(context, bot) == 0,
                    "an arrow was fired with the owner on the line of fire: " + combat.describe());
            if (giveUpTick[0] < 0) {
                placeOwner(owner, world, origin.getX() + 6.5D, origin);
                if (RangedWeapon.isLoaded(bot.getMainHandItem())) {
                    sawLoaded[0] = true;
                }
                if (combat.isRangedSuppressed()) {
                    giveUpTick[0] = (int) context.getTick();
                    require(context, sawLoaded[0], "the crossbow was given up without ever being loaded");
                    require(context, !combat.describe().contains("phase=RANGED"),
                            "the bot did not fall back from the ranged phase: " + combat.describe());
                }
                if (context.getTick() >= 290) {
                    context.fail(Component.nullToEmpty("the crossbow was never given up: sawLoaded=" + sawLoaded[0]
                            + " " + combat.describe()));
                }
            } else if (context.getTick() >= giveUpTick[0] + 25) {
                zombie.discard();
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_friend_xbow_done");
                despawnAndComplete(context, bot);
            }
        });
    }

    /**
     * A Multishot volley also flies ten degrees to either side, so the owner standing on a SIDE arrow's line blocks the shot even
     * though the middle arrow would miss them (the guard is widened per weapon). Fixture proof plus a live hold.
     */
    @GameTest(environment = ENV + "multishot_volley_is_not_fired_with_the_owner_on_a_side_arrow_line", maxTicks = 200)
    public void multishotVolleyIsNotFiredWithTheOwnerOnASideArrowLine(GameTestHelper context) {
        ServerPlayer owner = context.makeMockServerPlayerInLevel();
        AIPlayerEntity bot = spawnCorridor(context, "FriendMultishotGT", 254, -4, 16, owner.getUUID());
        var world = context.getLevel();
        BlockPos origin = bot.blockPosition().immutable();
        bot.getInventory().clearContent();
        InventoryAction.giveItem(bot, enchanted(context, Items.CROSSBOW, Enchantments.MULTISHOT, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.ARROW, 16));
        Zombie zombie = spawnZombie(context, origin.east(12), true);
        // 8 blocks along the east line, 10 degrees off it: the middle arrow passes to the side, a side arrow does not.
        double along = 8.0D;
        double side = along * Math.tan(Math.toRadians(RangedWeapon.MULTISHOT_SPREAD_DEG));
        owner.teleportTo(world, origin.getX() + 0.5D + along, origin.getY(), origin.getZ() + 0.5D + side,
                Set.of(), 0.0F, 0.0F, true);
        require(context, !StrikeLegality.friendlyOnLineOfFire(bot, zombie),
                "fixture: the owner must be clear of the middle arrow's line");
        require(context, StrikeLegality.friendlyOnLineOfFire(bot, zombie, RangedWeapon.MULTISHOT_SPREAD_DEG, false),
                "fixture: the owner must be on a side arrow's line");
        CombatTask combat = new CombatTask(EntityType.ZOMBIE, 1, 6.0F);
        TaskManager.INSTANCE.assign(bot, combat, TaskOrigin.safety("gametest_friend_multishot"));
        boolean[] sawLoaded = {false};
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "the bot died: " + combat.describe());
            require(context, botArrows(context, bot) == 0, "a volley was fired with the owner on a side arrow's line");
            owner.teleportTo(world, origin.getX() + 0.5D + along, origin.getY(), origin.getZ() + 0.5D + side,
                    Set.of(), 0.0F, 0.0F, true);
            sawLoaded[0] |= RangedWeapon.isLoaded(bot.getMainHandItem());
            if (context.getTick() >= 120) {
                require(context, sawLoaded[0], "fixture: the crossbow never loaded, so the guard was not exercised");
                zombie.discard();
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_friend_multishot_done");
                despawnAndComplete(context, bot);
            }
        });
    }

    // ------------------------------------------------------------------ weapon choice

    /**
     * Best-first between a bow and a crossbow (both are non-tools): with both able to fire, the better one by GearValue is chosen
     * (enchantments count), whichever kind it is; a weapon that cannot do the job (no ammunition for it) yields to the other. Wear
     * never does: the chosen weapon is used until it breaks, then the next best one takes over.
     */
    @GameTest(environment = ENV + "best_first_chooses_between_a_bow_and_a_crossbow", maxTicks = 40)
    public void bestFirstChoosesBetweenABowAndACrossbow(GameTestHelper context) {
        AIPlayerEntity bot = spawnCorridor(context, "GearRangedGT", 260, -4, 4, null);
        Zombie zombie = spawnZombie(context, bot.blockPosition().east(8), true);

        // 1. A Quick Charge III crossbow beats a plain bow.
        setKit(bot, new ItemStack(Items.BOW), enchanted(context, Items.CROSSBOW, Enchantments.QUICK_CHARGE, 3),
                new ItemStack(Items.ARROW, 8));
        require(context, chosen(bot, zombie).is(Items.CROSSBOW), "a Quick Charge III crossbow lost to a plain bow: " + chosen(bot, zombie));
        // 2. A Power V bow beats a plain crossbow.
        setKit(bot, enchanted(context, Items.BOW, Enchantments.POWER, 5), new ItemStack(Items.CROSSBOW),
                new ItemStack(Items.ARROW, 8));
        require(context, chosen(bot, zombie).is(Items.BOW), "a Power V bow lost to a plain crossbow: " + chosen(bot, zombie));
        // 3. The best weapon is used until it breaks, however worn: a Power V bow at one use left still goes before a plain
        // crossbow; once it is broken (gone) the crossbow, the next best, takes over.
        ItemStack worn = enchanted(context, Items.BOW, Enchantments.POWER, 5);
        worn.setDamageValue(worn.getMaxDamage() - 1);
        setKit(bot, worn, new ItemStack(Items.CROSSBOW), new ItemStack(Items.ARROW, 8));
        require(context, chosen(bot, zombie).is(Items.BOW), "a Power V bow at one use left was skipped: " + chosen(bot, zombie));
        setKit(bot, new ItemStack(Items.CROSSBOW), new ItemStack(Items.ARROW, 8));
        require(context, chosen(bot, zombie).is(Items.CROSSBOW), "the crossbow did not take over from the broken bow: " + chosen(bot, zombie));
        // 3b. Of two equal weapons the more worn goes first (it is used up, never set aside for the fresh one).
        ItemStack wornPlain = new ItemStack(Items.BOW);
        int wornDamage = wornPlain.getMaxDamage() - 1; // read before the kit takes the stack (a stack that was handed over is emptied)
        wornPlain.setDamageValue(wornDamage);
        setKit(bot, new ItemStack(Items.BOW), wornPlain, new ItemStack(Items.ARROW, 8));
        require(context, chosen(bot, zombie).getDamageValue() == wornDamage,
                "a fresh bow was used before the equal but worn one: " + chosen(bot, zombie).getDamageValue());
        // 4. No arrows at all: a bow cannot fire, a loaded crossbow can (its shot needs no ammunition).
        ItemStack loaded = new ItemStack(Items.CROSSBOW);
        loaded.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(new ItemStack(Items.ARROW)));
        setKit(bot, new ItemStack(Items.BOW), loaded);
        require(context, chosen(bot, zombie).is(Items.CROSSBOW) && RangedWeapon.isLoaded(chosen(bot, zombie)),
                "the loaded crossbow was not chosen over a bow with no arrows: " + chosen(bot, zombie));
        // 5. Nothing can fire: a bow and an empty crossbow with no arrows.
        setKit(bot, new ItemStack(Items.BOW), new ItemStack(Items.CROSSBOW));
        require(context, EquipAction.bestRangedSlot(bot, zombie).isEmpty(), "a weapon with no ammunition was chosen");
        // 6. A crossbow that holds a firework rocket is never used as a weapon.
        ItemStack rocket = new ItemStack(Items.CROSSBOW);
        rocket.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(new ItemStack(Items.FIREWORK_ROCKET)));
        setKit(bot, rocket, new ItemStack(Items.ARROW, 8));
        require(context, EquipAction.bestRangedSlot(bot, zombie).isEmpty(), "a rocket-loaded crossbow was chosen");
        // 7. Two plain weapons of the same value: a loaded crossbow (no draw needed) goes first.
        ItemStack loadedPlain = new ItemStack(Items.CROSSBOW);
        loadedPlain.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(new ItemStack(Items.ARROW)));
        setKit(bot, new ItemStack(Items.BOW), loadedPlain, new ItemStack(Items.ARROW, 8));
        require(context, RangedWeapon.isLoaded(chosen(bot, zombie)), "an equal-value loaded crossbow did not go first");
        zombie.discard();
        despawnAndComplete(context, bot);
    }

    /**
     * A bot runs out of arrows in the middle of a fight and falls back to its sword: the crossbow fires its two arrows, then the
     * bot walks up and finishes the zombie in melee.
     */
    @GameTest(environment = ENV + "a_bot_out_of_arrows_falls_back_to_melee", maxTicks = 700)
    public void aBotOutOfArrowsFallsBackToMelee(GameTestHelper context) {
        AIPlayerEntity bot = spawnCorridor(context, "OutOfArrowsGT", 266, -4, 14, null);
        BlockPos origin = bot.blockPosition().immutable();
        bot.getInventory().clearContent();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD));
        InventoryAction.giveItem(bot, new ItemStack(Items.CROSSBOW));
        InventoryAction.giveItem(bot, new ItemStack(Items.ARROW, 2));
        Zombie zombie = spawnZombie(context, origin.east(9), false);
        CombatTask combat = new CombatTask(EntityType.ZOMBIE, 1, 6.0F);
        TaskManager.INSTANCE.assign(bot, combat, TaskOrigin.safety("gametest_out_of_arrows"));

        Set<Integer> flown = new HashSet<>();
        boolean[] meleeAfterArrows = {false};
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "the bot died: " + combat.describe());
            noteNewArrows(context, bot, flown);
            if (arrows(bot) == 0 && !RangedWeapon.isLoaded(findWeapon(bot)) && bot.getMainHandItem().is(Items.STONE_SWORD)) {
                meleeAfterArrows[0] = true;
            }
            if (!zombie.isAlive()) {
                require(context, flown.size() == 2, "the two arrows should both have flown: " + flown.size());
                require(context, meleeAfterArrows[0],
                        "the bot never took its sword once the arrows were gone: main=" + bot.getMainHandItem());
                despawnAndComplete(context, bot);
            }
        });
    }

    // ------------------------------------------------------------------ human aim

    /**
     * The aim never turns faster than the configured rate (180 degrees per second here, 9 per tick): the zombie is moved to the
     * opposite side of the bot again and again, and no tick of the fight turns the view more than the cap, while shots still fly.
     */
    @GameTest(environment = ENV + "turn_rate_never_exceeds_the_configured_degrees_per_second", maxTicks = 330)
    public void turnRateNeverExceedsTheConfiguredDegreesPerSecond(GameTestHelper context) {
        MinecraftAiConfig previous = setAim(180.0D);
        try {
            AIPlayerEntity bot = spawnCorridor(context, "TurnRateGT", 272, -14, 14, null);
            BlockPos origin = bot.blockPosition().immutable();
            bot.getInventory().clearContent();
            InventoryAction.giveItem(bot, new ItemStack(Items.BOW));
            InventoryAction.giveItem(bot, new ItemStack(Items.ARROW, 32));
            Zombie zombie = spawnZombie(context, origin.east(8), true);
            CombatTask combat = new CombatTask(EntityType.ZOMBIE, 9, 6.0F);
            TaskManager.INSTANCE.assign(bot, combat, TaskOrigin.safety("gametest_turn_rate"));
            double cap = 180.0D / 20.0D;
            double[] last = {bot.getYRot(), bot.getXRot()};
            double[] total = {0.0D};
            int[] side = {1};
            Set<Integer> flown = new HashSet<>();
            context.failIfEver(() -> {
                try {
                    require(context, bot.isAlive(), "the bot died: " + combat.describe());
                    double moved = HumanAim.Core.angleBetween(last[0], last[1], bot.getYRot(), bot.getXRot());
                    require(context, moved <= cap + 0.01D,
                            "the view turned " + moved + " degrees in one tick, above the configured " + cap
                                    + " (tick " + context.getTick() + ", " + combat.describe() + ")");
                    total[0] += moved;
                    last[0] = bot.getYRot();
                    last[1] = bot.getXRot();
                    noteNewArrows(context, bot, flown);
                    int tick = (int) context.getTick();
                    if (tick > 0 && tick % 60 == 0) {
                        // A 180 degree flick: the zombie is on the other side of the bot now.
                        side[0] = -side[0];
                        zombie.snapTo(origin.getX() + 0.5D + 8.0D * side[0], origin.getY(), origin.getZ() + 0.5D, 90.0F, 0.0F);
                    }
                    if (tick >= 300) {
                        require(context, total[0] >= 300.0D, "fixture: the aim only turned " + total[0] + " degrees");
                        require(context, flown.size() >= 2, "no shots flew while turning: " + flown.size());
                        zombie.discard();
                        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_turn_rate_done");
                        restoreAim(previous);
                        despawnAndComplete(context, bot);
                    }
                } catch (RuntimeException failure) {
                    restoreAim(previous);
                    throw failure;
                }
            });
        } catch (RuntimeException failure) {
            restoreAim(previous);
            throw failure;
        }
    }

    /**
     * No shot before the aim settles: with a crossbow already loaded and the zombie directly BEHIND the bot, at 90 degrees per
     * second (4.5 per tick) the half turn takes 40 ticks. Not one arrow may fly before it is done, and the first arrow leaves
     * along the zombie's direction.
     */
    @GameTest(environment = ENV + "no_shot_before_the_aim_settles_on_the_target", maxTicks = 160)
    public void noShotBeforeTheAimSettlesOnTheTarget(GameTestHelper context) {
        MinecraftAiConfig previous = setAim(90.0D);
        try {
            AIPlayerEntity bot = spawnCorridor(context, "AimSettleGT", 278, -14, 14, null);
            BlockPos origin = bot.blockPosition().immutable();
            bot.getInventory().clearContent();
            ItemStack crossbow = new ItemStack(Items.CROSSBOW);
            crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(new ItemStack(Items.ARROW)));
            InventoryAction.giveItem(bot, crossbow);
            // The bot faces west (yaw 90); the zombie stands east, 180 degrees behind it.
            LookAction.setYawPitch(bot, 90.0F, 0.0F);
            Zombie zombie = spawnZombie(context, origin.east(8), true);
            CombatTask combat = new CombatTask(EntityType.ZOMBIE, 1, 6.0F);
            TaskManager.INSTANCE.assign(bot, combat, TaskOrigin.safety("gametest_aim_settle"));
            Set<Integer> flown = new HashSet<>();
            float[] yawAtShot = {Float.NaN};
            context.failIfEver(() -> {
                try {
                    require(context, bot.isAlive(), "the bot died: " + combat.describe());
                    int before = flown.size();
                    noteNewArrows(context, bot, flown);
                    int tick = (int) context.getTick();
                    if (flown.size() > before) {
                        yawAtShot[0] = bot.getYRot();
                        require(context, tick >= 38, "an arrow flew at tick " + tick
                                + ", before the 180 degree turn at 90 degrees per second could be done (40 ticks)");
                        double desiredYaw = HumanAim.yawTo(bot.getEyePosition(), zombie.getBoundingBox().getCenter());
                        double yawError = Math.abs(net.minecraft.util.Mth.wrapDegrees(bot.getYRot() - desiredYaw));
                        require(context, yawError <= 3.0D, "the arrow left " + yawError + " degrees off the target");
                        zombie.discard();
                        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_aim_settle_done");
                        restoreAim(previous);
                        despawnAndComplete(context, bot);
                    } else if (tick >= 140) {
                        context.fail(Component.nullToEmpty("no shot in 140 ticks: " + combat.describe()
                                + " yaw=" + bot.getYRot()));
                    }
                } catch (RuntimeException failure) {
                    restoreAim(previous);
                    throw failure;
                }
            });
        } catch (RuntimeException failure) {
            restoreAim(previous);
            throw failure;
        }
    }

    /**
     * A melee strike lands only on what is under the crosshair (vanilla's pick along the real look vector within its attack range):
     * with the target 90 degrees off the view the strikes fail for the ticks the human-speed turn takes and land only once the
     * crosshair is on the target; and a legal target standing behind another one is not struck through it.
     */
    @GameTest(environment = ENV + "melee_only_hits_what_is_under_the_crosshair", maxTicks = 80)
    public void meleeOnlyHitsWhatIsUnderTheCrosshair(GameTestHelper context) {
        AIPlayerEntity bot = spawnCorridor(context, "CrosshairGT", 284, -6, 6, null);
        BlockPos origin = bot.blockPosition().immutable();
        bot.getInventory().clearContent();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD));
        Husk far = spawnHusk(context, origin.east(2));
        Husk near = spawnHusk(context, origin.west(2));
        int[] attempt = {0};
        int[] phase = {0};
        boolean[] hit = {false};
        LookAction.setYawPitch(bot, 0.0F, 0.0F); // facing south: the husk to the east is 90 degrees off the view
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "the bot died");
            if (phase[0] == 0) {
                // Turning toward the husk to the east at 27 degrees per tick: the first strikes must be refused.
                float before = far.getHealth();
                attempt[0]++;
                ActionResult result = InteractAction.attackEntity(bot, far);
                boolean under = HumanAim.isUnderCrosshair(bot, far);
                if (result.isSuccess()) {
                    require(context, under, "a strike landed with the target not under the crosshair");
                    require(context, attempt[0] >= 3,
                            "the 90 degree turn took only " + attempt[0] + " ticks at 540 degrees per second");
                    require(context, far.getHealth() < before, "the strike landed but did no damage");
                    phase[0] = 1;
                } else {
                    require(context, InteractAction.NOT_UNDER_CROSSHAIR.equals(result.reason()),
                            "an unexpected refusal: " + result.reason());
                    require(context, far.getHealth() == before && near.getHealth() == near.getMaxHealth(),
                            "a refused strike still hurt something");
                    require(context, attempt[0] <= 8, "the crosshair never reached the target");
                }
                return;
            }
            if (phase[0] == 1) {
                // A legal target (in reach, clear line) BEHIND another husk: the crosshair holds the nearer one, so it is refused.
                far.snapTo(origin.getX() + 0.5D + 2.6D, origin.getY(), origin.getZ() + 0.5D, 90.0F, 0.0F);
                near.snapTo(origin.getX() + 0.5D + 1.4D, origin.getY(), origin.getZ() + 0.5D, 90.0F, 0.0F);
                LookAction.setYawPitch(bot, -90.0F, 0.0F); // facing east, straight at both
                phase[0] = 2;
                return;
            }
            if (phase[0] == 2) {
                float farBefore = far.getHealth();
                float nearBefore = near.getHealth();
                require(context, StrikeLegality.strikeRefusal(bot, far) == null,
                        "fixture: the far husk must be a legal strike (reach and line of sight)");
                ActionResult behind = InteractAction.attackEntity(bot, far);
                require(context, behind.isFailed() && InteractAction.NOT_UNDER_CROSSHAIR.equals(behind.reason())
                                && far.getHealth() == farBefore,
                        "a strike went through the nearer husk: " + behind.status() + ":" + behind.reason());
                require(context, HumanAim.crosshairEntity(bot) == near,
                        "fixture: the crosshair should hold the nearer husk: " + HumanAim.crosshairEntity(bot));
                ActionResult front = InteractAction.attackEntity(bot, near);
                require(context, front.isSuccess() && near.getHealth() < nearBefore,
                        "the husk under the crosshair was not struck: " + front.status() + ":" + front.reason());
                hit[0] = true;
                far.discard();
                near.discard();
                despawnAndComplete(context, bot);
            }
        });
    }

    // ------------------------------------------------------------------ fixtures

    private static ItemStack chosen(AIPlayerEntity bot, Zombie target) {
        OptionalInt slot = EquipAction.bestRangedSlot(bot, target);
        return slot.isPresent() ? bot.getInventory().getNonEquipmentItems().get(slot.getAsInt()) : ItemStack.EMPTY;
    }

    private static void setKit(AIPlayerEntity bot, ItemStack... stacks) {
        bot.getInventory().clearContent();
        for (ItemStack stack : stacks) {
            InventoryAction.giveItem(bot, stack);
        }
    }

    private static ItemStack findWeapon(AIPlayerEntity bot) {
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (RangedWeapon.isRanged(stack)) {
                return stack;
            }
        }
        return bot.getMainHandItem();
    }

    private static ItemStack enchanted(GameTestHelper context, Item item, ResourceKey<Enchantment> key, int level) {
        var enchantments = context.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        ItemStack stack = new ItemStack(item);
        stack.enchant(enchantments.get(key.identifier()).orElseThrow(), level);
        return stack;
    }

    /** Arrows the bot itself loosed, counted once each however long they stay in the world. */
    private static void noteNewArrows(GameTestHelper context, AIPlayerEntity bot, Set<Integer> flown) {
        for (AbstractArrow arrow : context.getLevel().getEntitiesOfClass(AbstractArrow.class,
                bot.getBoundingBox().inflate(64.0D), candidate -> candidate.getOwner() == bot)) {
            flown.add(arrow.getId());
        }
    }

    private static int botArrows(GameTestHelper context, AIPlayerEntity bot) {
        return context.getLevel().getEntitiesOfClass(AbstractArrow.class,
                bot.getBoundingBox().inflate(64.0D), arrow -> arrow.getOwner() == bot).size();
    }

    /** Arrows the bot carries, the offhand included (InventoryAction.countItem already counts the offhand). */
    private static int arrows(AIPlayerEntity bot) {
        return InventoryAction.countItem(bot, Items.ARROW);
    }

    private static void placeOwner(ServerPlayer owner, net.minecraft.server.level.ServerLevel world, double x, BlockPos origin) {
        owner.teleportTo(world, x, origin.getY(), origin.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
    }

    /** Swaps the global aim rate (degrees per second) and returns the config it replaced. */
    private static MinecraftAiConfig setAim(double degreesPerSecond) {
        MinecraftAiConfig old = MinecraftAiConfig.get();
        MinecraftAiConfig.Behaviour b = old.behaviour();
        MinecraftAiConfig next = old.withBehaviour(new MinecraftAiConfig.Behaviour(b.pace(), b.targeting(), b.gear(),
                b.follow(), b.warden(), new MinecraftAiConfig.CombatBehaviour(new MinecraftAiConfig.Aim(degreesPerSecond))));
        installConfig(next);
        return old;
    }

    private static void restoreAim(MinecraftAiConfig previous) {
        installConfig(previous);
    }

    private static void installConfig(MinecraftAiConfig config) {
        try {
            Field instance = MinecraftAiConfig.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, config);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot swap the test config", failure);
        }
    }

    private static Zombie spawnZombie(GameTestHelper context, BlockPos feet, boolean invulnerable) {
        Zombie zombie = EntityType.ZOMBIE.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (zombie == null) {
            context.fail(Component.nullToEmpty("failed to create the zombie fixture"));
            throw new IllegalStateException("failed to create the zombie fixture");
        }
        zombie.setPersistenceRequired();
        zombie.setNoAi(true);
        // A helmet keeps the daylight off it (a zombie burns in the sun).
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        zombie.setInvulnerable(invulnerable);
        zombie.snapTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 90.0F, 0.0F);
        context.getLevel().addFreshEntity(zombie);
        return zombie;
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

    /** A five-wide stone strip {@code minDx..maxDx} blocks either side of the bot, three blocks of air above. */
    private static AIPlayerEntity spawnCorridor(GameTestHelper context, String name, int relativeY,
                                                 int minDx, int maxDx, UUID owner) {
        var world = context.getLevel();
        world.setDayTime(1000L);
        BlockPos feet = context.absolutePos(new BlockPos(3, relativeY, 3));
        // A mob only ticks (its hurt cooldown, its knockback) in a loaded chunk that is entity-ticking: force every chunk of the
        // corridor, or a fixture standing a few blocks over a chunk border stops ticking (and stops taking damage after its first hit).
        GameTestChunkForcing.forceForTest(context, (feet.getX() + minDx - 2) >> 4, (feet.getX() + maxDx + 2) >> 4,
                (feet.getZ() - 4) >> 4, (feet.getZ() + 4) >> 4);
        for (int dx = minDx; dx <= maxDx; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlock(cell.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
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
}
