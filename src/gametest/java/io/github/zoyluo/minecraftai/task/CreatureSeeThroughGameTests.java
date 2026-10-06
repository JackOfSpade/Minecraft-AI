package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.StrikeLegality;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.GameTestCleanup;
import io.github.zoyluo.minecraftai.gametest.MockPlayers;
import io.github.zoyluo.minecraftai.gametest.PerceptionFixtures;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.perception.CreatureSenses;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * The bot's EYES see a creature through leaves, fences, panes and glass (docs/PERCEPTION.md, "See-through sight"), and nothing else
 * about the creature changes: a blow, an arrow or a blast cannot cross what the eyes pass, so a creature seen that way is noticed
 * but is no target, no pressure, no threat and no risk. A bot that treated seeing as threat would walk up to a pane and give up
 * again for ever (the guard's cooldown cycle), stop eating beside a glass-walled mob farm, or flee a creeper that cannot hurt it.
 * What the eyes still cannot pass (stone, a wall, a door, lava) hides the creature as it always did.
 *
 * <p>The arena: the bot at {@code feet} looking east, a wall across the whole width at {@code dx = 1}, the creature behind it.</p>
 */
public final class CreatureSeeThroughGameTests {
    private static final String ENV = "minecraftai-gametest:creature_see_through_game_tests_";
    /** Ticks a creature seen through a see-through wall stays quiet before the wall goes: long past every watcher's cadence. */
    private static final int QUIET_TICKS = 30;

    private static final BlockState LEAVES = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);

    // ------------------------------------------------------------------ noticed, but no target, pressure or threat

    @GameTest(environment = ENV + "a_hostile_behind_leaves_is_noticed_but_is_no_target_pressure_or_threat", maxTicks = 90 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void aHostileBehindLeavesIsNoticedButIsNoTargetPressureOrThreat(GameTestHelper context) {
        hostileBehind(context, "SeeLeavesGT", LEAVES, false);
    }

    @GameTest(environment = ENV + "a_hostile_behind_a_glass_pane_is_noticed_but_is_no_target_pressure_or_threat", maxTicks = 90 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void aHostileBehindAGlassPaneIsNoticedButIsNoTargetPressureOrThreat(GameTestHelper context) {
        hostileBehind(context, "SeePaneGT", Blocks.GLASS_PANE.defaultBlockState(), false);
    }

    /** A baby husk: its eyes are below the rail of a fence, so the plain ray to it crosses the fence (an adult's passes over). */
    @GameTest(environment = ENV + "a_hostile_behind_a_fence_is_noticed_but_is_no_target_pressure_or_threat", maxTicks = 90 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void aHostileBehindAFenceIsNoticedButIsNoTargetPressureOrThreat(GameTestHelper context) {
        hostileBehind(context, "SeeFenceGT", Blocks.OAK_FENCE.defaultBlockState(), true);
    }

    private static void hostileBehind(GameTestHelper context, String name, BlockState wallState, boolean baby) {
        Arena arena = new Arena(context);
        AIPlayerEntity bot = arena.bot(name);
        arena.wall(1, 1, wallState);
        Husk husk = arena.husk(3, baby);
        PerceptionFixtures.faceToward(bot, husk);
        PerceptionFixtures.afterNoticed(context, bot, List.of(husk), () -> {
            String seen = wallState.getBlock().getName().getString();
            require(context, ObservableWorldQuery.canNoticeCreature(bot, husk) && ObservableWorldQuery.canObserveEntity(bot, husk),
                    "the bot did not see the husk through " + seen);
            require(context, !CombatCore.hasLineOfSight(bot, husk),
                    "the physical line to the husk is clear through " + seen + ": the fixture is wrong");
            require(context, !StrikeLegality.hasStrikeLineOfSight(bot, husk) && StrikeLegality.strikeRefusal(bot, husk) != null,
                    "a strike through " + seen + " is not refused: " + StrikeLegality.strikeRefusal(bot, husk));
            require(context, !DangerWatcher.hasObservableHostilePressure(bot), "a husk seen through " + seen + " is hostile pressure");
            require(context, CombatCore.nearestTarget(bot, EntityType.HUSK, 16).isEmpty(),
                    "a husk seen through " + seen + " is a fight target");
            require(context, CombatCore.nearestHostileAround(bot, arena.feet, 10).isEmpty(),
                    "a husk seen through " + seen + " is what the guard acquires");
            int[] quiet = {0};
            PerceptionFixtures.everyTick(context, () -> {
                require(context, !TaskManager.INSTANCE.isActiveSafety(bot),
                        "a safety task started against a husk that is only seen through " + seen + ": "
                                + TaskManager.INSTANCE.getActive(bot).map(Task::describe).orElse("?"));
                require(context, CreatureSenses.INSTANCE.noticed(bot, husk), "the bot forgot the husk it sees through " + seen);
                if (++quiet[0] < QUIET_TICKS) {
                    return;
                }
                // The wall goes: the very same husk is now a target, pressure and a fight.
                arena.clearWall(1, 1);
                require(context, CombatCore.hasLineOfSight(bot, husk), "control: no physical line once the wall is gone");
                require(context, DangerWatcher.hasObservableHostilePressure(bot), "control: the husk in the open is no pressure");
                require(context, CombatCore.nearestTarget(bot, EntityType.HUSK, 16).isPresent(),
                        "control: the husk in the open is not a fight target");
                require(context, CombatCore.nearestHostileAround(bot, arena.feet, 10).isPresent(),
                        "control: the guard does not acquire the husk in the open");
                context.succeed();
            });
        });
    }

    @GameTest(environment = ENV + "a_creeper_behind_a_pane_is_noticed_but_is_no_risk", maxTicks = 90 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void aCreeperBehindAPaneIsNoticedButIsNoRisk(GameTestHelper context) {
        Arena arena = new Arena(context);
        AIPlayerEntity bot = arena.bot("SeeCreeperGT");
        arena.wall(1, 1, Blocks.GLASS.defaultBlockState());
        Creeper creeper = EntityType.CREEPER.create(context.getLevel(), EntitySpawnReason.COMMAND);
        creeper.setPersistenceRequired();
        creeper.setNoAi(true);
        arena.place(creeper, 6);
        PerceptionFixtures.faceToward(bot, creeper);
        PerceptionFixtures.afterNoticed(context, bot, List.of(creeper), () -> {
            require(context, ObservableWorldQuery.canNoticeCreature(bot, creeper), "the bot did not see the creeper through glass");
            require(context, CreeperDefenseTask.selectObservableCreeper(bot).isEmpty(),
                    "a creeper behind glass is a risk to flee from: its blast cannot cross it");
            int[] quiet = {0};
            PerceptionFixtures.everyTick(context, () -> {
                require(context, !TaskManager.INSTANCE.isActiveSafety(bot),
                        "a safety task started against a creeper behind glass: "
                                + TaskManager.INSTANCE.getActive(bot).map(Task::describe).orElse("?"));
                if (++quiet[0] < QUIET_TICKS) {
                    return;
                }
                arena.clearWall(1, 1);
                require(context, CreeperDefenseTask.selectObservableCreeper(bot).isPresent(),
                        "control: the creeper in the open is no risk");
                context.succeed();
            });
        });
    }

    // ------------------------------------------------------------------ what the eyes cannot pass hides it

    @GameTest(environment = ENV + "a_hostile_behind_a_stone_wall_or_a_closed_door_is_not_noticed", maxTicks = 90 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void aHostileBehindAStoneWallOrAClosedDoorIsNotNoticed(GameTestHelper context) {
        Arena arena = new Arena(context);
        AIPlayerEntity bot = arena.bot("SeeOpaqueGT");
        Husk husk = arena.husk(3, false);
        PerceptionFixtures.faceToward(bot, husk);
        PerceptionFixtures.prepare(context);
        int window = PerceptionFixtures.reactionTicks(bot, husk) + 15;
        arena.wall(1, 1, Blocks.COBBLESTONE_WALL.defaultBlockState());
        context.onEachTick(() -> {
            int tick = (int) context.getTick();
            if (tick > 2 * window) {
                return; // the control below runs from here
            }
            require(context, !CreatureSenses.INSTANCE.noticed(bot, husk) && !ObservableWorldQuery.canObserveEntity(bot, husk),
                    "a husk behind " + (tick < window ? "a stone wall" : "closed doors") + " was seen at tick " + tick);
            if (tick == window) {
                arena.clearWall(1, 1);
                arena.doors(1);
            } else if (tick == 2 * window) {
                arena.clearDoors(1);
                // Control: the same husk in the open is noticed within the formula's reaction time.
                PerceptionFixtures.afterNoticed(context, bot, List.of(husk), context::succeed);
            }
        });
    }

    @GameTest(environment = ENV + "a_hostile_behind_lava_is_not_seen", maxTicks = 90 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void aHostileBehindLavaIsNotSeen(GameTestHelper context) {
        Arena arena = new Arena(context);
        AIPlayerEntity bot = arena.bot("SeeLavaGT");
        Husk husk = arena.husk(8, false);
        PerceptionFixtures.faceToward(bot, husk);
        PerceptionFixtures.afterNoticed(context, bot, List.of(husk), () -> {
            // Vanilla's collider ray ignores every fluid; the bot's eyes do not ignore lava (they pass water only).
            arena.wall(5, 2, Blocks.LAVA.defaultBlockState());
            int[] after = {0};
            PerceptionFixtures.everyTick(context, () -> {
                if (++after[0] < 8) {
                    return;
                }
                require(context, !ObservableWorldQuery.canObserveEntity(bot, husk) && !CreatureSenses.INSTANCE.noticed(bot, husk),
                        "the bot still sees a husk through two blocks of lava");
                arena.clearWall(5, 2);
                context.succeed();
            });
        });
    }

    // ------------------------------------------------------------------ the guard, the owner, the trader

    /**
     * The guard watches a husk it sees through glass from where it stands: it must not walk up to the pane and give up again (the
     * engage, approach, give up, cooldown cycle {@code GuardTask} has for a target it cannot reach), and it engages the moment a
     * line exists.
     */
    @GameTest(environment = ENV + "the_guard_does_not_engage_a_hostile_it_only_sees_through_glass", maxTicks = 120 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void theGuardDoesNotEngageAHostileItOnlySeesThroughGlass(GameTestHelper context) {
        Arena arena = new Arena(context);
        AIPlayerEntity bot = arena.bot("SeeGuardGT");
        bot.getInventory().setItem(0, new ItemStack(Items.STONE_SWORD));
        arena.wall(2, 1, Blocks.GLASS.defaultBlockState());
        Husk husk = arena.husk(4, false);
        PerceptionFixtures.faceToward(bot, husk);
        PerceptionFixtures.afterNoticed(context, bot, List.of(husk), () -> {
            GuardTask guard = GuardTask.point(arena.feet);
            guard.start(bot);
            int[] ticks = {0};
            boolean[] engaged = {false};
            PerceptionFixtures.everyTick(context, () -> {
                if (guard.state() == TaskState.RUNNING) {
                    guard.tick(bot);
                }
                require(context, guard.state() == TaskState.RUNNING, "the guard ended: " + guard.state() + ":" + guard.failureReason());
                boolean approaching = guard.describe().contains("phase=APPROACH");
                int tick = ++ticks[0];
                if (tick <= QUIET_TICKS) {
                    require(context, !approaching, "the guard went for a husk it only sees through glass: " + guard.describe());
                    require(context, ObservableWorldQuery.canNoticeCreature(bot, husk),
                            "the premise is the guard SEES the husk through the glass, and it does not");
                    if (tick == QUIET_TICKS) {
                        arena.clearWall(2, 1);
                    }
                    return;
                }
                if (approaching) {
                    engaged[0] = true;
                }
                if (engaged[0]) {
                    guard.abort(bot);
                    context.succeed();
                } else if (tick > QUIET_TICKS + 10) {
                    context.fail(Component.nullToEmpty("the guard did not engage the husk once the glass was gone: " + guard.describe()));
                }
            });
        });
    }

    /**
     * An owner who looks at a foreign bot through leaves SEES it (it nominates the target), but across a hedge nothing can be hit:
     * the owner's look must not hold a fight alive, so the fight bookkeeping reads the owner's plain collider line.
     */
    @GameTest(environment = ENV + "an_owner_looking_through_leaves_does_not_keep_a_fight_alive", maxTicks = 40)
    public void anOwnerLookingThroughLeavesDoesNotKeepAFightAlive(GameTestHelper context) {
        Arena arena = new Arena(context);
        AIPlayerEntity bot = arena.bot("SeeOwnerGT");
        ServerPlayer owner = MockPlayers.ownerFor(context, bot);
        ServerPlayer foreign = MockPlayers.survivalMock(context);
        arena.put(owner, 0);
        arena.put(foreign, 4);
        arena.wall(2, 1, LEAVES);
        MockPlayers.faceTowards(owner, foreign);
        require(context, SharedVision.ownerSees(bot, foreign), "the owner does not see a foreign bot through leaves");
        require(context, !SharedVision.ownerSeesStrict(bot, foreign), "the owner has a plain line to the bot through leaves");
        require(context, !CombatCore.hasLineOfSightOrOwnerSees(bot, foreign),
                "a foreign bot behind leaves is a fight the owner's look keeps alive: neither can hit it");
        arena.clearWall(2, 1);
        require(context, SharedVision.ownerSees(bot, foreign) && SharedVision.ownerSeesStrict(bot, foreign),
                "control: the owner does not see the foreign bot in the open");
        require(context, CombatCore.hasLineOfSightOrOwnerSees(bot, foreign), "control: no line to the foreign bot in the open");
        context.succeed();
    }

    /** Opening a trade sends no pick ray, so a villager seen through a pane is walked to and not traded with. */
    @GameTest(environment = ENV + "a_villager_behind_glass_is_not_traded_with", maxTicks = 40)
    public void aVillagerBehindGlassIsNotTradedWith(GameTestHelper context) {
        Arena arena = new Arena(context);
        AIPlayerEntity bot = arena.bot("SeeTradeGT");
        bot.getInventory().setItem(0, new ItemStack(Items.EMERALD, 3));
        arena.wall(1, 1, Blocks.GLASS.defaultBlockState());
        Villager villager = EntityType.VILLAGER.create(context.getLevel(), EntitySpawnReason.COMMAND);
        villager.setNoAi(true);
        arena.place(villager, 2);
        MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.ARROW, 16), 12, 7, 0.05F);
        villager.getOffers().add(offer);
        require(context, ObservableWorldQuery.canObserveEntity(bot, villager), "the premise is the bot sees the villager through glass");

        TradeTask task = new TradeTask(Items.ARROW, 16);
        task.start(bot);
        for (int i = 0; i < 40 && task.state() == TaskState.RUNNING; i++) {
            task.tick(bot);
        }
        require(context, task.state() == TaskState.RUNNING && offer.getUses() == 0
                        && bot.getInventory().getItem(0).getCount() == 3,
                "a villager behind glass was traded with: " + task.state() + " " + task.failureReason() + " uses " + offer.getUses());
        arena.clearWall(1, 1);
        for (int i = 0; i < 8 && task.state() == TaskState.RUNNING; i++) {
            task.tick(bot);
        }
        require(context, task.state() == TaskState.COMPLETED && offer.getUses() == 1,
                "control: the trade did not complete once the glass was gone: " + task.state() + " " + task.failureReason());
        context.succeed();
    }

    /** A shot in flight is SEEN through leaves and glass like any creature; stone still hides it. */
    @GameTest(environment = ENV + "an_arrow_in_flight_is_seen_through_leaves_and_glass_but_not_through_stone", maxTicks = 40)
    public void anArrowInFlightIsSeenThroughLeavesAndGlassButNotThroughStone(GameTestHelper context) {
        Arena arena = new Arena(context);
        AIPlayerEntity bot = arena.bot("SeeArrowGT");
        Arrow arrow = EntityType.ARROW.create(context.getLevel(), EntitySpawnReason.COMMAND);
        arena.place(arrow, 5);
        arrow.setPos(arrow.getX(), arrow.getY() + 1.5D, arrow.getZ());
        PerceptionFixtures.facePoint(bot, arrow.position());
        BlockState[] seen = {LEAVES, Blocks.GLASS.defaultBlockState()};
        for (BlockState wall : seen) {
            arena.wall(2, 1, wall);
            require(context, CreatureSenses.INSTANCE.noticedProjectile(bot, arrow),
                    "an arrow behind " + wall.getBlock().getName().getString() + " was not seen");
            arena.clearWall(2, 1);
        }
        arena.wall(2, 1, Blocks.STONE.defaultBlockState());
        require(context, !CreatureSenses.INSTANCE.noticedProjectile(bot, arrow), "an arrow behind stone was seen");
        arena.clearWall(2, 1);
        require(context, CreatureSenses.INSTANCE.noticedProjectile(bot, arrow), "control: an arrow in the open was not seen");
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    /** A cleared strip: the bot at {@code feet} facing east, a wall spans the whole width. */
    private static final class Arena {
        final GameTestHelper context;
        final ServerLevel level;
        final BlockPos feet;
        private final List<String> bots = new ArrayList<>();
        private final List<Entity> entities = new ArrayList<>();

        Arena(GameTestHelper context) {
            this.context = context;
            this.level = context.getLevel();
            this.feet = context.absolutePos(new BlockPos(2, 4, 4));
            level.setDayTime(1000L);
            for (int dx = -2; dx <= 10; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    level.setBlock(feet.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                    for (int dy = 0; dy <= 6; dy++) {
                        level.setBlock(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
            CreatureSenses.forceEnabledForTests(true);
            GameTestCleanup.whenFinished(context, this::cleanUp);
        }

        AIPlayerEntity bot(String name) {
            Vec3 pose = Vec3.atBottomCenterOf(feet);
            AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(level.getServer(), name, level, pose, 0.0F, 0.0F, GameType.SURVIVAL)
                    .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
            bots.add(name);
            bot.teleportTo(level, pose.x, pose.y, pose.z, Set.of(), 0.0F, 0.0F, true);
            bot.setOnGround(true);
            // The chunk tracking view follows a teleport on the next tick, and every sight question needs it at once.
            level.getChunkSource().move(bot);
            if (!bot.connection.hasClientLoaded()) {
                bot.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            }
            bot.setHealth(bot.getMaxHealth());
            bot.getFoodData().setFoodLevel(20);
            return bot;
        }

        Husk husk(int dx, boolean baby) {
            Husk husk = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
            husk.setPersistenceRequired();
            husk.setNoAi(true);
            husk.setBaby(baby);
            return place(husk, dx);
        }

        <T extends Entity> T place(T entity, int dx) {
            entity.snapTo(feet.getX() + 0.5D + dx, feet.getY(), feet.getZ() + 0.5D, 90.0F, 0.0F);
            level.addFreshEntity(entity);
            entities.add(entity);
            return entity;
        }

        void put(ServerPlayer player, int dx) {
            player.teleportTo(level, feet.getX() + 0.5D + dx, feet.getY(), feet.getZ() + 0.5D, Set.of(), -90.0F, 0.0F, true);
        }

        /** A wall of {@code thickness} blocks starting {@code from} blocks east of the bot, across every ray to what stands behind it. */
        void wall(int from, int thickness, BlockState state) {
            for (int depth = 0; depth < thickness; depth++) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dy = 0; dy <= 3; dy++) {
                        level.setBlock(feet.offset(from + depth, dy, dz), state, Block.UPDATE_ALL);
                    }
                }
            }
        }

        void clearWall(int from, int thickness) {
            wall(from, thickness, Blocks.AIR.defaultBlockState());
        }

        /** Closed oak doors in every column (lower and upper half), their plane across the line of sight. */
        void doors(int dx) {
            BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST)
                    .setValue(BlockStateProperties.OPEN, false);
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlock(feet.offset(dx, 0, dz), lower, Block.UPDATE_ALL);
                level.setBlock(feet.offset(dx, 1, dz), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
            }
        }

        void clearDoors(int dx) {
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlock(feet.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(feet.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }

        private void cleanUp() {
            for (Entity entity : entities) {
                entity.discard();
            }
            for (String name : bots) {
                AIPlayerManager.INSTANCE.despawn(level.getServer(), name);
            }
        }
    }
}
