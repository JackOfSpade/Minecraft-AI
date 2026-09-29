package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.coordination.IdleCoordinator;
import io.github.zoyluo.minecraftai.coordination.MiningAssistCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.goal.GoalExecutor;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.observe.TpsGuard;
import net.minecraft.server.MinecraftServer;

public final class BotTickCoordinator {
    public static final BotTickCoordinator INSTANCE = new BotTickCoordinator();

    private BotTickCoordinator() {
    }

    public void tick(MinecraftServer server) {
        int tick = server.getTickCount();
        io.github.zoyluo.minecraftai.action.ContainerAction.tickPersistence(server);
        TpsGuard guard = TpsGuard.INSTANCE;
        boolean runDanger = tick % guard.dangerScanInterval() == 0;
        boolean runBackground = tick % guard.scanInterval() == 0;
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            // SAFE-1: the environmental safety net runs first; if the bot is currently self-rescuing (drowning/lava), it takes over this tick, skipping the other checks.
            if (NavSafetyNet.INSTANCE.tickBot(server, bot)) {
                continue;
            }
            StuckWatcher.INSTANCE.tickBot(server, bot);
            boolean handled = runDanger && DangerWatcher.INSTANCE.scanBot(server, bot);
            // Mining assist (shadow sensing): never consumes the tick, never throws, one static check when off.
            MiningAssistCoordinator.INSTANCE.tickBot(server, bot, handled);
            if (!handled && GoalExecutor.INSTANCE.tickBot(server, bot)) {
                continue;
            }
            if (!handled && runBackground) {
                // Layer 3: also auto-equips better armor from the inventory during normal ticks. Paused while a
                // player has the bot's inventory screen open: they are editing its equipment by hand, and the
                // bot re-equipping under their cursor made it re-run (and re-log) every scan.
                if (!io.github.zoyluo.minecraftai.inventory.BotInventoryScreenHandler.isScreenOpen(bot)) {
                    io.github.zoyluo.minecraftai.action.EquipAction.equipBestArmor(bot);
                }
                if (!StorageJanitor.INSTANCE.tickBot(bot)) {
                    IdleCoordinator.INSTANCE.tickBot(bot);
                }
            }
        }
    }
}
