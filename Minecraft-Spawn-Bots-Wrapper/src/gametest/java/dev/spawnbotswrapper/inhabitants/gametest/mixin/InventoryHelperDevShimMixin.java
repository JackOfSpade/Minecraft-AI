package dev.spawnbotswrapper.inhabitants.gametest.mixin;

import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.reflect.Field;

/**
 * TEST HARNESS ONLY (this source set is never part of the published jar). PvP BOT reaches the private hotbar index of
 * the player inventory by REFLECTION, first under Yarn's name and then under the intermediary name {@code field_7545}.
 * A real profile runs on intermediary names, where the second lookup succeeds; the dev-environment GameTest server runs
 * on Mojang names, where neither exists, so PvP BOT's whole tick dies in {@code InventoryHelper.<clinit>}. This shim
 * only translates those two lookups to the runtime name of the very same vanilla field ({@code selected}), so PvP BOT
 * reads and writes exactly the state it does in game. Nothing else of PvP BOT is altered, and the wrapper under test
 * contains no mixin at all.
 */
@Mixin(targets = "org.stepan1411.pvp_bot.utils.InventoryHelper")
abstract class InventoryHelperDevShimMixin {
    @Redirect(method = "<clinit>", at = @At(value = "INVOKE",
            target = "Ljava/lang/Class;getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;"))
    private static Field harness$lookup(Class<?> owner, String name) throws NoSuchFieldException {
        if (owner == Inventory.class && (name.equals("selectedSlot") || name.equals("field_7545"))) {
            return Inventory.class.getDeclaredField("selected");
        }
        return owner.getDeclaredField(name);
    }
}
