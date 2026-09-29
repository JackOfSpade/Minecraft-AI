package io.github.zoyluo.minecraftai.mixin;

import baritone.api.utils.accessor.ILootTable;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets Baritone roll a loot table directly to learn what a block drops ({@code BlockOptionalMeta#drops}). */
@Mixin(LootTable.class)
public abstract class BaritoneLootTableMixin implements ILootTable {
    @Invoker
    @Override
    public abstract ObjectArrayList<ItemStack> invokeGetRandomItems(LootContext context);
}
