package dev.spawnbotswrapper.inhabitants.mc;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

/** Runs every case of {@link VanillaRulesMcCases} inside the Minecraft sandbox; see {@link McSandbox}. */
class VanillaRulesMcTest {
    @TestFactory
    Stream<DynamicTest> cases() throws Exception {
        return McSandbox.cases("VanillaRulesMcCases");
    }
}
