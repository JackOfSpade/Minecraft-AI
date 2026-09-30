package io.github.zoyluo.minecraftai.task;

import com.google.gson.JsonObject;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.brain.ToolDefinition;
import io.github.zoyluo.minecraftai.brain.ToolRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.GameTestChunkForcing;
import io.github.zoyluo.minecraftai.gametest.GameTestCleanup;
import io.github.zoyluo.minecraftai.gametest.MockPlayers;
import io.github.zoyluo.minecraftai.gametest.PerceptionFixtures;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.observe.BotProfiler;
import io.github.zoyluo.minecraftai.perception.CreaturePerception;
import io.github.zoyluo.minecraftai.perception.CreatureSenses;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Live proofs of the companions' realistic perception (docs/PERCEPTION.md): a bot notices a creature only when it sees it (its view
 * cone, a clear line) for the reaction time, or hears it (vanilla vibrations, radius 16) with a clear line, or is struck by it. The
 * whole suite runs with perception ON now; these tests prove the model itself, the scan cost and the fail-safe.
 *
 * <p>The arena is a stone strip; the bot stands at the origin looking south (+Z), "behind" is north (-Z). Every test has its own
 * environment (its own batch: the perception switch is global).
 */
public final class CompanionPerceptionGameTests {
    private static final String ENV = "minecraftai-gametest:companion_perception_game_tests_";
    /** Own layer of the arena (other suites use 0..45, 74, 86, 100, 110, 194 and 206). */
    private static final int LAYER_Y = 122;
    private static final int HALF_X = 6;
    private static final int BEHIND = 24;
    private static final int AHEAD = 24;

    // ------------------------------------------------------------------ a zombie behind

    @GameTest(environment = ENV + "silent_zombie_behind_is_never_noticed_until_it_enters_the_cone", maxTicks = 200)
    public void silentZombieBehindIsNeverNoticedUntilItEntersTheCone(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("PerceptionZombieGT", 0, 0);
        f.hold(bot);
        Zombie zombie = f.zombie(0, -6);
        int[] enteredCone = {-1};
        int[] noticedAt = {-1};
        context.onEachTick(() -> {
            int tick = (int) context.getTick();
            boolean noticed = CreatureSenses.INSTANCE.noticed(bot, zombie);
            if (tick < 90) {
                f.require(!noticed, "a silent zombie 6 blocks BEHIND was noticed at tick " + tick + " ("
                        + CreatureSenses.INSTANCE.howNoticed(bot, zombie) + ")");
                f.require(tick < 30 || ObservableWorldQuery.canObserveEntity(bot, zombie),
                        "control: today's omnidirectional test must see this zombie");
                return;
            }
            if (enteredCone[0] < 0) {
                // The bot turns its head toward the zombie: it enters the view cone now.
                LookAction.lookAt(bot, zombie.getEyePosition());
                enteredCone[0] = tick;
                f.require(!noticed, "the zombie was noticed before it entered the cone");
                return;
            }
            if (noticed && noticedAt[0] < 0) {
                noticedAt[0] = tick;
            }
            int since = tick - enteredCone[0];
            double required = CreaturePerception.requiredSeconds(CreaturePerception.Params.defaults(), 0.0D,
                    bot.getEyePosition().distanceTo(zombie.getEyePosition()), CreaturePerception.Subject.of(false), true);
            int expected = (int) CreaturePerception.noticeTick(required);
            if (since < expected) {
                f.require(!noticed, "noticed after " + since + " ticks in the cone, before the reaction time of " + expected);
            }
            if (since > expected + 4) {
                f.require(noticedAt[0] >= 0, "not noticed " + since + " ticks after entering the cone (reaction time " + expected + ")");
                f.require(noticedAt[0] - enteredCone[0] >= expected,
                        "noticed too early: " + (noticedAt[0] - enteredCone[0]) + " ticks, reaction time " + expected);
                f.require(CreatureSenses.INSTANCE.howNoticed(bot, zombie).filter(h -> h == CreatureSenses.How.SIGHT).isPresent(),
                        "the zombie in the cone was not noticed by sight: " + CreatureSenses.INSTANCE.howNoticed(bot, zombie));
                f.finish();
            }
        });
    }

    @GameTest(environment = ENV + "walking_zombie_behind_is_noticed_by_its_footsteps_within_hearing_range", maxTicks = 260)
    public void walkingZombieBehindIsNoticedByItsFootstepsWithinHearingRange(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("PerceptionStepsGT", 0, 0);
        f.hold(bot);
        Husk husk = f.walkingHusk(0, -20);
        int[] inRange = {-1};
        context.onEachTick(() -> {
            int tick = (int) context.getTick();
            double distance = bot.distanceTo(husk);
            boolean noticed = CreatureSenses.INSTANCE.noticed(bot, husk);
            if (inRange[0] < 0 && distance <= 16.0D) {
                inRange[0] = tick;
            }
            Vec3 look = bot.getViewVector(1.0F);
            Vec3 toward = husk.getEyePosition().subtract(bot.getEyePosition());
            double theta = CreaturePerception.angleDeg(look.x, look.y, look.z, toward.x, toward.y, toward.z);
            if (!noticed) {
                f.require(bot.getHealth() >= bot.getMaxHealth() && distance > 1.5D,
                        "the walking zombie reached the bot without being noticed (distance " + distance + ")");
                return;
            }
            f.require(distance <= 17.5D, "noticed beyond the hearing range: " + distance);
            f.require(theta > 100.0D, "the zombie was in the view field, not just heard: " + theta);
            f.require(CreatureSenses.INSTANCE.howNoticed(bot, husk).filter(h -> h == CreatureSenses.How.HEARING).isPresent(),
                    "not noticed by hearing: " + CreatureSenses.INSTANCE.howNoticed(bot, husk));
            f.require(inRange[0] >= 0 && tick - inRange[0] >= 10,
                    "noticed within " + (tick - inRange[0]) + " ticks of coming in range: the reaction time is 0.5 s at least");
            f.require(bot.getHealth() >= bot.getMaxHealth(), "the zombie hit the bot before it was noticed");
            f.finish();
        });
    }

    @GameTest(environment = ENV + "noticed_zombie_from_behind_makes_the_watcher_take_up_the_fight", maxTicks = 320)
    public void noticedZombieFromBehindMakesTheWatcherTakeUpTheFight(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("PerceptionFightGT", 0, 0);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_SWORD));
        f.hold(bot);
        Husk husk = f.walkingHusk(0, -20);
        context.onEachTick(() -> {
            boolean noticed = CreatureSenses.INSTANCE.noticed(bot, husk);
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            boolean reacting = active instanceof CombatTask || active instanceof EvadeTask;
            if (!noticed) {
                f.require(!reacting, "the watcher reacted to a zombie the bot had not noticed: " + (active == null ? "no task" : active.describe()));
                f.require(bot.getHealth() >= bot.getMaxHealth(), "the zombie hit the bot before it was noticed");
                return;
            }
            // Noticed: the bot's own safety takes over (it turns round and fights, at human speed) before the zombie lands a blow.
            if (reacting) {
                f.finish();
            } else if (bot.getHealth() < bot.getMaxHealth()) {
                f.require(false, "the noticed zombie hit the bot and the watcher never took up the fight: "
                        + (active == null ? "no task" : active.describe()));
            }
        });
    }

    // ------------------------------------------------------------------ a creeper behind

    @GameTest(environment = ENV + "creeper_behind_is_noticed_by_its_hiss_not_before", maxTicks = 200)
    public void creeperBehindIsNoticedByItsHissNotBefore(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("PerceptionCreeperGT", 0, 0);
        f.hold(bot);
        Creeper creeper = f.creeper(0, -6);
        int[] primedAt = {-1};
        context.onEachTick(() -> {
            int tick = (int) context.getTick();
            boolean noticed = CreatureSenses.INSTANCE.noticed(bot, creeper);
            if (tick < 70) {
                f.require(!noticed, "a silent, unprimed creeper 6 blocks BEHIND was noticed at tick " + tick);
                return;
            }
            if (primedAt[0] < 0) {
                creeper.ignite();
                primedAt[0] = tick;
                return;
            }
            int since = tick - primedAt[0];
            if (noticed) {
                f.require(since >= 10, "the hiss was noticed after only " + since + " ticks (reaction time is 0.5 s at least)");
                f.require(creeper.isAlive() && creeper.getSwelling(1.0F) < 1.0F, "the creeper had already blown up");
                f.require(CreatureSenses.INSTANCE.howNoticed(bot, creeper).filter(h -> h == CreatureSenses.How.HEARING).isPresent(),
                        "not noticed by hearing: " + CreatureSenses.INSTANCE.howNoticed(bot, creeper));
                creeper.discard();
                f.finish();
                return;
            }
            if (since > 26) {
                creeper.discard();
                f.require(false, "the primed creeper behind was not noticed by its hiss within " + since + " ticks");
            }
        });
    }

    // ------------------------------------------------------------------ a sneaking hostile player

    @GameTest(environment = ENV + "sneaking_hostile_player_behind_is_not_noticed_until_it_hits", maxTicks = 160)
    public void sneakingHostilePlayerBehindIsNotNoticedUntilItHits(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("PerceptionSneakGT", 0, 0);
        f.hold(bot);
        ServerPlayer foreign = f.foreign(0, -2);
        foreign.setShiftKeyDown(true);
        int[] hitAt = {-1};
        context.onEachTick(() -> {
            int tick = (int) context.getTick();
            boolean noticed = CreatureSenses.INSTANCE.noticed(bot, foreign);
            if (hitAt[0] < 0) {
                f.require(!noticed, "a sneaking player 2 blocks BEHIND was noticed at tick " + tick + " before it hit");
                f.require(tick < 20 || ObservableWorldQuery.canObserveEntity(bot, foreign),
                        "control: today's omnidirectional test must see this player");
                if (tick == 80) {
                    float before = bot.getHealth();
                    boolean applied = bot.hurtServer(f.level, f.level.damageSources().playerAttack(foreign), 2.0F);
                    f.require(applied && bot.getHealth() < before, "the hit was not real");
                    hitAt[0] = tick;
                }
                return;
            }
            f.require(noticed, "the player that hit the bot is still unknown to it");
            f.require(CreatureSenses.INSTANCE.howNoticed(bot, foreign).filter(h -> h == CreatureSenses.How.HIT).isPresent(),
                    "not known by the blow: " + CreatureSenses.INSTANCE.howNoticed(bot, foreign));
            f.finish();
        });
    }

    // ------------------------------------------------------------------ the owner sees

    @GameTest(environment = ENV + "owner_sees_still_nominates_a_foreign_aggressor_the_bot_has_not_noticed", maxTicks = 100)
    public void ownerSeesStillNominatesAForeignAggressorTheBotHasNotNoticed(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("PerceptionOwnerGT", 0, 0);
        f.hold(bot);
        ServerPlayer owner = f.owner(bot, 4, 4);
        ServerPlayer foreign = f.foreign(0, -5);
        f.require(owner.hurtServer(f.level, f.level.damageSources().playerAttack(foreign), 2.0F), "the hit on the owner was not real");
        f.lookAtPoint(owner, new Vec3(owner.getX(), owner.getEyeY(), f.z(40.0D)));
        int[] step = {0};
        context.onEachTick(() -> {
            int tick = (int) context.getTick();
            f.require(!CreatureSenses.INSTANCE.noticed(bot, foreign), "the bot noticed a silent player behind it at tick " + tick);
            HostileBotLedger.invalidateVisionCache();
            if (step[0] == 0) {
                // The owner looks away: nobody sees the aggressor, so it is no target.
                f.require(!SharedVision.ownerSees(bot, foreign) && !SharedVision.seenByBotOrOwner(bot, foreign)
                                && !CombatCore.hostileTo(bot, foreign),
                        "an aggressor nobody sees was nominated");
                f.face(owner, foreign);
                step[0] = 1;
                return;
            }
            f.face(owner, foreign);
            f.require(SharedVision.ownerSees(bot, foreign) && SharedVision.seenByBotOrOwner(bot, foreign)
                            && CombatCore.hostileTo(bot, foreign) && !CombatCore.isFriendly(bot, foreign),
                    "the owner looking at the aggressor did not nominate it");
            f.require(!CreatureSenses.INSTANCE.noticed(bot, foreign), "the bot itself noticed the aggressor behind it");
            if (tick >= 40) {
                f.finish();
            }
        });
    }

    // ------------------------------------------------------------------ the reaction time

    @GameTest(environment = ENV + "zombie_stepping_into_view_at_ten_blocks_is_noticed_after_the_reaction_time", maxTicks = 160)
    public void zombieSteppingIntoViewAtTenBlocksIsNoticedAfterTheReactionTime(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("PerceptionReactionGT", 0, 0);
        f.hold(bot);
        Zombie zombie = f.zombie(0, 10);
        f.wall(5);
        int[] openedAt = {-1};
        context.onEachTick(() -> {
            int tick = (int) context.getTick();
            boolean noticed = CreatureSenses.INSTANCE.noticed(bot, zombie);
            if (openedAt[0] < 0) {
                f.require(!noticed, "a zombie behind a wall was noticed at tick " + tick);
                if (tick == 30) {
                    f.clearWall(5);
                    openedAt[0] = tick;
                    double d = bot.getEyePosition().distanceTo(zombie.getEyePosition());
                    f.require(Math.abs(d - 10.0D) < 0.6D, "fixture: the zombie is not about 10 blocks away: " + d);
                }
                return;
            }
            int since = tick - openedAt[0];
            double required = CreaturePerception.requiredSeconds(CreaturePerception.Params.defaults(), 0.0D,
                    bot.getEyePosition().distanceTo(zombie.getEyePosition()), CreaturePerception.Subject.of(false), true);
            int expected = (int) CreaturePerception.noticeTick(required);
            f.require(expected >= 14 && expected <= 16, "the formula gives " + expected + " ticks at 10 blocks, not about 0.73 s");
            if (since < expected) {
                f.require(!noticed, "the zombie was noticed " + since + " ticks after stepping into view; the reaction time is "
                        + expected + " ticks (" + required + " s)");
                f.require(!SharedVision.seenByBotOrOwner(bot, zombie), "the zombie was engaged before the reaction time");
                return;
            }
            if (noticed) {
                f.require(since <= expected + 3, "noticed late: " + since + " ticks, reaction time " + expected);
                f.finish();
            } else if (since > expected + 3) {
                f.require(false, "the zombie in plain view was not noticed after " + since + " ticks");
            }
        });
    }

    // ------------------------------------------------------------------ the LLM attack tool

    /**
     * A cow is no threat and no scan subject: animals keep omnidirectional observation, so the one-shot tool still works with the bot
     * facing away (a hostile that is noticed is the safety layer's business: see the busy test).
     */
    @GameTest(environment = ENV + "llm_attack_tool_hits_a_cow_behind_the_bot_that_faces_away", maxTicks = 140)
    public void llmAttackToolHitsACowBehindTheBotThatFacesAway(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("PerceptionToolGT", 0, 0);
        Cow husk = f.cow(0, -3);
        PerceptionFixtures.faceAway(bot, husk);
        PerceptionFixtures.afterNoticed(context, bot, List.of(husk), () -> {
            f.require(CreatureSenses.INSTANCE.noticed(bot, husk) && PerceptionFixtures.angleTo(bot, husk) > 100.0D,
                    "fixture: the cow must be behind the bot and known to it");
            float before = husk.getHealth();
            JsonObject args = new JsonObject();
            args.addProperty("entity_type", "minecraft:cow");
            ToolDefinition tool = new ToolRegistry().get("attack_entity").orElseThrow();
            ToolDefinition.ToolResult result = tool.handler().invoke(bot, args);
            f.require(result != null && result.ok(), "the attack tool refused: " + (result == null ? "null" : result.message()));
            f.require(husk.getHealth() >= before || result.message().contains("hit"),
                    "the tool claimed a hit that did not land: " + result.message());
            int from = (int) context.getTick();
            PerceptionFixtures.everyTick(context, () -> {
                if (husk.getHealth() < before) {
                    f.finish();
                } else if (context.getTick() - from >= 60) {
                    f.require(false, "the tool's attack never landed although the bot only had to turn round: " + result.message()
                            + " task=" + TaskManager.INSTANCE.getActive(bot).map(Task::describe).orElse("none"));
                }
            });
        });
    }

    @GameTest(environment = ENV + "llm_attack_tool_does_not_reveal_a_mob_the_bot_has_not_noticed", maxTicks = 60)
    public void llmAttackToolDoesNotRevealAMobTheBotHasNotNoticed(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("PerceptionToolBlindGT", 0, 0);
        f.hold(bot);
        Husk husk = f.zombieLike(0, -2);
        context.onEachTick(() -> {
            f.require(!CreatureSenses.INSTANCE.noticed(bot, husk), "fixture: the silent mob behind the bot was noticed");
            if (context.getTick() < 20) {
                return;
            }
            f.require(ObservableWorldQuery.canObserveEntity(bot, husk), "control: the mob is right there, in plain omnidirectional sight");
            JsonObject args = new JsonObject();
            args.addProperty("entity_type", "minecraft:husk");
            ToolDefinition.ToolResult result = new ToolRegistry().get("attack_entity").orElseThrow().handler().invoke(bot, args);
            f.require(result != null && !result.ok() && result.message().startsWith("no_nearby_entity"),
                    "the reply revealed or attacked a mob the bot has not noticed: " + (result == null ? "null" : result.message()));
            f.require(husk.getHealth() >= husk.getMaxHealth(), "an unnoticed mob was struck");
            f.require(TaskManager.INSTANCE.getActive(bot).filter(t -> t instanceof AttackEntityTask).isEmpty(),
                    "an attack was started on a mob the bot has not noticed");
            f.finish();
        });
    }

    @GameTest(environment = ENV + "llm_attack_tool_is_busy_instead_of_replacing_a_running_task", maxTicks = 140)
    public void llmAttackToolIsBusyInsteadOfReplacingARunningTask(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("PerceptionToolBusyGT", 0, 0);
        HoldingTask mission = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, mission, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_attack_busy"));
        net.minecraft.world.entity.animal.cow.Cow husk = f.cow(0, -3);
        PerceptionFixtures.faceAway(bot, husk);
        PerceptionFixtures.afterNoticed(context, bot, List.of(husk), () -> {
            JsonObject args = new JsonObject();
            args.addProperty("entity_type", "minecraft:cow");
            ToolDefinition tool = new ToolRegistry().get("attack_entity").orElseThrow();
            float yaw = bot.getYRot();
            float pitch = bot.getXRot();
            ToolDefinition.ToolResult result = tool.handler().invoke(bot, args);
            f.require(bot.getYRot() == yaw && bot.getXRot() == pitch,
                    "a refused attack call turned the bot's head: yaw " + yaw + " -> " + bot.getYRot() + ", pitch " + pitch + " -> " + bot.getXRot());
            f.require(result != null && !result.ok() && result.message().startsWith("busy: ")
                            && result.message().contains("holding_work"),
                    "a running mission must be reported as busy, not replaced: " + (result == null ? "null" : result.message()));
            f.require(TaskManager.INSTANCE.getActive(bot).orElse(null) == mission, "the tool replaced the running mission");
            // A safety task is never interrupted either, and the refusal says so without keeping the request.
            HoldingTask safety = new HoldingTask();
            TaskManager.INSTANCE.assign(bot, safety, TaskOrigin.safety("gametest_attack_busy_safety"));
            result = tool.handler().invoke(bot, args);
            f.require(bot.getYRot() == yaw && bot.getXRot() == pitch, "a refused attack call during a safety task turned the bot's head");
            f.require(result != null && !result.ok() && result.message().startsWith("busy: ")
                            && result.message().contains("safety task"),
                    "a safety task must be reported as busy: " + (result == null ? "null" : result.message()));
            f.require(TaskManager.INSTANCE.getActive(bot).orElse(null) == safety, "the tool interrupted a safety task");
            f.finish();
        });
    }

    // ------------------------------------------------------------------ the fail-safe

    @GameTest(environment = ENV + "a_failing_scan_falls_back_to_omnidirectional_sight_never_blindness", maxTicks = 80)
    public void aFailingScanFallsBackToOmnidirectionalSightNeverBlindness(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("PerceptionFailSafeGT", 0, 0);
        f.hold(bot);
        Husk husk = f.zombieLike(0, -4);
        int[] step = {0};
        int[] faultFrom = {0};
        context.onEachTick(() -> {
            int tick = (int) context.getTick();
            if (step[0] == 0 && tick >= 10) {
                f.require(!CreatureSenses.INSTANCE.noticed(bot, husk), "fixture: the silent mob behind the bot was noticed");
                CreatureSenses.setScanFaultForTests(() -> {
                    throw new IllegalStateException("gametest injected scan failure");
                });
                faultFrom[0] = tick;
                step[0] = 1;
                return;
            }
            if (step[0] == 1 && tick >= faultFrom[0] + 3) {
                // The scan has thrown on every tick since: the bot is on the legacy test, it can see what a plain line of sight sees.
                f.require(CreatureSenses.INSTANCE.noticed(bot, husk) && ObservableWorldQuery.canNoticeCreature(bot, husk),
                        "a failing scan left the bot blind to a creature in plain line of sight");
                CreatureSenses.setScanFaultForTests(null);
                faultFrom[0] = tick;
                step[0] = 2;
                return;
            }
            if (step[0] == 2 && tick >= faultFrom[0] + 4) {
                f.require(!CreatureSenses.INSTANCE.noticed(bot, husk),
                        "the bot stayed on the legacy test after the scan recovered: the mob behind it is unnoticed again");
                f.finish();
            }
        });
    }

    // ------------------------------------------------------------------ the cost of the scan

    /**
     * Five bots in a crowd (dozens of mobs: passive animals, villagers, zombies in front, zombies behind), the same scene measured
     * with the scan's cost limits off and on. The numbers go to the console ("PERCEPTION_COST"); the assertions are on what is
     * deterministic: the throttled scan casts fewer rays and every zombie in front is still noticed.
     */
    @GameTest(environment = ENV + "the_scan_cost_with_five_bots_in_a_crowd_is_throttled", maxTicks = 400)
    public void theScanCostWithFiveBotsInACrowdIsThrottled(GameTestHelper context) {
        Fixture f = new Fixture(context);
        List<AIPlayerEntity> bots = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            AIPlayerEntity bot = f.bot("PerceptionCrowdGT" + i, -4 + 2 * i, 0);
            f.hold(bot);
            bots.add(bot);
        }
        // Calm zombified piglins: creatures the scan reads (a NeutralMob is no "passive animal") but that the watchers leave alone, so
        // the bots stand still and the scene is the same in both measurements.
        List<LivingEntity> crowdInFront = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            crowdInFront.add(f.calmPiglin(-5 + (i % 10), 5 + (i / 10) * 3));
        }
        for (int i = 0; i < 10; i++) {
            f.calmPiglin(-5 + i, -8);
        }
        for (int i = 0; i < 24; i++) {
            net.minecraft.world.entity.animal.cow.Cow cow = EntityType.COW.create(f.level, EntitySpawnReason.COMMAND);
            cow.setNoAi(true);
            cow.setPersistenceRequired();
            f.add(cow, -5 + (i % 12), 12 + (i / 12) * 2);
        }
        for (int i = 0; i < 12; i++) {
            net.minecraft.world.entity.npc.villager.Villager villager = EntityType.VILLAGER.create(f.level, EntitySpawnReason.COMMAND);
            villager.setNoAi(true);
            villager.setPersistenceRequired();
            f.add(villager, -5 + i, -12);
        }
        double[] unthrottledMs = {0.0D};
        long[] unthrottledRays = {0L};
        long[] rays0 = {0L};
        context.onEachTick(() -> {
            int tick = (int) context.getTick();
            if (tick == 60) {
                // Everyone in front has been noticed by now: the scene is steady. Phase 1 runs with no cost limits at all.
                CreatureSenses.setThrottleForTests(false);
                bots.forEach(b -> BotProfiler.INSTANCE.clear(b.getUUID()));
                rays0[0] = CreatureSenses.raysCast();
            } else if (tick == 160) {
                unthrottledMs[0] = perTickMs(bots);
                unthrottledRays[0] = CreatureSenses.raysCast() - rays0[0];
                CreatureSenses.setThrottleForTests(true);
                bots.forEach(b -> BotProfiler.INSTANCE.clear(b.getUUID()));
                rays0[0] = CreatureSenses.raysCast();
            } else if (tick == 260) {
                double throttledMs = perTickMs(bots);
                long throttledRays = CreatureSenses.raysCast() - rays0[0];
                System.out.println("PERCEPTION_COST 5 bots, " + f.entityCount() + " mobs: unthrottled "
                        + String.format("%.3f", unthrottledMs[0]) + " ms/tick (" + unthrottledRays[0] / 100.0D
                        + " rays/tick), throttled " + String.format("%.3f", throttledMs) + " ms/tick (" + throttledRays / 100.0D
                        + " rays/tick)");
                for (AIPlayerEntity bot : bots) {
                    for (LivingEntity zombie : crowdInFront) {
                        f.require(CreatureSenses.INSTANCE.noticed(bot, zombie), "a calm piglin in the view was not noticed with the throttle on: bot "
                                + bot.getGameProfile().name() + " at " + bot.blockPosition().toShortString() + " task "
                                + TaskManager.INSTANCE.getActive(bot).map(Task::describe).orElse("none") + ", zombie at "
                                + zombie.blockPosition().toShortString() + " angle " + Math.round(PerceptionFixtures.angleTo(bot, zombie))
                                + " distance " + Math.round(bot.distanceTo(zombie)) + " clear=" + bot.hasLineOfSight(zombie));
                    }
                }
                f.require(throttledRays <= unthrottledRays[0], "the throttled scan cast more rays (" + throttledRays
                        + ") than the unthrottled (" + unthrottledRays[0] + ")");
                f.finish();
            }
        });
    }

    private static double perTickMs(List<AIPlayerEntity> bots) {
        double total = 0.0D;
        for (AIPlayerEntity bot : bots) {
            BotProfiler.Stat stat = BotProfiler.INSTANCE.snapshot(bot.getUUID()).get("perception_scan");
            total += stat == null ? 0.0D : stat.avgMs();
        }
        return total;
    }

    // ------------------------------------------------------------------ listeners

    @GameTest(environment = ENV + "hearing_listeners_are_removed_on_despawn_and_death", maxTicks = 80)
    public void hearingListenersAreRemovedOnDespawnAndDeath(GameTestHelper context) {
        Fixture f = new Fixture(context);
        int base = CreatureSenses.INSTANCE.listenerCount();
        AIPlayerEntity first = f.bot("PerceptionEarsAGT", 0, 0);
        AIPlayerEntity second = f.bot("PerceptionEarsBGT", 3, 0);
        int[] step = {0};
        context.onEachTick(() -> {
            int tick = (int) context.getTick();
            int count = CreatureSenses.INSTANCE.listenerCount();
            if (step[0] == 0 && tick >= 3) {
                f.require(count == base + 2, "two bots should have two listeners, found " + (count - base));
                second.kill(f.level);
                step[0] = 1;
            } else if (step[0] == 1) {
                f.require(count <= base + 1, "a dead bot kept its listener: " + (count - base));
                step[0] = 2;
            } else if (step[0] == 2) {
                AIPlayerManager.INSTANCE.despawn(f.level.getServer(), "PerceptionEarsAGT");
                f.require(CreatureSenses.INSTANCE.listenerCount() <= base + 1,
                        "a despawned bot kept its listener: " + (CreatureSenses.INSTANCE.listenerCount() - base));
                step[0] = 3;
            } else if (step[0] == 3) {
                f.require(CreatureSenses.INSTANCE.listenerCount() <= base + 1, "listeners leaked after despawn");
                f.finish();
            }
        });
    }

    @GameTest(environment = ENV + "hearing_listener_moves_with_the_bot_to_another_level", maxTicks = 60)
    public void hearingListenerMovesWithTheBotToAnotherLevel(GameTestHelper context) {
        Fixture f = new Fixture(context);
        int base = CreatureSenses.INSTANCE.listenerCount();
        AIPlayerEntity bot = f.bot("PerceptionLevelGT", 0, 0);
        ServerLevel nether = f.level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        f.require(nether != null, "fixture: the server has no Nether");
        int[] step = {0};
        context.onEachTick(() -> {
            int tick = (int) context.getTick();
            int count = CreatureSenses.INSTANCE.listenerCount();
            if (step[0] == 0 && tick >= 3) {
                f.require(count == base + 1 && CreatureSenses.INSTANCE.listenerLevel(bot).orElse(null) == f.level,
                        "the bot's listener is not registered in its own level: " + CreatureSenses.INSTANCE.listenerLevel(bot));
                // Above the Nether roof: nothing to fall into or suffocate in for the one tick it takes to look.
                bot.teleportTo(nether, f.x(0), 140.0D, f.z(0), Set.of(), 0.0F, 0.0F, true);
                step[0] = 1;
            } else if (step[0] == 1) {
                f.require(bot.level() == nether, "fixture: the bot did not arrive in the Nether");
                f.require(count == base + 1 && CreatureSenses.INSTANCE.listenerLevel(bot).orElse(null) == nether,
                        "the listener did not move with the bot to the other level (count " + (count - base) + ", level "
                                + CreatureSenses.INSTANCE.listenerLevel(bot).map(l -> l.dimension().identifier().toString()).orElse("none") + ")");
                f.finish();
            }
        });
    }

    // ------------------------------------------------------------------ fixture

    private static final class Fixture {
        final GameTestHelper context;
        final ServerLevel level;
        final BlockPos feet;
        private final List<String> botNames = new ArrayList<>();
        private final List<ServerPlayer> mocks = new ArrayList<>();
        private final List<Entity> entities = new ArrayList<>();
        private boolean finished;

        Fixture(GameTestHelper context) {
            this.context = context;
            this.level = context.getLevel();
            this.feet = context.absolutePos(new BlockPos(8, LAYER_Y, 8));
            level.setDayTime(1000L);
            GameTestChunkForcing.forceForTest(context,
                    (feet.getX() - HALF_X) >> 4, (feet.getX() + HALF_X) >> 4,
                    (feet.getZ() - BEHIND) >> 4, (feet.getZ() + AHEAD) >> 4);
            for (int dx = -HALF_X; dx <= HALF_X; dx++) {
                for (int dz = -BEHIND; dz <= AHEAD; dz++) {
                    level.setBlock(feet.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                    for (int dy = 0; dy <= 5; dy++) {
                        level.setBlock(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
            CreatureSenses.forceEnabledForTests(true);
            GameTestCleanup.whenFinished(context, this::cleanUp);
        }

        double x(double dx) {
            return feet.getX() + 0.5D + dx;
        }

        double z(double dz) {
            return feet.getZ() + 0.5D + dz;
        }

        void require(boolean condition, String message) {
            if (!condition) {
                context.fail(Component.nullToEmpty(message));
            }
        }

        AIPlayerEntity bot(String name, int dx, int dz) {
            AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                            level.getServer(), name, level, new Vec3(x(dx), feet.getY(), z(dz)), 0.0F, 0.0F, GameType.SURVIVAL)
                    .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
            botNames.add(name);
            if (!bot.connection.hasClientLoaded()) {
                bot.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            }
            bot.setHealth(bot.getMaxHealth());
            bot.getFoodData().setFoodLevel(20);
            return bot;
        }

        /** The bot keeps still and keeps its head where it is (a task that does nothing, like a miner facing its ore face). */
        void hold(AIPlayerEntity bot) {
            TaskManager.INSTANCE.assign(bot, new HoldingTask(), TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_perception"));
        }

        <T extends Entity> T add(T entity, double dx, double dz) {
            entity.snapTo(x(dx), feet.getY(), z(dz), 0.0F, 0.0F);
            level.addFreshEntity(entity);
            entities.add(entity);
            return entity;
        }

        Zombie zombie(int dx, int dz) {
            Zombie zombie = EntityType.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
            zombie.setPersistenceRequired();
            zombie.setNoAi(true);
            zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET)); // no sun burn in the daylight arena
            return add(zombie, dx, dz);
        }

        /** A husk (the sun does not burn it) without AI: it stands, silent. */
        Husk zombieLike(int dx, int dz) {
            Husk husk = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
            husk.setPersistenceRequired();
            husk.setNoAi(true);
            return add(husk, dx, dz);
        }

        Cow cow(int dx, int dz) {
            Cow cow = EntityType.COW.create(level, EntitySpawnReason.COMMAND);
            cow.setPersistenceRequired();
            cow.setNoAi(true);
            return add(cow, dx, dz);
        }

        /** A calm zombified piglin without AI: a creature the scan reads, and no threat. */
        net.minecraft.world.entity.monster.zombie.ZombifiedPiglin calmPiglin(int dx, int dz) {
            net.minecraft.world.entity.monster.zombie.ZombifiedPiglin piglin =
                    EntityType.ZOMBIFIED_PIGLIN.create(level, EntitySpawnReason.COMMAND);
            piglin.setPersistenceRequired();
            piglin.setNoAi(true);
            return add(piglin, dx, dz);
        }

        /** A husk with its own AI: it walks to the nearest player. */
        Husk walkingHusk(int dx, int dz) {
            Husk husk = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
            husk.setPersistenceRequired();
            return add(husk, dx, dz);
        }

        Creeper creeper(int dx, int dz) {
            Creeper creeper = EntityType.CREEPER.create(level, EntitySpawnReason.COMMAND);
            creeper.setPersistenceRequired();
            creeper.setNoAi(true);
            return add(creeper, dx, dz);
        }

        ServerPlayer owner(AIPlayerEntity bot, int dx, int dz) {
            ServerPlayer owner = MockPlayers.ownerFor(context, bot);
            mocks.add(owner);
            place(owner, dx, dz);
            return owner;
        }

        /** A foreign bot: a survival mock that owns no Minecraft-AI bot. */
        ServerPlayer foreign(int dx, int dz) {
            ServerPlayer mock = MockPlayers.survivalMock(context);
            mocks.add(mock);
            mock.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
            place(mock, dx, dz);
            return mock;
        }

        void place(ServerPlayer player, double dx, double dz) {
            player.teleportTo(level, x(dx), feet.getY(), z(dz), Set.of(), player.getYRot(), player.getXRot(), true);
        }

        void face(ServerPlayer looker, Entity target) {
            MockPlayers.faceTowards(looker, target);
        }

        void lookAtPoint(ServerPlayer looker, Vec3 point) {
            looker.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, point);
            looker.setYHeadRot(looker.getYRot());
        }

        /** A stone wall across the whole arena {@code dz} blocks south of the centre, four blocks high. */
        void wall(int dz) {
            for (int dx = -HALF_X; dx <= HALF_X; dx++) {
                for (int dy = 0; dy <= 3; dy++) {
                    level.setBlock(feet.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }

        void clearWall(int dz) {
            for (int dx = -HALF_X; dx <= HALF_X; dx++) {
                for (int dy = 0; dy <= 3; dy++) {
                    level.setBlock(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }

        int entityCount() {
            return entities.size();
        }

        void finish() {
            if (!finished) {
                finished = true;
                context.succeed();
            }
        }

        private void cleanUp() {
            CreatureSenses.forceEnabledForTests(false);
            CreatureSenses.setThrottleForTests(true);
            CreatureSenses.setScanFaultForTests(null);
            for (Entity entity : entities) {
                entity.discard();
            }
            for (ServerPlayer mock : mocks) {
                HostileBotLedger.clearAggressor(mock.getUUID());
                HostileBotIntent.forget(mock.getUUID());
                try {
                    mock.connection.onDisconnect(new DisconnectionDetails(Component.literal("gametest done")));
                } catch (RuntimeException failed) {
                    mock.discard();
                }
            }
            for (String name : botNames) {
                AIPlayerManager.INSTANCE.despawn(level.getServer(), name);
            }
            AggroSense.clearAll();
            CreatureSenses.INSTANCE.clearAll();
        }
    }

    private static final class HoldingTask extends AbstractTask {
        @Override
        public String name() {
            return "holding_work";
        }

        @Override
        public String describe() {
            return "Holding still, facing its work";
        }

        @Override
        public double progress() {
            return 0.5D;
        }

        @Override
        protected void onStart(AIPlayerEntity bot) {
        }

        @Override
        protected void onTick(AIPlayerEntity bot) {
        }
    }
}
