package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogFields;
import io.github.zoyluo.minecraftai.mining.assist.BotEdits;
import io.github.zoyluo.minecraftai.mode.FaceAim;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.AStarPathfinder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

public final class BuildAction {
    // Shared with ObservableWorldQuery.FACE_SAMPLE_INSET (both sample the same 3x3 inset grid on a face).
    private static final double FACE_SAMPLE_INSET = ObservableWorldQuery.FACE_SAMPLE_INSET;
    private static final double[][] FACE_SAMPLE_OFFSETS = {
            {0.0D, 0.0D},
            {-FACE_SAMPLE_INSET, 0.0D},
            {FACE_SAMPLE_INSET, 0.0D},
            {0.0D, -FACE_SAMPLE_INSET},
            {0.0D, FACE_SAMPLE_INSET},
            {-FACE_SAMPLE_INSET, -FACE_SAMPLE_INSET},
            {-FACE_SAMPLE_INSET, FACE_SAMPLE_INSET},
            {FACE_SAMPLE_INSET, -FACE_SAMPLE_INSET},
            {FACE_SAMPLE_INSET, FACE_SAMPLE_INSET}
    };
    /**
     * Shape-free discovery depths used before reading a support's state. One of these reaches
     * the real face of common partial blocks (slabs, farmland, beds) while the exact first-hit
     * check below prevents it from treating a neighbouring cell as the support.
     */
    private static final double[] FACE_PROOF_DEPTHS = {
            FaceAim.OBSERVE_DEPTH, 0.25D, 0.5D, 0.75D
    };

    private BuildAction() {
    }

    public static ActionResult placeBlock(AIPlayerEntity player, BlockPos against, Direction face, InteractionHand hand) {
        return placeBlock(player, against, face, hand, false);
    }

    /** Reported when the clicked block is inside spawn protection or outside the world border, as vanilla refuses it. */
    public static final String PROTECTED_AREA = "protected_area";

    /**
     * The check the packet handler makes before it lets a right click on a block reach the game mode
     * ({@code ServerGamePacketListenerImpl#handleUseItemOn}: {@code ServerLevel#mayInteract}, which refuses spawn
     * protection for a non-operator and anything outside the world border). Our clicks skip the packet handler, so the
     * check is repeated before every block use.
     */
    public static boolean isProtectedArea(AIPlayerEntity player, BlockPos pos) {
        return !player.level().mayInteract(player, pos);
    }

    /** Reported by the first pass of {@link #placeBlockAt} for a support that has an interaction of its own. */
    static final String SUPPORT_INTERACTIVE_DEFERRED = "support_interactive_deferred";

    /**
     * {@code deferInteractive}: a support whose own use interaction (menu, door, lever, cake, bed, ...) exists is
     * not clicked but reported {@link #SUPPORT_INTERACTIVE_DEFERRED}, so a plain support is tried first. The
     * shape is read to aim (a chest or slab face is not the cell face), but whether a click happens, and whether the
     * support counts as interactive, is only decided after a ray from the eye has proven that exact face visible.
     */
    private static ActionResult placeBlock(AIPlayerEntity player, BlockPos against, Direction face,
                                           InteractionHand hand, boolean deferInteractive) {
        if (io.github.zoyluo.minecraftai.task.ShieldGuard.usingShield(player)) {
            // The use key holds the shield up: the client drops every other use click meanwhile (Minecraft.handleKeybinds).
            // This is a temporary ownership handoff, not a placement refusal. Stateful callers must keep their
            // target and retry after the reactive guard lowers the shield.
            return ActionResult.IN_PROGRESS;
        }
        double reach = player.blockInteractionRange();
        double sampleRange = exactPlacementSampleRange(
                MinecraftAiConfig.get().perception().radius(), reach);
        // Prove the exact support face inside both physical interaction reach and configured
        // perception before deciding anything about that support or the destination.
        BlockHitResult hit = visibleSupportFaceHit(player, against, face, sampleRange);
        if (hit == null) {
            return ActionResult.failed("support_face_not_visible");
        }
        if (!player.isWithinBlockInteractionRange(against, 0.0D)) {
            return ActionResult.failed("support_out_of_reach_or_sight");
        }
        // Vanilla measures block interaction reach against the block's bounding box, not its
        // center. The center may be outside reach while a face inset is still a legal click.
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()) {
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.ERROR, player, "place_failed", "reason", "empty_hand");
            return ActionResult.failed("empty_hand");
        }
        BlockState placementState = placementStateOf(stack);
        if (placementState == null) {
            return ActionResult.failed("not_a_block_item");
        }
        if (deferInteractive && isInteractiveSupport(player.level(), against)) {
            return ActionResult.failed(SUPPORT_INTERACTIVE_DEFERRED);
        }
        if (isProtectedArea(player, against)) {
            return ActionResult.failed(PROTECTED_AREA);
        }
        BlockPos destination = against.relative(face);
        // A placement target is terrain too: direct BuildAction callers must have a fresh eye
        // proof before vanilla is asked to inspect it. Baritone supplies the equivalent already
        // admitted-fence proof through the explicit trusted result overload below.
        if (!ObservableWorldQuery.canObserveCell(player, destination)
                && !supportHitProvesAdjacentDestinationVisible(player, hit, destination)) {
            return ActionResult.failed("destination_not_visible");
        }
        Use use = useItemOnHitCrouching(player, hit, hand, placementState);
        net.minecraft.world.InteractionResult result = use.result();
        if (use.placed()) {
            player.swing(hand);
            player.resetLastActionTime();
            return ActionResult.SUCCESS;
        }
        String reason = result.consumesAction() ? "accepted_without_block_change" : result.getClass().getSimpleName();
        BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.ERROR, player, "place_failed",
                "pos", LogFields.pos(destination), "reason", reason);
        return ActionResult.failed("interact_block_" + reason);
    }

    /**
     * {@link #useItemOnHit} as a crouching player does it: place, do not use. Vanilla runs the clicked block's own
     * interaction first (menu, door, lever, crafting table, cake, bed) unless the placer is in secondary use, so our own
     * placement paths ({@link #placeBlock}, {@link #placeBlockAt}) crouch for the click. The flag is restored at once
     * and the pose only updates on the entity's own tick, so the eye does not move. A movement driver that hands over its
     * own hit (Baritone) calls {@link #useItemOnHit} directly and asks for sneak itself: a plain click there must still
     * be able to open a door or a gate, and a sneaking click opens nothing (the Baritone policy).
     */
    private static Use useItemOnHitCrouching(AIPlayerEntity player, BlockHitResult hit,
                                               InteractionHand hand, BlockState placementState) {
        boolean wasShifting = player.isShiftKeyDown();
        player.setShiftKeyDown(true);
        try {
            return useItemOnHit(player, hit, hand, placementState);
        } finally {
            player.setShiftKeyDown(wasShifting);
        }
    }

    /**
     * Outcome of {@link #useItemOnHit}.
     *
     * @param result      what the vanilla interaction returned
     * @param placementState the factual state at the admitted adjacent destination after a successful placement, or null
     *                       when the click did not change that destination
     * @param destination the cell a placement would have filled
     */
    public record Use(net.minecraft.world.InteractionResult result, BlockState placementState, BlockPos destination) {
        public boolean placed() {
            return placementState != null;
        }
    }

    /**
     * Uses the held item of {@code hand} on the exact hit {@code hit}, the way a client's right click does, and keeps the mod's
     * books when that placed a block (path cache invalidation, bot-edit ledger, the {@code place} action log line). This is
     * the single place a block is put down by a click: {@link #placeBlock} finds a support face and aims for it, then ends
     * here; a caller that has already aimed (a movement driver such as Baritone, whose right click may just as well open a
     * door or a gate) hands over its own hit and ends here too. It does not aim, check reach or swing: the caller owns those.
     */
    public static Use useItemOnHit(AIPlayerEntity player, BlockHitResult hit, InteractionHand hand) {
        return useItemOnHit(player, hit, hand, null);
    }

    /**
     * Executes an already aimed block click. {@code placementState} is supplied only by a caller
     * that has proved the destination (a visible direct placement or Baritone's admitted fence).
     * The exact admitted destination is then checked after the click so a consumed interaction
     * cannot be published as terrain when vanilla made no block change.
     */
    public static Use useItemOnHit(AIPlayerEntity player, BlockHitResult hit, InteractionHand hand,
                                   BlockState placementState) {
        BlockPos destination = hit.getBlockPos().relative(hit.getDirection());
        if (io.github.zoyluo.minecraftai.task.ShieldGuard.usingShield(player)) {
            // Baritone and other already-aimed callers have no ActionResult channel. PASS faithfully says
            // the click was not sent while the reactive shield owns the use key.
            return new Use(net.minecraft.world.InteractionResult.PASS, null, destination);
        }
        String supportFailure = supportFaceRefusal(player, hit);
        if (supportFailure != null) {
            return new Use(net.minecraft.world.InteractionResult.FAIL, null, destination);
        }
        ItemStack stack = player.getItemInHand(hand);
        var item = stack.getItem();
        BlockState heldPlacementState = placementStateOf(stack);
        if (isProtectedArea(player, hit.getBlockPos())) {
            return new Use(net.minecraft.world.InteractionResult.FAIL, null, destination);
        }
        // Every caller that supplies an expected placement state has already admitted this exact
        // adjacent cell (placeBlock proves it directly; Baritone supplies its admitted click
        // state). A consumed vanilla click alone is not proof of terrain mutation: it can be
        // refused because the placer still overlaps the destination.
        BlockState destinationBefore = placementState == null ? null : player.level().getBlockState(destination);
        net.minecraft.world.InteractionResult result = player.gameMode.useItemOn(
                player,
                player.level(),
                stack,
                hand,
                hit);
        BlockState destinationAfter = placementState == null ? null : player.level().getBlockState(destination);
        BlockState placed = confirmedPlacementState(heldPlacementState, placementState, result,
                destinationBefore, destinationAfter);
        if (placed != null) {
            AStarPathfinder.invalidateCache("block_place");
            BotEdits.notePlaced(player, destination);
            BotLog.action(player, "place", "pos", LogFields.pos(destination), "face", hit.getDirection(), "item", item);
        }
        return new Use(result, placed, destination);
    }

    /** The state a simple block item is expected to contribute before the click. */
    private static BlockState placementStateOf(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item ? item.getBlock().defaultBlockState() : null;
    }

    /**
     * A trusted action result is only publishable for the same ordinary block item whose state
     * the policy proved before the click. In particular, a door/gate use has no placement state,
     * and an arbitrary action that merely consumes the click cannot turn a caller-supplied state
     * into terrain provenance.
     */
    private static BlockState confirmedPlacementState(BlockState heldItemState, BlockState expected,
                                                      net.minecraft.world.InteractionResult result,
                                                      BlockState destinationBefore, BlockState destinationAfter) {
        if (expected == null || heldItemState == null || !result.consumesAction()
                || destinationBefore == null || destinationAfter == null
                || destinationBefore.equals(destinationAfter)
                || !destinationAfter.is(expected.getBlock())) {
            return null;
        }
        return expected.equals(heldItemState) ? destinationAfter : null;
    }

    /**
     * The perception proof {@link #placeBlock} demands, for a click a movement driver aimed itself (Baritone, whose right click
     * placing a block or opening a door arrives as a ready-made {@code hit}): the clicked face must be inside the interaction
     * reach and the configured perception radius, and a pure ray from the bot's eye to the hit point (no head turn, no state
     * change) must strike exactly that face of that block, so a face hidden behind another block is refused. Returns null when
     * the click is acceptable, otherwise the same failure reason {@link #placeBlock} would give
     * ({@code support_out_of_reach_or_sight} or {@code support_face_not_visible}).
     */
    public static String supportFaceRefusal(AIPlayerEntity player, BlockHitResult hit) {
        BlockPos against = hit.getBlockPos();
        Direction face = hit.getDirection();
        double sampleRange = exactPlacementSampleRange(
                MinecraftAiConfig.get().perception().radius(), player.blockInteractionRange());
        Vec3 eye = player.getEyePosition();
        Vec3 target = hit.getLocation();
        if (eye.distanceToSqr(target) > sampleRange * sampleRange || !player.isWithinBlockInteractionRange(against, 0.0D)) {
            return "support_out_of_reach_or_sight";
        }
        BlockHitResult seen = rayTo(player, eye, target, sampleRange);
        if (seen == null || seen.getType() != HitResult.Type.BLOCK || !against.equals(seen.getBlockPos()) || seen.getDirection() != face) {
            return "support_face_not_visible";
        }
        return null;
    }

    /**
     * Uses the item in {@code hand} on {@code pos}'s {@code face} exactly as a player's right click does:
     * the same exact-face ray proof as {@link #placeBlock} (a sampled point of that face that vanilla's
     * own look-direction raycast strikes, inside both the physical reach and the perception radius),
     * then {@code gameMode.useItemOn}. The item's own logic runs (hoe tilling and durability, seed
     * placement with its light and support rules, bone meal, buckets); nothing is written to the world
     * here. Success means vanilla accepted the interaction; whether the world changed as intended is for
     * the caller to check, since the result differs per item (tilling changes the clicked block, a seed
     * changes the cell above it).
     */
    public static ActionResult useItemOnFace(AIPlayerEntity player, BlockPos pos, Direction face, InteractionHand hand) {
        if (io.github.zoyluo.minecraftai.task.ShieldGuard.usingShield(player)) {
            // The use key holds the shield up: the client drops every other use click meanwhile (Minecraft.handleKeybinds).
            return ActionResult.IN_PROGRESS;
        }
        double sampleRange = exactPlacementSampleRange(
                MinecraftAiConfig.get().perception().radius(), player.blockInteractionRange());
        BlockHitResult hit = visibleSupportFaceHit(player, pos, face, sampleRange);
        if (hit == null) {
            return ActionResult.failed("face_not_visible");
        }
        if (!player.isWithinBlockInteractionRange(pos, 0.0D)) {
            return ActionResult.failed("out_of_reach_or_sight");
        }
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()) {
            return ActionResult.failed("empty_hand");
        }
        if (isProtectedArea(player, pos)) {
            return ActionResult.failed(PROTECTED_AREA);
        }
        net.minecraft.world.InteractionResult result = player.gameMode.useItemOn(
                player, player.level(), stack, hand, hit);
        if (!result.consumesAction()) {
            return ActionResult.failed("interact_block_" + result.getClass().getSimpleName());
        }
        player.swing(hand);
        player.resetLastActionTime();
        AStarPathfinder.invalidateCache("block_use");
        return ActionResult.SUCCESS;
    }

    /**
     * Uses the item in {@code hand} on the cell {@code pos} itself, aiming at its real outline (a
     * crop has no collider and can be only 2/16 tall, so no full-cube face point is a valid aim).
     * The head really turns to each sampled point inside the shape and vanilla's own look raycast must
     * strike this cell within both the physical reach and the perception radius before
     * {@code gameMode.useItemOn} is called with that exact hit (bone meal on a growing crop).
     */
    public static ActionResult useItemOnCell(AIPlayerEntity player, BlockPos pos, InteractionHand hand) {
        if (io.github.zoyluo.minecraftai.task.ShieldGuard.usingShield(player)) {
            // The use key holds the shield up: the client drops every other use click meanwhile (Minecraft.handleKeybinds).
            return ActionResult.IN_PROGRESS;
        }
        // shapeTopSamples reads the live state to aim at a crop's/field's actual outline. First
        // establish that this exact cell is in current eye view, so it is not a hidden-world
        // shape query.
        if (!ObservableWorldQuery.canObserveCell(player, pos)) {
            return ActionResult.failed("cell_not_visible");
        }
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()) {
            return ActionResult.failed("empty_hand");
        }
        var samples = ObservableWorldQuery.shapeTopSamples(player.level(), pos);
        if (samples.isEmpty()) {
            return ActionResult.failed("no_target_shape");
        }
        double sampleRange = exactPlacementSampleRange(
                MinecraftAiConfig.get().perception().radius(), player.blockInteractionRange());
        if (!player.isWithinBlockInteractionRange(pos, 0.0D)) {
            return ActionResult.failed("out_of_reach_or_sight");
        }
        Vec3 eye = player.getEyePosition();
        for (Vec3 target : samples) {
            if (eye.distanceToSqr(target) > sampleRange * sampleRange) {
                continue;
            }
            BlockHitResult hit = rotateAndRaycast(player, target, sampleRange);
            if (hit == null || hit.getType() != HitResult.Type.BLOCK || !pos.equals(hit.getBlockPos())) {
                continue;
            }
            if (isProtectedArea(player, pos)) {
                return ActionResult.failed(PROTECTED_AREA);
            }
            net.minecraft.world.InteractionResult result = player.gameMode.useItemOn(
                    player, player.level(), stack, hand, hit);
            if (!result.consumesAction()) {
                return ActionResult.failed("interact_block_" + result.getClass().getSimpleName());
            }
            player.swing(hand);
            player.resetLastActionTime();
            return ActionResult.SUCCESS;
        }
        return ActionResult.failed("cell_not_visible");
    }

    public static ActionResult placeBlockAt(AIPlayerEntity player, BlockPos pos) {
        // Do this before probing supports: all placement candidates are deferred as one action while
        // the reactive shield owns the use key. In particular, do not aggregate a transient busy
        // result into the misleading no_adjacent_block failure below.
        if (io.github.zoyluo.minecraftai.task.ShieldGuard.usingShield(player)) {
            return ActionResult.IN_PROGRESS;
        }
        ActionResult lastFailure = ActionResult.failed("no_adjacent_block");
        // Do not pre-filter supports through canObserveBlock's six face-center rays. A support
        // can expose only a clickable edge. placeBlock provides the strict observation proof by
        // requiring an exact vanilla ray hit before it clicks (the support shape is read to aim; the decision is ray-proven).
        // Supports with an interaction of their own (chest, door, lever, ...) are only used after every
        // plain support has been tried: placeBlock reports them deferred once their face is proven visible.
        List<Direction> deferredFaces = new ArrayList<>();
        BlockPos below = pos.below();
        ActionResult belowResult = placeBlock(player, below, Direction.UP, InteractionHand.MAIN_HAND, true);
        if (belowResult.isSuccess()) {
            return belowResult;
        }
        if (SUPPORT_INTERACTIVE_DEFERRED.equals(belowResult.reason())) {
            deferredFaces.add(Direction.UP);
        }
        lastFailure = preferPlacementFailure(lastFailure, belowResult);

        for (Direction direction : Direction.values()) {
            BlockPos against = pos.relative(direction.getOpposite());
            if (against.equals(below)) {
                continue;
            }
            ActionResult result = placeBlock(player, against, direction, InteractionHand.MAIN_HAND, true);
            if (result.isSuccess()) {
                return result;
            }
            if (SUPPORT_INTERACTIVE_DEFERRED.equals(result.reason())) {
                deferredFaces.add(direction);
            }
            lastFailure = preferPlacementFailure(lastFailure, result);
        }
        for (Direction direction : deferredFaces) {
            ActionResult result = placeBlock(player, pos.relative(direction.getOpposite()), direction,
                    InteractionHand.MAIN_HAND, false);
            if (result.isSuccess()) {
                return result;
            }
            lastFailure = preferPlacementFailure(lastFailure, result);
        }
        return lastFailure;
    }

    static double exactPlacementSampleRange(int perceptionRadius, double interactionRange) {
        return Math.min(Math.max(1, perceptionRadius), interactionRange);
    }

    /**
     * Whether {@link #placeBlockAt} would find an acceptable support at {@code pos} -- the SAME
     * reach and obstruction test placeBlockAt itself uses ({@link #probeSupportFaceHit} +
     * {@code isWithinBlockInteractionRange}), without any world mutation, requiring an item in hand, or
     * turning the bot's head (a candidate probe must not have that side effect; see
     * {@link #probeSupportFaceHit}). Lets a caller (e.g. CraftTask's placement-candidate search)
     * filter candidate cells down to ones BuildAction would actually accept, instead of only
     * checking "open air" and then discovering support_face_not_visible after already committing
     * to a cell.
     */
    public static boolean canAcceptPlacementAt(AIPlayerEntity player, BlockPos pos) {
        double reach = player.blockInteractionRange();
        double sampleRange = exactPlacementSampleRange(
                MinecraftAiConfig.get().perception().radius(), reach);
        BlockPos below = pos.below();
        if (hasAcceptableSupportFace(player, below, Direction.UP, sampleRange)) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            BlockPos against = pos.relative(direction.getOpposite());
            if (against.equals(below)) {
                continue;
            }
            if (hasAcceptableSupportFace(player, against, direction, sampleRange)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAcceptableSupportFace(AIPlayerEntity player,
                                                     BlockPos against,
                                                     Direction face,
                                                     double sampleRange) {
        return probeSupportFaceHit(player, against, face, sampleRange) != null
                && player.isWithinBlockInteractionRange(against, 0.0D);
    }

    static ActionResult preferPlacementFailure(ActionResult current, ActionResult candidate) {
        if (candidate == null || !candidate.isFailed()) {
            return current;
        }
        if (current == null || !current.isFailed()
                || placementFailurePriority(candidate.reason())
                > placementFailurePriority(current.reason())) {
            return candidate;
        }
        return current;
    }

    private static int placementFailurePriority(String reason) {
        if (PROTECTED_AREA.equals(reason)) {
            return 5;
        }
        if (reason != null && reason.startsWith("interact_block_")) {
            return 4;
        }
        if ("empty_hand".equals(reason)) {
            return 3;
        }
        if ("support_out_of_reach_or_sight".equals(reason)) {
            return 2;
        }
        if ("support_face_not_visible".equals(reason)) {
            return 1;
        }
        return 0;
    }

    /**
     * Returns a vanilla ray-proven hit on the requested support face. A face can be physically
     * clickable at an exposed edge even when its center is hidden by the neighbouring mining
     * wall, so sample a small deterministic inset grid before declaring it inaccessible. This is
     * the real placement's own proof: it physically turns the bot's head to look at each sampled
     * point, exactly as a player would, before asking vanilla what that look direction hits.
     */
    private static BlockHitResult visibleSupportFaceHit(AIPlayerEntity player,
                                                        BlockPos against,
                                                        Direction face,
                                                        double sampleRange) {
        return supportFaceHit(player, against, face, sampleRange, true);
    }

    /**
     * {@link #canAcceptPlacementAt}'s query form of {@link #visibleSupportFaceHit}: the identical
     * exact-edge search and the identical reach/obstruction result, but side-effect free. Turning
     * the head is an action a real placement performs; a mere candidate probe must not perform it
     * (it would jitter the bot's look direction once per sampled cell while it is only evaluating
     * candidates). Instead each candidate ray is cast directly from the eye toward the sampled
     * point with {@link #rayTo}, the pure equivalent of what {@code Entity.raycast} computes once
     * the head is turned to face that exact point.
     */
    private static BlockHitResult probeSupportFaceHit(AIPlayerEntity player,
                                                        BlockPos against,
                                                        Direction face,
                                                        double sampleRange) {
        return supportFaceHit(player, against, face, sampleRange, false);
    }

    /** Shared exact-edge sampler behind {@link #visibleSupportFaceHit} and {@link #probeSupportFaceHit}. */
    private static BlockHitResult supportFaceHit(AIPlayerEntity player,
                                                   BlockPos against,
                                                   Direction face,
                                                   double sampleRange,
                                                   boolean rotate) {
        // A full-cell first-hit probe is intentionally state-free. It earns the right to read a
        // partial support's actual outline only after the eye ray has struck this exact requested
        // face; until then even shape lookup would be a hidden-world state read.
        if (unshapedSupportFaceHit(player, against, face, sampleRange, rotate) == null) {
            return null;
        }
        BlockState observed = player.level().getBlockState(against);
        FaceAim.Target aim = FaceAim.aim(player.level(), against, observed,
                ClipContext.Block.OUTLINE, CollisionContext.of(player));
        return shapedSupportFaceHit(player, against, face, sampleRange, rotate, aim);
    }

    /** First-hit discovery against a cell box: no support state is consulted before this proof. */
    private static BlockHitResult unshapedSupportFaceHit(AIPlayerEntity player,
                                                         BlockPos against,
                                                         Direction face,
                                                         double sampleRange,
                                                         boolean rotate) {
        double sampleRangeSquared = sampleRange * sampleRange;
        Vec3 eye = player.getEyePosition();
        AABB cell = new AABB(against);
        for (double depth : FACE_PROOF_DEPTHS) {
            for (double[] offset : FACE_SAMPLE_OFFSETS) {
                Vec3 target = FaceAim.facePoint(cell, face, depth, offset[0], offset[1]);
                if (eye.distanceToSqr(target) > sampleRangeSquared) {
                    continue;
                }
                BlockHitResult hit = rotate
                        ? rotateAndRaycast(player, target, sampleRange)
                        : rayTo(player, eye, target, sampleRange);
                BlockHitResult exact = exactSupportFaceHit(player, eye, target, sampleRange,
                        against, face, rotate, hit);
                if (exact != null) {
                    return exact;
                }
            }
        }
        return null;
    }

    /** Re-aims at the now-authorised support's real outline for the actual vanilla click. */
    private static BlockHitResult shapedSupportFaceHit(AIPlayerEntity player,
                                                       BlockPos against,
                                                       Direction face,
                                                       double sampleRange,
                                                       boolean rotate,
                                                       FaceAim.Target aim) {
        double sampleRangeSquared = sampleRange * sampleRange;
        Vec3 eye = player.getEyePosition();
        for (double[] offset : FACE_SAMPLE_OFFSETS) {
            // End just inside the authorised outline. A segment that stops exactly on a shape
            // surface can report MISS, and extending it can close a narrow exposed edge behind
            // an occluder. The exact face check below still verifies the first hit.
            Vec3 target = FaceAim.facePoint(aim.box(), face, FaceAim.OBSERVE_DEPTH, offset[0], offset[1]);
            if (eye.distanceToSqr(target) > sampleRangeSquared) {
                continue;
            }
            BlockHitResult hit = rotate
                    ? rotateAndRaycast(player, target, sampleRange)
                    : rayTo(player, eye, target, sampleRange);
            BlockHitResult exact = exactSupportFaceHit(player, eye, target, sampleRange,
                    against, face, rotate, hit);
            if (exact != null) {
                return exact;
            }
        }
        return null;
    }

    /**
     * The placement path turns the bot toward its sampled hit point first. Rotation is stored as
     * floats, so a ray grazing an exposed inset can miss after that rounding even though the exact
     * double-precision eye-to-hit segment is clear. Keep the physical turn, accept the ordinary
     * vanilla pick when it reaches the requested face, and only then use that exact segment as a
     * fallback. It still requires the same support, face, range, and unobstructed world ray.
     */
    private static BlockHitResult exactSupportFaceHit(AIPlayerEntity player, Vec3 eye, Vec3 target,
                                                       double sampleRange, BlockPos against, Direction face,
                                                       boolean rotated, BlockHitResult hit) {
        if (isExactSupportFace(hit, against, face)) {
            return hit;
        }
        if (!rotated) {
            return null;
        }
        BlockHitResult precise = rayTo(player, eye, target, sampleRange);
        return isExactSupportFace(precise, against, face) ? precise : null;
    }

    private static boolean isExactSupportFace(BlockHitResult hit, BlockPos against, Direction face) {
        return hit != null
                && hit.getType() == HitResult.Type.BLOCK
                && against.equals(hit.getBlockPos())
                && hit.getDirection() == face;
    }

    /**
     * {@code hit} was produced by the first unobstructed eye ray to this exact support face. Its
     * adjacent destination cell is visible at its near boundary, even when that cell's centre is
     * hidden behind the same near occluder. Extend the segment a tiny distance into that exact
     * adjacent cell and re-clip it with the cell-observation fluid policy. This proves the cell,
     * rather than merely proving the clicked support a second time, while retaining a legal edge
     * click whose centre ray is occluded.
     */
    private static boolean supportHitProvesAdjacentDestinationVisible(AIPlayerEntity player, BlockHitResult hit,
                                                                       BlockPos destination) {
        if (hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().relative(hit.getDirection()).equals(destination)) {
            return false;
        }
        Vec3 insideDestination = hit.getLocation().add(
                hit.getDirection().getStepX() * FaceAim.OBSERVE_DEPTH,
                hit.getDirection().getStepY() * FaceAim.OBSERVE_DEPTH,
                hit.getDirection().getStepZ() * FaceAim.OBSERVE_DEPTH);
        double sampleRange = exactPlacementSampleRange(
                MinecraftAiConfig.get().perception().radius(), player.blockInteractionRange());
        if (player.getEyePosition().distanceToSqr(insideDestination) > sampleRange * sampleRange) {
            return false;
        }
        BlockHitResult seen = player.level().clip(new ClipContext(
                player.getEyePosition(), insideDestination,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
        return seen.getType() == HitResult.Type.MISS || destination.equals(seen.getBlockPos());
    }

    /** The real placement's ray: turn the head to {@code target}, then vanilla's own look-direction raycast. */
    private static BlockHitResult rotateAndRaycast(AIPlayerEntity player, Vec3 target, double sampleRange) {
        LookAction.lookAt(player, target);
        var lookedAt = player.pick(sampleRange, 1.0F, false);
        return lookedAt instanceof BlockHitResult hit ? hit : null;
    }

    /**
     * A pure proof for a sampled support point. It first clips the exact eye-to-point segment, which
     * preserves a narrow exposed inset that can be lost when the segment is extended past the support.
     * A point on a shape surface may report MISS because the endpoint never enters that shape, so the
     * previous full-range ray remains a fallback only for that case. Both forms use OUTLINE and ignore
     * fluids; neither reads or writes yaw or pitch.
     */
    private static BlockHitResult rayTo(AIPlayerEntity player, Vec3 eye, Vec3 target, double sampleRange) {
        if (eye.distanceToSqr(target) > sampleRange * sampleRange) {
            return null;
        }
        BlockHitResult direct = player.level().clip(new ClipContext(
                eye, target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (direct.getType() != HitResult.Type.MISS) {
            return direct;
        }
        Vec3 toTarget = target.subtract(eye);
        Vec3 direction = toTarget.lengthSqr() < 1.0E-9D ? new Vec3(0.0D, -1.0D, 0.0D) : toTarget.normalize();
        Vec3 end = eye.add(direction.scale(sampleRange));
        return player.level().clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    }

    /**
     * Whether right-clicking {@code pos} with a block in hand would run the block's own interaction (open a
     * menu, toggle a door / lever / button, eat a cake, use a bed, ...) unless the placer crouches.
     */
    static boolean isInteractiveSupport(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getMenuProvider(level, pos) != null || isInteractiveKind(state);
    }

    static boolean isInteractiveKind(BlockState state) {
        return state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES)
                || state.is(BlockTags.BUTTONS) || state.is(BlockTags.BEDS) || state.is(BlockTags.CANDLE_CAKES)
                || state.is(BlockTags.ANVIL) || state.is(BlockTags.SHULKER_BOXES) || state.is(BlockTags.ALL_SIGNS)
                || state.is(Blocks.CAKE) || state.is(Blocks.LEVER) || state.is(Blocks.NOTE_BLOCK)
                || state.is(Blocks.REPEATER) || state.is(Blocks.COMPARATOR)
                || state.is(Blocks.DAYLIGHT_DETECTOR) || state.is(Blocks.JUKEBOX)
                || state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.ENCHANTING_TABLE)
                || state.is(Blocks.LECTERN) || state.is(Blocks.RESPAWN_ANCHOR);
    }
}
