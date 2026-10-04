#!/usr/bin/env bash
# Baritone navigation regression evidence capture. This is intentionally an *unpaced GameTest* runner:
# it records elapsed sections on the server thread but does NOT establish production wall-clock route performance.
#
# usage: scripts/dev/nav_measurement.sh <repo-or-worktree> <outdir> [repetitions=5] [course-id ...]
# course ids: wall wallpick sealed steps pit lakedry lakenone lava cactus cliff3 cliff6
#             house forest moving twobots
#
# Every selected fixed geometry runs once per repetition on Baritone. The script keeps every new
# NAVCOURSE, NAVMEASURE and NAVPLAN row in a per-run directory rather than reducing repeated
# evidence to the final row as nav_courses_table.sh does.
set -euo pipefail

JAVA_COMMAND=()

resolve_java_command() {
  local path_java java_home converted candidate
  if path_java="$(command -v java 2>/dev/null)" && [ -n "$path_java" ]; then
    JAVA_COMMAND=("$path_java")
    return 0
  fi

  java_home="${JAVA_HOME:-}"
  if [ -n "$java_home" ]; then
    local -a candidates=("$java_home/bin/java" "$java_home/bin/java.exe")
    # A JAVA_HOME inherited from PowerShell can be a native C:\\ path; Git Bash's
    # cygpath form makes the same fallback usable on both hosts.
    if command -v cygpath >/dev/null 2>&1 && converted="$(cygpath -u "$java_home" 2>/dev/null || true)" \
        && [ -n "$converted" ] && [ "$converted" != "$java_home" ]; then
      candidates+=("$converted/bin/java" "$converted/bin/java.exe")
    fi
    for candidate in "${candidates[@]}"; do
      if [ -x "$candidate" ]; then
        JAVA_COMMAND=("$candidate")
        return 0
      fi
    done
  fi

  echo "Baritone measurement capture requires Java: add java to PATH or set JAVA_HOME to a JDK/JRE root with bin/java" >&2
  return 1
}

snapshot_current_artifacts() {
  # `prepareGameTestRun` deletes build/run/gameTest before every Gradle invocation. Capture the
  # three files that the just-finished process recreated; never calculate a line delta across
  # that deletion. The caller's run directory is already unique and outside the source tree.
  local results=$1 measurements=$2 plans=$3 run_dir=$4 source destination description
  local -a sources=("$results" "$measurements" "$plans")
  local -a names=(results.tsv measurements.tsv planner.tsv)
  local -a descriptions=("course result" "navigation measurement" "planner measurement")
  for ((i = 0; i < ${#sources[@]}; i++)); do
    source=${sources[$i]}
    destination="$run_dir/${names[$i]}"
    description=${descriptions[$i]}
    if [ ! -f "$source" ]; then
      echo "the just-run GameTest did not recreate $description artifact: $source" >&2
      return 1
    fi
    cp -- "$source" "$destination"
  done
}

self_test_snapshot_current_artifacts() {
  # Regression for Gradle's per-filter build/run deletion: a second run must preserve its own
  # freshly recreated rows rather than subtracting the deleted first-run line count.
  local source first second
  NAV_MEASUREMENT_SELF_TEST_TMP=$(mktemp -d "${TMPDIR:-/tmp}/minecraftai-nav-measurement.XXXXXX")
  trap 'rm -rf -- "$NAV_MEASUREMENT_SELF_TEST_TMP"' EXIT
  source="$NAV_MEASUREMENT_SELF_TEST_TMP/build/run/gameTest/nav_courses"
  first="$NAV_MEASUREMENT_SELF_TEST_TMP/first"
  second="$NAV_MEASUREMENT_SELF_TEST_TMP/second"
  mkdir -p "$source" "$first" "$second"
  printf 'NAVCOURSE\told\tbaritone\n' > "$source/results.tsv"
  printf 'NAVMEASURE\told\n' > "$source/measurements.tsv"
  printf 'NAVPLAN\told\n' > "$source/planner.tsv"
  snapshot_current_artifacts "$source/results.tsv" "$source/measurements.tsv" "$source/planner.tsv" "$first"
  rm -rf -- "$NAV_MEASUREMENT_SELF_TEST_TMP/build/run/gameTest"
  mkdir -p "$source"
  printf 'NAVCOURSE\tfresh\tbaritone\n' > "$source/results.tsv"
  printf 'NAVMEASURE\tfresh\n' > "$source/measurements.tsv"
  printf 'NAVPLAN\tfresh\n' > "$source/planner.tsv"
  snapshot_current_artifacts "$source/results.tsv" "$source/measurements.tsv" "$source/planner.tsv" "$second"
  grep -Fqx $'NAVCOURSE\tfresh\tbaritone' "$second/results.tsv"
  grep -Fqx $'NAVMEASURE\tfresh' "$second/measurements.tsv"
  grep -Fqx $'NAVPLAN\tfresh' "$second/planner.tsv"
  if grep -Fq 'old' "$second/results.tsv" "$second/measurements.tsv" "$second/planner.tsv"; then
    echo "self-test retained a pre-deletion artifact row" >&2
    return 1
  fi
}

self_test_java_home_fallback() {
  local saved_path="$PATH" saved_java_home="" java_home_was_set=0 fake_java ok=0
  if [ "${JAVA_HOME+x}" = x ]; then
    java_home_was_set=1
    saved_java_home="$JAVA_HOME"
  fi
  mkdir -p "$NAV_MEASUREMENT_SELF_TEST_TMP/no-java-path" "$NAV_MEASUREMENT_SELF_TEST_TMP/jdk/bin"
  fake_java="$NAV_MEASUREMENT_SELF_TEST_TMP/jdk/bin/java"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$fake_java"
  chmod +x "$fake_java"
  # command -v is a shell builtin, so a deliberately empty PATH safely proves that
  # JAVA_HOME/bin/java is selected when no PATH Java exists.
  PATH="$NAV_MEASUREMENT_SELF_TEST_TMP/no-java-path"
  JAVA_HOME="$NAV_MEASUREMENT_SELF_TEST_TMP/jdk"
  JAVA_COMMAND=()
  if resolve_java_command && [ "${JAVA_COMMAND[0]:-}" = "$fake_java" ]; then
    ok=1
  fi
  PATH="$saved_path"
  if [ "$java_home_was_set" = 1 ]; then
    JAVA_HOME="$saved_java_home"
  else
    unset JAVA_HOME
  fi
  [ "$ok" = 1 ]
}

if [ "${1:-}" = "--self-test" ]; then
  self_test_snapshot_current_artifacts
  self_test_java_home_fallback
  echo "nav_measurement artifact snapshot and JAVA_HOME fallback self-test: PASS"
  exit 0
fi

if [ $# -lt 2 ]; then
  sed -n '2,12p' "$0" >&2
  exit 2
fi

REPO="$(cd "$1" && pwd -P)"
OUT_INPUT="$2"
shift 2
resolve_java_command || exit 1
REPEATS=5
if [ $# -gt 0 ] && [[ "$1" =~ ^[0-9]+$ ]]; then
  REPEATS="$1"
  shift
fi
if [ "$REPEATS" -lt 1 ]; then
  echo "repetitions must be at least one" >&2
  exit 2
fi

# Resolve as much of a possibly new path as exists, without creating it first. That lets the
# clean-tree preflight happen before an accidentally in-repository evidence directory dirties it.
canonical_path_without_creating() {
  local candidate=$1 suffix=""
  case "$candidate" in
    /*) ;;
    *) candidate="$(pwd -P)/$candidate" ;;
  esac
  while [ ! -d "$candidate" ]; do
    suffix="/$(basename "$candidate")$suffix"
    candidate="$(dirname "$candidate")"
  done
  printf '%s%s\n' "$(cd "$candidate" && pwd -P)" "$suffix"
}

OUT="$(canonical_path_without_creating "$OUT_INPUT")"
case "$OUT" in
  "$REPO"|"$REPO"/*)
    echo "refusing an evidence output inside the source tree: $OUT" >&2
    exit 2
    ;;
esac
if [ -n "$(git -C "$REPO" status --porcelain=v1)" ]; then
  echo "Baritone measurement capture requires a clean source tree before creating its output directory" >&2
  exit 1
fi
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd -P)"
if [ -e "$OUT/README.txt" ] || [ -e "$OUT/runner.txt" ] || [ -d "$OUT/runs" ]; then
  echo "refusing to overwrite an existing P3 evidence bundle: $OUT" >&2
  exit 2
fi

ALL_COURSES=(wall wallpick sealed steps pit lakedry lakenone lava cactus cliff3 cliff6 house forest moving twobots)
COURSES=("$@")
if [ ${#COURSES[@]} -eq 0 ]; then
  COURSES=("${ALL_COURSES[@]}")
fi

filter_for() {
  local course=$1 suffix=""
  case "$course" in
    wall) suffix=wall_detour_baritone ;;
    wallpick) suffix=wall_detour_pickaxe_baritone ;;
    sealed) suffix=sealed_wall_baritone ;;
    steps) suffix=steps_baritone ;;
    pit) suffix=pit_crevasse_baritone ;;
    lakedry) suffix=lake_dry_path_baritone ;;
    lakenone) suffix=lake_no_dry_path_baritone ;;
    lava) suffix=lava_moat_bridge_baritone ;;
    cactus) suffix=cactus_field_baritone ;;
    cliff3) suffix=cliff_safe_drop_baritone ;;
    cliff6) suffix=cliff_unsafe_drop_baritone ;;
    house) suffix=house_door_baritone ;;
    forest) suffix=forest_baritone ;;
    moving) suffix=moving_target_baritone ;;
    twobots) suffix=two_bots_baritone ;;
    *) return 1 ;;
  esac
  printf 'navigation_course_game_tests_%s\n' "$suffix"
}

for course in "${COURSES[@]}"; do
  filter_for "$course" >/dev/null || { echo "unknown course id: $course" >&2; exit 2; }
done

line_count() {
  [ -f "$1" ] && wc -l < "$1" || printf '0\n'
}

copy_new_lines() {
  local source=$1 before=$2 destination=$3 total
  total=$(line_count "$source")
  if [ "$total" -gt "$before" ]; then
    sed -n "$((before + 1)),\$p" "$source" > "$destination"
  else
    : > "$destination"
  fi
}

git -C "$REPO" rev-parse HEAD > "$OUT/revision.txt"
git -C "$REPO" status --porcelain=v1 > "$OUT/status-start.txt"
git -C "$REPO" diff --check > "$OUT/diff-check-start.txt"
if [ -s "$OUT/status-start.txt" ]; then
  echo "Baritone measurement capture requires a clean source tree; see $OUT/status-start.txt" >&2
  exit 1
fi
"${JAVA_COMMAND[@]}" -version > "$OUT/java-version.txt" 2>&1
cat > "$OUT/README.txt" <<'EOF'
Baritone navigation measurement capture

This bundle is scale-one, unpaced Fabric GameTest evidence. The NAVMEASURE rows contain actual
elapsed server-thread durations for the complete AIPlayerEntity tick and complete MinecraftServer tick while the
course is active, plus NAVPLAN rows for Baritone inline admission.
It is not production wall-clock/pace proof: GameTests run ticks back-to-back and lack a normal
server driver/RCON fixture. It is a Baritone regression diagnostic, not an engine-selection gate.
EOF

RESULTS="$REPO/build/run/gameTest/nav_courses/results.tsv"
MEASUREMENTS="$REPO/build/run/gameTest/nav_courses/measurements.tsv"
PLANS="$REPO/build/run/gameTest/nav_courses/planner.tsv"
mkdir -p "$OUT/runs"
sequence=0

run_one() {
  local course=$1 repeat=$2 filter before_runner run_dir run_id
  filter=$(filter_for "$course")
  before_runner=$(line_count "$OUT/runner.txt")
  sequence=$((sequence + 1))
  run_dir=$(printf '%s/runs/%03d-%s-baritone-r%02d' "$OUT" "$sequence" "$course" "$repeat")
  mkdir -p "$run_dir"
  # The common runner serialises this worktree; retain any caller-supplied JVM flags while forcing
  # the fixture's only supported measurement mode.
  GT_JAVA_OPTS="${GT_JAVA_OPTS:-} -Dminecraftai.nav.measurement=scale1" GT_SLOTS=1 \
    bash "$REPO/scripts/dev/gametest.sh" "$REPO" "$OUT/runner.txt" "$filter"
  snapshot_current_artifacts "$RESULTS" "$MEASUREMENTS" "$PLANS" "$run_dir"
  copy_new_lines "$OUT/runner.txt" "$before_runner" "$run_dir/runner-status.txt"
  if ! awk -v filter="$filter" '
    $1 == "PASS" && $NF == filter { count++ }
    END { exit count == 1 ? 0 : 1 }
  ' "$run_dir/runner-status.txt"; then
    echo "the just-run filter did not report exactly one PASS: $filter (see $run_dir/runner-status.txt)" >&2
    exit 1
  fi
  if ! run_id=$(awk -F '\t' -v course="$course" '
    $1 == "NAVMEASURE" && $3 == "gametest_unpaced" && $4 == course && $5 == "baritone" &&
    $7 == "1" && $8 == "true" && $17 > 0 && $21 > 0 && $25 > 0 && $31 == 0 {
      count++; run_id = $6
    }
    END {
      if (count != 1 || run_id !~ /^[0-9]+$/) exit 1
      print run_id
    }
  ' "$run_dir/measurements.tsv"); then
    echo "missing or incomplete Baritone measurement evidence for $course; see $run_dir and $OUT/runner.txt" >&2
    exit 1
  fi
  if ! awk -F '\t' -v course="$course" '
    $1 == "NAVCOURSE" && $2 == course && $3 == "baritone" { count++ }
    END { exit count == 1 ? 0 : 1 }
  ' "$run_dir/results.tsv"; then
    echo "missing or ambiguous NAVCOURSE row for $course/baritone; see $run_dir/results.tsv" >&2
    exit 1
  fi
  if ! awk -F '\t' -v course="$course" -v run_id="$run_id" '
    $1 == "NAVPLAN" && $3 == "gametest_unpaced" && $4 == course && $5 == "baritone" && $6 == run_id { found = 1 }
    END { exit found ? 0 : 1 }
  ' "$run_dir/planner.tsv"; then
    echo "missing raw Baritone planner/admission row joined to NAVMEASURE run $run_id for $course; see $run_dir" >&2
    exit 1
  fi
}

echo "Baritone capture: unpaced GameTests only; this is not production wall-clock evidence."
for course in "${COURSES[@]}"; do
  for ((repeat = 1; repeat <= REPEATS; repeat++)); do
    run_one "$course" "$repeat"
  done
done

git -C "$REPO" status --porcelain=v1 > "$OUT/status-end.txt"
git -C "$REPO" diff --check > "$OUT/diff-check-end.txt"
if ! cmp -s "$OUT/status-start.txt" "$OUT/status-end.txt"; then
  echo "source tree changed during Baritone measurement capture; evidence is not comparable (see status-*.txt)" >&2
  exit 1
fi
echo "P3 raw capture complete: $OUT"
