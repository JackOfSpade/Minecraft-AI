package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.Gait;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.action.PaceOwner;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.navigation.NavOutcome;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * Physically points an owner to a known location without mining, interacting, or using any
 * hidden-world path. The owner leg may use the verified-owner route, because it is scoped to
 * chunks the server has already loaded. The target leg is otherwise ordinary observed navigation:
 * unseen targets get only short directional Baritone hops until the target is visible again.
 */
public final class ShowTargetTask extends AbstractTask {
    /** The requested player rendezvous distance. */
    static final int OWNER_RADIUS = 5;
    /** The requested final distance from the thing being shown. */
    static final int TARGET_RADIUS = 3;
    /** A directional route is a local, observed Baritone hop rather than a path through unseen terrain. */
    static final int HOP_DISTANCE = 12;
    /** A location from find is at most 64 blocks away; this still leaves room for a moving owner. */
    static final int MAX_TARGET_LEGS = 24;
    /** Bound the whole demonstration so a bad route cannot become a permanent task. */
    static final int MAX_SHOW_TICKS = 3_600;
    /** Exactly three distinct visual gestures, never an attack/mining input. */
    static final int SWING_COUNT = 3;
    /** Four game ticks makes each swing a discrete tap instead of holding an input. */
    static final int SWING_INTERVAL_TICKS = 4;
    private static final int RETRY_DELAY_TICKS = 5;

    private enum Phase {
        REACH_OWNER,
        REACH_TARGET,
        POINT
    }

    private final BlockPos target;
    private final String label;
    private Phase phase;
    private UUID ownerUuid;
    private NavOutcome handledOutcome;
    private int showStartedTick;
    private int nextRouteTick;
    private int targetLegs;
    private int swings;
    private int nextSwingTick;

    public ShowTargetTask(BlockPos target, String label) {
        if (target == null) {
            throw new IllegalArgumentException("missing_show_target");
        }
        this.target = target.immutable();
        String cleaned = label == null ? "" : label.trim();
        this.label = cleaned.isBlank() ? "the target" : cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    @Override
    public String name() {
        return "show_location";
    }

    @Override
    public String describe() {
        return "show target=" + target.toShortString() + " label=" + label
                + " phase=" + (phase == null ? "pending" : phase.name().toLowerCase())
                + " hops=" + targetLegs + "/" + MAX_TARGET_LEGS;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase == null ? Phase.REACH_OWNER : phase) {
            case REACH_OWNER -> 0.15D;
            case REACH_TARGET -> Math.min(0.85D, 0.35D + targetLegs / (double) MAX_TARGET_LEGS * 0.45D);
            case POINT -> Math.min(0.98D, 0.90D + swings / (double) SWING_COUNT * 0.08D);
        };
    }

    /** This task owns bounded navigation and its deliberate stationary pointing stage. */
    @Override
    public boolean isWaiting() {
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        ServerPlayer owner = resolveOwner(bot);
        if (owner == null) {
            cannotShow(bot, "owner_unavailable", "I can't show you that location because I can't find you in this dimension.");
            return;
        }
        ownerUuid = owner.getUUID();
        phase = Phase.REACH_OWNER;
        showStartedTick = bot.level().getServer().getTickCount();
        nextRouteTick = 0;
        targetLegs = 0;
        swings = 0;
        nextSwingTick = 0;
        handledOutcome = null;
        BrainCoordinator.INSTANCE.sendBotReply(bot, "I'll show you where " + label + " is.");
        BotLog.action(bot, "show_location_started", "target", target.toShortString(), "label", label,
                "owner", owner.getGameProfile().name());
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        int now = bot.level().getServer().getTickCount();
        if (now - showStartedTick >= MAX_SHOW_TICKS) {
            cannotShow(bot, "time_limit", "I couldn't reach that location in time to show it to you.");
            return;
        }
        switch (phase) {
            case REACH_OWNER -> reachOwner(bot, now);
            case REACH_TARGET -> reachTarget(bot, now);
            case POINT -> pointAtTarget(bot, now);
        }
    }

    /** First leg: a verified owner's live coordinate can be used even if the bot cannot see them. */
    private void reachOwner(AIPlayerEntity bot, int now) {
        ServerPlayer owner = resolveOwner(bot);
        if (owner == null) {
            cannotShow(bot, "owner_unavailable", "I can't show you that location because I can't find you in this dimension.");
            return;
        }
        if (bot.distanceTo(owner) <= OWNER_RADIUS) {
            // Stop the owner route before issuing the final-target Baritone request in this same tick.
            bot.getActionPack().stopNavigation();
            phase = Phase.REACH_TARGET;
            nextRouteTick = now;
            BotLog.action(bot, "show_location_owner_reached", "target", target.toShortString());
            reachTarget(bot, now);
            return;
        }
        ActionPack pack = bot.getActionPack();
        if (pack.hasBaritoneRoute()) {
            return;
        }
        noteEndedRoute(bot);
        if (now < nextRouteTick) {
            return;
        }
        ActionResult started = pack.startOwnerFollowTo(owner.getUUID(), owner.blockPosition(), OWNER_RADIUS, false);
        if (started.isFailed()) {
            nextRouteTick = now + RETRY_DELAY_TICKS;
            BotLog.action(bot, "show_location_owner_route_refused", "reason", started.reason());
            return;
        }
        requestSprintRoute(bot);
        nextRouteTick = now + RETRY_DELAY_TICKS;
        BotLog.path(bot, "show_location_owner_route", "target",
                io.github.zoyluo.minecraftai.log.LogFields.pos(owner.blockPosition()), "radius", OWNER_RADIUS,
                "policy", "owner_loaded_chunks_only");
    }

    /** Second leg: only give Baritone the exact target after a live proof; otherwise hop locally toward it. */
    private void reachTarget(AIPlayerEntity bot, int now) {
        ActionPack pack = bot.getActionPack();
        boolean targetVisible = ObservableWorldQuery.canObserveCell(bot, target);
        if (withinTargetRadius(bot)) {
            if (!targetVisible) {
                cannotShow(bot, "target_not_visible", "I reached the last known spot, but I can't see it clearly enough to point it out.");
                return;
            }
            pack.stopNavigation();
            phase = Phase.POINT;
            nextSwingTick = now;
            BotLog.action(bot, "show_location_target_reached", "target", target.toShortString(), "hops", targetLegs);
            pointAtTarget(bot, now);
            return;
        }
        if (pack.hasBaritoneRoute()) {
            return;
        }
        noteEndedRoute(bot);
        if (now < nextRouteTick) {
            return;
        }
        if (targetLegs >= MAX_TARGET_LEGS) {
            cannotShow(bot, "target_route_limit", "I couldn't safely reach that location to point it out.");
            return;
        }
        ActionResult started = startTargetRoute(bot, targetVisible);
        nextRouteTick = now + RETRY_DELAY_TICKS;
        if (started.isFailed()) {
            targetLegs++;
            BotLog.action(bot, "show_location_target_route_refused", "reason", started.reason(),
                    "target", target.toShortString(), "visible", targetVisible, "leg", targetLegs);
        }
    }

    /**
     * A visible target gets the requested GoalNear(3). A target that vanished from view after
     * the owner rendezvous receives only a short observed directional hop; the next tick can
     * re-prove it and use GoalNear(3).
     */
    private ActionResult startTargetRoute(AIPlayerEntity bot, boolean targetVisible) {
        ActionPack pack = bot.getActionPack();
        if (targetVisible) {
            ActionResult direct = pack.startApproachTo(target, TARGET_RADIUS, false, false);
            if (!direct.isFailed()) {
                targetLegs++;
                requestSprintRoute(bot);
                BotLog.path(bot, "show_location_target_route", "target",
                        io.github.zoyluo.minecraftai.log.LogFields.pos(target), "radius", TARGET_RADIUS,
                        "policy", "observed_no_break");
                return direct;
            }
            if (!isObservationAdmissionFailure(direct.reason())) {
                return direct;
            }
        }
        ActionResult pursuit = pack.startDirectionalPursuitTo(target, HOP_DISTANCE, false, false);
        if (!pursuit.isFailed()) {
            targetLegs++;
            requestSprintRoute(bot);
            BotLog.path(bot, "show_location_target_hop", "target",
                    io.github.zoyluo.minecraftai.log.LogFields.pos(target), "hop", HOP_DISTANCE,
                    "policy", "observed_no_break");
        }
        return pursuit;
    }

    /** Stop moving, face the actual visible location, then make exactly three harmless animation-only taps. */
    private void pointAtTarget(AIPlayerEntity bot, int now) {
        if (!ObservableWorldQuery.canObserveCell(bot, target)) {
            cannotShow(bot, "target_lost_before_point", "I lost sight of that spot before I could point it out.");
            return;
        }
        if (now < nextSwingTick || bot.isUsingItem()) {
            return;
        }
        LookAction.lookAt(bot, Vec3.atCenterOf(target));
        // swing() is a visual arm animation only. It does not call attack(), mine, use an item,
        // or hold an input, so this cannot break or interact with the target block.
        bot.swing(InteractionHand.MAIN_HAND);
        bot.resetLastActionTime();
        swings++;
        nextSwingTick = now + SWING_INTERVAL_TICKS;
        if (swings >= SWING_COUNT) {
            BotLog.action(bot, "show_location_pointed", "target", target.toShortString(), "swings", SWING_COUNT);
            BrainCoordinator.INSTANCE.sendBotReply(bot, "There it is.");
            complete();
        }
    }

    /** Consume each completed route outcome once for useful log diagnosis, without mistaking an old outcome for a new one. */
    private void noteEndedRoute(AIPlayerEntity bot) {
        NavOutcome outcome = bot.getActionPack().lastRouteOutcome();
        if (outcome == null || outcome == handledOutcome) {
            return;
        }
        handledOutcome = outcome;
        if (!outcome.success()) {
            BotLog.action(bot, "show_location_route_ended", "phase", phase.name().toLowerCase(),
                    "status", outcome.status(), "reason", outcome.reason(), "label", outcome.label());
        }
    }

    private boolean withinTargetRadius(AIPlayerEntity bot) {
        return bot.position().distanceToSqr(Vec3.atCenterOf(target)) <= (double) TARGET_RADIUS * TARGET_RADIUS;
    }

    private ServerPlayer resolveOwner(AIPlayerEntity bot) {
        UUID expected = ownerUuid;
        if (expected == null) {
            expected = AIPlayerManager.INSTANCE.ownerOf(bot).orElse(null);
        }
        UUID ownerId = expected;
        if (ownerId == null || AIPlayerManager.INSTANCE.ownerOf(bot).filter(ownerId::equals).isEmpty()) {
            return null;
        }
        ServerPlayer owner = bot.level().getServer().getPlayerList().getPlayer(ownerId);
        return owner != null && owner.level() == bot.level() ? owner : null;
    }

    /** Route leases give Baritone permission to sprint while preserving its collision/hunger safety checks. */
    private static void requestSprintRoute(AIPlayerEntity bot) {
        if (MinecraftAiConfig.get().behaviour().paceOrDefaults().paceEnabled()) {
            bot.getActionPack().requestRoutePace(Gait.SPRINT, PaceOwner.TASK);
        } else {
            // This mirrors the legacy no-pace-policy behaviour. The Baritone input bridge still
            // decides whether sprinting is safe on the current movement tick.
            bot.getActionPack().setSprinting(true);
        }
    }

    private static boolean isObservationAdmissionFailure(String reason) {
        if (reason == null) {
            return false;
        }
        return switch (reason) {
            case "navigation_goal_unobserved", "navigation_observed_corridor_unavailable",
                    "navigation_goal_without_observed_stance", "navigation_observation_fence_insufficient" -> true;
            default -> false;
        };
    }

    private void cannotShow(AIPlayerEntity bot, String reason, String message) {
        bot.getActionPack().stopNavigation();
        BotLog.action(bot, "show_location_failed", "target", target.toShortString(), "reason", reason);
        BrainCoordinator.INSTANCE.sendBotReply(bot, message);
        fail("show_location:" + reason);
    }
}
