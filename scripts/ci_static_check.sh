#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$ROOT"

fail() {
  printf '[ci-static] ERROR: %s\n' "$1" >&2
  exit 1
}

archive_entries() {
  local archive="$1"
  if command -v jar >/dev/null 2>&1; then
    jar tf "$archive"
  elif command -v unzip >/dev/null 2>&1; then
    unzip -Z1 "$archive"
  elif command -v tar >/dev/null 2>&1; then
    tar -tf "$archive"
  else
    printf '[ci-static] no jar, unzip, or tar command is available for artifact inspection\n' >&2
    return 127
  fi
}

extract_archive_entry() {
  local archive="$1" entry="$2" destination="$3"
  if command -v jar >/dev/null 2>&1; then
    (cd "$destination" && jar xf "$archive" "$entry")
  elif command -v unzip >/dev/null 2>&1; then
    unzip -qq "$archive" "$entry" -d "$destination"
  elif command -v tar >/dev/null 2>&1; then
    tar -xf "$archive" -C "$destination" "$entry"
  else
    printf '[ci-static] no jar, unzip, or tar command is available for artifact extraction\n' >&2
    return 127
  fi
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
  .github/workflows/codeql.yml
  .github/workflows/nightly.yml
  .github/workflows/manual-llm.yml
  .github/workflows/release.yml
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

grep -Fq 'Minecraft-Spawn-Bots-Wrapper' scripts/deploy_profile.sh \
  || fail 'local deploy does not build the bundled PvP BOT wrapper'
grep -Fq "'pvpbot-inhabitants-*.jar'" scripts/deploy_profile.sh \
  || fail 'local deploy does not replace the bundled PvP BOT wrapper jar'
grep -Fq 'require_profile_fabric_mod "pvp_bot" "PvP BOT"' scripts/deploy_profile.sh \
  || fail 'local deploy can install the PvP BOT wrapper without its required upstream mod'
grep -Fq 'fabric.mod.json' scripts/deploy_profile.sh \
  || fail 'local deploy does not inspect the upstream Fabric mod id instead of trusting a filename'
grep -Fq 'require_no_unmanaged_owned_mod_jars' scripts/deploy_profile.sh \
  || fail 'local deploy can publish a duplicate owned Fabric mod id from a renamed stale jar'
grep -Fq 'OWNED_MOD_TX_SELF_TEST_FAIL_PUBLISH_LABEL' scripts/deploy_profile.sh \
  || fail 'local deploy transaction self-test does not exercise a post-root-publish failure'
grep -Fq '[IO.File]::Replace($staged, $path, "$path.bak", $true)' scripts/deploy_profile.sh \
  || fail 'local deploy does not atomically replace an existing profile config'
grep -Fq '$j.llm -is [pscustomobject]' scripts/deploy_profile.sh \
  || fail 'local deploy does not normalize a malformed current LLM section before editing it'
grep -Fq 'install_owned_mods_transactionally "$JAR" "$WRAPPER_JAR"' scripts/deploy_profile.sh \
  || fail 'local deploy does not transact the complete owned-mod set together'
grep -Fq 'rollback_owned_mod_install' scripts/deploy_profile.sh \
  || fail 'local deploy cannot restore its owned jars after a publish failure'
grep -Fq 'OWNED_MOD_TX_PUBLISHED_TARGETS' scripts/deploy_profile.sh \
  || fail 'local deploy rollback does not limit removal to targets it actually published'
grep -Fq '.minecraftai-owned-mod-deploy.' scripts/deploy_profile.sh \
  || fail 'local deploy does not keep incomplete jar staging outside the mods directory'
grep -Fq 'DEEPSEEK_API_KEY' scripts/lib/env_parse.sh \
  || fail 'local deploy does not migrate the published legacy API-key environment name'
grep -Fq 'DEEPSEEK_API_KEY' .env.example \
  || fail 'the local env template does not document the published legacy API-key environment name'
grep -Fq 'cd /d \"$WRAPPER_ROOT_WIN\" && gradlew.bat' scripts/deploy_profile.sh \
  || fail 'local deploy can build the root project instead of the bundled PvP BOT wrapper'

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
grep -Fq 'working-directory: Minecraft-Spawn-Bots-Wrapper' .github/workflows/ci.yml \
  || fail 'PR CI does not run the independent PvP BOT wrapper project'
grep -Fq 'clean test compileGametestJava build' .github/workflows/ci.yml \
  || fail 'PR CI does not compile every source set and unit-test the independent PvP BOT wrapper project'
grep -Fq 'working-directory: Minecraft-Spawn-Bots-Wrapper' .github/workflows/release.yml \
  || fail 'tag releases do not test and build the independent PvP BOT wrapper project'
grep -Fq 'clean test compileGametestJava build' .github/workflows/release.yml \
  || fail 'tag releases do not compile every PvP BOT wrapper source set'
grep -Fq 'wrapper_jar=' .github/workflows/release.yml \
  || fail 'tag releases do not collect the PvP BOT wrapper artifact'
grep -Fq 'steps.artifacts.outputs.wrapper_jar' .github/workflows/release.yml \
  || fail 'tag releases do not attach the PvP BOT wrapper artifact'
grep -Fq 'working-directory: Minecraft-Spawn-Bots-Wrapper' .github/workflows/codeql.yml \
  || fail 'CodeQL does not compile the independent PvP BOT wrapper project'
grep -Fq 'compileGametestJava' .github/workflows/codeql.yml \
  || fail 'CodeQL does not compile every configured wrapper source set'
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
bash scripts/deploy_profile.sh --self-test-transaction >/dev/null \
  || fail 'deploy_profile.sh transaction rollback self-test failed'
bash scripts/dev/nav_measurement.sh --self-test >/dev/null \
  || fail 'Baritone navigation measurement artifact snapshot self-test failed'
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
# remain free of testmod classes and verification commands. The wrapper is a separately built
# release asset, so its job asks for `wrapper`; the tag-release job asks for `all` after both
# independent builds have completed. `1` remains the original root-artifact mode for callers.
artifact_mode="${CI_STATIC_CHECK_ARTIFACTS:-0}"
case "$artifact_mode" in
  0|'') artifact_dirs=() ;;
  1|main) artifact_dirs=(build/libs) ;;
  wrapper) artifact_dirs=(Minecraft-Spawn-Bots-Wrapper/build/libs) ;;
  all) artifact_dirs=(build/libs Minecraft-Spawn-Bots-Wrapper/build/libs) ;;
  *) fail "unknown CI_STATIC_CHECK_ARTIFACTS mode: $artifact_mode" ;;
esac

inspect_wrapper_metadata() {
  local jar_file="$1" jar_dir metadata_dir metadata
  jar_dir="$(cd "$(dirname "$jar_file")" && pwd -P)" \
    || fail "could not resolve wrapper artifact directory: $jar_file"
  jar_file="$jar_dir/$(basename "$jar_file")"
  metadata_dir="$(mktemp -d)" || fail "could not create metadata directory for $jar_file"
  if ! extract_archive_entry "$jar_file" fabric.mod.json "$metadata_dir"; then
    rm -rf "$metadata_dir"
    fail "could not extract wrapper Fabric metadata from $jar_file"
  fi
  [[ -f "$metadata_dir/fabric.mod.json" ]] || {
    rm -rf "$metadata_dir"
    fail "wrapper production jar has no fabric.mod.json: $jar_file"
  }
  metadata="$(<"$metadata_dir/fabric.mod.json")"
  rm -rf "$metadata_dir"
  grep -Fq '"id": "pvpbot_inhabitants"' <<<"$metadata" \
    || fail "wrapper production jar has the wrong Fabric mod id: $jar_file"
  grep -Fq '"pvp_bot": "*"' <<<"$metadata" \
    || fail "wrapper production jar does not retain its required pvp_bot dependency: $jar_file"
  if grep -Fq 'fabric-gametest' <<<"$metadata"; then
    fail "wrapper GameTest metadata leaked into production jar: $jar_file"
  fi
}

if [[ "${#artifact_dirs[@]}" -gt 0 ]]; then
  inspected=0
  wrapper_production_artifacts=0
  wrapper_artifacts_requested=0
  for artifact_dir in "${artifact_dirs[@]}"; do
    [[ -d "$artifact_dir" ]] || fail "artifact inspection requested before $artifact_dir exists"
    if [[ "$artifact_dir" == Minecraft-Spawn-Bots-Wrapper/build/libs ]]; then
      wrapper_artifacts_requested=1
    fi
    while IFS= read -r -d '' jar_file; do
      inspected=1
      if ! jar_entries="$(archive_entries "$jar_file")"; then
        fail "could not list jar for artifact inspection: $jar_file"
      fi
      if grep -Eq 'io/github/zoyluo/minecraftai/(gametest/|command/MinecraftAi(Test|Verify)Subcommand)' <<<"$jar_entries"; then
        fail "verification harness leaked into jar: $jar_file"
      fi
      if [[ "$artifact_dir" == Minecraft-Spawn-Bots-Wrapper/build/libs ]]; then
        if grep -Eq 'dev/spawnbotswrapper/inhabitants/gametest/|pvpbot-inhabitants-gametest' <<<"$jar_entries"; then
          fail "wrapper GameTest payload leaked into jar: $jar_file"
        fi
        case "$(basename "$jar_file")" in
          pvpbot-inhabitants-*-sources.jar|pvpbot-inhabitants-*-dev.jar) ;;
          pvpbot-inhabitants-*.jar)
            wrapper_production_artifacts=$((wrapper_production_artifacts + 1))
            inspect_wrapper_metadata "$jar_file"
            ;;
        esac
      fi
    done < <(find "$artifact_dir" -maxdepth 1 -type f -name '*.jar' -print0)
  done
  [[ "$inspected" == 1 ]] || fail 'artifact inspection found no jars'
  if [[ "$wrapper_artifacts_requested" == 1 && "$wrapper_production_artifacts" != 1 ]]; then
    fail "expected exactly one production pvpbot-inhabitants jar, found $wrapper_production_artifacts"
  fi
fi

bash scripts/capability_matrix.sh --check docs/CAPABILITY_MATRIX.md \
  || fail 'generated capability matrix is stale or its pinned evidence is invalid'

printf '[ci-static] OK: source-set, workflow, shell and artifact invariants hold\n'
