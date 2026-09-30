package dev.spawnbotswrapper.inhabitants.mc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Vanilla's rule for eating, enforced on inhabitants: food can only be eaten while the food level is below 20, unless
 * the item is always edible (golden apples, chorus fruit, ...). PvP BOT starts eating with {@code startUsingItem}
 * directly, which skips that rule, so a bot at a full food bar could eat ordinary food that a player cannot. Once per
 * server tick, after PvP BOT's own tick, an inhabitant that is in the middle of eating such food is stopped through the
 * vanilla {@code stopUsingItem} (nothing is consumed). Potions and milk are not food and are never touched.
 * <p>
 * Cost: a scan of the player list that tests {@code isUsingItem} first, so a tick where nobody eats costs a few field
 * reads per player. Only entities carrying the addon's marker are inhabitants; a PvP BOT bot of somebody else is left alone.
 */
public final class EatGate {
    private final Logger log;
    /** Bots for which the first stop has been logged; later ones are silent (it can repeat every few ticks). */
    private final Set<UUID> logged = new HashSet<>();

    public EatGate(Logger log) {
        this.log = log;
    }

    /** Once per server tick, after PvP BOT's tick. */
    public void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isUsingItem() || !player.getTags().contains(ProfileApplier.MARKER_TAG)
                    || !VanillaRules.mustStopEating(player)) {
                continue;
            }
            ItemStack food = player.getUseItem();
            player.stopUsingItem();
            if (logged.add(player.getUUID())) {
                log.info("Inhabitant {} tried to eat {} at a full food bar, which a player cannot do; it was stopped "
                        + "(only the first time per bot is logged)", player.getGameProfile().name(), food.getItem());
            }
        }
    }

    /** Server stopped: the next world starts with a clean slate. */
    public void reset() {
        logged.clear();
    }
}
