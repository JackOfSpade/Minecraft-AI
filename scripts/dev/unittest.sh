#!/usr/bin/env bash
# Unit-test runner for Linux (cloud sessions): one Gradle `test` run at a time machine-wide, with a summary line.
#
# usage: scripts/dev/unittest.sh [--wrapper] <repo-or-worktree> [<gradle args>...]
#   default : the root mod's unit tests      (./gradlew test in <worktree>)
#   --wrapper: the PvP BOT wrapper's tests   (./gradlew test in <worktree>/Minecraft-Spawn-Bots-Wrapper)
#   extra args go to Gradle, e.g.  --tests '*ShieldRulesTest'
# Prints one line:  UNIT root|wrapper PASS|FAIL tests=<n> failures=<n> errors=<n> skipped=<n> [<log path>]
# The full Gradle output is in <worktree>/build/unittest-<root|wrapper>.log (the wrapper's in its own build/).
#
# - A machine-wide flock serialises runs (each needs a Gradle daemon plus test workers; two at once, next to two GameTest
#   servers, do not fit in 15 GB). Waiting for the lock is normal.
# - Maven Central (repo.maven.apache.org / repo1.maven.org) answers HTTP 429 in bursts when many sessions share an egress IP.
#   The run is repeated with a growing pause while the log shows a 429; each retry gets further because Gradle caches
#   what it has already downloaded. A real failure (compile error, failed test) is never retried.
set -u
WRAP=0
if [ "${1:-}" = "--wrapper" ]; then WRAP=1; shift; fi
if [ $# -lt 1 ]; then sed -n '2,13p' "$0"; exit 2; fi
REPO="$(cd "$1" && pwd)" || exit 2
shift
if [ $WRAP = 1 ]; then DIR="$REPO/Minecraft-Spawn-Bots-Wrapper"; KIND=wrapper; else DIR="$REPO"; KIND=root; fi
cd "$DIR" || exit 2
mkdir -p build
LOG="$DIR/build/unittest-$KIND.log"

exec 7> /tmp/unittest.lock
flock 7

RC=1
for ((attempt = 1; attempt <= 20; attempt++)); do
  bash ./gradlew --console=plain test "$@" > "$LOG" 2>&1
  RC=$?
  if [ $RC -ne 0 ] && grep -q "status code 429" "$LOG"; then sleep $((10 + 5 * attempt)); continue; fi
  break
done

python3 - "$DIR/build/test-results/test" "$KIND" "$RC" "$LOG" <<'EOF'
import glob, sys, xml.etree.ElementTree as ET
d, kind, rc, log = sys.argv[1:5]
t = f = e = s = 0
for p in glob.glob(d + '/*.xml'):
    r = ET.parse(p).getroot()
    t += int(r.get('tests')); f += int(r.get('failures')); e += int(r.get('errors')); s += int(r.get('skipped'))
ok = rc == '0' and f == 0 and e == 0
print(f"UNIT {kind} {'PASS' if ok else 'FAIL'} tests={t} failures={f} errors={e} skipped={s} {'' if ok else log}")
EOF
exit $RC
