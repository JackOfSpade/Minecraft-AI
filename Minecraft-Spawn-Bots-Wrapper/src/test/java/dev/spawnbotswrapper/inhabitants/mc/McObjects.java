package dev.spawnbotswrapper.inhabitants.mc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Runs inside {@link McSandbox}: creates instances of Minecraft classes that cannot be constructed without a
 * running server (a server, a world, a player) so that code which only PASSES them around can be tested.
 * The instances are allocated without running a constructor; calling almost any method on them fails, which is
 * exactly what a test of pass-through logic wants (touching them by accident becomes a visible error).
 * Reflection only, so the tests have no compile-time dependency on JDK-internal classes.
 */
public final class McObjects {
    private static final Object UNSAFE;
    private static final Method ALLOCATE;
    private static final Method OBJECT_OFFSET;
    private static final Method PUT_OBJECT;
    private static final Method PUT_INT;

    static {
        try {
            Class<?> unsafe = Class.forName("sun.misc.Unsafe");
            Field f = unsafe.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = f.get(null);
            ALLOCATE = unsafe.getMethod("allocateInstance", Class.class);
            OBJECT_OFFSET = unsafe.getMethod("objectFieldOffset", Field.class);
            PUT_OBJECT = unsafe.getMethod("putObject", Object.class, long.class, Object.class);
            PUT_INT = unsafe.getMethod("putInt", Object.class, long.class, int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private McObjects() {
    }

    /** An instance of {@code type} whose constructor never ran. */
    public static <T> T opaque(Class<T> type) {
        try {
            return type.cast(ALLOCATE.invoke(UNSAFE, type));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot allocate " + type, e);
        }
    }

    /** Sets a (possibly private final) instance field on an object made by {@link #opaque}. */
    public static void setField(Object target, Class<?> declaring, String name, Object value) {
        try {
            Field field = declaring.getDeclaredField(name);
            long offset = (Long) OBJECT_OFFSET.invoke(UNSAFE, field);
            PUT_OBJECT.invoke(UNSAFE, target, offset, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set " + declaring.getSimpleName() + "." + name, e);
        }
    }

    /** Sets an int instance field on an object made by {@link #opaque}. */
    public static void setInt(Object target, Class<?> declaring, String name, int value) {
        try {
            Field field = declaring.getDeclaredField(name);
            long offset = (Long) OBJECT_OFFSET.invoke(UNSAFE, field);
            PUT_INT.invoke(UNSAFE, target, offset, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set " + declaring.getSimpleName() + "." + name, e);
        }
    }
}
