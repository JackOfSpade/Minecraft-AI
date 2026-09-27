package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.spawn.BlockProbe;
import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;

import java.util.ArrayList;
import java.util.List;

/** Scriptable geometry: normally finds a position per requested bot inside the structure. */
final class FakePlanner implements SpawnPlanner {

    enum Mode {
        /** Finds every requested position. */
        NORMAL,
        /** Finds none, and it is the structure's fault (nothing valid). */
        NONE,
        /** Finds none because chunks are not loaded. */
        UNLOADED,
        /** The structure has room for only {@code capacity} bots in total; asking for more is the structure's fault. */
        LIMITED,
        /** Like LIMITED, but the missing spots are only missing because chunks are not loaded. */
        LIMITED_UNLOADED
    }

    record FindCall(String structure, int count, List<Position> alreadyTaken, long firstDraw, boolean probeWasNull) {
    }

    record BehaviorCall(Position home, BotProfile.Behavior requested, long firstDraw) {
    }

    Mode mode = Mode.NORMAL;
    int capacity;
    boolean throwOnFind;
    boolean throwOnBehavior;
    /** When non-null, findPositions throws for structures whose key contains this text. */
    String throwForStructure;
    /** When set, planBehavior returns this instead of waypoints around home. */
    BotProfile.Behavior behaviorOverride;

    final List<FindCall> findCalls = new ArrayList<>();
    final List<BehaviorCall> behaviorCalls = new ArrayList<>();

    @Override
    public PositionResult findPositions(StructureSnapshot structure, BlockProbe probe, int count,
                                        List<Position> alreadyTaken, SplitMix64 rng) {
        // The first draw identifies which stream the engine handed over; our own choices are derived from it.
        long draw = rng.nextLong();
        findCalls.add(new FindCall(structure.key().asString(), count, List.copyOf(alreadyTaken), draw, probe == null));
        if (throwOnFind || (throwForStructure != null && structure.key().asString().contains(throwForStructure))) {
            throw new IllegalStateException("injected findPositions failure");
        }
        int give = switch (mode) {
            case NORMAL -> count;
            case NONE, UNLOADED -> 0;
            case LIMITED, LIMITED_UNLOADED -> Math.max(0, Math.min(count, capacity - alreadyTaken.size()));
        };
        boolean unloaded = mode == Mode.UNLOADED || mode == Mode.LIMITED_UNLOADED;
        List<Position> out = new ArrayList<>();
        SplitMix64 own = new SplitMix64(draw);
        IntBox box = structure.bounds();
        List<Position> used = new ArrayList<>(alreadyTaken);
        for (int i = 0; i < give; i++) {
            Position p;
            do {
                double x = box.minX() + own.nextInt(box.sizeX()) + 0.5;
                double z = box.minZ() + own.nextInt(box.sizeZ()) + 0.5;
                p = new Position(x, box.minY(), z, own.nextInt(360));
            } while (used.contains(p));
            used.add(p);
            out.add(p);
        }
        return new PositionResult(out, unloaded && out.size() < count, 10);
    }

    @Override
    public BotProfile.Behavior planBehavior(BlockProbe probe, Position home, BotProfile.Behavior requested,
                                            StructureSnapshot structure, SplitMix64 rng) {
        long draw = rng.nextLong();
        behaviorCalls.add(new BehaviorCall(home, requested, draw));
        if (throwOnBehavior) {
            throw new IllegalStateException("injected planBehavior failure");
        }
        if (behaviorOverride != null) {
            return behaviorOverride;
        }
        if (!requested.usesPath()) {
            return requested;
        }
        return requested.withWaypoints(List.of(new BotProfile.Waypoint(home.x(), home.y(), home.z())));
    }
}
