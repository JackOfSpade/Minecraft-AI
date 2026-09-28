#!/bin/bash
# Probe: verify a headless dedicated server can start + console commands can be injected via FIFO.
# Purpose: feasibility verification for the automated-testing harness (not the final harness).
set -u
cd /Users/zoyluo/codes/zoyprojects_github/mc_aiplayer || exit 1
LOG=/tmp/mc_harness.log
FIFO=/tmp/mc_harness.fifo
: > "$LOG"
rm -f "$FIFO"; mkfifo "$FIFO"

# Keep the FIFO write end open at all times, otherwise the server reads EOF and closes the console
sleep 100000 > "$FIFO" &
HOLDER=$!

# Headless server; --no-daemon lets stdin pass straight through to the server console
./gradlew --no-daemon --console=plain runServer < "$FIFO" >> "$LOG" 2>&1 &
SRV=$!
echo "[harness] server pid=$SRV holder=$HOLDER"

READY=0
for i in $(seq 1 480); do
  if grep -q 'Done (' "$LOG" 2>/dev/null; then READY=1; echo "[harness] READY at ${i}s"; break; fi
  if ! kill -0 "$SRV" 2>/dev/null; then echo "[harness] SERVER EXITED EARLY at ${i}s"; break; fi
  sleep 1
done

if [ "$READY" = 1 ]; then
  echo "[harness] === injecting test commands ==="
  echo "say HARNESS_PING_123" > "$FIFO"; sleep 2
  echo "minecraftai spawn Bob" > "$FIFO"; sleep 6
  echo "minecraftai list" > "$FIFO"; sleep 3
  echo "[harness] === command injection done ==="
fi

echo "[harness] stopping server"
echo "stop" > "$FIFO"; sleep 12
kill "$SRV" 2>/dev/null
kill "$HOLDER" 2>/dev/null
rm -f "$FIFO"
echo "[harness] FINISHED ready=$READY"
