#!/usr/bin/env bash
# Turns the NAVCOURSE result lines of the navigation course GameTests into the markdown table of docs/NAVIGATION_COURSES.md.
#   usage: scripts/nav_courses_table.sh <results.tsv | server log | gt console log> [...]
# The tests append one tab separated line per run to <game dir>/nav_courses/results.tsv (build/run/gameTest/nav_courses/results.tsv
# for ./gradlew runGameTest) and log the same line ("NAVCOURSE<TAB>course<TAB>engine<TAB>reached<TAB>ticks<TAB>damage<TAB>broken
# <TAB>placed<TAB>water ticks<TAB>lava ticks<TAB>reason<TAB>detail"). A later line for the same course and engine replaces an earlier one.
set -eu
[ $# -ge 1 ] || { echo "usage: $0 <results.tsv | log> [...]" >&2; exit 2; }
grep -h -P "NAVCOURSE\t" "$@" | sed -E 's/^.*NAVCOURSE\t/NAVCOURSE\t/' | awk -F'\t' '
BEGIN {
  n = split("wall wallpick sealed steps pit stairs lakedry lakenone lava cactus cliff3 cliff6 house gate ladder forest moving twobots long", order, " ")
}
{ key = $2 SUBSEP $3; row[key] = $4 "\t" $5 "\t" $6 "\t" $7 "\t" $8 "\t" $9 "\t" $10 "\t" $11 }
END {
  print "| course | engine | reached | ticks | damage | broken | placed | water ticks | failure reason |"
  print "|---|---|---|---|---|---|---|---|---|"
  for (i = 1; i <= n; i++) {
    eng = "baritone"
    key = order[i] SUBSEP eng
    if (!(key in row)) continue
    split(row[key], f, "\t")
    printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s |\n", order[i], eng, (f[1] == "true" ? "y" : "n"), f[2], f[3], f[4], f[5], f[6], f[8]
  }
}'
