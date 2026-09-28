package io.github.zoyluo.minecraftai.log;

import net.minecraft.util.math.BlockPos;

public final class LogFields {
    private LogFields() {
    }

    public static String pos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
