package io.github.zoyluo.minecraftai.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class VeinMinerConfigCheckTest {
    @Test
    void onlyAnExplicitTrueCountsAsRestricted() {
        assertTrue(VeinMinerConfigCheck.permissionRestricted("{\"permissionRestricted\": true}"));
        assertTrue(VeinMinerConfigCheck.permissionRestricted("{\"autoUpdate\":false,\"permissionRestricted\":true,\"x\":[1]}"));
        assertTrue(VeinMinerConfigCheck.permissionRestricted("{\"permissionRestricted\": \"true\"}"));
        assertFalse(VeinMinerConfigCheck.permissionRestricted("{\"permissionRestricted\": false}"));
        assertFalse(VeinMinerConfigCheck.permissionRestricted("{}"));
        assertFalse(VeinMinerConfigCheck.permissionRestricted("{\"PermissionRestricted\": true}"));
    }

    @Test
    void malformedOrOddlyShapedInputNeverThrowsAndIsNotRestricted() {
        for (String input : new String[]{null, "", "   ", "not json", "{", "[true]", "null", "42", "\"permissionRestricted\"",
                "{\"permissionRestricted\": null}", "{\"permissionRestricted\": [true]}", "{\"permissionRestricted\": {}}",
                "{\"permissionRestricted\": 1}", "\u0000\u0001"}) {
            assertFalse(VeinMinerConfigCheck.permissionRestricted(input), String.valueOf(input));
        }
    }

    @Test
    void warnsOnlyWhenVeinMinerIsLoadedAndNotRestricted() {
        assertNull(VeinMinerConfigCheck.warning(false, null));
        assertNull(VeinMinerConfigCheck.warning(false, "{}"));
        assertNull(VeinMinerConfigCheck.warning(true, "{\"permissionRestricted\": true}"));
        String unrestricted = VeinMinerConfigCheck.warning(true, "{\"permissionRestricted\": false}");
        assertNotNull(unrestricted);
        assertTrue(unrestricted.contains("permissionRestricted") && unrestricted.contains("bots"));
        assertNotNull(VeinMinerConfigCheck.warning(true, "garbage"));
        String missing = VeinMinerConfigCheck.warning(true, null);
        assertNotNull(missing);
        assertTrue(missing.contains("missing"));
    }

    @Test
    void readingAMissingSettingsFileIsNullNotAnError() {
        assertNull(VeinMinerConfigCheck.readSettings(Path.of("build", "no-such-config-dir")));
    }
}
