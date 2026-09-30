# Minecraft-AI Product & Engineering Roadmap

Status: Active  
Baseline date: 2026-07-10  
Applicable branch: `main`

This file is the single entry point for the current project roadmap. The local `PLAN*.md`, `WORKORDERS*.md`, and `reports/*roadmap*.md` files in the repo are kept as historical development records and no longer represent current priorities.

## 1. North Star

The goal is not for Bob to demonstrate “doing a lot of actions,” but for it to become a trustworthy Minecraft collaboration partner:

The current single highest product priority is **Mining First**: first turn “obtaining 64 diamonds from zero, obtaining 32 obsidian blocks from zero” into a reproducible, pausable/resumable, auditable strict-survival capability, then expand to other advanced capabilities. A small-scale demo, a controlled fixture, or a single-seed PASS does not count as complete.

> “Bob, come with me, build a small house here using nearby materials; go home first when it gets dark, and once finished put the leftover materials into this chest.”

Fulfilling this sentence means Bob must be able to:

- Understand the owner, location, area, target container, and sequencing;
- Produce an executable plan and continuously report real progress;
- Recover safely from danger, getting stuck, and insufficient resources;
- Correctly handle pausing, resuming, canceling, replacing, and appending tasks;
- Resume an unfinished Mission after a restart;
- Only report completion after the final state has been verified.

## 2. Product Positioning

Minecraft-AI keeps its existing core approach:

```text
LLM interprets intent
    → Mission / Goal describes the target state
    → Deterministic Planner expands dependencies
    → Task state machine executes
    → Action / Pathfinding operates Minecraft
```

The LLM is not allowed into the per-tick execution loop, nor is continuing to add more Tools used as the primary progress metric.

Two operating strategies must be clearly distinguished:

| Mode | Positioning | Hidden Resource Scan | Emergency Teleport | Applicable Scenario |
|---|---|---:|---:|---|
| `strict_survival` | Fair survival partner | Forbidden | Forbidden | Survival servers, demonstrating real gameplay ability |
| `operator` | Server-administration assistant | Configurable | Configurable | Private servers, debugging, automated ops |

Implemented: new installs default to `strict_survival`; `operator` must be explicitly enabled. When an old config is missing the mode field, it starts in `operator` mode for backward compatibility and emits a one-time migration warning; invalid config and invalid environment variables fail closed to strict. The four enhanced capabilities are each independent toggles, and the actual profile/effective capabilities are shown simultaneously in the UI, the snapshot, and the structured logs.

`strict_survival` does not enable hidden resource scanning, emergency teleportation, or manual teleportation; resource targets pass through a visibility boundary before being read. Navigation collision pre-checks, the first spawn/save restore, stepping down next to a fake-player, the bot's own fishing hook, and an explicit owner-follow position are documented runtime adapters and must not be reused by resource search.

## 3. Current Baseline

- Minecraft `1.21.11`, Fabric Loader `0.19.5`, Java `21`.
- 9 Goal categories, 63 Tool registration points, 34 concrete Task state machines.
- The testmod's `/minecraftai verify all` includes 100 deterministic scenarios; there are also 5 opt-in long-run/diagnostic scenarios and 4 real-LLM scenarios; the production jar does not include test/verify commands.
- `clean test` passes, with 86 JUnit classes and 415 tests currently; `runGameTest` has 588 scenarios in total, of which 42 fail due to pre-existing concurrency/timing issues introduced by the 1.21.5 GameTest framework rewrite (tracked separately, not a regression from recent feature changes; the rest pass). `capability_profile + runtime_control_suite` is 7/7 under both strict and operator; the two-JVM restart-resume precisely restores a non-default checkpoint and ends with the original Mission at `COMPLETED 4/4`.
- PR CI, the nightly dual profile/seed matrix, and the manual billed-LLM workflow are established; the evidence bundle binds revision/config/actual seed/runtime/profile and is sealed immutably.
- Existing multi-seed reports are usable for diagnostics but lack a commit SHA, config summary, and actual-seed readback, so they cannot serve as release proof for HEAD.

See the [Capability Matrix](docs/CAPABILITY_MATRIX.md) for the current capability snapshot and evidence boundaries.

## 4. Target Runtime

Do not rewrite existing Tasks; first wrap the existing modules with a unified Runtime, then gradually migrate state ownership:

```text
BotRuntime
├── IntentInbox
├── DecisionSession(epoch)
├── MissionQueue(persisted)
├── ExecutionStack
├── SafetyArbiter
├── WorldModel(dimension-aware)
├── Memory
└── EventLog(correlation id)
```

Every externally-promisable capability ultimately converges into a `Capability`:

```text
Capability
├── Tool schema
├── Preconditions
├── Planner expansion
├── Task factory
├── Postcondition
└── Verification scenarios
```

## 5. Phased Plan

### P0: Stable Runtime (Implementation Complete)

Goal: make Bob safely controllable, genuinely cancelable, resumable, and free of false completions.

- Isolation of stale LLM responses;
- Atomic pause, resume, cancel, and replace;
- Unified owner/OP permissions;
- Final postconditions for Goals;
- Mission and queue persistence, unified cleanup;
- Operating-mode contract;
- Fast unit tests, CI, and auditable reports.

Implementation status: the scope above is complete and has passed automated verification. The local working tree is not yet committed, so this round's real evidence is marked `UNVERIFIED` per the rules; this is not a test failure, but a safeguard against a dirty source tree being mistakenly pinned as the release baseline. Only a clean commit/CI can produce a pinnable `VERIFIED` bundle.

See [P0 Runtime Hardening](docs/P0_RUNTIME_HARDENING.md) for the detailed breakdown. No new high-level Goals or large-scale skills will be added until P0 is fully green.

### M1: Mining First (In Progress, Highest Priority)

The goal is not “being able to mine one,” but the following two final capabilities:

1. `diamond_stack_64`: on natural terrain, with an empty inventory and zero target-item handouts, end up holding at least 64 diamonds;
2. `obsidian_half_stack_32`: on natural terrain, with an empty inventory, autonomously obtain a diamond pickaxe and a water bucket, and end up holding at least 32 obsidian blocks following vanilla fluid rules.

The delivery order is fixed as:

1. `controlled`: second-level verification of the 63/64 and 31/32 quantity boundaries, persistence, and typed postconditions; makes no claim about in-game gameplay ability;
2. `prepared`: only pre-grants non-target equipment, and verifies long-quota execution, tool replacement, pickup, and recovery in a deterministic resource field;
3. `from_zero`: the end-user-facing bar — a `>=90%` success rate across 20 public seeds, with zero deaths;
4. Verify pause/cancel/restart-resume at the diamond `1/32/63` and obsidian `1/16/31` progress checkpoints, at `100%`.

Ordinary PRs only run the second-level controlled contract; prepared/from-zero must be run through an explicit local or nightly entry point. Until the multi-seed bar is met, both capabilities remain `MISSING` in the capability matrix, and a controlled/prepared PASS must not be used to pass off completion.

See the [Mining First Capability Contract](docs/MINING_ACCEPTANCE.md) for the full contract and the seed, timeout, and evidence rules. Until M1 meets its bar, v0.1's other golden chains receive only regression maintenance and do not take priority over active development.

### v0.1: Trustworthy Core Assistant

Only polish four golden chains:

1. Follow the owner, stand by, and guard a designated location;
2. Obtain a stable food supply starting from an empty inventory;
3. Obtain iron ingots/iron equipment from zero and deliver them into a designated container;
4. Build a small house in an area marked by the owner, pause at night, resume during the day, and pass acceptance.

Release bar:

- Each real chain uses at least 20 fixed public seeds, with a success rate `>= 90%`;
- `cancel/replace/restart-resume` deterministic scenarios pass at `100%`;
- No infinite loops, no stale tool calls, and no `PARTIAL` passed off as `COMPLETED`;
- TPS `>= 19` when running 4 Bots on the agreed reference machine;
- No unauthorized player-control, item-taking, or teleport paths.

### v0.2: Collaborative Experience

- Owner coordinates, line-of-sight target, selected area, and target container enter the context;
- Support collaborative phrasing such as “here,” “this chest,” “come with me,” and “give me the leftover materials”;
- Work area, no-break area, material budget, and construction preview;
- Panel displays the Mission queue, real progress, failure reasons, resource budget, and token cost;
- Chinese/English paraphrase intent-routing regression reaches `>= 95%`.

### v0.3: Advanced Survival & Production

- Building on the certified Mining First foundation, expand to enchanting, Fortune, hundreds-scale quantities of other ores, and automatic storage;
- Building repair, continuation, and multi-blueprint composition;
- Sustainable farms, base resupply, and long-term operation;
- Nether and cross-dimensional WorldModel;
- Enable multi-Bot division of labor, leasing, and resource reservation only after the single-Bot Runtime is stable.

## 6. Engineering Gates

Every merge must satisfy the corresponding tier:

| Tier | PR Gate | Nightly Gate |
|---|---|---|
| Pure logic | JUnit, static invariant checks, compile | the same set of JUnit/static checks/production build |
| Runtime contract | Cancel, permissions, lifecycle, two-JVM restart probe, strict evidence | 7 profile/runtime contracts × 2 profiles × 2 seeds |
| Mining First contract | 64/32 quantity boundaries, MissionSpec round-trip; no long-run execution | prepared/from-zero are run through an explicit strict-only long-run entry point and evidence is sealed |
| Game behavior | 3 Fabric GameTests | Currently also 3 GameTests; expands to 98 sharded scenarios with multiple seeds in v0.1 |
| LLM routing | No API key injected | A separate manual workflow runs 4 real-LLM stories after explicit billing confirmation |

The table above describes gates already in place; the 98-scenario multi-seed sharding and the nightly restart/resume matrix are follow-on items for v0.1, and are not to be treated as an accomplished fact of current P0 completion.

Every capability report must record:

- `commit_sha`, `build_version`, timestamp;
- Java/Minecraft/Fabric versions;
- Config hash, requested seed, actual seed;
- Scenario, duration, result, failure stage;
- Whether privileged perception/teleport is allowed.

## 7. Non-Goals

Explicitly out of scope before v0.1:

- End-to-end LLM or RL replacing deterministic Tasks;
- Continuing to add Tools just to show a bigger count;
- Multi-Bot orchestration without defined lease/recovery;
- Supporting multiple major Minecraft versions simultaneously;
- Major UI overhauls without acceptance criteria.

## 8. Next Checkpoint

P0 is complete; the next step moves into Mining First reliability convergence:

1. Clean CI first generates strict `VERIFIED` evidence for the `mining_contract_suite`, understanding that it represents only the quantity contract;
2. Run `diamond_stack_64_prepared` and then `diamond_stack_64_from_zero` in sequence, converging on `>=90%` across 20 seeds by iterating on the failure stage;
3. Have the obsidian chain use real water placement/vanilla fluid reactions, then run prepared/from-zero in sequence; a pseudo-capability that directly writes the target block is forbidden;
4. Only after both final scenarios meet the bar, explicitly update `reports/baselines/index.tsv` using `pin_baseline.sh`;
5. Use strict as the release bar, with operator continuing regression only as a separately auditable server-automation mode.
