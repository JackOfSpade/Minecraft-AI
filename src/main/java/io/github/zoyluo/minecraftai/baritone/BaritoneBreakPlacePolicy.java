package io.github.zoyluo.minecraftai.baritone;

import baritone.api.BaritoneAPI;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The one strict-survival rule set every world change of Baritone goes through: {@link ServerPlayerController} asks it before
 * every break, every click on a block (placing a block, opening a door), every use of an item without a block and every
 * inventory move, and {@link ServerPlayerContext} asks it before a process that scans the world may start. A refusal is a typed
 * reason, an entry in {@link BaritoneRefusals} and a {@code baritone_refused} line in the bot's log; the action does not happen.
 *
 * <p><b>Breaking.</b> A bot breaks natural terrain and nothing else: stone, dirt, sand, gravel, ores, leaves and the small plants
 * that grow in the way ({@link #naturalTerrain}). Everything a player made or put down (planks, glass, wool, bricks, iron doors,
 * fences ...), everything that stores or does something (any block with a block entity: chests, furnaces, beds, signs, spawners,
 * banners, barrels, hoppers ...; crafting tables and the like), every fluid, every dangerous block and bedrock stays. This is the
 * same idea as the legacy digger's whitelist ({@code NeighborEnumerator.isMineable}) with the natural leaves and plants added; the
 * bot has no way to tell a placed cobblestone from a natural one, exactly like a player. The block must also be observable
 * ({@link ObservableWorldQuery}: the strict-survival rays; a privileged profile sees through), which a block Baritone is
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

    /** Things whose use is the point of a right click, so a placement against them would not place anything. */
    private static final Set<Block> USE_INTERACTIVE = Set.of(
            Blocks.CRAFTING_TABLE, Blocks.CARTOGRAPHY_TABLE, Blocks.SMITHING_TABLE, Blocks.FLETCHING_TABLE, Blocks.LOOM,
            Blocks.STONECUTTER, Blocks.GRINDSTONE, Blocks.LEVER, Blocks.NOTE_BLOCK, Blocks.REPEATER, Blocks.COMPARATOR,
            Blocks.DAYLIGHT_DETECTOR, Blocks.COMPOSTER, Blocks.CAULDRON, Blocks.WATER_CAULDRON, Blocks.LAVA_CAULDRON,
            Blocks.POWDER_SNOW_CAULDRON, Blocks.RESPAWN_ANCHOR, Blocks.DRAGON_EGG, Blocks.CAKE);

    /** Verdict cache per block (the rule reads the block's default state only); "" = breakable, otherwise the reason. */
    private static final Map<Block, String> BREAK_VERDICT = new ConcurrentHashMap<>();

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
     * Block-level rule, safe on any thread: null if a bot may break blocks of this kind, else why not. Reads the block's default
     * state only, so it is a property of the kind of block (the cost model asks it for every block it looks at).
     */
    public static String breakDenialOf(Block block) {
        String verdict = BREAK_VERDICT.computeIfAbsent(block, BaritoneBreakPlacePolicy::computeBreakDenial);
        return verdict.isEmpty() ? null : verdict;
    }

    public static boolean isBreakable(BlockState state) {
        return state.isAir() || breakDenialOf(state.getBlock()) == null;
    }

    private static String computeBreakDenial(Block block) {
        BlockState state = block.defaultBlockState();
        if (state.isAir()) {
            return "";
        }
        if (state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) < 0.0F) {
            return "unbreakable";
        }
        if (block instanceof LiquidBlock || !state.getFluidState().isEmpty()) {
            return "fluid";
        }
        if (state.hasBlockEntity()) {
            return "block_entity";
        }
        if (state.is(BlockTags.BEDS) || USE_INTERACTIVE.contains(block) || state.is(BlockTags.ANVIL)) {
            return "protected_block";
        }
        if (Standability.isDangerous(state)) {
            return "dangerous_block";
        }
        return naturalTerrain(state) ? "" : "not_natural_terrain";
    }

    /** Terrain a world generator puts down and a bot may dig through. */
    private static boolean naturalTerrain(BlockState state) {
        Block block = state.getBlock();
        return state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(BlockTags.STONE_ORE_REPLACEABLES)
                || state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)
                || state.is(BlockTags.DIRT)
                || state.is(BlockTags.SAND)
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.REPLACEABLE)
                || block == Blocks.COBBLESTONE
                || block == Blocks.GRAVEL
                || block == Blocks.CLAY
                || block == Blocks.SNOW_BLOCK
                || block == Blocks.END_STONE
                || block == Blocks.CALCITE
                || block == Blocks.DRIPSTONE_BLOCK
                || OreScan.isOreBlock(block);
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
        if (isOpenable(support)) {
            return Decision.ALLOWED; // a wooden door, a trapdoor, a fence gate: the click opens it
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

    private static boolean isOpenable(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof DoorBlock door) {
            return door.type().canOpenByHand();
        }
        if (block instanceof TrapDoorBlock) {
            return block != Blocks.IRON_TRAPDOOR;
        }
        return block instanceof FenceGateBlock;
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

    /** The blocks a bot must not break, as a list Baritone can query ({@code contains}) and iterate. */
    private static final class DeniedBlocks extends AbstractList<Block> {
        private volatile List<Block> all;

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
            List<Block> list = all;
            if (list == null) {
                List<Block> built = new ArrayList<>();
                for (Block block : BuiltInRegistries.BLOCK) {
                    if (breakDenialOf(block) != null) {
                        built.add(block);
                    }
                }
                all = list = List.copyOf(built);
            }
            return list;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    static Decision refuse(AIPlayerEntity bot, BaritoneRefusals.Op op, BlockPos pos, String reason, String detail) {
        BaritoneRefusals.record(bot, op, pos, reason, detail);
        return Decision.refused(reason);
    }
}
