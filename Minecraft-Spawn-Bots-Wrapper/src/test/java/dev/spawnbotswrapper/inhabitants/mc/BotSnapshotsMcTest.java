package dev.spawnbotswrapper.inhabitants.mc;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

/** Runs every case of {@link BotSnapshotsMcCases} inside the Minecraft sandbox; see {@link McSandbox}. */
class BotSnapshotsMcTest {
    @TestFactory
    Stream<DynamicTest> cases() throws Exception {
        return McSandbox.cases("BotSnapshotsMcCases");
    }
}
