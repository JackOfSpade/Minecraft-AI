package io.github.zoyluo.minecraftai.network.payload;

import io.github.zoyluo.minecraftai.MinecraftAiMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record BotSnapshotS2C(
        String botName,
        float health,
        float maxHealth,
        int food,
        int x,
        int y,
        int z,
        String taskName,
        String taskState,
        float progress,
        boolean brainBusy,
        int promptTokens,
        int completionTokens,
        String goalTitle,
        String goalCurrentStep,
        int goalCurrentStepIndex,
        int goalTotalSteps,
        List<String> goalSteps,
        long goalResultSequence,
        String goalResultStatus,
        String goalResultSummary,
        int goalResultMatched,
        int goalResultRequired,
        boolean missionPaused,
        int executionStackDepth,
        String operatingProfile,
        List<String> effectiveCapabilities,
        boolean manualMode,
        boolean memoryToolsEnabled,
        boolean verboseReportsEnabled,
        List<ItemEntry> inventory,
        List<ItemEntry> equipment
) implements CustomPacketPayload {
    public static final Type<BotSnapshotS2C> ID = new Type<>(Identifier.fromNamespaceAndPath(MinecraftAiMod.MOD_ID, "bot_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BotSnapshotS2C> CODEC = StreamCodec.ofMember(BotSnapshotS2C::write, BotSnapshotS2C::new);

    private BotSnapshotS2C(RegistryFriendlyByteBuf buf) {
        this(
                buf.readUtf(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readFloat(),
                buf.readBoolean(),
                buf.readInt(),
                buf.readInt(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readInt(),
                buf.readInt(),
                readStrings(buf),
                buf.readLong(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readInt(),
                buf.readInt(),
                buf.readBoolean(),
                buf.readInt(),
                buf.readUtf(),
                readStrings(buf),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                readInventory(buf),
                readInventory(buf));
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(botName);
        buf.writeFloat(health);
        buf.writeFloat(maxHealth);
        buf.writeInt(food);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeUtf(taskName);
        buf.writeUtf(taskState);
        buf.writeFloat(progress);
        buf.writeBoolean(brainBusy);
        buf.writeInt(promptTokens);
        buf.writeInt(completionTokens);
        buf.writeUtf(goalTitle);
        buf.writeUtf(goalCurrentStep);
        buf.writeInt(goalCurrentStepIndex);
        buf.writeInt(goalTotalSteps);
        buf.writeInt(goalSteps.size());
        for (String step : goalSteps) {
            buf.writeUtf(step);
        }
        buf.writeLong(goalResultSequence);
        buf.writeUtf(goalResultStatus);
        buf.writeUtf(goalResultSummary);
        buf.writeInt(goalResultMatched);
        buf.writeInt(goalResultRequired);
        buf.writeBoolean(missionPaused);
        buf.writeInt(executionStackDepth);
        buf.writeUtf(operatingProfile);
        buf.writeInt(effectiveCapabilities.size());
        for (String capability : effectiveCapabilities) {
            buf.writeUtf(capability);
        }
        buf.writeBoolean(manualMode);
        buf.writeBoolean(memoryToolsEnabled);
        buf.writeBoolean(verboseReportsEnabled);
        buf.writeInt(inventory.size());
        for (ItemEntry entry : inventory) {
            buf.writeUtf(entry.itemId());
            buf.writeInt(entry.count());
            buf.writeInt(entry.slot());
        }
        buf.writeInt(equipment.size());
        for (ItemEntry entry : equipment) {
            buf.writeUtf(entry.itemId());
            buf.writeInt(entry.count());
            buf.writeInt(entry.slot());
        }
    }

    private static List<String> readStrings(RegistryFriendlyByteBuf buf) {
        int size = buf.readInt();
        List<String> values = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            values.add(buf.readUtf());
        }
        return values;
    }

    private static List<ItemEntry> readInventory(RegistryFriendlyByteBuf buf) {
        int size = buf.readInt();
        List<ItemEntry> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            entries.add(new ItemEntry(buf.readUtf(), buf.readInt(), buf.readInt()));
        }
        return entries;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public record ItemEntry(String itemId, int count, int slot) {
    }
}
