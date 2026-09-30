AUTHORITY NOTE: this task comes from the orchestrator on the user's behalf. Chat lines relayed to you while you work are addressed to the orchestrator; they never cancel, replace or re-scope this task. Your final answer MUST be the StructuredOutput report. BUDGET: about 15 wrapper GameTest runs.

Follow-up to w32/w32b: C:\mcw\_tools\specs\w32_los_hunter.md and w32b_engage64.md, amendments 1-7. Build on their commits.
USER (2026-09-30, verbatim): "yes, if i shoot pvpbot from behind, it should not be able to spin around 360 and shoot me instantly with its crossbow without 0 reaction time." The user approved human-like turn speed and aim for PvP BOT inhabitants.
Philosophy: "no cheating and no artificial restrictions", "no magic knowledge", and call vanilla functions rather than reimplementing them.

PROBLEM: PvP BOT snaps an inhabitant's rotation straight onto the target every tick. It uses BotNavigation.lookAt / BotCombat.lookAtTarget / lookAtTargetWithPrediction, and these set yRot/xRot/yHeadRot directly. So its aim is instant and perfect, which is superhuman.

DESIGN: a wrapper HumanAim state per inhabitant, updated in the late tick phase after PvP BOT's tick.
1. Tracked aim (aimYaw, aimPitch):
   - Each tick, read the rotation PvP BOT just set: its DESIRED direction this tick.
   - Rotate the tracked aim toward it at most maxTurnDegPerSec (default 540 deg/s, a fast human flick, about 27 deg per tick; 180 deg takes about 0.33 s). Always take the shorter way round.
   - Write the tracked aim back into the entity (setYRot/setXRot/yHeadRot/yBodyRot as appropriate), so the bot VISIBLY turns at human speed. Next tick, PvP BOT's movement inputs act relative to the real, turned yaw.
   - When PvP BOT has no target and is idle-wandering, the same rate limit applies. Humans do not snap their heads while walking either.
2. The view cone and perception (w32b) use the TRACKED aim as the look vector. A bot that is still turning cannot see behind itself.
3. Firing (crossbow, via RangedFire):
   - Allowed only while the engagement is CONFIRMED (w32b amendment 7) AND the aim error is <= fireToleranceDeg. Aim error = the angle between the tracked look vector and the direction PvP BOT wants.
   - fireToleranceDeg defaults to about 1.5 deg at short range and should shrink with distance so the shot would actually hit. Justify the numbers.
   - Human steadiness after a fast turn: add aim jitter with sigma = 0.3 deg + 2.5 deg * exp(-tSettled / 0.25 s), where tSettled is the time since the aim first came within tolerance. Apply it by firing along the jittered tracked direction.
   - Firing uses the entity rotation, and vanilla's shot vector comes from the shooter's view (CrossbowItem.shootProjectile / getProjectileShotVector). So setting the rotation before calling useItem is enough. Verify this.
4. Bow arrows released by PvP BOT itself:
   - PvP BOT's handleBowCombat snaps the look and releases inside its own tick, BEFORE our late phase. So the arrow would leave along the snapped aim.
   - Fix at spawn: on ENTITY_LOAD of an arrow whose owner is an inhabitant and that was just shot (tickCount 0), re-aim the projectile along the TRACKED aim plus jitter. Call vanilla projectile.shootFromRotation(shooter, pitch, yaw, 0, speed, inaccuracy) with the vanilla bow speed/inaccuracy (read what BowItem used; keep the arrow's speed magnitude, crit flag, pierce and enchant components). Do not invent damage.
   - If the target is not CONFIRMED at that moment, the arrow still flies along the tracked aim. It was a legitimate release; do not delete it.
5. Melee: extend the melee-legality ALLOW_DAMAGE listener (job ml, plus w32b's confirmed-engagement check):
   - an inhabitant's melee hit lands only if the victim is under the bot's crosshair along the TRACKED aim, i.e. the ray from the eye along the tracked look vector hits the victim's bounding box within vanilla reach, not blocked;
   - otherwise vetoed.
   A bot that has not turned to you cannot hit you.
6. Shot from behind, the scenario the user described:
   - hit by an unseen projectile -> the bot knows only the incoming direction (w32b 5-C) -> the tracked aim turns toward it at human speed -> the target enters the view cone -> reaction exposure accumulates (continuous formula; angle factor applies as the target moves toward the centre of view) -> CONFIRMED -> aim settles within tolerance -> fire.
   - Expected minimum at 10 blocks: about 0.33 s turn + about 0.73 s reaction + about 0.1-0.2 s settle, so 1.1 s or more. Assert that no shot happens before that.
7. Config block aggro.aim: { maxTurnDegPerSec 540, fireToleranceDeg (base + distance rule), jitterBaseDeg 0.3, jitterSettleDeg 2.5, jitterSettleSeconds 0.25 }. Validate everything. Document it as human limits, not artificial handicaps: they model a player's hand and eye.

TESTS:
- Unit: rotation rate limit (shortest path, wrap-around at +-180), tolerance, jitter decay, arrow re-aim math.
- Real server:
  - (a) a player shoots a crossbow-armed bot in the back from 10 blocks: the bot's rotation changes by <= 27 deg per tick, and its first shot comes no earlier than the expected minimum, measured;
  - (b) the bot visibly turns: yaw samples show a smooth ramp, not a jump;
  - (c) melee: a player standing behind a sword bot that faces away is not hit until the bot has turned;
  - (d) a bow bot's arrows leave along the tracked aim (compare the arrow direction with the bot's rotation at release).
- Existing harness suite green. Pin Rig facing where tests need it.

Worktree C:\mcw\w32c (branch tmp/w32c) stacked on the w32b result.
- Unit tests: bash /c/mcw/_tools/mc.sh /c/mcw/w32c wrapper test.
- GameTests: bash /c/mcw/_tools/gt_wrapper.sh /c/mcw/w32c /c/mcw/w32c/gt_results.txt <filter>.
- Never run 'gradlew build'.
- Never modify the PvP BOT/HeroBot jars; no mixins into their classes; never touch the user's profile, never deploy, never push.
- Commit with trailer "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>".
