# Mining exploration and ore depths

Mining begins with normal, observed targets. When a requested ore is not currently visible and
the bot is above that ore's downward-search layer, it may build its bounded, survival-safe
staircase to that layer. On arrival it stops descending and returns to observed cave/route
exploration; it never treats a target depth as permission to continue digging below it.

The bot reads only its own coordinate/Y value for this decision, just as a player can use F3.
It does not use ore locations, chunk data, or a height map to choose a staircase.

## Vanilla Java ore policy

| Dimension | Requested block family | Saved downward target Y | Notes |
| --- | --- | ---: | --- |
| Overworld | Coal / deepslate coal | 45 | Practical underground coal layer. |
| Overworld | Copper / deepslate copper | 43 | Dripstone caves can be better, but are explored only when actually observed. |
| Overworld | Iron / deepslate iron | 14 | The reachable lower iron band; the high mountain band is not reached by digging down. |
| Overworld | Lapis / deepslate lapis | 0 | Center of the ordinary lapis distribution. |
| Overworld | Gold / deepslate gold | -16 | General underground target; badlands' high gold band is explored only when already present. |
| Overworld | Redstone / deepslate redstone | -58 | Deep, bedrock-safe operational band. |
| Overworld | Diamond / deepslate diamond | -58 | Deep, bedrock-safe operational band. |
| Overworld | Emerald / deepslate emerald | 85 | Mountain-only target. From below it, the bot does not dig farther down. |
| Nether | Nether quartz ore | 114 | If already below it, explore what is visible rather than digging downward. |
| Nether | Nether gold ore | 114 | Same rule as quartz; it is a separate vanilla ore family. |
| Nether | Ancient debris | 16 | Requires a diamond-tier pickaxe; this is a descent target only, never an explosion strategy. |

The core table lives in `MiningChain`, so planning, runtime exploration, and mining-assist
ranking share the same values. Unknown/modded ores intentionally have no guessed depth: they stay
in ordinary observed exploration until a mod supplies a profile.

The values use current Java-edition generation guidance: [Minecraft Wiki's every-block mining
table](https://minecraft.wiki/w/Tutorial%3AObtaining_every_block) covers the Overworld and both
Nether ores, while [the Ancient Debris page](https://minecraft.wiki/w/Ancient_debris) documents
its Nether distribution. The table chooses downward-reachable operational targets where a source
also describes a statistically higher mountain band; this avoids a "mine downward away from the
ore" mistake.
