package dev.spawnbotswrapper.inhabitants.combat;

/**
 * The offhand rule (O1) for every inhabitant, as a pure decision: the best shield the bot carries, else a totem of undying, else
 * whatever it is. It is the same rule the Minecraft-AI companions follow, so all bots behave alike.
 * <ul>
 *   <li>Empty offhand (the shield broke, the totem popped): the best carried shield, else a carried totem, else nothing. The
 *       replacement is always of the same kind first: after a shield the next shield, only when there is none a totem; after
 *       a totem the next totem (a shield that was carried would already have been in the offhand).</li>
 *   <li>A totem in the offhand gives way to a carried shield (the totem takes the shield's place): a bot that holds a totem only
 *       because it had no shield switches to a shield as soon as it has one.</li>
 *   <li>A shield in the offhand is never swapped for another: it is used until it breaks.</li>
 *   <li>Any other offhand item is left alone.</li>
 * </ul>
 * "Best shield" means {@link #bestShield}: an enchanted shield before a plain one, then the lowest slot; wear never counts.
 * See {@code mc.OffhandPolicy} for the code that reads the inventory and moves the stacks.
 */
public final class OffhandRule {
    private OffhandRule() {
    }

    /** What the offhand holds, as far as the rule cares. */
    public enum Held {
        EMPTY, SHIELD, TOTEM, OTHER
    }

    /** What to put into the offhand. */
    public enum Move {
        NONE, SHIELD, TOTEM
    }

    /**
     * The best carried shield: an enchanted one beats a plain one, and of equal shields the lowest slot wins. Nothing else counts,
     * in particular not wear: a worn shield is used until it breaks like any other item. Among several enchanted shields the lowest
     * slot wins as well (the addon does not rank enchantments). Returns -1 for none.
     *
     * @param isShield   whether the slot holds a shield
     * @param isEnchanted whether the stack in the slot is enchanted
     * @param slots      the number of slots to look at (the addon passes the 36 hotbar and main inventory slots)
     */
    public static int bestShield(java.util.function.IntPredicate isShield, java.util.function.IntPredicate isEnchanted, int slots) {
        int best = -1;
        for (int i = 0; i < slots; i++) {
            if (!isShield.test(i)) {
                continue;
            }
            if (best < 0 || isEnchanted.test(i) && !isEnchanted.test(best)) {
                best = i;
            }
        }
        return best;
    }

    public static Move decide(Held offhand, boolean shieldCarried, boolean totemCarried) {
        return switch (offhand) {
            case OTHER, SHIELD -> Move.NONE;
            case TOTEM -> shieldCarried ? Move.SHIELD : Move.NONE;
            case EMPTY -> shieldCarried ? Move.SHIELD : totemCarried ? Move.TOTEM : Move.NONE;
        };
    }
}
