package dev.spawnbotswrapper.inhabitants.combat;

import java.util.HashMap;
import java.util.Map;

/**
 * Decides WHEN an inhabitant's loaded crossbow is fired and how long its next shot has to wait. Pure bookkeeping: the
 * Minecraft side ({@code mc.RangedFire}) feeds it what it sees each tick and does the firing and the item cooldown.
 * <p>
 * Why the addon fires at all: on this Minecraft version PvP BOT's crossbow routine ends every cycle by releasing an
 * item that is not being used, which does nothing, so a loaded crossbow is never fired. The addon fires it through the
 * vanilla right-click path once all of these hold: it is the main-hand crossbow and loaded, the bot is not using an
 * item, PvP BOT's target is alive, in range and in sight, PvP BOT is in ranged mode (when readable), the crossbow has
 * been loaded for {@code aimSettleTicks} (so the bot has aimed) and the per-bot shot interval has elapsed.
 * <p>
 * The shot interval is also enforced against everybody else: {@link #shot} is told about EVERY crossbow shot an
 * inhabitant fires (this addon's, or a held "use" action's), and the caller puts the crossbow on the vanilla item
 * cooldown so the interval binds them too.
 * <p>
 * Server thread only. Time is the level game time in ticks.
 */
public final class CrossbowPacer {

    /** @param enabled false: never fire, never pace */
    public record Settings(boolean enabled, int aimSettleTicks, int minShotIntervalTicks) {
    }

    /**
     * What the caller sees of one inhabitant this tick.
     *
     * @param crossbowInMainHand the main hand holds a crossbow
     * @param loaded             that crossbow is loaded
     * @param usingItem          the bot is using an item (drawing, blocking, eating ...)
     * @param onCooldown         the crossbow is on the vanilla item cooldown
     * @param targetReachable    PvP BOT has a target that is alive, within the targeting radius and in line of sight
     * @param rangedMode         PvP BOT is in ranged mode; null when that cannot be read
     */
    public record Look(boolean crossbowInMainHand, boolean loaded, boolean usingItem, boolean onCooldown,
                       boolean targetReachable, Boolean rangedMode) {
    }

    /** The verdict for one inhabitant on one tick; only {@link #FIRE} asks the caller to shoot. */
    public enum Verdict {
        FIRE,
        /** Pacing is switched off. */
        DISABLED,
        /** Not holding a crossbow, or it is not loaded. */
        NOT_LOADED,
        /** The bot is using an item (its own draw, a shield ...). */
        BUSY,
        /** PvP BOT has no target in reach and in sight. */
        NO_TARGET,
        /** PvP BOT is in melee, mace or another non-ranged mode. */
        NOT_RANGED_MODE,
        /** Loaded, but not for {@code aimSettleTicks} yet. */
        SETTLING,
        /** The last shot was less than the shot interval ago, or the crossbow is on cooldown. */
        WAITING_INTERVAL
    }

    private final Map<String, Long> loadedSince = new HashMap<>();
    private final Map<String, Long> lastShot = new HashMap<>();

    /** Judges one inhabitant now. Also keeps the "loaded since" clock: it starts when a loaded crossbow is first seen. */
    public Verdict evaluate(String bot, long now, Settings settings, Look look) {
        if (settings == null || !settings.enabled()) {
            return Verdict.DISABLED;
        }
        if (!look.crossbowInMainHand() || !look.loaded()) {
            loadedSince.remove(bot);
            return Verdict.NOT_LOADED;
        }
        Long since = loadedSince.get(bot);
        if (since == null || since > now) {
            since = now;
            loadedSince.put(bot, now);
        }
        if (look.usingItem()) {
            return Verdict.BUSY;
        }
        if (!look.targetReachable()) {
            return Verdict.NO_TARGET;
        }
        if (Boolean.FALSE.equals(look.rangedMode())) {
            return Verdict.NOT_RANGED_MODE;
        }
        if (now - since < Math.max(0, settings.aimSettleTicks())) {
            return Verdict.SETTLING;
        }
        Long last = lastShot.get(bot);
        if (look.onCooldown() || (last != null && last <= now && now - last < Math.max(1, settings.minShotIntervalTicks()))) {
            return Verdict.WAITING_INTERVAL;
        }
        return Verdict.FIRE;
    }

    /** A crossbow shot of this inhabitant happened now (whoever fired it). */
    public void shot(String bot, long now) {
        lastShot.put(bot, now);
        loadedSince.remove(bot);
    }

    /** Ticks until the next shot may be fired, counted from a shot that just happened: the whole interval. */
    public static int cooldownAfterShot(Settings settings) {
        return settings == null || !settings.enabled() ? 0 : Math.max(1, settings.minShotIntervalTicks());
    }

    /** Ticks since this bot's last recorded shot, or -1 when none. */
    public long ticksSinceShot(String bot, long now) {
        Long last = lastShot.get(bot);
        return last == null ? -1 : now - last;
    }

    /** Forgets everything about a bot (gone offline). */
    public void forget(String bot) {
        loadedSince.remove(bot);
        lastShot.remove(bot);
    }

    /** True when anything is remembered about this bot. */
    public boolean isTracked(String bot) {
        return loadedSince.containsKey(bot) || lastShot.containsKey(bot);
    }

    /** True when nothing is remembered about any bot. */
    public boolean isEmpty() {
        return loadedSince.isEmpty() && lastShot.isEmpty();
    }

    /** Names with any state, so the caller can drop the ones that went offline. */
    public java.util.Set<String> tracked() {
        java.util.Set<String> all = new java.util.HashSet<>(loadedSince.keySet());
        all.addAll(lastShot.keySet());
        return all;
    }

    /** Server stopped or a new one started: game time restarts, so nothing timed may survive. */
    public void reset() {
        loadedSince.clear();
        lastShot.clear();
    }
}
