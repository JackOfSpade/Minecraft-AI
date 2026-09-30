package io.github.zoyluo.minecraftai.baritone;

import baritone.api.utils.HostEnvironment;
import io.github.zoyluo.minecraftai.action.ToolSelector;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The mod's tool policy as Baritone's cost model sees it. A bot breaks with the tool {@link ToolSelector} equips when a break
 * starts ({@code ServerPlayerController#clickBlock}): the worst tool (lowest {@code GearValue}) that can harvest the block, the worst faster-than-hand
 * tool for soft blocks, never a sword; a worn tool is used until it breaks. Upstream's cost model instead prices every break with
 * the fastest tool on the hotbar (an iron pickaxe on stone), so a break would run longer than planned and a movement could time
 * out. This class answers "which stack will break this block" from the same pure chooser, on a private copy of the inventory that
 * is taken on the server thread when a cost model is created and read by the search on its worker thread.
 */
final class BaritoneToolPolicy implements HostEnvironment.ToolPolicy {
    static final BaritoneToolPolicy INSTANCE = new BaritoneToolPolicy();

    /** What a bot carries at the moment a cost model is created. Immutable once built. */
    record Snapshot(List<ItemStack> main, ItemStack offhand, int selected) {
    }

    private BaritoneToolPolicy() {
    }

    @Override
    public Object snapshot(Player player) {
        List<ItemStack> main = new ArrayList<>(player.getInventory().getNonEquipmentItems().size());
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            main.add(stack.copy());
        }
        return new Snapshot(List.copyOf(main), player.getItemBySlot(EquipmentSlot.OFFHAND).copy(), player.getInventory().getSelectedSlot());
    }

    @Override
    public ItemStack toolFor(Object snapshot, BlockState state) {
        if (!(snapshot instanceof Snapshot carried) || state.isAir()) {
            return null;
        }
        return ToolSelector.choose(carried.main(), carried.selected(), carried.offhand(), state, false).stack();
    }
}
