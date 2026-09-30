package io.github.zoyluo.minecraftai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Pins the follow-ups of the gear (G1-G4) and aggro-core (A1-A5) reviews to their source shape. The behaviour of the gear items is
 * proved live by {@code GearWorstFirstGameTests}; the rest is thread and allocation discipline that only source can show.
 */
final class ReviewNitsSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    private static String between(String source, String from, String to) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "missing: " + from);
        int end = source.indexOf(to, start);
        assertTrue(end > start, "missing: " + to);
        return source.substring(start, end);
    }

    // G1

    @Test
    void theBackgroundArmorPassNeverTakesOffAWornNonArmorItem() throws IOException {
        String body = between(read("action/EquipAction.java"), "public static int autoEquipArmor", "private static boolean isAutoWearable");
        assertTrue(body.contains("GearValue.hasBindingCurse(worn) || GearValue.armorPointsOf(worn, slot) <= 0.0D"),
                "a worn elytra, carved pumpkin or head (0 armor points) must be skipped like a Binding Curse piece");
        assertTrue(body.indexOf("armorPointsOf(worn, slot) <= 0.0D") < body.indexOf("int bestSlot"),
                "the worn-item guard must come before any swap");
    }

    // G2

    @Test
    void combatPassesItsTargetToTheWeaponAdequacyCheck() throws IOException {
        String core = read("task/CombatCore.java");
        assertTrue(core.contains("public static void ensureMeleeWeapon(AIPlayerEntity bot, LivingEntity target)")
                && core.contains("EquipAction.equipWeaponForContext(bot, target);"));
        String task = read("task/CombatTask.java");
        assertFalse(task.contains("ensureMeleeWeapon(bot);"), "CombatTask must give ensureMeleeWeapon its target");
        String equip = read("action/EquipAction.java");
        assertTrue(equip.contains("explicitTarget != null && explicitTarget.isAlive() && explicitTarget != bot\n                ? explicitTarget : contextTarget(bot)"),
                "an explicit live target beats the aggressor context");
    }

    // G3

    @Test
    void theExplicitBestFirstEntryPointsDocumentThatTheBackgroundPassUndoesThem() throws IOException {
        assertTrue(read("brain/ToolRegistry.java").contains("the automatic gear choice is worst-first"),
                "the equip_armor tool description must say it is a one-off");
        assertTrue(read("goal/GoalPlanner.java").contains("it is a one-off since gear is worst-first"));
        assertTrue(read("task/DescendToYTask.java").contains("This is an explicit best-first call and a"));
    }

    // G4

    @Test
    void theConfigReferenceIsVolatileBecauseBaritonesSearchThreadReadsIt() throws Exception {
        Field instance = MinecraftAiConfig.class.getDeclaredField("instance");
        assertTrue(Modifier.isVolatile(instance.getModifiers()), "MinecraftAiConfig.instance must be volatile");
        assertTrue(read("action/GearValue.java").contains("volatile reference"), "the GearValue javadoc must describe the shared read");
    }

    // A2

    @Test
    void marksAreNotRecordedWhenHostileBotsAreOffAndTheLedgerIsPrunedUnconditionally() throws IOException {
        String hurt = between(read("task/HostileBotLedger.java"), "private static void onProtectedVictimHurt", "markPlayer((ServerPlayer)");
        assertTrue(hurt.contains("!hostileBotsEnabled()"), "a disabled feature must not record marks");
        String tick = between(read("task/HostileBotIntent.java"), "public static void tick(MinecraftServer server)", "private static void sampleLevel");
        int prune = tick.indexOf("HostileBotLedger.prune(");
        assertTrue(prune >= 0 && prune < tick.indexOf("hostileBotsEnabled()"),
                "the ledger prune must run before the disabled early return");
        assertFalse(tick.substring(0, prune).contains("TRACKS.isEmpty()"), "the ledger prune must not depend on the intent tracks");
    }

    // A3

    @Test
    void theSiblingHurtLookupBuildsNoListAndSkipsSingleBotOwners() throws IOException {
        String core = between(read("task/CombatCore.java"), "public static boolean hasHurtBotOrOwner", "private static boolean recentlyHurtBy");
        assertFalse(core.contains("botsOf("), "hasHurtBotOrOwner runs per scanned entity per tick: no ArrayList");
        assertTrue(core.contains("anySiblingMatches(ownerId.get(), bot,"));
        String manager = between(read("manager/AIPlayerManager.java"), "public boolean anySiblingMatches", "public Optional<UUID> ownerOf");
        assertTrue(manager.contains("owned.size() < 2") && manager.contains("sibling != except"));
        assertFalse(manager.contains("new java.util.ArrayList"));
    }

    // A4

    @Test
    void aDespawnedBotsOwnerLookupIsDropped() throws IOException {
        String despawn = between(read("manager/AIPlayerManager.java"), "public boolean despawn(", "public Optional<AIPlayerEntity> getByName");
        assertTrue(despawn.contains("SharedVision.forget(entity.getUUID())"));
        assertTrue(read("task/SharedVision.java").contains("OWNER_CACHE.remove(botUuid)"));
    }

    // A5

    @Test
    void theVisibleAggressorRaycastRunsOncePerBotAggressorAndTick() throws IOException {
        String ledger = read("task/HostileBotLedger.java");
        String visible = between(ledger, "public static boolean isVisibleAggressor", "private record SeenKey");
        assertTrue(visible.contains("return seenThisTick(bot, player);") && !visible.contains("SharedVision.seenByBotOrOwner"));
        String cache = between(ledger, "private static boolean seenThisTick", "// ---");
        assertTrue(cache.contains("getGameTime()") && cache.contains("new SeenKey(bot.getUUID(), player.getUUID())")
                && cache.contains("SEEN_THIS_TICK.clear()"), "the cache is keyed by (bot, aggressor) and cleared when the game time moves");
        assertEquals(1, ledger.split("SharedVision.seenByBotOrOwner\\(bot, player\\)", -1).length - 1);
    }
}
