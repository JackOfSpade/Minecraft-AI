#!/usr/bin/env bash
# Build the AIBot mod and install it into a Minecraft launcher profile.
#
#   bash scripts/deploy_profile.sh [--profile-dir <dir>] [--no-build] [--no-config] [--check-key]
#
#   1. refuses to run while a Minecraft game client is running (the jar would be locked);
#   2. builds the mod jar (skipped with --no-build);
#   3. copies build/libs/Minecraft-AI-*.jar into <profile>/mods, replacing any older Minecraft-AI-*.jar
#      (and any older aibot-*.jar left over from before the jar was renamed);
#   4. applies the LLM settings from the gitignored .env to <profile>/config/aibot.json (the "llm"
#      section; the pre-rename "deepseek" section is migrated) and makes sure logging is enabled
#      (skipped with --no-config). The API key is never printed;
#   5. verifies the result and prints a summary. --check-key also asks the provider whether the key
#      is accepted (prints only the HTTP status).
#
# .env (gitignored; template: .env.example): AIBOT_LLM_API_KEY, AIBOT_LLM_BASE_URL, AIBOT_LLM_MODEL.
# The same variables in the process environment win over the file. The game never reads .env itself.
#
# Default profile: %APPDATA%/.minecraft/profiles/Minecraft-AI-1.21.11
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$ROOT"

die() { printf 'deploy: %s\n' "$*" >&2; exit 1; }

PROFILE_DIR=""
DO_BUILD=1
DO_CONFIG=1
CHECK_KEY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --profile-dir) [ $# -ge 2 ] || die "--profile-dir needs a value"; PROFILE_DIR="$2"; shift 2 ;;
    --no-build) DO_BUILD=0; shift ;;
    --no-config) DO_CONFIG=0; shift ;;
    --check-key) CHECK_KEY=1; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

if [ -z "$PROFILE_DIR" ]; then
  [ -n "${APPDATA:-}" ] || die "APPDATA is not set; pass --profile-dir"
  PROFILE_DIR="$(cygpath -u "$APPDATA")/.minecraft/profiles/Minecraft-AI-1.21.11"
fi
[ -d "$PROFILE_DIR" ] || die "profile directory not found: $PROFILE_DIR"
MODS_DIR="$PROFILE_DIR/mods"
CONFIG_DIR="$PROFILE_DIR/config"
[ -d "$MODS_DIR" ] || die "no mods directory in $PROFILE_DIR"

# ---- 1. never swap the jar under a running game
running="$(powershell.exe -NoProfile -Command '@(Get-CimInstance Win32_Process | Where-Object { @("javaw.exe","java.exe") -contains $_.Name -and $_.CommandLine -match "KnotClient|net.minecraft.client.main" }).Count' | tr -d '\r')"
[ "${running:-0}" = "0" ] || die "a Minecraft game client is running; close it first"

# ---- .env (parsed, never sourced: nothing in it is executed)
KEY=""; BASE=""; MODEL=""
ENV_FILE="$ROOT/.env"
if [ -f "$ENV_FILE" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in ''|'#'*) continue ;; esac
    name="${line%%=*}"
    value="${line#*=}"
    value="${value%\"}"; value="${value#\"}"; value="${value%\'}"; value="${value#\'}"
    case "$name" in
      AIBOT_LLM_API_KEY) KEY="$value" ;;
      AIBOT_LLM_BASE_URL) BASE="$value" ;;
      AIBOT_LLM_MODEL) MODEL="$value" ;;
    esac
  done < "$ENV_FILE"
fi
KEY="${AIBOT_LLM_API_KEY:-$KEY}"
BASE="${AIBOT_LLM_BASE_URL:-$BASE}"
MODEL="${AIBOT_LLM_MODEL:-$MODEL}"

# ---- 2. build
if [ "$DO_BUILD" = 1 ]; then
  if [ -z "${JAVA_HOME:-}" ]; then
    runtime="$(cygpath -u "${LOCALAPPDATA:-}")/Packages/Microsoft.4297127D64EC6_8wekyb3d8bbwe/LocalCache/Local/runtime/java-runtime-delta/windows-x64/java-runtime-delta"
    [ -x "$runtime/bin/java.exe" ] || die "JAVA_HOME is not set and the launcher's bundled JDK was not found"
    export JAVA_HOME="$(cygpath -w "$runtime")"
  fi
  export PATH="$(cygpath -u "$JAVA_HOME")/bin:$PATH"
  printf 'deploy: building...\n'
  # full Windows path: cmd.exe launched from Git Bash does not search the current directory
  GRADLEW_WIN="$(cygpath -w "$ROOT/gradlew.bat")"
  cmd.exe //c "$GRADLEW_WIN --no-daemon --console=plain clean build -x test -x runGameTest" >/dev/null \
    || die "build failed (run: cmd /c gradlew.bat build -x test -x runGameTest to see why)"
fi

# ---- 3. install the jar
JAR="$(ls -1 build/libs/Minecraft-AI-*.jar 2>/dev/null | grep -v -E -- '-(sources|dev)\.jar$' | head -n1 || true)"
[ -n "$JAR" ] && [ -f "$JAR" ] || die "no built jar in build/libs (build first)"
for old in "$MODS_DIR"/Minecraft-AI-*.jar "$MODS_DIR"/aibot-*.jar; do
  [ -e "$old" ] && rm -f -- "$old"
done
cp -- "$JAR" "$MODS_DIR/"
DEPLOYED="$MODS_DIR/$(basename "$JAR")"
[ "$(sha256sum < "$JAR")" = "$(sha256sum < "$DEPLOYED")" ] || die "deployed jar does not match the build"
printf 'deploy: installed %s (%s KB)\n' "$(basename "$JAR")" "$(( $(wc -c < "$JAR") / 1024 ))"

# ---- 4. LLM settings + logging in config/aibot.json (key only travels through the environment)
if [ "$DO_CONFIG" = 1 ]; then
  mkdir -p "$CONFIG_DIR"
  CONFIG_FILE="$CONFIG_DIR/aibot.json"
  [ -z "$KEY" ] && printf 'deploy: warning: no AIBOT_LLM_API_KEY in .env or the environment; the API key is left as it is\n'
  [ -f "$CONFIG_FILE" ] && cp -- "$CONFIG_FILE" "$CONFIG_FILE.bak"
  AIBOT_DEPLOY_CONFIG_PATH="$(cygpath -w "$CONFIG_FILE")" \
  AIBOT_LLM_API_KEY="$KEY" AIBOT_LLM_BASE_URL="$BASE" AIBOT_LLM_MODEL="$MODEL" \
  powershell.exe -NoProfile -Command '
    $ErrorActionPreference = "Stop"
    $path = $env:AIBOT_DEPLOY_CONFIG_PATH
    if (Test-Path -LiteralPath $path) { $j = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json }
    else { $j = [pscustomobject]@{ profile = "strict_survival" } }
    if ($null -eq $j) { throw "aibot.json is empty" }
    $names = @($j.PSObject.Properties.Name)
    if ($names -contains "llm") { $llm = $j.llm }
    elseif ($names -contains "deepseek") { $llm = $j.deepseek }
    else { $llm = [pscustomobject]@{} }
    foreach ($pair in @(@("apiKey", $env:AIBOT_LLM_API_KEY), @("baseUrl", $env:AIBOT_LLM_BASE_URL), @("model", $env:AIBOT_LLM_MODEL))) {
      if (-not [string]::IsNullOrWhiteSpace($pair[1])) { $llm | Add-Member -NotePropertyName $pair[0] -NotePropertyValue $pair[1] -Force }
    }
    if ($names -contains "deepseek") { $j.PSObject.Properties.Remove("deepseek") }
    $j | Add-Member -NotePropertyName "llm" -NotePropertyValue $llm -Force
    $log = "logging: absent (the mod defaults to enabled)"
    if ($names -contains "logging" -and $null -ne $j.logging) {
      if ($j.logging.enabled -ne $true) { $j.logging.enabled = $true; $log = "logging: was disabled, now enabled" }
      else { $log = "logging: enabled" }
    }
    [IO.File]::WriteAllText($path, ($j | ConvertTo-Json -Depth 20), (New-Object Text.UTF8Encoding($false)))
    $log
  ' | tr -d '\r' | sed 's/^/deploy: /'

  # ---- 5. verify, without ever printing the key
  AIBOT_DEPLOY_CONFIG_PATH="$(cygpath -w "$CONFIG_FILE")" powershell.exe -NoProfile -Command '
    $j = Get-Content -LiteralPath $env:AIBOT_DEPLOY_CONFIG_PATH -Raw | ConvertFrom-Json
    $l = $j.llm
    $host_ = ""; try { $host_ = ([uri]$l.baseUrl).Host } catch { $host_ = "(invalid url)" }
    $key = if ([string]::IsNullOrEmpty($l.apiKey)) { "NOT SET" } else { "set (" + $l.apiKey.Length + " chars)" }
    "config: profile=" + $j.profile + " | llm host=" + $host_ + " model=" + $l.model + " apiKey=" + $key
    "config: legacy deepseek section present: " + ($j.PSObject.Properties.Name -contains "deepseek")
  ' | tr -d '\r' | sed 's/^/deploy: /'
fi

if [ "$CHECK_KEY" = 1 ]; then
  [ -n "$KEY" ] || die "--check-key needs an API key"
  case "$BASE" in
    *generativelanguage.googleapis.com*)
      # GET one model: 200 = key accepted AND the model exists; 404 = unknown model; 400/403 = key problem
      url="https://generativelanguage.googleapis.com/v1beta/models/${MODEL:-gemini-3.5-flash-lite}"
      header="x-goog-api-key: $KEY" ;;
    *)
      url="${BASE%/}/v1/models"; header="Authorization: Bearer $KEY" ;;
  esac
  # the header travels on stdin, so the key never appears in a process list
  code="$(printf 'header = "%s"\n' "$header" | curl -sS -m 20 -o /dev/null -w '%{http_code}' -K - "$url" 2>/dev/null || true)"
  printf 'deploy: key check against %s -> HTTP %s (200 = accepted)\n' "$(printf '%s' "$url" | sed -E 's|https?://([^/]+).*|\1|')" "${code:-000}"
fi
printf 'deploy: done. Profile: %s\n' "$PROFILE_DIR"
