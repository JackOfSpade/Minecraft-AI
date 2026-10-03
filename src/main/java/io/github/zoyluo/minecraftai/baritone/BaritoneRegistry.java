package io.github.zoyluo.minecraftai.baritone;

import baritone.api.IBaritone;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.navigation.NavRoute;
import io.github.zoyluo.minecraftai.task.NavSafetyNet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * One {@link IBaritone} per bot, keyed by the bot's UUID: created on first use, torn down when the bot is removed.
 *
 * <p>Ownership rules (they are what keeps searches and instances from outliving a bot):</p>
 * <ul>
 *   <li>Instances are created and destroyed on the server thread only ({@link #get}, {@link #forget}, {@link #clearAll});
 *       reads ({@link #find}) are safe anywhere.</li>
 *   <li>{@link #forget} runs from {@code RuntimeLifecycleCoordinator} when a bot is deleted or unloaded: it cancels every
 *       search the instance has in flight (its own {@code PathingBehavior} search and any {@link BaritonePlanner} search),
 *       releases the inputs and the block it was breaking, and destroys the instance, so the entry does not pin the entity.</li>
 *   <li>{@link #reset} runs on death and on a runtime reset: same cancellation, but the instance stays (the bot is still
 *       there, and Baritone must not be left steering a body that just respawned or been sent somewhere else).</li>
 *   <li>A worker that is mid-search when its bot goes away is not interrupted (an A* has no safe interruption point other
 *       than its own cancel flag, which {@code cancel} sets); it finishes on the immutable snapshot it was given, and its
 *       result is dropped because the search was cancelled.</li>
 * </ul>
 *
 * <p>Who moves the bot: the registry also knows whether Baritone <em>drives</em> a bot right now (a process is in control or
 * a path is being executed or searched). {@link BaritoneDriver} runs the per-tick phases; the legacy action executor
 * ({@code ActionPack}) yields to a driving Baritone and {@link #preempt takes the bot back} when it starts something itself,
 * so exactly one of the two writes the bot's movement at any time.</p>
 */
public final class BaritoneRegistry {
    public static final BaritoneRegistry INSTANCE = new BaritoneRegistry();

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    private BaritoneRegistry() {
    }

    /** What the registry knows about one bot. */
    static final class Entry {
        final IBaritone baritone;
        final ServerPlayerContext context;
        /** The bot's live entity. Written on the server thread, read by workers through the context's supplier. */
        volatile AIPlayerEntity bot;
        /** Baritone owns the bot's movement inputs right now (set and cleared by {@link BaritoneDriver} and {@link #preempt}). */
        volatile boolean driven;
        /**
         * The route Baritone is executing may go through water (set by the navigator per request, or by a caller that drives
         * Baritone directly): while it is set and Baritone drives the bot, the drowning safety net leases the bot to Baritone
         * (see {@code NavSafetyNet#renewBaritoneWater}), and the lease ends with the drive.
         */
        volatile boolean waterAllowed;
        /** The route whose immutable observation boundary is currently exposed to this instance. */
        volatile NavRoute observedRoute;
        /**
         * A route was stopped because its terrain authority disappeared, rather than because
         * Baritone merely ended its path. Kept until the owning ActionPack records the typed
         * outcome or a newly admitted route replaces it.
         */
        volatile String observationRevocationReason;
        /** The last path event Baritone reported and the server tick it arrived in (how a route that ended short of its goal failed). */
        volatile PathEvent lastEvent;
        volatile int lastEventTick = -1;
        /** The water source a bucket fall placed and has not taken back yet, and the server tick it was placed in ({@link BaritoneWaterFall}). */
        volatile net.minecraft.core.BlockPos placedWater;
        /** Dimension of {@link #placedWater}; own-action provenance never crosses a teleport. */
        volatile String placedWaterDimension;
        volatile int placedWaterTick;
        /** Where the bot stood when its last driven physics tick began (for the fall-damage check after it). */
        double startX;
        double startY;
        double startZ;

        Entry(AIPlayerEntity bot, Function<Entry, IBaritone> factory) {
            this.bot = bot;
            this.baritone = factory.apply(this);
            this.context = (ServerPlayerContext) baritone.getPlayerContext();
        }
    }

    /** The bot's instance, created on first call. Server thread only. */
    public IBaritone get(AIPlayerEntity bot) {
        assertServerThread(bot);
        Entry entry = entries.get(bot.getUUID());
        if (entry == null) {
            entry = new Entry(bot, self -> {
                IBaritone created = BaritoneHost.create(() -> self.bot);
                created.getGameEventHandler().registerEventListener(new PathEventLogger(self));
                return created;
            });
            entries.put(bot.getUUID(), entry);
            NavEngineSelector.markBaritoneLive();
            NavEngineSelector.setUnavailableHook(BaritoneRegistry::abandonAll);
            BotLog.lifecycle(bot, "baritone_created", "instances", entries.size());
        } else {
            entry.bot = bot; // the same bot may have been given a new entity (respawn from a saved record)
        }
        return entry.baritone;
    }

    /** The instance of the bot with this UUID, or null. Safe from any thread. */
    public IBaritone find(UUID botId) {
        Entry entry = entries.get(botId);
        return entry == null ? null : entry.baritone;
    }

    /** The entry of the bot with this UUID, or null (package-private: the driver works on entries). */
    Entry entry(UUID botId) {
        return entries.get(botId);
    }

    public int size() {
        return entries.size();
    }

    /**
     * The break/place permission of the bot's Baritone instance (created if needed). Takes effect at the next plan; a path
     * that is already running re-validates its costs every tick and is cancelled as soon as it needs what is now forbidden.
     */
    public void setPolicy(AIPlayerEntity bot, BaritonePolicy policy) {
        get(bot);
        entries.get(bot.getUUID()).context.setPolicy(policy);
    }

    /**
     * Declares that what Baritone is doing for the bot may take it through water (a swim route): the drowning safety net then
     * leases the bot to Baritone for as long as Baritone drives it. Cleared with every hand-over, so it has to be set again by
     * whoever starts the next route.
     */
    public void setWaterAllowed(AIPlayerEntity bot, boolean allowed) {
        get(bot);
        entries.get(bot.getUUID()).waterAllowed = allowed;
    }

    /** The last path event of the bot's instance and the server tick it arrived in; null without one. */
    PathEvent lastPathEvent(AIPlayerEntity bot) {
        Entry entry = entries.get(bot.getUUID());
        return entry == null ? null : entry.lastEvent;
    }

    /** Forgets the last path event (a new route starts). */
    void clearLastPathEvent(AIPlayerEntity bot) {
        Entry entry = entries.get(bot.getUUID());
        if (entry != null) {
            entry.lastEvent = null;
            entry.lastEventTick = -1;
        }
    }

    public BaritonePolicy policy(AIPlayerEntity bot) {
        Entry entry = entries.get(bot.getUUID());
        return entry == null ? BaritonePolicy.UNRESTRICTED : entry.context.policy();
    }

    /** Publishes an immutable route fence before Baritone is allowed to warm up, plan, or execute. */
    public void setObservationFence(AIPlayerEntity bot, ObservedNavigationFence fence) {
        setObservationFence(bot, fence, null);
    }

    /** Publishes an immutable fence together with the route the tick driver must refresh. */
    public void setObservationFence(AIPlayerEntity bot, ObservedNavigationFence fence, NavRoute route) {
        get(bot);
        Entry entry = entries.get(bot.getUUID());
        entry.context.setObservationFence(fence, route != null && route.shape() == NavRoute.Shape.OWNER_FOLLOW);
        entry.observedRoute = route;
        entry.observationRevocationReason = null;
    }

    /**
     * Publishes the result of this bot's own confirmed placement to the active immutable fence.
     * The fence method refuses a cell that was not already admitted, so this cannot become an
     * alternate terrain-observation path or reveal a neighbour behind the placement.
     */
    void recordObservedPlacement(AIPlayerEntity bot, net.minecraft.core.BlockPos pos,
                                 net.minecraft.world.level.block.state.BlockState state) {
        Entry entry = entries.get(bot.getUUID());
        // The action result is trustworthy only for a cell in the live context's current
        // dimension fence. In particular, do not let a click that raced a teleport update a
        // same-coordinate fence from the old dimension.
        if (entry == null || entry.observedRoute == null || !entry.context.allowNavigationActionCell(pos)) {
            return;
        }
        ObservedNavigationFence before = entry.context.observationFence();
        ObservedNavigationFence after = before.withTrustedActionResult(pos, state, bot.getServer().getTickCount());
        if (after == before) {
            return;
        }
        entry.context.setObservationFence(after, entry.observedRoute != null
                && entry.observedRoute.shape() == NavRoute.Shape.OWNER_FOLLOW);
        BotLog.path(bot, "nav_observed_placement_result", "pos", pos, "generation", after.generation());
    }

    /** The active worker-visible fence, or an empty deny-all boundary before an admission. */
    public ObservedNavigationFence observationFence(AIPlayerEntity bot) {
        Entry entry = entries.get(bot.getUUID());
        return entry == null ? ObservedNavigationFence.empty() : entry.context.observationFence();
    }

    /**
     * Dimension-aware final admission for a live Baritone block action. The immutable fence by
     * itself carries no player/dimension identity, so callers that are about to inspect the live
     * world must use the owning context rather than calling {@link ObservedNavigationFence#allows}
     * directly.
     */
    boolean allowNavigationActionCell(AIPlayerEntity bot, net.minecraft.core.BlockPos pos) {
        Entry entry = entries.get(bot.getUUID());
        return entry != null && entry.context.allowNavigationActionCell(pos);
    }

    /** Bounded per-bot memory, kept separate from the active worker-visible fence. */
    public ObservedNavigationFence observationMemory(AIPlayerEntity bot) {
        Entry entry = entries.get(bot.getUUID());
        return entry == null ? ObservedNavigationFence.empty() : entry.context.observationMemory();
    }

    /** Releases active terrain authority while retaining only bounded per-bot memory for a future observed route. */
    public void clearObservationFence(AIPlayerEntity bot) {
        Entry entry = entries.get(bot.getUUID());
        if (entry != null) {
            entry.context.clearObservationFence();
            entry.observedRoute = null;
        }
    }

    /**
     * Revokes an active route because its immutable world view is no longer valid. This differs
     * from an ordinary preemption: {@link BaritoneNavigator#progress(AIPlayerEntity, NavRoute)}
     * can preserve the typed observation-loss outcome for the ActionPack instead of reporting a
     * misleading generic incomplete path. A teleport/dimension boundary also clears memory.
     */
    public void revokeObservation(AIPlayerEntity bot, String reason, boolean clearMemory) {
        Entry entry = entries.get(bot.getUUID());
        if (entry == null) {
            return;
        }
        if (hopToServerThread(bot, () -> revokeObservation(bot, reason, clearMemory))) {
            return;
        }
        entry.observationRevocationReason = reason == null || reason.isBlank()
                ? "observation_revoked" : reason;
        halt(entry, bot);
        if (clearMemory) {
            entry.context.clearObservationMemory();
        }
        BotLog.lifecycle(bot, "baritone_observation_revoked", "reason", entry.observationRevocationReason,
                "clear_memory", clearMemory);
    }

    /** The pending typed reason for a route stopped by {@link #revokeObservation}, or null. */
    String observationRevocationReason(AIPlayerEntity bot) {
        Entry entry = entries.get(bot.getUUID());
        return entry == null ? null : entry.observationRevocationReason;
    }

    /** Same release operation for a navigator completion path that only has the bot id. */
    public void clearObservationFence(UUID botId) {
        Entry entry = entries.get(botId);
        if (entry != null) {
            entry.context.clearObservationFence();
            entry.observedRoute = null;
        }
    }

    /** Active route metadata for the server-thread observation refresh boundary. */
    NavRoute observedRoute(AIPlayerEntity bot) {
        Entry entry = entries.get(bot.getUUID());
        return entry == null ? null : entry.observedRoute;
    }

    /**
     * Runs one raw Baritone game tick for the bot: refreshes the observable-entity list, then dispatches the tick event.
     * This is the middle of {@link BaritoneDriver#beforePhysics}, which is what the bot's own tick calls; on its own it
     * neither applies the inputs Baritone forced nor aims (tests that poke the behavior stack use it). Server thread only.
     */
    public void tick(AIPlayerEntity bot) {
        IBaritone baritone = get(bot);
        Entry entry = entries.get(bot.getUUID());
        entry.context.refreshEntities();
        baritone.getGameEventHandler().onTick(TickEvent.createNextProvider().apply(EventState.PRE, TickEvent.Type.IN));
    }

    /**
     * Whether Baritone is doing something with the bot: it drives it this tick, or a process wants control, or a path is being
     * searched or executed. Local physical-action code asks this before deciding the bot is idle.
     */
    public boolean isBusy(AIPlayerEntity bot) {
        Entry entry = entries.get(bot.getUUID());
        if (entry == null) {
            return false;
        }
        // The process and path state is the server thread's; another thread only gets the flag.
        return entry.driven || (bot.getServer().isSameThread() && BaritoneDriver.busy(entry.baritone));
    }

    /**
     * A local physical action is about to give the bot its own orders: whatever Baritone is doing stops (goal, path, search,
     * held keys, the block being broken) and the inputs it wrote are let go, before the caller writes its own. No-op when
     * Baritone is not busy with the bot. Server thread only.
     */
    public void preempt(AIPlayerEntity bot, String why) {
        Entry entry = entries.get(bot.getUUID());
        if (entry == null || !(entry.driven || BaritoneDriver.busy(entry.baritone))) {
            return;
        }
        if (hopToServerThread(bot, () -> preempt(bot, why))) {
            return;
        }
        halt(entry, bot);
        BotLog.lifecycle(bot, "baritone_preempted", "by", why);
    }

    /** Stops whatever Baritone is doing for the bot but keeps its instance. Server thread only; no-op if it has none. */
    public void reset(AIPlayerEntity bot, String reason) {
        Entry entry = entries.get(bot.getUUID());
        if (entry == null) {
            return;
        }
        if (hopToServerThread(bot, () -> reset(bot, reason))) {
            return;
        }
        halt(entry, bot);
        entry.context.clearObservationMemory();
        BotLog.lifecycle(bot, "baritone_reset", "reason", reason);
    }

    /** Cancels everything and destroys the bot's instance. Server thread only; no-op if it has none. */
    public void forget(AIPlayerEntity bot, String reason) {
        Entry entry = entries.remove(bot.getUUID());
        if (entry == null) {
            return;
        }
        if (hopToServerThread(bot, () -> teardown(bot, entry, reason))) {
            return;
        }
        teardown(bot, entry, reason);
    }

    private void teardown(AIPlayerEntity bot, Entry entry, String reason) {
        try {
            halt(entry, bot);
        } finally {
            entry.context.clearObservationMemory();
            BaritoneEdits.clear(bot.getUUID());
            BaritoneHost.destroy(entry.baritone);
            BotLog.lifecycle(bot, "baritone_destroyed", "reason", reason, "instances", entries.size());
        }
    }

    /** Server stop / world boundary: every instance goes. */
    public void clearAll() {
        List<Entry> all = List.copyOf(entries.values());
        entries.clear();
        for (Entry entry : all) {
            try {
                cancelAll(entry);
            } finally {
                entry.driven = false;
                entry.context.clearObservationMemory();
                BaritoneHost.destroy(entry.baritone);
            }
        }
        BaritoneEdits.clearAll();
        BaritoneNavigator.releaseAllRoutes();
        if (!all.isEmpty()) {
            BotLog.lifecycle("baritone_cleared", "count", all.size());
        }
    }

    /**
     * Baritone was given up on ({@link NavEngineSelector#markBaritoneUnavailable}): every bot is let go of and every instance,
     * route and ledger is dropped, each step best effort (the classes involved may be the very ones that failed). From here on
     * the hooks skip Baritone ({@link NavEngineSelector#baritoneActive}) and every active route remains stopped.
     */
    static void abandonAll() {
        INSTANCE.abandon();
    }

    private void abandon() {
        List<Entry> all = List.copyOf(entries.values());
        entries.clear();
        for (Entry entry : all) {
            AIPlayerEntity bot = entry.bot;
            boolean wasDriven = entry.driven;
            entry.driven = false;
            entry.waterAllowed = false;
            entry.context.clearObservationMemory();
            bestEffort(() -> cancelAll(entry));
            bestEffort(() -> NavSafetyNet.INSTANCE.clearBaritoneWater(bot));
            if (wasDriven) {
                bestEffort(() -> BotInputBridge.release(bot));
            }
            bestEffort(() -> BaritoneHost.destroy(entry.baritone));
        }
        bestEffort(BaritoneEdits::clearAll);
        bestEffort(BaritoneNavigator::releaseAllRoutes);
        bestEffort(() -> BotLog.lifecycle("baritone_abandoned", "count", all.size()));
    }

    private static void bestEffort(Runnable step) {
        try {
            step.run();
        } catch (Throwable ignored) {
            // the step may need the very class that failed
        }
    }

    /** Cancels everything Baritone is doing and lets go of the bot: no more forced inputs, no driving. */
    private static void halt(Entry entry, AIPlayerEntity bot) {
        boolean wasDriven = entry.driven;
        entry.driven = false;
        entry.waterAllowed = false;
        entry.observedRoute = null;
        entry.context.clearObservationFence();
        try {
            cancelAll(entry);
        } finally {
            NavSafetyNet.INSTANCE.clearBaritoneWater(bot);
            BaritoneNavigator.releaseRoute(bot.getUUID());
            if (wasDriven) {
                BotInputBridge.release(bot);
            }
        }
    }

    private static void cancelAll(Entry entry) {
        BaritonePlanner.cancel(entry.baritone);
        try {
            entry.baritone.getPathingBehavior().forceCancel();
            entry.baritone.getInputOverrideHandler().clearAllKeys();
            entry.baritone.getPlayerContext().playerController().resetBlockRemoving();
        } catch (RuntimeException e) {
            // A half-torn-down bot (entity already removed from its level) must not stop the rest of the cleanup.
            BotLog.config("baritone_cancel_failed", "error", String.valueOf(e));
        }
    }

    /** Lifecycle hooks normally run on the server thread; if one ever does not, the work is deferred to it. */
    private static boolean hopToServerThread(AIPlayerEntity bot, Runnable task) {
        if (bot.getServer().isSameThread()) {
            return false;
        }
        bot.getServer().execute(task);
        return true;
    }

    private static void assertServerThread(AIPlayerEntity bot) {
        if (!bot.getServer().isSameThread()) {
            throw new IllegalStateException("BaritoneRegistry must be used on the server thread");
        }
    }

    /** Baritone's path events (calculation finished/failed, segment finished, at goal, ...) into the bot's path log. */
    private static final class PathEventLogger implements AbstractGameEventListener {
        private final Entry entry;

        PathEventLogger(Entry entry) {
            this.entry = entry;
        }

        @Override
        public void onPathEvent(PathEvent event) {
            AIPlayerEntity bot = entry.bot;
            entry.lastEvent = event;
            entry.lastEventTick = bot.getServer().getTickCount();
            var behavior = entry.baritone.getPathingBehavior();
            BotLog.path(bot, "baritone_path_event", "event", event.name(), "goal", behavior.getGoal());
        }
    }
}
