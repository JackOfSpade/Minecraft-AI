package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

/**
 * Builds, tracks and tears down the PvP BOT paths this addon creates for its bots. It owns the one piece
 * of state the adapter needs for patrols: WHICH paths are ours, so that nothing ever touches a path (or a
 * follower) somebody else created.
 * <p>
 * Ordering rules that come from upstream's path traps:
 * <ul>
 *   <li>the whole path is built BEFORE the bot starts following it; editing a path that is followed can
 *       leave the follower pointing past the end (trap P2) and crash the tick;</li>
 *   <li>a ping-pong loop with fewer than two points crashes the tick (trap P1); {@link PatrolPlan} cannot
 *       represent it, so it cannot reach upstream from here;</li>
 *   <li>a follower whose path was deleted silently stops fighting and moving (trap P3), so every deletion goes
 *       through {@link #clear}, which also stops the follower.</li>
 * </ul>
 * Server thread only, like everything that touches PvP BOT's unsynchronised statics.
 */
final class PatrolManager {

    /** A path we created and the exact spelling of the bot name it was started for (upstream is case-sensitive). */
    record Owned(String path, String bot) {
    }

    private final Map<String, Owned> owned = new HashMap<>();
    private final Diagnostics log;

    PatrolManager(Diagnostics log) {
        this.log = log;
    }

    /** Names of the paths this manager currently considers its own (tests and diagnostics). */
    List<String> ownedPaths() {
        List<String> out = new ArrayList<>();
        for (Owned o : owned.values()) {
            out.add(o.path());
        }
        return out;
    }

    /**
     * Creates the path described by {@code plan} and makes {@code bot} follow it. Returns true only when
     * the bot is following. On any failure nothing is left behind: a half-built path is deleted again.
     *
     * @param bot the bot's name spelled exactly as PvP BOT lists it
     */
    boolean assign(UpstreamCalls calls, String bot, PatrolPlan plan) {
        if (calls == null || bot == null || plan == null) {
            return false;
        }
        String key = NameRules.key(bot);
        String path = plan.pathName();
        owned.remove(key);
        boolean created = false;
        try {
            // A path of this name left by an earlier session (upstream persists paths, not followers) or by
            // an earlier assignment: it lives in our reserved namespace, so replacing it is ours to do.
            if (calls.pathExists(path) && !calls.deletePath(path)) {
                return refuse(bot, "upstream would not delete the stale path " + path);
            }
            if (!calls.createPath(path)) {
                return refuse(bot, "upstream refused to create path " + path);
            }
            created = true;
            List<BotProfile.Waypoint> points = plan.points();
            for (int i = 0; i < points.size(); i++) {
                BotProfile.Waypoint w = points.get(i);
                if (!calls.addPoint(path, new Vec3(w.x(), w.y(), w.z()))) {
                    return rollback(calls, bot, path, "upstream refused waypoint #" + i);
                }
            }
            if (!calls.setLoop(path, plan.loop())) {
                return rollback(calls, bot, path, "upstream refused the loop flag");
            }
            if (!calls.setAttack(path, plan.attack())) {
                return rollback(calls, bot, path, "upstream refused the attack flag");
            }
            if (!calls.setWalkType(path, plan.walkType())) {
                return rollback(calls, bot, path, "upstream refused the walk type " + plan.walkType());
            }
            // Only now, with the path complete, does anything start walking it.
            if (!calls.startFollowing(bot, path)) {
                return rollback(calls, bot, path, "upstream refused to start following");
            }
            owned.put(key, new Owned(path, bot));
            for (String note : plan.notes()) {
                log.debugOnce("patrol-note|" + note, "patrol plan for " + bot + ": " + note);
            }
            return true;
        } catch (Throwable t) {
            log.failure("assigning a patrol to " + bot, t);
            if (created) {
                quietDelete(calls, path);
            }
            return false;
        }
    }

    /**
     * Stops the bot following, deletes its path and clears its stale navigation state; each step guarded on
     * its own so one failing step never blocks the others. Idempotent. Only a path this manager created, or
     * a leftover in our reserved {@code inh_} namespace for this very bot, is ever touched.
     */
    void clear(UpstreamCalls calls, String botName) {
        String key = NameRules.key(botName);
        Owned mine = owned.remove(key);
        if (calls == null || botName == null) {
            return;
        }
        String path = mine != null ? mine.path() : null;
        String bot = mine != null ? mine.bot() : botName;
        boolean leftover = false;
        if (path == null) {
            String candidate = UpstreamPathPlanner.pathName(botName);
            try {
                if (candidate != null && calls.pathExists(candidate)) {
                    path = candidate;
                    leftover = true;
                }
            } catch (Throwable t) {
                log.failure("looking for a leftover path of " + botName, t);
            }
        }
        if (path == null) {
            return;
        }

        try {
            Boolean following = calls.isFollowing(bot, path);
            // Never stop a follower that is on somebody else's path. When upstream cannot tell us, a bot whose
            // path we created is ours to stop; a leftover's followers go with the path when it is deleted.
            boolean stop = following == null ? !leftover : following;
            if (stop) {
                calls.stopFollowing(bot);
            }
        } catch (Throwable t) {
            log.failure("stopping the patrol of " + bot, t);
        }
        try {
            calls.deletePath(path);
        } catch (Throwable t) {
            log.failure("deleting path " + path, t);
        }
        try {
            calls.removeNavigationState(bot);
        } catch (Throwable t) {
            log.failure("clearing the navigation state of " + bot, t);
        }
    }

    /** True when the bot follows a path this manager created. */
    boolean isPatrolling(UpstreamCalls calls, String botName) {
        Owned mine = owned.get(NameRules.key(botName));
        if (calls == null || mine == null) {
            return false;
        }
        try {
            Boolean following = calls.isFollowing(mine.bot(), mine.path());
            return following != null ? following : calls.pathExists(mine.path());
        } catch (Throwable t) {
            log.failure("checking the patrol of " + botName, t);
            return false;
        }
    }

    /** Releases every path this manager created (shutdown / world switch). Returns how many were tracked. */
    int releaseAll(UpstreamCalls calls) {
        List<Owned> all = new ArrayList<>(owned.values());
        for (Owned o : all) {
            clear(calls, o.bot());
        }
        owned.clear();
        return all.size();
    }

    /** Forgets tracking without touching upstream (its state is gone or unreachable). */
    void forgetAll() {
        owned.clear();
    }

    private boolean refuse(String bot, String reason) {
        log.debugOnce("patrol-refused|" + reason, "no patrol for " + bot + ": " + reason);
        return false;
    }

    private boolean rollback(UpstreamCalls calls, String bot, String path, String reason) {
        quietDelete(calls, path);
        return refuse(bot, reason);
    }

    private void quietDelete(UpstreamCalls calls, String path) {
        try {
            calls.deletePath(path);
        } catch (Throwable t) {
            log.failure("removing the half-built path " + path, t);
        }
    }
}
