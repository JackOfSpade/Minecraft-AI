package io.github.zoyluo.minecraftai.client.screen.ui.cards;

import io.github.zoyluo.minecraftai.client.BotCommandBridge;
import io.github.zoyluo.minecraftai.client.screen.ui.Theme;
import io.github.zoyluo.minecraftai.client.BotClientState;
import io.github.zoyluo.minecraftai.network.payload.BotSnapshotS2C;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import java.util.List;

public final class QuickActionCard extends PanelCard {
    private static final int INPUT_H = 18;

    private final String target;
    private EditBox idField;
    private EditBox countField;
    private Button comeButton;
    private Button pauseButton;
    private Button stopButton;
    private Button eatButton;
    private Button mineButton;
    private Button craftButton;
    private Button smeltButton;

    public QuickActionCard(String target) {
        this.target = target == null ? "" : target;
    }

    @Override
    protected String titleKey() {
        return "card.minecraftai.quick";
    }

    @Override
    protected int bodyHeight() {
        return 82;
    }

    @Override
    public void setBounds(int x, int y, int w, int h) {
        super.setBounds(x, y, w, h);
        layoutWidgets();
    }

    @Override
    public void addWidgets(Consumer<AbstractWidget> sink) {
        Font renderer = Minecraft.getInstance().font;
        idField = new EditBox(renderer, 0, 0, 84, INPUT_H, Component.translatable("quick.minecraftai.id"));
        idField.setValue("minecraft:stone");
        idField.setMaxLength(128);
        idField.setSuggestion(Theme.tr("quick.minecraftai.id"));
        countField = new EditBox(renderer, 0, 0, 36, INPUT_H, Component.translatable("quick.minecraftai.count"));
        // An empty count is meaningful for Mine: it requests the same ten-minute exploration
        // and collection window as a player who says "mine coal" without naming a quantity.
        countField.setValue("");
        countField.setMaxLength(3);
        countField.setSuggestion(Theme.tr("quick.minecraftai.count"));

        comeButton = button("btn.minecraftai.come", () -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                BotCommandBridge.command(target, "move", client.player.blockPosition().toShortString().replace(",", ""), "", 1);
            }
        });
        pauseButton = button("btn.minecraftai.pause", () -> BotCommandBridge.command(
                target, snapshot != null && snapshot.missionPaused() ? "resume" : "pause", "", "", 1));
        stopButton = button("btn.minecraftai.stop", () -> BotCommandBridge.command(target, "abort", "", "", 1));
        eatButton = button("btn.minecraftai.eat", () -> BotCommandBridge.command(target, "eat", "", "", 1));
        mineButton = button("btn.minecraftai.mine", () -> BotCommandBridge.command(target, "mine", idField.getValue(), "", count()));
        craftButton = button("btn.minecraftai.craft", () -> BotCommandBridge.command(target, "craft", idField.getValue(), "", count()));
        smeltButton = button("btn.minecraftai.smelt", () -> BotCommandBridge.command(target, "smelt", idField.getValue(), "minecraft:iron_ingot", count()));

        layoutWidgets();
        sink.accept(idField);
        sink.accept(countField);
        sink.accept(comeButton);
        sink.accept(pauseButton);
        sink.accept(stopButton);
        sink.accept(eatButton);
        sink.accept(mineButton);
        sink.accept(craftButton);
        sink.accept(smeltButton);
    }

    @Override
    public void refresh(BotSnapshotS2C snapshot, List<BotClientState.ChatLine> chat) {
        super.refresh(snapshot, chat);
        if (pauseButton != null) {
            pauseButton.setMessage(Component.translatable(snapshot != null && snapshot.missionPaused()
                    ? "btn.minecraftai.resume" : "btn.minecraftai.pause"));
        }
    }

    @Override
    protected void renderBody(GuiGraphics context, int mouseX, int mouseY, float delta, Font renderer, int bx, int by, int bw, int bh) {
        context.drawString(renderer, Theme.tr("quick.minecraftai.input_hint"), bx, by, Theme.TEXT_DIM);
    }

    private Button button(String key, Runnable action) {
        return Button.builder(Component.translatable(key), button -> action.run()).bounds(0, 0, 38, INPUT_H).build();
    }

    private void layoutWidgets() {
        if (idField == null) {
            return;
        }
        int bx = x + Theme.PAD;
        int by = y + 40;
        int bw = w - Theme.PAD * 2;
        int quarter = Math.max(24, (bw - 9) / 4);
        comeButton.setPosition(bx, by);
        comeButton.setSize(quarter, INPUT_H);
        pauseButton.setPosition(bx + quarter + 3, by);
        pauseButton.setSize(quarter, INPUT_H);
        stopButton.setPosition(bx + quarter * 2 + 6, by);
        stopButton.setSize(quarter, INPUT_H);
        eatButton.setPosition(bx + quarter * 3 + 9, by);
        eatButton.setSize(Math.max(24, bw - quarter * 3 - 9), INPUT_H);
        idField.setPosition(bx, by + 22);
        idField.setSize(Math.max(76, bw - 42), INPUT_H);
        countField.setPosition(bx + bw - 36, by + 22);
        countField.setSize(36, INPUT_H);
        int third = Math.max(34, (bw - 8) / 3);
        mineButton.setPosition(bx, by + 44);
        mineButton.setSize(third, INPUT_H);
        craftButton.setPosition(bx + third + 4, by + 44);
        craftButton.setSize(third, INPUT_H);
        smeltButton.setPosition(bx + third * 2 + 8, by + 44);
        smeltButton.setSize(bw - third * 2 - 8, INPUT_H);
    }

    private int count() {
        String value = countField.getValue().trim();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }
}
