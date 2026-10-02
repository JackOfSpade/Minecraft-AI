package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.RecentDamage;
import io.github.zoyluo.minecraftai.log.BotLog;
import java.util.function.Predicate;

/**
 * The one walk/sprint/sneak policy of controller-driven travel (a bounded local action or a Baritone route). Given what is going on
 * it names the {@link Gait} of this tick; the enforcers ({@code ActionPack.onUpdate} for local actions and
 * {@code BotInputBridge.apply} for Baritone) then write it into the bot, after the vanilla rules ({@link PaceRules}).
 *
 * <p>Order of the rules (the first that applies decides; the ceilings are applied to whatever came out):</p>
 * <ol>
 *   <li><b>A warden hunts the bot or its owner</b> (observed): SPRINT.</li>
 *   <li><b>Aggro pressure</b> while travelling (the {@linkplain #setPressureProbe pressure probe}, wired to AggroSense): SPRINT,
 *       unless a WARDEN-owned lease decides the pace (the warden logic knows better).</li>
 *   <li><b>A lease</b> ({@link ActionPack#requestPace}, {@link ActionPack#requestRoutePace}): the highest-priority one. A lease is
 *       its owner's decision and skips the downgrade dwell (the owner has its own hysteresis).</li>
 *   <li><b>The task's own flags</b> ({@code setSneaking(true)} = SNEAK, {@code setSprinting(true)} = SPRINT).</li>
 *   <li><b>Route pace</b> from the straight-line distance to the goal: SPRINT from {@code routeSprintDistance} (8) on, WALK from
 *       {@code routeWalkDistance} (4.5) down, in between the previous route gait, so it never flaps. A downgrade needs
 *       {@value #DOWNGRADE_DWELL_TICKS} ticks at the current route gait; an upgrade is immediate.</li>
 *   <li><b>Quiet zone</b> on the task flags and route pace only (not on leases, pressure or a hunting warden): SILENT = SNEAK,
 *       CAUTION = at most WALK.</li>
 * </ol>
 * Then the ceilings: a <b>calm observed warden within 16 blocks</b> caps everything except a WARDEN lease to WALK (lifted while a
 * warden hunts the bot or the bot is taking damage), and a {@link ActionPack#capPace} ceiling (jump, drop, pillar, bridge and dig
 * nodes of a bounded local action) caps last: it beats pressure.
 *
 * <p>{@code pace.enabled=false} answers what the code did before this policy: the task's sprint flag, else a walk (the local
 * enforcer leaves the sub-target sprint rule of {@code WalkToController} in charge, and the Baritone bridge skips the policy).</p>
 *
 * <p>The decision itself ({@link #decide}) is pure; {@link #resolve} gathers its inputs from a bot.</p>
 */
public final class PacePolicy {
    /** Ticks a route gait must have lasted before it may be lowered again. */
    public static final int DOWNGRADE_DWELL_TICKS = 10;
    /** A calm warden this close caps the pace at a walk. */
    public static final double CALM_WARDEN_RANGE = 16.0D;
    /** The bot counts as "taking damage" for this many ticks after a hit. */
    public static final int DAMAGE_WINDOW_TICKS = 20;
    /** No decision for this many game ticks: the next one starts a fresh route (no hysteresis carried over from an old trip). */
    static final int FRESH_AFTER_TICKS = 5;

    /** Whether the bot is under aggro pressure; AggroSense installs the real one. Static, default: never. */
    private static volatile Predicate<AIPlayerEntity> pressureProbe = bot -> false;
    /** What {@link #setPressureProbe} restores on {@code null}: the production probe ({@link #setDefaultPressureProbe}), else never. */
    private static volatile Predicate<AIPlayerEntity> defaultPressureProbe = bot -> false;

    private PacePolicy() {
    }

    /** Replaces the pressure probe; {@code null} restores the default (the production probe, else never under pressure). */
    public static void setPressureProbe(Predicate<AIPlayerEntity> probe) {
        pressureProbe = probe == null ? defaultPressureProbe : probe;
    }

    /** Installs the production pressure probe: it is used now and restored whenever a scoped probe is removed. */
    public static void setDefaultPressureProbe(Predicate<AIPlayerEntity> probe) {
        defaultPressureProbe = probe == null ? bot -> false : probe;
        pressureProbe = defaultPressureProbe;
    }

    /** True when the probe says {@code bot} is under aggro pressure. */
    public static boolean underPressure(AIPlayerEntity bot) {
        return pressureProbe.test(bot);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The pure core
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A lease on the pace. A TICK lease is valid for {@value #TICK_LEASE_TICKS} game ticks after it is (re)requested, so it survives
     * a task that only ticks every fifth tick (the TaskManager throttle); a ROUTE lease has no clock, its holder ends it.
     *
     * @param untilExclusive first game tick the lease is no longer valid at ({@link Long#MAX_VALUE} for a route lease)
     */
    public record Lease(Gait gait, PaceOwner owner, long untilExclusive) {
        public static final int TICK_LEASE_TICKS = 6;

        public static Lease tick(Gait gait, PaceOwner owner, long now) {
            return new Lease(gait, owner, now + TICK_LEASE_TICKS);
        }

        public static Lease route(Gait gait, PaceOwner owner) {
            return new Lease(gait, owner, Long.MAX_VALUE);
        }

        public boolean validAt(long now) {
            return now < untilExclusive;
        }

        /**
         * The lease that wins at {@code now}: the valid one with the higher owner priority; equal priorities go to {@code a} (pass
         * the tick lease first: it is the fresher word). Null when neither is valid.
         */
        public static Lease best(Lease a, Lease b, long now) {
            boolean aValid = a != null && a.validAt(now);
            boolean bValid = b != null && b.validAt(now);
            if (aValid && bValid) {
                return b.owner.priority() > a.owner.priority() ? b : a;
            }
            return aValid ? a : bValid ? b : null;
        }
    }

    /** What the policy remembers between two decisions of one bot. */
    public static final class State {
        private Gait routeGait;
        private long routeSince;
        private long calls;
        private long lastNow = Long.MIN_VALUE;
        private Gait lastGait = Gait.SPRINT;

        /** The gait of the previous decision (SPRINT before the first). */
        public Gait lastGait() {
            return lastGait;
        }

        /** Forgets the route gait: the next decision starts fresh. */
        public void reset() {
            routeGait = null;
            lastNow = Long.MIN_VALUE;
        }
    }

    /**
     * Everything one decision looks at.
     *
     * @param huntingWarden     an observed warden within 24 blocks is hunting the bot or its owner
     * @param calmWardenCeiling a calm observed warden within 16 blocks, no hunting warden, the bot not taking damage
     * @param pressure          travelling and under aggro pressure
     * @param lease             the winning lease, or null
     * @param taskSneak         the task asked for {@code setSneaking(true)}
     * @param taskSprint        the task asked for {@code setSprinting(true)}
     * @param goalDistance      horizontal distance to the goal of the route, {@link Double#NaN} if unknown
     * @param quiet             the quiet-zone level
     * @param cap               the {@code capPace} ceiling, or null
     * @param now               the game time
     */
    public record Inputs(boolean huntingWarden, boolean calmWardenCeiling, boolean pressure, Lease lease, boolean taskSneak,
                         boolean taskSprint, double goalDistance, QuietZone.Level quiet, Gait cap, double routeSprintDistance,
                         double routeWalkDistance, long now) {
    }

    /** One decision. Pure apart from {@code state}. */
    public static Gait decide(Inputs in, State state) {
        state.calls++;
        Gait result;
        boolean wardenLease = in.lease() != null && in.lease().owner() == PaceOwner.WARDEN;
        boolean ceilingExempt = false;
        if (in.huntingWarden()) {
            result = Gait.SPRINT;
            ceilingExempt = true;
        } else if (in.pressure() && !wardenLease) {
            result = Gait.SPRINT;
        } else if (in.lease() != null) {
            result = in.lease().gait();
            ceilingExempt = wardenLease;
        } else {
            if (in.taskSneak()) {
                result = Gait.SNEAK;
            } else if (in.taskSprint()) {
                result = Gait.SPRINT;
            } else {
                result = routePace(in, state);
            }
            result = quietCeiling(result, in.quiet());
        }
        if (in.calmWardenCeiling() && !ceilingExempt) {
            result = Gait.min(result, Gait.WALK);
        }
        if (in.cap() != null) {
            result = Gait.min(result, in.cap());
        }
        state.lastGait = result;
        state.lastNow = in.now();
        return result;
    }

    private static Gait quietCeiling(Gait gait, QuietZone.Level quiet) {
        return switch (quiet) {
            case SILENT -> Gait.SNEAK;
            case CAUTION -> Gait.min(gait, Gait.WALK);
            case NONE -> gait;
        };
    }

    private static Gait routePace(Inputs in, State s) {
        double distance = in.goalDistance();
        if (s.routeGait == null || in.now() - s.lastNow > FRESH_AFTER_TICKS || in.now() < s.lastNow) {
            boolean far = Double.isNaN(distance) || distance >= (in.routeSprintDistance() + in.routeWalkDistance()) / 2.0D;
            s.routeGait = far ? Gait.SPRINT : Gait.WALK;
            s.routeSince = s.calls - DOWNGRADE_DWELL_TICKS;
        }
        Gait desired = s.routeGait;
        if (!Double.isNaN(distance)) {
            if (distance >= in.routeSprintDistance()) {
                desired = Gait.SPRINT;
            } else if (distance <= in.routeWalkDistance()) {
                desired = Gait.WALK;
            }
        }
        if (desired.compareTo(s.routeGait) > 0) {
            s.routeGait = desired;
            s.routeSince = s.calls;
        } else if (desired.compareTo(s.routeGait) < 0 && s.calls - s.routeSince >= DOWNGRADE_DWELL_TICKS) {
            s.routeGait = desired;
            s.routeSince = s.calls;
        }
        return s.routeGait;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The bot-level entry point
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The gait of {@code bot} this tick.
     *
     * @param goalDistance horizontal distance to the route goal, {@link Double#NaN} if there is none
     * @param travelling   the bot is being moved by a controller (a path, a walk or a route), so pressure and route pace apply
     */
    public static Gait resolve(AIPlayerEntity bot, double goalDistance, boolean travelling) {
        ActionPack pack = bot.getActionPack();
        MinecraftAiConfig.Pace config = MinecraftAiConfig.get().behaviour().paceOrDefaults();
        if (!config.paceEnabled()) {
            return pack.sprintRequested() ? Gait.SPRINT : Gait.WALK;
        }
        long now = bot.level().getGameTime();
        QuietZone zone = pack.quietZone();
        zone.refresh(bot);
        boolean hunting = zone.huntingWardenObserved();
        boolean tookDamage = RecentDamage.tookEntityDamage(bot.getUUID(), now, DAMAGE_WINDOW_TICKS);
        boolean calmCeiling = !hunting && !tookDamage && zone.calmWardenWithin(CALM_WARDEN_RANGE);
        Inputs inputs = new Inputs(
                hunting,
                calmCeiling,
                travelling && !hunting && pressureProbe.test(bot),
                pack.leaseAt(now),
                pack.sneakRequested(),
                pack.sprintRequested(),
                goalDistance,
                zone.level(),
                pack.capAt(now),
                config.routeSprintDistance(),
                config.routeWalkDistance(),
                now);
        Gait previous = pack.paceState().lastGait();
        Gait gait = decide(inputs, pack.paceState());
        if (gait != previous) {
            // A change of gait is rare (a few per trip): the line names everything the decision looked at.
            BotLog.path(bot, "pace_gait", "from", previous, "to", gait,
                    "goal_dist", Double.isNaN(goalDistance) ? "-" : Math.round(goalDistance * 10.0D) / 10.0D,
                    "lease", inputs.lease() == null ? "-" : inputs.lease().owner() + ":" + inputs.lease().gait(),
                    "cap", inputs.cap() == null ? "-" : inputs.cap() + ":" + pack.capReason(),
                    "quiet", inputs.quiet(), "hunting_warden", inputs.huntingWarden(), "calm_warden_cap", inputs.calmWardenCeiling(),
                    "pressure", inputs.pressure(), "task_sprint", inputs.taskSprint(), "task_sneak", inputs.taskSneak());
        }
        return gait;
    }
}
