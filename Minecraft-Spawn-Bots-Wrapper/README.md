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
    "behaviorVariation": true,
    "allowExplosiveKits": false,
    "allowElytra": false,
    "disabledEnchantments": ["minecraft:piercing", "minecraft:mending"]
  },

  "deterministic": { "enabled": false, "salt": "" },

  "processing": {
    "onlyNewlyGenerated": false,
    "maxLiveBots": 256,
    "maxStructuresPerTick": 4,
    "maxBotsPerTick": 1
  },

  // nearest-first population (see "Population by allocation" below); every value has a sane bound
  "allocation": { "enabled": true, "intervalTicks": 20, "moveThresholdBlocks": 4.0, "hysteresisBlocks": 8.0,
                  "dwellTicks": 400, "dwellOverrideBlocks": 32.0, "graceTicks": 200, "relevanceExtraChunks": 2,
                  "seenCheckTicks": 10, "seenHalfAngleDeg": 70.0 },
  "dormancy": { "enabled": true, "distanceBlocks": 160.0 },

  "spawning": {
    "backend": "AUTO",
    "namePrefix": "",
    "allowSubmerged": false
  },

  // PvP BOT settings the addon holds at these values (see "Managed PvP BOT settings" below); null/absent key = leave alone
  "pvpbotSettings": { "maxTargetDistance": 128.0, "rangedMinRange": 8.0, "rangedOptimalRange": 12.0,
                      "rangedMaxRange": 16.0, "autoEquipWeapon": false, "autoTargetEnabled": false,
                      "bowMinDrawTime": 20 },
  // how inhabitants notice, chase, search for and walk back from players: line of sight and reaction time, no block
  // distances (see "The line-of-sight hunter" below)
  "aggro": { "enabled": true, "reactionTicks": 5, "loseGraceTicks": 10, "searchTicks": 200, "returnArriveDistance": 1.5 },

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

### Population by allocation: who is near you gets the bots, and there is no loot farm

Every structure is rolled **exactly once**, permanently, and its number of bots **N** is rolled once and never
re-rolled: N is the size logic (`processing.blocksPerBot`, the structure's volume, `minBots`/`maxBots`) times the
occupied roll. Which of those bots exist *right now* depends on where the players are.

**Nearest first.** The structures within a player's relevance area (the server's simulation distance plus
`allocation.relevanceExtraChunks`, at most `dormancy.distanceBlocks`) are sorted by their **3D** distance from the
nearest real player (not PvP BOT or Minecraft-AI fake players) to the structure's bounding box: a trial chamber 90
blocks below you is farther than a village 60 blocks away on the surface. The nearest structure gets its full fill
target first, then the rest of `processing.maxLiveBots` goes to the next closest, and so on. That is why an
underground structure under your route can no longer eat the whole budget while the surface structures you ride past
get nothing. The order is recomputed when a player has moved at least `allocation.moveThresholdBlocks`, changed level,
or a structure appeared or went, at most once per `allocation.intervalTicks`. Only structures near a player are
looked at (a spatial index by 16x16-chunk cell), never the whole store; the cost measured with 500 known structures and
64 live bots is under 0.1 ms per server tick.

**Anti-churn.** A structure must be `hysteresisBlocks` (8) nearer than an allocated one to displace it; a bot is not
removed within `dwellTicks` (20 s) of coming up unless its slot is needed for a structure `dwellOverrideBlocks` (32)
nearer; a structure that drops out keeps its bots `graceTicks` (10 s) before they go. A bot that is engaged with a player
is never removed. Spawns and removals stay paced (`maxBotsPerTick`, the lag governor's batch size, no spawning while
the server is degraded).

**Seen bots persist, unseen bots are ephemeral.** A bot becomes *seen* when a real player actually sees it: its eye or
body is inside the player's view cone (`allocation.seenHalfAngleDeg`, 70 degrees each side, generous for wide FOV
settings), the same eye-to-eye and eye-to-body rays as the aggro perception are clear, and it is within vanilla's
128 block sight range; invisible bots are never seen. The flag is stored in the bot's record, survives restarts and is
never cleared while the bot lives. A seen bot is never removed for good by anything but its own death: when its
structure leaves the allocation (or the lag governor sheds it) it **sleeps** with its whole state (inventory, armor,
health, food, effects, experience) and its position, and wakes first when its structure is allocated again, at its saved
position when a bot can stand there (otherwise at a fresh spot of its structure), with the same name and identity. A
bot nobody saw is **deleted** when it has to go: no record, no snapshot, no profile is kept, and its slot is *vacant*.
When the structure is allocated again a fresh bot is rolled for each vacant slot (a different name and loadout: the
player never saw the old ones). Only seen bots are stored, which also keeps the store small.

**No loot or XP farm.** Vacant slots are `N - dead - failed - (bots that already occupy one)`; right after unseen bots
were deleted that is `N - dead - seenAlive`.

* **A structure yields at most N bots' worth of kills over the world's lifetime.** Every real death, from any cause
  (a player, a mob, a fall, the void), makes that bot's record `DEAD` for good; the slot is never refilled, not by the
  allocation and not by a return. A bot that stays offline for `processing.goneConfirmTicks` without this addon having
  removed it is counted as dead too.
* **Removal is not death.** Deleting an unseen bot, putting a seen one to sleep and the lag governor's shedding never
  drop anything and never count as a death. PvP BOT's own removal runs `clear` and then `player <name> kill`, a real
  vanilla death (its items and XP orbs drop, a death message goes out). The addon empties the bot first (inventory,
  armor, offhand, cursor, ender chest, experience), makes it leave like a player who logs out and only then lets PvP
  BOT forget it: no item entity, no XP orb, no death message, no statistic or advancement.
* **No re-roll fishing.** A fresh roll only fills a vacant slot; the player cannot learn a bot's loadout without seeing
  it, and seeing it locks it in. A seen bot is never re-rolled, and nothing is re-rolled on restart or reload.
* **No duplication.** A sleep is ordered snapshot, store, clear, remove: the record (state, position, `removing` mark)
  is written to disk *before* the bot is emptied, so a crash leaves either the unchanged live bot or a record marked
  `removing` (finished on the next start), never a live inventory together with a restorable copy of it. A wake writes
  the snapshot onto a fresh, empty fake player.

**Without the allocation** (`allocation.enabled: false`, or no real player online) population is first come, first
served under `processing.maxLiveBots`, and the old distance rule (`dormancy`: a bot beyond `distanceBlocks` for
`delayTicks` goes away through the same sleep-or-delete rule) is the fallback. With the allocation running the
relevance area replaces that rule (it is not run twice); `dormancy.enabled: false` also stops the allocation from
removing bots that left it.

**Upgrading.** Stores from before this version have their dormant, never-seen bots released on load (one INFO line:
"N unseen stored bots released"); their slots are vacant, deaths already recorded stay recorded. Bots that are live and
unseen stay until their structure leaves the allocation; a bot becomes seen the moment a player sees it after the update.

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
its inventory), from its starting health and hunger, and from PvP BOT's own **patrol
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
  `[COBWEB] No water bucket or ender pearl found!` to the console on every tick). Only newly rolled profiles get it:
  a stored profile keeps what it has. Cobwebs are still stocked as before.
  Checked against the decompiled PvP BOT (`BotUtils.handleCobwebEscape`, `handleAutoEat`, `findBestFood`): the escape
  swaps the bucket into hotbar slot 8 by exchanging the two stacks, so what was in slot 8 moves to the bucket's old
  slot and nothing is lost; `findBestFood` scans all 36 main-inventory slots and `handleAutoEat` swaps a food stack
  found at slot 9 or above into slot 8, so food outside the hotbar is still found and eaten afterwards.
* every inhabitant the addon manages is **swept for ender pearls**: right after it is restored and then about every
  5 seconds while it is online (so a picked-up pearl goes too). Only inhabitants are touched, never real players or
  other bots; nothing else in the inventory changes. The first removal per bot is logged once at INFO
  (`Removed N ender pearl(s) from inhabitant ...`), later ones only in debug mode. Profiles stored before this change
  keep their pearls in `populations.json`, but a re-dressing (`profiles.reapplyOnRestore`) drops them too.

## Disabled enchantments: no Piercing crossbows, no Mending gear

In vanilla Java a **Piercing** bolt ignores a raised shield (the game skips shield blocking for arrows with a pierce
level above zero). A hostile inhabitant with a piercing crossbow therefore could not be blocked at all, which is too
strong for a structure guardian, so Piercing is **off by default**. **Mending** is off by default too: an
inhabitant's gear is a one-time reward. A player who beats a guardian in gear well above what they can access gets to
use it, but it cannot be repaired without its base material (netherite, say) and, without Mending, not with experience
either, so the advantage is temporary. PvP BOT's auto-mend routine needs worn Mending armor, so it stays inactive; the
experience bottles inhabitants carry stay in their loadouts as loot for the player. The list is
`profiles.disabledEnchantments` (default `["minecraft:piercing", "minecraft:mending"]`). A config that lists its own
ids, for example only Piercing, replaces the default: that choice is kept and Mending is allowed again:

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

### Arrow counts

A bot that carries a bow or crossbow gets **0 to 32 arrows in total** when it is rolled (uniformly spread by the coverage
sampler; the stack of spectral or tipped arrows that about half of them carry is counted inside that total). Zero is
possible on purpose: an archer that starts out of ammunition uses its sword if it has one (see "Sword up close, and out
of arrows"). The roll consumes the same random draws as before (only the range changed, it used to be 0..256), so a
seeded loadout differs from an old one only in its arrow stacks. Stored profiles are not migrated: bots that already
carry more arrows keep them.

## Managed PvP BOT settings (`pvpbotSettings`)

PvP BOT keeps its combat settings per world (`config/pvpbot/worlds/<world>/settings.json`), and its defaults suit
a duel arena, not a structure inhabitant: it picks targets by itself (`autoTargetEnabled`), archers park 20 blocks
from their target, and a housekeeping routine (`autoEquipWeapon`) re-selects the best **melee** weapon every
`checkInterval` ticks. For a bot that carries a sword AND a bow or crossbow that last one ends every draw before it
completes (the selected slot leaves the ranged weapon inside the tick), so such a bot never shoots. The addon
therefore holds these settings at chosen values:

```jsonc
"pvpbotSettings": {
  "maxTargetDistance": 128.0,    // blocks, 4..128: the mod's own maximum (PvP BOT's catalog maximum and vanilla's line-of-sight cap)
  "autoTargetEnabled": false,    // PvP BOT: false; the wrapper's aggro controller acquires targets by line of sight
  "rangedMinRange": 8.0,         // archers back off below this (PvP BOT: 20)
  "rangedOptimalRange": 12.0,    // PvP BOT: 40
  "rangedMaxRange": 16.0,        // archers walk toward a target beyond this (PvP BOT: 60)
  "autoEquipWeapon": false,      // PvP BOT: true
  "rangedRetreatOnClose": false, // PvP BOT: true; false = a bot that carries a sword or axe switches to it up close
  "meleeRange": 2.5,             // blocks, 2..6 (PvP BOT: 3.5): the melee weapon comes out within twice this (5 blocks) and strikes within it
  "bowMinDrawTime": 20           // ticks 5..100; PvP BOT: 40 (an artificial 2 s wait); 20 = vanilla full power
}
```

Inhabitants have no block-distance restriction of their own: what they react to is decided by line of sight, up to
the mod's maximum, so `maxTargetDistance` is a ceiling only and PvP BOT must neither acquire targets itself
(`autoTargetEnabled` false) nor drop a chased target early.

* A `null` or absent key inside the block leaves PvP BOT's own value alone; a config file without the block at all
  gets the values above. `"pvpbotSettings": null` manages nothing.
* `rangedRetreatOnClose` and `meleeRange` are described under "Sword up close, and out of arrows" below; `meleeRange`
  outside 2..6 (PvP BOT's own clamp) is not applied, with a warning.
* Validation: `maxTargetDistance` is clamped to 4..128; the three ranges must satisfy
  `rangedMinRange < rangedOptimalRange <= rangedMaxRange <= maxTargetDistance` (judged with PvP BOT's own value for
  any you leave out), otherwise **all three ranged keys are skipped** with a warning and the other keys still apply.
* When it is applied: at the first tick (before the PvP BOT probe report, so the report shows the real values) and
  again **every time PvP BOT loads its per-world settings** (`/pvpbot reload`), which the addon notices because PvP
  BOT then replaces its settings object. Nothing is written when the values already match. The values are written
  into PvP BOT's settings object directly, not through its setters (they clamp the optimal range to at least 10 and
  the maximum to at least 15; the addon writes the fields, so a managed value may deliberately lie outside those
  setter clamps), and then PvP BOT's own save routine writes the settings file once.
* One INFO line names what changed: `PvP BOT settings: maxTargetDistance 64 -> 128, autoEquipWeapon true -> false`.
  A missing PvP BOT field is one warning and never an exception. `/inhabitants adapter` (and the probe report) warn
  when the effective `rangedMinRange` is above `maxTargetDistance`: ranged bots would back away from every target
  inside their own targeting radius.
* The other settings stay PvP BOT's. `autoTargetEnabled` false is the intended state under this addon: `/inhabitants
  adapter` no longer warns about it while `aggro.enabled` is on. PvP BOT's revenge logic (`revengeEnabled`, not managed)
  still handles whoever hit an inhabitant, and it accepts an attacker within `maxTargetDistance`, which at 128 is
  the mod's maximum.

### Sword up close, and out of arrows

A bot that carries a sword or axe (or a mace, spear or trident, as PvP BOT scores them) and a bow or crossbow shoots
while the player is farther than **5 blocks** (twice `meleeRange` 2.5) and takes the melee weapon out once the player is
within it, like a player swapping to a sword up close. `rangedRetreatOnClose: false` is what lets PvP BOT's own melee
mode win inside that distance (it also removes PvP BOT's velocity push away from a target inside melee range);
`meleeRange` sets the distance. PvP BOT's melee mode approaches until the target is within `meleeRange` and attacks
there: 2.5 blocks between the two bodies' centres, inside vanilla's 3.0 reach.

PvP BOT chooses its ranged mode whenever a bot carries a bow or crossbow anywhere in its inventory, without looking at
ammunition. A bot with **no arrow left** used to select the empty weapon, notice it cannot shoot, flip to melee mode
without attacking or moving, and repeat that every tick: it stood there holding the crossbow. Now:

* within 5 blocks PvP BOT's own melee mode runs (the setting above);
* beyond 5 blocks an out-of-ammo gap closer (after PvP BOT's tick, vanilla inventory calls and PvP BOT's own
  look-and-move only) selects the melee weapon (moving it into the hotbar if it sits in the main inventory) and walks
  toward the target until PvP BOT's melee mode takes over. It acts only for a bot PvP BOT has a live target for, that is
  not retreating or eating, has no arrow of any kind in slots 0-35 and no loaded crossbow it can fire, and carries a
  melee weapon;
* a crossbow that is still **loaded** fires its bolt first (the crossbow trigger allows it without an arrow in the
  inventory), then the sword comes out;
* a bot with no melee weapon and no ammunition is left exactly as PvP BOT has it;
* PvP BOT has no "pick arrows up" behaviour, and none is added: a bot only picks up arrows by walking over them, as in
  vanilla. Once it has an arrow again it shoots again.

## Natural bow and crossbow speed (no artificial limits)

The philosophy: an inhabitant shoots as fast as a person with the same weapon and the same enchantments would, and
uses nothing but vanilla mechanics. There is **no rate limit, cooldown or aim delay of this addon's own**; the only
gates on a shot are vanilla's (the charge time, the item use) and "has a live target in line of sight" (no shooting at
nothing).

* **Bows.** PvP BOT holds a bow draw for `bowMinDrawTime` ticks, default 40 (2 s), which is an artificial wait: vanilla
  full power is reached after 20. The addon manages `bowMinDrawTime` at **20** (see "Managed PvP BOT settings"), so an
  inhabitant releases a full-power arrow about every 21 ticks. Real-server test: gaps of 21 ticks, launch speed 3.0.
* **Crossbows.** Vanilla loads a crossbow as soon as its charge time has passed (25 ticks; Quick Charge I/II/III: 20, 15,
  10), but PvP BOT keeps every draw for a fixed 25 ticks whatever the enchantment, and on this Minecraft version its
  routine can never fire a **loaded** crossbow at all (it ends every cycle by releasing an item that is not being used,
  which does nothing). Once per server tick, after PvP BOT's own tick, for every inhabitant holding a crossbow in its
  main hand:
  1. **Release the draw when it is loaded**: while the bot is drawing and `CrossbowItem.isCharged` has just become true,
     the draw is released (`releaseUsingItem`), so the weapon does not sit loaded for the rest of PvP BOT's 25 ticks.
  2. **Fire the loaded crossbow** when the bot is not using an item and PvP BOT's current target is alive, in the same
     dimension and in line of sight: through the vanilla right-click path (`ServerPlayerGameMode.useItem`, exactly what a
     player's click does; no projectile is created here, damage, accuracy and speed are vanilla).

  The cycle is the charge time plus about two ticks: about **12 ticks with Quick Charge III**, about 27 without: what a
  person spam-clicking gets. Real-server test: eight gaps of exactly 12 ticks with a Quick Charge III crossbow, never
  faster than the 10 tick charge allows.
  A crossbow that is still loaded when the last arrow is gone fires too (PvP BOT's mode plays no part in the gate), so the
  out-of-ammo bolt is not wasted; then the sword comes out (see "Sword up close, and out of arrows").
* Removed: the earlier crossbow pacing (`rangedPacing.crossbowMinShotIntervalTicks`, the item cooldown that enforced it)
  and the aim-settle delay (`rangedPacing.aimSettleTicks`). A config that still has a `rangedPacing` block loads fine:
  the keys are ignored with one INFO line and are not written back. (An item held on a continuous "use" action, for
  example by PvP BOT's shield routine, right-clicks the crossbow whenever it is loaded: that is the vanilla mechanic and
  it is no longer capped.)
* Fail-soft: a missing PvP BOT name is one warning and the trigger then never fires; a bug in the tick switches the
  trigger off with one warning. Only inhabitants (the addon's own roster) are touched, never real players or other bots.
  The shots an inhabitant launches are still attributed in the combat diagnostics (`ranged loop:`), for diagnostics only.

## Managed PvP BOT setting: critical-hit fall phase

PvP BOT's melee routine only swings after a jump-crit with `crit-fall-ticks` ticks of descent (its default is
6), so bots looked passive at close range. The addon manages this one setting itself: at every server start it
runs PvP BOT's own command `pvpbot settings crit-fall-ticks <criticalFallTicks>` (config key
`criticalFallTicks`, default **3**, `0` = leave PvP BOT alone), through the same console mechanism as
`startupCommands`. Criticals stay enabled. An explicit
`pvpbot settings crit-fall-ticks N` in your own `startupCommands` wins over the managed value.

## Lag governor (`tpsThrottle`)

The governor sheds inhabitants (farthest from the nearest real player first; a seen bot sleeps with its state, an unseen
one is deleted, nothing drops and it is not a death) and
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

## The line-of-sight hunter (`aggro`)

PvP BOT's own auto-target notices any player within 64 blocks, through walls. This addon replaces it (the settings
policy turns PvP BOT's `autoTarget` off) with a hunter that works like a person: **everything is decided by line of
sight, there is no block-distance rule of any kind** (no aggro range, no leash). The only distance limit is the mod's
own maximum, 128 blocks (vanilla `hasLineOfSight`'s cap and PvP BOT's largest `maxTargetDistance`, read from PvP BOT's
effective value and capped at 128). The `aggro` block of `pvpbot_inhabitants.json`:

```jsonc
"aggro": {
  "enabled": true,
  "requireLineOfSight": true,          // false: a clear view is not required (sees through walls; not recommended)
  "reactionTicks": 5,                  // 0..100: a person needs 0.25 s to register somebody
  "distanceReactionTicksPer32": 5,     // 0..100: extra ticks per 32 blocks of distance; 0 disables. A soft scaling, not a limit
  "loseGraceTicks": 10,                // 1..72000: no line of sight for this long = the target is lost (0.5 s: a tree trunk does not break a chase)
  "searchTicks": 200,                  // 20..72000: the search lasts this long (10 s)
  "returnArriveDistance": 1.5,         // 0.5..16: blocks from home at which the walk back counts as arrived
  "stuckTicks": 40,                    // 10..1200: no progress along a route for this long replans it
  "returnMaxTicks": 1200,              // 20..72000: the longest walk home (60 s); then the bot stays where it is
  "scanIntervalTicks": 3,              // 1..40: how often an idle inhabitant looks around (staggered across inhabitants)
  "perception": { "enabled": true, "frontHalfAngleDeg": 60, "peripheralHalfAngleDeg": 100, "peripheralMultiplier": 2,
                  "sneakMultiplier": 2, "hearWalk": 4, "hearSprint": 8, "hearCombat": 12, "combatNoiseTicks": 10 }
}
```

An older config with `acquireRange`, `leashRange`, `loseSightTicks`, `returnToOrigin`, `returnStuckTicks`,
`perception.peripheralFactor` or `perception.sneakFactor` loads fine: they are ignored, with one INFO line, and not
written back.

The states of every inhabitant (`/inhabitants` status and the "Combat taken" lines show them):

```
IDLE --(noticed)--> CHASE --(lost)--> PURSUE --(arrived or blocked)--> SEARCH --(10 s)--> RETURN --(home)--> IDLE
  PURSUE / SEARCH / RETURN --(noticed again)--> CHASE          IDLE / PURSUE / SEARCH / RETURN --(hit)--> REACT or PURSUE
```

* **Noticing** (IDLE, and again while pursuing, searching or walking home). A player, or a companion bot, is noticed after
  staying in view for the reaction time: 5 ticks (0.25 s) in front, longer when it is harder to see (twice as long in
  the periphery of the 200 degree field of view, twice as long when sneaking, longer far away: 11 ticks at 40 blocks,
  15 at 64, 25 at 128, longer still when invisible or disguised). **Nothing is seen from behind**, but close footsteps
  are heard (walking 4, sprinting 8 and fighting 12 blocks; sneaking or standing still is silent; never through a wall),
  which counts as seeing in front. Hearing only ADDS awareness from behind; it restricts nothing. So a player walking up
  behind an inhabitant while sneaking is not noticed until they strike. Details and the shared rules:
  `docs/PERCEPTION.md`, golden vectors in `docs/perception/vectors.json`. "Valid" follows PvP BOT's own rules (not
  creative/spectator unless `attackInvincible`, not a faction ally, other bots only with `targetOtherBots`).
  `perception.enabled=false` is plain vanilla line of sight: in range and unobstructed is noticed at once.
  The noticed player is handed to PvP BOT as its forced target.
* **CHASE.** PvP BOT fights and moves. While the target is in sight (occlusion only, no cone: an engaged bot faces its
  target) the last known position and velocity are recorded. When it has been out of sight for `loseGraceTicks`, or is
  beyond the 128 block maximum, the target is LOST. A target that is dead, logged out, in another dimension or no longer
  attackable ends the hunt: PvP BOT's target state is cleared and the bot walks home.
* **PURSUE.** PvP BOT's target is cleared (so it does not track the player through walls; that also wipes its revenge
  memory) and the bot walks to the last known position along a route planned around obstacles (see "Routes" below). If the
  player was moving it continues a few blocks along the last heading first. No route, or no progress for `stuckTicks`
  (after replans): it searches from where it stands.
* **SEARCH** lasts `searchTicks` from the moment it begins. The bot looks all the way round (four quarter turns, about 24
  ticks), then repeatedly walks to the most promising nearby spot and looks round again. Spots are scored by alignment with
  the player's last heading, by how much hidden space would open up from there (corners, doorways, corridor branches:
  rays from the candidate to sample points the bot cannot see from where it stands), and by not having been visited; ones
  it cannot reach in the remaining time are skipped. A sound heard from behind cover (walking, sprinting or fighting
  within earshot, occluded) moves the search focus there within the same window. Seeing the player again (the reaction
  time applies) is a new CHASE. When the window is over the bot RETURNs.
* **RETURN.** The bot walks back to its **home anchor**: its position and level when it FIRST started aggro'ing. Home
  is kept through every cycle until the bot is back within `returnArriveDistance`; then it is cleared. The bot always
  returns to it, never to a temporary point: if it notices the player again on the way, the new chase (from wherever the
  bot is) does not change the home. If it cannot get home within `returnMaxTicks` it stops where it is (home cleared, one
  INFO line). Never a teleport, and the bot never breaks or places a block for any of this.
* **Being hit.** A hit by a valid player makes the bot aware of the attacker immediately, and the last known position is
  where the attacker stood. If the attacker is in sight, the bot turns to the pain (REACT) and reacts after the reaction
  time (`reactionTicks`); PvP BOT's instant revenge is cleared on the hit tick and the target is handed over when the
  delay ends. If the attacker is NOT in sight (an arrow from cover) the bot goes to PURSUE at once, to that position. Hits
  are detected both through PvP BOT's revenge memory and through the damage taken (HeroBot fake players skip the Fabric
  damage event), from any distance up to the 128 block maximum.
* **Mobs.** PvP BOT's native revenge still fights mobs (a forced target cannot name a specific mob): a mob fight is tracked,
  ends when the mob is out of sight for `loseGraceTicks` or gone, and the bot walks home; no pursuit and no search for
  mobs. A mob fight starts a home anchor like any other.
* **Somebody else's forced target** (a `/pvpbot` command, another mod) is only tracked: never cleared, pursued or
  returned from. A forced name PvP BOT writes itself for its wind-burst / elytra flow names the attacker or the current
  revenge target and is not "somebody else's".
* **Inert mode.** While PvP BOT's `autoTarget` is on, PvP BOT notices by itself: nothing is noticed here (logged once at
  INFO), but the lost-target pursuit, the search and the walk home still apply.
* **Patrols.** PvP BOT applies a patrolling inhabitant's movement toward its next patrol point every tick, which would pull
  it against the pursuit, the search and the walk home. While the hunt walks the bot (from PURSUE or the walk home until it
  is home, abandoned or switched off) the addon pauses that bot's follower (the path is kept) and resumes it afterwards; the
  patrol then restarts from its first point. Bots without a patrol of this addon are unaffected.
* **Order of work.** All of this runs in a Fabric tick phase ordered after the default phase, after PvP BOT's own bot tick,
  so its steering is the last input written each tick (over idle wander).
* **Status and logs.** One text per bot, for example `chasing Jack (seen 0.4 s ago; noticed by sight after 0.3 s)`,
  `pursuing Jack's last position, 6.2 to go`, `searching for Jack, 7.5 s left, 3 points checked`,
  `returning home, 18.0 to go`, `turning to Jack's hit (reacting)`, `idle`; it is appended to the "Combat taken" line
  as `aggro[...]`. Every transition is logged at debug; INFO at most one line per bot per 10 s.

### Routes

PvP BOT only steers in a straight line (jump, simple avoidance) and has no path finder. The wrapper plans routes through a
`PathPlanner` interface (a list of waypoints, bounded, never breaking or placing a block, never moving anything). The
shipped planner is **vanilla pathfinding through a detached helper mob**: one zombie per level, created but never added
to the level (so it is never in a chunk, never ticked, never spawned, saved or seen), placed at the bot's position
and asked for a route with the follow range set to cover the goal; vanilla bounds the search (follow range x 16
visited nodes) and knows walking, jumping up, dropping, swimming, doors, lava, fire and cactus. The bot follows the waypoints
with PvP BOT's own look and move input; a route that makes no progress for `stuckTicks` is replanned, and route
planning is limited to one plan per 20 ticks per bot and two per server tick overall. The interface exists so a
Baritone-backed planner (Minecraft-AI, when it is loaded) can be dropped in later without touching the state machine.

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

### Melee legality (no hitting through walls)

PvP BOT's melee routine has no line-of-sight check: an inhabitant chasing a player stood next to a one-block wall and
killed him through it. The addon vetoes every melee hit an inhabitant makes that a human client could not have made
(`combat.meleeLegality.enabled`, default `true`; the rule itself is vanilla and not configurable):

* a hit counts as **melee** when the damage source has the attacking inhabitant as both the causing and the direct entity
  (`player_attack` / `mob_attack`, or a weapon's own melee type: `spear`, `mace_smash`); projectiles, thorns, explosions and the like are never touched;
* it is **legal** when a human could have targeted the victim: the crosshair ray from the eye reaches the victim's
  bounding box within the vanilla **attack range of the attacker's weapon** (`AttackRange.isInRange` for
  `entityAttackRange()`: the weapon's `attack_range` component, else the default from `ENTITY_INTERACTION_RANGE`; sword
  and axe 3.0 blocks, a 1.21.11 spear 2.0 to 4.5 plus its 0.125 hitbox margin, so a spear cannot jab something closer
  than its minimum range, as in vanilla; measured to the nearest point of the box, plus a 0.2 block tolerance for
  position lag, vanilla's own server check adds 3.0 as anti-cheat slack) and no block with a collision shape (fluids ignored) is on the
  ray. A human can aim at any visible part of the box, so the nearest point, the centre, the head and the feet are tried;
  a vanilla sweeping-edge victim of the same swing as a legal primary hit is legal too;
* an illegal hit returns "no damage": no health loss, no knockback, no effect. PvP BOT's state is left alone, it simply
  keeps failing to land hits until it has a real line;
* every veto is a debug line; one INFO line per bot per minute reports the count and the reasons
  (`melee legality: vetoed N hit(s) by <bot> since the last line (a without a clear line, b outside its attack range); ...`). A bot's own count is also appended to its
  "Combat taken" line as `melee[vetoes N (wall a, reach b, farthest d)]`.

Limit: Fabric's damage event fires for normal players and mobs (whom inhabitants hit), not for a HeroBot victim, so an
inhabitant hitting another inhabitant is not covered. PvP BOT's `meleeRange` of 3.5 is measured between entities; 3.5
centre to centre is about 3.2 to the victim's box, which the tolerance still allows, so that setting itself is not a
cheat beyond vanilla reach by more than the tolerance.

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

## Inhabitants are ordinary survival players: what they use up stays used up

The point of an inhabitant is that it plays like a player, with the limits of a player: it can run out of arrows,
food, potions, blocks and tool durability, and nothing refills it.

* **Saved state.** The live state of every inhabitant (all 41 inventory slots including armor and offhand as vanilla
  item data with count, damage and components, the selected slot, health, food level, saturation, exhaustion, active
  effects, experience, fire and air) is written into its record in `populations.json`: once right after its first
  dressing, then about every 5 seconds while it is online (only when it changed), again when it goes dormant and once
  more when the server stops.
* **Sleep keeps it.** A seen bot that goes to sleep is emptied and removed without dying (see "Population by allocation"). It used to wake
  dressed again from its stored profile, with a full quiver, fresh food, potions and totems, pristine gear and full
  health. It now wakes with exactly the saved state, slot by slot, and is never dressed a second time. A record from
  before this change has no saved state: it is dressed from its profile once (one INFO line per bot) and saved from
  then on.
* **A restart does not heal.** HeroBot creates a fake player at full health. After a restart the saved health,
  hunger, saturation, exhaustion and missing effects are put back (health never above the current maximum).
  A bot the addon has not dressed yet (no marker) is restored from its saved state when it has one, from the
  profile only when it never was saved (`profiles.reapplyOnRestore` still switches this whole re-dressing off).
* **Survival mode.** HeroBot spawns a fake player in **creative** unless told otherwise, and PvP BOT's own switch to
  survival can run before the player exists. Every inhabitant the addon sees (spawn, restore, and every 5 s) that is in
  another game mode or has creative-style abilities (instabuild, mayfly, invulnerable, flying) is put into survival
  through vanilla's own path; one WARN per bot.
* **Vanilla stats only.** Earlier versions gave every bot permanent attribute modifiers with no item or effect
  behind them (max health -10..+20, reach, knockback resistance up to 1.0, attack speed). A normal player cannot have
  those, so `profiles.attributeVariation` and `profiles.scaleVariation` are gone (an old config that still names them
  is fine: unknown options are ignored). Modifiers of this addon that an earlier version put on an existing bot are
  removed (one INFO line per bot) and its health is clamped to the new maximum. Armor, toughness, netherite knockback
  resistance and enchantments come from gear and stay. Seeded (deterministic) worlds keep every other roll: the old
  attribute rolls are still drawn and dropped.
* **No eating at a full food bar.** PvP BOT starts eating with `startUsingItem`, which skips vanilla's rule that food
  can only be eaten below 20 food unless it is always edible. An inhabitant that is eating food which a player could
  not eat right now is stopped in the same tick (golden apples, chorus fruit and the like are always edible and are
  left alone; potions and milk are not food and are never touched).

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
natural crossbow and bow speed (a Quick Charge III crossbow about every 12 ticks, a full-power bow arrow about every 21),
the managed PvP BOT settings (including their re-application after PvP BOT reloads its settings), the combat log lines
and the ranged-loop cause text, and the line-of-sight hunter (`AggroGameTests`): the reaction time, nobody noticed from
behind (but footsteps heard, sneaking silent), a player 40 blocks away noticed and chased, a lost player pursued,
searched for 10 s and the bot walking home without a teleport, noticing again on the way home, and an arrow from cover.

```bash
./gradlew runGameTest -PupstreamModsDir=<dir with PVP_bot-*.jar and herobot-*.jar>   # default C:\mcw\_tools\deploycheck\mods
```

When the directory or either jar is missing, `runGameTest` is skipped with a message and everything else (build, unit
tests) is unaffected. `-PharnessFixesOff=true` switches the addon's managed PvP BOT settings off in the run, which
reproduces the failures they fix. `gradlew build` and `check` do NOT run the GameTests (only an explicit `runGameTest`, or
the machine-wide runner script, does). Two workarounds live in the test mod only: `InventoryHelperDevShimMixin`, a test-only field-name translation that changes no behaviour (it maps PvP BOT's reflective
hotbar-index lookup to the runtime field name; the dev runtime uses Mojang names, PvP BOT looks up Yarn and
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
5. **Fly away and back** to a populated structure. A bot you looked at is the same bot (same name, same items, where
   you left it); the ones you never saw are replaced by fresh bots, never more than the structure's planned number.
6. **Restart the server.** Confirm the same bots come back (or are correctly left dead if killed — see
   next step) and no duplicates appear.
7. **Kill an inhabitant.** Confirm it drops its loot and XP like a player, and that its slot is never refilled,
   even after further restarts or leaving and coming back.
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
