package io.github.zoyluo.minecraftai.goal;

import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.task.BlueprintSchema;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

public final class StructureVerifier {
    private StructureVerifier() {
    }

    public static StructureReport verify(ServerLevel world,
                                         BlueprintSchema blueprint,
                                         BlockPos anchor,
                                         int placed,
                                         int skipped) {
        if (blueprint == null || anchor == null || blueprint.placements() == null) {
            return new StructureReport("", 0, 0, placed, skipped, 0);
        }
        int matched = 0;
        int mismatched = 0;
        for (BlueprintSchema.BlockPlacement placement : blueprint.placements()) {
            if (matches(world, anchor, placement)) {
                matched++;
            } else {
                mismatched++;
            }
        }
        return new StructureReport(compact(anchor), blueprint.placements().size(), matched, placed, skipped, mismatched);
    }

    public static boolean matches(ServerLevel world,
                                  BlockPos anchor,
                                  BlueprintSchema.BlockPlacement placement) {
        if (placement == null || placement.blockId() == null) {
            return false;
        }
        Identifier expectedId;
        try {
            expectedId = Identifier.parse(placement.blockId());
        } catch (RuntimeException exception) {
            return false;
        }
        if (world == null || anchor == null) {
            return false;
        }
        BlockPos pos = anchor.offset(placement.dx(), placement.dy(), placement.dz());
        var state = world.getBlockState(pos);
        if ("minecraft:air".equals(placement.blockId())) {
            return state.isAir();
        }
        if (placement.palette() != null && !placement.palette().isBlank()) {
            return MaterialPalette.matchesBlock(state, placement.palette());
        }
        Block expected = BuiltInRegistries.BLOCK.getOptional(expectedId).orElse(null);
        return expected != null && state.is(expected);
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
