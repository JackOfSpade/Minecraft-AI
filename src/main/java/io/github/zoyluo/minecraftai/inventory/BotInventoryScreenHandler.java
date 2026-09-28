package io.github.zoyluo.minecraftai.inventory;

import io.github.zoyluo.minecraftai.MinecraftAiMod;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.task.TaskManager;
import net.minecraft.entity.ContainerUser;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.screen.Property;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The real inventory menu for an AI player.
 *
 * <p>The bot side deliberately has exactly 41 slots: three normal backpack rows, its normal
 * nine-slot hotbar, four armor slots, and its offhand. The selected hotbar cell is the bot's
 * actual main hand; it is not duplicated as an extra, fake equipment slot.</p>
 */
public final class BotInventoryScreenHandler extends ScreenHandler {
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
    public static final ScreenHandlerType<BotInventoryScreenHandler> TYPE = Registry.register(
            Registries.SCREEN_HANDLER,
            Identifier.of(MinecraftAiMod.MOD_ID, "bot_inventory"),
            new ScreenHandlerType<>(BotInventoryScreenHandler::new, FeatureFlags.VANILLA_FEATURES));

    private final Inventory botInventory;
    private final BotBackedInventory serverInventory;
    private final Property selectedBotHotbarSlot;
    private boolean opened;

    /** Creates the client mirror. Slot updates fill this temporary inventory immediately after open. */
    public BotInventoryScreenHandler(int syncId, PlayerInventory viewerInventory) {
        this(syncId, viewerInventory, new SimpleInventory(BOT_SLOT_COUNT), null);
    }

    /** Creates the authoritative server menu backed by the target bot's real player inventory. */
    public BotInventoryScreenHandler(int syncId, PlayerInventory viewerInventory,
                                     AIPlayerEntity bot, UUID viewerId) {
        this(syncId, viewerInventory, new BotBackedInventory(bot, viewerId), bot);
    }

    private BotInventoryScreenHandler(int syncId, PlayerInventory viewerInventory,
                                      Inventory botInventory, AIPlayerEntity bot) {
        super(TYPE, syncId);
        this.botInventory = botInventory;
        this.serverInventory = botInventory instanceof BotBackedInventory backed ? backed : null;
        this.selectedBotHotbarSlot = addProperty(bot == null
                ? Property.create()
                : selectedHotbarProperty(bot));

        addBotSlots();
        addViewerSlots(viewerInventory);

        if (serverInventory != null) {
            serverInventory.onOpen(viewerInventory.player);
            opened = true;
        }
    }

    /** Forces common registration without linking a client-only screen class on a dedicated server. */
    public static void initialize() {
        // Class initialization performs the registry insertion above.
    }

    /** The index of the actual bot hotbar cell which is currently held in its main hand. */
    public int selectedBotHotbarSlot() {
        return clampHotbarSlot(selectedBotHotbarSlot.get());
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }

        Slot source = slots.get(index);
        if (!source.hasStack() || !source.canTakeItems(player)) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = source.getStack();
        ItemStack original = stack.copy();
        boolean moved;
        if (index < BOT_SLOT_COUNT) {
            moved = insertItem(stack, BOT_SLOT_COUNT, slots.size(), true);
        } else {
            moved = insertIntoPreferredEquipment(stack)
                    || insertItem(stack, 0, ARMOR_START, false);
        }
        if (!moved) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            source.setStack(ItemStack.EMPTY);
        } else {
            source.markDirty();
        }
        source.onTakeItem(player, stack);
        return original;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return serverInventory == null || serverInventory.canPlayerUse(player);
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        if (opened && serverInventory != null) {
            serverInventory.onClose(player);
            opened = false;
        }
    }

    private void addBotSlots() {
        // PlayerInventory main slots 9..35 are the normal three-row backpack.
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < HOTBAR_SIZE; column++) {
                int slot = row * HOTBAR_SIZE + column;
                addSlot(new BotInventorySlot(botInventory, slot,
                        SLOT_X + column * SLOT_SPACING, BACKPACK_Y + row * SLOT_SPACING,
                        serverInventory));
            }
        }

        // PlayerInventory slots 0..8 are the bot's normal hotbar, including its selected main hand.
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

    private void addViewerSlots(PlayerInventory viewerInventory) {
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
        return target >= 0 && insertItem(stack, target, target + 1, false);
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

    private static Property selectedHotbarProperty(AIPlayerEntity bot) {
        return new Property() {
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

        private BotInventorySlot(Inventory inventory, int index, int x, int y,
                                 BotBackedInventory serverInventory) {
            super(inventory, index, x, y);
            this.serverInventory = serverInventory;
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            return serverInventory == null || serverInventory.isValid(getIndex(), stack);
        }

        @Override
        public boolean canTakeItems(PlayerEntity player) {
            return serverInventory == null || serverInventory.canPlayerUse(player);
        }

        @Override
        public int getMaxItemCount() {
            return isArmorSlot(getIndex()) ? 1 : super.getMaxItemCount();
        }
    }

    /** Separate type makes the real armor/offhand positions self-documenting in source and tests. */
    private static final class BotEquipmentSlot extends BotInventorySlot {
        private final EquipmentSlot equipmentSlot;

        private BotEquipmentSlot(Inventory inventory, int index, int x, int y,
                                 BotBackedInventory serverInventory, EquipmentSlot equipmentSlot) {
            super(inventory, index, x, y, serverInventory);
            this.equipmentSlot = equipmentSlot;
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            return super.canInsert(stack)
                    && (equipmentSlot == EquipmentSlot.OFFHAND
                    || stack.isEmpty()
                    || serverInventory == null
                    || serverInventory.preferredEquipmentSlot(stack) == equipmentSlot);
        }
    }

    /**
     * Maps the 41 visible bot cells directly to a ServerPlayerEntity's normal PlayerInventory,
     * armor, and offhand. Nothing in this inventory is padding or a copied stack.
     */
    private static final class BotBackedInventory implements Inventory {
        private final AIPlayerEntity bot;
        private final UUID viewerId;
        private final Set<UUID> viewers = new HashSet<>();

        private BotBackedInventory(AIPlayerEntity bot, UUID viewerId) {
            this.bot = bot;
            this.viewerId = viewerId;
        }

        @Override
        public int size() {
            return BOT_SLOT_COUNT;
        }

        @Override
        public boolean isEmpty() {
            for (int slot = 0; slot < BOT_SLOT_COUNT; slot++) {
                if (!getStack(slot).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getStack(int slot) {
            int mainSlot = mainInventorySlot(slot);
            if (mainSlot >= 0) {
                return bot.getInventory().getStack(mainSlot);
            }
            EquipmentSlot armor = armorSlot(slot);
            if (armor != null) {
                return bot.getEquippedStack(armor);
            }
            return slot == OFFHAND_SLOT ? bot.getOffHandStack() : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeStack(int slot, int amount) {
            if (amount <= 0 || !isManagedSlot(slot)) {
                return ItemStack.EMPTY;
            }
            int mainSlot = mainInventorySlot(slot);
            if (mainSlot >= 0) {
                ItemStack removed = bot.getInventory().removeStack(mainSlot, amount);
                markDirty();
                return removed;
            }
            ItemStack stack = getStack(slot);
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack removed = stack.split(amount);
            setStack(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            return removed;
        }

        @Override
        public ItemStack removeStack(int slot) {
            if (!isManagedSlot(slot)) {
                return ItemStack.EMPTY;
            }
            int mainSlot = mainInventorySlot(slot);
            if (mainSlot >= 0) {
                ItemStack removed = bot.getInventory().removeStack(mainSlot);
                markDirty();
                return removed;
            }
            ItemStack removed = getStack(slot);
            setStack(slot, ItemStack.EMPTY);
            return removed;
        }

        @Override
        public void setStack(int slot, ItemStack stack) {
            ItemStack value = stack == null ? ItemStack.EMPTY : stack;
            if (!isManagedSlot(slot) || (!value.isEmpty() && !isValid(slot, value))) {
                return;
            }
            int mainSlot = mainInventorySlot(slot);
            if (mainSlot >= 0) {
                bot.getInventory().setStack(mainSlot, value);
            } else if (slot == OFFHAND_SLOT) {
                bot.equipStack(EquipmentSlot.OFFHAND, value);
            } else {
                EquipmentSlot armor = armorSlot(slot);
                if (armor != null) {
                    bot.equipStack(armor, value);
                }
            }
            markDirty();
        }

        @Override
        public void markDirty() {
            bot.getInventory().markDirty();
        }

        @Override
        public void clear() {
            for (int slot = 0; slot < BOT_SLOT_COUNT; slot++) {
                setStack(slot, ItemStack.EMPTY);
            }
        }

        @Override
        public boolean canPlayerUse(PlayerEntity player) {
            return player != null
                    && player.getUuid().equals(viewerId)
                    && bot.isAlive()
                    && player.getEntityWorld() == bot.getEntityWorld()
                    && player.squaredDistanceTo(bot) <= MAX_USE_DISTANCE_SQUARED;
        }

        @Override
        public boolean isValid(int slot, ItemStack stack) {
            if (!isManagedSlot(slot)) {
                return false;
            }
            EquipmentSlot armor = armorSlot(slot);
            return armor == null || stack.isEmpty() || preferredEquipmentSlot(stack) == armor;
        }

        @Override
        public void onOpen(ContainerUser user) {
            if (!(user instanceof PlayerEntity player) || !canPlayerUse(player) || !viewers.add(player.getUuid())) {
                return;
            }
            OpenScreenLeases.open(bot);
            BotLog.action(bot, "inventory_screen_opened", "viewer", player.getGameProfile().name());
        }

        @Override
        public void onClose(ContainerUser user) {
            PlayerEntity player = user instanceof PlayerEntity viewer ? viewer : null;
            boolean closed = player != null && viewers.remove(player.getUuid());
            if (closed) {
                OpenScreenLeases.close(bot);
            }
            if (player != null) {
                BotLog.action(bot, "inventory_screen_closed", "viewer", player.getGameProfile().name());
            }
        }

        private EquipmentSlot preferredEquipmentSlot(ItemStack stack) {
            return bot.getPreferredEquipmentSlot(stack);
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

        private static synchronized void open(AIPlayerEntity bot) {
            Lease lease = OPEN.get(bot.getUuid());
            if (lease == null) {
                boolean pausedTask = TaskManager.INSTANCE.getActive(bot).isPresent();
                if (pausedTask) {
                    TaskManager.INSTANCE.pauseFor(bot, "inventory_screen_open");
                }
                lease = new Lease(0, pausedTask);
            }
            OPEN.put(bot.getUuid(), new Lease(lease.count() + 1, lease.pausedTask()));
            bot.getActionPack().stopAll();
        }

        private static synchronized void close(AIPlayerEntity bot) {
            Lease lease = OPEN.get(bot.getUuid());
            if (lease == null || lease.count() <= 1) {
                OPEN.remove(bot.getUuid());
                if (lease != null && lease.pausedTask()) {
                    TaskManager.INSTANCE.resumeFromPause(bot);
                }
                return;
            }
            OPEN.put(bot.getUuid(), new Lease(lease.count() - 1, lease.pausedTask()));
        }

        private record Lease(int count, boolean pausedTask) {
        }
    }
}
