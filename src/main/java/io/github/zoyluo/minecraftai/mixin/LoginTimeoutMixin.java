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
 * how short the join hold was set. Vanilla exposes no setting for this, so it is widened directly:
 * 6000 ticks (5 minutes) comfortably covers even a slow first-time spawn-area generation, while
 * still eventually failing a genuinely broken/hung connection instead of holding it open forever.
 */
@Mixin(ServerLoginNetworkHandler.class)
public abstract class LoginTimeoutMixin {
    @ModifyConstant(method = "method_52421()V", constant = @Constant(intValue = 600))
    private int minecraftai$extendLoginTimeout(int original) {
        return 6000;
    }
}
