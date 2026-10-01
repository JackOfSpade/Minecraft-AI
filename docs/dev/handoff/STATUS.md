# STATUS at the second hand-off (2026-10-01, cloud session -> Codex). READ THIS FIRST, then HANDOFF.md and RULES.md.

`main` is the only delivery branch. Everything marked done below is merged and verified on `main`; no patch-series-only work remains.

## What is merged on main (in order) and how it was verified
| job | what | evidence |
|---|---|---|
| tooling | `scripts/dev/gametest.sh` (an ALL run takes every server slot), `scripts/dev/unittest.sh` (serialised, retries Maven Central HTTP 429) | smoke GameTests root 10/10, wrapper 2/2 on Linux |
| du | gear rule (tools worst-first, non-tools best-first, use until it breaks), offhand ladder for all bots, durability chat warnings, PvP BOT managed `autoTotemEnabled=false`/`totemPriority=false` + wrapper `OffhandPolicy`, config backfill of absent managed keys | 2 independent reviews + fixes; wrapper ALL 63/63; targeted root classes green |
| hx | GameTest harness isolation (one test per batch, unique mocks, sweeper, world restorer, light/scene sync), WalkToController no longer hops/tramples farmland, OreDigTask drop recovery | root ALL 1183/1183 twice on the rebased head; 1 independent review (its last product changes, `WalkToController.entryTop` and the OreDig drop recovery, were NOT independently reviewed) |
| n1 | wrapper nits (see `nits_triage.md` partition N1) | independent review + fixes; wrapper unit 2011, wrapper ALL 64/64 |
| pf | whole root suite runs with companion perception ON (`MINECRAFTAI_HARNESS_PERCEPTION=off` is the diagnosis escape), fail-safe scan, throttled scan, attack tool busy/noticed-only, cover-peek knock-back fix | independent review + fixes; root ALL 1188/1188 on the final head (the post-review fixes and the peekaboo/dig_down fix were verified by that ALL, not separately reviewed) |

Unit suites on main: root 2658, wrapper 2011, all green. Run them with `scripts/dev/unittest.sh .` and `scripts/dev/unittest.sh --wrapper .`.

## Completed after the second hand-off
1. **sh (companion shield blocking) — COMPLETE:** the reviewed 0001–0005 content was reconciled on the perception-ON head, including
   component-backed shields, task-owned GuardTask melee rhythm, truthful watched/heard projectile handling, real visible-TNT reaction,
   Guardian beam coverage, and the shield-handoff watchdog fixes. Two independent reviews found no remaining P1/P2 issue; the delta
   review specifically verified the final exact-vanilla gates and schema-5 checkpoint coverage. The narrow post-`du` reconciliation only
   classifies a shield by its vanilla `BLOCKS_ATTACKS` component; it preserves `du`'s equipment selection and ownership policy.
   The specialised TNT, creeper, and guardian models decline modded subclasses rather than assuming vanilla fuse, radius, or beam mechanics.
   HIDDEN_BLOCK_SCAN remains an explicit operator capability override; strict mode and scan-failure fallback use ordinary observable facts.
   Heard-shot association is exact-type, physically back-projected, and uses vanilla vibration travel time; Guard owns its melee rhythm,
   while Hunt deliberately remains passive; following/escape/regroup/retreat block only already-in-flight projectiles to preserve sprint.
   Evidence: serial root fast suite 2714/2714 after all five source sets compiled; focused final contracts 18/18; live perception-ON
   GameTests all passed — `shield_blocking_game_tests_*` 17/17, `*shield*` 25/25, `combat_hardening_*` 18/18,
   `danger_watcher_low_health_*` 47/47, `creeper_defense_*` 8/8, `evade_*` 2/2, `follow_escort_*` 10/10, `*pace*` 41/41,
   `ranged_weapon_*` 13/13, `hostile_bot_targeting_*` 17/17, `companion_perception_*` 15/15, `gear_worst_first_*` 26/26,
   `auto_eat*` 4/4, and `hunger*` 3/3. Spec: `specs/sh_shield_blocking.md`; RULES.md "Shield use".

## Still to do
1. **Phase 2 remaining**: N2 (root product nits: `nits_triage.md` partitions N2a combat, N2b mining, N2c nav/other) and N3 (root test
   nits). Re-verify each item against current main first (many are obsolete; the triage was done before du/hx/pf merged: items marked
   "after tmp/du/hx/pf merges" must be re-checked). Item 184 decision: skip the calm-warden eat deferral when the need is urgent.
   W: production pathfinder warm-up at server start (hx report: the GameTest harness scales A*'s 50 ms wall-clock budget by 40 via
   `AStarPathfinder.setHarnessTimeScale`, so GameTests cannot catch a too-slow pathfinder; the warm-up job must add a real check).
   P3: switch `nav.engine` default from `legacy` to `baritone` (docs/NAVIGATION_ENGINE.md, NAVIGATION_BARITONE_PLAN.md), measure both engines
   (per-tick time, route time, pace and capability suites).
2. **Phase 3**: adversarial review since `770ccd1` (lenses: correctness, user rules, concurrency/tick cost, test validity; include the
   unreviewed pieces listed above), root unit + ALL twice green, wrapper unit + ALL twice green, document `scripts/dev` in
   docs/TESTING_AND_EVIDENCE.md, delete `docs/dev/handoff/`, push main (only branch).

## Environment facts learned (Linux cloud sandbox: 4 CPUs, 15 GB RAM)
- No download host is blocked. Maven Central answers HTTP 429 in bursts; `unittest.sh` retries; `gametest.sh` reports ERROR and can be re-run.
  A user-level `~/.gradle/init.d/mavencentral-mirror.gradle` pointing mavenCentral at repo1.maven.org was used (not committed).
- `scripts/dev/fetch-upstream-mods.sh` downloads PvP BOT/HeroBot (sha1-checked) to `~/.cache/upstream-mods` (never commit them).
- GameTest filters match the test METHOD name (squashed single-letter words); most globs must start with `*`. One filter per server start.
- `docs/dev/handoff/WORKER_COMMON.md` and `REVIEWER_COMMON.md` are the briefs used for every worker/reviewer (adapt paths).
- Run a root ALL with nothing else running (`GT_SLOTS=2 scripts/dev/gametest.sh . out.txt ALL`).
- Commit trailers used: `Co-Authored-By: Claude <model> <noreply@anthropic.com>`; author `Claude <noreply@anthropic.com>`.

## Decisions for the user (hand back)
- **Harness-only mixin** `InventoryHelperDevShimMixin` (src/gametest of the wrapper) into PvP BOT's `InventoryHelper`: kept, because it only maps two
  field names so the Mojang-named dev server can run PvP BOT, is not in the jar and changes no behaviour; `NoUpstreamMixinTest` guards that no other
  upstream mixin exists. RULES say "no mixins into their classes": the user must confirm this exception.
- Existing `pvpbot_inhabitants.json`: the startup command "pvpbot settings auto-target true" is overridden by managed settings; suggest removing it.
  Managed keys absent from a present `pvpbotSettings` block are now backfilled with the shipped values (explicit `null` = leave PvP BOT alone).
- Optional leftovers: NITS 59 (a seen bot rejoined by PvP BOT after goneConfirmTicks while its record is DORMANT is a second live copy), 66, 76, 78.

## LOCAL items (need the user's PC)
(a) pack-compat GameTest run with the profile's ~55 mods (`GT_EXTRA_MODS`, docs/TESTING_AND_EVIDENCE.md) before P3 and deploy; (b) any GameTest not run
in the cloud (none known: everything above ran on a real server); (c) deploy with `scripts/deploy_profile.sh` while the game is closed (docs/DEPLOY_LOCAL.md);
(d) delete local worktrees in `C:\mcw` and local `tmp/*` branches; (e) update Claude's local memory.
