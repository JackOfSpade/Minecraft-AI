package io.github.zoyluo.minecraftai.action;

/**
 * The pure decisions of shield use, free of any Minecraft class so each one is unit-testable: the front arc, the time a block needs
 * (turn, hotbar change, block delay) against the time a hit gives, the hand policy (what may be interrupted for a block), the
 * main-hand use order, and the melee rhythm between a bot's own swings. The numbers are vanilla's ({@code BlocksAttacks}: a 90 degree
 * half arc and {@code block_delay_seconds}), passed in by the callers from the item's own component.
 */
public final class ShieldRules {
    /**
     * The vanilla shield's {@code horizontal_blocking_angle} (its one damage reduction): a hit is blocked only when its source is within
     * this many degrees of the head direction. The callers pass the arc of the shield in hand ({@code ShieldBlockability.halfArcDeg});
     * this is the vanilla value for the tests.
     */
    public static final double VANILLA_HALF_ARC_DEG = 90.0D;
    /**
     * The arc the bot turns into, inside the vanilla one: a projectile's position at impact differs a little from where it is seen, and
     * a turn that stops exactly on the edge would let the smallest drift leave it.
     */
    public static final double ARC_MARGIN_DEG = 20.0D;
    /**
     * Ticks of slack between the last moment a raise can become active and the tick of the hit: the impact estimate is the closest
     * approach to the eyes (a little after first contact), and the use counter moves in the entity tick that may run after the arrow's.
     */
    public static final double HIT_SLACK_TICKS = 1.0D;
    /** The hotbar change before a main-hand item that would take the use is swapped for one that does not: one tick. */
    public static final int HOTBAR_SWITCH_TICKS = 1;

    private ShieldRules() {
    }

    // ------------------------------------------------------------------ the front arc

    /**
     * The angle, degrees in 0..180, between the head direction ({@code headYawDeg}) and the direction to a hit source at
     * {@code (dx, dz)} from the bot, both flattened: vanilla's {@code applyItemBlocking} measures exactly this with the head yaw.
     */
    public static double offsetDeg(double headYawDeg, double dx, double dz) {
        double length = Math.hypot(dx, dz);
        if (length < 1.0E-9D) {
            return 0.0D;
        }
        double yaw = Math.toRadians(headYawDeg);
        double headX = -Math.sin(yaw);
        double headZ = Math.cos(yaw);
        double dot = (headX * dx + headZ * dz) / length;
        return Math.toDegrees(Math.acos(Math.max(-1.0D, Math.min(1.0D, dot))));
    }

    /** True when a source at {@code offsetDeg} from the head direction is inside the vanilla arc. */
    public static boolean inArc(double offsetDeg, double halfArcDeg) {
        return offsetDeg <= halfArcDeg;
    }

    /** Degrees the head must still turn to bring a source at {@code offsetDeg} inside the arc with the margin ({@code 0} when it already is). */
    public static double degreesToTurn(double offsetDeg, double halfArcDeg) {
        return Math.max(0.0D, offsetDeg - Math.max(0.0D, halfArcDeg - ARC_MARGIN_DEG));
    }

    /** Whole ticks a turn of {@code degrees} takes at {@code degPerTick} (the human turn rate); a bot that cannot turn never arrives. */
    public static int turnTicks(double degrees, double degPerTick) {
        if (degrees <= 0.0D) {
            return 0;
        }
        return degPerTick <= 0.0D ? Integer.MAX_VALUE : (int) Math.ceil(degrees / degPerTick);
    }

    // ------------------------------------------------------------------ the reaction

    /**
     * May a sensed projectile be acted on now? A projectile from a shooter the bot is already tracking (noticed, see docs/PERCEPTION.md)
     * was anticipated: the bot watched the release, so it reacts at once. Anything else (an arrow from a shooter it had not noticed, an
     * ownerless one, a shot it only heard) is a first sighting: the human reaction time of the shared formula
     * ({@code CreaturePerception.requiredSeconds} for the projectile's angle and distance) must have passed in continuous exposure
     * first. Perception off: no reaction time (today's behaviour).
     */
    public static boolean reacted(boolean perceptionOn, boolean shooterTracked, double exposureSeconds, double requiredSeconds) {
        if (!perceptionOn || shooterTracked) {
            return true;
        }
        return requiredSeconds != Double.POSITIVE_INFINITY && exposureSeconds + 1.0E-9D >= requiredSeconds;
    }

    // ------------------------------------------------------------------ the time a block needs

    /** Ticks still to wait before a shield raised {@code ticksUsing} ticks ago blocks (0 once {@code block_delay_seconds} has passed). */
    public static int blockDelayRemaining(int blockDelayTicks, int ticksUsing) {
        return Math.max(0, blockDelayTicks - Math.max(0, ticksUsing));
    }

    /**
     * True when a block started now can be ACTIVE (turned into the arc, block delay passed) before a hit that lands in
     * {@code ticksToImpact} ticks. The turn and the block delay run together (the head turns while the shield warms up); a
     * hotbar change comes first. A raise that cannot make it is not started: the shield would only slow the bot down.
     */
    public static boolean canBeActiveInTime(double ticksToImpact, int turnTicks, int delayRemainingTicks, int switchTicks) {
        long needed = (long) Math.max(0, switchTicks) + Math.max(turnTicks, delayRemainingTicks);
        return ticksToImpact >= needed + HIT_SLACK_TICKS;
    }

    // ------------------------------------------------------------------ the hand policy

    /** What the bot is using right now, for the decision whether a block may take the hand. */
    public enum UseKind {
        /** Nothing in use. */
        NONE,
        /** The shield, raised already. */
        SHIELD,
        /** Food, a potion or another consumable: eating in progress. */
        CONSUMING,
        /** A drawn bow, a charging crossbow or a raised trident: a shot in progress. */
        RANGED_DRAW,
        /** Any other item use (a spyglass, a goat horn). */
        OTHER
    }

    /**
     * May a block take the use hand from what the bot is doing? Nothing in use, or the shield itself: yes. Eating in progress, a
     * shot being drawn and any other use are finished, never cancelled for a hit that only hurts: they are cancelled only for a hit
     * that would be lethal (a sensible player keeps chewing at low health because the food is the heal, and keeps drawing because the
     * arrow is the answer, but not with the next hit killing them).
     */
    public static boolean mayInterrupt(UseKind current, boolean incomingLethal) {
        return switch (current) {
            case NONE, SHIELD -> true;
            case CONSUMING, RANGED_DRAW, OTHER -> incomingLethal;
        };
    }

    /** True when a hit of {@code damage} half hearts would leave {@code healthAndAbsorption} at zero or below (armour and resistance ignored: the worst case). */
    public static boolean lethal(float damage, float healthAndAbsorption) {
        return damage >= healthAndAbsorption;
    }

    // ------------------------------------------------------------------ the main-hand use order

    /** What the main hand holds, for the vanilla use order (the client tries MAIN_HAND, then OFF_HAND; the first that consumes the use wins). */
    public enum MainHandKind {
        EMPTY,
        /** A sword, an axe, a tool, a block: {@code use} in the air passes to the offhand. */
        PLAIN,
        /** A shield: it blocks from the main hand itself. */
        SHIELD,
        /** A bow with ammunition, a loaded or loadable crossbow, a trident that can be raised: starts a draw, takes the use. */
        RANGED_READY,
        /** A bow without ammunition, a spent trident: the use fails and passes on. */
        RANGED_UNUSABLE,
        /** Food the bot can eat now, a potion, milk: starts eating. */
        CONSUMABLE_READY,
        /** Food the bot cannot eat now (full): the use passes on. */
        CONSUMABLE_REFUSED,
        /** Armour, an elytra, anything the use swaps into its slot. */
        EQUIPPABLE_SWAP,
        /** An item with a continued use (a spear charge, a spyglass, a goat horn) or an instant one in the air (a throwable, a fishing rod, a bucket). */
        OTHER_USE
    }

    /** True when the item in the main hand would take the use before the offhand shield is tried (so the hotbar must change first). */
    public static boolean mainHandConsumesUse(MainHandKind kind) {
        return switch (kind) {
            case EMPTY, PLAIN, RANGED_UNUSABLE, CONSUMABLE_REFUSED -> false;
            case SHIELD, RANGED_READY, CONSUMABLE_READY, EQUIPPABLE_SWAP, OTHER_USE -> true;
        };
    }

    // ------------------------------------------------------------------ the melee rhythm

    /** What to do this tick in a melee exchange with a noticed attacker in reach. */
    public enum MeleeStep {
        /** The swing is ready and the target is under the crosshair in reach: lower the shield if it is up, swing (never strike while using an item). */
        SWING,
        /** Bring the shield up: it will be active before the swing comes round. */
        RAISE,
        /** Keep the shield up (the swing is still cooling down, or the aim is still settling on the target). */
        HOLD,
        /** Nothing to do with the shield this tick (no attacker in reach, no usable shield, or too little time before the swing to be worth a raise). */
        IDLE
    }

    /**
     * The skilled player's rhythm: swing the moment the weapon is ready and the target is under the crosshair, raise the shield right
     * after and hold it through the cooldown, lower it for the next swing. A shield disabled by an axe ({@code shieldUsable} false, the
     * vanilla item cooldown) is never raised: no attempts, the fight simply goes on.
     *
     * @param swingReady               the weapon's attack strength is back (vanilla 0.95)
     * @param targetUnderCrosshairInReach the target is the entity under the crosshair within the weapon's vanilla reach
     * @param attackerInReach          a noticed attacker is within its own reach of the bot
     * @param shieldUsable             a shield is in the offhand and not on its item cooldown
     * @param shieldUp                 the shield is in use now
     * @param cooldownTicksLeft        ticks until the weapon is ready again
     * @param blockDelayTicks          the shield's {@code block_delay_seconds} in ticks
     */
    public static MeleeStep meleeStep(boolean swingReady, boolean targetUnderCrosshairInReach, boolean attackerInReach,
                                      boolean shieldUsable, boolean shieldUp, double cooldownTicksLeft, int blockDelayTicks) {
        if (swingReady && targetUnderCrosshairInReach) {
            return MeleeStep.SWING;
        }
        if (!attackerInReach || !shieldUsable) {
            return MeleeStep.IDLE;
        }
        if (shieldUp) {
            return MeleeStep.HOLD;
        }
        // Raising only pays when the shield can be active before the next swing: otherwise it is a slowdown for nothing.
        return cooldownTicksLeft >= blockDelayTicks + 1 ? MeleeStep.RAISE : MeleeStep.IDLE;
    }
}
