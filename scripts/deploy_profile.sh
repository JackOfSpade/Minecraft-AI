#!/usr/bin/env bash
# Build both Minecraft-AI mods and install them into a Minecraft launcher profile.
#
#   bash scripts/deploy_profile.sh [--profile-dir <dir>] [--no-build] [--no-config] [--check-key]
#   bash scripts/deploy_profile.sh --self-test-transaction
#
#   1. refuses to run while a Minecraft game client is running (the jar would be locked);
#   2. builds the Minecraft-AI and PvP BOT wrapper jars (skipped with --no-build);
#   3. stages, validates, and atomically applies the LLM settings from the gitignored .env to
#      <profile>/config/minecraftai.json (the "llm"
#      section; the pre-rename "deepseek" section is migrated), pins strict-survival Baritone navigation,
#      and makes sure logging is enabled
#      (skipped with --no-config). The API key is never printed;
#   4. transactionally installs both owned jars into <profile>/mods, restoring the prior owned set
#      if either publish step fails;
#   5. verifies the result and prints a summary. --check-key also asks the provider whether the key
#      is accepted (prints only the HTTP status).
#
# .env (gitignored; template: .env.example): MINECRAFTAI_LLM_API_KEY, MINECRAFTAI_LLM_BASE_URL, MINECRAFTAI_LLM_MODEL.
# The same variables in the process environment win over the file. DEEPSEEK_API_KEY remains an
# API-key fallback for profiles made before the provider-neutral rename, and the pre-rename
# interim AIBOT_LLM_API_KEY / AIBOT_LLM_BASE_URL / AIBOT_LLM_MODEL names are accepted per-variable
# after it (see scripts/lib/env_parse.sh). The game never reads .env itself.
#
# Default profile: %APPDATA%/.minecraft/profiles/Minecraft-AI-1.21.11
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$ROOT"
# shellcheck source=lib/env_parse.sh
source "$ROOT/scripts/lib/env_parse.sh"

die() { printf 'deploy: %s\n' "$*" >&2; exit 1; }

WRAPPER_ROOT="$ROOT/Minecraft-Spawn-Bots-Wrapper"
[ -d "$WRAPPER_ROOT" ] || die "missing bundled wrapper project: $WRAPPER_ROOT"

# These are populated only while the owned-mod deployment transaction is live.  Keeping its
# staging directory beside (but outside) mods means Fabric can never see a partial jar if the
# process is interrupted half-way through a copy.
OWNED_MOD_TX_DIR=""
OWNED_MOD_TX_ACTIVE=0
OWNED_MOD_TX_COMMITTED=0
OWNED_MOD_TX_PUBLISHED_TARGETS=()
# Test-only state is initialized internally and is honored only by --self-test-transaction. It
# cannot make a normal profile deployment take a synthetic failure path.
OWNED_MOD_TX_SELF_TEST_FAIL_PUBLISH_LABEL=""
OWNED_MOD_TX_SELF_TEST_SUCCESS_MARKER=""

PROFILE_DIR=""
DO_BUILD=1
DO_CONFIG=1
CHECK_KEY=0
SELF_TEST_TRANSACTION=0
SELF_TEST_PROFILE_DIR=""
while [ $# -gt 0 ]; do
  case "$1" in
    --profile-dir) [ $# -ge 2 ] || die "--profile-dir needs a value"; PROFILE_DIR="$2"; shift 2 ;;
    --no-build) DO_BUILD=0; shift ;;
    --no-config) DO_CONFIG=0; shift ;;
    --check-key) CHECK_KEY=1; shift ;;
    --self-test-transaction) SELF_TEST_TRANSACTION=1; DO_BUILD=0; DO_CONFIG=0; shift ;;
    -h|--help) sed -n '2,23p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

if [ "$SELF_TEST_TRANSACTION" = 1 ]; then
  SELF_TEST_PROFILE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/minecraftai-deploy-transaction-self-test.XXXXXXXX")" \
    || die "could not create deploy transaction self-test directory"
  PROFILE_DIR="$SELF_TEST_PROFILE_DIR"
  mkdir -p -- "$PROFILE_DIR/mods" || die "could not prepare deploy transaction self-test profile"
elif [ -z "$PROFILE_DIR" ]; then
  [ -n "${APPDATA:-}" ] || die "APPDATA is not set; pass --profile-dir"
  PROFILE_DIR="$(cygpath -u "$APPDATA")/.minecraft/profiles/Minecraft-AI-1.21.11"
fi
[ -d "$PROFILE_DIR" ] || die "profile directory not found: $PROFILE_DIR"
MODS_DIR="$PROFILE_DIR/mods"
CONFIG_DIR="$PROFILE_DIR/config"
[ -d "$MODS_DIR" ] || die "no mods directory in $PROFILE_DIR"

# ---- 1. never swap the jar under a running game
if [ "$SELF_TEST_TRANSACTION" = 0 ]; then
  running="$(powershell.exe -NoProfile -Command '@(Get-CimInstance Win32_Process | Where-Object { @("javaw.exe","java.exe") -contains $_.Name -and $_.CommandLine -match "KnotClient|net.minecraft.client.main" }).Count' | tr -d '\r')"
  [ "${running:-0}" = "0" ] || die "a Minecraft game client is running; close it first"

  # ---- .env (parsed, never sourced: nothing in it is executed)
  ENV_FILE="$ROOT/.env"
  deploy_profile_parse_env "$ENV_FILE"
  if [ -n "$DEPLOY_ENV_LEGACY_NAMES_USED" ]; then
    printf 'deploy: warning: using legacy .env/environment name(s) (rename to MINECRAFTAI_LLM_* when convenient): %s\n' \
      "$DEPLOY_ENV_LEGACY_NAMES_USED"
  fi
fi

# ---- 2. build
if [ "$DO_BUILD" = 1 ]; then
  if [ -z "${JAVA_HOME:-}" ]; then
    runtime="$(cygpath -u "${LOCALAPPDATA:-}")/Packages/Microsoft.4297127D64EC6_8wekyb3d8bbwe/LocalCache/Local/runtime/java-runtime-delta/windows-x64/java-runtime-delta"
    [ -x "$runtime/bin/java.exe" ] || die "JAVA_HOME is not set and the launcher's bundled JDK was not found"
    export JAVA_HOME="$(cygpath -w "$runtime")"
  fi
  export PATH="$(cygpath -u "$JAVA_HOME")/bin:$PATH"
  printf 'deploy: building Minecraft-AI...\n'
  # full Windows path: cmd.exe launched from Git Bash does not search the current directory
  GRADLEW_WIN="$(cygpath -w "$ROOT/gradlew.bat")"
  cmd.exe //c "$GRADLEW_WIN --no-daemon --console=plain clean build -x test -x runGameTest" >/dev/null \
    || die "build failed (run: cmd /c gradlew.bat build -x test -x runGameTest to see why)"
  printf 'deploy: building PvP BOT wrapper...\n'
  WRAPPER_ROOT_WIN="$(cygpath -w "$WRAPPER_ROOT")"
  # Gradle derives its project from the working directory, not the wrapper batch-file path.
  # Switch cmd.exe into the wrapper first so this cannot silently rebuild the root project.
  cmd.exe //c "cd /d \"$WRAPPER_ROOT_WIN\" && gradlew.bat --no-daemon --console=plain clean build -x test -x runGameTest" >/dev/null \
    || die "wrapper build failed (run: cmd /c \"cd /d Minecraft-Spawn-Bots-Wrapper && gradlew.bat build -x test -x runGameTest\" to see why)"
fi

# ---- 3. install the jars
release_jar() {
  local directory="$1" prefix="$2" candidate
  local matches=()
  for candidate in "$directory"/"$prefix"-*.jar; do
    [ -f "$candidate" ] || continue
    case "$candidate" in
      *-sources.jar|*-dev.jar) continue ;;
    esac
    matches+=("$candidate")
  done
  [ "${#matches[@]}" -eq 1 ] || die "expected exactly one release jar matching $directory/$prefix-*.jar (build first or remove stale artifacts)"
  printf '%s\n' "${matches[0]}"
}

profile_has_fabric_mod() {
  local required_id="$1"
  # File names are not a dependency contract: inspect each profile jar's Fabric manifest and
  # accept only the exact mod id. A corrupt or non-Fabric jar is irrelevant to this check.
  MINECRAFTAI_DEPLOY_MODS_DIR="$(cygpath -w "$MODS_DIR")" \
  MINECRAFTAI_DEPLOY_REQUIRED_MOD_ID="$required_id" \
  powershell.exe -NoProfile -Command '
    $mods = $env:MINECRAFTAI_DEPLOY_MODS_DIR
    $required = $env:MINECRAFTAI_DEPLOY_REQUIRED_MOD_ID
    $found = $false
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
    Get-ChildItem -LiteralPath $mods -Filter "*.jar" -File -ErrorAction Stop | ForEach-Object {
      $zip = $null
      try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($_.FullName)
        $entry = $zip.GetEntry("fabric.mod.json")
        if ($null -eq $entry) { return }
        $reader = [System.IO.StreamReader]::new($entry.Open(), [System.Text.Encoding]::UTF8, $true)
        try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json -ErrorAction Stop }
        finally { $reader.Dispose() }
        if ($manifest.id -eq $required) { $found = $true }
      } catch {
        # A bad third-party jar cannot satisfy a required Fabric dependency.
      } finally {
        if ($null -ne $zip) { $zip.Dispose() }
      }
    }
    if ($found) { exit 0 }
    exit 1
  '
}

require_profile_fabric_mod() {
  local required_id="$1" display_name="$2"
  profile_has_fabric_mod "$required_id" || die "required upstream Fabric mod '$display_name' (id '$required_id') is not installed in $MODS_DIR; install it before deploying pvpbot-inhabitants"
}

unmanaged_owned_mod_jars() {
  # The filename patterns below are the same fixed ownership boundary used by the transaction
  # backup. A renamed copy of one of our mods would otherwise survive that backup and Fabric would
  # reject the profile for loading a duplicate mod id after the new jar is published.
  MINECRAFTAI_DEPLOY_MODS_DIR="$(cygpath -w "$MODS_DIR")" \
  powershell.exe -NoProfile -Command '
    $ErrorActionPreference = "Stop"
    $mods = $env:MINECRAFTAI_DEPLOY_MODS_DIR
    $ownedPatterns = @{
      "minecraftai" = @("Minecraft-AI-*.jar", "minecraftai-*.jar")
      "pvpbot_inhabitants" = @("pvpbot-inhabitants-*.jar")
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
    Get-ChildItem -LiteralPath $mods -Filter "*.jar" -File -ErrorAction Stop |
      Sort-Object Name |
      ForEach-Object {
        $zip = $null
        try {
          $zip = [System.IO.Compression.ZipFile]::OpenRead($_.FullName)
          $entry = $zip.GetEntry("fabric.mod.json")
          if ($null -ne $entry) {
            $reader = [System.IO.StreamReader]::new($entry.Open(), [System.Text.Encoding]::UTF8, $true)
            try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json -ErrorAction Stop }
            finally { $reader.Dispose() }
            $id = [string]$manifest.id
            if ($ownedPatterns.ContainsKey($id)) {
              $managed = $false
              foreach ($pattern in $ownedPatterns[$id]) {
                # Use case-sensitive matching so the preflight never permits a name that the
                # fixed Bash backup glob would fail to reserve on a case-sensitive filesystem.
                if ($_.Name -clike $pattern) { $managed = $true; break }
              }
              if (-not $managed) { [Console]::Out.WriteLine($_.Name) }
            }
          }
        } catch {
          # An unreadable third-party jar cannot be classified as an unmanaged copy of our mod.
          # Fabric itself will report that separate profile problem on launch.
        } finally {
          if ($null -ne $zip) { $zip.Dispose() }
        }
      }
  ' | tr -d '\r'
}

require_no_unmanaged_owned_mod_jars() {
  local unmanaged
  if ! unmanaged="$(unmanaged_owned_mod_jars)"; then
    die "could not inspect profile mod manifests for unmanaged Minecraft-AI or pvpbot-inhabitants jars"
  fi
  if [ -n "$unmanaged" ]; then
    printf 'deploy: refusing unmanaged jar(s) that declare an owned Fabric mod id:\n%s\n' "$unmanaged" >&2
    die "back up, remove, or restore the normal managed filename for those jars before deploying"
  fi
}

same_sha256() {
  local first="$1" second="$2" first_hash second_hash
  first_hash="$(sha256sum < "$first")" || return 1
  second_hash="$(sha256sum < "$second")" || return 1
  [ "$first_hash" = "$second_hash" ]
}

move_owned_mods_to_backup() {
  local backup_dir="$1" pattern old backup
  shift
  mkdir -p -- "$backup_dir" || return 1
  for pattern in "$@"; do
    # The pattern is deliberately expanded here: it is an internal, fixed list of this project's
    # own artifact names, never profile input.
    for old in "$MODS_DIR"/$pattern; do
      [ -e "$old" ] || continue
      [ -f "$old" ] || {
        printf 'deploy: owned-mod path is not a regular file: %s\n' "$old" >&2
        return 1
      }
      backup="$backup_dir/${old##*/}"
      [ ! -e "$backup" ] || {
        printf 'deploy: duplicate owned-mod backup name: %s\n' "${old##*/}" >&2
        return 1
      }
      mv -- "$old" "$backup" || return 1
    done
  done
}

remove_published_owned_mods() {
  local target failed=0
  # A staging failure occurs before any target is recorded. Never infer targets from a broad
  # glob during rollback: those are the user's previous playable jars until a successful publish
  # explicitly records its exact new path.
  for target in "${OWNED_MOD_TX_PUBLISHED_TARGETS[@]}"; do
    case "$target" in
      "$MODS_DIR"/Minecraft-AI-*.jar|"$MODS_DIR"/minecraftai-*.jar|"$MODS_DIR"/pvpbot-inhabitants-*.jar) ;;
      *)
        printf 'deploy: rollback refuses unexpected published-mod path: %s\n' "$target" >&2
        failed=1
        continue
        ;;
    esac
    [ -e "$target" ] || continue
    if [ ! -f "$target" ]; then
      printf 'deploy: rollback cannot remove non-file published mod: %s\n' "$target" >&2
      failed=1
    elif ! rm -f -- "$target"; then
      printf 'deploy: rollback could not remove installed owned mod: %s\n' "$target" >&2
      failed=1
    fi
  done
  return "$failed"
}

restore_owned_mods_from_backup() {
  local backup_dir="$1" backup target failed=0
  [ -d "$backup_dir" ] || return 0
  for backup in "$backup_dir"/*.jar; do
    [ -e "$backup" ] || continue
    target="$MODS_DIR/${backup##*/}"
    if [ -e "$target" ]; then
      # Never restore over a file that we failed to remove: doing so could create the duplicate
      # mod-id state this transaction is intended to prevent.
      printf 'deploy: rollback cannot restore while target remains: %s\n' "$target" >&2
      failed=1
    elif ! mv -- "$backup" "$target"; then
      printf 'deploy: rollback could not restore prior owned mod: %s\n' "${backup##*/}" >&2
      failed=1
    fi
  done
  return "$failed"
}

rollback_owned_mod_install() {
  local failed=0
  printf 'deploy: owned-mod install failed; restoring the prior owned jars\n' >&2
  remove_published_owned_mods || failed=1
  restore_owned_mods_from_backup "$OWNED_MOD_TX_DIR/old/minecraftai" || failed=1
  restore_owned_mods_from_backup "$OWNED_MOD_TX_DIR/old/pvpbot-inhabitants" || failed=1
  return "$failed"
}

cleanup_owned_mod_transaction() {
  local tx="${OWNED_MOD_TX_DIR:-}"
  [ -n "$tx" ] || return 0
  # This is a mktemp-created, exact child of the selected profile, not a computed broad path.
  case "$tx" in
    "$PROFILE_DIR"/.minecraftai-owned-mod-deploy.*)
      [ -e "$tx" ] || return 0
      rm -rf -- "$tx"
      ;;
    *)
      printf 'deploy: refusing to clean an unexpected transaction path: %s\n' "$tx" >&2
      return 1
      ;;
  esac
}

owned_mod_transaction_on_exit() {
  local status="$1" preserve_transaction=0
  trap - EXIT HUP INT TERM
  if [ "${OWNED_MOD_TX_ACTIVE:-0}" = 1 ]; then
    if [ "$status" -ne 0 ] && [ "${OWNED_MOD_TX_COMMITTED:-0}" != 1 ]; then
      if ! rollback_owned_mod_install; then
        # An un-restored old jar is safer in the private transaction directory than deleted. This
        # also leaves an operator a byte-for-byte recovery source instead of hiding the failure.
        preserve_transaction=1
        printf 'deploy: rollback was incomplete; preserve and inspect transaction backup before launching Minecraft: %s\n' \
          "$OWNED_MOD_TX_DIR" >&2
      fi
    fi
    if [ "$preserve_transaction" = 0 ]; then
      cleanup_owned_mod_transaction \
        || printf 'deploy: could not remove transaction staging outside mods: %s\n' "$OWNED_MOD_TX_DIR" >&2
    fi
  fi
  exit "$status"
}

stage_release_mod() {
  local jar="$1" staged="$2" label="$3"
  if ! cp -- "$jar" "$staged"; then
    printf 'deploy: could not stage %s for deployment\n' "$label" >&2
    return 1
  fi
  if ! same_sha256 "$jar" "$staged"; then
    printf 'deploy: staged %s does not match the build\n' "$label" >&2
    return 1
  fi
}

publish_staged_mod() {
  local jar="$1" staged="$2" label="$3" target
  # Bash does not bind earlier variables in the same `local` declaration before expanding later
  # assignments under `set -u`; derive the target in a separate command.
  target="$MODS_DIR/${jar##*/}"
  if [ -e "$target" ]; then
    printf 'deploy: target should have been moved into the transaction backup: %s\n' "$target" >&2
    return 1
  fi
  if [ "$SELF_TEST_TRANSACTION" = 1 ] \
      && [ "$OWNED_MOD_TX_SELF_TEST_FAIL_PUBLISH_LABEL" = "$label" ]; then
    # This branch is reachable only from the disposable transaction self-test. It makes the
    # wrapper publish fail after the root artifact was successfully published, exercising the
    # same EXIT rollback path that a real filesystem failure uses.
    printf 'deploy self-test: intentionally refusing %s publish\n' "$label" >&2
    return 1
  fi
  # Record the exact vacant target before moving. An EXIT signal between `mv` and the next shell
  # instruction still rolls back the new jar, while a failed move leaves this path absent and the
  # rollback never touches the old glob-matched jars.
  OWNED_MOD_TX_PUBLISHED_TARGETS+=("$target")
  if ! mv -- "$staged" "$target"; then
    printf 'deploy: could not install %s\n' "$label" >&2
    return 1
  fi
  if ! same_sha256 "$jar" "$target"; then
    printf 'deploy: installed %s does not match the build\n' "$label" >&2
    return 1
  fi
  if [ "$SELF_TEST_TRANSACTION" = 1 ] && [ -n "$OWNED_MOD_TX_SELF_TEST_SUCCESS_MARKER" ]; then
    if ! printf '%s\n' "$label" >> "$OWNED_MOD_TX_SELF_TEST_SUCCESS_MARKER"; then
      printf 'deploy self-test: could not record successful %s publication\n' "$label" >&2
      return 1
    fi
  fi
  printf 'deploy: installed %s (%s KB)\n' "${target##*/}" "$(( $(wc -c < "$target") / 1024 ))"
}

install_owned_mods_transactionally() {
  local minecraftai_jar="$1" wrapper_jar="$2"
  local staged_minecraftai staged_wrapper

  OWNED_MOD_TX_DIR="$(mktemp -d "$PROFILE_DIR/.minecraftai-owned-mod-deploy.XXXXXXXX")" \
    || die "could not create an owned-mod deployment transaction"
  OWNED_MOD_TX_ACTIVE=1
  OWNED_MOD_TX_COMMITTED=0
  OWNED_MOD_TX_PUBLISHED_TARGETS=()
  trap 'owned_mod_transaction_on_exit $?' EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM

  if ! mkdir -p -- "$OWNED_MOD_TX_DIR/new/minecraftai" "$OWNED_MOD_TX_DIR/new/pvpbot-inhabitants"; then
    die "could not prepare owned-mod staging"
  fi
  staged_minecraftai="$OWNED_MOD_TX_DIR/new/minecraftai/${minecraftai_jar##*/}"
  staged_wrapper="$OWNED_MOD_TX_DIR/new/pvpbot-inhabitants/${wrapper_jar##*/}"
  stage_release_mod "$minecraftai_jar" "$staged_minecraftai" "Minecraft-AI" || die "could not stage Minecraft-AI"
  stage_release_mod "$wrapper_jar" "$staged_wrapper" "pvpbot-inhabitants" || die "could not stage pvpbot-inhabitants"

  # Move every old owned jar out before publishing either new one. If any move or later publish
  # fails, the EXIT transaction restores this exact pre-deploy set instead of leaving duplicates.
  move_owned_mods_to_backup "$OWNED_MOD_TX_DIR/old/minecraftai" \
    'Minecraft-AI-*.jar' 'minecraftai-*.jar' || die "could not reserve the existing Minecraft-AI jars"
  move_owned_mods_to_backup "$OWNED_MOD_TX_DIR/old/pvpbot-inhabitants" \
    'pvpbot-inhabitants-*.jar' || die "could not reserve the existing pvpbot-inhabitants jars"
  publish_staged_mod "$minecraftai_jar" "$staged_minecraftai" "Minecraft-AI" \
    || die "could not publish Minecraft-AI"
  publish_staged_mod "$wrapper_jar" "$staged_wrapper" "pvpbot-inhabitants" \
    || die "could not publish pvpbot-inhabitants"

  OWNED_MOD_TX_COMMITTED=1
  if cleanup_owned_mod_transaction; then
    OWNED_MOD_TX_ACTIVE=0
    OWNED_MOD_TX_DIR=""
    trap - EXIT HUP INT TERM
  else
    # The jars are already committed and no temporary jar was ever placed in mods. Keep the EXIT
    # trap armed for one more cleanup attempt, but do not roll back a playable completed deploy.
    printf 'deploy: warning: transaction staging remains outside mods and will be retried on exit: %s\n' \
      "$OWNED_MOD_TX_DIR" >&2
  fi
}

deploy_owned_mod_transaction_self_test() {
  local old_minecraftai="$MODS_DIR/Minecraft-AI-before-stage-failure.jar"
  local old_wrapper="$MODS_DIR/pvpbot-inhabitants-before-stage-failure.jar"
  local replacement_minecraftai="$PROFILE_DIR/Minecraft-AI-self-test.jar"
  local replacement_wrapper="$PROFILE_DIR/pvpbot-inhabitants-self-test.jar"
  local publish_marker="$PROFILE_DIR/root-published.marker"
  local publish_log="$PROFILE_DIR/wrapper-publish-failure.log"
  local old_minecraftai_sha old_wrapper_sha leftover status=0
  printf 'previous minecraftai artifact\n' > "$old_minecraftai"
  printf 'previous wrapper artifact\n' > "$old_wrapper"
  old_minecraftai_sha="$(sha256sum < "$old_minecraftai")"
  old_wrapper_sha="$(sha256sum < "$old_wrapper")"

  # The first staging copy fails before any old jar may be moved. Run it in a subshell because
  # the production failure path exits, which also exercises the real EXIT rollback trap.
  if ( install_owned_mods_transactionally "$PROFILE_DIR/missing-Minecraft-AI.jar" "$PROFILE_DIR/missing-wrapper.jar" ) \
      >/dev/null 2>&1; then
    printf 'deploy self-test: staging a missing jar unexpectedly succeeded\n' >&2
    status=1
  fi
  if [ ! -f "$old_minecraftai" ] || [ ! -f "$old_wrapper" ] \
      || [ "$(sha256sum < "$old_minecraftai")" != "$old_minecraftai_sha" ] \
      || [ "$(sha256sum < "$old_wrapper")" != "$old_wrapper_sha" ]; then
    printf 'deploy self-test: staging failure changed a prior owned jar\n' >&2
    status=1
  fi
  leftover="$(find "$PROFILE_DIR" -mindepth 1 -maxdepth 1 -type d -name '.minecraftai-owned-mod-deploy.*' -print -quit)"
  if [ -n "$leftover" ]; then
    printf 'deploy self-test: staging transaction was not cleaned: %s\n' "$leftover" >&2
    status=1
  fi

  # Now let Minecraft-AI publish successfully and deliberately reject only the wrapper publish.
  # The marker proves that rollback is tested after the first new jar was actually installed, not
  # merely after a staging or backup failure.
  printf 'replacement minecraftai artifact\n' > "$replacement_minecraftai"
  printf 'replacement wrapper artifact\n' > "$replacement_wrapper"
  OWNED_MOD_TX_SELF_TEST_FAIL_PUBLISH_LABEL="pvpbot-inhabitants"
  OWNED_MOD_TX_SELF_TEST_SUCCESS_MARKER="$publish_marker"
  if ( install_owned_mods_transactionally "$replacement_minecraftai" "$replacement_wrapper" ) \
      >"$publish_log" 2>&1; then
    printf 'deploy self-test: injected wrapper publish failure unexpectedly succeeded\n' >&2
    status=1
  fi
  OWNED_MOD_TX_SELF_TEST_FAIL_PUBLISH_LABEL=""
  OWNED_MOD_TX_SELF_TEST_SUCCESS_MARKER=""
  if [ ! -f "$publish_marker" ] || [ "$(cat "$publish_marker")" != "Minecraft-AI" ]; then
    local published_labels="(none)"
    if [ -f "$publish_marker" ]; then
      published_labels="$(tr '\n' ',' < "$publish_marker")"
    fi
    printf 'deploy self-test: wrapper failure did not occur after only Minecraft-AI was published (published: %s)\n' \
      "$published_labels" >&2
    if [ -f "$publish_log" ]; then
      sed 's/^/deploy self-test: transaction: /' "$publish_log" >&2
    fi
    status=1
  fi
  if [ ! -f "$old_minecraftai" ] || [ ! -f "$old_wrapper" ] \
      || [ "$(sha256sum < "$old_minecraftai")" != "$old_minecraftai_sha" ] \
      || [ "$(sha256sum < "$old_wrapper")" != "$old_wrapper_sha" ]; then
    printf 'deploy self-test: wrapper publish rollback changed a prior owned jar\n' >&2
    status=1
  fi
  if [ -e "$MODS_DIR/${replacement_minecraftai##*/}" ] \
      || [ -e "$MODS_DIR/${replacement_wrapper##*/}" ]; then
    printf 'deploy self-test: wrapper publish rollback left a replacement jar in mods\n' >&2
    status=1
  fi
  leftover="$(find "$PROFILE_DIR" -mindepth 1 -maxdepth 1 -type d -name '.minecraftai-owned-mod-deploy.*' -print -quit)"
  if [ -n "$leftover" ]; then
    printf 'deploy self-test: wrapper publish transaction was not cleaned: %s\n' "$leftover" >&2
    status=1
  fi
  case "$SELF_TEST_PROFILE_DIR" in
    "${TMPDIR:-/tmp}"/minecraftai-deploy-transaction-self-test.*)
      rm -rf -- "$SELF_TEST_PROFILE_DIR" || status=1
      ;;
    *)
      printf 'deploy self-test: refusing to clean unexpected test profile: %s\n' "$SELF_TEST_PROFILE_DIR" >&2
      status=1
      ;;
  esac
  [ "$status" = 0 ] || return "$status"
  printf 'deploy self-test: staging and post-root-publish rollback preserve prior owned jars\n'
}

if [ "$SELF_TEST_TRANSACTION" = 1 ]; then
  if deploy_owned_mod_transaction_self_test; then
    exit 0
  fi
  exit 1
fi

JAR="$(release_jar "$ROOT/build/libs" "Minecraft-AI")"
WRAPPER_JAR="$(release_jar "$WRAPPER_ROOT/build/libs" "pvpbot-inhabitants")"
# pvpbot-inhabitants declares pvp_bot as a hard Fabric dependency. Check the profile before
# touching either owned jar so a missing upstream mod cannot turn a valid profile into one that
# Fabric Loader refuses to launch. The deployer deliberately never downloads or replaces it.
require_profile_fabric_mod "pvp_bot" "PvP BOT"
# A renamed old copy of either owned mod is not part of the fixed filename transaction. Refuse
# before configuration or jar mutation rather than publishing a second Fabric mod id into the
# profile and leaving it unable to start.
require_no_unmanaged_owned_mod_jars

# ---- 3. LLM settings + logging in config/minecraftai.json (key only travels through the environment)
# Render and validate the full replacement before either owned jar changes. The final same-directory
# rename keeps a prior valid config intact if the filesystem refuses the update.
if [ "$DO_CONFIG" = 1 ]; then
  mkdir -p "$CONFIG_DIR"
  CONFIG_FILE="$CONFIG_DIR/minecraftai.json"
  [ -z "$KEY" ] && printf 'deploy: warning: no resolved LLM API key in .env or the environment; the API key is left as it is\n'
  MINECRAFTAI_DEPLOY_CONFIG_PATH="$(cygpath -w "$CONFIG_FILE")" \
  MINECRAFTAI_LLM_API_KEY="$KEY" MINECRAFTAI_LLM_BASE_URL="$BASE" MINECRAFTAI_LLM_MODEL="$MODEL" \
  powershell.exe -NoProfile -Command '
    $ErrorActionPreference = "Stop"
    $path = $env:MINECRAFTAI_DEPLOY_CONFIG_PATH
    if (Test-Path -LiteralPath $path) { $j = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json }
    else { $j = [pscustomobject]@{} }
    if ($null -eq $j) { throw "minecraftai.json is empty" }
    if ($j -isnot [pscustomobject]) { throw "minecraftai.json root must be a JSON object" }
    $names = @($j.PSObject.Properties.Name)
    # Keep the runtime current-name-wins rule even for malformed legacy files: an invalid/null
    # llm or deepseek node is normalized to an object before values are added, never piped through
    # Add-Member as $null or a scalar.
    if ($names -contains "llm" -and $j.llm -is [pscustomobject]) { $llm = $j.llm }
    elseif (-not ($names -contains "llm") -and $names -contains "deepseek" -and $j.deepseek -is [pscustomobject]) { $llm = $j.deepseek }
    else { $llm = [pscustomobject]@{} }
    foreach ($pair in @(@("apiKey", $env:MINECRAFTAI_LLM_API_KEY), @("baseUrl", $env:MINECRAFTAI_LLM_BASE_URL), @("model", $env:MINECRAFTAI_LLM_MODEL))) {
      if (-not [string]::IsNullOrWhiteSpace($pair[1])) { $llm | Add-Member -NotePropertyName $pair[0] -NotePropertyValue $pair[1] -Force }
    }
    if ($names -contains "deepseek") { $j.PSObject.Properties.Remove("deepseek") }
    $j | Add-Member -NotePropertyName "llm" -NotePropertyValue $llm -Force
    # A deployed player profile is deliberately companion-safe: no hidden-world scans or privileged
    # teleports, and every ordinary navigation request uses the observation-fenced Baritone engine.
    $j | Add-Member -NotePropertyName "profile" -NotePropertyValue "strict_survival" -Force
    $j | Add-Member -NotePropertyName "operatorCapabilities" -NotePropertyValue ([pscustomobject]@{
      hiddenBlockScan = $false; emergencyTeleport = $false; manualTeleport = $false
    }) -Force
    $nav = $null
    if ($names -contains "nav" -and $j.nav -is [pscustomobject]) { $nav = $j.nav }
    if ($null -eq $nav) { $nav = [pscustomobject]@{} }
    $nav | Add-Member -NotePropertyName "engine" -NotePropertyValue "baritone" -Force
    $j | Add-Member -NotePropertyName "nav" -NotePropertyValue $nav -Force
    $logging = $null
    if ($names -contains "logging" -and $j.logging -is [pscustomobject]) { $logging = $j.logging }
    if ($null -eq $logging) {
      $logging = [pscustomobject]@{}
      $log = "logging: created and enabled"
    } elseif ($logging.enabled -eq $true) {
      $log = "logging: enabled"
    } else {
      $log = "logging: was disabled, now enabled"
    }
    $logging | Add-Member -NotePropertyName "enabled" -NotePropertyValue $true -Force
    $j | Add-Member -NotePropertyName "logging" -NotePropertyValue $logging -Force
    $json = $j | ConvertTo-Json -Depth 20
    $directory = [IO.Path]::GetDirectoryName($path)
    $staged = Join-Path $directory ("." + [IO.Path]::GetFileName($path) + ".deploy-" + [guid]::NewGuid().ToString("N") + ".tmp")
    try {
      [IO.File]::WriteAllText($staged, $json, (New-Object Text.UTF8Encoding($false)))
      # Validate bytes from the staged file, not only the in-memory object, before publishing.
      $null = [IO.File]::ReadAllText($staged, [Text.Encoding]::UTF8) | ConvertFrom-Json -ErrorAction Stop
      if (Test-Path -LiteralPath $path) {
        [IO.File]::Replace($staged, $path, "$path.bak", $true)
      } else {
        [IO.File]::Move($staged, $path)
      }
    } finally {
      if (Test-Path -LiteralPath $staged) { Remove-Item -LiteralPath $staged -Force -ErrorAction SilentlyContinue }
    }
    $log
  ' | tr -d '\r' | sed 's/^/deploy: /'

  # Verify the committed profile configuration without ever printing the key.
  MINECRAFTAI_DEPLOY_CONFIG_PATH="$(cygpath -w "$CONFIG_FILE")" powershell.exe -NoProfile -Command '
    $j = Get-Content -LiteralPath $env:MINECRAFTAI_DEPLOY_CONFIG_PATH -Raw | ConvertFrom-Json
    $l = $j.llm
    $host_ = ""; try { $host_ = ([uri]$l.baseUrl).Host } catch { $host_ = "(invalid url)" }
    $key = if ([string]::IsNullOrEmpty($l.apiKey)) { "NOT SET" } else { "set (" + $l.apiKey.Length + " chars)" }
    "config: profile=" + $j.profile + " nav.engine=" + $j.nav.engine + " | llm host=" + $host_ + " model=" + $l.model + " apiKey=" + $key
    "config: legacy deepseek section present: " + ($j.PSObject.Properties.Name -contains "deepseek")
  ' | tr -d '\r' | sed 's/^/deploy: /'
fi

# ---- 4. Install the already-validated release artifacts only after the profile configuration
# publishes successfully. One rollback transaction covers the complete owned-mod set.
install_owned_mods_transactionally "$JAR" "$WRAPPER_JAR"

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
