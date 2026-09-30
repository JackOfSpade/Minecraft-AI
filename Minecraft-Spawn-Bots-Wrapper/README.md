# PvP BOT Inhabitants

A standalone Fabric addon for **Minecraft 1.21.11** that populates registered structures — vanilla or
modded — with bots supplied by [PvP BOT](https://modrinth.com/mod/pvp-bot-fabric). Every structure is
rolled **exactly once**, permanently, as occupied or abandoned; every spawned bot gets its own randomized
loadout, vitals and patrol behaviour, so exploring the world feels like the structures were already
inhabited.

This addon does **not** modify, copy, bundle or mixin into PvP BOT. It talks to it through a single
adapter class, using reflection and public commands only, so a future PvP BOT release can drop in without
touching this addon (see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)).

## Requirements

| | |
|---|---|
| Minecraft | 1.21.11 |
| Loader | [Fabric Loader](https://fabricmc.net/) ≥ 0.16.0 |
| Java | 21 |
| Required dependency | [Fabric API](https://modrinth.com/mod/fabric-api) |
| Required dependency | [PvP BOT](https://modrinth.com/mod/pvp-bot-fabric) `pvp_bot` (tested against 0.0.15) |
| Required dependency (of PvP BOT) | [HeroBot](https://modrinth.com/mod/herobot) `herobot` |

## Installation

1. Install Fabric Loader for 1.21.11.
2. Put `fabric-api`, `herobot`, `pvp-bot-fabric` and this addon's jar in your `mods/` folder (server or
   singleplayer — this addon has no client-side component).
3. Start the server once to generate `config/pvpbot_inhabitants.json`, then edit it to taste (see below).
4. Explore. The first time a structure's chunk loads, it is rolled.

> **Privacy note.** PvP BOT sends anonymous usage statistics (the names of bots it creates, an active-bot
> count, and damage totals) to `pvpbotstatsapi.survivalworld.win` over plain HTTP by default, and this addon
> creates many bots. To disable it, set `"send_anonymous_statistics": false` in
> `config/pvpbot/stats_config.json` and restart. This addon itself sends no telemetry anywhere.

## Configuration

`config/pvpbot_inhabitants.json`, created with defaults on first run. Every value can be left out — a
partial file inherits the rest. A broken file is **never overwritten**: the addon logs the parse error and
runs on defaults until you fix it.

```jsonc
{
  "enabled": true,
  "default": { "occupiedChance": 0.65, "minBots": 1, "maxBots": 4, "piecesPerBot": 0.5 },

  "structures": {
    "minecraft:pillager_outpost": { "occupiedChance": 0.85, "minBots": 2, "maxBots": 6 },
    "somemod:*": { "occupiedChance": 0.5 }
  },
  "tags": {
    "#minecraft:village": { "occupiedChance": 0.7, "minBots": 1, "maxBots": 5 }
  },
  "include": ["*"],
  "exclude": ["minecraft:buried_treasure"],

  "dimensions": { "include": ["*"], "exclude": [] },

  "profiles": {
    "randomize": true,
    "coverageBuckets": 8,
    "attributeVariation": true,
    "scaleVariation": false,
    "behaviorVariation": true,
    "allowExplosiveKits": false,
    "allowElytra": false,
    "disabledEnchantments": ["minecraft:piercing"]
  },

  "deterministic": { "enabled": false, "salt": "" },

  "processing": {
    "onlyNewlyGenerated": false,
    "maxLiveBots": 256,
    "maxStructuresPerTick": 4,
    "maxBotsPerTick": 1
  },

  "spawning": {
    "backend": "AUTO",
    "namePrefix": "",
    "allowSubmerged": false
  },

  // PvP BOT settings the addon holds at these values (see "Managed PvP BOT settings" below); null/absent key = leave alone
  "pvpbotSettings": { "maxTargetDistance": 10.0, "rangedMinRange": 6.0, "rangedOptimalRange": 8.0,
                      "rangedMaxRange": 10.0, "autoEquipWeapon": false },
  // fires a loaded crossbow and spaces shots (see "Crossbow trigger and shot pacing" below)
  "rangedPacing": { "enabled": true, "aimSettleTicks": 4, "crossbowMinShotIntervalTicks": 26 },

  "debugCommands": true,
  "commandPermissionLevel": 2
}
```

Structure identifiers accept:

| Form | Meaning |
|---|---|
| `minecraft:pillager_outpost` | exact registry id |
| `village_plains` | same as `minecraft:village_plains` |
| `somemod:*` | every structure of one mod |
| `#minecraft:village` | a structure tag |
| `*` | everything |

`structures` beats `tags` beats a namespace wildcard beats `default`, field by field — an override can set
only `occupiedChance` and still inherit `minBots`/`maxBots`/`piecesPerBot`. `exclude` always wins over `include`.

Full field reference: every key above, its valid range, and what it does is in
[`InhabitantsConfig`](src/main/java/dev/spawnbotswrapper/inhabitants/config/InhabitantsConfig.java).

### Bot count scales with structure size

`minBots`/`maxBots` set the range a structure can roll from, but a one-piece well and a fifty-house village
sharing the same tag shouldn't roll from the same range. `piecesPerBot` pulls the ceiling *down* for a
structure smaller than that range assumes: the effective maximum is
`min(maxBots, ceil(structurePieceCount / piecesPerBot))`, where a "piece" is one of the structure's
individual buildings/rooms (a structure with no piece data counts as one piece). It never raises the
ceiling above `maxBots`, and `minBots` is clamped down to match if the size cap falls below it. The default
(`0.5`, i.e. roughly two bots per piece) means a handful of pieces already reaches a typical `maxBots`, so
only a genuinely small structure draws from a visibly narrower range.

### Placement spreads across the structure

When placing an occupied structure's bots, positions are drawn from the structure's individual pieces
(buildings/rooms) rather than treated as one bounding box: every piece gets a bot before any piece gets a
second one, so a village's population lands in different houses instead of piling into whichever one was
sampled first. A structure with only one piece (or one that fits in a single box) instead spreads its bots
out within that box — the minimum separation between bots adapts to the box's footprint and the bot count,
never shrinking below whatever `spawning.minBotSeparation` already requires.

### Deterministic mode

`deterministic.enabled: true` derives every roll and profile from `world seed + salt + dimension +
structure id + start position`, so a fresh copy of the same world (and the same salt) reproduces the same
inhabitants from scratch. Once a structure has actually been processed, the saved result is authoritative —
deterministic mode does not retroactively re-roll anything.

### One spawn per structure, ever

Every structure is rolled and populated **exactly once**, permanently: `PopulationDriver` never re-rolls,
renames a live bot, or requests one twice, and once a structure settles into `POPULATED` or `GAVE_UP` no
code path ever revisits it. If a structure's inhabitants are later killed — by the player, by hostile mobs
already living there, by anything — that structure stays settled; it is **not** repopulated. This mirrors
the addon's own restart guarantee (a bot that goes permanently offline is never respawned either, see
`processing.goneConfirmTicks`): a POI's population is a one-time event, not something that regenerates.

### Names and presence

Inhabitants are named `<Word><Word>[<digits>]` (e.g. `DuskRaven`, `IronFang7`, `BriarWarden42`) — two
distinct words from the addon's own list run together, the way a person actually names an account, with an
occasional short digit tail for extra variety and to resolve the rare collision. `spawning.namePrefix` is
empty by default for exactly this reason: a fixed prefix in front of every single inhabitant (the addon's
own former default, `"Inh"`) is the opposite of realistic, since a real gamertag essentially never looks
like `Inh_Fang8rt2`. Set a prefix only if you need it for online-mode Mojang-account collision safety (see
the field's own doc comment in
[`InhabitantsConfig`](src/main/java/dev/spawnbotswrapper/inhabitants/config/InhabitantsConfig.java)) — every
inhabitant will then visibly share it, trading realism for that guarantee.

Two more things keep inhabitants from announcing themselves as an addon's bots rather than part of the
world:

- **No join/leave/advancement chat spam.** Vanilla's "X joined the game" / "X left the game" / advancement
  lines are suppressed for every name this addon is currently tracking as an inhabitant, on every server —
  a structure spawning several bots does not flood chat the moment its chunk loads. Real players, and any
  other mod's bots, are completely unaffected.
- **No nametag wallhack.** Vanilla player nametags render through terrain up to a distance — this is
  vanilla's own long-standing behaviour, not something this addon or PvP BOT introduces, but it reads as an
  X-ray for a structure meant to feel inhabited rather than radar-tagged. There is no vanilla per-entity,
  line-of-sight-gated nametag to opt into, so inhabitants are placed on a dedicated scoreboard team with
  `nametagVisibility` set to `never` instead: their nametags never render for anyone, full stop. This is
  entirely server-side (a vanilla scoreboard team), so it needs no client-side component and cannot conflict
  with another mod's rendering.

## What gets randomized, and what does not

PvP BOT keeps every combat setting in **one process-wide singleton**; this addon changes only the few settings
listed under `pvpbotSettings` (see "Managed PvP BOT settings" below), server-wide and never per bot, so it never
silently changes ONE existing bot's behaviour. Per-bot variety therefore comes only from
what a bot **carries** (PvP BOT chooses its weapon mode, shield/totem/potion/food/mending behaviour from
its inventory), from the few vanilla **attributes** PvP BOT actually reads, and from PvP BOT's own **patrol
path** system (stance and walk type; every path has its attack flag on, see "Every inhabitant fights" below).

The full table — every one of PvP BOT's 68 settings, whether it can vary per bot, and exactly how — is in
[docs/SETTINGS.md](docs/SETTINGS.md). The architecture and the reasoning behind it are in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Every inhabitant fights

PvP BOT only runs its combat AI for a path-following bot when the path's `attack` flag is on. Earlier
versions rolled that flag from a balanced 50/50 "combatant" deck, so roughly half of all guards and
patrols were **pacifists**: they never targeted, attacked or retaliated. That half of the deck is retired:

* the generator always rolls a fighter (the archetype label "Pacifist" no longer exists);
* every path is built with `attack=true`, whatever an old persisted profile says — the legacy
  `behavior.combatant` flag is kept in the file only for shape and is ignored;
* **migration**: `populations.json` is now `dataVersion` 3. On load, every profile of an older file that was a
  pacifist is rewritten in place to a fighter (loadout, vitals, stance and waypoints untouched; archetype
  "Pacifist" becomes "Fighter"), one summary line is logged (`data migration to version 3: N inhabitant
  profile(s) ...`) and the file is rewritten at the next save. A v3 file is refused by older builds;
* stale upstream paths (PvP BOT stores paths in its own file, with `attack=false` for old pacifists) are
  replaced: restoring a bot deletes and rebuilds its `inh_` path with `attack=true` before it starts following.

## No ender pearls: the cobweb escape loop

PvP BOT's cobweb escape (a bot standing inside a cobweb) uses a water bucket if it has one (a bounded ten-tick
routine), else an ender pearl: it selects the pearl slot, throws horizontally and clears its in-web flag, and repeats
on every tick it is still webbed. In cramped places (mineshafts, tunnels, next to walls) the pearl lands back in or
next to the web and the pearl cooldown turns most retries into no-ops, but every retry still switches the selected
hotbar slot, which cancels a crossbow charge, a bow draw and every attack. The visible symptom is a hostile bot right
next to you that "reloads its crossbow half-way, stops, reloads again" and never fires. Pearls are used by nothing
else in PvP BOT 0.0.15, so:

* new loadouts **never contain ender pearls**, and every combat-capable inhabitant gets **one water bucket** instead
  (PvP BOT's bucket branch is a bounded ten-tick routine: water at the bot's feet, which removes the web, picked up
  again on tick 5, the bot walking back for it if that failed; PvP BOT never refills, so nothing tops the bucket up
  later; before this the bucket was a coin flip and webbed bots without one printed
  `[COBWEB] No water bucket or ender pearl found!` to the console on every tick). Only newly rolled profiles get it;
  a stored profile keeps what it has. Cobwebs are still stocked as before;
* every inhabitant the addon manages is **swept for ender pearls**: right after it is restored and then about every
  5 seconds while it is online (so a picked-up pearl goes too). Only inhabitants are touched, never real players or
  other bots; nothing else in the inventory changes. The first removal per bot is logged once at INFO
  (`Removed N ender pearl(s) from inhabitant ...`), later ones only in debug mode. Profiles stored before this change
  keep their pearls in `populations.json`, but a re-dressing (`profiles.reapplyOnRestore`) drops them too.

## Disabled enchantments: no Piercing crossbows

In vanilla Java a **Piercing** bolt ignores a raised shield (the game skips shield blocking for arrows with a pierce
level above zero). A hostile inhabitant with a piercing crossbow therefore could not be blocked at all, which is too
strong for a structure guardian, so Piercing is **off by default**. The list is `profiles.disabledEnchantments`
(default `["minecraft:piercing"]`):

* ids are accepted with or without the `minecraft:` namespace and in any letter case; an entry that is not a valid id
  or names a `minecraft:` enchantment that does not exist gets one WARN at load and is otherwise ignored; `[]` turns
  the feature off (any enchantment can be listed, not just Piercing);
* new loadouts **never contain a disabled enchantment**. The roller still consumes the same random draws, so a seeded
  loadout differs from the old one only by the missing enchantment; nothing is substituted (no Multishot);
* every inhabitant is **sanitized**: when it is dressed (spawn or `profiles.reapplyOnRestore`), right after a restore and
  then about every 5 seconds while it is online, the disabled enchantments are removed from every stack it carries or
  wears (hotbar, main inventory, armor, offhand, stored enchantments of books). The item, its count and its other
  enchantments stay; real players and other bots are never touched. The first removal per bot is logged once at INFO
  (`Removed disabled enchantment(s) from inhabitant ...`), later ones only in debug mode;
* stored profiles (`populations.json`) are **not migrated**: the file keeps what was rolled and old saves load unchanged,
  but the application filters the loadout, so a re-dressing can never put a disabled enchantment back. Removing an
  id from the list makes stored loadouts whole again on their next re-dressing.

## Managed PvP BOT settings (`pvpbotSettings`)

PvP BOT keeps its combat settings per world (`config/pvpbot/worlds/<world>/settings.json`), and its defaults suit
a duel arena, not a structure inhabitant: it looks for targets up to **64 blocks** away, archers park 20 blocks from
their target, and a housekeeping routine (`autoEquipWeapon`) re-selects the best **melee** weapon every
`checkInterval` ticks. For a bot that carries a sword AND a bow or crossbow that last one ends every draw before it
completes (the selected slot leaves the ranged weapon inside the tick), so such a bot never shoots. The addon
therefore holds these settings at chosen values:

```jsonc
"pvpbotSettings": {
  "maxTargetDistance": 10.0,     // blocks, 4..64 (PvP BOT's own default: 64)
  "rangedMinRange": 6.0,         // archers back off below this (PvP BOT: 20)
  "rangedOptimalRange": 8.0,     // PvP BOT: 40
  "rangedMaxRange": 10.0,        // archers walk toward a target beyond this (PvP BOT: 60)
  "autoEquipWeapon": false       // PvP BOT: true
}
```

* A `null` or absent key inside the block leaves PvP BOT's own value alone; a config file without the block at all
  gets the values above. `"pvpbotSettings": null` manages nothing.
* Validation: `maxTargetDistance` is clamped to 4..64; the three ranges must satisfy
  `rangedMinRange < rangedOptimalRange <= rangedMaxRange <= maxTargetDistance` (judged with PvP BOT's own value for
  any you leave out), otherwise **all three ranged keys are skipped** with a warning and the other keys still apply.
* When it is applied: at the first tick (before the PvP BOT probe report, so the report shows the real values) and
  again **every time PvP BOT loads its per-world settings** (`/pvpbot reload`), which the addon notices because PvP
  BOT then replaces its settings object. Nothing is written when the values already match. The values are written
  into PvP BOT's settings object directly, not through its setters (they clamp the optimal range to at least 10 and
  the maximum to at least 15), and then PvP BOT's own save routine writes the settings file once.
* One INFO line names what changed: `PvP BOT settings: maxTargetDistance 64 -> 10, autoEquipWeapon true -> false`.
  A missing PvP BOT field is one warning and never an exception. `/inhabitants adapter` (and the probe report) warn
  when the effective `rangedMinRange` is above `maxTargetDistance`: ranged bots would back away from every target
  inside their own targeting radius.
* The other settings stay PvP BOT's: `autoTargetEnabled` in particular is NOT managed (with it off, inhabitants
  fight only what attacked them and never open fire on sight; turn it on with `pvpbot settings auto-target true` or
  in `settings.json`). Whoever hits an inhabitant from beyond `maxTargetDistance` (an arrow from far away) is NOT
  pursued: PvP BOT's revenge logic remembers the last attacker for 30 seconds but only targets one that is within
  `maxTargetDistance`, so the bot stays put until you come inside that radius (within those 30 seconds it then
  attacks you); melee hits are always inside it.

## Crossbow trigger and shot pacing (`rangedPacing`)

On this Minecraft version PvP BOT can never fire a **loaded** crossbow: its routine ends every cycle by releasing an
item that is not being used, which does nothing, so a crossbow inhabitant charged its weapon and then held it loaded
for ever, until a player hit it (a held shield "use" action, which right-clicks the crossbow every tick, then fired
it, about twice a second). The addon closes this in two vanilla-API pieces (no mixin, no PvP BOT internals beyond the
read-only target/mode getters):

* **Trigger.** Once per server tick, after PvP BOT's own tick, every inhabitant holding a **loaded crossbow in its
  main hand** is fired through the vanilla right-click path (`ServerPlayerGameMode.useItem`, exactly what a player's
  click does; no projectile is created here, damage and accuracy are vanilla) when ALL hold: the bot is not using an
  item; PvP BOT's current target is alive, in the same dimension, within PvP BOT's targeting radius
  (`maxTargetDistance`) and in line of sight; PvP BOT is in ranged mode (when its mode can be read); the crossbow has
  been loaded for `aimSettleTicks`; and `crossbowMinShotIntervalTicks` have passed since the bot's last shot. Bows need
  no trigger (`BowItem` fires on release once `autoEquipWeapon` no longer cancels the draw).
* **Pacing.** After ANY crossbow projectile an inhabitant launches (ours, or one from a held "use" action) the addon
  puts the crossbow on the **vanilla item cooldown** for the interval. Vanilla refuses to use an item on cooldown, so
  the interval binds every shooter: shots are never closer together than `crossbowMinShotIntervalTicks`.

```jsonc
"rangedPacing": {
  "enabled": true,                       // false: leave crossbows entirely to PvP BOT
  "aimSettleTicks": 4,                   // 0..40: how long a crossbow must have been loaded before it is fired
  "crossbowMinShotIntervalTicks": 26     // 1..200: PvP BOT's own cycle is a 25-tick draw plus one
}
```

Fail-soft: a missing PvP BOT name is one warning and the trigger then never fires (the pacing keeps working); a bug in
the tick switches the trigger off with one warning. Only inhabitants (the addon's own roster) are touched, never real
players or other bots.

## Managed PvP BOT setting: critical-hit fall phase

PvP BOT's melee routine only swings after a jump-crit with `crit-fall-ticks` ticks of descent (its default is
6), so bots looked passive at close range. The addon manages this one setting itself: at every server start it
runs PvP BOT's own command `pvpbot settings crit-fall-ticks <criticalFallTicks>` (config key
`criticalFallTicks`, default **3**, `0` = leave PvP BOT alone), through the same console mechanism as
`startupCommands`. Criticals stay enabled. An explicit
`pvpbot settings crit-fall-ticks N` in your own `startupCommands` wins over the managed value.

## Lag governor (`tpsThrottle`)

The governor sheds inhabitants (farthest from the nearest real player first, permanently, like a death) and
blocks new spawns only while the server is **genuinely** degraded. The measured metric is the rolling average
wall-clock ms between ticks, which never reads below ~50 and sits at 53-59 ms on a busy modded server; the old
fixed 55.6 ms threshold therefore fired during ordinary load. The trigger is now a small state machine
(`engine.TickHealth`):

* **enter level** = `max(degradedFloorMillis, baseline x degradedFactor)` (defaults 65 ms, x1.25). The
  baseline is the server's own typical tick time: a slow moving average (`baselineWindowTicks`, default 6000)
  updated only while not degraded and only from readings below the enter level, capped at `baselineMaxMillis`
  (60), so the enter level lies between 65 and 75 ms;
* degraded only after the reading stays above the enter level, uninterrupted, for `sustainTicks` (600);
* **exit level** = `max(recoveredFloorMillis, baseline x recoveredFactor)` (58 ms, x1.10), always at least 2 ms
  below the enter level and above the 50 ms floor, so recovery is reachable; it must hold for `sustainTicks`
  and at least `minDwellTicks` (1200) must have been spent degraded;
* while degraded, each check above the enter level sheds `despawnBatchSize` x (consecutive rounds); between the
  exit and enter levels nothing more is shed but spawns stay blocked;
* every transition and every shed round is logged at INFO with the numbers (`TPS governor: server DEGRADED ...`,
  `... RECOVERED ...`, `... shed N of M inhabitant(s) [names] ...`).

Config keys (all under `tpsThrottle`): new `degradedFloorMillis`, `degradedFactor`, `recoveredFloorMillis`,
`recoveredFactor`, `baselineMaxMillis`, `baselineWindowTicks`, `sustainTicks`, `minDwellTicks`; unchanged
`enabled`, `checkIntervalTicks`, `despawnBatchSize`; **removed** `healthyMillis` / `degradedMillis` (still
accepted in a file, ignored, with a config warning at startup — delete them).

## Combat log

Nothing used to log what bots did in a fight. The addon now writes structured, rate-limited lines for every
fight that involves an inhabitant (recognised by the addon's own roster, never by PvP BOT classes), through
Fabric's `AFTER_DAMAGE` / `AFTER_DEATH` events:

```
Combat: DuskRaven (inhabitant) hit Steve (player) 7 times for 31.5 damage (largest 6.0) with iron_sword [player], last at 2.1 blocks; Steve health now 6.0 (over 3.4 s)
Combat: zombie (mob) hit DuskRaven (inhabitant) 2 times for 6.0 damage with none [mob], last at 1.3 blocks; DuskRaven health now 14.0
Combat: DuskRaven (inhabitant) was killed by Steve (player) (player, diamond_sword, 1.8 blocks away)
Combat: DuskRaven (inhabitant) killed zombie (mob) (player, iron_sword, 1.2 blocks away)
Combat: DuskRaven (inhabitant) died (fall)
```

* hits between the same attacker and victim are **coalesced** into one INFO summary line per `combatLog.coalesceTicks`
  (default 100 = 5 s): count, total and largest damage, damage source type, weapon, last distance, health after;
* at most `combatLog.maxLinesPerMinute` (30) summary lines per minute; the excess is counted and reported once
  (`N more combat line(s) were suppressed ...`);
* kills and deaths (with the killer) are always logged;
* **`debug: true`** additionally writes every single hit immediately with full detail
  (`Combat hit: A (kind) -> B (kind): dmg (base before armor) [blocked] via <source> with <weapon> at <dist>; health now h`),
  including damage with no attacker (fall, fire, ...);
* `combatLog.enabled: false` switches it off. Target acquisition ("bot X targets Y") is deliberately not logged:
  PvP BOT keeps its current target in internal state the adapter does not read, so the first hit line of a fight
  is the observable record of who engaged whom.

### Aggro range, leash and walk back

PvP BOT's own auto-target notices any player within 64 blocks, through walls. This addon replaces it (the settings
policy turns PvP BOT's `autoTarget` off) with the rules below, applied to every inhabitant (`aggro` block in
`pvpbot_inhabitants.json`):

* **Noticing.** An idle inhabitant notices the nearest valid player within `acquireRange` (10) blocks **and** in line
  of sight (`requireLineOfSight`), checked every `scanIntervalTicks` (5). "Valid" follows PvP BOT's own rules (not
  creative/spectator unless `attackInvincible`, not a faction ally, other bots only with `targetOtherBots`). The
  player is handed to PvP BOT as a forced target.
* **Being hit.** Anyone who hits an inhabitant from any distance (up to PvP BOT's `maxTargetDistance`) is chased too
  (PvP BOT's revenge memory), and so is a mob it decides to fight. This includes a wind-burst / elytra flow in which
  PvP BOT writes the forced target itself: that chase is leashed like any other. **Limit:** PvP BOT itself never keeps
  a target farther than its `maxTargetDistance` ceiling (64), so a hit from beyond it is not chased. That is a PvP BOT
  limitation and is left as is.
* **Home and origins.** The first engagement of a bot that has no home records the bot's position and level as its
  **home anchor**, and that position is the origin its leash and sight rule are measured from. Home is kept until the
  bot is back within `returnArriveDistance` of it, the walk back is abandoned, or the bot dies or leaves, and only
  then cleared. An engagement that starts while the bot is walking back home (a hit, or a regular noticing) gets a
  **temporary origin**: the bot's position at that moment, from which its 32-block leash and 10 s sight rule are
  measured. A hit while a chase is already running changes nothing.
* **Giving up.** Every chase ends when the inhabitant is more than `leashRange` (32) blocks (horizontally) from
  its origin, or has not seen its target for `loseSightTicks` (200 = 10 s) in a row, or the
  target is gone (dead, logged out, another dimension, creative/spectator), or the bot changed dimension. PvP BOT's
  target state is then cleared (`BotCombat.clearTarget`, which also wipes its revenge memory).
* **Walking home.** With `returnToOrigin` the inhabitant then walks back to its HOME anchor (never to a temporary
  origin), using PvP BOT's own look and move-toward calls every tick (never a teleport), and counts as arrived within
  `returnArriveDistance` (1.5) blocks; the home anchor is then cleared. The walk is abandoned (one debug line, the bot
  stays put, home cleared) when the distance shrinks by less than a block over `returnStuckTicks` (200) or it lasts
  `returnMaxTicks` (1200). Both guards count per walking leg: after a temporary chase gives up, the new leg starts them
  afresh. While walking home the bot still notices players by the regular rules (within `acquireRange`, in line of
  sight), which starts a chase with a temporary origin; a bot is never noticed-and-chased on the very tick a chase was
  given up, so the walk always gets its steering tick.
* **Somebody else's forced target** (a `/pvpbot` command) is only tracked: never leashed, cleared or walked back from.
* **Inert mode.** While PvP BOT's `autoTarget` is on, PvP BOT notices by itself: nothing is noticed here (logged once
  at INFO), but the leash and the walk back still apply. A `WARN` is logged when `leashRange + acquireRange` exceeds
  PvP BOT's `maxTargetDistance`, since PvP BOT may then drop a target before the leash decides.
* The walk back runs in a Fabric tick phase ordered after the default phase, so it is the last input written each tick,
  after PvP BOT's own bot tick (idle wander, patrol movement). The state of a bot (`aggro[engaged Steve (acquired)
  12.3 from origin, unseen 40t]`, `aggro[engaged Steve (hit) 5.2 from temp origin, home 20.1 away, unseen 0t]`, `aggro[returning home, 18.0 to go]`) is appended to the "Combat taken" line; starts,
  give-ups and returns are logged at debug (one INFO per bot per 10 s at most).

### Diagnostic lines: hits an inhabitant TAKES, and bow/crossbow loops

Two further diagnostics (same `combatLog.enabled` switch, normal `latest.log`, no behaviour change of any bot)
exist to find a bot that misbehaves in a fight, for example one that "starts to reload its crossbow, stops
half-way, starts again and never attacks":

```
Combat taken: DuskRaven (inhabitant of minecraft:overworld|shipwreck|4,-7) took 4.0 damage from Steve (player) via player with iron_sword; DuskRaven health now 16.0 at 10.5 64.0 -3.3 in minecraft:overworld | state: slot=2 main=crossbow(unloaded) off=shield using=crossbow(7/25t) ammo=arrows:12,rockets:0 carries=bow,crossbow,melee nearest=Steve@2.3 los=yes ground=1 water=0 web=0 pvpbot=global(combat=1,autoTarget=1,ranged=1) target=Steve mode=RANGED draw=1/7
ranged loop: DuskRaven aborted 3 bow/crossbow draws in 10 s | draws: crossbow started t1200 lasted 12 ticks, ended: selected slot left the ranged weapon inside the tick (slot 2 at the draw's start, 0 at the start of this tick, 2 at its end; likely PvP BOT's weapon auto-equip); ... | ticks between a stop and the next start: 3,4 | state: ...
```

* **`Combat taken:`** (INFO) one line per hit an inhabitant takes: who hit it (player name, mob type, or another
  inhabitant; a projectile names its SHOOTER, the projectile type in `[...]`), the damage type and weapon, damage and
  health after, the inhabitant's position and dimension, and the structure it belongs to. The FIRST hit of an
  attacker/victim pair is always written immediately; repeats closer than half a second are folded into the next
  line (`+N hits since last line`). Unlike the coalesced `Combat:` summaries, a player hitting a bot shows at once.
* **`state:`** the compact snapshot on both lines: hotbar slot, main hand (a crossbow is marked
  `charged`/`unloaded`), off hand, the item in use with use ticks so far/total, arrows and firework rockets carried,
  which of bow/crossbow/melee weapon it carries, the attacker (else the nearest real player) with distance and an
  eye-to-eye line of sight, on ground / in water / in a cobweb, and PvP BOT's GLOBAL switches (combat, auto-target,
  ranged), followed by what PvP BOT intends for THIS bot, read through the adapter: `target=Steve` (its current
  target, `none`, or `unreadable` when PvP BOT does not list the bot or a name is missing upstream), `mode=RANGED`
  (its weapon mode) and `draw=1/7` (it believes it is drawing, for 7 ticks; `draw=0` when not).
* **`ranged loop:`** (WARN) for inhabitants within 32 blocks of a real player, every bow or crossbow draw is tracked;
  a draw is aborted when it ends with no projectile shot (an arrow owned by the bot spawning counts as a shot; a
  crossbow ending loaded counts as completed). Three or more aborted draws inside 10 s produce one line with each
  draw's length, what changed when it stopped (slot switch, main-hand item, line of sight lost, target moved or
  vanished, use restarted, and "selected slot left the ranged weapon inside the tick" when the hotbar selection, sampled
  at the START and at the END of the server tick, was moved off the bow or crossbow and back within one tick, which is
  the signature of PvP BOT's weapon auto-equip, see `pvpbotSettings`) and the ticks between a stop and the next
  start; at most one such line per bot per 30 s.
* **Why hits on inhabitants were never logged before:** the bot mod's fake player class re-implements the whole vanilla
  hurt routine, so the Fabric damage event (which the combat log listens to) never fires for an inhabitant; only
  deaths and hits on OTHER players were seen. Inhabitants are now also polled once per tick (recorded last damage
  source plus health), which yields the same `Combat taken:` line and ledger entry; the damage of a hit noticed this
  way is the health it cost (so 0.0 for a hit that was blocked or fully absorbed), and two hits within one tick are one
  line with the summed damage.

## Restart: restored bots patrol and fight at once

After a restart PvP BOT brings its bots back one by one with no completion signal, so the addon waits
`processing.restoreSettleTicks` (default 1200) before concluding anything (starting "gone" clocks, releasing leftovers,
spawning, dormancy, the lag governor). That wait used to also delay re-attaching each bot's path and follower —
PvP BOT persists paths but not who follows them — leaving restored inhabitants idle for over a minute. Now, during
the settle window, every bot that is already online *and* listed by PvP BOT gets its path rebuilt and its follower
re-attached (and, if `profiles.reapplyOnRestore`, its profile re-applied) within one roster pass (~1 s); a bot
PvP BOT has not restored yet is simply retried, never treated as gone, so the reconcile/orphan logic is unchanged.

## Admin / testing commands

Enabled by `debugCommands` (default `true`), gated by `commandPermissionLevel` (default `2`, operator):

| Command | Purpose |
|---|---|
| `/inhabitants info` | addon + adapter status, config summary, engine and population counts |
| `/inhabitants adapter` | full PvP BOT/HeroBot compatibility report |
| `/inhabitants structure here` | structures at your position, with tags, bounds and processed status |
| `/inhabitants nearby [radius]` | processed structures near you |
| `/inhabitants process nearest [roll\|occupied\|abandoned]` | force-process the nearest unprocessed structure |
| `/inhabitants reset here\|nearest\|structure <id> <x> <z> [removeBots]` | reset one structure's population state |
| `/inhabitants profile <bot>` | print a bot's generated profile |
| `/inhabitants catalog [category]` | PvP BOT setting classification and live audit |
| `/inhabitants reload` | reload the config file |

`reset`/`process` are testing tools, not gameplay: resetting a structure lets it be rolled again (with the
same result in deterministic mode); it is never done automatically.

## Building

```bash
./gradlew build
```

Produces `build/libs/pvpbot-inhabitants-<version>.jar`. Requires a JDK 21 (a Gradle toolchain download or
your own JDK 21 both work) and internet access on first run (Minecraft, the official Mojang mappings, Fabric API, Gradle
itself).

```bash
./gradlew test
```

Runs the full unit test suite (pure logic and Minecraft-registry-backed tests; no server required).

### Real-server GameTests (PvP BOT and HeroBot loaded)

`src/gametest` holds Fabric GameTests that run a real dedicated GameTest server with the PvP BOT and HeroBot release
jars as runtime mods (copied into the run directory like a profile, never compile-time dependencies): a real
inhabitant is requested from the population engine, dressed, and fights a survival mock player. They cover the
crossbow trigger and pacing, bows, the managed PvP BOT settings (including their re-application after PvP BOT reloads
its settings), the combat log lines, the targeting radius, and the ranged-loop cause text.

```bash
./gradlew runGameTest -PupstreamModsDir=<dir with PVP_bot-*.jar and herobot-*.jar>   # default C:\mcw\_tools\deploycheck\mods
```

When the directory or either jar is missing, `runGameTest` is skipped with a message and everything else (build, unit
tests) is unaffected. `-PharnessFixesOff=true` switches the addon's two ranged-combat fixes off in the run, which
reproduces the failures they fix. Two workarounds live in the test mod only: a mixin that maps PvP BOT's reflective
hotbar-index lookup to the runtime field name (the dev runtime uses Mojang names, PvP BOT looks up Yarn and
intermediary ones), and `attackInvincible` in the run's PvP BOT settings because GameTest mock players report
`isCreative()`.

## Test procedure

Automated tests cover every pure-logic path (rolling exactly once, coverage-sampled randomization, crash
and restart safety, spawn-position geometry, patrol planning, the PvP BOT adapter's reflection contract).
What they cannot cover is PvP BOT's actual runtime behaviour, so verify these by hand on a real server with
PvP BOT and HeroBot installed:

1. **Enter unexplored terrain** containing a vanilla structure (a village or pillager outpost is fastest).
   Confirm with `/inhabitants structure here` that it was detected, and that it now has a record (`/inhabitants nearby`).
2. **Repeat step 1** for several structures. Confirm some are abandoned and some are occupied
   (`/inhabitants nearby` shows the roll).
3. **Approach an occupied structure** and confirm bots appear at sensible positions inside it, at valid
   floor level, not stacked on top of each other.
4. **Compare several bots' profiles** with `/inhabitants profile <name>`: confirm visibly different
   loadouts, vitals and behaviour, and that numeric values are NOT all clustered near the middle of their
   range across many bots.
5. **Unload and reload the chunks** of a populated structure (fly away and back, or restart the client).
   Confirm no additional bots were spawned.
6. **Restart the server.** Confirm the same bots come back (or are correctly left dead if killed — see
   next step) and no duplicates appear.
7. **Kill an inhabitant.** Confirm its structure is never repopulated, even after further restarts or
   chunk reloads.
8. **Install a datapack or mod that registers its own structure** (or reuse `/inhabitants structure here`
   on any non-vanilla structure available to you) and confirm it is detected and populated with no
   addon changes.
9. **Replace the PvP BOT jar** with a newer compatible release and confirm `/inhabitants adapter` reports
   compatibility and spawning still works, with no changes to this addon's code.

## Compatibility notes

* Tested against PvP BOT `0.0.15` and HeroBot `1.21.11-1.4.3`. The adapter probes PvP BOT's public surface
  at startup rather than pinning an exact version; `/inhabitants adapter` reports `AVAILABLE`, `DEGRADED`
  (a fallback spawn path is in use) or `UNAVAILABLE` (with the exact reason) for whatever version is
  installed.
* PvP BOT has no compile-time dependency in this project and is never bundled; you must install it
  separately.
* If PvP BOT is missing or incompatible, this addon does nothing — it never marks a structure processed
  while it cannot populate it.

## License

MIT — see [LICENSE](LICENSE). Independent of, and not affiliated with, PvP BOT or HeroBot.
