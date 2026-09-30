package io.github.zoyluo.minecraftai.perception;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * How long each subject has been continuously exposed to one observer: the bookkeeping behind the reaction time of
 * {@link CreaturePerception} (the same semantics as the wrapper's {@code ExposureTracker}). Exposure is the number of ticks since the
 * subject was first sighted in an unbroken run; a run tolerates a single missed tick and is over when the subject was not sighted for
 * {@value #RESET_AFTER_MISSED} ticks in a row, so EVERY re-sighting after a real gap starts again from zero. Pure, no clock: the
 * caller passes the tick number. Server thread only.
 *
 * @param <K> the subject key (the entity's UUID)
 */
public final class ExposureTracker<K> {
    /** Consecutive ticks without a sighting after which the exposure starts again from zero. */
    public static final int RESET_AFTER_MISSED = 2;

    private static final class Run {
        long start;
        long last;
    }

    private final Map<K, Run> runs = new HashMap<>();

    /**
     * Records this tick's sighting of a subject and returns the continuous exposure so far, in ticks: 0 on the tick a run starts.
     * Calling it twice in one tick gives the same answer.
     */
    public long sighted(K key, long now) {
        Run r = runs.get(key);
        if (r == null || now - r.last > RESET_AFTER_MISSED || now < r.last) {
            r = new Run();
            r.start = now;
            runs.put(key, r);
        }
        r.last = now;
        return now - r.start;
    }

    /** True when the subject has an exposure run that is still alive at {@code now}. */
    public boolean inProgress(K key, long now) {
        Run r = runs.get(key);
        return r != null && now >= r.last && now - r.last <= RESET_AFTER_MISSED;
    }

    /** The subject was not sighted this tick: a run that has been broken for too long is dropped. */
    public void missed(K key, long now) {
        Run r = runs.get(key);
        if (r != null && (now < r.last || now - r.last > RESET_AFTER_MISSED)) {
            runs.remove(key);
        }
    }

    /** Forgets one subject. */
    public void forget(K key) {
        runs.remove(key);
    }

    /** Keeps only the runs of the subjects in {@code keep} (still present and valid this scan) and drops the others at once. */
    public void retain(Set<K> keep) {
        for (Iterator<K> it = runs.keySet().iterator(); it.hasNext(); ) {
            if (!keep.contains(it.next())) {
                it.remove();
            }
        }
    }

    public int size() {
        return runs.size();
    }

    public void clear() {
        runs.clear();
    }
}
