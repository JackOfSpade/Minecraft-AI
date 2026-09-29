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
| `tools/baritone/overlay/` | new files added to the generated tree: `HostEnvironment` (hooks) and stubs that stand in for excluded client-only classes (`RenderEvent`, `PathRenderer`, `SelectionRenderer`, `SchematicaHelper`, `LitematicaHelper`). An overlay file that shadows an upstream file must be in `exclude.txt` first |
| `tools/baritone/patches/` | the patch series (`git diff` output, one concern per file, description above the first `diff --git`) |
| `tools/baritone/BaritoneSource.java` | the generator (single-file Java 21 program, no dependencies besides `git`) |
| `tools/baritone/apply.sh` | runs the generator without Gradle |
| `tools/baritone/vendor-import.sh` | imports a Baritone version from an upstream clone (`git cat-file`, LF as committed) and writes `MANIFEST.txt` |
| `tools/baritone/baritone.gradle` | Gradle wiring, applied from `build.gradle` |

Gradle tasks: `generateBaritoneSources` (runs before `compileBaritoneJava`), `compileBaritoneJava`, `baritoneTest`
(part of `check`). The `baritone` output is on the compile and runtime classpath of `main`, `client`, `test` and `gametest`,
belongs to the `minecraftai` mod in Loom, and is packed into the mod jar (and so remapped to intermediary with the rest).
`javax.annotation` comes from `jsr305` (compileOnly), and there is deliberately **no nether-pathfinder** dependency.

## The patch series (29 files, +96 / -242 lines)

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
| `0010-block-optional-meta-no-client` | BlockOptionalMeta | the loot-table stub level no longer keeps a `Minecraft` reference |
| `0011-world-scanner-chunk-snapshot` | FasterWorldScanner | scans run on worker threads (parallel stream, mine/farm rescans); they read the chunk source's thread-safe view instead of `ServerChunkCache.getChunk`, which waits for the server thread once per chunk |

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

## Upgrading Baritone

1. `tools/baritone/vendor-import.sh <baritone clone> <tag or commit>`; update `third_party/baritone/UPSTREAM.md`.
2. `tools/baritone/apply.sh generate --3way --keep-repo --out build/baritone-work`. Exclusion entries that no longer match, and patches that do not apply,
   fail with the name. A conflicting patch is left as conflict markers in `build/baritone-work`; resolve, commit with the patch
   name as subject, then `tools/baritone/apply.sh resume --3way --out build/baritone-work`, then `export --out build/baritone-work`.
3. The generator ends with a scan for `net.minecraft.client`, `com.mojang.blaze3d` and `Minecraft.getInstance()`; each hit names
   file and line of *new* client usage upstream introduced. Extend a patch (or `exclude.txt` + an overlay stub), commit, `export`.
4. `./gradlew compileBaritoneJava baritoneTest test runGameTest` and the navigation GameTests.
5. Glue that implements upstream interfaces (`IPlayerContext`, `IPlayerController`, `IClientChunkProvider`) fails to compile
   if upstream changed a signature (26.1 renamed `ClickType` to `ContainerInput` in `windowClick`).

`tools/baritone/apply.sh report --upstream <dir> --out <dir> [--3way]` tries the series on another version without stopping
(rehearsal results for 1.21.10 and 26.1 are in the commit that introduced the series).

## The glue (new code in the mod, `src/main/java/.../minecraftai/baritone` and `.../mixin`)

| Class | Replaces (upstream, client) |
|---|---|
| `ServerPlayerContext` | `BaritonePlayerContext`: bot supplier, `ServerLevel`, `MinecraftServer` as the game-thread object, `getAllEntities()` |
| `ServerPlayerController` | `BaritonePlayerController`: breaking through the mod's `MiningController`, placing through `useItemOn` |
| `LoadedChunkSnapshot` + `ServerChunkCacheBaritoneMixin` + `ChunkMapVisibleChunksAccessorMixin` | the client's `MixinClientChunkProvider`/`MixinChunkArray`: an O(1), non-blocking, thread-safe view of the loaded chunks (`ChunkMap#visibleChunkMap` is published copy-on-write) |
| `BaritonePalettedContainerMixin` (+ `...DataMixin`), `BaritoneItemStackMixin`, `BaritoneLootTableMixin`, `BaritoneLootContextBuilderMixin` | the client-only `MixinPalettedContainer`, `MixinItemStack`, `MixinLootTable`, `MixinLootContextBuilder` (same code, needed by ore scanning and drop matching) |
| `BaritoneHost` | `BaritoneProvider`'s primary instance: `create(bot)`, `of(player)`, `destroy`, server-side default settings (`freeLook` off, no notifications) |

Covered by `BaritoneServerGameTests` (real server, real bots): instance lifecycle, thread-safe chunk snapshot, A* on a worker thread
(open floor and through a wall), the whole behavior stack ticking and producing inputs, ore scan through the snapshot and the raw
palettes, drops matched by loot table and item hash; and by the unit tests `BaritoneVendorIntegrityTest`,
`BaritoneServerOnlyClassesTest` (no client reference in the class files) and `BaritoneSourceGeneratorTest` (every way the pipeline
must refuse).

## What is still to build before Baritone drives a bot end to end

None of this needs another patch; it is glue for the next stage.

* **Tick driver and input bridge.** Write Baritone's forced inputs into `ActionPack` *before* `super.tick()` (`AIPlayerEntity.tick()`
  currently runs `super.tick()`, then `actionPack.onUpdate()`, so inputs are one tick late). Dispatch `TickEvent` (the GameTest
  already does), `PlayerUpdateEvent` (PRE before the player's own tick so `LookBehavior` sets the real rotation, POST after) and
  `onWorldEvent` to `baritone.getGameEventHandler()`.
* **Jump/move rotation events** (`RotationMoveEvent` from `Entity.moveRelative` / `LivingEntity.jumpFromGround`) are only needed
  with `freeLook`; `BaritoneHost` turns `freeLook` off so Baritone sets the real yaw.
* **Synchronous admission and thread bounds.** `PathingBehavior` searches on a static, unbounded thread pool (`Baritone.getExecutor()`);
  a caller that needs a same-tick answer runs `AStarPathFinder` inline (as `BaritoneServerGameTests` does).
* **Policy per bot.** Baritone reads `Baritone.settings()` live (a global, see `BaritoneAPI.getSettings()`); per-bot rules
  (strict-survival, protected blocks) belong in a `CalculationContext` subclass, not in mutated global settings.
* **Cache lifetime.** `WorldProvider` keeps one `WorldData` per dimension under `<game dir>/baritone/cache`; nothing closes it when
  the last bot goes away.
