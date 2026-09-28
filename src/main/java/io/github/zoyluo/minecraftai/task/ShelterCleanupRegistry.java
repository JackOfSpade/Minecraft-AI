package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.task.EmergencyShelterTask.ExitDebt;
import io.github.zoyluo.minecraftai.task.EmergencyShelterTask.ShelterCleanupDebt;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * shelterdig-refactor-1: the world-global, cross-bot shelter cleanup/exit-debt registry, extracted
 * out of {@link EmergencyShelterTask} purely to shrink that file -- every member here operates
 * only on AIPlayerEntity/BlockPos/BlockState/UUID arguments, never on a shelter task's own instance
 * state. {@link EmergencyShelterTask#ExitDebt} and {@link EmergencyShelterTask#ShelterCleanupDebt}
 * stay nested inside {@code EmergencyShelterTask} (FollowTask and other callers outside this
 * package reference them as {@code EmergencyShelterTask.ExitDebt} /
 * {@code EmergencyShelterTask.ShelterCleanupDebt}, and their construction needs private access to
 * that class's own state); only the maps and the pure claim/registry logic that key off them move
 * here. EmergencyShelterTask keeps its existing package-private/public API as thin delegators to
 * this class, so ShelterCleanupTask/FollowTask/DangerWatcher/RuntimeLifecycleCoordinator callers
 * need no change.
 */
final class ShelterCleanupRegistry {
    private ShelterCleanupRegistry() {
    }

    /**
     * A new player instruction is allowed to cancel a shelter, but must not strand the bot inside
     * a shell it just built.  The next follow task consumes this narrowly-scoped debt; it can only
     * remove blocks whose exact state was placed by this shelter in the same dimension.
     */
    private static final Map<UUID, ExitDebt> ABANDONED_EXIT_DEBTS = new ConcurrentHashMap<>();
    /**
     * A completed shelter leaves a short-lived world artifact.  This registry is deliberately
     * exact-state ownership rather than a geometric "tear down anything near the anchor" rule:
     * cleanup may mine only a block state actually placed by one of our shelters.
     */
    private static final Map<UUID, ShelterCleanupDebt> PENDING_CLEANUPS = new ConcurrentHashMap<>();
    static final int CLEANUP_CLAIM_LEASE_TICKS = 100;
    /**
     * A cleanup debt is world-global (any idle bot in the same dimension may eventually claim it),
     * but with no distance bound a bot on the far side of a large world would be scheduled a
     * background chore for a shelter it has no practical reason to travel to. Bounding this also
     * keeps unrelated, far-apart concurrent test fixtures from cross-contaminating each other.
     */
    static final double CLEANUP_MAX_DISTANCE = 128.0D;
    /**
     * The largest @GameTest(maxTicks=...) value anywhere in src/gametest bounds how long a single
     * GameTest structure can remain alive. registerCleanupDebt() is only ever called once per
     * shelter, inside that structure's own lifetime, using the JVM-global server tick counter that
     * every concurrently-scheduled GameTest batch shares. So while the registering test is still
     * running, the age of its debt can never exceed that test's own maxTicks. A debt older than
     * this, regardless of distance, is therefore unambiguously left behind by an already-finished,
     * unrelated test and must be pruned outright -- this closes the one case that slips through
     * CLEANUP_MAX_DISTANCE alone (two unrelated fixtures placed within 128 blocks of each other in
     * a dense batch). This reasoning is specific to the GameTest harness (many short-lived
     * structures sharing one world); a real server has no such "unrelated fixture" to leak from, so
     * pruning by this age is gated to the gametest-only mod id below and never applies in production
     * -- a legitimate shelter debt on a long-running server must stay claimable indefinitely.
     *
     * 20,000 is a fixed safety margin above the largest maxTicks actually used in src/gametest
     * today (16,000 as of this writing, verified by {@code grep -rhoE "maxTicks\s*=\s*[0-9]+"
     * src/gametest}), not a value recomputed from it -- nothing keeps the two in sync, so
     * EmergencyShelterAgeMaxTicksSourceContractTest pins that every @GameTest's maxTicks stays
     * below this constant, and will fail loudly (instead of silently pruning an active test's
     * debt) the day some fixture needs a longer window.
     */
    static final int CLEANUP_MAX_AGE_TICKS = 20_000;
    static final boolean AGE_PRUNING_ENABLED =
            FabricLoader.getInstance().isModLoaded("minecraftai-gametest");

    static Optional<ExitDebt> pendingExitDebt(AIPlayerEntity bot) {
        return Optional.ofNullable(ABANDONED_EXIT_DEBTS.get(bot.getUuid()));
    }

    static void clearExitDebt(AIPlayerEntity bot, ExitDebt debt) {
        if (debt != null) {
            ABANDONED_EXIT_DEBTS.remove(bot.getUuid(), debt);
        }
    }

    /**
     * Called only after FollowTask has physically stepped through a cancelled shelter's owned
     * doorway.  Before that point a cleanup worker could legally prove the blocks were ours yet
     * still leave the cancelled bot boxed in, so an ExitDebt is intentionally not cleanup work.
     */
    static void promoteExitDebtForCleanup(AIPlayerEntity bot, ExitDebt debt) {
        if (debt == null || !debt.matchesDimension(bot)) {
            return;
        }
        registerCleanupDebt(bot, debt.anchor(), debt.ownedPlacements);
    }

    /** True when this world has at least one exact-state bot-owned shelter artifact to remove
     *  within a reasonable travel distance of this bot, that this bot could actually claim right
     *  now. This must mirror {@link #claimPendingCleanup}'s own eligibility exactly (including the
     *  claim-lease check): a debt already leased to a different bot is not "pending" for this one.
     *  Reporting true for a debt this bot cannot claim used to make DangerWatcher dispatch a
     *  {@code ShelterCleanupTask} that immediately discovered nothing claimable and completed as a
     *  no-op -- burning that bot's one scan-priority slot on housekeeping instead of falling through
     *  to resume its own paused mission work in the same scan. */
    static boolean hasPendingCleanup(AIPlayerEntity bot) {
        synchronized (PENDING_CLEANUPS) {
            pruneInvalidCleanupDebts(bot);
            int now = bot.getEntityWorld().getServer().getTicks();
            return PENDING_CLEANUPS.values().stream()
                    .anyMatch(debt -> debt.matchesDimension(bot) && debt.isWithinReasonableRange(bot)
                            && debt.claimAvailableTo(bot.getUuid(), now));
        }
    }

    /**
     * Claims the nearest eligible artifact in this dimension, within a reasonable travel distance.
     * Claims are leased, not permanent, so an interrupted owner does not prevent a nearby idle bot
     * from helping later.
     */
    static Optional<ShelterCleanupDebt> claimPendingCleanup(AIPlayerEntity bot) {
        synchronized (PENDING_CLEANUPS) {
            pruneInvalidCleanupDebts(bot);
            int now = bot.getEntityWorld().getServer().getTicks();
            ShelterCleanupDebt selected = PENDING_CLEANUPS.values().stream()
                    .filter(debt -> debt.matchesDimension(bot) && debt.isWithinReasonableRange(bot))
                    .filter(debt -> debt.claimAvailableTo(bot.getUuid(), now))
                    .min(Comparator.comparingDouble(debt ->
                            debt.anchor().getSquaredDistance(bot.getBlockPos())))
                    .orElse(null);
            if (selected == null) {
                return Optional.empty();
            }
            selected.claim(bot.getUuid(), now);
            return Optional.of(selected);
        }
    }

    static boolean renewCleanupClaim(AIPlayerEntity bot, ShelterCleanupDebt debt) {
        if (debt == null) {
            return false;
        }
        synchronized (PENDING_CLEANUPS) {
            ShelterCleanupDebt current = PENDING_CLEANUPS.get(debt.id());
            if (current != debt || !debt.matchesDimension(bot)
                    || !debt.claimedBy(bot.getUuid())) {
                return false;
            }
            debt.claim(bot.getUuid(), bot.getEntityWorld().getServer().getTicks());
            return true;
        }
    }

    /** Returns a still-owned block; mismatched/replaced blocks are discarded without mining. */
    static Optional<BlockPos> nextCleanupBlock(AIPlayerEntity bot,
                                               ShelterCleanupDebt debt,
                                               Set<BlockPos> excluded) {
        if (debt == null) {
            return Optional.empty();
        }
        synchronized (PENDING_CLEANUPS) {
            ShelterCleanupDebt current = PENDING_CLEANUPS.get(debt.id());
            if (current != debt || !debt.matchesDimension(bot)
                    || !debt.claimedBy(bot.getUuid())) {
                return Optional.empty();
            }
            debt.discardChangedBlocks(bot);
            if (debt.remaining.isEmpty()) {
                PENDING_CLEANUPS.remove(debt.id(), debt);
                return Optional.empty();
            }
            return debt.remaining.keySet().stream()
                    .filter(position -> excluded == null || !excluded.contains(position))
                    .min(Comparator.comparingDouble(position ->
                            bot.getEyePos().squaredDistanceTo(position.toCenterPos())))
                    .map(BlockPos::toImmutable);
        }
    }

    static boolean ownsCleanupBlock(AIPlayerEntity bot,
                                    ShelterCleanupDebt debt,
                                    BlockPos position) {
        if (debt == null || position == null) {
            return false;
        }
        synchronized (PENDING_CLEANUPS) {
            return ObservableWorldQuery.canObserveBlock(bot, position)
                    && PENDING_CLEANUPS.get(debt.id()) == debt
                    && debt.matchesDimension(bot)
                    && debt.claimedBy(bot.getUuid())
                    && debt.ownsCurrentPlacement(bot, position);
        }
    }

    /** Marks a block settled only after the world no longer equals its recorded owned state. */
    static void settleCleanupBlock(AIPlayerEntity bot, ShelterCleanupDebt debt, BlockPos position) {
        if (debt == null || position == null) {
            return;
        }
        synchronized (PENDING_CLEANUPS) {
            ShelterCleanupDebt current = PENDING_CLEANUPS.get(debt.id());
            if (current != debt || !debt.claimedBy(bot.getUuid())) {
                return;
            }
            BlockState expected = debt.remaining.get(position);
            if (expected != null
                    && ObservableWorldQuery.canObserveBlock(bot, position)
                    && !expected.equals(bot.getEntityWorld().getBlockState(position))) {
                debt.remaining.remove(position);
            }
            debt.discardChangedBlocks(bot);
            if (debt.remaining.isEmpty()) {
                PENDING_CLEANUPS.remove(debt.id(), debt);
            }
        }
    }

    static void releaseCleanupClaim(AIPlayerEntity bot, ShelterCleanupDebt debt) {
        if (debt == null) {
            return;
        }
        synchronized (PENDING_CLEANUPS) {
            if (PENDING_CLEANUPS.get(debt.id()) == debt && debt.claimedBy(bot.getUuid())) {
                debt.release(bot.getUuid());
            }
        }
    }

    /**
     * Explicit despawn (GameTest's end-of-test teardown, and the equivalent production command)
     * permanently removes this bot: it will never return to finish or hand off its own shelter
     * debt. In production the placed blocks are still real and worth leaving for another bot to
     * claim, so this only runs under the GameTest harness ({@link #AGE_PRUNING_ENABLED}).
     * There it closes a gap {@link #CLEANUP_MAX_AGE_TICKS} cannot: two unrelated fixtures placed
     * close together in the same batch, tested back-to-back, produce a debt only moments old --
     * far too young to be pruned by age -- yet within {@link #CLEANUP_MAX_DISTANCE} of the very
     * next bot spawned. Forgetting it at despawn removes the leak at its source instead of relying
     * on distance/age to reject it downstream, which is what let it slip into some other test's
     * DangerWatcher scan as a phantom "shelter_cleanup" task.
     */
    public static void forgetCleanupDebtsOwnedBy(AIPlayerEntity bot) {
        if (!AGE_PRUNING_ENABLED) {
            return;
        }
        UUID uuid = bot.getUuid();
        synchronized (PENDING_CLEANUPS) {
            PENDING_CLEANUPS.values().removeIf(debt -> debt.owner.equals(uuid));
        }
    }

    private static void pruneInvalidCleanupDebts(AIPlayerEntity bot) {
        int now = bot.getEntityWorld().getServer().getTicks();
        for (Map.Entry<UUID, ShelterCleanupDebt> entry : PENDING_CLEANUPS.entrySet()) {
            ShelterCleanupDebt debt = entry.getValue();
            if (debt.isStale(now)) {
                // Older than any single GameTest's own maxTicks budget: definitely a leaked
                // artifact from an already-finished, unrelated test. Prune regardless of distance
                // or dimension -- this is what the 128-block gate alone could not catch.
                PENDING_CLEANUPS.remove(entry.getKey(), debt);
                continue;
            }
            if (!debt.matchesDimension(bot)) {
                continue;
            }
            debt.discardChangedBlocks(bot);
            if (debt.remaining.isEmpty()) {
                PENDING_CLEANUPS.remove(entry.getKey(), debt);
            }
        }
    }

    /**
     * Records an exit debt for a cancelled shelter's owned doorway. Split out from
     * {@code EmergencyShelterTask.preserveOwnedExitDebt} only because {@link #ABANDONED_EXIT_DEBTS}
     * lives here now; the debt itself is still built by, and remains specific to, that instance
     * method.
     */
    static void recordExitDebt(AIPlayerEntity bot, ExitDebt debt) {
        ABANDONED_EXIT_DEBTS.put(bot.getUuid(), debt);
    }

    static void registerCleanupDebt(AIPlayerEntity bot,
                                    BlockPos anchor,
                                    Map<BlockPos, BlockState> placements) {
        if (anchor == null || placements == null || placements.isEmpty()) {
            return;
        }
        Map<BlockPos, BlockState> exactOwned = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : placements.entrySet()) {
            BlockPos position = entry.getKey();
            BlockState expected = entry.getValue();
            if (position != null && expected != null
                    && expected.equals(bot.getEntityWorld().getBlockState(position))) {
                exactOwned.put(position.toImmutable(), expected);
            }
        }
        if (exactOwned.isEmpty()) {
            return;
        }
        ShelterCleanupDebt debt = new ShelterCleanupDebt(
                UUID.randomUUID(),
                bot.getUuid(),
                bot.getEntityWorld().getRegistryKey().getValue().toString(),
                anchor,
                exactOwned,
                bot.getEntityWorld().getServer().getTicks());
        PENDING_CLEANUPS.put(debt.id(), debt);
        BotLog.action(bot, "shelter_cleanup_registered",
                "anchor", anchor,
                "owned", exactOwned.size(),
                "owner", bot.getGameProfile().name());
    }
}
