package io.github.zoyluo.minecraftai.inventory;

import io.github.zoyluo.minecraftai.MinecraftAiMod;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.InventoryAudit;
import io.github.zoyluo.minecraftai.task.TaskManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The real inventory menu for an AI player.
 *
 * <p>The bot side deliberately has exactly 41 slots: three normal backpack rows, its normal
 * nine-slot hotbar, four armor slots, and its offhand. The selected hotbar cell is the bot's
 * actual main hand; it is not duplicated as an extra, fake equipment slot.</p>
 */
public final class BotInventoryScreenHandler extends AbstractContainerMenu {
    public static final int BACKPACK_SIZE = 27;
    public static final int HOTBAR_SIZE = 9;
    public static final int BOT_HOTBAR_START = BACKPACK_SIZE;
    public static final int ARMOR_START = BOT_HOTBAR_START + HOTBAR_SIZE;
    public static final int OFFHAND_SLOT = ARMOR_START + 4;
    public static final int BOT_SLOT_COUNT = OFFHAND_SLOT + 1;

    public static final int SLOT_X = 8;
    public static final int GEAR_Y = 31;
    public static final int BACKPACK_Y = 65;
    public static final int BOT_HOTBAR_Y = 137;
    public static final int PLAYER_INVENTORY_Y = 178;
    public static final int PLAYER_HOTBAR_Y = 236;

    private static final int SLOT_SPACING = 18;
    private static final double MAX_USE_DISTANCE_SQUARED = 64.0D;
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** Registered on the common side; only the screen renderer itself is registered client-side. */
    public static final MenuType<BotInventoryScreenHandler> TYPE = Registry.register(
            BuiltInRegistries.MENU,
            Identifier.fromNamespaceAndPath(MinecraftAiMod.MOD_ID, "bot_inventory"),
            new MenuType<>(BotInventoryScreenHandler::new, FeatureFlags.VANILLA_SET));

    private final Container botInventory;
    private final BotBackedInventory serverInventory;
    private final DataSlot selectedBotHotbarSlot;
    private boolean opened;

    /** Creates the client mirror. Slot updates fill this temporary inventory immediately after open. */
    public BotInventoryScreenHandler(int syncId, Inventory viewerInventory) {
        this(syncId, viewerInventory, new SimpleContainer(BOT_SLOT_COUNT), null);
    }

    /** Creates the authoritative server menu backed by the target bot's real player inventory. */
    public BotInventoryScreenHandler(int syncId, Inventory viewerInventory,
                                     AIPlayerEntity bot, UUID viewerId) {
        this(syncId, viewerInventory, new BotBackedInventory(bot, viewerId), bot);
    }

    private BotInventoryScreenHandler(int syncId, Inventory viewerInventory,
                                      Container botInventory, AIPlayerEntity bot) {
        super(TYPE, syncId);
        this.botInventory = botInventory;
        this.serverInventory = botInventory instanceof BotBackedInventory backed ? backed : null;
        this.selectedBotHotbarSlot = addDataSlot(bot == null
                ? DataSlot.standalone()
                : selectedHotbarProperty(bot));

        addBotSlots();
        addViewerSlots(viewerInventory);

        if (serverInventory != null) {
            serverInventory.startOpen(viewerInventory.player);
            opened = true;
        }
    }

    /** Forces common registration without linking a client-only screen class on a dedicated server. */
    public static void initialize() {
        // Class initialization performs the registry insertion above.
    }

    /** True while any player has this bot's inventory screen open (the bot's equipment is then being edited by hand). */
    public static boolean isScreenOpen(AIPlayerEntity bot) {
        return OpenScreenLeases.isOpen(bot);
    }

    /** The index of the actual bot hotbar cell which is currently held in its main hand. */
    public int selectedBotHotbarSlot() {
        return clampHotbarSlot(selectedBotHotbarSlot.get());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }

        Slot source = slots.get(index);
        if (!source.hasItem() || !source.mayPickup(player)) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = source.getItem();
        ItemStack original = stack.copy();
        boolean moved;
        if (index < BOT_SLOT_COUNT) {
            moved = moveItemStackTo(stack, BOT_SLOT_COUNT, slots.size(), true);
        } else {
            moved = insertIntoPreferredEquipment(stack)
                    || moveItemStackTo(stack, 0, ARMOR_START, false);
        }
        if (!moved) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            source.setByPlayer(ItemStack.EMPTY);
        } else {
            source.setChanged();
        }
        source.onTake(player, stack);
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return serverInventory == null || serverInventory.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (opened && serverInventory != null) {
            serverInventory.stopOpen(player);
            opened = false;
        }
    }

    private void addBotSlots() {
        // Inventory main slots 9..35 are the normal three-row backpack.
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < HOTBAR_SIZE; column++) {
                int slot = row * HOTBAR_SIZE + column;
                addSlot(new BotInventorySlot(botInventory, slot,
                        SLOT_X + column * SLOT_SPACING, BACKPACK_Y + row * SLOT_SPACING,
                        serverInventory));
            }
        }

        // Inventory slots 0..8 are the bot's normal hotbar, including its selected main hand.
        for (int column = 0; column < HOTBAR_SIZE; column++) {
            addSlot(new BotInventorySlot(botInventory, BOT_HOTBAR_START + column,
                    SLOT_X + column * SLOT_SPACING, BOT_HOTBAR_Y, serverInventory));
        }

        for (int index = 0; index < ARMOR_SLOTS.length; index++) {
            addSlot(new BotEquipmentSlot(botInventory, ARMOR_START + index,
                    SLOT_X + index * SLOT_SPACING, GEAR_Y, serverInventory, ARMOR_SLOTS[index]));
        }
        addSlot(new BotEquipmentSlot(botInventory, OFFHAND_SLOT,
                SLOT_X + 6 * SLOT_SPACING, GEAR_Y, serverInventory, EquipmentSlot.OFFHAND));
    }

    private void addViewerSlots(Inventory viewerInventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < HOTBAR_SIZE; column++) {
                addSlot(new Slot(viewerInventory, 9 + row * HOTBAR_SIZE + column,
                        SLOT_X + column * SLOT_SPACING,
                        PLAYER_INVENTORY_Y + row * SLOT_SPACING));
            }
        }
        for (int column = 0; column < HOTBAR_SIZE; column++) {
            addSlot(new Slot(viewerInventory, column,
                    SLOT_X + column * SLOT_SPACING, PLAYER_HOTBAR_Y));
        }
    }

    private boolean insertIntoPreferredEquipment(ItemStack stack) {
        if (serverInventory == null || stack.isEmpty()) {
            return false;
        }
        int target = botSlotForEquipment(serverInventory.preferredEquipmentSlot(stack));
        return target >= 0 && moveItemStackTo(stack, target, target + 1, false);
    }

    private int botSlotForEquipment(EquipmentSlot equipmentSlot) {
        if (equipmentSlot == EquipmentSlot.MAINHAND) {
            return BOT_HOTBAR_START + selectedBotHotbarSlot();
        }
        if (equipmentSlot == EquipmentSlot.OFFHAND) {
            return OFFHAND_SLOT;
        }
        for (int index = 0; index < ARMOR_SLOTS.length; index++) {
            if (ARMOR_SLOTS[index] == equipmentSlot) {
                return ARMOR_START + index;
            }
        }
        return -1;
    }

    private static DataSlot selectedHotbarProperty(AIPlayerEntity bot) {
        return new DataSlot() {
            @Override
            public int get() {
                return clampHotbarSlot(bot.getInventory().getSelectedSlot());
            }

            @Override
            public void set(int value) {
                // The server is the source of truth. The client-side property is updated by sync packets.
            }
        };
    }

    private static int clampHotbarSlot(int value) {
        return Math.max(0, Math.min(HOTBAR_SIZE - 1, value));
    }

    private static boolean isArmorSlot(int slot) {
        return armorSlot(slot) != null;
    }

    private static EquipmentSlot armorSlot(int slot) {
        int index = slot - ARMOR_START;
        return index >= 0 && index < ARMOR_SLOTS.length ? ARMOR_SLOTS[index] : null;
    }

    private static int mainInventorySlot(int slot) {
        if (slot >= 0 && slot < BACKPACK_SIZE) {
            return HOTBAR_SIZE + slot;
        }
        if (slot >= BOT_HOTBAR_START && slot < ARMOR_START) {
            return slot - BOT_HOTBAR_START;
        }
        return -1;
    }

    /** A validating bot slot. The server does all authorization and equipment validation. */
    private static class BotInventorySlot extends Slot {
        protected final BotBackedInventory serverInventory;

        private BotInventorySlot(Container inventory, int index, int x, int y,
                                 BotBackedInventory serverInventory) {
            super(inventory, index, x, y);
            this.serverInventory = serverInventory;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return serverInventory == null || serverInventory.canPlaceItem(getContainerSlot(), stack);
        }

        @Override
        public boolean mayPickup(Player player) {
            return serverInventory == null || serverInventory.stillValid(player);
        }

        @Override
        public int getMaxStackSize() {
            return isArmorSlot(getContainerSlot()) ? 1 : super.getMaxStackSize();
        }
    }

    /** Separate type makes the real armor/offhand positions self-documenting in source and tests. */
    private static final class BotEquipmentSlot extends BotInventorySlot {
        private final EquipmentSlot equipmentSlot;

        private BotEquipmentSlot(Container inventory, int index, int x, int y,
                                 BotBackedInventory serverInventory, EquipmentSlot equipmentSlot) {
            super(inventory, index, x, y, serverInventory);
            this.equipmentSlot = equipmentSlot;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return super.mayPlace(stack)
                    && (equipmentSlot == EquipmentSlot.OFFHAND
                    || stack.isEmpty()
                    || serverInventory == null
                    || serverInventory.preferredEquipmentSlot(stack) == equipmentSlot);
        }
    }

    /**
     * Maps the 41 visible bot cells directly to a ServerPlayer's normal Inventory,
     * armor, and offhand. Nothing in this inventory is padding or a copied stack.
     */
    private static final class BotBackedInventory implements Container {
        private final AIPlayerEntity bot;
        private final UUID viewerId;
        private final Set<UUID> viewers = new HashSet<>();

        private BotBackedInventory(AIPlayerEntity bot, UUID viewerId) {
            this.bot = bot;
            this.viewerId = viewerId;
        }

        @Override
        public int getContainerSize() {
            return BOT_SLOT_COUNT;
        }

        @Override
        public boolean isEmpty() {
            for (int slot = 0; slot < BOT_SLOT_COUNT; slot++) {
                if (!getItem(slot).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            int mainSlot = mainInventorySlot(slot);
            if (mainSlot >= 0) {
                return bot.getInventory().getItem(mainSlot);
            }
            EquipmentSlot armor = armorSlot(slot);
            if (armor != null) {
                return bot.getItemBySlot(armor);
            }
            return slot == OFFHAND_SLOT ? bot.getOffhandItem() : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            if (amount <= 0 || !isManagedSlot(slot)) {
                return ItemStack.EMPTY;
            }
            int mainSlot = mainInventorySlot(slot);
            if (mainSlot >= 0) {
                ItemStack removed = bot.getInventory().removeItem(mainSlot, amount);
                setChanged();
                return removed;
            }
            ItemStack stack = getItem(slot);
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack removed = stack.split(amount);
            setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            return removed;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            if (!isManagedSlot(slot)) {
                return ItemStack.EMPTY;
            }
            int mainSlot = mainInventorySlot(slot);
            if (mainSlot >= 0) {
                ItemStack removed = bot.getInventory().removeItemNoUpdate(mainSlot);
                setChanged();
                return removed;
            }
            ItemStack removed = getItem(slot);
            setItem(slot, ItemStack.EMPTY);
            return removed;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            ItemStack value = stack == null ? ItemStack.EMPTY : stack;
            if (!isManagedSlot(slot) || (!value.isEmpty() && !canPlaceItem(slot, value))) {
                return;
            }
            int mainSlot = mainInventorySlot(slot);
            if (mainSlot >= 0) {
                bot.getInventory().setItem(mainSlot, value);
            } else if (slot == OFFHAND_SLOT) {
                bot.setItemSlot(EquipmentSlot.OFFHAND, value);
            } else {
                EquipmentSlot armor = armorSlot(slot);
                if (armor != null) {
                    bot.setItemSlot(armor, value);
                }
            }
            setChanged();
        }

        @Override
        public void setChanged() {
            bot.getInventory().setChanged();
        }

        @Override
        public void clearContent() {
            for (int slot = 0; slot < BOT_SLOT_COUNT; slot++) {
                setItem(slot, ItemStack.EMPTY);
            }
        }

        @Override
        public boolean stillValid(Player player) {
            return player != null
                    && player.getUUID().equals(viewerId)
                    && bot.isAlive()
                    && player.level() == bot.level()
                    && player.distanceToSqr(bot) <= MAX_USE_DISTANCE_SQUARED;
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            if (!isManagedSlot(slot)) {
                return false;
            }
            EquipmentSlot armor = armorSlot(slot);
            return armor == null || stack.isEmpty() || preferredEquipmentSlot(stack) == armor;
        }

        @Override
        public void startOpen(ContainerUser user) {
            if (!(user instanceof Player player) || !stillValid(player) || !viewers.add(player.getUUID())) {
                return;
            }
            OpenScreenLeases.open(bot);
            InventoryAudit.INSTANCE.viewerOpened(bot.getUUID(), player.getUUID(), player.getGameProfile().name());
            BotLog.action(bot, "inventory_screen_opened", "viewer", player.getGameProfile().name());
        }

        @Override
        public void stopOpen(ContainerUser user) {
            Player player = user instanceof Player viewer ? viewer : null;
            boolean closed = player != null && viewers.remove(player.getUUID());
            if (closed) {
                OpenScreenLeases.close(bot);
                InventoryAudit.INSTANCE.viewerClosed(bot.getUUID(), player.getUUID());
            }
            if (player != null) {
                BotLog.action(bot, "inventory_screen_closed", "viewer", player.getGameProfile().name());
            }
        }

        private EquipmentSlot preferredEquipmentSlot(ItemStack stack) {
            return bot.getEquipmentSlotForItem(stack);
        }

        private static boolean isManagedSlot(int slot) {
            return slot >= 0 && slot < BOT_SLOT_COUNT;
        }
    }

    /** Shares the task-pause lease across independently opened menus for the same bot. */
    private static final class OpenScreenLeases {
        private static final Map<UUID, Lease> OPEN = new HashMap<>();

        private OpenScreenLeases() {
        }

        private static synchronized boolean isOpen(AIPlayerEntity bot) {
            return OPEN.containsKey(bot.getUUID());
        }

        private static synchronized void open(AIPlayerEntity bot) {
            Lease lease = OPEN.get(bot.getUUID());
            if (lease == null) {
                boolean pausedTask = TaskManager.INSTANCE.getActive(bot).isPresent();
                if (pausedTask) {
                    TaskManager.INSTANCE.pauseFor(bot, "inventory_screen_open");
                }
                lease = new Lease(0, pausedTask);
            }
            OPEN.put(bot.getUUID(), new Lease(lease.count() + 1, lease.pausedTask()));
            bot.getActionPack().stopAll();
        }

        private static synchronized void close(AIPlayerEntity bot) {
            Lease lease = OPEN.get(bot.getUUID());
            if (lease == null || lease.count() <= 1) {
                OPEN.remove(bot.getUUID());
                if (lease != null && lease.pausedTask()) {
                    TaskManager.INSTANCE.resumeFromPause(bot);
                }
                return;
            }
            OPEN.put(bot.getUUID(), new Lease(lease.count() - 1, lease.pausedTask()));
        }

        private record Lease(int count, boolean pausedTask) {
        }
    }
}
