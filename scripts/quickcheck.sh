#!/bin/bash
# Quick verification: rule out all gradle/loom caches, confirm runServer is running the latest class containing food.
# Only check whether food is recognized by verify (don't wait for the full run to complete).
set -u
cd "$(dirname "$0")/.." || exit 1
LOG=/tmp/mc_quickcheck.log
FIFO=/tmp/mc_quickcheck.fifo
: > "$LOG"
echo "[qc] killing gradle daemons + wiping build (ruling out all caches)"
pkill -9 -f "GradleDaemon" 2>/dev/null
pkill -9 -f "runServer" 2>/dev/null
pkill -9 -f "loom-cache/launch" 2>/dev/null
sleep 1
rm -rf build 2>/dev/null
rm -f run/world/minecraftai/bots.json 2>/dev/null
rm -f "$FIFO"; mkfifo "$FIFO"
sleep 100000 > "$FIFO" & HOLDER=$!

# One invocation: clean + runServer, full compile (no incremental staleness) + boot
./gradlew --no-daemon --console=plain clean runServer < "$FIFO" >> "$LOG" 2>&1 & SRV=$!
echo "[qc] server pid=$SRV, building+booting ..."
READY=0
for i in $(seq 1 600); do
  grep -q 'Done (' "$LOG" 2>/dev/null && { READY=1; echo "[qc] READY at ${i}s"; break; }
  kill -0 "$SRV" 2>/dev/null || { echo "[qc] SERVER DIED"; tail -15 "$LOG"; break; }
  sleep 1
done

if [ "$READY" = 1 ]; then
  sleep 2
  echo "minecraftai spawn QCBot" > "$FIFO"; sleep 5
  echo "minecraftai verify food" > "$FIFO"; sleep 15
fi

echo "[qc] ===== whether food is recognized ====="
if grep -q "unknown feature" "$LOG" 2>/dev/null; then
  echo "[qc] RESULT = STILL_UNKNOWN (food didn't make it into the running class)"
elif grep -qE "goal_plan.*Food|MinecraftAi Verify.*food|step=.*Hunt|step=.*Gather" "$LOG" 2>/dev/null; then
  echo "[qc] RESULT = FOOD_RECOGNIZED (verify food started running!)"
else
  echo "[qc] RESULT = INCONCLUSIVE"
fi
grep -E "unknown feature|goal_plan|MinecraftAi Verify" "$LOG" 2>/dev/null | tail -4

echo "stop" > "$FIFO"; sleep 8
kill "$SRV" 2>/dev/null; kill "$HOLDER" 2>/dev/null; rm -f "$FIFO"
echo "[qc] DONE"
