package io.github.zoyluo.aibot.mining.assist;

import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The mandatory-repeat suppression of mining-assist design 6.4, entirely separate from {@link PoiRegistry}
 * (mandatory never consults the registry). Also backs {@code DetourSafetyGate}'s {@code inNoDetourZone}: a
 * {@value #NO_DETOUR_ZONE_RADIUS_BLOCKS}-block zone around the latest mandatory site. Static, per bot, server
 * thread only.
 *
 * <p><b>Design-text note (same deliberate-simplification call as {@link PoiRegistry}).</b> Design 6.4 says the
 * no-detour zone is created "for the mission." This class's one tracked site per bot has no mission-boundary
 * lifetime: only {@link #clear(UUID)} (unload) / {@link #clearAll()} (world unload) and the existing
 * {@link #ACK_TTL_TICKS}/{@link #SAME_SITE_RADIUS_BLOCKS} conditions end its effect. A bot that finishes a
 * mission near a mandatory site and starts an unrelated new one nearby before those conditions are met still
 * has detours blocked and the mandatory warning suppressed for the old site. Same rationale as
 * {@link PoiRegistry}: no mission-lifecycle hook exists anywhere else in this codebase to bind "for the
 * mission" to, so this is accepted and documented rather than invented ad hoc for this one file.</p>
 */
public final class MandatoryLatch {
    public static final int SAME_SITE_RADIUS_BLOCKS = 24;
    public static final int ACK_TTL_TICKS = 2400;
    public static final int WARDEN_NOTICE_RATE_LIMIT_TICKS = 200;
    public static final int NO_DETOUR_ZONE_RADIUS_BLOCKS = 48;

    /** One tracked mandatory site. {@code acknowledgedTick} and {@code lastWardenNoticeTick} use the
     * {@link MiningAssistState#NEVER} sentinel while unset; {@code recordedTick} is always concrete. */
    private record Site(String dimensionKey, BlockPos centroid, int recordedTick, int acknowledgedTick, int lastWardenNoticeTick) {
        private Site {
            dimensionKey = Objects.requireNonNull(dimensionKey, "dimensionKey");
            centroid = Objects.requireNonNull(centroid, "centroid").toImmutable();
        }
    }

    /** {@code botId -> its one tracked mandatory site}, absent when none. */
    private static final Map<UUID, Site> SITES = new HashMap<>();

    private MandatoryLatch() {
    }

    /**
     * True when a repeat at {@code centroid} (same bot, same dimension) is suppressed. Suppressed only if ALL
     * hold: the earlier stop was acknowledged (player resumed), {@code wardenVisible} is false, {@code centroid}
     * is within {@link #SAME_SITE_RADIUS_BLOCKS} of the recorded site, and less than {@link #ACK_TTL_TICKS} have
     * passed since it was recorded. When that fails specifically because {@code wardenVisible} is true, a
     * narrower rate limit still applies (one notice per {@link #WARDEN_NOTICE_RATE_LIMIT_TICKS}). No prior site
     * for this bot/dimension: never suppressed (first mandatory site always stops).
     */
    public static boolean suppresses(UUID botId, String dimensionKey, BlockPos centroid, boolean wardenVisible, int nowTick) {
        Site site = SITES.get(botId);
        if (site == null || !site.dimensionKey().equals(dimensionKey)) {
            return false;
        }
        if (!withinRadius(site.centroid(), centroid, SAME_SITE_RADIUS_BLOCKS)) {
            return false;
        }
        if (wardenVisible) {
            return site.lastWardenNoticeTick() != MiningAssistState.NEVER
                    && (long) nowTick - site.lastWardenNoticeTick() < WARDEN_NOTICE_RATE_LIMIT_TICKS;
        }
        return site.acknowledgedTick() != MiningAssistState.NEVER
                && (long) nowTick - site.recordedTick() < ACK_TTL_TICKS;
    }

    /** Records/overwrites this bot's one tracked mandatory site: {@code recordedTick = nowTick},
     * {@code acknowledgedTick} reset to unset, {@code lastWardenNoticeTick} stamped to {@code nowTick}. Called
     * once per actual (unsuppressed) mandatory stop. */
    public static void record(UUID botId, String dimensionKey, BlockPos centroid, int nowTick) {
        SITES.put(botId, new Site(dimensionKey, centroid, nowTick, MiningAssistState.NEVER, nowTick));
    }

    /** The player resumed a mandatory-sourced STOPPED case: stamps {@code acknowledgedTick = nowTick} on the
     * existing site (no-op if none, or if the bot's tracked site has since moved on to a different one). */
    public static void acknowledge(UUID botId, int nowTick) {
        Site site = SITES.get(botId);
        if (site == null) {
            return;
        }
        SITES.put(botId, new Site(site.dimensionKey(), site.centroid(), site.recordedTick(), nowTick, site.lastWardenNoticeTick()));
    }

    /** Design 6.4/{@code SafeGateInputs#inNoDetourZone}: true while {@code pos} is within
     * {@link #NO_DETOUR_ZONE_RADIUS_BLOCKS} of this bot's tracked mandatory site in {@code dimensionKey}. False
     * when there is none. */
    public static boolean inNoDetourZone(UUID botId, String dimensionKey, BlockPos pos) {
        Site site = SITES.get(botId);
        if (site == null || !site.dimensionKey().equals(dimensionKey)) {
            return false;
        }
        return withinRadius(site.centroid(), pos, NO_DETOUR_ZONE_RADIUS_BLOCKS);
    }

    /** Bot unload only (see {@code MiningAssistRuntime.clearBotUnload}). */
    public static void clear(UUID botId) {
        SITES.remove(botId);
    }

    /** World unload. */
    public static void clearAll() {
        SITES.clear();
    }

    /** True when {@code a} and {@code b} are within {@code radiusBlocks} of each other, squared Euclidean 3D.
     * Duplicated from {@link PoiRegistry} on purpose: no cross-file dependency between the two latches. */
    private static boolean withinRadius(BlockPos a, BlockPos b, int radiusBlocks) {
        long dx = (long) a.getX() - b.getX();
        long dy = (long) a.getY() - b.getY();
        long dz = (long) a.getZ() - b.getZ();
        long distSq = dx * dx + dy * dy + dz * dz;
        long r = radiusBlocks;
        return distSq <= r * r;
    }
}
