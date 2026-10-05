package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mining.MiningChain;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
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
    /** A visible cave entrance gets one bounded, no-dig walk before the staircase resumes. */
    private static final int CAVE_SURVEY_MOVE_LIMIT = 240;
    /** Let Baritone settle an admitted route before treating an idle executor as a refusal. */
    private static final int CAVE_SURVEY_IDLE_GRACE_TICKS = 20;

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
    /** Caves previously surveyed by the owning request, supplied for its next safe re-descent. */
    private final Set<BlockPos> initialExcludedOpenCavities;
    /** Includes locally rejected cave routes as well as the parent-provided surveyed entries. */
    private final Set<BlockPos> excludedOpenCavities = new LinkedHashSet<>();
    private DescendToYTask descent;
    private int targetY = Integer.MAX_VALUE;
    /** A cave completion asks the parent to survey newly visible space before one bounded re-descent. */
    private boolean completedAtOpenCavity;
    /** The cave entry the parent must exclude when it starts a later fresh staircase. */
    private BlockPos completedOpenCavity;
    /** A currently admitted no-dig walk into a dry, observed, standable cave entry. */
    private BlockPos caveSurveyEntry;
    private BlockPos caveSurveyOrigin;
    private int caveSurveyStartedElapsed;

    private MiningExplorationTask(Set<Block> requestedBlocks, boolean geologicalProbe,
                                  Set<BlockPos> excludedOpenCavities) {
        this.requestedBlocks = Set.copyOf(requestedBlocks);
        this.geologicalProbe = geologicalProbe;
        this.initialExcludedOpenCavities = excludedOpenCavities == null
                ? Set.of() : Set.copyOf(excludedOpenCavities);
        this.resourceLabel = requestedBlocks.stream()
                .map(block -> BuiltInRegistries.BLOCK.getKey(block).toString())
                .sorted()
                .collect(Collectors.joining(","));
    }

    /** Builds an ore-depth exploration handoff for a known ore family. */
    public static MiningExplorationTask forOres(Set<Block> ores) {
        return forOres(ores, Set.of());
    }

    /** Package-visible continuation used when a parent resumes below an already surveyed cave. */
    static MiningExplorationTask forOres(Set<Block> ores, Set<BlockPos> excludedOpenCavities) {
        Set<Block> knownOres = new LinkedHashSet<>();
        if (ores != null) {
            for (Block block : ores) {
                if (block != null && MiningChain.forOre(block) != null) {
                    knownOres.add(block);
                }
            }
        }
        return new MiningExplorationTask(knownOres, false, excludedOpenCavities);
    }

    /**
     * Builds the appropriate handoff for a direct mine/gather source set.  Known ores use their
     * dimension-aware table entry; common geological sources get only a short source probe.
     */
    public static MiningExplorationTask forBlocks(Set<Block> blocks) {
        return forBlocks(blocks, Set.of());
    }

    /** Package-visible continuation used when a parent resumes below an already surveyed cave. */
    static MiningExplorationTask forBlocks(Set<Block> blocks, Set<BlockPos> excludedOpenCavities) {
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
                ? new MiningExplorationTask(geologicalBlocks, !geologicalBlocks.isEmpty(), excludedOpenCavities)
                : new MiningExplorationTask(knownOres, false, excludedOpenCavities);
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
        if (caveSurveyEntry != null) {
            return 0.5D;
        }
        return descent == null ? 0.0D : descent.progress();
    }

    @Override
    public boolean isWaiting() {
        return descent != null && descent.isWaiting();
    }

    /** True only when the fresh safe staircase stopped at an observed dry cave mouth. */
    public boolean completedAtOpenCavity() {
        return state == TaskState.COMPLETED && completedAtOpenCavity;
    }

    /** The observed cave stand to exclude from a later fresh staircase, if cave surveying completed. */
    public BlockPos completedOpenCavity() {
        return state == TaskState.COMPLETED && completedOpenCavity != null
                ? completedOpenCavity.immutable() : null;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        completedAtOpenCavity = false;
        completedOpenCavity = null;
        caveSurveyEntry = null;
        caveSurveyOrigin = null;
        excludedOpenCavities.clear();
        excludedOpenCavities.addAll(initialExcludedOpenCavities);
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
        if (!startDescent(bot, "mining_exploration_start_failed")) {
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
        if (caveSurveyEntry != null) {
            tickCaveSurvey(bot);
            return;
        }
        if (descent == null) {
            complete();
            return;
        }
        if (descent.state() == TaskState.RUNNING) {
            descent.tick(bot);
        }
        if (descent.state() == TaskState.COMPLETED) {
            BlockPos caveEntry = descent.completedOpenCavity();
            descent = null;
            if (caveEntry != null) {
                startCaveSurvey(bot, caveEntry);
                return;
            }
            BotLog.action(bot, "mining_exploration_reached_depth",
                    "resource", resourceLabel.isBlank() ? "unknown" : resourceLabel,
                    "at_y", bot.blockPosition().getY(),
                    "target_y", targetY);
            complete();
        } else if (descent.state() == TaskState.FAILED || descent.state() == TaskState.CANCELLED) {
            fail("mining_exploration_failed:" + descent.failureReason());
        }
    }

    /** Starts a new safe child, carrying every cave entry that this episode has already rejected. */
    private boolean startDescent(AIPlayerEntity bot, String failurePrefix) {
        descent = DescendToYTask.forMiningExploration(targetY, excludedOpenCavities);
        descent.start(bot);
        if (descent.state() == TaskState.FAILED) {
            fail(failurePrefix + ':' + descent.failureReason());
            return false;
        }
        return true;
    }

    /**
     * The descent has found a fully visible, dry, standable cave floor. Route to exactly that
     * observed cell without breaking or placing. If Baritone cannot admit it, reopen the stair
     * while excluding this entrance rather than reporting a cave the bot never entered.
     */
    private void startCaveSurvey(AIPlayerEntity bot, BlockPos entry) {
        caveSurveyEntry = entry.immutable();
        caveSurveyOrigin = bot.blockPosition().immutable();
        caveSurveyStartedElapsed = elapsed;
        ActionResult route = bot.getActionPack().startSurfacePathTo(caveSurveyEntry);
        if (route.isFailed()) {
            BotLog.action(bot, "mining_exploration_cave_route_refused",
                    "at", caveSurveyEntry.toShortString(), "reason", route.reason());
            resumePastUnroutableCave(bot);
            return;
        }
        BotLog.action(bot, "mining_exploration_cave_survey_started",
                "at", caveSurveyEntry.toShortString(), "target_y", targetY);
    }

    private void tickCaveSurvey(AIPlayerEntity bot) {
        if (caveSurveyEntry == null) {
            return;
        }
        if (bot.blockPosition().equals(caveSurveyEntry)) {
            completeCaveSurvey(bot);
            return;
        }
        int age = elapsed - caveSurveyStartedElapsed;
        boolean moved = caveSurveyOrigin != null && bot.blockPosition().distSqr(caveSurveyOrigin) > 1.0D;
        boolean routeEnded = age > CAVE_SURVEY_IDLE_GRACE_TICKS && bot.getActionPack().isPathExecutorIdle();
        if (age <= CAVE_SURVEY_MOVE_LIMIT && !routeEnded) {
            return;
        }
        bot.getActionPack().stopAll();
        if (moved) {
            // A route can end one cell short after exposing a side passage. The actual movement
            // still surveyed the cave honestly, so return control to the parent at that new view.
            completeCaveSurvey(bot);
            return;
        }
        BotLog.action(bot, "mining_exploration_cave_route_unmoved",
                "at", caveSurveyEntry.toShortString(),
                "reason", routeEnded ? "route_ended" : "timeout");
        resumePastUnroutableCave(bot);
    }

    private void completeCaveSurvey(AIPlayerEntity bot) {
        BlockPos entry = caveSurveyEntry;
        caveSurveyEntry = null;
        caveSurveyOrigin = null;
        completedAtOpenCavity = true;
        completedOpenCavity = entry == null ? null : entry.immutable();
        BotLog.action(bot, "mining_exploration_open_cavity_reached",
                "resource", resourceLabel.isBlank() ? "unknown" : resourceLabel,
                "at", bot.blockPosition().toShortString(),
                "entry", entry == null ? "none" : entry.toShortString(),
                "target_y", targetY);
        complete();
    }

    private void resumePastUnroutableCave(AIPlayerEntity bot) {
        if (caveSurveyEntry != null) {
            excludedOpenCavities.add(caveSurveyEntry.immutable());
        }
        caveSurveyEntry = null;
        caveSurveyOrigin = null;
        if (startDescent(bot, "mining_exploration_cave_reroute_failed")) {
            BotLog.action(bot, "mining_exploration_cave_reroute",
                    "excluded_entries", excludedOpenCavities.size(), "target_y", targetY);
        }
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        if (descent != null && descent.state() == TaskState.RUNNING) {
            descent.pause(bot);
        } else if (caveSurveyEntry != null) {
            bot.getActionPack().stopAll();
        }
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        if (descent != null && descent.state() == TaskState.PAUSED) {
            descent.resume(bot);
        } else if (caveSurveyEntry != null) {
            caveSurveyOrigin = bot.blockPosition().immutable();
            caveSurveyStartedElapsed = elapsed;
            ActionResult route = bot.getActionPack().startSurfacePathTo(caveSurveyEntry);
            if (route.isFailed()) {
                resumePastUnroutableCave(bot);
            }
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        if (descent != null && (descent.state() == TaskState.RUNNING || descent.state() == TaskState.PAUSED)) {
            descent.abort(bot);
        }
        bot.getActionPack().stopAll();
    }
}
