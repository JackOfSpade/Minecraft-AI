package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.assist.AssistRules;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.task.WardenState;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What the bot's surroundings say about how loud it may be: a per-bot cache of the quiet-zone level and of the wardens the bot can
 * see, refreshed on a schedule (never scanned per tick). Only observed facts are used: the biome of the bot's own cell (what the
 * biome fog and music of a real player show), its own DARKNESS effect, sculk sensors/shriekers the bot has line of sight to, and
 * wardens the bot can see (see {@link ObservableWorldQuery}).
 *
 * <ul>
 *   <li>{@link Level#NONE}: nothing special.</li>
 *   <li>{@link Level#CAUTION}: deep dark biome or the DARKNESS effect. Sprinting is not banned but the pace stays at a walk.</li>
 *   <li>{@link Level#SILENT}: CAUTION and an observed sculk sensor, calibrated sensor or shrieker within 8 blocks, or an observed
 *       calm warden within 20 blocks. Everything a sneak silences is worth silencing: the bot sneaks.</li>
 * </ul>
 * {@code pace.quietZoneCaution=false} makes the level always NONE; the warden queries keep answering (a warden is a threat, not a
 * zone).
 */
public final class QuietZone {
    /** The quiet-zone level. */
    public enum Level {
        NONE,
        CAUTION,
        SILENT
    }

    /** Ticks between two scans of the surroundings for wardens (short: a warden that starts hunting must be noticed fast). */
    static final int WARDEN_REFRESH_TICKS = 5;
    /** Ticks between two refreshes of the biome and the sculk scan. */
    static final int ZONE_REFRESH_TICKS = 20;
    /** Radius of the sculk sensor/shrieker scan (17^3 = 4913 block state reads, inside the budget below). */
    static final int SENSOR_RADIUS = 8;
    /** Most block states one refresh reads. */
    static final int SENSOR_SCAN_BUDGET = 5000;
    /** A calm warden this close makes the bot SILENT (in CAUTION). */
    static final double SILENT_WARDEN_RANGE = 20.0D;
    /** How far away a warden is looked for at all. */
    static final double WARDEN_SCAN_RANGE = 24.0D;
    /** A hunting warden counts within this distance. */
    static final double HUNTING_RANGE = 24.0D;

    private long nextWardenRefresh = Long.MIN_VALUE;
    private long nextZoneRefresh = Long.MIN_VALUE;
    private Level level = Level.NONE;
    private boolean caution;
    private boolean sensorSeen;
    private double nearestCalmWarden = Double.POSITIVE_INFINITY;
    private boolean huntingWarden;

    /** Refreshes what is due at this game tick. Cheap when nothing is due. */
    public void refresh(AIPlayerEntity bot) {
        long now = bot.level().getGameTime();
        if (now >= nextWardenRefresh || nextWardenRefresh - now > WARDEN_REFRESH_TICKS) {
            nextWardenRefresh = now + WARDEN_REFRESH_TICKS;
            scanWardens(bot, now);
        }
        boolean zoneDue = now >= nextZoneRefresh || nextZoneRefresh - now > ZONE_REFRESH_TICKS;
        if (zoneDue) {
            nextZoneRefresh = now + ZONE_REFRESH_TICKS;
            caution = isCaution(bot);
            sensorSeen = caution && scanSensors(bot);
        }
        recompute();
    }

    /** Forgets the cache: the next {@link #refresh} scans everything again (tests, respawn). */
    public void invalidate() {
        nextWardenRefresh = Long.MIN_VALUE;
        nextZoneRefresh = Long.MIN_VALUE;
        level = Level.NONE;
        caution = false;
        sensorSeen = false;
        nearestCalmWarden = Double.POSITIVE_INFINITY;
        huntingWarden = false;
    }

    /** The cached level (call {@link #refresh} first). */
    public Level level() {
        return level;
    }

    /** True when an observed warden that is not hunting stands within {@code range} blocks. */
    public boolean calmWardenWithin(double range) {
        return nearestCalmWarden <= range;
    }

    /**
     * A one-off answer (no cache, no bot state) to "does this bot observe a calm warden within {@code range} blocks?", for the
     * decisions that are not travel (the follow escort, eating): the same observation and hunting rules as the cached scan.
     */
    public static boolean calmWardenObservedWithin(AIPlayerEntity bot, double range) {
        long now = bot.level().getGameTime();
        List<Warden> wardens = bot.level().getEntitiesOfClass(Warden.class, bot.getBoundingBox().inflate(range), Warden::isAlive);
        if (wardens.isEmpty()) {
            return false;
        }
        List<LivingEntity> victims = victims(bot);
        for (Warden warden : wardens) {
            if (bot.distanceTo(warden) <= range
                    && ObservableWorldQuery.canNoticeCreatureWithin(bot, warden, (int) WARDEN_SCAN_RANGE)
                    && !WardenState.isHunting(warden, victims, now)) {
                return true;
            }
        }
        return false;
    }

    /** True when an observed warden within 24 blocks is hunting the bot or its owner. */
    public boolean huntingWardenObserved() {
        return huntingWarden;
    }

    private void recompute() {
        if (!MinecraftAiConfig.get().behaviour().paceOrDefaults().quietZoneCautionEnabled() || !caution) {
            level = Level.NONE;
            return;
        }
        level = sensorSeen || nearestCalmWarden <= SILENT_WARDEN_RANGE ? Level.SILENT : Level.CAUTION;
    }

    private static boolean isCaution(AIPlayerEntity bot) {
        if (bot.hasEffect(MobEffects.DARKNESS)) {
            return true;
        }
        String biome = bot.level().getBiome(bot.blockPosition()).unwrapKey()
                .map(key -> key.identifier().toString()).orElse("");
        return AssistRules.isDeepDarkBiome(biome);
    }

    private void scanWardens(AIPlayerEntity bot, long now) {
        nearestCalmWarden = Double.POSITIVE_INFINITY;
        huntingWarden = false;
        ServerLevel world = bot.level();
        List<Warden> wardens = world.getEntitiesOfClass(Warden.class, bot.getBoundingBox().inflate(WARDEN_SCAN_RANGE));
        if (wardens.isEmpty()) {
            return;
        }
        List<LivingEntity> victims = victims(bot);
        for (Warden warden : wardens) {
            if (!warden.isAlive() || !ObservableWorldQuery.canNoticeCreatureWithin(bot, warden, (int) WARDEN_SCAN_RANGE)) {
                continue;
            }
            double distance = bot.distanceTo(warden);
            if (WardenState.isHunting(warden, victims, now)) {
                if (distance <= HUNTING_RANGE) {
                    huntingWarden = true;
                }
            } else {
                nearestCalmWarden = Math.min(nearestCalmWarden, distance);
            }
        }
    }

    /** The bot and its owner when the owner is online: the ones a warden hunting "us" is hunting. */
    /** The bot and its owner: who a warden hunting "us" is hunting (for the callers outside this package). */
    public static List<LivingEntity> victimsOf(AIPlayerEntity bot) {
        return victims(bot);
    }

    static List<LivingEntity> victims(AIPlayerEntity bot) {
        List<LivingEntity> victims = new ArrayList<>(2);
        victims.add(bot);
        AIPlayerManager.INSTANCE.ownerOf(bot).ifPresent(ownerId -> {
            ServerPlayer owner = bot.level().getServer().getPlayerList().getPlayer(ownerId);
            if (owner != null) {
                victims.add(owner);
            }
        });
        return victims;
    }

    private static boolean scanSensors(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        BlockPos center = bot.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int reads = 0;
        for (int dx = -SENSOR_RADIUS; dx <= SENSOR_RADIUS; dx++) {
            for (int dy = -SENSOR_RADIUS; dy <= SENSOR_RADIUS; dy++) {
                for (int dz = -SENSOR_RADIUS; dz <= SENSOR_RADIUS; dz++) {
                    if (++reads > SENSOR_SCAN_BUDGET) {
                        return false;
                    }
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!world.isLoaded(cursor)) {
                        continue;
                    }
                    BlockState state = world.getBlockState(cursor);
                    Block block = state.getBlock();
                    if ((block == Blocks.SCULK_SENSOR || block == Blocks.CALIBRATED_SCULK_SENSOR || block == Blocks.SCULK_SHRIEKER)
                            && ObservableWorldQuery.canObserveBlockWithin(bot, cursor.immutable(), SENSOR_RADIUS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
