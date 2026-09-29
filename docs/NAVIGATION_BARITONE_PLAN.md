# Bot navigation on Baritone: analysis findings and proposed plan

Status: **PROPOSAL, awaiting the user's review. Nothing below is built yet.**
Date: 2026-09-29. Sources analysed (read-only, temporary copies in `C:\Users\PC\Downloads\baritone-analysis`):

| Tree | Version | Mappings | Role |
|---|---|---|---|
| cabaletta/baritone, branch 1.21.11 (commit 2372389, 2026-08-31) | v1.17.0, MC 1.21.11 | Mojang | **Base.** Current algorithms, our exact MC version, client-only. |
| Ladysnake/Automatone, branch 1.20 (2023-11-01) | v0.11.0, MC 1.20.1 | Yarn/Quilt | Readable template for the server-side layer; its pathing is an older Baritone. |
| Goodbird-git/PlayerEngine, 1.21.10-arch (2026-01) | MC 1.21.10 | Mojang | Mojang-named 1.21.x versions of the server mixins; decompiler output, reference only. |

Six analysts (calc core, movement, execution/behaviors, processes, server-port references, our integration seams)
plus a critic produced a per-file reuse matrix (journal: workflow `wf_cc199b6e-769`).

## 1. What we found

### 1.1 How much of Baritone we can reuse

Baritone 1.21.11 is about 40.8k LOC (26.3k main + 14.5k api).

| Verdict | Approx. LOC | What |
|---|---|---|
| Reuse as-is | ~28k | A* search, open set, path nodes, partial-path logic, segment splicing, all goals, all 8 movement types' cost model, PrecomputedData, PathExecutor, PathingControlManager, most processes, API types |
| Adapt (small edits) | ~5k | Settings (strip client toast/chat hooks), PathingBehavior (2 edits), CalculationContext / ToolSet / MovementDiagonal (LocalPlayer -> ServerPlayer), InventoryBehavior, MineProcess (1 cast) |
| Rewrite (thin glue) | ~1.6k replaced by ~0.9-1.3k new | Baritone/BaritoneProvider (per-bot registry), player context + controller, input handler, BlockStateInterface chunk source, tick driver, Helper logging |
| Skip | ~12k | chat commands (8.7k), elytra + native nether-pathfinder (3.2k), rendering/GUI, 20 client mixins, Litematica/Schematica bridges, on-disk chunk cache |

The client-bound surface is small: 38 files import `net.minecraft.client`, and there are only 22 non-import
`LocalPlayer` references. Baritone is already multi-instance internally (it even special-cases "not the primary =
a bot"). Its movement code "presses keys" through one map (`InputOverrideHandler`) and aims through one class
(`LookBehavior`), so rerouting keys to our bot's movement fields is exactly what Automatone did (~140 lines).

**No hard blocker was found.**

### 1.2 What Baritone brings that our navigator lacks

- **Goals are predicates** ("any cell within N of the player", `GoalNear`) instead of one exact cell. This removes
  the whole goal-snap bug class (lake beds, pits).
- **Costs are measured in game ticks** from real physics, and block breaking costs its real break time with the
  best tool plus a penalty. "Walk around first, break as a last resort" falls out of the cost model.
- **Best partial path when time runs out** (7 greediness coefficients), then plan the next segment while walking.
  This replaces our straight-line fallback.
- **Favouring the previous route** when replanning (anti flip-flop), which matters for a moving follow target.
- **Movements we do not have:** doors, fence gates and trapdoors (opened by right-click), ladders and vines,
  Depth Strider water speed, powder snow, frost walker, magma, falling-block handling, water-bucket fall
  (optional), parkour (optional, off by default).
- **Execution rechecks every tick:** off-path detection, cost increases, movement timeouts.
- **Path search off the server thread** with timeouts (our A* runs synchronously on the server thread and caused
  the 50 ms spikes seen in your logs).

### 1.3 What needs care (the real work)

1. **Client class-loading.** `Helper` (`Minecraft mc = Minecraft.getInstance()`), `Settings` (toast/chat
   lambdas), `BaritoneAPI`/`BaritoneProvider` and `BlockOptionalMeta`'s server-level stub reference client classes
   at class-load time. They must be compiled out, not just "not called". Verified by a dedicated-server boot.
2. **Tick order.** Our bot runs physics (`super.tick()`), then `playerTick()`, then `actionPack.onUpdate()`, so
   every input lands one tick late. Baritone must write inputs **before** `super.tick()`, with exactly one writer
   of the movement fields at a time (Baritone or our legacy executor, never both).
3. **Synchronous callers.** Many of our tasks ask for a path and get an answer in the same tick (try 4-5 candidate
   cells, compare the resolved goal). Baritone is asynchronous. Admission therefore runs Baritone's own A*
   inline with a small budget; the plan-ahead segments then run on a bounded worker pool.
4. **Contract-bound routes.** OreDig detours and hunting need a minimum-Y floor and a reversible walk-only return
   proof re-checked at every cell. Baritone has no equivalent. They keep the current navigator at first; later
   Baritone's A* can serve as the synchronous proof engine through a custom `CalculationContext`.
5. **Survival-legal actions.** Every break and place must still go through our `MiningController` and
   `BuildAction` (tool policy, logging, mining-assist hooks, edit ledger, cache invalidation). We keep Baritone's
   `BlockBreakHelper`/`BlockPlaceHelper` and implement their `IPlayerController` on top of our classes.
6. **Knowledge rules.** Route planning reads loaded terrain (like vanilla mobs and like our current A*). Baritone's
   **target scans** (MineProcess x-ray, GetToBlock, Farm, Builder full-volume reads, Explore's cache) are not used,
   or are re-fed from our observation layer. Fluid peeks next to blocks it may break keep our reactive checks.
7. **Water.** Baritone swims and bucket-falls; our safety net treats water as a crisis. Land routes avoid water at
   first; swimming stays with our swim-follow code until the two are reconciled.
8. **Chunk access and threading.** The A* worker reads server chunks through a small non-loading accessor mixin
   (full chunks, not only ticking ones); bounded daemon pool; `cancelRequested` made volatile; cancel on bot
   removal, respawn and dimension change; no per-tick `CalculationContext` allocations.
9. **Fake-player physics.** Baritone's jump, ascend, pillar and sneak-edge timings assume real player physics. Our
   repo has contradictory notes about stale `onGround` and gravity on the bot, so this is verified by probes
   before anything depends on it.
10. **Build setup.** Baritone uses Mojang names, we use Yarn. Plan: a `baritone-core` Loom subproject on Mojang
    mappings whose remapped jar our mod consumes and embeds (one deployed jar). This needs a 30-minute spike.
    Fallbacks: a separate nested build like the PvP BOT wrapper, or translating the kept sources to Yarn once.

## 2. Proposed design

- **Where it lives:** `baritone-core/` Gradle subproject (vendored Baritone 1.21.11 minus the skip list, original
  `baritone.*` packages for easy upstream diffs, mod id `baritone_core`). Consequence: the real Baritone client mod
  must not be installed in the same profile.
- **Per-bot instance:** a registry keyed by bot UUID; the player context resolves the current bot entity on every
  call (respawn-safe).
- **Server glue (~12-15 files, ~0.9-1.3k LOC):** server player context, `IPlayerController` over
  MiningController/BuildAction (+ a new `InteractAction.useBlock` for doors/gates/trapdoors), input bridge
  (movement keys -> the bot's movement fields before physics, sneak 0.3, sprint via the path executor),
  LookBehavior kept (constant sensitivity, no random looking), BlockStateInterface over a non-loading chunk
  accessor, server tick driver, bounded executor, logging to our per-bot logs.
- **Navigator switch inside ActionPack:** `nav.engine = legacy | baritone`, chosen per request by route type:

| Route type (callers) | Engine |
|---|---|
| Ordinary walks (follow, move, build/farm/container approaches, combat approach, ...) | Baritone |
| Surface-only walks (pickup recovery, escapes, depot/work-face approaches) | Baritone with breaking/placing/parkour off |
| Straight-line fallbacks (`startWalkTo`) | Replaced by Baritone partial paths |
| Contract routes (OreDig detours, hunting) | Legacy first; later Baritone A* as a synchronous proof engine |
| Dig approaches to solid goals (ore faces) | Legacy first; later Baritone behind our break whitelist |
| One-cell safety moves, start-cell repair | Unchanged |

  About 40 of the ~52 calling files need no edits if the ActionPack method signatures and result vocabulary stay.
- **Follow** pushes `GoalNear(player, 3)`; no stand-off cell, no goal snapping.
- **Policy per request** goes through `CalculationContext` subclasses (never by mutating the global Settings, which
  bots share): surface-only, break allowed as last resort with a high penalty, never break utility or player-built
  blocks (doors, chests, beds, glass, crafting tables, furnaces, ...), place only our throwaway blocks above the
  protected reserve, max fall = our configured safe fall (3), parkour off, water avoided on land routes.

## 3. Proposed phases (each ends with evidence you can check)

| Phase | Content | Exit evidence |
|---|---|---|
| P0 Spike (~half a day) | Cross-mapping Loom build; compile Baritone core with client refs stripped; dedicated-server boot; physics probes of the bot under raw inputs (walk, sprint, 1-block jump, 3-block fall, sneak at an edge, ladder, door, water entry) | Build works, server boots with no client classes, probe GameTests pass or give the list of physics fixes needed. **Go / no-go decision with you.** |
| P1 Core port | Vendor + strip + server glue; per-bot instances; unit tests for the pure core | Unit tests green; a bot walks a Baritone path in a GameTest |
| P2 Integration behind the switch | Navigator seam in ActionPack (default still legacy), follow and move on Baritone goals, doors/gates/ladders, tick-order fix for Baritone-driven bots | Existing GameTests unchanged with legacy; new tests pass with `nav.engine=baritone` |
| P3 Navigation obstacle course | GameTests: walls needing detours, 2-high steps, pits and old staircases, lake crossings, lava moats, cactus, cliffs, a house with a door (in and out), fence gates, ladders, tree canopy, moving target, two bots. Metrics: reached, time, damage, blocks broken/placed. Legacy vs Baritone side by side | Baritone at least as good as legacy on every course, then switch the default |
| P4 Extend | Dig approaches and contract routes on Baritone's A*; retire legacy code it covers | Mining/hunting GameTest suites green on Baritone |
| P5 Optional | BuilderProcess behind BuildTask, FarmProcess for more crops, targeted MineProcess fed by observed ores, ExploreProcess with a per-bot seen-chunk set | Per-feature GameTests |

## 4. Decisions needed from you

1. **Route knowledge:** plan routes over all loaded terrain (like vanilla mobs and our current navigator), with
   targets still limited to what the bot can see. (Recommended.)
2. **Breaking policy:** last resort only (high penalty), natural blocks plus anything not on a "never break" list of
   utility/player-built blocks. (Recommended.)
3. **Parkour and water-bucket falls:** off at first. (Recommended.)
4. **Water:** land routes avoid water; our swim-follow keeps handling swimming for now. (Recommended.)
5. **Packages:** keep `baritone.*` (easy upstream syncs; you must not also install the real Baritone mod in this
   profile) or relocate the packages (safe coexistence, harder syncs).
6. **P0 downloads:** the spike downloads Mojang's official mappings through Gradle (normal Loom behaviour).
7. **In-flight fixes:** the current follow/swim bug-fix jobs (r10c, r11a) continue, because the legacy navigator
   stays as the fallback and for mining/hunting routes. (Recommended.)
