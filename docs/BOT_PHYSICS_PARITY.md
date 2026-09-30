# Bot physics parity: fall damage and knockback

The bots are `ServerPlayer`s without a client. Two vanilla behaviours that a real player gets from its client were missing, which made the
bots immune to them. Bots are survival-legal and must not cheat, so both are now applied on the server.

## Fall damage

* **Cause.** In 1.21.11 `Entity.move` skips `checkFallDamage` for a server-side player (a player is client authoritative): vanilla expects
  the client's move packet to call `doCheckFallDamage(dx, dy, dz, onGround)` on the server. A bot has no such packet, so its
  `fallDistance` never grew and it took no damage from any drop (`DangerWatcher`'s `FALLING` threat could never fire). Gravity and
  collision were always fine (a bot ticks its own physics); only the damage was missing. The old `PathExecutor` note that fall damage
  had been "empirically confirmed" was wrong (only the gravity was).
* **Fix.** `AIPlayerEntity.tick` records where the tick began and, after the physics tick (`super.tick()` and `doTick()`), calls
  `doCheckFallDamage` with the tick's movement and `onGround()`, exactly what the move-packet handler does. It runs on every tick, at most
  once: the Baritone driver (`BaritoneDriver.afterPhysics`, step 6) does the same check for a driven bot and marks the tick
  (`markFallChecked`), so nothing is charged twice. Not applied to a passenger (the vehicle decides) or a dead bot. A teleport is not a fall: nothing in vanilla's teleport
  path clears `fallDistance` (a real client resets it with its own move packet), so `AIPlayerEntity` overrides `teleportTo` (both shapes)
  and `teleport(TeleportTransition)` to reset it and to mark the tick's fall check as done (otherwise the jump itself is measured as a
  fall). One place for every caller: `NavSafetyNet` (suffocation climb-up, drowning), `DangerWatcher` dark-trap surfacing,
  `GatherQuotaTask` surfacing, the panel RECALL / TO_AI teleports, `AIPlayerManager` respawn (a bot killed in mid-air is revived with no
  distance) and `FakePlayerMotion`. Everything else
  (block landed on, feather falling, armour, honey, hay, water, the 60 tick "client not loaded" protection of a fake connection) is vanilla's.
* **Numbers.** `ceil(distance - 3)` hit points: 0 / 2 / 5 / 9 for a fall of 3 / 5 / 8 / 12 blocks. The planner's `nav.maxSafeFall` (3) is
  therefore damage free and is now a real safety bound; nothing in the planner, `FollowTask`, `DescendToY`, `DigDown` or `EmergencyShelter`
  needed a change (they drop at most 3 blocks, or move by `FakePlayerMotion` steps that clear the fall distance).
* **Tests.** `BotFallAndKnockbackGameTests.botsTakeVanillaFallDamageWhenWalkedOrPushedOffALedge` (legacy `startWalkTo` and a plain velocity
  push, 3/5/8/12 blocks) and `BaritoneInputPhysicsProbeGameTests.fallLandingAndDamage` (Baritone driven, exact damage, so no double count).
  On a branch without the fix the first one fails: a 5 block walk off a ledge cost 0 hit points instead of 2.
  `BotFallAndKnockbackGameTests.aTeleportedBotTakesNoPhantomFallDamage`: a bot that has fallen 6+ blocks and is then teleported (both
  `teleportTo` shapes) keeps full health and a zero fall distance. A Baritone-driven tick followed by a legacy tick in one game tick
  (driver hand-over) cannot count twice or miss: the driver marks the tick (`markFallChecked`) and `checkFallDamageOnce` is a no-op
  when marked; that is pinned by `BotPhysicsParityContractTest` and exercised by `BaritoneInputPhysicsProbeGameTests.fallLandingAndDamage`.
* **Side effects of the live `FALLING` threat.** `DangerWatcher`'s `FALLING` threat (LOW severity) can now really fire, because
  `fallDistance` grows. Two short-lived effects: it blocks `canResumePausedWork` while the bot is airborne (paused work resumes on
  the landing tick), and a `CombatTask` whose top threat is `FALLING` skips its combat branch for that tick (the bot is mid-air and cannot
  fight anyway; the next grounded tick resumes it). Neither lasts beyond the fall.

## Knockback

* **Cause.** `Player.attack` against a `ServerPlayer` target calls `Player.causeExtraKnockback`, which sends
  `ClientboundSetEntityMotionPacket` to the target's client and then **restores the target's old server-side velocity** (the client applies
  the push and reports the result). A bot never got player melee knockback (its velocity after a hit was exactly zero). Mob melee and
  projectiles never restore the velocity (the only classes that name the motion packet are `ServerEntity`, `Player` and `MaceItem`), so
  they already worked.
* **Fix.** `BotMeleeKnockbackMixin` wraps the `hurtMarked` read in `Player.causeExtraKnockback` and answers false for an `AIPlayerEntity`
  target only, which skips the packet and the restore: the velocity from `LivingEntity.knockback` (knockback resistance already applied,
  sprint-hit extra knockback included) stays, and `ServerEntity` broadcasts it like for any mob. Real players take the original path. A
  non-exclusive `@WrapOperation` (MixinExtras), probed by `MixinTargetClassLoadGameTests.botMeleeKnockbackMixinTargetLoadsWithTheMixinApplied`.
* **Tests.** `BotFallAndKnockbackGameTests.meleeKnockbackOfABotMatchesAVanillaMob`: a plain player hit, a sprint hit, a zombie hit and both
  with knockback resistance 0.5 push a bot exactly as they push a control zombie in the same geometry (0.4 up, 0.4 away; 0.7 for the sprint
  hit; 0.2 with resistance 0.5).
  `BotFallAndKnockbackGameTests.arrowKnockbackOfABotMatchesAVanillaMob`: a skeleton's arrow (the damage source of a projectile hit, from the
  same geometry, also with resistance 0.5) pushes a bot exactly as it pushes a control zombie. A real flight is not simulated: the
  knockback is applied in `LivingEntity.hurtServer` from the position of the arrow, the same call for every target, so the hit call is
  the whole path.

The GameTests report `BOTFALL|...` and `BOTKB|...` lines on stdout with the measured values.

## No path-correction teleports (R5)

A player never corrects its position by being moved, so a bot does not either (`TeleportAudit` counts every such move as a `CORRECTION`;
the GameTests assert `TeleportAudit.corrections(bot) == 0`). What a teleport used to do, the movement keys do now:

* **`WalkedStep`** (`action`): one in-flight step written as forward, jump and sneak inputs at the pace-enforced speed (kinds `FLAT`,
  `STEP_UP`, `STEP_DOWN`, `SWIM`, `SNEAK_SHIFT`, `RECENTER`, `PUSH_OUT`). It validates on its first tick, re-proves its landing every tick and
  never moves the bot itself; `ActionPack.runStep` runs one as a controller, `PathExecutor.prefixStep` as the first step of a route.
* **Path start.** `ActionPack.snapPlayerToNearestStandable` only plans: a standable start is used as it is (a body that overlaps a wall
  column walks off it), any other start is left by a walked step onto an adjacent standable cell (once per origin cell per 200 ticks,
  `SnapRepeatGuard`) that the executor performs before its first node. The operator-profile long path-start relocation no longer exists.
* **Stalled hop / pillar.** After the jump arc a stalled hop is diagnosed (headroom, landing, occupant); when a retry can help the bot
  releases the keys, backs off a little and runs at the ledge again (at most twice), otherwise the node fails and the route is planned again
  from where the bot stands. A pillar re-jumps up to three times. No rescue teleport, no rollback teleport.
* **Suffocation.** `NavSafetyNet` decides `EMERGENCY_TELEPORT` first (operator only; nothing is scanned when it is denied) and otherwise
  gets the bot out with inputs: a vanilla-client style shove out of the block (at most 0.1 block per tick toward the nearest free side), a
  walked step onto an adjacent standable cell, or digging out the block at the head and then at the feet with the tool it has, at the real
  break time and only where a view ray from its own eye reaches; else it logs `navsafe_suffocation_trapped`.
* **Water.** A bot swims by inputs: `SWIM` steps hold the forward key and the depth a swimmer holds (jump while the feet are in the lower part of
  the cell, let go to sink), and a dive adds the downward push a client applies while shift is held in water (`goDownInWater`, 0.04 per
  tick; the server-side bot has no client code that would). Measured (`NaturalSwimGameTests.legacyInputsSwimAndSurface`): about 2.6 blocks per
  second across a pool and about 0.11 block per tick up. `FollowSwimming` (entering the water, greedy and routed swim steps, the way up)
  and the `NavSafetyNet` water rescue run one step at a time and re-plan when it ends; the follower drops a step in flight as soon as its
  lungs call for the way up (`FollowOxygen.SURFACE_FLOOR_AIR` is 200, the ascent estimate 0.1 block per tick). The drowning rescue takes
  over at 120 air. `FollowStuckRecovery`'s adjacent step, `FollowDigOut`'s walk into each opened cell and `ShelterExitDebtRepayer`'s walk
  out of the doorway are walked steps too.
* **What still moves a bot.** Spawn and respawn (`LIFECYCLE`), the panel recall (`USER`, gated by `manualTeleport`), vanilla teleports, and
  the operator-profile emergency rescues (`PRIVILEGED`: suffocation climb, drowning, dark-trap and gather surfacing; never in strict
  survival). The `FakePlayerMotion` primitives remain for the task conversions that are still to come.
