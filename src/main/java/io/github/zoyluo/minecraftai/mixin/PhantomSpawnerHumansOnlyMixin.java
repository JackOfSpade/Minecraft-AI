package io.github.zoyluo.minecraftai.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.zoyluo.minecraftai.network.PlayerKind;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Phantoms only spawn around human players. Vanilla picks phantom targets from every non-spectator
 * {@link ServerPlayer} of the world by their "time since last rest" statistic. Bots never sleep, so that
 * statistic grows forever and phantoms would spawn around a bot and attack the humans near it. This
 * hands the spawner's player loop a list without bots (see {@link PlayerKind}); humans keep exact vanilla
 * behaviour and no statistic is touched.
 */
@Mixin(PhantomSpawner.class)
public abstract class PhantomSpawnerHumansOnlyMixin {
    @ModifyExpressionValue(method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;players()Ljava/util/List;"))
    private List<ServerPlayer> minecraftai$skipBots(List<ServerPlayer> players) {
        return PlayerKind.humansOnly(players);
    }
}
