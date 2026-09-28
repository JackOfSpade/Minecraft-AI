package io.github.zoyluo.minecraftai.log;

/**
 * Pure consistency check backing {@code GatherQuotaTask}'s {@code gather_summary} {@code
 * consistent} field (see docs/LOGGING.md "Auditing a gather"). Kept separate from {@code
 * GatherQuotaTask} itself -- whose class carries Minecraft {@code Items} references in a static
 * field initializer -- so this arithmetic can be unit-tested without a Minecraft bootstrap.
 */
public final class GatherConsistency {
    private GatherConsistency() {
    }

    /**
     * A task's reported gains are consistent with what it can prove it did: every gained item
     * must be explained by the blocks it broke (bounded by that family's max drops per block) plus
     * whatever arrived unattributed, and it must never have used a forced pickup -- strict_survival
     * requires that to always be zero.
     */
    public static boolean isConsistent(int gained, int breaks, int maxDropsPerBlock,
                                        int unattributedGains, int forcedPickups) {
        return gained <= (long) breaks * maxDropsPerBlock + unattributedGains && forcedPickups == 0;
    }
}
