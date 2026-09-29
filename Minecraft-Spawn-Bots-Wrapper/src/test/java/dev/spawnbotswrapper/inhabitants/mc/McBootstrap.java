package dev.spawnbotswrapper.inhabitants.mc;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;

/**
 * Runs inside {@link McSandbox}'s loader: brings the game's static registries up once, and offers the
 * built-in data-driven registries (enchantments, structures, ...) as a lookup, which is all the code under
 * test needs from a "world" registry manager.
 */
public final class McBootstrap {
    private static HolderLookup.Provider lookup;

    private McBootstrap() {
    }

    /** Idempotent. */
    public static synchronized void ensure() {
        if (lookup == null) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            lookup = VanillaRegistries.createLookup();
        }
    }

    /** The built-in registries: vanilla enchantments, structures and the rest of the data-driven content. */
    public static HolderLookup.Provider registries() {
        ensure();
        return lookup;
    }
}
