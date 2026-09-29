package io.github.zoyluo.minecraftai.log;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.task.TaskManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auditable inventory-change log, sampled at the same cadence as {@code DiagnosticLogger}'s
 * {@code diag_snapshot} (see {@link #SNAPSHOT_INTERVAL}) and driven from the same per-tick call
 * site (see the {@code DiagnosticLogger.INSTANCE.tick} caller in {@code MinecraftAiMod}). Unlike
 * {@code diag_snapshot} -- which prints the whole inventory every sample, truncated in practice --
 * this emits one line only when something actually changed, naming exactly what moved. Combined
 * with {@code gather_summary}/{@code gather_unit} and capability-decision logging, this lets a
 * player's "did you really gather that" question be answered from the log instead of trusted on
 * faith; see docs/LOGGING.md "Auditing a gather".
 */
public final class InventoryAudit {
    public static final InventoryAudit INSTANCE = new InventoryAudit();

    private static final int SNAPSHOT_INTERVAL = 40; // matches DiagnosticLogger's diag_snapshot cadence
    private static final int MAX_LISTED_ITEMS = 8; // bounds the line length when many items change at once

    private final Map<UUID, Map<Item, Integer>> lastCounts = new ConcurrentHashMap<>();
    /** botId -> (viewerId -> viewer name), fed by BotInventoryScreenHandler's open/close hooks. */
    private final Map<UUID, Map<UUID, String>> viewers = new ConcurrentHashMap<>();

    private InventoryAudit() {
    }

    /** Called when a player opens a bot's inventory screen. */
    public void viewerOpened(UUID botId, UUID viewerId, String viewerName) {
        if (botId == null || viewerId == null) {
            return;
        }
        viewers.computeIfAbsent(botId, id -> new ConcurrentHashMap<>()).put(viewerId, viewerName);
    }

    /** Called when a player closes a bot's inventory screen. */
    public void viewerClosed(UUID botId, UUID viewerId) {
        if (botId == null || viewerId == null) {
            return;
        }
        Map<UUID, String> open = viewers.get(botId);
        if (open == null) {
            return;
        }
        open.remove(viewerId);
        if (open.isEmpty()) {
            viewers.remove(botId);
        }
    }

    public void clear(AIPlayerEntity bot) {
        lastCounts.remove(bot.getUUID());
        viewers.remove(bot.getUUID());
    }

    public void clearAll() {
        lastCounts.clear();
        viewers.clear();
    }

    public void tick(MinecraftServer server) {
        int tick = server.getTickCount();
        if (tick % SNAPSHOT_INTERVAL != 0) {
            return;
        }
        if (server.isShutdown()) {
            // Entities unload normally while the server stops; a diff against that teardown would
            // misreport ordinary despawn as a burst of "lost" items. clearTransient/forgetBot
            // already clear this bot's baseline on the same shutdown path.
            return;
        }
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            sampleAndLog(bot);
        }
    }

    private void sampleAndLog(AIPlayerEntity bot) {
        UUID botId = bot.getUUID();
        Map<Item, Integer> current = currentCounts(bot);
        Map<Item, Integer> previous = lastCounts.put(botId, current);
        if (previous == null) {
            return; // First sample after (re)spawn: nothing to diff against yet.
        }
        List<Map.Entry<String, Integer>> gained = new ArrayList<>();
        List<Map.Entry<String, Integer>> lost = new ArrayList<>();
        // TreeMap over the union of both samples' item ids gives deterministic, alphabetized output.
        Map<String, Item> byId = new TreeMap<>();
        for (Item item : previous.keySet()) {
            byId.put(BuiltInRegistries.ITEM.getKey(item).toString(), item);
        }
        for (Item item : current.keySet()) {
            byId.put(BuiltInRegistries.ITEM.getKey(item).toString(), item);
        }
        for (Map.Entry<String, Item> entry : byId.entrySet()) {
            int before = previous.getOrDefault(entry.getValue(), 0);
            int after = current.getOrDefault(entry.getValue(), 0);
            int delta = after - before;
            if (delta > 0) {
                gained.add(Map.entry(entry.getKey(), delta));
            } else if (delta < 0) {
                lost.add(Map.entry(entry.getKey(), delta));
            }
        }
        if (gained.isEmpty() && lost.isEmpty()) {
            return;
        }
        BotLog.action(bot, "inventory_delta",
                "task", TaskManager.INSTANCE.status(bot).name(),
                "viewer", viewerNameFor(botId),
                "gained", formatSide(gained),
                "lost", formatSide(lost));
    }

    private String viewerNameFor(UUID botId) {
        Map<UUID, String> open = viewers.get(botId);
        if (open == null || open.isEmpty()) {
            return "none";
        }
        return open.values().iterator().next();
    }

    private static Map<Item, Integer> currentCounts(AIPlayerEntity bot) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Pure formatting, package-visible so it can be unit-tested without a Minecraft bootstrap:
     * turns a list of (item id, signed delta) pairs into one bounded, comma-joined string such as
     * {@code minecraft:spruce_log+1,minecraft:oak_log+2}, e.g. for the {@code gained}/{@code lost}
     * fields of {@code inventory_delta}. Deltas keep their sign, so a "lost" list (negative
     * deltas) reads as {@code minecraft:torch-1} with no extra formatting needed. Returns "-" for
     * an empty list, and truncates to {@link #MAX_LISTED_ITEMS} entries plus a "+N more" tail so a
     * line with many changed items still stays bounded.
     */
    static String formatSide(List<Map.Entry<String, Integer>> entries) {
        if (entries.isEmpty()) {
            return "-";
        }
        int shown = Math.min(entries.size(), MAX_LISTED_ITEMS);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(',');
            }
            Map.Entry<String, Integer> entry = entries.get(i);
            int delta = entry.getValue();
            sb.append(entry.getKey()).append(delta >= 0 ? "+" : "").append(delta);
        }
        int remaining = entries.size() - shown;
        if (remaining > 0) {
            sb.append(",+").append(remaining).append("more");
        }
        return sb.toString();
    }
}
