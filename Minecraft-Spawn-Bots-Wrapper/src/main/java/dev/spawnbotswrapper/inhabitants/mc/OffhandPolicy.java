package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations;
import dev.spawnbotswrapper.inhabitants.combat.OffhandRule;
import dev.spawnbotswrapper.inhabitants.command.CommandServices;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The offhand of every inhabitant: the best shield it carries, else a totem of undying, else whatever it is (see
 * {@link OffhandRule} for the exact rule). When the offhand item breaks or pops the best replacement of the same kind takes its
 * place at once, and only when there is none the next rung of the ladder does.
 * <p>
 * PvP BOT's own {@code BotUtils.handleAutoTotem} forces a totem into the offhand whenever the bot is not blocking, which pushes a
 * shield out of it, and {@code totemPriority} keeps the totem there and blocks with a shield from the main hand. Both contradict
 * the rule, so the addon manages {@code autoTotemEnabled=false} and {@code totemPriority=false}. This tick runs AFTER PvP BOT's
 * tick (the caller registers it in the late phase, see {@link LateTickPhase}). It stays idle when PvP BOT's auto-totem is on (the
 * two would swap the offhand back and forth every tick), and it reads that setting only for a bot it would move.
 * <p>
 * With {@code totemPriority=false} and the shield already in the offhand PvP BOT blocks with it ({@code startBlocking} runs
 * {@code player use continuous}, the main-hand weapon has no use action, so the offhand shield is used) and does not swap
 * anything when it stops blocking ({@code stopBlocking} only restores an offhand item that it saved itself when it moved a
 * shield in). Vanilla inventory calls only: no mixin, no teleport, no damage change. Fail-soft: one failure switches the tick off
 * with one warning.
 */
public final class OffhandPolicy {
    private final Supplier<ServerSession> session;
    private final Logger log;
    private boolean broken;
    private final Set<String> warned = new HashSet<>();
    private boolean notedAutoTotem;

    public OffhandPolicy(Supplier<ServerSession> session, Logger log) {
        this.session = session;
        this.log = log;
    }

    /** Server stopped: nothing timed survives. */
    public void reset() {
        broken = false;
        warned.clear();
        notedAutoTotem = false;
    }

    /** Once per server tick, after PvP BOT's tick. */
    public void tick(MinecraftServer server) {
        if (broken) {
            return;
        }
        try {
            run(server);
        } catch (OutOfMemoryError e) {
            throw e;
        } catch (Throwable t) {
            broken = true;
            log.warn("offhand policy failed and is switched off until restart: {}", t.toString());
        }
    }

    private void run(MinecraftServer server) {
        ServerSession current = session.get();
        if (current == null || current.server() != server) {
            return;
        }
        CommandServices services = current.services();
        InhabitantsConfig cfg = services == null ? null : services.config().get();
        if (cfg == null || !cfg.enabled || services.population() == null || services.adapter() == null) {
            return;
        }
        PvpBotOperations adapter = services.adapter();
        Boolean autoTotemOff = null; // read lazily, once per tick, only for a bot that would be moved
        for (ServerPlayer bot : server.getPlayerList().getPlayers()) {
            try {
                if (!bot.isAlive() || services.population().findBot(bot.getName().getString()).isEmpty()) {
                    continue;
                }
                Plan plan = plan(bot);
                if (plan == null) {
                    continue;
                }
                if (autoTotemOff == null) {
                    autoTotemOff = !adapter.readCapabilities().autoTotemEnabled();
                    if (!autoTotemOff && !notedAutoTotem) {
                        notedAutoTotem = true;
                        log.warn("offhand policy idle: PvP BOT's own auto-totem is on (pvpbotSettings.autoTotemEnabled is not "
                                + "managed), it would undo every swap");
                    }
                }
                if (autoTotemOff) {
                    apply(bot, plan);
                }
            } catch (OutOfMemoryError e) {
                throw e;
            } catch (RuntimeException e) {
                if (warned.size() < 16 && warned.add(e.toString())) {
                    log.warn("offhand policy failed for {}: {}", bot.getName().getString(), e.toString());
                }
            }
        }
    }

    /** One move of one stack into the offhand. */
    record Plan(OffhandRule.Move move, int slot) {
    }

    /** What the rule would do for this bot, or null for nothing. Reads the inventory only. */
    static Plan plan(ServerPlayer bot) {
        ItemStack offhand = bot.getOffhandItem();
        OffhandRule.Held held = offhand.isEmpty() ? OffhandRule.Held.EMPTY
                : offhand.is(Items.SHIELD) ? OffhandRule.Held.SHIELD
                : offhand.is(Items.TOTEM_OF_UNDYING) ? OffhandRule.Held.TOTEM : OffhandRule.Held.OTHER;
        if (held == OffhandRule.Held.SHIELD || held == OffhandRule.Held.OTHER) {
            return null;
        }
        Inventory inv = bot.getInventory();
        int shieldSlot = -1;
        int totemSlot = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(Items.SHIELD)) {
                // The best shield: an enchanted one first, then the lowest slot.
                if (shieldSlot < 0 || stack.isEnchanted() && !inv.getItem(shieldSlot).isEnchanted()) {
                    shieldSlot = i;
                }
            } else if (totemSlot < 0 && stack.is(Items.TOTEM_OF_UNDYING)) {
                totemSlot = i;
            }
        }
        OffhandRule.Move move = OffhandRule.decide(held, shieldSlot >= 0, totemSlot >= 0);
        return switch (move) {
            case SHIELD -> new Plan(move, shieldSlot);
            case TOTEM -> new Plan(move, totemSlot);
            case NONE -> null;
        };
    }

    /** Moves the stack into the offhand; the displaced one (empty or the totem) takes the place it came from. */
    static void apply(ServerPlayer bot, Plan plan) {
        Inventory inv = bot.getInventory();
        ItemStack moving = inv.getItem(plan.slot());
        ItemStack displaced = bot.getOffhandItem();
        inv.setItem(plan.slot(), displaced);
        bot.setItemSlot(EquipmentSlot.OFFHAND, moving);
    }
}
