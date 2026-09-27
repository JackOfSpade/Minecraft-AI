package dev.spawnbotswrapper.inhabitants.spawn;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

final class Fixtures {

    private Fixtures() {
    }

    static StructureSnapshot snapshot(IntBox bounds, IntBox... pieces) {
        return new StructureSnapshot(new StructureKey("minecraft:overworld", "test:structure", 0, 0),
                Set.of(), bounds, List.of(pieces), true);
    }

    /** Bounds and a single piece that are the same box. */
    static StructureSnapshot single(IntBox box) {
        return snapshot(box, box);
    }

    static IntBox box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return new IntBox(x0, y0, z0, x1, y1, z1);
    }

    static DefaultSpawnPlanner planner(Consumer<InhabitantsConfig.Spawning> tweak) {
        InhabitantsConfig.Spawning s = new InhabitantsConfig.Spawning();
        tweak.accept(s);
        return new DefaultSpawnPlanner(() -> s);
    }

    static DefaultSpawnPlanner planner() {
        return planner(s -> {
        });
    }
}
