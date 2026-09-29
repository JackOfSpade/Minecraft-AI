package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StuckWatcher {
    public static final StuckWatcher INSTANCE = new StuckWatcher();

    private final Map<UUID, Sample> samples = new ConcurrentHashMap<>();

    private StuckWatcher() {
    }

    public void tick(MinecraftServer server) {
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            tickBot(server, bot);
        }
    }

    public void tickBot(MinecraftServer server, AIPlayerEntity bot) {
        int now = server.getTickCount();
        int window = MinecraftAiConfig.get().watchdog().stuckWindowTicks();
        Optional<Task> active = TaskManager.INSTANCE.getActive(bot);
        if (active.isEmpty() || active.get().state() != TaskState.RUNNING || active.get().isWaiting()) {
            samples.remove(bot.getUUID());
            return;
        }

        Task task = active.get();
        Sample current = new Sample(bot.blockPosition().immutable(), task.progress(), inventoryTotal(bot), now);
        Sample previous = samples.get(bot.getUUID());
        if (previous == null || previous.changed(current)) {
            samples.put(bot.getUUID(), current);
            return;
        }

        if (now - previous.sinceTick() < window) {
            return;
        }

        String reason = "stuck:" + task.name();
        TaskManager.INSTANCE.abort(bot);
        TaskManager.INSTANCE.recordFailure(bot, task.name(), reason, now);
        samples.remove(bot.getUUID());
        BotLog.warn(LogCategory.TASK, bot, "task_stuck_aborted",
                "name", task.name(),
                "reason", reason,
                "window_ticks", window,
                "progress", task.progress(),
                "pos", current.pos().toShortString());
    }

    public boolean reset(AIPlayerEntity bot) {
        return samples.remove(bot.getUUID()) != null;
    }

    public void clearAll() {
        samples.clear();
    }

    private static int inventoryTotal(AIPlayerEntity bot) {
        int total = 0;
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            total += stack.getCount();
        }
        total += bot.getItemBySlot(EquipmentSlot.OFFHAND).getCount();
        return total;
    }

    private record Sample(BlockPos pos, double progress, int inventoryTotal, int sinceTick) {
        private boolean changed(Sample other) {
            return !pos.equals(other.pos)
                    || Math.abs(progress - other.progress) > 0.0001D
                    || inventoryTotal != other.inventoryTotal;
        }
    }
}
