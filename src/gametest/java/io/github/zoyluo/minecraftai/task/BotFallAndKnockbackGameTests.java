package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Physics parity of the fake players with a vanilla player: fall damage and knockback.
 *
 * <p>Both were silently absent. A {@code ServerPlayer} does not check falls in {@code Entity.move} (vanilla expects the
 * client's move packet to drive {@code doCheckFallDamage}), and {@code Player.attack} against a {@code ServerPlayer} sends the
 * knockback to that player's client and then restores the old server-side velocity (the client is expected to apply it). A bot
 * has no client, so it took no fall damage and no player melee knockback. These tests fail on a branch without the fixes: the fall
 * tests measure the health a bot loses when it leaves a ledge (by the legacy executor and by a plain push), the knockback test
 * compares a bot hit by a player or a zombie with a vanilla zombie hit by the same attacker in the same geometry.</p>
 *
 * <p>Fall damage in vanilla is {@code ceil(distance - 3)}: 0 / 2 / 5 / 9 hit points for 3 / 5 / 8 / 12 blocks. The Baritone
 * driven case (and the absence of a double count when the driver and the bot's own tick both check) is asserted by
 * {@code BaritoneInputPhysicsProbeGameTests.fallLandingAndDamage}.</p>
 */
public final class BotFallAndKnockbackGameTests {
    private static final int[] HEIGHTS = {3, 5, 8, 12};
    private static final int[] VANILLA_DAMAGE = {0, 2, 5, 9};
    private static final int HALF_X = 10;
    private static final int HALF_Z = 9;
    /** A fake-connection bot counts as "client not loaded" (and takes no damage) for its first 60 ticks. */
    private static final int LOADED_AFTER_TICKS = 75;

    /**
     * A sealed, lit stone slab (floor top at {@code origin.y}, two blocks thick) with {@code top} cells of air above the floor. The test
     * world is a flat one whose structures sit near y = -58, so the suites keep to their own vertical layers of it: the other suites use
     * relative heights 46 and up, so this class stays below that (0..45).
     */
    private static BlockPos slab(GameTestHelper context, int baseY, int top) {
        ServerLevel world = context.getLevel();
        BlockPos origin = context.absolutePos(new BlockPos(8, baseY, 8));
        for (int dx = -HALF_X - 1; dx <= HALF_X + 1; dx++) {
            for (int dz = -HALF_Z - 1; dz <= HALF_Z + 1; dz++) {
                boolean ring = Math.abs(dx) > HALF_X || Math.abs(dz) > HALF_Z;
                for (int dy = -3; dy <= top; dy++) {
                    Block block = ring && dy >= -2 ? Blocks.BEDROCK : (dy >= -2 && dy <= -1 ? Blocks.STONE : Blocks.AIR);
                    world.setBlock(origin.offset(dx, dy, dz), block.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        for (int dx = -HALF_X; dx <= HALF_X; dx += 4) {
            for (int dz = -HALF_Z; dz <= HALF_Z; dz += 4) {
                world.setBlock(origin.offset(dx, 3, dz), Blocks.LIGHT.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        return origin;
    }

    private static AIPlayerEntity spawn(GameTestHelper context, String name, BlockPos feet) {
        ServerLevel world = context.getLevel();
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.setHealth(bot.getMaxHealth());
        return bot;
    }

    private static void put(AIPlayerEntity bot, ServerLevel world, double x, double y, double z) {
        bot.teleportTo(world, x, y, z, Set.of(), 0.0F, 0.0F, true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.fallDistance = 0.0D;
        bot.setOnGround(true);
    }

    private static void fail(GameTestHelper context, String message) {
        context.fail(Component.nullToEmpty(message));
        throw new IllegalStateException(message);
    }

    // ------------------------------------------------------------------------------------------------------------
    // fall damage
    // ------------------------------------------------------------------------------------------------------------

    /** How the bot is sent over the edge of the pillar. */
    private enum Mover {
        /** ActionPack.startWalkTo: the legacy executor's straight-line walk (its steering writes the inputs). */
        LEGACY_WALK,
        /** No controller at all: a velocity impulse toward the edge, as a knockback or a piston would give. */
        PLAIN_PUSH
    }

    /** Falls of 3, 5, 8 and 12 blocks for each mover, one after the other: 8 trials. */
    @GameTest(maxTicks = 1500)
    public void botsTakeVanillaFallDamageWhenWalkedOrPushedOffALedge(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos origin = slab(context, 22, 15);
        AIPlayerEntity bot = spawn(context, "BotFall", origin);
        double floorY = origin.getY();
        List<String> report = new ArrayList<>();
        int[] trial = {-1};
        int[] trialTick = {0};
        boolean[] airborne = {false};
        int[] landedAt = {-1};
        int[] tick = {0};
        context.onEachTick(() -> {
            tick[0]++;
            if (tick[0] < LOADED_AFTER_TICKS) {
                return;
            }
            if (tick[0] == LOADED_AFTER_TICKS && !bot.connection.hasClientLoaded()) {
                fail(context, "the bot is still protected as a not-yet-loaded client at tick " + tick[0]);
            }
            if (trial[0] >= 0) {
                int h = HEIGHTS[trial[0] % HEIGHTS.length];
                Mover mover = Mover.values()[trial[0] / HEIGHTS.length];
                trialTick[0]++;
                boolean grounded = bot.onGround();
                double y = bot.getY() - floorY;
                if (!grounded) {
                    airborne[0] = true;
                }
                if (airborne[0] && grounded && y < h - 0.5D && landedAt[0] < 0) {
                    landedAt[0] = trialTick[0];
                }
                if (landedAt[0] >= 0 && trialTick[0] >= landedAt[0] + 2) {
                    float damage = 20.0F - bot.getHealth();
                    int vanilla = VANILLA_DAMAGE[trial[0] % HEIGHTS.length];
                    String line = "BOTFALL|" + mover + "|height=" + h + "|damage=" + damage + "|vanilla=" + vanilla
                            + "|fallDistanceAfter=" + bot.fallDistance + "|landY=" + (bot.getY() - floorY);
                    System.out.println(line);
                    report.add(line);
                    if (Math.abs(damage - vanilla) > 0.01F) {
                        fail(context, mover + ": a " + h + " block fall cost " + damage + " hit points, vanilla costs " + vanilla + "\n" + report);
                    }
                    if (bot.fallDistance != 0.0D) {
                        fail(context, mover + ": the fall distance was not reset by the landing: " + bot.fallDistance);
                    }
                    trial[0] = -1;
                } else if (trialTick[0] > 120) {
                    fail(context, mover + ": the bot never landed from " + h + " blocks (" + bot.position() + ", ground=" + grounded + ")\n" + report);
                }
            }
            if (trial[0] == -1) {
                int next = report.size();
                if (next >= HEIGHTS.length * Mover.values().length) {
                    AIPlayerManager.INSTANCE.despawn(world.getServer(), bot.getGameProfile().name());
                    context.succeed();
                    return;
                }
                int h = HEIGHTS[next % HEIGHTS.length];
                Mover mover = Mover.values()[next / HEIGHTS.length];
                // a pillar h high (top surface at floorY + h), its edge at dz = +3.0; the floor beyond it
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        for (int dy = 0; dy <= 15; dy++) {
                            world.setBlock(origin.offset(dx, dy - 1, dz), (dy <= h ? Blocks.STONE : Blocks.AIR).defaultBlockState(), Block.UPDATE_ALL);
                        }
                    }
                }
                bot.getActionPack().stopAll();
                bot.setHealth(bot.getMaxHealth());
                bot.invulnerableTime = 0;
                // below the regeneration threshold (18) and above the hunger resupply: nothing may give a hit point back between the landing and the measurement
                bot.getFoodData().setFoodLevel(17);
                bot.getFoodData().setSaturation(0.0F);
                put(bot, world, origin.getX() + 0.5D, floorY + h, origin.getZ() + 0.5D + (mover == Mover.PLAIN_PUSH ? 2.2D : 0.0D));
                if (mover == Mover.LEGACY_WALK) {
                    bot.getActionPack().startWalkTo(new Vec3(origin.getX() + 0.5D, floorY, origin.getZ() + 6.5D), 0.3D);
                } else {
                    bot.setDeltaMovement(0.0D, 0.0D, 0.5D);
                }
                trial[0] = next;
                trialTick[0] = 0;
                airborne[0] = false;
                landedAt[0] = -1;
            }
        });
    }

    // ------------------------------------------------------------------------------------------------------------
    // knockback
    // ------------------------------------------------------------------------------------------------------------

    private record Variant(String label, boolean zombieAttacker, boolean sprint, boolean resistant) {
    }

    /**
     * Player melee against a bot must push it exactly as it pushes a vanilla mob in the same geometry (direction and size, also
     * with a sprint hit's extra knockback and with knockback resistance), and so must a zombie's melee. Every variant
     * hits a control zombie and, twelve ticks later (the attacker's cooldown has recharged), the bot from the same relative
     * position, and compares the velocities right after the hit.
     */
    @GameTest(maxTicks = 700)
    public void meleeKnockbackOfABotMatchesAVanillaMob(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos origin = slab(context, 4, 6);
        double botX = origin.getX() + 0.5D;
        double botZ = origin.getZ() + 0.5D;
        double controlX = botX + 6.0D;
        AIPlayerEntity target = spawn(context, "BotKbTarget", origin);
        AIPlayerEntity player = spawn(context, "BotKbPlayer", origin.offset(-6, 0, 0));
        List<Variant> variants = List.of(
                new Variant("player_plain", false, false, false),
                new Variant("player_sprint_hit", false, true, false),
                new Variant("player_vs_knockback_resistance", false, false, true),
                new Variant("zombie_melee", true, false, false),
                new Variant("zombie_vs_knockback_resistance", true, false, true));
        List<String> report = new ArrayList<>();
        Vec3[] controlVelocity = {Vec3.ZERO};
        int[] tick = {0};
        context.onEachTick(() -> {
            tick[0]++;
            int slot = tick[0] - LOADED_AFTER_TICKS;
            if (slot < 0 || slot % 12 != 0) {
                return;
            }
            int index = slot / 24;
            if (index >= variants.size()) {
                AIPlayerManager.INSTANCE.despawn(world.getServer(), target.getGameProfile().name());
                AIPlayerManager.INSTANCE.despawn(world.getServer(), player.getGameProfile().name());
                context.succeed();
                return;
            }
            Variant v = variants.get(index);
            boolean botSlot = (slot / 12) % 2 == 1;
            if (!botSlot) {
                Zombie victim = zombie(world, controlX, origin.getY(), botZ);
                setResistance(victim, v);
                controlVelocity[0] = hit(world, v, player, victim);
                victim.discard();
                return;
            }
            setResistance(target, v);
            put(target, world, botX, origin.getY(), botZ);
            Vec3 botVelocity = hit(world, v, player, target);
            Vec3 mobVelocity = controlVelocity[0];
            String line = String.format("BOTKB|%s|bot=(%.4f,%.4f,%.4f)|vanillaMob=(%.4f,%.4f,%.4f)", v.label(),
                    botVelocity.x, botVelocity.y, botVelocity.z, mobVelocity.x, mobVelocity.y, mobVelocity.z);
            System.out.println(line);
            report.add(line);
            // the attacker stands 1.5 blocks south of the victim: the push is away from it (+z) and up
            if (mobVelocity.horizontalDistance() < 0.2D || mobVelocity.y < 0.2D || mobVelocity.z <= 0.0D) {
                fail(context, "the control mob was not knocked back as expected, the test is broken: " + line);
            }
            if (botVelocity.subtract(mobVelocity).length() > 0.02D) {
                fail(context, v.label() + ": the bot's knockback differs from a vanilla mob's\n" + report);
            }
        });
    }

    /** Knockback resistance 0.5 for the resistant variants (set as the base value: an armour item only counts from the next tick), else 0. */
    private static void setResistance(LivingEntity victim, Variant v) {
        victim.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(v.resistant() ? 0.5D : 0.0D);
    }

    /** A mob without AI, added for one hit and removed right after it (a lingering hostile would send the bots evading). */
    private static Zombie zombie(ServerLevel world, double x, double y, double z) {
        Zombie zombie = EntityType.ZOMBIE.create(world, EntitySpawnReason.COMMAND);
        zombie.setNoAi(true);
        zombie.setSilent(true);
        zombie.snapTo(x, y, z, 0.0F, 0.0F);
        world.addFreshEntity(zombie);
        zombie.setDeltaMovement(Vec3.ZERO);
        zombie.setOnGround(true);
        return zombie;
    }

    /** One hit of the variant's attacker on {@code victim} from 1.5 blocks south of it (facing +z); returns the victim's velocity right after. */
    private static Vec3 hit(ServerLevel world, Variant v, AIPlayerEntity player, LivingEntity victim) {
        victim.invulnerableTime = 0;
        victim.setHealth(victim.getMaxHealth());
        if (v.zombieAttacker()) {
            Zombie attacker = zombie(world, victim.getX(), victim.getY(), victim.getZ() - 1.5D);
            attacker.doHurtTarget(world, victim);
            attacker.discard();
        } else {
            put(player, world, victim.getX(), victim.getY(), victim.getZ() - 1.5D);
            player.setSprinting(v.sprint());
            player.attack(victim);
        }
        return victim.getDeltaMovement();
    }
}
