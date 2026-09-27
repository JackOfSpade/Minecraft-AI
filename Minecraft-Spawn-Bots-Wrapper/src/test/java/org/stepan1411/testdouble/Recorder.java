package org.stepan1411.testdouble;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared state of the fake upstream classes: what was called (in order), what the test wants to fail or
 * throw, and what must NEVER be called. Fakes are static, exactly like the classes they stand in for, so
 * the state has to be static too; every test starts with {@link #reset()}.
 * <p>
 * Test-only. It reproduces signatures and trivial behaviour of the reflection contract and nothing else.
 */
public final class Recorder {

    /** Every recorded call as {@code method} or {@code method:detail}, in call order. */
    public static final List<String> CALLS = new ArrayList<>();
    /** Boolean methods named here answer false without doing their work. */
    public static final Set<String> FAIL = new HashSet<>();
    /** Methods named here throw an {@link IllegalStateException} (as upstream code might). */
    public static final Set<String> THROW = new HashSet<>();
    /** Things the adapter must never do (a setter, load/save, removeAll); a test asserts this stays empty. */
    public static final Set<String> FORBIDDEN = new LinkedHashSet<>();
    /** Names PvP BOT "lists" (its bot set). */
    public static final Set<String> LISTED = new LinkedHashSet<>();
    public static int getAllBotsCalls;

    private Recorder() {
    }

    public static void reset() {
        CALLS.clear();
        FAIL.clear();
        THROW.clear();
        FORBIDDEN.clear();
        LISTED.clear();
        getAllBotsCalls = 0;
    }

    /**
     * Records the call, throws when the test asked this method to throw, and returns false when the test
     * asked it to fail (true otherwise).
     */
    public static boolean guard(String method, String detail) {
        CALLS.add(detail == null || detail.isEmpty() ? method : method + ":" + detail);
        if (THROW.contains(method)) {
            throw new IllegalStateException("fake " + method + " configured to throw");
        }
        return !FAIL.contains(method);
    }

    /** Calls whose text starts with {@code prefix}. */
    public static List<String> callsStartingWith(String prefix) {
        List<String> out = new ArrayList<>();
        for (String c : CALLS) {
            if (c.startsWith(prefix)) {
                out.add(c);
            }
        }
        return out;
    }
}
