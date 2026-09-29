package io.github.zoyluo.minecraftai.baritone;

import baritone.api.IBaritone;
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
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
     * Runs steps 1-3 for the bot and reports whether Baritone owns its movement this tick. When it does the caller must skip
     * every legacy input write for the tick. Server thread only. Never throws: a failing Baritone tick cancels Baritone for the
     * bot (and is logged) instead of taking the bot's tick, and the server's, down with it.
     */
    public static boolean beforePhysics(AIPlayerEntity bot) {
        BaritoneRegistry.Entry entry = BaritoneRegistry.INSTANCE.entry(bot.getUUID());
        if (entry == null) {
            return false;
        }
        IBaritone baritone = entry.baritone;
        if (!entry.driven && !busy(baritone)) {
            return false;
        }
        if (!bot.isAlive() || bot.isRemoved()) {
            return false;
        }
        boolean wasDriven = entry.driven;
        try {
            entry.bot = bot;
            entry.context.refreshEntities();
            baritone.getGameEventHandler().onTick(nextTick(EventState.PRE));
            if (busy(baritone)) {
                if (!wasDriven) {
                    entry.driven = true;
                    bot.getActionPack().yieldToBaritone();
                    BotLog.lifecycle(bot, "baritone_takeover", "goal", baritone.getPathingBehavior().getGoal());
                }
                entry.startX = bot.getX();
                entry.startY = bot.getY();
                entry.startZ = bot.getZ();
                BotInputBridge.apply(bot, baritone);
                return true;
            }
            if (wasDriven) {
                entry.driven = false;
                BotInputBridge.release(bot);
                BotLog.lifecycle(bot, "baritone_released", "pos", bot.blockPosition());
            }
            return false;
        } catch (RuntimeException exception) {
            BotLog.error(bot, "baritone_tick_failed", exception);
            BaritoneRegistry.INSTANCE.reset(bot, "tick_failed");
            return false;
        }
    }

    /**
     * Runs steps 4-7 for a bot that {@link #beforePhysics} reported as driven. Server thread only; never throws (see above).
     */
    public static void afterPhysics(AIPlayerEntity bot) {
        BaritoneRegistry.Entry entry = BaritoneRegistry.INSTANCE.entry(bot.getUUID());
        if (entry == null || !entry.driven) {
            return;
        }
        IBaritone baritone = entry.baritone;
        try {
            // 4. The look target of this tick becomes the bot's rotation. LookBehavior writes yRot/xRot only; 5. the head and
            // the body follow the way LookAction turns a bot, so what other players see and what the legacy aim reads agree.
            baritone.getGameEventHandler().onPlayerUpdate(new PlayerUpdateEvent(EventState.PRE));
            LookAction.setYawPitch(bot, bot.getYRot(), bot.getXRot());
            // 6. ServerPlayer only checks falls when a client's move packet arrives; a bot has none, so without this a bot
            // takes no fall damage at all and fallDistance stays 0. The deltas are this tick's physics movement.
            double fallBefore = bot.fallDistance;
            float healthBefore = bot.getHealth();
            bot.doCheckFallDamage(bot.getX() - entry.startX, bot.getY() - entry.startY, bot.getZ() - entry.startZ, bot.onGround());
            if (fallBefore > 0.0D && bot.onGround()) {
                BotLog.danger(bot, "baritone_landing", "fall", fallBefore, "damage", healthBefore - bot.getHealth(), "fall_after", bot.fallDistance);
            }
            // 7.
            baritone.getGameEventHandler().onPlayerUpdate(new PlayerUpdateEvent(EventState.POST));
            baritone.getGameEventHandler().onPostTick(nextTick(EventState.POST));
        } catch (RuntimeException exception) {
            BotLog.error(bot, "baritone_post_tick_failed", exception);
            BaritoneRegistry.INSTANCE.reset(bot, "post_tick_failed");
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
