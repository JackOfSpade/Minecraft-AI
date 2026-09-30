package dev.spawnbotswrapper.inhabitants.profile;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.spawnbotswrapper.inhabitants.profile.ProfileTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * profiles.disabledEnchantments (Piercing by default: a piercing bolt ignores a raised shield in vanilla Java): the
 * roller never applies a disabled enchantment, but still consumes the same random draws, so a seeded loadout differs
 * from the old one only by the missing enchantment and nothing else (no Multishot in its place).
 */
class ProfileDisabledEnchantmentsTest {
    private static final int N = 2500;
    private static final long BASE = 31337L;

    private static InhabitantsConfig.Profiles options(String... disabled) {
        InhabitantsConfig.Profiles o = everythingOptions();
        o.disabledEnchantments = new ArrayList<>(List.of(disabled));
        return o;
    }

    private static List<String> fingerprints(InhabitantsConfig.Profiles o) {
        List<String> out = new ArrayList<>();
        for (BotProfile p : profiles(generate(N, allOn(), o, BASE))) {
            out.add(fingerprint(p));
        }
        return out;
    }

    @Test
    void theDefaultIsPiercing() {
        assertEquals(List.of("minecraft:piercing"), new InhabitantsConfig.Profiles().disabledEnchantments);
    }

    @Test
    void defaultRollsNeverContainPiercingAndNeverSubstituteMultishot() {
        InhabitantsConfig.Profiles defaults = defaultOptions();
        int crossbows = 0;
        for (BotProfile p : profiles(generate(N, allOn(), defaults, BASE))) {
            for (BotProfile.ItemSpec s : specs(p)) {
                assertFalse(s.enchantments().containsKey(NS + "piercing"), fingerprint(p));
                assertFalse(s.enchantments().containsKey(NS + "multishot"), fingerprint(p));
                if (s.item().equals(NS + "crossbow")) {
                    crossbows++;
                }
            }
        }
        assertTrue(crossbows > 100, "the test must actually see crossbows, saw " + crossbows);
    }

    @Test
    void aSeededLoadoutDiffersFromTheOldOneOnlyByTheMissingPiercing() {
        List<String> off = fingerprints(options());                       // nothing disabled: the old behaviour
        List<String> on = fingerprints(options("minecraft:piercing"));
        int changed = 0;
        for (int i = 0; i < N; i++) {
            String expected = off.get(i).replaceAll("\\+piercing\\d+", "");
            assertEquals(expected, on.get(i), "profile " + i + ": only piercing may differ");
            if (!expected.equals(off.get(i))) {
                changed++;
            }
        }
        assertTrue(changed > 50, "piercing crossbows must occur without the denylist, saw " + changed);
    }

    @Test
    void idsWorkWithoutTheNamespaceAndInAnyCase() {
        List<String> canonical = fingerprints(options("minecraft:piercing"));
        assertEquals(canonical, fingerprints(options("piercing")));
        assertEquals(canonical, fingerprints(options(" Minecraft:PIERCING ")));
    }

    @Test
    void anyOtherEnchantmentCanBeDisabledToo() {
        List<String> off = fingerprints(options());
        List<String> on = fingerprints(options("minecraft:unbreaking", "sharpness", "minecraft:mending", "thorns",
                "protection", "infinity"));
        for (int i = 0; i < N; i++) {
            String expected = off.get(i)
                    .replaceAll("\\+(unbreaking|sharpness|mending|thorns|protection|infinity)\\d+", "");
            assertEquals(expected, on.get(i), "profile " + i);
        }
    }

    @Test
    void anEmptyListDisablesTheFeature() {
        boolean seen = false;
        for (String f : fingerprints(options())) {
            seen |= f.contains("+piercing");
        }
        assertTrue(seen);
    }
}
