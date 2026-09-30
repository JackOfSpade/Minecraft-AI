# Realistic perception

Bots and inhabitants notice creatures the way a person would, so ambushes work: nobody sees you behind them, sneaking
or not, sneaking up is quiet, and **seeing takes a moment**. One pure model, identical in both mods:

```
read(observer O, subject S, heard?) -> SIGHT | HEARING | NONE   and the exposure (seconds) that makes it a notice
```

* Wrapper (PvP BOT inhabitants noticing players and Minecraft-AI bots): `combat/Perception` and `combat/ExposureTracker`,
  used by `AggroController` (the "line of sight hunter").
* Minecraft-AI (bots noticing creatures): `perception/CreaturePerception` (job "pr").

Both run the golden vectors in [`perception/vectors.json`](perception/vectors.json) and must agree on every case.

**Sight has no block limit inside the view cone.** The only hard-coded distance rule is the wrapper's *engage limit* of
64 blocks: a bot never ENGAGES (acquires, chases, pursues) a target it sees and measures farther away than that. It is a
constant of the hunter, not a parameter of the model, and it does not change what is seen: the reaction formula below
simply carries on past 64 blocks. Minecraft-AI applies its own radius on top.

## Model

1. **Sighted this tick.** All of: same level; inside the view field; the view unobstructed; not fully invisible.
   * *View field.* theta is the 3D angle between O's look vector and the direction from O's eye to S's eye.
     `theta <= fullAttentionHalfAngleDeg` (30) is full attention; the field reaches out to `peripheralHalfAngleDeg` (100,
     a 200 degree field); beyond that S is behind, never sighted.
   * *Occlusion.* A ray from O's eye to S's eye; if blocked, a second ray to S's body centre (a head over a wall counts).
     Vanilla clip rules (collider blocks, fluids ignored: what vanilla mobs cannot see through). The rays run LAST, after
     the cheap angle filter, and at most once.
2. **Reaction time**, ONE continuous formula in real seconds (doubles, no steps, no rounding):

   ```
   requiredSeconds = (base + (at64 - base) * distance / 64) * angleFactor(theta) * sneakFactor / visibility
   ```

   * `base` = `reactionBaseSeconds` 0.5 and `at64` = `reactionAt64Seconds` 2.0: 0.5 s up close, 2.0 s at 64 blocks, linear in
     between: every distance has its own number (10.3 blocks is 0.7414 s, 40 blocks 1.4375 s). Distance is eye to eye.
   * `angleFactor`: 1 up to `fullAttentionHalfAngleDeg` (30), rising **linearly** to `peripheralMultiplier` (2) at
     `peripheralHalfAngleDeg` (100); not sighted beyond. (65 degrees is 1.5, 80 degrees 1.714.)
   * `sneakFactor` = `sneakMultiplier` (2) while S sneaks, else 1. (A reaction to a HIT ignores it: pain tells the victim where
     the attacker is.)
   * `visibility` = vanilla `getVisibilityPercent` with its own sneak factor divided out (invisibility with armor cover,
     worn mob heads; so sneaking is not counted twice); 0 means never.
3. **Exposure** accumulates in real seconds, `0.05` per tick, continuously while sighted. It is per (observer, subject) and
   tolerates a single missed tick; when S is not sighted for 2 ticks in a row it starts again from zero
   (`ExposureTracker`). The NOTICE happens on the first tick whose exposure reaches `requiredSeconds` (20 TPS is the only
   granularity): a run that starts on tick 0 notices on tick `ceil(requiredSeconds / 0.05)`. Sighting needs no cone once a
   sound has turned the observer to S (see hearing), but it always needs the clear line.
4. **Hearing is an input, computed by vanilla.** Hearing is not part of the pure model: the caller runs the vanilla
   vibration system (the same `VibrationSystem` the sculk sensor and the Warden use) and says whether a sound was heard at
   S's position. Vanilla decides everything about the sound: the listener radius (`aggro.hearing.listenerRadius`, 16 = the
   Warden's; the sculk sensor's is 8), which events are vibrations (`GameEventTags.VIBRATIONS`), that a **sneaking**
   ("stepping carefully") entity's steps make none, that wool blocks vibrations in between (`DAMPENS_VIBRATIONS`), that
   spectators are silent, and how long a vibration takes to arrive. A sound heard at S's position with a clear line to S
   counts as sighted with angle factor 1 (the observer turned to the sound; the formula, sneaking and visibility still
   apply). **Hearing only ADDS awareness from behind. It never restricts sight.**
5. **Unplaced sound.** A sound with no valid subject in clear view near it is only an INVESTIGATE hint: a place to turn and
   look (idle) or to search (pursuing, searching). Vibrations pass through ordinary walls, so a sound behind a wall is a hint,
   never a notice. NO MAGIC: the hint is the position of the sound only (not who made it).
6. **Perception off** (`enabled=false`) is exactly vanilla `hasLineOfSight`: a clear line is `SIGHT` at once (required = 0),
   with no cone, no sneaking, no invisibility and no reaction time.
7. **Awareness and confirmation.** In the wrapper the hunt keeps a CONFIRMED flag: an engagement is confirmed only after the
   target has been continuously visible for the full reaction time. It ends on the FIRST unseen tick and EVERY re-sighting
   restarts the exposure from zero (no instant resume after a blink behind a tree, corner or pillar). PvP BOT gets the
   target, a loaded crossbow fires and a melee blow lands on a player only while confirmed. See the wrapper README.
8. **Config** (`perception` parameters of either mod): `enabled` (default true), `reactionBaseSeconds` 0.5,
   `reactionAt64Seconds` 2.0, `fullAttentionHalfAngleDeg` 30, `peripheralHalfAngleDeg` 100, `peripheralMultiplier` 2,
   `sneakMultiplier` 2. Bounds: angles 0..180 with full <= peripheral, multipliers 1..20, seconds >= 0 with at64 >= base.

## The wrapper's mapping

* `aggro.perception` holds `enabled`, `reactionBaseSeconds`, `reactionAt64Seconds`, `fullAttentionHalfAngleDeg`,
  `peripheralHalfAngleDeg`, `peripheralMultiplier` and `sneakMultiplier`; `aggro.hearing.listenerRadius` is the vibration
  radius. `aggro.requireLineOfSight` stays the switch for occlusion.
* The wrapper notices players and Minecraft-AI companions (all `ServerPlayer`s); mobs are not noticed by it.
* A candidate is scanned every `aggro.scanIntervalTicks` (3) ticks, staggered across inhabitants, and every tick while its
  exposure is in progress or a sound holds the bot's attention; candidates beyond the 64 block engage limit are never
  scanned (no rays).
* A hit tells the victim where a blow came from, not who struck it: a melee hit by an adjacent player it sees starts a
  reaction (required seconds at that distance, angle factor 1, sneaking ignored); a projectile from an unseen shooter gives
  only the incoming direction (the reverse of the projectile's velocity at impact), which the bot turns to look along, and
  the point found by tracing back along that line (first blocking block, or 64 blocks) is where it pursues.
* The old range-based and tick-based keys (`acquireRange`, `leashRange`, `loseSightTicks`, `peripheralFactor`, `sneakFactor`,
  `reactionTicks`, `distanceReactionTicksPer32`, `frontHalfAngleDeg`, `hearWalk`, `hearSprint`, `hearCombat`,
  `combatNoiseTicks`) are ignored with one INFO line when an old config still carries them.

## Scope

* IN: every place a bot NOTICES a creature (threat detection, target acquisition, aggro, aggressor checks,
  perception summaries given to the LLM, warden detection, projectile threat awareness).
* OUT: object perception (items, containers, crops, blocks, boats), non-hostile task targets a bot deliberately
  searches for (hunt, breed, milk, trade, villagers), strike legality (a physical ray check), and the owner's own
  camera cone (`SharedVision.ownerSees`, already realistic).
* Light level and darkness are not modelled (vanilla mobs ignore them too); a possible later option.

## Vectors

`perception/vectors.json` (version 3) lists the params, the cases (`angleDeg`, `distance`, `subject`, `heardNear`,
`occlusionClear`, optional `paramsOverride`, `expectSense`, `expectSeconds`, `expectNoticeTick`) and exposure `runs`
(a per-tick sighted string, the required seconds and the index of the first tick that notices). `expectSeconds` is
`requiredSeconds` as a double (compared with a tolerance), or `"never"`. Wrapper tests read `../docs/perception/vectors.json`,
the root tests read `docs/perception/vectors.json`. Extend the file only with results both implementations reproduce.
