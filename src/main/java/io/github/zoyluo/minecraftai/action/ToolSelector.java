package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.mining.ToolTier;
import io.github.zoyluo.minecraftai.util.ItemStackUtil;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class ToolSelector {
    private ToolSelector() {
    }

    public record Selection(boolean changed, int slot, ItemStack stack, float score) {
        public String describe() {
            if (slot < 0 || stack.isEmpty()) {
                return "no_tool";
            }
            return stack.getItem() + " slot=" + slot + " score=" + score + " changed=" + changed;
        }
    }

    public static Selection equipBestTool(AIPlayerEntity player, BlockState state) {
        return equipBestTool(player, state, true);
    }

    /**
     * As {@link #equipBestTool(AIPlayerEntity, BlockState)}; with {@code swordsMine} false a sword is never chosen to break a
     * block (it would be worn down for nothing on leaves, and is no digging tool otherwise): it counts like an empty hand. This
     * is the rule of a driver that digs whatever is in its way (Baritone's {@code useSwordToMine=false}).
     */
    public static Selection equipBestTool(AIPlayerEntity player, BlockState state, boolean swordsMine) {
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
            // Nothing to dig (a gap between dig steps, or a fluid cell): scoring every stack against air
            // ties them all, which picked a plank stack and made the hotbar ping-pong between steps.
            int held = player.getInventory().getSelectedSlot();
            return new Selection(false, held, player.getInventory().getNonEquipmentItems().get(held), 0.0F);
        }
        Inventory inventory = player.getInventory();
        int currentSlot = inventory.getSelectedSlot();
        Choice choice = choose(inventory.getNonEquipmentItems(), currentSlot,
                player.getItemBySlot(EquipmentSlot.OFFHAND), state, swordsMine);
        int bestSlot = choice.slot();
        ItemStack bestStack = choice.stack();
        float bestScore = choice.score();

        if (choice.offhand()) {
            int promoted = InventoryAction.promoteOffhandSlot(player, 0)
                    .orElse(-1);
            int hotbar = promoted < 0 ? -1 : InventoryAction.equipFromSlot(player, promoted);
            ItemStack equipped = hotbar >= 0
                    ? player.getInventory().getNonEquipmentItems().get(hotbar) : ItemStack.EMPTY;
            BotLog.action(player, "equip_best_tool", "slot", hotbar,
                    "tool", equipped.getItem(), "score", bestScore, "source", "offhand");
            return new Selection(hotbar >= 0, hotbar, equipped, bestScore);
        }
        if (bestSlot != currentSlot) {
            if (bestStack.isEmpty()) {
                boolean changed = InventoryAction.selectHotbar(player, bestSlot).isSuccess();
                BotLog.action(player, "equip_best_tool", "slot", bestSlot,
                        "tool", "empty_hand", "score", bestScore,
                        "reason", "preserve_soft_block_melee_durability");
                return new Selection(changed, bestSlot, ItemStack.EMPTY, bestScore);
            }
            int hotbar = InventoryAction.equipFromSlot(player, bestSlot);
            ItemStack equipped = hotbar >= 0 ? player.getInventory().getNonEquipmentItems().get(hotbar) : ItemStack.EMPTY;
            BotLog.action(player, "equip_best_tool", "slot", hotbar, "tool", equipped.getItem(), "score", bestScore);
            return new Selection(true, hotbar, equipped, bestScore);
        }
        return new Selection(false, currentSlot, bestStack, bestScore);
    }

    /** The tool decision over a set of stacks: the slot of the main list, or the off hand. Slot {@code currentSlot} means "keep the hand". */
    public record Choice(int slot, boolean offhand, ItemStack stack, float score) {
    }

    /**
     * The pure part of {@link #equipBestTool}: which stack breaks {@code state} best, by the mod's tool policy (a tool that can
     * harvest the block beats one that cannot, the cheapest renewable tier among those that can, an empty hand or a non-melee
     * stack for blocks that need no tool; wear never counts: a tool is used until it breaks). Reads the stacks only, so a caller may run it on a
     * snapshot of an inventory (Baritone's cost model does, on a worker thread).
     */
    public static Choice choose(List<ItemStack> main, int currentSlot, ItemStack offhand, BlockState state, boolean swordsMine) {
        return choose(main, currentSlot, offhand, state, swordsMine, GearValue.worstFirstEnabled());
    }

    /**
     * As above with the gear order given: {@code worstFirst} picks, among the stacks that can do the job (drop the block's loot, or
     * speed up a block that needs no tool), the one of the lowest {@link GearValue} (a wooden pickaxe before a stone one before an
     * iron one; Silk Touch last; a more worn stack before a fresh one of the same value); {@code false} is the earlier best-first
     * durability-preservation policy. Without any capable stack both fall back to the same scoring.
     */
    static Choice choose(List<ItemStack> main, int currentSlot, ItemStack offhand, BlockState state, boolean swordsMine,
                         boolean worstFirst) {
        if (worstFirst) {
            Choice worst = worstCapableChoice(main, currentSlot, offhand, state, swordsMine);
            if (worst != null) {
                return worst;
            }
        }
        ItemStack currentStack = main.get(currentSlot);
        float currentScore = score(currentStack, state, swordsMine);
        if (!swordsMine && currentStack.is(ItemTags.SWORDS)) {
            currentScore -= 0.002F; // a sword that is only in hand by chance yields to any other stack on a tie (cobweb: nothing beats the bare hand)
        }
        int bestSlot = currentSlot;
        boolean bestOffhand = false;
        ItemStack bestStack = currentStack;
        float bestScore = currentScore;
        int bestHandSafety = softBlockHandSafety(currentStack, state);

        for (int slot = 0; slot < main.size(); slot++) {
            ItemStack stack = main.get(slot);
            // Only an empty hotbar slot can be selected as an executable empty hand. Empty storage
            // slots are not candidates because equipFromSlot deliberately rejects them.
            if (stack.isEmpty() && !Inventory.isHotbarSlot(slot)) {
                continue;
            }
            float candidateScore = score(stack, state, swordsMine);
            int candidateHandSafety = softBlockHandSafety(stack, state);
            if (isBetterCandidate(candidateScore, candidateHandSafety,
                    bestScore, bestHandSafety)) {
                bestScore = candidateScore;
                bestHandSafety = candidateHandSafety;
                bestSlot = slot;
                bestOffhand = false;
                bestStack = stack;
            }
        }
        if (!offhand.isEmpty()) {
            float candidateScore = score(offhand, state, swordsMine);
            int candidateHandSafety = softBlockHandSafety(offhand, state);
            if (isBetterCandidate(candidateScore, candidateHandSafety,
                    bestScore, bestHandSafety)) {
                bestScore = candidateScore;
                bestSlot = -1;
                bestOffhand = true;
                bestStack = offhand;
            }
        }
        return new Choice(bestSlot, bestOffhand, bestStack, bestScore);
    }

    /** A stack that can do the job, with what the worst-first order ranks it by. */
    private record Capable(int slot, boolean offhand, ItemStack stack, boolean silk, double value, int remaining) {
    }

    private static boolean capableFor(ItemStack stack, BlockState state, boolean swordsMine) {
        if (stack.isEmpty() || !swordsMine && stack.is(ItemTags.SWORDS)) {
            return false;
        }
        return state.requiresCorrectToolForDrops() ? stack.isCorrectToolForDrops(state) : stack.getDestroySpeed(state) > 1.0F;
    }

    /** True when {@code a} goes before {@code b}: no Silk Touch first, then the lower value, then the more worn stack. */
    private static boolean worseFirst(Capable a, Capable b) {
        if (a.silk() != b.silk()) {
            return !a.silk();
        }
        return GearValue.Core.compare(a.value(), a.remaining(), b.value(), b.remaining()) < 0;
    }

    /**
     * The worst-first decision: the lowest-value stack that is correct for the block (drops its loot; for a block that needs no tool,
     * one that mines it faster than a bare hand), worn or not, and never a sword when {@code swordsMine} is false.
     * Null when no stack qualifies (a wrong-tier tool, or nothing faster than the hand): the caller then scores as before. Equal
     * stacks keep the hand the bot already holds.
     */
    private static Choice worstCapableChoice(List<ItemStack> main, int currentSlot, ItemStack offhand, BlockState state,
                                             boolean swordsMine) {
        Capable best = null;
        for (int slot = 0; slot < main.size(); slot++) {
            ItemStack stack = main.get(slot);
            if (!capableFor(stack, state, swordsMine)) {
                continue;
            }
            Capable candidate = new Capable(slot, false, stack, GearValue.hasSilkTouch(stack), GearValue.toolValue(stack),
                    GearValue.remaining(stack));
            if (best == null || worseFirst(candidate, best)
                    || !worseFirst(best, candidate) && candidate.slot() == currentSlot && best.slot() != currentSlot) {
                best = candidate;
            }
        }
        if (capableFor(offhand, state, swordsMine)) {
            Capable candidate = new Capable(-1, true, offhand, GearValue.hasSilkTouch(offhand), GearValue.toolValue(offhand),
                    GearValue.remaining(offhand));
            if (best == null || worseFirst(candidate, best)) {
                best = candidate;
            }
        }
        if (best == null) {
            return null;
        }
        float speed = best.stack().getDestroySpeed(state);
        float score = state.requiresCorrectToolForDrops() ? 100.0F + Math.min(speed, 9.9F) * 0.1F : speed;
        return new Choice(best.slot(), best.offhand(), best.stack(), score);
    }


    /**
     * OreDig channel policy: use the lowest pickaxe that can harvest the block (one with a single use left is left out only here: the channel serves exact break/pickup/return transactions, and ToolTier reports a raw-one pick as no pick; elsewhere a worn tool is used until it breaks). With {@code behaviour.gear.worstFirst}
     * (the default) that is the lowest {@link GearValue} (a wooden or golden pick digs stone and coal, a stone pick iron ore, an
     * iron pick diamond, obsidian selects diamond) with no stone floor: missions are worst-first too. With it off, the earlier
     * policy applies: never below stone for ordinary rock, then the lowest tier and the most durable pick. Ordinary rock is never
     * dug with an iron or diamond pick in either mode. Other BlockMiner users keep {@link #equipBestTool} unchanged.
     */
    public static Selection equipMiningChannelTool(AIPlayerEntity player, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) {
            return equipBestTool(player, state);
        }
        Inventory inventory = player.getInventory();
        int currentSlot = inventory.getSelectedSlot();
        // Worst-first: no stone floor either. A wooden (or golden) pickaxe may dig ordinary rock; the cap for ordinary rock (never an
        // iron or diamond pick, that would silently consume the finite mission tool) is unchanged.
        boolean worstFirst = GearValue.worstFirstEnabled();
        int requiredTier = ToolTier.requiredPickaxeTier(state.getBlock());
        int minimumTier = worstFirst ? requiredTier : channelMinimumTier(requiredTier);
        int maximumTier = channelMaximumTier(channelMinimumTier(requiredTier), OreScan.isOreBlock(state.getBlock()));
        int bestSlot = -1;
        int bestOffhandSlot = -1;
        int bestTier = Integer.MAX_VALUE;
        int bestRemaining = -1;
        ItemStack bestChannelStack = ItemStack.EMPTY;
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            ItemStack stack = inventory.getNonEquipmentItems().get(slot);
            int tier = ToolTier.pickaxeTier(stack);
            if (tier < minimumTier || tier > maximumTier || !stack.isCorrectToolForDrops(state)
                    || ItemStackUtil.isNearlyBroken(stack)) {
                continue;
            }
            int remaining = stack.isDamageableItem()
                    ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
            if (channelBetter(worstFirst, tier, remaining, stack, bestTier, bestRemaining, bestChannelStack)) {
                bestTier = tier;
                bestRemaining = remaining;
                bestSlot = slot;
                bestOffhandSlot = -1;
                bestChannelStack = stack;
            }
        }
        ItemStack offHandStack = player.getItemBySlot(EquipmentSlot.OFFHAND);
        int offHandTier = ToolTier.pickaxeTier(offHandStack);
        if (offHandTier >= minimumTier && offHandTier <= maximumTier && offHandStack.isCorrectToolForDrops(state)
                && !ItemStackUtil.isNearlyBroken(offHandStack)) {
            int remaining = offHandStack.isDamageableItem()
                    ? offHandStack.getMaxDamage() - offHandStack.getDamageValue() : Integer.MAX_VALUE;
            if (channelBetter(worstFirst, offHandTier, remaining, offHandStack, bestTier, bestRemaining, bestChannelStack)) {
                bestTier = offHandTier;
                bestRemaining = remaining;
                bestSlot = -1;
                bestOffhandSlot = 0;
                bestChannelStack = offHandStack;
            }
        }
        if (bestSlot < 0 && bestOffhandSlot < 0) {
            // Ordinary branch rock is a renewable stone-tool channel. Falling back to an iron or
            // diamond pick silently consumes the finite mission tool until the target ore becomes
            // unharvestable; return an explicit absence so BlockMiner can fail and replan service.
            return new Selection(false, -1, ItemStack.EMPTY, 0.0F);
        }
        float policyScore = 1000.0F - bestTier * 10.0F;
        if (bestOffhandSlot >= 0) {
            int promoted = InventoryAction.promoteOffhandSlot(player, bestOffhandSlot)
                    .orElse(-1);
            int hotbar = promoted < 0 ? -1 : InventoryAction.equipFromSlot(player, promoted);
            ItemStack equipped = hotbar >= 0 ? inventory.getNonEquipmentItems().get(hotbar) : ItemStack.EMPTY;
            BotLog.action(player, "equip_mining_channel_tool",
                    "slot", hotbar, "tool", equipped.getItem(), "tier", bestTier,
                    "source", "offhand");
            return new Selection(hotbar >= 0, hotbar, equipped, policyScore);
        }
        ItemStack bestStack = inventory.getNonEquipmentItems().get(bestSlot);
        if (bestSlot != currentSlot) {
            int hotbar = InventoryAction.equipFromSlot(player, bestSlot);
            ItemStack equipped = hotbar >= 0 ? inventory.getNonEquipmentItems().get(hotbar) : ItemStack.EMPTY;
            BotLog.action(player, "equip_mining_channel_tool",
                    "slot", hotbar, "tool", equipped.getItem(), "tier", bestTier);
            return new Selection(true, hotbar, equipped, policyScore);
        }
        return new Selection(false, currentSlot, bestStack, policyScore);
    }

    /**
     * Is the candidate pickaxe better for the channel than the best so far? Worst-first: no Silk Touch, then the lower
     * {@link GearValue}, then the more worn one (the first candidate always wins). Best-first (the earlier policy): the lower tier,
     * then the more durable one.
     */
    private static boolean channelBetter(boolean worstFirst, int tier, int remaining, ItemStack stack,
                                         int bestTier, int bestRemaining, ItemStack bestStack) {
        if (bestTier == Integer.MAX_VALUE) {
            return true;
        }
        if (!worstFirst) {
            return tier < bestTier || (tier == bestTier && remaining > bestRemaining);
        }
        boolean silk = GearValue.hasSilkTouch(stack);
        boolean bestSilk = GearValue.hasSilkTouch(bestStack);
        if (silk != bestSilk) {
            return !silk;
        }
        return GearValue.Core.compare(GearValue.toolValue(stack), remaining, GearValue.toolValue(bestStack), bestRemaining) < 0;
    }

    static int channelMinimumTier(int requiredTier) {
        return Math.max(ToolTier.STONE, requiredTier);
    }

    /** Exact tool requested when the channel policy has no usable candidate. */
    public static Item requiredMiningChannelTool(BlockState state) {
        int tier = channelMinimumTier(ToolTier.requiredPickaxeTier(state.getBlock()));
        if (tier >= ToolTier.DIAMOND) {
            return Items.DIAMOND_PICKAXE;
        }
        if (tier >= ToolTier.IRON) {
            return Items.IRON_PICKAXE;
        }
        return Items.STONE_PICKAXE;
    }

    static int channelMaximumTier(int minimumTier, boolean targetOre) {
        return !targetOre && minimumTier == ToolTier.STONE
                ? ToolTier.STONE : ToolTier.NETHERITE;
    }

    private static boolean isBetterCandidate(float candidateScore,
                                             int candidateHandSafety,
                                             float bestScore,
                                             int bestHandSafety) {
        return candidateScore > bestScore + 0.001F
                || Math.abs(candidateScore - bestScore) <= 0.001F
                && candidateHandSafety > bestHandSafety;
    }

    /**
     * A sword or axe that mines at bare-hand speed provides no benefit but still loses durability
     * for every broken block. On a speed tie prefer an executable empty hand, then a non-damageable
     * stack, then another damageable tool; a melee weapon is the last legal soft-block hand.
     */
    private static int softBlockHandSafety(ItemStack stack, BlockState state) {
        if (state.requiresCorrectToolForDrops()) {
            return 0;
        }
        if (stack.isEmpty()) {
            return 3;
        }
        if (!stack.isDamageableItem()) {
            return 2;
        }
        return stack.is(ItemTags.SWORDS) || stack.getItem() instanceof AxeItem ? 0 : 1;
    }

    private static float score(ItemStack stack, BlockState state, boolean swordsMine) {
        if (!swordsMine && stack.is(ItemTags.SWORDS)) {
            return score(ItemStack.EMPTY, state); // a sword is no digging tool: it counts like an empty hand
        }
        return score(stack, state);
    }

    private static float score(ItemStack stack, BlockState state) {
        if (stack.isEmpty()) {
            return state.requiresCorrectToolForDrops() ? 0.001F : 1.0F;
        }
        float speed = stack.getDestroySpeed(state);
        // Blocks that don't require a tool (dirt/sand/gravel/logs, etc.): keep the original behavior, pick the fastest tool (shovel/axe are fastest); unaffected.
        if (!state.requiresCorrectToolForDrops()) {
            return speed;
        }
        // Requires a tool but this tool's tier is insufficient (won't drop loot, e.g. a stone pickaxe on a diamond ore block): fall back to a very low score, only used reluctantly when there's no other choice.
        if (!stack.isCorrectToolForDrops(state)) {
            return Math.max(0.001F, speed * 0.01F);
        }
        // Requires a tool and can mine it: durability-preservation policy -- among tools that can equally mine it, prefer the readily-replenished stone tools (unlimited cobblestone + ample durability),
        // saving the scarce iron/diamond pickaxe durability for ore that genuinely requires a high tier (diamond/gold/redstone ore; when a stone pickaxe can't mine it, it naturally falls into the !suitable branch above and picks iron).
        // Root cause fix: the old logic picked purely by speed -> if iron is available it grabs iron to mine stone/descend hundreds of blocks -> the iron pickaxe wears through -> reaching diamond ore triggers need_better_tool (a regression for real_diamond).
        // Layering: the suitable base score (100) dominates everything; preservationRank (stone > wood/gold > iron > diamond) * 10 is added on top; speed only serves as a small tiebreak within the same tier.
        return 100.0F + preservationRank(stack) * 10.0F + Math.min(speed, 9.9F) * 0.1F;
    }

    // Durability-preservation preference: higher value = higher priority to use. Stone tools are top priority (unlimited cobblestone, an instant replan-and-replace if broken, 131 durability is plenty);
    // wood/gold are next (easy to replenish but low durability); iron/diamond should be preserved the most (scarce, crafting one requires mining + smelting), reserved for high-tier ore that a stone pickaxe can't mine.
    private static int preservationRank(ItemStack stack) {
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (path.startsWith("stone_")) {
            return 5;
        }
        if (path.startsWith("wooden_") || path.startsWith("golden_")) {
            return 4;
        }
        if (path.startsWith("iron_")) {
            return 2;
        }
        if (path.startsWith("diamond_")) {
            return 1;
        }
        if (path.startsWith("netherite_")) {
            return 0;
        }
        return 3; // Non-tiered material tools: neutral middle ground, neither specially preserved nor specially consumed
    }
}
