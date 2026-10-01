# Navigation obstacle courses: legacy navigator vs Baritone

Status: built (P2a of `docs/NAVIGATION_BARITONE_PLAN.md`). This is the measurement that decides whether `nav.engine` may default to
`baritone` (P3: only when Baritone is at least as good on every course). The course fixture and the opt-in P3 timing probe add
diagnostic instrumentation, but do not change navigation policy, the production configuration, or the default. They record where each
engine stands and assert only what both engines must always meet.

**Result in one line:** the historical outcome rows show Baritone reaching every legacy-reachable course safely and improving several
capabilities, but the later paced comparison contains four slower Baritone rows (`lakedry`, `lava`, `cactus`, and `long`). Therefore the
current evidence does **not** meet the “at least equal everywhere” gate and `nav.engine` remains `legacy`.

## What runs

`NavigationCourseGameTests` (`src/gametest/java/.../task/`) has two tests per course, one per engine, named
`navigation_course_game_tests_<course>_<engine>[_measurement]`. Each test is its own test environment (its own batch), so no other bot is
active and the timings of the two engines compare. The geometry comes from `NavigationCourses` (one builder per course, run for both
engines: identical cells, same arena layer), the run and the metrics from `NavigationCourseRun`.

* The bot is driven only through the real task API: `FollowTask` on a stand-in player (a legacy-engine bot that holds still, or that is
  moved by the test), or `MoveTask` to an exact goal cell. Both go through `ActionPack.startPathTo` / `startApproachTo`, which is where
  `nav.engine` decides. The engine is chosen per bot (`NavEngineSelector.setBotEngine`, LEGACY or BARITONE); the global engine is not
  touched. The profile is strict survival (asserted by the fixture). The bot's inventory is empty except in `wallpick` and `sealed`
  (a stone pickaxe in the hotbar).
* Arrival: follow = the task is waiting, within 3.6 blocks (`STOP_DISTANCE` 3.0 + the arrival slack 0.5) and within 1.6 blocks of the
  player's height (a follower that waits below a target on a tower has not arrived); move = the task completed within 2.3 blocks of the
  goal cell. A course with legs (stairs up then down, house in then out) re-targets after each arrival.
* Metrics per run: reached, ticks (until every leg was reached, else the budget), damage taken (sum of health drops, falls included),
  blocks broken and placed (a snapshot of the arena volume compared with the cells around each bot every tick and at the end; a solid
  cell that became air or fluid is a break, the opposite a placement; a door or gate opening is not a change), ticks in water, ticks in
  lava (0 everywhere, not tabulated), the failure reason, and a detail line (route outcome, notices, Baritone routes started, blocks
  walked, per-leg ticks; for the moving target the gap statistics).
* Output: one `NAVCOURSE` line per run in the server log and appended to `<game dir>/nav_courses/results.tsv`
  (`build/run/gameTest/nav_courses/results.tsv`), plus a `nav_course_result` event in each bot's own log
  (`logs/minecraftai/sessions/<session>/by-bot/Nc<course><L|B>.log`, L = legacy, B = Baritone; the followed player is `...T`).
  `scripts/nav_courses_table.sh <results.tsv>` prints the table below.
* Asserted (a test without `_measurement`): no damage at all (so no fall beyond the vanilla-safe 3 blocks, no cactus, no lava), never in
  water or lava, never dead; the course was reached (or, for the sealed lake, the bot held on its bank, did not cross and told the player
  once or twice); no block broken while a walkable way exists (`noBreak` courses); the Baritone runs really started Baritone routes and the
  legacy runs have no Baritone instance; the moving target never gets more than 8 blocks ahead of the follower. Performance (ticks, walked
  distance, break counts on the sealed wall) is data, not an assertion.
* A `_measurement` test runs the same run and records the same line but asserts nothing about the outcome: it records a known failure of
  one engine. Five exist, all legacy: `house_door`, `fence_gate`, `ladder_shaft`, `lake_no_dry_path`, `cactus_field`
  (`navigation_course_game_tests_house_door_legacy_measurement` and so on). When a legacy gap is fixed the test is renamed to its asserting
  form (and its environment JSON with it).

Re-run: `bash /c/mcw/_tools/gt_filter.sh <worktree> <results> 'navigation_course_game_tests_*'` (about 3 minutes), then
`scripts/nav_courses_table.sh build/run/gameTest/nav_courses/results.tsv`.

## P3 timing preflight: scale one, explicitly unpaced

The historical course table is outcome/tick evidence, not permission to flip the default. In particular, a normal GameTest run
sets the legacy A* wall-clock allowance to 40x because its ticks run back-to-back, and it did not capture the complete engine or
server-tick costs. `nav.engine` remains `legacy` while this evidence is gathered and reviewed.

For a paired elapsed-duration capture, use the serial runner (five alternating repeats are the default):

```bash
bash scripts/dev/nav_measurement.sh <repo-or-worktree> <outdir> 5
# Or a smaller smoke capture, retaining the same artifact schema:
bash scripts/dev/nav_measurement.sh <repo-or-worktree> <outdir> 1 wall long
```

Choose an empty `<outdir>` outside the source tree: the runner refuses an in-tree bundle and requires a clean tree before it
creates any evidence files.

For each fixed course geometry it runs legacy then Baritone on odd repeats and Baritone then legacy on even repeats. It passes the
explicit `-Dminecraftai.nav.measurement=scale1` property. The test harness consequently sets the legacy A* allowance to exactly one,
then refuses to emit an evidence row unless the measured bots retain their requested per-bot engine, actual driver evidence matches the
requested engine (a Baritone row needs a Baritone admission or driven tick and no legacy fallback), the full engine and server tick both
have samples, and at least one matching planner/admission sample exists. It never changes the global config/default.

Keep every directory under `<outdir>/runs/`; do not reduce them with `nav_courses_table.sh` before review. Each contains:

* `results.tsv`: the existing `NAVCOURSE` outcome/tick/damage/edit/water row;
* `measurements.tsv`: one `NAVMEASURE` schema-1 row with provenance `gametest_unpaced`, legacy budget scale, engine-isolation result,
  duplicated route outcome, and count/average/p95/max for complete engine-owned bot ticks, complete server ticks, and planner calls;
  it also includes actual Baritone-driver tick, legacy-`ActionPack` update, and Baritone-fallback counts;
* `planner.tsv`: one raw `NAVPLAN` schema-1 row for every legacy A* invocation (including executor-time replans and return proofs) or
  Baritone inline admission, including caller wall duration, engine-reported search duration, nodes, moves and result; and
* the runner's full Gradle/server logs in `<outdir>/gt_logs/`.

“Complete engine-owned bot tick” means the whole `AIPlayerEntity.tick`, including the Baritone driver before/after vanilla physics or
the legacy `ActionPack.onUpdate`, rather than the old task-only profiler section. Separate driver counters distinguish a Baritone
admission/scheduler tick from a Baritone-driven tick and invalidate a Baritone row if it falls back to legacy. The server metric brackets
all of `MinecraftServer.tickServer`. The Baritone plan metric is its synchronous admission; its later worker planning is still visible in
the per-bot PATH log but is not relabelled as server-thread admission time. Outside an explicit capture the probes stop at a volatile
inactive gate before taking a timestamp or lock.

This is still **not production wall-clock/pace proof**. A Fabric GameTest is unpaced, the source tree has no automated normal-server
driver/RCON fixture, and a scale-one search budget only makes the legacy planner allowance factual. A default flip additionally needs
a separately captured normal-server, fixed-geometry run on the same JDK/heap with server pacing, plus the LOCAL profile-mod pack check.
No after-the-fact percentage tolerance is implied by these artifacts: under “at least equal everywhere,” any allowed jitter rule must
be chosen by the user before comparison.

## Courses

| # | id | Driver | Geometry (x east, z south; start to target) | Expectation |
|---|---|---|---|---|
| 1 | `wall` | follow | two-high stone wall across x = 0 from z = -9 to 5, gap z = 6..8 (detour about 12 blocks); (-7,0,0) to (7,0,0); empty hands | reach, no block broken |
| 1b | `wallpick` | follow | the same wall, stone pickaxe in the hotbar (breaking two blocks is cheap: does the bot still walk around?) | reach, no block broken |
| 2 | `sealed` | follow | two-thick natural stone wall over the whole width to the ceiling; stone pickaxe | reach (breaking is the only way; count the breaks) |
| 3 | `steps` | follow | a 1-high step, then a second one (target two blocks above the start) | reach, nothing broken |
| 4 | `pit` | follow | 3-wide, 5-deep crevasse across the course with a walkable end at z >= 6 (a fall is 5 blocks: 2 damage and no way out) | reach, no damage |
| 5 | `stairs` | move | 4 stair blocks up to a 5-long plateau (4 up), then 4 stair blocks down; goals: plateau, far floor | reach both legs |
| 6 | `lakedry` | follow | a one-deep lake x = 0..4, z = -9..3; dry ground at z >= 4 | reach, never in water |
| 7 | `lakenone` | follow | water over the whole width (x = 0..3), no dry way, 800 ticks | hold on the bank, dry, say so once (`follow_no_dry_route`) |
| 8 | `lava` | follow | lava moat over the whole width with a one-wide stone bridge at z = 4 (four cells off the line) | reach, no damage |
| 9 | `cactus` | follow | checkerboard of two-high cacti on sand, x = -3..3, z = -8..5, clear lane at z >= 6 | reach, no damage |
| 10a | `cliff3` | follow | target at the bottom of a basin, vertical 3-block rim (vanilla-safe) | reach, no damage |
| 10b | `cliff6` | follow | same with a 6-block rim: no way down but a fall or digging | no damage (holding is fine) |
| 11 | `house` | follow | 7x7 oak-plank house with roof and one closed door in the west wall; target inside, then teleported outside again (two legs) | reach both, no wall broken |
| 12 | `gate` | follow | oak fence (1.5 high) across the whole width with one closed fence gate at z = 3, off the line | reach, fence not broken |
| 13 | `ladder` | move | 5x5, 4-high stone tower whose only way up is a ladder on its west face; goal on top | reach, nothing broken |
| 14 | `forest` | follow | oak trunks every 4 blocks with a persistent canopy (leaves at y 2..5, some at head height); 28 blocks across | reach, no damage |
| 15 | `moving` | follow | the player walks a 16x8 loop at 0.2 blocks/tick around a stone pillar for 400 ticks, then stops | keep up (never more than 8 ahead), settle within 3.6 |
| 16 | `twobots` | follow | two bots, one player, wall with a one-wide gap between them | both reach |
| 17 | `long` | move | 76 blocks along x (five chunk borders) with a wall, a pillar and a pit on the line; chunks force-loaded | reach |

## Results

Two full runs of the suite (38 tests each, all green) gave the same table except for one tick: `ladder` on Baritone took 76 and 73 ticks,
`long` on Baritone 289 and 290. The table is the first run. Ticks are game ticks from the moment the bot starts; damage is in health
points (2 = one heart); "n" with reason `timeout` means the budget of the course ran out.

| course | engine | reached | ticks | damage | broken | placed | water ticks | failure reason |
|---|---|---|---|---|---|---|---|---|
| wall | legacy | y | 70 | 0.0 | 0 | 0 | 0 | - |
| wall | baritone | y | 61 | 0.0 | 0 | 0 | 0 | - |
| wallpick | legacy | y | 70 | 0.0 | 0 | 0 | 0 | - |
| wallpick | baritone | y | 61 | 0.0 | 0 | 0 | 0 | - |
| sealed | legacy | y | 113 | 0.0 | 4 | 0 | 0 | - |
| sealed | baritone | y | 100 | 0.0 | 4 | 0 | 0 | - |
| steps | legacy | y | 71 | 0.0 | 0 | 0 | 0 | - |
| steps | baritone | y | 46 | 0.0 | 0 | 0 | 0 | - |
| pit | legacy | y | 68 | 0.0 | 0 | 0 | 0 | - |
| pit | baritone | y | 61 | 0.0 | 0 | 0 | 0 | - |
| stairs | legacy | y | 136 | 0.0 | 0 | 0 | 0 | - |
| stairs | baritone | y | 96 | 0.0 | 0 | 0 | 0 | - |
| lakedry | legacy | y | 86 | 0.0 | 0 | 0 | 0 | - |
| lakedry | baritone | y | 69 | 0.0 | 0 | 0 | 0 | - |
| lakenone | legacy | n | 800 | 0.0 | 0 | 0 | 0 | held_no_route |
| lakenone | baritone | n | 800 | 0.0 | 0 | 0 | 0 | held_no_route |
| lava | legacy | y | 85 | 0.0 | 0 | 0 | 0 | - |
| lava | baritone | y | 69 | 0.0 | 0 | 0 | 0 | - |
| cactus | legacy | y | 82 | 1.0 | 0 | 0 | 0 | - |
| cactus | baritone | y | 79 | 0.0 | 0 | 0 | 0 | - |
| cliff3 | legacy | y | 89 | 0.0 | 0 | 0 | 0 | - |
| cliff3 | baritone | y | 44 | 0.0 | 0 | 0 | 0 | - |
| cliff6 | legacy | n | 500 | 0.0 | 0 | 0 | 0 | timeout |
| cliff6 | baritone | n | 500 | 0.0 | 0 | 0 | 0 | timeout |
| house | legacy | n | 900 | 0.0 | 0 | 0 | 0 | timeout |
| house | baritone | y | 69 | 0.0 | 0 | 0 | 0 | - |
| gate | legacy | n | 600 | 0.0 | 0 | 0 | 0 | timeout |
| gate | baritone | y | 63 | 0.0 | 0 | 0 | 0 | - |
| ladder | legacy | n | 500 | 0.0 | 2 | 0 | 0 | timeout |
| ladder | baritone | y | 76 | 0.0 | 0 | 0 | 0 | - |
| forest | legacy | y | 105 | 0.0 | 0 | 0 | 0 | - |
| forest | baritone | y | 95 | 0.0 | 0 | 0 | 0 | - |
| moving | legacy | y | 421 | 0.0 | 0 | 0 | 0 | - |
| moving | baritone | y | 421 | 0.0 | 0 | 0 | 0 | - |
| twobots | legacy | y | 70 | 0.0 | 0 | 0 | 0 | - |
| twobots | baritone | y | 53 | 0.0 | 0 | 0 | 0 | - |
| long | legacy | y | 290 | 0.0 | 0 | 0 | 0 | - |
| long | baritone | y | 289 | 0.0 | 0 | 0 | 0 | - |

`lakenone` is expected to end unreached: both engines held on the bank without entering the water (`held_no_route` = the budget ran out
with the bot dry and on its own side). `cliff6` is unreached on both, without damage.

Blocks walked (legacy/Baritone; the straight line is 14..16 on most courses): `wall` 16/16, `lakedry` 21/18, `lava` 21/18, `stairs` 31/26,
`gate` 51 (legacy never gets through) / 16, `lakenone` 124 / 9 (legacy wanders along the bank), `moving` 64/73, `long` 76/79.

## With the pace policy

The same 38 tests after the pace policy (`docs/BOT_PACE.md`: sprint far from the goal, walk for the last 4.5 blocks, the vanilla sprint rules). One full run; "before" is the table above (pace did not exist). Damage is 0.0 everywhere (the legacy `cactus` point is gone). `reached` did not change in any row.

| course | engine | reached | ticks before | ticks with pace | change | damage |
|---|---|---|---|---|---|---|
| wall | legacy | y | 70 | 69 | -1 | 0.0 |
| wall | baritone | y | 61 | 64 | +3 | 0.0 |
| wallpick | legacy | y | 70 | 69 | -1 | 0.0 |
| wallpick | baritone | y | 61 | 64 | +3 | 0.0 |
| sealed | legacy | y | 113 | 114 | +1 | 0.0 |
| sealed | baritone | y | 100 | 104 | +4 | 0.0 |
| steps | legacy | y | 71 | 65 | -6 | 0.0 |
| steps | baritone | y | 46 | 48 | +2 | 0.0 |
| pit | legacy | y | 68 | 67 | -1 | 0.0 |
| pit | baritone | y | 61 | 41 | -20 | 0.0 |
| stairs | legacy | y | 136 | 125 | -11 | 0.0 |
| stairs | baritone | y | 96 | 102 | +6 | 0.0 |
| lakedry | legacy | y | 86 | 70 | -16 | 0.0 |
| lakedry | baritone | y | 69 | 73 | +4 | 0.0 |
| lakenone | legacy | n | 800 | 800 | 0 | 0.0 |
| lakenone | baritone | n | 800 | 800 | 0 | 0.0 |
| lava | legacy | y | 85 | 69 | -16 | 0.0 |
| lava | baritone | y | 69 | 73 | +4 | 0.0 |
| cactus | legacy | y | 82 | 79 | -3 | 0.0 |
| cactus | baritone | y | 79 | 84 | +5 | 0.0 |
| cliff3 | legacy | y | 89 | 90 | +1 | 0.0 |
| cliff3 | baritone | y | 44 | 48 | +4 | 0.0 |
| cliff6 | legacy | n | 500 | 500 | 0 | 0.0 |
| cliff6 | baritone | n | 500 | 500 | 0 | 0.0 |
| house | legacy | n | 900 | 900 | 0 | 0.0 |
| house | baritone | y | 69 | 81 | +12 | 0.0 |
| gate | legacy | n | 600 | 600 | 0 | 0.0 |
| gate | baritone | y | 63 | 66 | +3 | 0.0 |
| ladder | legacy | n | 500 | 500 | 0 | 0.0 |
| ladder | baritone | y | 76 | 75 | -1 | 0.0 |
| forest | legacy | y | 105 | 103 | -2 | 0.0 |
| forest | baritone | y | 95 | 99 | +4 | 0.0 |
| moving | legacy | y | 421 | 421 | 0 | 0.0 |
| moving | baritone | y | 421 | 421 | 0 | 0.0 |
| twobots | legacy | y | 70 | 99 | +29 | 0.0 |
| twobots | baritone | y | 53 | 57 | +4 | 0.0 |
| long | legacy | y | 290 | 281 | -9 | 0.0 |
| long | baritone | y | 289 | 292 | +3 | 0.0 |

Baritone routes are 2 to 6 ticks slower (3 to 5 on most): the last 4.5 blocks are walked (about 21 ticks instead of 16). `house` has two legs (into the house, out again), so +12. Legacy routes are the same within a few ticks, mostly a little faster (`lakedry`, `lava` -16, `stairs` -11, `long` -9; not analysed further, most likely the sprint now follows the distance to the goal instead of each sub-target), with one exception, `twobots` +29, which is a symmetry artefact of the course and not a pace defect: both bots now sprint in step and reach the one-wide gap together (see "Course timing with pace on" in `docs/BOT_PACE.md`). `pit` on Baritone (61 -> 41) is not explained by pace (walking cannot shorten a route): the pre-pace table was measured on an older tree.

## Verdict per course

| course | verdict | why |
|---|---|---|
| wall, wallpick | equal outcome, Baritone faster (61 vs 70 ticks) | both walk around (16 blocks) and break nothing, with and without a pickaxe |
| sealed | equal outcome, Baritone faster (100 vs 113) | both dig exactly the minimal 4 blocks (two columns, two high) and no more |
| steps | Baritone better (46 vs 71) | |
| pit | equal, Baritone faster (61 vs 68) | neither falls in |
| stairs | Baritone better (96 vs 136, 26 vs 31 blocks) | both legs |
| lakedry | Baritone better (69 vs 86) | both dry |
| lakenone | **Baritone better** | Baritone: 1 notice, stands at the bank (walked 9 blocks), never wet. Legacy: dry and never crosses, but wanders along the bank (124 blocks in 800 ticks), starts hand-mining the bank floor (about 20 `mine_start` events) and told the player 0 or 1 times (0 in one run, 1 in another) |
| lava | Baritone better (69 vs 85) | neither touches lava, no damage |
| cactus | **Baritone better** | Baritone 0 damage; legacy 1 point (`damage_taken source='cactus'`) brushing the cacti at the edge of the lane |
| cliff3 | Baritone better (44 vs 89) | both take the drop without damage |
| cliff6 | equal (safe), Baritone communicates | neither falls or takes damage. Baritone: 4 route starts, a `follow_no_progress` preemption and one no-route notice; legacy waits at the rim (`waiting=true`, 8.6 blocks from the player) and says nothing |
| house | **legacy fails** | legacy: `pathfinding_failed: GOAL_UNREACHABLE` for the target inside (a closed wooden door is a wall to its A*), it stays at the door and reports no route; Baritone opens the door, in 36 ticks, and out again in 33 |
| gate | **legacy fails** | same for the fence gate: legacy wanders near the fence (51 blocks, 15 `mine_start` events), Baritone walks to the gate and through it in 63 ticks |
| ladder | **legacy fails** | legacy has no ladder movement: `MoveTask` falls back to digging straight at the goal and hand-mines the tower (2 blocks broken), Baritone climbs in 73-76 ticks and breaks nothing |
| forest | Baritone faster (95 vs 105) | both weave through trunks and canopy |
| moving | **Baritone better** | Baritone: mean gap 3.71, max 4.3, within 4.5 blocks for 100 % of the ticks after catching up; legacy: mean 4.12, max 5.4, within 4.5 for 64 %. Neither is ever lost (max gap below 8). The finish tick (421) is set by the player stopping |
| twobots | equal outcome, Baritone faster (53 vs 70) | both pass the one-wide gap one after the other, no damage |
| long | equal (289-290 vs 290) | Baritone 79 blocks walked, legacy 76 |

## Legacy-worse cases, with evidence

Source: the per-bot logs of the runs (`Nc<course>L.log`), and the code: `pathfinding/` has no door, fence-gate or ladder movement (the only
hit for `DoorBlock|FenceGate|LadderBlock|CLIMBABLE` is the standability rule that lets a bot *stand in* a climbable block).

1. **Closed doors and fence gates are walls** (`house`, `gate`). `NchouseL`: `follow_direct_walk_refused ... path_reason='pathfinding_failed:
   GOAL_UNREACHABLE' reason='cell_not_walkable'` and `follow_no_dry_route` (the message the player gets is "no dry way", which is wrong: the
   way is a door), then 18 `mine_start` events. `NcgateL`: 15 `mine_start` events, 51 blocks walked without getting through. Nothing was
   broken within the budget only because breaking planks and fences by hand is slow; with a tool it would dig the house. This is the
   original complaint in another form: legacy does not path *through* the thing that is meant to be opened.
2. **Ladders** (`ladder`). `NcladderL`: `move_waypoint` x5, then `mine_start` / `miner_slow_dump` on the tower's stone (2 blocks broken,
   goal not reached in 500 ticks); `MoveTask` reports `Digging to <goal>`. Any vertical route that needs a ladder fails or digs.
3. **Sealed lake** (`lakenone`, measurement). Safe (dry, no crossing) but noisy and unreliable: 124 blocks walked along the bank, about 20
   `mine_start` events on the bank's floor edge, and the "no dry route" notice appeared in one of two runs only (0 vs 1 in 800 ticks). The
   bedrock-sealed fixture of `FollowTaskGameTests.followWithNoRouteAcrossWaterStaysDryInsteadOfWalkingStraightIn` gets its single notice;
   this course, sealed by the arena ring only, does not always.
4. **Cactus** (`cactus`, measurement). `NccactusL`: `damage_taken amount='1.0' source='cactus' hp='20.0->19.0'`; the string-pulled route
   (`path_skip` x12) cuts along the cacti that border the lane. Baritone's route keeps clear (0 damage).
5. **Cliff rim** (`cliff6`, not a safety failure). Legacy reports `waiting=true` 8.6 blocks from the player and no notice; the player
   cannot tell the follower has given up. Baritone tells them (`notices=1`).
6. **Speed and closeness.** Baritone is faster on 12 of the 14 courses that both engines reach (equal on `long`, and `moving` ends when the
   player stops) and stays closer to a moving player (above).

## Baritone observations (no worse outcome, but worth watching)

* **Route churn while the target moves.** `moving`: 41 Baritone route starts in 421 ticks (about one per 10 ticks) and 73 blocks walked
  for the player's 80. The follow task's re-goal bound (60) holds, but each start runs a synchronous admission search; on a busy server
  this is the cost to look at first. Legacy re-plans every 40 ticks (`REPATH_TICKS`).
* **Retry rhythm at a bank.** `lakenone`: 19-20 route starts in 800 ticks (a `baritone_admission` each, all ending `path_incomplete`),
  one notice. Correct and dry, but a follower parked at a sealed bank keeps asking every 40 ticks.
* **Refused scan process on a cliff.** `cliff6`: `baritone_refused op='scan_process' reason='scan_process_refused' detail='mine'` (strict
  survival refuses Baritone's own dig-down process, as designed), then the `follow_no_progress` preemption. Safe.
* `long`: 79 blocks walked for 76 straight-line, 289-290 ticks: the same as legacy's segmented waypoint relay.

## Caveats of the measurement

* One geometry per course; the distances are short (10-25 blocks, 76 for `long`), so ticks separate the engines by tens of ticks, not
  seconds. The runs are deterministic to within a tick or two (two suite runs), except legacy's notice count on `lakenone`.
* The one-wide lava bridge and the checkerboard cactus field are deliberately unforgiving; a wider bridge would not change the ranking.
* Not covered here (owned by other work): parkour, water-bucket falls, vines, avoidance settings, containers, farming.
* The inventory is empty (bar the pickaxe), so Baritone cannot place blocks: `placed` is 0 everywhere. A course with a bot that carries
  blocks (pillar and bridge decisions) is a separate item.
* The arena's bedrock ring seals every course; the sealed lake and the cliff are therefore "sealed by construction", not by a
  mob-proof wall.

## P3 (default flip)

Do not flip the default. The pace-on table records Baritone later than legacy on fixed geometry for `lakedry` (73 vs 70 ticks), `lava`
(73 vs 69), `cactus` (84 vs 79), and `long` (292 vs 281). That alone fails the stated “at least as good everywhere” condition; the
unpaced P3 artifacts are additional diagnostic evidence, not a substitute for a normal-server paced capture or a user-chosen jitter rule.
`nav.engine=legacy` remains the default.
