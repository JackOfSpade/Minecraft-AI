package dev.spawnbotswrapper.inhabitants.combat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure logic of the "ranged loop" diagnostic: a bot that keeps starting to draw a bow or load a crossbow and stops
 * before anything is shot (the hostile bot on the land-locked ship that "starts to reload, stops half-way, starts
 * again and never attacks"). Fed one {@link Sample} per bot per server tick by the Minecraft side; it tracks the
 * item-use transitions, classifies each finished draw as completed or aborted, and raises an {@link Alert} when
 * {@value #THRESHOLD} or more draws were aborted inside {@value #WINDOW_TICKS} ticks, at most once per
 * {@value #ALERT_COOLDOWN_TICKS} ticks per bot. It only observes: nothing here can change a bot.
 * <p>
 * A draw is COMPLETED when an arrow (or other projectile) owned by the bot spawned at or after the draw started
 * ({@link Sample#lastShotTick}, strictly later than the start), or when a crossbow ended the draw loaded. Anything else is ABORTED, and the alert
 * records what changed when it stopped (hotbar slot, main-hand item, line of sight, distance) so the log says why.
 * No Minecraft, no clock, no logger. Server thread only.
 * <p>
 * Limit: samples are taken once per server tick (END_SERVER_TICK), so a transition that begins and ends inside one tick is
 * invisible. A draw aborted after 0 or 1 tick and restarted at once is only seen as a restart when the use ticks of the
 * new sample are lower than the previous sample's; a stop and restart that leaves the use ticks equal or higher between two
 * samples is not counted. The diagnostic is a hint for the log, not a guarantee.
 */
public final class RangedCycleDetector {
    public static final int WINDOW_TICKS = 200;
    public static final int THRESHOLD = 3;
    public static final int ALERT_COOLDOWN_TICKS = 600;

    /**
     * One observation of one bot.
     *
     * @param tick            server tick
     * @param usingRanged     the bot is using a bow or crossbow right now
     * @param usedItem        the item in use ("bow", "crossbow"), or "none"
     * @param useTicks        ticks the current use has run (a drop between samples means it was stopped and restarted)
     * @param crossbowCharged a crossbow in either hand is loaded
     * @param slot            selected hotbar slot
     * @param mainHand        main-hand item name
     * @param lastShotTick    tick a projectile owned by this bot last spawned, or -1 for never
     * @param distance        blocks to the nearest real player; negative when unknown
     * @param lineOfSight     line of sight to that player; null when not measured
     * @param slotAtTickStart selected hotbar slot at the START of this server tick (before anything ran), or -1 when
     *                        not sampled; a value that differs from the slot the draw started in, while {@code slot}
     *                        (the END of the tick) is back, shows something moved the selection away from the ranged
     *                        weapon and back inside the tick
     */
    public record Sample(long tick, boolean usingRanged, String usedItem, int useTicks, boolean crossbowCharged, int slot,
                         String mainHand, long lastShotTick, double distance, Boolean lineOfSight, int slotAtTickStart) {
        /** A sample without the start-of-tick slot (not sampled). */
        public Sample(long tick, boolean usingRanged, String usedItem, int useTicks, boolean crossbowCharged, int slot,
                      String mainHand, long lastShotTick, double distance, Boolean lineOfSight) {
            this(tick, usingRanged, usedItem, useTicks, crossbowCharged, slot, mainHand, lastShotTick, distance,
                    lineOfSight, -1);
        }
    }

    /** One draw that ended without a shot. */
    public record Aborted(long startTick, long endTick, String item, String why) {
        public long ticks() {
            return endTick - startTick;
        }
    }

    /** @param count aborted draws inside the window (at least {@link #THRESHOLD}); {@code draws} are those draws */
    public record Alert(String bot, int count, List<Aborted> draws) {
    }

    private static final class Active {
        final long startTick;
        final String item;
        final int slot;
        final String mainHand;
        final double distance;
        final Boolean lineOfSight;
        int lastUseTicks;

        Active(Sample s) {
            this.startTick = s.tick();
            this.item = s.usedItem();
            this.slot = s.slot();
            this.mainHand = s.mainHand();
            this.distance = s.distance();
            this.lineOfSight = s.lineOfSight();
            this.lastUseTicks = s.useTicks();
        }
    }

    private static final class State {
        long lastTick = Long.MIN_VALUE;
        Active active;
        final Deque<Aborted> aborted = new ArrayDeque<>();
        long lastAlert = Long.MIN_VALUE;
    }

    private final Map<String, State> bots = new HashMap<>();

    /** Feeds one observation; returns an alert when the loop threshold was just crossed and the bot is not cooling down. */
    public Alert observe(String bot, Sample s) {
        State st = bots.computeIfAbsent(bot, k -> new State());
        if (s.tick() < st.lastTick) { // the server clock restarted: nothing old is comparable
            st.active = null;
            st.aborted.clear();
            st.lastAlert = Long.MIN_VALUE;
        }
        st.lastTick = s.tick();
        boolean newAbort = false;
        Active a = st.active;
        boolean restarted = a != null && s.usingRanged() && a.item.equals(s.usedItem()) && s.useTicks() < a.lastUseTicks;
        if (a != null && !restarted) {
            a.lastUseTicks = s.useTicks();
        }
        if (a != null && (restarted || !s.usingRanged() || !a.item.equals(s.usedItem()))) {
            st.active = null;
            if (!completed(a, s)) {
                st.aborted.addLast(new Aborted(a.startTick, s.tick(), a.item, whatChanged(a, s)));
                newAbort = true;
            }
        }
        if (s.usingRanged() && st.active == null) {
            st.active = new Active(s);
        }
        while (!st.aborted.isEmpty() && s.tick() - st.aborted.peekFirst().endTick() > WINDOW_TICKS) {
            st.aborted.removeFirst();
        }
        if (newAbort && st.aborted.size() >= THRESHOLD
                && (st.lastAlert == Long.MIN_VALUE || s.tick() - st.lastAlert >= ALERT_COOLDOWN_TICKS)) {
            st.lastAlert = s.tick();
            return new Alert(bot, st.aborted.size(), List.copyOf(new ArrayList<>(st.aborted)));
        }
        return null;
    }

    /** True when this bot has a draw in progress or aborted draws still inside the window (the caller may skip idle bots). */
    public boolean tracking(String bot) {
        State st = bots.get(bot);
        return st != null && (st.active != null || !st.aborted.isEmpty());
    }

    /** Drops one bot (out of range, left, died): its half-finished draw is not an abort. */
    public void forget(String bot) {
        bots.remove(bot);
    }

    /** Drops every bot not in {@code keep}. */
    public void retainOnly(Set<String> keep) {
        for (Iterator<String> it = bots.keySet().iterator(); it.hasNext(); ) {
            if (!keep.contains(it.next())) {
                it.remove();
            }
        }
    }

    public void reset() {
        bots.clear();
    }

    public int trackedBots() {
        return bots.size();
    }

    private static boolean completed(Active a, Sample end) {
        // Strictly after the start: a shot released in the very tick a new draw began belongs to the PREVIOUS draw (a real shot
        // of this draw comes at least a charge time later), so a same-tick restart does not inherit it.
        if (end.lastShotTick() > a.startTick) {
            return true;
        }
        return a.item.contains("crossbow") && end.crossbowCharged() && end.slot() == a.slot;
    }

    private static String whatChanged(Active a, Sample s) {
        List<String> parts = new ArrayList<>();
        if (s.slot() != a.slot) {
            parts.add("slot switch " + a.slot + "->" + s.slot());
        }
        if (s.slotAtTickStart() >= 0 && s.slotAtTickStart() != a.slot) {
            parts.add("selected slot left the ranged weapon inside the tick (slot " + a.slot + " at the draw's start, "
                    + s.slotAtTickStart() + " at the start of this tick, " + s.slot() + " at its end; likely PvP BOT's "
                    + "weapon auto-equip)");
        }
        if (!a.mainHand.equals(s.mainHand())) {
            parts.add("main hand " + a.mainHand + "->" + s.mainHand());
        }
        if (s.usingRanged() && !a.item.equals(s.usedItem())) {
            parts.add("switched to using " + s.usedItem());
        }
        if (s.usingRanged() && a.item.equals(s.usedItem())) {
            parts.add("use restarted");
        }
        if (Boolean.TRUE.equals(a.lineOfSight) && Boolean.FALSE.equals(s.lineOfSight())) {
            parts.add("line of sight lost");
        }
        if (a.distance >= 0 && s.distance() >= 0 && Math.abs(a.distance - s.distance()) >= 2.0) {
            parts.add(String.format(Locale.ROOT, "target distance %.1f->%.1f", a.distance, s.distance()));
        }
        if (s.distance() < 0 && a.distance >= 0) {
            parts.add("target lost");
        }
        return parts.isEmpty() ? "stopped with nothing else changed" : String.join(", ", parts);
    }

    /**
     * {@code ranged loop: Bob aborted 3 bow/crossbow draws in 10 s | draws: crossbow 12t (slot switch 2->0), ... |
     * state: <snapshot>}.
     */
    public static String line(Alert alert, String snapshot) {
        StringBuilder sb = new StringBuilder("ranged loop: ").append(alert.bot()).append(" aborted ")
                .append(alert.count()).append(" bow/crossbow draws in ").append(WINDOW_TICKS / 20).append(" s | draws: ");
        boolean first = true;
        for (Aborted d : alert.draws()) {
            if (!first) {
                sb.append("; ");
            }
            first = false;
            sb.append(d.item()).append(" started t").append(d.startTick()).append(" lasted ").append(d.ticks())
                    .append(" ticks, ended: ").append(d.why());
        }
        Aborted prev = null;
        StringBuilder gaps = new StringBuilder();
        for (Aborted d : alert.draws()) {
            if (prev != null) {
                gaps.append(gaps.length() == 0 ? "" : ",").append(d.startTick() - prev.endTick());
            }
            prev = d;
        }
        if (gaps.length() > 0) {
            sb.append(" | ticks between a stop and the next start: ").append(gaps);
        }
        if (snapshot != null) {
            sb.append(" | state: ").append(snapshot);
        }
        return sb.toString();
    }
}
