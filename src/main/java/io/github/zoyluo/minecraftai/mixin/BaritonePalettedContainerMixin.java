package io.github.zoyluo.minecraftai.mixin;

import baritone.utils.accessor.IPalettedContainer;
import baritone.utils.accessor.IPalettedContainer.IData;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Baritone's {@code FasterWorldScanner} reads a chunk section's raw palette and bit storage instead of asking for every
 * block state. Upstream implements this in {@code baritone.launch.mixins.MixinPalettedContainer}, which is one of the
 * client-only mixins and is not part of this build; this is the same code (it finds the {@code Data} field by its type
 * because a mixin cannot name it) for the server.
 */
@Mixin(PalettedContainer.class)
public abstract class BaritonePalettedContainerMixin<T> implements IPalettedContainer<T> {
    @Unique
    private static final MethodHandle MINECRAFTAI_DATA_GETTER;

    static {
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
        MethodHandle rawGetter;
        try {
            rawGetter = MethodHandles.lookup().unreflectGetter(dataField);
        } catch (IllegalAccessException impossible) {
            throw new IllegalStateException("PalettedContainer may not access its own field?!", impossible);
        }
        MINECRAFTAI_DATA_GETTER = MethodHandles.explicitCastArguments(rawGetter,
                MethodType.methodType(IData.class, PalettedContainer.class));
    }

    @Override
    public Palette<T> getPalette() {
        return minecraftai$data().getPalette();
    }

    @Override
    public BitStorage getStorage() {
        return minecraftai$data().getStorage();
    }

    @Unique
    @SuppressWarnings("unchecked")
    private IData<T> minecraftai$data() {
        try {
            return (IData<T>) (Object) MINECRAFTAI_DATA_GETTER.invoke((PalettedContainer<T>) (Object) this);
        } catch (Throwable t) {
            throw minecraftai$sneaky(t);
        }
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException minecraftai$sneaky(Throwable t) throws E {
        throw (E) t;
    }
}
