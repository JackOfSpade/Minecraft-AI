package io.github.zoyluo.minecraftai.log;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.goal.GoalExecutor;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.task.TaskManager;
import io.github.zoyluo.minecraftai.task.TaskStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detailed diagnostic logging for test playback/replay. Does not modify any existing entity/task classes:
 * - Every SNAPSHOT_INTERVAL ticks, considers one rich snapshot per bot, written only when {@link DiagnosticSnapshotGate} sees a change or a heartbeat is due (position/health/hunger/mode/on-ground/fall/air/task+phase+progress/held item/inventory/path and mining state).
 * - Every tick, compares against the previous tick and catches key events: health loss, sudden fall-distance spikes, disappearing from the world/death (this is exactly what's needed to investigate "Bob suddenly vanished").
 * All calls happen on the main thread within the server tick (G2 thread-safe).
 */
public final class DiagnosticLogger {
    public static final DiagnosticLogger INSTANCE = new DiagnosticLogger();

    private static final int SNAPSHOT_INTERVAL = 40; // one rich snapshot every 2 seconds
    private boolean enabled = true;

    private final Map<UUID, Sample> last = new ConcurrentHashMap<>();
    private final DiagnosticSnapshotGate snapshotGate = new DiagnosticSnapshotGate();

    private DiagnosticLogger() {
    }

    public void clear(AIPlayerEntity bot) {
        last.remove(bot.getUUID());
        snapshotGate.clear(bot.getUUID());
    }

    public void clearAll() {
        last.clear();
        snapshotGate.clearAll();
    }

    public void tick(MinecraftServer server) {
        if (!enabled) {
            return;
        }
        // While the server is stopping, entities get unloaded normally (isAlive flips to false, removed from all()),
        // so skip death/vanish detection here to avoid misreporting a "server shutdown unload" as diag_bot_died / diag_bot_vanished.
        if (server.isShutdown()) {
            last.clear();
            return;
        }
        int tick = server.getTickCount();

        Map<UUID, Sample> current = new LinkedHashMap<>();
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            UUID id = bot.getUUID();
            Sample now = sampleOf(bot);
            current.put(id, now);

            Sample prev = last.get(id);
            detectEvents(bot, prev, now);

            if (tick % SNAPSHOT_INTERVAL == 0) {
                snapshot(bot, now, tick);
            }
        }

        // Detect "vanished": present on the previous tick but missing from all() on this tick -- this is the core clue for Bob suddenly disappearing
        for (Map.Entry<UUID, Sample> entry : last.entrySet()) {
            if (!current.containsKey(entry.getKey())) {
                Sample gone = entry.getValue();
                BotLog.lifecycle("diag_bot_vanished",
                        "name", gone.name,
                        "last_pos", gone.x + "," + gone.y + "," + gone.z,
                        "last_hp", fmt(gone.health),
                        "last_food", gone.food,
                        "last_task", gone.taskName + "/" + gone.taskPhase,
                        "on_ground", gone.onGround,
                        "fall", fmt(gone.fallDistance),
                        "removed", gone.removed,
                        "alive", gone.alive,
                        "note", "present on the previous tick, no longer in AIPlayerManager.all() on this tick; check the last_* fields to determine whether it died, fell into the void, or was removed");
            }
        }

        last.clear();
        last.putAll(current);
    }

    private void detectEvents(AIPlayerEntity bot, Sample prev, Sample now) {
        if (prev == null) {
            return;
        }
        // Health loss
        if (now.health < prev.health - 0.01F) {
            BotLog.danger(bot, "diag_health_drop",
                    "from", fmt(prev.health),
                    "to", fmt(now.health),
                    "delta", fmt(prev.health - now.health),
                    "pos", now.x + "," + now.y + "," + now.z,
                    "task", now.taskName + "/" + now.taskPhase,
                    "fall", fmt(now.fallDistance),
                    "in_lava", now.inLava,
                    "submerged", now.submerged,
                    "air", now.air);
        }
        // Alive -> !alive. Entity.isAlive() also goes false for a plain removal (chunk unload,
        // despawn, the player disconnecting/quitting the world) that never touched health -- e.g.
        // Moss was removed ~30ms before server_stopping when the user quit the world, at 20/20 hp,
        // and that used to get misreported as diag_bot_died. Classify it first; only a real death
        // gets diag_bot_died, everything else gets the lifecycle event below.
        if (prev.alive && !now.alive) {
            if (isRealDeath(now.health, now.removed, now.removalReason)) {
                BotLog.danger(bot, "diag_bot_died",
                        "pos", now.x + "," + now.y + "," + now.z,
                        "task", now.taskName + "/" + now.taskPhase,
                        "fall", fmt(now.fallDistance),
                        "in_lava", now.inLava,
                        "air", now.air);
            } else {
                BotLog.lifecycle(bot, "diag_bot_removed",
                        "pos", now.x + "," + now.y + "," + now.z,
                        "hp", fmt(now.health),
                        "reason", now.removalReason == null ? "unknown" : now.removalReason.name(),
                        "task", now.taskName + "/" + now.taskPhase);
            }
        }
        // Large fall
        if (now.fallDistance > 4.0F && now.fallDistance > prev.fallDistance + 2.0F) {
            BotLog.danger(bot, "diag_falling",
                    "fall", fmt(now.fallDistance),
                    "pos", now.x + "," + now.y + "," + now.z,
                    "on_ground", now.onGround,
                    "task", now.taskName + "/" + now.taskPhase);
        }
        // Sudden Y drop (suspected fall into a hole/void)
        if (prev.y - now.y > 3) {
            BotLog.danger(bot, "diag_y_drop",
                    "from_y", prev.y,
                    "to_y", now.y,
                    "pos", now.x + "," + now.y + "," + now.z,
                    "task", now.taskName + "/" + now.taskPhase);
        }
    }

    private void snapshot(AIPlayerEntity bot, Sample s, int tick) {
        String goal = GoalExecutor.INSTANCE.describeActiveGoal(bot);
        String step = GoalExecutor.INSTANCE.describeActiveStep(bot);
        DiagnosticSnapshotGate.Decision decision = snapshotGate.decide(bot.getUUID(), readingOf(s, goal, step), tick);
        if (!decision.emit()) {
            return;
        }
        BotLog.action(bot, "diag_snapshot",
                // why this line was written and how many were skipped since the previous one (see DiagnosticSnapshotGate)
                "reason", decision.reason(),
                "skipped", decision.suppressedSinceLast(),
                // —— status ——
                "pos", s.x + "," + s.y + "," + s.z,
                "hp", fmt(s.health) + "/" + fmt(s.maxHealth),
                "food", s.food,
                "air", s.air,
                "mode", s.mode,
                "on_ground", s.onGround,
                "in_lava", s.inLava,
                "submerged", s.submerged,
                "fall", fmt(s.fallDistance),
                "light", s.light,
                // —— goal ——
                "goal", goal,
                "step", step,
                // —— task ——
                "task", s.taskName,
                "task_state", s.taskState,
                "task_phase", s.taskPhase,
                "task_progress", fmt((float) s.taskProgress),
                "path_idle", s.pathIdle,
                "mining_idle", s.miningIdle,
                // —— surroundings ——
                "nearby", scanNearby(bot),
                // —— inventory ——
                "held", s.held,
                "inv", s.inventory);
    }

    private static DiagnosticSnapshotGate.Reading readingOf(Sample s, String goal, String step) {
        String state = s.taskName + "|" + s.taskState + "|" + s.taskPhase + "|" + goal + "|" + step
                + "|" + s.air + "|" + s.mode + "|" + s.inLava + "|" + s.submerged;
        String loadout = fmt(s.health) + "|" + s.food + "|" + s.held + "|" + s.inventory;
        return new DiagnosticSnapshotGate.Reading(s.x, s.y, s.z, state, loadout);
    }

    // Living entities within 24 blocks (key for hunting/combat diagnostics): animal count (+ nearest type@distance) / hostile count (+ nearest type@distance).
    // Only scanned once per rich snapshot (every SNAPSHOT_INTERVAL), not included in the per-tick Sample, to avoid scanning entities every tick and hurting TPS.
    private static String scanNearby(AIPlayerEntity bot) {
        try {
            CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "diagnostic_nearby");
            AABB box = bot.getBoundingBox().inflate(24.0D);
            List<LivingEntity> ents = bot.level().getEntitiesOfClass(
                    LivingEntity.class, box,
                    e -> e.isAlive() && e != bot && ObservableWorldQuery.canObserveEntity(bot, e));
            int animals = 0;
            int hostiles = 0;
            LivingEntity nearAnimal = null;
            LivingEntity nearHostile = null;
            double da = Double.MAX_VALUE;
            double dh = Double.MAX_VALUE;
            for (LivingEntity e : ents) {
                double d = bot.distanceTo(e);
                if (e instanceof Monster) {
                    hostiles++;
                    if (d < dh) {
                        dh = d;
                        nearHostile = e;
                    }
                } else if (e instanceof AgeableMob) {
                    animals++;
                    if (d < da) {
                        da = d;
                        nearAnimal = e;
                    }
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("animals=").append(animals);
            if (nearAnimal != null) {
                sb.append('(').append(typeId(nearAnimal)).append('@').append((int) da).append(')');
            }
            sb.append(" hostiles=").append(hostiles);
            if (nearHostile != null) {
                sb.append('(').append(typeId(nearHostile)).append('@').append((int) dh).append(')');
            }
            return sb.toString();
        } catch (RuntimeException ignored) {
            return "?";
        }
    }

    private static String typeId(LivingEntity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    private Sample sampleOf(AIPlayerEntity bot) {
        Sample s = new Sample();
        s.name = bot.getGameProfile().name();
        s.x = (int) Math.floor(bot.getX());
        s.y = (int) Math.floor(bot.getY());
        s.z = (int) Math.floor(bot.getZ());
        s.yaw = bot.getYRot();
        s.health = bot.getHealth();
        s.maxHealth = bot.getMaxHealth();
        s.food = bot.getFoodData().getFoodLevel();
        s.air = bot.getAirSupply();
        s.onGround = bot.onGround();
        s.fallDistance = (float) bot.fallDistance;
        s.alive = bot.isAlive();
        s.removed = bot.isRemoved();
        s.removalReason = bot.getRemovalReason();
        try {
            s.mode = bot.gameMode.getGameModeForPlayer().getSerializedName();
        } catch (RuntimeException ignored) {
            s.mode = "?";
        }
        try {
            s.submerged = bot.isUnderWater();
            BlockPos at = bot.blockPosition();
            s.inLava = bot.level().getBlockState(at).getFluidState().is(net.minecraft.tags.FluidTags.LAVA)
                    || bot.level().getBlockState(at.below()).getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
            s.light = bot.level().getMaxLocalRawBrightness(at);
        } catch (RuntimeException ignored) {
            // Keep defaults if world access fails; other fields are unaffected
        }

        TaskStatus status = TaskManager.INSTANCE.status(bot);
        s.taskName = status.name();
        s.taskState = String.valueOf(status.state());
        s.taskProgress = status.progress();
        s.taskPhase = extractPhase(status.description());

        try {
            s.pathIdle = bot.getActionPack().isPathExecutorIdle();
            s.miningIdle = bot.getActionPack().isMiningIdle();
        } catch (RuntimeException ignored) {
            // ignore
        }

        ItemStack main = bot.getMainHandItem();
        s.held = main.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(main.getItem()) + "x" + main.getCount();
        s.inventory = inventorySummary(bot);
        return s;
    }

    private static String inventorySummary(AIPlayerEntity bot) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty()) {
                counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return "empty";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(e.getKey()).append('x').append(e.getValue());
        }
        return sb.toString();
    }

    private static String extractPhase(String description) {
        if (description == null) {
            return "";
        }
        int idx = description.indexOf("phase=");
        if (idx < 0) {
            return "";
        }
        int end = description.indexOf(' ', idx);
        return end < 0 ? description.substring(idx + 6) : description.substring(idx + 6, end);
    }

    /**
     * A real death is zero/negative health, or an explicit KILLED removal (mirrors
     * DangerWatcher.scanBot's own death check: {@code health <= 0 || removalReason == KILLED}).
     * Any other removal reason (DISCARDED, UNLOADED_TO_CHUNK, the player quitting the world, ...)
     * is a plain removal, not a death, even though Entity.isAlive() is false for it too.
     */
    static boolean isRealDeath(float health, boolean removed, Entity.RemovalReason removalReason) {
        if (health <= 0.0F) {
            return true;
        }
        return removed && removalReason == Entity.RemovalReason.KILLED;
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static final class Sample {
        String name = "";
        int x;
        int y;
        int z;
        float yaw;
        float health;
        float maxHealth;
        int food;
        int air;
        boolean onGround;
        float fallDistance;
        boolean alive = true;
        boolean removed;
        Entity.RemovalReason removalReason;
        String mode = "?";
        boolean submerged;
        boolean inLava;
        int light;
        String taskName = "idle";
        String taskState = "";
        double taskProgress;
        String taskPhase = "";
        boolean pathIdle = true;
        boolean miningIdle = true;
        String held = "empty";
        String inventory = "empty";
    }
}
