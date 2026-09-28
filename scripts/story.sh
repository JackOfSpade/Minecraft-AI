#!/bin/bash
# Real-story harness: treats "the product itself" as the test -- real seeded world + real conversation layer (natural-language (Chinese) instruction -> LLM -> tools -> execution),
# watching exactly one metric: say one sentence -> it gets done alive -> time taken is reasonable. This is the metric closest to the user's vision ("chat and have it mine/forage/dig for me"),
# filling in the "real terrain x real brain" layer that the lab test suite can't measure.
#
#   WARNING billing: this hits the real LLM API; this script forces WITH_LLM=1. **This incurs API costs**, so it is not run by default and is not part of the gate.
#   Usage: MINECRAFTAI_LLM_API_KEY=xxx bash scripts/story.sh            (runs all stories x multiple seeds)
#          MINECRAFTAI_LLM_API_KEY=xxx bash scripts/story.sh llm_diamond  (single story, cheapest)
#   Output: reports/story_state.tsv (each line: story seed result summary); resumable (if killed and restarted, already-completed runs are skipped).
#   Detached run: nohup bash scripts/story.sh >/tmp/story.out 2>&1 &     (chat side only reads state)
set -u
cd "$(dirname "$0")/.." || exit 1
source scripts/lib/devloop.sh

if [ -z "${MINECRAFTAI_LLM_API_KEY:-}${DEEPSEEK_API_KEY:-}" ]; then
  echo "[story] requires MINECRAFTAI_LLM_API_KEY (or the old name DEEPSEEK_API_KEY; the real conversation layer goes through the API and incurs cost). See the script header for usage."
  exit 2
fi
export WITH_LLM=1

LOCK=/tmp/story.lock
STATE=reports/story_state.tsv
mkdir -p reports
devloop_acquire_lock "$LOCK" story
[ -f "$STATE" ] || printf "story\tseed\tresult\tsummary\n" > "$STATE"

# Story set: covers the four facets of the vision (movement/foraging/mining/deep-ore mining). Pass an argument to run only specific stories.
if [ $# -eq 0 ]; then
  STORIES=(llm_move llm_food llm_iron llm_diamond)
else
  STORIES=("$@")
fi
# Multiple seeds on real terrain (nether-like terrain / plains) -- the same story must complete alive across terrains to count as truly robust.
SEEDS=(20260610 3000)

run_story() {
  local story="$1" seed="$2"
  if awk -F'\t' -v s="$story" -v d="$seed" '$1==s&&$2==d{r=$3} END{exit (r=="PASS"||r=="FAIL")?0:1}' "$STATE" 2>/dev/null; then
    echo "[story] $story@$seed already run, skip."; return
  fi
  echo "[story] === $story @ seed=$seed ==="
  local out summary
  out=$(SEED="$seed" bash scripts/food_test.sh "$story" 6000 2>&1)
  summary=$(echo "$out" | grep -E "\[MinecraftAi Verify\] summary" | tail -1)
  summary="${summary#*summary }"
  [ -z "$summary" ] && summary="NO_SUMMARY(server error/missing key, see /tmp/mc_test_${story}_*.log)"
  local result="FAIL"; echo "$summary" | grep -q "${story}=PASS" && result="PASS"
  printf "%s\t%s\t%s\t%s\n" "$story" "$seed" "$result" "$summary" >> "$STATE"
  echo "[story] $story@$seed -> $result"
}

devloop_keep_awake
for story in "${STORIES[@]}"; do
  for seed in "${SEEDS[@]}"; do
    run_story "$story" "$seed"
  done
done
echo "[story] ALL STORIES COMPLETE $(date '+%F %T')"
echo "ALLDONE" >> "$STATE"
