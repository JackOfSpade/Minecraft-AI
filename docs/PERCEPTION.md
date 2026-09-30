# Realistic perception

Bots and inhabitants notice creatures the way a person would, so ambushes work: nobody sees you behind them, sneaking
or not, sneaking up is quiet, and **seeing takes a moment**. One pure model, identical in both mods:

```
read(observer O, subject S) -> SIGHT | HEARING | INVESTIGATE | NONE   and the exposure (ticks) that makes it a notice
```

* Wrapper (PvP BOT inhabitants noticing players and Minecraft-AI bots): `combat/Perception` and `combat/ExposureTracker`,
  used by `AggroController` (the "line of sight hunter").
* Minecraft-AI (bots noticing creatures): `perception/CreaturePerception` (job "pr"), with its own maximum radius.

Both run the golden vectors in [`perception/vectors.json`](perception/vectors.json) and must agree on every case.

There is **no block-distance rule**. The only distance limit is the *mod maximum* (`modMax`): for the wrapper 128 blocks,
which is vanilla `LivingEntity.hasLineOfSight`'s cap and PvP BOT's largest `maxTargetDistance`; for Minecraft-AI its
existing observation radius. Everything else is line of sight and time.

## Model

1. **Sighted this tick.** All of: same level; `distance <= modMax`; inside the view cone; the view unobstructed; not fully
   invisible.
   * *View cone.* theta is the 3D angle between O's look vector and the direction from O's eye to S's eye.
     `theta <= frontHalfAngleDeg` (60, a 120 degree cone): front. Up to `peripheralHalfAngleDeg` (100, a 200 degree field):
     peripheral. Beyond: behind, never sighted.
   * *Occlusion.* A ray from O's eye to S's eye; if blocked, a second ray to S's body centre (a head over a wall counts).
     Vanilla clip rules (collider blocks, fluids ignored: what vanilla mobs cannot see through). The rays run LAST, after
     the cheap distance and angle filters, and at most once.
2. **Reaction time.** A sighted subject is *noticed* only after it has stayed sighted for

   ```
   required = reactionTicks * cone * sneak / visibility  +  distanceTicksPer32 * distance / 32
   ```

   in ticks, where `reactionTicks` = 5 (0.25 s, a person's reaction), `cone` = 1 in front and `peripheralMultiplier` (2) in
   the periphery, `sneak` = `sneakMultiplier` (2) while S sneaks (else 1), `visibility` = vanilla `getVisibilityPercent` with
   its own sneak factor divided out (invisibility with armor cover, worn mob heads; so sneaking is not counted twice), and
   the distance term adds `distanceTicksPer32` (5) ticks per 32 blocks (0 disables it). The table, without the distance
   term: front 5, peripheral 10, sneaking in front 10, invisible never, behind never; with it, 40 blocks is 11.25 and 64
   blocks is 15. The distance term is a soft scaling, not a restriction.
3. **Exposure** is the number of ticks since S was first sighted in an unbroken run. It is per (observer, subject) and
   tolerates a single missed tick; when S is not sighted for 2 ticks in a row it starts again from zero
   (`ExposureTracker`). S is noticed when `exposure >= required`.
4. **Hearing.** S makes noise this tick; O HEARS S when `distance <= noise radius` and the occlusion rays are clear (no
   hearing through walls, so no x-ray). A heard subject counts as sighted in the FRONT cone (O turns to the sound):
   `required = reactionTicks + the distance term`. The noise radius is the maximum of: standing still or sneaking 0
   (silent), walking 4, sprinting 8, combat noise in the last 10 ticks 12 (swing, being hurt, bow/crossbow/trident,
   breaking or placing a block, eating or drinking; heard even from a sneaking player). Mobs: noisy hostiles 8, creeper 0
   (16 while swelling), warden 24, animals 4 while moving.
   **Hearing only ADDS awareness from behind. It never restricts sight:** a walking player 30 blocks away in front is out
   of earshot but plainly seen.
5. **Occluded sound.** Heard but with the line blocked is `INVESTIGATE`: a place to go and look, never a notice. The
   wrapper's search uses it to move its focus; Minecraft-AI may use it for the same.
6. **Perception off** (`enabled=false`) is exactly vanilla `hasLineOfSight`: within `modMax` and unobstructed is `SIGHT`
   at once (required = 0), with no cone, no sneaking, no invisibility and no reaction time.
7. **Awareness.** Once O has noticed S, or S has hit O, O is aware of S and follows it by plain occlusion (no cone, no sneak
   factor, no reaction time): an engaged combatant turns to follow you. In the wrapper the hunt itself is the awareness
   (`aggro.loseGraceTicks`, then the last-known-position pursuit and search, see the wrapper README).
8. **Config** (`perception` parameters of either mod): `enabled` (default true), `frontHalfAngleDeg` 60,
   `peripheralHalfAngleDeg` 100, `peripheralMultiplier` 2, `sneakMultiplier` 2, `reactionTicks` 5,
   `distanceTicksPer32` 5, `hearWalk` 4, `hearSprint` 8, `hearCombat` 12, `hearNoisyMob` 8, `hearPrimedCreeper` 16,
   `hearWarden` 24, `hearAnimal` 4, `combatNoiseTicks` 10. Bounds: angles 0..180 with front <= peripheral, multipliers
   1..20, radii >= 0, ticks >= 0.

## The wrapper's mapping

* `aggro.reactionTicks` and `aggro.distanceReactionTicksPer32` are the reaction terms; `aggro.perception` holds
  `enabled`, `frontHalfAngleDeg`, `peripheralHalfAngleDeg`, `peripheralMultiplier`, `sneakMultiplier`, `hearWalk`,
  `hearSprint`, `hearCombat` and `combatNoiseTicks`. `aggro.requireLineOfSight` stays the switch for occlusion.
* The wrapper notices players and Minecraft-AI companions (all `ServerPlayer`s), so the mob radii (`hearNoisyMob`,
  `hearPrimedCreeper`, `hearWarden`, `hearAnimal`) exist only for Minecraft-AI and take their defaults in the wrapper.
* A candidate is scanned every `aggro.scanIntervalTicks` (3) ticks, staggered across inhabitants, and every tick while
  its exposure is in progress; the cheap filters (level, distance, cone) run before any ray.
* A hit by a player makes the bot aware of the attacker immediately (no exposure needed); the reaction delay
  (`reactionTicks`) applies before it strikes back, see the wrapper README.
* The old range-based keys (`acquireRange`, `leashRange`, `loseSightTicks`, `peripheralFactor`, `sneakFactor`) are
  ignored with one INFO line when an old config still carries them.

## Scope

* IN: every place a bot NOTICES a creature (threat detection, target acquisition, aggro, aggressor checks,
  perception summaries given to the LLM, warden detection, projectile threat awareness).
* OUT: object perception (items, containers, crops, blocks, boats), non-hostile task targets a bot deliberately
  searches for (hunt, breed, milk, trade, villagers), strike legality (a physical ray check), and the owner's own
  camera cone (`SharedVision.ownerSees`, already realistic).
* Light level and darkness are not modelled (vanilla mobs ignore them too); a possible later option.

## Vectors

`perception/vectors.json` (version 2) lists the params and cases (`angleDeg`, `distance`, `subject`, `occlusionClear`,
optional `modMax` and `paramsOverride`, `expectSense`, `expectTicks`). `expectTicks` is the exposure in ticks after which the
subject is noticed, or `"never"`. Wrapper tests read `../docs/perception/vectors.json`, the root tests read
`docs/perception/vectors.json`. Extend the file only with results both implementations reproduce.
