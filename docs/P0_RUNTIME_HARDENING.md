# P0 Runtime Hardening

Status: Implementation complete (current working tree, not yet committed)
Goal: Eliminate current release-blocking issues via small, independently verifiable commits.
Principle: Define the contract first, then modify the implementation; do not use P0 as an excuse to rewrite existing Tasks.

## Implementation Status

Updated: 2026-07-10

| Scope | Status | Current Evidence |
|---|---|---|
| P0-07a Test bootstrap | Completed | 19 test classes, 68 JUnit tests, plus 3 GameTests; failures all return non-zero exit codes |
| P0-01 Decision lease | Completed | lease/session tests cover stale callbacks, duplicate callbacks, continuation, external invalidation, and a new runtime with the same UUID |
| P0-02 Intent control | Completed | `IntentController`, Mission paused gate, Task origin, nestable `ExecutionStack`; control suite 6/6 |
| P0-03 Authorization | Completed | owner/OP policy uniformly covers commands, chat, C2S, subscriptions, Tools, and Jobs; denials are logged to the SECURITY audit |
| P0-04 Goal postcondition | Completed | 9 Goal types use typed predicates; final states are unified as `COMPLETED/PARTIAL/FAILED/CANCELLED` |
| P0-05 Lifecycle/persistence | Completed | schema v1, atomic merge-write, legacy migration, Mission/checkpoint/Job lease recovery; verified PASS across two JVMs |
| P0-06 Operating profiles | Completed | strict by default on fresh installs, legacy operator compatibility, 4 capability gates, UI/log auditing; dual-profile 7/7 |
| P0-07b CI/nightly | Completed | testmod isolated from the production jar; PR and nightly dual profile/seed, manual billed-LLM workflow configured |
| P0-08 Evidence | Completed | immutable bundle, dynamic port/runDir, validation/redaction, batch, explicit pin, and baseline index implemented |

## Execution Order

```text
P0-07a Test bootstrap
   ├── P0-01 Decision epoch
   │      └── P0-02 Intent control
   │             ├── P0-04 Goal postcondition
   │             └── P0-05 Lifecycle/persistence
   ├── P0-03 Authorization
   └── P0-06 Operating mode contract

P0-07b CI/nightly wrap-up
   └── P0-08 Evidence pipeline
```

Every behavioral work order submits a reproducible failing test first, then the implementation; P0-07 is not tests bolted on at the end — it runs through the entire phase.

## P0-01: Isolate Stale LLM Responses

Problem evidence:

- `BrainCoordinator.handleMessage` allows new requests in while a Goal is active;
- `AsyncDecisionExecutor` callbacks have no request id;
- A stale request that arrives late can still enter `ActionDispatcher`.

Implementation scope:

- Add a monotonically increasing `decisionEpoch` per Bot;
- Every new message, reset, external panel/command cancel, despawn, and server stop invalidates the old epoch;
- After the HTTP callback returns to the main thread, first validate bot liveness, Runtime identity, and epoch;
- A stale response only logs `stale_decision_dropped`; it must not write to history or dispatch a Tool.

Within the same batch of LLM Tools, `stop/abort_task` now goes through a typed `ControlEffect` that preserves the current APPLYING lease; once the whole Tool batch finishes, whether to terminate or continue is decided based on whether a replacement exists — `stop + new Goal` is never cut off midway.

Acceptance:

- Construct a scenario where request A is delayed and request B returns first; A's Tool must never execute;
- A response that returns after reset/despawn does not affect the new Bot;
- Normal single-request path behavior is unchanged.

Risk: Cannot rely solely on `Future.cancel(true)`; network calls may not respond to interruption, so epoch validation must be the final line of defense.

## P0-02: Unify Pause, Cancel, and Replace Semantics

Current implementation: `cancel_current/cancel_all/replace/pause/resume` all go through the same transactional entry point; user pause and safety preemption are saved in separate layers.

Problem evidence:

- `stop` only stops the `ActionPack`;
- `abort_task` and the panel's `abort` only abort the current Task;
- GoalExecutor may treat the abort as a failure and re-plan.

First, pin down the contract:

| Operation | Current Mission | Queue | paused Task | LLM epoch |
|---|---|---|---|---|
| `pause` | Kept | Kept | Kept | Invalidated |
| `resume` | Continues | Kept | Restored | New epoch |
| `cancel_current` | Cleared | Kept | Cleared | Invalidated |
| `cancel_all` | Cleared | Cleared | Cleared | Invalidated |
| `replace` | Cleared and replaced | Explicit appended items kept by default | Cleared | Invalidated and a new one created |

Note: The epoch descriptions in the table apply to external controls such as the player panel/commands. For the LLM's same-batch `stop + replacement`, the current APPLYING lease is preserved to guarantee batch atomicity; once the batch ends, if the replacement has started, normal continuation proceeds, otherwise the decision ends.

P0-02a implemented:

- A single `IntentController` atomically handles Brain, Goal/queue, BotMemory, claimed Job, active/paused Task, ActionPack, failure cache, and UI state;
- `stop`, `abort_task`, `cancel_all`, direct panel dispatch/abort/reset, direct command dispatch/abort/reset, and despawn/server-stop all now go through the unified entry point;
- Cancellation results use `CANCELLED`; internal safety circuit-breaks still use `FAILED`;
- The queue is only promoted on the next server tick, avoiding repeated same-tick cancellations from swallowing consecutive queued goals;
- `IntentController.replace()` is now wired to direct panel dispatch, `/minecraftai task`, and `/minecraftai memory goto`; the LLM production path achieves an equivalent replacement via a same-batch `stop + Goal/Action`;
- `runtime_control_suite` verifies no revival within 200 ticks, idempotent repeated cancellation, queue promotion, Goal/action replacement, and rollback on replacement start failure.

P0-02b implemented:

- Introduce a Mission-level paused gate, Task origin, and a nestable ExecutionStack;
- The safety layer allows life-saving work but must not automatically resume a Mission the user explicitly paused;
- Panel, command, and chat entry points share unified `pause/resume` semantics.

`ExecutionStack` restores nested safety tasks in LIFO order; if the Mission is still user-paused after a safety task ends, it is not mistakenly resumed. Every Task-assignment entry point carries a `TaskOrigin`, and persistence only saves the pause gate when an active/queued Mission exists.

Acceptance:

- “Stop mining, build a house instead” does not finish the old mine first, nor does it queue the house-building in the wrong position;
- Within 200 ticks after `cancel_all`, no Goal is revived and no stale Tool callback fires;
- After cancel, the panel shows idle/cancelled, with no leftover FAILED.

## P0-03: Unify Bot Authorization Policy

Problem evidence:

- Commands and some panel operations require OP;
- item move, teleport, and the `@Bot` chat entry point lack consistent owner/OP validation;
- Passing a botName can resolve to any Bot.

Implementation scope:

- Create a new `BotAuthorizationPolicy`;
- Define `VIEW/COMMAND/INVENTORY/TELEPORT/ADMIN` permissions;
- By default the owner can operate their own Bot, OP can manage all Bots, and other players are denied;
- All commands, chat, C2S payloads, subscriptions, and bot-to-bot messages call it uniformly;
- Denial events log actor, bot, and operation, without logging sensitive message bodies.

Acceptance:

- Non-owners cannot subscribe to, command, take items from, place items into, or teleport any Bot;
- The owner and OP permission matrices all pass;
- Behavior is consistent whether botName is empty or an explicit name is given.

Risk: Need to decide up front whether shared Bots are allowed; if so, an explicit ACL should be used instead of continuing to rely on names.

## P0-04: Goal Final Postconditions

Problem evidence:

- Most Goals report completion as soon as their steps are exhausted;
- Failures in steps like `Stockpile` can be skipped by best-effort logic;
- Partial block placement in `PlaceStationsTask` and Build can be treated as complete.

Implementation scope:

- Define a typed `GoalPredicate` for each Goal type;
- Re-verify against WorldSnapshot/Inventory/Structure before declaring completion;
- Results are split into `COMPLETED/PARTIAL/FAILED/CANCELLED`;
- best-effort only affects whether to continue, and must not change the final facts;
- Build records expected/placed/skipped/mismatched, and performs a structural rescan.

Acceptance:

- Insufficient inventory, items not stored in the chest, missing workstation parts, and a house missing key blocks must never return COMPLETED;
- If the goal is already satisfied, it can complete with zero steps;
- The UI, logs, and LLM feedback all use the same result.

## P0-05: Runtime Cleanup and Mission Persistence

Problem evidence:

- `BotRecord` does not save the active Goal, queue, or progress;
- GoalExecutor's per-bot Map is not fully cleaned up at every lifecycle entry point;
- A persisted Job's `CLAIMED` state may have no owner to continue processing it after a restart.

Implementation scope:

- Persist the declarative `MissionSpec`, queue, and checkpoint metadata, without serializing concrete Task objects;
- Re-plan after restart, and use postconditions to skip already-completed steps;
- Add `schemaVersion` and migration;
- server stop, despawn, death, and reset all go through the unified Runtime lifecycle;
- On startup, reopen `CLAIMED` Jobs that have no valid lease, or explicitly fail them;
- Persistence uses atomic writes, with background batched flushing.

Acceptance:

- Mining, house-building, and queued goals continue after restart, without re-consuming already-produced output;
- Switching worlds within the same JVM does not reuse the old Runtime;
- Old-format saves can be migrated, or produce a clear, recoverable error.

Completion result: The unified `runtime.json` uses `schemaVersion=1`, with a background 750ms merge-write and a synchronous flush on server stop; the temp file is unique and is atomically replaced after fsync. A corrupted or future schema enters read-only protection without overwriting the original file. On restart, planning resumes from `MissionSpec` and applies checkpoint/postconditions first; `CLAIMED` Jobs from an old session are automatically reopened. `scripts/persistence_restart_test.sh` has launched two JVMs back-to-back against the same world, precisely comparing non-default checkpoint maps, the active Mission, the queue, and pause state; it verifies stale-lease reopening and confirms the original Mission reaches `COMPLETED 4/4` after resuming.

## P0-06: Operating Mode and Fairness Contract

Decided: fresh installs default to `strict_survival`, with `operator` requiring explicit opt-in; when an old config is missing a profile, it currently falls back to `operator` for compatibility and emits a migration warning.

Implementation scope:

- Mark capabilities such as hidden block scan, emergency teleport, and forced pickup as capability flags;
- Each verification run records the actual mode;
- Under `strict_survival`, only allowed perception and actions are used;
- Under `operator`, existing enhanced capabilities are retained, but are clearly surfaced in the UI and docs;
- README, Wiki, and configuration docs match actual behavior.

Acceptance:

- The two modes have independent test matrices;
- strict-mode code paths cannot invoke privileged capabilities;
- operator-mode enhanced behavior is auditable and can be disabled.

Completion result: The config, the `MINECRAFTAI_PROFILE` environment variable, the server-side snapshot, and the control panel all use the same effective policy. Resource/entity discovery first goes through `ObservableWorldQuery`; under strict, hidden scanning, emergency teleport, forced pickup, and manual teleport are disabled, death returns the bot to the world spawn point, and remote placement and container/furnace mutation are constrained by reach/visibility. Operator's four toggles can each be disabled independently, and every allow/deny decision produces a throttled, structured decision log entry.

## P0-07: Fast Test Pyramid and CI

Implementation scope:

- Borrow the pure-policy test pattern from the old tests on `origin/alpha`, without restoring the old implementation;
- Prioritize coverage for Decision lease, cancellation state transitions, the authorization matrix, GoalPredicate/GoalResult, profile parsing, persistence migration, and Job scope;
- PR CI: JUnit, compile, remap jar, minimal dedicated-server smoke test;
- Nightly: GameTest/Testmod, multiple seeds, real-LLM story runs executed separately;
- Migrate the full verify harness out of the production jar into a test source set, keeping only the necessary self-diagnostics in production.

Acceptance:

- `./gradlew test` is no longer `NO-SOURCE`;
- Every P0 bug has at least one automated test that goes from red to green;
- CI returns a non-zero status on failure and saves diagnostic artifacts.

Current: Both the production jar and the sources jar are content-checked to confirm they contain no GameTest classes and no `/minecraftai test` or `/minecraftai verify`; the command-driven harness lives in `src/gametest`. PR CI runs JUnit, GameTest, build, a two-JVM restart, and strict evidence; nightly runs strict/operator × multiple seeds; real-LLM story runs can only be executed after manually confirming the billing. All workflows upload diagnostics and evidence on failure.

## P0-08: Auditable Capability Report

Implementation scope:

- Each run writes an immutable directory and manifest;
- Record `commit_sha/build_version/timestamp/runtime/config_hash/requested_seed/actual_seed/mode`;
- Unify the harness lock, dynamic temp directory, dynamic port, and cleanup trap;
- A capability baseline must explicitly pin a run; automatically picking the “best run” is not allowed;
- README numbers are auto-generated from, or reference, a committed baseline.

Acceptance:

- A fresh clone can reproduce the baseline;
- The report is traceable to a unique code state and configuration;
- A report that is missing, corrupted, or has incomplete metadata is marked `UNVERIFIED` and must not count toward the release gate.

Completion result: `scripts/evidence_run.sh`, `evidence_batch.sh`, `evidence_validate.sh`, and `pin_baseline.sh` share locking, dynamic ports, a unique runDir, process-tree cleanup, and atomic publishing. Each bundle binds the start/end revision/worktree, the actual seed, the actual JVM, profile/capabilities, redacted config, logs, and multi-level checksums. A clean worktree pins the source with `git archive`; a dirty worktree or fixture automatically downgrades to `UNVERIFIED`. `reports/baselines/index.tsv` is the sole modern baseline selector — the capability matrix never scans a directory to pick the best result.

## P0 Definition of Done

- Every work order above has an independent automated test; splitting into commits is left for the user to explicitly authorize with `cp` — nothing has been staged or committed without authorization so far;
- No unauthorized P0/P1 control paths remain;
- The stale-response, cancel, restart, and postcondition suites are all green;
- The capability matrix can be auto-generated from a pinned manifest;
- The installation and project documentation no longer contradict actual behavior.
