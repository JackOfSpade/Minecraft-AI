# Minecraft-AI Capability Matrix

This file is generated from `reports/capability_baseline_manifest.tsv` and `reports/baselines/index.tsv` via `scripts/capability_matrix.sh`.

Important: `reports/baselines/index.tsv` is the sole selector for VERIFIED single-run evidence of ordinary capabilities, taking precedence over the legacy manifest; the generator never automatically picks the “best” or “latest” result. Mining First explicitly forbids single-run pinning — it must first pass the fixed 20-seed and sentinel-batch gates in `scripts/mining_release_gate.sh`. Until a qualifying aggregated-evidence format exists, both Mining First capabilities remain `MISSING`. The manifest retains historical source filenames and SHA-256 hashes for traceability, but the generator does not read or depend on these local reports; legacy data lacks a tested revision, config hash, and actual seed, so it is always `UNVERIFIED`.

| ID | Capability | Scenario | Result | Maturity | Evidence | Confidence | Tested Version | Date | Mode | Fixture | Notes |
|---|---|---|---:|---|---|---|---|---|---|---|---|
| `diamond_stack_64` | Mine a full stack of diamonds from zero | `diamond_stack_64_from_zero` | — | `BLOCKED` | `MISSING` | `NONE` | No test record | — | — | Natural terrain, empty inventory, 64 diamonds, zero deaths, strict_survival | Mining First's final commitment; the controlled contract only verifies the quantity contract, and prepared is only for long-run diagnostics; no pinnable from_zero evidence yet |
| `obsidian_half_stack_32` | Obtain a half-stack of obsidian from zero | `obsidian_half_stack_32_from_zero` | — | `BLOCKED` | `MISSING` | `NONE` | No test record | — | — | Natural terrain, empty inventory, autonomously obtains a diamond pickaxe and bucket, 32 obsidian, zero deaths, strict_survival | Mining First's final commitment; must use real water placement and vanilla fluid reactions; no pinnable from_zero evidence yet |
| `wood_from_zero` | Natural wood gathering | `real_wood` | 4/4 (100%; 4 seeds; FAIL 0, ERR 0) | `DEMONSTRATED` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed not read back | 2026-06-24 | unknown (legacy) | Natural terrain, empty inventory, daytime, surface-only, zero deaths | Fixed small batch all green, but the report is not bound to code and config |
| `food_from_zero` | Obtain cooked food | `real_food` | 8/10 (80%; 10 seeds; FAIL 2, ERR 0) | `ALPHA` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed not read back | 2026-06-18 | unknown (legacy) | Natural terrain, empty inventory, daytime, surface-only, four servings of cooked food, zero deaths | A relatively good batch at 8/10, not representative of current HEAD; failure stage other=2 |
| `wheat_to_bread` | Grow wheat and bake bread | `real_wheat` | 1/5 (20%; 5 seeds; FAIL 4, ERR 0) | `UNSTABLE` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed not read back | 2026-06-24 | unknown (legacy) | Natural terrain, empty inventory, randomTickSpeed=40, two loaves of bread, zero deaths | Growth time is accelerated, yet only a few succeed; failure stages other=3, wood_gather=1 |
| `iron_bulk_100` | Iron ore at the hundred scale | `real_iron_bulk` | 2/12 (17%; 6 seeds; FAIL 6, ERR 4) | `UNSTABLE` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed not read back | 2026-07-03 | unknown (legacy) | Pre-supplied with five iron pickaxes, deep-mining gear, and 64 torches; target 100 raw iron | Isolated long-run mining; ERR counts toward the total denominator; failure stages other=6, server_err=4 |
| `diamond_from_zero` | Mine a diamond from zero | `real_diamond` | 6/10 (60%; 10 seeds; FAIL 4, ERR 0) | `EXPERIMENTAL` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed not read back | 2026-07-06 | unknown (legacy) | Natural terrain, empty inventory, daytime, surface-only, one diamond, zero deaths | Full deep-mining tool chain, not yet at the release threshold; failure stages other=2, timeout=1, wood_gather=1 |
| `iron_armor_from_zero` | Full iron armor and sword from zero | `real_armor` | 10/12 (83%; 6 seeds; FAIL 2, ERR 0) | `ALPHA` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed not read back | 2026-07-05 | unknown (legacy) | Natural terrain, empty inventory, full iron armor set plus iron sword, zero deaths | One of the strongest long chains in the current local reports; failure stage timeout=2 |
| `small_hut_core` | Build a house on real terrain | `real_build` | 7/10 (70%; 10 seeds; FAIL 3, ERR 0) | `ALPHA` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed not read back | 2026-06-24 | unknown (legacy) | Pre-supplied planks from multiple wood types and ground-leveling materials; only tests site selection, ground leveling, and completion | Asserts at least 80 planks nearby; does not represent structural completeness; failure stage other=3 |
| `navigate_120` | Long-distance navigation | `real_nav_far` | 0/4 (0%; 4 seeds; FAIL 4, ERR 0) | `BLOCKED` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed not read back | 2026-06-24 | unknown (legacy) | Long-distance movement on natural terrain | Sample size is very small, but the current pinned batch is all red; failure stages aborted=2, other=1, timeout=1 |
| `combat_multiseed` | Comprehensive combat | `combat` | — | `UNKNOWN` | `MISSING` | `NONE` | No test record | — | — | No explicitly pinned multi-seed combat report yet | A deterministic combat scenario and combat implementation exist, but there is no comparable real baseline |

## Determination Rules

- `DEMONSTRATED`: Stable performance in a limited pinned batch, but not yet meeting the release gate.
- `ALPHA`: The main chain runs end-to-end, but still has significant environmental failures.
- `EXPERIMENTAL`: Can succeed, but success rate or process stability is insufficient.
- `UNSTABLE/BLOCKED`: Cannot be made as a commitment to users.
- `UNVERIFIED`: The manifest retains only the legacy summary, historical source filenames, and hashes — it lacks verifiable evidence of a unique code revision, config, or actual seed.
- `MISSING`: No explicitly pinned, comparable batch exists yet.

## Release Gates

- Mining First: `diamond_stack_64` and `obsidian_half_stack_32` each require at least 20 fixed, public seeds, a success rate `>= 90%`, zero deaths, and `strict_survival`;
- The controlled contract only verifies quantity/persistence/postconditions, and prepared is used only for diagnostics; a PASS on either must not certify the final capability;
- Each of the four golden chains requires at least 20 fixed, public seeds, with a success rate `>= 90%`;
- `cancel/replace/restart-resume` must be `100%`;
- `PARTIAL` must not count toward PASS;
- Every run counted toward the gate must record `commit_sha/config_hash/actual_seed/mode`;
- `UNVERIFIED` and `MISSING` never count toward the release pass rate.

## Update Procedure

1. Run immutable, metadata-tagged multi-seed tests;
2. For ordinary capabilities, use `scripts/pin_baseline.sh` to explicitly bind a VERIFIED run to the capability ID; Mining First is forbidden from using this single-run entry point;
3. Commit the immutable bundle together with `reports/baselines/index.tsv` to version control;
4. Run `bash scripts/capability_matrix.sh --output docs/CAPABILITY_MATRIX.md`;
5. Once P0-07b CI is established, run `bash scripts/capability_matrix.sh --check docs/CAPABILITY_MATRIX.md`.
