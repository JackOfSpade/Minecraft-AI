package io.github.zoyluo.minecraftai.mixin;

import baritone.utils.accessor.IPalettedContainer;
import io.github.zoyluo.minecraftai.baritone.PaletteAccess;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Baritone's {@code FasterWorldScanner} reads a chunk section's raw palette and bit storage instead of asking for every
 * block state. Upstream implements this in {@code baritone.launch.mixins.MixinPalettedContainer}, which is one of the
 * client-only mixins and is not part of this build; this is the same idea for the server. The reflective lookup of the private
 * {@code Data} field is in {@link PaletteAccess}, a lazily initialised holder: this mixin is applied to a vanilla class at
 * start-up whatever the navigation engine is, so nothing here may fail when {@code PalettedContainer} is not what Baritone
 * expects (a failed lookup makes Baritone unavailable instead, see {@link PaletteAccess}).
 */
@Mixin(PalettedContainer.class)
public abstract class BaritonePalettedContainerMixin<T> implements IPalettedContainer<T> {
    @Override
    @SuppressWarnings("unchecked")
    public Palette<T> getPalette() {
        return PaletteAccess.data((PalettedContainer<T>) (Object) this).getPalette();
    }

    @Override
    @SuppressWarnings("unchecked")
    public BitStorage getStorage() {
        return PaletteAccess.data((PalettedContainer<T>) (Object) this).getStorage();
    }
}
