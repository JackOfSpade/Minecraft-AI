package io.github.zoyluo.minecraftai.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.log.BotLog;

/**
 * The fixed, global Baritone settings of this mod.
 *
 * <p>{@code BaritoneAPI.getSettings()} is one object for the whole JVM and Baritone reads it live, so these are not per-bot
 * knobs; they encode what our bots may do on a server. They are written over whatever
 * {@code <game dir>/baritone/settings.txt} loaded (that file is read by {@code BaritoneAPI}'s static initializer, and a
 * hand-edited value there must not be able to give a bot moves the rest of the mod does not model).</p>
 *
 * <p>Two groups:</p>
 * <ul>
 *   <li>{@link #applyFixed()}: never changes at run time; applied once, before the first instance exists.</li>
 *   <li>{@link #applyNavLimits()}: derived from {@link MinecraftAiConfig} (the fall height our own navigation accepts), so
 *       it is re-applied whenever an instance is created or a plan is requested; a config reload takes effect at the next
 *       plan, never in the middle of one.</li>
 * </ul>
 */
public final class BaritoneSettings {
    /** How far from a hostile mob a route is made more expensive (blocks), and by how much (a multiplier of each step). */
    static final int MOB_AVOIDANCE_RADIUS = 6;
    static final double MOB_AVOIDANCE_COEFFICIENT = 2.0D;

    private BaritoneSettings() {
    }

    public static void applyFixed() {
        Settings settings = BaritoneAPI.getSettings();

        // -- Moves that are config switches (nav.baritone.*): parkour, parkour-place, parkour-ascend, the water-bucket fall, vines and
        // mob avoidance. They are derived from the config at every plan request (applyNavLimits), not fixed here, so a hand-edited
        // baritone/settings.txt cannot turn them on behind the config's back. What is fixed here is what the config does not offer:
        // a mob spawner is never avoided (the spawner list of Baritone's avoidance comes from its world cache, which stays off below).
        settings.mobSpawnerAvoidanceCoefficient.value = 1.0D;
        settings.mobAvoidanceRadius.value = MOB_AVOIDANCE_RADIUS;
        settings.mobAvoidanceCoefficient.value = MOB_AVOIDANCE_COEFFICIENT;

        // -- Strict survival (BaritoneBreakPlacePolicy): what a bot may break is decided by the mod's rules, not by a hand-edited
        // settings file. Nothing is broken "anyway" without allowBreak, inventory moves are off (the controller refuses them too),
        // and the cost model is fed the block-level break rule so routes avoid protected blocks instead of being vetoed later.
        settings.allowBreakAnyway.value = new java.util.ArrayList<>();
        settings.allowInventory.value = false;
        BaritoneBreakPlacePolicy.installPlanningRules();

        // -- Tools: the mod's own policy picks the tool (ToolSelector, once when a break starts: ServerPlayerController#clickBlock),
        // never Baritone's. Its auto-tool takes the fastest tool of the hotbar, which spends an iron or diamond pickaxe on stone; with
        // assumeExternalAutoTool it does not touch the selected slot at all (autoTool stays on, because the cost model keys on it), the
        // cost model prices breaks with the tool the policy will pick (BaritoneToolPolicy, patch 0016), a sword is never a mining tool
        // (leaves, cobweb) and a tool close to breaking is left alone.
        settings.assumeExternalAutoTool.value = true;
        settings.useSwordToMine.value = false;
        settings.itemSaver.value = true;

        // -- No world cache: a bot only ever plans over chunks that are loaded right now, and the cached-chunk machinery
        // (a packer thread and 512x512 region files per dimension) would give it knowledge of terrain it cannot currently observe.
        // Decision: this stays off for good, it is not a capability switch (planning is over loaded terrain, like a vanilla mob's).
        settings.chunkCaching.value = false;

        // -- A bot's yaw is its real yaw (ActionPack and vanilla movement read it directly), so Baritone must set it for real
        // instead of the "free look" trick that only changes the direction of the next move.
        settings.freeLook.value = false;
        settings.blockFreeLook.value = false;

        // -- There is no chat, toast or desktop to tell. Text Baritone would have said goes to the bot log instead.
        settings.desktopNotifications.value = false;
        settings.logAsToast.value = false;
        settings.chatControl.value = false;
        settings.chatDebug.value = false;
        settings.logger.value = message -> BotLog.config("baritone_message", "text", message.getString());
    }

    public static void applyNavLimits() {
        Settings settings = BaritoneAPI.getSettings();
        MinecraftAiConfig.Nav nav = MinecraftAiConfig.get().nav();
        MinecraftAiConfig.BaritoneCaps caps = nav.baritoneCaps();
        int maxSafeFall = Math.max(1, nav.maxSafeFall());
        // Without a bucket this is the only fall limit that is consulted.
        settings.maxFallHeightNoWater.value = maxSafeFall;
        // With a water bucket in the hotbar (Baritone's cost model checks the bot's own inventory and refuses the Nether) a higher fall
        // is planned, and only then; without the switch the bucket limit is the same number, so it can never exceed what the mod
        // considers survivable. The click itself is allowed by BaritoneBreakPlacePolicy only inside the fall movement.
        settings.allowWaterBucketFall.value = caps.waterBucketFallEnabled();
        settings.maxFallHeightBucket.value = caps.waterBucketFallEnabled() ? Math.max(maxSafeFall, caps.maxBucketFall()) : maxSafeFall;

        // Parkour is a sprint jump a player makes; the physics probes (BaritoneInputPhysicsProbeGameTests) pin that a bot's jump is
        // a player's. Baritone plans gaps of 2 and 3 blocks (never wider) and refuses to plan one when it cannot sprint.
        settings.allowParkour.value = caps.parkourEnabled();
        settings.allowParkourPlace.value = caps.parkourEnabled() && caps.parkourPlaceEnabled();
        settings.allowParkourAscend.value = caps.parkourEnabled() && caps.parkourAscendEnabled();

        settings.allowVines.value = caps.vinesEnabled();
        // Mob avoidance reads the mobs through IPlayerContext#entities(), which is the bot's observation-filtered list
        // (ServerPlayerContext#refreshEntities: hostile mobs it can see, nothing else), so an unseen mob is never avoided.
        settings.avoidance.value = caps.mobAvoidanceEnabled();
    }
}
