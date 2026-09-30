package io.github.zoyluo.minecraftai.action;

/**
 * The extra ticks a route's deadline gets for the part of a tick a slow gait does not count ({@link Gait#clockWeight}), so a bot
 * that deliberately sneaks or walks is not timed out for it. The total is capped for the whole route: without a cap a sneak
 * (3.4 ticks of credit per tick) would move the deadline out faster than time passes and a long SNEAK lease (a sculk quiet zone, a
 * warden) could never reach its deadline, however stuck the bot is.
 */
final class DeadlineCredit {
    /** The most a route may be credited, as a multiple of the ticks its deadline first allowed. */
    static final double BUDGET_MULTIPLE = 1.0D;

    private final int cap;
    private double carry;
    private int granted;

    /** @param cap the total whole ticks this route may be credited (0 = none) */
    DeadlineCredit(int cap) {
        this.cap = Math.max(0, cap);
    }

    /** The credit for a route whose deadline first allowed {@code originalBudgetTicks} ticks. */
    static DeadlineCredit forBudget(int originalBudgetTicks) {
        return new DeadlineCredit((int) Math.round(Math.max(0, originalBudgetTicks) * BUDGET_MULTIPLE));
    }

    /** Records a tick at {@code gait}; returns the whole ticks to add to the deadline now (usually 0). */
    int note(Gait gait) {
        if (gait == Gait.SPRINT || granted >= cap) {
            return 0;
        }
        carry += 1.0D - gait.clockWeight();
        int whole = (int) carry;
        if (whole <= 0) {
            return 0;
        }
        carry -= whole;
        whole = Math.min(whole, cap - granted);
        granted += whole;
        return whole;
    }

    /** The whole ticks credited so far. */
    int granted() {
        return granted;
    }
}
