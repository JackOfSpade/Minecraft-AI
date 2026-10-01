package io.github.zoyluo.minecraftai.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Source-contract pins for the failure paths of the Baritone layer (it ships in the user's jar whatever the engine is, so a failing
 * Baritone must never take a bot's tick, the legacy navigator or the server down): what runs on a driven tick, what a route
 * lifecycle does with the water bookkeeping, and what the mixins that inject into vanilla unconditionally may and may not do.
 */
class BaritoneRobustnessContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature + " must exist");
        int end = source.indexOf("\n    }\n", start);
        assertTrue(end > start, signature + " must end with a method-level closing brace");
        return source.substring(start, end);
    }

    // ---- 1/2/3: fail-soft after Baritone is live -------------------------------------------------------------------------

    @Test
    void theDriverContainsAnyThrowableAndClassifiesIt() throws IOException {
        String driver = read("baritone/BaritoneDriver.java");
        assertFalse(driver.contains("catch (RuntimeException"), "a linkage failure on a driven tick must not escape the bot's tick");
        assertEquals(2, count(driver, "} catch (Throwable failure) {"), "beforePhysics and afterPhysics both contain any Throwable");
        String failed = method(driver, "private static void tickFailed(");
        assertTrue(failed.contains("VirtualMachineError") && failed.contains("throw fatal"), "true VM errors are rethrown");
        assertTrue(failed.contains("NavEngineSelector.isInitialisationFailure(failure)")
                && failed.contains("NavEngineSelector.markBaritoneUnavailable(event, failure)"), "a linkage-type failure retires Baritone");
        assertTrue(failed.contains("BaritoneRegistry.INSTANCE.reset(bot, reason)"), "an ordinary failure resets that bot's Baritone only");
        assertTrue(failed.contains("NavigationMeasurement.noteBaritoneFallback(bot)"),
                "a contained driver failure (including the existing testFault seam) invalidates an active Baritone measurement");
        String gameTest = Files.readString(Path.of("src/gametest/java/io/github/zoyluo/minecraftai/baritone/BaritoneNavigationGameTests.java"));
        assertTrue(gameTest.contains("aLinkageFailureInsideADrivenTickRetiresBaritoneAndTheBotContinuesLegacy")
                        && gameTest.contains("aLinkageFailureAfterPhysicsInsideADrivenTickRetiresBaritoneAndTheBotContinuesLegacy")
                        && gameTest.contains("\"before_physics\"") && gameTest.contains("\"after_physics\"")
                        && gameTest.contains("linkageFailureInsideADrivenTick(context,"),
                "separate linkage-failure GameTests must retain beforePhysics and afterPhysics contained-driver coverage");
        String afterEnvironment = "baritone_navigation_game_tests_a_linkage_failure_after_physics_inside_adriven_tick_retires_baritone_and_the_bot_continues_legacy";
        assertTrue(gameTest.contains("environment = \"minecraftai-gametest:" + afterEnvironment + "\""),
                "the afterPhysics linkage-failure GameTest must name its registered test environment");
        Path environments = Path.of("src/gametest/resources/data/minecraftai-gametest/test_environment");
        assertEquals(Files.readString(environments.resolve("baritone_navigation_game_tests_a_linkage_failure_inside_adriven_tick_retires_baritone_and_the_bot_continues_legacy.json")),
                Files.readString(environments.resolve(afterEnvironment + ".json")),
                "the afterPhysics linkage-failure GameTest must carry the same minimal test environment as beforePhysics");
    }

    @Test
    void everyHookThatRunsForEveryBotIsGatedOnActiveAndContainsFailures() throws IOException {
        String pack = read("action/ActionPack.java");
        assertTrue(pack.contains("NavEngineSelector.hook(\"baritone_preempt\", () -> BaritoneRegistry.INSTANCE.preempt(player, why))"));
        assertTrue(pack.contains("NavEngineSelector.query(\"baritone_busy\", () -> BaritoneRegistry.INSTANCE.isBusy(player), false)"));
        assertFalse(pack.contains("NavEngineSelector.baritoneLive()"), "ActionPack asks baritoneActive (through hook/query), never the bare live flag");
        String lifecycle = read("runtime/RuntimeLifecycleCoordinator.java");
        assertFalse(lifecycle.contains("NavEngineSelector.baritoneLive()"));
        assertEquals(4, count(lifecycle, "NavEngineSelector.hook(\"baritone_"), "reset x2, forget and clearAll");
        String selector = read("navigation/NavEngineSelector.java");
        assertTrue(selector.contains("return live && !FAILED.get();"), "active = live and not failed");
    }

    @Test
    void theRegistryRegistersItsTeardownWithTheSelectorWhenItGoesLive() throws IOException {
        String registry = read("baritone/BaritoneRegistry.java");
        assertTrue(registry.contains("NavEngineSelector.setUnavailableHook(BaritoneRegistry::abandonAll);"));
        String abandon = method(registry, "private void abandon() {");
        assertTrue(abandon.contains("bestEffort(") && !abandon.contains("halt("), "every teardown step is best effort");
        assertTrue(abandon.contains("BaritoneNavigator::releaseAllRoutes") && abandon.contains("BotInputBridge.release(bot)"));
    }

    @Test
    void settleRouteContainsBaritoneFailuresAndEndsTheRoute() throws IOException {
        String pack = read("action/ActionPack.java");
        String settle = method(pack, "private void settleRoute() {");
        int inactive = settle.indexOf("if (!NavEngineSelector.baritoneActive()) {");
        int progress = settle.indexOf("BaritoneNavigator.progress(player, current)");
        int catchAll = settle.indexOf("} catch (Throwable failure) {", progress);
        assertTrue(inactive >= 0 && progress > inactive && catchAll > progress, "no Baritone call after it was given up on, and any throwable is caught");
        assertTrue(settle.contains("NavEngineSelector.handleFailure(\"baritone_progress\", failure)"));
        assertTrue(settle.contains("NavigationMeasurement.noteBaritoneFallback(player)"),
                "a progress failure outside selector.attempt invalidates an active Baritone measurement too");
        assertTrue(settle.contains("finishRoute(NavOutcome.Status.FAILED,"), "a failed progress question ends the route FAILED");
        assertFalse(settle.replace("NavEngineSelector.hook(\"baritone_cancel\", () -> BaritoneNavigator.cancel(", "").contains("BaritoneNavigator.cancel("),
                "every cancel in settleRoute is contained");
        assertTrue(settle.contains("progress == NavRoute.Progress.POLICY_REFUSED"), "a vetoed route lets Baritone go before it is recorded");
        String finish = method(pack, "private void finishRoute(");
        assertTrue(finish.contains("NavEngineSelector.hook(\"baritone_release_route\""), "releasing the water bookkeeping is contained too");
    }

    // ---- 4/5: route lifecycle ---------------------------------------------------------------------------------------------

    @Test
    void theWaterLeaseIsGrantedOnlyToAnAdmittedRouteAndAStartThatFailsTakesItsStateBack() throws IOException {
        String navigator = read("baritone/BaritoneNavigator.java");
        String start = method(navigator, "public static Admission start(");
        int admission = start.indexOf("BaritonePlanner.planNow(");
        int lease = start.indexOf("NavSafetyNet.INSTANCE.renewBaritoneWater(bot)");
        int accepted = start.indexOf("accepted = true;");
        assertTrue(admission > 0 && lease > admission && accepted > lease, "the lease follows a successful admission");
        assertTrue(start.contains("} finally {\n            if (!accepted) {\n                abandonStart(bot, previousPolicy);"),
                "a refusal or an exception releases the water bookkeeping, the swim permission and the lease");
        String abandon = method(navigator, "private static void abandonStart(");
        assertTrue(abandon.contains("releaseRoute(bot.getUUID())") && abandon.contains("clearBaritoneWater(bot)")
                && abandon.contains("setWaterAllowed(bot, false)") && abandon.contains("setPolicy(bot, previousPolicy)"),
                "a refused start also puts back the previous break/place permission");
        assertTrue(start.contains("registry.isBusy(bot) ? registry.policy(bot) : BaritonePolicy.WALK_ONLY"),
                "the permission to restore is the running route's, or walk-only when nothing runs");
    }

    @Test
    void aRouteReplacedByANewerRequestIsRecordedAsCancelledReplaced() throws IOException {
        String pack = read("action/ActionPack.java");
        String start = method(pack, "private ActionResult startBaritoneRoute(");
        int settle = start.indexOf("settleRoute();");
        int previous = start.indexOf("NavRoute previous = route;");
        int admission = start.indexOf("BaritoneNavigator.start(player, request, admit)");
        int replaced = start.indexOf("finishRoute(NavOutcome.Status.CANCELLED, NavRouteRules.REPLACED, false);");
        int assign = start.indexOf("route = request;");
        assertTrue(settle > 0 && previous > settle && admission > previous && replaced > admission && assign > replaced,
                "an ended route is settled first; the replaced one is recorded before the new one is installed");
        assertTrue(start.contains("if (previous != null && admit) {"), "the deliberate follow re-goal refresh (no admission) is not an ending");
        assertTrue(start.contains("cancelBaritoneRoute(\"start_failed\")"), "an exception during start ends the previous route too");
    }

    // ---- 8: the mixins that inject into vanilla unconditionally ------------------------------------------------------------

    @Test
    void thePalettedContainerMixinHasNoStaticInitialiserThatCanCrashStartUp() throws IOException {
        String mixin = read("mixin/BaritonePalettedContainerMixin.java");
        assertFalse(mixin.contains("static {"), "no static initialiser in a mixin applied to a vanilla class at start-up");
        assertFalse(mixin.contains("throw new IllegalStateException"));
        assertTrue(mixin.contains("PaletteAccess.data("));
        String access = read("baritone/PaletteAccess.java");
        assertTrue(access.contains("private static final class Scan {") && access.contains("extends LinkageError"),
                "the scan is a lazily initialised holder and its failure is a linkage failure (Baritone unavailable)");
        assertTrue(read("baritone/BaritoneHost.java").contains("PaletteAccess.verify();"),
                "a palette layout Baritone cannot read is found when Baritone is configured, on the server thread");
    }

    @Test
    void theLootContextMixinWrapsTheCallInsteadOfRedirectingIt() throws IOException {
        String mixin = read("mixin/BaritoneLootContextBuilderMixin.java");
        assertFalse(mixin.contains("\n    @Redirect(") || mixin.contains("import org.spongepowered.asm.mixin.injection.Redirect;"),
                "an exclusive redirect would conflict with another mod's injection into the same call");
        assertTrue(mixin.contains("@WrapOperation(method = \"create\"") && mixin.contains("original.call(server)"),
                "a real server keeps its own call (through any other mod's wrapper)");
    }

    @Test
    void theItemStackMixinOnlyForgetsTheHashOnDamageAndComputesItOnRead() throws IOException {
        String mixin = read("mixin/BaritoneItemStackMixin.java");
        String inject = method(mixin, "private void minecraftai$onItemDamageSet(");
        assertTrue(inject.contains("minecraftai$baritoneHash = 0;") && !inject.contains("hashCode()") && !inject.contains("getDamageValue()"),
                "setDamageValue runs for every damaged stack: it costs one field write");
        String read = method(mixin, "public int getBaritoneHash() {");
        assertTrue(read.contains("item.hashCode() + getDamageValue()"), "the value of Baritone's hash contract is unchanged");
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }
}
