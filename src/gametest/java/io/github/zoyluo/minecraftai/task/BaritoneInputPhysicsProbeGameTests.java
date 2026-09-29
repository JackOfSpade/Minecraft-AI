package io.github.zoyluo.minecraftai.task;

import baritone.api.IBaritone;
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.SprintStateEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.input.Input;
import com.mojang.logging.LogUtils;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.baritone.BaritoneRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Input-to-physics regression suite for the Baritone navigation port: what a bot does when Baritone's held inputs are its
 * only controls.
 *
 * <p>Every probe drives an {@link AIPlayerEntity} <em>only</em> through Baritone's {@code InputOverrideHandler} (the
 * forward/back/left/right/jump/sneak/sprint keys a movement writes), through the real per-tick path of the integration:
 * {@code BaritoneDriver.beforePhysics} runs a probe process that sets the keys, {@code BotInputBridge} turns them into
 * {@code zza}/{@code xxa}/jump/sneak/sprint, vanilla {@code super.tick()} moves the bot, and {@code afterPhysics} runs the
 * fall check and dispatches the post-tick events, where the probe samples the result. The path executor and the legacy
 * ActionPack input path are bypassed entirely. Results are printed as {@code BOTPROBE|<probe>|<trial>|k=v ...} log lines
 * and checked against a small vanilla reference model, with the tolerances of the original spike measurements (for example
 * walking 4.3168 blocks/s here against 4.3172 for a vanilla client).
 *
 * <p>The probe process ({@code Rig.ProbeProcess}) is an ordinary {@code IBaritoneProcess} that always asks for
 * {@code REQUEST_PAUSE}, so Baritone never plans a path but counts as busy and therefore drives the bot. A listener on the
 * game event handler answers {@code SprintStateEvent} from the SPRINT key (the role {@code PathExecutor} plays for real
 * movements), so the bridge's sprint rules (forward input, food, wall hit) are what the sprint trials exercise. This
 * class lives in the {@code task} package only to renew {@link NavSafetyNet}'s swim lease in the water trials.
 */
public final class BaritoneInputPhysicsProbeGameTests {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String TAG = "BOTPROBE";
    private static final int SETTLE_TICKS = 8;
    private static final int SET_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE
            | Block.UPDATE_SUPPRESS_DROPS;

    // ------------------------------------------------------------------ vanilla reference model
    /** Ground friction of stone (0.6) times the 0.91 air drag applied every tick. */
    private static final double GROUND_F = 0.6D * 0.91D;
    private static final double WALK_SPEED = 0.1D;
    private static final double SPRINT_SPEED = 0.13D;
    private static final double AIR_SPEED = 0.02D;
    private static final double AIR_SPRINT_SPEED = 0.026D;
    private static final double SNEAK_SCALE = 0.3D;

    /** Steady-state horizontal blocks/second for a given ground speed attribute and input scale. */
    private static double refSteadyBps(double speed, double inputScale, double blockSpeedFactor) {
        return 0.98D * inputScale * speed / (1.0D - GROUND_F * blockSpeedFactor) * 20.0D;
    }

    /** Flat-ground sprint jump after {@code runUpTicks} of sprinting; returns {distance, flightTicks}. */
    private static double[] refSprintJumpFlat(int runUpTicks) {
        double z = 0.0D;
        double vz = 0.0D;
        for (int i = 0; i < runUpTicks; i++) {
            vz += 0.98D * SPRINT_SPEED;
            z += vz;
            vz *= GROUND_F;
        }
        double z0 = z;
        double vy = 0.42D;
        double y = 0.0D;
        vz += 0.2D;
        vz += 0.98D * SPRINT_SPEED;
        z += vz;
        y += vy;
        vz *= GROUND_F;
        vy = (vy - 0.08D) * 0.98D;
        int ticks = 1;
        while (ticks < 100) {
            vz += 0.98D * AIR_SPRINT_SPEED;
            double ny = y + vy;
            z += vz;
            ticks++;
            if (ny <= 0.0D) {
                break;
            }
            y = ny;
            vz *= 0.91D;
            vy = (vy - 0.08D) * 0.98D;
        }
        return new double[]{z - z0, ticks};
    }

    // ------------------------------------------------------------------------- rig
    /** Baritone-style key state; converted exactly like Baritone's PlayerMovementInput. */
    private static final class Keys {
        boolean forward;
        boolean back;
        boolean left;
        boolean right;
        boolean jump;
        boolean sneak;
        boolean sprint;
        float yaw;
        float pitch;

        void clearButtons() {
            forward = false;
            back = false;
            left = false;
            right = false;
            jump = false;
            sneak = false;
            sprint = false;
        }
    }

    private static final class Sample {
        int t;
        double preX;
        double preY;
        double preZ;
        double preVx;
        double preVy;
        double preVz;
        boolean preGround;
        double postX;
        double postY;
        double postZ;
        double postVx;
        double postVy;
        double postVz;
        boolean postGround;
        boolean postSupported;
        boolean postSprinting;
        boolean postCrouching;
        boolean postHorizontalCollision;
        boolean postInWater;
        boolean postUnderWater;
        boolean postSwimming;
        float postHealth;
        double postFall;
        float postZza;

        boolean moved() {
            return Math.abs(postX - preX) > 1.0E-9D || Math.abs(postZ - preZ) > 1.0E-9D;
        }

        boolean rose() {
            return postY > preY + 1.0E-9D;
        }
    }

    private interface Step {
        /** Called in the pre-tick hook; returns true when the trial is over (no physics tick is used). */
        boolean step(Rig r, int t);
    }

    private interface PostStep {
        void post(Rig r, int t);
    }

    private static final class Trial {
        final String name;
        final Consumer<Rig> setup;
        final Step step;
        final java.util.function.Function<Rig, String> result;
        PostStep post;
        boolean rawMode;
        int settle = SETTLE_TICKS;

        Trial(String name, Consumer<Rig> setup, Step step, java.util.function.Function<Rig, String> result) {
            this.name = name;
            this.setup = setup;
            this.step = step;
            this.result = result;
        }

        /** Post-tick writer that replaces the per-tick key writes: keys set from the POST event reach the bot one tick later. */
        Trial post(PostStep post) {
            this.post = post;
            this.rawMode = true;
            return this;
        }

        Trial settle(int ticks) {
            this.settle = ticks;
            return this;
        }
    }

    private static final class Rig {
        final GameTestHelper ctx;
        final ServerLevel level;
        final AIPlayerEntity bot;
        final BlockPos origin;
        final String probe;
        final Keys keys = new Keys();
        final ProbeProcess process = new ProbeProcess();
        IBaritone baritone;
        final List<Trial> trials = new ArrayList<>();
        final List<Sample> samples = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        Trial cur;
        int trialIndex = -1;
        int settleLeft;
        int t;
        Sample open;
        boolean finished;
        Throwable error;
        long groundMismatchTicks;
        long groundCheckedTicks;
        boolean originalSpawnMonsters;
        double lastWaterBps;

        Rig(GameTestHelper ctx, AIPlayerEntity bot, BlockPos origin, String probe) {
            this.ctx = ctx;
            this.level = ctx.getLevel();
            this.bot = bot;
            this.origin = origin;
            this.probe = probe;
        }

        /**
         * Registers the probe process and the post-tick listener with the bot's Baritone instance and wakes the process
         * once: the driver only ticks a bot that Baritone is already busy with, and a process that is in control is what
         * makes it busy, so the first tick of the instance is dispatched by hand.
         */
        void install() {
            baritone = BaritoneRegistry.INSTANCE.get(bot);
            baritone.getPathingControlManager().registerProcess(process);
            baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
                @Override
                public void onPlayerUpdate(PlayerUpdateEvent event) {
                    if (event.getState() == EventState.POST) {
                        post();
                    }
                }

                @Override
                public void onPlayerSprintState(SprintStateEvent event) {
                    if (baritone.getInputOverrideHandler().isInputForcedDown(Input.SPRINT)) {
                        event.setState(true);
                    }
                }
            });
            BaritoneRegistry.INSTANCE.tick(bot);
        }

        /** Asks for control every tick and never a path; its onTick is the probe's "before physics" slot. */
        final class ProbeProcess implements IBaritoneProcess {
            boolean active = true;

            @Override
            public boolean isActive() {
                return active;
            }

            @Override
            public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
                pre();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            @Override
            public boolean isTemporary() {
                // a hand-over (legacy order, reset) cancels every process; a probe must be cancelled, not throw
                return true;
            }

            @Override
            public void onLostControl() {
            }

            @Override
            public String displayName0() {
                return "input physics probe";
            }
        }

        BlockPos rel(int x, int y, int z) {
            return origin.offset(x, y, z);
        }

        void teleport(double x, double y, double z, float yaw) {
            bot.teleportTo(level, origin.getX() + x, origin.getY() + y, origin.getZ() + z,
                    Set.of(), yaw, 0.0F, true);
            bot.setDeltaMovement(Vec3.ZERO);
            bot.fallDistance = 0.0D;
            bot.setHealth(bot.getMaxHealth());
            bot.getFoodData().setFoodLevel(20);
            bot.setAirSupply(bot.getMaxAirSupply());
            bot.setShiftKeyDown(false);
            bot.setSprinting(false);
            bot.setJumping(false);
            bot.zza = 0.0F;
            bot.xxa = 0.0F;
            keys.yaw = yaw;
            keys.pitch = 0.0F;
            LookAction.setYawPitch(bot, yaw, 0.0F);
        }

        /** Baritone's key state for this tick, exactly what a movement writes; the bridge does the rest. */
        void applyKeys() {
            IInputOverrideHandler handler = baritone.getInputOverrideHandler();
            handler.clearAllKeys();
            handler.setInputForceState(Input.MOVE_FORWARD, keys.forward);
            handler.setInputForceState(Input.MOVE_BACK, keys.back);
            handler.setInputForceState(Input.MOVE_LEFT, keys.left);
            handler.setInputForceState(Input.MOVE_RIGHT, keys.right);
            handler.setInputForceState(Input.JUMP, keys.jump);
            handler.setInputForceState(Input.SNEAK, keys.sneak);
            handler.setInputForceState(Input.SPRINT, keys.sprint);
            LookAction.setYawPitch(bot, keys.yaw, keys.pitch);
        }

        Sample s(int i) {
            return samples.get(i);
        }

        Sample last() {
            return samples.get(samples.size() - 1);
        }

        /** Horizontal blocks/second between the pre-tick positions of samples a and b. */
        double bps(int a, int b) {
            Sample x = s(a);
            Sample y = s(b);
            return Math.hypot(y.preX - x.preX, y.preZ - x.preZ) / (b - a) * 20.0D;
        }

        void expect(boolean cond, String message) {
            if (!cond) {
                failures.add(cur == null ? message : cur.name + ": " + message);
                LOG.warn("{}|{}|EXPECT-FAIL|{}", TAG, probe, message);
            }
        }

        void emit(String trial, String data) {
            String line = TAG + "|" + probe + "|" + trial + "|" + data;
            lines.add(line);
            LOG.info("{}", line);
        }

        boolean startNext() {
            trialIndex++;
            if (trialIndex >= trials.size()) {
                return false;
            }
            cur = trials.get(trialIndex);
            samples.clear();
            open = null;
            t = 0;
            keys.clearButtons();
            cur.setup.accept(this);
            settleLeft = cur.settle;
            return true;
        }

        void finishTrial() {
            try {
                emit(cur.name, cur.result.apply(this));
            } catch (RuntimeException e) {
                error = e;
                LOG.error("{}|{}|{}|RESULT-EXCEPTION", TAG, probe, cur.name, e);
            }
            cur = null;
        }

        void pre() {
            try {
                while (true) {
                    if (finished) {
                        process.active = false;
                        keys.clearButtons();
                        applyKeys();
                        return;
                    }
                    if (cur == null && !startNext()) {
                        finished = true;
                        continue;
                    }
                    if (settleLeft > 0) {
                        settleLeft--;
                        open = null;
                        keys.clearButtons();
                        if (!cur.rawMode) {
                            applyKeys();
                        } else {
                            baritone.getInputOverrideHandler().clearAllKeys();
                        }
                        return;
                    }
                    Sample s = new Sample();
                    s.t = t;
                    s.preX = bot.getX();
                    s.preY = bot.getY();
                    s.preZ = bot.getZ();
                    Vec3 v = bot.getDeltaMovement();
                    s.preVx = v.x;
                    s.preVy = v.y;
                    s.preVz = v.z;
                    s.preGround = bot.onGround();
                    keys.clearButtons();
                    boolean end = cur.step.step(this, t);
                    if (end) {
                        finishTrial();
                        continue;
                    }
                    if (!cur.rawMode) {
                        applyKeys();
                    }
                    samples.add(s);
                    open = s;
                    t++;
                    return;
                }
            } catch (Throwable e) {
                error = e;
                LOG.error("{}|{}|PRE-EXCEPTION", TAG, probe, e);
            }
        }

        void post() {
            try {
                Sample s = open;
                if (s == null) {
                    return;
                }
                open = null;
                s.postX = bot.getX();
                s.postY = bot.getY();
                s.postZ = bot.getZ();
                Vec3 v = bot.getDeltaMovement();
                s.postVx = v.x;
                s.postVy = v.y;
                s.postVz = v.z;
                s.postGround = bot.onGround();
                AABB below = bot.getBoundingBox().move(0.0D, -1.0E-3D, 0.0D);
                s.postSupported = level.getBlockCollisions(bot, below).iterator().hasNext();
                s.postSprinting = bot.isSprinting();
                s.postCrouching = bot.isCrouching();
                s.postHorizontalCollision = bot.horizontalCollision;
                s.postInWater = bot.isInWater();
                s.postUnderWater = bot.isUnderWater();
                s.postSwimming = bot.isSwimming();
                s.postHealth = bot.getHealth();
                s.postFall = bot.fallDistance;
                s.postZza = bot.zza;
                groundCheckedTicks++;
                if (s.postGround != s.postSupported) {
                    groundMismatchTicks++;
                }
                if (cur != null && cur.post != null) {
                    cur.post.post(this, s.t);
                }
            } catch (Throwable e) {
                error = e;
                LOG.error("{}|{}|POST-EXCEPTION", TAG, probe, e);
            }
        }
    }

    // -------------------------------------------------------------------- helpers
    private static String f(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static void box(ServerLevel w, BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2,
                            BlockState state) {
        for (BlockPos p : BlockPos.betweenClosed(o.offset(x1, y1, z1), o.offset(x2, y2, z2))) {
            w.setBlock(p, state, SET_FLAGS);
        }
    }

    private static void air(ServerLevel w, BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2) {
        box(w, o, x1, y1, z1, x2, y2, z2, Blocks.AIR.defaultBlockState());
    }

    private static BlockState stone() {
        return Blocks.STONE.defaultBlockState();
    }

    private static Rig newRig(GameTestHelper ctx, String probe, String botName) {
        ServerLevel lvl = ctx.getLevel();
        boolean originalSpawnMonsters = lvl.getGameRules().get(GameRules.SPAWN_MONSTERS);
        lvl.getGameRules().set(GameRules.SPAWN_MONSTERS, false, lvl.getServer());
        BlockPos origin = ctx.absolutePos(new BlockPos(4, 4, 4));
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        ctx.getLevel().getServer(), botName, ctx.getLevel(),
                        Vec3.atBottomCenterOf(origin), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + botName));
        Rig rig = new Rig(ctx, bot, origin, probe);
        rig.originalSpawnMonsters = originalSpawnMonsters;
        return rig;
    }

    /** Runs the trials; per server tick the callback checks completion and rethrows hook errors. */
    private static void run(GameTestHelper ctx, Rig rig, String botName) {
        rig.install();
        ctx.failIfEver(() -> {
            for (Monster m : rig.level.getEntitiesOfClass(Monster.class,
                    new AABB(rig.origin).inflate(64.0D))) {
                m.discard();
            }
            if (rig.error != null) {
                rig.level.getGameRules().set(GameRules.SPAWN_MONSTERS, rig.originalSpawnMonsters, rig.level.getServer());
                AIPlayerManager.INSTANCE.despawn(ctx.getLevel().getServer(), botName);
                ctx.fail(Component.nullToEmpty("probe hook error: " + rig.error));
                return;
            }
            if (rig.finished) {
                rig.process.active = false;
                rig.level.getGameRules().set(GameRules.SPAWN_MONSTERS, rig.originalSpawnMonsters, rig.level.getServer());
                rig.emit("_summary", "ground_checked=" + rig.groundCheckedTicks
                        + " ground_flag_mismatch=" + rig.groundMismatchTicks
                        + " failures=" + rig.failures.size());
                AIPlayerManager.INSTANCE.despawn(ctx.getLevel().getServer(), botName);
                if (!rig.failures.isEmpty()) {
                    ctx.fail(Component.nullToEmpty(String.join("; ", rig.failures)));
                    return;
                }
                ctx.succeed();
            }
        });
    }

    /** Walk (flat, +Z) trial builder. */
    private static Trial flatMove(String name, double startZ, int ticks, Consumer<Keys> keys,
                                  java.util.function.Function<Rig, String> result) {
        return flatMove(name, 0.5D, startZ, ticks, keys, result);
    }

    private static Trial flatMove(String name, double startX, double startZ, int ticks, Consumer<Keys> keys,
                                  java.util.function.Function<Rig, String> result) {
        return new Trial(name, r -> r.teleport(startX, 0.0D, startZ, 0.0F), (r, t) -> {
            if (t >= ticks) {
                return true;
            }
            keys.accept(r.keys);
            return false;
        }, result);
    }

    private static String speedResult(Rig r, double refBps) {
        int n = r.samples.size() - 1;
        double bps = r.bps(10, n);
        StringBuilder early = new StringBuilder();
        for (int i = 1; i <= 6 && i <= n; i++) {
            early.append(f(Math.hypot(r.s(i).preX - r.s(i - 1).preX, r.s(i).preZ - r.s(i - 1).preZ)))
                    .append(i < 6 ? "," : "");
        }
        int sprintKept = 0;
        for (Sample s : r.samples) {
            if (s.postSprinting) {
                sprintKept++;
            }
        }
        r.expect(Math.abs(bps - refBps) <= refBps * 0.03D + 0.02D,
                "speed " + f(bps) + " b/s vs vanilla " + f(refBps));
        return "bps=" + f(bps) + " vanilla_bps=" + f(refBps) + " ratio=" + f(bps / refBps)
                + " earlyDisp=" + early + " sprintFlagKept=" + sprintKept + "/" + r.samples.size()
                + " finalZ=" + f(r.last().postZ - r.origin.getZ()) + " finalX="
                + f(r.last().postX - r.origin.getX());
    }

    // ============================================================ 1. walk / sprint / sneak speed
    @GameTest(environment = "minecraftai-gametest:baritone_input_physics_probe_game_tests_walk_sprint_sneak_speeds", maxTicks = 700)
    public void walkSprintSneakSpeeds(GameTestHelper context) {
        String name = "RawWalkGT";
        Rig r = newRig(context, "walk_sprint_sneak_speeds", name);
        ServerLevel w = context.getLevel();
        air(w, r.origin, -14, -3, -6, 14, 6, 36);
        box(w, r.origin, -14, -1, -6, 14, -1, 36, stone());
        r.trials.add(flatMove("walk", -3.0D, 46, k -> k.forward = true,
                rig -> speedResult(rig, refSteadyBps(WALK_SPEED, 1.0D, 1.0D))));
        r.trials.add(flatMove("sprint", -3.0D, 46, k -> {
            k.forward = true;
            k.sprint = true;
        }, rig -> speedResult(rig, refSteadyBps(SPRINT_SPEED, 1.0D, 1.0D))));
        r.trials.add(flatMove("sneak_walk", -3.0D, 46, k -> {
            k.forward = true;
            k.sneak = true;
        }, rig -> speedResult(rig, refSteadyBps(WALK_SPEED, SNEAK_SCALE, 1.0D))));
        r.trials.add(flatMove("walk_backward", 12.0D, 46, k -> k.back = true,
                rig -> speedResult(rig, refSteadyBps(WALK_SPEED, 1.0D, 1.0D))));
        r.trials.add(flatMove("strafe_left", -8.5D, 0.0D, 46, k -> k.left = true,
                rig -> speedResult(rig, refSteadyBps(WALK_SPEED, 1.0D, 1.0D))));
        r.trials.add(flatMove("strafe_right", 8.5D, 0.0D, 46, k -> k.right = true,
                rig -> speedResult(rig, refSteadyBps(WALK_SPEED, 1.0D, 1.0D))));
        // two impulses of 1.0 give a vector longer than 1 that vanilla normalises (speed x 1/0.98)
        r.trials.add(flatMove("diagonal_forward_left", -8.5D, -3.0D, 46, k -> {
            k.forward = true;
            k.left = true;
        }, rig -> speedResult(rig, refSteadyBps(WALK_SPEED, 1.0D, 1.0D) / 0.98D)));
        r.trials.add(flatMove("sprint_diagonal", -8.5D, -3.0D, 46, k -> {
            k.forward = true;
            k.left = true;
            k.sprint = true;
        }, rig -> speedResult(rig, refSteadyBps(SPRINT_SPEED, 1.0D, 1.0D) / 0.98D)));
        // the sprint rules of the bridge (LocalPlayer.aiStep on a client): a wall ends the sprint, low food and a missing
        // forward input never start one
        r.trials.add(new Trial("sprint_into_wall", rig -> {
            box(w, rig.origin, -3, 0, 9, 3, 2, 9, stone());
            rig.teleport(0.5D, 0.0D, 0.0D, 0.0F);
        }, (rig, t) -> {
            if (t >= 50) {
                return true;
            }
            rig.keys.forward = true;
            rig.keys.sprint = true;
            return false;
        }, rig -> {
            int kept = 0;
            int collided = 0;
            for (Sample s : rig.samples) {
                kept += s.postSprinting ? 1 : 0;
                collided += s.postHorizontalCollision ? 1 : 0;
            }
            rig.expect(kept >= 5 && kept < rig.samples.size() - 5 && !rig.last().postSprinting,
                    "the sprint did not start, or was not ended by the wall (sprinting " + kept + " of " + rig.samples.size() + " ticks)");
            return "ticks=" + rig.samples.size() + " sprintTicks=" + kept
                    + " horizontalCollisionTicks=" + collided + " finalZ=" + f(rig.bot.getZ() - rig.origin.getZ())
                    + " sprintAtEnd=" + rig.last().postSprinting;
        }));
        r.trials.add(new Trial("sprint_with_food_level_3", rig -> {
            air(w, rig.origin, -3, 0, 9, 3, 2, 9);
            rig.teleport(0.5D, 0.0D, -3.0D, 0.0F);
            rig.bot.getFoodData().setFoodLevel(3);
        }, (rig, t) -> {
            if (t >= 46) {
                return true;
            }
            rig.keys.forward = true;
            rig.keys.sprint = true;
            return false;
        }, rig -> {
            int kept = 0;
            for (Sample s : rig.samples) {
                kept += s.postSprinting ? 1 : 0;
            }
            double bps = rig.bps(10, rig.samples.size() - 1);
            double walk = refSteadyBps(WALK_SPEED, 1.0D, 1.0D);
            rig.expect(kept == 0, "the bot sprinted at food level 3 (" + kept + " ticks)");
            rig.expect(Math.abs(bps - walk) <= walk * 0.03D + 0.02D, "speed " + f(bps) + " b/s is not the walking speed " + f(walk));
            return "bps=" + f(bps) + " sprintTicks=" + kept + "/" + rig.samples.size() + " food=" + rig.bot.getFoodData().getFoodLevel();
        }));
        r.trials.add(new Trial("sprint_key_without_forward_input_walks", rig -> {
            rig.teleport(-8.5D, 0.0D, -3.0D, 0.0F);
        }, (rig, t) -> {
            if (t >= 46) {
                return true;
            }
            rig.keys.left = true;
            rig.keys.sprint = true;
            return false;
        }, rig -> {
            int kept = 0;
            for (Sample s : rig.samples) {
                kept += s.postSprinting ? 1 : 0;
            }
            double bps = rig.bps(10, rig.samples.size() - 1);
            double walk = refSteadyBps(WALK_SPEED, 1.0D, 1.0D);
            rig.expect(kept == 0, "the bot sprinted without a forward input (" + kept + " ticks)");
            rig.expect(Math.abs(bps - walk) <= walk * 0.03D + 0.02D, "strafe speed " + f(bps) + " b/s is not the walking speed " + f(walk));
            return "bps=" + f(bps) + " sprintTicks=" + kept + "/" + rig.samples.size();
        }));
        run(context, r, name);
    }

    // ============================================ 2. step-up, sprint-jump distance, parkour gaps
    @GameTest(environment = "minecraftai-gametest:baritone_input_physics_probe_game_tests_step_up_sprint_jump_and_gaps", maxTicks = 1400)
    public void stepUpSprintJumpAndGaps(GameTestHelper context) {
        String name = "RawJumpGT";
        Rig r = newRig(context, "step_up_sprint_jump_and_gaps", name);
        ServerLevel w = context.getLevel();
        air(w, r.origin, -5, -6, -8, 5, 8, 26);
        // trial: 1-block step at z=6 across the lane
        Consumer<Rig> stepSetup = rig -> {
            box(w, rig.origin, -5, -1, -8, 5, -1, 26, stone());
            air(w, rig.origin, -5, 0, -8, 5, 3, 26);
            box(w, rig.origin, -3, 0, 6, 3, 0, 8, stone());
            rig.teleport(0.5D, 0.0D, -2.0D, 0.0F);
        };
        r.trials.add(new Trial("stepup_no_jump", stepSetup, (rig, t) -> {
            if (t >= 60) {
                return true;
            }
            rig.keys.forward = true;
            return false;
        }, rig -> {
            Sample l = rig.last();
            double y = l.postY - rig.origin.getY();
            double z = l.postZ - rig.origin.getZ();
            rig.expect(y < 0.05D && z < 6.0D, "auto-stepped 1 block without jump y=" + f(y));
            return "finalY=" + f(y) + " finalZ=" + f(z) + " blocked=" + (y < 0.05D && z < 6.0D);
        }));
        for (boolean sprint : new boolean[]{false, true}) {
            r.trials.add(new Trial(sprint ? "stepup_sprint_jump" : "stepup_walk_jump", stepSetup, (rig, t) -> {
                double y = rig.bot.getY() - rig.origin.getY();
                double z = rig.bot.getZ() - rig.origin.getZ();
                if (t >= 80 || (rig.bot.onGround() && y > 0.99D && z > 6.4D && t > 3)) {
                    return true;
                }
                rig.keys.forward = true;
                rig.keys.sprint = sprint;
                // Baritone MovementAscend: press jump once within 1.2 blocks of the step and keep it
                rig.keys.jump = z > 6.0D - 1.2D;
                return false;
            }, rig -> {
                double y = rig.bot.getY() - rig.origin.getY();
                double z = rig.bot.getZ() - rig.origin.getZ();
                boolean ok = rig.bot.onGround() && y > 0.99D && y < 1.01D;
                rig.expect(ok, "did not end standing on the step y=" + f(y));
                int hops = 0;
                for (int i = 1; i < rig.samples.size(); i++) {
                    if (rig.s(i).preGround && rig.s(i).rose()) {
                        hops++;
                    }
                }
                return "success=" + ok + " ticks=" + rig.samples.size() + " finalY=" + f(y)
                        + " finalZ=" + f(z) + " jumpsStarted=" + hops;
            }));
        }
        // single standing jump: apex and flight time (vanilla apex 1.2522, 12 ticks)
        r.trials.add(new Trial("jump_in_place", stepSetup, (rig, t) -> {
            if (t >= 30) {
                return true;
            }
            rig.keys.jump = t == 0;
            return false;
        }, rig -> {
            double apex = 0.0D;
            int flight = 0;
            for (Sample s : rig.samples) {
                apex = Math.max(apex, s.postY - rig.origin.getY());
                if (!s.postGround) {
                    flight++;
                }
            }
            rig.expect(Math.abs(apex - 1.2522D) < 0.01D, "jump apex " + f(apex));
            return "apex=" + f(apex) + " vanillaApex=1.2522 airborneTicks=" + flight
                    + " firstJumpTickPostVy=" + f(rig.s(0).postVy) + " firstTickRose=" + rig.s(0).rose();
        }));
        // sprint jump on flat ground after a 25 tick run-up
        Consumer<Rig> flatSetup = rig -> {
            box(w, rig.origin, -5, -1, -8, 5, -1, 26, stone());
            air(w, rig.origin, -5, 0, -8, 5, 3, 26);
            rig.teleport(0.5D, 0.0D, -6.0D, 0.0F);
        };
        int[] jumpTick = {0};
        r.trials.add(new Trial("sprint_jump_flat", flatSetup, (rig, t) -> {
            if (t >= 90) {
                return true;
            }
            if (t > 26 && rig.samples.get(t - 1).postGround) {
                return true; // landed
            }
            rig.keys.forward = true;
            rig.keys.sprint = true;
            rig.keys.jump = t == 25;
            jumpTick[0] = 25;
            return false;
        }, rig -> {
            int j = 25;
            Sample land = rig.last();
            double dist = land.postZ - rig.s(j).preZ;
            int flight = rig.samples.size() - j;
            double[] ref = refSprintJumpFlat(25);
            rig.expect(Math.abs(dist - ref[0]) < 0.15D, "sprint jump distance " + f(dist) + " vs model " + f(ref[0]));
            return "distance=" + f(dist) + " modelDistance=" + f(ref[0]) + " flightTicks=" + flight
                    + " modelFlightTicks=" + f(ref[1]) + " landedOnGround=" + land.postGround
                    + " preJumpBps=" + f(rig.bps(15, 25));
        }));
        // parkour-style gaps: sprint run-up, jump when the centre passes the edge (z=8.0)
        for (int gap = 2; gap <= 5; gap++) {
            final int g = gap;
            for (double late : new double[]{0.0D, 0.25D}) {
                String trialName = "gap" + g + (late == 0.0D ? "_edge" : "_late25");
                r.trials.add(new Trial(trialName, rig -> {
                    box(w, rig.origin, -5, -1, -8, 5, -1, 26, stone());
                    air(w, rig.origin, -5, -1, 8, 5, -1, 8 + g - 1);
                    air(w, rig.origin, -5, 0, -8, 5, 3, 26);
                    rig.teleport(0.5D, 0.0D, -3.0D, 0.0F);
                }, (rig, t) -> {
                    double y = rig.bot.getY() - rig.origin.getY();
                    double z = rig.bot.getZ() - rig.origin.getZ();
                    if (t >= 100 || y < -1.5D) {
                        return true;
                    }
                    if (t > 12 && rig.bot.onGround() && z > 8 + g + 0.1D) {
                        return true;
                    }
                    rig.keys.forward = true;
                    rig.keys.sprint = true;
                    rig.keys.jump = rig.bot.onGround() && z >= 8.0D + late;
                    return false;
                }, rig -> {
                    double y = rig.bot.getY() - rig.origin.getY();
                    double z = rig.bot.getZ() - rig.origin.getZ();
                    boolean ok = rig.bot.onGround() && y > -0.01D && z > 8 + g;
                    if ((g <= 3 && late == 0.0D) || (g == 4 && late > 0.0D)) {
                        rig.expect(ok, "sprint-jump across a " + g + " gap (late=" + late + ") failed");
                    }
                    return "success=" + ok + " finalZ=" + f(z) + " finalY=" + f(y) + " ticks=" + rig.samples.size();
                }));
            }
        }
        run(context, r, name);
    }

    // ================================================================ 3. fall landing and damage
    @GameTest(environment = "minecraftai-gametest:baritone_input_physics_probe_game_tests_fall_landing_and_damage", maxTicks = 700)
    public void fallLandingAndDamage(GameTestHelper context) {
        String name = "RawFallGT";
        Rig r = newRig(context, "fall_landing_and_damage", name);
        ServerLevel w = context.getLevel();
        air(w, r.origin, -6, -3, -6, 6, 16, 12);
        // a ServerPlayer only checks falls when a client move packet arrives; the driver does it for a driven bot after every
        // physics tick (doCheckFallDamage), so fall distance and damage must match vanilla
        for (int height : new int[]{3, 5, 8, 12}) {
            final int h = height;
            r.trials.add(new Trial("fall" + h + "_driven", rig -> {
                air(w, rig.origin, -6, -3, -6, 6, 24, 12);
                box(w, rig.origin, -6, -1, -6, 6, -1, 12, stone());
                // platform pillar, top at y=h, edge at z=4.0
                box(w, rig.origin, -1, 0, -2, 1, h - 1, 3, stone());
                rig.teleport(0.5D, h, 0.5D, 0.0F);
            }, (rig, t) -> {
                if (t >= 90) {
                    return true;
                }
                if (t > 5 && rig.samples.get(t - 1).postGround && rig.samples.get(t - 1).postY - rig.origin.getY() < h - 0.5D) {
                    return true;
                }
                rig.keys.forward = t < 30;
                return false;
            }, rig -> {
                double maxFall = 0.0D;
                int airborne = 0;
                int landTick = -1;
                for (Sample s : rig.samples) {
                    maxFall = Math.max(maxFall, s.postFall);
                    if (!s.postGround) {
                        airborne++;
                    }
                }
                for (int i = 1; i < rig.samples.size(); i++) {
                    if (rig.s(i).postGround && rig.s(i).postY - rig.origin.getY() < h - 0.5D && landTick < 0) {
                        landTick = i;
                    }
                }
                Sample end = rig.last();
                float damage = 20.0F - rig.bot.getHealth();
                double vanilla = Math.max(0.0D, h - 3.0D);
                double landY = end.postY - rig.origin.getY();
                rig.expect(rig.bot.onGround() && Math.abs(landY) < 0.01D, "did not settle on the floor y=" + f(landY));
                rig.expect(h < 5 || maxFall > h * 0.5D, "the fall distance was not tracked (max " + f(maxFall) + " for a " + h + " block fall)");
                rig.expect(damage >= vanilla - 0.01D && damage <= vanilla + 1.01D, "fall damage " + damage + " vs vanilla " + vanilla);
                return "height=" + h + " landTick=" + landTick + " airborneTicks=" + airborne
                        + " maxFallDistanceSeen=" + f(maxFall) + " damage=" + damage
                        + " vanillaDamageForSeenFall=" + vanilla + " finalY=" + f(landY)
                        + " finalOnGround=" + end.postGround + " landZ=" + f(end.postZ - rig.origin.getZ());
            }));
        }
        r.trials.add(new Trial("damage_sanity_hurt_and_causeFallDamage", rig -> {
            air(w, rig.origin, -6, -3, -6, 6, 24, 12);
            box(w, rig.origin, -6, -1, -6, 6, -1, 12, stone());
            rig.teleport(0.5D, 0.0D, 0.5D, 0.0F);
        }, (rig, t) -> {
            if (t == 2) {
                float h0 = rig.bot.getHealth();
                boolean hurt = rig.bot.hurtServer(rig.level, rig.level.damageSources().generic(), 2.0F);
                rig.lines.add("_d1|hurtReturned=" + hurt + " hpDrop=" + (h0 - rig.bot.getHealth()));
                rig.bot.invulnerableTime = 0;
                float h1 = rig.bot.getHealth();
                boolean fell = rig.bot.causeFallDamage(6.0D, 1.0F, rig.level.damageSources().fall());
                rig.lines.add("_d2|causeFallDamage6Returned=" + fell + " hpDrop=" + (h1 - rig.bot.getHealth()));
            }
            return t >= 4;
        }, rig -> {
            return "invulnerableToFall=" + rig.bot.isInvulnerableTo(rig.level, rig.level.damageSources().fall())
                    + " hasClientLoaded=" + rig.bot.connection.hasClientLoaded()
                    + " abilitiesInvulnerable=" + rig.bot.getAbilities().invulnerable
                    + " fallDamageRule=" + rig.level.getGameRules().get(GameRules.FALL_DAMAGE)
                    + " " + rig.lines.get(rig.lines.size() - 2) + " " + rig.lines.get(rig.lines.size() - 1);
        }));
        run(context, r, name);
    }

    // ================================================================== 4. sneak at a ledge
    @GameTest(environment = "minecraftai-gametest:baritone_input_physics_probe_game_tests_sneak_stops_at_ledge_edge", maxTicks = 700)
    public void sneakStopsAtLedgeEdge(GameTestHelper context) {
        String name = "RawSneakEdgeGT";
        Rig r = newRig(context, "sneak_stops_at_ledge_edge", name);
        ServerLevel w = context.getLevel();
        air(w, r.origin, -6, -8, -8, 6, 8, 16);
        // floor z in [-8, 6], edge at z=7.0; open drop beyond (nothing until y<-8)
        box(w, r.origin, -6, -1, -8, 6, -1, 6, stone());
        r.trials.add(new Trial("sneak_forward_to_edge", rig -> rig.teleport(0.5D, 0.0D, 3.0D, 0.0F), (rig, t) -> {
            if (t >= 110) {
                return true;
            }
            rig.keys.forward = true;
            rig.keys.sneak = true;
            return false;
        }, rig -> {
            Sample l = rig.last();
            double z = l.postZ - rig.origin.getZ();
            double y = l.postY - rig.origin.getY();
            rig.expect(y > -0.05D && l.postGround, "sneaking forward fell off the ledge y=" + f(y));
            rig.expect(z - 7.0D > 0.2D && z - 7.0D < 0.32D, "sneak overhang " + f(z - 7.0D) + " not the vanilla 0.29");
            return "finalZ=" + f(z) + " overhangPastEdge=" + f(z - 7.0D) + " finalY=" + f(y)
                    + " onGround=" + l.postGround + " crouchingPose=" + l.postCrouching + " fellOff=" + (y < -0.5D);
        }));
        r.trials.add(new Trial("walk_forward_off_edge_control", rig -> rig.teleport(0.5D, 0.0D, 3.0D, 0.0F), (rig, t) -> {
            if (t >= 60) {
                return true;
            }
            rig.keys.forward = true;
            return false;
        }, rig -> {
            Sample l = rig.last();
            double y = l.postY - rig.origin.getY();
            rig.expect(y < -0.5D, "unsneaked walk did not leave the ledge");
            return "finalY=" + f(y) + " fellOff=" + (y < -0.5D);
        }));
        // Baritone bridging technique: face away from the void and back up sneaking
        r.trials.add(new Trial("sneak_backward_to_edge", rig -> rig.teleport(0.5D, 0.0D, 3.0D, 180.0F), (rig, t) -> {
            if (t >= 110) {
                return true;
            }
            rig.keys.back = true;
            rig.keys.sneak = true;
            return false;
        }, rig -> {
            Sample l = rig.last();
            double z = l.postZ - rig.origin.getZ();
            double y = l.postY - rig.origin.getY();
            rig.expect(y > -0.05D && l.postGround, "sneak-backing fell off the ledge y=" + f(y));
            rig.expect(z - 7.0D > 0.2D && z - 7.0D < 0.32D, "sneak-back overhang " + f(z - 7.0D) + " not the vanilla 0.29");
            return "finalZ=" + f(z) + " overhangPastEdge=" + f(z - 7.0D) + " finalY=" + f(y)
                    + " onGround=" + l.postGround + " fellOff=" + (y < -0.5D);
        }));
        run(context, r, name);
    }

    // ======================================================================= 5. ladder and vine
    @GameTest(environment = "minecraftai-gametest:baritone_input_physics_probe_game_tests_ladder_and_vine_climb", maxTicks = 1200)
    public void ladderAndVineClimb(GameTestHelper context) {
        String name = "RawClimbGT";
        Rig r = newRig(context, "ladder_and_vine_climb", name);
        ServerLevel w = context.getLevel();
        air(w, r.origin, -5, -3, -5, 5, 12, 10);
        for (String kind : new String[]{"ladder", "vine"}) {
            Consumer<Rig> setup = rig -> {
                air(w, rig.origin, -5, -1, -5, 5, 12, 10);
                box(w, rig.origin, -5, -1, -5, 5, -1, 3, stone());
                // wall + top ledge: x in [-1,1], z in [2,6], up to y=5 (top face at y=6)
                box(w, rig.origin, -1, 0, 2, 1, 5, 6, stone());
                for (int y = 0; y <= 5; y++) {
                    BlockState st = kind.equals("ladder")
                            ? Blocks.LADDER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                            : Blocks.VINE.defaultBlockState().setValue(BlockStateProperties.SOUTH, true);
                    w.setBlock(rig.rel(0, y, 1), st, SET_FLAGS);
                }
                // floor in front, ledge floor beyond wall top handled by the wall itself
                box(w, rig.origin, -5, -1, 4, 5, -1, 10, stone());
            };
            r.trials.add(new Trial(kind + "_walk_in_forward_jump", rig -> {
                setup.accept(rig);
                rig.teleport(0.5D, 0.0D, 0.5D, 0.0F);
            }, (rig, t) -> {
                double y = rig.bot.getY() - rig.origin.getY();
                double z = rig.bot.getZ() - rig.origin.getZ();
                if (t >= 160 || (rig.bot.onGround() && y > 5.99D && z > 2.3D)) {
                    return true;
                }
                rig.keys.forward = true;
                rig.keys.jump = true;
                return false;
            }, rig -> climbResult(rig, kind)));
            r.trials.add(new Trial(kind + "_in_cell_jump_only", rig -> {
                setup.accept(rig);
                rig.teleport(0.5D, 0.0D, 1.5D, 0.0F);
            }, (rig, t) -> {
                double y = rig.bot.getY() - rig.origin.getY();
                if (t >= 80 || y > 6.2D) {
                    return true;
                }
                rig.keys.jump = true;
                return false;
            }, rig -> climbResult(rig, kind)));
            r.trials.add(new Trial(kind + "_descend_no_input", rig -> {
                setup.accept(rig);
                rig.teleport(0.5D, 4.0D, 1.5D, 0.0F);
            }, (rig, t) -> {
                double y = rig.bot.getY() - rig.origin.getY();
                return t >= 60 || (rig.bot.onGround() && y < 0.05D);
            }, rig -> {
                int n = rig.samples.size();
                double vy = n > 6 ? rig.s(5).postVy : 0.0D;
                double y = rig.bot.getY() - rig.origin.getY();
                double mean = (rig.s(0).preY - rig.last().postY) / n;
                rig.expect(Math.abs(mean - 0.15D) < 0.03D, kind + " slide speed " + f(mean));
                return "ticksToFloor=" + n + " meanDescentPerTick=" + f(mean) + " vanillaSlide=0.1500 postTickVy=" + f(vy) + " finalY=" + f(y);
            }));
            r.trials.add(new Trial(kind + "_sneak_holds", rig -> {
                setup.accept(rig);
                rig.teleport(0.5D, 3.0D, 1.5D, 0.0F);
            }, (rig, t) -> {
                if (t >= 40) {
                    return true;
                }
                rig.keys.sneak = true;
                return false;
            }, rig -> {
                double drop = rig.s(0).preY - rig.last().postY;
                rig.expect(drop < 0.3D, kind + " sneaking did not hold position on the climbable");
                return "dropOver40Ticks=" + f(drop) + " holdsPosition=" + (drop < 0.3D);
            }));
        }
        run(context, r, name);
    }

    private static String climbResult(Rig rig, String kind) {
        double startY = rig.s(0).preY - rig.origin.getY();
        double y = rig.bot.getY() - rig.origin.getY();
        double z = rig.bot.getZ() - rig.origin.getZ();
        double climbSum = 0.0D;
        int climbTicks = 0;
        for (Sample s : rig.samples) {
            double py = s.preY - rig.origin.getY();
            if (py > 1.0D && py < 5.0D && s.postY > s.preY) {
                climbSum += s.postY - s.preY;
                climbTicks++;
            }
        }
        double perTick = climbTicks == 0 ? 0.0D : climbSum / climbTicks;
        boolean reachedTop = y > 5.9D;
        rig.expect(y > 5.5D, kind + " climb stalled before the top y=" + f(y));
        rig.expect(Math.abs(perTick - 0.1176D) < 0.012D, kind + " climb speed " + f(perTick) + "/tick");
        if (rig.cur.name.endsWith("walk_in_forward_jump")) {
            rig.expect(reachedTop && rig.bot.onGround(), kind + " forward+jump climb did not end on the top ledge");
        }
        return "reachedTop=" + reachedTop + " ticks=" + rig.samples.size() + " startY=" + f(startY)
                + " finalY=" + f(y) + " finalZ=" + f(z) + " onGroundAtEnd=" + rig.bot.onGround()
                + " meanClimbPerTick=" + f(perTick) + " (vanilla 0.1176 per tick, 2.35 b/s)"
                + " mainlyClimbTicks=" + climbTicks;
    }

    // ================================================================ 6. doors and fence gates
    @GameTest(environment = "minecraftai-gametest:baritone_input_physics_probe_game_tests_doors_and_gates", maxTicks = 1200)
    public void doorsAndGates(GameTestHelper context) {
        String name = "RawDoorGT";
        Rig r = newRig(context, "doors_and_gates", name);
        ServerLevel w = context.getLevel();
        air(w, r.origin, -6, -3, -6, 6, 8, 12);
        for (String kind : new String[]{"door", "gate"}) {
            for (String mode : new String[]{"open_walk", "closed_walk", "closed_use_then_walk"}) {
                boolean startOpen = mode.equals("open_walk");
                r.trials.add(new Trial(kind + "_" + mode, rig -> {
                    air(w, rig.origin, -6, -1, -6, 6, 8, 12);
                    box(w, rig.origin, -6, -1, -6, 6, -1, 12, stone());
                    box(w, rig.origin, -6, 0, 4, 6, 3, 4, stone()); // wall with a 1-wide opening at x=0
                    air(w, rig.origin, 0, 0, 4, 0, 3, 4);
                    if (kind.equals("door")) {
                        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)
                                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                                .setValue(BlockStateProperties.OPEN, startOpen);
                        w.setBlock(rig.rel(0, 0, 4), lower, SET_FLAGS);
                        w.setBlock(rig.rel(0, 1, 4), lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), SET_FLAGS);
                    } else {
                        w.setBlock(rig.rel(0, 0, 4), Blocks.OAK_FENCE_GATE.defaultBlockState()
                                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)
                                .setValue(BlockStateProperties.OPEN, startOpen), SET_FLAGS);
                    }
                    rig.teleport(0.5D, 0.0D, 0.5D, 0.0F);
                }, new Step() {
                    String useInfo = "not_attempted";

                    @Override
                    public boolean step(Rig rig, int t) {
                        double z = rig.bot.getZ() - rig.origin.getZ();
                        if (t >= 100 || (z > 5.5D && rig.bot.onGround())) {
                            rig.lines.add("_useInfo|" + useInfo);
                            return true;
                        }
                        BlockPos doorPos = rig.rel(0, 0, 4);
                        if (mode.equals("closed_use_then_walk") && t == 5) {
                            // aim at the door centre and click exactly like a Baritone right click
                            Vec3 aim = Vec3.atCenterOf(doorPos).add(0.0D, kind.equals("door") ? 0.0D : 0.0D, 0.0D);
                            LookAction.lookAt(rig.bot, aim);
                            HitResult pick = rig.bot.pick(rig.bot.blockInteractionRange(), 1.0F, false);
                            boolean pickIsDoor = pick instanceof BlockHitResult b && b.getBlockPos().equals(doorPos);
                            boolean beforeOpen = rig.level.getBlockState(doorPos).getValue(BlockStateProperties.OPEN);
                            InteractionResult res = null;
                            if (pick instanceof BlockHitResult b) {
                                res = rig.bot.gameMode.useItemOn(rig.bot, rig.level, ItemStack.EMPTY,
                                        InteractionHand.MAIN_HAND, b);
                            }
                            boolean afterOpen = rig.level.getBlockState(doorPos).getValue(BlockStateProperties.OPEN);
                            useInfo = "pickIsTarget=" + pickIsDoor + " pickType=" + pick.getType()
                                    + " result=" + res + " openBefore=" + beforeOpen + " openAfter=" + afterOpen;
                            rig.keys.yaw = rig.bot.getYRot();
                            rig.keys.pitch = rig.bot.getXRot();
                        }
                        if (mode.equals("closed_use_then_walk") && t < 5) {
                            return false;
                        }
                        rig.keys.yaw = 0.0F;
                        rig.keys.pitch = 0.0F;
                        rig.keys.forward = true;
                        return false;
                    }
                }, rig -> {
                    double z = rig.bot.getZ() - rig.origin.getZ();
                    boolean through = z > 5.0D;
                    boolean shouldPass = !mode.equals("closed_walk");
                    rig.expect(through == shouldPass, "passage=" + through + " expected=" + shouldPass);
                    String use = rig.lines.isEmpty() ? "" : rig.lines.remove(rig.lines.size() - 1);
                    if (mode.equals("closed_use_then_walk")) {
                        rig.expect(use.contains("pickIsTarget=true") && use.contains("openAfter=true"),
                                "right click did not open the " + kind + ": " + use);
                    }
                    return "passedThrough=" + through + " finalZ=" + f(z) + " ticks=" + rig.samples.size()
                            + " " + use;
                }));
            }
        }
        // iron door must not open by hand
        r.trials.add(new Trial("iron_door_use", rig -> {
            air(w, rig.origin, -6, -1, -6, 6, 8, 12);
            box(w, rig.origin, -6, -1, -6, 6, -1, 12, stone());
            BlockState lower = Blocks.IRON_DOOR.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)
                    .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
            w.setBlock(rig.rel(0, 0, 4), lower, SET_FLAGS);
            w.setBlock(rig.rel(0, 1, 4), lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), SET_FLAGS);
            rig.teleport(0.5D, 0.0D, 2.5D, 0.0F);
        }, (rig, t) -> {
            if (t == 2) {
                BlockPos p = rig.rel(0, 0, 4);
                LookAction.lookAt(rig.bot, Vec3.atCenterOf(p));
                HitResult pick = rig.bot.pick(rig.bot.blockInteractionRange(), 1.0F, false);
                if (pick instanceof BlockHitResult b) {
                    InteractionResult res = rig.bot.gameMode.useItemOn(rig.bot, rig.level, ItemStack.EMPTY,
                            InteractionHand.MAIN_HAND, b);
                    rig.lines.add("_iron|result=" + res + " open="
                            + rig.level.getBlockState(p).getValue(BlockStateProperties.OPEN));
                }
            }
            return t >= 6;
        }, rig -> {
            String line = rig.lines.get(rig.lines.size() - 1);
            rig.expect(line.contains("open=false"), "iron door opened by hand: " + line);
            return line;
        }));
        run(context, r, name);
    }

    // ==================================================================== 7. water and swimming
    @GameTest(environment = "minecraftai-gametest:baritone_input_physics_probe_game_tests_water_and_swimming", maxTicks = 1500)
    public void waterAndSwimming(GameTestHelper context) {
        String name = "RawWaterGT";
        Rig r = newRig(context, "water_and_swimming", name);
        ServerLevel w = context.getLevel();
        air(w, r.origin, -6, -8, -8, 6, 8, 24);
        Consumer<Rig> pool = rig -> {
            air(w, rig.origin, -6, -7, -8, 6, 8, 24);
            box(w, rig.origin, -6, -6, -8, 6, -6, 24, stone());
            box(w, rig.origin, -6, -5, -8, 6, -1, 0, stone()); // shore, top at y=0
            box(w, rig.origin, -6, -5, 21, 6, 4, 21, stone());
            box(w, rig.origin, -4, -5, 1, -4, 4, 20, stone());
            box(w, rig.origin, 4, -5, 1, 4, 4, 20, stone());
            box(w, rig.origin, -3, -5, 1, 3, -5, 20, stone());
            box(w, rig.origin, -3, -4, 1, 3, -1, 20, Blocks.WATER.defaultBlockState()); // 4 deep, surface at y=0
        };
        for (String variant : new String[]{"forward_jump", "forward_only", "forward_jump_sprint", "idle_sink"}) {
            r.trials.add(new Trial("swim_" + variant + "_with_lease", rig -> {
                pool.accept(rig);
                rig.teleport(0.5D, 0.0D, -1.5D, 0.0F);
            }, (rig, t) -> {
                NavSafetyNet.INSTANCE.renewFollowSwim(rig.bot);
                if (t >= 100) {
                    return true;
                }
                rig.keys.forward = !variant.equals("idle_sink") || t < 18;
                rig.keys.jump = variant.startsWith("forward_jump");
                rig.keys.sprint = variant.equals("forward_jump_sprint");
                return false;
            }, rig -> {
                String res = waterResult(rig);
                if (variant.equals("forward_only")) {
                    rig.expect(Math.abs(rig.lastWaterBps - 1.96D) < 0.12D, "underwater walk speed " + f(rig.lastWaterBps));
                }
                return res;
            }));
        }
        r.trials.add(new Trial("swim_up_from_pool_floor_jump_only", rig -> {
            pool.accept(rig);
            rig.teleport(0.5D, -4.0D, 4.5D, 0.0F);
        }, (rig, t) -> {
            NavSafetyNet.INSTANCE.renewFollowSwim(rig.bot);
            double y = rig.bot.getY() - rig.origin.getY();
            if (t >= 90 || (y > -0.6D && t > 2)) {
                return true;
            }
            rig.keys.jump = true;
            return false;
        }, rig -> {
            double y = rig.bot.getY() - rig.origin.getY();
            double maxVy = 0.0D;
            for (Sample s : rig.samples) {
                maxVy = Math.max(maxVy, s.postVy);
            }
            rig.expect(y > -0.6D, "jump-in-water never surfaced y=" + f(y));
            return "ticksToSurface=" + rig.samples.size() + " finalY=" + f(y) + " maxVy=" + f(maxVy)
                    + " inWater=" + rig.bot.isInWater() + " underWater=" + rig.bot.isUnderWater();
        }));
        r.trials.add(new Trial("swim_to_shore_and_exit_flush_with_water_surface", rig -> {
            pool.accept(rig);
            rig.teleport(0.5D, -1.0D, 4.5D, 180.0F);
        }, (rig, t) -> {
            NavSafetyNet.INSTANCE.renewFollowSwim(rig.bot);
            double y = rig.bot.getY() - rig.origin.getY();
            double z = rig.bot.getZ() - rig.origin.getZ();
            if (t >= 120 || (rig.bot.onGround() && y > -0.05D && z < 0.6D && t > 3)) {
                return true;
            }
            rig.keys.forward = true;
            rig.keys.jump = true;
            return false;
        }, rig -> {
            double y = rig.bot.getY() - rig.origin.getY();
            double z = rig.bot.getZ() - rig.origin.getZ();
            boolean out = rig.bot.onGround() && y > -0.05D && z < 1.0D;
            rig.expect(out, "could not leave the pool flush with the water surface");
            return "exited=" + out + " ticks=" + rig.samples.size() + " finalY=" + f(y) + " finalZ=" + f(z);
        }));
        run(context, r, name);
    }

    private static String waterResult(Rig rig) {
        int inWater = 0;
        int under = 0;
        int swimming = 0;
        double minY = 1e9;
        double maxWaterSpeed = 0.0D;
        int firstWater = -1;
        for (Sample s : rig.samples) {
            if (s.postInWater) {
                inWater++;
                if (firstWater < 0) {
                    firstWater = s.t;
                }
            }
            if (s.postUnderWater) {
                under++;
            }
            if (s.postSwimming) {
                swimming++;
            }
            minY = Math.min(minY, s.postY - rig.origin.getY());
        }
        int a = Math.min(rig.samples.size() - 2, Math.max(firstWater + 20, 40));
        int b = rig.samples.size() - 1;
        while (b > a && rig.s(b).preZ - rig.origin.getZ() > 19.0D) {
            b--;
        }
        double waterBps = b > a ? rig.bps(a, b) : 0.0D;
        rig.lastWaterBps = waterBps;
        double y = rig.bot.getY() - rig.origin.getY();
        double z = rig.bot.getZ() - rig.origin.getZ();
        return "ticks=" + rig.samples.size() + " ticksInWater=" + inWater + " ticksUnderWater=" + under + " ticksSwimmingPose=" + swimming
                + " minY=" + f(minY) + " finalY=" + f(y) + " finalZ=" + f(z)
                + " steadyBpsLate=" + f(waterBps) + " (vanilla water walk/swim about 1.96 b/s, sprint-swim faster)";
    }

    // ================================================== 8. slow blocks, slabs and stairs (no jump)
    @GameTest(environment = "minecraftai-gametest:baritone_input_physics_probe_game_tests_slow_blocks_slabs_and_stairs", maxTicks = 1000)
    public void slowBlocksSlabsAndStairs(GameTestHelper context) {
        String name = "RawSlowGT";
        Rig r = newRig(context, "slow_blocks_slabs_and_stairs", name);
        ServerLevel w = context.getLevel();
        air(w, r.origin, -5, -3, -8, 5, 6, 40);
        for (String kind : new String[]{"stone_reference", "soul_sand", "honey_block"}) {
            BlockState floor = switch (kind) {
                case "soul_sand" -> Blocks.SOUL_SAND.defaultBlockState();
                case "honey_block" -> Blocks.HONEY_BLOCK.defaultBlockState();
                default -> stone();
            };
            double sf = kind.equals("stone_reference") ? 1.0D : 0.4D;
            r.trials.add(new Trial("walk_on_" + kind, rig -> {
                air(w, rig.origin, -5, -2, -8, 5, 6, 40);
                box(w, rig.origin, -5, -1, -8, 5, -1, 40, floor);
                rig.teleport(0.5D, kind.equals("soul_sand") ? -0.0625D : 0.0D, -3.0D, 0.0F);
            }, (rig, t) -> {
                if (t >= 60) {
                    return true;
                }
                rig.keys.forward = true;
                return false;
            }, rig -> {
                double ref = refSteadyBps(WALK_SPEED, 1.0D, sf);
                double bps = rig.bps(30, rig.samples.size() - 1);
                double stoneBps = refSteadyBps(WALK_SPEED, 1.0D, 1.0D);
                rig.expect(Math.abs(bps - ref) < ref * 0.06D + 0.05D, "speed " + f(bps) + " vs model " + f(ref));
                return "bps=" + f(bps) + " modelBps=" + f(ref) + " ratioToStoneWalk=" + f(bps / stoneBps);
            }));
        }
        Consumer<Rig> reset = rig -> {
            air(w, rig.origin, -5, -2, -8, 5, 6, 40);
            box(w, rig.origin, -5, -1, -8, 5, -1, 4, stone());
        };
        r.trials.add(new Trial("bottom_slab_row_no_jump", rig -> {
            reset.accept(rig);
            box(w, rig.origin, -1, 0, 5, 1, 0, 7, Blocks.OAK_SLAB.defaultBlockState());
            box(w, rig.origin, -1, -1, 5, 1, -1, 7, stone());
            box(w, rig.origin, -5, -1, 8, 5, -1, 20, stone());
            rig.teleport(0.5D, 0.0D, 0.5D, 0.0F);
        }, (rig, t) -> {
            if (t >= 70) {
                return true;
            }
            rig.keys.forward = true;
            return false;
        }, rig -> {
            double maxY = 0.0D;
            for (Sample s : rig.samples) {
                maxY = Math.max(maxY, s.postY - rig.origin.getY());
            }
            double z = rig.bot.getZ() - rig.origin.getZ();
            rig.expect(maxY > 0.49D && z > 8.0D, "slab row not crossed without jumping maxY=" + f(maxY));
            return "maxYWithoutJump=" + f(maxY) + " finalZ=" + f(z) + " finalY=" + f(rig.bot.getY() - rig.origin.getY());
        }));
        r.trials.add(new Trial("stairs_up_no_jump", rig -> {
            reset.accept(rig);
            box(w, rig.origin, -1, -1, 5, 1, -1, 12, stone());
            box(w, rig.origin, -1, 0, 5, 1, 0, 5, Blocks.OAK_STAIRS.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
            box(w, rig.origin, -1, 0, 6, 1, 0, 12, stone());
            rig.teleport(0.5D, 0.0D, 0.5D, 0.0F);
        }, (rig, t) -> {
            if (t >= 45) {
                return true;
            }
            rig.keys.forward = true;
            return false;
        }, rig -> {
            double y = rig.bot.getY() - rig.origin.getY();
            double z = rig.bot.getZ() - rig.origin.getZ();
            rig.expect(y > 0.99D && z > 6.0D, "stairs not climbed without jumping y=" + f(y));
            return "finalY=" + f(y) + " finalZ=" + f(z) + " onGround=" + rig.bot.onGround();
        }));
        run(context, r, name);
    }

    // ================================================== 11. pillar jump-place and sneak bridging
    @GameTest(environment = "minecraftai-gametest:baritone_input_physics_probe_game_tests_pillar_and_bridge_placement", maxTicks = 900)
    public void pillarAndBridgePlacement(GameTestHelper context) {
        String name = "RawPlaceGT";
        Rig r = newRig(context, "pillar_and_bridge_placement", name);
        ServerLevel w = context.getLevel();
        air(w, r.origin, -6, -3, -6, 6, 10, 26);
        Consumer<Rig> setup = rig -> {
            air(w, rig.origin, -6, -3, -6, 6, 10, 26);
            box(w, rig.origin, -6, -1, -6, 6, -1, 6, stone());
            rig.bot.getInventory().clearContent();
            rig.bot.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 32));
            InventoryAction.equipFromSlot(rig.bot, 0);
        };
        int[] level = {0};
        int[] placed = {0};
        String[] lastFail = {"none"};
        // Baritone MovementPillar: hold jump, look down, place under the feet at the top of the arc
        r.trials.add(new Trial("pillar_hold_jump_place_under_feet_x3", rig -> {
            level[0] = 0;
            placed[0] = 0;
            lastFail[0] = "none";
            rig.teleport(0.5D, 0.0D, 0.5D, 0.0F);
            setup.accept(rig);
        }, (rig, t) -> {
            double y = rig.bot.getY() - rig.origin.getY();
            if (t >= 200 || (level[0] >= 3 && rig.bot.onGround() && y > 2.99D)) {
                return true;
            }
            rig.keys.pitch = 90.0F;
            rig.keys.jump = level[0] < 3;
            BlockPos cell = rig.rel(0, level[0], 0);
            if (level[0] < 3 && y > level[0] + 1.0D && rig.level.getBlockState(cell).isAir()) {
                ActionResult res = BuildAction.placeBlockAt(rig.bot, cell);
                if (res.isSuccess()) {
                    level[0]++;
                    placed[0]++;
                } else {
                    lastFail[0] = String.valueOf(res.reason());
                }
                rig.keys.pitch = 90.0F;
            }
            return false;
        }, rig -> {
            double y = rig.bot.getY() - rig.origin.getY();
            boolean ok = level[0] >= 3 && rig.bot.onGround() && y > 2.99D;
            rig.expect(ok, "pillar did not reach 3 blocks (level=" + level[0] + " y=" + f(y) + ")");
            return "success=" + ok + " blocksPlaced=" + placed[0] + " ticks=" + rig.samples.size() + " finalY=" + f(y)
                    + " onGround=" + rig.bot.onGround() + " lastPlaceFailure=" + lastFail[0];
        }));
        int[] nextZ = {7};
        r.trials.add(new Trial("sneak_bridge_place_ahead_over_void", rig -> {
            nextZ[0] = 7;
            placed[0] = 0;
            lastFail[0] = "none";
            rig.teleport(0.5D, 0.0D, 3.5D, 0.0F);
            setup.accept(rig);
        }, (rig, t) -> {
            double z = rig.bot.getZ() - rig.origin.getZ();
            double y = rig.bot.getY() - rig.origin.getY();
            if (t >= 320 || y < -1.5D || (z > 12.6D && rig.bot.onGround())) {
                return true;
            }
            BlockPos ahead = rig.rel(0, -1, nextZ[0]);
            while (!rig.level.getBlockState(ahead).isAir() && nextZ[0] < 40) {
                nextZ[0]++;
                ahead = rig.rel(0, -1, nextZ[0]);
            }
            rig.keys.sneak = true;
            rig.keys.forward = true;
            if (z >= nextZ[0] - 0.75D) {
                ActionResult res = BuildAction.placeBlockAt(rig.bot, ahead);
                if (res.isSuccess()) {
                    placed[0]++;
                } else {
                    lastFail[0] = String.valueOf(res.reason());
                }
                rig.keys.yaw = 0.0F;
                rig.keys.pitch = 0.0F;
            }
            return false;
        }, rig -> {
            double z = rig.bot.getZ() - rig.origin.getZ();
            double y = rig.bot.getY() - rig.origin.getY();
            boolean ok = y > -0.05D && z > 12.5D;
            rig.expect(ok, "sneak bridge failed (z=" + f(z) + " y=" + f(y) + ")");
            return "success=" + ok + " blocksPlaced=" + placed[0] + " ticks=" + rig.samples.size() + " finalZ=" + f(z)
                    + " finalY=" + f(y) + " lastPlaceFailure=" + lastFail[0];
        }));
        run(context, r, name);
    }

    // ============================================================ 9. onGround correctness
    @GameTest(environment = "minecraftai-gametest:baritone_input_physics_probe_game_tests_on_ground_consistency", maxTicks = 900)
    public void onGroundConsistency(GameTestHelper context) {
        String name = "RawGroundGT";
        Rig r = newRig(context, "on_ground_consistency", name);
        ServerLevel w = context.getLevel();
        air(w, r.origin, -6, -3, -6, 6, 12, 12);
        Consumer<Rig> setup = rig -> {
            air(w, rig.origin, -6, -3, -6, 6, 12, 12);
            box(w, rig.origin, -6, -1, -6, 6, -1, 12, stone());
            box(w, rig.origin, -1, 0, -2, 1, 2, 3, stone()); // 3-high pillar top y=3
        };
        r.trials.add(new Trial("idle_standing", rig -> {
            setup.accept(rig);
            rig.teleport(0.5D, 0.0D, 6.5D, 0.0F);
        }, (rig, t) -> t >= 30, rig -> groundTrace(rig, "idle")));
        r.trials.add(new Trial("jump_and_land", rig -> {
            setup.accept(rig);
            rig.teleport(0.5D, 0.0D, 6.5D, 0.0F);
        }, (rig, t) -> {
            rig.keys.jump = t == 0;
            return t >= 24;
        }, rig -> groundTrace(rig, "jump")));
        r.trials.add(new Trial("walk_off_3_high_pillar", rig -> {
            setup.accept(rig);
            rig.teleport(0.5D, 3.0D, 0.5D, 0.0F);
        }, (rig, t) -> {
            rig.keys.forward = t < 20;
            return t >= 34;
        }, rig -> groundTrace(rig, "fall3")));
        r.trials.add(new Trial("teleport_2_above_floor", rig -> {
            setup.accept(rig);
            rig.teleport(0.5D, 2.0D, 6.5D, 0.0F);
        }, (rig, t) -> t >= 14, rig -> groundTrace(rig, "tp_air")).settle(0));
        r.trials.add(new Trial("teleport_exactly_on_floor", rig -> {
            setup.accept(rig);
            rig.teleport(0.5D, 0.0D, 6.5D, 0.0F);
        }, (rig, t) -> t >= 6, rig -> groundTrace(rig, "tp_floor")).settle(0));
        r.trials.add(new Trial("teleport_onto_floor_from_air_setOnGround_false", rig -> {
            setup.accept(rig);
            rig.teleport(0.5D, 0.0D, 6.5D, 0.0F);
            rig.bot.setOnGround(false);
        }, (rig, t) -> t >= 6, rig -> groundTrace(rig, "tp_floor_flagfalse")).settle(0));
        run(context, r, name);
    }

    private static String groundTrace(Rig rig, String label) {
        StringBuilder pre = new StringBuilder();
        StringBuilder post = new StringBuilder();
        StringBuilder sup = new StringBuilder();
        StringBuilder ys = new StringBuilder();
        int mismatch = 0;
        for (Sample s : rig.samples) {
            ys.append(f(s.postY - rig.origin.getY())).append(',');
            pre.append(s.preGround ? 'G' : 'a');
            post.append(s.postGround ? 'G' : 'a');
            sup.append(s.postSupported ? 'S' : '.');
            if (s.postGround != s.postSupported) {
                mismatch++;
            }
        }
        int allowedMismatch = label.equals("idle") || label.equals("jump") ? 0 : 1;
        rig.expect(mismatch <= allowedMismatch,
                label + " onGround flag disagreed with the collision geometry for " + mismatch + " ticks");
        return label + " preFlag=" + pre + " postFlag=" + post + " geometrySupported=" + sup
                + " postY=" + ys + " mismatchTicks=" + mismatch + " finalY=" + f(rig.bot.getY() - rig.origin.getY());
    }

    // ========================================================== 10. input-to-motion latency
    @GameTest(environment = "minecraftai-gametest:baritone_input_physics_probe_game_tests_input_tick_order_latency", maxTicks = 700)
    public void inputTickOrderLatency(GameTestHelper context) {
        String name = "RawOrderGT";
        Rig r = newRig(context, "input_tick_order_latency", name);
        ServerLevel w = context.getLevel();
        air(w, r.origin, -6, -3, -6, 6, 8, 12);
        Consumer<Rig> setup = rig -> {
            air(w, rig.origin, -6, -3, -6, 6, 8, 12);
            box(w, rig.origin, -6, -1, -6, 6, -1, 12, stone());
            rig.teleport(0.5D, 0.0D, 0.5D, 0.0F);
        };
        // A: keys set by a process in its onTick (what every Baritone movement does) reach the physics of the same tick
        r.trials.add(new Trial("A_forward_written_in_process_tick", setup, (rig, t) -> {
            rig.keys.forward = t < 6;
            return t >= 8;
        }, rig -> latency(rig, false, "write_at=process_tick(t0)")));
        r.trials.add(new Trial("A_jump_written_in_process_tick", setup, (rig, t) -> {
            rig.keys.jump = t == 0;
            return t >= 8;
        }, rig -> latency(rig, true, "write_at=process_tick(t0)")));
        // C: keys set from the post-tick event (PlayerUpdateEvent POST) are picked up by the bridge one tick later
        r.trials.add(new Trial("C_forward_written_post_tick", setup, (rig, t) -> t >= 9, rig ->
                latency(rig, false, "write_at=post(t0)")).post((rig, t) -> {
            IInputOverrideHandler handler = rig.baritone.getInputOverrideHandler();
            handler.clearAllKeys();
            handler.setInputForceState(Input.MOVE_FORWARD, t <= 5);
        }));
        r.trials.add(new Trial("C_jump_written_post_tick", setup, (rig, t) -> t >= 9, rig ->
                latency(rig, true, "write_at=post(t0)")).post((rig, t) -> {
            IInputOverrideHandler handler = rig.baritone.getInputOverrideHandler();
            handler.clearAllKeys();
            handler.setInputForceState(Input.JUMP, t == 0);
        }));
        // releasing every key stops the input at once: no motion input is left behind after the last written tick
        r.trials.add(new Trial("A_release_leaves_no_motion_input", setup, (rig, t) -> {
            rig.keys.forward = t < 3;
            return t >= 6;
        }, rig -> {
            rig.expect(rig.s(4).postZza == 0.0F && !rig.s(4).postSprinting,
                    "the bridge kept a motion input after every key was released: zza=" + rig.s(4).postZza);
            return "zzaAfterRelease=" + f(rig.s(4).postZza) + " zzaWhileHeld=" + f(rig.s(1).postZza);
        }));
        run(context, r, name);
    }

    private static String latency(Rig rig, boolean vertical, String how) {
        int first = -1;
        for (Sample s : rig.samples) {
            if (vertical ? s.rose() : s.moved()) {
                first = s.t;
                break;
            }
        }
        double firstDisp = first < 0 ? 0.0D : Math.hypot(rig.s(first).postX - rig.s(first).preX,
                rig.s(first).postZ - rig.s(first).preZ);
        String trialName = rig.cur.name;
        int expected = trialName.startsWith("A_") ? 0 : 1;
        rig.expect(first == expected, "input written as '" + how + "' first moved the bot on tick " + first
                + " (expected " + expected + ")");
        double firstDy = first < 0 ? 0.0D : rig.s(first).postY - rig.s(first).preY;
        return how + " firstTickWithMotion=" + first + " firstTickHorizontalDisp=" + f(firstDisp)
                + " firstTickDy=" + f(firstDy);
    }
}
