package io.github.zoyluo.minecraftai.persist;

public record BotRecord(
        String name,
        String dimension,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String gameMode,
        float health,
        int hunger,
        String inventoryNbt,
        String memoryNbt,
        String ownerUuid,
        /** One of OfflineProfileFactory's 18 default-skin indices; null for records saved before
         *  skin randomization existed, in which case a fresh random one is assigned on restore. */
        Integer skinIndex,
        /** SNBT compound with equipment (armor/offhand), ender chest, hotbar slot, XP, saturation,
         *  status effects, air/fire (see {@link BotPlayerState}); null for records saved before it
         *  existed, which means "nothing to restore". */
        String playerStateNbt,
        /** Versioned JSON with the bot's conversation memory (summary, older lines, recent chat tail; see
         *  {@link io.github.zoyluo.minecraftai.brain.ChatMemory}); null for records saved before it existed
         *  or when there was nothing to keep, which means "nothing to restore". */
        String conversationMemoryJson,
        /** "x,y,z" of the floor cell of the pillar the bot stood on when it was saved (see {@link TowerBaseCodec}),
         *  so a restart does not leave it stranded up there: the restored bot takes the tower down first, whether
         *  or not the task that built it is restored. Null when the bot stood on none and for records saved
         *  before it existed, which means "no tower"; a value that does not decode is refused, never guessed at. */
        String towerBase
) {
    /** Pre-{@code towerBase} shape, kept so older callers and tests still compile. */
    public BotRecord(String name, String dimension, double x, double y, double z, float yaw, float pitch,
                     String gameMode, float health, int hunger, String inventoryNbt, String memoryNbt,
                     String ownerUuid, Integer skinIndex, String playerStateNbt, String conversationMemoryJson) {
        this(name, dimension, x, y, z, yaw, pitch, gameMode, health, hunger, inventoryNbt, memoryNbt,
                ownerUuid, skinIndex, playerStateNbt, conversationMemoryJson, null);
    }

    /** Pre-{@code conversationMemoryJson} shape, kept so older callers and tests still compile. */
    public BotRecord(String name, String dimension, double x, double y, double z, float yaw, float pitch,
                     String gameMode, float health, int hunger, String inventoryNbt, String memoryNbt,
                     String ownerUuid, Integer skinIndex, String playerStateNbt) {
        this(name, dimension, x, y, z, yaw, pitch, gameMode, health, hunger, inventoryNbt, memoryNbt,
                ownerUuid, skinIndex, playerStateNbt, null, null);
    }

    /** Pre-{@code playerStateNbt} shape, kept so older callers and tests still compile. */
    public BotRecord(String name, String dimension, double x, double y, double z, float yaw, float pitch,
                     String gameMode, float health, int hunger, String inventoryNbt, String memoryNbt,
                     String ownerUuid, Integer skinIndex) {
        this(name, dimension, x, y, z, yaw, pitch, gameMode, health, hunger, inventoryNbt, memoryNbt,
                ownerUuid, skinIndex, null, null, null);
    }
}
