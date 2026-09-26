package io.github.zoyluo.aibot.pathfinding;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.mining.OreScan;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class NeighborEnumerator {
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH,
            Direction.EAST,
            Direction.SOUTH,
            Direction.WEST
    };

    private final AIPlayerEntity bot;
    private final boolean canPillar;
    private final boolean allowDig;
    private BlockPos pathGoal; // 终点格:岩浆预检豁免用(终点贴岩浆由任务层封堵处理,不该让唯一入口无解)
    // digEnterable()/adjacentHazardFluid() 每 tick 内会被同一批候选格反复问到同一个邻位(挖穿泛洪
    // 时尤其明显),而 OreScan.observeDangerFluid 的可观测性判定要发真实射线,不能像 Standability
    // 那样全局缓存(结果依赖 bot 的实时眼位/朝向)。本实例只服务单次 findPath() 调用,搜索期间世界
    // 与 bot 位置都不变,按格记忆结果是安全的,把重复射线开销降到"每个格子最多问一次"。
    private final Map<BlockPos, OreScan.Observation> hazardObservationCache = new HashMap<>();

    public NeighborEnumerator(AIPlayerEntity bot) {
        this(bot, false, true);
    }

    // NAV-9:canPillar=true 时允许"垫方块上升"邻接(仅当 bot 背包有可放置方块时由 A* 传入)。
    public NeighborEnumerator(AIPlayerEntity bot, boolean canPillar) {
        this(bot, canPillar, true);
    }

    // NAV-OPT:allowDig=false 时**禁用 DIG_THROUGH 邻居**——只在空气格上做"纯步行"搜索。
    // 用于两阶段寻路的第一阶段:绝大多数移动靠纯步行即可达,搜索空间小(只空气格)、收敛快;
    // 而启用挖穿会把每个相邻实心方块都当邻居,使搜索退化成"3D 体积扩散",被困/地下时极易撑爆到
    // SEARCH_LIMIT(实测 5 格距离的 move 都 SEARCH_LIMIT 的机制根因)。纯步行无解再开第二阶段挖穿。
    //
    // bot 仅用于 digEnterable() 的岩浆/水观测门控(可为 null——没有 bot 就没法证明"已观测",
    // adjacentHazardFluid 会诚实地把每个邻位都判 UNKNOWN 并放行,而不是退化回未门控的原始读取)。
    public NeighborEnumerator(AIPlayerEntity bot, boolean canPillar, boolean allowDig) {
        this.bot = bot;
        this.canPillar = canPillar;
        this.allowDig = allowDig;
    }

    public void setPathGoal(BlockPos goal) {
        this.pathGoal = goal;
    }

    public List<NeighborCandidate> getNeighbors(BlockPos current, ServerWorld world) {
        List<NeighborCandidate> result = new ArrayList<>(HORIZONTAL.length);
        for (Direction direction : HORIZONTAL) {
            BlockPos target = current.offset(direction);
            if (Standability.isStandable(world, target)) {
                result.add(new NeighborCandidate(target, MoveType.WALK, 0));
                continue;
            }

            BlockPos jumpTarget = target.up();
            if (canJumpOnto(world, current, target) && Standability.isStandable(world, jumpTarget)) {
                result.add(new NeighborCandidate(jumpTarget, MoveType.JUMP_UP, 0));
                continue;
            }

            NeighborCandidate drop = findDrop(world, target);
            if (drop != null) {
                result.add(drop);
                continue;
            }

            // NAV-BRIDGE:水平搭桥跨缺口。target 走不了(非可站)、findDrop 也没找到安全落点(缺口太深/
            // 悬空太远)、但 target 与其上方确实是真开阔空气(不是墙,只是没有地板)——在 target 正下方
            // 垫一块再走过去。与 addPillar 共用同一个 canPillar 闸门(两者都要消耗背包里的方块)。
            if (canPillar && bridgeable(world, target)) {
                result.add(new NeighborCandidate(target, MoveType.BRIDGE, 0));
                continue;
            }

            if (allowDig && digEnterable(world, target)) {
                result.add(new NeighborCandidate(target, MoveType.DIG_THROUGH, 0));
            }
            // 斜上挖登(DIG 垂直分量之上行):目标=邻位高一格,挖开其脚头两格后跳进去。
            // 仅当自己头顶跳跃空间已空才生成(执行器只挖目标两格,不清自己头顶)——坡面/露天爬坡够用,
            // 全封闭竖井上行交给 pillar。治 geo_slope:坡体内矿(高 3 格)水平 DIG 永远够不到。
            BlockPos upTarget = target.up();
            if (allowDig && digEnterable(world, upTarget) && collisionEmpty(world, current.up(2))) {
                result.add(new NeighborCandidate(upTarget, MoveType.DIG_THROUGH, 0));
            }
        }
        // 垂直向下挖落(DIG 垂直分量之下行):挖开脚下一格掉下去站稳。治 geo_deep/埋矿族:
        // 矿在正下方若干格,水平 DIG 在本层泛洪永远够不到(实测 ore_dig_buried/deep 同源)。
        if (allowDig) {
            BlockPos below = current.down();
            if (isMineable(world, below) && !collisionEmpty(world, below.down())) {
                result.add(new NeighborCandidate(below, MoveType.DIG_THROUGH, 0));
            }
        }
        addDiagonals(current, world, result);
        addPillar(current, world, result);
        return result;
    }

    // NAV-3:同高对角移动。仅当目标格可站、且两个正交相邻格都"可穿过"(不切墙角)时才允许。
    private static void addDiagonals(BlockPos current, ServerWorld world, List<NeighborCandidate> result) {
        Direction[][] pairs = {
                {Direction.NORTH, Direction.EAST},
                {Direction.NORTH, Direction.WEST},
                {Direction.SOUTH, Direction.EAST},
                {Direction.SOUTH, Direction.WEST}
        };
        for (Direction[] pair : pairs) {
            BlockPos diag = current.offset(pair[0]).offset(pair[1]);
            if (!Standability.isStandable(world, diag)) {
                continue;
            }
            if (!passableColumn(world, diag)) {
                continue;
            }
            if (!passableColumn(world, current.offset(pair[0])) || !passableColumn(world, current.offset(pair[1]))) {
                continue;
            }
            result.add(new NeighborCandidate(diag, MoveType.DIAGONAL, 0));
        }
    }

    // NAV-9:垫方块上升一格(原地)。bot 会在脚下放方块并跳上去。需要头顶两格净空。
    private void addPillar(BlockPos current, ServerWorld world, List<NeighborCandidate> result) {
        if (!canPillar) {
            return;
        }
        BlockPos up1 = current.up();
        BlockPos up2 = current.up(2);
        // up1 = 新脚位(当前头位,应为空);up2 = 新头位,需净空
        if (collisionEmpty(world, up1) && collisionEmpty(world, up2) && !Standability.isDangerous(world.getBlockState(up1))) {
            result.add(new NeighborCandidate(up1, MoveType.PILLAR_UP, 0));
        }
    }

    // NAV-BRIDGE:目标格与其上方都是真开阔空气(collision 全空)才算"缺口",不是被实心墙挡住够不着顶。
    private static boolean bridgeable(ServerWorld world, BlockPos target) {
        return collisionEmpty(world, target) && collisionEmpty(world, target.up());
    }

    private static boolean collisionEmpty(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private static boolean passableColumn(ServerWorld world, BlockPos feet) {
        return collisionEmpty(world, feet) && collisionEmpty(world, feet.up());
    }

    private static boolean canJumpFrom(ServerWorld world, BlockPos current) {
        return collisionEmpty(world, current.up()) && collisionEmpty(world, current.up(2));
    }

    private static boolean canJumpOnto(ServerWorld world, BlockPos current, BlockPos front) {
        if (!canJumpFrom(world, current)) {
            return false;
        }
        BlockState frontState = world.getBlockState(front);
        if (frontState.getCollisionShape(world, front).isEmpty()) {
            return false;
        }
        if (frontState.getCollisionShape(world, front).getMax(Direction.Axis.Y) > 1.0D) {
            return false;
        }
        return collisionEmpty(world, front.up()) && collisionEmpty(world, front.up(2));
    }

    private static NeighborCandidate findDrop(ServerWorld world, BlockPos target) {
        if (!collisionEmpty(world, target)) {
            return null;
        }
        if (!collisionEmpty(world, target.up())) {
            return null;
        }
        int maxFall = AIBotConfig.get().nav().maxSafeFall();
        for (int fall = 1; fall <= maxFall; fall++) {
            BlockPos landing = target.down(fall);
            if (Standability.isStandable(world, landing)) {
                return new NeighborCandidate(landing, MoveType.DROP_DOWN, fall);
            }
            if (!collisionEmpty(world, landing)) {
                return null;
            }
        }
        return null;
    }

    // DIG 可进入:脚位与头位各自"可挖 或 已通行"(但不全空——全空是 WALK/JUMP 的领域),
    // 且脚下有支撑(挖完站得住)。修"脚空头实"死角:终点=矿正下方时站位空气、头顶是矿,
    // 原 isMineable 要求脚位非空气 → 四种邻居全拒,goal 节点永不入队,A* 万格泛洪 TIMEOUT(geo_wall 实测)。
    private boolean digEnterable(ServerWorld world, BlockPos target) {
        BlockPos head = target.up();
        boolean footOpen = collisionEmpty(world, target);
        boolean headOpen = collisionEmpty(world, head);
        if (footOpen && headOpen) {
            return false;
        }
        boolean footOk = footOpen || isMineable(world, target);
        boolean headOk = headOpen || isMineable(world, head);
        if (!footOk || !headOk || collisionEmpty(world, target.down())) {
            return false;
        }
        // P0 安全预检(深层挖矿头号死因):挖开这两格后侧面/上方岩浆会涌入——-59 钻石层就是岩浆层,
        // 实操挖钻石最常见死法。脚/头任一格暴露面贴岩浆/水 → 这条路不挖,A* 自然绕行。
        //
        // Strict-survival gate (companion fix to the DigDownTask/DescendToYTask x-ray closed in
        // a0c4edd): adjacentHazardFluid() below only rejects a direction on a hazard that is
        // ALREADY genuinely observable through the bot's own eyes right now (an open pocket, a
        // previously mined cavity, or a naturally exposed face) via OreScan.observeDangerFluid's
        // ObservableWorldQuery gate. A neighbour still hidden behind unmined rock reports UNKNOWN
        // and is never treated as a hazard here — that used to be unsound (the earlier code read
        // raw, un-mined fluid state with no gate at all, letting the bot "see" lava through solid
        // rock it had never observed). Leaving an unknown cell unrejected is safe now because
        // PathExecutor.tickDigThrough() (the sole executor of MoveType.DIG_THROUGH) reactively
        // re-checks every newly-exposed neighbour the instant mining actually opens each cell, and
        // aborts/replans on a real hazard there — see the comment on that method. This preflight is
        // therefore a proactive best-effort optimization (avoid a route the bot can already see is
        // dangerous), not the safety boundary; the reactive check is.
        boolean isGoal = pathGoal != null && (target.equals(pathGoal) || head.equals(pathGoal));
        if (!isGoal && (adjacentHazardFluid(target) || adjacentHazardFluid(head))) {
            return false; // 终点格豁免:贴岩浆的矿仍可达,挖前由任务层先封岩浆(ore_dig_lava_seal)
        }
        // P0 沙砾坍塌预检:头位上方是悬沙/砾(FallingBlock)→ 挖开即连环下落,砸头窒息+填回通道。
        if (world.getBlockState(head.up()).getBlock() instanceof net.minecraft.block.FallingBlock) {
            return false;
        }
        return true;
    }

    // 暴露面危险流体(岩浆/水):四水平邻+上方任一已观测到危险流体即危险(下方由 target.down
    // 实心保证不漏)。见 digEnterable() 上方的门控说明:只在真已观测到时拒绝,未观测的邻位一律
    // UNKNOWN 放行,交给 PathExecutor.tickDigThrough() 的反应式复查兜底。
    private boolean adjacentHazardFluid(BlockPos pos) {
        if (isObservedHazardFluid(pos.up())) {
            return true;
        }
        for (Direction d : HORIZONTAL) {
            if (isObservedHazardFluid(pos.offset(d))) {
                return true;
            }
        }
        return false;
    }

    private boolean isObservedHazardFluid(BlockPos pos) {
        return cachedHazardObservation(pos) == OreScan.Observation.OBSERVED_PRESENT;
    }

    private OreScan.Observation cachedHazardObservation(BlockPos pos) {
        return hazardObservationCache.computeIfAbsent(
                pos.toImmutable(), p -> OreScan.observeDangerFluid(bot, p));
    }

    private static boolean hasHeadroom(ServerWorld world, BlockPos target) {
        // 挖掘语义的头位:已空 或 可挖(执行器 tickDigThrough 会把脚位+头位都挖开)。
        // 原"头上两格必须已空"把穿实心山体判成无路——每一步头位都是石头,DIG 邻居一个都生成不出,
        // 这正是 geo_slope/wall/pocket 全卡 no_progress 的根因(挖掘寻路只能贴地刨坑、不能穿山)。
        BlockPos head = target.up();
        return collisionEmpty(world, head) || isMineable(world, head);
    }

    private static boolean isMineable(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.getHardness(world, pos) < 0.0F || world.getBlockEntity(pos) != null) {
            return false;
        }
        if (!state.getFluidState().isEmpty() || Standability.isDangerous(state)) {
            return false;
        }
        // 矿石本身可挖(终点豁免的配套:目标矿格要能进路径;OreScan 含模组 _ore 后缀)。
        if (io.github.zoyluo.aibot.mining.OreScan.isOreBlock(state.getBlock())) {
            return true;
        }
        return state.isIn(BlockTags.STONE_ORE_REPLACEABLES)
                || state.isIn(BlockTags.DEEPSLATE_ORE_REPLACEABLES)
                || state.isIn(BlockTags.DIRT)
                || state.isOf(Blocks.STONE)
                || state.isOf(Blocks.COBBLESTONE)
                || state.isOf(Blocks.GRANITE)
                || state.isOf(Blocks.DIORITE)
                || state.isOf(Blocks.ANDESITE)
                || state.isOf(Blocks.SAND)
                || state.isOf(Blocks.RED_SAND)
                || state.isOf(Blocks.GRAVEL);
    }
}
