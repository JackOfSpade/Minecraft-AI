package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.adapter.ManagedSettings;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Ensures the server lifecycle hands every shipped PvP BOT setting to the adapter without duplicating the policy. */
class ServerSessionManagedSettingsTest {

    @Test
    void translatesEveryShippedSettingIntoTheAdapterPolicy() {
        InhabitantsConfig.PvpbotSettings shipped = InhabitantsConfig.PvpbotSettings.shipped();

        assertEquals(new ManagedSettings(shipped.maxTargetDistance, shipped.rangedMinRange, shipped.rangedOptimalRange,
                        shipped.rangedMaxRange, shipped.autoEquipWeapon, shipped.autoTargetEnabled,
                        shipped.rangedRetreatOnClose, shipped.meleeRange, shipped.bowMinDrawTime,
                        shipped.autoTotemEnabled, shipped.totemPriority),
                ServerSession.managedSettings(shipped));
    }

    @Test
    void aMissingSettingsBlockLeavesEveryUpstreamSettingUnmanaged() {
        assertEquals(ManagedSettings.NONE, ServerSession.managedSettings(null));
    }
}
