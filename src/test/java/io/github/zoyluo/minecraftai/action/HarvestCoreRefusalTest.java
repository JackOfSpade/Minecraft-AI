package io.github.zoyluo.minecraftai.action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Which refusals of a mining start a gather may take as final. The eyes that nominate a log see further than the hand that mines
 * it, so a log behind panes, a fence or a leaf the bot may not break is refused with a typed reason the moment it is asked for, and
 * waiting where it stands changes neither the refusal nor the world.
 */
class HarvestCoreRefusalTest {
    @Test
    void aTargetTheBotDoesNotSeeOrMayNotReachIsRefusedForGood() {
        assertTrue(HarvestCore.refusedAsUnmineable(ActionResult.failed(MiningController.TARGET_NOT_OBSERVED)));
        assertTrue(HarvestCore.refusedAsUnmineable(ActionResult.failed(MiningController.TARGET_OBSTRUCTED)));
    }

    @Test
    void aBreakThatStartedOrADifferentRefusalIsNotAFinalVerdictOnTheTarget() {
        assertFalse(HarvestCore.refusedAsUnmineable(ActionResult.IN_PROGRESS));
        assertFalse(HarvestCore.refusedAsUnmineable(ActionResult.SUCCESS));
        assertFalse(HarvestCore.refusedAsUnmineable(ActionResult.failed("invalid_mining_target")), "a malformed request is the caller's");
        assertFalse(HarvestCore.refusedAsUnmineable(ActionResult.failed("support_underfoot")),
                "a block the bot stands on stops being refused when it steps off");
        assertFalse(HarvestCore.refusedAsUnmineable(ActionResult.failed("")));
    }
}
