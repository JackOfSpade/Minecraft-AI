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
    private BaritoneSettings() {
    }

    public static void applyFixed() {
        Settings settings = BaritoneAPI.getSettings();

        // -- Moves our bots do not do, or do not do safely.
        // Parkour needs sprint-jump timing that depends on the exact tick a jump input lands; we have verified that the
        // physics is identical to a player's, but a failed 4-block jump is a fall into whatever is below. Off, all three.
        settings.allowParkour.value = false;
        settings.allowParkourPlace.value = false;
        settings.allowParkourAscend.value = false;
        // The water-bucket clutch places water mid-fall and picks it up again; the bots have no such routine and a wrong
        // guess is a death, so falls are only taken when they are safe on their own (see applyNavLimits).
        settings.allowWaterBucketFall.value = false;

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
        settings.chunkCaching.value = false;
        // Mob avoidance is a per-tick entity scan on the server thread for a cost term we do not use.
        settings.avoidance.value = false;

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
        int maxSafeFall = Math.max(1, MinecraftAiConfig.get().nav().maxSafeFall());
        // Without a bucket this is the only fall limit that is consulted; the bucket limit is set to the same number so
        // that it can never exceed what the mod considers survivable even if a bucket ever gets into the picture.
        settings.maxFallHeightNoWater.value = maxSafeFall;
        settings.maxFallHeightBucket.value = maxSafeFall;
    }
}
