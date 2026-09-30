REVISION 2026-09-30 (supersedes the range-based sight in section 1-3 below for BOTH mods): the user then asked for
"no hard-coded block distance restrictions" on PvP BOT bots and "real human reaction time: seen for at least 0.25 s".
The shared model becomes TIME-based: sighted = same level && distance <= the MOD MAX && in view cone (front 60 /
peripheral 100 deg; behind never) && occlusion clear (eye ray, then body-centre ray) && not fully invisible; NOTICED
after continuous exposure >= reactionTicks (5) * cone multiplier (front 1, peripheral 2) * sneak multiplier (2) /
visibility + distance term (5 ticks per 32 blocks, soft). Hearing (walk 4 / sprint 8 / combat 12, sneaking silent) with
occlusion clear counts as front-cone exposure; occluded sound = investigate hint only. Awareness after a notice or a
hit = tracking by plain occlusion. MOD MAX: wrapper = 128 (vanilla hasLineOfSight cap = PvP BOT maxTargetDistance);
Minecraft-AI = its existing profile observation radius. The wrapper job w32 (specs\w32_los_hunter.md) rewrites
Perception.java, docs/perception/vectors.json (expected outputs become required exposure ticks) and docs/PERCEPTION.md;
job pr reuses them. Everything else below (scope, call-site list for pr) still applies.

REALISTIC PERCEPTION: shared design for Minecraft-AI bots AND PvP BOT inhabitants (wrapper)
User request (2026-09-30, verbatim): "i want line of sight that we use everywhere is realistic (e.g. they cannot see you if you're behind them, sneaking or not), so this allow ambush plays, i'll let you best design this if we dont have it already"

WHAT EXISTS TODAY (not realistic):
- Minecraft-AI: ObservableWorldQuery.canObserveEntity(bot, e) = within radius && bot.hasLineOfSight(e). It is omnidirectional, and sneaking does not matter. Used for creatures AND objects at ~60 call sites.
- The owner's (human) vision is SharedVision.ownerSees: a 60 deg half-angle cone plus hasLineOfSight. It already models the camera and is kept.
- Wrapper AggroController: acquisition uses player.hasLineOfSight(candidate). It is omnidirectional, and sneaking does not matter.

MODEL. One pure decision function, identical in both mods:
  notice(observer O, subject S) -> SIGHT | HEARING | NONE
It applies to NOTICING CREATURES (players, Minecraft-AI bots, PvP BOT bots, mobs, animals). Objects are out of scope (see "Scope").

1. Occlusion: realistic line of sight.
   - Ray from O's eye to S's eye; if that is blocked, a second ray to S's body centre (peeking over a wall or a head above cover counts).
   - Vanilla clip rules: ClipContext.Block.COLLIDER, fluids ignored, i.e. the same blocks vanilla mobs cannot see through.
   - Run the rays LAST (after the cheap range and angle filters).
2. View cone: O's look vector = getViewVector(1f), head yaw and pitch. theta = the 3D angle between the look vector and the O-eye -> S direction.
   - |theta| <= frontHalfAngle (60 deg, a 120 deg cone): full sight range.
   - frontHalfAngle < |theta| <= peripheralHalfAngle (100 deg, a 200 deg total field): sight range * peripheralFactor (0.5).
   - |theta| > peripheralHalfAngle (behind): NOT seen.
3. Subject visibility factors, multiplied into the sight range:
   - sneaking (isShiftKeyDown / crouching pose): sneakFactor 0.5. Vanilla uses 0.8 for mobs; 0.5 makes ambushes meaningful.
   - invisibility and disguise: vanilla LivingEntity.getVisibilityPercent(O) with its own sneak factor divided out, so sneaking is not counted twice. This keeps vanilla rules for invisibility (armor coverage) and worn mob heads.
   SIGHT = range_eff >= distance && occlusion clear, where range_eff = baseRange * coneFactor * sneakFactor * visibility.
4. Hearing: a close, noisy subject is noticed even from behind.
   S emits a noise radius this tick. O notices by HEARING if distance <= noise radius AND the occlusion rays are clear: O turns to the sound and sees S. Hearing never works through walls, so there is no x-ray.
   Noise radius, the max of what applies:
   - sneaking movement: 0 (silent);
   - standing still: 0;
   - walking: 4;
   - sprinting / sprint-jumping: 8;
   - combat noise in the last 10 ticks (swing, being hurt, firing or charging a bow/crossbow/trident, breaking or placing a block, eating/drinking): 12. A sneak-attack is heard, but the victim becomes aware by the hit anyway.
   - Mobs:
     - noisy hostiles (zombie family, skeleton family, spider, pillager/vindicator/evoker/witch, enderman, blaze, ghast, piglin/brute, hoglin, phantom, slime/magma): 8;
     - creeper: 0 while not primed, 16 while swelling;
     - warden: 24;
     - animals and other creatures: walking 4 while moving, 0 while idle;
     - projectiles: see the Minecraft-AI notes.
   The hearing radius is capped at the observer's base sight range.
5. Awareness memory (engaged tracking):
   - Once O has NOTICED S (sight or hearing), or S has HIT O (damage attribution), O is AWARE of S for awarenessTicks (200 = 10 s).
   - Awareness refreshes whenever plain occlusion is clear (no cone, no sneak factor): an engaged combatant turns to follow you.
   - While aware, "can see" checks use plain occlusion. This matches the wrapper's existing 10 s lose-sight rule and PvP BOT's revenge.
   - Awareness is per (observer, subject). Clear it on death, logout and level change.
6. Config (per mod): perception { enabled (default true), frontHalfAngleDeg 60, peripheralHalfAngleDeg 100, peripheralFactor 0.5, sneakFactor 0.5, hearWalk 4, hearSprint 8, hearCombat 12, hearNoisyMob 8, hearPrimedCreeper 16, hearWarden 24, hearAnimal 4, combatNoiseTicks 10, awarenessTicks 200 }.
   - enabled=false restores today's omnidirectional line of sight exactly.
   - Validate bounds (angles 0..180, front <= peripheral, factors 0..1, radii >= 0).
7. Golden vectors: both mods implement the same pure function, so they share one test-vector file.
   - The file is docs/perception/vectors.json in the repo ROOT. The root tests read docs/perception/vectors.json; the wrapper tests read ../docs/perception/vectors.json.
   - Cases: in front at 9.5 and 10.5 (base 10); peripheral 70 deg at 4.9 vs 5.1; behind 150 deg at 2 (walking: heard at <= 4; sneaking: not); sneaking in front at 4.9/5.1; sprinting behind at 7.9/8.1; combat noise at 11.9; invisible with no armor; occluded (hearing does not pass walls); primed creeper behind at 15; warden behind at 23.
   - Both test suites must pass all vectors. Whichever job lands first creates the file; the second reuses it unchanged (or extends it with the same results in both).

SCOPE:
- IN: every place a bot NOTICES a creature (threat detection, target acquisition, aggro, visible-aggressor checks, perception summaries given to the LLM, warden detection, projectile threat awareness).
- OUT (unchanged, documented why): OBJECT perception (items on the ground, containers, crops, blocks, boats) and non-hostile task targets where the bot deliberately searches (hunt/breed/milk/trade/villagers). A bot glances around while searching, and a cone would only make chores dumber with no ambush value. Also out: strike legality (StrikeLegality / CombatCore.hasLineOfSight = physical ray legality, unchanged) and the owner's camera cone (SharedVision.ownerSees, already realistic).
- Light level / darkness is NOT modelled; vanilla mobs ignore it too. Mention it as a possible later option.

JOB wp (wrapper, now): PvP BOT inhabitants noticing players and Minecraft-AI bots.
- Add combat/Perception (pure) and the noise/visibility facts to AggroWorld.Body: look vector, eye, body centre, sneaking, sprinting, moving, recent swing/hurt/use-item ticks, and the invisibility factor from getVisibilityPercent.
- The AggroDriver fills them in from ServerPlayer.
- AggroController acquisition uses notice() with baseRange = aggro.acquireRange (10). So: front 10 blocks, peripheral 5, sneaking halves, behind only by hearing (walk 4 / sprint 8 / combat 12), sneaking behind = never noticed until you hit.
- Awareness: the engagement's 10 s lose-sight rule already exists (keep it, using plain occlusion with the second body ray).
- aggro.requireLineOfSight stays as the switch for occlusion. Add an "aggro.perception" block (keys above). perception.enabled=false = today's behaviour.
- The status/damage-taken line should say how a target was noticed (sight / hearing / hit).
- Tests: golden vectors plus controller cases:
  - player sneaking behind within 2 blocks: never acquired; hit -> engaged;
  - player walking behind at 3: acquired (hearing);
  - sprinting behind at 7: acquired; at 9: not;
  - in front at 9.5 standing: acquired; sneaking in front at 6: not; at 4.5: yes.
- Also create docs/perception/vectors.json and a short docs/PERCEPTION.md in the repo root describing the shared model.
- Coordination: w29 (another branch) edits the adapter/CombatLogger/harness/InhabitantsConfig (pvpbotSettings block). Keep your config additions in the aggro block.

JOB pr (Minecraft-AI, after the fe and nx jobs merge; they own DangerWatcher/AggroSense/SharedVision/CombatCore right now):
- Add perception/CreaturePerception (pure core + level adapter) and an awareness memory per bot.
- Behaviour config behaviour.perception.
- Split ObservableWorldQuery into canObserveEntity (objects, unchanged) and a new canNoticeCreature(bot, living) used at the creature call sites. Audit EVERY caller of canObserveEntity/canObserveEntityWithin/hasLineOfSight and classify each (list it in the report).
- Creature call sites include DangerWatcher threat scans, AggroSense, CombatCore target acquisition, CombatTask skeleton/creeper/target checks, CreeperDefenseTask, EmergencyShelterTask threat checks, EvadeTask, ProjectileThreat (a projectile is noticed if its shooter's combat noise is heard or the projectile itself is in sight), QuietZone warden detection (warden noise 24), SharedVision.seenByBotOrOwner (bot part), HostileBotIntent sampling (the ag review nit: gate intent sampling on perception), PerceptionCollector (what the LLM is told it sees), and the DiagnosticLogger lists.
- Being hit makes the bot aware of the attacker (RecentDamage attribution).
- The owner's shared vision still counts: a threat the owner sees is known to the bot (the owner calls it out).
- GameTests:
  - a zombie approaching from behind a mining bot is noticed only at hearing range (8), not at 16;
  - a creeper creeping up behind is noticed only when it primes (16) or enters the front cone;
  - a PvP BOT-like hostile player sneaking behind is not noticed until it hits;
  - owner-sees still nominates;
  - regression classes: combat_hardening, danger_watcher_*, creeper_defense_*, emergency_shelter_*, evade_*, mining_assist_sense_*.

REVISION 2 (2026-09-30): HEARING uses the vanilla vibration system called directly (VibrationSystem.User/Data/Listener
+ DynamicGameEventListener + VibrationSystem.Ticker, like the Warden/sculk sensor; listener radius 16 by default (user: the Warden radius);
vanilla sneaking and wool rules). Reaction time: requiredSeconds = (0.5 + 1.5*d/64) * angleFactor (1 up to 30 deg,
linear to 2 at 100 deg) * 2 if sneaking / visibility; heard + visible counts with angleFactor 1; heard + occluded =
investigate hint. Engagement limit 64 blocks (wrapper; decided only from a sighting). Job pr (Minecraft-AI) must use
the same approach for companions (a vibration listener per bot) and the same formula/vectors. See
specs\w32b_engage64.md amendments 1-5 for the authoritative details.
