#!/bin/bash
# Target reliability measurement: run the same verify target across multiple seeds, N runs each, and tally the pass rate plus a per-failure stage profile.
# Addresses "real_* is flaky / only breaks in a specific chunk": first quantify how often it fails and at which stage, then use /minecraftai snapshot to capture the live state, freeze it into a deterministic scenario, and actually fix it.
# Same design as auto30.sh: serial execution + mkdir lock + resumable (via awk) + caffeinate.
# Usage: nohup bash scripts/reliability.sh [feature] [runs_per_seed] [timeout_s] </dev/null >/tmp/reliability.out 2>&1 &
set -u
cd "$(dirname "$0")/.." || exit 1
FEATURE="${1:-real_diamond3}"; RUNS="${2:-2}"; TIMEOUT="${3:-2200}"
# SEEDS_OVERRIDE="s1 s2 ..." swaps the seed set (default is three seeds); STATE_FILE swaps the state file (use a separate file for random batch tests so it doesn't mix with existing data).
if [ -n "${SEEDS_OVERRIDE:-}" ]; then read -r -a SEEDS <<< "$SEEDS_OVERRIDE"; else SEEDS=(20260610 3000 777); fi
LOCK=/tmp/reliability.lock; STATE="${STATE_FILE:-reports/reliability_state.tsv}"
mkdir -p reports
mkdir "$LOCK" 2>/dev/null || { echo "[reliability] another instance running, exit."; exit 0; }
trap 'rmdir "$LOCK" 2>/dev/null' EXIT
[ -f "$STATE" ] || printf "feature\tseed\trun\tresult\treason\tstage\tsummary\n" > "$STATE"
classify_stage() {
  case "$1" in
    PASS) echo pass ;;
    *no_resource_nearby*|*gather_timeout*|*GOAL_UNREACHABLE*) echo wood_gather ;;
    *ore_dig_no_progress*|*ore_dig_stall*|*no_progress*) echo ore_dig ;;
    *descend*|*DescendTo*) echo descend ;;
    *lava*|*burn*|*on_fire*) echo lava ;;
    *smelt*) echo smelt ;;
    *death*|*died*|*killed*) echo death ;;
    *timeout*) echo timeout ;;
    *aborted*) echo aborted ;;
    *NO_SUMMARY*|*NO_RESULT*) echo server_err ;;
    *) echo other ;;
  esac
}
run_one() {
  local seed="$1" run="$2"
  if awk -F'\t' -v f="$FEATURE" -v s="$seed" -v r="$run" '$1==f&&$2==s&&$3==r{res=$4} END{exit (res=="PASS"||res=="FAIL")?0:1}' "$STATE" 2>/dev/null; then
    echo "[reliability] $FEATURE#$seed#$run skip"; return; fi
  echo "[reliability] === $FEATURE#$seed#$run (t=${TIMEOUT}s) ==="
  local out summary reason result stage
  out=$(SEED="$seed" bash scripts/food_test.sh "$FEATURE" "$TIMEOUT" 2>&1)
  summary=$(echo "$out" | grep -E "\[MinecraftAi Verify\] summary" | tail -1); summary="${summary#*summary }"
  if [ -z "$summary" ]; then summary="NO_SUMMARY"; result=ERR; reason=NO_SUMMARY
  elif echo "$summary" | grep -q FAIL; then result=FAIL; reason=$(echo "$summary" | sed -n 's/.*FAIL:\([^,}]*\).*/\1/p'); [ -z "$reason" ] && reason=unknown_fail
  else result=PASS; reason=PASS; fi
  stage=$(classify_stage "$reason")
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\n" "$FEATURE" "$seed" "$run" "$result" "$reason" "$stage" "$summary" >> "$STATE"
  echo "[reliability] $FEATURE#$seed#$run -> $result [$stage]: $reason"
}
command -v caffeinate >/dev/null && caffeinate -is -w $$ &
# Batch run of the same code across multiple seeds: do one clean compile first, then each run reuses it via SKIP_COMPILE (code is frozen, saving N-1 full recompiles).
echo "[reliability] compiling once (clean) before batch ..."
./gradlew --stop >/dev/null 2>&1
if ./gradlew --no-daemon --rerun-tasks --no-build-cache clean classes >/dev/null 2>&1; then
  export SKIP_COMPILE=1; echo "[reliability] prebuilt OK, runs will SKIP_COMPILE"
else
  echo "[reliability] PREBUILD FAILED, exit"; exit 1
fi
for seed in "${SEEDS[@]}"; do for run in $(seq 1 "$RUNS"); do run_one "$seed" "$run"; done; done
echo "[reliability] ===== SUMMARY $FEATURE ====="
awk -F'\t' -v f="$FEATURE" 'NR>1&&$1==f{key=$2"#"$3;res[key]=$4;stage[key]=$6} END{t=0;p=0;for(k in res){t++;if(res[k]=="PASS")p++;c[stage[k]]++} if(t==0){print "  no runs";exit} printf "  runs=%d pass=%d rate=%.0f%%\n",t,p,(p*100.0/t); for(st in c)printf "    %-12s %d\n",st,c[st]}' "$STATE"
echo "ALLDONE" >> "$STATE"
