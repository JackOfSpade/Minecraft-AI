# Realistic perception

Bots and inhabitants notice creatures the way a person would, so ambushes work: nobody sees you behind them, sneaking
or not, and sneaking up is quiet. One pure decision, identical in both mods:

```
notice(observer O, subject S) -> SIGHT | HEARING | NONE
```

* Wrapper (PvP BOT inhabitants noticing players and Minecraft-AI bots): `combat/Perception`, used by `AggroController`.
* Minecraft-AI (bots noticing creatures): `perception/CreaturePerception` (job "pr").

Both run the golden vectors in [`perception/vectors.json`](perception/vectors.json) and must agree on every case.

## Model

1. **Occlusion.** A ray from O's eye to S's eye; if blocked, a second ray to S's body centre (a head over a wall
   counts). Vanilla clip rules (collider blocks, fluids ignored). The rays run LAST, after the cheap range and angle
   filters, and at most once.
2. **View cone.** theta is the 3D angle between O's look vector and the direction from O's eye to S's eye.
   `theta <= frontHalfAngleDeg` (60, a 120 degree cone): full sight range. Up to `peripheralHalfAngleDeg` (100, a
   200 degree field): `peripheralFactor` (0.5) of the range. Beyond: not seen.
3. **Subject visibility** multiplies the sight range: sneaking `sneakFactor` (0.5); invisibility and disguise use
   vanilla `getVisibilityPercent` with its own sneak factor divided out, so sneaking is not counted twice.
   `SIGHT = baseRange * cone * sneak * visibility >= distance` and a clear view.
4. **Hearing.** S makes noise this tick; O notices by HEARING when `distance <= min(noise, baseRange)` and the
   occlusion rays are clear (no hearing through walls, so no x-ray). Noise is the maximum of:
   sneaking movement 0 (silent), standing still 0, walking 4, sprinting 8, combat noise in the last 10 ticks 12
   (swing, being hurt, bow/crossbow/trident, breaking or placing a block, eating or drinking). Mobs: noisy hostiles 8,
   creeper 0 (16 while swelling), warden 24, animals 4 while moving.
5. **Awareness.** Once O has noticed S, or S has hit O, O is aware of S for `awarenessTicks` (200); while aware,
   "can see" uses plain occlusion (no cone, no sneak factor): an engaged combatant turns to follow you.
   In the wrapper the engagement itself is the awareness (`aggro.loseSightTicks`).
6. **Config** (`perception` block per mod): `enabled` (default true; false = today's omnidirectional line of sight),
   `frontHalfAngleDeg`, `peripheralHalfAngleDeg`, `peripheralFactor`, `sneakFactor`, `hearWalk`, `hearSprint`,
   `hearCombat`, `hearNoisyMob`, `hearPrimedCreeper`, `hearWarden`, `hearAnimal`, `combatNoiseTicks`,
   `awarenessTicks`. Bounds: angles 0..180 with front <= peripheral, factors 0..1, radii >= 0. In the wrapper the block
   is `aggro.perception` (the base sight range is `aggro.acquireRange`, occlusion stays `aggro.requireLineOfSight`).

## Scope

* IN: every place a bot NOTICES a creature (threat detection, target acquisition, aggro, aggressor checks,
  perception summaries given to the LLM, warden detection, projectile threat awareness).
* OUT: object perception (items, containers, crops, blocks, boats), non-hostile task targets a bot deliberately
  searches for (hunt, breed, milk, trade, villagers), strike legality (a physical ray check), and the owner's own
  camera cone (`SharedVision.ownerSees`, already realistic).
* Light level and darkness are not modelled (vanilla mobs ignore them too); a possible later option.

## Vectors

`perception/vectors.json` lists the params and cases (`baseRange`, `angleDeg`, `distance`, `subject`,
`occlusionClear`, optional `paramsOverride`, `expect`). Wrapper tests read `../docs/perception/vectors.json`, the root
tests read `docs/perception/vectors.json`. Extend the file only with results both implementations reproduce.
