package io.github.zoyluo.minecraftai.action;

/**
 * The pure rules of {@link WalkedStep} (no world, no bot): which cell offsets a kind of step may cover, how much of its time budget a
 * tick costs, when the keys are let go, where a body that overlaps a block is pushed, and how the walk keys follow from the heading.
 * Kept free of Minecraft state so they are unit-testable.
 */
public final class WalkedStepRules {
    /** The shortest time a step may be given, in full-speed (walking) ticks. */
    public static final int MIN_TIMEOUT_TICKS = 20;
    /** Full-speed ticks a step is given per block it covers (a walk needs about 4.7, the rest is slack for a jump, a slab, a nudge). */
    public static final int TIMEOUT_TICKS_PER_BLOCK = 8;
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
            case SWIM -> Math.abs(dy) <= 1 && horizontal <= 1 && (horizontal + Math.abs(dy)) > 0;
            case SNEAK_SHIFT, RECENTER, PUSH_OUT -> dx == 0 && dy == 0 && dz == 0;
        };
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
            case FLAT, STEP_UP, STEP_DOWN, SWIM -> true;
            case SNEAK_SHIFT, RECENTER, PUSH_OUT -> false;
        };
    }

    /** Whether the walk keys are let go for the last stretch of a point-step (a cell-step keeps walking until it is in the cell). */
    public static boolean brakes(WalkedStep.Kind kind) {
        return kind == WalkedStep.Kind.RECENTER || kind == WalkedStep.Kind.SNEAK_SHIFT;
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

    /**
     * Forward and left key values that move the bot toward a target offset {@code (dx, dz)} when it faces {@code yawDegrees}
     * (vanilla moveRelative: forward is (-sin, cos), left is (cos, sin) in the yaw frame). Unit length; zero when there is no offset.
     */
    public static float[] keysToward(double yawDegrees, double dx, double dz) {
        double distance = Math.hypot(dx, dz);
        if (distance < 1.0E-6D) {
            return new float[]{0.0F, 0.0F};
        }
        double yaw = Math.toRadians(yawDegrees);
        double ux = dx / distance;
        double uz = dz / distance;
        return new float[]{(float) (-Math.sin(yaw) * ux + Math.cos(yaw) * uz), (float) (Math.cos(yaw) * ux + Math.sin(yaw) * uz)};
    }

    /** A step-up needs the jump key only while the bot is still below the target floor and standing on something. */
    public static boolean jumpNow(WalkedStep.Kind kind, boolean grounded, double feetY, int targetY, boolean headUnderwater) {
        return switch (kind) {
            case STEP_UP -> grounded && feetY < targetY - 0.05D;
            case SWIM -> headUnderwater || feetY < targetY - 0.05D;
            default -> false;
        };
    }
}
