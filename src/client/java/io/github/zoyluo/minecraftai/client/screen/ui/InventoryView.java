package io.github.zoyluo.minecraftai.client.screen.ui;

import io.github.zoyluo.minecraftai.client.BotClientState;
import io.github.zoyluo.minecraftai.client.BotCommandBridge;
import io.github.zoyluo.minecraftai.network.payload.BotItemMoveC2S;
import io.github.zoyluo.minecraftai.network.payload.BotSnapshotS2C;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Interactive inventory panel: top half = AI inventory (click to take an item to the player),
 * bottom half = player inventory (click to give an item to the AI).
 * Left-click = whole stack; Shift+left-click = single item. Slots are addressed by "real main index",
 * mapping directly onto the slot field of {@link BotItemMoveC2S}.
 * Never touches AbstractContainerMenu/Screen container logic anywhere in this flow -- only sends C2S packets,
 * with the Inventory modified directly server-side (per iron rule G3).
 */
public final class InventoryView implements PanelComponent {
    private static final int SLOT = 18;       // single-cell side length (incl. 1px border, 16px icon)
    private static final int COLS = 9;
    private static final int AI_ROWS = 4;     // AI main has 36 slots = 4x9
    private static final int PL_MAIN_ROWS = 3; // player main inventory slots 9..35
    private static final int HOVER = 0x40FFFFFF;

    private final String target;

    private int x;
    private int y;
    private int w;
    private int h;

    // Origins for each section, computed in setBounds; shared by render and hit-test to keep pixels consistent.
    private int gridX;
    private int equipRowY;
    private int aiLabelY;
    private int aiGridY;
    private int plLabelY;
    private int plMainY;
    private int plHotbarY;

    private BotSnapshotS2C snapshot;
    private final ItemStack[] aiSlots = new ItemStack[AI_ROWS * COLS];
    private final ItemStack[] equipSlots = new ItemStack[6]; // 0 head / 1 chest / 2 legs / 3 feet / 4 main hand / 5 off hand

    public InventoryView(String target) {
        this.target = target;
        for (int i = 0; i < aiSlots.length; i++) {
            aiSlots[i] = ItemStack.EMPTY;
        }
        for (int i = 0; i < equipSlots.length; i++) {
            equipSlots[i] = ItemStack.EMPTY;
        }
    }

    @Override
    public void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.gridX = x + Math.max(0, (w - COLS * SLOT) / 2);
        this.equipRowY = y + 11;                  // equipment row (title at y)
        this.aiLabelY = equipRowY + SLOT + 5;     // AI inventory title
        this.aiGridY = aiLabelY + 11;
        int aiBottom = aiGridY + AI_ROWS * SLOT;
        this.plLabelY = aiBottom + 6;
        this.plMainY = plLabelY + 11;
        this.plHotbarY = plMainY + PL_MAIN_ROWS * SLOT + 3;
    }

    @Override
    public int preferredHeight() {
        // Equipment: 11 (title) + 18 (one row) + 5; AI: 11+72; player: 6+11+54+3+18
        return 11 + SLOT + 5 + 11 + AI_ROWS * SLOT + 6 + 11 + PL_MAIN_ROWS * SLOT + 3 + SLOT;
    }

    @Override
    public void refresh(BotSnapshotS2C snapshot, List<BotClientState.ChatLine> chat) {
        this.snapshot = snapshot;
        for (int i = 0; i < aiSlots.length; i++) {
            aiSlots[i] = ItemStack.EMPTY;
        }
        for (int i = 0; i < equipSlots.length; i++) {
            equipSlots[i] = ItemStack.EMPTY;
        }
        if (snapshot != null) {
            for (BotSnapshotS2C.ItemEntry entry : snapshot.inventory()) {
                int slot = entry.slot();
                if (slot >= 0 && slot < aiSlots.length) {
                    aiSlots[slot] = stack(entry);
                }
            }
            for (BotSnapshotS2C.ItemEntry entry : snapshot.equipment()) {
                int slot = entry.slot();
                if (slot >= 0 && slot < equipSlots.length) {
                    equipSlots[slot] = stack(entry);
                }
            }
        }
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta, Font renderer) {
        Inventory playerInv = playerInventory();

        ItemStack hovered = ItemStack.EMPTY;

        // -- AI equipment (head/chest/legs/feet/main hand/off hand, display only, not transferable) --
        context.drawString(renderer, Theme.tr("inventory.minecraftai.section_equip"), gridX, y, Theme.TEXT_DIM);
        for (int i = 0; i < equipSlots.length; i++) {
            int gx = gridX + i * SLOT;
            boolean hot = inCell(mouseX, mouseY, gx, equipRowY);
            drawSlot(context, renderer, gx, equipRowY, equipSlots[i], hot);
            if (hot && !equipSlots[i].isEmpty()) {
                hovered = equipSlots[i];
            }
        }

        // -- AI inventory --
        context.drawString(renderer, Theme.tr("inventory.minecraftai.section_ai"), gridX, aiLabelY, Theme.TEXT_DIM);
        for (int slot = 0; slot < aiSlots.length; slot++) {
            int gx = gridX + (slot % COLS) * SLOT;
            int gy = aiGridY + (slot / COLS) * SLOT;
            boolean hot = inCell(mouseX, mouseY, gx, gy);
            drawSlot(context, renderer, gx, gy, aiSlots[slot], hot);
            if (hot && !aiSlots[slot].isEmpty()) {
                hovered = aiSlots[slot];
            }
        }

        // -- Player inventory --
        context.drawString(renderer, Theme.tr("inventory.minecraftai.section_self"), gridX, plLabelY, Theme.TEXT_DIM);
        if (playerInv != null) {
            for (int row = 0; row < PL_MAIN_ROWS; row++) {
                for (int col = 0; col < COLS; col++) {
                    int slot = 9 + row * COLS + col;
                    int gx = gridX + col * SLOT;
                    int gy = plMainY + row * SLOT;
                    boolean hot = inCell(mouseX, mouseY, gx, gy);
                    ItemStack stack = playerInv.getItem(slot);
                    drawSlot(context, renderer, gx, gy, stack, hot);
                    if (hot && !stack.isEmpty()) {
                        hovered = stack;
                    }
                }
            }
            for (int col = 0; col < COLS; col++) {
                int gx = gridX + col * SLOT;
                boolean hot = inCell(mouseX, mouseY, gx, plHotbarY);
                ItemStack stack = playerInv.getItem(col);
                drawSlot(context, renderer, gx, plHotbarY, stack, hot);
                if (hot && !stack.isEmpty()) {
                    hovered = stack;
                }
            }
        }

        if (!hovered.isEmpty()) {
            context.setTooltipForNextFrame(renderer, hovered, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean left = button == 0;
        boolean right = button == 1;
        if (!left && !right) {
            return false;
        }
        boolean single = left && Minecraft.getInstance().hasShiftDown(); // Shift+left-click = single item
        boolean half = right;                            // right-click = half stack

        // AI slots: take out
        for (int slot = 0; slot < aiSlots.length; slot++) {
            int gx = gridX + (slot % COLS) * SLOT;
            int gy = aiGridY + (slot / COLS) * SLOT;
            if (inCell(mouseX, mouseY, gx, gy)) {
                ItemStack src = aiSlots[slot];
                if (!src.isEmpty()) {
                    BotCommandBridge.moveItem(target, BotItemMoveC2S.TAKE, slot, amountFor(src, single, half));
                }
                return true;
            }
        }
        // Player main inventory: give item
        Inventory inv = playerInventory();
        for (int row = 0; row < PL_MAIN_ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int gx = gridX + col * SLOT;
                int gy = plMainY + row * SLOT;
                if (inCell(mouseX, mouseY, gx, gy)) {
                    putFromPlayer(inv, 9 + row * COLS + col, single, half);
                    return true;
                }
            }
        }
        // Player hotbar: give item
        for (int col = 0; col < COLS; col++) {
            int gx = gridX + col * SLOT;
            if (inCell(mouseX, mouseY, gx, plHotbarY)) {
                putFromPlayer(inv, col, single, half);
                return true;
            }
        }
        return false;
    }

    private void putFromPlayer(Inventory inv, int slot, boolean single, boolean half) {
        if (inv == null) {
            return;
        }
        ItemStack src = inv.getItem(slot);
        if (!src.isEmpty()) {
            BotCommandBridge.moveItem(target, BotItemMoveC2S.PUT, slot, amountFor(src, single, half));
        }
    }

    // Whole stack = 0 (server takes the whole stack); single = 1; half stack rounds up.
    private static int amountFor(ItemStack src, boolean single, boolean half) {
        if (single) {
            return 1;
        }
        if (half) {
            int count = src.getCount();
            return Math.max(1, count / 2 + count % 2);
        }
        return 0;
    }

    private boolean inCell(double mx, double my, int gx, int gy) {
        return mx >= gx && mx < gx + SLOT && my >= gy && my < gy + SLOT;
    }

    private void drawSlot(GuiGraphics context, Font renderer, int gx, int gy, ItemStack stack, boolean hovered) {
        context.fill(gx, gy, gx + SLOT, gy + SLOT, Theme.TRACK);
        context.hLine(gx, gx + SLOT - 1, gy, Theme.BORDER);
        context.hLine(gx, gx + SLOT - 1, gy + SLOT - 1, Theme.BORDER);
        context.vLine(gx, gy, gy + SLOT - 1, Theme.BORDER);
        context.vLine(gx + SLOT - 1, gy, gy + SLOT - 1, Theme.BORDER);
        if (stack != null && !stack.isEmpty()) {
            context.renderItem(stack, gx + 1, gy + 1);
            context.renderItemDecorations(renderer, stack, gx + 1, gy + 1);
        }
        if (hovered) {
            context.fill(gx + 1, gy + 1, gx + SLOT - 1, gy + SLOT - 1, HOVER);
        }
    }

    private static Inventory playerInventory() {
        Minecraft client = Minecraft.getInstance();
        return client.player == null ? null : client.player.getInventory();
    }

    private static ItemStack stack(BotSnapshotS2C.ItemEntry entry) {
        Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(entry.itemId())).orElse(Items.BARRIER);
        return new ItemStack(item, entry.count());
    }
}
