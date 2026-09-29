package io.github.zoyluo.minecraftai.client;

import io.github.zoyluo.minecraftai.network.payload.BotCommandC2S;
import io.github.zoyluo.minecraftai.network.payload.BotItemMoveC2S;
import io.github.zoyluo.minecraftai.network.payload.BotTeleportC2S;
import io.github.zoyluo.minecraftai.network.payload.SetOptionC2S;
import io.github.zoyluo.minecraftai.network.payload.SubscribeBotC2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public final class BotCommandBridge {
    private BotCommandBridge() {
    }

    public static boolean hasPermission() {
        Minecraft client = Minecraft.getInstance();
        // Owner authorization is server-side and depends on the selected Bot; the client cannot
        // infer it from OP level. This probe only means that a player connection exists.
        return client.player != null;
    }

    public static void subscribe(String botName, boolean subscribe) {
        if (ClientPlayNetworking.canSend(SubscribeBotC2S.ID)) {
            ClientPlayNetworking.send(new SubscribeBotC2S(botName, subscribe));
        }
    }

    public static void chat(String botName, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        BotClientState.INSTANCE.addTranscript("user", text.trim());
        if (ClientPlayNetworking.canSend(BotCommandC2S.ID)) {
            ClientPlayNetworking.send(new BotCommandC2S(botName, "chat", text.trim(), "", 1));
        } else {
            sendChatMessage("@" + botName + " " + text.trim());
        }
    }

    public static void command(String botName, String action, String arg1, String arg2, int count) {
        if (ClientPlayNetworking.canSend(BotCommandC2S.ID)) {
            ClientPlayNetworking.send(new BotCommandC2S(clean(botName), action, clean(arg1), clean(arg2), count));
            return;
        }
        sendCommand(fallbackCommand(clean(botName), action, clean(arg1), clean(arg2), Math.max(1, count)));
    }

    private static String fallbackCommand(String botName, String action, String arg1, String arg2, int count) {
        return switch (action) {
            case "move" -> "minecraftai task assign " + botName + " move " + arg1;
            case "mine" -> "minecraftai task assign " + botName + " mine " + arg1 + " " + count;
            case "craft" -> "minecraftai task assign " + botName + " craft " + arg1 + " " + count;
            case "smelt" -> "minecraftai task assign " + botName + " smelt " + arg1 + " " + arg2 + " " + count;
            case "eat" -> "minecraftai task assign " + botName + " eat";
            case "abort" -> "minecraftai task abort " + botName;
            case "pause" -> "minecraftai task pause " + botName;
            case "resume" -> "minecraftai task resume " + botName;
            case "reset" -> "minecraftai brain reset " + botName;
            default -> "minecraftai status";
        };
    }

    private static void sendCommand(String command) {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() != null) {
            client.getConnection().sendCommand(command);
        }
    }

    /** Moves items between the owner and their own AI; the server always re-verifies owner/OP status. */
    public static void moveItem(String botName, int direction, int slot, int amount) {
        if (ClientPlayNetworking.canSend(BotItemMoveC2S.ID)) {
            ClientPlayNetworking.send(new BotItemMoveC2S(clean(botName), direction, slot, amount));
        }
    }

    /** Teleport. direction: BotTeleportC2S.TO_AI (player -> near AI) / RECALL_AI (AI -> near player). */
    public static void teleport(String botName, int direction) {
        if (ClientPlayNetworking.canSend(BotTeleportC2S.ID)) {
            ClientPlayNetworking.send(new BotTeleportC2S(clean(botName), direction));
        }
    }

    public static void setOption(String botName, String key, boolean value) {
        if (ClientPlayNetworking.canSend(SetOptionC2S.ID)) {
            ClientPlayNetworking.send(new SetOptionC2S(botName == null ? "" : botName, key, value));
        }
    }

    private static void sendChatMessage(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() != null) {
            client.getConnection().sendChat(message);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
