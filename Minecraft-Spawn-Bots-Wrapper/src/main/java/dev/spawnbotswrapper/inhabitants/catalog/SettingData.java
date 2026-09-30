package dev.spawnbotswrapper.inhabitants.catalog;

import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Category;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Mechanism;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.SettingSpec;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.ValueType;

import java.util.List;

import static dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Mechanism.ATTRIBUTE;
import static dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Mechanism.LOADOUT;
import static dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Mechanism.PATH;

/**
 * The classification of every PvP BOT v0.0.15 setting, in upstream declaration order. Ranges and defaults
 * are the setter clamps and field initialisers of {@code BotSettings}; the test baseline
 * {@code upstream-settings-0.0.15.csv} is derived from the same source, so a typo here fails a test.
 * <p>
 * How the categories were decided: PvP BOT reads ONE process-wide settings object for every bot, so a
 * setting is PER_BOT_RANDOMIZABLE only when something PvP BOT genuinely reads per bot (inventory, an
 * attribute, its path system) switches the same behaviour on or off. Where the proxy is only partial,
 * the note says exactly what varies and what does not; where the two usage audits disagreed, the
 * conservative reading was taken and the note says so.
 * <p>
 * Notes are plain ASCII without pipes or angle brackets so the generated Markdown stays byte-stable.
 */
final class SettingData {
    private static final String NO_KEY = "";

    private SettingData() {
    }

    static List<SettingSpec> entries() {
        return List.of(
                // ---- equipment housekeeping
                flag("autoEquipArmor", "auto-armor", true).perBot(LOADOUT,
                        "loadout: worn armor slots plus a strictly higher-tier spare armor piece in inventory",
                        "Truthful. Every checkInterval ticks (not while eating) a bot swaps in a strictly higher-tier "
                                + "armor piece found in its inventory; the score is tier only (netherite, diamond, "
                                + "iron, turtle helmet, chainmail, golden, leather, elytra; copper scores 0) with no "
                                + "enchantment or durability awareness. Varies: whether the bot carries a better spare "
                                + "than it wears. Does not vary: a bot that carries a better spare cannot be made to "
                                + "keep the worse piece."),
                flag("autoEquipWeapon", "auto-weapon", true).global(
                        "No per-bot proxy: a housekeeping routine run for every bot that carries a scored weapon "
                                + "(sword, axe, trident, mace). When the best one sits in the main inventory it is "
                                + "swapped into hotbar slot 0 and selected, displacing slot 0; a bow pushed out of the "
                                + "hotbar that way is not held for shooting. It cannot be switched off per bot, so "
                                + "this is a layout constraint on loadouts (best melee weapon in slot 0), not a trait. "
                                + "Worse than a layout constraint: every checkInterval ticks it re-selects the melee weapon, "
                                + "which ends any bow or crossbow draw in progress of a bot that carries both, so such a bot "
                                + "never shoots. This addon therefore manages it: config pvpbotSettings.autoEquipWeapon "
                                + "(shipped false) is written into the settings whenever PvP BOT loads them; melee combat "
                                + "selects its own weapon regardless. Audits: partial at best."),
                flag("dropWorseArmor", "drop-armor", false).global(
                        "No per-bot proxy: default-off toggle that tosses strictly worse armor of the same slot after "
                                + "each equip pass (elytra counts as chest armor and goes once a chestplate is worn). "
                                + "No loadout can enable or disable it for one bot. Hazard if an admin enables it: "
                                + "spare armor and elytras are dumped on the ground."),
                flag("dropWorseWeapons", "drop-weapon", false).global(
                        "No per-bot proxy: default-off toggle that drops every scored weapon below the best one in "
                                + "slots 0-35, including maces, tridents and axes, so a mixed loadout would be "
                                + "dismantled. Cannot be set per bot; loadouts must not rely on backup weapons if an "
                                + "admin enables it."),
                decimal("dropDistance", "drop-distance", 1.0, 10.0, 3.0).admin(
                        "Not a distance: it scales the toss speed (0.3 x value) of items dropped by the two dropWorse "
                                + "settings and is irrelevant while they are off. Never touched."),
                whole("checkInterval", "interval", 1, 100, 20).admin(
                        "One global tick counter that paces equipment checks for all bots in the same tick. Values "
                                + "below 10 also stop PvP BOT's dead-bot cleanup (it only runs at counter multiples of "
                                + "10), so an operator warning is appropriate. Never changed."),

                // ---- combat core and targeting
                flag("combatEnabled", "combat", true).global(
                        "No per-bot proxy any more: every inhabitant fights, so this addon builds every path with "
                                + "attack=true. (A follower of a path with attack=false skips PvP BOT's whole combat AI "
                                + "-- no targeting, no retaliation, no shield or weapon combat logic, no idle wander -- "
                                + "which is what the retired 'pacifist' half of the behaviour deck did to roughly "
                                + "half of all guards and patrols; the store migrates old pacifist profiles to "
                                + "fighters.) The global switch still applies to everyone. Trap: a follower whose "
                                + "path vanished is silently pacified and stops moving."),
                flag("revengeEnabled", "revenge", true).global(
                        "No per-bot proxy: remembers the last living attacker for 30 s and targets it, bypassing the "
                                + "three target filters; absorption or resistance suppress it only as a perverse "
                                + "side effect. With defaults inhabitants stay passive until hit."),
                flag("autoTargetEnabled", "auto-target", false).global(
                        "No per-bot proxy: PvP BOT's own acquisition of the nearest entity within maxTargetDistance (no line of "
                                + "sight needed). Managed by this addon: config pvpbotSettings.autoTargetEnabled (shipped false), "
                                + "because the addon's aggro controller (config aggro) acquires instead, by line of sight: players it can see "
                                + "(no block limit), plus a chase after a hit from farther away. Revenge, faction enemies and forced "
                                + "orders (which the aggro range uses) still apply. The value is written into the field, like the "
                                + "others; with aggro.enabled false and this managed as false inhabitants stay passive until hit."),
                flag("targetPlayers", "target-players", true).global(
                        "No per-bot proxy: global filter inside auto-target for real players (names not registered as "
                                + "bots). Forced, revenge and faction targets bypass it."),
                flag("targetHostileMobs", "target-mobs", false).global(
                        "No per-bot proxy: global auto-target filter. Naming trap: it is also the flag for every "
                                + "non-hostile mob (animals, villagers, golems), so enabling it makes inhabitants "
                                + "attack village life."),
                flag("targetOtherBots", "target-bots", false).global(
                        "No per-bot proxy: global auto-target filter for bots registered with PvP BOT, inhabitants "
                                + "included. The default keeps inhabitants from auto-targeting each other; faction "
                                + "enemies bypass it."),
                decimal("maxTargetDistance", "view-distance", 5.0, 128.0, 64.0).global(
                        "No per-bot proxy: search and acceptance radius (blocks, 3D, no dimension check) for forced, "
                                + "revenge, faction and auto targets; also sizes the per-tick entity query when "
                                + "auto-target is on. A forced order beyond it is ignored. This addon manages it: config "
                                + "pvpbotSettings.maxTargetDistance (shipped 128 = PvP BOT's catalog maximum, allowed 4..128) "
                                + "is written into the settings whenever PvP BOT loads them. 128 is a ceiling only: line of "
                                + "sight decides when a chase ends, and the high value keeps PvP "
                                + "BOT from dropping a chased target early and lets a hit from far away register as revenge."),
                decimal("meleeRange", "melee-range", 2.0, 6.0, 3.5).perBot(ATTRIBUTE,
                        "vitals.attributes[minecraft:entity_interaction_range]",
                        "Partial, reach only. Varies: entity_interaction_range 2..6 (vanilla 3.0); on the 'attack "
                                + "once' path (criticals off, mace hits, shield-break hits) effective reach = "
                                + "min(attribute, global meleeRange), so it can only clamp down. Does not vary: "
                                + "approach and attack-attempt distance stay the global meleeRange, and the default "
                                + "jump-crit path (criticals on) calls the vanilla attack directly with no reach "
                                + "check. A block in the line of sight is hit first. Verified in HeroBot HEAD only."),
                decimal("rangedMinRange", "ranged-min-range", 3.0, 20.0, 20.0).global(
                        "No per-bot proxy: numeric tuning. Archers park at about min to min+2 blocks (20 to 22 by "
                                + "default), not at rangedOptimalRange; the loadout only decides who is an archer. The "
                                + "default equals the clamp maximum. Managed by this addon: config "
                                + "pvpbotSettings.rangedMinRange (shipped 8), written straight into the field because the "
                                + "setter would clamp the ranges below."),
                decimal("rangedOptimalRange", "ranged-optimal-range", 10.0, 50.0, 40.0).global(
                        "No per-bot proxy: only used while retreating with a bow (no food, health under 50 percent), "
                                + "not in normal engagement. The loadout only decides who is an archer. Managed by this "
                                + "addon: config pvpbotSettings.rangedOptimalRange (shipped 12)."),
                decimal("rangedMaxRange", "ranged-max-range", 15.0, 100.0, 60.0).global(
                        "No per-bot proxy: archers walk toward a target beyond this distance instead of shooting. The "
                                + "loadout only decides who is an archer. Managed by this addon: config "
                                + "pvpbotSettings.rangedMaxRange (shipped 16). The managed values are written straight into the fields, "
                                + "so they may deliberately lie outside PvP BOT's setter clamps (the shipped 8/12/16 happen to "
                                + "sit inside them; the catalog bounds only describe what the setters accept)."),
                decimal("maceRange", NO_KEY, 3.0, 10.0, 6.0).global(
                        "No per-bot proxy: mace mode-selection distance and engagement radius (twice the value). A "
                                + "mace in the loadout only decides who can use mace mode. No command key: "
                                + "settings.json or the GUI payload only."),
                whole("attackCooldown", "attack-cooldown", 1, 40, 10).perBot(ATTRIBUTE,
                        "vitals.attributes[minecraft:attack_speed]; loadout: the held weapon sets the vanilla recharge",
                        "Partial, attack tempo. Varies: the real interval, max(global attackCooldown, vanilla recharge "
                                + "of the held weapon at the bot's attack_speed); the attribute and the weapon change "
                                + "how long a bot waits, never below the global floor. Does not vary: the setting "
                                + "itself, and the low-health multiplier (x1.5) hits every bot. At the default of 10 "
                                + "ticks the weapon already dominates (sword about 12.5 ticks, axe 20 or more)."),
                decimal("moveSpeed", "move-speed", 0.1, 2.0, 1.0).global(
                        "No per-bot proxy. Audits split: one saw movement_speed as a coarse substitute, the other "
                                + "showed that HeroBot overwrites the input fields PvP BOT writes, so all motion is "
                                + "velocity impulses scaled by this global scalar and neither the attribute nor speed "
                                + "potions change it. Conservative: not an attribute upstream, not randomized. A path "
                                + "walkType changes sprint and hop, not this scalar, and path followers move at a "
                                + "fixed 1.0. Verified in HeroBot HEAD only."),
                flag("criticalsEnabled", "criticals", true).global(
                        "No per-bot proxy: global toggle for jump-crit hits; it also decides which melee path is used "
                                + "(direct attack versus 'attack once'), see meleeRange. jump_strength or gravity "
                                + "attributes can only sabotage it (a too short fall phase means the bot never "
                                + "attacks), so they are never varied."),
                whole("criticalFallTicks", "crit-fall-ticks", 1, 10, 6).global(
                        "No per-bot proxy: consecutive falling ticks required before a crit hit. Attribute-sensitive "
                                + "only in a perverse way (jump strength, gravity), so not varied. The melee routine "
                                + "only swings after a full jump-and-fall of this many ticks, so a large value makes "
                                + "bots look passive at close range. This addon manages it: at every server start it runs "
                                + "'pvpbot settings crit-fall-ticks 3' (config key criticalFallTicks, 0 = unmanaged) "
                                + "instead of keeping upstream's 6."),
                whole("bowMinDrawTime", "bow-draw-ticks", 5, 100, 40).global(
                        "No per-bot proxy: ticks a bow is drawn before release (crossbows use a fixed 25). Quick "
                                + "Charge and Power enchantments are never consulted, so enchanting the bow does not "
                                + "substitute."),

                // ---- weapon modes and gear behaviours
                flag("rangedEnabled", "ranged", true).perBot(LOADOUT,
                        "loadout: hotbar bow/crossbow + arrows",
                        "Partial, gate by presence. A bow or crossbow in slots 0-35 (a bow must be in hotbar 0-8 to be "
                                + "held) plus at least one arrow in slots 0-35 (offhand arrows do not count) puts the "
                                + "bot in ranged mode. Varies: archer or not. Does not vary: an archer cannot also "
                                + "melee at the default ranged-retreat, so bow plus sword is an archer. Traps: a bow "
                                + "without arrows is inert; a charged crossbow is never fired (unverified). Rolled "
                                + "crossbows never carry Piercing (profiles.disabledEnchantments, on by default: a "
                                + "piercing bolt ignores a raised shield, so it could not be blocked). Audits "
                                + "split: partial versus truthful."),
                flag("maceEnabled", "mace", true).perBot(LOADOUT,
                        "loadout: hotbar mace, optionally wind_charge",
                        "Partial. A mace in the hotbar lets mace mode win within maceRange, ahead of spear, melee and "
                                + "ranged; a wind_charge in slots 0-35 makes the smash real. Varies: mace user or not. "
                                + "Does not vary: an elytra plus firework_rocket plus mace kit runs a separate dive "
                                + "routine that ignores this setting, so do not combine that trio unless the dive is "
                                + "wanted. Audits split: partial versus truthful."),
                flag("spearEnabled", NO_KEY, false).perBot(LOADOUT,
                        "loadout: hotbar spear, only while the global switch is on",
                        "Truthful once the global switch is on, dormant at the default (off): then a hotbar spear "
                                + "(item id contains 'spear') selects spear mode, otherwise spears are ignored "
                                + "everywhere. Varies: spear user or not, only while the switch is on. Does not vary: "
                                + "the switch itself, which the addon never turns on because that affects every bot. "
                                + "Audits split: truthful if enabled versus none because it defaults off; the code "
                                + "path is unambiguous. No command key."),
                flag("crystalPvpEnabled", NO_KEY, true).perBot(LOADOUT,
                        "loadout: obsidian + end_crystal together",
                        "Truthful. Obsidian AND end_crystal both in slots 0-35 enable crystal mode (first priority, "
                                + "target 2.5-8 blocks): the bot places obsidian and crystals and detonates them, "
                                + "destroying blocks and hurting bystanders. Varies: crystal fighter or not. Does not "
                                + "vary: the mode priority or the damage, which come with the items; any bot carrying "
                                + "both does it, so carry them only deliberately. No command key."),
                flag("anchorPvpEnabled", NO_KEY, true).perBot(LOADOUT,
                        "loadout: respawn_anchor + glowstone together",
                        "Truthful. respawn_anchor AND glowstone both in slots 0-35 enable anchor mode (second "
                                + "priority, target 2-8 blocks, health at least 40 percent, dimension id without "
                                + "'nether'); the explosions destroy blocks. Varies: anchor fighter or not. Does not "
                                + "vary: the mode priority or the damage, which come with the items. No command key."),
                decimal("spearRange", NO_KEY, 2.0, 8.0, 4.5).global(
                        "No per-bot proxy: distance at which spear mode is preferred over melee and ranged, behind a "
                                + "default-off feature. Spear carriers only decide who is affected. No command key."),
                decimal("spearChargeRange", NO_KEY, 5.0, 20.0, 12.0).global(
                        "No per-bot proxy: spear-mode engagement radius; the charge-start distance inside the spear "
                                + "handler is hard-coded. No command key."),
                flag("autoTotemEnabled", "auto-totem", true).perBot(LOADOUT,
                        "loadout: totem_of_undying in the offhand or inventory",
                        "Truthful. A totem_of_undying anywhere in slots 0-35 is swapped into the offhand every tick "
                                + "(unless the bot is blocking), pushing the offhand item into the totem's old slot; "
                                + "the pop itself is vanilla and independent. Varies: totem carrier or not. Does not "
                                + "vary: where the totem ends up, always the offhand; a shield left there beside spare "
                                + "totems is swapped out."),
                flag("totemPriority", "totem-priority", true).perBot(LOADOUT,
                        "loadout: totem_of_undying in the offhand + shield",
                        "Partial. Only a bot that carries a shield AND holds a totem in the offhand uses the main-hand "
                                + "shield route (the shield goes through hotbar slot 1, which becomes scratch space); "
                                + "every other shield carrier takes the offhand route, which overwrites and destroys a "
                                + "non-shield offhand item. Varies: which route the bot takes. Does not vary: a "
                                + "totem-offhand bot cannot be given the other branch. Audits split: truthful versus "
                                + "partial."),
                flag("autoEatEnabled", "auto-eat", true).perBot(LOADOUT,
                        "loadout: food item, golden apple for the low-health trigger; vitals.foodLevel: at or below "
                                + "minHungerToEat makes a food carrier eat at spawn",
                        "Partial. Eating needs a food item in slots 0-35 (offhand excluded); triggers are hunger at or "
                                + "below minHungerToEat, health at most 50 percent (golden apple preferred) or at most "
                                + "30 percent. Varies: eater or not. Does not vary: the triggers, and the same food "
                                + "gates retreatEnabled, so 'eats but never retreats' cannot be expressed. Vanilla "
                                + "refuses normal food at full hunger (unverified), so full-hunger bots only heal with "
                                + "golden apples."),
                flag("autoShieldEnabled", "auto-shield", true).perBot(LOADOUT,
                        "loadout: vanilla shield in the offhand or inventory",
                        "Partial. Blocking needs a vanilla shield (offhand or slots 0-35; modded shields are "
                                + "invisible); with none, PvP BOT's block logic does nothing. Varies: blocker or not. "
                                + "Does not vary: when it blocks (global timings). Leak: the standard-retreat branch "
                                + "(food and low health) still sends 'use continuous' without checking for a shield, "
                                + "so a shieldless bot uses its main-hand item. Audits split: partial versus "
                                + "truthful."),
                flag("autoMendEnabled", "auto-mend", true).perBot(LOADOUT,
                        "loadout: experience_bottle + worn Mending armor with damageFraction",
                        "Truthful. Needs an experience_bottle in slots 0-35 and WORN armor with Mending whose "
                                + "durability fraction is below mendDurabilityThreshold. Inactive while Mending is on "
                                + "profiles.disabledEnchantments (the default): no inhabitant wears Mending armor, "
                                + "so the routine never starts; experience bottles stay in loadouts as loot for "
                                + "players. Varies: mender or not (only with Mending allowed). Does "
                                + "not vary: the trigger threshold; fresh gear never triggers it, so a loadout must "
                                + "pre-damage the armor to show it. While mending the bot stops fighting and throws "
                                + "bottles at its feet (whether bots collect the orbs is unverified)."),
                decimal("mendDurabilityThreshold", NO_KEY, 0.1, 0.9, 0.25).global(
                        "No per-bot proxy: a FRACTION (not a percent) compared with worn armor durability. "
                                + "Pre-damaging armor below it forces mending, but that is a state hack, not a per-bot "
                                + "threshold. No command key."),
                decimal("shieldHealthThreshold", NO_KEY, 0.1, 1.0, 0.5).global(
                        "No per-bot proxy: health RATIO (not a percent) below which the melee handler holds a shield "
                                + "and multiplies the attack cooldown by 1.5, even for bots without a shield. "
                                + "max_health rescales absolute HP but never the ratio. No command key."),
                flag("shieldBreakEnabled", "shield-break", true).perBot(LOADOUT,
                        "loadout: vanilla axe",
                        "Truthful. Any vanilla axe in slots 0-35 lets the bot swap to it and strike a blocking PLAYER "
                                + "target (every second tick, shieldBreakChance percent). Varies: shield-breaker or "
                                + "not. Does not vary: the chance. Modded and copper axes score 0 and are never "
                                + "picked; the axe becomes the held weapon for that swing."),
                whole("shieldHoldTicks", "shield-hold-ticks", 10, 200, 60).global(
                        "No per-bot proxy: how long the auto-shield stays raised after its last trigger. Unrelated to "
                                + "the internal combat counter of the same name."),
                whole("shieldRaiseTicks", "shield-raise-ticks", 2, 40, 12).global(
                        "No per-bot proxy: prediction lead time when an enemy player sprints toward the bot (hold time "
                                + "is max(6, value/2)). A shield only decides whether it has any effect."),
                flag("preferSword", "prefer-sword", true).perBot(LOADOUT,
                        "loadout: only one melee weapon class, sword-only or axe-only",
                        "Partial. A bot that carries only swords or only axes has its weapon class pinned regardless "
                                + "of the flag. Varies: sword user or axe user. Does not vary: the tie-break for a bot "
                                + "that carries both stays the global preferSword (+5 for swords). The score table is "
                                + "vanilla-only; mace, spear and modded weapons score 0. Audits split: partial versus "
                                + "truthful."),
                whole("minHungerToEat", NO_KEY, 1, 20, 14).global(
                        "No per-bot proxy: hunger threshold in points. A starting food level can be seeded per bot but "
                                + "that is state, not the threshold, and a stationary inhabitant barely loses hunger. "
                                + "No command key."),
                flag("autoPotionEnabled", "auto-potion", true).perBot(LOADOUT,
                        "loadout: splash healing potion, optionally splash strength/swiftness/fire_resistance",
                        "Truthful. Healing potions (splash or lingering, else drinkable) in slots 0-35 are used at the "
                                + "retreat health ratio while a target exists; splash or lingering strength, swiftness "
                                + "and fire_resistance potions are thrown as buffs. Varies: potion user or not, and "
                                + "which kinds. Does not vary: the health ratio at which healing starts. Leak: one "
                                + "switch drives both. A permanent effect longer than 100 ticks suppresses the "
                                + "matching buff throw."),
                flag("cobwebEnabled", NO_KEY, true).perBot(LOADOUT,
                        "loadout: cobweb",
                        "Truthful. A cobweb in slots 0-35 lets the bot place webs on its target (in retreat within 8 "
                                + "blocks, or in melee at 2-6 blocks against a moving target); without one it never "
                                + "does. Varies: web placer or not. Does not vary: the bot's own escape from webs "
                                + "(water_bucket only: ender pearls are never stocked because the pearl escape "
                                + "loop cancels attacks), a separate ungated mechanism. Leak: a decorative "
                                + "cobweb is used as a weapon. No command key."),
                flag("retreatEnabled", "retreat", true).perBot(LOADOUT,
                        "loadout: any food item; vitals.healthFraction: starting below retreatHealthPercent retreats "
                                + "at first contact",
                        "Partial. The standard retreat requires a food item in slots 0-35 (with health at most "
                                + "retreatHealthPercent, or at most criticalHealthPercent); without food it never "
                                + "happens. Varies: retreater or not. Does not vary: the health thresholds, which stay "
                                + "global. Leaks: a food-less bot with bow, arrows and health under 50 percent still "
                                + "kites, a bot runs away while eating, and the same food gates autoEat. Audits split: "
                                + "partial versus truthful."),
                decimal("retreatHealthPercent", NO_KEY, 0.1, 0.9, 0.3).global(
                        "No per-bot proxy: health RATIO (not a percent) that starts retreat and healing-potion use. "
                                + "max_health rescales absolute HP but not the ratio; starting health is state, not a "
                                + "threshold. No command key."),
                decimal("criticalHealthPercent", NO_KEY, 0.05, 0.5, 0.15).global(
                        "No per-bot proxy: health ratio at which retreat ignores the shield-broken suppression. No "
                                + "ordering is enforced against retreatHealthPercent. No command key."),

                // ---- movement, idle behaviour, factions and accuracy
                flag("bhopEnabled", "bhop", true).perBot(PATH,
                        "behavior.walkType",
                        "Partial, path followers only. A path's walkType replaces this flag for ALL of the follower's "
                                + "movement, combat included: bhop = jump-hop, sprint = no hop, walk = no hop and no "
                                + "sprint (a walk-path bot fights at walking pace). Varies: hop, sprint or walk style. "
                                + "Does not vary: STAND bots use the global flag and always sprint. The only "
                                + "per-entity override of a setting in PvP BOT."),
                flag("idleWanderEnabled", "idle", false).perBot(PATH,
                        "behavior.stance",
                        "Partial, same trait by a different mechanism. Varies: PATROL_PINGPONG and PATROL_CYCLE make a "
                                + "bot roam along path waypoints, GUARD_POST holds a post and returns to it after a "
                                + "fight, STAND stands still. Does not vary: upstream's random wander is not enabled "
                                + "for that bot, and the path route is deterministic at path walk speed. If an admin "
                                + "enables the global wander, path followers run both movers in the same tick."),
                decimal("idleWanderRadius", "idle-radius", 3.0, 50.0, 10.0).perBot(PATH,
                        "behavior.patrolRadius: waypoints stay within it; behavior.waypointCount",
                        "Partial, patrol radius. Varies: waypoints are planned within the profile's patrol radius of "
                                + "the bot's home position. Does not vary: upstream's global wander radius stays "
                                + "untouched. Path steering is straight-line with no pathfinding and needs Y-exact "
                                + "waypoints, so waypoints across walls jam the bot."),
                flag("factionsEnabled", NO_KEY, true).global(
                        "No per-bot proxy for the switch. Faction membership is a real per-bot upstream lever (by "
                                + "name, kept in factions.json) but the addon does not use it: faction targeting is "
                                + "proactive and would need shared persistent files with entries upstream never "
                                + "cleans. No command key."),
                flag("friendlyFireEnabled", "friendly-fire", false).global(
                        "No per-bot proxy: global ally rule (allies are members of the same faction by name), "
                                + "irrelevant while no factions exist."),
                whole("missChance", "miss-chance", 0, 100, 0).global(
                        "No per-bot proxy: percent chance an attack becomes a swing. Per-bot skill cannot be expressed "
                                + "through loadout or attributes."),
                whole("mistakeChance", "mistake-chance", 0, 100, 0).global(
                        "No per-bot proxy: percent chance of a random yaw offset (up to 30 degrees either way) before "
                                + "an 'attack once'; the jump-crit path ignores it."),
                whole("shieldBreakChance", "shield-break-chance", 0, 100, 40).global(
                        "No per-bot proxy: percent chance per eligible tick to break a blocking target's shield. An "
                                + "axe only decides whether the bot can try."),

                // ---- upstream tail: persistence and spawn switches interleaved with late-added combat settings
                flag("botsRelogs", NO_KEY, true).admin(
                        "Restore-on-restart switch for PvP BOT's bots.json: when off, the registry is wiped at every "
                                + "server start and inhabitants would be forgotten upstream. Operators should keep it "
                                + "on; the addon never writes it. No command key."),
                flag("useSpecialNames", "special-names", false).admin(
                        "Only affects PvP BOT's own name generator (auto-named and mass spawns), which also performs "
                                + "blocking network I/O. The addon supplies its own names and never calls it."),
                flag("botLeaveOnDeath", "bot-leave-on-death", true).admin(
                        "Re-pushed into HeroBot every 20 ticks, so it cannot be set independently. Decides whether a "
                                + "dead bot disconnects (on) or respawns unmanaged (off); death is permanent for the "
                                + "addon, which relies on it being on. Never touched."),
                flag("attackInvincible", "attack-invincible", false).global(
                        "No per-bot proxy: global filter that lets bots target creative, spectator and invulnerable "
                                + "players (a forced order on such a player is otherwise wiped)."),
                decimal("aimSpeed", "aim-speed", 3.0, 90.0, 90.0).global(
                        "No per-bot proxy: maximum degrees per look call (per tick, not per second as the wiki says) "
                                + "in PvP BOT's navigation helpers; bow, cobweb and crystal aiming snap instantly and "
                                + "ignore it. The command accepts only 3..45 although the setter clamp is 3..90, so "
                                + "the default is unreachable through the command."),
                flag("shieldMace", "shield-mace", true).global(
                        "No per-bot proxy. Both audits: partial. The defence is triggered by the OPPONENT (a player "
                                + "wielding a falling mace) and freezes the bot's attacks for up to 20 ticks per "
                                + "detected dive even when it carries no shield; a shield only makes the block real, "
                                + "so withholding one does not switch the behaviour off."),
                whole("maxMassSpawn", "max-mass-spawn", 50, 10000, 1000).admin(
                        "Caps one mass-spawn command only; PvP BOT's programmatic spawn path has no cap, so the addon "
                                + "enforces its own limits. Never touched."),
                flag("arrowPredictionEnabled", "arrow-prediction", true).global(
                        "No per-bot proxy: aim-quality toggle for bow and crossbow users (leads the target and "
                                + "compensates gravity)."),
                flag("rangedStrafeEnabled", "ranged-strafe", true).global(
                        "No per-bot proxy: sideways strafing while shooting. It writes an input field HeroBot may "
                                + "overwrite, so it is possibly inert (unverified for the shipped HeroBot)."),
                flag("rangedRetreatOnClose", "ranged-retreat", true).global(
                        "No per-bot proxy: decides whether a bow carrier keeps shooting at point blank (on, default) "
                                + "or switches to melee if it carries a melee weapon. Loadout composition does not "
                                + "substitute: at the default every bow-plus-arrows bot is an archer, so hybrids "
                                + "collapse to archers."),
                flag("profileLagFix", "profile-lagg-fix", true).admin(
                        "Spawn-path optimisation: pre-fills the profile cache before each playerspawn issued by PvP "
                                + "BOT's own spawn method. Never touched. The command key really is spelled with a "
                                + "double g."),
                flag("safeSpawn", "safe-spawn", true).admin(
                        "Random 0.1-0.5 block offset applied only by mass-spawn and faction teleport, not by PvP BOT's "
                                + "spawn method; the addon must place bots itself. Never touched."),
                flag("clearOnRemove", "clear-on-remove", true).admin(
                        "Empties a bot's inventory before PvP BOT removes it, so nothing drops. Dead-bot cleanup does "
                                + "not clear, so an inhabitant killed in combat drops its loadout normally. Never "
                                + "touched."));
    }

    private static Draft flag(String field, String commandKey, boolean defaultValue) {
        return new Draft(field, commandKey, ValueType.BOOLEAN, Double.NaN, Double.NaN, Boolean.toString(defaultValue));
    }

    private static Draft whole(String field, String commandKey, int min, int max, int defaultValue) {
        return new Draft(field, commandKey, ValueType.INT, min, max, Integer.toString(defaultValue));
    }

    private static Draft decimal(String field, String commandKey, double min, double max, double defaultValue) {
        return new Draft(field, commandKey, ValueType.DOUBLE, min, max, Double.toString(defaultValue));
    }

    /** The mechanical half of a spec; the classification half is added by exactly one of the finishers. */
    private record Draft(String field, String commandKey, ValueType type, double min, double max, String defaultValue) {

        SettingSpec perBot(Mechanism mechanism, String facet, String note) {
            return finish(Category.PER_BOT_RANDOMIZABLE, mechanism, facet, note);
        }

        SettingSpec global(String note) {
            return finish(Category.GLOBAL_ONLY, Mechanism.NONE, "", note);
        }

        SettingSpec admin(String note) {
            return finish(Category.ADMIN_OPERATIONAL, Mechanism.NONE, "", note);
        }

        private SettingSpec finish(Category category, Mechanism mechanism, String facet, String note) {
            return new SettingSpec(field, commandKey, type, min, max, defaultValue, category, mechanism, facet, note);
        }
    }
}
