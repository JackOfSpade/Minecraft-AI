package dev.spawnbotswrapper.inhabitants.spawn;

/**
 * "Can a bot stand here?" for one exact position, on top of the same definition spawn-position search uses
 * ({@code Standing}): a solid floor below, free feet and head blocks, no hazard, inside the world border. Used to
 * decide whether a sleeping bot may wake up at the place it went to sleep at, or must be placed again.
 */
public final class SpawnSafety {
    private SpawnSafety() {
    }

    public enum Verdict {
        /** A bot can stand there. */
        SAFE,
        /** It cannot (blocks changed, a hazard, no floor). */
        UNSAFE,
        /** A needed block is not loaded, or nothing can be said. */
        UNKNOWN
    }

    /** @param x/y/z the FEET position (block centre in x/z) */
    public static Verdict check(BlockProbe probe, double x, double y, double z, boolean allowSubmerged) {
        if (probe == null) {
            return Verdict.UNKNOWN;
        }
        ProbeView view = new ProbeView(probe);
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y + 1.0e-3);
        int bz = (int) Math.floor(z);
        return switch (Standing.at(view, bx, by, bz, allowSubmerged)) {
            case VALID -> Verdict.SAFE;
            case INVALID -> Verdict.UNSAFE;
            case UNLOADED -> Verdict.UNKNOWN;
        };
    }
}
