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

PvP BOT keeps every combat setting in **one process-wide singleton**; this addon never touches it, so it
never silently changes the behaviour of bots that already exist. Per-bot variety therefore comes only from
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

* new loadouts **never contain ender pearls** (water buckets and cobwebs are still stocked as before);
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

## Managed PvP BOT setting: critical-hit fall phase

PvP BOT's melee routine only swings after a jump-crit with `crit-fall-ticks` ticks of descent (its default is
6), so bots looked passive at close range. The addon manages this one setting itself: at every server start it
runs PvP BOT's own command `pvpbot settings crit-fall-ticks <criticalFallTicks>` (config key
`criticalFallTicks`, default **3**, `0` = leave PvP BOT alone), through the same console mechanism as
`startupCommands`. Criticals stay enabled. The addon never edits PvP BOT's settings files. An explicit
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

### Diagnostic lines: hits an inhabitant TAKES, and bow/crossbow loops

Two further diagnostics (same `combatLog.enabled` switch, normal `latest.log`, no behaviour change of any bot)
exist to find a bot that misbehaves in a fight, for example one that "starts to reload its crossbow, stops
half-way, starts again and never attacks":

```
Combat taken: DuskRaven (inhabitant of minecraft:overworld|shipwreck|4,-7) took 4.0 damage from Steve (player) via player_attack with iron_sword; DuskRaven health now 16.0 at 10.5 64.0 -3.3 in minecraft:overworld | state: slot=2 main=crossbow(unloaded) off=shield using=crossbow(7/25t) ammo=arrows:12,rockets:0 carries=bow,crossbow,melee nearest=Steve@2.3 los=yes ground=1 water=0 web=0 pvpbot=global(combat=1,autoTarget=1,ranged=1;target=not-readable)
ranged loop: DuskRaven aborted 3 bow/crossbow draws in 10 s | draws: crossbow started t1200 lasted 12 ticks, ended: slot switch 2->0, main hand crossbow->iron_sword; ... | ticks between a stop and the next start: 3,4 | state: ...
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
  ranged). PvP BOT's per-bot current target is internal state the adapter does not read, so it is reported as
  `target=not-readable` rather than guessed.
* **`ranged loop:`** (WARN) for inhabitants within 32 blocks of a real player, every bow or crossbow draw is tracked;
  a draw is aborted when it ends with no projectile shot (an arrow owned by the bot spawning counts as a shot; a
  crossbow ending loaded counts as completed). Three or more aborted draws inside 10 s produce one line with each
  draw's length, what changed when it stopped (slot switch, main-hand item, line of sight lost, target moved or
  vanished, use restarted) and the ticks between a stop and the next start; at most one such line per bot per 30 s.

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
