package io.github.zoyluo.minecraftai.mode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityPolicyTest {
    @Test
    void strictSurvivalRejectsEveryPrivilegeEvenWhenOperatorFlagsAreEnabled() {
        for (PrivilegedCapability capability : PrivilegedCapability.values()) {
            CapabilityDecision decision = CapabilityPolicy.decide(
                    OperatingProfile.STRICT_SURVIVAL,
                    OperatorCapabilities.defaults(),
                    capability);

            assertFalse(decision.allowed(), capability.name());
            org.junit.jupiter.api.Assertions.assertEquals(
                    capability == PrivilegedCapability.HIDDEN_BLOCK_SCAN
                            ? CapabilityDecision.Reason.DENIED_RETIRED_CAPABILITY
                            : CapabilityDecision.Reason.DENIED_STRICT_SURVIVAL,
                    decision.reason());
        }
    }

    @Test
    void operatorCannotReenableTheRetiredHiddenWorldCapability() {
        OperatorCapabilities flags = new OperatorCapabilities(true, false, false);

        CapabilityDecision hidden = decide(flags, PrivilegedCapability.HIDDEN_BLOCK_SCAN);
        assertFalse(hidden.allowed());
        org.junit.jupiter.api.Assertions.assertEquals(
                CapabilityDecision.Reason.DENIED_RETIRED_CAPABILITY, hidden.reason());
        assertFalse(decide(flags, PrivilegedCapability.EMERGENCY_TELEPORT).allowed());
        assertFalse(decide(flags, PrivilegedCapability.MANUAL_TELEPORT).allowed());
    }

    @Test
    void missingProfileOrOperatorConfigurationFailsClosed() {
        assertFalse(CapabilityPolicy.decide(null, OperatorCapabilities.defaults(),
                PrivilegedCapability.HIDDEN_BLOCK_SCAN).allowed());
        CapabilityDecision missing = CapabilityPolicy.decide(OperatingProfile.OPERATOR, null,
                PrivilegedCapability.EMERGENCY_TELEPORT);
        assertFalse(missing.allowed());
        org.junit.jupiter.api.Assertions.assertEquals(
                CapabilityDecision.Reason.DENIED_MISSING_CONFIGURATION, missing.reason());
    }

    private static CapabilityDecision decide(OperatorCapabilities flags, PrivilegedCapability capability) {
        return CapabilityPolicy.decide(OperatingProfile.OPERATOR, flags, capability);
    }
}
