package dev.spawnbotswrapper.inhabitants.combat;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Counts the melee hits vetoed by the melee legality rule and decides when a line goes to the log: every veto is a
 * debug line (the caller writes it), and one INFO line per bot per minute (1200 ticks) says how many hits were vetoed
 * since the last INFO line and why. No clock, no logger: the caller passes the tick. Server thread only.
 */
public final class MeleeVetoLog {
    /** Ticks between two INFO lines about the same bot. */
    public static final long INFO_INTERVAL_TICKS = 1200;
    static final int MAX_BOTS = 256;

    /** Why a hit is illegal. */
    public enum Reason {
        /** A block with a collision shape stands between the eye and every point of the victim a client could aim at. */
        BLOCKED,
        /** Vanilla's attack range of the weapon does not accept the victim's box: farther than the weapon's maximum range. */
        OUT_OF_REACH,
        /** Closer than the weapon's minimum range (a spear cannot jab something next to it, as in vanilla). */
        TOO_CLOSE
    }

    private static final class PerBot {
        long lastInfo = Long.MIN_VALUE;
        int sinceInfo;
        int sinceInfoBlocked;
        int sinceInfoReach;
        long total;
        long blocked;
        long reach;
        long close;
        double farthest;
    }

    private final Map<String, PerBot> bots = new HashMap<>();

    /**
     * Registers one veto.
     *
     * @param distance eye to nearest point of the victim's box, for the report
     * @param reach the range limit that was violated: the maximum reach for BLOCKED and OUT_OF_REACH, the minimum range for
     *              TOO_CLOSE
     * @return the INFO line to write now, or null (the veto is only counted)
     */
    public String veto(long now, String bot, Reason reason, String victim, double distance, double reach) {
        String key = bot.toLowerCase(Locale.ROOT);
        PerBot p = bots.get(key);
        if (p == null) {
            if (bots.size() >= MAX_BOTS) {
                bots.clear();
            }
            p = new PerBot();
            bots.put(key, p);
        }
        p.total++;
        p.sinceInfo++;
        if (reason == Reason.BLOCKED) {
            p.blocked++;
            p.sinceInfoBlocked++;
        } else {
            // Both range reasons are "outside its attack range"; TOO_CLOSE is also counted on its own.
            p.reach++;
            p.sinceInfoReach++;
            if (reason == Reason.TOO_CLOSE) {
                p.close++;
            }
        }
        if (reason != Reason.TOO_CLOSE) {
            p.farthest = Math.max(p.farthest, distance); // a too-close veto is the nearest, never the farthest
        }
        if (p.lastInfo != Long.MIN_VALUE && now >= p.lastInfo && now - p.lastInfo < INFO_INTERVAL_TICKS) {
            return null;
        }
        p.lastInfo = now;
        String line = String.format(Locale.ROOT,
                "melee legality: vetoed %d hit(s) by %s since the last line (%d without a clear line, %d outside its attack range); "
                        + "latest on %s at %.2f blocks (%s %.2f); %d vetoed by it in total",
                p.sinceInfo, bot, p.sinceInfoBlocked, p.sinceInfoReach, victim, distance,
                reason == Reason.TOO_CLOSE ? "too close, minimum range" : "reach", reach, p.total);
        p.sinceInfo = 0;
        p.sinceInfoBlocked = 0;
        p.sinceInfoReach = 0;
        return line;
    }

    /** A short diagnostic text about one bot, or null when nothing was vetoed for it. */
    public String describe(String bot) {
        PerBot p = bots.get(bot.toLowerCase(Locale.ROOT));
        if (p == null) {
            return null;
        }
        return String.format(Locale.ROOT, "vetoes %d (wall %d, reach %d, of which too close %d, farthest %.2f)", p.total,
                p.blocked, p.reach, p.close, p.farthest);
    }

    public void reset() {
        bots.clear();
    }
}
