package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Source-shape pins for the companion perception (docs/PERCEPTION.md) and for the four review fixes of the bow/crossbow job (cx):
 * (a) the LLM {@code attack_entity} tool works when the bot faces away, (b) a cover peek without a clear line is counted, (c) the
 * shooter side is weapon-neutral, (d) a mount on the crosshair ray is never struck through. The behaviour is proved live by
 * {@code CompanionPerceptionGameTests} and {@code RangedWeaponGameTests}.
 */
final class CompanionPerceptionSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    @Test
    void theAttackToolStartsABoundedAttackWhenOnlyTheTurnIsMissing() throws IOException {
        String tool = read("brain/ToolRegistry.java");
        int start = tool.indexOf("register(\"attack_entity\"");
        String body = tool.substring(start, tool.indexOf("/** Terminal/mission-control commands", start));
        int strike = body.indexOf("InteractAction.attackEntity(bot, target.get())");
        int notYet = body.indexOf("InteractAction.NOT_UNDER_CROSSHAIR.equals(first.reason())");
        int task = body.indexOf("new AttackEntityTask(target.get())");
        assertTrue(strike >= 0 && notYet > strike && task > notYet,
                "the first strike comes first, and only a missing turn (not_under_crosshair) starts the bounded attack");
        assertTrue(body.contains("attack_started"), "the tool reports the attack as STARTED, never as a landed hit");
        assertTrue(body.contains("return result(first);"), "every other outcome is reported as it is");

        String attack = read("task/AttackEntityTask.java");
        assertTrue(attack.contains("MAX_TICKS = 40") && attack.contains("StrikeLegality.strikeRefusal(bot, target)")
                        && attack.contains("InteractAction.attackEntity(bot, target)"),
                "bounded, and through the same strike legality gate as every other strike");
        assertTrue(attack.contains("fail(refusal)") && attack.contains("fail(InteractAction.NOT_UNDER_CROSSHAIR)"),
                "a refusal or running out of ticks ends it FAILED with the truth");
        assertTrue(attack.contains("InteractAction.mountInTheWay(bot, target)"), "a mount in the way is handled, not struck through");
    }

    @Test
    void aCoverPeekWithoutAnEnemyLineIsCountedAndGivenUp() throws IOException {
        String combat = read("task/CombatTask.java");
        int peek = combat.indexOf("private void coverPeek(AIPlayerEntity bot)");
        String body = combat.substring(peek, combat.indexOf("private boolean shouldBlock(AIPlayerEntity bot)"));
        int sight = body.indexOf("\"no_line_of_sight\".equals(refusal)");
        int clear = body.indexOf("refusal == null");
        assertTrue(sight >= 0 && clear > sight, "no_line_of_sight remains the sole blocked-shot refusal");
        assertTrue(body.contains("++sightBlockedPeeks > BLOCKED_PEEK_LIMIT")
                        && body.substring(sight, clear).contains("giveUpRangedForBlockedShot(bot, refusal)"),
                "after a sustained blocked enemy line, ranged is given up");
        assertTrue(body.substring(clear).contains("sightBlockedPeeks = 0"), "a clear shot resets the count");
        assertEquals(3, count(combat, "sightBlockedPeeks = 0;"),
                "the counter is reset by a clear shot, by the give-up path and by the task reset");
    }

    @Test
    void theShooterSideIsWeaponNeutral() throws IOException {
        String core = read("task/CombatCore.java");
        int up = core.indexOf("static boolean isRangedWeaponUp(LivingEntity shooter, int minDrawTicks)");
        String body = core.substring(up, core.indexOf("static boolean isLongRangeDanger"));
        assertTrue(body.contains("Items.BOW") && body.contains("Items.CROSSBOW") && body.contains("CrossbowItem.isCharged(stack)")
                        && body.contains("getMainHandItem()") && body.contains("getOffhandItem()"),
                "a bow drawn, a crossbow charging, or a loaded crossbow in either hand");
        String guard = read("task/ShieldGuard.java");
        int drawing = guard.indexOf("static boolean isDrawingBowAt(LivingEntity shooter, AIPlayerEntity bot)");
        String drawingBody = guard.substring(drawing, guard.length());
        assertTrue(drawingBody.contains("CombatCore.isRangedWeaponUp(shooter, SHOOTER_DRAW_TICKS)") && !drawingBody.contains("Items.BOW"),
                "the shield guard recognises the shooter through the neutral helper, not the bow item");
        assertTrue(read("task/CombatTask.java").contains("return ShieldGuard.isDrawingBowAt(shooter, bot);"),
                "CombatTask keeps its entry point, which is the guard's");
        String sense = read("task/AggroSense.java");
        int any = sense.indexOf("private static boolean isDrawingBowAtAny");
        String anyBody = sense.substring(any, sense.indexOf("DRAW_AIM_DOT", any));
        assertTrue(anyBody.contains("CombatCore.isRangedWeaponUp(shooter, DRAW_TICKS)") && !anyBody.contains("Items.BOW"),
                "AggroSense recognises the shooter through the neutral helper too");
    }

    @Test
    void aMountOnTheCrosshairIsSwitchedToOrWalkedAroundNeverStruckThrough() throws IOException {
        String interact = read("action/InteractAction.java");
        assertTrue(interact.contains("public static Entity mountInTheWay(AIPlayerEntity player, Entity target)")
                        && interact.contains("target.getVehicle()") && interact.contains("HumanAim.crosshairEntity(player)"),
                "the mount is what the bot's own crosshair holds");
        assertTrue(interact.contains("A MOUNT IN THE WAY") && interact.contains("never strikes THROUGH it"), "the behaviour is documented");
        int refusal = interact.indexOf("StrikeLegality.strikeRefusal(player, target)");
        int attack = interact.indexOf("player.attack(target)");
        assertTrue(refusal >= 0 && attack > refusal && interact.indexOf("HumanAim.isUnderCrosshair(player, target)") < attack,
                "a strike still lands only on what is under the crosshair");

        String combat = read("task/CombatTask.java");
        int handle = combat.indexOf("private void handleMountInTheWay(AIPlayerEntity bot)");
        String body = combat.substring(handle, combat.indexOf("private void block(AIPlayerEntity bot)"));
        assertTrue(body.contains("CombatCore.hostileTo(bot, living)") && body.contains("target = living;")
                        && body.contains("Phase.REPOSITION"),
                "a hostile mount becomes the target, any other mount is walked around (another angle)");
        assertFalse(body.contains("attackEntity"), "there is no strike through the mount");
    }

    @Test
    void theHarnessRunsTheWholeSuiteWithPerceptionOnAndOffIsADiagnosticEscape() throws IOException {
        String harness = Files.readString(Path.of(
                "src/gametest/java/io/github/zoyluo/minecraftai/gametest/MinecraftAiHarnessTestMod.java"));
        assertTrue(harness.contains("CreatureSenses.setHarnessDefaultOff(") && harness.contains("\"off\".equalsIgnoreCase(System.getenv(\"MINECRAFTAI_HARNESS_PERCEPTION\"))"),
                "the whole suite runs with realistic perception, as in production; only MINECRAFTAI_HARNESS_PERCEPTION=off turns it off");
        String tests = Files.readString(Path.of(
                "src/gametest/java/io/github/zoyluo/minecraftai/task/CompanionPerceptionGameTests.java"));
        assertTrue(tests.contains("CreatureSenses.forceEnabledForTests(true)") && tests.contains("CreatureSenses.forceEnabledForTests(false)"),
                "the perception tests opt in for their batch and restore the default");
        String senses = read("perception/CreatureSenses.java");
        assertTrue(senses.contains("config().enabledOn() && (!harnessOff || forcedOn)"),
                "production (no harness) follows behaviour.perception.enabled alone");
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }
}
