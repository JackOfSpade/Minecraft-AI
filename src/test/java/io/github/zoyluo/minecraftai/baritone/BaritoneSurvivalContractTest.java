package io.github.zoyluo.minecraftai.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
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

    // ------------------------------------------------------------------------------------------------------------------
    // The result of every policy call is honoured, and the X-ray ban holds for all mod code
    // ------------------------------------------------------------------------------------------------------------------

    /** The text of the braces block that starts at the first '{' at or after {@code from}. */
    private static String block(String source, int from) {
        int open = source.indexOf('{', from);
        assertTrue(open >= 0, "no block after offset " + from);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces after offset " + from);
    }

    /** The only conditions a refusal test may have in front of the policy call: nothing, or the typed-player guard of windowClick. */
    private static final java.util.Set<String> KNOWN_GUARDS = java.util.Set.of("!", "!(player instanceof AIPlayerEntity self) || !");

    /** The only bodies of a refusal branch: leave the entry (in the form its return type needs), aborting a running break first. */
    private static final java.util.Set<String> KNOWN_REFUSALS = java.util.Set.of(
            "return;", "return false;", "return InteractionResult.FAIL;",
            "mining.abort(self); mining = null; hitting = false; return false;");

    /**
     * Every use of {@code call} in {@code method} is a refusal test of a KNOWN shape, {@code if (<guard>call(...).allowed())} with
     * one of {@link #KNOWN_GUARDS}, whose block is one of {@link #KNOWN_REFUSALS} (it leaves the entry): a controller entry cannot
     * ask the policy and then act on a refusal anyway, and a new shape (a refusal that logs and carries on, a condition that only
     * sometimes refuses) fails here until it is reviewed and added on purpose.
     */
    private static void assertRefusalEndsTheEntry(String method, String call, int expectedUses) {
        int uses = 0;
        for (int at = method.indexOf(call); at >= 0; at = method.indexOf(call, at + 1)) {
            uses++;
            String before = method.substring(Math.max(0, at - 120), at);
            int ifAt = before.lastIndexOf("if (");
            assertTrue(ifAt >= 0, call + " must be tested as `if (<guard>" + call + "...allowed())`");
            String guard = before.substring(ifAt + "if (".length());
            assertTrue(KNOWN_GUARDS.contains(guard), call + ": unknown refusal guard `" + guard + "`, known: " + KNOWN_GUARDS);
            int close = method.indexOf(".allowed())", at);
            int open = method.indexOf('{', at);
            assertTrue(close > 0 && close < open && method.substring(close + ".allowed())".length(), open).isBlank(),
                    call + ": the `if` must test exactly the decision's allowed()");
            String refusalBranch = block(method, at).replaceAll("\\s+", " ").replaceAll("^\\{ | \\}$", "");
            assertTrue(KNOWN_REFUSALS.contains(refusalBranch), call + ": unknown refusal branch `" + refusalBranch + "`, known: " + KNOWN_REFUSALS);
        }
        assertEquals(expectedUses, uses, call + " uses in the method");
    }

    @Test
    void anUnknownRefusalShapeIsRejectedByTheContractCheck() {
        assertRefusalEndsTheEntry("void m() { if (!BaritoneBreakPlacePolicy.checkBreak(x, y).allowed()) { return false; } }",
                "BaritoneBreakPlacePolicy.checkBreak(", 1);
        for (String bad : new String[] {
                "void m() { if (flag || !BaritoneBreakPlacePolicy.checkBreak(x, y).allowed()) { return false; } }", // extra condition
                "void m() { if (!BaritoneBreakPlacePolicy.checkBreak(x, y).allowed()) { log(); } }", // refusal that carries on
                "void m() { if (!BaritoneBreakPlacePolicy.checkBreak(x, y).allowed()) { log(); return false; } }", // extra statement
                "void m() { if (!BaritoneBreakPlacePolicy.checkBreak(x, y).allowed() && flag) { return false; } }"}) { // partial test
            assertThrows(AssertionError.class, () -> assertRefusalEndsTheEntry(bad, "BaritoneBreakPlacePolicy.checkBreak(", 1), bad);
        }
    }


    @Test
    void everyControllerEntryReturnsOnARefusal() throws IOException {
        String controller = read("baritone/ServerPlayerController.java");
        assertRefusalEndsTheEntry(body(controller, "public boolean clickBlock("), "BaritoneBreakPlacePolicy.checkBreak(", 1);
        assertRefusalEndsTheEntry(body(controller, "public InteractionResult processRightClickBlock("), "BaritoneBreakPlacePolicy.checkClickBlock(", 1);
        assertRefusalEndsTheEntry(body(controller, "public InteractionResult processRightClick("), "BaritoneBreakPlacePolicy.checkUseItem(", 1);
        assertRefusalEndsTheEntry(body(controller, "public void windowClick("), "BaritoneBreakPlacePolicy.checkWindowClick(", 1);
        // A break that is under way is re-checked when the cell changes under it, and that refusal aborts it.
        String step = controller.substring(controller.indexOf("private boolean step()"));
        String recheck = step.substring(0, step.indexOf("ActionResult result = mining.tick("));
        assertRefusalEndsTheEntry(recheck, "BaritoneBreakPlacePolicy.checkBreak(", 1);
        assertTrue(block(recheck, recheck.indexOf("BaritoneBreakPlacePolicy.checkBreak(")).contains("mining.abort("), "the refused break is aborted");
        // The context hands the process gate's boolean straight to Baritone.
        assertTrue(body(read("baritone/ServerPlayerContext.java"), "public boolean allowScanningProcess(")
                .contains("return BaritoneBreakPlacePolicy.allowScanningProcess("));
    }

    @Test
    void theClickAllowanceForOpeningNeedsTheBlockToWinAndTheBlockSetType() throws IOException {
        String policy = read("baritone/BaritoneBreakPlacePolicy.java");
        assertTrue(policy.contains("opensOnClick(support, itemUseWinsOverBlock(bot))"), "the item-wins case does not get the open allowance");
        String opens = body(policy, "static boolean opensOnClick(");
        assertTrue(opens.contains("if (itemUseWins) {\n            return false;"), "an item that wins over the block is refused first");
        assertTrue(opens.contains("door.type().canOpenByHand()") && opens.contains("minecraftai$type().canOpenByHand()"),
                "doors and trapdoors both ask the BlockSetType, not a block identity");
        // Vanilla (ServerPlayerGameMode#useItemOn): sneaking only makes the item win when a hand holds something.
        String wins = body(policy, "static boolean itemUseWinsOverBlock(");
        assertTrue(wins.contains("bot.isSecondaryUseActive() && (!bot.getMainHandItem().isEmpty() || !bot.getOffhandItem().isEmpty())"),
                "a sneaking bot with both hands empty still opens the door");
        assertTrue(!policy.contains("IRON_TRAPDOOR"), "no identity test against a specific trapdoor");
        assertTrue(Files.readString(Path.of("src/main/resources/minecraftai.mixins.json")).contains("TrapDoorBlockTypeInvokerMixin"));
    }

    @Test
    void theBreakVerdictCacheIsDroppedOnServerStartAndOnEveryTagLoad() throws IOException {
        String policy = read("baritone/BaritoneBreakPlacePolicy.java");
        assertTrue(policy.contains("return BreakRule.denialOf(block);") && !policy.contains("ConcurrentHashMap"), "no policy-private static cache");
        String rule = read("mining/BreakRule.java");
        assertTrue(rule.contains("BreakVerdictCache.BLOCKS.verdict("), "the shared rule reads the shared cache");
        assertTrue(!rule.contains("import baritone.") && !rule.contains("minecraftai.baritone."), "the shared rule imports no Baritone type");
        String mod = read("MinecraftAiMod.java");
        assertTrue(mod.contains("ServerLifecycleEvents.SERVER_STARTING.register(server -> BreakVerdictCache.invalidate())"));
        assertTrue(mod.contains("CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> BreakVerdictCache.invalidate())"));
        String cache = read("mining/BreakVerdictCache.java");
        assertTrue(!cache.contains("import baritone.") && !cache.contains("BaritoneBreakPlacePolicy."),
                "the lifecycle hook reaches no Baritone class");
        // The planning list Baritone iterates is a snapshot of these verdicts, so it follows the cache's generation.
        assertTrue(policy.contains("BreakVerdictCache.BLOCKS.generation()"), "the denied-block list is rebuilt after a tag reload");
    }

    /** Baritone's world-scanning processes and scanner: they know where an ore is without having seen it (an X-ray). */
    private static final String[] SCANNING_ACCESSORS = {
            "getMineProcess(", "getExploreProcess(", "getFarmProcess(", "getGetToBlockProcess(", "getBuilderProcess(", "getWorldScanner("};

    /** The only file of the mod that may name a scanning accessor: the driver's busy check. */
    private static final Set<String> SCANNING_ACCESSOR_FILES = Set.of("BaritoneDriver.java");

    @Test
    void noModCodeOutsideTheBusyCheckReachesAScanningProcessOrTheWorldScanner() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (String accessor : SCANNING_ACCESSORS) {
                    if (source.contains(accessor) && !SCANNING_ACCESSOR_FILES.contains(file.getFileName().toString())) {
                        offenders.add(file + " uses " + accessor);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "X-ray ban: a scanning process or the world scanner is reachable from " + offenders);
    }

    @Test
    void theBusyCheckOnlyAsksWhetherAScanningProcessIsActive() throws IOException {
        String driver = read("baritone/BaritoneDriver.java");
        for (String accessor : SCANNING_ACCESSORS) {
            for (int at = driver.indexOf(accessor); at >= 0; at = driver.indexOf(accessor, at + 1)) {
                assertTrue(driver.startsWith(accessor + ").isActive()", at), "BaritoneDriver may only read " + accessor + ").isActive()");
            }
        }
        assertTrue(!driver.contains("getWorldScanner("), "the driver never scans");
    }
}
