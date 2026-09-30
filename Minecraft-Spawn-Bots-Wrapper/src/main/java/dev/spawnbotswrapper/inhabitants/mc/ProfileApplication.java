package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.engine.BotGateway;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.store.BotSnapshot;
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
     * Captures the live state of a bot (never a non-bot): null when the entity is not a bot, dead, or on any failure.
     * Warnings about slots that could not be saved are appended to {@code warnings}.
     */
    BotSnapshot capture(ServerPlayer bot, List<String> warnings);

    /**
     * Writes a snapshot back onto a bot: with {@code inventory} the whole inventory and selected slot, then health,
     * hunger, effects, experience, fire and air; without it (a bot that kept its inventory across a restart) only
     * health, hunger and the effects the bot lacks. Nothing is dressed from a profile. Never throws.
     */
    Result restore(ServerPlayer bot, BotSnapshot snapshot, boolean inventory);

    /**
     * Makes the bot an ordinary survival player: survival game mode and no creative-style ability, and none of this
     * addon's attribute modifiers (older versions added permanent ones). Never for a non-bot. Returns what was wrong.
     */
    BotGateway.StateFixes enforceVanilla(ServerPlayer bot);

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
