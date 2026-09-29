package io.github.zoyluo.minecraftai.mixin;

import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reads the (protected) {@code BlockSetType} of a trapdoor, so a hand-openable one is told from an iron one by the same flag doors use. */
@Mixin(TrapDoorBlock.class)
public interface TrapDoorBlockTypeInvokerMixin {
    @Invoker("getType")
    BlockSetType minecraftai$type();
}
