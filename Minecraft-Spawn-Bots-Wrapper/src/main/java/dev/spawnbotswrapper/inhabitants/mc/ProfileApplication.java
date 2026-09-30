package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;

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
    Result apply(ServerPlayer bot, BotProfile profile, boolean clearInventoryFirst);

    /** True when the addon has already applied a profile to this entity (the marker survives restarts). */
    boolean isMarked(ServerPlayer bot);

    /** Records that a profile was applied. */
    void mark(ServerPlayer bot);

    /**
     * Removes every ender pearl from a bot's inventory (never for a non-bot) and returns how many pearls were
     * removed; 0 when none, when the entity is not a bot, or on any failure. Nothing else is changed.
     */
    int stripEnderPearls(ServerPlayer bot);

    /**
     * Removes every enchantment listed in {@code profiles.disabledEnchantments} from every stack a bot carries or
     * wears (never for a non-bot), keeping the items and their other enchantments. Returns one description per
     * removal (for example {@code minecraft:piercing (crossbow)}); empty when nothing was removed, when the entity is not
     * a bot, or on any failure.
     */
    List<String> stripDisabledEnchantments(ServerPlayer bot);

    /**
     * @param loadoutApplied the inventory section ran to completion (individual items may still have been
     *                       skipped, see the warnings)
     * @param vitalsApplied  the attribute / health / hunger section ran to completion
     * @param warnings       everything that was skipped or adjusted, one line each
     * @param pearlsRemoved  ender pearls the dressing took out of the inventory (see {@link #stripEnderPearls})
     * @param enchantmentsRemoved  disabled enchantments the dressing took off items (see {@link #stripDisabledEnchantments})
     */
    record Result(boolean loadoutApplied, boolean vitalsApplied, List<String> warnings, int pearlsRemoved,
                  List<String> enchantmentsRemoved) {
        public Result {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            enchantmentsRemoved = enchantmentsRemoved == null ? List.of() : List.copyOf(enchantmentsRemoved);
        }

        public Result(boolean loadoutApplied, boolean vitalsApplied, List<String> warnings) {
            this(loadoutApplied, vitalsApplied, warnings, 0, List.of());
        }
    }
}
