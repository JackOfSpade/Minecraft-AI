package io.github.zoyluo.aibot.task;

import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;

public record Threat(Type type, Severity severity, LivingEntity entity, BlockPos pos) {
    public enum Type {
        LOW_HP,
        HOSTILE,
        DROWNING,
        LAVA,
        FALLING
    }

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH
    }
}
