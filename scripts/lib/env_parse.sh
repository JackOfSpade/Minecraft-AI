#!/usr/bin/env bash
# Shared .env parser for scripts/deploy_profile.sh, extracted so its behaviour can be exercised in
# isolation (against a throwaway .env in a temp dir) without touching the real profile or the
# repo-root .env. This file is sourced by scripts in ../; it does not execute a run by itself.

# deploy_profile_parse_env <path-to-env-file>
#   Reads MINECRAFTAI_LLM_API_KEY / MINECRAFTAI_LLM_BASE_URL / MINECRAFTAI_LLM_MODEL (current names)
#   from the given file, falling back for the key to the published pre-rename name
#   DEEPSEEK_API_KEY, then per-variable to the interim AIBOT_LLM_API_KEY /
#   AIBOT_LLM_BASE_URL / AIBOT_LLM_MODEL names when a current name is absent. The same variables
#   in the process environment win over the file, and a current name always wins over a legacy
#   name at the same level (env-vs-env, file-vs-file). Overall precedence, highest first:
#     1. MINECRAFTAI_LLM_* in the process environment
#     2. MINECRAFTAI_LLM_* in the file
#     3. DEEPSEEK_API_KEY in the process environment
#     4. DEEPSEEK_API_KEY in the file
#     5. AIBOT_LLM_* in the process environment
#     6. AIBOT_LLM_* in the file
#   Sets the caller's KEY / BASE / MODEL. Also sets DEPLOY_ENV_LEGACY_NAMES_USED to a comma-separated
#   list of legacy names (names only, never values) that actually supplied a resolved value, empty
#   when none did, so the caller can print a single warning line.
deploy_profile_parse_env() {
  local env_file="$1"
  local cur_key="" cur_base="" cur_model=""
  local deepseek_key="" aibot_key="" aibot_base="" aibot_model=""

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
        DEEPSEEK_API_KEY) deepseek_key="$value" ;;
        AIBOT_LLM_API_KEY) aibot_key="$value" ;;
        AIBOT_LLM_BASE_URL) aibot_base="$value" ;;
        AIBOT_LLM_MODEL) aibot_model="$value" ;;
      esac
    done < "$env_file"
  fi

  KEY="${MINECRAFTAI_LLM_API_KEY:-${cur_key:-${DEEPSEEK_API_KEY:-${deepseek_key:-${AIBOT_LLM_API_KEY:-${aibot_key:-}}}}}}"
  BASE="${MINECRAFTAI_LLM_BASE_URL:-${cur_base:-${AIBOT_LLM_BASE_URL:-${aibot_base:-}}}}"
  MODEL="${MINECRAFTAI_LLM_MODEL:-${cur_model:-${AIBOT_LLM_MODEL:-${aibot_model:-}}}}"

  DEPLOY_ENV_LEGACY_NAMES_USED=""
  if [ -z "${MINECRAFTAI_LLM_API_KEY:-}" ] && [ -z "$cur_key" ] \
      && { [ -n "${DEEPSEEK_API_KEY:-}" ] || [ -n "$deepseek_key" ]; }; then
    DEPLOY_ENV_LEGACY_NAMES_USED="${DEPLOY_ENV_LEGACY_NAMES_USED}DEEPSEEK_API_KEY,"
  fi
  if [ -z "${MINECRAFTAI_LLM_API_KEY:-}" ] && [ -z "$cur_key" ] \
      && [ -z "${DEEPSEEK_API_KEY:-}" ] && [ -z "$deepseek_key" ] \
      && { [ -n "${AIBOT_LLM_API_KEY:-}" ] || [ -n "$aibot_key" ]; }; then
    DEPLOY_ENV_LEGACY_NAMES_USED="${DEPLOY_ENV_LEGACY_NAMES_USED}AIBOT_LLM_API_KEY,"
  fi
  if [ -z "${MINECRAFTAI_LLM_BASE_URL:-}" ] && [ -z "$cur_base" ] \
      && { [ -n "${AIBOT_LLM_BASE_URL:-}" ] || [ -n "$aibot_base" ]; }; then
    DEPLOY_ENV_LEGACY_NAMES_USED="${DEPLOY_ENV_LEGACY_NAMES_USED}AIBOT_LLM_BASE_URL,"
  fi
  if [ -z "${MINECRAFTAI_LLM_MODEL:-}" ] && [ -z "$cur_model" ] \
      && { [ -n "${AIBOT_LLM_MODEL:-}" ] || [ -n "$aibot_model" ]; }; then
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
          DEEPSEEK_API_KEY AIBOT_LLM_API_KEY AIBOT_LLM_BASE_URL AIBOT_LLM_MODEL
    deploy_profile_parse_env "$f1"
    check "current names only (file)" cur-key https://cur cur-model ""
  )

  # 2. Only the interim pre-rename names, in the file: falls back for all three, with a warning
  #    note naming exactly those three (values never appear in the note).
  f2="$tmp/legacy-only.env"
  printf 'AIBOT_LLM_API_KEY=leg-key\nAIBOT_LLM_BASE_URL=https://legacy\nAIBOT_LLM_MODEL=leg-model\n' > "$f2"
  (
    unset MINECRAFTAI_LLM_API_KEY MINECRAFTAI_LLM_BASE_URL MINECRAFTAI_LLM_MODEL \
          DEEPSEEK_API_KEY AIBOT_LLM_API_KEY AIBOT_LLM_BASE_URL AIBOT_LLM_MODEL
    deploy_profile_parse_env "$f2"
    check "legacy names only (file)" leg-key https://legacy leg-model \
      "AIBOT_LLM_API_KEY,AIBOT_LLM_BASE_URL,AIBOT_LLM_MODEL"
  )

  # 3. Both published legacy key forms and current names in the file: current wins for every
  #    variable, with no legacy note.
  f3="$tmp/both.env"
  printf 'MINECRAFTAI_LLM_API_KEY=cur-key\nDEEPSEEK_API_KEY=deep-key\nAIBOT_LLM_API_KEY=leg-key\nMINECRAFTAI_LLM_BASE_URL=https://cur\nAIBOT_LLM_BASE_URL=https://legacy\nMINECRAFTAI_LLM_MODEL=cur-model\nAIBOT_LLM_MODEL=leg-model\n' > "$f3"
  (
    unset MINECRAFTAI_LLM_API_KEY MINECRAFTAI_LLM_BASE_URL MINECRAFTAI_LLM_MODEL \
          DEEPSEEK_API_KEY AIBOT_LLM_API_KEY AIBOT_LLM_BASE_URL AIBOT_LLM_MODEL
    deploy_profile_parse_env "$f3"
    check "current wins over legacy (file)" cur-key https://cur cur-model ""
  )

  # 4. The old public DeepSeek key is migrated from the file, including quoted values.
  f4="$tmp/deepseek-quoted.env"
  printf 'DEEPSEEK_API_KEY="quoted-key"\n' > "$f4"
  (
    unset MINECRAFTAI_LLM_API_KEY MINECRAFTAI_LLM_BASE_URL MINECRAFTAI_LLM_MODEL \
          DEEPSEEK_API_KEY AIBOT_LLM_API_KEY AIBOT_LLM_BASE_URL AIBOT_LLM_MODEL
    deploy_profile_parse_env "$f4"
    check "DeepSeek legacy value unquoted" quoted-key "" "" "DEEPSEEK_API_KEY"
  )

  # 5. AIBOT's quoted fallback remains supported for profiles created during the interim rename.
  f5="$tmp/aibot-quoted.env"
  printf 'AIBOT_LLM_API_KEY="quoted-key"\n' > "$f5"
  (
    unset MINECRAFTAI_LLM_API_KEY MINECRAFTAI_LLM_BASE_URL MINECRAFTAI_LLM_MODEL \
          DEEPSEEK_API_KEY AIBOT_LLM_API_KEY AIBOT_LLM_BASE_URL AIBOT_LLM_MODEL
    deploy_profile_parse_env "$f5"
    check "AIBOT legacy value unquoted" quoted-key "" "" "AIBOT_LLM_API_KEY"
  )

  # 6. A current process name wins over a legacy file name per variable; untouched variables
  #    still fall back to the file's interim names.
  f6="$tmp/mixed.env"
  printf 'AIBOT_LLM_API_KEY=leg-key\nAIBOT_LLM_BASE_URL=https://legacy\nAIBOT_LLM_MODEL=leg-model\n' > "$f6"
  (
    unset MINECRAFTAI_LLM_BASE_URL MINECRAFTAI_LLM_MODEL DEEPSEEK_API_KEY \
          AIBOT_LLM_API_KEY AIBOT_LLM_BASE_URL AIBOT_LLM_MODEL
    export MINECRAFTAI_LLM_API_KEY=env-key
    deploy_profile_parse_env "$f6"
    check "current env wins for one variable, others still fall back" env-key https://legacy leg-model \
      "AIBOT_LLM_BASE_URL,AIBOT_LLM_MODEL"
  )

  # 7. The current file name still beats the old public environment name. This locks the documented
  #    provider-neutral migration rule across sources, not merely within one file.
  (
    unset MINECRAFTAI_LLM_API_KEY MINECRAFTAI_LLM_BASE_URL MINECRAFTAI_LLM_MODEL \
          AIBOT_LLM_API_KEY AIBOT_LLM_BASE_URL AIBOT_LLM_MODEL
    export DEEPSEEK_API_KEY=env-deepseek-key
    deploy_profile_parse_env "$f1"
    check "current file name beats DeepSeek environment fallback" cur-key https://cur cur-model ""
  )

  # 8. The published DeepSeek file name beats an interim AIBOT environment value, matching the
  #    full precedence order rather than accidentally giving every environment legacy name priority.
  f8="$tmp/deepseek-file-vs-aibot-env.env"
  printf 'DEEPSEEK_API_KEY=file-deepseek-key\n' > "$f8"
  (
    unset MINECRAFTAI_LLM_API_KEY MINECRAFTAI_LLM_BASE_URL MINECRAFTAI_LLM_MODEL \
          DEEPSEEK_API_KEY AIBOT_LLM_BASE_URL AIBOT_LLM_MODEL
    export AIBOT_LLM_API_KEY=env-aibot-key
    deploy_profile_parse_env "$f8"
    check "DeepSeek file beats AIBOT environment fallback" file-deepseek-key "" "" "DEEPSEEK_API_KEY"
  )

  # 9. Conversely, the published DeepSeek environment name beats an interim AIBOT file value.
  f9="$tmp/deepseek-env-vs-aibot-file.env"
  printf 'AIBOT_LLM_API_KEY=file-aibot-key\n' > "$f9"
  (
    unset MINECRAFTAI_LLM_API_KEY MINECRAFTAI_LLM_BASE_URL MINECRAFTAI_LLM_MODEL \
          AIBOT_LLM_API_KEY AIBOT_LLM_BASE_URL AIBOT_LLM_MODEL
    export DEEPSEEK_API_KEY=env-deepseek-key
    deploy_profile_parse_env "$f9"
    check "DeepSeek environment beats AIBOT file fallback" env-deepseek-key "" "" "DEEPSEEK_API_KEY"
  )

  # 10. The old public key in the process environment is used and noted when nothing else supplies it.
  (
    unset MINECRAFTAI_LLM_API_KEY MINECRAFTAI_LLM_BASE_URL MINECRAFTAI_LLM_MODEL \
          AIBOT_LLM_API_KEY AIBOT_LLM_BASE_URL AIBOT_LLM_MODEL
    export DEEPSEEK_API_KEY=env-deepseek-key
    deploy_profile_parse_env "$tmp/nonexistent.env"
    check "DeepSeek env var, no file" env-deepseek-key "" "" "DEEPSEEK_API_KEY"
  )

  # 11. The interim legacy name in the process environment remains a final fallback.
  (
    unset MINECRAFTAI_LLM_API_KEY MINECRAFTAI_LLM_BASE_URL MINECRAFTAI_LLM_MODEL \
          DEEPSEEK_API_KEY AIBOT_LLM_BASE_URL AIBOT_LLM_MODEL
    export AIBOT_LLM_API_KEY=env-legacy-key
    deploy_profile_parse_env "$tmp/nonexistent.env"
    check "AIBOT env var, no file" env-legacy-key "" "" "AIBOT_LLM_API_KEY"
  )

  # 12. Nothing set anywhere: everything empty, no legacy note.
  (
    unset MINECRAFTAI_LLM_API_KEY MINECRAFTAI_LLM_BASE_URL MINECRAFTAI_LLM_MODEL \
          DEEPSEEK_API_KEY AIBOT_LLM_API_KEY AIBOT_LLM_BASE_URL AIBOT_LLM_MODEL
    deploy_profile_parse_env "$tmp/nonexistent.env"
    check "nothing set" "" "" "" ""
  )

  printf '[env-parse] self-test PASS\n'
fi
