package io.github.zoyluo.minecraftai.brain;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Boots the vanilla registries (items, data components) in the plain JUnit VM, so tests of
 * registry-backed logic run against the real item registry instead of a stand-in. Idempotent.
 */
final class RegistryBootstrap {
    private RegistryBootstrap() {
    }

    static void ensure() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
}
