package dev.spawnbotswrapper.inhabitants.adapter;

import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Takes a bot out of the world WITHOUT killing it (removal is not death): no item drops, no XP orbs, no death message,
 * no statistics or advancements a death would trigger.
 * <p>
 * PvP BOT's own removal runs {@code clear <name>} and then {@code player <name> kill}, and HeroBot's kill is a real
 * vanilla death ({@code hurtServer} with unbounded damage): the bot drops its items and XP orbs (level x 7, at most 100)
 * and a death message goes out. Removing bots that way would turn every removal into a loot and XP source. Instead the bot
 * is emptied first (inventory, armor, offhand, the cursor, the ender chest, experience) and then leaves the way any
 * player leaves: the connection's {@code onDisconnect}, which is vanilla's own leave path
 * ({@code PlayerList.remove}: the player data is saved, empty, and the entity is removed at once). PvP BOT's list is
 * cleaned up afterwards by its own {@code removeBot}, which then finds no player to clear or kill.
 * <p>
 * Server thread only.
 */
final class BotRemoval {
    private BotRemoval() {
    }

    /** Empties everything the bot carries and every point of experience, so that nothing can drop or be saved. */
    static void empty(ServerPlayer bot) {
        bot.getInventory().clearContent();
        bot.getEnderChestInventory().clearContent();
        if (bot.containerMenu != null) {
            bot.containerMenu.setCarried(ItemStack.EMPTY);
        }
        bot.experienceLevel = 0;
        bot.experienceProgress = 0.0f;
        bot.totalExperience = 0;
    }

    /**
     * Makes the bot leave the server like a player who logs out: synchronous, so the entity is gone when this returns.
     *
     * @return whether the bot is no longer in the player list
     */
    static boolean disconnect(MinecraftServer server, ServerPlayer bot) {
        if (bot.connection == null) {
            server.getPlayerList().remove(bot);
        } else {
            bot.connection.onDisconnect(new DisconnectionDetails(Component.literal("Removed")));
        }
        return server.getPlayerList().getPlayer(bot.getUUID()) == null;
    }
}
