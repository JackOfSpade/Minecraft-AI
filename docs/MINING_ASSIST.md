# Mining Assist

Mining Assist gives the mining bots an honest sense of their surroundings: first-hit view rays from the
bot's own eye, clamped to the same perception radius every other observation uses. From that it can
notice ore in a cave, remember lava, and recognise the palette of a structure. The full design document
(`MINING_ASSIST_DESIGN.md`, revision 2, kept outside this repository) has the section numbers quoted below;
this page describes what is shipped.

## Status: P1 built, default still `sense` until flipped

Phase P1 (the opportunistic valuables detour) is built and tested, but the shipped default mode stays
`sense` until the GameTest lane and the 4-bot cost gate have run clean (that flip, and the matching config
and doc line, is the orchestrator's own last step of the phase, not part of the code in this repository
version). A server that wants the detour today opts in explicitly with `miningAssist.mode` set to `detour`
or `all` (see Modes). With the shipped default, P0's guarantee still holds: the assist only observes and
writes logs, and changes nothing a bot does:

- no detour to a valuable, no pause, no stop for a structure, no chat message, no LLM call;
- no task is assigned or modified, and `OreDigTask` (like every other mining task) is not touched;
- no new privilege: no teleport, no forced pickup, no hidden block scan, no structure lookup. A ray hit is a
  nomination, never proof, and a hit on an unloaded chunk is "unknown", never "empty".

What P0 exists for is measurement: how much the sensor costs per tick, what it sees, and what the POI scorer
would have said. Later phases (detour, POI stop-and-notify, LLM confirmation, exploration) each ship behind
their own mode and only after these numbers are known. P1 is the detour; it is described below.

## Modes

| Mode | Meaning |
|---|---|
| `off` | The first statement of every hook, of the coordinator, of the gate and of the tick measurement is one static read and a return, and the placed-blocks file is not read at start-up. What still runs is the bare per-bot call and the once-per-tick dirty-flag check of the sidecar writer. |
| `sense` | Sensor, shadow POI scoring and shadow logs. Nothing acts. Shipped default. |
| `detour` | Sensor plus the P1 opportunistic detour: a mining task may walk off its strip face to break a valuable it saw on the way, then return to the exact anchor (see below). |
| `poi` | Reserved for P2 (deterministic POI stop-and-notify). Behaves like `sense` today: the sensor and the shadow POI scorer run, nothing acts. |
| `all` | Every phase shipped so far at once: today this is the same as `detour` (P2 is not shipped yet). |

Resolution order, first match wins:

1. Environment variable `MINECRAFTAI_MINING_ASSIST` (non-blank).
2. `miningAssist.mode` in `config/minecraftai.json`.
3. The shipped default (`sense`).

Names are case-insensitive. An explicit but unknown value resolves to `off` and logs a warning.
`MINECRAFTAI_MINING_ASSIST_DETERMINISTIC=1` removes time-based throttling (used by reproducible runs).

### Turning it off or on

- Off for a server: set `"miningAssist": { "mode": "off" }` in `config/minecraftai.json`, or start the server with
  `MINECRAFTAI_MINING_ASSIST=off`. Restart is required; the config is read once at start-up.
- Only the shadow log lines off, sensor still running: `"miningAssist": { "sense": { "shadowLog": false } }`.
- GameTests and `/minecraftai verify` scenarios run with the assist off by default (see Tests and evidence).

## When it senses

For each bot, every tick, `MiningAssistCoordinator` runs after the danger scan and before the goal executor.
It never consumes the tick and never throws. The checks run cheapest first:

1. Mode off (or harness default off with no opted-in bot): return after one static read.
2. The danger scan handled this bot this tick: skip (design 2.3, step 3d).
3. The active task is `OreDigTask`, `DigDownTask`, `DescendToYTask`, `MineTask` or `MineValuablesTask`.
   The retired `StripMineTask` is not publicly reachable, so it is not covered. `DigDownTask` is sensed in
   both of its phases in P0.
4. The gate is open (below).
5. The bot is underground: `!world.isSkyVisible(feet)`, an own-cell read.

### The gate

The assist only runs for a bot whose active request came from a real player or mission: origin `MISSION`,
`PLAYER_COMMAND`, `PLAYER_PANEL`, `LLM_TOOL` or `JOB`. It is closed for `SAFETY`, `SYSTEM_BACKGROUND` and
`VERIFY`, for a bot that holds a strict `MiningEvidenceAudit` session, while `TpsGuard` reports degraded TPS,
and while the harness default is off (unless a test opts the bot in). No origin at all counts as not real. The
verdict is cached for 20 ticks per bot, except that a cached open verdict is re-checked against the live
origin and audit session on every call (two map lookups), so an audit session that begins never waits out
the cache. A change of verdict writes one `assist_gate` line with the reason.

## What a sensing tick does

- **Break peek.** Up to 4 pending block breaks are drained. The break hook fires when the bot has finished
  its side of the break, not when the world confirmed it (a protected region can refuse it), so the peek
  never assumes success: it observes the broken cell through `OreScan.observe`, the same observation proof
  the mining tasks use, and only a cell observed as air (or as fluid that flowed in) becomes open in the
  occupancy window and enters the bot's dug ring. A cell that still holds its block is folded like any first
  hit; one that cannot be observed stays unknown. Both count as `breaks_unconfirmed`. The six neighbours are
  observed the same way. A neighbour that is open space, was not dug by the bot itself, and was not already
  seen as open by an earlier ray is a breakthrough: the sweep restarts with a fresh rotation at a raised ray
  rate for one sweep, at most once per 40 ticks per bot (a restart inside the gap is counted as
  `breakthroughs_deferred` and the running sweep goes on).
- **Sweep.** By default 40 collider rays per bot per tick (plus an outline re-cast on every second ray for
  decor such as rails and cobweb), walking a 2048-direction lattice in a per-sweep random rotation, so any
  partial sweep is spatially uniform. A full sweep takes about 51 ticks. The budget is shared: with several
  sweeping bots each gets `min(rays per tick, global rays per tick / bots)`, and it is halved while the tick
  headroom latch is set. Rays fill the bot's own memories: a free-length ring (for the openness estimate), a
  65x65x65 observed-occupancy window (about 69 KB, allocated on the first sweep), a hazard field for lava,
  water and traps, a ledger of valuable-block sightings (cap 64, entries expire after 6000 ticks), and a POI
  evidence window. A remembered lava or water cell is forgotten by a ray only when the ray proves the cell
  empty: the fluid shape is partial height (a source is 8/9 of a block, flowing fluid lower), so a ray that
  merely crosses the open top of the cell passes over the surface and proves nothing. The proof needs the
  ray's part inside the cell to reach the lowest tenth of it; a cell that is only grazed stays remembered and
  reads as fluid in the occupancy window.
- **Shadow POI scoring**, every 20 ticks per bot (staggered by `uuid.hashCode() & 15`). The scorer combines
  the non-natural blocks the rays saw, visible entities (through `canObserveEntity`) and the cave openness
  estimate into a band: `NONE`, `POSSIBLE`, `CAVERN_ONLY`, `STRUCTURE_CERTAIN` or `MANDATORY` (warden risk).
  Only band changes are logged. It is the one heavy operation of the tick; nothing acts on it.
- **The bot's own edits.** Blocks a bot placed (torches, planks, beds, seals) are recorded per dimension and
  never count as structure evidence. The record is persisted to `config/minecraftai/mining_assist_edits.json`,
  written by a background thread when it changed and at least 1200 ticks have passed, plus one synchronous
  write on server stop. A missing or corrupt file is treated as empty. `edits.sidecar=false` keeps it in memory.
- **Biome.** The biome id at the bot's own feet (the F3 equivalent) is read once per POI evaluation. It marks
  the deep dark (for the future deep-dark veto, logged only) and lush caves (so azalea-tree logs and leaves
  are not mistaken for structure wood).

When a bot stops mining for 2400 ticks (two minutes) its state is released after one last cost line. Lava
memory never expires by time; a stopped mission is the only time-based exit.

## Configuration

All keys are optional and live under `miningAssist` in `config/minecraftai.json`. Out-of-range values are clamped
and wrong-typed values fall back to the default, each with one `assist_config_warning` at start-up. Keys that
are read in P0:

| Key | Default | Effect in P0 |
|---|---|---|
| `mode` | `sense` | See Modes. |
| `sense.raysPerTick` | 40 | Collider rays per bot per tick (1..256). |
| `sense.globalRaysPerTick` | 640 | Ray budget shared by all sweeping bots (1..4096). |
| `sense.adaptiveThrottle` | true | Halve rays while the tick headroom latch is set. Off when deterministic. |
| `sense.shadowLog` | true in every mode that senses (all but `off`) | Band changes, sightings, session and cost lines. |
| `tick.startWorkMs`, `tick.abortWorkMs` | 38, 48 | Start and abort gates of the tick headroom. P0 only reads the headroom's ray-halving latch (halve above 44 ms of work per tick, re-arm below 40 ms), so these two keys have no visible effect until an acting phase uses the gates. |
| `poi.enabled` | true | Shadow POI scoring on or off. |
| `poi.cavernDimensions` | `["minecraft:overworld"]` | Dimensions where the open-cavern signal counts. |
| `poi.dedupeRadius` | 40 | Radius within which POI evidence counts as the same candidate site. |
| `edits.sidecar` | true | Persist the placed-blocks record. |
| `detour.announceMinValue` | 90 | Smallest raw value that gets an `assist_sighting` line (and, in `detour`/`all` mode, the rare-find chat line). |
| `detour.enabled` | true | Master switch of the detour itself; `detourActive()` is this AND the mode allowing it. |
| `detour.minValue`, `detour.minScore` | 45, 1.5 | Admission thresholds of the detour's cost/value ranking (design 4.2). |
| `detour.maxRadius`, `detour.maxUp`, `detour.maxDown` | 12, 2, 4 | How far off the strip face a detour may walk. |
| `detour.leaseTicks`, `detour.minIntervalTicks`, `detour.maxPerMission` | 300, 400, 24 | Per-mission budget and cooldown (`MissionAssistLedger`). |
| `detour.minFreeSlots`, `detour.startHpMargin`, `detour.lavaClearRadius` | 3, 4, 4 | Inventory reserve, extra hp margin to start, and how close a remembered lava cell may be. |
| `route.bucketMs` | 100 | Server-wide millisecond budget for starting a detour route (`RouteBudget`); reconfigured live when the config reloads. |
| `tick.startWorkMs`, `tick.abortWorkMs` | 38, 48 | Start and abort gates of the tick headroom; a running detour re-asks these before every break, route leg and drop chase (design 4.3/4.6). |
| `safety.deepDarkVeto` | true | Whether the own-cell deep-dark biome vetoes a detour start and self-aborts a running one. |

The other keys of the design (the remaining `poi.*`, `advisor.*`, `explore.*`) are parsed and validated but
nothing reads them until their phase ships.

## The detour (P1)

In `detour` or `all` mode, a mining task (currently only `OreDigTask`) may leave its strip face for a short,
walk-only excursion to a valuable it already saw with an ordinary view ray, then return to the exact face it
left. Nothing about this is a new privilege: the SAFE gate (mode, origin, TPS, hp, hazards, POI evidence,
traps — design 4.4) is asked before the detour starts and again, live, before every break, every new route
leg and every drop chase; any failure self-aborts the same tick and the bot walks back. Every action is
preceded by the same exact-cell re-proof every other mining task uses; a ray hit is still only a nomination.

- **Walk-only.** The detour only ever calls the ordinary walk-path start (`startSurfacePathTo`); it never digs
  a shortcut through stone the way OreDig's own fallback paths sometimes do. A valuable that a walk-only route
  cannot reach is skipped, not dug toward.
- **Opportunistic, not a search.** It only reacts to sightings the sensor already made in shadow; it never
  goes looking. A mission with nothing nearby worth the trip never detours.
- **Bounded.** At most 12 members of one vein per detour, at most a handful of detours per mission
  (`detour.maxPerMission`), a lease that lengthens a little with every break but is capped, and a mission-wide
  budget of detour ticks. Two bots never claim the same cell (`OreClaims`, a soft lease, not a lock).
- **Anchor invariant.** The strip-mining numbers (direction, leg, steps left, leg length) are never touched
  during a detour; only the anchor face substitutes for the live feet in the checkpoint. This relies on those
  four numbers changing only inside `stripMine`/`digTowardStep`/`digDownOneLayer`, which the detour code does
  not call.
- **A detour never fails the mission (I8).** Everything the engine does, past the mode-off check, runs inside
  an exception fence: a defect there stops the movement and the miner, clears the published state and logs
  once, but the mining task itself keeps running.
- **Honest limits (design 12), carried over from the design as written:**
  - The walk-only route crosses whatever cells the executor's raw A* chose; `Standability.isDangerous` omits
    pressure plates and tripwire, so a trap first seen mid-route is only caught by the live TICK gate, not by
    the start check.
  - `GoalExecutor.madeReplanProgress`'s y-decrease credit and the mission's own replan/tick budgets are the
    only backstop for a failure mid-excursion; the detour adds no new one.
  - A pre-existing, unclaimed target or vein break by another bot is not claim-protected (only a fresh
    `scanBonusOre` cell is); a double break is harmless because each bot's own exact-once ledger re-proves.
  - The anchor invariant above depends on no other code path touching the four strip numbers while a detour
    is live; it was true at the time this phase shipped and is worth re-checking if the strip-mining code
    changes.

### What changed for a bot

With `detour` or `all` mode on: a bot mining with assist on may leave its strip face for up to about 12
blocks, mine a short vein of a valuable it happened to see, and return to the exact face it left, walking
back rather than teleporting. If the value found is at least `detour.announceMinValue` (default 90) and the
bot has not announced one in the last 600 ticks, it says one line in chat, for example "Spotted diamond ore
nearby, grabbing it." Nothing else about the mission changes: the same tool policy, the same strip pattern,
the same anchor once it is back.

The sensing radius is the existing `perception.radius` of the main config (default 16), capped at 24 blocks
for the sensor (the ray budget is counted in rays, so an unbounded radius would make every ray longer with no
compensation); the assist never sees farther than a bot's ordinary perception does.

## Logs

Shadow logging is deliberately low volume and follows `LOGGING.md`. Everything is greppable with
`event=assist_`. The events and their fields are listed in `LOGGING.md`. In short: one `assist_config` at
start-up; `assist_gate` when a bot's gate changes; `assist_sense_enabled` / `assist_sense_disabled` around a
sensing session (a session ends after 40 quiet ticks, so short interruptions do not log);
`assist_poi_band` when the scorer's band differs from the band of the last line, at most one line per 200
ticks per bot (a band that flips back inside the gap writes nothing; the line carries `withheld`, the number
of changes it held back); `assist_sighting` for rare finds (at most 6 per window);
`assist_sense_summary` once per bot per minute of sensing with rays, milliseconds and counts; and
`assist_tick_failed` if the coordinator's exception fence ever fires.

## Tests and evidence

- JUnit covers the pure kernels, the adapters' source contracts (no raw world reads outside the one
  first-hit read, no privileged primitive, no task assignment), the tick coordinator's position between the
  danger scan and the goal executor, and the wiring in `MinecraftAiMod` and `RuntimeLifecycleCoordinator`.
- The GameTest and verify harness call `MiningAssistRuntime.setHarnessDefaultOff(true)`, which keeps the mode but
  turns the harness-off flag on. The gate then refuses every bot, and the hooks do nothing at all, until a
  test opts a bot in with `MiningAssistRuntime.forceEnable(uuid)` (which also makes the run deterministic).
  An explicit `MINECRAFTAI_MINING_ASSIST` or file mode still wins. The method is named for what the flag means:
  the design text spells the same call `setHarnessDefault(false)`, which reads inverted, so a harness entry
  point written from the design must use `setHarnessDefaultOff(true)`. A source-contract test pins it as the
  first statement of `MinecraftAiHarnessTestMod.onInitialize`.
- `MiningAssistSenseGameTests` (GameTest, `src/gametest/java/io/github/zoyluo/minecraftai/mining/assist`) runs the
  sensor in a real Minecraft world, through the real coordinator, with strict-survival capabilities. Every test
  builds its own sealed stone fixture, waits until the bot is underground by the world's own sky test, opts the
  bot in with `forceEnable` and gives it a real mining-class task (`MISSION`, `PLAYER_COMMAND`); the tests that
  need a stationary bot pause that task object in place, so it stays the bot's active task and the ray sequence
  is deterministic. It checks: ores in plain view are sighted within two sweeps and the recall over 48
  single-face ores between 2 and 8 blocks away is at least 90 percent; an ore behind one stone layer (and an
  ore behind another ore) never enters the ledger over 20 sweeps and stays UNKNOWN in the occupancy window
  (the x-ray canary, with a positive control); an un-forced bot, and a bot whose task has a `VERIFY`, `SAFETY`
  or `SYSTEM_BACKGROUND` origin, never gets state, profiler sections or log lines; an open audit session and a
  degraded TPS verdict close the gate at once; every one of the five mining classes is sensed and a non-mining
  task is not; a mineshaft palette placed by the test scores `STRUCTURE_CERTAIN` (rails and cobweb through the
  outline pass, torches counted as weak evidence, a chest minecart as entity evidence) while the same kind of
  blocks placed by the bot through `BuildAction.placeBlockAt` are recorded in the placed ledger and never score;
  a real `OreDigTask` mission's break is peeked and reveals the ore behind it; a real strip mine is peeked and
  its own torches never score; four bots sensing at once stay cheap; a pause, an abort, a re-assignment and a
  despawn leave no state behind; the per-minute cost line and the release of an idle bot's state follow the
  documented clocks. Measured numbers are written to the server log with the tag `[assist-gametest]`.
- The gate wiring (which origin, whether an audit session is open, TPS) is unit-tested through the pure
  resolver with fakes and pinned by a source contract on the live lookups, so an edit that ignored the audit
  session or treated a missing origin as real would fail a test.
- `scripts/evidence_run.sh` pins the assist to `off` for every scenario. `--assist <mode>` opts in for a local
  run. The mode is exported as `MINECRAFTAI_MINING_ASSIST` and written into both the runtime `config/minecraftai.json` and
  the sealed `effective-config.redacted.json`, so `config_hash` covers it, and into the manifest as
  `mining_assist_mode`. The validator checks that the two agree and refuses a non-`off` mode for the
  certifying `*_from_zero` Mining First scenarios. `scripts/ci_static_check.sh` fails if any workflow sets a
  non-`off` mode. A bundle sealed before this feature has neither the manifest key nor the config section and
  is read as `off`; that legacy leniency is deliberate (a bundle with only one of the two is rejected), and it
  means the attestation of an old bundle rests on the commit it was produced from.

## Honest limits

- The sensor has run in a real Minecraft world only through the GameTests above, not yet on a live modded
  server. There, with strict survival: 48 of 48 single-face ores between 2 and 8 blocks away were sighted
  after two sweeps (4120 rays; the design asks for at least 90 percent), no ray was "unknown" (the chunk ring
  around the bot was loaded), and an ore in plain view about 5 blocks away was first sighted after 120 to 400
  rays. Steady state per bot per tick (a sweep step of 40 collider rays plus the decor re-casts) averaged
  about 0.1 to 0.2 ms with a 0.3 ms 95th percentile, the POI evaluation (every 20 ticks) about 0.2 to 0.5 ms;
  four bots together cost about 0.4 to 0.85 ms per tick, and no steady-state assist section exceeded 3 ms.
  The first sweep step of a cold JVM cost 23 to 32 ms and the first POI evaluation 17 to 90 ms (class loading
  and JIT, once per server start, so the first mining session after a start can show one slow tick). The
  design's ON against OFF cost lane on a running server (`TickHeadroom` p99) and the `assist_recall_probe`
  verify scenario have still not been run. The shipped default is `sense`, so until they have, a server that
  sees a tick-time effect should set `miningAssist.mode` to `off` (the only cost of a wrong guess is that
  sensing logs disappear). The synthetic-cavern unit test covers the ray geometry (240 of 240 ores).
- The break peek observes the broken cell through the ordinary cell observation (a ray from the eye to the
  centre of the cell). A hole at foot level in a wall, mined from the cave while the block above it is still
  solid, is not observable that way: the break is counted in `breaks_unconfirmed`, the cell does not enter the
  bot's dug ring, and the occupancy window learns the cell is open from the next rays instead. The six
  neighbours are still peeked (the ore behind such a block is still found). An eye-level break, and the
  breaks of a real strip-mining tunnel (14 of 14 in the GameTest), are confirmed.
- A collider ray passes through pressure plates and tripwire (they have no collision shape), so trap recall
  is limited to blocks such as TNT and dispensers. Plates only show up in the outline decor pass, which feeds
  the POI window, not the hazard field.
- The POI scorer gives any modded entity a small score on its own. In a modded pack a modded mob in line of
  sight can therefore raise a `POSSIBLE` band in the shadow logs. Harmless while nothing acts on it, but the
  shadow logs should be reviewed before phase P2 does.
- The placed-blocks record is keyed by dimension id only and lives in the shared config folder, so a block a
  bot placed in one world is also treated as bot-placed at the same coordinates in another world of the same
  installation. Weak-evidence rules bound the cost.
- The occupancy window says what the rays did, not what is there. AIR means "a view ray passed through and no
  collider or fluid was struck": a ray passes through cells with no collision shape (fire, cobweb, powder
  snow, pressure plates, tripwire, sweet berry bushes) and over the top of partial-height fluid, so those cells
  read as AIR. A remembered lava or water cell is put back to FLUID when a ray only grazes it, but a fluid
  cell no ray has struck yet still reads AIR. A consumer that decides where the bot may walk (a later
  phase) must re-observe the feet and head cells through `OreScan.observe` rather than trust AIR, or the
  occupancy needs a separate "transparent, contents unproven" state first.
- The break peek restarts the sweep at most once per 40 ticks and only for open space no ray has seen. In a
  cavern-heavy vein the raised rate can still be held for about half the time; `breakthroughs` and
  `breakthroughs_deferred` in the cost summary say how often.
- The tick headroom sees each tick's work clamped to 100 ms, so one autosave or GC pause does not latch the
  ray-halving throttle; several such ticks in a row still do. Read `throttled_out` with that in mind.
- Sightings are nominations. A future consumer must re-prove the exact cell through the ordinary observation
  path before acting on one.
- The openness estimate needs about 16 ticks of sweep after arriving somewhere new before it is valid.
- Sensing is underground only and only for the five task classes above. The mandatory warden-risk band is
  scored and logged in P0 but does not stop the bot; that arrives with the POI phase.
- The placed-blocks record keeps one 8192-cell least-recently-used set per dimension id it has seen, and the
  sidecar file holds all of them (a full dimension is about 172 KB; a file over 8 MiB is refused on load and
  the record starts empty). A vanilla-plus-Fabric pack has a handful of dimensions, so this does not bind today; a
  pack with dynamically created dimensions would need a cap on the dimension count.
- The remembered hazards of one bot are capped at 4096 cells. When the cap is passed the farthest 256 cells
  are dropped by a sort of the whole field, a few milliseconds once per 256 newly seen cells; it only occurs
  next to a very large lava or water body.

## Observation predicates by caller group

The block observation predicates (`canObserveBlock*`, `canObserveCell*`) are an older family than the view
sensor and follow the block's own shape (`FaceAim`): a block with a collision shape is aimed at through it
with a COLLIDER ray; a non-colliding, non-fluid block (torch, rail, cobweb, plant, crop, banner) is aimed at
through its outline with an OUTLINE ray, because a player in plain view sees it. That is visibility, not
solidity. By caller group:

1. Target discovery (containers, stations, crops, ores, decor lists) counts outline-only blocks as seen.
2. Hazard and unknown-cell fail-closed checks read the state after the proof and decide on it, so a seen
   cobweb is a known cobweb, never "known safe" (fluid cells are unchanged: no outline, full-cell ray).
3. Tunnel-cell checks use `canObserveCell || canObserveBlock`, which already accepted non-colliding cells.
4. Support, ground and standability proofs (a stand cell's floor, a torch or depot floor) use
   `canObserveCollider*`, which never accepts a block without a collision shape; the caller still checks the
   collision shape or `Standability`.
5. Placement proofs (`BuildAction`) aim at the support's outline shape and are decided by an exact ray hit
   on that face.

`canObserveFarmCell` is the farm-specific cell query (top-of-shape samples, the empty cell above a field
accepted).

Intended consequence: under strict survival, food planning (a "what can I eat here" survey) now sees short
grass, ferns and sweet berry bushes that are in plain view, where the old collider-only proof never
accepted them. Nothing behind a wall or out of range becomes visible; only the plants a player looking at
the spot would see.

## Invariants the code comments cite

The design document is not in this repository, so the invariants that comments, tests and log lines refer
to by number are restated here (design section 1):

- **I1 Honest sensing.** Perception is first-hit rays clamped to the live perception radius. A ray hit is
  only a nomination; any action is preceded by an `OreScan.observe*` or `ObservableWorldQuery` re-proof of
  that exact cell. Unknown is never absent, safe or hazardous. A ray to a block aims at that block's own shape (`FaceAim`: the collision shape, or for a block with none, such as a torch, rail, cobweb, plant or crop, its selection outline with an OUTLINE ray), so "observable" means "in plain view" for those too. That is visibility only: what may be stood on, leaned on or placed against is decided by a collider proof (`canObserveCollider*`) or by reading the collision shape after the proof, never by "I can see a torch there". The shape is read to choose the aim point; the decision stays ray-proven. By caller group: target discovery counts an outline-only block as seen; hazard and unknown-cell checks read the state after the proof, so a seen cobweb is a known cobweb and never "known safe" (fluid cells are unchanged); tunnel-cell checks use `canObserveCell || canObserveBlock`, which already accepted non-colliding cells; support, ground and standability proofs use `canObserveCollider*`; placement proofs aim at the support outline and are decided by an exact ray hit; `canObserveFarmCell` is the farm-specific query (top-of-shape samples, the empty cell above a field accepted).
- **I2 No new privilege.** No teleport, forced pickup, hidden scan, `StructureManager`, `getBlockEntity` or
  `maybeHas`. `castViewRay` never calls `CapabilityRuntime.decide`. The own-cell reads beyond position are
  exactly two, both at the bot's feet and both pinned to one call in one file by a source contract: the biome
  id (`PoiDetector`, the F3 equivalent) and the sky-visibility flag (`MiningAssistCoordinator`, the
  underground test, the same call `DangerWatcher` and `MineValuablesTask` already make). The design text
  names only the biome; this is the corrected statement. The break peek and the entity evidence reuse the
  existing `OreScan.observe` and `canObserveEntity` helpers, whose answers remain observation-bound in
  every profile because hidden scanning is retired; `castViewRay` itself is profile independent.
  The palette prefilter of the observable block scans (`SectionPrefilter`: `LevelChunkSection.maybeHas` and
  one state read, run before the ray proof in `OreProspector` and `WorkshopLocator`) is a cost-only conjunct:
  it only puts the cheaper test first, a cell is still returned only when it matches AND is observable, the
  prefilter's answer never leaves the scan, and no decision (target, log line, notice, abort) reacts to a
  section or cell that matched but was not seen. `maybeHas` stays banned from the sensor and the assist.
- **I4 Origin and flag gating.** The assist runs only for `MISSION`, `PLAYER_COMMAND`, `PLAYER_PANEL`,
  `LLM_TOOL` and `JOB` origins, never while `MiningEvidenceAudit.hasSession(uuid)`, and only when the mode
  allows. The harness defaults it off. With the mode off a hook is one static check.
- **I5 G1 and G2.** No task assigns tasks; the assist assigns, pauses and resumes nothing. Everything runs
  on the server thread; the only background work is the sidecar writer, which is handed an immutable string.
- **I11 Measure before acting.** P0 ships sensing in shadow; acting features ship later behind flags.
- **I15 Hazard memory is monotone.** Lava is forgotten only by re-observation of the cell as non-fluid (a
  first hit that read a non-fluid state, or a ray that proves the cell empty as described above), by mission
  end, by a dimension change, or by capacity eviction. Nothing ages out by time.

I3, I6 to I10 and I12 to I14 concern the detour, the POI stop and the OreDig anchors and arrive with the
phases that implement them.
