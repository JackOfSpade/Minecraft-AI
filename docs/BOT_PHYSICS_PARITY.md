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
  (`markFallChecked`), so nothing is charged twice. Not applied to a passenger (the vehicle decides) or a dead bot. Teleports done by
  `FakePlayerMotion` and the safety moves happen outside that measured tick and clear `fallDistance` themselves. Everything else
  (block landed on, feather falling, armour, honey, hay, water, the 60 tick "client not loaded" protection of a fake connection) is vanilla's.
* **Numbers.** `ceil(distance - 3)` hit points: 0 / 2 / 5 / 9 for a fall of 3 / 5 / 8 / 12 blocks. The planner's `nav.maxSafeFall` (3) is
  therefore damage free and is now a real safety bound; nothing in the planner, `FollowTask`, `DescendToY`, `DigDown` or `EmergencyShelter`
  needed a change (they drop at most 3 blocks, or move by `FakePlayerMotion` steps that clear the fall distance).
* **Tests.** `BotFallAndKnockbackGameTests.botsTakeVanillaFallDamageWhenWalkedOrPushedOffALedge` (legacy `startWalkTo` and a plain velocity
  push, 3/5/8/12 blocks) and `BaritoneInputPhysicsProbeGameTests.fallLandingAndDamage` (Baritone driven, exact damage, so no double count).
  On a branch without the fix the first one fails: a 5 block walk off a ledge cost 0 hit points instead of 2.

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

The GameTests report `BOTFALL|...` and `BOTKB|...` lines on stdout with the measured values.
