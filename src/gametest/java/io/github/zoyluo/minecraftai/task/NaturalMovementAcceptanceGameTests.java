package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.TeleportAudit;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

/**
 * R5 acceptance: a long mixed session with not one correction teleport, in strict survival and on both navigation engines.
 *
 * <p>The owner is a survival mock (not ticked, so the test moves it by placing it, the way a scripted client would) that, over 1200
 * ticks, walks, sneaks over a slab edge, sprints up a one-block step, drops two blocks into a basin, swims a pond, sprints on,
 * stands while a zombie goes for it, and then walks back. The bot follows it the whole way, with real inputs only. What is asserted, on
 * every tick: {@code TeleportAudit.corrections(bot) == 0} and no privileged teleport either (nothing in the course is an
 * emergency), no drowning or suffocation damage, no damage at all before the zombie, and the follow task alive; at the end the bot is
 * within four blocks of the owner and has been past the pond.</p>
 *
 * <p>Only the strict-survival profile is run here, where the emergency teleport is denied (so a bot that got stuck cannot be rescued by
 * one). The operator profile is one JVM-wide config value: swapping it for a minute while the other scenarios run concurrently would
 * change what they see. That profile is covered by the source lock ({@code NoCorrectionTeleportSourceTest}: the only relocations left in
 * production are the four capability-gated emergency rescues) and by {@code ActionPackSuppressedSnapGameTests}, which runs the operator
 * profile for a single tick.</p>
 */
public final class NaturalMovementAcceptanceGameTests {
    private static final String ENV = "minecraftai-gametest:natural_movement_acceptance_game_tests_";
    private static final int SESSION_TICKS = 1200;
    private static final double YAW_EAST = -90.0D;
    private static final int ARENA_LAYER = 20;

    @GameTest(environment = ENV + "strict_session_has_zero_correction_teleports_legacy", maxTicks = 1300)
    public void strictSessionHasZeroCorrectionTeleportsLegacy(GameTestHelper context) {
        session(context, false, "AccL");
    }

    @GameTest(environment = ENV + "strict_session_has_zero_correction_teleports_baritone", maxTicks = 1300)
    public void strictSessionHasZeroCorrectionTeleportsBaritone(GameTestHelper context) {
        session(context, true, "AccB");
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** One stretch of the owner's route: walk (or sprint, sneak, swim) to {@code toDx} at {@code speed} blocks per tick, then stand. */
    private record Leg(double toDx, double speed, boolean sprint, boolean sneak, int standTicks) {
    }

    private static final Leg[] ROUTE = {
            new Leg(-23.0D, 0.20D, false, false, 30),   // a walk on the flat
            new Leg(-16.0D, 0.065D, false, true, 20),   // a sneak over the slab edge (cells -20 and -19)
            new Leg(-13.0D, 0.20D, false, false, 0),
            new Leg(-6.0D, 0.28D, true, false, 40),     // a sprint up the one-block step (cell -12) and along the platform
            new Leg(5.0D, 0.20D, false, false, 30),     // off the platform: a two-block drop into the basin (cell -4)
            new Leg(12.0D, 0.10D, false, false, 20),    // across the pond (cells 6 to 10, water two blocks deep)
            new Leg(24.0D, 0.28D, true, false, 0),      // a sprint on the far bank
    };

    /** The owner's feet height above the arena's floor level, at {@code dx} along the course. */
    private static double groundAt(double dx) {
        int cell = (int) Math.floor(dx + 0.5D);
        if (cell == -20 || cell == -19) {
            return 0.5D;                                 // the bottom slabs
        }
        if (cell >= -12 && cell <= -5) {
            return 1.0D;                                 // the platform
        }
        if (cell >= 6 && cell <= 10) {
            return -1.45D;                               // afloat in the pond
        }
        return cell >= -4 ? -1.0D : 0.0D;                // the basin floor, or the flat
    }

    private static void buildCourse(FollowFieldFixture f) {
        BaritoneEngineArena arena = f.arena;
        for (int z = -8; z <= 8; z++) {
            for (int x : new int[]{-20, -19}) {
                arena.world.setBlock(arena.cell(x, 0, z),
                        Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM), 3);
            }
            for (int x = -12; x <= -5; x++) {
                arena.set(x, 0, z, Blocks.STONE);
            }
            for (int x = -4; x <= 34; x++) {
                arena.set(x, -1, z, Blocks.AIR);
            }
        }
        for (int z = -3; z <= 3; z++) {
            for (int x = 6; x <= 10; x++) {
                arena.set(x, -2, z, Blocks.WATER);
                arena.set(x, -3, z, Blocks.WATER);
            }
        }
    }

    private static void session(GameTestHelper context, boolean baritone, String prefix) {
        // Its own world layer: the scene is wide and lives for a minute, and the other follow scenes share layer 0.
        FollowFieldFixture f = new FollowFieldFixture(context, 40, 8, ARENA_LAYER);
        f.require(MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL, "fixture: the default profile is not strict survival");
        buildCourse(f);
        AIPlayerEntity bot = f.bot(prefix + "Bot", -34, 0, baritone);
        ServerPlayer owner = f.owner(bot, -30, 0);
        f.give(bot, new ItemStack(Items.STONE_SWORD));
        moveOwner(f, owner, -30.0D);
        // Everything above was fixture: from here on every teleport of the bot is the code under test's.
        TeleportAudit.reset(bot);
        FollowTask follow = f.follow(bot, "", "gametest_natural_movement_acceptance");

        double[] dx = {-30.0D};
        int[] leg = {0};
        int[] stand = {30};
        int[] tick = {0};
        double[] maxBotDx = {-40.0D};
        Zombie[] zombie = {null};
        int[] zombieTick = {-1};
        boolean[] returned = {false};
        context.failIfEver(() -> {
            int now = ++tick[0];
            TaskState state = follow.state();
            f.require(state != TaskState.FAILED && state != TaskState.CANCELLED && state != TaskState.COMPLETED,
                    "follow ended at tick " + now + ": " + state + " " + follow.failureReason());
            f.require(bot.isAlive(), "the bot died at tick " + now + " at " + bot.blockPosition().toShortString());
            f.require(TeleportAudit.corrections(bot) == 0, "the bot was teleported to correct its position at tick " + now + " at "
                    + bot.blockPosition().toShortString() + " (" + TeleportAudit.lastCaller(bot) + ")");
            f.require(TeleportAudit.count(bot, TeleportAudit.Kind.PRIVILEGED) == 0,
                    "the bot used an emergency teleport at tick " + now + " (" + TeleportAudit.lastCaller(bot) + ")");
            var lastDamage = bot.getLastDamageSource();
            f.require(lastDamage == null || !(lastDamage.is(DamageTypes.DROWN) || lastDamage.is(DamageTypes.IN_WALL)),
                    "the bot took drowning or suffocation damage at tick " + now + " at " + bot.blockPosition().toShortString());
            if (zombie[0] == null) {
                f.require(bot.getHealth() >= bot.getMaxHealth() - 0.01F,
                        "the bot took damage before any zombie at tick " + now + ": " + bot.getHealth() + " at " + bot.blockPosition().toShortString());
            }
            maxBotDx[0] = Math.max(maxBotDx[0], bot.getX() - f.x(0.0D));

            // The owner's script.
            if (leg[0] < ROUTE.length) {
                Leg current = ROUTE[leg[0]];
                if (stand[0] > 0) {
                    stand[0]--;
                    setGait(owner, false, false);
                } else {
                    setGait(owner, current.sprint(), current.sneak());
                    dx[0] = Math.min(current.toDx(), dx[0] + current.speed());
                    moveOwner(f, owner, dx[0]);
                    if (dx[0] >= current.toDx()) {
                        stand[0] = current.standTicks();
                        leg[0]++;
                        setGait(owner, false, false);
                    }
                }
            } else if (zombie[0] == null && stand[0] <= 0) {
                // The far bank: a zombie goes for the owner and hits it once (the aggro the follower reacts to).
                zombie[0] = f.zombie(31.0D, 0.0D, false);
                zombie[0].snapTo(f.x(31.0D), f.arena.origin.getY() - 1.0D, f.z(0.0D), 90.0F, 0.0F);
                zombieTick[0] = now;
                owner.hurtServer(f.level, f.level.damageSources().mobAttack(zombie[0]), 1.0F);
            } else if (zombie[0] != null) {
                owner.setHealth(owner.getMaxHealth());
                if (now >= zombieTick[0] + 380 && !returned[0]) {
                    // Over: the owner walks back toward the pond and stands there.
                    if (dx[0] > 13.0D) {
                        dx[0] = Math.max(13.0D, dx[0] - 0.20D);
                        moveOwner(f, owner, dx[0]);
                    } else {
                        returned[0] = true;
                    }
                }
            }

            if (now >= SESSION_TICKS) {
                f.require(maxBotDx[0] >= 14.0D, "the bot never got past the pond (furthest x offset " + maxBotDx[0] + ")");
                f.require(bot.distanceTo(owner) <= 4.0D, "the bot ended " + bot.distanceTo(owner) + " blocks from the owner, at "
                        + bot.blockPosition().toShortString() + " (owner " + owner.blockPosition().toShortString() + ")");
                f.require(TeleportAudit.corrections(bot) == 0, "a correction teleport happened (" + TeleportAudit.lastCaller(bot) + ")");
                f.finish();
            }
        });
    }

    private static void setGait(ServerPlayer owner, boolean sprint, boolean sneak) {
        owner.setSprinting(sprint);
        owner.setShiftKeyDown(sneak);
    }

    /** Puts the owner at {@code dx} on the course, on the ground (or afloat) there, facing east. */
    private static void moveOwner(FollowFieldFixture f, ServerPlayer owner, double dx) {
        double y = f.arena.origin.getY() + groundAt(dx);
        owner.teleportTo(f.level, f.x(dx), y, f.z(0.0D), Set.of(), (float) YAW_EAST, 0.0F, true);
        owner.setDeltaMovement(Vec3.ZERO);
    }

}
