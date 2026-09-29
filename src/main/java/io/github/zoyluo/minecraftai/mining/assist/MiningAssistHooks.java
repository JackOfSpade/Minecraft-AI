package io.github.zoyluo.minecraftai.mining.assist;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * O(1), exception-free static entry points that other classes call (mining-assist design 3.3 and 8.1).
 * Every hook starts with the single static mode check, so with the assist {@code off} it costs one
 * volatile read and changes nothing. A hook never throws: it must not be able to break the block break
 * or placement that called it.
 *
 * <p>Placement notes go straight to {@link BotEdits#notePlaced}; the break hook lives here because it also
 * queues the cell for the coordinator's break peek.</p>
 */
public final class MiningAssistHooks {
    private MiningAssistHooks() {
    }

    /**
     * A block break by {@code player} just finished at {@code pos} (called from {@code MiningController}
     * after the break packet). For a bot with assist state it queues the cell in the pending-break ring
     * (capacity {@value MiningAssistState#PENDING_BREAK_CAP}, oldest dropped) that the coordinator drains
     * through {@link BreakPeek}. A bot without assist state (it has not sensed yet, or it is mining on the
     * surface) costs one map lookup and nothing is stored. The hook does not decide that the break took
     * effect: the peek observes the cell and only then notes it in the bot's dug ring. Ignores non-bot players.
     */
    public static void onBotBreak(ServerPlayer player, BlockPos pos) {
        try {
            if (!MiningAssistRuntime.senseConfigured()) {
                return;
            }
            if (!(player instanceof AIPlayerEntity bot) || pos == null) {
                return;
            }
            MiningAssistState state = MiningAssistRegistry.getIfPresent(bot.getUUID());
            if (state != null) {
                state.pendingBreaks().offer(pos.asLong());
            }
        } catch (RuntimeException exception) {
            MiningAssistRuntime.noteHookFailure("onBotBreak", exception);
        }
    }
}
