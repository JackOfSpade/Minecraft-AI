package dev.spawnbotswrapper.inhabitants.mc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Hands the {@code spawning.backend} config value ({@code AUTO}, {@code CLASS} or {@code COMMAND}) to the
 * PvP BOT adapter.
 * <p>
 * The adapter's frozen interface has no setter for it, so the adapter implementation may offer one as an
 * extra public method, and this helper finds it: any public single-argument method whose name starts with
 * {@code set} and contains {@code backend}, taking either a String or an enum with a matching constant.
 * Keeping the lookup here means the mod entrypoint neither depends on a particular spelling nor breaks when
 * the adapter changes it; when there is no such method the choice is simply not applied and the caller says so.
 */
public final class AdapterBackend {
    private AdapterBackend() {
    }

    /**
     * Applies {@code backend} to {@code adapter} if it has a setter for it.
     *
     * @return true when a setter was found and accepted the value
     */
    public static boolean apply(Object adapter, String backend) {
        if (adapter == null || backend == null) {
            return false;
        }
        for (Method method : adapter.getClass().getMethods()) {
            if (!isBackendSetter(method)) {
                continue;
            }
            Object argument = argumentFor(method.getParameterTypes()[0], backend);
            if (argument == null) {
                continue;
            }
            try {
                method.invoke(adapter, argument);
                return true;
            } catch (IllegalAccessException | InvocationTargetException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean isBackendSetter(Method method) {
        String name = method.getName().toLowerCase(Locale.ROOT);
        return method.getParameterCount() == 1 && name.startsWith("set") && name.contains("backend");
    }

    private static Object argumentFor(Class<?> type, String backend) {
        if (type == String.class) {
            return backend;
        }
        if (type.isEnum()) {
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(backend)) {
                    return constant;
                }
            }
        }
        return null;
    }
}
