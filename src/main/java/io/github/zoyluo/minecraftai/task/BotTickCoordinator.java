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
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            boolean runDanger = tick % guard.dangerScanInterval(bot.getUUID()) == 0;
            boolean runBackground = tick % guard.scanInterval(bot.getUUID()) == 0;
            // Realistic perception (docs/PERCEPTION.md): what this bot has noticed, read once per tick before anything asks. It never
            // consumes the tick and never throws.
            io.github.zoyluo.minecraftai.perception.CreatureSenses.INSTANCE.tickBot(server, bot);
            // Route evidence is earned by genuine bot/linked-owner sight lines. It is collected
            // before the goal executor can request a fresh path this tick.
            io.github.zoyluo.minecraftai.perception.SharedWorldSight.tickBot(bot);
            // Nearly broken gear: one chat line per item and crossing, instantly, never an interruption (behaviour.gear.durabilityWarnings).
            io.github.zoyluo.minecraftai.action.DurabilityWarnings.tickBot(bot);
            // Auto-equips armor (the best piece per slot, the next best the moment one breaks, see GearValue) and the offhand (the best
            // shield, else a totem, see OffhandPolicy). Stateless and it never consumes the tick, so it runs for every bot on every tick,
            // before the safety net, the danger scan and the mission executor (which owns the tick for a whole mission): a broken piece or
            // a popped totem is replaced during a mission and in the middle of a lava or drowning rescue too. Paused only while a player
            // has the bot's inventory screen open: they are editing its equipment by hand, and the bot re-equipping under their cursor
            // made it re-run (and re-log) every scan.
            if (!io.github.zoyluo.minecraftai.inventory.BotInventoryScreenHandler.isScreenOpen(bot)) {
                io.github.zoyluo.minecraftai.action.EquipAction.autoEquipArmor(bot);
                io.github.zoyluo.minecraftai.action.OffhandPolicy.apply(bot);
            }
            // SAFE-1: the environmental safety net runs first; if the bot is currently self-rescuing (drowning/lava), it takes over this tick, skipping the other checks.
            if (NavSafetyNet.INSTANCE.tickBot(server, bot)) {
                // The rescue has the bot: a shield the reactive owner raised comes down (it would slow every stroke).
                ShieldGuard.INSTANCE.standDown(bot, "safety_net");
                continue;
            }
            StuckWatcher.INSTANCE.tickBot(server, bot);
            boolean handled = runDanger && DangerWatcher.INSTANCE.scanBot(server, bot);
            // Mining assist (shadow sensing): never consumes the tick, never throws, one static check when off.
            MiningAssistCoordinator.INSTANCE.tickBot(server, bot, handled);
            // The reactive shield owner (see ShieldGuard): what the bot has noticed that a shield can stop, from every task, and a
            // shield it raised that nothing threatens any more. TaskManager.tickAll ran before this coordinator, so the tasks read its
            // state on the next tick; it never consumes the tick and never throws.
            ShieldGuard.INSTANCE.tickBot(server, bot);
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
