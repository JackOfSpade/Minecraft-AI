package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 挖掘式导航:当纯寻路(A*)走不通(被墙 / 复杂地形 / 自挖隧道 / SEARCH_LIMIT)时,朝目标"挖一格走一格"硬开一条路。
 *
 * 这是"AI 玩家手里有镐,被挡住就该挖开走过去"这一本该有的能力——根治反复出现的"被困出不去"
 *(实测:丛林 + 自挖隧道里 move 寻路 SEARCH_LIMIT,bot 卡死、只能靠大脑一格格手动 mine_block 直到耗尽轮次)。
 *
 * 纯函数 {@link #stepToward}(朝目标的下一格)+ 有状态 {@link #digStep}(调用方持有 {@link BlockMiner});全程主线程(G2)。
 */
public final class DigNav {
    private DigNav() {
    }

    /**
     * 朝 target 挖掘式前进一格:清出朝向格(脚位+头位)→ 已通则走进去(更低则主动下沉,bot 无被动重力)。
     * 返回 true=本 tick 有进展(在挖或已迈步);false=该方向受阻(如相邻岩浆),调用方应改道或失败。
     */
    public static boolean digStep(AIPlayerEntity bot, BlockMiner miner, BlockPos target) {
        ServerWorld world = bot.getEntityWorld();
        BlockPos feet = bot.getBlockPos();
        BlockPos step = stepToward(feet, target);
        if (step == null) {
            return false;
        }
        if (adjacentHazardFluid(bot, step)) {
            return false; // 朝向格挨着已观测到的危险流体(岩浆/水) → 不挖,交还调用方
        }
        BlockPos solid = firstSolid(world, step, step.up());
        if (solid == null) {
            // 朝向格已是空气 → 迈进去(更低则下沉,平/高则走)。
            miner.cancel(bot);
            if (step.getY() < feet.getY()) {
                bot.getActionPack().descendInto(step);
            } else {
                bot.getActionPack().startWalkTo(step.toCenterPos());
            }
            return true;
        }
        BlockMiner.Status st = miner.target() != null && miner.target().equals(solid)
                ? miner.tick(bot)
                : begin(bot, miner, solid);
        // 反应式复查(与 NeighborEnumerator/PathExecutor 挖穿同款):solid 刚被挖成真实空气,
        // 它的邻位第一次对 bot 的眼睛真正可查。挖前 adjacentHazardFluid 拒的是"已观测"危险,
        // 未观测的隐藏邻位诚实放行;这里补上挖开瞬间的复查兜底,而不是盲目继续挖/走进去。
        if (st == BlockMiner.Status.DONE && adjacentHazardFluid(bot, solid)) {
            return false; // 挖开即暴露已观测到的危险流体 → 交还调用方改道,同前置检查的约定
        }
        return st == BlockMiner.Status.DONE || st == BlockMiner.Status.MINING;
    }

    private static BlockMiner.Status begin(AIPlayerEntity bot, BlockMiner miner, BlockPos pos) {
        miner.begin(bot, pos);
        return miner.tick(bot);
    }

    /** 朝目标的下一格:竖直优先(目标更低且水平已对齐则下挖),否则较大水平分量(避免对角穿墙角)。 */
    public static BlockPos stepToward(BlockPos from, BlockPos target) {
        int dy = target.getY() - from.getY();
        int dx = target.getX() - from.getX();
        int dz = target.getZ() - from.getZ();
        if (dy < 0 && Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
            return from.down();
        }
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return from.offset(dx > 0 ? Direction.EAST : Direction.WEST);
        }
        if (dz != 0) {
            return from.offset(dz > 0 ? Direction.SOUTH : Direction.NORTH);
        }
        if (dy < 0) {
            return from.down();
        }
        if (dy > 0) {
            return from.up();
        }
        return null;
    }

    // a、b 中第一个需要挖开的(非空气)方块。
    private static BlockPos firstSolid(ServerWorld world, BlockPos a, BlockPos b) {
        if (!world.getBlockState(a).isAir()) {
            return a.toImmutable();
        }
        if (!world.getBlockState(b).isAir()) {
            return b.toImmutable();
        }
        return null;
    }

    // 门控版(取代原始 adjacentLava):pos 本身贴身可见,如实读取合法(同 Standability 对物理下一步
    // 的处理);但 pos 的六邻位可能仍藏在未挖的实心方块后面,只有已经真被 bot 观测到才算危险——
    // 未观测的隐藏邻位诚实放行,交给挖开瞬间的反应式复查(见 digStep 内的 DONE 分支)兜底,而不是
    // 像旧版那样无门控直读邻位流体状态(能透过没挖过的岩石"看见"岩浆/水)。
    private static boolean adjacentHazardFluid(AIPlayerEntity bot, BlockPos pos) {
        FluidState here = bot.getEntityWorld().getFluidState(pos);
        if (here.isIn(FluidTags.LAVA) || here.isIn(FluidTags.WATER)) {
            return true;
        }
        for (Direction d : Direction.values()) {
            if (isObservedHazardFluid(bot, pos.offset(d))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isObservedHazardFluid(AIPlayerEntity bot, BlockPos pos) {
        return io.github.zoyluo.aibot.mining.OreScan.observeDangerFluid(bot, pos)
                == io.github.zoyluo.aibot.mining.OreScan.Observation.OBSERVED_PRESENT;
    }
}
