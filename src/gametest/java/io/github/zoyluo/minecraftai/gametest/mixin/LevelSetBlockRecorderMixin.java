package io.github.zoyluo.minecraftai.gametest.mixin;

import io.github.zoyluo.minecraftai.gametest.GameTestLightSync;
import io.github.zoyluo.minecraftai.gametest.GameTestWorldRestorer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Test-only (GameTest source set): tells {@link GameTestWorldRestorer} the state a block had before the first change of a batch, and
 * {@link GameTestLightSync} which chunks this tick's light updates are in.
 */
@Mixin(Level.class)
public abstract class LevelSetBlockRecorderMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void minecraftai$recordOriginalState(BlockPos pos, BlockState state, int flags, int recursionLeft,
            CallbackInfoReturnable<Boolean> cir) {
        GameTestLightSync.blockChanging((Level) (Object) this, pos);
        GameTestWorldRestorer.beforeSetBlock((Level) (Object) this, pos);
    }
}
