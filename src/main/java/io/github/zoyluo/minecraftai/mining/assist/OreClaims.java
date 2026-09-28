package io.github.zoyluo.aibot.mining.assist;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Server-wide, per-dimension claims on valuables that a detour is about to break (mining-assist design 4.11),
 * so two bots do not walk to and break the same vein. Static, server thread only, cleared in
 * {@code MiningAssistRuntime.clearWorldRuntime}. Modelled on {@code EmergencyShelterTask.PENDING_CLEANUPS}: a
 * claim is a soft lease, never a lock, and never the proof that a block is there (the exact-once re-proof of the
 * cell before each swing is what protects the world).
 *
 * <p>Storage: {@code Map<dimension key, Long2ObjectOpenHashMap<Claim>>}, keyed by {@code BlockPos.asLong()}, so a
 * lookup allocates and boxes nothing. A claim is {@code (ownerUuid, expiresTick)}. The lease is
 * {@value #LEASE_TICKS} server ticks and the engine renews it every {@value #RENEW_INTERVAL_TICKS} ticks
 * ({@link #renewAll}); an expired claim counts as not held and is dropped lazily, and {@code renewAll} and
 * {@code releaseAll} also sweep the expired entries of every dimension they touch, so a cell nobody asks about
 * again does not stay in the map for ever.</p>
 *
 * <p><b>Where it is checked.</b> Not in OreDig's {@code oreExcluded} (that loop runs over 50k cells and only ever
 * returns target ores, which detours never claim). It is a separate statement inside {@code scanBonusOre}, after
 * the ore test (hook 2), and in the detour start and per-member selection.</p>
 *
 * <p><b>Residual (pre-existing):</b> another bot's OreDig target, vein and dig-through breaks are not claim
 * protected (P6). A double break is harmless: each bot's own exact-once ledger re-proves.</p>
 */
public final class OreClaims {
    /** Lease of one claim, in server ticks. */
    public static final int LEASE_TICKS = 100;
    /** How often the engine renews the leases of a live detour, in server ticks. */
    public static final int RENEW_INTERVAL_TICKS = 40;

    /** One soft lease: the owner and the server tick it expires at. */
    private record Claim(UUID owner, int expiresTick) {
    }

    /** {@code dimensionKey -> (posKey -> Claim)}. Plain {@code HashMap} for the outer, small (few dimensions) map; the inner map is the boxing-free fastutil one. */
    private static final Map<String, Long2ObjectOpenHashMap<Claim>> CLAIMS = new HashMap<>();

    private OreClaims() {
    }

    /**
     * Claims {@code posKey} ({@code BlockPos.asLong()}) in {@code dimensionKey} for {@code owner} until
     * {@code nowTick + LEASE_TICKS}. Returns true when the claim is now held by {@code owner}: it was free, it had
     * expired, or {@code owner} already held it (the lease is then refreshed). Returns false, and changes nothing,
     * when another owner holds an unexpired claim on it.
     */
    public static boolean tryClaim(String dimensionKey, UUID owner, long posKey, int nowTick) {
        Long2ObjectOpenHashMap<Claim> table = CLAIMS.computeIfAbsent(dimensionKey, k -> new Long2ObjectOpenHashMap<>());
        Claim existing = table.get(posKey);
        if (existing != null && nowTick < existing.expiresTick() && !existing.owner().equals(owner)) {
            return false;
        }
        table.put(posKey, new Claim(owner, nowTick + LEASE_TICKS));
        return true;
    }

    /** True when an owner other than {@code me} holds an unexpired claim on {@code posKey}. An expired claim is not held (and is removed). */
    public static boolean heldByOther(String dimensionKey, UUID me, long posKey, int nowTick) {
        Long2ObjectOpenHashMap<Claim> table = CLAIMS.get(dimensionKey);
        if (table == null) {
            return false;
        }
        Claim c = table.get(posKey);
        if (c == null) {
            return false;
        }
        if (nowTick >= c.expiresTick()) {
            table.remove(posKey);
            return false;
        }
        return !c.owner().equals(me);
    }

    /** Refreshes the lease of every claim held by {@code owner} to {@code nowTick + LEASE_TICKS}. Returns how many were refreshed (expired ones are dropped, not refreshed). */
    public static int renewAll(UUID owner, int nowTick) {
        int renewed = 0;
        for (Long2ObjectOpenHashMap<Claim> table : CLAIMS.values()) {
            Iterator<Long2ObjectMap.Entry<Claim>> it = table.long2ObjectEntrySet().iterator();
            while (it.hasNext()) {
                Long2ObjectMap.Entry<Claim> e = it.next();
                Claim c = e.getValue();
                if (!c.owner().equals(owner)) {
                    continue;
                }
                if (nowTick >= c.expiresTick()) {
                    it.remove();
                    continue;
                }
                e.setValue(new Claim(owner, nowTick + LEASE_TICKS));
                renewed++;
            }
        }
        return renewed;
    }

    /** Releases one claim if {@code owner} holds it. Returns true when a claim was removed. */
    public static boolean release(String dimensionKey, UUID owner, long posKey) {
        Long2ObjectOpenHashMap<Claim> table = CLAIMS.get(dimensionKey);
        if (table == null) {
            return false;
        }
        Claim c = table.get(posKey);
        if (c == null || !c.owner().equals(owner)) {
            return false;
        }
        table.remove(posKey);
        return true;
    }

    /** Releases every claim of {@code owner} in every dimension (FINISH, abort, pause, delete, orphan cleanup, state clear). Returns how many were removed. */
    public static int releaseAll(UUID owner) {
        int removed = 0;
        for (Long2ObjectOpenHashMap<Claim> table : CLAIMS.values()) {
            Iterator<Long2ObjectMap.Entry<Claim>> it = table.long2ObjectEntrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue().owner().equals(owner)) {
                    it.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Drops every claim (world unload). */
    public static void clearAll() {
        CLAIMS.clear();
    }

    /** Number of stored claims including expired ones not yet dropped (diagnostics and tests). */
    public static int size() {
        int total = 0;
        for (Long2ObjectOpenHashMap<Claim> table : CLAIMS.values()) {
            total += table.size();
        }
        return total;
    }

    // ---- adapters for callers that hold a bot (hook 2 of OreDigTask, the host) -------------------------------

    /**
     * Hook 2 of {@code OreDigTask.scanBonusOre}: whether another bot holds a claim on {@code pos} in this bot's
     * dimension, at the bot's server tick. Equivalent to
     * {@code heldByOther(BotEdits.dimensionKey(bot.getEntityWorld()), bot.getUuid(), pos.asLong(),
     * MiningAssistRuntime.serverTick(bot))}. Must be cheap and never throw: it answers false at once, before it reads
     * the dimension or the tick, when no claim exists anywhere ({@link #size()} is 0: assist off or nobody claims,
     * the design's "one static check" cost claim for hook 2 on every ore cell of the bonus scan); any failure to resolve the
     * dimension or the tick answers false.
     */
    public static boolean heldByOther(AIPlayerEntity bot, BlockPos pos) {
        if (size() == 0) {
            return false;
        }
        try {
            String dimensionKey = BotEdits.dimensionKey(bot.getEntityWorld());
            int nowTick = MiningAssistRuntime.serverTick(bot);
            return heldByOther(dimensionKey, bot.getUuid(), pos.asLong(), nowTick);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@link #tryClaim} for a bot's own dimension, uuid and server tick. */
    public static boolean tryClaim(AIPlayerEntity bot, BlockPos pos) {
        String dimensionKey = BotEdits.dimensionKey(bot.getEntityWorld());
        int nowTick = MiningAssistRuntime.serverTick(bot);
        return tryClaim(dimensionKey, bot.getUuid(), pos.asLong(), nowTick);
    }

    /** {@link #renewAll} for a bot at its server tick. */
    public static int renewAll(AIPlayerEntity bot) {
        return renewAll(bot.getUuid(), MiningAssistRuntime.serverTick(bot));
    }
}
