package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.memory.ContainerLedger;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

public final class ContainerAction {
    /** Squared eye-to-block-center distance within which a bot may open and use a container. */
    public static final double REACH_SQUARED = 20.25D;

    private ContainerAction() {
    }

    /** The storage block kinds a bot may pick on its own: chests (incl. trapped), barrels and shulker boxes. */
    public static boolean isStorageBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock;
    }

    /**
     * Cheap and content-free: whether a storage block stands at {@code pos} that vanilla would let a
     * player open right now (a chest under a solid block or a sitting cat, or a shulker box with its
     * lid obstructed, is not). Never touches loot tables or inventory contents.
     */
    public static boolean isOpenableStorage(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!isStorageBlock(state) || !(level.getBlockEntity(pos) instanceof Container)) {
            return false;
        }
        return lidClear(level, pos, state);
    }

    /** Vanilla's blocked-lid rule for the storage kinds; barrels have no lid. */
    private static boolean lidClear(Level level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block instanceof ChestBlock chestBlock) {
            return ChestBlock.getContainer(chestBlock, state, level, pos, false) != null;
        }
        if (block instanceof ShulkerBoxBlock
                && level.getBlockEntity(pos) instanceof ShulkerBoxBlockEntity box
                && box.isClosed()) {
            Direction facing = state.getValue(ShulkerBoxBlock.FACING);
            var lid = Shulker.getProgressDeltaAabb(1.0F, facing, 0.0F, 0.5F, pos.getBottomCenter()).deflate(1.0E-6D);
            return level.noCollision(lid);
        }
        return true;
    }

    /** A double chest's two halves share one identity: the half whose position sorts first. */
    public static BlockPos canonicalPos(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = ChestBlock.getConnectedBlockPos(pos, state);
            return (other.compareTo(pos) < 0 ? other : pos).immutable();
        }
        return pos.immutable();
    }

    /**
     * Resolves the container at an explicitly targeted position (any block entity that is a
     * {@link Container}: this is the path a caller-supplied coordinate or a remembered depot takes;
     * automatic selection must use {@link #isOpenableStorage} first). A chest with a blocked lid is
     * never resolved, exactly like vanilla.
     */
    public static Optional<Container> resolve(AIPlayerEntity bot, BlockPos pos) {
        BlockState state = bot.level().getBlockState(pos);
        Block block = state.getBlock();
        if (block instanceof ChestBlock chestBlock) {
            Container inventory = ChestBlock.getContainer(chestBlock, state, bot.level(), pos, false);
            if (inventory == null) {
                return Optional.empty();
            }
            generateLoot(bot, bot.level().getBlockEntity(pos));
            if (state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                generateLoot(bot, bot.level().getBlockEntity(ChestBlock.getConnectedBlockPos(pos, state)));
            }
            return Optional.of(inventory);
        }
        if (block instanceof ShulkerBoxBlock && !lidClear(bot.level(), pos, state)) {
            return Optional.empty();
        }
        if (bot.level().getBlockEntity(pos) instanceof Container inventory) {
            generateLoot(bot, inventory);
            return Optional.of(inventory);
        }
        return Optional.empty();
    }

    /**
     * Line of sight to a container: a ray from the eyes to the block center must hit that block
     * first. The usual face-center test cannot be used here, because a chest collision box is a
     * sixteenth smaller than the block on every side and the ray would end short of it, so a chest
     * in plain view never counted as observable. A wall (or any other block) in between still does.
     */
    public static boolean canSee(AIPlayerEntity bot, BlockPos pos) {
        return ObservableWorldQuery.canObserveCell(bot, pos);
    }

    /** True when the bot stands within reach of {@code pos} and can see it (the same test every transfer phase uses). */
    public static boolean inReachAndSight(AIPlayerEntity bot, BlockPos pos) {
        return bot.getEyePosition().distanceToSqr(pos.getCenter()) <= REACH_SQUARED
                && canSee(bot, pos);
    }

    /**
     * Opens the container at {@code pos} for real: in reach and line of sight, the position must
     * hold a container the bot may use ({@code explicit} allows any block-entity container, else
     * only storage blocks). The moment it opens, the bot sees the contents, so the ledger is
     * updated from them; if the observed block is no longer a container the stale ledger entry is
     * dropped. Contents can only be learned through this call or a transfer through a container
     * obtained from it.
     */
    public static Optional<Container> open(AIPlayerEntity bot, BlockPos pos, boolean explicit) {
        if (!inReachAndSight(bot, pos)) {
            return Optional.empty();
        }
        Level level = bot.level();
        Optional<Container> container = explicit || isStorageBlock(level.getBlockState(pos))
                ? resolve(bot, pos)
                : Optional.empty();
        if (container.isPresent()) {
            note(bot, pos, container.get());
        } else if (!(level.getBlockEntity(pos) instanceof Container)) {
            forget(bot, pos);
        }
        return container;
    }

    /** Drops the ledger entry for the container at (or under) {@code pos}; call only once the block is observed gone. */
    public static void forget(AIPlayerEntity bot, BlockPos pos) {
        String dimension = bot.level().dimension().identifier().toString();
        ContainerLedger ledger = BotMemoryStore.INSTANCE.of(bot.getUUID()).containers();
        boolean removed = ledger.forget(dimension, pos);
        removed |= ledger.forget(dimension, canonicalPos(bot.level(), pos));
        if (removed) {
            BotLog.action(bot, "container_ledger_forget", "pos", pos.toShortString());
            markPersistenceDirty(bot);
        }
    }

    /** Records what the bot sees inside {@code container} (which it has open at {@code pos}) in its ledger. */
    public static void note(AIPlayerEntity bot, BlockPos pos, Container container) {
        if (!isStorageBlock(bot.level().getBlockState(pos))) {
            return; // furnaces, hoppers and the like are workstations, not storage the ledger tracks
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        int free = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                free++;
            } else {
                counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
        }
        Level level = bot.level();
        BlockPos canonical = canonicalPos(level, pos);
        String block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        String dimension = level.dimension().identifier().toString();
        ContainerLedger ledger = BotMemoryStore.INSTANCE.of(bot.getUUID()).containers();
        ledger.record(new ContainerLedger.Entry(dimension, canonical, block, counts, free,
                container.getContainerSize(), level.getGameTime()));
        // A half that used to be a separate entry (or a neighbour that is no longer storage) must
        // not linger as a stale second copy of the same chest.
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbour = pos.relative(direction);
            if (!neighbour.equals(canonical)
                    && (!isStorageBlock(level.getBlockState(neighbour))
                            || canonicalPos(level, neighbour).equals(canonical))) {
                ledger.forget(dimension, neighbour);
            }
        }
        if (!pos.equals(canonical)) {
            ledger.forget(dimension, pos);
        }
        markPersistenceDirty(bot);
    }

    /** A persistence capture walks every bot, so a burst of transfers asks for one flush per two seconds at most. */
    private static final int PERSIST_MIN_INTERVAL_TICKS = 40;
    private static int lastPersistTick = Integer.MIN_VALUE;

    private static void markPersistenceDirty(AIPlayerEntity bot) {
        var server = bot.level().getServer();
        if (server == null) {
            return;
        }
        int tick = server.getTickCount();
        synchronized (ContainerAction.class) {
            if (lastPersistTick != Integer.MIN_VALUE && tick >= lastPersistTick
                    && tick - lastPersistTick < PERSIST_MIN_INTERVAL_TICKS) {
                return; // the regular save (and the shutdown save) still carries the newest ledger
            }
            lastPersistTick = tick;
        }
        io.github.zoyluo.minecraftai.persist.BotPersistence.INSTANCE.markDirty(server);
    }

    /** Deposit through an opened container, then refresh the ledger from what it now holds. */
    public static TransferResult deposit(AIPlayerEntity bot, BlockPos pos, Container container,
                                         Predicate<ItemStack> filter, int maxItems) {
        TransferResult result = depositOne(container, bot, filter, maxItems);
        note(bot, pos, container);
        return result;
    }

    /** Withdraw through an opened container, then refresh the ledger from what it now holds. */
    public static TransferResult withdraw(AIPlayerEntity bot, BlockPos pos, Container container,
                                          Item item, int maxItems) {
        TransferResult result = withdrawOne(container, bot, item, maxItems);
        note(bot, pos, container);
        return result;
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

    private static void generateLoot(AIPlayerEntity bot, Object inventory) {
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

        public static TransferResult failed(String reason) {
            return new TransferResult(0, reason);
        }

        public boolean movedAny() {
            return count > 0;
        }
    }
}
