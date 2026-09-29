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

Two reflexes in `DangerWatcher` light an idle bot's surroundings on their own when it carries torches:

* the night top-up: at night, idle, with torches, start `light_area` (radius 8, up to 8 torches);
* the dark-spot reflex: the spot is below the torch threshold in both block light and combined light.

Both are gated by `night.autoLight` in `minecraftai.json` (default `true`). The pre-rename key
`night.autoSleep` is still read (it never controlled sleeping); when both are present `autoLight` wins.
`night.torchLightThreshold` (default 8) is the light level below which a cell counts as dark.

Neither reflex spends torches on the surface. `SurfaceCheck.isOnSurface` looks straight up from the bot: air
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
