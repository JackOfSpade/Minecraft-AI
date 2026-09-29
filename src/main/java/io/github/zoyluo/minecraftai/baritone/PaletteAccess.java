package io.github.zoyluo.minecraftai.baritone;

import baritone.utils.accessor.IPalettedContainer.IData;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * How {@code BaritonePalettedContainerMixin} reaches the private {@code Data} field of a {@code PalettedContainer} (a mixin cannot
 * name that field, so it is found by its type). The reflective scan lives here, in a lazily initialised holder, and not in the
 * mixin's static initialiser: a mixin's initialiser runs when {@code PalettedContainer} is loaded, that is, at server start-up
 * with every engine, and a mod that changes {@code PalettedContainer} must not be able to crash that. The scan now runs only when
 * Baritone is first configured ({@link BaritoneHost#configure}, through {@link #verify}) or first reads a palette, and a scan that
 * finds no single {@code Data} field fails as a {@link LinkageError}, which is what {@code NavEngineSelector} treats as "Baritone
 * cannot be used in this JVM": it is retired for the session and the legacy navigator carries on.
 */
public final class PaletteAccess {
    /** The palette layout of this JVM does not match what Baritone reads. */
    public static final class Unsupported extends LinkageError {
        private static final long serialVersionUID = 1L;

        Unsupported(String message) {
            super(message);
        }
    }

    private PaletteAccess() {
    }

    /** Initialise-on-demand holder: the scan runs on the first {@link #data} or {@link #verify}, once. */
    private static final class Scan {
        static final MethodHandle GETTER;
        static final String FAILURE;

        static {
            MethodHandle getter = null;
            String failure = null;
            try {
                getter = find();
            } catch (Throwable scanFailed) {
                failure = scanFailed.toString();
            }
            GETTER = getter;
            FAILURE = failure;
        }

        private static MethodHandle find() throws IllegalAccessException {
            Field dataField = null;
            for (Field field : PalettedContainer.class.getDeclaredFields()) {
                if (IData.class.isAssignableFrom(field.getType())) {
                    if ((field.getModifiers() & (Modifier.STATIC | Modifier.FINAL)) != 0 || field.isSynthetic()) {
                        continue;
                    }
                    if (dataField != null) {
                        throw new IllegalStateException("PalettedContainer has more than one Data field.");
                    }
                    dataField = field;
                }
            }
            if (dataField == null) {
                throw new IllegalStateException("PalettedContainer has no Data field.");
            }
            MethodHandle raw = MethodHandles.privateLookupIn(PalettedContainer.class, MethodHandles.lookup()).unreflectGetter(dataField);
            return MethodHandles.explicitCastArguments(raw, MethodType.methodType(IData.class, PalettedContainer.class));
        }
    }

    /**
     * Checks that the palette layout can be read.
     *
     * @throws Unsupported when it cannot
     */
    public static void verify() {
        if (Scan.GETTER == null) {
            throw new Unsupported("PalettedContainer cannot be read by Baritone: " + Scan.FAILURE);
        }
    }

    /** The {@code Data} (palette and bit storage) of {@code container}. */
    @SuppressWarnings("unchecked")
    public static <T> IData<T> data(PalettedContainer<T> container) {
        verify();
        try {
            return (IData<T>) (Object) Scan.GETTER.invoke(container);
        } catch (RuntimeException | Error rethrow) {
            throw rethrow;
        } catch (Throwable other) {
            throw new IllegalStateException(other);
        }
    }
}
