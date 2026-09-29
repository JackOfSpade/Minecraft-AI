package dev.spawnbotswrapper.inhabitants.combat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * The pure core of the combat log: turns a stream of hit and death events that involve inhabitants into a small
 * number of readable log lines. No Minecraft, no logger, no clock (the caller passes the server tick and a
 * {@link Sink}), so the coalescing and rate limiting are unit tested exactly.
 * <p>
 * Three rules keep a long fight from spamming the log:
 * <ol>
 *   <li><b>Coalescing.</b> Hits between the same (attacker, victim) pair are folded into ONE summary line
 *       (count, total and largest damage, weapon, last distance, health after) emitted {@code coalesceTicks}
 *       after the first hit of the burst. A pair that keeps fighting produces one line per window, not per hit.</li>
 *   <li><b>Budget.</b> At most {@code maxLinesPerMinute} summary lines per minute of server time; the excess is
 *       counted and reported by a single "N line(s) suppressed" line when the minute rolls over.</li>
 *   <li><b>Deaths always show.</b> Kills and deaths are rare and permanent, so they bypass the budget; a pair's
 *       pending summary is flushed first so the log reads in order.</li>
 * </ol>
 * In {@code detail} mode (the addon's {@code debug: true}) every hit is written immediately and in full, with no
 * coalescing and no budget, including damage that has no attacker (fall, fire, ...). Server thread only.
 */
public final class CombatLedger {

    /** Ticks in the budget window: one minute of server time. */
    public static final long BUDGET_WINDOW_TICKS = 1200;
    /** Bound on pairs waiting for their summary; the oldest is flushed early beyond it. */
    static final int MAX_PENDING_PAIRS = 256;

    public enum Kind {
        INHABITANT("inhabitant"), PLAYER("player"), MOB("mob");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /** One side of a fight: a name (player name, or an entity type such as {@code zombie}) and what it is. */
    public record Actor(String name, Kind kind) {
        String describe() {
            return name + " (" + kind.label() + ")";
        }

        String key() {
            return kind.label() + ":" + name.toLowerCase(Locale.ROOT);
        }
    }

    /**
     * @param attacker    null for damage with no attacker (fall, fire, ...); such hits are only logged in detail mode
     * @param damage      damage actually taken
     * @param baseDamage  damage before armor and enchantment reduction
     * @param blocked     true when a shield blocked (part of) it
     * @param sourceType  the damage type, e.g. {@code player_attack}, {@code arrow}, {@code fall}
     * @param weapon      the weapon's item name, or "none"
     * @param distance    blocks between attacker and victim, or a negative number when there is no attacker
     * @param healthAfter the victim's health after the hit
     */
    public record Hit(Actor attacker, Actor victim, float damage, float baseDamage, boolean blocked,
                      String sourceType, String weapon, double distance, float healthAfter) {
    }

    /** @param killer null when nothing living caused the death (fall, lava, ...) */
    public record Death(Actor victim, Actor killer, String sourceType, String weapon, double distance) {
    }

    /** Where finished lines go (INFO level of the wrapper's logger in production). */
    @FunctionalInterface
    public interface Sink {
        void line(String line);
    }

    private static final class Burst {
        final Actor attacker;
        final Actor victim;
        final long firstTick;
        long lastTick;
        int hits;
        int blockedHits;
        double total;
        float largest;
        String weapon;
        String sourceType;
        double distance;
        float healthAfter;

        Burst(Actor attacker, Actor victim, long tick) {
            this.attacker = attacker;
            this.victim = victim;
            this.firstTick = tick;
            this.lastTick = tick;
        }
    }

    private final Sink sink;
    private final IntSupplier coalesceTicks;
    private final IntSupplier maxLinesPerMinute;
    private final Map<String, Burst> pending = new LinkedHashMap<>();
    private long windowStart = Long.MIN_VALUE;
    private int linesInWindow;
    private int suppressed;

    public CombatLedger(Sink sink, IntSupplier coalesceTicks, IntSupplier maxLinesPerMinute) {
        this.sink = sink;
        this.coalesceTicks = coalesceTicks;
        this.maxLinesPerMinute = maxLinesPerMinute;
    }

    /** Number of pairs currently waiting for their summary (tests and diagnostics). */
    public int pendingPairs() {
        return pending.size();
    }

    /** A hit involving an inhabitant. {@code detail}: write it now, in full, instead of coalescing. */
    public void hit(long now, Hit hit, boolean detail) {
        if (detail) {
            sink.line(detailLine(hit));
            return;
        }
        if (hit.attacker() == null) {
            return; // environmental damage is only interesting when someone asked for detail
        }
        String key = hit.attacker().key() + ">" + hit.victim().key();
        Burst burst = pending.get(key);
        if (burst == null) {
            if (pending.size() >= MAX_PENDING_PAIRS) {
                flushOldest(now);
            }
            burst = new Burst(hit.attacker(), hit.victim(), now);
            pending.put(key, burst);
        }
        burst.lastTick = now;
        burst.hits++;
        if (hit.blocked()) {
            burst.blockedHits++;
        }
        burst.total += hit.damage();
        burst.largest = Math.max(burst.largest, hit.damage());
        burst.weapon = hit.weapon();
        burst.sourceType = hit.sourceType();
        burst.distance = hit.distance();
        burst.healthAfter = hit.healthAfter();
    }

    /** A death of an inhabitant, or a kill made by one. Always logged, after the pending summaries it concludes. */
    public void death(long now, Death death) {
        flushInvolving(now, death.victim());
        boolean hasWeapon = death.weapon() != null && !death.weapon().equals("none");
        String how = death.sourceType() + (hasWeapon ? ", " + death.weapon() : "");
        if (death.killer() == null) {
            sink.line("Combat: " + death.victim().describe() + " died (" + how + ")");
            return;
        }
        String distance = death.distance() < 0 ? "" : ", " + blocks(death.distance()) + " away";
        if (death.victim().kind() == Kind.INHABITANT) {
            sink.line("Combat: " + death.victim().describe() + " was killed by " + death.killer().describe()
                    + " (" + how + distance + ")");
        } else {
            sink.line("Combat: " + death.killer().describe() + " killed " + death.victim().describe()
                    + " (" + how + distance + ")");
        }
    }

    /** Call once per server tick (cheap when nothing is pending): emits the summaries whose window has elapsed. */
    public void tick(long now) {
        rollBudget(now);
        if (pending.isEmpty()) {
            return;
        }
        long window = Math.max(1, coalesceTicks.getAsInt());
        List<Burst> due = null;
        for (Burst b : pending.values()) {
            // now < firstTick: the burst was recorded on another server clock (ticks restart at 0 on a new server),
            // so it can never age normally; it is stale and due immediately.
            if (now < b.firstTick || now - b.firstTick >= window) {
                if (due == null) {
                    due = new ArrayList<>();
                }
                due.add(b);
            } else {
                break; // insertion order is first-hit order, so nothing later is due either
            }
        }
        if (due != null) {
            for (Burst b : due) {
                pending.values().remove(b);
                emit(now, b);
            }
        }
    }

    /** Writes every pending summary now (shutdown, or the feature being switched off). */
    public void flushAll(long now) {
        List<Burst> all = new ArrayList<>(pending.values());
        pending.clear();
        for (Burst b : all) {
            emit(now, b);
        }
        rollBudgetForce(now);
    }

    /** Drops all pending summaries and budget state without writing anything (a new server starts its own clock). */
    public void reset() {
        pending.clear();
        windowStart = Long.MIN_VALUE;
        linesInWindow = 0;
        suppressed = 0;
    }

    // ------------------------------------------------------------------ internals

    private void flushInvolving(long now, Actor victim) {
        List<Burst> mine = new ArrayList<>();
        for (Iterator<Burst> it = pending.values().iterator(); it.hasNext(); ) {
            Burst b = it.next();
            if (b.victim.key().equals(victim.key()) || b.attacker.key().equals(victim.key())) {
                mine.add(b);
                it.remove();
            }
        }
        for (Burst b : mine) {
            emit(now, b);
        }
    }

    private void flushOldest(long now) {
        Iterator<Burst> it = pending.values().iterator();
        if (it.hasNext()) {
            Burst oldest = it.next();
            it.remove();
            emit(now, oldest);
        }
    }

    private void emit(long now, Burst b) {
        rollBudget(now);
        if (linesInWindow >= Math.max(1, maxLinesPerMinute.getAsInt())) {
            suppressed++;
            return;
        }
        linesInWindow++;
        StringBuilder sb = new StringBuilder("Combat: ").append(b.attacker.describe()).append(" hit ")
                .append(b.victim.describe()).append(' ').append(b.hits).append(b.hits == 1 ? " time" : " times")
                .append(" for ").append(one(b.total)).append(" damage");
        if (b.hits > 1) {
            sb.append(" (largest ").append(one(b.largest)).append(')');
        }
        sb.append(" with ").append(b.weapon).append(" [").append(b.sourceType).append(']');
        if (b.distance >= 0) {
            sb.append(", last at ").append(blocks(b.distance));
        }
        sb.append("; ").append(b.victim.name()).append(" health now ").append(one(b.healthAfter));
        if (b.blockedHits > 0) {
            sb.append(", ").append(b.blockedHits).append(" blocked");
        }
        long span = b.lastTick - b.firstTick;
        if (span > 0) {
            sb.append(" (over ").append(String.format(Locale.ROOT, "%.1f", span / 20.0)).append(" s)");
        }
        sink.line(sb.toString());
    }

    private void rollBudget(long now) {
        if (windowStart == Long.MIN_VALUE) {
            windowStart = now;
            return;
        }
        // now < windowStart: the server-tick clock restarted (a new server in the same JVM); start a fresh window.
        if (now < windowStart || now - windowStart >= BUDGET_WINDOW_TICKS) {
            rollBudgetForce(now);
        }
    }

    private void rollBudgetForce(long now) {
        if (suppressed > 0) {
            sink.line("Combat: " + suppressed + " more combat line(s) were suppressed in the last minute (limit "
                    + Math.max(1, maxLinesPerMinute.getAsInt()) + " per minute; hits are summed per attacker and victim,"
                    + " set debug to see every hit)");
        }
        suppressed = 0;
        linesInWindow = 0;
        windowStart = now;
    }

    private static String detailLine(Hit h) {
        String who = h.attacker() == null ? "(no attacker)" : h.attacker().describe();
        StringBuilder sb = new StringBuilder("Combat hit: ").append(who).append(" -> ").append(h.victim().describe())
                .append(": ").append(one(h.damage())).append(" damage");
        if (Math.abs(h.baseDamage() - h.damage()) > 0.05f) {
            sb.append(" (").append(one(h.baseDamage())).append(" before armor)");
        }
        if (h.blocked()) {
            sb.append(" [blocked]");
        }
        sb.append(" via ").append(h.sourceType()).append(" with ").append(h.weapon());
        if (h.distance() >= 0) {
            sb.append(" at ").append(blocks(h.distance()));
        }
        sb.append("; ").append(h.victim().name()).append(" health now ").append(one(h.healthAfter()));
        return sb.toString();
    }

    private static String one(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String blocks(double v) {
        return String.format(Locale.ROOT, "%.1f blocks", v);
    }
}
