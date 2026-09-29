package io.github.zoyluo.minecraftai.inventory;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Opens the exact bot inventory handler with a deliberately short title. */
public final class BotInventoryScreenFactory implements MenuProvider {
    private static final int MAX_TITLE_NAME_LENGTH = 10;

    private final AIPlayerEntity bot;
    private final UUID viewerId;

    public BotInventoryScreenFactory(AIPlayerEntity bot, Player viewer) {
        this.bot = bot;
        this.viewerId = viewer.getUUID();
    }

    @Override
    public Component getDisplayName() {
        return Component.literal(shortName(bot.getGameProfile().name()) + " gear");
    }

    @Override
    public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
        return new BotInventoryScreenHandler(syncId, playerInventory, bot, viewerId);
    }

    private static String shortName(String name) {
        if (name == null || name.isBlank()) {
            return "Bot";
        }
        if (name.length() <= MAX_TITLE_NAME_LENGTH) {
            return name;
        }
        return name.substring(0, MAX_TITLE_NAME_LENGTH - 1) + "…";
    }
}
