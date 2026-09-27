package dev.spawnbotswrapper.inhabitants.spawn;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.engine.SpawnPlanner;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Pure geometry logic over a {@link BlockProbe}: where bots may stand inside a structure, and which
 * patrol each of them can genuinely walk.
 * <p>
 * There is deliberately no pathfinding, hearing or line-of-sight logic here. PvP BOT stays responsible
 * for combat and navigation; this class only guarantees that what it hands over is safe to stand on and
 * that waypoints are reachable by PvP BOT's straight-line steering (see {@link StraightWalk}).
 * <p>
 * The spawning options are read once per call, so a config reload takes effect on the next call. The
 * planner keeps no state between calls; everything random comes from the generator passed in, so the same
 * generator state gives the same answer on every JDK.
 */
public final class DefaultSpawnPlanner implements SpawnPlanner {

    /** Tolerance for a stored feet Y that is a hair below its block after float round trips. */
    private static final double LEVEL_EPSILON = 1e-6;

    private final Supplier<InhabitantsConfig.Spawning> options;

    public DefaultSpawnPlanner(Supplier<InhabitantsConfig.Spawning> options) {
        this.options = options;
    }

    /**
     * Samples columns from the structure's pieces (see {@link ColumnSampler}) and, for each, collects EVERY
     * standing level inside the piece so multi-storey interiors are all reachable. The whole column is
     * skipped, and the fact remembered, as soon as one block it needs is unloaded: that is "try later",
     * not "no floor here". Levels must have the feet inside the piece box, so a bot is genuinely inside the
     * structure and never on a neighbouring roof or the terrain around it.
     * <p>
     * Work is bounded: at most {@code count * positionAttemptsPerBot} columns, each read once. Levels of a
     * column that were not picked are kept as spare and only used, without any further reading, when the
     * columns ran out before enough bots were placed, so a small tower still fits one bot per floor.
     */
    @Override
    public PositionResult findPositions(StructureSnapshot structure, BlockProbe probe, int count,
                                        List<Position> alreadyTaken, SplitMix64 rng) {
        if (count <= 0) {
            return new PositionResult(List.of(), false, 0);
        }
        InhabitantsConfig.Spawning opt = options.get();
        boolean allowSubmerged = opt.allowSubmerged;
        double separation = Double.isNaN(opt.minBotSeparation) ? 0.0 : Math.max(0.0, opt.minBotSeparation);
        long budget = (long) count * Math.max(1, opt.positionAttemptsPerBot);
        List<Position> taken = alreadyTaken == null ? List.of() : alreadyTaken;

        ProbeView view = new ProbeView(probe);
        ColumnSampler sampler = new ColumnSampler(structure.sampleBoxes());
        List<Position> found = new ArrayList<>();
        List<Spot> spare = new ArrayList<>();
        int tried = 0;
        boolean skippedUnloaded = false;

        while (found.size() < count && tried < budget && sampler.hasNext()) {
            ColumnSampler.Column column = sampler.next(rng);
            tried++;
            List<Integer> levels = new ArrayList<>();
            if (!collectLevels(view, column, allowSubmerged, levels)) {
                skippedUnloaded = true;
                continue;
            }
            levels.removeIf(level -> tooClose(column.x() + 0.5, level, column.z() + 0.5, separation, found, taken));
            if (levels.isEmpty()) {
                continue;
            }
            int y = levels.remove(rng.nextInt(levels.size()));
            found.add(new Position(column.x() + 0.5, y, column.z() + 0.5, yaw(rng)));
            for (int other : levels) {
                spare.add(new Spot(column.x(), other, column.z()));
            }
        }

        while (found.size() < count && !spare.isEmpty()) {
            Spot spot = spare.remove(rng.nextInt(spare.size()));
            if (!tooClose(spot.x() + 0.5, spot.y(), spot.z() + 0.5, separation, found, taken)) {
                found.add(new Position(spot.x() + 0.5, spot.y(), spot.z() + 0.5, yaw(rng)));
            }
        }
        return new PositionResult(found, found.size() < count && skippedUnloaded, tried);
    }

    /** A standing level of an already-read column that was not the one picked; no further probing needed. */
    private record Spot(int x, int y, int z) {
    }

    /**
     * Turns the abstract behaviour into a concrete, verified one. A home that cannot be verified as a
     * dry-or-allowed standing position degrades to STAND; a patrol that cannot be built (or would have a
     * single waypoint, which PvP BOT's ping-pong turns into a server-tick crash) degrades to GUARD_POST.
     * Every returned waypoint has been validated; none is ever guessed.
     */
    @Override
    public BotProfile.Behavior planBehavior(BlockProbe probe, Position home, BotProfile.Behavior requested,
                                            StructureSnapshot structure, SplitMix64 rng) {
        String stance = requested.stance();
        if (BotProfile.Stance.STAND.equals(stance)) {
            return requested;
        }
        boolean guard = BotProfile.Stance.GUARD_POST.equals(stance);
        boolean cycle = BotProfile.Stance.PATROL_CYCLE.equals(stance);
        if (!guard && !cycle && !BotProfile.Stance.PATROL_PINGPONG.equals(stance)) {
            return standing(requested);
        }

        if (!Double.isFinite(home.x()) || !Double.isFinite(home.y()) || !Double.isFinite(home.z())) {
            return standing(requested);
        }
        InhabitantsConfig.Spawning opt = options.get();
        ProbeView view = new ProbeView(probe);
        int bx = (int) Math.floor(home.x());
        int by = (int) Math.floor(home.y() + LEVEL_EPSILON);
        int bz = (int) Math.floor(home.z());
        if (Standing.at(view, bx, by, bz, opt.allowSubmerged) != Standing.Verdict.VALID) {
            return standing(requested);
        }
        BotProfile.Waypoint post = new BotProfile.Waypoint(home.x(), home.y(), home.z());
        boolean dryHome = Standing.at(view, bx, by, bz, false) == Standing.Verdict.VALID;
        if (guard || !dryHome) {
            return guardPost(requested, post);
        }

        double radius = Double.isNaN(requested.patrolRadius())
                ? 0.0 : Math.min(PatrolPlanner.MAX_RADIUS, Math.max(0.0, requested.patrolRadius()));
        int wanted = Math.max(PatrolPlanner.MIN_WAYPOINTS,
                Math.min(PatrolPlanner.MAX_WAYPOINTS, requested.waypointCount()));
        IntBox limit = PatrolPlanner.softLimit(structure.bounds());
        List<BotProfile.Waypoint> route = new PatrolPlanner(view).plan(home, by, cycle, radius, wanted, limit, rng);
        if (route.size() < PatrolPlanner.MIN_WAYPOINTS) {
            return guardPost(requested, post);
        }
        double reach = 0.0;
        for (BotProfile.Waypoint w : route) {
            reach = Math.max(reach, PatrolPlanner.horizontalDistance(w.x(), w.z(), home.x(), home.z()));
        }
        return new BotProfile.Behavior(stance, requested.combatant(), requested.walkType(), reach,
                route.size(), route);
    }

    /**
     * Collects the valid standing levels of a column inside its piece box.
     *
     * @return false when the column has to be skipped because a block it needs is unloaded
     */
    private static boolean collectLevels(ProbeView view, ColumnSampler.Column column, boolean allowSubmerged,
                                         List<Integer> levels) {
        IntBox box = column.box();
        int low = Math.max(box.minY(), view.minY() + 1);
        int high = Math.min(box.maxY(), view.maxY() - 2);
        for (int y = low; y <= high; y++) {
            switch (Standing.at(view, column.x(), y, column.z(), allowSubmerged)) {
                case VALID -> levels.add(y);
                case UNLOADED -> {
                    levels.clear();
                    return false;
                }
                case INVALID -> {
                }
            }
        }
        return true;
    }

    /** Too close when nearer than the separation, and never on top of an existing position even at 0. */
    private static boolean tooClose(double x, double y, double z, double separation,
                                    List<Position> found, List<Position> taken) {
        double limitSq = separation * separation;
        return nearAny(x, y, z, limitSq, found) || nearAny(x, y, z, limitSq, taken);
    }

    private static boolean nearAny(double x, double y, double z, double limitSq, List<Position> others) {
        for (Position p : others) {
            double dx = p.x() - x;
            double dy = p.y() - y;
            double dz = p.z() - z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < limitSq || distSq == 0.0) {
                return true;
            }
        }
        return false;
    }

    /** Uniform in [0, 360); the float cast can round the top of the range up to exactly 360. */
    private static float yaw(SplitMix64 rng) {
        float yaw = (float) (rng.nextDouble() * 360.0);
        return yaw >= 360.0f ? 0.0f : yaw;
    }

    private static BotProfile.Behavior standing(BotProfile.Behavior requested) {
        return new BotProfile.Behavior(BotProfile.Stance.STAND, requested.combatant(), requested.walkType(),
                0.0, 0, List.of());
    }

    private static BotProfile.Behavior guardPost(BotProfile.Behavior requested, BotProfile.Waypoint post) {
        return new BotProfile.Behavior(BotProfile.Stance.GUARD_POST, requested.combatant(), requested.walkType(),
                0.0, 1, List.of(post));
    }
}
