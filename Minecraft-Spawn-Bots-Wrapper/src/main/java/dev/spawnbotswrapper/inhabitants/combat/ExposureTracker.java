package dev.spawnbotswrapper.inhabitants.combat;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * How long each (observer, subject) pair has been continuously exposed: the bookkeeping behind the reaction time of
 * {@link Perception}. Exposure is the number of ticks since the subject was first sighted in an unbroken run; a run
 * tolerates a single missed tick and is over when the subject was not sighted for {@value #RESET_AFTER_MISSED} ticks in
 * a row. Pure, no clock: the caller passes the tick number. Server thread only.
 */
public final class ExposureTracker {
    /** Consecutive ticks without a sighting after which the exposure starts again from zero. */
    public static final int RESET_AFTER_MISSED = 2;

    private static final class Run {
        long start;
        long last;
    }

    private final Map<String, Run> runs = new HashMap<>();

    /** The key of one (observer, subject) pair. */
    public static String key(String observer, String subject) {
        return observer + '\u0000' + subject;
    }

    /**
     * Records this tick's sighting of a pair and returns the continuous exposure so far, in ticks: 0 on the tick a run
     * starts. Call once per tick per pair that is being watched.
     */
    public long sighted(String key, long now) {
        Run r = runs.get(key);
        if (r == null || now - r.last > RESET_AFTER_MISSED || now < r.last) {
            r = new Run();
            r.start = now;
            runs.put(key, r);
        }
        r.last = now;
        return now - r.start;
    }

    /** True when the pair has an exposure run that is still alive at {@code now} (so it must be watched every tick). */
    public boolean inProgress(String key, long now) {
        Run r = runs.get(key);
        return r != null && now >= r.last && now - r.last <= RESET_AFTER_MISSED;
    }

    /** The subject was not sighted this tick: a run that has been broken for too long is dropped. */
    public void missed(String key, long now) {
        Run r = runs.get(key);
        if (r != null && (now < r.last || now - r.last > RESET_AFTER_MISSED)) {
            runs.remove(key);
        }
    }

    /** Forgets one pair. */
    public void forget(String key) {
        runs.remove(key);
    }

    /** Forgets every pair of one observer (it died, left or started an engagement). */
    public void forgetObserver(String observer) {
        String prefix = observer + '\u0000';
        for (Iterator<String> it = runs.keySet().iterator(); it.hasNext(); ) {
            if (it.next().startsWith(prefix)) {
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
