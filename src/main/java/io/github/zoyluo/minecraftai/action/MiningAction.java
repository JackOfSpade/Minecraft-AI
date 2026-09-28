package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public final class MiningAction {
    private MiningAction() {
    }

    public static ActionResult startMining(AIPlayerEntity player, BlockPos pos, Direction face) {
        return player.getActionPack().startMining(pos, face);
    }

    public static ActionResult stopMining(AIPlayerEntity player) {
        player.getActionPack().stopMining();
        return ActionResult.SUCCESS;
    }
}
