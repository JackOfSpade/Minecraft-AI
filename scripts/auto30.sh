#!/bin/bash
# Automated test driver (fixes the root cause of "getting killed / failing to start"): one detached, serial, lock-protected, resumable process runs all rounds to completion.
#   Root cause: food_test.sh runs ./gradlew --stop on startup + a single MC instance holds the port exclusively -> any two overlapping runs kill each other;
#         previously the chat loop dispatched each round as a background task one at a time, and wakeup/user messages/background tasks would overlap -> mutual kills + rounds getting reclaimed.
#   Fix: (1) serial execution (this script runs one round after another, never concurrently) (2) mkdir atomic lock (a duplicate launch exits immediately instead of killing the running one)
#         (3) resumable (each round's result is written to the state file; if killed, a restart skips already-completed rounds -- interruption becomes harmless).
# Usage: nohup bash scripts/auto30.sh </dev/null >/tmp/auto30.out 2>&1 &   (the chat side only reads the state file)
set -u
cd "$(dirname "$0")/.." || exit 1

LOCK=/tmp/auto30.lock
STATE=reports/auto30_state.tsv
mkdir -p reports
# Atomic lock: mkdir failure = another instance is already running, exit immediately (idempotent, a duplicate launch is harmless, no mutual killing)
if ! mkdir "$LOCK" 2>/dev/null; then
  echo "[auto30] another instance running (lock $LOCK), exit."
  exit 0
fi
trap 'rmdir "$LOCK" 2>/dev/null' EXIT
[ -f "$STATE" ] || printf "round\tfeature\tresult\tsummary\n" > "$STATE"

# Round table: label | feature param | timeout seconds | seed (empty = current world)
# Design: one full pass of the geo/mining matrix each (canvas determinism) -> real with two seeds (verifies EXPLORE+R2 real combat strength)
#        -> one pass each of food/nav/assistant/material -> another round of geo/mining/real (flaky detection).
ROUNDS=(
  "14|geo_vertical+geo_slope+geo_overhang+geo_wall+geo_pocket+geo_deep+geo_lava|1500|"
  "15|geo_gravel+geo_fullinv+geo_rich+geo_water+geo_bonus+geo_flow+geo_lake+geo_guard|1800|"
  "16|dig_down+mine_exposed+ore_dig_buried+mine_to_iron+mine_buried_iron+mine_iron_pocket|1800|"
  "17|mine_with_mob+mine_iron_from_scratch+achieve_iron_ingot+achieve_gold_ingot+achieve_obsidian|1800|"
  "18|achieve_iron_pickaxe+achieve_diamond+geo_recover+geo_stockpile+geo_resume+explore_wood|2400|"
  "19|real_wood|2400|20260610"
  "20|real_iron|3000|20260610"
  "21|real_diamond|3600|20260610"
  "22|real_wood+real_food+real_wheat|3000|777"
  "23|real_iron+real_diamond|3600|777"
  "24|food+food_full+farm+farm_wheat_from_scratch|1800|"
  "25|food_farm+forage+farm_irrigate+cake|1800|"
  "26|nav_buried_escape+nav_unreachable+real_nav_far+nav_pillar_out|1800|"
  "27|geo_vertical+geo_slope+geo_overhang+geo_wall+geo_pocket+geo_deep+geo_lava|1500|"
  "28|geo_gravel+geo_fullinv+geo_rich+geo_water+geo_bonus+geo_flow+geo_lake+geo_guard|1800|"
  "29|dig_down+mine_exposed+ore_dig_buried+mine_to_iron+mine_buried_iron+mine_iron_pocket+mine_with_mob+mine_iron_from_scratch|2400|"
  "30|achieve_iron_ingot+achieve_gold_ingot+achieve_obsidian+achieve_iron_pickaxe+achieve_diamond+geo_recover+geo_stockpile+geo_resume+explore_wood|2700|"
)

run_one() {
  local label="$1" feature="$2" timeout="$3" seed="$4"
  # Resumable (awk reads the last record for this label): skip if already DONE/HASFAIL, run if ERR/no record.
  # Use awk instead of grep -P (BSD/GNU behavior differs) + never rewrite the file (append-only, to avoid concurrent read/mv truncation).
  if awk -F'\t' -v l="$label" '$1==l{r=$3} END{exit (r=="DONE"||r=="HASFAIL")?0:1}' "$STATE" 2>/dev/null; then
    echo "[auto30] round $label already done, skip."
    return
  fi
  echo "[auto30] === round $label: $feature (seed=${seed:-current}) ==="
  local out summary
  if [ -n "$seed" ]; then
    out=$(SEED="$seed" bash scripts/food_test.sh "$feature" "$timeout" 2>&1)
  else
    out=$(bash scripts/food_test.sh "$feature" "$timeout" 2>&1)
  fi
  summary=$(echo "$out" | grep -E "\[MinecraftAi Verify\] summary" | tail -1)
  summary="${summary#*summary }"
  [ -z "$summary" ] && summary="NO_SUMMARY(server error, see /tmp/mc_test_*.log)"
  local result="DONE"
  echo "$summary" | grep -q "FAIL" && result="HASFAIL"
  echo "$summary" | grep -q "NO_SUMMARY" && result="ERR"
  # Append-only (never rewrite the file): duplicate lines from reruns are harmless, readers (report/skip logic) all take the last entry.
  printf "%s\t%s\t%s\t%s\n" "$label" "$feature" "$result" "$summary" >> "$STATE"
  echo "[auto30] round $label -> $result: $summary"
}

# caffeinate prevents sleep (built-in, harmless if it fails); the whole run is serial
command -v caffeinate >/dev/null && caffeinate -is -w $$ &
for entry in "${ROUNDS[@]}"; do
  IFS='|' read -r label feature timeout seed <<< "$entry"
  run_one "$label" "$feature" "$timeout" "$seed"
done

echo "[auto30] ALL ROUNDS COMPLETE $(date '+%F %T')"
echo "ALLDONE" >> "$STATE"
