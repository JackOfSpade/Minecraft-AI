package io.github.zoyluo.minecraftai.inventory;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;

import java.util.UUID;

/** Opens the exact bot inventory handler with a deliberately short title. */
public final class BotInventoryScreenFactory implements NamedScreenHandlerFactory {
    private static final int MAX_TITLE_NAME_LENGTH = 10;

    private final AIPlayerEntity bot;
    private final UUID viewerId;

    public BotInventoryScreenFactory(AIPlayerEntity bot, PlayerEntity viewer) {
        this.bot = bot;
        this.viewerId = viewer.getUuid();
    }

    @Override
    public Text getDisplayName() {
        return Text.literal(shortName(bot.getGameProfile().name()) + " gear");
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
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
