package io.github.zoyluo.minecraftai.task;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

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
