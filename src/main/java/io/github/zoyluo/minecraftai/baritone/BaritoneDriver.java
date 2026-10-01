package io.github.zoyluo.minecraftai.baritone;

import baritone.api.IBaritone;
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.task.NavSafetyNet;
import java.util.function.BiFunction;

/**
 * The tick driver: runs Baritone for a bot inside the bot's own tick, in the order the client runs it, and bridges the two
 * things Baritone's client hooks did (the movement inputs and the look direction) to the bot.
 *
 * <p>Order of one driven bot tick ({@code AIPlayerEntity.tick}), and where each step sits in the client's tick:</p>
 * <pre>
 *   beforePhysics
 *     1. refresh the observable entities       (server thread copy for the worker threads; no client counterpart)
 *     2. TickEvent PRE/IN                       Minecraft.tick, before the screen: PathingBehavior asks the processes for a command,
 *                                               PathExecutor runs the movement and sets the input keys and the look target,
 *                                               InputOverrideHandler clicks (BlockBreakHelper/BlockPlaceHelper), LookBehavior
 *                                               advances its aim processor
 *     3. input bridge                           PlayerMovementInput.tick + LocalPlayer.aiStep: zza/xxa/jump/sneak/sprint
 *   ServerPlayer.tick(), doTick()               the physics tick, with the yaw the previous tick left
 *   afterPhysics
 *     4. PlayerUpdateEvent PRE                  LocalPlayer.tick after the physics: LookBehavior applies its target rotation
 *     5. look bridge                            the new rotation becomes the bot's head and body rotation too (LookAction)
 *     6. fall check                             what the client's move packet does on the server: fall distance and damage
 *     7. PlayerUpdateEvent POST, TickEvent POST end of Minecraft.tick
 * </pre>
 * <p>The rotation is applied after the physics, as on the client, so the tick's movement uses the yaw of the previous tick
 * and Baritone's own timing assumptions (it computes a tick's inputs from the rotation it left last tick, and only clicks a
 * block once the rotation it applied already points at it) hold unchanged.</p>
 *
 * <p>Single writer: a bot is <em>driven</em> from the first tick a process wants control (or a path is searched or run)
 * until the tick none does. While driven, the legacy {@code ActionPack} executes nothing and writes no inputs; when it is asked to
 * start something ({@code walkTo}, a path, mining, an input) it calls {@link BaritoneRegistry#preempt} first, which stops
 * Baritone and lets go of the inputs, so control changes hands with one tick of neutral input at most and never has two
 * writers. A bot that is not busy costs the driver one map lookup and a few field reads per tick.</p>
 */
public final class BaritoneDriver {
    private BaritoneDriver() {
    }

    /**
     * Test seam (GameTests only): a fault raised from inside a driven tick, exactly where a Baritone call would raise it, to prove
     * the containment of {@link #beforePhysics}/{@link #afterPhysics} (a {@link NoClassDefFoundError} retires Baritone for the session
     * and the bot carries on with the legacy executor in the same tick). Receives the phase name ({@code before_physics},
     * {@code after_physics}). Always null in production.
     */
    static volatile java.util.function.Consumer<String> testFault;

    private static void injectTestFault(String phase) {
        java.util.function.Consumer<String> fault = testFault;
        if (fault != null) {
            fault.accept(phase);
        }
    }

    /**
     * Runs steps 1-3 for the bot and reports whether Baritone owns its movement this tick. When it does the caller must skip
     * every legacy input write for the tick. Server thread only. Never throws: a failing Baritone tick (any Throwable but a VM error) cancels Baritone for the
     * bot (and is logged) instead of taking the bot's tick, and the server's, down with it.
     */
    public static boolean beforePhysics(AIPlayerEntity bot) {
        BaritoneRegistry.Entry entry = BaritoneRegistry.INSTANCE.entry(bot.getUUID());
        if (entry == null) {
            return false;
        }
        IBaritone baritone = entry.baritone;
        if (!entry.driven && !busy(baritone) && entry.placedWater == null) {
            return false;
        }
        if (!bot.isAlive() || bot.isRemoved()) {
            return false;
        }
        boolean wasDriven = entry.driven;
        try {
            entry.bot = bot;
            // ActionPack.yieldToBaritone() is cleanup, not admission: it deliberately refuses to
            // disturb an unreconciled guarded step. The driver is the final boundary before a
            // Baritone PRE tick or BotInputBridge can move the bot, so it must cleanly hand any
            // direct/existing Baritone process back before the guarded step gets this tick.
            if (guardedStepBlocksBaritone(bot, entry, baritone)) {
                return false;
            }
            if (entry.placedWater != null) {
                BaritoneWaterFall.recover(bot, entry); // a water source a bucket fall left behind is taken back (also when not driven)
            }
            if (!wasDriven && !busy(baritone)) {
                return false;
            }
            entry.context.refreshEntities();
            injectTestFault("before_physics");
            baritone.getGameEventHandler().onTick(nextTick(EventState.PRE));
            // A callback in PRE may have changed controller ownership in this same tick; do
            // not let that turn into a bridge input below.
            if (guardedStepBlocksBaritone(bot, entry, baritone)) {
                return false;
            }
            if (busy(baritone)) {
                if (!wasDriven) {
                    entry.driven = true;
                    bot.getActionPack().yieldToBaritone();
                    if (guardedStepBlocksBaritone(bot, entry, baritone)) {
                        return false;
                    }
                    BotLog.lifecycle(bot, "baritone_takeover", "goal", baritone.getPathingBehavior().getGoal());
                }
                if (guardedStepBlocksBaritone(bot, entry, baritone)) {
                    return false;
                }
                entry.startX = bot.getX();
                entry.startY = bot.getY();
                entry.startZ = bot.getZ();
                BotInputBridge.apply(bot, baritone);
                if (entry.waterAllowed) {
                    // The route may swim: the drowning safety net must not treat the swimming bot as a rescue case (it moved a
                    // submerged bot 1.43 blocks in the spike probe). Renewed every driven tick, so it lapses by itself with the drive.
                    NavSafetyNet.INSTANCE.renewBaritoneWater(bot);
                }
                return true;
            }
            if (wasDriven) {
                entry.driven = false;
                entry.waterAllowed = false;
                NavSafetyNet.INSTANCE.clearBaritoneWater(bot);
                BotInputBridge.release(bot);
                BotLog.lifecycle(bot, "baritone_released", "pos", bot.blockPosition());
            }
            return false;
        } catch (Throwable failure) {
            tickFailed(bot, "baritone_tick_failed", "tick_failed", failure);
            return false;
        }
    }

    /**
     * Cancels a direct or already-driven Baritone process before it can enter the input bridge.
     * This is intentionally a cancellation, never a lease release: only the exact step owner
     * (or NavSafetyNet's explicit emergency preemption) may remove the ActionPack fence.
     */
    private static boolean guardedStepBlocksBaritone(AIPlayerEntity bot, BaritoneRegistry.Entry entry, IBaritone baritone) {
        if (!bot.getActionPack().baritoneControlBlocked()) {
            return false;
        }
        if (entry.driven || busy(baritone)) {
            // Registry preemption clears forced Baritone keys when it had driven a prior tick,
            // cancels a just-started direct process, and preserves its normal route/water cleanup.
            BaritoneRegistry.INSTANCE.preempt(bot, ActionPack.GUARDED_STEP_FENCE);
        }
        return true;
    }

    /**
     * Runs steps 4-7 for a bot that {@link #beforePhysics} reported as driven. Server thread only; never throws (see above).
     * Returns whether the steps ran to the end: false when the bot is no longer driven or a step failed (contained, and logged),
     * in which case the caller lets the legacy executor take the rest of the tick.
     */
    public static boolean afterPhysics(AIPlayerEntity bot) {
        BaritoneRegistry.Entry entry = BaritoneRegistry.INSTANCE.entry(bot.getUUID());
        if (entry == null || !entry.driven) {
            return false;
        }
        IBaritone baritone = entry.baritone;
        try {
            // 4. The look target of this tick becomes the bot's rotation. LookBehavior writes yRot/xRot only; 5. the head and
            // the body follow the way LookAction turns a bot, so what other players see and what the legacy aim reads agree.
            injectTestFault("after_physics");
            baritone.getGameEventHandler().onPlayerUpdate(new PlayerUpdateEvent(EventState.PRE));
            LookAction.setYawPitch(bot, bot.getYRot(), bot.getXRot());
            // 6. ServerPlayer only checks falls when a client's move packet arrives; a bot has none, so this is what the packet does:
            // the fall distance and the damage. The deltas are this tick's physics movement. AIPlayerEntity.tick makes the same check
            // for every tick that is not driven, and skips its own for this one (markFallChecked), so nothing is charged twice.
            double fallBefore = bot.fallDistance;
            float healthBefore = bot.getHealth();
            bot.doCheckFallDamage(bot.getX() - entry.startX, bot.getY() - entry.startY, bot.getZ() - entry.startZ, bot.onGround());
            bot.markFallChecked(); // the bot's own per-tick check (every legacy tick) must not charge this tick again
            if (fallBefore > 0.0D && bot.onGround()) {
                BotLog.danger(bot, "baritone_landing", "fall", fallBefore, "damage", healthBefore - bot.getHealth(), "fall_after", bot.fallDistance);
            }
            // 7.
            baritone.getGameEventHandler().onPlayerUpdate(new PlayerUpdateEvent(EventState.POST));
            baritone.getGameEventHandler().onPostTick(nextTick(EventState.POST));
            // The navigator seam's per-tick check (arrival, failure, timeout, water rule) while this bot is driven; a bot that is
            // not driven gets the same check from ActionPack.onUpdate.
            bot.getActionPack().onBaritoneTick();
            return true;
        } catch (Throwable failure) {
            tickFailed(bot, "baritone_post_tick_failed", "post_tick_failed", failure);
            return false;
        }
    }

    /**
     * A Baritone call of a driven tick threw. Anything that is not a true VM error is contained here, so a bot's tick (and the
     * server) never dies of it: a linkage-type failure (a class that cannot load or initialise on this first driven tick: a mixin or
     * remap problem in some modpack; see {@link NavEngineSelector#isInitialisationFailure}) retires Baritone for the session, which
     * lets go of every bot and hands all of them to the legacy navigator; any other failure only resets this bot's Baritone.
     */
    private static void tickFailed(AIPlayerEntity bot, String event, String reason, Throwable failure) {
        if (failure instanceof VirtualMachineError fatal && !(failure instanceof StackOverflowError)) {
            throw fatal;
        }
        try {
            BotLog.error(bot, event, failure);
        } catch (Throwable ignored) {
            // logging must not make it worse
        }
        if (NavEngineSelector.isInitialisationFailure(failure)) {
            NavEngineSelector.markBaritoneUnavailable(event, failure);
            return;
        }
        try {
            BaritoneRegistry.INSTANCE.reset(bot, reason);
        } catch (Throwable resetFailed) {
            NavEngineSelector.handleFailure(event + "_reset", resetFailed);
        }
    }

    /** Baritone wants (or holds) control of the bot: a process is in control, a path is searched or executed, or a key is held. */
    static boolean busy(IBaritone baritone) {
        var pathing = baritone.getPathingBehavior();
        return pathing.isPathing()
                || pathing.getInProgress().isPresent()
                || baritone.getPathingControlManager().mostRecentInControl().isPresent()
                || baritone.getCustomGoalProcess().isActive()
                || baritone.getMineProcess().isActive()
                || baritone.getGetToBlockProcess().isActive()
                || baritone.getFollowProcess().isActive()
                || baritone.getExploreProcess().isActive()
                || baritone.getFarmProcess().isActive()
                || baritone.getBuilderProcess().isActive();
    }

    private static TickEvent nextTick(EventState state) {
        BiFunction<EventState, TickEvent.Type, TickEvent> provider = TickEvent.createNextProvider();
        return provider.apply(state, TickEvent.Type.IN);
    }
}
