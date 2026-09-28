package io.github.zoyluo.minecraftai.perception;

import com.google.gson.Gson;

import java.util.List;
import java.util.Map;

public record PerceptionSnapshot(
        SelfState self,
        TaskInfo task,
        Highlights highlights,
        List<NearbyBlock> blocks,
        List<NearbyEntity> entities,
        List<NearbyItem> items,
        TimeInfo time
) {
    private static final Gson GSON = new Gson();

    public String toJson() {
        return GSON.toJson(this);
    }

    public record SelfState(
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            float hp,
            int hunger,
            String holdingItem,
            Map<String, Integer> inventoryCount,
            Equipment equipment
        ) {
    }

    /** Current worn and held gear, including durability when the item can take damage. */
    public record Equipment(
            EquippedItem mainHand,
            EquippedItem offHand,
            EquippedItem head,
            EquippedItem chest,
            EquippedItem legs,
            EquippedItem feet
    ) {
    }

    /** An empty slot is minecraft:air x0; remainingDurability is -1 for a non-damageable item. */
    public record EquippedItem(String item, int count, int remainingDurability) {
    }

    public record NearbyBlock(String type, int x, int y, int z, double distance) {
    }

    public record Highlights(
            List<NearbyBlock> nearest_tree,
            List<NearbyBlock> nearest_stone,
            List<NearbyBlock> nearest_ore,
            List<NearbyBlock> nearest_water,
            List<NearbyBlock> nearest_furnace,
            List<NearbyBlock> nearest_chest,
            List<NearbyBlock> nearest_bed,
            List<NearbyBlock> nearest_crafting_table,
            List<NearbyEntity> nearest_hostile
    ) {
    }

    public record NearbyEntity(String type, double x, double y, double z, double distance, boolean hostile, float hp) {
    }

    public record NearbyItem(String type, double x, double y, double z) {
    }

    public record TaskInfo(String name, String state, double progress, int elapsedTicks, String description, String failureReason) {
    }

    public record TimeInfo(long worldTime, boolean isDay, int light, boolean isRaining, boolean isThundering) {
    }
}
