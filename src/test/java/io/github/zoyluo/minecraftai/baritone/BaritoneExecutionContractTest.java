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
        int before = source.indexOf("BaritoneDriver.beforePhysics(this)", tick);
        int physics = source.indexOf("super.tick()", before);
        int doTick = source.indexOf("this.doTick()", physics);
        int after = source.indexOf("BaritoneDriver.afterPhysics(this)", doTick);
        int legacy = source.indexOf("this.actionPack.onUpdate()", doTick);
        assertTrue(tick >= 0 && before > tick && physics > before && doTick > physics && after > doTick && legacy > doTick,
                "beforePhysics must precede the physics tick; afterPhysics and the legacy update follow it");
        String between = source.substring(doTick, legacy);
        assertTrue(between.contains("if (baritoneDrives)") && between.contains("else"),
                "the legacy executor's update must be the alternative to afterPhysics, never run in the same tick");
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
                "PathExecutor.RouteContract routeContract) {\n        claim(",
                "public ActionResult startMining(BlockPos pos, Direction face) {\n        claim(",
                "public void stopAll() {\n        BaritoneRegistry.INSTANCE.preempt("}) {
            assertTrue(source.contains(entry), "missing hand-over in ActionPack: " + entry.split("\n")[0]);
        }
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
        Matcher scale = Pattern.compile("\\*= SNEAK_SCALE").matcher(bridge);
        int count = 0;
        while (scale.find()) {
            count++;
        }
        assertEquals(2, count, "forward and left are scaled once each, in apply()");
        assertEquals(0.3F, BotInputBridge.SNEAK_SCALE);
    }

    @Test
    void policyPresetsMeanWhatTheirNamesSay() {
        assertTrue(BaritonePolicy.UNRESTRICTED.allowBreak() && BaritonePolicy.UNRESTRICTED.allowPlace());
        assertFalse(BaritonePolicy.WALK_ONLY.allowBreak() || BaritonePolicy.WALK_ONLY.allowPlace());
        assertTrue(BaritonePolicy.NO_PLACING.allowBreak() && !BaritonePolicy.NO_PLACING.allowPlace());
        assertTrue(!BaritonePolicy.NO_BREAKING.allowBreak() && BaritonePolicy.NO_BREAKING.allowPlace());
    }
}
