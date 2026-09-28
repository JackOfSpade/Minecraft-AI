package io.github.zoyluo.minecraftai.mining.assist;

/**
 * Exponential moving average with an explicit smoothing factor. The first sample seeds the value
 * (no warm-up bias toward zero). Not thread-safe: callers own one instance per signal and update it
 * from a single thread.
 */
public final class Ema {
    private final double alpha;
    private double value;
    private boolean seeded;

    /** @param alpha weight of a new sample, in (0, 1] */
    public Ema(double alpha) {
        if (!(alpha > 0.0D && alpha <= 1.0D)) {
            throw new IllegalArgumentException("alpha must be in (0, 1], got " + alpha);
        }
        this.alpha = alpha;
    }

    /**
     * Folds one sample in and returns the new value. A non-finite sample is ignored so one bad
     * measurement cannot poison the average forever.
     */
    public double update(double sample) {
        if (!Double.isFinite(sample)) {
            return value;
        }
        if (!seeded) {
            value = sample;
            seeded = true;
        } else {
            value += alpha * (sample - value);
        }
        return value;
    }

    /** Current average, or 0.0 before the first sample. */
    public double value() {
        return value;
    }

    public boolean isSeeded() {
        return seeded;
    }

    public double alpha() {
        return alpha;
    }

    /** Forgets all samples; the next sample seeds again. */
    public void reset() {
        value = 0.0D;
        seeded = false;
    }
}
