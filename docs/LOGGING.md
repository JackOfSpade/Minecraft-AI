# Smart Logging

## Design Goals

Every bot log line is automatically tagged with the scope of the current request (`scopeOf` in
`log/BotLog.java` reads it live from `TaskManager.activeOrigin(bot)`, so callers never have to
pass it manually). To debug a single player command, you just filter by scope instead of digging
through the entire session's log. Logs are saved in a directory per server startup, under
`logs/minecraftai/sessions/<session-id>/`; `MinecraftAiConfig.Logging.maxSessions` (default 3)
controls how many of the most recent startups' logs are kept, with older ones automatically
pruned (`pruneOldSessions` in `log/BotLogWriter.java`).

The design deliberately avoids logging everything — it only records the information necessary to
determine whether a given category of request completed normally. Log volume is inversely related
to debugging cost: logging too much actually slows down investigation.

## Expected Workflow

After a player has played for a while, hand the logs to an AI (not limited to Claude — any
assistant that can read code and logs works) to check for bugs.

## Self-Improvement Principle

**If, during investigation, the log for some scope turns out to be insufficient to tell whether
the request succeeded or failed, or why it failed — that is itself a defect in the logging
system, not something to shrug off.** Handle it the same way you'd handle any other bug: find the
code location that should have recorded this information but didn't, and add the missing
`BotLog.*` call so the next occurrence of that same kind of request produces a log sufficient for
debugging. Don't just note "logging was insufficient" in this investigation's conclusion and leave
the same blind spot for next time.

When adding logging, keep the scope tight to "what this specific category of request needs" —
don't raise the verbosity across an entire task or an entire category just because you found one
gap; that would defeat the purpose of "log only what's needed".

## General Task Log Coverage Audit (2026-09-27)

`task/TaskManager.java` records these common events for every task type: `task_assigned`
(including `describe()`), `task_paused`, `task_resumed`, `task_cancelled`, `task_completed`, and
`task_failed` (including `failureReason()`) — so even a task that doesn't write a single log line
of its own still has start/end records. Following the self-improvement principle above, we went
through the task classes that had zero logging or noticeably thin logging at the time
(`FarmTask`, `FollowTask`, `GuardTask`, `StripMineTask`, `CraftTask`, and about 29
others in total), using the standard "if this request actually failed, can you tell which step and
why from the logs alone, without reading the source?" Conclusions were handled case by case: where
a single generic failure-reason string was being reused across multiple distinct causes, the
string was made more specific, or a log line with context was added before the failure point;
where a decision (abandoning one approach for another, silently skipping something, reporting
partial completion as success) would only surface later and left no trace beforehand, a log line
was added at the point the decision is made; tasks that already distinguish each failure path with
a distinct, self-explanatory reason string were left unchanged (several tasks remained at zero
changes after the audit, e.g. `HoldTask`, `FishTask`, `EatTask` — they were already adequate, and
nothing was added just to look like work had been done). No per-tick logging was added, and no
task category received a blanket increase in verbosity.

## Mining Assist Logging (P0 Shadow Mode)

Mining Assist's sensor only observes and logs during real tasks — it never changes the bot's
behavior (see [MINING_ASSIST.md](MINING_ASSIST.md) for details). This category of request
originally had no logging, so per the principle above we added the events listed below; to honor
"log only what's needed", the output is deliberately kept low-volume: no per-tick, per-ray, or
per-ordinary-discovery logs — only session start/end, POI band changes, rare finds (at most 6 per
statistics window), and one cost summary per minute. The config option
`miningAssist.sense.shadowLog=false` turns off this shadow output; config, gating, and error logs
are unaffected. All events can be pulled out with `grep 'event=assist_'`.

| Category | event | When it's written | Key fields |
|---|---|---|---|
| CONFIG | `assist_config`, `assist_harness_default` | One `assist_config` line at startup; when the test harness calls `setHarnessDefaultOff`, an additional `assist_harness_default` line records the final outcome after the harness default is turned off | `mode`, `mode_source`, `harness_off`, `deterministic`, `rays_per_tick`, `global_rays_per_tick`, `adaptive_throttle`, `shadow_log`, `edits_sidecar`; `assist_harness_default` carries `harness_default_off`, `harness_off`, `mode` |
| CONFIG(WARN) | `assist_config_warning` | One line at startup for each config value that was ignored, truncated, or adjusted | `note` |
| CONFIG(WARN) | `assist_config_read_failed`, `assist_edits_load_problem` | `minecraftai.json` or the placement-record file could not be read (fails open, continuing with defaults/empty records) | `path`, `error`, or `problem` |
| TASK | `assist_gate` | When a bot's gating decision changes (cached per bot for 20 ticks) | `enabled`, `deny` (`mode_off`/`harness_off`/`origin`/`audit_session`/`tps_degraded`), `mode`, `forced` |
| TASK | `assist_sense_enabled` | A sensing session starts (mining-type task, underground, gate allows it). The rebuild that follows the exception guard discarding state does not count as a new session and is not logged (the failure line already covers it) | `task`, `mode`, `dimension`, `feet` |
| TASK | `assist_sense_disabled` | Session ends: 40 consecutive ticks with no sensing; brief interruptions are not logged | `reason` (`not_mining_task`/`gate_closed`/`surface`), `deny`, `rays_total`, `sweeps_total` |
| TASK | `assist_state_released` | Bot has stopped mining for a full 2400 ticks and its state is released | `idle_ticks`, `rays_total`, `sweeps_total`, `sightings`, `hazards` |
| TASK | `assist_poi_band` | A change in the shadow POI score's band (`NONE`/`POSSIBLE`/`CAVERN_ONLY`/`STRUCTURE_CERTAIN`/`MANDATORY`); quiet bots produce nothing. At least 200 ticks must separate two lines for the same bot: changes within that interval are deferred (only written if the band still differs from the last line once the interval has passed); back-and-forth jitter that returns to the original band produces no line at all | `band`, `t`, `s`, `c`, `e`, `cells`, `label`, `confirmed`, `trigger`, `anchor`, `biome`, `withheld` (number of band changes suppressed since the last line) |
| TASK | `assist_sighting` | The sensor newly nominates a rare block whose raw value is at least `detour.announceMinValue` (default 90); at most 6 lines per window | `block`, `pos`, `value`, `dist` |
| TASK | `assist_cavern_channel_disabled` | At most one line per bot: the cavern-openness channel is disabled because the dimension isn't in the allowed list or the radius is under 12 | `dimension`, `radius`, `reason` |
| PROFILE | `assist_sense_summary` | One line per bot per minute during sensing, plus one more with `final=true` when the session is released | `rays`, `steps`, `sweeps`, `breakthroughs`/`breakthroughs_deferred` (breakthrough restarts deferred within the 40-tick interval), `peeked_breaks`/`breaks_unconfirmed` (the cell wasn't observed as empty after mining: rejected or unobservable, nothing assumed), `poi_bands_withheld`, `step_ms_avg`/`step_ms_max`, `poi_ms_avg`/`poi_ms_max`, `throttled_out`, `sightings`, `best_sighting`, `hazards`, `poi_window`, `open_fraction` |
| ERROR | `assist_tick_failed` | The coordinator's exception guard trips: at most one line per bot per minute; that bot's state is discarded and sensing is paused for 100 ticks | exception and stack trace, `task`, `feet` |
| ERROR | `assist_hook_failed`, `assist_edits_hook_failed`, `assist_edits_save_failed` | The mining/placement hook fails, or the placement record fails to write to disk (hooks are only logged the first few times) | `hook`/`where`/`path` |

Use these logs to answer questions about a single mining request:

- Whether the sensor ran, and why not: check `reason` on `assist_sense_enabled`/`assist_sense_disabled`, and `deny` on `assist_gate`;
- How much it cost: `step_ms_*`, `poi_ms_*`, `rays`, `throttled_out` on `assist_sense_summary` (measure first, then talk about letting it act — design invariant I11);
- What it thought it saw: `assist_poi_band`, `assist_sighting`, and `best_sighting` in the summary. These are only the sensor's nominations — they don't mean the bot actually mined it.

If an investigation finds these logs still can't explain why the sensor failed to nominate an ore
that was later mined, follow the self-improvement principle above: add only the missing line for
this sensing-request category, and don't raise verbosity across the board.

## Mining Assist Logging (P1 Detour Mining)

P1 adds an "en-route detour" to the mining task: while pathing, it mines valuable ore it naturally
spots along the way (see design section 4). The default remains `sense` (no detouring); detouring
requires explicitly setting `miningAssist.mode` to `detour` (or `all`). The table below lists the
events P1 adds, formatted consistently with the `assist_*` rows above — likewise no per-tick
logging, only one line each at phase boundaries, skips, exceptions, and completion.

| Category | event | When it's written | Key fields |
|---|---|---|---|
| TASK | `ore_dig_detour_start` | A detour begins | `block`, `pos`, `value`, `score`, `cluster`, `members`, `pose`, `zero_transit`, `anchor`, `locked` |
| TASK | `ore_dig_detour_skip` | A candidate or vein member is skipped (excluded, already claimed, gone, no standable position, route too long, …); at most one line per cell per 600 ticks | `reason`, `pos`, `block`, `value` |
| TASK | `ore_dig_detour_route` | Every pathfinding attempt to approach, chase a dropped item, or return | `leg` (`approach`/`chase`/`return`), `to`, `result`, `attempt`, `reason` |
| TASK | `ore_dig_detour_break` | Each block mined | `pos`, `block`, `breaks`, `members`, `lease_left` |
| TASK | `ore_dig_detour_seal` | Each time a fluid exposed by mining is sealed | `cell`, `seals` |
| TASK | `ore_dig_detour_drop_lost` | Waiting for a dropped item times out, or its landing spot isn't standable | `cell`, `waited`, `reason` (`timeout`/`no_stand`) |
| TASK | `ore_dig_detour_abort` | The detour is aborted (including an abort triggered by a pause) | `reason` (the abort reasons from design section 4.12), `phase`, `pos`, `breaks` |
| TASK | `ore_dig_detour_end` | A detour finishes completely (FINISH) | `reason`, `breaks`, `members`, `seals`, `drops_lost`, `ticks`, `abort` |
| TASK | `ore_dig_detour_orphan` | The coordinator finds that a published detour has lost its owner (the task was replaced, ended, or its state expired), and releases the claim and clears the published tuple on its behalf | `cause` (`owner_changed`/`not_running`/`phase_idle`/`stale`), `phase`, `claims_released` |
| TASK | `ore_dig_detour_cursor_drift` | `restoreAnchorNumbers` finds the tracked position no longer matches the anchor recorded before departure, and has corrected it against the anchor | `dir`, `leg`, `steps`, `len` |
| WARN | `ore_dig_detour_return_rebased` | The return path fails repeatedly, forcing the detour to be abandoned in place and disabling further detours for this task (logged loudly, for after-the-fact review) | `reason`, `unsafe`, `at`, `anchor`, `breaks` |
| DANGER | `ore_dig_detour_lava_claimed` | The detour claims a lava hazard itself (bypassing the usual Evade/pause path) | `lava`, `phase` |
| TASK | `ore_dig_detour_resume_return` | When resuming a detour after a pause, the start/end of an attempt to walk back to the anchor | `face`, `attempt`, `result` |

A detour never permanently fails the task (design invariant I8): if nothing worth mining is found
along the way, or the detour hits any safety threshold mid-route, it just quietly gives up/aborts
and the original mining task continues as normal. These events therefore exist to answer "did this
detour happen, why didn't it, and was it worth it": whether ore worth detouring for was found is
in `ore_dig_detour_start`/`_skip`, what happened mid-route is in `_abort`/`_route`, and how many
blocks a detour actually netted is in `_end`.

## Auditable Gather / Mining Logging

Prompted by a real question: the user asked a bot to gather 32 logs and then wanted to confirm
from the logs alone that those logs were actually broken and physically picked up in
`strict_survival`, not spawned in or force-granted. Before this section, that could only be
reconstructed indirectly — `mine_complete` carried no fields at all, `GatherQuotaTask` logged
individual pickup events but no per-unit gain record or end-of-task summary, inventory only showed
up inside the periodic `diag_snapshot` line (truncated in practice), and privileged capability
decisions (`FORCED_PICKUP`, `HIDDEN_BLOCK_SCAN`) were logged individually but never summarized per
task. Per the self-improvement principle above, this was a genuine gap: the events below close it.
Every one of them is observation only — none of it changes what a task actually counts, decides, or
does; see the "Constraints" note against each event.

| Category | event | When it's written | Key fields |
|---|---|---|---|
| ACTION | `mine_complete` | `MiningController` finishes breaking a block successfully (see `ActionPack.tickMining`) | `block` (registry id of the block, captured before the break), `pos`, `tool` (held item id, or `empty`), `ticks` (break duration) |
| ACTION | `gather_unit` | `GatherQuotaTask`'s counted total for an accepted item increases | `item`, `delta`, `total`, `target`, `source` (`pickup` when attributable to a block this task broke, with that block's `pos` included; `unattributed` otherwise, e.g. a player handed the bot an item mid-task) |
| ACTION | `gather_summary` | Exactly one line whenever a `GatherQuotaTask` ends, for any reason (complete, fail, abort, cancel) | `item`/family, `target`, `baseline` (accepted-item inventory count at task start), `final` (at task end), `gained` (sum of `gather_unit` deltas), `breaks` (blocks of the family this task itself broke), `pickups` (confirmed physical pickups), `pickup_misses`, `unattributed_gains`, `forced_pickups` (`FORCED_PICKUP` decisions allowed during this task — must be 0 in `strict_survival`), `capability_denials` (privileged decisions denied during this task), `elapsed_ticks`, `outcome`, `consistent` (see below) |
| ACTION | `inventory_delta` | A bot's inventory differs from the previous sample, sampled at the same cadence as `diag_snapshot` (every ~2s, driven from the same per-tick call site — see `log/InventoryAudit.java`); only written when something actually changed | `task` (active task name), `viewer` (name of a player with this bot's inventory screen open, or `none`), `gained` (e.g. `minecraft:spruce_log+1`), `lost` (e.g. `minecraft:torch-1`); both are bounded to a handful of items with a `+N more` tail if more changed at once |

`gather_summary`'s `consistent` field (`log/GatherConsistency.java`) is `true` only when
`gained <= breaks * max_drops_per_broken_block + unattributed_gains && forced_pickups == 0` — i.e.
every gained item can be explained by what the task actually broke (bounded by that item family's
normal drop count per block — 1 for logs) plus whatever arrived unattributed, and the task never
used a forced pickup. A `false` here is the sign to go dig further; it does not by itself prove
anything was faked (e.g. a player physically handing the bot logs is unattributed and legitimate,
just outside what this task can attribute to its own mining).

**Constraints these events hold to:** they add fields, never rename or remove any existing event or
field (GameTests such as `GatherPickupGameTests` and external scripts may read the old ones);
`gather_unit`/`inventory_delta`'s inventory scans only run when their log category is actually
enabled (`BotLogWriter.enabled`), so a quiet run pays nothing beyond that one check; and none of
this logic feeds back into `GatherQuotaTask`'s phase/`countSoFar`/`targetPos` decisions.

### Auditing a gather

To confirm whether a "gather N `<item>`" request was legitimately fulfilled, read (for that bot,
filtered to the request's scope — see "Design Goals" above) in this order:

1. **`gather_summary`** — the one-line verdict. Check `consistent=true`, `forced_pickups=0`, and
   that `outcome` is what you expect (`complete` vs. a failure/cancel reason). If `consistent` is
   `false` or `forced_pickups > 0`, treat the run as suspect and go to step 2.
2. **`gather_unit`** — the per-gain ledger. Every line with `source=pickup` names the exact block
   position it came from; sum their `delta`s and compare against `gather_summary`'s `breaks` (each
   pickup should trace back to one of this task's own `mine_complete`/`exact_block_broken`
   breaks). Lines with `source=unattributed` are gains this task can't attribute to its own
   mining — legitimate (a player handing over items) but worth a second look if there are many.
3. **`mine_complete`** — cross-check each break `gather_unit` points at: same `pos`, a `block`
   that actually produces the target item, and a plausible `ticks` (a suspiciously low tick count
   for a hard block is a sign of a modified client or a forced break).
4. **`capability_decision`** (from `mode/CapabilityRuntime.java`) — for the request's scope, look
   for any `capability=FORCED_PICKUP allowed=true` line; there should be none in `strict_survival`,
   and `gather_summary`'s `forced_pickups` should already have flagged it.
5. **`inventory_delta`** — an independent cross-check: the bot's actual inventory changes over the
   same window should line up with what `gather_unit` reported gained, and `viewer` tells you
   whether a player had the inventory screen open at the time (relevant if items appeared that
   `gather_unit` didn't explain).

## Log Volume Control (2026-09-29)

Analysis of a real two-hour session (4.6 MB `all.log`) found three recurring events making up most of
the file. They are now throttled by pure, unit-tested policies; nothing is silent: every suppressed
line is either counted on the next line that is written or summarized.

| Event | Before | Now |
|---|---|---|
| `diag_snapshot` (57% of the log) | Every bot, every 2 s, unconditionally | Decided every 2 s by `log/DiagnosticSnapshotGate`: written when the bot's **state** changed (task, task state/phase, goal/step, air, game mode, lava/submerged; at most once per 2 s), or when it **moved** >= 3 blocks or its **loadout** changed (health, food, held item, inventory; at most once per 5 s), otherwise a **heartbeat** every 30 s. New fields `reason` (`first`, `state`, `moved`, `loadout`, combinations, `heartbeat`) and `skipped` (snapshots not written since the previous line). The nearby-entity scan (and its capability decision) only runs when a line is actually written. |
| `capability_decision` (18%) | One INFO line per (capability, context) every 5 s, forever | `mode/CapabilityAuditThrottle`: the **first occurrence** of each (bot, capability, allowed, reason, context) is always logged in full, so a denial (or an allowed decision, which the strict-survival canary GameTests grep for) is never lost. Repeats are counted per (bot, capability, allowed, reason) and reported as one `capability_decision_summary` per 60 s (`repeats`, `window_ticks`, `contexts`), plus a flush when the bot is removed and for every bot on a world boundary, server stop or reload (`CapabilityRuntime.clearAll`). A lone repeat that nothing pushes out is reported by a sweep every 5 s (`CapabilityRuntime.flushDue`), so it appears within 60 s + 5 s. `MANUAL_TELEPORT` and allowed `EMERGENCY_TELEPORT` are still logged every time. |
| `tps_guard_state` (278 events) | Flipped `degraded` true/false several times per second around a single 55 ms threshold, also toggling the mining-assist gate (`assist_gate deny=tps_degraded`) | `observe/TpsDegradationLatch`: enter above 62.5 ms (16 TPS) only after 10 consecutive samples, exit at or below 58 ms only after 40 consecutive samples, and each state lasts at least 100 (degraded) / 40 (normal) ticks. The value is an EMA of the interval between `END_SERVER_TICK` calls, whose floor is 50 ms (a healthy server paces itself at 50 ms) and which reads 53-59 ms in this pack's normal busy operation, so both thresholds sit outside that band and are reachable. Only real transitions are logged (new field `previous_state_ticks`). |

`snapshot_slow` (`perception/PerceptionCollector`) is WARN only above 50 ms (a full tick), DEBUG for
11-50 ms and absent below (`slowSnapshotLevel`, unit-tested); the 76 WARNs seen in the session were all
11-32 ms.

Gather's treeless-area prospect scan (`gather_prospect_empty` / `gather_prospected`) is a resumable
`mining/OreProspector.Scan` advanced at most ~2 ms per server tick instead of one 200-600 ms tick; those
two events gained `scan_steps`, `scan_max_step_us` (and `scan_total_ms` when empty). The visibility rules
are unchanged: every candidate is still ray-checked before its block state is read.

Gather's wide survey (search radius 32/48, `HarvestCore.NearestScan`) and the en-route explore scan are
resumable in the same way (2 ms per tick; at most one walk-reachability A*, itself capped at 30 ms, per
tick). What still lands in a single tick, deliberately: the path plan that follows a finished scan
(`startPathTo` towards a found target, or the up-to-5 waypoint A* runs of `roamToNewArea` /
`startExplore`, 250-440 ms cold in the strict profile). Those are the A* pathfinder's own bounded
searches (node and millisecond caps) and cannot be suspended half-way without a resumable pathfinder,
which is out of scope here; the scan work itself is no longer part of that tick. Every scan is dropped
when the task leaves the phase that owns it (`exploreScan` on every exit from EXPLORE, `surveyScan` on
leaving SURVEY) or when the bot has moved more than 8 blocks (20 for explore) since it began.
