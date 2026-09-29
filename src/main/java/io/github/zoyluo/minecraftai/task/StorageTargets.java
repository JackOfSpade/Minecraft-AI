package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ContainerAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.memory.ContainerLedger;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Automatic storage selection shared by every task that fills or empties containers. The rules:
 * only storage blocks (chest, barrel, shulker box) are ever picked on the bot's own initiative
 * (furnaces, hoppers, droppers, crafters, jukeboxes need explicit coordinates); a container whose
 * lid vanilla would refuse to open is skipped; chests near an observed spawner (dungeon and
 * structure loot) are skipped; containers the ledger recently saw full are DEMOTED for deposits
 * (tried last, never excluded: a player may have emptied them since, and opening one re-verifies
 * it), and a "full" observation older than {@link ContainerLedger#FULL_TRUST_TICKS} is not believed
 * at all. Nothing here reads container contents: ranking uses the ledger (what the bot saw when it
 * last opened a container) and the block kind is tested before any line-of-sight ray is cast.
 * A remembered (ledger) position is only ever a candidate when its chunk is loaded and it lies
 * within {@link #LEDGER_MAX_DISTANCE} blocks: an unloaded position is unknown, not visited.
 */
final class StorageTargets {
    /** Largest search radius honoured for automatic container search (the find_container tool clamps to it). */
    static final int MAX_RADIUS = 16;
    /** Farthest a remembered (ledger) container may be from the bot and still be walked to. */
    static final int LEDGER_MAX_DISTANCE = 128;
    private static final int SPAWNER_RADIUS = 6;

    private StorageTargets() {
    }

    static int clampRadius(int radius) {
        return Math.max(1, Math.min(MAX_RADIUS, radius));
    }

    /** A remembered container the bot may consider: in a loaded chunk and within {@link #LEDGER_MAX_DISTANCE}. */
    static boolean ledgerCandidateOk(AIPlayerEntity bot, ContainerLedger.Entry entry) {
        double max = (double) LEDGER_MAX_DISTANCE * LEDGER_MAX_DISTANCE;
        return entry.pos().distSqr(bot.blockPosition()) <= max && bot.level().hasChunkAt(entry.pos());
    }

    /**
     * Storage the bot can currently see within {@code radius} of {@code center}, one position per
     * container (a double chest counts once), nearest to the bot first. {@code demoteKnownFull}
     * moves containers the ledger recently saw full to the END of the list (they stay candidates:
     * opening one re-verifies it); with it false the list is purely by distance.
     */
    static List<BlockPos> observedStorage(AIPlayerEntity bot, BlockPos center, int radius, boolean demoteKnownFull) {
        Level level = bot.level();
        BlockPos origin = bot.blockPosition();
        Map<BlockPos, Boolean> spawnerCache = new HashMap<>();
        Map<BlockPos, BlockPos> byContainer = new LinkedHashMap<>();
        BlockPos.betweenClosedStream(center.offset(-radius, -3, -radius), center.offset(radius, 4, radius))
                // Cheapest test first: the block kind. Only real storage blocks reach the rest.
                .filter(pos -> ContainerAction.isStorageBlock(level.getBlockState(pos)))
                .filter(pos -> ContainerAction.isOpenableStorage(level, pos))
                .map(BlockPos::immutable)
                // One ray before the spawner scan (which reads ~2200 block states per candidate).
                .filter(pos -> ContainerAction.canSee(bot, pos))
                .filter(pos -> !spawnerCache.computeIfAbsent(pos, cell -> nearObservedSpawner(bot, cell)))
                .forEach(pos -> {
                    BlockPos canonical = ContainerAction.canonicalPos(level, pos);
                    BlockPos existing = byContainer.get(canonical);
                    if (existing == null || pos.distSqr(origin) < existing.distSqr(origin)) {
                        byContainer.put(canonical, pos);
                    }
                });
        List<BlockPos> result = new ArrayList<>(byContainer.values());
        result.sort(Comparator
                .comparing((BlockPos pos) -> demoteKnownFull && ledgerKnowsFull(bot, pos, true))
                .thenComparingDouble(pos -> pos.distSqr(origin)));
        return result;
    }

    /**
     * Containers to try around a remembered base: storage in sight there plus containers the ledger
     * remembers inside the same box (not necessarily in sight from here; the caller walks over and
     * verifies on arrival; only loaded, reasonably near ones). Ordered "preferred" first, then
     * nearest to the bot; with {@code demoteKnownFull} the ones the ledger recently saw full come
     * last (a full-and-remembered container that is out of sight is left out entirely: walking to it
     * would only confirm what the ledger says, and the flag fades on its own). {@code preferred}
     * gets (position, currently observed) and may only use ledger knowledge, never contents.
     */
    static List<BlockPos> aroundBase(AIPlayerEntity bot, BlockPos base, int radius, boolean demoteKnownFull,
                                     java.util.function.BiPredicate<BlockPos, Boolean> preferred) {
        Level level = bot.level();
        BlockPos origin = bot.blockPosition();
        long now = level.getGameTime();
        List<BlockPos> observed = observedStorage(bot, base, radius, false);
        List<BlockPos> all = new ArrayList<>(observed);
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        for (BlockPos pos : observed) {
            seen.add(ContainerAction.canonicalPos(level, pos));
        }
        String dimension = level.dimension().identifier().toString();
        for (ContainerLedger.Entry entry : ledger(bot).inDimension(dimension)) {
            BlockPos pos = entry.pos();
            if (Math.abs(pos.getX() - base.getX()) > radius || Math.abs(pos.getZ() - base.getZ()) > radius
                    || pos.getY() < base.getY() - 3 || pos.getY() > base.getY() + 4
                    || seen.contains(pos) || (demoteKnownFull && entry.knownFull(now))
                    || !ledgerCandidateOk(bot, entry)) {
                continue;
            }
            all.add(pos);
        }
        java.util.Set<BlockPos> observedSet = new java.util.HashSet<>(observed);
        all.sort(Comparator
                .comparing((BlockPos pos) -> demoteKnownFull && ledgerKnowsFull(bot, pos, observedSet.contains(pos)))
                .thenComparing((BlockPos pos) -> !preferred.test(pos, observedSet.contains(pos)))
                .thenComparingDouble(pos -> pos.distSqr(origin)));
        return all;
    }

    /** How close to a remembered base/home/depot place a storage block must be to count as the player's base storage. */
    private static final int BASE_STORAGE_RADIUS = 16;

    /**
     * Where the bot may put surplus junk on its own initiative: the nearest container the ledger says
     * has room (the bot opened it before), or storage in sight that stands at a remembered
     * base/home/depot place. Random structure chests are never picked. {@code inReachOnly} keeps
     * only targets that can be used without moving (the follow case). The result is a TRUSTED
     * candidate, not an explicit target: the junk task still requires a real, openable storage block
     * (and no observed spawner nearby) when it gets there.
     */
    static Optional<BlockPos> junkStowTarget(AIPlayerEntity bot, int radius, boolean inReachOnly) {
        Level level = bot.level();
        BlockPos origin = bot.blockPosition();
        long now = level.getGameTime();
        String dimension = level.dimension().identifier().toString();
        List<BlockPos> pool = new ArrayList<>();
        for (ContainerLedger.Entry entry : ledger(bot).withRoom(dimension, origin, now, 8,
                entry -> ledgerCandidateOk(bot, entry))) {
            if (Math.sqrt(entry.pos().distSqr(origin)) <= radius) {
                pool.add(entry.pos());
            }
        }
        var memory = BotMemoryStore.INSTANCE.of(bot.getUUID());
        Optional<BlockPos> place = memory.placeIn(bot.level(), "base", "home", "depot", "chest");
        if (place.isPresent()) {
            for (BlockPos pos : observedStorage(bot, origin, Math.min(radius, MAX_RADIUS), false)) {
                if (pos.distSqr(place.get()) <= (double) BASE_STORAGE_RADIUS * BASE_STORAGE_RADIUS
                        && !ledgerKnowsFull(bot, pos, true)) {
                    pool.add(pos);
                }
            }
        }
        pool.sort(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
        for (BlockPos pos : pool) {
            if (inReachOnly && !ContainerAction.inReachAndSight(bot, pos)) {
                continue;
            }
            if (inReachOnly || ContainerAction.isOpenableStorage(level, pos) || !ContainerAction.canSee(bot, pos)) {
                return Optional.of(pos);
            }
        }
        return Optional.empty();
    }

    /** True when an OBSERVED spawner (or trial spawner) stands within a few blocks of {@code pos}. */
    static boolean nearObservedSpawner(AIPlayerEntity bot, BlockPos pos) {
        Level level = bot.level();
        return BlockPos.betweenClosedStream(pos.offset(-SPAWNER_RADIUS, -SPAWNER_RADIUS, -SPAWNER_RADIUS),
                        pos.offset(SPAWNER_RADIUS, SPAWNER_RADIUS, SPAWNER_RADIUS))
                .filter(cell -> level.hasChunkAt(cell))
                .anyMatch(cell -> {
                    BlockState state = level.getBlockState(cell);
                    return (state.is(Blocks.SPAWNER) || state.is(Blocks.TRIAL_SPAWNER))
                            && ObservableWorldQuery.canObserveBlock(bot, cell);
                });
    }

    static Optional<ContainerLedger.Entry> entry(AIPlayerEntity bot, BlockPos pos, boolean observed) {
        String dimension = bot.level().dimension().identifier().toString();
        ContainerLedger ledger = BotMemoryStore.INSTANCE.of(bot.getUUID()).containers();
        Optional<ContainerLedger.Entry> direct = ledger.get(dimension, pos);
        if (direct.isPresent() || !observed) {
            return direct;
        }
        return ledger.get(dimension, ContainerAction.canonicalPos(bot.level(), pos));
    }

    /** True when the ledger saw this container full recently enough to still believe it (see {@link ContainerLedger#FULL_TRUST_TICKS}). */
    static boolean ledgerKnowsFull(AIPlayerEntity bot, BlockPos pos, boolean observed) {
        long now = bot.level().getGameTime();
        return entry(bot, pos, observed).map(entry -> entry.knownFull(now)).orElse(false);
    }

    /** How many of {@code item} the ledger says the container at {@code pos} held (0 when never opened). */
    static int ledgerCount(AIPlayerEntity bot, BlockPos pos, boolean observed, Item item) {
        return entry(bot, pos, observed)
                .map(entry -> entry.count(BuiltInRegistries.ITEM.getKey(item).toString()))
                .orElse(0);
    }

    static ContainerLedger ledger(AIPlayerEntity bot) {
        return BotMemoryStore.INSTANCE.of(bot.getUUID()).containers();
    }
}
