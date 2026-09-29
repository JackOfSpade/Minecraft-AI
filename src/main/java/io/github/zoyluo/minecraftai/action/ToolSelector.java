package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.mining.ToolTier;
import io.github.zoyluo.minecraftai.util.ItemStackUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
        Inventory inventory = player.getInventory();
        int currentSlot = inventory.getSelectedSlot();
        ItemStack currentStack = inventory.getNonEquipmentItems().get(currentSlot);
        float currentScore = score(currentStack, state);
        int bestSlot = currentSlot;
        int bestOffhandSlot = -1;
        ItemStack bestStack = currentStack;
        float bestScore = currentScore;
        int bestHandSafety = softBlockHandSafety(currentStack, state);

        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            ItemStack stack = inventory.getNonEquipmentItems().get(slot);
            // Only an empty hotbar slot can be selected as an executable empty hand. Empty storage
            // slots are not candidates because equipFromSlot deliberately rejects them.
            if (stack.isEmpty() && !Inventory.isHotbarSlot(slot)) {
                continue;
            }
            float candidateScore = score(stack, state);
            int candidateHandSafety = softBlockHandSafety(stack, state);
            if (isBetterCandidate(candidateScore, candidateHandSafety,
                    bestScore, bestHandSafety)) {
                bestScore = candidateScore;
                bestHandSafety = candidateHandSafety;
                bestSlot = slot;
                bestOffhandSlot = -1;
                bestStack = stack;
            }
        }
        ItemStack offHandStack = player.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!offHandStack.isEmpty()) {
            float candidateScore = score(offHandStack, state);
            int candidateHandSafety = softBlockHandSafety(offHandStack, state);
            if (isBetterCandidate(candidateScore, candidateHandSafety,
                    bestScore, bestHandSafety)) {
                bestScore = candidateScore;
                bestHandSafety = candidateHandSafety;
                bestSlot = -1;
                bestOffhandSlot = 0;
                bestStack = offHandStack;
            }
        }

        if (bestOffhandSlot >= 0) {
            int promoted = InventoryAction.promoteOffhandSlot(player, bestOffhandSlot)
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

    /**
     * OreDig channel policy: use the lowest healthy pickaxe tier that can harvest the block, but
     * never go below stone for ordinary rock. Thus stone/deepslate consume renewable stone picks,
     * while diamond/redstone/gold automatically select iron and obsidian selects diamond. Other
     * BlockMiner users keep {@link #equipBestTool} unchanged.
     */
    public static Selection equipMiningChannelTool(AIPlayerEntity player, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) {
            return equipBestTool(player, state);
        }
        Inventory inventory = player.getInventory();
        int currentSlot = inventory.getSelectedSlot();
        int minimumTier = channelMinimumTier(ToolTier.requiredPickaxeTier(state.getBlock()));
        int maximumTier = channelMaximumTier(minimumTier, OreScan.isOreBlock(state.getBlock()));
        int bestSlot = -1;
        int bestOffhandSlot = -1;
        int bestTier = Integer.MAX_VALUE;
        int bestRemaining = -1;
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            ItemStack stack = inventory.getNonEquipmentItems().get(slot);
            int tier = ToolTier.pickaxeTier(stack);
            if (tier < minimumTier || tier > maximumTier || !stack.isCorrectToolForDrops(state)
                    || ItemStackUtil.isNearlyBroken(stack)) {
                continue;
            }
            int remaining = stack.isDamageableItem()
                    ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
            if (tier < bestTier || (tier == bestTier && remaining > bestRemaining)) {
                bestTier = tier;
                bestRemaining = remaining;
                bestSlot = slot;
                bestOffhandSlot = -1;
            }
        }
        ItemStack offHandStack = player.getItemBySlot(EquipmentSlot.OFFHAND);
        int offHandTier = ToolTier.pickaxeTier(offHandStack);
        if (offHandTier >= minimumTier && offHandTier <= maximumTier && offHandStack.isCorrectToolForDrops(state)
                && !ItemStackUtil.isNearlyBroken(offHandStack)) {
            int remaining = offHandStack.isDamageableItem()
                    ? offHandStack.getMaxDamage() - offHandStack.getDamageValue() : Integer.MAX_VALUE;
            if (offHandTier < bestTier || (offHandTier == bestTier && remaining > bestRemaining)) {
                bestTier = offHandTier;
                bestRemaining = remaining;
                bestSlot = -1;
                bestOffhandSlot = 0;
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

    private static float score(ItemStack stack, BlockState state) {
        if (stack.isEmpty()) {
            return state.requiresCorrectToolForDrops() ? 0.001F : 1.0F;
        }
        float speed = stack.getDestroySpeed(state);
        if (ItemStackUtil.isNearlyBroken(stack)) {
            return 0.001F; // About to break -> don't use it, to avoid it breaking mid-swing
        }
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
