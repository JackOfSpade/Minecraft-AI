# Architecture

PvP BOT Inhabitants is a standalone Fabric addon for **Minecraft 1.21.11**. It populates registered
structures, vanilla and modded, with bots that the upstream **PvP BOT** mod spawns and drives. It never
copies, bundles, patches or mixes into PvP BOT.

This document explains what the addon does, why it is built the way it is, and exactly how it touches PvP BOT.

## 1. What upstream really offers (audited against PvP BOT 0.0.15)

The design follows from facts found by reading the upstream source and checking the released jar, not from
assumptions:

| Fact | Consequence |
|---|---|
| PvP BOT has **no public API**. An API existed in 0.0.13/0.0.14 and was deleted ("will be rewritten"). | Every upstream symbol is treated as private. One adapter class talks to it by reflection and fails soft. |
| All 68 settings live in **one process-wide singleton** (`BotSettings`). Nothing is per bot. | The addon never writes a setting. Per-bot variety comes only from levers that are genuinely per bot (below). |
| Bot identity is the **name string only**; everything upstream is keyed by name. | Names are addon-generated, reserved-prefix, unique, and recorded. |
| `BotManager.spawnBot(...)` returning `true` does **not** mean a bot exists; the entity may appear several ticks later, and PvP BOT drops an entry whose entity is absent for ~50 ticks. | Spawning is asynchronous: request, then poll for the entity and for PvP BOT listing it; repair orphans; time out. |
| Spawning is done through HeroBot's `playerspawn` command; bots are full player entities and hold chunk tickets. | A live-bot cap, paced spawning, and honest documentation of the chunk-loading cost. |
| Bots are restored by PvP BOT from its own `bots.json` at server start (no completion signal). | The addon never respawns a missing inhabitant, waits before reconciling, and re-applies only per-bot state upstream does not persist. |
| `BotNameGenerator` does blocking HTTPS on the server thread. | Never touched. The addon has its own name generator. |
| PvP BOT sends telemetry (bot names, counts) over plain HTTP by default. | Documented in the README; the addon sends nothing itself. |

## 2. Module map

```
dev.spawnbotswrapper.inhabitants
├─ InhabitantsMod           entrypoint: wiring, lifecycle, tick loop
├─ config/                  editable JSON config, validation, include/exclude, per-structure/tag rules
├─ util/, sample/           stable hashing + RNG, coverage sampler (bucket decks)
├─ structure/               StructureKey / StructureSnapshot / IntBox   (pure data)
├─ engine/                  PopulationEngine + ports              (pure logic, no Minecraft)
├─ store/                   persistence (atomic JSON + append-only abandoned key log)
├─ profile/                 BotProfile schema, ProfileGenerator, ProfileFormatter, vocabulary
├─ spawn/                   position finding + patrol planning     (pure logic over a BlockProbe)
├─ catalog/                 SettingCatalog: classification of every PvP BOT setting
├─ mc/                      Minecraft glue: chunk-event detection, block probe, profile applier, gateways
├─ adapter/                 PvpBotAdapter: the ONLY class that references PvP BOT / HeroBot
└─ command/                 /inhabitants admin and test commands
```

The important seams are **ports**: small interfaces the pure logic depends on, so it is fully unit-tested
without a Minecraft server.

```
                     +-----------------------------------------------+
   chunk events ---> | mc.StructureDetector  (Minecraft, thin)       |
                     +----------------------+------------------------+
                                            | StructureSnapshot (pure data)
                                            v
   +----------------------------------------------------------------------+
   |  engine.PopulationEngine   (pure)                                     |
   |    roll once -> plan bots -> find positions -> spawn -> profile -> save |
   |                                                                        |
   |  ports:  BotGateway   WorldGateway   Clock   ProfileFactory   SpawnPlanner |
   |  storage: store.PopulationStorage                                       |
   +---------+---------------+--------------+-------------+-----------------+
             |               |              |             |
             v               v              v             v
      mc.McBotGateway   mc.McWorldGateway  Clock   profile.ProfileGenerator
             |               |                          spawn.DefaultSpawnPlanner
             v               v
   adapter.PvpBotAdapter   mc.McBlockProbe
   (reflection + commands)  (never loads chunks)
             |
             v
        PvP BOT / HeroBot
```

`PvpBotAdapter` is the single point of contact with upstream. An architecture test fails the build if any
other source file mentions PvP BOT or HeroBot.

## 3. From chunk load to living inhabitants

1. **Detection.** `ServerChunkEvents.CHUNK_LOAD` (and `CHUNK_GENERATE`, to know a structure is new) fires on
   the server thread *before* the chunk's own future completes, so the callback only remembers the chunk.
   On the next tick the detector reads the chunk's `StructureStart`s from Minecraft's structure registry.
   No block scanning, no name lists: anything registered as a structure, including modded ones, appears
   automatically.
2. **Identity.** A structure instance is `(dimension, registry id, start chunk)`. A structure has exactly
   one start chunk, so a village of fifty houses is **one** key and therefore **one** roll, however many of
   its chunks load, unload or reload.
3. **Roll, exactly once.** Eligibility (include/exclude ids and tags, dimensions) then a single roll against
   the configured occupied chance. The decision, the bot count, every bot's name and seed are **persisted
   before any bot is spawned**.
4. **Abandoned** structures are recorded permanently (append-only key log, flushed immediately) and are never
   rolled again. **Occupied** structures become `OCCUPIED_PENDING`.
5. **Positions.** The spawn planner samples columns inside the structure's *pieces* (multi-storey aware),
   requires a solid standable floor, two blocks of headroom, no fluid/fire/lava/hazard, and enough distance
   between bots. It reads blocks only from already-loaded chunks and reports "chunk not loaded" separately
   from "no valid floor", so a structure whose neighbouring chunks are still generating is retried without
   burning an attempt.
6. **Spawn (write-ahead).** The bot is marked `REQUESTED` and saved *before* the request is sent. The adapter
   asks PvP BOT to spawn it, then the engine polls for the entity and for PvP BOT listing it.
7. **Profile.** When the entity exists, a randomized profile is generated, its patrol waypoints are planned
   from the real home position, and the profile is applied (loadout, vitals, path). The stored profile is
   authoritative and never regenerated.
8. **Done.** When every planned bot has spawned or definitively failed, the structure becomes `POPULATED`
   (or `GAVE_UP` if none could be placed). Both are terminal.

### Why nothing is populated twice

* One roll per structure key (see 2), and the record is written before spawning starts.
* A crash between "requested" and "appeared" resumes: a bot that is online is adopted, one that is not is
  retried. Names are fixed at roll time, so a resume never renames or double-spawns.
* Abandoned decisions survive even a hard crash (the key log is appended immediately).
* If persisted data cannot be read, the store reports itself **unusable** and the addon refuses to roll
  anything: a wrongly empty store would otherwise re-roll the world and duplicate populations.
* Killed inhabitants are never replaced; a structure never returns to "unprocessed". There is no respawn logic.
* If PvP BOT is missing or incompatible, nothing is rolled or recorded, so structures are not marked
  processed while unable to be populated.

## 4. Randomization

### Coverage instead of bell curves

Plain random (or normal) values cluster; the extremes almost never show. Every setting owns a **deck** of
buckets: the range is cut into N buckets (default 8), each is dealt exactly once in random order, and only
then is the deck reshuffled. Within the dealt bucket the value is uniform; the first and last buckets snap to
the exact endpoint some of the time. So any N consecutive bots cover N distinct regions of the range (very
slow to very fast, pristine to nearly broken gear) while individual results still look random. Booleans use a
two-card deck (exactly balanced per pair) and categorical choices deal one card per option.

Decks are persisted with the world data, so coverage carries across restarts instead of restarting the
cycle every session.

### Deterministic mode

When enabled, the roll and every profile derive from `world seed + salt + dimension + structure id + start
chunk` through fixed algorithms (SplitMix64 and FNV-1a, implemented here so results do not depend on the JDK
version). Coverage in this mode is per structure: bot *i* is generated after replaying bots *0..i-1* with
fresh decks, so results are independent of what else happened in the world and of restarts. Persisted results
remain authoritative once a structure has actually been processed.

## 5. What "per bot" can honestly mean

PvP BOT reads every combat and behaviour setting from a global object, and the addon never changes it
("silently modifying every existing bot" is exactly what the design avoids). The per-bot levers that do exist
are:

| Lever | Mechanism | Examples |
|---|---|---|
| **Loadout** | PvP BOT chooses weapon mode, shield, totem, potion, food and mending behaviour from what a bot carries | armor tiers/enchants/wear, sword vs axe vs mace vs bow vs crossbow, arrows, shield, totems, food, potions, XP bottles, cobwebs |
| **Vanilla attributes PvP BOT really reads** | attribute modifiers with fixed addon-owned ids | entity interaction range (melee reach), attack speed |
| **PvP BOT's own path system** | per-bot path with waypoints, walk type and an attack flag | guard post / patrol / ring, patrol radius, bhop/sprint/walk, pacifist (attack flag off) |
| **Initial vitals** | health and hunger at spawn | injured or hungry bots so eat/retreat logic is exercised |

Max health and knockback resistance are still varied on the profile (they are legitimate vanilla flavour -
different bots really do have different HP pools) but are not counted as proxies for any PvP BOT setting:
PvP BOT reads health only as a *ratio* of the current maximum (so max health rescales absolute HP without
moving any threshold) and never reads knockback resistance at all. Settings with no truthful per-bot proxy
(ranged distances, aim speed, miss/mistake chance, shield timings, revenge, auto-target, ...) stay
**GLOBAL_ONLY** and are documented, not pretended: see [SETTINGS.md](SETTINGS.md). `SettingCatalog` classifies all 68 settings in code and, at startup, compares
itself with the settings a running PvP BOT reports, logging any new or removed setting so a future upstream
release can be audited mechanically.

## 6. The PvP BOT adapter

`PvpBotAdapter` is the only code that names PvP BOT or HeroBot.

* **No compile-time dependency.** Classes and methods are resolved by name with `Class.forName(name, false,
  loader)` and verified for existence, staticness and signature at startup.
* **Spawn tiers.** T1 `BotManager.spawnBot(server, name, source, pos)`; T2 the 3-argument overload with a
  prepared source; T3 the public `/pvpbot spawn` command. Configurable and self-degrading.
* **A console-derived source** placed in the target dimension and position is always used (HeroBot's
  `playerspawn` silently refuses non-op player sources and takes the dimension from the source).
* **Never trusts a boolean.** Success means: the player entity exists, it is a bot, and PvP BOT lists it. A
  bot that exists but is not listed (upstream's orphan window) is re-listed through upstream's own adopt path.
* **Patrol** uses `BotPath` (create, add points, walk type, loop, attack flag, start following) with the
  upstream traps designed around: paths are built completely before following starts, a single-point
  ping-pong loop (which crashes upstream's tick) is never created, and paths are named with an addon prefix
  and cleaned up when their bot is gone.
* **Never touched:** `BotNameGenerator` (blocking network I/O), `BotFaction` (static initialiser binds the
  world too early), any settings setter, `removeAllBots`, `reload`, mass-spawn.
* **Reporting.** At startup one report logs: addon version, PvP BOT version, HeroBot version, API
  compatibility result (`AVAILABLE`, `DEGRADED`, `UNAVAILABLE` with the exact missing member), spawn tier, and
  warnings about upstream settings that will surprise an operator.

### If PvP BOT changes

Only `adapter/` should need edits. The checklist: update the probed member list, adjust the spawn call, run the
adapter tests (they use fake upstream classes shaped like the contract), then run the end-to-end procedure in
the README. The addon does not pin a PvP BOT version; it probes members and classifies the result, and a newer
but compatible release runs with an "untested version" warning. If a real API returns upstream, a new
implementation of `PvpBotOperations` can replace the reflection code without touching the engine, the store or
the profile logic.

## 7. Performance and scale notes

* Work is bounded per tick (structures rolled, bots requested) and paced (`spawnIntervalTicks`).
* Bots are full players and, like real players, keep their surroundings loaded and ticking; hundreds of
  inhabitants spread over many structures keep many chunk areas loaded. `processing.maxLiveBots` caps this;
  structures beyond the cap simply stay pending (never re-rolled) until a bot dies.
* The abandoned-structure log is append-only and indexed by chunk, so a heavily explored world does not slow
  saving down; only the small set of occupied structures is rewritten.
* Block inspection never loads or generates a chunk.
