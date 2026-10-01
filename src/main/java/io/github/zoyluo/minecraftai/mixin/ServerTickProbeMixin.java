package io.github.zoyluo.minecraftai.mixin;

import io.github.zoyluo.minecraftai.MinecraftAiMod;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.navigation.NavigationMeasurement;
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
    @Unique
    private long minecraftai$navigationMeasurementStarted;

    @Inject(method = "tickServer(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void minecraftai$onTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        // The measurement helper returns zero outside an explicit P3 capture, so ordinary server
        // ticks pay only one cheap inactive check here. The RETURN hook below then measures the
        // whole MinecraftServer tick rather than just the mod's END_SERVER_TICK callbacks.
        minecraftai$navigationMeasurementStarted = NavigationMeasurement.beginServerTick();
        if (++minecraftai$tickCount % 1200L == 0L) {
            BotLog.lifecycle("mixin_alive", "server_tick", minecraftai$tickCount);
        }
    }

    @Inject(method = "tickServer(Ljava/util/function/BooleanSupplier;)V", at = @At("RETURN"))
    private void minecraftai$afterTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        NavigationMeasurement.endServerTick(minecraftai$navigationMeasurementStarted);
        minecraftai$navigationMeasurementStarted = 0L;
    }
}
