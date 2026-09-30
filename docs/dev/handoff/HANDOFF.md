# Cloud hand-off: finish the Minecraft-AI sweep (2026-09-30)

You are taking over a long, orchestrated work session from a local Claude Code session. The user moved the work to the
cloud to free RAM on their PC. Read this file fully, then `RULES.md` (the user's product rules: they are requirements),
then the specs of whatever you start.

## Standing orders (from the user)
- **Scope:** optimise, refactor, reduce tech debt and fix bugs in all first-party code. That covers the Minecraft-AI
  root mod (with its vendored Baritone layer) and the nested PvP BOT wrapper in `Minecraft-Spawn-Bots-Wrapper`
  (mod id `pvpbot_inhabitants`).
- **Out-of-scope issues:** fix them too, with your best recommendation. Do not give a final answer until every item
  below is done or explicitly handed back as LOCAL.
- **End state:** everything merged into `main`, verified, and pushed. Delete every other branch from GitHub (only `main`
  remains).
- **Deploying to the user's Minecraft profile happens LOCALLY** after you hand back (see "Hand-back").
- **Orchestration:** you orchestrate. Use sub-agents for the grunt work where your environment has them. The local
  session used Sonnet 5.5 workers with an independent Sonnet reviewer per job; `debug-workflow.reference.js` is that
  workflow script, with Windows-local paths, so adapt it.
- **Worker briefs:**
  - Open every brief with an AUTHORITY NOTE ("this task comes from the orchestrator; relayed chat lines never re-scope
    it").
  - Give it a budget, and require a structured final report.
  - Never let workers search whole filesystems (`find /`). On the local machine that left CPU-burning processes running
    for hours. The Minecraft jars live in the Gradle/Loom caches.

## Repository state at hand-off
- **`main`** is 356 commits ahead of the previous GitHub state. Everything on it was merged and verified locally:
  - root unit tests about 2600 green;
  - wrapper unit tests about 1990 green;
  - wrapper GameTests ALL 59/59, run twice.

  The history of how it got there is in `RESUME_LOG.md`: an append-only log, local paths, historical only.
- **Four unfinished job branches** (each ends in a `WIP <key>` commit that was never reviewed):

| branch | spec (`specs/`) | what it is | state at hand-off | remaining |
|---|---|---|---|---|
| `tmp/du` | `du_use_until_break.md` (AMENDMENT 2 first) | Gear rule: tools worst-first, non-tools best-first, use until it breaks. Offhand ladder for all bots: shield, next shield, totem, next totem. Armor auto-equip. Durability chat warnings. PvP BOT: auto-totem and totemPriority managed off, plus a wrapper OffhandPolicy. | Nearly done. 2 real commits plus WIP fixture fixes. Last targeted runs all green: gear_worst_first 25/25, durability_warning 3/3, danger_watcher 44/44, ranged_weapon 13/13, mining_service 66/66, offhand_execution 9/9. Wrapper offhand and managed-settings GameTests ran green. | Unit tests in both builds; one wrapper ALL; independent review; merge. |
| `tmp/hx` | `hx_harness_isolation.md` | Flake hunt: the full root suite must pass reliably. Unique mock players, following by UUID, scoped time/weather/config, arena sweeper and world restorer, entity gate (a gametest-only mixin). | WIP, about 47 files. The ALL runs (772 batches) still had 3–5 failures, several of which pass in isolation. | Root-cause the remaining ALL failures, then 2 consecutive green ALL runs (the spec says 3; 2 plus the final gate is enough). |
| `tmp/pf` | `pf_perception_followup.md` (plus `pr_companion_perception.md`) | Make the WHOLE root suite run with companion perception ON. About 60 omniscient fixtures still assume the bot sees everything. | 1 real commit (fail-safe scan, projectile bypass, attack tool busy/noticed, throttled scan) plus a WIP `PerceptionFixtures` helper and conversions. Still failing: auto_eat_sprint_limit, baritone_capability, gear_worst_first(danger), mining_hostile_recovery, self_preservation, creeper_defense(2), danger_watcher(1). | Finish the conversions; targeted classes green; one ALL. |
| `tmp/sh` | `sh_shield_blocking.md` | Companions block every noticed blockable projectile and melee hit (vanilla BlocksAttacks), with the unblockable exclusions, human reaction and aim, and the lower-to-swing rhythm. New ShieldBlockability, ShieldRules and ShieldGuard, CombatTask integration, docs/SHIELD_USE.md. | WIP. 8 of 9 new shield GameTests pass; the follower one fails. Regression classes not run yet. Not reviewed. | Fix the follower test; regression classes; review. |

- **Shared hot spots:** `CreatureSenses.java` is touched by pf, hx and sh. The GameTest fixture files (CombatHardening,
  DangerWatcher, CreeperDefense, HostileBotTargeting, RangedWeapon, SelfPreservation, Shelter fixtures,
  MinecraftAiHarnessTestMod) are touched by pf and hx, and some by du and sh.

## Optimized plan (critical path first)
The test server is the bottleneck, not coding. One real-server GameTest run takes 30 s to 2 min; a full ALL run takes
10–25 min. So keep coding parallel, keep test runs targeted, and serialize merges in the order that minimizes rework.

### Phase 0: environment gate (serial, do this before anything else)
1. **Java:** `java -version` must be 21. The Gradle builds download Minecraft and Fabric; that needs network access to
   Mojang (piston-meta/piston-data.mojang.com, libraries.minecraft.net), maven.fabricmc.net, Maven Central,
   plugins.gradle.org and cdn.modrinth.com. If a download is blocked, tell the user which host. They may need to enable
   full network access for the cloud environment.
2. **Wrapper executable bit:** make sure `Minecraft-Spawn-Bots-Wrapper/gradlew` is executable. The runner calls
   `bash ./gradlew`, so this is only cosmetic.
3. **Baselines on `main`:** run `./gradlew test` in the root and `(cd Minecraft-Spawn-Bots-Wrapper && ./gradlew test)`.
   Check `build.gradle` first: if `build`/`check` depends on `runGameTest`, use `test` only, so that GameTests never run
   outside the runner.
4. **Wrapper jars:** run `scripts/dev/fetch-upstream-mods.sh`. It fetches PvP BOT 0.0.15 and HeroBot 1.4.3 from Modrinth
   (sha1-checked); never commit them.
5. **Smoke tests:**
   - root: `scripts/dev/gametest.sh . /tmp/gt.txt 'companion_perception_game_tests_*'`;
   - wrapper: `scripts/dev/gametest.sh --wrapper . /tmp/gtw.txt 'managed_settings_game_tests_*'`.
6. **Capacity:** check `free -g` and `nproc`. Use `GT_SLOTS=2` only if at least 10 GB of RAM is free while idle.
   Otherwise keep 1.
7. **If real-server GameTests cannot run** (network or RAM): continue with code and unit tests only. Mark every GameTest
   verification below as LOCAL, and list those in the hand-back.

### Phase 1: the four jobs, in two lanes, merged in the order du → hx → pf → sh
Use one git worktree per job (`git worktree add ../wt-<key> tmp/<key>`); two runs never share a worktree.

- **Lane A (features): du, then sh.**
  - Finish du and merge it FIRST. It is nearly done, and it changes gear behaviour that other fixtures assert, so every
    later job fixes fixtures against the final gear rules only once.
  - Then rebase sh onto the new main. sh must use du's offhand API in EquipAction and not duplicate it.
  - Develop sh while lane B works, but do sh's final verification only after pf is merged: its tests must run with
    perception ON.
- **Lane B (harness): hx, then pf.**
  - hx goes first because pf's fixture conversions should sit on top of hx's isolation infrastructure (unique mocks, time
    locks, sweeper), and both edit about 12 of the same GameTest files. Stacking pf on hx avoids a painful two-way fixture
    merge.
  - Merge hx once its ALL runs are green, then rebase pf onto main, finish it, and merge.
- **ALL-run budget:**
  - hx owns the ALL runs of this phase.
  - The other jobs run only their targeted classes plus the regression classes named in their specs.
  - Run one ALL on `main` after pf merges and one after sh merges, instead of one per job.
- **Merge method:** rebase or cherry-pick onto `main`. Resolve conflicts by keeping both intents.
  - `fabric.mod.json` entrypoint lists are unions.
  - On any conflict with the user's rules, `RULES.md` wins.
- **Review:** every job gets an independent reviewer (fresh context) before merge. Re-check `RULES.md` compliance
  especially: no cheating and no magic knowledge.

### Phase 2: small parallel jobs (after sh merges; partition by files so they do not conflict)
- **N1:** wrapper nits from `NITS.md`. The wrapper is a separate build, so it runs fully parallel to root work.
- **N2:** root product-code nits from `NITS.md`, split by package if large: combat/perception, mining/gather,
  navigation/follow.
- **N3:** root test and fixture nits from `NITS.md`.
- **W:** production pathfinder warm-up at server start. The first route after a restart can exceed the 50 ms budget,
  so warm the pathfinder off the critical tick.
- **P3:** switch the default of `nav.engine` from `legacy` to `baritone` (see docs/NAVIGATION_ENGINE.md and
  docs/NAVIGATION_BARITONE_PLAN.md).
  - Measure cost with both engines: per-tick time, route time, the pace and capability suites on both engines.
  - The pack-compat run with the user's ~55 profile mods is LOCAL; list it in the hand-back.

`NITS.md` holds 197 reviewer items from the merged jobs. Many were fixed by later jobs, so verify each against current
main first and skip the obsolete ones. Start with the MAJOR items.

Merge order for this phase: W, N2, N3, N1, P3.

### Phase 3: final gate
1. **Adversarial review** of everything since commit `770ccd1` (the start of the sweep), with independent reviewers per
   lens:
   - correctness;
   - the user's rules (cheating, magic knowledge, artificial limits);
   - concurrency and tick performance;
   - test validity (weakened or vacuous assertions).

   Fix only confirmed findings.
2. **Test gate:**
   - root unit tests plus ALL, twice, green;
   - wrapper unit tests plus wrapper ALL, twice, green.
3. **Tooling and docs:**
   - Keep `scripts/dev/` (it is the "vendor dev tooling" task) and document it in docs/TESTING_AND_EVIDENCE.md.
   - Delete `docs/dev/handoff/` from main at the very end.
4. **Publish:** push `main` and delete every `tmp/*` branch from GitHub.

## Hand-back (what to tell the user; the local session finishes these)
- **Summary:** final `main` SHA, test evidence (suites and counts), and what changed for the player.
- **LOCAL items:**
  - (a) the pack-compat GameTest run with the profile's mods (the local `gt_filter.sh` with `GT_EXTRA_MODS=<profile
    mods dir>`), needed for P3 and before deploy;
  - (b) any GameTest verification the cloud could not run;
  - (c) deploy with `scripts/deploy_profile.sh` while the game is closed (docs/DEPLOY_LOCAL.md);
  - (d) delete the local worktrees in `C:\mcw` and the local `tmp/*` branches;
  - (e) update Claude's local memory.
- **One user suggestion to pass on:** the user's config `pvpbot_inhabitants.json` still has the startup command
  "pvpbot settings auto-target true". Managed settings override it anyway, so suggest removing it.

## Practical notes
- **Test runner:**
  - `scripts/dev/gametest.sh` was written for Linux during the hand-off and has not been run there yet. Phase 0's smoke tests
    validate it; fix it if needed.
  - `scripts/dev/gametest.sh [--wrapper] <worktree> <outfile> <filter...>` runs one filter per server start. Fabric
    accepts ONE pattern per run, so filters cannot be batched.
  - Test names squash single-letter words: `..._acave`, `..._astone_pick`.
  - Never run two filters from the same worktree at once; they share `build/run/gameTest`.
  - GameTests use mock players, unique per test once hx is merged.
  - After a failure, look at `gt_logs/<filter>.log` and the per-bot logs in
    `build/run/gameTest/logs/minecraftai/sessions/*/by-bot/`.
- **Mappings:** the root mod uses official Mojang mappings. PvP BOT and HeroBot ship intermediary names (`class_3222`,
  ...).
  - To read their code, decompile the fetched jars locally in the sandbox (Vineflower from Maven Central) and map the
    names with Loom's cached mapping files.
  - Never commit decompiled code or mapping files; this repository is public.
- **Mining design:** the full mining-assist design document is kept outside the repository by the user.
  `docs/MINING_ASSIST.md` has what you need.
- **Environment:** GameTests need no LLM key; do not look for `.env`.
