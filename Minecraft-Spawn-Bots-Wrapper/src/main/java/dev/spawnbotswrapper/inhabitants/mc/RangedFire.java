package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations;
import dev.spawnbotswrapper.inhabitants.command.CommandServices;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * Lets an inhabitant's crossbow cycle at its NATURAL speed: exactly what a person who spam-clicks the crossbow gets,
 * with no rate limit of this addon's own. Once per server tick, after PvP BOT's own tick (the caller registers it in a
 * phase behind the default one), for every inhabitant holding a crossbow in the main hand:
 * <ol>
 *   <li><b>Release the draw when it is loaded.</b> Vanilla loads the crossbow the moment the (enchantment adjusted)
 *       charge time has passed (Quick Charge III: 10 ticks; none: 25), but PvP BOT keeps every draw for a fixed 25
 *       ticks whatever the enchantment, which is an artificial wait. When the bot is drawing and
 *       {@code CrossbowItem.isCharged} has become true, the draw is released ({@code releaseUsingItem}, nothing else).</li>
 *   <li><b>Fire the loaded crossbow.</b> PvP BOT cannot fire a loaded crossbow on this Minecraft version (it calls the
 *       release of an item that is not being used, which does nothing). When the crossbow is loaded, the bot is not using
 *       an item and PvP BOT's target is alive and in line of sight, the crossbow is fired through the vanilla
 *       right-click path {@code ServerPlayerGameMode.useItem}, exactly what a player's click does.</li>
 * </ol>
 * The cycle is charge time plus about two ticks: about 12 ticks with Quick Charge III, about 27 without. The only gates
 * are vanilla mechanics (charge time, item use) and "has a live target in line of sight" (no shooting at nothing);
 * there is no cooldown or interval of this addon's own here: the aim gate below (the hand and the eye settling on the
 * target) is the only wait beyond vanilla mechanics. No projectile is created here and no damage, accuracy or speed is
 * touched. A loaded crossbow fires with no arrow left in the inventory too (the out-of-ammo bolt already in it), because
 * PvP BOT's mode plays no part in the gate. Bows need no trigger: PvP BOT releases them itself, at
 * {@code pvpbotSettings.bowMinDrawTime} (20 = full power).
 * <p>
 * <b>Reaction time.</b> A loaded crossbow is fired at a PLAYER only while the aggro engagement with that player is CONFIRMED
 * (it was continuously in sight for the full reaction time, and again after every re-sighting) and it is in line of sight this
 * tick, so a pre-loaded crossbow fires at the earliest one reaction time after the player comes into view. Bows are PvP BOT's
 * own (it draws only while it holds a target, and the target is only handed over once confirmed).
 * <p>
 * <b>Human aim.</b> The bot shoots only once its TRACKED aim (see {@link HumanAimDriver}: the head turns at human speed) is within
 * tolerance of the direction PvP BOT wants, and the bolt leaves along that aim plus the hand's jitter. Vanilla's crossbow shot
 * vector is the shooter's view vector ({@code CrossbowItem.shootProjectile} with no target), so setting the rotation for the
 * duration of the {@code useItem} call is all it takes; the rotation is put back right after. This is a human limit of the hand
 * and the eye, not a rate limit: a bot already looking at its target fires the tick the crossbow is loaded.
 * <p>
 * The PvP BOT state it needs (its target) is read through the adapter. Everything is fail-soft: one failure disables
 * the tick with one warning, never a crash.
 */
public final class RangedFire {
    private final Supplier<ServerSession> session;
    private final Logger log;
    /** Set after the tick threw once (logged once): a bug must not repeat 20 times a second. */
    private boolean broken;
    private long shotsFired;
    private long drawsReleased;

    /** Whether an inhabitant (first name) may hurt a player (second name) now: only within a CONFIRMED engagement (the reaction time). */
    private final BiPredicate<String, String> mayFirePlayer;
    private long shotsHeldBack;
    /** Human aim: a shot needs the bot to actually point at its target, and leaves along where it points (may be null). */
    private final HumanAimDriver aim;
    private long shotsHeldOnAim;

    public RangedFire(Supplier<ServerSession> session, Logger log) {
        this(session, log, (bot, victim) -> true);
    }

    public RangedFire(Supplier<ServerSession> session, Logger log, BiPredicate<String, String> mayFirePlayer) {
        this(session, log, mayFirePlayer, null);
    }

    public RangedFire(Supplier<ServerSession> session, Logger log, BiPredicate<String, String> mayFirePlayer, HumanAimDriver aim) {
        this.session = session;
        this.log = log;
        this.mayFirePlayer = mayFirePlayer;
        this.aim = aim;
    }

    /** Ticks a loaded crossbow was held back because the bot had not turned onto its target yet (tests and diagnostics). */
    public long shotsHeldOnAim() {
        return shotsHeldOnAim;
    }

    /** Ticks a loaded crossbow was held back because the engagement with its player target was not confirmed (tests and diagnostics). */
    public long shotsHeldBack() {
        return shotsHeldBack;
    }

    /** Crossbow shots this addon has fired itself since start (for tests and diagnostics). */
    public long shotsFired() {
        return shotsFired;
    }

    /** Loaded draws this addon has released early since start (for tests and diagnostics). */
    public long drawsReleased() {
        return drawsReleased;
    }

    /** Server stopped: nothing timed may survive into the next world. */
    public void reset() {
        broken = false;
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
            log.warn("crossbow trigger failed and is switched off until restart: {}", t.toString());
        }
    }

    private void run(MinecraftServer server) {
        ServerSession current = session.get();
        if (current == null || current.server() != server) {
            return;
        }
        CommandServices services = current.services();
        InhabitantsConfig cfg = services == null ? null : services.config().get();
        if (cfg == null || !cfg.enabled || services.population() == null) {
            return;
        }
        PvpBotOperations adapter = services.adapter();
        for (ServerPlayer bot : server.getPlayerList().getPlayers()) {
            if (bot.getMainHandItem().is(Items.CROSSBOW)) {
                handle(bot, services, adapter);
            }
        }
    }

    /** One player holding a crossbow: nothing unless it is an inhabitant. */
    private void handle(ServerPlayer bot, CommandServices services, PvpBotOperations adapter) {
        if (!isInhabitant(services, bot)) {
            return;
        }
        ItemStack main = bot.getMainHandItem();
        if (bot.isUsingItem()) {
            // Drawing: the moment vanilla has loaded it (isCharged), stop holding the draw.
            if (bot.getUsedItemHand() == InteractionHand.MAIN_HAND && CrossbowItem.isCharged(main)) {
                bot.releaseUsingItem();
                drawsReleased++;
            }
            return;
        }
        if (!CrossbowItem.isCharged(main) || adapter == null) {
            return;
        }
        Optional<PvpBotOperations.CombatView> view = adapter.combatView(bot.getName().getString());
        if (view.isPresent() && hasLiveTargetInSight(bot, view.get().target())) {
            // The reaction time: a loaded crossbow fires at a player only inside a CONFIRMED engagement with exactly that
            // player (target seen continuously for the full reaction time, and again after every re-sighting).
            if (view.get().target() instanceof ServerPlayer player
                    && !mayFirePlayer.test(bot.getName().getString(), player.getName().getString())) {
                shotsHeldBack++;
                return;
            }
            HumanAimDriver.Shot shot = aim == null ? null : aim.decideShot(bot, view.get().target());
            if (shot != null && !shot.allowed()) {
                // Human aim: the head is still turning onto the target. The bolt leaves along where the bot really looks.
                shotsHeldOnAim++;
                return;
            }
            Runnable launch = () -> bot.gameMode.useItem(bot, bot.level(), main, InteractionHand.MAIN_HAND);
            if (shot == null) {
                launch.run();
            } else {
                aim.launchAlong(bot, shot, launch);
            }
            shotsFired++;
        }
    }

    /** The target is alive, in the same level and in line of sight (vanilla's own check, which caps at 128 blocks). */
    private static boolean hasLiveTargetInSight(ServerPlayer bot, Entity target) {
        return target != null && target.isAlive() && target.level() == bot.level() && bot.hasLineOfSight(target);
    }

    /** An inhabitant is a player of this addon's own roster (the same name index the combat log uses). */
    private static boolean isInhabitant(CommandServices services, ServerPlayer player) {
        return services.population().findBot(player.getName().getString()).isPresent();
    }
}
