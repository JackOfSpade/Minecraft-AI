package io.github.zoyluo.minecraftai.mixin;

import io.github.zoyluo.minecraftai.network.PlayerKind;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;

/**
 * Only human players take part in the sleep vote. Vanilla counts every non-spectator
 * {@link ServerPlayer} in the world, so our awake bots (and other mods' fake players) would
 * otherwise stop a human from ever skipping the night at the default 100% threshold. Bots never sleep
 * (that behaviour was removed); this hands vanilla a list without them, so the gamerule percentage,
 * the "X/Y players sleeping" message and the morning wake-up all behave as vanilla among humans.
 */
@Mixin(SleepStatus.class)
public abstract class SleepManagerHumansOnlyMixin {
    @ModifyVariable(method = "update", at = @At("HEAD"), argsOnly = true)
    private List<ServerPlayer> minecraftai$countHumansOnly(List<ServerPlayer> players) {
        return PlayerKind.humansOnly(players);
    }

    @ModifyVariable(method = "areEnoughDeepSleeping", at = @At("HEAD"), argsOnly = true)
    private List<ServerPlayer> minecraftai$resetTimeForHumansOnly(List<ServerPlayer> players) {
        return PlayerKind.humansOnly(players);
    }
}
