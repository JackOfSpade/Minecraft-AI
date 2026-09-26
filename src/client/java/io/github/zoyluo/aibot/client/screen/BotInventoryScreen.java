package io.github.zoyluo.aibot.client.screen;

import io.github.zoyluo.aibot.inventory.BotInventoryScreenHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

/** Client renderer for the real 41-slot bot inventory handler. */
public final class BotInventoryScreen extends HandledScreen<BotInventoryScreenHandler> {
    private static final int WIDTH = 176;
    private static final int HEIGHT = 262;
    private static final int SLOT_SIZE = 18;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int TEXT_DIM = 0xFFB8B8B8;
    private static final int PANEL = 0xFF252525;
    private static final int PANEL_BORDER = 0xFF808080;
    private static final int SLOT_BORDER = 0xFF909090;
    private static final int SLOT_INNER = 0xFF353535;
    private static final int MAIN_HAND_GOLD = 0xFFFFC84A;

    public BotInventoryScreen(BotInventoryScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        backgroundWidth = WIDTH;
        backgroundHeight = HEIGHT;
        titleX = 8;
        titleY = 7;
        playerInventoryTitleX = 8;
        playerInventoryTitleY = 166;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        drawMouseoverTooltip(context, mouseX, mouseY);
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        context.fill(x, y, x + backgroundWidth, y + backgroundHeight, PANEL);
        drawBorder(context, x, y, backgroundWidth, backgroundHeight, PANEL_BORDER);
        for (Slot slot : handler.slots) {
            drawSlotFrame(context, x + slot.x, y + slot.y);
        }

        int selectedX = x + BotInventoryScreenHandler.SLOT_X
                + handler.selectedBotHotbarSlot() * SLOT_SIZE;
        int selectedY = y + BotInventoryScreenHandler.BOT_HOTBAR_Y;
        drawBorder(context, selectedX - 1, selectedY - 1, SLOT_SIZE + 2, SLOT_SIZE + 2, MAIN_HAND_GOLD);
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        context.drawText(textRenderer, title, titleX, titleY, TEXT, false);
        context.drawText(textRenderer, "Armor (H C L B)", 8, 20, TEXT_DIM, false);
        context.drawText(textRenderer, "Offhand", 116, 20, TEXT_DIM, false);
        context.drawText(textRenderer, "Backpack", 8, 53, TEXT_DIM, false);
        context.drawText(textRenderer, "Hotbar (gold = main hand)", 8, 125, TEXT_DIM, false);
        context.drawText(textRenderer, "Your inventory", playerInventoryTitleX, playerInventoryTitleY, TEXT_DIM, false);
    }

    private static void drawSlotFrame(DrawContext context, int slotX, int slotY) {
        context.fill(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, SLOT_BORDER);
        context.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, SLOT_INNER);
    }

    private static void drawBorder(DrawContext context, int left, int top, int width, int height, int color) {
        context.fill(left, top, left + width, top + 1, color);
        context.fill(left, top + height - 1, left + width, top + height, color);
        context.fill(left, top, left + 1, top + height, color);
        context.fill(left + width - 1, top, left + width, top + height, color);
    }
}
