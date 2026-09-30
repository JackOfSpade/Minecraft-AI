# Mining First Capability Contract

Status: M0 skeleton established; neither the 64-diamond nor the 32-obsidian capability has been certified.
Final capability IDs: `diamond_stack_64`, `obsidian_half_stack_32`.

> **2026-08 user-committed scope expansion: a full stack of 64 obsidian (`obsidian_stack_64`).**
> The 32 contract remains unchanged (sealed); 64 is a superset of it, and the new scenario has been wired into the verify/evidence chain:
> `obsidian_stack_64_controlled` / `obsidian_stack_64_prepared` (48,000 ticks) /
> `obsidian_stack_64_from_zero` (316,800 ticks = the 32 version's 240,000 + incremental 32 blocks × 2,400
> per-block amortization; evidence target=`obsidian64`, wall-clock cap 25,200 s = ~19% margin at
> the 15 TPS minimum-supported rate). The audit threshold is parameterized via `MiningEvidenceAudit.begin(bot, OBSIDIAN, 64)`,
> and the physical evidence chain (real water placement ≥1, water-driven conversion/mining/vanilla MINED/physical pickup all ≥64) is isomorphic to the 32 version.
> The release gate (20-seed ≥90%, etc.) applies equally to 64; the certification batch will be started by the user at their discretion.

This document defines exactly what “can mine” means. The source code accepting `count=64/32`, a quick contract scenario PASS, or a single ideal-canvas scenario PASS cannot substitute for genuine long-run acceptance.

## 1. Three-Tier Scenarios

| Tier | Diamond Scenario | Obsidian Scenario | Purpose | Proves Final Capability |
|---|---|---|---|---|
| Controlled contract | `diamond_stack_64_controlled` | `obsidian_half_stack_32_controlled` | Second-level verification of quantity boundaries, typed postconditions, and `MissionSpec` round-trip | No |
| Prepared execution | `diamond_stack_64_prepared` | `obsidian_half_stack_32_prepared` | Pre-places non-target equipment and deterministic resources, isolating continuous gathering, pickup, tools, and planner/task wiring | No |
| From zero | `diamond_stack_64_from_zero` | `obsidian_half_stack_32_from_zero` | Natural terrain, empty inventory, full survival chain | Yes, and must satisfy the multi-seed gate |

`controlled` runs in PR CI and the regular `mining` regression suite; `prepared` and `from_zero` are explicit opt-in and are prohibited from entering `/minecraftai verify all`. The final capability manifest binds only to the `from_zero` scenario.

## 2. Final Postconditions

### `diamond_stack_64`

- Inventory contains `minecraft:diamond >= 64`;
- Starts with an empty inventory; no diamond ore is pre-placed, and no diamonds or target drops are granted;
- Autonomously completes wood gathering, crafting table, pickaxe, iron, smelting, resupply, descending, ore-finding, continuous gathering, and pickup;
- Survival mode throughout, zero deaths; hidden scan, forced pickup, and emergency/manual teleport are prohibited;
- A sealed PASS must come from an isolated, newly created from-zero world in which no target ore or target drops are pre-placed. The ledger only accepts `observed diamond ore -> exact break -> single native pickup credit` transactions on an exact world-ore cell; the audited break is then taken as the minimum against the vanilla `MINED` delta for `diamond_ore + deepslate_diamond_ore`, and the native pickup credit is cross-constrained against vanilla `PICKED_UP diamond`, with all three required to be `>=64`. The current from-zero chain does not use Fortune, so a single exact break contributes at most 1 native pickup credit no matter how large the inventory delta is; the final inventory count cannot substitute for these provenance sources;
- `PARTIAL`, timeout, a task still running, or a drop that never entered the inventory must not be counted as PASS.

### `obsidian_half_stack_32`

- Inventory contains `minecraft:obsidian >= 32`;
- Starts with an empty inventory; no obsidian is pre-placed or granted;
- Autonomously obtains a diamond pickaxe, bucket/water source, and locates lava that can be safely worked;
- Obsidian must form through actual water placement and Minecraft's fluid reaction. Directly calling `setBlockState(..., OBSIDIAN)`, forming obsidian without a water bucket, or fabricated drops all fail final acceptance;
- Survival mode throughout, zero deaths; privileged capability rules are the same as for diamond.
- A sealed PASS must simultaneously prove: real `water_bucket` use `>=1`; observed water-backed `lava -> obsidian` conversions `>=32`; conversion-backed breaks and vanilla `MINED obsidian` both `>=32`; and the corresponding physical pickup and vanilla `PICKED_UP obsidian` both `>=32`.

## 3. Release Gate

Fixed public seed set:

```text
3000,155361719,632510390,111,700,4040404,12345,54321,99999,246810,
105441651,1061665215,206232996,42414950,456718736,586434987,
633819475,715809951,222222,1234567
```

Each final capability must simultaneously satisfy:

- At least `18/20 PASS (>=90%)` across the 20 seeds above;
- The three sentinel seeds `3000, 20260610, 777` are each repeated 3 times, with deterministic results and no duplicate counting;
- pause, cancel, and restart-resume are each verified at diamond progress points `1/32/63` and obsidian progress points `1/16/31`, with control semantics at `100%`;
- On tool breakage, full inventory, unreachable drops, lava/water, hostile mobs, cross-chunk movement, and temporarily invisible resources, the run must either recover or fail explicitly within a hard time limit — it must never spin indefinitely;
- Prepared timeout caps: diamond `60000 ticks`, obsidian `24000 ticks`. The from-zero obsidian verifier is currently fixed at `240000 ticks`; from-zero diamond does not use a magic literal but is computed dynamically by the live nominal plan, with a theoretical floor of `411200 ticks`. Capacity recovery is billed per reachable delivered watermark of the normal mining batch; auxiliary steps are classified by their actual hard window. The current empty-inventory live plan declares `2120000 ticks`, and GameTest additionally requires that the subsequent nominal plan not exceed `2592000 ticks` — what 48 hours at 15 TPS can cover. The shell harness's default wall-clock caps differ by target: obsidian `18000 seconds`, diamond `172800 seconds`; `evidence_run.sh` must read the actual tick budget from the single `RUNNING timeout=N` log line, and must fail-closed immediately after startup if `verify_timeout_seconds * 15 < N`. Here, 15 TPS is the minimum supported rate for a long run; the tick budget and the wall-clock cap are two separate contracts, and either can only be tightened going forward based on public evidence, or adjusted through documentation review.

## 4. Evidence Eligibility

Every run usable for a capability determination must be generated by `scripts/evidence_run.sh` / `scripts/evidence_batch.sh` and satisfy:

- Clean worktree, with the start and end `commit_sha` identical;
- `profile=strict_survival`, `mode=deterministic`;
- `actual_seed_verified=yes`;
- `config_hash`, runtime version, start/end times, and the result are all sealed in an immutable bundle;
- `hiddenBlockScan=false, emergencyTeleport=false, manualTeleport=false`;
- The from-zero verifier checks the actual GameMode every tick from scenario start to terminal, and proves `death_delta=0` via the scenario baseline delta of vanilla `Stats.CUSTOM/DEATHS`; any non-Survival tick, any death delta, or any `CapabilityRuntime` decision of `allowed=true` must fail the run;
- The run manifest schema 2 seals provenance schema 2's `mining_provenance_*`, GameMode/privilege/death counts, the two physical-provenance chains, and `verify_timeout_seconds` / `scenario_timeout_ticks`. A non-fixture Mining First PASS must have a unique `RUNNING timeout=N` that matches the manifest verbatim, and the wall-clock cap must cover at least `ceil(N/15)` seconds; regardless of whether the verdict is PASS or FAIL, `evidence_validate.sh` requires a unique structured terminal event and cross-checks it field-by-field against the manifest. Mining First runs on the old provenance schema 1, missing the timeout contract, or with missing events or fields are all rejected for sealing/validation;
- If the verifier summary claims a full PASS but the physical provenance verdict is FAIL, the bundle can only be recorded as `ERROR`, retaining the original verifier pass counts/summary; it must not be disguised as `FAIL + passed==total + PASS summary`. An ordinary `FAIL` must satisfy `passed < total` and a nonzero exit code;
- Only the `from_zero` scenario may bind to a final capability ID; controlled/prepared evidence may only be used for diagnostics;
- A single PASS does not equal release certification. Until the 20-seed batch reaches the threshold, the manifest must remain `MISSING` and must not be written as a `VERIFIED` capability conclusion.
- `scripts/pin_baseline.sh`'s single-run pin hard-rejects both Mining First capability IDs; manually writing to `reports/baselines/index.tsv` will also fail capability matrix validation.
- `scripts/mining_release_gate.sh` only accepts two explicitly passed-in sealed batches; it does not scan a directory to pick the best result. It also requires that both batches come from the same commit, and that all runs use the same `config_hash` and Minecraft/Fabric/Java runtime. Full host-kernel information from different GitHub runners is still retained in each run's manifest, but is not treated as an equality condition across runners.
- GitHub sharding binds `target/role/seed/run_index` and a unique run `LOCKED` hash through a sealed shard descriptor. The aggregator derives the full set of shard IDs from a fixed contract; a missing, duplicate, or extra descriptor, a repeated reference to the same run, or mixed commit/config/build/runtime will all fail-closed.

## 5. Execution Entry Points

Quick contract:

```bash
bash scripts/evidence_run.sh \
  --scenario mining_contract_suite \
  --seed 20260610 \
  --profile strict_survival
```

Explicit long run:

```bash
MINECRAFTAI_MINING_SEEDS=3000,20260610,777 \
  bash scripts/mining_acceptance.sh prepared all

bash scripts/mining_acceptance.sh from_zero diamond
```

`from_zero` does not accept custom seeds/runs: it automatically runs the fixed 20-seed primary batch (1 run per seed) and the `3000,20260610,777` sentinel batch (3 runs per seed), then calls `scripts/mining_release_gate.sh` to produce an `18/20 + 9/9` `MINING_MULTI_SEED_GATE` verdict. This verdict only proves the multi-seed and zero-death portion of the postconditions; pause/cancel/restart-resume and boundary scenarios must still separately satisfy Section 3 of this document, and full capability cannot be certified from this alone. Use `prepared` when a small sample is needed to localize an issue — a diagnostic sample must not be represented as release acceptance.

`MINECRAFTAI_MINING_TIMEOUT` can still explicitly override the default wall-clock cap, but it cannot weaken the scenario contract: if the override value is shorter than the tick budget the verifier actually declares, `evidence_run.sh` will refuse to run and will not publish a bundle acceptable to the release gate. The current `2120000 ticks` needs at least `141334 seconds` to cover 15 TPS; the default diamond value and the harness's hard cap are both `172800 seconds`, and cover the GameTest-locked `2592000 ticks` plan ceiling. None of these are a commitment about a single run's actual elapsed time; reaching the full postcondition early still results in an immediate PASS.

GitHub nightly's manual entry point offers the same `prepared` / `from_zero` choice; regular PRs do not run these multi-hour scenarios. `MINING_MULTI_SEED_GATE=PASS` still does not automatically rewrite the capability manifest; until the remaining release gates and a committable pin format for aggregated evidence land, the final capability continues to fail-closed as `MISSING`.

### GitHub Actions Sharded Execution

After manually selecting `from_zero`, the workflow generates a fixed 58-job matrix from [mining_acceptance_contract.sh](../scripts/lib/mining_acceptance_contract.sh): 20 primary runs plus 9 sentinel runs for each of the two capabilities. Each job runs independently for at most 350 minutes, to avoid squeezing 29 sequential runs past the GitHub-hosted runner's 6-hour limit. This 350 minutes is the current GitHub runner's execution envelope and does not cover every possible dynamic diamond hard timeout; if diamond completes a full PASS before that point, the evidence remains valid — otherwise the job may be terminated by the platform before the verifier's typed timeout, and a missing shard will make the aggregation fail-closed. Resolving this limitation will require further tightening and re-proving the mission budget, or adjusting the runner architecture, in the future; this contract does not misrepresent 350 minutes as the full scenario's upper bound.

Each shard uses [mining_evidence_shard.sh](../scripts/mining_evidence_shard.sh) to generate one sealed run bundle and one sealed descriptor. Each of the two final target-specific aggregate jobs downloads only its own 29 explicitly named artifacts, and [mining_evidence_aggregate.sh](../scripts/mining_evidence_aggregate.sh) performs the following checks:

1. The expected 29 shard IDs exactly match the downloaded set;
2. The descriptor checksum/`LOCKED` and run checksum/`LOCKED` are all valid;
3. Each seed/run-index is unique, and the same run bundle must not be counted more than once;
4. Scenario, actual seed, strict profile, absence of privileged capability, commit, config, and build/runtime all match;
5. Every run counted as PASS has its Survival/privilege boundary and the corresponding target's physical-provenance counts re-verified;
6. Reconstructs a primary/sentinel canonical batch that the existing `evidence_validate.sh` can independently verify;
7. Finally emits only `MINING_MULTI_SEED_GATE`, explicitly stating `FULL_CAPABILITY_CERTIFIED=no`.

The artifact name and download pattern are bound to both `github.run_id` and `github.run_attempt`, prohibiting different rerun batches from being mixed into one aggregation. If a shard needs to be rerun, the entire workflow should be rerun rather than only rerunning the failed job and reusing the successful artifacts from the previous attempt.
