# Refactor/bug-fix sweep: paused 2026-09-28 ~21:35 local for a Claude Code restart

All workflows were stopped, orphaned GameTest/Gradle processes killed, and the `.gametest.lock` / `.mcslots` locks
cleared. The user's Minecraft client (javaw, launched 21:30) was left running. No job touched `main`.

## Repo state
- `main` (C:\Users\PC\Desktop\Minecraft-AI) = 7379e36, clean, **104 commits ahead of origin/main (not pushed)**.
  Root unit tests: 2000/2000 green at 7379e36.
- Deployed to the profile at 7379e36 (21:28) with `bash scripts/deploy_profile.sh`. Wrapper jar unchanged since
  8202564 (only a doc changed), so it was not redeployed.
- The three formerly failing GameTests (lava_exposed, inventory_reserve, drop_recovery) PASS on main at 7379e36.

## Jobs (worktree C:\mcw\<key>, branch tmp/<key>, based on main unless noted)
| key | state | brief / spec | next step |
|---|---|---|---|
| r13 | DONE by debugger: 88d9f5b "Gather blocks by what they DROP, and require the optimal tool" (base f8ef45c, older main). Report: specs\r13_debug_report.json (5 new GatherToolPolicy GameTests + 6 gather_pickup tests fixed). Review was just starting. | specs\r13_launched_prompt.txt | independent review, then cherry-pick onto main |
| r10 | WIP c78fbeb (paused mid-job): FollowStuckRecovery + RecoveryClock, two-phase goal snap in Standability/AStarPathfinder, new pathfinding GameTests, FollowTaskGameTests, STOP_DISTANCE 3, tool text 3-5 blocks | specs\r10b_r14_jobs.json jobs[0] | resume debugger ("a WIP commit exists; build on it"), then review |
| r14 | WIP 989a388 (paused mid-job, was running drop_recovery GameTest): SETTLE_DROP no_stand fix, side-effect-free placement probe, BreakPeek re-proof unit tests | specs\r10b_r14_jobs.json jobs[1] | resume debugger, then review |
| r11b | WIP 66ce60e (paused early): two boat mixins (logical side + server-side paddles), BoatFollowTask/FollowTask.followBoat edits | specs\r11b_launched_prompt.txt (root cause: server boats with a player driver never simulate; updatePaddles is client-only) | resume debugger, then review |
| w23 | 3b251ba (shelter helpers shared) + WIP 1c7a114 (OreDig arena sharing) | specs\w23.md | resume implementer (wave workflow), then review |
| r11a | QUEUED until r10 merges | specs\r11_jobs.json jobs[0] (swim follow, oxygen from measured air-loss, scan throttle, verify goal snap over water, dig-out when stuck) | launch after r10 |
| w22 | QUEUED until r10/r13/r14 merge (touches src/test broadly) | specs\w22.md | launch later |

Resume a job: the worktree must exist; the job key is the worktree name. Use repo `.claude/debug-workflow.js`
(args `{jobs:[{key,tests,brief}]}`) or `.claude/wave-workflow.js` (args `{wps:[key],project:{key:'root'}}`).
Prepend to every resumed brief: "A WIP commit (HEAD) holds the previous worker's paused state: read `git show HEAD`,
keep what is sound, finish the job, commit, and end with the StructuredOutput report." Keep the AUTHORITY NOTE.
The scripts pin `model: 'sonnet'` (alias for the newest Sonnet).

## After all jobs merge
1. Full GameTest suite (several runs) + isolated reruns of failures; flake hunt (ore_dig_poi batch, smelt furnace
   placement, collapsed_lateral_detour, three_blocked_directions, descend_lateral_budget were load-flaky).
2. Final adversarial review of `git diff 770ccd1..main`.
3. Docs sync (wrapper README vs code; LOGGING/MINING_ASSIST vs new behaviour).
4. Push main; delete every tmp/* branch and worktree and C:\mcw (only `main` must remain, local and GitHub).
5. Redeploy (game must be closed; ask the user), copy the wrapper jar only if the wrapper changed.

## Resumed 2026-09-28 (after the restart)
- r13 rebased onto main 7379e36 (commit now on top of main); continuation adds the log/wood bootstrap exception
  (hand-gather only the logs needed for a table + wooden axe when no axe can be crafted), drop-index build_ms, tool text.
- Running: debug workflow wf_f8bf98ef-b8d (task w8bieiysv) = r13, r10, r14, r11b; wave workflow wf_830ce2c2-474
  (task wro9i8ryy) = w23. `.claude/debug-workflow.js` now skips the debugger when a job carries `report`.
- w23 MERGED (90a8fff, 86c57b8, f4a68d2 + nits 31b699c); worktree removed.
- NEW user request r15 (sleep): "bots go to sleep in an existing empty bed nearby until next day; no bed -> stop and tell
  user". Worktree C:\mcw\r15 from main 31b699c; debug workflow wf_a97c9638-606 (task w2z8kuybj). Rewrites the existing
  SleepTask (no bed placement, observable/cheap search, occupied/dimension checks, sleep until day, wake on cancel,
  friendly reasons) and excludes AIPlayerEntity bots from vanilla's sleep vote via a mixin (orchestrator decision).
- r15 REDIRECTED by the user: REMOVE the bot sleep feature entirely (SleepTask, tool, command, network action, panel
  button, lang keys, DangerWatcher sleep bits; rename Night.autoSleep -> clearer name reading the old key) and exclude
  bots (AIPlayerEntity or EmbeddedChannel connection = ours + HeroBot/PvP BOT) from vanilla's sleep vote via a mixin.
  Worktree reset to main; debug workflow wf_9511919e-60b (task w12wln42o).
- r15 EXTENDED (user: automatic night torch lighting must not happen on the surface; "I'll let you best design it"):
  surface = column from head to world top has only air/fluids/tree (logs, leaves, vines, cocoa, bee nests, mangrove,
  pale moss, snow on canopy) and mushroom/fungus blocks; both DangerWatcher reflexes skip the surface, automatic
  LightAreaTask skips surface cells, explicit light requests unchanged; Night.autoSleep -> autoLight (legacy key read).
  Relaunched: debug workflow wf_1d85dff3-f30 (task wiobjcsym). Lesson: snapshot a worktree before resetting it.
- QUEUED r16 (user: "bots' lack of sleep should not spawn phantoms"): run AFTER r15's debugger commits, in the SAME
  worktree/branch (key r15) with specs\r16_phantoms.json: MixinExtras @ModifyExpressionValue on PhantomSpawner.spawn's
  getPlayers() filtering bots via r15's shared predicate (no stat edits). Then merge r15+r16 together.
- VeinMiner 2.11.2 (Fabric) INSTALLED in the profile 2026-09-29 (hash verified; read-only config/Veinminer/update file
  blocks its inert update downloads); mod_list.txt committed 6968b60. Bots WILL vein-mine until r17 is deployed and
  "permissionRestricted": true is set in <profile>/config/Veinminer/settings.json.
- Session-log analysis (20260929-013041) + nav audit + Baritone research done. Queued jobs r17, r10c, r18, r19, r20, r21:
  see specs\queued_after_merges.md. Navigation program (Baritone-inspired, clean-room: goal predicates, partial paths,
  time-based costs incl. tool-aware breaking, path favouring, doors/ladders, nav obstacle-course GameTests) awaits the
  user's go-ahead + route-knowledge stance.
- MERGED to main (47e98b7): r13 (gather by drops + optimal tool + wood bootstrap + lazy drop index), r14 (settle drop,
  pure placement probe, BreakPeek tests), r10 (standing-order follow, STOP_DISTANCE 3, two-phase goal snap), r11b (boat
  follow: server-side paddle mixins, water approach, watchdog). Unit tests 2030/2030. Worktrees removed.
- Merge GameTest sanity run on main in background (scratchpad gt_merge1.txt).
- RUNNING: r15 (sleep removal + human-only sleep vote + surface lighting; wf_1d85dff3-f30), and debug workflow
  wf_74f3d477-f96 (task wls5v34q4) = r10c (yaw hijack etc.), r11a (swim/oxygen/dig-out), n1 (review nits), r20
  (logging/perf), r21 (vein mode). Baritone reuse analysis wf_cc199b6e-769 (task w1yyx95qa), sources in
  C:\Users\PC\Downloads\baritone-analysis (baritone 1.21.11, automatone 1.20, playerengine 1.21.10) - delete when done.
- Still queued: r16 (phantoms) + r17 (VeinMiner bot exclusion) after r15; r18 (brain), r19 (danger/survival) after r15.
- USER GATE: do NOT start building the Baritone-based navigator. Present the analysis findings + plan first; the user
  wants to review/monitor before any build work starts.
- Baritone plan written: docs/NAVIGATION_BARITONE_PLAN.md (commit 80daa03). User: "leave this plan there for now".
- NEW user question: port permanently to NeoForge? Assessment workflow wf_fb525a36-41a (task wcbro5ggm): claim
  fact-check, modpack availability on NeoForge 1.21.11, our port cost, critic. Discussion only; no porting.
- NeoForge assessment DONE (wf_fb525a36-41a + wf_ab69a152-fc9): pasted claims mostly false/misleading; permanent port NOT
  recommended (no gain for Minecraft-AI; ~1-2 weeks extra incl. 685 GameTests; no NeoForge 1.21.11 combat fake-player bot
  mod for the wrapper). RECOMMENDED instead: Yarn -> Mojang mappings migration on Fabric (Loom migrateMappings; required
  for 26.x; lets Baritone source compile in-project, removing the cross-mapping spike). Awaiting user go-ahead; run it
  only after in-flight jobs merge. Wrapper backend options on Fabric: PvP BOT (current), VexBot (commands), Carpet.
- USER APPROVED the Yarn -> Mojang mappings migration (root mod AND wrapper: "replace any yarn we use here"), on Fabric.
  Plan: (1) rehearsal now in scratch worktrees C:\mcw\mm (root) + C:\mcw\mmw (wrapper), workflow wf_5c79a4f8-910 (task
  wdlt4g2hk), using _tools/mc_mojmap.sh + root-mojmap/wrapper-mojmap classpath dumps (never touch _tools/root|wrapper
  while Yarn workers run); outputs _tools/mojmap/migrate_{root,wrapper}.sh + manual_fixes_{root,wrapper}.md.
  (2) HOLD new job launches (specs/batch_after_r15.json: t1 vacuous GameTests, r16 phantoms+VeinMiner, r18 brain, r19
  survival) until after the migration; rewrite their briefs for Mojang names. (3) After running jobs (r10c, r11a, n1,
  r20, r21) merge: baseline full GameTest suite on Yarn main, replay the migration on main, full verification, update
  tooling (_tools/root|wrapper dumps), docs, memory (1.21.11 yarn port notes), Baritone plan (drop cross-mapping step).
- DEPLOYED main ef66841 to the profile (2026-09-29 ~01:40 local): r13/r14/r10/r11b/r15/w23 changes live. Infinite
  Inventory 2.0.0 installed (mod_list ef66841). VeinMiner bot exclusion (r16) and phantom exclusion NOT yet built.
- Infinite Inventory 2.0.0 REMOVED again (client crash: mixin built for pre-1.21.9 input API); jar in <profile>/backups/removed-mods/.
- Endless Inventory 1.1.3.3 (Fabric, 1.21.11) INSTALLED in place of Infinite Inventory; per-player autopick default off (bots unaffected).
- NEW user bug r22: bots lose armor/offhand on restart (BotPersistence saves only PlayerInventory main slots; 1.21.5+ equipment is separate). Also audit ender chest, XP, effects, saturation, selected slot. Workflow wf_e8382648-546 (task wbofpfchr), worktree C:\mcw\r22. Merge BEFORE the mojmap migration.
- Mojmap REHEARSAL DONE (wf_5c79a4f8-910): root tmp/mm (362 files, 2044/2044 tests, 8 GameTest filters pass), wrapper
  tmp/mmw (71 files, 1429/1429). Auditor: ready_with_fixes. Hardening + replay on newer main running: wf_7c290a6d-14d
  (task wkeemd0q7), fresh worktree C:\mcw\mm2. Real run follows _tools/mojmap/README.md AFTER r10c/r11a/n1/r20/r21/r22
  merge (baseline ALL GameTests on Yarn main first; full ALL after; class-load probe for LoginTimeout/Merchant mixins).
- r22 MERGED (773bf7c): bot armor/offhand/ender chest/XP/effects/etc persist across restarts. Nits queued.
- DEPLOYED main 773bf7c (r22 persistence). Installed Streams Reflowing 2.14.1; "no oceans" done via Tectonic
  config ocean_offset -0.8 -> -0.1 (No Oceans datapack is a no-op with Tectonic; not installed). mod_list committed.
- Mojmap script hardening DONE (wf_7c290a6d-14d): replay on 7bffc55 passes (root 2044/2044, wrapper 1429/1429,
  gradle build + 11-mixin jar check, class-load probe GameTests pass). Keep C:\mcw\mm3 until the tooling swap (the
  -mojmap dumps reference its loom cache). Follow _tools/mojmap/README.md for the real run.
- MERGED r10c (0d7a13a) + r21 (29cb8de, 2609af6); main 2609af6, 2054 unit tests green.
- PvP BOT "not attacking" investigated (wf_71f004d0-561): user was in Creative (PvP BOT never targets creative);
  ~35-50% inhabitants are Pacifists by wrapper design; 16-block target range; crit-jump gate under low ceilings.
  USER CHOSE: all inhabitants fighters; keep crits with criticalFallTicks 3; ALSO fix wrapper lag governor (too strict
  vs this pack's 53-59 ms normal tick) and add inhabitant combat logging.
- RUNNING wf_47ae44b5-89a (task whgxa2w89): w26 wrapper (all fighters + data migration, crit ticks 3 via startup
  command, adaptive lag governor, combat logging, faster restore settle) + fix rounds for rejected r20 (latch
  thresholds), r11a (wading takeover, pond test, oxygen on exit, dig-out safety, r10c nits), n1 (boat guard test,
  debug residue, lifecycle flake A/B, r21 mine_ore mode validation). All four must merge BEFORE the mojmap migration.
- USER APPROVED Baritone via PRISTINE VENDOR + REPLAYABLE PATCH SERIES + NEW GLUE CLASSES (full capability incl.
  movement execution; "planning only" REJECTED). Upstream kept byte-identical in third_party/baritone (1.21.11,
  2372389); patches in tools/baritone (git-apply series, fail loudly); exclusions by list; built as a source set.
  P0 SPIKE RUNNING on scratch worktrees C:\mcw\bs (vendor/patch/glue/execution) + C:\mcw\bsp (raw-input physics probes),
  both from tmp/mm3 (Mojang-mapped rehearsal): workflow wf_b9c5a8f0-468 (task wtiog5vlv). Upgrade rehearsal sources:
  Downloads/baritone-analysis/baritone-1.21.10 and baritone-26.1. After the real mojmap migration, re-apply the spike
  output (vendor dir + tooling + glue + tests) onto main for P1-P3.
- MERGED r20 (TPS latch reachable thresholds, survey scan spread, audit flush), n1 (boat guard test, drop settle/rest gate,
  bootstrap fixes, mine_ore mode validation, drop index warm-up), r11a (swim follow + oxygen + dig-out + wading fix +
  snap guard + root combat logging; its stray wrapper governor tweak reverted in d18f83e). main d18f83e: root 2141,
  wrapper 1429 unit tests green. w26 fix round running (wf_27684417-5af, task wplzunu82: CombatLedger per-server clock).
- MIGRATION BASELINE running: gt_all.sh on detached worktree C:\mcw\base (main d18f83e) -> _tools/mojmap/baseline_d18f83e.
- MERGED w26 (wrapper: all fighters + v3 data migration, crit-fall-ticks 3 via startup command, adaptive lag governor (new keys; legacy healthyMillis/degradedMillis ignored with a warning), combat log, prompt restore). Deploy notes: back up saves/Brave New World/pvpbot_inhabitants/populations.json (v3 is not readable by the old jar); optionally delete tpsThrottle.healthyMillis/degradedMillis from the installed config.
- MERGED w26 (9581f16). BASELINE (Yarn main d18f83e, full suite): 730 tests, 719 pass, 11 fail -> list in
  _tools/mojmap/baseline_d18f83e/gt_cases.txt; isolated reruns running (-> isolated_reruns.txt).
- REAL MIGRATION RUNNING: workflow wf_07768457-7f4 (task w3w8m1tj8) on C:\mcw\mig (branch migrate/mojmap from 9581f16):
  migrate root+wrapper, unit tests, gradle build, ALL GameTests + diff vs baseline, audit. MAIN FROZEN until merge
  (no new commits to main except the migration fast-forward; queue work in specs).
- Baseline isolated reruns: 10/11 pass alone (full-suite flakes); 1 REAL failure (follow no-route water test) -> queued priority 1 after migration.
- 2026-09-29 ~07:40 MIGRATED: main fast-forwarded to migrate/mojmap (a0452df; audit ready_with_notes). Tooling swapped:
  _tools/root + wrapper dumps regenerated from main with Mojang names (Yarn copies kept as root-yarn/wrapper-yarn +
  mc_yarn.sh, only for the C:\mcw\base Yarn baseline); smoke: root 2141/2141, wrapper 1485/1485; rehearsal dumps and
  mc_mojmap.sh removed; worktrees mig/mm/mm2/mmw + branches removed (mm3 kept: base of the tmp/bs* spike branches).
  New helper: _tools/mcjavap.sh (javap on the Mojang jar + root classpath). Gradle in bash needs JAVA_HOME (the
  java-runtime-delta path from mojmap/migrate_root.sh).
- main fb15a5d (docs Yarn names -> Mojang, Baritone plan rewritten for the approved pristine-vendor design, duplicate
  banned tokens) + f8b0ce7 (modCompileOnly + modLocalRuntime me.lucko:fabric-permissions-api:0.6.1 for the VeinMiner
  hook; dumps regenerated; GameTest server boots with it: probe + sleep_vote pass).
- POST-MIGRATION BATCH RUNNING (worktrees C:\mcw\<key>, branches tmp/<key> from f8b0ce7):
  wave A wf_7ccda661-e43 (task weem4g7m3): p1 (PRIORITY 1 follow no-route water + FollowDigOut/SwimRoute gate + snap/hold
  GameTests), t1 (vacuous succeedIf fixtures), r16 (phantoms + VeinMiner permission hook), r19 (survival 1-9 incl.
  damage-log coalescing and LightAreaTask column memo).
  wave B wf_cec8a5db-594 (task wecgns6z5): r18 (brain), n2 (nits: census equipment, effects replace, adaptive root latch,
  HarvestCore fmt, drop-index bounded warm-up, wrapper CombatLedger stop race), g1 (gather axe bootstrap broke 4 logs).
  NEXT after merges: f1 full-suite flake hunt (baseline 11 + migrated 6 lists in _tools/mojmap), re-apply Baritone spike
  (wf_b9c5a8f0-468 on bs/bsp from tmp/mm3) onto main, P1-P3, deploy (+ veinminer permissionRestricted=true,
  populations.json backup, wrapper jar), final review, push, cleanup.
- MERGED wave B onto main 36a9e72: r18 brain (a1415ba), g1 gather bootstrap pickup (13a9369), n2 nits (0515483 9a9ff64
  319eb1a 36a9e72). All approve_with_nits. Nit follow-up RUNNING: b2 wf_e9684a7c-b7f (task wmw5ho7wk) on C:\mcw\b2
  (brain request-started flag / silent-drop path, latch residual re-learn, LOGGING.md, gather contract + slack).
  Verification of main running (task b2nqrxgx6 -> _tools/verify/waveB.txt).
- main 36a9e72 verified: root 2187, wrapper 1487 JUnit green; tool_registry_mining 8/8, bot_persistence 4/4,
  mining_assist_sense 14/14; gather_tool_policy 9/10 (break_blocks_with_carried_crafting_table_needs_fewer_hand_breaks
  'broke 3, max 2' - older intermittent, also failed on n2's branch) -> job g2 RUNNING wf_0409df34-636 (task wc748pej1).
- MERGED b2 onto main db54dd1 (8b627e3 brain InstructionRoundEvaluator/request-started, 3cf227d latch re-learn,
  db54dd1 gather contract + hand bound 3). approve_with_nits; QUEUED minor nits for the next nit round:
  BrainCoordinator hasInstruction uses never-cleared lastInstruction (autonomous wake may apologise for an old
  answer-only instruction) - track whether the call chain belongs to a player instruction; failure-report round clears
  withholdSay (save/restore); InstructionRoundEvaluatorTest tautological assertion.
- g2 done (fde6951: KnownCellPickupSweep for hidden drops beyond reach; approve_with_nits). Orchestrator rebased tmp/g2
  onto main db54dd1 (-> 2219e0d, resolved task-start reset conflict); 2 b2 contract tests fail as expected.
  g2 FIX ROUND RUNNING wf_9767fe6e-a8f (task wkdmxq6ex): reconcile contracts, no sweep while chasing a visible drop,
  step() enum + honest approach log, optional bootstrap integration GameTest.
- WAVE A done (wf_7ccda661-e43). MERGED p1 (string-pull column traversal, real no-route fixture, snap/hold GameTests,
  follow_no_dry_route notice) + r16 (PhantomSpawnerHumansOnlyMixin, PermissionsIntegration veinminer.use) -> main b4b4257.
  t1 RUNAWAY server (boat_follow chunk-unforce hang, 1.3 GB log) + 2 duplicate queues stopped by orchestrator.
  gt_filter.sh now has a WATCHDOG (run_watched: 25 min / ALL 120 min / 300 MB log -> taskkill tree, HUNG line) and a
  dead-owner stale-lock check (old copy gt_filter.sh.pre-watchdog).
  t1 (approve_with_nits + MAJOR dropped reseal/rotation/timeout coverage) and r19 (REJECT: regen-stall eats harmful food)
  rebased onto b4b4257 (clean, unit green). FIX ROUNDS RUNNING wf_37d34716-acf (task wlx0pdvx7): r19, t1, n3 (p1/r16/b2
  nits incl. brushed-column dryness, no-route notice, mutation checks, hunt/gather/ore_dig globs, VeinMiner WARN,
  lastInstruction). g2 fix round wf_9767fe6e-a8f still running.
- MERGED g2 (c1a7777 + ad17219 KnownCellPickupSweep, no override of a chased visible drop, Step enum, bootstrap
  integration GameTest) -> main ad17219, root 2224 green. QUEUED nits (next nit round n4): KnownCellPickupSweep ~line 82
  dwell ignores approachKnownPickupCell's return (path may start while reporting DWELLING); GatherPickupGameTests
  bootstrapPickupSweepsForHiddenRestingDrop should assert gather_bootstrap_origin_sweep (prove the sweep, not a visible
  chase); regex-heavy GatherExactBreakSourceContractTest (optional pure test of target order).
- BARITONE P0 SPIKE DONE (wf_b9c5a8f0-468): critic go_with_conditions. 14 patches / 33 files (+131/-264), vendor
  byte-identical, headless server boot OK, planning 7/7, navigation 14/14 end-to-end, physics within 0.01% (4 server gaps
  closed in glue), upgrade rehearsal 12/14 patches clean on 1.21.10 and 26.1. Holes: break/place policy, X-ray scanning
  processes, NavSafetyNet swim hijack, disabled capabilities (parkour, water bucket, vines, chunk caching), no
  production-jar boot. Hung bs2 GameTest (running since 07:02 alongside everything) + queues stopped.
- TRANSPLANT: tmp/bx (C:\mcw\bx) = main cef0096 + 18 spike commits (JSON unions only) + stray gt_results removal;
  base ref tmp/bx-base (3e28d54). root-bx dumps (dumpcp_bs.init.gradle); mc_bs.sh takes MC_CPDIR. unit 2242/2243
  (BaritoneVendorIntegrityTest upstreamNotes... fails), btest 41/41. main cef0096 also ignores gt_results*/gt_logs.
  debug-workflow.js now supports per-job testCmd and base.
- BARITONE P1 + PACK-COMPAT RUNNING wf_c6f553c4-764 (task wbj10ktul): bx (verify/hygiene/probe port/docs/jar check),
  bx2 (strict-survival policy hook, disallow lists, perception on placements, rightclick/windowClick guard, no scanning
  processes, negative GameTests), bx3 (navigation.engine switch default legacy, fail-soft lazy bootstrap, ActionPack
  seam, follow GoalNear, NavSafetyNet lease), pc1 (GameTests with the profile's mods loaded via -PgametestExtraMods /
  GT_EXTRA_MODS). Merge plan: bx -> bx2/bx3 onto it -> main after pc1-style run with Baritone. P2 later: obstacle-course
  comparison legacy vs Baritone + re-enable parkour/water bucket/vines/etc. under test; P3 cutover default.
- MERGED t1 (8: GameTestCleanup listener, runLocked fixed, shelter regen-stall product fix, paused mission kept,
  breach-exit coverage, GameTestChunkForcing, cleanup self-tests), n3 (5: brushed-column dryness, genuine no-route
  notice, VeinMiner WARN, InstructionChain, phantom doc) and r19 (4: safe-food regen stall, SAFETY deferral, damage-log
  coalescing, leash, tool/armor nits) -> main e273e6b, root 2247 green. All approve_with_nits.
- NEW REGRESSION found by n3: ore_dig_poi_game_tests late_verdict_is_notify_only + player_pause_during_hold_is_never_
  overridden fail on main (passed on d18f83e and a0452df). RUNNING wf_d4139a03-d1b (task wtephrpyt): q1 (bisect a0452df..
  b4b4257 + fix) and n4 (nits with orchestrator decisions: no poison food ever, dead pressure-timeout call removed,
  force-only chunk forcing, GOAL_NOT_STANDABLE announces, withholdSay kept on failure injection, sweep dwell MOVING).
- USER ASKED (while waiting): what non-navigation parts of Baritone (and family) can replace/improve ours, e.g. combat.
  RUNNING analysis workflow wf_62dbd876-09e (task wst26o9dr), read-only: 9 domain analysts (combat, survival,
  resources, crafting, building, farming, equipment, memory, architecture) over Baritone 1.21.11 + PlayerEngine
  1.21.10 (AltoClef/ChatClef server port: MobDefenseChain, MLG bucket, FoodChain, item catalogue...) + Automatone,
  play-log weakness miner, 2 skeptics per candidate (better / legality+cost), synthesis, completeness critic.
  Report to the user with a recommendation; do not build before they choose.
- MERGED q1 (d3347a0: ore_dig_poi stub advisor latch-gated, BotLogWriter.awaitDrainedForTest; cause = wall-clock
  sleeps vs ~1000 tick/s headless server, not a product regression) + n4 (b64f44a, a8645f1: poison food never eaten via
  consume effects, deferral scoped to player chains, budgetEndWork helper, force-only chunk forcing, sweep reapproach,
  GOAL_NOT_STANDABLE genuine) -> main a8645f1, root 2255 green.
  QUEUED tiny nits (n5): BotLogWriter.forceOverflowForTest must bump enqueuedCount; OreDigPoiGameTests HOLD_TICKS 10 ->
  ~50; SafetyTaskActiveException text must not promise a deferral when none was recorded (autonomous rounds);
  InventoryAction.HARMFUL_FOODS trim to CHICKEN/ROTTEN_FLESH; FollowNoRoute GOAL_NOT_STANDABLE should persist
  (RepeatedFailures) before announcing; GameTestCleanup javadoc wrap.
- n5 RUNNING wf_b7fe77a1-9ca (task wilhng5d9) on C:\mcw\n5 (queued tiny nits above).
- P1 DONE (wf_c6f553c4-764, all approve_with_nits): bx (UPSTREAM.md tracked, 11-probe physics suite, docs, jar check
  OK: 349 baritone classes intermediary, 19 mixins), bx2 (BaritoneBreakPlacePolicy, patch 0015, BaritoneGoals,
  strict-survival GameTests), bx3 (nav.engine legacy|baritone default legacy, NavEngineSelector fail-soft, ActionPack
  seam, BaritoneNavigator, follow GoalNear, water lease; 24 engine GameTests), pc1 (pack harness -PgametestExtraMods /
  GT_EXTRA_MODS: 52 pack mods, our 12 mixins OK, VeinMiner respects the bot denial). pc1 merged to main (89184ca).
  Combined tmp/bx = main + transplant + bx + bx2 + bx3 + 86bdb24 (announceNoRoute arg); base ref tmp/bx-base2;
  unit 2306 green, btest 41/41.
- BARITONE FIX ROUND RUNNING wf_48c3579a-dcc (task wkpx0ose8): bxa (fail-soft after live, hook gating, settleRoute,
  lease/route release, replaced routes, follow no-progress/timeout, refusal cap + staircase test, PalettedContainer
  mixin lazy (else startup crash with pack!), @Redirect -> @WrapOperation, ItemStack hash gating, conclusive lazy test,
  PACK-COMPAT run with Baritone) and bxb (openable click, BREAK_VERDICT reload, X-ray source scan, README/plan docs,
  exact fall-damage probe).
- QUEUED deploy-prep job: deploy_profile.sh must set VeinMiner permissionRestricted=true when VeinMiner is installed
  and warn/refuse if Physics Mod collapse=true (config/physicsmod/physics_server_config.json); mod_list.txt notes;
  packrun/ in .gitignore; back up pvpbot_inhabitants/populations.json; build+copy wrapper jar.
  QUEUED end-of-sweep: vendor reusable dev tooling (gt_filter.sh w/ watchdog + pack env, mcjavap.sh, dumpcp init,
  union_json_conflict.pl?) into scripts/dev before deleting C:\mcw (docs reference /c/mcw/_tools/gt_filter.sh).
- MERGED n5 (7d5387f -> main) + javadoc fixups; n5 worktree removed.
- REUSE ANALYSIS DONE (wf_62dbd876-09e, 164 agents; full data in _tools/specs/reuse_analysis.json). Verdict: combat -
  ours already stronger/observation-gated, little to port; real value = bugs/gaps it exposed in OUR code + small ideas.
  RUNNING wf_80fc23d9-470 (task w34ejbkmy): fm (farm visibility/OUTLINE, no setBlock till/plant/water/milk, walk
  pickup, bone meal), ob (shape-aware observation face points, sneak-placing, match-first scan order), ct (container
  ledger + tool, no remote peeking, storage-target rules, junk/keep deposit policy), cb (DPS weapons + spear/mace,
  shield stall bug, guard/warden/creaking/Enemy safety, legal strike reach+LOS, friendly fire, creeper shield fallback,
  skeleton-draw shield, ranged option, strafe footing), bm (conversation summary + persistence, log retention by bot
  activity), sv (food reserve/choice, fire reflex, powder snow, eating watchdog).
  AFTER tmp/bx MERGES: ph job (fall damage parity every tick: bots take NO fall damage today; fake-player knockback
  (Automatone PlayerEntityMixin.cancelKnockbackCancellation idea); combat FakePlayerMotion teleport steps -> real inputs
  (decision: real inputs, user can veto); legacy break/place cadence parity (vanilla destroy delay)); mb job (shared
  tag-driven natural-terrain break rule for legacy diggers + Baritone; Baritone autoTool -> ToolSelector
  (assumeExternalAutoTool, useSwordToMine=false, itemSaver=true) + GameTests).
- BARITONE FIX ROUND DONE (bxa cae4acd/91986f8, bxb 779d85b; approve_with_nits). PACK-COMPAT WITH BARITONE: mixins 6/6,
  baritone_server 8/8, navigation 14/14, engine 30/30, follow 10/10 with 45 profile jars. Combined + rebased:
  MAIN FAST-FORWARDED to bead24b = whole Baritone layer (nav.engine default legacy). unit 2341, btest 41/41.
  Worktrees bx/bxa/bxb/bs*/mm3 and branches removed. NEW TOOLING: _tools/root-main dumps (dumpcp_bs from main checkout);
  mc_bs.sh defaults to root-main -> use `bash /c/mcw/_tools/mc_bs.sh <wt> root test|btest` for trees WITH Baritone;
  plain mc.sh + root/ dumps only for older pre-Baritone branches (fm ob ct cb bm sv were branched before bead24b:
  when merging them, rebase onto main and verify with mc_bs.sh). Smoke GameTests on main queued (task b03pnkzl9 ->
  _tools/verify/baritone_main.txt).
- RUNNING wf_1cd66b45-b20 (task w0lfyviji): ph (fall damage parity every tick + fake-player knockback) and mb (Baritone
  autoTool -> ToolSelector (+patch 0016 ToolSet hook), shared tag-driven natural-terrain break rule for all diggers,
  legacy 5-tick destroy delay, 8 Baritone review nits). QUEUED after cb merges: ph2 = combat FakePlayerMotion teleport
  steps -> real inputs; placement cadence (4 ticks) in BuildAction after ob merges.
- Smoke on main bead24b: mixin probes 6/6, baritone_server 8/8, baritone_engine_follow 10/10, follow_task 12/12, legacy-lazy proof (alone, conclusive) 1/1 - all PASS.
- ANALYSIS JOBS DONE (wf_80fc23d9-470, all approve_with_nits). MERGED to main ae95614: bm 68dc7a4 (conversation memory
  + log retention), sv fdf339f (food reserve, fire reflex, powder snow, eat watchdog), cb b5a950d (combat hardening),
  fm ae95614 (farming legal + outline visibility + pickup + bone meal). unit 2381 green (mc_bs).
  ct rebased (e53b98d, unit 2390); ob re-applied on main as uncommitted changes with conflicts (orig ref tmp/ob-orig).
- RUNNING wf_aa8e23d0-769 (task w44dixunf): ob (resolve conflicts; shift only on our placement paths - Baritone door
  clicks untouched; outline-observability semantics per caller group + design doc; ore_dig_pickup flake A/B; nits),
  ct (full-ledger demotion MAJOR + 10 nits), cb2 (combat teleport steps -> real inputs + 7 combat nits), n6 (fm/sv/bm
  nits incl. memory block moved out of the system prompt). Still running: wf_1cd66b45-b20 ph + mb.
- DEPLOYED 2026-09-29 16:43 (user request, playing without Minecraft-AI bots): Minecraft-AI jar from main ae95614
  (deploy_profile.sh) + wrapper jar from main (w26 fighters/crits/governor/combat log + CombatLedger fix). Pre-deploy
  pack check (_tools/deploycheck: 45 pack jars + new wrapper): mixin probes 6/6, phantom 1/1, sleep vote 1/1,
  VeinMiner pack 1/1, persistence 5/5. Profile: VeinMiner permissionRestricted=true; pvpbot_inhabitants.json old
  tpsThrottle.healthyMillis/degradedMillis removed; Physics collapse=false (unchanged). Backups:
  <profile>\backups\pre-deploy-2026-09-29-1640 (old jars, VeinMiner settings, wrapper config, populations.json v2).
- TOOLING: gt_filter.sh waits while javaw.exe (the user's client) runs; mc.sh/mc_bs.sh use 1 slot while it runs.
- MERGED ph (d157768: vanilla fall damage every tick + player-melee knockback mixin BotMeleeKnockbackMixin) and mb
  (dc6aaf9: Baritone tool policy + patch 0016 ToolSet host hook, shared mining/BreakRule, 5-tick destroy delay, Baritone
  nits) -> main dc6aaf9 (AIPlayerEntity conflict: mb structure + ph checkFallDamageOnce on the legacy path).
  unit 2386, btest 41/41. NOT deployed (deployed = ae95614).
- RUNNING wf_e82d74d1-760 (task wlzf6mb8b): pm (teleport resets fallDistance MAJOR, projectile knockback test, raw ore
  blocks natural, obsidian/plain ice refused for channels, dig-through cadence+rule, protected contract literal
  restored, body-clear exempt documented, lazy ToolSet snapshot, docs). Still running wf_aa8e23d0-769 (ob, ct, cb2, n6).
- MERGED ct (c9630d7, 3cfa00e), n6 (d7fad68, f85a3bf, 1dfef96), ob (f1d4184, 8e82f5e), cb2 (5f313aa) -> main 5f313aa.
  unit 2429, btest 41/41. All approve_with_nits. Known red on main: ore_dig_poi_game_tests_warden_risk_always_stops
  (since cb b5a950d: warden within ~17 blocks = hostile pressure) -> n7 item 1.
- RUNNING wf_fbd076f0-78a (task wmp8en1x4): n7 (warden POI fixture + both behaviours pinned, stepByInput item-use x0.2,
  cover-phase bow suppression, ob comments/docs I2 + 3.1 mirror, ct box/player-named junk target, n6 corridor fall
  column + GameTests, irrigate reposition count, LOGGING wording, autonomous-wake memory). pm still running.
  QUEUED: ph2 general item-use slowdown (x0.2) + sprint hunger gate in ActionPack/BotInputBridge (after pm merges);
  flake hunt (full suite on final main); Baritone P2 (obstacle courses legacy vs Baritone, restore parkour/water-bucket/
  vines/chunk caching under tests) then P3 default flip only if at least as good; vendor dev tooling into scripts/dev;
  final adversarial review since 770ccd1; final full suite; push; cleanup; deploy (ask user to close the game).
- MERGED pm (e253a75: teleport overrides reset fall distance, arrow knockback test, raw ore blocks natural, plain ice
  refused, dig-through delay + execution-time BreakRule, protected contract literals restored, body-clear exempt, lazy
  ToolSet snapshot) -> main e253a75, unit 2433, btest 41/41. QUEUED pm nits: in-tick teleport fallChecked test; route
  refusal test asserts the refusal/replan; PrivilegedBoundarySourceTest reject '.teleport(' except super; BlockMiner
  sticky natural-only mode hardening.
- BARITONE P2 RUNNING wf_cefeeff5-3d9 (task wc7ajlqn2): c1 (17-course suite legacy vs baritone, metrics table
  docs/NAVIGATION_COURSES.md, measurement only) and c2 (parkour, water-bucket fall with pickup, vines, observed-only mob
  avoidance behind config; chunk caching stays off). n7 still running. Then: gap fixes from c1, P3 default decision,
  ph2 (general item-use slowdown + sprint hunger gate, after n7), flake hunt, final review/suite/push/cleanup/deploy.
- n7 REJECTED (blocker: giveUpBowForFriendlyLine/coverHide used releaseUsingItem = FIRES the drawn bow at the friend).
  Fix round RUNNING wf_2198f59a-732 (task wpux8yjii) in C:\mcw\n7 (cancel via stopUsingItem, audit all
  releaseUsingItem, friend-on-line GameTests, slowed-step timeout). Deployed jar ae95614 does NOT contain cb2/n7 code.
- USER REQUIREMENTS 2026-09-29 evening (R1-R5): R1 target hostile bots (PvP BOT) that aggro onto the player/our bots and
  are in LOS of either (still never the owner/our bots); R2 worst-first gear (tools/weapons/armor, enchantments
  counted, lowest that can do the job); R3 never fight wardens, sneak away; R4 follow: only hit things in melee range
  while continuing to follow, natural walk/sprint pace (distance, match player pace, careful in ancient cities, always
  sprint when hostiles aggro'd on player/bot/our bots); R5 remove ALL micro-teleports (unnatural).
  DESIGN RUNNING wf_19583223-f82 (task wa23dh0lz): 5 mappers -> design -> 2 reviewers -> revision -> job plan.
- USER DECISIONS on the R1-R5 design (override the design workflow's proposals where they differ):
  R3 wardens: RUN (sprint) while the warden is chasing/charging the bot, then SNEAK away once it stops chasing; never fight.
  R2 gear: ALWAYS cheapest/worst that can do the job (tools, weapons, armor; enchantments add value) - NO escalation to
     best gear when hurt/in danger; predictable; the player overrides by taking items out of the bot's inventory.
  NEW R6: auto-eat when hunger drops below the level at which the bot can sprint (vanilla: sprint needs food > 6).
  R4 follow logic doubles as RETREAT movement (retreat = same movement/pace + only knock back what is in melee range).
  Panel teleports (BotTeleportC2S TO_AI/RECALL_AI, old feature since the initial commit, keys unbound by default) are
     MANUAL_TELEPORT-gated and disabled in strict_survival: leave as is (user-triggered, not micro-teleports).
- MERGED n7 (8 commits incl. ebaa964: bow give-ups cancel instead of firing; ActionPack.stopAll no longer fires a drawn
  bow; warden POI fixture + evade case; corridor fall column; named junk chest; docs) -> main. QUEUED nit: helper that
  releases only a shield (else stopUsingItem) at CreeperDefenseTask.endShield / CombatTask.block / lowerReactiveShield /
  CombatCore.strikeIfReady.
- MERGED c1 (48cf987: 19 navigation courses x 2 engines + docs/NAVIGATION_COURSES.md) and c2 (a7b7c37: nav.baritone
  parkour/parkourAscend/parkourPlace/waterBucketFall(maxBucketFall 12)/vines/mobAvoidance, default on; patch 0017)
  -> main a7b7c37, unit 2445, btest 41/41. COURSES: Baritone >= legacy on all 19 (12 faster, 2 equal, 5 legacy
  failures: door, gate, ladder, cactus damage, sealed-lake wandering). Watch: route churn (41 starts/421 ticks on a
  moving target), cost unmeasured, no bridging/pillaring courses (placed=0).
  QUEUED P2 nits: measurement tests must still assert safety invariants; moving-target bound ~6.5; MOVE courses must
  prove Baritone drove (hasBaritoneRoute); P3 paragraph qualified; dead code; chunk unforce restore; bucket fall
  health guard (plan only if hp > expected fall damage) + refusal-during-fall event; narrow the empty-hand click
  bypass; recover() must verify the ray hits the placed water + tests; refusal-code tests; tighter avoidance cost bound.
  P3 DECISION PENDING: flip nav.engine default to baritone after the R1-R6 work (pace policy must exist in both
  engines) + a cost measurement of admission/churn + pack-compat run.
- DEPLOYED 2026-09-29 (2nd): Minecraft-AI jar from main a7b7c37 (pack boot check 7/7: mixin probes 7, fall+knockback 4,
  phantom, sleep vote, VeinMiner pack, persistence 5, baritone_server 8). Wrapper unchanged since ae95614 (not
  redeployed). Backup <profile>\backups\pre-deploy-2026-09-29-2.
- DESIGN for R1-R6 DONE (wf_19583223-f82): full spec in the task output (jobs foundation -> aggro-core+pace-core ->
  warden/gear/follow-escort/r5-core-paths -> r5-* -> r5-finalize). Needs orchestrator edits for the user's decisions.
- R1-R6 BUILD STARTED. Spec: _tools/specs/r1_r6_design.json + overrides _tools/specs/r1_r6_user_decisions.md (no gear
  escalation/provenance/mission floor; warden sneak/sprint/sneak; retreat = follow logic; no path-correction teleports in
  any profile; R6 auto-eat at food <= 7). WAVES (short worktree keys): W1 f0=foundation (RUNNING wf_467bf1bd-90e, task
  wc3frd2pe) -> W2 ag=aggro-core + pc=pace-core -> W3 gr=gear-worst-first, fe=follow-pace-escort (+R6 auto-eat),
  rc=r5-core-paths -> W4 wd=warden-sneak-away (+retreat via FollowEscort; now depends on fe), rf=r5-follow-water,
  rs=r5-shelter, rp=r5-pickup-water, rd=r5-digdown -> W5 ry=r5-descend, ro=r5-oredig -> W6 rz=r5-finalize.
  Merge each wave to main before branching the next. Then P3 default flip + P2 nits + flake hunt + final steps.
- USER REPORT (playing): hostile PvP BOT next to the user never attacks; crossbow reload starts/stops repeatedly.
  DIAGNOSIS: PvP BOT BotUtils cobweb escape throws an ender pearl horizontally ('player <bot> use once', pitch 0) EVERY
  tick while webbed; in cramped places it lands back in the web; each retry switches the hotbar slot -> cancels the
  crossbow charge; ends only when pearls run out (92 msgs/5 s in latest.log). Pearls are used ONLY by that escape.
  Our LoadoutRoller stocks 0-8 pearls (+ optional water bucket, 0-16 cobwebs). FIX job w27 RUNNING wf_154b5053-833
  (task w2hs0is3a): no pearls in loadouts + strip pearls from existing inhabitants (restore + periodic sanitize).
  Deploy the wrapper jar when it lands (the user wants this for play).
- MERGED w27 (8fcf635: no ender pearls in loadouts; ProfileApplier strips pearls at dressing; BotRoster sweeps online
  inhabitants after restore and every 100 ticks; wrapper 1497 green). Pack boot check (new wrapper) 2/2 PASS.
  DEPLOYED wrapper jar 20:49 (backup <profile>\backups\pre-deploy-2026-09-29-3-wrapper). QUEUED w27 nits: log
  dressing-time pearl removal once per bot (README claim), sweep tests assert INFO-once/debug and failure containment.
- USER: the pearl fix did not fix the crossbow bot (it is on a land-locked ship, on planks, not in webs); keep pearl
  change (it fixed a real web loop: 9 bots took 'attack normally' path). User asked for LOGGING first, will hit the bot
  again. w28 RUNNING wf_0d660b26-86f (task wn6n4n8s1): wrapper logs damage TAKEN by inhabitants with a state snapshot
  (slot, main/offhand, using item + ticks, crossbow charged, arrows/rockets, LOS, PvP BOT target if readable) + a
  ranged-cycle detector (>= 3 aborted bow/crossbow draws in 10 s near a player -> WARN 'ranged loop'). Deploy the wrapper
  jar right after (game must be closed).
- WAVE 1 foundation MERGED (b98bcf0 + d2bd23f: snap counted as CORRECTION). main d2bd23f, unit 2474.
- WAVE 2 RUNNING wf_68f7ed75-83a (task wf2fxvtq1): ag=aggro-core (+RecentDamage lethal hit, ownerFor test), pc=pace-core.
- MERGED w28 (710b412 + e6da4ec: 'Combat taken:' lines with state snapshot; 'ranged loop:' WARN detector; taken line
  logged before the ledger). Wrapper 1531 green; pack boot check PASS. DEPLOYED wrapper jar 21:26 (backup
  pre-deploy-2026-09-29-4-wrapper). NEXT: user hits the ship crossbow bot -> read latest.log 'Combat taken:' /
  'ranged loop:' lines, diagnose, fix. QUEUED w28 nits: mark adapter-default capability values in the snapshot; global
  lines/minute cap for 'Combat taken'; ENTITY_LOAD shot attribution nit.
- 2026-09-29 21:55 USER: bots only shoot after being hit; then 'machine gun' crossbows; shots unblockable; "confirm not
  cheating"; "64 block range too far, change to 10; will they come if I hit them outside the range?".
  ROOT CAUSES (decompiled PvP BOT in C:\mcw\_tools\pvpbot_src): R1 BotEquipment.autoEquip every checkInterval=20 ticks
  re-selects the melee slot -> vanilla cancels every bow/crossbow draw (26-tick draw cycle in log, durations 1-19);
  R2 PvP BOT 'fires' a loaded crossbow with releaseUsingItem = no-op in 1.21.11 -> never shoots; R3 after the user
  swings within 4 blocks, auto-shield (totemPriority, main-hand shield) sends HeroBot 'use continuous', combat re-selects
  the crossbow -> vanilla right-click spam at Quick Charge III rate (~0.55 s/shot); Piercing ignores shields (vanilla
  LivingEntity.applyItemBlocking). NOT cheating: vanilla item-use path, vanilla damage/enchant levels; instant aim is PvP BOT.
  Revenge is capped by maxTargetDistance too (hit from > 10 blocks: they don't come; they remember the attacker 30 s).
  APPLIED NOW to <profile>\config\pvpbot\worlds\Brave New World\settings.json (backup
  backups\pvpbot-settings-BraveNewWorld-2026-09-29-2150.json): maxTargetDistance 10, ranged 6/8/10, autoEquipWeapon false.
  w29 RUNNING wf_d503ffdf-27b (task w79r3xcb6), spec specs\w29_ranged_fix.md: wrapper GameTest harness with PvP BOT +
  HeroBot, pvpbotSettings policy, crossbow trigger + 26-tick pacing via item cooldowns, 'Combat taken' fix, getTarget.
  Deploy the wrapper jar when merged (game closed).
- 2026-09-29 22:10 USER: "come after you for up to 64 blocks (the default). I just want the aggro range to be 10";
  "piercing is OP on a hostile pvpbot, disable it". DESIGN: PvP BOT maxTargetDistance 64 (chase/revenge/forced limit)
  + autoTargetEnabled false; wrapper AggroController acquires players within 10 blocks WITH line of sight and hands
  them over via BotCombat.setTarget; disengages its own forced targets (logout/death/dimension/> 64/600 ticks unseen).
  RUNNING wf_8f0f32dc-9ed (task wql85d64f): w30 = disabledEnchantments (default minecraft:piercing) at roll + sanitize
  existing/stored (+ w27 nits); w31 = AggroController (specs\w30_no_piercing.md, specs\w31_aggro_range.md).
  AT MERGE (after w29): set w29 pvpbotSettings defaults to maxTargetDistance 64, autoTargetEnabled false, ranged
  8/12/16 (fights at ~8-10 blocks, starts shooting at 16), autoEquipWeapon false; then queue harness GameTests for aggro
  (acquire at 9.5 not 10.5, chase to 64). Until deployed, the world file stays at maxTargetDistance 10 + autoTarget on.
- 2026-09-29 22:20 USER REVISED aggro rules: aggro only within 10 blocks AND line of sight; a hit from outside 10 also
  starts a chase; chase ends at 32 blocks from the engagement origin OR 10 s without sight; then WALK back to the origin.
  Stopped wql85d64f; relaunched w30 (same spec, continue uncommitted work) + w31 (spec rewritten: engagements with
  origin, leash 32, loseSight 200 ticks, clearTarget, return via BotNavigation after PvP BOT's tick, no teleports) as
  wf_bf503916-1f2 (task wtrzrfsiw). AT MERGE: w29 pvpbotSettings defaults -> maxTargetDistance 64 (ceiling only),
  autoTargetEnabled false, ranged 8/12/16, autoEquipWeapon false; then harness GameTests for aggro/leash/return.
- 2026-09-29 22:30 USER refined aggro: a hit (or regular aggro) while RETURNING gets a TEMPORARY origin (leash 32 /
  sight 10 s from there) but the bot always returns to its FIRST (home) origin, then clears it; acquisition while
  returning is allowed (regular 10 + LOS); hits beyond PvP BOT's 64 ceiling: leave. Spec ready:
  specs\w31b_aggro_origins.md -> launch as w31b stacked on tmp/w31 when w31 lands (do NOT restart w31 again).
- 22:45 MERGED w30 (72bdaa9 5523fd0 249475c: profiles.disabledEnchantments default piercing; sanitize at dressing +
  restore + 100-tick sweep; stored profiles filtered at apply; w27 log nit). Wrapper 1556 green on main. QUEUED w30 nits:
  rename sweepPearls/PEARL_SWEEP_TICKS -> inventory sweep after w29/w31 merge; McBotGateway import order + drop pending
  maps on roster removal; note that stored profile display may list stripped enchantments.
- w31 DONE (tmp/w31 1be814c 1e1dc9a, review approve_with_nits, 1617 green); w31b RUNNING wf_7678724a-a55 (task
  wxat7fa80) stacked on tmp/w31: home anchor + temporary origins, acquisition while returning, review fixes R1-R3.
- WAVE 2 DONE: ag MERGED on main as 7850176 (root 2482 green). QUEUED ag nits: RecentDamage LETHAL_AMOUNTS stale on
  vetoed death; ledger marks while hostileBots disabled never pruned; botsOf allocation per entity per tick; SharedVision/
  AggroSense caches never pruned for despawned bots; intent sampling not gated on vision; action->task package cycle +
  double raycast. pc REJECTED (Baritone route lease dropped by yieldToBaritone; weak lease tests; deadline credit
  uncapped; mission food budgets unmeasured; twobots +29/house +12 ticks).
- tmp/w3base = main 7850176 + pace edd3120. WAVE 3 partial RUNNING wf_4c86682c-f78 (task w77ec3ri2): pc2 (pace review
  fixes, worktree C:\mcw\pc2) + gr (gear worst-first per user decisions, C:\mcw\gr). After pc2 approves: merge
  edd3120 + pc2 commits + gr to main, then launch fe (follow-pace-escort + R6 auto-eat) and rc (r5-core-paths).
- 23:05 MERGED w31 + w31b on main (621b90c 08cd7ef e5a842b): wrapper AggroController (10 blocks + LOS acquire via
  BotCombat.setTarget, 32-block leash from engagement origin, 200 ticks unseen, clearTarget, walk home via
  BotNavigation in a late END_SERVER_TICK phase, home anchor + temporary origins, acquisition while returning).
  Wrapper 1657 green on main. NOTE for user: a player who stays within 10 blocks in sight after a give-up re-engages
  (fresh temp origin) - follows "regular aggro logic"; offered mitigation (no re-acquire of the same target until it
  was out of range/sight once). QUEUED w31b nits: forced name equal to the revenge target at FIRST observation still
  counts as external; README long status line; document re-engagement.
- 2026-09-30 ~00:10 MERGED on main: pace d2b583f + pace fixes f2c41c3 77e6667 (route lease kept on Baritone
  hand-over, deadline credit cap, course docs) + gear worst-first b1bd2d8 387ba1c 536f812. Root 2526 green.
  QUEUED pc2 nits: twobots legacy symmetry jam at one-wide gap (+29 ticks; stuck-recovery jitter), re-measure pit
  baseline. gr nits -> nx job. Remaining pace nit (stale lease on rejected Baritone start) -> rc job.
- WAVE 3 RUNNING wf_dcd4227d-dc9 (task w4o2qb2eq): fe (follow-pace-escort + R6 auto-eat + AggroSense pruning), rc
  (r5-core-paths + stale-lease nit), nx (gear G1-G6 + aggro A1-A5 nits, specs\nx_review_nits.md), od (OreDig
  drop_recovery_contract_bound failure: bisect + fix, specs\od_oredig_drop_recovery.md). Worktrees C:\mcw\{fe,rc,nx,od}.
  w29 (crossbow harness) still RUNNING (task w79r3xcb6). Then wave 4: wd, rf, rs, rp, rd.
- 00:25 USER: realistic line of sight everywhere (can't see you behind them, sneaking or not; ambush plays), design
  delegated. DESIGN specs\perception_design.md (cone 60/100 deg, peripheral 0.5, sneak 0.5 + silent, hearing radii,
  2-ray occlusion, 10 s awareness, objects out of scope, shared golden vectors docs/perception/vectors.json).
  wp (wrapper AggroController perception) RUNNING wf_5500d30f-800 (task wtg5yho5y), worktree C:\mcw\wp.
  pr (Minecraft-AI CreaturePerception + audit of all canObserveEntity callers) QUEUED until fe + nx merge (they own
  DangerWatcher/AggroSense/SharedVision/CombatCore); run it as part of wave 4.
- 00:40 w29 DONE (tmp/w29, 9 commits, review approve_with_nits; harness proves R1/R2/R3/R4a on a real server; wrapper
  GameTest runner C:\mcw\_tools\gt_wrapper.sh; HitPoller fixes 'Combat taken' for HeroBot BotPlayer.hurtServer
  override). MERGE JOB m29 RUNNING wf_f08cf90c-f8a (task w5dt1qsx7), spec specs\m29_merge.md: cherry-pick w29 onto
  main, reconcile with aggro, pvpbotSettings defaults -> 64 / autoTarget false / 8-12-16 / autoEquip false, nits,
  real-server AggroGameTests (acquire/wall/hit-from-20/leash+walk-home/10 s unseen/temp origin). Then wp (perception)
  merges on top; then pack boot check + DEPLOY wrapper (game closed).
- 00:55 wp DONE (tmp/wp bc4bed5 faab062, review approve_with_nits; wrapper 1683 green; docs/perception/vectors.json
  ~55 golden cases + docs/PERCEPTION.md). HOLD: merge AFTER m29 (cherry-pick wp onto main+m29; small diff). Then job
  wp2: real-server perception GameTests with the m29 harness (walking behind heard at 3, sprinting at 7, sneaking
  behind never, sneak-front 4.5 yes / 6 no) + nits (perception.enabled=false is not 'exactly' old LOS: document or
  keep vanilla hasLineOfSight for disabled path; PERCEPTION.md: wrapper awareness = aggro.loseSightTicks, mob radii
  fixed; optionally log moving/noise age in the noticed line).
- 01:10 USER: NO block-distance restrictions for PvP BOT bots (no 10 aggro / 32 leash); line of sight only (mod max);
  lost sight -> go to last known position -> smart search 10 s -> give up -> return to FIRST aggro start; re-sight
  while returning restarts the cycle; human reaction time: seen >= 0.25 s. Stopped m29 (w5dt1qsx7) mid step 2 and
  relaunched it with an AMENDMENT (maxTargetDistance 128 mod max, delete TargetingRadius test, skip AggroGameTests):
  wf_5382ba54-e92 (task wev0us2g5). NEW spec specs\w32_los_hunter.md (time-based perception, CHASE/PURSUE/SEARCH/RETURN,
  wrapper path planner via detached vanilla helper mob or bounded A*, hits: reaction delay / pursue from cover, mobs:
  return when lost, real-server tests a-f). perception_design.md got a REVISION header (time-based model for both mods).
  PLAN: m29 done -> merge m29 to main -> cherry-pick wp (bc4bed5 faab062) onto main -> launch w32 on main.
- 01:20 USER: "try to use baritone if it helps" -> w32 gets a PathPlanner interface (standalone vanilla planner first);
  follow-up bx = Minecraft-AI public planning API over vendored Baritone (any ServerPlayer, no break/place, move set
  limited to PvP BOT steering) + wrapper adapter, kept only if real-server courses show it is better. Also wave-4 wd
  (warden flee / retreat) should use Baritone GoalRunAway on the Baritone engine.
- USER asked for a stable deploy NOW. Snapshot worktree C:\mcw\dep = tmp/m29 @8ed80fc (main 536f812 + w29 commits;
  root source identical to main). Wrapper unit 1723 green; wrapper harness 11/11 PASS (ran as a side effect of
  'gradlew build -x test': NIT - build/check wires runGameTest, bypassing the machine lock; fix in m29/w32).
  Pack boot check running (task bv6jbztyx) -> results_dep.txt; then deploy root (scripts/deploy_profile.sh from main
  checkout) + wrapper jar from dep (backup first).
- 01:30 USER PHILOSOPHY: "no cheating and no artificial restrictions" - shot speed = the weapon's natural vanilla rate
  with buffs. Added to w32 spec: remove crossbow pacing + aimSettle, release a loaded crossbow draw at vanilla charge
  time (PvP BOT holds 25 ticks), managed bowMinDrawTime 20 (vanilla full power; PvP BOT default 40), audit other
  artificial limits. The current deploy still has the 26-tick pacing (user: "for next update").
- 01:40 USER: bots must actually CONSUME arrows/potions/food/everything like a normal player (can run out). Read-only
  audit RUNNING wf_cb844da9-239 (task welxe17f4): inhabitants (wrapper incl. dep version + PvP BOT + HeroBot game
  mode/abilities, reapplyOnRestore refill risk) and Minecraft-AI companions. Fixes go into the next jobs.
- 2026-09-30 01:04 DEPLOYED: Minecraft-AI jar from main 536f812 (scripts/deploy_profile.sh) + wrapper jar built from
  C:\mcw\dep (tmp/m29 @8ed80fc = main + w29). Pack boot check: mixin_target_class_load 7/7 PASS (others still running,
  task bv6jbztyx -> deploycheck\results_dep.txt; tell the user if any fails). Backup
  <profile>\backups\pre-deploy-2026-09-30-1 (both jars, pvpbot_inhabitants.json, world PvP BOT settings.json).
  Deployed behaviour: crossbow trigger + 26-tick pacing, no Piercing, PvP BOT auto-target at 10 (inert aggro
  controller: leash/10 s sight/walk home), managed 10 / 6-8-10 / autoEquip off, water bucket for new rolls,
  HitPoller logging; companions: R1 targeting, pace, worst-first gear.
- 01:15 AUDIT DONE (wf_cb844da9-239). Item-use paths vanilla; all bots survival. Fix jobs:
  cv (Minecraft-AI vanilla parity) RUNNING wf_b4507ed6-c2c (task wiovjh5mf), spec specs\cv_vanilla_parity.md: remove
  operator mid-air setBlock + forced pickup + pace switches; death revive clears XP/effects; restore single source of
  truth; mayInteract check; smelt/trade/craft loss bugs. QUEUED cs (wrapper) specs\cs_inhabitant_state.md AFTER m29
  merges: dormancy live-state snapshot (no refill), persist vitals across restart (HeroBot setHealth(20)), unmarked
  restore from snapshot, force survival, REMOVE non-vanilla attribute variation (+strip existing modifiers), eat-at-full
  gate. LATER feature job cu: companions actually use potions/totems/pearls/crossbows/tridents/milk and craft arrows.
  USER confirmed: Infinity bow not consuming plain arrows is correct.
- 01:35 MERGED on main: m29 (11 commits: w29 harness/RangedFire/pacing/HitPoller/water bucket + final managed settings
  128 / autoTarget false / 8-12-16 / autoEquip false, crossbow-used-item check, radius test deleted; wall test forces
  target) + wp (fafff1e perception model, 2a97ff2 aggro by sight cone + hearing). Wrapper 1753 green on main.
  RUNNING wf_2c6f4a8b-1ea (task waumkxi82): w32 (LOS hunter + natural fire rates + build-no-gametest nit) and cs
  (inhabitant state: dormancy snapshot, vitals, survival, remove attribute variation, eat gate).
- USER: "add crossbow support [for companions], consolidated with bow support". Spec specs\cx_ranged_weapons.md
  (one RangedWeapon layer, vanilla paths only, natural rates, worst-first, friendly-fire guard). Launch AFTER nx and fe
  merge (EquipAction/CombatCore/DangerWatcher owners).
- 01:36 DEPLOYED wrapper from main 2a97ff2 (m29 + wp perception; backup <profile>\backups\pre-deploy-2026-09-30-2-wrapper).
  Minecraft-AI jar unchanged (root identical). Evidence: pack boot mixin 7/7 PASS with this jar; smoke tests
  (tmp/sm b82b1b7 AggroSmokeGameTests: front sight, sneaking behind not noticed, walking behind heard, 200-tick give-up
  + walk home, no teleports) 5/5 each. Old ranged harness tests flaky under perception (random spawn facing) -> Rig
  facing fix (w32/ml). Earlier deploy's full boot check ended 7/7 PASS. Tool change: gt_filter.sh/gt_wrapper.sh now
  honour C:\mcw\_tools\.priority (callers without GT_PRIORITY=1 wait) - backups *.bak_prio.
- FINDING: PvP BOT melee has no LOS check (kills through a 1-block wall). Job ml (specs\ml_melee_legality.md): veto
  inhabitant melee without a clear crosshair ray / beyond vanilla reach via ServerLivingEntityEvents.ALLOW_DAMAGE.
- 01:45 USER: sight has no block limit in the view cone, but bots do NOT ENGAGE beyond 64 blocks - the ONLY hard-coded
  restriction. Queued follow-up w32b (specs\w32b_engage64.md) stacked on w32 when it lands: engage limit 64 (acquire,
  hit, and chase target > 64 from bot -> give up 'too far' -> return home), managed maxTargetDistance 64, docs.
- 01:50 USER: 'too far' only when the bot SEES the target > 64 away - no magic knowledge. w32b spec amended (NO MAGIC
  KNOWLEDGE A-G: sight-only distance, unseen projectile hit -> direction only + trace-back investigation point, clear
  forced target on first unseen tick (PvP BOT tracks true position otherwise), unseen logout/death = lost sight).
- 01:55 USER: reaction time 0.5 s base, 1.0 s at 64 blocks -> w32b amendment 2 (reactionTicks 10, +5 per 32 blocks).
  The Minecraft-AI perception job pr must use the same defaults (shared vectors).
- 02:00 USER: reaction time must be a continuous (dynamic) function of distance. w32b amendment 3: requiredSeconds =
  (0.5 + 0.5*d/64) * angleFactor (1 up to 30 deg, linear to 2 at 100 deg, unseen beyond) * sneak 2 / visibility.
- 02:05 USER: reaction time rises to 2.0 s at 64 blocks (amendment 4): (0.5 + 1.5*d/64) * angle * sneak / visibility.
- 02:10 USER: hearing must CALL vanilla's sculk/warden vibration system. w32b amendment 5 + perception_design REVISION 2:
  per-bot VibrationSystem (vanilla Data/Listener/DynamicGameEventListener/Ticker), radius 8, vanilla sneak/wool rules,
  onReceiveVibration -> sound position only (no magic); heard+visible = exposure angle 1, heard+occluded = hint.
- 02:15 USER: hearing radius 16 (warden) and confirmed heard-unseen sounds are search clues (w32b amendment 6).
- 02:20 USER: no instant sniping when re-appearing - reaction time on EVERY re-sighting. w32b amendment 7: CONFIRMED
  flag (full reaction exposure, reset on first unseen tick), setTarget only while confirmed, RangedFire only while
  confirmed + visible, damage-level veto of inhabitant melee without a confirmed engagement (in ml's ALLOW_DAMAGE
  listener). Consider asking the user about human aim-turn speed (PvP BOT snaps aim instantly).
- 02:25 USER approved human-like aim: "if i shoot pvpbot from behind, it should not be able to spin around 360 and shoot
  me instantly". Spec specs\w32c_human_aim.md (tracked aim rate-limited 540 deg/s written back to the entity, view cone
  uses tracked aim, fire only when confirmed + aim within tolerance + settle jitter, re-aim PvP BOT's bow arrows at
  spawn via vanilla shootFromRotation, melee needs the victim under the tracked crosshair). Chain: w32 -> w32b -> w32c.
  ALSO apply human turn speed/aim to Minecraft-AI companions (add to pr or cx).
- 02:45 USER BUG: out-of-ammo bots keep the empty bow/crossbow and never switch to their sword. ROOT CAUSE: PvP BOT
  selectWeaponMode -> RANGED whenever a ranged weapon exists (no ammo check); handleRangedCombat selects it, sees no
  arrows, flips MELEE and returns; loop. Hidden before by autoEquipWeapon (now managed off). Job oa (specs\
  oa_out_of_ammo.md): managed rangedRetreatOnClose=false (melee within 7 blocks when a melee weapon exists), wrapper
  out-of-ammo gap closer (select sword + BotNavigation.moveToward), RangedFire may fire a last loaded bolt, report the
  'surrender' pose. Arrow counts: LoadoutRoller r.count(0..256) per bot + half of ranged bots get a spectral/tipped
  stack; 0 is possible on purpose.
- 02:55 USER: melee switch at 5 blocks (managed meleeRange 2.5), arrows 0..32 total at spawn (no trimming of live
  bots), PvP BOT has NO pickup logic -> leave it. oa restarted with the amendment: wf_? (see /workflows; task launched
  02:55).
- 04:35 USER: tab list full but no hostile bots near. DIAGNOSIS: maxLiveBots 64; 94% of spawns underground (trial
  chambers/crypts); per-structure max = min(64, volume/300) so ONE trial chamber held 60 bots and one crypt 37; surface
  structures got none; dormancy churn ~1200 cycles/2.5 h (no chat spam). Startup command 'pvpbot settings auto-target
  true' is overridden by the managed settings (fine; suggest removing it). Job pd (specs\pd_population_distribution.md):
  processing.maxBotsPerStructure (default 8, clamp existing), nearest-first live budget (3D distance, hysteresis,
  never evict engaged bots), 'where are my bots' status. Launch AFTER cs merges (dormancy snapshot).
- 04:45 USER: NO per-structure cap, NO 'where are my bots' command; nearest POI fills to its size-based target, the
  rest goes to the next closest; recalc when the nearest POI changes, despawn (saved) / respawn dynamically; efficiency
  design delegated. pd restarted with AMENDMENT + EFFICIENCY DESIGN E1-E7 (spatial index over the simulation area,
  recompute on >= 4 blocks of movement, incremental reconcile queue nearest-first, hysteresis 8 / dwell 20 s / grace
  10 s, batched saves, wake saved residents first, < 0.2 ms/tick). Relies on cs dormancy snapshots.
- 04:50 USER: bots the player has SEEN stay while their chunk is loaded. pd restarted again with AMENDMENT 2 (S1-S5:
  human camera cone 70 deg + occlusion, check every 5-10 ticks, protected like engaged bots, flag cleared on unload).
- 04:55 USER: seen bots must still be there when you come back later (e.g. go to base to prepare, return). pd
  restarted with AMENDMENT 3 (P1-P6): persistent seen flag in the store, never permanently removed except death (TPS
  shedding/cleanup/re-roll refuse seen bots), dormant with full state + position on unload, woken first at the saved
  position with the same identity. Reconcile the position-in-snapshot with cs at merge.
- 05:00 USER: only SEEN bots are saved; unseen bots are permanently deleted when their chunk unloads (storage). pd
  restarted (5th, each within minutes) with AMENDMENT 4 (U1-U7): unseen deletion frees a vacant slot; per-structure N /
  deadCount / seen list; refill = wake seen + fresh rolls for N - dead - seenAlive; deaths never replaced (no loot
  farm, README's one-time population preserved); migration drops unseen stored bots. pd owns the save POLICY; cs owns
  the snapshot mechanism -> reconcile at merge.
- 05:05 USER: no loot farm, design delegated. pd restarted (6th) with AMENDMENT 5 (F1-F8): lifetime cap N with permanent
  deadCount; REMOVAL IS NOT DEATH (PvP BOT removeBot = clear + 'player X kill' = real death with XP orbs -> replace with
  snapshot(seen) -> clear inv+XP -> non-death disconnect, deregister, ignore in death accounting); real deaths keep
  vanilla drops and never refill; no re-roll fishing; snapshot->clear->remove ordering with crash safety; TPS shedding
  follows the same rules; audit every removal path.
- 05:10 USER: no Mending on PvP BOT gear (OP loot should be temporary unless the player can repair with the base
  material). Job mn RUNNING (specs\mn_no_mending.md): default disabledEnchantments = piercing + mending (existing w30
  sweep strips it from live bots), stop stocking XP bottles while mending is disabled (their only use was PvP BOT's
  auto-mend; rng draws kept), catalog text.
- 05:15 USER: keep XP bottles in loadouts (loot). mn restarted with the amendment (only the Mending denylist default +
  catalog text; XP bottle roll unchanged).
- 05:25 MERGED ml on main (558c1be smoke tests, 724ee28 MeleeLegality ALLOW_DAMAGE veto, 189b43f Rig.faceTarget).
  Wrapper 1771 green. Follow-up ml2 (specs\ml2_melee_vanilla_reach.md): reach from vanilla ATTACK_RANGE /
  AttackRange.isInRange (spears, min range), veto counts in the Combat taken line, prune primaries map, nits.
- 05:30 MERGED mn on main (06dc1e4: default disabledEnchantments = piercing + mending; XP bottles unchanged). Wrapper
  1774 green. User config has no disabledEnchantments key -> default applies. QUEUED nit: InhabitantsConfig javadoc
  line ~257 is 142 chars (rewrap). Watch ProfileGeneratorTest golden line 161 + SETTINGS.md line 58 when merging
  cs/oa (regenerate if the roll changes).
- 05:45 MERGED WAVE 3 on main: nx (12b22b9 bcf8228), rc (5af66d5 c509d53 63060fe), fe (ccf135b 36829a6), od (512dd74)
  + 2ea4489 (missing @Test in PrivilegedBoundarySourceTest, passes). Root 2573 green. fe validation incomplete (client
  running) -> regression batch RUNNING on C:\mcw\dep @main (task btp8429uq -> verify\wave3_main.txt: hunger, follow_task,
  auto_eat, follow_pace/escort, creeper, survival_reflex, baritone_engine_follow, underground_safety,
  mining_hostile_recovery, gear, snap, natural_movement, pace, hostile_bot_targeting, danger_watcher, combat_hardening,
  ore_dig_opportunistic_lifecycle). QUEUED nits: fe 8 minor, rc 4 minor, nx 6 minor, od 2 minor (see task output
  w4o2qb2eq).
- WAVE 4 RUNNING (task via /workflows, launched 05:45): wd (warden + retreat escort + Baritone GoalRunAway), rf, rs, rp,
  rd. Worktrees C:\mcw\{wd,rf,rs,rp,rd}. cv (vanilla parity) still running. Then: pr (companion perception, time-based +
  vibrations + human aim), cx (bow/crossbow consolidation), wave 5 (ry, ro), wave 6 (rz), P3, final steps.
- 06:00 MERGED oa on main (2c798c6 + c3d89e6; Rig conflict resolved keeping ml's faceTarget()). Wrapper 1787 green.
  managed rangedRetreatOnClose=false + meleeRange=2.5 (melee within 5), OutOfAmmoGapCloser, RangedFire fires a last
  loaded bolt, arrows 0..32. User config has NO pvpbotSettings block -> all shipped defaults apply (the review's
  'present block without the new keys' gap does not affect the user). QUEUED oa nits: catalog note that per-bot
  entity_interaction_range above 2.5 has no effect on PvP BOT's attack path while meleeRange is managed; OutOfAmmo
  busy gate could also read PvP BOT's isEating; INFO line when a present pvpbotSettings block lacks new keys.
- 06:10 MERGED ml2 on main (3fe329f: MeleeLegality via vanilla AttackRange per weapon incl. spear min/max, spear +
  mace_smash damage types gated, veto counts in Combat taken line, bounded SweepMemory). Wrapper 1797 green.
- USER: the enchantment denylist must only affect PvP BOT bots; players keep Piercing/Mending. GAP FOUND: the 100-tick
  sweep strips/deletes ANY stack in an inhabitant inventory, incl. picked-up player items (Mending gear, pearls).
  Job is (specs\is_issued_items_only.md): mark wrapper-issued stacks (CUSTOM_DATA marker), sweeps touch marked only,
  markers removed when items leave the bot, one-time migration pass per existing bot. LAUNCH AFTER cs MERGES.
- 06:25 w32 DONE (tmp/w32, 20 commits, approve_with_nits; wrapper harness 19/19 in repeated ALL runs; natural rates
  QC3 12 ticks / bow 21 ticks; helper-mob vanilla pathfinding planner; gradle build no longer runs runGameTest) and cs
  DONE (tmp/cs ab32899 18b506f, approve_with_nits; snapshot through dormancy+restart, forced survival, attribute
  variation removed, EatGate; its 4 ALL failures = the random-facing flakiness fixed by ml's Rig.faceTarget). Direct
  cherry-pick onto main conflicted in 14 files -> merge job mg RUNNING (specs\mg_merge_w32_cs.md; worktree C:\mcw\mg).
  After mg: launch w32b (engage 64 + no magic + continuous reaction 0.5->2.0 s + vanilla vibrations r16 + re-sight
  reaction + CONFIRMED gating), then w32c (human aim), then pd (population) rebase/merge, is (issued-items marker).
- 06:35 WAVE 3 REGRESSION on main 2ea4489: 16 classes PASS (hunger, follow_task 12, auto_eat, follow_pace 14,
  follow_escort 10, creeper 8, survival_reflex 18, baritone_engine_follow 10, underground_safety 26, mining_hostile 7,
  gear 18, snap 19, pace 11+8, hostile_bot_targeting 17, combat_hardening 18); 3 FAIL once each:
  natural_movement pillar_up_without_rescue_teleport, danger_watcher_low_health combat_reequips_backup_in_the_same_attack
  _boundary, ore_dig_opportunistic_lifecycle two_bots_one_vein_exactly_one_breaker. Triage job t3 RUNNING (C:\mcw\t3).
- 07:10 MERGED on main: cv (2fc24d1 fa9f0e3 5f557c1 c373fff 325fbaa; docs conflict resolved) -> root 2584 green;
  mg = w32 hunter + cs state (25 commits incl. review nits) -> wrapper green; mg's ALL 39/39 x4. pd DONE (approve_with_
  nits; allocator 36-90 us/tick; store 31.8 MB -> 3.8 MB) -> merge job mg2 (specs\mg2_merge_pd.md: skip duplicate cs
  commit, reconcile with hunter/cs, review fixes a-g). w32b LAUNCHED in parallel (specs\w32b_engage64.md amendments 1-7).
- 07:20 MERGED t3 (56420a0: 3 GameTest fixture fixes - danger_watcher backup premise invalidated by nx G2 policy
  (armoured zombie), ore_dig two_bots race (settle from the mining tick), natural_movement pillar cold-JIT pathfinder
  timeout (warm-up)). QUEUED: production pathfinder warm-up at server start (first route after restart may exceed the
  50 ms budget); unit test pinning 'a two-use sword is not adequate vs an ordinary zombie' (G2 policy); t3 warm-up
  should assert the warm search expanded nodes.
- 07:40 WAVE 4 DONE (all approve_with_nits). MERGED on main: wd 8e83e74 (warden never fought, calm -> sneak away,
  hunting -> sprint, retreat escort, Baritone GoalRunAway, WardenRefusal), rf ed501e8, rs e54869a. Root unit green.
  rp conflicted semantically with rf in WalkedStep/WalkedStepRules (water jump rules) -> merge+fix job w4f
  (specs\w4f_merge_rp_rd.md): land rp + rd, fix vacuous MiningServiceDisposal pin, restore DigDown DESCEND assert,
  triage pre-existing acquire_water failures, verification sweep. QUEUED wave-4 minor nits (wd 4, rf 6, rs 3, rp 5, rd 5)
  - see task output weq1dcg6h. Then: wave 5 (ry, ro), wave 6 (rz), pr (companion perception), cx (bow/crossbow), P3.
- 07:45 cx LAUNCHED (companion bow+crossbow consolidation + human aim H1-H3, spec amended). pr (companion perception:
  time-based continuous reaction formula, vibrations r16, reaction on re-sighting, no magic) waits for w32b to finalize
  docs/perception/vectors.json and for cx (CombatTask/CombatCore overlap).
- 08:10 MERGED on main: mg2 (population allocator + review fixes a-g incl. removal journal; 2d817ad 57e2ba4 cef66c8
  58a311b), w32b (6df4d4c bbf363e: engage 64 sighted-only, no magic knowledge, continuous reaction (0.5->2.0 s),
  vanilla VibrationSystem hearing r16, CONFIRMED gating on every sighting; AggroDriver conflict = union), fix commit
  (isEngaged without REACT). Wrapper unit 1954 green. Full wrapper ALL x2 RUNNING on C:\mcw\dep@main
  (verify\wrapper_main_all.txt). NEXT for wrapper: is (issued-items marker), w32c (human aim). Minor nits: mg2 5, w32b 7
  (task outputs w6udi3zt0).
- 08:40 VERIFIED main 883e3ac wrapper: gt_wrapper ALL = PASS 52/52 x2 (hunter + w32b + population + state + melee +
  out-of-ammo + no-mending + settings + combat log). Deployable wrapper candidate (not deployed; offered to the user).
- 08:55 MERGED w4f on main (8f8e369 rp, a31ce54 rd, 4777cd8 pins, d9eb380 AcquireWater ascent settle (real regression
  from 5af66d5 walked path start), 22a3f61 + ccfe4ad fixtures). Root unit green. WAVE 4 COMPLETE. WAVE 5 LAUNCHING: ry
  (r5-descend), ro (r5-oredig). QUEUED w4f nits: hunt fixture vacuous !original.isAlive(); cross-test arena leakage
  hazard (general); dig-down budgets raised without per-cell measurements; WalkedStepRules 5-arg jumpNow shim.
- 09:20 MERGED on main: is (afbd097: issued-item CUSTOM_DATA marker, sanitizers touch marked only, IssuedItemGuard
  unmarks drops/arrows, one-time migration per bot) + w32c (847f59f: HumanAim tracked rotation <= 540 deg/s written to
  the entity, fire only when aim settled + jitter, PvP BOT bow arrows re-aimed on spawn, melee needs victim under the
  tracked crosshair). Import conflict = union. Wrapper unit 1986 green. Combined ALL x2 RUNNING (verify\wrapper_main_all2.txt).
  HOSTILE-BOT WORK (all user requests) IS NOW ON MAIN pending the combined verification. Minor nits queued (is 4, w32c 6).
- 09:45 VERIFIED main 847f59f wrapper: gt_wrapper ALL PASS 59/59 x2. Complete hostile-bot build ready (deploy offered,
  awaiting the user's answer). Running: cx (companion ranged + human aim), ry, ro (wave 5).
- 10:05 MERGED cx on main (9d5cc84: RangedWeapon layer bow+crossbow, HumanAim for companions, crosshair melee,
  multishot/piercing-aware friendly-fire guard, worst-first bow vs crossbow). Root unit green. pr LAUNCHED (specs\
  pr_companion_perception.md: CreaturePerception + vibration hearing + reaction formula + call-site audit, shared golden
  vectors, plus cx review fixes a-d: LLM attack tool under human aim, cover-peek LOS loop, crossbow-draw recognition,
  mount on crosshair).
- 10:35 MERGED wave 5 on main (9e86ef6 ry DescendToYTask walked; 1362987 ro OreDig/HarvestCore/MiningBarricade walked).
  Root unit green. Behaviour notes accepted: gravel onto the head after a stair landing is mined (no hop room), walk-out
  of gravel may take one vanilla suffocation hit. QUEUED: latent cross-chunk drop flake in other pickup fixtures (align
  scenes to one chunk column); ry 7 / ro 5 minor nits. WAVE 6 rz LAUNCHED.
- 11:05 MERGED pr on main (c93f435 04c54c7: companion CreaturePerception + ExposureTracker + BotEars (VibrationSystem)
  + canNoticeCreature at creature sites + AttackEntityTask for the LLM attack tool). Root unit green. GAP: harness runs
  legacy suites with perception OFF (~60 omniscient fixtures fail ON). Follow-up pf queued (specs\pf_perception_followup.md):
  convert fixtures so the whole suite runs perception ON, pr nits a-d, danger_watcher paused_dig_down lava failure on main.
  Launch pf AFTER rz lands (fixture overlap).
- 11:50 MERGED rz on main (4075948 9b37eb4: FakePlayerMotion teleport primitives deleted, NoCorrectionTeleportSourceTest,
  zero-correction acceptance session on both engines, lava-return fixture). Root unit green. R1-R6 PROGRAM COMPLETE.
  RUNNING: pf (perception ON for the whole suite + pr nits) and hx (flake hunt: unique mock players, follow by UUID,
  scoped time/weather/config, arena cleanup; target 3 consecutive green ALL runs). REMAINING after them: nit sweep
  (queued nits from all jobs), production pathfinder warm-up, P3 (nav.engine default -> baritone with cost measurement +
  pack-compat), vendor dev tooling into scripts/dev, final adversarial review since 770ccd1, final ALL (root + wrapper),
  push (only main), cleanup worktrees/C:\mcw/Downloads\baritone-analysis, final deploy, memory update.
- 12:00 USER: ALL bots (PvP BOT + Minecraft-AI) use the chosen weapon until it breaks (no durability-based swaps; the
  nx G2 hits+2 adequacy rule and cx 'not-about-to-break' ordering must go); Minecraft-AI bots warn the owner in chat at
  <10% durability for diamond/netherite gear/tools + shield/bow/crossbow, chat only, never interrupt. Job du
  (specs\du_use_until_break.md): audit + remove durability swaps (weapons/tools/armor), restore the danger_watcher backup
  fixture premise, chat warnings (once per crossing, re-arm on repair, rate limit), wrapper/PvP BOT audit.
- 12:10 USER CHANGED R2: tools worst-first (next worst on break); non-tools best-first (next best auto-equipped on
  break); warnings instant, list + trident/mace/elytra/fishing rod. du restarted with the amendment (U1-U4); memory and
  specs\r1_r6_user_decisions.md updated.

## 2026-09-30 (after compaction): offhand/shield request
- User: offhand = best shield > next shield > totem > next totem, for ALL bots; companions auto-equip shields and armor;
  companions block all blockable projectiles and melee (not potions, sonic boom, etc.).
- du stopped and relaunched with AMENDMENT 2 (O1-O4) in du_use_until_break.md: task w260yxs31, run wf_e4771843-5e9.
  PvP BOT: manage autoTotemEnabled=false + totemPriority=false, add a wrapper OffhandPolicy.
- sh (companion shield blocking): spec sh_shield_blocking.md, worktree C:\mcw\sh, task wyfg2t2xa, run wf_44d215f7-9d1.
  Told not to edit EquipAction (du owns it); reconcile at merge.
- Still running: pf, hx. Queue unchanged (nit sweep, pathfinder warm-up, P3, final review/suites, push, cleanup, deploy).
- ~15:28 USER: clean stop of everything (plans to hand off to a cloud session to save local RAM). Stopped workflows
  wrws18h9o (pf+hx), w260yxs31 (du), wyfg2t2xa (sh); killed their Gradle/GameTest servers (two were running AT ONCE),
  waiters and stale tail -f loops. WIP committed LOCALLY (not reviewed, GameTests incomplete): tmp/du 0c16fdd (2 real
  commits before it), tmp/sh 6f2d4d2, tmp/pf 387122e (1 real commit before it), tmp/hx a123ec0. Local main = 356
  commits ahead of origin (unpushed).
- LOCK BUG FOUND+FIXED: gt_filter/gt_wrapper's EXIT trap removed the lock unconditionally, so a WAITER killed by a
  timeout deleted the running holder's lock -> overlapping GameTest servers. Now both source gt_lock.sh: FIFO tickets
  (.gtqueue), owner-only release, own run's process tree killed on exit, orphan server killed on stale reclaim,
  events in gt_lock.log. Backups *.bak_fifo. Simulated: killed waiter leaves the lock alone; order is FIFO.
- WASTE FOUND: 5 worker-started `find / -name minecraft*...jar` scans (oldest 2026-09-29 22:42) ran for hours
  (~32 CPU-hours) and are now stuck in the kernel, unkillable (terminate = access denied); a reboot clears them.
  Rule for future briefs: never search whole drives; jars: .gradle/loom-cache/minecraftMaven/net/minecraft/
  minecraft-common-2ae02fda0f/1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2/*.jar, javap via mcjavap.sh.
