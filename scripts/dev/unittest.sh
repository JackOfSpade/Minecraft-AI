#!/usr/bin/env bash
# Unit-test runner for Linux and Git Bash: one Gradle `test` run at a time machine-wide, with a summary line.
#
# usage: scripts/dev/unittest.sh [--self-test] [--wrapper] <repo-or-worktree> [<gradle args>...]
#   default : the root mod's unit tests      (./gradlew test in <worktree>)
#   --wrapper: the PvP BOT wrapper's tests   (./gradlew test in <worktree>/Minecraft-Spawn-Bots-Wrapper)
#   extra args go to Gradle, e.g.  --tests '*ShieldRulesTest'
# Prints one line:  UNIT root|wrapper PASS|FAIL tests=<n> failures=<n> errors=<n> skipped=<n> [<log path>]
# The full Gradle output is in <worktree>/build/unittest-<root|wrapper>.log (the wrapper's in its own build/).
#
# - A machine-wide flock serialises runs (or an atomic mkdir lock on Git Bash, where flock is unavailable); each needs a
#   Gradle daemon plus test workers, and two at once next to two GameTest servers do not fit in 15 GB. Waiting is normal.
# - Maven Central (repo.maven.apache.org / repo1.maven.org) answers HTTP 429 in bursts when many sessions share an egress IP.
#   The run is repeated with a growing pause while the log shows a 429; each retry gets further because Gradle caches
#   what it has already downloaded. A real failure (compile error, failed test) is never retried.
set -u
SELF_TEST=0
if [ "${1:-}" = "--self-test" ]; then SELF_TEST=1; shift; fi
WRAP=0
if [ "${1:-}" = "--wrapper" ]; then WRAP=1; shift; fi
if [ "$SELF_TEST" = 1 ]; then
  if [ "$WRAP" != 0 ] || [ $# -ne 0 ]; then sed -n '2,13p' "$0"; exit 2; fi
  REPO="$(pwd -P)"
else
  if [ $# -lt 1 ]; then sed -n '2,13p' "$0"; exit 2; fi
  REPO="$(cd "$1" && pwd)" || exit 2
  shift
fi
if [ $WRAP = 1 ]; then DIR="$REPO/Minecraft-Spawn-Bots-Wrapper"; KIND=wrapper; else DIR="$REPO"; KIND=root; fi
cd "$DIR" || exit 2
mkdir -p build
LOG="$DIR/build/unittest-$KIND.log"

HAVE_FLOCK=0
command -v flock >/dev/null 2>&1 && HAVE_FLOCK=1
UNIT_LOCK_DIR=""

# Keep the portable mkdir lock branch covered on Linux CI as well as Git Bash. This
# happens before lock acquisition, unlike a runtime override inside the self-test.
if [ "$SELF_TEST" = 1 ]; then
  HAVE_FLOCK=0
fi

# Git for Windows does not provide flock. Like the GameTest runner, never reclaim a lock by
# guessing whether a PID is stale: print the exact lock/owner and let an operator remove an
# abandoned directory instead of risking a concurrent Gradle test run.
mkdir_unit_lock_wait() {
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

release_unit_lock() {
  if [ "$HAVE_FLOCK" = 1 ]; then
    flock -u 7 2>/dev/null
    exec 7>&- 2>/dev/null
    return 0
  fi
  local owner
  owner="$(cat "$UNIT_LOCK_DIR/pid" 2>/dev/null || true)"
  [ "$owner" = "$$" ] || return 0
  rm -f -- "$UNIT_LOCK_DIR/pid"
  rmdir "$UNIT_LOCK_DIR" 2>/dev/null || true
}

if [ "$HAVE_FLOCK" = 1 ]; then
  exec 7> /tmp/unittest.lock
  flock 7
else
  UNIT_LOCK_DIR="/tmp/unittest.lock.d"
  mkdir_unit_lock_wait "$UNIT_LOCK_DIR" || exit 1
fi

# A signal must not unlock the machine-wide slot while Gradle or a test worker is still alive.
# Git Bash lacks both setsid and ps -o, so discover PID/PPID columns from ps -ef's header and
# retain the pre-TERM descendant snapshot through KILL (a TERM-ignoring child may be reparented).
unit_children_of() {
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

UNIT_PID=""
UNIT_TREE_PIDS=()
remember_unit_pid() {
  local candidate=$1 known
  for known in "${UNIT_TREE_PIDS[@]:-}"; do
    [ "$known" = "$candidate" ] && return 0
  done
  UNIT_TREE_PIDS+=("$candidate")
}

snapshot_unit_tree() {
  local pid=$1 child
  while IFS= read -r child; do
    [ -n "$child" ] && snapshot_unit_tree "$child"
  done < <(unit_children_of "$pid")
  remember_unit_pid "$pid"
}

signal_unit_snapshot() {
  local signal=$1 pid
  for pid in "${UNIT_TREE_PIDS[@]:-}"; do
    [ -n "$pid" ] && kill "-$signal" "$pid" 2>/dev/null || true
  done
}

start_unit_command() {
  "$@" > "$LOG" 2>&1 &
  UNIT_PID=$!
}

terminate_unit_run() {
  [ -n "$UNIT_PID" ] || return 0
  local watched_pid=$UNIT_PID
  UNIT_TREE_PIDS=()
  snapshot_unit_tree "$watched_pid"
  signal_unit_snapshot TERM
  sleep 5
  # Add descendants created while TERM was in flight, but keep the original tree because a
  # surviving child may have been reparented by the time this second snapshot runs.
  snapshot_unit_tree "$watched_pid"
  signal_unit_snapshot KILL
  wait "$watched_pid" 2>/dev/null || true
  UNIT_PID=""
}

run_unit_gradle() {
  start_unit_command bash ./gradlew --console=plain test "$@"
  local rc
  wait "$UNIT_PID" 2>/dev/null
  rc=$?
  UNIT_PID=""
  return "$rc"
}

wait_for_unit_pid_gone() {
  local pid=$1 attempt
  # A KILL/reap can briefly lag behind its wrapper on Git Bash, so make the assertion
  # bounded rather than falsely failing a successful portable cancellation cleanup.
  for ((attempt = 0; attempt < 5; attempt++)); do
    if ! kill -0 "$pid" 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  ! kill -0 "$pid" 2>/dev/null
}

on_unit_signal() {
  terminate_unit_run
  case "$1" in
    INT) exit 130 ;;
    *) exit 143 ;;
  esac
}

trap 'terminate_unit_run; release_unit_lock' EXIT
trap 'on_unit_signal INT' INT
trap 'on_unit_signal TERM' TERM
trap 'on_unit_signal HUP' HUP

unit_runner_self_test() {
  local child_pid_file stubborn_child attempt
  child_pid_file="$(mktemp "${TMPDIR:-/tmp}/minecraftai-unittest-child.XXXXXX")" || return 1
  # The nested child deliberately ignores TERM. terminate_unit_run must retain its PID through
  # the grace period, KILL it, reap the wrapper, and only then allow the EXIT trap to unlock.
  start_unit_command bash -c 'trap "" TERM; bash -c '\''trap "" TERM; while :; do sleep 1; done'\'' & child=$!; printf "%s\\n" "$child" > "$1"; wait "$child"' _ "$child_pid_file"
  for ((attempt = 0; attempt < 5; attempt++)); do
    [ -s "$child_pid_file" ] && break
    sleep 1
  done
  if [ ! -s "$child_pid_file" ]; then
    terminate_unit_run
    rm -f -- "$child_pid_file"
    return 1
  fi
  stubborn_child="$(cat "$child_pid_file" 2>/dev/null || true)"
  terminate_unit_run
  # terminate_unit_run is the cleanup called by every signal trap. It must finish
  # before the EXIT trap releases this script's machine-wide mkdir lock.
  if [ -z "$stubborn_child" ] || ! wait_for_unit_pid_gone "$stubborn_child" \
      || [ ! -d "$UNIT_LOCK_DIR" ] || [ "$(cat "$UNIT_LOCK_DIR/pid" 2>/dev/null || true)" != "$$" ]; then
    rm -f -- "$child_pid_file"
    return 1
  fi
  rm -f -- "$child_pid_file"
}

if [ "$SELF_TEST" = 1 ]; then
  if ! unit_runner_self_test; then
    echo "Unit runner self-test failed" >&2
    exit 1
  fi
  echo "Unit runner self-test passed (flock=$HAVE_FLOCK)"
  exit 0
fi

RC=1
for ((attempt = 1; attempt <= 20; attempt++)); do
  run_unit_gradle "$@"
  RC=$?
  if [ $RC -ne 0 ] && grep -q "status code 429" "$LOG"; then sleep $((10 + 5 * attempt)); continue; fi
  break
done

PYTHON=()
python_works() {
  "$@" -c 'import xml.etree.ElementTree' >/dev/null 2>&1
}
if command -v python3 >/dev/null 2>&1 && python_works python3; then
  PYTHON=(python3)
elif command -v python >/dev/null 2>&1 && python_works python; then
  PYTHON=(python)
elif command -v py >/dev/null 2>&1 && python_works py -3; then
  PYTHON=(py -3)
fi

if [ "${#PYTHON[@]}" -gt 0 ]; then
  "${PYTHON[@]}" - "$DIR/build/test-results/test" "$KIND" "$RC" "$LOG" <<'EOF'
import glob, sys, xml.etree.ElementTree as ET
d, kind, rc, log = sys.argv[1:5]
t = f = e = s = 0
for p in glob.glob(d + '/*.xml'):
    r = ET.parse(p).getroot()
    t += int(r.get('tests')); f += int(r.get('failures')); e += int(r.get('errors')); s += int(r.get('skipped'))
ok = rc == '0' and f == 0 and e == 0
print(f"UNIT {kind} {'PASS' if ok else 'FAIL'} tests={t} failures={f} errors={e} skipped={s} {'' if ok else log}")
EOF
else
  # Windows' Store execution aliases make `python3` appear on PATH without an interpreter.
  # Gradle writes one root <testsuite> per XML report, so retain a dependency-free summary
  # rather than turning a successful test run into a shell error on Git Bash.
  xml_total() {
    local attribute=$1 total=0 report value
    shopt -s nullglob
    for report in "$DIR/build/test-results/test"/*.xml; do
      value="$(grep -m 1 '<testsuite ' "$report" | sed -n -E "s/.*[[:space:]]${attribute}=\"([0-9]+)\".*/\\1/p")"
      value="${value:-0}"
      total=$((total + value))
    done
    shopt -u nullglob
    printf '%s' "$total"
  }
  TESTS="$(xml_total tests)"
  FAILURES="$(xml_total failures)"
  ERRORS="$(xml_total errors)"
  SKIPPED="$(xml_total skipped)"
  if [ "$RC" = 0 ] && [ "$FAILURES" = 0 ] && [ "$ERRORS" = 0 ]; then
    echo "UNIT $KIND PASS tests=$TESTS failures=$FAILURES errors=$ERRORS skipped=$SKIPPED"
  else
    echo "UNIT $KIND FAIL tests=$TESTS failures=$FAILURES errors=$ERRORS skipped=$SKIPPED $LOG"
  fi
fi
exit $RC
