package io.github.zoyluo.minecraftai.client.screen;

import io.github.zoyluo.minecraftai.inventory.BotInventoryScreenHandler;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** Client renderer for the real 41-slot bot inventory handler. */
public final class BotInventoryScreen extends AbstractContainerScreen<BotInventoryScreenHandler> {
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

    public BotInventoryScreen(BotInventoryScreenHandler handler, Inventory inventory, Component title) {
        super(handler, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        titleLabelX = 8;
        titleLabelY = 7;
        inventoryLabelX = 8;
        inventoryLabelY = 166;
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        renderTooltip(context, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics context, float delta, int mouseX, int mouseY) {
        context.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL);
        drawBorder(context, leftPos, topPos, imageWidth, imageHeight, PANEL_BORDER);
        for (Slot slot : menu.slots) {
            drawSlotFrame(context, leftPos + slot.x, topPos + slot.y);
        }

        int selectedX = leftPos + BotInventoryScreenHandler.SLOT_X
                + menu.selectedBotHotbarSlot() * SLOT_SIZE;
        int selectedY = topPos + BotInventoryScreenHandler.BOT_HOTBAR_Y;
        drawBorder(context, selectedX - 1, selectedY - 1, SLOT_SIZE + 2, SLOT_SIZE + 2, MAIN_HAND_GOLD);
    }

    @Override
    protected void renderLabels(GuiGraphics context, int mouseX, int mouseY) {
        context.drawString(font, title, titleLabelX, titleLabelY, TEXT, false);
        context.drawString(font, "Armor (H C L B)", 8, 20, TEXT_DIM, false);
        context.drawString(font, "Offhand", 116, 20, TEXT_DIM, false);
        context.drawString(font, "Backpack", 8, 53, TEXT_DIM, false);
        context.drawString(font, "Hotbar (gold = main hand)", 8, 125, TEXT_DIM, false);
        context.drawString(font, "Your inventory", inventoryLabelX, inventoryLabelY, TEXT_DIM, false);
    }

    private static void drawSlotFrame(GuiGraphics context, int slotX, int slotY) {
        context.fill(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, SLOT_BORDER);
        context.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, SLOT_INNER);
    }

    private static void drawBorder(GuiGraphics context, int left, int top, int width, int height, int color) {
        context.fill(left, top, left + width, top + 1, color);
        context.fill(left, top + height - 1, left + width, top + height, color);
        context.fill(left, top, left + 1, top + height, color);
        context.fill(left + width - 1, top, left + width, top + height, color);
    }
}
