package io.github.zoyluo.minecraftai.action;

/**
 * The pure rules of {@link WalkedStep} (no world, no bot): which cell offsets a kind of step may cover, how much of its time budget a
 * tick costs, when the keys are let go, and where a body that overlaps a block is pushed.
 * Kept free of Minecraft state so they are unit-testable.
 */
public final class WalkedStepRules {
    /** The shortest time a step may be given, in full-speed (walking) ticks. */
    public static final int MIN_TIMEOUT_TICKS = 20;
    /** Full-speed ticks a step is given per block it covers (a walk needs about 4.7, the rest is slack for a jump, a slab, a nudge). */
    public static final int TIMEOUT_TICKS_PER_BLOCK = 8;
    /** A drop presses no key once the bot is this close to the middle of its hole (a body 0.6 wide falls into a one-block hole from within 0.2). */
    public static final double DROP_CENTRED = 0.1D;
    /** Ground friction leaves a released walker sliding about 1.3 ticks of its speed: the keys are let go that far before the point. */
    public static final double BRAKE_FACTOR = 1.3D;
    /** Largest horizontal speed of the vanilla-client style push out of a block (LocalPlayer.moveTowardsClosestSpace uses 0.1). */
    public static final double PUSH_OUT_SPEED = 0.1D;
    /** A push out never travels farther than one block: the neighbouring cell is the only place a free side is looked for. */
    public static final double PUSH_OUT_MAX_SHIFT = 1.0D;
    /** The longest a step inside its own cell (recentre, sneak shift) may move the bot. */
    public static final double IN_CELL_MAX_OFFSET = 0.75D;
    /** A point-step is done once the bot is this close to its point. */
    public static final double POINT_TOLERANCE = 0.2D;
    /** A sneak shift over an edge is done this close to its point: it has to be over the edge (the eye past the face of the support), not near it. */
    public static final double SHIFT_TOLERANCE = 0.05D;
    /** Ticks a push out is given (0.8 block at 0.1 per tick is 8, plus slack). */
    public static final int PUSH_OUT_MAX_TICKS = 24;
    /** The speed factor of a walk relative to the walking pace, on which {@link #tickCost} is scaled (walking clock weight is 1/1.3). */
    private static final double WALK_CLOCK_WEIGHT = 1.0D / 1.3D;
    private static final double MIN_TICK_COST = 0.05D;

    private WalkedStepRules() {
    }

    /**
     * The time a step of {@code blocks} horizontal blocks may take, in full-speed ticks: {@code max(20, 8 per block)}. A tick that is
     * slower than a walk (a sneak, an item in use) costs less of it ({@link #tickCost}), so the same budget grows to five times as
     * many ticks while an item is used (input 0.2) and to about 3.4 times as many while sneaking.
     */
    public static double timeoutBudget(double blocks) {
        return Math.max(MIN_TIMEOUT_TICKS, TIMEOUT_TICKS_PER_BLOCK * Math.max(0.0D, blocks));
    }

    /**
     * How much of the budget one tick spends: 1 at a walk or a sprint, less at a slower gait. {@code clockWeight} is what the pace
     * enforcer reports ({@code ActionPack.paceClockWeight}: 1 sprint, 1/1.3 walk, 1/4.4 sneak, times 0.2 while an item is in use).
     */
    public static double tickCost(double clockWeight) {
        double cost = clockWeight / WALK_CLOCK_WEIGHT;
        return Math.max(MIN_TICK_COST, Math.min(1.0D, cost));
    }

    /** Whether the keys are let go now so the slide ends on the point (only steps that end at a point, not in a cell). */
    public static boolean shouldBrake(double distance, double speed) {
        return distance <= speed * BRAKE_FACTOR;
    }

    /** Whether a step of this kind may cover the offset from the bot's cell to its target cell. */
    public static boolean offsetAllowed(WalkedStep.Kind kind, int dx, int dy, int dz) {
        int horizontal = Math.max(Math.abs(dx), Math.abs(dz));
        return switch (kind) {
            case FLAT -> dy == 0 && horizontal == 1;
            case STEP_UP -> dy == 1 && horizontal == 1;
            case STEP_DOWN -> dy >= -3 && dy <= -1 && horizontal == 1;
            case DROP -> dy >= -3 && dy <= -1 && dx == 0 && dz == 0;
            case SWIM -> Math.abs(dy) <= 1 && horizontal <= 1 && (horizontal + Math.abs(dy)) > 0;
            case SNEAK_SHIFT, RECENTER, PUSH_OUT -> dx == 0 && dy == 0 && dz == 0;
        };
    }

    /**
     * Whether a point-owned in-cell step may start from this relative cell. The target point can
     * lie just over an edge, so the same cell and each horizontal neighbour are legitimate; a
     * different Y or a two-cell hop is not.
     */
    public static boolean inCellEnvelope(int dx, int dy, int dz) {
        return dy == 0 && Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
    }

    /** The walking kind that covers a height difference of {@code dy} cells to an adjacent cell (null when no walk does: up two, down four). */
    public static WalkedStep.Kind walkKindFor(int dy) {
        if (dy == 0) {
            return WalkedStep.Kind.FLAT;
        }
        if (dy == 1) {
            return WalkedStep.Kind.STEP_UP;
        }
        return dy >= -3 && dy < 0 ? WalkedStep.Kind.STEP_DOWN : null;
    }

    /** True for the kinds that end in a cell (the bot's block position must equal it) rather than at a point. */
    public static boolean endsInCell(WalkedStep.Kind kind) {
        return switch (kind) {
            case FLAT, STEP_UP, STEP_DOWN, DROP, SWIM -> true;
            case SNEAK_SHIFT, RECENTER, PUSH_OUT -> false;
        };
    }

    /** Whether the walk keys are let go for the last stretch of a step that settles on a point (a cell-step keeps walking until it is in the cell; a drop settles over its hole). */
    public static boolean brakes(WalkedStep.Kind kind) {
        return kind == WalkedStep.Kind.RECENTER || kind == WalkedStep.Kind.SNEAK_SHIFT || kind == WalkedStep.Kind.DROP;
    }

    /**
     * The direction with the smallest shift out of the block, or -1 when there is none within {@link #PUSH_OUT_MAX_SHIFT}.
     * {@code shifts[i]} is how far the body has to move in direction {@code i} to be free of every block (infinity or NaN: never).
     * The first of equally near directions wins, so the choice is stable.
     */
    public static int bestPushDirection(double[] shifts) {
        int best = -1;
        double bestShift = Double.POSITIVE_INFINITY;
        for (int i = 0; i < shifts.length; i++) {
            double shift = shifts[i];
            if (Double.isNaN(shift) || shift > PUSH_OUT_MAX_SHIFT) {
                continue;
            }
            if (shift < bestShift) {
                bestShift = shift;
                best = i;
            }
        }
        return best;
    }

    /** The horizontal speed of the push for a body that still has {@code remaining} blocks to go: never above {@link #PUSH_OUT_SPEED}. */
    public static double pushSpeed(double remaining) {
        return Math.max(0.0D, Math.min(PUSH_OUT_SPEED, remaining));
    }

    /** A swimmer holds the feet this far above the floor of the cell it swims through (jump below it, sink above it). */
    public static final double SWIM_HOLD_DEPTH = 0.3D;
    /** Swimming covers a block at about a third of the walking pace (about 2 blocks per second): a swim step is given longer. */
    public static final double SWIM_BUDGET_FACTOR = 2.5D;
    /** The downward push a diving swimmer gets per tick (vanilla LivingEntity.goDownInWater, which the client applies while shift is held in water). */
    public static final double SWIM_DIVE_PUSH = 0.04D;
    /** A swim step whose target is this close (horizontally) to the bot has nothing to walk toward: it only holds jump (up) or lets go (down). */
    public static final double SWIM_OVERHEAD_DISTANCE = 0.3D;

    /** {@link #timeoutBudget(double)} for the kind of step (a swim step is given {@value #SWIM_BUDGET_FACTOR} times as long). */
    public static double timeoutBudget(WalkedStep.Kind kind, double blocks) {
        return timeoutBudget(blocks) * (kind == WalkedStep.Kind.SWIM ? SWIM_BUDGET_FACTOR : 1.0D);
    }

    /**
     * Whether the jump key is down. A step-up presses it only while the bot is still below the target floor and standing on something
     * (or afloat: a swimmer pushing against a bank is lifted onto it by holding jump). A swim step holds the depth a swimmer holds:
     * jump while the feet are in the lower part of the target cell (a step up to a higher cell keeps jumping until it is there), and
     * let go to sink toward a lower one, so a level swim neither bobs up out of its cell nor sinks below it.
     *
     * <p>Why this is the vanilla rule: in water the jump key is a swim stroke (LivingEntity.jumpInLiquid, +0.04 upward per tick), not a
     * jump, and a body with its head under water has no ground. A player who wants to rise holds it, one who wants to stay at the
     * surface taps it, one who wants to dive lets go (and holds shift). Hence:
     * <ul>
     * <li>STEP_UP presses it whenever the body is below the target floor and either stands on something or is afloat ({@code inWater};
     * a head under water implies it): the stroke that lifts a swimmer onto a bank or out of a pool, and the plain hop on land.</li>
     * <li>SWIM presses it while the feet are below the hold depth of the target cell (its floor plus {@link #SWIM_HOLD_DEPTH}): swim up
     * until the feet reach the cell, stay at the surface once there. That also covers a stroke that leaves the water for the air cell
     * above it. A rule of "jump whenever the head is under water" would never let a dive happen and would bob a level swim up out of
     * its cell, so the head is deliberately not part of the SWIM rule.</li>
     * </ul>
     * The swimming and rescue steps (NaturalSwimGameTests) hold their depth with it, and the pool exit, rim and pickup steps of the
     * pickup job keep stroking until the feet are up.
     */
    /** Whether the step should hold the jump key for a bot that may be afloat ({@code inWater}). */
    public static boolean jumpNow(WalkedStep.Kind kind, boolean grounded, double feetY, int targetY, boolean headUnderwater,
                                  boolean inWater) {
        return switch (kind) {
            case STEP_UP -> (grounded || inWater) && feetY < targetY - 0.05D;
            case SWIM -> feetY < targetY + SWIM_HOLD_DEPTH;
            default -> false;
        };
    }
}
