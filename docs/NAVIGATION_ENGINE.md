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
* Obstacle courses (legacy vs Baritone on identical geometry, results table and verdicts): `NavigationCourseGameTests`, see
  `docs/NAVIGATION_COURSES.md`.
