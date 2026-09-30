#!/usr/bin/env bash
# Real-server Fabric GameTest runner for Linux (cloud sessions), one filter at a time, with one PASS/FAIL line per filter.
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
# - Never two runs in the same worktree (they share build/run/gameTest): a per-worktree flock enforces it.
# - At most GT_SLOTS servers machine-wide (default 1; each needs about GT_HEAP + 1 GB of RAM). Raise it only when
#   `free -g` shows the room. An ALL run takes every slot (it runs alone).
# Env: GT_HEAP (default 2560m); GT_TIMEOUT (seconds per filter, default 1500; ALL gets 7200); GT_DAEMON=1 to use a Gradle
# daemon (faster repeated runs, more resident memory).
set -u
WRAP=0
if [ "${1:-}" = "--wrapper" ]; then WRAP=1; shift; fi
if [ $# -lt 3 ]; then sed -n '2,12p' "$0"; exit 2; fi
REPO="$(cd "$1" && pwd)" || exit 2
OUTF="$2"; shift 2
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

mkdir -p build
exec 8> "build/.gametest-worktree.lock"
flock 8

SLOT_FD=9
# A normal run holds ONE of the GT_SLOTS slot locks. An ALL run holds EVERY slot, so no other server runs beside it: a
# full-suite result is only meaningful when the machine is not shared with another server (CPU starvation flakes tests).
# Both kinds take slots in index order, and an ALL run waits for each slot in turn, so the two cannot deadlock.
ALL_FDS=()
acquire_slot() {
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
  local fd
  for fd in "${ALL_FDS[@]:-}"; do [ -n "$fd" ] && { flock -u "$fd" 2>/dev/null; eval "exec $fd>&-" 2>/dev/null; }; done
  ALL_FDS=()
  flock -u 9 2>/dev/null; exec 9>&- 2>/dev/null
}

RUN_PID=""
kill_run() {
  [ -n "$RUN_PID" ] || return 0
  kill -TERM -- "-$RUN_PID" 2>/dev/null; sleep 5; kill -KILL -- "-$RUN_PID" 2>/dev/null
  RUN_PID=""
}
trap 'kill_run; release_slot' EXIT
trap 'exit 143' INT TERM HUP

# Runs Gradle in its own process group so a timeout or an exit kills the Gradle daemon AND the forked test server.
run_watched() { # $1 = limit seconds, rest = command
  local limit=$1; shift
  setsid "$@" > "$L" 2>&1 &
  RUN_PID=$!
  local start; start=$(date +%s)
  HUNG=""
  while kill -0 "$RUN_PID" 2>/dev/null; do
    sleep 5
    local mb=$(( $(stat -c %s "$L" 2>/dev/null || echo 0) / 1048576 ))
    if [ $(( $(date +%s) - start )) -gt "$limit" ] || [ "$mb" -gt "$MAX_LOG_MB" ]; then
      HUNG="elapsed=$(( $(date +%s) - start ))s log=${mb}MB"
      kill_run
      break
    fi
  done
  local rc=1
  if [ -z "$HUNG" ]; then wait "$RUN_PID" 2>/dev/null; rc=$?; fi
  RUN_PID=""
  return $rc
}

for F in "$@"; do
  L="$LOGDIR/$(echo "$F" | tr -c 'a-zA-Z0-9_\n' '_' | cut -c1-120)$([ $WRAP = 1 ] && echo _wrapper).log"
  acquire_slot "$F"
  if [ "$F" = ALL ]; then
    JAVA_TOOL_OPTIONS="-Xmx$HEAP" run_watched 7200 "${GRADLE[@]}" runGameTest "${XARGS[@]}"
  else
    JAVA_TOOL_OPTIONS="-Xmx$HEAP -Dfabric-api.gametest.filter=$PREFIX:$F" \
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
