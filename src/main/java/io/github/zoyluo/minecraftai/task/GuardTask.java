package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.util.BlockPosText;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

public final class GuardTask extends AbstractTask {
    private static final double GUARD_RADIUS = 10.0D;
    private static final double RETURN_DISTANCE = 5.0D;
    /** Sustained loss of sight (2.5 s) ends an engagement, like CombatTask's own grace window. */
    private static final int LOST_SIGHT_LIMIT = 50;
    /**
     * After a lost-sight disengage the same target is left alone this long (10 s), unless it hurts the
     * bot or its owner: a visible-but-unstrikable target (behind glass, across a gap) must not cycle
     * engage / walk up / disengage / return forever.
     */
    private static final int LOST_SIGHT_COOLDOWN_TICKS = 200;

    private enum Phase {
        WATCH,
        APPROACH,
        STRIKE,
        REPOSITION,
        RETURN
    }

    private final String targetPlayerName;
    private final BlockPos fixedPoint;
    private Phase phase = Phase.WATCH;
    private LivingEntity target;
    private BlockPos guardPoint;
    private int repositionTicks;
    private int lostSightTicks;
    private java.util.UUID cooldownTargetId;
    private int cooldownUntilElapsed;
    private boolean waiting;

    public GuardTask(BlockPos point, String targetPlayerName) {
        this.fixedPoint = point == null ? null : point.immutable();
        this.targetPlayerName = targetPlayerName == null ? "" : targetPlayerName.trim();
    }

    public static GuardTask point(BlockPos point) {
        return new GuardTask(point, "");
    }

    public static GuardTask player(String playerName) {
        return new GuardTask(null, playerName);
    }

    @Override
    public String name() {
        return "guard";
    }

    @Override
    public String describe() {
        return "Guarding " + BlockPosText.compactOrElse(currentGuardPoint(), "owner") + " phase=" + phase + (waiting ? " waiting" : "");
    }

    @Override
    public double progress() {
        return phase == Phase.WATCH ? 0.5D : 0.75D;
    }

    @Override
    public boolean isWaiting() {
        return waiting;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        guardPoint = fixedPoint == null ? bot.blockPosition().immutable() : fixedPoint;
        CombatCore.equipMelee(bot);
        phase = Phase.WATCH;
        cooldownTargetId = null;
        cooldownUntilElapsed = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        BlockPos point = resolveGuardPoint(bot);
        if (point == null) {
            bot.getActionPack().stopAll();
            waiting = true;
            if (elapsed % 200 == 1) {
                BrainCoordinator.INSTANCE.sendPanelChat(bot, "bot", "The guard target is offline or in another dimension, so I will stand watch here.");
            }
            return;
        }
        guardPoint = point;
        waiting = false;
        switch (phase) {
            case WATCH -> watch(bot);
            case APPROACH -> approach(bot);
            case STRIKE -> strike(bot);
            case REPOSITION -> reposition(bot);
            case RETURN -> returnToGuardPoint(bot);
        }
    }

    private void watch(AIPlayerEntity bot) {
        target = CombatCore.nearestHostileAround(bot, guardPoint, GUARD_RADIUS,
                entity -> !coolingDown(bot, entity)).orElse(null);
        if (target != null) {
            // This task never completes/fails (a persistent watch), so combat episodes would
            // otherwise leave zero trace -- no way to tell, after the fact, whether/when/against
            // what the guard actually fought.
            BotLog.danger(bot, "guard_engage", "target", target.getType().toString(),
                    "pos", target.blockPosition().toShortString());
            CombatCore.equipMelee(bot);
            phase = Phase.APPROACH;
            CombatCore.startApproach(bot, target);
            return;
        }
        if (bot.blockPosition().distSqr(guardPoint) > RETURN_DISTANCE * RETURN_DISTANCE) {
            phase = Phase.RETURN;
            bot.getActionPack().startPathTo(guardPoint);
            return;
        }
        bot.getActionPack().stopMovement();
        waiting = true;
    }

    /**
     * Ends the engagement, returning true, when the target is gone or dead, is no longer a legal
     * melee target (a never-melee threat such as a creeper or an angry enderman, or a mob that is
     * calm again), or has been out of sight (behind a wall) for longer than a momentary occlusion.
     * The guard never keeps swinging at, or chasing, something it cannot legally hit.
     */
    private boolean disengageIfInvalid(AIPlayerEntity bot, String phaseName) {
        String reason = null;
        if (target == null) {
            reason = "target_gone";
        } else if (!target.isAlive()) {
            reason = "target_dead";
        } else if (CombatCore.isMeleeForbiddenThreat(target)) {
            reason = "melee_forbidden";
        } else if (!CombatCore.hostileTo(bot, target)) {
            reason = "not_hostile";
        } else if (CombatCore.hasLineOfSight(bot, target)) {
            lostSightTicks = 0;
        } else if (++lostSightTicks > LOST_SIGHT_LIMIT) {
            reason = "lost_sight";
        }
        if (reason == null) {
            return false;
        }
        BotLog.danger(bot, "guard_disengage", "phase", phaseName, "reason", reason);
        if ("lost_sight".equals(reason) && target != null) {
            cooldownTargetId = target.getUUID();
            cooldownUntilElapsed = elapsed + LOST_SIGHT_COOLDOWN_TICKS;
        }
        bot.getActionPack().stopAll();
        target = null;
        lostSightTicks = 0;
        phase = Phase.RETURN;
        return true;
    }

    /** A target dropped for lost sight stays ignored for a while, unless it has since hurt the bot or its owner. */
    private boolean coolingDown(AIPlayerEntity bot, LivingEntity entity) {
        return elapsed < cooldownUntilElapsed
                && entity.getUUID().equals(cooldownTargetId)
                && !CombatCore.hasHurtBotOrOwner(bot, entity);
    }

    private void approach(AIPlayerEntity bot) {
        if (disengageIfInvalid(bot, "APPROACH")) {
            return;
        }
        CombatCore.lookAt(bot, target);
        if (CombatCore.canStrikeNow(bot, target)) {
            bot.getActionPack().stopAll();
            phase = Phase.STRIKE;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            CombatCore.startApproach(bot, target);
        }
    }

    private void strike(AIPlayerEntity bot) {
        if (disengageIfInvalid(bot, "STRIKE")) {
            return;
        }
        if (bot.distanceTo(target) > CombatCore.ATTACK_RANGE || !CombatCore.canStrikeNow(bot, target)) {
            phase = Phase.APPROACH;
            CombatCore.startApproach(bot, target);
            return;
        }
        if (CombatCore.strikeIfReady(bot, target)) {
            repositionTicks = 8;
            phase = Phase.REPOSITION;
        }
    }

    private void reposition(AIPlayerEntity bot) {
        if (disengageIfInvalid(bot, "REPOSITION")) {
            return;
        }
        CombatCore.lookAt(bot, target);
        bot.getActionPack().setStrafing(CombatCore.safeStrafeInput(
                bot, elapsed % 40 < 20 ? 0.45F : -0.45F));
        repositionTicks--;
        if (repositionTicks <= 0) {
            bot.getActionPack().stopMovement();
            phase = Phase.STRIKE;
        }
    }

    private void returnToGuardPoint(AIPlayerEntity bot) {
        if (bot.blockPosition().distSqr(guardPoint) <= 4.0D) {
            bot.getActionPack().stopAll();
            phase = Phase.WATCH;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            bot.getActionPack().startPathTo(guardPoint);
        }
    }

    private BlockPos resolveGuardPoint(AIPlayerEntity bot) {
        if (!targetPlayerName.isBlank()) {
            ServerPlayer player = bot.level().getServer().getPlayerList().getPlayerByName(targetPlayerName);
            return player != null && player.level() == bot.level() ? player.blockPosition().immutable() : null;
        }
        if (fixedPoint != null) {
            return fixedPoint;
        }
        Optional<ServerPlayer> owner = AIPlayerManager.INSTANCE.ownerOf(bot)
                .map(uuid -> bot.level().getServer().getPlayerList().getPlayer(uuid));
        return owner.filter(player -> player.level() == bot.level())
                .map(player -> player.blockPosition().immutable())
                .orElse(guardPoint);
    }

    private BlockPos currentGuardPoint() {
        return guardPoint == null ? fixedPoint : guardPoint;
    }
}
