package io.github.zoyluo.minecraftai.mixin;

import net.minecraft.server.network.ServerLoginNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Vanilla force-disconnects any client still in the LOGIN phase after a fixed 600 ticks (30s,
 * "Took too long to log in"), counted by this class's own per-connection tick loop -- a limit the
 * wrapper addon's JoinHoldGate cannot see or extend (its own doc comment covers why). Confirmed by
 * direct log evidence that this fixed budget, not anything held on our side, is the actual
 * bottleneck on a heavily modded worldgen stack: server_started to disconnect landed at ~31s on a
 * fresh world with nothing logged in between (spawn-chunk generation still running) regardless of
 * how short the join hold was set. Vanilla exposes no setting for this, so it is widened directly.
 * <p>
 * First widened to 6000 ticks (5 minutes); confirmed working correctly (the disconnect moved to
 * exactly the new 5-minute mark, not the old 30s one) but still not enough -- this modpack's own
 * "Preparing spawn area" logged 16% and never printed another progress line for the full 5 minutes
 * on a fresh world (Terralith + Tectonic + Amplified Nether + a 38MB structure datapack all
 * generating near spawn at once), while CPU usage confirmed it was still genuinely working, not
 * hung. Widened further to 36000 ticks (30 minutes): effectively no downside for a local
 * singleplayer world (worst case a truly broken connection just waits longer before failing), while
 * comfortably covering even this modpack's slow first-time spawn generation.
 */
@Mixin(ServerLoginNetworkHandler.class)
public abstract class LoginTimeoutMixin {
    @ModifyConstant(method = "tick()V", constant = @Constant(intValue = 600))
    private int minecraftai$extendLoginTimeout(int original) {
        return 36000;
    }
}
