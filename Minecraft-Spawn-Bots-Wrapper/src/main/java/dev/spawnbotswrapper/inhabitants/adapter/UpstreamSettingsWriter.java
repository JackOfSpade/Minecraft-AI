package dev.spawnbotswrapper.inhabitants.adapter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ONLY place that writes PvP BOT's settings, and only the few the addon manages (see {@link ManagedSettings}).
 * <p>
 * Why fields and not PvP BOT's setters: the setters clamp to ranges (ranged optimal at least 10, ranged max at least
 * 15, ranged min at most 20) that exclude the short archer distances the addon wants, and every setter also writes the
 * settings file. So the private fields are set directly and the file is written once afterwards through PvP BOT's own
 * save routine. Every name is resolved once when the contract is probed; a missing one is reported (one warning) and
 * the rest keeps working.
 */
final class UpstreamSettingsWriter {

    /** The managed settings by name, with the type the field must have. */
    static final Map<String, Class<?>> MANAGED = Map.ofEntries(
            Map.entry("maxTargetDistance", double.class),
            Map.entry("rangedMinRange", double.class),
            Map.entry("rangedOptimalRange", double.class),
            Map.entry("rangedMaxRange", double.class),
            Map.entry("autoEquipWeapon", boolean.class),
            Map.entry("autoTargetEnabled", boolean.class),
            Map.entry("rangedRetreatOnClose", boolean.class),
            Map.entry("meleeRange", double.class),
            Map.entry("bowMinDrawTime", int.class),
            Map.entry("autoTotemEnabled", boolean.class),
            Map.entry("totemPriority", boolean.class));

    /** What a probe found: writable fields by setting name, PvP BOT's save routine, and what is unusable. */
    record Handles(Map<String, Field> fields, Method save, List<String> problems) {
        Handles {
            fields = Map.copyOf(fields);
            problems = List.copyOf(problems);
        }

        boolean canWrite(String name) {
            return save != null && fields.containsKey(name);
        }
    }

    private UpstreamSettingsWriter() {
    }

    /** Resolves the fields and the save routine of the settings class; never initialises it. {@code type} may be null. */
    static Handles resolve(Class<?> type) {
        Map<String, Field> fields = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        Method save = null;
        if (type == null) {
            problems.add("the settings class is not available");
            return new Handles(fields, null, problems);
        }
        for (Map.Entry<String, Class<?>> e : MANAGED.entrySet()) {
            try {
                Field f = type.getDeclaredField(e.getKey());
                if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())) {
                    problems.add("field " + e.getKey() + " is static or final");
                } else if (f.getType() != e.getValue()) {
                    problems.add("field " + e.getKey() + " is " + f.getType().getSimpleName() + ", expected "
                            + e.getValue().getSimpleName());
                } else {
                    fields.put(e.getKey(), f);
                }
            } catch (NoSuchFieldException ex) {
                problems.add("field " + e.getKey() + " not found");
            } catch (Throwable t) {
                problems.add("field " + e.getKey() + " could not be inspected (" + Diagnostics.describe(t) + ")");
            }
        }
        try {
            Method m = type.getMethod("save");
            if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class) {
                problems.add("the save routine is not a static void method");
            } else {
                save = m;
            }
        } catch (NoSuchMethodException ex) {
            problems.add("the save routine was not found");
        } catch (Throwable t) {
            problems.add("the save routine could not be inspected (" + Diagnostics.describe(t) + ")");
        }
        return new Handles(fields, save, problems);
    }

    /** Sets one managed field on the settings object; the caller saves afterwards. */
    static void write(Handles handles, Object settings, String name, Object value) throws Throwable {
        Field f = handles.fields().get(name);
        if (f == null) {
            throw new IllegalStateException("no writable field " + name);
        }
        f.setAccessible(true);
        if (f.getType() == double.class) {
            f.setDouble(settings, ((Number) value).doubleValue());
        } else if (f.getType() == int.class) {
            f.setInt(settings, ((Number) value).intValue());
        } else {
            f.setBoolean(settings, (Boolean) value);
        }
    }

    /** PvP BOT's own routine that writes the per-world settings file. */
    static void save(Handles handles) throws Throwable {
        if (handles.save() == null) {
            throw new IllegalStateException("no save routine");
        }
        UpstreamCalls.invoke(handles.save(), null);
    }
}
