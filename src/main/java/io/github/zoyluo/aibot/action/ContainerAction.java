package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.LootableInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;
import java.util.function.Predicate;

public final class ContainerAction {
    private ContainerAction() {
    }

    public static Optional<Inventory> resolve(AIPlayerEntity bot, BlockPos pos) {
        BlockState state = bot.getServerWorld().getBlockState(pos);
        Block block = state.getBlock();
        if (block instanceof ChestBlock chestBlock) {
            Inventory inventory = ChestBlock.getInventory(chestBlock, state, bot.getServerWorld(), pos, true);
            if (inventory != null) {
                generateLoot(bot, inventory);
                return Optional.of(inventory);
            }
        }
        if (bot.getServerWorld().getBlockEntity(pos) instanceof Inventory inventory) {
            generateLoot(bot, inventory);
            return Optional.of(inventory);
        }
        return Optional.empty();
    }

    public static TransferResult depositOne(Inventory container,
                                            AIPlayerEntity bot,
                                            Predicate<ItemStack> filter,
                                            int maxItems) {
        if (maxItems <= 0) {
            return TransferResult.done();
        }
        PlayerTransfer source = findPlayerStack(bot, filter);
        if (source == null) {
            return TransferResult.failed("nothing_to_deposit");
        }
        int requested = Math.min(source.stack().getCount(), maxItems);
        Item item = source.stack().getItem();
        ItemStack moving = source.stack().copyWithCount(requested);
        int inserted = insert(container, moving);
        if (inserted <= 0) {
            return TransferResult.failed("container_full");
        }
        source.stack().decrement(inserted);
        bot.getInventory().markDirty();
        container.markDirty();
        BotLog.action(bot, "container_deposit", "item", item, "count", inserted);
        return TransferResult.moved(inserted);
    }

    public static TransferResult withdrawOne(Inventory container,
                                             AIPlayerEntity bot,
                                             Item item,
                                             int maxItems) {
        if (maxItems <= 0) {
            return TransferResult.done();
        }
        for (int slot = 0; slot < container.size(); slot++) {
            ItemStack stack = container.getStack(slot);
            if (!stack.isOf(item)) {
                continue;
            }
            int requested = Math.min(stack.getCount(), maxItems);
            ItemStack moving = stack.copyWithCount(requested);
            int inserted = insertPlayer(bot, moving);
            if (inserted <= 0) {
                return TransferResult.failed("inventory_full");
            }
            stack.decrement(inserted);
            container.markDirty();
            bot.getInventory().markDirty();
            BotLog.action(bot, "container_withdraw", "item", item, "count", inserted);
            return TransferResult.moved(inserted);
        }
        return TransferResult.failed("missing " + item + " x" + maxItems);
    }

    public static boolean isReservedTool(ItemStack stack) {
        return !stack.isEmpty() && stack.isDamageable();
    }

    private static PlayerTransfer findPlayerStack(AIPlayerEntity bot, Predicate<ItemStack> filter) {
        DefaultedList<ItemStack> main = bot.getInventory().main;
        for (int slot = 0; slot < main.size(); slot++) {
            ItemStack stack = main.get(slot);
            if (!stack.isEmpty() && filter.test(stack)) {
                return new PlayerTransfer(stack);
            }
        }
        DefaultedList<ItemStack> offHand = bot.getInventory().offHand;
        for (ItemStack stack : offHand) {
            if (!stack.isEmpty() && filter.test(stack)) {
                return new PlayerTransfer(stack);
            }
        }
        return null;
    }

    private static int insertPlayer(AIPlayerEntity bot, ItemStack moving) {
        int original = moving.getCount();
        boolean inserted = bot.getInventory().insertStack(moving);
        if (!inserted && moving.getCount() == original) {
            return 0;
        }
        return original - moving.getCount();
    }

    private static int insert(Inventory inventory, ItemStack moving) {
        int original = moving.getCount();
        for (int slot = 0; slot < inventory.size() && !moving.isEmpty(); slot++) {
            ItemStack target = inventory.getStack(slot);
            if (target.isEmpty() || !ItemStack.areItemsAndComponentsEqual(target, moving)) {
                continue;
            }
            if (!inventory.isValid(slot, moving)) {
                continue;
            }
            int room = Math.min(target.getMaxCount(), inventory.getMaxCount(target)) - target.getCount();
            if (room <= 0) {
                continue;
            }
            int moved = Math.min(room, moving.getCount());
            target.increment(moved);
            moving.decrement(moved);
        }
        for (int slot = 0; slot < inventory.size() && !moving.isEmpty(); slot++) {
            ItemStack target = inventory.getStack(slot);
            if (!target.isEmpty()) {
                continue;
            }
            if (!inventory.isValid(slot, moving)) {
                continue;
            }
            int moved = Math.min(Math.min(moving.getMaxCount(), inventory.getMaxCount(moving)), moving.getCount());
            inventory.setStack(slot, moving.copyWithCount(moved));
            moving.decrement(moved);
        }
        return original - moving.getCount();
    }

    private static void generateLoot(AIPlayerEntity bot, Inventory inventory) {
        if (inventory instanceof LootableInventory lootableInventory) {
            lootableInventory.generateLoot(bot);
        }
    }

    private record PlayerTransfer(ItemStack stack) {
    }

    public record TransferResult(int count, String reason) {
        static TransferResult moved(int count) {
            return new TransferResult(count, "");
        }

        static TransferResult done() {
            return new TransferResult(0, "");
        }

        static TransferResult failed(String reason) {
            return new TransferResult(0, reason);
        }

        public boolean movedAny() {
            return count > 0;
        }
    }
}
