package io.github.zoyluo.minecraftai.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The wiring that keeps a bot from being stranded on its own tower by a server restart. Capture and restore need a real server, which
 * the GameTests {@code TowerRestoreGameTests} run end to end; these pin the choices a refactor must not undo: what is saved is the
 * tower the bot stands on (not whatever the custody still holds), a restore goes through the same custody and descent as every other
 * orphaned tower, and nothing about a tower travels with a death.
 */
class TowerPersistenceSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature + " must exist");
        int end = source.indexOf("\n    }\n", start);
        assertTrue(end > start, signature + " must end with a method-level closing brace");
        return source.substring(start, end);
    }

    @Test
    void theSaveKeepsTheTowerTheBotStandsOnAndNothingElse() throws IOException {
        String persistence = read("persist/BotPersistence.java");
        String capture = body(persistence, "public static BotRecord capture(AIPlayerEntity bot)");
        String custody = body(read("task/TowerCustody.java"), "public Optional<BlockPos> standingBase(AIPlayerEntity bot)");

        assertTrue(capture.contains("TowerCustody.INSTANCE.standingBase(bot).map(TowerBaseCodec::encode).orElse(null)"),
                "the record carries the custody's tower as text, or nothing");
        assertTrue(custody.contains("current.tower().standsOnTower(bot)"),
                "a bot that has left its tower (revived at the world spawn after dying) saves none: nothing under it is its to take down");
    }

    @Test
    void aRestoredBotHoldsItsTowerWithNoOwnerSoTheOrphanDescentTakesItDown() throws IOException {
        String custody = read("task/TowerCustody.java");
        String restore = body(custody, "public boolean restore(AIPlayerEntity bot, BlockPos base)");
        String ended = body(custody, "private static boolean ended(Task owner)");
        String orphans = body(custody, "Set<UUID> tickOrphans()");

        assertTrue(restore.contains("TowerDescent.from(base)") && restore.contains("tower.standsOnTower(bot)")
                        && restore.contains("new Held(null, tower)"),
                "the tower is the one descent every orphan gets, held only for a bot that stands on it, with no task answering for it");
        assertTrue(ended.contains("owner == null"), "a tower without an owner is an orphan from its first tick");
        assertTrue(orphans.contains("!ended(current.owner())") && orphans.contains("TaskManager.INSTANCE.isActiveSafety(bot)"),
                "the restored tower waits for a safety task like any other orphan, and nothing else");
    }

    @Test
    void everyRestoreOfARecordRestoresItsTowerOnce() throws IOException {
        String manager = read("manager/AIPlayerManager.java");
        String persistence = read("persist/BotPersistence.java");
        String restore = body(persistence, "public static void restoreTower(AIPlayerEntity bot, BotRecord record)");

        assertEquals(manager.indexOf("BotPersistence.restoreTower(bot, record)"),
                manager.lastIndexOf("BotPersistence.restoreTower("), "exactly one restore per respawn");
        assertTrue(manager.indexOf("BotPersistence.restoreTower(bot, record)") > manager.indexOf("BotPersistence.applyStoredPlayerState(bot, record)"),
                "the tower is restored for the bot as the record has put it back (position, items, health), after the snap to a safe cell");
        assertTrue(restore.contains("TowerBaseCodec.Status.ABSENT") && restore.contains("\"malformed\"")
                        && restore.contains("\"other_dimension\"") && restore.contains("\"not_on_tower\""),
                "an old record restores as it did; a damaged one, another dimension and a bot off its column each restore no tower and say why");
    }

    @Test
    void aDeathCarriesNoTowerIntoTheRespawn() throws IOException {
        String manager = read("manager/AIPlayerManager.java");
        String respawn = body(manager, "public boolean respawnDeadBot(AIPlayerEntity bot)");

        assertTrue(!respawn.contains("restoreTower") && !respawn.contains("TowerCustody.INSTANCE.restore")
                        && !respawn.contains("towerBase"),
                "a revived bot is at the spawn, not on its tower: nothing of the tower is applied to it");
    }
}
