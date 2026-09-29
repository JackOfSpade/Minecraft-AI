package io.github.zoyluo.minecraftai.memory;

import io.github.zoyluo.minecraftai.log.BotLog;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;

public final class BotMemoryStore {
    public static final BotMemoryStore INSTANCE = new BotMemoryStore();

    private final Map<UUID, BotMemory> memories = new ConcurrentHashMap<>();

    private BotMemoryStore() {
    }

    public BotMemory of(UUID botId) {
        return memories.computeIfAbsent(botId, ignored -> new BotMemory());
    }

    public String saveString(UUID botId) {
        return of(botId).toNbt().toString();
    }

    public void loadString(UUID botId, String snbt) {
        if (snbt == null || snbt.isBlank()) {
            return;
        }
        try {
            CompoundTag root = TagParser.parseCompoundFully(snbt);
            of(botId).load(root);
            BotLog.comm(null, "bot_memory_loaded", "bot_uuid", botId);
        } catch (Exception exception) {
            BotLog.error("bot_memory_load_failed", exception, "bot_uuid", botId);
        }
    }

    public void remove(UUID botId) {
        memories.remove(botId);
    }

    public void clear() {
        memories.clear();
    }
}
