package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.GameTestCleanup;
import io.github.zoyluo.minecraftai.gametest.MockPlayers;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The scene of the follow GameTests (pace, escort, eating while following): a sealed flat course ({@link BaritoneEngineArena}), a
 * followed player that is a survival mock (a mock is not ticked, so its sneak and sprint flags stay as the test sets them and the
 * test moves it by placing it), the follower on either engine, and the mobs of a scenario. Everything it made is removed when the
 * test ends. Every test has its own environment (its own batch), so the arena layer does not have to be unique.
 */
final class FollowFieldFixture {
    final GameTestHelper context;
    final ServerLevel level;
    final BaritoneEngineArena arena;
    private final List<String> botNames = new ArrayList<>();
    private final List<ServerPlayer> mocks = new ArrayList<>();
    private final List<Entity> entities = new ArrayList<>();
    private final List<Runnable> cleanups = new ArrayList<>();
    private boolean finished;

    FollowFieldFixture(GameTestHelper context, int halfX, int halfZ) {
        this(context, halfX, halfZ, 0);
    }

    /** As above on its own world layer (a long or wide scene keeps clear of the layer-0 scenes that run at the same time). */
    FollowFieldFixture(GameTestHelper context, int halfX, int halfZ, int layer) {
        this(context, halfX, halfZ, layer, 4);
    }

    /** As above with a deeper sealed floor for a water column whose bottom is part of the fixture. */
    FollowFieldFixture(GameTestHelper context, int halfX, int halfZ, int layer, int floorDepth) {
        this.context = context;
        this.level = context.getLevel();
        this.arena = BaritoneEngineArena.build(context, layer, halfX, halfZ, floorDepth);
        level.setDayTime(1000L);
        GameTestCleanup.whenFinished(context, this::cleanUp);
    }

    BlockPos cell(int dx, int dz) {
        return arena.cell(dx, 0, dz);
    }

    double x(double dx) {
        return arena.origin.getX() + 0.5D + dx;
    }

    double z(double dz) {
        return arena.origin.getZ() + 0.5D + dz;
    }

    void require(boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
            throw new IllegalStateException(message);
        }
    }

    void onFinish(Runnable cleanup) {
        cleanups.add(cleanup);
    }

    /** A follower at (dx, dz) on the chosen engine, full health and food, able to take damage. */
    AIPlayerEntity bot(String name, int dx, int dz, boolean baritone) {
        AIPlayerEntity bot = baritone ? arena.spawnOnBaritone(name, cell(dx, dz)) : arena.spawn(name, cell(dx, dz));
        botNames.add(name);
        if (!baritone) {
            NavEngineSelector.setBotEngine(bot.getUUID(), io.github.zoyluo.minecraftai.navigation.NavEngine.BARITONE);
        }
        if (!bot.connection.hasClientLoaded()) {
            bot.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        }
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(20.0F);
        return bot;
    }

    /** A followed player: a survival mock at (dx, dz). */
    ServerPlayer target(int dx, int dz) {
        ServerPlayer mock = MockPlayers.survivalMock(context);
        mocks.add(mock);
        place(mock, dx, dz);
        return mock;
    }

    /** A survival mock that is the owner of {@code bot}, at (dx, dz). */
    ServerPlayer owner(AIPlayerEntity bot, int dx, int dz) {
        ServerPlayer owner = MockPlayers.ownerFor(context, bot);
        mocks.add(owner);
        place(owner, dx, dz);
        return owner;
    }

    /** A foreign bot (a survival mock that owns no Minecraft-AI bot) at (dx, dz). */
    ServerPlayer foreign(int dx, int dz) {
        ServerPlayer mock = MockPlayers.survivalMock(context);
        mocks.add(mock);
        place(mock, dx, dz);
        return mock;
    }

    void place(ServerPlayer player, double dx, double dz) {
        player.teleportTo(level, x(dx), arena.origin.getY(), z(dz), Set.of(), player.getYRot(), player.getXRot(), true);
        player.setDeltaMovement(Vec3.ZERO);
    }

    FollowTask follow(AIPlayerEntity bot, String targetName, String reason) {
        FollowTask follow = new FollowTask(targetName);
        TaskManager.INSTANCE.assign(bot, follow, TaskOrigin.of(TaskOrigin.Kind.VERIFY, reason));
        return follow;
    }

    void give(AIPlayerEntity bot, ItemStack stack) {
        InventoryAction.giveItem(bot, stack);
    }

    <T extends Entity> T add(T entity, double dx, double dz) {
        entity.snapTo(x(dx), arena.origin.getY(), z(dz), 90.0F, 0.0F);
        level.addFreshEntity(entity);
        entities.add(entity);
        return entity;
    }

    Zombie zombie(double dx, double dz, boolean noAi) {
        Zombie zombie = EntityType.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        zombie.setPersistenceRequired();
        zombie.setNoAi(noAi);
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(net.minecraft.world.item.Items.LEATHER_HELMET)); // no sun burn
        return add(zombie, dx, dz);
    }

    Creeper creeper(double dx, double dz) {
        Creeper creeper = EntityType.CREEPER.create(level, EntitySpawnReason.COMMAND);
        creeper.setPersistenceRequired();
        creeper.setNoAi(true);
        return add(creeper, dx, dz);
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
            AIPlayerManager.INSTANCE.getByName(name).ifPresent(bot -> {
                TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
                bot.getActionPack().stopAll();
                bot.getActionPack().clearPace();
                NavEngineSelector.clearBotEngine(bot.getUUID());
            });
            AIPlayerManager.INSTANCE.despawn(level.getServer(), name);
        }
        AggroSense.clearAll();
    }
}
