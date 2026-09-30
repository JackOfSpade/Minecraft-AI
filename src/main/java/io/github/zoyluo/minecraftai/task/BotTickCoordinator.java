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
            // Realistic perception (docs/PERCEPTION.md): what this bot has noticed, read once per tick before anything asks. It never
            // consumes the tick and never throws.
            io.github.zoyluo.minecraftai.perception.CreatureSenses.INSTANCE.tickBot(server, bot);
            // Nearly broken gear: one chat line per item and crossing, instantly, never an interruption (behaviour.gear.durabilityWarnings).
            io.github.zoyluo.minecraftai.action.DurabilityWarnings.tickBot(bot);
            // SAFE-1: the environmental safety net runs first; if the bot is currently self-rescuing (drowning/lava), it takes over this tick, skipping the other checks.
            if (NavSafetyNet.INSTANCE.tickBot(server, bot)) {
                continue;
            }
            StuckWatcher.INSTANCE.tickBot(server, bot);
            boolean handled = runDanger && DangerWatcher.INSTANCE.scanBot(server, bot);
            // Mining assist (shadow sensing): never consumes the tick, never throws, one static check when off.
            MiningAssistCoordinator.INSTANCE.tickBot(server, bot, handled);
            if (!handled && runBackground
                    && !io.github.zoyluo.minecraftai.inventory.BotInventoryScreenHandler.isScreenOpen(bot)) {
                // Layer 3: auto-equips armor (the best piece per slot, the next best the moment one breaks, see GearValue) and the offhand
                // (the best shield, else a totem, see OffhandPolicy). It runs before the mission executor, which owns the tick for the
                // whole length of a mission, so a broken piece or a popped totem is replaced during a mission too. Paused while a player
                // has the bot's inventory screen open: they are editing its equipment by hand, and the bot re-equipping under their
                // cursor made it re-run (and re-log) every scan.
                io.github.zoyluo.minecraftai.action.EquipAction.autoEquipArmor(bot);
                io.github.zoyluo.minecraftai.action.OffhandPolicy.apply(bot);
            }
            if (!handled && GoalExecutor.INSTANCE.tickBot(server, bot)) {
                continue;
            }
            if (!handled && runBackground) {
                if (!StorageJanitor.INSTANCE.tickBot(bot)) {
                    IdleCoordinator.INSTANCE.tickBot(bot);
                }
            }
        }
        // A bot that is gone (despawned, unloaded) leaves no vibration listener and no memory behind.
        io.github.zoyluo.minecraftai.perception.CreatureSenses.INSTANCE.endTick(AIPlayerManager.INSTANCE.all());
    }
}
