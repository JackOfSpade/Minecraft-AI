package io.github.zoyluo.minecraftai.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Source-contract pins for how Baritone's execution is wired into the bot: the order inside the bot's tick, the single-writer
 * hand-over with local physical actions, and the two rules that keep break/place routed through the mod's primitives. These are
 * the properties the end-to-end GameTests rely on and that a careless edit would break without failing a compile.
 */
class BaritoneExecutionContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    @Test
    void baritoneWritesTheInputsBeforeTheBotsPhysicsAndAimsAfterIt() throws IOException {
        String source = read("entity/AIPlayerEntity.java");
        int tick = source.indexOf("public void tick()");
        int before = source.indexOf("boolean baritoneDrives = baritoneBeforePhysics();", tick);
        int physics = source.indexOf("super.tick()", before);
        int doTick = source.indexOf("this.doTick()", physics);
        int after = source.indexOf("baritoneCompleted = baritoneAfterPhysics();", doTick);
        int localUpdate = source.indexOf("this.actionPack.onUpdate()", doTick);
        assertTrue(tick >= 0 && before > tick && physics > before && doTick > physics && after > doTick && localUpdate > doTick,
                "beforePhysics must precede the physics tick; afterPhysics and the local-action update follow it");
        String tickBody = method(source, "public void tick() {");
        assertTrue(tickBody.contains("if (baritoneDrives) {\n                boolean baritoneCompleted = false;\n                try {\n                    baritoneCompleted = baritoneAfterPhysics();")
                        && tickBody.contains("} finally {")
                        && tickBody.contains("NavigationMeasurement.noteDriver(this, true, !baritoneCompleted, owner);"),
                "the measured branch must retain whether Baritone actually completed its post-physics drive even when a local-action update throws");
        int localBranch = tickBody.indexOf("} else {");
        int beforeOwner = tickBody.indexOf("ownerBeforeUpdate = this.actionPack.controllerOwnerForMeasurement();", localBranch);
        int localActionUpdate = tickBody.indexOf("this.actionPack.onUpdate();", localBranch);
        int afterOwner = tickBody.indexOf("ownerAfterUpdate = this.actionPack.controllerOwnerForMeasurement();", localActionUpdate);
        assertTrue(localBranch >= 0 && beforeOwner > localBranch && localActionUpdate > beforeOwner && afterOwner > localActionUpdate
                        && tickBody.contains("try {\n                    this.actionPack.onUpdate();\n                } finally {"),
                "a non-driven tick must preserve the local-action scheduler branch and observe its owner on both sides of the update, even on failure");
    }

    @Test
    void theEntityHooksReachTheDriverOnlyWhileBaritoneIsActiveAndContainAnyThrowable() throws IOException {
        String source = read("entity/AIPlayerEntity.java");
        String before = source.substring(source.indexOf("private boolean baritoneBeforePhysics() {"));
        before = before.substring(0, before.indexOf("\n    }\n"));
        assertTrue(before.indexOf("NavEngineSelector.baritoneActive()") < before.indexOf("BaritoneDriver.beforePhysics(this)"),
                "the driver is asked only while Baritone is initialised and not given up on");
        assertTrue(before.contains("catch (Throwable failure)") && before.contains("NavigationMeasurement.noteBaritoneFallback(this)")
                        && before.contains("NavEngineSelector.handleFailure("),
                "a linkage-type failure that escapes the driver invalidates an active capture, retires Baritone, and lets the tick carry on");
        String after = method(source, "private boolean baritoneAfterPhysics() {");
        assertTrue(after.contains("catch (Throwable failure)") && after.contains("NavigationMeasurement.noteBaritoneFallback(this)")
                        && after.contains("this.actionPack.onUpdate()")
                        && after.contains("return false;") && after.contains("return true;"),
                "when the post-physics hook does not complete, the same tick runs only the local-action update and reports false");
    }

    @Test
    void driverPhasesFollowTheClientsTickOrder() throws IOException {
        String source = read("baritone/BaritoneDriver.java");
        int refresh = source.indexOf("refreshEntities()");
        int tick = source.indexOf("onTick(nextTick(EventState.PRE))", refresh);
        int apply = source.indexOf("BotInputBridge.apply(bot, baritone)", tick);
        assertTrue(refresh > 0 && tick > refresh && apply > tick, "entities, then the Baritone tick, then the inputs");
        int pre = source.indexOf("new PlayerUpdateEvent(EventState.PRE)");
        int look = source.indexOf("LookAction.setYawPitch(bot, bot.getYRot(), bot.getXRot())", pre);
        int fall = source.indexOf("doCheckFallDamage", look);
        int post = source.indexOf("new PlayerUpdateEvent(EventState.POST)", fall);
        assertTrue(pre > 0 && look > pre && fall > look && post > fall, "rotation applied, then the look bridge, the fall check, then POST");
    }

    @Test
    void everyNavigationEntryUsesTheBaritoneOnlySeam() throws IOException {
        String source = read("action/ActionPack.java");
        String walk = between(source, "public ActionResult startWalkTo(Vec3 target, double arrivalThreshold)", "// Unified entry point");
        String dig = between(source, "public ActionResult startDigPathTo(BlockPos goal, int protectedStoneLikeReserve)",
                "public ActionResult startPathTo(BlockPos goal)");
        String path = between(source, "private ActionResult startPathTo(BlockPos goal, boolean canPillar,",
                "public BlockPos activePathGoal()");
        assertTrue(walk.contains("NavEngineSelector.attempt(") && walk.contains("startBaritoneRoute("),
                "ordinary coordinate walks use the Baritone seam");
        assertTrue(dig.contains("routeOnBaritone(\"dig_path_to\"") && !dig.contains("new AStarPathfinder("),
                "dig approaches are Baritone-only and have no raw A* tunnel fallback");
        assertTrue(path.contains("routeOnBaritone(\"path_to\"") && !path.contains("pathExecutor"),
                "ordinary and contract routes have no executor fallback");
        int entry = source.indexOf("RouteConstraints routeConstraints) {");
        int fence = source.indexOf("if (controllerStartBlocked())", entry);
        int refusal = source.indexOf("return ActionResult.failed(GUARDED_STEP_FENCE);", fence);
        int routed = source.indexOf("routeOnBaritone(\"path_to\"", refusal);
        assertTrue(entry > 0 && fence > entry && refusal > fence && routed > refusal,
                "startPathTo refuses a guarded step before entering the sole navigation seam");
        assertFalse(source.contains("pathfinding.PathExecutor") || source.contains("PathExecutor.RouteContract")
                        || source.contains("pathExecutor"),
                "ActionPack must not retain retired executor wiring");
        // Claim and stopAll both preempt an already-driven Baritone route before an incompatible physical action.
        int release = source.indexOf("private void releaseBaritone(String why) {");
        assertTrue(release > 0 && source.indexOf("BaritoneRegistry.INSTANCE.preempt(player, why)", release) > release
                        && source.indexOf("cancelBaritoneRoute(why)", release) > release,
                "releaseBaritone must cancel the recorded route and preempt Baritone");
        assertTrue(source.contains("BaritoneRegistry.INSTANCE.isBusy(player)"), "hasActiveActions must count a busy Baritone");
    }

    @Test
    void baritoneBreaksAndPlacesOnlyThroughTheModsPrimitives() throws IOException {
        String controller = read("baritone/ServerPlayerController.java");
        assertTrue(controller.contains("MiningController.driven("), "breaking goes through MiningController");
        assertTrue(controller.contains("BuildAction.useItemOnHit("), "placing goes through BuildAction");
        assertFalse(controller.contains("gameMode.useItemOn"), "the controller must not call useItemOn itself");
        assertFalse(controller.contains(".destroyBlock(") || controller.contains(".setBlock("), "the controller must not edit blocks itself");
        String mining = read("action/MiningController.java");
        int driven = mining.indexOf("if (!driven) {\n            LookAction.lookAtBlock");
        assertTrue(driven > 0, "a driven MiningController must not re-aim the bot");
        assertTrue(mining.contains("if (driven) {") && mining.contains("ToolSelector.equipBestTool"),
                "a driven MiningController must not re-select the tool");
    }

    @Test
    void sneakScalingIsAppliedExactlyOnce() throws IOException {
        String bridge = read("baritone/BotInputBridge.java");
        // Paced path (PaceRules.inputScale carries the sneak 0.3 and the item-use 0.2 factors): one write each, one multiplication each.
        assertEquals(1, occurrences(bridge, "bot.zza = forward * scale;"), "forward is scaled once, in apply()");
        assertEquals(1, occurrences(bridge, "bot.xxa = left * scale;"), "left is scaled once, in apply()");
        // pace.enabled=false path: the sneak factor, once each.
        assertEquals(2, occurrences(bridge, "*= PaceRules.SNEAK_SCALE"), "forward and left are scaled once each in the unpaced path");
        assertEquals(0.3F, BotInputBridge.SNEAK_SCALE);
    }

    @Test
    void aFreshFollowCalculationMayCarryOnlyOnePriorExecutorDirection() throws IOException {
        String bridge = read("baritone/BotInputBridge.java");
        String driver = read("baritone/BaritoneDriver.java");
        String registry = read("baritone/BaritoneRegistry.java");

        assertTrue(driver.contains("BotInputBridge.releaseForFollowHandoff(bot);"),
                "only a natural driver release may consider arming the follow calculation handoff");
        assertTrue(bridge.contains("executor == null")
                        && bridge.contains("getInProgress().isPresent()")
                        && bridge.contains("takeFollowHandoff(bot, entry)"),
                "the old direction is available only while the replacement is calculating, never over an executor's own keys");
        assertTrue(bridge.contains("instanceof FollowTask") && bridge.contains("bot.getActionPack().hasBaritoneRoute()")
                        && bridge.contains("isLevelFollowHandoff(bot, entry, entry.observedRoute)")
                        && bridge.contains("entry.followHandoffFeetY == feetY")
                        && bridge.contains("isLevelDryFollowHandoffRoute(route, feetY, bot.isInWater())")
                        && bridge.contains("route.shape() == NavRoute.Shape.DIRECTIONAL_PURSUIT")
                        && bridge.contains("route.resolvedGoal()")
                        && bridge.contains("!isLandFollowRoute(route) || inWater")
                        && bridge.contains("bot.onGround()") && bridge.contains("bot.onClimbable()")
                        && bridge.contains("entry.lastBridgeRoute == sourceRoute")
                        && bridge.contains("FOLLOW_HANDOFF_TICKS = 1")
                        && bridge.contains("clearFollowHandoff(entry);"),
                "continuity is land-follow scoped, dry/level/supported at both ends, and consumed after one tick");
        String release = method(bridge, "static void releaseForFollowHandoff(AIPlayerEntity bot) {");
        assertTrue(release.contains("isNaturalFollowHandoffCompletion(BaritoneNavigator.progress(bot, sourceRoute))")
                        && bridge.contains("return progress == NavRoute.Progress.ARRIVED;"),
                "only a navigator-confirmed arrival may issue a receipt; failed or ended-short paths are neutralized");
        assertTrue(bridge.contains("PaceRules.inputScale(effectiveSneak, bot.isUsingItem())"),
                "the continued raw key still passes through the ordinary shield/use-item pace calculation");
        assertTrue(bridge.contains("ActionPack.reprojectControllerInputs(entry.followHandoffForward")
                        && bridge.contains("entry.followHandoffYaw, bot.getYRot())"),
                "a shield rotation must preserve the previous executor's world-space direction");
        assertTrue(bridge.contains("entry.followHandoffSprinting")
                        && bridge.contains("(sprintWanted(baritone) || handoffSprinting)")
                        && bridge.contains("entry.followHandoffSprinting = false;")
                        && registry.contains("boolean lastBridgeSprinting;")
                        && registry.contains("boolean followHandoffSprinting;"),
                "a level-safe handoff preserves the prior executor's sprint intent without bypassing current pace constraints");
        assertTrue(bridge.contains("clearFollowInputContinuity(entry);")
                        && registry.contains("BotInputBridge.clearFollowInputContinuity(entry);")
                        && registry.contains("float lastBridgeForward;")
                        && registry.contains("float followHandoffYaw;")
                        && registry.contains("NavRoute lastBridgeRoute;")
                        && registry.contains("int followHandoffFeetY = Integer.MIN_VALUE;")
                        && registry.contains("boolean followHandoffAvailable;"),
                "ordinary release/preemption clears retained input rather than letting it become a movement owner");
        int replacement = registry.indexOf("void cancelForReplacement(AIPlayerEntity bot, String why)");
        int retain = registry.indexOf("boolean retainNaturalFollowHandoff = entry.followHandoffAvailable", replacement);
        int replacementHalt = registry.indexOf("halt(entry, bot, retainNaturalFollowHandoff);", retain);
        int scopedClear = registry.indexOf("if (!retainNaturalFollowHandoff)", replacementHalt);
        assertTrue(replacement >= 0 && retain > replacement && replacementHalt > retain && scopedClear > replacementHalt
                        && registry.substring(retain, replacementHalt).contains("!entry.driven")
                        && registry.substring(retain, replacementHalt).contains("!BaritoneDriver.busy(entry.baritone)")
                        && registry.substring(retain, replacementHalt).contains("followHandoffExpiresAt"),
                "an accepted successor preserves only a live, naturally released follow receipt while cancelling every old route authority");
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, startMarker + " must exist");
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(end > start, endMarker + " must follow " + startMarker);
        return source.substring(start, end);
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature + " must exist");
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int at = open; at < source.length(); at++) {
            char current = source.charAt(at);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(start, at + 1);
            }
        }
        throw new AssertionError(signature + " must close");
    }

    @Test
    void policyPresetsMeanWhatTheirNamesSay() {
        assertTrue(BaritonePolicy.UNRESTRICTED.allowBreak() && BaritonePolicy.UNRESTRICTED.allowPlace());
        assertFalse(BaritonePolicy.WALK_ONLY.allowBreak() || BaritonePolicy.WALK_ONLY.allowPlace());
        assertTrue(BaritonePolicy.NO_PLACING.allowBreak() && !BaritonePolicy.NO_PLACING.allowPlace());
        assertTrue(!BaritonePolicy.NO_BREAKING.allowBreak() && BaritonePolicy.NO_BREAKING.allowPlace());
    }
}
