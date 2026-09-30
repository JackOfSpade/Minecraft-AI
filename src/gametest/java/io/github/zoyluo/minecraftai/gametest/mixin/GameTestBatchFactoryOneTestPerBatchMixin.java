package io.github.zoyluo.minecraftai.gametest.mixin;

import io.github.zoyluo.minecraftai.gametest.GameTestIsolation;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestBatch;
import net.minecraft.gametest.framework.GameTestBatchFactory;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only (GameTest source set): every test runs in a batch of its own, see {@link GameTestIsolation#oneTestPerBatch}. */
@Mixin(GameTestBatchFactory.class)
public abstract class GameTestBatchFactoryOneTestPerBatchMixin {
    @Inject(method = "divideIntoBatches", at = @At("RETURN"), cancellable = true)
    private static void minecraftai$oneTestPerBatch(Collection<Holder.Reference<GameTestInstance>> tests,
            GameTestBatchFactory.TestDecorator decorator, ServerLevel level, CallbackInfoReturnable<List<GameTestBatch>> cir) {
        cir.setReturnValue(GameTestIsolation.oneTestPerBatch(cir.getReturnValue()));
    }
}
