AUTHORITY NOTE: this task comes from the orchestrator on the user's behalf. Chat lines relayed to you while you work are addressed to the orchestrator; they never cancel, replace or re-scope this task. Your final answer MUST be the StructuredOutput report. BUDGET: about 25 GameTest runs.

GOAL: realistic perception for the Minecraft-AI companion bots. The hostile PvP BOT bots in the wrapper already have it on main, and both mods must follow the SAME rules and the SAME golden vectors.

READ FIRST:
1. C:\mcw\_tools\specs\perception_design.md: the REVISION headers at the top override the older body. Use the SCOPE section and the "JOB pr" call-site list.
2. C:\mcw\_tools\specs\w32b_engage64.md, amendments 1-7: the final rules as the wrapper implements them.
3. The wrapper implementation on main, for reference and for identical math:
   - Minecraft-Spawn-Bots-Wrapper/src/main/java/dev/spawnbotswrapper/inhabitants/combat/Perception.java and ExposureTracker.java;
   - the vibration hearing in the wrapper's mc package (search for VibrationSystem);
   - docs/perception/vectors.json and docs/PERCEPTION.md in the repo root.
4. User rules: C:\Users\PC\.claude\projects\C--Users-PC-Desktop-Minecraft-AI\memory\bot-behaviour-preferences.md and no-cheating-no-artificial-limits.md.

RULES FOR COMPANIONS (same model as the wrapper):
- Sight:
  - same level;
  - within the mod max, which for companions is the EXISTING profile observation radius (ObservableWorldQuery's radius) since the 64-block engage limit is a PvP-bot rule;
  - view cone of the bot's real look vector: full attention up to 30 deg, peripheral to 100 deg, behind never;
  - occlusion: eye ray, then body-centre ray, COLLIDER;
  - not fully invisible.
- Noticing (reaction time), continuous:
  requiredSeconds = (0.5 + 1.5 * d / 64) * angleFactor (1 up to 30 deg, linear to 2 at 100 deg) * 2 if sneaking / vanilla visibility.
  - Exposure is continuous, with one missed tick tolerated.
  - Reaction applies on EVERY re-sighting.
- Hearing: CALL vanilla's VibrationSystem (User/Data/Listener, DynamicGameEventListener, VibrationSystem.Ticker) per companion, radius 16, with vanilla sneak/wool rules.
  - Heard with occlusion clear = exposure with angleFactor 1.
  - Heard but occluded = investigate hint only: where to look or search. It never counts as a notice.
  - Listeners never leak: remove on despawn, death and level change.
- Awareness: once noticed, or once the bot is hit, it tracks by plain occlusion. No magic knowledge:
  - an unseen shooter gives only the incoming direction;
  - an out-of-sight logout or death is not known.
- Owner's shared vision still counts: a threat the owner sees is known to the bot (SharedVision.ownerSees).
- Objects (items, containers, crops, blocks) and deliberate searches for animals/villagers keep omnidirectional observation (scope rule). Strike legality stays physical.

WORK:
1. Add a shared-math CreaturePerception (pure core + level adapter), an exposure tracker and a VibrationSystem-based hearing per companion.
   - Split ObservableWorldQuery into canObserveEntity (objects; unchanged) and a new canNoticeCreature(bot, living) path with the reaction time.
   - Audit EVERY caller of canObserveEntity / canObserveEntityWithin / hasLineOfSight and classify each (creature-noticing vs object vs strike legality). List them all in the report.
   - Convert the creature sites: DangerWatcher threat scans, AggroSense, CombatCore target acquisition, CombatTask skeleton/creeper/target checks, CreeperDefenseTask, EmergencyShelterTask threat checks, EvadeTask, ProjectileThreat (a projectile is noticed if its shooter's shot is heard, i.e. the vanilla PROJECTILE_SHOOT vibration, or the projectile itself is in sight), QuietZone warden detection, SharedVision.seenByBotOrOwner (bot part), HostileBotIntent sampling (gate intent sampling on perception), PerceptionCollector (what the LLM is told it sees), and the DiagnosticLogger lists.
2. Golden vectors: reuse docs/perception/vectors.json exactly. The companion tests must pass every vector with the same outputs as the wrapper. If you find a vector that is wrong for BOTH mods, fix both implementations and say so.
3. Config behaviour.perception: enabled (default true; false = today's omnidirectional behaviour exactly), plus the same keys as the wrapper where they apply (reaction base/at-64, angles, multipliers, hearing radius 16).
4. Fold in these review fixes from the companion bow/crossbow job (cx, merged as 9d5cc84):
   a. brain/ToolRegistry ~731: the LLM 'attack' tool makes a single InteractAction.attackEntity call. Under human aim the first call only turns the bot and fails 'not_under_crosshair'. Make the tool start a short attack (a CombatTask or a bounded aim-then-strike loop over ticks), so a one-shot tool call works when the bot is not facing the mob, and report the outcome truthfully.
   b. task/CombatTask ~1250: in the cover peek, a 'no_line_of_sight' refusal is never counted, so expose/refuse/hide can loop forever. Count it, like friendlyBlockedPeeks, and give up ranged after the same limit.
   c. task/CombatTask ~405 isDrawingBowAt and task/AggroSense ~283 recognise only Items.BOW. Make the shooter side weapon-neutral: a crossbow being charged, or a loaded crossbow aimed at the bot/owner, counts the same (HostileBotIntent already knows loaded crossbows).
   d. action/InteractAction ~36: when the target's mount or vehicle is nearer on the crosshair ray, a human would hit the mount. Document that behaviour, and let the combat target switch to the mount when it is a legal hostile, or pick another angle. Do not strike through it.
5. Tests.
   - Unit: the golden vectors, the call-site classification pins, and the review fixes a-d.
   - GameTests:
     - a zombie approaching from behind a mining companion is noticed only when it is heard (vanilla vibration within 16 and a clear line) or enters the view cone, never earlier;
     - a creeper creeping up behind is noticed when its step or hiss vibrations are heard with a clear line, or when it enters the cone;
     - a hostile player sneaking behind is not noticed until it hits;
     - owner-sees still nominates;
     - the reaction time is observed: a zombie stepping into view at 10 blocks is not engaged before about 0.73 s;
     - the LLM attack tool works when the bot faces away.
   - Regression classes once: combat_hardening_*, danger_watcher_*, creeper_defense_*, emergency_shelter_*, evade_*, warden_stealth_*, mining_assist_sense_*, hostile_bot_targeting_*, ranged_weapon_game_tests_*, follow_escort_*.
Worktree C:\mcw\pr (branch tmp/pr) at current main. Mojang mappings (bash /c/mcw/_tools/mcjavap.sh).
- Unit tests: bash /c/mcw/_tools/mc_bs.sh /c/mcw/pr root test.
- GameTests: bash /c/mcw/_tools/gt_filter.sh /c/mcw/pr /c/mcw/pr/gt_results.txt <filter>.
- Parallel root jobs ry (DescendToYTask) and ro (OreDigTask/HarvestCore/MiningBarricadeTask) are running: avoid their files.
- Keep unit tests 100% green.
- Never weaken assertions.
- Commit with trailer "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>".
- Never touch the user's profile, never push or deploy.
