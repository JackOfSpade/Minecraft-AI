package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * A narrow bridge to PvP BOT's own per-tick combat controller.
 *
 * <p>Minecraft-AI players are ordinary {@link ServerPlayer}s, but are not registered in PvP BOT's
 * bot manager. PvP BOT's combat tick itself does not require that registration, so this bridge
 * gives it the factual vanilla aggro target and lets its own aiming, weapons, healing and movement
 * logic drive the existing entity. Nothing is swapped or respawned, and the bridge has no
 * compile-time dependency on the optional mod.
 */
final class PvpBotCombatBrain {
    static final PvpBotCombatBrain INSTANCE = new PvpBotCombatBrain();

    private static final String COMBAT_CLASS = "org.stepan1411.pvp_bot.bot.BotCombat";
    private static final String NAVIGATION_CLASS = "org.stepan1411.pvp_bot.bot.BotNavigation";
    /** The wrapper's fixed PvP BOT engagement horizon; this is not an owner-distance leash. */
    private static final double DIRECT_COMBAT_RANGE = 32.0D;
    private static final double OUT_OF_RANGE_CHASE_SPEED = 1.0D;

    private final Set<UUID> activeBots = ConcurrentHashMap.newKeySet();
    private final Set<UUID> reportedFailures = ConcurrentHashMap.newKeySet();
    private volatile Bridge bridge;
    private volatile boolean resolved;

    private PvpBotCombatBrain() {
    }

    boolean available() {
        return bridge() != null;
    }

    boolean isActive(AIPlayerEntity bot) {
        return activeBots.contains(bot.getUUID());
    }

    /**
     * Gives PvP BOT the vanilla target immediately. {@code lastAttacker} is deliberately written
     * before its update tick: the target's current Mob#getTarget fact is enough; no damage event,
     * line-of-sight proof, or fake PvP BOT registration is needed.
     */
    boolean tick(MinecraftServer server, AIPlayerEntity bot, LivingEntity aggressor) {
        Bridge current = bridge();
        if (current == null || aggressor == null || !aggressor.isAlive()) {
            return false;
        }
        try {
            String name = bot.getGameProfile().name();
            Object state = current.getState.invoke(null, name);
            if (state == null) {
                ShieldGuard.releasePvpBrainMeleeBlock(bot);
                return false;
            }
            // Beyond PvP BOT's own engagement horizon, use its navigation primitive only. Calling
            // update there would let a server's optional auto-target setting pick an unrelated
            // player before the injected aggressor comes back in range.
            if (bot.distanceToSqr(aggressor) > DIRECT_COMBAT_RANGE * DIRECT_COMBAT_RANGE) {
                ShieldGuard.releasePvpBrainMeleeBlock(bot);
                if (current.moveTowardPosition == null) {
                    return false;
                }
                current.moveTowardPosition.invoke(null, bot, aggressor.position(), OUT_OF_RANGE_CHASE_SPEED);
                activeBots.add(bot.getUUID());
                return true;
            }
            ShieldGuard.preparePvpBrainMeleeBlock(bot, aggressor);
            current.lastAttacker.set(state, aggressor);
            if (aggressor instanceof ServerPlayer player) {
                String desiredTarget = player.getGameProfile().name();
                Object forced = current.forcedTargetName.get(state);
                // PvP BOT's update can remember a revenge target without actually forcing it.
                // The inhabitants wrapper correctly vetoes such unconfirmed player strikes, which
                // otherwise leaves a companion circling an attacker without ever landing a hit.
                // Force the factual aggressor once per target change so both systems agree on
                // combat authority, then let the regular combat tick handle weapons and movement.
                if (!(forced instanceof String forcedName) || !forcedName.equalsIgnoreCase(desiredTarget)) {
                    current.setTarget.invoke(null, name, desiredTarget);
                }
            }
            if (current.lastAttackTime != null) {
                // PvP BOT compares this field with System.currentTimeMillis(), not Minecraft
                // server ticks. Supplying a tick count makes the forced revenge memory look
                // decades old as soon as a target moves beyond its normal engage distance.
                current.lastAttackTime.setLong(state, System.currentTimeMillis());
            }
            current.update.invoke(null, bot, server);

            // The injected aggressor has priority inside the range. Refuse a mismatched target
            // rather than letting any PvP BOT auto-target setting turn on the player or a bystander.
            if (current.target != null && current.target.get(state) != aggressor) {
                current.clearTarget.invoke(null, name);
                ShieldGuard.releasePvpBrainMeleeBlock(bot);
                activeBots.remove(bot.getUUID());
                return false;
            }
            activeBots.add(bot.getUUID());
            return true;
        } catch (Throwable failure) {
            ShieldGuard.releasePvpBrainMeleeBlock(bot);
            activeBots.remove(bot.getUUID());
            if (reportedFailures.add(bot.getUUID())) {
                BotLog.error(bot, "pvp_bot_combat_bridge_failed", failure,
                        "target", aggressor.getType().toString());
            }
            return false;
        }
    }

    /** Stop only a combat session started by this bridge, leaving genuine PvP BOT players alone. */
    void release(AIPlayerEntity bot) {
        ShieldGuard.releasePvpBrainMeleeBlock(bot);
        if (!activeBots.remove(bot.getUUID())) {
            return;
        }
        Bridge current = bridge();
        if (current == null) {
            return;
        }
        String name = bot.getGameProfile().name();
        try {
            current.clearTarget.invoke(null, name);
        } catch (Throwable ignored) {
            // Optional integration must never make a safety transition fail.
        }
        if (current.removeNavigationState != null) {
            try {
                current.removeNavigationState.invoke(null, name);
            } catch (Throwable ignored) {
                // Same best-effort cleanup policy as clearTarget.
            }
        }
    }

    void clearAll() {
        activeBots.clear();
        reportedFailures.clear();
    }

    private Bridge bridge() {
        if (resolved) {
            return bridge;
        }
        synchronized (this) {
            if (resolved) {
                return bridge;
            }
            try {
                Class<?> combat = Class.forName(COMBAT_CLASS);
                Method getState = combat.getMethod("getState", String.class);
                Method clearTarget = combat.getMethod("clearTarget", String.class);
                Method setTarget = combat.getMethod("setTarget", String.class, String.class);
                Method update = findUpdate(combat);
                Class<?> stateType = getState.getReturnType();
                Field lastAttacker = stateType.getField("lastAttacker");
                if (!Entity.class.isAssignableFrom(lastAttacker.getType())) {
                    throw new IllegalStateException("BotCombat.CombatState.lastAttacker is not an Entity");
                }
                Field lastAttackTime = optionalLongField(stateType, "lastAttackTime");
                Field target = requiredEntityField(stateType, "target");
                Field forcedTargetName = requiredStringField(stateType, "forcedTargetName");

                Method moveToward = null;
                Method removeState = null;
                try {
                    Class<?> navigation = Class.forName(NAVIGATION_CLASS);
                    moveToward = findMoveTowardPosition(navigation);
                    removeState = optionalStaticMethod(navigation, "removeState", String.class);
                } catch (ClassNotFoundException ignored) {
                    // Combat still works; only the beyond-32 navigation hand-off is unavailable.
                }
                bridge = new Bridge(getState, clearTarget, setTarget, update, lastAttacker, lastAttackTime,
                        target, forcedTargetName, moveToward, removeState);
            } catch (Throwable ignored) {
                // PvP BOT is optional. The existing safety fallback remains authoritative when it
                // is not installed or has changed its public bridge contract.
                bridge = null;
            }
            resolved = true;
            return bridge;
        }
    }

    private static Method findUpdate(Class<?> combat) throws NoSuchMethodException {
        for (Method method : combat.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals("update")
                    && Modifier.isStatic(method.getModifiers())
                    && parameters.length == 2
                    && parameters[0].isAssignableFrom(ServerPlayer.class)
                    && parameters[1].isAssignableFrom(MinecraftServer.class)) {
                return method;
            }
        }
        throw new NoSuchMethodException("BotCombat.update(ServerPlayer, MinecraftServer)");
    }

    private static Method findMoveTowardPosition(Class<?> navigation) {
        for (Method method : navigation.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals("moveTowardPosition")
                    && Modifier.isStatic(method.getModifiers())
                    && parameters.length == 3
                    && parameters[0].isAssignableFrom(ServerPlayer.class)
                    && parameters[1].isAssignableFrom(Vec3.class)
                    && (parameters[2] == double.class || parameters[2] == Double.class)) {
                return method;
            }
        }
        return null;
    }

    private static Method optionalStaticMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            Method method = type.getMethod(name, parameters);
            return Modifier.isStatic(method.getModifiers()) ? method : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Field optionalLongField(Class<?> type, String name) {
        try {
            Field field = type.getField(name);
            return field.getType() == long.class ? field : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Field requiredEntityField(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getField(name);
        if (!Entity.class.isAssignableFrom(field.getType())) {
            throw new IllegalStateException("BotCombat.CombatState." + name + " is not an Entity");
        }
        return field;
    }

    private static Field requiredStringField(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getField(name);
        if (field.getType() != String.class) {
            throw new IllegalStateException("BotCombat.CombatState." + name + " is not a String");
        }
        return field;
    }

    private record Bridge(Method getState,
                          Method clearTarget,
                          Method setTarget,
                          Method update,
                          Field lastAttacker,
                          Field lastAttackTime,
                          Field target,
                          Field forcedTargetName,
                          Method moveTowardPosition,
                          Method removeNavigationState) {
    }
}
