package io.github.zoyluo.minecraftai.testsupport;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** A map-backed {@link BlockGetter}: air everywhere except the cells set, enough for {@code BlockGetter.clip}. */
public final class FakeLevel implements BlockGetter {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final Map<Long, BlockState> cells = new HashMap<>();

    public FakeLevel set(int x, int y, int z, BlockState state) {
        cells.put(BlockPos.asLong(x, y, z), state);
        return this;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return cells.getOrDefault(pos.asLong(), AIR);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return 384;
    }

    @Override
    public int getMinY() {
        return -64;
    }
}
