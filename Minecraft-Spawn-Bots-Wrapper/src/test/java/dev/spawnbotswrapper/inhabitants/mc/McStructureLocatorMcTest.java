package dev.spawnbotswrapper.inhabitants.mc;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

/** Runs every case of {@link McStructureLocatorMcCases} inside the Minecraft sandbox; see {@link McSandbox}. */
class McStructureLocatorMcTest {
    @TestFactory
    Stream<DynamicTest> cases() throws Exception {
        return McSandbox.cases("McStructureLocatorMcCases");
    }
}
