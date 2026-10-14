package dev.spawnbotswrapper.inhabitants.gametest;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The wrapper's {@code pvpbotSettings} policy against the real PvP BOT settings object of the GameTest world. The run
 * directory starts PvP BOT with autoEquipWeapon at its own default (true); the wrapper has to turn it off and hold the
 * short ranges, and has to do it again whenever PvP BOT loads its settings anew.
 */
public final class ManagedSettingsGameTests {
    private static final String ENV = "pvpbot-inhabitants-gametest:";

    private static Path settingsFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("pvpbot").resolve("worlds").resolve("Test Level")
                .resolve("settings.json");
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(net.minecraft.network.chat.Component.nullToEmpty(message));
            throw new IllegalStateException(message);
        }
    }

    private static String held() {
        return "autoEquipWeapon=" + Upstream.setting("isAutoEquipWeapon") + " autoTarget=" + Upstream.setting("isAutoTargetEnabled") + " maxTargetDistance="
                + Upstream.setting("getMaxTargetDistance") + " ranged=" + Upstream.setting("getRangedMinRange") + "/"
                + Upstream.setting("getRangedOptimalRange") + "/" + Upstream.setting("getRangedMaxRange") + " retreatOnClose="
                + Upstream.setting("isRangedRetreatOnClose") + " meleeRange=" + Upstream.setting("getMeleeRange") + " bowMinDrawTime="
                + Upstream.setting("getBowMinDrawTime") + " autoTotem=" + Upstream.setting("isAutoTotemEnabled") + " totemPriority="
                + Upstream.setting("isTotemPriority");
    }

    private static boolean isHeld(String getter, Object expected) {
        return Objects.equals(expected, Upstream.setting(getter));
    }

    private static boolean heldAtTheShippedValues() {
        InhabitantsConfig.PvpbotSettings shipped = InhabitantsConfig.PvpbotSettings.shipped();
        return isHeld("isAutoEquipWeapon", shipped.autoEquipWeapon)
                && isHeld("getMaxTargetDistance", shipped.maxTargetDistance)
                && isHeld("isAutoTargetEnabled", shipped.autoTargetEnabled)
                && isHeld("getBowMinDrawTime", shipped.bowMinDrawTime)
                && isHeld("getRangedMinRange", shipped.rangedMinRange)
                && isHeld("getRangedOptimalRange", shipped.rangedOptimalRange)
                && isHeld("getRangedMaxRange", shipped.rangedMaxRange)
                && isHeld("isRangedRetreatOnClose", shipped.rangedRetreatOnClose)
                && isHeld("getMeleeRange", shipped.meleeRange)
                && isHeld("isAutoTotemEnabled", shipped.autoTotemEnabled)
                && isHeld("isTotemPriority", shipped.totemPriority);
    }

    private static void requireFileSetting(GameTestHelper context, String file, String name, Object expected) {
        String entry = "\"" + name + "\": " + expected;
        require(context, file.contains(entry), "the settings file is missing " + entry + ":\n" + file);
    }

    /**
     * PvP BOT starts with auto-equip on and a 64-block radius in its own defaults; the wrapper must replace those
     * with its shipped policy.
     */
    @GameTest(environment = ENV + "settings_applied", maxTicks = 40)
    public void wrapperHoldsPvpBotSettingsAtTheManagedValues(GameTestHelper context) {
        context.runAfterDelay(5, () -> {
            require(context, heldAtTheShippedValues(), "PvP BOT runs with " + held());
            try {
                String file = Files.readString(settingsFile(), StandardCharsets.UTF_8);
                InhabitantsConfig.PvpbotSettings shipped = InhabitantsConfig.PvpbotSettings.shipped();
                requireFileSetting(context, file, "autoEquipWeapon", shipped.autoEquipWeapon);
                requireFileSetting(context, file, "autoTargetEnabled", shipped.autoTargetEnabled);
                requireFileSetting(context, file, "maxTargetDistance", shipped.maxTargetDistance);
                requireFileSetting(context, file, "rangedMinRange", shipped.rangedMinRange);
                requireFileSetting(context, file, "rangedOptimalRange", shipped.rangedOptimalRange);
                requireFileSetting(context, file, "rangedMaxRange", shipped.rangedMaxRange);
                requireFileSetting(context, file, "rangedRetreatOnClose", shipped.rangedRetreatOnClose);
                requireFileSetting(context, file, "meleeRange", shipped.meleeRange);
                requireFileSetting(context, file, "bowMinDrawTime", shipped.bowMinDrawTime);
                requireFileSetting(context, file, "autoTotemEnabled", shipped.autoTotemEnabled);
                requireFileSetting(context, file, "totemPriority", shipped.totemPriority);
            } catch (IOException e) {
                context.fail(net.minecraft.network.chat.Component.nullToEmpty("cannot read " + settingsFile() + ": " + e));
            }
            context.succeed();
        });
    }

    /** {@code /pvpbot reload} makes PvP BOT read its file into a NEW settings object: whatever the file says wins until the wrapper applies its values again. */
    @GameTest(environment = ENV + "settings_reload", maxTicks = 100)
    public void managedSettingsAreAppliedAgainWhenPvpBotLoadsItsSettingsAgain(GameTestHelper context) {
        context.runAfterDelay(5, () -> {
            require(context, heldAtTheShippedValues(), "before the reload PvP BOT runs with " + held());
            Object before = Upstream.settingsObject();
            try {
                // Only the managed keys go back to PvP BOT's defaults: the file is the whole settings state of the GameTest
                // world, and every test after this one runs against it.
                String file = Files.readString(settingsFile(), StandardCharsets.UTF_8)
                        .replaceAll("\"autoEquipWeapon\": \\w+", "\"autoEquipWeapon\": true")
                        .replaceAll("\"autoTargetEnabled\": \\w+", "\"autoTargetEnabled\": true")
                        .replaceAll("\"bowMinDrawTime\": [0-9]+", "\"bowMinDrawTime\": 40")
                        .replaceAll("\"maxTargetDistance\": [0-9.]+", "\"maxTargetDistance\": 40.0")
                        .replaceAll("\"rangedMinRange\": [0-9.]+", "\"rangedMinRange\": 20.0")
                        .replaceAll("\"rangedOptimalRange\": [0-9.]+", "\"rangedOptimalRange\": 40.0")
                        .replaceAll("\"rangedMaxRange\": [0-9.]+", "\"rangedMaxRange\": 60.0")
                        .replaceAll("\"rangedRetreatOnClose\": \\w+", "\"rangedRetreatOnClose\": true")
                        .replaceAll("\"meleeRange\": [0-9.]+", "\"meleeRange\": 3.5")
                        .replaceAll("\"autoTotemEnabled\": \\w+", "\"autoTotemEnabled\": true")
                        .replaceAll("\"totemPriority\": \\w+", "\"totemPriority\": true");
                Files.writeString(settingsFile(), file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                context.fail(net.minecraft.network.chat.Component.nullToEmpty("cannot write " + settingsFile() + ": " + e));
            }
            Upstream.reloadSettings();
            require(context, Upstream.settingsObject() != before, "PvP BOT did not replace its settings object on load");
            require(context, Boolean.TRUE.equals(Upstream.setting("isAutoEquipWeapon")),
                    "the reloaded settings do not carry the file's values: " + held());
            context.succeedWhen(() -> require(context, heldAtTheShippedValues(),
                    "the wrapper did not apply the managed values to the reloaded settings: " + held()));
        });
    }
}
