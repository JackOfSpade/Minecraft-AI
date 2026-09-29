package io.github.zoyluo.minecraftai.task;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Rejects a target only until either the observed topology changes or a bounded TTL expires. */
final class ObsidianTargetMemory {
    private record Stamp(int topologyEpoch, int expiresAtTick) {
    }

    private final Map<BlockPos, Stamp> rejected = new HashMap<>();

    void reject(BlockPos pos, int topologyEpoch, int now, int ttl) {
        if (pos != null) {
            rejected.put(pos.immutable(),
                    new Stamp(Math.max(0, topologyEpoch), now + Math.max(1, ttl)));
        }
    }

    boolean isRejected(BlockPos pos, int topologyEpoch, int now) {
        Stamp stamp = rejected.get(pos);
        if (stamp == null) {
            return false;
        }
        if (stamp.topologyEpoch() != topologyEpoch || now >= stamp.expiresAtTick()) {
            rejected.remove(pos);
            return false;
        }
        return true;
    }

    void clear() {
        rejected.clear();
    }
}
