package io.github.zoyluo.minecraftai.client.screen.ui;

import io.github.zoyluo.minecraftai.client.BotClientState;
import io.github.zoyluo.minecraftai.network.payload.BotSnapshotS2C;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;

public interface PanelComponent {
    void setBounds(int x, int y, int w, int h);

    int preferredHeight();

    void refresh(BotSnapshotS2C snapshot, List<BotClientState.ChatLine> chat);

    void render(GuiGraphics context, int mouseX, int mouseY, float delta, Font renderer);

    default boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    default boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return false;
    }

    default void addWidgets(Consumer<AbstractWidget> sink) {
    }
}
