package dev.spawnbotswrapper.inhabitants.adapter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Class locators for the probe tests. The healthy fake upstream classes live under their real names
 * (org.stepan1411.pvp_bot...) in the test tree; a scenario replaces one upstream class name by a variant
 * or removes it, which is how each way of being incompatible is produced without a custom class loader.
 */
final class TestLocators {

    private TestLocators() {
    }

    static ClassLocator canonical() {
        return ClassLocator.forLoader(TestLocators.class.getClassLoader());
    }

    /** Canonical fakes, except that {@code upstreamName} resolves to {@code replacement}. */
    static ClassLocator replacing(String upstreamName, Class<?> replacement) {
        return builder().replace(upstreamName, replacement).build();
    }

    /** Canonical fakes, except that each named class cannot be found. */
    static ClassLocator missing(String... upstreamNames) {
        Builder b = builder();
        for (String n : upstreamNames) {
            b.remove(n);
        }
        return b.build();
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private final Map<String, Class<?>> replacements = new HashMap<>();
        private final Set<String> removed = new HashSet<>();
        private final Map<String, Throwable> failing = new HashMap<>();

        Builder replace(String upstreamName, Class<?> replacement) {
            replacements.put(upstreamName, replacement);
            return this;
        }

        /** Replacement by class name, for classes a test cannot name at compile time (non-public ones). */
        Builder replace(String upstreamName, String className) {
            try {
                replacements.put(upstreamName, Class.forName(className));
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException(className, e);
            }
            return this;
        }

        Builder remove(String upstreamName) {
            removed.add(upstreamName);
            return this;
        }

        /** Loading this class throws {@code error} (a linkage problem, say) instead of "not found". */
        Builder failWith(String upstreamName, Throwable error) {
            failing.put(upstreamName, error);
            return this;
        }

        ClassLocator build() {
            ClassLocator base = canonical();
            Map<String, Class<?>> replaced = Map.copyOf(replacements);
            Set<String> gone = Set.copyOf(removed);
            Map<String, Throwable> broken = Map.copyOf(failing);
            return name -> {
                Throwable t = broken.get(name);
                if (t != null) {
                    sneakyThrow(t);
                }
                if (gone.contains(name)) {
                    throw new ClassNotFoundException(name);
                }
                Class<?> r = replaced.get(name);
                return r != null ? r : base.load(name);
            };
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
}
