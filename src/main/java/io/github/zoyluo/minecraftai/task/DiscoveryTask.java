package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.mining.OreProspector;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A bounded, locate-only search for resources a player can plausibly ask a companion to find.
 *
 * <p>The task intentionally has no collection action. It never mines, harvests, attacks, opens
 * storage, force-loads chunks, or treats a remembered/loaded cell as a discovery. A block or
 * entity is reported only after the normal observation predicate proves it is in the bot's live
 * view. When its local survey comes up empty, it walks one short
 * {@link io.github.zoyluo.minecraftai.navigation.NavRoute.Shape#DIRECTIONAL_PURSUIT directional}
 * hop; ordinary player chunk tracking then reveals more terrain just as it would for a human
 * player. The hop route itself is constrained to locally observed terrain by the navigation
 * fence.</p>
 */
public final class DiscoveryTask extends AbstractTask {
    /** Persistent hand-off for a later "show me" request. It records only an actually observed find. */
    private static final String LAST_FOUND_PLACE = "last_found";
    private static final String LAST_FOUND_LABEL = "last_found_label";

    /** A same-dimension successful discovery that can be demonstrated physically later. */
    public record FoundTarget(BlockPos pos, String label) {
        /**
         * What a show-location call demonstrates: the explicit coordinate, or the latest real find when none is given (empty if
         * there is neither). A target that is the latest find keeps the label the discovery proved ("a chest or other storage
         * container"), however the call was phrased: a name the model picked for it ("the bonus chest") is a claim nothing the
         * bot observed supports. Any other coordinate keeps the requested label.
         */
        public static Optional<FoundTarget> forShowing(Optional<FoundTarget> latestFind, BlockPos explicit, String requestedLabel) {
            if (explicit == null) {
                return latestFind;
            }
            return Optional.of(latestFind.filter(found -> found.pos().equals(explicit))
                    .orElseGet(() -> new FoundTarget(explicit, requestedLabel)));
        }
    }

    private enum TargetKind {
        BLOCK,
        SHEEP,
        CONTAINER
    }

    /** The observation proof that must accompany a matching block-state read. */
    private enum BlockObservation {
        BLOCK,
        /** Crops need outline samples; shape-less fluids safely fall back to a visible-cell proof. */
        OUTLINE_OR_CELL
    }

    /**
     * A find target is deliberately state-based rather than a small fixed enum of resources.
     * That lets the tool use the live block registry (including modded blocks) without turning a
     * request for a furnace, flower, or sapling into a hidden-world scan.
     */
    private record Target(TargetKind kind, String id, String displayName,
                          Predicate<BlockState> blockMatcher, BlockObservation blockObservation) {
        static Target block(Block block) {
            Identifier key = BuiltInRegistries.BLOCK.getKey(block);
            String display = "minecraft".equals(key.getNamespace())
                    ? key.getPath().replace('_', ' ')
                    : key.toString();
            // Crop outlines are often too small for a face-centre ray. A fluid has no outline,
            // so the same predicate deliberately reduces to a normal visible-cell proof instead.
            BlockObservation observation = (block instanceof CropBlock
                    || !block.defaultBlockState().getFluidState().isEmpty())
                    ? BlockObservation.OUTLINE_OR_CELL : BlockObservation.BLOCK;
            return blocks(key.toString(), display, state -> state.is(block),
                    observation);
        }

        static Target blocks(String id, String displayName, Predicate<BlockState> matcher,
                             BlockObservation observation) {
            return new Target(TargetKind.BLOCK, id, displayName, matcher, observation);
        }

        static Target sheep() {
            return new Target(TargetKind.SHEEP, "sheep", "sheep", null, null);
        }

        static Target container(String id) {
            return new Target(TargetKind.CONTAINER, id, "a chest or other storage container", null, null);
        }
    }

    /** The hard end-to-end search time: three real minutes at 20 ticks/second. */
    static final int MAX_SEARCH_TICKS = 3_600;
    /** A physical leg never asks Baritone to go farther than this from its current observed stance. */
    static final int HOP_DISTANCE = 12;
    /** A stalled route is released rather than silently consuming the whole search lifetime. */
    static final int HOP_TIMEOUT_TICKS = 240;
    /** Eight sectors at each of two radii covers the local area without an unbounded spiral. */
    static final int MAX_HOPS = 16;
    /** Re-run a visibility survey while travelling so newly loaded terrain is eligible. */
    static final int SURVEY_INTERVAL_TICKS = 20;
    /** A resumable OreProspector share; this never performs a synchronous world-sized scan. */
    static final long SURVEY_BUDGET_NANOS = 1_500_000L;
    private static final int MIN_SEARCH_RADIUS = 24;
    private static final int MAX_SEARCH_RADIUS = 64;
    private static final int[] SEARCH_RADII = {24, 48};
    private static final int[][] SEARCH_DIRECTIONS = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 1},
            {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };
    private static final Set<Block> IRON_ORES = OreScan.oreFamily(Blocks.IRON_ORE);
    private static final Target IRON_ORE = Target.blocks("iron_ore", "iron ore",
            state -> IRON_ORES.contains(state.getBlock()), BlockObservation.BLOCK);
    private static final Target MATURE_WHEAT = Target.blocks("mature_wheat", "mature wheat",
            DiscoveryTask::isMatureWheat, BlockObservation.OUTLINE_OR_CELL);
    private static final Target SHEEP = Target.sheep();
    private static final Target CONTAINER = Target.container("container");
    /**
     * The same search as {@link #CONTAINER}: a bonus chest is an ordinary chest, and nothing a bot can observe tells it from
     * another one (its loot table is not visible), so a find for it never claims to have found that particular chest.
     */
    private static final Target BONUS_CHEST = Target.container("bonus_chest");
    private static final Target FLOWERS = Target.blocks("flowers", "flowers",
            state -> state.is(BlockTags.FLOWERS), BlockObservation.BLOCK);
    private static final Target SAPLINGS = Target.blocks("saplings", "saplings",
            state -> state.is(BlockTags.SAPLINGS), BlockObservation.BLOCK);
    private static final Target PLANTS = Target.blocks("plants", "plants",
            state -> state.is(BlockTags.FLOWERS)
                    || state.is(BlockTags.SAPLINGS)
                    || state.getBlock() instanceof CropBlock
                    || (!state.isAir() && state.getFluidState().isEmpty() && state.is(BlockTags.REPLACEABLE)),
            BlockObservation.OUTLINE_OR_CELL);

    private final Target target;
    private final int searchRadius;
    private BlockPos anchor;
    /** Server time, rather than task-tick count, so the advertised three-minute limit survives TPS throttling. */
    private int searchStartedTick;
    private int latestSearchAgeTicks;
    private OreProspector.Scan blockSurvey;
    private int nextSurveyTick;
    private boolean firstSurveyFinished;
    private boolean hopInFlight;
    private int hopStartedTick;
    private int nextHopTick;
    private int hopsAttempted;
    private boolean announced;

    private DiscoveryTask(Target target, int requestedRadius) {
        this.target = target;
        this.searchRadius = Math.max(MIN_SEARCH_RADIUS, Math.min(MAX_SEARCH_RADIUS, requestedRadius));
    }

    /**
     * Resolves a player-facing noun to either one of the few semantic targets (sheep, a storage
     * container, mature wheat, an iron-ore family) or an exact block from the live registry.
     * Bare names use the vanilla namespace; modded targets retain their explicit namespace.
     */
    public static DiscoveryTask find(String requestedTarget, int radius) {
        String normalized = normalize(requestedTarget);
        Target target = specialTarget(normalized);
        if (target == null) {
            Optional<Block> block = resolveRegisteredBlock(normalized);
            if (block.isPresent()) {
                target = Target.block(block.get());
            } else if (isRegisteredItem(normalized)) {
                throw new IllegalArgumentException("find_target_is_item: " + requestedTarget
                        + " (find locates placed blocks, not loose items; use a block id or name instead)");
            } else {
                throw new IllegalArgumentException("unsupported_find_target: " + requestedTarget
                        + " (use any registered non-air block id/name, e.g. furnace, oak_sapling, "
                        + "modid:block; or use sheep, container, or bonus_chest)");
            }
        }
        return new DiscoveryTask(target, radius);
    }

    /**
     * Retrieves the latest real discovery in this dimension. The marker is written only by
     * {@link #finishFound(AIPlayerEntity, BlockPos)}, after the normal live-observation proof.
     */
    public static Optional<FoundTarget> latestFound(AIPlayerEntity bot) {
        var memory = BotMemoryStore.INSTANCE.of(bot.getUUID());
        String dimension = bot.level().dimension().identifier().toString();
        return memory.place(LAST_FOUND_PLACE)
                .filter(place -> dimension.equals(place.dimension()))
                .map(place -> new FoundTarget(place.pos(), memory.recall(LAST_FOUND_LABEL)
                        .filter(label -> !label.isBlank()).orElse("that location")));
    }

    private static String normalize(String requestedTarget) {
        if (requestedTarget == null || requestedTarget.isBlank()) {
            throw new IllegalArgumentException("missing_find_target");
        }
        return requestedTarget.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
    }

    /** Legacy resource phrases stay intentionally broader than a single registry entry. */
    private static Target specialTarget(String normalized) {
        String words = normalized.replace('-', '_');
        return switch (words) {
            case "iron", "iron_ore", "raw_iron", "minecraft:iron_ore", "minecraft:raw_iron" -> IRON_ORE;
            case "wheat", "mature_wheat", "wheat_crop", "wheat_crops", "minecraft:wheat" -> MATURE_WHEAT;
            case "sheep", "sheeps", "minecraft:sheep" -> SHEEP;
            case "bonus_chest" -> BONUS_CHEST;
            case "container", "storage", "storage_container" -> CONTAINER;
            case "flower", "flowers" -> FLOWERS;
            case "sapling", "saplings" -> SAPLINGS;
            case "plant", "plants" -> PLANTS;
            default -> null;
        };
    }

    /**
     * Try an exact identifier before the user-friendly hyphen and plural fallbacks. This keeps
     * valid ids such as {@code minecraft:weeping_vines} exact while accepting "furnaces" and
     * "crafting table" as natural requests.
     */
    private static Optional<Block> resolveRegisteredBlock(String normalized) {
        for (String candidate : identifierCandidates(normalized)) {
            Identifier id = Identifier.tryParse(candidate);
            if (id == null) {
                continue;
            }
            Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            if (block != null && !block.defaultBlockState().isAir()) {
                return Optional.of(block);
            }
        }
        return Optional.empty();
    }

    /** Distinguish a misspelled block from a valid item, without ever treating an item as a placed block. */
    private static boolean isRegisteredItem(String normalized) {
        for (String candidate : identifierCandidates(normalized)) {
            Identifier id = Identifier.tryParse(candidate);
            if (id != null && BuiltInRegistries.ITEM.getOptional(id)
                    .filter(item -> item != Items.AIR).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static LinkedHashSet<String> identifierCandidates(String normalized) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addIdentifierCandidate(candidates, normalized);
        addIdentifierCandidate(candidates, normalized.replace('-', '_'));
        for (String candidate : new LinkedHashSet<>(candidates)) {
            addSingularIdentifierCandidate(candidates, candidate);
        }
        return candidates;
    }

    private static void addIdentifierCandidate(Set<String> candidates, String raw) {
        if (raw != null && !raw.isBlank()) {
            candidates.add(raw.contains(":") ? raw : "minecraft:" + raw);
        }
    }

    private static void addSingularIdentifierCandidate(Set<String> candidates, String candidate) {
        int separator = candidate.indexOf(':');
        String namespace = separator >= 0 ? candidate.substring(0, separator + 1) : "minecraft:";
        String path = separator >= 0 ? candidate.substring(separator + 1) : candidate;
        if (path.length() > 1 && path.endsWith("s")) {
            candidates.add(namespace + path.substring(0, path.length() - 1));
        }
    }

    @Override
    public String name() {
        return "find";
    }

    @Override
    public String describe() {
        return "find target=" + target.id()
                + " radius=" + searchRadius + " hops=" + hopsAttempted + "/" + maxHops();
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        double time = Math.min(0.9D, (double) latestSearchAgeTicks / MAX_SEARCH_TICKS);
        double hops = Math.min(0.9D, (double) hopsAttempted / Math.max(1, maxHops()));
        return Math.max(time, hops);
    }

    /** Surveys and route admission may intentionally wait; do not let the generic idle watchdog abort this search. */
    @Override
    public boolean isWaiting() {
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        anchor = bot.blockPosition().immutable();
        searchStartedTick = bot.level().getServer().getTickCount();
        latestSearchAgeTicks = 0;
        blockSurvey = null;
        nextSurveyTick = 0;
        firstSurveyFinished = false;
        hopInFlight = false;
        hopStartedTick = 0;
        nextHopTick = 0;
        hopsAttempted = 0;
        announced = false;
        BotLog.action(bot, "discovery_search_started", "target", target.id(),
                "anchor", anchor.toShortString(), "radius", searchRadius, "max_ticks", MAX_SEARCH_TICKS);
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        int now = bot.level().getServer().getTickCount();
        latestSearchAgeTicks = Math.max(0, now - searchStartedTick);
        if (latestSearchAgeTicks >= MAX_SEARCH_TICKS) {
            finishNotFound(bot, "time_limit");
            return;
        }
        // A route is normally aimed back inside the search disc with one whole hop of
        // margin. This is the final guard for a detour/replan that nevertheless carries the
        // bot across that disc: stop immediately rather than letting a locate request become
        // an open-ended exploration order.
        if (horizontalDistanceSquared(bot.blockPosition(), anchor)
                > (double) searchRadius * searchRadius) {
            finishNotFound(bot, "search_boundary");
            return;
        }

        findVisibleTarget(bot, now).ifPresentOrElse(
                found -> finishFound(bot, found),
                () -> continueSearch(bot, now));
    }

    /**
     * Performs only live, observable discovery. The observable-only OreProspector entry points
     * skip unloaded chunks and never inspect hidden terrain; their result is independently
     * re-proven below before this player-facing task can report a block.
     */
    private Optional<BlockPos> findVisibleTarget(AIPlayerEntity bot, int now) {
        return switch (target.kind()) {
            case BLOCK -> surveyVisibleBlock(bot, now);
            case SHEEP -> visibleSheep(bot, now).map(Sheep::blockPosition);
            case CONTAINER -> surveyVisibleContainer(bot, now);
        };
    }

    private Optional<BlockPos> surveyVisibleBlock(AIPlayerEntity bot, int now) {
        if (blockSurvey == null && now >= nextSurveyTick) {
            blockSurvey = target.blockObservation() == BlockObservation.OUTLINE_OR_CELL
                    ? OreProspector.beginObservableFarmCells(bot, searchRadius, target.blockMatcher(), null)
                    : OreProspector.beginObservable(bot, searchRadius, target.blockMatcher(), null);
        }
        if (blockSurvey == null || !blockSurvey.step(SURVEY_BUDGET_NANOS)) {
            return Optional.empty();
        }
        BlockPos candidate = blockSurvey.result();
        blockSurvey = null;
        firstSurveyFinished = true;
        nextSurveyTick = now + SURVEY_INTERVAL_TICKS;
        return candidate != null && withinSearchDisc(candidate) && reproveVisibleBlock(bot, candidate)
                ? Optional.of(candidate.immutable()) : Optional.empty();
    }

    private Optional<Sheep> visibleSheep(AIPlayerEntity bot, int now) {
        if (now < nextSurveyTick) {
            return Optional.empty();
        }
        firstSurveyFinished = true;
        nextSurveyTick = now + SURVEY_INTERVAL_TICKS;
        return bot.level().getEntitiesOfClass(Sheep.class, bot.getBoundingBox().inflate(searchRadius),
                        Sheep::isAlive)
                .stream()
                .filter(sheep -> withinSearchDisc(sheep.blockPosition()))
                .filter(sheep -> ObservableWorldQuery.canObserveEntityWithin(bot, sheep, searchRadius))
                .min(Comparator.comparingDouble(sheep -> bot.distanceToSqr(sheep)));
    }

    /** A container lookup is a finite local scan, so poll it at the same cadence as other surveys. */
    private Optional<BlockPos> surveyVisibleContainer(AIPlayerEntity bot, int now) {
        if (now < nextSurveyTick) {
            return Optional.empty();
        }
        // Unlike the resumable block survey, a container lookup answers synchronously. Mark it
        // complete even on a miss so the bounded exploration phase can begin.
        firstSurveyFinished = true;
        nextSurveyTick = now + SURVEY_INTERVAL_TICKS;
        return ContainerTask.nearestContainer(bot, Math.min(16, searchRadius))
                .filter(this::withinSearchDisc);
    }

    private boolean reproveVisibleBlock(AIPlayerEntity bot, BlockPos candidate) {
        boolean visible = target.blockObservation() == BlockObservation.OUTLINE_OR_CELL
                ? ObservableWorldQuery.canObserveFarmCell(bot, candidate)
                : ObservableWorldQuery.canObserveBlock(bot, candidate);
        return visible && target.blockMatcher().test(bot.level().getBlockState(candidate));
    }

    private static boolean isMatureWheat(BlockState state) {
        return state.is(Blocks.WHEAT)
                && state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    private void continueSearch(AIPlayerEntity bot, int now) {
        if (!firstSurveyFinished) {
            return;
        }
        if (hopInFlight) {
            if (!bot.getActionPack().isPathExecutorIdle()) {
                if (now - hopStartedTick <= HOP_TIMEOUT_TICKS) {
                    return;
                }
                bot.getActionPack().cancelBaritoneRoute("discovery_hop_timeout");
            }
            hopInFlight = false;
            hopsAttempted++;
            nextHopTick = now + 5;
            blockSurvey = null;
            nextSurveyTick = now;
            return;
        }
        if (hopsAttempted >= maxHops()) {
            finishNotFound(bot, "area_exhausted");
            return;
        }
        if (now < nextHopTick || horizontalDistanceSquared(bot.blockPosition(), anchor)
                > (double) searchRadius * searchRadius) {
            if (horizontalDistanceSquared(bot.blockPosition(), anchor) > (double) searchRadius * searchRadius) {
                finishNotFound(bot, "search_boundary");
            }
            return;
        }

        BlockPos heading = headingFor(hopsAttempted);
        // Search deliberately has no break permission: it discovers, but never changes terrain
        // as a side effect of looking for something.
        ActionResult start = bot.getActionPack().startDirectionalPursuitTo(
                heading, HOP_DISTANCE, false, false);
        if (start.isFailed()) {
            BotLog.action(bot, "discovery_hop_refused", "target", target.id(),
                    "reason", start.reason(), "hop", hopsAttempted + 1, "heading", heading.toShortString());
            hopsAttempted++;
            nextHopTick = now + SURVEY_INTERVAL_TICKS;
            return;
        }
        hopInFlight = true;
        hopStartedTick = now;
        BotLog.action(bot, "discovery_hop_started", "target", target.id(),
                "hop", hopsAttempted + 1, "heading", heading.toShortString(), "max_hop", HOP_DISTANCE);
    }

    private int maxHops() {
        return searchRadius <= SEARCH_RADII[0] ? SEARCH_DIRECTIONS.length : MAX_HOPS;
    }

    private BlockPos headingFor(int hop) {
        int ring = Math.min(SEARCH_RADII.length - 1, hop / SEARCH_DIRECTIONS.length);
        int distance = Math.min(searchRadius - HOP_DISTANCE, SEARCH_RADII[ring]);
        int[] direction = SEARCH_DIRECTIONS[Math.floorMod(hop, SEARCH_DIRECTIONS.length)];
        // A diagonal offset of (distance, distance) would exceed the stated circular radius.
        // Project every heading into the same disc, leaving one complete local hop as a buffer
        // for Baritone's observed pursuit leg.
        double length = Math.hypot(direction[0], direction[1]);
        int component = (int) Math.floor(distance / length);
        int dx = direction[0] * component;
        int dz = direction[1] * component;
        return anchor.offset(dx, 0, dz);
    }

    private static double horizontalDistanceSquared(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    /** Every report remains inside the circle the player asked us to search, even after a local hop. */
    private boolean withinSearchDisc(BlockPos candidate) {
        return candidate != null && horizontalDistanceSquared(candidate, anchor)
                <= (double) searchRadius * searchRadius;
    }

    private void finishFound(AIPlayerEntity bot, BlockPos found) {
        if (announced) {
            return;
        }
        announced = true;
        bot.getActionPack().stopNavigation();
        // "show me" must never be backed by an arbitrary remembered coordinate: retain only
        // this target after the visible survey above has re-proven it.
        BotMemoryStore.INSTANCE.of(bot.getUUID()).markPlace(LAST_FOUND_PLACE, bot.level(), found);
        BotMemoryStore.INSTANCE.of(bot.getUUID()).remember(LAST_FOUND_LABEL, target.displayName());
        double distance = Math.sqrt(horizontalDistanceSquared(bot.blockPosition(), found));
        String location = found.getX() + ", " + found.getY() + ", " + found.getZ();
        String caveat = target == BONUS_CHEST ? " I can't tell whether it is the bonus chest." : "";
        BrainCoordinator.INSTANCE.sendBotReply(bot, "I found " + target.displayName()
                + " at " + location + " (about " + Math.round(distance)
                + " blocks away)." + caveat + " Would you like me to show you where it is?");
        BotLog.action(bot, "discovery_found", "target", target.id(),
                "pos", found.toShortString(), "distance", Math.round(distance), "hops", hopsAttempted);
        complete();
    }

    private void finishNotFound(AIPlayerEntity bot, String reason) {
        if (announced) {
            return;
        }
        announced = true;
        bot.getActionPack().stopNavigation();
        String message = "I couldn't find " + target.displayName() + " after searching within "
                + searchRadius + " blocks for up to 3 minutes.";
        BrainCoordinator.INSTANCE.sendBotReply(bot, message);
        BotLog.action(bot, "discovery_not_found", "target", target.id(),
                "reason", reason, "radius", searchRadius, "hops", hopsAttempted, "elapsed", latestSearchAgeTicks);
        fail("not_found:" + target.id() + ":" + reason);
    }
}
