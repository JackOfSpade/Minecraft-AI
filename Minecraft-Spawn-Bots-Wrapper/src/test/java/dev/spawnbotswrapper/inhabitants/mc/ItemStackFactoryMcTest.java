package dev.spawnbotswrapper.inhabitants.mc;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

/** Runs every case of {@link ItemStackFactoryMcCases} inside the Minecraft sandbox; see {@link McSandbox}. */
class ItemStackFactoryMcTest {
    @TestFactory
    Stream<DynamicTest> cases() throws Exception {
        return McSandbox.cases("ItemStackFactoryMcCases");
    }
}
