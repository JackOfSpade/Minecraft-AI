#!/usr/bin/env bash
# Shared boilerplate for the long-running, resumable dev-loop scripts (auto30.sh, story.sh,
# reliability.sh): a single-instance mkdir lock and a best-effort keep-awake guard. This file is
# sourced by scripts in ../; it does not execute a run by itself.

# devloop_acquire_lock <lock-dir> <label>
#   Atomic single-instance lock: `mkdir` failure means another instance is already running, so a
#   duplicate launch prints "[<label>] another instance running (lock <lock-dir>), exit." and exits
#   0 (idempotent -- a duplicate launch is harmless, never kills the running one). On success,
#   registers a trap that removes the lock directory on exit.
devloop_acquire_lock() {
  local lock="$1" label="$2"
  mkdir "$lock" 2>/dev/null || { echo "[$label] another instance running (lock $lock), exit."; exit 0; }
  # The lock path is expanded now (not when the trap fires), since it is a local variable that goes
  # out of scope once this function returns.
  # shellcheck disable=SC2064
  trap "rmdir '$lock' 2>/dev/null" EXIT
}

# devloop_keep_awake
#   Best-effort keep-awake for the life of this process (macOS caffeinate); a harmless no-op where
#   caffeinate isn't installed (e.g. Linux/Windows).
devloop_keep_awake() {
  command -v caffeinate >/dev/null && caffeinate -is -w $$ &
  :
}
