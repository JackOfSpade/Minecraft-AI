package io.github.zoyluo.minecraftai.baritone;

import baritone.api.IBaritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.event.events.PathEvent;
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
 */
public final class BaritoneRegistry {
    public static final BaritoneRegistry INSTANCE = new BaritoneRegistry();

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    private BaritoneRegistry() {
    }

    /** What the registry knows about one bot. */
    private static final class Entry {
        final IBaritone baritone;
        final ServerPlayerContext context;
        /** The bot's live entity. Written on the server thread, read by workers through the context's supplier. */
        volatile AIPlayerEntity bot;

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

    public int size() {
        return entries.size();
    }

    /**
     * Runs one Baritone game tick for the bot: refreshes the observable-entity list, then dispatches the tick. The bot must
     * call this <em>before</em> its own physics tick so that the inputs Baritone forces are in place when the bot moves
     * (see the input bridge). Server thread only.
     */
    public void tick(AIPlayerEntity bot) {
        IBaritone baritone = get(bot);
        Entry entry = entries.get(bot.getUUID());
        entry.context.refreshEntities();
        baritone.getGameEventHandler().onTick(TickEvent.createNextProvider().apply(EventState.PRE, TickEvent.Type.IN));
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
        cancelAll(entry);
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
            cancelAll(entry);
        } finally {
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
                BaritoneHost.destroy(entry.baritone);
            }
        }
        if (!all.isEmpty()) {
            BotLog.lifecycle("baritone_cleared", "count", all.size());
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
