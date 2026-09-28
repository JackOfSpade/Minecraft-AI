package io.github.zoyluo.minecraftai.mixin;

import io.github.zoyluo.minecraftai.MinecraftAiMod;
import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class ServerTickProbeMixin {
    @Unique
    private long minecraftai$tickCount;

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void minecraftai$onTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        if (++minecraftai$tickCount % 1200L == 0L) {
            BotLog.lifecycle("mixin_alive", "server_tick", minecraftai$tickCount);
        }
    }
}
