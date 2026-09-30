package dev.spawnbotswrapper.inhabitants.combat;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Notices hits on an inhabitant WITHOUT the Fabric damage event, which never fires for it: the bot mod's fake
 * player class re-implements the whole vanilla hurt routine, so the injection Fabric puts into the vanilla one is
 * never reached (that is why no "Combat taken:" line ever appeared for a bot). What the re-implementation does keep
 * doing is recording the last damage source on the entity, and the health drops; so, once per tick, the caller
 * hands in what it sees and this class says whether a NEW hit landed since the last tick and how much health it
 * cost. Pure: no Minecraft types (the damage source is an opaque identity token), no clock, no logger.
 * <p>
 * A hit is reported when the recorded damage source is a different object than last tick (each hit records a fresh
 * one, except for a few shared environmental types, which the second rule covers) or when the total (health plus
 * absorption) dropped while a source is recorded. It is NOT reported when the event already delivered that very
 * source ({@link #delivered}), so a bot class that does fire the event is never counted twice. Two hits inside one
 * tick are one report: the drop of the tick is their sum. Server thread only.
 */
public final class HitPoller {
    /** Health drops below this are float noise. */
    private static final float EPSILON = 0.001f;

    /** A hit: {@code damage} is the health (plus absorption) it cost; zero when it was blocked or fully absorbed. */
    public record Hit(float damage) {
        public boolean blocked() {
            return damage <= 0.0f;
        }
    }

    private static final class State {
        Object source;
        Object delivered;
        float total;
    }

    private final Map<UUID, State> states = new HashMap<>();

    /**
     * One observation of one inhabitant.
     *
     * @param source its recorded last damage source (identity token), or null when none is recent
     * @param total  health plus absorption now
     * @return the hit that landed since the previous call for this bot, or null. The first call for a bot only
     *         records the baseline.
     */
    public Hit observe(UUID bot, Object source, float total) {
        State st = states.get(bot);
        if (st == null) {
            st = new State();
            st.source = source;
            st.total = total;
            states.put(bot, st);
            return null;
        }
        float dropped = st.total - total;
        Hit hit = null;
        if (source != null && source != st.delivered && (source != st.source || dropped > EPSILON)) {
            hit = new Hit(Math.max(0.0f, dropped));
        }
        st.source = source;
        st.total = total;
        return hit;
    }

    /** The damage event delivered this hit itself: do not report the same source again. */
    public void delivered(UUID bot, Object source) {
        State st = states.get(bot);
        if (st != null) {
            st.delivered = source;
        }
    }

    /** Drops one bot. */
    public void forget(UUID bot) {
        states.remove(bot);
    }

    /** Drops every bot not in {@code keep}. */
    public void retainOnly(Set<UUID> keep) {
        for (Iterator<UUID> it = states.keySet().iterator(); it.hasNext(); ) {
            if (!keep.contains(it.next())) {
                it.remove();
            }
        }
    }

    public int tracked() {
        return states.size();
    }

    public void reset() {
        states.clear();
    }
}
