package io.github.zoyluo.minecraftai.client.screen.ui.cards;

import io.github.zoyluo.minecraftai.client.screen.ui.Theme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

public final class StatusCard extends PanelCard {
    @Override
    protected String titleKey() {
        return "card.minecraftai.status";
    }

    @Override
    protected int bodyHeight() {
        return 100;
    }

    @Override
    protected void renderBody(DrawContext context, int mouseX, int mouseY, float delta, TextRenderer renderer, int bx, int by, int bw, int bh) {
        if (snapshot == null) {
            context.drawTextWithShadow(renderer, Theme.tr("status.minecraftai.waiting"), bx, by, Theme.TEXT_DIM);
            return;
        }
        drawStat(context, renderer, bx, by, bw, Theme.tr("status.minecraftai.hp"), snapshot.health(), snapshot.maxHealth(), Theme.HP);
        drawStat(context, renderer, bx, by + 20, bw, Theme.tr("status.minecraftai.food"), snapshot.food(), 20.0F, Theme.FOOD);
        drawStat(context, renderer, bx, by + 40, bw, Theme.tr("status.minecraftai.progress"), snapshot.progress(), 1.0F, Theme.OK);

        String task = Theme.tr("task.minecraftai." + snapshot.taskName());
        if (task.equals("task.minecraftai." + snapshot.taskName())) {
            task = snapshot.taskName();
        }
        String brain = snapshot.brainBusy() ? Theme.tr("status.minecraftai.brain.busy") : Theme.tr("status.minecraftai.brain.idle");
        int brainColor = snapshot.brainBusy() ? Theme.ACCENT : Theme.TEXT_DIM;
        context.drawTextWithShadow(renderer, Theme.tr("status.minecraftai.task", task, snapshot.taskState()), bx, by + 61, Theme.TEXT);
        context.drawTextWithShadow(renderer, brain, bx, by + 73, brainColor);
        String tokens = Theme.tr("status.minecraftai.tokens", snapshot.promptTokens(), snapshot.completionTokens());
        context.drawTextWithShadow(renderer, tokens, bx + Math.max(0, bw - renderer.getWidth(tokens)), by + 73, Theme.TEXT_DIM);
        // Real-time coordinates: the snapshot is pushed periodically by the server and refreshes as the bot moves (so you can see exactly where the bot is while mining/diving).
        String pos = Theme.tr("status.minecraftai.pos", snapshot.x(), snapshot.y(), snapshot.z());
        context.drawTextWithShadow(renderer, pos, bx, by + 87, Theme.TEXT);
    }

    private static void drawStat(DrawContext context, TextRenderer renderer, int x, int y, int w, String label, float value, float max, int color) {
        String text = max == 1.0F ? label + " " + (int) (value * 100) + "%" : label + " " + (int) value + "/" + (int) max;
        context.drawTextWithShadow(renderer, text, x, y, Theme.TEXT);
        Theme.bar(context, x, y + 10, w, 7, max <= 0.0F ? 0.0F : value / max, color);
    }
}
