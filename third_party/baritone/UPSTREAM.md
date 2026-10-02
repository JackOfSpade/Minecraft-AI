# Vendored Baritone (pristine)

Everything under `src/`, plus `LICENSE` and `README.md`, is **byte-identical to upstream**. Never edit it in place.
All changes we need live outside this directory:

* `tools/baritone/patches/*.patch` - the numbered patch series (applied at build time to a generated copy)
* `tools/baritone/exclude.txt`     - files that are not compiled
* `tools/baritone/overlay/`        - new upstream-neutral files (hooks, stubs for excluded classes)
* `src/main/java/.../baritone/`    - our integration code (glue), in the mod itself

## Upstream

| | |
|---|---|
| Repository | https://github.com/cabaletta/baritone |
| Tag        | `v1.17.0` (Minecraft 1.21.11, Mojang mappings + Parchment) |
| Commit     | `23723891da460ef15797b02fe5b385b0c5b163cc` ("Merge branch '1.21.10' into 1.21.11", 2026-08-31) |
| Tree       | `236881316e5a1d7746bd50353d1e5098b012b5da` (whole repository tree at that commit) |
| Imported   | with `tools/baritone/vendor-import.sh` (`git cat-file`: the LF blobs exactly as committed). CRLF trap: a Windows clone with `core.autocrlf=true` checks files out as CRLF, and anything copied from its working tree, or exported with `git archive` there, changes blob hashes and fails the integrity test. Always import through the script |

`MANIFEST.txt` lists the upstream git blob SHA-1 of every vendored file. Check with
`java tools/baritone/BaritoneSource.java verify` (recomputes the blob hashes) or, against an upstream clone,
`git ls-tree -r 2372389 -- src/api src/main src/launch src/test LICENSE README.md`.

## What was copied

| Path | Compiled? | Why it is here |
|---|---|---|
| `src/api/`    | yes (minus `tools/baritone/exclude.txt`) | Baritone API |
| `src/main/`   | yes (minus `tools/baritone/exclude.txt`) | pathing core, movements, processes, behaviors |
| `src/launch/` | **no**  | client-only Sponge mixins. Kept as the reference for the server-side counterparts we write in the mod (input/rotation hooks, palette/loot/item accessors), so a version bump shows which mixin targets moved |
| `src/test/`   | yes, as the `baritoneTest` source set (JUnit 4) | upstream's own unit tests for the pathing core (elytra test excluded) |
| `LICENSE`, `README.md` | no | upstream metadata |

Not copied on purpose: `build.gradle`, `buildSrc/`, `fabric/`, `forge/`, `neoforge/`, `tweaker/`, `scripts/`, `Dockerfile`, `jitpack.yml`,
docs, and `src/schematica_api/` (compile stubs for the Schematica/Litematica mod bridges, which are excluded).

## Upgrading

See the replayable patch series in `tools/baritone/patches/` and `docs/NAVIGATION_ENGINE.md`.
