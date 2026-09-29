package io.github.zoyluo.minecraftai.baritone;

import baritone.api.BaritoneAPI;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mining.BreakRule;
import io.github.zoyluo.minecraftai.mining.BreakVerdictCache;
import io.github.zoyluo.minecraftai.mixin.TrapDoorBlockTypeInvokerMixin;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The one strict-survival rule set every world change of Baritone goes through: {@link ServerPlayerController} asks it before
 * every break, every click on a block (placing a block, opening a door), every use of an item without a block and every
 * inventory move, and {@link ServerPlayerContext} asks it before a process that scans the world may start. A refusal is a typed
 * reason, an entry in {@link BaritoneRefusals} and a {@code baritone_refused} line in the bot's log; the action does not happen.
 *
 * <p><b>Breaking.</b> A bot breaks natural terrain and nothing else, by the mod-wide {@link BreakRule} that the legacy diggers
 * ({@code OreDigTask}, {@code NeighborEnumerator}, {@code FollowDigOut}) use too: a tag-driven whitelist of what a world generator
 * puts down (stone of every kind, soil, sand, terracotta, ice, ores, leaves and the small plants that grow in the way ...), with
 * everything a player or a structure made or put down (planks, glass, wool, bricks, slabs, doors ...), everything that stores or
 * does something (any block with a block entity, crafting tables and the like), every fluid, every dangerous block and bedrock
 * staying. The bot has no way to tell a placed cobblestone from a natural one, exactly like a player. The block must also be
 * observable ({@link ObservableWorldQuery}: the strict-survival rays; a privileged profile sees through), which a block Baritone is
 * looking at always is, and the bot's break permission ({@link BaritonePolicy}) must allow it.</p>
 *
 * <p><b>Planning.</b> {@link #installPlanningRules} hands the block-level part of the same rule to Baritone's cost model
 * ({@code Settings#blocksToDisallowBreaking}): a route through a protected block costs infinity, so Baritone plans around it
 * (or reports no path) instead of planning through it and being vetoed at execution. The position-level parts (observability,
 * permission) exist only here, because a search runs on a worker thread against a snapshot and must not ray-cast the live world.</p>
 *
 * <p><b>Placing.</b> Only the throwaway blocks of Baritone's settings ({@code acceptableThrowawayItems}: dirt, cobblestone,
 * netherrack, stone) are put down, only when the bot's permission allows placing, only against a support face that passes the
 * legacy perception proof ({@link BuildAction#supportFaceRefusal}: in reach, in perception range, and a ray from the eye strikes
 * exactly that face), and never against something a click would use instead (a chest, a bed, a crafting table, a lever ...).
 * The same click may open a wooden door, a trapdoor or a fence gate; that passes the same proof and nothing else is used.</p>
 *
 * <p><b>Item use without a block</b> ({@code processRightClick}: buckets, ender pearls, food, potions ...) is refused unless the
 * item is in {@link #USE_ITEM_ALLOWLIST}, which is empty: the settings the bots run with (no water-bucket fall, no elytra) need
 * nothing. <b>Inventory moves</b> ({@code windowClick}) are only the hotbar swap of the bot's own inventory, and only when
 * Baritone's {@code allowInventory} is on, which {@link BaritoneSettings} keeps off.</p>
 *
 * <p><b>Scanning processes</b> (mine, get-to-block, farm, explore, build) choose their targets by scanning loaded chunks, that
 * is, they know where an ore is without having seen it. They may only start when the bot holds the hidden-scan privilege
 * ({@link PrivilegedCapability#HIDDEN_BLOCK_SCAN}, never granted in strict survival); Baritone only ever receives coordinate goals
 * ({@link BaritoneGoals}) that the caller has already proven observable.</p>
 */
public final class BaritoneBreakPlacePolicy {
    /** Items {@code processRightClick} may use with no block. Empty: nothing the bots' Baritone settings do needs one. */
    public static final Set<Item> USE_ITEM_ALLOWLIST = Set.of();

    /** The processes that scan loaded chunks for targets; see {@link #allowScanningProcess}. */
    public static final Set<String> SCANNING_PROCESSES = Set.of("mine", "get_to_block", "farm", "explore", "build");

    /** Things whose use is the point of a right click, so a placement against them would not place anything (shared with the break rule). */
    private static final Set<Block> USE_INTERACTIVE = BreakRule.USE_INTERACTIVE;

    private BaritoneBreakPlacePolicy() {
    }

    /** A decision: {@code reason} is null when allowed. */
    public record Decision(boolean allowed, String reason) {
        static final Decision ALLOWED = new Decision(true, null);

        static Decision refused(String reason) {
            return new Decision(false, reason);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Breaking
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Block-level rule, safe on any thread: null if a bot may break blocks of this kind, else why not. It is the mod-wide
     * {@link BreakRule}, shared with the legacy diggers, and reads the block's default
     * state only, so it is a property of the kind of block (the cost model asks it for every block it looks at).
     */
    public static String breakDenialOf(Block block) {
        return BreakRule.denialOf(block);
    }

    public static boolean isBreakable(BlockState state) {
        return state.isAir() || breakDenialOf(state.getBlock()) == null;
    }

    /**
     * Whether the bot may break the block at {@code pos} right now (server thread). A cell that is already empty is allowed
     * (there is nothing to break).
     */
    public static Decision checkBreak(AIPlayerEntity bot, BlockPos pos) {
        if (!BaritoneRegistry.INSTANCE.policy(bot).allowBreak()) {
            return refuse(bot, BaritoneRefusals.Op.BREAK, pos, "policy_no_break", "-");
        }
        BlockState state = bot.level().getBlockState(pos);
        if (state.isAir()) {
            return Decision.ALLOWED;
        }
        String block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String denial = breakDenialOf(state.getBlock());
        if (denial != null) {
            return refuse(bot, BaritoneRefusals.Op.BREAK, pos, denial, block);
        }
        if (bot.level().getBlockEntity(pos) != null) {
            return refuse(bot, BaritoneRefusals.Op.BREAK, pos, "block_entity", block);
        }
        if (!observable(bot, pos)) {
            return refuse(bot, BaritoneRefusals.Op.BREAK, pos, "not_observable", block);
        }
        return Decision.ALLOWED;
    }

    private static boolean observable(AIPlayerEntity bot, BlockPos pos) {
        return ObservableWorldQuery.canObserveBlock(bot, pos) || ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, pos);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Placing and clicking blocks
    // ---------------------------------------------------------------------------------------------------------------

    /** Whether a right click on the block of {@code hit} with the item in {@code hand} may go ahead (server thread). */
    public static Decision checkClickBlock(AIPlayerEntity bot, BlockHitResult hit, InteractionHand hand) {
        BlockPos against = hit.getBlockPos();
        BlockState support = bot.level().getBlockState(against);
        ItemStack held = bot.getItemInHand(hand);
        String detail = BuiltInRegistries.ITEM.getKey(held.getItem()) + "@" + BuiltInRegistries.BLOCK.getKey(support.getBlock());
        String seen = BuildAction.supportFaceRefusal(bot, hit);
        if (seen != null) {
            return refuse(bot, BaritoneRefusals.Op.PLACE, against, seen, detail);
        }
        if (opensOnClick(support, itemUseWinsOverBlock(bot))) {
            return Decision.ALLOWED; // a wooden door, a trapdoor, a fence gate: the click opens it, whatever the hand holds
        }
        if (!(held.getItem() instanceof BlockItem)) {
            return refuse(bot, BaritoneRefusals.Op.PLACE, against, "not_a_block_item", detail);
        }
        if (!BaritoneAPI.getSettings().acceptableThrowawayItems.value.contains(held.getItem())) {
            return refuse(bot, BaritoneRefusals.Op.PLACE, against, "item_not_allowed", detail);
        }
        if (!BaritoneRegistry.INSTANCE.policy(bot).allowPlace()) {
            return refuse(bot, BaritoneRefusals.Op.PLACE, against, "policy_no_place", detail);
        }
        if (isUseInteractive(support)) {
            return refuse(bot, BaritoneRefusals.Op.PLACE, against, "support_is_interactive", detail);
        }
        return Decision.ALLOWED;
    }

    /**
     * Whether a right click on {@code state} opens it: a door or trapdoor whose {@code BlockSetType} lets a hand open it (never
     * iron), or a fence gate, unless {@code itemUseWins}. Vanilla ({@code ServerPlayerGameMode#useItemOn}) lets the item win over
     * the block only for a player who is sneaking <em>and</em> holds something in either hand; such a click uses the item on the
     * block, which for a non-block item would be an item use the policy never allowed, so it gets no open allowance and falls
     * through to the placement rules, which refuse it (a door, trapdoor or gate is never a support for a placement). A sneaking
     * player with both hands empty still opens the door, exactly like in vanilla.
     */
    static boolean opensOnClick(BlockState state, boolean itemUseWins) {
        if (itemUseWins) {
            return false;
        }
        Block block = state.getBlock();
        if (block instanceof DoorBlock door) {
            return door.type().canOpenByHand();
        }
        if (block instanceof TrapDoorBlock trapDoor) {
            return ((TrapDoorBlockTypeInvokerMixin) trapDoor).minecraftai$type().canOpenByHand();
        }
        return block instanceof FenceGateBlock;
    }

    /** Vanilla's {@code isSecondaryUseActive() && (main hand or off hand not empty)}: the item is used on the block instead of the block. */
    static boolean itemUseWinsOverBlock(AIPlayerEntity bot) {
        return bot.isSecondaryUseActive() && (!bot.getMainHandItem().isEmpty() || !bot.getOffhandItem().isEmpty());
    }

    private static boolean isUseInteractive(BlockState state) {
        return state.hasBlockEntity() || USE_INTERACTIVE.contains(state.getBlock())
                || state.is(BlockTags.BEDS) || state.is(BlockTags.ANVIL) || state.is(BlockTags.BUTTONS)
                || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.getBlock() instanceof FenceGateBlock;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Item use, inventory, scanning processes
    // ---------------------------------------------------------------------------------------------------------------

    /** Whether the item in {@code hand} may be used with no block at all ({@code processRightClick}). */
    public static Decision checkUseItem(AIPlayerEntity bot, InteractionHand hand) {
        Item item = bot.getItemInHand(hand).getItem();
        if (USE_ITEM_ALLOWLIST.contains(item)) {
            return Decision.ALLOWED;
        }
        return refuse(bot, BaritoneRefusals.Op.USE_ITEM, null, "item_not_allowed", BuiltInRegistries.ITEM.getKey(item).toString());
    }

    /** Whether Baritone may make this container click: only a hotbar swap in the bot's own inventory, and only if it is switched on. */
    public static Decision checkWindowClick(AIPlayerEntity bot, int windowId, int slotId, int button, ClickType type) {
        String detail = type + ":" + slotId + "/" + button;
        if (!BaritoneAPI.getSettings().allowInventory.value) {
            return refuse(bot, BaritoneRefusals.Op.INVENTORY, null, "inventory_moves_off", detail);
        }
        boolean own = windowId == bot.inventoryMenu.containerId && bot.containerMenu == bot.inventoryMenu;
        boolean swap = type == ClickType.SWAP && button >= 0 && button <= 8 && slotId >= 9 && slotId <= 44;
        if (!own || !swap) {
            return refuse(bot, BaritoneRefusals.Op.INVENTORY, null, "not_a_hotbar_swap", detail);
        }
        return Decision.ALLOWED;
    }

    /**
     * Whether a process that finds its targets by scanning the loaded world may start for the bot: only with the hidden-scan
     * privilege (a strict-survival bot never has it). Logged and recorded when refused.
     */
    public static boolean allowScanningProcess(AIPlayerEntity bot, String process) {
        if (!SCANNING_PROCESSES.contains(process)) {
            return true;
        }
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "baritone_scan_process:" + process).allowed()) {
            return true;
        }
        refuse(bot, BaritoneRefusals.Op.SCAN_PROCESS, null, "scan_process_refused", process);
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Planning
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Installs the block-level break rule into Baritone's cost model: {@code Settings#blocksToDisallowBreaking} becomes a view
     * that contains every block {@link #breakDenialOf} denies, so no search ever plans through one. A hash-cached view rather
     * than a list of a thousand blocks, because the cost model asks it for every block of every node.
     */
    static void installPlanningRules() {
        BaritoneAPI.getSettings().blocksToDisallowBreaking.value = new DeniedBlocks();
    }

    /**
     * The blocks a bot must not break, as a list Baritone can query ({@code contains}) and iterate. The iterable snapshot is tied
     * to the verdict cache's generation: after a tag reload it is built again instead of listing the old tags' verdicts.
     */
    private static final class DeniedBlocks extends AbstractList<Block> {
        private record Snapshot(int generation, List<Block> blocks) {
        }

        private volatile Snapshot snapshot;

        @Override
        public boolean contains(Object o) {
            return o instanceof Block block && breakDenialOf(block) != null;
        }

        @Override
        public Block get(int index) {
            return all().get(index);
        }

        @Override
        public int size() {
            return all().size();
        }

        private List<Block> all() {
            int generation = BreakVerdictCache.BLOCKS.generation();
            Snapshot current = snapshot;
            if (current == null || current.generation() != generation) {
                List<Block> built = new ArrayList<>();
                for (Block block : BuiltInRegistries.BLOCK) {
                    if (breakDenialOf(block) != null) {
                        built.add(block);
                    }
                }
                current = new Snapshot(generation, List.copyOf(built)); // the generation read BEFORE the build: a reload during it makes this stale
                snapshot = current;
            }
            return current.blocks();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    static Decision refuse(AIPlayerEntity bot, BaritoneRefusals.Op op, BlockPos pos, String reason, String detail) {
        BaritoneRefusals.record(bot, op, pos, reason, detail);
        return Decision.refused(reason);
    }
}
