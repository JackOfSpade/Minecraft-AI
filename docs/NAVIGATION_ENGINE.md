# Navigation (`nav.engine`)

Status: Baritone is the only production navigation engine. Ordinary path requests never fall back to the old A* / `PathExecutor` navigator. If Baritone is unavailable or a request cannot prove an honest route, the request fails with a typed reason and the bot stays put.

## Config

`config/minecraftai.json`, section `nav`:

```json
{
  "nav": {
    "engine": "baritone"
  }
}
```

`baritone` is the sole runtime value. A saved `legacy` value is accepted once as a compatibility alias and is canonicalised to Baritone; it produces `nav_engine_legacy_migrated`. A missing or unknown value also becomes Baritone, and an unknown spelling produces `nav_engine_invalid_migrated`. Neither value enables the retired navigator or a runtime fallback.

`NavEngine.LEGACY` remains only as a historical label for archived measurements and old saved-config migration. It is not a selectable executor. The retired A* planner has no production startup or route wiring. Bounded local physical actions such as a one-cell drowning or suffocation escape remain separately audited player-input actions; they are not an alternate navigator. The P3 diagnostic uses `NavigationControllerOwner.LOCAL_ACTION` for those actions, never `NavEngine.LEGACY`.

## Routing

All public `ActionPack` navigation entry points use Baritone:

| Request | Baritone route |
|---|---|
| `startPathTo`, `startDigPathTo` | observed block goal; breaking/placing only when that request authorises it |
| `startSurfacePathTo`, `startSurfaceDigFallbackPathTo` | observed constrained surface route, with its Y floor and optional observed return proof |
| `startWalkTo` | observed block goal; no straight-line-controller fallback |
| `startApproachTo` | `GoalNear` for a currently visible target |
| `startSwimRouteTo` | observed destination with water traversal enabled and the water-safety lease |
| `startRunAwayFrom` | Baritone's `GoalRunAway`, through the bot's visible escape corridor |

An unavailable Baritone session returns `baritone_unavailable`; an admission refusal returns its precise route reason such as `navigation_goal_unobserved` or `navigation_observed_corridor_unavailable`. Completion remains visible through `ActionPack.lastRouteOutcome()` as `SUCCESS`, `FAILED`, `TIMEOUT`, or `CANCELLED`.

## Honest terrain boundary

Before Baritone is allowed to warm up, plan, or execute, the server-thread admission builds an immutable per-bot observation fence. The vendored Baritone accessor is patched to consult that fence before any chunk, cache, or movement-state read; a cell outside it is treated as bedrock. This prevents a loaded chunk from becoming route knowledge merely because it is loaded.

- A new block target must be visible now. A remembered target may be used only from bounded same-dimension memory (at most 8,192 cells and 6,000 ticks), and its full `BlockState` must be visibly re-proven before reporting arrival.
- Walking terrain is admitted only from individually line-of-sight-proven feet, head, and support cells in a narrow corridor. Hidden walls, tunnels, ore, and ground do not become usable route data.
- A break-capable route may break only a block already represented in its immutable snapshot and still must pass the normal strict-survival break policy. It cannot mine toward a hidden diamond or tunnel through unseen stone.
- The eyes see through leaves, fences, glass and water (not lava): a block behind them is observed, so a route to a canopy log is admitted. The cells the rays passed through are stored as what they are (a leaf, a fence or water, never air), so the planner cannot walk through foliage it only looked through, and breaking the log still needs a line a hand can reach (see `docs/PERCEPTION.md`, sight is not reach).
- A water-permitted route, including `startSwimRouteTo`, resolves to an observed dry stance while using a visible water corridor and the drowning-safety lease. Its player-originated proof sees through water as every sight proof does; solids, lava and unloaded chunks still block it, and the snapshot includes the feet, support, and three cells of movement headroom Baritone needs. The internal exact-water form is reserved for a caller whose destination itself is a visibly observed water or shore cell.
- An approach target must be visible at admission. Run-away routes use a visible direction/corridor, not a guessed destination. Teleports and dimension changes revoke active terrain authority and clear memory.

The fence is refreshed before driven Baritone ticks. A refresh that cannot preserve an honest view stops the route as `navigation_observation_lost`; there is no alternate navigator.

## Survival movement policy

Baritone settings retain only moves a player can perform. Breaks and placements are filtered through the mod's strict-survival policy, and dry routes avoid water. Optional movement capabilities are under `nav.baritone`:

```json
{
  "nav": {
    "baritone": {
      "parkour": true,
      "parkourAscend": true,
      "parkourPlace": true,
      "waterBucketFall": true,
      "maxBucketFall": 12,
      "vines": true,
      "mobAvoidance": true
    }
  }
}
```

The vendored Baritone patch and its source-contract checks document the exact capability rules in `tools/baritone/patches/`. The Baritone chunk cache remains disabled, and patched terrain reads fail closed outside the observation fence.

## Logging and diagnosis

Each route records a PATH-category admission decision. The important events are:

- `nav_engine_legacy_migrated`, `nav_engine_invalid_migrated`
- `nav_goal_rejected` with a target, reason, ray count, and fresh-cell count
- `nav_observation_fence_updated` with its generation, cell count, rays, and target provenance
- `baritone_admission` with planner outcome, duration, node count, movement count, policy, and fence size
- `observed_target_route_started`, `observed_target_revalidated`, and `observed_target_memory_revoked` for remembered targets
- `route_observation_lost` and `baritone_observation_revoked` when an active route loses its terrain authority
- ordinary route events: `path_complete`, `path_failed`, `path_timeout`, and `path_cancelled`

These records are enough to distinguish a normal failure from a no-cheat refusal and to audit whether a remembered target was re-proven. See `docs/LOGGING.md` for session locations and retention.

## Verification

The Baritone unit/source contracts cover engine selection, no fallback, route result mapping, policy mapping, the observation fence, water-goal separation, and remembered-target revalidation. GameTests cover visible routes, hidden-goal rejection, scan isolation, water routes, and survival policy controls. The repository test scripts run these together with the vendored-source patch replay.
