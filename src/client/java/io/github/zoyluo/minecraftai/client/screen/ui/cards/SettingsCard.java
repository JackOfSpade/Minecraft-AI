package io.github.zoyluo.minecraftai.client.screen.ui.cards;

import io.github.zoyluo.minecraftai.client.BotClientState;
import io.github.zoyluo.minecraftai.client.BotCommandBridge;
import io.github.zoyluo.minecraftai.client.screen.ui.Theme;
import io.github.zoyluo.minecraftai.network.payload.BotSnapshotS2C;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public final class SettingsCard extends PanelCard {
    private static final int BUTTON_H = 18;

    private final String target;
    private Button manualButton;
    private Button memoryButton;
    private Button reportsButton;
    private Button teleportToButton;  // Teleport to AI (player -> near AI)
    private Button recallButton;      // Recall AI (AI -> near player)

    public SettingsCard(String target) {
        this.target = target == null ? "" : target;
    }

    @Override
    protected String titleKey() {
        return "card.minecraftai.settings";
    }

    @Override
    protected int bodyHeight() {
        return 118;
    }

    @Override
    public void setBounds(int x, int y, int w, int h) {
        super.setBounds(x, y, w, h);
        layoutWidgets();
    }

    @Override
    public void refresh(BotSnapshotS2C snapshot, List<BotClientState.ChatLine> chat) {
        super.refresh(snapshot, chat);
        updateLabels();
    }

    @Override
    public void addWidgets(Consumer<AbstractWidget> sink) {
        manualButton = button("settings.minecraftai.manual", () -> toggle("manual", snapshot == null || !snapshot.manualMode()));
        memoryButton = button("settings.minecraftai.memory", () -> toggle("memory", snapshot == null || !snapshot.memoryToolsEnabled()));
        reportsButton = button("settings.minecraftai.reports", () -> toggle("reports", snapshot == null || !snapshot.verboseReportsEnabled()));
        teleportToButton = button("settings.minecraftai.tp_to_ai",
                () -> BotCommandBridge.teleport(target, io.github.zoyluo.minecraftai.network.payload.BotTeleportC2S.TO_AI));
        recallButton = button("settings.minecraftai.recall_ai",
                () -> BotCommandBridge.teleport(target, io.github.zoyluo.minecraftai.network.payload.BotTeleportC2S.RECALL_AI));
        layoutWidgets();
        updateLabels();
        sink.accept(teleportToButton);
        sink.accept(recallButton);
        sink.accept(manualButton);
        sink.accept(memoryButton);
        sink.accept(reportsButton);
    }

    @Override
    protected void renderBody(GuiGraphics context, int mouseX, int mouseY, float delta, Font renderer, int bx, int by, int bw, int bh) {
        String bot = snapshot == null ? (target.isBlank() ? Theme.tr("screen.minecraftai.owner_bot") : target) : snapshot.botName();
        context.drawString(renderer, Theme.tr("settings.minecraftai.target", bot), bx, by, Theme.TEXT_DIM);
        if (snapshot == null) {
            context.drawString(renderer, Theme.tr("status.minecraftai.waiting"), bx, by + 14, Theme.TEXT_DIM);
        } else {
            String key = "strict_survival".equals(snapshot.operatingProfile())
                    ? "settings.minecraftai.profile.strict" : "settings.minecraftai.profile.operator";
            context.drawString(renderer, Theme.tr("settings.minecraftai.profile", Theme.tr(key)),
                    bx, by + 14, "strict_survival".equals(snapshot.operatingProfile()) ? 0xFF65C18C : 0xFFE4A853);
        }
    }

    private Button button(String key, Runnable action) {
        return Button.builder(Component.translatable(key), button -> action.run()).bounds(0, 0, 120, BUTTON_H).build();
    }

    private void toggle(String key, boolean value) {
        BotCommandBridge.setOption(target, key, value);
    }

    private void layoutWidgets() {
        if (teleportToButton == null) {
            return;
        }
        int bx = x + Theme.PAD;
        int bw = w - Theme.PAD * 2;
        // Teleport row: two buttons side by side (each takes half the width).
        int half = (bw - 4) / 2;
        int tpY = y + 32;
        teleportToButton.setPosition(bx, tpY);
        teleportToButton.setSize(half, BUTTON_H);
        recallButton.setPosition(bx + half + 4, tpY);
        recallButton.setSize(half, BUTTON_H);
        // Three toggles stacked vertically.
        int by = tpY + 24;
        manualButton.setPosition(bx, by);
        manualButton.setSize(bw, BUTTON_H);
        memoryButton.setPosition(bx, by + 22);
        memoryButton.setSize(bw, BUTTON_H);
        reportsButton.setPosition(bx, by + 44);
        reportsButton.setSize(bw, BUTTON_H);
    }

    private void updateLabels() {
        if (manualButton == null) {
            return;
        }
        manualButton.setMessage(label("settings.minecraftai.manual", snapshot != null && snapshot.manualMode()));
        memoryButton.setMessage(label("settings.minecraftai.memory", snapshot == null || snapshot.memoryToolsEnabled()));
        reportsButton.setMessage(label("settings.minecraftai.reports", snapshot == null || snapshot.verboseReportsEnabled()));
        boolean teleportEnabled = snapshot != null && snapshot.effectiveCapabilities().contains("MANUAL_TELEPORT");
        teleportToButton.active = teleportEnabled;
        recallButton.active = teleportEnabled;
    }

    private static Component label(String key, boolean enabled) {
        return Component.literal(Theme.tr(key) + ": " + Theme.tr(enabled ? "settings.minecraftai.on" : "settings.minecraftai.off"));
    }
}
