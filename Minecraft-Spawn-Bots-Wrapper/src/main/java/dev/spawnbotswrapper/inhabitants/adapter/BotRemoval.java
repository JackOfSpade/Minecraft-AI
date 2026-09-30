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

    /**
     * What a bot carried when it was emptied: kept so that a removal that then fails leaves the bot exactly as it was, not
     * a live bot that has lost its items (an emptied bot that stays in the world would be a silent item and XP sink).
     */
    record Carried(java.util.List<ItemStack> inventory, java.util.List<ItemStack> enderChest, java.util.List<ItemStack> craftingGrid,
                   ItemStack cursor, int experienceLevel, float experienceProgress, int totalExperience) {
    }

    /**
     * Empties everything the bot carries and every point of experience, so that nothing can drop or be saved: inventory
     * (main, armor, offhand), ender chest, the cursor, the 2x2 crafting grid and its result slot (a player who leaves has the
     * grid handed back to him by the menu, and that would drop the items), experience. Returns what was taken, for
     * {@link #restore}.
     */
    static Carried empty(ServerPlayer bot) {
        Carried carried = new Carried(copyOf(bot.getInventory()), copyOf(bot.getEnderChestInventory()),
                copyOf(bot.inventoryMenu.getCraftSlots()),
                bot.containerMenu == null ? ItemStack.EMPTY : bot.containerMenu.getCarried().copy(),
                bot.experienceLevel, bot.experienceProgress, bot.totalExperience);
        try {
            bot.getInventory().clearContent();
            bot.getEnderChestInventory().clearContent();
            bot.inventoryMenu.getCraftSlots().clearContent();
            bot.inventoryMenu.getResultSlot().set(ItemStack.EMPTY);
            if (bot.containerMenu != null) {
                bot.containerMenu.setCarried(ItemStack.EMPTY);
            }
            bot.experienceLevel = 0;
            bot.experienceProgress = 0.0f;
            bot.totalExperience = 0;
        } catch (RuntimeException | Error e) {
            restore(bot, carried); // half emptied is the worst state: put back what was taken, then let the caller see the failure
            throw e;
        }
        return carried;
    }

    /**
     * Puts back what {@link #empty} took, for a bot whose removal failed (it is still in the world): its items, its cursor and
     * its experience are exactly as they were.
     */
    static void restore(ServerPlayer bot, Carried carried) {
        putBack(bot.getInventory(), carried.inventory());
        putBack(bot.getEnderChestInventory(), carried.enderChest());
        putBack(bot.inventoryMenu.getCraftSlots(), carried.craftingGrid());
        if (bot.containerMenu != null) {
            bot.containerMenu.setCarried(carried.cursor().copy());
        }
        bot.experienceLevel = carried.experienceLevel();
        bot.experienceProgress = carried.experienceProgress();
        bot.totalExperience = carried.totalExperience();
    }

    private static java.util.List<ItemStack> copyOf(net.minecraft.world.Container container) {
        java.util.List<ItemStack> out = new java.util.ArrayList<>(container.getContainerSize());
        for (int i = 0; i < container.getContainerSize(); i++) {
            out.add(container.getItem(i).copy());
        }
        return out;
    }

    private static void putBack(net.minecraft.world.Container container, java.util.List<ItemStack> stacks) {
        for (int i = 0; i < stacks.size() && i < container.getContainerSize(); i++) {
            container.setItem(i, stacks.get(i).copy());
        }
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
