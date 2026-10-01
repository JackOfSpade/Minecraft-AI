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
        int after = source.indexOf("baritoneCompleted = baritoneAfterPhysics();", doTick);
        int legacy = source.indexOf("this.actionPack.onUpdate()", doTick);
        assertTrue(tick >= 0 && before > tick && physics > before && doTick > physics && after > doTick && legacy > doTick,
                "beforePhysics must precede the physics tick; afterPhysics and the legacy update follow it");
        String tickBody = method(source, "public void tick() {");
        assertTrue(tickBody.contains("if (baritoneDrives) {\n                boolean baritoneCompleted = false;\n                try {\n                    baritoneCompleted = baritoneAfterPhysics();")
                        && tickBody.contains("} finally {")
                        && tickBody.contains("NavigationMeasurement.noteDriver(this, true, !baritoneCompleted, owner);"),
                "the measured branch must retain whether Baritone actually completed its post-physics drive even when its legacy fallback throws");
        int legacyBranch = tickBody.indexOf("} else {");
        int beforeOwner = tickBody.indexOf("ownerBeforeUpdate = this.actionPack.navigationOwnerForMeasurement();", legacyBranch);
        int legacyUpdate = tickBody.indexOf("this.actionPack.onUpdate();", legacyBranch);
        int afterOwner = tickBody.indexOf("ownerAfterUpdate = this.actionPack.navigationOwnerForMeasurement();", legacyUpdate);
        assertTrue(legacyBranch >= 0 && beforeOwner > legacyBranch && legacyUpdate > beforeOwner && afterOwner > legacyUpdate
                        && tickBody.contains("try {\n                    this.actionPack.onUpdate();\n                } finally {"),
                "a non-driven tick must preserve the legacy scheduler branch and observe its owner on both sides of the update, even on failure");
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
                "when the post-physics hook does not complete, the same tick falls back to legacy and reports false");
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
                "public ActionResult startWalkTo(Vec3 target, double arrivalThreshold) {\n        claim(",
                "public ActionResult startDigPathTo(BlockPos goal, int protectedStoneLikeReserve) {\n        claim(",
                "public ActionResult startMining(BlockPos pos, Direction face) {\n        claim(",
                "public void stopAll() {\n        releaseBaritone(\"stop_all\");"}) {
            assertTrue(source.contains(entry), "missing hand-over in ActionPack: " + entry.split("\n")[0]);
        }
        // The private path entry may hand the request to Baritone first (which is not the legacy executor and needs no
        // hand-over), but everything the legacy executor does after a null answer starts with the claim.
        int entry = source.indexOf("PathExecutor.RouteContract routeContract) {\n        // Engine seam");
        int routed = source.indexOf("routeOnBaritone(\"path_to\"", entry);
        int claim = source.indexOf("claim(\"path_to\");", routed);
        int legacyWork = source.indexOf("int reserve = Math.max(0, protectedStoneLikeReserve);", claim);
        assertTrue(entry > 0 && routed > entry && claim > routed && legacyWork > claim,
                "startPathTo: the engine seam first, then the claim, then any legacy work");
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
