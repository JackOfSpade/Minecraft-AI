package dev.spawnbotswrapper.inhabitants.adapter;

import java.util.ArrayList;
import java.util.List;

/**
 * The spawn decisions that need no Minecraft objects: which tiers to try in which order, and what a
 * spawn poll should conclude from what was observed this tick. Pure so that every branch is testable.
 * <p>
 * The poll rules exist because PvP BOT's own return value cannot be trusted (it says true even when
 * nothing spawned), because the entity appears asynchronously after HeroBot finishes a profile lookup,
 * and because PvP BOT drops a listed name whose entity stays absent for about 2.5 seconds: a bot that
 * arrives later exists but is no longer managed (the "orphan window"). Re-calling PvP BOT's spawn for an
 * ALREADY ONLINE name is its documented way to list that bot again.
 */
final class SpawnPolicy {

    /**
     * Ticks an online-but-unlisted bot entity is given to show up in PvP BOT's list before it is re-listed.
     * PvP BOT lists a name synchronously when spawning, so this only covers a same-tick race.
     */
    static final int RELIST_GRACE_TICKS = 5;
    /** Ticks after a re-list before "still not listed" is declared a failure. */
    static final int RELIST_VERIFY_TICKS = 10;

    private SpawnPolicy() {
    }

    /** Tiers to attempt in order for a backend preference and the members that exist. */
    static List<SpawnTier> tierOrder(SpawnBackend backend, boolean hasClassWithPos, boolean hasClass,
                                     boolean hasCommand) {
        SpawnBackend b = backend == null ? SpawnBackend.AUTO : backend;
        List<SpawnTier> order = new ArrayList<>(3);
        if (b != SpawnBackend.COMMAND) {
            if (hasClassWithPos) {
                order.add(SpawnTier.CLASS_POS);
            }
            if (hasClass) {
                order.add(SpawnTier.CLASS);
            }
        }
        if (b != SpawnBackend.CLASS && hasCommand) {
            order.add(SpawnTier.COMMAND);
        }
        return List.copyOf(order);
    }

    static SpawnTier primary(List<SpawnTier> order) {
        return order.isEmpty() ? SpawnTier.NONE : order.get(0);
    }

    /**
     * What was observed for one requested name.
     *
     * @param name            the requested name (for messages)
     * @param recordedFailure why the request was refused or could not be issued, or null
     * @param entityPresent   an online player with this name exists
     * @param entityIsBot     that player is a bot entity (meaningful only when present)
     * @param listed          PvP BOT lists the name
     * @param now             current server tick
     * @param entitySeenTick  tick when an online but unlisted bot entity was first noticed, or -1
     * @param relistAttempted whether the adopt call was already made
     * @param relistTick      tick of the adopt call (meaningful only when attempted)
     * @param canRelist       whether an adopt path exists (only the class API can adopt)
     */
    record PollInput(String name, String recordedFailure, boolean entityPresent, boolean entityIsBot,
                     boolean listed, long now, long entitySeenTick, boolean relistAttempted, long relistTick,
                     boolean canRelist) {
    }

    sealed interface Decision permits Decision.Pending, Decision.Ready, Decision.Relist, Decision.Failed {
        record Pending() implements Decision {
        }

        record Ready() implements Decision {
        }

        /** Call the adopt path now, then keep polling. */
        record Relist() implements Decision {
        }

        record Failed(String reason) implements Decision {
        }
    }

    static Decision decide(PollInput in) {
        if (in.recordedFailure() != null) {
            return new Decision.Failed(in.recordedFailure());
        }
        if (!in.entityPresent()) {
            // The engine owns the deadline: a slow profile lookup is indistinguishable from a refusal here.
            return new Decision.Pending();
        }
        if (!in.entityIsBot()) {
            return new Decision.Failed("the name '" + in.name() + "' is taken by a real player");
        }
        if (in.listed()) {
            return new Decision.Ready();
        }
        long seen = in.entitySeenTick() < 0 ? in.now() : in.entitySeenTick();
        if (in.now() - seen < RELIST_GRACE_TICKS) {
            return new Decision.Pending();
        }
        if (!in.relistAttempted()) {
            return in.canRelist()
                    ? new Decision.Relist()
                    : new Decision.Failed("bot '" + in.name() + "' is online but PvP BOT does not list it, and the "
                            + "COMMAND spawn tier cannot re-list it (PvP BOT's spawn command refuses existing players)");
        }
        if (in.now() - in.relistTick() < RELIST_VERIFY_TICKS) {
            return new Decision.Pending();
        }
        return new Decision.Failed("bot '" + in.name() + "' is online but PvP BOT still does not list it "
                + "after it was re-listed");
    }
}
