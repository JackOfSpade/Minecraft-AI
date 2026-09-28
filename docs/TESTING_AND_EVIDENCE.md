# Minecraft-AI Testing and Evidence

Minecraft-AI treats “code passes tests,” “the scenario passes locally,” and “usable as a release basis” as separate concerns. A scenario result of `PASS` does not mean the evidence state is necessarily `VERIFIED`.

## Test Layers

| Layer | Entry point | Current scale | Purpose |
|---|---|---:|---|
| JUnit | `./gradlew test` | 19 test classes, 68 tests | Verifies pure Java strategy, permissions, Goal predicate/result, serialization, and atomic storage boundaries. |
| Fabric GameTest | `./gradlew runGameTest` | 3 tests | Verifies deterministic smoke cases in a real Minecraft world context. |
| Interactive harness | `./gradlew runHarnessServer` | test-only commands | Provides the `/minecraftai test`, `/minecraftai verify`, and restart probe commands. |
| Single-run evidence | `scripts/evidence_run.sh` | One scenario/seed/profile | Starts an isolated server and seals an immutable run bundle. |
| Batch evidence | `scripts/evidence_batch.sh` | Explicit seed/run matrix | Aggregates multiple independent bundles; does not automatically select a baseline. |
| Two-JVM restart | `scripts/persistence_restart_test.sh` | Two consecutive server processes | Verifies non-default checkpoints, exact Mission/queue/pause recovery, stale Job lease reopening, and reaching `COMPLETED 4/4` after resume. |

The current source test inventory is 19 JUnit classes, 68 tests, and 3 GameTests. In the current local diagnostics, the strict/operator capability + runtime-control suite is `7/7 PASS` for both; the two-JVM persistence probe recovers the checkpoint map exactly, and reaches the original Mission's `COMPLETED 4/4` after resume. These numbers describe verification of this working tree and do not substitute for the clean-commit evidence gate.

## Production Boundary

`MinecraftAiTestSubcommand`, `MinecraftAiVerifySubcommand`, the GameTest classes, and the restart harness all live under `src/gametest`. Production `src/main` does not register `/minecraftai test` or `/minecraftai verify`, and the production jar should not contain these classes either.

After building, you can run the static gate with artifact checks:

```bash
./gradlew clean build
CI_STATIC_CHECK_ARTIFACTS=1 bash scripts/ci_static_check.sh
```

This check scans `build/libs/*.jar` and fails if it finds GameTest or verification command leakage.

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

Once a bundle is published it is treated as immutable. Do not manually edit results or metadata inside the directory; re-run instead if a fix is needed.

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
- the immutable bundle's hash matches `LOCKED`.

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
