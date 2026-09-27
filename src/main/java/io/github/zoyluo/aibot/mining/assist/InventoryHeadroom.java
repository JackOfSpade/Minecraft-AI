package io.github.zoyluo.aibot.mining.assist;

/**
 * Pure inventory and tool-wear rules of the detour (mining-assist design 4.6 steps 7 and 8). The host reads the
 * inventory (empty main slots, room left in partial stacks of the drop item, the tool's durability) and asks
 * this class, so "63/64 partial stack" and "durability reserve" are unit tests, not GameTests.
 *
 * <h2>Capacity (merge aware)</h2>
 * A break is admitted when, after its expected yield lands, at least {@code reserve} main-inventory slots are
 * still empty ({@code detour.minFreeSlots}, 3 by default, one more in rare expedition batches):
 * <pre>
 * overflow   = max(0, expectedYield - roomInPartialStacks)      items that need a new slot
 * slotsNeeded = ceil(overflow / STACK_SIZE)                     STACK_SIZE = 64
 * capacityOk = emptyMainSlots - slotsNeeded &gt;= reserve
 * </pre>
 * So a yield that merges into a partial stack costs no slot: with {@code emptyMainSlots == reserve}, a 63/64
 * stack (room 1) admits a yield of 1 and refuses a yield of 2. An unknown drop (a modded ore, or a block whose
 * drop item the host cannot name) needs {@code max(reserve, }{@value #UNKNOWN_DROP_MIN_EMPTY_SLOTS}{@code )}
 * empty slots ({@link #unknownDropOk}).
 *
 * <h2>Tool wear (design 4.6 step 7)</h2>
 * The channel tool that {@code ToolSelector.equipMiningChannelTool} returns must have at least
 * {@code max(24 + 2 * plannedMembers, ceil(0.15 * maxDamage))} uses left ({@link #durabilityOk}).
 */
public final class InventoryHeadroom {
    /** Every ore drop stacks to 64. */
    public static final int STACK_SIZE = 64;
    /** An unknown drop needs at least this many empty slots regardless of a smaller reserve. */
    public static final int UNKNOWN_DROP_MIN_EMPTY_SLOTS = 3;
    /** Design 4.6 step 7: flat part of the durability reserve. */
    public static final int WEAR_BASE = 24;
    /** Design 4.6 step 7: extra uses reserved per planned member. */
    public static final int WEAR_PER_MEMBER = 2;
    /** Design 4.6 step 7: fraction of the tool's maximum durability that must remain. */
    public static final double WEAR_FRACTION = 0.15D;

    /**
     * What one break of a block is expected to yield: at most {@code maxItems} items (no Fortune), and whether
     * the drop item is known well enough to compute room in partial stacks. {@code itemKnown == false} means
     * the caller must use {@link #unknownDropOk}.
     */
    public record Estimate(int maxItems, boolean itemKnown) {
    }

    private InventoryHeadroom() {
    }

    /**
     * The design's {@code capacityOk(emptyMainSlots, roomInPartialStacksForItem, expectedYield, reserve)}, see
     * the class comment for the formula. Negative inputs count as 0; a {@code reserve} below 0 counts as 0.
     */
    public static boolean capacityOk(int emptyMainSlots, int roomInPartialStacks, int expectedYield, int reserve) {
        throw new UnsupportedOperationException("P1 stub: InventoryHeadroom.capacityOk");
    }

    /** True when {@code emptyMainSlots >= max(reserve, UNKNOWN_DROP_MIN_EMPTY_SLOTS)}. */
    public static boolean unknownDropOk(int emptyMainSlots, int reserve) {
        throw new UnsupportedOperationException("P1 stub: InventoryHeadroom.unknownDropOk");
    }

    /**
     * Expected drop of a valuable, by registry path (a {@code deepslate_} prefix and a namespace are ignored):
     * coal, iron, gold, diamond, emerald, nether quartz ores and ancient debris and the raw_*_block blocks give
     * 1 known item; copper ore 5; redstone ore 5; lapis ore 9; nether gold ore 6 (all known). Everything else
     * (gilded blackstone, amethyst cluster, any modded or unlisted ore, null) gives {@code Estimate(4, false)}
     * for an unlisted block and is treated as unknown by the caller.
     */
    public static Estimate estimate(String registryPath) {
        throw new UnsupportedOperationException("P1 stub: InventoryHeadroom.estimate");
    }

    /**
     * True when the tool has enough uses left: {@code remaining >= max(WEAR_BASE + WEAR_PER_MEMBER *
     * plannedMembers, ceil(WEAR_FRACTION * maxDamage))}. {@code maxDamage <= 0} (an unbreakable or non-damageable
     * tool) is always fine. {@code remaining} is {@code maxDamage - damage}.
     */
    public static boolean durabilityOk(int remainingDurability, int maxDamage, int plannedMembers) {
        throw new UnsupportedOperationException("P1 stub: InventoryHeadroom.durabilityOk");
    }
}
