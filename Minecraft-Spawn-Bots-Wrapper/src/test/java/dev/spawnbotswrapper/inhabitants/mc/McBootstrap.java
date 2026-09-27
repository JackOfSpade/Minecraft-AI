package dev.spawnbotswrapper.inhabitants.mc;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.RegistryWrapper;

/**
 * Runs inside {@link McSandbox}'s loader: brings the game's static registries up once, and offers the
 * built-in data-driven registries (enchantments, structures, ...) as a lookup, which is all the code under
 * test needs from a "world" registry manager.
 */
public final class McBootstrap {
    private static RegistryWrapper.WrapperLookup lookup;

    private McBootstrap() {
    }

    /** Idempotent. */
    public static synchronized void ensure() {
        if (lookup == null) {
            SharedConstants.createGameVersion();
            Bootstrap.initialize();
            lookup = BuiltinRegistries.createWrapperLookup();
        }
    }

    /** The built-in registries: vanilla enchantments, structures and the rest of the data-driven content. */
    public static RegistryWrapper.WrapperLookup registries() {
        ensure();
        return lookup;
    }
}
