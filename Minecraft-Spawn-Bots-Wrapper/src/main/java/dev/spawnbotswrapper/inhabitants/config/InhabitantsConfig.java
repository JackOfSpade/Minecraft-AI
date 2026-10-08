package dev.spawnbotswrapper.inhabitants.config;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The editable configuration ({@code config/pvpbot_inhabitants.json}). Plain mutable data so Gson can
 * bind it; every field has a sensible default so a partial file is fine. Nothing in the spawning
 * logic hard-codes these numbers - the values below are only the shipped defaults.
 * <p>
 * Structure identifiers everywhere in this file use the same syntax:
 * <ul>
 *   <li>{@code minecraft:pillager_outpost} - an exact registry id (a bare {@code village_plains} means {@code minecraft:village_plains})</li>
 *   <li>{@code #minecraft:village} - a structure tag</li>
 *   <li>{@code somemod:*} - every structure of one namespace (mod)</li>
 *   <li>{@code *} - everything</li>
 * </ul>
 */
public final class InhabitantsConfig {
    public static final int CURRENT_VERSION = 1;

    public int configVersion = CURRENT_VERSION;

    /** Master switch. When false the addon does nothing (no rolls are made or recorded). */
    public boolean enabled = true;

    /** Verbose logging of rolls, positions and profile generation. */
    public boolean debug = false;

    /** Register the admin/testing commands ({@code /inhabitants ...}). */
    public boolean debugCommands = true;

    /** Op level needed for the admin commands (0-4). */
    public int commandPermissionLevel = 2;

    /**
     * Turn off vanilla's Locator Bar gamerule on server start, so inhabitants (indistinguishable from
     * real players at the protocol level) don't show up as radar-like waypoints under the hunger bar.
     * Applied once per server start; the player can still turn the gamerule back on manually.
     */
    public boolean hideLocatorBar = true;

    /** Baseline occupied/abandoned probability and bot count, used unless overridden below. */
    @SerializedName("default")
    public Rule defaults = new Rule();

    /** Only structures matching one of these are eligible. Empty is treated as {@code ["*"]}. */
    public List<String> include = new ArrayList<>(List.of("*"));

    /** Structures matching any of these are never populated (they are not even rolled). Wins over include. */
    public List<String> exclude = new ArrayList<>(List.of("minecraft:buried_treasure"));

    /** Per-structure overrides: exact id or {@code namespace:*}. Fields you omit fall through to tags, then default. */
    public Map<String, RuleOverride> structures = defaultStructureOverrides();

    /** Per-tag overrides, keys like {@code #minecraft:village}. Earlier entries win over later ones. */
    public Map<String, RuleOverride> tags = defaultTagOverrides();

    /** Dimensions structures are processed in. */
    public Dimensions dimensions = new Dimensions();

    public Profiles profiles = new Profiles();
    public Deterministic deterministic = new Deterministic();
    public Processing processing = new Processing();
    public Spawning spawning = new Spawning();
    /** Despawns inhabitants when the server is struggling, farthest from the nearest real player first. */
    public TpsThrottle tpsThrottle = new TpsThrottle();
    /** Rate-limited log lines about fights involving inhabitants (hits, kills, deaths); see {@link CombatLog}. */
    public CombatLog combatLog = new CombatLog();
    /** Rules of the fights themselves (currently only the melee legality rule); see {@link Combat}. */
    public Combat combat = new Combat();
    /** How close a player must be before an inhabitant notices them, and when it gives up a chase; see {@link Aggro}. */
    public Aggro aggro = new Aggro();
    /** Despawns inhabitants that have drifted far from every real player, and restores them later unchanged. */
    public Dormancy dormancy = new Dormancy();
    /** Nearest-first population: which structures get the live bots, and how fast bots come and go around the players. */
    public Allocation allocation = new Allocation();
    /** Holds newly-connecting players on their loading screen for a grace period after server start; see {@link Connection}. */
    public Connection connection = new Connection();
    /**
     * Console commands run, in order, once every server start (silently: nothing is echoed to chat), before
     * anything else in this file takes effect. Not specific to inhabitants -- a general convenience so choices
     * that some OTHER mod only stores per world name (PvP BOT's own combat settings, for instance, which reset
     * to its hardcoded defaults on every fresh world with no template of its own) can be reasserted automatically
     * instead of retyped by hand after every new world. Each command is independent: one failing (unknown
     * command, bad argument) is logged and does not stop the rest from running. Example:
     * {@code ["pvpbot settings auto-target true", "pvpbot settings view-distance 16"]}.
     * <p>
     * Besides this list the addon manages one PvP BOT setting on its own, see {@link #criticalFallTicks}.
     */
    public List<String> startupCommands = new ArrayList<>();

    /**
     * The one PvP BOT setting this addon manages itself: {@code crit-fall-ticks}, applied through the same console
     * mechanism as {@link #startupCommands} ({@code pvpbot settings crit-fall-ticks N}, run first at every server
     * start; the addon never edits PvP BOT's settings files). PvP BOT's melee routine only swings after a
     * jump-crit with this many ticks of descent; its own default is 6, which made bots look passive at close
     * range, so the managed value is 3 (criticals themselves stay enabled). 0 = do not manage it. An explicit
     * {@code crit-fall-ticks} command in {@link #startupCommands} wins over this value. Range 0..10.
     */
    public int criticalFallTicks = DEFAULT_CRITICAL_FALL_TICKS;

    /**
     * PvP BOT settings this addon keeps at chosen values (see {@link PvpbotSettings}); applied every time PvP BOT loads
     * its per-world settings, through the adapter, which is the only place that writes them. An absent block means the
     * shipped values; inside a present block an absent or null key leaves PvP BOT's own value alone.
     */
    @SerializedName("pvpbotSettings")
    public PvpbotSettings pvpbotSettings = PvpbotSettings.shipped();


    public static final int DEFAULT_CRITICAL_FALL_TICKS = 3;

    /**
     * Rolled once per structure: occupied or abandoned, and the minimum bot count if occupied. There is
     * deliberately no per-structure/tag maximum here any more: the ceiling for one structure INSTANCE is
     * always {@code min(EffectiveRule.MAX_BOTS_PER_STRUCTURE, ceil(totalVolume / processing.blocksPerBot))}
     * (see {@link EffectiveRule#sizeCappedMax}) -- a one-room well and a fifty-house village never share a
     * hand-picked number just because they share a tag; a bigger structure simply supports more bots, up
     * to the one global cap every structure shares.
     */
    public static final class Rule {
        /** Probability in [0,1] that a structure is occupied. */
        public double occupiedChance = 0.65;
        /** Bots an occupied structure gets at minimum (also the floor the size-scaled ceiling folds down to). */
        public int minBots = 1;

        public Rule() {
        }

        public Rule(double occupiedChance, int minBots) {
            this.occupiedChance = occupiedChance;
            this.minBots = minBots;
        }
    }

    /**
     * PvP BOT settings the addon enforces (PvP BOT keeps them per world, in {@code config/pvpbot/worlds/<world>/settings.json}).
     * A null value means "leave PvP BOT's own value alone". In a file, a key that is ABSENT from a present block takes its shipped
     * value (see {@link PvpbotSettings#backfillAbsent}); only an explicit {@code null} (or a {@code null} block) leaves PvP BOT alone. They are written straight into PvP BOT's settings object, NOT
     * through its setters, because the setters clamp to ranges (ranged optimal at least 10, ranged max at least 15) that
     * can exclude the ranges wanted here (the shipped 8/12/16 happen to sit inside them, but the mechanism stays field writes).  A
     * managed value may therefore deliberately lie outside a setter clamp; PvP BOT itself never re-validates a loaded value.
     * <ul>
     *   <li>{@link #maxTargetDistance} - PvP BOT's targeting radius (blocks, allowed 4..128). Shipped 16 = the aggro controller's
     *       new-target and close-combat limit, so PvP BOT only has to cover the initial acquisition and close combat. Once acquired, the wrapper keeps chasing a
     *       visible target beyond this radius and re-arms PvP BOT when the target returns.</li>
     *   <li>{@link #rangedMinRange}, {@link #rangedOptimalRange}, {@link #rangedMaxRange} - archer distances. Must satisfy
     *       min &lt; optimal &lt;= max &lt;= maxTargetDistance, otherwise all three are left alone (with a warning).</li>
     *   <li>{@link #autoEquipWeapon} - PvP BOT's housekeeping that keeps selecting the best MELEE weapon every
     *       {@code checkInterval} ticks. For a bot that carries both a sword and a bow or crossbow it ends every draw
     *       (the selected slot leaves the ranged weapon inside the tick), so such bots never shoot. false stops it;
     *       melee combat picks its own weapon anyway.</li>
     *   <li>{@link #autoTargetEnabled} - PvP BOT's own acquisition of the nearest entity within maxTargetDistance. Shipped
     *       false: the wrapper's aggro controller acquires (by line of sight) instead. Set it to true (or set
     *       {@code aggro.enabled} to false and manage nothing here) to give acquisition back to PvP BOT.</li>
     *   <li>{@link #rangedRetreatOnClose} - PvP BOT's "archers keep shooting and back away when the target is close". Shipped
     *       false: a bot that carries a melee weapon then switches to it once the target is within twice PvP BOT's melee
     *       range (like a player swapping to a sword up close) and keeps shooting beyond that; it also removes PvP BOT's
     *       velocity push away from a target inside melee range. true restores PvP BOT's archer that never puts the
     *       bow away while it holds an arrow (with no arrow left this addon's out-of-ammo gap closer still applies).</li>
     *   <li>{@link #meleeRange} - PvP BOT's melee range. Shipped 2.5 (PvP BOT: 3.5): a melee weapon comes out within twice
     *       this (5 blocks) and attacks land within it (2.5 blocks between the two centres, inside vanilla's 3.0
     *       reach). PvP BOT clamps it to 2..6; a value outside is not applied.</li>
     *   <li>{@link #bowMinDrawTime} - ticks PvP BOT holds a bow draw before it releases (5..100). Shipped 20 = vanilla
     *       full power; PvP BOT's own default of 40 (2 s) is an artificial wait, so an inhabitant shoots as fast as a
     *       person holding the bow to full draw would. Written straight into the field like the others.</li>
     *   <li>{@link #autoTotemEnabled} - PvP BOT's "swap a totem into the offhand whenever the bot is not blocking", which
     *       pushes a shield out of the offhand. Shipped false: the offhand rule is this addon's {@code OffhandPolicy}
     *       (the best shield, else a totem, the next of the same kind when one breaks or pops; any other offhand item is
     *       left alone). true gives the offhand back to PvP BOT.</li>
     *   <li>{@link #totemPriority} - PvP BOT's "keep a totem in the offhand and block with a shield from the main hand".
     *       Shipped false: a bot blocks with the shield that is already in its offhand.</li>
     * </ul>
     */
    public static final class PvpbotSettings {
        public Double maxTargetDistance;
        public Double rangedMinRange;
        public Double rangedOptimalRange;
        public Double rangedMaxRange;
        public Boolean autoEquipWeapon;
        public Boolean autoTargetEnabled;
        public Boolean rangedRetreatOnClose;
        public Double meleeRange;
        public Integer bowMinDrawTime;
        public Boolean autoTotemEnabled;
        public Boolean totemPriority;

        public PvpbotSettings() {
        }

        /** The values the addon ships with: PvP BOT's targeting radius at the 16-block acquisition/close-combat limit
         * (the wrapper keeps a line-of-sight chase beyond it), PvP BOT's own target acquisition off (the aggro controller
         * acquires), archers at 8/12/16 and no weapon auto-equip. */
        public static PvpbotSettings shipped() {
            PvpbotSettings s = new PvpbotSettings();
            s.maxTargetDistance = 16.0;
            s.rangedMinRange = 8.0;
            s.rangedOptimalRange = 12.0;
            s.rangedMaxRange = 16.0;
            s.autoEquipWeapon = false;
            s.autoTargetEnabled = false;
            s.rangedRetreatOnClose = false;
            s.meleeRange = 2.5;
            s.bowMinDrawTime = 20;
            s.autoTotemEnabled = false;
            s.totemPriority = false;
            return s;
        }

        /**
         * Gives every key that the file does NOT contain at all (an absent key, as opposed to an explicit {@code null}) its shipped
         * value, so a {@code pvpbotSettings} block written before a key existed still manages it (an existing install must not keep
         * PvP BOT's own auto-totem or totem priority just because its file predates them). An explicit {@code null} stays null: that
         * is the one way to leave a PvP BOT value alone.
         *
         * @param present says whether the file has an entry (of any value, null included) for a key name
         * @return the names of the keys that were filled, in declaration order
         */
        public List<String> backfillAbsent(java.util.function.Predicate<String> present) {
            PvpbotSettings shipped = shipped();
            List<String> filled = new ArrayList<>();
            for (java.lang.reflect.Field field : PvpbotSettings.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        || !java.lang.reflect.Modifier.isPublic(field.getModifiers()) || present.test(field.getName())) {
                    continue;
                }
                try {
                    field.set(this, field.get(shipped));
                    filled.add(field.getName());
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
            return filled;
        }

        /** True when nothing is managed. */
        public boolean isEmpty() {
            return maxTargetDistance == null && rangedMinRange == null && rangedOptimalRange == null
                    && rangedMaxRange == null && autoEquipWeapon == null && autoTargetEnabled == null
                    && rangedRetreatOnClose == null && meleeRange == null
                    && bowMinDrawTime == null && autoTotemEnabled == null && totemPriority == null;
        }
    }


    /** A partial rule; null fields inherit. */
    public static final class RuleOverride {
        public Double occupiedChance;
        public Integer minBots;

        public RuleOverride() {
        }

        public RuleOverride(Double occupiedChance, Integer minBots) {
            this.occupiedChance = occupiedChance;
            this.minBots = minBots;
        }
    }

    public static final class Dimensions {
        public List<String> include = new ArrayList<>(List.of("*"));
        public List<String> exclude = new ArrayList<>();
    }

    public static final class Profiles {
        /**
         * Give every bot an independently randomized loadout / starting health and hunger / behaviour profile. Bots never get attribute modifiers (max health, reach, ...): they have the stats of a vanilla player.
         * When false bots keep whatever PvP BOT gives a fresh bot (nothing).
         */
        public boolean randomize = true;
        /** How many buckets a numeric range is divided into for coverage sampling (2-64). */
        public int coverageBuckets = 8;
        /** Give bots a PvP BOT patrol path and/or faction where PvP BOT supports it (see README). */
        public boolean behaviorVariation = true;
        /** Dress a bot again if it comes back (e.g. after a restart) without its items and without a marker: from its last saved state when there is one, from the stored profile only when it never was saved. */
        public boolean reapplyOnRestore = true;
        /**
         * Allow loadouts with end crystals + obsidian, or respawn anchors + glowstone. PvP BOT's crystal/anchor
         * PvP places blocks and causes EXPLOSIONS, which destroy the structure the bot lives in. Off by default.
         */
        public boolean allowExplosiveKits = false;
        /**
         * Allow elytra loadouts. Elytra + fireworks + a mace triggers PvP BOT's flight routine whenever the bot
         * has a target, which sends bots soaring out of their structure. Off by default.
         */
        public boolean allowElytra = false;
        /**
         * Enchantments no inhabitant is given or keeps (ids with or without the {@code minecraft:} namespace; an
         * empty list disables the feature). Default: Piercing (a piercing bolt ignores a raised shield in
         * vanilla Java, so a hostile inhabitant's crossbow could not be blocked) and Mending (gear is a one-time
         * reward that cannot be repaired with experience). A list written in the config replaces the default. New
         * loadouts never contain them (the rolls are otherwise unchanged), inhabitants that already carry one are
         * stripped of just that enchantment (dressing, after a restore and about every 5 s), and stored profiles are
         * filtered when re-applied. A changed list applies to gear dressed from then on (and to the periodic sweep of
         * wrapper-issued gear); a stack the one-time migration left unmarked, or one a bot picked up, is never re-judged.
         */
        public List<String> disabledEnchantments = new ArrayList<>(DisabledEnchantments.DEFAULT);
    }

    public static final class Deterministic {
        /**
         * Derive every roll and profile from world seed + dimension + structure id + start position
         * (+ salt), so a fresh copy of the same world produces the same inhabitants. Persisted results
         * remain authoritative once a structure has been processed.
         */
        public boolean enabled = false;
        /** Change to get a different-but-still-reproducible set of results. */
        public String salt = "";
    }

    public static final class Processing {
        /**
         * true: only structures generated while this addon is installed are processed.
         * false: any structure whose start chunk loads and has no record yet is rolled (existing worlds get inhabitants too).
         */
        public boolean onlyNewlyGenerated = false;
        /** How many newly seen structures are rolled per server tick. */
        public int maxStructuresPerTick = 2;
        /** How many bots are requested from PvP BOT per server tick (spawning a fake player is not free). */
        public int maxBotsPerTick = 1;
        /** Minimum ticks between two bot spawn requests. */
        public int spawnIntervalTicks = 20;
        /**
         * How many blocks of a structure's total volume (sum of its pieces' bounding boxes, or the
         * overall bounds when it has no piece data) justify one more bot, before
         * {@link EffectiveRule#MAX_BOTS_PER_STRUCTURE} clamps the result. A single global knob rather than
         * a per-structure one: a bigger structure earning more bots is a property of its size, not of an
         * operator having hand-picked a number for that particular structure type. Tune this once you have
         * seen real structures in play -- it is a rough starting estimate, not a calibrated constant.
         */
        public double blocksPerBot = 300.0;
        /** Ticks to wait before the first population attempt, so neighbouring chunks of the structure can load. */
        public int initialDelayTicks = 40;
        /** Ticks between retries while a structure's chunks are not loaded/valid yet. */
        public int retryIntervalTicks = 100;
        /** Population attempts (with loaded chunks) before an occupied structure is given up on permanently. */
        public int maxAttemptsPerStructure = 12;
        /** Never have more than this many living inhabitants at once; further population waits (no re-roll). 0 = unlimited. */
        public int maxLiveBots = 24;
        /** Ticks to wait for PvP BOT to make a requested bot appear before counting the attempt as failed. */
        public int appearTimeoutTicks = 200;
        /** Ticks between disk saves of changed state (also saved on server stop). */
        public int saveIntervalTicks = 600;
        /**
         * After a server start PvP BOT restores its bots one by one with no completion signal; the addon waits
         * this many ticks before it concludes anything about missing bots (offline clocks, releasing leftovers,
         * spawning, dormancy, the TPS governor). It does NOT delay the important part: from the first tick a bot
         * that PvP BOT has already brought back (online and listed) gets its patrol path and follower re-attached
         * (with attack=true) and, if enabled, its profile re-applied, so restored inhabitants walk and fight
         * promptly instead of idling for this whole window.
         */
        public int restoreSettleTicks = 1200;
        /**
         * A spawned inhabitant that stays offline this many ticks (after the settle period) is considered gone
         * for good: the addon releases its upstream leftovers (patrol path etc.). It is NEVER respawned.
         */
        public int goneConfirmTicks = 6000;
    }

    /**
     * Reserved for connection-lifecycle options. Formerly held {@code joinHoldTicks}, a login-hold protection
     * whose enforcing mechanism ({@code JoinHoldGate}) was removed in commit 770ccd1 ("Fix world-gen hang:
     * remove JoinHoldGate, root-caused by bisection") because merely registering it caused a world-gen hang
     * whose exact mechanism was never pinned down; the now-inert field was removed along with it. An old config
     * file that still has a {@code connection.joinHoldTicks} key loads fine -- Gson silently ignores unknown
     * JSON members -- it just no longer does anything.
     */
    public static final class Connection {
    }

    /**
     * Reactive safety valve for server load, instead of a single hand-picked {@code processing.maxLiveBots}
     * guess that may be wrong at any given moment: periodically checks the real, measured tick rate and, while
     * it is degraded, (a) hard-blocks every new spawn -- both freshly-discovered structures and already-pending
     * ones -- and (b) sheds a batch of already-live inhabitants, farthest from the nearest real player first,
     * since those are both the least noticed if removed and the least likely to be who the slowdown is actually
     * about. The batch escalates the longer the server stays degraded (a lone bad check sheds one base batch, a
     * second consecutive one sheds two, and so on, resetting the moment it recovers), so a brief blip is handled
     * gently while a sustained, genuine overload converges quickly instead of nibbling forever.
     * <p>
     * "Degraded" is deliberately hard to reach: a small state machine ({@code engine.TickHealth}) compares the
     * smoothed tick time with a level derived from the server's OWN measured baseline (so a pack that idles at
     * 55 ms per tick is not treated as overloaded), requires the excess to be sustained, uses separate enter and
     * exit levels (hysteresis) and a minimum dwell, and logs every transition with the numbers. Shedding happens
     * only while genuinely degraded. A shed bot is taken out
     * of the world without dying and without dropping anything: one a player has seen sleeps with its whole state,
     * one nobody saw is deleted and its slot is vacant. Unblocking new spawns once the server is healthy again does
     * not by itself create anything -- only the nearest-first {@link Allocation} does (it wakes sleepers and rolls
     * fresh bots for vacant slots of structures near a player). See {@link Allocation} for the mechanism that keeps
     * the population close to the players during ordinary play.
     */
    public static final class TpsThrottle {
        /** Master switch. */
        public boolean enabled = true;
        /**
         * LEGACY, ignored (a warning is logged when the key is present). The old fixed pair (52.6 / 55.6 ms) was
         * tighter than this kind of server's NORMAL tick time -- a modded server idles at 53-59 ms per tick and
         * the measured metric can never go below ~50 -- so it shed inhabitants while the server was merely busy.
         * Replaced by {@link #degradedFloorMillis} / {@link #degradedFactor} and {@link #recoveredFloorMillis} /
         * {@link #recoveredFactor}, which sit above the server's own measured baseline.
         */
        public Double healthyMillis;
        /** LEGACY, ignored; see {@link #healthyMillis}. */
        public Double degradedMillis;

        /**
         * Enter level, fixed part. The server counts as degraded only while the smoothed tick time stays above
         * {@code max(degradedFloorMillis, baseline x degradedFactor)} for {@link #sustainTicks}. The floor sits
         * well above a normal busy tick (53-59 ms) so ordinary load never triggers it.
         */
        public double degradedFloorMillis = 65.0;
        /** Enter level, adaptive part: multiple of the server's own measured baseline tick time (see {@link #baselineWindowTicks}). */
        public double degradedFactor = 1.25;
        /**
         * Exit (recovery) level, fixed part; hysteresis: must be lower than the enter level, and still
         * reachable given that the measured tick time never drops below ~50 ms. Recovered means the smoothed tick
         * time stays at or below {@code max(recoveredFloorMillis, baseline x recoveredFactor)} (never above
         * enter level minus 2 ms) for {@link #sustainTicks} and at least {@link #minDwellTicks} were spent degraded.
         */
        public double recoveredFloorMillis = 58.0;
        /** Exit level, adaptive part: multiple of the measured baseline. */
        public double recoveredFactor = 1.10;
        /**
         * Cap for the measured baseline (ms/tick), so a server that happens to start (or spend its whole life)
         * struggling cannot teach the governor that a genuinely awful tick time is "normal". With the defaults
         * the enter level therefore never exceeds 60 x 1.25 = 75 ms.
         */
        public double baselineMaxMillis = 60.0;
        /**
         * Time constant (ticks) of the baseline: a slow moving average of the tick time, updated only while the
         * server is NOT degraded and only from readings below the enter level, so load spikes never raise it.
         * The very first readings are a plain running mean so it converges quickly after start-up.
         */
        public int baselineWindowTicks = 6000;
        /** How long (ticks) the smoothed tick time must stay beyond a level, uninterrupted, before the state flips either way. */
        public int sustainTicks = 600;
        /** Minimum ticks spent in the degraded state before recovery is allowed (no flip-flopping). */
        public int minDwellTicks = 1200;
        /** Ticks between checks, so one round's effect on the tick rate is fully measured before reacting again.
         * Matches {@code TpsGateway}'s own rolling sample window (100 ticks) on purpose: a shorter interval
         * would react to a reading still diluted by ticks from before the last shed. */
        public int checkIntervalTicks = 100;
        /** Base inhabitants removed on the first consecutive degraded check, farthest from the nearest real
         * player first; doubles-by-addition for each further consecutive bad check (2x, 3x, ...), resetting to
         * this base the moment the server is healthy again. No floor: can reduce the live population to 0 if
         * the problem persists (useful for narrowing down whether the inhabitants are even the cause). */
        public int despawnBatchSize = 3;
    }

    /**
     * Structured, throttled logging of combat that involves an inhabitant, so "what did the bots do?" has an
     * answer in the server log. Inhabitants are recognised by this addon's own roster (never by PvP BOT classes).
     * Normal mode writes one INFO summary line per (attacker, victim) pair per {@link #coalesceTicks}, plus a line
     * for every kill and death, all under a global line budget; {@code debug: true} additionally writes every
     * single hit with full detail (damage, source type, weapon, distance, health after) and unattributed damage.
     */
    public static final class CombatLog {
        /** Master switch. */
        public boolean enabled = true;
        /** Hits between the same attacker and victim are coalesced into one summary line per this many ticks. */
        public int coalesceTicks = 100;
        /** Hard cap on INFO combat lines per minute; lines beyond it are counted and reported once as "N suppressed". */
        public int maxLinesPerMinute = 30;
    }

    /** The {@code combat} block: what an inhabitant may do in a fight. */
    public static final class Combat {
        /** No cheating: an inhabitant's melee hit must be one a human client could make; see {@link MeleeLegality}. */
        public MeleeLegality meleeLegality = new MeleeLegality();
    }

    /**
     * The melee legality rule ({@code combat.meleeLegality}). PvP BOT's melee has no line-of-sight check, so an inhabitant
     * used to hit a player THROUGH a wall. With this on, a melee hit by an inhabitant is vetoed (no damage, no knockback)
     * unless a human could have targeted the victim: the crosshair ray from the eye reaches the victim's box within the
     * attacker's entity interaction range (vanilla 3.0, plus a 0.2 lag tolerance) without a block that has a collision
     * shape in between. The rule itself is vanilla and not configurable, only the switch is. Projectiles are never
     * touched. See {@code MeleeLegality} in the sources.
     */
    public static final class MeleeLegality {
        /** Master switch. */
        public boolean enabled = true;
    }

    /**
     * How the hostile inhabitants hunt players and their companions (see {@code AggroController}); everything is decided
     * by what they SEE and HEAR. Sight has no block limit inside the view cone; the one hard-coded distance rule is the
     * ENGAGE LIMIT of 16 blocks (a constant, not a setting): it applies only to starting a new engagement. A target already
     * acquired inside that radius continues to be chased while it remains visible.
     * <ul>
     *   <li><b>Noticing.</b> A player is noticed after staying in view for a reaction time that is one continuous formula
     *       (see {@link AggroPerception}): 0.5 s up close, 2.0 s at 32 blocks, longer at an angle, sneaking or when
     *       hard to see. Nothing is seen behind. Sounds are vanilla vibrations (see {@link AggroHearing}).</li>
     *   <li><b>Chase.</b> While the target is in sight (occlusion only) the inhabitant chases; the fight starts only after
     *       the reaction time, and again after EVERY re-sighting. Not in sight for {@link #loseGraceTicks} (10 = 0.5 s) it has
     *       LOST the target.</li>
     *   <li><b>Pursue, search, return.</b> A lost target: walk to the last place it was seen, search around there for
     *       {@link #searchTicks} (200 = 10 s), then WALK back to where the first hunt began (the home anchor, kept until it
     *       is back within {@link #returnArriveDistance}); seeing the player again starts a new chase, home unchanged.
     *       {@link #stuckTicks} without progress replans the walk; {@link #returnMaxTicks} is the longest walk home.
     *       Never a teleport; no blocks are ever broken or placed for it.</li>
     * </ul>
     * Works together with PvP BOT's auto-target being OFF (then the addon does the noticing); while it is ON PvP BOT
     * notices by itself and only the lost-target search and the walk home apply. The addon says so once in the log.
     */
    public static final class Aggro {
        /** Master switch. */
        public boolean enabled = true;
        /** A player is only noticed when the inhabitant has a line of sight to them (like a vanilla mob). */
        public boolean requireLineOfSight = true;
        /** Ticks without a line of sight to the target after which it counts as lost (10 = 0.5 s). Range 1..72000. */
        public int loseGraceTicks = 10;
        /** Ticks the search lasts after arriving where the target was last seen (200 = 10 s). Range 20..72000. */
        public int searchTicks = 200;
        /** Blocks (horizontal) from home at which the walk back counts as arrived. Range 0.5..16. */
        public double returnArriveDistance = 1.5;
        /** A walk is replanned when it gets no closer to its next waypoint over this many ticks. Range 10..1200. */
        public int stuckTicks = 40;
        /** The walk home gives up after this many ticks; the inhabitant then stays where it is. Range 20..72000. */
        public int returnMaxTicks = 1200;
        /** Ticks between looks for a player, for an idle inhabitant (staggered across inhabitants; 1..40). */
        public int scanIntervalTicks = 3;
        /** How an inhabitant perceives a player: reaction time, view cone, sneaking. See {@link AggroPerception}. */
        public AggroPerception perception = new AggroPerception();
        /** How an inhabitant hears: vanilla vibrations. See {@link AggroHearing}. */
        public AggroHearing hearing = new AggroHearing();
        /** How fast and how steadily an inhabitant aims: human turn speed, aim tolerance, jitter. See {@link AggroAim}. */
        public AggroAim aim = new AggroAim();
    }

    /**
     * Realistic noticing for the aggro controller (the {@code aggro.perception} block; see {@code Perception} and
     * {@code docs/PERCEPTION.md}, the same model Minecraft-AI's bots use).
     * <p>
     * The reaction time is ONE continuous formula in real seconds (no steps):
     * {@code (reactionBaseSeconds + (reactionAt64Seconds - reactionBaseSeconds) * distance / 64) * angleFactor * sneakFactor
     * / visibility}. The angle factor is 1 up to {@link #fullAttentionHalfAngleDeg} and rises linearly to
     * {@link #peripheralMultiplier} at {@link #peripheralHalfAngleDeg}; beyond that nothing is seen. A sneaking player takes
     * {@link #sneakMultiplier} times as long to be spotted. {@code enabled=false} is plain vanilla line of sight: a clear
     * line is noticed at once, no cone, no sneaking, no reaction time.
     */
    public static final class AggroPerception {
        /** Master switch; off = plain vanilla line of sight (no cone, no sneaking, no reaction time). */
        public boolean enabled = true;
        /** Seconds a player right in front must stay in view before an inhabitant reacts (a human's reaction). Range 0..10. */
        public double reactionBaseSeconds = 0.5;
        /** Seconds the same takes for a player at the legacy 64-block reaction measurement; the target-engagement cap is separately 16 blocks. Range base..30. */
        public double reactionAt64Seconds = 2.0;
        /** Half-angle (degrees) of the cone in which the plain reaction time applies. Range 0..180. */
        public double fullAttentionHalfAngleDeg = 30.0;
        /** Half-angle (degrees) out to which the peripheral field reaches; behind it nothing is seen. Range full..180. */
        public double peripheralHalfAngleDeg = 100.0;
        /** How many times longer the reaction takes at the edge of the peripheral field. Range 1..20. */
        public double peripheralMultiplier = 2.0;
        /** How many times longer the reaction takes for a sneaking player. Range 1..20. */
        public double sneakMultiplier = 2.0;
    }

    /**
     * Hearing for the aggro controller (the {@code aggro.hearing} block): the vanilla vibration system, called directly
     * exactly as the Warden and the sculk sensor use it. What is heard, how far, through what and how quickly (sneaking is
     * silent, wool blocks and dampens, sound needs time to travel) are vanilla's own rules. A heard sound whose source the
     * inhabitant can see counts as sight without the view cone; a sound it cannot place is only somewhere to look or search.
     */
    public static final class AggroHearing {
        /** Blocks within which a vibration is heard: 16 is the Warden's listener radius (the sculk sensor's is 8). Range 1..64. */
        public int listenerRadius = 16;
    }

    /**
     * Human aim for the aggro controller (the {@code aggro.aim} block; see {@code HumanAim}). PvP BOT snaps an inhabitant's
     * rotation onto its target every tick, which is instant and perfect: shot in the back, a bot would spin round and shoot at
     * once. These are HUMAN LIMITS, not artificial handicaps: they model a player's hand and eye.
     * <ul>
     *   <li>The head turns at most {@link #maxTurnDegPerSec} (540 = a fast mouse flick, 27 degrees per tick, a half turn in
     *       a third of a second), the shorter way round; the view cone, sight and every shot use where it really looks.</li>
     *   <li>A shot is only released once the aim is within {@link #fireToleranceDeg} of the wanted direction, and within the angle
     *       the target's {@link #fireTargetRadius} subtends at that distance (so it would actually hit).</li>
     *   <li>Right after a flick the hand shakes: jitter of {@code jitterBaseDeg + jitterSettleDeg * exp(-t / jitterSettleSeconds)}
     *       degrees (standard deviation), t being the time since the aim first came on target.</li>
     * </ul>
     * {@code enabled=false} leaves PvP BOT's rotation alone (instant, perfect aim).
     */
    public static final class AggroAim {
        /** Master switch; off = PvP BOT's instant, perfect aim. */
        public boolean enabled = true;
        /** Fastest head turn, degrees per second (540 = 27 per tick). Range 30..3600. */
        public double maxTurnDegPerSec = 540.0;
        /** The widest aim error (degrees) at which a shot is still released, at short range. Range 0.1..10. */
        public double fireToleranceDeg = 1.5;
        /** Blocks: the target's hit radius; at distance d the tolerance is at most atan(radius / d). Range 0.05..1. */
        public double fireTargetRadius = 0.25;
        /** Steady aim jitter, degrees (standard deviation). Range 0..5. */
        public double jitterBaseDeg = 0.3;
        /** Extra jitter right after the aim came on target, degrees (standard deviation). Range 0..15. */
        public double jitterSettleDeg = 2.5;
        /** Time constant (seconds) of how fast that extra jitter fades. Range 0.01..5. */
        public double jitterSettleSeconds = 0.25;
    }

    /**
     * Without this, a spawned inhabitant is a real player-like entity (see the addon README) that keeps
     * ticking forever no matter how far the player travels -- PvP BOT gives it none of vanilla's distance-based
     * entity unloading. Left alone, the live population would only ever grow as the player explores. This
     * periodically removes inhabitants that have stayed far from every real player for a while (a bot a player has
     * seen sleeps with its whole state and wakes unchanged, one nobody saw is deleted and its slot is vacant), which
     * lets the population settle to an equilibrium around wherever the player actually is.
     * <p>
     * SUPERSEDED by {@link Allocation} while that runs (it is enabled and a real player is online): its relevance
     * area does the same job structure by structure, so this distance rule is then not run at all, {@code
     * distanceBlocks} only caps the relevance area (never below the simulation distance), and {@code enabled=false} stops the allocation from removing bots
     * that left it. {@code delayTicks} and {@code scanIntervalTicks} only apply to the fallback (allocation off, or no
     * player known). See {@link TpsThrottle} for the separate, reactive mechanism that responds to server load.
     */
    public static final class Dormancy {
        /** Master switch. */
        public boolean enabled = true;
        /** Blocks from the nearest online real player beyond which a live inhabitant is a dormancy candidate. */
        public double distanceBlocks = 160.0;
        /** A candidate must stay beyond that distance for this many consecutive ticks before it actually goes
         * dormant, so a brief detour or flyby does not despawn it. */
        public int delayTicks = 1200;
        /** Ticks between distance scans. */
        public int scanIntervalTicks = 100;
    }

    /**
     * Nearest-first dynamic fill. The structure nearest to any real player (3D distance to its bounding box, so a trial
     * chamber 90 blocks below is farther than a village 60 blocks away on the surface) gets its full fill target first
     * (the size logic: {@code processing.blocksPerBot}), then the next nearest gets the rest of {@code processing.maxLiveBots},
     * and so on. As soon as the order changes the allocation is recomputed: bots of structures that dropped out go away
     * (a bot a player has SEEN sleeps with its whole state, an unseen one is deleted and its slot is free for a fresh roll),
     * bots of structures that came in are woken or rolled. Deaths are never refilled.
     * <p>
     * Only structures within the players' relevance area (the server simulation distance plus {@code relevanceExtraChunks}
     * chunks, capped by {@code dormancy.distanceBlocks} while dormancy is enabled but never smaller than the simulation distance
     * itself) can host bots. Every value here has a sane
     * bound (see {@code ConfigValidator}). When {@code enabled} is false, or no real player is online, population is first
     * come, first served and {@link Dormancy} sleeps far bots, as before.
     */
    public static final class Allocation {
        public boolean enabled = true;
        /** Ticks between two allocation passes (at most one per this many ticks). */
        public int intervalTicks = 20;
        /** The order is recomputed only when a real player moved at least this far (blocks) since the last pass, or on an event. */
        public double moveThresholdBlocks = 4.0;
        /** A structure must be this much (blocks) nearer than one that is already allocated to displace it. */
        public double hysteresisBlocks = 8.0;
        /** A bot is not removed within this many ticks of being spawned or woken, unless the room is needed for a structure {@link #dwellOverrideBlocks} nearer. */
        public int dwellTicks = 400;
        public double dwellOverrideBlocks = 32.0;
        /** A structure that drops out of the allocation keeps its live bots this many ticks before they are removed. */
        public int graceTicks = 200;
        /** Chunks added to the server simulation distance to get the relevance area of a player. */
        public int relevanceExtraChunks = 2;
        /** Ticks between two looks at whether a player sees an unseen bot. */
        public int seenCheckTicks = 10;
        /** Half-angle of the view cone (degrees) a bot must be inside to count as seen: generous, so wide field-of-view settings are covered. */
        public double seenHalfAngleDeg = 70.0;
    }

    public static final class Spawning {
        /** AUTO (BotManager.spawnBot, falling back to /pvpbot spawn), CLASS, or COMMAND. */
        public String backend = "AUTO";
        /** Candidate positions tried per bot before giving up on that bot. */
        public int positionAttemptsPerBot = 80;
        /** Minimum distance in blocks between two bots of the same structure. */
        public double minBotSeparation = 3.0;
        /** Permit standing in water (some structures, e.g. ocean ruins, need it). Lava is never allowed. */
        public boolean allowSubmerged = false;
        /**
         * Optional reserved prefix for generated bot names (up to 8 letters/digits/underscores; names are
         * at most 16 characters). Empty by default: {@link dev.spawnbotswrapper.inhabitants.engine.NameGenerator}
         * already draws from a large two-word pool ({@code DuskRaven}, {@code IronFang7}, ...), so an
         * inhabitant reads as a real player name instead of every single one sharing an obvious system tag.
         * Set this only on an online-mode server where a generated name might otherwise collide with a
         * real Mojang account and take over its UUID/skin; a distinctive prefix (e.g. {@code "Inh"}) rules
         * that out entirely, at the cost of every inhabitant visibly sharing it.
         */
        public String namePrefix = "";
    }

    private static Map<String, RuleOverride> defaultStructureOverrides() {
        Map<String, RuleOverride> m = new LinkedHashMap<>();
        m.put("minecraft:pillager_outpost", new RuleOverride(0.85, 2));
        m.put("minecraft:mansion", new RuleOverride(0.90, 2));
        m.put("minecraft:ancient_city", new RuleOverride(0.50, 1));
        m.put("minecraft:trial_chambers", new RuleOverride(0.60, 1));
        return m;
    }

    private static Map<String, RuleOverride> defaultTagOverrides() {
        Map<String, RuleOverride> m = new LinkedHashMap<>();
        m.put("#minecraft:village", new RuleOverride(0.70, 1));
        return m;
    }
}
