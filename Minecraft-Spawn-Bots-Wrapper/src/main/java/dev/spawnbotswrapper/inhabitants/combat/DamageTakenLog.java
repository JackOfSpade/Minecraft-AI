package dev.spawnbotswrapper.inhabitants.combat;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * The pure core of the "damage taken" diagnostic: one INFO line per hit an inhabitant takes, with who did it, how,
 * where, and the bot's {@link StateSnapshot}. The player-hits-inhabitant case used to leave no trace at all.
 * <p>
 * Throttle: hits with the same (victim, attacker) pair closer than {@code minGapTicks} to the last LOGGED hit of
 * that pair are counted and folded into the next line ("+N hits since last line"); the FIRST hit of a pair, and
 * any hit after a quiet gap, is always logged immediately. No clock, no logger: the caller passes the tick.
 * Server thread only.
 */
public final class DamageTakenLog {
    /** Default minimum gap between two lines for the same pair: half a second of server time. */
    public static final int DEFAULT_MIN_GAP_TICKS = 10;
    /** Pair entries older than this are forgotten (a fresh hit is then "first" again). */
    static final long FORGET_TICKS = 1200;
    static final int MAX_PAIRS = 512;

    /** Everything the line says; the snapshot is passed as text so this class stays free of Minecraft. */
    public record Taken(String victim, String population, String attacker, String attackerKind, String directCause,
                        String sourceType, String weapon, float damage, float baseDamage, boolean blocked,
                        float healthAfter, double x, double y, double z, String dimension, String snapshot) {
    }

    private static final class Pair {
        long lastLogged;
        int suppressed;
    }

    private final int minGapTicks;
    private final Map<String, Pair> pairs = new HashMap<>();

    public DamageTakenLog() {
        this(DEFAULT_MIN_GAP_TICKS);
    }

    public DamageTakenLog(int minGapTicks) {
        this.minGapTicks = Math.max(0, minGapTicks);
    }

    /**
     * Registers a hit and says whether to write a line now.
     *
     * @return -1 when the hit is folded into a later line (write nothing), else the number of hits folded into the
     *         line about to be written (0 for a plain line)
     */
    public int admit(long now, String victimKey, String attackerKey) {
        String key = victimKey + ">" + attackerKey;
        Pair p = pairs.get(key);
        if (p == null) {
            if (pairs.size() >= MAX_PAIRS) {
                prune(now);
            }
            p = new Pair();
            p.lastLogged = now;
            pairs.put(key, p);
            return 0;
        }
        // now < lastLogged: the server clock restarted (a new server in the same JVM); treat as a fresh pair.
        if (now < p.lastLogged || now - p.lastLogged >= minGapTicks) {
            int folded = p.suppressed;
            p.lastLogged = now;
            p.suppressed = 0;
            return folded;
        }
        p.suppressed++;
        return -1;
    }

    /** Number of remembered pairs (tests). */
    public int pairs() {
        return pairs.size();
    }

    /** Forgets everything (server stopping). */
    public void reset() {
        pairs.clear();
    }

    private void prune(long now) {
        for (Iterator<Pair> it = pairs.values().iterator(); it.hasNext(); ) {
            Pair p = it.next();
            if (now < p.lastLogged || now - p.lastLogged >= FORGET_TICKS) {
                it.remove();
            }
        }
        if (pairs.size() >= MAX_PAIRS) {
            pairs.clear(); // pathological: a few extra "first" lines beat unbounded growth
        }
    }

    /**
     * For example {@code Combat taken: Bob (inhabitant of village-1) took 4.0 damage from Steve (player) via
     * player_attack with iron_sword; Bob health now 16.0 at 10.5 64.0 -3.2 in minecraft:overworld | state: ...}.
     */
    public static String line(Taken t, int folded) {
        StringBuilder sb = new StringBuilder("Combat taken: ").append(t.victim()).append(" (inhabitant");
        if (t.population() != null) {
            sb.append(" of ").append(t.population());
        }
        sb.append(") took ").append(one(t.damage())).append(" damage");
        if (Math.abs(t.baseDamage() - t.damage()) > 0.05f) {
            sb.append(" (").append(one(t.baseDamage())).append(" before armor)");
        }
        if (t.blocked()) {
            sb.append(" [blocked]");
        }
        if (t.attacker() == null) {
            sb.append(" from no attacker");
        } else {
            sb.append(" from ").append(t.attacker());
            if (t.attackerKind() != null) {
                sb.append(" (").append(t.attackerKind()).append(')');
            }
        }
        sb.append(" via ").append(t.sourceType());
        if (t.directCause() != null) {
            sb.append(" [").append(t.directCause()).append(']');
        }
        sb.append(" with ").append(t.weapon()).append("; ").append(t.victim()).append(" health now ")
                .append(one(t.healthAfter()));
        if (folded > 0) {
            sb.append(" (+").append(folded).append(folded == 1 ? " hit" : " hits").append(" since last line)");
        }
        sb.append(" at ").append(one(t.x())).append(' ').append(one(t.y())).append(' ').append(one(t.z()))
                .append(" in ").append(t.dimension());
        if (t.snapshot() != null) {
            sb.append(" | state: ").append(t.snapshot());
        }
        return sb.toString();
    }

    private static String one(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
