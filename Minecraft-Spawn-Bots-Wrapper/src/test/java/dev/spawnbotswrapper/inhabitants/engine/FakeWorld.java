package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.spawn.BlockProbe;
import dev.spawnbotswrapper.inhabitants.spawn.Cell;

import java.util.HashSet;
import java.util.Set;

/** World stand-in: every dimension is loaded and empty unless the test says otherwise. */
final class FakeWorld implements WorldGateway {

    static final BlockProbe OPEN = new BlockProbe() {
        @Override
        public Cell cell(int x, int y, int z) {
            return Cell.EMPTY;
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return 319;
        }
    };

    long seed = 987654321L;
    final Set<String> unloadedDimensions = new HashSet<>();
    boolean throwOnProbe;
    boolean throwOnSeed;
    int probeCalls;
    /** What standing(...) answers: UNKNOWN keeps the saved position (a world with no block data), UNSAFE forces a new place. */
    dev.spawnbotswrapper.inhabitants.spawn.SpawnSafety.Verdict standingVerdict = dev.spawnbotswrapper.inhabitants.spawn.SpawnSafety.Verdict.UNKNOWN;

    @Override
    public long worldSeed() {
        if (throwOnSeed) {
            throw new IllegalStateException("injected worldSeed failure");
        }
        return seed;
    }

    @Override
    public dev.spawnbotswrapper.inhabitants.spawn.SpawnSafety.Verdict standing(String dimensionId, double x, double y, double z) {
        return standingVerdict;
    }

    @Override
    public BlockProbe probe(String dimensionId) {
        probeCalls++;
        if (throwOnProbe) {
            throw new IllegalStateException("injected probe failure");
        }
        return unloadedDimensions.contains(dimensionId) ? null : OPEN;
    }
}
