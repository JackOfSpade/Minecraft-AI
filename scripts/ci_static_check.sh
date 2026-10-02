#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$ROOT"

fail() {
  printf '[ci-static] ERROR: %s\n' "$1" >&2
  exit 1
}

required_files=(
  build.gradle
  src/gametest/resources/fabric.mod.json
  src/gametest/java/io/github/zoyluo/minecraftai/gametest/MinecraftAiDeterministicGameTests.java
  src/gametest/java/io/github/zoyluo/minecraftai/gametest/MinecraftAiHarnessTestMod.java
  src/gametest/java/io/github/zoyluo/minecraftai/command/MinecraftAiTestSubcommand.java
  src/gametest/java/io/github/zoyluo/minecraftai/command/MinecraftAiVerifySubcommand.java
  scripts/evidence_run.sh
  scripts/evidence_batch.sh
  scripts/evidence_validate.sh
  scripts/mining_acceptance.sh
  scripts/mining_evidence_shard.sh
  scripts/mining_evidence_aggregate.sh
  scripts/mining_release_gate.sh
  scripts/pin_baseline.sh
  scripts/persistence_restart_test.sh
  scripts/lib/harness.sh
  scripts/lib/mining_acceptance_contract.sh
  scripts/capability_matrix.sh
  reports/baselines/index.tsv
  reports/capability_baseline_manifest.tsv
  docs/CAPABILITY_MATRIX.md
  docs/MINING_ACCEPTANCE.md
  .github/workflows/ci.yml
  .github/workflows/nightly.yml
  .github/workflows/manual-llm.yml
)

for file in "${required_files[@]}"; do
  [[ -f "$file" ]] || fail "missing required file: $file"
done

grep -Fq 'createSourceSet.set(true)' build.gradle \
  || fail 'Fabric GameTest must use an isolated source set'
grep -Fq "modId.set('minecraftai-gametest')" build.gradle \
  || fail 'Fabric GameTest mod id is not pinned'
grep -Fq 'runHarnessServer' build.gradle \
  || fail 'command-driven harness run is missing'
grep -Fq 'must be a project-relative child directory' build.gradle \
  || fail 'harness run directory traversal guard is missing'
grep -Fq '"fabric-gametest"' src/gametest/resources/fabric.mod.json \
  || fail 'GameTest entrypoint is not registered'

[[ ! -e src/main/java/io/github/zoyluo/minecraftai/command/MinecraftAiTestSubcommand.java ]] \
  || fail 'test command leaked into production source set'
[[ ! -e src/main/java/io/github/zoyluo/minecraftai/command/MinecraftAiVerifySubcommand.java ]] \
  || fail 'verify command leaked into production source set'
if find src/main -type f \( -iname '*gametest*.java' -o -path '*/gametest/*' \) -print -quit | grep -q .; then
  fail 'GameTest implementation leaked into the production source set'
fi
if grep -RqE 'MinecraftAi(Test|Verify)Subcommand|literal\("(test|verify)"\)' src/main/java; then
  fail 'production command graph references a verification harness'
fi

for script in scripts/ci_static_check.sh scripts/food_test.sh scripts/night_watch.sh \
  scripts/evidence_run.sh scripts/evidence_batch.sh scripts/evidence_validate.sh \
  scripts/mining_acceptance.sh scripts/mining_evidence_shard.sh scripts/mining_evidence_aggregate.sh \
  scripts/mining_release_gate.sh scripts/pin_baseline.sh scripts/persistence_restart_test.sh \
  scripts/capability_matrix.sh scripts/deploy_profile.sh scripts/gate.sh scripts/auto30.sh \
  scripts/story.sh scripts/reliability.sh scripts/lib/harness.sh \
  scripts/lib/mining_acceptance_contract.sh scripts/lib/devloop.sh scripts/lib/env_parse.sh \
  scripts/dev/gametest.sh scripts/dev/unittest.sh scripts/dev/nav_measurement.sh; do
  bash -n "$script" || fail "shell syntax failed: $script"
done

# Every third-party workflow action is pinned to an immutable commit. Dependabot
# still updates these pins, while this check prevents a mutable tag or branch
# from quietly becoming the executable CI supply chain.
while IFS= read -r uses_line; do
  action_ref="${uses_line##*@}"
  [[ "$action_ref" =~ ^[0-9a-f]{40}$ ]] \
    || fail "workflow action is not pinned to a full commit SHA: $uses_line"
done < <(grep -RhoE '^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]+[^[:space:]#]+' .github/workflows)

for workflow in .github/workflows/ci.yml .github/workflows/nightly.yml .github/workflows/manual-llm.yml; do
  grep -Fq 'fetch-depth: 0' "$workflow" \
    || fail "$workflow must fetch full history for commit reachability validation"
  grep -Eq 'actions/upload-artifact@[0-9a-f]{40}' "$workflow" \
    || fail "$workflow does not upload diagnostics"
  grep -Fq 'if: always()' "$workflow" \
    || fail "$workflow may discard diagnostics after a failure"
  if grep -Fq 'scripts/food_test.sh' "$workflow"; then
    fail "$workflow still invokes the legacy shared-run wrapper"
  fi
done

for workflow in .github/workflows/ci.yml .github/workflows/nightly.yml; do
  grep -Fq 'runGameTest' "$workflow" || fail "$workflow does not execute runGameTest"
  if grep -Eq '(DEEPSEEK|MINECRAFTAI_LLM)_API_KEY' "$workflow"; then
    fail "$workflow must not have access to the billed LLM secret"
  fi
done
grep -Fq 'scripts/evidence_run.sh' .github/workflows/ci.yml \
  || fail 'PR CI does not run isolated runtime evidence'
grep -Fq 'mining_contract_suite' .github/workflows/ci.yml \
  || fail 'PR CI does not run the fast Mining First contract'
grep -Fq 'scripts/evidence_batch.sh' .github/workflows/nightly.yml \
  || fail 'nightly does not run the profile/seed evidence matrix'
grep -Fq 'scripts/mining_acceptance.sh' .github/workflows/nightly.yml \
  || fail 'nightly lacks the explicit long Mining First entrypoint'
grep -Fq 'fromJSON(needs.prepare_mining_shards.outputs.matrix)' .github/workflows/nightly.yml \
  || fail 'from_zero Mining First workflow is not using the fixed parallel shard matrix'
grep -Eq 'actions/download-artifact@[0-9a-f]{40}' .github/workflows/nightly.yml \
  || fail 'from_zero Mining First workflow does not download shard evidence for aggregation'
grep -Fq 'scripts/mining_evidence_aggregate.sh' .github/workflows/nightly.yml \
  || fail 'from_zero Mining First workflow does not revalidate and aggregate shards'
grep -Fq 'merge-multiple: true' .github/workflows/nightly.yml \
  || fail 'from_zero Mining First shard artifacts are not merged into the canonical evidence roots'
grep -Fq 'pattern: mining-shard-${{ matrix.target }}-*-${{ github.run_id }}-${{ github.run_attempt }}' .github/workflows/nightly.yml \
  || fail 'from_zero Mining First aggregation can mix artifacts from different workflow attempts'
if grep -Eq 'mining_acceptance\.sh[^[:cntrl:]]*from_zero' .github/workflows/nightly.yml; then
  fail 'from_zero Mining First must not regress to one sequential GitHub-hosted job'
fi
grep -Fq 'scripts/mining_release_gate.sh' scripts/mining_acceptance.sh \
  || fail 'from_zero Mining First runs do not enforce the aggregate release gate'
grep -Fq 'FULL_CAPABILITY_CERTIFIED=no' scripts/mining_release_gate.sh \
  || fail 'multi-seed evidence gate must not claim full Mining First certification'
grep -Fq 'profile: [strict_survival, operator]' .github/workflows/nightly.yml \
  || fail 'nightly does not cover both operating profiles'

for capability in diamond_stack_64 obsidian_half_stack_32; do
  awk -F '\t' -v capability="$capability" 'NR > 1 && $1 == capability && $8 == "MISSING" { found++ } END { exit found == 1 ? 0 : 1 }' \
    reports/capability_baseline_manifest.tsv \
    || fail "Mining First capability must exist exactly once and remain MISSING until evidence is pinned: $capability"
  if awk -F '\t' -v capability="$capability" 'NR > 1 && $1 == capability { found=1 } END { exit found ? 0 : 1 }' reports/baselines/index.tsv; then
    fail "Mining First single-run baseline index entry is forbidden: $capability"
  fi
done
bash scripts/mining_release_gate.sh --self-test >/dev/null \
  || fail 'Mining First release threshold self-test failed'
bash scripts/mining_evidence_shard.sh --self-test >/dev/null \
  || fail 'Mining First shard matrix self-test failed'
bash scripts/mining_evidence_aggregate.sh --self-test >/dev/null \
  || fail 'Mining First evidence aggregator self-test failed'
bash scripts/lib/env_parse.sh --self-test >/dev/null \
  || fail 'deploy_profile.sh .env parser self-test failed'
bash scripts/dev/nav_measurement.sh --self-test >/dev/null \
  || fail 'P3 navigation measurement artifact snapshot self-test failed'
bash scripts/dev/unittest.sh --self-test >/dev/null \
  || fail 'portable unit-test runner self-test failed'
bash scripts/dev/gametest.sh --self-test >/dev/null \
  || fail 'portable GameTest runner self-test failed'

manual=.github/workflows/manual-llm.yml
grep -Fq 'workflow_dispatch:' "$manual" || fail 'manual LLM workflow is not dispatch-only'
if grep -Eq '^[[:space:]]+(push|pull_request|schedule):' "$manual"; then
  fail 'manual LLM workflow must never run automatically'
fi
grep -Fq 'secrets.DEEPSEEK_API_KEY' "$manual" \
  || fail 'manual LLM workflow does not receive its secret through GitHub Secrets'
grep -Fq 'MINECRAFTAI_LLM_API_KEY: ${{ secrets.DEEPSEEK_API_KEY }}' "$manual" \
  || fail 'manual LLM workflow must expose its secret to the run as MINECRAFTAI_LLM_API_KEY'
grep -Fq 'confirm_billing:' "$manual" \
  || fail 'manual LLM workflow does not require explicit billing confirmation'
grep -Fq -- '--mode llm_story' "$manual" \
  || fail 'manual LLM workflow is not explicitly marked as billed evidence'

# The mining assist stays off in every evidence run and in every workflow: evidence_run.sh pins it in both
# --with-llm branches and seals it into the hashed config, the validator refuses a non-off certifying
# bundle, and no workflow may opt in (an explicit --assist mode is a local, non-certifying choice).
grep -Fq 'ASSIST_MODE="off"' scripts/evidence_run.sh \
  || fail 'evidence_run.sh does not default the mining assist to off'
[[ "$(grep -Fc 'MINECRAFTAI_MINING_ASSIST="$ASSIST_MODE"' scripts/evidence_run.sh)" == 2 ]] \
  || fail 'evidence_run.sh must pin MINECRAFTAI_MINING_ASSIST in both --with-llm branches'
grep -Fq 'certifying_bundle_has_mining_assist_mode' scripts/evidence_validate.sh \
  || fail 'evidence_validate.sh does not reject a non-off mining assist mode in certifying bundles'
for workflow in .github/workflows/*.yml; do
  if grep -Eiq 'miningAssist' "$workflow"; then
    fail "$workflow must not configure the mining assist; evidence_run.sh pins it to off"
  fi
  if grep -Eq -- '--assist[[:space:]]*$' "$workflow"; then
    fail "$workflow passes --assist without a mode on the same line"
  fi
  while IFS= read -r assist_setting; do
    [[ -n "$assist_setting" ]] || continue
    assist_value="$(printf '%s' "$assist_setting" \
      | sed -E "s/^(MINECRAFTAI_MINING_ASSIST[[:space:]]*[:=]|--assist[[:space:]=])[[:space:]]*//; s/[\"']//g")"
    [[ "$assist_value" == off ]] \
      || fail "$workflow sets the mining assist to '$assist_value'; only off is allowed in CI"
  done < <(grep -hoE -- "(MINECRAFTAI_MINING_ASSIST[[:space:]]*[:=]|--assist[[:space:]=])[[:space:]]*[^[:space:]#]*" "$workflow" || true)
done

# When invoked after `build`, inspect every produced jar. Sources and production jars must both
# remain free of testmod classes and verification commands.
if [[ "${CI_STATIC_CHECK_ARTIFACTS:-0}" == 1 ]]; then
  [[ -d build/libs ]] || fail 'artifact inspection requested before build/libs exists'
  inspected=0
  while IFS= read -r -d '' jar_file; do
    inspected=1
    if jar tf "$jar_file" | grep -Eq 'io/github/zoyluo/minecraftai/(gametest/|command/MinecraftAi(Test|Verify)Subcommand)'; then
      fail "verification harness leaked into jar: $jar_file"
    fi
  done < <(find build/libs -maxdepth 1 -type f -name '*.jar' -print0)
  [[ "$inspected" == 1 ]] || fail 'artifact inspection found no jars'
fi

bash scripts/capability_matrix.sh --check docs/CAPABILITY_MATRIX.md \
  || fail 'generated capability matrix is stale or its pinned evidence is invalid'

printf '[ci-static] OK: source-set, workflow, shell and artifact invariants hold\n'
