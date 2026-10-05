package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mining.MiningChain;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * A small, non-durable handoff used after a mining request has exhausted its ordinary observed
 * search.  It knows a requested ore family's documented downward layer, but never has a location
 * for that ore: it can only begin a bounded safe staircase and then returns control to the
 * caller's normal observed cave/route search.
 *
 * <p>This is deliberately not a new goal step.  In particular it cannot restore the retired
 * {@code DESCEND_TO_Y} checkpoint path; its child is always freshly constructed and its progress
 * is intentionally not checkpointed.</p>
 */
public final class MiningExplorationTask extends AbstractTask {
    /** A short geological probe is enough to expose stone beneath snow/soil without guessing an ore layer. */
    private static final int GEOLOGICAL_PROBE_DEPTH = 12;
    /** Keep the generic probe away from the dimension floor and DescendToYTask's hard lower bound. */
    private static final int GEOLOGICAL_FLOOR_MARGIN = 5;

    /**
     * Non-ore blocks for which descending is a meaningful way to reveal an ordinary mining
     * source.  Everything else remains a normal observed gather/mine request rather than turning
     * an arbitrary block request into excavation.
     */
    private static final Set<Block> GEOLOGICAL_SOURCES = Set.of(
            Blocks.STONE,
            Blocks.DEEPSLATE,
            Blocks.COBBLESTONE,
            Blocks.COBBLED_DEEPSLATE,
            Blocks.GRANITE,
            Blocks.DIORITE,
            Blocks.ANDESITE,
            Blocks.TUFF,
            Blocks.CALCITE,
            Blocks.BLACKSTONE,
            Blocks.BASALT,
            Blocks.NETHERRACK,
            Blocks.END_STONE);

    private final Set<Block> requestedBlocks;
    private final boolean geologicalProbe;
    private final String resourceLabel;
    private DescendToYTask descent;
    private int targetY = Integer.MAX_VALUE;

    private MiningExplorationTask(Set<Block> requestedBlocks, boolean geologicalProbe) {
        this.requestedBlocks = Set.copyOf(requestedBlocks);
        this.geologicalProbe = geologicalProbe;
        this.resourceLabel = requestedBlocks.stream()
                .map(block -> BuiltInRegistries.BLOCK.getKey(block).toString())
                .sorted()
                .collect(Collectors.joining(","));
    }

    /** Builds an ore-depth exploration handoff for a known ore family. */
    public static MiningExplorationTask forOres(Set<Block> ores) {
        Set<Block> knownOres = new LinkedHashSet<>();
        if (ores != null) {
            for (Block block : ores) {
                if (block != null && MiningChain.forOre(block) != null) {
                    knownOres.add(block);
                }
            }
        }
        return new MiningExplorationTask(knownOres, false);
    }

    /**
     * Builds the appropriate handoff for a direct mine/gather source set.  Known ores use their
     * dimension-aware table entry; common geological sources get only a short source probe.
     */
    public static MiningExplorationTask forBlocks(Set<Block> blocks) {
        Set<Block> knownOres = new LinkedHashSet<>();
        Set<Block> geologicalBlocks = new LinkedHashSet<>();
        if (blocks != null) {
            for (Block block : blocks) {
                if (block == null) {
                    continue;
                }
                if (MiningChain.forOre(block) != null) {
                    knownOres.add(block);
                } else if (GEOLOGICAL_SOURCES.contains(block)) {
                    geologicalBlocks.add(block);
                }
            }
        }
        return knownOres.isEmpty()
                ? new MiningExplorationTask(geologicalBlocks, !geologicalBlocks.isEmpty())
                : new MiningExplorationTask(knownOres, false);
    }

    /** True only when an empty observed search is eligible for a mining exploration handoff. */
    public static boolean supports(Set<Block> blocks) {
        if (blocks == null) {
            return false;
        }
        for (Block block : blocks) {
            if (block != null && (MiningChain.forOre(block) != null || GEOLOGICAL_SOURCES.contains(block))) {
                return true;
            }
        }
        return false;
    }

    /** Convenience predicate for direct single-block mine requests. */
    public static boolean supports(Block block) {
        return block != null && supports(Set.of(block));
    }

    @Override
    public String name() {
        return "mining_exploration";
    }

    @Override
    public String describe() {
        return "Mining exploration for " + (resourceLabel.isBlank() ? "observed resources" : resourceLabel)
                + (targetY == Integer.MAX_VALUE ? "" : " to Y=" + targetY);
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return descent == null ? 0.0D : descent.progress();
    }

    @Override
    public boolean isWaiting() {
        return descent != null && descent.isWaiting();
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        int currentY = bot.blockPosition().getY();
        targetY = geologicalProbe
                ? Math.max(bot.level().getMinY() + GEOLOGICAL_FLOOR_MARGIN,
                        currentY - GEOLOGICAL_PROBE_DEPTH)
                : MiningChain.bestY(bot.level(), requestedBlocks);
        // This one comparison is the depth policy boundary: neither an ore table value nor a
        // generic probe may authorize digging farther down once the bot is already at/below it.
        if (!MiningChain.shouldDescend(currentY, targetY)) {
            BotLog.action(bot, "mining_exploration_observed",
                    "resource", resourceLabel.isBlank() ? "unknown" : resourceLabel,
                    "at_y", currentY,
                    "target_y", targetY == Integer.MAX_VALUE ? "none" : targetY);
            complete();
            return;
        }
        descent = DescendToYTask.forMiningExploration(targetY);
        descent.start(bot);
        if (descent.state() == TaskState.FAILED) {
            fail("mining_exploration_start_failed:" + descent.failureReason());
            return;
        }
        BotLog.action(bot, "mining_exploration_started",
                "resource", resourceLabel.isBlank() ? "unknown" : resourceLabel,
                "from_y", currentY,
                "target_y", targetY,
                "mode", geologicalProbe ? "geological_probe" : "ore_depth");
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (descent == null) {
            complete();
            return;
        }
        if (descent.state() == TaskState.RUNNING) {
            descent.tick(bot);
        }
        if (descent.state() == TaskState.COMPLETED) {
            BotLog.action(bot, "mining_exploration_reached_depth",
                    "resource", resourceLabel.isBlank() ? "unknown" : resourceLabel,
                    "at_y", bot.blockPosition().getY(),
                    "target_y", targetY);
            complete();
        } else if (descent.state() == TaskState.FAILED || descent.state() == TaskState.CANCELLED) {
            fail("mining_exploration_failed:" + descent.failureReason());
        }
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        if (descent != null && descent.state() == TaskState.RUNNING) {
            descent.pause(bot);
        }
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        if (descent != null && descent.state() == TaskState.PAUSED) {
            descent.resume(bot);
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        if (descent != null && (descent.state() == TaskState.RUNNING || descent.state() == TaskState.PAUSED)) {
            descent.abort(bot);
        }
    }
}
