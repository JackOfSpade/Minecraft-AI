package io.github.zoyluo.minecraftai.baritone;

import baritone.api.IBaritone;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
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

    public BaritonePolicy policy(AIPlayerEntity bot) {
        Entry entry = entries.get(bot.getUUID());
        return entry == null ? BaritonePolicy.UNRESTRICTED : entry.context.policy();
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
     * searched or executed. Legacy code asks this before deciding the bot is idle.
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
     * The legacy action executor is about to give the bot its own orders: whatever Baritone is doing stops (goal, path, search,
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
                BaritoneHost.destroy(entry.baritone);
            }
        }
        BaritoneEdits.clearAll();
        if (!all.isEmpty()) {
            BotLog.lifecycle("baritone_cleared", "count", all.size());
        }
    }

    /** Cancels everything Baritone is doing and lets go of the bot: no more forced inputs, no driving. */
    private static void halt(Entry entry, AIPlayerEntity bot) {
        boolean wasDriven = entry.driven;
        entry.driven = false;
        try {
            cancelAll(entry);
        } finally {
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
            var behavior = entry.baritone.getPathingBehavior();
            BotLog.path(bot, "baritone_path_event", "event", event.name(), "goal", behavior.getGoal());
        }
    }
}
