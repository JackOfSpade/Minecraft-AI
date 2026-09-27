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
  "default": { "occupiedChance": 0.65, "minBots": 1, "maxBots": 4 },

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
    "allowElytra": false
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
    "namePrefix": "Inh",
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
only `occupiedChance` and still inherit `minBots`/`maxBots`. `exclude` always wins over `include`.

Full field reference: every key above, its valid range, and what it does is in
[`InhabitantsConfig`](src/main/java/dev/spawnbotswrapper/inhabitants/config/InhabitantsConfig.java).

### Deterministic mode

`deterministic.enabled: true` derives every roll and profile from `world seed + salt + dimension +
structure id + start position`, so a fresh copy of the same world (and the same salt) reproduces the same
inhabitants from scratch. Once a structure has actually been processed, the saved result is authoritative —
deterministic mode does not retroactively re-roll anything.

## What gets randomized, and what does not

PvP BOT keeps every combat setting in **one process-wide singleton**; this addon never touches it, so it
never silently changes the behaviour of bots that already exist. Per-bot variety therefore comes only from
what a bot **carries** (PvP BOT chooses its weapon mode, shield/totem/potion/food/mending behaviour from
its inventory), from the few vanilla **attributes** PvP BOT actually reads, and from PvP BOT's own **patrol
path** system (stance, walk type, and a pacifist flag).

The full table — every one of PvP BOT's 68 settings, whether it can vary per bot, and exactly how — is in
[docs/SETTINGS.md](docs/SETTINGS.md). The architecture and the reasoning behind it are in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

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
your own JDK 21 both work) and internet access on first run (Minecraft, Yarn mappings, Fabric API, Gradle
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
