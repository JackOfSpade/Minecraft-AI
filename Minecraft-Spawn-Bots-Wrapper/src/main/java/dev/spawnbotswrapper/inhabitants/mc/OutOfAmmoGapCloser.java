package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations;
import dev.spawnbotswrapper.inhabitants.combat.AggroWorld;
import dev.spawnbotswrapper.inhabitants.combat.OutOfAmmo;
import dev.spawnbotswrapper.inhabitants.combat.TargetControl;
import dev.spawnbotswrapper.inhabitants.command.CommandServices;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Gets an inhabitant that ran out of arrows out of PvP BOT's dead end and into melee (see {@link OutOfAmmo} for the
 * problem and the rules; this class reads the game and acts).
 * <p>
 * Once per server tick, AFTER PvP BOT's own tick (the caller registers it in the late phase, see {@link LateTickPhase}),
 * for every inhabitant that PvP BOT has a live target for and that carries a bow or crossbow but has no arrow and no bolt
 * it can fire: hold the best melee weapon (moving it into the hotbar the way PvP BOT moves an axe there) and, where PvP BOT
 * stands still, walk toward the target with PvP BOT's own look and move-toward input until its melee mode is in reach.
 * Vanilla inventory and PvP BOT's own navigation calls only: no mixin, no teleport, no damage or reach change. A bot
 * with no melee weapon is left exactly as PvP BOT has it. Fail-soft: one failure switches the tick off with one warning.
 */
public final class OutOfAmmoGapCloser {
    private final Supplier<ServerSession> session;
    private final Logger log;
    private boolean broken;
    private final Set<String> warned = new HashSet<>();

    public OutOfAmmoGapCloser(Supplier<ServerSession> session, Logger log) {
        this.session = session;
        this.log = log;
    }

    /** Server stopped: nothing timed survives. */
    public void reset() {
        broken = false;
        warned.clear();
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
            log.warn("out-of-ammo gap closer failed and is switched off until restart: {}", t.toString());
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
        // Read lazily and once per tick: only when a bot gets as far as needing them.
        Shared shared = new Shared();
        for (ServerPlayer bot : server.getPlayerList().getPlayers()) {
            try {
                handle(bot, services, adapter, shared);
            } catch (OutOfMemoryError e) {
                throw e;
            } catch (RuntimeException e) {
                if (warned.size() < 16 && warned.add(e.toString())) {
                    log.warn("out-of-ammo gap closer failed for {}: {}", bot.getName().getString(), e.toString());
                }
            }
        }
    }

    /** PvP BOT settings shared by every bot of one tick. */
    private static final class Shared {
        boolean read;
        boolean rangedEnabled;
        boolean preferSword;
        PvpBotOperations.MeleeTuning tuning;
    }

    private static void readShared(PvpBotOperations adapter, Shared shared) {
        if (shared.read) {
            return;
        }
        shared.read = true;
        var caps = adapter.readCapabilities();
        shared.rangedEnabled = caps.rangedEnabled();
        shared.preferSword = caps.preferSword();
        shared.tuning = adapter.meleeTuning().orElse(null);
    }

    private void handle(ServerPlayer bot, CommandServices services, PvpBotOperations adapter, Shared shared) {
        if (!bot.isAlive() || services.population().findBot(bot.getName().getString()).isEmpty()) {
            return;
        }
        String name = bot.getName().getString();
        Inventory inv = bot.getInventory();
        // The cheap gates first: no ammo and a ranged weapon, nothing else is read for a bot that is fine.
        boolean carriesRanged = false;
        boolean hasArrow = false;
        for (int i = 0; i < 36; i++) {
            Item item = inv.getItem(i).getItem();
            carriesRanged |= item instanceof BowItem || item instanceof CrossbowItem;
            hasArrow |= item instanceof ArrowItem;
        }
        if (!carriesRanged || hasArrow) {
            return;
        }
        Optional<PvpBotOperations.CombatView> view = adapter.combatView(name);
        Entity target = view.isPresent() ? view.get().target() : null;
        if (target == null || !target.isAlive() || target.level() != bot.level()) {
            return;
        }
        readShared(adapter, shared);
        if (shared.tuning == null) {
            return;
        }
        boolean loaded = loadedCrossbowReady(bot, target);
        List<String> ids = new ArrayList<>(36);
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            ids.add(stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        int meleeSlot = OutOfAmmo.bestMeleeSlot(ids, shared.preferSword);
        OutOfAmmo.Verdict verdict = OutOfAmmo.judge(new OutOfAmmo.Facts(true, adapter.retreating(name), eating(bot),
                shared.rangedEnabled, true, false, loaded, meleeSlot, view.get().mode(), bot.distanceTo(target),
                shared.tuning.meleeRange()));
        if (verdict == OutOfAmmo.Verdict.IDLE) {
            return;
        }
        hold(bot, inv, meleeSlot);
        if (verdict == OutOfAmmo.Verdict.SELECT_AND_CLOSE) {
            TargetControl control = adapter.targetControl();
            if (control.steeringAvailable()) {
                control.steer(bot, new AggroWorld.Pos(target.getX(), target.getEyeY(), target.getZ()),
                        shared.tuning.moveSpeed());
            }
        }
    }

    /** A charged crossbow in the hotbar or offhand that {@link RangedFire} can shoot at this target (it needs a clear line). */
    private static boolean loadedCrossbowReady(ServerPlayer bot, Entity target) {
        boolean charged = CrossbowItem.isCharged(bot.getOffhandItem());
        for (int i = 0; i < 9 && !charged; i++) {
            charged = CrossbowItem.isCharged(bot.getInventory().getItem(i));
        }
        return charged && bot.hasLineOfSight(target);
    }

    private static boolean eating(ServerPlayer bot) {
        if (!bot.isUsingItem()) {
            return false;
        }
        ItemUseAnimation animation = bot.getUseItem().getUseAnimation();
        return animation == ItemUseAnimation.EAT || animation == ItemUseAnimation.DRINK;
    }

    /**
     * Selects the melee weapon's hotbar slot, first swapping it into the hotbar when it sits in the main inventory (the
     * way PvP BOT swaps an axe into slot 0): into the selected slot when that holds a ranged weapon or nothing, else into
     * another such hotbar slot, else into the selected slot.
     */
    private static void hold(ServerPlayer bot, Inventory inv, int meleeSlot) {
        int slot = meleeSlot;
        if (slot >= 9) {
            int hot = hotbarSlotFor(inv);
            ItemStack weapon = inv.getItem(slot);
            ItemStack displaced = inv.getItem(hot);
            inv.setItem(hot, weapon);
            inv.setItem(slot, displaced);
            slot = hot;
        }
        if (inv.getSelectedSlot() != slot) {
            if (bot.isUsingItem()) {
                bot.stopUsingItem();
            }
            inv.setSelectedSlot(slot);
        }
    }

    private static int hotbarSlotFor(Inventory inv) {
        int selected = inv.getSelectedSlot();
        if (emptyOrRanged(inv.getItem(selected))) {
            return selected;
        }
        for (int i = 0; i < 9; i++) {
            if (emptyOrRanged(inv.getItem(i))) {
                return i;
            }
        }
        return selected;
    }

    private static boolean emptyOrRanged(ItemStack stack) {
        return stack.isEmpty() || stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem;
    }
}
