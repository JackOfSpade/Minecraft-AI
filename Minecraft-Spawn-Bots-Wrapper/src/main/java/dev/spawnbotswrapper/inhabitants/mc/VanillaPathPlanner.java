package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Pos;
import dev.spawnbotswrapper.inhabitants.combat.PathPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The standalone {@link PathPlanner}: vanilla pathfinding, through a DETACHED helper mob.
 * <p>
 * Vanilla's path finder (walk, jump up one block, drop, swim, pass doors, avoid lava, fire, cactus and the like) is a
 * mob's navigation. This planner creates one zombie per level with {@code EntityType.create}, which is NEVER added to the
 * level: it is not in any chunk, is never ticked, never spawns, is never seen or saved, and is not a target for anything.
 * To plan, the helper is put at the bot's position, marked as standing on the ground (vanilla only plans for a mob
 * that stands or swims), its follow range is set to cover the goal (bounded by the caller's limit), and
 * {@code getNavigation().createPath(goal, ...)} answers with the route. Vanilla bounds the search itself (follow range
 * times 16 visited nodes), so one call has a fixed cost; the caller limits how often it asks.
 * <p>
 * The route is only a list of cells: nothing here breaks or places a block or moves any entity. One helper is kept per
 * level and is dropped when the server stops ({@link #reset}); a failed plan is reported as
 * {@link PathPlanner.Outcome#UNAVAILABLE} (one line in the log the first time), never thrown.
 */
public final class VanillaPathPlanner implements PathPlanner {
    /** The smallest and largest follow range (blocks) a plan uses; the search area grows with the distance to the goal. */
    static final double MIN_RANGE = 16.0;

    private final Map<ServerLevel, Zombie> helpers = new HashMap<>();
    private boolean failedOnce;
    private long plans;
    private long reached;

    @Override
    public Plan plan(Object bot, Pos goal, double maxRange) {
        if (!(bot instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return Plan.none(Outcome.UNAVAILABLE);
        }
        try {
            Zombie helper = helper(level);
            if (helper == null) {
                return Plan.none(Outcome.UNAVAILABLE);
            }
            double distance = Math.sqrt(player.distanceToSqr(goal.x(), goal.y(), goal.z()));
            double range = Math.max(MIN_RANGE, Math.min(maxRange, distance * 1.5 + 8.0));
            AttributeInstance follow = helper.getAttribute(Attributes.FOLLOW_RANGE);
            if (follow != null) {
                follow.setBaseValue(range);
                // vanilla sizes the search (follow range x 16 nodes) once, when the navigation is built: do it for this range
                helper.getNavigation().updatePathfinderMaxVisitedNodes();
            }
            helper.setPos(player.getX(), player.getY(), player.getZ());
            helper.setOnGround(true);
            helper.getNavigation().stop();
            Path path = helper.getNavigation().createPath(BlockPos.containing(goal.x(), goal.y(), goal.z()), 0);
            helper.getNavigation().stop();
            plans++;
            if (path == null || path.getNodeCount() == 0) {
                return Plan.none(Outcome.NONE);
            }
            List<Pos> waypoints = new ArrayList<>(path.getNodeCount());
            for (int i = 0; i < path.getNodeCount(); i++) {
                Vec3 v = path.getEntityPosAtNode(helper, i);
                waypoints.add(new Pos(v.x, v.y, v.z));
            }
            if (path.canReach()) {
                reached++;
            }
            return new Plan(waypoints, path.canReach() ? Outcome.REACHES : Outcome.PARTIAL);
        } catch (RuntimeException | LinkageError e) {
            if (!failedOnce) {
                failedOnce = true;
                org.slf4j.LoggerFactory.getLogger("pvpbot_inhabitants").warn(
                        "aggro: route planning through the vanilla path finder failed ({}); inhabitants walk straight "
                                + "until it works", e.toString());
            }
            return Plan.none(Outcome.UNAVAILABLE);
        }
    }

    /** The detached helper of this level (created on first use); never added to the level. */
    private Zombie helper(ServerLevel level) {
        Zombie z = helpers.get(level);
        if (z == null) {
            z = EntityType.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
            if (z == null) {
                return null;
            }
            helpers.put(level, z);
        }
        return z;
    }

    /** Drops the helpers (the server is stopping). */
    public void reset() {
        helpers.clear();
        failedOnce = false;
    }

    /** Helpers alive right now, for tests. */
    public int helperCount() {
        return helpers.size();
    }

    /** Plans made, for diagnostics. */
    public long plans() {
        return plans;
    }

    /** Plans that reached their goal, for diagnostics. */
    public long reached() {
        return reached;
    }
}
