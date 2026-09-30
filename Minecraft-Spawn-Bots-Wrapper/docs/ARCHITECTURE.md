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
| All 68 settings live in **one process-wide singleton** (`BotSettings`). Nothing is per bot. | The addon writes only the few settings under `pvpbotSettings` (server-wide, only when they differ). Per-bot variety comes only from levers that are genuinely per bot (below). |
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
├─ combat/                  the line-of-sight hunter (pure): Perception, ExposureTracker, AggroController, PathPlanner,
│                           PathFollower, SearchPlanner; combat log ledgers and diagnostics
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
8. **Done.** When every planned bot has spawned, died or definitively failed, the structure becomes `POPULATED`
   (or `GAVE_UP` if none could be placed). Both are terminal as far as the ROLL goes: N is never re-rolled.
9. **Allocation.** Which bots exist now is decided by `AllocationGovernor` (see 3b), not by the order structures were
   found in.

### 3b. Population by allocation (engine package)

* `StructureIndex` keeps the occupied structures by 16x16-chunk cell; only those reaching into a real player's relevance
  area (simulation distance + margin, at most `dormancy.distanceBlocks`) are looked at.
* `Allocation` (pure) sorts them by 3D distance from the nearest real player to the bounding box and walks the list,
  giving each its fill target (`N - dead - failed`) until `processing.maxLiveBots` is used up; protected bots (engaged with
  a player, or SEEN while their chunk is loaded by a player) count first. Hysteresis keeps an allocated structure against a rival that is
  not nearer by `allocation.hysteresisBlocks`.
* `AllocationGovernor` recomputes only when a player moved, changed level, or something relevant happened, at most once
  per interval, and diffs the desired counts against the live ones: surplus bots are handed to `Retirer` (paced by the lag
  governor's batch size, after the grace and dwell times), structures that came in get their sleepers woken first
  (`DormancyRestorer`) and fresh bots rolled for their vacant slots, which the `PopulationDriver` places nearest first
  under its own pacing and the lag governor's spawn block. There is no separate queue: each pass is a diff.
* `SeenTracker` asks the gateway whether a real player sees a live bot (view cone, range, invisibility, then the
  occlusion rays, last); the answer is stored for good in the bot's record.
* `Retirer` is the only place that ends a live inhabitant. A removal is NOT a death: `BotGateway.remove` empties the bot
  (`adapter.BotRemoval`) and lets it leave like a logging-out player before PvP BOT forgets it, so nothing drops and
  nothing dies. `BotRemoval.empty` also clears the 2x2 crafting grid and result slot and returns what it took, so a removal
  that fails after the emptying puts everything back. A seen bot sleeps (snapshot, position and a `removing` mark are made
  durable BEFORE the bot is emptied, by one append to the store's write-ahead journal `populations.journal`, see
  `store.RecordJournal`), an unseen bot is deleted with no record (its `removing` mark is journaled first, so a crash can
  never turn the missing bot into a death; after a restart `Retirer.finishInterrupted` and `tickLate` finish or undo it); `died` turns a real death into a `DEAD` record for good.
* Removal paths, and what they do: allocation surplus, lag shedding and the legacy distance rule go through `Retirer`
  (sleep or delete); an admin `reset ... removeBots` and a failed spawn go through the same non-death `remove` and drop
  the record; a real death arrives from the server's death event (or, as a fallback, a bot gone for `goneConfirmTicks`);
  a server stop keeps every record and saves the live states.

### Why nothing is populated twice

* One roll per structure key (see 2), and the record is written before spawning starts.
* A crash between "requested" and "appeared" resumes: a bot that is online is adopted, one that is not is
  retried. Names are fixed at roll time, so a resume never renames or double-spawns.
* Abandoned decisions survive even a hard crash (the key log is appended immediately).
* If persisted data cannot be read, the store reports itself **unusable** and the addon refuses to roll
  anything: a wrongly empty store would otherwise re-roll the world and duplicate populations.
* Killed inhabitants are never replaced: a death is a `DEAD` record, the slot is spent for good and a structure yields
  at most N bots' worth of kills over the world's lifetime. Only vacant slots (from deleted, never-seen bots) are ever
  rolled again, and only a structure that is allocated. A structure never returns to "unprocessed".
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

PvP BOT reads every combat and behaviour setting from a global object. The addon changes only the few settings
listed under `pvpbotSettings` in its config (targeting radius, the archer distances, weapon auto-equip, PvP BOT's own
auto-target, the bow draw time: see
README, "Managed PvP BOT settings"), through one class (`adapter/UpstreamSettingsWriter`), only when they differ
and only for the whole server, never per bot ("silently modifying every existing bot" is what the design avoids). The per-bot levers that do exist
are:

| Lever | Mechanism | Examples |
|---|---|---|
| **Loadout** | PvP BOT chooses weapon mode, shield, totem, potion, food and mending behaviour from what a bot carries | armor tiers/enchants/wear, sword vs axe vs mace vs bow vs crossbow, arrows, shield, totems, food, potions, XP bottles, cobwebs |
| **PvP BOT's own path system** | per-bot path with waypoints, walk type and an attack flag | guard post / patrol / ring, patrol radius, bhop/sprint/walk (the attack flag is always ON: every inhabitant fights) |
| **Initial vitals** | health and hunger at spawn | injured or hungry bots so eat/retreat logic is exercised |

There is deliberately no attribute lever: earlier versions rolled permanent attribute modifiers (max health,
reach, attack speed, knockback resistance) that no item or effect stands behind, stats a player cannot have, so an
inhabitant has the attributes of a vanilla player and the addon removes those modifiers from bots that still carry
them (`meleeRange` and `attackCooldown`, which used them as proxies, are GLOBAL_ONLY now). Settings with no truthful per-bot proxy
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
  inhabitants spread over many structures keep many chunk areas loaded. `processing.maxLiveBots` caps this and the
  nearest-first allocation decides who gets the slots; structures beyond the budget stay pending (never re-rolled)
  until they are nearer to a player than what holds the slots. The allocator costs under 0.1 ms per server tick with
  500 known structures and 64 live bots (`AllocatorCostTest`).
* The abandoned-structure log is append-only and indexed by chunk, so a heavily explored world does not slow
  saving down; only the small set of occupied structures is rewritten.
* Block inspection never loads or generates a chunk.

## 7. The line-of-sight hunter

`combat.AggroController` is pure decision logic over three ports: `AggroWorld` (inhabitants, players, eyes, look
direction, stance, occlusion rays, hits taken, candidate search cells), `TargetControl` (PvP BOT's targets and its look /
move-toward / halt input, implemented by the adapter) and `PathPlanner` (routes as waypoint lists). Everything Minecraft is
in `mc.AggroDriver` (world view) and `mc.VanillaPathPlanner` (vanilla pathfinding through a detached helper mob that is
never added to the level); PvP BOT is only ever touched through the adapter. It runs in a Fabric tick phase ordered after
PvP BOT's own bot tick (`mc.LateTickPhase`) so that its steering is the last input each tick.

The state machine per inhabitant is IDLE -> CHASE -> PURSUE -> SEARCH -> RETURN -> IDLE (plus REACT, the reaction
delay after a visible hit); see the README for the rules. Time, not distance, is what noticing is made of
(`Perception` + `ExposureTracker`, shared with Minecraft-AI through `docs/perception/vectors.json`); the only distance limit
is the mod maximum of 128 blocks. A Baritone-backed `PathPlanner` can replace the vanilla one without touching the state
machine.
