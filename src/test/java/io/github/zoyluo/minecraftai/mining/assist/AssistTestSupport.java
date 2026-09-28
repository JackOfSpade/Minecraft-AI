package io.github.zoyluo.minecraftai.mining.assist;

import java.util.UUID;
import java.util.function.LongPredicate;

/** Shared fixtures for the assist adapter tests: facts without a registry, a fixed bot uuid. */
final class AssistTestSupport {
    static final UUID BOT = new UUID(0x1234_5678_9ABC_DEF0L, 0x0FED_CBA9_8765_4321L);
    static final String OVERWORLD = "minecraft:overworld";
    static final LongPredicate NOT_PLACED = packed -> false;

    private AssistTestSupport() {
    }

    /** Facts of a vanilla block id, derived through the same pure rules the adapter uses. */
    static BlockFacts facts(String path) {
        return facts("minecraft", path);
    }

    static BlockFacts facts(String namespace, String path) {
        return BlockFacts.derive(namespace, path, false, false, false);
    }

    static BlockFacts chest() {
        return BlockFacts.derive("minecraft", "chest", true, false, false);
    }
}
