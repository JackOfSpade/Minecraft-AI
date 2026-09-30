package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.InteractAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.StrikeLegality;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.RecentDamage;
import io.github.zoyluo.minecraftai.gametest.GameTestCleanup;
import io.github.zoyluo.minecraftai.gametest.MockPlayers;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.network.PlayerKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Live proofs of R1: Minecraft-AI bots never target their owner or another Minecraft-AI bot, but DO fight a foreign bot (a fake
 * player that is not ours, such as a PvP BOT inhabitant; here a survival mock player on an EmbeddedChannel, which
 * {@code PlayerKind.isBot} classes as a bot exactly like PvP BOT's) once it has hit, or exclusively aimed at, the owner or a
 * Minecraft-AI bot, and only while the bot or its owner can see it.
 *
 * <p>Every test has its own environment (its own batch, so tests never run side by side: the ledger is global) and cleans up the mock
 * players it made (a mock is never ticked, so a swing or a drawn bow left on one would stay "happening" for later tests). Mock players
 * are not ticked: the tests drive their swing/draw state with {@link MockPlayers} and every time in these tests is level game time.
 */
public final class HostileBotTargetingGameTests {
    private static final String ENV = "minecraftai-gametest:hostile_bot_targeting_game_tests_";
    /** Relative height of the arena (own layer: other suites use 0..45, 74, 86, 100, 194 and 206). */
    private static final int LAYER_Y = 110;
    private static final int HALF = 7;

    // ------------------------------------------------------------------ the R1 rules

    @GameTest(environment = ENV + "foreign_bot_that_hits_the_owner_becomes_a_visible_target", maxTicks = 80)
    public void foreignBotThatHitsTheOwnerBecomesAVisibleTarget(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("HbtOwnerHitGT", 0, 0);
        ServerPlayer owner = f.owner(bot, -3, 3);
        ServerPlayer foreign = f.foreign(3, 0);
        f.face(foreign, bot);

        // MockPlayers.ownerFor registers the owner the way AIPlayerManager.spawn(..., owner) does.
        f.require(AIPlayerManager.INSTANCE.ownerOf(bot).filter(owner.getUUID()::equals).isPresent()
                        && AIPlayerManager.INSTANCE.isAnyBotOwner(owner.getUUID())
                        && AIPlayerManager.INSTANCE.botsOf(owner.getUUID()).contains(bot),
                "MockPlayers.ownerFor did not register the owner");
        f.require(PlayerKind.isBot(foreign) && !(foreign instanceof AIPlayerEntity) && !AIPlayerManager.INSTANCE.isAnyBotOwner(foreign.getUUID()),
                "the foreign mock is not a markable foreign bot");
        f.require(CombatCore.isFriendly(bot, foreign) && !CombatCore.hostileTo(bot, foreign),
                "an armed foreign bot that did nothing must be friendly");

        float ownerBefore = owner.getHealth();
        boolean applied = owner.hurtServer(f.level, f.level.damageSources().playerAttack(foreign), 2.0F);
        f.require(applied && owner.getHealth() < ownerBefore, "the hit on the owner was not real: applied=" + applied);
        long now = f.level.getGameTime();
        f.require(HostileBotLedger.isMarked(foreign.getUUID(), now), "the foreign bot that hit the owner was not marked");
        f.require(!CombatCore.isFriendly(bot, foreign) && CombatCore.hostileTo(bot, foreign),
                "a visible marked aggressor must not be friendly and must be hostile");
        f.require(CombatCore.isFriendly(bot, owner) && !CombatCore.hostileTo(bot, owner), "the owner became a target");

        DangerWatcher.INSTANCE.scanBot(f.level.getServer(), bot);
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        f.require(active instanceof CombatTask combat && combat.defensiveTarget() == foreign || active instanceof EvadeTask,
                "DangerWatcher did not react to the marked foreign bot: " + (active == null ? "no task" : active.describe()));

        // In reach and in the open, a strike lands.
        f.place(foreign, 1.5D, 0.0D);
        f.require(StrikeLegality.strikeRefusal(bot, foreign) == null,
                "the strike on a visible marked aggressor is refused: " + StrikeLegality.strikeRefusal(bot, foreign));
        float before = foreign.getHealth();
        ActionResult hit = InteractAction.attackEntity(bot, foreign);
        f.require(hit.isSuccess() && foreign.getHealth() < before, "the strike did not hurt the aggressor: " + hit.reason());
        f.finish();
    }

    @GameTest(environment = ENV + "foreign_bot_that_hits_asibling_bot_is_targeted_by_the_other_bot", maxTicks = 60)
    public void foreignBotThatHitsASiblingBotIsTargetedByTheOtherBot(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity first = f.bot("HbtSiblingAGT", 0, 0);
        AIPlayerEntity second = f.bot("HbtSiblingBGT", 0, 5);
        ServerPlayer owner = f.owner(first, -4, 0);
        MockPlayers.ownerFor(owner, second);
        ServerPlayer foreign = f.foreign(3, 0);
        f.require(CombatCore.isFriendly(first, foreign) && !CombatCore.hostileTo(first, foreign), "the foreign bot started hostile");

        float before = second.getHealth();
        f.require(second.hurtServer(f.level, f.level.damageSources().playerAttack(foreign), 2.0F) && second.getHealth() < before,
                "the hit on the sibling bot was not real");
        f.require(HostileBotLedger.isMarked(foreign.getUUID(), f.level.getGameTime()), "hitting a sibling bot did not mark the foreign bot");
        f.require(CombatCore.hostileTo(first, foreign) && !CombatCore.isFriendly(first, foreign),
                "the other bot does not treat the aggressor of its sibling as hostile");
        f.require(CombatCore.isFriendly(first, second) && CombatCore.isFriendly(second, first), "sibling bots stopped being friends");
        f.finish();
    }

    @GameTest(environment = ENV + "foreign_bot_that_hits_another_owners_bot_is_targeted", maxTicks = 60)
    public void foreignBotThatHitsAnotherOwnersBotIsTargeted(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity ours = f.bot("HbtOwnerOneGT", 0, 0);
        AIPlayerEntity theirs = f.bot("HbtOwnerTwoGT", 0, 5);
        ServerPlayer firstOwner = f.owner(ours, -4, 0);
        ServerPlayer secondOwner = f.owner(theirs, -4, 5);
        ServerPlayer foreign = f.foreign(3, 0);

        float before = theirs.getHealth();
        f.require(theirs.hurtServer(f.level, f.level.damageSources().playerAttack(foreign), 2.0F) && theirs.getHealth() < before,
                "the hit on the other owner's bot was not real");
        f.require(HostileBotLedger.isMarked(foreign.getUUID(), f.level.getGameTime()), "the ledger is not global: the hit on another owner's bot did not mark");
        f.require(CombatCore.hostileTo(ours, foreign), "our bot ignores the aggressor of another owner's bot");
        f.require(CombatCore.isFriendly(ours, theirs) && CombatCore.isFriendly(ours, firstOwner),
                "another owner's bot or our owner became a target");
        // The other owner is a human that owns a bot: not a foreign bot, not friendly by the bot rule, and not hostile either.
        f.require(!HostileBotLedger.isMarkableForeignBot(secondOwner), "a bot owner is markable");
        f.finish();
    }

    @GameTest(environment = ENV + "passive_armed_foreign_bot_stays_friendly", maxTicks = 260)
    public void passiveArmedForeignBotStaysFriendly(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("HbtPassiveGT", 0, 0);
        ServerPlayer owner = f.owner(bot, -5, 5);
        ServerPlayer foreign = f.foreign(4, 0);
        foreign.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        f.lookAtPoint(foreign, new Vec3(f.x(20.0D), f.feet.getY() + 1.6D, f.z(0.0D)));
        context.onEachTick(() -> {
            long t = context.getTick();
            if (t == 100) {
                f.face(foreign, bot); // now facing the bot at 4 blocks, armed, but standing still
            }
            if (t >= 2) {
                f.require(CombatCore.isFriendly(bot, foreign) && !CombatCore.hostileTo(bot, foreign),
                        "a passive armed foreign bot became a target at tick " + t);
                long now = f.level.getGameTime();
                f.require(!HostileBotLedger.isMarked(foreign.getUUID(), now) && !HostileBotLedger.isSuspect(foreign.getUUID(), now),
                        "a passive armed foreign bot was marked or suspected at tick " + t);
            }
            if (t % 20 == 10) {
                DangerWatcher.INSTANCE.scanBot(f.level.getServer(), bot);
                Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
                f.require(!(active instanceof CombatTask) && !(active instanceof EvadeTask),
                        "a passive armed foreign bot triggered a threat task: " + (active == null ? "" : active.describe()));
                f.require(!AggroSense.snapshot(bot).pressure(), "a passive armed foreign bot counted as pressure at tick " + t);
            }
            if (t >= 200) {
                f.finish();
            }
        });
    }

    @GameTest(environment = ENV + "pvp_bot_swinging_at_zombie_beside_owner_is_never_marked", maxTicks = 160)
    public void pvpBotSwingingAtZombieBesideOwnerIsNeverMarked(GameTestHelper context) {
        Fixture f = new Fixture(context);
        // The bot stands far from the husk (outside the hostile pressure envelope): it must not go and kill the zombie, which would
        // leave nothing beside the owner.
        AIPlayerEntity bot = f.bot("HbtZombieSwingGT", 7, 7);
        ServerPlayer owner = f.owner(bot, -3, -4);
        ServerPlayer foreign = f.foreign(-6, -4);
        foreign.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        Husk husk = f.husk(-4, -4);
        f.face(foreign, husk);
        boolean[] suspectedAfterZombie = {false};
        context.onEachTick(() -> {
            long t = context.getTick();
            long now = f.level.getGameTime();
            if (t < 100) {
                if (t % 10 == 1) {
                    MockPlayers.endSwing(foreign);
                } else if (t % 10 == 5) {
                    MockPlayers.swingOnce(foreign);
                }
                f.require(!HostileBotLedger.isMarked(foreign.getUUID(), now) && !HostileBotLedger.isSuspect(foreign.getUUID(), now),
                        "a bot fighting a zombie beside the owner was marked or suspected at tick " + t);
                f.require(CombatCore.isFriendly(bot, foreign), "a bot fighting a zombie beside the owner became a target at tick " + t);
            } else if (t == 100) {
                MockPlayers.endSwing(foreign);
                husk.discard();
                f.face(foreign, owner); // control: the same swing at the owner with no mob around is intent
            } else if (t == 105) {
                MockPlayers.swingOnce(foreign);
            } else if (t > 105 && t <= 115) {
                suspectedAfterZombie[0] |= HostileBotLedger.isSuspect(foreign.getUUID(), now);
            }
            if (t == 116) {
                f.require(suspectedAfterZombie[0], "control failed: a swing at the owner with no mob around was not even suspected");
                f.require(!HostileBotLedger.isMarked(foreign.getUUID(), now) && CombatCore.isFriendly(bot, foreign),
                        "a mere swing marked the foreign bot or made it a target");
                f.finish();
            }
        });
    }

    @GameTest(environment = ENV + "armed_charge_is_suspect_but_not_a_target", maxTicks = 120)
    public void armedChargeIsSuspectButNotATarget(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("HbtChargeGT", 0, 0);
        ServerPlayer owner = f.owner(bot, -6, 6);
        ServerPlayer foreign = f.foreign(7, 0);
        foreign.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        boolean[] suspected = {false};
        int[] suspectedAt = {-1};
        context.onEachTick(() -> {
            long t = context.getTick();
            long now = f.level.getGameTime();
            if (t >= 1 && t <= 20) {
                f.place(foreign, Math.max(3.0D, 7.0D - 0.2D * t), 0.0D); // 0.2 blocks per tick from 7 to 3 blocks
            }
            f.face(foreign, bot);
            if (!suspected[0] && t >= 1 && t <= 30 && HostileBotLedger.isSuspect(foreign.getUUID(), now)) {
                suspected[0] = true;
                suspectedAt[0] = (int) t;
                f.require(!HostileBotLedger.isMarked(foreign.getUUID(), now), "a charge marked the foreign bot");
                f.require(CombatCore.isFriendly(bot, foreign) && !CombatCore.hostileTo(bot, foreign),
                        "a merely suspect foreign bot became a target");
            }
            if (t == 26) {
                f.require(suspected[0], "an armed charge (7 to 3 blocks at 0.2 blocks per tick) was not suspected within 25 ticks");
            }
            if (t == 27) {
                AggroSense.Snapshot snapshot = AggroSense.snapshot(bot);
                f.require(snapshot.pressure() && snapshot.playerKindAggressor() && snapshot.aggressorCount() >= 1,
                        "a suspect foreign bot is not pressure: " + snapshot);
                f.require(CombatCore.isFriendly(bot, foreign), "the suspect became a target");
                float before = bot.getHealth();
                f.require(bot.hurtServer(f.level, f.level.damageSources().playerAttack(foreign), 2.0F) && bot.getHealth() < before,
                        "the hit on the bot was not real");
                f.require(HostileBotLedger.isMarked(foreign.getUUID(), f.level.getGameTime()),
                        "a suspect that hit the bot was not promoted to marked");
                f.require(!CombatCore.isFriendly(bot, foreign) && CombatCore.hostileTo(bot, foreign),
                        "a marked former suspect is not a target");
                f.finish();
            }
        });
    }

    @GameTest(environment = ENV + "exclusive_bow_aim_marks", maxTicks = 90)
    public void exclusiveBowAimMarks(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("HbtAimGT", -5, 5);
        ServerPlayer owner = f.owner(bot, 6, 0);
        ServerPlayer foreign = f.foreign(-2, 0);
        foreign.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        f.face(foreign, owner);
        // Phase A: a cow stands in the line of fire, nearer than the owner: the aim is not exclusive, nothing is marked.
        Cow cow = f.cow(1, 0);
        context.onEachTick(() -> {
            long t = context.getTick();
            long now = f.level.getGameTime();
            f.face(foreign, owner);
            if (t == 1) {
                MockPlayers.startDraw(foreign, InteractionHand.MAIN_HAND);
            }
            if (t == 24) {
                f.require(!HostileBotLedger.isMarked(foreign.getUUID(), now),
                        "a bow aimed past a nearer cow (not exclusive) marked the foreign bot");
                cow.discard();
            }
            if (t == 30) {
                f.require(!HostileBotLedger.isMarked(foreign.getUUID(), now), "the mark came before the draw was sustained");
            }
            if (t == 44) {
                f.require(HostileBotLedger.isMarked(foreign.getUUID(), now),
                        "a bow drawn at the owner with nothing in between for 20 ticks did not mark the foreign bot");
                f.require(HostileBotLedger.entry(foreign.getUUID()).reason().equals("aim"), "the mark reason is not aim");
                f.require(!CombatCore.isFriendly(bot, foreign) && CombatCore.hostileTo(bot, foreign),
                        "a marked archer that the owner and the bot see is not hostile");
                f.finish();
            }
        });
    }

    @GameTest(environment = ENV + "marked_aggressor_behind_walls_seen_by_nobody_is_not_targeted", maxTicks = 60)
    public void markedAggressorBehindWallsSeenByNobodyIsNotTargeted(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("HbtHiddenGT", 0, 0);
        ServerPlayer owner = f.owner(bot, -4, 0);
        ServerPlayer foreign = f.foreign(5, 0);
        f.wall(2);
        f.lookAtPoint(owner, new Vec3(f.x(-20.0D), owner.getEyeY(), f.z(0.0D))); // the owner looks away from everything
        f.require(owner.hurtServer(f.level, f.level.damageSources().playerAttack(foreign), 2.0F), "the hit on the owner was not real");
        long now = f.level.getGameTime();
        f.require(HostileBotLedger.isMarked(foreign.getUUID(), now), "the foreign bot was not marked");
        f.require(!bot.hasLineOfSight(foreign) && !SharedVision.ownerSees(bot, foreign), "the fixture is seen by someone");
        f.require(CombatCore.isFriendly(bot, foreign) && !CombatCore.hostileTo(bot, foreign),
                "a marked aggressor that nobody sees was targeted");
        DangerWatcher.INSTANCE.scanBot(f.level.getServer(), bot);
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        f.require(!(active instanceof CombatTask) && !(active instanceof EvadeTask),
                "an unseen aggressor triggered a threat task: " + (active == null ? "" : active.describe()));

        f.clearWall(2);
        f.require(bot.hasLineOfSight(foreign), "the wall did not open");
        f.require(!CombatCore.isFriendly(bot, foreign) && CombatCore.hostileTo(bot, foreign),
                "the marked aggressor is not a target once the wall opens");
        f.finish();
    }

    @GameTest(environment = ENV + "owner_seen_aggressor_is_nominated_but_never_struck_through_a_wall", maxTicks = 100)
    public void ownerSeenAggressorIsNominatedButNeverStruckThroughAWall(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("HbtOwnerSeesGT", 0, 0);
        ServerPlayer owner = f.owner(bot, 3, 5);
        ServerPlayer foreign = f.foreign(3, 0);
        f.wall(1);
        f.face(owner, foreign);
        f.require(owner.hurtServer(f.level, f.level.damageSources().playerAttack(foreign), 2.0F), "the hit on the owner was not real");
        f.face(owner, foreign);
        f.require(!bot.hasLineOfSight(foreign) && SharedVision.ownerSees(bot, foreign),
                "fixture: the bot must not see the foreign bot while the owner does");
        f.require(CombatCore.hostileTo(bot, foreign) && !CombatCore.isFriendly(bot, foreign),
                "an aggressor the owner is looking at was not nominated");
        String refusal = StrikeLegality.strikeRefusal(bot, foreign);
        f.require("no_line_of_sight".equals(refusal) || "out_of_reach".equals(refusal),
                "a strike through the wall was not refused: " + refusal);
        float before = foreign.getHealth();
        f.require(InteractAction.attackEntity(bot, foreign).isFailed() && !CombatCore.strikeIfReady(bot, foreign)
                        && foreign.getHealth() == before,
                "the bot struck an aggressor through a wall");
        DangerWatcher.INSTANCE.scanBot(f.level.getServer(), bot);
        context.onEachTick(() -> {
            f.face(owner, foreign);
            f.require(foreign.getHealth() == before, "the aggressor was hurt through a full-width wall at tick " + context.getTick());
            if (context.getTick() >= 60) {
                f.finish();
            }
        });
    }

    @GameTest(environment = ENV + "aggressor_behind_the_owner_is_not_owner_seen", maxTicks = 60)
    public void aggressorBehindTheOwnerIsNotOwnerSeen(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("HbtBehindGT", 0, 0);
        ServerPlayer owner = f.owner(bot, 3, 5);
        ServerPlayer foreign = f.foreign(3, 0);
        f.wall(1);
        f.require(owner.hurtServer(f.level, f.level.damageSources().playerAttack(foreign), 2.0F), "the hit on the owner was not real");
        // The owner has a clear line to the aggressor but is looking the other way (the aggressor is behind the owner).
        f.lookAtPoint(owner, new Vec3(owner.getX(), owner.getEyeY(), f.z(20.0D)));
        f.require(owner.hasLineOfSight(foreign) && !bot.hasLineOfSight(foreign), "fixture: only the owner has a clear line");
        f.require(!SharedVision.ownerSees(bot, foreign), "an aggressor behind the owner was taken as seen by the owner");
        f.require(CombatCore.isFriendly(bot, foreign) && !CombatCore.hostileTo(bot, foreign),
                "an aggressor outside the owner's view cone was nominated");
        // Control: the owner turns toward it.
        f.face(owner, foreign);
        f.require(SharedVision.ownerSees(bot, foreign) && CombatCore.hostileTo(bot, foreign),
                "control failed: the owner looking at the aggressor did not nominate it");
        f.finish();
    }

    @GameTest(environment = ENV + "aggressor_mark_expires_after_memory", maxTicks = 60)
    public void aggressorMarkExpiresAfterMemory(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("HbtExpiryGT", 0, 0);
        f.owner(bot, -4, 0);
        ServerPlayer foreign = f.foreign(3, 0);
        int memory = HostileBotLedger.memoryTicks();
        f.require(memory == 600, "the default aggressor memory is not 600 ticks: " + memory);
        // A mark set 598 game ticks ago (the ledger takes the tick as an argument; a test cannot wait 600 ticks).
        long markedAt = f.level.getGameTime() - (memory - 2);
        HostileBotLedger.mark(foreign.getUUID(), markedAt, "test");
        f.require(!CombatCore.isFriendly(bot, foreign), "a mark 598 ticks old is not live");
        boolean[] seenExpired = {false};
        context.onEachTick(() -> {
            long age = f.level.getGameTime() - markedAt;
            boolean friendly = CombatCore.isFriendly(bot, foreign);
            f.require(friendly == age > memory, "age " + age + ": friendly=" + friendly + " but the memory is " + memory);
            f.require(CombatCore.hostileTo(bot, foreign) == !friendly, "hostileTo disagrees with isFriendly at age " + age);
            seenExpired[0] |= friendly;
            if (age >= memory + 4) {
                f.require(seenExpired[0], "the mark never expired");
                f.finish();
            }
        });
    }

    @GameTest(environment = ENV + "minecraft_ai_bot_never_targeted_even_after_hitting_the_owner", maxTicks = 60)
    public void minecraftAiBotNeverTargetedEvenAfterHittingTheOwner(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("HbtOursGT", 0, 0);
        AIPlayerEntity other = f.bot("HbtOursOtherGT", 2, 0);
        ServerPlayer owner = f.owner(bot, -3, 0);
        float before = owner.getHealth();
        f.require(owner.hurtServer(f.level, f.level.damageSources().playerAttack(other), 2.0F) && owner.getHealth() < before,
                "the hit on the owner was not real");
        f.require(HostileBotLedger.entry(other.getUUID()) == null, "a Minecraft-AI bot entered the ledger");
        context.onEachTick(() -> {
            f.require(CombatCore.isFriendly(bot, other) && !CombatCore.hostileTo(bot, other)
                            && StrikeLegality.strikeRefusal(bot, other) != null,
                    "a Minecraft-AI bot that hit the owner became a target at tick " + context.getTick());
            if (context.getTick() % 10 == 5) {
                DangerWatcher.INSTANCE.scanBot(f.level.getServer(), bot);
                Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
                f.require(!(active instanceof CombatTask combat && combat.defensiveTarget() == other),
                        "the bot fights the other Minecraft-AI bot");
            }
            if (context.getTick() >= 30) {
                f.finish();
            }
        });
    }

    @GameTest(environment = ENV + "other_bot_owner_who_hits_us_is_hostile_as_today", maxTicks = 60)
    public void otherBotOwnerWhoHitsUsIsHostileAsToday(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("HbtRivalGT", 0, 0);
        AIPlayerEntity theirs = f.bot("HbtRivalBotGT", 0, 6);
        ServerPlayer human = f.owner(theirs, 3, 0); // a human that owns another Minecraft-AI bot
        f.require(!CombatCore.isFriendly(bot, human) && !CombatCore.hostileTo(bot, human),
                "another bot's owner must be neither friendly nor hostile before it does anything");
        float before = bot.getHealth();
        f.require(bot.hurtServer(f.level, f.level.damageSources().playerAttack(human), 2.0F) && bot.getHealth() < before,
                "the hit on the bot was not real");
        f.require(CombatCore.hostileTo(bot, human), "another bot's owner that hit us is not hostile (hasHurtBotOrOwner)");
        f.require(HostileBotLedger.entry(human.getUUID()) == null, "a bot owner entered the ledger");
        f.finish();
    }

    @GameTest(environment = ENV + "mob_that_hurt_asibling_bot_is_hostile", maxTicks = 60)
    public void mobThatHurtASiblingBotIsHostile(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity first = f.bot("HbtMobSiblingAGT", 0, 0);
        AIPlayerEntity second = f.bot("HbtMobSiblingBGT", 0, 5);
        AIPlayerEntity stranger = f.bot("HbtMobStrangerGT", 5, 5);
        ServerPlayer owner = f.owner(first, -4, 0);
        MockPlayers.ownerFor(owner, second);
        f.owner(stranger, -4, 6);
        Cow siblingHurter = f.cow(3, 0);
        Cow strangerHurter = f.cow(3, 2);
        f.require(!CombatCore.hostileTo(first, siblingHurter) && !CombatCore.hostileTo(first, strangerHurter),
                "a calm cow is hostile");
        float before = second.getHealth();
        f.require(second.hurtServer(f.level, f.level.damageSources().mobAttack(siblingHurter), 1.0F) && second.getHealth() < before,
                "the hit on the sibling bot was not real");
        f.require(CombatCore.hostileTo(first, siblingHurter), "a mob that hurt a sibling bot is not hostile to the other bot");
        before = stranger.getHealth();
        f.require(stranger.hurtServer(f.level, f.level.damageSources().mobAttack(strangerHurter), 1.0F) && stranger.getHealth() < before,
                "the hit on the other owner's bot was not real");
        f.require(!CombatCore.hostileTo(first, strangerHurter), "a mob that hurt another owner's bot became hostile through the sibling rule");
        f.finish();
    }

    @GameTest(environment = ENV + "aggro_sense_flags_zombie_that_hurt_the_owner", maxTicks = 60)
    public void aggroSenseFlagsZombieThatHurtTheOwner(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("HbtSenseGT", -6, 0);
        ServerPlayer owner = f.owner(bot, -6, 3);
        Zombie zombie = f.zombie(6, 0); // 12 blocks away: observed (16) but outside the 10 block pressure envelope
        context.onEachTick(() -> {
            long t = context.getTick();
            if (t == 2) {
                AggroSense.Snapshot calm = AggroSense.snapshot(bot);
                f.require(!calm.pressure() && calm.aggressorCount() == 0 && !calm.ownerUnderAttack(),
                        "a calm zombie counted as pressure: " + calm);
            }
            if (t == 3) {
                float before = owner.getHealth();
                f.require(owner.hurtServer(f.level, f.level.damageSources().mobAttack(zombie), 2.0F) && owner.getHealth() < before,
                        "the zombie's hit on the owner was not real");
            }
            if (t == 5) {
                AggroSense.Snapshot snapshot = AggroSense.snapshot(bot);
                f.require(snapshot.pressure() && snapshot.aggressorCount() == 1 && snapshot.aggressors().contains(zombie),
                        "the zombie that hurt the owner is not an aggressor: " + snapshot);
                f.require(snapshot.ownerUnderAttack(), "the owner is not under attack");
                f.require(snapshot.strongestType() == EntityType.ZOMBIE && snapshot.strongestDefaultMaxHealth() == 20.0F,
                        "strongest is " + snapshot.strongestType() + " " + snapshot.strongestDefaultMaxHealth());
                f.require(!snapshot.playerKindAggressor() && !snapshot.rangedOrExplosive(), "a zombie is neither a player nor ranged");
                f.finish();
            }
        });
    }

    @GameTest(environment = ENV + "aggro_sense_does_not_flag_acalm_warden", maxTicks = 60)
    public void aggroSenseDoesNotFlagACalmWarden(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("HbtWardenSenseGT", 0, 0);
        f.owner(bot, -3, 2);
        Warden warden = f.warden(6, 0);
        f.require(warden.getPose() != Pose.ROARING, "the fixture warden is roaring");
        context.onEachTick(() -> {
            long t = context.getTick();
            if (t == 2) {
                AggroSense.Snapshot calm = AggroSense.snapshot(bot);
                f.require(!calm.pressure() && calm.aggressorCount() == 0 && calm.aggressors().isEmpty(),
                        "a calm warden counted as pressure: " + calm);
            }
            if (t == 4) {
                // Control: once it hit the bot it is hunting and an aggressor.
                float before = bot.getHealth();
                f.require(bot.hurtServer(f.level, f.level.damageSources().mobAttack(warden), 1.0F) && bot.getHealth() < before,
                        "the warden's hit on the bot was not real");
            }
            if (t == 6) {
                AggroSense.Snapshot hunting = AggroSense.snapshot(bot);
                f.require(hunting.pressure() && hunting.aggressors().contains(warden),
                        "a warden that hit the bot is not an aggressor: " + hunting);
                f.finish();
            }
        });
    }

    // ------------------------------------------------------------------ the killing blow (RecentDamage)

    @GameTest(environment = ENV + "killing_blow_is_recorded_and_marks_the_killer", maxTicks = 60)
    public void killingBlowIsRecordedAndMarksTheKiller(GameTestHelper context) {
        Fixture f = new Fixture(context);
        AIPlayerEntity bot = f.bot("HbtLethalGT", 0, 0);
        ServerPlayer owner = f.owner(bot, -3, 3);
        ServerPlayer foreign = f.foreign(3, 0);
        List<RecentDamage.Hit> seen = new ArrayList<>();
        Runnable unsubscribe = RecentDamage.addListener(seen::add);
        f.onFinish(unsubscribe);
        owner.setHealth(2.0F);
        owner.hurtServer(f.level, f.level.damageSources().playerAttack(foreign), 1000.0F);
        f.require(owner.isDeadOrDying(), "the owner survived a 1000 damage hit");
        boolean recorded = seen.stream().anyMatch(hit -> owner.getUUID().equals(hit.victim())
                && foreign.getUUID().equals(hit.attacker()) && hit.byEntity() && hit.amount() > 0.0F);
        f.require(recorded, "the killing blow was not recorded from the death event: " + seen);
        f.require(HostileBotLedger.isMarked(foreign.getUUID(), f.level.getGameTime()),
                "the killer of a protected victim was not marked");
        f.finish();
    }

    // ------------------------------------------------------------------ fixture

    /** One arena: a stone platform, the players and mobs of one test, and their clean-up. */
    private static final class Fixture {
        final GameTestHelper context;
        final ServerLevel level;
        final BlockPos feet;
        private final List<String> botNames = new ArrayList<>();
        private final List<ServerPlayer> mocks = new ArrayList<>();
        private final List<Entity> entities = new ArrayList<>();
        private final List<Runnable> cleanups = new ArrayList<>();
        private boolean finished;

        Fixture(GameTestHelper context) {
            this.context = context;
            this.level = context.getLevel();
            this.feet = context.absolutePos(new BlockPos(HALF + 1, LAYER_Y, HALF + 1));
            level.setDayTime(1000L);
            for (int dx = -HALF; dx <= HALF; dx++) {
                for (int dz = -HALF; dz <= HALF; dz++) {
                    level.setBlock(feet.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                    for (int dy = 0; dy <= 5; dy++) {
                        level.setBlock(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
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

        void onFinish(Runnable cleanup) {
            cleanups.add(cleanup);
        }

        AIPlayerEntity bot(String name, int dx, int dz) {
            AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                            level.getServer(), name, level, new Vec3(x(dx), feet.getY(), z(dz)), 0.0F, 0.0F, GameType.SURVIVAL)
                    .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
            botNames.add(name);
            // A freshly spawned bot is protected as a not-yet-loaded client (invulnerable) until its load timeout; the tests hit it at tick 0.
            if (!bot.connection.hasClientLoaded()) {
                bot.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            }
            bot.setHealth(bot.getMaxHealth());
            bot.getFoodData().setFoodLevel(20);
            InventoryAction.giveItem(bot, new ItemStack(Items.IRON_SWORD));
            return bot;
        }

        /** A survival mock registered as the owner of {@code bot} ({@link MockPlayers#ownerFor}), placed at (dx, dz). */
        ServerPlayer owner(AIPlayerEntity bot, int dx, int dz) {
            ServerPlayer owner = MockPlayers.ownerFor(context, bot);
            mocks.add(owner);
            place(owner, dx, dz);
            return owner;
        }

        /** A foreign bot: a survival mock on an EmbeddedChannel that owns no Minecraft-AI bot, with an iron sword. */
        ServerPlayer foreign(int dx, int dz) {
            ServerPlayer mock = MockPlayers.survivalMock(context);
            mocks.add(mock);
            mock.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
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
            looker.lookAt(EntityAnchorArgument.Anchor.EYES, point);
            looker.setYHeadRot(looker.getYRot());
        }

        <T extends Entity> T add(T entity, int dx, int dz) {
            entity.snapTo(x(dx), feet.getY(), z(dz), 90.0F, 0.0F);
            level.addFreshEntity(entity);
            entities.add(entity);
            return entity;
        }

        Husk husk(int dx, int dz) {
            Husk husk = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
            husk.setPersistenceRequired();
            husk.setNoAi(true);
            return add(husk, dx, dz);
        }

        Zombie zombie(int dx, int dz) {
            Zombie zombie = EntityType.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
            zombie.setPersistenceRequired();
            zombie.setNoAi(true);
            zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET)); // no sun burn in the daylight arena
            return add(zombie, dx, dz);
        }

        Cow cow(int dx, int dz) {
            Cow cow = EntityType.COW.create(level, EntitySpawnReason.COMMAND);
            cow.setPersistenceRequired();
            cow.setNoAi(true);
            return add(cow, dx, dz);
        }

        Warden warden(int dx, int dz) {
            Warden warden = EntityType.WARDEN.create(level, EntitySpawnReason.COMMAND);
            warden.setPersistenceRequired();
            warden.setNoAi(true);
            return add(warden, dx, dz);
        }

        /** A stone wall across the whole arena at {@code dx} blocks east of the centre, four blocks high. */
        void wall(int dx) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    level.setBlock(feet.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }

        void clearWall(int dx) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    level.setBlock(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }

        void finish() {
            if (!finished) {
                finished = true;
                context.succeed();
            }
        }

        private void cleanUp() {
            for (Runnable cleanup : cleanups) {
                cleanup.run();
            }
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
        }
    }
}
