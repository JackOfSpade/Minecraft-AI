package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;

/**
 * Writes a {@link BotProfile} onto a live bot entity and keeps the marker that says it was done. An interface
 * so {@link McBotGateway} can be tested without real player entities; {@link ProfileApplier} is the
 * implementation.
 */
public interface ProfileApplication {

    /**
     * Applies loadout and vitals. Never throws: what could not be applied is reported in the result.
     *
     * @param clearInventoryFirst wipe the inventory before filling it; only ever true for a fresh addon bot
     */
    Result apply(ServerPlayerEntity bot, BotProfile profile, boolean clearInventoryFirst);

    /** True when the addon has already applied a profile to this entity (the marker survives restarts). */
    boolean isMarked(ServerPlayerEntity bot);

    /** Records that a profile was applied. */
    void mark(ServerPlayerEntity bot);

    /**
     * @param loadoutApplied the inventory section ran to completion (individual items may still have been
     *                       skipped, see the warnings)
     * @param vitalsApplied  the attribute / health / hunger section ran to completion
     * @param warnings       everything that was skipped or adjusted, one line each
     */
    record Result(boolean loadoutApplied, boolean vitalsApplied, List<String> warnings) {
        public Result {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
