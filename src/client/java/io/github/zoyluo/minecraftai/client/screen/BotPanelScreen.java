package io.github.zoyluo.minecraftai.client.screen;

import io.github.zoyluo.minecraftai.client.BotClientState;
import io.github.zoyluo.minecraftai.client.BotCommandBridge;
import io.github.zoyluo.minecraftai.client.screen.ui.ChatView;
import io.github.zoyluo.minecraftai.client.screen.ui.InventoryView;
import io.github.zoyluo.minecraftai.client.screen.ui.PanelComponent;
import io.github.zoyluo.minecraftai.client.screen.ui.Theme;
import io.github.zoyluo.minecraftai.client.screen.ui.cards.GoalView;
import io.github.zoyluo.minecraftai.client.screen.ui.cards.QuickActionCard;
import io.github.zoyluo.minecraftai.client.screen.ui.cards.SettingsCard;
import io.github.zoyluo.minecraftai.client.screen.ui.cards.StatusCard;
import io.github.zoyluo.minecraftai.network.payload.BotSnapshotS2C;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class BotPanelScreen extends Screen {
    public enum Mode {
        CHAT_STATUS,
        ACTIONS,
        SETTINGS,
        INVENTORY,
        GOAL
    }

    private final List<PanelComponent> leftCards = new ArrayList<>();
    private final List<PanelComponent> laidOutCards = new ArrayList<>();
    private final List<AbstractWidget> panelWidgets = new ArrayList<>();
    private final Mode mode;
    private ChatView chat;
    private EditBox input;
    private Button sendButton;
    private Button goalButton;
    private Button inventoryButton;
    private Button settingsButton;
    private Button closeButton;
    private int px;
    private int py;
    private int pw;
    private int ph;
    private int leftW;
    private int rightW;
    private String target = "";

    public BotPanelScreen(Mode mode) {
        super(mode == Mode.GOAL ? Component.literal("Goal & Execution Chain")
                : Component.translatable(mode == Mode.ACTIONS ? "screen.minecraftai.actions_panel"
                : mode == Mode.SETTINGS ? "screen.minecraftai.settings_panel"
                : mode == Mode.INVENTORY ? "screen.minecraftai.inventory_panel"
                : "screen.minecraftai.panel"));
        this.mode = mode;
    }

    public Mode mode() {
        return mode;
    }

    @Override
    protected void init() {
        target = BotClientState.INSTANCE.targetBot();
        panelWidgets.clear();
        computeLayout();
        buildCards();
        layoutComponents();
        if (mode == Mode.CHAT_STATUS) {
            int stripY = py + ph - Theme.INPUT_H - Theme.PAD + 1;
            input = new EditBox(font, px + leftW + Theme.GUTTER + Theme.PAD, stripY,
                    Math.max(60, rightW - Theme.PAD * 2 - 54), 18, Component.translatable("chat.minecraftai.input"));
            input.setMaxLength(512);
            input.setSuggestion(Theme.tr("chat.minecraftai.input"));
            sendButton = Button.builder(Component.translatable("btn.minecraftai.send"), button -> sendChat())
                    .bounds(px + pw - Theme.PAD - 48, stripY, 48, 18)
                    .build();
            register(input);
            register(sendButton);
        }
        goalButton = Button.builder(Component.literal(mode == Mode.GOAL ? "Chat" : "Goal"), button -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new BotPanelScreen(mode == Mode.GOAL ? Mode.CHAT_STATUS : Mode.GOAL));
                    }
                })
                .bounds(px + pw - 178, py + 4, 40, 14)
                .build();
        inventoryButton = Button.builder(Component.translatable(mode == Mode.INVENTORY ? "btn.minecraftai.chat" : "btn.minecraftai.inventory"), button -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new BotPanelScreen(mode == Mode.INVENTORY ? Mode.CHAT_STATUS : Mode.INVENTORY));
                    }
                })
                .bounds(px + pw - 134, py + 4, 40, 14)
                .build();
        settingsButton = Button.builder(Component.translatable(mode == Mode.SETTINGS ? "btn.minecraftai.chat" : "btn.minecraftai.settings"), button -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new BotPanelScreen(mode == Mode.SETTINGS ? Mode.CHAT_STATUS : Mode.SETTINGS));
                    }
                })
                .bounds(px + pw - 90, py + 4, 40, 14)
                .build();
        closeButton = Button.builder(Component.translatable("btn.minecraftai.close"), button -> onClose())
                .bounds(px + pw - 46, py + 4, 38, 14)
                .build();
        register(goalButton);
        register(inventoryButton);
        register(settingsButton);
        register(closeButton);
        for (PanelComponent card : laidOutCards) {
            card.addWidgets(this::register);
        }
        BotCommandBridge.subscribe(target, true);
        if (input != null) {
            setInitialFocus(input);
        }
    }

    private void register(AbstractWidget widget) {
        addRenderableWidget(widget);   // Register so it takes over click/focus/input routing
        panelWidgets.add(widget);   // Rendering is handled manually by this class (see render), bypassing super.render's background blur pass
    }

    @Override
    public void onClose() {
        BotCommandBridge.subscribe(target, false);
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (chat != null && chat.mouseScrolled(mouseX, mouseY, verticalAmount)) {
            return true;
        }
        for (PanelComponent card : laidOutCards) {
            if (card.mouseScrolled(mouseX, mouseY, verticalAmount)) {
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        for (PanelComponent card : laidOutCards) {
            if (card.mouseClicked(click.x(), click.y(), click.button())) {
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean keyPressed(KeyEvent keyInput) {
        int keyCode = keyInput.key();
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && input != null && input.isFocused()) {
            sendChat();
            return true;
        }
        return super.keyPressed(keyInput);
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        Theme.panel(context, px, py, pw, ph, Theme.PANEL_BG);
        drawTitleBar(context);
        BotSnapshotS2C snapshot = BotClientState.INSTANCE.snapshot();
        List<BotClientState.ChatLine> transcript = BotClientState.INSTANCE.transcript();
        for (PanelComponent card : laidOutCards) {
            card.refresh(snapshot, transcript);
            card.render(context, mouseX, mouseY, delta, font);
        }
        if (chat != null) {
            chat.refresh(snapshot, transcript);
            chat.render(context, mouseX, mouseY, delta, font);
        }
        if (mode == Mode.CHAT_STATUS) {
            drawInputStrip(context);
        }
        // Render widgets manually, without calling super.render() -- this bypasses the 1.21 screen background blur/darken pass (otherwise the panel content would be blurred while the widgets stay sharp).
        for (AbstractWidget widget : panelWidgets) {
            widget.render(context, mouseX, mouseY, delta);
        }
    }

    private void computeLayout() {
        boolean docked = width >= 420;
        if (mode == Mode.ACTIONS) {
            pw = docked ? Math.min(260, Math.max(220, (int) (width * 0.24F))) : Math.max(220, width - 20);
            ph = Math.max(150, Math.min(height - 24, 180));
        } else if (mode == Mode.SETTINGS) {
            pw = docked ? Math.min(300, Math.max(260, (int) (width * 0.28F))) : Math.max(240, width - 20);
            ph = Math.max(150, Math.min(height - 24, 190));
        } else if (mode == Mode.INVENTORY) {
            // Must fit a 9-column x 18px grid (162) plus left/right padding; height must fit the equipment row + AI's 4 rows + player's 4 rows
            pw = docked ? Math.min(240, Math.max(200, (int) (width * 0.24F))) : Math.max(200, width - 20);
            ph = Math.max(220, Math.min(height - 24, 272));
        } else if (mode == Mode.GOAL) {
            // Goal & Execution Chain: single column, fits the full step chain
            pw = docked ? Math.min(300, Math.max(240, (int) (width * 0.26F))) : Math.max(240, width - 20);
            ph = Math.max(180, Math.min(height - 24, 260));
        } else {
            pw = docked ? Math.min(520, Math.max(360, (int) (width * 0.48F))) : Math.max(240, width - 20);
            // Critical: ph must fit on screen (including top/bottom margins), otherwise the bottom input box gets pushed off the bottom edge of the screen
            ph = Math.max(160, Math.min(height - 24, 380));
        }
        px = docked ? width - pw - 12 : (width - pw) / 2;
        py = 12;
        leftW = mode == Mode.ACTIONS || mode == Mode.SETTINGS || mode == Mode.INVENTORY || mode == Mode.GOAL ? pw : Math.max(160, Math.round(pw * 0.42F));
        rightW = pw - leftW - Theme.GUTTER;
    }

    private void buildCards() {
        leftCards.clear();
        if (mode == Mode.ACTIONS) {
            leftCards.add(new QuickActionCard(target));
            chat = null;
            return;
        }
        if (mode == Mode.SETTINGS) {
            leftCards.add(new SettingsCard(target));
            chat = null;
            return;
        }
        if (mode == Mode.INVENTORY) {
            leftCards.add(new InventoryView(target));
            chat = null;
            return;
        }
        if (mode == Mode.GOAL) {
            leftCards.add(new GoalView());
            chat = null;
            return;
        }
        // CHAT_STATUS: only place the status card (health/hunger/progress/task). Goal & Execution Chain is reached via the top "Goal" button, inventory via the "Inventory" button.
        leftCards.add(new StatusCard());
        chat = new ChatView();
    }

    private void layoutComponents() {
        laidOutCards.clear();
        int leftX = px + Theme.PAD;
        int cardY = py + Theme.TITLE_H + Theme.PAD;
        int cardW = leftW - Theme.PAD * 2;
        // CHAT_STATUS's left column bottom line must leave room for the bottom input strip; other modes use the panel bottom
        int bottom = py + ph - Theme.PAD - (mode == Mode.CHAT_STATUS ? Theme.INPUT_H : 0);
        for (PanelComponent card : leftCards) {
            int remaining = bottom - cardY;
            if (remaining < 40) {
                break;  // Stop laying out once it no longer fits, to avoid an unlaid-out card rendering outside the panel at its default (0,0)
            }
            int cardH = Math.min(card.preferredHeight(), remaining);
            card.setBounds(leftX, cardY, cardW, cardH);
            laidOutCards.add(card);
            cardY += cardH + Theme.GUTTER;
        }
        if (chat != null) {
            int rightX = px + leftW + Theme.GUTTER;
            int rightY = py + Theme.TITLE_H + Theme.PAD;
            int chatH = ph - Theme.TITLE_H - Theme.PAD * 3 - Theme.INPUT_H;
            chat.setBounds(rightX + Theme.PAD, rightY, rightW - Theme.PAD * 2, Math.max(40, chatH));
        }
    }

    private void drawTitleBar(GuiGraphics context) {
        String name = displayTarget();
        if (mode == Mode.GOAL) {
            context.drawString(font, Component.literal("Goal & Execution Chain · " + name), px + Theme.PAD, py + 6, Theme.TEXT_STRONG);
        } else {
            String titleKey = mode == Mode.ACTIONS ? "screen.minecraftai.actions_title"
                    : mode == Mode.SETTINGS ? "screen.minecraftai.settings_title"
                    : mode == Mode.INVENTORY ? "screen.minecraftai.inventory_title"
                    : "screen.minecraftai.title";
            context.drawString(font, Theme.tr(titleKey, name), px + Theme.PAD, py + 6, Theme.TEXT_STRONG);
        }
        context.hLine(px + Theme.PAD, px + pw - Theme.PAD - 1, py + Theme.TITLE_H, Theme.BORDER);
        if (mode == Mode.CHAT_STATUS) {
            context.vLine(px + leftW, py + Theme.TITLE_H + 1, py + ph - Theme.PAD, Theme.BORDER);
        }
    }

    private void drawInputStrip(GuiGraphics context) {
        int x = px + leftW + Theme.GUTTER + Theme.PAD - 2;
        int y = py + ph - Theme.INPUT_H - Theme.PAD - 1;
        int w = rightW - Theme.PAD * 2 + 4;
        int h = Theme.INPUT_H + 2;
        Theme.panel(context, x, y, w, h, Theme.CHAT_INPUT_BG);
        context.hLine(x + 1, x + w - 2, y + 1, Theme.BORDER_BRIGHT);
    }

    private String displayTarget() {
        BotSnapshotS2C snapshot = BotClientState.INSTANCE.snapshot();
        if (snapshot != null) {
            return snapshot.botName();
        }
        return target == null || target.isBlank() ? Theme.tr("screen.minecraftai.owner_bot") : target;
    }

    private void sendChat() {
        if (input == null) {
            return;
        }
        String text = input.getValue().trim();
        if (text.isEmpty()) {
            return;
        }
        BotCommandBridge.chat(target, text);
        input.setValue("");
    }
}
