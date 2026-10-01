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
 * hand-over with the legacy executor, and the two rules that keep break/place routed through the mod's primitives. These are
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
        int after = source.indexOf("baritoneAfterPhysics();", doTick);
        int legacy = source.indexOf("this.actionPack.onUpdate()", doTick);
        assertTrue(tick >= 0 && before > tick && physics > before && doTick > physics && after > doTick && legacy > doTick,
                "beforePhysics must precede the physics tick; afterPhysics and the legacy update follow it");
        String between = source.substring(doTick, legacy);
        assertTrue(between.contains("if (baritoneDrives)") && between.contains("else"),
                "the legacy executor's update must be the alternative to afterPhysics, never run in the same tick");
    }

    @Test
    void theEntityHooksReachTheDriverOnlyWhileBaritoneIsActiveAndContainAnyThrowable() throws IOException {
        String source = read("entity/AIPlayerEntity.java");
        String before = source.substring(source.indexOf("private boolean baritoneBeforePhysics() {"));
        before = before.substring(0, before.indexOf("\n    }\n"));
        assertTrue(before.indexOf("NavEngineSelector.baritoneActive()") < before.indexOf("BaritoneDriver.beforePhysics(this)"),
                "the driver is asked only while Baritone is initialised and not given up on");
        assertTrue(before.contains("catch (Throwable failure)") && before.contains("NavEngineSelector.handleFailure("),
                "a linkage-type failure that escapes the driver retires Baritone and the tick carries on");
        String after = source.substring(source.indexOf("private void baritoneAfterPhysics() {"));
        after = after.substring(0, after.indexOf("\n    }\n"));
        assertTrue(after.contains("catch (Throwable failure)") && after.contains("this.actionPack.onUpdate()"),
                "when the post-physics hook fails the legacy update still runs this tick");
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
    void everyLegacyEntryThatMakesTheBotActClaimsItFirst() throws IOException {
        String source = read("action/ActionPack.java");
        for (String entry : new String[]{
                "public ActionResult startWalkTo(Vec3 target, double arrivalThreshold) {\n        if (controllerStartBlocked()) {\n            return ActionResult.failed(GUARDED_STEP_FENCE);\n        }\n        claim(",
                "public ActionResult startDigPathTo(BlockPos goal, int protectedStoneLikeReserve) {\n        if (controllerStartBlocked()) {\n            return ActionResult.failed(GUARDED_STEP_FENCE);\n        }\n        claim(",
                "public ActionResult startMining(BlockPos pos, Direction face) {\n        if (controllerStartBlocked()) {\n            return ActionResult.failed(GUARDED_STEP_FENCE);\n        }\n        claim(",
                "public void stopAll() {\n        if (emergencyInputBlocked()) {\n            return;\n        }\n        releaseBaritone(\"stop_all\");"}) {
            assertTrue(source.contains(entry), "missing hand-over in ActionPack: " + entry.split("\n")[0]);
        }
        // The private path entry refuses a guarded fence before it can start either engine. It may hand an admitted request to
        // Baritone first (which is not the legacy executor and needs no hand-over), but legacy work after a null answer claims it.
        int entry = source.indexOf("PathExecutor.RouteContract routeContract) {");
        int fence = source.indexOf("if (controllerStartBlocked())", entry);
        int refusal = source.indexOf("return ActionResult.failed(GUARDED_STEP_FENCE);", fence);
        int routed = source.indexOf("routeOnBaritone(\"path_to\"", refusal);
        int claim = source.indexOf("claim(\"path_to\");", routed);
        int legacyWork = source.indexOf("int reserve = Math.max(0, protectedStoneLikeReserve);", claim);
        assertTrue(entry > 0 && fence > entry && refusal > fence && routed > refusal && claim > routed && legacyWork > claim,
                "startPathTo: the guarded refusal must precede the engine seam, and the legacy claim must precede legacy work");
        assertTrue(source.contains("if (routed != null) {\n            return routed;\n        }\n        claim(\"path_to\")"),
                "only a routed (non-null) answer may skip the claim");
        // The claim and stopAll go through releaseBaritone, which is what preempts Baritone (and ends a recorded route).
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

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    @Test
    void policyPresetsMeanWhatTheirNamesSay() {
        assertTrue(BaritonePolicy.UNRESTRICTED.allowBreak() && BaritonePolicy.UNRESTRICTED.allowPlace());
        assertFalse(BaritonePolicy.WALK_ONLY.allowBreak() || BaritonePolicy.WALK_ONLY.allowPlace());
        assertTrue(BaritonePolicy.NO_PLACING.allowBreak() && !BaritonePolicy.NO_PLACING.allowPlace());
        assertTrue(!BaritonePolicy.NO_BREAKING.allowBreak() && BaritonePolicy.NO_BREAKING.allowPlace());
    }
}
