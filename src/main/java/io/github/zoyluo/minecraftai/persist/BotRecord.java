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
        Integer skinIndex
) {
}
