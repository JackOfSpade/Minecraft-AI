# Realistic perception

Bots and inhabitants notice creatures the way a person would, so ambushes work: nobody sees you behind them, sneaking
or not, sneaking up is quiet, and **seeing takes a moment**. One pure model, identical in both mods:

```
read(observer O, subject S, heard?) -> SIGHT | HEARING | NONE   and the exposure (seconds) that makes it a notice
```

* Wrapper (PvP BOT inhabitants noticing players and Minecraft-AI bots): `combat/Perception` and `combat/ExposureTracker`,
  used by `AggroController` (the "line of sight hunter").
* Minecraft-AI (companion bots noticing creatures): `perception/CreaturePerception` (the same pure function),
  `perception/ExposureTracker`, `perception/BotEars` (vanilla vibrations) and `perception/CreatureSenses` (the level adapter);
  see "Minecraft-AI's mapping" below.

Both run the golden vectors in [`perception/vectors.json`](perception/vectors.json) and must agree on every case
(Minecraft-AI: `CreaturePerceptionTest`; wrapper: `PerceptionTest`).

**Sight has no block limit inside the view cone.** The only hard-coded distance rule is the wrapper's *engage limit* of
64 blocks: a bot never ENGAGES (acquires, chases, pursues) a target it sees and measures farther away than that. It is a
constant of the hunter, not a parameter of the model, and it does not change what is seen: the reaction formula below
simply carries on past 64 blocks. Minecraft-AI applies its own radius on top.

## Model

1. **Sighted this tick.** All of: same level; inside the view field; the view unobstructed; not fully invisible.
   * *View field.* theta is the 3D angle between O's look vector and the direction from O's eye to S's eye. (In the wrapper O's look vector is where the inhabitant REALLY looks: its head turns at a human speed limit, see the wrapper's "Human aim", so a bot that is still turning cannot see behind itself.)
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
   vibration system and says whether a sound was heard at
   S's position. Vanilla decides everything about the sound: the listener radius (`aggro.hearing.listenerRadius`, 16 = the
   configured radius), which events are vibrations (`GameEventTags.VIBRATIONS`), that a **sneaking**
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

## Minecraft-AI's mapping

Companion bots are `AIPlayerEntity`s (real `ServerPlayer`s), so the same rules apply to what THEY notice: creatures (mobs,
players, other bots), never objects. Sight has no engage limit here; the farthest a companion sees is its profile observation
radius (`perception.radius`, 16 by default; the configured observation radius), because the 64 block engage limit
is a PvP BOT rule. The 64 in the formula is only its slope.

* **State, not rays, at the call sites.** `CreatureSenses.tickBot` runs once per bot per server tick (`BotTickCoordinator`,
  before anything else): it reads every creature around the bot (the cheap distance and angle filters first, the eye ray and
  then the body-centre ray last), counts the continuous exposure per creature (`ExposureTracker`: one missed tick tolerated,
  every re-sighting after a gap starts again from zero) and keeps the set of creatures the bot has NOTICED.
  `ObservableWorldQuery.canNoticeCreature(bot, creature)` is then a lookup.
* **Awareness.** A creature that has been noticed stays noticed while plain occlusion is clear (no cone, no reaction time: an
  engaged bot faces what it fights). One tick with no line is tolerated; after that it is forgotten, and the next sighting
  is a new reaction. A creature that leaves the observation radius, dies or despawns is forgotten at once.
* **Hearing** is vanilla's vibration system, used per bot (`BotEars`: `VibrationSystem.Data`/`User`/`Listener`, a
  `DynamicGameEventListener`, and a silent parity ticker every tick; radius `behaviour.perception.hearing.listenerRadius`, 16 =
  the configured radius; vanilla decides sneaking, wool and travel time). The parity ticker retains vanilla selection,
  travel, delivery and data-change semantics but omits only the client-visible traveling vibration-particle packet. A sound
  whose source is a creature in clear view within 4 blocks
  is where it came from: the bot is "turned to it" for 30 ticks (sight without the cone, the reaction time still applies).
  A sound with nobody in view is an INVESTIGATE hint (`CreatureSenses.hint`; an idle bot, one with no task and no action in
   progress, turns to look; a bot busy with a step, a dig or a route keeps its head on its work). A bot does not hear its own steps
   and blows, nor an item it dropped itself. NO MAGIC: the listener retains only the emitted event's delivered source block and kind;
   for a shot it also retains vanilla's original vibration travel time from the currently delivered `VibrationInfo`, never its source
   entity or projectile owner. Listeners are removed on despawn, death, level change, when perception is switched off and when the
   server stops (`CreatureSenses.listenerCount()` is the test seam).
* **Blows.** A melee blow makes its (adjacent) striker known at once (`RecentDamage` attribution). A projectile from an unseen
  shooter gives only the direction it came from (the reverse of its velocity at impact, traced back to the first block): a
  hint, never the shooter.
* **Projectiles.** A projectile in flight is sensed when it is itself in view (inside the view field, clear line). Heard-shot awareness
  is deliberately narrower: only an exact vanilla arrow, spectral arrow, trident, or llama spit can additionally be matched when its
  observable ballistic motion back-projects to the delivered `PROJECTILE_SHOOT` source block and its age agrees with that vibration's
  original travel time; it must still have a clear line to the projectile. Fireballs, wither skulls, shulker bullets, rockets, wind
  charges, and modded subclasses are never associated from hearing alone, because their unseen future course or target is not knowledge
  the bot has. Acting on a sensed projectile follows the same reaction rule as a creature (`docs/SHIELD_USE.md`): a projectile from a
  shooter the bot is tracking was anticipated and is answered at once; any other is a first sighting and waits the reaction time of the
  formula (angle factor 1 when only its ordinary ballistic shot was heard), so a shot from a shooter nobody noticed normally just hits,
  and a shot from behind that nobody heard or saw always does.
* **No hidden-scan decision.** `HIDDEN_BLOCK_SCAN` is retired in every profile. Existing configuration values are parsed only for
  migration and every runtime request is denied and logged, so projectile awareness uses only sight/cone/line-of-sight or the
  vibration facts above. `scanFailedRecently` is the separate one-tick fail-open
  safety net for an actual perception-scan exception: it uses the old line-of-sight answer so a broken scan does not blind the bot; it
  neither grants a hidden scan nor turns a heard event into knowledge of a hidden shooter or future trajectory.
* **The owner's sight** still nominates: `SharedVision.seenByBotOrOwner` is "the bot noticed it, or its owner sees it" (foreign
  bots only), and `HostileBotIntent` only samples the intent of a foreign bot that someone on the protected side has noticed.
* **Config** `behaviour.perception`: `enabled` (default true; false = today's omnidirectional line of sight exactly, no listener),
  `reactionBaseSeconds`, `reactionAt64Seconds`, `fullAttentionHalfAngleDeg`, `peripheralHalfAngleDeg`, `peripheralMultiplier`,
  `sneakMultiplier` and `hearing.listenerRadius` (see OPERATING_PROFILES.md).
* **Call sites.** `PerceptionCallSiteClassificationTest` lists every caller of `canNoticeCreature`, `canObserveEntity` and
  `hasLineOfSight` with its class: creature noticing (DangerWatcher threat scans, AggroSense, CombatCore target acquisition,
  CombatTask, CreeperDefenseTask, EmergencyShelterTask, EvadeTask, FollowEscort, ProjectileThreat, QuietZone, SharedVision, ShieldGuard,
  HostileBotIntent, PerceptionCollector, DiagnosticLogger, Baritone mob avoidance), objects and deliberate searches (kept
  omnidirectional: drops, boats, prey, the discovery task's deliberate sheep survey, breeding, milking, trading, the landmark evidence of the mining assist) and physical strike legality (kept).
  The `attack_entity` command only considers creatures the bot has noticed (animals and villagers stay omnidirectional) and is refused with `busy` while another task runs (never replaces it, and a refused call does not turn the bot's head: a busy bot
  only strikes what is already under its crosshair).
* **Cost.** The scan is throttled (`CreatureSenses`): passive creatures (a mob that is neither an `Enemy` nor a `NeutralMob` and
  hunts nothing: animals, villagers, fish) are not scanned at all and answer the plain omnidirectional test on demand (the scope
  rule: animals and villagers keep omnidirectional observation); every other creature is read every second tick (alternating by
  entity) unless it already has a run of exposure or a sound at it, so the reaction time keeps its tick granularity and a creature
  entering the view is seen at most one tick later; one clear-view answer per creature per scan. Passive creatures are not read, but
  they stay candidates for explaining a sound, so a cow's steps are the cow's and never an unexplained hint. A noticed creature's line
  is verified every second tick while it is clear and every tick once a check fails, so a line lost on the unverified tick ends the
  awareness at most one tick (50 ms, one scan cadence) later than an every-tick check would. The scan time is recorded per bot
  as the `perception_scan` section of `BotProfiler`.
  *Measurement* (`companion_perception_game_tests_the_scan_cost_with_five_bots_in_a_crowd_is_throttled`, prints `PERCEPTION_COST`):
  five idle bots among 66 mobs (30 calm zombified piglins, 20 of them in front, 24 cows, 12 villagers), the SAME scene measured over
  100 ticks with the cost limits off, then 100 ticks with them on; the figure is the sum over the five bots of the mean
  `perception_scan` time per tick. Three runs on the 4-CPU cloud machine shared with another test server (2026-09-30): unthrottled
  0.663 / 1.500 / 1.407 ms/tick, throttled 0.242 / 0.614 / 0.255 ms/tick (median 1.41 -> 0.26 ms/tick); rays cast 234 -> 60 per tick in
  every run (the deterministic part: wall times vary with the machine's load). A scan that throws never leaves the bot blind: for that tick (and the next) every
  creature question is answered by the old omnidirectional test, and the failure is logged once per bot.
* **Peeking takes the reaction time.** A creature out of sight for more than a tick is forgotten and is noticed again only after the
  reaction time of looking at it, so a bot that peeks (round its cover column, through the observation port of its shelter) must
  look for at least that long before it decides from what it sees: `CreatureSenses.noticeDwellTicks(distance)` (the shared
  formula straight ahead plus the scan cadence, zero with perception off). The cover peek of `CombatTask` stays exposed that long
  (at least `PEEKABOO_EXPOSE_TICKS`), and `EmergencyShelterTask` keeps the foot wall closed that long after it has opened the head
  port, so the hostile outside is noticed before the door opens.
* **GameTests** run the WHOLE suite with perception ON, as in production. A fixture that needs a bot to know a hostile faces the bot
  toward it (or lets it make noise, or strike it) and waits the reaction time of the shared formula, computed from the real distance
  and angle plus the scan cadence (`PerceptionFixtures`, never an arbitrary budget). `MINECRAFTAI_HARNESS_PERCEPTION=off` runs the
  suite with the old omnidirectional test, for diagnosis only.

## Shared block sight and navigation

Terrain knowledge is shared between a Minecraft-AI bot and its linked owner. A block/cell becomes
route knowledge only after a vanilla eye ray from either observer reaches it. The observer's
effective block range is the lower of its requested client render distance and the server view
distance, converted from chunks with `chunks × 16`; its endpoint chunk must also be both tracked
for that observer and loaded by the server. This is deliberately not a loaded-chunk snapshot.

`SharedWorldSight` retains the ray-proven cells (including outline-only blocks such as rails,
vines, torches, and crops) for 6,000 ticks. A route request adds an exact ray to its requested
target plus the ray-proven feet/headroom/floor lane needed by the navigator. Baritone receives
only that bounded memory in `ObservedNavigationFence`, so it can path through terrain either
observer actually saw without discovering hidden terrain. Mutable targets are re-checked before
an interaction; owner sight gives navigation knowledge, never remote break/place authority.

Every successful live block or cell observation refreshes that same memory, including a linked
player's observation. Resource selection checks remembered matching blocks before its smaller
local survey cube, so a player-looking-at resource can nominate it even when it is farther than
the bot's interaction-scale search range. That remembered block is still re-proven by a current
bot-or-owner eye ray before its state is reread or it becomes a route target; stale memory is a
lead, never a blind-path permission.

## See-through sight

A bot's eyes pass through what a player looks straight past: leaves, fences and fence gates, glass, panes and bars, ice, slime
and honey, chains, ladders, scaffolding, crops, and every plant, torch, rail, web or vine nothing collides with, and water
(waterlogged or not). Lava never is, and neither are walls, doors, trapdoors, slabs, stairs, chests, beds, signs, buttons or any
solid cube. `mode/SeeThrough` decides per block from vanilla's own classes, tags and collision shapes (so a modded block falls
into a rule instead of needing a list entry) and `SeeThroughGoldenTest` pins the verdict of all 1166 vanilla blocks (248
see-through): a block a later Minecraft adds fails the test until somebody decides on purpose.

`mode/SightClip` is `level.clip` with those cells skipped. `SightClipContext` is a vanilla `ClipContext` whose two per-cell hooks
answer an empty shape for what is skipped, so vanilla's own traversal and nearest-hit rule do the rest and a ray that crosses
nothing see-through is exactly vanilla's. The cell being observed (the target) is never skipped, an eye inside a see-through cell
sees out of it, and lava keeps its shape even for a ray that asked for `Fluid.NONE`.

**Sight is not reach.** Seeing a block behind a leaf does not let a hand reach it: every actuator (break, place, open, interact,
strike, bucket) keeps its own vanilla `ClipContext` and refuses what a real pick ray would not reach. The context therefore also
reports `obstructions()`: the see-through blocks a vanilla pick ray along the same segment would hit, nearest the eye first. A pick
ray is an `OUTLINE` ray with `Fluid.NONE` (`Entity.pick`), so that is the shape the list is modelled with whatever shape the sight
ray used: water is never an obstruction, a block with no collision but an outline (grass, a torch, an open gate) is, and a ray
that slips past a lone fence post or a pane's post is not. `crossed()` lists every skipped cell with the state it really holds, so
a recorder never stores a leaf, a fence or water as air.

## Scope

* IN: every place a bot NOTICES a creature (threat detection, target acquisition, aggro, aggressor checks,
  perception summaries given to the LLM, projectile threat awareness).
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
