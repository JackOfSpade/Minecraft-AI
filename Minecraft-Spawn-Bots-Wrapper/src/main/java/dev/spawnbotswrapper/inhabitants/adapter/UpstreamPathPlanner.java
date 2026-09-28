package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure translation of a {@link BotProfile.Behavior} into the parameters of a PvP BOT path. No Minecraft,
 * no reflection: everything that decides what gets built lives here so it can be tested exhaustively.
 * <p>
 * PvP BOT's naming is inverted relative to intuition: its {@code loop=true} means PING-PONG (reverse at the
 * ends) and {@code loop=false} means RING (restart at the first point). Stance mapping:
 * <ul>
 *   <li>GUARD_POST: one point, loop=false (after a fight the bot walks back to it, then simply stays)</li>
 *   <li>PATROL_CYCLE: loop=false, two or more points</li>
 *   <li>PATROL_PINGPONG: loop=true, two or more points</li>
 * </ul>
 * A cycle or ping-pong that only has ONE usable point is downgraded to a guard post instead of being
 * refused: refusing would leave the bot with no path at all, and a pacifist ({@code combatant=false})
 * would silently turn into a fighter.
 */
final class UpstreamPathPlanner {

    /** Reserved namespace of every path this addon creates; also how leftovers of a past session are recognised. */
    static final String PATH_PREFIX = "inh_";
    /** Upstream stores paths by name in a JSON file and lists them in chat; keep names short. */
    static final int MAX_PATH_NAME = 40;
    /** No stance needs more; upstream rewrites its whole paths file on every point added. */
    static final int MAX_WAYPOINTS = 64;
    static final String DEFAULT_WALK_TYPE = BotProfile.WalkType.BHOP;

    private UpstreamPathPlanner() {
    }

    /** {@code plan} is null exactly when {@code rejection} is set. */
    record Outcome(PatrolPlan plan, String rejection) {
        static Outcome accepted(PatrolPlan plan) {
            return new Outcome(plan, null);
        }

        static Outcome rejected(String reason) {
            return new Outcome(null, reason);
        }
    }

    /**
     * {@code inh_} plus the lower-cased bot name with every character outside {@code [a-z0-9_]} replaced by
     * an underscore, at most {@link #MAX_PATH_NAME} characters. Null for a null or blank name.
     */
    static String pathName(String botName) {
        if (botName == null || botName.isBlank()) {
            return null;
        }
        String lower = botName.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(PATH_PREFIX);
        for (int i = 0; i < lower.length() && sb.length() < MAX_PATH_NAME; i++) {
            char c = lower.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    /** Normalises a walk type to bhop, sprint or walk; anything else falls back to upstream's default. */
    static String walkType(String value) {
        if (value != null) {
            String v = value.trim().toLowerCase(Locale.ROOT);
            if (v.equals(BotProfile.WalkType.BHOP) || v.equals(BotProfile.WalkType.SPRINT)
                    || v.equals(BotProfile.WalkType.WALK)) {
                return v;
            }
        }
        return DEFAULT_WALK_TYPE;
    }

    static boolean isFinite(BotProfile.Waypoint w) {
        return w != null && Double.isFinite(w.x()) && Double.isFinite(w.y()) && Double.isFinite(w.z());
    }

    static Outcome plan(String botName, BotProfile.Behavior behavior) {
        if (behavior == null) {
            return Outcome.rejected("no behaviour given");
        }
        if (!behavior.usesPath()) {
            return Outcome.rejected("stance " + behavior.stance() + " does not use a path");
        }
        String pathName = pathName(botName);
        if (pathName == null) {
            return Outcome.rejected("the bot name cannot be turned into a path name");
        }
        List<BotProfile.Waypoint> given = behavior.waypoints();
        if (given.isEmpty()) {
            return Outcome.rejected("stance " + behavior.stance() + " has no waypoints");
        }
        if (given.size() > MAX_WAYPOINTS) {
            return Outcome.rejected(given.size() + " waypoints exceed the limit of " + MAX_WAYPOINTS);
        }
        for (int i = 0; i < given.size(); i++) {
            if (!isFinite(given.get(i))) {
                return Outcome.rejected("waypoint #" + i + " is missing or not a finite position");
            }
        }

        List<String> notes = new ArrayList<>();
        String walkType = walkType(behavior.walkType());
        if (behavior.walkType() != null && !walkType.equals(behavior.walkType().trim().toLowerCase(Locale.ROOT))) {
            notes.add("unknown walk type '" + behavior.walkType() + "', using " + walkType);
        }

        List<BotProfile.Waypoint> points;
        boolean loop;
        switch (behavior.stance()) {
            case BotProfile.Stance.GUARD_POST -> {
                points = List.of(given.get(0));
                loop = false;
                if (given.size() > 1) {
                    notes.add("guard post uses only the first of " + given.size() + " waypoints");
                }
            }
            case BotProfile.Stance.PATROL_CYCLE -> {
                points = given;
                loop = false;
                if (given.size() < 2) {
                    notes.add("a cycle needs 2+ waypoints; using a guard post");
                }
            }
            case BotProfile.Stance.PATROL_PINGPONG -> {
                points = given;
                loop = given.size() >= 2;
                if (!loop) {
                    notes.add("a ping-pong loop with one waypoint crashes PvP BOT (trap P1); using a guard post");
                }
            }
            default -> {
                return Outcome.rejected("unknown stance '" + behavior.stance() + "'");
            }
        }
        return Outcome.accepted(new PatrolPlan(pathName, points, loop, behavior.combatant(), walkType, notes));
    }
}
