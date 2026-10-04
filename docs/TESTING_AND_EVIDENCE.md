# Minecraft-AI Testing and Evidence

Minecraft-AI treats “code passes tests,” “the scenario passes locally,” and “usable as a release basis” as separate concerns. A scenario result of `PASS` does not mean the evidence state is necessarily `VERIFIED`.

## Test Layers

| Layer | Entry point | Current scale | Purpose |
|---|---|---:|---|
| JUnit | `./gradlew test` | Runner-counted; see `scripts/dev/unittest.sh` | Verifies pure Java strategy, permissions, Goal predicate/result, serialization, and atomic storage boundaries. |
| Fabric GameTest | `./gradlew runGameTest` | Runner-counted; see `scripts/dev/gametest.sh` | Verifies deterministic smoke cases in a real Minecraft world context. |
| Interactive harness | `./gradlew runHarnessServer` | test-only commands | Provides the `/minecraftai test`, `/minecraftai verify`, and restart probe commands. |
| Single-run evidence | `scripts/evidence_run.sh` | One scenario/seed/profile | Starts an isolated server and seals a tamper-evident run bundle. |
| Batch evidence | `scripts/evidence_batch.sh` | Explicit seed/run matrix | Aggregates multiple independent bundles; does not automatically select a baseline. |
| Two-JVM restart | `scripts/persistence_restart_test.sh` | Two consecutive server processes | Verifies non-default checkpoints, exact Mission/queue/pause recovery, stale Job lease reopening, and reaching `COMPLETED 4/4` after resume. |

The source suites grow continuously, so this guide does not hard-code a test inventory. Treat the counted `UNIT ... PASS tests=...` and GameTest `PASS ran=... tests=...` lines emitted by `scripts/dev/unittest.sh` and `scripts/dev/gametest.sh` as authoritative for the checked revision, and record the final root and wrapper counts in the verification handoff. In the current local diagnostics, the strict/operator capability + runtime-control suite is `7/7 PASS` for both; the two-JVM persistence probe recovers the checkpoint map exactly, and reaches the original Mission's `COMPLETED 4/4` after resume. These facts do not substitute for the clean-commit evidence gate.

## Developer Test Runners (`scripts/dev`)

Use the Bash runners for repeatable local verification. Run them from Linux, WSL, or Git Bash; on this Windows machine the maintained local runners under `C:\mcw\_tools` are an equivalent transport when they are available. Do not run two GameTest servers in one worktree, and keep `GT_SLOTS=1` unless the machine has been deliberately provisioned for more memory.

The evidence tools require a real Python 3.8+ interpreter for their sealed-file and JSON checks. The resolver deliberately does not invoke a direct per-user Windows App Execution Alias because it can open a Store prompt instead of an interpreter; use `py -3` or set `HARNESS_PYTHON_BIN` to a directly executable interpreter path (for example, `/c/tools/Python/python.exe`) when Git Bash cannot resolve `python3`, `python`, or `py -3`. This applies to `evidence_run.sh`, `evidence_batch.sh`, `evidence_validate.sh`, `pin_baseline.sh`, `mining_evidence_shard.sh`, `mining_evidence_aggregate.sh`, and `persistence_restart_test.sh`.

```bash
# Root or wrapper unit tests. The runner serializes unit-test Gradle processes,
# retries Maven Central 429 responses only, and prints a counted UNIT PASS/FAIL line.
bash scripts/dev/unittest.sh "$PWD"
bash scripts/dev/unittest.sh --wrapper "$PWD"

# One real-server GameTest filter per server start. Use an exact method name or a glob;
# most class globs need the leading '*'. Results and per-filter logs are retained.
bash scripts/dev/gametest.sh "$PWD" "$PWD/build/gametest-root.txt" '*follow_swim_game_tests_*'
bash scripts/dev/gametest.sh --wrapper "$PWD" "$PWD/build/gametest-wrapper.txt" '*wrapper_game_tests_*'

# A whole-suite run is exclusive: ALL acquires every GameTest slot and must not share the machine
# with another GameTest server.
bash scripts/dev/gametest.sh "$PWD" "$PWD/build/gametest-root-all.txt" ALL
bash scripts/dev/gametest.sh --wrapper "$PWD" "$PWD/build/gametest-wrapper-all.txt" ALL
```

`gametest.sh` adds the correct mod-specific GameTest namespace, writes one `PASS`, `FAIL`, `NOMATCH`, `ERROR`, `HUNG`, or `SKIPPED` line per requested filter, and stores full Gradle/server output beside the chosen result file in `gt_logs/`. `GT_HEAP` controls the server heap (default `2560m`), `GT_TIMEOUT` controls a filtered-run watchdog, and `GT_DAEMON=1` opts into a resident Gradle daemon; the default is `--no-daemon` to reduce resident memory. A `NOMATCH`, `ERROR`, `HUNG`, or `SKIPPED` result is not test evidence.

The wrapper suite needs the upstream PvP BOT and HeroBot runtime jars. Fetch them outside the repository before running it; the script verifies the expected checksums and never adds the jars to Git:

```bash
bash scripts/dev/fetch-upstream-mods.sh
# Or choose a non-repository cache explicitly:
UPSTREAM_MODS_DIR="$HOME/.cache/upstream-mods" bash scripts/dev/fetch-upstream-mods.sh "$UPSTREAM_MODS_DIR"
```

Pass the same `UPSTREAM_MODS_DIR` to `gametest.sh --wrapper` when using a non-default cache. The runner treats missing jars as `SKIPPED`, which is a setup failure to resolve before claiming wrapper coverage.

## Production Boundary

`MinecraftAiTestSubcommand`, `MinecraftAiVerifySubcommand`, the GameTest classes, and the restart harness all live under `src/gametest`. Production `src/main` does not register `/minecraftai test` or `/minecraftai verify`, and the production jar should not contain these classes either.

After building, you can run the static gate with artifact checks:

```bash
./gradlew clean build
CI_STATIC_CHECK_ARTIFACTS=1 bash scripts/ci_static_check.sh
```

This check scans `build/libs/*.jar` and fails if it finds GameTest or verification command leakage.

## Pack-Compat Check (GameTests With the Installed Mods)

Our mixins (`minecraftai.mixins.json`, `injectors.defaultRequire = 1`) and features run in a pack next to mods that transform the same
vanilla classes (Lithium, FerriteCore, ModernFix) or change how blocks break (VeinMiner, Physics Mod). The plain GameTest run has none of
them, so before a deploy to the Minecraft profile run the same GameTests with the profile's mods loaded. Fabric Loader loads jars from
`<runDir>/mods` in a development run and remaps intermediary jars itself, so nothing else is needed.

```bash
# GT_EXTRA_MODS = the profile's mods folder (read only: nothing is ever written to it). Use forward slashes.
export GT_EXTRA_MODS="C:/Users/PC/AppData/Roaming/.minecraft/profiles/Minecraft-AI-1.21.11/mods"
bash /c/mcw/_tools/gt_filter.sh <repo> <repo>/packrun/gt_results.txt \
  'mixin_target_class_load_game_tests_*' 'permissions_integration_game_tests_*' 'vein_miner_pack_game_tests_*' \
  'sleep_vote_game_tests_*' 'phantom_spawner_game_tests_*' 'follow_task_game_tests_*' \
  'gather_tool_policy_game_tests_*' 'bot_persistence_restore_game_tests_*'
```

Without the wrapper: `./gradlew runGameTest -PgametestExtraMods=<mods dir> [-PgametestExtraModsConfig=<config dir>] [-PgametestExtraModsSkip=id1,id2]`
(with `JAVA_TOOL_OPTIONS=-Dfabric-api.gametest.filter=minecraftai-gametest:<filter>`). The property is off by default, so normal runs are
unchanged. What `prepareGameTestRun` does with it, always logged as `gametestExtraMods: ...` lines in the Gradle output:

- Copies every `*.jar` of the mods folder into `build/run/gameTest/mods` after the run dir is recreated. Skipped, with the reason logged:
  our own mod (`minecraftai`, `minecraftai-gametest`: the profile holds an older deployed Minecraft-AI jar), `fabric-api` (the dev run
  has its own), client-only mods (`"environment": "client"`: Sodium, Iris and so on; a dedicated server never loads them, and skipping
  them keeps the loader's runtime remap short), and the ids in `-PgametestExtraModsSkip` (the documented place for a mod that cannot
  start in the headless server; currently empty).
- A pack mod's own `fabric-gametest` entrypoint (Inventory Sorter ships one whose test class is not in the release jar and crashes the
  GameTest server) is stripped from the COPY of that jar; nothing else in the jar changes.
- With `GT_EXTRA_MODS` set, `gt_filter.sh` also passes the profile's `config` folder (next to `mods`) as `-PgametestExtraModsConfig`. The
  mods' behaviour depends on it: Physics Mod collapses connected blocks by default, and only its `collapse: false` in
  `physics_server_config.json` makes bots' block breaking behave as in the real pack (without the config, 6 gather tests, the follow-dig
  test and the vein test fail because everything a bot breaks takes its neighbours with it). Our `minecraftai*`/`aibot*` files (they hold
  the LLM key), `*.bak` files and VeinMiner's folder are never copied; only file names are logged.
- If VeinMiner is among the mods, the run dir gets `config/Veinminer/settings.json` with `permissionRestricted: true` (the setting the
  deploy step sets in the profile) and `config/Veinminer/update` as a plain file, as in the profile, so its
  auto-updater cannot write a download. Note that VeinMiner still asks Modrinth for the newest version at start-up (it logs
  "veinminer is up to date").
- `gt_filter.sh` retries (at most twice) a run that dies with Fabric Loader's runtime-remap race (`Failed to remap mods!`,
  `ClosedFileSystemException`) before the server exists; that is a loader race, not a test result.

What to look for:

1. `mixin_target_class_load_game_tests_*` must PASS: every mixin of ours still applies with Lithium/FerriteCore/ModernFix transforming
   the same classes (a conflict fails the class load hard, `defaultRequire = 1`). The log line "Method overwrite conflict for getTemperature
   in lithium... previously written by ...modernfix" is between those two mods, not ours.
2. `permissions_integration_game_tests_*` proves the API answers bots "no". `vein_miner_pack_game_tests_*` proves VeinMiner really respects
   it: with the pack's VeinMiner and `permissionRestricted: true`, a human stand-in breaking one ore of a five-ore vein with an iron pickaxe
   vein-mines all five (positive control) while a bot breaking the same vein through its real `MiningController` removes only the ore it
   broke. The log line `VEINMINER_PACK result=checked human_ores_left=0 bot_ores_left=4 of=5` is the evidence (a run without VeinMiner logs
   `result=skipped` and passes, so the normal suite is unaffected).
3. Every other class must be as green as without the pack. A failure that only appears with the pack is a pack interaction: find which mod
   (rerun with `GT_EXTRA_MODS_SKIP=<mod id>` to bisect) and fix OUR side; never change the pack.

Caveats: the check runs on a Terralith/Tectonic/Streams Reflowing world generator, but the fixtures build their own blocks, so terrain does
not matter. A run takes 1 to 3 minutes longer than a plain one because Loader remaps about 30 mods.

## GameTest isolation (every test runs alone)

The whole suite shares one test world and one JVM. The harness (`src/gametest/.../gametest`, GameTest runs only) makes every test find
the same world, whatever ran before it:

- **One test per batch** (`GameTestIsolation`, `GameTestBatchFactoryOneTestPerBatchMixin`). Vanilla runs up to 50 tests of the same
  environment at once, 13 blocks apart, while our scenes are far larger (a follow field, a pond, a 24-block corridor, a lake): scenes of
  a batch overlapped and a test failed because of its neighbour (water from a lake test flooded a follow test). Now the default
  environment is split into single-test batches like the tests that have an environment of their own; a full run takes about as
  long as before (about 6 minutes of server time for 1160 tests on a 4-core machine).
- **Cleanup between tests** (`GameTestSweeper`, `GameTestWorldRestorer`, `GameTestEntityGate`). When the next test starts, every block
  the last one changed is put back, its entities, bots and mock players are removed (and logged as a leak), the ambient is reset (clock
  at day 1000, clear weather, the suite's game rules) and the JVM-wide configs and test hooks it may have switched are put back (loudly).
  Entities loaded from chunks rather than added by code (generated animals, leftovers saved with a chunk) are discarded; natural spawning
  is off.
- **Light in step with ticks** (`GameTestLightSync`). The GameTest server runs ticks back to back, far faster than 20 per second, while
  the light engine works on its own thread; at the end of every tick that changed blocks the server waits (bounded) until their light is
  published, so the next tick sees it as a real server would.
- **Unique mock players** (`MockPlayers.mock`): every mock has its own name and UUID and is disconnected when its test ends
  (vanilla's `makeMockServerPlayerInLevel` names all of them `test-mock-player` and never removes them, and name lookups return the
  oldest).
- A test that needs night, rain or another game rule sets it for its premise; it does not have to restore it. Wall-clock things (thread
  pools, background planners) must be waited for in wall-clock time, not in ticks: a tick count is no clock on this server.
- `GameTestIsolationSelfTests` checks the batch split, the ambient reset and the light publication.

## Baritone GameTests: what to run how

- Every test runs alone (see above), so the Baritone classes can be run together or one class glob at a time.

## Single Isolated Evidence Run

Minimal command:

```bash
bash scripts/evidence_run.sh \
  --scenario capability_profile+runtime_control_suite \
  --seed 20260610 \
  --profile strict_survival
```

Common parameters:

- `--profile strict_survival|operator`: defaults to strict;
- `--operator-capabilities all|none|<csv>`: only takes effect for operator;
- `--seed <integer>`: the requested world seed;
- `--timeout` and `--startup-timeout`: scenario and startup timeouts;
- `--mode deterministic`: default, no LLM cost;
- `--mode llm_story --with-llm`: explicitly enables the LLM, requires `MINECRAFTAI_LLM_API_KEY` (the old name `DEEPSEEK_API_KEY` still works);
- `--assist off|sense|detour|poi|all`: Mining Assist mode; the default and evidence requirement is `off` — see “Mining Assist Mode Pinning” below;
- `--fixture-log <file>`: only tests the seal/parse structure; it can never become `VERIFIED`.

The script uses an independent run directory, a dynamic local port, a process lock, and a cleanup trap; it does not run a global `gradlew --stop` and does not delete the shared `run/` directory. Existing evidence directories are never overwritten.

## Bundle Structure

After a successful seal, the script prints `EVIDENCE_DIR=...`. A single run lives at `artifacts/evidence/<run-id>/` and contains:

```text
manifest.tsv
result.tsv
server.log
effective-config.redacted.json
checksums.sha256
LOCKED
```

- `manifest.tsv`: commit, working-tree start/end state, runtime, profile, effective capability, config hash, requested/actual seed, isolation directory, result, and evidence state;
- `result.tsv`: the scenario result and the `/minecraftai verify` summary;
- `effective-config.redacted.json`: a redacted snapshot of the actual test configuration;
- `checksums.sha256` and `LOCKED`: the integrity boundary established after sealing;
- `server.log`: the server log, after credential-pattern checks and necessary redaction have been applied.

Once a bundle is published it is sealed and tamper-evident: validation checks its checksums and `LOCKED` marker. Advisory read-only modes are also applied where the filesystem supports them, but do not manually edit results or metadata inside the directory; re-run instead if a fix is needed.

## `PASS` vs. `VERIFIED`

`result=PASS` only means the scenario's assertions passed. To get `evidence_state=VERIFIED`, the following provenance conditions must also all be satisfied:

- the working tree is clean at both start and end;
- the start and end commits are identical and resolvable;
- execution runs from a `git archive` snapshot of that commit, not an unstable live worktree;
- the server starts successfully and exits normally;
- the requested seed matches the actual seed read back from the server;
- the isolated working directory and Java runtime are verifiable;
- revision/build/config metadata is complete;
- it is not fixture input;
- there are no secrets in the sealed log that need to be redacted.

If any condition is not met, the scenario can still show `PASS`, but the evidence becomes `UNVERIFIED`, with the reason listed in `verification_reason`. The local strict/operator `7/7` run currently in this repository is exactly this kind of correct downgrade: the working tree is dirty, so it cannot enter the release baseline.

## Validating a Bundle

Structure and checksum validation:

```bash
bash scripts/evidence_validate.sh artifacts/evidence/<run-id>
```

Release-gate validation:

```bash
bash scripts/evidence_validate.sh \
  --require-verified artifacts/evidence/<run-id>
```

The validator does not source metadata from within the bundle, and rejects path traversal, symlinks, staging directories, duplicate manifest keys, unknown schemas, checksum mismatches, and provenance contradictions. You can run its security self-test:

```bash
bash scripts/evidence_validate.sh --self-test
```

## Batch Runs

```bash
bash scripts/evidence_batch.sh \
  --scenario real_food \
  --seeds 12345,246810,632510390 \
  --runs 2 \
  --profile strict_survival
```

By default, a batch requires every child run to be `VERIFIED`. `--allow-unverified` is only suitable for local diagnostics and must not be used for the release gate. The batch outputs to `artifacts/evidence-batches/<batch-id>/`, which only references each child bundle and never automatically selects or pins a result on the user's behalf.

## Explicitly Pinning a Baseline

The sole selector for the new-style baseline is `reports/baselines/index.tsv`. A pin operation must explicitly supply a capability ID and a run that has already passed `--require-verified`:

```bash
bash scripts/pin_baseline.sh \
  food_from_zero \
  artifacts/evidence/<run-id>
```

The pin process also verifies:

- the capability ID is registered exactly once in `reports/capability_baseline_manifest.tsv`;
- the run scenario matches that capability's registered scenario;
- the profile is `strict_survival` and the mode is `deterministic`;
- by default, only `PASS` is accepted;
- the sealed bundle's hash matches `LOCKED`.

The script copies the bundle to `reports/baselines/<capability-id>/<run-id>/`, then atomically updates `reports/baselines/index.tsv`. Old runs are not deleted when re-pinned.

`reports/capability_baseline_manifest.tsv` still holds capability registration info and the legacy fallback. It is not an index that “automatically picks the best report”; when there is no new-style pin, the generated capability matrix shows the legacy report and stays `UNVERIFIED`.

Update the matrix:

```bash
bash scripts/capability_matrix.sh --output docs/CAPABILITY_MATRIX.md
bash scripts/capability_matrix.sh --check docs/CAPABILITY_MATRIX.md
```

## Mining Assist Mode Pinning

Mining Assist (see [MINING_ASSIST.md](MINING_ASSIST.md)) defaults to running in `sense` shadow mode during real missions, but all evidence runs must be captured with it turned off, so the evidence doesn't get mixed with the sensor's runtime overhead or logs:

- `scripts/evidence_run.sh` pins the mode to `off` for every scenario (including both `--with-llm` branches): it exports `MINECRAFTAI_MINING_ASSIST=off` and writes `"miningAssist": { "mode": "off" }` into both the runtime `config/minecraftai.json` and the sealed `effective-config.redacted.json`, so `config_hash` covers this mode; the corresponding manifest key is `mining_assist_mode`.
- Only an explicit `--assist <mode>` changes it. This is a local, non-certifying choice; the three `*_from_zero` Mining First certifying scenarios reject any non-`off` mode outright at runtime.
- `scripts/evidence_validate.sh` requires that the `mining_assist_mode` in the manifest matches the mode in the effective config, and rejects any certifying bundle that is not `off` (reason `certifying_bundle_has_mining_assist_mode`). Old bundles sealed before Mining Assist existed have neither this key nor this config section, and still validate as `off`; if only one of the two is present, it's judged inconsistent. `--self-test` covers these cases.
- `scripts/ci_static_check.sh` checks `evidence_run.sh`'s default value and both places where `MINECRAFTAI_MINING_ASSIST` is pinned, and rejects any workflow that sets the mode to anything other than `off` (environment variable, `--assist` argument, or `miningAssist` config section).
- The GameTest and `/minecraftai verify` harness call `MiningAssistRuntime.setHarnessDefaultOff(true)` in `MinecraftAiHarnessTestMod` (`true` meaning the harness defaults to off), after which the gate is closed for all bots and every hook does nothing, until some test explicitly enables an individual bot with `MiningAssistRuntime.forceEnable(uuid)`; an explicit environment variable or config-file mode still takes priority.
- P1 (opportunistic detour, see “The detour (P1)” in [MINING_ASSIST.md](MINING_ASSIST.md)) has already been implemented, but the harness still defaults to `off`; the `assist_mine_lane` verify scenario is the only channel that explicitly selects `detour` mode by name — every other scenario stays pinned to `off` just like the three scripts listed above, unaffected by P1 landing. `OreDigOpportunisticGameTests` (`src/gametest/java/io/github/zoyluo/minecraftai/mining/assist`) is P1's own GameTest coverage, running in this separate channel; it likewise uses `forceEnable` to explicitly turn mode on per bot, and does not change any of the pinning rules above.

## CI Constraints

- The PR CI and nightly deterministic workflow do not accept `MINECRAFTAI_LLM_API_KEY` / `DEEPSEEK_API_KEY`;
- nightly covers the `strict_survival` and `operator` profiles;
- LLM story evidence can only be triggered via the manual workflow, and requires explicit confirmation of billing;
- the workflow still uploads diagnostic artifacts on failure;
- release claims may only reference a validated, explicitly pinned `VERIFIED` bundle.
