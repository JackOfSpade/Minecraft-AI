package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.EquipAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Set;

/**
 * Live bug (session 20260928-230626, bot Moss): the player asked the bot to make tool sets "one
 * for you that you keep and give one set for me". None of ToolRegistry's ~59 tools could hand an
 * item to a player (deposit/withdraw only move items in/out of containers, trade only works with
 * villagers), so the model stalled on repeated say(purpose=plan) calls until
 * model_call_budget_exhausted. The recipient here is another bot (a real, strict-survival
 * Player target), per the fix's own suggestion for exercising this without a human tester.
 */
public final class GiveItemTaskGameTests {
    @GameTest(maxTicks = 300)
    public void recipientEndsUpHoldingExactlyTheGivenItemsAndGiverInventoryDecreasesByThatCount(
            GameTestHelper context) {
        Fixture fixture = spawnGiverAndRecipient(context, new BlockPos(6, 4, 6), "HoldingGT");
        AIPlayerEntity giver = fixture.giver();
        AIPlayerEntity recipient = fixture.recipient();
        InventoryAction.giveItem(giver, new ItemStack(Items.STONE_PICKAXE, 1));
        InventoryAction.giveItem(giver, new ItemStack(Items.DIRT, 4)); // untouched control stack
        int giverBefore = InventoryAction.countItem(giver, Items.STONE_PICKAXE);

        GiveItemTask task = new GiveItemTask(Items.STONE_PICKAXE, 1, fixture.recipientName());
        task.start(giver);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(giver);
                return;
            }
            require(context, task.state() == TaskState.COMPLETED,
                    "give_item did not complete: " + task.failureReason());
            require(context, InventoryAction.countItem(giver, Items.STONE_PICKAXE)
                            == giverBefore - 1,
                    "giver's inventory did not decrease by exactly the given count");
            require(context, InventoryAction.countItem(giver, Items.DIRT) == 4,
                    "give_item touched an unrelated stack");
            boolean recipientHolding = InventoryAction.countItem(recipient, Items.STONE_PICKAXE) >= 1;
            boolean droppedNearRecipient = !nearbyItemEntities(context, recipient, Items.STONE_PICKAXE).isEmpty();
            require(context, recipientHolding || droppedNearRecipient,
                    "recipient ended up neither holding the given item nor standing next to a "
                            + "dropped entity of it");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 60)
    public void insufficientInventoryFailsWithAClearReasonAndTouchesNothing(GameTestHelper context) {
        Fixture fixture = spawnGiverAndRecipient(context, new BlockPos(16, 4, 6), "InsufficientGT");
        AIPlayerEntity giver = fixture.giver();
        InventoryAction.giveItem(giver, new ItemStack(Items.STONE_PICKAXE, 1));

        GiveItemTask task = new GiveItemTask(Items.STONE_PICKAXE, 3, fixture.recipientName());
        task.start(giver);
        for (int tick = 0; tick < 5 && task.state() == TaskState.RUNNING; tick++) {
            task.tick(giver);
        }

        require(context, task.state() == TaskState.FAILED,
                "give_item with insufficient inventory should fail, not " + task.state());
        require(context, "need: minecraft:stone_pickaxe x3".equals(task.failureReason()),
                "expected a clear insufficient-inventory reason, got: " + task.failureReason());
        require(context, InventoryAction.countItem(giver, Items.STONE_PICKAXE) == 1,
                "a failed give must not touch the giver's inventory");
        cleanup(context, fixture);
    }

    @GameTest(maxTicks = 60)
    public void freshDeliveryGuardFailsBeforeAnyCobblestoneDropOrRouteWork(GameTestHelper context) {
        Fixture fixture = spawnGiverAndRecipient(context, new BlockPos(24, 4, 6), "FreshGuardGT");
        AIPlayerEntity giver = fixture.giver();
        InventoryAction.giveItem(giver, new ItemStack(Items.COBBLESTONE, 64));

        GiveItemTask task = new GiveItemTask(Items.COBBLESTONE, 32, fixture.recipientName(),
                () -> true, true, () -> false);
        task.start(giver);
        task.tick(giver);

        require(context, task.state() == TaskState.FAILED,
                "failed fresh conservation guard must stop the handoff before pathing/drop");
        require(context, "give_item_fresh_quota_lost".equals(task.failureReason()),
                "fresh guard failure reason was not typed: " + task.failureReason());
        require(context, InventoryAction.countItem(giver, Items.COBBLESTONE) == 64,
                "fresh guard failure must not debit protected cobblestone");
        cleanup(context, fixture);
    }

    /**
     * A freshly crafted chestplate is worn by the armor auto-equip pass before the Give step
     * starts, while the planner and the fresh-delivery guard count it as held.  The handoff must
     * take it off the way a player does, not fail with "need" and be replanned identically.
     */
    @GameTest(maxTicks = 300)
    public void wornArmorIsTakenOffAndHandedOver(GameTestHelper context) {
        Fixture fixture = spawnGiverAndRecipient(context, new BlockPos(6, 4, 6), "WornArmorGT");
        AIPlayerEntity giver = fixture.giver();
        AIPlayerEntity recipient = fixture.recipient();
        InventoryAction.giveItem(giver, new ItemStack(Items.IRON_CHESTPLATE, 1));
        require(context, EquipAction.autoEquipArmor(giver) == 1
                        && giver.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE),
                "setup: the auto-equip pass should have worn the carried chestplate");
        require(context, InventoryAction.countItem(giver, Items.IRON_CHESTPLATE) == 0,
                "setup: a worn chestplate is not in the main inventory");

        GiveItemTask task = new GiveItemTask(Items.IRON_CHESTPLATE, 1, fixture.recipientName(),
                () -> true, false, true, () -> true);
        task.start(giver);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(giver);
                return;
            }
            require(context, task.state() == TaskState.COMPLETED,
                    "handing over a worn chestplate did not complete: " + task.failureReason());
            require(context, giver.getItemBySlot(EquipmentSlot.CHEST).isEmpty(),
                    "the delivered chestplate is still worn");
            require(context, InventoryAction.countGiveable(giver, Items.IRON_CHESTPLATE) == 0,
                    "the giver still holds a chestplate after handing its only one over");
            require(context, EquipAction.autoEquipArmor(giver) == 0,
                    "nothing is left for the auto-equip pass to put back on");
            require(context, InventoryAction.countGiveable(recipient, Items.IRON_CHESTPLATE) >= 1
                            || !nearbyItemEntities(context, recipient, Items.IRON_CHESTPLATE).isEmpty(),
                    "the chestplate reached neither the recipient nor the ground beside it");
            cleanup(context, fixture);
        });
    }

    /** Vanilla never lets a survival player take Curse of Binding armor off: say so with a typed reason. */
    @GameTest(maxTicks = 60)
    public void wornBindingCurseArmorFailsWithATypedReasonAndStaysWorn(GameTestHelper context) {
        Fixture fixture = spawnGiverAndRecipient(context, new BlockPos(6, 4, 6), "CursedGT");
        AIPlayerEntity giver = fixture.giver();
        ItemStack cursed = new ItemStack(Items.IRON_CHESTPLATE);
        cursed.enchant(enchantment(context, Enchantments.BINDING_CURSE), 1);
        giver.setItemSlot(EquipmentSlot.CHEST, cursed);

        GiveItemTask task = new GiveItemTask(Items.IRON_CHESTPLATE, 1, fixture.recipientName());
        task.start(giver);
        task.tick(giver);

        require(context, task.state() == TaskState.FAILED
                        && GiveItemTask.WORN_PIECE_LOCKED.equals(task.failureReason()),
                "a cursed worn piece should fail as " + GiveItemTask.WORN_PIECE_LOCKED
                        + ", not " + task.state() + " " + task.failureReason());
        require(context, giver.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE),
                "the cursed chestplate must stay on");
        cleanup(context, fixture);
    }

    /**
     * The fresh stack is chosen on purpose: the old, damaged, enchanted or nearly broken copy
     * the bot already carried must stay, whatever its slot.  Slot order still rules a delivery
     * that is not fresh, and fungible raw materials either way.
     */
    @GameTest(maxTicks = 40)
    public void freshDeliveryDropsTheNewestStackWhileOtherDeliveriesKeepSlotOrder(GameTestHelper context) {
        Fixture fixture = spawnGiverAndRecipient(context, new BlockPos(6, 4, 6), "StackChoiceGT");
        AIPlayerEntity giver = fixture.giver();

        ItemStack damaged = new ItemStack(Items.IRON_PICKAXE);
        damaged.setDamageValue(100);
        placeInSlot(giver, 0, damaged);
        placeInSlot(giver, 1, new ItemStack(Items.IRON_PICKAXE));
        require(context, InventoryAction.dropItems(giver, Items.IRON_PICKAXE, 1, true),
                "fresh drop failed");
        require(context, onlyPickaxeDamage(giver) == 100,
                "a fresh delivery shipped the damaged pickaxe and kept the new one");

        giver.getInventory().clearContent();
        placeInSlot(giver, 0, damaged.copy());
        placeInSlot(giver, 1, new ItemStack(Items.IRON_PICKAXE));
        require(context, InventoryAction.dropItems(giver, Items.IRON_PICKAXE, 1, false),
                "ordinary drop failed");
        require(context, onlyPickaxeDamage(giver) == 0,
                "a delivery that is not fresh must keep taking the first slot");

        giver.getInventory().clearContent();
        ItemStack enchanted = new ItemStack(Items.IRON_PICKAXE);
        enchanted.enchant(enchantment(context, Enchantments.EFFICIENCY), 3);
        placeInSlot(giver, 0, enchanted);
        placeInSlot(giver, 1, new ItemStack(Items.IRON_PICKAXE));
        require(context, InventoryAction.dropItems(giver, Items.IRON_PICKAXE, 1, true),
                "fresh drop of the plain pickaxe failed");
        require(context, onlyPickaxe(giver).isEnchanted(),
                "a fresh delivery shipped the enchanted pickaxe the bot already carried");

        giver.getInventory().clearContent();
        ItemStack nearlyBroken = new ItemStack(Items.IRON_PICKAXE);
        nearlyBroken.setDamageValue(nearlyBroken.getMaxDamage() - 1);
        placeInSlot(giver, 0, nearlyBroken);
        placeInSlot(giver, 5, new ItemStack(Items.IRON_PICKAXE));
        require(context, InventoryAction.dropItems(giver, Items.IRON_PICKAXE, 1, true),
                "fresh drop with a nearly broken copy failed");
        require(context, onlyPickaxeDamage(giver) == nearlyBroken.getDamageValue(),
                "a fresh delivery shipped the nearly broken pickaxe, which the guard does not even count");

        giver.getInventory().clearContent();
        giver.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        placeInSlot(giver, 1, damagedChestplate());
        require(context, InventoryAction.dropItems(giver, Items.IRON_CHESTPLATE, 1, true),
                "fresh drop of a worn chestplate failed");
        require(context, giver.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                        && InventoryAction.countItem(giver, Items.IRON_CHESTPLATE) == 1,
                "a fresh delivery kept the new worn chestplate and shipped the damaged one in the inventory");
        cleanup(context, fixture);
    }

    /** The task carries the fresh flag through to the drop: the new pickaxe leaves, the old one stays. */
    @GameTest(maxTicks = 300)
    public void freshGiveTaskHandsOverTheNewPickaxeAndKeepsTheDamagedOne(GameTestHelper context) {
        Fixture fixture = spawnGiverAndRecipient(context, new BlockPos(6, 4, 6), "FreshTaskGT");
        AIPlayerEntity giver = fixture.giver();
        ItemStack damaged = new ItemStack(Items.IRON_PICKAXE);
        damaged.setDamageValue(100);
        placeInSlot(giver, 0, damaged);
        placeInSlot(giver, 1, new ItemStack(Items.IRON_PICKAXE));

        GiveItemTask task = new GiveItemTask(Items.IRON_PICKAXE, 1, fixture.recipientName(),
                () -> true, false, true, () -> true);
        task.start(giver);
        context.failIfEver(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(giver);
                return;
            }
            require(context, task.state() == TaskState.COMPLETED,
                    "fresh give did not complete: " + task.failureReason());
            require(context, onlyPickaxeDamage(giver) == 100,
                    "the fresh give shipped the damaged pickaxe the bot already carried");
            cleanup(context, fixture);
        });
    }

    private static ItemStack damagedChestplate() {
        ItemStack stack = new ItemStack(Items.IRON_CHESTPLATE);
        stack.setDamageValue(50);
        return stack;
    }

    private static void placeInSlot(AIPlayerEntity bot, int slot, ItemStack stack) {
        bot.getInventory().getNonEquipmentItems().set(slot, stack);
    }

    /** The one iron pickaxe left in the main inventory after a drop; fails the test run if there is not exactly one. */
    private static ItemStack onlyPickaxe(AIPlayerEntity bot) {
        List<ItemStack> held = bot.getInventory().getNonEquipmentItems().stream()
                .filter(stack -> stack.is(Items.IRON_PICKAXE)).toList();
        if (held.size() != 1) {
            throw new IllegalStateException("expected exactly one iron pickaxe left, found " + held.size());
        }
        return held.get(0);
    }

    private static int onlyPickaxeDamage(AIPlayerEntity bot) {
        return onlyPickaxe(bot).getDamageValue();
    }

    private static net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> enchantment(
            GameTestHelper context,
            net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key) {
        return context.getLevel().registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(key);
    }

    private static List<ItemEntity> nearbyItemEntities(
            GameTestHelper context, AIPlayerEntity near, net.minecraft.world.item.Item item) {
        AABB search = near.getBoundingBox().inflate(2.0D);
        return context.getLevel().getEntitiesOfClass(ItemEntity.class, search,
                entity -> entity.getItem().is(item));
    }

    private static Fixture spawnGiverAndRecipient(
            GameTestHelper context, BlockPos relativeFeet, String uniqueSuffix) {
        var world = context.getLevel();
        world.setDayTime(1000L);
        BlockPos feet = context.absolutePos(relativeFeet);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        // Distinct names per test method: Fabric GameTest batches can run different tests'
        // structures concurrently on the same server, and AIPlayerManager's bot-name registry is
        // server-global, so reusing a name across test methods races and fails to spawn.
        String giverName = "Give" + uniqueSuffix;
        String recipientName = "Recv" + uniqueSuffix;
        AIPlayerEntity giver = spawnBot(world, giverName, feet);
        AIPlayerEntity recipient = spawnBot(world, recipientName, feet.north(4));
        return new Fixture(giverName, recipientName, giver, recipient);
    }

    private static AIPlayerEntity spawnBot(
            net.minecraft.server.level.ServerLevel world, String name, BlockPos feet) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        return bot;
    }

    private static void cleanup(GameTestHelper context, Fixture fixture) {
        AIPlayerManager.INSTANCE.despawn(fixture.giver().level().getServer(), fixture.giverName());
        AIPlayerManager.INSTANCE.despawn(fixture.recipient().level().getServer(), fixture.recipientName());
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    private record Fixture(String giverName, String recipientName, AIPlayerEntity giver, AIPlayerEntity recipient) {
    }
}
