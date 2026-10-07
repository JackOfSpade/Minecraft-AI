package io.github.zoyluo.minecraftai.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Whether a held tool can break a block is decided by vanilla's own break rate and the one window the miner gives a break
 * ({@link BlockMiner#MINE_TIMEOUT_TICKS}): no invented limit. The rates below are vanilla's formula, tool speed over block hardness
 * over 30 for a tool that harvests the block and over 100 for one that does not.
 */
final class BreakEffortTest {
    private static final int WINDOW = BlockMiner.MINE_TIMEOUT_TICKS;

    private static float rate(float toolSpeed, float hardness, boolean harvests) {
        return toolSpeed / hardness / (harvests ? 30.0F : 100.0F);
    }

    @Test
    void aBlockOfHardnessMinusOneCanNeverBeBroken() {
        // Vanilla's rate for bedrock and barriers is zero whatever the tool.
        assertEquals(BreakEffort.UNBREAKABLE, BreakEffort.verdict(0.0F, WINDOW));
        assertEquals(BreakEffort.UNBREAKABLE, BreakEffort.verdict(Float.NaN, WINDOW));
    }

    @Test
    void stoneByHandIsSlowButLegal() {
        assertNull(BreakEffort.verdict(rate(1.0F, 1.5F, false), WINDOW), "150 ticks by hand fits the window");
    }

    @Test
    void deepslateByHandTakesLongerThanTheMinerWaits() {
        assertEquals(BreakEffort.TOO_SLOW, BreakEffort.verdict(rate(1.0F, 3.0F, false), WINDOW), "300 ticks by hand");
    }

    @Test
    void aPickaxeThatHarvestsMakesTheSameBlocksQuick() {
        assertNull(BreakEffort.verdict(rate(2.0F, 1.5F, true), WINDOW), "a wooden pickaxe on stone");
        assertNull(BreakEffort.verdict(rate(2.0F, 3.0F, true), WINDOW), "a wooden pickaxe on deepslate");
        assertNull(BreakEffort.verdict(rate(4.0F, 3.0F, true), WINDOW), "a stone pickaxe on iron ore");
    }

    @Test
    void obsidianNeedsTheTierThatHarvestsItAndStillTakesAWhile() {
        assertNull(BreakEffort.verdict(rate(8.0F, 50.0F, true), WINDOW), "a diamond pickaxe: 188 ticks");
        assertNull(BreakEffort.verdict(rate(9.0F, 50.0F, true), WINDOW), "a netherite pickaxe");
        assertEquals(BreakEffort.TOO_SLOW, BreakEffort.verdict(rate(6.0F, 50.0F, false), WINDOW),
                "an iron pickaxe does not harvest it: more than 800 ticks");
        assertEquals(BreakEffort.TOO_SLOW, BreakEffort.verdict(rate(1.0F, 50.0F, false), WINDOW), "by hand: 5000 ticks");
    }

    @Test
    void theWindowIsTheMinersOwnAndNotACapOfThisClass() {
        float oneBreakPerFiveHundredTicks = 1.0F / 500.0F;
        assertEquals(BreakEffort.TOO_SLOW, BreakEffort.verdict(oneBreakPerFiveHundredTicks, WINDOW));
        assertNull(BreakEffort.verdict(oneBreakPerFiveHundredTicks, 600), "a longer window lets the same break through");
    }
}
