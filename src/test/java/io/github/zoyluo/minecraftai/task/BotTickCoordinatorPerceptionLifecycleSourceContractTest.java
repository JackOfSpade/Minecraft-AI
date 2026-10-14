package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The delayed companion-respawn path skips normal perception ticking, so it must detach the
 * listener itself. Otherwise a dead bot remains in AIPlayerManager's retained collection and
 * keeps hearing game events until it eventually revives.
 */
class BotTickCoordinatorPerceptionLifecycleSourceContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/BotTickCoordinator.java");

    @Test
    void deathOwnedTicksForgetTheSkippedCompanionsPerceptionState() throws IOException {
        String source = Files.readString(SOURCE);
        String deathGate = "if (DangerWatcher.INSTANCE.tickDeathRespawn(server, bot)) {";
        int gate = source.indexOf(deathGate);
        int continueAt = source.indexOf("continue;", gate);
        assertTrue(gate >= 0 && continueAt > gate, "the death-owned coordinator branch must remain before the normal tick");

        String branch = source.substring(gate, continueAt);
        assertTrue(branch.contains("CreatureSenses.INSTANCE.forget(bot.getUUID());"),
                "a death-owned tick must detach the listener before it skips normal perception ticking");
        assertTrue(source.indexOf("CreatureSenses.INSTANCE.tickBot(server, bot)") > continueAt,
                "a revived bot must register fresh perception state only from its next normal tick");
    }
}
