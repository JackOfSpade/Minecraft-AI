#!/usr/bin/env bash
# Generates the capability matrix from an explicitly pinned manifest. Scanning a directory and auto-selecting the best or most recent result is forbidden.
# The legacy aggregate value is an UNVERIFIED historical snapshot from the manifest; the generation process must not read local reliability TSVs.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="${CAPABILITY_BASELINE_MANIFEST:-$ROOT/reports/capability_baseline_manifest.tsv}"
BASELINE_INDEX="${CAPABILITY_BASELINE_INDEX:-$ROOT/reports/baselines/index.tsv}"
EXPECTED_MANIFEST_HEADER=$'capability_id\tdisplay_name\tscenario\tlegacy_result\tsource_file\tsource_sha256\tmaturity\tevidence_state\tconfidence\ttested_revision\trun_date\tmode\tactual_seed_verified\tfixture\tnote'
EXPECTED_INDEX_HEADER=$'capability_id\trun_id\tevidence_path\tevidence_lock_sha256\tcommit_sha\tscenario\tpinned_at_utc'

usage() {
  echo "usage: bash scripts/capability_matrix.sh [--output <file> | --check <file>]" >&2
}

sha256_file() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
    return
  fi
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
    return
  fi
  echo "capability-matrix: no SHA-256 command available" >&2
  return 1
}

md_escape() {
  local value="${1:-}"
  value="${value//|/\\|}"
  printf '%s' "$value"
}

validate_manifest() {
  local actual_header
  IFS= read -r actual_header < "$MANIFEST"
  if [[ "$actual_header" != "$EXPECTED_MANIFEST_HEADER" ]]; then
    echo "capability-matrix: unexpected manifest header" >&2
    return 1
  fi
  awk -F '\t' '
    NR == 1 { next }
    NF != 15 || $1 == "" || $2 == "" || $3 == "" || $4 == "" || $14 == "" || $15 == "" { exit 2 }
    seen[$1]++ { exit 3 }
    $8 == "UNVERIFIED" {
      if ($5 !~ /^reports\/reliability_[A-Za-z0-9_.+-]+\.tsv$/ ||
          length($6) != 64 || $6 !~ /^[0-9a-f]+$/ || $13 != "no") exit 4
      next
    }
    $8 == "MISSING" {
      if ($4 != "—" || $5 != "-" || $6 != "-" || $13 != "no") exit 5
      next
    }
    { exit 6 }
  ' "$MANIFEST" || {
    echo "capability-matrix: malformed row, duplicate capability_id, or dishonest legacy state" >&2
    return 1
  }
}

manifest_get() {
  local file="$1" key="$2"
  awk -F '\t' -v wanted="$key" '$1 == wanted { print substr($0, index($0, "\t") + 1); exit }' "$file"
}

validate_index() {
  local actual_header
  [[ -f "$BASELINE_INDEX" && ! -L "$BASELINE_INDEX" ]] || {
    echo "capability-matrix: baseline index missing or unsafe: $BASELINE_INDEX" >&2
    return 1
  }
  IFS= read -r actual_header < "$BASELINE_INDEX"
  [[ "$actual_header" == "$EXPECTED_INDEX_HEADER" ]] || {
    echo 'capability-matrix: unexpected baseline index header' >&2
    return 1
  }
  awk -F '\t' -v registry="$MANIFEST" '
    NR == 1 { next }
    NF != 7 || $1 == "" || seen[$1]++ || $1 !~ /^[A-Za-z0-9][A-Za-z0-9_.+-]*$/ ||
      $2 !~ /^[A-Za-z0-9][A-Za-z0-9_.+-]*$/ || index($3, "..") ||
      $3 !~ /^reports\/baselines\/[A-Za-z0-9][A-Za-z0-9_.+-]*\/[A-Za-z0-9][A-Za-z0-9_.+-]*$/ ||
      length($4) != 64 || $4 !~ /^[0-9a-f]+$/ ||
      (length($5) != 40 && length($5) != 64) || $5 !~ /^[0-9a-f]+$/ ||
      $6 !~ /^[A-Za-z0-9][A-Za-z0-9_.+-]*$/ ||
      $7 !~ /^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$/ { exit 2 }
  ' "$BASELINE_INDEX" || {
    echo 'capability-matrix: malformed or duplicate baseline index row' >&2
    return 1
  }
}

pinned_baseline() {
  local capability="$1" expected_scenario="$2" row
  row="$(awk -F '\t' -v wanted="$capability" 'NR > 1 && $1 == wanted { print; found++ } END { if (found > 1) exit 1 }' "$BASELINE_INDEX")" || return 1
  case "$capability" in
    diamond_stack_64|obsidian_half_stack_32)
      [[ -z "$row" ]] || {
        echo "capability-matrix: Mining First cannot be certified by the single-run baseline index: $capability" >&2
        return 1
      }
      return 2
      ;;
  esac
  [[ -n "$row" ]] || return 2

  local indexed_cap run_id evidence_path lock_hash commit_sha indexed_scenario pinned_at
  IFS=$'\t' read -r indexed_cap run_id evidence_path lock_hash commit_sha indexed_scenario pinned_at <<< "$row"
  [[ "$indexed_cap" == "$capability" && "$indexed_scenario" == "$expected_scenario"
      && "$evidence_path" == "reports/baselines/$capability/$run_id" ]] || {
    echo "capability-matrix: pinned index mismatch for $capability" >&2
    return 1
  }
  local bundle="$ROOT/$evidence_path"
  "$ROOT/scripts/evidence_validate.sh" --require-verified "$bundle" >/dev/null || {
    echo "capability-matrix: pinned evidence is not VERIFIED: $evidence_path" >&2
    return 1
  }
  [[ "$(sha256_file "$bundle/LOCKED")" == "$lock_hash" ]] || {
    echo "capability-matrix: pinned LOCKED hash mismatch: $evidence_path" >&2
    return 1
  }

  local manifest="$bundle/manifest.tsv" result_file="$bundle/result.tsv"
  local manifest_commit manifest_scenario manifest_result profile run_mode timestamp actual_seed seed_verified evidence_state
  manifest_commit="$(manifest_get "$manifest" commit_sha)"
  manifest_scenario="$(manifest_get "$manifest" scenario)"
  manifest_result="$(manifest_get "$manifest" result)"
  profile="$(manifest_get "$manifest" profile)"
  run_mode="$(manifest_get "$manifest" mode)"
  timestamp="$(manifest_get "$manifest" timestamp_utc)"
  actual_seed="$(manifest_get "$manifest" actual_seed)"
  seed_verified="$(manifest_get "$manifest" actual_seed_verified)"
  evidence_state="$(manifest_get "$manifest" evidence_state)"
  [[ "$manifest_commit" == "$commit_sha" && "$manifest_scenario" == "$expected_scenario"
      && "$evidence_state" == VERIFIED && "$profile" == strict_survival
      && "$run_mode" == deterministic && "$seed_verified" == yes ]] || {
    echo "capability-matrix: pinned provenance mismatch: $evidence_path" >&2
    return 1
  }

  local header row_scenario requested_seed row_actual row_result passed total exit_code summary
  IFS= read -r header < "$result_file"
  [[ "$header" == $'scenario\trequested_seed\tactual_seed\tresult\tpassed\ttotal\texit_code\tsummary' ]] || return 1
  IFS=$'\t' read -r row_scenario requested_seed row_actual row_result passed total exit_code summary < <(sed -n '2p' "$result_file")
  [[ "$row_scenario" == "$expected_scenario" && "$row_actual" == "$actual_seed"
      && "$row_result" == "$manifest_result" && "$passed" =~ ^[0-9]+$
      && "$total" =~ ^[1-9][0-9]*$ && "$exit_code" =~ ^[0-9]+$ ]] || {
    echo "capability-matrix: pinned result mismatch: $evidence_path" >&2
    return 1
  }
  local rate
  rate="$(awk -v pass="$passed" -v total="$total" 'BEGIN { printf "%.0f", pass * 100.0 / total }')"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$passed/$total (${rate}%; actual seed $actual_seed; result $manifest_result)" \
    "[VERIFIED](../$evidence_path)" "$commit_sha" "${timestamp%%T*}" \
    "$profile / $run_mode" "pinned run $run_id"
}

render() {
  if [[ ! -f "$MANIFEST" ]]; then
    echo "capability-matrix: manifest not found: $MANIFEST" >&2
    return 1
  fi
  validate_manifest || return 1
  validate_index || return 1

  cat <<'EOF'
# MinecraftAi Capability Matrix

This file is generated from `reports/capability_baseline_manifest.tsv` and `reports/baselines/index.tsv` via `scripts/capability_matrix.sh`.

Important: `reports/baselines/index.tsv` is the sole selector for VERIFIED single-run evidence of ordinary capabilities, taking priority over the legacy manifest; the generator never auto-selects the “best” or “most recent” result. Mining First explicitly forbids single-run pinning and must first pass the fixed 20-seed and sentinel-batch gate in `scripts/mining_release_gate.sh`. Until a qualifying aggregated-evidence format exists, both Mining First capabilities remain `MISSING`. The manifest retains historical source filenames and SHA-256 hashes for traceability, but the generator does not read or depend on these local reports; legacy data lacks a tested revision, config hash, and actual seed, so it is always `UNVERIFIED`.

| ID | Capability | Scenario | Result | Maturity | Evidence | Confidence | Tested Revision | Date | Mode | Fixture | Note |
|---|---|---|---:|---|---|---|---|---|---|---|---|
EOF

  local line_number=1
  local errors=0
  while IFS=$'\t' read -r capability_id display_name scenario legacy_result source_file source_sha256 maturity evidence_state confidence tested_revision run_date run_mode actual_seed_verified fixture note; do
    line_number=$((line_number + 1))
    if [[ "$capability_id" == "capability_id" || -z "$capability_id" ]]; then
      continue
    fi
    if [[ -z "${note:-}" ]]; then
      echo "capability-matrix: malformed manifest row $line_number" >&2
      errors=1
      continue
    fi

    local result="$legacy_result"
    local evidence="$evidence_state"
    local provenance="$tested_revision"
    local mode_display="$run_mode"
    local date_display="$run_date"
    local pinned_data pinned_status
    if pinned_data="$(pinned_baseline "$capability_id" "$scenario")"; then
      local pinned_note
      IFS=$'\t' read -r result evidence provenance date_display mode_display pinned_note <<< "$pinned_data"
      note="${note}; ${pinned_note}"
      actual_seed_verified=yes
    else
      pinned_status=$?
      if [[ $pinned_status -ne 2 ]]; then
        errors=1
        continue
      fi
    evidence="\`$evidence_state\` (legacy snapshot)"
    if [[ "$source_file" == "-" && "$evidence_state" == "MISSING" ]]; then
      provenance="no test record"
      mode_display="—"
      date_display="—"
      evidence='`MISSING`'
    else
      if [[ "$actual_seed_verified" != "yes" ]]; then
        provenance="$provenance; seed not read back"
      fi
      if [[ "$run_mode" == "legacy_unspecified" ]]; then
        mode_display="unknown (legacy)"
      fi
    fi
    fi

    printf '| `%s` | %s | `%s` | %s | `%s` | %s | `%s` | %s | %s | %s | %s | %s |\n' \
      "$(md_escape "$capability_id")" \
      "$(md_escape "$display_name")" \
      "$(md_escape "$scenario")" \
      "$(md_escape "$result")" \
      "$(md_escape "$maturity")" \
      "$evidence" \
      "$(md_escape "$confidence")" \
      "$(md_escape "$provenance")" \
      "$(md_escape "$date_display")" \
      "$(md_escape "$mode_display")" \
      "$(md_escape "$fixture")" \
      "$(md_escape "$note")"
  done < "$MANIFEST"

  cat <<'EOF'

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
EOF

  return "$errors"
}

mode="stdout"
target=""
if [[ $# -gt 0 ]]; then
  case "$1" in
    --output|--check)
      mode="${1#--}"
      if [[ $# -ne 2 ]]; then
        usage
        exit 2
      fi
      target="$2"
      ;;
    *)
      usage
      exit 2
      ;;
  esac
fi

if [[ "$mode" == "stdout" ]]; then
  render
  exit
fi

if [[ "$mode" == "output" ]]; then
  target_dir="$(dirname "$target")"
  mkdir -p "$target_dir"
  tmp="$(mktemp "$target_dir/.minecraftai-capability.XXXXXX")"
else
  tmp="$(mktemp "${TMPDIR:-/tmp}/minecraftai-capability.XXXXXX")"
fi
trap 'rm -f "$tmp"' EXIT
render > "$tmp"

if [[ "$mode" == "check" ]]; then
  if [[ ! -f "$target" ]]; then
    echo "capability-matrix: generated document missing: $target" >&2
    exit 1
  fi
  diff -u "$target" "$tmp"
  exit
fi

mv "$tmp" "$target"
trap - EXIT
