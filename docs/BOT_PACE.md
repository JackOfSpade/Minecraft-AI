# Bot pace: walk, sprint and sneak

One policy decides how a bot moves on the ground while a controller drives it (a legacy path or walk, or a Baritone route). It is
the same for both navigation engines and it is vanilla-legal: a bot never does what a player could not.

Code: `action/Gait`, `PaceOwner`, `PaceRules`, `PacePolicy`, `QuietZone`, the enforcers in `ActionPack.onUpdate` (legacy) and
`baritone/BotInputBridge.apply` (Baritone).

## The gaits

| Gait   | Speed (blocks/s) | Input scale | Notes |
|--------|------------------|-------------|-------|
| SNEAK  | 1.3              | 0.3         | Silent (a sneaking player makes no step, landing or swim vibration). Never walks off an edge. |
| WALK   | 4.3              | 1.0         | Walking is *not* silent: only sneaking silences steps. |
| SPRINT | 5.6              | 1.0         | Needs the vanilla conditions below. |

`Gait.clockWeight()` says how much of a tick a tick at that gait counts against a route's clocks: 1 for SPRINT, 1/1.3 for WALK,
1/4.4 for SNEAK. A route deadline moves out by the part of the tick a slow gait does not count (`ActionPack.notePaceTick`), and the
legacy walker's time limit and progress limits scale with the pace (`paceClockWeight`, `lastInputScale`), so a bot that
deliberately sneaks or holds a shield up is not timed out or declared stuck for it.

**Deadline credit is capped.** A sneaking tick would move a route's deadline out by 0.77 ticks, so without a limit a long SNEAK lease
(a SILENT quiet zone, a warden) never met its deadline however stuck the bot was. `DeadlineCredit` caps the total credit of a route at
one original deadline budget (600 + 20 ticks per block): at most twice the ordinary time, then the deadline is a deadline again.

**The route lease survives the hand-over to Baritone.** `BaritoneDriver` calls `ActionPack.yieldToBaritone()` on the first tick it drives
a new route, i.e. after the caller has requested the lease. `yieldToBaritone` therefore drops the legacy executor without ending the
lease (`dropPathExecutor`); only `clearActivePathExecutor` (a new path/walk/mining, `stopNavigation`, `stopAll`) also ends it.
`PaceBaritoneGameTests.sneakRouteLeaseHoldsAfterBaritoneTakesOver` / `walkRouteLeaseHoldsAfterBaritoneTakesOver` pin it: measured on the
version that lost the lease, a SNEAK lease sprinted 37 of 57 ticks and a WALK lease 87 of 106; with the fix 0 sprint ticks and 212 of 213
moving ticks sneaking.

## Course timing with pace on

The 38 navigation courses (`docs/NAVIGATION_COURSES.md`, "With the pace policy") take about 4 ticks longer on Baritone (WALK for the last
4.5 blocks: 21 ticks instead of 16, once per leg: `house` has two legs, +12) and about the same on the legacy engine. One legacy course
got much slower and it is not a pace defect: `twobots` 70 -> 99 ticks. Two bots that start side by side and follow the same player
through a one-block gap now sprint identically (SPRINT the whole way, no pressure, WALK only in the last 4.2 blocks; traced tick by
tick) and reach the gap at the same tick, so they push each other off it for about 45 ticks until the walker's sidle recovery frees
them. Before pace, the legacy walker's own sub-target sprint rule (`nav.sprintMinDist`) happened to put the two bots out of step, so
one passed the gap before the other arrived (a 12 tick brush against the wall for the first, none for the second). The Baritone
`twobots` is 53 -> 57 (the walk-in only). Nothing lifts or fails to lift a WALK cap here.

## What decides the gait (in this order)

`PacePolicy.resolve(bot, goalDistance, travelling)`. The first rule that applies decides; ceilings apply afterwards.

1. **A warden hunts the bot or its owner** (observed: synced anger level, roaring pose, or a recorded hit): SPRINT.
2. **Aggro pressure** while travelling (`PacePolicy.setPressureProbe`, wired to AggroSense by the aggro job): SPRINT, unless a
   WARDEN lease decides the pace.
3. **A lease**: the highest-priority valid one (WARDEN 90 > EVADE 60 > TASK 40 > FOLLOW 30). A lease is its owner's decision and skips
   the downgrade dwell (the owner has its own hysteresis).
   * `ActionPack.requestPace(gait, owner)`: a TICK lease, valid for 6 game ticks, so it survives the TaskManager's 1-in-5 throttle.
   * `ActionPack.requestRoutePace(gait, owner)`: a ROUTE lease. Ended only by `stopAll`, `stopNavigation`, the start of a new
     path/walk/route and the settling of the route; **not** by `stopMovement` (the walkers call that at every sub-target). Request
     it after starting the route.
4. **The task's own flags**: `setSneaking(true)` = SNEAK, `setSprinting(true)` = SPRINT.
5. **Route pace** from the horizontal distance to the goal: SPRINT from `behaviour.pace.routeSprintDistance` (8) on, WALK from
   `routeWalkDistance` (4.5) down, in between the previous route gait. A downgrade needs 10 ticks at the current route gait (an
   upgrade is immediate), so a bot at 5 to 7 blocks never flaps. A trip starts at the gait its first distance calls for (no history
   from an earlier trip, and no goal known = SPRINT, as Baritone always did).
6. **Quiet zone** (rules 4 and 5 only): SILENT = SNEAK, CAUTION = at most WALK.

Then the ceilings:

* **A calm observed warden within 16 blocks** caps everything except a WARDEN lease to WALK (user decision: the "always sprint under
  aggro" rule must not wake a warden). The cap is lifted while a warden hunts the bot or the bot is taking damage (an entity hit in
  the last 20 ticks).
* **`capPace(max, reason)`**, valid this tick and the next: the legacy `PathExecutor` caps to WALK on JUMP_UP, DROP_DOWN, PILLAR_UP,
  BRIDGE and DIG_THROUGH nodes. It beats pressure and a hunting warden (a jump made at a sprint overshoots a drop).

`behaviour.pace.enabled=false` switches the policy off: the legacy `WalkToController` keeps its old sub-target sprint rule, the
Baritone bridge sprints whenever Baritone asks (with the old vanilla checks), nothing else changes.

## Vanilla rules (applied once, by the enforcers)

`PaceRules`:

* No sprint at food 6 or lower (unless the player may fly), while sneaking, while using an item, while blind, without forward input
  (> 0.5), and none on a hard horizontal collision (`horizontalCollision && !minorHorizontalCollision`): a wall ends the sprint, a
  brush against a corner does not.
* Input scale: 0.3 while sneaking, times 0.2 while using an item (`behaviour.pace.itemUseSlowdown`). A raised shield, a drawn bow or
  eating slows the walk to about 0.9 blocks/s.
* Movement costs food: at the end of a bot's tick `ServerPlayer.checkMovementStatistics` is called with the tick's displacement
  (not on a tick a teleport moved the bot, not while it is a passenger; `behaviour.pace.movementExhaustion`). It charges 0.1
  exhaustion per sprinted metre (0.01 per metre swum) and awards the movement statistics, exactly as a move packet does for a
  player. Jump exhaustion is vanilla's own.

## Controller-driven movement vs raw keys

* **Controller-driven**: a legacy `PathExecutor`, a legacy `walkTo`, a Baritone route, or a raw-input driver that calls
  `ActionPack.markControllerInput()` every tick it drives. The enforcer applies the gait and the vanilla rules to its keys.
* **Raw keys**: anything else that writes `setForward`/`setStrafing` itself (the walked combat steps: peeks, ducks, the running strafe,
  creeper steps) keeps its own sprint flag and its own item-use scaling, untouched: no leases, no pressure, no double slowdown.

The legacy enforcer runs after the controllers tick: `sneak = task sneak || (gait == SNEAK && !edge)`,
`sprint = gait == SPRINT && sprintAllowed && walker.geometryAllowsSprint()`, input scaled by `inputScale`. `WalkToController` no
longer writes the sprint flag itself; it only says whether the ground ahead allows one (`geometryAllowsSprint`: no jump or blocked
step, two clear cells ahead).

## Exceptions

* **Descents and climbables lift a sneak.** A sneaking player does not walk off an edge and does not climb down a ladder or a vine,
  so a SNEAK gait is lifted on a drop or descend node (`PathExecutor.onEdgeDescentNode`), on Baritone's Descend/Fall/Downward
  movements, and whenever the bot is on a climbable. A sneak the task asked for itself (`setSneaking(true)`) is respected.
* **Parkour sprints whatever the gait.** Baritone plans parkour with sprint, and a jump from a walk falls into the gap: when the
  current movement or one of the next two is a `MovementParkour`, Baritone's sprint request is honoured (only the vanilla rules can
  veto it) and the sneak is lifted. Near a calm warden this makes noise; the alternative (a per-bot allow-sprint patch, 0018) is
  deferred.
* **Sneaking, walking and sprinting cannot be sneaked past a claim.** `setSprinting(true)` and `setSneaking(true)` only record the flag
  while a Baritone route drives the bot; they no longer take the bot over and cancel the route the caller has just started (the
  evade task sets sprint right after starting its route).

## Quiet zones and wardens

`QuietZone` is a per-bot cache: wardens every 5 ticks, the biome and the sculk scan every 20. Only observed facts are used.

| Level   | When | Effect |
|---------|------|--------|
| NONE    | otherwise | none |
| CAUTION | own-feet biome `minecraft:deep_dark`, or the DARKNESS effect | at most WALK |
| SILENT  | CAUTION and an observed sculk sensor, calibrated sensor or shrieker within 8 blocks (at most 5000 block reads per refresh), or an observed calm warden within 20 | SNEAK |

`behaviour.pace.quietZoneCaution=false` makes the level always NONE (the warden queries keep answering).

## Switches

`behaviour.pace.enabled`, `itemUseSlowdown`, `movementExhaustion`, `routeSprintDistance` (8), `routeWalkDistance` (4.5),
`quietZoneCaution`. See `docs/OPERATING_PROFILES.md`.

## Following a player (R4)

`FollowTask` decides its gait with `FollowPace` (pure, unit-tested) and leases it every tick as a FOLLOW tick lease
(`ActionPack.requestPace(gait, PaceOwner.FOLLOW)`), in every land mode (legacy path or walk, Baritone route, holding at arrival). It reads
what an observer can see of the player: the gap, the horizontal speed over the last ten game ticks, the synced sprint flag, how long the
player has been sneaking (consecutive ticks), plus the quiet zone, wardens and aggro pressure. Rules, the first that applies decides:

1. A hunting warden, or aggro pressure (hostiles after the bot, its owner, the followed player or a Minecraft-AI bot): SPRINT. A calm
   warden within 16 blocks that is not hunting caps it to WALK.
2. Gap at most 4.5: SNEAK if the player has sneaked for 4 ticks, else WALK.
3. The player sneaks: SNEAK up to a gap of 8, then WALK, SPRINT only from 14 blocks and outside quiet zones.
4. The player sprints (flag, or 5.0 blocks per second): SPRINT.
5. SILENT zone: SNEAK up to 8, else WALK. CAUTION: SPRINT only from 16.
6. The player walks (between 1.5 and 5.0 blocks per second): WALK unless the gap is at least `follow.sprintGap` (10).
7. Otherwise SPRINT from `follow.sprintGap`, WALK up to `follow.walkGap` (6), in between the gait it had (never a sneak).

An upgrade is immediate, a downgrade needs 10 ticks at the current gait (a lease skips the pace policy's own dwell). Measured before this
change (old code, follow of a moving mock player): a sprinting player left the follower 15.3 blocks behind, a sneaking player was followed at
a walk, an aggro'd zombie did not make the follower run, and a calm warden 14 blocks away made it evade.

**The escort (`FollowEscort`).** A follower never goes off to fight. What is in its melee reach it knocks away, on ready ticks only, while
it keeps following: candidates are what `CombatCore.hostileTo` calls hostile (a MARKED foreign bot, never a SUSPECT one) within 4.5 blocks that
the bot's own eyes see and melee is allowed against (no creeper, no warden). A swing is made only when the cooldown is full, no item is in use
and the reach is legal (`strikeIfReady` turns the bot toward its target first, and on the legacy engine that steers the next tick, so it is not
called on other ticks). The weapon is picked when a candidate is near, at most every 40 ticks. Next to a calm warden (16 blocks) it is silent
(no swing, no weapon swap) unless the bot was hurt in the last 40 ticks. `DangerWatcher` does not pause a follow for an ordinary hostile
(`behaviour.follow.escortOnly`): it still hands over for low health, a creeper (CreeperDefense), a warden that is hunting or within 8 blocks,
a hostile that could kill the bot in two hits, lava, drowning and falling. Regroup is skipped while following. A `FollowTask` with a hostile
within 6 blocks is TaskManager-critical (it ticks even under a degraded TPS).

**Retreat.** The user decision is that evade and retreat legs use the same pace policy and the same escort rule. The pace policy already covers them (aggro pressure sprints, the EVADE lease); a swing at what is in reach while evading is not part of `EvadeTask` yet (it was outside this change).

## Eating at the sprint limit (R6)

A player cannot sprint at 6 food points or fewer, so a bot eats when its food is at 7 or lower (`DangerWatcher.SPRINT_LIMIT_FOOD`), whatever
`survival.hungerEatThreshold` is, and it does not wait for its walk to end (a follower on a long route would otherwise reach 6 first). It does
not eat in the middle of a fight (a hostile in view, or hurt this tick) or while an Evade/Combat task runs, and it defers next to a calm
warden (20 blocks) unless its health is 6 or lower; it eats as soon as they allow. The normal food rules apply (`FoodPolicy`: no reserve food,
no poison, harmful food last), and a protected transaction (a mining break, craft, smelt, container) still finishes first below the critical
level. Tests: `AutoEatSprintLimitGameTests`.

## Tests

Unit: `PaceRulesTest`, `PacePolicyTest`, `DeadlineCreditTest` (also pins that `yieldToBaritone` keeps the route lease). GameTests: `PaceGameTests` (legacy engine) and `PaceBaritoneGameTests` (Baritone); for following: unit `FollowPaceTest`, `FollowEscortSourceContractTest`, GameTests `FollowPaceGameTests` (legacy) and `FollowPaceBaritoneGameTests` (Baritone) with shared scenarios in `FollowPaceScenarios`, and `FollowEscortGameTests`.
