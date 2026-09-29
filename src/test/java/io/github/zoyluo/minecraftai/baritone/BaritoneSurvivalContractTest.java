package io.github.zoyluo.minecraftai.baritone;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Pins the structure of the strict-survival rules (source contract; the behavior is proven by {@code BaritoneSurvivalGameTests}):
 * every way Baritone changes the world or the inventory asks {@link BaritoneBreakPlacePolicy} first, the scanning processes ask
 * the player context before they start, and the planning rules are installed with the fixed settings.
 */
class BaritoneSurvivalContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    /** The text of the method that starts at {@code signature}, up to the next method of the class. */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing " + signature);
        int end = source.indexOf("\n    @Override", start + signature.length());
        return end < 0 ? source.substring(start) : source.substring(start, end);
    }

    @Test
    void everyControllerEntryAsksThePolicyBeforeActing() throws IOException {
        String controller = read("baritone/ServerPlayerController.java");
        assertTrue(body(controller, "public boolean clickBlock(").contains("BaritoneBreakPlacePolicy.checkBreak("));
        assertTrue(body(controller, "public InteractionResult processRightClickBlock(").contains("BaritoneBreakPlacePolicy.checkClickBlock("));
        assertTrue(body(controller, "public InteractionResult processRightClick(").contains("BaritoneBreakPlacePolicy.checkUseItem("));
        assertTrue(body(controller, "public void windowClick(").contains("BaritoneBreakPlacePolicy.checkWindowClick("));
        String click = body(controller, "public InteractionResult processRightClickBlock(");
        assertTrue(click.indexOf("checkClickBlock(") < click.indexOf("BuildAction.useItemOnHit("), "the click is checked before it is made");
        String use = body(controller, "public InteractionResult processRightClick(");
        assertTrue(use.indexOf("checkUseItem(") < use.indexOf("gameMode.useItem("), "the item use is checked before it is made");
        String window = body(controller, "public void windowClick(");
        assertTrue(window.indexOf("checkWindowClick(") < window.indexOf("menu.clicked("), "the inventory click is checked before it is made");
        assertTrue(body(controller, "public boolean clickBlock(").indexOf("checkBreak(") < body(controller, "public boolean clickBlock(").indexOf("MiningController.driven("),
                "the break is checked before the controller exists");
    }

    @Test
    void scanningProcessesAskThePlayerContextAndStrictSurvivalRefusesThem() throws IOException {
        String context = read("baritone/ServerPlayerContext.java");
        assertTrue(body(context, "public boolean allowScanningProcess(").contains("BaritoneBreakPlacePolicy.allowScanningProcess("));
        String policy = read("baritone/BaritoneBreakPlacePolicy.java");
        assertTrue(policy.contains("PrivilegedCapability.HIDDEN_BLOCK_SCAN"), "the scanning processes are gated by the hidden-scan privilege");
        for (String process : new String[] {"mine", "get_to_block", "farm", "explore", "build"}) {
            assertTrue(policy.contains("\"" + process + "\""), "scanning process not listed: " + process);
        }
    }

    @Test
    void planningRulesAndFixedSettingsAreInstalledTogether() throws IOException {
        String settings = read("baritone/BaritoneSettings.java");
        assertTrue(settings.contains("BaritoneBreakPlacePolicy.installPlanningRules()"));
        assertTrue(settings.contains("allowInventory.value = false"));
        assertTrue(settings.contains("allowBreakAnyway.value"));
        assertTrue(read("baritone/BaritoneBreakPlacePolicy.java").contains("blocksToDisallowBreaking"));
    }

    @Test
    void goalsThatReachBaritoneAreCoordinateGoals() throws IOException {
        String goals = read("baritone/BaritoneGoals.java");
        assertTrue(goals.contains("goal_type_not_allowed") && goals.contains("target_not_observed"));
        assertTrue(!goals.contains("getMineProcess()") && !goals.contains("getExploreProcess()") && !goals.contains("getFarmProcess()")
                && !goals.contains("getGetToBlockProcess()"), "BaritoneGoals must not start a scanning process");
    }
}
