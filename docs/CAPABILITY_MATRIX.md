# MinecraftAi Capability Matrix

This file is generated from `reports/capability_baseline_manifest.tsv` and `reports/baselines/index.tsv` via `scripts/capability_matrix.sh`.

Important: `reports/baselines/index.tsv` is the sole selector for VERIFIED single-run evidence of ordinary capabilities, taking priority over the legacy manifest; the generator never auto-selects the “best” or “most recent” result. Mining First explicitly forbids single-run pinning and must first pass the fixed 20-seed and sentinel-batch gate in `scripts/mining_release_gate.sh`. Until a qualifying aggregated-evidence format exists, both Mining First capabilities remain `MISSING`. The manifest retains historical source filenames and SHA-256 hashes for traceability, but the generator does not read or depend on these local reports; legacy data lacks a tested revision, config hash, and actual seed, so it is always `UNVERIFIED`.

| ID | Capability | Scenario | Result | Maturity | Evidence | Confidence | Tested Revision | Date | Mode | Fixture | Note |
|---|---|---|---:|---|---|---|---|---|---|---|---|
| `diamond_stack_64` | Mine a full stack of diamonds from scratch | `diamond_stack_64_from_zero` | — | `BLOCKED` | `MISSING` | `NONE` | no test record | — | — | natural terrain, empty inventory, 64 diamonds, zero deaths, strict_survival | Mining First final commitment; controlled only verifies the count contract, prepared is only for long-run diagnostics; no pinnable from_zero evidence yet |
| `obsidian_half_stack_32` | Obtain half a stack of obsidian from scratch | `obsidian_half_stack_32_from_zero` | — | `BLOCKED` | `MISSING` | `NONE` | no test record | — | — | natural terrain, empty inventory, self-acquired diamond pickaxe and bucket, 32 obsidian, zero deaths, strict_survival | Mining First final commitment; must use real water placement and vanilla fluid reactions; no pinnable from_zero evidence yet |
| `wood_from_zero` | Natural wood gathering | `real_wood` | 4/4 (100%; 4 seeds; FAIL 0, ERR 0) | `DEMONSTRATED` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed not read back | 2026-06-24 | unknown (legacy) | natural terrain, empty inventory, daytime, surface-level, zero deaths | Fixed small batch all green, but the report is not bound to code and configuration |
| `food_from_zero` | Obtain cooked food | `real_food` | 8/10 (80%; 10 seeds; FAIL 2, ERR 0) | `ALPHA` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed not read back | 2026-06-18 | unknown (legacy) | natural terrain, empty inventory, daytime, surface-level, four cooked food items, zero deaths | Better-than-average batch 8/10, not representative of current HEAD; failure phase other=2 |
| `wheat_to_bread` | Grow wheat and bake bread | `real_wheat` | 1/5 (20%; 5 seeds; FAIL 4, ERR 0) | `UNSTABLE` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed not read back | 2026-06-24 | unknown (legacy) | natural terrain, empty inventory, randomTickSpeed=40, two bread, zero deaths | Growth time was accelerated, yet still only a small number of successes; failure phase other=3, wood_gather=1 |
| `iron_bulk_100` | Bulk iron ore (hundreds) | `real_iron_bulk` | 2/12 (17%; 6 seeds; FAIL 6, ERR 4) | `UNSTABLE` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed not read back | 2026-07-03 | unknown (legacy) | pre-equipped with five iron pickaxes, deep-mining gear, and 64 torches, target 100 raw iron | Isolated long-run mining; ERR counted in the total denominator; failure phase other=6, server_err=4 |
| `diamond_from_zero` | Mine diamond from scratch | `real_diamond` | 6/10 (60%; 10 seeds; FAIL 4, ERR 0) | `EXPERIMENTAL` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed not read back | 2026-07-06 | unknown (legacy) | natural terrain, empty inventory, daytime, surface-level, one diamond, zero deaths | Full deep-tier tool chain, has not yet met the release threshold; failure phase other=2, timeout=1, wood_gather=1 |
| `iron_armor_from_zero` | Iron armor and sword from scratch | `real_armor` | 10/12 (83%; 6 seeds; FAIL 2, ERR 0) | `ALPHA` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed not read back | 2026-07-05 | unknown (legacy) | natural terrain, empty inventory, full iron armor set plus iron sword, zero deaths | One of the strongest long chains in the current local reports; failure phase timeout=2 |
| `small_hut_core` | Build a hut on real terrain | `real_build` | 7/10 (70%; 10 seeds; FAIL 3, ERR 0) | `ALPHA` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed not read back | 2026-06-24 | unknown (legacy) | pre-equipped with multiple wood-type planks and ground-leveling materials, tests only site selection, ground leveling, and completion | Assertion is at least 80 planks nearby, does not represent structural completeness; failure phase other=3 |
| `navigate_120` | Long-distance navigation | `real_nav_far` | 0/4 (0%; 4 seeds; FAIL 4, ERR 0) | `BLOCKED` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed not read back | 2026-06-24 | unknown (legacy) | natural terrain, long-distance movement | Sample size is very small, but the current pinned batch is all-red (all failing); failure phase aborted=2, other=1, timeout=1 |
| `combat_multiseed` | Combined combat | `combat` | — | `UNKNOWN` | `MISSING` | `NONE` | no test record | — | — | no explicitly pinned multi-seed combat report yet | A deterministic combat scenario and combat implementation exist, but there is no comparable real baseline |

## Determination Rules

- `DEMONSTRATED`: stable across a limited pinned batch, but has not yet reached the release gate.
- `ALPHA`: the main chain runs end-to-end, but still has significant environmental failures.
- `EXPERIMENTAL`: can succeed, but success rate or process stability is insufficient.
- `UNSTABLE/BLOCKED`: cannot be presented as a commitment to users.
- `UNVERIFIED`: the manifest retains only the legacy aggregate, historical source filename, and hash — it lacks verifiable, unique code, config, or actual-seed evidence.
- `MISSING`: no explicitly pinned, comparable batch exists yet.

## Release Gates

- Mining First: `diamond_stack_64` and `obsidian_half_stack_32` each require at least 20 fixed, published seeds, a success rate `>= 90%`, zero deaths, under `strict_survival`;
- The controlled contract only verifies quantity/persistence/postconditions, and prepared is for diagnostics only; a PASS on either must not certify the final capability;
- Each of the four golden chains requires at least 20 fixed, published seeds, with a success rate `>= 90%`;
- `cancel/replace/restart-resume` must be `100%`;
- `PARTIAL` must not count as a PASS;
- Every run counted toward the gate must record `commit_sha/config_hash/actual_seed/mode`;
- `UNVERIFIED` and `MISSING` never count toward the release pass rate.

## Update Procedure

1. Run immutable, metadata-tagged multi-seed tests;
2. For ordinary capabilities, use `scripts/pin_baseline.sh` to explicitly bind a VERIFIED run to the capability ID; Mining First is forbidden from using this single-run entry point;
3. Commit the immutable bundle together with `reports/baselines/index.tsv` to version control;
4. Run `bash scripts/capability_matrix.sh --output docs/CAPABILITY_MATRIX.md`;
5. After P0-07b CI is established, run `bash scripts/capability_matrix.sh --check docs/CAPABILITY_MATRIX.md`.
