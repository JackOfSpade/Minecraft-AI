package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogFields;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class MovementAction {
    private MovementAction() {
    }

    public static ActionResult setForward(AIPlayerEntity player, float value) {
        player.getActionPack().setForward(value);
        return ActionResult.SUCCESS;
    }

    public static ActionResult setStrafing(AIPlayerEntity player, float value) {
        player.getActionPack().setStrafing(value);
        return ActionResult.SUCCESS;
    }

    public static ActionResult setSneaking(AIPlayerEntity player, boolean sneaking) {
        player.getActionPack().setSneaking(sneaking);
        return ActionResult.SUCCESS;
    }

    public static ActionResult setSprinting(AIPlayerEntity player, boolean sprinting) {
        player.getActionPack().setSprinting(sprinting);
        return ActionResult.SUCCESS;
    }

    public static ActionResult startJump(AIPlayerEntity player) {
        player.getActionPack().setJumping(true);
        return ActionResult.SUCCESS;
    }

    public static ActionResult jumpOnce(AIPlayerEntity player) {
        player.getActionPack().jumpOnce();
        BotLog.action(player, "jump");
        return ActionResult.SUCCESS;
    }

    public static ActionResult startWalkTo(AIPlayerEntity player, Vec3d target) {
        BotLog.action(player, "walk_to", "target", target);
        return player.getActionPack().startWalkTo(target);
    }

    public static ActionResult startPathTo(AIPlayerEntity player, BlockPos goal) {
        BotLog.action(player, "path_to", "goal", LogFields.pos(goal));
        return player.getActionPack().startPathTo(goal);
    }

    public static ActionResult stopAll(AIPlayerEntity player) {
        player.getActionPack().stopAll();
        BotLog.action(player, "stop_all");
        return ActionResult.SUCCESS;
    }
}
