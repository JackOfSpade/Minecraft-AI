#!/usr/bin/env bash
# Real-server Fabric GameTest runner for Linux and Git Bash, one filter at a time, with one PASS/FAIL line per filter.
#
# usage: scripts/dev/gametest.sh [--wrapper] <repo-or-worktree> <outfile> <filter|ALL> [<filter> ...]
#   Root mod:  filter = exact test name or a glob with * ; the "minecraftai-gametest:" prefix is added.
#   --wrapper: runs Minecraft-Spawn-Bots-Wrapper's suite ("pvpbot-inhabitants-gametest:" prefix). It needs the PvP BOT and
#              HeroBot jars: run scripts/dev/fetch-upstream-mods.sh first (default dir $HOME/.cache/upstream-mods,
#              override with UPSTREAM_MODS_DIR). Without them the run prints SKIPPED.
#   GameTest names squash single-letter words: inside_acave, crafts_astone_pick.
# Output lines appended to <outfile>:
#   PASS ran=<environment batches> tests=<required tests passed> <filter>
#   FAIL ran=<batches> <filter> :: <failed test names>
#   NOMATCH <filter> | ERROR(build/compile?) <filter> | HUNG(...) <filter> | SKIPPED(...) <filter>
# Full Gradle+server log per filter: <dir of outfile>/gt_logs/<filter>.log ; the server's own logs of the LAST run are in
# <project>/build/run/gameTest/logs.
#
# Concurrency:
# - Never two runs in the same worktree (they share build/run/gameTest): a per-worktree flock, or an atomic mkdir lock on
#   Git Bash where flock is unavailable, enforces it.
# - At most GT_SLOTS servers machine-wide (default 1; each needs about GT_HEAP + 1 GB of RAM). Raise it only when
#   `free -g` shows the room. An ALL run takes every slot (it runs alone).
# Env: GT_HEAP (default 2560m); GT_TIMEOUT (seconds per filter, default 1500; ALL gets 7200); GT_DAEMON=1 to use a Gradle
# daemon (faster repeated runs, more resident memory). GT_JAVA_OPTS appends explicit JVM properties for a specialised fixture;
# scripts/dev/nav_measurement.sh uses it for its opt-in Baritone diagnostic capture.
set -u
SELF_TEST=0
if [ "${1:-}" = "--self-test" ]; then SELF_TEST=1; shift; fi
WRAP=0
if [ "${1:-}" = "--wrapper" ]; then WRAP=1; shift; fi
if [ "$SELF_TEST" = 1 ]; then
  if [ "$WRAP" != 0 ] || [ $# -ne 0 ]; then sed -n '2,12p' "$0"; exit 2; fi
  REPO="$(pwd -P)"
  SELF_TMP="$(mktemp -d "${TMPDIR:-/tmp}/minecraftai-gametest-self-test.XXXXXX")" || exit 1
  OUTF="$SELF_TMP/out"
else
  if [ $# -lt 3 ]; then sed -n '2,12p' "$0"; exit 2; fi
  REPO="$(cd "$1" && pwd)" || exit 2
  OUTF="$2"; shift 2
fi
touch "$OUTF"; OUTF="$(cd "$(dirname "$OUTF")" && pwd)/$(basename "$OUTF")"
LOGDIR="$(dirname "$OUTF")/gt_logs"; mkdir -p "$LOGDIR"
if [ $WRAP = 1 ]; then
  DIR="$REPO/Minecraft-Spawn-Bots-Wrapper"; PREFIX=pvpbot-inhabitants-gametest
  XARGS=("-PupstreamModsDir=${UPSTREAM_MODS_DIR:-$HOME/.cache/upstream-mods}")
else
  DIR="$REPO"; PREFIX=minecraftai-gametest; XARGS=()
fi
cd "$DIR" || exit 2
GRADLE=(bash ./gradlew --console=plain)
[ -n "${GT_DAEMON:-}" ] || GRADLE+=(--no-daemon)
HEAP="${GT_HEAP:-2560m}"
SLOTS="${GT_SLOTS:-1}"
MAX_LOG_MB=300
HAVE_FLOCK=0
command -v flock >/dev/null 2>&1 && HAVE_FLOCK=1
HAVE_SETSID=0
command -v setsid >/dev/null 2>&1 && HAVE_SETSID=1
WATCH_INTERVAL=5

# The runner self-test is the regression proof for the Git-Bash fallbacks.  Force the
# mkdir locks even on Linux CI, and force no-setsid below, so both branches are exercised
# before any worktree lock is acquired.
if [ "$SELF_TEST" = 1 ]; then
  HAVE_FLOCK=0
fi

# Git for Windows' Bash does not ship flock. An atomic mkdir is the portable equivalent here:
# every lock directory is owned by this script's PID and only that owner removes it. Deliberately
# do not guess at stale ownership: PID reuse makes automatic removal less safe than showing the
# path and letting an operator recover an abandoned lock explicitly.
mkdir_lock_wait() {
  local lock=$1 shown=0 owner
  while ! mkdir "$lock" 2>/dev/null; do
    if [ "$shown" = 0 ]; then
      owner="$(cat "$lock/pid" 2>/dev/null || true)"
      echo "waiting for mkdir lock $lock${owner:+ (pid $owner)}" >&2
      shown=1
    fi
    sleep 5
  done
  if ! printf '%s\n' "$$" > "$lock/pid"; then
    rmdir "$lock" 2>/dev/null || true
    return 1
  fi
}

mkdir_lock_try() {
  local lock=$1
  mkdir "$lock" 2>/dev/null || return 1
  if ! printf '%s\n' "$$" > "$lock/pid"; then
    rmdir "$lock" 2>/dev/null || true
    return 1
  fi
}

mkdir_unlock() {
  local lock=$1 owner
  owner="$(cat "$lock/pid" 2>/dev/null || true)"
  [ "$owner" = "$$" ] || return 0
  rm -f -- "$lock/pid"
  rmdir "$lock" 2>/dev/null || true
}

mkdir -p build
WORKTREE_LOCK_DIR=""
if [ "$HAVE_FLOCK" = 1 ]; then
  exec 8> "build/.gametest-worktree.lock"
  flock 8
else
  WORKTREE_LOCK_DIR="build/.gametest-worktree.lock.d"
  mkdir_lock_wait "$WORKTREE_LOCK_DIR" || exit 1
fi

SLOT_FD=9
# A normal run holds ONE of the GT_SLOTS slot locks. An ALL run holds EVERY slot, so no other server runs beside it: a
# full-suite result is only meaningful when the machine is not shared with another server (CPU starvation flakes tests).
# Both kinds take slots in index order, and an ALL run waits for each slot in turn, so the two cannot deadlock.
ALL_FDS=()
SLOT_DIRS=()
acquire_slot() {
  if [ "$HAVE_FLOCK" != 1 ]; then
    SLOT_DIRS=()
    if [ "$1" = ALL ]; then
      for ((i = 0; i < SLOTS; i++)); do
        if ! mkdir_lock_wait "/tmp/gametest.slot$i.lock.d"; then
          release_slot
          return 1
        fi
        SLOT_DIRS+=("/tmp/gametest.slot$i.lock.d")
      done
      return 0
    fi
    while true; do
      for ((i = 0; i < SLOTS; i++)); do
        if mkdir_lock_try "/tmp/gametest.slot$i.lock.d"; then
          SLOT_DIRS=("/tmp/gametest.slot$i.lock.d")
          return 0
        fi
      done
      sleep 5
    done
  fi
  if [ "$1" = ALL ]; then
    ALL_FDS=()
    for ((i = 0; i < SLOTS; i++)); do
      exec {fd}> "/tmp/gametest.slot$i"
      flock "$fd"
      ALL_FDS+=("$fd")
    done
    return 0
  fi
  while true; do
    for ((i = 0; i < SLOTS; i++)); do
      exec 9> "/tmp/gametest.slot$i"
      if flock -n 9; then return 0; fi
      exec 9>&-
    done
    sleep 5
  done
}
release_slot() {
  if [ "$HAVE_FLOCK" != 1 ]; then
    local dir
    for dir in "${SLOT_DIRS[@]:-}"; do
      [ -n "$dir" ] && mkdir_unlock "$dir"
    done
    SLOT_DIRS=()
    return 0
  fi
  local fd
  for fd in "${ALL_FDS[@]:-}"; do [ -n "$fd" ] && { flock -u "$fd" 2>/dev/null; eval "exec $fd>&-" 2>/dev/null; }; done
  ALL_FDS=()
  flock -u 9 2>/dev/null; exec 9>&- 2>/dev/null
}

release_worktree_lock() {
  if [ "$HAVE_FLOCK" = 1 ]; then
    flock -u 8 2>/dev/null
    exec 8>&- 2>/dev/null
  elif [ -n "$WORKTREE_LOCK_DIR" ]; then
    mkdir_unlock "$WORKTREE_LOCK_DIR"
  fi
}

# Without setsid, Git Bash gives the background Gradle wrapper a normal process group. Snapshot
# its POSIX child tree before TERM instead of sending a negative-PID signal (which would target
# no process there). The same snapshot is retained for KILL because a TERM-ignoring child can be
# reparented while the wrapper exits during the grace period. Git Bash's ps does not support
# `-o`, and its long layouts vary, so find PID/PPID columns from the ps -ef header rather than
# relying on Linux field numbers.
children_of() {
  ps -ef 2>/dev/null | awk -v parent="$1" '
    NR == 1 {
      for (column = 1; column <= NF; column++) {
        name = toupper($column)
        if (name == "PID") pid_column = column
        if (name == "PPID") ppid_column = column
      }
      next
    }
    pid_column && ppid_column && $ppid_column == parent { print $pid_column }
  '
}

RUN_TREE_PIDS=()
remember_tree_pid() {
  local candidate=$1 known
  for known in "${RUN_TREE_PIDS[@]:-}"; do
    [ "$known" = "$candidate" ] && return 0
  done
  RUN_TREE_PIDS+=("$candidate")
}

snapshot_tree() {
  local pid=$1 child
  while IFS= read -r child; do
    [ -n "$child" ] && snapshot_tree "$child"
  done < <(children_of "$pid")
  remember_tree_pid "$pid"
}

signal_snapshot() {
  local signal=$1 pid
  for pid in "${RUN_TREE_PIDS[@]:-}"; do
    [ -n "$pid" ] && kill "-$signal" "$pid" 2>/dev/null || true
  done
}

RUN_PID=""
kill_run() {
  [ -n "$RUN_PID" ] || return 0
  if [ "$HAVE_SETSID" = 1 ]; then
    kill -TERM -- "-$RUN_PID" 2>/dev/null
    sleep 5
    kill -KILL -- "-$RUN_PID" 2>/dev/null
  else
    RUN_TREE_PIDS=()
    snapshot_tree "$RUN_PID"
    signal_snapshot TERM
    sleep 5
    # Include descendants that appeared during the grace period while retaining every PID that
    # was below the wrapper before TERM (those may already have been reparented).
    snapshot_tree "$RUN_PID"
    signal_snapshot KILL
  fi
  RUN_PID=""
}
trap 'kill_run; release_slot; release_worktree_lock' EXIT
trap 'exit 143' INT TERM HUP

# Runs Gradle in its own process group when setsid is available. Git Bash lacks setsid, where
# the retained PID snapshot below still tears down the Gradle wrapper and its forked test server on timeout.
run_watched() { # $1 = limit seconds, rest = command
  local limit=$1; shift
  if [ "$HAVE_SETSID" = 1 ]; then
    setsid "$@" > "$L" 2>&1 &
  else
    "$@" > "$L" 2>&1 &
  fi
  RUN_PID=$!
  local watched_pid=$RUN_PID
  local start; start=$(date +%s)
  HUNG=""
  while kill -0 "$RUN_PID" 2>/dev/null; do
    sleep "$WATCH_INTERVAL"
    local mb=$(( $(stat -c %s "$L" 2>/dev/null || echo 0) / 1048576 ))
    if [ $(( $(date +%s) - start )) -gt "$limit" ] || [ "$mb" -gt "$MAX_LOG_MB" ]; then
      HUNG="elapsed=$(( $(date +%s) - start ))s log=${mb}MB"
      kill_run
      break
    fi
  done
  local rc=1
  if [ -z "$HUNG" ]; then
    wait "$watched_pid" 2>/dev/null
    rc=$?
  else
    # Reap the killed wrapper so Git Bash does not emit an asynchronous "Killed" job notice.
    wait "$watched_pid" 2>/dev/null || true
  fi
  RUN_PID=""
  return $rc
}

wait_for_pid_gone() {
  local pid=$1 attempt
  # KILL/reap is asynchronous on a few POSIX layers (notably Git Bash), so avoid a
  # flaky immediate kill -0 assertion while keeping the regression check bounded.
  for ((attempt = 0; attempt < 5; attempt++)); do
    if ! kill -0 "$pid" 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  ! kill -0 "$pid" 2>/dev/null
}

runner_self_test_no_setsid() {
  local child_pid_file stubborn_child
  WATCH_INTERVAL=1
  L="$(mktemp "${TMPDIR:-/tmp}/minecraftai-gametest-watch.XXXXXX")" || return 1
  acquire_slot SELF_TEST || return 1
  if ! run_watched 3 bash -c 'exit 0'; then
    release_slot
    rm -f -- "$L"
    return 1
  fi
  release_slot
  rm -f -- "$L"

  L="$(mktemp "${TMPDIR:-/tmp}/minecraftai-gametest-watch.XXXXXX")" || return 1
  child_pid_file="$(mktemp "${TMPDIR:-/tmp}/minecraftai-gametest-child.XXXXXX")" || { rm -f -- "$L"; return 1; }
  acquire_slot SELF_TEST || return 1
  # The child deliberately ignores TERM. If the wrapper exits and it is reparented during the
  # five-second grace period, the retained pre-TERM PID snapshot must still KILL it.
  if run_watched 0 bash -c 'trap "" TERM; bash -c '\''trap "" TERM; while :; do sleep 1; done'\'' & child=$!; printf "%s\\n" "$child" > "$1"; wait "$child"' _ "$child_pid_file"; then
    release_slot
    rm -f -- "$L"
    rm -f -- "$child_pid_file"
    return 1
  fi
  if [ -z "$HUNG" ]; then
    release_slot
    rm -f -- "$L"
    rm -f -- "$child_pid_file"
    return 1
  fi
  stubborn_child="$(cat "$child_pid_file" 2>/dev/null || true)"
  if [ -z "$stubborn_child" ] || ! wait_for_pid_gone "$stubborn_child"; then
    release_slot
    rm -f -- "$L"
    rm -f -- "$child_pid_file"
    return 1
  fi
  release_slot
  rm -f -- "$L"
  rm -f -- "$child_pid_file"
}

runner_self_test() {
  # CI commonly has setsid, but this is specifically a regression test for the portable
  # no-setsid tree snapshot and retained-PID teardown path.
  local saved_setsid=$HAVE_SETSID rc
  HAVE_SETSID=0
  runner_self_test_no_setsid
  rc=$?
  HAVE_SETSID=$saved_setsid
  return "$rc"
}

if [ "$SELF_TEST" = 1 ]; then
  if ! runner_self_test; then
    echo "GameTest runner self-test failed" >&2
    exit 1
  fi
  release_worktree_lock
  rmdir "$LOGDIR" 2>/dev/null || true
  rm -f -- "$OUTF"
  rmdir "$SELF_TMP" 2>/dev/null || true
  echo "GameTest runner self-test passed (flock=$HAVE_FLOCK setsid=$HAVE_SETSID)"
  exit 0
fi

for F in "$@"; do
  L="$LOGDIR/$(echo "$F" | tr -c 'a-zA-Z0-9_\n' '_' | cut -c1-120)$([ $WRAP = 1 ] && echo _wrapper).log"
  acquire_slot "$F"
  if [ "$F" = ALL ]; then
    JAVA_TOOL_OPTIONS="-Xmx$HEAP ${GT_JAVA_OPTS:-}" run_watched 7200 "${GRADLE[@]}" runGameTest "${XARGS[@]}"
  else
    JAVA_TOOL_OPTIONS="-Xmx$HEAP ${GT_JAVA_OPTS:-} -Dfabric-api.gametest.filter=$PREFIX:$F" \
      run_watched "${GT_TIMEOUT:-1500}" "${GRADLE[@]}" runGameTest "${XARGS[@]}"
  fi
  RC=$?
  release_slot
  if [ -n "$HUNG" ]; then
    [ "$(stat -c %s "$L" 2>/dev/null || echo 0)" -gt $((MAX_LOG_MB * 1048576)) ] && { head -c 20000000 "$L" > "$L.head"; mv "$L.head" "$L"; }
    echo "HUNG(killed by watchdog: $HUNG; log: $L) $F" >> "$OUTF"; tail -1 "$OUTF"; continue
  fi
  RAN=$(grep -c "Running test environment" "$L")
  NTESTS=$(grep -oE "All [0-9]+ required tests? passed" "$L" | grep -oE "[0-9]+" | tail -1)
  FAILS=$(grep -E "\(Minecraft\)    - $PREFIX:" "$L" | sed -E "s/.*$PREFIX:([^:]*):.*/\1/" | paste -sd, -)
  if grep -q "runGameTest skipped" "$L"; then echo "SKIPPED(no PvP BOT / HeroBot jars: run scripts/dev/fetch-upstream-mods.sh) $F" >> "$OUTF"
  elif [ $RC -eq 0 ] && [ "$RAN" = 0 ]; then echo "NOMATCH(no test matched this filter - check the exact name) $F" >> "$OUTF"
  elif [ $RC -eq 0 ]; then echo "PASS ran=$RAN tests=${NTESTS:-?} $F" >> "$OUTF"
  elif [ "$RAN" = 0 ]; then echo "ERROR(build/compile? see $L) $F" >> "$OUTF"
  else echo "FAIL ran=$RAN $F :: ${FAILS}" >> "$OUTF"; fi
  tail -1 "$OUTF"
done
