package org.stepan1411.pvp_bot.bot;

import org.stepan1411.testdouble.Recorder;

import java.lang.reflect.Field;

/**
 * Test-only fake of the upstream settings singleton. Mirrors what matters to the adapter: a static
 * {@code get()}, instance getters, instance fields (the setting names), a few static fields that must NOT
 * be reported as settings, and setters/load/save that must never be called.
 */
public class BotSettings {

    private static final Object GSON = new Object();
    private static BotSettings INSTANCE = new BotSettings();
    private static String configPath;
    public static final BotSettings DEFAULTS = new BotSettings();

    private boolean autoEquipArmor = true;
    private boolean autoEquipWeapon = true;
    private int checkInterval = 20;
    private boolean combatEnabled = true;
    private boolean autoTargetEnabled = false;
    private boolean rangedEnabled = true;
    private boolean maceEnabled = true;
    private boolean spearEnabled = false;
    private boolean crystalPvpEnabled = true;
    private boolean anchorPvpEnabled = true;
    private boolean autoTotemEnabled = true;
    private boolean autoEatEnabled = true;
    private boolean autoShieldEnabled = true;
    private boolean autoMendEnabled = true;
    private boolean shieldBreakEnabled = true;
    private boolean autoPotionEnabled = true;
    private boolean cobwebEnabled = true;
    private boolean retreatEnabled = true;
    private boolean botsRelogs = true;
    private boolean useSpecialNames = false;
    private boolean botLeaveOnDeath = true;
    private int maxMassSpawn = 1000;
    private boolean profileLagFix = true;
    private boolean safeSpawn = true;
    private boolean clearOnRemove = true;
    private boolean totemPriority = true;
    private boolean preferSword = true;
    private boolean rangedRetreatOnClose = true;

    public static BotSettings get() {
        Recorder.guard("BotSettings.get", "");
        return INSTANCE;
    }

    /** Test control (not part of upstream): sets one instance field of the singleton, e.g. {@code botsRelogs}. */
    public static void put(String field, Object value) {
        try {
            Field f = BotSettings.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(INSTANCE, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(field, e);
        }
    }

    /** Test control: a fresh singleton with upstream's defaults. */
    public static void resetInstance() {
        INSTANCE = new BotSettings();
    }

    public static void load() {
        Recorder.FORBIDDEN.add("BotSettings.load");
    }

    public static void save() {
        Recorder.FORBIDDEN.add("BotSettings.save");
    }

    public boolean isAutoEquipArmor() { return autoEquipArmor; }
    public boolean isAutoEquipWeapon() { return autoEquipWeapon; }
    public int getCheckInterval() { return checkInterval; }
    public boolean isCombatEnabled() { return combatEnabled; }
    public boolean isAutoTargetEnabled() { return autoTargetEnabled; }
    public boolean isRangedEnabled() { return rangedEnabled; }
    public boolean isMaceEnabled() { return maceEnabled; }
    public boolean isSpearEnabled() { return spearEnabled; }
    public boolean isCrystalPvpEnabled() { return crystalPvpEnabled; }
    public boolean isAnchorPvpEnabled() { return anchorPvpEnabled; }
    public boolean isAutoTotemEnabled() { return autoTotemEnabled; }
    public boolean isAutoEatEnabled() { return autoEatEnabled; }
    public boolean isAutoShieldEnabled() { return autoShieldEnabled; }
    public boolean isAutoMendEnabled() { return autoMendEnabled; }
    public boolean isShieldBreakEnabled() { return shieldBreakEnabled; }
    public boolean isAutoPotionEnabled() { return autoPotionEnabled; }
    public boolean isCobwebEnabled() { return cobwebEnabled; }
    public boolean isRetreatEnabled() { return retreatEnabled; }
    public boolean isBotsRelogs() { return botsRelogs; }
    public boolean isUseSpecialNames() { return useSpecialNames; }
    public boolean isBotLeaveOnDeath() { return botLeaveOnDeath; }
    public int getMaxMassSpawn() { return maxMassSpawn; }
    public boolean isProfileLagFix() { return profileLagFix; }
    public boolean isSafeSpawn() { return safeSpawn; }
    public boolean isClearOnRemove() { return clearOnRemove; }
    public boolean isTotemPriority() { return totemPriority; }
    public boolean isPreferSword() { return preferSword; }
    public boolean isRangedRetreatOnClose() { return rangedRetreatOnClose; }

    public void setBotsRelogs(boolean v) { Recorder.FORBIDDEN.add("setBotsRelogs"); }
    public void setBotLeaveOnDeath(boolean v) { Recorder.FORBIDDEN.add("setBotLeaveOnDeath"); }
    public void setAutoTargetEnabled(boolean v) { Recorder.FORBIDDEN.add("setAutoTargetEnabled"); }
    public void setMaceEnabled(boolean v) { Recorder.FORBIDDEN.add("setMaceEnabled"); }
    public void setCheckInterval(int v) { Recorder.FORBIDDEN.add("setCheckInterval"); }
}
