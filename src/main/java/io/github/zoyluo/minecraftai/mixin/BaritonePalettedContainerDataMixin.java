package io.github.zoyluo.minecraftai.mixin;

import baritone.utils.accessor.IPalettedContainer.IData;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Makes {@code PalettedContainer.Data} readable through Baritone's {@link IData} (see BaritonePalettedContainerMixin). */
@Mixin(targets = "net/minecraft/world/level/chunk/PalettedContainer$Data")
public abstract class BaritonePalettedContainerDataMixin<T> implements IData<T> {
    @Accessor
    @Override
    public abstract Palette<T> getPalette();

    @Accessor
    @Override
    public abstract BitStorage getStorage();
}
