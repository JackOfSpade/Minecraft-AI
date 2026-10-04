# Navigation course regressions

Status: Baritone is the only navigation engine. These are strict-survival regression courses, not a legacy-versus-Baritone comparison or a gate for selecting a default. Current engine and observation-boundary behavior is documented in [NAVIGATION_ENGINE.md](NAVIGATION_ENGINE.md).

## What the courses prove

Each course runs on its own fresh arena through the real `FollowTask` or `MoveTask` and must prove all of the following:

- the bot reaches a reachable goal, or safely holds on the near side when no dry route exists;
- it takes no damage, enters neither water nor lava, and does not break a block when a walkable route exists;
- a sealed wall is broken only when its course explicitly permits it; and
- Baritone owns the route. A missing Baritone instance or a follow route that never starts fails the test.

The production observation fence is exercised separately by the Baritone navigation, survival, capability, and water GameTests. It prevents a hidden ore or unexplored corridor from becoming a route target or traversable terrain. A previously observed exposed target may be retained only under the bounded, revalidated memory rules in [NAVIGATION_ENGINE.md](NAVIGATION_ENGINE.md).

## Courses

| Area | Course IDs |
|---|---|
| Detours and permitted breaking | `wall`, `wallpick`, `sealed`, `steps`, `pit` |
| Dry/water/lava safety | `lakedry`, `lakenone`, `lava`, `cactus`, `cliff3`, `cliff6` |
| Vanilla traversal | `house`, `forest` |
| Following behavior | `moving`, `twobots` |

The test methods retain their `_baritone` environments so the associated GameTest resources remain stable.

## Running the suite

Run the ordinary regression suite serially through the shared GameTest wrapper:

```bash
bash scripts/dev/gametest.sh . build/gametest-navigation-courses.txt "navigation_course_game_tests_*_baritone"
```

Every run emits a `NAVCOURSE` tab-separated record to the server log and to `<game dir>/nav_courses/results.tsv`. `scripts/nav_courses_table.sh` formats those rows as a compact Markdown table.

`scripts/dev/nav_measurement.sh` is an optional, clean-tree Baritone diagnostic capture. It preserves raw `NAVCOURSE`, `NAVMEASURE`, and `NAVPLAN` rows per repetition, but GameTests are unpaced and therefore do not establish production wall-clock performance. It never changes the navigation engine or broadens route knowledge.

## Historical note

The earlier paired-engine P3 comparison and default-flip decision are retired. The shipped policy is Baritone-only, fail-closed, and observation-fenced; an unavailable or inadmissible route leaves the bot in place with a typed outcome and audit log.
