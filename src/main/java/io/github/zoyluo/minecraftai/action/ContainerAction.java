package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class ContainerAction {
    private ContainerAction() {
    }

    public static Optional<Container> resolve(AIPlayerEntity bot, BlockPos pos) {
        BlockState state = bot.level().getBlockState(pos);
        Block block = state.getBlock();
        if (block instanceof ChestBlock chestBlock) {
            Container inventory = ChestBlock.getContainer(chestBlock, state, bot.level(), pos, true);
            if (inventory != null) {
                generateLoot(bot, inventory);
                return Optional.of(inventory);
            }
        }
        if (bot.level().getBlockEntity(pos) instanceof Container inventory) {
            generateLoot(bot, inventory);
            return Optional.of(inventory);
        }
        return Optional.empty();
    }

    public static TransferResult depositOne(Container container,
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
        source.stack().shrink(inserted);
        bot.getInventory().setChanged();
        container.setChanged();
        BotLog.action(bot, "container_deposit", "item", item, "count", inserted);
        return TransferResult.moved(inserted);
    }

    public static TransferResult withdrawOne(Container container,
                                             AIPlayerEntity bot,
                                             Item item,
                                             int maxItems) {
        if (maxItems <= 0) {
            return TransferResult.done();
        }
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.is(item)) {
                continue;
            }
            int requested = Math.min(stack.getCount(), maxItems);
            ItemStack moving = stack.copyWithCount(requested);
            int inserted = insertPlayer(bot, moving);
            if (inserted <= 0) {
                return TransferResult.failed("inventory_full");
            }
            stack.shrink(inserted);
            container.setChanged();
            bot.getInventory().setChanged();
            BotLog.action(bot, "container_withdraw", "item", item, "count", inserted);
            return TransferResult.moved(inserted);
        }
        return TransferResult.failed("missing " + item + " x" + maxItems);
    }

    public static boolean isReservedTool(ItemStack stack) {
        return !stack.isEmpty() && stack.isDamageableItem();
    }

    private static PlayerTransfer findPlayerStack(AIPlayerEntity bot, Predicate<ItemStack> filter) {
        NonNullList<ItemStack> main = bot.getInventory().getNonEquipmentItems();
        for (int slot = 0; slot < main.size(); slot++) {
            ItemStack stack = main.get(slot);
            if (!stack.isEmpty() && filter.test(stack)) {
                return new PlayerTransfer(stack);
            }
        }
        ItemStack offHandStack = bot.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!offHandStack.isEmpty() && filter.test(offHandStack)) {
            return new PlayerTransfer(offHandStack);
        }
        return null;
    }

    private static int insertPlayer(AIPlayerEntity bot, ItemStack moving) {
        int original = moving.getCount();
        boolean inserted = bot.getInventory().add(moving);
        if (!inserted && moving.getCount() == original) {
            return 0;
        }
        return original - moving.getCount();
    }

    private static int insert(Container inventory, ItemStack moving) {
        int original = moving.getCount();
        for (int slot = 0; slot < inventory.getContainerSize() && !moving.isEmpty(); slot++) {
            ItemStack target = inventory.getItem(slot);
            if (target.isEmpty() || !ItemStack.isSameItemSameComponents(target, moving)) {
                continue;
            }
            if (!inventory.canPlaceItem(slot, moving)) {
                continue;
            }
            int room = Math.min(target.getMaxStackSize(), inventory.getMaxStackSize(target)) - target.getCount();
            if (room <= 0) {
                continue;
            }
            int moved = Math.min(room, moving.getCount());
            target.grow(moved);
            moving.shrink(moved);
        }
        for (int slot = 0; slot < inventory.getContainerSize() && !moving.isEmpty(); slot++) {
            ItemStack target = inventory.getItem(slot);
            if (!target.isEmpty()) {
                continue;
            }
            if (!inventory.canPlaceItem(slot, moving)) {
                continue;
            }
            int moved = Math.min(Math.min(moving.getMaxStackSize(), inventory.getMaxStackSize(moving)), moving.getCount());
            inventory.setItem(slot, moving.copyWithCount(moved));
            moving.shrink(moved);
        }
        return original - moving.getCount();
    }

    private static void generateLoot(AIPlayerEntity bot, Container inventory) {
        if (inventory instanceof RandomizableContainer lootableInventory) {
            lootableInventory.unpackLootTable(bot);
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
