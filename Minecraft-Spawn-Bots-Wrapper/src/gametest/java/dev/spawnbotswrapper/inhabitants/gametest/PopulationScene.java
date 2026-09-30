package dev.spawnbotswrapper.inhabitants.gametest;

import dev.spawnbotswrapper.inhabitants.InhabitantsMod;
import dev.spawnbotswrapper.inhabitants.command.CommandServices;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.engine.EngineControl;
import dev.spawnbotswrapper.inhabitants.engine.ForceMode;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The scene of the population GameTests: a stone platform, a survival player that is not a PvP BOT bot (the "human"
 * the allocation and the seen check look at), and ONE real inhabitant asked from the population engine like a structure
 * population would (a one-cell structure that always has exactly one bot).
 * <p>
 * Standalone on purpose (it does not share the combat scene's class): the tests here change the addon's configuration
 * for the run (removal of bots that left the allocation is switched on and made quick) and put it back on cleanup.
 */
final class PopulationScene {
    static final Logger LOG = LoggerFactory.getLogger("harness-population");
    static final int LAYER_Y = 100;

    /** Every death of a living entity the server reported, by entity id (AFTER_DEATH, exactly what the addon listens to). */
    private static final Map<UUID, Integer> DEATHS = new ConcurrentHashMap<>();

    static {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> DEATHS.merge(entity.getUUID(), 1, Integer::sum));
    }

    static int deathsOf(UUID id) {
        return DEATHS.getOrDefault(id, 0);
    }

    final GameTestHelper ctx;
    final MinecraftServer server;
    final ServerLevel level;
    final BlockPos botFeet;
    ServerPlayer human;
    String botName;
    ServerPlayer bot;
    private boolean requested;
    private boolean cleaned;
    private final List<Runnable> cleanups = new ArrayList<>();
    private final String structureId = "gametest:population_" + Long.toHexString(System.nanoTime());

    PopulationScene(GameTestHelper ctx) {
        this.ctx = ctx;
        this.level = ctx.getLevel();
        this.server = level.getServer();
        this.botFeet = ctx.absolutePos(new BlockPos(4, LAYER_Y, 4));
    }

    void fail(String message) {
        cleanup();
        ctx.fail(Component.nullToEmpty(message));
        throw new IllegalStateException(message);
    }

    void succeed() {
        cleanup();
        ctx.succeed();
    }

    void onCleanup(Runnable undo) {
        cleanups.add(undo);
    }

    void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        // what this scene dropped must not be counted by the next one
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, aroundTheCell(16))) {
            if (!groundBefore.contains(e.getUUID())) {
                e.discard();
            }
        }
        for (ExperienceOrb e : level.getEntitiesOfClass(ExperienceOrb.class, aroundTheCell(16))) {
            if (!groundBefore.contains(e.getUUID())) {
                e.discard();
            }
        }
        cleaned = true;
        for (Runnable r : cleanups) {
            try {
                r.run();
            } catch (RuntimeException e) {
                LOG.warn("cleanup step failed: {}", e.toString());
            }
        }
        try {
            CommandServices services = services();
            if (services != null && requested) {
                services.engine().reset(key(), true);
            }
        } catch (RuntimeException e) {
            LOG.warn("cleanup of the inhabitant failed: {}", e.toString());
        }
        try {
            if (human != null) {
                server.getPlayerList().remove(human);
            }
        } catch (RuntimeException e) {
            LOG.warn("cleanup of the human failed: {}", e.toString());
        }
    }

    CommandServices services() {
        return InhabitantsMod.servicesOf(server);
    }

    StructureKey key() {
        return new StructureKey(level.dimension().identifier().toString(), structureId, botFeet.getX() >> 4, botFeet.getZ() >> 4);
    }

    /**
     * Switches on what these tests are about and makes it quick: bots of a structure that left the allocation are removed
     * (the harness run has dormancy off), with no grace and no dwell time, and the relevance area is only {@code radius}
     * blocks so that a player a few dozen blocks away is "out of range" without any chunk having to load.
     */
    void allocationQuick(double radius) {
        InhabitantsConfig cfg = services().config().get();
        boolean dormancy = cfg.dormancy.enabled;
        boolean aggro = cfg.aggro.enabled;
        double distance = cfg.dormancy.distanceBlocks;
        int grace = cfg.allocation.graceTicks;
        int dwell = cfg.allocation.dwellTicks;
        int interval = cfg.allocation.intervalTicks;
        int seenCheck = cfg.allocation.seenCheckTicks;
        cfg.dormancy.enabled = true;
        cfg.aggro.enabled = false; // a bot that chases the player over the platform edge is no subject of these tests
        cfg.dormancy.distanceBlocks = radius;
        cfg.allocation.graceTicks = 0;
        cfg.allocation.dwellTicks = 0;
        cfg.allocation.intervalTicks = 5;
        cfg.allocation.seenCheckTicks = 5;
        onCleanup(() -> {
            cfg.dormancy.enabled = dormancy;
            cfg.aggro.enabled = aggro;
            cfg.dormancy.distanceBlocks = distance;
            cfg.allocation.graceTicks = grace;
            cfg.allocation.dwellTicks = dwell;
            cfg.allocation.intervalTicks = interval;
            cfg.allocation.seenCheckTicks = seenCheck;
        });
    }

    /** A death drops the inventory and the experience like in a survival world, whatever the world's rules say. */
    void vanillaDeathRules() {
        GameRules rules = server.overworld().getGameRules();
        boolean keep = rules.get(GameRules.KEEP_INVENTORY);
        rules.set(GameRules.KEEP_INVENTORY, false, server);
        onCleanup(() -> rules.set(GameRules.KEEP_INVENTORY, keep, server));
    }

    /** Item and experience entities already lying around when the scene starts (an earlier test's leftovers): never counted. */
    private final Set<UUID> groundBefore = new java.util.HashSet<>();

    void buildPlatform() {
        groundBefore.clear();
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, aroundTheCell(16))) {
            groundBefore.add(e.getUUID());
        }
        for (ExperienceOrb e : level.getEntitiesOfClass(ExperienceOrb.class, aroundTheCell(16))) {
            groundBefore.add(e.getUUID());
        }
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                level.setBlock(botFeet.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 6; dy++) {
                    level.setBlock(botFeet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    /** The human: a survival player standing {@code dx} blocks east of the bot, looking WEST at it (or away from it). */
    void createHuman(double dx, boolean lookingAtTheBot) {
        human = ctx.makeMockServerPlayerInLevel();
        human.setGameMode(GameType.SURVIVAL);
        if (!human.connection.hasClientLoaded()) {
            human.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        }
        human.setHealth(human.getMaxHealth());
        human.setNoGravity(true);
        placeHuman(dx, lookingAtTheBot);
    }

    void placeHuman(double dx, boolean lookingAtTheBot) {
        Vec3 spot = Vec3.atBottomCenterOf(botFeet).add(dx, 0, 0);
        human.teleportTo(level, spot.x, spot.y, spot.z, Set.of(), lookingAtTheBot ? 90.0F : -90.0F, 0.0F, true);
    }

    /** The human flies {@code dx} blocks east (no chunk has to load: the platform's own chunks reach that far). */
    void moveHuman(double dx) {
        Vec3 spot = Vec3.atBottomCenterOf(botFeet).add(dx, 0, 0);
        human.teleportTo(level, spot.x, spot.y, spot.z, Set.of(), human.getYRot(), human.getXRot(), true);
    }

    boolean requestInhabitant() {
        if (requested) {
            return true;
        }
        CommandServices services = services();
        if (services == null) {
            return false;
        }
        IntBox cell = new IntBox(botFeet.getX(), botFeet.getY(), botFeet.getZ(), botFeet.getX(), botFeet.getY(), botFeet.getZ());
        EngineControl.ProcessOutcome outcome = services.engine().process(
                new StructureSnapshot(key(), Set.of(), cell, List.of(), true), ForceMode.OCCUPIED);
        if (outcome.kind() != EngineControl.ProcessOutcome.Kind.OCCUPIED_QUEUED) {
            fail("the engine did not queue the inhabitant: " + outcome);
        }
        requested = true;
        return true;
    }

    Optional<StructureRecord> record() {
        CommandServices services = services();
        return services == null ? Optional.empty() : services.population().find(key());
    }

    /** One line about the structure's record for a failure message. */
    String describeRecord() {
        Optional<StructureRecord> r = record();
        if (r.isEmpty()) {
            return "no record";
        }
        StringBuilder sb = new StringBuilder("record status=" + r.get().status + " planned=" + r.get().plannedBots + " dead=" + r.get().deadCount() + " bots=[");
        for (BotRecord b : r.get().bots) {
            sb.append(b.index).append(":").append(b.name).append(":").append(b.state).append(b.seen ? ":seen" : "").append(b.removing ? ":removing" : "").append(" ");
        }
        return sb.append("]").toString();
    }

    /** The record of the one bot the structure has ever had at index {@code index}, or null. */
    BotRecord botRecord(int index) {
        return record().flatMap(r -> r.bots.stream().filter(b -> b.index == index).findFirst()).orElse(null);
    }

    /** True once the inhabitant is online and recorded SPAWNED; sets {@link #bot}/{@link #botName} (survival, like a real server). */
    boolean inhabitantReady() {
        Optional<StructureRecord> record = record();
        if (record.isEmpty() || record.get().bots.isEmpty()) {
            return false;
        }
        BotRecord planned = record.get().bots.stream().filter(b -> b.state == BotState.SPAWNED).findFirst().orElse(null);
        if (planned == null) {
            BotRecord any = record.get().bots.get(0);
            if (any.state == BotState.FAILED) {
                fail("the inhabitant could not be spawned: " + any.failure);
            }
            return false;
        }
        ServerPlayer entity = server.getPlayerList().getPlayerByName(planned.name);
        if (entity == null) {
            return false;
        }
        bot = entity;
        if (bot.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) {
            bot.setGameMode(GameType.SURVIVAL);
        }
        botName = planned.name;
        return true;
    }

    /** Puts a few distinct items and some experience on the bot, and returns what it now carries (item id to count). */
    Map<String, Integer> dress() {
        Inventory inv = bot.getInventory();
        inv.clearContent();
        inv.setItem(0, new ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
        inv.setItem(1, new ItemStack(net.minecraft.world.item.Items.ARROW, 37));
        inv.setItem(2, new ItemStack(net.minecraft.world.item.Items.GOLDEN_APPLE, 3));
        inv.setItem(3, new ItemStack(net.minecraft.world.item.Items.DIAMOND, 11));
        inv.setItem(38, new ItemStack(net.minecraft.world.item.Items.IRON_CHESTPLATE));
        inv.setSelectedSlot(0);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.experienceLevel = 30;
        bot.totalExperience = 825;
        bot.experienceProgress = 0.0F;
        return counts(bot);
    }

    /** Item id to total count over the whole inventory (main, hotbar, armor, offhand). */
    static Map<String, Integer> counts(ServerPlayer p) {
        Map<String, Integer> out = new TreeMap<>();
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                out.merge(stack.getItem().toString(), stack.getCount(), Integer::sum);
            }
        }
        return out;
    }

    /** Item id to count of the item entities lying within {@code radius} blocks of the bot's platform cell. */
    Map<String, Integer> itemsOnTheGround(double radius) {
        Map<String, Integer> out = new TreeMap<>();
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, aroundTheCell(radius))) {
            if (groundBefore.contains(e.getUUID())) {
                continue;
            }
            out.merge(e.getItem().getItem().toString(), e.getItem().getCount(), Integer::sum);
        }
        return out;
    }

    int orbsOnTheGround(double radius) {
        int n = 0;
        for (ExperienceOrb e : level.getEntitiesOfClass(ExperienceOrb.class, aroundTheCell(radius))) {
            if (!groundBefore.contains(e.getUUID())) {
                n += e.getValue();
            }
        }
        return n;
    }

    private AABB aroundTheCell(double radius) {
        return AABB.ofSize(Vec3.atCenterOf(botFeet), radius * 2, radius * 2, radius * 2);
    }
}
