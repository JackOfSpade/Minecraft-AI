# Night behaviour

## Sleeping

Bots never sleep. There is no `sleep` tool, task type, command or panel button: whether the night is skipped
is decided by the human players alone, through vanilla's sleep vote.

Only human players count in that vote. Vanilla counts every non-spectator player in the world, so an awake
bot (ours, or another mod's fake player such as PvP BOT's HeroBot) would stop a human from ever skipping the
night at the default `playersSleepingPercentage` of 100. `SleepManagerHumansOnlyMixin` hands the vote a list
without bots; the gamerule percentage, the "X/Y players sleeping" message and the morning wake-up then work
exactly as vanilla among the humans. A player is a bot when it is an `AIPlayerEntity` or its connection runs
over a netty `EmbeddedChannel` (the way fake-player mods fake a client; real players use a socket or a local
channel). See `network/PlayerKind`. With no human online the vote never skips.

Bots do not cause phantoms either. Phantoms spawn around a player whose "time since last rest" statistic is
high, and a bot that never sleeps would pile that statistic up forever, so vanilla would spawn phantoms around
the bot and they would attack the humans near it. `PhantomSpawnerHumansOnlyMixin` filters bots out of the
player list `PhantomSpawner.tick` iterates (the same `PlayerKind` predicate as the sleep vote); humans keep
exact vanilla behaviour and no statistic is edited. Phantoms that already exist are unaffected.

## Automatic lighting never happens on the surface

Two reflexes in `DangerWatcher` light an idle bot's surroundings on their own when it carries torches (a third, for a bot stuck in the dark, is below):

* the night top-up: at night, idle, with torches, start `light_area` (radius 8, up to 8 torches);
* the dark-spot reflex: the spot is below the torch threshold in both block light and combined light.

They are gated by `night.autoLight` in `minecraftai.json` (default `true`), as is the stuck-in-the-dark answer. The pre-rename key
`night.autoSleep` is still read (it never controlled sleeping); when both are present `autoLight` wins.
`night.torchLightThreshold` (default 8) is the light level below which a cell counts as dark.

None of them spends torches on the surface. `SurfaceCheck.isOnSurface` looks straight up from the bot: air
and fluids do not block, natural tree and mushroom growth does not block (logs, leaves, vines, cocoa, bee
nests, mangrove roots and propagules, moss carpets, snow layers, big and small mushrooms, fungi, wart blocks,
shroomlight, plus tall grass and other plants a bot walks through), and any other block is a roof. Reaching
the top of the world without a roof means the bot is on the surface, so a bot in the open or under a tree
canopy is left alone (a throttled `auto_light_skipped reason=surface` event is logged), while a bot in a cave,
a room or under an overhang is lit. The exemption list lives in one documented place, `SurfaceCheck.classify`.

When an automatic reflex does start lighting (for example at a cave mouth), `LightAreaTask.automatic` also
drops every candidate cell that is itself on the surface, so torches only go under the roof.

Explicit requests (the `light_area` tool, `assign_task light_area`, `/minecraftai task assign <bot> light_area`
and task-board jobs) light wherever they are asked to, surface included.

## Stuck in the dark

A bot that stands for eight seconds in one dark cell under a roof (`DangerWatcher.isDarkTrapCell`: below the torch
threshold in block and combined light, and not on the surface) while idle or running a stuck `move` is judged
trapped (`dark_trap_detected`). An operator profile that allows `emergencyTeleport` surfaces it. Strict survival
denies the teleport, so the bot answers with what it carries (`dark_trap_response`):

* `LIGHT`: it carries a torch, so it starts `LightAreaTask.automatic` (underground only, like the reflexes above);
  a stuck `move` is paused first and resumes once the lighting ends;
* `CRAFT_TORCHES`: it carries no torch but coal or charcoal and sticks (or planks), so it crafts torches in the
  inventory grid; the lighting follows once it has them;
* `DIG_OUT`: nothing to light the cell with and nothing to make a torch from, so it tells the player once and starts
  `DigOutTask`, which digs a stair up out of the dark (see below);
* `LIGHTING_OFF`: `night.autoLight` is `false`, so it places nothing, whatever it carries, and says that automatic
  lighting is switched off (once).

A cell is answered with the same thing at most once: it is announced again only after the bot has moved or its
means changed (a torch used up, coal crafted), so the same trap is never reported every eight seconds.

### Digging out

`DigOutTask` is the answer of a bot that has nothing to light the cell with. It climbs a block per step, a block forward
and a block up (`OreClimb`'s rise, the stair OreDig digs up to an ore), until the cell it stands in is no longer a
dark trap: lit by anything, or under open sky. It never lights the surface and it does not know where the sky is; it
digs up, as a player does, and only what it sees decides each step. In the middle of a room, where there is no wall to
start a rise in, it first walks to the nearest wall it can see, and never back onto a cell it has stood in since its
last rise.

A step is opened only when what the bot sees allows it (`StairDig` through `MiningSafety.openingRefusal`): no fluid
or falling block in, over or beside any cell of the step (an open cell beside a lava or water cell that an earlier
opening revealed is refused too, since the flow comes in through it), nothing the break rule does not let a bot dig,
no player standing on it. A refused heading is turned from (right, left, back); when all four are refused, or a block
will not give, or the build limit is reached, the task ends and says why. A stuck `move` that was paused for it
resumes afterwards, as after the lighting.

After it breaks a cell, the bot gives the opening a factual safety look before accepting the light that may now enter
through it. It may identify lava or water only after a short real eye ray reaches that fluid's surface; with a carried
solid block it places that block into the opening by the ordinary reach/pick-ray placement path, records the cell as
sealed, and takes another direction. It never reopens or enters a sealed flow cell. If it has no suitable block, or
the placement is refused, it walks its recorded stair back down and ends with `dig_out_flow_unsealed:<reason>`.

Rock is mined with the lowest tool that can actually harvest it; a sword is not used as a bare-hand substitute. If no
usable pickaxe is carried but the bot can make one from its own materials, it crafts the cheapest suitable pickaxe
first, including placing and recovering a crafting table when needed. Bare-hand stone remains legal. A block that the
held tool cannot finish in the miner's ordinary timeout is refused before any swing using vanilla's break progress:
`break_refused:unbreakable` for a no-progress block and `break_refused:too_slow` otherwise.

The choice of direction also has no sky knowledge. At task start and after each landed rise, the bot considers only
open, fluid-free foot/head cells that pass `ObservableWorldQuery.canObserveCell` within its normal visible range; it
may prefer a direction whose seen light is brighter than its own cell, but never one where lava is seen. With no such
cell, it follows the ordinary wall-and-stair behavior. A lit adjacent cell that is visibly walkable is an escape route
to walk through, not a wall to dig toward.
