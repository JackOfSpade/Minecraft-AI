# Navigation engine switch (`nav.engine`)

Status: built (P1 of `docs/NAVIGATION_BARITONE_PLAN.md`). The default is `legacy`; nothing changes for a player until the Baritone
engine has been shown to be at least as good on the obstacle courses (P2), which is also when the default is flipped.

## Config

`config/minecraftai.json`, section `nav`:

```json
{
  "nav": {
    "engine": "legacy"
  }
}
```

| Value | Meaning |
|---|---|
| `legacy` (default, also for a missing/unknown value) | the mod's own pathfinder (`AStarPathfinder` + `PathExecutor`); no Baritone class is loaded |
| `baritone` | ordinary walk requests are executed by the vendored Baritone (`CustomGoalProcess`), see the routing table |

The value is global. A per-bot override exists as a Java hook only (`NavEngineSelector.setBotEngine(uuid, engine)`); it is what the
GameTests use to run one bot on Baritone without changing the others, and there is no command or config key for it yet.
The template written for a new install contains `"engine": "legacy"`.

## What goes where (engine = baritone)

| Request (ActionPack API) | Engine |
|---|---|
| `startPathTo(goal[, reserve])` (ordinary walks) | Baritone. Breaking is allowed as a last resort (Baritone's own cost model prefers walking around), placing only when the caller could pillar and keeps no stone reserve |
| `startSurfacePathTo(goal)` (no dig, no pillar) | Baritone with breaking and placing off |
| Follow, land: `startApproachTo(target, radius, refresh, allowBreak)` | Baritone `GoalNear(target, radius)` (3 for follow), re-targeted as the player moves; no stand-off cell, no goal snapping |
| `startSwimRouteTo(goal)` | Baritone, may cross water (leased against `NavSafetyNet`) |
| `startSurfacePathTo(goal, minimumY[, returnAnchor])`, `startSurfaceDigFallbackPathTo` (contract routes: OreDig detours, hunting) | legacy |
| `startDigPathTo` (dig approaches to ore faces) | legacy |
| `startWalkTo` (straight-line walks), one-cell safety moves, start-cell repair | legacy |
| Follow a swimming player, follow a boat | legacy (swim-follow, boat follow) |

Result vocabulary is the legacy one, so the ~40 callers need no change: a request answers `IN_PROGRESS`,
`failed("pathfinding_failed: GOAL_UNREACHABLE")` or `failed("pathfinding_throttled")` immediately; how a route ended is
`ActionPack.lastRouteOutcome()` (`SUCCESS`, `FAILED`, `TIMEOUT`, `CANCELLED` with a reason) and the idle checks
(`isPathExecutorIdle()`, `hasActiveActions()`, `activePathGoal()`) work as before.

Admission is synchronous: Baritone's A* runs once inline on the server thread with a small budget (40/100 ms) and answers "can it
get there at all". No path, or an exact-cell request whose search ran out of places to look before its budget (the cell cannot be
reached over the loaded terrain), is `GOAL_UNREACHABLE`. A partial path (far goal, cut-off search) is accepted; approach requests
(follow) also accept the partial path to the closest reachable point, which is how a follower walks up to its bank when the player is
across water. A search that used its whole budget for nothing proves nothing (cold start, busy server) and lets the route start.
The first request of a session runs one warm-up search so class loading does not eat the admission budget.

## Movement capabilities (`nav.baritone`, only with `engine = baritone`)

```json
{
  "nav": {
    "engine": "baritone",
    "baritone": {
      "parkour": true,
      "parkourAscend": true,
      "parkourPlace": true,
      "waterBucketFall": true,
      "maxBucketFall": 12,
      "vines": true,
      "mobAvoidance": true
    }
  }
}
```

Every switch is a move a player makes and stays inside the survival rules; a missing key keeps the default shown, and `false`
gives the behaviour the engine had before the switch existed. They are read at every plan request, so a reload applies to the next
route. Details, the rules and the tests are in `tools/baritone/README.md` ("Capabilities").

| Key | Effect |
|---|---|
| `parkour` | sprint jumps over gaps of 2 and 3 blocks; a gap of 4 or more is never planned |
| `parkourAscend` | (needs `parkour`) the same jump landing one block higher |
| `parkourPlace` | (needs `parkour`) a block placed in mid-jump to land on; only with throwaway blocks and permission to place |
| `waterBucketFall` | a fall above `maxSafeFall` is taken with a water bucket from the hotbar and the water is picked up again; never in the Nether or where water evaporates |
| `maxBucketFall` | the highest fall planned with the bucket (blocks), default 12 |
| `vines` | vines count as blocks the bot may stand on (climbing a vine column works without it) |
| `mobAvoidance` | routes stay 6 blocks away from hostile mobs the bot can *see* (a mob it cannot observe is ignored) |

The chunk cache of Baritone stays off whatever the config says (it would plan over terrain the bot never saw).

## Fail-soft and lazy bootstrap

* Nothing Baritone-related is initialised until a request with the Baritone engine needs it (`BaritoneRegistry.get` creates the
  first instance and raises `NavEngineSelector.baritoneLive()`). The per-tick driver hook (`AIPlayerEntity`), the lifecycle hooks
  (`RuntimeLifecycleCoordinator`) and every `ActionPack` entry check that flag first, so with the legacy engine no `baritone.*`
  class (nor the glue classes) is loaded by them. `BaritoneEngineWaterGameTests.legacyEngineLoadsNoBaritoneClasses` checks this on
  a real server.
* If Baritone fails to initialise or link (`LinkageError`, `ExceptionInInitializerError`, a mixin or remap problem in some modpack),
  the request is answered by the legacy navigator, the failure is logged once (`nav_baritone_unavailable`) and Baritone is not asked
  again for the session (`NavEngineSelector.effective()` is `legacy` from then on). Any other exception is logged
  (`nav_baritone_request_failed`) and only that request falls back.
* The mixins stay registered whatever the engine is. Those that add behaviour to vanilla classes unconditionally:
  `BaritoneItemStackMixin` (re-computes a hash in `ItemStack.setDamageValue`), `BaritoneLootContextBuilderMixin` (redirects
  `MinecraftServer.reloadableRegistries()` in `LootContext.Builder.create`, same value for a real server) and
  `BaritonePalettedContainerMixin` (a static initialiser that scans `PalettedContainer` for its `Data` field and throws
  `IllegalStateException` if there is not exactly one; a modpack that changes that class would fail at start-up even with the
  legacy engine). `ServerChunkCacheBaritoneMixin`, `BaritoneLootTableMixin` and `BaritonePalettedContainerDataMixin` only add
  interface methods, `ChunkMapVisibleChunksAccessorMixin` is an accessor. All of them make vanilla classes implement a Baritone API
  interface, which loads that interface at start-up (interfaces only, no static state).

## One writer

Exactly one of Baritone and the legacy executor writes a bot's movement inputs. A Baritone route starts by dropping the legacy
executor state (`yieldToBaritone`); any legacy order (`claim(..)`, `stopAll`, `stopNavigation`) cancels the Baritone route first
(recorded as `CANCELLED`) and releases the inputs it wrote. `BaritoneExecutionContractTest` and `NavEngineLazyBootstrapContractTest`
pin the order.

## Water

* Dry routes stay dry. `Blocks.WATER` is in Baritone's `blocksToAvoid` while at least one dry route is active and no swim route is
  (Baritone's cost model has no per-request switch for it; `Settings` is one object per JVM). A dry route that still ends up in the
  water is abandoned (`route_entered_water`). A request from a bot that stands in water is a swim route (the way out is the first
  part of it). Limit: while a swim route of one bot runs, other bots' dry plans may see water too; their `route_entered_water`
  rule still fails them closed. The proper fix is a per-player patch like 0014 (P2).
* `NavSafetyNet` lease: while Baritone drives a bot along a swim-permitted route, `BaritoneDriver` renews a 6-tick lease every driven
  tick (and `BaritoneNavigator.start` grants it with the route); `NavSafetyNet.tickBot` returns before its water crisis machine
  while the lease is valid, exactly like the follow swim lease, and the lease is void as soon as the air is at the surfacing
  threshold. It ends with the drive (arrival, cancel, hand-over, bot removal). The spike measured a 1.43-block displacement of a
  submerged Baritone-driven bot by the rescue; `BaritoneEngineWaterGameTests` shows the lease (a submerged swimmer crosses a channel
  with swimming-speed steps and no rescue) and the control (without it the safety net takes the bot over).

## No correction teleports (R5)

A bot never moves itself to fix its position, in any profile and on either engine. Where the old code teleported the bot onto the next
cell (a path-start snap, a stair, a landing, a retreat, a recentre, a pickup nudge) it now presses the keys a player would.

* **`WalkedStep`** (`action/WalkedStep.java`) is one in-flight, input-driven step: `FLAT`, `STEP_UP` (forward plus jump), `STEP_DOWN` and
  `DROP` (walk off the edge or fall into the cell below, gravity lands it), `SWIM`, `SNEAK_SHIFT` (sneak a little over an edge to see the
  side face of the support), `RECENTER` (walk back to a point inside the cell) and `PUSH_OUT` (the vanilla client's shove out of a
  block, at most 0.1 block per tick). It writes forward, jump and sneak, aims, and lets vanilla physics move the bot at the speed a
  player has: the pace enforcer applies the gait and the vanilla rules to its keys (`markControllerInput`, capped at WALK, SWIM at
  SPRINT). The first tick validates the step (adjacency for the kind, a dry hazard-free standable landing, no block or entity in the
  way; `WalkedStep.refusal` is the shared check) and every later tick re-proves the landing; a step that overruns its budget or leaves
  its course fails with a reason and the owner decides what to do next. A step holds no state outside itself: a pause, a restart or a
  hazard just `cancel`s it and the owner re-derives from `bot.blockPosition()`. `ActionPack.runStep`/`beginDescend`, `InCellWalk`
  (`nudgeToward`, `recenter`, `beginEdgeShift`) and `PathExecutor` (the route's pre-step) are the callers.
* **`FakePlayerMotion`** keeps only the read-only checks (`isBlockCollisionFree`, `landingOccupant`); its `stepTo`, `stepToStandable`,
  `swimStepTo`, `jumpTo`, `shiftToSupportEdge`, `returnToBlockCenter` and `nudgeWithinBlockToward` primitives, `ActionPack.descendInto`
  and `tryPhysicalSnap` are deleted. The path-start snap (`snapPlayerToNearestStandable`) plans a walked step or refuses; it never moves
  the bot.
* **`TeleportAudit`** (`entity/TeleportAudit.java`) classifies and counts every teleport of a bot (per bot, by the first mod frame of the
  stack): `LIFECYCLE` (`AIPlayerManager`: spawn, respawn after death), `USER` (`MinecraftAiServerNetworking`: the panel's recall and
  to-bot buttons, capability `MANUAL_TELEPORT`), `VANILLA` (portals, pearls), `PRIVILEGED` (the emergency rescues below), `TEST` (a GameTest
  fixture move, `BotFixtureMoves`) and `CORRECTION` (anything else). `TeleportAudit.corrections(bot)` is the number every "no micro-teleport"
  test asserts to be 0.
* **The allow-list.** `NoCorrectionTeleportSourceTest` scans the production source: an entity relocation (`teleportTo`, `teleport`,
  `moveTo`, `snapTo`, `setPos`, ...) may appear only in `AIPlayerEntity` (delegation to `super` after the audit), `AIPlayerManager`,
  `MinecraftAiServerNetworking` and the four operator-profile EMERGENCY rescues (`TeleportAudit.PRIVILEGED_METHODS`):
  `NavSafetyNet#escapeSuffocation`, `NavSafetyNet#emergencyTeleportToAir`, `DangerWatcher#escapeToSurface` and
  `GatherQuotaTask#trySurface`. Each decides `EMERGENCY_TELEPORT` (operator profile only, **denied in strict survival**) before it reads a
  single cell the bot may not see; in strict survival the same situations are left by walking, shoving, digging or swimming. The emergency
  teleports therefore remain in the operator profile and nowhere else. `PrivilegedBoundarySourceTest` pins the capability side.
* **Acceptance.** `NaturalMovementAcceptanceGameTests` runs a 1200-tick session (walk, sneak over a slab edge, sprint up a step, a
  two-block drop, a pond swim, a zombie hitting the owner, the walk back) with `TeleportAudit.corrections(bot) == 0` and no privileged
  teleport on every tick, no drowning or suffocation damage and the bot within four blocks of the owner at the end: strict survival, on
  both engines (legacy and Baritone). The operator profile is not run there (it is one JVM-wide config value); it is covered by the
  `NoCorrectionTeleportSourceTest` source lock and by `ActionPackSuppressedSnapGameTests`.

## Telemetry (per-bot logs, PATH category)

* `nav_engine` on every ActionPack path/walk request: `engine` (used), `configured`, `kind` (`path_to`, `approach`, `swim_route`,
  `dig_path_to`, `walk_to`), `goal`, `why` (`started`, `regoal`, `rejected: ...`, `throttled`, `contract_route`, `dig_approach`,
  `straight_line_walk`, `engine_legacy`, `baritone_unavailable`).
* `baritone_admission` (search result, nodes, moves, ms, policy), `baritone_warm_up`, `baritone_path_event` (Baritone's calculation and
  segment events), `baritone_takeover` / `baritone_released` / `baritone_preempted` (hand-over), `baritone_landing` (fall check).
* The route outcome uses the legacy event names: `path_complete`, `path_failed`, plus `path_timeout` and `path_cancelled`.

## Tests

* Unit: `MinecraftAiConfigNavEngineTest`, `NavEngineSelectorTest` (selection, per-bot override, simulated bootstrap failure),
  `NavRouteRulesTest` (request to permissions, state to result vocabulary), `BaritoneNavigatorMappingTest`,
  `NavEngineLazyBootstrapContractTest` (gates, routing table, single writer, lease wiring).
* GameTests (real server, per-bot engine override): `BaritoneEngineFollowGameTests` (wall detour, step, pit, door, moving target,
  arrival within 3 blocks, cancel, bot removal, sealed moat stays dry, dry bridge), `BaritoneEngineMoveGameTests` (the same for
  move-to, `stopAll`, removal, unreachable goal answer, dry bridge, config switch), `BaritoneEngineWaterGameTests` (swim lease and
  control, fallback when Baritone is unavailable, the legacy engine loads no Baritone class).
* No correction teleports: unit `NoCorrectionTeleportSourceTest`, `PrivilegedBoundarySourceTest`, `TeleportAuditClassifierTest`; GameTests
  `NaturalMovementAcceptanceGameTests` and the per-area `*NaturalMovementGameTests` / `NaturalSwimGameTests`.
* Obstacle courses (legacy vs Baritone on identical geometry, results table and verdicts): `NavigationCourseGameTests`, see
  `docs/NAVIGATION_COURSES.md`.
