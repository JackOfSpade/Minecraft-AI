#!/usr/bin/env bash
# Shared .env parser for scripts/deploy_profile.sh, extracted so its behaviour can be exercised in
# isolation (against a throwaway .env in a temp dir) without touching the real profile or the
# repo-root .env. This file is sourced by scripts in ../; it does not execute a run by itself.

# deploy_profile_parse_env <path-to-env-file>
#   Reads MINECRAFTAI_LLM_API_KEY / MINECRAFTAI_LLM_BASE_URL / MINECRAFTAI_LLM_MODEL (current names)
#   from the given file, falling back per-variable to the pre-rename interim names
#   AIBOT_LLM_API_KEY / AIBOT_LLM_BASE_URL / AIBOT_LLM_MODEL when a current name is absent. The same
#   variables in the process environment win over the file, and a current name always wins over a
#   legacy name at the same level (env-vs-env, file-vs-file). Overall precedence, highest first:
#     1. MINECRAFTAI_LLM_* in the process environment
#     2. MINECRAFTAI_LLM_* in the file
#     3. AIBOT_LLM_* in the process environment
#     4. AIBOT_LLM_* in the file
#   Sets the caller's KEY / BASE / MODEL. Also sets DEPLOY_ENV_LEGACY_NAMES_USED to a comma-separated
#   list of the legacy AIBOT_LLM_* names (names only, never values) that actually supplied a
#   resolved value, empty when none did, so the caller can print a single warning line.
deploy_profile_parse_env() {
  local env_file="$1"
  local cur_key="" cur_base="" cur_model=""
  local leg_key="" leg_base="" leg_model=""

  if [ -f "$env_file" ]; then
    local line name value
    while IFS= read -r line || [ -n "$line" ]; do
      line="${line%$'\r'}"
      case "$line" in ''|'#'*) continue ;; esac
      name="${line%%=*}"
      value="${line#*=}"
      value="${value%\"}"; value="${value#\"}"; value="${value%\'}"; value="${value#\'}"
      case "$name" in
        MINECRAFTAI_LLM_API_KEY) cur_key="$value" ;;
        MINECRAFTAI_LLM_BASE_URL) cur_base="$value" ;;
        MINECRAFTAI_LLM_MODEL) cur_model="$value" ;;
        AIBOT_LLM_API_KEY) leg_key="$value" ;;
        AIBOT_LLM_BASE_URL) leg_base="$value" ;;
        AIBOT_LLM_MODEL) leg_model="$value" ;;
      esac
    done < "$env_file"
  fi

  KEY="${MINECRAFTAI_LLM_API_KEY:-${cur_key:-${AIBOT_LLM_API_KEY:-${leg_key:-}}}}"
  BASE="${MINECRAFTAI_LLM_BASE_URL:-${cur_base:-${AIBOT_LLM_BASE_URL:-${leg_base:-}}}}"
  MODEL="${MINECRAFTAI_LLM_MODEL:-${cur_model:-${AIBOT_LLM_MODEL:-${leg_model:-}}}}"

  DEPLOY_ENV_LEGACY_NAMES_USED=""
  if [ -z "${MINECRAFTAI_LLM_API_KEY:-}" ] && [ -z "$cur_key" ] \
      && { [ -n "${AIBOT_LLM_API_KEY:-}" ] || [ -n "$leg_key" ]; }; then
    DEPLOY_ENV_LEGACY_NAMES_USED="${DEPLOY_ENV_LEGACY_NAMES_USED}AIBOT_LLM_API_KEY,"
  fi
  if [ -z "${MINECRAFTAI_LLM_BASE_URL:-}" ] && [ -z "$cur_base" ] \
      && { [ -n "${AIBOT_LLM_BASE_URL:-}" ] || [ -n "$leg_base" ]; }; then
    DEPLOY_ENV_LEGACY_NAMES_USED="${DEPLOY_ENV_LEGACY_NAMES_USED}AIBOT_LLM_BASE_URL,"
  fi
  if [ -z "${MINECRAFTAI_LLM_MODEL:-}" ] && [ -z "$cur_model" ] \
      && { [ -n "${AIBOT_LLM_MODEL:-}" ] || [ -n "$leg_model" ]; }; then
    DEPLOY_ENV_LEGACY_NAMES_USED="${DEPLOY_ENV_LEGACY_NAMES_USED}AIBOT_LLM_MODEL,"
  fi
  DEPLOY_ENV_LEGACY_NAMES_USED="${DEPLOY_ENV_LEGACY_NAMES_USED%,}"
}

# Self-test: exercises deploy_profile_parse_env against throwaway .env files in a temp dir. Never
# touches the real repo-root .env or a Minecraft profile.
#   bash scripts/lib/env_parse.sh --self-test
if [[ "${BASH_SOURCE[0]}" == "${0:-}" && "${1:-}" == --self-test ]]; then
  set -euo pipefail

  fail() { printf '[env-parse] self-test FAIL: %s\n' "$1" >&2; exit 1; }

  check() {
    local desc="$1" want_key="$2" want_base="$3" want_model="$4" want_legacy="$5"
    [[ "$KEY" == "$want_key" ]] || fail "$desc: KEY='$KEY' want '$want_key'"
    [[ "$BASE" == "$want_base" ]] || fail "$desc: BASE='$BASE' want '$want_base'"
    [[ "$MODEL" == "$want_model" ]] || fail "$desc: MODEL='$MODEL' want '$want_model'"
    [[ "$DEPLOY_ENV_LEGACY_NAMES_USED" == "$want_legacy" ]] \
      || fail "$desc: legacy names used='$DEPLOY_ENV_LEGACY_NAMES_USED' want '$want_legacy'"
  }

  tmp="$(mktemp -d "${TMPDIR:-/tmp}/minecraftai-env-parse-self-test.XXXXXX")"
  trap 'rm -rf -- "$tmp"' EXIT

  # 1. Only the current names, in the file: used as-is, no legacy note.
  f1="$tmp/current-only.env"
  printf 'MINECRAFTAI_LLM_API_KEY=cur-key\nMINECRAFTAI_LLM_BASE_URL=https://cur\nMINECRAFTAI_LLM_MODEL=cur-model\n' > "$f1"
  (
    unset MINECRAFTAI_LLM_API_KEY MINECRAFTAI_LLM_BASE_URL MINECRAFTAI_LLM_MODEL \
          AIBOT_LLM_API_KEY AIBOT_LLM_BASE_URL AIBOT_LLM_MODEL
    deploy_profile_parse_env "$f1"
    check "current names only (file)" cur-key https://cur cur-model ""
  )

  # 2. Only the pre-rename legacy names, in the file: falls back for all three, with a warning note
  #    naming exactly those three (values never appear in the note).
  f2="$tmp/legacy-only.env"
  printf 'AIBOT_LLM_API_KEY=leg-key\nAIBOT_LLM_BASE_URL=https://legacy\nAIBOT_LLM_MODEL=leg-model\n' > "$f2"
  (
    unset MINECRAFTAI_LLM_API_KEY MINECRAFTAI_LLM_BASE_URL MINECRAFTAI_LLM_MODEL \
          AIBOT_LLM_API_KEY AIBOT_LLM_BASE_URL AIBOT_LLM_MODEL
    deploy_profile_parse_env "$f2"
    check "legacy names only (file)" leg-key https://legacy leg-model \
      "AIBOT_LLM_API_KEY,AIBOT_LLM_BASE_URL,AIBOT_LLM_MODEL"
  )

  # 3. Both present in the file: the current name wins for every variable, no legacy note.
  f3="$tmp/both.env"
  printf 'MINECRAFTAI_LLM_API_KEY=cur-key\nAIBOT_LLM_API_KEY=leg-key\nMINECRAFTAI_LLM_BASE_URL=https://cur\nAIBOT_LLM_BASE_URL=https://legacy\nMINECRAFTAI_LLM_MODEL=cur-model\nAIBOT_LLM_MODEL=leg-model\n' > "$f3"
  (
    unset MINECRAFTAI_LLM_API_KEY MINECRAFTAI_LLM_BASE_URL MINECRAFTAI_LLM_MODEL \
          AIBOT_LLM_API_KEY AIBOT_LLM_BASE_URL AIBOT_LLM_MODEL
    deploy_profile_parse_env "$f3"
    check "current wins over legacy (file)" cur-key https://cur cur-model ""
  )

  # 4. Legacy value quoted in the file: the existing quote-stripping applies to legacy names too.
  f4="$tmp/legacy-quoted.env"
  printf 'AIBOT_LLM_API_KEY="quoted-key"\n' > "$f4"
  (
    unset MINECRAFTAI_LLM_API_KEY MINECRAFTAI_LLM_BASE_URL MINECRAFTAI_LLM_MODEL \
          AIBOT_LLM_API_KEY AIBOT_LLM_BASE_URL AIBOT_LLM_MODEL
    deploy_profile_parse_env "$f4"
    check "legacy value unquoted" quoted-key "" "" "AIBOT_LLM_API_KEY"
  )

  # 5. Current name in the process environment wins over a legacy name in the file (per variable);
  #    the untouched variables still fall back to the file's legacy names.
  f5="$tmp/mixed.env"
  printf 'AIBOT_LLM_API_KEY=leg-key\nAIBOT_LLM_BASE_URL=https://legacy\nAIBOT_LLM_MODEL=leg-model\n' > "$f5"
  (
    unset MINECRAFTAI_LLM_BASE_URL MINECRAFTAI_LLM_MODEL AIBOT_LLM_API_KEY AIBOT_LLM_BASE_URL AIBOT_LLM_MODEL
    export MINECRAFTAI_LLM_API_KEY=env-key
    deploy_profile_parse_env "$f5"
    check "current env wins for one variable, others still fall back" env-key https://legacy leg-model \
      "AIBOT_LLM_BASE_URL,AIBOT_LLM_MODEL"
  )

  # 6. Legacy name in the process environment is used (and noted) when nothing else supplies a value.
  (
    unset MINECRAFTAI_LLM_API_KEY MINECRAFTAI_LLM_BASE_URL MINECRAFTAI_LLM_MODEL \
          AIBOT_LLM_BASE_URL AIBOT_LLM_MODEL
    export AIBOT_LLM_API_KEY=env-legacy-key
    deploy_profile_parse_env "$tmp/nonexistent.env"
    check "legacy env var, no file" env-legacy-key "" "" "AIBOT_LLM_API_KEY"
  )

  # 7. Nothing set anywhere: everything empty, no legacy note.
  (
    unset MINECRAFTAI_LLM_API_KEY MINECRAFTAI_LLM_BASE_URL MINECRAFTAI_LLM_MODEL \
          AIBOT_LLM_API_KEY AIBOT_LLM_BASE_URL AIBOT_LLM_MODEL
    deploy_profile_parse_env "$tmp/nonexistent.env"
    check "nothing set" "" "" "" ""
  )

  printf '[env-parse] self-test PASS\n'
fi
