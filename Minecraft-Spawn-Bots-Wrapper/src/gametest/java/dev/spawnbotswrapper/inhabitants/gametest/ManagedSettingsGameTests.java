package dev.spawnbotswrapper.inhabitants.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
                + Upstream.setting("getBowMinDrawTime");
    }

    private static boolean heldAtTheShippedValues() {
        return Boolean.FALSE.equals(Upstream.setting("isAutoEquipWeapon"))
                && Double.valueOf(64.0).equals(Upstream.setting("getMaxTargetDistance"))
                && Boolean.FALSE.equals(Upstream.setting("isAutoTargetEnabled"))
                && Integer.valueOf(20).equals(Upstream.setting("getBowMinDrawTime"))
                && Double.valueOf(8.0).equals(Upstream.setting("getRangedMinRange"))
                && Double.valueOf(12.0).equals(Upstream.setting("getRangedOptimalRange"))
                && Double.valueOf(16.0).equals(Upstream.setting("getRangedMaxRange"))
                && Boolean.FALSE.equals(Upstream.setting("isRangedRetreatOnClose"))
                && Double.valueOf(2.5).equals(Upstream.setting("getMeleeRange"));
    }

    /** PvP BOT starts with auto-equip on and a 64 block radius in nothing but its own defaults; the wrapper must have fixed that. */
    @GameTest(environment = ENV + "settings_applied", maxTicks = 40)
    public void wrapperHoldsPvpBotSettingsAtTheManagedValues(GameTestHelper context) {
        context.runAfterDelay(5, () -> {
            require(context, heldAtTheShippedValues(), "PvP BOT runs with " + held());
            try {
                String file = Files.readString(settingsFile(), StandardCharsets.UTF_8);
                require(context, file.contains("\"autoEquipWeapon\": false"),
                        "the settings file was not written with autoEquipWeapon=false:\n" + file);
                require(context, file.contains("\"rangedOptimalRange\": 12.0"),
                        "the managed optimal range is not in the file:\n" + file);
                require(context, file.contains("\"bowMinDrawTime\": 20"),
                        "the managed bow draw time is not in the file:\n" + file);
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
                        .replaceAll("\"meleeRange\": [0-9.]+", "\"meleeRange\": 3.5");
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
