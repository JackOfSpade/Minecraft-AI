package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Whether the tool a digger holds can break a block at all, by vanilla's own rate. Vanilla gives every block a break rate per tick
 * ({@link BlockState#getDestroyProgress}: the held tool's speed over the block's hardness, over 30 when the tool is the right one
 * to harvest it and over 100 when it is not, scaled by enchantments and effects), so a block takes {@code ceil(1 / rate)} ticks, the
 * rate is zero for a block of hardness -1 (bedrock, barriers), and the harvest rules are already in the number: obsidian takes a
 * diamond pickaxe 188 ticks, an iron pickaxe more than 800 and a bare hand 5000.
 *
 * <p>Nothing here is a limit of its own. {@link BlockMiner} gives every break {@link BlockMiner#MINE_TIMEOUT_TICKS} and fails with
 * {@code mine_timeout} after them; a block whose vanilla break time is longer than that cannot be broken through the miner, and
 * {@link #refusal} says so before the first swing instead of after two hundred ticks of swinging. A bare hand on stone takes 150
 * ticks and is legal (slow); on deepslate it takes 300 and is refused.</p>
 */
public final class BreakEffort {
    /** Typed refusal: no tool breaks it (vanilla hardness -1: bedrock, barrier, end portal frame ...). */
    public static final String UNBREAKABLE = "unbreakable";
    /** Typed refusal: it breaks, but not inside the window of {@link BlockMiner}, with what the digger holds (obsidian by hand, deepslate by hand). */
    public static final String TOO_SLOW = "too_slow";

    private BreakEffort() {
    }

    /**
     * The verdict on a break rate: {@link #UNBREAKABLE} when no tool makes progress at all, {@link #TOO_SLOW} when the block needs more
     * than {@code windowTicks} ticks, null when it can be broken inside the window.
     */
    static String verdict(float progressPerTick, int windowTicks) {
        if (!(progressPerTick > 0.0F)) {
            return UNBREAKABLE;
        }
        return Math.ceil(1.0D / progressPerTick) > windowTicks ? TOO_SLOW : null;
    }

    /**
     * Why the block at {@code pos} cannot be broken with what {@code bot} holds now, or null when it can: the vanilla rate of the held
     * tool against the block ({@link #verdict}). The caller equips the tool first ({@link ToolSelector#equipBestTool}); the bot is on
     * the ground, since a body in the air breaks at a fifth of the rate and says nothing about the tool.
     */
    public static String refusal(AIPlayerEntity bot, ServerLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return verdict(state.getDestroyProgress(bot, world, pos), BlockMiner.MINE_TIMEOUT_TICKS);
    }
}
