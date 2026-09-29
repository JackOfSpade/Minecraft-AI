# Baritone in Minecraft-AI

Baritone (the pathfinder and its movement execution: doors, ladders, pillaring, parkour, jump timing, block breaking and
placing) is used as a library inside the mod. Its source is vendored **pristine**, every change we need is a small numbered
patch that is replayed at build time, and all code that adapts Baritone to our server-side bots is new code in the mod.
Upgrading Baritone is therefore "import the new tag, replay the patches, fix what reports a conflict".

```
third_party/baritone/             upstream Baritone, byte-identical (v1.17.0 / MC 1.21.11), never edited
        |  tools/baritone/BaritoneSource.java  (run by Gradle: generateBaritoneSources, or tools/baritone/apply.sh)
        |    1. copy src/api, src/main, src/test
        |    2. remove everything in tools/baritone/exclude.txt          (by list, nothing is deleted from third_party)
        |    3. add tools/baritone/overlay/**                            (new files: hooks, stubs for excluded classes)
        |    4. git apply tools/baritone/patches/NNNN-*.patch, in order  (strict; fails naming the patch)
        |    5. scan the result for client references                    (loud early check before javac)
        v
build/baritone-src/               generated, git-ignored
        |  Gradle source sets 'baritone' (api + main) and 'baritoneTest' (upstream's own JUnit 4 tests)
        v
Minecraft common jar only         no net.minecraft.client on the classpath: a leftover client reference cannot compile
        |
        v
src/main/java/.../minecraftai/baritone/   glue (ours): ServerPlayerContext, ServerPlayerController, LoadedChunkSnapshot, BaritoneHost
src/main/java/.../minecraftai/mixin/      Baritone*Mixin, ServerChunkCacheBaritoneMixin, ChunkMapVisibleChunksAccessorMixin
```

## Files

| Path | What |
|---|---|
| `third_party/baritone/UPSTREAM.md`, `MANIFEST.txt` | version, commit, upstream blob SHA of every vendored file (`apply.sh verify` recomputes them; the generator refuses to run on an edited vendor tree) |
| `tools/baritone/exclude.txt` | what is vendored but not compiled, with the reason per group. Every entry has to match something, so an upstream move fails the build instead of silently compiling more or less |
| `tools/baritone/overlay/` | new files added to the generated tree: `HostEnvironment` (hooks: game directory, game-thread hop, loot level, worker executor, daemon threads) and stubs that stand in for excluded client-only classes (`RenderEvent`, `PathRenderer`, `SelectionRenderer`, `SchematicaHelper`, `LitematicaHelper`). An overlay file that shadows an upstream file must be in `exclude.txt` first |
| `tools/baritone/patches/` | the patch series (`git diff` output, one concern per file, description above the first `diff --git`) |
| `tools/baritone/BaritoneSource.java` | the generator (single-file Java 21 program, no dependencies besides `git`) |
| `tools/baritone/apply.sh` | runs the generator without Gradle |
| `tools/baritone/vendor-import.sh` | imports a Baritone version from an upstream clone (`git cat-file`, LF as committed) and writes `MANIFEST.txt` |
| `tools/baritone/baritone.gradle` | Gradle wiring, applied from `build.gradle` |

Gradle tasks: `generateBaritoneSources` (runs before `compileBaritoneJava`), `compileBaritoneJava`, `baritoneTest`
(part of `check`). The `baritone` output is on the compile and runtime classpath of `main`, `client`, `test` and `gametest`,
belongs to the `minecraftai` mod in Loom, and is packed into the mod jar (and so remapped to intermediary with the rest).
`javax.annotation` comes from `jsr305` (compileOnly), and there is deliberately **no nether-pathfinder** dependency.

## The patch series

The edits are small and mostly deletions of client-only code. Two rules kept them small: types are *generalised*
(`LocalPlayer` -> `Player`, `ClientChunkCache` -> `ChunkSource`, `ClientLevel` -> `Level`), and where behaviour must
differ, upstream gets a new hook or a defaulted method that our glue implements instead of rewritten logic.

| Patch | Files | Concern |
|---|---|---|
| `0001-host-environment-hooks` | Helper, Settings, SettingsUtil, IBuilderProcess | no `Minecraft.getInstance()`, `GuiMessageTag`, `BaritoneToast` in static initialisers. Game directory and game-thread hop come from the overlay class `HostEnvironment`; the chat logger defaults to SLF4J, the toaster to a no-op |
| `0002-events-not-client-typed` | TickEvent, WorldEvent, IGameEventListener | client imports (javadoc only) dropped, `WorldEvent` carries a `Level` |
| `0003-player-types-in-api` | IPlayerContext, IPlayerController, RotationUtils, IBaritoneProvider | `player()` is a `Player`; `minecraft()` is the game-thread event loop (`Minecraft` or `MinecraftServer`); `entities()` is implemented by the host; new defaulted `mouseSensitivity()`; `createBaritone(Function<IBaritone, IPlayerContext>)`; `getBaritoneForMinecraft/Connection` dropped |
| `0004-player-types-in-core` | InventoryBehavior, CalculationContext, MovementDiagonal, ToolSet | `LocalPlayer` -> `Player` |
| `0005-chunk-source-generic` | BlockStateInterface, IClientChunkProvider | `ClientChunkCache` -> `ChunkSource`; the host makes `ServerChunkCache` implement `IClientChunkProvider` with a non-blocking snapshot |
| `0006-input-without-client-classes` | InputOverrideHandler, BlockBreakHelper, BlockPlaceHelper | no swapping of the `LocalPlayer`'s input object (the host applies `isInputForcedDown` itself), no client destroy-delay reset, `isHandsBusy()` -> `isPassenger()` |
| `0007-behaviors-without-client-options` | PathingBehavior, CustomGoalProcess, LookBehavior, MineProcess | no disconnect-on-arrival, no auto-jump toggle, sensitivity from `IPlayerContext`, dropped items via `ctx.entities()` |
| `0008-world-cache-directory` | WorldProvider | cache directory under `<game dir>/baritone/cache` instead of a client save/server-address layout |
| `0009-baritone-instance-construction` | Baritone, BaritoneProvider | constructed from a player-context factory; no primary instance, no chat control; `NullElytraProcess`; commands and GUI unsupported |
| `0010-block-optional-meta-no-client` | BlockOptionalMeta | block drops are rolled on a real level supplied through `HostEnvironment.setLootLevel` (upstream's private registry-reloading stub level wedges a Fabric server and is never used) |
| `0011-world-scanner-chunk-snapshot` | FasterWorldScanner | scans run on worker threads (parallel stream, mine/farm rescans); they read the chunk source's thread-safe view instead of `ServerChunkCache.getChunk`, which waits for the server thread once per chunk |
| `0012-executor-host-hook` | Baritone, CachedWorld | Baritone's background pool (path searches, rescans, region loads) is supplied by the host through `HostEnvironment.executor()` (bounded, named, daemon) instead of a private unbounded non-daemon pool; the two never-ending cache loops (region packer, periodic save) get their own daemon threads so they cannot occupy a slot of the bounded pool |
| `0013-cancel-before-start` | AbstractNodeCostSearch | `cancelRequested` is volatile and no longer reset when `calculate()` starts, so a search cancelled while it waits for a worker (bounded pool: it can wait) is dropped instead of running to its timeout |
| `0014-per-player-break-place-permission` | IPlayerContext, CalculationContext, MineProcess, PathExecutor, MovementParkour | `IPlayerContext` gets defaulted `allowBreak()`/`allowPlace()` (the global `Settings` values); the cost model, mine process and executor ask the player context, so one bot can be allowed to place or break and another not (`Settings` is one object for the JVM) |
| `0015-scanning-process-permission` | IPlayerContext, MineProcess, GetToBlockProcess, FarmProcess, ExploreProcess, BuilderProcess | `IPlayerContext.allowScanningProcess(name)` (default true): the processes that pick their targets by scanning loaded chunks ask it before they start and do nothing when refused (see "Strict-survival rules" below) |
| `0016-tool-policy-host-hook` | ToolSet, CalculationContext | a cost model prices a break with the tool the host says it will really equip (`HostEnvironment.ToolPolicy`, judged on a snapshot of the inventory taken on the game thread when the cost model is created), not with the fastest tool on the hotbar; no policy set = upstream behaviour (see "Tools" below) |

## Excluded from the build (see `exclude.txt`)

Chat commands (`baritone.command.**`; the command *API* stays because `IBaritone` names its interfaces), elytra flying
(needs the nether-pathfinder JNI library), renderers/GUI/toasts, the client `BaritonePlayerContext` /
`BaritonePlayerController` / `PlayerMovementInput`, the superseded `WorldScanner`, the Schematica/Litematica mod bridges,
and all of `src/launch` (client mixins, kept in the vendor tree as the reference for the server-side mixins we write).
Upstream's schematic *formats* (`.schematic`, `.schem`, `.litematic` files) are still compiled, so the builder can read files.

## Working on the patches

```
tools/baritone/apply.sh generate --keep-repo --out build/baritone-work   # a git repo: 'upstream', 'overlay', one commit per patch
cd build/baritone-work && <edit> && git add -A && git commit -m 0012-some-concern -m "why"   # subject = patch name
tools/baritone/apply.sh export --out build/baritone-work   # rewrites tools/baritone/patches from those commits
```

To change an existing patch, commit a `fixup!` (or `git rebase -i` it) in that repo before `export`. A new file that has no
upstream counterpart goes to `overlay/`, not into a patch.

## Line endings (the CRLF trap)

Upstream commits LF. A Windows clone with `core.autocrlf=true` checks the same files out as CRLF, so a tree copied from its working
directory (or exported from it with `git archive`) has different bytes than upstream's blobs: every hash in `MANIFEST.txt` stops
matching and `BaritoneVendorIntegrityTest` and the generator's `verify` fail on every file. Import only with
`tools/baritone/vendor-import.sh`, which reads blobs with `git cat-file` (bytes exactly as committed) and takes the hashes from the
object database, never from the files: the manifest is upstream's hash, not ours. The same holds inside this repository:
`third_party/baritone` must not go through an editor or a tool that normalises line endings.

`third_party/baritone/UPSTREAM.md` is the only file of the vendor directory that is not upstream's. It is tracked despite the
repository's `*.md` ignore rule (there is an explicit exception in `.gitignore`); `vendor-import.sh` keeps it and you update it by hand.

## Upgrading Baritone (checklist)

Work in a branch; nothing here needs the vendor tree to be edited in place.

1. **Import.** `tools/baritone/vendor-import.sh <baritone clone> <tag or commit>`. It replaces everything under
   `third_party/baritone` except `UPSTREAM.md` and rewrites `MANIFEST.txt`. Edit `UPSTREAM.md` (tag, commit, tree, date). Check
   `tools/baritone/apply.sh verify` and that `git diff --stat third_party/baritone` shows only real upstream changes (a diff of
   every file means CRLF, see above).
2. **Replay the patches.** `tools/baritone/apply.sh generate --3way --keep-repo --out build/baritone-work`. It fails naming the
   first exclusion entry that matches nothing (upstream moved or deleted a file; update `exclude.txt`) or the first patch that
   does not apply. A conflicting patch is left as conflict markers in `build/baritone-work`.
3. **Resolve.** Fix the markers in `build/baritone-work`, commit with the patch name as the subject, run
   `tools/baritone/apply.sh resume --3way --out build/baritone-work` until the series is through, then
   `tools/baritone/apply.sh export --out build/baritone-work` to rewrite `tools/baritone/patches`. A hunk that upstream made
   unnecessary is dropped from its patch; a patch that ends up empty is deleted and the rest renumbered (then update the table above).
4. **Client scan.** The generator ends with a scan for `net.minecraft.client`, `com.mojang.blaze3d` and `Minecraft.getInstance()`.
   Every hit is new client usage upstream introduced: extend a patch (or `exclude.txt` plus an overlay stub), `export` again.
5. **Compile the glue against the new API.** Glue that implements upstream interfaces (`IPlayerContext`, `IPlayerController`,
   `IClientChunkProvider`, `IBaritoneProcess` users) fails to compile when a signature moved (26.1 renamed `ClickType` to
   `ContainerInput` in `windowClick`). Fix the glue, not the vendor tree. The `Baritone*Mixin` classes are the server
   counterparts of upstream's `src/launch` mixins (`MixinPalettedContainer`, `MixinItemStack`, `MixinLootTable`,
   `MixinLootContextBuilder`): diff those upstream files between the two versions to see whether a target moved.
6. **Tests, in this order.** `./gradlew compileBaritoneJava baritoneTest test` (upstream's own JUnit tests must stay 100%: the
   pathing core does not change with our patches), then the Baritone GameTests
   (`./gradlew runGameTest` with filters `baritone_server_game_tests_*`, `baritone_glue_game_tests_*`,
   `baritone_planning_game_tests_*`, `baritone_navigation_game_tests_*`, `baritone_input_physics_probe_game_tests_*`), then the
   legacy suites that share the bot tick (`follow_task_game_tests_*`, `gather_tool_policy_game_tests_*`,
   `mixin_target_class_load_game_tests_*`, `bot_persistence_restore_game_tests_*`). A moved physics number in the probe suite is a
   Minecraft change: read the probe line before touching Baritone.
7. **Build the jar** (`./gradlew build -x runGameTest`) and check it: the Baritone classes are in it (remapped to intermediary), it
   contains `baritone-server-build.properties` with the patch count, every mixin of `minecraftai.mixins.json` is present. The
   headless boot GameTest fails if a client class is reachable.

`tools/baritone/apply.sh report --upstream <dir> --out <dir> [--3way]` tries the series on another version without stopping; run it first to
size the work (rehearsal results for 1.21.10 and 26.1 are in the commit that introduced the series).

## The glue (new code in the mod, `src/main/java/.../minecraftai/baritone` and `.../mixin`)

| Class | Replaces (upstream, client) |
|---|---|
| `ServerPlayerContext` | `BaritonePlayerContext`: bot supplier, `ServerLevel`, `MinecraftServer` as the game-thread object, the bot plus the dropped items it can observe (`ObservableWorldQuery`; refreshed on the server thread, readable from workers), a non-blocking `playerFeet()` (the default reads `Level#getBlockState`, which waits for the server thread from any other thread, and deadlocked the ore scan) |
| `ServerPlayerController` | `BaritonePlayerController`: breaking through the mod's `MiningController`, placing through `useItemOn` |
| `LoadedChunkSnapshot` + `ServerChunkCacheBaritoneMixin` + `ChunkMapVisibleChunksAccessorMixin` | the client's `MixinClientChunkProvider`/`MixinChunkArray`: an O(1), non-blocking, thread-safe view of the loaded chunks (`ChunkMap#visibleChunkMap` is published copy-on-write) |
| `BaritonePalettedContainerMixin` (+ `...DataMixin`), `BaritoneItemStackMixin`, `BaritoneLootTableMixin`, `BaritoneLootContextBuilderMixin` | the client-only `MixinPalettedContainer`, `MixinItemStack`, `MixinLootTable`, `MixinLootContextBuilder` (same code, needed by ore scanning and drop matching) |
| `BaritoneHost` | `BaritoneProvider`'s primary instance: `create(bot)`, `of(player)`, `destroy`; `configure(server)` installs the worker pool and the fixed settings once |
| `BaritoneRegistry` | (new) one instance per bot keyed by UUID; `get`, `find`, `tick`, `reset` (death, runtime reset), `forget` (delete/unload), `clearAll`; wired into `RuntimeLifecycleCoordinator` so an instance never outlives its bot |
| `BaritonePlanner` | (new) plan-only use: `plan(baritone, goal)` runs an A* on the shared pool over a snapshot taken on the server thread and returns a `Plan` (result, movements, search and queue time); one search per bot, superseded or cancelled with the bot |
| `BaritoneExecutor` | (new) the shared worker pool behind `HostEnvironment.executor()`: `min(4, max(2, cores/2))` daemon threads, FIFO queue, queue-wait and concurrency counters |
| `BaritoneSettings` | (new) the fixed global settings (no parkour, no water-bucket fall, no chunk cache, no avoidance, free look off, output to `BotLog`) and the ones derived from the mod's navigation config (`maxFallHeightNoWater` = `nav.maxSafeFall`) |
| `BaritoneDriver` | (new) the tick driver, called from `AIPlayerEntity.tick()`: `beforePhysics` (refresh observable entities, `TickEvent`, input bridge) runs before `super.tick()`/`doTick()`, `afterPhysics` (`PlayerUpdateEvent` PRE = Baritone applies its look target, look bridge, fall check, POST) after it; the order is the client's, so Baritone's own timing assumptions hold. Only a bot Baritone is busy with is ticked |
| `BotInputBridge` | (new) `InputOverrideHandler` state -> `zza`/`xxa`/jump/sneak/sprint of the bot. Sneak scaling (0.3) applied exactly once; sprint only with a forward input, food > 6, not sneaking/using an item/blind, and it ends on a wall hit (the server never clears the flag itself) |
| look bridge (in `BaritoneDriver`) | Baritone's rotation becomes head and body rotation through `LookAction.setYawPitch`; `MiningController.driven(..)` (used for Baritone's breaks) neither re-aims nor re-selects the tool |
| `BaritoneEdits` | (new) ledger of every break (completed `MiningController` break, block, tool, ticks) and placement (`BuildAction.useItemOnHit`) of a bot's instance; the end-to-end tests compare it with a diff of the world |
| `BaritonePolicy` | (new) per-bot `allowBreak`/`allowPlace` (`UNRESTRICTED`, `WALK_ONLY`, `NO_PLACING`, `NO_BREAKING`), answered through `ServerPlayerContext.allowBreak()/allowPlace()` (patch 0014) |
| `BaritoneBreakPlacePolicy`, `BaritoneRefusals`, `BaritoneGoals` | (new) the strict-survival rules, their refusal ledger and the coordinate-goal door, see "Strict-survival rules" below |

**Who moves the bot.** Exactly one of Baritone and the legacy `ActionPack` at a time. A bot is *driven* from the first tick a Baritone process wants control (or a path is searched/run) to the tick none does; while driven `ActionPack` executes nothing and writes no inputs. When legacy code gives an order (`startWalkTo`, any `startPathTo`, `startDigPathTo`, `startMining`, a held input, `stopAll`) `ActionPack.claim` calls `BaritoneRegistry.preempt`, which cancels Baritone (goal, path, search, keys, the block being broken) and releases the inputs it wrote before the legacy order takes effect; when Baritone takes over `ActionPack.yieldToBaritone` drops the legacy walk/path/mining. `hasActiveActions()` counts a busy Baritone. Falls: a driven bot gets `doCheckFallDamage` every tick (a `ServerPlayer` only checks falls on client move packets, so bots otherwise never take fall damage).

Break/place routing: `ServerPlayerController.clickBlock/onPlayerDamageBlock` -> `MiningController.driven`, `processRightClickBlock` -> `BuildAction.useItemOnHit` (also opens doors and gates). Baritone has no other way to change a block.


Covered by `BaritoneNavigationGameTests` (end to end, a bot walks through Baritone: 20-block sprint, wall detour, step up and 3-block drop, closed wooden door, ladder, bridging and pillaring only when placing is allowed, breaking through a stone wall with the pickaxe through `MiningController`, hand-over with the legacy executor, the driver's fall check, legacy navigation with an idle instance), `BaritoneExecutionContractTest` (tick order, hand-over hooks, routing rules), `BaritoneServerGameTests`, `BaritonePlanningGameTests` (a bot plans a wall detour, a one-block step, a pit, a closed wooden door, a closed iron door (not planned through: a player build) and a water strip on a sealed platform) and `BaritoneGlueGameTests` (headless boot, registry lifecycle, worker pool bounds, queued-plan cancellation, settings, observable entities) on a real server with real bots: instance lifecycle, thread-safe chunk snapshot, A* on a worker thread
(open floor and through a wall), the whole behavior stack ticking and producing inputs, ore scan through the snapshot and the raw
palettes, drops matched by loot table and item hash; and by the unit tests `BaritoneVendorIntegrityTest`,
`BaritoneServerOnlyClassesTest` (no client reference in the class files) and `BaritoneSourceGeneratorTest` (every way the pipeline
must refuse). The physics behind the input bridge is pinned by `BaritoneInputPhysicsProbeGameTests` (section below).

## Physics probes and the input contract

`BaritoneInputPhysicsProbeGameTests` (11 GameTests, environments `baritone_input_physics_probe_game_tests_*`) is the permanent
regression suite for the assumption Baritone's cost tables rest on: a bot whose only controls are Baritone's held keys behaves
like a vanilla client player. A probe process (`REQUEST_PAUSE` every tick, so Baritone counts as busy but never plans) writes the
keys in `onTick`; `BaritoneDriver` and `BotInputBridge` apply them, vanilla `super.tick()` moves the bot, and a post-tick
listener samples the result. It measures walking 4.317 blocks/s (vanilla 4.317), sprinting 5.612, sneaking 1.295, strafing and
diagonals (the 1/0.98 normalisation), step-up with and without a jump, jump apex 1.2522 / flight time, sprint-jump distance
against a closed-form model, 2-5 block gaps, fall distance and damage (`doCheckFallDamage` from the driver), sneaking at a ledge
(0.29 overhang), ladder and vine climbing and sliding, doors and fence gates (right click through the interaction manager; an
iron door stays shut), swimming, soul sand, honey, slabs, stairs, `onGround` against the collision geometry, pillar jump-and-place,
sneak bridging, the tick order (keys set in a process tick move the bot in the same tick, keys set from the post-tick event one tick
later, a release leaves no input behind) and the bridge's sprint rules (a wall ends the sprint, food 6 or less and a missing forward
input never start one). Each trial prints a `BOTPROBE|<probe>|<trial>|...` line into the log; a failing assertion names the
measured and the expected value. When a Minecraft or Baritone upgrade moves one of these numbers, this suite says which.

## Still open in this layer

* **Jump/move rotation events** (`RotationMoveEvent` from `Entity.moveRelative` / `LivingEntity.jumpFromGround`) are only needed
  with `freeLook`; `BaritoneSettings` keeps `freeLook` off so Baritone sets the real yaw.
* **Cache lifetime.** `WorldProvider` keeps one `WorldData` per dimension under `<game dir>/baritone/cache` (its region packer and saver
  are two daemon threads per dimension); nothing closes it when the last bot goes away. With `chunkCaching` off it only holds
  waypoints and empty region files.
* **Restoring the capabilities that are switched off** (parkour, water-bucket falls, vines, the on-disk chunk cache) is planned with the
  obstacle-course comparison of legacy against Baritone (phase P2 of `docs/NAVIGATION_BARITONE_PLAN.md`), each under its own tests,
  before the default of `nav.engine` is flipped.
* **Policy per bot.** Baritone reads `Baritone.settings()` live (a global, see `BaritoneAPI.getSettings()`), so everything that
  differs per bot goes through the player context (patches 0014 and 0015: `allowBreak`, `allowPlace`, `allowScanningProcess`),
  never through mutated global settings. Anything new that must differ per bot follows the same route.

### Built on top of this layer (no longer open)

* **The `nav.engine` switch** (`legacy` by default, `baritone`): ordinary walks, surface walks, follow (`GoalNear`) and swim routes are
  executed by Baritone behind `ActionPack`, everything else stays on the legacy navigator; Baritone is bootstrapped lazily and
  fail-soft (a link or init failure falls back to legacy for the session). See `docs/NAVIGATION_ENGINE.md`.
* **Follow and move adapters** (`BaritoneNavigator`, `NavEngineSelector`): the legacy result vocabulary (`IN_PROGRESS`,
  `GOAL_UNREACHABLE`, `lastRouteOutcome()`), synchronous admission through one inline budgeted search, one writer of the bot's
  movement at a time.
* **The water lease.** A swim route (`BaritoneRegistry.setWaterAllowed`) leases the bot to Baritone against the drowning safety net
  for as long as Baritone drives it (`NavSafetyNet.renewBaritoneWater`, refused once the air is low).
* **The survival rules** of the next section (`BaritoneBreakPlacePolicy` and friends), with the pack-compat GameTest harness that
  runs the GameTests next to the mods of the real profile.

Plan, status and next phases: `docs/NAVIGATION_BARITONE_PLAN.md`.

## Strict-survival rules (what Baritone must not do, and how that is enforced)

Baritone is a full pathfinder, but a bot stays survival-legal: it may only act on what it can observe, and it must not damage what
players built or stored. One rule set, `BaritoneBreakPlacePolicy`, is consulted by `ServerPlayerController` before every world change
and fed into Baritone's planning; every refusal is a `baritone_refused` line in the bot's log and an entry in `BaritoneRefusals`.

| Baritone does | Rule |
|---|---|
| break a block (`clickBlock`, `onPlayerDamageBlock`) | only natural terrain by the mod-wide `BreakRule` (a tag-driven whitelist: every kind of stone incl. tuff, deepslate, basalt, sandstone, terracotta, ice, dripstone, soul sand, sculk, dirt, sand, gravel, ores incl. `c:ores`, `c:stones` modded stone, leaves, small plants) that is observable (`ObservableWorldQuery`) and only if the bot's `BaritonePolicy` allows breaking. Never a block entity (chest, furnace, bed, spawner, sign, barrel ...), a crafting table or other utility block, a fluid, a dangerous block, bedrock, or anything that is not natural terrain (planks, glass, wool, concrete, the brick family incl. cracked/mossy/chiseled stone bricks, slabs, stairs, walls, doors: structures and player builds), and infested blocks. A placed cobblestone cannot be told from a natural one, as for a player |
| plan a route | the block-level part of the rule is installed as `Settings.blocksToDisallowBreaking` (a hash-cached view, `BaritoneBreakPlacePolicy.installPlanningRules`), so searches plan around protected blocks (or report no path) instead of planning through them and being vetoed at execution. `allowBreakAnyway` is emptied |
| place a block / click a block (`processRightClickBlock`) | the support face must pass the legacy perception proof (`BuildAction.supportFaceRefusal`: in reach, in perception range, an eye ray strikes exactly that face); only Baritone's throwaway blocks (`acceptableThrowawayItems`); only if the policy allows placing; never against a chest, bed, crafting table, door, lever ... The same click may open a door or trapdoor whose `BlockSetType` lets a hand open it (never iron) or a fence gate, unless the item wins over the block (vanilla `useItemOn`: a sneaking bot with something in either hand would use the item instead of the block, so that click gets no allowance and is refused like any other non-throwaway use; a sneaking bot with both hands empty still opens it) |
| use an item without a block (`processRightClick`) | refused unless in `BaritoneBreakPlacePolicy.USE_ITEM_ALLOWLIST`, which is empty (water bucket, ender pearl, food ... are all refused) |
| move inventory items (`windowClick`) | refused unless `allowInventory` is on (`BaritoneSettings` keeps it off) and then only a hotbar swap in the bot's own inventory |
| scan the world for targets (`MineProcess`, `GetToBlockProcess`, `FarmProcess`, `ExploreProcess`, `BuilderProcess`) | **do not start** (patch 0015, `ServerPlayerContext.allowScanningProcess`) unless the bot holds the hidden-scan privilege (`PrivilegedCapability.HIDDEN_BLOCK_SCAN`, never in strict survival). They find ores, crops and blocks by reading every loaded chunk, i.e. X-ray |

**Decision: the break whitelist is natural terrain only (kept on purpose).** Two consequences are intended and documented here so
nobody "fixes" them by widening the list:

* **Logs are never breakable by Baritone.** A route through a forest goes around the trees, and a log cabin, a fence of logs or a
  wooden tower is protected without having to tell it from a natural tree. Wood for the bots comes from the gather tasks (legacy
  actions, their own tool and tree rules), not from Baritone opening a way.
* **Player-built cobblestone, dirt and other "natural-looking" blocks cannot be told from natural ones**, exactly like for a player
  who digs through a wall of cobblestone. The rule is about the kind of block, not about who placed it (`BaritoneEdits` and the mod's
  own placement ledger are not consulted, and must not become a way to learn what is "player built"). Everything that is clearly
  made (planks, glass, bricks, wool, iron doors, fences, stairs ...) or that stores or does something stays protected.

The verdict per block kind is cached (`BreakVerdictCache`); it is computed from tag membership, so the cache is dropped when the server
starts and on every tag load or `/reload` (`MinecraftAiMod`), and not only on the first use.

**The X-ray rule for callers:** Baritone only ever receives coordinate goals (`GoalBlock`, `GoalNear`, `GoalTwoBlocks`, `GoalXZ`, `GoalYLevel`, or
`BaritoneGoals.composite` of those) built from targets the mod's observation layer produced. `BaritoneGoals` is the door: `setGoal`
refuses every other goal type, `mineAt(bot, pos)` refuses a target the bot cannot observe right now (`target_not_observed`), one that
is not breakable, or a bot that may not break. Never call `getMineProcess()`, `getGetToBlockProcess()`, `getFarmProcess()`,
`getExploreProcess()` or `getBuilderProcess()` from mod code, and do not scan with `getWorldScanner()` to choose a target. This is enforced, not just
asked: `BaritoneSurvivalContractTest` scans all of `src/main` and fails when any of those accessors appears outside `BaritoneDriver`, whose
busy check may only read `.isActive()`. Known
limits: a search plans over the loaded chunks like the legacy navigator (it does not reveal ore, but a route can pass through an
unseen cave); Baritone's placement chooses its own support face, and a face refused at execution (say next to a chest) is not
planned around.

Tests: `BaritoneSurvivalGameTests` (routes around a bed, chest, crafting table and player-built planks, and the same geometry with natural stone as the
control that is broken through; no break and no route when the only way is through protected blocks; the controller refuses each protected
block, an unseen stone and a policy that forbids breaking; placements with a hidden, out-of-reach or interactive support, a wrong item or no
permission are refused and a legal one is not; item use and inventory moves; scanning processes refuse to start; a goal on an unobserved ore is refused and on a visible ore is carried out).

**One break rule for both engines.** The rule is `mining/BreakRule` (no Baritone type, so the legacy engine runs without any Baritone
class): `BaritoneBreakPlacePolicy` asks it for every click and for the cost model, and the legacy diggers ask it too
(`BlockMiner` in its natural-terrain-only mode, which `OreDigTask` uses for all its channel, detour and branch-leg breaks;
`NeighborEnumerator.isMineable`, the path search's dig-through rule, and through it `FollowDigOut`; the ore digger's tunnel step
and stair choice reject a structure block as a boundary of the tunnel). The legacy diggers enforce the categories they had no rule
for (`BreakRule.legacyDenialOf`: structure, infested, block entity, not natural); bedrock, fluids and dangerous blocks keep their
existing dedicated handling there. Expect more detours and "no route" inside structures: that is intended. Pointed dripstone stays
refused (`Standability.isDangerous`), infested blocks are refused whatever they look like (a stronghold's silverfish nest cannot be
told apart from outside).

## Tools

A break made by Baritone is priced and executed with the mod's own tool policy (`ToolSelector`), not with Baritone's auto-tool
(which takes the fastest tool of the hotbar and spends an iron or diamond pickaxe on stone):

* `BaritoneSettings`: `assumeExternalAutoTool=true` (Baritone never touches the selected slot; `autoTool` stays on because the cost
  model keys on it), `useSwordToMine=false`, `itemSaver=true`.
* `ServerPlayerController#clickBlock` calls `ToolSelector.equipBestTool(bot, state, false)` once when a break starts (never per tick: a
  running break keeps its tool, `MiningController.driven`). `false` = a sword is never a mining tool (leaves, cobweb).
* Cost model: `BaritoneToolPolicy` (patch 0016) answers "which stack will break this block" from the same pure chooser
  (`ToolSelector.choose`) on a copy of the inventory made on the server thread when the cost model is created, so a planned break
  takes as long as the real one and a movement does not time out (the stone pickaxe is slower than the iron one).
* The follower's look: `FollowTask.faceTarget` leaves the head alone while a Baritone route runs (Baritone owns yaw and pitch;
  pitching at the player put the pitch back before every click and a follower could never break the lower block of a wall).

Break/place cadence: Baritone keeps vanilla's break delay (patch 0006, `blockBreakSpeed`); the legacy `ActionPack` waits five ticks
after a multi-tick break (vanilla's `destroyDelay`; an instant break sets none). The placement cadence (`rightClickDelay` = 4) lives
in `BuildAction` and is not changed here.
