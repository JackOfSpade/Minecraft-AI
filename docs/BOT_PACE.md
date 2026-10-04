# Bot pace: walk, sprint and sneak

One policy decides how a bot moves on the ground while a controller drives it (a Baritone route or a bounded local physical action).
It is vanilla-legal: a bot never does what a player could not.

Code: `action/Gait`, `PaceOwner`, `PaceRules`, `PacePolicy`, `QuietZone`, the local-action enforcer in `ActionPack.onUpdate`, and
`baritone/BotInputBridge.apply` for Baritone input.

## The gaits

| Gait   | Speed (blocks/s) | Input scale | Notes |
|--------|------------------|-------------|-------|
| SNEAK  | 1.3              | 0.3         | Silent (a sneaking player makes no step, landing or swim vibration). Never walks off an edge. |
| WALK   | 4.3              | 1.0         | Walking is *not* silent: only sneaking silences steps. |
| SPRINT | 5.6              | 1.0         | Needs the vanilla conditions below. |

`Gait.clockWeight()` says how much of a tick a tick at that gait counts against a route's clocks: 1 for SPRINT, 1/1.3 for WALK,
1/4.4 for SNEAK. A route deadline moves out by the part of the tick a slow gait does not count (`ActionPack.notePaceTick`), and the
local walk controller's time limit and progress limits scale with the pace (`paceClockWeight`, `lastInputScale`), so a bot that
deliberately sneaks or holds a shield up is not timed out or declared stuck for it.

**Deadline credit is capped.** A sneaking tick would move a route's deadline out by 0.77 ticks, so without a limit a long SNEAK lease
(for example, a task-owned sneak route) never met its deadline however stuck the bot was. `DeadlineCredit` caps the total credit of a route at
one original deadline budget (600 + 20 ticks per block): at most twice the ordinary time, then the deadline is a deadline again.

**The route lease survives hand-over to Baritone.** `BaritoneDriver` calls `ActionPack.yieldToBaritone()` on the first tick it drives
a new route, after the caller has requested the lease. `yieldToBaritone` cancels only competing local inputs (a guarded step, mining,
or a local walk) and deliberately preserves the route lease; route settlement, a new route request, `stopNavigation`, or `stopAll` ends it.
`PaceBaritoneGameTests.sneakRouteLeaseHoldsAfterBaritoneTakesOver` / `walkRouteLeaseHoldsAfterBaritoneTakesOver` pin it: measured on the
version that lost the lease, a SNEAK lease sprinted 37 of 57 ticks and a WALK lease 87 of 106; with the fix 0 sprint ticks and 212 of 213
moving ticks sneaking.

## Course timing with pace on

The historical paired-engine timing tables are retired with the old navigator. The current Baritone-only course suite verifies the
outcomes that matter for pace: legal movement, dry/safe traversal, no unnecessary breaking, and bounded following behavior. Its
unpaced GameTest timings are regression diagnostics rather than a player-facing speed promise.

## What decides the gait (in this order)

`PacePolicy.resolve(bot, goalDistance, travelling)`. The first rule that applies decides; ceilings apply afterwards.

1. **Aggro pressure** while travelling (`PacePolicy.setPressureProbe`, wired to AggroSense by the aggro job): SPRINT.
2. **A lease**: the highest-priority valid one (EVADE 60 > TASK 40 > FOLLOW 30). A lease is its owner's decision and skips
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

* **`capPace(max, reason)`**, valid this tick and the next: a bounded walked step caps to WALK where a sprint would make a jump,
  descent, bridge, or dig-through unsafe. It beats pressure (a jump made at a sprint overshoots a drop).

`behaviour.pace.enabled=false` switches the policy off: the local `WalkToController` keeps its sub-target sprint rule, and the
Baritone bridge sprints whenever Baritone asks (with the vanilla checks); nothing else changes.

## Vanilla rules (applied once, by the enforcers)

`PaceRules`:

* No sprint at food 6 or lower (unless the player may fly), while sneaking, while using an item, while blind, without forward input
  (> 0.5), and none on a hard horizontal collision (`horizontalCollision && !minorHorizontalCollision`): a wall ends the sprint, a
  brush against a corner does not.
* Input scale: 0.3 while sneaking, times 0.2 while using an item (no switch: vanilla applies it unconditionally). A raised shield, a drawn bow or
  eating slows the walk to about 0.9 blocks/s.
* Movement costs food: at the end of a bot's tick `ServerPlayer.checkMovementStatistics` is called with the tick's displacement
  (not on a tick a teleport moved the bot, not while it is a passenger; no switch: the cost is vanilla and unconditional). It charges 0.1
  exhaustion per sprinted metre (0.01 per metre swum) and awards the movement statistics, exactly as a move packet does for a
  player. Jump exhaustion is vanilla's own.

## Controller-driven movement vs raw keys

* **Controller-driven**: a local `walkTo`, a guarded walked step, a Baritone route, or a raw-input driver that calls
  `ActionPack.markControllerInput()` every tick it drives. The enforcer applies the gait and the vanilla rules to its keys.
* **Raw keys**: anything else that writes `setForward`/`setStrafing` itself (the walked combat steps: peeks, ducks, the running strafe,
  creeper steps) keeps its own sprint flag and its own item-use scaling, untouched: no leases, no pressure, no double slowdown.

The `ActionPack` local-action enforcer runs after its controllers tick: `sneak = task sneak || (gait == SNEAK && !edge)`,
`sprint = gait == SPRINT && sprintAllowed && walker.geometryAllowsSprint()`, input scaled by `inputScale`. `WalkToController` no
longer writes the sprint flag itself; it only says whether the ground ahead allows one (`geometryAllowsSprint`: no jump or blocked
step, two clear cells ahead).

## Exceptions

* **Descents and climbables lift a sneak.** A sneaking player does not walk off an edge and does not climb down a ladder or a vine,
  so a SNEAK gait is lifted on a guarded local descent, on Baritone's Descend/Fall/Downward
  movements, and whenever the bot is on a climbable. A sneak the task asked for itself (`setSneaking(true)`) is respected.
* **Parkour sprints whatever the gait.** Baritone plans parkour with sprint, and a jump from a walk falls into the gap: when the
  current movement or one of the next two is a `MovementParkour`, Baritone's sprint request is honoured (only the vanilla rules can
  veto it) and the sneak is lifted.
* **Sneaking, walking and sprinting cannot be sneaked past a claim.** `setSprinting(true)` and `setSneaking(true)` only record the flag
  while a Baritone route drives the bot; they no longer take the bot over and cancel the route the caller has just started. (Evade
  asks for its gait with a lease right after starting its route, see below.)

## Walked steps and teleports (R5)

No pace rule is bypassed by a teleport: the old correction teleports (path-start snap, stair and landing steps, retreat, recentre, pickup
nudge) are gone in every profile, so every metre a bot covers is covered by its keys at the gait the pace layer allows. A `WalkedStep` (the
input-driven replacement, see `docs/NAVIGATION_ENGINE.md`) is controller-driven: it calls `markControllerInput()` every tick, is capped at
WALK (a swim step at SPRINT), and gets the vanilla rules (no sprint at food 6 or lower, sneak scaling, item-use slowdown) from the
enforcer like any route, so a bot that recentres, hops a ledge or leaves a block moves at the speed a player has, and a sneak it holds
(the shift over a support edge) is its own. A drop or descend step lifts a sneak for the walk off the edge, as above.

`TeleportAudit` counts what is left: `LIFECYCLE` (spawn and respawn), `USER` (the panel's recall and to-bot buttons, `MANUAL_TELEPORT`),
`PRIVILEGED` (the operator-profile emergency rescues: suffocation, drowning, dark-trap and gather surfacing; denied in strict survival) and
`CORRECTION`, which must stay at 0. `NoCorrectionTeleportSourceTest` locks the allow-list in the source, and
`NaturalMovementAcceptanceGameTests` runs a mixed 1200-tick follow session (walk, sneak, sprint, step-up, drop, pond, zombie) with zero
corrections in strict survival on both engines (the operator profile is covered by the source lock above and by
`ActionPackSuppressedSnapGameTests`). A teleport also never charges movement food cost (see the vanilla rules above), which is
one more reason a bot must not use one to cover ground.

## Switches

`behaviour.pace.enabled`, `routeSprintDistance` (8), `routeWalkDistance` (4.5). See
`docs/OPERATING_PROFILES.md`.

## Following a player (R4)

`FollowTask` decides its gait with `FollowPace` (pure, unit-tested) and leases it every tick as a FOLLOW tick lease
(`ActionPack.requestPace(gait, PaceOwner.FOLLOW)`), in every land mode (Baritone route, bounded local action, or holding at arrival). It reads
what an observer can see of the player: the gap, the horizontal speed over the last ten game ticks, the synced sprint flag, how long the
player has been sneaking (consecutive ticks), plus aggro pressure. Rules, the first that applies decides:

1. Aggro pressure (hostiles after the bot, its owner, the followed player or a Minecraft-AI bot): SPRINT.
2. Gap at most 4.5: SNEAK if the player has sneaked for 4 ticks, else WALK.
3. The player sneaks: SNEAK up to a gap of 8, then WALK, SPRINT only from 14 blocks and outside quiet zones.
4. The player sprints (flag, or 5.0 blocks per second): SPRINT.
5. SILENT zone: SNEAK up to 8, else WALK. CAUTION: SPRINT only from 16.
6. The player walks (between 1.5 and 5.0 blocks per second): WALK unless the gap is at least `follow.sprintGap` (10).
7. Otherwise SPRINT from `follow.sprintGap`, WALK up to `follow.walkGap` (6), in between the gait it had (never a sneak).

An upgrade is immediate, a downgrade needs 10 ticks at the current gait (a lease skips the pace policy's own dwell). Measured before this
change (old code, follow of a moving mock player): a sprinting player left the follower 15.3 blocks behind, a sneaking player was followed at
a walk, an aggro'd zombie did not make the follower run.

**The escort (`FollowEscort`).** A follower never goes off to fight. What is in its melee reach it knocks away, on ready ticks only, while
it keeps following: candidates are what `CombatCore.hostileTo` calls hostile (a MARKED foreign bot, never a SUSPECT one) within 4.5 blocks that
the bot's own eyes see and melee is allowed against (no creeper). A swing is made only when the cooldown is full, no item is in use
and the reach is legal (`strikeIfReady` turns the bot toward its target first, and that turn affects the next input tick, so it is not
called on other ticks). The weapon is picked when a candidate is near, at most every 40 ticks. `DangerWatcher` does not pause a follow for an ordinary hostile
(`behaviour.follow.escortOnly`): it still hands over for low health, a creeper (CreeperDefense),
a hostile that could kill the bot in two hits, lava, drowning and falling. Regroup is skipped while following. A `FollowTask` with a hostile
within 6 blocks is TaskManager-critical (it ticks even under a degraded TPS).

## Eating at the sprint limit (R6)

A player cannot sprint at 6 food points or fewer, so a bot eats when its food is at 7 or lower (`DangerWatcher.SPRINT_LIMIT_FOOD`), whatever
`survival.hungerEatThreshold` is, and it does not wait for its walk to end (a follower on a long route would otherwise reach 6 first). It does
not eat in the middle of a fight (a hostile in view, or hurt this tick) or while an Evade/Combat task runs. The normal food rules apply (`FoodPolicy`: no reserve food,
no poison, harmful food last), and a protected transaction (a mining break, craft, smelt, container) still finishes first below the critical
level. Tests: `AutoEatSprintLimitGameTests`.

## Tests

Unit: `PaceRulesTest`, `PacePolicyTest`, `DeadlineCreditTest` (also pins that `yieldToBaritone` keeps the route lease). GameTests: `PaceGameTests` and `PaceBaritoneGameTests`; for following: unit `FollowPaceTest`, `FollowEscortSourceContractTest`, GameTests `FollowPaceGameTests`, `FollowPaceBaritoneGameTests`, shared scenarios in `FollowPaceScenarios`, and `FollowEscortGameTests`.
