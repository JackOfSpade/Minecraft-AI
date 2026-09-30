package io.github.zoyluo.minecraftai.manager;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.Set;

/**
 * A bot revived in place after dying (it has no client to send the respawn packet) must come back as a vanilla
 * respawn would hand a player back: no status effects, no XP (die() already dropped the orbs, so keeping the level
 * would duplicate it), saturation 5, no fire, full health and food.
 */
public final class BotRespawnStateGameTests {
    private static final String ENV = "minecraftai-gametest:bot_respawn_state_game_tests_";

    @GameTest(environment = ENV + "death_revive_clears_effects_and_xp_like_a_vanilla_respawn", maxTicks = 100)
    public void deathReviveClearsEffectsAndXpLikeAVanillaRespawn(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(3, 2, 3));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.offset(dx, 0, dz);
                world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        String name = "RespawnStateGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        try {
            bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                    Set.of(), 0.0F, 0.0F, true);
            bot.setExperienceLevels(12);
            bot.experienceProgress = 0.4F;
            bot.totalExperience = 195;
            bot.getFoodData().setFoodLevel(3);
            bot.getFoodData().setSaturation(0.0F);

            // Die for real, so die() drops its XP orbs exactly as it does in the field.
            bot.setHealth(0.0F);
            bot.die(world.damageSources().genericKill());
            require(context, bot.isDeadOrDying(), "fixture: the bot did not die");
            int orbsBefore = world.getEntitiesOfClass(ExperienceOrb.class,
                    bot.getBoundingBox().inflate(6.0D), orb -> true).size();
            require(context, orbsBefore > 0, "fixture: dying dropped no XP orbs, so the duplication is not observable");

            // What a dead bot can still carry into the revive: effects and a fire.
            bot.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 6000, 1));
            bot.addEffect(new MobEffectInstance(MobEffects.SPEED, 6000, 0));
            bot.setRemainingFireTicks(200);
            require(context, bot.getActiveEffects().size() == 2, "fixture: the effects were not applied");

            require(context, AIPlayerManager.INSTANCE.respawnDeadBot(bot), "respawnDeadBot did not revive the bot");

            require(context, bot.getHealth() == 20.0F, "health " + bot.getHealth());
            require(context, bot.getFoodData().getFoodLevel() == 20, "food " + bot.getFoodData().getFoodLevel());
            require(context, bot.getFoodData().getSaturationLevel() == 5.0F,
                    "saturation " + bot.getFoodData().getSaturationLevel());
            require(context, bot.getActiveEffects().isEmpty(),
                    "effects survived the respawn: " + bot.getActiveEffects());
            require(context, bot.experienceLevel == 0 && bot.experienceProgress == 0.0F && bot.totalExperience == 0,
                    "xp survived the respawn (duplicated next to the dropped orbs): level " + bot.experienceLevel
                            + " progress " + bot.experienceProgress + " total " + bot.totalExperience);
            require(context, bot.getRemainingFireTicks() <= 0, "fire " + bot.getRemainingFireTicks());
        } finally {
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
        }
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
