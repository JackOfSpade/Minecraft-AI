package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.Gait;
import io.github.zoyluo.minecraftai.action.PaceOwner;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * A deterministic player-command retreat: move to a genuine footing behind the player, then
 * become an ordinary continuous follow. It deliberately contains no planning/model call.
 */
public final class RetreatFollowTask extends AbstractTask {
    private static final int SIDE_SEARCH_RADIUS = 3;
    private static final int BEHIND_SEARCH_DEPTH = 4;
    private static final int VERTICAL_SEARCH = 2;
    private static final double ARRIVAL_DISTANCE = 1.35D;

    private enum Phase {
        RETREAT,
        FOLLOW
    }

    private final UUID playerId;
    private final String playerName;
    private Phase phase = Phase.RETREAT;
    private BlockPos retreatDestination;
    private FollowTask follow;

    public RetreatFollowTask(UUID playerId, String playerName) {
        this.playerId = playerId;
        this.playerName = playerName == null ? "" : playerName;
    }

    @Override
    public String name() {
        return "retreat_follow";
    }

    @Override
    public String describe() {
        return phase == Phase.RETREAT
                ? "Retreating behind " + playerName
                : "Following " + playerName;
    }

    @Override
    public double progress() {
        return phase == Phase.RETREAT ? 0.25D : 0.5D;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        ServerPlayer player = player(bot).orElse(null);
        if (player == null || player.level() != bot.level()) {
            fail("retreat_player_unavailable");
            return;
        }
        retreatDestination = nearestStandableBehind((ServerLevel) bot.level(), player);
        if (retreatDestination == null) {
            fail("retreat_no_standable_block_behind_player");
            return;
        }
        if (!startRetreatRoute(bot, player)) {
            fail("retreat_route_unavailable");
        }
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        ServerPlayer player = player(bot).orElse(null);
        if (player == null || player.level() != bot.level()) {
            fail("retreat_player_unavailable");
            return;
        }
        if (phase == Phase.FOLLOW) {
            follow.tick(bot);
            return;
        }

        // The immediate retreat gets the strongest ordinary pace lease. Low-food and survival
        // policy can still lower it, rather than forcing an unsafe sprint.
        bot.getActionPack().requestPace(Gait.SPRINT, PaceOwner.TASK);
        bot.getActionPack().requestRoutePace(Gait.SPRINT, PaceOwner.TASK);
        if (atRetreatDestination(bot)) {
            bot.getActionPack().stopAll();
            phase = Phase.FOLLOW;
            follow = new FollowTask(playerName);
            follow.start(bot);
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && !bot.getActionPack().hasBaritoneRoute()
                && !startRetreatRoute(bot, player)) {
            fail("retreat_route_lost");
        }
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        if (follow != null && follow.state() == TaskState.RUNNING) {
            follow.pause(bot);
        }
        super.onPause(bot);
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        if (follow != null && follow.state() == TaskState.PAUSED) {
            follow.resume(bot);
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        if (follow != null && (follow.state() == TaskState.RUNNING || follow.state() == TaskState.PAUSED)) {
            follow.cancel(bot, "retreat_follow_ended");
        }
        super.onAbort(bot);
    }

    private Optional<ServerPlayer> player(AIPlayerEntity bot) {
        if (bot.level().getServer() == null || playerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(bot.level().getServer().getPlayerList().getPlayer(playerId));
    }

    private boolean startRetreatRoute(AIPlayerEntity bot, ServerPlayer player) {
        if (retreatDestination == null) {
            return false;
        }
        bot.getActionPack().requestPace(Gait.SPRINT, PaceOwner.TASK);
        bot.getActionPack().requestRoutePace(Gait.SPRINT, PaceOwner.TASK);
        ActionResult route = AIPlayerManager.INSTANCE.ownerOf(bot).filter(playerId::equals).isPresent()
                ? bot.getActionPack().startOwnerFollowTo(playerId, retreatDestination, 0, true)
                : bot.getActionPack().startApproachTo(retreatDestination, 0, true, false);
        return !route.isFailed();
    }

    private boolean atRetreatDestination(AIPlayerEntity bot) {
        return retreatDestination != null
                && bot.position().distanceTo(Vec3.atBottomCenterOf(retreatDestination)) <= ARRIVAL_DISTANCE;
    }

    /**
     * Picks the closest fresh, standable footing that is strictly behind the player's current
     * facing. The exact block behind them wins whenever possible; side and one-step height options
     * only exist to avoid a wall, hole, or occupied doorway.
     */
    static BlockPos nearestStandableBehind(ServerLevel world, ServerPlayer player) {
        if (world == null || player == null) {
            return null;
        }
        BlockPos origin = player.blockPosition();
        Direction behind = player.getDirection().getOpposite();
        BlockPos ideal = origin.relative(behind);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        double bestPlayerDistance = Double.MAX_VALUE;
        for (int dx = -SIDE_SEARCH_RADIUS; dx <= SIDE_SEARCH_RADIUS; dx++) {
            for (int dz = -SIDE_SEARCH_RADIUS; dz <= SIDE_SEARCH_RADIUS; dz++) {
                int depth = dx * behind.getStepX() + dz * behind.getStepZ();
                if (depth < 1 || depth > BEHIND_SEARCH_DEPTH) {
                    continue;
                }
                for (int dy = -VERTICAL_SEARCH; dy <= VERTICAL_SEARCH; dy++) {
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    if (!Standability.isStandableFresh(world, candidate)) {
                        continue;
                    }
                    double score = candidate.distSqr(ideal);
                    double playerDistance = candidate.distSqr(origin);
                    if (score < bestScore || (score == bestScore && playerDistance < bestPlayerDistance)) {
                        best = candidate.immutable();
                        bestScore = score;
                        bestPlayerDistance = playerDistance;
                    }
                }
            }
        }
        return best;
    }
}
