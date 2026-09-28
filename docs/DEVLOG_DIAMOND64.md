# Dev Log · Stable Mining of 64 Diamonds + 64 Obsidian (diamond64 / obsidian64)

> This file is the **rolling development log** for this phase: what was done at each stage, validation results, and the **outstanding issues list**.
> Design blueprint: `PLAN.md`; historical stages: `PLAN_HISTORY*.md` and `M*_VALIDATION_REPORT.md`.
> Goal (confirmed with the user): under strict_survival, reliably complete MineOre[diamond_ore x64] and obsidian x64 end-to-end;
> foraging, lighting, and combat capabilities may be extended as needed. LLM = `deepseek-v4-flash`.

---

## Stage 0 · Baseline Fixes and Model Switch (2026-08-07)

**Commits**: `d90352e6` (previous batch of hunt/route-contract hardening), `eed0adf2` (this batch)

**Completed**:
- `PathExecutor.isExactConstrainedRoute` extracted into a shared exactness contract; `HuntTask` now delegates to it,
  so unit tests no longer trigger `HuntTask.<clinit>`, which requires registry bootstrap.
- `MiningCheckpointMissionGameTests` legacy checkpoint simulation: removed the stray `snap_dimension` key
  (this orphaned key used to make restore correctly fail-closed with `mission_restore_invalid_replan_snapshot`).
- DeepSeek default model `deepseek-chat` → `deepseek-v4-flash`:
  - Explicitly send `thinking {enabled, reasoning_effort=low}` (V4 defaults to `high`, and reasoning shares
    `max_tokens` with the main response, so the implicit default would starve the tool call); `maxTokens` 2048 → 8192.
  - Response parsing now records `reasoning_tokens`; warn with `api_truncated_before_output` when
    `finish_reason=length` and there is no executable output at all.
  - No longer NPEs when the user config is missing `reasoningEffort` (falls back to the default value).

**Validation**: all 336 unit tests green; all 576 gametests green (`./gradlew runGameTest`).

---

## Stage 1 · P1 Pickup-Recovery Stall Fix + Full-Chain Investigation (2026-08-07)

**Completed**:
- **P1 root cause confirmed and fixed** (two evidence runs):
  - Pattern A (confirmed): after a dropped item shifted position it was never observed again, so `lastSeen`
    stayed pinned to the old cell; the stand cell resolved by `approachKnownPickupCell` happened to be the
    current cell → the same-cell nudge returned true ("still approaching") on every tick → the recovery loop
    stalled in place under an empty column until the 200-tick timeout. After the task failed, navsafe happened
    to shift one cell and vanilla collision immediately completed the pickup.
  - Pattern B (original flake): the body's hitbox overlapped the stone pedestal fractionally → the pathing
    start-point check silently failed, with a 20-tick cooldown, no escalation, and no logging.
  - Fix (`OreDigTask`): added stall detection (`updatePickupRecoveryStall`, reset on movement ownership /
    cell change) + escalation at the 30-tick threshold to an **observation sweep**
    (`startPickupObservationSweepStep`, mirroring Hunt's sweep: walk an exact, non-mining route around the
    ring of observable stand positions centered on last-seen) + rate-limited stall logging
    (`ore_dig_pickup_recovery_stalled`). `HarvestCore.startExactPickupPath` promoted to public for shared use.
  - Added deterministic gametest `pedestalLandedDropIsPhysicallyRecovered` (intercepts the drop and pins it to
    a pedestal, eliminating RNG); failed 1/3 before the fix, passed all 577 cases across 5 consecutive runs after.
- **Flaky test fix**: `oldNearbyRawDropCannotPoisonFreshKillTransaction` — the offset arena kept chunks loaded
  only via a fake player's chunk ticket; a transient unload/reload made the entity reference held by the
  closure report `isAlive()=false`. Fixed by deferring entity spawn (to tick 40) and re-querying with
  `getEntitiesByClass` at assertion time (an existing convention in this repo).
- **Full-chain investigation completed**: 8 parallel agents reviewed 7 subsystems (1.48M tokens), producing
  [FINDINGS_DIAMOND64.md](FINDINGS_DIAMOND64.md): **11 blockers / 19 major / 20 minor**, including a budget
  model and 10 open questions that need to be answered by real runs.

**Key conclusions (excerpt)**:
- F2: no surplus carryover on the per-batch diamond quota, plus only 2 resource epochs per batch, structurally
  caps mission success rate at ~17-66% (a mathematical ceiling unrelated to any bug) — highest priority.
- F3/F7: mid-descent landing drift and pickaxe exhaustion both immediately fail the mission outright
  (should be downgraded to recoverable).
- F4/F5/F6: three deadlocks in the obsidian chain (water-pour livelock, resumeFirst ordering, food hardcoded
  at 8 units).
- F29: the acceptance suite only has an obsidian-32 scenario; 64 needs a new scenario and budget calibration
  (i.e., P3).

---

## Stage 2 · Descent Chain Hardening: F3 + F7 (2026-08-07)

**Completed**:
- **F3 landing-drift downgrade** (`DescendToYTask`): a stand position on a third cell other than origin/target
  no longer immediately terminates the mission with `descend_landing_pose_drift` — it is now treated as an
  external displacement: pending landing is cleared and the staircase loop is re-anchored at the current
  actual stand position, capped at 8 recoveries per single descent (`MAX_LANDING_DRIFT_RECOVERIES`); only past
  that cap does it fall back to the original fail-closed behavior. Event: `descend_landing_drift_recovered`.
- **F7 descent pickaxe gate** (`DescendToYTask`): both productive staircase-digging sites (the main staircase
  and the lateral bypass) now check `ToolTier.canHarvestWithInventory` before `miner.begin`; if unqualified,
  fail immediately with the typed failure `need_better_tool:<pickaxe_id>` (consistent with the DigDownTask
  contract), letting GoalExecutor backtrack to restock a pickaxe. Survival clearing when the body is buried
  is **not gated** (must still be escapable bare-handed).
- Added gametest `knockbackLandingDriftReanchorsInsteadOfFailingTheMission`; two existing descend fixtures now
  issue an iron pickaxe (a real mission always has a pickaxe before descending, so the fixture now matches
  production semantics).

**Validation**: all 578 gametests green; all 336 unit tests green.

---

## Stage 3 · F2 Task-Level Margin Epoch Pool for Rare Ores (2026-08-07)

**Problem**: the 64-diamond mission is split into 8 batches x 8 diamonds, and each batch only gets 2 bounded
resource epochs (the initial one plus 1 retry, `MAX_RARE_RESOURCE_RETRIES_PER_BATCH=1`). Diamond yield is
random (expected 3-9 per epoch), so a single batch has a 10-30% chance of falling short of quota; if the
epoch-1 window times out, `finishActive` terminates the entire mission outright — even at 63/64 already
mined. This structurally caps mission success rate at ~17-66% (a mathematical ceiling unrelated to any bug).

**Approach: a bounded task-level margin epoch pool**
- Added `MiningBudget.rareMissionEpochMargin(batchCount) = min(batchCount / 2, 2)`
  (2 for a 64 target; see the cap below), pinned as the constant `DIAMOND_STACK_EPOCH_MARGIN = 2`; the
  per-batch epoch cap becomes `rareMissionResourceEpochCapacity(batchCount) = 2 + margin` (4 for a 64 target).
- `GoalExecutor.scheduleRareResourceRetry`: once the in-batch retry is exhausted (epoch >= 1), if the
  task-level margin pool still has balance, one more epoch may be drawn (epoch 2/3/...), using the exact same
  mechanism as the existing retry (a fresh RARE_ORE_BATCH service plus a new 24,000-tick OreDig window; the
  hard budget stays monotonic and never refreshes). Draw event: `rare_epoch_margin_drawn` (used/pool).
- **Persisted ledger**: `ActivePlan.rareEpochMarginUsed`, checkpoint key `rare_epoch_margin_used`.
  Monotonically increasing at the task level: it is not reset on a batch's closed commit (unlike the
  per-batch epoch), only a brand-new mission starts from 0. Restore is fail-closed: missing = legacy 0;
  a non-canonical, non-negative integer, an over-pool value, or `epoch - 1 > margin_used` (i.e. an epoch
  the ledger never paid for) all map to `mission_restore_invalid_rare_epoch_margin`;
  `normalizeRestoredRareResourceEpoch` was extended so a margin epoch must exactly match the epoch of the
  durable open batch.
- **Window math**: `MiningMissionBudget.rareOreDigCumulativeHardWindowTicks` gained an explicit
  `maxResourceEpochs` overload (the old single-argument interface still only recognizes 2 regular epochs);
  the epoch boundary used in OreDig checkpoint decoding / `advanceResourceEpoch` now comes from the
  mission-target-derived capacity, while ordinary ore batches still pin epoch at 0.
- **Supply pre-provisioning (margin epochs are fully paid for up front, to prevent unbounded renewal)**:
  - Food: `RARE_BOOTSTRAP_FOOD` 72 → **80** (18 epochs x 4 + 8 buffer);
  - Torches: `DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES` 640 → **720** (18 epochs x 40);
  - Sticks: `DIAMOND_STACK_CHANNEL_REPAIR_STICKS` 224 → **252**,
    `DIAMOND_STACK_BOOTSTRAP_STICKS` 228 → **256**;
  - `forQuota`/`rareMissionFoodTarget` add a matching margin term for any rare quota (e.g. for an 18 target:
    torches 240→280, food 32→36; for a 32 target, food 40→48).
  - **Stone is not given a margin** (a deviation from the brief; see below).
- **Service contract**: `ServicePolicy.rareOreBatch` now accepts margin epochs (capped by mission-derived
  value); a margin epoch reuses the same policy shape as an epoch-1 retry (food floor clamps to 8, torch/stick
  floors unchanged — margin supplies are carried extra, so the minimum is a floor, not a refresh);
  `rareServiceFoodMinimum` clamps for epoch>=2.
- **Outer timeout**: `diamondStack64FromZero()` retry terms now include margin (retryOreDig/retryService
  8 → 10), floor 603,200 → 660,800 ticks; the live-plan version auto-expands along with the nominal plan.

**Two deviations from the brief (both stemming from the 36-slot main-inventory physical wall)**:
1. **Margin pool = 2, not batchCount/2 = 4**. The first full gametest pass proved with hard evidence that
   carrying 4 margin epochs' worth of supplies (+160 torches +56 sticks = +4 slots) makes the rare boundary
   service's `requiredWorkingFreeSlots` contract unsatisfiable — several 64-target fixtures hit
   `mining_service_inventory_reserve_depleted:free=5:required=7` right at boundary-zero, or fail when forced
   to discard a pocket item (all margin supplies belong to a protected category and cannot be discarded).
   The measured headroom was only 2 slots → `RARE_MISSION_EPOCH_MARGIN_CAP = 2` (+80 torches +28 sticks
   +8 food, exactly +2 slots). Expanding the pool back to 4 would require banking margin supplies at a
   mission depot (a new cross-layer round-trip mechanism), left as a separate follow-up.
2. **No margin on stone**: stone is the only self-resupplying resource underground; the existing design
   already only bootstraps the first two pools (later batches are crafted on the fly from mining spoil by the
   service, and the epoch>=1 service policy only guarantees an EMERGENCY reserve) — a margin epoch is the
   same shape as an epoch-1 retry and draws from the same source; and there is no carrying capacity left
   for it anyway.

Structural benefit at margin=2: a single batch failure now needs to burn through both the "in-batch retry"
and the task's only 2 margin epochs consecutively before the mission is terminated; given the investigated
10-30% per-batch shortfall probability, the mission-level failure rate is now significantly below the
original "one shortfall and the mission is dead" behavior.

**Validation**: `./gradlew test` — all 340 unit tests green (5 new cases added: margin-pool math, window
overload, margin-ledger decode/restore fail-closed, normalize-margin-epoch attribution, epoch-timeout
classification); `./gradlew runGameTest` — all 580 gametests green (new
`epochOneTimeoutWithMissionMarginSurvivesAndDrawsOneEpoch` — epoch 1 times out at exactly the 48,000-tick
mark with margin available → mission survives, atomically draws 1 margin epoch, hard budget does not
refresh; `epochTimeoutWithExhaustedMarginPoolStaysTerminal` — the same kind of timeout is still terminal
once margin is exhausted; the existing `sameBatchEpochOneChannelToolFailureIsTerminalWithoutAnotherService`
now injects margin_used=2 and keeps the terminal semantics; checkpoint round-trip gained margin-key
assertions). The descent-kit stress fixture now carries only 1 wooden pickaxe, since margin supplies take up
3 more slots (the wood/stone pickaxe retirement contract is unchanged); the crowding boundary in
`diamond64RestoresMissionKit` tightened from free=4 to free=2.
The two diamond64 coal-bootstrap fixtures (`diamond64BootstrapCoal...` and
`spawnDiamond64CoalBootstrapMiner`) were resupplied to match the expanded contract: 720 torches extends the
coal chain to 12 batches, the channel-repair pickaxe heads need 56x3=168 stone, cobblestone 160→**192**
(still 3 slots) — otherwise the planner would insert a stone-mining detour before the coal OreDig and hit
the fixture's tick 80/100 deadline; sticks 234→262 (=BOOTSTRAP_STICKS+6), and logs converged to 64 (1 slot)
to keep the carried amount close to the pre-margin baseline.

---

## Stage 4 · Obsidian Chain Deadlocks: F4 + F21 (2026-08-07)

**Completed** (`CreateObsidianTask`):
- **F4 water-pour livelock**: on flat-pool terrain the pour point can be up to 4 cells from the lava clue,
  while vanilla water only advances 1 cell every 5 ticks — a fixed 4-tick wait meant the water could never
  reach the lava, the world showed zero change on recheck, and the same clue was replayed forever (each
  round also called `noteTopologyProgress`, resetting the 800-tick stall detector, until the
  153,600-tick mission budget was burned through). Fix: (1) scale the wait duration as
  `max(4, distance*5+4)` (`pourSpreadWaitTicks`, capped at 24 ticks); (2) record the clue poured this round
  (`lastPourClue`); if the drain cycle ends with no conversion, no pickup, and the clue is still observably
  lava, `rejectLava` enters a bounded rejection ledger (TTL 600) and search rotates to another clue, with
  event `create_obsidian_barren_pour_rejected`. Occlusion does not count as a negative result (only "observed
  and still lava" triggers rejection).
- **F21 vetoed after re-review**: the investigation had recommended changing restore to call `enter()`
  unconditionally; this was immediately shot down by an existing checkpoint round-trip gametest
  (`restore changed task checkpoint key phase_started`). Re-review conclusion: the mission clock runs on
  mission ticks, **the clock does not advance while paused**, so a safe preemption never burns phase-window
  time; cross-process restore is deliberately designed to "resume the remaining window rather than reset the
  clock" (to prevent restarts from refreshing the budget). The investigation's characterization of F21 was
  wrong; the change was reverted, and the findings entry is marked `[vetoed after re-review]`. Lesson: an
  agent's conclusion must survive judgment against the existing contract tests.

**Validation**: covered by the unified full-suite validation after the F2 wrap-up (see Stage 3/5 records).

---

## Stage 5 · F6 Obsidian Food Budget Scaled to Mission Size (2026-08-07)

**Completed**:
- `MiningBudget.obsidianExpeditionFoodTarget(missionTarget)`: 4 cooked-food units plus a 4-unit buffer per
  8-block service segment, with the floor kept at the old 8 units (the prepared short-run contract is
  unchanged). 64 blocks → **36** units, 32 blocks → 20, 16 blocks → 12, each within 1 inventory slot. The old
  hardcoded 8 units was guaranteed to run out mid-mission on a 64-block task, and deep underground there is
  neither huntable prey nor a pre-placed depot, leaving `mining_service_food_reserve_depleted` unrecoverable
  (F6).
- The formula lives in `MiningBudget`, which has no registry dependency (so it is pure-unit-testable;
  `GoalPlanner`'s `<clinit>` needs bootstrap, the same lesson as with `HuntTask`).
  `MiningPlanningSourceContractTest` now pins the scaling call; added `ObsidianFoodBudgetTest`
  (floor/scaling/rounding/single-slot cap); the rations and assertions in 3 planner gametest fixtures now
  derive from the constant (20 units).

**Validation**: all 343 unit tests green; all 580 gametests green.

---

## Stage 6 · F11 Restart Freeze on an Already-Delivered Batch (2026-08-07)

**Completed** (`OreDigTask.finishAlreadyDeliveredBatch`):
- The `targetCount==0` fast path runs before the hard timeout; its UNKNOWN branch (when restore-time
  observation cannot see `active_break_pos` after a restart) used to just `return` and spin every tick with
  nothing able to terminate it → an unbounded freeze.
- Fix: bounded the observation-recovery window — while spinning, take one step every 20 ticks around the
  observable ring of stand positions for the broken-block cell (`startObservationSweepStep`, generalized and
  shared from P1's pickup sweep); if it is still unobservable after exceeding `RESTORE_FACE_LIMIT`
  (1200 ticks), fall back to the conservative **under-count rather than over-count** exact-once result:
  `clearActiveTargetBreak` (the ore stays in the world; it is never miscounted as collected), with event
  `ore_dig_delivered_batch_break_unobservable`.

**Validation**: all 343 unit tests green; all 580 gametests green.
**TODO**: add a deterministic gametest (restore injecting targetCount=0 plus a fixture where the broken-block
cell is occluded, asserting recovery within the bounded window) — tracked as P5.

---

## Stage 7 · Two Deadlock Fixes: F5 Obsidian resumeFirst Missing-Resource Reordering + F8 Capacity-Parent Namespace Orphaning (2026-08-07)

**Completed**:

- **F5 obsidian resumeFirst missing-resource deadlock** (`GoalExecutor.reconcileObsidianSteps`):
  under an open transaction (waterSource/pickupPos/activeBreakPos), replan used to unconditionally insert the
  resume step at index 0 — ahead of the fresh plan's supply steps (new bucket / replacement pickaxe / fetch
  water). If the failure reason was itself a "missing resource" kind, the recovering mission would fail again
  for the same reason on its very first tick, and 3 consecutive zero-progress replans would kill the whole
  mission. Fix:
  - When the failure reason matches an exact prefix set (`need_better_tool:` /
    `create_obsidian_bucket_lost_after_pour` / `*_missing_water`, new helper
    `isObsidianMissingResourceFailure`) and this is a resume-first case, keep the supply prefix from the
    fresh plan that precedes the first MAKE_OBSIDIAN step and let it execute physically first, inserting the
    resume step after that prefix; for all other failure reasons, today's resume-first behavior is kept
    (the correct order for a physical continuation).
  - The reordering decision is logged as its own event, `goal_obsidian_resume_resupply_first`
    (reason/supply_steps/target).
  - When the fresh plan has no MAKE_OBSIDIAN step (no provable supply prefix) or planning fails, behavior is
    identical to the old logic (resume pinned to the front). The restore path
    (`rebuildObsidianAcquisition`) is unchanged: if resources are still missing after a restart, the very
    first failure goes through the fixed replan reordering, self-healing within a single replan instead of
    burning through 3.
- **F8 capacity-parent namespace orphaning** (`GoalExecutor.handleStepFailure`'s generic replan path):
  when a capacity handoff service fails, generic replan's `steps.clear()` destroys the exact retry step but
  never clears `capacityParentNamespace`; if the fresh plan no longer contains the parent ore family,
  settlement becomes permanently unreachable — evidence collection then rejects every MINE_ORE
  `plan.miningCheckpoint` update, and the next rare batch that successfully commits dies at the moment of
  success with `rare_batch_commit_checkpoint_invalid`. Fix (in the same transaction as installing the new
  queue):
  - During replan, if `capacityParentNamespace != null` and the fresh plan contains no same-family MINE_ORE
    step that can rebind that debit (matched by fingerprint + `acceptsStepTarget`, the same criteria used at
    assignment time by `isCapacityParentRetry`), roll back the marker and all watermarks
    (delivered/face/services_used), with event `goal_capacity_parent_rolled_back`.
  - The AUXILIARY namespace is discarded together with the now-dangling open ordinary cursor (there is no
    physical ledger entry; already-delivered output is in the inventory and the planner recomputes it
    faithfully from inventory — keeping it would instead make a restart fail-closed on an aux namespace
    missing its marker); the MINING namespace retires its cursor the same way as
    `goal_failed_primary_service_retired`.
  - **Fail-closed boundaries are not relaxed**: if the parent checkpoint cannot be decoded, carries an
    unsettled physical ledger entry (pending_pickup/active_break), or has `rare_mission_target != 0` (a
    marker pointing at an uninterpretable rare-cursor state), no rollback happens, preserving existing
    semantics. Restore validation is unchanged — the rollback happens within the same transaction, before
    persistence, and introduces no new persisted keys; existing exits such as
    `mission_restore_orphaned_capacity_handoff_cursor` are kept as-is.

**Regression tests**:
- Unit test `GoalExecutorObsidianResumeReconcileTest` (5 new cases): exact index and ordering of the
  supply-prefix reordering, physical-continuation resume-first unchanged, fallback when there is no prefix /
  an empty plan, the closed-transaction in-place replacement contract, and the exact scope of the
  failure-reason prefix set.
- Gametest `CreateObsidianMissionRecoveryGameTests.missingToolFailureWithOpenTransactionResuppliesBeforeResuming`:
  restores a target-32 open active-break transaction (obsidian genuinely placed in the world), removes the
  pickaxe to inject `need_better_tool:minecraft:diamond_pickaxe` → asserts the mission survives, the supply
  step is assigned before resume, the obsidian.* namespace retains its transaction identity throughout, and
  after CRAFTing the replacement pickaxe, MAKE resumes running with the original target/active_break.
- Gametest `MiningCheckpointMissionGameTests.failedCapacityHandoffWithoutParentFamilyRollsBackDebtAndRareBatchSettles`:
  fabricates an auxiliary capacity parent (an open iron-ore debit) plus a protected diamond64 rare cursor and
  a budget-exhausted capacity service; after restore the service fails with the typed `mining_service_timeout:`
  → generic replan (the fresh plan, after precheck, contains no iron family) → asserts the marker/watermarks/
  aux namespace are all rolled back while the rare cursor is preserved; then restores a rare batch delivered
  in full (delivered=8/8) and asserts its commit settles normally (`mining.batch_open=false`, epoch reset to
  zero) instead of dying with `rare_batch_commit_checkpoint_invalid`.

**Process lesson**: the first version of the F8 gametest re-registered `context.runAtEveryTick` from inside a
tick callback, which directly NPE-crashed the GameTest scheduler (modifying the listener table while
`GameTestState.tickTests` was iterating it) — fixed by driving the commit synchronously inside the probe
instead (`AbstractTask.abort` is a no-op on COMPLETED, so it just clears the TaskManager slot and then
manually settles via `tickBot`). Registering a nested tick listener is a hard no-go in this repo's gametests.

**Validation**: `./gradlew test` — all 348 unit tests green (343 + 5 new); `./gradlew runGameTest` — all 582
cases (580 + 2 new) green across two consecutive runs.

---

## Stage 9 · P3/F29 Obsidian-64 Acceptance Scenario Delivered (2026-08-07)

**Completed** (the 32 contract stays sealed and untouched; 64 is a superset of it):
- `MiningEvidenceAudit`: an audit session now carries an explicit `requiredCount` (the legacy entry point
  keeps the 64-diamond/32-obsidian thresholds); `Snapshot.passes()` is judged against the session's own
  quota — the 64 commitment reuses the same physical evidence chain rather than starting a new ledger.
- Verify scenarios: `obsidian_stack_64_controlled` (a quantity contract, part of the verify all/mining
  regression), `obsidian_stack_64_prepared` (a 10x7 pool of 70 sources, supplies scaled to the 64 contract,
  48,000-tick timeout = double the per-block rate of the 32 version), `obsidian_stack_64_from_zero`
  (timeout 316,800 = 240,000 plus the incremental 32 blocks amortized at 2,400 each; audit quota 64). Both
  long-run tiers are explicit opt-in; the sealed 32/diamond acceptance suite and the PR CI contract are
  unchanged.
- Evidence chain: new target `obsidian64` (scenario mapping, a >=64 physical-evidence threshold, wall-clock
  cap of 25,200 s — about 19% headroom at the 15-TPS floor, better than the 32 contract's 12.5%).
- `docs/MINING_ACCEPTANCE.md` records the 64-commitment basis and its derivation.

**Validation**: all 348 unit tests green; all 582 gametests green; shell syntax check passed.
**Commit**: `a6a01eb1`. The certification long-run (the 20-seed gate applies to 64 as well) is to be started
by the user at their discretion:
`bash scripts/evidence_run.sh --scenario obsidian_stack_64_from_zero`.

---

## Outstanding Issues (rolling list)

| ID | Severity | Issue | Status |
|---|---|---|---|
| P1 | major | Pickup-recovery stall livelock (drop landing on a pedestal / same-cell nudge false progress / silent NO_START) | **Fixed** (Stage 1) |
| F1-F50 | see report | Full-chain investigation findings list, see [FINDINGS_DIAMOND64.md](FINDINGS_DIAMOND64.md); fix progress is annotated item-by-item in that file | in progress |
| P2 | to be assessed | diamond64/obsidian64 full-chain stability gaps — 7 parallel subsystem investigations underway (OreDig recovery, Planner batching, Executor replan budget, obsidian lava chain, Descend round trips, survival-interruption recovery, budget/evidence system); items will be prioritized by impact once complete. | under investigation |
| P3 | major | Obsidian target 32 -> 64: acceptance scenarios/audit/evidence chain delivered (Stage 9); the lava-source-pool capacity assumption (a single lake reliably supplying 64) still needs confirmation via a from-zero real run | **Fixed** (Stage 9) |
| P4 | note | **Certification long-run cost**: the from-zero diamond live plan already declares 2,120,000 ticks (~39 hours/run at 15 TPS); the 20-seed gate is day-scale compute. This phase delivers "capability and stability plus a re-verifiable entry point"; sealed batch certification is to be started by the user at their discretion (`scripts/evidence_batch.sh`). | known constraint |

---

## Milestone Plan (to be refined as the investigation proceeds)

- **S1 Fix known instabilities**: P1 and the blocker-level defects found by the investigation.
- **S2 Mining chain hardening**: batch/budget/checkpoint consistency across the full 64-target run.
- **S3 Obsidian chain hardening**: drop preservation and safe recovery in lava scenarios.
- **S4 Survival extensions**: extend foraging (underground food economy), lighting (torch cadence), and
  combat (tunnel encounters) as needed.
- **S5 End-to-end evidence**: acceptance via N consecutive evidence-run passes of diamond64+obsidian64.
