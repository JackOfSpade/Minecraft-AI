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
     Vanilla clip rules (collider blocks, fluids ignored: what vanilla mobs cannot see through); a Minecraft-AI companion's
     eyes also pass through leaves, fences, glass and water, and only its eyes (see "Creatures: noticing is sight, a fight is
     physical" below). The model takes the answer as an input (`occlusionClear`), so the vectors are the same either way.
     The rays run LAST, after the cheap angle filter, and at most once.
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
6. **Perception off** (`enabled=false`) is exactly vanilla `hasLineOfSight` (for a Minecraft-AI companion with its see-through
   eyes): a clear line is `SIGHT` at once (required = 0), with no cone, no sneaking, no invisibility and no reaction time.
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
* **Awareness.** A creature that has been noticed stays noticed while occlusion is clear (no cone, no reaction time: an
  engaged bot faces what it fights; the eyes see through foliage, fences, glass and water). One tick with no line is tolerated;
  after that it is forgotten, and the next sighting is a new reaction. A creature that leaves the observation radius, dies or
  despawns is forgotten at once.
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
  shooter gives only the direction it came from (the reverse of its velocity at impact, traced back to the first block with the
  plain vanilla ray: a projectile never crossed a leaf or a fence): a hint, never the shooter.
* **Projectiles.** A projectile in flight is sensed when it is itself in view (inside the view field, clear line; one loosed from
  behind foliage or glass is in view like any other; the arrow itself cannot cross the leaf, so a shield raised for it is an early,
  harmless flinch). Heard-shot awareness
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
  The owner's eyes see through foliage, fences, glass and water like the bot's (`SharedVision.ownerSees`); the bookkeeping of a
  fight (`CombatCore.hasLineOfSightOrOwnerSees`: the lost-line timer, whether a threat is worth a task) reads the owner's plain
  collider line instead (`ownerSeesStrict`), so an owner looking through a window cannot keep the bot in a fight it can never strike in.
* **Config** `behaviour.perception`: `enabled` (default true; false = today's omnidirectional line of sight exactly, no listener),
  `reactionBaseSeconds`, `reactionAt64Seconds`, `fullAttentionHalfAngleDeg`, `peripheralHalfAngleDeg`, `peripheralMultiplier`,
  `sneakMultiplier` and `hearing.listenerRadius` (see OPERATING_PROFILES.md).
* **Call sites.** `PerceptionCallSiteClassificationTest` lists every caller of `canNoticeCreature`, `canObserveEntity` and
  `hasLineOfSight` with its class: creature noticing (DangerWatcher threat scans, AggroSense, CombatCore target acquisition,
  CombatTask, CreeperDefenseTask, EmergencyShelterTask, EvadeTask, FollowEscort, ProjectileThreat, QuietZone, SharedVision, ShieldGuard,
  HostileBotIntent, PerceptionCollector, DiagnosticLogger, Baritone mob avoidance, AmbientConversationCoordinator: a canned ambient line needs an addressee
  the speaker has noticed), objects and deliberate searches (kept
  omnidirectional: drops, boats, prey, the discovery task's deliberate sheep survey, breeding, milking, trading, the landmark evidence of the mining assist) and physical strike legality (kept).
  Where a decision needs both ("noticed" and "can be hit"), the pair is written out and pinned by `SightVersusReachSourceContractTest`
  (see "Creatures: noticing is sight, a fight is physical").
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
  every run (the deterministic part: wall times vary with the machine's load). With the eyes that see through foliage and glass
  (`SightClip` rays, 2026-10-06, one run): unthrottled 0.529 ms/tick, throttled 0.205 ms/tick, rays cast 234 -> 60 per tick as before
  (the scene has nothing see-through in it, which is the point: a ray that crosses nothing costs what a vanilla one does). A scan that throws never leaves the bot blind: for that tick (and the next) every
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
route knowledge only after an eye ray from either observer reaches it (the eyes see through
foliage, fences, glass and water, see below). The observer's
effective block range is the lower of its requested client render distance and the server view
distance, converted from chunks with `chunks × 16`; its endpoint chunk must also be both tracked
for that observer and loaded by the server. This is deliberately not a loaded-chunk snapshot. A bot has no client whose
movement packets re-centre its tracking, so `AIPlayerEntity` does it itself: at the start of every tick and straight after
every teleport (including the one that places a new bot), which is what lets a bot see the chunk it was just put in during
the tick it was put there.

`SharedWorldSight` retains the ray-proven cells (including outline-only blocks such as rails,
vines, torches, and crops) for 6,000 ticks. A cell a ray passed through is stored as what it is:
a leaf, a fence, glass or water keeps its state and is never remembered as free air, so Baritone
cannot plan through foliage or water it merely looked through. A route request adds an exact ray to its requested
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
see-through): a block a later Minecraft adds fails the test until somebody decides on purpose. The per-block list with the reason
for each verdict (researched from the game's own models, textures and shapes, and judged twice independently) is
[`perception/see_through_blocks.tsv`](perception/see_through_blocks.tsv); the judgement calls are walls (opaque: solid stone), doors,
vault and beacon (opaque), tinted glass, slime, honey, spawners and the barrier block (see-through).

`mode/SightClip` is `level.clip` with those cells skipped. `SightClipContext` is a vanilla `ClipContext` whose two per-cell hooks
answer an empty shape for what is skipped, so vanilla's own traversal and nearest-hit rule do the rest and a ray that crosses
nothing see-through is exactly vanilla's. The cell being observed (the target) is never skipped, an eye inside a see-through cell
sees out of it, and lava keeps its shape even for a ray that asked for `Fluid.NONE`. The one lava that does not hide the ground is the
lava the observer stands in (its eye is above the surface; a bot that fell into a pool still sees the bank it must climb to). An eye
in the lava itself is blind.

**Sight is not reach.** Seeing a block behind a leaf does not let a hand reach it: every actuator (break, place, open, interact,
strike, bucket) refuses what a real pick ray would not reach. A context built with `trackObstructions()` therefore also reports
`obstructions()`: the see-through blocks a vanilla pick ray along the same segment would hit, nearest the eye first (tracking costs
a shape lookup and a clip per skipped block, so only the reach proofs ask for it: `SightClip.pick`). A pick ray is an `OUTLINE` ray
with `Fluid.NONE` (`Entity.pick`), so that is the shape the list is modelled with whatever shape the sight ray used: water is never
an obstruction, a block with no collision but an outline (grass, a cobweb, a torch, an open gate) is, and a ray that slips past a
lone fence post or a pane's post is not. `crossed()` lists every skipped cell with the state it really holds, so a recorder never
stores a leaf, a fence, a plant that no collision shape ever stopped (fire, a cobweb, a berry bush) or water as air, nor the lava a
bot wades in; it answers a recorder's per-cell question from an index, so a long underwater ray costs one lookup per cell.

The two families live side by side in `ObservableWorldQuery`. The ordinary predicates (`canObserveBlock`,
`canObserveBlockCellFace`, `canObserveBlockWithInsetFaces`, `canObserveCell`, `canObserveFarmCell`, the collider forms, the
`*ThroughFluids` aliases and the linked owner's mirrors) are sight: they cast `SightClip` rays and pass the observed cell as the
target. Each one an actuator needs has a `Strict` twin, the line a hand needs: the vanilla pick ray (outline shapes, fluids ignored)
along the very segment, which the first leaf, plant, cobweb, torch, open gate, fence post or pane its outline crosses stops, which
water does not (a player mines the log across a pond) and lava still does. The strict twins aim at the outline of the target, and
only the bot's own hand counts: a linked owner's clear line proves sight, never reach. A break, an open or a use sends no pick ray
of its own, so each of those actuators re-proves with the strict twin itself:
`MiningController.currentObservedTarget` (the sole break gate; `visiblyAir` only settles a finished break and may use sight),
`ContainerAction.canSee`, `FarmAction.harvestProof`, `BaritoneGoals.mineAt` (through the miner's admission, below), the break proof of `BaritoneBreakPlacePolicy`, a
furnace or depot the bot reaches into (`SmeltTask`, `StripMineTask`, `MiningServiceTask`, `WorkshopLocator`) and
`InteractAction.useItemOnEntity` and `TradeTask` (the vanilla collider line of `StrikeLegality`: opening a trade sends no pick ray either).
Placement, bucket and strike rays were always plain
vanilla clips. `SightVersusReachSourceContractTest` pins both lists. A log seen through two leaves is therefore a target (navigation
admits it and the shared memory knows the leaves and the log), but a break through the leaf is never sent: the leaf is broken first
(next section).

### Mining what is seen through something

The bot first tries the target itself: the strict gate above, six face centres and the 3x3 inset grid on each face with the vanilla
pick ray, is the "natural" line, so a log in the open, behind the gap of a fence line (between the rails) or across a pond is mined
without breaking anything. Only when no line passes the strict gate but the eyes do see the block (the sight proofs, state-free
first) does `MiningController` clear the way, and it does so inside the one operation every caller already uses
(`ActionPack.startMining`, `BlockMiner`, `HarvestCore`, `BaritoneGoals.mineAt`, the legacy path executor's dig steps), so Gather,
OreDig, farming and the rest need no change and see one coherent break: it starts as in progress, keeps the generation, the break
delay and the cancel semantics (a cancel aborts the step that is running), and succeeds when the target is gone. Gather does read
the miner's refusal from every place it starts a break (start, resume after a pause, retry): a log its eyes nominated but its hand may
not reach (`target_obstructed`, `target_not_observed`) is given up on the next tick (`gather_harvest_refused`, and the survey
re-plans) instead of standing in HARVEST until the deadline. A log behind blocks it may not break (`target_obstructed`) is excluded for
`EpisodeMemory.TTL_UNREACHABLE`; one that left its sight, for `EpisodeMemory.TTL_SHORT` (another stance may show it again). A generic mine
request does the same with its block (`mine_target_refused`) instead of waiting for a drop that cannot come.

`mode/ReachObstructions` lists, for each of the strict gate's own aim lines, the see-through blocks that gate would meet first: it
casts the very ray the strict twins do (`SightClip.pick`, tracked) and reads `obstructions()`, the see-through blocks whose outline the
segment crosses, nearest first. What is on a line is exactly what must disappear for the strict gate to pass along it, and a line with
nothing on it is one a hand already reaches (a click through the gap of a fence line, past a lone post, across water).
`MiningObstruction` then picks the line with the fewest blocks, then the nearest first block, and breaks that block like any other:
through a normal `MiningController` with its own tool
(`ToolSelector`), break delay, safety, drops and audit (`mine_complete` with `obstruction_of`, the edits ledger). It re-plans after
each break, so each step removes one real block and the target is started only once the strict gate passes, never while an intact
see-through block is what a hand would hit. A break the server did not carry out ends the operation (`obstruction_persisted`) instead
of looping; no count or timer was invented for it.

What may be broken is the mod-wide `BreakRule`: natural terrain, which for something in the way means leaves and the small plants
that grow in the way. A fence, a gate, glass, a pane, bars, a ladder, a chain or ice was put there by somebody (or releases water),
so a line that crosses one is skipped altogether, even with a leaf in front of it. A block the bot or another player stands on and a
leaf beside lava the bot can see, or beside water that would flow into its cell, are kept too (`exposes_lava`, `exposes_water`).
When no line is left the operation fails with the typed `target_obstructed` (the callers' "not mineable from this stand": a task
tries another stand), and `mining_obstruction_refused` says which block and why;
`target_not_observed` stays for a target the eyes do not see (an opaque block, lava, out of reach). A Baritone driver's
controller does not clear anything on top of that: Baritone clicks what its own pick ray meets, which is the leaf. Neither does the
break of the bot's own footing (`ActionPack.startOwnSupportMining`, the one that takes a pillar the bot built down again,
`TowerDescent`): it aims only at the block under the bot's feet, goes through the same strict gate, is refused unless that block
is seen, and never clears anything in front of it. Clearing never breaks a block the bot or another player stands on, so the tower
the bot is on is only ever taken down by that dedicated path.

A pillar plan weighs the same split before the bot stands on the pillar (`HarvestCore.isSightlineBlocked`): a line from the pillar
head to the target that only leaves, small plants or water cross is not shut (the controller breaks the first and a hand passes
the second, `MiningObstruction.handGetsPast`), so a log in a canopy is climbed to from the lowest pillar in reach; a line crossed by
anything else the bot has seen (a ledge, a wall, a fence, a pane) is shut, as before. The pillar's own column still has to be air
all the way up to the target.

`castViewRay` stays the strict first-hit view ray: the mining assist's sweeper and the suffocation escape's dig choice use it,
because an occupancy grid that writes air for every traversed cell and a hazard field that takes a water surface for a fluid hit
cannot take a ray that passes through foliage or water. `castSightRay` is its see-through sibling for sight consumers (the tree and
target look-arounds, the observed-navigation fence): its `ViewHit` lists the `crossed()` cells with their real states, so
`TreeHorizonScan` keeps a leaf as a landmark when no trunk shows behind it, `VisibleTargetHorizonScan` finds a plant or a cobweb a
ray crosses, and `ObservedNavigationFence.scanRay` stores what the ray passed through. Lava is never forgotten: it stays opaque, so
a ray that crossed water and then met lava remembers both. The linked owner's eyes (`SharedVision.ownerSees`) see through the same
things as the bot's.

### Creatures: noticing is sight, a fight is physical

A creature behind leaves, a fence, glass or water is noticed like any other: `CreatureSenses` casts `SightClip` rays for the scan
(the eye ray and the body-centre ray), the sound match, the awareness check, a projectile in flight and the omnidirectional
fail-safe, and `canObserveEntity` (animals, villagers, boats, drops, prey) is the same eye-to-eye ray as vanilla's
`hasLineOfSight` with the same 128 block limit. Lava and every opaque block (stone, walls, doors, slabs) still hide it, and so does
a creature behind lava: vanilla's ray passed lava because it ignores every fluid, a bot's eyes do not. Nothing else about the
creature changes: a blow, an arrow or a blast cannot cross what the eyes pass, so **seeing is not threatening**.

| Question | Line | Where |
| --- | --- | --- |
| Is it noticed? Is it still tracked? What the LLM is told, avoidance routing, a sound matched to a creature, a projectile in view, prey and drops discovered, the owner's nomination | sight (see-through) | `CreatureSenses`, `canNoticeCreature`, `canObserveEntity`, `SharedVision.ownerSees` |
| Can it be struck or shot? Does it press on the bot? Is it a threat to fight or flee, a target to acquire, a creeper to run from? Is a fight still worth keeping? | physical (the vanilla collider ray) | `CombatCore.hasLineOfSight`, `StrikeLegality`, `DangerWatcher.canReachThreat`, the pressure sets, `nearestTarget`, `nearestHostileAround`, `CreeperDefenseTask`, `EvadeTask`, the lost-line timers of `CombatTask` and `GuardTask`, `SharedVision.ownerSeesStrict` |
| Where did a projectile that hit the bot come from? | physical | `CreatureSenses.traceBack` |
| May a hand milk, feed, board or trade? | physical, proved before the click | `InteractAction.useItemOnEntity`, `TradeTask` |
| Which animal, villager or prey does a deliberate search set out for, and from which stand? | sight to nominate, then the physical line of what the click or blow will need | `CombatCore.nearestTarget` (a cow too), `TradeTask.nearestVillager`, `MilkCowAction.nearestCow`, `BreedTask.findPair`, `HuntTask.canStrikeFrom` (per attack pose), the last stand (`DangerWatcher.lastStandTarget`) |

The split exists because a creature noticed through a leaf used to imply a physical line (sight and the line were the same ray),
and a lot of code leans on that. A bot that fought what it merely sees would walk up to a pane, find no line and give up, then
engage the same target again for ever (the guard's cooldown cycle); it would stop eating and cleaning up beside a glass-walled mob
farm; it would flee a creeper whose blast cannot cross the glass, and hold the escape open for as long as the creeper stays in
view (`docs/FINDINGS_DIAMOND64.md` F10, "visible but unreachable"). So every decision that commits the bot to a creature asks the
physical line as well as the notice, written out where it is made and pinned by `SightVersusReachSourceContractTest`:
target acquisition (`CombatCore.nearestTarget` for a hostile, `nearestHostileAround`, which is the guard's), the hostile-pressure
sets (`DangerWatcher.observableActiveHostilePressure`, `CombatTask.observableActiveHostiles`), the threat itself
(`canReachThreat`), a creeper as a risk (`CreeperDefenseTask.observableCreeperSnapshots`) and as an unsettled flight
(`EvadeTask`), and the two lost-line timers (`CombatTask`, `GuardTask`): they end a fight the bot can never strike in, which is
about what a blow can cross, so they stay physical and a target seen but never reachable is dropped (and the guard leaves it alone
for a while) exactly as one seen across a gap always was. The owner's nomination in that bookkeeping is the owner's collider line
too. What a deliberate search sets out for is chosen the same way: the nearest cow, villager or pair that is seen would otherwise be
a pen behind glass that the bot walks to and cannot touch, while the one in the open is never chosen (a fight reported complete with
nothing killed, a trade or a feeding that never starts, a hunt standing at the pane until its no-progress deadline). A fight, a
trade, a milking and a breeding therefore nominate an animal that is seen AND on the plain vanilla line from where the bot stands; a
hunt keeps the see-through nomination (prey is spotted across a hedge and walked to) and asks for the line from each attack pose
(`HuntTask.canStrikeFrom`), so prey with no pose it can be struck from is rejected like any other that cannot be reached; and the
last stand fights the nearest hostile it can hit, not the first one it merely sees. Prey is nominated out to 64 blocks but block sight
ends at the render distance: the ground under an animal seen beyond it has not been seen, so the hunt does not judge it (no
`no_round_trip` on the first step) and walks observed legs toward the animal until its cell is in sight
(`HuntTask.startLegTowardUnseenPrey`). What the shelter's exit and the shield guard ask of a noticed creature stay sight: they plan for what is out there, and the
mob's own vanilla AI (opaque) gates what it can do to the bot.

The PvP BOT wrapper is not changed: its inhabitants keep vanilla's opaque ray (`AggroDriver`, `AggroWorld`), as they do for any
player, and the shared model stays the same (`occlusionClear` is only an input, `perception/vectors.json` is untouched). The
asymmetry is deliberate: the request was for Minecraft-AI's bots. A bot that sees an inhabitant through a hedge notices it, and
that is all it gains; to strike it needs the same physical line any player needs, and the inhabitant still notices the bot only
through a clear line.

## Scope

* IN: every place a bot NOTICES a creature (threat detection, target acquisition, aggro, aggressor checks,
  perception summaries given to the LLM, projectile threat awareness), through the see-through eyes of the bot.
* OUT: object perception (items, containers, crops, blocks, boats), non-hostile task targets a bot deliberately
  searches for (hunt, breed, milk, trade, villagers: omnidirectional, with the physical line of the click or blow they need
  on top, see above), strike legality and every other physical ray check (they keep the
  vanilla collider line), and the owner's own camera cone (`SharedVision.ownerSees`, which uses the same see-through eyes as
  the bot's).
* Light level and darkness are not modelled (vanilla mobs ignore them too); a possible later option.

## Vectors

`perception/vectors.json` (version 3) lists the params, the cases (`angleDeg`, `distance`, `subject`, `heardNear`,
`occlusionClear`, optional `paramsOverride`, `expectSense`, `expectSeconds`, `expectNoticeTick`) and exposure `runs`
(a per-tick sighted string, the required seconds and the index of the first tick that notices). `expectSeconds` is
`requiredSeconds` as a double (compared with a tolerance), or `"never"`. Wrapper tests read `../docs/perception/vectors.json`,
the root tests read `docs/perception/vectors.json`. Extend the file only with results both implementations reproduce.
