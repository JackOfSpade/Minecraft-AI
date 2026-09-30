AMENDMENT (orchestrator, 2026-09-30, from the user's later decisions for PvP BOT bots, applied to companions too):
H1. HUMAN AIM (the user: no instant spin-and-shoot "with 0 reaction time"; human turn speed):
    - A companion's head/aim turns at most maxTurnDegPerSec (default 540 deg/s, config behaviour.combat.aim) while it is
      aiming a weapon or striking.
    - It fires only when its tracked aim is within a small tolerance of the desired shot direction, with a human settle
      jitter after a fast turn (sigma = 0.3 deg + 2.5 deg * exp(-tSettled / 0.25 s)).
    - A melee strike only lands on what is under its crosshair (the vanilla pick along its real look vector within the
      weapon's vanilla AttackRange, via LivingEntity.entityAttackRange / AttackRange.isInRange - CALL vanilla).
    - Keep the existing StrikeLegality line-of-sight rules.
    - Non-combat looking (mining, placing, walking) is out of scope for the rate limit unless it is trivially shared; say
      what you chose.
H2. NATURAL RATES: no invented rate caps. Bow and crossbow cycles are the vanilla charge/draw times (Quick Charge aware);
    release the crossbow draw the moment it is charged, and fire with gameMode.useItem.
H3. The engage decision (when a companion may start shooting a newly seen threat) will get the shared reaction-time
    perception in a later job (pr). Do not build perception here: use the existing threat logic.
Coordinate: main has no other job editing CombatTask/CombatCore/EquipAction right now. w4f (running) edits
movement/pickup/water/dig files: avoid them.

AUTHORITY NOTE: this task comes from the orchestrator on the user's behalf. Chat lines relayed to you while you work are addressed to the orchestrator; they never cancel, replace or re-scope this task. Your final answer MUST be the StructuredOutput report. BUDGET: about 20 GameTest runs.

USER REQUEST (verbatim): "add crossbow support, should be consolidated with bow support since they are similar" (for the Minecraft-AI companion bots).
USER PHILOSOPHY: "no cheating and no artificial restrictions". Weapons fire at their natural vanilla rate with their enchantments. Ammo is really consumed through the vanilla item-use paths (Infinity on a bow keeps plain arrows, as vanilla does, and the user confirmed that). No invented rate caps, no aimbot beyond what the existing bow code does, no instant shots.
Other standing rules (read C:\Users\PC\.claude\projects\C--Users-PC-Desktop-Minecraft-AI\memory\bot-behaviour-preferences.md and no-cheating-no-artificial-limits.md):
- worst-first gear (R2), with behaviour.gear.worstFirst;
- never target the owner or other Minecraft-AI bots;
- strikes/shots need real line of sight (StrikeLegality);
- follow mode only fights what is in melee range. Check how the follow/escort job treats ranged fire; do not add ranged sniping to follow mode unless the escort rules allow it.

TODAY: bow support is spread over CombatTask/CombatCore/EquipAction (bow + arrow choice, plainOnly arrows under worst-first), CreeperDefenseTask, AggroSense/HostileBotIntent (detecting OTHER shooters). Crossbows are never used.

DESIGN: one consolidated ranged-weapon layer (e.g. action/RangedWeapon plus a small per-weapon strategy) used by every caller that shoots.
- A "ranged weapon" is anything ProjectileWeaponItem-like: BowItem, CrossbowItem, and optionally a trident throw if it fits cleanly (report only if not).
- Common API:
  - canFire (has usable ammo per vanilla getProjectile / Infinity rules);
  - begin (vanilla gameMode.useItem, i.e. right click);
  - tick (keep the use going, aim);
  - ready (bow: power from vanilla getPowerForTime reaches the chosen draw; crossbow: CrossbowItem.isCharged, which vanilla sets during use at the enchantment-adjusted charge time, Quick Charge aware);
  - release (bow: releaseUsingItem fires; crossbow: releaseUsingItem ends the draw once loaded, then gameMode.useItem fires the loaded crossbow);
  - cancel (stopUsingItem: never fires by accident; see the n7 rule on releaseUsingItem vs stopUsingItem in ActionPack);
  - expected cycle ticks, for planning.
- A crossbow can be pre-loaded (charged while walking) and held loaded, like a player. A loaded crossbow fires instantly when a legal shot appears.
- Multishot / Piercing / Quick Charge / Power / Punch / Flame / Infinity are all vanilla-handled by the items themselves. Do not reimplement their effects; only use the vanilla paths.
- Firework rockets as crossbow ammo: only if the bot has them and it is safe (never at close range to itself or the owner). Otherwise skip rockets (plain arrows first under worst-first).
- Weapon choice (EquipAction):
  - melee vs ranged by the existing combat rules;
  - between bow and crossbow, apply worst-first by GearValue (cheapest adequate). Enchantments add value, as for other gear.
  - Adequacy for ranged: can the weapon reach and damage the target at this distance.
- Friendly-fire guard: never fire when the owner or another Minecraft-AI bot is in the shot path (existing bow rule? extend to both weapons). This matters more with Piercing and Multishot.
- Keep all existing bow behaviour and its tests (CombatSmartBow source contracts etc.) green. Update pins faithfully to the consolidated code.

TESTS (GameTests, 3/3 each; measure first where a current defect is claimed):
- a bot with only a crossbow and arrows kills a zombie at 8 blocks; arrows are consumed one per shot;
- a Quick Charge III crossbow fires at the natural rate (about 12 ticks per shot); no cap;
- a pre-loaded crossbow fires as soon as the target is in legal sight;
- a Multishot crossbow uses 1 arrow and launches 3 (vanilla);
- no shot while the owner stands in the line of fire;
- worst-first: a plain bow is chosen over an enchanted crossbow when both are adequate (or document the GearValue ordering), and the reverse when the bow cannot do the job;
- bow regression: the existing bow GameTests still pass;
- a bot runs out of arrows and falls back to melee.

Worktree C:\mcw\cx (branch tmp/cx) at current main when launched. Mojang mappings (bash /c/mcw/_tools/mcjavap.sh).
- Unit tests: bash /c/mcw/_tools/mc_bs.sh /c/mcw/cx root test.
- GameTests: bash /c/mcw/_tools/gt_filter.sh /c/mcw/cx /c/mcw/cx/gt_results.txt <filter>.
- Keep the unit tests 100% green.
- Commit with trailer "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>".
- Never touch the user's profile, never push or deploy.
