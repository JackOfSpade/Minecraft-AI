package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations.SpawnState;
import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations.SpawnTicket;
import dev.spawnbotswrapper.inhabitants.adapter.SpawnPolicy.Decision;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bookkeeping of the spawns this adapter was asked for, and the poll that turns "what can be observed
 * right now" into {@link SpawnState}. It never touches Minecraft or PvP BOT itself: everything it needs to
 * see or do goes through {@link World}, so the whole poll protocol (grace, a single re-list, verification,
 * refusal of real players) is testable without a server.
 * <p>
 * The protocol exists because PvP BOT's answer to a spawn is worthless (it says true when nothing spawned)
 * and its list can lose a bot whose entity arrives late. A spawn is finished only when an online
 * <em>bot</em> entity exists AND PvP BOT lists it; an online bot that PvP BOT does not list is re-listed once.
 * Only names this adapter (or, after a restart, its caller) asked for are ever tracked, so nothing else can
 * be adopted.
 */
final class SpawnTracker {

    /** A ticket nobody polled to completion for this long is dropped (10 minutes at 20 tps). */
    static final long TICKET_TTL_TICKS = 12_000L;

    /** An online player as seen by one poll. {@code handle} is whatever the {@link World} needs to act on it. */
    record Player(boolean bot, UUID uuid, Object handle) {
    }

    /** What one poll may observe or do. */
    interface World {
        /** Current server tick, or a negative number when unknown. */
        long tick();

        /** The online player with this name (any case), or null. */
        Player player(String name);

        /** Whether PvP BOT lists this name (any case). */
        boolean listed(String name);

        /** Whether an adopt path exists (only the class API can re-list a bot). */
        boolean canRelist();

        /** Lists the online bot with PvP BOT again. */
        void relist(String name, Player player) throws Throwable;
    }

    /** Per-request state; mutated only on the server thread. */
    private static final class Ticket {
        final long id;
        final String name;
        final long requestedAtTick;
        String failure;
        long entitySeenTick = -1L;
        boolean relistAttempted;
        long relistTick = -1L;

        Ticket(long id, String name, long requestedAtTick) {
            this.id = id;
            this.name = name;
            this.requestedAtTick = requestedAtTick;
        }

        String key() {
            return keyOf(id, name);
        }
    }

    /**
     * Ids restart at 1 in every JVM run, so a ticket rebuilt from a previous run can share an id with a live
     * one. The name is part of the identity so the two never mix.
     */
    private static String keyOf(long id, String name) {
        return id + ":" + NameRules.key(name);
    }

    private final Diagnostics log;
    private final Map<String, Ticket> tickets = new LinkedHashMap<>();
    private long nextId = 1L;

    SpawnTracker(Diagnostics log) {
        this.log = log;
    }

    int size() {
        return tickets.size();
    }

    void clear() {
        tickets.clear();
    }

    /** Starts tracking a request for {@code name} and returns its handle. */
    SpawnTicket open(String name, long now) {
        prune(now);
        Ticket t = new Ticket(nextId++, name == null ? "" : name, Math.max(now, 0L));
        tickets.put(t.key(), t);
        return handle(t);
    }

    /** Records that the request was refused or could not be issued; every later poll reports it. */
    void fail(SpawnTicket handle, String reason) {
        Ticket t = tickets.get(keyOf(handle.id(), handle.name()));
        if (t != null) {
            t.failure = reason;
        }
    }

    private static SpawnTicket handle(Ticket t) {
        return new SpawnTicket(t.id, t.name, t.requestedAtTick);
    }

    private void prune(long now) {
        if (now >= 0) {
            tickets.values().removeIf(t -> now - t.requestedAtTick > TICKET_TTL_TICKS);
        }
    }

    /**
     * The state for a ticket handed back by the caller. A ticket this tracker did not issue in this session
     * (the caller rebuilds one for a spawn that was still open when the server last stopped) is tracked from
     * now on like any other: the caller only polls names it requested itself, and everything that touches
     * PvP BOT for it is still gated on the entity being a bot.
     */
    private Ticket ticketFor(SpawnTicket handle, long now) {
        String key = keyOf(handle.id(), handle.name());
        Ticket t = tickets.get(key);
        if (t == null) {
            prune(now);
            t = new Ticket(handle.id(), handle.name() == null ? "" : handle.name(), Math.max(handle.requestedAtTick(), 0L));
            if (!NameRules.isValid(t.name)) {
                t.failure = NameRules.refusal(NameRules.Check.INVALID_FORMAT, t.name);
            }
            tickets.put(key, t);
        }
        return t;
    }

    /** One poll. Exceptions from {@code world} propagate; the caller decides how to survive them. */
    SpawnState poll(SpawnTicket handle, World world) throws Throwable {
        if (handle == null) {
            return new SpawnState.Failed("no spawn ticket given");
        }
        long now = world.tick();
        Ticket t = ticketFor(handle, now);
        Player player = t.failure == null ? world.player(t.name) : null;
        boolean present = player != null;
        boolean bot = present && player.bot();
        boolean listed = t.failure == null && world.listed(t.name);

        if (present && bot && !listed) {
            if (t.entitySeenTick < 0) {
                t.entitySeenTick = now;
            }
        } else {
            t.entitySeenTick = -1L;
        }

        Decision decision = SpawnPolicy.decide(new SpawnPolicy.PollInput(t.name, t.failure, present, bot, listed, now,
                t.entitySeenTick, t.relistAttempted, t.relistTick, world.canRelist()));
        return switch (decision) {
            case Decision.Ready ready -> {
                tickets.remove(t.key());
                yield new SpawnState.Ready(player.uuid());
            }
            // A failed ticket is kept (until it ages out) so that polling it again gives the same answer instead
            // of starting a fresh attempt: a bot that was re-listed once must not be re-listed again.
            case Decision.Failed failed -> new SpawnState.Failed(failed.reason());
            case Decision.Relist relist -> {
                relist(world, t, player, now);
                yield new SpawnState.Pending();
            }
            case Decision.Pending pending -> new SpawnState.Pending();
        };
    }

    /** Lists an online, unlisted bot again, once. A failure is logged and counts as the attempt. */
    private void relist(World world, Ticket t, Player player, long now) {
        t.relistAttempted = true;
        t.relistTick = now;
        try {
            world.relist(t.name, player);
            log.debugOnce("relist|" + NameRules.key(t.name), "re-listed the bot '" + t.name + "' with PvP BOT: its "
                    + "entity appeared after PvP BOT had already dropped the name");
        } catch (Throwable e) {
            log.failure("re-listing a bot with PvP BOT", e);
        }
    }
}
