package io.github.zoyluo.minecraftai.client.screen.ui.cards;

import io.github.zoyluo.minecraftai.client.screen.ui.Theme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * Goal view (GOAL mode): shows the **complete execution chain** of the bot's current goal
 * and which node it is currently on, using as many steps as the available height allows, and
 * keeping the current step scrolled into the center.
 */
public final class GoalView extends PanelCard {
    @Override
    protected String titleKey() {
        return "card.minecraftai.goal";
    }

    @Override
    protected int bodyHeight() {
        return 220; // GOAL full-screen view, fills the entire left column
    }

    @Override
    protected void renderBody(DrawContext context, int mouseX, int mouseY, float delta, TextRenderer renderer, int bx, int by, int bw, int bh) {
        if (snapshot == null) {
            context.drawTextWithShadow(renderer, Theme.tr("goal.minecraftai.empty"), bx, by, Theme.TEXT_DIM);
            return;
        }
        if (snapshot.goalTotalSteps() <= 0) {
            if (snapshot.goalResultStatus().isBlank()) {
                context.drawTextWithShadow(renderer, Theme.tr("goal.minecraftai.empty"), bx, by, Theme.TEXT_DIM);
            } else {
                context.drawTextWithShadow(renderer,
                        Theme.tr("goal.minecraftai.result." + snapshot.goalResultStatus().toLowerCase(java.util.Locale.ROOT)),
                        bx, by, "COMPLETED".equals(snapshot.goalResultStatus()) ? Theme.OK
                                : "PARTIAL".equals(snapshot.goalResultStatus()) ? Theme.SYS
                                : "FAILED".equals(snapshot.goalResultStatus()) ? Theme.HP : Theme.TEXT_DIM);
                context.drawTextWithShadow(renderer, Theme.trim(renderer, snapshot.goalResultSummary(), bw), bx, by + 16, Theme.TEXT);
                context.drawTextWithShadow(renderer,
                        Theme.tr("goal.minecraftai.evidence", snapshot.goalResultMatched(), snapshot.goalResultRequired()),
                        bx, by + 32, Theme.TEXT_DIM);
            }
            return;
        }
        String title = snapshot.goalTitle().isBlank() ? Theme.tr("goal.minecraftai.untitled") : snapshot.goalTitle();
        context.drawTextWithShadow(renderer, Theme.trim(renderer, title, bw), bx, by, Theme.TEXT_STRONG);
        int cur = snapshot.goalCurrentStepIndex();
        int total = snapshot.goalTotalSteps();
        int currentNumber = Math.min(cur + 1, total);
        context.drawTextWithShadow(renderer, Theme.tr("goal.minecraftai.progress", currentNumber, total), bx, by + 12, Theme.TEXT_DIM);

        int rows = snapshot.goalSteps().size();
        int maxRows = Math.max(1, (bh - 27) / Theme.LINE_H);
        int start = 0;
        if (rows > maxRows) {
            start = Math.max(0, Math.min(cur - maxRows / 2, rows - maxRows)); // keep the current step scrolled into the center
        }
        for (int i = 0; i < maxRows && start + i < rows; i++) {
            int idx = start + i;
            boolean current = idx == cur;
            int color = current ? Theme.ACCENT : idx < cur ? Theme.OK : Theme.TEXT_DIM;
            String marker = current ? ">" : idx < cur ? "x" : "-";
            context.drawTextWithShadow(renderer, Theme.trim(renderer, marker + " " + snapshot.goalSteps().get(idx), bw),
                    bx, by + 27 + i * Theme.LINE_H, color);
        }
    }
}
